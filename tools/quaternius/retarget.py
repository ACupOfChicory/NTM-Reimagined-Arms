"""Retarget Quaternius UAL clips onto the six parts of a Minecraft HumanoidModel.

Run:  python3 -I tools/quaternius/retarget.py <animations dir> <check.json> TAG=<file.glb> ...
      (TAG: a key of PACKS — UAL1, UAL2, KK_RANGED, ..., SWAT)

Model space of a Minecraft ModelPart: X = the model's left, Y = down, Z = back
(forward is -Z), pixels. A part's rotation is Rz(zRot) * Ry(yRot) * Rx(xRot)
around its pivot (JOML rotationZYX), and the six parts are siblings: turning the
body does not carry the head or the arms. glTF here: X = left, Y = up,
Z = forward, metres, so a glTF vector maps to MC as (x, -y, -z).

Per frame:
- arm, leg: the part points from shoulder to wrist (hip to ankle), its twist
  (turn around its own length) comes from the hand for an arm (the gun is in
  the hand) and from the thigh for a leg (the knee; feet turn out much more);
- body: points from the neck down to the middle of the hips, twist from spine_03;
- head: the Head bone's turn from its rest pose, as is;
- position: the hips move with the pelvis (scaled by leg length), and the
  height is set so the lowest corner of the six boxes sits as far above the
  ground as the lowest UAL joint does; the head, body and arms hang from the
  neck, which is 12 px above the hips along the body.

Output: Bedrock animation JSON 1.8.0, read the way PlayerAnimationLib reads it:
rotation [x, y, z] degrees = ModelPart xRot, yRot, zRot; position = the
pivot's shift from the default pose in pixels, with Y up (so [dx, -dy, dz]).
Each clip is written whole and split into `_upper` (head, body, arms) and
`_lower` (legs plus a `root` position bone that a player adds to all parts).
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gltf_skel import Glb  # noqa: E402

C = np.diag([1.0, -1.0, -1.0])
Y = np.array([0.0, 1.0, 0.0])

# Default pivots of HumanoidModel (pixels, MC model space).
PIVOT = {
    "head": np.array([0.0, 0.0, 0.0]),
    "body": np.array([0.0, 0.0, 0.0]),
    "right_arm": np.array([-5.0, 2.0, 0.0]),
    "left_arm": np.array([5.0, 2.0, 0.0]),
    "right_leg": np.array([-1.9, 12.0, 0.0]),
    "left_leg": np.array([1.9, 12.0, 0.0]),
}
# Boxes relative to the pivot (min, max), pixels.
BOX = {
    "head": ((-4, -8, -4), (4, 0, 4)),
    "body": ((-4, 0, -2), (4, 12, 2)),
    "right_arm": ((-3, -2, -2), (1, 10, 2)),
    "left_arm": ((-1, -2, -2), (3, 10, 2)),
    "right_leg": ((-2, 0, -2), (2, 12, 2)),
    "left_leg": ((-2, 0, -2), (2, 12, 2)),
}
PIVOT_HIP = np.array([0.0, 12.0, 0.0])
PARTS = ["head", "body", "right_arm", "left_arm", "right_leg", "left_leg"]
UPPER = ["head", "body", "right_arm", "left_arm"]
LOWER = ["right_leg", "left_leg"]
LIMB = {  # part: (root joint, middle joint, end joint)
    "right_arm": ("upperarm_r", "lowerarm_r", "hand_r"),
    "left_arm": ("upperarm_l", "lowerarm_l", "hand_l"),
    "right_leg": ("thigh_r", "calf_r", "foot_r"),
    "left_leg": ("thigh_l", "calf_l", "foot_l"),
}
# Where the wrist and the ankle sit along an MC arm/leg, pixels from the pivot.
LIMB_END_PX = {"right_arm": 10.0, "left_arm": 10.0, "right_leg": 12.0, "left_leg": 12.0}

def _sided(r_names, l_names):
    out = {}
    for canon, (r, l) in zip(("upperarm", "lowerarm", "hand", "thigh", "calf", "foot"), zip(r_names, l_names)):
        out[canon + "_r"], out[canon + "_l"] = r, l
    return out


# Each rig: the canonical bones the retarget reads -> the rig's own names, and the joints the ground ignores.
RIGS = {
    "ual": {
        "bones": {**_sided(["upperarm_r", "lowerarm_r", "hand_r", "thigh_r", "calf_r", "foot_r"],
                           ["upperarm_l", "lowerarm_l", "hand_l", "thigh_l", "calf_l", "foot_l"]),
                  "neck_01": "neck_01", "spine_03": "spine_03", "pelvis": "pelvis", "Head": "Head"},
        "not_ground": ("root",),
    },
    # KayKit Rig_Medium: no neck, the head grows from the chest; `wrist` is the wrist, `hand` the palm
    "kaykit": {
        "bones": {**_sided(["upperarm.r", "lowerarm.r", "wrist.r", "upperleg.r", "lowerleg.r", "foot.r"],
                           ["upperarm.l", "lowerarm.l", "wrist.l", "upperleg.l", "lowerleg.l", "foot.l"]),
                  "neck_01": "head", "spine_03": "chest", "pelvis": "hips", "Head": "head"},
        "not_ground": ("root",),
    },
    # Quaternius' modular characters (poly.pizza SWAT): the feet are IK bones off Root, the ankle is LowerLeg's end
    "qmodular": {
        "bones": {**_sided(["UpperArm.R", "LowerArm.R", "Wrist.R", "UpperLeg.R", "LowerLeg.R", "LowerLeg.R_end"],
                           ["UpperArm.L", "LowerArm.L", "Wrist.L", "UpperLeg.L", "LowerLeg.L", "LowerLeg.L_end"]),
                  "neck_01": "Neck", "spine_03": "Chest", "pelvis": "Hips", "Head": "Head"},
        "not_ground": ("Root", "Body", "PT.L", "PT.L_end", "PT.R", "PT.R_end"),
    },
}
# A pack: its rig and the output file it goes to.
PACKS = {
    "UAL1": ("ual", "ual1"),
    "UAL2": ("ual", "ual2"),
    "KK_RANGED": ("kaykit", "kaykit"),
    "KK_GENERAL": ("kaykit", "kaykit"),
    "KK_MOVE_ADV": ("kaykit", "kaykit"),
    "KK_MOVE_BASIC": ("kaykit", "kaykit"),
    "KK_SIM": ("kaykit", "kaykit"),
    "SWAT": ("qmodular", "swat"),
}

SELECTED = {
    "KK_RANGED": [
        "Ranged_2H_Aiming", "Ranged_2H_Shoot", "Ranged_2H_Shooting", "Ranged_2H_Reload",
        "Ranged_1H_Aiming", "Ranged_1H_Shoot", "Ranged_1H_Shooting", "Ranged_1H_Reload",
    ],
    "KK_GENERAL": ["Idle_A", "Idle_B", "Death_A", "Death_B", "Hit_A", "Hit_B", "Throw", "Interact", "PickUp",
                   "Use_Item"],
    "KK_MOVE_ADV": ["Crawling", "Crouching", "Sneaking", "Running_HoldingRifle", "Running_Strafe_Left",
                    "Running_Strafe_Right", "Walking_Backwards", "Dodge_Forward", "Dodge_Backward", "Dodge_Left",
                    "Dodge_Right"],
    "KK_MOVE_BASIC": ["Walking_A", "Running_A"],
    "KK_SIM": ["Lie_Down", "Lie_Idle", "Lie_StandUp", "Waving"],
    "SWAT": ["CharacterArmature|Idle_Gun", "CharacterArmature|Idle_Gun_Pointing", "CharacterArmature|Idle_Gun_Shoot",
             "CharacterArmature|Gun_Shoot", "CharacterArmature|Run_Shoot", "CharacterArmature|Wave"],
    "UAL1": [
        "Idle_Loop", "Walk_Loop", "Jog_Fwd_Loop", "Sprint_Loop", "Crouch_Idle_Loop", "Crouch_Fwd_Loop",
        "Pistol_Idle_Loop", "Pistol_Aim_Neutral", "Pistol_Aim_Up", "Pistol_Aim_Down", "Pistol_Shoot",
        "Pistol_Reload", "Death01", "Hit_Chest", "Hit_Head", "Roll", "Jump_Start", "Jump_Loop", "Jump_Land",
        "Interact", "PickUp_Table", "Punch_Jab", "Punch_Cross", "Spell_Simple_Shoot", "Fixing_Kneeling",
        "Idle_Talking_Loop",
    ],
    "UAL2": [
        "OverhandThrow", "Hit_Knockback", "Idle_Rail_Call", "Idle_No_Loop", "Yes", "LayToIdle", "Slide_Start",
        "Slide_Loop", "Slide_Exit", "ClimbUp_1m", "Melee_Hook", "Consume", "Walk_Carry_Loop",
        "Idle_FoldArms_Loop",
    ],
}


# Clips that loop though their names do not say so. (KayKit's Ranged_*_Aiming raise the gun from the idle pose
# in 0.3 s and then hold it: played once, the last frame held.)
LOOPS = {"Idle_A", "Idle_B", "Crawling", "Crouching", "Sneaking", "Running_HoldingRifle", "Running_Strafe_Left",
         "Running_Strafe_Right", "Walking_Backwards", "Walking_A", "Running_A", "Lie_Idle", "Ranged_2H_Shooting", "Ranged_1H_Shooting", "Idle_Gun", "Idle_Gun_Pointing",
         "Run_Shoot", "Waving", "Wave"}
# Clips played once that end in a pose to keep: written with Bedrock's "loop": "hold_on_last_frame".
HOLDS = {"Ranged_2H_Aiming", "Ranged_1H_Aiming", "Pistol_Aim_Neutral", "Pistol_Aim_Up", "Pistol_Aim_Down",
         "Death_A", "Death_B", "Death01", "Lie_Down"}


def unit(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-12 else v


def rot_between(a, b):
    """Smallest rotation taking unit a onto unit b."""
    a, b = unit(a), unit(b)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if c < -0.999999:
        axis = unit(np.cross(a, [1, 0, 0]) if abs(a[0]) < 0.9 else np.cross(a, [0, 1, 0]))
        return 2 * np.outer(axis, axis) - np.eye(3)
    k = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + k + k @ k / (1 + c)


def frame_from(yaxis, zhint):
    y = unit(yaxis)
    z = unit(zhint - np.dot(zhint, y) * y)
    x = np.cross(y, z)
    return np.column_stack([x, y, z])


def rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler_to_mat(deg):
    x, y, z = np.radians(deg)
    return rz(z) @ ry(y) @ rx(x)


def mat_to_euler(m, prev=None):
    """M = Rz Ry Rx -> (x, y, z) degrees, the branch nearest `prev`."""
    sy = max(-1.0, min(1.0, -m[2, 0]))
    b = math.asin(sy)
    if abs(math.cos(b)) < 1e-6:
        a = math.atan2(-m[1, 2], m[1, 1])
        c = 0.0
        cands = [(a, b, c)]
    else:
        a = math.atan2(m[2, 1], m[2, 2])
        c = math.atan2(m[1, 0], m[0, 0])
        cands = [(a, b, c), (a + math.pi, math.pi - b, c + math.pi)]
    best = None
    for cand in cands:
        d = np.degrees(cand)
        if prev is not None:
            d = prev + (d - prev + 180.0) % 360.0 - 180.0
            cost = float(np.sum((d - prev) ** 2))
        else:
            d = (d + 180.0) % 360.0 - 180.0
            cost = float(np.sum(d ** 2))
        if best is None or cost < best[0]:
            best = (cost, d)
    return best[1]


class Skeleton:
    def __init__(self, glb, rig="ual"):
        self.g = glb
        self.bones = RIGS[rig]["bones"]
        skins = glb.j.get("skins")
        joints = sorted({i for sk in skins for i in sk["joints"]}) if skins else range(len(glb.nodes))
        skip = set(RIGS[rig]["not_ground"])
        self.ground_nodes = [i for i in joints if glb.names[i] not in skip]
        self.rest = self.pose(glb.rest_world())
        r = self.rest
        self.leg_len = np.linalg.norm(r["P"]["foot_l"] - r["P"]["thigh_l"])
        self.scale = 12.0 / self.leg_len  # px per metre for root motion
        self.ground0 = self.lowest(r)
        self.A = {}
        for part, (a, _, c) in LIMB.items():
            self.A[part] = frame_from(r["P"][c] - r["P"][a], np.array([0.0, 0.0, 1.0]))
        self.A["body"] = frame_from(self.hips(r) - r["P"]["neck_01"], np.array([0.0, 0.0, 1.0]))

    def pose(self, world):
        """World matrices -> MC-frame positions and turns of the canonical bones, and the lowest joint's height."""
        P, R = {}, {}
        for canon, name in self.bones.items():
            m = world[self.g.index[name]]
            P[canon] = C @ m[:3, 3]
            r = m[:3, :3] / np.linalg.norm(m[:3, :3], axis=0)
            R[canon] = C @ r @ C
        low = min(float(world[i][1, 3]) for i in self.ground_nodes)
        return {"P": P, "R": R, "low": low}

    @staticmethod
    def hips(p):
        return 0.5 * (p["P"]["thigh_l"] + p["P"]["thigh_r"])

    @staticmethod
    def lowest(p):
        """Height of the lowest joint above y=0."""
        return p["low"]

    def delta(self, p, bone):
        return p["R"][bone] @ self.rest["R"][bone].T

    def hip_height(self, p):
        return -self.hips(p)[1]

    def solve(self, p, legs="ankle"):
        """One frame -> {part: (pivot px, rotation 3x3)}, hips' shift.

        legs="ankle": a leg points from hip to ankle (feet in place, hips stay high
        when the knee bends); legs="thigh": along the thigh (hips drop, the foot
        ends where the knee is)."""
        rot = {}
        for part, (a, b, c) in LIMB.items():
            twist = c if part.endswith("arm") else a  # hand for arms, thigh for legs
            m0 = self.delta(p, twist) @ self.A[part]
            end = b if (legs == "thigh" and part.endswith("leg")) else c
            d = p["P"][end] - p["P"][a]
            rot[part] = rot_between(m0 @ Y, d) @ m0
        m0 = self.delta(p, "spine_03") @ self.A["body"]
        rot["body"] = rot_between(m0 @ Y, self.hips(p) - p["P"]["neck_01"]) @ m0
        rot["head"] = self.delta(p, "Head")
        rp = self.delta(p, "pelvis")

        hip = np.array([0.0, 12.0, 0.0]) + self.scale * (self.hips(p) - self.hips(self.rest))
        hip[1] = 12.0  # height comes from the ground below
        neck_off = rot["body"] @ np.array([0.0, -12.0, 0.0])
        piv = {
            "body": neck_off,
            "head": neck_off,
            "right_arm": neck_off + rot["body"] @ np.array([-5.0, 2.0, 0.0]),
            "left_arm": neck_off + rot["body"] @ np.array([5.0, 2.0, 0.0]),
            "right_leg": rp @ np.array([-1.9, 0.0, 0.0]),
            "left_leg": rp @ np.array([1.9, 0.0, 0.0]),
        }
        low = -1e9
        for part in PARTS:
            lo, hi = BOX[part]
            for cx in (lo[0], hi[0]):
                for cy in (lo[1], hi[1]):
                    for cz in (lo[2], hi[2]):
                        low = max(low, (piv[part] + rot[part] @ np.array([cx, cy, cz]))[1])
        lift = self.scale * (self.lowest(p) - self.ground0)
        hip[1] = 24.0 - lift - low
        root = hip - np.array([0.0, 12.0, 0.0])
        out = {part: (PIVOT_HIP + root + piv[part], rot[part]) for part in PARTS}
        return out, root


def reduce_keys(times, vals, tol):
    """Keep the fewest keys whose linear interpolation stays within tol of every sample."""
    n = len(times)
    if n <= 2:
        return list(range(n))
    keep = [0]
    i = 0
    while i < n - 1:
        j = i + 2
        while j < n:
            ok = True
            for k in range(i + 1, j):
                u = (times[k] - times[i]) / (times[j] - times[i])
                if np.max(np.abs(vals[i] + u * (vals[j] - vals[i]) - vals[k])) > tol:
                    ok = False
                    break
            if not ok:
                break
            j += 1
        keep.append(j - 1)
        i = j - 1
    return keep


def interp(times, vals, keys, t):
    ks = [times[k] for k in keys]
    if t <= ks[0]:
        return vals[keys[0]]
    if t >= ks[-1]:
        return vals[keys[-1]]
    j = int(np.searchsorted(ks, t, side="right"))
    a, b = keys[j - 1], keys[j]
    u = (t - times[a]) / (times[b] - times[a])
    return vals[a] + u * (vals[b] - vals[a])


def angle(a, b):
    return math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(unit(a), unit(b)))))))


def leg_errors(sk, p, out):
    """Hip height error and ankle position error (px) of one solved frame."""
    hip_mc = 24.0 - (out["right_leg"][0][1] + out["left_leg"][0][1]) / 2
    hip_err = (hip_mc - 12.0) - sk.scale * (sk.hip_height(p) - sk.hip_height(sk.rest))
    foot = 0.0
    for part in LOWER:
        a, _, c = LIMB[part]
        piv, m = out[part]
        mc = piv + m @ np.array([0.0, 12.0, 0.0])
        want = piv + sk.scale * (p["P"][c] - p["P"][a])
        foot = max(foot, float(np.linalg.norm(mc - want)))
    return abs(hip_err), foot


def pick_legs(sk, clip):
    """The leg mode with the smaller p95 of max(hip error, ankle error)."""
    best = None
    for mode in ("ankle", "thigh"):
        errs = []
        for t in clip.times:
            p = sk.pose(clip.world(t))
            out, _ = sk.solve(p, mode)
            errs.append(max(leg_errors(sk, p, out)))
        score = float(np.percentile(errs, 95))
        if best is None or score < best[0] - 0.25:  # ankle unless thigh is clearly better
            best = (score, mode)
    return best[1]


def mat_angle(a, b):
    """Angle of the rotation taking a onto b, degrees."""
    c = (np.trace(a.T @ b) - 1.0) / 2.0
    return math.degrees(math.acos(max(-1.0, min(1.0, c))))


def solve_frame(sk, clip, t, legs, prev):
    """One time -> (frame, root, pose, solved matrices); Euler angles follow `prev`."""
    p = sk.pose(clip.world(t))
    out, root = sk.solve(p, legs)
    f, mats = {}, {}
    for part in PARTS:
        piv, m = out[part]
        e = mat_to_euler(m, prev.get(part) if prev else None)
        f[part] = (piv - PIVOT[part], e, piv - root - PIVOT[part])
        mats[part] = m
    return {"t": t, "f": f, "root": root, "p": p, "m": mats, "legerr": leg_errors(sk, p, out)}


def convert(sk, clip, rot_tol=0.5, pos_tol=0.05, legs="ankle", mid_tol=2.0, depth=4):
    """mid_tol: where Euler interpolation between two 30 fps samples strays further (a fast swing), samples
    are added in between, halving the step up to `depth` times."""
    def follow(a, t):
        return solve_frame(sk, clip, t, legs, {k: v[1] for k, v in a["f"].items()})

    def refine(a, t, d):
        """Samples after `a` up to time t, each solved from its left neighbour."""
        b = follow(a, t)
        tm = (a["t"] + t) / 2
        mid = follow(a, tm)
        err = max(mat_angle(euler_to_mat((a["f"][k][1] + b["f"][k][1]) / 2), mid["m"][k]) for k in PARTS)
        if err <= mid_tol or d >= depth:
            return [b]
        left = refine(a, tm, d + 1)
        return left + refine(left[-1], t, d + 1)

    dense = [solve_frame(sk, clip, clip.times[0], legs, None)]
    for t in clip.times[1:]:
        dense += refine(dense[-1], t, 0)
    times = [d["t"] for d in dense]
    frames = [d["f"] for d in dense]
    roots = [d["root"] for d in dense]
    poses = [d["p"] for d in dense]
    legerr = [d["legerr"] for d in dense]
    solved = [d["m"] for d in dense]

    chans = {}
    for part in PARTS:
        rv = np.array([f[part][1] for f in frames])
        pv = np.array([f[part][0] * np.array([1, -1, 1]) for f in frames])
        pr = np.array([f[part][2] * np.array([1, -1, 1]) for f in frames])
        chans[part] = {
            "rotation": (rv, reduce_keys(times, rv, rot_tol)),
            "position": (pv, reduce_keys(times, pv, pos_tol)),
            "position_rel": (pr, reduce_keys(times, pr, pos_tol)),
        }
    rootv = np.array([r * np.array([1, -1, 1]) for r in roots])
    # only the split halves carry it (the whole clip has the shift in each part)
    chans["root"] = {"position_rel": (rootv, reduce_keys(times, rootv, pos_tol))}

    # --- check: rebuild each part from the written keys, compare with UAL bones.
    stats = {k: [] for k in ("rebuild", "rebuild_turn", "mid", "arm_up", "arm_fore", "elbow", "leg_up", "leg_low", "knee",
                             "hand_px", "foot_px", "body", "body_twist", "head")}
    stats["hip_px"] = [e[0] for e in legerr]
    stats["ankle_px"] = [e[1] for e in legerr]
    for fi, t in enumerate(times):
        p = poses[fi]
        m = {}
        for part in PARTS:
            rv, keys = chans[part]["rotation"]
            m[part] = euler_to_mat(interp(times, rv, keys, t))
            stats["rebuild_turn"].append(mat_angle(m[part], solved[fi][part]))
        if fi:
            # halfway to the previous sample: what a player sees between keys
            tm = (times[fi - 1] + t) / 2
            pm = solve_frame(sk, clip, tm, legs, None)
            for part in PARTS:
                rv, keys = chans[part]["rotation"]
                stats["mid"].append(mat_angle(euler_to_mat(interp(times, rv, keys, tm)), pm["m"][part]))
        for part, (a, b, c) in LIMB.items():
            d_up = p["P"][b] - p["P"][a]
            d_lo = p["P"][c] - p["P"][b]
            d_all = p["P"][c] - p["P"][a]
            axis = m[part] @ Y
            target = d_up if (legs == "thigh" and part.endswith("leg")) else d_all
            stats["rebuild"].append(angle(axis, target))
            arm = part.endswith("arm")
            stats["arm_up" if arm else "leg_up"].append(angle(axis, d_up))
            stats["arm_fore" if arm else "leg_low"].append(angle(axis, d_lo))
            stats["elbow" if arm else "knee"].append(angle(d_up, d_lo))
            full = np.linalg.norm(sk.rest["P"][c] - sk.rest["P"][a])
            short = np.linalg.norm(d_all) / full * LIMB_END_PX[part]
            stats["hand_px" if arm else "foot_px"].append(LIMB_END_PX[part] - short)
        stats["body"].append(angle(m["body"] @ Y, sk.hips(p) - p["P"]["neck_01"]))
        sh_ual = p["P"]["upperarm_l"] - p["P"]["upperarm_r"]
        sh_mc = m["body"] @ np.array([1.0, 0.0, 0.0])
        axis = unit(m["body"] @ Y)
        sh_ual = sh_ual - np.dot(sh_ual, axis) * axis
        stats["body_twist"].append(angle(sh_mc, sh_ual))
        hf = sk.delta(p, "Head") @ np.array([0.0, 0.0, -1.0])
        stats["head"].append(angle(m["head"] @ np.array([0.0, 0.0, -1.0]), hf))

    nkeys = sum(len(chans[part][k][1]) for part in PARTS for k in ("rotation", "position"))
    return chans, stats, len(times), nkeys, times


def bedrock(times, chans, parts, length, loop, split=False):
    """split: positions without the hips' shift, which the `root` bone carries."""
    bones = {}
    for part in parts:
        b = {}
        for kind, (vals, keys) in chans[part].items():
            if kind == ("position" if split else "position_rel"):
                continue
            if np.max(np.abs(vals)) < 1e-4:
                continue
            b[kind.replace("_rel", "")] = {f"{times[k]:.4f}".rstrip("0").rstrip(".") if times[k] else "0.0":
                       {"post": [round(float(x), 3) for x in vals[k]], "lerp_mode": "linear"} for k in keys}
        if b:
            bones[part] = b
    return {"loop": loop if loop == "hold_on_last_frame" else bool(loop), "animation_length": round(length, 4), "bones": bones}


def grade(err, good, fair):
    return "хорошо" if err <= good else "сносно" if err <= fair else "плохо"


def grades(st):
    """Upper body by how far the wrist falls short, legs by hips and ankles (p95, px)."""
    hand = float(np.percentile(st["hand_px"], 95))
    legs = float(np.percentile(np.maximum(st["hip_px"], st["ankle_px"]), 95))
    return grade(hand, 1.0, 2.5), grade(legs, 1.5, 3.0), hand, legs


def main():
    out, check = sys.argv[1], sys.argv[2]
    packs = [a.split("=", 1) for a in sys.argv[3:]]
    os.makedirs(out, exist_ok=True)
    rows = []
    files = {}
    for tag, path in packs:
        rig, outname = PACKS[tag]
        g = Glb(path)
        sk = Skeleton(g, rig)
        anims = files.setdefault(outname, {})
        for name in g.anims:
            clip = g.clip(name)
            if clip.length <= 0:
                continue  # a single pose
            if clip.zero_scale():
                print(f"{tag} {name}: skipped, a bone is scaled to nothing")
                continue
            legs = pick_legs(sk, clip)
            chans, st, nframes, nkeys, times = convert(sk, clip, legs=legs)
            gu, gl, eu, el = grades(st)
            short = name.split("|")[-1]
            loop = short.endswith("_Loop") or short in LOOPS
            seam = seam_px = None
            if loop:  # a loop's last frame must be its first, turns and the hips' shift
                a = sk.solve(sk.pose(clip.world(0.0)), legs)[0]
                b = sk.solve(sk.pose(clip.world(clip.length)), legs)[0]
                seam = max(mat_angle(a[k][1], b[k][1]) for k in PARTS)
                rv = chans["root"]["position_rel"][0]
                seam_px = float(np.linalg.norm(rv[-1] - rv[0]))
            elif short in HOLDS:
                loop = "hold_on_last_frame"
            q = {k: (float(np.mean(v)), float(np.percentile(v, 95)), float(np.max(v))) for k, v in st.items()}
            rows.append({"pack": tag, "clip": name, "length": clip.length, "frames": nframes, "keys": nkeys,
                         "selected": name in SELECTED[tag], "legs": legs, "upper": gu, "lower": gl,
                         "hand_p95_px": eu, "legs_p95_px": el, "loop_seam_deg": seam, "loop_seam_px": seam_px,
                         "stats": q})
            if name in SELECTED[tag]:
                key = short.lower()
                assert key not in anims, key
                anims[key] = bedrock(times, chans, PARTS, clip.length, loop)
                anims[key + "_upper"] = bedrock(times, chans, UPPER, clip.length, loop, split=True)
                anims[key + "_lower"] = bedrock(times, chans, LOWER + ["root"], clip.length, loop, split=True)
    for outname, anims in files.items():
        doc = {"format_version": "1.8.0", "animations": anims}
        with open(os.path.join(out, f"{outname}.animation.json"), "w") as f:
            json.dump(doc, f, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
            f.write("\n")
    with open(check, "w") as f:
        json.dump(rows, f, indent=1, ensure_ascii=False, allow_nan=False)
        f.write("\n")
    for r in rows:
        s = r["stats"]
        print(f"{r['pack']} {r['clip']:24s} {'*' if r['selected'] else ' '} верх {r['upper']:7s} {r['hand_p95_px']:4.1f}px "
              f"низ {r['lower']:7s} {r['legs_p95_px']:4.1f}px ({r['legs']:5s}) "
              f"keys {r['keys']:5d}/{r['frames'] * 12:5d} rebuild max {s['rebuild'][2]:5.2f}° turn {s['rebuild_turn'][2]:5.2f}° mid {s['mid'][2]:5.2f}° "
              f"elbow p95 {s['elbow'][1]:5.1f}° "
              f"knee p95 {s['knee'][1]:5.1f}° "
              f"body {s['body'][2]:4.1f}° twist {s['body_twist'][1]:4.1f}° head {s['head'][2]:4.1f}°")


if __name__ == "__main__":
    main()
