#!/usr/bin/env python3
"""
2026-10-24：普通／魔法／稀有裝備在格子裡的「簡圖」（16×16），整份寫到 src/main/resources/web/plain_art.txt。

照種類一張（戒指、項鍊、腰帶、頭盔、胸甲、護腿、靴子、各種武器、盾、法器、箭袋），跟傳奇那種細緻的像素圖明顯不同：
深色粗外框＋淺灰金屬＋一個青色點綴（寶石、刃口），木頭、皮革的部分用淡褐色。
外框、亮面（左上）、暗面（右下）都由程式自動加：這裡只畫「填色的形狀」。

格式跟 unique_art.txt 一樣（item 代號；調色盤；16 行）。物品上用 custom_model_data 的字串 0 plain:種類 選到它，
只畫在格子裡；拿在手上、穿在身上照原版的樣子。

改完直接跑：python3 tools/gen_plain_art.py（加 --preview 另外存 tools/plain_art_preview.png 看效果）
"""
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'src/main/resources/web/plain_art.txt'
PREVIEW = ROOT / 'tools/plain_art_preview.png'
N = 16

# 材質：基本色、亮面、暗面（調色盤字元）
MATERIALS = {
    'm': ('cdd3d8', 'f4f6f8', '98a1a8'),   # 金屬（淺灰）
    'a': ('6fd3dc', 'c6f2f5', '3a9fab'),   # 點綴（青）
    'w': ('b48c5c', 'd8b585', '7c5c37'),   # 木頭（淡褐）
    'r': ('d0584c', 'f09a8f', '8f3128'),   # 力量（紅）
    'g': ('62b25a', 'a6dc9e', '3a7a35'),   # 敏捷（綠）
    'u': ('5b8fe0', 'a4c3f4', '34609f'),   # 智慧（藍）
    'f': ('e8553f', 'ffa08f', 'a12d1d'),   # 戒指寶石：火焰（紅）
    'i': ('5fb4f0', 'b8e2fb', '2f78b0'),   # 冰冷（藍）
    'z': ('f2cf3f', 'fff09a', 'b38f12'),   # 閃電（黃）
    'p': ('a55fd8', 'd3a8f2', '6c3299'),   # 混沌（紫）
    'c': ('d08a3e', 'f2be82', '8e5520'),   # 戒指圓寶石：物理（銅橘）
    'l': ('ef6f9e', 'ffb3cd', 'a83c64'),   # 生命（粉紅）
    'x': ('d9ccff', 'ffffff', '9a88d6'),   # 施法速度（淡紫白）
    'H': ('e9a52b', 'ffd88a', 'a36d0f'),   # 掉落稀有度（金）
    'B': ('9e2532', 'd0545f', '5e1119'),   # 箭袋：流血（暗紅）
}
OUTLINE = '2c3135'
KEYS = {'m': ('m', 'M', 'n'), 'a': ('a', 'A', 'b'), 'w': ('w', 'W', 'v'), 'r': ('r', 'R', 'q'), 'g': ('g', 'G', 'h'),
        'u': ('u', 'U', 't'), 'f': ('f', 'F', 'e'), 'i': ('i', 'I', 'j'), 'z': ('z', 'Z', 's'), 'p': ('p', 'P', 'y'),
        'c': ('c', 'C', 'd'), 'l': ('l', 'L', 'k'), 'x': ('x', 'X', 'E'), 'H': ('H', 'J', 'K'), 'B': ('B', 'D', 'Q')}


class Canvas:
    def __init__(self):
        self.px = [[None] * N for _ in range(N)]

    def dot(self, x, y, mat):
        if 0 <= x < N and 0 <= y < N:
            self.px[y][x] = mat

    def rect(self, x0, y0, x1, y1, mat):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.dot(x, y, mat)

    def disc(self, cx, cy, r, mat):
        for y in range(N):
            for x in range(N):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.dot(x, y, mat)

    def ring(self, cx, cy, rin, rout, mat):
        for y in range(N):
            for x in range(N):
                d = math.hypot(x - cx, y - cy)
                if rin <= d <= rout:
                    self.dot(x, y, mat)

    def line(self, x0, y0, x1, y1, mat, width=1):
        steps = max(abs(x1 - x0), abs(y1 - y0)) * 2 + 1
        for i in range(steps + 1):
            t = i / steps
            x = x0 + (x1 - x0) * t
            y = y0 + (y1 - y0) * t
            for dy in range(width):
                for dx in range(width):
                    self.dot(round(x) + dx - width // 2, round(y) + dy - width // 2, mat)

    def poly(self, pts, mat):
        ys = [p[1] for p in pts]
        for y in range(max(0, math.floor(min(ys))), min(N - 1, math.ceil(max(ys))) + 1):
            for x in range(N):
                if inside(pts, x, y):
                    self.dot(x, y, mat)

    def erase(self, x, y):
        if 0 <= x < N and 0 <= y < N:
            self.px[y][x] = None

    def rows(self):
        """外框（4 鄰沒填色的格子）＋亮面（上、左是空的）＋暗面（下、右是空的）。"""
        out = [['.'] * N for _ in range(N)]
        filled = lambda x, y: 0 <= x < N and 0 <= y < N and self.px[y][x] is not None
        for y in range(N):
            for x in range(N):
                mat = self.px[y][x]
                if mat is None:
                    if any(filled(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        out[y][x] = 'o'
                    continue
                base, light, dark = KEYS[mat]
                same = lambda xx, yy: filled(xx, yy) and self.px[yy][xx] == mat
                if not same(x, y - 1) or not same(x - 1, y):
                    out[y][x] = light
                elif not same(x, y + 1) or not same(x + 1, y):
                    out[y][x] = dark
                else:
                    out[y][x] = base
        return [''.join(r) for r in out]


def inside(pts, x, y):
    px, py = x + 0.5, y + 0.5
    hit = False
    j = len(pts) - 1
    for i in range(len(pts)):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
            hit = not hit
        j = i
    return hit


# ---- 各種類 ----

def ring():
    c = Canvas()
    c.ring(7.5, 10, 2.0, 4.6, 'm')
    c.poly([(5, 2), (10, 2), (12, 4), (7.5, 8), (3, 4)], 'a')
    return c


def amulet():
    c = Canvas()
    c.line(3, 1, 7, 7, 'm')
    c.line(12, 1, 8, 7, 'm')
    c.disc(7.5, 10.5, 3.6, 'm')
    c.disc(7.5, 10.5, 1.9, 'a')
    return c


def belt():
    c = Canvas()
    c.rect(1, 6, 14, 9, 'w')
    c.rect(6, 5, 9, 10, 'm')
    c.rect(7, 7, 8, 8, 'a')
    return c


def helmet():
    c = Canvas()
    c.poly([(3, 12), (3, 6), (5, 3), (10, 3), (12, 6), (12, 12), (10, 12), (10, 9), (5, 9), (5, 12)], 'm')
    c.rect(7, 2, 8, 3, 'a')
    return c


def body():
    c = Canvas()
    c.poly([(1, 4), (5, 2), (7, 4), (8, 4), (10, 2), (14, 4), (13, 8), (12, 7), (12, 13), (3, 13), (3, 7), (2, 8)], 'm')
    c.rect(7, 7, 8, 8, 'a')
    return c


def leggings():
    c = Canvas()
    c.rect(3, 2, 12, 5, 'm')
    c.poly([(3, 5), (7, 5), (7, 13), (3, 13)], 'm')
    c.poly([(8, 5), (12, 5), (12, 13), (8, 13)], 'm')
    c.rect(7, 3, 8, 4, 'a')
    return c


def boots():
    c = Canvas()
    c.poly([(3, 2), (8, 2), (8, 9), (13, 10), (13, 13), (3, 13)], 'm')
    c.rect(3, 10, 6, 11, 'a')
    return c


def sword():
    c = Canvas()
    c.line(4, 11, 12, 3, 'm', 2)
    c.line(2, 10, 5, 13, 'w', 1)   # 護手（木頭色）
    c.line(3, 12, 1, 14, 'w', 2)   # 握把
    c.dot(13, 2, 'a')
    return c


def axe():
    c = Canvas()
    c.line(3, 13, 11, 5, 'w', 2)
    c.poly([(8, 2), (12, 1), (14, 5), (13, 8), (10, 6)], 'm')
    c.line(12, 2, 14, 6, 'a')
    return c


def mace():
    c = Canvas()
    c.line(2, 13, 9, 6, 'w', 2)
    c.disc(10.5, 4.5, 3.2, 'm')
    c.dot(10, 4, 'a')
    c.dot(11, 5, 'a')
    return c


def hammer():
    c = Canvas()
    c.line(2, 14, 9, 7, 'w', 2)
    c.poly([(6, 4), (10, 0), (15, 5), (11, 9)], 'm')
    c.line(11, 1, 14, 4, 'a')
    return c


def sceptre():
    c = Canvas()
    c.line(2, 13, 9, 6, 'w', 2)
    c.poly([(9, 7), (8, 4), (11, 2), (14, 5), (12, 8)], 'm')
    c.disc(11, 5, 1.3, 'a')
    return c


def claw():
    c = Canvas()
    c.rect(2, 9, 8, 13, 'w')
    for i in range(3):
        c.line(4 + i * 2, 8, 9 + i * 2, 2 + i, 'm')
    c.dot(9, 2, 'a')
    c.dot(11, 3, 'a')
    c.dot(13, 4, 'a')
    return c


def dagger():
    c = Canvas()
    c.line(6, 9, 12, 3, 'm', 2)
    c.line(4, 8, 7, 11, 'w')
    c.line(5, 10, 3, 12, 'w', 2)
    c.dot(13, 2, 'a')
    return c


def spear():
    c = Canvas()
    c.line(1, 14, 10, 5, 'w')
    c.poly([(9, 4), (12, 1), (14, 1), (14, 3), (11, 6)], 'm')
    c.dot(13, 2, 'a')
    return c


def quarterstaff():
    c = Canvas()
    c.line(2, 13, 13, 2, 'w', 2)
    c.line(4, 11, 5, 10, 'm', 2)
    c.line(10, 5, 11, 4, 'm', 2)
    c.dot(13, 2, 'a')
    return c


def talisman():
    c = Canvas()
    c.line(3, 14, 8, 9, 'w', 2)
    c.poly([(7, 6), (9, 2), (13, 2), (14, 6), (11, 9), (8, 9)], 'm')
    c.dot(10, 4, 'a')
    c.dot(12, 4, 'a')
    c.rect(10, 6, 12, 6, 'a')
    return c


def bow():
    c = Canvas()
    c.ring(1.5, 14.5, 10.6, 12.2, 'w')
    for x in range(N):
        for y in range(N):
            if x < 2 or y > 13:
                c.erase(x, y)
    c.line(3, 3, 12, 12, 'm')
    c.dot(7, 7, 'a')
    return c


def crossbow():
    c = Canvas()
    c.line(2, 14, 10, 6, 'w', 2)
    c.line(6, 3, 13, 10, 'm', 2)
    c.line(10, 6, 13, 3, 'a')
    return c


def wand():
    c = Canvas()
    c.line(3, 13, 10, 6, 'w', 2)
    c.poly([(9, 6), (12, 2), (14, 4), (10, 7)], 'a')
    return c


def staff():
    c = Canvas()
    c.line(2, 14, 10, 6, 'w', 2)
    c.ring(11, 4.5, 1.8, 3.3, 'm')
    c.disc(11, 4.5, 1.6, 'a')
    return c


def shield():
    c = Canvas()
    c.poly([(2, 2), (13, 2), (13, 8), (7.5, 14), (2, 8)], 'm')
    c.poly([(6, 5), (9, 5), (9, 8), (7.5, 10), (6, 8)], 'a')
    return c


def buckler():
    c = Canvas()
    c.disc(7.5, 7.5, 6.2, 'g')   # 2026-10-24：敏捷盾改綠色（跟敏捷防具一樣）
    c.disc(7.5, 7.5, 2.2, 'a')
    return c


def focus():
    c = Canvas()
    c.disc(7.5, 7, 4.8, 'a')
    c.rect(4, 12, 11, 13, 'm')
    c.rect(6, 11, 9, 11, 'm')
    return c


def quiver():
    return quiver_of('a')


def quiver_of(mat):
    """箭袋：褐色袋身，露出三支箭，箭羽照固定詞綴上色（2026-10-24）。"""
    c = Canvas()
    c.line(5, 7, 8, 4, 'm')
    c.line(9, 10, 12, 7, 'm')
    c.poly([(8, 1), (11, 1), (11, 4), (8, 4)], mat)       # 兩支箭的箭羽
    c.poly([(12, 4), (15, 4), (15, 7), (12, 7)], mat)
    c.poly([(1, 10), (6, 5), (11, 10), (6, 15)], 'w')
    return c


# 箭袋照固定詞綴換箭羽顏色（2026-10-24）
QUIVER_VARIANTS = {
    'phys': 'c', 'fire': 'f', 'crit': 'x', 'speed': 'g', 'pierce': 'u', 'life': 'l', 'poison': 'p', 'bleed': 'B',
    'accuracy': 'a', 'stun': 'H', 'projspeed': 'i',
}


# ---- 防具與盾照屬性（2026-10-24）：形狀看主屬性（力量板甲、敏捷皮甲、智慧布袍），顏色看屬性（力量紅、敏捷綠、智慧藍），
# 雙屬性的主體是第一個屬性的顏色、飾邊是第二個 ----

FAMILIES = {  # 代號 → (形狀, 主體, 飾邊)
    'str': ('plate', 'r', 'r'), 'dex': ('leather', 'g', 'g'), 'int': ('cloth', 'u', 'u'),
    'str_dex': ('plate', 'r', 'g'), 'str_int': ('plate', 'r', 'u'), 'dex_int': ('leather', 'g', 'u'),
}


def helmet_of(shape, main, trim):
    c = Canvas()
    if shape == 'plate':
        c.poly([(3, 13), (3, 6), (5, 3), (10, 3), (12, 6), (12, 13), (10, 13), (10, 9), (5, 9), (5, 13)], main)
        c.rect(7, 1, 8, 4, trim)                      # 盔頂的冠
        c.rect(3, 9, 4, 13, trim) if trim != main else None
        c.rect(11, 9, 12, 13, trim) if trim != main else None
    elif shape == 'leather':
        c.poly([(2, 10), (3, 5), (6, 2), (9, 2), (12, 5), (13, 10)], main)   # 圓帽
        c.rect(1, 10, 14, 11, trim)                   # 帽沿
    else:
        c.poly([(7.5, 1), (12, 6), (13, 13), (10, 13), (10, 8), (5, 8), (5, 13), (2, 13), (3, 6)], main)  # 尖兜帽
        c.rect(6, 7, 9, 7, trim)
        c.dot(7, 2, 'a')
    return c


def body_of(shape, main, trim):
    c = Canvas()
    if shape == 'plate':
        c.poly([(3, 5), (6, 3), (9, 3), (12, 5), (12, 13), (3, 13)], main)
        c.disc(2.5, 5, 2.2, trim)                     # 肩甲
        c.disc(12.5, 5, 2.2, trim)
        c.rect(7, 7, 8, 8, 'a')
    elif shape == 'leather':
        c.poly([(3, 3), (6, 2), (7, 5), (8, 5), (9, 2), (12, 3), (12, 13), (3, 13)], main)   # 背心
        c.rect(3, 9, 12, 10, trim)                    # 腰帶
        c.rect(7, 9, 8, 10, 'a')
    else:
        c.poly([(5, 2), (10, 2), (13, 5), (12, 6), (11, 5), (13, 14), (2, 14), (4, 5), (3, 6), (2, 5)], main)  # 長袍
        c.line(7, 3, 7, 13, trim)                     # 前襟
        c.line(8, 3, 8, 13, trim)
        c.dot(7, 5, 'a')
    return c


def leggings_of(shape, main, trim):
    c = Canvas()
    if shape == 'plate':
        c.rect(3, 2, 12, 5, main)
        c.rect(3, 6, 6, 13, main)
        c.rect(9, 6, 12, 13, main)
        c.rect(3, 8, 6, 9, trim if trim != main else 'a')   # 護膝
        c.rect(9, 8, 12, 9, trim if trim != main else 'a')
    elif shape == 'leather':
        c.rect(4, 2, 11, 3, trim)                     # 腰帶
        c.rect(4, 4, 11, 6, main)
        c.rect(4, 7, 6, 13, main)
        c.rect(9, 7, 11, 13, main)
        c.rect(7, 2, 8, 3, 'a')
    else:
        c.rect(5, 2, 10, 3, trim)                     # 裙腰
        c.poly([(5, 4), (10, 4), (13, 13), (2, 13)], main)   # 長裙
        c.rect(2, 12, 13, 13, trim)                   # 裙襬
        c.dot(7, 3, 'a')
    return c


def boots_of(shape, main, trim):
    c = Canvas()
    if shape == 'plate':
        c.poly([(3, 2), (8, 2), (8, 9), (13, 10), (13, 13), (3, 13)], main)
        c.rect(9, 10, 13, 13, trim if trim != main else main)
        c.rect(3, 6, 8, 7, 'a')
    elif shape == 'leather':
        c.poly([(4, 4), (8, 4), (8, 10), (13, 11), (13, 13), (4, 13)], main)
        c.rect(3, 2, 9, 4, trim)                      # 翻邊
        c.dot(6, 3, 'a')
    else:
        c.poly([(5, 7), (8, 7), (8, 10), (14, 11), (14, 13), (4, 13), (4, 9)], main)   # 便鞋
        c.rect(4, 6, 9, 7, trim)
        c.dot(13, 11, 'a')
    return c


def shield_of(main, trim):
    c = Canvas()
    c.poly([(2, 2), (13, 2), (13, 8), (7.5, 14), (2, 8)], main)
    c.rect(2, 2, 13, 3, trim)
    c.poly([(6, 5), (9, 5), (9, 8), (7.5, 10), (6, 8)], 'a')
    return c


# ---- 戒指照固定詞綴的抗性換寶石顏色（2026-10-24）：火紅、冰藍、電黃、混沌紫；雙抗左右各半、全抗三色 ----

RING_ELEMENTS = {'fire': 'f', 'cold': 'i', 'lightning': 'z', 'chaos': 'p'}
RING_VARIANTS = {
    'fire': ['fire'], 'cold': ['cold'], 'lightning': ['lightning'], 'chaos': ['chaos'],
    'fire_cold': ['fire', 'cold'], 'fire_lightning': ['fire', 'lightning'], 'cold_lightning': ['cold', 'lightning'],
    'all': ['fire', 'cold', 'lightning'],
}


def ring_of(elements):
    c = ring()
    mats = [RING_ELEMENTS[e] for e in elements]
    for y in range(N):
        for x in range(N):
            if c.px[y][x] == 'a':
                c.px[y][x] = mats[min(len(mats) - 1, (x - 3) * len(mats) // 10)] if x >= 3 else mats[0]
    return c


# 不是抗性的戒指（2026-10-24）：圓形寶石（跟抗性戒指的三角切面分開），顏色照固定詞綴
RING_OTHERS = {'phys': 'c', 'life': 'l', 'mana': 'a', 'accuracy': 'g', 'cast': 'x', 'rarity': 'H', 'chaosdmg': 'p'}


def ring_round(mat):
    c = Canvas()
    c.ring(7.5, 10, 2.0, 4.6, 'm')
    c.disc(7.5, 4.5, 3.0, mat)
    return c


# 項鍊照固定詞綴換墜子（2026-10-24）：屬性是菱形（力量紅、敏捷綠、智慧藍、全屬性三色），抗性是三角切面（全抗三色），
# 其他是圓形（生命粉紅、魔力回復青、能量護盾淡紫白、精魂銅橘、掉落稀有度金）
AMULET_VARIANTS = {
    'str': ('diamond', ['r']), 'dex': ('diamond', ['g']), 'int': ('diamond', ['u']), 'attr_all': ('diamond', ['r', 'g', 'u']),
    'res_all': ('facet', ['f', 'i', 'z']),
    'life': ('round', ['l']), 'mana': ('round', ['a']), 'es': ('round', ['x']), 'spirit': ('round', ['c']), 'rarity': ('round', ['H']),
}


def amulet_of(shape, mats):
    c = Canvas()
    top = 4 if shape == 'facet' else 6
    c.line(3, 1, 6, top, 'm')
    c.line(12, 1, 9, top, 'm')
    if shape == 'diamond':
        c.poly([(7.5, 5), (12, 9.5), (7.5, 14), (3, 9.5)], mats[0])
    elif shape == 'facet':
        c.poly([(2, 5), (13, 5), (7.5, 14)], mats[0])
    else:
        c.disc(7.5, 10, 3.6, mats[0])
    if len(mats) > 1:
        gem = [(x, y) for y in range(5, N) for x in range(N) if c.px[y][x] == mats[0]]
        lo = min(x for x, _ in gem)
        width = max(x for x, _ in gem) - lo + 1
        for x, y in gem:
            c.px[y][x] = mats[min(len(mats) - 1, (x - lo) * len(mats) // width)]
    return c


ICONS = {
    'ring': ring, 'amulet': amulet, 'belt': belt,
    'helmet': helmet, 'body': body, 'leggings': leggings, 'boots': boots,
    'sword': sword, 'axe': axe, 'mace': mace, 'hammer': hammer, 'sceptre': sceptre, 'claw': claw, 'dagger': dagger,
    'spear': spear, 'quarterstaff': quarterstaff, 'talisman': talisman,
    'bow': bow, 'crossbow': crossbow, 'wand': wand, 'staff': staff,
    'shield': shield, 'buckler': buckler, 'focus': focus, 'quiver': quiver,
}
for _family, (_shape, _main, _trim) in FAMILIES.items():
    ICONS['helmet_' + _family] = (lambda s=_shape, m=_main, t=_trim: helmet_of(s, m, t))
    ICONS['body_' + _family] = (lambda s=_shape, m=_main, t=_trim: body_of(s, m, t))
    ICONS['leggings_' + _family] = (lambda s=_shape, m=_main, t=_trim: leggings_of(s, m, t))
    ICONS['boots_' + _family] = (lambda s=_shape, m=_main, t=_trim: boots_of(s, m, t))
for _variant, _mat in QUIVER_VARIANTS.items():
    ICONS['quiver_' + _variant] = (lambda m=_mat: quiver_of(m))
for _variant, (_shape, _mats) in AMULET_VARIANTS.items():
    ICONS['amulet_' + _variant] = (lambda sh=_shape, ms=_mats: amulet_of(sh, ms))
for _variant, _mat in RING_OTHERS.items():
    ICONS['ring_' + _variant] = (lambda m=_mat: ring_round(m))
for _variant, _elements in RING_VARIANTS.items():
    ICONS['ring_' + _variant] = (lambda e=_elements: ring_of(e))
for _family in ('str', 'str_dex', 'str_int'):
    _main, _trim = FAMILIES[_family][1], FAMILIES[_family][2]
    ICONS['shield_' + _family] = (lambda m=_main, t=_trim: shield_of(m, t))


def palette():
    lines = ['  o=' + OUTLINE]
    for mat, (base, light, dark) in MATERIALS.items():
        b, l, d = KEYS[mat]
        lines += ['  %s=%s' % (b, base), '  %s=%s' % (l, light), '  %s=%s' % (d, dark)]
    return lines


def main():
    out = ['# 《星骸》普通／魔法／稀有裝備在格子裡的簡圖（16×16）。由 tools/gen_plain_art.py 整份產生（不要手改）。',
           '# 物品上用 custom_model_data 的字串 plain:種類 選到它；只畫在格子裡，拿在手上、穿在身上照原版。', '']
    sprites = {}
    for name, draw in ICONS.items():
        rows = draw().rows()
        sprites[name] = rows
        out.append('item ' + name)
        out += palette()
        out += ['  ' + r for r in rows]
        out.append('')
    OUT.write_text('\n'.join(out), encoding='utf-8')
    print('寫好 %s：%d 張' % (OUT.relative_to(ROOT), len(sprites)))
    if '--preview' in sys.argv:
        from PIL import Image
        colors = {'o': OUTLINE}
        for mat, triple in MATERIALS.items():
            for key, hexv in zip(KEYS[mat], triple):
                colors[key] = hexv
        scale, cols = 6, 10
        rows_n = (len(sprites) + cols - 1) // cols
        img = Image.new('RGBA', (cols * (N + 2) * scale, rows_n * (N + 2) * scale), (139, 139, 139, 255))
        for i, rows in enumerate(sprites.values()):
            ox = (i % cols) * (N + 2) + 1
            oy = (i // cols) * (N + 2) + 1
            for y, row in enumerate(rows):
                for x, ch in enumerate(row):
                    if ch != '.':
                        rgb = tuple(int(colors[ch][j:j + 2], 16) for j in (0, 2, 4))
                        for dy in range(scale):
                            for dx in range(scale):
                                img.putpixel(((ox + x) * scale + dx, (oy + y) * scale + dy), rgb + (255,))
        img.save(PREVIEW)
        print('預覽：%s' % PREVIEW.relative_to(ROOT))


if __name__ == '__main__':
    main()
