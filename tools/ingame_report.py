#!/usr/bin/env python3
"""The test log (/gunfeel testlog) against review/INGAME-CHECKS.md.

    python3 tools/ingame_report.py <game>/logs/ntm-gunfeel-test-*.tsv [server/logs/ntm-gunfeel-test-*.tsv]

Give the client's file and, from a dedicated server, the server's too (in single player both sides are in one file).
Each point: OK, ПЛОХО (with the shots it went wrong on) or "нет данных" (what to do in game to get them). What only
eyes can tell (how holes, cracks and particles look, how a hit sounds) is listed at the end.
"""
import math
import sys
from collections import Counter, defaultdict


def parse(paths):
    events = []
    for path in paths:
        with open(path, encoding='utf-8') as f:
            for line in f:
                if line.startswith('#') or not line.strip():
                    continue
                parts = line.rstrip('\n').split('\t')
                if len(parts) < 4:
                    continue
                kv = {}
                for p in parts[4:]:
                    k, _, v = p.partition('=')
                    kv[k] = v
                events.append({'t': int(parts[0]), 'side': parts[1], 'shot': parts[2], 'ev': parts[3], **kv, 'file': path})
    return events


def vec(s):
    try:
        return tuple(float(x) for x in s.split(','))
    except (ValueError, AttributeError):
        return None


def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def yes(v):
    return v == 'true'


def num(v, d=float('nan')):
    try:
        return float(v)
    except (TypeError, ValueError):
        return d


class Report:
    def __init__(self):
        self.lines = []
        self.counts = Counter()

    def section(self, title):
        self.lines.append('')
        self.lines.append('## ' + title)

    def item(self, mark, text, details=()):
        sym = {'ok': 'OK    ', 'bad': 'ПЛОХО ', 'none': 'нет   ', 'info': '·     '}[mark]
        self.counts[mark] += 1
        self.lines.append(f'{sym}{text}')
        details = list(details)
        for d in details[:8]:
            self.lines.append('        ' + d)
        if len(details) > 8:
            self.lines.append(f'        … ещё {len(details) - 8}')

    def print(self):
        print('\n'.join(self.lines))
        print()
        print(f"Итого: OK {self.counts['ok']}, ПЛОХО {self.counts['bad']}, нет данных {self.counts['none']}")


FENCE_WORDS = ('fence', 'gate', 'wall')
NTM_WOOD = ('hbm:wood_barrier', 'hbm:wood_structure_ceiling', 'hbm:roof', 'hbm:scaffold')
NOT_GLASS = ('hbm:frozen_dirt', 'hbm:frozen_grass', 'hbm:block_fiberglass')


def main(paths):
    ev = parse(paths)
    r = Report()
    by = defaultdict(list)
    for e in ev:
        by[e['ev']].append(e)
    shots = defaultdict(lambda: {'C': [], 'S': []})
    for e in ev:
        if e['shot'] != '-':
            shots[e['shot']][e['side']].append(e)
    starts = [e for e in by['start'] if e['side'] == 'C']
    game = starts[-1]['game'] if starts else '?'
    sides = {e['side'] for e in ev}
    print(f"Файлы: {', '.join(paths)}")
    print(f"Игра: {game}; стороны в логе: {''.join(sorted(sides))}; выстрелов: {len(shots)}")
    if 'S' not in sides:
        print('Лога сервера нет: сверка клиента и сервера пропущена (нужен оп, или дай файл сервера вторым)')

    # ---------------------------------------------------------------- 1
    r.section('1. Настройки сервера у игроков')
    st = by['settings']
    errs = [e for e in st if 'error' in e]
    if errs:
        r.item('bad', 'Вход: клиент не прочитал настройки сервера', [e['error'] for e in errs])
    elif any(e['source'] == 'server' for e in st):
        last = [e for e in st if e['source'] == 'server'][-1]
        r.item('ok', f"Вход: настройки сервера прочитаны ({last['ruleLines']} строк правил, {last['serverKeys']} ключей, {last['shapes']} форм)")
    elif game in ('single', 'lan_host'):
        r.item('info', 'Вход на сервер: в этой игре сервер — она сама; проверяется на своём сервере или гостем LAN')
    else:
        r.item('none', 'Вход: нет строки settings; включи testlog до входа на сервер')
    received = [e for e in st if e['source'] == 'server']
    if len(received) > 1:
        r.item('ok', f'Правки на сервере дошли до игрока ещё {len(received) - 1} раз(а) без перезахода')
    elif game == 'server':
        r.item('none', 'Правка на сервере без перезахода: не было; поправь ntm-gunfeel-bullets.txt на сервере во время игры')
    left = [e for e in st if e.get('why') == 'left']
    if left:
        r.item('ok', 'Выход с сервера: снова свои правила')

    both = [(sid, s) for sid, s in shots.items() if s['C'] and s['S']]
    # when the server broke each block (a client that has not heard of it yet still sees the block)
    broke_at = defaultdict(list)
    for e in by['wear']:
        if e['result'] == 'broke':
            broke_at[e['pos']].append(e['t'])

    def why_differs(s, only_c, only_s):
        """Why the two sides saw a shot differently, when that is expected; None when it is not."""
        if any(e['ev'] == 'ricochet' and e['result'] == 'bounced' for e in s['S']):
            return 'рикошет'
        fired = min(e['t'] for e in s['S'])
        if any(fired - 2000 <= t <= fired + 200 for pos, _ in only_c + only_s for t in broke_at.get(pos, ())):
            return 'сервер только что сломал блок'
        ce = [e for e in s['C'] if e['ev'] == 'end']
        se = [e for e in s['S'] if e['ev'] == 'end']
        if ce and se and ce[-1]['by'] == se[-1]['by'] == 'entity':
            return None if dist(vec(ce[-1]['at']), vec(se[-1]['at'])) > 0.5 else 'попал в моба (строка блока за ним — до проверки мобов)'
        if ce and se and dist(vec(ce[-1]['at']), vec(se[-1]['at'])) < 0.05:
            return 'на границе блоков'
        return None

    mism, far, expected = [], [], Counter()
    for sid, s in both:
        cb = {(e['pos'], e['result']) for e in s['C'] if e['ev'] == 'block'}
        sb = {(e['pos'], e['result']) for e in s['S'] if e['ev'] == 'block'}
        cg = {e['pos'] for e in s['C'] if e['ev'] == 'gap'}
        sg = {e['pos'] for e in s['S'] if e['ev'] == 'gap'}
        ce = [e for e in s['C'] if e['ev'] == 'end' and e['by'] != 'life']
        se = [e for e in s['S'] if e['ev'] == 'end']
        gap = None
        if ce and se:
            a, b = vec(ce[-1]['at']), vec(se[-1]['at'])
            if a and b and dist(a, b) > 0.5:
                gap = dist(a, b)
        if cb == sb and cg == sg and gap is None:
            continue
        only_c = sorted(cb - sb) + [(p, 'gap') for p in sorted(cg - sg)]
        only_s = sorted(sb - cb) + [(p, 'gap') for p in sorted(sg - cg)]
        why = why_differs(s, only_c, only_s)
        if why:
            expected[why] += 1
            continue
        if cb != sb or cg != sg:
            mism.append(f'выстрел {sid}: только у клиента {only_c}, только на сервере {only_s}')
        if gap is not None:
            far.append(f"выстрел {sid}: трассер кончился в {ce[-1]['at']}, сервер — в {se[-1]['at']} ({gap:.2f} блока)")
    if both:
        r.item('bad' if mism else 'ok', f'Клиент и сервер видят одно и то же (пролёт, пробитие, стоп): {len(both) - len(mism)} из {len(both)}', mism)
        r.item('bad' if far else 'ok', f'bulletSpeed: трассер кончается там же, где сервер засчитал попадание: {len(both) - len(far)} из {len(both)}', far)
        if expected:
            r.item('info', 'Расхождения с понятной причиной (не считаются)', [f'{w}: {n}' for w, n in expected.most_common()])
        if expected.get('рикошет'):
            r.item('info', 'После рикошета сервер больше ничего не пишет, а трассер летит дальше: см. BUGS.md')
    else:
        r.item('none', 'Сверка клиента с сервером: нет выстрелов с обеих сторон')
    kicks = [e for e in by['recoil'] if e['phase'] == 'kick']
    held = [e for e in kicks if num(e['min'], 0) > 0]
    if held:
        bad = [f"scale {e['scale']} < min {e['min']}" for e in held if num(e['scale']) + 1e-3 < num(e['min'])]
        bad += [f"свои кривые при min {e['min']}" for e in held if yes(e['ownCurves'])]
        r.item('bad' if bad else 'ok', f'cameraScaleMin: отдача не слабее минимума сервера ({len(held)} выстрелов)', bad)
    else:
        r.item('none', 'cameraScaleMin: не было выстрелов при cameraScaleMin > 0 на сервере')
    reaim = by['reaim']
    changed = defaultdict(set)
    for e in reaim:
        changed[(e['gun'], e['kind'])].add(e['seconds'])
    moved = [f'{g} {k}: {sorted(v)}' for (g, k), v in changed.items() if len(v) > 1]
    if moved:
        r.item('ok', '/gunaim: время перезахода поменялось на лету', moved)
    else:
        r.item('none', '/gunaim: не менялось за время лога; поменяй и перезаряди ту же пушку')

    # ---------------------------------------------------------------- 2
    r.section('2. Заборы, калитки, стены')
    fam = defaultdict(Counter)
    hole_in_gap = []
    for sid, s in shots.items():
        c = s['C'] or s['S']
        gaps = {e['pos'] for e in c if e['ev'] == 'gap'}
        hit = {e['pos'] for e in c if e['ev'] == 'block'}
        for e in c:
            if e['ev'] in ('gap', 'block') and any(w in e['block'] for w in FENCE_WORDS):
                fam[e['block']][e['ev'] if e['ev'] == 'gap' else e['result']] += 1
        for e in s['C']:
            if e['ev'] == 'hole' and yes(e['placed']) and e['pos'] in gaps and e['pos'] not in hit:
                hole_in_gap.append(f"выстрел {sid}: дырка на {e['block']} {e['pos']}, хотя пуля прошла щель")
    if fam:
        r.item('info', 'Что пули делали с заборами (щель / пробил / остановилась)',
               [f"{b}: щель {c['gap']}, пробил {c['pierce']}, стоп {c['stop']}" for b, c in sorted(fam.items())])
        r.item('bad' if hole_in_gap else 'ok', 'Дырки только там, где пуля попала, не в щелях', hole_in_gap)
    hit_blocks = {e['block'] for e in ev if e['ev'] in ('gap', 'block', 'hole')}
    for need, name in (('fence_gate', 'калитке'), ('_wall', 'стене'), ('bamboo', 'бамбуковому забору'),
                       ('hbm:steel_grate', 'сетке NTM'), ('copper_grate', 'медной решётке')):
        if not any(need in b for b in hit_blocks):
            r.item('none', f'Не стрелял по {name} ({need})')

    # ---------------------------------------------------------------- 3
    r.section('3. Стекло и дырки')
    holes = [e for e in by['hole']]
    glass_wrong = [f"{e['block']}: дырка как на стекле" for e in holes if e['block'] in NOT_GLASS and yes(e['glass'])]
    seen_not_glass = {e['block'] for e in holes if e['block'] in NOT_GLASS}
    if seen_not_glass:
        r.item('bad' if glass_wrong else 'ok', f"Не стекло: {', '.join(sorted(seen_not_glass))}", sorted(set(glass_wrong)))
    else:
        r.item('none', 'Не стрелял по hbm:frozen_dirt, frozen_grass, block_fiberglass')
    glass_breaks = [e for e in by['wear'] if e['result'] == 'broke' and ('glass' in e['block'] or 'pane' in e['block'])]
    if glass_breaks:
        r.item('ok', 'Стекло ломается от пуль', sorted({e['block'] for e in glass_breaks}))
    else:
        r.item('none', 'Стекло не ломалось: постреляй в обычное, цветное стекло и панели')
    ntm_glass = Counter(e['block'] for e in by['wear'] if e['block'].startswith('hbm:') and 'glass' in e['block'])
    if ntm_glass:
        r.item('info', 'Стекло NTM: попаданий до поломки / износ', [f'{b}: {n} событий' for b, n in ntm_glass.items()])
    else:
        r.item('none', 'Стекло NTM (кварцевое, борное, армированное, ламинат): не стрелял')

    # ---------------------------------------------------------------- 4
    r.section('4. Правила path и hole')
    styles = defaultdict(Counter)
    for e in holes:
        styles[e['block']][f"{e['drawn']}/{e['how']}"] += 1
    ruled = {b: c for b, c in styles.items() if any(not k.startswith('-/-') and not k.startswith('null') for k in c)}
    if ruled:
        r.item('info', 'Блоки со своими правилами hole (стиль / как)', [f'{b}: {dict(c)}' for b, c in sorted(ruled.items())])
    else:
        r.item('none', 'Дырок на блоках с правилами hole не было')

    # ---------------------------------------------------------------- 5
    r.section('5. Материалы')
    sounds = defaultdict(Counter)
    for e in by['fx']:
        sounds[e['material']][e['sound']] += 1
    if sounds:
        r.item('info', 'Звуки попаданий по материалам', [f'{m}: {dict(c)}' for m, c in sorted(sounds.items())])
    else:
        r.item('none', 'Попаданий с эффектами не было')
    ric = by['ricochet']
    ric_glass = [f"выстрел {e['shot']}: рикошет от {e['block']}" for e in ric if e.get('material') == 'glass']
    if ric:
        r.item('bad' if ric_glass else 'ok', f'Рикошеты: {len(ric)}, от стекла нет', ric_glass)
    else:
        r.item('none', 'Рикошетов не было: стреляй вскользь по камню или металлу')
    wood = [e for e in holes if e['block'] in NTM_WOOD and yes(e['placed'])]
    off_board = [f"выстрел {e['shot']}: {e['block']} {e['at']}" for e in wood if e['onBoard'] == 'false']
    if wood:
        r.item('bad' if off_board else 'ok', f'Окопы NTM: дырки только на досках ({len(wood)} дырок)', off_board)
    else:
        r.item('none', 'Окопы NTM (перекрытие, крыша, леса, барьер): не стрелял')
    fire_gun = {e['shot']: e.get('gun', '-') for e in by['fire'] if e['side'] == 'S'}
    bags = defaultdict(list)
    for e in by['wear']:
        if e['block'] == 'hbm:sandbags':
            bags[e['pos']].append(e)
    if bags:
        det = []
        for pos, es in bags.items():
            hits = [e for e in es if e['result'] in ('wear', 'broke')]
            weak = sum(1 for e in es if e['result'] == 'too_weak')
            guns = Counter(fire_gun.get(e['shot'], '-') for e in es)
            broke = any(e['result'] == 'broke' for e in es)
            det.append(f"{pos}: {'порван' if broke else 'цел'} после {len(hits)} засчитанных, {weak} слабых; {dict(guns)}")
        r.item('info', 'Мешки с песком (ожидание: винтовка ~18 попаданий, пулемёт почти не берёт)', det)
    else:
        r.item('none', 'По мешкам с песком не стрелял')
    # bulletBreakConcrete as the side had it at the moment: the last "rules" line before (the start line before any)
    concrete_now = {}
    concrete_bad, concrete_ok, concrete_allowed = [], 0, 0
    for e in ev:
        if e['ev'] == 'start' and 'bulletBreakConcrete' in e:
            concrete_now.setdefault(e['side'], yes(e['bulletBreakConcrete']))
        elif e['ev'] == 'rules':
            concrete_now[e['side']] = yes(e['concrete'])
        elif e['ev'] == 'wear' and e['result'] == 'broke' and 'concrete' in e['block']:
            if concrete_now.get(e['side'], False):
                concrete_allowed += 1
            else:
                concrete_bad.append(f"выстрел {e['shot']}: {e['block']} {e['pos']}")
        elif e['ev'] == 'block' and 'concrete' in e['block'] and not concrete_now.get(e['side'], False):
            concrete_ok += 1
    if concrete_bad:
        r.item('bad', 'Бетон сломался при bulletBreakConcrete = false', concrete_bad)
    elif concrete_ok or concrete_allowed:
        r.item('ok', f'Бетон держит ({concrete_ok} попаданий)' + (f'; при bulletBreakConcrete = true сломан {concrete_allowed} раз' if concrete_allowed else ''))
    else:
        r.item('none', 'По бетону не стрелял')
    pierce = defaultdict(list)
    for e in by['block']:
        if e['side'] == 'S' or 'S' not in sides:
            pierce[e['material']].append(e)
    if pierce:
        det = []
        for m, es in sorted(pierce.items()):
            p = [e for e in es if e['result'] == 'pierce']
            loss = [1 - num(e['left']) / num(e['damage']) for e in p if num(e['damage']) > 0]
            det.append(f"{m}: пробил {len(p)} из {len(es)}" + (f", теряет в среднем {100 * sum(loss) / len(loss):.0f}% урона" if loss else ''))
        r.item('info', 'Пробитие по материалам (сталь и бетон должны держать)', det)
        for m in ('steel', 'concrete'):
            # a path of a few hundredths: the bullet cut a corner into the next block, it did not go through
            through = [e for e in pierce.get(m, []) if e['result'] == 'pierce' and num(e['path'], 1) >= 0.1]
            corners = [e for e in pierce.get(m, []) if e['result'] == 'pierce' and num(e['path'], 1) < 0.1]
            if corners:
                r.item('info', f'{m}: задет угол {len(corners)} раз (путь < 0.1 блока, не пробитие)')
            if through:
                r.item('bad', f'{m}: пробит {len(through)} раз', [f"выстрел {e['shot']}: {e['block']} {e['damage']}→{e['left']}" for e in through])

    # ---------------------------------------------------------------- 6
    r.section('6. Остальное')
    ammo = by['ammo']
    if ammo:
        r.item('info', 'Меню патронов (сверь с экраном)', sorted({f"{e['gun']} патрон {e['ammo']}: урон {e['damage']} ×{e['projectiles']}, "
                                                                 f"голова {e['head']}, T-51 {e['armored']}" for e in ammo}))
    else:
        r.item('none', 'Меню патронов не открывалось')
    spread = [e for e in by['spread'] if e['side'] == 'S' or 'S' not in sides]
    if spread:
        groups = defaultdict(list)
        for e in spread:
            state = 'ползком' if yes(e['crawl']) else 'присед' if yes(e['sneak']) else 'в воздухе' if yes(e['air']) else \
                'бег' if yes(e['sprint']) else 'ходьба' if num(e['pace'], 0) > 0 else 'стоя'
            if yes(e['aim']):
                state += ' + прицел'
            groups[state].append(num(e['mult']))
        det = [f'{s}: ×{sum(v) / len(v):.2f} к разбросу NTM ({len(v)} выстр.)' for s, v in sorted(groups.items())]
        zero = sorted({e['gun'] for e in spread if num(e['base'], 1) == 0})
        if zero:
            det.append('у NTM разброс 0 (множитель ни на что не влияет): ' + ', '.join(zero))
        missing = [s for s in ('стоя', 'ходьба', 'бег', 'в воздухе', 'присед', 'ползком') if not any(k.startswith(s) for k in groups)]
        r.item('info', 'Живой разброс по состояниям', det)
        if missing:
            r.item('none', 'Разброс: не стрелял ' + ', '.join(missing))
        bloom = [num(e['bloom'], 0) for e in spread]
        r.item('ok' if max(bloom) > 0 else 'none', f'Длинная очередь: раскачка до {max(bloom):.3f}')
    else:
        r.item('none', 'Разброс: нет строк spread (нужен лог сервера)')
    q = by['quality']
    if len({e['preset'] for e in q}) > 1:
        r.item('ok', 'Пресеты качества переключались', [f"{e['preset']}: дырок {e['holeMax']}/{e['holeMaxMachines']}, жизнь {e['holeLife']} с, "
                                                       f"частицы {e['particleScale']}, руками: {e['byHand'] or 'нет'}" for e in q])
    else:
        r.item('none', '/gunfeel quality: не переключал')
    # the limits count hits (one hit on a model leaves a hole per part), the log counts holes: only the kind is checked
    wrong_kind = sorted({e['block'] for e in holes if yes(e['placed']) and yes(e['machine']) and e['block'].startswith('minecraft:')})
    if holes:
        r.item('bad' if wrong_kind else 'ok', 'Дырки на обычных блоках считаются по holeMax, а не по holeMaxMachines',
               ['под лимитом машин: ' + ', '.join(wrong_kind[:12])] if wrong_kind else [])
    back = [e for e in by['recoil'] if e['phase'] == 'back' and yes(e['stopped'])]
    if back:
        off = [f"целился {e['from']}, взгляд вернулся на {e['to']}" for e in back if abs(num(e['from']) - num(e['to'])) > 1.0]
        r.item('bad' if off else 'ok', f'Отдача у края обзора: взгляд вернулся туда, куда целился ({len(back)} очередей; мышь не трогать)', off)
    else:
        r.item('none', 'Отдача у края обзора: не стрелял, глядя прямо вверх или вниз')
    hello = by['hello']
    if starts:
        s0 = starts[-1]
        r.item('info', f"Версии: мод {s0['mod']}, NTM {s0['ntm']} (проверено {s0['ntmTested']}), выключено: {s0['off']}")
    if hello:
        h = hello[-1]
        r.item('ok' if yes(h['sameMod']) else 'info', f"Мод на сервере {h['serverMod']}, у тебя {h['mod']}" + ('' if yes(h['sameMod']) else ': должно быть сообщение в чате'))

    r.section('Только глазами (лог этого не скажет)')
    for t in ('дырка на стекле видна с обеих сторон и поверх стекла за ним, на панели тоже',
              'дырки на сетке NTM и медной решётке — по прутьям и пикселям',
              'трещины и осколки стекла NTM, порванные мешки',
              'звук и частицы похожи на материал (дерево, камень, металл, стекло)',
              'отдача с cameraScaleMin ощущается как 0.8, тряска своя',
              'трассер в щели между досками летит в щель',
              'меню патронов читается, числа совпадают со строками выше'):
        r.lines.append('  [ ] ' + t)
    r.print()


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
