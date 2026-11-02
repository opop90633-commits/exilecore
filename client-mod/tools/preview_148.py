"""2026-11-02 1.4.8 大地圖預覽圖（沿用 C 組的畫法）：1920×1080、GUI 縮放 3（GUI 640×360）。示意圖，照模組程式的版面與顏色畫。"""
import math
import random
import sys
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
S = 3
W, H = 640, 360
CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
SYM = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SYMS = set("▲☠◆◇✔●⚠⛨◎✦━▮✖⚔▼•")
_cache = {}


def font(px, sym=False):
    key = (px, sym)
    if key not in _cache:
        _cache[key] = ImageFont.truetype(SYM, px) if sym else ImageFont.truetype(CJK, px, index=3)
    return _cache[key]


def rgb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255)


def argb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255, (c >> 24) & 255)


class Canvas:
    def __init__(self, sky=True):
        self.img = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 255))
        d = ImageDraw.Draw(self.img)
        if sky:
            for y in range(H * S):
                t = y / (H * S)
                if t < 0.52:
                    k = t / 0.52
                    col = (int(70 + 60 * k), int(85 + 60 * k), int(120 + 60 * k))
                else:
                    col = (int(72 - 20 * (t - 0.52)), int(64 - 20 * (t - 0.52)), int(56 - 20 * (t - 0.52)))
                d.line([(0, y), (W * S, y)], fill=col)

    def fill(self, x0, y0, x1, y1, color):
        layer = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).rectangle([x0 * S, y0 * S, x1 * S - 1, y1 * S - 1], fill=argb(color))
        self.img = Image.alpha_composite(self.img, layer)

    def outline(self, x, y, w, h, color):
        d = ImageDraw.Draw(self.img)
        d.rectangle([x * S, y * S, (x + w) * S - 1, (y + h) * S - 1], outline=argb(color), width=S)

    def width(self, text, scale=1.0):
        px = int(8 * S * scale * 1.15)
        total = 0
        for ch in text:
            f = font(px, ch in SYMS)
            total += f.getlength(ch)
        return total / S

    def text(self, x, y, text, color=0xFFFFFF, scale=1.0, shadow=True):
        """x, y 是 GUI 座標的左上角；scale 1 = 原版字（8 GUI 像素高）。"""
        px = int(8 * S * scale * 1.15)
        d = ImageDraw.Draw(self.img)
        cx = x * S
        for ch in text:
            f = font(px, ch in SYMS)
            if shadow:
                d.text((cx + S * scale, y * S - px * 0.12 + S * scale), ch, font=f, fill=(int(rgb(color)[0] / 4), int(rgb(color)[1] / 4), int(rgb(color)[2] / 4)))
            d.text((cx, y * S - px * 0.12), ch, font=f, fill=rgb(color))
            cx += f.getlength(ch)
        return (cx / S) - x

    def parts(self, x, y, parts, scale=1.0):
        for t, c in parts:
            x += self.text(x, y, t, c, scale)
        return x

    def centered(self, cx, y, text, color=0xFFFFFF, scale=1.0):
        self.text(cx - self.width(text, scale) / 2, y, text, color, scale)

    def arrow(self, cx, cy, degrees, color, size=5):
        a = math.radians(degrees)
        pts = [(0, -size), (size * 0.75, size * 0.7), (0, size * 0.25), (-size * 0.75, size * 0.7)]
        rot = [((cx + px * math.cos(a) - py * math.sin(a)) * S, (cy + px * math.sin(a) + py * math.cos(a)) * S) for px, py in pts]
        ImageDraw.Draw(self.img).polygon(rot, fill=rgb(color), outline=(0, 0, 0))

    def save(self, name):
        self.img.convert("RGB").save(OUT + "/" + name)


def header(c, title):
    c.fill(0, 0, W, 15, 0xC0202434)
    c.text(5, 3, title, 0xFFFFFF, 1.05)


def hotbar(c, orbs=True):
    x0 = W // 2 - 91
    c.fill(x0, H - 22, x0 + 182, H, 0xC0202020)
    for i in range(9):
        c.outline(x0 + 1 + i * 20, H - 21, 20, 20, 0xFF606060)
    if orbs:
        for cx, col in [(W // 2 - 122 - 19, 0xFFB01818), (W // 2 + 122 + 19, 0xFF2050C0)]:
            layer = Image.new("RGBA", c.img.size, (0, 0, 0, 0))
            ImageDraw.Draw(layer).ellipse([(cx - 18) * S, (H - 38) * S, (cx + 18) * S, (H - 2) * S], fill=argb(col), outline=(40, 30, 20, 255), width=S * 2)
            c.img = Image.alpha_composite(c.img, layer)


def bossbar(c, y=12, name="潮汐統帥", frac=0.62):
    c.centered(W / 2, y - 9, name, 0xFFFFFF, 1)
    c.fill(W // 2 - 91, y, W // 2 + 91, y + 5, 0xFF401010)
    c.fill(W // 2 - 91, y, W // 2 - 91 + int(182 * frac), y + 5, 0xFFE03030)


def target(c, y=23):
    left = W // 2 - 75
    c.fill(left - 2, y - 2, left + 152, y + 30, 0x90000000)
    c.text(left, y, "潮汐統帥", 0xAF6025)
    lv = "Lv.70 傳奇·首領"
    c.text(left + 150 - c.width(lv), y, lv, 0xAAAAAA)
    c.fill(left, y + 10, left + 150, y + 17, 0xFF300808)
    c.fill(left, y + 10, left + 93, y + 17, 0xFFD02828)
    c.outline(left, y + 10, 150, 7, 0xFF000000)
    c.centered(W / 2, y + 11, "1,240,000 / 2,000,000", 0xFFFFFF, 0.6)
    c.text(left, y + 20, "燃 2.5s  咒·萎力", 0xFF7B3D, 0.75)


PRIO = [0xFFA0A0A0, 0xFFFFC040, 0xFFFF8030, 0xFFFF3030]


def card(c, x, y, w, prio, title, sub, countdown=None, arrow=None, dist=None):
    h = 26
    c.fill(x, y, x + w, y + h, 0xB0100808)
    c.fill(x, y, x + 2, y + h, PRIO[prio])
    c.outline(x, y, w, h, PRIO[prio] if prio >= 3 else 0xFF3A2A20)
    left = x + 5
    right = x + w - 4
    if arrow is not None:
        deg, safe = arrow
        c.arrow(left + 5, y + 10, deg, 0x45FF6A if safe else 0xFF5555, 5)
        c.centered(left + 5, y + 17.5, f"{dist} 格", 0xFFFFFF, 0.5)
        left += 15
    if countdown is not None:
        cw = c.width(countdown, 1.4)
        c.text(right - cw, y + 5, countdown, 0xFF5555 if float(countdown) <= 2 else 0xFFFFFF, 1.4)
        right -= 26
    c.parts(left, y + 4, title)
    if sub:
        c.parts(left, y + 15, sub, 0.75)


def minimap_cells(seed=7):
    random.seed(seed)
    N = 112
    grid = [[0] * N for _ in range(N)]
    rooms = []
    # 6×6 格位（每格 16 個小地圖格），走一條主路
    path = [(0, 2)]
    while len(path) < 9:
        x, z = path[-1]
        options = [(x + 1, z), (x, z + 1), (x, z - 1)]
        options = [(a, b) for a, b in options if 0 <= a < 6 and 0 <= b < 6 and (a, b) not in path]
        if not options:
            break
        path.append(random.choice(options))
    side = []
    for (x, z) in path[2:6:2]:
        for (a, b) in [(x, z + 1), (x, z - 1)]:
            if 0 <= b < 6 and (a, b) not in path:
                side.append((a, b))
                break
    def room(gx, gz, kind):
        cx, cz = 8 + gx * 16 + 8, 8 + gz * 16 + 8
        r = 10 if kind == "BOSS" else random.randint(4, 6)
        for z in range(cz - r, cz + r + 1):
            for x in range(cx - r, cx + r + 1):
                inside = (x - cx) ** 2 + (z - cz) ** 2 <= r * r if kind == "BOSS" else True
                if 0 <= x < N and 0 <= z < N and inside:
                    grid[z][x] = 1
        rooms.append((cx, cz, kind))
        return cx, cz
    centers = []
    for i, (gx, gz) in enumerate(path):
        centers.append(room(gx, gz, "START" if i == 0 else "BOSS" if i == len(path) - 1 else "NORMAL"))
    def corridor(a, b):
        (x0, z0), (x1, z1) = a, b
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for dz in (-1, 0, 1):
                grid[z0 + dz][x] = 1
        for z in range(min(z0, z1), max(z0, z1) + 1):
            for dx in (-1, 0, 1):
                grid[z][x1 + dx] = 1
    for i in range(len(centers) - 1):
        corridor(centers[i], centers[i + 1])
    for (gx, gz) in side:
        sc = room(gx, gz, "SIDE")
        best = min(centers, key=lambda p: abs(p[0] - sc[0]) + abs(p[1] - sc[1]))
        corridor(best, sc)
    # 牆：地板外面一格
    for z in range(N):
        for x in range(N):
            if grid[z][x] == 0 and any(0 <= x + dx < N and 0 <= z + dz < N and grid[z + dz][x + dx] == 1 for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
                grid[z][x] = 2
    # 一個水池
    lx, lz = centers[3]
    for z in range(lz - 2, lz + 3):
        for x in range(lx + 1, lx + 4):
            grid[z][x] = 3
    return grid, rooms, centers


def fog(grid, centers, upto):
    N = len(grid)
    seen = [[False] * N for _ in range(N)]
    pts = []
    for i in range(upto):
        (x0, z0), (x1, z1) = centers[i], centers[i + 1]
        steps = abs(x1 - x0) + abs(z1 - z0)
        for s in range(0, steps + 1, 1):
            if s <= abs(x1 - x0):
                pts.append((x0 + (s if x1 >= x0 else -s), z0))
            else:
                t = s - abs(x1 - x0)
                pts.append((x1, z0 + (t if z1 >= z0 else -t)))
    for (px, pz) in pts:
        for z in range(pz - 7, pz + 8):
            for x in range(px - 7, px + 8):
                if 0 <= x < N and 0 <= z < N and (x - px) ** 2 + (z - pz) ** 2 <= 49:
                    seen[z][x] = True
    return seen, pts[-1]


TILE = {1: 0xD0383C48, 2: 0xF0A8A090, 3: 0xD02A5A9A}


def draw_map(c, grid, seen, x0, y0, per, cx_cell=None, cz_cell=None, size=None):
    N = len(grid)
    layer = Image.new("RGBA", c.img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for z in range(N):
        for x in range(N):
            if not seen[z][x] or grid[z][x] == 0:
                continue
            gx = x0 + x * per
            gz = y0 + z * per
            if size is not None:
                if not (cx_cell - size / 2 <= gx < cx_cell + size / 2 - per and cz_cell - size / 2 <= gz < cz_cell + size / 2 - per):
                    continue
            d.rectangle([gx * S, gz * S, (gx + per) * S, (gz + per) * S], fill=argb(TILE[grid[z][x]]))
    c.img = Image.alpha_composite(c.img, layer)


def glyph(c, x, y, ch, color, scale):
    c.text(x - c.width(ch, scale) / 2, y - 4 * scale, ch, color, scale)


def minimap(c, grid, seen, rooms, events, player, yaw, area_x, area_y, size=72):
    c.fill(area_x - 1, area_y - 1, area_x + size + 1, area_y + size + 1, 0xFF5A5040)
    c.fill(area_x, area_y, area_x + size, area_y + size, 0xC0080808)
    per = size / 48
    cx = area_x + size / 2
    cy = area_y + size / 2
    ox = cx - player[0] * per
    oz = cy - player[1] * per
    draw_map(c, grid, seen, ox, oz, per, cx, cy, size)
    for (ex, ez, name, col, st) in events:
        px, pz = ox + ex * per, oz + ez * per
        if area_x + 2 < px < area_x + size - 2 and area_y + 2 < pz < area_y + size - 2 and seen[int(ez)][int(ex)]:
            glyph(c, px, pz, "✔" if st == 2 else "◆", 0x606060 if st == 2 else col, 0.7)
    c.arrow(cx, cy, yaw + 180, 0xFFE070, 3.2)
    c.text(area_x, area_y + size + 1.5, "探索 41%　[M] 大地圖", 0xA0A0A0, 0.5)


def mappanel(c, y):
    x0 = W - 4 - 150
    rows = [([("沉船灣 T7", 0x9FD3FF)], "T7 Lv.70"), ([("剩 5 次", 0xFFB347)], "探索 41%"), ([("清除 22%", 0xFFFFFF)], ""),
            ([("Boss 潮汐統帥", 0xFFFFFF)], "戰鬥中 62%")]
    h = 4 + len(rows) * 10 + 4
    c.fill(x0, y, x0 + 150, y + h, 0xA0000000)
    c.outline(x0, y, 150, h, 0xFF3A3A48)
    yy = y + 3
    for left, right in rows:
        c.parts(x0 + 3, yy, left, 0.7)
        if right:
            c.text(x0 + 147 - c.width(right, 0.7), yy, right, 0xC0C0C0 if "戰鬥" not in right else 0xFF5555, 0.7)
        yy += 10
    c.text(x0 + 130, y + h + 1, "F6 收起", 0xA0A0A0, 0.4)


def side_list(c, x, y, title, lines, left, total):
    width = 132
    height = 13 + len(lines) * 8 + 10
    c.fill(x, y, x + width, y + height + 3, 0xA0080810)
    c.outline(x, y, width, height + 3, 0xFF5A4630)
    c.parts(x + 4, y + 3, title, 0.85)
    dy = y + 14
    for line in lines:
        c.parts(x + 4, dy, [("• ", 0xFFD27F)] + line, 0.7)
        dy += 8
    frac = left / total
    c.fill(x + 4, dy + 2, x + width - 4, dy + 5, 0xFF301010)
    c.fill(x + 4, dy + 2, x + 4 + int((width - 8) * frac), dy + 5, 0xFFFFC040)
    t = f"剩 {left:.1f} 秒"
    c.text(x + width - 4 - c.width(t, 0.6), dy - 3, t, 0xFFFFFF, 0.6)


EVENTS = None


def scene_alerts():
    grid, rooms, centers = minimap_cells()
    seen, player = fog(grid, centers, 7)
    events = [(centers[2][0] + 3, centers[2][1], "神龕", 0x9FE8C8, 2), (centers[4][0], centers[4][1] + 2, "裂隙", 0xC48BFF, 0)]
    c = Canvas()
    header(c, "示意圖：星骸輔助模組 1.4（C 組）　Boss 預警（上方一排）＋機制清單（左上）　1920×1080　GUI 縮放 3")
    bossbar(c, 24)
    target(c, 35)
    # 預警那一排（目標框下面）
    y = 70
    cards = [
        (2, [("⚠ 潮汐漩渦", 0xFF5555)], [("離開紅色區域", 0xFF5555)], None, (160, False), 6, 130),
        (3, [("⚠ 滅世巨浪", 0xFF5555)], [("躲進綠色安全區！", 0x45FF6A)], "3.4", (-35, True), 9, 160),
        (1, [("⚠ 場地危害：落雷", 0xFFB347)], [("紅圈會一直落在玩家附近（大約每 6 秒）", 0xFFFFFF)], None, None, None, 170),
    ]
    # 範圍 324 寬（兩邊讓開右上角 150 寬的框）：第一排「最要緊的在中間、第二則在右」，放不下的換到第二排
    row1 = [cards[1], cards[0]]
    total = sum(cd[6] for cd in row1) + 4
    x = W / 2 - total / 2
    for p, t, s_, cd, ar, dist, w in row1:
        card(c, x, y, w, p, t, s_, cd, ar, dist)
        x += w + 4
    p, t, s_, cd, ar, dist, w = cards[2]
    card(c, W / 2 - 170 / 2, y + 28, 170, p, t, s_, cd, ar, dist)
    side_list(c, 4, 18, [("第 2 階段・怒濤", 0xFF5555)],
              [[("⚠ 倒數大招：12 秒後「滅世巨浪」", 0xFFFFFF)], [("躲進綠色安全區", 0xFFFFFF)], [("新招式：潮汐漩渦、水牢", 0xFFFFFF)],
               [("Boss 動作變快", 0xFFFFFF)]], 3.4, 12)
    minimap(c, grid, seen, rooms, events, player, 40, W - 4 - 72, 4)
    mappanel(c, 4 + 72 + 7 + 3)
    c.text(W / 2 - 4, H / 2 - 5, "+", 0xFFFFFF, 1.4, False)
    hotbar(c)
    c.text(14, 250, "同時好幾則：優先度高的在中間（紅框閃）；有座標的畫箭頭（紅＝往外逃的方向、綠＝往安全區走）與距離", 0xE0E0E0, 0.85)
    c.text(14, 262, "同一則每 5 tick 重送只會延長，不會疊；裝了模組的人伺服器不送大標題，沒裝的人照舊", 0xE0E0E0, 0.85)
    c.save("preview-14c-alerts.png")


def tooltip_box(c, x, y, lines, title):
    w = max(c.width("".join(t for t, _ in line), 1) for line in lines) + 8
    h = len(lines) * 10 + 6
    c.fill(x, y, x + w, y + h, 0xF0100010)
    c.outline(x, y, w, h, 0xFF2A0868)
    c.outline(x + 1, y + 1, w - 2, h - 2, 0xFF25015B)
    yy = y + 4
    for line in lines:
        c.parts(x + 4, yy, line)
        yy += 10
    c.text(x, y - 11, title, 0xF5B942, 0.85)
    return w


def scene_tooltip():
    c = Canvas(sky=False)
    c.fill(0, 0, W, H, 0xFF2A2A30)
    header(c, "示意圖：星骸輔助模組 1.4（C 組）　裝備說明的三種樣子　1920×1080　GUI 縮放 3")
    name = [("暴風 冠冕", 0xFFFF77)]
    compact = [name, [("鐵盔 ", 0xFFFF77), ("稀有", 0x555555)], [("+12 最大生命 ", 0xA0A0FF), ("(固定)", 0x555555)],
               [("◆ ", 0x7F88D8), ("+25 最大生命", 0xB4BCFF), (" [生命]", 0x707070)], [("◆ ", 0x7F88D8), ("+38% 護甲增加", 0xB4BCFF), (" [防禦]", 0x707070)],
               [("◇ ", 0xD0A06A), ("+30% 火焰抗性", 0xFFD9A0), (" [火焰]", 0x707070)], [("◇ ", 0xD0A06A), ("+18% 冰冷抗性", 0xFFD9A0), (" [冰冷]", 0x707070)], [("", 0)],
               [("比身上的 ", 0x555555), ("舊鐵盔", 0xAAAAAA), ("：", 0x555555)], [("  生命 +12 ", 0x55FF55), ("火抗 +30 ", 0x55FF55), ("護甲 −8", 0xFF5555)],
               [("按住 Shift：完整說明　Alt：詞綴階級", 0x555555)]]
    full = [name, [("鐵盔 ", 0xFFFF77), ("稀有", 0x555555)], [("品質：", 0xAAAAAA), ("+12%", 0x8888FF)], [("護甲：", 0xAAAAAA), ("186", 0x8888FF)],
            [("━━━━━━━━━━━━━━", 0x555555)], [("需要 等級 52・力量 64", 0xAAAAAA)], [("━━━━━━━━━━━━━━", 0x555555)],
            [("+12", 0xA0A0FF), ("(10–15)", 0x8C8C8C), (" 最大生命 ", 0xA0A0FF), ("(固定)", 0x555555)], [("━━━━━━━━━━━━━━", 0x555555)],
            [("前綴 2/3・後綴 2/3", 0xAAAAAA)],
            [("◆ ", 0x7F88D8), ("+25", 0xB4BCFF), ("(20–30)", 0x8C8C8C), (" 最大生命 ", 0xB4BCFF), ("(T3)", 0x555555), (" [生命]", 0x707070)],
            [("◆ ", 0x7F88D8), ("+38", 0xB4BCFF), ("(27–42)", 0x8C8C8C), ("% 護甲增加 ", 0xB4BCFF), ("(T2)", 0x555555), (" [防禦]", 0x707070)],
            [("◇ ", 0xD0A06A), ("+30", 0xFFD9A0), ("(26–30)", 0x8C8C8C), ("% 火焰抗性 ", 0xFFD9A0), ("(T1)", 0x555555), (" [火焰]", 0x707070)],
            [("◇ ", 0xD0A06A), ("+18", 0xFFD9A0), ("(16–20)", 0x8C8C8C), ("% 冰冷抗性 ", 0xFFD9A0), ("(T4)", 0x555555), (" [冰冷]", 0x707070)],
            [("━━━━━━━━━━━━━━", 0x555555)], [("物品等級 72", 0x555555)], [("", 0)],
            [("比身上的 ", 0x555555), ("舊鐵盔", 0xAAAAAA), ("：", 0x555555)], [("  生命 +12 ", 0x55FF55), ("火抗 +30 ", 0x55FF55), ("護甲 −8", 0xFF5555)]]
    def bar(f):
        n = round(f * 5)
        col = 0x55FF55 if f >= 0.8 else 0xFFD24A if f >= 0.4 else 0xFF7070
        return [("▮" * n, col), ("▮" * (5 - n), 0x404040), (f" {round(f * 100)}%", 0xAAAAAA)]
    tiers = [name, [("鐵盔 ", 0xFFFF77), ("稀有", 0x555555)], [("詞綴階級與數值範圍・物品等級 72", 0xAAAAAA)],
             [("固定　", 0x555555), ("+12 最大生命", 0xA0A0FF)], [("　12 ", 0xFFFFFF), ("（10–15） ", 0x8C8C8C)] + bar(0.4),
             [("T3 ", 0xE0E0FF), ("前綴　", 0x555555), ("+25 最大生命", 0xB4BCFF)], [("　25 ", 0xFFFFFF), ("（20–30） ", 0x8C8C8C)] + bar(0.5),
             [("T2 ", 0xE0E0FF), ("前綴　", 0x555555), ("+38% 護甲增加", 0xB4BCFF)], [("　38 ", 0xFFFFFF), ("（27–42） ", 0x8C8C8C)] + bar(0.73),
             [("T1 ", 0xFFD27F), ("後綴　", 0x555555), ("+30% 火焰抗性", 0xFFD9A0)], [("　30 ", 0xFFFFFF), ("（26–30） ", 0x8C8C8C)] + bar(1.0),
             [("T4 ", 0xA0A0A0), ("後綴　", 0x555555), ("+18% 冰冷抗性", 0xFFD9A0)], [("　18 ", 0xFFFFFF), ("（16–20） ", 0x8C8C8C)] + bar(0.5),
             [("按住 Shift：完整說明　Alt：詞綴階級", 0x555555)]]
    x = 8
    x += tooltip_box(c, x, 34, compact, "平常（精簡）") + 10
    x += tooltip_box(c, x, 34, full, "按住 Shift（完整＝伺服器原本的說明）") + 10
    tooltip_box(c, x, 34, tiers, "按住 Alt（詞綴階級、範圍、擲到幾成）")
    c.text(8, 300, "說明欄的資料：物品 custom_data 裡的 PublicBukkitValues → exilecore:data（詞綴的階級、數值、範圍）；伺服器的 lore 不改", 0xE0E0E0, 0.8)
    c.text(8, 311, "沒裝模組的人照舊看完整版；寶石平常只顯示第一段＋需要的等級，Shift 完整", 0xE0E0E0, 0.8)
    c.save("preview-14c-tooltip.png")


def scene_minimap():
    grid, rooms, centers = minimap_cells()
    seen, player = fog(grid, centers, 6)
    events = [(centers[2][0] + 3, centers[2][1], "神龕", 0x9FE8C8, 2), (centers[4][0], centers[4][1] + 2, "裂隙", 0xC48BFF, 1),
              (centers[7][0] - 2, centers[7][1], "保險箱", 0xC8D0D8, 0)]
    c = Canvas()
    header(c, "示意圖：星骸輔助模組 1.4（C 組）　大地圖（按 M）　左上：平常的小地圖（右上角）　1920×1080　GUI 縮放 3")
    # 大地圖
    size = min(W - 40, H - 80)
    per = size / len(grid)
    x0 = W / 2 - size / 2
    y0 = 16 + 14
    c.fill(x0 - 4, y0 - 14, x0 + size + 4, y0 + size + 12, 0xD0000000)
    c.outline(x0 - 4, y0 - 14, size + 8, size + 26, 0xFF5A5040)
    c.text(x0, y0 - 11, "地圖", 0xF5B942)
    c.text(x0 + size - c.width("探索 38%"), y0 - 11, "探索 38%", 0xC0C0C0)
    draw_map(c, grid, seen, x0, y0, per)
    for (ex, ez, name, col, st) in events:
        px, pz = x0 + ex * per, y0 + ez * per
        glyph(c, px, pz, "✔" if st == 2 else "◆", 0x606060 if st == 2 else col, 0.9)
        c.centered(px, pz + 4, name + ("（完成）" if st == 2 else "（進行中）" if st == 1 else ""), 0x808080 if st == 2 else col, 0.5)
    bx, bz = centers[-1]
    glyph(c, x0 + bx * per, y0 + bz * per, "☠", 0xFF7070, 1.1)
    c.centered(x0 + bx * per, y0 + bz * per + 5, "Boss", 0xFF7070, 0.5)
    mx, mz = player[0] - 6, player[1] + 3
    glyph(c, x0 + mx * per, y0 + mz * per, "●", 0x55FF55, 0.7)
    c.centered(x0 + mx * per, y0 + mz * per + 4, "阿明", 0x88FF88, 0.5)
    c.arrow(x0 + player[0] * per, y0 + player[1] * per, 90 + 180 - 90, 0xFFE070, 4)
    c.text(x0, y0 + size + 3, "◆ 事件　☠ Boss　● 隊友　▲ 你　[M] 收起　走過的地方才亮（迷霧）", 0x909090, 0.6)
    # 左上：平常的小地圖
    c.text(8, 20, "平常（右上角最上面，地圖資訊框接在下面）：", 0xE0E0E0, 0.75)
    minimap(c, grid, seen, rooms, events, player, 90, 20, 32)
    hotbar(c)
    c.save("preview-14c-minimap.png")


def scene_death():
    c = Canvas()
    c.fill(0, 0, W, H, 0x80600000)
    header(c, "示意圖：星骸輔助模組 1.4（C 組）　死亡回顧面板（死亡畫面右邊；重生後按 J 再看）　1920×1080　GUI 縮放 3")
    c.centered(W / 2, 60, "你死了！", 0xFFFFFF, 2)
    c.centered(W / 2, 90, "被 潮汐統帥 擊殺", 0xFFFFFF)
    c.centered(W / 2, 104, "分數：0", 0xFFFF55)
    for i, label in enumerate(["重生", "標題畫面"]):
        bx = W / 2 - 100
        by = 130 + i * 24
        c.fill(bx, by, bx + 200, by + 20, 0xFF6F6F6F)
        c.outline(bx, by, 200, 20, 0xFF000000)
        c.centered(bx + 100, by + 6, label, 0xFFFFFF)
    width = 210
    x = W - width - 8
    y = 24
    body = [
        [("✖ 穿心 ", 0xFF5555), ("潮汐統帥", 0xFFFFFF), ("・", 0x555555), ("滅世巨浪", 0xFFFFFF), ("　物理 5,880", 0xC8C8C8), ("（最大生命 4,200 的 140%）", 0x555555)],
        [("▼ 最後 5 下（3.2 秒內，共 ", 0xAAAAAA), ("8,950", 0xFFFFFF), ("＝最大生命的 213%）", 0xAAAAAA)],
        [(" ▮▮▮▮▮▮▮", 0x55DD55), ("▮▮▮", 0x555555), (" -3.2s ", 0x555555), ("潮汐統帥", 0xAAAAAA), ("・普攻 ", 0x555555), ("-820", 0xFF5555), (" 物理 820", 0x555555)],
        [(" ▮▮▮▮▮", 0xFFD24A), ("▮▮▮▮▮", 0x555555), (" -2.1s ", 0x555555), ("點燃", 0xAAAAAA), ("・持續 ", 0x555555), ("-640", 0xFF5555), (" 火焰 640", 0xFF7B3D)],
        [(" ▮▮▮", 0xFFD24A), ("▮▮▮▮▮▮▮", 0x555555), (" -1.4s ", 0x555555), ("潮汐統帥", 0xAAAAAA), ("・潮汐漩渦 ", 0x555555), ("-1,610", 0xFF5555), (" 冰冷 1,610", 0x7FC8FF)],
        [(" ▮", 0xFF5555), ("▮▮▮▮▮▮▮▮▮", 0x555555), (" -0.0s ", 0x555555), ("潮汐統帥", 0xAAAAAA), ("・滅世巨浪 ", 0x555555), ("-5,880", 0xFF5555), (" 物理 5,880", 0x555555)],
        [("⚔ 承受：", 0xAAAAAA), ("物理 6,700", 0xC8C8C8), ("（護甲擋掉 12%）、", 0x555555), ("冰冷 1,610", 0x7FC8FF), ("（抗性擋掉 40%）", 0x555555)],
        [("✦ 提示：冰冷抗性 40%，沒滿（上限 75%）——補滿的話這段的冰冷傷害少 58%", 0xFFFF55)],
    ]
    # 換行：大約每行 38 個字
    wrapped = []
    for line in body:
        cur, n = [], 0
        for t, col in line:
            for ch in t:
                if c.width("".join(t for t, _ in cur) + ch, 0.7) > width - 10:
                    wrapped.append(cur)
                    cur, n = [], 0
                if cur and cur[-1][1] == col:
                    cur[-1] = (cur[-1][0] + ch, col)
                else:
                    cur.append((ch, col))
                n += 1
        wrapped.append(cur)
    height = 16 + len(wrapped) * 7 + 12
    c.fill(x, y, x + width, y + height, 0xD0100606)
    c.fill(x, y, x + width, y + 13, 0xE0300808)
    c.outline(x, y, width, height, 0xFF7A2A20)
    c.text(x + 5, y + 3, "☠ 死亡回顧", 0xFF5555)
    dy = y + 17
    for line in wrapped:
        c.parts(x + 5, dy, line, 0.7)
        dy += 7
    c.text(x + 5, y + height - 8, "[J] 重生後再看一次", 0x707070, 0.55)
    c.text(14, 300, "裝了模組（招呼 death:true）的人聊天欄就不印這一段；/deaths 不管有沒有裝都照舊印在聊天欄", 0xE0E0E0, 0.85)
    c.save("preview-14c-death.png")


def button(c, x, y, w, label, on=None):
    c.fill(x, y, x + w, y + 20, 0xFF6F6F6F)
    c.outline(x, y, w, 20, 0xFF000000)
    text = label if on is None else f"{label}：{'開' if on else '關'}"
    c.centered(x + w / 2, y + 6, text, 0xFFFFFF)


def scene_settings():
    c = Canvas()
    c.fill(0, 0, W, H, 0xA0101010)
    header(c, "示意圖：星骸輔助模組 1.4（C 組）　設定畫面（按 O）　1920×1080　GUI 縮放 3")
    c.centered(W / 2, 6 + 14, "星骸輔助模組設定", 0xF5B942)
    c.centered(W / 2, 17 + 14, "按鍵：O 設定　M 大地圖　J 死亡回顧（在「選項 → 控制 → 按鍵設定 → 星骸」改）", 0x909090, 0.6)
    toggles = ["星骸 HUD", "技能列", "增益列", "目標框", "取代原版愛心", "生命球、魔力球", "技能格寫按鍵", "特效改由模組繪製", "提示列", "側邊面板",
               "戰鬥資源", "異常、守護", "經驗條的字", "光環、召喚物、撿到的", "地圖資訊框", "傷害數字", "怪物頭上血條", "Boss 預警", "小地圖",
               "死亡回顧面板", "精簡的裝備說明", "自動更新"]
    cols, cw = 3, 140
    start = W / 2 - (cols * cw + (cols - 1) * 4) / 2
    y = 26 + 14
    for i, t in enumerate(toggles):
        button(c, start + (i % cols) * (cw + 4), y, cw, t, t != "傷害數字")
        if i % cols == cols - 1:
            y += 21
    if len(toggles) % cols:
        y += 21
    y += 3
    left = W / 2 - 154
    right = W / 2 + 4
    for xx, label, frac in [(left, "HUD 縮放：1.0×", 0.2), (right, "小地圖大小：72", 0.27)]:
        c.fill(xx, y, xx + 150, y + 20, 0xFF3A3A3A)
        c.outline(xx, y, 150, 20, 0xFF000000)
        c.fill(xx + int(142 * frac), y, xx + int(142 * frac) + 8, y + 20, 0xFFA0A0A0)
        c.centered(xx + 75, y + 6, label, 0xFFFFFF)
    y += 24
    button(c, left, y, 150, "拖動 HUD 位置…")
    button(c, right, y, 150, "重設預設值")
    y += 24
    button(c, W / 2 - 100, y, 200, "完成")
    c.save("preview-14c-settings.png")


def scene_edit():
    grid, rooms, centers = minimap_cells()
    seen, player = fog(grid, centers, 7)
    c = Canvas()
    header(c, "示意圖：星骸輔助模組 1.4（C 組）　拖動 HUD 位置（設定畫面 →「拖動 HUD 位置…」）　1920×1080　GUI 縮放 3")
    bossbar(c, 24)
    hotbar(c)
    boxes = [("小地圖", W - 76, 4, 72, 79), ("地圖資訊框", W - 154, 86, 150, 46), ("側邊面板", W - 152, 144, 150, 160),
             ("Boss 預警", W / 2 - 162, 70, 324, 54), ("機制清單", 4, 18, 132, 64), ("目標框", W / 2 - 84, 33, 168, 32),
             ("提示列", W / 2 - 140, H - 84 - 44, 280, 44), ("技能列", W / 2 - 99, H - 37 - 18, 198, 18), ("增益列", 2, H - 15 - 24, 175, 24),
             ("異常、守護", W / 2 - 122 - 46, H - 39 - 33, 46, 30), ("戰鬥資源", W / 2 + 164, H - 37, 150, 36), ("生命球、魔力球", W / 2 - 159, H - 37, 318, 36),
             ("經驗字", W / 2 - 91, H - 30, 182, 6)]
    for name, x, y, w, h in boxes:
        hot = name == "小地圖"
        c.fill(x, y, x + w, y + h, 0x6040A0FF if hot else 0x30FFFFFF)
        c.outline(x, y, w, h, 0xFF60C0FF if hot else 0xC0FFFFFF)
        c.text(x + 2, y + 2, name + (" *" if name in ("機制清單",) else ""), 0xFFFFFF, 0.6)
    c.centered(W / 2, H / 2 + 16, "左鍵拖動、右鍵回到原位（按著 Shift 不吸附）", 0xE0E0E0)
    button(c, W / 2 - 102, H / 2 + 30, 100, "全部重設")
    button(c, W / 2 + 2, H / 2 + 30, 100, "完成")
    c.save("preview-14c-hudedit.png")




TILE_DIM = {1: 0x90202430, 2: 0xA0686258, 3: 0x90203A60}


def draw_map2(c, grid, seen, x0, y0, per, opacity, show_all=True):
    N = len(grid)
    layer = Image.new("RGBA", c.img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for z in range(N):
        for x in range(N):
            t = grid[z][x]
            if t == 0:
                continue
            if seen[z][x]:
                col = TILE[t]
            elif show_all:
                col = TILE_DIM[t]
            else:
                continue
            a = int(((col >> 24) & 255) * opacity)
            col = (a << 24) | (col & 0xFFFFFF)
            gx, gz = x0 + x * per, y0 + z * per
            if gx + per < 0 or gz + per < 0 or gx > W or gz > H:
                continue
            d.rectangle([gx * S, gz * S, (gx + per) * S, (gz + per) * S], fill=argb(col))
    c.img = Image.alpha_composite(c.img, layer)


MOB = {0: 0xFFFFFF, 1: 0x8888FF, 2: 0xFFFF77, 3: 0xAF6025}


def mobs(c, list_, x0, y0, per, dot, opacity):
    a = int(max(0.6, opacity) * 255) << 24
    for (mx, mz, r) in list_:
        x, y = round(x0 + (mx + 0.5) * per), round(y0 + (mz + 0.5) * per)
        half = dot if r >= 2 else max(1, dot - 1)
        c.fill(x - half - 1, y - half - 1, x + half + 1, y + half + 1, a)
        c.fill(x - half, y - half, x + half, y + half, a | MOB[r])


def game_bg(c):
    """地獄磚的場景（示意）：紅黑格子。"""
    random.seed(3)
    layer = Image.new("RGBA", c.img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for gy in range(0, H, 12):
        for gx in range(0, W, 12):
            k = random.randint(0, 40)
            d.rectangle([gx * S, gy * S, (gx + 12) * S, (gy + 12) * S], fill=(70 + k, 18 + k // 3, 22 + k // 3, 255))
    c.img = Image.alpha_composite(c.img, layer)


def scene_bigmap(opacity, name, show_all=True):
    grid, rooms, centers = minimap_cells()
    seen, player = fog(grid, centers, 4)
    random.seed(11)
    N = len(grid)
    floor = [(x, z) for z in range(N) for x in range(N) if grid[z][x] == 1]
    mobs_list = []
    for (cx, cz) in centers[1:-1]:
        for _ in range(random.randint(3, 7)):
            r = random.choices([0, 1, 2], [6, 3, 1])[0]
            mobs_list.append((cx + random.randint(-4, 4), cz + random.randint(-4, 4), r))
    events = [(centers[2][0] + 3, centers[2][1], "神龕", 0x9FE8C8, 2), (centers[5][0], centers[5][1] + 2, "裂隙", 0xC48BFF, 1),
              (centers[7][0] - 2, centers[7][1], "保險箱", 0xC8D0D8, 0)]
    c = Canvas(sky=False)
    game_bg(c)
    size = min(W - 40, H - 80)
    per = size / N
    cx, cy = W / 2, H / 2
    left, top = cx - player[0] * per, cy - player[1] * per
    draw_map2(c, grid, seen, left, top, per, opacity, show_all)
    mobs(c, mobs_list, left, top, per, 2, opacity)
    for (ex, ez, ename, col, st) in events:
        px, pz = left + ex * per, top + ez * per
        glyph(c, px, pz, "✔" if st == 2 else "◆", 0x606060 if st == 2 else col, 0.9)
        c.centered(px, pz + 4, ename + ("（完成）" if st == 2 else "（進行中）" if st == 1 else ""), 0x808080 if st == 2 else col, 0.5)
    bx, bz = centers[-1]
    glyph(c, left + bx * per, top + bz * per, "☠", 0xFF7070, 1.1)
    c.centered(left + bx * per, top + bz * per + 5, "Boss", 0xFF7070, 0.5)
    c.arrow(cx, cy, 90, 0xFFE070, 4)
    c.text(8, 8, "地圖　探索 31%", 0xF5B942, 0.75)
    c.text(8, 16, "◆ 事件　☠ Boss　● 隊友　▲ 你　▪ 怪（白 普通、藍 魔法、黃 稀有）　[M] 收起", 0xB0B0B0, 0.55)
    c.text(8, H - 12, "示意圖：模組 1.4.8 大地圖　不透明度 " + str(round(opacity * 100)) + "%　以你為中心　沒走過的房間暗一點　1920×1080 GUI 3", 0xFFFFFF, 0.55)
    hotbar(c)
    c.save(name)


scene_bigmap(0.7, "preview-148-bigmap-70.png")
scene_bigmap(0.35, "preview-148-bigmap-35.png")
print("ok")
