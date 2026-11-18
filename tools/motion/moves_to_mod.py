#!/usr/bin/env python3
"""Put the moves made from recordings of people (retarget_moves.py: turns on the spot, starts into a run, stops from
one; retarget_crouch.py: down into a crouch and up out of it) into the mod: their legs (`<name>_lower`) and what the
mod plays them by, as BotMoves reads them.

Run:  python3 -I tools/motion/moves_to_mod.py <mocap_moves.animation.json> <mocap_moves.clips.json> \
          [<mocap_crouch_moves.animation.json> <mocap_crouch_moves.clips.json>:name,name ...] \
          src/main/resources/assets/ntmgunfeel/animations/bot_moves.json
      python3 -I tools/motion/test_moves_to_mod.py

Each pair of files gives its moves (after `:` in the clips file's name, only those named). Each move: its kind (turn,
start, stop, down, up), length (s), the curve it is played by (`turn_left_deg` for a turn, `ahead_m` for the others;
`hz` samples a second from its start), how far its hips go down (`drop`, px: `drop_px`, or `gait_drop_px` a crouch's
gait gives), a down's or an up's crouch share (`crouch`, 0 to 1, the same samples), its stances (s, each foot) and the
foot it ends on, the legs' rotations (degrees, a key each `t`), its source, license and credit.
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
	out = {
		"kind": kind,
		"length": float(anim["animation_length"]),
		"hz": int(meta["curve_hz"]),
		"curve": [round(float(v), DIGITS) for v in curve],
		"drop": float(meta["drop_px"] if "drop_px" in meta else meta["gait_drop_px"]),
		"ends_on_foot": meta["ends_on_foot"],
		"stances": meta["stances"],
		"source": meta["source"],
		"license": meta["license"],
		"credit": meta["credit"],
		"bones": bones,
	}
	if kind in ("down", "up"):
		crouch = [round(float(v), DIGITS) for v in meta["crouch"]]
		if len(crouch) != len(curve) or any(not 0.0 <= v <= 1.0 for v in crouch):
			raise ValueError(f"{name}: a crouch share of {len(crouch)} samples, not 0 to 1 by the curve's {len(curve)}")
		out["crouch"] = crouch
	return out


#: what the file says it holds: the first set's, and with the crouch's ACCAD's
ABOUT = ("AI mobs' moves from recordings of people (tools/motion/retarget_moves.py, moves_to_mod.py): "
		"100STYLE (Ian Mason, CC BY 4.0) and the CMU Graphics Lab Motion Capture Database (mocap.cs.cmu.edu)")
ABOUT_ACCAD = "; retarget_crouch.py: ACCAD's Open Motion Project (The Ohio State University, CC BY 3.0)"


def build(anims: dict, clips: dict) -> dict:
	out = {}
	for name, meta in clips.items():
		low = anims.get(name + "_lower")
		if low is None:
			raise ValueError(f"{name}: no {name}_lower clip")
		out[name] = move(name, low, meta)
	accad = any(m["kind"] in ("down", "up") for m in out.values())
	return {"about": ABOUT + (ABOUT_ACCAD if accad else ""), "moves": out}


def main(argv: list[str]) -> int:
	if len(argv) < 4 or len(argv) % 2:
		print(__doc__)
		return 2
	anims, clips = {}, {}
	for k in range(1, len(argv) - 1, 2):
		path, _, only = argv[k + 1].partition(":")
		with open(argv[k]) as f:
			a = json.load(f)["animations"]
		with open(path) as f:
			c = json.load(f)["clips"]
		if only:
			names = only.split(",")
			missing = [n for n in names if n not in c]
			if missing:
				print(f"not in {path}: {' '.join(missing)}")
				return 2
			c = {n: c[n] for n in names}
		twice = sorted(set(c) & set(clips))
		if twice:
			print(f"in two files: {' '.join(twice)}")
			return 2
		anims.update(a)
		clips.update(c)
	data = build(anims, clips)
	with open(argv[-1], "w") as f:
		json.dump(data, f, separators=(",", ":"))
		f.write("\n")
	print(f"{len(data['moves'])} moves -> {argv[-1]}")
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv))
