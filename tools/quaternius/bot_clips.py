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
}
# turns only: the game moves the mob itself as it is thrown, and the head stays at the eye the server keeps
TURNS_ONLY = {"hit_knock"}


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
    with open(OUT, "w") as fh:
        json.dump({"format_version": "1.8.0", "animations": out}, fh, separators=(",", ":"), allow_nan=False)
        fh.write("\n")


if __name__ == "__main__":
    main()
