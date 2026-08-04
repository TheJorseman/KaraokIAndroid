"""Pytest fixtures and configuration for scripts.tests.

Adds the project root to sys.path so `import scripts.models...` works
from any working directory, and exposes shared audio fixtures.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))


@pytest.fixture(scope="session")
def repo_root() -> Path:
    return REPO_ROOT


@pytest.fixture(scope="session")
def test_wav(repo_root: Path) -> Path:
    """A small deterministic WAV used by every test in this folder.

    Generated once per session. 2 s of two superimposed sine sweeps at
    16 kHz, mono. Two sweeps with non-overlapping frequency bands so
    any mask-based separation model has something to separate.
    """
    target = repo_root / "scripts" / "fixtures" / "test_sweep.wav"
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        sr = 16_000
        t = np.linspace(0, 2.0, int(2.0 * sr), endpoint=False, dtype=np.float32)
        a = 0.3 * np.sin(2 * np.pi * np.linspace(220, 880, t.size) * t)
        b = 0.3 * np.sin(2 * np.pi * np.linspace(1500, 4000, t.size) * t)
        sf.write(str(target), a + b, sr, subtype="PCM_16")
    return target


@pytest.fixture(scope="session")
def calibration_dir(repo_root: Path) -> Path:
    """A directory of small WAVs for static INT8 calibration."""
    target = repo_root / "scripts" / "fixtures" / "calibration"
    if target.exists() and any(target.glob("*.wav")):
        return target
    target.mkdir(parents=True, exist_ok=True)
    sr = 16_000
    rng = np.random.default_rng(42)
    for i in range(8):
        # Each file is 1 s of a randomly-modulated sine at a different
        # base frequency. Different content per file -> better
        # activation range coverage in the calibration step.
        base = 200.0 * (1 + i)
        t = np.linspace(0, 1.0, sr, endpoint=False, dtype=np.float32)
        env = 0.2 + 0.1 * rng.standard_normal(sr).cumsum()
        env = (env - env.min()) / (env.max() - env.min() + 1e-9) * 0.5
        audio = (env * np.sin(2 * np.pi * base * t)).astype(np.float32)
        sf.write(str(target / f"calib_{i:02d}.wav"), audio, sr, subtype="PCM_16")
    return target
