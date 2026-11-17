"""The body's weight in recordings of people: how the trunk leans and settles, how the arms lag, how one crouches.

Run:  python3 -I tools/motion/weight.py <motion-data dir> [out.md]      every measure, the tables of stage2-people.md
      python3 -I tools/motion/weight.py scan stops <motion-data dir>    CMU takes with clean starts and stops (STOPS)
      python3 -I tools/motion/weight.py scan crouch <motion-data dir>   CMU takes with crouches from standing (CROUCH)
Test: python3 -I -m unittest discover -s tools/motion -p test_weight.py

The motion-data dir is the one feel.py reads (nothing of it goes into the repository). Everything is in the bot's
size (feel.person: a leg of 0.75 m, 60 frames a second), so speeds and accelerations are the bot's, not the person's.

What is measured, for the springs an AI mob's body may get over its clips:

1. lean against acceleration (starts from standing, stops to it): the chest's pitch toward where the pelvis faces,
   fitted over a group of them as  pitch(t) = c_each + kv * v(t) + ka * a(t - lag),  v and a the pelvis's speed and
   acceleration forward, and per event with that kv; ka (deg per m/s^2), lag (ms), the most the chest leans from
   where it stands;
2. the settle after a stop: the chest's pitch from its largest swing on, against the free motion of a damped spring
   x'' + 2 zeta omega x' + omega^2 x = 0 (zeta, omega fitted, and read off the overshoot), the overshoot past where
   it comes to rest and the time it takes to stay within a degree of it;
3. the roll into a turn walking and running: the chest's roll toward the inside fitted as  roll(t) = c + k * a_lat(t
   - lag),  a_lat the pelvis's acceleration across its way (v * omega); and against the turn's rate alone;
4. the arms on a turn made slowly: how far the line through the elbows is left behind the line through the shoulders
   over the turn's core (deg, and per 100 deg/s), the swing past after it, half the turn's time apart (ms);
5. a crouch and a stand-up from standing, the feet staying put: how long, and the pelvis's height over normalised time
   against smoothstep (and a sine and smootherstep);
6. for 1 to 3, how far the head moves over the pelvis in the body's frame meanwhile (cm), and how much of each lean
   the bot could take about its hips with its head within 3 cm of the server's eye.

Python 3 and numpy only.
"""
from __future__ import annotations

import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import feel  # noqa: E402
import mocap  # noqa: E402

FPS = feel.FPS
#: the bot's trunk box, the neck (its pivot) to the hips: 12 px
BOT_TRUNK = 0.75
#: the bot's head may leave the server's eye by no more than this, m
EYE_SLACK = 0.03

#: more points than feel.person keeps: (CMU, 100STYLE); a joint's place is where its bone starts
POINTS = {
	"right_shoulder": ("rhumerus", "RightShoulder"), "right_elbow": ("rradius", "RightElbow"),
	"left_shoulder": ("lhumerus", "LeftShoulder"), "left_elbow": ("lradius", "LeftElbow"),
}


# ---------------------------------------------------------------- reading

def load(path: str, cut: tuple[int, int] | None = None, smooth: float = 1.5) -> feel.Take:
	"""A recording as feel.person makes it, with the shoulders and the elbows in take.extra["pts"] (m, the
	bot's size, smoothed and sampled as the take; only their differences mean anything, the floor is not taken off)."""
	mo = feel.read_person(path, cut)
	tk = feel.person(mo, os.path.basename(path), smooth)
	col = 0 if not mo.name.endswith(".bvh") else 1
	step = max(1, int(round(mo.fps / FPS)))
	pos, _ = mocap.forward_kinematics(mo)
	idx = [mo.skeleton.index(v[col]) for v in POINTS.values()]
	p = feel.gauss(pos[:, idx], smooth * step)[::step] * tk.extra["scale"]
	tk.extra["pts"] = {k: p[:, i] for i, k in enumerate(POINTS)}
	return tk


# ---------------------------------------------------------------- signals

def planar(take: feel.Take, sigma_s: float) -> tuple[np.ndarray, np.ndarray]:
	"""The pelvis's velocity and acceleration over the floor, (N,2) each in (x, z): the velocity smoothed by a Gaussian
	of sigma_s seconds (zero phase: it shifts nothing in time; the velocity, not the place, so that the ends keep
	their speed), the acceleration its derivative."""
	v = feel.gauss(feel.deriv(take.pelvis[:, [0, 2]], take.t), sigma_s * FPS)
	return v, feel.deriv(v, take.t)


def chest_up(take: feel.Take) -> np.ndarray:
	"""(N,3) where the chest's up points in the world (the body box's turn applied to up; every turn is nought at
	rest, up then being the world's)."""
	return take.rot[:, 1, :, 1]


def tilt(up: np.ndarray, d: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
	"""The pitch toward d and the roll toward n (d turned a quarter on the floor), deg, of an up vector; d (N,2) or
	(2,), unit, in (x, z)."""
	d = np.broadcast_to(d, (len(up), 2))
	n = np.stack([-d[:, 1], d[:, 0]], -1)
	h = up[:, [0, 2]]
	pitch = np.degrees(np.arctan2(np.einsum("ni,ni->n", h, d), up[:, 1]))
	roll = np.degrees(np.arctan2(np.einsum("ni,ni->n", h, n), up[:, 1]))
	return pitch, roll


def body_frame(take: feel.Take, v: np.ndarray) -> np.ndarray:
	"""(N,3) a vector given in the world put into the pelvis's frame: x across, y up, z ahead."""
	yaw = np.radians(take.yaw["pelvis"])
	c, s = np.cos(yaw), np.sin(yaw)
	return np.stack([c * v[:, 0] + s * v[:, 2], v[:, 1], -s * v[:, 0] + c * v[:, 2]], -1)


def head_rel(take: feel.Take) -> np.ndarray:
	"""(N,3) the head over the pelvis in the pelvis's frame, m."""
	return body_frame(take, take.head - take.pelvis)


def facing(take: feel.Take) -> np.ndarray:
	"""(N,2) the pelvis's forward on the floor, (x, z) (feel.yaw_of's sense)."""
	y = np.radians(take.yaw["pelvis"])
	return np.stack([-np.sin(y), np.cos(y)], -1)


def ix(t: np.ndarray, s: float) -> int:
	return int(np.clip(np.searchsorted(t, s), 0, len(t) - 1))


def fit_lagged(y: np.ndarray, x: np.ndarray, a: int, b: int, lags: np.ndarray) -> dict:
	"""y[a:b] = c + k x[i - lag], the lag (frames) of `lags` that fits best: k, lag, c, r2. x is held at its ends
	beyond them."""
	best = {"k": float("nan"), "lag": 0, "c": float("nan"), "r2": float("nan")}
	yy = y[a:b]
	pad = int(np.max(np.abs(lags)))
	xp = np.pad(x, (pad, pad), mode="edge")
	tot = float(np.sum((yy - yy.mean()) ** 2))
	for lag in lags:
		m = np.stack([np.ones(b - a), xp[a - lag + pad:b - lag + pad]], -1)
		coef, *_ = np.linalg.lstsq(m, yy, rcond=None)
		r2 = 1 - float(np.sum((yy - m @ coef) ** 2)) / tot if tot > 0 else 0.0
		if not best["r2"] == best["r2"] or r2 > best["r2"]:
			best = {"k": float(coef[1]), "lag": int(lag), "c": float(coef[0]), "r2": r2}
	return best


def lag_frames(lo_s: float, hi_s: float) -> np.ndarray:
	return np.arange(int(round(lo_s * FPS)), int(round(hi_s * FPS)) + 1)


# ---------------------------------------------------------------- 1, 2: starts and stops

#: a start or a stop: between a speed held of at least LEAST and a low under STILL, m/s
LEAST, STILL = 0.3, 0.2
#: the lags tried for the lean behind the acceleration, s
LEAN_LAGS = (-0.1, 0.5)


def phases(take: feel.Take, sigma_s: float = 0.2, most_turn: float = 45.0, least_stand: float = 0.25,
		steady_s: float = 0.6) -> list[dict]:
	"""Each speeding up from a low (under STILL) to the next peak, and each slowing down from a peak to a low, the peak
	LEAST or more within 2 s, going forward (the way within 60 deg of where the pelvis faces at the peak) and the
	pelvis turning less than most_turn deg from the peak to the low; a start from standing and a stop to it (the slow
	run least_stand s or more) to or from going on steadily (over steady_s s beyond the peak, as far as the recording
	goes, at 70 % of the peak's speed or more): kind, the frames of the peak, of the low and of the
	slow run's ends, the speed at the peak."""
	v, _ = planar(take, sigma_s)
	sp = np.linalg.norm(v, axis=1)
	fw = facing(take)
	yaw = take.yaw["pelvis"]
	slow = sp < STILL
	out, n, near = [], len(sp), int(2.0 * FPS)

	def ok(pk, lo, i, j, kind):
		if j - i < least_stand * FPS:
			return False
		if float(fw[pk] @ (v[pk] / sp[pk])) < 0.5 or np.ptp(yaw[min(pk, lo):max(pk, lo) + 1]) >= most_turn:
			return False
		w = sp[max(0, pk - int(steady_s * FPS)):pk + 1] if kind == "stop" else sp[pk:pk + int(steady_s * FPS) + 1]
		return float(w.min()) >= 0.7 * sp[pk]

	i = 0
	while i < n:
		if not slow[i]:
			i += 1
			continue
		j = i
		while j < n and slow[j]:
			j += 1
		lo = i + int(np.argmin(sp[i:j]))
		if i > 0:
			# the peak: the last local top before the low (not one from an earlier run)
			k, a = i - 1, max(0, i - near)
			while k > a and sp[k - 1] >= sp[k]:
				k -= 1
			if sp[k] >= LEAST and ok(k, lo, i, j, "stop"):
				out.append({"kind": "stop", "peak": k, "low": lo, "begin": i, "end": j, "hold": float(sp[k])})
		if j < n:
			k, b = j, min(n, j + near)
			while k < b - 1 and sp[k + 1] >= sp[k]:
				k += 1
			if sp[k] >= LEAST and ok(k, lo, i, j, "start"):
				out.append({"kind": "start", "peak": k, "low": lo, "begin": i, "end": j, "hold": float(sp[k])})
		i = j
	return out


def pitch_fwd(take: feel.Take, sigma_s: float) -> np.ndarray:
	"""The chest's pitch toward where the pelvis faces, deg (+ forward), smoothed as the acceleration is."""
	pitch, _ = tilt(chest_up(take), facing(take))
	return feel.gauss(pitch, sigma_s * FPS)


def lean_events(take: feel.Take, sigma_s: float = 0.2) -> list[dict]:
	"""Per start and stop (phases): the chest's pitch from where it stands (the median over up to 1 s of the slow run;
	deg), the speed and the acceleration forward (m/s, m/s^2, the acceleration padded for the lags in LEAN_LAGS), its
	most forward and back, the most acceleration, the head's most over the pelvis (cm, all ways and forward)."""
	t = take.t
	v, a = planar(take, sigma_s)
	fw = facing(take)
	vf, af = np.einsum("ni,ni->n", v, fw), np.einsum("ni,ni->n", a, fw)
	pitch = pitch_fwd(take, sigma_s)
	hr = head_rel(take)
	lo_l, hi_l = (int(round(x * FPS)) for x in LEAN_LAGS)
	afp = np.pad(af, (hi_l, -lo_l), mode="edge")
	out = []
	for ph in phases(take, sigma_s):
		if ph["kind"] == "stop":
			w0, w1 = ph["peak"] - int(0.3 * FPS), ph["low"] + int(0.5 * FPS)
			rest = slice(ph["low"], min(ph["end"], ph["low"] + int(1.0 * FPS)))
		else:
			w0, w1 = ph["low"] - int(0.5 * FPS), ph["peak"] + int(0.3 * FPS)
			rest = slice(max(ph["begin"], ph["low"] - int(1.0 * FPS)), ph["low"] + 1)
		w0, w1 = max(w0, 0), min(w1, len(t))
		if w1 - w0 < int(0.5 * FPS):
			continue
		stand = float(np.median(pitch[rest]))
		dev = pitch[w0:w1] - stand
		hd = hr[w0:w1] - np.median(hr[rest], axis=0)
		out.append({"kind": ph["kind"], "at": float(t[ph["low"]]), "hold": ph["hold"], "y": dev, "v": vf[w0:w1],
				"a": afp[w0:w1 + hi_l - lo_l], "lean_fwd": float(dev.max()), "lean_back": float(dev.min()),
				"acc_max": float(np.max(np.abs(af[w0:w1]))), "head_cm": float(np.max(np.linalg.norm(hd, axis=1)) * 100),
				"head_fwd_cm": float(np.max(np.abs(hd[:, 2])) * 100)})
	return out


def lagged(ev: dict, lag: int) -> np.ndarray:
	"""An event's acceleration lag frames before each of its frames."""
	hi_l = int(round(LEAN_LAGS[1] * FPS))
	n = len(ev["y"])
	return ev["a"][hi_l - lag:hi_l - lag + n]


def pooled_lean(events: list[dict]) -> dict:
	"""One kv, ka and lag for all the events: pitch = c_event + kv v + ka a(t - lag), each event's own c taken out (its
	means), the lag the one of LEAN_LAGS that fits best; r2 of what is left after the means."""
	lo_l, hi_l = (int(round(x * FPS)) for x in LEAN_LAGS)
	best = {"kv": float("nan"), "ka": float("nan"), "lag_ms": float("nan"), "r2": float("nan")}
	if not events:
		return best
	y = np.concatenate([e["y"] - e["y"].mean() for e in events])
	v = np.concatenate([e["v"] - e["v"].mean() for e in events])
	tot = float(np.sum(y ** 2))
	for lag in range(lo_l, hi_l + 1):
		a = np.concatenate([(lambda x: x - x.mean())(lagged(e, lag)) for e in events])
		m = np.stack([v, a], -1)
		coef, *_ = np.linalg.lstsq(m, y, rcond=None)
		r2 = 1 - float(np.sum((y - m @ coef) ** 2)) / tot if tot > 0 else 0.0
		if not (best["r2"] == best["r2"]) or r2 > best["r2"]:
			best = {"kv": float(coef[0]), "ka": float(coef[1]), "lag_ms": lag / FPS * 1000, "r2": r2}
	return best


def event_lean(ev: dict, kv: float) -> dict:
	"""One event's ka and lag with kv given: pitch - kv v = c + ka a(t - lag)."""
	lo_l, hi_l = (int(round(x * FPS)) for x in LEAN_LAGS)
	y = ev["y"] - kv * ev["v"]
	best = {"ka": float("nan"), "lag_ms": float("nan"), "r2": float("nan")}
	tot = float(np.sum((y - y.mean()) ** 2))
	for lag in range(lo_l, hi_l + 1):
		a = lagged(ev, lag)
		m = np.stack([np.ones_like(a), a], -1)
		coef, *_ = np.linalg.lstsq(m, y, rcond=None)
		r2 = 1 - float(np.sum((y - m @ coef) ** 2)) / tot if tot > 0 else 0.0
		if not (best["r2"] == best["r2"]) or r2 > best["r2"]:
			best = {"ka": float(coef[1]), "lag_ms": lag / FPS * 1000, "r2": r2}
	return best


def spring(t: np.ndarray, x0: float, v0: float, zeta: float, omega: float) -> np.ndarray:
	"""The free motion of x'' + 2 zeta omega x' + omega^2 x = 0 from x(0) = x0, x'(0) = v0."""
	if abs(zeta - 1.0) < 1e-6:
		return (x0 + (v0 + omega * x0) * t) * np.exp(-omega * t)
	if zeta < 1.0:
		wd = omega * math.sqrt(1 - zeta * zeta)
		return np.exp(-zeta * omega * t) * (x0 * np.cos(wd * t) + (v0 + zeta * omega * x0) / wd * np.sin(wd * t))
	s = omega * math.sqrt(zeta * zeta - 1)
	r1, r2 = -zeta * omega + s, -zeta * omega - s
	c2 = (v0 - r1 * x0) / (r2 - r1)
	return (x0 - c2) * np.exp(r1 * t) + c2 * np.exp(r2 * t)


def fit_spring(t: np.ndarray, x: np.ndarray, zetas=None, omegas=None) -> dict:
	"""zeta and omega (1/s) of the damped spring whose free motion from x[0] (at rest: v0 = x'[0]) is nearest x; rms."""
	zetas = np.arange(0.1, 2.001, 0.02) if zetas is None else zetas
	omegas = np.arange(1.0, 30.001, 0.25) if omegas is None else omegas
	t = t - t[0]
	v0 = float((x[1] - x[0]) / (t[1] - t[0])) if len(t) > 1 else 0.0
	best = (float("inf"), 0.0, 0.0)
	for z in zetas:
		for w in omegas:
			e = float(np.sqrt(np.mean((spring(t, x[0], v0, z, w) - x) ** 2)))
			if e < best[0]:
				best = (e, float(z), float(w))
	return {"zeta": best[1], "omega": best[2], "rms": best[0]}


def zeta_of_overshoot(os_ratio: float) -> float:
	"""The damping that gives this overshoot (the first swing past rest over the start) in a free motion from rest."""
	if os_ratio <= 0:
		return 1.0
	l = math.log(os_ratio)
	return -l / math.sqrt(math.pi ** 2 + l * l)


#: a settle read as a spring's: its swing within these, deg (a smaller one is lost in the standing's sway, a larger
#: one is a move of its own: a bow, a bend), and the spring's fit off it by no more than this share of the swing
CLEAN_SWING, CLEAN_RMS = (2.0, 15.0), 0.35


def omega_of_overshoot(share: float, t_over: float) -> float:
	"""The undamped omega (1/s) of the spring whose free motion from rest swings past by `share` t_over s later (half
	of its damped period)."""
	if not share > 0 or not t_over > 0:
		return float("nan")
	z = zeta_of_overshoot(share)
	return math.pi / t_over / math.sqrt(1 - z * z)


def settle(take: feel.Take, sigma_s: float = 0.03, band: float = 1.0, fit_s: float = 2.0, least_stand: float = 0.8) -> list[dict]:
	"""Per stop followed by standing (under STILL) for least_stand s or more: the chest's pitch toward where the pelvis
	faces, about where it comes to rest (the median of the last half of the standing, at most 3 s of it), from its
	largest swing once the speed is down to half of what it held: that swing (deg, + forward), the swing past rest
	after it (deg, + forward; and its share of the first), the time until it stays within `band` deg of rest (s, from
	the swing and from the low of the speed), the damped spring whose free motion fits it best (zeta, omega 1/s, rms
	deg) and the damping and omega its overshoot alone gives (with when it comes); whether it reads as a spring's
	(CLEAN_SWING, CLEAN_RMS); the head's most over the pelvis meanwhile (cm)."""
	t = take.t
	hr = head_rel(take)
	sp = np.linalg.norm(planar(take, 0.2)[0], axis=1)
	pitch = pitch_fwd(take, sigma_s)
	out = []
	for ph in phases(take, 0.2):
		lo, end = ph["low"], ph["end"]
		if ph["kind"] != "stop" or end - lo < int(least_stand * FPS):
			continue
		stop_end = min(end, lo + int(3.0 * FPS))
		r0 = (lo + stop_end) // 2
		rest = float(np.median(pitch[r0:stop_end]))
		x = pitch - rest
		s0 = ph["peak"]
		while s0 < lo and sp[s0] > 0.5 * ph["hold"]:
			s0 += 1
		s1 = max(s0 + 1, min(lo + int(0.6 * FPS), r0))
		i1 = s0 + int(np.argmax(np.abs(x[s0:s1])))
		p1 = float(x[i1])
		sg = 1.0 if p1 >= 0 else -1.0
		after = x[i1:min(i1 + int(1.5 * FPS), stop_end)]
		over = max(0.0, -float(np.min(sg * after)))
		t_over = float(np.argmin(sg * after)) / FPS if over > 0 else float("nan")
		beyond = np.flatnonzero(np.abs(x[i1:stop_end]) > band)
		settle_s = (beyond[-1] + 1) / FPS if beyond.size else 0.0
		j = min(i1 + int(fit_s * FPS), stop_end)
		fit = fit_spring(t[i1:j], x[i1:j]) if j - i1 > 10 else {"zeta": float("nan"), "omega": float("nan"), "rms": float("nan")}
		hd = hr[ph["peak"]:stop_end] - np.median(hr[r0:stop_end], axis=0)
		share = over / abs(p1) if p1 else 0.0
		out.append({"at": float(t[lo]), "hold": ph["hold"], "stand_s": (end - lo) / FPS, "swing": p1, "over": -sg * over,
				"over_share": share, "settle_s": settle_s, "settle_from_low_s": (i1 - lo) / FPS + settle_s,
				"zeta": fit["zeta"], "omega": fit["omega"], "fit_rms": fit["rms"], "zeta_os": zeta_of_overshoot(share),
				"t_over": t_over, "omega_os": omega_of_overshoot(share, t_over),
				"clean": bool(CLEAN_SWING[0] <= abs(p1) <= CLEAN_SWING[1] and fit["rms"] <= CLEAN_RMS * abs(p1)),
				"head_cm": float(np.max(np.linalg.norm(hd, axis=1)) * 100)})
	return out


# ---------------------------------------------------------------- 3: turns on the move

def turn_roll(take: feel.Take, sigma_s: float = 0.25, lags=(-0.3, 0.6), least_speed: float = LEAST) -> list[dict]:
	"""Per turn of 60 deg and more (feel.turns) made going at least least_speed: the chest's roll toward the turn's
	inside fitted on the pelvis's acceleration across its way (deg per m/s^2) and on the way's rate of turn alone
	(deg per 100 deg/s); the lag (ms); the peak roll in, the peak a_lat, rate and the mean speed; the head's most
	across over the pelvis meanwhile (cm)."""
	t = take.t
	v, _ = planar(take, sigma_s)
	sp = np.linalg.norm(v, axis=1)
	d = v / np.maximum(sp, 1e-6)[:, None]
	# the acceleration across the way taken frame by frame (2 frames' smoothing) and only then smoothed as the roll
	# is: the smoothing then commutes with roll = k a_lat (smoothing the velocity first cuts the corner of the turn)
	vr = feel.gauss(feel.deriv(take.pelvis[:, [0, 2]], t), 2.0)
	ar = feel.deriv(vr, t)
	spr = np.linalg.norm(vr, axis=1)
	alat_raw = (vr[:, 0] * ar[:, 1] - vr[:, 1] * ar[:, 0]) / np.maximum(spr, 1e-6)
	alat = feel.gauss(alat_raw, sigma_s * FPS)
	rate = np.degrees(feel.gauss(alat_raw / np.maximum(spr, 0.2), sigma_s * FPS))
	_, roll = tilt(chest_up(take), d)
	roll = feel.gauss(roll, sigma_s * FPS)
	hr = head_rel(take)
	out = []
	for tr in feel.turns(take):
		a0, b0 = ix(t, tr["from"]), ix(t, tr["to"]) + 1
		if np.mean(spr[a0:b0]) < least_speed or np.min(spr[a0:b0]) < 0.5 * least_speed:
			continue
		f = fit_lagged(roll, alat, a0, b0, lag_frames(*lags))
		fr = fit_lagged(roll, rate / 100.0, a0, b0, lag_frames(*lags))
		sg = 1.0 if np.mean(alat[a0:b0]) >= 0 else -1.0
		hd = hr[a0:b0, 0] - np.median(hr[a0:b0, 0])
		out.append({"from": tr["from"], "deg": tr["deg"], "speed": float(np.mean(spr[a0:b0])), "k": f["k"], "lag_ms": f["lag"] / FPS * 1000,
				"r2": f["r2"], "k_rate": fr["k"], "lag_rate_ms": fr["lag"] / FPS * 1000, "r2_rate": fr["r2"],
				"roll_in": float(np.max(sg * (roll[a0:b0] - f["c"]))), "alat_max": float(np.max(sg * alat[a0:b0])),
				"rate_max": float(np.max(np.abs(rate[a0:b0]))), "head_cm": float(np.max(np.abs(hd)) * 100)})
	return out


# ---------------------------------------------------------------- 4: the arms on a turn on the spot

def line_yaw(right: np.ndarray, left: np.ndarray) -> np.ndarray:
	"""(N,) the yaw of the line from the left point to the right one on the floor, deg, unwrapped."""
	h = right - left
	return np.degrees(np.unwrap(np.arctan2(h[:, 2], h[:, 0])))


def arm_lag(take: feel.Take, sigma_s: float = 0.08, most_speed: float = 0.7, core: float = 0.5, after_s: float = 0.5) -> list[dict]:
	"""Per turn of 60 deg and more (feel.turns) made slowly (the pelvis slower than most_speed on average; the
	recordings' turns "on the spot" step round): how far the line through the elbows is left behind the line through
	the shoulders, deg (+ behind), over the turn's core (where the shoulders turn faster than `core` of their peak):
	its median and most, and the median per 100 deg/s of the shoulders' rate there; the most it swings past (ahead)
	within after_s s of the core's end; when half of the turn is done by the elbows after the shoulders (ms); the
	arms' forward-back swing in the chest's frame (half the difference of the two arms, deg) and the elbows' shift
	(px), most over the core; and what the elbow line does off the turns (p90 of its distance from the shoulders',
	deg: the steps' own arm swing, against which the lag is read)."""
	t = take.t
	P = take.extra["pts"]
	sh = feel.gauss(line_yaw(P["right_shoulder"], P["left_shoulder"]), sigma_s * FPS)
	el = feel.gauss(line_yaw(P["right_elbow"], P["left_elbow"]), sigma_s * FPS)
	el += 360.0 * np.round((sh[0] - el[0]) / 360.0)
	rate = feel.deriv(sh, t)
	diff = el - sh
	still = np.abs(rate) < 10.0
	diff = diff - (float(np.median(diff[still])) if still.any() else float(np.median(diff)))
	# the arms' swing in the chest's frame: ahead is the shoulder line turned a quarter, the way the pelvis faces
	yaw = np.radians(sh)
	fwd = np.stack([-np.sin(yaw), np.zeros_like(yaw), np.cos(yaw)], -1)
	if np.mean(np.einsum("ni,ni->n", fwd[:, [0, 2]], facing(take))) < 0:
		fwd = -fwd

	def swing(s, e):
		u = P[e] - P[s]
		return np.degrees(np.arctan2(np.einsum("ni,ni->n", u, fwd), -u[:, 1])), np.einsum("ni,ni->n", u, fwd)

	sr, er = swing("right_shoulder", "right_elbow")
	sl, elf = swing("left_shoulder", "left_elbow")
	anti = (sr - sl) / 2
	anti = anti - (np.median(anti[still]) if still.any() else np.median(anti))
	shift = (er - elf) / 2
	shift = shift - (np.median(shift[still]) if still.any() else np.median(shift))
	sp = np.linalg.norm(planar(take, 0.2)[0], axis=1)
	trs = feel.turns(take)
	off = np.ones(len(t), bool)
	for tr in trs:
		off[ix(t, tr["from"]):ix(t, tr["to"]) + 1] = False
	noise = float(np.percentile(np.abs(diff[off]), 90)) if off.any() else float("nan")
	out = []
	for tr in trs:
		a0, b0 = ix(t, tr["from"]), ix(t, tr["to"]) + 1
		if np.mean(sp[a0:b0]) >= most_speed:
			continue
		sg = 1.0 if sh[b0 - 1] >= sh[a0] else -1.0
		lag = -sg * diff
		r = sg * rate
		peak = float(np.max(r[a0:b0]))
		c = a0 + np.flatnonzero(r[a0:b0] > core * peak)
		ie = int(c[-1])
		tail = lag[ie:min(len(t), ie + int(after_s * FPS))]
		hs, he = feel.half(t, sh, a0, b0 - 1), feel.half(t, el, a0, b0 - 1)
		out.append({"from": tr["from"], "deg": float(sh[b0 - 1] - sh[a0]), "speed": float(np.mean(sp[a0:b0])), "rate_max": peak,
				"lag_deg": float(np.median(lag[c])), "lag_most": float(np.max(lag[c])),
				"k": float(np.median(lag[c]) / (np.median(r[c]) / 100.0)), "over_deg": float(max(0.0, -tail.min())) if tail.size else 0.0,
				"half_ms": (he - hs) * 1000, "swing_deg": float(np.max(np.abs(anti[c]))), "shift_px": float(np.max(np.abs(shift[c])) * 16),
				"noise_deg": noise})
	return out


# ---------------------------------------------------------------- 5: crouch and stand up

def smoothstep(u: np.ndarray) -> np.ndarray:
	u = np.clip(u, 0, 1)
	return u * u * (3 - 2 * u)


#: other easings the shape is set against: name -> (curve, its peak rate over the mean)
EASINGS = {
	"smoothstep": (smoothstep, 1.5),
	"sine": (lambda u: (1 - np.cos(np.pi * np.clip(u, 0, 1))) / 2, math.pi / 2),
	"smootherstep": (lambda u: (lambda c: c ** 3 * (c * (6 * c - 15) + 10))(np.clip(u, 0, 1)), 1.875),
}


def crouches(take: feel.Take, least_drop: float = 0.08, hold_s: float = 0.3, most_bend: float = 35.0,
		sigma_frames: float = 2.0) -> list[dict]:
	"""Each going down from standing and each standing up to it, the feet staying put: the pelvis's height (the bot's
	size) changing one way by least_drop m or more, from or to 90 % of its standing height (its 95th percentile, which
	must be a standing pelvis's, 0.7 to 1.0 m), held within 3 cm for hold_s at the low end, the chest's pitch changing
	by most_bend deg or less (not a bend to the floor). Its time is from where the rate of the height first passes 5 %
	of its peak to where it falls back under it. Out: kind, s, from and to (m), the chest's pitch change (deg), the
	height over normalised time sampled at 21 points, its rms and most off each easing, the peak rate over the mean
	rate, when the peak rate comes (0..1)."""
	t = take.t
	n = len(t)
	h = feel.gauss(take.pelvis[:, 1], sigma_frames)
	dh = feel.deriv(h, t)
	stand = float(np.percentile(h, 95))
	if not 0.7 <= stand <= 1.0:
		return []
	foot_v = np.stack([feel.gauss(np.linalg.norm(feel.deriv(take.ankle[:, k][:, [0, 2]], t), axis=1), 3.0) for k in range(2)], -1)
	planted = np.all((take.sole < 0.06) & (foot_v < 0.3), axis=1)
	sp = np.linalg.norm(planar(take, 0.1)[0], axis=1)
	pitch = pitch_fwd(take, 0.05)
	hold = int(hold_s * FPS)
	peaks = np.flatnonzero((np.abs(dh[1:-1]) >= np.abs(dh[:-2])) & (np.abs(dh[1:-1]) > np.abs(dh[2:])) & (np.abs(dh[1:-1]) > 0.2)) + 1
	covered = np.zeros(n, bool)
	out = []
	for p in sorted(peaks, key=lambda i: -abs(dh[i])):
		if covered[p]:
			continue
		sg = np.sign(dh[p])
		thr = 0.05 * abs(dh[p])
		a = p
		while a > 0 and sg * dh[a - 1] > thr:
			a -= 1
		b = p
		while b < n - 1 and sg * dh[b + 1] > thr:
			b += 1
		covered[a:b + 1] = True
		drop = float(h[a] - h[b]) * -sg
		if drop < least_drop:
			continue
		if sg < 0:
			kind, top, low = "down", a, b
			if h[a] < 0.9 * stand or b + hold >= n or np.ptp(h[b:b + hold]) > 0.03:
				continue
			span = slice(max(0, a - 6), min(n, b + hold))
		else:
			kind, top, low = "up", b, a
			if h[b] < 0.9 * stand or a - hold < 0 or np.ptp(h[a - hold:a + 1]) > 0.03:
				continue
			span = slice(max(0, a - hold), min(n, b + 6))
		bend = float(pitch[low] - pitch[top])
		if not planted[span].all() or np.max(sp[span]) > 0.4 or abs(bend) > most_bend:
			continue
		T = t[b] - t[a]
		u = (t[a:b + 1] - t[a]) / T
		y = (h[a:b + 1] - h[a]) / (h[b] - h[a])
		pk = int(np.argmax(sg * dh[a:b + 1]))
		row = {"kind": kind, "at": float(t[a]), "s": float(T), "from": float(h[a]), "to": float(h[b]), "stand": stand,
				"bend": bend, "shape": np.interp(np.linspace(0, 1, 21), u, y), "peak_rate": float(abs(dh[a + pk]) * T / drop),
				"peak_at": float(u[pk])}
		for nm, (fn, _) in EASINGS.items():
			e = y - fn(u)
			row[nm + "_rms"] = float(np.sqrt(np.mean(e ** 2)))
			row[nm + "_max"] = float(np.max(np.abs(e)))
		out.append(row)
	return sorted(out, key=lambda r: r["at"])


# ---------------------------------------------------------------- the sets

#: the CMU recordings with a crouch or a stand-up from standing, the feet put, found by `weight.py scan crouch`
#: (scan_crouches; 77_04 left out: the same take as 139_04)
CROUCH = ("02_08", "111_10", "111_11", "113_15", "114_03", "118_32", "132_15", "137_10", "139_04", "143_34", "15_04",
		"22_17", "29_12", "31_02", "31_03", "31_04", "31_06", "31_07", "31_08", "31_10", "31_11", "32_04", "32_05", "32_11",
		"32_12", "32_13", "54_04", "54_06", "54_07", "54_09", "54_25", "55_08", "55_14", "55_28", "86_02", "86_09", "94_13",
		"94_16")


#: 100STYLE takes (uncut: the actor goes into the style and out of it) with such a crouch or stand-up
CROUCH_STYLE = ("BentKnees_ID", "Crouched_ID", "Dinosaur_ID", "OnToesCrouched_ID")
#: more CMU recordings with a start from standing or a stop to it, going forward, found by `weight.py scan stops`
STOPS = ("05_06", "05_08", "104_14", "104_19", "104_21", "104_31", "104_34", "104_35", "104_36", "104_38", "104_56",
		"104_57", "105_02", "105_05", "105_11", "105_12", "105_14", "105_15", "105_16", "105_17", "105_18", "105_19",
		"105_20", "105_22", "105_23", "105_26", "105_29", "105_34", "105_36", "105_57", "108_01", "108_02", "108_03",
		"108_27", "113_25", "114_09", "120_01", "120_08", "120_16", "120_17", "120_18", "120_21", "121_22", "121_23",
		"122_30", "122_32", "122_35", "122_36", "122_38", "122_39", "123_02", "123_05", "123_11", "125_06", "127_03",
		"127_04", "13_06", "131_01", "131_04", "132_15", "132_16", "132_23", "132_24", "132_25", "132_26", "132_28",
		"132_29", "132_30", "132_31", "132_35", "132_37", "132_39", "132_40", "132_41", "132_43", "132_44", "132_53",
		"132_54", "132_55", "132_56", "133_03", "133_04", "133_05", "133_06", "133_13", "133_14", "133_15", "133_19",
		"133_20", "133_21", "133_23", "133_24", "136_01", "136_10", "136_13", "136_17", "136_18", "136_24", "136_31",
		"136_32", "137_02", "137_04", "137_06", "137_08", "137_09", "137_10", "137_11", "137_12", "137_14", "137_16",
		"137_18", "137_20", "137_21", "137_22", "137_24", "137_26", "137_27", "137_29", "137_35", "137_38", "137_42",
		"138_01", "138_02", "138_28", "138_29", "138_30", "138_33", "138_34", "138_35", "138_36", "138_37", "138_38",
		"138_39", "138_40", "138_42", "138_44", "139_16", "139_31", "14_30", "14_32", "142_01", "142_02", "142_03",
		"142_04", "142_06", "142_08", "142_09", "142_11", "142_14", "142_16", "19_06", "41_05", "54_08", "54_18",
		"54_19", "55_14", "55_15", "55_19", "55_28", "56_05", "56_06", "70_02", "70_05", "70_11", "73_02", "73_05",
		"73_11", "74_15", "74_16", "74_19", "77_16", "77_31", "81_04", "82_09", "83_30", "83_32", "83_35", "83_36",
		"83_38", "83_39", "84_15", "84_16", "84_19", "86_06", "86_08", "86_10", "90_22", "90_23", "91_02", "91_05",
		"91_11", "91_12", "91_14", "91_15", "91_16", "91_17", "91_18", "91_19", "91_20", "91_22", "91_23", "91_26",
		"91_29", "91_34", "91_36", "91_57", "94_01", "94_12")


def cmu_path(root: str, name: str) -> str:
	return os.path.join(root, "cmu", "all_asfamc", "subjects", name.split("_")[0], name + ".amc")


def crouch_sets(root: str) -> list[tuple[str, tuple[int, int] | None]]:
	style = os.path.join(root, "100style", "100STYLE")
	return [(cmu_path(root, nm), None) for nm in CROUCH] + [(os.path.join(style, nm.split("_")[0], nm + ".bvh"), None) for nm in CROUCH_STYLE]


def _root_lines(path: str) -> np.ndarray:
	"""(N,3) the root's place from an AMC's root lines, in the file's units (no kinematics)."""
	rows = []
	with open(path, encoding="utf-8", errors="replace") as f:
		for ln in f:
			if ln.startswith("root "):
				rows.append([float(x) for x in ln.split()[1:4]])
	return np.array(rows).reshape(-1, 3)


def _cmu_all(root: str) -> list[str]:
	import glob
	return sorted(glob.glob(os.path.join(root, "cmu", "all_asfamc", "subjects", "*", "*.amc")))


def scan_crouches(root: str, prefilter: float = 0.85) -> list[tuple[str, list[dict]]]:
	"""Every CMU recording whose root dips under `prefilter` of its usual height (its 90th percentile, read off the
	AMC's root lines), measured by crouches()."""
	found = []
	for p in _cmu_all(root):
		y = _root_lines(p)[:, 1]
		if len(y) < 120 or not np.any(y < prefilter * np.percentile(y, 90)):
			continue
		try:
			ev = crouches(load(p))
		except (ValueError, KeyError) as e:
			print(f"skipped {p}: {e}", file=sys.stderr)
			continue
		if ev:
			found.append((os.path.basename(p)[:-4], ev))
	return found


def scan_stops(root: str, least_hold: float = 0.6, least_stand: float = 0.8) -> list[tuple[str, list[dict], list[dict]]]:
	"""Every CMU recording with a start or a stop going forward at least least_hold m/s (the bot's size) and a stand
	of least_stand s or more before or after it: its lean_events and settle. Prefiltered on the AMC's root lines (a
	stretch under 5 % of the root's 95th percentile speed for least_stand s)."""
	found = []
	for p in _cmu_all(root):
		r = _root_lines(p)
		if len(r) < 240:
			continue
		sp = np.convolve(np.linalg.norm(np.diff(r[:, [0, 2]], axis=0), axis=1) * 120, np.ones(24) / 24, mode="same")
		top = np.percentile(sp, 95)
		if top < 10:
			continue
		still = sp < 0.05 * top
		run, most = 0, 0
		for x in still:
			run = run + 1 if x else 0
			most = max(most, run)
		if most < least_stand * 120:
			continue
		try:
			tk = load(p)
		except (ValueError, KeyError) as e:
			print(f"skipped {p}: {e}", file=sys.stderr)
			continue
		ph = [x for x in phases(tk) if x["hold"] >= least_hold and x["end"] - x["begin"] >= least_stand * FPS]
		if not ph:
			continue
		ev = [e for e in lean_events(tk) if e["hold"] >= least_hold]
		found.append((os.path.basename(p)[:-4], ev, settle(tk, least_stand=least_stand)))
	return found


# ---------------------------------------------------------------- the report

def q(xs, p):
	xs = [x for x in xs if x == x]
	return float(np.percentile(xs, p)) if xs else float("nan")


def spread(xs, nd=1) -> str:
	"""median (p10..p90), n"""
	xs = [x for x in xs if x == x]
	if not xs:
		return "—"
	f = lambda x: f"{x:.{nd}f}".replace(".", ",")
	return f"{f(q(xs, 50))} ({f(q(xs, 10))}…{f(q(xs, 90))}), {len(xs)}"


def gather(root: str) -> dict:
	"""Every measure over the recordings it is made on; the same event met twice (CMU has a few takes twice under two
	names) kept once."""
	sets = feel.people_sets(root)
	res = {"lean": [], "settle": [], "turn": [], "arms": [], "crouch": []}

	def each(recs):
		for path, cut in recs:
			if not os.path.exists(path):
				print(f"skipped (not there): {path}", file=sys.stderr)
				continue
			yield os.path.basename(path), load(path, cut)

	seen = set()

	def once(rows, name, keys):
		out = []
		for e in rows:
			sig = tuple(round(float(e[k]), 3) for k in keys)
			if sig not in seen:
				seen.add(sig)
				out.append(dict(e, rec=name))
		return out

	for name, tk in each(sets["start_stop"] + [(cmu_path(root, nm), None) for nm in STOPS]):
		res["lean"] += once(lean_events(tk), name, ("hold", "lean_fwd", "lean_back", "acc_max"))
		res["settle"] += once(settle(tk), name, ("hold", "swing", "stand_s"))
	for name, tk in each(sets["turns_walk"] + sets["turns_run"]):
		res["turn"] += [dict(e, rec=name) for e in turn_roll(tk)]
	for name, tk in each(sets["turns_spot"]):
		res["arms"] += [dict(e, rec=name) for e in arm_lag(tk)]
	for name, tk in each(crouch_sets(root)):
		res["crouch"] += once(crouches(tk), name, ("s", "from", "to"))
	return res


def recs(rows) -> str:
	names = sorted({r["rec"] for r in rows})
	return f"{len(names)}: " + ", ".join(n.replace(".bvh", "").replace(".amc", "") for n in names)


def num(x, nd=1) -> str:
	return "—" if x != x else f"{x:.{nd}f}".replace(".", ",")


#: the groups of starts and stops by the speed they go to or come from, m/s (the bot's size: a walk is under ~1.9)
SPEEDS = (("шагом", 0.3, 1.9), ("бегом", 1.9, 99.0))


def lean_table(lean: list[dict]) -> tuple[list[str], dict]:
	L = ["| | случаев (записей) | kv, °/(м/с) | ka, °/(м/с²) | запазд., мс | R² | ka по случаям | запазд. по случаям, мс | пик вперёд, ° | пик назад, ° | abs(a) max, м/с² | голова над тазом, см | вперёд, см |",
		"|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
	fits = {}
	for kind, lab in (("start", "разгон"), ("stop", "торможение")):
		for g, lo, hi in SPEEDS:
			rs = [r for r in lean if r["kind"] == kind and lo <= r["hold"] < hi]
			if not rs:
				continue
			f = pooled_lean(rs)
			per = [event_lean(r, f["kv"]) for r in rs]
			good = [(r, p) for r, p in zip(rs, per) if p["r2"] >= 0.5]
			fits[(kind, g)] = dict(f, n=len(rs), lean_fwd=q([r["lean_fwd"] for r in rs], 90), lean_back=q([r["lean_back"] for r in rs], 10),
					ka_med=q([p["ka"] for p in per], 50), lag_med=q([p["lag_ms"] for p in per], 50), acc=q([r["acc_max"] for r in rs], 50),
					head=q([r["head_cm"] for r in rs], 50), head90=q([r["head_cm"] for r in rs], 90),
					ka_good=q([p["ka"] for _, p in good], 50), lag_good=q([p["lag_ms"] for _, p in good], 50), n_good=len(good))
			for sub, rows in (("", list(zip(rs, per))), (" (R² случая ≥ 0,5)", good)):
				if not rows:
					continue
				rr, pp = [r for r, _ in rows], [p for _, p in rows]
				pooled = (f"{num(f['kv'])} | {num(f['ka'], 2)} | {num(f['lag_ms'], 0)} | {num(f['r2'], 2)}" if not sub else "| | |")
				L.append(f"| {lab} {g}{sub} | {len(rr)} ({len({r['rec'] for r in rr})}) | {pooled} | "
					f"{spread([p['ka'] for p in pp], 2)} | {spread([p['lag_ms'] for p in pp], 0)} | {spread([r['lean_fwd'] for r in rr])} | "
					f"{spread([r['lean_back'] for r in rr])} | {spread([r['acc_max'] for r in rr], 2)} | {spread([r['head_cm'] for r in rr])} | "
					f"{spread([r['head_fwd_cm'] for r in rr])} |")
	return L, fits


def report(res: dict) -> str:
	L = ["Медиана (p10…p90), число случаев. Масштаб бота: нога 0,75 м, 60 кадров/с; скорости и ускорения — в нём же.", ""]
	lean = res["lean"]
	tl, fits = lean_table(lean)
	L += ["## 1. Наклон корпуса против ускорения таза (старты из стойки, остановки в стойку)", "",
		"Общая подгонка по группе: наклон груди вперёд = c(случай) + kv·v + ka·a(t − запаздывание); «по случаям» — ka и "
		"запаздывание каждого случая при kv группы. Пики — от стойки этого случая.", ""] + tl
	L += ["", f"Записи ({recs(lean)}).", ""]
	st = res["settle"]
	L += ["## 2. Досадка после остановки", "",
		"От наибольшего отклонения груди (после того как скорость упала вдвое) до покоя (медиана второй половины стойки).", "",
		f"«Чистые» — размах {num(CLEAN_SWING[0], 0)}…{num(CLEAN_SWING[1], 0)}° и rms подгонки не больше {num(CLEAN_RMS * 100, 0)} % размаха. "
		"ζ, ω — подгонка свободного движения пружины; по перелёту — из доли перелёта и времени до него (только где он есть).", "",
		"| | случаев (записей) | размах, ° (+ вперёд) | перелёт, ° | доля перелёта | до ±1°, с | от низа скорости, с | ζ | ω, 1/с | rms подгонки, ° | ζ по перелёту | ω по перелёту, 1/с | голова, см |",
		"|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
	for g, sel in (("все", lambda r: True), ("чистые", lambda r: r["clean"])):
		rs = [r for r in st if sel(r)]
		if not rs:
			continue
		L.append(f"| {g} | {len(rs)} ({len({r['rec'] for r in rs})}) | {spread([r['swing'] for r in rs])} | {spread([abs(r['over']) for r in rs])} | "
			f"{spread([r['over_share'] for r in rs], 2)} | {spread([r['settle_s'] for r in rs], 2)} | {spread([r['settle_from_low_s'] for r in rs], 2)} | "
			f"{spread([r['zeta'] for r in rs], 2)} | {spread([r['omega'] for r in rs])} | {spread([r['fit_rms'] for r in rs], 2)} | "
			f"{spread([r['zeta_os'] for r in rs if r['over_share'] > 0], 2)} | {spread([r['omega_os'] for r in rs])} | {spread([r['head_cm'] for r in rs])} |")
	L += ["", f"Записи ({recs(st)}).", ""]
	tr = res["turn"]
	L += ["## 3. Крен в повороте на ходу", "",
		"Крен груди внутрь = c + k·a_lat(t − запаздывание), a_lat = v·ω таза; второй столбец k — против одной скорости поворота.", "",
		"| | поворотов | v, м/с | k, °/(м/с²) | запазд., мс | R² | k, ° на 100°/с | запазд., мс | R² | крен внутрь, ° | a_lat max, м/с² | поворот max, °/с | голова вбок, см |",
		"|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
	for g, lo, hi in (("шагом", 0.0, 1.4), ("бегом", 1.4, 99.0)):
		rs = [r for r in tr if lo <= r["speed"] < hi]
		if not rs:
			continue
		L.append(f"| {g} | {len(rs)} | {spread([r['speed'] for r in rs], 2)} | {spread([r['k'] for r in rs], 2)} | {spread([r['lag_ms'] for r in rs], 0)} | "
			f"{spread([r['r2'] for r in rs], 2)} | {spread([r['k_rate'] for r in rs], 2)} | {spread([r['lag_rate_ms'] for r in rs], 0)} | "
			f"{spread([r['r2_rate'] for r in rs], 2)} | {spread([r['roll_in'] for r in rs])} | {spread([r['alat_max'] for r in rs], 2)} | "
			f"{spread([r['rate_max'] for r in rs], 0)} | {spread([r['head_cm'] for r in rs])} |")
	L += ["", f"Записи ({recs(tr)}).", ""]
	ar = res["arms"]
	L += ["## 4. Руки на повороте на месте (шагом, переступая)", "",
		"Отставание — рыскание линии локтей за линией плеч на ядре поворота (плечи быстрее половины своего пика), + позади.", "",
		"| | поворотов | плечи max, °/с | отставание, ° | его max, ° | ° на 100°/с | перелёт после, ° | половина поворота: локти позже, мс | мах рук, ° | сдвиг локтя, px | мах локтей без поворота p90, ° |",
		"|---|---|---|---|---|---|---|---|---|---|---|"]
	for g, sel in (("все", lambda r: True), ("100STYLE", lambda r: r["rec"].endswith(".bvh")), ("CMU", lambda r: r["rec"].endswith(".amc"))):
		rs = [r for r in ar if sel(r)]
		if not rs:
			continue
		L.append(f"| {g} | {len(rs)} | {spread([r['rate_max'] for r in rs], 0)} | {spread([r['lag_deg'] for r in rs])} | {spread([r['lag_most'] for r in rs])} | "
			f"{spread([r['k'] for r in rs], 2)} | {spread([r['over_deg'] for r in rs])} | {spread([r['half_ms'] for r in rs], 0)} | "
			f"{spread([r['swing_deg'] for r in rs])} | {spread([r['shift_px'] for r in rs], 2)} | {spread(sorted({round(r['noise_deg'], 3) for r in rs}))} |")
	L += ["", f"Записи ({recs(ar)}).", ""]
	cr = res["crouch"]
	L += ["## 5. Присед и вставание из стойки (стопы на месте)", ""]
	if cr:
		L += ["| | случаев | длительность, с | таз от, м | таз до, м | наклон груди, ° | rms к smoothstep | max | rms к sine | rms к smootherstep | пик скорости / средняя | где пик |",
			"|---|---|---|---|---|---|---|---|---|---|---|---|"]
		rows = []
		for kind, lab in (("down", "присед"), ("up", "вставание")):
			rows += [(lab, [r for r in cr if r["kind"] == kind]),
				(lab + ", таз ниже на 0,15 м и больше", [r for r in cr if r["kind"] == kind and abs(r["from"] - r["to"]) >= 0.15])]
		for lab, rs in rows:
			if not rs:
				continue
			L.append(f"| {lab} | {len(rs)} | {spread([r['s'] for r in rs], 2)} | {spread([r['from'] for r in rs], 2)} | {spread([r['to'] for r in rs], 2)} | "
				f"{spread([r['bend'] for r in rs], 0)} | {spread([r['smoothstep_rms'] for r in rs], 3)} | {spread([r['smoothstep_max'] for r in rs], 3)} | "
				f"{spread([r['sine_rms'] for r in rs], 3)} | {spread([r['smootherstep_rms'] for r in rs], 3)} | {spread([r['peak_rate'] for r in rs], 2)} | "
				f"{spread([r['peak_at'] for r in rs], 2)} |")
		for kind, lab in (("down", "присед"), ("up", "вставание")):
			rs = [r for r in cr if r["kind"] == kind]
			if rs:
				shape = np.median(np.stack([r["shape"] for r in rs]), axis=0)[::2]
				L += ["", f"Медианная кривая ({lab}), доля пути при u = 0; 0,1 … 1: " + "; ".join(num(x, 2) for x in shape)]
		L += ["", "smoothstep при тех же u: " + "; ".join(num(x, 2) for x in smoothstep(np.linspace(0, 1, 11))), "",
			f"Записи ({recs(cr)}).", ""]
	else:
		L += ["Таких переходов в списках нет.", ""]
	L += head_section(res, fits)
	L += constants(res, fits)
	return "\n".join(L) + "\n"


def head_section(res: dict, fits: dict) -> list[str]:
	"""How far the head goes over the pelvis in 1 to 3, and how much of each lean the bot could take turning its trunk
	about the hips (the head then goes BOT_TRUNK sin(lean)) within EYE_SLACK; about the neck it does not go at all."""
	most = math.degrees(math.asin(EYE_SLACK / BOT_TRUNK))
	L = ["## 6. Голова над тазом и правило «3 см от глаза»", "",
		f"Наклон корпуса бота вокруг бёдер уводит шею на {num(BOT_TRUNK * 100, 0)} см·sin(угла): 3 см — это {num(most, 1)}°. "
		"Вокруг шеи голова не двигается совсем, зато низ корпуса (12 px) уходит на 12·sin(угла) px.", "",
		"| что | голова над тазом у людей, см: медиана / p90 | наклон людей, °: медиана / p90 | доля, которую можно взять вокруг бёдер | низ корпуса при наклоне p90 вокруг шеи, px |",
		"|---|---|---|---|---|"]

	def row(lab, heads, leans):
		if not heads:
			return
		l50, l90 = q(leans, 50), q(leans, 90)
		L.append(f"| {lab} | {num(q(heads, 50))} / {num(q(heads, 90))} | {num(l50)} / {num(l90)} | {num(min(1.0, most / l90) * 100, 0) if l90 > 0 else '—'} % | "
			f"{num(12 * math.sin(math.radians(l90)), 1)} |")

	lean = res["lean"]
	for kind, lab in (("start", "разгон"), ("stop", "торможение")):
		for g, lo, hi in SPEEDS:
			rs = [r for r in lean if r["kind"] == kind and lo <= r["hold"] < hi]
			row(f"{lab} {g}", [r["head_cm"] for r in rs], [max(r["lean_fwd"], -r["lean_back"]) for r in rs])
	st = [r for r in res["settle"] if r["clean"]]
	row("досадка (чистые)", [r["head_cm"] for r in st], [abs(r["swing"]) for r in st])
	for g, lo, hi in (("шагом", 0.0, 1.4), ("бегом", 1.4, 99.0)):
		rs = [r for r in res["turn"] if lo <= r["speed"] < hi]
		row(f"крен в повороте {g} (голова вбок)", [r["head_cm"] for r in rs], [r["roll_in"] for r in rs])
	return L + [""]


def constants(res: dict, fits: dict) -> list[str]:
	"""The numbers above as a spring's constants in the bot's units."""
	rad = math.pi / 180
	L = ["## Константы для пружин (единицы бота)", "",
		"Тик 50 мс; ускорение в бл/тик² = м/с² / 400. Запаздывание τ пружины второго порядка без перелёта ≈ 2ζ/ω, "
		"отсюда ω = 2ζ/τ.", ""]
	run_start, run_stop = fits.get(("start", "бегом")), fits.get(("stop", "бегом"))
	walk_start, walk_stop = fits.get(("start", "шагом")), fits.get(("stop", "шагом"))
	for lab, f in (("разгон бегом", run_start), ("торможение бегом", run_stop), ("разгон шагом", walk_start), ("торможение шагом", walk_stop)):
		if not f:
			continue
		lag = max(f["lag_good"], 1.0) / 1000
		L.append(f"- {lab} (случаи, где наклон следует за ускорением, {f['n_good']} из {f['n']}; по всем ka {num(f['ka_med'], 2)}, τ {num(f['lag_med'], 0)} мс): "
			f"ka {num(f['ka_good'], 2)} °/(м/с²) = {num(f['ka_good'] * rad, 4)} рад/(м/с²) = {num(f['ka_good'] * rad * 400, 1)} рад/(бл/тик²); "
			f"τ {num(f['lag_good'], 0)} мс = {num(f['lag_good'] / 50, 1)} тика → ω ≈ {num(2 / lag, 0)} 1/с при ζ = 1; "
			f"пики (p90 / p10): +{num(f['lean_fwd'])}° / {num(f['lean_back'])}° = {num(f['lean_fwd'] * rad, 3)} / {num(f['lean_back'] * rad, 3)} рад; "
			f"низ корпуса (12 px) уходит на {num(12 * math.sin(max(abs(f['lean_fwd']), abs(f['lean_back'])) * rad), 1)} px")
	st = [r for r in res["settle"] if r["clean"]]
	if st:
		L.append(f"- досадка (чистые, {len(st)}): ζ {num(q([r['zeta'] for r in st], 50), 2)} (по перелёту {num(q([r['zeta_os'] for r in st if r['over_share'] > 0], 50), 2)}), "
			f"ω {num(q([r['omega'] for r in st], 50))} 1/с (по перелёту {num(q([r['omega_os'] for r in st], 50))}), перелёт "
			f"{num(q([r['over_share'] for r in st], 50) * 100, 0)} % размаха, до ±1° за {num(q([r['settle_s'] for r in st], 50), 2)} с "
			f"({num(q([r['settle_s'] for r in st], 50) * 20, 0)} тиков)")
	tr = res["turn"]
	for g, lo, hi in (("шагом", 0.0, 1.4), ("бегом", 1.4, 99.0)):
		rs = [r for r in tr if lo <= r["speed"] < hi]
		if rs:
			k, lag = q([r["k"] for r in rs], 50), q([r["lag_ms"] for r in rs], 50)
			L.append(f"- крен в повороте {g}: {num(k, 2)} °/(м/с²) = {num(k * rad, 4)} рад/(м/с²), τ {num(lag, 0)} мс = {num(lag / 50, 1)} тика → ω ≈ "
				f"{num(2 / max(lag, 1.0) * 1000, 0)} 1/с при ζ = 1; крен p90 {num(q([r['roll_in'] for r in rs], 90))}°")
	ar = res["arms"]
	if ar:
		L.append(f"- руки на повороте на месте: отставание {num(q([r['lag_deg'] for r in ar], 50))}° (p90 {num(q([r['lag_most'] for r in ar], 90))}°), "
			f"{num(q([r['k'] for r in ar], 50), 2)}° на 100°/с, половина поворота позже на {num(q([r['half_ms'] for r in ar], 50), 0)} мс")
	cr = res["crouch"]
	for kind, lab in (("down", "присед"), ("up", "вставание")):
		rs = [r for r in cr if r["kind"] == kind]
		if rs:
			sec = q([r["s"] for r in rs], 50)
			L.append(f"- {lab}: {num(sec, 2)} с = {num(sec * 20, 0)} тиков (p10…p90 {num(q([r['s'] for r in rs], 10), 2)}…{num(q([r['s'] for r in rs], 90), 2)} с)")
	return L + [""]


def main(argv: list[str]) -> int:
	if len(argv) >= 3 and argv[0] == "scan" and argv[1] == "crouch":
		for nm, ev in scan_crouches(argv[2]):
			print(nm, [(e["kind"], round(e["at"], 2), round(e["s"], 2), round(e["from"], 2), round(e["to"], 2), round(e["bend"]),
				round(e["smoothstep_rms"], 3)) for e in ev], flush=True)
		return 0
	if len(argv) >= 3 and argv[0] == "scan" and argv[1] == "stops":
		for nm, ev, st in scan_stops(argv[2]):
			print(nm, [(e["kind"], round(e["at"], 2), round(e["hold"], 2), round(e["lean_fwd"], 1), round(e["lean_back"], 1)) for e in ev],
				[(round(e["at"], 2), round(e["hold"], 2), round(e["swing"], 1), round(e["over"], 1), round(e["stand_s"], 1)) for e in st], flush=True)
		return 0
	if len(argv) >= 1 and os.path.isdir(argv[0]):
		text = report(gather(argv[0]))
		if len(argv) >= 2:
			with open(argv[1], "w", encoding="utf-8") as f:
				f.write(text)
		print(text)
		return 0
	print(__doc__)
	return 2


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
