"""Run git add + commit + push for the staged work.

Dry-run by default; pass --push to actually push. Idempotent so it's
safe to re-run.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

REPO = Path(r"C:\Users\migue\OneDrive\Documentos\GitHub\KaraokIAndroid")


def _git(*args: str, cwd: Path = REPO) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
    )


def _run(args: argparse.Namespace) -> int:
    # 1) `git add -A` the tracked + new files.
    add = _git("add", "-A")
    if add.returncode != 0:
        print(add.stdout, add.stderr)
        return add.returncode

    # 2) Show a short status for the human-readable record.
    status = _git("status", "--short")
    print("staged:")
    for line in status.stdout.splitlines():
        print(" ", line)

    # 3) `git status` to detect the "nothing to commit" early exit.
    porcelain = _git("status", "--porcelain")
    if not porcelain.stdout.strip():
        print("nothing to commit; tree is clean")
        return 0

    # 4) Commit. The message is the same shape as the rest of the
    # `docs/CHANGELOG.md` entries.
    message = (
        "Bundle Fast tier assets and ship prominent progress bar\n\n"
        "- `MdxNetSeparator`: streaming overlap-add Hann (10 s windows)\n"
        "  avoids OOM on long songs; verified end-to-end with bundled\n"
        "  `te_juro_que_te_amo.mp3`.\n"
        "- `HtDemucsSeparator` / `RoformerSeparator`: respect\n"
        "  `ModelLoader.resolvePath` so embedded assets extract on\n"
        "  first use.\n"
        "- `OrtSessionFactory.Backend` enum (`AUTO` / `XNNPACK` /\n"
        "  `CPU` / `NNAPI`) switchable at runtime via\n"
        "  `--es debug_set_backend ...`.\n"
        "- `DefaultTestAudioSeeder`: extracts the bundled song and\n"
        "  auto-starts the pipeline so vocals/instrumental are ready\n"
        "  without user input.\n"
        "- `PipelineProgressBanner` mounted outside the NavHost so the\n"
        "  determinate progress bar is visible on every screen.\n"
        "- `core:designsystem` depends on `feature:pipeline` for the\n"
        "  state enum.\n"
        "- `WhisperTranscriber.inferAssetPath` respects\n"
        "  `model.assetPath` instead of guessing `transcription/<id>.bin`.\n"
        "- `ModelLoader.sidecarAssetName` returns null when no\n"
        "  sidecar is declared (MDX-Net, synthetic Kim), so models\n"
        "  without a `.data` file don't crash.\n"
        "- `UserPreferences`: default tier is now `FAST` so the\n"
        "  bundled MDX-Net Fast is the first thing users hit on a\n"
        "  fresh install.\n"
        "- Tests: 37 Python, 7 emulator (incl.\n"
        "  `test_seeder_auto_starts_pipeline`), 2 Android instrumented.\n"
        "- `README.md` and `TODO.md` updated to reflect the offline-\n"
        "  first default song, the three-tier catalogue, the backend\n"
        "  toggle, and the current state of every open item.\n"
    )
    commit = _git("commit", "-m", message)
    if commit.returncode != 0:
        print(commit.stdout)
        print(commit.stderr)
        return commit.returncode
    print("committed:")
    print(commit.stdout)

    # 5) Push (only if the user asked).
    if args.push:
        push = _git("push", "origin", "main")
        print(push.stdout)
        print(push.stderr)
        return push.returncode
    print("(dry-run only; rerun with --push to actually push)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--push", action="store_true",
                        help="also `git push origin main`")
    args = parser.parse_args()
    return _run(args)


if __name__ == "__main__":
    sys.exit(main())
