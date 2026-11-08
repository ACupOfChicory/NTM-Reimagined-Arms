#!/usr/bin/env python3
"""Copy the clips AI mobs play out of animations/ into the mod's resources.

python3 -I tools/quaternius/bot_clips.py

Only clips check.json grades good or fair where they are used (see BOT below); everything else a mob does is
posed in code (BotPoser). Writes src/main/resources/assets/ntmgunfeel/animations/bot.animation.json.
"""
import json
import os

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


def main():
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
    with open(OUT, "w") as fh:
        json.dump({"format_version": "1.8.0", "animations": out}, fh, separators=(",", ":"), allow_nan=False)
        fh.write("\n")


if __name__ == "__main__":
    main()
