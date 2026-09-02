"""Probe the Mel-Band RoFormer vocals FP16 graph.

Reads input/output names, exact input shapes, and runs a synthetic
forward pass to verify the ORT contract that the Android
`RoformerSeparator` and the host STFT/iSTFT helpers must satisfy.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

CACHE_DIR = Path(os.environ.get("KARAOKEI_CACHE", Path.home() / ".cache" / "karaokei"))
GRAPH = CACHE_DIR / "syhft_core_folded_fp16_webgpu.onnx"
SAMPLE_RATE = 44_100


def _ensure_graph() -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    if GRAPH.exists():
        return GRAPH
    print(f"downloading RoFormer FP16 graph -> {GRAPH}")
    subprocess.run(
        [
            "curl",
            "--fail",
            "--location",
            "--silent",
            "--show-error",
            "-o",
            str(GRAPH),
            "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/main/syhft_core_folded_fp16_webgpu.onnx",
        ],
        check=True,
    )
    return GRAPH


def main() -> int:
    path = _ensure_graph()
    so = ort.SessionOptions()
    so.log_severity_level = 3
    session = ort.InferenceSession(str(path), sess_options=so, providers=["CPUExecutionProvider"])
    print("inputs:")
    for inp in session.get_inputs():
        info = session.get_input_details() if hasattr(session, "get_input_details") else None
        print(f"  name={inp.name}  shape={inp.shape}  type={inp.type}")
    print("outputs:")
    for out in session.get_outputs():
        print(f"  name={out.name}  shape={out.shape}  type={out.type}")

    sample_input_meta = session.get_inputs()[0]
    shape = [1 if dim is None or isinstance(dim, str) else int(dim) for dim in sample_input_meta.shape]
    print(f"resolved sample shape: {shape}")
    rng = np.random.default_rng(0)
    payload = rng.standard_normal(shape).astype(np.float32)
    print(f"running inference with shape {payload.shape} ({payload.nbytes / 1024:.1f} KB)...")
    t0 = time.perf_counter()
    out = session.run(None, {sample_input_meta.name: payload})[0]
    dt = time.perf_counter() - t0
    print(f"inference: {dt:.2f}s on CPUExecutionProvider")
    print(f"output shape: {out.shape}  dtype: {out.dtype}")
    if out.size:
        flat = out.reshape(-1)
        print(f"output: min={flat.min():.4f} max={flat.max():.4f} mean={flat.mean():.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
