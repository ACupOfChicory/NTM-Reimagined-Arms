# Writes src/main/resources/ntmgunfeel-materials.txt from the review: the user's marks (fixes/) win over Claude's sure
# verdicts (claude.json); a block gets a line only where the review differs from what the classifier and the bullet
# rules give it now (build/materials-report.tsv, made by MaterialsTest). Prints where the bullet rules' own material
# lines disagree with the review: those lines come later and win.
# Usage: python3 review/materials/make_rules.py  (from the repo root, after ./gradlew test)
import glob
import json
import os
from collections import defaultdict

here = os.path.dirname(os.path.abspath(__file__))
claude = json.load(open(os.path.join(here, "claude.json")))
fixes = {os.path.basename(f)[:-5]: json.load(open(f)) for f in glob.glob(os.path.join(here, "fixes", "*.json"))}
EXTRA = {"minecraft:vault": "STEEL"}  # decided in the chat: a solid steel casing

now, step = {}, {}
for line in open("build/materials-report.tsv"):
    if line.startswith("#") or line.startswith("how\t"):
        continue
    p = line.rstrip("\n").split("\t")
    now[p[1]] = p[2].upper()
    step[p[1]] = p[4]

review = {k: v[0] for k, v in claude.items() if v[1]}
for k, d in fixes.items():
    if d.get("material"):
        review[k] = d["material"]
    elif d.get("ok"):
        review.pop(k, None)  # the user agreed with what the page showed: nothing to change
review.update(EXTRA)

lines = defaultdict(list)
clash = []
for k in sorted(review):
    m = review[k]
    if k not in now:
        print("not in the dump:", k)
        continue
    if now[k] == m:
        continue
    if step[k] == "rule":
        clash.append(f"{k}: bullet rules say {now[k]}, the review {m}")
        continue
    lines[m].append(k)

ORDER = ["NONE", "GLASS", "REINFORCED_GLASS", "LAMINATE", "WOOD", "SOFT", "STONE", "CONCRETE", "METAL", "STEEL",
         "MACHINE", "UNBREAKABLE"]
WHAT = {
    "NONE": "flown through: thin, hollow or not there for a bullet",
    "WOOD": "wood and plastics",
    "SOFT": "soft ground, cloth, organic",
    "STONE": "stone, brick, ceramic, minerals",
    "CONCRETE": "concrete and the like",
    "METAL": "soft metals: lead, copper, gold, aluminium, plain iron",
    "STEEL": "steel and the hard alloys",
    "MACHINE": "a hollow steel casing",
    "UNBREAKABLE": "never pierced",
}
out = ["# NTM Reimagined Arms: the material of each block where the classifier (Materials) guesses wrong, from the",
       "# block review (review/materials: Claude read every doubtful block with its recipe, the author settled the rest).",
       "# Same syntax as the material lines of the bullet rules, which come after these and win, then the config's.",
       "# Made by review/materials/make_rules.py; edit the review, not this file."]
for m in ORDER:
    if not lines[m]:
        continue
    out.append("")
    out.append(f"# {m.lower()}: {WHAT.get(m, '')}".rstrip(": "))
    out += [f"material {k} {m.lower()}" for k in lines[m]]
open("src/main/resources/ntmgunfeel-materials.txt", "w").write("\n".join(out) + "\n")
print(sum(len(v) for v in lines.values()), "lines;", {m: len(v) for m, v in lines.items()})
for c in clash:
    print("clash:", c)
