"""Quantize the RoFormer graph to INT8 with a static calibration set.

Lightweight wrapper around `onnxruntime.quantization.quantize_static`
that runs in a single thread and emits an output inside the asset
pack. Used by the SDR comparison script to decide whether INT8 is
viable; if SDR drops more than 2 dB relative to FP16 we keep the FP16
weights as the production file.
"""
from __future__ import annotations

import argparse
import json
import time
import wave
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort

from scripts.models._calibration import AudioCalibrationReader
from scripts.models.paths import file_size_str
from scripts.models.quantize_onnx import quantize

GRAPH_IN_NAME = "syhft_core_folded_fp16_webgpu.onnx"
GRAPH_OUT_NAME = "syhft_core_folded_int8.onnx"
TARGET_INPUT_SHAPE = (1, 2050, 1101, 2)
CALIBRATION_DIR_NAME = "_calibration_stereo"


def ensure_calibration(target_dir: Path) -> Path:
    cal = target_dir / CALIBRATION_DIR_NAME
    cal.mkdir(parents=True, exist_ok=True)
    if any(cal.glob("*.wav")):
        return cal
    print(f"[calib] synthesizing stereo 44.1 kHz WAVs in {cal}")
    for i in range(4):
        seconds = 6
        rate = 44_100
        t = np.linspace(0, seconds, rate * seconds, endpoint=False, dtype=np.float32)
        left = (0.2 * np.sin(2 * np.pi * 220 * t)).astype(np.float32)
        right = (0.2 * np.sin(2 * np.pi * 330 * t)).astype(np.float32)
        stereo = np.stack([left, right], axis=-1)
        with wave.open(str(cal / f"calib_{i:02d}.wav"), "wb") as h:
            h.setnchannels(2); h.setsampwidth(2); h.setframerate(rate)
            h.writeframes(stereo.tobytes())
    return cal


def run(cache_root: Path, target_root: Path) -> dict:
    src = cache_root / GRAPH_IN_NAME
    if not src.exists():
        raise SystemExit(f"missing source graph at {src}")
    target_root.mkdir(parents=True, exist_ok=True)
    cal = ensure_calibration(target_root)
    out = target_root / GRAPH_OUT_NAME
    print(f"[quant] reading {src}")
    start = time.time()
    # Dynamic quantization is dramatically faster than static
    # quantization on a 707 MB ONNX graph (the latter takes hours
    # because it runs the FP32 graph multiple times for calibration).
    # We accept a small quality loss in exchange for build time.
    quantize(src, out, strategy="dynamic")
    elapsed = time.time() - start
    sidecar = target_root / f"{GRAPH_OUT_NAME}.data"
    # Validate before writing a production manifest. ORT currently
    # rejects this FP16 graph when DynamicQuantizeLinear is inserted;
    # never publish an invalid INT8 artifact.
    try:
        ort.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    except Exception as error:
        out.unlink(missing_ok=True)
        raise SystemExit(
            "INT8 RoFormer graph is not loadable by ONNX Runtime; "
            "keep FP16 instead: " + str(error)
        ) from error

    manifest = {
        "model_id": "mel-band-roformer-vocals-int8",
        "source": "silverdaw/mel-band-roformer-vocals-onnx",
        "license": "MIT",
        "quantization": {"method": "dynamic", "target": "INT8", "elapsed_seconds": elapsed},
        "files": {
            GRAPH_OUT_NAME: {
                "size_bytes": out.stat().st_size,
                "human_size": file_size_str(out),
            },
            f"{GRAPH_OUT_NAME}.data": {
                "size_bytes": sidecar.stat().st_size if sidecar.exists() else 0,
                "human_size": file_size_str(sidecar) if sidecar.exists() else "-",
            },
        },
    }
    (target_root / "manifest_int8.json").write_text(
        json.dumps(manifest, indent=2) + "\n"
    )
    print(f"[quant] wrote {out} ({file_size_str(out)})")
    if sidecar.exists():
        print(f"[quant] wrote {sidecar} ({file_size_str(sidecar)})")
    return manifest


if __name__ == "__main__":
    repo = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path,
                        default=repo / "scripts" / "download_cache" / "roformer-vocals")
    parser.add_argument("--target", type=Path,
                        default=repo / "scripts" / "build_output" / "roformer-int8")
    args = parser.parse_args()
    print(json.dumps(run(args.cache, args.target), indent=2))
