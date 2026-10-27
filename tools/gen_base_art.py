#!/usr/bin/env python3
"""
2026-10-21：一般裝備（普通／魔法／稀有）、地圖、藥劑、護符、珠寶的 16×16 圖，整份寫到 src/main/resources/web/base_art.txt。

- 武器、防具、盾、箭袋、飾品：拿同一種類（弓、弩、單手錘、頭盔、戒指…）的傳奇圖當形狀，換成「沒有魔力」的顏色：
  照名稱前兩個字的材質（天鐵、炎晶、曜石、古王…）上色，木頭、皮革的部分留著，發光、寶石的部分換成材質的點綴色。
  同一種類的基底各挑不同的傳奇當形狀（照代號的雜湊），所以同類的基底長得不一樣。
- 地圖：一塊刻著星座的石板，邊框照星圖的第幾圈上色（第 1 圈青、2 綠、3 紫、4 橘、5 紅），星座照代號隨機。
- 藥劑：四種瓶子（照階級：小瓶、圓瓶、高瓶、星瓶），生命紅、魔力藍；瓶口的金屬照階級換。
- 護符：2026-10-27 起每種一張：掛在細繩上的吊飾，形狀照作用（六角冰晶、水滴、避雷針、心、葉、方石、鱗、羽毛、金幣、新月），中間的記號照它對付的東西。
- 珠寶：2026-10-27 起每種基底一張：赤星晶圓形明亮式切工＋四爪金托、翠星晶階梯切工＋銀包邊、蒼星晶水滴切工＋吊環、白星晶鑽石形；
  遺時的同一種切工縮小放進古銅錶盤環；永恆珠寶是暗金日輪中間一顆六角暗紫寶石。

只改珠寶、護符（不動其他已經畫好的圖）：python3 tools/gen_base_art.py --only jewel,charm（把現有 base_art.txt 裡那幾張換掉、缺的補在最後）。

每次整份重寫（不是附加），改完直接跑：python3 tools/gen_base_art.py；資源包重開伺服器時重新產生。
"""
import colorsys
import hashlib
import math
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from gen_unique_art import parse, KIND_TAGS, FRAMES  # noqa: E402
except ImportError:  # 2026-10-27：只換珠寶、護符（--only）用不到傳奇圖，沒有 gen_unique_art.py 也能跑
    parse, KIND_TAGS, FRAMES = None, set(), []

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


def gear(sprites, uniques, bases):
    def kind(b):
        tags = b.get('tags') or []
        if 'sceptre' in tags:  # 2026-10-26：PoE2 的權杖不是錘了（拿掉 mace 標籤），形狀還是跟權杖的傳奇
            return (b.get('slot'), 'sceptre')
        return (b.get('slot'),) + tuple(sorted(t for t in tags if t in KIND_TAGS))
    pools = {}
    for uid, u in uniques.items():
        if isinstance(u, dict) and uid in sprites and u.get('base') in bases:
            pools.setdefault(kind(bases[u['base']]), []).append(uid)
            pools.setdefault(('slot', bases[u['base']].get('slot')), []).append(uid)
    blocks = []
    missing = []
    for bid, b in bases.items():
        if b.get('retired') or b.get('slot') in ('MAP', 'JEWEL'):  # 2026-10-27：珠寶另外畫（jewel_art）
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


# ---- 小畫布（珠寶、護符用；2026-10-27）----

class Pix:
    """16×16 的格子，每格一個調色盤字元；outline() 把貼著填色的空格畫成外框 o。"""

    def __init__(self):
        self.g = [['.'] * 16 for _ in range(16)]

    def put(self, x, y, ch):
        if 0 <= x < 16 and 0 <= y < 16:
            self.g[y][x] = ch

    def get(self, x, y):
        return self.g[y][x] if 0 <= x < 16 and 0 <= y < 16 else '.'

    def fill(self, cells, ch):
        for x, y in cells:
            self.put(x, y, ch)

    def stamp(self, x0, y0, rows, mapping):
        for dy, row in enumerate(rows):
            for dx, c in enumerate(row):
                if c in mapping:
                    self.put(x0 + dx, y0 + dy, mapping[c])

    def outline(self, ch='o'):
        out = [r[:] for r in self.g]
        for y in range(16):
            for x in range(16):
                if self.g[y][x] == '.' and any(self.get(x + dx, y + dy) not in '.' + ch
                                               for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    out[y][x] = ch
        self.g = out

    def rows(self):
        return [''.join(r) for r in self.g]


def cells_disc(cx, cy, r):
    return {(x, y) for y in range(16) for x in range(16) if (x - cx) ** 2 + (y - cy) ** 2 <= r * r}


def cells_ring(cx, cy, rin, rout):
    return {(x, y) for y in range(16) for x in range(16) if rin * rin <= (x - cx) ** 2 + (y - cy) ** 2 <= rout * rout}


def cells_poly(pts):
    out = set()
    for y in range(16):
        for x in range(16):
            px, py = x + 0.5, y + 0.5
            hit = False
            j = len(pts) - 1
            for i in range(len(pts)):
                xi, yi = pts[i]
                xj, yj = pts[j]
                if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                    hit = not hit
                j = i
            if hit:
                out.add((x, y))
    return out


def cells_line(x0, y0, x1, y1):
    steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
    return {(round(x0 + (x1 - x0) * i / steps), round(y0 + (y1 - y0) * i / steps)) for i in range(steps + 1)}


def shade(p, cells, base, light, dark):
    """填一塊並自動加亮面（上、左是邊）與暗面（下、右是邊）。"""
    for x, y in cells:
        if (x, y - 1) not in cells or (x - 1, y) not in cells:
            p.put(x, y, light)
        elif (x, y + 1) not in cells or (x + 1, y) not in cells:
            p.put(x, y, dark)
        else:
            p.put(x, y, base)


# ---- 珠寶：每種基底一張（2026-10-27）----
# 站長：「珠寶要用材質包去畫」。以前是拿傳奇珠寶的圖換色（四種長得一樣），現在每種基底各畫一顆：
#   赤星晶：圓形明亮式切工（八個斜面），四爪金托；翠星晶：長方的階梯切工（祖母綠切工），銀色包邊；
#   蒼星晶：水滴（梨形）切工，頂上銀帽＋吊環；白星晶：鑽石的冠部＋亭部，兩邊白金爪，旁邊一顆閃光；
#   遺時（半徑珠寶）：同一種切工縮小，外面套一圈有刻度的古銅環（像錶盤，表示「範圍」），寶石顏色舊一點；
#   永恆珠寶：六角的暗紫寶石，外面一圈帶光芒的暗金日輪。
# 調色盤：o 外框、W 閃光、T 檯面、H 亮面、L 淺面、G 本色、D 暗面、R 最暗的邊；m／M／n 鑲座金屬（本色／亮／暗）；b／B／c 古銅環。

def gem_palette(hue, sat=0.80, aged=False, pale=False):
    k = 0.75 if aged else 1.0
    if pale:  # 白星晶：近乎無色，帶一點冷光
        return {'W': 'ffffff', 'T': hls(0.55, 0.90, 0.30), 'H': hls(0.55, 0.84, 0.35), 'L': hls(0.58, 0.74, 0.30),
                'G': hls(0.60, 0.64, 0.22), 'D': hls(0.62, 0.48, 0.22), 'R': hls(0.64, 0.34, 0.25),
                'P': hls(0.90, 0.78, 0.70), 'Q': hls(0.48, 0.75, 0.70)}
    return {'W': hls(hue, 0.94, 0.5), 'T': hls(hue, 0.70, sat * k), 'H': hls(hue, 0.62, sat * k), 'L': hls(hue, 0.50, sat * k),
            'G': hls(hue, 0.40, sat * k), 'D': hls(hue, 0.28, sat * k), 'R': hls(hue, 0.18, sat * k)}


def facet_fill(p, cells, cx, cy, facets, table_r):
    """明亮式切工：中間的檯面亮、外圈照角度切成幾個斜面，左上亮、右下暗，最外一圈是暗邊。"""
    for x, y in cells:
        dx, dy = x + 0.5 - cx, y + 0.5 - cy
        d = math.hypot(dx, dy) or 0.01
        rim = any((x + ax, y + ay) not in cells for ax, ay in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        lit = -(dx + dy) / (d * 1.414)
        if d <= table_r:
            ch = 'T'
        else:
            sector = int((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * facets) % facets
            v = lit + (0.3 if sector % 2 else -0.3)
            ch = 'H' if v > 0.55 else 'L' if v > 0.0 else 'G' if v > -0.55 else 'D'
        if rim:
            ch = 'L' if lit > 0.6 else 'R' if lit < -0.2 else 'D'
        p.put(x, y, ch)


def step_fill(p, x0, y0, x1, y1, cut):
    """階梯切工（祖母綠）：一圈一圈往內，上、左的斜面亮、下、右暗，中間是檯面；cut 是切掉的角。"""
    cells = set()
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            k = min(x - x0, x1 - x, y - y0, y1 - y)
            corner = min(x - x0, x1 - x) + min(y - y0, y1 - y)
            if corner < cut:
                continue
            cells.add((x, y))
            top_left = min(x - x0, y - y0) <= min(x1 - x, y1 - y)
            if k == 0:
                ch = 'L' if top_left else 'R'
            elif k == 1:
                ch = 'H' if top_left else 'D'
            elif k == 2:
                ch = 'L' if top_left else 'G'
            else:
                ch = 'T'
            p.put(x, y, ch)
    return cells


def jewel_cut(p, kind, cx, cy, s):
    """畫一顆寶石（s 是大小：1 一般、0.7 縮小放進遺時的環裡）；回傳寶石佔的格子。"""
    if kind == 'ruby':
        cells = cells_disc(cx, cy, 4.6 * s)
        facet_fill(p, cells, cx + 0.5, cy + 0.5, 8, 1.6 * s)
        return cells
    if kind == 'emerald':
        hw, hh = round(4 * s), round(5 * s)
        x0, y0, x1, y1 = round(cx - hw + 0.5), round(cy - hh + 0.5), round(cx + hw - 0.5), round(cy + hh - 0.5)
        return step_fill(p, x0, y0, x1, y1, 2 if s >= 1 else 1)
    if kind == 'sapphire':
        r = 4.2 * s
        by = cy + 1.6 * s
        cells = cells_disc(cx, by, r) | cells_poly([(cx - r * 0.82 + 0.5, by - r * 0.4), (cx + 0.5, by - r * 1.9), (cx + r * 0.82 + 0.5, by - r * 0.4)])
        facet_fill(p, cells, cx + 0.5, by + 0.3, 6, 1.3 * s)
        return cells
    if kind == 'diamond':
        w, top, girdle, bottom = 5.5 * s, cy - 4.5 * s, cy - 1.5 * s, cy + 6 * s
        crown = cells_poly([(cx - w * 0.55 + 0.5, top), (cx + w * 0.55 + 0.5, top), (cx + w + 0.5, girdle), (cx - w + 0.5, girdle)])
        pav = cells_poly([(cx - w + 0.5, girdle), (cx + w + 0.5, girdle), (cx + 0.5, bottom)])
        cells = crown | pav
        for x, y in cells:
            band = int((x + 0.5 - (cx + 0.5)) / (w / 3) + 3)  # 由左到右六條斜面
            if (x, y) in crown:
                ch = ['H', 'T', 'W', 'T', 'L', 'G'][max(0, min(5, band))]
            else:
                ch = ['L', 'H', 'G', 'L', 'D', 'R'][max(0, min(5, band))]
            p.put(x, y, ch)
        for x in range(16):  # 腰線
            if (x, round(girdle)) in cells:
                p.put(x, round(girdle), 'G' if x < cx else 'D')
        return cells
    raise ValueError(kind)


JEWEL_STYLE = {  # 標籤 → (切工, 色相)
    'ruby': ('ruby', 0.985), 'emerald': ('emerald', 0.38), 'sapphire': ('sapphire', 0.62),
}


def jewel_art(jid, base):
    tags = set(base.get('tags') or [])
    colours = [t.replace('r_', '') for t in tags if t.replace('r_', '') in JEWEL_STYLE]
    p = Pix()
    metal = {'m': hls(0.60, 0.70, 0.10), 'M': hls(0.60, 0.88, 0.12), 'n': hls(0.62, 0.48, 0.12)}       # 銀
    gold = {'m': hls(0.12, 0.52, 0.75), 'M': hls(0.13, 0.72, 0.85), 'n': hls(0.10, 0.34, 0.70)}        # 金
    bronze = {'b': hls(0.08, 0.40, 0.45), 'B': hls(0.09, 0.62, 0.55), 'c': hls(0.07, 0.24, 0.45)}      # 古銅（遺時的環）
    if 'timeless' in tags:
        # 永恆：暗金日輪（八道光芒）＋六角暗紫寶石，中間一點亮光
        rays = set()
        for i in range(8):
            a = i * math.pi / 4 + math.pi / 8
            rays |= cells_line(7.5 + math.cos(a) * 4.0, 7.5 + math.sin(a) * 4.0, 7.5 + math.cos(a) * 7.2, 7.5 + math.sin(a) * 7.2)
        p.fill({(round(x), round(y)) for x, y in rays}, 'm')
        shade(p, cells_ring(7.5, 7.5, 3.6, 5.3), 'm', 'M', 'n')
        hexa = cells_poly([(7.5 + math.cos(a) * 3.8 + 0.5, 7.5 + math.sin(a) * 3.8 + 0.5)
                           for a in [i * math.pi / 3 + math.pi / 6 for i in range(6)]])
        facet_fill(p, hexa, 8, 8, 6, 1.0)
        p.put(7, 7, 'W')
        p.put(8, 7, 'T')
        p.outline()
        pal = dict(gem_palette(0.82, 0.55), **{'m': hls(0.11, 0.38, 0.60), 'M': hls(0.12, 0.60, 0.75), 'n': hls(0.10, 0.24, 0.55)})
        pal['o'] = '120c10'
        return block(jid, pal, p.rows())
    kind = JEWEL_STYLE[colours[0]][0] if len(colours) == 1 else 'diamond'
    hue = JEWEL_STYLE[colours[0]][1] if len(colours) == 1 else 0.6
    radius = 'radius' in tags
    s = 0.62 if radius else 1.0
    if radius:
        # 遺時：一圈古銅錶盤（四個亮的刻度，左上亮、右下暗），寶石縮小放中間
        ring = cells_ring(7.5, 7.5, 5.6, 7.4)
        for x, y in ring:
            lit = -((x - 7.5) + (y - 7.5))
            p.put(x, y, 'B' if lit > 6 else 'c' if lit < -6 else 'b')
        for x, y in ((7, 1), (8, 1), (1, 7), (1, 8), (14, 7), (14, 8), (7, 14), (8, 14)):
            p.put(x, y, 'B')
    if kind == 'ruby':
        cells = jewel_cut(p, 'ruby', 7.5 if not radius else 7.0, 7.5 if not radius else 7.0, s)
        if not radius:
            for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):  # 四爪
                p.fill({(x, y), (x + (1 if x < 8 else -1), y), (x, y + (1 if y < 8 else -1))}, 'm')
                p.put(x, y, 'M' if x < 8 and y < 8 else 'n' if x > 8 and y > 8 else 'm')
        else:
            p.fill({(7, 3), (8, 3)}, 'm')
        metal = gold
    elif kind == 'emerald':
        if not radius:
            frame = {(x, y) for y in range(1, 15) for x in range(2, 14)
                     if min(x - 2, 13 - x) + min(y - 1, 14 - y) >= 2}
            shade(p, frame, 'm', 'M', 'n')
            jewel_cut(p, 'emerald', 7.5, 7.5, 1.0 * 0.95)
            for x, y in ((2, 7), (13, 7)):  # 兩側的小鉚釘
                p.put(x, y, 'M')
        else:
            jewel_cut(p, 'emerald', 7.5, 7.5, s)
    elif kind == 'sapphire':
        if not radius:
            jewel_cut(p, 'sapphire', 7.5, 7.0, 1.0)
            p.fill({(7, 0), (8, 0)}, 'n')          # 吊環
            p.fill({(6, 1), (9, 1)}, 'n')
            p.fill({(7, 1), (8, 1)}, '.')
            p.fill({(7, 2), (8, 2), (6, 3), (9, 3)}, 'm')
            p.put(7, 2, 'M')
        else:
            jewel_cut(p, 'sapphire', 7.5, 7.5, s)
    else:
        if not radius:
            jewel_cut(p, 'diamond', 7.5, 7.5, 1.0)
            p.fill({(1, 6), (1, 5), (14, 6), (14, 5)}, 'm')   # 白金爪
            p.put(1, 5, 'M')
            p.put(14, 5, 'n')
            for x, y in ((1, 1), (2, 0), (2, 2), (3, 1), (2, 1)):   # 閃光
                p.put(x, y, 'W' if (x, y) == (2, 1) else 'Q')
            p.put(13, 13, 'P')
        else:
            jewel_cut(p, 'diamond', 7.5, 7.5, 0.72)
    p.outline()
    pal = gem_palette(hue, aged=radius, pale=(kind == 'diamond'))
    pal.update(gold if kind == 'ruby' else metal)
    pal.update(bronze)
    pal['o'] = '14100e'
    rows = p.rows()
    if kind == 'diamond':  # 閃光不要外框
        rows = [''.join('.' if ch == 'o' and (x, y) in {(1, 0), (3, 0), (0, 1), (4, 1), (1, 2), (3, 2), (2, 3), (0, 0), (4, 0), (0, 2), (4, 2)} and not radius else ch
                        for x, ch in enumerate(r)) for y, r in enumerate(rows)]
    return block(jid, pal, rows)


# ---- 護符：每種一張（2026-10-27）----
# 站長：「護符要用材質包去畫」。以前所有護符都是同一個吊墜換顏色、中間換紋樣，跟藥劑也不好分。現在：
#   每個護符都掛在一條細繩上（藥劑是瓶子，護符是吊飾），本體的形狀照「解什麼」或「擋什麼」換，中間的記號照它對付的東西：
#   融冰＝暖橘的六角冰晶，中間雪花；澆熄＝藍色水滴，中間火焰；避雷＝銅色避雷針（倒三角），中間閃電；
#   凝血＝深紅的心，綁一條白繃帶；祛毒＝綠葉，中間毒滴；穩身＝方形灰石，刻著同心的符；
#   焰鱗／霜鱗／雷鱗／蝕鱗＝一片鱗（上圓下尖，有鱗紋），火紅／冰藍／金黃／暗紫，各刻火焰、雪花、閃電、漩渦；
#   迅影＝銀色羽毛；聚寶＝金幣刻星；滌咒＝淡紫的新月加一點星光；續命（退役）＝年輪木片冒新芽。
# 調色盤：k／K 繩子；a／A／z 本體（本色／亮／暗）；E／F 記號（亮／暗）；x／X 第二種材質（繃帶、新芽、鱗紋…）；o 外框。

CORD = ['....kkkkkk......', '...k......K.....', '...k......K.....', '....k....K......']


EMBLEM_ART = {
    'flake': ['E.E.E', '.EEE.', 'EEFEE', '.EEE.', 'E.E.E'],
    'flame': ['..E..', '.EE..', '.EFE.', 'EFFEE', '.EEE.'],
    'bolt': ['...EE', '..EE.', '.EEEE', '..EE.', '.EE..', 'EE...'],
    'drop': ['..E..', '.EEE.', 'EEFEE', 'EEEEE', '.EEE.'],
    'swirl': ['.EEE.', 'E...E', 'E.E.E', 'E..EE', '.E...'],
    'rune': ['EEEEE', 'E...E', 'E.F.E', 'E...E', 'EEEEE'],
    'star': ['..E..', '.EFE.', 'EFFFE', '.EFE.', '..E..'],
    'sparkle': ['.E.', 'EFE', '.E.'],
}


def charm_art(cid, c):
    trig = c.get('trigger')
    p = Pix()
    p.stamp(0, 0, CORD, {'k': 'k', 'K': 'K'})
    sat = 0.6
    if trig == 'freeze':        # 融冰：六角冰晶形，暖橘色，中間雪花
        body = cells_poly([(8, 3.5), (13.5, 6.5), (13.5, 12), (8, 15.5), (2.5, 12), (2.5, 6.5)])
        bh, eh, emblem, at = 0.07, 0.53, 'flake', (5, 7)
    elif trig == 'ignite':      # 澆熄：水滴，藍色，中間火焰
        body = cells_disc(7.5, 10.5, 4.6) | cells_poly([(3.6, 9), (8, 3.2), (12.4, 9)])
        bh, eh, emblem, at = 0.58, 0.06, 'flame', (5, 8)
    elif trig == 'shock':       # 避雷：銅色避雷針（倒三角），中間閃電
        body = cells_poly([(2, 4), (14, 4), (8, 16)]) | {(x, y) for x in (7, 8) for y in (3,)}
        bh, eh, emblem, at = 0.07, 0.15, 'bolt', (5, 5)
    elif trig == 'bleed':       # 凝血：深紅的心，橫綁一條白繃帶
        body = cells_disc(5, 7, 3.2) | cells_disc(10.5, 7, 3.2) | cells_poly([(1.8, 8), (13.7, 8), (8, 15)])
        bh, eh, emblem, at = 0.99, 0.0, None, None
    elif trig == 'poison':      # 祛毒：綠葉，中間毒滴
        body = cells_poly([(8, 3.5), (12.5, 7), (13, 11), (8, 15.6), (3, 11), (3.5, 7)])
        bh, eh, emblem, at = 0.32, 0.25, 'drop', (5, 8)
    elif trig == 'stun':        # 穩身：方形灰石，刻同心符
        body = {(x, y) for x in range(3, 13) for y in range(4, 15)} - {(3, 4), (12, 4), (3, 14), (12, 14)}
        bh, eh, emblem, at, sat = 0.10, 0.12, 'rune', (5, 7), 0.10
    elif trig in ('hit_fire', 'hit_cold', 'hit_lightning', 'hit_chaos'):   # 鱗：上圓下尖
        body = cells_disc(7.5, 8, 5.2) | cells_poly([(2.5, 9), (12.5, 9), (8, 15.8)])
        body = {(x, y) for x, y in body if y >= 3}
        bh, eh, emblem = {'hit_fire': (0.02, 0.10, 'flame'), 'hit_cold': (0.58, 0.52, 'flake'),
                          'hit_lightning': (0.13, 0.15, 'bolt'), 'hit_chaos': (0.80, 0.78, 'swirl')}[trig]
        at = (5, 7) if emblem != 'bolt' else (5, 6)
    elif trig == 'slow':        # 迅影：銀色羽毛（斜的）
        body = cells_poly([(4, 15), (6, 9), (10, 4), (13, 3.5), (12, 7), (8, 12)])
        bh, eh, emblem, at, sat = 0.58, 0.55, None, None, 0.10
    elif trig == 'kill_rare':   # 聚寶：金幣刻星
        body = cells_disc(7.5, 9.5, 5.6)
        bh, eh, emblem, at, sat = 0.12, 0.12, 'star', (5, 7), 0.75
    elif trig == 'curse':       # 滌咒：淡紫新月＋星光
        body = cells_disc(7.5, 9.5, 5.6) - cells_disc(10.5, 8, 4.4)
        bh, eh, emblem, at = 0.76, 0.14, 'sparkle', (10, 8)
    else:                       # 續命（退役）：年輪木片冒新芽
        body = cells_disc(7.5, 10, 5.2)
        bh, eh, emblem, at, sat = 0.08, 0.33, None, None, 0.45
    shade(p, body, 'a', 'A', 'z')
    if trig == 'bleed':         # 繃帶：斜的一條，帶十字
        for x, y in cells_line(2, 11, 13, 6) | cells_line(2, 12, 13, 7):
            if (x, y) in body:
                p.put(x, y, 'x')
        p.fill({(7, 8), (8, 9), (7, 9), (8, 8)}, 'X')
    elif trig in ('hit_fire', 'hit_cold', 'hit_lightning', 'hit_chaos'):   # 鱗紋：兩道弧
        for x, y in body:
            if y >= 12 and abs(math.hypot(x - 7.5, y - 9.5) - 4.6) < 0.5:
                p.put(x, y, 'z')
    elif trig == 'slow':        # 羽毛的羽軸與羽紋
        for x, y in cells_line(4, 14, 12, 4):
            p.put(x, y, 'x')
        for x, y in ((7, 8), (9, 7), (6, 11), (10, 5)):
            p.put(x, y, 'z')
    elif trig == 'curse':
        pass
    elif emblem is None:        # 年輪＋新芽
        for x, y in body:
            if abs(math.hypot(x - 7.5, y - 10) - 2.5) < 0.5:
                p.put(x, y, 'z')
        p.fill({(7, 3), (8, 2), (9, 2), (6, 2)}, 'x')
    if emblem:
        art = EMBLEM_ART[emblem]
        p.stamp(at[0] + (5 - len(art[0])) // 2 if emblem != 'sparkle' else at[0], at[1], art, {'E': 'E', 'F': 'F'})
    if trig == 'curse':
        p.stamp(11, 4, ['.E.', 'EFE', '.E.'], {'E': 'E', 'F': 'F'})
    p.outline()
    rows = p.rows()
    rows[:4] = [''.join(ch if ch != 'o' else '.' for ch in r) if y < 3 else r for y, r in enumerate(rows[:4])]  # 繩子不加外框
    pal = {
        'k': hls(0.08, 0.32, 0.35), 'K': hls(0.08, 0.22, 0.35),
        'a': hls(bh, 0.45, sat), 'A': hls(bh, 0.63, sat), 'z': hls(bh, 0.30, sat),
        'E': hls(eh, 0.85, 0.85), 'F': hls(eh, 0.60, 0.95),
        'x': hls(0.12, 0.93, 0.15) if trig != 'heartwood' else hls(0.33, 0.55, 0.6), 'X': hls(0.0, 0.55, 0.75),
        'o': '1a1512',
    }
    if trig == 'low_life':
        pal['x'] = hls(0.33, 0.50, 0.65)
    if trig == 'kill_rare':
        pal['E'] = hls(0.13, 0.88, 0.9)
        pal['F'] = hls(0.11, 0.40, 0.8)
    if trig == 'stun':
        pal['E'] = hls(0.55, 0.80, 0.6)
        pal['F'] = hls(0.55, 0.60, 0.8)
    return block(cid, pal, rows)


def splice(fresh):
    """2026-10-27：--only 用：把現有 base_art.txt 裡同代號的圖換成 fresh 的（前面的註解行留著），沒有的補在最後。"""
    fresh = dict(fresh)
    chunks = OUT.read_text(encoding='utf-8').rstrip('\n').split('\n\n')
    out = []
    for chunk in chunks:
        lines = chunk.split('\n')
        at = next((i for i, ln in enumerate(lines) if ln.startswith('item ')), None)
        if at is not None and lines[at][5:].strip() in fresh:
            chunk = '\n'.join(lines[:at] + [fresh.pop(lines[at][5:].strip())])
        out.append(chunk)
    out += list(fresh.values())
    OUT.write_text('\n\n'.join(out) + '\n', encoding='utf-8')


def main():
    bases = {}
    for f in sorted((DATA / 'bases').glob('*.yml')):
        for k, v in (yaml.safe_load(f.read_text(encoding='utf-8')) or {}).items():
            if isinstance(v, dict):
                bases[k] = v
    flasks_data = yaml.safe_load((DATA / 'flasks.yml').read_text(encoding='utf-8'))
    jewels = [jewel_art(k, v) for k, v in bases.items() if v.get('slot') == 'JEWEL' and not v.get('retired')]
    charms = [charm_art(k, v) for k, v in flasks_data['charms']['bases'].items()]
    if '--only' in sys.argv:
        parts = sys.argv[sys.argv.index('--only') + 1].split(',')
        fresh = [(b.split('\n')[0][5:], b) for b in (jewels if 'jewel' in parts else []) + (charms if 'charm' in parts else [])]
        splice(fresh)
        print('只換了', len(fresh), '張（', ','.join(parts), '）')
        return
    sprites = parse(UNIQUE_ART.read_text(encoding='utf-8'))
    uniques = yaml.safe_load((DATA / 'uniques.yml').read_text(encoding='utf-8'))
    blocks = gear(sprites, uniques, bases)
    gear_count = len([b for b in blocks if not any(b.split('\n')[0].endswith(s) for s in FRAMES)])
    maps = [map_art(k, v) for k, v in bases.items() if v.get('slot') == 'MAP' and not v.get('retired')]
    flasks = []
    for kind in ('life', 'mana'):
        ids = [k for k, v in flasks_data['bases'].items() if v.get('kind') == kind]
        ids.sort(key=lambda k: flasks_data['bases'][k].get('level', 1))
        for tier, fid in enumerate(ids):
            flasks.append(flask_art(fid, flasks_data['bases'][fid], tier, len(ids)))
    header = [
        '# 《星骸》一般裝備、地圖、藥劑、護符、珠寶的像素圖（16×16）。格式跟 unique_art.txt 一樣：item 基底代號；palette；16 行。',
        '# 由 tools/gen_base_art.py 整份產生（不要手改，改產生器再跑一次）。物品上用 custom_model_data 的字串 base:基底代號 選到它，',
        '# 沒套資源包的玩家看到原本的物品。',
    ]
    OUT.write_text('\n'.join(header) + '\n\n' + '\n\n'.join(blocks + jewels + maps + flasks + charms) + '\n', encoding='utf-8')
    print('基底圖：裝備', gear_count, '、珠寶', len(jewels), '、地圖', len(maps), '、藥劑', len(flasks), '、護符', len(charms))


if __name__ == '__main__':
    main()
