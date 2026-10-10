#!/usr/bin/env python3
"""
《星骸》裝備幻化的美術（2026-10）：每一套主題畫一組 32×32 的物品圖與 64×32 的穿在身上的貼圖，
寫到 src/main/resources/web/skins/（index.txt 列出所有檔案；UiPack 產生介面資源包時讀進去）。

每套：
  <套>_blade、<套>_staff                      主手（近戰武器、法杖／權杖）
  <套>_bow、<套>_bow_pulling_0..2              弓（拉弓三張）
  <套>_crossbow、<套>_crossbow_pulling_0..2、<套>_crossbow_arrow   弩
  <套>_shield、<套>_quiver                     副手（盾、箭袋）
  <套>_helmet、<套>_chestplate、<套>_leggings、<套>_boots      防具在格子裡的圖示
  <套>_humanoid、<套>_humanoid_leggings        穿在身上（原版 64×32 的盔甲貼圖格式）

用法：python3 tools/gen_skin_art.py [--preview preview.png]
"""
import math
import os
import random
import sys

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "web", "skins")

THEMES = {
    # 代號: (名字, 底色暗/中/亮, 金屬暗/中/亮, 光暗/亮, 寶石, 紋章)
    "starlight": ("星耀", ("8a8fa8", "c8cde0", "f4f6ff"), ("8a6a1e", "d4a83a", "ffe08a"), ("5fe0ff", "d8fbff"), "7fd8ff", "star"),
    "void":      ("虛空", ("1a1024", "3a2058", "5c3488"), ("3a3a4a", "6e6e86", "b4b4d0"), ("d24bff", "ffb0ff"), "ff4be0", "eye"),
    "frost":     ("霜晶", ("2a5a8a", "5fa8e0", "cfefff"), ("6a7a8a", "b8c8d8", "ffffff"), ("8af0ff", "eaffff"), "a0f4ff", "snow"),
    "inferno":   ("熾焰", ("3a0e0a", "8a1e10", "d84a1a"), ("6a3a10", "c87a20", "ffc24a"), ("ff7a1a", "ffe08a"), "ffb000", "flame"),
    "verdant":   ("翠影", ("0e2a1e", "1f6a46", "4fc08a"), ("5a3a1e", "a8743a", "e0b070"), ("5affb0", "d8ffe8"), "3affa0", "leaf"),
    "bloodmoon": ("血月", ("1a0a10", "5a0f1e", "a81e34"), ("3a3a44", "8a8a98", "d0d0dc"), ("ff3a4a", "ffb0b0"), "ff2040", "moon"),
    "ironguard": ("鐵衛", ("2a3240", "4a5a74", "7a8ca8"), ("4a4a4a", "8a8a8a", "c8c8c8"), ("6fa8ff", "d0e4ff"), "5f9fff", "cross"),
    # 2026-10 新增的戰甲主題
    "sakura":    ("櫻華", ("6a2a44", "d86a98", "ffd0e4"), ("8a6a5a", "e0c0b0", "fff4ee"), ("ff9ad0", "fff0f8"), "ff6ab0", "leaf"),
    "solar":     ("烈陽", ("6a3a10", "d8902a", "ffe08a"), ("8a5a10", "e0b040", "fff0b0"), ("ffb000", "fff6d0"), "ff8a00", "star"),
    "storm":     ("雷霆", ("1a2440", "3a5a9a", "8ab0ff"), ("3a3f4a", "7a8494", "c8d0dc"), ("8fe8ff", "ffffff"), "6fdcff", "cross"),
    "shadow":    ("暗影", ("101014", "2a2a34", "4a4a5a"), ("24242c", "4a4a58", "8a8a9a"), ("9a6aff", "e0d0ff"), "b47aff", "eye"),
}


def rgb(hexstr, alpha=255):
    return (int(hexstr[0:2], 16), int(hexstr[2:4], 16), int(hexstr[4:6], 16), alpha)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


class Pal:
    def __init__(self, theme):
        name, base, metal, glow, gem, emblem = THEMES[theme]
        self.name = name
        self.b = [rgb(c) for c in base]
        self.m = [rgb(c) for c in metal]
        self.g = [rgb(c) for c in glow]
        self.gem = rgb(gem)
        self.emblem = emblem
        self.out = mix(self.b[0], (0, 0, 0, 255), 0.55)


def outline(img, color):
    """不透明的像素外面描一圈（4 鄰）。"""
    w, h = img.size
    src = img.load()
    add = []
    for y in range(h):
        for x in range(w):
            if src[x, y][3] != 0:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and src[nx, ny][3] != 0 and src[nx, ny] != color:
                    add.append((x, y))
                    break
    for x, y in add:
        src[x, y] = color


def sparkle(img, pal, points):
    px = img.load()
    for x, y in points:
        if 0 <= x < img.size[0] and 0 <= y < img.size[1]:
            px[x, y] = pal.g[1]
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < img.size[0] and 0 <= ny < img.size[1] and px[nx, ny][3] == 0:
                    px[nx, ny] = pal.g[0][:3] + (170,)


def put(px, size, x, y, c):
    if 0 <= x < size and 0 <= y < size:
        px[x, y] = c


def disc(px, size, cx, cy, r, inner, outer, core=None):
    for y in range(size):
        for x in range(size):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d <= r:
                t = d / max(r, 0.01)
                c = mix(inner, outer, t)
                if core is not None and d <= r * 0.35:
                    c = core
                px[x, y] = c


# ---- 主手 ----

def blade(pal):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    sx, sy = 9.5, 22.5          # 護手中心
    d = (1 / math.sqrt(2), -1 / math.sqrt(2))
    p = (1 / math.sqrt(2), 1 / math.sqrt(2))
    length = 27
    for y in range(S):
        for x in range(S):
            vx, vy = x + 0.5 - sx, y + 0.5 - sy
            s = vx * d[0] + vy * d[1]
            q = vx * p[0] + vy * p[1]
            # 劍身：越往尖端越窄
            if 1.0 < s < length:
                width = 2.6 if s < length - 6 else 2.6 * (length - s) / 6
                if abs(q) < width:
                    if abs(q) < 0.75:
                        c = mix(pal.g[0], pal.g[1], min(1, s / length + 0.2))   # 發光的血槽
                    elif q > 0:
                        c = mix(pal.b[2], pal.m[2], 0.3)
                    else:
                        c = pal.b[1]
                    px[x, y] = c
            # 護手（橫的，兩端翹起來）
            if -1.6 < s < 1.2 and abs(q) < 7.0:
                c = pal.m[2] if s > 0 else pal.m[1]
                if abs(q) > 5.5:
                    c = pal.m[0]
                px[x, y] = c
            # 握把
            if -8.5 < s <= -1.6 and abs(q) < 1.3:
                px[x, y] = pal.b[0] if int(s * 1.5) % 2 == 0 else pal.m[0]
    # 寶石：護手中間、柄頭
    disc(px, S, sx + 0.3, sy - 0.3, 1.6, pal.g[1], pal.gem)
    disc(px, S, sx - 6.6, sy + 6.6, 1.9, pal.m[2], pal.m[1], pal.gem)
    outline(img, pal.out)
    sparkle(img, pal, [(27, 4), (22, 7)])
    return img


def staff(pal):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    # 杖身
    for i in range(0, 24):
        x, y = 2 + i, 29 - i
        for o in (0, 1):
            put(px, S, x + o, y, pal.b[1] if o == 0 else pal.b[0])
        if i % 6 == 3:
            put(px, S, x, y - 1, pal.m[2])
            put(px, S, x + 1, y, pal.m[1])
    # 杖頭：月牙形的金屬架，中間一顆發光的球
    cx, cy = 23.5, 8.5
    for y in range(S):
        for x in range(S):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if 5.2 <= d <= 6.8:
                ang = math.atan2(y + 0.5 - cy, x + 0.5 - cx)
                if not (-2.4 < ang < -0.75):          # 右上方開口
                    px[x, y] = pal.m[2] if d < 6.0 else pal.m[1]
    disc(px, S, cx, cy, 3.8, pal.g[1], pal.g[0], (255, 255, 255, 255))
    # 尖刺
    for (x, y) in ((17, 8), (16, 8), (23, 15), (23, 16), (29, 3), (30, 2)):
        put(px, S, x, y, pal.m[1])
    outline(img, pal.out)
    sparkle(img, pal, [(29, 9), (19, 3), (26, 14)])
    return img


# ---- 弓 ----

def bezier(a, c, b, t):
    return ((1 - t) ** 2 * a[0] + 2 * (1 - t) * t * c[0] + t * t * b[0],
            (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * c[1] + t * t * b[1])


def line(px, S, a, b, color, width=0):
    steps = int(max(abs(b[0] - a[0]), abs(b[1] - a[1])) * 2) + 1
    for i in range(steps + 1):
        t = i / steps
        x = a[0] + (b[0] - a[0]) * t
        y = a[1] + (b[1] - a[1]) * t
        for ox in range(-width, width + 1):
            for oy in range(-width, width + 1):
                put(px, S, int(x) + ox, int(y) + oy, color)


def bow(pal, pull):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    a, b = (29.0, 2.0), (2.0, 29.0)
    c = (2.0, 2.0)
    # 弦（先畫，弓身蓋在上面）
    mid = (15.5 + pull * 2.2, 15.5 + pull * 2.2)
    if pull < 0:
        line(px, S, a, b, pal.g[1])
    else:
        line(px, S, a, mid, pal.g[1])
        line(px, S, mid, b, pal.g[1])
        # 箭：從弦往左上穿過握把
        tip = (mid[0] - 15, mid[1] - 15)
        line(px, S, mid, tip, pal.m[1])
        for (dx, dy) in ((0, 0), (1, 0), (0, 1), (-1, 0), (0, -1)):
            put(px, S, int(tip[0]) + dx, int(tip[1]) + dy, pal.g[0])
        put(px, S, int(mid[0]) + 1, int(mid[1]), pal.g[0])
        put(px, S, int(mid[0]), int(mid[1]) + 1, pal.g[0])
    # 弓身：粗、中間是握把，兩端是金屬的弓梢
    for i in range(0, 121):
        t = i / 120
        x, y = bezier(a, c, b, t)
        edge = abs(t - 0.5) * 2
        thick = 2 if edge < 0.8 else 1
        col = pal.m[1] if edge > 0.82 else (pal.b[2] if edge < 0.15 else pal.b[1])
        for o in range(thick):
            put(px, S, int(x) - o, int(y) - o, col)
        if edge < 0.8:
            put(px, S, int(x) + 1, int(y) + 1, pal.b[0])
    # 握把的寶石、弓身上的光紋
    gx, gy = bezier(a, c, b, 0.5)
    disc(px, S, gx + 0.5, gy + 0.5, 1.7, pal.g[1], pal.gem)
    for t in (0.28, 0.72):
        x, y = bezier(a, c, b, t)
        put(px, S, int(x), int(y), pal.g[0])
    outline(img, pal.out)
    sparkle(img, pal, [(29, 2), (2, 29)])
    return img


def crossbow(pal, pull, loaded=False):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    # 弩臂（斜的槍托）
    line(px, S, (2, 29), (20, 11), pal.b[1], 1)
    line(px, S, (3, 29), (20, 12), pal.b[0])
    line(px, S, (8, 21), (13, 19), pal.m[1])         # 扳機
    # 弓臂：跟槍托垂直、往右上彎
    a, b, c = (8.0, 1.0), (30.0, 23.0), (24.0, 6.0)
    back = 0.0 if pull < 0 else (pull + 1) * 2.0
    if loaded:
        back = 7.0
    nock = (15.0 - back * 0.6, 17.0 + back * 0.6) if back else None
    if nock:
        line(px, S, a, nock, pal.g[1])
        line(px, S, nock, b, pal.g[1])
    else:
        line(px, S, a, b, pal.g[1])
    if loaded:
        line(px, S, nock, (25, 7), pal.m[2])
        for (dx, dy) in ((0, 0), (1, 0), (0, -1), (1, -1)):
            put(px, S, 25 + dx, 7 + dy, pal.g[0])
    for i in range(0, 101):
        t = i / 100
        x, y = bezier(a, c, b, t)
        edge = abs(t - 0.5) * 2
        col = pal.m[2] if edge > 0.85 else pal.m[1] if edge > 0.35 else pal.b[2]
        put(px, S, int(x), int(y), col)
        put(px, S, int(x) + 1, int(y) - 1, col)
        put(px, S, int(x) - 1, int(y) + 1, pal.m[0] if edge > 0.35 else pal.b[1])
    # 弩身：弓臂跟槍托接起來的那一塊（方的金屬座）
    for y in range(9, 17):
        for x in range(15, 23):
            if abs((x - 19) + (y - 13)) <= 2 and abs((x - 19) - (y - 13)) <= 5:
                put(px, S, x, y, pal.m[1] if (x + y) % 2 else pal.m[2])
    disc(px, S, 19.5, 12.5, 2.0, pal.g[1], pal.gem)
    outline(img, pal.out)
    sparkle(img, pal, [(8, 1), (30, 23)])
    return img


# ---- 副手 ----

def emblem(px, S, pal, kind, cx, cy):
    c1, c2 = pal.g[1], pal.g[0]
    pts = []
    if kind == "star":
        for k in range(4):
            ang = k * math.pi / 2
            for r in range(0, 5):
                pts.append((cx + math.cos(ang) * r, cy + math.sin(ang) * r))
        for k in range(4):
            ang = k * math.pi / 2 + math.pi / 4
            for r in range(0, 3):
                pts.append((cx + math.cos(ang) * r, cy + math.sin(ang) * r))
    elif kind == "eye":
        for x in range(-5, 6):
            h = int(round(2.6 * math.sqrt(max(0, 1 - (x / 5.5) ** 2))))
            pts.append((cx + x, cy - h))
            pts.append((cx + x, cy + h))
        disc(px, S, cx + 0.5, cy + 0.5, 1.8, pal.gem, pal.g[0], (20, 0, 30, 255))
    elif kind == "snow":
        for k in range(6):
            ang = k * math.pi / 3
            for r in range(0, 5):
                pts.append((cx + math.cos(ang) * r, cy + math.sin(ang) * r))
            pts.append((cx + math.cos(ang) * 3 + math.cos(ang + 1) * 1.2, cy + math.sin(ang) * 3 + math.sin(ang + 1) * 1.2))
    elif kind == "flame":
        for y in range(-5, 5):
            w = (5 - abs(y + 1)) * 0.5 if y > -1 else (y + 5) * 0.45
            for x in range(-int(w) - 1, int(w) + 1):
                pts.append((cx + x + (0.6 * math.sin(y)), cy + y))
    elif kind == "leaf":
        for t in range(-5, 6):
            w = 2.6 * math.sqrt(max(0, 1 - (t / 5.5) ** 2))
            for o in range(-int(w), int(w) + 1):
                pts.append((cx + t * 0.7 + o * 0.7, cy - t * 0.7 + o * 0.7))
    elif kind == "moon":
        for y in range(S):
            for x in range(S):
                d1 = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                d2 = math.hypot(x + 0.5 - cx - 2, y + 0.5 - cy + 1)
                if d1 <= 4.6 and d2 > 3.8:
                    pts.append((x, y))
    else:  # cross
        for r in range(-4, 5):
            pts.append((cx + r, cy))
            pts.append((cx, cy + r))
            pts.append((cx + r, cy + 1 if r else cy))
    for i, (x, y) in enumerate(pts):
        put(px, S, int(round(x)), int(round(y)), c1 if i % 3 else c2)


def shield(pal):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    for y in range(2, 31):
        for x in range(4, 28):
            # 鳶形盾：上面平、下面收成尖
            half = 12 if y < 17 else 12 * (1 - ((y - 17) / 14) ** 1.6)
            dx = abs(x + 0.5 - 16)
            if dx > half:
                continue
            border = half - dx < 2.2 or y < 4
            if border:
                px[x, y] = pal.m[2] if (x + y) % 5 else pal.m[1]
            else:
                t = (y - 2) / 28
                px[x, y] = mix(pal.b[2], pal.b[0], t * 0.9)
    # 中間的直條、紋章
    for y in range(4, 26):
        put(px, S, 15, y, mix(pal.b[1], pal.b[0], 0.4))
    emblem(px, S, pal, pal.emblem, 16, 13)
    for (x, y) in ((6, 4), (25, 4), (16, 27)):
        disc(px, S, x + 0.5, y + 0.5, 1.4, pal.gem, pal.m[2])
    outline(img, pal.out)
    sparkle(img, pal, [(26, 2), (5, 9)])
    return img


def quiver(pal):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    # 箭筒（斜的筒）
    for i in range(0, 20):
        cx, cy = 7 + i * 0.8, 27 - i * 0.8
        for o in range(-3, 4):
            x, y = int(cx + o * 0.7), int(cy + o * 0.7)
            c = pal.b[2] if o < -1 else pal.b[1] if o < 2 else pal.b[0]
            if i in (2, 3, 15, 16):
                c = pal.m[2] if o < 0 else pal.m[1]
            put(px, S, x, y, c)
            put(px, S, x + 1, y, c)
    # 露出來的箭羽
    for k, (x, y) in enumerate(((22, 5), (25, 7), (20, 8), (24, 10))):
        line(px, S, (x - 3, y + 3), (x, y), pal.m[1])
        for (dx, dy) in ((0, 0), (1, -1), (1, 0), (0, -1), (2, -1), (1, -2)):
            put(px, S, x + dx, y + dy, pal.g[k % 2])
    # 背帶、寶石
    line(px, S, (9, 18), (17, 26), pal.m[0])
    disc(px, S, 13.5, 21.5, 1.6, pal.gem, pal.m[2])
    outline(img, pal.out)
    sparkle(img, pal, [(27, 4)])
    return img


# ---- 防具圖示 ----

def helmet(pal):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    for y in range(5, 27):
        for x in range(5, 27):
            d = math.hypot((x + 0.5 - 16) / 10.5, (y + 0.5 - 17) / 11)
            if d > 1 or y > 25:
                continue
            c = mix(pal.b[2], pal.b[0], (y - 5) / 22)
            if abs(y - 16) <= 1 and 9 <= x <= 22:
                c = pal.g[1] if y == 16 else pal.g[0]          # 面罩的發光縫
            elif abs(x + 0.5 - 16) < 1.2 and y < 15:
                c = pal.m[2]                                      # 中間的冠脊
            elif d > 0.88:
                c = pal.m[1]
            px[x, y] = c
    # 頂上的羽冠
    for i in range(7):
        put(px, S, 16 + i, 5 - i // 2, pal.g[0])
        put(px, S, 16 + i, 6 - i // 2, pal.g[1] if i % 2 else pal.g[0])
    disc(px, S, 16.5, 9.5, 1.5, pal.gem, pal.m[2])
    outline(img, pal.out)
    sparkle(img, pal, [(24, 2)])
    return img


def chestplate(pal):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    for y in range(5, 29):
        for x in range(3, 29):
            dx = abs(x + 0.5 - 16)
            body = dx < 8 - max(0, y - 22) * 0.4
            shoulder = y < 13 and dx < 13 - (y - 5) * 0.2 and dx >= 6
            if not (body or shoulder):
                continue
            if shoulder and not body:
                c = pal.m[2] if y < 8 else pal.m[1]
            else:
                c = mix(pal.b[2], pal.b[0], (y - 5) / 24)
                if y < 7 and dx < 3:
                    c = pal.b[0]                                     # 領口
                if abs((y - 14) - dx * 0.8) < 0.7 and y > 12:
                    c = pal.g[0]                                     # V 字光紋
                if y in (23, 24):
                    c = pal.m[1]                                     # 腰帶
            px[x, y] = c
    disc(px, S, 16.5, 15.5, 2.3, pal.g[1], pal.gem)
    outline(img, pal.out)
    sparkle(img, pal, [(4, 6), (27, 6)])
    return img


def leggings(pal):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    for y in range(4, 30):
        for x in range(6, 26):
            dx = x + 0.5 - 16
            if y < 10:
                ok = abs(dx) < 9.5
            else:
                ok = 1.5 < abs(dx) < 9.5 - (y - 10) * 0.12
            if not ok:
                continue
            c = mix(pal.b[2], pal.b[0], (y - 4) / 26)
            if y < 7:
                c = pal.m[1] if y > 4 else pal.m[2]                  # 腰帶
            if 17 <= y <= 19:
                c = pal.m[2] if y == 17 else pal.m[1]                # 護膝
            if abs(abs(dx) - 5.5) < 0.6 and 8 < y < 27 and not 17 <= y <= 19:
                c = pal.g[0]
            px[x, y] = c
    disc(px, S, 16.5, 6.0, 1.6, pal.gem, pal.m[2])
    outline(img, pal.out)
    return img


def boots(pal):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    for side in (-1, 1):
        x0 = 16 + side * 6          # 靴筒的中心
        for y in range(7, 29):
            for dx in range(-4, 8):
                inside = abs(dx) <= 3 if y < 22 else (-3 <= dx <= 6 - max(0, y - 26))
                if not inside:
                    continue
                x = x0 + dx * side                        # 腳尖朝外
                c = mix(pal.b[2], pal.b[0], (y - 7) / 21)
                if y in (7, 8):
                    c = pal.m[2]                          # 靴口
                if y >= 27:
                    c = pal.m[1]                          # 鞋底
                if 21 <= y <= 22 and abs(dx) <= 3:
                    c = pal.m[1]                          # 腳踝的扣環
                if dx == 0 and 10 < y < 20:
                    c = pal.g[0]
                put(px, S, x, y, c)
        disc(px, S, x0 + 0.5, 13.5, 1.3, pal.gem, pal.m[2])
    outline(img, pal.out)
    return img


# ---- 穿在身上（64×32，原版盔甲貼圖的排法）----

def face(px, x0, y0, w, h, pal, top_bright=False, trim=True, glow_row=None, gem=None, rows=None):
    for y in range(h):
        if rows is not None and y not in rows:
            continue
        for x in range(w):
            t = y / max(1, h - 1)
            c = mix(pal.b[2], pal.b[0], t * 0.85)
            if top_bright and y < 2:
                c = pal.m[2]
            if trim and (x == 0 or x == w - 1 or y == 0 or y == h - 1):
                c = pal.m[2] if y == 0 else pal.m[1]
            elif trim and w >= 6 and h >= 6 and (x == 1 or x == w - 2) and 1 < y < h - 2:
                c = mix(c, pal.b[0], 0.35)                    # 內框的陰影，看起來有一塊一塊的甲片
            if glow_row is not None and y == glow_row and 0 < x < w - 1:
                c = pal.g[1] if x % 2 else pal.g[0]
            px[x0 + x, y0 + y] = c
    if gem is not None:
        gx, gy = gem
        px[x0 + gx, y0 + gy] = pal.gem
        px[x0 + gx + 1, y0 + gy] = pal.gem
        px[x0 + gx, y0 + gy + 1] = pal.g[1]
        px[x0 + gx + 1, y0 + gy + 1] = pal.gem


def box(px, u, v, w, h, d, pal, **kw):
    """一個方塊的六面（原版的 UV：上、下在第一排，右前左後在第二排）。"""
    face(px, u + d, v, w, d, pal, trim=True)                      # 上
    face(px, u + d + w, v, w, d, pal, trim=True)                  # 下
    face(px, u, v + d, d, h, pal, **{k: val for k, val in kw.items() if k in ("top_bright", "rows")})   # 右
    face(px, u + d, v + d, w, h, pal, **kw)                       # 前
    face(px, u + d + w, v + d, d, h, pal, **{k: val for k, val in kw.items() if k in ("top_bright", "rows")})  # 左
    face(px, u + d + w + d, v + d, w, h, pal, **{k: val for k, val in kw.items() if k in ("top_bright", "rows")})  # 後


# ---- 時裝（2026-10 改版：身、腿、腳不再是一塊一塊的甲片，改成有布料、滾邊、腰帶、刺繡的衣服）----
# 每一套一種款式：collar 領口、front 前襟、belt 腰帶、motif 刺繡、hem 下襬與袖口、legs 褲子、boot 鞋子
OUTFIT = {
    "starlight": dict(collar="high", front="trim", belt="belt", motif="star", hem="trim", legs="stripe", boot="cuff"),
    "void":      dict(collar="v", front="none", belt="sash", motif="rune", hem="trim", legs="plain", boot="wrap"),
    "frost":     dict(collar="fur", front="buttons", belt="belt", motif="snow", hem="fur", legs="stripe", boot="fur"),
    "inferno":   dict(collar="v", front="trim", belt="sash", motif="flame", hem="flame", legs="plain", boot="cuff"),
    "verdant":   dict(collar="v", front="laces", belt="belt", motif="leaf", hem="trim", legs="wrap", boot="lace"),
    "bloodmoon": dict(collar="high", front="buttons", belt="belt", motif="none", hem="trim", legs="stripe", boot="cuff"),
    "ironguard": dict(collar="high", front="buttons", belt="belt", motif="none", hem="trim", legs="stripe", boot="lace"),
    "sakura":    dict(collar="cross", front="none", belt="obi", motif="petal", hem="trim", legs="pleat", boot="tabi"),
    "solar":     dict(collar="high", front="trim", belt="sash", motif="star", hem="trim", legs="pleat", boot="cuff"),
    "storm":     dict(collar="high", front="trim", belt="belt", motif="bolt", hem="zigzag", legs="stripe", boot="lace"),
    "shadow":    dict(collar="cross", front="none", belt="obi", motif="none", hem="none", legs="wrap", boot="wrap"),
}
FUR = ((236, 232, 222, 255), (206, 198, 186, 255))


def cloth(pal, x, y, h, dark=0.0):
    """布料：上亮下暗的漸層，加一點交錯的織紋。"""
    t = y / max(1, h - 1)
    c = mix(pal.b[2], pal.b[1], min(1, t * 0.9))
    c = mix(c, pal.b[0], dark)
    if (x + y) % 2 == 0:
        c = mix(c, pal.b[0], 0.10)
    return c


def motif_pixels(kind):
    """刺繡的小圖樣（相對座標、用光色的哪一階）。"""
    return {
        "star":  [((1, 0), 1), ((0, 1), 0), ((1, 1), 1), ((2, 1), 0), ((1, 2), 0)],
        "petal": [((0, 0), 0), ((1, 1), 1), ((2, 0), 0), ((1, 2), 0)],
        "flame": [((1, 0), 1), ((0, 1), 0), ((1, 1), 1), ((1, 2), 0)],
        "snow":  [((0, 0), 0), ((2, 0), 0), ((1, 1), 1), ((0, 2), 0), ((2, 2), 0)],
        "leaf":  [((2, 0), 1), ((1, 1), 0), ((2, 1), 0), ((0, 2), 0)],
        "rune":  [((0, 0), 1), ((0, 1), 0), ((0, 2), 1), ((1, 2), 0)],
        "bolt":  [((1, 0), 1), ((0, 1), 1), ((1, 1), 0), ((0, 2), 0)],
    }.get(kind, [])


def stamp(px, x0, y0, kind, pal, w, h, fx, fy):
    for (dx, dy), k in motif_pixels(kind):
        x, y = fx + dx, fy + dy
        if 0 <= x < w and 0 <= y < h:
            px[x0 + x, y0 + y] = pal.g[k]


def coat_face(px, x0, y0, w, h, pal, st, kind):
    """上衣的一面（kind：front／back／side／top／bottom）。"""
    for y in range(h):
        for x in range(w):
            px[x0 + x, y0 + y] = cloth(pal, x, y, h, 0.15 if kind in ("side", "back") else 0)
    if kind in ("top", "bottom"):
        for x in range(w):
            px[x0 + x, y0] = pal.m[1]
        return
    mid = (w - 1) / 2
    # 領口
    if st["collar"] == "fur":
        for y in range(2):
            for x in range(w):
                px[x0 + x, y0 + y] = FUR[(x + y) % 2]
    elif st["collar"] == "high":
        for x in range(w):
            px[x0 + x, y0] = pal.m[1]
            if kind == "front" and abs(x - mid) < 1.5:
                px[x0 + x, y0 + 1] = pal.m[2]
    elif kind == "front" and st["collar"] == "v":
        for y in range(4):
            for x in range(w):
                d = abs(x - mid)
                if d < (3.5 - y) * 0.75:
                    px[x0 + x, y0 + y] = mix(pal.b[0], pal.m[0], 0.5)      # 裡面的襯衣
                elif d < (3.5 - y) * 0.75 + 1:
                    px[x0 + x, y0 + y] = pal.m[2]                          # 領口的滾邊
    elif kind == "front" and st["collar"] == "cross":
        for y in range(min(h, 7)):
            x = int(round(1 + y * 0.85))                                  # 右衽：左襟壓右襟
            if 0 <= x < w:
                px[x0 + x, y0 + y] = pal.m[2]
                if x - 1 >= 0:
                    px[x0 + x - 1, y0 + y] = pal.g[1] if y < 2 else mix(pal.m[2], pal.b[2], 0.5)
    if kind == "back" and st["collar"] in ("v", "cross"):
        for x in range(w):
            px[x0 + x, y0] = pal.m[1]
    # 前襟
    if kind == "front":
        if st["front"] == "trim":
            for y in range(2, h):
                for x in range(w):
                    if abs(x - mid) < 0.6:
                        px[x0 + x, y0 + y] = pal.m[2] if y % 3 else pal.g[0]
        elif st["front"] == "buttons":
            for y in range(3, h - 1, 2):
                px[x0 + int(mid - 1.5), y0 + y] = pal.m[2]
                px[x0 + int(mid + 2), y0 + y] = pal.m[2]
        elif st["front"] == "laces":
            for y in range(2, 6):
                px[x0 + int(mid) + (y % 2), y0 + y] = pal.m[2]
    # 腰帶
    belt_rows = []
    if st["belt"] == "obi":
        belt_rows = [6, 7, 8]
        for y in belt_rows:
            for x in range(w):
                px[x0 + x, y0 + y] = pal.m[2] if y in (6, 8) else pal.m[1]
        if kind == "back" and w >= 6:
            for (dx, dy) in ((2, 6), (3, 7), (4, 7), (5, 6), (2, 8), (5, 8)):
                px[x0 + dx, y0 + dy] = pal.g[0]                            # 背後的結
        if kind == "front":
            px[x0 + int(mid), y0 + 7] = pal.gem
    elif st["belt"] == "belt":
        belt_rows = [7]
        for x in range(w):
            px[x0 + x, y0 + 7] = pal.m[0]
        if kind == "front":
            px[x0 + int(mid), y0 + 7] = pal.m[2]
            px[x0 + int(mid) + 1, y0 + 7] = pal.m[2]
    elif st["belt"] == "sash" and kind in ("front", "back"):
        for y in range(h):
            for x in range(w):
                if abs((x if kind == "front" else w - 1 - x) - (y - 2) * 0.8) < 0.9 and 1 < y < h - 2:
                    px[x0 + x, y0 + y] = pal.m[1] if (x + y) % 3 else pal.m[2]
    # 刺繡
    if st["motif"] != "none":
        if kind == "front" and w >= 6:
            stamp(px, x0, y0, st["motif"], pal, w, h, 0, 8 if 8 not in belt_rows else 9)
            stamp(px, x0, y0, st["motif"], pal, w, h, w - 3, 3)
        elif kind == "back" and w >= 6:
            stamp(px, x0, y0, st["motif"], pal, w, h, int(mid) - 1, 3)
    # 下襬
    if st["hem"] == "fur":
        for y in (h - 2, h - 1):
            for x in range(w):
                px[x0 + x, y0 + y] = FUR[(x + y) % 2]
    elif st["hem"] == "trim":
        for x in range(w):
            px[x0 + x, y0 + h - 1] = pal.m[1]
            if x % 2 == 0:
                px[x0 + x, y0 + h - 2] = mix(pal.b[1], pal.g[0], 0.4)
    elif st["hem"] in ("flame", "zigzag"):
        for x in range(w):
            top = h - 2 - (x % 2 if st["hem"] == "zigzag" else (1 if x % 3 == 1 else 0))
            for y in range(top, h):
                px[x0 + x, y0 + y] = pal.g[0] if y == top else pal.m[1]


def sleeve_face(px, x0, y0, w, h, pal, st, kind):
    for y in range(h):
        for x in range(w):
            px[x0 + x, y0 + y] = cloth(pal, x, y, h, 0.12 if kind in ("side", "back") else 0.04)
    if kind in ("top", "bottom"):
        for x in range(w):
            px[x0 + x, y0] = pal.m[1] if kind == "top" else mix(pal.b[0], pal.m[0], 0.5)
        return
    for x in range(w):
        px[x0 + x, y0] = pal.m[1]                                          # 肩線
    # 袖口
    if st["hem"] == "fur" or st["collar"] == "fur":
        for y in (h - 2, h - 1):
            for x in range(w):
                px[x0 + x, y0 + y] = FUR[(x + y) % 2]
    elif st["collar"] == "cross":                                          # 寬袖：袖口一圈淺色
        for x in range(w):
            px[x0 + x, y0 + h - 1] = pal.m[2]
            px[x0 + x, y0 + h - 2] = mix(pal.b[2], pal.m[2], 0.5)
    else:
        for x in range(w):
            px[x0 + x, y0 + h - 2] = pal.m[1]
            px[x0 + x, y0 + h - 1] = pal.m[2] if x % 2 else pal.m[1]
    if kind == "front" and st["motif"] != "none":
        stamp(px, x0, y0, st["motif"], pal, w, h, 0, 4)


def trouser_face(px, x0, y0, w, h, pal, st, kind, rows):
    for y in rows:
        for x in range(w):
            px[x0 + x, y0 + y] = cloth(pal, x, y, h, 0.35 + (0.1 if kind in ("side", "back") else 0))
    if kind in ("top", "bottom"):
        return
    if st["legs"] == "stripe" and kind == "side":
        for y in rows:
            px[x0 + w // 2, y0 + y] = pal.m[2]
    elif st["legs"] == "pleat":
        for y in rows:
            for x in range(0, w, 2):
                px[x0 + x, y0 + y] = mix(cloth(pal, x, y, h, 0.35), pal.b[0], 0.35)
    elif st["legs"] == "wrap":
        for y in rows:
            if y >= 4:
                for x in range(w):
                    if (x + y) % 3 == 0:
                        px[x0 + x, y0 + y] = mix(pal.m[1], pal.b[2], 0.3)
    elif kind == "front" and 5 in rows:
        for x in range(w):
            px[x0 + x, y0 + 5] = mix(cloth(pal, x, 5, h, 0.35), pal.b[0], 0.3)    # 膝蓋的縫線


def boot_face(px, x0, y0, w, h, pal, st, kind, rows):
    leather = mix(pal.m[0], (20, 14, 10, 255), 0.35)
    hi = mix(pal.m[1], leather, 0.4)
    for y in rows:
        for x in range(w):
            c = leather if (x + y) % 2 else mix(leather, hi, 0.4)
            if y == rows[-1]:
                c = mix(leather, (0, 0, 0, 255), 0.4)                       # 鞋底
            px[x0 + x, y0 + y] = c
    if kind in ("top", "bottom"):
        return
    top = rows[0]
    if st["boot"] == "fur":
        for y in (top, top + 1):
            for x in range(w):
                px[x0 + x, y0 + y] = FUR[(x + y) % 2]
    elif st["boot"] == "cuff":
        for x in range(w):
            px[x0 + x, y0 + top] = pal.m[2]
            px[x0 + x, y0 + top + 1] = pal.m[1]
    elif st["boot"] == "lace" and kind == "front":
        for y in rows[1:-1]:
            px[x0 + (y % 2) + 1, y0 + y] = pal.m[2]
    elif st["boot"] == "tabi":
        for y in rows[:-1]:
            for x in range(w):
                px[x0 + x, y0 + y] = mix(FUR[0], pal.b[2], 0.25)
        if kind == "front":
            px[x0 + 1, y0 + rows[-2]] = mix(FUR[1], pal.b[0], 0.3)        # 分趾
        for x in range(w):
            px[x0 + x, y0 + rows[-1]] = mix(pal.m[0], (0, 0, 0, 255), 0.3)
    elif st["boot"] == "wrap":
        for y in rows[:-1]:
            for x in range(w):
                if (x - y) % 3 == 0:
                    px[x0 + x, y0 + y] = mix(pal.b[2], pal.m[2], 0.4)


def unfold(px, u, v, w, h, d, painter):
    """一個方塊照原版 UV 攤開：painter(x0, y0, 寬, 高, kind)。"""
    painter(u + d, v, w, d, "top")
    painter(u + d + w, v, w, d, "bottom")
    painter(u, v + d, d, h, "side")
    painter(u + d, v + d, w, h, "front")
    painter(u + d + w, v + d, d, h, "side")
    painter(u + d + w + d, v + d, w, h, "back")


def humanoid(pal, theme):
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = img.load()
    st = OUTFIT.get(theme, OUTFIT["ironguard"])
    # 頭（3D 頭盔底下的那一層）：照舊
    box(px, 0, 0, 8, 8, 8, pal, glow_row=4, gem=(3, 1))
    unfold(px, 16, 16, 8, 12, 4, lambda x0, y0, w, h, k: coat_face(px, x0, y0, w, h, pal, st, k))
    unfold(px, 40, 16, 4, 12, 4, lambda x0, y0, w, h, k: sleeve_face(px, x0, y0, w, h, pal, st, k))
    # 鞋子：腿的下面五排
    rows = list(range(7, 12))
    unfold(px, 0, 16, 4, 12, 4, lambda x0, y0, w, h, k: boot_face(px, x0, y0, w, h, pal, st, k, rows if k not in ("top", "bottom") else list(range(h))))
    return img


def humanoid_leggings(pal, theme):
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = img.load()
    st = OUTFIT.get(theme, OUTFIT["ironguard"])
    # 腰：身體的下面五排（上衣蓋住大部分，看得到的是褲頭）
    waist = list(range(7, 12))
    unfold(px, 16, 16, 8, 12, 4, lambda x0, y0, w, h, k: trouser_face(px, x0, y0, w, h, pal, st, k, waist if k not in ("top", "bottom") else list(range(h))))
    legs = list(range(0, 10))
    unfold(px, 0, 16, 4, 12, 4, lambda x0, y0, w, h, k: trouser_face(px, x0, y0, w, h, pal, st, k, legs if k not in ("top", "bottom") else list(range(h))))
    return img


# ---- 時裝的圖示（2026-10：衣服、褲子、鞋子的樣子，不是甲片）----

def garment_icon(pal, theme):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    st = OUTFIT.get(theme, OUTFIT["ironguard"])
    for y in range(5, 29):
        for x in range(2, 30):
            dx = abs(x + 0.5 - 16)
            body = dx < 7.5 + max(0, y - 18) * 0.25 and y >= 6
            sleeve = 7 <= y <= 17 and dx < 14 - (17 - y) * 0.15 and dx >= 7 and y >= 6 + (dx - 7) * 0.35
            if not (body or sleeve):
                continue
            c = cloth(pal, x, y - 5, 24, 0.1 if sleeve and not body else 0)
            if sleeve and not body and y >= 15:
                c = FUR[(x + y) % 2] if st["hem"] == "fur" or st["collar"] == "fur" else pal.m[1]
            if body:
                if y == 28 or (st["hem"] == "fur" and y >= 27):
                    c = FUR[(x + y) % 2] if st["hem"] == "fur" else pal.m[1]
                if st["belt"] == "obi" and 17 <= y <= 20:
                    c = pal.m[2] if y in (17, 20) else pal.m[1]
                elif st["belt"] == "belt" and y == 19:
                    c = pal.m[0] if dx > 1 else pal.m[2]
                elif st["belt"] == "sash" and abs((x - 9) - (y - 6) * 0.65) < 1.2:
                    c = pal.m[1]
                if st["front"] == "trim" and dx < 0.8 and y > 8:
                    c = pal.m[2]
                if st["front"] == "buttons" and dx in (2.5,) and y % 3 == 0 and 9 <= y <= 26:
                    c = pal.m[2]
            px[x, y] = c
    # 領口
    for y in range(5, 11):
        for x in range(12, 21):
            dx = abs(x + 0.5 - 16)
            if st["collar"] in ("v", "cross") and dx < (11 - y) * 0.7 and y < 11:
                px[x, y] = mix(pal.b[0], pal.m[0], 0.5) if st["collar"] == "v" else (pal.m[2] if abs(dx - (11 - y) * 0.7) < 1 else px[x, y])
            elif st["collar"] == "fur" and y < 8 and dx < 5:
                px[x, y] = FUR[(x + y) % 2]
            elif st["collar"] == "high" and y < 7 and dx < 4:
                px[x, y] = pal.m[1]
    for (mx, my) in ((10, 23), (19, 11)):
        for (dx, dy), k in motif_pixels(st["motif"]):
            put(px, S, mx + dx, my + dy, pal.g[k])
    outline(img, pal.out)
    return img


def trouser_icon(pal, theme):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    st = OUTFIT.get(theme, OUTFIT["ironguard"])
    for y in range(4, 30):
        for x in range(5, 27):
            dx = x + 0.5 - 16
            wide = 1.8 if st["legs"] == "pleat" else 0
            ok = abs(dx) < 9 + (y - 4) * 0.05 * wide if y < 11 else 1.2 < abs(dx) < 9 - (y - 11) * (0.15 - wide * 0.07)
            if not ok:
                continue
            c = cloth(pal, x, y - 4, 26, 0.35)
            if y < 7:
                c = pal.m[1] if y > 4 else pal.m[2]
            if st["legs"] == "stripe" and abs(abs(dx) - 7.5) < 0.6 and y > 8:
                c = pal.m[2]
            elif st["legs"] == "pleat" and int(abs(dx)) % 3 == 0 and y > 8:
                c = mix(c, pal.b[0], 0.35)
            elif st["legs"] == "wrap" and y > 17 and (x + y) % 3 == 0:
                c = mix(pal.m[1], pal.b[2], 0.3)
            px[x, y] = c
    outline(img, pal.out)
    return img


def shoe_icon(pal, theme):
    S = 32
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = img.load()
    st = OUTFIT.get(theme, OUTFIT["ironguard"])
    leather = mix(pal.m[0], (20, 14, 10, 255), 0.35)
    hi = mix(pal.m[1], leather, 0.4)
    for side in (-1, 1):
        x0 = 16 + side * 6
        for y in range(9, 29):
            for dx in range(-4, 8):
                inside = abs(dx) <= 3 if y < 23 else (-3 <= dx <= 6 - max(0, y - 26))
                if not inside:
                    continue
                x = x0 + dx * side
                c = leather if (x + y) % 2 else mix(leather, hi, 0.4)
                if st["boot"] == "tabi":
                    c = mix(FUR[0], pal.b[2], 0.25)
                if y >= 27:
                    c = mix(leather, (0, 0, 0, 255), 0.4)
                if y in (9, 10):
                    c = FUR[(x + y) % 2] if st["boot"] == "fur" else pal.m[2] if st["boot"] == "cuff" else c
                if st["boot"] == "lace" and dx in (0, 1) and 11 < y < 22 and (y + dx) % 2 == 0:
                    c = pal.m[2]
                if st["boot"] == "wrap" and y < 24 and (dx - y) % 3 == 0:
                    c = mix(pal.b[2], pal.m[2], 0.4)
                put(px, S, x, y, c)
    outline(img, pal.out)
    return img


def build(theme):
    pal = Pal(theme)
    images = {
        f"{theme}_blade": blade(pal),
        f"{theme}_staff": staff(pal),
        f"{theme}_bow": bow(pal, -1),
        f"{theme}_crossbow": crossbow(pal, -1),
        f"{theme}_crossbow_arrow": crossbow(pal, 2, loaded=True),
        f"{theme}_shield": shield(pal),
        f"{theme}_quiver": quiver(pal),
        f"{theme}_helmet": helmet(pal),
        f"{theme}_chestplate": garment_icon(pal, theme),
        f"{theme}_leggings": trouser_icon(pal, theme),
        f"{theme}_boots": shoe_icon(pal, theme),
        f"{theme}_humanoid": humanoid(pal, theme),
        f"{theme}_humanoid_leggings": humanoid_leggings(pal, theme),
    }
    for i in range(3):
        images[f"{theme}_bow_pulling_{i}"] = bow(pal, i)
        images[f"{theme}_crossbow_pulling_{i}"] = crossbow(pal, i)
    return images


def main():
    random.seed(7)
    os.makedirs(OUT, exist_ok=True)
    names = []
    all_images = {}
    for theme in THEMES:
        for name, image in build(theme).items():
            image.save(os.path.join(OUT, name + ".png"))
            names.append(name)
            all_images[name] = image
    with open(os.path.join(OUT, "index.txt"), "w", encoding="utf-8") as f:
        f.write("# 裝備幻化的美術（tools/gen_skin_art.py 產生）：介面資源包產生時讀這裡列的檔案\n")
        for name in names:
            f.write(name + ".png\n")
    if "--preview" in sys.argv:
        path = sys.argv[sys.argv.index("--preview") + 1]
        keys = [n for n in names if not n.endswith("humanoid") and not n.endswith("humanoid_leggings")]
        cols = 17
        scale = 4
        sheet = Image.new("RGBA", (cols * (32 * scale + 4), (len(keys) // cols + 1) * (32 * scale + 4)), (70, 70, 80, 255))
        for i, n in enumerate(keys):
            im = all_images[n].resize((32 * scale, 32 * scale), Image.NEAREST)
            sheet.paste(im, ((i % cols) * (32 * scale + 4), (i // cols) * (32 * scale + 4)), im)
        sheet.save(path)
        layers = [n for n in names if n.endswith("humanoid") or n.endswith("humanoid_leggings")]
        sheet2 = Image.new("RGBA", (2 * (64 * 4 + 4), (len(layers) // 2 + 1) * (32 * 4 + 4)), (70, 70, 80, 255))
        for i, n in enumerate(layers):
            im = all_images[n].resize((64 * 4, 32 * 4), Image.NEAREST)
            sheet2.paste(im, ((i % 2) * (64 * 4 + 4), (i // 2) * (32 * 4 + 4)), im)
        sheet2.save(path.replace(".png", "_layers.png"))
    print(len(names), "images")


if __name__ == "__main__":
    main()
