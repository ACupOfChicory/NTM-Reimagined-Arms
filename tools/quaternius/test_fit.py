#!/usr/bin/env python3
"""fit.py on the clips the mod takes crouching: needs no pack, only animations/*.animation.json.

python3 -I tools/quaternius/test_fit.py
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bot_clips  # noqa: E402
import fit  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def clip(f, name):
    with open(os.path.join(HERE, "animations", f + ".animation.json")) as fh:
        return json.load(fh)["animations"][name]


def posed(out, t):
    """each leg of a fitted clip rebuilt from its written keys: hip, knee, ankle, the lowest corner"""
    legs = {}
    for up, low in fit.LEGS:
        hip = fit.pivot(out, up, t)
        thigh = fit.turn(out, up, t)
        shin = thigh @ fit.turn(out, low, t)
        knee = hip + thigh @ np.array([0.0, fit.THIGH, 0.0])
        legs[up] = (hip, knee, knee + shin @ np.array([0.0, fit.SHIN, 0.0]), max(fit.lowest(hip, thigh), fit.lowest(knee, shin)))
    return legs


def test_bend():
    hip, ankle = np.array([0.0, 12.0, 0.0]), np.array([0.0, 20.0, -3.0])
    knee, end = fit.bend(hip, ankle, hip + np.array([0.0, 0.0, -1.0]))
    assert abs(np.linalg.norm(knee - hip) - 6) < 1e-9 and abs(np.linalg.norm(end - knee) - 6) < 1e-9
    assert knee[2] < -1.5, "the knee ahead: %s" % knee
    # out of reach: the ankle pulled in along the same line
    knee, end = fit.bend(hip, np.array([0.0, 30.0, 0.0]), hip + np.array([0.0, 0.0, -1.0]))
    assert abs(np.linalg.norm(end - hip) - 11.98) < 1e-6 and abs(end[0]) < 1e-9 and abs(end[2]) < 1e-9


def test_crouch_clips():
    for name, (f, src, t0, t1, how) in bot_clips.FIT.items():
        c = clip(f, src)
        t1 = c["animation_length"] if t1 is None else t1
        n = max(1, round((t1 - t0) * 30))
        times = [t0 + (t1 - t0) * i / n for i in range(n + 1)] if t1 > t0 else [t0]
        out, report = fit.fit(c, bot_clips.CROUCH_NECK, times, length=t1 - t0, **how)
        # the neck where it was asked: the body is not moved (the pose layer puts it), only turned
        assert "position" not in out["bones"].get("body", {}), name
        steps = [out["animation_length"] * i / 60 for i in range(61)] if out["animation_length"] else [0.0]
        low = []
        for t in steps:
            trunk = fit.turn(out, "body", t)
            hips = np.array(bot_clips.CROUCH_NECK) + trunk @ np.array([0.0, 12.0, 0.0])
            legs = posed(out, t)
            for up, (hip, knee, ankle, lowest) in legs.items():
                # between the keys too: nothing in the ground (0.15 px: keys dropped within half a degree)
                assert lowest <= fit.GROUND + 0.15, "%s at %.3f: %s %.2f px into the ground" % (name, t, up, lowest - fit.GROUND)
            mid = (legs["right_leg"][0] + legs["left_leg"][0]) / 2
            assert np.linalg.norm((mid - hips)[1:]) < 1.5, "%s at %.3f: the legs %.2f px off the trunk's end" % (name, t, np.linalg.norm((mid - hips)[1:]))
            low.append(max(v[3] for v in legs.values()))
        # a foot on the ground through the step (the clip's own passing of the feet: within a pixel)
        assert min(low) > fit.GROUND - 1.0, "%s: both feet %.2f px up" % (name, fit.GROUND - min(low))
        if how.get("plant"):
            for r in report:
                assert abs(r["low"] - fit.GROUND) < 0.02, "%s: %s stands %.3f px off the ground" % (name, r["leg"], fit.GROUND - r["low"])
        print("%s: ok, knees to %.0f deg" % (name, max(r["knee_deg"] for r in report)))
    # down on a knee: the right knee at the ground, the left foot ahead of it
    k, _ = fit.fit(clip("ual1", "fixing_kneeling"), bot_clips.CROUCH_NECK, [2.6], length=0, **bot_clips.FIT["kneel"][4])
    legs = posed(k, 0.0)
    assert fit.GROUND - legs["right_leg"][1][1] < 3.5, "the right knee %.1f px up" % (fit.GROUND - legs["right_leg"][1][1])
    assert legs["left_leg"][2][2] < legs["right_leg"][2][2] - 8, "the feet %s %s" % (legs["left_leg"][2], legs["right_leg"][2])
    assert math.isclose(k["animation_length"], 0.0)


def _turn_at(d, w):
    """the turn d taken w of the way, as a slerp from none (BotPoser.add)"""
    return fit._turned(d, w)


def test_hits_head_at_the_eye():
    """Each flinch as the mod adds it (BotPoser.add: from its first frame, the nod a slerp after the head's own pose,
    the pivots' change straight; its weight in over HIT_IN = 1 tick and out over HIT_OUT = 3, BotAnimator): the head's
    middle within 3 cm (0.48 px) of where it was, level or looking 30 degrees up, down or aside; the trunk's fold the
    clip's (within half a degree); no leg in the ground"""
    mod = bot_clips.build(say=lambda line: None)
    for name in bot_clips.HEAD_PINNED:
        f, src = bot_clips.BOT[name]
        before, c = clip(f, src), mod[name]
        L = c["animation_length"]
        assert math.isclose(L, before["animation_length"]), name
        r0, p0 = fit.turn(c, "head", 0.0), fit.pivot(c, "head", 0.0)
        b0, s0 = fit.turn(c, "body", 0.0), fit.turn(before, "body", 0.0)
        worst = 0.0
        for t in np.linspace(0.0, L, int(L * 120) + 1):
            w = max(0.0, min(1.0, t * 20 / 1.0, (L - t) * 20 / 3.0))
            d = _turn_at(r0.T @ fit.turn(c, "head", t), w)
            for look in fit.LOOKS:
                off = w * (fit.pivot(c, "head", t) - p0) + look @ (d - np.eye(3)) @ fit.HEAD_MID
                worst = max(worst, float(np.linalg.norm(off)))
            fold = b0.T @ fit.turn(c, "body", t)
            was = s0.T @ fit.turn(before, "body", t)
            a = math.degrees(math.acos(max(-1.0, min(1.0, (np.trace(fold.T @ was) - 1.0) / 2.0))))
            assert a < 0.6, "%s at %.3f: the trunk's fold %.2f deg off the clip's" % (name, t, a)
            for up, (hip, knee, ankle, lowest) in posed(c, t).items():
                assert lowest <= fit.GROUND + 0.15, "%s at %.3f: %s %.2f px into the ground" % (name, t, up, lowest - fit.GROUND)
        assert worst <= 0.48, "%s: the head's middle %.2f px (%.1f cm) off the eye" % (name, worst, worst * 6.25)
        print("%s: ok, the head within %.1f cm" % (name, worst * 6.25))


def test_mod_file_is_current():
    """bot.animation.json holds what bot_clips.py writes from animations/"""
    with open(bot_clips.OUT) as fh:
        mod = fh.read()
    assert mod == bot_clips.text(bot_clips.build(say=lambda line: None)), "run bot_clips.py: the mod's clips are not what it writes"


if __name__ == "__main__":
    test_bend()
    test_crouch_clips()
    test_hits_head_at_the_eye()
    test_mod_file_is_current()
    print("ok")
