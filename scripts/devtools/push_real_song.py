"""Push `scripts/fixtures/te_juro_que_te_amo.mp3` to a connected Android
emulator / device, into a directory the user can import from the
app's "Import song" flow.

Usage (PowerShell on Windows):

    $env:PYTHONPATH = "."
    & "C:\Users\migue\anaconda3\python.exe" -m scripts.devtools.push_real_song

Defaults:
- emulator-5554
- /sdcard/Music/Te_Juro_Que_Te_Amo.mp3

Override with --serial and --remote-path.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

FIXTURE = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "te_juro_que_te_amo.mp3"
)


def _run(args: list[str]) -> None:
    print(">>", " ".join(args))
    res = subprocess.run(args, capture_output=True, text=True)
    if res.stdout:
        print(res.stdout)
    if res.returncode != 0:
        if res.stderr:
            print(res.stderr, file=sys.stderr)
        raise SystemExit(res.returncode)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--remote-path", default="/sdcard/Music/Te_Juro_Que_Te_Amo.mp3")
    parser.add_argument("--fixture", default=str(FIXTURE))
    args = parser.parse_args()

    fixture = Path(args.fixture)
    if not fixture.exists():
        print(f"fixture missing: {fixture}", file=sys.stderr)
        return 2
    if shutil.which("adb") is None:
        print("adb not found on PATH", file=sys.stderr)
        return 3

    remote_dir = str(Path(args.remote_path).parent)
    _run(["adb", "-s", args.serial, "shell", "mkdir", "-p", remote_dir])
    _run(["adb", "-s", args.serial, "push", str(fixture), args.remote_path])
    _run(["adb", "-s", args.serial, "shell", "ls", "-la", args.remote_path])
    print(f"pushed {fixture} -> {args.serial}:{args.remote_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
