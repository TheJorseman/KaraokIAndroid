"""Print the Windows default audio playback device."""
import subprocess
r = subprocess.run(
    ["powershell", "-NoProfile", "-Command", "(Get-AudioDevice -Playback).Name"],
    capture_output=True, text=True,
)
print("Default playback device:", r.stdout.strip() or "(none reported)")
r2 = subprocess.run(
    [
        "powershell", "-NoProfile", "-Command",
        "Get-CimInstance Win32_SoundDevice | Where-Object { $_.Status -eq 'OK' -and $_.DeviceID -like '*audio*' } | Select-Object -ExpandProperty Name",
    ],
    capture_output=True, text=True,
)
print("All OK audio devices:")
print(r2.stdout)
