"""Inspect the pulled emulator DB and dump models rows."""
import sqlite3
import sys

path = sys.argv[1]
con = sqlite3.connect(path)
cur = con.cursor()
print("tables:", [r[0] for r in cur.execute("SELECT name FROM sqlite_master WHERE type='table'")])
for r in cur.execute("SELECT id, tier, type, downloaded_at, local_path FROM models"):
    print(r)
con.close()
