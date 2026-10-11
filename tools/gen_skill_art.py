#!/usr/bin/env python3
"""
產生 src/main/resources/web/skill_art.txt：每個技能的 16×16 圖示（格式同 unique_art.txt；代號是 skill_技能代號）。

每個圖示 = 依元素上色的方框底（外框、漸層）＋ 技能的圖形（自動描一圈深色外框）。
圖形用下面的小工具畫（線、圓、多邊形、像素字串）；每個技能一個函式（SKILLS 表）。
異常／分歧／幻影的寶石（variant_of）沿用原技能的圖示（遊戲裡照 variant_of 找）。
改完直接跑：python3 tools/gen_skill_art.py（會順便輸出 tools/skill_art_preview.png 預覽）
"""
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'src/main/resources/web/skill_art.txt'
PREVIEW = ROOT / 'tools/skill_art_preview.png'
N = 16

# ---- 顏色 ----
def hx(s):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)

# 元素：底（外框、上、下）、圖形（暗、中、亮、白）
ELEMENTS = {
    'fire':      (('#1a0703', '#5a1c0a', '#2c0c04'), ('#a8300c', '#ff6a1a', '#ffb43a', '#fff0a0')),
    'cold':      (('#04101c', '#164a72', '#0a2238'), ('#2a78c0', '#5ec4ff', '#b8eeff', '#ffffff')),
    'lightning': (('#141004', '#4a3e0e', '#241e06'), ('#b08a10', '#ffd23a', '#fff27a', '#ffffff')),
    'chaos':     (('#0e0418', '#3e1660', '#1c0830'), ('#5a1e8c', '#9a4ad8', '#cf8aff', '#9dff7a')),
    'physical':  (('#100e0c', '#46403a', '#221e1a'), ('#6e655a', '#a89c88', '#e0d6c0', '#ffffff')),
    'blood':     (('#140404', '#4a1414', '#220808'), ('#7a1010', '#c8282a', '#ff6a5a', '#ffd0c8')),
    'minion':    (('#0a100c', '#2e4234', '#141e18'), ('#6e6a58', '#bdb59a', '#ece4c8', '#ffffff')),
    'wind':      (('#061210', '#1e4a44', '#0c2420'), ('#3a8a7a', '#7ad8c0', '#c8fff0', '#ffffff')),
    'holy':      (('#141004', '#5a4a1a', '#2a220a'), ('#b08a2a', '#f5c84a', '#fff0a0', '#ffffff')),
    'life':      (('#061206', '#1e4a1e', '#0c240c'), ('#2a8a2a', '#5ad84a', '#b8ff9a', '#ffffff')),
    'mana':      (('#04081a', '#18306a', '#0a1838'), ('#2a4ac0', '#5a86ff', '#a8c4ff', '#ffffff')),
    'war':       (('#140a04', '#4e2a10', '#261406'), ('#8a4a14', '#e08a2a', '#ffc86a', '#fff4d0')),
}
OUTLINE = (12, 10, 14, 255)
WOOD = ('#4a2e14', '#7a4e24', '#a8743a')
STEEL = ('#4a4e5a', '#8a92a6', '#d6dcea', '#ffffff')
BONE = ('#7a7058', '#c8bea0', '#f4ecd6')
GOLD = ('#7a5a14', '#d4a030', '#ffe07a')


class Icon:
    def __init__(self, element):
        self.el = element
        bg, fg = ELEMENTS[element]
        self.bg = [hx(c) for c in bg]
        self.fg = [hx(c) for c in fg]
        self.base = [[None] * N for _ in range(N)]
        self.top = [[None] * N for _ in range(N)]
        # 底：外框一圈、裡面上亮下暗的漸層、四角切掉
        for y in range(N):
            for x in range(N):
                if (x in (0, 15) and y in (0, 15)):
                    continue
                if x in (0, 15) or y in (0, 15):
                    self.base[y][x] = self.bg[0]
                else:
                    t = (y - 1) / 13
                    c1, c2 = self.bg[1], self.bg[2]
                    self.base[y][x] = tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3)) + (255,)
        # 內框亮邊
        for i in range(1, 15):
            self.base[1][i] = lighten(self.base[1][i], 0.18)

    # 圖形：c 可以是 0–3（元素色）或顏色字串／tuple
    def col(self, c):
        if isinstance(c, int):
            return self.fg[c]
        if isinstance(c, str):
            return hx(c)
        return c

    def px(self, x, y, c):
        x, y = int(round(x)), int(round(y))
        if 1 <= x <= 14 and 1 <= y <= 14:
            self.top[y][x] = self.col(c)

    def line(self, x0, y0, x1, y1, c):
        x0, y0, x1, y1 = int(round(x0)), int(round(y0)), int(round(x1)), int(round(y1))
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.px(x0, y0, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def thick(self, x0, y0, x1, y1, c, w=2):
        for ox in range(w):
            for oy in range(w):
                self.line(x0 + ox * 0.0 + (ox if abs(y1 - y0) >= abs(x1 - x0) else 0), y0 + (oy if abs(x1 - x0) > abs(y1 - y0) else 0),
                          x1 + (ox if abs(y1 - y0) >= abs(x1 - x0) else 0), y1 + (oy if abs(x1 - x0) > abs(y1 - y0) else 0), c)

    def disc(self, cx, cy, r, c):
        for y in range(N):
            for x in range(N):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 0.3:
                    self.px(x, y, c)

    def ring(self, cx, cy, r, c, w=1.0):
        for y in range(N):
            for x in range(N):
                d = math.hypot(x - cx, y - cy)
                if r - w / 2 - 0.35 <= d <= r + w / 2 + 0.05:
                    self.px(x, y, c)

    def ball(self, cx, cy, r, c0=0, c1=1, c2=2, c3=3):
        """有陰影的球（左上亮）。"""
        for y in range(N):
            for x in range(N):
                d = math.hypot(x - cx, y - cy)
                if d <= r + 0.3:
                    l = math.hypot(x - (cx - r * 0.4), y - (cy - r * 0.4)) / (r * 1.5)
                    self.px(x, y, c3 if l < 0.18 else c2 if l < 0.45 else c1 if l < 0.85 else c0)

    def poly(self, pts, c):
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        for y in range(int(min(ys)), int(max(ys)) + 1):
            for x in range(int(min(xs)), int(max(xs)) + 1):
                if inside(x + 0.0, y + 0.0, pts):
                    self.px(x, y, c)

    def art(self, rows, colors, ox=0, oy=0):
        """像素字串：colors = {字母: 顏色}，. 透明。"""
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch != '.' and ch != ' ':
                    self.px(ox + x, oy + y, colors[ch])

    def outline(self):
        """圖形外圍描一圈深色（只描在底上，不蓋到圖形）。"""
        add = []
        for y in range(1, 15):
            for x in range(1, 15):
                if self.top[y][x] is None:
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < N and 0 <= ny < N and self.top[ny][nx] is not None and self.top[ny][nx][3] == 255 \
                                and self.top[ny][nx] != OUTLINE_SKIP:
                            add.append((x, y))
                            break
        for x, y in add:
            self.top[y][x] = darken(self.base[y][x], 0.65)

    def grid(self):
        out = []
        for y in range(N):
            row = []
            for x in range(N):
                row.append(self.top[y][x] if self.top[y][x] is not None else self.base[y][x])
            out.append(row)
        return out


OUTLINE_SKIP = (0, 0, 0, 1)


def inside(x, y, pts):
    c = False
    j = len(pts) - 1
    for i in range(len(pts)):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / (yj - yi + 1e-9) + xi):
            c = not c
        j = i
    return c


def lighten(c, t):
    return tuple(int(c[i] + (255 - c[i]) * t) for i in range(3)) + (255,)


def darken(c, t):
    return tuple(int(c[i] * (1 - t)) for i in range(3)) + (255,)


# ---- 常用圖形 ----

def sword(i, x0=3, y0=12, x1=12, y1=3, blade=STEEL, hilt=GOLD):
    """斜劍（左下握把、右上劍尖）。"""
    i.line(x0 + 2, y0 - 2, x1, y1, blade[1])
    i.line(x0 + 3, y0 - 2, x1, y1 + 1, blade[0])
    i.line(x0 + 2, y0 - 3, x1 - 1, y1, blade[2])
    i.px(x1, y1, blade[3])
    i.line(x0, y0 - 4, x0 + 4, y0, hilt[1])  # 護手
    i.line(x0, y0, x0 + 1, y0 - 1, WOOD[1])
    i.px(x0 - 1, y0 + 1, hilt[2])


def axe(i, head=STEEL):
    i.line(3, 13, 11, 5, WOOD[1])
    i.line(4, 13, 12, 5, WOOD[0])
    i.poly([(9, 2), (14, 4), (13, 9), (10, 8), (9, 5)], head[1])
    i.line(13, 4, 13, 8, head[2])
    i.line(9, 2, 14, 4, head[2])


def hammer(i, head=STEEL):
    i.line(3, 13, 10, 6, WOOD[1])
    i.line(4, 13, 11, 6, WOOD[0])
    i.poly([(8, 2), (14, 7), (11, 10), (5, 5)], head[1])
    i.line(8, 2, 14, 7, head[2])
    i.line(5, 5, 11, 10, head[0])


def spear(i, x0=2, y0=13, x1=13, y1=2, tip=STEEL):
    i.line(x0, y0, x1 - 3, y1 + 3, WOOD[1])
    i.line(x0 + 1, y0, x1 - 2, y1 + 3, WOOD[0])
    i.poly([(x1 - 4, y1 + 2), (x1, y1), (x1 - 2, y1 + 4)], tip[1])
    i.px(x1, y1, tip[3])
    i.px(x1 - 1, y1 + 1, tip[2])


def arrow(i, x0, y0, x1, y1, shaft=WOOD[2], head=STEEL[2], fletch='#e8e0d0'):
    i.line(x0, y0, x1, y1, shaft)
    dx, dy = x1 - x0, y1 - y0
    L = math.hypot(dx, dy) or 1
    ux, uy = dx / L, dy / L
    px_, py_ = -uy, ux
    i.poly([(x1 + ux * 1.5, y1 + uy * 1.5), (x1 - ux * 1.5 + px_ * 1.5, y1 - uy * 1.5 + py_ * 1.5),
            (x1 - ux * 1.5 - px_ * 1.5, y1 - uy * 1.5 - py_ * 1.5)], head)
    i.px(x1 + ux, y1 + uy, head)
    i.px(x0 + px_, y0 + py_, fletch)
    i.px(x0 - px_, y0 - py_, fletch)
    i.px(x0 + ux + px_, y0 + uy + py_, fletch)
    i.px(x0 + ux - px_, y0 + uy - py_, fletch)


def bolt(i, pts, c=1, hi=3):
    for a, b in zip(pts, pts[1:]):
        i.line(a[0], a[1], b[0], b[1], c)
        i.line(a[0] + 1, a[1], b[0] + 1, b[1], c)
    for a, b in zip(pts, pts[1:]):
        i.line(a[0], a[1], b[0], b[1], hi)


def flame(i, cx, cy, h=6, w=3, c=(1, 2, 3)):
    """一團火（底在 cy，往上 h 格）。"""
    for y in range(int(h) + 1):
        t = y / max(1, h)
        half = w * (1 - t ** 1.6) + 0.3
        wob = math.sin(y * 1.3) * 0.6
        for x in range(int(cx - half - 1), int(cx + half + 2)):
            if abs(x - (cx + wob * t)) <= half:
                d = abs(x - (cx + wob * t)) / (half + 0.01)
                col = c[2] if d < 0.35 and t < 0.6 else c[1] if d < 0.7 else c[0]
                i.px(x, cy - y, col)


def snowflake(i, cx, cy, r, c=2, hi=3):
    for a in range(6):
        ang = math.pi / 3 * a
        ex, ey = cx + math.cos(ang) * r, cy + math.sin(ang) * r
        i.line(cx, cy, ex, ey, c)
        mx, my = cx + math.cos(ang) * r * 0.6, cy + math.sin(ang) * r * 0.6
        for s in (-1, 1):
            i.px(mx + math.cos(ang + s * 1.0) * 1.3, my + math.sin(ang + s * 1.0) * 1.3, c)
    i.px(cx, cy, hi)


def burst(i, cx, cy, r0, r1, n, c):
    for a in range(n):
        ang = 2 * math.pi * a / n
        i.line(cx + math.cos(ang) * r0, cy + math.sin(ang) * r0, cx + math.cos(ang) * r1, cy + math.sin(ang) * r1, c)


def skull(i, ox=4, oy=3, bone=BONE, eye='#1a1010'):
    i.art([
        '..xxxx..',
        '.xxxxxx.',
        'xxxxxxxx',
        'xeexxeex',
        'xeexxeex',
        'xxxxxxxx',
        '.xxnnxx.',
        '..xxxx..',
        '..x.x.x.',
    ], {'x': bone[1], 'e': eye, 'n': eye}, ox, oy)
    i.px(ox + 2, oy + 1, bone[2])
    i.px(ox + 3, oy + 1, bone[2])


def totem(i, face=1, wood=WOOD):
    i.poly([(5, 3), (10, 3), (10, 14), (5, 14)], wood[1])
    i.line(5, 3, 5, 14, wood[0])
    i.line(10, 3, 10, 14, wood[0])
    i.line(5, 7, 10, 7, wood[0])
    i.line(5, 11, 10, 11, wood[0])
    i.px(6, 5, face); i.px(9, 5, face)
    i.line(7, 9, 8, 9, face)
    i.line(4, 3, 11, 3, wood[2])


def ground(i, y=13, c=None):
    i.line(2, y, 13, y, c or '#5a4a3a')
    i.line(2, y + 1, 13, y + 1, '#3a2e24')


def cracks(i, c):
    i.line(7, 13, 5, 10, c); i.line(5, 10, 3, 11, c)
    i.line(8, 13, 10, 10, c); i.line(10, 10, 12, 9, c)


def speed(i, c, ys=(5, 8, 11), x0=2, length=4):
    for y in ys:
        i.line(x0, y, x0 + length, y, c)


def sigil(i, emblem=None):
    """光環的法陣：一圈＋四個點，中間放記號。"""
    i.ring(7.5, 7.5, 6, 0, 1)
    i.ring(7.5, 7.5, 5, 1, 1)
    for x, y in ((7, 1), (8, 1), (1, 7), (1, 8), (14, 7), (14, 8), (7, 14), (8, 14)):
        i.px(x, y, 2)
    if emblem:
        emblem(i)


def horn(i, c=GOLD):
    i.poly([(3, 10), (10, 4), (13, 3), (13, 7), (11, 9), (5, 12)], c[1])
    i.line(13, 3, 13, 7, c[2])
    i.line(3, 10, 5, 12, c[0])
    i.line(4, 10, 10, 5, c[2])


def waves(i, cx, cy, rs, c, a0=-0.9, a1=0.9):
    for r in rs:
        for k in range(24):
            ang = a0 + (a1 - a0) * k / 23
            i.px(cx + math.cos(ang) * r, cy + math.sin(ang) * r, c)


# ---- 每個技能 ----

def S(element):
    return Icon(element)


def heavy_strike():
    i = S('physical'); axe(i); burst(i, 12, 9, 1.5, 3, 6, 3); return i

def cleave():
    i = S('physical'); sword(i, 3, 13, 12, 4)
    waves(i, 6, 9, [6, 7], 2, -1.9, -0.2); return i

def ground_slam():
    i = S('physical'); hammer(i); ground(i); cracks(i, 3); return i

def leap_slam():
    i = S('physical'); hammer(i)
    for k in range(3):
        i.px(2 + k, 4 - k + 3, 2)
    i.line(1, 9, 3, 7, 3); i.line(2, 11, 4, 9, 2); ground(i); return i

def earthquake():
    i = S('physical'); ground(i, 12); cracks(i, 2)
    i.line(4, 4, 6, 7, 3); i.line(6, 7, 5, 9, 3); i.line(11, 3, 9, 6, 3); i.line(9, 6, 11, 9, 3)
    i.disc(7.5, 9, 1.2, 1); return i

def whirling_blades():
    i = S('wind')
    sword(i, 2, 10, 10, 2); waves(i, 8, 8, [5, 6], 2, 0.3, 2.8); speed(i, 1, (11, 13), 1, 3); return i

def flicker_strike():
    i = S('chaos'); sword(i, 5, 13, 13, 5)
    sword(i, 2, 10, 9, 3, blade=('#3e1660', '#6a2ea0', '#9a6ad8', '#cf8aff'), hilt=('#3e1660', '#6a2ea0', '#9a6ad8'))
    return i

def puncture():
    i = S('blood'); spear(i, 2, 13, 13, 2)
    for p in ((10, 9), (11, 11), (9, 12)):
        i.px(p[0], p[1], 2)
    i.px(12, 6, 1); return i

def viper_strike():
    i = S('chaos'); sword(i, 3, 13, 12, 4, hilt=('#2a5a1a', '#4a9a2a', '#9dff7a'))
    i.line(5, 6, 8, 3, 3); i.px(4, 7, 3); i.px(9, 3, 3); i.px(7, 9, 3); i.px(12, 9, 2); return i

def boneshatter():
    i = S('blood'); hammer(i, head=('#7a7058', '#c8bea0', '#f4ecd6', '#ffffff'))
    burst(i, 4, 4, 1, 3, 5, 2); return i

def ancestral_totem():
    i = S('war'); totem(i, '#ffd23a')
    i.line(11, 2, 13, 4, STEEL[2]); i.line(11, 4, 13, 2, STEEL[2]); return i

def dash():
    i = S('wind')
    i.art(['..xx....', '..xxx...', '..xxxx..', 'xxxxxxx.', 'xxxxxxxx'], {'x': '#c8a070'}, 6, 7)
    i.line(6, 12, 13, 12, '#7a4e24')
    speed(i, 2, (5, 8, 11), 1, 4); return i

def split_arrow():
    i = S('physical')
    for (x1, y1) in ((13, 2), (13, 7), (8, 2)):
        arrow(i, 3, 13, x1, y1)
    return i

def burning_arrow():
    i = S('fire'); flame(i, 11, 7, 5, 2.4); arrow(i, 2, 13, 11, 4); return i

def caustic_arrow():
    i = S('chaos'); arrow(i, 2, 13, 11, 4)
    i.disc(11, 11, 2.2, 0); i.disc(12, 10, 1.2, 3); i.px(8, 12, 3); i.px(13, 13, 1); return i

def elemental_hit():
    # 2026-10：幻元箭——箭頭前面火、冰、雷三顆小球
    i = S('physical'); arrow(i, 2, 13, 8, 7)
    for (x, y, dark, mid, light) in ((12.5, 3, '#a8300c', '#ff6a1a', '#ffd070'), (12.5, 10, '#2a78c0', '#5ec4ff', '#e0f8ff'),
                                     (6, 3, '#b08a10', '#ffd23a', '#fff8c0')):
        i.disc(x, y, 2.1, dark); i.disc(x - 0.3, y - 0.3, 1.4, mid); i.px(x - 0.7, y - 0.7, light)
    return i

def frenzy():
    # 2026-10：怒星箭——一支箭加三顆怒星（綠）
    i = S('life'); arrow(i, 2, 13, 11, 4)
    for (x, y) in ((4, 4), (7.5, 2.5), (11.5, 10.5)):
        i.disc(x, y, 1.5, 0); i.disc(x - 0.2, y - 0.2, 1.0, 2); i.px(x - 0.5, y - 0.5, 3)
    return i

def rain_of_arrows():
    i = S('physical')
    for x in (3, 7, 11):
        arrow(i, x, 2, x + 1, 10)
    ground(i); return i

def lightning_arrow():
    i = S('lightning'); arrow(i, 2, 13, 11, 4)
    bolt(i, [(11, 3), (9, 6), (12, 7), (10, 11)], 1, 3); return i

def ice_shot():
    i = S('cold'); arrow(i, 2, 13, 10, 5, head='#b8eeff'); snowflake(i, 11, 4, 2.5); return i

def tornado_shot():
    i = S('wind')
    for k, r in enumerate((5, 4, 3, 2)):
        i.line(7.5 - r, 3 + k * 2.6, 7.5 + r, 3 + k * 2.6, 2 if k % 2 else 1)
    arrow(i, 2, 13, 13, 2); return i

def blink_arrow():
    i = S('chaos'); arrow(i, 2, 13, 11, 4)
    i.ring(12, 4, 2.5, 2); i.px(12, 4, 3); return i

def crossbow_bolt(i, x0, y0, x1, y1, head=STEEL[2]):
    i.line(x0, y0, x1, y1, '#5a4a3a')
    i.line(x0, y0 + 1, x1, y1 + 1, '#3a2e24')
    i.poly([(x1 + 1, y1), (x1 - 2, y1 - 2), (x1 - 2, y1 + 2)], head)

def explosive_bolt():
    i = S('fire'); crossbow_bolt(i, 2, 11, 8, 8); burst(i, 11, 6, 1.5, 4, 8, 2); i.disc(11, 6, 1.5, 3); return i

def permafrost_bolts():
    i = S('cold')
    for y in (4, 8, 12):
        crossbow_bolt(i, 2, y, 11, y - 2, head='#b8eeff')
    snowflake(i, 12, 9, 2); return i

def rapid_fire():
    i = S('physical')
    for k in range(3):
        crossbow_bolt(i, 2 + k * 3, 4 + k * 3, 9 + k * 2, 3 + k * 3)
    return i

def grenade(i, body='#4a4e5a', hi='#8a92a6'):
    i.disc(7, 9, 4, body)
    i.disc(6, 8, 1.5, hi)
    i.line(9, 5, 11, 3, '#c8a070')

def explosive_grenade():
    i = S('fire'); grenade(i); i.px(12, 2, 3); i.px(11, 2, 2); i.px(12, 3, 1); return i

def gas_grenade():
    i = S('chaos'); grenade(i, '#2a3a1a', '#5a7a3a')
    i.disc(12, 4, 1.6, 3); i.disc(13, 7, 1.0, 1); i.px(10, 2, 2); return i

def tempest_flurry():
    i = S('lightning')
    i.line(2, 13, 12, 3, WOOD[1]); i.line(3, 13, 13, 3, WOOD[0])
    bolt(i, [(5, 3), (7, 6), (5, 7), (8, 10)], 1, 3); bolt(i, [(10, 8), (12, 10), (10, 11), (13, 13)], 1, 3); return i

def ice_strike():
    i = S('cold')
    i.line(2, 13, 9, 6, WOOD[1]); i.line(3, 13, 10, 6, WOOD[0])
    waves(i, 7, 8, [4, 5.5, 7], 2, -1.2, 0.2); i.px(13, 4, 3); return i

def storm_wave():
    i = S('lightning')
    for k in range(3):
        i.line(2 + k * 2, 13 - k * 2, 6 + k * 3, 5 - k, 1 if k != 1 else 2)
    bolt(i, [(9, 2), (11, 6), (9, 8), (13, 13)], 1, 3); return i

def killing_palm():
    i = S('physical')
    i.art([
        '...x.x.x....',
        '...x.x.x.x..',
        '..xxxxxxxx..',
        '..xxxxxxxx..',
        'x.xxxxxxxx..',
        'xxxxxxxxx...',
        '.xxxxxxxx...',
        '..xxxxxx....',
        '...xxxxx....',
    ], {'x': '#e8c4a0'}, 2, 3)
    burst(i, 7, 7, 4, 6, 6, 3); return i

def tempest_bell():
    i = S('lightning')
    i.poly([(5, 4), (10, 4), (11, 11), (4, 11)], GOLD[1]); i.line(5, 4, 10, 4, GOLD[2]); i.line(3, 11, 12, 11, GOLD[0])
    i.disc(7.5, 12.5, 1, GOLD[2]); i.line(7, 2, 8, 2, GOLD[0]); i.line(7, 3, 8, 3, GOLD[0])
    bolt(i, [(13, 3), (12, 6), (14, 7)], 1, 3); bolt(i, [(2, 3), (3, 6), (1, 7)], 1, 3); return i

def lightning_spear():
    i = S('lightning'); spear(i, 2, 13, 13, 2, tip=('#b08a10', '#ffd23a', '#fff27a', '#ffffff'))
    bolt(i, [(9, 9), (11, 10), (10, 12), (13, 13)], 1, 3); return i

def whirling_slash():
    i = S('wind'); i.ring(7.5, 7.5, 5.5, 1, 1); i.ring(7.5, 7.5, 3.5, 2, 1)
    spear(i, 3, 12, 12, 3); return i

def whirlwind_lance():
    i = S('wind')
    for k, r in enumerate((5, 4, 3, 2)):
        i.line(4 - r * 0.2, 3 + k * 2.6, 4 + r, 3 + k * 2.6, 2 if k % 2 else 1)
    spear(i, 2, 13, 13, 2); return i

def lunge():
    i = S('physical'); spear(i, 1, 9, 14, 7); speed(i, 2, (4, 12), 1, 5); return i

def hunters_mark():
    i = S('blood'); i.ring(7.5, 7.5, 5, 2, 1)
    i.line(7, 1, 7, 5, 3); i.line(8, 1, 8, 5, 3); i.line(7, 10, 7, 14, 3); i.line(8, 10, 8, 14, 3)
    i.line(1, 7, 5, 7, 3); i.line(1, 8, 5, 8, 3); i.line(10, 7, 14, 7, 3); i.line(10, 8, 14, 8, 3)
    i.disc(7.5, 7.5, 1, 1); return i

def fireball():
    i = S('fire')
    for k in range(4):
        i.disc(4 - k * 0.6 + k * 0, 11 + k * 0.3, 1.5 - k * 0.3, 0)
    i.line(3, 12, 7, 8, 0); i.line(2, 11, 6, 8, 1)
    i.ball(9, 6, 3.6, 0, 1, 2, 3); return i

def frost_nova():
    i = S('cold'); i.ring(7.5, 7.5, 6, 1, 1); i.ring(7.5, 7.5, 4.3, 2, 1); snowflake(i, 7.5, 7.5, 2.6, 3, 3); return i

def spark():
    i = S('lightning')
    for (cx, cy) in ((5, 5), (10, 9), (5, 11)):
        i.disc(cx, cy, 1.3, 2); i.px(cx, cy, 3)
        burst(i, cx, cy, 1.5, 2.6, 4, 1)
    return i

def arc():
    i = S('lightning')
    bolt(i, [(2, 4), (5, 7), (4, 8), (8, 9), (7, 10), (11, 11), (13, 13)], 1, 3)
    for (x, y) in ((2, 4), (8, 9), (13, 13)):
        i.disc(x, y, 1, 2)
    return i

def comet():
    i = S('cold')
    i.line(2, 2, 7, 7, 0); i.line(3, 2, 8, 7, 1); i.line(2, 3, 7, 8, 1); i.line(3, 3, 7, 7, 2)
    i.ball(9.5, 9.5, 3.4, 0, 1, 2, 3); return i

def blizzard():
    i = S('cold')
    for (x, y, r) in ((4, 4, 2), (11, 5, 2.4), (6, 10, 2.4), (12, 11, 1.6)):
        snowflake(i, x, y, r)
    i.line(1, 7, 4, 6, 1); i.line(9, 13, 13, 12, 1); return i

def flame_wall():
    i = S('fire')
    for x in (3, 6, 9, 12):
        flame(i, x, 13, 8 + (x % 2), 1.6)
    return i

def flame_dash():
    i = S('fire'); flame(i, 11, 13, 9, 2.6)
    speed(i, 1, (6, 9, 12), 1, 5); i.px(6, 4, 2); return i

def frostblink():
    i = S('cold'); snowflake(i, 11, 7, 3)
    for k in range(3):
        i.px(2 + k, 7, 1); i.px(3 + k, 5, 0); i.px(3 + k, 9, 0)
    i.ring(4, 7, 2, 1); return i

def flame_totem():
    i = S('fire'); totem(i, '#ffd23a', wood=('#3a1a0a', '#6a2e14', '#9a4a24')); flame(i, 7.5, 3, 3, 2.2); return i

def shockwave_totem():
    i = S('physical'); totem(i, '#ffffff'); waves(i, 7.5, 13, [5, 7], 2, -3.0, -0.15); return i

def _mini_totem(i, x0, wood=('#2a2a32', '#7a7a8a', '#b8b8c8')):
    i.poly([(x0, 6), (x0 + 3, 6), (x0 + 3, 14), (x0, 14)], wood[1])
    i.line(x0, 6, x0, 14, wood[0]); i.line(x0 + 3, 6, x0 + 3, 14, wood[0]); i.line(x0, 10, x0 + 3, 10, wood[0])
    i.line(x0 - 1, 6, x0 + 4, 6, wood[2]); i.px(x0 + 1, 8, '#ffd23a'); i.px(x0 + 2, 8, '#ffd23a')

def searing_bond():
    # 2026-10：焚鏈圖騰——兩根圖騰之間一條火鏈
    i = S('fire'); _mini_totem(i, 1); _mini_totem(i, 11)
    for x in range(4, 12):
        y = 9 + (1 if x % 2 else 0)
        i.px(x, y, '#ff6a1a'); i.px(x, y - 1, '#ffd23a'); i.px(x, y + 1, '#a8300c')
    i.px(7, 6, '#fff0a0'); i.px(9, 6, '#ffd070'); return i

def searing_bond_anomalous():
    # 2026-10：異常・焚鏈圖騰——火鏈加上中間的爆炸
    i = searing_bond(); burst(i, 7.5, 4, 1.2, 3.2, 8, '#fff0a0'); i.disc(7.5, 4, 1.4, '#ffb43a'); i.px(7, 4, '#ffffff'); return i

def decoy_totem():
    # 2026-10：替身圖騰——草人圖騰，旁邊幾個怒氣記號
    i = S('war'); totem(i, '#3a1a0a', wood=('#7a5a1a', '#c8a040', '#ead070'))
    for (x, y) in ((2, 3), (13, 4), (2, 11), (13, 12)):
        i.px(x, y, '#ff3a2a'); i.px(x, y + 1, '#ff3a2a'); i.px(x, y + 3, '#ff3a2a')
    return i

def fire_trap():
    i = S('fire'); i.poly([(3, 9), (12, 9), (13, 13), (2, 13)], '#4a4e5a'); i.line(3, 9, 12, 9, '#8a92a6')
    flame(i, 7.5, 9, 6, 3); return i

def lightning_trap():
    i = S('lightning'); i.poly([(3, 10), (12, 10), (13, 13), (2, 13)], '#4a4e5a'); i.line(3, 10, 12, 10, '#8a92a6')
    for x in (4, 8, 12):
        bolt(i, [(x - 1, 9), (x + 1, 6), (x - 1, 4), (x, 2)], 1, 3)
    return i

def bear_trap():
    i = S('physical')
    i.art([
        'x.x.x.x.x.x.',
        'xxxxxxxxxxxx',
        '.x........x.',
        '.x...oo...x.',
        '.x........x.',
        'xxxxxxxxxxxx',
        'x.x.x.x.x.x.',
    ], {'x': STEEL[1], 'o': '#8a1a1a'}, 2, 4)
    i.line(2, 5, 13, 5, STEEL[2]); return i

def chaos_bolt():
    i = S('chaos')
    for k in range(3):
        i.disc(3 + k * 1.2, 12 - k * 1.2, 1.2, 0)
    i.ball(9, 6, 3.4, 0, 1, 2, 2); i.px(8, 5, 3); i.px(10, 7, 3); return i

def blight_nova():
    i = S('chaos'); i.ring(7.5, 7.5, 6, 0, 1); i.ring(7.5, 7.5, 4, 1, 1)
    i.disc(7.5, 7.5, 2, 3)
    for (x, y) in ((3, 3), (12, 4), (4, 12), (12, 12)):
        i.px(x, y, 2)
    return i

def void_rift():
    i = S('chaos')
    i.poly([(7, 1), (10, 6), (8, 14), (5, 8)], '#08020e')
    i.line(7, 1, 5, 8, 2); i.line(5, 8, 8, 14, 2); i.line(7, 1, 10, 6, 1); i.line(10, 6, 8, 14, 1)
    i.px(7, 7, 3); i.px(8, 10, 2); return i

def detonate_dead():
    i = S('fire'); skull(i, 4, 6); burst(i, 8, 5, 3, 5, 7, 1); i.px(8, 1, 3); return i

def raise_zombie():
    i = S('minion')
    i.art([
        '....gg......',
        '...gggg.....',
        '...gggg..g..',
        '....gg..ggg.',
        '..ggggggggg.',
        '.gggggggg...',
        '.g.gggg.....',
        '...g..g.....',
    ], {'g': '#5a8a4a'}, 2, 2)
    i.px(5, 4, '#ff3a1a'); i.px(6, 4, '#ff3a1a'); ground(i, 12, '#4a3a2a'); return i

def summon_skeletons():
    i = S('minion'); skull(i, 4, 2)
    i.line(4, 13, 11, 13, BONE[1]); i.line(7, 12, 7, 14, BONE[1]); i.line(3, 11, 12, 11, BONE[0]); return i

def raging_spirits():
    i = S('fire')
    for (x, y) in ((5, 11), (11, 9)):
        flame(i, x, y, 6, 2.2)
        i.px(x - 1, y - 3, '#1a0703'); i.px(x + 1, y - 3, '#1a0703')
    return i

def rally():
    i = S('war'); horn(i); i.px(2, 4, 3); i.px(3, 2, 3); i.px(1, 6, 2); return i

def desecrate():
    i = S('chaos'); ground(i, 11, '#3a2a3a')
    i.line(6, 11, 4, 6, BONE[1]); i.line(9, 11, 11, 7, BONE[1]); i.px(4, 5, BONE[2]); i.px(11, 6, BONE[2])
    i.poly([(5, 12), (10, 12), (11, 14), (4, 14)], '#1a0a1a'); i.px(7, 13, 3); return i

def flame_golem():
    i = S('fire')
    i.poly([(4, 5), (11, 5), (12, 11), (3, 11)], '#3a2420'); i.disc(7.5, 4, 2.2, '#4a2e24')
    i.line(4, 7, 11, 7, 1); i.line(5, 9, 10, 9, 2); i.px(6, 4, 3); i.px(9, 4, 3)
    i.line(2, 6, 2, 11, '#3a2420'); i.line(13, 6, 13, 11, '#3a2420'); i.line(4, 12, 5, 14, '#3a2420'); i.line(11, 12, 10, 14, '#3a2420')
    return i

def aura(element, emblem):
    def make():
        i = S(element); sigil(i, emblem); return i
    return make

def em_hatred(i): snowflake(i, 7.5, 7.5, 3, 2, 3)
def em_anger(i): flame(i, 7.5, 11, 6, 2.6)
def em_wrath(i): bolt(i, [(9, 3), (6, 7), (9, 8), (6, 12)], 1, 3)
def em_determination(i): i.poly([(5, 4), (10, 4), (10, 9), (7.5, 12), (5, 9)], STEEL[1]); i.line(5, 4, 10, 4, STEEL[2]); i.line(7, 5, 7, 10, STEEL[0])
def em_pride(i): sword(i, 4, 11, 11, 4); i.px(7, 3, GOLD[2]); i.px(5, 3, GOLD[1]); i.px(9, 3, GOLD[1])
def em_grace(i): i.poly([(5, 11), (10, 3), (11, 4), (7, 11)], '#e8f4ff'); i.line(5, 11, 10, 4, '#8ab0d0')
def em_discipline(i): i.ring(7.5, 7.5, 3, 2, 1); i.disc(7.5, 7.5, 1.6, 3)
def em_haste(i): i.poly([(5, 4), (10, 4), (7.5, 7.5)], 2); i.poly([(5, 11), (10, 11), (7.5, 7.5)], 2); i.line(5, 4, 10, 4, 3); i.line(5, 11, 10, 11, 3)
def em_purity(i):
    i.disc(5.5, 6, 1.5, '#ff6a1a'); i.disc(9.5, 6, 1.5, '#5ec4ff'); i.disc(7.5, 9.5, 1.5, '#ffd23a')
def em_vitality(i): i.art(['.xx.xx.', 'xxxxxxx', 'xxxxxxx', '.xxxxx.', '..xxx..', '...x...'], {'x': '#e83a3a'}, 4, 5); i.px(5, 6, '#ffb0a0')
def em_clarity(i): i.poly([(7.5, 3), (10, 8), (7.5, 11), (5, 8)], 1); i.px(7, 6, 3); i.px(7, 7, 2)
def em_precision(i): i.ring(7.5, 7.5, 3, 2, 1); i.line(7, 2, 7, 13, 3); i.line(2, 7, 13, 7, 3)

def righteous_fire():
    i = S('fire')
    i.art(['..xx..', '..xx..', '.xxxx.', 'x.xx.x', '..xx..', '.x..x.', '.x..x.'], {'x': '#2a0a04'}, 5, 5)
    for x in (3, 5, 10, 12):
        flame(i, x, 13, 6, 1.4)
    flame(i, 7.5, 6, 3, 1.4); return i

def herald(element, emblem):
    def make():
        i = S(element)
        i.poly([(4, 3), (11, 3), (11, 9), (7.5, 13), (4, 9)], 0)
        i.poly([(5, 4), (10, 4), (10, 9), (7.5, 12), (5, 9)], 1)
        emblem(i); return i
    return make

def em_ash(i): flame(i, 7.5, 10, 5, 1.8, ('#3a1a0a', '#ffb43a', '#fff0a0'))
def em_thunder(i): bolt(i, [(8, 4), (6, 7), (9, 8), (7, 11)], 2, 3)
def em_ice(i): snowflake(i, 7.5, 7.5, 2.4, 3, 3)

def cry(element, mark):
    def make():
        i = S(element)
        i.art([
            '..xxxxx...',
            '.xxxxxxx..',
            'xxexxxexx.',
            'xxxxxxxxx.',
            'xxmmmmmxx.',
            'xxmmmmmxx.',
            '.xxmmmxx..',
            '..xxxxx...',
        ], {'x': '#d8a878', 'e': '#1a1010', 'm': '#3a0a0a'}, 1, 4)
        waves(i, 9, 9, [3.5, 5, 6.5], 2, -0.9, 0.9)
        mark(i); return i
    return make

def mk_enduring(i): i.poly([(11, 1), (14, 1), (14, 4), (12.5, 6), (11, 4)], STEEL[1])
def mk_rallying(i): i.line(12, 1, 12, 5, GOLD[2]); i.line(10, 3, 14, 3, GOLD[2])
def mk_intimidating(i): i.art(['.xx.', 'xxxx', 'x..x'], {'x': BONE[1]}, 11, 1)
def mk_star(i): i.px(12, 1, 3); i.line(11, 2, 13, 2, 3); i.px(12, 3, 3); i.px(10, 2, 2); i.px(14, 2, 2)

def armour_piercing_bolt():
    i = S('physical'); i.poly([(9, 3), (13, 3), (13, 11), (9, 11)], STEEL[0]); i.line(9, 3, 9, 11, STEEL[2])
    crossbow_bolt(i, 1, 8, 11, 6); burst(i, 9, 7, 1, 2.5, 5, 3); return i

def shrapnel_shot():
    i = S('physical')
    for (x, y) in ((9, 3), (12, 5), (10, 8), (13, 9), (11, 12), (8, 11)):
        i.disc(x, y, 0.8, STEEL[2])
    crossbow_bolt(i, 1, 8, 6, 8); return i

def bolt_barrage():
    i = S('physical')
    for x in (3, 7, 11):
        i.line(x, 2, x + 1, 9, '#5a4a3a'); i.poly([(x + 1, 11), (x - 1, 8), (x + 3, 8)], STEEL[2])
    ground(i); return i

def concussion_grenade():
    i = S('physical'); grenade(i); waves(i, 7, 9, [5.5, 6.8], 2, -1.4, 1.4); return i

def rend():
    i = S('blood')
    for k in range(3):
        i.line(3 + k * 3, 3, 6 + k * 3, 13, 1); i.line(4 + k * 3, 3, 7 + k * 3, 13, 2)
    i.px(5, 14, 2); i.px(11, 14, 1); return i

def sunder():
    i = S('physical'); axe(i); ground(i)
    i.line(8, 13, 13, 11, 3); i.line(5, 13, 1, 12, 3); return i

def bladestorm():
    i = S('physical')
    for k in range(4):
        ang = k * math.pi / 2 + 0.4
        x1, y1 = 7.5 + math.cos(ang) * 6, 7.5 + math.sin(ang) * 6
        i.line(7.5, 7.5, x1, y1, STEEL[1]); i.line(7.5 + math.cos(ang + 0.3), 7.5 + math.sin(ang + 0.3), x1, y1, STEEL[2])
    i.ring(7.5, 7.5, 6.5, 2); i.disc(7.5, 7.5, 1, GOLD[1]); return i

def skull_crack():
    i = S('physical'); hammer(i); skull(i, 1, 6, eye='#1a1010'); return i


SKILLS = {
    'heavy_strike': heavy_strike, 'cleave': cleave, 'ground_slam': ground_slam, 'leap_slam': leap_slam, 'earthquake': earthquake,
    'whirling_blades': whirling_blades, 'flicker_strike': flicker_strike, 'puncture': puncture, 'viper_strike': viper_strike,
    'boneshatter': boneshatter, 'ancestral_totem': ancestral_totem, 'dash': dash, 'split_arrow': split_arrow,
    'burning_arrow': burning_arrow, 'elemental_hit': elemental_hit, 'frenzy': frenzy, 'caustic_arrow': caustic_arrow, 'rain_of_arrows': rain_of_arrows,
    'lightning_arrow': lightning_arrow, 'ice_shot': ice_shot, 'tornado_shot': tornado_shot, 'blink_arrow': blink_arrow,
    'explosive_bolt': explosive_bolt, 'permafrost_bolts': permafrost_bolts, 'rapid_fire': rapid_fire,
    'explosive_grenade': explosive_grenade, 'gas_grenade': gas_grenade, 'tempest_flurry': tempest_flurry, 'ice_strike': ice_strike,
    'storm_wave': storm_wave, 'killing_palm': killing_palm, 'tempest_bell': tempest_bell, 'lightning_spear': lightning_spear,
    'whirling_slash': whirling_slash, 'whirlwind_lance': whirlwind_lance, 'lunge': lunge, 'hunters_mark': hunters_mark,
    'fireball': fireball, 'frost_nova': frost_nova, 'spark': spark, 'arc': arc, 'comet': comet, 'blizzard': blizzard,
    'flame_wall': flame_wall, 'flame_dash': flame_dash, 'frostblink': frostblink, 'flame_totem': flame_totem, 'searing_bond': searing_bond, 'searing_bond_anomalous': searing_bond_anomalous, 'decoy_totem': decoy_totem,
    'shockwave_totem': shockwave_totem, 'fire_trap': fire_trap, 'lightning_trap': lightning_trap, 'bear_trap': bear_trap,
    'chaos_bolt': chaos_bolt, 'blight_nova': blight_nova, 'void_rift': void_rift, 'detonate_dead': detonate_dead,
    'raise_zombie': raise_zombie, 'summon_skeletons': summon_skeletons, 'raging_spirits': raging_spirits, 'rally': rally,
    'desecrate': desecrate, 'flame_golem': flame_golem,
    'hatred': aura('cold', em_hatred), 'anger': aura('fire', em_anger), 'wrath': aura('lightning', em_wrath),
    'righteous_fire': righteous_fire, 'determination': aura('physical', em_determination), 'pride': aura('blood', em_pride),
    'grace': aura('wind', em_grace), 'discipline': aura('mana', em_discipline), 'haste': aura('wind', em_haste),
    'purity_of_elements': aura('holy', em_purity), 'vitality': aura('life', em_vitality), 'clarity': aura('mana', em_clarity),
    'precision': aura('holy', em_precision),
    'herald_of_ash': herald('fire', em_ash), 'herald_of_thunder': herald('lightning', em_thunder), 'herald_of_ice': herald('cold', em_ice),
    'enduring_cry': cry('war', mk_enduring), 'rallying_cry': cry('war', mk_rallying), 'intimidating_cry': cry('blood', mk_intimidating),
    'star_cry': cry('holy', mk_star),
    'armour_piercing_bolt': armour_piercing_bolt, 'shrapnel_shot': shrapnel_shot, 'bolt_barrage': bolt_barrage,
    'concussion_grenade': concussion_grenade, 'rend': rend, 'sunder': sunder, 'bladestorm': bladestorm, 'skull_crack': skull_crack,
}


# ==== 2026-10：PoE2 補齊的技能圖示 ====

def staff(i, x0=2, y0=13, x1=12, y1=3):
    i.line(x0, y0, x1, y1, WOOD[1]); i.line(x0 + 1, y0, x1 + 1, y1, WOOD[0])
    i.px(x1, y1, GOLD[2]); i.px(x0, y0, GOLD[1])

def bow(i, c=WOOD):
    for y in range(2, 14):
        x = 4 + int(3.2 * math.sin(math.pi * (y - 2) / 11))
        i.px(x, y, c[1]); i.px(x - 1, y, c[0])
    i.line(4, 2, 4, 13, '#e8e0d0')

def banner(i, cloth, emblem=None):
    i.line(3, 1, 3, 14, WOOD[1]); i.line(4, 1, 4, 14, WOOD[0]); i.px(3, 1, GOLD[2])
    i.poly([(5, 2), (13, 2), (13, 10), (9, 8), (5, 10)], cloth)
    i.line(5, 2, 13, 2, lighten(hx(cloth), 0.3))
    if emblem:
        emblem(i)

def palm(i, skin='#e8c4a0'):
    i.art(['..x.x.x...', '..x.x.x.x.', '.xxxxxxxx.', 'x.xxxxxxx.', 'xxxxxxxx..', '.xxxxxxx..', '..xxxxx...'], {'x': skin}, 3, 4)

def curse(element, mark):
    def make():
        i = S(element)
        i.ring(7.5, 7.5, 5.5, 0, 1)
        i.poly([(2, 7.5), (7.5, 4), (13, 7.5), (7.5, 11)], 0)
        i.disc(7.5, 7.5, 2.2, 1); i.disc(7.5, 7.5, 1, '#100810')
        mark(i); return i
    return make

def offering(element, mark):
    def make():
        i = S(element)
        i.poly([(6, 14), (9, 14), (8, 3), (7, 3)], BONE[1]); i.line(7, 3, 7, 13, BONE[2])
        i.line(4, 14, 11, 14, BONE[0])
        mark(i); return i
    return make

def orb(element, deco=None):
    def make():
        i = S(element); i.ball(7.5, 7.5, 4.2, 0, 1, 2, 3)
        if deco:
            deco(i)
        return i
    return make

def skeleton_head(i, ox, oy, helm=None):
    skull(i, ox, oy)
    if helm:
        i.line(ox, oy, ox + 7, oy, helm); i.line(ox + 1, oy - 1, ox + 6, oy - 1, helm)

def A(el, *fs):
    def make():
        i = S(el)
        for f in fs:
            f(i)
        return i
    return make

F_RED = ('#a8300c', '#ff6a1a', '#ffd070')
NEW_SKILLS = {
    # 戰吼、詛咒、祭品、旗
    'fortifying_cry': cry('war', mk_enduring), 'infernal_cry': cry('fire', lambda i: flame(i, 12.5, 5, 4, 1.4)),
    'seismic_cry': cry('physical', lambda i: (ground(i, 13), cracks(i, 3))), 'ancestral_cry': cry('war', lambda i: totem_mini(i)),
    'vulnerability_curse': curse('blood', lambda i: (i.line(2, 2, 5, 5, 3), i.line(10, 10, 13, 13, 3))),
    'flammability_curse': curse('fire', lambda i: flame(i, 12, 14, 4, 1.4)),
    'hypothermia_curse': curse('cold', lambda i: snowflake(i, 12, 12, 2)),
    'conductivity_curse': curse('lightning', lambda i: bolt(i, [(12, 9), (11, 11), (13, 12), (12, 14)], 1, 3)),
    'despair_curse': curse('chaos', lambda i: skull(i, 8, 9)),
    'elemental_weakness_curse': curse('holy', lambda i: (i.disc(3, 12, 1.4, '#ff6a1a'), i.disc(7.5, 13, 1.4, '#5ec4ff'), i.disc(12, 12, 1.4, '#ffd23a'))),
    'enfeeble_curse': curse('chaos', lambda i: (i.line(10, 11, 13, 14, STEEL[1]), i.line(11, 11, 14, 14, STEEL[0]))),
    'temporal_curse': curse('mana', lambda i: (i.poly([(10, 10), (14, 10), (12, 12)], 2), i.poly([(10, 14), (14, 14), (12, 12)], 2))),
    'blood_offering': offering('blood', lambda i: (i.disc(11, 5, 1.6, 1), i.px(11, 7, 1), i.px(11, 9, 2))),
    'bone_offering': offering('minion', lambda i: (i.poly([(10, 3), (14, 3), (14, 7), (12, 9), (10, 7)], STEEL[1]))),
    'soul_offering': offering('chaos', lambda i: flame(i, 12, 9, 6, 1.8, ('#5a1e8c', '#9a4ad8', '#cf8aff'))),
    'war_banner': A('war', lambda i: banner(i, '#b8281e', lambda j: sword(j, 6, 9, 11, 3))),
    'dread_banner': A('blood', lambda i: banner(i, '#2a1a24', lambda j: skull(j, 6, 2, eye='#ff2a1a'))),
    'defiance_banner': A('mana', lambda i: banner(i, '#2a4ac0', lambda j: j.poly([(7, 3), (11, 3), (11, 6), (9, 8), (7, 6)], STEEL[2]))),
    # 錘
    'rolling_slam': A('physical', hammer, ground, lambda i: waves(i, 4, 13, [3, 5], 2, -2.6, -1.6)),
    'volcanic_fissure': A('fire', hammer, lambda i: (ground(i, 13, '#3a1a0a'), i.line(2, 12, 13, 12, 1), i.line(4, 13, 11, 13, 2))),
    'armour_breaker': A('physical', hammer, lambda i: (i.poly([(1, 9), (5, 9), (5, 13), (3, 14), (1, 13)], STEEL[0]), i.line(2, 10, 4, 13, 3))),
    'shield_rush': A('war', lambda i: (i.poly([(7, 3), (13, 3), (13, 9), (10, 13), (7, 9)], STEEL[1]), i.line(7, 3, 13, 3, STEEL[2]), speed(i, 2, (5, 8, 11), 1, 4))),
    'hammer_of_gods': A('holy', lambda i: hammer(i, head=GOLD + ('#ffffff',)), lambda i: burst(i, 11, 5, 3, 5, 6, 3)),
    'shatter_spikes': A('physical', ground, lambda i: [i.poly([(x - 1, 13), (x + 1, 13), (x, 13 - h)], STEEL[2]) for x, h in ((3, 6), (6, 9), (9, 7), (12, 10))]),
    'magma_spray': A('fire', hammer, lambda i: [i.disc(x, y, 1.1, 2) for x, y in ((3, 4), (6, 2), (2, 8))]),
    'true_strike': A('holy', hammer, lambda i: (i.ring(11, 5, 3, 3, 1), i.px(11, 5, 3))),
    'titan_slam': A('war', lambda i: hammer(i, head=('#3a2a1a', '#7a5a3a', '#b88a5a')), ground, lambda i: cracks(i, 2), lambda i: burst(i, 7.5, 12, 3, 5, 5, 3)),
    'stampede': A('physical', lambda i: [i.poly([(x, 10), (x + 3, 10), (x + 3, 13), (x, 13)], '#7a5a3a') for x in (3, 9)], lambda i: speed(i, 2, (4, 7), 1, 6), ground),
    'forge_hammer': A('fire', hammer, lambda i: waves(i, 7.5, 7.5, [6], 2, 0.6, 2.6)),
    # 長棍
    'gale_sweep': A('wind', staff, lambda i: waves(i, 4, 6, [4, 6, 8], 2, -0.6, 1.2)),
    'vault_strike': A('physical', staff, ground, lambda i: burst(i, 12, 12, 1, 3, 5, 3)),
    'whirling_rush': A('wind', lambda i: (i.ring(7.5, 7.5, 5, 1, 1), staff(i)), lambda i: speed(i, 3, (4, 11), 1, 3)),
    'siphon_strike': A('lightning', staff, lambda i: bolt(i, [(10, 9), (12, 11), (10, 12), (13, 14)], 1, 3), lambda i: i.disc(4, 4, 1.5, 2)),
    'reeling_palm': A('physical', palm, lambda i: [i.px(x, 2, 3) for x in (3, 7, 11)]),
    'frost_palm': A('cold', lambda i: palm(i, '#b8eeff'), lambda i: snowflake(i, 12, 4, 2)),
    'frost_wave': A('cold', staff, lambda i: waves(i, 2, 8, [5, 7, 9], 2, -0.9, 0.9)),
    'falling_thunder': A('lightning', staff, lambda i: bolt(i, [(11, 1), (9, 5), (12, 6), (10, 10)], 1, 3)),
    'glacial_cascade': A('cold', lambda i: [i.poly([(x - 1, 13), (x + 1, 13), (x, 13 - h)], 2) for x, h in ((3, 3), (6, 5), (9, 7), (12, 9))], ground),
    # 弓
    'arrow_barrage': A('physical', lambda i: [arrow(i, 2, 4 + k * 3, 12, 3 + k * 3) for k in range(3)]),
    'miasma_arrow': A('chaos', lambda i: arrow(i, 2, 13, 10, 5), lambda i: (i.disc(11, 4, 2.4, 0), i.disc(12, 3, 1.3, 2))),
    'thunder_arrow': A('lightning', lambda i: arrow(i, 2, 13, 9, 6), lambda i: bolt(i, [(12, 1), (11, 5), (13, 6), (11, 10)], 1, 3)),
    'frost_volley': A('cold', lambda i: [arrow(i, 2, 13, x, y, head='#b8eeff') for x, y in ((13, 3), (10, 2), (13, 7))]),
    'shock_arrow': A('lightning', lambda i: arrow(i, 2, 13, 11, 4), lambda i: (i.ring(11.5, 3.5, 2.5, 2), i.px(11, 4, 3))),
    'bramble_arrow': A('life', lambda i: arrow(i, 2, 13, 11, 4), lambda i: [i.line(x, 14, x + d, 10, 1) for x, d in ((8, -1), (11, 1), (13, 0))]),
    'venom_burst_arrow': A('chaos', lambda i: arrow(i, 2, 13, 9, 6), lambda i: burst(i, 11.5, 4.5, 1, 3, 7, 2), lambda i: i.disc(11.5, 4.5, 1, 3)),
    'spiral_volley': A('wind', lambda i: [i.px(7.5 + math.cos(a / 3) * a / 4, 7.5 + math.sin(a / 3) * a / 4, 2) for a in range(4, 28)], lambda i: arrow(i, 9, 9, 13, 3)),
    'blast_arrow': A('fire', lambda i: arrow(i, 2, 13, 9, 6), lambda i: burst(i, 11.5, 4.5, 1, 3.5, 8, 2), lambda i: i.disc(11.5, 4.5, 1.2, 3)),
    'chain_arrow': A('lightning', lambda i: arrow(i, 2, 13, 8, 7), lambda i: bolt(i, [(9, 6), (12, 7), (10, 3), (13, 2)], 1, 3)),
    'snipe': A('physical', lambda i: arrow(i, 1, 14, 13, 2), lambda i: (i.ring(11, 4, 2.5, 3), i.px(11, 4, 3))),
    # 弩
    'galvanic_bolts': A('lightning', lambda i: [crossbow_bolt(i, 2, 8, 12, y) for y in (3, 8, 13)], lambda i: i.px(13, 8, 3)),
    'siege_salvo': A('fire', lambda i: [i.disc(x, 4, 1.4, STEEL[1]) for x in (4, 8, 12)], ground, lambda i: burst(i, 8, 11, 1, 3, 6, 2)),
    'hail_rounds': A('cold', lambda i: [i.disc(x, y, 1.2, 2) for x, y in ((4, 3), (8, 5), (12, 3), (6, 9), (11, 10))], ground),
    'piercing_rounds': A('physical', lambda i: crossbow_bolt(i, 1, 8, 13, 8), lambda i: [i.line(x, 5, x, 11, STEEL[0]) for x in (5, 9)]),
    'incendiary_shot': A('fire', lambda i: crossbow_bolt(i, 1, 8, 6, 8), lambda i: [flame(i, x, y, 2, 1) for x, y in ((10, 5), (12, 9), (9, 12))]),
    'frost_bolt_round': A('cold', lambda i: crossbow_bolt(i, 1, 6, 10, 6, head='#b8eeff'), lambda i: (ground(i, 13, '#2a78c0'), snowflake(i, 12, 10, 2))),
    'storm_bolts': A('lightning', lambda i: [crossbow_bolt(i, 2, y, 11, y - 2) for y in (5, 9, 13)], lambda i: bolt(i, [(13, 3), (12, 6), (14, 7)], 1, 3)),
    'oil_grenade': A('fire', lambda i: grenade(i, '#3a2a14', '#7a5a2a'), lambda i: flame(i, 12, 6, 4, 1.6)),
    'voltaic_grenade': A('lightning', grenade, lambda i: bolt(i, [(12, 1), (11, 4), (13, 5)], 1, 3)),
    'cluster_grenade': A('fire', grenade, lambda i: [i.disc(x, y, 1, 2) for x, y in ((12, 3), (13, 6), (11, 1))]),
    # 矛
    'storm_javelin': A('lightning', lambda i: spear(i, 2, 13, 12, 3), lambda i: bolt(i, [(13, 1), (12, 4), (14, 5)], 1, 3)),
    'spear_thicket': A('physical', ground, lambda i: [i.line(x, 13, x, 4 + (x % 3), WOOD[1]) or i.px(x, 3 + (x % 3), STEEL[2]) for x in (3, 6, 9, 12)]),
    'rapid_thrust': A('blood', lambda i: [spear(i, 1, 6 + k * 3, 13, 5 + k * 3) for k in range(3)]),
    'explosive_spear': A('fire', lambda i: spear(i, 2, 13, 11, 4), lambda i: burst(i, 12, 3, 1, 3, 7, 2)),
    'fangs_of_frost': A('cold', lambda i: spear(i, 2, 13, 13, 2, tip=('#2a78c0', '#5ec4ff', '#b8eeff', '#ffffff')), lambda i: [i.px(x, y, 3) for x, y in ((11, 7), (12, 9), (8, 3))]),
    'gale_spear': A('wind', lambda i: spear(i, 2, 13, 13, 2), lambda i: [i.line(3, y, 3 + w, y, 2) for y, w in ((4, 5), (7, 3), (10, 4))]),
    'sweeping_repel': A('wind', lambda i: i.ring(7.5, 7.5, 5.5, 2, 1), lambda i: spear(i, 3, 12, 12, 3)),
    'serpent_fury': A('wind', lambda i: spear(i, 1, 13, 14, 2), lambda i: [i.px(7.5 + math.sin(t / 2) * 2, 13 - t, 2) for t in range(0, 11)]),
    'prism_rend': A('holy', lambda i: spear(i, 2, 13, 10, 5), lambda i: (i.disc(12, 3, 1.5, '#ff6a1a'), i.disc(13, 7, 1.3, '#5ec4ff'), i.disc(9, 2, 1.3, '#ffd23a'))),
    'thunder_leap': A('lightning', lambda i: spear(i, 4, 13, 13, 4), ground, lambda i: bolt(i, [(3, 2), (2, 6), (4, 7), (2, 11)], 1, 3)),
    'blood_harvest': A('blood', lambda i: spear(i, 2, 13, 11, 4), lambda i: [i.disc(x, y, 0.9, 2) for x, y in ((12, 8), (13, 11), (10, 12), (8, 10))]),
    'sunfire_spear': A('holy', lambda i: spear(i, 2, 13, 12, 3, tip=GOLD + ('#ffffff',)), lambda i: burst(i, 12, 3, 1.5, 3.5, 8, 3)),
    # 法術
    'firestorm': A('fire', lambda i: [i.line(x, 1, x - 2, 8, 2) for x in (4, 8, 12)], lambda i: [flame(i, x, 13, 4, 1.4) for x in (3, 8, 13)]),
    'scorch_breath': A('fire', lambda i: [i.poly([(2, 7), (13, 2), (13, 13)], 1), i.poly([(2, 7), (10, 4), (10, 10)], 2)]),
    'ember_volley': A('fire', lambda i: [i.disc(x, y, 1.3, 2) or i.px(x, y, 3) for x, y in ((3, 12), (6, 9), (9, 6), (12, 3))]),
    'frost_snap': A('cold', lambda i: burst(i, 7.5, 7.5, 2, 6, 8, 2), lambda i: snowflake(i, 7.5, 7.5, 2.4, 3, 3)),
    'frost_orb': orb('cold', lambda i: snowflake(i, 7.5, 7.5, 2, 3, 3)),
    'frostbolt': A('cold', lambda i: [i.line(2, 13 - k, 8, 7 - k, 1) for k in range(2)], lambda i: i.poly([(7, 4), (13, 2), (11, 8)], 2)),
    'ball_lightning': orb('lightning', lambda i: bolt(i, [(6, 4), (8, 7), (6, 8), (9, 11)], 3, 3)),
    'essence_drain': A('chaos', lambda i: i.ball(9, 6, 3.4, 0, 1, 2, 3), lambda i: [i.line(2, 12 - k, 6, 8 - k, 1) for k in range(2)], lambda i: i.px(9, 6, '#9dff7a')),
    'contagion': A('chaos', lambda i: [i.disc(x, y, r, 1) for x, y, r in ((5, 6, 2), (10, 5, 1.6), (8, 10, 2.2), (12, 11, 1.2))], lambda i: i.px(8, 10, 3)),
    'storm_orb': orb('lightning', lambda i: (bolt(i, [(4, 12), (3, 14)], 1, 3), bolt(i, [(11, 12), (12, 14)], 1, 3))),
    'lightning_pillar': A('lightning', lambda i: [i.line(x, 1, x, 13, 1 if x in (6, 9) else 3) for x in (6, 7, 8, 9)], ground),
    'hex_burst': A('chaos', lambda i: i.ring(7.5, 7.5, 5.5, 1, 1), lambda i: burst(i, 7.5, 7.5, 1, 4, 8, 2), lambda i: i.disc(7.5, 7.5, 1.2, 3)),
    'bone_storm': A('minion', lambda i: [i.line(x, y, x + 4, y - 4, BONE[1]) or i.px(x + 4, y - 4, BONE[2]) for x, y in ((2, 8), (5, 13), (8, 9))]),
    'void_bolt': A('chaos', lambda i: i.ball(7.5, 7.5, 3, 0, 1, 2, 3), lambda i: [i.disc(x, y, 0.9, 1) for x, y in ((2, 2), (13, 3), (2, 13), (13, 12))]),
    'frost_mine': A('cold', lambda i: i.poly([(7.5, 2), (12, 8), (7.5, 13), (3, 8)], 1), lambda i: i.poly([(7.5, 4), (10, 8), (7.5, 11), (5, 8)], 2), lambda i: i.px(7, 6, 3)),
    'ice_wall': A('cold', lambda i: [i.poly([(x, 13), (x + 2, 13), (x + 2, 13 - h), (x + 1, 12 - h), (x, 13 - h)], 2 if x % 2 else 1) for x, h in ((2, 7), (5, 9), (8, 8), (11, 6))], ground),
    'storm_blink': A('lightning', lambda i: bolt(i, [(2, 12), (6, 8), (5, 7), (10, 4), (13, 2)], 1, 3), lambda i: (i.ring(3, 12, 1.8, 2), i.ring(12, 3, 1.8, 2))),
    'frost_blink': frostblink, 'flame_blink': flame_dash,
    'inferno_blast': A('fire', lambda i: i.ring(7.5, 7.5, 6, 0, 1), lambda i: i.ball(7.5, 7.5, 3.6, 0, 1, 2, 3), lambda i: burst(i, 7.5, 7.5, 4.2, 6, 8, 2)),
    'sun_orb': orb('holy', lambda i: burst(i, 7.5, 7.5, 4.6, 6.2, 8, 2)),
    'ember_seed': A('fire', lambda i: (i.disc(7.5, 9, 2.6, '#3a1a0a'), i.disc(7.5, 9, 1.4, 2)), lambda i: flame(i, 7.5, 6, 4, 1.4)),
    'winter_eye': A('cold', lambda i: i.poly([(2, 7.5), (7.5, 4), (13, 7.5), (7.5, 11)], 1), lambda i: i.disc(7.5, 7.5, 2, 3), lambda i: i.disc(7.5, 7.5, 1, '#0a2238')),
    'static_field': A('lightning', lambda i: i.ring(7.5, 9, 5, 1, 1), lambda i: [bolt(i, [(x, 4), (x + 1, 7), (x - 1, 9)], 2, 3) for x in (5, 10)]),
    'bone_spikes': A('minion', ground, lambda i: [i.poly([(x - 1, 13), (x + 1, 13), (x, 13 - h)], BONE[1]) for x, h in ((3, 5), (6, 8), (9, 9), (12, 6))]),
    'dark_idol': A('chaos', lambda i: totem(i, '#9dff7a', wood=('#1a0a24', '#3e1660', '#6a2ea0')), lambda i: i.disc(7.5, 2, 1.2, 2)),
    # 召喚
    'beast_companion': A('wind', lambda i: i.art([
        '.x.......x..', 'xx.......xx.', 'xxxxxxxxxxx.', 'xxexxxxxexx.', 'xxxxxxxxxxx.', '.xxxnnnxxx..', '..xxxxxxx...', '...x...x....'],
        {'x': '#9a8a7a', 'e': '#ffd23a', 'n': '#1a1010'}, 2, 3)),
    'skeletal_brute': A('minion', lambda i: skeleton_head(i, 4, 3, '#3a3a3a'), lambda i: axe(i)),
    'frost_wraith': A('cold', lambda i: skeleton_head(i, 4, 3, '#5ec4ff'), lambda i: snowflake(i, 12, 12, 2)),
    'skeletal_archers': A('minion', lambda i: skeleton_head(i, 6, 3), lambda i: bow(i)),
    'skeletal_pyromancer': A('fire', lambda i: skeleton_head(i, 4, 5, GOLD[1]), lambda i: flame(i, 12, 5, 4, 1.4)),
    'skeletal_stormcaller': A('lightning', lambda i: skeleton_head(i, 4, 5), lambda i: bolt(i, [(12, 1), (11, 4), (13, 5)], 1, 3)),
    'skeletal_frostcaller': A('cold', lambda i: skeleton_head(i, 4, 5, '#b8eeff'), lambda i: snowflake(i, 12, 3, 2)),
    'skeletal_reaver': A('minion', lambda i: skeleton_head(i, 4, 6), lambda i: (sword(i, 1, 6, 6, 1), sword(i, 9, 6, 14, 1))),
    'bone_raise': A('minion', lambda i: ground(i, 12, '#3a2a1a'), lambda i: skull(i, 4, 3), lambda i: i.line(7, 12, 7, 14, BONE[1])),
    # 常駐、光環
    'frost_aegis': aura('cold', lambda i: i.poly([(5, 4), (10, 4), (10, 9), (7.5, 12), (5, 9)], '#b8eeff')),
    'battle_rage': aura('blood', lambda i: (i.disc(7.5, 7.5, 2.4, '#c8282a'), i.px(7, 6, '#ff6a5a'))),
    'storm_staff': aura('lightning', lambda i: (staff(i, 4, 11, 11, 4), bolt(i, [(9, 6), (7, 8)], 3, 3))),
    'ruin_mantra': aura('war', lambda i: burst(i, 7.5, 7.5, 1, 3.5, 8, 3)),
    'attrition': aura('blood', lambda i: (i.poly([(5, 4), (10, 4), (7.5, 7.5)], 2), i.poly([(5, 11), (10, 11), (7.5, 7.5)], 2))),
    'mana_storm': aura('mana', lambda i: (i.disc(7.5, 7.5, 2.4, 2), burst(i, 7.5, 7.5, 2.6, 4, 6, 3))),
    'wind_dancer': aura('wind', em_grace), 'magma_barrier': aura('fire', em_determination),
    'combat_frenzy': aura('war', lambda i: (sword(i, 4, 11, 11, 4), i.px(11, 10, 3))), 'archmage': aura('mana', em_clarity),
    'elemental_conflux': aura('holy', em_purity), 'herald_of_plague': herald('chaos', lambda i: (i.disc(7.5, 8, 1.8, 3), i.px(6, 6, 2))),
}

def totem_mini(i):
    i.poly([(11, 1), (14, 1), (14, 6), (11, 6)], WOOD[1]); i.px(12, 3, '#ffd23a'); i.px(13, 3, '#ffd23a')

SKILLS.update(NEW_SKILLS)


NEW_SKILLS2 = {
    'flame_stream': A('fire', lambda i: i.poly([(2, 8), (13, 3), (13, 13)], 0), lambda i: i.poly([(2, 8), (12, 5), (12, 11)], 1), lambda i: i.poly([(2, 8), (9, 7), (9, 9)], 3)),
    'frost_stream': A('cold', lambda i: i.poly([(2, 8), (13, 3), (13, 13)], 0), lambda i: i.poly([(2, 8), (12, 5), (12, 11)], 1), lambda i: snowflake(i, 10, 8, 2, 3, 3)),
    'storm_lash': A('lightning', lambda i: bolt(i, [(2, 13), (5, 9), (4, 8), (8, 6), (7, 5), (11, 3), (13, 4)], 1, 3), lambda i: speed(i, 2, (11, 13), 7, 5)),
    'charged_shot': A('physical', bow, lambda i: arrow(i, 5, 8, 13, 8), lambda i: [i.px(x, 4, 3) for x in (7, 9, 11)]),
    'bound_spectre': A('chaos', lambda i: i.art(['..xxxx..', '.xxxxxx.', 'xxexxexx', 'xxxxxxxx', '.xx..xx.', '.x....x.', 'x......x'], {'x': '#9a4ad8', 'e': '#9dff7a'}, 4, 3), ground),
    'skeletal_cleric': A('life', lambda i: skeleton_head(i, 4, 5, GOLD[1]), lambda i: (i.line(12, 2, 12, 6, '#ffffff'), i.line(10, 4, 14, 4, '#ffffff'))),
    # 2026-10：可以直接放的動作技能
    'escape_shot': A('cold', bow, lambda i: arrow(i, 5, 8, 13, 8), lambda i: speed(i, 3, (4, 12), 0, 3)),
    'raise_guard': A('physical', lambda i: i.poly([(4, 3), (11, 3), (11, 9), (7.5, 13), (4, 9)], STEEL[1]),
                     lambda i: i.poly([(5, 4), (10, 4), (10, 8.5), (7.5, 11.5), (5, 8.5)], STEEL[2]), lambda i: i.line(7, 5, 7, 10, GOLD[1])),
}
SKILLS.update(NEW_SKILLS2)

# 2026-10：德魯伊
def paw(i, cx=7.5, cy=9, c=1, toe=2):
    i.disc(cx, cy, 2.6, c)
    for dx, dy in ((-3.2, -3), (-1, -4.6), (1.4, -4.6), (3.6, -3)):
        i.disc(cx + dx, cy + dy, 1.0, toe)


def claws(i, c=2, n=3, x0=3, y0=3, length=9):
    for k in range(n):
        i.line(x0 + k * 3, y0, x0 + k * 3 + length * 0.5, y0 + length, c)
        i.line(x0 + k * 3 + 1, y0, x0 + k * 3 + 1 + length * 0.5, y0 + length, 3)


def wing(i, c=1, hi=2):
    i.poly([(2, 4), (13, 7), (11, 10), (8, 9), (6, 12), (4, 9)], c)
    for x0 in (5, 8, 11):
        i.line(3, 5, x0, 9 + (x0 == 5) * 2, hi)


def leaf(i, cx, cy, c='#5ad84a', hi='#b8ff9a'):
    i.poly([(cx - 3, cy + 3), (cx - 1, cy - 3), (cx + 3, cy - 3), (cx + 1, cy + 3)], c)
    i.line(cx - 2, cy + 2, cx + 2, cy - 2, hi)


def vine(i, c='#2a8a2a', hi='#5ad84a'):
    pts = [(2, 13), (4, 10), (3, 7), (6, 5), (8, 7), (10, 4), (13, 3)]
    for (a, b), (c2, d) in zip(pts, pts[1:]):
        i.line(a, b, c2, d, c)
    for x, y in pts[1::2]:
        i.px(x + 1, y, hi)


def wolf_head(i, c='#a8a8b8', eye='#7ad8ff'):
    i.art(['x......x', 'xx....xx', 'xxxxxxxx', 'xexxxxex', '.xxxxxx.', '..xxxx..', '...nn...'],
          {'x': c, 'e': eye, 'n': '#1a1010'}, 4, 4)


DRUID_SKILLS = {
    'bear_maul': A('physical', lambda i: paw(i, 7.5, 9, '#7a4e24', '#a8743a'), lambda i: burst(i, 7.5, 9, 4, 6, 6, 3)),
    'bear_slam': A('war', lambda i: paw(i, 7.5, 7, '#7a4e24', '#a8743a'), ground, lambda i: cracks(i, 2)),
    'mountain_fury': A('physical', lambda i: i.poly([(2, 13), (7, 3), (13, 13)], 1), lambda i: i.poly([(5, 7), (7, 3), (9, 7)], 3), lambda i: paw(i, 7.5, 11, '#7a4e24', '#a8743a')),
    'rampage': A('war', lambda i: paw(i, 9, 8, '#7a4e24', '#a8743a'), lambda i: speed(i, 2, (5, 8, 11), 1, 4)),
    'walking_calamity': A('fire', lambda i: paw(i, 7.5, 7, '#5a1c0a', '#a8300c'), ground, lambda i: flame(i, 4, 12, 4, 2), lambda i: flame(i, 11, 12, 4, 2)),
    'wolf_shred': A('physical', claws),
    'wolf_rend': A('blood', claws, lambda i: [i.px(x, 13, 2) for x in (4, 8, 12)]),
    'pounce': A('cold', wolf_head, lambda i: speed(i, 2, (3, 13), 0, 3)),
    'cross_slash': A('cold', lambda i: (i.line(2, 2, 13, 13, 2), i.line(3, 2, 13, 12, 3), i.line(13, 2, 2, 13, 2), i.line(12, 2, 2, 12, 3))),
    'arctic_howl': A('cold', wolf_head, lambda i: waves(i, 8, 8, (5, 7), 2)),
    'lunar_blessing': A('mana', lambda i: i.disc(8, 8, 5, 2), lambda i: i.disc(10, 6, 4, '#0a1838'), lambda i: i.px(4, 4, 3)),
    'wing_blast': A('wind', wing, lambda i: waves(i, 4, 8, (8, 10), 2, -0.6, 0.6)),
    'rolling_magma': A('fire', lambda i: i.ball(8, 8, 4.5), ground),
    'devour': A('blood', lambda i: i.art(['xxxxxxxx', 'x.t.t.tx', 'x......x', 'x.t.t.tx', 'xxxxxxxx'], {'x': '#c8282a', 't': '#f4ecd6'}, 4, 5)),
    'oil_barrage': A('chaos', lambda i: [i.disc(x, y, 1.6, 0) for x, y in ((4, 5), (9, 4), (6, 9), (11, 9), (8, 13))], lambda i: [i.px(x - 0.5, y - 0.5, 2) for x, y in ((4, 5), (9, 4), (6, 9), (11, 9))]),
    'flame_breath': A('fire', lambda i: i.poly([(2, 8), (13, 3), (13, 13)], 0), lambda i: i.poly([(2, 8), (12, 5), (12, 11)], 1), lambda i: flame(i, 9, 8, 4, 2)),
    'thunderstorm': A('lightning', lambda i: [i.disc(x, 4, 2.2, '#8a92a6') for x in (5, 8, 11)], lambda i: bolt(i, [(8, 6), (6, 9), (9, 10), (7, 14)], 1, 3)),
    'primal_tornado': A('wind', lambda i: [i.line(3 + k, 3 + k * 2, 13 - k, 3 + k * 2, 1 + (k % 2)) for k in range(5)], ground),
    'thrashing_vines': A('life', vine, lambda i: leaf(i, 10, 10)),
    'volcano': A('fire', lambda i: i.poly([(2, 14), (6, 7), (10, 7), (14, 14)], '#46403a'), lambda i: flame(i, 8, 6, 5, 3)),
    'entangle': A('life', vine, lambda i: (i.line(3, 3, 13, 13, '#2a8a2a'), i.line(13, 3, 3, 13, '#5ad84a'))),
    'barkskin': A('life', lambda i: i.poly([(4, 3), (11, 3), (11, 9), (7.5, 13), (4, 9)], '#7a4e24'), lambda i: [i.line(x, 4, x, 10, '#4a2e14') for x in (6, 9)], lambda i: leaf(i, 8, 7)),
    'briarpatch': A('life', vine, lambda i: [i.px(x, y, '#f4ecd6') for x, y in ((5, 9), (7, 6), (9, 6), (12, 4))]),
    'savage_fury': A('blood', claws, lambda i: burst(i, 8, 8, 5, 7, 8, 3)),
    'wolf_pack': A('minion', lambda i: wolf_head(i, '#bdb59a'), lambda i: (i.px(2, 12, 2), i.px(13, 12, 2))),
}
SKILLS.update(DRUID_SKILLS)


# 2026-10：PoE2 主動技能第 1 批
def shield_shape(i, c=None, hi=None):
    i.poly([(4, 3), (11, 3), (11, 9), (7.5, 13), (4, 9)], c or STEEL[1])
    i.poly([(5, 4), (10, 4), (10, 8.5), (7.5, 11.5), (5, 8.5)], hi or STEEL[2])


POE2_BATCH1 = {
    'supercharged_slam': A('physical', lambda i: hammer(i), ground, lambda i: cracks(i, 3), lambda i: burst(i, 8, 12, 3, 5, 6, 2)),
    'resonating_shield': A('physical', shield_shape, lambda i: waves(i, 8, 8, (6, 7), 3, -3.2, 3.2)),
    'gathering_storm': A('lightning', lambda i: i.line(2, 13, 13, 2, WOOD[2]), lambda i: bolt(i, [(3, 9), (6, 7), (5, 6), (9, 4)], 1, 3), lambda i: speed(i, 2, (10, 12), 1, 3)),
    'disengage': A('physical', lambda i: spear(i, 2, 13, 12, 3), lambda i: speed(i, 2, (6, 9, 12), 10, 3)),
    'glacial_lance': A('cold', lambda i: spear(i, 2, 13, 13, 2, tip=('#2a78c0', '#5ec4ff', '#b8eeff', '#ffffff')), lambda i: snowflake(i, 4, 4, 2, 2, 3)),
    'primal_strikes': A('lightning', lambda i: spear(i, 2, 13, 13, 2), lambda i: bolt(i, [(9, 9), (11, 7), (10, 6), (13, 4)], 1, 3)),
    'cull_the_weak': A('blood', lambda i: spear(i, 2, 13, 13, 2), lambda i: i.disc(11, 11, 2, 2)),
    'toxic_growth': A('chaos', lambda i: [i.disc(x, y, 1.6, 1) for x, y in ((4, 10), (8, 6), (12, 10))], lambda i: [i.px(x, y - 1, 3) for x, y in ((4, 10), (8, 6), (12, 10))], ground),
    'magnetic_salvo': A('lightning', lambda i: [i.line(3 + k * 2, 13, 5 + k * 2, 4, 1) for k in range(5)], lambda i: [i.px(5 + k * 2, 4, 3) for k in range(5)]),
    'glacial_bolt': A('cold', lambda i: crossbow_bolt(i, 2, 12, 12, 3), lambda i: [i.line(x, 9, x, 13, 2) for x in (9, 11, 13)]),
    'plasma_blast': A('lightning', lambda i: crossbow_bolt(i, 2, 12, 9, 5), lambda i: i.ball(10.5, 5, 3.2), lambda i: burst(i, 10.5, 5, 3.5, 5, 8, 3)),
    'frost_darts': A('cold', lambda i: [i.line(2, 4 + k * 4, 11, 2 + k * 4, 2) for k in range(3)], lambda i: [i.px(12, 2 + k * 4, 3) for k in range(3)]),
    'profane_ritual': A('chaos', lambda i: i.ring(8, 8, 5, 1, 1.2), lambda i: [i.line(8, 3, 11, 12, 2), i.line(11, 12, 4, 6, 2), i.line(4, 6, 12, 6, 2), i.line(12, 6, 5, 12, 2), i.line(5, 12, 8, 3, 2)]),
    'herald_of_blood': A('blood', lambda i: sigil(i), lambda i: [i.disc(x, y, 1.2, 2) for x, y in ((8, 5), (6, 9), (10, 9))]),
    'berserk': A('blood', lambda i: flame(i, 8, 12, 9, 4, (0, 1, 2)), lambda i: i.line(6, 8, 10, 8, 3)),
    'overwhelming_presence': A('war', lambda i: sigil(i), lambda i: waves(i, 8, 8, (6,), 3, -3.2, 3.2)),
    'withering_presence': A('chaos', lambda i: skull(i, 4, 3, BONE), lambda i: waves(i, 8, 8, (7,), 1, -3.2, 3.2)),
}
SKILLS.update(POE2_BATCH1)


# 2026-10：PoE2 主動技能第 2 批
def target_ring(i, c, hi):
    i.ring(8, 8, 5, c, 1.2)
    i.ring(8, 8, 2, hi, 1.0)
    for x, y, x2, y2 in ((8, 1, 8, 4), (8, 12, 8, 15), (1, 8, 4, 8), (12, 8, 15, 8)):
        i.line(x, y, x2, y2, c)


POE2_BATCH2 = {
    'voltaic_mark': A('lightning', lambda i: target_ring(i, 1, 3), lambda i: bolt(i, [(9, 4), (7, 8), (9, 9), (7, 13)], 2, 3)),
    'freezing_mark': A('cold', lambda i: target_ring(i, 1, 3), lambda i: snowflake(i, 8, 8, 2, 2, 3)),
    'bloodhounds_mark': A('blood', lambda i: target_ring(i, 1, 3), lambda i: i.disc(8, 9, 1.6, 2)),
    'ice_tipped_arrows': A('cold', bow, lambda i: arrow(i, 4, 12, 12, 4, head='#b8eeff'), lambda i: snowflake(i, 12, 4, 1, 2, 3)),
    'emergency_reload': A('war', lambda i: crossbow_bolt(i, 2, 12, 12, 3), lambda i: crossbow_bolt(i, 2, 14, 12, 5), lambda i: speed(i, 2, (3, 6), 10, 3)),
    'toxic_domain': A('chaos', lambda i: i.ring(8, 9, 5, 1, 1.4), lambda i: [i.disc(x, y, 1.2, 2) for x, y in ((6, 8), (10, 8), (8, 11))], lambda i: i.px(8, 9, 3)),
    'blink': A('mana', lambda i: speed(i, 1, (5, 8, 11), 2, 5), lambda i: i.disc(11, 8, 2.2, 3)),
    'time_of_need': A('holy', lambda i: sigil(i), lambda i: (i.line(8, 5, 8, 11, 3), i.line(5, 8, 11, 8, 3))),
    'ghost_dance': A('mana', lambda i: i.art(['..xxxx..', '.xxxxxx.', 'xxexxexx', 'xxxxxxxx', 'xxxxxxxx', 'x.xx.xx.', '........'], {'x': '#a8c4ff', 'e': '#0a1838'}, 4, 4)),
    'scavenged_plating': A('physical', lambda i: i.poly([(4, 3), (11, 3), (11, 9), (7.5, 13), (4, 9)], STEEL[1]), lambda i: [i.line(5, y, 10, y, STEEL[3]) for y in (5, 8, 10)]),
    'eternal_rage': A('blood', lambda i: flame(i, 8, 13, 10, 5, (0, 1, 2)), lambda i: flame(i, 8, 13, 5, 2, (2, 3, 3))),
    'mana_remnants': A('mana', lambda i: [i.disc(x, y, 1.4, 2) for x, y in ((5, 6), (10, 5), (8, 10), (12, 11), (4, 12))], lambda i: [i.px(x - 0.5, y - 0.5, 3) for x, y in ((5, 6), (10, 5), (8, 10))]),
    'alchemists_boon': A('life', lambda i: i.poly([(6, 3), (10, 3), (10, 6), (13, 12), (3, 12), (6, 6)], '#c8e8c8'), lambda i: i.poly([(4.5, 9), (11.5, 9), (12.5, 11.5), (3.5, 11.5)], 1), lambda i: i.line(6, 3, 10, 3, WOOD[1])),
    'ravenous_swarm': A('chaos', lambda i: [i.disc(x, y, 1.0, 1) for x, y in ((4, 4), (8, 6), (12, 4), (5, 10), (10, 11), (13, 8))], lambda i: [i.px(x, y, 3) for x, y in ((4, 4), (8, 6), (12, 4), (5, 10), (10, 11), (13, 8))]),
}
SKILLS.update(POE2_BATCH2)


# 2026-10：PoE2 主動技能第 3 批
POE2_BATCH3 = {
    'trail_of_caltrops': A('physical', lambda i: [i.poly([(x - 1.5, y + 1), (x, y - 1.5), (x + 1.5, y + 1)], STEEL[2]) for x, y in ((4, 11), (8, 9), (12, 11))], lambda i: speed(i, 2, (4, 6), 2, 6)),
    'mirage_archer': A('mana', bow, lambda i: arrow(i, 5, 8, 13, 8), lambda i: [i.px(x, y, 3) for x, y in ((3, 3), (12, 13), (2, 12))]),
    'plague_bearer': A('chaos', lambda i: i.ball(8, 9, 4.5, 0, 1, 2, 3), lambda i: burst(i, 8, 9, 5, 7, 8, 2)),
    'iron_ward': A('physical', lambda i: i.poly([(4, 3), (11, 3), (11, 9), (7.5, 13), (4, 9)], STEEL[1]), lambda i: i.line(7.5, 4, 7.5, 11, STEEL[3]), lambda i: i.line(5, 7, 10, 7, STEEL[3])),
    'shield_wall': A('physical', lambda i: i.poly([(2, 13), (2, 7), (5, 5), (8, 7), (11, 5), (14, 7), (14, 13)], '#7a5a3a'), lambda i: [i.line(x, 8, x, 13, '#4a3424') for x in (4, 8, 12)], ground),
}
SKILLS.update(POE2_BATCH3)

# 2026-10：殘影、元素灌注、指令型召喚物、坐騎
SYSTEMS_BATCH = {
    'phantom_step': A('mana', lambda i: [i.poly([(x, 3), (x + 3, 3), (x + 3, 13), (x, 13)], 1 if x < 8 else 3) for x in (4, 9)],
                      lambda i: speed(i, 2, (5, 8, 11), 1, 3)),
    'elemental_pulse': A('holy', lambda i: i.ring(8, 8, 6, 1, 1.2), lambda i: flame(i, 5, 9, 4, 2),
                         lambda i: snowflake(i, 11, 7, 2.5), lambda i: bolt(i, [(8, 3), (7, 7), (9, 8), (8, 12)], 2, 3)),
    'infusion_bolt': A('holy', lambda i: i.ball(9, 7, 3.5, 0, 1, 2, 3), lambda i: speed(i, 1, (6, 8, 10), 1, 4),
                       lambda i: [i.px(x, y, 3) for x, y in ((12, 3), (13, 11), (6, 12))]),
    'command_skeleton_slam': A('minion', lambda i: skull(i, 4, 2), ground, lambda i: cracks(i, 2)),
    'command_volley': A('minion', lambda i: arrow(i, 2, 13, 12, 3), lambda i: arrow(i, 5, 14, 14, 6), lambda i: skull(i, 1, 1)),
    'command_elements': A('minion', lambda i: skull(i, 4, 5), lambda i: flame(i, 4, 4, 3, 1.5), lambda i: snowflake(i, 8, 3, 2),
                          lambda i: bolt(i, [(12, 1), (11, 4), (13, 5), (12, 8)], 2, 3)),
    'command_beast_pounce': A('war', lambda i: i.poly([(3, 12), (5, 6), (9, 4), (13, 6), (12, 9), (8, 10), (6, 13)], 1),
                              lambda i: [i.px(x, 7, 3) for x in (10, 11)], lambda i: burst(i, 8, 12, 2, 4, 6, 2)),
    'summon_mount': A('war', lambda i: i.poly([(2, 12), (4, 7), (8, 6), (11, 3), (13, 5), (12, 8), (10, 9), (10, 13), (4, 13)], 1),
                      lambda i: i.line(5, 7, 9, 7, 3), lambda i: speed(i, 2, (10, 12), 0, 2)),
    'bone_steed': A('minion', lambda i: i.poly([(2, 12), (4, 7), (8, 6), (11, 3), (13, 5), (12, 8), (10, 9), (10, 13), (4, 13)], 2),
                    lambda i: [i.line(x, 8, x, 12, 0) for x in (5, 7, 9)], lambda i: i.px(11, 5, 3)),
    'hoof_trample': A('physical', lambda i: i.poly([(5, 3), (11, 3), (12, 8), (4, 8)], STEEL[1]), lambda i: i.line(4, 9, 12, 9, STEEL[3]),
                      ground, lambda i: cracks(i, 2)),
}
SKILLS.update(SYSTEMS_BATCH)

# 2026-10：PoE2 主動技能第 4 批
POE2_BATCH4 = {
    'ancestral_cry': A('fire', lambda i: i.poly([(4, 4), (12, 4), (10, 9), (6, 9)], STEEL[1]), lambda i: waves(i, 8, 9, (5, 7), 2, -3.2, 3.2), lambda i: flame(i, 8, 9, 4, 2)),
    'bone_cage': A('physical', lambda i: [i.poly([(x - 1.2, 13), (x, 4 + abs(x - 8) * 0.6), (x + 1.2, 13)], '#e8dcc0') for x in (3, 6, 9, 12)], ground),
    'snap': A('holy', lambda i: burst(i, 8, 8, 3, 6, 8, 2), lambda i: flame(i, 5, 11, 3, 1.5), lambda i: snowflake(i, 11, 5, 2), lambda i: bolt(i, [(4, 2), (3, 5), (5, 5), (4, 8)], 2, 3)),
    'charge_regulation': A('war', sigil, lambda i: [i.disc(x, y, 1.3, 3) for x, y in ((8, 4), (5, 10), (11, 10))]),
    'convalescence': A('mana', lambda i: i.poly([(4, 3), (11, 3), (11, 9), (7.5, 13), (4, 9)], 1), lambda i: (i.line(7.5, 5, 7.5, 11, 3), i.line(4.5, 8, 10.5, 8, 3))),
    'grim_feast': A('minion', lambda i: skull(i, 4, 3, BONE), lambda i: [i.disc(x, 13, 1.2, '#c8282a') for x in (4, 8, 12)]),
    'sacrifice': A('blood', lambda i: skull(i, 4, 2, BONE), lambda i: i.line(3, 13, 13, 13, 2), lambda i: flame(i, 8, 13, 3, 1.5)),
    'shard_scavenger': A('war', lambda i: crossbow_bolt(i, 2, 12, 12, 3), lambda i: [i.poly([(x, y - 1.5), (x + 1.2, y), (x, y + 1.5), (x - 1.2, y)], '#c77dff') for x, y in ((4, 4), (12, 11), (8, 13))]),
    'siphon_elements': A('holy', sigil, lambda i: flame(i, 4, 6, 3, 1.5), lambda i: snowflake(i, 12, 5, 2), lambda i: bolt(i, [(8, 9), (7, 11), (9, 12), (8, 14)], 2, 3)),
    'elemental_trinity': A('holy', lambda i: i.ring(8, 8, 6, 1, 1.0), lambda i: i.disc(8, 3.5, 1.8, '#ff7a2a'), lambda i: i.disc(4, 11, 1.8, '#5ec4ff'), lambda i: i.disc(12, 11, 1.8, '#ffe04a')),
    'frozen_locus': A('cold', lambda i: i.line(2, 13, 13, 2, WOOD[2]), lambda i: i.poly([(10, 14), (12, 8), (14, 14)], '#b8eeff'), lambda i: speed(i, 1, (4, 6), 1, 3)),
    'rake': A('blood', lambda i: spear(i, 2, 13, 13, 2), lambda i: [i.line(6 + k * 2, 13, 9 + k * 2, 9, 2) for k in range(3)], lambda i: speed(i, 2, (3, 5), 2, 3)),
    'lightning_rod': A('lightning', lambda i: arrow(i, 8, 1, 8, 12), lambda i: bolt(i, [(4, 8), (3, 11), (5, 11), (4, 14)], 2, 3), lambda i: bolt(i, [(12, 8), (11, 11), (13, 11), (12, 14)], 2, 3)),
    'fragmentation_rounds': A('physical', lambda i: [crossbow_bolt(i, 2, 4 + k * 4, 11, 2 + k * 4) for k in range(3)], lambda i: [i.px(13, y, 3) for y in (2, 6, 10)]),
    'stormblast_bolts': A('lightning', lambda i: crossbow_bolt(i, 2, 4, 10, 10), lambda i: i.ring(11, 11, 3, 2, 1.0), lambda i: bolt(i, [(11, 8), (10, 11), (12, 11), (11, 14)], 2, 3)),
    'siege_ballista': A('fire', lambda i: i.poly([(4, 13), (6, 8), (10, 8), (12, 13)], WOOD[1]), lambda i: crossbow_bolt(i, 6, 8, 12, 2), lambda i: flame(i, 12, 4, 3, 1.5)),
    'artillery_ballista': A('physical', lambda i: i.poly([(4, 13), (6, 8), (10, 8), (12, 13)], WOOD[1]), lambda i: [i.line(x, 1, x - 1, 6, STEEL[3]) for x in (8, 11, 14)]),
}
SKILLS.update(POE2_BATCH4)


# 2026-10：連珠（下一次弓／矛的投射物攻擊重複）：一支箭，後面跟著兩個箭頭的殘影（同一條線上一支接一支）
def linked_volley():
    i = S('war')
    arrow(i, 2, 13, 12, 3)
    for t, c in ((0.36, 2), (0.66, 1)):
        x, y = 12 - 10 * t, 3 + 10 * t
        i.line(x, y, x - 2.2, y - 0.2, c)
        i.line(x, y, x + 0.2, y + 2.2, c)
    return i


SKILLS['linked_volley'] = linked_volley

LETTERS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@$%^&*()_+-=[]{};:,<>/?~|'


def encode(name, grid):
    palette = {}
    rows = []
    for row in grid:
        line = ''
        for c in row:
            if c is None:
                line += '.'
                continue
            key = c[:3]
            if key not in palette:
                if len(palette) >= len(LETTERS):
                    # 顏色太多：找最接近的
                    best = min(palette, key=lambda k: sum((k[i] - key[i]) ** 2 for i in range(3)))
                    line += palette[best]
                    continue
                palette[key] = LETTERS[len(palette)]
            line += palette[key]
        rows.append(line)
    out = ['item ' + name]
    for key, letter in palette.items():
        out.append('  %s=%02x%02x%02x' % (letter, key[0], key[1], key[2]))
    out += ['  ' + r for r in rows]
    return '\n'.join(out)

# ======================================================================
# 2026-10：輔助寶石的圖示（support_輔助代號）。第二版：照效果（詞綴）分成幾類，同一類同一個圖示，
# 一看就知道是攻擊、法術、投射物、範圍……的輔助（SUPPORT_CATEGORIES）。
# ======================================================================

# 類別 → (名稱, 底色, 圖形)
SUPPORT_CATEGORIES = {
    'attack':     ('攻擊', 'blood', 'sword'),
    'spell':      ('法術', 'mana', 'sparkle'),
    'projectile': ('投射物', 'wind', 'arrows'),
    'area':       ('範圍', 'war', 'rings'),
    'minion':     ('召喚物與圖騰', 'minion', 'skull'),
    'aura':       ('光環與戰吼', 'holy', 'halo'),
    'trigger':    ('觸發', 'lightning', 'bolt'),
    'ailment':    ('異常與減益', 'chaos', 'drop'),
    'crit':       ('暴擊', 'fire', 'star'),
    'sustain':    ('續航', 'life', 'heart'),
    'speed':      ('速度與時間', 'cold', 'hourglass'),
    'damage':     ('通用強化', 'physical', 'plus'),
}


def support_category(sup):
    mods = sup.get('modifiers', []) or []
    stats = ' '.join(str(m.get('stat', '')) for m in mods)
    tags = ' '.join(' '.join(m.get('tags', []) or []) for m in mods)
    req = (sup.get('requires', []) or [])

    def has(*keys):
        return any(k in stats for k in keys)
    if 'minion' in req or 'totem' in req or 'minion' in tags or has('skill.minions', 'skill.totems', 'flag.spell_totem', 'flag.trap', 'totem.'):
        return 'minion'
    if any(r in req for r in ('aura', 'warcry', 'herald')) or has('aura.', 'support.spirit', 'warcry.'):
        return 'aura'
    if has('proc.', 'trigger.', 'grant.'):
        return 'trigger'
    if has('ignite', 'bleed', 'poison', 'shock', 'chill', 'freeze', 'electrocute', 'ailment', 'stun', 'daze',
           'curse.', 'exposure', 'penetration', 'armour.break', 'pin.'):
        return 'ailment'
    if has('skill.projectiles', 'skill.pierce', 'skill.chains', 'skill.fork', 'projectile', 'flag.returning') or 'projectile' in req:
        return 'projectile'
    if has('skill.area', 'melee_splash', 'seal.area'):
        return 'area'
    if has('crit.'):
        return 'crit'
    if has('leech.', 'life.', 'mana.', 'skill.cost', 'flask.', 'charm.', 'flag.blood_magic'):
        return 'sustain'
    if has('attack.speed', 'cast.speed', 'skill.repeats', 'flag.echo', 'skill.duration', 'skill.cooldown', 'added_cooldown',
           'reload', 'fuse', 'combo.duration'):
        return 'speed'
    if 'spell' in req or 'curse' in req or 'mark' in req:
        return 'spell'
    if any(r in req for r in ('attack', 'melee', 'strike', 'crossbow', 'grenade')):
        return 'attack'
    return 'damage'


def draw_category(i, kind):
    W, L, D = '#ffffff', 2, 1
    if kind == 'sword':
        sword(i, 3, 12, 12, 3)
    elif kind == 'sparkle':
        i.line(7, 2, 7, 12, L); i.line(8, 2, 8, 12, L); i.line(2, 7, 12, 7, L); i.line(2, 8, 12, 8, L)
        i.disc(7.5, 7.5, 2.2, 3); i.px(4, 4, L); i.px(11, 4, L); i.px(4, 11, L); i.px(11, 11, L)
    elif kind == 'arrows':
        arrow(i, 2, 10, 9, 3)
        arrow(i, 6, 13, 13, 6)
    elif kind == 'rings':
        i.ring(7.5, 7.5, 5.4, L, 1.0); i.ring(7.5, 7.5, 3.0, 3, 1.0); i.disc(7.5, 7.5, 0.8, W)
    elif kind == 'skull':
        skull(i, 4, 3)
    elif kind == 'halo':
        for a in range(36):
            ang = 2 * math.pi * a / 36
            i.px(7.5 + math.cos(ang) * 4.2, 3.6 + math.sin(ang) * 1.5, 3)
        i.disc(7.5, 8, 2.1, L)
        i.poly([(2.5, 14), (12.5, 14), (10, 10.6), (5, 10.6)], L)
        i.px(6, 7, 3)
    elif kind == 'bolt':
        bolt(i, [(10, 2), (5, 8), (9, 8), (5, 13)], 1, 3)
    elif kind == 'drop':
        i.poly([(7.5, 2), (12, 9), (7.5, 13.5), (3, 9)], L)
        i.disc(7.5, 9.5, 3.6, L); i.px(6, 8, '#ffffff'); i.px(6, 9, '#ffffff'); i.px(7, 7, '#ffffff')
    elif kind == 'star':
        burst(i, 7.5, 7.5, 1.5, 6.2, 8, L)
        i.disc(7.5, 7.5, 2.3, 3)
    elif kind == 'heart':
        i.disc(5.2, 6, 2.6, L); i.disc(9.8, 6, 2.6, L); i.poly([(2.6, 6.5), (12.4, 6.5), (7.5, 12.5)], L)
        i.px(4, 5, 3); i.px(5, 4, 3)
    elif kind == 'hourglass':
        i.line(3, 2, 12, 2, 3); i.line(3, 13, 12, 13, 3)
        i.poly([(4, 3), (11, 3), (7.5, 7.5)], L); i.poly([(7.5, 7.5), (11, 12), (4, 12)], D)
        i.line(7, 8, 7, 11, 3); i.line(8, 8, 8, 11, 3)
    elif kind == 'plus':
        for d in (-1, 0, 1):
            i.line(7.5 + d, 3, 7.5 + d, 12, L); i.line(3, 7.5 + d, 12, 7.5 + d, L)
        i.line(7, 4, 7, 11, 3); i.line(4, 7, 11, 7, 3)


def support_icon(sup):
    name, element, glyph = SUPPORT_CATEGORIES[support_category(sup)]
    i = S(element)
    draw_category(i, glyph)
    return i


def support_icons():
    import yaml
    data = yaml.safe_load((ROOT / 'src/main/resources/data/supports.yml').read_text(encoding='utf-8'))
    sups = data.get('supports', data)
    out = {}
    for sid, sup in sups.items():
        if isinstance(sup, dict) and 'name' in sup:
            out[sid] = sup
    return out


def main():
    blocks = ['# 《星骸》技能圖示（16×16；tools/gen_skill_art.py 產生，不要手改）。格式同 unique_art.txt；代號 skill_技能代號。']
    grids = {}
    for name, fn in SKILLS.items():
        icon = fn()
        icon.outline()
        grid = icon.grid()
        grids[name] = grid
        blocks.append(encode('skill_' + name, grid))
    import collections
    counts = collections.Counter(support_category(sup) for sup in support_icons().values())
    print('support categories', dict(counts))
    for sid, sup in support_icons().items():
        icon = support_icon(sup)
        icon.outline()
        grid = icon.grid()
        grids['support_' + sid] = grid
        blocks.append(encode('support_' + sid, grid))
    OUT.write_text('\n\n'.join(blocks) + '\n', encoding='utf-8')
    print('wrote', OUT, len(SKILLS), 'icons')
    try:
        from PIL import Image
        cols = 12
        scale = 6
        rows_n = (len(grids) + cols - 1) // cols
        img = Image.new('RGBA', (cols * (N * scale + 4), rows_n * (N * scale + 4)), (40, 40, 40, 255))
        for k, (name, grid) in enumerate(grids.items()):
            ox, oy = (k % cols) * (N * scale + 4), (k // cols) * (N * scale + 4)
            for y in range(N):
                for x in range(N):
                    c = grid[y][x]
                    if c:
                        for a in range(scale):
                            for b in range(scale):
                                img.putpixel((ox + x * scale + a, oy + y * scale + b), c)
        img.save(PREVIEW)
        print('preview', PREVIEW)
    except ImportError:
        pass


if __name__ == '__main__':
    main()
