"""Train the bots' imitation model on recorded fights (fights.py works out the cases) and write the model file the
mod reads (ImitationModel): a logistic regression for each question, a softmax score for the cover chosen.

The last fifth of every recording is held out: the model is scored on it against a constant chance (the training
rate) by log loss, and by AUC; the cover choice against always the search's best (what the formula takes).

Usage: python3 tools/imitation/train.py OUT.json FILE_OR_DIR...   (then copy OUT.json to the server's
config/ntm-gunfeel-imitation.json and /armsai imitation reload)
"""

import json
import sys

import numpy as np

import fights

FEATURES = None  # from the recordings' header
L2 = 1e-3
HOLD = 0.2
# ImitationModel.MIN_CASES: fewer, and the mod does not use the question
MIN_CASES = 200


def logistic(X, y, l2=L2, steps=200):
    """weights by Newton's method (small rows: a few hundred cases to a few hundred thousand)"""
    w = np.zeros(X.shape[1])
    reg = l2 * np.eye(X.shape[1])
    reg[0, 0] = 0  # the bias is not held back
    for _ in range(steps):
        p = 1 / (1 + np.exp(-(X @ w)))
        g = X.T @ (p - y) / len(y) + reg @ w
        H = (X.T * (p * (1 - p))) @ X / len(y) + reg + 1e-9 * np.eye(X.shape[1])
        step = np.linalg.solve(H, g)
        w -= step
        if np.max(np.abs(step)) < 1e-7:
            break
    return w


def logloss(p, y):
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return float(-np.mean(y * np.log(p) + (1 - y) * np.log(1 - p)))


def auc(p, y):
    pos, neg = p[y == 1], p[y == 0]
    if len(pos) == 0 or len(neg) == 0:
        return None
    order = np.argsort(np.concatenate([pos, neg]))
    ranks = np.empty(len(order))
    ranks[order] = np.arange(1, len(order) + 1)
    return float((ranks[: len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)))


def softmax_fit(groups, l2=L2, steps=500, lr=0.5):
    """conditional logit: groups of (rows, chosen); gradient steps"""
    d = len(groups[0][0][0])
    w = np.zeros(d)
    G = [(np.array(r), c) for r, c in groups]
    for _ in range(steps):
        g = l2 * w
        for R, c in G:
            s = R @ w
            s -= s.max()
            e = np.exp(s)
            p = e / e.sum()
            g += (R.T @ p - R[c]) / len(G)
        w -= lr * g
    return w


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    out_path = argv[1]
    train = {q: [] for q in fights.WINDOWS}
    test = {q: [] for q in fights.WINDOWS}
    rank_train, rank_test = [], []
    header, who = None, set()
    for p in fights.files(argv[2:]):
        h, views = fights.read(p)
        if h is None:
            continue
        who.add(h.get("who", "?") + ":" + h.get("name", "?"))
        if header is None:
            header = h
        elif h["features"] != header["features"] or h["spotFeatures"] != header["spotFeatures"]:
            print(p, ": another build's numbers, skipped", file=sys.stderr)
            continue
        cut = int(len(views) * (1 - HOLD))
        a, ra = fights.cases(views[:cut])
        b, rb = fights.cases(views[cut:])
        for q in fights.WINDOWS:
            train[q] += a[q]
            test[q] += b[q]
        rank_train += ra
        rank_test += rb
    if header is None:
        print("no recordings", file=sys.stderr)
        return 1
    names = sorted(who)
    model = {"version": 1, "about": " ".join(names[:5]) + (" and %d more" % (len(names) - 5) if len(names) > 5 else ""),
             "features": header["features"], "spotFeatures": header["spotFeatures"], "questions": {}}
    report = {}
    for q, rows in train.items():
        if len(rows) + len(test[q]) < MIN_CASES or len({y for _, y in rows}) < 2:
            report[q] = {"cases": len(rows), "skipped": "too few cases, or all one answer"}
            continue
        X = np.array([r for r, _ in rows], dtype=float)
        y = np.array([1.0 if d else 0.0 for _, d in rows])
        w = logistic(X, y)
        # scored on the held-out part with the weights from the rest; the file's weights from all of it
        Xa = np.array([r for r, _ in rows + test[q]], dtype=float)
        ya = np.array([1.0 if d else 0.0 for _, d in rows + test[q]])
        model["questions"][q] = {"w": logistic(Xa, ya).round(5).tolist(), "min": Xa.min(0).round(4).tolist(), "max": Xa.max(0).round(4).tolist(),
                                 "window": fights.WINDOWS[q], "cases": len(ya)}
        r = {"cases": len(rows), "rate": round(float(y.mean()), 4)}
        if test[q]:
            Xt = np.array([x for x, _ in test[q]], dtype=float)
            yt = np.array([1.0 if d else 0.0 for _, d in test[q]])
            pt = 1 / (1 + np.exp(-(Xt @ w)))
            r.update(held=len(yt), logloss=round(logloss(pt, yt), 4), constant=round(logloss(np.full(len(yt), y.mean()), yt), 4),
                     auc=None if auc(pt, yt) is None else round(auc(pt, yt), 3))
        report[q] = r
    if len(rank_train) + len(rank_test) >= MIN_CASES and rank_train:
        w = softmax_fit(rank_train)
        both = rank_train + rank_test
        allrows = np.array([row for g, _ in both for row in g], dtype=float)
        model["rank"] = {"w": softmax_fit(both).round(5).tolist(), "min": allrows.min(0).round(4).tolist(), "max": allrows.max(0).round(4).tolist(),
                         "window": 1, "cases": len(both)}
        r = {"cases": len(rank_train), "firstChosen": round(sum(1 for _, c in rank_train if c == 0) / len(rank_train), 3)}
        if rank_test:
            hit = sum(1 for g, c in rank_test if int(np.argmax(np.array(g) @ w)) == c)
            r.update(held=len(rank_test), modelTop=round(hit / len(rank_test), 3),
                     formulaTop=round(sum(1 for _, c in rank_test if c == 0) / len(rank_test), 3))
        report["RANK"] = r
    else:
        report["RANK"] = {"cases": len(rank_train), "skipped": "too few cover choices"}
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(model, f)
    print(json.dumps(report, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
