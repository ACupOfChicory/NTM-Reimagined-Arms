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
A baby (#baby) in 1.21.1 is the adult model scaled about the feet: it takes the adult's labels (scaled by the median of
the sizes), and its own 26.2 profile (another model there) only where the bank has no adult one.

python3 port_labels.py <dumps dir> <types file> <out dir>; the types file holds "heads <type>" / "limbs <type>" lines.
"""
import json, math, os, re, sys

ROOT = os.path.join(os.path.dirname(__file__), '..', '..', '..')
BANKS = {k: json.load(open(os.path.join(ROOT, 'src/main/resources/ntmgunfeel-%s.json' % k)))['profiles'] for k in ('heads', 'limbs')}
LAYER = re.compile(r'^root/(?:overlayLayer|bodyEquip/\d+)/draws/\d+/tree/')
SLACK = .04


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


def build(kind, type_, de, notes):
    bank = BANKS[kind][type_]
    adult = BANKS[kind].get(type_.removesuffix('#baby')) if type_.endswith('#baby') else None
    if adult is not None and not adult.get('none'): bank = adult
    names = {b['name'] for b in de}
    # an adult is one size on both versions: a part that changed (the tropical fish's top fin, x3 high) is no scale
    scale = scale_of(bank, de) if adult is not None else 1
    zones, seen = [], set()
    for z in bank['zones']:
        ref = bank['parts'][z['part']]
        name = target(ref['name'], names)
        if name is None:
            notes.append('%s %s: zone on %s has no part in 1.21.1, dropped' % (kind, type_, norm(ref['name'])))
            continue
        for i, box in enumerate(de):
            if box['name'] != name: continue
            kept = cut([carry(p, ref, scale, box) for p in z['planes']])
            if kept is None: continue
            if not z['planes'] and not inside(box, ref, scale):
                notes.append('%s %s: box %d %s lies outside the 26.2 part' % (kind, type_, i, name))
            key = (i, z['group'], json.dumps(kept))
            if key in seen: continue
            seen.add(key)
            zones.append({'part': i, 'group': z['group'], 'planes': kept})
    if not zones: raise SystemExit('%s %s: no zone left' % (kind, type_))
    parts = [{'name': b['name'], 'size': [round(x, 6) for x in b['size']], 'shape': b['shape'],
              'center': [round(x, 6) for x in b['center']]} for b in de]
    return {'none': False, 'parts': parts, 'zones': zones}


def main(dumps, types, out):
    result = {'heads': {}, 'limbs': {}}; notes = []
    for line in open(types):
        line = line.strip()
        if not line or line.startswith('#'): continue
        kind, type_ = line.split()
        dump = json.load(open(os.path.join(dumps, type_.replace(':', '_').replace('#', '_') + '.json')))
        result[kind][type_] = build(kind, type_, dump['parts'], notes)
    for kind, profiles in result.items():
        path = os.path.join(out, 'ntmgunfeel-%s-1.21.1.json' % kind)
        with open(path, 'w') as f:
            json.dump({'schema': 1, 'profiles': dict(sorted(profiles.items()))}, f, ensure_ascii=False, indent=1)
            f.write('\n')
        print(path, len(profiles))
    for n in notes: print('note:', n)


if __name__ == '__main__':
    main(*sys.argv[1:])
