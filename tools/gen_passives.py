# -*- coding: utf-8 -*-
"""產生被動天賦樹 data/passives/tree.json（第 2 版：七個職業）。
用法：python tools/gen_passives.py src/main/resources/data/passives/tree.json

格子座標 (x, y)，y 向下（與箱子 GUI 的列一致）。相鄰（上下左右）的兩個節點就是相連。

骨架：中心 + 三圈方形的環（r = 4、10、16）+ 輻條。七個職業的起點各在自己方向的輻條旁邊（起點是葉子：
只連一格，別人不會穿過它）。方向（數學角度，0 = 右、90 = 上）每 45 度一個區：
  女巫 112.5（智慧，靠力量那一側）、女術士 67.5（智慧）、武僧 22.5（敏捷＋智慧）、遊俠 -22.5（敏捷）、
  女獵手 -67.5（敏捷＋力量）、傭兵 -112.5（力量＋敏捷）、野蠻人 -157.5（力量）、157.5 是力量＋智慧（沒有職業）。
旅行節點（環與輻條）給所在區的屬性；群組（小點＋核心）自動掛在環上、彼此不相碰；
主題照區分配：離那一區職業起點越近的群組，拿越貼近那個職業的主題（傭兵起點附近是弩、武僧是長棍…）。
基石 14 個，平均掛在最外圈外面。
珠寶孔 18 個（PoE2 的珠寶插槽）：每一區在中環、外環各掛一個，中心的輻條旁邊兩個；放在所有群組之後，
既有節點的位置與檔案順序都不變（所以 VERSION 不用加、舊的規劃代碼照樣能用）。
異常狀態的群組（2026-10：流血、中毒、暈眩、電擊）：放在珠寶孔之後、用剩下的空位，主題寫死在 AILMENT_CLUSTERS
（不走各區的主題輪替，既有群組的主題也不會變）；同樣不動任何既有節點。
暴擊的群組（2026-10：法術暴擊、攻擊暴擊）：再放在異常狀態的群組之後，寫死在 CRIT_CLUSTERS，一樣不動既有節點。
"""
import json, math, sys
from collections import OrderedDict

OUT = sys.argv[1] if len(sys.argv) > 1 else 'tree.json'
VERSION = 2
TARGET = int(sys.argv[sys.argv.index('--target') + 1]) if '--target' in sys.argv else 780  # 最多幾個節點（放滿就停）

cells = OrderedDict()   # (x, y) -> dict(kind, cluster, role)

def put(x, y, kind, cluster=None):
    if (x, y) in cells:
        raise SystemExit(f'重複的格子 {(x, y)}：{cells[(x, y)]} vs {kind}/{cluster}')
    cells[(x, y)] = {'kind': kind, 'cluster': cluster}

def nbrs(p):
    return ((p[0] + 1, p[1]), (p[0] - 1, p[1]), (p[0], p[1] + 1), (p[0], p[1] - 1))

def angle_of(x, y):
    """數學角度（度，-180..180），y 向下所以取負號。"""
    return math.degrees(math.atan2(-y, x))

# ---------------- 區 ----------------
SECTORS = [  # (代號, 中心角度, 職業, 屬性)
    ('witch', 112.5, 'witch', ('int',)),
    ('sorceress', 67.5, 'sorceress', ('int',)),
    ('monk', 22.5, 'monk', ('dex', 'int')),
    ('ranger', -22.5, 'ranger', ('dex',)),
    ('huntress', -67.5, 'huntress', ('dex', 'str')),
    ('mercenary', -112.5, 'mercenary', ('str', 'dex')),
    ('marauder', -157.5, 'marauder', ('str',)),
    ('templar', 157.5, None, ('str', 'int')),
]

def sector_of(x, y):
    a = angle_of(x, y)
    best = None
    for sid, centre, cls, attrs in SECTORS:
        d = abs((a - centre + 180) % 360 - 180)
        if best is None or d < best[0]:
            best = (d, sid)
    return best[1]

SECTOR = {s[0]: s for s in SECTORS}

# ---------------- 骨架 ----------------
RINGS = tuple(int(v) for v in (sys.argv[sys.argv.index('--rings') + 1].split(',') if '--rings' in sys.argv else (4, 9, 14)))

put(0, 0, 'center')
for d in (1, 2, 3):
    put(0, -d, 'travel'); put(0, d, 'travel'); put(-d, 0, 'travel'); put(d, 0, 'travel')
for r in RINGS:
    for x in range(-r, r + 1):
        for y in range(-r, r + 1):
            if max(abs(x), abs(y)) == r:
                put(x, y, 'travel')

def staircase(a, b):
    """a 到 b 的 4 連通路徑（不含 a、b），沿著直線走。"""
    path = []
    x, y = a
    steps = abs(b[0] - a[0]) + abs(b[1] - a[1])
    for _ in range(steps):
        # 選讓下一格最靠近直線的那一步
        options = []
        if x != b[0]:
            options.append((x + (1 if b[0] > x else -1), y))
        if y != b[1]:
            options.append((x, y + (1 if b[1] > y else -1)))
        def off(p):
            # 點到 a-b 直線的距離
            ax, ay = a; bx, by = b
            return abs((bx - ax) * (ay - p[1]) - (ax - p[0]) * (by - ay)) / math.hypot(bx - ax, by - ay)
        x, y = min(options, key=off)
        if (x, y) != b:
            path.append((x, y))
    return path

def ring_point(r, deg):
    """方形環 r 上最接近角度 deg 的格子。"""
    best = None
    for x in range(-r, r + 1):
        for y in range(-r, r + 1):
            if max(abs(x), abs(y)) != r:
                continue
            d = abs((angle_of(x, y) - deg + 180) % 360 - 180)
            if best is None or d < best[0]:
                best = (d, (x, y))
    return best[1]

def spoke(r1, r2, deg):
    a = ring_point(r1, deg)
    b = ring_point(r2, deg)
    for p in staircase(a, b):
        if p in cells:
            break  # 已經碰到外面那一圈（斜的輻條會先碰到環的邊）
        put(p[0], p[1], 'travel')
    return a, b

# 內環 → 中環：每一區一條（職業的那條掛起點）
SPOKES = {}
for sid, centre, cls, attrs in SECTORS:
    SPOKES[sid] = spoke(RINGS[0], RINGS[1], centre)
# 中環 → 外環：八個方向（區與區之間），再加四個正方向
for deg in (0, 45, 90, 135, 180, -135, -90, -45):
    spoke(RINGS[1], RINGS[2], deg)

# 起點：職業那條輻條的中間旁邊的一格空位（只碰到輻條那一格）
STARTS = {}
def place_start(cls, sid):
    a, b = SPOKES[sid]
    path = staircase(a, b)
    mid = path[len(path) // 2]
    for cand in nbrs(mid):
        if cand in cells:
            continue
        touching = [q for q in nbrs(cand) if q in cells]
        if touching == [mid]:
            put(cand[0], cand[1], 'start_' + cls)
            STARTS[cls] = cand
            return
    raise SystemExit(f'{cls} 的起點放不下（輻條 {a}→{b}）')

for sid, centre, cls, attrs in SECTORS:
    if cls:
        place_start(cls, sid)

skeleton = set(cells)

# ---------------- 群組 ----------------
# 形狀：本地座標 (f, s)：f = 往外（離開錨點）的距離，s = 側向；第一格一定是 (1, 0)
SHAPES = {
    'wheel': [(1, 0, 's')] + [(f, s, 's') for f in (2, 3, 4) for s in (-1, 0, 1) if not (f == 3 and s == 0)] + [(3, 0, 'n')],
    'fork':  [(1, 0, 's'), (2, 0, 's'), (3, 0, 'n'), (2, 1, 's'), (2, -1, 's')],
    'line4': [(1, 0, 's'), (2, 0, 's'), (3, 0, 's'), (4, 0, 'n')],
    'line3': [(1, 0, 's'), (2, 0, 's'), (3, 0, 'n')],
    'line2': [(1, 0, 's'), (2, 0, 'n')],
    'cross': [(1, 0, 's'), (2, 0, 'n'), (2, 1, 's'), (2, -1, 's'), (3, 0, 's')],
    'ell':   [(1, 0, 's'), (2, 0, 's'), (2, 1, 's'), (2, 2, 'n')],
    'keystone': [(1, 0, 's'), (2, 0, 's'), (3, 0, 'k')],
    'socket': [(1, 0, 'j')],   # 珠寶孔：掛在環上的一格葉子（配了它才能放珠寶）
}

clusters = []   # dict(id, theme, cells: [(pos, kind)], anchor, shape)

def transform(anchor, d, f, s):
    # d = 往外的方向 (dx, dy)；側向 = 轉 90 度
    side = (-d[1], d[0])
    return (anchor[0] + d[0] * f + side[0] * s, anchor[1] + d[1] * f + side[1] * s)

def fits(anchor, d, shape, margin=True):
    pos = [transform(anchor, d, f, s) for f, s, _ in SHAPES[shape]]
    own = set(pos)
    for p in pos:
        if p in cells:
            return None
        for q in nbrs(p):
            if q in cells and q not in own and not (q == anchor and p == pos[0]):
                return None
    if margin:
        # 跟別的群組至少隔一格（斜角也不要貼著），畫面才分得出來
        for p in pos:
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    q = (p[0] + dx, p[1] + dy)
                    if q in cells and q not in own and q != anchor and cells[q]['cluster'] is not None:
                        return None
    return pos

MARGIN = '--loose' in sys.argv   # 預設群組之間斜角可以相鄰（放得比較多）

def place(anchor, d, shape, cid, margin=None):
    pos = fits(anchor, d, shape, MARGIN if margin is None else margin)
    if pos is None:
        return False
    kinds = {'s': 'small', 'n': 'notable', 'k': 'keystone', 'j': 'socket'}
    entry = {'id': cid, 'theme': None, 'cells': [], 'anchor': anchor, 'shape': shape}
    for p, (f, s, k) in zip(pos, SHAPES[shape]):
        put(p[0], p[1], kinds[k], cid)
        entry['cells'].append((p, kinds[k]))
    clusters.append(entry)
    return True

def outward(p, r):
    """環 r 上這一格往外（離開中心）的方向；角落不掛。"""
    x, y = p
    if abs(x) == r and abs(y) == r:
        return None
    if abs(x) == r:
        return (1 if x > 0 else -1, 0)
    return (0, 1 if y > 0 else -1)

# 1) 基石：外環外面，平均 14 個方向
KEYSTONE_ANGLES = [180 - i * 22.5 for i in range(16)]   # 每一區的中間與區的交界各一個
keystone_slots = []
for deg in KEYSTONE_ANGLES:
    p = ring_point(RINGS[-1], deg)
    d = outward(p, RINGS[-1])
    if d is None:
        p = (p[0] - (1 if p[0] > 0 else -1), p[1])
        d = outward(p, RINGS[-1])
    if not place(p, d, 'keystone', f'k{len(keystone_slots)}'):
        raise SystemExit(f'基石放不下：{p}')
    keystone_slots.append(clusters[-1])

# 2) 一般群組：每一圈的每一格（角落除外）都是候選的錨點，往外、往內各一個方向；
#    照區分桶，輪流在每一區放一個（各區一樣多），先放大的（輪、叉），再放小的，直到總數到 TARGET。
candidates = {}
for r in RINGS:
    for p in sorted((q for q in skeleton if max(abs(q[0]), abs(q[1])) == r and cells[q]['kind'] == 'travel'),
                    key=lambda q: angle_of(*q)):
        d = outward(p, r)
        if d is None:
            continue
        for direction in (d, (-d[0], -d[1])):
            if r == RINGS[0] and direction != d:
                continue  # 內環裡面是中心與輻條，不掛
            candidates.setdefault(sector_of(*p), []).append((p, direction))
if '--spokes' in sys.argv:
    # 輻條上的格子也可以掛（往四個方向試）
    for q in sorted(skeleton, key=lambda q: angle_of(*q) if q != (0, 0) else 0):
        if cells[q]['kind'] != 'travel' or max(abs(q[0]), abs(q[1])) in RINGS or max(abs(q[0]), abs(q[1])) < RINGS[0]:
            continue
        for direction in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            candidates.setdefault(sector_of(*q), []).append((q, direction))

cid = 0
PASSES = [(name, int(q)) for name, q in (x.split(':') for x in (sys.argv[sys.argv.index('--passes') + 1].split(',') if '--passes' in sys.argv
          else 'wheel:2,cross:3,fork:3,ell:3,line4:99,line3:99,line2:99'.split(',')))]
for shape, quota in PASSES:
    placed = {sid: 0 for sid in candidates}
    progress = True
    while progress and len(cells) < TARGET:
        progress = False
        for sid in [x[0] for x in SECTORS]:
            if len(cells) >= TARGET:
                break
            if placed[sid] >= quota:
                continue
            for i, (p, direction) in enumerate(candidates.get(sid, [])):
                # 從這一區的中間往兩邊找（比較平均），用 i 的順序做固定的交錯
                if place(p, direction, shape, f'c{cid}'):
                    cid += 1
                    placed[sid] += 1
                    progress = True
                    candidates[sid].pop(i)
                    break

# 3) 珠寶孔（PoE2 的珠寶插槽）：每一區在中環與外環各掛一個，最靠近區中心的空位優先，
#    跟群組隔一格（跟群組一樣的 margin 規則），所以畫面上一眼就認得出來；放在群組之後，不會動到任何既有節點的位置。
#    另外在中心的輻條旁邊放兩個（每個職業都走得到）。
SOCKETS_PER_SECTOR_RINGS = (RINGS[1], RINGS[2])
socket_count = 0
def place_socket(anchor, direction, margin=True):
    global socket_count
    if place(anchor, direction, 'socket', f's{socket_count}', margin=margin):
        socket_count += 1
        return True
    return False

def socket_anchors(sid, centre, r):
    """這一區、環 r 上的旅行格（最靠近區中心的在前），再加上環 r 往內那一段輻條上的格子（備用）。"""
    ring_cells = [q for q in skeleton if max(abs(q[0]), abs(q[1])) == r and cells[q]['kind'] == 'travel' and sector_of(*q) == sid]
    ring_cells.sort(key=lambda q: (abs((angle_of(*q) - centre + 180) % 360 - 180), q))
    inner = RINGS[RINGS.index(r) - 1]
    spoke_cells = [q for q in skeleton if inner < max(abs(q[0]), abs(q[1])) < r and cells[q]['kind'] == 'travel' and sector_of(*q) == sid]
    spoke_cells.sort(key=lambda q: (-max(abs(q[0]), abs(q[1])), q))   # 靠外的優先
    options = []
    for p in ring_cells:
        d = outward(p, r)
        if d is not None:
            options.append((p, d))
            options.append((p, (-d[0], -d[1])))
    for p in spoke_cells:
        for d in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            options.append((p, d))
    return options

for sid, centre, cls, attrs in SECTORS:
    for r in SOCKETS_PER_SECTOR_RINGS:
        options = socket_anchors(sid, centre, r)
        # 先找跟群組隔一格的位置，真的沒有才允許斜角貼著群組
        if not any(place_socket(p, d, True) for p, d in options) and not any(place_socket(p, d, False) for p, d in options):
            raise SystemExit(f'{sid} 區的環 {r} 放不下珠寶孔')
for anchor, direction in (((0, -2), (1, 0)), ((0, 2), (-1, 0))):
    if not place_socket(anchor, direction):
        raise SystemExit(f'中心的珠寶孔放不下：{anchor}')

# 4) 異常狀態的群組（2026-10）：每種異常在跟它最搭的區各放一組（流血：野蠻人、女獵手、傭兵；中毒：遊俠、女巫、武僧；
#    暈眩：野蠻人、傭兵、力量＋智慧；電擊：女術士、武僧、遊俠），用該區剩下的空位，先試叉、再短支；
#    跟珠寶孔一樣放在最後，既有節點不動。主題寫死（不走 POOLS 的輪替）。
AILMENT_CLUSTERS = [  # (主題, 區)
    ('str_bleed', 'marauder'), ('str_bleed', 'huntress'), ('str_bleed', 'mercenary'),
    ('dex_poison', 'ranger'), ('dex_poison', 'witch'), ('dex_poison', 'monk'),
    ('str_stun', 'marauder'), ('str_stun', 'mercenary'), ('str_stun', 'templar'),
    ('int_electrocute', 'sorceress'), ('int_electrocute', 'monk'), ('int_electrocute', 'ranger'),
    ('hyb_ailmentguard', 'templar'), ('hyb_ailmentguard', 'huntress'), ('hyb_ailmentguard', 'witch'), ('hyb_ailmentguard', 'sorceress'),
]
AILMENT_SHAPES = ('fork', 'line3', 'line2')
ailment_clusters = []
def ailment_anchors(sid, centre):
    """這一區可以掛的格子：環與輻條的旅行格、既有群組的小點（不掛起點、珠寶孔、基石、核心）；
    外環與外環外面的優先（空位在那裡），同一圈離區中心近的優先；四個方向都試。"""
    options = []
    for q, info in cells.items():
        if sector_of(*q) != sid:
            continue
        if info['kind'] not in ('travel', 'small'):
            continue
        if info['cluster'] is not None and cluster_by_id_now[info['cluster']]['shape'] in ('keystone', 'socket'):
            continue
        options.append(q)
    options.sort(key=lambda q: (-max(abs(q[0]), abs(q[1])), abs((angle_of(*q) - centre + 180) % 360 - 180), q))
    out = []
    for q in options:
        for d in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            # 往外的方向優先（新群組長在樹的外緣，不塞進中間）
            outwardness = (q[0] * d[0] + q[1] * d[1])
            out.append((-outwardness, q, d))
    out.sort(key=lambda t: (t[0] > 0, ))
    return [(q, d) for _, q, d in out]

cluster_by_id_now = {c['id']: c for c in clusters}
for theme, sid in AILMENT_CLUSTERS:
    centre = SECTOR[sid][1]
    options = ailment_anchors(sid, centre)
    done = False
    for shape in AILMENT_SHAPES:
        for margin in (True, False):
            for p, d in options:
                if place(p, d, shape, f'a{len(ailment_clusters)}', margin=margin):
                    clusters[-1]['fixed_theme'] = theme
                    clusters[-1]['sector'] = sid
                    cluster_by_id_now[clusters[-1]['id']] = clusters[-1]
                    ailment_clusters.append(clusters[-1])
                    done = True
                    break
            if done:
                break
        if done:
            break
    if not done:
        raise SystemExit(f'{sid} 區放不下異常狀態的群組 {theme}')

# 5) 暴擊的群組（2026-10）：以前的暴擊天賦都是通用的（只有匕首／爪、矛、錘限定），這裡補只對法術／攻擊有效、數值比較高的：
#    法術暴擊（女術士、女巫）、攻擊暴擊（遊俠、女獵手）。跟異常狀態的群組一樣放在最後、用剩下的空位，既有節點不動；
#    不超出現在的範圍（x、y 都在 ±21 內），星盤與網頁規劃器的大小不用改。
CRIT_CLUSTERS = [('int_spellcrit', 'sorceress'), ('int_spellcrit', 'witch'), ('dex_attackcrit', 'ranger'), ('dex_attackcrit', 'huntress')]
CRIT_LIMIT = 21
crit_clusters = []
for theme, sid in CRIT_CLUSTERS:
    centre = SECTOR[sid][1]
    options = ailment_anchors(sid, centre)
    done = False
    for shape in AILMENT_SHAPES:
        for margin in (True, False):
            for p, d in options:
                if any(max(abs(q[0]), abs(q[1])) > CRIT_LIMIT for q in (transform(p, d, f, s) for f, s, _ in SHAPES[shape])):
                    continue
                if place(p, d, shape, f'x{len(crit_clusters)}', margin=margin):
                    clusters[-1]['fixed_theme'] = theme
                    clusters[-1]['sector'] = sid
                    cluster_by_id_now[clusters[-1]['id']] = clusters[-1]
                    crit_clusters.append(clusters[-1])
                    done = True
                    break
            if done:
                break
        if done:
            break
    if not done:
        raise SystemExit(f'{sid} 區放不下暴擊的群組 {theme}')

# ---------------- 主題 ----------------
def inc(stat, pct, tags=(), text=None):
    return ({'stat': stat, 'type': 'INCREASED', 'value': round(pct / 100.0, 4), 'tags': list(tags)}, text)
def more(stat, pct, tags=(), text=None):
    return ({'stat': stat, 'type': 'MORE', 'value': round(pct / 100.0, 4), 'tags': list(tags)}, text)
def flat(stat, v, tags=(), text=None):
    return ({'stat': stat, 'type': 'FLAT', 'value': v, 'tags': list(tags)}, text)

# 主題：small = 小點的修飾器；notables = [(名稱, 修飾器)…]，同一個主題第 k 次出現用第 k 個（輪流）
THEMES = {
    # ---- 智慧：法術、元素、混沌、能量護盾 ----
    'int_spell': {'small': [inc('damage', 10, ['spell'], '增加 10% 法術傷害')], 'notables': [
        ('奧術', [inc('damage', 25, ['spell'], '增加 25% 法術傷害'), inc('cast.speed', 5, text='增加 5% 施法速度')]),
        ('祕傳', [inc('damage', 28, ['spell'], '增加 28% 法術傷害'), inc('mana.max', 8, text='增加 8% 最大魔力')]),
        ('咒術精通', [inc('damage', 22, ['spell'], '增加 22% 法術傷害'), flat('crit.multi', 15, ['spell'], '法術 +15% 暴擊傷害')])]},
    'int_es': {'small': [inc('es.max', 8, text='增加 8% 最大能量護盾')], 'notables': [
        ('靈盾', [inc('es.max', 20, text='增加 20% 最大能量護盾'), flat('es.max', 20, text='+20 最大能量護盾')]),
        ('星輝之膚', [inc('es.max', 22, text='增加 22% 最大能量護盾'), flat('resist.chaos', 8, text='+8% 混沌抗性')]),
        ('不滅靈光', [inc('es.max', 18, text='增加 18% 最大能量護盾'), inc('mana.max', 10, text='增加 10% 最大魔力')])]},
    'int_mana': {'small': [inc('mana.max', 8, text='增加 8% 最大魔力')], 'notables': [
        ('深泉', [inc('mana.max', 20, text='增加 20% 最大魔力'), inc('mana.regen', 30, text='增加 30% 魔力回復')]),
        ('靜思', [inc('mana.regen', 40, text='增加 40% 魔力回復'), inc('skill.cost', -6, text='減少 6% 魔力消耗')])]},
    'int_cast': {'small': [inc('cast.speed', 4, text='增加 4% 施法速度')], 'notables': [
        ('急智', [inc('cast.speed', 8, text='增加 8% 施法速度'), inc('damage', 10, ['spell'], '增加 10% 法術傷害')]),
        ('疾咒', [inc('cast.speed', 10, text='增加 10% 施法速度'), inc('skill.cost', -5, text='減少 5% 魔力消耗')])]},
    'int_lightning': {'small': [inc('damage', 12, ['lightning'], '增加 12% 閃電傷害')], 'notables': [
        ('雷霆', [inc('damage', 30, ['lightning'], '增加 30% 閃電傷害'), flat('resist.lightning', 8, text='+8% 閃電抗性')]),
        ('靜電場', [inc('damage', 25, ['lightning'], '增加 25% 閃電傷害'), inc('shock.effect', 20, text='增加 20% 感電效果')])]},
    'int_cold': {'small': [inc('damage', 12, ['cold'], '增加 12% 冰冷傷害')], 'notables': [
        ('霜咬', [inc('damage', 30, ['cold'], '增加 30% 冰冷傷害'), flat('resist.cold', 8, text='+8% 冰冷抗性')]),
        ('永凍', [inc('damage', 25, ['cold'], '增加 25% 冰冷傷害'), inc('freeze.buildup', 25, text='增加 25% 凍結累積')])]},
    'int_fire': {'small': [inc('damage', 12, ['fire'], '增加 12% 火焰傷害')], 'notables': [
        ('烈焰', [inc('damage', 30, ['fire'], '增加 30% 火焰傷害'), flat('resist.fire', 8, text='+8% 火焰抗性')]),
        ('焚身', [inc('damage', 25, ['fire'], '增加 25% 火焰傷害'), inc('ignite.magnitude', 20, text='增加 20% 點燃傷害')])]},
    'int_chaos': {'small': [inc('damage', 12, ['chaos'], '增加 12% 混沌傷害')], 'notables': [
        ('腐蝕', [inc('damage', 30, ['chaos'], '增加 30% 混沌傷害'), flat('resist.chaos', 10, text='+10% 混沌抗性')]),
        ('虛無', [inc('damage', 28, ['chaos'], '增加 28% 混沌傷害'), inc('cast.speed', 5, text='增加 5% 施法速度')]),
        ('星骸低語', [inc('damage', 25, ['chaos'], '增加 25% 混沌傷害'), inc('skill.area', 8, text='增加 8% 範圍效果')])]},
    'int_chaosres': {'small': [flat('resist.chaos', 7, text='+7% 混沌抗性')], 'notables': [
        ('蝕骨之耐', [flat('resist.chaos', 15, text='+15% 混沌抗性'), inc('life.max', 5, text='增加 5% 最大生命')]),
        ('暗之庇護', [flat('resist.chaos', 15, text='+15% 混沌抗性'), inc('es.max', 10, text='增加 10% 最大能量護盾')])]},
    'int_elemental': {'small': [inc('damage', 10, ['elemental'], '增加 10% 元素傷害')], 'notables': [
        ('元素之主', [inc('damage', 25, ['elemental'], '增加 25% 元素傷害'),
                  flat('resist.fire', 6, text='+6% 所有元素抗性'), flat('resist.cold', 6), flat('resist.lightning', 6)]),
        ('三相共振', [inc('damage', 22, ['elemental'], '增加 22% 元素傷害'), inc('ailment.duration', 15, text='增加 15% 元素異常持續時間')])]},
    'int_wandstaff': {'small': [inc('damage', 12, ['wand'], '增加 12% 法杖與長杖傷害'), inc('damage', 12, ['staff'])], 'notables': [
        ('祕法使', [inc('damage', 30, ['wand'], '增加 30% 法杖與長杖傷害'), inc('damage', 30, ['staff']),
                 inc('cast.speed', 8, text='增加 8% 施法速度')])]},
    'int_spellcrit': {'small': [inc('crit.chance', 15, ['spell'], '增加 15% 法術暴擊率')], 'notables': [
        ('星辰之眼', [inc('crit.chance', 40, ['spell'], '增加 40% 法術暴擊率'), flat('crit.multi', 20, ['spell'], '法術 +20% 暴擊傷害')]),
        ('咒心', [flat('crit.multi', 30, ['spell'], '法術 +30% 暴擊傷害'), inc('crit.chance', 20, ['spell'], '增加 20% 法術暴擊率')])]},
    'dex_attackcrit': {'small': [inc('crit.chance', 15, ['attack'], '增加 15% 攻擊暴擊率')], 'notables': [
        ('致命精準', [inc('crit.chance', 40, ['attack'], '增加 40% 攻擊暴擊率'), flat('crit.multi', 20, ['attack'], '攻擊 +20% 暴擊傷害')]),
        ('破綻', [flat('crit.multi', 30, ['attack'], '攻擊 +30% 暴擊傷害'), inc('crit.chance', 20, ['attack'], '增加 20% 攻擊暴擊率')])]},
    'int_area': {'small': [inc('skill.area', 6, text='增加 6% 範圍效果')], 'notables': [
        ('擴散', [inc('skill.area', 15, text='增加 15% 範圍效果'), inc('damage', 15, ['area'], '增加 15% 範圍傷害')])]},
    'int_ailment': {'small': [inc('ailment.duration', 8, text='增加 8% 元素異常持續時間')], 'notables': [
        ('元素侵蝕', [flat('ignite.chance', 8, text='+8% 點燃、感電機率'), flat('shock.chance', 8),
                  inc('freeze.buildup', 20, text='增加 20% 凍結累積')])]},
    'int_lightres': {'small': [flat('resist.lightning', 8, text='+8% 閃電抗性')], 'notables': [
        ('避雷', [flat('resist.lightning', 15, text='+15% 閃電抗性'), flat('attr.int', 10, text='+10 智慧')])]},
    'int_coldres': {'small': [flat('resist.cold', 8, text='+8% 冰冷抗性')], 'notables': [
        ('霜衣', [flat('resist.cold', 15, text='+15% 冰冷抗性'), flat('attr.dex', 10, text='+10 敏捷')])]},
    'int_cost': {'small': [inc('skill.cost', -4, text='減少 4% 魔力消耗')], 'notables': [
        ('節制', [inc('skill.cost', -10, text='減少 10% 魔力消耗'), inc('mana.regen', 20, text='增加 20% 魔力回復')])]},
    'int_minion': {'small': [inc('damage', 12, ['minion'], '增加 12% 召喚物傷害')], 'notables': [
        ('亡者領主', [inc('damage', 30, ['minion'], '增加 30% 召喚物傷害'), inc('life.max', 20, ['minion'], '增加 20% 召喚物最大生命')]),
        ('死靈低語', [inc('damage', 25, ['minion'], '增加 25% 召喚物傷害'), inc('attack.speed', 10, ['minion'], '增加 10% 召喚物攻擊速度')]),
        ('不死軍團', [flat('skill.minions', 1, ['minion'], '同時最多多 1 隻召喚物'), inc('life.max', 15, ['minion'], '增加 15% 召喚物最大生命')])]},
    'hyb_aura': {'small': [inc('aura.effect', 4, text='增加 4% 光環效果')], 'notables': [
        ('領袖', [inc('aura.effect', 10, text='增加 10% 光環效果'), inc('aura.reservation', -10, text='減少 10% 光環的魔力保留')]),
        ('信念', [inc('aura.effect', 12, text='增加 12% 光環效果'), inc('mana.max', 10, text='增加 10% 最大魔力')])]},
    'str_warcry': {'small': [inc('skill.cooldown', -5, ['warcry'], '戰吼冷卻時間減少 5%')], 'notables': [
        ('戰場號令', [inc('skill.cooldown', -15, ['warcry'], '戰吼冷卻時間減少 15%'), flat('warcry.exerts', 1, ['warcry'], '戰吼多強化 1 次攻擊')]),
        ('怒吼', [inc('skill.duration', 25, ['warcry'], '戰吼的效果持續時間增加 25%'), inc('skill.area', 20, ['warcry'], '戰吼範圍增加 20%')])]},
    'int_totem': {'small': [inc('damage', 12, ['totem'], '增加 12% 圖騰傷害')], 'notables': [
        ('圖騰大師', [inc('damage', 30, ['totem'], '增加 30% 圖騰傷害'), flat('skill.totems', 1, ['totem'], '同時最多多 1 個圖騰')]),
        ('祖靈守望', [inc('damage', 25, ['totem'], '增加 25% 圖騰傷害'), inc('skill.duration', 30, ['totem'], '圖騰持續時間增加 30%')]),
        ('圖騰共鳴', [inc('skill.area', 15, ['totem'], '圖騰技能範圍增加 15%'), inc('damage', 20, ['totem'], '增加 20% 圖騰傷害')])]},
    'dex_trap': {'small': [inc('damage', 12, ['trap'], '增加 12% 陷阱與榴彈傷害'), inc('damage', 12, ['grenade'])], 'notables': [
        ('陷阱師', [inc('damage', 30, ['trap'], '增加 30% 陷阱與榴彈傷害'), inc('damage', 30, ['grenade']),
                 inc('skill.area', 12, ['trap'], '陷阱與榴彈範圍增加 12%'), inc('skill.area', 12, ['grenade'])]),
        ('爆破專家', [inc('damage', 25, ['trap'], '增加 25% 陷阱與榴彈傷害'), inc('damage', 25, ['grenade']),
                  inc('skill.cooldown', -15, ['grenade'], '榴彈冷卻時間減少 15%')]),
        ('詭雷', [inc('damage', 20, ['trap'], '增加 20% 陷阱與榴彈傷害'), inc('damage', 20, ['grenade']),
                inc('skill.duration', 30, ['trap'], '陷阱持續時間增加 30%')])]},
    'dex_travel': {'small': [inc('skill.cooldown', -4, ['movement'], '位移技能冷卻時間減少 4%')], 'notables': [
        ('迅捷步伐', [inc('skill.cooldown', -12, ['movement'], '位移技能冷卻時間減少 12%'), inc('movement.speed', 3, text='增加 3% 移動速度')]),
        ('殘影', [inc('skill.cooldown', -10, ['movement'], '位移技能冷卻時間減少 10%'), inc('damage', 25, ['movement'], '增加 25% 位移技能傷害')])]},
    'witch_blood': {'small': [inc('life.max', 4, text='增加 4% 最大生命')], 'notables': [
        ('血咒', [inc('life.max', 8, text='增加 8% 最大生命'), flat('leech.life', 1, text='物理傷害的 1% 偷取為生命')]),
        ('鮮血儀式', [inc('life.max', 6, text='增加 6% 最大生命'), inc('damage', 15, ['spell'], '增加 15% 法術傷害')])]},
    # ---- 力量：生命、護甲、近戰 ----
    'str_life': {'small': [inc('life.max', 4, text='增加 4% 最大生命')], 'notables': [
        ('壯碩', [inc('life.max', 10, text='增加 10% 最大生命'), flat('life.max', 20, text='+20 最大生命')]),
        ('巨人之軀', [inc('life.max', 12, text='增加 12% 最大生命'), flat('attr.str', 10, text='+10 力量')]),
        ('不屈', [inc('life.max', 8, text='增加 8% 最大生命'), flat('resist.max', 1, text='+1% 所有抗性上限')])]},
    'str_armour': {'small': [inc('armour', 12, text='增加 12% 護甲')], 'notables': [
        ('鐵壁之軀', [inc('armour', 30, text='增加 30% 護甲'), flat('attr.str', 15, text='+15 力量')]),
        ('岩膚', [inc('armour', 35, text='增加 35% 護甲'), inc('life.max', 4, text='增加 4% 最大生命')])]},
    'str_melee': {'small': [inc('damage', 10, ['melee', 'physical'], '增加 10% 近戰物理傷害')], 'notables': [
        ('碎骨', [inc('damage', 25, ['melee', 'physical'], '增加 25% 近戰物理傷害'), flat('attr.str', 10, text='+10 力量')]),
        ('蠻力', [inc('damage', 28, ['melee', 'physical'], '增加 28% 近戰物理傷害'), flat('crit.multi', 15, ['melee'], '近戰 +15% 暴擊傷害')])]},
    'str_axe': {'small': [inc('damage', 12, ['axe'], '增加 12% 斧傷害')], 'notables': [
        ('伐木者', [inc('damage', 30, ['axe'], '增加 30% 斧傷害'), inc('attack.speed', 6, ['axe'], '使用斧時增加 6% 攻擊速度')])]},
    'str_mace': {'small': [inc('damage', 12, ['mace'], '增加 12% 錘傷害')], 'notables': [
        ('粉碎', [inc('damage', 30, ['mace'], '增加 30% 錘傷害'), flat('crit.multi', 15, ['mace'], '使用錘時 +15% 暴擊傷害')])]},
    'str_regen': {'small': [flat('life.regen', 2, text='每秒回復 2 生命')], 'notables': [
        ('不滅', [flat('life.regen', 6, text='每秒回復 6 生命'), inc('life.max', 6, text='增加 6% 最大生命')])]},
    'str_fireres': {'small': [flat('resist.fire', 8, text='+8% 火焰抗性')], 'notables': [
        ('烈焰之心', [flat('resist.fire', 15, text='+15% 火焰抗性'), flat('attr.str', 10, text='+10 力量')])]},
    'str_block': {'small': [flat('block.chance', 2, text='+2% 格擋率')], 'notables': [
        ('盾牆', [flat('block.chance', 5, text='+5% 格擋率'), inc('armour', 20, text='增加 20% 護甲')])]},
    'str_twohand': {'small': [inc('damage', 12, ['two_hand'], '增加 12% 雙手武器傷害')], 'notables': [
        ('巨兵', [inc('damage', 30, ['two_hand'], '增加 30% 雙手武器傷害'), inc('attack.speed', 8, ['two_hand'], '使用雙手武器時增加 8% 攻擊速度')])]},
    'str_slam': {'small': [inc('damage', 10, ['area', 'melee'], '增加 10% 近戰範圍傷害')], 'notables': [
        ('震地', [inc('damage', 25, ['area'], '增加 25% 範圍傷害'), inc('skill.area', 10, text='增加 10% 範圍效果')])]},
    # ---- 敏捷：閃避、攻速、投射物、暴擊 ----
    'dex_evasion': {'small': [inc('evasion', 12, text='增加 12% 閃避')], 'notables': [
        ('幻影', [inc('evasion', 30, text='增加 30% 閃避'), flat('attr.dex', 10, text='+10 敏捷')]),
        ('身輕如燕', [inc('evasion', 30, text='增加 30% 閃避'), inc('movement.speed', 3, text='增加 3% 移動速度')])]},
    'dex_aps': {'small': [inc('attack.speed', 4, text='增加 4% 攻擊速度')], 'notables': [
        ('疾風', [inc('attack.speed', 8, text='增加 8% 攻擊速度'), inc('accuracy', 10, text='增加 10% 命中值')]),
        ('連擊', [inc('attack.speed', 8, text='增加 8% 攻擊速度'), inc('damage', 10, ['attack'], '增加 10% 攻擊傷害')])]},
    'dex_accuracy': {'small': [inc('accuracy', 10, text='增加 10% 命中值')], 'notables': [
        ('鷹眼', [inc('accuracy', 25, text='增加 25% 命中值'), inc('crit.chance', 15, text='增加 15% 暴擊率')])]},
    'dex_bow': {'small': [inc('damage', 12, ['bow'], '增加 12% 弓傷害')], 'notables': [
        ('神射', [inc('damage', 30, ['bow'], '增加 30% 弓傷害'), inc('attack.speed', 8, ['bow'], '使用弓時增加 8% 攻擊速度')]),
        ('長弓手', [inc('damage', 25, ['bow'], '增加 25% 弓傷害'), inc('skill.projectile_speed', 15, text='增加 15% 投射物速度')])]},
    'dex_dagger': {'small': [inc('damage', 12, ['dagger'], '增加 12% 匕首與爪傷害'), inc('damage', 12, ['claw'])], 'notables': [
        ('刺客', [inc('damage', 30, ['dagger'], '增加 30% 匕首與爪傷害'), inc('damage', 30, ['claw']),
                inc('crit.chance', 20, ['dagger'], '使用匕首或爪時增加 20% 暴擊率'), inc('crit.chance', 20, ['claw'])])]},
    'dex_crit': {'small': [inc('crit.chance', 15, text='增加 15% 暴擊率')], 'notables': [
        ('致命一擊', [inc('crit.chance', 40, text='增加 40% 暴擊率'), flat('crit.multi', 20, text='+20% 暴擊傷害')]),
        ('要害', [flat('crit.multi', 30, text='+30% 暴擊傷害'), inc('crit.chance', 15, text='增加 15% 暴擊率')])]},
    'dex_move': {'small': [inc('movement.speed', 2, text='增加 2% 移動速度')], 'notables': [
        ('風行', [inc('movement.speed', 5, text='增加 5% 移動速度'), inc('evasion', 10, text='增加 10% 閃避')])]},
    'dex_proj': {'small': [inc('damage', 10, ['projectile'], '增加 10% 投射物傷害')], 'notables': [
        ('穿刺', [inc('damage', 25, ['projectile'], '增加 25% 投射物傷害'), inc('attack.speed', 4, text='增加 4% 攻擊速度')]),
        ('彈道學', [inc('damage', 22, ['projectile'], '增加 22% 投射物傷害'), inc('skill.projectile_speed', 15, text='增加 15% 投射物速度')])]},
    'dex_coldres': {'small': [flat('resist.cold', 8, text='+8% 冰冷抗性')], 'notables': [
        ('霜行者', [flat('resist.cold', 15, text='+15% 冰冷抗性'), inc('evasion', 10, text='增加 10% 閃避')])]},
    'dex_flask': {'small': [inc('flask.life_recovery', 8, text='增加 8% 生命藥劑回復')], 'notables': [
        ('藥師', [inc('flask.life_recovery', 20, text='增加 20% 生命與魔力藥劑回復'), inc('flask.mana_recovery', 20)])]},
    # ---- 新職業 ----
    'merc_crossbow': {'small': [inc('damage', 12, ['crossbow'], '增加 12% 弩傷害')], 'notables': [
        ('重弩手', [inc('damage', 30, ['crossbow'], '增加 30% 弩傷害'), inc('attack.speed', 8, ['crossbow'], '使用弩時增加 8% 攻擊速度')]),
        ('裝填', [inc('damage', 25, ['crossbow'], '增加 25% 弩傷害'), flat('skill.pierce', 1, text='投射物多穿透 1 個敵人')])]},
    'merc_ammo': {'small': [inc('skill.projectile_speed', 8, text='增加 8% 投射物速度')], 'notables': [
        ('穿甲彈', [flat('skill.pierce', 1, text='投射物多穿透 1 個敵人'), inc('damage', 15, ['projectile'], '增加 15% 投射物傷害')])]},
    'hyb_armoureva': {'small': [inc('armour', 6, text='增加 6% 護甲與閃避'), inc('evasion', 6)], 'notables': [
        ('傭兵之甲', [inc('armour', 15, text='增加 15% 護甲與閃避'), inc('evasion', 15), inc('life.max', 4, text='增加 4% 最大生命')])]},
    'monk_staff': {'small': [inc('damage', 12, ['quarterstaff'], '增加 12% 長棍傷害')], 'notables': [
        ('棍術', [inc('damage', 30, ['quarterstaff'], '增加 30% 長棍傷害'), inc('attack.speed', 6, ['quarterstaff'], '使用長棍時增加 6% 攻擊速度')]),
        ('守勢', [inc('damage', 22, ['quarterstaff'], '增加 22% 長棍傷害'), flat('block.chance', 3, text='+3% 格擋率')])]},
    'monk_storm': {'small': [inc('damage', 10, ['attack', 'lightning'], '增加 10% 攻擊的閃電傷害')], 'notables': [
        ('掌心雷', [flat('damage.gain.lightning', 8, text='物理傷害的 8% 額外獲得為閃電傷害'), flat('shock.chance', 10, text='+10% 感電機率')])]},
    'monk_frost': {'small': [inc('damage', 10, ['attack', 'cold'], '增加 10% 攻擊的冰冷傷害')], 'notables': [
        ('寒掌', [flat('damage.gain.cold', 8, text='物理傷害的 8% 額外獲得為冰冷傷害'), inc('freeze.buildup', 25, text='增加 25% 凍結累積')])]},
    'hunt_spear': {'small': [inc('damage', 12, ['spear'], '增加 12% 矛傷害')], 'notables': [
        ('長矛手', [inc('damage', 30, ['spear'], '增加 30% 矛傷害'), inc('crit.chance', 20, ['spear'], '使用矛時增加 20% 暴擊率')]),
        ('投矛', [inc('damage', 25, ['spear'], '增加 25% 矛傷害'), inc('damage', 15, ['projectile'], '增加 15% 投射物傷害')])]},
    'hunt_buckler': {'small': [flat('block.chance', 2, text='+2% 格擋率')], 'notables': [
        ('小盾舞者', [flat('block.chance', 5, text='+5% 格擋率'), inc('evasion', 20, text='增加 20% 閃避')])]},
    # ---- 混合 ----
    'hyb_sword': {'small': [inc('damage', 12, ['sword'], '增加 12% 劍傷害')], 'notables': [
        ('劍聖', [inc('damage', 30, ['sword'], '增加 30% 劍傷害'), inc('attack.speed', 8, ['sword'], '使用劍時增加 8% 攻擊速度')])]},
    'hyb_physical': {'small': [inc('damage', 10, ['physical'], '增加 10% 物理傷害')], 'notables': [
        ('殘暴', [inc('damage', 25, ['physical'], '增加 25% 物理傷害'), flat('attr.str', 10, text='+10 力量'), flat('attr.dex', 10, text='+10 敏捷')])]},
    'hyb_lifeeva': {'small': [inc('life.max', 4, text='增加 4% 最大生命'), inc('evasion', 6, text='增加 6% 閃避')], 'notables': [
        ('獵手之軀', [inc('life.max', 8, text='增加 8% 最大生命'), inc('evasion', 15, text='增加 15% 閃避')])]},
    'hyb_lifees': {'small': [inc('life.max', 4, text='增加 4% 最大生命'), inc('es.max', 4, text='增加 4% 最大能量護盾')], 'notables': [
        ('血與魂', [inc('life.max', 8, text='增加 8% 最大生命'), inc('es.max', 10, text='增加 10% 最大能量護盾')])]},
    'hyb_evaes': {'small': [inc('evasion', 6, text='增加 6% 閃避'), inc('es.max', 6, text='增加 6% 最大能量護盾')], 'notables': [
        ('幽魂', [inc('evasion', 15, text='增加 15% 閃避'), inc('es.max', 15, text='增加 15% 最大能量護盾')]),
        ('雲水', [inc('evasion', 12, text='增加 12% 閃避'), inc('es.max', 12, text='增加 12% 最大能量護盾'), inc('movement.speed', 3, text='增加 3% 移動速度')])]},
    'hyb_armoures': {'small': [inc('armour', 6, text='增加 6% 護甲與能量護盾'), inc('es.max', 6)], 'notables': [
        ('聖騎之甲', [inc('armour', 15, text='增加 15% 護甲與能量護盾'), inc('es.max', 15), flat('resist.max', 1, text='+1% 所有抗性上限')])]},
    'hyb_firelife': {'small': [inc('damage', 10, ['fire'], '增加 10% 火焰傷害')], 'notables': [
        ('熔核', [inc('damage', 20, ['fire'], '增加 20% 火焰傷害'), inc('life.max', 6, text='增加 6% 最大生命')])]},
    'hyb_ignite': {'small': [inc('ignite.magnitude', 10, text='增加 10% 點燃傷害')], 'notables': [
        ('焚城', [flat('ignite.chance', 10, text='+10% 點燃機率'), inc('ignite.magnitude', 25, text='增加 25% 點燃傷害')])]},
    'hyb_stormcrit': {'small': [inc('damage', 10, ['cold'], '增加 10% 冰冷與閃電傷害'), inc('damage', 10, ['lightning'])], 'notables': [
        ('風暴', [inc('damage', 20, ['cold'], '增加 20% 冰冷與閃電傷害'), inc('damage', 20, ['lightning']),
                inc('crit.chance', 15, text='增加 15% 暴擊率')])]},
    'hyb_staff': {'small': [inc('damage', 12, ['staff'], '增加 12% 長杖傷害')], 'notables': [
        ('杖法', [inc('damage', 30, ['staff'], '增加 30% 長杖傷害'), flat('block.chance', 3, text='+3% 格擋率')])]},
    'hyb_lifemana': {'small': [inc('life.max', 4, text='增加 4% 最大生命'), inc('mana.max', 6, text='增加 6% 最大魔力')], 'notables': [
        ('生命之泉', [inc('life.max', 8, text='增加 8% 最大生命'), inc('mana.max', 12, text='增加 12% 最大魔力')])]},
    'hyb_lifecrit': {'small': [inc('life.max', 4, text='增加 4% 最大生命'), inc('crit.chance', 8, text='增加 8% 暴擊率')], 'notables': [
        ('冷血', [inc('life.max', 8, text='增加 8% 最大生命'), inc('crit.chance', 20, text='增加 20% 暴擊率')])]},
    'hyb_allres': {'small': [flat('resist.fire', 4, text='+4% 所有元素抗性'), flat('resist.cold', 4), flat('resist.lightning', 4)], 'notables': [
        ('三重屏障', [flat('resist.fire', 10, text='+10% 所有元素抗性'), flat('resist.cold', 10), flat('resist.lightning', 10)])]},
    # ---- 異常狀態（2026-10）：流血、中毒、暈眩、電擊；機率是 FLAT 百分比點數，累積／傷害是 INCREASED ----
    'str_bleed': {'small': [inc('bleed.magnitude', 12, text='增加 12% 流血傷害')], 'notables': [
        ('撕裂', [flat('bleed.chance', 12, ['attack'], '攻擊 +12% 流血機率'), inc('bleed.magnitude', 30, text='增加 30% 流血傷害')]),
        ('血流不止', [inc('bleed.magnitude', 25, text='增加 25% 流血傷害'), inc('bleed.duration', 30, text='增加 30% 流血持續時間')]),
        ('開膛', [flat('bleed.chance', 15, ['attack'], '攻擊 +15% 流血機率'), inc('damage', 15, ['physical'], '增加 15% 物理傷害')])]},
    'dex_poison': {'small': [inc('poison.magnitude', 12, text='增加 12% 中毒傷害')], 'notables': [
        ('毒牙', [flat('poison.chance', 12, text='+12% 中毒機率'), inc('poison.magnitude', 30, text='增加 30% 中毒傷害')]),
        ('潰爛', [inc('poison.magnitude', 25, text='增加 25% 中毒傷害'), inc('poison.duration', 30, text='增加 30% 中毒持續時間')]),
        ('劇毒', [flat('poison.chance', 15, text='+15% 中毒機率'), inc('damage', 15, ['chaos'], '增加 15% 混沌傷害')])]},
    'str_stun': {'small': [inc('stun.buildup', 12, text='增加 12% 暈眩累積')], 'notables': [
        ('震魄', [inc('stun.buildup', 35, text='增加 35% 暈眩累積'), inc('damage', 10, ['melee'], '增加 10% 近戰傷害')]),
        ('重壓', [inc('stun.buildup', 25, text='增加 25% 暈眩累積'), inc('stun.duration', 30, text='增加 30% 暈眩持續時間')]),
        ('碎顱', [inc('stun.buildup', 30, text='增加 30% 暈眩累積'), inc('damage', 15, ['slam'], '增加 15% 重擊傷害')])]},
    'int_electrocute': {'small': [inc('electrocute.buildup', 12, text='增加 12% 電擊累積')], 'notables': [
        ('觸電', [flat('electrocute.chance', 15, text='+15% 電擊機率'), inc('electrocute.buildup', 30, text='增加 30% 電擊累積')]),
        ('麻痺', [inc('electrocute.buildup', 25, text='增加 25% 電擊累積'), inc('electrocute.duration', 30, text='增加 30% 電擊持續時間')]),
        ('雷殛', [flat('electrocute.chance', 15, text='+15% 電擊機率'), inc('damage', 15, ['lightning'], '增加 15% 閃電傷害')])]},
    'hyb_ailmentguard': {'small': [inc('stun.threshold', 10, text='增加 10% 暈眩門檻')], 'notables': [
        ('封創', [flat('avoid.bleed', 25, text='25% 機率避免流血'), flat('avoid.poison', 25, text='25% 機率避免中毒')]),
        ('不動', [inc('stun.threshold', 40, text='增加 40% 暈眩門檻'), flat('avoid.stun', 15, text='15% 機率避免暈眩')]),
        ('絕緣', [flat('avoid.electrocute', 30, text='30% 機率避免電擊'), flat('avoid.shock', 20, text='20% 機率避免感電')])]},
}

# 各區的主題（越前面越靠近職業起點）
POOLS = {
    'witch':     ['int_chaos', 'witch_blood', 'int_minion', 'hyb_lifees', 'int_spell', 'int_es', 'int_area', 'int_minion',
                  'int_cost', 'int_chaosres', 'int_cast', 'hyb_lifemana', 'int_spellcrit', 'int_mana'],
    'sorceress': ['int_spell', 'int_fire', 'int_cold', 'int_lightning', 'int_totem', 'int_es', 'int_cast', 'int_wandstaff',
                  'int_spellcrit', 'int_ailment', 'int_mana', 'int_lightres', 'int_coldres', 'int_area', 'int_cost'],
    'monk':      ['monk_staff', 'monk_storm', 'monk_frost', 'hyb_evaes', 'dex_aps', 'hyb_stormcrit', 'dex_travel', 'dex_move',
                  'monk_staff', 'dex_crit', 'int_coldres', 'hyb_allres', 'dex_evasion'],
    'ranger':    ['dex_bow', 'dex_proj', 'dex_evasion', 'dex_aps', 'dex_trap', 'dex_crit', 'dex_move', 'dex_dagger',
                  'dex_coldres', 'dex_flask', 'dex_bow', 'hyb_lifecrit', 'dex_proj'],
    'huntress':  ['hunt_spear', 'hunt_buckler', 'dex_proj', 'hyb_lifeeva', 'dex_crit', 'dex_travel', 'dex_accuracy',
                  'hunt_spear', 'dex_evasion', 'dex_flask', 'hyb_sword', 'dex_aps'],
    'mercenary': ['merc_crossbow', 'hyb_armoureva', 'merc_ammo', 'dex_trap', 'hyb_sword', 'hyb_physical', 'str_life',
                  'merc_crossbow', 'str_fireres', 'dex_accuracy', 'hyb_allres', 'str_twohand'],
    'marauder':  ['str_life', 'str_melee', 'str_armour', 'str_axe', 'str_mace', 'str_twohand', 'str_slam', 'str_warcry',
                  'str_block', 'str_fireres', 'str_life', 'str_melee', 'hyb_physical', 'str_regen'],
    'templar':   ['hyb_armoures', 'hyb_firelife', 'int_minion', 'hyb_staff', 'hyb_aura', 'int_totem', 'str_regen',
                  'str_block', 'hyb_allres', 'int_elemental', 'hyb_lifemana', 'str_life', 'int_es', 'hyb_ignite'],
}

# 基石：照角度（KEYSTONE_ANGLES 的順序：180 度開始順時針，每一區的中間與交界各一個）
KEYSTONES = [
    # 180：野蠻人與力量＋智慧的交界
    ('擴域', [inc('skill.area', 50), more('damage', -15, ['area'])], ['增加 50% 範圍效果', '更少 15% 範圍傷害']),
    # 157.5：力量＋智慧
    ('混沌免疫', [flat('flag.chaos_immune', 1)], ['免疫混沌傷害', '最大生命變為 1']),
    # 135
    ('靈魂之盾', [more('es.max', 30), more('life.max', -20)], ['更多 30% 最大能量護盾', '更少 20% 最大生命']),
    # 112.5：女巫
    ('虛空之契', [more('damage', 30, ['chaos']), flat('resist.chaos', -30)], ['更多 30% 混沌傷害', '-30% 混沌抗性']),
    # 90
    ('元素過載', [more('damage', 25, ['elemental']), more('damage', -50, ['physical'])], ['更多 25% 元素傷害', '更少 50% 物理傷害']),
    # 67.5：女術士
    ('霜心', [inc('chill.effect', 50), more('freeze.buildup', 40), more('damage', -30, ['fire'])],
     ['增加 50% 冰緩效果', '更多 40% 凍結累積', '更少 30% 火焰傷害']),
    # 45
    ('疾行者', [more('evasion', 30), more('armour', -50), more('es.max', -20)], ['更多 30% 閃避', '更少 50% 護甲', '更少 20% 最大能量護盾']),
    # 22.5：武僧
    ('迅捷之刃', [more('attack.speed', 15), more('damage', -10, ['attack'])], ['更多 15% 攻擊速度', '更少 10% 攻擊傷害']),
    # 0
    ('箭雨', [flat('skill.projectiles', 2), more('damage', -25, ['projectile'])], ['多 2 個投射物', '更少 25% 投射物傷害']),
    # -22.5：遊俠
    ('玻璃大砲', [more('damage', 30), more('life.max', -30)], ['更多 30% 傷害', '更少 30% 最大生命']),
    # -45
    ('尋寶者', [inc('drop.rarity', 40), inc('drop.quantity', 20), more('damage', -15)],
     ['增加 40% 物品掉落稀有度', '增加 20% 物品掉落數量', '更少 15% 傷害']),
    # -67.5：女獵手
    ('堅韌小盾', [flat('block.chance', 15), more('life.max', -10)], ['+15% 格擋率', '更少 10% 最大生命']),
    # -90
    ('鐵壁反射', [flat('flag.iron_reflexes', 1)], ['閃避值全部轉為護甲']),
    # -112.5：傭兵
    ('沉重打擊', [more('attack.speed', -15), more('damage', 30, ['attack'])], ['更少 15% 攻擊速度', '更多 30% 攻擊傷害']),
    # -135
    ('必中', [flat('flag.always_hit', 1), flat('flag.no_crit', 1)], ['攻擊必定命中', '無法造成暴擊']),
    # -157.5：野蠻人
    ('狂戰之血', [more('damage', 40, ['melee', 'physical']), more('armour', -25), more('evasion', -25)],
     ['更多 40% 近戰物理傷害', '更少 25% 護甲與閃避']),
]
assert len(KEYSTONES) == len(KEYSTONE_ANGLES)

# 主題分配：每一區的群組照離起點（沒有職業的區：離區的中心方向）的距離排，依序拿這一區的主題
def sector_anchor_point(sid):
    cls = SECTOR[sid][2]
    if cls:
        return STARTS[cls]
    centre = SECTOR[sid][1]
    return ring_point(RINGS[1], centre)

normal = [c for c in clusters if c['shape'] not in ('keystone', 'socket') and c.get('fixed_theme') is None]
by_sector = {}
for c in normal:
    xs = [p[0] for p, _ in c['cells']]; ys = [p[1] for p, _ in c['cells']]
    c['sector'] = sector_of(sum(xs) / len(xs), sum(ys) / len(ys))
    by_sector.setdefault(c['sector'], []).append(c)
for c in ailment_clusters + crit_clusters:
    c['theme'] = c['fixed_theme']
use = {}
for sid, members in by_sector.items():
    origin = sector_anchor_point(sid)
    members.sort(key=lambda c: (math.hypot(c['anchor'][0] - origin[0], c['anchor'][1] - origin[1]), c['anchor']))
    pool = POOLS[sid]
    for i, c in enumerate(members):
        c['theme'] = pool[i % len(pool)]
for i, c in enumerate(keystone_slots):
    c['theme'] = ('keystone', i)

ATTR = {'str': ('attr.str', '力量'), 'dex': ('attr.dex', '敏捷'), 'int': ('attr.int', '智慧')}

def travel_node(x, y):
    sid = sector_of(x, y)
    attrs = SECTOR[sid][3]
    if len(attrs) == 2:
        a, b = attrs
        return {'name': f'{ATTR[a][1]}與{ATTR[b][1]}', 'stats': [
            {'stat': ATTR[a][0], 'type': 'FLAT', 'value': 5, 'tags': []},
            {'stat': ATTR[b][0], 'type': 'FLAT', 'value': 5, 'tags': []}],
            'description': [f'+5 {ATTR[a][1]}', f'+5 {ATTR[b][1]}'], 'icon': 'GRAY_STAINED_GLASS_PANE'}
    a = attrs[0]
    return {'name': ATTR[a][1], 'stats': [{'stat': ATTR[a][0], 'type': 'FLAT', 'value': 10, 'tags': []}],
            'description': [f'+10 {ATTR[a][1]}'], 'icon': 'GRAY_STAINED_GLASS_PANE'}

SMALL_ICON = 'GRAY_STAINED_GLASS_PANE'
NOTABLE_ICON = {'int': 'ENCHANTED_BOOK', 'str': 'IRON_CHESTPLATE', 'dex': 'FEATHER', 'hyb': 'AMETHYST_SHARD',
                'witch': 'WITHER_ROSE', 'merc': 'CROSSBOW', 'monk': 'BAMBOO', 'hunt': 'IRON_SPEAR'}
CLASS_NAMES = {'marauder': '野蠻人', 'mercenary': '傭兵', 'huntress': '女獵手', 'ranger': '遊俠', 'monk': '武僧',
               'sorceress': '女術士', 'witch': '女巫'}

cluster_by_id = {c['id']: c for c in clusters}
notable_uses = {}
nodes = []
for (x, y), info in cells.items():
    kind = info['kind']
    node = {'id': f'n{x}_{y}'.replace('-', 'm'), 'x': x, 'y': y}
    if kind == 'center':
        node.update({'type': 'notable', 'name': '流放者之心', 'icon': 'BEACON',
                     'stats': [{'stat': 'attr.str', 'type': 'FLAT', 'value': 10, 'tags': []},
                               {'stat': 'attr.dex', 'type': 'FLAT', 'value': 10, 'tags': []},
                               {'stat': 'attr.int', 'type': 'FLAT', 'value': 10, 'tags': []},
                               {'stat': 'life.max', 'type': 'INCREASED', 'value': 0.06, 'tags': []}],
                     'description': ['+10 所有屬性', '增加 6% 最大生命']})
    elif kind.startswith('start_'):
        cls = kind[6:]
        node.update({'type': 'start', 'name': CLASS_NAMES[cls] + '起點', 'icon': 'BEACON', 'start': cls,
                     'stats': [], 'description': ['職業起點，自動配置']})
    elif kind == 'travel':
        node.update({'type': 'small'})
        node.update(travel_node(x, y))
    elif kind == 'socket':
        node.update({'type': 'socket', 'name': '珠寶孔', 'icon': 'AMETHYST_SHARD', 'stats': [],
                     'description': ['配了這一點之後，點它把背包裡的珠寶放進去', '珠寶的詞綴會加在角色身上；再點一次拿回來']})
    else:
        c = cluster_by_id[info['cluster']]
        if c['shape'] == 'keystone':
            if kind == 'keystone':
                name, mods, desc = KEYSTONES[c['theme'][1]]
                node.update({'type': 'keystone', 'name': name, 'icon': 'NETHER_STAR',
                             'stats': [m for m, _ in mods], 'description': desc})
            else:
                node.update({'type': 'small'})
                node.update(travel_node(x, y))
        else:
            theme = THEMES[c['theme']]
            if kind == 'small':
                node.update({'type': 'small', 'icon': SMALL_ICON, 'stats': [m for m, _ in theme['small']],
                             'description': [t for _, t in theme['small'] if t]})
                node['name'] = node['description'][0]
            else:
                k = notable_uses.get(c['theme'], 0)
                notable_uses[c['theme']] = k + 1
                name, mods = theme['notables'][k % len(theme['notables'])]
                node.update({'type': 'notable', 'name': name,
                             'icon': NOTABLE_ICON.get(c['theme'].split('_')[0], 'AMETHYST_SHARD'),
                             'stats': [m for m, _ in mods], 'description': [t for _, t in mods if t]})
    nodes.append(node)

# 同名的核心：第二個起加上「II」「III」，搜尋時分得出來
seen_names = {}
for n in nodes:
    if n['type'] == 'notable' and n['name'] != '流放者之心':
        k = seen_names.get(n['name'], 0) + 1
        seen_names[n['name']] = k
        if k > 1:
            n['name'] += ' ' + 'I' * k if k <= 3 else f' {k}'

# ---------------- 檢查 ----------------
pos = {(n['x'], n['y']): n for n in nodes}
def neighbours(p):
    return [q for q in nbrs(p) if q in pos]
for cls, p in STARTS.items():
    if len(neighbours(p)) != 1:
        raise SystemExit(f'{cls} 的起點不是葉子：{neighbours(p)}')
seen = set()
stack = [(0, 0)]
while stack:
    p = stack.pop()
    if p in seen:
        continue
    seen.add(p)
    stack.extend(neighbours(p))
if len(seen) != len(nodes):
    missing = [p for p in pos if p not in seen]
    raise SystemExit(f'有 {len(missing)} 個節點連不到：{missing[:10]}')

tree = {
    'version': VERSION,
    'starts': {cls: pos[p]['id'] for cls, p in STARTS.items()},
    'nodes': nodes,
}
with open(OUT, 'w', encoding='utf-8') as f:
    json.dump(tree, f, ensure_ascii=False, indent=1)

# ---------------- 報告與 ASCII 預覽 ----------------
xs = [n['x'] for n in nodes]; ys = [n['y'] for n in nodes]
counts = {}
for n in nodes:
    counts[n['type']] = counts.get(n['type'], 0) + 1
print('節點數', len(nodes), counts, '範圍 x', min(xs), max(xs), 'y', min(ys), max(ys))
theme_count = {}
for c in normal:
    theme_count[c['theme']] = theme_count.get(c['theme'], 0) + 1
print('群組', len(normal), '形狀', {s: sum(1 for c in normal if c['shape'] == s) for s in SHAPES}, '珠寶孔', socket_count)
print('每區群組', {sid: len(m) for sid, m in by_sector.items()})
print('異常狀態群組', [(c['fixed_theme'], c['sector'], c['shape'], c['anchor']) for c in ailment_clusters])
print('暴擊群組', [(c['fixed_theme'], c['sector'], c['shape'], c['anchor']) for c in crit_clusters])
print('起點', STARTS)
if '--map' in sys.argv:
    letters = {'marauder': 'M', 'mercenary': 'E', 'huntress': 'H', 'ranger': 'R', 'monk': 'O', 'sorceress': 'S', 'witch': 'W'}
    sym = {'small': 'o', 'notable': 'N', 'keystone': 'K', 'socket': 'J'}
    for y in range(min(ys), max(ys) + 1):
        row = ''
        for x in range(min(xs), max(xs) + 1):
            n = pos.get((x, y))
            if n is None:
                row += ' .'
            elif n['type'] == 'start':
                row += ' ' + letters[n['start']]
            elif (x, y) == (0, 0):
                row += ' C'
            elif n['type'] == 'small' and cells[(x, y)]['cluster'] is not None:
                row += ' s'
            else:
                row += ' ' + sym[n['type']]
        print(f'{y:4d} ' + row)
