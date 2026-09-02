"""End-to-end smoke test for the HTDemucs 6-stem ONNX graph.

Downloads the model file from Hugging Face into the local cache
(`download_cache/htdemucs-6s/`), then runs a single forward pass on
a synthetic 343980-sample @ 44.1 kHz mix and reports the stem
energies. This is the same contract the Android `HtDemucsSeparator`
relies on; if this script prints six stems with non-trivial energy,
the Kotlin wrapper can drive the model too.

Usage::

    PYTHONPATH=. KMP_DUPLICATE_LIB_OK=TRUE \
        python -m scripts.devtools.htdemucs_smoke
"""
from __future__ import annotations

import os
import shutil
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

REPO_ROOT = Path(__file__).resolve().parents[2]
CACHE_DIR = REPO_ROOT / "scripts" / "download_cache" / "htdemucs-6s"
MODEL_URL = (
    "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/"
    "htdemucs_6s_fp16weights.onnx"
)
MODEL_FILENAME = "htdemucs_6s_fp16weights.onnx"
TARGET_SAMPLES = 343_980
SAMPLE_RATE = 44_100


def _download() -> Path:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    target = CACHE_DIR / MODEL_FILENAME
    if target.exists() and target.stat().st_size > 1_000_000:
        print(f"reusing cached model: {target} ({target.stat().st_size} bytes)")
        return target
    if shutil.which("curl") is None:
        raise SystemExit("curl not found on PATH")
    print(f"downloading {MODEL_URL} -> {target}")
    import subprocess

    res = subprocess.run(
        [
            "curl",
            "--fail",
            "--location",
            "--silent",
            "--show-error",
            "-o",
            str(target),
            MODEL_URL,
        ],
        check=False,
    )
    if res.returncode != 0:
        target.unlink(missing_ok=True)
        raise SystemExit(f"curl exited with {res.returncode}")
    return target


def _synth_mix() -> np.ndarray:
    t = np.linspace(0, TARGET_SAMPLES / SAMPLE_RATE, TARGET_SAMPLES, endpoint=False, dtype=np.float32)
    left = 0.4 * np.sin(2 * np.pi * 440 * t) + 0.2 * np.sin(2 * np.pi * 1500 * t)
    right = 0.3 * np.sin(2 * np.pi * 660 * t) + 0.2 * np.sin(2 * np.pi * 2000 * t)
    return np.stack([left, right], axis=0).astype(np.float32)


def main() -> int:
    path = _download()
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    mix = _synth_mix()
    print(f"mix shape: {mix.shape}  dtype: {mix.dtype}")
    t0 = time.perf_counter()
    out = session.run(None, {"mix": mix[None, :, :]})[0]
    dt = time.perf_counter() - t0
    print(f"inference: {dt:.2f}s on CPUExecutionProvider")
    print(f"output shape: {out.shape}  dtype: {out.dtype}")
    stems = out[0]
    energies = [float(np.mean(s ** 2)) for s in stems]
    total = sum(energies) or 1.0
    labels = ["vocals", "drums", "bass", "guitar", "piano", "other"]
    for i, e in enumerate(energies):
        label = labels[i] if i < len(labels) else f"stem_{i}"
        print(f"stem {i} ({label}): energy={e:.6f}  share={e/total:.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
