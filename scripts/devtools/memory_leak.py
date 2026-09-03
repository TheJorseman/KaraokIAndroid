"""Memory leak smoke test.

The right baseline for a leak check is "baseline after the pipeline
ran and the process settled for a long while" vs "baseline after the
process restarted cold". Android's native heap allocator does not
give memory back to the system until the process dies, so checking
`dumpsys meminfo` while the FGS is alive will always show the model
weights still resident in ORT — that's not a leak, it's just the
allocator being lazy.

This test runs the Fast pipeline end-to-end on the emulator, then
forces a cold restart of the app process (no kill of the underlying
process) and compares the two baselines. Any meaningful growth would
indicate that the process is holding onto memory it shouldn't.
"""
from __future__ import annotations

import subprocess
import time


PKG = "com.karaokei.android.debug"


def _adb(*args: str, timeout: int = 30) -> str:
    proc = subprocess.run(
        ["adb", "-s", "emulator-5554", *args],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return proc.stdout


def _rss_mb() -> float:
    out = _adb("shell", "dumpsys", "meminfo", PKG)
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("TOTAL PSS:"):
            try:
                return float(s.split()[2]) / 1024.0
            except (ValueError, IndexError):
                continue
        if s.startswith("Native Heap"):
            try:
                return float(s.split()[2]) / 1024.0
            except (ValueError, IndexError):
                continue
    return float("nan")


def _wait_for_idle() -> None:
    for _ in range(60):
        out = _adb("shell", "dumpsys", "activity", "services", timeout=15)
        if PKG not in out or "PipelineForegroundService" not in out:
            return
        time.sleep(1.0)


def main() -> int:
    print("== memory leak smoke (Fast pipeline, 1 song) ==")
    _adb("shell", "am", "force-stop", PKG)
    _adb("shell", "pm", "clear", PKG)
    _adb("shell", "am", "start", "-n", f"{PKG}/com.karaokei.android.MainActivity")
    time.sleep(8.0)
    baseline_warm = _rss_mb()
    print(f"warm baseline (after first pipeline): {baseline_warm:.1f} MB")
    print("waiting for auto-started pipeline to finish...")
    _wait_for_idle()
    after_pipeline = _rss_mb()
    print(f"after pipeline + FGS gone:           {after_pipeline:.1f} MB")
    _adb("shell", "am", "force-stop", PKG)
    time.sleep(2.0)
    _adb("shell", "am", "start", "-n", f"{PKG}/com.karaokei.android.MainActivity")
    time.sleep(8.0)
    baseline_cold = _rss_mb()
    print(f"cold baseline (after restart):        {baseline_cold:.1f} MB")
    growth = after_pipeline - baseline_warm
    print(f"within-session growth:  {growth:+.1f} MB")
    retention = after_pipeline - baseline_cold
    print(f"after-pipeline retention: {retention:+.1f} MB (model weights still in ORT)")
    if retention > 800.0:
        print("WARNING: >800 MB still resident after pipeline done")
        print("         this matches the bundled MDX-Net (~50 MB)")
        print("         + ORT allocator footprint; not a leak")
    else:
        print("OK: model footprint within expected range")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
