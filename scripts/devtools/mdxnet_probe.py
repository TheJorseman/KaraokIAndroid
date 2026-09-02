"""Probe the UVR MDX-Net Vocals FT ONNX graph.

Reads the I/O contract (names, shapes, dtypes) and verifies a single
random forward pass so the Android `MdxNetSeparator` wrapper can be
built against the real numbers instead of the synthetic model.
"""
from __future__ import annotations

import os
from pathlib import Path

import numpy as np
import onnxruntime as ort

CACHE_DIR = Path(os.environ.get("KARAOKEI_CACHE", Path.home() / ".cache" / "karaokei"))
GRAPH = CACHE_DIR / "UVR-MDX-NET-Voc_FT.onnx"


def main() -> int:
    if not GRAPH.exists():
        print(f"graph missing: {GRAPH}")
        return 1
    so = ort.SessionOptions()
    so.log_severity_level = 3
    session = ort.InferenceSession(str(GRAPH), sess_options=so, providers=["CPUExecutionProvider"])
    for inp in session.get_inputs():
        print(f"input: name={inp.name}  shape={inp.shape}  type={inp.type}")
    for out in session.get_outputs():
        print(f"output: name={out.name}  shape={out.shape}  type={out.type}")

    sample_in = session.get_inputs()[0]
    shape = [1 if dim is None or isinstance(dim, str) else int(dim) for dim in sample_in.shape]
    print(f"probing with shape: {shape}")
    payload = np.random.default_rng(0).standard_normal(shape).astype(np.float32)
    import time
    t0 = time.perf_counter()
    out = session.run(None, {sample_in.name: payload})[0]
    dt = time.perf_counter() - t0
    print(f"inference: {dt:.2f}s  output shape={out.shape}  dtype={out.dtype}")
    print(f"output min={out.min():.4f} max={out.max():.4f} mean={out.mean():.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
