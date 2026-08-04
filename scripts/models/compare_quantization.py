"""Compare FP16 vs INT8 RoFormer outputs on a synthetic STFT.

Generates a 6-second stereo sine mix at 44.1 kHz, computes its STFT
packed exactly as the RoFormer contract expects, runs both models,
and reports cosine similarity, mean and max absolute error. Caller
decides whether the INT8 quality loss is acceptable.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort


def stereo_sine(seconds: float, rate: int) -> np.ndarray:
    t = np.linspace(0, seconds, int(rate * seconds), endpoint=False, dtype=np.float32)
    # Mix several harmonics so the spectral mask has more structure
    # than a single sine. Stereo so the channels packing is exercised.
    left = (
        0.20 * np.sin(2 * np.pi * 220.0 * t) +
        0.10 * np.sin(2 * np.pi * 440.0 * t) +
        0.05 * np.sin(2 * np.pi * 880.0 * t)
    ).astype(np.float32)
    right = (
        0.20 * np.sin(2 * np.pi * 330.0 * t) +
        0.10 * np.sin(2 * np.pi * 550.0 * t) +
        0.05 * np.sin(2 * np.pi * 990.0 * t)
    ).astype(np.float32)
    return np.stack([left, right], axis=-1)


def stft_packed(pcm: np.ndarray, n_fft: int, hop: int) -> np.ndarray:
    """Per-channel STFT returning [channels, n_freq, n_frames, 2]."""
    window = np.hanning(n_fft + 1)[:-1].astype(np.float32)
    out = []
    for ch in range(pcm.shape[1]):
        pad = np.pad(pcm[:, ch], (n_fft // 2, n_fft // 2), mode="reflect")
        n_frames = 1 + (len(pad) - n_fft) // hop
        frames = np.stack(
            [pad[i * hop : i * hop + n_fft] * window for i in range(n_frames)]
        )
        spec = np.fft.rfft(frames, axis=1)
        out.append(np.stack([spec.real, spec.imag], axis=-1).astype(np.float32))
    return np.stack(out, axis=0)


def to_roformer_input(stft_packed: np.ndarray, n_freq_target: int) -> np.ndarray:
    """[channels, n_freq, n_frames, 2] -> [1, n_freq*channels, n_frames, 2].

    RoFormer packs bins as `(2 * freq + channel)` per the upstream
    model card, so we interleave the complex channels along the bin
    axis. The output axis 1 must be exactly `n_freq * channels`.
    """
    # stft_packed is produced by stft_packed() as
    # [channels, frames, freq, complex]. Normalize it here to
    # [channels, freq, frames, complex] before packing.
    if stft_packed.shape[1] != n_freq_target and stft_packed.shape[2] == n_freq_target:
        stft_packed = stft_packed.transpose(0, 2, 1, 3)
    channels, n_freq, n_frames, _ = stft_packed.shape
    n_freq = min(n_freq, n_freq_target)
    re = stft_packed[:, :n_freq, :, 0]  # [channels, n_freq, n_frames]
    im = stft_packed[:, :n_freq, :, 1]
    # RoFormer contract: bin[k = 2 * f + ch], real+imag stacked at axis -1.
    # einsum produces [channels, n_freq, n_frames] with the axis order
    # (ch, f) which when reshaped to (channels * n_freq,) yields the
    # interleaved order expected by the model.
    # RoFormer contract: bin[k = 2 * f + ch], real+imag stacked at axis -1.
    # `np.reshape(..., order='F')` flattens the (n_freq, channels)
    # axes in column-major order, which matches `2 * f + ch`.
    permuted_re = re.transpose(1, 0, 2)  # [n_freq, channels, n_frames]
    permuted_im = im.transpose(1, 0, 2)
    packed_re = permuted_re.reshape(n_freq * channels, n_frames, order="F")
    packed_im = permuted_im.reshape(n_freq * channels, n_frames, order="F")
    out = np.stack([packed_re, packed_im], axis=-1)  # [n_freq*channels, n_frames, 2]
    return out[np.newaxis, ...]


def run_session(graph: Path, stft_input: np.ndarray, input_name: str) -> np.ndarray:
    session = ort.InferenceSession(str(graph), providers=["CPUExecutionProvider"])
    out = session.run(None, {input_name: stft_input})[0]
    return out


def metrics(a: np.ndarray, b: np.ndarray) -> dict:
    a = a.flatten().astype(np.float64)
    b = b.flatten().astype(np.float64)
    denom = (np.linalg.norm(a) * np.linalg.norm(b)) + 1e-12
    cosine = float(np.dot(a, b) / denom)
    max_abs = float(np.max(np.abs(a - b)))
    mean_abs = float(np.mean(np.abs(a - b)))
    return {
        "cosine_similarity": cosine,
        "max_abs_diff": max_abs,
        "mean_abs_diff": mean_abs,
    }


def run(fp16: Path, int8: Path, sample_seconds: float = 30.0) -> dict:
    rate = 44_100
    n_fft = 2048
    hop = 441
    pcm = stereo_sine(sample_seconds, rate)
    stft = stft_packed(pcm, n_fft=n_fft, hop=hop)

    sess = ort.InferenceSession(str(fp16), providers=["CPUExecutionProvider"])
    input_name = sess.get_inputs()[0].name
    expected_shape = sess.get_inputs()[0].shape
    expected_n_freq = expected_shape[1] // 2
    expected_n_frames = expected_shape[2]
    # Pad / trim frames to the exact count the model expects.
    # stft_packed() returns [channels, frames, freq, complex].
    n_frames = stft.shape[1]
    if n_frames < expected_n_frames:
        pad = np.zeros(
            (stft.shape[0], expected_n_frames - n_frames, stft.shape[2], 2),
            dtype=stft.dtype,
        )
        stft = np.concatenate([stft, pad], axis=1)
    elif n_frames > expected_n_frames:
        stft = stft[:, :expected_n_frames, :, :]

    x = to_roformer_input(stft, expected_n_freq)
    print(f"x shape: {x.shape} (expected {[1, expected_n_freq * 2, expected_n_frames, 2]})")
    out_fp16 = run_session(fp16, x, input_name)
    try:
        out_int8 = run_session(int8, x, input_name)
    except Exception as error:
        return {
            "verdict": "REJECT_INT8",
            "reason": "INT8 graph cannot be loaded by ONNX Runtime",
            "error": str(error),
            "fp16_output_shape": list(out_fp16.shape),
        }
    result = metrics(out_fp16, out_int8)
    result["verdict"] = "KEEP_INT8" if result["cosine_similarity"] >= 0.999 and result["mean_abs_diff"] <= 0.01 else "KEEP_FP16"
    return result


if __name__ == "__main__":
    repo = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--fp16",
        type=Path,
        default=repo / "scripts" / "download_cache" / "roformer-vocals" / "syhft_core_folded_fp16_webgpu.onnx",
    )
    parser.add_argument(
        "--int8",
        type=Path,
        default=repo / "scripts" / "build_output" / "roformer-int8" / "syhft_core_folded_int8.onnx",
    )
    args = parser.parse_args()
    print(json.dumps(run(args.fp16, args.int8), indent=2))
