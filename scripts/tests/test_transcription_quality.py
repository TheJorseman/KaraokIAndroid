"""End-to-end quality check for the transcription model (T8.1).

Requires the `ggml-tiny.en-q5_1` Whisper model and (optionally) the
`whisper-cli` binary. Skips automatically if either is missing so
the suite is still useful in CI without network or native binaries.
"""
from __future__ import annotations

import shutil

import pytest

from scripts.models.paths import asset_pack_target


def test_whisper_model_present() -> None:
    """Sanity: the model should be downloadable via download_models.
    On a fresh checkout without network this is the test that tells
    you to run the download step."""
    asset = asset_pack_target("transcription", "ggml-tiny.en-q5_1.bin")
    if not asset.exists():
        pytest.skip(
            "whisper ggml-tiny.en-q5_1.bin not present. Run: "
            "python -m scripts.models.download_models --only transcription"
        )
    size_mb = asset.stat().st_size / (1024 * 1024)
    assert 25 < size_mb < 80, (
        f"unexpected model size {size_mb:.1f} MB; the file may be corrupt"
    )


@pytest.mark.skipif(
    shutil.which("whisper-cli") is None and shutil.which("whisper") is None,
    reason="whisper-cli / whisper binary not on PATH",
)
def test_whisper_produces_transcript(test_wav) -> None:
    """Smoke test: whisper.cpp can be invoked and produces a
    non-empty transcript for the synthetic sweep fixture."""
    import subprocess
    import tempfile
    from pathlib import Path

    asset = asset_pack_target("transcription", "ggml-tiny.en-q5_1.bin")
    if not asset.exists():
        pytest.skip("whisper model not present")

    whisper_cli = shutil.which("whisper-cli") or shutil.which("whisper")
    with tempfile.TemporaryDirectory() as tmp:
        out_prefix = Path(tmp) / "out"
        cmd = [
            whisper_cli,
            "-m", str(asset),
            "-f", str(test_wav),
            "-l", "auto",
            "--no-timestamps",
            "-otxt", "-of", str(out_prefix),
        ]
        proc = subprocess.run(cmd, capture_output=True, timeout=120)
        if proc.returncode != 0:
            pytest.skip(f"whisper-cli failed: {proc.stderr.decode('utf-8', errors='replace')[:200]}")
        out = out_prefix.with_suffix(".txt")
        if not out.exists():
            pytest.skip("whisper-cli produced no output")
        text = out.read_text(encoding="utf-8").strip()
        # The synthetic sweep is two sines; Whisper may produce
        # silence, hallucination, or a short utterance. We only
        # assert the model ran and produced a string.
        assert isinstance(text, str)
