#!/usr/bin/env python3
"""Compare two traces of NTM Reimagined Arms (-Dntmgunfeel.trace, see Trace.java) event by event.

    python3 -I tools/trace_diff.py A.tsv B.tsv [--abs 1e-4] [--rel 1e-6] [--tol kind.key=abs ...]
                                               [--skip kind ...] [--only kind ...] [--ignore key ...] [--no-time]
                                               [--bits kind.key=n ...] [--unordered kind ...] [--raw-shots] [--show 20]

Lines are matched by (side, part, tick, partial, kind, shot): the n-th such event of A with the n-th of B; part counts
the "segment" events of a scenario (TraceScenario), whose time starts over with each. Shots, and the
entity ids in --id-keys (default: target, owner), are numbered by the order each side first names them (entity ids
differ between runs and versions); --raw-shots keeps them as written. --no-time matches by (side, kind, shot) and order alone, for runs whose ticks are not the same.
Fields: the same keys; numbers (and comma-separated lists of them) within --abs or --rel of each other, everything
else exactly. --tol camera.pitch=1e-4 sets one key's tolerance; --tol hole_made=0 one kind's. --bits mask.hit=1: a
string of 0 and 1 (a hitbox's ray grid) may differ in up to n places. --only keeps the kinds named and drops the rest.
--unordered hole_made: that kind's events of one key are matched as a set (sorted by their fields), not in the order
written, for things made in the same tick in an order the run does not fix (a shotgun's pellets). Hex numbers
(-Dntmgunfeel.traceHex) are compared as written. The wall column is never compared.
Exit 0: the same; 1: differences; 2: bad input.
"""
import argparse
import math
import re
import sys
from collections import defaultdict

NUMBER = re.compile(r'^[-+]?(?:\d+\.?\d*(?:[eE][-+]?\d+)?|\.\d+(?:[eE][-+]?\d+)?|nan|NaN|-?Infinity|-?inf)$')


def read(path):
    header, events = {}, []
    with open(path, encoding='utf-8') as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line:
                continue
            if line.startswith('# trace '):
                for part in line[2:].split('  ')[1:]:
                    if '=' in part:
                        k, v = part.split('=', 1)
                        header[k.strip()] = v.strip()
                continue
            if line.startswith('#'):
                continue
            cols = line.split('\t')
            if len(cols) < 6:
                raise ValueError(f'{path}:{n}: not a trace line (did the run have -Dntmgunfeel.trace?)')
            tick, partial, _wall, side, shot, kind = cols[:6]
            fields = {}
            for kv in cols[6:]:
                k, _, v = kv.partition('=')
                fields[k] = v
            events.append({'line': n, 'tick': tick, 'partial': partial, 'side': side, 'shot': shot, 'kind': kind,
                           'fields': fields})
    if not header:
        raise ValueError(f'{path}: no "# trace" header')
    return header, events


def renumber(events, id_keys):
    # one numbering per side for shots and entity ids alike: a bullet's id is an entity id too
    seen, count = {}, defaultdict(int)

    def number_of(side, v):
        if v in ('-', '-1'):
            return v
        if (side, v) not in seen:
            count[side] += 1
            seen[(side, v)] = '#' + str(count[side])
        return seen[(side, v)]

    for e in events:
        e['shot'] = number_of(e['side'], e['shot'])
        for k in id_keys:
            if k in e['fields']:
                e['fields'][k] = number_of(e['side'], e['fields'][k])


def group(events, timed):
    # a scenario's time starts over with each part ("segment" event): the part's number goes into the key
    groups, part = defaultdict(list), defaultdict(int)
    for e in events:
        if e['kind'] == 'segment':
            part[e['side']] += 1
        seg = part[e['side']]
        key = (e['side'], seg, e['tick'], e['partial'], e['kind'], e['shot']) if timed else (e['side'], seg, e['kind'], e['shot'])
        groups[key].append(e)
    return groups


def number(s):
    if not NUMBER.match(s):
        return None
    try:
        return float(s)
    except ValueError:
        return None


def bits_close(a, b, n):
    if len(a) != len(b) or set(a) - {'0', '1'} or set(b) - {'0', '1'}:
        return False
    return sum(x != y for x, y in zip(a, b)) <= n


def close(a, b, abs_tol, rel_tol):
    if a == b:
        return True
    pa, pb = a.split(','), b.split(',')
    if len(pa) != len(pb):
        return False
    for x, y in zip(pa, pb):
        if x == y:
            continue
        fx, fy = number(x), number(y)
        if fx is None or fy is None:
            return False
        if math.isnan(fx) and math.isnan(fy):
            continue
        if not (abs(fx - fy) <= abs_tol or abs(fx - fy) <= rel_tol * max(abs(fx), abs(fy))):
            return False
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('a')
    ap.add_argument('b')
    ap.add_argument('--abs', type=float, default=0.0, help='absolute tolerance for numbers (default 0: exact)')
    ap.add_argument('--rel', type=float, default=0.0, help='relative tolerance for numbers (default 0)')
    ap.add_argument('--tol', action='append', default=[], help='kind.key=abs or kind=abs')
    ap.add_argument('--skip', action='append', default=[], help='an event kind left out')
    ap.add_argument('--only', action='append', default=[], help='keep only this event kind (several allowed)')
    ap.add_argument('--bits', action='append', default=[], help='kind.key=n: 0/1 strings may differ in n places')
    ap.add_argument('--unordered', action='append', default=[], help='an event kind matched as a set, not in order')
    ap.add_argument('--ignore', action='append', default=[], help='a key left out (kind.key or key)')
    ap.add_argument('--no-time', action='store_true', help='match by side, kind, shot and order, not by time')
    ap.add_argument('--raw-shots', action='store_true', help='compare bullet and entity ids as written')
    ap.add_argument('--id-keys', default='target,owner', help='keys holding entity ids, renumbered like shots')
    ap.add_argument('--show', type=int, default=20, help='differences shown (default 20)')
    args = ap.parse_args()

    tol = {}
    for t in args.tol:
        k, _, v = t.partition('=')
        tol[k] = float(v)
    try:
        ha, ea = read(args.a)
        hb, eb = read(args.b)
    except (OSError, ValueError) as e:
        print(e, file=sys.stderr)
        return 2
    for k in ('scenario', 'seed', 'clock', 'numbers'):
        if ha.get(k) != hb.get(k):
            print(f'note: header {k} differs: {ha.get(k)} / {hb.get(k)}')
    skip, only = set(args.skip), set(args.only)
    bits = {}
    for t in args.bits:
        k, _, v = t.partition('=')
        bits[k] = int(v)
    ea = [e for e in ea if e['kind'] not in skip and (not only or e['kind'] in only)]
    eb = [e for e in eb if e['kind'] not in skip and (not only or e['kind'] in only)]
    if not args.raw_shots:
        ids = [k for k in args.id_keys.split(',') if k]
        renumber(ea, ids)
        renumber(eb, ids)
    ga, gb = group(ea, not args.no_time), group(eb, not args.no_time)

    diffs = []
    only_a = only_b = same = 0
    unordered = set(args.unordered)

    def order(events):
        return sorted(events, key=lambda e: sorted(e['fields'].items()))

    for key in sorted(set(ga) | set(gb)):
        la, lb = ga.get(key, []), gb.get(key, [])
        if la and la[0]['kind'] in unordered or lb and lb[0]['kind'] in unordered:
            la, lb = order(la), order(lb)
        for i in range(max(len(la), len(lb))):
            if i >= len(lb):
                only_a += 1
                diffs.append(f'only in A  line {la[i]["line"]}: {key} #{i}')
                continue
            if i >= len(la):
                only_b += 1
                diffs.append(f'only in B  line {lb[i]["line"]}: {key} #{i}')
                continue
            a, b = la[i], lb[i]
            kind = a['kind']
            bad = []
            for k in sorted(set(a['fields']) | set(b['fields'])):
                if k in args.ignore or f'{kind}.{k}' in args.ignore:
                    continue
                va, vb = a['fields'].get(k), b['fields'].get(k)
                if va is None or vb is None:
                    bad.append(f'{k}: {va} / {vb}')
                    continue
                if f'{kind}.{k}' in bits:
                    if not bits_close(va, vb, bits[f'{kind}.{k}']):
                        bad.append(f'{k}: {sum(x != y for x, y in zip(va, vb))} of {len(va)} differ')
                    continue
                t = tol.get(f'{kind}.{k}', tol.get(kind, args.abs))
                if not close(va, vb, t, args.rel):
                    bad.append(f'{k}: {va} / {vb}')
            if bad:
                diffs.append(f'differs    lines {a["line"]}/{b["line"]}: {key}: ' + '; '.join(bad))
            else:
                same += 1

    print(f'A: {args.a} ({len(ea)} events)  B: {args.b} ({len(eb)} events)')
    print(f'same {same}, differing {len(diffs) - only_a - only_b}, only in A {only_a}, only in B {only_b}')
    for d in diffs[:args.show]:
        print('  ' + d)
    if len(diffs) > args.show:
        print(f'  ... {len(diffs) - args.show} more')
    return 1 if diffs else 0


if __name__ == '__main__':
    sys.exit(main())
