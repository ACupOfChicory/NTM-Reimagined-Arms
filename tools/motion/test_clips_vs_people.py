"""Checks of clips_vs_people.py that need no recordings: python3 -I tools/motion/test_clips_vs_people.py

- one box in one frame put in the world by hand (plain trigonometry) against the script's forward kinematics;
- the slide of the walk as the mod plays it worked out two ways: the standing foot's path over the floor frame to
  frame, and the mob's way on less the foot's way back under the body from the stance's first frame to its last;
- the mod's pace: a step of both legs in 0.47 s at full walk (vanilla's), the gait's weights.
"""
import math
import os
import sys
import unittest

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import clips_vs_people as cvp  # noqa: E402

CLIPS = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "ntmgunfeel", "animations", "bot.animation.json")


def mat(x, y, z):
	"""Rz(z) . Ry(y) . Rx(x) as nested lists, radians."""
	cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
	rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
	ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
	rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
	mm = lambda a, b: [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
	return mm(rz, mm(ry, rx))


def apply(m, v):
	return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def lerp_key(track, t):
	"""a channel's value at t, linear between keys, held at the ends (as BotClip.Track.sample)"""
	times, vals = track
	if t <= times[0]:
		return list(vals[0])
	for i in range(len(times) - 1):
		if t <= times[i + 1]:
			u = (t - times[i]) / (times[i + 1] - times[i])
			return [vals[i][k] + (vals[i + 1][k] - vals[i][k]) * u for k in range(3)]
	return list(vals[-1])


class Checks(unittest.TestCase):
	@classmethod
	def setUpClass(cls):
		cls.clips = cvp.read_clips(CLIPS)

	def test_fk_by_hand(self):
		"""The right ankle of the walk as written, at 0.5 s, worked out by hand."""
		c = self.clips["walk"]
		t = 0.5
		rl = [math.radians(a) for a in lerp_key(c.rot["right_leg"], t)]
		rs = [math.radians(a) for a in lerp_key(c.rot["right_shin"], t)]
		root = lerp_key(c.pos["root"], t)
		leg = lerp_key(c.pos["right_leg"], t) if "right_leg" in c.pos else [0, 0, 0]
		# the thigh's pivot: default + its own shift + the root's, Bedrock's y up -> ModelPart's y down
		pivot = [-1.9 + leg[0] + root[0], 12 - leg[1] - root[1], 0 + leg[2] + root[2]]
		knee_to_ankle = apply(mat(*rs), [0, 6, 0])
		in_thigh = [0 + knee_to_ankle[0], 6 + knee_to_ankle[1], 0 + knee_to_ankle[2]]
		p = [pivot[i] + apply(mat(*rl), in_thigh)[i] for i in range(3)]
		# rotateY(180) . scale(-1, -1, 1) . translate(0, -1.501, 0) . p / 16 = (x/16, 1.501 - y/16, -z/16)
		world = [p[0] / 16, 1.501 - p[1] / 16, -p[2] / 16]
		tk = cvp.take("walk", np.array([t]), cvp.clip_pose(c, np.array([t])))
		got = tk.ankle[0, 0]
		print(f"\nFK by hand, walk right ankle at {t} s: {np.round(world, 4)}; script: {np.round(got, 4)}")
		self.assertTrue(np.allclose(world, got, atol=1e-9))

	def test_box_turn_in_world(self):
		"""A box's turn in the world is a turn (the 4x4's px -> m scale taken off) and the thigh's own about its pivot."""
		c = self.clips["jog"]
		tk = cvp.take("jog", np.array([0.3]), cvp.clip_pose(c, np.array([0.3])))
		r = tk.rot[0, cvp.BOXES.index("right_leg")]
		self.assertTrue(np.allclose(r @ r.T, np.eye(3), atol=1e-9))
		rl = [math.radians(a) for a in lerp_key(c.rot["right_leg"], 0.3)]
		# world turn = rotY(180) . diag(-1, -1, 1) . R_thigh = diag(1, -1, -1) . R_thigh
		want = np.diag([1.0, -1.0, -1.0]) @ np.array(mat(*rl))
		self.assertTrue(np.allclose(r, want, atol=1e-9))

	def test_pace(self):
		"""Full walk (s >= 0.25): walkPos a block a tick, a step of both legs in CYCLE ticks (vanilla's 0.47 s)."""
		st = cvp.steady(0.27)
		self.assertAlmostEqual(st["cycle_s"], 2 * math.pi / 0.6662 / 20, places=6)
		self.assertAlmostEqual(st["cycle_s"], 0.4716, places=3)
		self.assertAlmostEqual(cvp.steady(0.19)["steps_s"], 2 * 0.76 * 20 / cvp.CYCLE, places=6)
		w = cvp.gait_weights(0.22)
		self.assertAlmostEqual(w[0], 0.5, places=6)
		self.assertAlmostEqual(w[1], 0.5, places=6)
		self.assertAlmostEqual(float(w.sum()), 1.0, places=6)

	def test_slide_two_ways(self):
		"""The walk at 0.19 blocks a tick: the path summed frame to frame is no shorter than the way from end to end,
		and that is the mob's way on (3.8 m/s over the stance) less the foot's way back."""
		d = cvp.measure_played(self.clips, 0.19)
		by_speed = (d["speed"] - d["foot_back"]) * d["carry_s"] * 1000
		print(f"\nwalk at 0.19: path {d['slide_mm']:.0f} mm, end to end {d['slide2_mm']:.0f} mm, "
				f"(3.8 - {d['foot_back']:.2f}) m/s x {d['carry_s']:.3f} s = {by_speed:.0f} mm")
		self.assertGreaterEqual(d["slide_mm"] + 1e-6, d["slide2_mm"] - 1e-6)
		self.assertAlmostEqual(d["slide2_mm"], by_speed, delta=0.02 * by_speed)
		# and by hand from the take's own frames
		st = cvp.steady(0.19)
		t = np.arange(int(5 * st["cycle_s"] * cvp.FPS) + 1) / cvp.FPS
		tk, _ = cvp.played(self.clips, 0.19, t)
		stance = tk.extra["stance"][:, 0]
		runs = cvp.runs(stance)
		a, e = runs[1]
		path = float(np.sum(np.linalg.norm(np.diff(tk.ankle[a:e, 0][:, [0, 2]], axis=0), axis=1)) * 1000)
		ends = abs((tk.ankle[e - 1, 0, 2] - tk.ankle[a, 0, 2])) * 1000
		print(f"one stance of the right foot, frames {a}..{e - 1}: path {path:.0f} mm, its place moved {ends:.0f} mm")
		self.assertGreater(path, 300)


if __name__ == "__main__":
	unittest.main(verbosity=2)
