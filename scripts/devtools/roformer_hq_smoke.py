"""Smoke-test RoFormer HQ on host CPU.

Reports the input/output shape, the per-chunk latency, and the peak
RSS so we can compare against the published Snapdragon 8 Gen 2 / M2
numbers in `docs/perf-comparison.md`.
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

CACHE = Path(os.environ.get("KARAOKEI_CACHE", r"C:\Users\migue\Downloads\hf-cache"))
GRAPH = CACHE / "roformer-hq.onnx"
DATA = CACHE / "roformer-hq.onnx.data"
SAMPLE_RATE = 44_100
N_FFT = 2048
HOP = 441
EXPECTED_FRAMES = 1101
CHUNK_SAMPLES = HOP * (EXPECTED_FRAMES - 1)  # 1100 * 441 = 485100
WINDOW = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N_FFT) / (N_FFT - 1))


def main() -> int:
    if not GRAPH.exists() or not DATA.exists():
        print(f"graph or data missing: {GRAPH} / {DATA}")
        return 1
    import onnxruntime as ort

    sid = ort.InferenceSession(
        str(GRAPH),
        providers=["CPUExecutionProvider"],
    )
    print("inputs:", [(i.name, i.shape, i.type) for i in sid.get_inputs()])
    print("outputs:", [(o.name, o.shape, o.type) for o in sid.get_outputs()])

    # ~11-second mono test signal with two tones so the spectrum
    # has real content across all bins (matches the 1101-frame
    # contract exactly).
    pcm = 0.5 * np.sin(2 * np.pi * 440 * np.arange(CHUNK_SAMPLES) / SAMPLE_RATE).astype(np.float32)
    pcm += 0.3 * np.sin(2 * np.pi * 1500 * np.arange(CHUNK_SAMPLES) / SAMPLE_RATE).astype(np.float32)
    pcm = pcm / (max(1.0, float(np.max(np.abs(pcm)))) * 1.1)

    # Pad the chunk so STFT lands on the contracted frame count (1101).
    if pcm.size < CHUNK_SAMPLES:
        pcm = np.concatenate([pcm, np.zeros(CHUNK_SAMPLES - pcm.size, dtype=np.float32)])

    def stft(samples: np.ndarray) -> np.ndarray:
        n_frames = (samples.size - N_FFT) // HOP + 1
        out = np.zeros((n_frames, N_FFT // 2 + 1), dtype=np.complex64)
        for f in range(n_frames):
            s = f * HOP
            frame = samples[s:s + N_FFT] * WINDOW if s + N_FFT <= samples.size else np.pad(samples[s:], (0, N_FFT - (samples.size - s))) * WINDOW
            out[f] = np.fft.rfft(frame)
        return out

    spec = stft(pcm)
    print(f"spec: {spec.shape}  dtype={spec.dtype}")

    # Pack the same way RoformerSeparator builds the input: per-bin
    # pair (left real, left imag, right real, right imag) interleaved
    # so the 2050-bin dim is `(numBins=1025) × 2 channels`. shape
    # [1, 2050, 1101, 2] where index `2*k` is left-channel real, `2*k+1`
    # is right-channel real (and the trailing `, 1` is the imag axis).
    num_bins = spec.shape[1]  # 1025 with n_fft=2048
    num_frames = EXPECTED_FRAMES
    actual = min(spec.shape[0], num_frames)
    payload = np.zeros((1, 2 * num_bins, num_frames, 2), dtype=np.float32)
    for f in range(actual):
        for k in range(num_bins):
            re = spec[f, k].real
            im = -spec[f, k].imag  # numpy convention
            payload[0, 2 * k, f, 0] = re
            payload[0, 2 * k, f, 1] = im
            payload[0, 2 * k + 1, f, 0] = re
            payload[0, 2 * k + 1, f, 1] = im

    # Warmup + measurement
    for _ in range(2):
        sid.run(None, {"stft_repr": payload})
    t0 = time.perf_counter()
    out = sid.run(None, {"stft_repr": payload})[0]
    dt = time.perf_counter() - t0
    print(f"inference: {dt:.2f}s on CPU   out max abs={np.max(np.abs(out)):.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
