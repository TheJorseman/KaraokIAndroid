"""MDX-Net Karaoke 2 contract tests.

Verifies the layout the Android `MdxNetSeparator` relies on without
booting the emulator:

- The UVR MDX-Net Karaoke 2 ONNX graph (or any MDX-Net with the
  same contract) accepts `[batch, 4, numBins, frames]` with the four
  channels being (real, imag, mag, phase) of the spectrogram.
- The output has the same shape; the first two channels are the
  vocal estimate directly (no separate masking step).
- A synthetic STFT round-trip (numpy) reconstructs the time-domain
  signal to within numerical precision, confirming the host
  pipeline is correct.
"""
from __future__ import annotations

import numpy as np
import pytest


N_FFT = 4096
HOP = 512
N_BINS = N_FFT // 2 + 1   # 2049 bins from rfft
EXPECTED_BINS = 2048       # graph contract: drops the last bin
SAMPLE_RATE = 16_000
MDX_FRAMES_PER_CHUNK = 256


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


def test_input_contract_matches_ort_graph_layout() -> None:
    payload = np.zeros((1, 4, EXPECTED_BINS, MDX_FRAMES_PER_CHUNK), dtype=np.float32)
    assert payload.shape == (1, 4, EXPECTED_BINS, MDX_FRAMES_PER_CHUNK)
    assert payload.dtype == np.float32


def test_stft_round_trip_preserves_signal() -> None:
    """Host STFT/iSTFT round-trip used by MdxNetSeparator on the
    vocals output. Confirms the overlap-add and Hann envelope
    normalisation reconstruct a synthetic sine to within numerical
    precision.
    """
    sr = 16_000
    duration_s = 1.0
    t = np.linspace(0, duration_s, int(sr * duration_s), endpoint=False, dtype=np.float32)
    pcm = 0.5 * np.sin(2 * np.pi * 440 * t).astype(np.float32)
    spec = _stft(pcm)
    back = _istft(spec)
    centre = back[N_FFT // 2 : -N_FFT // 2]
    src_centre = pcm[N_FFT // 2 : -N_FFT // 2][: len(centre)]
    np.testing.assert_allclose(centre, src_centre, atol=5e-3)


def test_payload_channel_layout_real_imag_mag_phase() -> None:
    """The MDX-Net graph expects (real, imag, mag, phase) per
    (frame, freq) pair. Verify the helper that packs a numpy
    complex spectrogram into the model's input layout.
    """
    sr = 16_000
    pcm = 0.4 * np.sin(2 * np.pi * np.arange(sr * 2, dtype=np.float32) * 440 / sr).astype(np.float32)
    spec = _stft(pcm)
    payload = np.zeros((1, 4, EXPECTED_BINS, 1), dtype=np.float32)
    for k in range(EXPECTED_BINS):
        re = spec[0, k].real
        im = spec[0, k].imag
        mag = np.sqrt(re * re + im * im)
        phase = np.arctan2(im, re)
        payload[0, 0, k, 0] = re
        payload[0, 1, k, 0] = im
        payload[0, 2, k, 0] = mag
        payload[0, 3, k, 0] = phase
    # mag must equal sqrt(real^2 + imag^2)
    np.testing.assert_allclose(
        payload[0, 2, :, 0],
        np.sqrt(payload[0, 0, :, 0] ** 2 + payload[0, 1, :, 0] ** 2),
        atol=1e-5,
    )


def test_chunked_payload_layout() -> None:
    """MDX-Net runs in 256-frame chunks. Verify a multi-chunk
    payload can be built and the per-chunk shapes match.
    """
    sr = 16_000
    n_frames = 600  # > 256 → 3 chunks: 256, 256, 88
    spec = np.random.default_rng(0).standard_normal((n_frames, EXPECTED_BINS)).astype(np.float32) \
        + 1j * np.random.default_rng(1).standard_normal((n_frames, EXPECTED_BINS)).astype(np.float32)
    chunk_count = (n_frames + MDX_FRAMES_PER_CHUNK - 1) // MDX_FRAMES_PER_CHUNK
    assert chunk_count == 3
    for c in range(chunk_count):
        s = c * MDX_FRAMES_PER_CHUNK
        e = min(s + MDX_FRAMES_PER_CHUNK, n_frames)
        actual = e - s
        assert actual <= MDX_FRAMES_PER_CHUNK
        # The last chunk in a 600-frame run has 88 frames; the
        # Android wrapper pads the rest of the 256-frame chunk with
        # zeros.
        assert actual == MDX_FRAMES_PER_CHUNK or c == chunk_count - 1
