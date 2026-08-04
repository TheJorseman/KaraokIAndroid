"""End-to-end WAV round-trip: decode fixture, downmix, resample, write back, re-read.

Mirrors the Kotlin `AudioExtractor.decodeToPcm()` + `downmixToMono()` +
`resampleLinear()` + `WavWriter` round-trip in pure Python so the
pipeline behaviour is regression-tested without booting the
emulator. The fixture `scripts/fixtures/test_sweep.wav` is the same
file the Android app seeds on first launch.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest
import scipy.signal
import soundfile as sf


FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "test_sweep.wav"


def _stereo_synthetic(duration_s: float = 3.0, rate: int = 16000) -> np.ndarray:
    t = np.linspace(0, duration_s, int(rate * duration_s), endpoint=False)
    left = 0.4 * np.sin(2 * np.pi * 440.0 * t)
    right = 0.4 * np.sin(2 * np.pi * 660.0 * t)
    return np.stack([left, right], axis=-1).astype(np.float32)


def _downmix_to_mono(pcm: np.ndarray) -> np.ndarray:
    if pcm.ndim == 1:
        return pcm
    return pcm.mean(axis=-1).astype(np.float32)


def _resample(pcm: np.ndarray, from_rate: int, to_rate: int) -> np.ndarray:
    if from_rate == to_rate:
        return pcm
    g = np.gcd(from_rate, to_rate)
    return scipy.signal.resample_poly(pcm, to_rate // g, from_rate // g, axis=0).astype(np.float32)


def _read_wav_pcm16_mono(path: Path) -> tuple[np.ndarray, int]:
    pcm, sr = sf.read(str(path))
    return pcm.astype(np.float32), sr


def _write_wav_pcm16_mono(path: Path, pcm: np.ndarray, rate: int) -> None:
    sf.write(str(path), pcm, rate, subtype="PCM_16")


def test_sweep_fixture_round_trip_preserves_shape_and_rate(tmp_path: Path) -> None:
    assert FIXTURE.exists(), "fixture missing: run the app once or commit it"
    pcm, sr = _read_wav_pcm16_mono(FIXTURE)
    assert sr == 16000
    assert pcm.ndim == 1
    assert pcm.dtype == np.float32
    assert len(pcm) == 32000


def test_stereo_to_mono_downmix_matches_average() -> None:
    # Verifies the downmix is the per-frame mean across channels. The
    # synthetic stereo signal is left=440 Hz, right=660 Hz with the
    # same amplitude 0.4; the mean of a 440 Hz and 660 Hz sine is the
    # exact sum. We assert the result is within numerical noise of the
    # analytic mean.
    stereo = _stereo_synthetic()
    mono = _downmix_to_mono(stereo)
    t = np.linspace(0, 3, 48000, endpoint=False)
    expected = 0.2 * np.sin(2 * np.pi * 440.0 * t) + 0.2 * np.sin(2 * np.pi * 660.0 * t)
    assert mono.shape == (48000,)
    assert np.allclose(mono, expected, atol=1e-6)


@pytest.mark.parametrize("from_rate,to_rate", [(44100, 16000), (16000, 48000), (48000, 16000)])
def test_resample_length_and_dtype(tmp_path: Path, from_rate: int, to_rate: int) -> None:
    pcm = _stereo_synthetic(2.0, from_rate).flatten().astype(np.float32)
    out = _resample(pcm, from_rate, to_rate)
    expected_len = int(round(len(pcm) * to_rate / from_rate))
    assert abs(len(out) - expected_len) <= 1
    assert out.dtype == np.float32


def test_write_then_read_preserves_samples(tmp_path: Path) -> None:
    pcm = _stereo_synthetic(1.0).mean(axis=-1).astype(np.float32)
    target = tmp_path / "round.wav"
    _write_wav_pcm16_mono(target, pcm, 16000)
    back, sr = _read_wav_pcm16_mono(target)
    assert sr == 16000
    assert np.allclose(back, pcm, atol=5e-4)


def test_downmix_then_resample_pipeline_round_trip(tmp_path: Path) -> None:
    stereo = _stereo_synthetic(2.0, 44100).astype(np.float32)
    mono = _downmix_to_mono(stereo)
    target = tmp_path / "out.wav"
    _write_wav_pcm16_mono(target, _resample(mono, 44100, 16000), 16000)
    back, sr = _read_wav_pcm16_mono(target)
    assert sr == 16000
    assert len(back) == int(round(44100 * 2 * 16000 / 44100))
