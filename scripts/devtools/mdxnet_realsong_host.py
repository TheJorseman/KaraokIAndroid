"""Compare MDX-Net KARA_2 output on the host for the real song.

Feeds the actual `te_juro_que_te_amo.mp3` through the same MDX-Net
pipeline used on Android and reports the vocal estimate's RMS/peak
and sparsity, so we can tell whether the sparse-spike output we see
on the emulator is a model problem or a platform (XNNPACK/CPU)
problem.
"""
from __future__ import annotations

import os
from pathlib import Path

import numpy as np

CACHE = Path(os.environ.get("KARAOKEI_CACHE", r"C:\Users\migue\Downloads\hf-cache"))
GRAPH = CACHE / "UVR_MDXNET_KARA_2.onnx"
SONG = Path(r"C:\Users\migue\OneDrive\Documentos\GitHub\KaraokIAndroid\Te Juro Que Te Amo.mp3")

N_FFT = 4096
HOP = 512
EXPECTED_BINS = N_FFT // 2
MDX_FRAMES = 256
SR = 16_000


def main() -> int:
    import soundfile as sf
    import onnxruntime as ort

    pcm, sr = sf.read(str(SONG), always_2d=True)
    mono = pcm.mean(axis=1).astype(np.float32)
    if sr != SR:
        # crude linear resample
        g = np.gcd(sr, SR)
        import scipy.signal
        mono = scipy.signal.resample_poly(mono, SR // g, sr // g).astype(np.float32)

    sid = ort.InferenceSession(str(GRAPH), providers=["CPUExecutionProvider"])

    window = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N_FFT) / (N_FFT - 1))
    n_frames = (len(mono) - N_FFT) // HOP + 1
    n_bins = N_FFT // 2 + 1
    spec = np.zeros((n_frames, n_bins), dtype=np.complex64)
    for f in range(n_frames):
        s = f * HOP
        frame = mono[s:s + N_FFT] * window if s + N_FFT <= len(mono) else np.pad(mono[s:], (0, N_FFT - (len(mono) - s))) * window
        spec[f] = np.fft.rfft(frame)

    # Run one 256-frame chunk and reconstruct the vocal estimate.
    actual = min(MDX_FRAMES, n_frames)
    payload = np.zeros((1, 4, EXPECTED_BINS, MDX_FRAMES), dtype=np.float32)
    for f in range(actual):
        for k in range(EXPECTED_BINS):
            re = spec[f, k].real
            im = spec[f, k].imag
            mag = np.sqrt(re * re + im * im)
            phase = np.arctan2(im, re)
            payload[0, 0, k, f] = re
            payload[0, 1, k, f] = im
            payload[0, 2, k, f] = mag
            payload[0, 3, k, f] = phase

    out = sid.run(None, {"input": payload})[0]
    print(f"out shape={out.shape}  max abs={np.max(np.abs(out)):.4f}  nan={np.isnan(out).any()}  inf={np.isinf(out).any()}")

    # Reconstruct the vocal estimate (channels 0,1 = real, imag).
    vocals_spec = (out[0, 0, :, :actual] + 1j * out[0, 1, :, :actual]).astype(np.complex64)
    # iSTFT
    n_out = (actual - 1) * HOP + N_FFT
    voc = np.zeros(n_out, dtype=np.float32)
    wsum = np.zeros(n_out, dtype=np.float32)
    for f in range(actual):
        frame = np.fft.irfft(np.concatenate([vocals_spec[:, f], np.zeros(N_FFT - n_bins, dtype=np.complex64)]), n=N_FFT).astype(np.float32) * window
        s = f * HOP
        voc[s:s + N_FFT] += frame
        wsum[s:s + N_FFT] += window * window
    nz = wsum > 1e-9
    voc[nz] /= wsum[nz]

    rms = float(np.sqrt(np.mean(voc ** 2)))
    peak = float(np.max(np.abs(voc)))
    nz_ratio = float(np.mean(np.abs(voc) > 0.001))
    print(f"vocals estimate: RMS={rms:.5f} peak={peak:.4f} nonzero_ratio={nz_ratio:.3f}")
    print(f"input mix RMS={float(np.sqrt(np.mean(mono**2))):.5f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
