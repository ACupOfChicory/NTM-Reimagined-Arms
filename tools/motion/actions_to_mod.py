#!/usr/bin/env python3
"""Put actions made from recordings of people (retarget_actions.py: a grenade's throw) into the mod, as BotClips reads
them: whole clips (Bedrock 1.8.0, linear keys) by the mod's name for each.

Run:  python3 -I tools/motion/actions_to_mod.py <mocap_actions.animation.json> <mocap_actions.clips.json> \
          src/main/resources/assets/ntmgunfeel/animations/bot_people.animation.json throw_person=throw_cmu79_35a
      python3 -I tools/motion/test_actions_to_mod.py

Each clip goes in as it is (its keys rounded to DIGITS), played once; its source, license and credit (from the clips'
notes) go beside it under "about".
"""
from __future__ import annotations

import json
import math
import sys

#: digits kept: degrees and pixels
DIGITS = 3


def keys(ch: dict) -> dict:
	"""a channel's keys by time (4 digits: two keys on one time are an error), each its value after the key"""
	out = {}
	for t in sorted(ch, key=float):
		v = ch[t].get("post") if isinstance(ch[t], dict) else ch[t]
		k = "%.4f" % float(t)
		if v is None:
			raise ValueError(f"the key at {k} s has no post")
		if k in out:
			raise ValueError(f"two keys at {k} s")
		vals = [float(x) for x in v]
		if len(vals) != 3 or not all(math.isfinite(x) for x in vals):
			raise ValueError(f"the key at {k} s is not three numbers: {v}")
		out[k] = {"post": [round(x, DIGITS) for x in vals]}
	return out


def clip(name: str, anim: dict) -> dict:
	bones = {}
	for bone, chans in anim["bones"].items():
		kept = {k: keys(ch) for k, ch in chans.items() if k in ("rotation", "position") and ch}
		if kept:
			bones[bone] = kept
	if not bones:
		raise ValueError(f"{name}: no bones")
	return {"loop": False, "animation_length": float(anim["animation_length"]), "bones": bones}


def build(anims: dict, notes: dict, pick: dict[str, str]) -> dict:
	out, about = {}, {}
	for mod, src in pick.items():
		if src not in anims:
			raise ValueError(f"{mod}: no clip {src}")
		out[mod] = clip(src, anims[src])
		n = notes.get(src, {})
		missing = [k for k in ("source", "license", "credit") if not n.get(k)]
		if missing:
			raise ValueError(f"{mod}: {src} has no {', '.join(missing)} in the clips' notes")
		about[mod] = {k: n[k] for k in ("source", "license", "credit")}
	return {"format_version": "1.8.0", "about": about, "animations": out}


def main(argv: list[str]) -> int:
	if len(argv) < 5:
		print(__doc__)
		return 2
	bad = [a for a in argv[4:] if "=" not in a]
	if bad:
		print(f"not name=clip: {' '.join(bad)}")
		return 2
	pick = dict(a.split("=", 1) for a in argv[4:])
	if len(pick) != len(argv[4:]):
		print("a name given twice")
		return 2
	with open(argv[1]) as f:
		anims = json.load(f)["animations"]
	with open(argv[2]) as f:
		notes = json.load(f)
	notes = notes.get("clips", notes)
	data = build(anims, notes, pick)
	with open(argv[3], "w") as f:
		json.dump(data, f, separators=(",", ":"))
		f.write("\n")
	print(f"{len(data['animations'])} clips -> {argv[3]}")
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv))
