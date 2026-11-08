"""A made-up fighter with a known rhythm, recorded as FightRecorder writes it: fights.py must count his rhythm back,
and train.py must learn it (his chance to look out grows with his time in cover) and write a model the mod reads.

python3 tools/imitation/test_imitation.py
"""

import gzip
import json
import os
import random
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fights  # noqa: E402

NAMES = ["bias", "health", "loaded", "reloading", "crouching", "prone", "visible", "known", "nearest", "nearestSeesMe", "seeingMe",
         "sinceSeen", "inCover", "peeks", "sinceHurt", "coverLow", "coverHigh", "friends", "exposed", "exposedFor", "hiddenFor"]
SPOTS = ["place", "score", "away", "high", "hard", "peek", "toward", "aside", "fromThreat"]


def fighter(seconds, seed):
    """hides 16 to 24 ticks, looks out 8 or 16, and every 5th time runs 8 blocks aside to the 2nd cover offered"""
    rnd = random.Random(seed)
    views, t, x, cycle = [], 0, 0.0, 0
    while t < seconds * 20:
        hide = 16 + rnd.randrange(3) * 4
        for k in range(0, hide, 4):
            views.append(view(t, x, hid=1, exp=0, in_cover=k, cover_x=x + 8))
            t += 4
        peek = 8 + rnd.randrange(2) * 8
        for k in range(0, peek, 4):
            views.append(view(t, x + 1.5, hid=0, exp=1, in_cover=hide + k, cover_x=x + 8, fired=1))
            t += 4
        cycle += 1
        if cycle % 5 == 0:
            views.append(view(t, x, hid=1, exp=0, in_cover=hide, cover_x=x + 8))
            t += 4
            for k in range(1, 4):
                views.append(view(t, x + k * 2.5, hid=0, exp=0, in_cover=0, cover_x=x + 8))
                t += 4
            x += 8
    return views


def view(t, x, hid, exp, in_cover, cover_x, fired=0):
    f = [0.0] * 21
    f[0] = 1
    f[1] = 1
    f[6] = 0.25 if exp else 0
    f[7] = 0.25
    f[8] = 0.5
    f[12] = min(in_cover, 400) / 400
    f[16] = hid
    f[20] = f[12] if hid else 0
    f[18] = exp
    # two cover places: straight back (the search's best) and the one he takes
    c = [[int(x) - 3, 64, 0, 9.0, 2, 1, 1, [0, 1, 0.2, 1, 1, 1, -0.2, 0, 0.6]],
         [int(cover_x), 64, 0, 7.0, 2, 1, 1, [1 / 7, 0.78, 0.5, 1, 1, 1, 0, 0.5, 0.5]]]
    return {"t": t, "x": x, "y": 64, "z": 0.5, "yaw": 0, "pitch": 0, "hp": 1, "ld": 20, "cap": 30, "rl": 0, "cr": 0, "pr": 0, "sp": 0,
            "fi": fired, "hu": 0, "ch": 2 if hid else 0, "hid": hid, "exp": exp, "f": f,
            "e": [[9, x + 30, 64, 0.5, exp, exp, exp]], "c": c}


def write(path, views):
    with gzip.open(path, "wt", encoding="utf-8") as f:
        f.write(json.dumps({"kind": "header", "v": 1, "who": "player", "name": "Test", "id": 1, "every": 4, "quiet": 20,
                            "features": NAMES, "spotFeatures": SPOTS}) + "\n")
        for v in views:
            f.write(json.dumps(v) + "\n")
        # a line cut off by a crash
        f.write('{"t": 99999, "x": 1')


def main():
    views = fighter(2400, 1)
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "a.jsonl.gz")
        write(path, views)
        h, read = fights.read(path)
        assert h["name"] == "Test" and len(read) == len(views), (len(read), len(views))
        s = fights.stats(read)
        assert s["peekTicks"]["median"] in (8, 16) and 10 <= s["peekTicks"]["mean"] <= 14, s["peekTicks"]
        assert 16 <= s["hideTicks"]["median"] <= 28, s["hideTicks"]
        # one cover change every 5 cycles of about 32 ticks plus the run: about 6 a minute
        assert 4 <= s["shiftsPerMinute"] <= 8, s["shiftsPerMinute"]
        assert s["coverChoices"] > 20, s
        out = os.path.join(d, "model.json")
        r = subprocess.run([sys.executable, os.path.join(HERE, "train.py"), out, d], capture_output=True, text=True, check=True)
        report = json.loads(r.stdout)
        m = json.load(open(out))
        assert m["version"] == 1 and m["features"] == NAMES
        assert set(m["questions"]) >= {"EXPOSE", "HIDE", "SHIFT"}, m["questions"].keys()
        # the longer in cover, the likelier out: the inCover weight is positive, and the model beats a constant chance
        assert m["questions"]["EXPOSE"]["w"][12] > 0, m["questions"]["EXPOSE"]["w"]
        assert report["EXPOSE"]["logloss"] < report["EXPOSE"]["constant"], report["EXPOSE"]
        assert report["RANK"]["modelTop"] > report["RANK"]["formulaTop"], report["RANK"]
        for q in m["questions"].values():
            assert len(q["w"]) == len(q["min"]) == len(q["max"]) == 21 and q["cases"] > 0
        print("ok", json.dumps({"stats": {k: s[k] for k in ("shiftsPerMinute", "peekTicks", "hideTicks")}, "EXPOSE": report["EXPOSE"],
                                "RANK": report["RANK"]}))


if __name__ == "__main__":
    main()
