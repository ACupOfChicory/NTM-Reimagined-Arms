"""Checks of weight.py on made-up motion, no recordings:
python3 -I -m unittest discover -s tools/motion -p test_weight.py   (or python3 -I tools/motion/test_weight.py)

Each check builds a take whose answer is known by construction and asks weight.py for it back:

- a start and a stop whose chest leans as 2 + 4 v + 3 a(t - 0.1 s): the pooled fit gives kv 4, ka 3, lag 100 ms;
- the damped spring's closed form against a plain numerical integration, and its fit and its overshoot read back;
- a stop whose chest then swings as a spring of zeta 0.35, omega 8: settle() reads them back;
- a turn at 1.5 m/s whose chest rolls 2.5 deg per m/s^2 of v * omega, 150 ms late: turn_roll() reads them back;
- shoulders turned half a circle and elbows following them 50 ms late: arm_lag() gives the 50 ms and the angle;
- a crouch eased by smoothstep and a stand-up eased by a sine: crouches() gives their time and tells them apart.
"""
import math
import os
import sys
import unittest

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import feel  # noqa: E402
import weight as W  # noqa: E402

FPS = W.FPS


def rot_from_up(up: np.ndarray) -> np.ndarray:
	"""(N,3,3) the turn taking the world's up to each up (Rodrigues about up x it)."""
	n = len(up)
	out = np.tile(np.eye(3), (n, 1, 1))
	y = np.array([0.0, 1.0, 0.0])
	for i in range(n):
		u = up[i] / np.linalg.norm(up[i])
		ax = np.cross(y, u)
		s = np.linalg.norm(ax)
		if s < 1e-12:
			continue
		k = ax / s
		c = float(u[1])
		K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
		out[i] = np.eye(3) + s * K + (1 - c) * K @ K
	return out


def take(t, pelvis, up, yaw_deg, pts=None, sole=None):
	n = len(t)
	rot = np.tile(np.eye(3), (n, 10, 1, 1))
	rot[:, 1] = rot_from_up(up)
	yaw = np.asarray(yaw_deg, dtype=float) * np.ones(n)
	tk = feel.Take("made", t, {"head": yaw.copy(), "chest": yaw.copy(), "pelvis": yaw.copy()}, rot,
			np.stack([pelvis * [1, 0, 1] + [0.1, 0, 0], pelvis * [1, 0, 1] - [0.1, 0, 0]], 1),
			np.zeros((n, 2)) if sole is None else sole, pelvis, pelvis + [0.0, 0.75, 0.0])
	tk.extra["pts"] = pts or {}
	return tk


def up_of(pitch_deg, d2, roll_deg=None):
	"""Up vectors pitched toward d2 (x, z) and rolled toward d2 turned a quarter, deg."""
	n = len(pitch_deg)
	d2 = np.broadcast_to(d2, (n, 2))
	nn = np.stack([-d2[:, 1], d2[:, 0]], -1)
	p = np.radians(pitch_deg)
	r = np.zeros(n) if roll_deg is None else np.radians(roll_deg)
	h = np.tan(p)[:, None] * d2 + np.tan(r)[:, None] * nn
	up = np.stack([h[:, 0], np.ones(n), h[:, 1]], -1)
	return up / np.linalg.norm(up, axis=1)[:, None]


def ease(t, a, b):
	return W.smoothstep((t - a) / (b - a))


def integrate(x, t):
	return np.concatenate([[0.0], np.cumsum((x[1:] + x[:-1]) / 2 * np.diff(t))])


class Checks(unittest.TestCase):
	def test_lean_against_acceleration(self):
		t = np.arange(0, 9, 1 / FPS)
		v = 1.2 * (ease(t, 1.5, 2.5) - ease(t, 5.0, 6.0))
		a = np.gradient(v, t)
		yaw = 30.0
		d = np.array([-math.sin(math.radians(yaw)), math.cos(math.radians(yaw))])
		s = integrate(v, t)
		pelvis = np.stack([s * d[0], np.full_like(s, 0.75), s * d[1]], -1)
		a_late = np.interp(t - 0.1, t, a)
		pitch = 2 + 4 * v + 3 * a_late
		tk = take(t, pelvis, up_of(pitch, d), yaw)
		ev = W.lean_events(tk)
		self.assertEqual(sorted(e["kind"] for e in ev), ["start", "stop"])
		f = W.pooled_lean(ev)
		print(f"\npooled: kv {f['kv']:.3f}, ka {f['ka']:.3f}, lag {f['lag_ms']:.0f} ms, r2 {f['r2']:.4f}")
		self.assertAlmostEqual(f["kv"], 4, delta=0.1)
		self.assertAlmostEqual(f["ka"], 3, delta=0.1)
		self.assertAlmostEqual(f["lag_ms"], 100, delta=1)
		for e in ev:
			one = W.event_lean(e, f["kv"])
			self.assertAlmostEqual(one["ka"], 3, delta=0.15)
			self.assertAlmostEqual(one["lag_ms"], 100, delta=17)
		# the head is put straight over the pelvis: it does not move over it
		self.assertLess(max(e["head_cm"] for e in ev), 1e-6)

	def test_spring_closed_form(self):
		t = np.arange(0, 3, 1 / 600)
		for z, w in ((0.3, 8.0), (1.0, 6.0), (1.6, 10.0)):
			x, v = 2.0, -1.0
			xs = []
			for _ in t:
				xs.append(x)
				# semi-implicit Euler at 600 Hz
				v += (-2 * z * w * v - w * w * x) / 600
				x += v / 600
			got = W.spring(t, 2.0, -1.0, z, w)
			self.assertLess(float(np.max(np.abs(got - np.array(xs)))), 0.02, (z, w))

	def test_spring_fit_and_overshoot(self):
		t = np.arange(0, 2, 1 / FPS)
		x = W.spring(t, 5.0, 0.0, 0.45, 9.0)
		f = W.fit_spring(t, x)
		self.assertAlmostEqual(f["zeta"], 0.45, delta=0.021)
		self.assertAlmostEqual(f["omega"], 9.0, delta=0.26)
		os_ = -x.min() / x[0]
		self.assertAlmostEqual(os_, math.exp(-0.45 * math.pi / math.sqrt(1 - 0.45 ** 2)), delta=0.005)
		self.assertAlmostEqual(W.zeta_of_overshoot(os_), 0.45, delta=0.01)
		self.assertEqual(W.zeta_of_overshoot(0.0), 1.0)

	def test_settle(self):
		t = np.arange(0, 8, 1 / FPS)
		v = 1.2 * (1 - ease(t, 2.0, 2.8))
		yaw = -60.0
		d = np.array([-math.sin(math.radians(yaw)), math.cos(math.radians(yaw))])
		s = integrate(v, t)
		pelvis = np.stack([s * d[0], np.full_like(s, 0.75), s * d[1]], -1)
		t1 = 2.6
		pitch = np.where(t < t1, 6.0 * ease(t, 2.0, t1), 6.0 * W.spring(np.maximum(t - t1, 0), 1.0, 0.0, 0.35, 8.0))
		pitch = pitch + 3.0
		st = W.settle(take(t, pelvis, up_of(pitch, d), yaw))
		self.assertEqual(len(st), 1)
		e = st[0]
		want = math.exp(-0.35 * math.pi / math.sqrt(1 - 0.35 ** 2))
		print(f"\nsettle: swing {e['swing']:.2f}, over {e['over']:.2f} ({e['over_share']:.3f}, spring {want:.3f}), zeta {e['zeta']:.2f}, "
				f"omega {e['omega']:.2f}, settle {e['settle_s']:.2f} s")
		self.assertAlmostEqual(e["swing"], 6.0, delta=0.15)
		self.assertLess(e["over"], 0)
		self.assertAlmostEqual(e["over_share"], want, delta=0.03)
		self.assertAlmostEqual(e["zeta"], 0.35, delta=0.05)
		self.assertAlmostEqual(e["omega"], 8.0, delta=0.6)
		# within 1 deg of rest: 6 exp(-zeta omega t) under 1 by about t = ln 6 / 2.8
		self.assertLess(e["settle_s"], math.log(6) / (0.35 * 8) + 0.2)

	def test_turn_roll(self):
		t = np.arange(0, 6, 1 / FPS)
		w = math.radians(90) * (ease(t, 2.0, 2.3) - ease(t, 3.0, 3.3))
		psi = integrate(w, t)
		d = np.stack([-np.sin(psi), np.cos(psi)], -1)
		vel = 1.5 * d
		pos = np.stack([integrate(vel[:, 0], t), integrate(vel[:, 1], t)], -1)
		pelvis = np.stack([pos[:, 0], np.full(len(t), 0.75), pos[:, 1]], -1)
		alat = np.interp(t - 0.15, t, 1.5 * w)
		# the inside: d turned a quarter, as tilt() takes it; w > 0 turns d toward it
		n = np.stack([-d[:, 1], d[:, 0]], -1)
		self.assertGreater(float(np.einsum("i,i->", np.gradient(d, t, axis=0)[150], n[150]) * w[150]), -1e-9)
		roll = 2.5 * alat
		tk = take(t, pelvis, up_of(np.zeros(len(t)), d, roll), np.degrees(psi))
		tr = W.turn_roll(tk)
		self.assertEqual(len(tr), 1)
		e = tr[0]
		print(f"\nturn: k {e['k']:.2f}, lag {e['lag_ms']:.0f} ms, roll in {e['roll_in']:.2f}, a_lat {e['alat_max']:.2f}, rate {e['rate_max']:.0f}")
		self.assertAlmostEqual(e["k"], 2.5, delta=0.1)
		self.assertAlmostEqual(e["lag_ms"], 150, delta=17)
		self.assertAlmostEqual(e["speed"], 1.5, delta=0.02)
		self.assertGreater(e["roll_in"], 0)
		self.assertAlmostEqual(e["rate_max"], 90, delta=5)
		# k against the rate alone: v * (pi / 180) * 100 * k
		self.assertAlmostEqual(e["k_rate"], 2.5 * 1.5 * math.radians(100), delta=0.15)

	def test_arm_lag(self):
		t = np.arange(0, 5, 1 / FPS)
		w = math.radians(180) * (ease(t, 1.0, 1.3) - ease(t, 1.95, 2.25))
		ps = integrate(w, t)
		pe = np.interp(t - 0.05, t, ps)
		line = lambda p: np.stack([np.cos(p), np.zeros_like(p), np.sin(p)], -1)
		c = np.array([0.0, 1.3, 0.0])
		pts = {"right_shoulder": c + 0.2 * line(ps), "left_shoulder": c - 0.2 * line(ps),
				"right_elbow": c + 0.2 * line(pe) - [0, 0.25, 0], "left_elbow": c - 0.2 * line(pe) - [0, 0.25, 0]}
		pelvis = np.tile([0.0, 0.75, 0.0], (len(t), 1))
		tk = take(t, pelvis, up_of(np.zeros(len(t)), np.array([0.0, 1.0])), np.degrees(ps), pts)
		ar = W.arm_lag(tk)
		self.assertEqual(len(ar), 1)
		e = ar[0]
		print(f"\narms: lag {e['lag_deg']:.2f} deg (most {e['lag_most']:.2f}), {e['k']:.2f} per 100 deg/s, half {e['half_ms']:.1f} ms, "
				f"over {e['over_deg']:.2f}")
		self.assertAlmostEqual(e["half_ms"], 50, delta=2)
		# 50 ms behind at 180 deg/s
		self.assertAlmostEqual(e["lag_most"], 9.0, delta=0.4)
		self.assertAlmostEqual(e["k"], 5.0, delta=0.3)
		self.assertLess(e["over_deg"], 0.05)

	def test_crouch(self):
		t = np.arange(0, 6, 1 / FPS)
		sine = W.EASINGS["sine"][0]
		h = 0.85 - 0.30 * ease(t, 1.0, 1.6) + 0.30 * sine((t - 3.0) / 0.8)
		pelvis = np.stack([np.zeros_like(t), h, np.zeros_like(t)], -1)
		tk = take(t, pelvis, up_of(np.zeros(len(t)), np.array([0.0, 1.0])), 0.0)
		cr = W.crouches(tk)
		self.assertEqual([e["kind"] for e in cr], ["down", "up"])
		down, up = cr
		print(f"\ncrouch: down {down['s']:.3f} s (smoothstep rms {down['smoothstep_rms']:.4f}), up {up['s']:.3f} s "
				f"(smoothstep rms {up['smoothstep_rms']:.4f}, sine {up['sine_rms']:.4f}); peak rate {down['peak_rate']:.2f}, {up['peak_rate']:.2f}")
		self.assertAlmostEqual(down["s"], 0.6, delta=0.05)
		self.assertAlmostEqual(up["s"], 0.8, delta=0.06)
		self.assertAlmostEqual(down["from"], 0.85, delta=0.005)
		self.assertAlmostEqual(down["to"], 0.55, delta=0.005)
		self.assertLess(down["smoothstep_rms"], 0.02)
		self.assertLess(up["sine_rms"], up["smoothstep_rms"])
		self.assertAlmostEqual(down["peak_rate"], 1.5, delta=0.1)
		self.assertAlmostEqual(up["peak_rate"], math.pi / 2, delta=0.1)
		self.assertAlmostEqual(down["peak_at"], 0.5, delta=0.05)

	def test_body_frame(self):
		"""The head a decimetre ahead of the pelvis, the pelvis facing yaw 120: 10 cm ahead in the body's frame."""
		yaw = 120.0
		f = np.array([-math.sin(math.radians(yaw)), 0.0, math.cos(math.radians(yaw))])
		t = np.arange(0, 1, 1 / FPS)
		pelvis = np.tile([1.0, 0.75, 2.0], (len(t), 1))
		tk = take(t, pelvis, up_of(np.zeros(len(t)), np.array([0.0, 1.0])), yaw)
		tk.head = pelvis + 0.1 * f + [0, 0.7, 0]
		hr = W.head_rel(tk)
		self.assertTrue(np.allclose(hr[0], [0.0, 0.7, 0.1], atol=1e-12))


if __name__ == "__main__":
	unittest.main(verbosity=2)
