"""Retarget free humanoid clips (Quaternius UAL and SWAT, KayKit) onto the ten parts of the AI mobs' model
(convert10, below; the six parts of a plain HumanoidModel, described here, are kept for the picker page).

Run:  python3 -I tools/quaternius/retarget.py [--choices anim-choices.json] <animations dir> <check.json> TAG=<file.glb> ...
      (TAG: a key of PACKS — UAL1, UAL2, KK_RANGED, ..., SWAT; --choices: the owner's pick per clip from the
      picker page, anim_picker.py: how each clip's arms and legs are carried over, or that it is left out)

Model space of a Minecraft ModelPart: X = the model's left, Y = down, Z = back
(forward is -Z), pixels. A part's rotation is Rz(zRot) * Ry(yRot) * Rx(xRot)
around its pivot (JOML rotationZYX), and the six parts are siblings: turning the
body does not carry the head or the arms. glTF here: X = left, Y = up,
Z = forward, metres, so a glTF vector maps to MC as (x, -y, -z).

Per frame:
- arm, leg: the part points from shoulder to wrist (hip to ankle) — or, by
  MODES, along the upper bone (shoulder to elbow, hip to knee), the lower one
  (elbow to wrist, knee to ankle), or halfway between the upper bone and the
  whole limb; its twist (turn around its own length) comes from the hand for an
  arm (the gun is in the hand) and from the thigh for a leg (the knee; feet turn
  out much more);
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
# How a limb's one MC part follows its two bones: along the whole limb (joint to end: shoulder to wrist, hip to
# ankle), the upper bone, the lower one, or halfway between the upper bone and the whole limb.
MODES = ("upper", "lower", "end", "mix")
# the leg modes' old names (pick_legs, check.json)
LEG_ALIAS = {"ankle": "end", "thigh": "upper"}
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
             "CharacterArmature|Gun_Shoot", "CharacterArmature|Run_Shoot", "CharacterArmature|Wave",
             "CharacterArmature|HitRecieve", "CharacterArmature|HitRecieve_2"],
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

    @staticmethod
    def limb_dir(p, part, mode):
        """The way a limb's MC part points in one frame (MODES; the leg's old names too)."""
        mode = LEG_ALIAS.get(mode, mode)
        a, b, c = LIMB[part]
        if mode == "upper":
            return p["P"][b] - p["P"][a]
        if mode == "lower":
            return p["P"][c] - p["P"][b]
        if mode == "mix":
            return unit(p["P"][b] - p["P"][a]) + unit(p["P"][c] - p["P"][a])
        assert mode == "end", mode
        return p["P"][c] - p["P"][a]

    def limb_rot(self, p, part, mode):
        a, _, c = LIMB[part]
        twist = c if part.endswith("arm") else a  # hand for arms, thigh for legs
        m0 = self.delta(p, twist) @ self.A[part]
        return rot_between(m0 @ Y, self.limb_dir(p, part, mode)) @ m0

    def solve(self, p, legs="ankle", arms="end"):
        """One frame -> {part: (pivot px, rotation 3x3)}, hips' shift.

        legs="ankle" ("end"): a leg points from hip to ankle (feet in place, hips stay high
        when the knee bends); legs="thigh" ("upper"): along the thigh (hips drop, the foot
        ends where the knee is); "lower": along the shin; "mix": halfway. arms: the same for an arm."""
        rot, piv, hip = self.parts(p, legs, arms)
        hip[1] = self.hip_y(p, self.lowest_corner(rot, piv, PARTS))
        root = hip - np.array([0.0, 12.0, 0.0])
        out = {part: (PIVOT_HIP + root + piv[part], rot[part]) for part in PARTS}
        return out, root

    def parts(self, p, legs="ankle", arms="end"):
        """The parts' turns and their pivots from the hips, and the hips' place (its height still to be set)."""
        rot = {part: self.limb_rot(p, part, arms if part.endswith("arm") else legs) for part in LIMB}
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
        return rot, piv, hip

    @staticmethod
    def lowest_corner(rot, piv, parts):
        """How far below the hips the lowest box corner of these parts is, pixels (MC y is down)."""
        low = -1e9
        for part in parts:
            lo, hi = BOX[part]
            for cx in (lo[0], hi[0]):
                for cy in (lo[1], hi[1]):
                    for cz in (lo[2], hi[2]):
                        low = max(low, (piv[part] + rot[part] @ np.array([cx, cy, cz]))[1])
        return low

    def hip_y(self, p, low):
        """The hips' height (MC y) that puts a lowest corner `low` below them as far over the ground as the rig's
        lowest joint rises over its rest height."""
        return 24.0 - self.scale * (self.lowest(p) - self.ground0) - low


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


# the page's limits of a correction: degrees, pixels
ADJUST_LIMIT = {"rot": 180, "pos": 12}


def adjusted(out, adjust):
    """The owner's correction of a clip (anim-choices.json `adjust`), the same in every frame: each part named is
    turned by Rz Ry Rx of `rot` (degrees, as ModelPart's xRot, yRot, zRot) around the model's axes at its pivot, after
    the pose, and its pivot moved by `pos` (pixels, as Bedrock's position: X the model's left, Y up, Z back). The
    picker page does the same to its "chosen" window."""
    if not adjust:
        return out
    out = dict(out)
    for part, a in adjust.items():
        piv, m = out[part]
        d = np.array(a.get("pos", (0, 0, 0)), float) * np.array([1.0, -1.0, 1.0])
        out[part] = (piv + d, euler_to_mat(a.get("rot", (0, 0, 0))) @ m)
    return out


# ------------------------------------------------------------------ ten parts: each arm and leg of two boxes
# The bots' model (BotModel in the mod): the upper arm and the thigh are vanilla's arm and leg cut at half length, the
# forearm and the shin hang from them as children, hinged at the elbow and the knee. Each box follows its own bone.
CHILD = {"right_forearm": "right_arm", "left_forearm": "left_arm", "right_shin": "right_leg", "left_shin": "left_leg"}
PARTS10 = ["head", "body", "right_arm", "right_forearm", "left_arm", "left_forearm",
           "right_leg", "right_shin", "left_leg", "left_shin"]
UPPER10 = ["head", "body", "right_arm", "right_forearm", "left_arm", "left_forearm"]
LOWER10 = ["right_leg", "right_shin", "left_leg", "left_shin"]
# a child's pivot in its parent, px: the elbow on the arm's middle line 6 px below the arm's top, the knee 6 px down
JOINT = {"right_forearm": np.array([-1.0, 4.0, 0.0]), "left_forearm": np.array([1.0, 4.0, 0.0]),
         "right_shin": np.array([0.0, 6.0, 0.0]), "left_shin": np.array([0.0, 6.0, 0.0])}
BOX10 = {
    "head": BOX["head"], "body": BOX["body"],
    "right_arm": ((-3, -2, -2), (1, 4, 2)), "left_arm": ((-1, -2, -2), (3, 4, 2)),
    "right_forearm": ((-2, 0, -2), (2, 6, 2)), "left_forearm": ((-2, 0, -2), (2, 6, 2)),
    "right_leg": ((-2, 0, -2), (2, 6, 2)), "left_leg": ((-2, 0, -2), (2, 6, 2)),
    "right_shin": ((-2, 0, -2), (2, 6, 2)), "left_shin": ((-2, 0, -2), (2, 6, 2)),
}
# each limb box's bone: from, to, and the bone its twist (turn about its own length) comes from: the hand for the
# forearm (it holds the gun), each other bone its own
BONE10 = {
    "right_arm": ("upperarm_r", "lowerarm_r", "upperarm_r"), "right_forearm": ("lowerarm_r", "hand_r", "hand_r"),
    "left_arm": ("upperarm_l", "lowerarm_l", "upperarm_l"), "left_forearm": ("lowerarm_l", "hand_l", "hand_l"),
    "right_leg": ("thigh_r", "calf_r", "thigh_r"), "right_shin": ("calf_r", "foot_r", "calf_r"),
    "left_leg": ("thigh_l", "calf_l", "thigh_l"), "left_shin": ("calf_l", "foot_l", "calf_l"),
}
# the wrist and the ankle: the end of the forearm and of the shin, px from its pivot
END10_PX = 6.0
# how far the wrist (ankle) is from the top of the limb's middle line with the limb straight, px (the arm's pivot is
# 1 px in from its middle line)
REACH_PX = {part: float(JOINT[child][1] + END10_PX) for child, part in CHILD.items()}


def frames10(sk):
    """Each limb box's rest frame: its Y along its bone at rest."""
    r = sk.rest
    return {part: frame_from(r["P"][b] - r["P"][a], np.array([0.0, 0.0, 1.0])) for part, (a, b, _) in BONE10.items()}


def parts10(sk, p):
    """One frame on ten parts: the world turns, the pivots from the hips, the hips' place (height still to be set)."""
    a10 = sk.__dict__.setdefault("A10", frames10(sk))
    rot, piv, hip = sk.parts(p, "end", "end")
    for part, (a, b, tw) in BONE10.items():
        m0 = sk.delta(p, tw) @ a10[part]
        rot[part] = rot_between(m0 @ Y, p["P"][b] - p["P"][a]) @ m0
    for child, parent in CHILD.items():
        piv[child] = piv[parent] + rot[parent] @ JOINT[child]
    return rot, piv, hip


def lowest_corner10(rot, piv, parts=PARTS10):
    low = -1e9
    for part in parts:
        lo, hi = BOX10[part]
        for cx in (lo[0], hi[0]):
            for cy in (lo[1], hi[1]):
                for cz in (lo[2], hi[2]):
                    low = max(low, (piv[part] + rot[part] @ np.array([cx, cy, cz]))[1])
    return low


def hip_y10(sk, p, low):
    """The hips' height (MC y): the rig's pelvis height, scaled; lifted only so no box goes into the ground (`low`:
    how far the lowest box corner is below the hips). The limbs are the rig's bones now, so the feet land where its
    feet do; a box's corner is not a joint, so the rule of the six parts (the lowest corner as high as the lowest
    joint) put the hips off by up to 8 px lying, rolling or running."""
    return min(12.0 - sk.scale * (sk.hip_height(p) - sk.hip_height(sk.rest)), 24.0 - low)


def solve10(sk, p):
    """One frame -> {part: (pivot px, world turn)}, the hips' shift (hip_y10)."""
    rot, piv, hip = parts10(sk, p)
    hip[1] = hip_y10(sk, p, lowest_corner10(rot, piv))
    root = hip - np.array([0.0, 12.0, 0.0])
    return {part: (PIVOT_HIP + root + piv[part], rot[part]) for part in PARTS10}, root


def adjusted10(out, adjust):
    """`adjusted` on ten parts: a correction names one of the six (an arm is the upper arm, a leg the thigh); its
    child goes along with it."""
    if not adjust:
        return out
    out = dict(out)
    for part, a in adjust.items():
        piv, m = out[part]
        d = np.array(a.get("pos", (0, 0, 0)), float) * np.array([1.0, -1.0, 1.0])
        e = euler_to_mat(a.get("rot", (0, 0, 0)))
        out[part] = (piv + d, e @ m)
        for child, parent in CHILD.items():
            if parent == part:
                cp, cm = out[child]
                out[child] = (piv + d + e @ (cp - piv), e @ cm)
    return out


def local10(world):
    """World turns -> each part's own: a child's from its parent."""
    return {part: world[CHILD[part]].T @ m if part in CHILD else m for part, m in world.items()}


def world10(local):
    return {part: local[CHILD[part]] @ m if part in CHILD else m for part, m in local.items()}


def limb_errors10(sk, p, out):
    """How far the MC wrists and ankles are from the rig's, from the top of the limb's middle line with the rig's
    limb scaled to the MC one straight; and the hips' height error, px."""
    hip_mc = 24.0 - (out["right_leg"][0][1] + out["left_leg"][0][1]) / 2
    hip_err = abs((hip_mc - 12.0) - sk.scale * (sk.hip_height(p) - sk.hip_height(sk.rest)))
    miss = {}
    for part, (a, _, c) in LIMB.items():
        child = next(k for k, v in CHILD.items() if v == part)
        end = out[child][0] + out[child][1] @ np.array([0.0, END10_PX, 0.0])
        full = np.linalg.norm(sk.rest["P"][c] - sk.rest["P"][a])
        top = out[part][0] + out[part][1] @ (JOINT[child] * np.array([1.0, 0.0, 1.0]))
        want = top + (p["P"][c] - p["P"][a]) / full * REACH_PX[part]
        miss[part] = float(np.linalg.norm(end - want))
    return hip_err, miss


def solve_frame10(sk, clip, t, prev, adjust=None):
    p = sk.pose(clip.world(t))
    out, root = solve10(sk, p)
    out = adjusted10(out, adjust)
    world = {part: out[part][1] for part in PARTS10}
    local = local10(world)
    f = {}
    for part in PARTS10:
        e = mat_to_euler(local[part], prev.get(part) if prev else None)
        if part in CHILD:  # hinged where it is: no move of its own
            f[part] = (np.zeros(3), e, np.zeros(3))
        else:
            piv = out[part][0]
            f[part] = (piv - PIVOT[part], e, piv - root - PIVOT[part])
    return {"t": t, "f": f, "root": root, "p": p, "m": local, "w": world, "out": out, "err": limb_errors10(sk, p, out)}


def convert10(sk, clip, rot_tol=0.5, pos_tol=0.05, mid_tol=2.0, depth=4, adjust=None):
    """`convert` on ten parts, each box along its own bone."""
    def follow(a, t):
        return solve_frame10(sk, clip, t, {k: v[1] for k, v in a["f"].items()}, adjust)

    def refine(a, t, d):
        b = follow(a, t)
        tm = (a["t"] + t) / 2
        mid = follow(a, tm)
        err = max(mat_angle(euler_to_mat((a["f"][k][1] + b["f"][k][1]) / 2), mid["m"][k]) for k in PARTS10)
        if err <= mid_tol or d >= depth:
            return [b]
        left = refine(a, tm, d + 1)
        return left + refine(left[-1], t, d + 1)

    dense = [solve_frame10(sk, clip, clip.times[0], None, adjust)]
    for t in clip.times[1:]:
        dense += refine(dense[-1], t, 0)
    times = [d["t"] for d in dense]

    chans = {}
    for part in PARTS10:
        rv = np.array([d["f"][part][1] for d in dense])
        pv = np.array([d["f"][part][0] * np.array([1, -1, 1]) for d in dense])
        pr = np.array([d["f"][part][2] * np.array([1, -1, 1]) for d in dense])
        chans[part] = {
            "rotation": (rv, reduce_keys(times, rv, rot_tol)),
            "position": (pv, reduce_keys(times, pv, pos_tol)),
            "position_rel": (pr, reduce_keys(times, pr, pos_tol)),
        }
    rootv = np.array([d["root"] * np.array([1, -1, 1]) for d in dense])
    chans["root"] = {"position_rel": (rootv, reduce_keys(times, rootv, pos_tol))}

    # --- check: rebuild each part from the written keys, each limb box against its own bone
    stats = {k: [] for k in ("rebuild", "rebuild_turn", "mid", "arm_up", "arm_fore", "elbow", "leg_up", "leg_low",
                             "knee", "hand_px", "foot_px", "hip_px", "ankle_px", "body", "body_twist", "head")}
    for fi, t in enumerate(times):
        d = dense[fi]
        p = d["p"]
        local = {part: euler_to_mat(interp(times, chans[part]["rotation"][0], chans[part]["rotation"][1], t))
                 for part in PARTS10}
        m = world10(local)
        for part in PARTS10:
            stats["rebuild_turn"].append(mat_angle(local[part], d["m"][part]))
        if fi:
            tm = (times[fi - 1] + t) / 2
            pm = solve_frame10(sk, clip, tm, None, adjust)
            for part in PARTS10:
                rv, keys = chans[part]["rotation"]
                stats["mid"].append(mat_angle(euler_to_mat(interp(times, rv, keys, tm)), pm["m"][part]))
        for part, (a, b, _) in BONE10.items():
            dev = angle(m[part] @ Y, p["P"][b] - p["P"][a])
            stats["rebuild"].append(dev)
            arm = "arm" in part
            stats[("arm_fore" if part in CHILD else "arm_up") if arm else ("leg_low" if part in CHILD else "leg_up")].append(dev)
        for part, (a, b, c) in LIMB.items():
            stats["elbow" if part.endswith("arm") else "knee"].append(angle(p["P"][b] - p["P"][a], p["P"][c] - p["P"][b]))
        hip_err, miss = d["err"]
        stats["hip_px"].append(hip_err)
        stats["hand_px"].append(max(miss["right_arm"], miss["left_arm"]))
        stats["foot_px"].append(max(miss["right_leg"], miss["left_leg"]))
        stats["ankle_px"].append(max(miss["right_leg"], miss["left_leg"]))
        stats["body"].append(angle(m["body"] @ Y, sk.hips(p) - p["P"]["neck_01"]))
        sh_ual = p["P"]["upperarm_l"] - p["P"]["upperarm_r"]
        sh_mc = m["body"] @ np.array([1.0, 0.0, 0.0])
        axis = unit(m["body"] @ Y)
        sh_ual = sh_ual - np.dot(sh_ual, axis) * axis
        stats["body_twist"].append(angle(sh_mc, sh_ual))
        hf = sk.delta(p, "Head") @ np.array([0.0, 0.0, -1.0])
        stats["head"].append(angle(m["head"] @ np.array([0.0, 0.0, -1.0]), hf))

    nkeys = sum(len(chans[part][k][1]) for part in PARTS10 for k in ("rotation", "position"))
    return chans, stats, len(times), nkeys, times


def solve_frame(sk, clip, t, legs, prev, arms="end", adjust=None):
    """One time -> (frame, root, pose, solved matrices); Euler angles follow `prev`."""
    p = sk.pose(clip.world(t))
    out, root = sk.solve(p, legs, arms)
    out = adjusted(out, adjust)
    f, mats = {}, {}
    for part in PARTS:
        piv, m = out[part]
        e = mat_to_euler(m, prev.get(part) if prev else None)
        f[part] = (piv - PIVOT[part], e, piv - root - PIVOT[part])
        mats[part] = m
    return {"t": t, "f": f, "root": root, "p": p, "m": mats, "legerr": leg_errors(sk, p, out)}


def convert(sk, clip, rot_tol=0.5, pos_tol=0.05, legs="ankle", mid_tol=2.0, depth=4, arms="end", adjust=None):
    """mid_tol: where Euler interpolation between two 30 fps samples strays further (a fast swing), samples
    are added in between, halving the step up to `depth` times."""
    def follow(a, t):
        return solve_frame(sk, clip, t, legs, {k: v[1] for k, v in a["f"].items()}, arms, adjust)

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

    dense = [solve_frame(sk, clip, clip.times[0], legs, None, arms, adjust)]
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
            pm = solve_frame(sk, clip, tm, legs, None, arms, adjust)
            for part in PARTS:
                rv, keys = chans[part]["rotation"]
                stats["mid"].append(mat_angle(euler_to_mat(interp(times, rv, keys, tm)), pm["m"][part]))
        for part, (a, b, c) in LIMB.items():
            d_up = p["P"][b] - p["P"][a]
            d_lo = p["P"][c] - p["P"][b]
            d_all = p["P"][c] - p["P"][a]
            axis = m[part] @ Y
            target = sk.limb_dir(p, part, arms if part.endswith("arm") else legs)
            stats["rebuild"].append(angle(axis, target))
            arm = part.endswith("arm")
            stats["arm_up" if arm else "leg_up"].append(angle(axis, d_up))
            stats["arm_fore" if arm else "leg_low"].append(angle(axis, d_lo))
            stats["elbow" if arm else "knee"].append(angle(d_up, d_lo))
            full = np.linalg.norm(sk.rest["P"][c] - sk.rest["P"][a])
            mode = LEG_ALIAS.get(arms if arm else legs, arms if arm else legs)
            if mode == "end":  # along the limb: the end falls short as far as the limb is shorter
                short = np.linalg.norm(d_all) / full * LIMB_END_PX[part]
                miss = LIMB_END_PX[part] - short
            else:  # how far the part's end is from where the wrist (ankle) is
                miss = float(np.linalg.norm(unit(axis) * LIMB_END_PX[part] - d_all / full * LIMB_END_PX[part]))
            stats["hand_px" if arm else "foot_px"].append(miss)
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


def read_choices(path):
    """anim-choices.json (the picker page): {"clips": {"PACK/clip": {"arms": mode, "legs": mode, "take": bool,
    "adjust": {part: {"rot": [x, y, z] degrees, "pos": [x, y, z] px}}}}} (adjust: see `adjusted`)"""
    with open(path) as f:
        doc = json.load(f)
    out = {}
    for key, c in doc["clips"].items():
        for limb in ("arms", "legs"):
            if c.get(limb) not in MODES:
                raise ValueError(f"{key}: {limb} {c.get(limb)!r} is none of {MODES}")
        if not isinstance(c.get("take", True), bool):
            raise ValueError(f"{key}: take {c.get('take')!r} is not true or false")
        adjust = c.get("adjust") or {}
        if not isinstance(adjust, dict):
            raise ValueError(f"{key}: adjust {adjust!r} is not parts by name")
        for part, a in adjust.items():
            if part not in PARTS:
                raise ValueError(f"{key}: adjust {part!r} is none of {PARTS}")
            if not isinstance(a, dict) or set(a) - set(ADJUST_LIMIT):
                raise ValueError(f"{key}: adjust {part} {a!r} has other than rot and pos")
            for k, limit in ADJUST_LIMIT.items():
                v = a.get(k, [0, 0, 0])
                if not (isinstance(v, list) and len(v) == 3 and all(
                        isinstance(x, (int, float)) and not isinstance(x, bool) and abs(x) <= limit for x in v)):
                    raise ValueError(f"{key}: adjust {part} {k} {v!r} is not three numbers within ±{limit}")
        out[key] = c
    return out


def main():
    args = sys.argv[1:]
    choices = {}
    if "--choices" in args:
        i = args.index("--choices")
        choices = read_choices(args[i + 1])
        args = args[:i] + args[i + 2:]
    seen = set()
    out, check = args[0], args[1]
    packs = [a.split("=", 1) for a in args[2:]]
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
            adjust = None
            chose = choices.get(f"{tag}/{name}")
            seen.add(f"{tag}/{name}")
            take = name in SELECTED[tag]
            if chose is not None:
                take = take and chose.get("take", True)
                adjust = chose.get("adjust") or None
            chans, st, nframes, nkeys, times = convert10(sk, clip, adjust=adjust)
            gu, gl, eu, el = grades(st)
            short = name.split("|")[-1]
            loop = short.endswith("_Loop") or short in LOOPS
            seam = seam_px = None
            if loop:  # a loop's last frame must be its first, turns and the hips' shift
                a = solve10(sk, sk.pose(clip.world(0.0)))[0]
                b = solve10(sk, sk.pose(clip.world(clip.length)))[0]
                seam = max(mat_angle(a[k][1], b[k][1]) for k in PARTS10)
                rv = chans["root"]["position_rel"][0]
                seam_px = float(np.linalg.norm(rv[-1] - rv[0]))
            elif short in HOLDS:
                loop = "hold_on_last_frame"
            q = {k: (float(np.mean(v)), float(np.percentile(v, 95)), float(np.max(v))) for k, v in st.items()}
            row = {"pack": tag, "clip": name, "length": clip.length, "frames": nframes, "keys": nkeys,
                   "selected": take, "upper": gu, "lower": gl,
                   "hand_p95_px": eu, "legs_p95_px": el, "loop_seam_deg": seam, "loop_seam_px": seam_px,
                   "stats": q}
            if adjust:
                row["adjust"] = adjust
            rows.append(row)
            if take:
                key = short.lower()
                assert key not in anims, key
                anims[key] = bedrock(times, chans, PARTS10, clip.length, loop)
                anims[key + "_upper"] = bedrock(times, chans, UPPER10, clip.length, loop, split=True)
                anims[key + "_lower"] = bedrock(times, chans, LOWER10 + ["root"], clip.length, loop, split=True)
    if any(c.get("arms") != "end" or c.get("legs") != "end" for c in choices.values()):
        print("--choices: the arms' and legs' modes are left aside, each box follows its own bone")
    for key in sorted(set(choices) - seen):
        print(f"--choices: no clip {key} in the packs given, left as it is")
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
              f"низ {r['lower']:7s} {r['legs_p95_px']:4.1f}px "
              f"keys {r['keys']:5d}/{r['frames'] * 12:5d} rebuild max {s['rebuild'][2]:5.2f}° turn {s['rebuild_turn'][2]:5.2f}° mid {s['mid'][2]:5.2f}° "
              f"bones p95 {max(s[k][1] for k in ('arm_up', 'arm_fore', 'leg_up', 'leg_low')):5.2f}° "
              f"body {s['body'][2]:4.1f}° twist {s['body_twist'][1]:4.1f}° head {s['head'][2]:4.1f}°")


if __name__ == "__main__":
    main()
