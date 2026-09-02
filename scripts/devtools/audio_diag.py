"""Diagnose the host's default audio output and let the user pick a
specific device for the QEMU emulator's audio backend.

The emulator's audio plugin (`qemu/audio/sdl`) opens whatever
device SDL_AUDIODRIVER returns. When the default host audio device
is a virtual cable (VoiceMeeter / NVIDIA Virtual / Voice.ai) the
emulator writes to a sink that nothing is listening on, and the user
hears nothing.

Usage::

    PYTHONPATH=. python -m scripts.devtools.audio_diag
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def _powershell(cmd: str) -> str:
    proc = subprocess.run(
        ["powershell", "-NoProfile", "-Command", cmd],
        capture_output=True,
        text=True,
    )
    return proc.stdout


def list_devices() -> None:
    print("=== Windows playback devices (Status = OK) ===")
    print(_powershell(
        "Get-CimInstance Win32_SoundDevice | "
        "Where-Object { $_.Status -eq 'OK' -and $_.DeviceID -like '*audio*' } | "
        "Select-Object Name, DeviceID | Format-Table -AutoSize | Out-String"
    ))


def default_endpoint() -> None:
    print("=== Default audio endpoint (Core Audio API) ===")
    print(_powershell(
        "(New-Object -ComObject Shell.Application).NameSpace(0x11).Items() | "
        "Where-Object { $_.IsFolder -eq $false } | "
        "Select-Object Name, Path | Format-Table -AutoSize | Out-String"
    ))


def suggest_emulator_args() -> None:
    print("=== Suggested QEMU launch options for the emulator ===")
    print("""
The QEMU emulator's audio plugin is `sdl` (default) or `none`
(disabled). The `sdl` backend uses SDL_AUDIO_DRIVER which on Windows
defaults to `directsound`. On Windows 10/11 this routes audio to
whatever the Windows default audio endpoint is.

If your Windows default endpoint is a virtual device (VoiceMeeter
aux, NVIDIA Broadcast, Voice.ai Cable, …) audio leaves the emulator
but never reaches your speakers.

Fixes (in order of preference):
1. Set your Windows default playback device to a real output
   (Speakers, Headphones, HDMI). Verify with::
       PowerShell > (Get-AudioDevice -Playback).Name
2. Or launch the emulator with a specific audio backend via the
   -audio flag (only `-audio none` and `-audio sdl` are supported
   on the OSS emulator today).
3. Or restart the emulator with the audio device exposed::
       adb shell stop audio && adb shell start audio
""")


def stop_start_emulator_audio() -> None:
    print("=== adb commands to bounce the emulator's audio service ===")
    print("    adb shell stop audio; adb shell start audio")


def main() -> int:
    list_devices()
    default_endpoint()
    suggest_emulator_args()
    stop_start_emulator_audio()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
