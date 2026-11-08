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
}


def main():
    cache = {}
    out = {}
    for name, (f, clip) in BOT.items():
        if f not in cache:
            with open(os.path.join(HERE, "animations", f + ".animation.json")) as fh:
                cache[f] = json.load(fh)["animations"]
        out[name] = cache[f][clip]
    with open(OUT, "w") as fh:
        json.dump({"format_version": "1.8.0", "animations": out}, fh, separators=(",", ":"), allow_nan=False)
        fh.write("\n")


if __name__ == "__main__":
    main()
