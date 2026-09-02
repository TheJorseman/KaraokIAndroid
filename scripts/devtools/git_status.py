"""Inspect repo state, find what's modified, and help craft the commit
message + push."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

REPO = Path(r"C:\Users\migue\OneDrive\Documentos\GitHub\KaraokIAndroid")


def _git(*args: str, cwd: Path = REPO) -> str:
    proc = subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
    )
    return (proc.stdout + proc.stderr).strip()


def main() -> int:
    print("=" * 60)
    print("git status (short):")
    print(_git("status", "--short"))
    print("=" * 60)
    print("git status (long) — first 60 lines:")
    long_status = _git("status")
    for line in long_status.splitlines()[:60]:
        print(line)
    print("=" * 60)
    print("git log --oneline -10:")
    print(_git("log", "--oneline", "-10"))
    print("=" * 60)
    print("git branch --show-current:")
    print(_git("branch", "--show-current"))
    print("=" * 60)
    print("git remote -v:")
    print(_git("remote", "-v"))
    print("=" * 60)
    print("git ls-files --modified:")
    print(_git("ls-files", "--modified"))
    print("=" * 60)
    print("Untracked files (top 30):")
    proc = subprocess.run(
        ["git", "status", "--short", "--untracked"],
        cwd=REPO,
        capture_output=True,
        text=True,
    )
    untracked = [
        line.split()[-1]
        for line in proc.stdout.splitlines()
        if line.startswith("??")
    ]
    for f in untracked[:30]:
        print(" ", f)
    if len(untracked) > 30:
        print(f"  ... and {len(untracked) - 30} more")
    print(f"Total untracked: {len(untracked)}")
    print("=" * 60)
    print("git diff --stat | head -30:")
    diff = _git("diff", "--stat")
    for line in diff.splitlines()[:30]:
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
