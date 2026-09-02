"""Quick-and-dirty inspection of a pulled Room SQLite file."""
from __future__ import annotations

import sqlite3
import sys
from pathlib import Path

if len(sys.argv) < 2:
    print("usage: inspect_db.py <path-to-db>")
    raise SystemExit(1)
db = Path(sys.argv[1])
con = sqlite3.connect(db)
cur = con.cursor()
print(f"db: {db}  size: {db.stat().st_size} bytes")
print("tables:")
for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'"):
    print(" ", r[0])
print("\nmodels:")
for r in cur.execute("SELECT id, tier, type, downloaded_at, local_path, url FROM models ORDER BY tier, type"):
    print(" ", r)
print("\nsongs:")
for r in cur.execute("SELECT id, title, status, file_uri FROM songs"):
    print(" ", r)
print("\nprocessing_cache:")
for r in cur.execute("SELECT song_id, stage, completed_at, output_path FROM processing_cache"):
    print(" ", r)
con.close()
