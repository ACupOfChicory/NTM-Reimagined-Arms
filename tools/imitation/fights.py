"""Recorded fights (FightRecorder): read them, work out what the fighter decided, count how he fights.

A recording is a gzipped JSON-lines file: a header, then a view every 4 ticks in a fight (every 20 out of one).
What he decided is not in it; it is worked out here from the views that follow each one:

  EXPOSE  hidden in cover, not reloading: a step out into an enemy's sight within 12 ticks
  HIDE    out at his peek (in sight by his cover, still): a step back, hidden again, within 12 ticks
  (a step: an enemy that moves round him changes what sees him, but that is not his decision)
  SHIFT       settled in cover: more than 4 blocks off within 40 ticks
  SHIFT_BACK  just back in from a look out: the same
  SHIFT_HIT   hit by his cover: the same
  PUSH    enemies known, none seen for 2 seconds: 2 blocks nearer where the nearest was within 20 ticks
  RANK    at the start of a SHIFT: which of the cover places the bots' search offered he was hidden in next

Usage: python3 tools/imitation/fights.py stats|pool FILE_OR_DIR...   (pool: all of them as one fighter's numbers)
"""

import gzip
import json
import math
import os
import sys

WINDOWS = {"EXPOSE": 12, "HIDE": 12, "SHIFT": 40, "SHIFT_BACK": 40, "SHIFT_HIT": 40, "PUSH": 20}
# views further apart than this are a gap: no window runs across one, ticks
GAP = 8
SHIFT_AWAY = 4.0
STEP = 0.7
# standing still this long in cover, ticks: settled there (not on his way)
SETTLED = 8
PUSH_NEARER = 2.0
PUSH_UNSEEN = 40


def files(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _, names in os.walk(p):
                for n in sorted(names):
                    if n.endswith(".jsonl.gz") or n.endswith(".jsonl"):
                        yield os.path.join(root, n)
        else:
            yield p


def read(path):
    """(header, views); a line cut off at the end (a crash, a full file) is dropped"""
    opener = gzip.open if path.endswith(".gz") else open
    header, views = None, []
    try:
        with opener(path, "rt", encoding="utf-8") as f:
            for line in f:
                try:
                    o = json.loads(line)
                except json.JSONDecodeError:
                    break
                if o.get("kind") == "header":
                    header = o
                else:
                    views.append(o)
    except (EOFError, OSError):
        pass  # a gzip cut off: what was read is kept
    return header, views


def dist(a, b):
    return math.hypot(a["x"] - b["x"], a["z"] - b["z"])


def fight(v):
    return len(v["e"]) > 0


def window(views, i, ticks):
    """the views after i within ticks, or None when a gap breaks the window before its end"""
    out, t0, last = [], views[i]["t"], views[i]["t"]
    for j in range(i + 1, len(views)):
        t = views[j]["t"]
        if t - last > GAP:
            return None
        if t - t0 > ticks:
            return out
        out.append(views[j])
        last = t
    return None  # the recording ends within it


def near_cover(v):
    # FightFeatures: inCover (index 12) is nonzero only by the cover he took
    return v["f"][12] > 0 or v["hid"] == 1


def still(v):
    """ticks he has stood still (FightFeatures stillFor, index 21)"""
    return v["f"][21] * 400 if len(v["f"]) > 21 else 0


def cases(views):
    """the model's cases: {question: [(row, did)]}, and the cover choices: [(rows, chosen)]

    Each question is asked where a bot asks it: a mean over moments when the decision could not be taken (on his
    way, reloading) would be too low a chance for the moments it can.
    """
    out = {q: [] for q in WINDOWS}
    ranks = []
    for i, v in enumerate(views):
        if not fight(v):
            continue
        f = v["f"]
        prev = views[i - 1] if i > 0 and v["t"] - views[i - 1]["t"] <= GAP else None
        settled = v["hid"] == 1 and v["rl"] == 0 and still(v) >= SETTLED
        # hidden, not reloading (a new fight's first hide is short: no stillness asked)
        if v["hid"] == 1 and v["rl"] == 0:
            w = window(views, i, WINDOWS["EXPOSE"])
            if w is not None:
                out["EXPOSE"].append((f, any(x["exp"] == 1 and dist(x, v) >= STEP for x in w)))
        # out at his peek: in an enemy's sight by his cover, standing there
        if v["exp"] == 1 and near_cover(v) and still(v) >= 4:
            w = window(views, i, WINDOWS["HIDE"])
            if w is not None:
                out["HIDE"].append((f, any(x["hid"] == 1 and dist(x, v) >= STEP for x in w)))
        if near_cover(v):
            w = window(views, i, WINDOWS["SHIFT"])
            if w is not None:
                moved = any(dist(x, v) > SHIFT_AWAY for x in w)
                # settled in cover: whether to look for a better place now
                if settled:
                    out["SHIFT"].append((f, moved))
                # just back in from a look out (a step of his out of their sight)
                if v["hid"] == 1 and prev is not None and prev["exp"] == 1 and dist(prev, v) >= STEP:
                    out["SHIFT_BACK"].append((f, moved))
                # hit by his cover
                if v["hu"] == 1:
                    out["SHIFT_HIT"].append((f, moved))
                # the start of the move: still before, off after (one choice a move, not one a step)
                start = (i + 1 < len(views) and dist(views[i + 1], v) > 0.5) and (prev is None or dist(prev, v) <= 0.5)
                if moved and v["c"] and start:
                    pick = chosen(views, i)
                    if pick is not None:
                        ranks.append(([c[7] for c in v["c"]], pick))
        seen = sum(1 for e in v["e"] if e[4] == 1)
        if seen == 0 and f[11] * 400 >= PUSH_UNSEEN:
            w = window(views, i, WINDOWS["PUSH"])
            if w is not None:
                e = v["e"][0]
                d0 = math.hypot(e[1] - v["x"], e[3] - v["z"])
                out["PUSH"].append((f, any(d0 - math.hypot(e[1] - x["x"], e[3] - x["z"]) >= PUSH_NEARER for x in w)))
    return out, ranks


def chosen(views, i):
    """the cover place offered at i that he was hidden in next (within 60 ticks), or None"""
    v = views[i]
    for j in range(i + 1, len(views)):
        x = views[j]
        if x["t"] - v["t"] > 60:
            return None
        if x["hid"] == 1 and dist(x, v) > SHIFT_AWAY - 1:
            best, bd = None, 1.5
            for k, c in enumerate(v["c"]):
                d = math.hypot(c[0] + 0.5 - x["x"], c[2] + 0.5 - x["z"])
                if d < bd:
                    best, bd = k, d
            return best
    return None


def runs(views, key):
    """lengths in ticks of the runs of views with v[key] == 1 in a fight, each closed (a gap or the end drops it)"""
    out, start, last = [], None, None
    for v in views:
        if last is not None and v["t"] - last > GAP:
            start = None
        on = fight(v) and v[key] == 1
        if on and start is None:
            start = v["t"]
        elif not on and start is not None:
            out.append(v["t"] - start)
            start = None
        last = v["t"]
    return out


def stats(views):
    """how he fights: the numbers the bots are held against"""
    fv = [v for v in views if fight(v)]
    if not fv:
        return {"fightSeconds": 0}
    # fight time: views a fight apart by at most GAP
    ticks = 0
    for a, b in zip(fv, fv[1:]):
        if b["t"] - a["t"] <= GAP:
            ticks += b["t"] - a["t"]
    out, ranks = cases(views)
    # cover changes: off more than SHIFT_AWAY from where he was hidden; one a window
    shifts, quiet_until = 0, -1
    for i, v in enumerate(views):
        if v["hid"] == 1 and fight(v) and v["t"] > quiet_until:
            w = window(views, i, WINDOWS["SHIFT"])
            if w and any(dist(x, v) > SHIFT_AWAY for x in w):
                shifts += 1
                quiet_until = v["t"] + WINDOWS["SHIFT"]
    peeks = runs(views, "exp")
    hides = [r for r in runs(views, "hid")]
    # reaction: from an enemy coming into sight (none seen before) to his first shot, ticks
    reactions, since = [], None
    for a, b in zip(views, views[1:]):
        va = sum(1 for e in a["e"] if e[4] == 1)
        vb = sum(1 for e in b["e"] if e[4] == 1)
        if va == 0 and vb > 0 and b["t"] - a["t"] <= GAP:
            since = b["t"]
        if since is not None and b["fi"] == 1:
            if b["t"] - since <= 100:
                reactions.append(b["t"] - since)
            since = None
        if since is not None and vb == 0:
            since = None
    reloads = [b for a, b in zip(views, views[1:]) if a["rl"] == 0 and b["rl"] == 1 and fight(b)]
    dists = [math.hypot(v["e"][0][1] - v["x"], v["e"][0][3] - v["z"]) for v in fv if v["e"][0][4] == 1]
    minutes = ticks / 1200.0
    return {
        "fightSeconds": round(ticks / 20.0, 1),
        "shiftsPerMinute": round(shifts / minutes, 2) if minutes > 0 else 0,
        "peekTicks": summary(peeks),
        "hideTicks": summary(hides),
        "reactionTicks": summary(reactions),
        "inCoverShare": round(sum(1 for v in fv if v["hid"] == 1) / len(fv), 3),
        "reloadsInCoverShare": round(sum(1 for b in reloads if b["hid"] == 1) / len(reloads), 3) if reloads else None,
        "fightDistance": summary(dists),
        "cases": {q: [len(c), sum(1 for _, y in c if y)] for q, c in out.items()},
        "coverChoices": len(ranks),
    }


def summary(xs):
    if not xs:
        return None
    s = sorted(xs)
    return {"n": len(s), "median": s[len(s) // 2], "mean": round(sum(s) / len(s), 1)}


def pool(all_stats):
    """many fighters' numbers as one: rates weighted by fight time, lengths by how many there were"""
    secs = sum(x["fightSeconds"] for x in all_stats)
    out = {"fighters": len(all_stats), "fightSeconds": round(secs, 1)}
    if secs <= 0:
        return out
    out["shiftsPerMinute"] = round(sum(x["shiftsPerMinute"] * x["fightSeconds"] for x in all_stats if x["fightSeconds"]) / secs, 2)
    out["inCoverShare"] = round(sum(x["inCoverShare"] * x["fightSeconds"] for x in all_stats if x["fightSeconds"]) / secs, 3)
    for k in ("peekTicks", "hideTicks", "reactionTicks", "fightDistance"):
        xs = [x[k] for x in all_stats if x.get(k)]
        n = sum(x["n"] for x in xs)
        out[k] = {"n": n, "mean": round(sum(x["mean"] * x["n"] for x in xs) / n, 1)} if n else None
    rl = [x["reloadsInCoverShare"] for x in all_stats if x.get("reloadsInCoverShare") is not None]
    out["reloadsInCoverShare"] = round(sum(rl) / len(rl), 3) if rl else None
    return out


def main(argv):
    if len(argv) < 3 or argv[1] not in ("stats", "pool"):
        print(__doc__)
        return 2
    every = []
    for p in files(argv[2:]):
        h, views = read(p)
        if h is None:
            print(p, ": no header, skipped", file=sys.stderr)
            continue
        s = stats(views)
        every.append(s)
        if argv[1] == "stats":
            print(json.dumps({"file": p, "who": h.get("who"), "name": h.get("name"), **s}))
    if argv[1] == "pool":
        print(json.dumps(pool([x for x in every if x["fightSeconds"] > 0])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
