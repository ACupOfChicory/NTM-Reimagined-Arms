#!/usr/bin/env python3
# Builds the five gun mechanisms NTM has no item for (steel, high-speed steel, desh, ferrouranium, technetium steel) and
# the recipes that take them, into src/main/resources:
# - assets/hbm/textures/item/part_mechanism_<material>.png: NTM's three mechanisms share one 16x16 mask of six shades;
#   each pixel's shade (its rank by brightness) gets the colour of that rank in the material's palette, which every gun
#   part of the material in NTM shares (six colours). The same method rebuilds NTM's three mechanisms exactly.
# - assets/hbm/items and models/item: as NTM's mechanisms have them.
# - data/hbm/tags/item/gun_mechanisms/<material>.json: the casting mold, the crucible and JEI find a mechanism by it.
# - recipes: NTM's recipe of each gun in RECIPES with the mechanism swapped, under NTM's id (data/hbm/recipe) with the
#   condition ntmgunfeel:gun_mechanism_recipes on = true, and NTM's own as it is under data/ntmgunfeel/recipe/ntm with
#   on = false. Tesla Cannon also has the balance's recipe (balance_recipes), so it gets all four.
# Usage: tools/gun_mechanisms.py [NTM jar] (default: deps.ntm of stonecutter.properties.toml)
import json, os, re, struct, sys, zipfile, zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'src', 'main', 'resources')

# the new mechanisms: NTM's material name (its tag path) -> the part of NTM whose palette it takes
MATERIALS = {
    'steel': 'part_receiver_light_steel',
    'dura_steel': 'part_receiver_light_dura_steel',
    'workers_alloy': 'part_receiver_light_workers_alloy',
    'ferrouranium': 'part_receiver_heavy_ferrouranium',
    'tc_alloy': 'part_receiver_light_tc_alloy',
}
NTM_MECHANISMS = ['gun_metal', 'weapon_steel', 'saturnite']
PARTS = ['part_receiver_light', 'part_receiver_heavy', 'part_barrel_light', 'part_barrel_heavy', 'part_grip', 'part_stock']

# The mechanism is the gun's tier, as in NTM (gunmetal for the early guns, weapon steel for the middle ones, saturnite
# for the late ones), on a finer ladder of eight, by when the material can be had:
# steel < gunmetal < high-speed steel (tier 2) < desh < ferrouranium < weapon steel (tier 4) < technetium steel (5) < saturnite (6).
# A gun takes the rung of its tier and its own material (its barrels and receivers); a B-side (the base gun and
# mechanisms) the rung above its base gun's, never below the B-side's own tier (notes/balance/progression-items.md).
# recipe -> {NTM's mechanism: the new one}
RECIPES = {
    'gun_flaregun': {'gun_metal': 'steel'},
    'gun_greasegun': {'gun_metal': 'steel'},
    'gun_light_revolver': {'gun_metal': 'steel'},
    'gun_maresleg': {'gun_metal': 'steel'},
    'gun_charge_thrower': {'gun_metal': 'steel'},
    'gun_charge_thrower_rubber': {'gun_metal': 'steel'},
    'gun_am180': {'gun_metal': 'dura_steel'},
    'gun_congolake': {'gun_metal': 'dura_steel'},
    'gun_flamer': {'gun_metal': 'dura_steel'},
    'gun_liberator': {'gun_metal': 'dura_steel'},
    'gun_carbine': {'gun_metal': 'workers_alloy'},
    'gun_heavy_revolver': {'gun_metal': 'workers_alloy'},
    'gun_panzerschreck': {'gun_metal': 'workers_alloy'},
    'gun_spas12': {'gun_metal': 'workers_alloy'},
    'gun_uzi': {'gun_metal': 'workers_alloy'},
    'gun_light_revolver_atlas': {'weapon_steel': 'workers_alloy'},
    'gun_henry_lincoln': {'weapon_steel': 'workers_alloy'},
    'gun_maresleg_akimbo': {'weapon_steel': 'workers_alloy'},
    'gun_flamer_topaz': {'weapon_steel': 'workers_alloy'},
    'gun_amat': {'weapon_steel': 'ferrouranium'},
    'gun_autoshotgun': {'weapon_steel': 'ferrouranium'},
    'gun_m2': {'weapon_steel': 'ferrouranium'},
    'gun_quadro': {'weapon_steel': 'ferrouranium'},
    'gun_uzi_akimbo': {'weapon_steel': 'ferrouranium'},
    'gun_lag': {'weapon_steel': 'tc_alloy'},
    'gun_minigun': {'weapon_steel': 'tc_alloy'},
    'gun_missile_launcher': {'weapon_steel': 'tc_alloy'},
    'gun_tesla_cannon': {'weapon_steel': 'tc_alloy'},
}
# recipes the balance (balance_recipes) also has under NTM's id -> the mechanism of the balance's recipe (Tesla Cannon:
# tier 7 with it, so saturnite; NTM's at tier 5 takes technetium steel). The balance's recipe with NTM's mechanism is
# kept in data/ntmgunfeel/recipe/balance/ and read from there: change the balance's recipe there, not in data/hbm.
BALANCE = {'gun_tesla_cannon': 'saturnite'}
MECH = 'ntmgunfeel:gun_mechanism_recipes'
BAL = 'ntmgunfeel:balance_recipes'


def decode(data):
    i = 8; idat = b''
    while i < len(data):
        l = struct.unpack('>I', data[i:i+4])[0]; t = data[i+4:i+8]; d = data[i+8:i+8+l]
        if t == b'IHDR': W, H, bd, ct = struct.unpack('>IIBB', d[:10])
        elif t == b'IDAT': idat += d
        i += 12 + l
    assert bd == 8 and ct == 6, 'only 8-bit RGBA PNGs'
    bpp = 4; raw = zlib.decompress(idat); stride = W * bpp; rows = []; prev = bytearray(stride)
    for y in range(H):
        f = raw[y*(stride+1)]; line = bytearray(raw[y*(stride+1)+1:(y+1)*(stride+1)])
        for x in range(stride):
            a = line[x-bpp] if x >= bpp else 0; b = prev[x]; c = prev[x-bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line)); prev = line
    return W, H, [[tuple(r[x*4:x*4+4]) for x in range(W)] for r in rows]


def encode(pixels):
    H = len(pixels); W = len(pixels[0])
    raw = b''.join(b'\0' + bytes(c for p in row for c in p) for row in pixels)
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


def luma(c): return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]


def palette(pixels):
    cols = {p[:3] for row in pixels for p in row if p[3]}
    assert all(p[3] in (0, 255) for row in pixels for p in row), 'only fully clear or solid pixels'
    return sorted(cols, key=luma)


def mask(pixels):
    pal = palette(pixels)
    return [[pal.index(p[:3]) if p[3] else None for p in row] for row in pixels], pal


def paint(m, pal):
    return [[(0, 0, 0, 0) if i is None else pal[i] + (255,) for i in row] for row in m]


def solid(pixels):
    # clear pixels as (0, 0, 0, 0): NTM's keep a colour in them that is never drawn
    return [[p if p[3] else (0, 0, 0, 0) for p in row] for row in pixels]


def hexc(c): return '#%02x%02x%02x' % c


def write(path, data):
    path = os.path.join(RES, path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if isinstance(data, (dict, list)): data = (json.dumps(data, indent=2, ensure_ascii=False) + '\n').encode()
    with open(path, 'wb') as f: f.write(data)


def jar_path():
    if len(sys.argv) > 1: return sys.argv[1]
    m = re.search(r'deps\.ntm\s*=\s*"([^"]+)"', open(os.path.join(ROOT, 'stonecutter.properties.toml')).read())
    return os.path.join(ROOT, m.group(1))


def swap(recipe, change):
    s = json.dumps(recipe)
    for old, new in change.items():
        assert '"#hbm:gun_mechanisms/%s"' % old in s, (recipe, old)
        s = s.replace('"#hbm:gun_mechanisms/%s"' % old, '"#hbm:gun_mechanisms/%s"' % new)
    return json.loads(s)


def conditioned(recipe, *conds):
    out = {'neoforge:conditions': [{'type': t, 'on': on} for t, on in conds]}
    out.update({k: v for k, v in recipe.items() if k != 'neoforge:conditions'})
    return out


def main():
    jar = zipfile.ZipFile(jar_path())
    tex = lambda n: decode(jar.read('assets/hbm/textures/item/%s.png' % n))[2]
    names = set(jar.namelist())

    # the mask: one for NTM's three mechanisms
    masks = {n: mask(tex('part_mechanism_' + n)) for n in NTM_MECHANISMS}
    m0 = masks[NTM_MECHANISMS[0]][0]
    for n, (m, pal) in masks.items():
        assert m == m0, 'the mechanisms of %s and %s differ in shape' % (NTM_MECHANISMS[0], n)
        assert len(pal) == 6, n
    counts = [sum(row.count(i) for row in m0) for i in range(6)]
    print('mask: 16x16, %d solid pixels; by shade (dark -> light): %s' % (sum(counts), counts))

    def material_palette(mat):
        pals = {}
        for p in PARTS:
            f = 'assets/hbm/textures/item/%s_%s.png' % (p, mat)
            if f in names: pals[p] = palette(decode(jar.read(f))[2])
        first = next(iter(pals.values()))
        assert all(v == first for v in pals.values()), (mat, pals)
        assert len(first) == 6, (mat, first)
        return first, sorted(pals)

    # the method checked on NTM's own: the receiver's palette on the mask gives NTM's mechanism pixel for pixel
    for n in NTM_MECHANISMS:
        pal, _ = material_palette(n)
        assert paint(m0, pal) == solid(tex('part_mechanism_' + n)), n
        print('check %-13s the parts\' palette on the mask = part_mechanism_%s of NTM, all 256 pixels' % (n, n))

    print('\nnew mechanisms, palette by shade (dark -> light), from the material part -> on the mechanism:')
    for mat, source in MATERIALS.items():
        pal, from_parts = material_palette(mat)
        assert palette(tex(source)) == pal
        img = paint(m0, pal)
        png = encode(img)
        back = mask(decode(png)[2])
        assert back[0] == m0 and back[1] == pal, mat
        write('assets/hbm/textures/item/part_mechanism_%s.png' % mat, png)
        write('assets/hbm/items/part_mechanism_%s.json' % mat, {'model': {'type': 'minecraft:model', 'model': 'hbm:item/part_mechanism_%s' % mat}})
        write('assets/hbm/models/item/part_mechanism_%s.json' % mat, {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'hbm:item/part_mechanism_%s' % mat}})
        write('data/hbm/tags/item/gun_mechanisms/%s.json' % mat, {'values': [{'id': 'hbm:part_mechanism_%s' % mat, 'required': False}]})
        used = [hexc(c) for c in palette(img)]
        print('  %-13s %s (%s, all %d parts alike) -> %s' % (mat, ' '.join(hexc(c) for c in pal), source, len(from_parts), ' '.join(used)))

    print('\nrecipes:')
    for name, change in RECIPES.items():
        ntm = json.loads(jar.read('data/hbm/recipe/%s.json' % name))
        if name in BALANCE:
            # the balance's recipe with NTM's mechanism, kept from the first run on
            keep = os.path.join(RES, 'data/ntmgunfeel/recipe/balance/%s.json' % name)
            src = keep if os.path.exists(keep) else os.path.join(RES, 'data/hbm/recipe/%s.json' % name)
            balance = json.load(open(src))
            to_balance = {old: BALANCE[name] for old in change}
            write('data/hbm/recipe/%s.json' % name, conditioned(swap(balance, to_balance), (BAL, True), (MECH, True)))
            write('data/ntmgunfeel/recipe/balance/%s.json' % name, conditioned(balance, (BAL, True), (MECH, False)))
            write('data/ntmgunfeel/recipe/mechanisms/%s.json' % name, conditioned(swap(ntm, change), (BAL, False), (MECH, True)))
            write('data/ntmgunfeel/recipe/ntm/%s.json' % name, conditioned(ntm, (BAL, False), (MECH, False)))
        else:
            write('data/hbm/recipe/%s.json' % name, conditioned(swap(ntm, change), (MECH, True)))
            write('data/ntmgunfeel/recipe/ntm/%s.json' % name, conditioned(ntm, (MECH, False)))
        print('  %-27s %s' % (name, ', '.join('%s -> %s' % kv for kv in change.items())))


if __name__ == '__main__':
    main()
