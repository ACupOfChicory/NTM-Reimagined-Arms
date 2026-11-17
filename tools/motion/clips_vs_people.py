"""The clips AI mobs play now, measured as feel.py measures people, and set against people.

Run:  python3 -I tools/motion/clips_vs_people.py <bot.animation.json> <motion-data dir> [out.md] [--sources DIR]

<bot.animation.json> is the mod's src/main/resources/assets/ntmgunfeel/animations/bot.animation.json; <motion-data dir>
holds the downloaded sets as feel.py reads them (nothing of them goes into the repository); --sources is
tools/quaternius/animations, for the full-body clips the gait clips were cut from and a few other packs' runs (the
trunk and the arms the mod does not take from the clips).

Each clip is put on BotModel's ten boxes (default pivots as BotPoserTest.parts(), the model put in the world as
LivingEntityRenderer does: rotateY(180 - yaw) . scale(-1, -1, 1) . translate(0, -1.501, 0) . px / 16) and sampled at
60 frames a second, as a feel.Take. Two ways of a gait clip:

- as written: the clip at its own time, its root's shift added to every part (tools/quaternius README, Output);
- as the mod plays it at a mob's speed (BotPoser.gait, plant, stoop; BotAnimator.move): the walk, jog and sprint
  blended by gaitWeights at heading 0, each as far through its step as walkPos is (a step of both legs every
  CYCLE = 2 pi / 0.6662 of walkPos; walkPos goes on by min(4 s, 1) . tempo a tick at s blocks a tick), the legs only
  (rotations: the clips' root and pivots are not used), the foot it stands on hung under its knee (plant), the trunk
  leaned on the run (stoop). The mob goes on at s blocks a tick: 1 block is 1 m in the bot's size (its leg 12 px,
  0.75 m), as feel.py puts people.

What it gives for a gait: the steps a second, the step's length, the share of a step each foot stands (duty
factor), how high a foot is lifted, the hips' bob, the thigh's swing, the trunk's lean, the speed a standing foot
goes back at (the speed the clip "expects") and how far it slides over the floor at the mob's speed; for every
clip the jerk of each box, the head's travel over the pelvis and, standing, the feet's slide.

Python 3 and numpy only.
"""
from __future__ import annotations

import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import feel  # noqa: E402

FPS = feel.FPS
BOXES = feel.BOXES
PX = 1.0 / 16.0

# ---------------------------------------------------------------- the mod's numbers (BotPoser, BotAnimator)

#: a step of both legs, walkPos
CYCLE = 2 * math.pi / 0.6662
MOVE_FROM, MOVE_FULL = 0.01, 0.05
JOG_FROM, JOG_FULL, SPRINT_FROM, SPRINT_FULL = 0.20, 0.24, 0.29, 0.305
RUN_FROM, RUN_FULL, TEMPO_RUN, STRIDE_RUN, STRIDE_MIDDLE = 0.22, 0.26, 0.78, 1.3, -17.0
JOG_RUN = 0.6
RUN_LEAN, RUN_BEND, TAC_LEAN = 0.35, 0.1, 0.12
DOWN_MOST = 0.45
GROUND = 24.0
PLANT_SWING, PLANT_WALK, KNEE_MOST = 0.5, 0.25, 2.2
HALF = 6.0
GAIT = ("walk", "jog", "sprint")

#: BotModel's parts: default pivot (px, the model's axes: y down, -z ahead) and parent
PIVOT = {
	"head": (0.0, 0.0, 0.0), "body": (0.0, 0.0, 0.0),
	"right_arm": (-5.0, 2.0, 0.0), "left_arm": (5.0, 2.0, 0.0),
	"right_leg": (-1.9, 12.0, 0.0), "left_leg": (1.9, 12.0, 0.0),
	"right_forearm": (-1.0, 4.0, 0.0), "left_forearm": (1.0, 4.0, 0.0),
	"right_shin": (0.0, 6.0, 0.0), "left_shin": (0.0, 6.0, 0.0),
}
PARENT = {"right_forearm": "right_arm", "left_forearm": "left_arm", "right_shin": "right_leg", "left_shin": "left_leg"}
UPPER = ("head", "body", "right_arm", "left_arm", "right_leg", "left_leg")


def smooth(x):
	x = np.clip(x, 0.0, 1.0)
	return x * x * (3 - 2 * x)


def jmod(a, b):
	"""Java's float %: the sign of the dividend."""
	return math.fmod(a, b)


# ---------------------------------------------------------------- clips

class Clip:
	"""A Bedrock clip as BotClip reads it: per bone a rotation (deg) and a position (px, Y up) track, linear keys,
	held at both ends."""

	def __init__(self, name: str, a: dict):
		self.name = name
		self.length = float(a.get("animation_length", 0.0))
		loop = a.get("loop")
		self.mode = "loop" if loop is True else ("hold" if loop == "hold_on_last_frame" else "once")
		self.rot, self.pos = {}, {}
		for bone, ch in a.get("bones", {}).items():
			for kind, dst in (("rotation", self.rot), ("position", self.pos)):
				if kind in ch:
					keys = sorted((float(t), v["post"] if isinstance(v, dict) else v) for t, v in ch[kind].items())
					dst[bone] = (np.array([k[0] for k in keys]), np.array([k[1] for k in keys], dtype=float))

	@staticmethod
	def sample(track, t: np.ndarray) -> np.ndarray:
		times, vals = track
		t = np.atleast_1d(np.asarray(t, dtype=float))
		if len(times) == 1:
			return np.repeat(vals[:1], len(t), 0)
		return np.stack([np.interp(t, times, vals[:, i]) for i in range(3)], -1)

	def r(self, bone, t):
		return self.sample(self.rot[bone], t) if bone in self.rot else None

	def p(self, bone, t):
		return self.sample(self.pos[bone], t) if bone in self.pos else None

	def local(self, sec: np.ndarray) -> np.ndarray:
		if self.mode == "loop" and self.length > 0:
			return np.mod(sec, self.length)
		return np.clip(sec, 0.0, self.length)


def read_clips(path: str) -> dict[str, Clip]:
	with open(path, encoding="utf-8") as f:
		anims = json.load(f)["animations"]
	return {n: Clip(n, a) for n, a in anims.items()}


# ---------------------------------------------------------------- the model in the world

def rot_zyx(e: np.ndarray) -> np.ndarray:
	"""(N,3) xRot, yRot, zRot radians -> (N,3,3) Rz . Ry . Rx (ModelPart, Quaternionf.rotationZYX)."""
	x, y, z = e[:, 0], e[:, 1], e[:, 2]
	cx, sx, cy, sy, cz, sz = np.cos(x), np.sin(x), np.cos(y), np.sin(y), np.cos(z), np.sin(z)
	o, l = np.zeros_like(x), np.ones_like(x)
	rx = np.stack([l, o, o, o, cx, -sx, o, sx, cx], -1).reshape(-1, 3, 3)
	ry = np.stack([cy, o, sy, o, l, o, -sy, o, cy], -1).reshape(-1, 3, 3)
	rz = np.stack([cz, -sz, o, sz, cz, o, o, o, l], -1).reshape(-1, 3, 3)
	return rz @ ry @ rx


def pose_rest(n: int) -> dict:
	"""Every part at its default pivot, unturned: part -> {"p": (N,3) px, "e": (N,3) rad}."""
	return {b: {"p": np.tile(np.array(PIVOT[b]), (n, 1)), "e": np.zeros((n, 3))} for b in BOXES}


def base(yaw_deg: float = 0.0) -> np.ndarray:
	"""LivingEntityRenderer's turn of the model into the world (4x4, model px -> m)."""
	a = math.radians(180.0 - yaw_deg)
	ry = np.array([[math.cos(a), 0, math.sin(a), 0], [0, 1, 0, 0], [-math.sin(a), 0, math.cos(a), 0], [0, 0, 0, 1.0]])
	s = np.diag([-1.0, -1.0, 1.0, 1.0])
	t = np.eye(4)
	t[1, 3] = -1.501
	k = np.diag([PX, PX, PX, 1.0])
	return ry @ s @ t @ k


def fk(pose: dict, root: np.ndarray | None = None, yaw: float = 0.0) -> dict:
	"""The parts' 4x4 in the world (N,4,4; the translations in m) for a pose, the mob's feet at `root` (N,3) m."""
	n = next(iter(pose.values()))["p"].shape[0]
	b = base(yaw)
	out = {}
	for name in BOXES:
		m = np.zeros((n, 4, 4))
		m[:, :3, :3] = rot_zyx(pose[name]["e"])
		m[:, :3, 3] = pose[name]["p"]
		m[:, 3, 3] = 1.0
		par = out[PARENT[name]]["model"] if name in PARENT else np.broadcast_to(np.eye(4), (n, 4, 4))
		model = par @ m
		w = b @ model
		if root is not None:
			w = w.copy()
			w[:, :3, 3] += root
		out[name] = {"model": model, "world": w}
	return out


def point(mats: dict, part: str, local_px) -> np.ndarray:
	"""(N,3) m: a point in a part's own px."""
	v = np.append(np.asarray(local_px, dtype=float), 1.0)
	return np.einsum("nij,j->ni", mats[part]["world"], v)[:, :3]


def sole(mats: dict, shin: str) -> np.ndarray:
	"""(N,) the lowest of the sole's four corners over the floor, m (BotFeelStand)."""
	ys = [point(mats, shin, (x, 6.0, z))[:, 1] for x in (-2.0, 2.0) for z in (-2.0, 2.0)]
	return np.min(np.stack(ys, -1), -1)


def take(name: str, t: np.ndarray, pose: dict, root: np.ndarray | None = None) -> feel.Take:
	"""A pose over time as feel.py's take (yaw 0: the mob faces +z)."""
	m = fk(pose, root)
	# each box's turn in the world (the 4x4 also scales px to m: taken off)
	rot = np.stack([m[b]["world"][:, :3, :3] / PX for b in BOXES], 1)
	ankle = np.stack([point(m, "right_shin", (0, 6, 0)), point(m, "left_shin", (0, 6, 0))], 1)
	soles = np.stack([sole(m, "right_shin"), sole(m, "left_shin")], -1)
	z = np.zeros(len(t))
	tk = feel.Take(name, t, {"head": z.copy(), "chest": z.copy(), "pelvis": z.copy()}, rot, ankle, soles,
			point(m, "body", (0, 12, 0)), point(m, "head", (0, -4, 0)))
	tk.extra["mats"] = m
	# it faces +z and goes that way (a clip in place too): its root on the floor where it is put
	tk.extra["fwd"] = np.tile(np.array([0.0, 1.0]), (len(t), 1))
	tk.extra["root"] = np.zeros((len(t), 3)) if root is None else root
	return tk


def clip_pose(clip: Clip, t: np.ndarray, bones=None) -> dict:
	"""The clip's pose from the default one (BotPoser.set, all of the way): its root's shift on every part."""
	pose = pose_rest(len(t))
	root = clip.p("root", t)
	for b in BOXES:
		if bones is not None and b not in bones:
			continue
		e = clip.r(b, t)
		if e is not None:
			pose[b]["e"] = np.radians(e)
		p = clip.p(b, t)
		if p is not None:
			pose[b]["p"] = pose[b]["p"] + p * np.array([1.0, -1.0, 1.0])
	if root is not None:
		for b in UPPER:
			pose[b]["p"] = pose[b]["p"] + root * np.array([1.0, -1.0, 1.0])
	return pose


#: the way each gait clip goes (x, z on the floor, the mob facing +z; its right is -x)
WAY = {"back": (0.0, -1.0), "strafe_right": (-1.0, 0.0), "strafe_left": (1.0, 0.0)}


def written(clip: Clip, cycles: int = 3, way: tuple[float, float] | None = None) -> feel.Take:
	"""The clip as written, at its own time, 60 frames a second (a loop `cycles` times over, else once), in place;
	`way` the way it would go (default: by its name, else ahead)."""
	n = max(2, int(round(clip.length * FPS * (cycles if clip.mode == "loop" else 1))) + 1)
	t = np.arange(n) / FPS
	tk = take(clip.name, t, clip_pose(clip, clip.local(t)))
	way = way or WAY.get(clip.name, (0.0, 1.0))
	tk.extra["fwd"] = np.tile(np.array(way), (n, 1))
	return tk


def added(clip: Clip, cycles: int = 1) -> feel.Take:
	"""The clip as BotPoser.add puts it over the rest pose (the idle's breathing, a flinch): each part turned by the
	clip's turn from its first frame, its pivot moved by the clip's shift from its first frame."""
	n = max(2, int(round(clip.length * FPS * (cycles if clip.mode == "loop" else 1))) + 1)
	t = np.arange(n) / FPS
	tl = clip.local(t)
	pose = pose_rest(n)
	for b in BOXES:
		e, e0 = clip.r(b, tl), clip.r(b, np.zeros(1))
		if e is not None:
			r = np.einsum("ji,njk->nik", rot_zyx(np.radians(e0))[0], rot_zyx(np.radians(e)))
			pose[b]["e"] = euler_zyx(r)
		pp, p0 = clip.p(b, tl), clip.p(b, np.zeros(1))
		if pp is not None:
			pose[b]["p"] = pose[b]["p"] + (pp - p0) * np.array([1.0, -1.0, 1.0])
	return take(clip.name + " (add)", t, pose)


def euler_zyx(r: np.ndarray) -> np.ndarray:
	"""(N,3,3) Rz . Ry . Rx -> (N,3) x, y, z radians."""
	y = np.arcsin(np.clip(-r[:, 2, 0], -1, 1))
	x = np.arctan2(r[:, 2, 1], r[:, 2, 2])
	z = np.arctan2(r[:, 1, 0], r[:, 0, 0])
	return np.stack([x, y, z], -1)


# ---------------------------------------------------------------- the mod at a speed

#: a foot of a clip as written stands while its sole is this near the clip's floor (the clip's lowest sole), m: as
#: near as feel.py takes a recorded foot to stand
STANCE_NEAR = 0.03
#: the clip's floor: this percentile of each sole (a frame where a foot jumps through the floor does not set it;
#: a foot of a rig may stand a centimetre lower than the other)
FLOOR_PCT = 5


def clip_stance(clip: Clip, n: int = 600) -> np.ndarray:
	"""(n,2) over the clip's length: where each foot stands in the clip as written (its root's shift in: the clip's
	own floor), right and left."""
	if getattr(clip, "_stance", None) is None or len(clip._stance) != n:
		t = clip.length * np.arange(n) / n
		tk = take(clip.name, t, clip_pose(clip, t))
		floor = np.percentile(tk.sole, FLOOR_PCT, axis=0)
		clip._stance = tk.sole <= floor + STANCE_NEAR
	return clip._stance


def phase(clip: Clip) -> float:
	"""BotPoser.phase: where in the clip (share of its length) to be at walkPos 0."""
	if getattr(clip, "_phase", None) is None:
		clip._phase = _phase(clip)
	return clip._phase


def _phase(clip: Clip) -> float:
	n = 240
	ts = clip.length * np.arange(n) / n
	r = clip.r("right_leg", ts)[:, 0]
	lt = clip.r("left_leg", ts)
	l = lt[:, 0] if lt is not None else np.zeros(n)
	d = r - l
	i_best = int(np.argmax(r))          # the first of equal maxima, as the strict > there
	back = i_best / n
	pas, near = back + 0.25, float("inf")
	for i in range(n):
		a, b = d[i], d[(i + 1) % n]
		if a <= 0 or b > 0:
			continue
		at = (i + a / (a - b)) / n
		off = abs(jmod(jmod(at - back - 0.25, 1.0) + 1.5, 1.0) - 0.5)
		if off < near:
			near, pas = off, at
	return jmod(jmod(pas - 0.25, 1.0) + 1.0, 1.0)


def running(s):
	return smooth((s - RUN_FROM) / (RUN_FULL - RUN_FROM))


def tempo(s, mode):
	return 1 - (1 - TEMPO_RUN) * running(s) if mode == "tempo" else 1.0


def stride(s, mode):
	return 1 + (STRIDE_RUN - 1) * running(s) if mode == "stride" else 1.0


def gait_clips(heading: float) -> tuple[str, ...]:
	"""BotPoser.gaitClips: walk, jog, sprint, back, and the strafe to the side it goes (heading in radians)."""
	return GAIT + ("back", "strafe_right" if math.sin(heading) > 0 else "strafe_left")


def gait_weights(s: float, heading: float = 0.0) -> np.ndarray:
	"""BotPoser.gaitWeights: each of gait_clips' weights at s blocks a tick going `heading` radians off ahead."""
	c, si = math.cos(heading), math.sin(heading)
	ahead, back, side = max(c, 0.0), max(-c, 0.0), abs(si)
	tot = ahead + back + side
	jog = float(smooth((s - JOG_FROM) / (JOG_FULL - JOG_FROM)))
	spr = float(smooth((s - SPRINT_FROM) / (SPRINT_FULL - SPRINT_FROM)))
	return np.array([ahead / tot * (1 - jog), ahead / tot * jog * (1 - spr), ahead / tot * jog * spr, back / tot, side / tot])


def steady(s: float, mode: str = "vanilla", tempo_k: float = 1.0) -> dict:
	"""BotAnimator.move held at s blocks a tick: walk (vanilla's swing speed), walkPos a second, a step's time."""
	walk = min(4 * s, 1.0)
	rate = walk * tempo(s, mode) * tempo_k * 20.0
	return {"walk": walk, "rate": rate, "cycle_s": CYCLE / rate, "steps_s": 2 * rate / CYCLE}


def lowest_limb(thigh_m: np.ndarray, shin_e: np.ndarray, shin_p) -> np.ndarray:
	"""BotPoser.limb: the lowest corner (largest model y) of a leg's two boxes, px. thigh_m (N,4,4) its model frame."""
	def low(m, x0, y0, h):
		ys = []
		for i in range(8):
			v = np.array([x0 + (i & 1) * 4.0, y0 + (i >> 1 & 1) * h, -2.0 + (i >> 2 & 1) * 4.0, 1.0])
			ys.append((m @ v)[:, 1])
		return np.max(np.stack(ys, -1), -1)
	a = low(thigh_m, -2.0, 0.0, HALF)
	m2 = np.zeros_like(thigh_m)
	m2[:, :3, :3] = rot_zyx(shin_e)
	m2[:, :3, 3] = shin_p
	m2[:, 3, 3] = 1
	return np.maximum(a, low(thigh_m @ m2, -2.0, 0.0, HALF))


LEG_BONES = ("right_leg", "right_shin", "left_leg", "left_shin")


def played(clips: dict[str, Clip], s: float, t: np.ndarray, mode: str = "vanilla", tempo_k: float = 1.0,
		stride_k: float = 1.0, heading_deg: float = 0.0, alert: float = 0.0, plant: bool = True,
		hips: bool = False) -> tuple[feel.Take, dict]:
	"""The legs and the trunk as BotPoser poses a mob on the move at s blocks a tick (held), `heading_deg` off ahead
	(0 ahead, 180 back, 90 to its right), at times t (s): BotPoser.gait, plant, stoop; the arms, the head and the
	clips' root are not the clips' there (code poses them) and stay at rest. tempo_k, stride_k: a trial's further
	share of the steps' pace and of the thighs' swing (1: the mod's). A trial too: plant off; hips on (the clips' root
	and thigh pivots played as written, blended as their turns: the hips go up and down with the steps)."""
	if s < MOVE_FULL:
		raise ValueError("below MOVE_FULL vanilla's swing comes into the legs: not modelled")
	h = math.radians(heading_deg)
	st = steady(s, mode, tempo_k)
	wp = st["rate"] * t
	u = wp / CYCLE
	n = len(t)
	names = gait_clips(h)
	ws = gait_weights(s, h)
	acc = {b: np.zeros((n, 3)) for b in LEG_BONES}
	shift = {b: np.zeros((n, 3)) for b in ("root", "right_leg", "left_leg")}
	total = 0.0
	for w, name in zip(ws, names):
		if w <= 1e-4 or name not in clips:
			continue
		c = clips[name]
		tc = np.mod(u + phase(c), 1.0) * c.length
		for b in acc:
			e = c.r(b, tc)
			if e is not None:
				acc[b] += w * e
		for b in shift:
			pp = c.p(b, tc)
			if pp is not None:
				shift[b] += w * pp
		total += w
	pose = pose_rest(n)
	# ahead only (the walk's, jog's and sprint's share) the thighs swing wider (stride)
	wide = 1 + (stride(s, mode) * stride_k - 1) * float(ws[:3].sum())
	for b, a in acc.items():
		x = a / total
		if b.endswith("_leg") and wide != 1:
			x = x.copy()
			x[:, 0] = STRIDE_MIDDLE + (x[:, 0] - STRIDE_MIDDLE) * wide
		pose[b]["e"] = np.radians(x)
	if hips:
		flip = np.array([1.0, -1.0, 1.0])
		for b in UPPER:
			pose[b]["p"] = pose[b]["p"] + shift["root"] / total * flip
		for b in ("right_leg", "left_leg"):
			pose[b]["p"] = pose[b]["p"] + shift[b] / total * flip
	run = ws[1] * JOG_RUN + ws[2]
	tac = alert * (1 - run)
	knees = RUN_BEND * run
	# plant: the foot it stands on hung under its knee (walking back the legs the other way round)
	swing = np.sin(wp * 0.6662) * (1 - 2 * ws[3])
	stepping = float(smooth(st["walk"] / PLANT_WALK))
	wr = smooth((PLANT_SWING - swing) / PLANT_SWING)
	wl = smooth((PLANT_SWING + swing) / PLANT_SWING)
	if knees > 0:
		for leg in ("right", "left"):
			pose[leg + "_leg"]["e"][:, 0] -= knees
			pose[leg + "_shin"]["e"][:, 0] += knees
	for leg, w0 in (("right", wr), ("left", wl)):
		if not plant:
			break
		w = (1 - stepping) + stepping * w0
		r = rot_zyx(pose[leg + "_leg"]["e"])
		down = np.hypot(r[:, 1, 1], r[:, 1, 2])
		w = w * smooth((down - 0.2) / 0.3)
		k = np.clip(np.arctan2(r[:, 1, 2], r[:, 1, 1]), 0.0, KNEE_MOST)
		e = pose[leg + "_shin"]["e"]
		e[:, 0] = e[:, 0] + w * (k - e[:, 0])
		e[:, 1] *= 1 - w
		e[:, 2] *= 1 - w
	# stoop: the trunk leaned into the run, the hips back, all of it down onto the lower foot (DOWN_MOST at most)
	lean_r = RUN_LEAN * run + TAC_LEAN * tac
	if lean_r > 0 or knees > 0:
		pose["body"]["e"][:, 0] += lean_r
		for b in ("right_leg", "left_leg"):
			pose[b]["p"][:, 2] += 12 * math.sin(lean_r)
		for b in ("right_arm", "left_arm"):
			pose[b]["p"][:, 2] += 2 * math.sin(lean_r)
		lows = []
		for leg in ("right", "left"):
			m = np.zeros((n, 4, 4))
			m[:, :3, :3] = rot_zyx(pose[leg + "_leg"]["e"])
			m[:, :3, 3] = pose[leg + "_leg"]["p"]
			m[:, 3, 3] = 1
			lows.append(lowest_limb(m, pose[leg + "_shin"]["e"], pose[leg + "_shin"]["p"]))
		low = np.maximum(*lows)
		down = np.clip(GROUND - low, 0.0, DOWN_MOST * min(1.0, knees / RUN_BEND)) if knees > 0 else np.zeros(n)
		for b in UPPER:
			pose[b]["p"][:, 1] += down
	# the game's way on: yaw 0 faces +z, the heading turns it to its right (-x)
	way = np.array([-math.sin(h), 0.0, math.cos(h)])
	root = np.outer(s * 20.0 * t, way)
	tk = take(f"{s:.2f}", t, pose, root)
	tk.extra["fwd"] = np.tile(way[[0, 2]], (n, 1))
	# where each foot carries it by the clip's own stance (the clip of most weight, at its phase)
	main = names[int(np.argmax(ws))]
	if main in clips:
		c = clips[main]
		grid = clip_stance(c)
		ph = np.mod(u + phase(c), 1.0)
		tk.extra["stance"] = grid[np.minimum((ph * len(grid)).astype(int), len(grid) - 1)]
	info = dict(st, weights=dict(zip(names, ws)), main=main, run=run, wide=wide, lean_deg=math.degrees(lean_r),
			speed=s * 20.0)
	return tk, info


# ---------------------------------------------------------------- the gait's numbers, both sides alike

def runs(mask: np.ndarray, least: int = 2, gap: int = 2) -> list[tuple[int, int]]:
	"""[a, b) runs of True at least `least` frames long, runs closer than `gap` frames joined."""
	out = []
	i, n = 0, len(mask)
	while i < n:
		if not mask[i]:
			i += 1
			continue
		j = i
		while j < n and mask[j]:
			j += 1
		if out and i - out[-1][1] <= gap:
			out[-1] = (out[-1][0], j)
		else:
			out.append((i, j))
		i = j
	return [(a, b) for a, b in out if b - a >= least]


def contact_floor(tk: feel.Take, ground: float = 0.01) -> np.ndarray:
	"""(N,2) a foot down: its sole within `ground` of the floor (the bot stand's rule)."""
	return tk.sole <= ground


def contact_people(tk: feel.Take, ground: float = 0.03, loose: bool = False) -> np.ndarray:
	"""(N,2) a recorded foot down: its lowest point (ankle or toe) within 3 cm of the floor (each foot's own: a
	marker's height differs a little) and slower than feel.PEOPLE_STILL over it (feel.slides' rule); `loose`: slower
	than a third of the body's speed (a runner's foot lands and leaves moving: the time it is down, for the duty)."""
	out = np.zeros(tk.sole.shape, dtype=bool)
	body = ground_speed(tk)
	for k in range(2):
		pt = low_point(tk, k)
		v = np.concatenate([[0.0], np.linalg.norm(np.diff(pt[:, [0, 2]], axis=0), axis=1)]) * FPS
		low = tk.sole[:, k] - np.percentile(tk.sole[:, k], 1) <= ground
		still = np.maximum(feel.PEOPLE_STILL, body / 3) if loose else feel.PEOPLE_STILL
		out[:, k] = low & (feel.gauss(v, 1.0) < still)
	return out


def low_point(tk: feel.Take, k: int) -> np.ndarray:
	"""(N,3) the foot's point that stands: the ankle, or the toe where lower (a recording)."""
	pt = tk.ankle[:, k].copy()
	if "toe" in tk.extra:
		toe = tk.extra["toe"][:, k]
		pt = np.where((toe[:, 1] < pt[:, 1])[:, None], toe, pt)
	return pt


def heading(tk: feel.Take) -> np.ndarray:
	"""(N,2) the way it goes on the floor (x, z), unit: the pelvis's motion smoothed (the bot's root)."""
	if "fwd" in tk.extra:
		return tk.extra["fwd"]
	root = tk.extra.get("root", tk.pelvis)
	v = feel.deriv(feel.gauss(root[:, [0, 2]], 10.0), tk.t)
	n = np.linalg.norm(v, axis=1, keepdims=True)
	return np.where(n > 1e-6, v / np.maximum(n, 1e-9), np.array([0.0, 1.0]))


def ground_speed(tk: feel.Take) -> np.ndarray:
	root = tk.extra.get("root", tk.pelvis)
	return np.linalg.norm(feel.deriv(feel.gauss(root[:, [0, 2]], 6.0), tk.t), axis=1)


def pitch_trace(tk: feel.Take, box: str, fwd: np.ndarray) -> np.ndarray:
	"""(N,) deg: a box's turn about the body's left-right axis, its angular speed summed up (no rest axis needed, so a
	recording's bone and the bot's box alike). Positive: its lower end goes ahead."""
	i = BOXES.index(box)
	r = tk.rot[:, i]
	d = np.einsum("nij,nkj->nik", r[1:], r[:-1])
	w = feel.rotvec(d)                               # rad a frame, world
	f = (fwd[1:] + fwd[:-1]) / 2
	# the left-right axis: up x ahead (x, z) -> right-handed, y up
	lat = np.stack([f[:, 1], np.zeros(len(f)), -f[:, 0]], -1)
	ang = np.degrees(np.cumsum(np.einsum("ni,ni->n", w, lat)))
	return np.concatenate([[0.0], ang])


def lean_trace(tk: feel.Take, fwd: np.ndarray) -> np.ndarray:
	"""(N,) deg: the head over the pelvis leaned ahead of the upright."""
	d = tk.head - tk.pelvis
	ahead = d[:, 0] * fwd[:, 0] + d[:, 2] * fwd[:, 1]
	return np.degrees(np.arctan2(ahead, d[:, 1]))


def lower_foot(tk: feel.Take) -> np.ndarray:
	"""(N,2) the foot that carries it: the lower of the two (a clip's own idea of its stance, wherever the floor is)."""
	r = tk.sole[:, 0] <= tk.sole[:, 1]
	return np.stack([r, ~r], -1)


def cycles(tk: feel.Take, support: np.ndarray, contact: np.ndarray | None = None, ok: np.ndarray | None = None,
		speed: np.ndarray | None = None, shortest: float = 0.25, longest: float = 2.5) -> list[dict]:
	"""Each step of both legs, from a foot's coming down to carry it (`support`) to its next, where `ok` holds all
	through: its numbers. `contact` (default: support) is where the foot is on the floor, for the duty factor."""
	n = len(tk.t)
	ok = np.ones(n, dtype=bool) if ok is None else ok
	contact = support if contact is None else contact
	fwd = heading(tk)
	v = ground_speed(tk) if speed is None else speed
	lean = lean_trace(tk, fwd)
	traces = {b: pitch_trace(tk, b, fwd) for b in ("right_leg", "left_leg", "right_arm", "left_arm", "body")}
	root = tk.extra.get("root", tk.pelvis)
	out = []
	for k, side in enumerate(("right", "left")):
		downs = runs(support[:, k])
		pt = low_point(tk, k)
		for (a, e), (b, _) in zip(downs, downs[1:]):
			T = (b - a) / FPS
			if not (shortest <= T <= longest) or not ok[a:b + 1].all():
				continue
			cyc = slice(a, b)
			# while it carries: how far the foot goes over the floor (1: the path summed frame to frame), and (2) the
			# body's way on less the foot's way back under the body, from its first frame to its last
			path = np.linalg.norm(np.diff(pt[a:e][:, [0, 2]], axis=0), axis=1)
			rel = pt[a:e] - root[a:e]
			along = rel[:, 0] * fwd[a:e, 0] + rel[:, 2] * fwd[a:e, 1]
			dur = (e - 1 - a) / FPS
			went = float(np.sum(v[a:e - 1]) / FPS)
			back = float(along[0] - along[-1])
			# the foot's speed back frame by frame: what is left sliding at the best steady speed for it (its mean)
			vf = -np.diff(along) * FPS
			rest = float(np.sum(np.abs(vf - back / dur)) / FPS * 1000) if dur > 0 else float("nan")
			swing = ~support[a:b, k]
			sp = float(np.mean(v[cyc]))
			out.append({
				"side": side, "a": a, "T": T, "steps_s": 2 / T, "speed": sp, "step_m": sp * T / 2,
				"duty": float(np.mean(contact[a:b, k])),
				"carry": float(np.mean(support[a:b, k])),
				"lift_m": float(np.max(tk.sole[a:b, k][swing])) if swing.any() else 0.0,
				"gap_m": float(np.median(tk.sole[a:e, k])),
				"bob_m": float(np.ptp(tk.pelvis[cyc, 1])),
				"lean_deg": float(np.median(lean[cyc])),
				"thigh_deg": float(np.ptp(traces[side + "_leg"][cyc])),
				"arm_deg": float(np.ptp(traces[side + "_arm"][cyc])),
				"trunk_sway_deg": float(np.ptp(traces["body"][cyc])),
				"carry_s": dur,
				"slide_mm": float(path.sum() * 1000),
				"slide2_mm": abs(went - back) * 1000,
				# the speed the foot goes back at under the body while it carries: the speed the clip expects, m/s
				"foot_back": back / dur if dur > 0 else float("nan"),
				"foot_back_m": back,
				"uneven_mm": rest,
			})
	return out


def steps(tk: feel.Take, support: np.ndarray, ok: np.ndarray | None = None, speed: np.ndarray | None = None,
		shortest: float = 0.12, longest: float = 1.25) -> list[dict]:
	"""Each step (a foot's coming down to the other's): its time, the speed meanwhile, its length."""
	n = len(tk.t)
	ok = np.ones(n, dtype=bool) if ok is None else ok
	v = ground_speed(tk) if speed is None else speed
	ev = sorted([(a, k) for k in range(2) for a, _ in runs(support[:, k])])
	out = []
	for (a, ka), (b, kb) in zip(ev, ev[1:]):
		T = (b - a) / FPS
		if ka == kb or not (shortest <= T <= longest) or not ok[a:b + 1].all():
			continue
		sp = float(np.mean(v[a:b]))
		out.append({"a": a, "T": T, "steps_s": 1 / T, "speed": sp, "step_m": sp * T})
	return out


def med_of(cs: list[dict], key: str, q: float = 50) -> float:
	xs = [c[key] for c in cs if c[key] == c[key]]
	return float(np.percentile(xs, q)) if xs else float("nan")


def straight(tk: feel.Take, least: float, most_turn: float = 60.0) -> np.ndarray:
	"""(N,) frames going on at `least` m/s or more and turning slower than `most_turn` deg/s (100STYLE goes round its
	room in curves: 60 deg/s keeps its long arcs)."""
	v = ground_speed(tk)
	yr = np.abs(feel.deriv(feel.gauss(tk.yaw["pelvis"], 6.0), tk.t))
	return (v >= least) & (yr <= most_turn)


# ---------------------------------------------------------------- people on the move

#: 100STYLE styles walked (FW) and run (FR) ahead: the plain one, the soldier's, in a hurry, long steps
STYLES = ("Neutral", "Swat", "Rushed", "BigSteps")
#: CMU takes going straight ahead (the site's own words): walks, and runs or jogs
CMU_WALKS = [(16, k) for k in (15, 16, 21, 22, 31, 32, 47, 58)] + [(35, k) for k in range(1, 6)] + [(8, k) for k in (1, 2, 3)]
CMU_RUNS = [(9, k) for k in range(1, 12)] + [(16, k) for k in (35, 36, 45, 46, 55, 56)] + \
	[(35, k) for k in range(17, 27)] + [(2, 3)] + [(127, k) for k in (3, 6, 7, 8)] + [(139, 10)] + \
	[(141, k) for k in (1, 2, 3, 34)] + [(143, k) for k in (1, 42)]


def gait_sets(root: str) -> list[tuple[str, str, tuple[int, int] | None, str]]:
	"""(kind, path, cut, label) of every recording going ahead measured here."""
	style = os.path.join(root, "100style", "100STYLE")
	cuts = {}
	fc = os.path.join(style, "Frame_Cuts.csv")
	if os.path.exists(fc):
		import csv
		with open(fc, encoding="utf-8") as f:
			for r in csv.DictReader(f):
				cuts[r["STYLE_NAME"]] = r
	out = []
	for st in STYLES:
		for mv, kind in (("FW", "walk"), ("FR", "run")):
			c = cuts.get(st, {})
			a, b = c.get(mv + "_START", "N/A"), c.get(mv + "_STOP", "N/A")
			out.append((kind, os.path.join(style, st, f"{st}_{mv}.bvh"), None if "N/A" in (a, b) else (int(a), int(b)),
					f"100STYLE {st}_{mv}"))
	cmu = os.path.join(root, "cmu", "all_asfamc", "subjects")
	for kind, lst in (("walk", CMU_WALKS), ("run", CMU_RUNS)):
		for n, k in lst:
			out.append((kind, os.path.join(cmu, f"{n:02d}", f"{n:02d}_{k:02d}.amc"), None, f"CMU {n:02d}_{k:02d}"))
	for st in ("Neutral", "Swat"):
		c = cuts.get(st, {})
		a, b = c.get("ID_START", "N/A"), c.get("ID_STOP", "N/A")
		out.append(("stand", os.path.join(style, st, f"{st}_ID.bvh"), None if "N/A" in (a, b) else (int(a), int(b)),
				f"100STYLE {st}_ID"))
	return out


def people_gait(root: str, say=None) -> list[dict]:
	"""Every recording going ahead: its take, its steps going straight on (each cycle's numbers), its feel.summary."""
	out = []
	for kind, path, cut, label in gait_sets(root):
		if not os.path.exists(path):
			if say:
				say(f"skipped (not there): {label}")
			continue
		tk = feel.person(feel.read_person(path, cut), label)
		down = contact_people(tk, loose=True)
		# going on: faster than a slow walk, not turning on the spot
		ok = straight(tk, 0.3)
		cs = cycles(tk, down, ok=ok)
		# the slide: feel.slides' own rule (low and slow), over each time a foot is down
		still = contact_people(tk)
		for c in cs:
			k = 0 if c["side"] == "right" else 1
			seg = runs(still[c["a"]:c["a"] + int(round(c["T"] * FPS)), k])
			pt = low_point(tk, k)[c["a"]:]
			c["slide_mm"] = max([float(np.linalg.norm(np.diff(pt[x:y][:, [0, 2]], axis=0), axis=1).sum() * 1000) for x, y in seg] or [0.0])
		out.append({"kind": kind, "label": label, "take": tk, "cycles": cs, "steps": steps(tk, down, ok=ok),
				"summary": feel.summary(tk, 0.03)})
	return out


def fit_power(v: np.ndarray, y: np.ndarray) -> tuple[float, float]:
	"""y = a v^b by least squares on the logs: (a, b)."""
	b, la = np.polyfit(np.log(v), np.log(y), 1)
	return float(math.exp(la)), float(b)


#: a run leaned this far forward (the head over the pelvis) and more is a crouched run (a soldier's, a hurried one)
LEANED = 12.0
#: recordings this jerky (p99, deg/frame^2) are left out of the fits: their markers jump (CMU 141, 143_01)
JERKY = 4.5


def run_style(r: dict) -> str:
	"""walk, run (upright), run_leaned; '' for a recording left out (no whole step on the straight, or jerky)."""
	if r["kind"] not in ("walk", "run") or not r["cycles"] or r["summary"]["jerk_p99"] > JERKY:
		return ""
	if r["kind"] == "walk":
		return "walk"
	return "run_leaned" if med_of(r["cycles"], "lean_deg") >= LEANED else "run"


def people_fits(rows: list[dict]) -> dict:
	"""Steps a second against the speed (bot's size): walks, upright runs and leaned runs apart, y = a v^b."""
	out = {}
	for kind, lo, hi in (("walk", 0.5, 1.9), ("run", 1.8, 5.0), ("run_leaned", 1.8, 5.0), ("run_all", 1.8, 5.0)):
		st = [x for r in rows if (run_style(r) == kind or (kind == "run_all" and run_style(r).startswith("run")))
				for x in r["steps"] if lo <= x["speed"] <= hi]
		if len(st) >= 4:
			v = np.array([x["speed"] for x in st])
			f = np.array([x["steps_s"] for x in st])
			out[kind] = {"cadence": fit_power(v, f), "n": len(st), "v": (float(v.min()), float(v.max()))}
	return out


def human_cadence(fits: dict, v: float, run: str = "run") -> float:
	"""Steps a second a person takes at v m/s (the bot's size): a run's fit (`run`: upright or leaned) from 1.8 m/s
	on (extrapolated past the recordings), the walk's below."""
	kind = run if v >= 1.8 or "walk" not in fits else "walk"
	if kind not in fits:
		return float("nan")
	a, b = fits[kind]["cadence"]
	return a * v ** b


# ---------------------------------------------------------------- the mod's gait, measured

#: speeds measured (blocks a tick): a walk (an AI mob's 0.19), the walk-to-jog blend, a march (0.27), the blend into
#: the sprint, a run (0.32)
SPEEDS = (0.10, 0.15, 0.19, 0.20, 0.22, 0.24, 0.27, 0.29, 0.30, 0.32)
#: the slide a person's standing foot has (feel.py: 30 mm summed over a stand at most)
STILL_MM = 30.0
#: steps a second no person goes past (a sprinter's at the top: some 5)
MOST_STEPS = 6.0


def measure_played(clips: dict[str, Clip], s: float, mode: str = "vanilla", tempo_k: float = 1.0,
		stride_k: float = 1.0, heading_deg: float = 0.0, n_cycles: float = 5.0, plant: bool = True,
		hips: bool = False) -> dict:
	"""A steady gait as the mod plays it: its cycles' medians (stance by the clip), the stand's own slide rule
	(feel.slides at 1 cm over the floor), the jerk and the head."""
	st = steady(s, mode, tempo_k)
	t = np.arange(int(n_cycles * st["cycle_s"] * FPS) + 1) / FPS
	tk, info = played(clips, s, t, mode, tempo_k, stride_k, heading_deg, plant=plant, hips=hips)
	cs = cycles(tk, tk.extra["stance"], contact_floor(tk), shortest=0.05)
	d = {k: med_of(cs, k) for k in ("steps_s", "duty", "carry", "lift_m", "gap_m", "bob_m", "lean_deg", "thigh_deg",
			"carry_s", "slide_mm", "slide2_mm", "foot_back", "foot_back_m", "uneven_mm")}
	d["steps_s"] = st["steps_s"]
	d["slide_mm_max"] = med_of(cs, "slide_mm", 100)
	d["n"] = len(cs)
	d.update({"floor_" + k: v for k, v in feel.slides(tk, 0.01).items()})
	j = feel.jerk(tk)
	legs = [BOXES.index(b) for b in ("body",) + LEG_BONES]
	jl = j[:, legs]
	d["jerk_p99"] = float(np.percentile(jl, 99))
	d["jerk_max"] = float(jl.max())
	d["jerk_box"] = BOXES[legs[int(np.unravel_index(np.argmax(jl), jl.shape)[1])]]
	d["head_cm"] = feel.head_travel(tk)
	d["info"] = info
	d["speed"] = s * 20.0
	return d


def scan(clips: dict[str, Clip], s: float, what: str, mode: str = "vanilla", tempo_k: float = 1.0,
		ks=None, plant: bool = True, hips: bool = False) -> dict:
	"""The share of the pace (what='tempo') or of the thighs' swing ('stride') at which the foot that carries slides
	least, the other held (tempo_k): the best k, its slide, and the ks at which it slides STILL_MM or less."""
	ks = np.round(np.arange(0.3, 4.001, 0.05), 3) if ks is None else ks
	best, rows = None, []
	for k in ks:
		# no faster than a sprinter's steps
		if what == "tempo" and steady(s, mode, k * tempo_k)["steps_s"] > MOST_STEPS:
			continue
		try:
			d = measure_played(clips, s, mode, tempo_k=k * tempo_k if what == "tempo" else tempo_k,
					stride_k=k if what == "stride" else 1.0, n_cycles=4.0, plant=plant, hips=hips)
		except ValueError:
			continue
		if d["n"] == 0 or d["slide_mm"] != d["slide_mm"]:
			continue
		rows.append((float(k), d["slide_mm"], d["slide2_mm"], d["thigh_deg"], d["steps_s"]))
		if best is None or d["slide_mm"] < best[1]:
			best = rows[-1]
	ok = [r[0] for r in rows if r[1] <= STILL_MM]
	return {"k": best[0] if best else float("nan"), "slide": best[1] if best else float("nan"),
			"slide2": best[2] if best else float("nan"), "thigh": best[3] if best else float("nan"),
			"steps_s": best[4] if best else float("nan"), "ok": (min(ok), max(ok)) if ok else None}


def measure_written(clip: Clip) -> dict:
	"""A clip as written at its own time: its gait in place (stance where a foot is within STANCE_NEAR of the clip's
	floor), the jerk, the head, the feet's slide standing."""
	tk = written(clip, 3)
	d = {"length": clip.length, "mode": clip.mode}
	floor = np.percentile(tk.sole, FLOOR_PCT, axis=0)
	con = tk.sole <= floor + STANCE_NEAR
	cs = cycles(tk, con, speed=np.zeros(len(tk.t))) if clip.mode == "loop" else []
	for k in ("steps_s", "duty", "lift_m", "bob_m", "lean_deg", "thigh_deg", "arm_deg", "carry_s", "foot_back",
			"foot_back_m", "uneven_mm"):
		d[k] = med_of(cs, k)
	d["n"] = len(cs)
	j = feel.jerk(tk)
	parts = [i for i, b in enumerate(BOXES) if b in clip.rot]
	if j.size and parts:
		jj = j[:, parts]
		d["jerk_p99"] = float(np.percentile(jj, 99))
		d["jerk_max"] = float(jj.max())
		d["jerk_box"] = BOXES[parts[int(np.unravel_index(np.argmax(jj), jj.shape)[1])]]
	else:
		d["jerk_p99"] = d["jerk_max"] = float("nan")
		d["jerk_box"] = ""
	d["head_cm"] = feel.head_travel(tk)
	d["bones"] = sorted(set(clip.rot) | set(clip.pos))
	d.update({"floor_" + k: v for k, v in feel.slides(tk, 0.01).items()})
	return d


def stand_numbers(tk: feel.Take) -> dict:
	"""Standing: how far each ankle goes over the floor at most from where it began, mm; feel.slides at 1 cm; the jerk;
	the head."""
	drift = max(float(np.max(np.linalg.norm(tk.ankle[:, k, [0, 2]] - tk.ankle[0, k, [0, 2]], axis=1))) for k in range(2)) * 1000
	j = feel.jerk(tk)
	d = {"drift_mm": drift, "jerk_p99": float(np.percentile(j, 99)) if j.size else float("nan"),
			"jerk_max": float(j.max()) if j.size else float("nan"),
			"jerk_box": BOXES[int(np.unravel_index(np.argmax(j), j.shape)[1])] if j.size else "",
			"head_cm": feel.head_travel(tk), "sole_min_cm": float(tk.sole.min() * 100), "sole_max_cm": float(tk.sole.min(1).max() * 100)}
	d.update({"floor_" + k: v for k, v in feel.slides(tk, 0.01).items()})
	return d


#: --sources: whole-body clips the gait clips come from, and other packs' walks and runs (file stem, clip)
SOURCE_CLIPS = (("ual1", "walk_loop"), ("ual1", "jog_fwd_loop"), ("ual1", "sprint_loop"), ("kaykit", "walking_a"),
		("kaykit", "running_a"), ("kaykit", "running_holdingrifle"))


def read_sources(folder: str) -> dict[str, Clip]:
	out = {}
	for stem, name in SOURCE_CLIPS:
		path = os.path.join(folder, stem + ".animation.json")
		if not os.path.exists(path):
			continue
		with open(path, encoding="utf-8") as f:
			anims = json.load(f)["animations"]
		for suffix in ("", "_lower"):
			if name + suffix in anims:
				out[f"{stem}/{name}{suffix}"] = Clip(f"{stem}/{name}{suffix}", anims[name + suffix])
	return out


# ---------------------------------------------------------------- all of it

#: how the mod plays each clip (BotPoser): gait (by walkPos, legs only), set (from the default pose), add (the
#: change from its first frame over what is there), turns (turns only)
PLAYED_AS = {"idle": "add", "hit_chest": "add", "hit_head": "add", "hit_fold": "add", "hit_back": "add",
		"hit_knock": "add", "walk": "gait", "jog": "gait", "sprint": "gait", "back": "gait", "strafe_left": "gait",
		"strafe_right": "gait", "crouch_walk": "set, crouched, by walkPos", "kneel": "set, crouched",
		"jump": "turns, legs", "throw": "set", "wave": "set", "reload": "set", "door": "set", "death": "set",
		"roll": "set", "slide": "set"}
#: the clips a mob plays standing on its feet (their feet should not move)
STANDING = ("idle", "kneel", "hit_chest", "hit_head", "hit_fold", "hit_back", "hit_knock", "throw", "wave", "reload", "door")


def analyse(bot_json: str, root: str, sources: str | None = None, say=None) -> dict:
	say = say or (lambda *_: None)
	clips = read_clips(bot_json)
	say("people ...")
	ppl = people_gait(root, say)
	fits = people_fits(ppl)
	say("the mod's gait ...")
	played_rows = {}
	for s in SPEEDS:
		for mode in ("vanilla", "tempo", "stride"):
			if mode != "vanilla" and running(s) <= 0:
				continue
			played_rows[(s, mode)] = measure_played(clips, s, mode)
	for h, s in ((180.0, 0.15), (90.0, 0.15), (-90.0, 0.15), (180.0, 0.19), (90.0, 0.19)):
		played_rows[(s, f"heading {h:.0f}")] = measure_played(clips, s, heading_deg=h)
	for s in SPEEDS:
		played_rows[(s, "hips")] = measure_played(clips, s, plant=False, hips=True)
	say("clips as written ...")
	written_rows = {n: measure_written(c) for n, c in clips.items()}
	src = read_sources(sources) if sources else {}
	src_rows = {n: measure_written(c) for n, c in src.items()}
	say("what would make the feet stand ...")
	coeffs = {}
	for s in SPEEDS:
		v = s * 20.0
		ws = gait_weights(s)
		main = GAIT[int(np.argmax(ws[:3]))]
		w = written_rows[main]
		f_c = 2.0 / clips[main].length
		f_m = steady(s)["steps_s"]
		f_h = human_cadence(fits, v, "run") if fits else float("nan")
		f_hl = human_cadence(fits, v, "run_leaned") if fits else float("nan")
		v_exp = w["foot_back"]
		need = v / (v_exp * f_m / f_c)
		row = {"main": main, "f_c": f_c, "f_m": f_m, "f_h": f_h, "f_hl": f_hl, "v_exp": v_exp,
				"v_played": v_exp * f_m / f_c, "need": need, "step_need": v / f_m, "step_h": v / f_h, "step_hl": v / f_hl,
				"step_clip": v_exp / f_c}
		for key, fh in (("h", f_h), ("hl", f_hl)):
			row["tempo_" + key] = fh / f_m
			row["stride_" + key] = v / (v_exp * fh / f_c)
		row["scan_tempo"] = scan(clips, s, "tempo")
		row["scan_stride"] = scan(clips, s, "stride")
		row["hips_tempo"] = scan(clips, s, "tempo", plant=False, hips=True)
		row["hips_stride"] = scan(clips, s, "stride", plant=False, hips=True)
		row["hips_stride_h"] = scan(clips, s, "stride", tempo_k=f_h / f_m, plant=False, hips=True) if f_h == f_h else None
		coeffs[s] = row
	swaps = {}
	for n, c in src.items():
		if not n.endswith("_lower"):
			continue
		for s in (0.19, 0.27, 0.32):
			cl2 = dict(clips)
			for g in GAIT:
				cl2[g] = Clip(g, {"animation_length": c.length, "loop": True, "bones": {}})
				cl2[g].rot, cl2[g].pos = c.rot, c.pos
			swaps[(n, s)] = measure_played(cl2, s)
	say("standing ...")
	stand = {}
	for n in STANDING:
		if n not in clips:
			continue
		c = clips[n]
		if PLAYED_AS.get(n) == "add":
			stand[n] = stand_numbers(added(c, 2 if c.mode == "loop" else 1))
		else:
			stand[n] = stand_numbers(written(c, 2))
	if "idle" in clips:
		stand["idle (as written)"] = stand_numbers(written(clips["idle"], 2))
	stand_people = {r["label"]: stand_numbers(r["take"]) for r in ppl if r["kind"] == "stand"}
	for r in ppl:
		if r["kind"] == "stand":
			stand_people[r["label"]].update({"floor_" + k: v for k, v in feel.slides(r["take"], 0.03).items()})
	say("people's other sets (feel.people) ...")
	others = feel.people(root)
	return {"clips": clips, "people": ppl, "fits": fits, "played": played_rows, "written": written_rows,
			"sources": src_rows, "coeffs": coeffs, "swaps": swaps, "stand": stand, "stand_people": stand_people,
			"others": others}


def f(x, nd=1):
	return "—" if x is None or x != x else f"{x:.{nd}f}"


def bins_table(ppl: list[dict]) -> list[str]:
	"""People's steps by speed: steps a second, the step's length, and each cycle's numbers."""
	edges = (0.5, 0.8, 1.1, 1.4, 1.8, 2.4, 2.8, 3.2, 3.6, 4.4)
	L = ["| скорость, м/с | записей | шагов | шагов/с | длина шага, м | доля опоры | подъём стопы, см | ход таза, см | "
			"наклон, ° | мах бедра, ° | мах плеча, ° |", "|---|---|---|---|---|---|---|---|---|---|---|"]
	for lo, hi in zip(edges, edges[1:]):
		st = [x for r in ppl if r["kind"] in ("walk", "run") for x in r["steps"] if lo <= x["speed"] < hi]
		cs = [x for r in ppl if r["kind"] in ("walk", "run") for x in r["cycles"] if lo <= x["speed"] < hi]
		recs = {r["label"] for r in ppl if r["kind"] in ("walk", "run") for x in r["steps"] if lo <= x["speed"] < hi}
		if not st:
			continue
		L.append(f"| {lo:.1f}–{hi:.1f} | {len(recs)} | {len(st)} | {f(med_of(st, 'steps_s'), 2)} | {f(med_of(st, 'step_m'), 2)} | "
				f"{f(med_of(cs, 'duty'), 2)} | {f(med_of(cs, 'lift_m') * 100)} | {f(med_of(cs, 'bob_m') * 100)} | "
				f"{f(med_of(cs, 'lean_deg'))} | {f(med_of(cs, 'thigh_deg'), 0)} | {f(med_of(cs, 'arm_deg'), 0)} |")
	return L


def report(r: dict, bot_json: str) -> str:
	L = ["# Клипы ботов против людей: числа", "",
		f"Сделано `tools/motion/clips_vs_people.py` из `{os.path.basename(bot_json)}`. Меры — как в `feel.py` "
		"(60 кадров/с, рост бота: нога 0,75 м, 1 блок = 1 м). Люди — 100STYLE и CMU, положенные на рост бота.", ""]
	fits = r["fits"]
	# ---- the run
	L += ["## Бег и шаг, как их играет мод", "",
		"Мод при скорости моба s (блоков/тик) играет клип ног по фазе walkPos: шагов/с = 2·min(4s, 1)·темп·20/CYCLE "
		"(CYCLE = 2π/0,6662), клипы смешаны по gaitWeights; ноги — только повороты клипа (без хода таза и без "
		"корпуса и рук клипа), опорная голень повешена под колено (plant), на бегу корпус наклонён (stoop). Опора — "
		"кадры, где стопа стоит в самом клипе (подошва в 3 см от пола клипа с его ходом таза), по фазе.", "",
		"| s, бл/тик (м/с) | клипы | шагов/с: мод / люди (бег) | шаг, м: нужен / у людей / даёт клип в моде | стопа назад в опоре, м/с | "
		"скольжение за опору, мм (путь / по краям) | неровность, мм | на полу (правило стенда): доля, мм за касание мед / макс | "
		"зазор опорной стопы, см | подъём, см | наклон, ° | рывок ног p99 / max |",
		"|---|---|---|---|---|---|---|---|---|---|---|---|"]
	for (s, mode), d in r["played"].items():
		if mode != "vanilla":
			continue
		c = r["coeffs"][s]
		w = ", ".join(f"{k} {v:.2f}" for k, v in d["info"]["weights"].items() if v > 1e-3)
		L.append(f"| {s:.2f} ({s * 20:.1f}) | {w} | {f(d['steps_s'], 2)} / {f(c['f_h'], 2)} | {f(c['step_need'], 2)} / "
				f"{f(c['step_h'], 2)} / {f(d['foot_back'] / d['steps_s'], 2)} | "
				f"{f(d['foot_back'], 2)} | {f(d['slide_mm'], 0)} / {f(d['slide2_mm'], 0)} | {f(d['uneven_mm'], 0)} | "
				f"{f(d['duty'], 2)}, {f(d['floor_slide_stand_med'], 0)} / {f(d['floor_slide_stand_max'], 0)} | "
				f"{f(d['gap_m'] * 100)} | {f(d['lift_m'] * 100)} | {f(d['lean_deg'])} | {f(d['jerk_p99'], 1)} / {f(d['jerk_max'], 1)} ({d['jerk_box']}) |")
	L += ["", "Настройка botRunGait на бегу (s ≥ 0,22): tempo (шаги реже, 0,78 от ванильных), stride (бёдра шире ×1,3).", "",
		"| s | режим | шагов/с | стопа назад, м/с | скольжение за опору, мм (путь / по краям) | на полу: мм за касание мед / макс | зазор, см | подъём, см | мах бедра, ° |",
		"|---|---|---|---|---|---|---|---|---|"]
	for (s, mode), d in r["played"].items():
		if mode in ("tempo", "stride") or (mode == "vanilla" and running(s) > 0):
			L.append(f"| {s:.2f} | {mode} | {f(d['steps_s'], 2)} | {f(d['foot_back'], 2)} | {f(d['slide_mm'], 0)} / {f(d['slide2_mm'], 0)} | "
					f"{f(d['floor_slide_stand_med'], 0)} / {f(d['floor_slide_stand_max'], 0)} | {f(d['gap_m'] * 100)} | {f(d['lift_m'] * 100)} | {f(d['thigh_deg'], 0)} |")
	L += ["", "Проба: те же клипы, но с ходом таза клипа (root и сдвиги бёдер, как записаны) и без plant — опора клипа "
		"встаёт на пол (зазор ≈ 0), голова ходит вместе с тазом (у jog на 18 см: больше 3 см от глаза сервера).", "",
		"| s | стопа назад, м/с | скольжение за опору, мм (путь / по краям) | неровность, мм | на полу: доля, мм за касание мед / макс | зазор, см | ход таза, см |",
		"|---|---|---|---|---|---|---|"]
	for (s, mode), d in r["played"].items():
		if mode == "hips":
			L.append(f"| {s:.2f} | {f(d['foot_back'], 2)} | {f(d['slide_mm'], 0)} / {f(d['slide2_mm'], 0)} | {f(d['uneven_mm'], 0)} | "
					f"{f(d['duty'], 2)}, {f(d['floor_slide_stand_med'], 0)} / {f(d['floor_slide_stand_max'], 0)} | {f(d['gap_m'] * 100)} | {f(d['bob_m'] * 100)} |")
	L += ["", "Назад и вбок (тот же разбор, ход вдоль направления движения):", "",
		"| s | куда | шагов/с | стопа против хода, м/с | скольжение за опору, мм | на полу: мм за касание мед / макс | рывок p99 / max |",
		"|---|---|---|---|---|---|---|"]
	for (s, mode), d in r["played"].items():
		if mode.startswith("heading"):
			L.append(f"| {s:.2f} ({s * 20:.1f} м/с) | {mode.replace('heading ', '')}° | {f(d['steps_s'], 2)} | {f(d['foot_back'], 2)} | {f(d['slide_mm'], 0)} | "
					f"{f(d['floor_slide_stand_med'], 0)} / {f(d['floor_slide_stand_max'], 0)} | {f(d['jerk_p99'], 1)} / {f(d['jerk_max'], 1)} |")
	# ---- coefficients
	L += ["", "## Что нужно, чтобы опора стояла", "",
		"Клип сам по себе (как записан, со своим ходом таза): его стопа в опоре идёт назад со скоростью v_клипа при его "
		"собственном темпе. Мод проигрывает его в f_мод/f_клипа раз быстрее. Чтобы опора стояла, произведение "
		"«темп × длина» должно вырасти в v_моба / v_играемая раз. «По людям»: темп ставим как у людей на этой скорости "
		"(подгонка ниже), остальное — длиной шага.", "",
		"| s (м/с) | клип | f клипа / мод / люди (бег), шагов/с | v клипа → играемая, м/с | нужно «темп×длина» | "
		"по людям: темп k / длина k | в моде сейчас: только темп k (мм) / только длина k (мм) | "
		"с ходом таза, без plant: только темп k (мм) / только длина k (мм) / длина k при темпе людей (мм) |",
		"|---|---|---|---|---|---|---|---|"]
	for s, c in r["coeffs"].items():
		def sc(x):
			if not x:
				return "—"
			ok = f", ≤30 мм при {x['ok'][0]:.2f}–{x['ok'][1]:.2f}" if x["ok"] else ""
			return f"{f(x['k'], 2)} ({f(x['slide'], 0)}{ok})"
		L.append(f"| {s:.2f} ({s * 20:.1f}) | {c['main']} | {f(c['f_c'], 2)} / {f(c['f_m'], 2)} / {f(c['f_h'], 2)} | "
				f"{f(c['v_exp'], 2)} → {f(c['v_played'], 2)} | {f(c['need'], 2)} | {f(c['tempo_h'], 2)} / {f(c['stride_h'], 2)} | "
				f"{sc(c['scan_tempo'])} / {sc(c['scan_stride'])} | "
				f"{sc(c['hips_tempo'])} / {sc(c['hips_stride'])} / {sc(c['hips_stride_h'])} |")
	L += ["", "Перебор «в моде» — вся цепочка BotPoser (plant, stoop, без хода таза), k от 0,3 до 4 через 0,05; темп не выше "
		f"{MOST_STEPS:.0f} шагов/с. Скольжение — медиана пути опорной стопы по полу за опору.", ""]
	# ---- people
	L += ["## Люди: темп и длина шага от скорости", "",
		"Шаги на прямой (быстрее 0,3 м/с, поворот медленнее 60°/с); скорость — таза по полу; всё в росте бота.", ""]
	L += bins_table(r["people"])
	names = {"walk": "Шаг", "run": "Бег прямой (наклон головы над тазом < 12°)", "run_leaned": "Бег с наклоном (≥ 12°)",
			"run_all": "Бег, все чистые записи"}
	for kind, ft in fits.items():
		a, b = ft["cadence"]
		L.append("")
		L.append(f"{names[kind]}: шагов/с = {a:.2f}·v^{b:.2f}, длина шага = v^{1 - b:.2f}/{a:.2f} "
				f"({ft['n']} шагов, v {ft['v'][0]:.1f}–{ft['v'][1]:.1f} м/с): скорость растёт на {b * 100:.0f} % темпом и "
				f"на {(1 - b) * 100:.0f} % длиной шага (доли в логарифмах)."
				+ ("" if kind == "walk" else " На 3,8 / 5,4 / 6,4 м/с: "
				+ " / ".join(f"{a * v ** b:.2f} шагов/с, шаг {v / (a * v ** b):.2f} м" for v in (3.8, 5.4, 6.4)) + "."))
	L += ["", "«Люди (бег)» в таблицах выше — подгонка прямого бега; выше 4,4 м/с записей нет, это продолжение кривой. "
		"Бег с наклоном в записях короткий по скорости (2,1–4,4 м/с) и разный по манере, его кривая дальше записей не годится. "
		"Записи вне подгонки: без целого шага на прямой или с рывком p99 > 4,5 (скачущие маркеры)."]
	L += ["", "По записям:", "", "| запись | вид (в подгонке) | циклов | v, м/с | шагов/с | шаг, м | доля опоры | подъём, см | таз, см | наклон, ° | "
		"бедро, ° | плечо, ° | скольжение опоры, мм | рывок p99 / max (коробка) | голова, см |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
	for p in r["people"]:
		if p["kind"] == "stand":
			continue
		cs, st, sm = p["cycles"], p["steps"], p["summary"]
		L.append(f"| {p['label']} | {p['kind']} ({run_style(p) or 'вне'}) | {len(cs)} | {f(med_of(st, 'speed'), 2)} | {f(med_of(st, 'steps_s'), 2)} | {f(med_of(st, 'step_m'), 2)} | "
				f"{f(med_of(cs, 'duty'), 2)} | {f(med_of(cs, 'lift_m') * 100)} | {f(med_of(cs, 'bob_m') * 100)} | {f(med_of(cs, 'lean_deg'))} | "
				f"{f(med_of(cs, 'thigh_deg'), 0)} | {f(med_of(cs, 'arm_deg'), 0)} | {f(med_of(cs, 'slide_mm'), 0)} | "
				f"{f(sm['jerk_p99'], 1)} / {f(sm['jerk_max'], 1)} ({sm['jerk_box']}) | {f(sm['head_cm'])} |")
	# ---- clips as written
	L += ["", "## Клипы как записаны (свой темп, 60 кадров/с)", "",
		"Ходьба: опора — подошва в 3 см от пола клипа. «v клипа» — скорость стопы назад в опоре: с какой скоростью "
		"клип ждёт, что пойдёт моб; «неровн.» — сколько стопа проскользит за опору даже при лучшей ровной скорости. "
		"Наклон, мах плеча, голова — только у клипов с корпусом (источники).", "",
		"| клип | длина, с | шагов/с | v клипа, м/с | шаг при ней, м | путь стопы в опоре, м | неровн., мм | доля опоры | подъём, см | "
		"таз, см | бедро, ° | наклон, ° | плечо, ° | рывок p99 / max (коробка) | голова над тазом, см |",
		"|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
	for n, d in list(r["written"].items()) + list(r["sources"].items()):
		if d["n"] == 0:
			continue
		L.append(f"| {n} | {f(d['length'], 2)} | {f(d['steps_s'], 2)} | {f(d['foot_back'], 2)} | {f(d['foot_back'] / d['steps_s'], 2)} | "
				f"{f(d['foot_back_m'], 2)} | {f(d['uneven_mm'], 0)} | {f(d['duty'], 2)} | {f(d['lift_m'] * 100)} | {f(d['bob_m'] * 100)} | "
				f"{f(d['thigh_deg'], 0)} | {f(d['lean_deg'])} | {f(d['arm_deg'], 0)} | {f(d['jerk_p99'], 1)} / {f(d['jerk_max'], 1)} ({d['jerk_box']}) | "
				f"{f(d['head_cm'])} |")
	if r["swaps"]:
		L += ["", "Клип-источник вместо walk, jog и sprint разом, в нынешней цепочке мода:", "",
			"| клип | s | стопа назад, м/с | скольжение за опору, мм | на полу: мм за касание мед / макс | зазор, см | рывок ног p99 / max |",
			"|---|---|---|---|---|---|---|"]
		for (n, s), d in r["swaps"].items():
			L.append(f"| {n} | {s:.2f} | {f(d['foot_back'], 2)} | {f(d['slide_mm'], 0)} | {f(d['floor_slide_stand_med'], 0)} / "
					f"{f(d['floor_slide_stand_max'], 0)} | {f(d['gap_m'] * 100)} | {f(d['jerk_p99'], 1)} / {f(d['jerk_max'], 1)} |")
	# ---- all clips
	L += ["", "## Все клипы: рывок, голова, стопы", "",
		"Свой темп. Рывок — по коробкам, которые клип двигает. Голова — ход над тазом в системе тела (бот: не больше 3 см "
		"от серверного глаза). Стопы — `feel.slides` с полом в 1 см (как у стенда): макс. за касание.", "",
		"| клип | как играет мод | длина, с | цикл | коробки | рывок p99 / max (коробка) | голова, см | стопы на полу: мм/кадр p95, за касание макс |",
		"|---|---|---|---|---|---|---|---|"]
	for n, d in r["written"].items():
		bones = ", ".join(b for b in d["bones"] if b != "root") + (" + root" if "root" in d["bones"] else "")
		L.append(f"| {n} | {PLAYED_AS.get(n, '?')} | {f(d['length'], 2)} | {d['mode']} | {bones} | {f(d['jerk_p99'], 1)} / {f(d['jerk_max'], 1)} ({d['jerk_box']}) | "
				f"{f(d['head_cm'])} | {f(d['floor_slide_frame_p95'], 1)}, {f(d['floor_slide_stand_max'], 0)} |")
	L += ["", "Ходьба в моде (свой темп ×, см. выше): рывок ног растёт как квадрат ускорения клипа.", "",
		"| s | рывок p99 / max (коробка) | голова, см |", "|---|---|---|"]
	for (s, mode), d in r["played"].items():
		if mode == "vanilla":
			L.append(f"| {s:.2f} | {f(d['jerk_p99'], 1)} / {f(d['jerk_max'], 1)} ({d['jerk_box']}) | {f(d['head_cm'])} |")
	# ---- standing
	L += ["", "## Стоя: стопы", "",
		"Как мод кладёт клип (add — изменение от первого кадра поверх покоя). Уход лодыжки — наибольший от места в начале, "
		"мм; подошва — ниже/выше пола, см.", "",
		"| клип | уход лодыжки, мм | на полу: мм/кадр p95, за касание макс | подошва мин / наибольшая из нижних, см | рывок p99 / max (коробка) | голова, см |",
		"|---|---|---|---|---|---|"]
	for n, d in list(r["stand"].items()) + [("люди: " + k, v) for k, v in r["stand_people"].items()]:
		L.append(f"| {n} | {f(d['drift_mm'], 0)} | {f(d['floor_slide_frame_p95'], 1)}, {f(d['floor_slide_stand_max'], 0)} | "
				f"{f(d['sole_min_cm'])} / {f(d['sole_max_cm'])} | {f(d['jerk_p99'], 2)} / {f(d['jerk_max'], 2)} ({d['jerk_box']}) | {f(d['head_cm'])} |")
	# ---- people's other sets
	L += ["", "## Люди по наборам feel.py (для остального)", "",
		"| набор | запись | рывок p99 / max (коробка) | голова, см | скольжение мм/кадр p95, за опору макс |", "|---|---|---|---|---|"]
	for kind, rows in r["others"].items():
		for tk, d, _, _ in rows:
			L.append(f"| {kind} | {tk.name} | {f(d['jerk_p99'], 1)} / {f(d['jerk_max'], 1)} ({d['jerk_box']}) | {f(d['head_cm'])} | "
					f"{f(d['slide_frame_p95'], 1)}, {f(d['slide_stand_max'], 0)} |")
	return "\n".join(L) + "\n"


def main(argv: list[str]) -> int:
	sources = None
	if "--sources" in argv:
		i = argv.index("--sources")
		sources = argv[i + 1]
		argv = argv[:i] + argv[i + 2:]
	if len(argv) < 2:
		print(__doc__)
		return 2
	r = analyse(argv[0], argv[1], sources, say=lambda m: print(m, file=sys.stderr))
	text = report(r, argv[0])
	if len(argv) >= 3:
		with open(argv[2], "w", encoding="utf-8") as fh:
			fh.write(text)
	print(text)
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
