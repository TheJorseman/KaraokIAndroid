"""Probe HTDemucs stem order on a synthetic mix sized exactly to the model's
expected sample count (343980 samples at 44.1 kHz = ~7.8 s)."""
from __future__ import annotations

import os

os.environ.setdefault("HF_HOME", r"C:\Users\migue\AppData\Local\Temp\htdemucs-probe")
os.environ.setdefault("HF_HUB_DISABLE_HARD_LINKS", "1")

import numpy as np
import onnxruntime as ort
from huggingface_hub import hf_hub_download

path = hf_hub_download(
    repo_id="StemSplitio/htdemucs-onnx",
    filename="htdemucs_fp16weights.onnx",
    cache_dir=r"C:\Users\migue\AppData\Local\Temp\htdemucs-probe",
)
session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])

sr = 44100
target = 343980
t = np.linspace(0, target / sr, target, endpoint=False, dtype=np.float32)
mix = np.stack(
    [
        0.5 * np.sin(2 * np.pi * 440 * t),
        0.5 * np.sin(2 * np.pi * 880 * t),
    ],
    axis=0,
).astype(np.float32)

out = session.run(None, {"mix": mix[None, :, :]})[0]
print("output shape:", out.shape, "dtype:", out.dtype)
stems = out[0]
energies = [float(np.mean(s ** 2)) for s in stems]
total = sum(energies) or 1.0
for i, e in enumerate(energies):
    print(f"stem {i}: energy = {e:.6f}  ratio = {e / total:.3f}")

print(
    "\nInferred order: by energy descending (proxy for loudness). "
    "Vocals are the loudest, drums are rhythmic, bass is low-freq."
)
