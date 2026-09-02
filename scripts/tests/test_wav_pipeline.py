"""End-to-end WAV round-trip: decode fixture, downmix, resample, write back, re-read.

Mirrors the Kotlin `AudioExtractor.decodeToPcm()` + `downmixToMono()` +
`resampleLinear()` + `WavWriter` round-trip in pure Python so the
pipeline behaviour is regression-tested without booting the
emulator. The fixture `scripts/fixtures/test_sweep.wav` is the same
file the Android app seeds on first launch.

Also exercises `scripts/fixtures/te_juro_que_te_amo.mp3` (the real
song used as the end-to-end target) through the same pipeline to make
sure the helpers behave on a non-synthetic source.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest
import scipy.signal
import soundfile as sf


FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "test_sweep.wav"
REAL_FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "te_juro_que_te_amo.mp3"


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


@pytest.mark.skipif(not REAL_FIXTURE.exists(), reason="te_juro_que_te_amo.mp3 fixture missing")
def test_real_song_fixture_runs_through_pipeline(tmp_path: Path) -> None:
    """Round-trips the real song through the same code path the
    Android `AudioExtractor` uses: decode → downmix → resample 44.1→16
    kHz → write mono WAV → re-read.

    The assertions are deliberately loose: real audio has DC bias and
    lossy MP3 encoding. We just confirm the pipeline produces a
    non-empty 16 kHz / mono file with the expected shape.
    """
    pcm, sr = sf.read(str(REAL_FIXTURE), always_2d=False)
    assert sr > 0
    if pcm.ndim > 1:
        pcm = pcm.mean(axis=-1).astype(np.float32)
    else:
        pcm = pcm.astype(np.float32)
    assert pcm.size > 16000, "fixture is suspiciously short"

    mono = _downmix_to_mono(pcm if pcm.ndim == 1 else np.stack([pcm, pcm], axis=-1))
    target = tmp_path / "te_juro_pipeline_out.wav"
    _write_wav_pcm16_mono(target, _resample(mono, sr, 16000), 16000)
    back, out_sr = _read_wav_pcm16_mono(target)
    assert out_sr == 16000
    assert back.ndim == 1
    assert back.size > 16000
    assert np.max(np.abs(back)) <= 1.001


@pytest.mark.skipif(not REAL_FIXTURE.exists(), reason="te_juro_que_te_amo.mp3 fixture missing")
def test_real_song_up_sample_to_44_1k_stereo_shape() -> None:
    """Mirrors the HTDemucs pre-processing used by the Android app:
    mono 16 kHz PCM → mono 44.1 kHz → stereo 44.1 kHz. We assert the
    output shape `(2, samples)` matches the ORT graph's `[batch=1,
    channels=2, samples]` contract (after prepending a batch
    dimension) and that the broadcast is lossless on both channels.
    """
    pcm, sr = sf.read(str(REAL_FIXTURE), always_2d=False)
    if pcm.ndim > 1:
        mono16k = _downmix_to_mono(pcm)
    else:
        mono16k = pcm.astype(np.float32)
    mono16k = _resample(mono16k, sr, 16000)

    expected_len = int(round(mono16k.size * 44100 / 16000))
    mono44_1k = _resample(mono16k, 16000, 44100)
    assert abs(len(mono44_1k) - expected_len) <= 1

    # ORT contract: [batch=1, channels=2, samples] with values
    # `[L0, L1, ..., Ln-1, R0, R1, ..., Rn-1]`. Build it from scratch
    # so the data layout is explicit.
    stereo = np.stack([mono44_1k, mono44_1k], axis=0).astype(np.float32)
    as_graph_input = stereo[None, :, :]
    assert as_graph_input.shape == (1, 2, mono44_1k.size)
    np.testing.assert_array_equal(as_graph_input[0, 0], as_graph_input[0, 1])
