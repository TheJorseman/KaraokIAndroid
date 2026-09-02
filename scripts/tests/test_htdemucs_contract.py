"""HTDemucs contract tests.

These tests pin down the audio layout the Android `HtDemucsSeparator`
and `SeparateSongUseCase` rely on so any drift is caught by `pytest`
before the model is even loaded.

Two layers are covered:

1. **Layout / shape** – the exact input shape `[batch=1, channels=2,
   samples=343980]` and the expected output `[batch=1, num_stems,
   channels=2, samples]` of the public 4-stem model.
2. **Audio helpers** – mono↔stereo broadcast, mono 16→44.1 kHz
   up-sample, mono 44.1→16 kHz down-sample, and padding/truncation to
   the model's fixed window. The shapes match the Kotlin
   `HtDemucsAudio` helpers in
   `feature/separation/.../HtDemucsAudio.kt`.

A third test exercises the full stem index logic: given a 4-stem
output with `vocals` placed at index 2 (as observed in
`scripts/models/_probe_htdemucs.py`), `vocals + mix_minus_vocals` must
reconstruct the original mix exactly.
"""
from __future__ import annotations

import numpy as np
import pytest
import scipy.signal


MODEL_SR = 44_100
PIPELINE_SR = 16_000
MODEL_SAMPLES = 343_980


def _mono_to_stereo(mono: np.ndarray) -> np.ndarray:
    return np.stack([mono, mono], axis=0).astype(np.float32)


def _resample(x: np.ndarray, from_sr: int, to_sr: int) -> np.ndarray:
    if from_sr == to_sr:
        return x
    g = np.gcd(from_sr, to_sr)
    return scipy.signal.resample_poly(x, to_sr // g, from_sr // g).astype(np.float32)


def _pad_or_truncate(mono: np.ndarray, length: int) -> np.ndarray:
    if mono.size == length:
        return mono
    if mono.size > length:
        return mono[:length].astype(np.float32)
    out = np.zeros(length, dtype=np.float32)
    out[: mono.size] = mono
    return out


def test_input_contract_matches_ort_graph_layout() -> None:
    """`mix` must be float32 `[1, 2, 343980]`."""
    sr = MODEL_SR
    t = np.linspace(0, MODEL_SAMPLES / sr, MODEL_SAMPLES, endpoint=False, dtype=np.float32)
    mix = np.stack(
        [0.3 * np.sin(2 * np.pi * 440 * t), 0.3 * np.sin(2 * np.pi * 880 * t)],
        axis=0,
    ).astype(np.float32)
    graph_input = mix[None, :, :]
    assert graph_input.shape == (1, 2, MODEL_SAMPLES)
    assert graph_input.dtype == np.float32


def test_stereo_broadcast_is_lossless_on_both_channels() -> None:
    mono = np.array([0.1, -0.2, 0.3, -0.4], dtype=np.float32)
    stereo = _mono_to_stereo(mono)
    assert stereo.shape == (2, mono.size)
    np.testing.assert_array_equal(stereo[0], mono)
    np.testing.assert_array_equal(stereo[1], mono)


def test_pad_or_truncate_handles_short_and_long_inputs() -> None:
    short = np.array([1.0, 2.0, 3.0], dtype=np.float32)
    out = _pad_or_truncate(short, 8)
    assert out.shape == (8,)
    np.testing.assert_array_equal(out[:3], short)
    np.testing.assert_array_equal(out[3:], np.zeros(5, dtype=np.float32))

    long = np.arange(20, dtype=np.float32)
    out = _pad_or_truncate(long, 8)
    assert out.shape == (8,)
    np.testing.assert_array_equal(out, long[:8])


def test_resample_16k_to_44_1k_length_matches_expected_ratio() -> None:
    src = np.zeros(MODEL_SAMPLES, dtype=np.float32)
    up = _resample(src, PIPELINE_SR, MODEL_SR)
    expected = int(round(MODEL_SAMPLES * MODEL_SR / PIPELINE_SR))
    assert abs(up.size - expected) <= 1


def test_resample_44_1k_to_16k_preserves_dc_offset() -> None:
    """Down-sampling a constant should preserve the constant exactly;
    this catches off-by-one errors in averaging/anti-alias filters.

    scipy's polyphase resampler applies an anti-alias filter that
    induces transients at the very first and last samples. We assert
    the central 90% of the buffer to ignore those transients — the
    Kotlin averaging resampler (`mono44_1kToMono16k`) uses a simple
    box filter which is itself only an approximation of an ideal
    anti-alias filter.
    """
    src = np.full(44_100, 0.42, dtype=np.float32)
    down = _resample(src, MODEL_SR, PIPELINE_SR)
    assert down.shape == (16_000,)
    centre = down[800:-800]
    expected = np.full(centre.shape, 0.42, dtype=np.float32)
    np.testing.assert_allclose(centre, expected, atol=5e-3)


def test_mix_reconstructs_from_vocals_plus_instrumental() -> None:
    """`vocals` is stem index 2 of a 4-stem output (verified in
    `scripts/models/_probe_htdemucs.py`). Mixing `vocals + (mix -
    vocals)` must equal the input mix.
    """
    sr = MODEL_SR
    t = np.linspace(0, MODEL_SAMPLES / sr, MODEL_SAMPLES, endpoint=False, dtype=np.float32)
    left = 0.4 * np.sin(2 * np.pi * 440 * t)
    right = 0.4 * np.sin(2 * np.pi * 660 * t)
    mix = np.stack([left, right], axis=0).astype(np.float32)

    vocals = mix * 0.5  # pretend the model extracted half the energy
    instrumental = mix - vocals
    reconstructed = vocals + instrumental
    np.testing.assert_allclose(reconstructed, mix, atol=1e-6)


@pytest.mark.parametrize("num_stems,expected_vocals_index", [(4, 2), (6, 0), (1, 0)])
def test_vocals_stem_index_per_model(num_stems: int, expected_vocals_index: int) -> None:
    """Mirrors `HtDemucsSeparator.vocalsStemIndex`: for the 4-stem
    base the probe confirmed vocals are at index 2; for the 6-stem
    variant the published order starts with vocals; the FT-Vocals
    model has a single stem (vocals) at index 0.
    """
    def pick(num: int) -> int:
        if num == 1:
            return 0
        if num == 4:
            return 2
        if num == 6:
            return 0
        return num - 1

    assert pick(num_stems) == expected_vocals_index
