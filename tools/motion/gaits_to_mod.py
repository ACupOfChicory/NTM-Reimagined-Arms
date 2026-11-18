#!/usr/bin/env python3
"""Put gait loops made from recordings of people (retarget_mocap.py: runs, sprints and walks ahead) into the mod as
the variants of its gait slots: each AI mob plays its own one of each slot (BotGaits), the legs only.

Run:  python3 -I tools/motion/gaits_to_mod.py src/main/resources/assets/ntmgunfeel/animations/bot_gaits.animation.json \
          <mocap.animation.json> <mocap_exact.animation.json> walk=run_cmu16_45,... jog=... sprint=... stroll=...
      python3 -I tools/motion/test_gaits_to_mod.py

Each <name>.animation.json goes with its <name>.clips.json (the clips' notes). A clip goes in by its own name from its
legs' clip (<clip>_lower: the thighs' and the shins' turns, the keys rounded as actions_to_mod.py rounds them), looped;
beside it under "gaits" its own pace (speed_m_s, metres a second: blocks) and how far down its hips are held (drop_px),
under "about" its source, license and credit; under "slots" each slot's variants in the order given.
"""
from __future__ import annotations

import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from actions_to_mod import keys  # noqa: E402

#: the bones a gait clip moves in the mod (BotPoser.LEGS)
LEGS = ("right_leg", "right_shin", "left_leg", "left_shin")
#: the slots the mod reads (BotGaits): each must be there
SLOTS = ("walk", "jog", "sprint", "stroll")


def gait(name: str, anim: dict) -> dict:
	bones = {}
	length = float(anim["animation_length"])
	if not (math.isfinite(length) and length > 0):
		raise ValueError(f"{name}: a length of {length}")
	for bone in LEGS:
		rot = anim["bones"].get(bone, {}).get("rotation")
		if not rot:
			raise ValueError(f"{name}: no {bone} turns")
		k = keys(rot)
		# a loop: keys from its start to its end, the end's the start's (its seam closed)
		ts = list(k)
		if ts[0] != "%.4f" % 0.0 or ts[-1] != "%.4f" % length:
			raise ValueError(f"{name}: {bone}'s keys from {ts[0]} to {ts[-1]} s, not 0 to {length}")
		if max(abs(a - b) for a, b in zip(k[ts[0]]["post"], k[ts[-1]]["post"])) > 0.01:
			raise ValueError(f"{name}: {bone}'s loop open: {k[ts[0]]['post']} to {k[ts[-1]]['post']}")
		bones[bone] = {"rotation": k}
	return {"loop": True, "animation_length": length, "bones": bones}


def build(anims: dict, notes: dict, slots: dict[str, list[str]]) -> dict:
	out, about, paces = {}, {}, {}
	missing = [s for s in SLOTS if s not in slots]
	if missing:
		raise ValueError(f"no {', '.join(missing)} slot")
	for slot, names in slots.items():
		if not names:
			raise ValueError(f"{slot}: no clips")
		if len(set(names)) != len(names):
			raise ValueError(f"{slot}: a clip given twice")
		for src in names:
			if src in out:
				continue
			if src + "_lower" not in anims:
				raise ValueError(f"{slot}: no clip {src}_lower")
			n = notes.get(src, {})
			missing = [k for k in ("source", "license", "credit") if not n.get(k)]
			if missing:
				raise ValueError(f"{src} has no {', '.join(missing)} in the clips' notes")
			speed, drop = n.get("speed_m_s"), n.get("drop_px")
			if not isinstance(speed, (int, float)) or not math.isfinite(speed) or speed <= 0:
				raise ValueError(f"{src}: a pace of {speed}")
			if not isinstance(drop, (int, float)) or not math.isfinite(drop) or drop < 0:
				raise ValueError(f"{src}: a drop of {drop}")
			out[src] = gait(src, anims[src + "_lower"])
			about[src] = {k: n[k] for k in ("source", "license", "credit")}
			paces[src] = {"speed": float(speed), "drop": float(drop)}
	# the mod holds the hips at one height for them all (BotGaits: the jog's)
	drops = {p["drop"] for p in paces.values()}
	if len(drops) > 1:
		raise ValueError(f"the clips' hips at {sorted(drops)} px down: one height for all")
	return {"format_version": "1.8.0", "about": about, "gaits": paces, "slots": slots, "animations": out}


def main(argv: list[str]) -> int:
	files = [a for a in argv[2:] if "=" not in a]
	given = [a for a in argv[2:] if "=" in a]
	if len(argv) < 3 or not files or not given:
		print(__doc__)
		return 2
	slots = {}
	for a in given:
		slot, names = a.split("=", 1)
		if slot in slots:
			print(f"the slot {slot} given twice")
			return 2
		slots[slot] = [n for n in names.split(",") if n]
	bad = [f for f in files if not f.endswith(".animation.json")]
	if bad:
		print(f"not an .animation.json: {' '.join(bad)}")
		return 2
	anims, notes = {}, {}
	for f in files:
		with open(f) as fh:
			a = json.load(fh)["animations"]
		with open(f[: -len(".animation.json")] + ".clips.json") as fh:
			n = json.load(fh)
		n = n.get("clips", n)
		twice = sorted((set(a) & set(anims)) | (set(n) & set(notes)))
		if twice:
			print(f"in two files: {' '.join(twice)}")
			return 2
		anims.update(a)
		notes.update(n)
	data = build(anims, notes, slots)
	with open(argv[1], "w") as fh:
		json.dump(data, fh, separators=(",", ":"))
		fh.write("\n")
	print(f"{len(data['animations'])} clips in {len(slots)} slots -> {argv[1]}")
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv))
