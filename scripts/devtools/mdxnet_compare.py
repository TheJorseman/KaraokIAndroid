"""Compare MDX-Net KARA_2 vs Voc_FT input contracts so we can pick
the one that matches the Android wrapper (n_fft=6144, hop=512).
"""
from __future__ import annotations

import os
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

CACHE_DIR = Path(os.environ.get("KARAOKEI_CACHE", Path.home() / ".cache" / "karaokei"))


def probe(path: Path, primary: str) -> None:
    print(f"\n=== {path.name} (primary_stem={primary}) ===")
    so = ort.SessionOptions()
    so.log_severity_level = 3
    session = ort.InferenceSession(str(path), sess_options=so, providers=["CPUExecutionProvider"])
    for inp in session.get_inputs():
        print(f"  in : {inp.name}  shape={inp.shape}  type={inp.type}")
    for out in session.get_outputs():
        print(f"  out: {out.name}  shape={out.shape}  type={out.type}")


def main() -> int:
    for name, primary in [
        ("UVR_MDXNET_KARA_2.onnx", "Instrumental"),
        ("UVR-MDX-NET-Voc_FT.onnx", "Vocals"),
    ]:
        p = CACHE_DIR / name
        if p.exists():
            probe(p, primary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
