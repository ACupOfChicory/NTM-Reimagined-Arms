"""How an AI mob's body moves against how people move, in the same numbers.

Run:  python3 -I tools/motion/feel.py bot <label-feel.tsv>             the stand's frames (BotFeelStand), by scene
      python3 -I tools/motion/feel.py people <motion-data dir>          the recordings of people, by set
      python3 -I tools/motion/feel.py table <label-feel.tsv> <motion-data dir> [out.md]

The motion-data dir holds the downloaded sets as they unpack (100style/100STYLE/<Style>/<Style>_<Move>.bvh,
cmu/all_asfamc/subjects/<n>/<n>_<k>.amc); nothing of them goes into the repository.

Both sides become a "take": the same few series, at 60 frames a second, in the bot's size (a leg of 12 px, 0.75 m):

- yaw of the head, the chest and the pelvis, degrees, unwrapped (where each faces on the floor);
- the turn of each of the ten boxes of the bot's model in the world (head, body, upper arms, forearms, thighs,
  shins): for a recording, the bone each box is made from (tools/quaternius: the upper arm from the humerus ...);
- the feet: the ankle's place, and the sole's height over the floor;
- the pelvis and the head, their places.

From a take:

- turn: the peak speed (deg/s) and acceleration (deg/s^2) of each yaw, smoothed by a Gaussian of sigma 2 frames;
- lag: when each of head, chest, pelvis has done half of a turn, against the head (the bot: and against the yaw the
  server gave it);
- jerk: the second difference of each box's turn, deg/frame^2 at 60 frames a second (the bot's raw; a recording
  after the Gaussian of sigma 1.5 frames a clip made from it would be smoothed with);
- slide: how far a foot standing on the floor moves over it, mm a frame and mm summed over each time it stands;
- start and stop: from standing to 90 % of its speed, from 90 % to under 0.1 m/s, and the feet lifted on the way;
- head over the pelvis: how far the head moves about its middle place over the pelvis, in the body's frame (the
  bot's head may not leave the server's eye by more than 3 cm, so a recording that moves it more cannot be taken as
  it is).

Python 3 and numpy only.
"""
from __future__ import annotations

import csv
import math
import os
import sys
from dataclasses import dataclass, field

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mocap  # noqa: E402

FPS = 60.0
#: the bot's leg, hip pivot to sole: 12 px
BOT_LEG = 0.75
BOXES = ("head", "body", "right_arm", "right_forearm", "left_arm", "left_forearm", "right_leg", "right_shin",
		"left_leg", "left_shin")


@dataclass
class Take:
	name: str
	t: np.ndarray                      # (N,) s
	yaw: dict                          # head, chest, pelvis -> (N,) deg, unwrapped
	rot: np.ndarray                    # (N,10,3,3) the boxes' turns in the world
	ankle: np.ndarray                  # (N,2,3) right, left; m
	sole: np.ndarray                   # (N,2) the sole's height over the floor, m
	pelvis: np.ndarray                 # (N,3) m
	head: np.ndarray                   # (N,3) m
	extra: dict = field(default_factory=dict)


# ---------------------------------------------------------------- numbers

def gauss(x: np.ndarray, sigma: float) -> np.ndarray:
	"""A Gaussian along axis 0, the ends mirrored."""
	if sigma <= 0:
		return x
	r = int(math.ceil(3 * sigma))
	k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
	k /= k.sum()
	pad = np.concatenate([x[r:0:-1], x, x[-2:-r - 2:-1]], axis=0) if len(x) > r + 1 else np.pad(x, [(r, r)] + [(0, 0)] * (x.ndim - 1), mode="edge")
	out = np.zeros_like(x, dtype=np.float64)
	for i, w in enumerate(k):
		out += w * pad[i:i + len(x)]
	return out


def deriv(y: np.ndarray, t: np.ndarray) -> np.ndarray:
	"""dy/dt at each sample (uneven t: central differences)."""
	return np.gradient(y, t, axis=0)


def rotvec(r: np.ndarray) -> np.ndarray:
	"""(...,3,3) -> (...,3) axis times angle, radians."""
	c = np.clip((np.trace(r, axis1=-2, axis2=-1) - 1) / 2, -1, 1)
	a = np.arccos(c)
	v = np.stack([r[..., 2, 1] - r[..., 1, 2], r[..., 0, 2] - r[..., 2, 0], r[..., 1, 0] - r[..., 0, 1]], -1)
	s = np.sin(a)
	k = np.where(s > 1e-6, a / (2 * np.maximum(s, 1e-12)), 0.5)
	return v * k[..., None]


def jerk(take: Take) -> np.ndarray:
	"""(N-2,10) the change of each box's angular speed from frame to frame, deg/frame^2 at FPS."""
	r = take.rot
	d = np.einsum("nbij,nbkj->nbik", r[1:], r[:-1])          # R_t R_{t-1}^T
	w = rotvec(d) / np.diff(take.t)[:, None, None]             # rad/s, world
	tm = (take.t[1:] + take.t[:-1]) / 2
	a = np.linalg.norm(np.diff(w, axis=0), axis=-1) / np.diff(tm)[:, None]   # rad/s^2
	return np.degrees(a) / FPS ** 2


def quat_mat(q: np.ndarray) -> np.ndarray:
	return mocap.quat_to_mat(q)


def yaw_of(fwd: np.ndarray) -> np.ndarray:
	"""The yaw (deg) of a forward vector on the floor (x, z), unwrapped: Minecraft's sense (0 to +z, 90 to -x)."""
	return np.degrees(np.unwrap(np.arctan2(-fwd[..., 0], fwd[..., 2])))


# ---------------------------------------------------------------- the stand

def read_bot(path: str) -> dict[str, Take]:
	"""BotFeelStand's frames, a take per scene."""
	with open(path, encoding="utf-8") as f:
		rows = list(csv.DictReader(f, delimiter="\t"))
	scenes: dict[str, list] = {}
	for r in rows:
		scenes.setdefault(r["сцена"], []).append(r)
	out = {}
	for name, rs in scenes.items():
		g = lambda k: np.array([float(r[k]) for r in rs])
		t = g("t")
		# frames in order of their time only (the stand's clock may step back over a scene's change)
		keep = np.concatenate([[True], t[1:] > np.maximum.accumulate(t)[:-1] + 1e-6])
		rs = [r for r, k in zip(rs, keep) if k]
		t = g("t")
		rot = np.stack([quat_mat(np.stack([g(f"{b}_qw"), g(f"{b}_qx"), g(f"{b}_qy"), g(f"{b}_qz")], -1)) for b in BOXES], 1)
		fwd = lambda i: np.einsum("nij,j->ni", rot[:, i], np.array([0.0, 0.0, -1.0]))
		body = g("body_rot")
		pt = lambda p: np.stack([g(p + "_x"), g(p + "_y"), g(p + "_z")], -1)
		take = Take(name, t, {"head": yaw_of(fwd(0)), "chest": yaw_of(fwd(1)), "pelvis": np.degrees(np.unwrap(np.radians(body)))},
				rot, np.stack([pt("right_ankle"), pt("left_ankle")], 1), np.stack([g("right_sole"), g("left_sole")], -1),
				pt("pelvis"), pt("head"))
		take.extra["root"] = np.stack([g("x"), g("y"), g("z")], -1)
		take.extra["received"] = np.degrees(np.unwrap(np.radians(g("yaw_received"))))
		take.extra["speed"] = g("скорость")
		take.extra["frame_ms"] = g("кадр_мс")
		# the pelvis yaw and the head's yaw unwrapped from the same start as the received one
		for k in ("head", "chest"):
			take.yaw[k] += 360.0 * np.round((take.yaw["pelvis"][0] - take.yaw[k][0]) / 360.0)
		take.extra["received"] += 360.0 * np.round((take.yaw["pelvis"][0] - take.extra["received"][0]) / 360.0)
		out[name] = take
	return out


# ---------------------------------------------------------------- recordings of people

#: which bone each of the ten boxes is made from, and the points: (CMU, 100STYLE)
BONES = {
	"head": ("head", "Head"), "body": ("thorax", "Chest4"),
	"right_arm": ("rhumerus", "RightShoulder"), "right_forearm": ("rradius", "RightElbow"),
	"left_arm": ("lhumerus", "LeftShoulder"), "left_forearm": ("lradius", "LeftElbow"),
	"right_leg": ("rfemur", "RightHip"), "right_shin": ("rtibia", "RightKnee"),
	"left_leg": ("lfemur", "LeftHip"), "left_shin": ("ltibia", "LeftKnee"),
}
POINTS = {
	"pelvis": ("root", "Hips"), "head": ("head", "Head"),
	"right_ankle": ("rfoot", "RightAnkle"), "left_ankle": ("lfoot", "LeftAnkle"),
	"right_toe": ("rtoes", "RightToe"), "left_toe": ("ltoes", "LeftToe"),
	"right_hip": ("rfemur", "RightHip"), "left_hip": ("lfemur", "LeftHip"),
}


def read_person(path: str, cut: tuple[int, int] | None = None) -> mocap.Motion:
	if path.endswith(".bvh"):
		mo = mocap.read_bvh(path, 0.01, os.path.basename(path))
	else:
		subj = os.path.basename(path).split("_")[0]
		mo = mocap.read_asf_amc(os.path.join(os.path.dirname(path), subj + ".asf"), path, os.path.basename(path))
	if cut:
		mo = mocap.slice_motion(mo, *cut)
	return mo


def person(mo: mocap.Motion, name: str, smooth: float = 1.5) -> Take:
	"""A recording as a take: at 60 frames a second, the bones' turns smoothed (sigma in 60 fps frames), in the bot's
	size (its pelvis height over the floor made the bot's leg)."""
	cmu = not mo.name.endswith(".bvh")
	col = 0 if cmu else 1
	step = max(1, int(round(mo.fps / FPS)))
	pos, rot = mocap.forward_kinematics(mo)
	sk = mo.skeleton
	ix = lambda nm: sk.index(nm)
	# quaternions smoothed with their signs kept continuous, then every step-th frame
	q = mocap.mat_to_quat(rot)
	for j in range(q.shape[1]):
		flip = np.cumsum(np.concatenate([[0], (np.einsum("ni,ni->n", q[1:, j], q[:-1, j]) < 0).astype(int)])) % 2
		q[:, j] *= np.where(flip, -1.0, 1.0)[:, None]
	q = gauss(q, smooth * step)
	rot = mocap.quat_to_mat(q)[::step]
	pos = gauss(pos, smooth * step)[::step]
	n = pos.shape[0]
	t = np.arange(n) / (mo.fps / step)
	p = {k: pos[:, ix(v[col])] for k, v in POINTS.items()}
	floor = np.percentile(np.minimum.reduce([p["right_toe"][:, 1], p["left_toe"][:, 1], p["right_ankle"][:, 1], p["left_ankle"][:, 1]]), 1)
	hip = np.median((p["right_hip"][:, 1] + p["left_hip"][:, 1]) / 2) - floor
	k = BOT_LEG / hip
	sc = lambda v: (v - np.array([0.0, floor, 0.0])) * k
	# forward at rest: across the hip line, level (every local turn is nought at rest)
	rp, _ = mocap.rest_pose(sk)
	h = rp[ix(POINTS["left_hip"][col])] - rp[ix(POINTS["right_hip"][col])]
	f0 = np.cross(h, [0.0, 1.0, 0.0])
	f0 /= np.linalg.norm(f0)
	fw = lambda nm: np.einsum("nij,j->ni", rot[:, ix(nm)], f0)
	boxes = np.stack([rot[:, ix(BONES[b][col])] for b in BOXES], 1)
	ankle = np.stack([sc(p["right_ankle"]), sc(p["left_ankle"])], 1)
	sole = np.stack([np.minimum(sc(p["right_ankle"])[:, 1], sc(p["right_toe"])[:, 1]),
			np.minimum(sc(p["left_ankle"])[:, 1], sc(p["left_toe"])[:, 1])], -1)
	take = Take(name, t, {"head": yaw_of(fw(BONES["head"][col])), "chest": yaw_of(fw(BONES["body"][col])),
			"pelvis": yaw_of(fw(POINTS["pelvis"][col]))}, boxes, ankle, sole, sc(p["pelvis"]), sc(p["head"]))
	for kk in ("head", "chest"):
		take.yaw[kk] += 360.0 * np.round((take.yaw["pelvis"][0] - take.yaw[kk][0]) / 360.0)
	take.extra["scale"] = k
	# the toe as the sole's point for the slide where it is lower than the ankle's (the heel up, the ball down)
	take.extra["toe"] = np.stack([sc(p["right_toe"]), sc(p["left_toe"])], 1)
	return take


# ---------------------------------------------------------------- measures

def turns(take: Take, least: float = 60.0, on: float = 40.0, gap: float = 0.8) -> list[dict]:
	"""Each turn of `least` degrees or more: the frames where the head, the chest or the pelvis turns faster than
	`on` deg/s, runs closer than `gap` s together taken as one (the bot's head turns well before its body), the
	turn's size the change of the three's mean over it."""
	t = take.t
	ys = {k: gauss(v, 2.0) for k, v in take.yaw.items()}
	both = (ys["head"] + ys["chest"] + ys["pelvis"]) / 3
	fast = np.max(np.abs(np.stack([deriv(v, t) for v in ys.values()])), axis=0) > on
	runs, i, n = [], 0, len(t)
	while i < n:
		if not fast[i]:
			i += 1
			continue
		j = i
		while j < n and fast[j]:
			j += 1
		if runs and t[i] - t[runs[-1][1] - 1] < gap:
			runs[-1][1] = j
		else:
			runs.append([i, j])
		i = j
	out = []
	for i, j in runs:
		a, b = max(0, i - int(0.3 * FPS)), min(n - 1, j + int(0.3 * FPS))
		if abs(both[b] - both[a]) >= least:
			out.append(turn_numbers(take, ys, a, b))
	return out


def half(t: np.ndarray, y: np.ndarray, a: int, b: int) -> float:
	"""When y has gone half the way from y[a] to y[b] (first crossing), s."""
	mid = (y[a] + y[b]) / 2
	s = np.sign(y[b] - y[a])
	for i in range(a, b):
		if (y[i + 1] - mid) * s >= 0 > (y[i] - mid) * s:
			return t[i] + (t[i + 1] - t[i]) * (mid - y[i]) / (y[i + 1] - y[i])
	return float("nan")


def turn_numbers(take: Take, ys: dict, a: int, b: int) -> dict:
	t = take.t
	d = {"from": t[a], "to": t[b], "deg": ys["pelvis"][b] - ys["pelvis"][a],
			"speed": float(np.mean(np.linalg.norm(np.diff(take.pelvis[a:b + 1, [0, 2]], axis=0), axis=1) / np.diff(t[a:b + 1])))}
	for k, y in ys.items():
		w = deriv(y, t)[a:b + 1]
		al = deriv(deriv(y, t), t)[a:b + 1]
		d[k + "_w"] = float(np.max(np.abs(w)))
		d[k + "_a"] = float(np.max(np.abs(al)))
		d[k + "_half"] = half(t, y, a, b)
	if "received" in take.extra:
		d["received_half"] = half(t, take.extra["received"], a, b)
	return d


#: a recorded foot stands only while slower than this over the floor, m/s (the bot's size)
PEOPLE_STILL = 0.25


def slides(take: Take, ground: float) -> dict:
	"""The feet standing: a foot stands where its sole is within `ground` of the floor (m); its slide is how far the
	lowest point of it moves over the floor from frame to frame while it stands, mm."""
	per, sums = [], []
	for k in range(2):
		pt = take.ankle[:, k].copy()
		if "toe" in take.extra:
			toe = take.extra["toe"][:, k]
			pt = np.where((toe[:, 1] < pt[:, 1])[:, None], toe, pt)
		step = np.linalg.norm(np.diff(pt[:, [0, 2]], axis=0), axis=1) * 1000
		down = take.sole[:, k] <= ground
		if "toe" in take.extra:
			# a recording: the toe passes the floor in its swing within a centimetre or two, so a foot stands where it
			# is low and slow as well (PEOPLE_STILL)
			v = np.concatenate([[0.0], step]) / 1000 * FPS
			down &= gauss(v, 1.0) < PEOPLE_STILL
		both = down[1:] & down[:-1]
		run = 0.0
		for s, b in zip(step, both):
			if b:
				per.append(s)
				run += s
			elif run > 0:
				sums.append(run)
				run = 0.0
		if run > 0:
			sums.append(run)
	per, sums = np.array(per or [0.0]), np.array(sums or [0.0])
	return {"slide_frame_p95": float(np.percentile(per, 95)), "slide_frame_max": float(per.max()),
			"slide_stand_max": float(sums.max()), "slide_stand_med": float(np.median(sums)), "stands": len(sums)}


#: a start or a stop counts from or to this speed held, m/s; under STILL it stands (100STYLE's StartStop slows to
#: about 0.1 between its runs, not to nought)
MOVING, STILL = 0.3, 0.1


def start_stop(take: Take) -> list[dict]:
	"""Starts and stops of the pelvis over the floor (the bot's root): its speed up from under STILL to 90 % of the
	most it goes at in the 1.5 s after, and down from 90 % of the most in the 1.5 s before to under STILL, s; and
	the feet lifted meanwhile."""
	t = take.t
	root = take.extra.get("root", take.pelvis)
	# the steps' sway out (sigma 0.1 s): the speed it goes at, not each footfall's
	v = gauss(np.linalg.norm(deriv(gauss(root[:, [0, 2]], 2.0), t), axis=1), 0.1 * FPS)
	out = []
	moving = v > STILL
	edges = np.flatnonzero(np.diff(moving.astype(int)))
	near = int(1.5 * FPS)
	for e in edges:
		if not moving[e] and moving[e + 1]:
			hold = float(np.max(v[e + 1:e + 1 + near]))
			j = e + 1
			while j < len(v) and v[j] < 0.9 * hold:
				j += 1
			if hold >= MOVING:
				out.append({"kind": "start", "at": t[e], "s": t[min(j, len(t) - 1)] - t[e], "hold": hold, "steps": lifts(take, e, j)})
		elif moving[e] and not moving[e + 1]:
			hold = float(np.max(v[max(0, e - near):e + 1]))
			j = e
			while j > 0 and v[j] < 0.9 * hold:
				j -= 1
			if hold >= MOVING:
				out.append({"kind": "stop", "at": t[e], "s": t[e] - t[j], "hold": hold, "steps": lifts(take, j, e)})
	return out


def lifts(take: Take, a: int, b: int, up: float = 0.03) -> int:
	"""Feet lifted (sole over `up` m) between frames a and b."""
	n = 0
	for k in range(2):
		s = take.sole[a:b + 1, k] > up
		n += int(np.sum(s[1:] & ~s[:-1]))
	return n


def head_travel(take: Take) -> float:
	"""The most the head moves from its middle place over the pelvis, in the pelvis's frame, cm."""
	yaw = np.radians(take.yaw["pelvis"])
	rel = take.head - take.pelvis
	c, s = np.cos(yaw), np.sin(yaw)
	# into the body's frame: x across, z ahead
	x = c * rel[:, 0] + s * rel[:, 2]
	z = -s * rel[:, 0] + c * rel[:, 2]
	b = np.stack([x, rel[:, 1], z], -1)
	return float(np.max(np.linalg.norm(b - np.median(b, axis=0), axis=1)) * 100)


def summary(take: Take, ground: float) -> dict:
	j = jerk(take)
	d = {"frames": len(take.t), "fps": (len(take.t) - 1) / (take.t[-1] - take.t[0]) if len(take.t) > 1 else 0,
			"jerk_max": float(j.max()) if j.size else 0.0, "jerk_p99": float(np.percentile(j, 99)) if j.size else 0.0,
			"jerk_box": BOXES[int(np.unravel_index(np.argmax(j), j.shape)[1])] if j.size else "",
			"head_cm": head_travel(take)}
	d.update(slides(take, ground))
	return d


# ---------------------------------------------------------------- the people's sets

#: the recordings measured: name -> (path under the motion-data dir, the cut or None, what it is)
def people_sets(root: str) -> dict[str, list[tuple[str, tuple[int, int] | None]]]:
	style = os.path.join(root, "100style", "100STYLE")
	cuts = {}
	with open(os.path.join(style, "Frame_Cuts.csv"), encoding="utf-8") as f:
		for r in csv.DictReader(f):
			cuts[r["STYLE_NAME"]] = r

	def s(st: str, mv: str):
		c = cuts.get(st, {})
		a, b = c.get(mv + "_START", "N/A"), c.get(mv + "_STOP", "N/A")
		return (os.path.join(style, st, f"{st}_{mv}.bvh"), None if "N/A" in (a, b) else (int(a), int(b)))

	cmu = os.path.join(root, "cmu", "all_asfamc", "subjects")
	c = lambda n, k: (os.path.join(cmu, str(n), f"{n}_{k:02d}.amc"), None)
	return {
		"turns_spot": [s("Neutral", "TR1"), s("Swat", "TR1"), c(69, 13), c(69, 14), c(69, 15)],
		"turns_walk": [c(16, 17), c(16, 18), c(16, 19), c(16, 20), c(69, 20), c(69, 21)],
		"turns_run": [c(16, 41), c(16, 42), c(16, 43), c(16, 44)],
		"start_stop": [s("StartStop", "FW"), s("StartStop", "FR"), c(16, 33), c(16, 34), c(16, 8), c(16, 57), c(127, 5),
			c(127, 17), c(127, 18), c(127, 19), c(127, 20), c(128, 5), c(128, 6), c(128, 7), c(128, 8), c(143, 2)],
		"walk": [s("Neutral", "FW"), s("Swat", "FW")],
		"strafe": [s("Neutral", "SW"), s("Swat", "SW")],
		"crouch": [s("Crouched", "FW"), s("Crouched", "ID")],
	}


def people(root: str) -> dict[str, dict]:
	out = {}
	for kind, recs in people_sets(root).items():
		rows = []
		for path, cut in recs:
			if not os.path.exists(path):
				print(f"skipped (not there): {path}", file=sys.stderr)
				continue
			tk = person(read_person(path, cut), os.path.basename(path))
			rows.append((tk, summary(tk, 0.03), turns(tk), start_stop(tk)))
		out[kind] = rows
	return out


# ---------------------------------------------------------------- the table

def med(xs, q=50):
	xs = [x for x in xs if x == x]
	return float(np.percentile(xs, q)) if xs else float("nan")


def bot_rows(bots: dict[str, Take]) -> dict[str, dict]:
	out = {}
	for name, tk in bots.items():
		d = summary(tk, 0.01)
		tr = turns(tk)
		d["turns"] = tr
		d["stops"] = start_stop(tk)
		out[name] = d
	return out


def fmt(x, nd=0):
	return "—" if x != x else f"{x:.{nd}f}"


def table(bot_path: str, root: str) -> str:
	bots = read_bot(bot_path)
	b = bot_rows(bots)
	p = people(root)
	L = ["# Bot motion feel: now / people / limit", "",
		f"Stand: `{os.path.basename(bot_path)}`. People: 100STYLE and CMU, put on the bot's ten boxes (feel.py).", ""]

	def turn_line(scene: str, kinds: list[str], label: str):
		bt = b.get(scene, {}).get("turns", [])
		pt = [x for k in kinds for _, _, ts, _ in p.get(k, []) for x in ts]
		bb = bt[0] if bt else {}
		lag = lambda d, a, z: (d.get(z + "_half", np.nan) - d.get(a + "_half", np.nan)) * 1000
		L.append(f"| {label} | turn {fmt(bb.get('deg', np.nan))}° | " + " | ".join([
			f"{fmt(bb.get('chest_w', np.nan))} / {fmt(med([x['chest_w'] for x in pt]))} (p90 {fmt(med([x['chest_w'] for x in pt], 90))})",
			f"{fmt(bb.get('chest_a', np.nan))} / {fmt(med([x['chest_a'] for x in pt]))}",
			f"{fmt(lag(bb, 'head', 'chest'))} / {fmt(med([lag(x, 'head', 'chest') for x in pt]))}",
			f"{fmt(lag(bb, 'chest', 'pelvis'))} / {fmt(med([lag(x, 'chest', 'pelvis') for x in pt]))}",
			f"{fmt(lag(bb, 'received', 'chest')) if bb else '—'}",
			f"{len(pt)}"]) + " |")

	L += ["## Turns", "", "Bot / people (median of each turn of 60° and more in the recordings). Speeds deg/s, "
		"acceleration deg/s², lags ms (when half the turn is done).", "",
		"| scene | bot | chest speed | chest accel | head→chest lag | chest→pelvis lag | received→drawn lag | people turns |",
		"|---|---|---|---|---|---|---|---|"]
	turn_line("turn90", ["turns_spot"], "turn 90 on the spot")
	turn_line("turn180", ["turns_spot"], "turn 180 on the spot")
	turn_line("walk_turn90", ["turns_walk"], "turn 90 walking")
	turn_line("run_turn180", ["turns_run"], "turn 180 running")
	L += ["", "## Jerk, slide, head", "", "Jerk: deg/frame² at 60 fps, the largest of the ten boxes (p99 / max). Slide: "
		"mm a frame (p95 / max) and summed over a stand (max). Head: cm from its middle place over the pelvis.", "",
		"| take | frames (fps) | jerk p99 / max (box) | slide/frame p95 / max | slide/stand max | head cm |", "|---|---|---|---|---|---|"]
	for name, d in b.items():
		L.append(f"| bot {name} | {d['frames']} ({fmt(d['fps'])}) | {fmt(d['jerk_p99'], 1)} / {fmt(d['jerk_max'], 1)} ({d['jerk_box']}) | "
			f"{fmt(d['slide_frame_p95'], 1)} / {fmt(d['slide_frame_max'], 1)} | {fmt(d['slide_stand_max'], 1)} | {fmt(d['head_cm'], 1)} |")
	for kind, rows in p.items():
		for tk, d, _, _ in rows:
			L.append(f"| {kind}: {tk.name} | {d['frames']} | {fmt(d['jerk_p99'], 1)} / {fmt(d['jerk_max'], 1)} ({d['jerk_box']}) | "
				f"{fmt(d['slide_frame_p95'], 1)} / {fmt(d['slide_frame_max'], 1)} | {fmt(d['slide_stand_max'], 1)} | {fmt(d['head_cm'], 1)} |")
	L += ["", "## Starts and stops", "", "s from standing (under 0.1 m/s) to 90 % of the speed it holds / from 90 % to under 0.1 m/s; feet lifted meanwhile.", "",
		"| take | starts s (steps) | stops s (steps) |", "|---|---|---|"]
	ss = lambda xs, k: ", ".join(f"{x['s']:.2f} ({x['steps']})" for x in xs if x["kind"] == k) or "—"
	for name in ("start_stop", "walk_turn90", "run_turn180"):
		if name in b:
			L.append(f"| bot {name} | {ss(b[name]['stops'], 'start')} | {ss(b[name]['stops'], 'stop')} |")
	for tk, _, _, st in p.get("start_stop", []):
		starts = [x["s"] for x in st if x["kind"] == "start"]
		stops = [x["s"] for x in st if x["kind"] == "stop"]
		steps = [x["steps"] for x in st if x["kind"] == "stop"]
		L.append(f"| {tk.name} | median {fmt(med(starts), 2)} of {len(starts)} | median {fmt(med(stops), 2)} of {len(stops)}, steps median {fmt(med(steps))} |")
	return "\n".join(L) + "\n"


def main(argv: list[str]) -> int:
	if len(argv) >= 2 and argv[0] == "bot":
		for name, d in bot_rows(read_bot(argv[1])).items():
			print(name, {k: (round(v, 2) if isinstance(v, float) else v) for k, v in d.items() if k not in ("turns", "stops")})
			for tr in d["turns"]:
				print("   turn", {k: round(v, 3) for k, v in tr.items()})
			for st in d["stops"]:
				print("  ", st)
		return 0
	if len(argv) >= 2 and argv[0] == "people":
		for kind, rows in people(argv[1]).items():
			for tk, d, ts, st in rows:
				print(kind, tk.name, f"scale {tk.extra['scale']:.3f}", {k: (round(v, 2) if isinstance(v, float) else v) for k, v in d.items()})
				for tr in ts:
					print("   turn", {k: round(v, 3) for k, v in tr.items()})
		return 0
	if len(argv) >= 3 and argv[0] == "table":
		text = table(argv[1], argv[2])
		if len(argv) >= 4:
			with open(argv[3], "w", encoding="utf-8") as f:
				f.write(text)
		print(text)
		return 0
	print(__doc__)
	return 2


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
