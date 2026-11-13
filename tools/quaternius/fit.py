"""Fit a carried-over clip's legs under a head that stays where the server keeps the mob's eye.

A clip lowers and sways the whole body as its rig does; an AI mob's head may not leave the eye the server looks and
shoots from (3 cm). So the clip is moved, frame by frame, till its neck is at the place given (the model's own while
standing; vanilla's crouch, 3.2 px lower, crouching), and each leg is then bent again at the knee to put its ankle
back where the clip had it over the ground: the hips move with the head, the feet stay on the ground.

Works on the clips retarget.py wrote (animations/*.animation.json): the model's ten parts, no rig needed.
"""
import math

import numpy as np

from retarget import BOX10, PIVOT, euler_to_mat, mat_to_euler, reduce_keys, rot_between, unit

GROUND = 24.0  # where the feet stand, px down from the model's origin
THIGH = SHIN = 6.0
Y = np.array([0.0, 1.0, 0.0])
LEGS = (("right_leg", "right_shin"), ("left_leg", "left_shin"))


def keys(ch):
    return sorted(((float(t), v["post"] if isinstance(v, dict) else v) for t, v in ch.items()), key=lambda k: k[0])


def at(ch, t, none=(0.0, 0.0, 0.0)):
    """a channel's value at t (linear keys); `none` when the clip has no such channel"""
    if not ch:
        return np.array(none, float)
    ks = keys(ch)
    if t <= ks[0][0]:
        return np.array(ks[0][1], float)
    for (t0, v0), (t1, v1) in zip(ks, ks[1:]):
        if t <= t1:
            u = (t - t0) / (t1 - t0) if t1 > t0 else 1.0
            return np.array(v0, float) + (np.array(v1, float) - np.array(v0, float)) * u
    return np.array(ks[-1][1], float)


def pivot(clip, bone, t):
    """a part's pivot in the model, px (Y down): its default place and the clip's position (Bedrock's: Y up)"""
    return PIVOT[bone] + at(clip["bones"].get(bone, {}).get("position"), t) * np.array([1.0, -1.0, 1.0])


def turn(clip, bone, t):
    return euler_to_mat(at(clip["bones"].get(bone, {}).get("rotation"), t))


def lowest(piv, rot, bone="right_leg"):
    """the lowest corner (largest y) of a limb's box posed"""
    lo, hi = BOX10[bone]
    return max((piv + rot @ np.array([cx, cy, cz]))[1] for cx in (lo[0], hi[0]) for cy in (lo[1], hi[1]) for cz in (lo[2], hi[2]))


def bend(hip, ankle, pole):
    """the knee of a leg from hip to ankle, thigh and shin of 6 px: on the side `pole` (a point) is; and the ankle,
    pulled in to where the leg reaches"""
    d = ankle - hip
    dist = float(np.linalg.norm(d))
    reach = THIGH + SHIN - 0.02
    if dist > reach:
        ankle = hip + d * (reach / dist)
        d, dist = ankle - hip, reach
    u = d / dist
    side = (pole - hip) - np.dot(pole - hip, u) * u
    if np.linalg.norm(side) < 1e-6:
        fwd = np.array([0.0, 0.0, -1.0])
        side = fwd - np.dot(fwd, u) * u
    a = dist / 2.0
    return hip + a * u + math.sqrt(max(THIGH * THIGH - a * a, 0.0)) * unit(side), ankle


def leg(hip, thigh0, shin0, ankle, pole, plant):
    """One leg from a hip moved: the thigh's and the shin's turns (the model's axes) that put the ankle at `ankle`,
    each the clip's own turned the least. Raised till no box is in the ground; `plant`: lowered too, till it stands
    on it, its foot drawn in under the hip as far as it must to reach."""
    for _ in range(60):
        knee, end = bend(hip, ankle, pole)
        thigh = rot_between(thigh0 @ Y, knee - hip) @ thigh0
        shin = rot_between(shin0 @ Y, end - knee) @ shin0
        over = max(lowest(hip, thigh), lowest(knee, shin)) - GROUND
        if over <= 0.01 and (not plant or over >= -0.01):
            break
        if over < 0 and np.linalg.norm(ankle - hip) >= THIGH + SHIN - 0.03:
            # it cannot reach the ground there: the foot in under the hip
            ankle = np.array([hip[0] + (ankle[0] - hip[0]) * 0.93, ankle[1], hip[2] + (ankle[2] - hip[2]) * 0.93])
        ankle = ankle - np.array([0.0, over, 0.0])
    return thigh, shin, knee, end


def fit(clip, neck, times, plant=False, body=None, feet=None, loop=False, length=None, square=False):
    """The clip's legs under a neck at `neck` (the body's pivot, px): {bones} of a Bedrock animation, the body's turn
    and the four leg parts. `body`: the trunk's turn (degrees) in place of the clip's; `feet`: {leg: (x, z)} the
    ankles' places, px: x from the leg's own hip, z from the middle of the hips, in place of the clip's; `square`:
    each leg turned from the model's rest pose, not from the clip's turn (none of the clip's twist of a thigh about
    its own length, which a box shows as a leg turned edge on), the knee ahead."""
    neck = np.array(neck, float)
    rows = {b: {"rotation": [], "position": []} for b in ("body", "right_leg", "right_shin", "left_leg", "left_shin")}
    prev = {}
    report = []
    for t in times:
        own = turn(clip, "body", t)
        trunk = own if body is None else euler_to_mat(body)
        # the hips from the neck: along the trunk as it is drawn; the legs' pivots from the hips as the clip had them
        hips0 = pivot(clip, "body", t) + own @ np.array([0.0, 12.0, 0.0])
        hips = neck + trunk @ np.array([0.0, 12.0, 0.0])
        e = mat_to_euler(trunk, prev.get("body"))
        prev["body"] = e
        rows["body"]["rotation"].append(e)
        rows["body"]["position"].append(np.zeros(3))
        for up, low in LEGS:
            hip0 = pivot(clip, up, t)
            thigh0 = turn(clip, up, t)
            shin0 = thigh0 @ turn(clip, low, t)
            knee0 = hip0 + thigh0 @ np.array([0.0, THIGH, 0.0])
            ankle0 = knee0 + shin0 @ np.array([0.0, SHIN, 0.0])
            hip = hips + (hip0 - hips0)
            if feet and up in feet:
                ankle = np.array([hip[0] + feet[up][0], ankle0[1], hips[2] + feet[up][1]])
            else:
                # where it was over the ground, moved with the hips along it
                ankle = np.array([ankle0[0] + hips[0] - hips0[0], ankle0[1], ankle0[2] + hips[2] - hips0[2]])
            pole = knee0 + hips - hips0 + np.array([0.0, 0.0, -0.01])
            if square:
                thigh0 = shin0 = np.eye(3)
                pole = hip + np.array([0.0, 0.0, -1.0])
            thigh, shin, knee, end = leg(hip, thigh0, shin0, ankle, pole, plant)
            et, es = mat_to_euler(thigh, prev.get(up)), mat_to_euler(thigh.T @ shin, prev.get(low))
            prev[up], prev[low] = et, es
            rows[up]["rotation"].append(et)
            rows[up]["position"].append((hip - PIVOT[up]) * np.array([1.0, -1.0, 1.0]))
            rows[low]["rotation"].append(es)
            rows[low]["position"].append(np.zeros(3))
            report.append({"t": t, "leg": up, "hip": hip, "knee": knee, "ankle": end,
                           "low": max(lowest(hip, thigh), lowest(knee, shin)),
                           "moved": float(np.linalg.norm(end - ankle0 - (hips - hips0) * np.array([1.0, 0.0, 1.0]))),
                           "knee_deg": math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(unit(knee - hip), unit(end - knee)))))))})
    times = list(times)
    bones = {}
    for b, ch in rows.items():
        out = {}
        for kind, tol in (("rotation", 0.5), ("position", 0.05)):
            vals = np.array(ch[kind])
            # nothing to say; but a leg's turn is always said: the player puts the part to it over what it had
            if np.max(np.abs(vals)) < 1e-4 and (kind == "position" or b == "body"):
                continue
            keep = reduce_keys(times, vals, tol)
            out[kind] = {"%.4f" % (times[k] - times[0]): {"post": [round(float(x), 3) for x in vals[k]], "lerp_mode": "linear"}
                         for k in keep}
        if out:
            bones[b] = out
    span = times[-1] - times[0] if length is None else length
    return {"loop": bool(loop) if span > 0 else "hold_on_last_frame", "animation_length": round(span, 4), "bones": bones}, report
