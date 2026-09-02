"""Standalone script for testing the emulator DB dump."""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, r"C:\Users\migue\OneDrive\Documentos\GitHub\KaraokIAndroid")

from scripts.tests.test_emulator_integration import adb_runas_db_dump  # noqa: E402

with tempfile.TemporaryDirectory() as td_str:
    td = Path(td_str)
    print(f"tmp: {td}")
    with adb_runas_db_dump("emulator-5554", td) as con:
        cur = con.cursor()
        print("tables:", [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'")])
        try:
            rows = list(cur.execute("SELECT id, tier, type FROM models"))
            print(f"models ({len(rows)}):")
            for r in rows:
                print(" ", r)
        except Exception as e:
            print("models error:", e)
        try:
            print("songs:", list(cur.execute("SELECT id, title, status FROM songs")))
        except Exception as e:
            print("songs error:", e)
        try:
            print("cache:", list(cur.execute("SELECT song_id, stage, output_path FROM processing_cache")))
        except Exception as e:
            print("cache error:", e)
