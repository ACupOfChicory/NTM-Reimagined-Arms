#!/usr/bin/env python3
"""Head and limb labels for the models 1.21.1 draws otherwise than 26.2 (notes/ports/de-1.21.1.md, «Разметка голов и
конечностей»).

The shared banks (src/main/resources/ntmgunfeel-heads.json, ntmgunfeel-limbs.json) were labelled on 26.2, whose vanilla
mobs are Flywheel visuals: a layer's parts are named after the visual's fields (root/overlayLayer/draws/0/tree/head), and
a part with several cubes is one mesh. DE 1.21.1 draws them through ModelPart: a layer's parts take the model's own names,
and every cube is its own box. This takes the parts from a snapshot of the port's own models (HeadSmoke, -PdumpProfiles)
and carries each labelled zone of the shared bank over to the boxes of the same part:
  - the part's name without the 26.2 layer prefix, else its last segment if that is unique, else that segment without
    26.2's _r1 of a rotated helper part and 1.21.1's baby_ of a foal's leg;
  - a zone with no planes takes every box of that part;
  - a zone cut by planes: the planes are in the 26.2 part's own frame (origin at its min x, max y, max z corner, axes
    +x, -y, -z in the profile frame); they are moved to each box's frame through the profile frame, and a box the planes
    keep whole or drop whole loses them or the zone.
A baby (#baby) in 1.21.1 is the adult model, its head and body scaled each its own way (AgeableListModel): with a
snapshot of the adult it takes the adult's zones box for box, planes unchanged (they are in the box's own frame, which
the scaling carries along); a box of the same name pairs with the one of the same shape and the most even scale. Without
the adult's snapshot it takes the adult's labels scaled by the median of the sizes; its own 26.2 profile (another model
there) only where the bank has no adult one.
Parts the model may not draw (main's `instead`, X456): a type line may name stand cases of other states of the mob
(minecraft:goat#nolefthorn, minecraft:vindicator#attack, minecraft:llama#carpet). A box only another state draws joins
the parts; a labelled box one state does not draw and a labelled box only another state draws take each other as
`instead` (the illager's crossed arms and its free arms), else the nearest labelled box every state draws (the goat's
horn the head, the llama's carpet the head), so the binding does not reject the label without them.

python3 port_labels.py <dumps dir> <types file> <out dir>; the types file holds "heads <type> [<case>...]" /
"limbs <type> [<case>...]" lines.
"""
import json, math, os, re, sys

ROOT = os.path.join(os.path.dirname(__file__), '..', '..', '..')
BANKS = {k: json.load(open(os.path.join(ROOT, 'src/main/resources/ntmgunfeel-%s.json' % k)))['profiles'] for k in ('heads', 'limbs')}
LAYER = re.compile(r'^root/(?:overlayLayer|bodyEquip/\d+)/draws/\d+/tree/')
SLACK = .04
WOOL = '/wool'


def norm(n):
    if n.startswith('mesh/'): n = n[5:]
    while n.startswith('root/root/'): n = n[5:]
    return n


def base(n):
    return LAYER.sub('root/', norm(n))


def last(n):
    return n.split('/')[-1]


def target(name, de_names):
    n = base(name)
    if n in de_names: return n
    hits = {d for d in de_names if last(d) == last(n)}
    if not hits: hits = {d for d in de_names if plain(d) == plain(n)}
    return hits.pop() if len(hits) == 1 else None


def plain(n):
    """The last segment without what one version adds: 26.2's rotated helper cube (tail_r1), 1.21.1's baby legs."""
    return re.sub(r'_r\d+$', '', last(n)).replace('baby_', '')


def bank_frame(ref, scale):
    s = [x * scale for x in ref['size']]; c = [x * scale for x in ref['center']]
    return [c[0] - s[0] / 2, c[1] + s[1] / 2, c[2] + s[2] / 2], s


def corners(box):
    o, u, v, w = box['origin'], box['u'], box['v'], box['w']
    return [[o[i] + a * u[i] + b * v[i] + c * w[i] for i in range(3)] for a in (0, 1) for b in (0, 1) for c in (0, 1)]


def inside(box, ref, scale):
    (ox, oy, oz), (lx, ly, lz) = bank_frame(ref, scale)
    lo, hi = [ox, oy - ly, oz - lz], [ox + lx, oy, oz]
    return all(lo[i] - SLACK <= p[i] <= hi[i] + SLACK for p in corners(box) for i in range(3))


def carry(plane, ref, scale, box):
    """The 26.2 plane on part ref, in box's own affine frame."""
    (ox, oy, oz), (lx, ly, lz) = bank_frame(ref, scale)
    px, py, pz, d = plane
    # bank local a=(X-ox)/lx, b=(oy-Y)/ly, c=(oz-Z)/lz; the box's point X=o+a'u+b'v+c'w
    def coef(vec): return px * vec[0] / lx - py * vec[1] / ly - pz * vec[2] / lz
    o = box['origin']
    return [coef(box['u']), coef(box['v']), coef(box['w']), d + px * (o[0] - ox) / lx + py * (oy - o[1]) / ly + pz * (oz - o[2]) / lz]


def value(plane, a, b, c): return plane[0] * a + plane[1] * b + plane[2] * c + plane[3]


def kept(planes):
    """The share of the box the planes keep, on a 10x10x10 grid of cell centres."""
    grid = [(i + .5) / 10 for i in range(10)]
    return sum(all(value(p, a, b, c) >= 0 for p in planes) for a in grid for b in grid for c in grid) / 1000


def cut(planes):
    """None: the box is outside (or a sliver under 2% of it); [] whole; else the planes that cut it."""
    if planes and kept(planes) < .02: return None
    keep = []
    for p in planes:
        vals = [value(p, a, b, c) for a in (0, 1) for b in (0, 1) for c in (0, 1)]
        if max(vals) < 1e-6: return None
        if min(vals) < -1e-8: keep.append([round(x, 6) for x in p])
    return keep


def scale_of(profile, de):
    ratios = []
    for ref in profile['parts']:
        hits = [b for b in de if b['name'] == target(ref['name'], {x['name'] for x in de})]
        if len(hits) == 1: ratios += [hits[0]['size'][i] / ref['size'][i] for i in range(3)]
    ratios.sort()
    return ratios[len(ratios) // 2] if ratios else 1


def carried(kind, type_, bank, de, scale, notes):
    """The bank's zones on the boxes of the same part: (box, group, planes)."""
    names = {b['name'] for b in de}
    # two labelled 26.2 parts of one 1.21.1 name (the llama's head and its carpet, a layer drawn again over the model):
    # a box takes the zones of the one nearest its size; of one size (the zombie villager's leg and its clothes' leg) both
    labelled = sorted({z['part'] for z in bank['zones']})
    def error(r, box): return max(abs(math.log(box['size'][k] / (bank['parts'][r]['size'][k] * scale))) for k in range(3))
    owner = {}
    for i, box in enumerate(de):
        refs = [r for r in labelled if target(bank['parts'][r]['name'], names) == box['name']]
        if len(refs) < 2: continue
        best = min(error(r, box) for r in refs)
        owner[i] = {r for r in refs if error(r, box) - best < .02}
    zones, seen = [], set()
    for z in bank['zones']:
        ref = bank['parts'][z['part']]
        name = target(ref['name'], names)
        if name is None:
            notes.append('%s %s: zone on %s has no part in 1.21.1, dropped' % (kind, type_, norm(ref['name'])))
            continue
        for i, box in enumerate(de):
            if box['name'] != name or z['part'] not in owner.get(i, {z['part']}): continue
            kept = cut([carry(p, ref, scale, box) for p in z['planes']])
            if kept is None: continue
            if not z['planes'] and not inside(box, ref, scale):
                notes.append('%s %s: box %d %s lies outside the 26.2 part' % (kind, type_, i, name))
            key = (i, z['group'], json.dumps(kept))
            if key in seen: continue
            seen.add(key)
            zones.append({'part': i, 'group': z['group'], 'planes': kept})
    return zones


def same_box(a, b):
    return a['name'] == b['name'] and a['shape'] == b['shape'] and all(abs(a['size'][i] - b['size'][i]) < 1e-3 for i in range(3)) \
        and all(abs(a['center'][i] - b['center'][i]) < 1e-3 for i in range(3))


def drawn_alike(a, b):
    """One box in two states of a mob: its pose may differ (the illager's raised arm), its cube not."""
    return a['name'] == b['name'] and a['shape'] == b['shape'] and all(abs(a['size'][i] - b['size'][i]) < 1e-3 for i in range(3))


def union(de, states):
    """The parts of every state: the base's boxes, then each box only another state draws (one cube in another pose is
    the same box); for each state, the boxes of the union it draws."""
    parts = list(de); drawn = []
    for state in states:
        shown = set()
        for box in state:
            free = [i for i in range(len(parts)) if i not in shown]
            hit = next((i for i in free if same_box(parts[i], box)), None)
            if hit is None:
                alike = [i for i in free if drawn_alike(parts[i], box)]
                c = box['center']
                hit = min(alike, key=lambda i: sum((parts[i]['center'][k] - c[k]) ** 2 for k in range(3))) if alike else None
            if hit is None: parts.append(box); hit = len(parts) - 1
            shown.add(hit)
        drawn.append(shown)
    return parts, [set(range(len(de)))] + [d for d in drawn]


def link(parts, zones, drawn, notes, label):
    """instead for each labelled box some state does not draw: a labelled box drawn in every state without it, first
    one that itself comes and goes (the illager's crossed arms <-> its free arms), else one every state draws."""
    labelled = sorted({z['part'] for z in zones})
    stable = [i for i in labelled if all(i in d for d in drawn)]
    instead = {}
    for i in labelled:
        if i in stable: continue
        without = [d for d in drawn if i not in d]
        cands = [j for j in labelled if j != i and all(j in d for d in without)]
        c = parts[i]['center']
        def dist(j): return sum((parts[j]['center'][k] - c[k]) ** 2 for k in range(3))
        pick = sorted(cands, key=lambda j: (j in stable, dist(j)))
        if not pick: notes.append('%s: labelled box %d %s has no partner for the states without it' % (label, i, parts[i]['name'])); continue
        instead[i] = pick[0]
    return instead


def pair(adult, baby):
    """Each baby box -> the adult box it scales: same name without 1.21.1's baby_ (a foal's legs are other cubes), then
    the same shape and the most even scale. (box -> adult box, the unevenness of each pair)"""
    def key(b): return b['name'].replace('baby_', '')
    def cost(a, b):
        r = [math.log(b['size'][i] / a['size'][i]) for i in range(3)]
        return max(r) - min(r) + (a['shape'] != b['shape'])
    out, used, uneven = {}, set(), {}
    for c, j, i in sorted((cost(a, b), j, i) for i, a in enumerate(adult) for j, b in enumerate(baby) if key(a) == key(b)):
        if j in out or i in used: continue
        out[j] = i; used.add(i); uneven[j] = c
    return out, uneven


def profile(kind, type_, bank, de, scale, notes, states=()):
    parts, drawn = union(de, states)
    zones = carried(kind, type_, bank, parts, scale, notes)
    return parts, zones, drawn


def build(kind, type_, de, notes, states=(), adult_de=None, adult_states=()):
    bank = BANKS[kind][type_]
    adult = BANKS[kind].get(type_.removesuffix('#baby')) if type_.endswith('#baby') else None
    if adult is not None and not adult.get('none'): bank = adult
    if adult is not None and adult_de is not None and bank is adult:
        # box for box from the adult's snapshot
        grown, grown_zones, _ = profile(kind, type_.removesuffix('#baby'), adult, adult_de, 1, [], adult_states)
        parts, drawn = union(de, states)
        to, uneven = pair(grown, parts)
        for j, c in sorted(uneven.items()):
            if c > .05 and any(z['part'] == to[j] for z in grown_zones):
                notes.append('%s %s: box %d %s is another cube than the adult\'s (unevenness %.2f), its zone carried in its own frame' % (kind, type_, j, parts[j]['name'], c))
        zones = [{'part': j, 'group': z['group'], 'planes': z['planes']} for z in grown_zones for j in sorted(to) if z['part'] == to[j]]
        for i, box in enumerate(grown):
            if any(z['part'] == i for z in grown_zones) and i not in to.values():
                notes.append('%s %s: adult box %d %s has no baby box, its zone dropped' % (kind, type_, i, box['name']))
    else:
        # an adult is one size on both versions: a part that changed (the tropical fish's top fin, x3 high) is no scale
        scale = scale_of(bank, de) if adult is not None else 1
        parts, zones, drawn = profile(kind, type_, bank, de, scale, notes, states)
    if not zones: raise SystemExit('%s %s: no zone left' % (kind, type_))
    instead = link(parts, zones, drawn, notes, '%s %s' % (kind, type_))
    out = []
    for i, b in enumerate(parts):
        part = {'name': b['name'], 'size': [round(x, 6) for x in b['size']], 'shape': b['shape'],
                'center': [round(x, 6) for x in b['center']]}
        if i in instead: part['instead'] = instead[i]
        out.append(part)
    return {'none': False, 'parts': out, 'zones': zones}


def main(dumps, types, out):
    result = {'heads': {}, 'limbs': {}}; notes = []
    for line in open(types):
        line = line.strip()
        if not line or line.startswith('#'): continue
        kind, type_, *cases = line.split()
        def load(case):
            path = os.path.join(dumps, case.replace(':', '_').replace('#', '_') + '.json')
            if not os.path.exists(path): return None
            parts = json.load(open(path))['parts']
            # the stand regrows a sheared sheep's wool before its first binding check, so that snapshot has the fur
            # layer; at spawn the capture is the same boxes without it (port-dumps456c.result: 6 parts)
            if '#sheared' in case: parts = [b for b in parts if not b['name'].endswith(WOOL)]
            return parts
        grown = type_.removesuffix('#baby')
        adult_cases = [c for t in open(types) for k, a, *cs in [t.split() or ['#', '']] if k == kind and a == grown for c in cs] \
            if type_.endswith('#baby') else []
        for c in cases + adult_cases:
            if load(c) is None: notes.append('%s %s: no snapshot of %s' % (kind, type_, c))
        result[kind][type_] = build(kind, type_, load(type_), notes, [x for x in map(load, cases) if x is not None],
                                    load(grown) if type_.endswith('#baby') else None, [x for x in map(load, adult_cases) if x is not None])
    for kind, profiles in result.items():
        path = os.path.join(out, 'ntmgunfeel-%s-1.21.1.json' % kind)
        with open(path, 'w') as f:
            json.dump({'schema': 1, 'profiles': dict(sorted(profiles.items()))}, f, ensure_ascii=False, indent=1)
            f.write('\n')
        print(path, len(profiles))
    for n in notes: print('note:', n)


if __name__ == '__main__':
    main(*sys.argv[1:])
