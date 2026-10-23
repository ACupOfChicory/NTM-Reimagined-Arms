#!/usr/bin/env python3
# The ammo menu numbers for every NTM gun and ammo, as a page to read without the game (review/ammo/page.html).
# Reads the gun dump (src/test/gundata, ./gradlew runDumpBlocks -PnoAssets) and the names from the NTM jar's
# ru_ru.json, works the numbers out as AmmoStats does, and the spread as NTM does with the mod's spread settings.
# Run from the repo root: python3 review/ammo/make_page.py
import csv, glob, json, math, re, zipfile

TSV = sorted(glob.glob('src/test/gundata/*.tsv'))[-1]
JAR = 'libs/mods/hbm-neoforge-1.0.3.jar'
REF_DT, REF_DR = 2.0, 0.15  # AmmoStats.REF_DT/DR: T-51


def props(path):
    out = {}
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            out[k.strip().replace('\\:', ':')] = v.strip()
    return out


SP = props('src/main/resources/ntmgunfeel-server.properties')


def through(amount, dt, dr, neg, pierce):
    dt = max(0.0, dt - neg)
    if dt >= amount:
        return 0.0
    dr *= min(2.0, max(0.0, 1.0 - pierce))
    return (amount - dt) * (1.0 - dr)


def projectiles(lo, hi, split):
    hi = max(hi, lo)
    return sum(int(k * split) for k in range(lo, hi + 1)) / (hi - lo + 1)


def deg(spread):
    # a shot leans off by spread × a normal random on each axis of the unit heading: the angle it leans by on one axis
    return math.degrees(math.atan(spread))


lang = json.loads(zipfile.ZipFile(JAR).read('assets/hbm/lang/ru_ru.json'))


def name(item_id):
    return lang.get('item.hbm.' + item_id.split(':', 1)[1], item_id)


guns = {}
for r in csv.DictReader(open(TSV, encoding='utf-8'), delimiter='\t'):
    f = {k: float(v) for k, v in r.items() if re.fullmatch(r'-?[\d.]+', v or '')}
    key = (r['gun'], r['config'], r['receiver'])
    g = guns.setdefault(key, {
        'id': r['gun'], 'name': name(r['gun']), 'part': r['config'] + '/' + r['receiver'],
        'auto': r['auto'] == 'true', 'delay': int(f['delayAfterFire']),
        'innate': f['spreadInnate'], 'hip': f['spreadHipfire'], 'ammoMult': f['spreadAmmoMult'], 'rows': []})
    dmg = f['baseDamage'] * f['damageMult']
    n = projectiles(int(f['projectilesMin']), int(f['projectilesMax']), f['split'])
    head = dmg * f['headshotMult'] if f['headshotMult'] > 1 else dmg
    g['rows'].append({
        'ammo': name(r['ammo']), 'id': r['ammo'], 'type': r['type'],
        'dmg': dmg, 'n': n, 'shot': dmg * n, 'head': head,
        'armor': through(dmg, REF_DT, REF_DR, f['thresholdNegation'], f['piercing']) * n,
        'neg': f['thresholdNegation'], 'pierce': f['piercing'],
        # the ammo's own spread goes in twice in NTM: in Lego.calcSpread (times the gun's ammo multiplier) and again in
        # the bullet's constructor; only the first is scaled by the shooter's state
        'aspread': f['ammoSpread'] * f['spreadAmmoMult'], 'aspread2': f['ammoSpread']})


def mult(gun, key):
    return float(SP.get(gun + '.' + key, SP.get(key, '1')))


out = []
for g in sorted(guns.values(), key=lambda g: (g['name'], g['part'])):
    m = {k: mult(g['id'], 'spread' + k) for k in ('Stand', 'Move', 'Sneak', 'Crawl', 'Aim')}
    g['mult'] = m
    out.append(g)

data = {'guns': out, 'ref': {'dt': REF_DT, 'dr': REF_DR}, 'source': TSV.split('/')[-1]}
html = open('review/ammo/template.html', encoding='utf-8').read()
html = html.replace('/*DATA*/null', json.dumps(data, ensure_ascii=False, separators=(',', ':')))
open('review/ammo/page.html', 'w', encoding='utf-8').write(html)
print(len(out), 'gun parts,', sum(len(g['rows']) for g in out), 'rows')
