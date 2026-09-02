"""Pull and inspect the KaraokI Android Room database.

Used by the emulator integration test to confirm the catalog sync
runs, the three-tier dispatch is populated, and the debug pipeline
trigger leaves the right rows.
"""
from __future__ import annotations

import sqlite3
import subprocess
import sys
from pathlib import Path


def main(serial: str = "emulator-5554") -> int:
    out_dir = Path(__file__).resolve().parents[1] / "download_cache" / "emulator-dumps"
    out_dir.mkdir(parents=True, exist_ok=True)
    db_combo = out_dir / "karaoke.combined"
    db_target = out_dir / "karaoke.db"
    proc = subprocess.run(
        [
            "adb",
            "-s",
            serial,
            "exec-out",
            "run-as com.karaokei.android.debug sh -c 'cat databases/karaoke.db databases/karaoke.db-wal databases/karaoke.db-shm'",
        ],
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        print(proc.stderr.decode("utf-8", "replace"), file=sys.stderr)
        return proc.returncode
    db_combo.write_bytes(proc.stdout)
    db_target.write_bytes(proc.stdout)
    con = sqlite3.connect(db_target)
    cur = con.cursor()
    print(f"db file size: {len(proc.stdout)} bytes")
    print("tables:")
    for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'"):
        print(" ", r[0])
    print("\nmodels:")
    for r in cur.execute(
        "SELECT id, tier, type, downloaded_at, local_path, url FROM models ORDER BY tier, type"
    ):
        print(" ", r)
    print("\nsongs:")
    for r in cur.execute("SELECT id, title, status, file_uri FROM songs"):
        print(" ", r)
    print("\nprocessing_cache:")
    for r in cur.execute("SELECT song_id, stage, completed_at, output_path FROM processing_cache"):
        print(" ", r)
    con.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(*sys.argv[1:]))
