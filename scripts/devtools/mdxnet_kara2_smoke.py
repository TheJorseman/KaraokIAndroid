"""Smoke test for the UVR MDX-Net Karaoke 2 ONNX graph.

Runs a single chunk on a synthetic mix to confirm the I/O contract
the Android `MdxNetSeparator` wrapper relies on. The wrapper converts
the model's per-chunk vocal estimate back to time domain via the host
STFT/iSTFT.
"""
from __future__ import annotations

import os
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

CACHE_DIR = Path(os.environ.get("KARAOKEI_CACHE", Path.home() / ".cache" / "karaokei"))
GRAPH = CACHE_DIR / "UVR_MDXNET_KARA_2.onnx"
SAMPLE_RATE = 16_000
N_FFT = 4096
HOP = 512
N_BINS = N_FFT // 2 + 1  # 2049 bins for n_fft=4096; model uses bins 0..2047
EXPECTED_BINS = 2048       # graph contract: bins 0..2047 (drops bin 2048)
WINDOW_SAMPLES = 10 * SAMPLE_RATE
HOP_SAMPLES = 5 * SAMPLE_RATE
FRAMES = (WINDOW_SAMPLES - N_FFT) // HOP + 1


def _hann(n: int) -> np.ndarray:
    return 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / (n - 1))


def _stft(pcm: np.ndarray) -> np.ndarray:
    window = _hann(N_FFT)
    n_frames = (len(pcm) - N_FFT) // HOP + 1
    out = np.zeros((n_frames, N_BINS), dtype=np.complex64)
    for f in range(n_frames):
        start = f * HOP
        frame = pcm[start : start + N_FFT] * window
        out[f] = np.fft.rfft(frame)
    return out


def _istft(spec: np.ndarray) -> np.ndarray:
    window = _hann(N_FFT)
    n_frames = spec.shape[0]
    length = (n_frames - 1) * HOP + N_FFT
    out = np.zeros(length, dtype=np.float32)
    weight = np.zeros(length, dtype=np.float32)
    for f in range(n_frames):
        start = f * HOP
        frame = np.fft.irfft(spec[f], n=N_FFT).astype(np.float32) * window
        out[start : start + N_FFT] += frame
        weight[start : start + N_FFT] += window * window
    nz = weight > 1e-9
    out[nz] /= weight[nz]
    return out


def main() -> int:
    if not GRAPH.exists():
        print(f"graph missing: {GRAPH}")
        return 1
    so = ort.SessionOptions()
    so.log_severity_level = 3
    session = ort.InferenceSession(str(GRAPH), sess_options=so, providers=["CPUExecutionProvider"])
    t = np.linspace(0, WINDOW_SAMPLES / SAMPLE_RATE, WINDOW_SAMPLES, endpoint=False, dtype=np.float32)
    mix = 0.3 * np.sin(2 * np.pi * 440 * t) + 0.2 * np.sin(2 * np.pi * 1500 * t)
    spec = _stft(mix)
    chunk_frames = 256
    chunk_count = (spec.shape[0] + chunk_frames - 1) // chunk_frames
    vocals_full = np.zeros_like(spec)
    for c in range(chunk_count):
        s = c * chunk_frames
        e = min(s + chunk_frames, spec.shape[0])
        actual = e - s
        payload = np.zeros((1, 4, EXPECTED_BINS, chunk_frames), dtype=np.float32)
        for f in range(actual):
            for k in range(EXPECTED_BINS):
                re = spec[s + f, k].real
                im = spec[s + f, k].imag
                mag = np.sqrt(re * re + im * im)
                phase = np.arctan2(im, re)
                payload[0, 0, k, f] = re
                payload[0, 1, k, f] = im
                payload[0, 2, k, f] = mag
                payload[0, 3, k, f] = phase
        t0 = time.perf_counter()
        out = session.run(None, {"input": payload})[0]
        dt = time.perf_counter() - t0
        print(f"chunk {c + 1}/{chunk_count}: {dt:.2f}s on CPU  out shape={out.shape}")
        for f in range(actual):
            re = out[0, 0, :, f]
            im = out[0, 1, :, f]
            # Pad back to 2049 bins (the original STFT width) with zeros
            # for the dropped highest frequency.
            full = np.zeros(N_BINS, dtype=np.complex64)
            full[:EXPECTED_BINS] = re.astype(np.float32) + 1j * im.astype(np.float32)
            if f == 0:
                print(f"   full.shape={full.shape} vocals_full.shape={vocals_full.shape}")
            vocals_full[s + f] = full

    vocals_pcm = _istft(vocals_full)
    energy_in = float((mix ** 2).mean())
    energy_out = float((vocals_pcm ** 2).mean())
    print(f"mix RMS={np.sqrt(energy_in):.4f}  vocals RMS={np.sqrt(energy_out):.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
