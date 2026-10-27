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
    'N': ('e6dcc0', 'fbf6e6', 'a89c7c'),   # 2026-10-27：骨頭（法杖、長杖、權杖）
    'V': ('6e4a2e', '957050', '442a18'),   # 2026-10-27：深色木頭（枯枝、灰燼、冥暗）
}
OUTLINE = '2c3135'
KEYS = {'m': ('m', 'M', 'n'), 'a': ('a', 'A', 'b'), 'w': ('w', 'W', 'v'), 'r': ('r', 'R', 'q'), 'g': ('g', 'G', 'h'),
        'u': ('u', 'U', 't'), 'f': ('f', 'F', 'e'), 'i': ('i', 'I', 'j'), 'z': ('z', 'Z', 's'), 'p': ('p', 'P', 'y'),
        'c': ('c', 'C', 'd'), 'l': ('l', 'L', 'k'), 'x': ('x', 'X', 'E'), 'H': ('H', 'J', 'K'), 'B': ('B', 'D', 'Q'),
        'N': ('N', 'O', 'S'), 'V': ('V', 'Y', 'T')}


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
    return belt_of('w', 'm', 'a', 'plain')


def belt_of(strap, buckle, gem, shape):
    """腰帶照固定詞綴換樣子（2026-10-24）：gem 一般扣環、扣環上的寶石換色；flask 扣環換成一個小藥瓶；
    plate 金屬帶身加鉚釘（護甲）；heavy 大扣環（暈眩門檻）。"""
    c = Canvas()
    c.rect(1, 6, 14, 9, strap)
    if shape == 'flask':
        c.rect(7, 3, 8, 4, 'm')          # 瓶口
        c.rect(6, 5, 9, 11, gem)         # 瓶身
        c.rect(7, 6, 8, 6, 'm')
    elif shape == 'plate':
        c.rect(6, 5, 9, 10, buckle)
        c.rect(7, 7, 8, 8, gem)
        for x in (2, 4, 11, 13):          # 鉚釘
            c.dot(x, 7, gem)
            c.dot(x, 8, gem)
    elif shape == 'heavy':
        c.rect(1, 5, 14, 10, strap)      # 加寬的帶身
        c.rect(4, 3, 11, 12, buckle)
        c.rect(7, 6, 8, 9, gem)          # 扣針
    elif shape == 'plain':
        c.rect(6, 5, 9, 10, buckle)
        c.rect(7, 7, 8, 8, gem)
    else:
        # 2026-10-24 第二版：整個扣環都是詞綴的顏色（大一點），中間一根金屬扣針
        c.rect(5, 4, 10, 11, gem)
        c.rect(7, 6, 8, 9, buckle)
    return c


# 腰帶照固定詞綴（2026-10-24）：生命粉紅寶石、能量護盾藍寶石、藥劑生命／魔力回復是小藥瓶（粉紅／青）、護甲是金屬帶身加銅鉚釘、
# 施法速度淡紫白、力量紅、閃電黃、暈眩門檻是金色大扣環
BELT_VARIANTS = {
    'life': ('w', 'm', 'l', 'gem'), 'es': ('w', 'm', 'u', 'gem'), 'flask_life': ('w', 'm', 'l', 'flask'), 'flask_mana': ('w', 'm', 'a', 'flask'),
    'armour': ('m', 'm', 'c', 'plate'), 'cast': ('w', 'm', 'x', 'gem'), 'str': ('w', 'm', 'r', 'gem'), 'lightning': ('w', 'm', 'z', 'gem'),
    'stun': ('w', 'H', 'm', 'heavy'),
}


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
    return sceptre_of('H', 'a', 'crown')  # 2026-10-27：改成短、金屬杆、頂端王冠（以前跟法杖、長杖一樣是木杆）


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
    return wand_of('w', 'a', 'crystal')  # 2026-10-27：改成短而細、尖端寶石（以前跟長杖、權杖一樣長一樣粗）


def staff():
    return staff_of('w', 'a', 'claw')  # 2026-10-27：改成長、頂端大寶石




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
def split(c, main, trim):
    """雙屬性的防具（2026-10-24 第二版）：整件照第一個屬性畫，右半邊換成第二個屬性的顏色，左右各半一眼就看得出是雙屬性。"""
    if main != trim:
        for y in range(N):
            for x in range(8, N):
                if c.px[y][x] == main:
                    c.px[y][x] = trim
    return c


for _family, (_shape, _main, _trim) in FAMILIES.items():
    ICONS['helmet_' + _family] = (lambda s=_shape, m=_main, t=_trim: split(helmet_of(s, m, m), m, t))
    ICONS['body_' + _family] = (lambda s=_shape, m=_main, t=_trim: split(body_of(s, m, m), m, t))
    ICONS['leggings_' + _family] = (lambda s=_shape, m=_main, t=_trim: split(leggings_of(s, m, m), m, t))
    ICONS['boots_' + _family] = (lambda s=_shape, m=_main, t=_trim: split(boots_of(s, m, m), m, t))
for _variant, _mat in QUIVER_VARIANTS.items():
    ICONS['quiver_' + _variant] = (lambda m=_mat: quiver_of(m))
for _variant, (_strap, _buckle, _gem, _shape) in BELT_VARIANTS.items():
    ICONS['belt_' + _variant] = (lambda s=_strap, b=_buckle, g=_gem, sh=_shape: belt_of(s, b, g, sh))
for _variant, (_shape, _mats) in AMULET_VARIANTS.items():
    ICONS['amulet_' + _variant] = (lambda sh=_shape, ms=_mats: amulet_of(sh, ms))
for _variant, _mat in RING_OTHERS.items():
    ICONS['ring_' + _variant] = (lambda m=_mat: ring_round(m))
for _variant, _elements in RING_VARIANTS.items():
    ICONS['ring_' + _variant] = (lambda e=_elements: ring_of(e))
for _family in ('str', 'str_dex', 'str_int'):
    _main, _trim = FAMILIES[_family][1], FAMILIES[_family][2]
    ICONS['shield_' + _family] = (lambda m=_main, t=_trim: split(shield_of(m, m), m, t))


# ---- 法杖、長杖、權杖（2026-10-27）----
# 站長：三種施法武器以前長得太像（都是一樣長、一樣粗的木杆＋頂端一顆青寶石）。現在照輪廓分開：
#   法杖：短而細（1 格寬的杆子，只到格子中間），尖端一顆小寶石或骨頭；
#   長杖：從左下角拉到右上角的長杆（2 格寬），頂端一顆大寶石（爪托、圓環、彎鉤、分叉、鐮刃、鈴、枝葉、冰錐、鏡面）；
#   權杖：短而粗的金屬杆，頂端是金屬的頭（王冠、寶珠、翼片、神龕、骷髏、狼牙、香爐）。
# 同一類的每個基底（weapons_casters.yml）另外一張：杆子的材質（木頭、深色木頭、骨頭、金屬、金）、寶石顏色（照賦予技能的元素）、頭的樣子。
# 物品上用 plain:基底代號 選到（PlainArt.category 先找基底代號那張，沒有才用 wand／staff／sceptre）。

def wand_of(shaft, gem, tip):
    c = Canvas()
    c.line(4, 12, 9, 7, shaft)               # 細杆（1 格），只到格子中間
    c.dot(3, 13, 'm')                         # 柄尾
    c.dot(4, 13, 'm')
    c.dot(3, 12, 'm')
    if tip == 'crystal':                      # 尖晶
        c.line(10, 6, 12, 4, gem, 2)
        c.dot(13, 2, gem)
        c.dot(9, 7, 'm')
    elif tip == 'orb':                        # 小寶珠
        c.disc(11, 5, 1.9, gem)
    elif tip == 'fork':                       # 叉尖夾著寶石
        c.line(9, 7, 9, 3, 'm')
        c.line(9, 7, 13, 7, 'm')
        c.rect(11, 4, 12, 5, gem)
    elif tip == 'bone':                       # 骨頭：圓圓的骨節，頂上一點
        c.disc(10.5, 5.5, 1.6, shaft)
        c.dot(12, 3, gem)
        c.dot(13, 3, gem)
    elif tip == 'spike':                      # 長尖刺
        c.line(10, 6, 11, 5, gem, 2)
        c.line(12, 4, 14, 2, gem)
    elif tip == 'twig':                       # 枯枝：彎的分枝，兩端各一點寶石
        c.line(9, 7, 10, 4, shaft)
        c.line(9, 7, 12, 6, shaft)
        c.rect(10, 2, 11, 3, gem)
        c.rect(12, 5, 13, 6, gem)
    elif tip == 'barb':                       # 帶倒鉤
        c.line(9, 7, 12, 4, 'm')
        c.dot(10, 4, 'm')
        c.dot(12, 6, 'm')
        c.rect(12, 2, 13, 3, gem)
    elif tip == 'coil':                       # 杆上纏線圈
        c.line(10, 6, 12, 4, gem, 2)
        c.dot(13, 2, gem)
        for x, y in ((5, 10), (7, 8)):
            c.dot(x, y + 1, gem)
            c.dot(x + 1, y, gem)
    elif tip == 'guard':                      # 決鬥：小護手
        c.line(6, 8, 8, 10, 'm')
        c.line(10, 6, 12, 4, gem, 2)
        c.dot(13, 2, gem)
    return c


def staff_of(shaft, gem, head):
    c = Canvas()
    c.line(1, 14, 10, 5, shaft, 2)           # 長杆（2 格，左下角到右上）
    c.dot(1, 15, 'm')                         # 杖尾包金屬
    c.dot(0, 15, 'm')
    if head == 'claw':                        # 爪托抓著大寶珠
        c.disc(11.5, 3.5, 2.6, gem)
        c.line(9, 6, 8, 3, 'm')
        c.line(9, 6, 12, 7, 'm')
    elif head == 'ring':                      # 圓環中間一顆
        c.ring(11.5, 3.5, 2.4, 3.4, 'm')
        c.disc(11.5, 3.5, 1.5, gem)
    elif head == 'crook':                     # 彎鉤，鉤下吊一顆寶石
        c.line(10, 5, 11, 1, shaft, 2)
        c.line(11, 1, 14, 1, shaft, 2)
        c.line(14, 2, 14, 3, shaft)
        c.disc(13.5, 5.5, 1.7, gem)
    elif head == 'fork':                      # 兩根分叉，中間夾一顆
        c.line(10, 5, 9, 1, 'm')
        c.line(10, 5, 14, 6, 'm')
        c.disc(12, 3, 1.6, gem)
    elif head == 'blade':                     # 鐮刃
        c.poly([(9, 3), (12, 0), (15, 1), (15, 4), (13, 2), (11, 3)], gem)
        c.disc(10.5, 4.5, 1.2, 'm')
    elif head == 'bell':                      # 鈴
        c.line(10, 5, 13, 2, shaft, 2)
        c.poly([(11, 6), (12, 4), (14, 4), (15, 6), (15, 8), (11, 8)], gem)
        c.dot(13, 9, 'm')
    elif head == 'branch':                    # 枝葉：分枝上長出葉子（花）
        c.line(10, 5, 9, 2, shaft)
        c.line(10, 5, 13, 4, shaft)
        c.disc(9, 1.2, 1.3, gem)
        c.disc(13.8, 3.6, 1.3, gem)
        c.disc(11.6, 1.8, 1.0, gem)
    elif head == 'icicle':                    # 冰錐：長長的尖晶
        c.poly([(9, 7), (8, 4), (14, 0), (15, 1), (11, 8)], gem)
    elif head == 'mirror':                    # 鏡面：金屬框圓鏡
        c.disc(11.5, 3.5, 3.4, 'm')
        c.disc(11.5, 3.5, 2.3, gem)
    return c


def sceptre_of(metal, gem, head):
    """權杖直立著畫（法杖、長杖都是斜的）：短而粗的金屬杆＋頂端的金屬頭。"""
    c = Canvas()
    c.rect(7, 10, 8, 13, metal)              # 短杆（2 格寬）
    c.rect(6, 14, 9, 14, metal)              # 柄頭
    c.rect(7, 15, 8, 15, metal)
    c.rect(5, 9, 10, 9, metal)               # 頸箍
    if head == 'crown':                       # 王冠：冠帶＋三個尖，冠帶中間一顆寶石、尖上各一點
        c.rect(3, 6, 12, 8, metal)
        c.poly([(3, 6), (3, 2), (6, 6)], metal)
        c.poly([(5.5, 6), (7.5, 1), (9.5, 6)], metal)
        c.poly([(9, 6), (12, 2), (12, 6)], metal)
        c.rect(7, 6, 8, 7, gem)
        c.dot(3, 3, gem)
        c.dot(11, 3, gem)
    elif head == 'orb':                       # 寶珠：大圓珠，上面一個小十字
        c.disc(7.5, 5.5, 3.4, gem)
        c.rect(6, 8, 9, 8, metal)            # 托住珠子的金屬托
        c.rect(7, 0, 8, 2, metal)            # 頂上的小十字
        c.rect(6, 1, 9, 1, metal)
    elif head == 'flange':                    # 翼片錘頭：中間一塊，兩邊三片翼
        c.rect(5, 2, 10, 8, metal)
        for y in (2, 5):
            c.rect(3, y, 4, y + 1, metal)
            c.rect(11, y, 12, y + 1, metal)
        c.rect(7, 4, 8, 6, gem)
    elif head == 'shrine':                    # 神龕：屋頂＋小龕，龕裡一顆寶石
        c.poly([(2.5, 5), (7.5, 0), (12.5, 5)], metal)
        c.rect(4, 5, 11, 8, metal)
        c.rect(6, 5, 9, 7, gem)
    elif head == 'skull':                     # 骷髏
        c.disc(7.5, 4.5, 3.6, 'N')
        c.rect(5, 7, 10, 8, 'N')
        c.erase(5, 4)
        c.erase(6, 4)
        c.erase(9, 4)
        c.erase(10, 4)
        c.erase(7, 8)
        c.erase(8, 8)
        c.rect(7, 6, 8, 6, gem)              # 鼻孔裡的光
    elif head == 'fang':                      # 狼牙：一圈往外的尖牙，中間一顆
        for x0, y0, x1, y1 in ((5, 6, 2, 3), (6, 4, 4, 0), (9, 4, 11, 0), (10, 6, 13, 3), (5, 8, 2, 8), (10, 8, 13, 8)):
            c.line(x0, y0, x1, y1, 'N')
        c.disc(7.5, 6, 2.6, metal)
        c.disc(7.5, 6, 1.2, gem)
    elif head == 'censer':                    # 香爐：鏤空的小籠，裡面燒著（寶石色）
        c.disc(7.5, 5, 3.5, metal)
        c.rect(7, 0, 8, 1, metal)
        for x, y in ((5, 4), (7, 3), (9, 4), (6, 6), (8, 6), (10, 6), (7, 5)):
            c.dot(x, y, gem)
    return c


WAND_BASES = {  # 基底 → (杆子, 寶石, 尖端)
    'cwand_01': ('V', 'p', 'twig'),     # 枯枝：深色彎枝、混沌紫
    'cwand_02': ('N', 'x', 'bone'),     # 骸骨：骨頭
    'cwand_03': ('m', 'u', 'crystal'),  # 調律：金屬杆、藍晶
    'cwand_04': ('w', 'a', 'orb'),      # 虹吸：青色寶珠
    'cwand_05': ('N', 'f', 'crystal'),  # 炎屍：骨杆、火紅尖晶
    'cwand_06': ('m', 'z', 'fork'),     # 電流：金屬叉、黃
    'cwand_07': ('V', 'g', 'orb'),      # 腐蝕：深色木、綠珠
    'cwand_08': ('N', 'B', 'spike'),    # 血祭：骨杆、血紅長刺
    'cwand_09': ('m', 'i', 'crystal'),  # 霜蝕：冰晶
    'cwand_10': ('V', 'p', 'barb'),     # 刑苦：倒鉤
    'cwand_11': ('H', 'x', 'spike'),    # 銳芒：金杆、白色長刺
    'cwand_12': ('V', 'p', 'orb'),      # 太古：深色木、紫珠
    'cwand_13': ('w', 'z', 'coil'),     # 纏蝕：纏著電線圈
    'cwand_14': ('m', 'r', 'guard'),    # 決鬥：小護手、紅
}
STAFF_BASES = {  # 基底 → (杆子, 寶石, 頂端)
    'cstaff_01': ('V', 'f', 'claw'),    # 灰燼
    'cstaff_02': ('m', 'i', 'ring'),    # 凝冰
    'cstaff_03': ('w', 'z', 'fork'),    # 雷鳴
    'cstaff_04': ('w', 'g', 'branch'),  # 精靈：綠葉
    'cstaff_05': ('w', 'f', 'branch'),  # 炎木：紅花
    'cstaff_06': ('w', 'H', 'bell'),    # 鳴鐘：金鈴
    'cstaff_07': ('V', 'p', 'crook'),   # 裂魂
    'cstaff_08': ('V', 'B', 'blade'),   # 血鐮：鐮刃
    'cstaff_09': ('m', 'i', 'icicle'),  # 冰錐
    'cstaff_10': ('N', 'z', 'claw'),    # 麻痺：骨杆、黃
    'cstaff_11': ('H', 'x', 'ring'),    # 聖紋：金杆、白光
    'cstaff_12': ('V', 'p', 'claw'),    # 冥暗
    'cstaff_13': ('N', 'B', 'fork'),    # 饑渴：骨杆、血紅
    'cstaff_14': ('m', 'i', 'claw'),    # 永凍
    'cstaff_15': ('w', 'r', 'ring'),    # 咆哮
    'cstaff_16': ('V', 'u', 'crook'),   # 棲鴉
    'cstaff_17': ('m', 'a', 'mirror'),  # 映鏡
}
SCEPTRE_BASES = {  # 基底 → (金屬, 寶石, 頭)
    'csceptre_01': ('m', 'a', 'skull'),   # 骸鈴
    'csceptre_02': ('m', 'a', 'flange'),  # 堅忍
    'csceptre_03': ('m', 'r', 'fang'),    # 狼牙
    'csceptre_04': ('m', 'p', 'orb'),     # 凶兆
    'csceptre_05': ('c', 'r', 'orb'),     # 赭土：銅杆、赭紅珠
    'csceptre_06': ('H', 'f', 'shrine'),  # 焰龕
    'csceptre_07': ('H', 'i', 'shrine'),  # 霜龕
    'csceptre_08': ('H', 'z', 'shrine'),  # 雷龕
    'csceptre_09': ('H', 'p', 'shrine'),  # 蝕龕
    'csceptre_10': ('m', 'B', 'fang'),    # 吞噬
    'csceptre_11': ('m', 'i', 'orb'),     # 寒心
    'csceptre_12': ('H', 'x', 'crown'),   # 虔誠
    'csceptre_13': ('c', 'g', 'censer'),  # 芳香
    'csceptre_14': ('H', 'a', 'crown'),   # 聖徒
    'csceptre_15': ('H', 'r', 'crown'),   # 聖化
    'csceptre_16': ('m', 'z', 'flange'),  # 怒雷
}
for _bid, (_s, _g, _t) in WAND_BASES.items():
    ICONS[_bid] = (lambda s=_s, g=_g, t=_t: wand_of(s, g, t))
for _bid, (_s, _g, _t) in STAFF_BASES.items():
    ICONS[_bid] = (lambda s=_s, g=_g, t=_t: staff_of(s, g, t))
for _bid, (_s, _g, _t) in SCEPTRE_BASES.items():
    ICONS[_bid] = (lambda s=_s, g=_g, t=_t: sceptre_of(s, g, t))


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
