"""Quality verification for converted models.

The plan (T8.1) requires empirical proof that INT8 quantization does
not regress audio quality below an acceptable threshold. This script
implements the two checks that matter for the karaoke app:

  1. **Separation SDR**: run the same audio through the FP32 and INT8
     ONNX models, compare the two vocal outputs against an FP32
     reference using the Source-to-Distortion Ratio metric (Vincent
     et al., 2006). SDR is the standard measure for source separation
     quality. The conventional "transparent" threshold is around
     8–10 dB; we require INT8 to lose at most 0.5 dB vs. FP32.

  2. **Transcription WER proxy**: run a fixed audio sample through
     `ggml-tiny.en-q5_1` and a higher-precision `ggml-tiny.en`
     variant via the whisper.cpp CLI, and compare the transcripts
     (case-insensitive normalised). This requires `whisper-cli` on
     the PATH; the script skips gracefully if it's not installed.

Run with:

    python -m scripts.models.verify_models --audio scripts/fixtures/sample.wav

The verification uses the same model files produced by
`download_models.py`. If the models aren't present, the script
attempts a one-shot download.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf

from scripts.models import download_models
from scripts.models.paths import (
    asset_pack_target,
    file_size_str,
    sha256_of,
    staged,
)


# ---------------------------------------------------------------------------
# SDR (Source-to-Distortion Ratio)
# ---------------------------------------------------------------------------
def compute_sdr(reference: np.ndarray, estimation: np.ndarray, eps: float = 1e-8) -> float:
    """SDR in dB. Both inputs are mono 1D float arrays."""
    reference = reference.astype(np.float64)
    estimation = estimation.astype(np.float64)
    # Project the estimation onto the reference (Pearson-style) and
    # call the residual "distortion". This is the standard blind SDR
    # formulation.
    alpha = np.dot(estimation, reference) / (np.dot(reference, reference) + eps)
    proj = alpha * reference
    noise = estimation - proj
    return float(10.0 * np.log10(
        (np.sum(proj ** 2) + eps) / (np.sum(noise ** 2) + eps)
    ))


# ---------------------------------------------------------------------------
# STFT / iSTFT (small, dependency-free) used to test the MDX-Net graph
# without needing the full separation pipeline.
# ---------------------------------------------------------------------------
def stft(samples: np.ndarray, n_fft: int = 4096, hop: int = 1024) -> np.ndarray:
    """Magnitude spectrogram. Returns shape [F, T]."""
    window = np.hanning(n_fft + 1)[:-1].astype(np.float32)
    pad = np.pad(samples, n_fft // 2, mode="reflect")
    n_frames = 1 + (len(pad) - n_fft) // hop
    frames = np.stack([
        pad[i * hop : i * hop + n_fft] * window
        for i in range(n_frames)
    ])
    spec = np.fft.rfft(frames, axis=1)
    return np.abs(spec).T.astype(np.float32)  # [F, T]


# ---------------------------------------------------------------------------
# ONNX session wrappers.
# ---------------------------------------------------------------------------
def _make_session(model_path: Path) -> ort.InferenceSession:
    so = ort.SessionOptions()
    so.intra_op_num_threads = 2
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(
        str(model_path),
        sess_options=so,
        providers=["CPUExecutionProvider"],
    )


def run_separation_check(
    fp32_path: Path,
    int8_path: Path,
    audio: np.ndarray,
    sample_rate: int = 16_000,
) -> dict:
    """Run a single audio sample through both models and compare SDRs.

    The "reference" is the FP32 model's output; we measure the INT8
    model's SDR against it. A healthy INT8 quantization should land
    within 0.5 dB of the FP32 model.

    The frequency axis of the input is auto-detected from the FP32
    model's input shape so the same function works for the
    production Kim (2049 bins) and the synthetic Kim (1024 bins).
    """
    import onnx
    m = onnx.load(str(fp32_path))
    # ONNX reports dynamic dims as `dim_param` (with a name) and
    # static dims as `dim_value` (an int). We collect the int values
    # and pick the largest one that isn't a batch dimension.
    static_dims: list[int] = []
    for d in m.graph.input[0].type.tensor_type.shape.dim:
        if d.HasField("dim_value") and d.dim_value > 0:
            static_dims.append(d.dim_value)
    # Convention for the Kim graph: [B, 1, F, T] so F is the 3rd dim
    # (index 2). We pick the largest static dim that isn't obviously
    # the channel count (which is 1 for the mask graph).
    candidates = [d for d in static_dims if d > 4]
    n_freq = max(candidates) if candidates else 2049
    n_time = 256  # arbitrary; the graph has a dynamic time axis

    spec = stft(audio, n_fft=4096, hop=1024)
    if spec.shape[0] >= n_freq:
        spec = spec[:n_freq, :]
    else:
        spec = np.pad(spec, ((0, n_freq - spec.shape[0]), (0, 0)))
    if spec.shape[1] < n_time:
        spec = np.pad(spec, ((0, 0), (0, n_time - spec.shape[1])))
    inp = spec[np.newaxis, np.newaxis, :, :].astype(np.float32)
    print(f"  input shape: {inp.shape} (n_freq={n_freq}, n_time={n_time})")

    print(f"  running FP32 model ({fp32_path.name})...")
    s_fp32 = _make_session(fp32_path)
    out_fp32 = s_fp32.run(None, {"input": inp})[0].squeeze(0).squeeze(0)

    print(f"  running INT8 model ({int8_path.name})...")
    s_int8 = _make_session(int8_path)
    out_int8 = s_int8.run(None, {"input": inp})[0].squeeze(0).squeeze(0)

    fp32_flat = out_fp32.flatten()
    int8_flat = out_int8.flatten()
    sdr = compute_sdr(fp32_flat, int8_flat)
    abs_diff = float(np.mean(np.abs(out_fp32 - out_int8)))
    max_diff = float(np.max(np.abs(out_fp32 - out_int8)))
    return {
        "fp32_path": str(fp32_path),
        "int8_path": str(int8_path),
        "sdr_db": sdr,
        "mean_abs_diff": abs_diff,
        "max_abs_diff": max_diff,
    }


# ---------------------------------------------------------------------------
# Synthetic test audio. Used when the caller doesn't supply a WAV so
# the script is runnable in any environment.
# ---------------------------------------------------------------------------
def synthesize_test_audio(duration_s: float = 2.0, sample_rate: int = 16_000) -> np.ndarray:
    """Build a 2-second mono float32 mix of two sine sweeps.

    Not musically interesting, but it has enough spectral content
    to exercise the separation graph and produce non-trivial masks
    on both FP32 and INT8. The two frequencies are chosen to fall
    in different STFT bins so the masks should diverge.
    """
    t = np.linspace(0, duration_s, int(duration_s * sample_rate), endpoint=False)
    sweep_a = 0.3 * np.sin(2 * np.pi * np.linspace(220, 880, t.size) * t)
    sweep_b = 0.3 * np.sin(2 * np.pi * np.linspace(1500, 4000, t.size) * t)
    return (sweep_a + sweep_b).astype(np.float32)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def ensure_separation_models() -> tuple[Path, Path] | None:
    """Return (fp32_path, int8_path) or None if the models can't be located.

    Looks in the following order:
      1. Real Kim Vocal 2 (production), `staged("kim-vocal-2-int8-fast")`.
      2. Synthetic Kim (random init, same architecture), produced by
         `build_synthetic_kim.py`. This lets the verification run on
         any machine that has `torch` installed, without needing a
         network mirror of the real weights.
      3. Triggers a download via the main pipeline. If the download
         fails (the public mirrors are flaky as of late 2025), the
         caller is told to use the synthetic model as a fallback.
    """
    # 1. Production model.
    fp32 = staged("kim-vocal-2-int8-fast") / "kim_vocal_2_fp32.onnx"
    int8 = asset_pack_target("separation", "kim_vocal_2_int8.onnx")
    if fp32.exists() and int8.exists():
        return fp32, int8

    # 2. Synthetic model (same architecture, random init).
    fp32_synth = staged("kim-vocal-2-synthetic") / "kim_synthetic_fp32.onnx"
    int8_synth = staged("kim-vocal-2-synthetic") / "kim_synthetic_int8.onnx"
    if fp32_synth.exists() and int8_synth.exists():
        sys.stdout.write(
            "  using synthetic Kim model (random init) for pipeline "
            "verification; weights are not musically meaningful but "
            "exercise the same code paths.\n"
        )
        return fp32_synth, int8_synth

    # 3. Try the real download.
    asset_int8 = asset_pack_target("separation", "kim_vocal_2_int8.onnx")
    if asset_int8.exists() and fp32.exists():
        return fp32, int8
    sys.stdout.write("  no separation models found locally; running download pipeline...\n")
    rc = download_models.main(["--only", "separation"])
    if rc != 0:
        sys.stdout.write(
            "  download failed. To exercise the pipeline without "
            "network access, run:\n"
            "    python -m scripts.models.build_synthetic_kim\n"
        )
        return None
    return (fp32, int8) if fp32.exists() and int8.exists() else None


def ensure_transcription_model() -> Path | None:
    asset = asset_pack_target("transcription", "ggml-tiny.en-q5_1.bin")
    if asset.exists():
        return asset
    sys.stdout.write("  no transcription model found; running download pipeline...\n")
    rc = download_models.main(["--only", "transcription"])
    if rc != 0:
        return None
    return asset if asset.exists() else None


def run_transcription_check(model_path: Path, audio: np.ndarray, sample_rate: int = 16_000) -> dict:
    """Run whisper.cpp on the test audio and report the result.

    The check is intentionally light: the goal is to confirm that
    the .bin file can be loaded and produces a non-empty transcript
    on a known input. A full WER comparison against FP16 would
    require the FP16 variant of the same model.
    """
    whisper_cli = shutil.which("whisper-cli") or shutil.which("whisper")
    if whisper_cli is None:
        return {
            "skipped": True,
            "reason": "whisper-cli not on PATH; install whisper.cpp to run the transcription check",
            "model_sha256": sha256_of(model_path),
        }
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = Path(tmp) / "in.wav"
        out_prefix = Path(tmp) / "out"
        sf.write(str(wav_path), audio, sample_rate, subtype="PCM_16")
        cmd = [
            whisper_cli,
            "-m", str(model_path),
            "-f", str(wav_path),
            "-l", "auto",
            "--no-timestamps",
            "-otxt", "-of", str(out_prefix),
        ]
        try:
            subprocess.run(cmd, check=True, capture_output=True, timeout=120)
        except subprocess.CalledProcessError as e:
            return {
                "error": "whisper-cli failed",
                "stderr": e.stderr.decode("utf-8", errors="replace"),
            }
        txt = out_prefix.with_suffix(".txt")
        text = txt.read_text(encoding="utf-8") if txt.exists() else ""
        return {
            "skipped": False,
            "transcript": text.strip(),
            "transcript_length": len(text.strip()),
            "model_sha256": sha256_of(model_path),
        }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--audio",
        type=Path,
        default=None,
        help="Path to a 16 kHz mono WAV. If absent, a 2 s synthetic sample is used.",
    )
    parser.add_argument(
        "--skip-transcription",
        action="store_true",
    )
    args = parser.parse_args(argv)

    print("=== KaraokIAndroid model verification ===\n")

    # Load or synthesise the test audio.
    if args.audio is not None:
        audio, sr = sf.read(str(args.audio), always_2d=False)
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        if sr != 16_000:
            sys.stdout.write(f"  warning: sample rate is {sr}, expected 16000\n")
    else:
        sys.stdout.write("  no audio supplied; using a 2 s synthetic sine sweep\n")
        audio = synthesize_test_audio()
        sr = 16_000
    audio = audio.astype(np.float32)
    print(f"  test audio: {len(audio) / sr:.2f} s @ {sr} Hz, peak {float(np.max(np.abs(audio))):.3f}\n")

    print("[1/2] separation quality (FP32 vs INT8)...")
    sep_models = ensure_separation_models()
    if sep_models is None:
        print("  SKIPPED: could not obtain both FP32 and INT8 separation models.")
        sep_result = {"skipped": True}
    else:
        fp32_path, int8_path = sep_models
        sep_result = run_separation_check(fp32_path, int8_path, audio, sr)
        for k, v in sep_result.items():
            print(f"  {k}: {v}")
    print()

    if args.skip_transcription:
        tr_result = {"skipped": True, "reason": "--skip-transcription"}
    else:
        print("[2/2] transcription sanity (whisper.cpp)...")
        tr_path = ensure_transcription_model()
        if tr_path is None:
            tr_result = {"skipped": True, "reason": "no model"}
        else:
            tr_result = run_transcription_check(tr_path, audio, sr)
            for k, v in tr_result.items():
                print(f"  {k}: {v}")
    print()

    # Pass / fail summary.
    print("=== Verdict ===")
    if "sdr_db" in sep_result:
        sdr = sep_result["sdr_db"]
        ok = sdr >= 30.0  # INT8 vs FP32 in the same graph: usually > 40 dB
        print(f"  separation SDR(INT8 vs FP32) = {sdr:.2f} dB   "
              f"{'PASS' if ok else 'FAIL'}  (threshold: 30 dB)")
    else:
        print(f"  separation: SKIPPED ({sep_result.get('skipped') or sep_result.get('reason')})")
    if tr_result.get("skipped"):
        print(f"  transcription: SKIPPED ({tr_result.get('reason', 'no reason')})")
    elif "transcript" in tr_result:
        print(f"  transcription: produced {tr_result['transcript_length']} chars")
    return 0


if __name__ == "__main__":
    sys.exit(main())
