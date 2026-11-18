#!/usr/bin/env python3
"""Put the moves made from recordings of people (retarget_moves.py: turns on the spot, starts into a run, stops from
one) into the mod: their legs (`<name>_lower`) and what the mod plays them by, as BotMoves reads them.

Run:  python3 -I tools/motion/moves_to_mod.py <mocap_moves.animation.json> <mocap_moves.clips.json> \
          src/main/resources/assets/ntmgunfeel/animations/bot_moves.json
      python3 -I tools/motion/test_moves_to_mod.py

Each move: its kind (turn, start, stop), length (s), the curve it is played by (`turn_left_deg` for a turn, `ahead_m`
for a start or a stop; `hz` samples a second from its start), how far its hips go down (`drop`, px), its stances (s,
each foot) and the foot it ends on, the legs' rotations (degrees, a key each `t`), its source, license and credit.
"""
from __future__ import annotations

import json
import sys

#: the bones a move puts in the mod (its legs; the trunk, the arms and the head are the mod's own)
LEGS = ("right_leg", "right_shin", "left_leg", "left_shin")
#: digits kept: degrees and the curve's values
DIGITS = 3


def move(name: str, anim: dict, meta: dict) -> dict:
	kind = meta["kind"]
	curve = meta["turn_left_deg"] if kind == "turn" else meta["ahead_m"]
	bones = {}
	for bone in LEGS:
		keys = anim["bones"].get(bone, {}).get("rotation")
		if not keys:
			continue
		ts = sorted(keys, key=float)
		bones[bone] = {
			"t": [round(float(t), 4) for t in ts],
			"r": [round(float(v), DIGITS) for t in ts for v in keys[t]["post"]],
		}
	if len(bones) != len(LEGS):
		raise ValueError(f"{name}: legs missing ({sorted(bones)})")
	return {
		"kind": kind,
		"length": float(anim["animation_length"]),
		"hz": int(meta["curve_hz"]),
		"curve": [round(float(v), DIGITS) for v in curve],
		"drop": float(meta["drop_px"]),
		"ends_on_foot": meta["ends_on_foot"],
		"stances": meta["stances"],
		"source": meta["source"],
		"license": meta["license"],
		"credit": meta["credit"],
		"bones": bones,
	}


def build(anims: dict, clips: dict) -> dict:
	out = {}
	for name, meta in clips.items():
		low = anims.get(name + "_lower")
		if low is None:
			raise ValueError(f"{name}: no {name}_lower clip")
		out[name] = move(name, low, meta)
	return {
		"about": "AI mobs' moves from recordings of people (tools/motion/retarget_moves.py, moves_to_mod.py): "
		"100STYLE (Ian Mason, CC BY 4.0) and the CMU Graphics Lab Motion Capture Database (mocap.cs.cmu.edu)",
		"moves": out,
	}


def main(argv: list[str]) -> int:
	if len(argv) != 4:
		print(__doc__)
		return 2
	with open(argv[1]) as f:
		anims = json.load(f)["animations"]
	with open(argv[2]) as f:
		clips = json.load(f)["clips"]
	data = build(anims, clips)
	with open(argv[3], "w") as f:
		json.dump(data, f, separators=(",", ":"))
		f.write("\n")
	print(f"{len(data['moves'])} moves -> {argv[3]}")
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv))
