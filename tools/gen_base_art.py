#!/usr/bin/env python3
"""
2026-10-21：一般裝備（普通／魔法／稀有）、地圖、藥劑、護符、珠寶的 16×16 圖，整份寫到 src/main/resources/web/base_art.txt。

- 武器、防具、盾、箭袋、飾品、珠寶：拿同一種類（弓、弩、單手錘、頭盔、戒指…）的傳奇圖當形狀，換成「沒有魔力」的顏色：
  照名稱前兩個字的材質（天鐵、炎晶、曜石、古王…）上色，木頭、皮革的部分留著，發光、寶石的部分換成材質的點綴色。
  同一種類的基底各挑不同的傳奇當形狀（照代號的雜湊），所以同類的基底長得不一樣。
- 地圖：一塊刻著星座的石板，邊框照星圖的第幾圈上色（第 1 圈青、2 綠、3 紫、4 橘、5 紅），星座照代號隨機。
- 藥劑：四種瓶子（照階級：小瓶、圓瓶、高瓶、星瓶），生命紅、魔力藍；瓶口的金屬照階級換。
- 護符：吊墜，顏色與中間的紋樣照觸發條件（冰、火、電、毒…）。

每次整份重寫（不是附加），改完直接跑：python3 tools/gen_base_art.py；資源包重開伺服器時重新產生。
"""
import colorsys
import hashlib
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gen_unique_art import parse, KIND_TAGS, FRAMES  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / 'src/main/resources/data'
UNIQUE_ART = ROOT / 'src/main/resources/web/unique_art.txt'
OUT = ROOT / 'src/main/resources/web/base_art.txt'

# 材質：(金屬的色相, 飽和度, 亮度倍率), (點綴的色相, 飽和度)
THEMES = {
    '天鐵': ((0.58, 0.14, 1.10), (0.55, 0.45)), '炎晶': ((0.04, 0.32, 0.90), (0.06, 0.85)),
    '曜石': ((0.75, 0.18, 0.60), (0.78, 0.60)), '古王': ((0.12, 0.50, 1.00), (0.98, 0.60)),
    '秘銀': ((0.52, 0.18, 1.20), (0.50, 0.40)), '聖紋': ((0.13, 0.18, 1.25), (0.12, 0.70)),
    '龍骨': ((0.11, 0.22, 1.15), (0.02, 0.50)), '霜晶': ((0.55, 0.45, 1.15), (0.52, 0.70)),
    '星紋': ((0.65, 0.35, 0.70), (0.13, 0.75)), '雷紋': ((0.62, 0.15, 0.90), (0.15, 0.90)),
    '淵鋼': ((0.60, 0.25, 0.55), (0.48, 0.60)), '暗鋼': ((0.62, 0.08, 0.60), (0.62, 0.20)),
    '戰痕': ((0.08, 0.08, 0.85), (0.02, 0.45)), '寒鐵': ((0.58, 0.25, 0.95), (0.55, 0.50)),
    '星骸': ((0.70, 0.30, 0.65), (0.85, 0.60)), '金耀': ((0.13, 0.65, 1.10), (0.13, 0.90)),
    '銅鑲': ((0.07, 0.50, 0.95), (0.45, 0.50)), '赤銅': ((0.05, 0.55, 0.90), (0.05, 0.70)),
    '隕鐵': ((0.05, 0.12, 0.70), (0.08, 0.40)), '精鐵': ((0.60, 0.05, 1.00), (0.60, 0.15)),
}
# 名稱裡有這些字：點綴色（飾品、箭袋這些名字不是材質的）
HUES = [('炎', 0.04), ('焰', 0.05), ('烈', 0.07), ('赤', 0.99), ('血', 0.98), ('霜', 0.55), ('寒', 0.56), ('蒼', 0.58),
        ('藍', 0.62), ('月', 0.60), ('雷', 0.15), ('日', 0.12), ('金', 0.13), ('珀', 0.10), ('紫', 0.78), ('蝕', 0.80),
        ('毒', 0.30), ('翠', 0.38), ('青', 0.47), ('生', 0.33), ('星', 0.66), ('夢', 0.82), ('珠', 0.55), ('憶', 0.72)]
JEWEL_HUES = {'ruby': 0.99, 'emerald': 0.38, 'sapphire': 0.62}


def hexrgb(h):
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def tohex(r, g, b, alpha=''):
    return '%02x%02x%02x%s' % (round(max(0, min(1, r)) * 255), round(max(0, min(1, g)) * 255), round(max(0, min(1, b)) * 255), alpha)


def hls(h, l, s, alpha=''):
    return tohex(*colorsys.hls_to_rgb(h % 1.0, max(0, min(1, l)), max(0, min(1, s))), alpha)


def digest(text):
    return int(hashlib.md5(text.encode('utf-8')).hexdigest(), 16)


def block(name, pal, rows):
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), name
    used = set(''.join(rows)) - {'.'}
    return '\n'.join(['item ' + name] + ['  %s=%s' % (k, v) for k, v in pal.items() if k in used] + ['  ' + r for r in rows])


# ---- 裝備：傳奇圖換成沒有魔力的顏色 ----

def theme_of(base):
    name = base.get('name', '')
    if name[:2] in THEMES:
        return THEMES[name[:2]]
    for word, h in HUES:
        if word in name:
            return (0.6, 0.08, 1.0), (h, 0.65)
    return (0.6, 0.06, 1.0), (0.6, 0.15)


def recolor_gear(pal, base, jitter):
    (mh, ms, ml), (ah, as_) = theme_of(base)
    tags = set(base.get('tags') or [])
    req = base.get('requirements') or {}
    armour_like = base.get('slot') in ('HEAD', 'CHEST', 'LEGS', 'FEET', 'OFF_HAND') and 'quiver' not in tags
    leather = armour_like and req.get('dex', 0) > 0 and req.get('str', 0) == 0 and req.get('int', 0) == 0
    cloth = armour_like and req.get('int', 0) > 0 and req.get('str', 0) == 0 and req.get('dex', 0) == 0
    out = {}
    for k, hexc in pal.items():
        r, g, b = hexrgb(hexc)
        alpha = hexc[6:8] if len(hexc) > 6 else ''
        h, l, s = colorsys.rgb_to_hls(r, g, b)
        wood = 0.02 <= h <= 0.13 and s >= 0.18 and l <= 0.5
        # 2026-10-21：素一點——寶石、發光的點綴不上色，跟本體同一種材質（只亮一點點），亮度只留四階
        l = round(min(0.9, max(0.1, l)) / 0.2) * 0.2 if not wood else l
        if wood:
            out[k] = hls(h, l * (0.75 if ml < 0.7 else 1.0), s * 0.6, alpha)
        elif leather:
            out[k] = hls(0.07 + jitter * 0.5, l * 0.95 * (0.85 + 0.15 * ml), 0.30, alpha)
        elif cloth:
            out[k] = hls(ah + 0.02, l * (0.9 + 0.1 * ml), 0.22, alpha)
        else:
            out[k] = hls(mh + jitter, min(0.9, l * ml), ms * 0.6, alpha)
    return out


def plainify(pal, rows):
    """2026-10-21：拿掉傳奇圖上的光暈（半透明的像素）和飄在外面的亮點（周圍八格都沒有鄰居的像素）。"""
    grid = [list(r) for r in rows]
    for y in range(16):
        for x in range(16):
            ch = grid[y][x]
            if ch == '.':
                continue
            hexc = pal.get(ch, 'ffffff')
            if len(hexc) > 6 and int(hexc[6:8], 16) < 0x70:  # 只拿掉很淡的光暈
                grid[y][x] = '.'
    out = [row[:] for row in grid]
    for y in range(16):
        for x in range(16):
            if grid[y][x] == '.':
                continue
            if all(not (0 <= x + dx < 16 and 0 <= y + dy < 16) or grid[y + dy][x + dx] == '.'
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1))):  # 斜線（法杖的杆子）不算孤立
                out[y][x] = '.'
    return [''.join(r) for r in out]


def recolor_jewel(pal, base):
    tags = set(base.get('tags') or [])
    hues = [JEWEL_HUES[t.replace('r_', '')] for t in tags if t.replace('r_', '') in JEWEL_HUES]
    lost = 'radius' in tags
    out = {}
    for k, hexc in pal.items():
        r, g, b = hexrgb(hexc)
        alpha = hexc[6:8] if len(hexc) > 6 else ''
        h, l, s = colorsys.rgb_to_hls(r, g, b)
        if len(hues) != 1:  # 白星晶：近乎無色的晶體
            out[k] = hls(0.6, min(0.95, l * 1.1), 0.08 if s > 0.1 else s, alpha)
        elif s > 0.15:
            out[k] = hls(hues[0], l * (0.85 if lost else 1.0), min(1.0, s * (0.75 if lost else 0.9)), alpha)
        else:
            out[k] = hls(0.12 if lost else h, l, 0.5 if lost else s, alpha)
    return out


def gear(sprites, uniques, bases):
    kind = lambda b: (b.get('slot'),) + tuple(sorted(t for t in (b.get('tags') or []) if t in KIND_TAGS))
    pools = {}
    for uid, u in uniques.items():
        if isinstance(u, dict) and uid in sprites and u.get('base') in bases:
            pools.setdefault(kind(bases[u['base']]), []).append(uid)
            pools.setdefault(('slot', bases[u['base']].get('slot')), []).append(uid)
    blocks = []
    missing = []
    for bid, b in bases.items():
        if b.get('retired') or b.get('slot') == 'MAP':
            continue
        pool = sorted(set(pools.get(kind(b)) or pools.get(('slot', b.get('slot'))) or []))
        if not pool:
            missing.append(bid)
            continue
        d = digest(bid)
        tpl = pool[d % len(pool)]
        jitter = ((d >> 8) % 100 - 50) / 2000.0
        for suffix in [''] + FRAMES:
            if tpl + suffix not in sprites:
                continue
            pal, rows = sprites[tpl + suffix]
            if b.get('slot') == 'JEWEL':
                pal = recolor_jewel(pal, b)
            else:
                rows = plainify(pal, rows)
                pal = recolor_gear(pal, b, jitter)
            blocks.append(block(bid + suffix, pal, rows))
    if missing:
        print('沒有形狀可用（照舊用原版物品）：', missing)
    return blocks


# ---- 地圖：刻著星座的石板 ----

MAP_ROWS = [
    '................',
    '...bbbbbbbbb....',
    '..bTTTTTTTTTb...',
    '.bTtttttttttTb..',
    '.bTttttttttttb..',
    '.bTttttttttttb..',
    '.bTttttttttttb..',
    '.bTttttttttttb..',
    '.bTttttttttttb..',
    '.bTttttttttttb..',
    '.bTttttttttttb..',
    '.bTttttttttttb..',
    '.bTTtttttttTTb..',
    '..bTTTTTTTTTb...',
    '...bbbbbbbbb....',
    '................',
]
RING_HUES = {1: 0.48, 2: 0.28, 3: 0.76, 4: 0.07, 5: 0.98}


def line(grid, a, b, ch):
    (x0, y0), (x1, y1) = a, b
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        if grid[y0][x0] == 't':
            grid[y0][x0] = ch
        if (x0, y0) == (x1, y1):
            return
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def map_art(mid, base):
    ring = next((int(t[-1]) for t in base.get('tags') or [] if t.startswith('map_t')), 1)
    hue = RING_HUES.get(ring, 0.5)
    d = digest(mid)
    grid = [list(r) for r in MAP_ROWS]
    # 缺一角（照代號挑哪一角）
    corner = d % 4
    for (x, y) in [(1, 3), (2, 2), (1, 4)] if corner == 0 else [(13, 4), (12, 2), (13, 3)] if corner == 1 else \
            [(1, 11), (2, 13), (1, 12)] if corner == 2 else [(13, 11), (12, 13), (13, 12)]:
        grid[y][x] = '.'
    # 星座：4–5 顆星，相鄰的連線
    count = 4 + (d >> 3) % 2
    stars = []
    seed = d >> 5
    while len(stars) < count:
        x, y = 4 + seed % 8, 4 + (seed >> 4) % 7
        seed = (seed * 1103515245 + 12345) & 0xFFFFFFFF
        if all(abs(x - a) + abs(y - b) >= 3 for a, b in stars):
            stars.append((x, y))
    stars.sort()
    for a, b in zip(stars, stars[1:]):
        line(grid, a, b, 'l')
    for i, (x, y) in enumerate(stars):
        grid[y][x] = 'W' if i == (d >> 11) % count else 'S'
    # 圈數：右下角幾個小點
    for i in range(ring):
        x, y = 11 - i * 2, 11
        if grid[y][x] in 'tl':
            grid[y][x] = 'r'
    pal = {
        'b': hls(hue, 0.16, 0.45), 'T': hls(hue, 0.34, 0.55), 't': hls(hue, 0.17, 0.18),
        'l': hls(hue, 0.42, 0.55), 'S': hls(hue, 0.70, 0.85), 'W': hls(hue, 0.92, 0.9), 'r': hls(hue, 0.55, 0.7),
    }
    return block(mid, pal, [''.join(r) for r in grid])


# ---- 藥劑 ----

FLASKS = [
    [  # 小瓶
        '................', '......cccc......', '......cCCc......', '.....mmmmmm.....',
        '......owgo......', '......owgo......', '.....owgggo.....', '....owLLLLLo....',
        '....oHLLLLDo....', '...owHLLLLLDo...', '...oHLLLLLLDo...', '...oLLLLLLLDo...',
        '...oLLLLLLDDo...', '...oDLLLLDDDo...', '....oDDDDDDo....', '.....oooooo.....'],
    [  # 圓瓶
        '................', '......cccc......', '......cCCc......', '.....mmmmmm.....',
        '......owwo......', '......owgo......', '....oowggwoo....', '...owHLLLLLLo...',
        '..owHLLLLLLLDo..', '..oHLLLLLLLLDo..', '..oLLLLLLLLLDo..', '..oLLLLLLLLDDo..',
        '..oDLLLLLLLDDo..', '...oDLLLLLDDo...', '....ooDDDDoo....', '......oooo......'],
    [  # 高瓶
        '.......cc.......', '......cCCc......', '.....mMmmMm.....', '......owgo......',
        '......owgo......', '.....mmmmmm.....', '.....owHLLo.....', '....owHLLLLo....',
        '....oHLLLLDo....', '....oLLLLLDo....', '....oLLLLLDo....', '....oLLLLDDo....',
        '...mmoDLLDDomm..', '....oDDDDDDo....', '....mMMMMMMm....', '.....mmmmmm.....'],
    [  # 星瓶
        '.......cc.......', '......cCCc......', '.....mmmmmm.....', '......owgo......',
        '.....mowgom.....', '....oowHLLoo....', '...owHLLLLLDo...', '..owHLLssLLLDo..',
        '.oHLLLsSSsLLLDo.', '..oLLLLssLLLDo..', '..oDLLLLLLLDDo..', '...oDLLLLLDDo...',
        '....oDDLLDDo....', '.....oDDDDo.....', '......mMMm......', '.......mm.......'],
]
TRIMS = [(0.08, 0.25, 0.45), (0.58, 0.06, 0.70), (0.12, 0.60, 0.55), (0.55, 0.45, 0.70)]  # 銅、銀、金、星


def flask_art(fid, f, tier, tiers):
    shape = min(3, tier * 4 // max(1, tiers))
    life = f.get('kind') == 'life'
    hue = (0.995 + 0.003 * tier) % 1.0 if life else 0.62 - 0.008 * tier  # 生命一直是紅的、魔力藍到靛
    if 'molten' in fid:
        hue = 0.03 if life else 0.70
    th, ts, tl = TRIMS[shape]
    pal = {
        'o': hls(0.6, 0.18, 0.15), 'w': hls(0.6, 0.92, 0.3), 'g': hls(0.58, 0.72, 0.25, 'b0'),
        'L': hls(hue, 0.45, 0.85), 'D': hls(hue, 0.28, 0.85), 'H': hls(hue, 0.68, 0.9),
        'c': hls(0.08, 0.42, 0.45), 'C': hls(0.08, 0.28, 0.45),
        'm': hls(th, tl, ts), 'M': hls(th, tl * 0.65, ts),
        's': hls(0.14, 0.75, 0.9), 'S': hls(0.14, 0.95, 0.9),
    }
    return block(fid, pal, FLASKS[shape])


# ---- 護符 ----

CHARM_ROWS = [
    '.....nnnnnn.....', '....n......n....', '....n......n....', '.....n....n.....',
    '......mMMm......', '.....mMbbMm.....', '....mBBBBBBm....', '...mBbbbbbbBm...',
    '...mBbbbbbbBm...', '...mBbbbbbbBm...', '...mBbbbbbbBm...', '....mBBBBBBm....',
    '.....mBBBBm.....', '......mBBm......', '.......mm.......', '................',
]
# 中間的紋樣（6×4，畫在 x 5–10、y 7–10）
EMBLEMS = {
    'flake': ['.E..E.', '..EE..', '..EE..', '.E..E.'],
    'drop': ['..EE..', '.EEEE.', '.EEEE.', '..EE..'],
    'bolt': ['...EE.', '..EE..', '.EE...', 'EE....'],
    'cross': ['..EE..', 'EEEEEE', 'EEEEEE', '..EE..'],
    'ring': ['.EEEE.', 'E....E', 'E....E', '.EEEE.'],
    'eye': ['......', '.EEEE.', 'EE..EE', '.EEEE.'],
    'diamond': ['..EE..', '.EEEE.', '.EEEE.', '..EE..'],
}
CHARMS = {  # 觸發 → (紋樣, 寶石色相, 紋樣色相, 金屬是金的)
    'freeze': ('flake', 0.55, 0.52, False), 'ignite': ('drop', 0.04, 0.10, False), 'shock': ('bolt', 0.14, 0.15, False),
    'bleed': ('drop', 0.98, 0.0, False), 'poison': ('drop', 0.30, 0.28, False), 'stun': ('cross', 0.08, 0.10, False),
    'hit_fire': ('diamond', 0.02, 0.08, True), 'hit_cold': ('diamond', 0.60, 0.55, True), 'hit_lightning': ('diamond', 0.14, 0.12, True),
    'hit_chaos': ('diamond', 0.80, 0.78, True), 'hit_unique': ('eye', 0.60, 0.58, False), 'kill_rare': ('ring', 0.12, 0.13, True),
    'low_life': ('cross', 0.33, 0.98, True),
}


def charm_art(cid, c):
    emblem, gem_h, emb_h, gold = CHARMS.get(c.get('trigger'), ('diamond', 0.6, 0.6, False))
    grid = [list(r) for r in CHARM_ROWS]
    for dy, row in enumerate(EMBLEMS[emblem]):
        for dx, ch in enumerate(row):
            if ch == 'E':
                grid[7 + dy][5 + dx] = 'E'
    pal = {
        'n': hls(0.08, 0.30, 0.35), 'm': hls(0.12, 0.55, 0.65) if gold else hls(0.6, 0.62, 0.06),
        'M': hls(0.12, 0.35, 0.65) if gold else hls(0.6, 0.40, 0.06),
        'B': hls(gem_h, 0.30, 0.65), 'b': hls(gem_h, 0.45, 0.70), 'E': hls(emb_h, 0.82, 0.9),
    }
    return block(cid, pal, [''.join(r) for r in grid])


def main():
    sprites = parse(UNIQUE_ART.read_text(encoding='utf-8'))
    uniques = yaml.safe_load((DATA / 'uniques.yml').read_text(encoding='utf-8'))
    bases = {}
    for f in sorted((DATA / 'bases').glob('*.yml')):
        for k, v in (yaml.safe_load(f.read_text(encoding='utf-8')) or {}).items():
            if isinstance(v, dict):
                bases[k] = v
    blocks = gear(sprites, uniques, bases)
    gear_count = len([b for b in blocks if not any(b.split('\n')[0].endswith(s) for s in FRAMES)])
    maps = [map_art(k, v) for k, v in bases.items() if v.get('slot') == 'MAP' and not v.get('retired')]
    flasks_data = yaml.safe_load((DATA / 'flasks.yml').read_text(encoding='utf-8'))
    flasks = []
    for kind in ('life', 'mana'):
        ids = [k for k, v in flasks_data['bases'].items() if v.get('kind') == kind]
        ids.sort(key=lambda k: flasks_data['bases'][k].get('level', 1))
        for tier, fid in enumerate(ids):
            flasks.append(flask_art(fid, flasks_data['bases'][fid], tier, len(ids)))
    charms = [charm_art(k, v) for k, v in flasks_data['charms']['bases'].items()]
    header = [
        '# 《星骸》一般裝備、地圖、藥劑、護符、珠寶的像素圖（16×16）。格式跟 unique_art.txt 一樣：item 基底代號；palette；16 行。',
        '# 由 tools/gen_base_art.py 整份產生（不要手改，改產生器再跑一次）。物品上用 custom_model_data 的字串 base:基底代號 選到它，',
        '# 沒套資源包的玩家看到原本的物品。',
    ]
    OUT.write_text('\n'.join(header) + '\n\n' + '\n\n'.join(blocks + maps + flasks + charms) + '\n', encoding='utf-8')
    print('基底圖：裝備與珠寶', gear_count, '、地圖', len(maps), '、藥劑', len(flasks), '、護符', len(charms))


if __name__ == '__main__':
    main()
