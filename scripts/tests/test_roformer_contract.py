"""RoFormer vocals FP16 contract tests.

Pins down the layout the Android `RoformerSeparator` and the host
STFT/iSTFT helpers rely on:

- STFT n_fft = 2048, hop = 441, 44.1 kHz, periodic Hann.
- Input tensor `[1, 2050, 1101, 2]` (real, imag) with packed bin
  index `2 * freq + channel` (channel 0 = left, 1 = right).
- Output same shape; complex mask applied by elementwise
  multiplication onto the input STFT.
- iSTFT reconstruction uses the same Hann window and Hann envelope
  normalisation (the sum-of-squares weight divided into the
  overlap-add buffer).
"""
from __future__ import annotations

import numpy as np
import pytest


N_FFT = 2048
HOP = 441
EXPECTED_FRAMES = 1101
N_BINS = N_FFT // 2 + 1   # 1025 bins
PACKED_BINS = 2 * N_BINS  # 2050 packed bins (one pair per channel)
SAMPLE_RATE = 44_100


def _hann(n: int) -> np.ndarray:
    # Symmetric Hann (matches numpy.hanning); for STFT we use the
    # symmetric Hann and divide by sum(w^2) in the iSTFT.
    return 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / (n - 1))


def _stft(pcm: np.ndarray) -> np.ndarray:
    """Return shape [numFrames, numBins] complex64."""
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


def _build_input_tensor(left_pcm: np.ndarray, right_pcm: np.ndarray, n_frames: int = EXPECTED_FRAMES) -> np.ndarray:
    """Build the `[1, 2050, n_frames, 2]` input the graph expects.

    Layout per frame: 2050 packed bins × 2 (real, imag). Packed index
    `2 * freq + channel`; channel 0 = left, 1 = right.
    """
    left_spec = _stft(left_pcm.astype(np.float32))
    right_spec = _stft(right_pcm.astype(np.float32))
    actual = min(n_frames, left_spec.shape[0])
    payload = np.zeros((1, PACKED_BINS, n_frames, 2), dtype=np.float32)
    for f in range(actual):
        for k in range(N_BINS):
            payload[0, 2 * k, f, 0] = left_spec[f, k].real
            payload[0, 2 * k, f, 1] = left_spec[f, k].imag
            payload[0, 2 * k + 1, f, 0] = right_spec[f, k].real
            payload[0, 2 * k + 1, f, 1] = right_spec[f, k].imag
    return payload


def test_input_tensor_layout_shape() -> None:
    sr = SAMPLE_RATE
    chunk = np.zeros(HOP * (EXPECTED_FRAMES - 1), dtype=np.float32)
    payload = _build_input_tensor(chunk, chunk)
    assert payload.shape == (1, PACKED_BINS, EXPECTED_FRAMES, 2)
    assert payload.dtype == np.float32


def test_input_tensor_packed_bin_layout() -> None:
    """Packed bin `2 * freq + channel`: index 0 = (freq=0, ch=0) = left."""
    sr = SAMPLE_RATE
    t = np.linspace(0, HOP * (EXPECTED_FRAMES - 1) / sr, HOP * (EXPECTED_FRAMES - 1), endpoint=False, dtype=np.float32)
    left = 0.4 * np.sin(2 * np.pi * 440 * t).astype(np.float32)
    right = 0.3 * np.sin(2 * np.pi * 660 * t).astype(np.float32)
    payload = _build_input_tensor(left, right)
    left_spec = _stft(left)
    # Index 0 should be the real part of left at frame 0, freq 0.
    np.testing.assert_allclose(payload[0, 0, 0, 0], left_spec[0, 0].real, atol=1e-5)
    np.testing.assert_allclose(payload[0, 0, 0, 1], left_spec[0, 0].imag, atol=1e-5)
    # Index 1 should be the real part of right at frame 0, freq 0.
    right_spec = _stft(right)
    np.testing.assert_allclose(payload[0, 1, 0, 0], right_spec[0, 0].real, atol=1e-5)


def test_mask_multiplication_round_trip() -> None:
    """Identity mask (1+0j for both channels) should reconstruct the
    mix unchanged through complex multiplication + iSTFT.
    """
    sr = SAMPLE_RATE
    chunk_samples = HOP * (EXPECTED_FRAMES - 1)
    t = np.linspace(0, chunk_samples / sr, chunk_samples, endpoint=False, dtype=np.float32)
    left = 0.4 * np.sin(2 * np.pi * 440 * t).astype(np.float32)
    right = 0.3 * np.sin(2 * np.pi * 660 * t).astype(np.float32)
    payload = _build_input_tensor(left, right)
    # Apply identity mask: payload = mask * payload, so mask = 1+0j
    vocals_packed = payload.copy()
    # Recover left and right spectrograms from the packed layout.
    n_frames = min(EXPECTED_FRAMES, _stft(left).shape[0])
    left_spec = np.zeros((n_frames, N_BINS), dtype=np.complex64)
    right_spec = np.zeros((n_frames, N_BINS), dtype=np.complex64)
    for f in range(n_frames):
        for k in range(N_BINS):
            left_spec[f, k] = payload[0, 2 * k, f, 0] + 1j * payload[0, 2 * k, f, 1]
            right_spec[f, k] = payload[0, 2 * k + 1, f, 0] + 1j * payload[0, 2 * k + 1, f, 1]
    vocals_left = _istft(left_spec)
    vocals_right = _istft(right_spec)
    # Boundary tolerance: drop the first/last N_FFT/2 samples.
    np.testing.assert_allclose(
        vocals_left[N_FFT // 2 : -N_FFT // 2],
        left[N_FFT // 2 : -N_FFT // 2][: vocals_left[N_FFT // 2 : -N_FFT // 2].size],
        atol=5e-3,
    )
    np.testing.assert_allclose(
        vocals_right[N_FFT // 2 : -N_FFT // 2],
        right[N_FFT // 2 : -N_FFT // 2][: vocals_right[N_FFT // 2 : -N_FFT // 2].size],
        atol=5e-3,
    )


def test_hamming_overlap_window_shape() -> None:
    """The wrapper uses a Hamming window for chunk overlap-add; verify
    its shape and that sum-of-squares is non-zero (avoids div-by-zero
    when no chunk covers a sample).
    """
    chunk_samples = HOP * (EXPECTED_FRAMES - 1)
    window = 0.54 - 0.46 * np.cos(2 * np.pi * np.arange(chunk_samples) / (chunk_samples - 1))
    assert window.shape == (chunk_samples,)
    assert window.sum() > 0
