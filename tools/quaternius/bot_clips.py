#!/usr/bin/env python3
"""Copy the clips AI mobs play out of animations/ into the mod's resources.

python3 -I tools/quaternius/bot_clips.py

Only clips check.json grades good or fair where they are used (see BOT below); everything else a mob does is
posed in code (BotPoser). Writes src/main/resources/assets/ntmgunfeel/animations/bot.animation.json.

Crouching, the legs are fitted under the head (FIT, fit.py): the head stays at the eye the server keeps.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fit  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "src", "main", "resources", "assets", "ntmgunfeel", "animations",
                   "bot.animation.json")
# name in the mod: (file, clip) -- the half used and why
BOT = {
    "idle": ("ual1", "idle_loop"),               # UAL Idle_Loop: upper good, lower good
    "hit_chest": ("ual1", "hit_chest"),          # UAL Hit_Chest: good, good
    "hit_head": ("ual1", "hit_head"),            # UAL Hit_Head: good, good
    "throw": ("ual2", "overhandthrow_upper"),    # UAL OverhandThrow: upper fair (legs poor: not used)
    "wave": ("kaykit", "waving_upper"),          # KayKit Waving: upper fair, the right arm waves
    # on the move: the legs only (the arms keep the gun); ten parts' grades
    "walk": ("ual1", "walk_loop_lower"),         # UAL Walk_Loop: lower good
    "jog": ("ual1", "jog_fwd_loop_lower"),       # UAL Jog_Fwd_Loop: lower good
    "sprint": ("ual1", "sprint_loop_lower"),     # UAL Sprint_Loop: lower good
    "back": ("kaykit", "walking_backwards_lower"),          # KayKit Walking_Backwards: lower fair
    "strafe_left": ("kaykit", "running_strafe_left_lower"),  # KayKit Running_Strafe_Left: lower fair
    "strafe_right": ("kaykit", "running_strafe_right_lower"),  # KayKit Running_Strafe_Right: lower fair
    "reload": ("kaykit", "ranged_2h_reload_upper"),  # KayKit Ranged_2H_Reload: upper fair (a rifle's reload)
    "death": ("kaykit", "death_a"),              # KayKit Death_A: upper good, lower poor (it falls: the legs fold)
    # KayKit Hit_B: thrown back and up on its feet again, first and last frame the rest pose (added as a flinch).
    # UAL Hit_Knockback is a fall onto the back (the body to -88 degrees and lying there): no flinch over standing legs
    "hit_knock": ("kaykit", "hit_b"),
    # a forward roll under fire (AiPose.ROLL), the whole body: UAL Roll, upper and lower poor by the numbers (knees bent
    # past 120 degrees), played squeezed into the action's 16 ticks; in place (no move in its root: the game moves it)
    "roll": ("ual1", "roll"),
    # in the air (a jump up a block, a fall): the legs, one knee drawn up
    "jump": ("ual1", "jump_loop_lower"),         # UAL Jump_Loop: lower good
    # two more flinches: doubled up forward, thrown back (Quaternius SWAT HitRecieve, HitRecieve_2: good, good)
    "hit_fold": ("swat", "hitrecieve"),
    "hit_back": ("swat", "hitrecieve_2"),
    # a door pushed open with the free left hand (AiPose.DOOR): UAL Interact, its left arm (upper good)
    "door": ("ual1", "interact_upper"),
}
# Crouching (AiPose.CROUCH): the legs fitted under a neck where vanilla's crouch has it (the body's pivot 3.2 px down:
# the head at the eye the server keeps crouching), each foot back on the ground (fit.py). name: (file, clip, from,
# to seconds, what fit takes)
CROUCH_NECK = (0.0, 3.2, 0.0)
FIT = {
    # on the move: KayKit Crouching, a walk bent low (its own neck within 1.4 px of the place: its feet moved 0.4 px
    # at most); the trunk's lean is the clip's
    "crouch_walk": ("kaykit", "crouching", 0.0, None, {"loop": True}),
    # at a stand: down on the right knee, the left foot ahead, as UAL Fixing_Kneeling kneels (at 2.6 s: which knee,
    # how far apart the feet). Its trunk is bent over its work, ours upright a little forward, the gun's; its left
    # thigh is turned out 43 degrees about its own length, ours square (a box turned so shows its edge), the left
    # foot under its knee
    "kneel": ("ual1", "fixing_kneeling", 2.6, 2.6, {"plant": True, "body": (10.0, 0.0, 0.0), "square": True,
                                                     "feet": {"right_leg": (0.0, 6.0), "left_leg": (0.0, -5.0)}}),
}
# a slide into cover (AiPose.SLIDE), the whole body, 16 ticks: UAL Slide_Start down into the slide (to 0.533 s), then
# Slide_Exit while the hips stay low (to 0.267 s); the pose layer fades it into the crouch after. Poor by the numbers too
SPLICE = {"slide": [("ual2", "slide_start", 0.5333), ("ual2", "slide_exit", 0.2667)]}
# turns only: the game moves the mob itself as it is thrown, and the head stays at the eye the server keeps
TURNS_ONLY = {"hit_knock"}


def _keys(ch):
    """a channel's keys as (time, value) sorted; a key with pre/post is its post (the clips' keys are linear)"""
    return sorted(((float(t), v["post"] if isinstance(v, dict) else v) for t, v in ch.items()), key=lambda k: k[0])


def _at(keys, t):
    if t <= keys[0][0]:
        return keys[0][1]
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t <= t1:
            u = (t - t0) / (t1 - t0) if t1 > t0 else 1.0
            return [a + (b - a) * u for a, b in zip(v0, v1)]
    return keys[-1][1]


def splice(parts):
    """clips one after the other, each up to its time (the value there a key of its own); played once"""
    bones = {}
    off = 0.0
    for clip, upto in parts:
        for b, chans in clip["bones"].items():
            for name, ch in chans.items():
                keys = _keys(ch)
                kept = [(t, v) for t, v in keys if t < upto - 1e-6] + [(upto, _at(keys, upto))]
                dst = bones.setdefault(b, {}).setdefault(name, {})
                for t, v in kept:
                    dst["%.4f" % (off + t)] = {"post": [round(x, 3) for x in v], "lerp_mode": "linear"}
        off += upto
    return {"loop": False, "animation_length": round(off, 4), "bones": bones}


def build(say=print):
    """the mod's clips, by name"""
    cache = {}
    out = {}
    for name, (f, clip) in BOT.items():
        if f not in cache:
            with open(os.path.join(HERE, "animations", f + ".animation.json")) as fh:
                cache[f] = json.load(fh)["animations"]
        out[name] = cache[f][clip]
        if name in TURNS_ONLY:
            out[name] = dict(out[name], bones={b: {k: v for k, v in ch.items() if k != "position"}
                                               for b, ch in out[name]["bones"].items()})
    for name, parts in SPLICE.items():
        for f, _, _ in parts:
            if f not in cache:
                with open(os.path.join(HERE, "animations", f + ".animation.json")) as fh:
                    cache[f] = json.load(fh)["animations"]
        out[name] = splice([(cache[f][clip], upto) for f, clip, upto in parts])
    for name, (f, clip, t0, t1, how) in FIT.items():
        if f not in cache:
            with open(os.path.join(HERE, "animations", f + ".animation.json")) as fh:
                cache[f] = json.load(fh)["animations"]
        src = cache[f][clip]
        t1 = src["animation_length"] if t1 is None else t1
        n = max(1, round((t1 - t0) * 30))
        times = [t0 + (t1 - t0) * i / n for i in range(n + 1)] if t1 > t0 else [t0]
        out[name], report = fit.fit(src, CROUCH_NECK, times, length=t1 - t0, **how)
        say("%s: knees to %.0f deg, a foot moved %.2f px at most, the lowest box %.2f px over the ground at least" % (
            name, max(r["knee_deg"] for r in report), max(r["moved"] for r in report),
            min(fit.GROUND - r["low"] for r in report)))
    return out


def text(out):
    return json.dumps({"format_version": "1.8.0", "animations": out}, separators=(",", ":"), allow_nan=False) + "\n"


def main():
    with open(OUT, "w") as fh:
        fh.write(text(build()))


if __name__ == "__main__":
    main()
