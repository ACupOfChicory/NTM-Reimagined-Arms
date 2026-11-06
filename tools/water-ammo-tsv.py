#!/usr/bin/env python3
"""Writes the water-FX ammo table (ammo.tsv) from notes/water/ammo.json of the main checkout."""
import json
import os

MAIN = os.path.expanduser("~/ntm-gunfeel-project/ntm-gunfeel")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "src/main/resources/assets/ntmgunfeel/water/ammo.tsv")

with open(os.path.join(MAIN, "notes/water/ammo.json"), encoding="utf-8") as f:
    ammo = sorted(json.load(f), key=lambda a: a["id"])
cols = ["id", "cal", "var", "proj", "shape", "d", "nose", "body"]
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("\t".join(cols) + "\n")
    for a in ammo:
        f.write("\t".join(str(a[c]) for c in cols) + "\n")
print(len(ammo), "rows")
