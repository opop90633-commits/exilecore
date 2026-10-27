#!/usr/bin/env python3
"""把 PoE2 0.5 的天賦樹（PoB-PoE2 TreeData/0_5 + RePoE poe2 0.5.5.4.2）轉成 src/main/resources/data/passives/tree.json。

輸入（tools/poe2_tree/）：
  poe2_tree.json   節點、連線、PoE2 座標與格子座標（160 單位一格）
  stat_map.json    PoE2 stat id → 引擎的屬性鍵（status = mapped/approx 才會生效，其他只顯示說明、標「尚未生效」）
                   2026-10-26：status = conditional 但寫了 condition（與 suggest）的也生效，tree.json 的 stat 多一個 "condition"
                   （引擎的 TreeConditions：stationary、surrounded、lowlife、hit、vs_blinded…）；條件還沒有系統支撐的（UNSUPPORTED_CONDITIONS）不生效
  overrides.json   手寫的對照（基石、特殊的 stat），蓋過 stat_map
  names_zh.json    節點名稱的中文（照台服 poe2db.tw；沒有的用英文）
  templates_zh.json 說明行的中文樣板（數字換成 #）

輸出：tree.json（version 4），每個節點有 links（明確的連線），wires 是不相鄰的連線在格子上走的路（畫連線用）。
"""
import heapq
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, 'poe2_tree')
OUT = os.path.join(HERE, '..', 'src', 'main', 'resources', 'data', 'passives', 'tree.json')
VERSION = 6  # 2026-10-26：拿掉連枷 10 個節點（第 5 版）；加回觀星專屬等 178 個有解鎖條件的節點（第 6 版）

# 我們的職業 → PoE2 起點（共用起點的職業：遊俠／女獵人、女巫／女術士）
STARTS = {
    'marauder': 47175,   # Warrior
    'mercenary': 50986,
    'monk': 44683,
    'ranger': 50459,
    'huntress': 50459,
    'sorceress': 54447,
    'witch': 54447,
    'druid': 61525,
}
START_OWNER = {47175: 'marauder', 50986: 'mercenary', 44683: 'monk', 50459: 'ranger', 54447: 'sorceress', 61525: 'druid'}
# PoE2 換職業會換掉的節點（variants）：Witch／Druid／Huntress 拿到別的效果
VARIANT_CLASS = {'Witch': 'witch', 'Druid': 'druid', 'Huntress': 'huntress'}
# 2026-10-26：原作 unlock_constraint 的昇華 → 配了才解鎖的昇華屬性（見 AscKeys.UNSEEN_PATH）
UNLOCK_ASCENDANCY = {'Oracle': 'asc.unseen_path'}
SHOW_ASCENDANCY = {'Oracle': 'starseer'}   # ascendancy.yml 的昇華代號

INACTIVE = '（尚未生效）'
# 2026-10-27：只有外觀的 stat（方塊世界做不出來，照原作寫、標明只有外觀；沒有修飾器，也不算「尚未生效」）
# 2026-10-27：照亮範圍改成遠距離命中懲罰（overrides.json），不再是只有外觀
COSMETIC = {}
# 2026-10-27：只有說明文字、其他節點沒有同樣說明行的（變體節點）：樣板 → stat id（值照文字裡的數字）
TEXT_STAT_IDS = {'Gain #% of Damage as Extra Damage of a random Element': 'non_skill_base_all_damage_%_to_gain_as_random_element'}
# 2026-10-26：引擎還沒有的條件（寫在這裡的不生效）；變身、夥伴已經在 ConditionTracker 接上，拿掉了
UNSUPPORTED_CONDITIONS = set()


def load(name, default=None):
    path = os.path.join(DATA, name)
    if not os.path.exists(path):
        return default
    with open(path, encoding='utf-8') as f:
        return json.load(f)


TREE = load('poe2_tree.json')
STAT_MAP = load('stat_map.json')
OVERRIDES = load('overrides.json', {})
NAMES_ZH = load('names_zh.json', {})
TEMPLATES_ZH = load('templates_zh.json', {})

NUMBER = re.compile(r'\d+(?:\.\d+)?')
MARKUP = re.compile(r'\[([^\]|]*\|)?([^\]]*)\]')


def clean(text):
    return MARKUP.sub(r'\2', text)


def zh_line(line):
    template = NUMBER.sub('#', line)
    zh = TEMPLATES_ZH.get(template)
    if not zh:
        return line
    numbers = NUMBER.findall(line)
    parts = zh.split('#')
    if len(parts) - 1 != len(numbers):
        return line
    out = parts[0]
    for number, part in zip(numbers, parts[1:]):
        out += number + part
    return out


COST_EFFICIENCY_KEYS = {'skill.cost_efficiency', 'skill.mana_cost_efficiency', 'skill.life_cost_efficiency'}


def zh_name(name):
    return NAMES_ZH.get(name) or name


def convert(stat_id, value):
    """一個 PoE2 stat → 引擎修飾器的清單；不能生效回傳 None。"""
    if stat_id in OVERRIDES:
        rule = OVERRIDES[stat_id]
        if rule is None:
            return None
        if any(m.get('condition') in UNSUPPORTED_CONDITIONS for m in rule):
            return None
        return [with_condition(dict(stat=m['stat'], type=m['type'], value=(m['value'] if 'value' in m else scale(m['type'], value * m.get('scale', 1))),
                                    tags=m.get('tags', [])), m.get('condition')) for m in rule]
    entry = STAT_MAP.get(stat_id)
    if entry is None or not entry['suggest']:
        return None
    condition = entry.get('condition')
    if entry['status'] == 'conditional':
        if not condition or condition in UNSUPPORTED_CONDITIONS:
            return None
    elif entry['status'] not in ('mapped', 'approx'):
        return None
    note = entry['note'] or ''
    v = value
    if 'negate' in note or 'efficiency' in note:
        v = -v
    if 'per minute' in note:
        v = v / 60.0
    if 'permyriad' in note:
        v = v / 100.0
    result = []
    for s in entry['suggest']:
        if s['stat'].startswith('flag.'):
            result.append(with_condition(dict(stat=s['stat'], type='FLAT', value=1, tags=s['tags']), condition))
            continue
        # 2026-10-27 照 PoE2：消耗效率有自己的屬性鍵（CostKeys：消耗 ÷ (1 + 效率)），數值照原作不反過來；
        # 這次之前效率一律反過來寫成 skill.cost 的減少
        sv = value if s['stat'] in COST_EFFICIENCY_KEYS else v
        result.append(with_condition(dict(stat=s['stat'], type=s['type'], value=scale(s['type'], sv), tags=s['tags']), condition))
    return result


def with_condition(mod, condition):
    """有條件的修飾器多一個 condition 欄位（引擎的 TreeConditions）。"""
    if condition:
        mod['condition'] = condition
    return mod


def scale(kind, v):
    if kind in ('INCREASED', 'MORE'):
        return round(v / 100.0, 6)
    return round(v, 6) if isinstance(v, float) else v


ICONS = [
    # (關鍵字（圖示路徑或英文名稱，小寫）, 原版材質)
    ('jewel', 'AMETHYST_SHARD'), ('minion', 'BONE'), ('companion', 'WOLF_SPAWN_EGG'), ('totem', 'TOTEM_OF_UNDYING'),
    ('fire', 'BLAZE_POWDER'), ('ignite', 'BLAZE_POWDER'), ('burn', 'BLAZE_POWDER'), ('cold', 'BLUE_ICE'), ('freez', 'BLUE_ICE'),
    ('chill', 'SNOWBALL'), ('ice', 'BLUE_ICE'), ('lightning', 'LIGHTNING_ROD'), ('shock', 'LIGHTNING_ROD'), ('electro', 'LIGHTNING_ROD'),
    ('chaos', 'WITHER_ROSE'), ('poison', 'SPIDER_EYE'), ('bleed', 'REDSTONE'), ('blood', 'REDSTONE'), ('curse', 'FERMENTED_SPIDER_EYE'),
    ('crossbow', 'CROSSBOW'), ('bow', 'BOW'), ('arrow', 'ARROW'), ('spear', 'IRON_SPEAR'), ('quarterstaff', 'BAMBOO'), ('staff', 'BAMBOO'),
    ('mace', 'MACE'), ('axe', 'IRON_AXE'), ('sword', 'IRON_SWORD'), ('dagger', 'IRON_SWORD'), ('flail', 'CHAIN'), ('shield', 'SHIELD'),
    ('block', 'SHIELD'), ('armour', 'IRON_CHESTPLATE'), ('evasion', 'FEATHER'), ('deflect', 'FEATHER'), ('energyshield', 'HEART_OF_THE_SEA'),
    ('energy shield', 'HEART_OF_THE_SEA'), ('mana', 'LAPIS_LAZULI'), ('life', 'GLISTERING_MELON_SLICE'), ('flask', 'POTION'),
    ('charm', 'POTION'), ('crit', 'TARGET'), ('accuracy', 'TARGET'), ('projectile', 'ARROW'), ('stun', 'ANVIL'), ('warcry', 'GOAT_HORN'),
    ('banner', 'WHITE_BANNER'), ('shapeshift', 'WOLF_SPAWN_EGG'), ('bear', 'WOLF_SPAWN_EGG'), ('wyvern', 'PHANTOM_MEMBRANE'),
    ('grenade', 'TNT'), ('trap', 'TRIPWIRE_HOOK'), ('herald', 'BELL'), ('aura', 'BEACON'), ('spirit', 'BEACON'), ('charge', 'GLOWSTONE_DUST'),
    ('speed', 'SUGAR'), ('area', 'FIREWORK_STAR'), ('spell', 'ENCHANTED_BOOK'), ('attack', 'GOLDEN_SWORD'), ('damage', 'GOLDEN_SWORD'),
]


def icon_for(node):
    kind = node['type']
    if kind == 'keystone':
        return 'NETHER_STAR'
    if kind == 'jewel_socket':
        return 'AMETHYST_SHARD'
    if kind == 'class_start':
        return 'BEACON'
    if kind in ('small', 'attribute'):
        return 'GRAY_STAINED_GLASS_PANE'
    hay = (node.get('icon', '') + ' ' + node.get('name', '') + ' ' + ' '.join(node_lines(node))).lower()
    for key, material in ICONS:
        if key in hay:
            return material
    return 'ENCHANTED_BOOK'


def node_lines(node):
    lines = [l['text'] for l in node.get('lines', []) if not l.get('hidden') and l.get('text')]
    if not lines:
        lines = [clean(x) for x in node.get('text') or []]
    return lines


# 只有說明文字的 stat（PoB 的 stats 是文字）：用其他節點同樣的說明行找回 stat id
# 樣板 → (stat id, 每個 stat 的值 = 文字裡第幾個數字 × 倍率)；倍率從原本的節點學（「減少」是負的、每分鐘的要乘 60）
TEMPLATE_STATS = {}
for _n in TREE['nodes']:
    for _l in _n.get('lines', []):
        if not (_l.get('stat_ids') and _l.get('text')):
            continue
        _numbers = [float(x) for x in NUMBER.findall(_l['text'])]
        _rule = []
        for _v in _l['values']:
            _hit = next((k for k, x in enumerate(_numbers) if x and abs(abs(_v) / x - round(abs(_v) / x)) < 1e-9 and round(abs(_v) / x) in (1, 60, 100)), None)
            if _hit is None:
                _rule = None
                break
            _rule.append((_hit, _v / _numbers[_hit]))
        if _rule is not None:
            TEMPLATE_STATS.setdefault(NUMBER.sub('#', _l['text']), (_l['stat_ids'], _rule))


def stats_and_description(lines):
    """lines：[{text, stat_ids, values}] → (修飾器, 說明行)。"""
    stats = []
    description = []
    for line in lines:
        if line.get('hidden') or not line.get('text'):
            continue
        text = line['text']
        mods = []
        active = bool(line.get('stat_ids'))
        cosmetic = next((COSMETIC[i] for i in line.get('stat_ids', []) if i in COSMETIC), None)
        if cosmetic:
            description.append(zh_line(text) + cosmetic)
            continue
        for stat_id, value in zip(line.get('stat_ids', []), line.get('values', [])):
            converted = convert(stat_id, value)
            if converted is None:
                active = False
                break
            mods.extend(converted)
        whole = zh_line(text)
        replaced = replaced_line(line, mods) if active else None
        if replaced:
            description.append(replaced)
            stats.extend(mods)
            continue
        parts = whole.split('\n') if whole != text else [zh_line(part) for part in text.split('\n')]
        for part in parts:
            description.append(part + ('' if active else INACTIVE))
        if active:
            stats.extend(mods)
    return stats, description


def number(v):
    return ('%.6f' % v).rstrip('0').rstrip('.')


def replaced_line(line, mods):
    """2026-10-27：原作的效果在方塊世界沒有作用、換成別的效果的 stat（stat_map 的 zh：{pos, neg} 依原作數值正負選），說明行照換過的寫。
    {v} = 原作數值（絕對值），{x} = 換過的修飾器數值（INCREASED／MORE 換回百分比，絕對值）。"""
    ids = line.get('stat_ids', [])
    if len(ids) != 1 or not mods:
        return None
    zh = (STAT_MAP.get(ids[0]) or {}).get('zh')
    if not zh:
        return None
    value = (line.get('values') or [0])[0]
    template = zh.get('pos' if value >= 0 else 'neg') if isinstance(zh, dict) else zh
    if not template:
        return None
    mod = mods[0]
    x = abs(mod['value']) * (100 if mod['type'] in ('INCREASED', 'MORE') else 1)
    return template.replace('{v}', number(abs(value))).replace('{x}', number(x))


def text_lines(texts):
    """只有文字（變體節點）：用樣板找回 stat id，值照文字裡的數字。"""
    lines = []
    for text in texts:
        text = clean(text)
        found = TEMPLATE_STATS.get(NUMBER.sub('#', text))
        numbers = [float(x) for x in NUMBER.findall(text)]
        if found and all(k < len(numbers) for k, _ in found[1]):
            values = [round(numbers[k] * ratio, 6) for k, ratio in found[1]]
            lines.append({'text': text, 'stat_ids': found[0], 'values': values})
        elif NUMBER.sub('#', text) in TEXT_STAT_IDS and len(numbers) == 1:
            lines.append({'text': text, 'stat_ids': [TEXT_STAT_IDS[NUMBER.sub('#', text)]], 'values': numbers})
        else:
            lines.append({'text': text, 'stat_ids': [], 'values': []})
    return lines


def route(start, goal, occupied, used, bounds):
    """格子上從 start 走到 goal（四方向），盡量不穿過別的節點、不跟別的線重疊、少轉彎。回傳中間的格子。"""
    (sx, sy), (gx, gy) = start, goal
    margin = 3
    minx, maxx = min(sx, gx) - margin, max(sx, gx) + margin
    miny, maxy = min(sy, gy) - margin, max(sy, gy) + margin
    heap = [(abs(sx - gx) + abs(sy - gy), 0.0, sx, sy, -1)]
    best = {(sx, sy, -1): 0.0}
    parent = {}
    dirs = ((1, 0), (-1, 0), (0, 1), (0, -1))
    while heap:
        f, g, x, y, d = heapq.heappop(heap)
        if (x, y) == (gx, gy):
            path = []
            state = (x, y, d)
            while state in parent:
                state = parent[state]
                if (state[0], state[1]) != (sx, sy):
                    path.append((state[0], state[1]))
            path.reverse()
            return path
        if g > best.get((x, y, d), 1e18):
            continue
        for i, (dx, dy) in enumerate(dirs):
            nx, ny = x + dx, y + dy
            if nx < minx or nx > maxx or ny < miny or ny > maxy:
                continue
            cost = 1.0
            if (nx, ny) != (gx, gy):
                if (nx, ny) in occupied:
                    cost += 40
                cost += 0.6 * used.get((nx, ny), 0)
            if d != -1 and d != i:
                cost += 0.25
            ng = g + cost
            key = (nx, ny, i)
            if ng < best.get(key, 1e18):
                best[key] = ng
                parent[key] = (x, y, d)
                heapq.heappush(heap, (ng + abs(nx - gx) + abs(ny - gy), ng, nx, ny, i))
    return None


def main():
    raw = {n['id']: n for n in TREE['nodes']}
    # 2026-10-26 照 PoE2：有 unlock_constraint 的節點（觀星的「未見之道」節點、兩個要先配某些核心的）也放進來，
    # 寫 unlock 欄位（引擎在 PassiveTree 檢查）；這次之前整批排除
    excluded = set()
    # 2026-10-26 照站長：連枷（PoE2 0.5 還拿不到）那一整串 10 個節點拿掉
    flail = {i for i, n in raw.items() if any(any('flail' in s for s in (l.get('stat_ids') or [])) for l in n.get('lines', []))}
    excluded |= flail
    edges = set()
    for n in raw.values():
        if n['id'] in excluded:
            continue
        for other in n.get('out', []) + n.get('in', []):
            if other in raw and other not in excluded and other != n['id']:
                edges.add((min(n['id'], other), max(n['id'], other)))
    # 連不到任何起點的節點（例如 Sinister 珠寶孔、只能靠暗金拿到的核心）不放
    adjacency = {}
    for a, b in edges:
        adjacency.setdefault(a, []).append(b)
        adjacency.setdefault(b, []).append(a)
    reach = set(START_OWNER)
    queue = list(START_OWNER)
    while queue:
        cur = queue.pop()
        for nxt in adjacency.get(cur, []):
            if nxt not in reach:
                reach.add(nxt)
                queue.append(nxt)
    # 2026-10-27 照 PoE2 0.5：樹上連不到的核心（連枷的除外）是只能靠注入拿到的核心（塗油限定），另外放在 tree.json 的 anoint
    anoint_ids = sorted(i for i, n in raw.items() if n['type'] == 'notable' and i not in reach and i not in excluded)
    # 2026-10-26：有 unlock 的節點排在最後（規劃代碼照檔案順序記位元，舊代碼的位置不變）
    ids = sorted((i for i in raw if i in reach), key=lambda i: (bool(raw[i].get('unlock_constraint')), i))
    edges = {(a, b) for a, b in edges if a in reach and b in reach}
    # PoE2 的起點彼此不相連（起點不能當路），連到起點的線只留起點出去的
    pos = {i: tuple(raw[i]['grid']) for i in ids}
    occupied = set(pos.values())
    assert len(occupied) == len(ids), '格子座標重複'

    nodes = []
    for i in ids:
        n = raw[i]
        kind = n['type']
        out = {'id': 'p%d' % i, 'x': pos[i][0], 'y': pos[i][1], 'px': round(n['x']), 'py': round(n['y'])}
        if kind == 'class_start':
            out['type'] = 'start'
            out['name'] = '起點'
            out['start'] = START_OWNER[i]
            out['stats'] = []
            out['description'] = []
        else:
            out['type'] = {'small': 'small', 'attribute': 'small', 'notable': 'notable', 'keystone': 'keystone',
                           'jewel_socket': 'socket'}[kind]
            if kind == 'attribute':
                out['name'] = '屬性'
                out['stats'] = [{'stat': 'attr.choice', 'type': 'FLAT', 'value': 5, 'tags': []}]
                out['description'] = ['+5 任一屬性（配了之後左鍵切換力量／敏捷／智慧）']
            elif kind == 'jewel_socket':
                out['name'] = '珠寶孔'
                out['stats'] = []
                out['description'] = ['可以放一顆珠寶']
            else:
                out['name'] = zh_name(n['name'])
                stats, description = stats_and_description(n.get('lines') or text_lines(n.get('text') or []))
                out['stats'] = stats
                out['description'] = description
        constraint = n.get('unlock_constraint')
        if constraint:
            if constraint.get('ascendancy'):
                # 2026-10-26 照站長：ascendancy＝哪個昇華才顯示（別的角色的天賦樹、規劃器不畫這些節點）
                out['unlock'] = {'stat': UNLOCK_ASCENDANCY[constraint['ascendancy']], 'ascendancy': SHOW_ASCENDANCY[constraint['ascendancy']]}
                out['description'] = out['description'] + ['未見之道：觀星配了「未見之道」才能配']
            else:
                out['unlock'] = {'nodes': ['p%d' % k for k in constraint['nodes']]}
                out['description'] = out['description'] + ['要先配：' + '或'.join(
                    '「' + zh_name(raw[k]['name']) + '」' for k in constraint['nodes'])]
        out['icon'] = icon_for(n)
        out['en'] = n['name']
        if n.get('variants'):
            variants = {}
            for cls, v in n['variants'].items():
                key = VARIANT_CLASS.get(cls)
                if key is None:
                    continue
                stats, description = stats_and_description(text_lines(v.get('stats') or []))
                variants[key] = {'name': zh_name(v.get('name', n['name'])), 'en': v.get('name', n['name']), 'stats': stats,
                                 'description': description}
            if variants:
                out['variants'] = variants
        nodes.append(out)

    index = {i: k for k, i in enumerate(ids)}
    links = {i: [] for i in ids}
    for a, b in sorted(edges):
        links[a].append(b)
    for out, i in zip(nodes, ids):
        out['links'] = ['p%d' % b for b in links[i]]

    # 不相鄰的連線：在格子上找一條路（畫線用）；短的先排，長的繞開
    wires = []
    used = {}
    for a, b in sorted(edges, key=lambda e: abs(pos[e[0]][0] - pos[e[1]][0]) + abs(pos[e[0]][1] - pos[e[1]][1])):
        pa, pb = pos[a], pos[b]
        if abs(pa[0] - pb[0]) + abs(pa[1] - pb[1]) == 1:
            continue
        path = route(pa, pb, occupied, used, None)
        if path is None:
            print('找不到路：', a, b, file=sys.stderr)
            continue
        for cell in path:
            used[cell] = used.get(cell, 0) + 1
        wires.append({'a': 'p%d' % a, 'b': 'p%d' % b, 'cells': [list(c) for c in path]})

    # 2026-10-27 照 PoE2 0.5：塗油限定的核心——不在樹上（沒有連線、不能配點、天賦樹不畫），只能注入在項鍊上；
    # x／y 是原作的格子座標（不跟樹上的節點共用格子，只給注入選單排序用），配方在 data/passives/anoint_only.yml
    anoint = []
    for i in anoint_ids:
        n = raw[i]
        stats, description = stats_and_description(n.get('lines') or text_lines(n.get('text') or []))
        anoint.append({'id': 'p%d' % i, 'x': n['grid'][0], 'y': n['grid'][1], 'px': round(n['x']), 'py': round(n['y']),
                       'type': 'notable', 'name': zh_name(n['name']), 'stats': stats, 'description': description,
                       'icon': icon_for(n), 'en': n['name'], 'anointOnly': True})

    starts = {cls: 'p%d' % node for cls, node in STARTS.items()}
    root = {
        'version': VERSION,
        'unit': 160,
        # 2026-10-26：資料檔的值不寫別的遊戲的名字（PlayerTextTest），來源說明改用中文、不寫原作簡稱
        'source': ('原作 0.5 天賦樹：' + TREE['source']['pob'] + '；' + TREE['source']['repoe']
                   + '；每格 160 原作單位；tools/gen_poe2_tree.py 產生').replace('PathOfBuilding-PoE2', 'Path of Building 2'),
        'starts': starts,
        'nodes': nodes,
        'wires': wires,
        'anoint': anoint,
    }
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(root, f, ensure_ascii=False, separators=(',', ':'))
        f.write('\n')
    write_anoint_yml(anoint)
    anoint_inactive = sum(1 for n in anoint for d in n['description'] if d.endswith(INACTIVE))
    print('anoint-only', len(anoint), 'inactive lines', anoint_inactive)
    inactive = sum(1 for n in nodes for d in n['description'] if d.endswith(INACTIVE))
    total = sum(len(n['description']) for n in nodes)
    print('nodes', len(nodes), 'edges', len(edges), 'wires', len(wires), 'wire cells', len(used),
          'shared cells', sum(1 for v in used.values() if v > 1), 'lines', total, 'inactive', inactive,
          'excluded oracle', len(excluded) - len(flail), 'excluded flail', len(flail), 'unreachable', len(raw) - len(ids) - len(excluded))


def yaml_str(text):
    return '"' + text.replace('\\', '\\\\').replace('"', '\\"') + '"'


def write_anoint_yml(anoint):
    """2026-10-27：塗油限定核心的注入配方（tools/poe2_tree/anoint_recipes.json，照 poe2db）→ data/passives/anoint_only.yml。"""
    recipes = load('anoint_recipes.json', {})
    emotions = recipes.get('_emotions', {})
    lines = [
        '# 2026-10-27 照 PoE2 0.5：塗油限定的核心（天賦樹上沒有、只能注入在項鍊上的核心）與它們的注入配方。',
        '# tools/gen_poe2_tree.py 產生（配方的來源是 tools/poe2_tree/anoint_recipes.json，照 poe2db 各核心頁面），不要手改。',
        '# 核心的效果在 tree.json 的 anoint（PassiveTree.anointable() 會跟樹上的核心一起列出來，anointOnly = true）。',
        '# recipe：三種蒸餾情緒，照原作寫的順序（原作的配方有順序：巨著與模範、群集復甦與奇術發動機用的是同樣三種，只差順序）。',
        '# 情緒的代號照 currencies.yml 現有的寫法（distilled_<情緒>）；強效（Potent）三種是原作 0.5 終局才掉的情緒。',
        'emotions:',
    ]
    for key, (en, zh) in emotions.items():
        lines.append('  %s: { name: %s }' % (key, yaml_str(zh)))
    lines.append('notables:')
    for n in anoint:
        recipe = recipes.get(n['en'])
        if recipe is None:
            raise SystemExit('anoint_recipes.json 沒有 ' + n['en'] + ' 的配方')
        for emotion in recipe['recipe']:
            if emotion not in emotions:
                raise SystemExit('anoint_recipes.json：' + n['en'] + ' 的情緒 ' + emotion + ' 不在 _emotions')
        lines.append('  %s:' % n['id'])
        lines.append('    name: %s' % yaml_str(n['name']))
        lines.append('    recipe: [%s]' % ', '.join(recipe['recipe']))
    path = os.path.join(os.path.dirname(OUT), 'anoint_only.yml')
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
