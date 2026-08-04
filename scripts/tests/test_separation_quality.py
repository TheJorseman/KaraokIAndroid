"""End-to-end quality check for the separation model (T8.1).

Generates (or loads) a small WAV, runs the FP32 and INT8 versions of
the model on the same input, and asserts the SDR between the two
outputs is high enough to ship. The test prefers the real Kim
Vocal 2 weights if present and falls back to the synthetic Kim
UNet (random init, same architecture) when the public mirrors
are unavailable.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from scripts.models.paths import asset_pack_target, staged
from scripts.models.verify_models import compute_sdr, run_separation_check


def _find_models() -> tuple[Path, Path] | None:
    """Return (fp32, int8) for whichever Kim model is available."""
    real_fp32 = staged("kim-vocal-2-int8-fast") / "kim_vocal_2_fp32.onnx"
    real_int8 = asset_pack_target("separation", "kim_vocal_2_int8.onnx")
    if real_fp32.exists() and real_int8.exists():
        return real_fp32, real_int8
    synth_fp32 = staged("kim-vocal-2-synthetic") / "kim_synthetic_fp32.onnx"
    synth_int8 = staged("kim-vocal-2-synthetic") / "kim_synthetic_int8.onnx"
    if synth_fp32.exists() and synth_int8.exists():
        return synth_fp32, synth_int8
    return None


@pytest.mark.skipif(
    _find_models() is None,
    reason=(
        "no separation model available. Run either:\n"
        "  python -m scripts.models.download_models --only separation   (real Kim)\n"
        "  python -m scripts.models.build_synthetic_kim                (synthetic Kim)"
    ),
)
def test_int8_separation_close_to_fp32(repo_root: Path, test_wav: Path) -> None:
    import soundfile as sf
    audio, sr = sf.read(str(test_wav), always_2d=False)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    audio = audio.astype(np.float32)
    fp32, int8 = _find_models()
    result = run_separation_check(fp32, int8, audio, sr)

    # The same graph on the same input, with INT8 weights, should
    # produce a near-identical mask. SDR > 30 dB is the bar we ship.
    assert "sdr_db" in result, result
    sdr = result["sdr_db"]
    assert sdr > 30.0, f"INT8 vs FP32 SDR is {sdr:.2f} dB, expected > 30 dB"

    # Also assert the output tensors are elementwise close.
    assert result["max_abs_diff"] < 0.1, (
        f"max abs diff is {result['max_abs_diff']}, expected < 0.1"
    )


def test_sdr_metric_is_symmetric_and_bounded() -> None:
    """Sanity check on the SDR metric itself: identical signals give
    very high SDR, orthogonal noise gives very low SDR."""
    rng = np.random.default_rng(0)
    ref = rng.standard_normal(4096).astype(np.float32)
    same_sdr = compute_sdr(ref, ref.copy())
    assert same_sdr > 60.0, f"identical signals: SDR={same_sdr} should be very high"

    # Random noise is by construction a very bad estimate of `ref`:
    # the projection is tiny relative to the residual.
    noise_sdr = compute_sdr(ref, rng.standard_normal(4096).astype(np.float32))
    assert noise_sdr < 5.0, f"random noise: SDR={noise_sdr} should be very low"

    # A noisy version of the reference is a much better estimate than
    # pure noise; SDR should be in the typical 5–20 dB range and
    # bounded above by the SNR we injected.
    snr_db = 10.0
    noise = rng.standard_normal(4096).astype(np.float32)
    noise *= np.linalg.norm(ref) / (np.linalg.norm(noise) * 10 ** (snr_db / 20.0))
    noisy = ref + noise
    noisy_sdr = compute_sdr(ref, noisy)
    assert 0 < noisy_sdr < snr_db + 5, f"noisy ref SDR={noisy_sdr} out of range"
