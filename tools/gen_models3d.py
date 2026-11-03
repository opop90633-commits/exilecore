#!/usr/bin/env python3
"""
《星骸》3D 時裝的模型（2026-10）：武器、副手、頭飾、背飾（翅膀、光輪）、寵物，全部是自己用方塊堆出來的 3D 物品模型，
不是原版的東西改的。

貼圖（2026-11-03 改成真的貼圖集）：每個模型一張 64×64（放不下就 128、256）的貼圖集，每個方塊的每一面在貼圖集上分到自己的一塊
（照面的實際尺寸，每格 2 像素、放不下就 1 像素；一樣大小、顏色、材質的面共用一塊），shelf packing 排進去、面的 uv 指到那一塊。
每一塊照色票那一格的基色，用「材質畫法」畫（Model.materials：色票格 → 材質，見 MATERIALS；box(mat=...) 可以指定單一方塊）：
光照（上面亮、下面暗）、邊緣（外圈 1 像素暗、內圈 1 像素亮，有厚度感）、材質紋路（金屬刷痕、木紋、織紋、鱗片、條紋……）、
小幅度的雜訊（用模型名稱當種子，每次產生一樣）。
發光的面（glow=True）另外放到 <代號>_glow.png：GLOW_FRAMES 格直排的動畫貼圖（.mcmeta frametime GLOW_FRAMETIME、interpolate），
每格亮度脈動；模型 textures 的 "#1" 指到它（一般的面用 "#0"），light_emission 照舊。
半透明：crystal／glass／water 這幾種材質 alpha 不滿（TRANSLUCENT）。1.21 一般物品（非方塊物品）畫的時候用 translucent 的
render type，GUI、手上、展示實體都會混色；要是幻化套在方塊物品上（cutout）半透明的像素會變實心，所以這幾種材質都另外有亮邊。
色票 colors 還是每個模型 16 格（"#rrggbb" 或 ("#rrggbb", alpha)），只是現在是材質的基色；palette_png() 留著給其他工具看。

輸出到 src/main/resources/web/pack3d.zip（裡面照資源包的路徑）；UiPack 產生介面資源包時原封不動放進去：
  assets/exilecore/models/item/skin3d/<代號>.json              模型
  assets/exilecore/textures/item/skin3d/<代號>.png             貼圖集
  assets/exilecore/textures/item/skin3d/<代號>_glow.png(.mcmeta) 發光面的動畫貼圖（有發光面才有）
  assets/exilecore/items/skin/<代號>_<部位>.json               物品定義（SponsorService 套幻化時用 exilecore:skin/<代號>_<部位>）

給其他工具（BetterModel 轉換等）：build_all() 回 {代號: (Model 們, 部位 → 模型名[, 額外檔案])}；Model 有 name、colors、kind、materials、
elements（每一面的 "uv" 已經是 0..16 的貼圖座標，另外帶 "_tex" = {c 色票格, mat 材質, w, h 面的尺寸, glow, face}，json() 輸出時拿掉）、
textures()（{檔名: PIL Image}）、json()。

部位：weapon → blade、staff；offhand → shield、quiver；headgear → helmet；back → back（翅膀是 back_l、back_r 兩片）；pet → pet。

未擁有變灰（2026-11-03，時裝櫃用）：
  模型每一面都寫 tintindex 0（沒有 tints 的時候顏色不變）。物品定義 items/skin/<代號>_<部位>.json 整棵包一層
  {"type": "minecraft:condition", "property": "minecraft:custom_model_data", "index": 0, "on_true": <變灰版>, "on_false": <原本>}，
  變灰版 = 原本那棵樹深拷貝、每個 {"type": "minecraft:model"} 節點加 tints: [{"type": "minecraft:constant", "value": DIM_TINT}]
  （戰甲的身、腿、腳引用的 2D 圖 exilecore:item/skin/... 是 item/generated，layer0 本來就有 tintindex，一樣會變灰）。
  手做的模型（CUSTOM）讀進來時也補 tintindex、物品定義一樣包 condition。
  伺服器端：ItemStack 的 custom_model_data 旗標 flags[0] = true（CustomModelData.customModelData().addFlag(true)）就整件變灰，
  SponsorMenus 列未擁有的時裝時掛上去；沒旗標（或 false）照原本的顏色。

用法：python3 tools/gen_models3d.py [--preview 預覽.png]（預覽每一面照貼圖集取樣，看得到材質）
"""
import json
import math
import os
import random
import sys

from PIL import Image, ImageDraw

ROOT = os.path.join(__import__("tempfile").gettempdir(), "exilecore_pack3d")  # 中間檔（不放進插件）
ZIP = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "web", "pack3d.zip")
NS = "exilecore"

# ---- 貼圖（2026-11-03 改成真的貼圖集：每一面在貼圖集上有自己的一塊，照材質畫） ----


def rgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def color_of(colors, i):
    """色票第 i 格 → RGBA（格子可以是 "#rrggbb" 或 ("#rrggbb", alpha)）。"""
    c = colors[i] if i < len(colors) else None
    if c is None:
        return (255, 0, 255, 255)
    return rgb(c[0], c[1]) if isinstance(c, tuple) else rgb(c)


def palette_png(colors, seed):
    """舊版的 16 格色票（4×4 格、每格 4×4）。現在貼圖集才是模型用的；留著給預覽／其他工具看顏色。"""
    rnd = random.Random(seed)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for i, c in enumerate(colors[:16]):
        if c is None:
            continue
        base = rgb(c[0], c[1]) if isinstance(c, tuple) else rgb(c)
        sx, sy = (i % 4) * 4, (i // 4) * 4
        for y in range(4):
            for x in range(4):
                light = 1.12 - y * 0.08 + rnd.uniform(-0.035, 0.035)
                px[sx + x, sy + y] = tuple(max(0, min(255, int(base[k] * light))) for k in range(3)) + (base[3],)
    return img


# 材質種類（Model.materials：色票格 → 材質；box(mat=...) 可以指定單一方塊）：
#   plain 光滑　metal 金屬（直刷痕＋高光）　wood 木紋　cloth 織紋　leather 皮革　stone 石頭　crystal 水晶（斜切面、半透明）
#   feather 羽紋　scale 鱗片（shell 同）　fur 毛　glow 發光（中心亮）　glass 玻璃（半透明、邊亮）　water 波紋　straw 草編
#   stripe 條紋（stripe:h 橫向、stripe:v 直向，預設 h；也可寫 stripe:h:#rrggbb 指定第二個顏色）
#   water:flow（2026-11-03 星潮祭）：發光面的水，波紋照動畫格往下流
MATERIALS = ("plain", "metal", "wood", "cloth", "leather", "stone", "crystal", "feather", "scale", "shell", "fur",
             "glow", "glass", "water", "straw", "stripe")
TRANSLUCENT = {"crystal": 200, "glass": 130, "water": 170}   # 半透明材質的 alpha（1.21 物品用 translucent 的 render type，可以混色）
GLOW_FRAMES, GLOW_FRAMETIME = 6, 3                           # 發光面的動畫：6 格、每格 3 tick


def _clamp(v):
    return 0 if v < 0 else 255 if v > 255 else int(v)


def _mix(c, white, t):
    return tuple(c[k] + (white[k] - c[k]) * t for k in range(3))


def _vnoise(rnd_seed, u, v, cell):
    """很簡單的 value noise（每 cell 像素一個隨機值、雙線性內插），給皮革、石頭、毛用。"""
    def val(i, j):
        return random.Random((rnd_seed * 73856093) ^ (i * 19349663) ^ (j * 83492791)).random()
    fu, fv = u / cell, v / cell
    i, j = int(fu), int(fv)
    tu, tv = fu - i, fv - j
    a = val(i, j) * (1 - tu) + val(i + 1, j) * tu
    b = val(i, j + 1) * (1 - tu) + val(i + 1, j + 1) * tu
    return a * (1 - tv) + b * tv


def material_pixel(mat, u, v, w, h, base, seed, rnd, phase=None):
    """材質的一個像素：回 (rgb, alpha, 亮度係數)。u、v 是在這一面裡的像素座標（0..w-1, 0..h-1）。
    phase（2026-11-03 星潮祭）：發光動畫的格 0..1，目前只有 water:flow 用——波紋跟著格往下流（發光的水面看起來在流動；
    原本的 water 不動，夏日的水槍弩不變）。"""
    kind, _, arg = mat.partition(":")
    rgbv = base[:3]
    alpha = base[3]
    k = 1.0
    cu, cv = (u + 0.5) / w, (v + 0.5) / h            # 0..1
    long_h = w >= h                                  # 長邊是橫的
    if kind == "metal":
        k *= 1.0 + 0.1 * (random.Random(seed * 31 + (u if h >= w else v)).random() - 0.5) * 2    # 沿長邊的刷痕
        band = (cu + cv) if long_h else (cu - cv + 1)
        k *= 1.0 + 0.24 * max(0.0, 1 - abs(band - 0.75) * 5)     # 一道斜的高光
        k *= 1.0 - 0.1 * max(0.0, 1 - abs(band - 1.3) * 4)       # 高光另一側暗一點
        if w >= 3 and h >= 3 and rnd.random() < 0.06:
            k *= 1.3                                               # 高光斑
    elif kind == "wood":
        t = (cv if long_h else cu) * (h if long_h else w)
        s = (cu if long_h else cv) * (w if long_h else h)
        k *= 1.0 + 0.12 * math.sin(t * 2.1 + 0.9 * math.sin(s * 0.45 + seed % 7)) - 0.04
        if rnd.random() < 0.02:
            k *= 0.85                                              # 小節疤
    elif kind == "cloth":
        k *= 1.05 if (u + v) % 2 == 0 else 0.93
        if u % 2 == 0 and v % 2 == 0:
            k *= 1.04
    elif kind == "leather":
        k *= 0.86 + 0.26 * _vnoise(seed, u, v, 2.0)
        if (u == 1 or u == w - 2 or v == 1 or v == h - 2) and (u + v) % 2 == 0 and w >= 4 and h >= 4:
            k *= 1.12                                              # 縫線
    elif kind == "stone":
        k *= 0.84 + 0.3 * _vnoise(seed, u, v, 2.5)
        if rnd.random() < 0.06:
            k *= 0.72                                              # 裂縫的點
    elif kind == "crystal":
        facet = int((u + v) // max(1, (w + h) // 5 or 1)) % 3
        k *= (0.9, 1.0, 1.14)[facet]
        if abs((u - v) - (w - h) // 2) <= 0:
            k *= 1.35                                              # 斜的亮線
        rgbv = _mix(rgbv, (255, 255, 255), 0.12)
        alpha = min(alpha, TRANSLUCENT["crystal"])
    elif kind == "feather":
        if long_h:
            shaft = abs(cv - 0.5) < 0.5 / h
            barb = (u + (v if cv > 0.5 else -v)) % 3 == 0
        else:
            shaft = abs(cu - 0.5) < 0.5 / w
            barb = (v + (u if cu > 0.5 else -u)) % 3 == 0
        if shaft and (h if long_h else w) >= 3:
            k *= 1.12
        elif barb:
            k *= 0.86
    elif kind in ("scale", "shell"):
        row = v // 2
        col = (u + (row % 2)) // 2
        if v % 2 == 1:
            k *= 0.76                                              # 每片鱗的下緣
        elif (u + (row % 2)) % 2 == 0:
            k *= 1.1
        k *= 1.0 + 0.04 * (random.Random(seed + row * 97 + col).random() - 0.5)
    elif kind == "fur":
        k *= 0.86 + 0.26 * _vnoise(seed, u, v // 3, 1.0)       # 一撮一撮的直毛
        if random.Random(seed * 17 + u * 131 + v * 7).random() < 0.12:
            k *= 0.85                                              # 毛尖的暗點
    elif kind == "glow":
        d = math.hypot(cu - 0.5, cv - 0.5) * 2
        k *= 1.0 + 0.35 * max(0.0, 1 - d)
        rgbv = _mix(rgbv, (255, 255, 255), 0.55 * max(0.0, 1 - d) ** 2)
    elif kind == "glass":
        rgbv = _mix(rgbv, (255, 255, 255), 0.15)
        if abs((u - v) - (w - h) // 2 + 1) <= 0:
            k *= 1.3                                               # 反光斜線
        alpha = min(alpha, TRANSLUCENT["glass"])
    elif kind == "water":
        flow = (phase or 0.0) * 2 * math.pi if arg == "flow" else 0.0   # 2026-11-03 星潮祭 water:flow：每一格波紋往下移一點，整輪剛好一個週期
        wave = math.sin(v * 1.6 - flow + 0.9 * math.sin(u * 0.9 + seed % 5) + seed % 3)
        k *= 1.0 + 0.09 * wave
        if wave > 0.92:
            k *= 1.22                                              # 波峰的反光
        rgbv = _mix(rgbv, (255, 255, 255), 0.08)
        alpha = min(alpha, TRANSLUCENT["water"])
    elif kind == "straw":
        k *= 1.08 if ((u // 2) + (v // 2)) % 2 == 0 else 0.9
        if u % 2 == 0:
            k *= 0.93
    elif kind == "stripe":
        parts = arg.split(":")
        direction = parts[0] if parts and parts[0] in ("h", "v") else "h"
        n = (v if direction == "h" else u)
        width = max(1, round((h if direction == "h" else w) / 4))
        if (n // width) % 2 == 1:
            if len(parts) > 1 and parts[1].startswith("#"):
                rgbv = rgb(parts[1])[:3]
            else:
                lum = 0.299 * rgbv[0] + 0.587 * rgbv[1] + 0.114 * rgbv[2]
                rgbv = _mix(rgbv, (255, 255, 255), 0.4) if lum < 110 else tuple(c * 0.7 for c in rgbv)
    else:  # plain：上面微亮、下面微暗
        k *= 1.06 - 0.12 * cv
    return rgbv, alpha, k


def paint_face(px, x0, y0, w, h, base, mat, face, seed, phase=None):
    """把一面畫到貼圖集：基色 × 光照（上亮下暗）× 材質 × 邊緣（外圈暗、內圈亮）× 雜訊。phase：發光動畫 0..1。"""
    rnd = random.Random(seed)
    light = {"up": 1.12, "down": 0.8}.get(face, 1.0)
    for v in range(h):
        for u in range(w):
            rgbv, alpha, k = material_pixel(mat, u, v, w, h, base, seed, rnd, phase)
            k *= light
            if w >= 3 and h >= 3:
                if u == 0 or v == 0 or u == w - 1 or v == h - 1:
                    k *= 0.74
                elif u == 1 or v == 1 or u == w - 2 or v == h - 2:
                    k *= 1.1
            elif w >= 2 and h >= 2 and (u == w - 1 or v == h - 1):
                k *= 0.84
            k *= 1.0 + rnd.uniform(-0.035, 0.035)
            if phase is not None:
                pulse = 0.5 + 0.5 * math.sin(phase * 2 * math.pi)
                k *= 0.85 + 0.35 * pulse
                cu, cv = (u + 0.5) / w - 0.5, (v + 0.5) / h - 0.5
                d = max(0.0, 1 - math.hypot(cu, cv) * 2)
                rgbv = _mix(rgbv, (255, 255, 255), 0.45 * pulse * d)
            px[x0 + u, y0 + v] = (_clamp(rgbv[0] * k), _clamp(rgbv[1] * k), _clamp(rgbv[2] * k), int(alpha))


def shelf_pack(rects, width):
    """最簡單的 shelf packing：照高矮排、一排一排放。rects：[(w, h)] → [(x, y)]、總高。"""
    order = sorted(range(len(rects)), key=lambda i: (-rects[i][1], -rects[i][0]))
    pos = [None] * len(rects)
    x = y = shelf_h = 0
    for i in order:
        w, h = rects[i]
        if x + w > width:
            x, y, shelf_h = 0, y + shelf_h, 0
        pos[i] = (x, y)
        x += w
        shelf_h = max(shelf_h, h)
    return pos, y + shelf_h


# ---- 模型 ----

FACES = ("north", "south", "east", "west", "up", "down")
FACE_SIZE = {   # 每一面的寬、高是哪兩個軸（0 = x、1 = y、2 = z）
    "north": (0, 1), "south": (0, 1), "east": (2, 1), "west": (2, 1), "up": (0, 2), "down": (0, 2)}


class Model:
    def __init__(self, name, colors, kind, materials=None):
        self.name = name
        self.colors = colors
        self.kind = kind
        self.materials = dict(materials or {})   # 色票格 → 材質（見 MATERIALS）；沒寫的是 plain
        self._elements = []
        self._dirty = True
        self._layout = None

    # elements：每一面的 "uv" 永遠是算好的（0..16 貼圖座標）；面上另外帶一個 "_tex"（畫貼圖用的資料，json() 時拿掉）
    @property
    def elements(self):
        if self._dirty:
            self._pack()
        return self._elements

    @elements.setter
    def elements(self, value):
        self._elements = value
        self._dirty = True

    def box(self, a, b, c, rot=None, faces=None, glow=False, mat=None, part=None):
        """a、b：兩個角；c：色票第幾格（或 {面: 格}）；rot：(角度, 軸, 原點)；faces：只畫哪幾面；mat：這個方塊的材質（或 {面: 材質}）。
        part（2026-11-03 星潮祭）：("骨頭路徑", 轉軸位置)，給 gen_cosmetic_bbmodels.py 分骨頭用——路徑用 / 分層（"t0a/t0b" = t0b 是 t0a 的子骨頭、
        每一層的名字要獨一無二），轉軸是物品模型座標；json() 輸出時拿掉，物品模型本身不受影響。"""
        lo = [round(min(a[i], b[i]), 3) for i in range(3)]
        hi = [round(max(a[i], b[i]), 3) for i in range(3)]
        for v in lo + hi:
            assert -16 <= v <= 32, (self.name, a, b)
        el = {"from": lo, "to": hi, "faces": {}}
        if part is not None:
            el["_part"] = {"path": part[0], "pivot": [round(float(v), 3) for v in part[1]]}
        for f in FACES:
            if faces is not None and f not in faces:
                continue
            idx = c[f] if isinstance(c, dict) else c
            m = (mat[f] if isinstance(mat, dict) else mat) or self.materials.get(idx) or ("glow" if glow else "plain")
            ax, ay = FACE_SIZE[f]
            # tintindex 0：物品定義有 tints 的時候才染（未擁有變灰用，見檔頭）；沒有 tints 顏色不變
            el["faces"][f] = {"uv": [0, 0, 1, 1], "texture": "#1" if glow else "#0", "tintindex": 0,
                              "_tex": {"c": idx, "mat": m, "w": hi[ax] - lo[ax], "h": hi[ay] - lo[ay], "glow": bool(glow), "face": f}}
        if rot is not None:
            angle, axis, origin = rot
            assert angle in (-45, -22.5, 0, 22.5, 45), angle
            el["rotation"] = {"angle": angle, "axis": axis, "origin": list(origin)}
        if glow:
            el["light_emission"] = 15
        self._elements.append(el)
        self._dirty = True
        return el

    def mirror_x(self, src_count=None):
        """把目前的方塊左右鏡射（x → 16 − x）再加一份。"""
        for el in list(self.elements[:src_count] if src_count else self.elements):
            lo, hi = el["from"], el["to"]
            new = json.loads(json.dumps(el))
            new["from"] = [round(16 - hi[0], 3), lo[1], lo[2]]
            new["to"] = [round(16 - lo[0], 3), hi[1], hi[2]]
            if "rotation" in new:
                r = new["rotation"]
                r["origin"][0] = round(16 - r["origin"][0], 3)
                if r["axis"] in ("y", "z"):
                    r["angle"] = -r["angle"]
            if "_part" in new:   # 2026-11-03 星潮祭：骨頭的轉軸跟著鏡射、名字 _r → _l（翅膀右邊做好鏡射成左邊）
                p = new["_part"]
                p["pivot"][0] = round(16 - p["pivot"][0], 3)
                p["path"] = "/".join(seg[:-2] + "_l" if seg.endswith("_r") else seg for seg in p["path"].split("/"))
            f = new["faces"]
            if "east" in f or "west" in f:
                e, w = f.pop("east", None), f.pop("west", None)
                if e:
                    f["west"] = e
                    e["_tex"]["face"] = "west"
                if w:
                    f["east"] = w
                    w["_tex"]["face"] = "east"
            self._elements.append(new)
        self._dirty = True

    def flip_x(self):
        """整個模型左右鏡射（x → 16 − x），不加一份。"""
        n = len(self.elements)
        self.mirror_x()
        self.elements = self._elements[n:]

    def tilt(self, angle=-45, axis="z", origin=(8, 8, 8)):
        """
        整個模型一起轉（武器：直的做好之後斜 45 度，對上原版手持物品的角度）。
        原版平面物品的圖：圖的右上角在模型裡是 x 大、y 大（右上），拿在手上時右上角朝前。
        所以劍尖要轉到右上（−45 度；2026-10 實測：轉反了劍會朝下）；弓、弩做好之後先左右鏡射再轉 −45 度（實測這樣拿起來才對）。
        """
        for el in self.elements:
            assert "rotation" not in el, self.name
            el["rotation"] = {"angle": angle, "axis": axis, "origin": list(origin)}

    # ---- 貼圖集 ----

    @staticmethod
    def _region_key(t):
        side = "up" if t["face"] == "up" else "down" if t["face"] == "down" else "side"
        return (t["c"], t["mat"], round(t["w"], 3), round(t["h"], 3), t["glow"], side)

    def _pack(self):
        """每一面在貼圖集上分一塊（一樣的面共用一塊）：先試 64×64／每格 2 像素，放不下就 1 像素、再放不下就 128、256。
        發光的面另外放到 <name>_glow（動畫貼圖）。"""
        self._dirty = False
        keys = {False: [], True: []}
        for el in self._elements:
            for face in el["faces"].values():
                t = face["_tex"]
                k = self._region_key(t)
                if k not in keys[t["glow"]]:
                    keys[t["glow"]].append(k)
        layout = {}
        for glow in (False, True):
            ks = keys[glow]
            if not ks:
                if not glow:   # 全部都是發光面的模型：主貼圖還是要有（particle 用），給一塊 16×16 的底色
                    layout[False] = {"size": 16, "scale": 1, "regions": {}}
                continue
            for width, scale in ((64, 2), (64, 1), (128, 2), (128, 1), (256, 1), (256, 1)):
                if glow:
                    width //= 2
                rects = [(max(1, round(k[2] * scale)), max(1, round(k[3] * scale))) for k in ks]
                if any(r[0] > width for r in rects):
                    continue
                pos, height = shelf_pack(rects, width)
                if height <= width:
                    break
            else:
                raise AssertionError((self.name, "貼圖集放不下"))
            layout[glow] = {"size": width, "scale": scale,
                            "regions": {k: (pos[i][0], pos[i][1], rects[i][0], rects[i][1]) for i, k in enumerate(ks)}}
        self._layout = layout
        for el in self._elements:
            for face in el["faces"].values():
                t = face["_tex"]
                lay = layout[t["glow"]]
                x, y, w, h = lay["regions"][self._region_key(t)]
                s = 16 / lay["size"]
                face["uv"] = [round(x * s, 4), round(y * s, 4), round((x + w) * s, 4), round((y + h) * s, 4)]

    def _seed(self):
        return sum(ord(ch) * (i + 1) for i, ch in enumerate(self.name))

    def textures(self):
        """回 {檔名（不含路徑）: Image}：<name>.png 貼圖集；有發光面的話再加 <name>_glow.png（GLOW_FRAMES 格直排的動畫）。"""
        self.elements  # 確定排好了
        out = {}
        seed = self._seed()
        for glow, lay in self._layout.items():
            size = lay["size"]
            frames = GLOW_FRAMES if glow else 1
            img = Image.new("RGBA", (size, size * frames), (0, 0, 0, 0))
            if not lay["regions"]:   # 全發光的模型：主貼圖只拿來當 particle，塗一塊底色
                first = next((i for i, c in enumerate(self.colors) if c), 0)
                img.paste(color_of(self.colors, first), (0, 0, size, size))
            px = img.load()
            for fi in range(frames):
                for i, (k, (x, y, w, h)) in enumerate(lay["regions"].items()):
                    c, mat, _, _, _, side = k
                    face = "up" if side == "up" else "down" if side == "down" else "north"
                    paint_face(px, x, y + fi * size, w, h, color_of(self.colors, c), mat, face, seed + i * 7919,
                               phase=(fi / frames) if glow else None)
            out[f"{self.name}_glow.png" if glow else f"{self.name}.png"] = img
        return out

    def json(self):
        tex = f"{NS}:item/skin3d/{self.name}"
        textures = {"0": tex, "particle": tex}
        elements = json.loads(json.dumps(self.elements))
        has_glow = False
        for el in elements:
            el.pop("_part", None)   # 2026-11-03 星潮祭：骨頭標記只給 bbmodel 用
            for face in el["faces"].values():
                has_glow = has_glow or face["_tex"]["glow"]
                del face["_tex"]
        if has_glow:
            textures["1"] = tex + "_glow"
        return {"textures": textures, "elements": elements, "display": DISPLAY[self.kind]}




HANDHELD = {
    # 原版的手持數值；模型比原版的劍長（約 22 格對 16 格），所以拿在手上縮小一點
    "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.0, 0.5], "scale": [0.68, 0.68, 0.68]},
    "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4.0, 0.5], "scale": [0.68, 0.68, 0.68]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.55, 0.55, 0.55]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.55, 0.55, 0.55]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45]},
    "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.75, 0.75, 0.75]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}
OFFHAND = {
    # 跟原版平面物品（item/generated）一樣拿：盾面朝外側（2026-10：原本轉 90 度變成朝前後，看起來像飄在手臂後面）
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.6, 0.6, 0.6]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.6, 0.6, 0.6]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.85, 0.85, 0.85]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}
HEADGEAR = {
    # 戴在頭上：沒有 head 這一項 = 不轉（模型的 16 格 = 頭的 1.25 倍，頭在 1.6～14.4；正面是 north）
    "gui": {"rotation": [25, 145, 0], "translation": [0, -2.5, 0], "scale": [0.72, 0.72, 0.72]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
}
FLOATING = {
    # 背飾、寵物：在展示實體上（不轉、中心在 8,8,8）；選單裡的圖示
    "gui": {"rotation": [25, 145, 0], "translation": [0, 0, 0], "scale": [0.55, 0.55, 0.55]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
}
DISPLAY = {"weapon": HANDHELD, "offhand": OFFHAND, "headgear": HEADGEAR, "back": FLOATING, "pet": FLOATING}


# ======================================================================
# 武器（直的做：握把在下、尖端在上，中心 x = z = 8；最後整個斜 45 度）
# ======================================================================

def star_greatsword():
    m = Model("star_greatsword", ["#c9a43a", "#ffe08a", "#8a6a1e", "#3a2a24",
                                  "#dfe6f5", "#ffffff", "#9aa6c4", "#5fe0ff",
                                  "#d8fbff", "#7fd8ff", "#2a3050", None,
                                  None, None, None, None], "weapon")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal', 3: 'leather', 4: 'metal', 5: 'metal', 6: 'metal', 9: 'crystal', 10: 'metal'}
    m.box((7, -3, 7), (9, -1, 9), 0)                       # 柄頭
    m.box((7.4, -3.6, 7.4), (8.6, -3, 8.6), 9)              # 柄頭的寶石
    m.box((7.5, -1, 7.5), (8.5, 4, 8.5), 3)                 # 握把
    m.box((7.3, 0.5, 7.3), (8.7, 1, 8.7), 2)
    m.box((7.3, 2.5, 7.3), (8.7, 3, 8.7), 2)
    m.box((4, 4, 7), (12, 5.5, 9), 0)                       # 護手
    m.box((2.8, 4.6, 7.4), (4, 6.6, 8.6), 1)
    m.box((12, 4.6, 7.4), (13.2, 6.6, 8.6), 1)
    m.box((7.1, 4.1, 6.4), (8.9, 5.9, 9.6), 7, glow=True)   # 護手中間的星石
    m.box((6.5, 5.5, 7.55), (9.5, 16.5, 8.45), 4)           # 劍身
    m.box((7.6, 6, 7.4), (8.4, 15.5, 8.6), 7, glow=True)    # 血槽的星光
    m.box((7, 16.5, 7.65), (9, 18, 8.35), 4)
    m.box((7.6, 18, 7.75), (8.4, 19.2, 8.25), 5)
    m.box((6.2, 6, 7.7), (6.5, 15.8, 8.3), 5)               # 刃口亮邊
    m.box((9.5, 6, 7.7), (9.8, 15.8, 8.3), 5)
    m.tilt()
    return m


def void_scythe():
    m = Model("void_scythe", ["#1a1024", "#3a2058", "#5c3488", "#b4b4d0",
                              "#d24bff", "#ffb0ff", "#ff4be0", "#6e6e86",
                              "#24182e", None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'wood', 1: 'wood', 2: 'metal', 3: 'metal', 7: 'metal', 8: 'metal'}
    m.box((7.5, -4.5, 7.5), (8.5, 17, 8.5), 1)              # 長柄
    m.box((7.2, -5, 7.2), (8.8, -4.2, 8.8), 7)
    for y in (0, 6, 11):
        m.box((7.25, y, 7.25), (8.75, y + 0.8, 8.75), 7)    # 柄上的環
    m.box((7, 15.5, 7), (9, 18, 9), 2)                      # 刃座
    m.box((7.3, 16.2, 6.7), (8.7, 17.4, 7), 6, glow=True)   # 刃座的眼
    # 2026-10：刃原本往 +x 長，拿在手上刃尖朝上；鏡像到 -x（刃朝下彎，後面的尖刺換到 +x）
    def X(x):
        return 16 - x
    def mbox(p, q, color, glow=False):
        m.box((min(X(p[0]), X(q[0])), p[1], p[2]), (max(X(p[0]), X(q[0])), q[1], q[2]), color, glow=glow)
    mbox((9, 16, 7.7), (14, 17.8, 8.3), 3)                 # 刃（往右彎下來）
    mbox((13, 14.2, 7.7), (16, 16.6, 8.3), 3)
    mbox((15.2, 11.6, 7.75), (17, 14.6, 8.25), 3)
    mbox((16, 9.2, 7.8), (17.4, 11.8, 8.2), 3)
    mbox((16.4, 7.6, 7.85), (17.2, 9.4, 8.15), 5)
    mbox((9, 15.4, 7.75), (13.4, 16, 8.25), 4, glow=True)  # 內緣的紫光
    mbox((13, 13.8, 7.75), (15.3, 14.3, 8.25), 4, glow=True)
    mbox((14.8, 11, 7.8), (15.3, 13.9, 8.2), 4, glow=True)
    mbox((4.6, 16.4, 7.7), (7, 17.4, 8.3), 7)              # 後面的尖刺
    mbox((3.6, 16.6, 7.8), (4.6, 17.2, 8.2), 3)
    m.tilt()
    return m


def inferno_axe():
    m = Model("inferno_axe", ["#2a1410", "#6a3a10", "#c87a20", "#ffc24a",
                              "#3a0e0a", "#8a1e10", "#d84a1a", "#ff7a1a",
                              "#ffe08a", "#1a0a08", None, None, None, None, None, None], "weapon")
    m.materials = {0: 'wood', 1: 'metal', 2: 'metal', 3: 'metal', 4: 'cloth', 5: 'metal', 6: 'metal', 9: 'metal'}
    m.box((7.5, -4, 7.5), (8.5, 16, 8.5), 0)                # 斧柄
    m.box((7.2, -4.6, 7.2), (8.8, -3.8, 8.8), 2)
    m.box((7.3, -1, 7.3), (8.7, 3, 8.7), 4)                 # 纏布
    m.box((6.4, 11.5, 6.8), (9.6, 16.5, 9.2), 5)            # 斧頭中間
    m.box((7, 12.8, 6.6), (9, 15.2, 9.4), 7, glow=True)     # 熔岩核心
    for sign in (1, -1):
        cx = 8
        def X(x):
            return cx + sign * (x - cx)
        m.box((X(9.6), 11, 7.55), (X(12.5), 17, 8.45), 6)  # 斧刃
        m.box((X(12.5), 10.2, 7.6), (X(14), 17.8, 8.4), 6)
        m.box((X(14), 10.8, 7.65), (X(15), 17.2, 8.35), 7, glow=True)  # 燒紅的刃口
        m.box((X(15), 11.6, 7.7), (X(15.6), 16.4, 8.3), 8, glow=True)
    m.box((7.5, 16.5, 7.5), (8.5, 19.5, 8.5), 2)            # 頂刺
    m.box((7.7, 19.5, 7.7), (8.3, 20.4, 8.3), 3)
    m.tilt()
    return m


def frost_halberd():
    m = Model("frost_halberd", ["#2a5a8a", "#5fa8e0", "#cfefff", "#ffffff",
                                "#8af0ff", "#eaffff", "#6a7a8a", "#b8c8d8",
                                "#1a2e44", None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'crystal', 1: 'crystal', 2: 'crystal', 3: 'crystal', 6: 'metal', 7: 'metal', 8: 'cloth'}
    m.box((7.5, -5, 7.5), (8.5, 15, 8.5), 6)                # 柄
    m.box((7.3, -1, 7.3), (8.7, 2.5, 8.7), 8)
    m.box((7.2, 13.5, 7.2), (8.8, 15, 8.8), 7)
    m.box((7, 15, 7), (9, 17, 9), 1)                        # 冰晶槍頭
    m.box((7.3, 17, 7.3), (8.7, 19, 8.7), 2)
    m.box((7.6, 19, 7.6), (8.4, 20.6, 8.4), 5, glow=True)
    m.box((4.5, 12.4, 7.6), (7.5, 15.4, 8.4), 1)            # 側邊的冰刃（2026-10：換到 -x，拿在手上刃朝下）
    m.box((2.8, 12.8, 7.65), (4.5, 15, 8.35), 2)
    m.box((1.8, 13.3, 7.7), (2.8, 14.6, 8.3), 4, glow=True)
    m.box((8.5, 13.4, 7.7), (10.6, 14.6, 8.3), 2)           # 反方向的小刺
    m.box((10.6, 13.7, 7.75), (11.5, 14.3, 8.25), 4, glow=True)
    m.box((6.6, 15.6, 6.6), (7, 16.8, 7), 4, glow=True)     # 冰晶碎片
    m.box((9, 16, 9), (9.4, 17.2, 9.4), 4, glow=True)
    m.tilt()
    return m


def verdant_staff():
    m = Model("verdant_staff", ["#3a2410", "#5a3a1e", "#a8743a", "#e0b070",
                                "#0e2a1e", "#1f6a46", "#4fc08a", "#3affa0",
                                "#d8ffe8", "#ff9ad5", None, None, None, None, None, None], "weapon")
    m.materials = {0: 'wood', 1: 'wood', 2: 'wood', 3: 'wood', 4: 'feather', 5: 'feather', 6: 'feather', 9: 'plain'}
    m.box((7.4, -5, 7.4), (8.6, 13, 8.6), 1)                # 樹枝杖身
    m.box((7.2, 2, 7.2), (8.8, 3, 8.8), 0)
    m.box((7.2, 8, 7.2), (8.8, 9, 8.8), 0)
    m.box((6, 12.5, 7.4), (7.2, 17.5, 8.6), 1)              # 分岔托著球
    m.box((8.8, 12.5, 7.4), (10, 17.5, 8.6), 1)
    m.box((6.5, 17.5, 7.5), (7.3, 18.5, 8.5), 2)
    m.box((8.7, 17.5, 7.5), (9.5, 18.5, 8.5), 2)
    m.box((6.8, 14.4, 6.8), (9.2, 16.8, 9.2), 7, glow=True)  # 森之核
    m.box((7.3, 14.9, 6.5), (8.7, 16.3, 9.5), 8, glow=True)
    m.box((4.2, 11.6, 7.8), (6, 13, 8.2), 6)                # 葉子
    m.box((3.4, 12.2, 7.85), (4.2, 12.8, 8.15), 5)
    m.box((10, 9.6, 7.8), (11.8, 11, 8.2), 6)
    m.box((11.8, 10, 7.85), (12.6, 10.6, 8.15), 5)
    m.box((6.2, 5, 7.8), (7.4, 6.2, 8.2), 6)
    m.box((9.6, 18.2, 7.8), (10.6, 19.2, 8.2), 9)           # 小花
    m.tilt()
    return m


def blood_katana():
    m = Model("blood_katana", ["#1a0a10", "#5a0f1e", "#a81e34", "#ff3a4a",
                               "#d0d0dc", "#ffffff", "#8a8a98", "#3a3a44",
                               "#c9a43a", None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'cloth', 1: 'cloth', 2: 'cloth', 4: 'metal', 5: 'metal', 6: 'metal', 7: 'metal', 8: 'metal'}
    m.box((7.5, -4.5, 7.5), (8.5, 2, 8.5), 2)               # 柄（紅繩）
    for y in (-3.6, -1.6, 0.4):
        m.box((7.4, y, 7.4), (8.6, y + 0.7, 8.6), 0)        # 菱形的黑紋
    m.box((7.3, -5.1, 7.3), (8.7, -4.5, 8.7), 8)            # 柄頭
    m.box((6, 2, 7), (10, 2.8, 9), 7)                       # 鍔
    m.box((6.6, 2.1, 6.8), (9.4, 2.7, 9.2), 8)
    m.box((7.3, 2.8, 7.75), (8.7, 12, 8.25), 4)             # 刀身（上段微微彎）
    m.box((7.5, 12, 7.78), (9, 17, 8.22), 4)
    m.box((7.9, 17, 7.8), (9.3, 19.5, 8.2), 4)
    m.box((8.4, 19.5, 7.85), (9.3, 20.6, 8.15), 5)
    m.box((7.1, 3.2, 7.8), (7.5, 11.6, 8.2), 3, glow=True)  # 刃紋（血光）
    m.box((7.3, 12, 7.82), (7.7, 16.6, 8.18), 3, glow=True)
    m.box((7.7, 17, 7.85), (8.1, 19.2, 8.15), 3, glow=True)
    m.tilt()
    return m


# ======================================================================
# 副手（直立、正面朝 -z 也朝 +z：兩面都畫）
# ======================================================================

def star_aegis():
    m = Model("star_aegis", ["#c9a43a", "#ffe08a", "#8a6a1e", "#2a3a6a",
                             "#3f5fa8", "#5fe0ff", "#d8fbff", "#f4f6ff",
                             None, None, None, None, None, None, None, None], "offhand")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal', 3: 'metal', 4: 'metal', 7: 'metal'}
    m.box((2, 2, 7), (14, 15, 9), 0)                        # 金框
    m.box((3, 3, 6.5), (13, 14, 9.5), 4)                    # 盾面（兩面）
    m.box((4, 0.5, 7.1), (12, 2, 8.9), 0)                   # 下面收尖
    m.box((5, -1, 7.2), (11, 0.5, 8.8), 0)
    m.box((6.5, -2.4, 7.3), (9.5, -1, 8.7), 2)
    m.box((4.5, 3.4, 6.3), (11.5, 3.9, 9.7), 3)             # 內圈
    m.box((6.5, 6.5, 5.8), (9.5, 9.5, 10.2), 5, glow=True)  # 中間的星石
    m.box((7.4, 4.5, 5.9), (8.6, 11.5, 10.1), 6, glow=True)  # 十字星芒
    m.box((4.5, 7.4, 5.9), (11.5, 8.6, 10.1), 6, glow=True)
    m.box((1.4, 13.5, 7.3), (2.6, 16, 8.7), 1)              # 上緣兩角
    m.box((13.4, 13.5, 7.3), (14.6, 16, 8.7), 1)
    return m


def void_tome():
    m = Model("void_tome", ["#1a1024", "#3a2058", "#5c3488", "#e8dcc0",
                            "#d24bff", "#ffb0ff", "#c9a43a", "#ffe08a",
                            None, None, None, None, None, None, None, None], "offhand")
    m.materials = {0: 'leather', 1: 'leather', 2: 'leather', 3: 'plain', 6: 'metal', 7: 'metal'}
    m.box((3, 2, 5.8), (13, 15, 6.8), 1)                    # 封面
    m.box((3, 2, 9.2), (13, 15, 10.2), 1)                   # 封底
    m.box((3.4, 2.4, 6.8), (12.6, 14.6, 9.2), 3)            # 書頁
    m.box((2.2, 2, 5.8), (3.4, 15, 10.2), 0)                # 書背
    m.box((12.6, 7.6, 6.6), (13.6, 9.4, 9.4), 6)            # 扣環
    m.box((3.6, 2.4, 5.5), (5, 3.8, 6), 6)                  # 金角
    m.box((11, 13.2, 5.5), (12.4, 14.6, 6), 6)
    m.box((3.6, 13.2, 5.5), (5, 14.6, 6), 6)
    m.box((11, 2.4, 5.5), (12.4, 3.8, 6), 6)
    m.box((6.6, 7.4, 5.3), (9.4, 10.2, 5.8), 2)             # 封面上的眼
    m.box((7.4, 8.2, 5), (8.6, 9.4, 5.3), 4, glow=True)
    m.box((7.2, 15.6, 7.2), (8.8, 17.2, 8.8), 5, glow=True)  # 書上飄著的符文
    m.box((7.6, 17.6, 7.6), (8.4, 18.4, 8.4), 4, glow=True)
    return m


# ======================================================================
# 頭飾（頭在 1.6～14.4；正面是 north = z 小的那邊）
# ======================================================================

def ring(m, y0, y1, inset, thick, c, glow=False):
    lo, hi = inset, 16 - inset
    m.box((lo, y0, lo), (hi, y1, lo + thick), c, glow=glow)
    m.box((lo, y0, hi - thick), (hi, y1, hi), c, glow=glow)
    m.box((lo, y0, lo + thick), (lo + thick, y1, hi - thick), c, glow=glow)
    m.box((hi - thick, y0, lo + thick), (hi, y1, hi - thick), c, glow=glow)


def star_crown():
    m = Model("star_crown", ["#c9a43a", "#ffe08a", "#8a6a1e", "#5fe0ff",
                             "#d8fbff", "#7fd8ff", "#f4f6ff", None,
                             None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal', 6: 'metal'}
    ring(m, 13.2, 15.4, 1.2, 1.0, 0)
    ring(m, 13.0, 13.4, 1.0, 0.6, 2)
    for side in range(4):
        for (x, h, w) in ((3.4, 1.8, 1.0), (7.3, 3.6, 1.4), (11.6, 1.8, 1.0)):
            if side == 0:
                a, b = (x, 15.4, 1.2), (x + w, 15.4 + h, 2.0)
            elif side == 1:
                a, b = (x, 15.4, 14.0), (x + w, 15.4 + h, 14.8)
            elif side == 2:
                a, b = (1.2, 15.4, x), (2.0, 15.4 + h, x + w)
            else:
                a, b = (14.0, 15.4, x), (14.8, 15.4 + h, x + w)
            m.box(a, b, 1)
    m.box((7.2, 18.8, 1.1), (8.8, 20.0, 2.1), 3, glow=True)   # 正面最高那根上的星石
    m.box((7.0, 13.6, 0.8), (9.0, 15.0, 1.2), 5, glow=True)   # 額前的寶石
    m.box((3.6, 13.8, 0.9), (4.6, 14.6, 1.2), 4, glow=True)
    m.box((11.4, 13.8, 0.9), (12.4, 14.6, 1.2), 4, glow=True)
    return m


def inferno_horns():
    m = Model("inferno_horns", ["#2a1410", "#4a2418", "#7a3a20", "#ff7a1a",
                                "#ffe08a", "#d84a1a", "#1a0a08", None,
                                None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'stone', 1: 'stone', 2: 'stone', 6: 'metal'}
    n = len(m.elements)
    m.box((1.0, 10.5, 6.0), (2.6, 13.0, 9.4), 2)             # 角根
    m.box((0.0, 12.4, 6.4), (1.6, 15.0, 9.0), 1)
    m.box((-0.8, 14.6, 6.8), (0.8, 17.2, 8.8), 1)
    m.box((-1.2, 16.8, 7.2), (0.2, 19.0, 8.6), 0)
    m.box((-1.0, 18.8, 7.5), (-0.1, 20.4, 8.4), 3, glow=True)  # 燒紅的角尖
    m.box((-0.6, 20.2, 7.7), (0.0, 21.0, 8.2), 4, glow=True)
    m.mirror_x()
    ring(m, 12.6, 13.4, 1.3, 0.6, 6)                          # 黑鐵頭環
    m.box((7.2, 12.4, 1.0), (8.8, 14.0, 1.4), 5, glow=True)
    return m


def frost_halo():
    m = Model("frost_halo", ["#cfefff", "#8af0ff", "#eaffff", "#5fa8e0",
                             "#ffffff", None, None, None,
                             None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'crystal', 1: 'crystal', 2: 'crystal', 3: 'crystal', 4: 'crystal'}
    ring(m, 17.6, 18.4, 2.6, 1.0, 1, glow=True)
    ring(m, 17.9, 18.1, 2.2, 0.4, 2, glow=True)
    for (x, z) in ((2.6, 2.6), (12.4, 2.6), (2.6, 12.4), (12.4, 12.4)):
        m.box((x, 18.4, z), (x + 1.0, 20.0, z + 1.0), 4, glow=True)   # 冰晶
        m.box((x + 0.25, 20.0, z + 0.25), (x + 0.75, 20.8, z + 0.75), 2, glow=True)
    for (x, z) in ((7.4, 2.6), (7.4, 12.4), (2.6, 7.4), (12.4, 7.4)):
        m.box((x, 18.4, z), (x + 1.2, 19.4, z + 1.0), 3)
    return m


def verdant_wreath():
    m = Model("verdant_wreath", ["#1f6a46", "#4fc08a", "#2a8a5a", "#ff9ad5",
                                 "#ffffff", "#ffe08a", "#ff6aa8", "#0e2a1e",
                                 None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'feather', 1: 'feather', 2: 'feather', 3: 'plain', 4: 'plain', 5: 'plain', 6: 'plain', 7: 'wood'}
    ring(m, 13.4, 14.6, 1.0, 1.2, 7)
    rnd = random.Random(7)
    pts = []
    for i in range(7):
        t = 1.4 + i * 2.0
        pts += [(t, 0.7), (t, 14.3), (0.7, t), (14.3, t)]
    for (x, z) in pts:
        c = rnd.choice((0, 1, 2))
        m.box((x - 0.8, 13.6 + rnd.uniform(-0.2, 0.3), z - 0.4), (x + 0.8, 15.2 + rnd.uniform(-0.2, 0.4), z + 0.4), c)
    for (x, z, c) in ((3, 0.4, 3), (8, 0.3, 4), (13, 0.4, 6), (0.4, 5, 3), (0.4, 11, 4),
                      (15.6, 5, 6), (15.6, 11, 3), (5, 15.6, 4), (11, 15.6, 3)):
        m.box((x - 0.7, 14.6, z - 0.4), (x + 0.7, 16.0, z + 0.4), c)
        m.box((x - 0.25, 15.1, z - 0.55), (x + 0.25, 15.5, z + 0.55), 5)   # 花心
    return m


def void_mask():
    m = Model("void_mask", ["#1a1024", "#3a2058", "#5c3488", "#d24bff",
                            "#ffb0ff", "#b4b4d0", "#0a0610", None,
                            None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal', 5: 'leather', 6: 'plain'}
    m.box((2.0, 3.6, 0.9), (14.0, 13.6, 1.5), 1)            # 面具
    m.box((3.0, 2.6, 1.0), (13.0, 3.6, 1.5), 1)
    m.box((5.0, 1.8, 1.05), (11.0, 2.6, 1.5), 0)
    m.box((3.6, 8.4, 0.7), (6.8, 10.0, 0.9), 6)             # 眼窩
    m.box((9.2, 8.4, 0.7), (12.4, 10.0, 0.9), 6)
    m.box((4.4, 8.8, 0.55), (6.0, 9.6, 0.7), 3, glow=True)  # 發光的眼
    m.box((10.0, 8.8, 0.55), (11.6, 9.6, 0.7), 3, glow=True)
    m.box((7.6, 4.0, 0.8), (8.4, 12.8, 0.9), 2)             # 中線
    m.box((2.6, 13.6, 1.0), (4.0, 16.6, 1.6), 2)            # 額上的尖角
    m.box((12.0, 13.6, 1.0), (13.4, 16.6, 1.6), 2)
    m.box((7.2, 13.6, 1.0), (8.8, 17.8, 1.6), 0)
    m.box((7.5, 17.8, 1.1), (8.5, 18.8, 1.5), 3, glow=True)
    m.box((1.4, 9.0, 1.5), (1.8, 10.4, 14.6), 5)            # 綁帶
    m.box((14.2, 9.0, 1.5), (14.6, 10.4, 14.6), 5)
    m.box((1.4, 9.0, 14.2), (14.6, 10.4, 14.6), 5)
    return m


# ======================================================================
# 背飾：翅膀（右邊一片，轉軸在 8,8,8；左邊是鏡射）、光輪
# ======================================================================

def wing(name, colors, build, materials=None):
    m = Model(name, colors, "back", materials)
    build(m)
    if all("rotation" not in el for el in m.elements):
        m.tilt(22.5, "z", (8, 8, 8))  # 整片往上揚一點
    left = Model(name + "_l", colors, "back")
    tmp = Model("tmp", colors, "back")
    tmp.elements = json.loads(json.dumps(m.elements))
    n = len(tmp.elements)
    tmp.mirror_x()
    left.elements = tmp.elements[n:]
    m.name = name + "_r"
    return m, left


def seraph(m):
    m.box((8, 9, 7.4), (17, 10.6, 8.6), 0)                   # 翼骨
    m.box((16.4, 10, 7.5), (24, 11.6, 8.5), 0)
    m.box((23.4, 10.6, 7.6), (28, 12, 8.4), 1)
    for i, x in enumerate(range(9, 29, 2)):
        top = 9.5 + min(i, 6) * 0.25
        length = 5 + i * 1.15
        m.box((x, top - length, 7.7 + (i % 2) * 0.1), (x + 2.1, top, 8.3 + (i % 2) * 0.1), 2)       # 長羽毛
        m.box((x + 0.3, top - length - 0.8, 7.8), (x + 1.8, top - length, 8.2), 3)                 # 羽尖（淡金）
    for i, x in enumerate(range(9, 27, 3)):
        m.box((x, 7.5 + i * 0.3, 8.3), (x + 3, 10.4 + i * 0.3, 8.8), 4)                            # 覆羽
    m.box((27.6, 11.2, 7.7), (29, 12.6, 8.3), 5, glow=True)  # 翼尖的星光


def demon(m):
    bone = 0
    m.box((8, 9, 7.4), (16, 10.6, 8.6), bone)                # 上臂
    m.box((15.4, 10, 7.5), (21, 11.6, 8.5), bone)
    m.box((20.6, 10.4, 7.5), (22.2, 14.6, 8.5), bone)        # 指骨往上
    m.box((21.6, 14.0, 7.6), (22.8, 15.6, 8.4), 3)           # 爪
    for (x0, x1, y1) in ((14, 15.4, -3), (17.4, 18.8, -5), (20.8, 22.2, -6)):
        m.box((x0, y1, 7.6), (x1, 10.4, 8.4), bone)          # 往下的指骨
        m.box((x0 + 0.2, y1 - 1.2, 7.7), (x1 - 0.2, y1, 8.3), 3)
    m.box((8.6, -2, 7.9), (14, 9.2, 8.1), 1)                 # 翼膜
    m.box((15.4, -4.2, 7.9), (17.4, 10, 8.1), 1)
    m.box((18.8, -5.2, 7.9), (20.8, 10.2, 8.1), 1)
    m.box((14, -2.8, 7.9), (15.4, 9.8, 8.1), 2)
    m.box((17.4, -4.6, 7.9), (18.8, 10, 8.1), 2)
    m.box((22.2, -3.0, 7.9), (23.4, 13.6, 8.1), 2)
    m.box((9, 0, 7.85), (13.6, 1, 8.15), 4, glow=True)       # 膜上的血紋
    m.box((15.6, -1.5, 7.85), (20.6, -0.6, 8.15), 4, glow=True)


def crystal(m):
    m.box((8, 9.2, 7.5), (13, 10.4, 8.5), 0)                 # 晶核
    shards = [((12, 9, 7.4), (22, 11, 8.6), 22.5, 1), ((11, 6, 7.5), (20, 7.8, 8.5), 0, 2),
              ((11, 3, 7.6), (18, 4.6, 8.4), -22.5, 1), ((13, 12, 7.5), (24, 13.8, 8.5), 45, 2),
              ((10, 0, 7.6), (15, 1.4, 8.4), -45, 3)]
    for a, b, ang, c in shards:
        origin = (a[0], (a[1] + b[1]) / 2, 8)
        m.box(a, b, c, rot=(ang, "z", origin) if ang else None)
        tip_a = (b[0], a[1] + 0.3, a[2] + 0.2)
        tip_b = (b[0] + 2.2, b[1] - 0.3, b[2] - 0.2)
        m.box(tip_a, tip_b, 4, rot=(ang, "z", origin) if ang else None, glow=True)


def halo_back():
    m = Model("halo_back", ["#c9a43a", "#ffe08a", "#fff6d0", "#ffffff",
                            "#5fe0ff", None, None, None,
                            None, None, None, None, None, None, None, None], "back")
    m.materials = {}
    r = 9.0
    for i in range(8):
        ang = i * 45
        cx = 8 + r * math.cos(math.radians(ang))
        cy = 8 + r * math.sin(math.radians(ang))
        seg = 7.4
        if ang % 90 == 0:
            if ang in (0, 180):
                m.box((cx - 0.6, cy - seg / 2, 7.4), (cx + 0.6, cy + seg / 2, 8.6), 1, glow=True)
            else:
                m.box((cx - seg / 2, cy - 0.6, 7.4), (cx + seg / 2, cy + 0.6, 8.6), 1, glow=True)
        else:
            m.box((cx - seg / 2, cy - 0.6, 7.4), (cx + seg / 2, cy + 0.6, 8.6), 1,
                  rot=(-45 if ang in (45, 225) else 45, "z", (cx, cy, 8)), glow=True)
    for i in range(4):
        ang = i * 90 + 45
        cx = 8 + 12.2 * math.cos(math.radians(ang))
        cy = 8 + 12.2 * math.sin(math.radians(ang))
        m.box((cx - 0.9, cy - 0.9, 7.6), (cx + 0.9, cy + 0.9, 8.4), 2, rot=(45, "z", (cx, cy, 8)), glow=True)
    m.box((7, 7, 7.5), (9, 9, 8.5), 4, rot=(45, "z", (8, 8, 8)), glow=True)
    return m


# ======================================================================
# 寵物（中心在 8,8,8；臉朝 north）
# ======================================================================

def pet_wisp():
    m = Model("pet_wisp", ["#5fe0ff", "#d8fbff", "#ffffff", "#7fd8ff",
                           "#c9a43a", "#ffe08a", None, None,
                           None, None, None, None, None, None, None, None], "pet")
    m.materials = {4: 'metal', 5: 'metal'}
    m.box((6, 6, 6), (10, 10, 10), 1, glow=True)             # 核
    m.box((5.4, 6.6, 6.6), (10.6, 9.4, 9.4), 0, glow=True)
    m.box((6.6, 5.4, 6.6), (9.4, 10.6, 9.4), 0, glow=True)
    m.box((6.6, 6.6, 5.4), (9.4, 9.4, 10.6), 0, glow=True)
    m.box((6.8, 8.2, 5.2), (7.6, 9.0, 5.4), 4)               # 眼睛
    m.box((8.4, 8.2, 5.2), (9.2, 9.0, 5.4), 4)
    ring(m, 7.7, 8.3, 2.0, 0.6, 5)                           # 金環
    for (x, y, z) in ((3, 12, 8), (13, 4, 7), (8, 13.5, 12), (4, 4, 4)):
        m.box((x - 0.4, y - 0.4, z - 0.4), (x + 0.4, y + 0.4, z + 0.4), 2, glow=True)
    return m


def pet_dragon():
    m = Model("pet_dragon", ["#8a1e10", "#d84a1a", "#ff7a1a", "#ffe08a",
                             "#3a0e0a", "#ffc24a", "#1a0a08", "#f4e0c0",
                             None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'scale', 1: 'scale', 2: 'scale', 4: 'stone', 5: 'stone', 6: 'plain', 7: 'plain'}
    m.box((5, 5, 6), (11, 10, 12.5), 1)                      # 身體
    m.box((6, 4.6, 6.6), (10, 5, 12), 7)                     # 肚子
    m.box((5.5, 8, 1.6), (10.5, 12.4, 6.2), 1)               # 頭
    m.box((6.4, 8, 0.0), (9.6, 10.6, 1.6), 1)                # 嘴
    m.box((6.8, 10.0, -0.1), (7.4, 10.4, 0.1), 6)            # 鼻孔
    m.box((8.6, 10.0, -0.1), (9.2, 10.4, 0.1), 6)
    m.box((5.9, 10.8, 1.4), (7.3, 11.8, 1.6), 3, glow=True)  # 眼睛
    m.box((8.7, 10.8, 1.4), (10.1, 11.8, 1.6), 3, glow=True)
    m.box((6.0, 12.4, 4.4), (7.0, 14.6, 5.4), 4)             # 角
    m.box((9.0, 12.4, 4.4), (10.0, 14.6, 5.4), 4)
    m.box((7.4, 10.2, 6.0), (8.6, 11.2, 12.2), 2)            # 背鰭
    m.box((11, 9.4, 7.0), (15.6, 10.2, 11.4), 2)             # 翅膀
    m.box((0.4, 9.4, 7.0), (5, 10.2, 11.4), 2)
    m.box((14.6, 10.2, 7.4), (15.6, 11.6, 8.4), 0)
    m.box((0.4, 10.2, 7.4), (1.4, 11.6, 8.4), 0)
    m.box((7, 6, 12.5), (9, 8, 15.5), 1)                     # 尾巴
    m.box((7.4, 6.4, 15.5), (8.6, 7.6, 18.0), 1)
    m.box((7.2, 6.2, 18.0), (8.8, 7.8, 19.4), 2, glow=True)  # 尾巴的火
    for (x, z) in ((5.4, 6.8), (9.2, 6.8), (5.4, 10.4), (9.2, 10.4)):
        m.box((x, 2.8, z), (x + 1.4, 5, z + 1.6), 0)         # 腳
    return m


def pet_owl():
    m = Model("pet_owl", ["#cfefff", "#eaffff", "#8ab4d8", "#ffe08a",
                          "#1a1a24", "#ff9a3a", "#ffffff", "#5fa8e0",
                          None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'feather', 1: 'feather', 2: 'feather', 4: 'plain', 5: 'plain', 6: 'feather', 7: 'feather'}
    m.box((4.6, 3, 5), (11.4, 10.6, 11), 1)                  # 身體
    m.box((5.4, 3.4, 4.6), (10.6, 9, 5), 6)                  # 胸口
    m.box((4.2, 10.4, 4.6), (11.8, 15, 11.4), 1)             # 頭
    m.box((5.0, 11.4, 4.2), (7.6, 14.0, 4.6), 0)             # 臉盤
    m.box((8.4, 11.4, 4.2), (11.0, 14.0, 4.6), 0)
    m.box((5.5, 11.9, 4.0), (7.1, 13.5, 4.2), 3, glow=True)  # 眼睛
    m.box((8.9, 11.9, 4.0), (10.5, 13.5, 4.2), 3, glow=True)
    m.box((6.0, 12.4, 3.9), (6.6, 13.0, 4.0), 4)
    m.box((9.4, 12.4, 3.9), (10.0, 13.0, 4.0), 4)
    m.box((7.4, 10.6, 3.6), (8.6, 12.0, 4.6), 5)             # 嘴
    m.box((4.2, 15, 6), (5.6, 17.2, 7.4), 2)                 # 耳羽
    m.box((10.4, 15, 6), (11.8, 17.2, 7.4), 2)
    m.box((3.6, 4.2, 5.8), (4.6, 10, 10.6), 2)               # 翅膀
    m.box((11.4, 4.2, 5.8), (12.4, 10, 10.6), 2)
    m.box((5.4, 4, 11), (10.6, 5.6, 12.4), 7)                # 尾羽
    m.box((5.6, 2, 5.6), (7, 3, 7.6), 5)                     # 腳
    m.box((9, 2, 5.6), (10.4, 3, 7.6), 5)
    return m


def pet_slime():
    m = Model("pet_slime", [("#3affa0", 150), "#1f6a46", "#4fc08a", "#0e2a1e",
                            "#d8ffe8", "#ffffff", None, None,
                            None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'glass', 3: 'plain', 4: 'plain', 5: 'plain'}
    m.box((6, 4.4, 6), (10, 8.4, 10), 2, glow=True)          # 核
    m.box((6.2, 8.6, 3.4), (7.4, 9.8, 3.6), 3)               # 眼睛
    m.box((8.6, 8.6, 3.4), (9.8, 9.8, 3.6), 3)
    m.box((6.6, 9.2, 3.35), (7.0, 9.6, 3.4), 5)
    m.box((9.0, 9.2, 3.35), (9.4, 9.6, 3.4), 5)
    m.box((7.2, 6.6, 3.4), (8.8, 7.2, 3.6), 3)               # 嘴
    m.box((3.6, 3, 3.6), (12.4, 11.6, 12.4), 0)              # 半透明的外殼（最後畫）
    return m


def pet_golem():
    m = Model("pet_golem", ["#4a5a74", "#7a8ca8", "#a8b8d0", "#2a3240",
                            "#6fa8ff", "#d0e4ff", "#3a3a3a", "#8a8a8a",
                            None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'stone', 1: 'stone', 2: 'stone', 3: 'stone', 6: 'stone', 7: 'stone'}
    m.box((5, 4.4, 6), (11, 11, 10.4), 1)                    # 身體
    m.box((6.6, 6.6, 5.6), (9.4, 9.4, 6), 4, glow=True)      # 胸口的符文
    m.box((5.6, 11, 5.6), (10.4, 15, 9.8), 2)                # 頭
    m.box((6.2, 12.6, 5.3), (7.4, 13.4, 5.6), 4, glow=True)  # 眼睛
    m.box((8.6, 12.6, 5.3), (9.8, 13.4, 5.6), 4, glow=True)
    m.box((5.4, 14.4, 5.4), (10.6, 15.4, 10), 3)             # 頭盔邊
    m.box((7.6, 15.4, 7), (8.4, 17, 8.6), 4, glow=True)      # 頭頂的燈
    m.box((2.8, 3.6, 7), (5, 11, 9.4), 0)                    # 手臂（長長的）
    m.box((11, 3.6, 7), (13.2, 11, 9.4), 0)
    m.box((2.6, 2.8, 6.8), (5.2, 4, 9.6), 7)                 # 拳頭
    m.box((10.8, 2.8, 6.8), (13.4, 4, 9.6), 7)
    m.box((5.6, 1, 6.6), (7.6, 4.4, 9.6), 3)                 # 腿
    m.box((8.4, 1, 6.6), (10.4, 4.4, 9.6), 3)
    return m


def pet_tome():
    m = Model("pet_tome", ["#3a2058", "#5c3488", "#e8dcc0", "#d24bff",
                           "#ffb0ff", "#c9a43a", "#fff6e0", None,
                           None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'leather', 1: 'leather', 2: 'plain', 5: 'metal', 6: 'plain'}
    # 翻開的書：左右兩半各斜 22.5 度
    m.box((1.6, 6.4, 3.6), (8, 7.2, 12.4), 1, rot=(22.5, "z", (8, 6.8, 8)))   # 左封面
    m.box((2.0, 7.2, 4.0), (8, 8.2, 12.0), 2, rot=(22.5, "z", (8, 6.8, 8)))   # 左頁
    m.box((8, 6.4, 3.6), (14.4, 7.2, 12.4), 1, rot=(-22.5, "z", (8, 6.8, 8)))  # 右封面
    m.box((8, 7.2, 4.0), (14.0, 8.2, 12.0), 6, rot=(-22.5, "z", (8, 6.8, 8)))  # 右頁
    m.box((7.4, 6.0, 3.6), (8.6, 7.4, 12.4), 0)              # 書背
    m.box((7.0, 10.4, 7.0), (9.0, 12.4, 9.0), 3, rot=(45, "y", (8, 11.4, 8)), glow=True)  # 飄著的符文
    m.box((7.5, 12.8, 7.5), (8.5, 13.8, 8.5), 4, glow=True)
    m.box((4.0, 9.6, 6.0), (4.8, 10.4, 6.8), 4, glow=True)
    m.box((11.2, 9.2, 9.4), (12.0, 10.0, 10.2), 4, glow=True)
    return m


def pet_fox():
    m = Model("pet_fox", ["#d86a1a", "#ff9a3a", "#ffffff", "#2a1410",
                          "#5fe0ff", "#a8481a", "#ffd0a0", None,
                          None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'fur', 1: 'fur', 2: 'fur', 3: 'plain', 5: 'fur', 6: 'fur'}
    m.box((5.2, 4.4, 5.6), (10.8, 9, 12.4), 1)               # 身體
    m.box((6, 4.0, 6), (10, 4.4, 11.6), 6)                   # 肚子
    m.box((5, 7, 1.8), (11, 11.6, 6.2), 1)                   # 頭
    m.box((6.4, 7, 0.2), (9.6, 9.4, 1.8), 2)                 # 嘴（白）
    m.box((7.4, 8.6, 0.0), (8.6, 9.4, 0.2), 3)               # 鼻子
    m.box((5.8, 9.6, 1.6), (7.2, 10.6, 1.8), 4, glow=True)   # 靈光的眼睛
    m.box((8.8, 9.6, 1.6), (10.2, 10.6, 1.8), 4, glow=True)
    m.box((5, 11.6, 3), (6.8, 14, 4.6), 5)                   # 耳朵
    m.box((9.2, 11.6, 3), (11, 14, 4.6), 5)
    m.box((5.5, 11.6, 3.4), (6.3, 13.2, 3.0), 3)
    m.box((6, 6.2, 12.4), (10, 10, 15.6), 1)                 # 大尾巴
    m.box((6.4, 7, 15.6), (9.6, 10.4, 18.4), 1)
    m.box((6.8, 7.6, 18.4), (9.2, 10.2, 20.4), 2)            # 白尾巴尖
    for (x, z) in ((5.6, 6.2), (9.0, 6.2), (5.6, 10.2), (9.0, 10.2)):
        m.box((x, 2.4, z), (x + 1.4, 4.4, z + 1.4), 3)       # 黑襪子
    return m



# ======================================================================
# 弓、弩（2026-10）：直的做（弓：弓身直立、往 +x 射；弩：弩身往 +x），最後斜 45 度對上原版的圖。拉弓三段、弩上弦三段＋上好弦
# ======================================================================

BOW_DISPLAY = {
    "thirdperson_righthand": {"rotation": [-80, 260, -40], "translation": [-1, -2, 2.5], "scale": [0.75, 0.75, 0.75]},
    "thirdperson_lefthand": {"rotation": [-80, -280, 40], "translation": [-1, -2, 2.5], "scale": [0.75, 0.75, 0.75]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.58, 0.58, 0.58]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.58, 0.58, 0.58]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.45, 0.45, 0.45]},
    "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.8, 0.8, 0.8]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}
CROSSBOW_DISPLAY = {
    "thirdperson_righthand": {"rotation": [-90, 0, -60], "translation": [2, 0.1, -3], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_lefthand": {"rotation": [-90, 0, 30], "translation": [2, 0.1, -3], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [-90, 0, -55], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [-90, 0, 35], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}
DISPLAY["bow"] = BOW_DISPLAY
DISPLAY["crossbow"] = CROSSBOW_DISPLAY

# 色票的位置：0 弓身暗、1 弓身、2 弓身亮、3 握把、4 弦、5 點綴（發光）、6 箭桿、7 箭頭（發光）、8 金屬、9 寶石（發光）
BOW_MATS = {0: "wood", 1: "wood", 2: "wood", 3: "leather", 4: "cloth", 6: "wood", 7: "metal", 8: "metal", 9: "crystal"}
# 弩：0 托、1 弩身（木）、2 弩臂（金屬）、4 弦、5 點綴（發光）、6 弩箭、7 箭頭、8 金屬件、9 寶石
CROSSBOW_MATS = {0: "wood", 1: "wood", 2: "metal", 4: "cloth", 6: "wood", 7: "metal", 8: "metal", 9: "crystal"}


def stair(m, a, b, c, steps=8, w=0.3):
    """斜線（不能用轉的時候）：用一階一階的小方塊排出來。a、b 是 (x, y)。"""
    for i in range(steps):
        t0, t1 = i / steps, (i + 1) / steps
        x0, y0 = a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0
        x1, y1 = a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1
        m.box((min(x0, x1) - w / 2, min(y0, y1) - w / 4, 7.9), (max(x0, x1) + w / 2, max(y0, y1) + w / 4, 8.1), c)


def bow_model(art, colors, pull, extras, materials=None):
    """pull：None = 沒拉；0、1、2 = 拉弓的三段。extras(m, flex)：這把弓自己的裝飾。materials：蓋過 BOW_MATS 的材質。"""
    name = art if pull is None else f"{art}_pulling_{pull}"
    m = Model(name, colors, "bow", {**BOW_MATS, **(materials or {})})
    flex = 0 if pull is None else (0.4, 0.8, 1.2)[pull]   # 拉越開，弓臂往後彎越多
    m.box((7.2, 6.4, 7.3), (9.4, 9.6, 8.7), 3)              # 握把
    for sign in (1, -1):
        def Y(y):
            return 8 + sign * (y - 8)
        segs = [((7.8, 9.6), (9.4, 12.0), 1), ((7.0 - flex * 0.3, 12.0), (8.6 - flex * 0.3, 14.4), 1),
                ((6.1 - flex * 0.7, 14.4), (7.7 - flex * 0.7, 16.6), 0), ((5.4 - flex, 16.6), (6.7 - flex, 18.2), 5)]
        for (x0, y0), (x1, y1), c in segs:
            m.box((x0, Y(y0), 7.5), (x1, Y(y1), 8.5), c, glow=(c == 5))
    tip = 5.6 - flex
    if pull is None or pull < 2:
        sx = tip - (0 if pull is None else (1.0, 2.0)[pull])
        m.box((sx - 0.15, -1.6, 7.9), (sx + 0.15, 17.6, 8.1), 4)       # 弦（直的）
        nock = sx
    else:
        # 拉滿：上下兩段斜斜的，在中間碰在一起
        nock = tip - 3.9
        stair(m, (tip, 17.6), (nock, 8.0), 4)
        stair(m, (nock, 8.0), (tip, -1.6), 4)
    if pull is not None:
        m.box((nock, 7.75, 7.75), (nock + 11.5, 8.25, 8.25), 6)        # 箭
        m.box((nock + 11.5, 7.35, 7.6), (nock + 13.2, 8.65, 8.4), 7, glow=True)
        m.box((nock + 0.2, 7.1, 7.85), (nock + 2.0, 8.9, 8.15), 2)     # 箭羽
    extras(m, flex)
    m.flip_x()
    m.tilt(-45)
    return m


def bow_set(art, colors, extras, materials=None):
    models = [bow_model(art, colors, None, extras, materials)] + [bow_model(art, colors, i, extras, materials) for i in range(3)]
    ref = lambda n: {"type": "minecraft:model", "model": f"{NS}:item/skin3d/{n}"}
    definition = {"type": "minecraft:condition", "property": "minecraft:using_item",
                  "on_false": ref(art),
                  "on_true": {"type": "minecraft:range_dispatch", "property": "minecraft:use_duration", "scale": 0.05,
                              "entries": [{"threshold": 0.65, "model": ref(art + "_pulling_1")},
                                          {"threshold": 0.9, "model": ref(art + "_pulling_2")}],
                              "fallback": ref(art + "_pulling_0")}}
    return models, {"bow": definition}


def star_longbow_extras(m, flex):
    m.box((8.6, 7.3, 6.9), (9.8, 8.7, 9.1), 9, glow=True)                  # 握把上的星石
    for sign in (1, -1):
        y = 8 + sign * 5.2
        m.box((9.2 - flex * 0.2, y - 0.6, 7.6), (10.6 - flex * 0.2, y + 0.6, 8.4), 8)   # 弓臂上的金飾
        m.box((10.6 - flex * 0.2, y - 0.3, 7.7), (11.4 - flex * 0.2, y + 0.3, 8.3), 9, glow=True)


def verdant_bow_extras(m, flex):
    m.box((9.4, 10.6, 7.8), (11.0, 11.8, 8.2), 8)                            # 葉子
    m.box((9.4, 4.2, 7.8), (11.0, 5.4, 8.2), 8)
    m.box((11.0, 11.0, 7.85), (11.8, 11.5, 8.15), 9)
    m.box((8.8, 7.5, 7.0), (9.6, 8.5, 9.0), 5, glow=True)


def blood_bow_extras(m, flex):
    for sign in (1, -1):
        y = 8 + sign * 9.6
        m.box((4.6 - flex, y - 0.4, 7.7), (5.6 - flex, y + 0.4, 8.3), 8)    # 弓尖的倒鉤
    m.box((8.8, 6.6, 6.9), (9.8, 9.4, 9.1), 9, glow=True)                  # 血晶
    m.box((9.8, 7.4, 7.6), (10.8, 8.6, 8.4), 5, glow=True)


def crossbow_model(art, colors, state, extras, materials=None):
    """state：None 沒上弦、0／1／2 上弦中、'arrow' 上好弦（有箭）。materials：蓋過 CROSSBOW_MATS 的材質。"""
    name = art if state is None else (f"{art}_arrow" if state == "arrow" else f"{art}_pulling_{state}")
    m = Model(name, colors, "crossbow", {**CROSSBOW_MATS, **(materials or {})})
    m.box((1.5, 6.8, 7.2), (12.2, 8.6, 8.8), 1)              # 弩身
    m.box((0.0, 5.4, 7.3), (3.0, 8.6, 8.7), 0)               # 托
    m.box((5.0, 5.4, 7.6), (6.0, 6.8, 8.4), 8)               # 扳機
    m.box((4.6, 8.6, 7.6), (13.0, 9.1, 8.4), 8)              # 箭槽
    m.box((12.0, 1.6, 7.4), (13.6, 14.4, 8.6), 2)            # 弩臂
    m.box((11.2, 0.4, 7.5), (12.6, 2.0, 8.5), 5, glow=True)  # 弩臂兩端
    m.box((11.2, 14.0, 7.5), (12.6, 15.6, 8.5), 5, glow=True)
    m.box((12.2, 6.6, 7.0), (13.8, 9.4, 9.0), 8)             # 弩臂中間的座
    if state is None or state in (0, 1):
        sx = 11.4 - (0 if state is None else (1.4, 2.8)[state])
        m.box((sx - 0.15, 1.0, 7.9), (sx + 0.15, 15.0, 8.1), 4)
    else:
        # 拉滿：弦從兩端斜斜拉到後面的弦扣
        tip_x = 11.4
        nut = tip_x - 6.6
        stair(m, (tip_x, 15.0), (nut, 8.0), 4)
        stair(m, (nut, 8.0), (tip_x, 1.0), 4)
        m.box((nut - 0.6, 7.4, 7.5), (nut + 0.4, 8.8, 8.5), 8)   # 弦扣
    if state == "arrow":
        m.box((5.0, 9.1, 7.75), (14.4, 9.6, 8.25), 6)            # 弩箭
        m.box((14.4, 8.8, 7.6), (15.8, 9.9, 8.4), 7, glow=True)
    extras(m)
    m.flip_x()
    m.tilt(-45)
    return m


def crossbow_set(art, colors, extras, materials=None):
    models = [crossbow_model(art, colors, s, extras, materials) for s in (None, 0, 1, 2, "arrow")]
    ref = lambda n: {"type": "minecraft:model", "model": f"{NS}:item/skin3d/{n}"}
    definition = {"type": "minecraft:select", "property": "minecraft:charge_type",
                  "cases": [{"when": "arrow", "model": ref(art + "_arrow")}, {"when": "rocket", "model": ref(art + "_arrow")}],
                  "fallback": {"type": "minecraft:condition", "property": "minecraft:using_item",
                               "on_false": ref(art),
                               "on_true": {"type": "minecraft:range_dispatch", "property": "minecraft:crossbow/pull",
                                           "entries": [{"threshold": 0.58, "model": ref(art + "_pulling_1")},
                                                       {"threshold": 1.0, "model": ref(art + "_pulling_2")}],
                                           "fallback": ref(art + "_pulling_0")}}}
    return models, {"crossbow": definition}


def inferno_extras(m):
    m.box((6.6, 6.6, 6.8), (9.4, 8.8, 9.2), 9, glow=True)      # 熔岩核心
    m.box((1.0, 8.6, 7.6), (3.0, 9.4, 8.4), 5, glow=True)


def frost_extras(m):
    m.box((13.6, 7.2, 7.2), (15.0, 8.8, 8.8), 9, glow=True)    # 冰晶
    m.box((12.4, 15.6, 7.7), (13.2, 17.0, 8.3), 9, glow=True)
    m.box((12.4, -1.0, 7.7), (13.2, 0.4, 8.3), 9, glow=True)


def iron_extras(m):
    m.box((2.0, 5.0, 7.1), (3.4, 9.0, 8.9), 8)                 # 鐵箍
    m.box((8.0, 6.6, 7.1), (9.0, 8.8, 8.9), 8)


# ======================================================================
# 3D 戰甲（2026-10）：頭盔是 3D 模型、肩甲是兩個跟在肩膀上的 3D 模型；身、腿、腳用那一套自己畫的戰甲貼圖
# ======================================================================

def helm_shell(m, metal, trim, open_face=True):
    m.box((1.2, 14.4, 1.2), (14.8, 15.6, 14.8), metal)         # 頂
    m.box((1.2, 5.0, 13.8), (14.8, 14.4, 14.8), metal)         # 後
    m.box((1.2, 5.0, 1.2), (2.2, 14.4, 13.8), metal)           # 左右
    m.box((13.8, 5.0, 1.2), (14.8, 14.4, 13.8), metal)
    m.box((2.2, 11.6, 1.0), (13.8, 14.4, 2.2), metal)          # 額
    m.box((1.0, 13.8, 0.8), (15.0, 14.6, 2.4), trim)           # 額上的飾邊
    if open_face:
        m.box((2.2, 5.0, 1.0), (5.0, 11.6, 2.2), metal)        # 護頰
        m.box((11.0, 5.0, 1.0), (13.8, 11.6, 2.2), metal)
        m.box((7.3, 6.6, 0.6), (8.7, 11.6, 1.2), trim)         # 護鼻
    else:
        m.box((2.2, 5.0, 1.0), (13.8, 11.6, 2.2), metal)       # 整面
    m.box((7.0, 15.6, 3.0), (9.0, 16.4, 14.0), trim)           # 頂上的脊


def armor_ironguard_helm(colors, materials=None):
    m = Model("armor3d_ironguard_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2, open_face=False)
    m.box((3.0, 9.0, 0.7), (13.0, 9.8, 1.0), 6)                # 眼縫
    for x in (5.0, 7.4, 9.8):
        m.box((x, 6.0, 0.7), (x + 1.0, 8.0, 1.0), 6)           # 透氣孔
    m.box((7.4, 16.4, 5.0), (8.6, 20.0, 12.0), 5)              # 羽飾
    m.box((7.6, 18.0, 12.0), (8.4, 19.4, 14.6), 5)
    return m


def armor_frost_helm(colors, materials=None):
    m = Model("armor3d_frost_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2)
    for (x, h) in ((3.0, 2.6), (6.0, 4.2), (9.0, 4.2), (12.0, 2.6)):
        m.box((x, 15.6, 1.4), (x + 1.2, 15.6 + h, 2.6), 5, glow=True)    # 冰晶冠
    m.box((7.2, 12.0, 0.6), (8.8, 13.6, 1.0), 5, glow=True)
    m.box((0.2, 10.0, 6.0), (1.2, 13.0, 9.0), 5, glow=True)              # 兩側的冰晶
    m.box((14.8, 10.0, 6.0), (15.8, 13.0, 9.0), 5, glow=True)
    return m


def armor_inferno_helm(colors, materials=None):
    m = Model("armor3d_inferno_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2)
    m.box((0.2, 11.0, 6.0), (1.2, 13.0, 9.0), 0)               # 角
    m.box((-1.0, 12.6, 6.4), (0.4, 15.4, 8.6), 0)
    m.box((-1.4, 15.0, 6.8), (-0.2, 17.4, 8.2), 5, glow=True)
    m.box((14.8, 11.0, 6.0), (15.8, 13.0, 9.0), 0)
    m.box((15.6, 12.6, 6.4), (17.0, 15.4, 8.6), 0)
    m.box((16.2, 15.0, 6.8), (17.4, 17.4, 8.2), 5, glow=True)
    m.box((6.6, 16.4, 4.0), (9.4, 18.0, 12.0), 5, glow=True)   # 火焰冠
    m.box((7.3, 18.0, 6.0), (8.7, 19.6, 10.0), 9, glow=True)
    m.box((3.0, 9.0, 0.7), (5.0, 9.8, 1.0), 9, glow=True)      # 發光的眼
    m.box((11.0, 9.0, 0.7), (13.0, 9.8, 1.0), 9, glow=True)
    return m


def armor_starlight_helm(colors, materials=None):
    m = Model("armor3d_starlight_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2)
    for sign in (1, -1):
        def X(x):
            return 8 + sign * (x - 8)
        m.box((X(14.8), 10.0, 7.0), (X(16.0), 13.0, 9.0), 2)  # 側翼
        m.box((X(16.0), 11.0, 7.4), (X(17.6), 15.0, 8.6), 5)
        m.box((X(17.6), 12.6, 7.6), (X(18.8), 17.2, 8.4), 5)
        m.box((X(18.8), 14.6, 7.7), (X(19.6), 18.4, 8.3), 9, glow=True)
    m.box((6.8, 12.0, 0.4), (9.2, 14.0, 1.0), 9, glow=True)   # 額上的星石
    m.box((7.4, 17.4, 7.4), (8.6, 18.6, 8.6), 9, glow=True)   # 頭頂飄著的星
    return m


def armor_void_helm(colors, materials=None):
    m = Model("armor3d_void_helm", colors, "headgear", materials)
    helm_shell(m, 1, 0, open_face=False)
    m.box((3.0, 8.4, 0.7), (6.4, 10.2, 1.0), 6)                # 眼窩
    m.box((9.6, 8.4, 0.7), (13.0, 10.2, 1.0), 6)
    m.box((3.8, 8.9, 0.5), (5.6, 9.7, 0.7), 9, glow=True)      # 眼
    m.box((10.4, 8.9, 0.5), (12.2, 9.7, 0.7), 9, glow=True)
    for (x, h) in ((2.0, 3.0), (4.6, 4.6), (7.2, 6.0), (9.6, 4.6), (12.2, 3.0)):
        m.box((x, 15.6, 1.2), (x + 1.6, 15.6 + h, 2.6), 0)    # 虛空之冠
    m.box((7.4, 21.0, 1.4), (8.6, 22.2, 2.4), 9, glow=True)
    return m


def armor_verdant_helm(colors, materials=None):
    m = Model("armor3d_verdant_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2)
    for sign in (1, -1):                                       # 兩側長出來的葉片
        def X(x):
            return 8 + sign * (x - 8)
        m.box((X(14.8), 11.0, 6.6), (X(16.2), 12.4, 9.4), 5)
        m.box((X(16.0), 12.0, 7.0), (X(17.4), 14.6, 9.0), 5)
        m.box((X(17.0), 14.0, 7.4), (X(18.0), 16.0, 8.6), 9, glow=True)
    m.box((6.6, 15.6, 2.0), (9.4, 17.0, 4.0), 5)               # 額上的嫩芽
    m.box((7.4, 17.0, 2.4), (8.6, 18.4, 3.6), 9, glow=True)
    return m


def armor_bloodmoon_helm(colors, materials=None):
    m = Model("armor3d_bloodmoon_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2, open_face=False)
    m.box((3.0, 9.0, 0.7), (13.0, 9.8, 1.0), 9, glow=True)     # 血紅的眼縫
    m.box((5.0, 15.6, 6.0), (11.0, 16.6, 10.0), 3)             # 新月形的冠
    m.box((3.6, 16.2, 7.0), (5.0, 18.4, 9.0), 3)
    m.box((11.0, 16.2, 7.0), (12.4, 18.4, 9.0), 3)
    m.box((2.8, 18.0, 7.4), (3.8, 20.0, 8.6), 9, glow=True)
    m.box((12.2, 18.0, 7.4), (13.2, 20.0, 8.6), 9, glow=True)
    return m


def armor_sakura_helm(colors, materials=None):
    m = Model("armor3d_sakura_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2)
    for (x, z) in ((3.0, 3.0), (12.0, 4.0)):                   # 髮簪上的花
        m.box((x - 1.2, 15.6, z - 1.2), (x + 1.2, 16.6, z + 1.2), 5)
        m.box((x - 0.5, 16.6, z - 0.5), (x + 0.5, 17.2, z + 0.5), 9, glow=True)
    m.box((13.6, 14.0, 8.0), (14.4, 15.0, 13.0), 2)            # 簪子
    m.box((7.2, 12.2, 0.6), (8.8, 13.6, 1.0), 9, glow=True)
    return m


def armor_solar_helm(colors, materials=None):
    m = Model("armor3d_solar_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2)
    for (x, h) in ((2.4, 2.4), (4.8, 3.6), (7.2, 4.8), (9.6, 3.6), (12.0, 2.4)):
        m.box((x, 15.6, 1.0), (x + 1.4, 15.6 + h, 2.2), 5, glow=True)   # 日冕
    m.box((6.8, 12.0, 0.4), (9.2, 14.0, 1.0), 9, glow=True)
    return m


def armor_storm_helm(colors, materials=None):
    m = Model("armor3d_storm_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2, open_face=False)
    m.box((3.0, 9.0, 0.7), (13.0, 9.8, 1.0), 9, glow=True)     # 電光眼縫
    for sign in (1, -1):                                       # 兩側的雷角（折線）
        def X(x):
            return 8 + sign * (x - 8)
        m.box((X(14.8), 11.0, 7.2), (X(16.2), 12.2, 8.8), 2)
        m.box((X(15.6), 12.2, 7.4), (X(16.6), 14.4, 8.6), 5, glow=True)
        m.box((X(16.6), 14.0, 7.5), (X(17.8), 15.0, 8.5), 5, glow=True)
        m.box((X(17.2), 15.0, 7.6), (X(18.0), 17.0, 8.4), 9, glow=True)
    return m


def armor_shadow_helm(colors, materials=None):
    m = Model("armor3d_shadow_helm", colors, "headgear", materials)
    m.box((1.0, 15.0, 1.0), (15.0, 16.0, 15.0), 1)             # 兜帽（前面開口）
    m.box((1.0, 4.0, 13.8), (15.0, 15.0, 15.0), 1)
    m.box((1.0, 4.0, 1.0), (2.2, 15.0, 13.8), 1)
    m.box((13.8, 4.0, 1.0), (15.0, 15.0, 13.8), 1)
    m.box((2.2, 12.6, 0.8), (13.8, 15.0, 2.0), 1)              # 帽簷
    m.box((2.2, 4.0, 0.6), (13.8, 6.6, 1.4), 0)                # 遮住口鼻的布
    m.box((3.0, 15.6, 3.0), (13.0, 16.8, 13.0), 1)
    m.box((5.0, 16.8, 8.0), (11.0, 17.6, 15.0), 0)             # 帽尖往後垂
    m.box((6.0, 17.0, 14.0), (10.0, 18.0, 16.6), 0)
    m.box((4.2, 9.0, 0.6), (6.4, 9.8, 1.0), 9, glow=True)      # 兜帽裡發光的眼
    m.box((9.6, 9.0, 0.6), (11.8, 9.8, 1.0), 9, glow=True)
    return m


def pauldron(name, colors, style, materials=None):
    """右肩（往 +x 是外側）；轉軸在 8,8,8。"""
    m = Model(name, colors, "back", materials)
    m.box((4.6, 8.4, 4.6), (11.4, 10.2, 11.4), 1)              # 肩甲殼
    m.box((5.2, 10.2, 5.2), (10.8, 11.0, 10.8), 2)
    m.box((4.2, 6.4, 4.4), (11.8, 8.4, 11.6), 1)               # 往下蓋的一片
    m.box((4.0, 6.0, 4.2), (12.0, 6.6, 11.8), 2)               # 飾邊
    if style == "spike":
        m.box((10.6, 9.4, 7.2), (12.4, 10.8, 8.8), 0)
        m.box((12.4, 9.8, 7.5), (14.0, 10.6, 8.5), 5, glow=True)
    elif style == "crystal":
        m.box((8.6, 11.0, 7.0), (10.0, 13.4, 8.6), 5, glow=True)
        m.box((10.2, 10.6, 7.4), (11.2, 12.4, 8.4), 5, glow=True)
    elif style == "wing":
        m.box((10.2, 10.0, 7.4), (12.4, 11.2, 8.6), 5)
        m.box((11.6, 11.0, 7.6), (13.2, 12.6, 8.4), 9, glow=True)
    elif style == "eye":
        m.box((7.2, 8.6, 4.2), (8.8, 10.0, 4.6), 9, glow=True)
        m.box((10.4, 9.0, 7.2), (12.0, 12.4, 8.8), 0)
    elif style == "wave":
        # 2026-11-03 照站長：夏日系列「浪潮」——肩上捲起來的一小片浪，浪尖白沫發光
        m.box((9.6, 10.6, 6.2), (12.2, 12.4, 9.8), 1)
        m.box((11.6, 12.0, 6.6), (13.4, 13.8, 9.4), 3)
        m.box((12.4, 13.6, 6.9), (13.8, 14.6, 9.1), 5, glow=True)
        m.box((10.8, 14.0, 7.1), (12.8, 14.8, 8.9), 5, glow=True)
        m.box((9.8, 13.2, 7.3), (11.0, 13.9, 8.7), 9, glow=True)
    elif style == "rope":
        # 2026-11-03 照站長：夏日系列「沙灘衛士」——繞肩甲一圈的麻繩，外側打一個結、兩條繩尾垂下來
        m.box((4.4, 10.0, 4.4), (11.6, 10.9, 5.3), 4)
        m.box((4.4, 10.0, 10.7), (11.6, 10.9, 11.6), 4)
        m.box((4.4, 10.0, 5.3), (5.3, 10.9, 10.7), 4)
        m.box((10.7, 10.0, 5.3), (11.6, 10.9, 10.7), 4)
        m.box((11.2, 9.8, 6.8), (13.0, 11.8, 9.2), 0)
        m.box((11.9, 8.2, 7.0), (12.6, 9.8, 7.8), 4)
        m.box((12.2, 7.6, 8.2), (12.9, 9.8, 9.0), 4)
    elif style == "coral":
        # 2026-11-03 照站長：星潮祭限定「珊瑚海妖鎧」——肩上長出橘粉的珊瑚枝（往外、往上分岔，枝頭淺色的珊瑚蟲），
        #   正面一顆小珍珠（發光），肩後垂一條短觸手（兩段、下緣淺青的吸盤、尖端發光；左右兩邊合起來是背後兩條觸手披風）
        m.box((8.4, 11.0, 7.2), (9.6, 14.2, 8.4), 3)                                   # 主幹（橘）
        m.box((9.4, 13.2, 7.3), (10.4, 16.6, 8.3), 3, rot=(-22.5, "z", (9.6, 13.2, 7.8)))   # 往外分岔
        m.box((7.4, 13.6, 7.4), (8.4, 16.0, 8.2), 4, rot=(22.5, "z", (8.4, 13.6, 7.8)))    # 往內分岔（粉）
        m.box((10.6, 10.8, 6.2), (11.8, 12.8, 7.2), 4)                                 # 外側的小枝
        m.box((11.0, 12.8, 6.4), (11.6, 13.6, 7.0), 10)
        for (x, y, z) in ((8.6, 14.2, 7.4), (9.9, 16.4, 7.5), (7.6, 15.9, 7.5)):
            m.box((x, y, z), (x + 0.8, y + 0.7, z + 0.8), 10)                          # 枝頭的珊瑚蟲（淺色）
        m.box((7.2, 8.4, 4.0), (8.8, 10.0, 4.4), 5, glow=True)                         # 正面的小珍珠
        m.box((6.2, 3.0, 11.4), (8.4, 8.4, 12.8), 1)                                   # 肩後垂下的短觸手：上段
        m.box((6.5, -1.2, 11.6), (8.1, 3.0, 12.6), 2, rot=(-22.5, "x", (7.3, 3.0, 12.1)))   # 下段往後翹
        for y in (4.0, 5.8, 7.4):
            m.box((6.0, y, 11.8), (6.2, y + 0.8, 12.4), 9, glow=True)                  # 內側的吸盤（淺青）
        m.box((6.8, -2.2, 11.8), (7.8, -1.2, 12.4), 9, rot=(-22.5, "x", (7.3, 3.0, 12.1)), glow=True)   # 尖端的光
    else:
        m.box((7.4, 11.0, 6.0), (8.6, 11.6, 10.0), 0)          # 鉚釘條
    return m


ARMOR3D = {
    # 主題（自己畫的戰甲貼圖）: (色票, 頭盔, 肩甲樣式)
    "ironguard": (["#2a3240", "#4a5a74", "#7a8ca8", "#c8c8c8", "#3a3a3a", "#d0e4ff", "#14181e", None,
                   None, "#6fa8ff", None, None, None, None, None, None], armor_ironguard_helm, "rivet"),
    "frost": (["#2a5a8a", "#5fa8e0", "#cfefff", "#ffffff", "#1a2e44", "#8af0ff", "#0e1a28", None,
               None, "#eaffff", None, None, None, None, None, None], armor_frost_helm, "crystal"),
    "inferno": (["#3a0e0a", "#8a1e10", "#c87a20", "#ffc24a", "#2a1410", "#ff7a1a", "#1a0a08", None,
                 None, "#ffe08a", None, None, None, None, None, None], armor_inferno_helm, "spike"),
    "starlight": (["#8a6a1e", "#c8cde0", "#d4a83a", "#ffe08a", "#5a5e74", "#f4f6ff", "#2a3050", None,
                   None, "#5fe0ff", None, None, None, None, None, None], armor_starlight_helm, "wing"),
    "void": (["#1a1024", "#3a2058", "#5c3488", "#b4b4d0", "#24182e", "#d24bff", "#0a0610", None,
              None, "#ff4be0", None, None, None, None, None, None], armor_void_helm, "eye"),
    # 2026-10 新增
    "verdant": (["#0e2a1e", "#1f6a46", "#a8743a", "#e0b070", "#3a2410", "#4fc08a", "#081a12", None,
                 None, "#5affb0", None, None, None, None, None, None], armor_verdant_helm, "crystal"),
    "bloodmoon": (["#1a0a10", "#5a0f1e", "#8a8a98", "#d0d0dc", "#2a2a30", "#a81e34", "#0a0408", None,
                   None, "#ff3a4a", None, None, None, None, None, None], armor_bloodmoon_helm, "spike"),
    "sakura": (["#6a2a44", "#d86a98", "#e0c0b0", "#fff4ee", "#4a2a34", "#ff9ad0", "#2a1018", None,
                None, "#fff0f8", None, None, None, None, None, None], armor_sakura_helm, "wing"),
    "solar": (["#6a3a10", "#d8902a", "#e0b040", "#fff0b0", "#4a2410", "#ffb000", "#2a1408", None,
               None, "#fff6d0", None, None, None, None, None, None], armor_solar_helm, "wing"),
    "storm": (["#1a2440", "#3a5a9a", "#7a8494", "#c8d0dc", "#3a3f4a", "#8fe8ff", "#0a1020", None,
               None, "#ffffff", None, None, None, None, None, None], armor_storm_helm, "crystal"),
    "shadow": (["#101014", "#2a2a34", "#4a4a58", "#8a8a9a", "#1a1a20", "#9a6aff", "#050508", None,
                None, "#e0d0ff", None, None, None, None, None, None], armor_shadow_helm, "eye"),
}


ARMOR_MATS = {0: "metal", 1: "metal", 2: "metal", 3: "metal", 4: "metal", 6: "plain"}   # 戰甲色票的預設材質（ARMOR3D 第四格蓋過）


def armor_set(theme):
    colors, helm_fn, style = ARMOR3D[theme][:3]
    mats = {**ARMOR_MATS, **(ARMOR3D[theme][3] if len(ARMOR3D[theme]) > 3 else {})}
    art = f"armor3d_{theme}"
    helm = helm_fn(colors, mats)
    right = pauldron(f"{art}_pauldron_r", colors, style, mats)
    left = Model(f"{art}_pauldron_l", colors, "back")
    tmp = Model("tmp", colors, "back")
    tmp.elements = json.loads(json.dumps(right.elements))
    n = len(tmp.elements)
    tmp.mirror_x()
    left.elements = tmp.elements[n:]
    parts = {"helmet": helm.name, "pauldron_r": right.name, "pauldron_l": left.name}
    for piece in ("chestplate", "leggings", "boots"):
        parts[piece] = {"type": "minecraft:model", "model": f"{NS}:item/skin/{theme}_{piece}"}  # 自己畫的戰甲圖示
    extra = {f"assets/{NS}/equipment/skin_{art}.json":
             {"layers": {"humanoid": [{"texture": f"{NS}:skin_{theme}"}],
                         "humanoid_leggings": [{"texture": f"{NS}:skin_{theme}"}]}}}
    return [helm, right, left], parts, extra



# ======================================================================
# 2026-10 新增：月卡限定（月刃、月華之翼、月兔）與抽獎箱的新時裝。
# 武器照前面的做法：直的做、刃（有方向的部分）放在 -x 那一側（拿在手上刃朝下），最後斜 45 度
# ======================================================================

def moon_glaive():
    m = Model("moon_glaive", ["#2a3050", "#c8d0e8", "#f4f8ff", "#9fd3ff",
                              "#e8d48a", "#3a4a8a", None, None,
                              None, None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'wood', 1: 'metal', 2: 'metal', 4: 'metal', 5: 'cloth'}
    m.box((7.5, -4, 7.5), (8.5, 13, 8.5), 0)                # 長柄
    m.box((7.2, -4.6, 7.2), (8.8, -3.8, 8.8), 4)            # 柄尾
    for y in (0, 4.5):
        m.box((7.3, y, 7.3), (8.7, y + 0.8, 8.7), 5)        # 纏帶
    m.box((6.4, 12.6, 7), (9.6, 14, 9), 4)                  # 刃座
    m.box((7.3, 13.0, 6.7), (8.7, 14.4, 9.3), 3, glow=True)  # 月石
    m.box((6.8, 14, 7.7), (9.2, 19, 8.3), 1)                # 直刃
    m.box((7.3, 19, 7.75), (8.7, 20.6, 8.25), 2)
    m.box((2.6, 15, 7.7), (6.8, 17, 8.3), 1)                # 新月形的刃（往 -x 彎下來）
    m.box((1.0, 12.4, 7.75), (3.2, 15.4, 8.25), 1)
    m.box((0.4, 9.8, 7.8), (1.8, 12.6, 8.2), 2)
    m.box((0.6, 8.6, 7.85), (1.4, 9.8, 8.15), 2)
    m.box((3.2, 14.6, 7.75), (6.8, 15.0, 8.25), 3, glow=True)  # 內緣的月光
    m.box((1.8, 12.0, 7.8), (3.2, 12.4, 8.2), 3, glow=True)
    m.tilt()
    return m


def thunder_hammer():
    m = Model("thunder_hammer", ["#3a3f4a", "#7a8494", "#c8d0dc", "#e0b84a",
                                 "#8fe8ff", "#ffffff", "#4a2e1a", None,
                                 None, None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal', 3: 'metal', 6: 'wood'}
    m.box((7.4, -4, 7.4), (8.6, 12, 8.6), 6)                # 握柄
    m.box((7.0, -4.8, 7.0), (9.0, -3.8, 9.0), 3)
    m.box((7.2, 0, 7.2), (8.8, 0.8, 8.8), 3)
    m.box((3.5, 12, 5.5), (12.5, 17, 10.5), 1)              # 鎚頭（左右對稱）
    m.box((3.3, 12.8, 5.3), (12.7, 13.6, 10.7), 3)
    m.box((3.3, 15.4, 5.3), (12.7, 16.2, 10.7), 3)
    m.box((2.6, 12.6, 6.2), (3.5, 16.4, 9.8), 0)            # 兩端的打擊面
    m.box((12.5, 12.6, 6.2), (13.4, 16.4, 9.8), 0)
    m.box((7, 13.8, 5.2), (9, 15.6, 5.5), 4, glow=True)     # 雷紋
    m.box((7, 13.8, 10.5), (9, 15.6, 10.8), 4, glow=True)
    m.box((7.4, 17, 7.4), (8.6, 19, 8.6), 2)                # 頂刺
    m.box((7.7, 19, 7.7), (8.3, 20.2, 8.3), 5, glow=True)
    m.box((4.4, 17, 7.8), (5.2, 18.6, 8.2), 4, glow=True)   # 跳動的電光
    m.box((10.8, 17, 7.8), (11.6, 18.2, 8.2), 4, glow=True)
    m.tilt()
    return m


def abyss_trident():
    m = Model("abyss_trident", ["#0e2a3a", "#1f6a7a", "#4fd8d0", "#8afff0",
                                "#e8e0c8", "#c9a43a", None, None,
                                None, None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal', 4: 'stone', 5: 'metal'}
    m.box((7.5, -5, 7.5), (8.5, 12, 8.5), 1)                # 長柄
    m.box((7.2, -5.6, 7.2), (8.8, -4.6, 8.8), 5)
    for y in (-1, 4):
        m.box((7.3, y, 7.3), (8.7, y + 0.8, 8.7), 5)
    m.box((7, 10.6, 7), (9, 12, 9), 3, glow=True)           # 深淵之眼
    m.box((4, 12, 7.4), (12, 13.2, 8.6), 5)                 # 橫樑
    m.box((7.3, 13.2, 7.3), (8.7, 20, 8.7), 2)              # 中間的叉
    m.box((7.6, 20, 7.6), (8.4, 21.6, 8.4), 3, glow=True)
    for x0 in (4.0, 10.8):
        m.box((x0, 13.2, 7.5), (x0 + 1.2, 18, 8.5), 2)      # 兩邊的叉（對稱）
        m.box((x0 + 0.2, 18, 7.6), (x0 + 1.0, 19.2, 8.4), 3, glow=True)
    m.box((5.2, 16.2, 7.6), (6.0, 17.2, 8.4), 4)            # 倒鉤
    m.box((10.0, 16.2, 7.6), (10.8, 17.2, 8.4), 4)
    m.tilt()
    return m


def rune_shield():
    m = Model("rune_shield", ["#5a4a3a", "#8a7a6a", "#4a5a74", "#6fa8ff",
                              "#c8c8c8", "#3a2a1e", None, None,
                              None, None, None, None, None, None, None, None], "offhand")
    m.materials = {0: 'wood', 1: 'wood', 2: 'metal', 4: 'metal', 5: 'wood'}
    m.box((3, 2, 7), (13, 14, 9), 0)                        # 木盾
    m.box((2, 4, 7.1), (14, 12, 8.9), 0)
    m.box((2.6, 1.4, 6.8), (13.4, 2.2, 9.2), 2)             # 鐵邊
    m.box((2.6, 13.8, 6.8), (13.4, 14.6, 9.2), 2)
    m.box((1.4, 3.6, 6.8), (2.2, 12.4, 9.2), 2)
    m.box((13.8, 3.6, 6.8), (14.6, 12.4, 9.2), 2)
    m.box((6.6, 6.6, 6.3), (9.4, 9.4, 9.7), 4)              # 盾心
    for a, b in (((7.5, 3.4), (8.5, 5.8)), ((7.5, 10.2), (8.5, 12.6)), ((3.4, 7.5), (5.8, 8.5)), ((10.2, 7.5), (12.6, 8.5))):
        m.box((a[0], a[1], 6.6), (b[0], b[1], 6.8), 3, glow=True)    # 符文
        m.box((a[0], a[1], 9.2), (b[0], b[1], 9.4), 3, glow=True)
    return m


def oni_mask():
    m = Model("oni_mask", ["#b81e2a", "#6a0f18", "#e0b84a", "#f4f0e8",
                           "#1a0a0a", "#ffb000", None, None,
                           None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'wood', 1: 'wood', 2: 'metal', 3: 'stone', 4: 'plain'}
    m.box((2.0, 3.6, 0.9), (14.0, 13.6, 1.5), 0)            # 面具
    m.box((2.4, 11.2, 0.6), (13.6, 12.4, 0.9), 1)           # 眉骨
    m.box((3.6, 8.6, 0.7), (6.6, 10.2, 0.9), 4)             # 眼
    m.box((9.4, 8.6, 0.7), (12.4, 10.2, 0.9), 4)
    m.box((4.6, 9.0, 0.55), (5.6, 9.8, 0.7), 5, glow=True)
    m.box((10.4, 9.0, 0.55), (11.4, 9.8, 0.7), 5, glow=True)
    m.box((7.2, 6.6, 0.6), (8.8, 8.6, 0.9), 1)              # 鼻
    m.box((4.4, 4.2, 0.7), (11.6, 5.6, 0.9), 4)             # 嘴
    m.box((5.2, 3.4, 0.6), (6.0, 5.2, 0.75), 3)             # 獠牙
    m.box((10.0, 3.4, 0.6), (10.8, 5.2, 0.75), 3)
    for x0 in (3.0, 11.4):
        m.box((x0, 13.6, 1.2), (x0 + 1.6, 17.0, 2.6), 2)    # 角
        m.box((x0 + 0.2, 17.0, 1.4), (x0 + 1.2, 18.6, 2.4), 3)
    m.box((1.4, 9.0, 1.5), (1.8, 10.4, 14.6), 1)            # 綁帶
    m.box((14.2, 9.0, 1.5), (14.6, 10.4, 14.6), 1)
    m.box((1.4, 9.0, 14.2), (14.6, 10.4, 14.6), 1)
    return m


def lunar(m):
    m.box((8, 9, 7.4), (18, 10.4, 8.6), 0)                  # 翼骨
    m.box((17.4, 10, 7.5), (25, 11.4, 8.5), 0)
    for i, x in enumerate(range(9, 27, 2)):
        top = 9.6 + i * 0.2
        length = 4 + i * 1.4
        m.box((x, top - length, 7.7 + (i % 2) * 0.1), (x + 1.6, top, 8.3 + (i % 2) * 0.1), 2 if i % 2 else 1)   # 細長的銀羽
        m.box((x + 0.2, top - length - 1.0, 7.8), (x + 1.4, top - length, 8.2), 3, glow=True)                 # 羽尖的月光
    m.box((25, 11, 7.6), (27, 15, 8.4), 5, glow=True)        # 翼尖的新月
    m.box((26.4, 15, 7.6), (28, 16.4, 8.4), 5, glow=True)
    m.box((26.4, 9.6, 7.6), (28, 11, 8.4), 5, glow=True)


def butterfly(m):
    # 2026-10：整片往下移 6（原本上翅高過頭）；翅根不動
    m.box((8, 8, 7.6), (9.4, 10, 8.4), 0)                   # 翅根
    d = -6
    m.box((9, 9 + d, 7.9), (20, 18 + d, 8.1), 1)            # 上翅
    m.box((20, 12 + d, 7.9), (23, 18.5 + d, 8.1), 1)
    m.box((9, 18 + d, 7.9), (22, 19 + d, 8.1), 0)
    m.box((15, 13 + d, 7.85), (18, 16 + d, 8.15), 3, glow=True)     # 翅上的斑紋
    m.box((20.5, 16 + d, 7.85), (22, 17.5 + d, 8.15), 4, glow=True)
    m.box((9, 2 + d, 7.9), (17, 9 + d, 8.1), 2)             # 下翅
    m.box((12, 0 + d, 7.9), (16, 2 + d, 8.1), 2)
    m.box((14, -3 + d, 7.9), (15.4, 0 + d, 8.1), 0)         # 尾突
    m.box((12, 4 + d, 7.85), (14.5, 6.5 + d, 8.15), 5, glow=True)


def pet_moon_rabbit():
    m = Model("pet_moon_rabbit", ["#ffffff", "#eef2ff", "#ffb6c8", "#1a1a24",
                                  "#e8d48a", "#9fd3ff", None, None,
                                  None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'fur', 1: 'fur', 2: 'plain', 3: 'plain'}
    m.box((5, 3, 6), (11, 9, 12), 0)                        # 身體
    m.box((5.4, 8, 2.6), (10.6, 13, 7.4), 0)                # 頭
    for x0 in (5.8, 8.8):
        m.box((x0, 13, 4.4), (x0 + 1.4, 18.5, 5.6), 0)      # 長耳朵
        m.box((x0 + 0.3, 13.4, 4.3), (x0 + 1.1, 18.0, 4.4), 2)
    m.box((6.2, 10.4, 2.4), (7.2, 11.4, 2.6), 3)            # 眼睛
    m.box((8.8, 10.4, 2.4), (9.8, 11.4, 2.6), 3)
    m.box((7.6, 9.4, 2.4), (8.4, 10.0, 2.6), 2)             # 鼻子
    m.box((7, 5, 12), (9, 7, 13.6), 1)                      # 尾巴
    m.box((5.4, 2, 5.4), (7.2, 3, 8), 1)                    # 腳
    m.box((8.8, 2, 5.4), (10.6, 3, 8), 1)
    m.box((11.5, 14, 7.6), (12.5, 17, 8.4), 4, glow=True)   # 飄在旁邊的新月
    m.box((12.5, 16.6, 7.6), (13.8, 17.6, 8.4), 4, glow=True)
    m.box((12.5, 13.4, 7.6), (13.8, 14.4, 8.4), 4, glow=True)
    return m


def pet_bat():
    m = Model("pet_bat", ["#2a1a2a", "#4a2e4a", "#6a4a6a", "#ff3a4a",
                          "#d0d0dc", None, None, None,
                          None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'fur', 1: 'fur', 2: 'leather', 4: 'plain'}
    m.box((6, 6, 6.5), (10, 10.5, 9.5), 1)                  # 身體
    m.box((6.2, 10, 6), (9.8, 13, 9), 1)                    # 頭
    m.box((6.4, 13, 7), (7.4, 14.6, 8), 0)                  # 耳朵
    m.box((8.6, 13, 7), (9.6, 14.6, 8), 0)
    m.box((6.8, 11.4, 5.8), (7.6, 12.2, 6), 3, glow=True)   # 紅眼睛
    m.box((8.4, 11.4, 5.8), (9.2, 12.2, 6), 3, glow=True)
    m.box((7.2, 10, 5.9), (7.6, 10.6, 6), 4)                # 小尖牙
    m.box((8.4, 10, 5.9), (8.8, 10.6, 6), 4)
    for (a, b, c) in (((10, 8), (15, 11), 2), ((15, 6), (16.4, 11.2), 0), ((11, 6.4), (14, 8), 2)):
        m.box((a[0], a[1], 7.8), (b[0], b[1], 8.2), c)      # 右翅
        m.box((16 - b[0], a[1], 7.8), (16 - a[0], b[1], 8.2), c)   # 左翅
    m.box((7, 5, 7.4), (7.6, 6, 8.2), 0)                    # 腳
    m.box((8.4, 5, 7.4), (9, 6, 8.2), 0)
    return m


def pet_jelly():
    m = Model("pet_jelly", [("#8af0ff", 150), "#d8fbff", "#ff9ad5", "#5fe0ff",
                            "#ffffff", "#1a1a24", None, None,
                            None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'glass', 4: 'plain', 5: 'plain'}
    m.box((6.5, 9.6, 6.5), (9.5, 12.4, 9.5), 2, glow=True)  # 發光的核
    m.box((6.4, 10.6, 4.3), (7.2, 11.4, 4.5), 5)            # 眼睛
    m.box((8.8, 10.6, 4.3), (9.6, 11.4, 4.5), 5)
    for (x, z, length) in ((5.5, 5.5, 5), (10, 5.5, 6), (5.5, 10, 6), (10, 10, 5), (7.7, 7.7, 7)):
        m.box((x, 9 - length, z), (x + 0.6, 9, z + 0.6), 3, glow=True)   # 觸手
        m.box((x - 0.1, 9 - length - 0.6, z - 0.1), (x + 0.7, 9 - length, z + 0.7), 1, glow=True)
    m.box((5.5, 13, 5.5), (10.5, 14.2, 10.5), 0)            # 半透明的傘（最後畫）
    m.box((4.5, 9, 4.5), (11.5, 13, 11.5), 0)
    return m


def sun_bow_extras(m, flex):
    m.box((8.6, 6.6, 6.8), (10.2, 9.4, 9.2), 9, glow=True)                 # 握把上的日輪
    m.box((10.2, 7.6, 7.7), (11.6, 8.4, 8.3), 5, glow=True)
    for sign in (1, -1):
        y = 8 + sign * 6
        m.box((9 - flex * 0.2, y - 0.5, 7.6), (10.4 - flex * 0.2, y + 0.5, 8.4), 8)   # 弓臂上的金焰
        m.box((10.4 - flex * 0.2, y - 0.3, 7.7), (11.2 - flex * 0.2, y + 0.3, 8.3), 5, glow=True)


def shadow_extras(m):
    m.box((6.6, 6.6, 6.8), (9.4, 8.8, 9.2), 9, glow=True)      # 暗影之眼
    m.box((0.4, 8.6, 7.6), (1.6, 10.0, 8.4), 8)                # 托上的尖刺
    m.box((0.4, 4.0, 7.6), (1.6, 5.4, 8.4), 8)



# ======================================================================
# 2026-10 S1 贊助禮包限定：「創世」系列（絕版，不抽、不換）。深宇宙藍 × 金 × 極光青，每一件都比一般時裝多一層細節：
# 浮空的星砂碎片、發光的符文槽、金色的滾邊。武器一樣直的做、最後斜 45 度。
# ======================================================================

GENESIS = ["#0b1230", "#1b2a5c", "#5a3fb0", "#c8d6f0",
           "#f2c14e", "#9a7420", "#39e7ff", "#c8f6ff",
           "#ffffff", "#ff5fd2", "#2a3a7a", "#7fe8ff",
           None, None, None, None]
# 0 深宇宙藍、1 午夜藍、2 星雲紫、3 淡鋼、4 金、5 暗金、6 極光青（發光）、7 亮青（發光）、8 白、9 星砂粉（發光）、10 中藍、11 冰青
GENESIS_MATS = {0: "metal", 1: "metal", 2: "crystal", 3: "metal", 4: "metal", 5: "metal", 8: "metal", 10: "metal", 11: "crystal"}


def genesis_sparks(m, points, c=9, size=0.5, rot=None):
    """浮在旁邊的星砂碎片（小小的發光方塊，斜著放）。"""
    for (x, y, z) in points:
        m.box((x - size, y - size, z - size), (x + size, y + size, z + size), c,
              rot=((45, "z", (x, y, z)) if rot else None), glow=True)


def genesis_blade():
    m = Model("genesis_blade", GENESIS, "weapon")
    m.materials = GENESIS_MATS
    # 柄頭：星形的金座、底下一顆星砂
    m.box((6.6, -4.4, 6.6), (9.4, -2.6, 9.4), 4)
    m.box((7.3, -5.4, 7.3), (8.7, -4.4, 8.7), 9, glow=True)
    m.box((6.0, -3.8, 7.4), (6.6, -3.2, 8.6), 5)
    m.box((9.4, -3.8, 7.4), (10.0, -3.2, 8.6), 5)
    # 握把：午夜藍，纏著金線
    m.box((7.4, -2.6, 7.4), (8.6, 4.2, 8.6), 1)
    for y in (-1.8, -0.4, 1.0, 2.4):
        m.box((7.25, y, 7.25), (8.75, y + 0.5, 8.75), 4)
    # 護手：寬的金座，兩端往上翹成一對新月，中間鑲極光核
    m.box((3.6, 4.2, 6.9), (12.4, 6.0, 9.1), 4)
    m.box((4.4, 6.0, 7.2), (11.6, 6.6, 8.8), 5)
    for sign in (1, -1):
        def X(x):
            return 8 + sign * (x - 8)
        m.box((min(X(12.4), X(14.0)), 4.6, 7.3), (max(X(12.4), X(14.0)), 6.4, 8.7), 4)
        m.box((min(X(13.4), X(14.6)), 6.4, 7.4), (max(X(13.4), X(14.6)), 8.8, 8.6), 4)
        m.box((min(X(14.0), X(15.0)), 8.8, 7.5), (max(X(14.0), X(15.0)), 10.6, 8.5), 5)
        m.box((min(X(14.1), X(14.9)), 10.6, 7.6), (max(X(14.1), X(14.9)), 11.8, 8.4), 6, glow=True)
    m.box((6.9, 4.0, 6.3), (9.1, 6.2, 9.7), 10)                  # 核的座
    m.box((7.3, 4.4, 6.0), (8.7, 5.8, 10.0), 6, glow=True)       # 極光核
    m.box((7.7, 4.8, 5.8), (8.3, 5.4, 10.2), 8, glow=True)
    # 劍身：淡鋼，兩側亮邊，中間一條極光血槽、槽裡嵌著符文
    m.box((6.2, 6.6, 7.5), (9.8, 19.6, 8.5), 3)
    m.box((5.9, 7.0, 7.65), (6.2, 19.2, 8.35), 8)
    m.box((9.8, 7.0, 7.65), (10.1, 19.2, 8.35), 8)
    m.box((7.45, 7.2, 7.4), (8.55, 18.8, 8.6), 6, glow=True)
    for i, y in enumerate((8.2, 10.4, 12.6, 14.8, 17.0)):
        m.box((7.65, y, 7.3), (8.35, y + 0.9, 8.7), 9 if i % 2 == 0 else 7, glow=True)
    # 劍尖：三段收細
    m.box((6.7, 19.6, 7.6), (9.3, 21.4, 8.4), 3)
    m.box((7.2, 21.4, 7.7), (8.8, 23.0, 8.3), 3)
    m.box((7.6, 23.0, 7.75), (8.4, 24.4, 8.25), 8)
    m.box((7.8, 24.4, 7.85), (8.2, 25.2, 8.15), 7, glow=True)
    # 劍身外面浮著的星砂
    genesis_sparks(m, ((5.0, 9.6, 8.0), (11.0, 12.8, 8.0), (4.8, 16.0, 8.0), (11.2, 18.6, 8.0)), 9, 0.45)
    genesis_sparks(m, ((11.0, 9.0, 8.0), (5.0, 13.4, 8.0)), 7, 0.35)
    m.tilt()
    return m


def genesis_staff():
    """創世星辰杖：午夜藍杖身纏金線、頂端一對新月金角托著極光核，核外圍浮著一圈星砂，底端金色的杖尖。"""
    m = Model("genesis_staff", GENESIS, "weapon")
    m.materials = GENESIS_MATS
    m.box((7.3, -5.4, 7.3), (8.7, 12.6, 8.7), 1)                # 杖身
    m.box((7.6, -7.0, 7.6), (8.4, -5.4, 8.4), 4)                # 底端的杖尖
    m.box((7.8, -7.8, 7.8), (8.2, -7.0, 8.2), 6, glow=True)
    for y in (-3.6, -0.2, 3.2, 6.6):
        m.box((7.1, y, 7.1), (8.9, y + 0.7, 8.9), 4)             # 金環
    m.box((7.05, 9.0, 7.05), (8.95, 10.4, 8.95), 10)             # 握把上方的座
    m.box((6.6, 10.4, 6.6), (9.4, 12.6, 9.4), 4)                 # 頂座
    m.box((6.2, 12.6, 6.2), (9.8, 13.4, 9.8), 5)
    for sign in (1, -1):                                         # 一對新月金角
        def X(x):
            return 8 + sign * (x - 8)
        m.box((min(X(9.8), X(11.2)), 12.8, 7.3), (max(X(9.8), X(11.2)), 15.0, 8.7), 4)
        m.box((min(X(10.8), X(12.0)), 15.0, 7.4), (max(X(10.8), X(12.0)), 18.4, 8.6), 4)
        m.box((min(X(11.2), X(12.2)), 18.4, 7.5), (max(X(11.2), X(12.2)), 21.2, 8.5), 5)
        m.box((min(X(10.6), X(11.6)), 21.2, 7.6), (max(X(10.6), X(11.6)), 23.0, 8.4), 5)
        m.box((min(X(10.8), X(11.4)), 23.0, 7.7), (max(X(10.8), X(11.4)), 24.2, 8.3), 7, glow=True)
    m.box((6.6, 15.4, 6.6), (9.4, 18.2, 9.4), 6, glow=True)      # 極光核
    m.box((6.0, 16.2, 7.0), (10.0, 17.4, 9.0), 7, glow=True)                  # 核的橫向光帶
    m.box((7.2, 16.0, 7.2), (8.8, 17.6, 8.8), 8, glow=True)
    m.box((7.6, 18.2, 7.6), (8.4, 19.6, 8.4), 7, glow=True)      # 核上面冒出的光
    m.box((7.8, 19.6, 7.8), (8.2, 20.8, 8.2), 8, glow=True)
    m.box((7.4, 13.4, 7.4), (8.6, 15.4, 8.6), 11)                # 托著核的冰青柱
    genesis_sparks(m, ((4.6, 14.2, 8.0), (11.4, 19.6, 8.0), (4.8, 19.0, 8.0), (11.2, 13.8, 8.0), (8.0, 22.6, 8.0)), 9, 0.45)
    genesis_sparks(m, ((5.2, 4.0, 8.0), (10.8, 1.0, 8.0)), 7, 0.3)
    m.tilt()
    return m


def genesis_bow_extras(m, flex):
    m.box((8.4, 6.8, 6.6), (10.0, 9.2, 9.4), 10)                            # 握把中央的座
    m.box((8.8, 7.2, 6.2), (9.6, 8.8, 9.8), 6, glow=True)                   # 極光核
    m.box((9.0, 7.6, 6.0), (9.4, 8.4, 10.0), 8, glow=True)
    for sign in (1, -1):
        y = 8 + sign * 4.6
        m.box((9.0 - flex * 0.25, y - 0.8, 7.55), (10.6 - flex * 0.25, y + 0.8, 8.45), 8)    # 弓臂上的金飾
        m.box((10.6 - flex * 0.25, y - 0.4, 7.65), (11.6 - flex * 0.25, y + 0.4, 8.35), 9, glow=True)
        y2 = 8 + sign * 8.4
        m.box((7.4 - flex * 0.7, y2 - 0.6, 7.6), (8.6 - flex * 0.7, y2 + 0.6, 8.4), 8)
        y3 = 8 + sign * 10.6
        m.box((4.6 - flex, y3 - 0.5, 7.7), (5.6 - flex, y3 + 0.5, 8.3), 5, glow=True)       # 弓尖的星砂
    genesis_sparks(m, ((11.8, 12.6, 8.0), (11.8, 3.4, 8.0)), 9, 0.4)


def genesis_crossbow_extras(m):
    m.box((6.4, 6.4, 6.6), (9.6, 9.0, 9.4), 10)                 # 弩身中段的座
    m.box((7.0, 6.9, 6.3), (9.0, 8.5, 9.7), 9, glow=True)       # 極光核
    m.box((7.6, 7.3, 6.1), (8.4, 8.1, 9.9), 8, glow=True)
    m.box((1.4, 5.0, 7.0), (2.6, 9.0, 9.0), 8)                  # 托上的金箍
    m.box((3.6, 6.6, 7.0), (4.4, 8.8, 9.0), 8)
    m.box((12.0, 0.4, 7.6), (13.6, 1.6, 8.4), 8)                # 弩臂兩端的金座
    m.box((12.0, 14.4, 7.6), (13.6, 15.6, 8.4), 8)
    m.box((12.4, -0.8, 7.7), (13.2, 0.4, 8.3), 9, glow=True)
    m.box((12.4, 15.6, 7.7), (13.2, 16.8, 8.3), 9, glow=True)
    genesis_sparks(m, ((14.8, 4.6, 8.0), (14.8, 11.4, 8.0)), 5, 0.4)


def genesis_aegis():
    m = Model("genesis_aegis", GENESIS, "offhand")
    m.materials = GENESIS_MATS
    m.box((2.0, 2.0, 7.0), (14.0, 15.0, 9.0), 4)                # 金框
    m.box((2.8, 2.8, 6.6), (13.2, 14.2, 9.4), 1)                # 盾面
    m.box((3.6, 3.6, 6.4), (12.4, 13.4, 9.6), 10)               # 內層
    m.box((4.4, 0.6, 7.1), (11.6, 2.0, 8.9), 4)                 # 下面收尖
    m.box((5.4, -0.9, 7.2), (10.6, 0.6, 8.8), 4)
    m.box((6.6, -2.4, 7.3), (9.4, -0.9, 8.7), 5)
    m.box((7.3, -3.4, 7.4), (8.7, -2.4, 8.6), 9, glow=True)
    m.box((1.2, 13.6, 7.2), (2.8, 16.4, 8.8), 4)                # 上緣兩角
    m.box((13.2, 13.6, 7.2), (14.8, 16.4, 8.8), 4)
    m.box((1.6, 16.4, 7.4), (2.4, 17.6, 8.6), 6, glow=True)
    m.box((13.6, 16.4, 7.4), (14.4, 17.6, 8.6), 6, glow=True)
    m.box((4.8, 15.0, 7.3), (11.2, 16.0, 8.7), 5)               # 上緣的冠
    m.box((6.4, 16.0, 7.4), (9.6, 17.0, 8.6), 4)
    m.box((7.4, 17.0, 7.5), (8.6, 18.0, 8.5), 7, glow=True)
    # 中間：八芒星的極光核
    m.box((6.2, 6.2, 5.8), (9.8, 9.8, 10.2), 6, rot=(45, "z", (8, 8, 8)), glow=True)
    m.box((6.6, 6.6, 5.6), (9.4, 9.4, 10.4), 7, glow=True)
    m.box((7.3, 7.3, 5.4), (8.7, 8.7, 10.6), 8, glow=True)
    m.box((7.5, 3.8, 6.0), (8.5, 12.2, 10.0), 6, glow=True)     # 十字星芒
    m.box((3.8, 7.5, 6.0), (12.2, 8.5, 10.0), 6, glow=True)
    m.box((7.6, 4.6, 6.0), (8.4, 11.4, 10.0), 11, rot=(45, "z", (8, 8, 8)), glow=True)
    # 四角的符文槽
    for (x, y) in ((4.6, 4.6), (11.4, 4.6), (4.6, 11.4), (11.4, 11.4)):
        m.box((x - 0.7, y - 0.7, 6.2), (x + 0.7, y + 0.7, 9.8), 5)
        m.box((x - 0.35, y - 0.35, 6.0), (x + 0.35, y + 0.35, 10.0), 9, glow=True)
    genesis_sparks(m, ((0.4, 8.0, 8.0), (15.6, 8.0, 8.0), (8.0, 19.0, 8.0)), 9, 0.45)
    return m


def genesis_wing(m):
    """創世之翼：金色的翼骨、三層青藍漸層的羽片、羽尖星砂，翼根一顆極光核。"""
    m.box((7.6, 8.6, 7.4), (16.4, 10.6, 8.6), 4)                 # 翼骨
    m.box((15.8, 9.4, 7.5), (23.6, 11.2, 8.5), 4)
    m.box((23.0, 10.2, 7.6), (28.6, 11.8, 8.4), 5)
    m.box((7.8, 8.2, 6.9), (10.2, 11.2, 9.1), 10)                # 翼根的座
    m.box((8.4, 8.8, 6.6), (9.6, 10.6, 9.4), 6, glow=True)       # 極光核
    for i, x in enumerate(range(10, 29, 2)):
        top = 9.2 + min(i, 7) * 0.3
        length = 5.5 + i * 1.3
        c = (1, 10, 2)[i % 3]
        m.box((x, top - length, 7.7 + (i % 2) * 0.1), (x + 2.1, top, 8.3 + (i % 2) * 0.1), c)       # 長羽
        m.box((x + 0.3, top - length - 1.2, 7.8), (x + 1.8, top - length, 8.2), 11)               # 羽尖冰青
        m.box((x + 0.6, top - length - 1.9, 7.85), (x + 1.5, top - length - 1.2, 8.15), 7, glow=True)
        if i % 3 == 1:
            m.box((x + 0.7, top - length * 0.55, 7.65), (x + 1.4, top - length * 0.55 + 0.8, 8.35), 9, glow=True)  # 羽上的符文
    for i, x in enumerate(range(10, 27, 3)):
        m.box((x, 7.4 + i * 0.35, 8.3), (x + 3, 10.2 + i * 0.35, 8.85), 3)                        # 覆羽（淡鋼）
        m.box((x + 0.4, 9.8 + i * 0.35, 8.4), (x + 2.6, 10.3 + i * 0.35, 8.8), 4)                 # 覆羽的金邊
    m.box((28.2, 10.8, 7.6), (30.0, 12.6, 8.4), 7, glow=True)   # 翼尖的星
    genesis_sparks(m, ((19.0, 12.8, 8.0), (25.0, 14.0, 8.0), (14.0, 1.0, 8.0), (22.0, -2.0, 8.0)), 9, 0.45)


def pet_genesis_whale():
    """創世小星鯨：圓滾滾的星空鯨，身上有星砂、尾巴拖著極光。"""
    m = Model("pet_genesis_whale", GENESIS, "pet")
    m.materials = {**GENESIS_MATS, 1: 'plain', 10: 'plain', 11: 'plain'}
    m.box((4.4, 5.0, 3.0), (11.6, 10.6, 12.0), 1)               # 身體
    m.box((5.0, 4.4, 3.6), (11.0, 5.0, 11.4), 11)               # 白肚子（冰青）
    m.box((5.2, 10.6, 4.0), (10.8, 11.2, 10.6), 10)             # 背部的深色
    m.box((4.8, 6.2, 1.4), (11.2, 10.0, 3.0), 1)                # 頭（前面）
    m.box((5.4, 5.4, 1.0), (10.6, 6.2, 3.0), 11)                # 下巴
    m.box((5.6, 7.8, 1.1), (6.8, 8.8, 1.4), 8)                  # 眼睛
    m.box((9.2, 7.8, 1.1), (10.4, 8.8, 1.4), 8)
    m.box((6.0, 8.0, 1.0), (6.5, 8.6, 1.1), 0)
    m.box((9.5, 8.0, 1.0), (10.0, 8.6, 1.1), 0)
    m.box((5.4, 7.0, 1.2), (6.3, 7.5, 1.4), 9, glow=True)       # 腮紅
    m.box((9.7, 7.0, 1.2), (10.6, 7.5, 1.4), 9, glow=True)
    m.box((7.3, 11.2, 5.6), (8.7, 12.6, 7.0), 6, glow=True)     # 噴氣孔的極光
    m.box((7.6, 12.6, 5.9), (8.4, 13.6, 6.7), 7, glow=True)
    m.box((11.6, 7.0, 6.0), (15.0, 8.2, 9.0), 1, rot=(-22.5, "z", (11.6, 7.6, 7.5)))   # 胸鰭
    m.box((1.0, 7.0, 6.0), (4.4, 8.2, 9.0), 1, rot=(22.5, "z", (4.4, 7.6, 7.5)))
    m.box((11.8, 7.4, 6.6), (14.6, 7.8, 8.4), 4, rot=(-22.5, "z", (11.6, 7.6, 7.5)))   # 鰭上的金邊
    m.box((1.4, 7.4, 6.6), (4.2, 7.8, 8.4), 4, rot=(22.5, "z", (4.4, 7.6, 7.5)))
    m.box((6.4, 6.4, 12.0), (9.6, 9.0, 14.6), 1)                # 尾柄
    m.box((4.0, 7.0, 14.4), (12.0, 8.4, 17.0), 1)               # 尾鰭
    m.box((3.4, 7.2, 16.4), (5.6, 8.2, 18.2), 10)
    m.box((10.4, 7.2, 16.4), (12.6, 8.2, 18.2), 10)
    m.box((4.0, 7.5, 17.0), (12.0, 7.9, 17.6), 6, glow=True)    # 尾鰭的極光邊
    for (x, y, z) in ((5.6, 9.4, 5.0), (9.8, 9.8, 7.4), (6.6, 10.2, 9.6), (10.2, 8.6, 10.8)):
        m.box((x - 0.35, y - 0.35, z - 0.35), (x + 0.35, y + 0.35, z + 0.35), 9, glow=True)       # 身上的星砂
    ring(m, 11.6, 12.2, 2.4, 0.5, 4)                            # 頭上的小金環
    genesis_sparks(m, ((2.6, 12.4, 8.0), (13.6, 12.0, 6.0), (8.0, 3.0, 15.4), (12.4, 4.0, 3.0)), 7, 0.4)
    return m


def armor_genesis_helm(colors, materials=None):
    m = Model("armor3d_genesis_helm", colors, "headgear", materials)
    helm_shell(m, 1, 4)
    m.box((2.6, 14.6, 1.6), (13.4, 15.4, 3.0), 4)               # 額上的金冠座
    for (x, h) in ((2.6, 2.2), (5.0, 3.6), (7.4, 5.0), (9.8, 3.6), (12.2, 2.2)):
        m.box((x, 15.6, 1.4), (x + 1.2, 15.6 + h, 2.6), 4)       # 五芒冠
        m.box((x + 0.3, 15.6 + h, 1.6), (x + 0.9, 15.6 + h + 1.0, 2.4), 6, glow=True)
    m.box((6.6, 12.0, 0.4), (9.4, 14.2, 1.0), 10)               # 額上的星石座
    m.box((7.2, 12.5, 0.2), (8.8, 13.7, 0.8), 6, glow=True)
    m.box((7.6, 12.9, 0.0), (8.4, 13.3, 0.6), 8, glow=True)
    for sign in (1, -1):                                        # 兩側的新月翼
        def X(x):
            return 8 + sign * (x - 8)
        m.box((min(X(14.8), X(16.2)), 9.0, 6.8), (max(X(14.8), X(16.2)), 12.6, 9.2), 4)
        m.box((min(X(16.0), X(17.6)), 10.2, 7.2), (max(X(16.0), X(17.6)), 15.4, 8.8), 4)
        m.box((min(X(17.4), X(18.6)), 13.0, 7.4), (max(X(17.4), X(18.6)), 18.0, 8.6), 5)
        m.box((min(X(18.4), X(19.2)), 16.0, 7.6), (max(X(18.4), X(19.2)), 19.8, 8.4), 6, glow=True)
        m.box((min(X(3.0), X(5.0)), 9.0, 0.7), (max(X(3.0), X(5.0)), 9.8, 1.0), 6, glow=True)   # 眼縫的極光
    m.box((7.6, 20.4, 7.6), (8.4, 21.2, 8.4), 9, rot=(45, "z", (8, 20.8, 8)), glow=True)   # 頭頂飄著的星砂
    return m


def genesis_pauldron(name, colors):
    """創世的肩甲：金邊的三層甲片、肩上一顆極光核、外側兩根新月刺。右肩（+x 是外側），轉軸 8,8,8。"""
    m = Model(name, colors, "back", GENESIS_MATS)
    m.box((4.4, 8.4, 4.4), (11.6, 10.4, 11.6), 1)               # 肩甲殼
    m.box((4.2, 10.4, 4.2), (11.8, 10.9, 11.8), 4)              # 金邊
    m.box((5.2, 10.9, 5.2), (10.8, 11.5, 10.8), 10)
    m.box((4.0, 6.4, 4.2), (12.0, 8.4, 11.8), 1)                # 第二層
    m.box((3.8, 6.0, 4.0), (12.2, 6.6, 12.0), 4)
    m.box((4.2, 4.6, 4.6), (11.8, 6.0, 11.4), 10)               # 第三層
    m.box((4.0, 4.2, 4.4), (12.0, 4.8, 11.6), 5)
    m.box((7.0, 11.2, 7.0), (9.0, 12.6, 9.0), 6, rot=(45, "y", (8, 11.9, 8)), glow=True)   # 極光核
    m.box((7.5, 11.7, 7.5), (8.5, 13.1, 8.5), 8, glow=True)
    m.box((10.8, 9.6, 6.6), (13.0, 10.8, 7.6), 4)               # 新月刺
    m.box((12.6, 10.4, 6.8), (14.2, 11.4, 7.4), 5)
    m.box((14.0, 11.0, 6.9), (15.0, 12.2, 7.3), 6, glow=True)
    m.box((10.8, 9.6, 8.4), (13.0, 10.8, 9.4), 4)
    m.box((12.6, 10.4, 8.6), (14.2, 11.4, 9.2), 5)
    m.box((14.0, 11.0, 8.7), (15.0, 12.2, 9.1), 6, glow=True)
    for z in (5.4, 8.0, 10.6):
        m.box((11.3, 7.0, z), (11.9, 7.8, z + 0.8), 9, glow=True)   # 第二層外側的符文
    return m


def genesis_armor_set():
    colors = GENESIS
    art = "armor3d_genesis"
    helm = armor_genesis_helm(colors, GENESIS_MATS)
    right = genesis_pauldron(f"{art}_pauldron_r", colors)
    left = Model(f"{art}_pauldron_l", colors, "back")
    tmp = Model("tmp", colors, "back")
    tmp.elements = json.loads(json.dumps(right.elements))
    n = len(tmp.elements)
    tmp.mirror_x()
    left.elements = tmp.elements[n:]
    parts = {"helmet": helm.name, "pauldron_r": right.name, "pauldron_l": left.name}
    for piece in ("chestplate", "leggings", "boots"):
        parts[piece] = {"type": "minecraft:model", "model": f"{NS}:item/skin/genesis_{piece}"}
    extra = {f"assets/{NS}/equipment/skin_{art}.json":
             {"layers": {"humanoid": [{"texture": f"{NS}:skin_genesis"}],
                         "humanoid_leggings": [{"texture": f"{NS}:skin_genesis"}]}}}
    return [helm, right, left], parts, extra



# ======================================================================
# 2026-10 萬燈夜（萬聖節活動）限定：南瓜、巫帽、蝙蝠、幽靈、夜貓、骷髏、提燈……全部是活動兌換才拿得到
# ======================================================================

HW_ORANGE = ["#a8480e", "#e2761a", "#ffa63a", "#4a2a12",
             "#2e6a1e", "#ffd23a", "#1a0e06", "#ffe9a0",
             None, None, None, None, None, None, None, None]


def hw_pumpkin_head():
    """南瓜燈頭：整顆頭套進刻臉的南瓜（臉在 north），眼睛、嘴透出橘光，頂上有蒂和捲鬚。"""
    m = Model("hw_pumpkin_head", HW_ORANGE, "headgear")
    m.materials = {0: 'leather', 1: 'leather', 2: 'leather', 3: 'wood', 4: 'plain'}
    m.box((0.6, 0.8, 0.6), (15.4, 14.6, 15.4), 1)             # 南瓜
    for x in (3.2, 7.4, 11.6):                                 # 直的稜（正面、背面）
        m.box((x, 1.0, 0.4), (x + 1.2, 14.4, 0.6), 0, faces=("north",))
        m.box((x, 1.0, 15.4), (x + 1.2, 14.4, 15.6), 0, faces=("south",))
    for z in (3.2, 7.4, 11.6):                                 # 側面的稜
        m.box((0.4, 1.0, z), (0.6, 14.4, z + 1.2), 0, faces=("west",))
        m.box((15.4, 1.0, z), (15.6, 14.4, z + 1.2), 0, faces=("east",))
    m.box((1.6, 14.6, 1.6), (14.4, 15.4, 14.4), 2)             # 頂
    m.box((7.0, 15.4, 7.0), (9.0, 18.0, 9.0), 3)               # 蒂
    m.box((9.0, 17.0, 7.6), (11.4, 17.6, 8.4), 4)              # 捲鬚
    m.box((11.0, 17.6, 7.6), (11.6, 18.6, 8.4), 4)
    # 刻出來的臉（三角眼、鋸齒嘴），往內縮一點、發光
    for x0 in (3.0, 9.6):
        m.box((x0, 8.4, 0.24), (x0 + 3.4, 9.4, 0.38), 5, glow=True)
        m.box((x0 + 0.6, 9.4, 0.24), (x0 + 2.8, 10.4, 0.38), 5, glow=True)
        m.box((x0 + 1.2, 10.4, 0.24), (x0 + 2.2, 11.2, 0.38), 5, glow=True)
    m.box((6.9, 6.8, 0.24), (9.1, 7.8, 0.38), 5, glow=True)     # 鼻
    m.box((3.0, 3.0, 0.24), (13.0, 5.4, 0.38), 5, glow=True)    # 嘴
    m.box((2.2, 4.4, 0.24), (3.0, 5.4, 0.38), 5, glow=True)     # 嘴角往上翹
    m.box((13.0, 4.4, 0.24), (13.8, 5.4, 0.38), 5, glow=True)
    for x in (5.0, 9.4):
        m.box((x, 4.4, 0.16), (x + 1.4, 5.4, 0.24), 1)          # 上排的牙（沒刻掉的南瓜肉）
    for x in (7.2, 11.2):
        m.box((x, 3.0, 0.16), (x + 1.2, 3.9, 0.24), 1)          # 下排的牙
    return m


def hw_witch_hat():
    m = Model("hw_witch_hat", ["#1a1024", "#2e1a44", "#4a2a6a", "#8a4fd8",
                               "#e8c050", "#d8d0e8", "#3aff7a", None,
                               None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'cloth', 1: 'cloth', 2: 'cloth', 3: 'cloth', 4: 'metal', 5: 'stone'}
    m.box((-1.4, 13.0, -1.4), (17.4, 13.8, 17.4), 1)           # 寬帽緣
    m.box((1.4, 13.8, 1.4), (14.6, 16.2, 14.6), 2)             # 帽身
    m.box((1.2, 13.8, 1.2), (14.8, 15.0, 14.8), 3)             # 紫色帽帶
    m.box((6.6, 13.9, 0.9), (9.4, 15.0, 1.2), 4)               # 金扣
    m.box((7.2, 14.2, 0.8), (8.8, 14.7, 0.9), 0)
    m.box((3.0, 16.2, 3.0), (13.0, 18.6, 13.0), 2)
    m.box((4.4, 18.6, 4.4), (11.6, 20.8, 11.6), 1)
    m.box((5.6, 20.8, 6.0), (10.0, 22.8, 10.4), 1, rot=(-22.5, "z", (8, 20.8, 8)))   # 往旁邊歪的尖
    m.box((7.0, 22.6, 7.0), (9.6, 25.0, 9.6), 0, rot=(-45, "z", (8, 22.6, 8)))
    m.box((-0.6, 12.6, 6.6), (0.6, 13.0, 9.4), 5)              # 掛在帽緣的小骷髏
    m.box((-0.4, 11.4, 7.0), (0.4, 12.6, 9.0), 5)
    m.box((-0.5, 11.9, 7.4), (-0.4, 12.3, 7.8), 6, glow=True)
    m.box((-0.5, 11.9, 8.2), (-0.4, 12.3, 8.6), 6, glow=True)
    return m


def hw_devil_horns():
    m = Model("hw_devil_horns", ["#5a0a10", "#a8141e", "#e02a2a", "#ff6a4a",
                                 "#1a0606", "#ffb000", None, None,
                                 None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'stone', 1: 'stone', 2: 'stone', 4: 'metal'}
    m.box((3.0, 13.6, 6.6), (5.2, 15.4, 9.4), 1)               # 小小的角根
    m.box((2.4, 15.4, 6.9), (4.6, 17.0, 9.1), 1, rot=(22.5, "z", (3.5, 15.4, 8)))
    m.box((1.8, 16.8, 7.2), (3.6, 18.4, 8.8), 2, rot=(22.5, "z", (2.7, 16.8, 8)))
    m.box((1.4, 18.2, 7.5), (2.8, 19.4, 8.5), 3, rot=(45, "z", (2.1, 18.2, 8)), glow=True)
    m.mirror_x()
    ring(m, 13.2, 13.8, 1.3, 0.5, 4)                           # 黑色細頭環
    m.box((7.2, 13.0, 1.0), (8.8, 14.2, 1.3), 5, glow=True)    # 額前的小火光
    return m


def hw_bat_wing(m):
    bone = 0
    m.box((8, 9, 7.5), (15, 10.4, 8.5), bone)                  # 上臂
    m.box((14.6, 9.6, 7.5), (20, 11.0, 8.5), bone)
    m.box((19.6, 10.2, 7.6), (21.0, 13.4, 8.4), bone)          # 拇指的鉤
    m.box((20.4, 13.0, 7.6), (21.6, 14.2, 8.4), 3)
    tips = ((12.8, 14.0, -1.0), (16.2, 17.4, -3.4), (19.6, 20.8, -2.0), (22.6, 23.8, 2.4))
    for (x0, x1, y1) in tips:
        m.box((x0, y1, 7.6), (x1, 10.2, 8.4), bone)            # 指骨
        m.box((x0 + 0.2, y1 - 1.0, 7.7), (x1 - 0.2, y1, 8.3), 3)
    # 翼膜：指骨之間一片一片，下緣是波浪
    m.box((8.6, 1.0, 7.9), (12.8, 9.4, 8.1), 1)
    m.box((14.0, -1.6, 7.9), (16.2, 9.6, 8.1), 1)
    m.box((17.4, -0.4, 7.9), (19.6, 9.8, 8.1), 1)
    m.box((20.8, 3.0, 7.9), (22.6, 10.0, 8.1), 1)
    m.box((12.8, 0.2, 7.9), (14.0, 9.6, 8.1), 2)
    m.box((16.2, -2.0, 7.9), (17.4, 9.8, 8.1), 2)
    m.box((19.6, 1.0, 7.9), (20.8, 10.0, 8.1), 2)
    m.box((9.4, 4.0, 7.85), (12.4, 4.6, 8.15), 4, glow=True)   # 膜上的紫色脈絡
    m.box((14.4, 2.0, 7.85), (19.0, 2.6, 8.15), 4, glow=True)


def hw_wisp_halo():
    m = Model("hw_wisp_halo", ["#1a3a2a", "#3aff7a", "#b8ffd0", "#ffffff",
                               "#7affd8", "#2ac8a8", None, None,
                               None, None, None, None, None, None, None, None], "back")
    m.materials = {}
    for i in range(8):
        ang = math.radians(i * 45 + 22.5)
        cx = 8 + 8.6 * math.cos(ang)
        cy = 8 + 8.6 * math.sin(ang)
        c = 1 if i % 2 == 0 else 4
        m.box((cx - 1.2, cy - 1.0, 7.2), (cx + 1.2, cy + 1.4, 8.8), c, glow=True)   # 鬼火
        m.box((cx - 0.7, cy + 1.4, 7.5), (cx + 0.7, cy + 2.6, 8.5), 2, glow=True)
        m.box((cx - 0.3, cy + 2.6, 7.8), (cx + 0.3, cy + 3.4, 8.2), 3, glow=True)
    m.box((6.8, 6.8, 7.6), (9.2, 9.2, 8.4), 5, rot=(45, "z", (8, 8, 8)), glow=True)
    return m


def hw_pet_pumpkin():
    m = Model("hw_pet_pumpkin", HW_ORANGE, "pet")
    m.materials = {0: 'leather', 1: 'leather', 2: 'leather', 3: 'wood', 4: 'feather'}
    m.box((4, 4, 4), (12, 11, 12), 1)                          # 小南瓜
    for x in (5.6, 7.6, 9.6):
        m.box((x, 4.2, 3.8), (x + 0.8, 10.8, 4.0), 0, faces=("north",))
        m.box((x, 4.2, 12.0), (x + 0.8, 10.8, 12.2), 0, faces=("south",))
    m.box((7.2, 11, 7.2), (8.8, 13, 8.8), 3)                   # 蒂
    m.box((8.8, 12.2, 7.6), (11.6, 12.8, 9.2), 4)              # 葉子
    m.box((5.2, 8.0, 3.9), (7.0, 9.4, 4.0), 5, glow=True)      # 眼
    m.box((9.0, 8.0, 3.9), (10.8, 9.4, 4.0), 5, glow=True)
    m.box((5.6, 5.4, 3.9), (10.4, 6.6, 4.0), 5, glow=True)     # 笑嘴
    m.box((6.4, 6.6, 3.9), (7.2, 7.2, 4.0), 5, glow=True)
    m.box((8.8, 6.6, 3.9), (9.6, 7.2, 4.0), 5, glow=True)
    m.box((7.4, 2.6, 7.4), (8.6, 4.0, 8.6), 7, glow=True)      # 底下的燭光
    return m


def hw_pet_ghost():
    m = Model("hw_pet_ghost", ["#f4f6ff", "#d8dcef", ("#ffffff", 170), "#1a1a24",
                               "#ff9ad5", "#9fd3ff", None, None,
                               None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'cloth', 1: 'cloth', 2: 'glass', 3: 'plain', 4: 'plain'}
    m.box((5, 8, 5), (11, 14, 11), 0)                          # 頭
    m.box((4.4, 4, 4.6), (11.6, 9, 11.4), 0)                   # 身體（被單）
    for (x, z) in ((4.4, 4.6), (7.0, 4.6), (9.6, 4.6), (4.4, 9.2), (9.6, 9.2)):
        m.box((x, 2.4, z), (x + 2.0, 4.0, z + 2.2), 1)         # 下襬的波浪
    m.box((6.0, 10.6, 4.8), (7.2, 12.2, 5.0), 3)               # 黑眼睛
    m.box((8.8, 10.6, 4.8), (10.0, 12.2, 5.0), 3)
    m.box((7.4, 9.0, 4.8), (8.6, 9.8, 5.0), 3)                 # 小嘴（「喔」）
    m.box((5.0, 9.4, 4.8), (6.0, 10.0, 5.0), 4)                # 臉紅
    m.box((10.0, 9.4, 4.8), (11.0, 10.0, 5.0), 4)
    m.box((11.6, 6.4, 7.2), (13.0, 7.6, 8.6), 1)               # 小手
    m.box((3.0, 6.4, 7.2), (4.4, 7.6, 8.6), 1)
    return m


def hw_pet_cat():
    m = Model("hw_pet_cat", ["#141418", "#24242c", "#3a3a46", "#ffd23a",
                             "#ff9ad5", "#8a4fd8", "#e8c050", None,
                             None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'fur', 1: 'fur', 2: 'fur', 4: 'plain', 5: 'cloth', 6: 'metal'}
    m.box((5.2, 2.4, 6.4), (10.8, 8.4, 12.0), 1)               # 坐著的身體
    m.box((5.4, 7.6, 4.2), (10.6, 12.4, 9.2), 1)               # 頭
    m.box((5.6, 12.4, 5.2), (7.2, 14.0, 6.6), 0)               # 耳朵
    m.box((8.8, 12.4, 5.2), (10.4, 14.0, 6.6), 0)
    m.box((5.9, 12.6, 5.1), (6.9, 13.6, 5.2), 4)
    m.box((9.1, 12.6, 5.1), (10.1, 13.6, 5.2), 4)
    m.box((6.0, 9.8, 4.0), (7.4, 11.0, 4.2), 3, glow=True)     # 黃眼睛
    m.box((8.6, 9.8, 4.0), (10.0, 11.0, 4.2), 3, glow=True)
    m.box((6.5, 9.9, 3.95), (6.9, 10.9, 4.0), 0)               # 直瞳
    m.box((9.1, 9.9, 3.95), (9.5, 10.9, 4.0), 0)
    m.box((7.6, 8.8, 4.0), (8.4, 9.4, 4.2), 4)                 # 鼻子
    m.box((5.6, 2.4, 5.0), (7.2, 4.0, 6.4), 2)                 # 前腳
    m.box((8.8, 2.4, 5.0), (10.4, 4.0, 6.4), 2)
    m.box((10.8, 3.0, 10.4), (12.0, 4.2, 11.6), 1)             # 尾巴繞上來
    m.box((12.0, 3.0, 9.0), (13.2, 9.0, 10.2), 1)
    m.box((11.4, 9.0, 9.0), (12.6, 10.6, 10.2), 2)
    m.box((5.2, 7.0, 4.8), (10.8, 7.6, 5.4), 5)                # 紫色項圈
    m.box((7.4, 6.0, 4.6), (8.6, 7.0, 5.0), 6, glow=True)      # 鈴鐺
    return m


def hw_pet_skull():
    m = Model("hw_pet_skull", ["#e8e0c8", "#c8bea0", "#8a826a", "#1a1a14",
                               "#3aff7a", "#b8ffd0", None, None,
                               None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'stone', 1: 'stone', 2: 'stone', 3: 'plain'}
    m.box((4.4, 7.0, 4.4), (11.6, 13.4, 11.6), 0)              # 頭骨
    m.box((5.0, 13.4, 5.0), (11.0, 14.0, 11.0), 1)
    m.box((5.4, 4.8, 5.0), (10.6, 7.0, 9.6), 1)                # 下巴
    m.box((5.4, 9.0, 4.2), (7.6, 11.2, 4.4), 3)                # 眼窩
    m.box((8.4, 9.0, 4.2), (10.6, 11.2, 4.4), 3)
    m.box((5.9, 9.5, 4.15), (7.1, 10.7, 4.2), 4, glow=True)    # 綠色的魂火
    m.box((8.9, 9.5, 4.15), (10.1, 10.7, 4.2), 4, glow=True)
    m.box((7.4, 7.8, 4.2), (8.6, 8.8, 4.4), 3)                 # 鼻孔
    for x in (5.8, 7.0, 8.2, 9.4):
        m.box((x, 6.2, 4.8), (x + 0.8, 7.2, 5.0), 0)           # 牙
    m.box((7.4, 2.0, 7.4), (8.6, 4.4, 8.6), 5, glow=True)      # 底下拖著的魂火
    m.box((7.8, 0.6, 7.8), (8.2, 2.0, 8.2), 4, glow=True)
    return m


def hw_pumpkin_scythe():
    m = Model("hw_pumpkin_scythe", ["#1a120c", "#3a2a1a", "#5a4430", "#c8c8d0",
                                    "#ffffff", "#e2761a", "#ffd23a", "#2e6a1e",
                                    "#8a8a98", None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'wood', 1: 'wood', 2: 'wood', 3: 'metal', 4: 'metal', 5: 'leather', 7: 'plain', 8: 'metal'}
    m.box((7.5, -4.5, 7.5), (8.5, 16.5, 8.5), 1)               # 彎彎曲曲的木柄
    m.box((7.2, -5.0, 7.2), (8.8, -4.2, 8.8), 2)
    for y in (0, 5, 10):
        m.box((7.3, y, 7.3), (8.7, y + 0.6, 8.7), 7)           # 纏著的藤
    m.box((6.6, 15.6, 6.6), (9.4, 18.2, 9.4), 5)               # 柄頂的小南瓜
    m.box((7.0, 16.4, 6.5), (7.8, 17.2, 6.6), 6, glow=True)
    m.box((8.2, 16.4, 6.5), (9.0, 17.2, 6.6), 6, glow=True)
    m.box((7.6, 18.2, 7.6), (8.4, 19.0, 8.4), 7)
    def X(x):
        return 16 - x
    def mbox(p, q, color, glow=False):
        m.box((min(X(p[0]), X(q[0])), p[1], p[2]), (max(X(p[0]), X(q[0])), q[1], q[2]), color, glow=glow)
    mbox((9, 14.6, 7.7), (13.6, 16.0, 8.3), 3)                 # 彎月形的刃
    mbox((13.0, 12.8, 7.7), (15.6, 14.8, 8.3), 3)
    mbox((15.0, 10.2, 7.75), (16.8, 13.2, 8.25), 3)
    mbox((15.8, 8.0, 7.8), (17.0, 10.4, 8.2), 3)
    mbox((16.2, 6.6, 7.85), (16.8, 8.2, 8.15), 4)
    mbox((9, 14.2, 7.75), (13.0, 14.6, 8.25), 6, glow=True)    # 刃內緣的燈火
    mbox((13.0, 12.4, 7.75), (15.0, 12.8, 8.25), 6, glow=True)
    mbox((14.6, 10.0, 7.8), (15.0, 12.4, 8.2), 6, glow=True)
    m.tilt()
    return m


def hw_broom_staff():
    m = Model("hw_broom_staff", ["#3a2410", "#6a4420", "#a8743a", "#d8b060",
                                 "#8a4fd8", "#d8a8ff", "#3aff7a", None,
                                 None, None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'wood', 1: 'wood', 2: 'straw', 3: 'straw', 4: 'cloth'}
    m.box((7.5, -4.0, 7.5), (8.5, 13.0, 8.5), 1)               # 掃帚柄（倒過來拿：掃帚頭在上面）
    m.box((7.2, -4.6, 7.2), (8.8, -3.8, 8.8), 0)
    m.box((7.2, 12.4, 7.2), (8.8, 13.6, 8.8), 4)               # 紫色綁繩
    m.box((6.4, 13.6, 6.4), (9.6, 15.0, 9.6), 3)               # 草束
    m.box((5.6, 15.0, 5.6), (10.4, 18.0, 10.4), 2)
    m.box((5.0, 18.0, 5.0), (11.0, 20.6, 11.0), 3)
    for (x, z) in ((5.2, 5.2), (10.0, 5.2), (5.2, 10.0), (10.0, 10.0), (7.6, 4.8), (7.6, 10.4)):
        m.box((x, 20.6, z), (x + 0.8, 21.8, z + 0.8), 2)       # 散開的草尖
    m.box((7.0, 21.2, 7.0), (9.0, 23.0, 9.0), 5, glow=True)    # 冒出來的紫色火花
    m.box((7.6, 23.0, 7.6), (8.4, 24.0, 8.4), 6, glow=True)
    m.box((10.4, 19.6, 7.6), (11.2, 20.4, 8.4), 5, glow=True)
    m.box((4.8, 18.8, 7.6), (5.6, 19.6, 8.4), 5, glow=True)
    m.tilt()
    return m


def hw_candy_cane():
    m = Model("hw_candy_cane", ["#ffffff", "#e8202a", "#ff8a9a", "#2e9a3e",
                                "#ffd23a", None, None, None,
                                None, None, None, None, None, None, None, None], "weapon")
    m.materials = {0: 'plain', 1: 'plain', 2: 'plain', 3: 'cloth'}
    for i, y in enumerate(range(-4, 15, 2)):
        m.box((7.3, y, 7.3), (8.7, y + 2, 8.7), 0 if i % 2 == 0 else 1)   # 紅白相間的棒
    m.box((7.3, 15.0, 7.3), (8.7, 17.4, 8.7), 1)               # 往 -x 彎的勾
    m.box((5.4, 17.0, 7.3), (8.7, 18.6, 8.7), 0)
    m.box((3.6, 16.4, 7.3), (5.8, 18.2, 8.7), 1)
    m.box((3.2, 14.0, 7.3), (4.8, 16.6, 8.7), 0)
    m.box((3.4, 12.6, 7.4), (4.6, 14.0, 8.6), 1)
    m.box((6.8, 2.0, 6.8), (9.2, 3.4, 9.2), 3)                 # 綠色緞帶
    m.box((9.2, 1.4, 7.6), (10.8, 3.0, 8.4), 3)
    m.box((5.2, 1.4, 7.6), (6.8, 3.0, 8.4), 3)
    m.box((7.6, 17.6, 6.8), (8.4, 18.4, 7.3), 4, glow=True)    # 小星星
    m.tilt()
    return m


def hw_ghost_lantern():
    m = Model("hw_ghost_lantern", ["#1a1a20", "#3a3a46", "#6a6a7a", ("#3aff7a", 200),
                                   "#b8ffd0", "#ffffff", None, None,
                                   None, None, None, None, None, None, None, None], "offhand")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal', 3: 'glass'}
    m.box((5.0, 2.0, 5.0), (11.0, 3.0, 11.0), 1)               # 燈座
    m.box((5.4, 12.0, 5.4), (10.6, 13.0, 10.6), 1)             # 燈頂
    m.box((6.6, 13.0, 6.6), (9.4, 14.2, 9.4), 2)
    m.box((7.4, 14.2, 7.6), (8.6, 16.6, 8.4), 0)               # 提把
    m.box((6.2, 16.0, 7.6), (9.8, 16.8, 8.4), 0)
    for (x, z) in ((5.2, 5.2), (10.0, 5.2), (5.2, 10.0), (10.0, 10.0)):
        m.box((x, 3.0, z), (x + 0.8, 12.0, z + 0.8), 0)        # 四根柱
    m.box((6.6, 4.6, 6.6), (9.4, 9.4, 9.4), 4, glow=True)      # 綠色鬼火
    m.box((7.2, 9.4, 7.2), (8.8, 11.0, 8.8), 5, glow=True)
    m.box((5.6, 3.0, 5.6), (10.4, 12.0, 10.4), 3, glow=True)   # 半透明的燈罩
    return m


def hw_coffin_shield():
    m = Model("hw_coffin_shield", ["#2a1a12", "#4a2e1e", "#6a4430", "#8a8a98",
                                   "#e8e0c8", "#e2761a", "#ffd23a", None,
                                   None, None, None, None, None, None, None, None], "offhand")
    m.materials = {0: 'wood', 1: 'wood', 2: 'wood', 3: 'metal', 4: 'stone', 5: 'plain'}
    # 棺材形：上窄、肩寬、下窄（做在 x-y 平面上，z 是厚度）
    m.box((5.0, 15.0, 7.0), (11.0, 17.0, 9.0), 1)
    m.box((3.0, 9.0, 7.0), (13.0, 15.0, 9.0), 1)
    m.box((4.0, 2.0, 7.0), (12.0, 9.0, 9.0), 1)
    m.box((5.0, 0.6, 7.0), (11.0, 2.0, 9.0), 1)
    m.box((7.4, 3.0, 6.7), (8.6, 14.6, 7.0), 3)                # 鐵十字
    m.box((5.0, 11.0, 6.7), (11.0, 12.2, 7.0), 3)
    for (x, y) in ((3.6, 14.0), (11.6, 14.0), (4.6, 2.6), (10.6, 2.6)):
        m.box((x, y, 6.8), (x + 0.8, y + 0.8, 7.0), 3)         # 鉚釘
    m.box((7.0, 12.6, 6.6), (9.0, 14.4, 6.7), 4)               # 小骷髏
    m.box((7.3, 13.4, 6.55), (7.8, 13.9, 6.6), 6, glow=True)
    m.box((8.2, 13.4, 6.55), (8.7, 13.9, 6.6), 6, glow=True)
    return m


def hw_bat_bow_extras(m, flex):
    for sign in (1, -1):
        y = 8 + sign * 6.0
        m.box((9.0 - flex * 0.2, y - 1.2, 7.8), (12.6 - flex * 0.2, y + 0.4, 8.2), 8)   # 弓臂上張開的小蝠翼
        m.box((12.0 - flex * 0.2, y - 2.0, 7.8), (13.0 - flex * 0.2, y + 0.4, 8.2), 8)
    m.box((8.6, 6.8, 6.8), (10.0, 9.2, 9.2), 9, glow=True)                  # 握把上的紅眼
    m.box((10.0, 7.6, 7.6), (11.0, 8.4, 8.4), 5, glow=True)


def hw_pumpkin_crossbow_extras(m):
    m.box((6.2, 6.4, 6.6), (9.6, 9.6, 9.4), 9)                 # 弩身上的小南瓜
    m.box((6.8, 7.4, 6.5), (7.6, 8.2, 6.6), 5, glow=True)
    m.box((8.2, 7.4, 6.5), (9.0, 8.2, 6.6), 5, glow=True)
    m.box((7.6, 9.6, 7.6), (8.2, 10.6, 8.4), 3)
    m.box((0.6, 8.6, 7.6), (2.4, 9.6, 8.4), 5, glow=True)


# ======================================================================
# 2026-11-03 照站長：夏日系列（抽獎箱）。海藍、沙金、椰綠、西瓜紅、珊瑚粉、日落橘，每一件都要一眼看得出是什麼。
# 武器一樣直的做（握把在下、尖端在上、有方向的部分放 -x 側），最後斜 45 度；翅膀右邊一片、左邊鏡射；寵物中心 8,8,8 臉朝 north。
# ======================================================================

SUMMER_SEA, SUMMER_DEEP, SUMMER_FOAM = "#1ea7d8", "#0d5c8a", "#7fe0ff"
SUMMER_SAND, SUMMER_SAND_DARK, SUMMER_SAND_LIGHT = "#e8c56a", "#b8903a", "#ffe08a"
SUMMER_GREEN, SUMMER_GREEN_DARK, SUMMER_GREEN_LIGHT = "#3aa655", "#1e6a30", "#8fd86a"
SUMMER_RED, SUMMER_RED_DARK, SUMMER_CORAL, SUMMER_ORANGE = "#ff4d5a", "#c8202e", "#ff7f9f", "#ff8c2a"
SUMMER_WOOD, SUMMER_WOOD_LIGHT = "#6a4420", "#d8a868"


def _pad(colors):
    return list(colors) + [None] * (16 - len(colors))


# 2026-11-03 照站長：夏日系列
def summer_surfboard():
    """衝浪板巨劍（傳說）：寬板、板尾三片鰭、橫向的彩色條紋、中間一條木色龍骨；握把在板尾下面。"""
    m = Model("summer_surfboard", _pad([SUMMER_SEA, "#ffffff", SUMMER_ORANGE, SUMMER_RED, SUMMER_SAND,
                                        SUMMER_WOOD, SUMMER_DEEP, SUMMER_SAND_LIGHT, SUMMER_FOAM, SUMMER_CORAL]), "weapon")
    m.materials = {0: 'stripe:h', 1: 'plain', 2: 'plain', 3: 'plain', 4: 'leather', 5: 'wood', 6: 'plain', 8: 'plain', 9: 'plain'}
    m.box((7.2, -4.6, 7.2), (8.8, -4, 8.8), 4)               # 柄頭
    m.box((7.5, -4, 7.5), (8.5, 1.6, 8.5), 5)                # 握把（綁在板尾下面）
    m.box((7.3, -3, 7.3), (8.7, -2.4, 8.7), 4)
    m.box((7.3, -1, 7.3), (8.7, -0.4, 8.7), 4)
    m.box((5.2, 1.4, 7.45), (10.8, 3, 8.55), 0)              # 板尾（收窄）
    m.box((4.6, 3, 7.4), (11.4, 16, 8.6), 0)                 # 板身
    m.box((5.2, 16, 7.45), (10.8, 18.6, 8.55), 0)            # 板頭漸漸收尖
    m.box((6, 18.6, 7.5), (10, 20.4, 8.5), 0)
    m.box((7, 20.4, 7.55), (9, 21.6, 8.45), 1)               # 板尖（白）
    m.box((4.5, 5.6, 7.3), (11.5, 7.2, 8.7), 2)              # 橫向條紋：橘、白、紅、白、珊瑚
    m.box((4.5, 7.2, 7.3), (11.5, 8.0, 8.7), 1)
    m.box((4.5, 10.6, 7.3), (11.5, 12.2, 8.7), 3)
    m.box((4.5, 12.2, 7.3), (11.5, 13.0, 8.7), 1)
    m.box((5.1, 16.6, 7.35), (10.9, 17.4, 8.65), 9)
    m.box((7.7, 1.6, 7.25), (8.3, 20.2, 8.75), 5)            # 龍骨（木色）
    m.box((4.4, 3.4, 7.6), (4.6, 15.6, 8.4), 8)              # 板緣亮邊
    m.box((11.4, 3.4, 7.6), (11.6, 15.6, 8.4), 8)
    m.box((7.3, 2, 8.6), (8.7, 5.8, 10.8), 6)                # 板尾的中鰭（往 +z 凸出）
    m.box((7.5, 4.4, 10.8), (8.5, 5.8, 11.6), 6)
    m.box((5.1, 3, 8.6), (5.9, 5.4, 9.9), 6)                 # 兩片側鰭
    m.box((10.1, 3, 8.6), (10.9, 5.4, 9.9), 6)
    m.tilt()
    return m


# 2026-11-03 照站長：夏日系列
def summer_watermelon_hammer():
    """西瓜大鎚（史詩）：深綠條紋的圓鎚頭，-x 那一側切開露出紅瓤與黑籽；頂上留著一小截藤。"""
    m = Model("summer_watermelon_hammer", _pad([SUMMER_GREEN_DARK, SUMMER_GREEN, SUMMER_GREEN_LIGHT, SUMMER_RED, "#ffffff",
                                                SUMMER_WOOD, "#1a1a1a", SUMMER_SAND, "#ff8090", "#f0f8e0"]), "weapon")
    m.materials = {0: 'stripe:v', 1: 'stripe:v', 2: 'plain', 3: 'plain', 4: 'plain', 5: 'wood', 6: 'plain', 7: 'leather', 8: 'plain', 9: 'plain'}
    m.box((7.4, -4, 7.4), (8.6, 11.4, 8.6), 5)               # 握柄
    m.box((7.0, -4.8, 7.0), (9.0, -4, 9.0), 7)
    m.box((7.2, 0, 7.2), (8.8, 0.8, 8.8), 7)
    m.box((7.2, 5, 7.2), (8.8, 5.8, 8.8), 7)
    m.box((5.0, 12, 5.2), (12.5, 19, 10.8), 1)               # 鎚頭（圓西瓜：中段寬、上下收）
    m.box((5.6, 11, 4.2), (11.5, 20, 11.8), 1)
    m.box((6.2, 10.4, 5.5), (10.5, 20.6, 10.5), 1)
    for x in (6.2, 8.4, 10.6):                               # 深綠條紋（繞著 x 軸一圈一圈）
        m.box((x, 10.3, 4.1), (x + 0.9, 20.7, 11.9), 0)
        m.box((x, 11.9, 5.1), (x + 0.9, 19.1, 10.9), 0)
    # -x 那一端切開：最後 1.5 格整段是紅瓤（側面也看得到紅的），跟綠皮之間一圈白色的果皮內層，切面邊緣也鑲白
    m.box((3.4, 12, 5.2), (5.0, 19, 10.8), 3)                # 紅瓤
    m.box((5.0, 11.9, 5.1), (5.4, 19.1, 10.9), 4)            # 白色果皮內層（一圈）
    m.box((3.3, 12, 5.2), (3.4, 19, 5.7), 4)                 # 切面四邊的白邊
    m.box((3.3, 12, 10.3), (3.4, 19, 10.8), 4)
    m.box((3.3, 12, 5.7), (3.4, 12.5, 10.3), 4)
    m.box((3.3, 18.5, 5.7), (3.4, 19, 10.3), 4)
    m.box((3.2, 14.4, 7.4), (3.4, 16.6, 8.6), 8)             # 切面中心最甜的一塊
    for (y, z) in ((13.6, 6.6), (14.8, 9.0), (16.4, 6.8), (17.4, 8.6), (15.6, 7.8), (13.4, 8.4)):
        m.box((3.25, y, z), (3.4, y + 0.7, z + 0.5), 6)      # 切面上的黑籽
    for (x, y) in ((3.7, 13.8), (4.4, 16.2), (3.9, 17.4)):
        m.box((x, y, 10.8), (x + 0.5, y + 0.7, 10.9), 6)     # 側面果肉上的籽
        m.box((x, y, 5.1), (x + 0.5, y + 0.7, 5.2), 6)
    m.box((7.3, 20.6, 7.3), (8.7, 22.2, 8.7), 0)             # 藤蒂
    m.box((8.7, 21.4, 7.6), (11, 22, 8.4), 2)                # 捲鬚
    m.box((10.6, 22, 7.6), (11.4, 23, 8.4), 2)
    m.tilt()
    return m


# 2026-11-03 照站長：夏日系列
def summer_coconut_staff():
    """椰影長杖（史詩）：棕色杖身，頂端一顆毛毛的椰子（三個眼）、四片葉子往外垂，葉子中間一點海藍的光。"""
    m = Model("summer_coconut_staff", _pad(["#4a2e18", SUMMER_WOOD, "#a8743a", SUMMER_GREEN_DARK, SUMMER_GREEN, SUMMER_GREEN_LIGHT,
                                            SUMMER_FOAM, "#d8fbff", SUMMER_SAND, "#8a6a40"]), "weapon")
    m.materials = {0: 'fur', 1: 'wood', 2: 'wood', 3: 'feather', 4: 'feather', 5: 'feather', 8: 'straw', 9: 'fur'}
    m.box((7.4, -5, 7.4), (8.6, 13.5, 8.6), 1)               # 杖身
    m.box((7.1, -5.6, 7.1), (8.9, -4.8, 8.9), 0)
    for y in (-1, 4, 9):
        m.box((7.2, y, 7.2), (8.8, y + 0.7, 8.8), 8)         # 綁著的草繩
    m.box((5.6, 13, 5.6), (10.4, 18, 10.4), 1)               # 椰子
    m.box((5.2, 14, 6.2), (10.8, 17, 9.8), 0)                # 毛毛的（凸出來的深色塊）
    m.box((6.2, 14, 5.2), (9.8, 17, 10.8), 0)
    m.box((6.6, 13.2, 6.6), (9.4, 13.8, 9.4), 9)
    m.box((6.4, 15.6, 5.0), (7.2, 16.4, 5.2), 0)             # 三個眼（正面）
    m.box((8.8, 15.6, 5.0), (9.6, 16.4, 5.2), 0)
    m.box((7.6, 14.2, 5.0), (8.4, 15.0, 5.2), 0)
    m.box((7, 18, 7), (9, 19.6, 9), 6, glow=True)            # 葉子中間的海藍光
    m.box((7.5, 19.6, 7.5), (8.5, 20.4, 8.5), 7, glow=True)
    # 四片葉子往外垂（武器不能用轉的：一階一階排下去）
    for i, (d0, d1, y) in enumerate(((0, 3.0, 18.6), (2.6, 5.4, 17.8), (5.0, 7.2, 16.9), (6.8, 8.4, 15.9))):
        c = 4 if i % 2 == 0 else 5
        m.box((8 + d0, y, 7.5), (8 + d1, y + 0.9, 8.5), c)          # +x
        m.box((8 - d1, y, 7.5), (8 - d0, y + 0.9, 8.5), c)          # -x
        m.box((7.5, y, 8 + d0), (8.5, y + 0.9, 8 + d1), 3 if c == 4 else 4)   # +z
        m.box((7.5, y, 8 - d1), (8.5, y + 0.9, 8 - d0), 3 if c == 4 else 4)   # -z
    for (d0, d1, y) in ((1.4, 2.6, 19.5), (3.8, 5.0, 18.7)):
        m.box((8 + d0, y, 7.7), (8 + d1, y + 0.3, 8.3), 5)          # 葉脈亮邊
        m.box((8 - d1, y, 7.7), (8 - d0, y + 0.3, 8.3), 5)
    m.tilt()
    return m


# 2026-11-03 照站長：夏日系列
def summer_popsicle_blade():
    """冰棒劍（稀有）：木棍握把，下半西瓜紅、上半海藍的雙色冰棒，-x 側上面被咬掉一口；藍的那半有一點霜。"""
    m = Model("summer_popsicle_blade", _pad([SUMMER_WOOD_LIGHT, SUMMER_RED, "#ff8090", SUMMER_SEA, SUMMER_FOAM,
                                             "#ffffff", "#1a1a1a", SUMMER_SAND_LIGHT, SUMMER_RED_DARK]), "weapon")
    m.materials = {0: 'wood', 1: 'stripe:h', 2: 'plain', 3: 'stripe:h', 4: 'plain', 5: 'plain', 6: 'plain'}
    m.box((7.3, -4.4, 7.7), (8.7, 3.6, 8.3), 0)              # 木棍（扁的）
    m.box((7.0, -4.6, 7.65), (9.0, -3.6, 8.35), 0)
    m.box((5, 3, 7.3), (11, 10.5, 8.7), 1)                   # 下半：西瓜紅
    m.box((5.4, 2.4, 7.5), (6.4, 3, 8.5), 2)                 # 融化滴下來
    m.box((9.6, 2.0, 7.5), (10.4, 3, 8.5), 2)
    for (y, z) in ((5, 7.2), (7.4, 8.65), (9, 7.2)):
        m.box((6.2, y, z), (6.9, y + 0.6, z + 0.15), 6)      # 紅的那半的西瓜籽
        m.box((8.8, y + 0.8, z), (9.5, y + 1.4, z + 0.15), 6)
    m.box((5, 10.5, 7.3), (11, 14, 8.7), 3)                  # 上半：海藍
    m.box((7, 14, 7.3), (11, 18, 8.7), 3)                    # 被咬掉一口（-x 側缺一塊）
    m.box((6.2, 13.4, 7.35), (7, 14.4, 8.65), 3)             # 咬痕的鋸齒
    m.box((6.6, 14.4, 7.35), (7, 15.4, 8.65), 3)
    m.box((7.4, 18, 7.4), (10.6, 19.2, 8.6), 3)              # 圓頂
    m.box((8.2, 19.2, 7.5), (9.8, 19.9, 8.5), 4)
    m.box((5.0, 10.4, 7.25), (11.0, 10.8, 8.75), 5)          # 兩色交界的白線
    m.box((8.0, 11.4, 7.2), (8.6, 12.0, 7.3), 5, glow=True)  # 霜
    m.box((9.6, 15.6, 7.2), (10.2, 16.2, 7.3), 5, glow=True)
    m.box((8.4, 16.8, 8.7), (9.0, 17.4, 8.8), 5, glow=True)
    m.tilt()
    return m


# 2026-11-03 照站長：夏日系列
def summer_parasol_shield():
    """遮陽傘盾（史詩）：圓傘面、紅白八向輻射條紋、頂上一顆珠、下面一截木柄（盾、箭袋都能套）。"""
    m = Model("summer_parasol_shield", _pad(["#ffffff", SUMMER_RED, SUMMER_RED_DARK, SUMMER_WOOD_LIGHT, SUMMER_SAND,
                                             SUMMER_CORAL, SUMMER_WOOD]), "offhand")
    m.materials = {0: 'stripe:v:#ffe6e6', 1: 'cloth', 2: 'cloth', 3: 'wood', 4: 'metal', 5: 'cloth', 6: 'wood'}
    # 圓傘面：正八角形 = 四條一樣的長方形（兩條直的、兩條轉 45 度）疊起來，半徑 6.6；z 各差一點免得同一面打架
    m.box((1.4, 5.3, 7.2), (14.6, 10.7, 8.8), 0)
    m.box((5.3, 1.4, 7.22), (10.7, 14.6, 8.78), 0)
    m.box((1.4, 5.3, 7.24), (14.6, 10.7, 8.76), 0, rot=(45, "z", (8, 8, 8)))
    m.box((5.3, 1.4, 7.26), (10.7, 14.6, 8.74), 0, rot=(45, "z", (8, 8, 8)))
    m.box((7.2, 2.0, 7.1), (8.8, 14.0, 8.9), 1)              # 紅色的輻射條紋（直、橫、兩斜；在傘面裡面）
    m.box((2.0, 7.2, 7.12), (14.0, 8.8, 8.88), 1)
    m.box((7.2, 2.0, 7.14), (8.8, 14.0, 8.86), 1, rot=(45, "z", (8, 8, 8)))
    m.box((7.2, 2.0, 7.16), (8.8, 14.0, 8.84), 1, rot=(-45, "z", (8, 8, 8)))
    for i in range(8):                                       # 傘緣八個尖之間垂下來的小片（深紅）
        ang = math.radians(i * 45 + 22.5)
        cx, cy = 8 + 6.8 * math.cos(ang), 8 + 6.8 * math.sin(ang)
        m.box((cx - 0.6, cy - 0.6, 7.35), (cx + 0.6, cy + 0.6, 8.65), 2, rot=(45, "z", (cx, cy, 8)))
    m.box((7.3, 14.6, 7.3), (8.7, 16.0, 8.7), 4)             # 傘頂的珠
    m.box((7.5, 8, 6.4), (8.5, 8.6, 9.6), 3)                 # 傘骨中心
    m.box((7.5, -1.2, 7.5), (8.5, 1.6, 8.5), 3)              # 木柄
    m.box((6, -2, 7.5), (8.5, -1.2, 8.5), 6)                 # 彎勾
    return m


# 2026-11-03 照站長：夏日系列
def summer_lifebuoy():
    """救生圈盾（稀有）：橘白相間的胖環，中間是空的，外面繞一圈繩子、四個結（盾、箭袋都能套）。"""
    m = Model("summer_lifebuoy", _pad([SUMMER_ORANGE, "#ffffff", "#d86a10", SUMMER_SAND, SUMMER_SAND_DARK, SUMMER_RED]), "offhand")
    m.materials = {0: 'stripe:h', 1: 'stripe:h', 2: 'plain', 3: 'straw', 4: 'straw', 5: 'cloth'}
    m.box((4.6, 11.8, 6.8), (11.4, 14.4, 9.2), 0)            # 四段直的（橘）
    m.box((4.6, 1.6, 6.8), (11.4, 4.2, 9.2), 0)
    m.box((1.6, 4.6, 6.8), (4.2, 11.4, 9.2), 0)
    m.box((11.8, 4.6, 6.8), (14.4, 11.4, 9.2), 0)
    for (cx, cy, ang) in ((12.0, 12.0, -45), (4.0, 12.0, 45), (4.0, 4.0, -45), (12.0, 4.0, 45)):
        m.box((cx - 3.0, cy - 1.3, 6.9), (cx + 3.0, cy + 1.3, 9.1), 1, rot=(ang, "z", (cx, cy, 8)))   # 四段斜的（白）
    m.box((2.8, 14.6, 7.7), (13.2, 15.0, 8.3), 3)            # 外圈的繩子
    m.box((2.8, 1.0, 7.7), (13.2, 1.4, 8.3), 3)
    m.box((1.0, 2.8, 7.7), (1.4, 13.2, 8.3), 3)
    m.box((14.6, 2.8, 7.7), (15.0, 13.2, 8.3), 3)
    for (cx, cy, ang) in ((13.2, 13.2, -45), (2.8, 13.2, 45), (2.8, 2.8, -45), (13.2, 2.8, 45)):
        m.box((cx - 1.6, cy - 0.2, 7.7), (cx + 1.6, cy + 0.2, 8.3), 3, rot=(ang, "z", (cx, cy, 8)))
    for (x, y) in ((8, 14.6), (8, 1.0), (1.0, 8), (15.0, 8)):
        m.box((x - 0.7, y - 0.5, 7.5), (x + 0.7, y + 0.5, 8.5), 4)      # 繩結
    m.box((4.0, 2.2, 6.6), (6.0, 3.6, 7.0), 5)               # 底下的小標籤
    return m


# 2026-11-03 照站長：夏日系列
def summer_straw_hat():
    """草帽（史詩）：寬寬的八角帽簷、帽身、海藍帽帶，正面偏右別一朵珊瑚粉的小花。"""
    m = Model("summer_straw_hat", _pad([SUMMER_SAND_DARK, SUMMER_SAND, SUMMER_SAND_LIGHT, SUMMER_SEA, SUMMER_CORAL,
                                        "#ffe13a", "#ffffff", SUMMER_GREEN]), "headgear")
    m.materials = {0: 'straw', 1: 'straw', 2: 'straw', 3: 'cloth', 4: 'plain', 5: 'plain', 7: 'feather'}
    m.box((-2.4, 13.0, 1.6), (18.4, 13.8, 14.4), 1)          # 帽簷（三塊疊成八角；y 各差一點免得同一面打架）
    m.box((1.6, 13.04, -2.4), (14.4, 13.76, 18.4), 1)
    m.box((-0.6, 13.08, -0.6), (16.6, 13.72, 16.6), 1)
    m.box((-2.0, 13.82, 2.6), (18.0, 13.9, 13.4), 0)         # 簷上一圈深色的草紋
    m.box((2.6, 13.84, -2.0), (13.4, 13.92, 18.0), 0)
    m.box((1.4, 13.9, 1.4), (14.6, 18.8, 14.6), 1)           # 帽身（高一點，才看得出是帽子）
    m.box((2.4, 18.8, 2.4), (13.6, 19.8, 13.6), 2)           # 帽頂
    m.box((5.0, 19.6, 5.0), (11.0, 19.9, 11.0), 1)           # 頂上壓一個凹（深一點的那圈）
    m.box((1.2, 14.4, 1.2), (14.8, 15.8, 14.8), 3)           # 海藍帽帶
    m.box((1.3, 17.2, 1.3), (14.7, 17.5, 14.7), 0)           # 帽身的草紋
    m.box((10.6, 14.2, 0.5), (13.6, 16.4, 1.2), 4)           # 小花：四瓣＋黃花心
    m.box((11.6, 13.4, 0.6), (12.6, 14.2, 1.1), 4)
    m.box((11.6, 16.4, 0.6), (12.6, 17.2, 1.1), 4)
    m.box((11.6, 14.8, 0.3), (12.6, 15.8, 0.5), 5)
    m.box((13.4, 14.6, 0.7), (14.8, 15.4, 1.1), 7)           # 葉子
    return m


# 2026-11-03 照站長：夏日系列
def summer_goggles():
    """潛水鏡（稀有）：藍框雙鏡片（鏡片發一點光）、黑色頭帶，橘色呼吸管從嘴邊往右上繞到頭頂。"""
    m = Model("summer_goggles", _pad([SUMMER_DEEP, SUMMER_SEA, SUMMER_FOAM, SUMMER_ORANGE, "#1a1a1a", "#ffffff", "#d86a10"]), "headgear")
    m.materials = {0: 'plain', 1: 'plain', 2: 'glass', 3: 'plain', 4: 'cloth', 6: 'plain'}
    m.box((2.4, 7.4, 0.6), (7.6, 11.8, 1.4), 1)              # 左鏡框
    m.box((8.4, 7.4, 0.6), (13.6, 11.8, 1.4), 1)             # 右鏡框
    m.box((3.1, 8.1, 0.4), (6.9, 11.1, 0.6), 2, glow=True)   # 鏡片
    m.box((9.1, 8.1, 0.4), (12.9, 11.1, 0.6), 2, glow=True)
    m.box((3.5, 10.2, 0.3), (4.6, 10.7, 0.4), 5, glow=True)  # 鏡片上的反光
    m.box((9.5, 10.2, 0.3), (10.6, 10.7, 0.4), 5, glow=True)
    m.box((7.6, 8.6, 0.7), (8.4, 10.4, 1.3), 0)              # 鼻梁
    m.box((2.4, 7.4, 0.6), (13.6, 7.8, 1.4), 0)              # 框的下緣（深藍）
    m.box((1.4, 9.0, 1.4), (2.4, 10.4, 14.6), 4)             # 頭帶
    m.box((13.6, 9.0, 1.4), (14.6, 10.4, 14.6), 4)
    m.box((1.4, 9.0, 14.2), (14.6, 10.4, 14.8), 4)
    m.box((9.6, 4.6, 0.5), (11.8, 6.0, 1.4), 3)              # 呼吸管：咬嘴
    m.box((11.6, 4.8, 0.7), (15.2, 5.8, 1.5), 1)             # 往右
    m.box((14.4, 4.8, 0.7), (15.6, 20.0, 1.7), 1)            # 右側往上
    m.box((14.3, 11.0, 0.6), (15.7, 11.8, 1.8), 0)           # 固定環
    m.box((14.3, 16.0, 0.6), (15.7, 16.8, 1.8), 0)
    m.box((14.1, 20.0, 0.4), (15.9, 21.6, 2.0), 3)           # 管口（橘）
    m.box((14.4, 21.6, 0.7), (15.6, 22.0, 1.7), 6)
    return m


# 2026-11-03 照站長：夏日系列
def summer_shades():
    """太陽眼鏡（稀有）：黑色粗框、深色鏡片上一道發光的反光、鏡腳繞到耳後。"""
    m = Model("summer_shades", _pad(["#1a1a1a", "#2a2a34", "#202838", SUMMER_FOAM, "#ffffff", SUMMER_ORANGE]), "headgear")
    m.materials = {0: 'plain', 1: 'plain', 2: 'glass', 5: 'plain'}
    m.box((2.4, 8.0, 0.6), (7.4, 11.2, 1.4), 0)              # 左框
    m.box((8.6, 8.0, 0.6), (13.6, 11.2, 1.4), 0)             # 右框
    m.box((3.0, 8.5, 0.4), (6.8, 10.7, 0.6), 2)              # 鏡片
    m.box((9.2, 8.5, 0.4), (13.0, 10.7, 0.6), 2)
    m.box((3.6, 9.9, 0.3), (5.4, 10.3, 0.4), 3, glow=True)   # 反光
    m.box((9.8, 9.9, 0.3), (11.6, 10.3, 0.4), 3, glow=True)
    m.box((4.0, 9.3, 0.3), (4.6, 9.7, 0.4), 4, glow=True)
    m.box((10.2, 9.3, 0.3), (10.8, 9.7, 0.4), 4, glow=True)
    m.box((7.4, 9.6, 0.7), (8.6, 10.4, 1.3), 0)              # 鼻梁
    m.box((2.2, 11.2, 0.6), (13.8, 11.9, 1.4), 0)            # 眉條
    m.box((1.4, 10.4, 1.4), (2.2, 11.2, 12.0), 1)            # 鏡腳
    m.box((13.8, 10.4, 1.4), (14.6, 11.2, 12.0), 1)
    m.box((1.4, 9.0, 11.4), (2.2, 10.4, 12.0), 1)            # 勾在耳後
    m.box((13.8, 9.0, 11.4), (14.6, 10.4, 12.0), 1)
    m.box((12.6, 11.9, 0.8), (13.6, 12.5, 1.3), 5)           # 框角一點橘色裝飾
    return m


# 2026-11-03 照站長：夏日系列
def summer_wave(m):
    """浪花之翼（傳說）：一道從肩膀往外捲起來的浪，深藍到淺藍一階一階往上，浪尖往回捲、白沫發光，旁邊飛著水珠。"""
    m.box((8, 6.5, 7.5), (13, 10.5, 8.5), 0)                 # 浪根（深藍）
    m.box((8.5, 4.5, 7.6), (18, 6.5, 8.4), 0)                # 底下的暗水
    m.box((12.5, 7, 7.5), (18, 12.5, 8.5), 1)                # 一階一階往上
    m.box((17.5, 8, 7.5), (22, 14.5, 8.5), 1)
    m.box((21.5, 9.5, 7.5), (25.5, 17, 8.5), 2)
    m.box((25, 11.5, 7.5), (28, 20, 8.5), 2)
    m.box((26.5, 13, 7.6), (28.6, 18.6, 8.4), 3)             # 浪壁最亮的那一片
    m.box((22.5, 19.6, 7.6), (28.4, 21.6, 8.4), 3)           # 浪尖往回捲
    m.box((19.5, 19, 7.6), (23, 21, 8.4), 4, glow=True)      # 白沫
    m.box((17, 17.6, 7.7), (20, 19.4, 8.3), 5, glow=True)
    m.box((12.5, 12.5, 7.7), (14.2, 13.6, 8.3), 4, glow=True)   # 每一階的浪頭白沫
    m.box((17.5, 14.5, 7.7), (19.4, 15.6, 8.3), 4, glow=True)
    m.box((21.5, 17, 7.7), (23.6, 18.2, 8.3), 4, glow=True)
    m.box((25.4, 20, 7.7), (27.6, 21.0, 8.3), 5, glow=True)
    for (x, y) in ((29.6, 18.4), (30.4, 15.2), (15.6, 15.6), (24.2, 23.0), (29.8, 22.2)):
        m.box((x - 0.5, y - 0.5, 7.7), (x + 0.5, y + 0.5, 8.3), 5, glow=True)   # 飛起來的水珠（不轉：翅膀整片要一起揚 22.5 度）
    m.box((9, 7.5, 8.5), (16, 9.5, 9.0), 1)                  # 浪的厚度（後面一層）


# 2026-11-03 照站長：夏日系列
def summer_gull(m):
    """海鷗之翼（史詩）：白色的長翼、羽尖灰到黑（不發光）。"""
    m.box((8, 9, 7.4), (16, 10.6, 8.6), 4)                   # 翼骨（淺灰）
    m.box((15.4, 10, 7.5), (23, 11.6, 8.5), 4)
    m.box((22.4, 10.4, 7.6), (27, 11.8, 8.4), 4)
    for i, x in enumerate(range(9, 29, 2)):
        top = 9.5 + min(i, 6) * 0.25
        length = 4.5 + i * 1.1
        m.box((x, top - length, 7.7 + (i % 2) * 0.1), (x + 2.1, top, 8.3 + (i % 2) * 0.1), 1)       # 白羽
        tip = 3 if i >= 7 else 2
        m.box((x + 0.2, top - length - 1.4, 7.8), (x + 1.9, top - length, 8.2), tip)                # 羽尖：灰、最外面幾根黑
    for i, x in enumerate(range(9, 27, 3)):
        m.box((x, 7.5 + i * 0.3, 8.3), (x + 3, 10.4 + i * 0.3, 8.8), 0)                            # 覆羽（淺灰）
    m.box((26.6, 11.4, 7.7), (28.4, 12.6, 8.3), 3)           # 翼尖黑


# 2026-11-03 照站長：夏日系列
def summer_sun_halo():
    """烈日光輪（史詩）：照聖輪的做法——中間一顆發光的太陽，八道長芒、八道短芒交錯往外射。"""
    m = Model("summer_sun_halo", _pad([SUMMER_ORANGE, "#ffb000", "#ffe13a", "#fff6c0", SUMMER_RED]), "back")
    m.materials = {}
    m.box((4.8, 4.8, 7.4), (11.2, 11.2, 8.6), 2, glow=True)  # 太陽（正方＋轉 45 度 = 八角）
    m.box((4.8, 4.8, 7.44), (11.2, 11.2, 8.56), 2, rot=(45, "z", (8, 8, 8)), glow=True)
    m.box((6.2, 6.2, 7.3), (9.8, 9.8, 8.7), 3, rot=(45, "z", (8, 8, 8)), glow=True)   # 核心更亮
    def ray(ang, r0, r1, w, c):
        """從中心往 ang 度射出去的一道芒：先放在最近的軸（0／90／180／270）上，再轉差的那 ±22.5／45 度。"""
        base = (round(ang / 90) * 90) % 360
        rot = ang - base if ang - base <= 45 else ang - base - 360
        if base in (0, 180):
            a, b = ((8 + r0, 8 - w / 2, 7.5), (8 + r1, 8 + w / 2, 8.5)) if base == 0 else ((8 - r1, 8 - w / 2, 7.5), (8 - r0, 8 + w / 2, 8.5))
        else:
            a, b = ((8 - w / 2, 8 + r0, 7.5), (8 + w / 2, 8 + r1, 8.5)) if base == 90 else ((8 - w / 2, 8 - r1, 7.5), (8 + w / 2, 8 - r0, 8.5))
        m.box(a, b, c, rot=(rot, "z", (8, 8, 8)) if rot else None, glow=True)

    for i in range(8):
        ray(i * 45, 6.4, 13.2, 1.8, 0)                      # 八道長芒（橘）
        ray(i * 45 + 22.5, 6.2, 10.6, 1.2, 1)               # 八道短芒（金）
    return m


# 2026-11-03 照站長：夏日系列
def summer_pet_turtle():
    """小海龜（史詩）：綠色圓殼、殼上深色的格紋、沙色的肚子，頭往前探、四片鰭往外張。"""
    m = Model("summer_pet_turtle", _pad([SUMMER_GREEN_DARK, SUMMER_GREEN, SUMMER_GREEN_LIGHT, SUMMER_SAND, "#1a1a1a",
                                         SUMMER_FOAM, "#ffffff", "#7a6a30"]), "pet")
    m.materials = {0: 'scale', 1: 'scale', 2: 'scale', 3: 'plain', 4: 'plain', 7: 'plain'}
    m.box((4.4, 6, 4.8), (11.6, 9.8, 13.2), 1)               # 殼
    m.box((5.2, 9.8, 5.8), (10.8, 11.2, 12.2), 1)
    m.box((6.2, 11.2, 7.0), (9.8, 11.8, 11.0), 0)            # 殼頂的格紋
    m.box((5.2, 10.2, 6.2), (6.0, 10.8, 11.8), 0)
    m.box((10.0, 10.2, 6.2), (10.8, 10.8, 11.8), 0)
    m.box((5.4, 10.8, 8.4), (10.6, 11.3, 9.4), 0)
    m.box((4.2, 7.2, 5.2), (11.8, 7.8, 12.8), 0)             # 殼邊的那一圈
    m.box((5.0, 5.4, 5.4), (11.0, 6, 12.6), 3)               # 肚子（沙色）
    m.box((6.2, 6.4, 1.8), (9.8, 9.4, 5.2), 2)               # 頭
    m.box((6.6, 9.4, 2.4), (9.4, 9.8, 4.6), 2)
    m.box((6.3, 8.0, 1.6), (7.1, 8.8, 1.8), 4)               # 眼睛
    m.box((8.9, 8.0, 1.6), (9.7, 8.8, 1.8), 4)
    m.box((7.4, 6.8, 1.6), (8.6, 7.2, 1.8), 0)               # 嘴
    m.box((1.6, 5.8, 4.6), (4.6, 6.8, 7.6), 2, rot=(22.5, "y", (4.6, 6.3, 6.1)))     # 前鰭
    m.box((11.4, 5.8, 4.6), (14.4, 6.8, 7.6), 2, rot=(-22.5, "y", (11.4, 6.3, 6.1)))
    m.box((2.6, 5.8, 10.6), (4.6, 6.8, 12.8), 2)             # 後鰭
    m.box((11.4, 5.8, 10.6), (13.4, 6.8, 12.8), 2)
    m.box((7.4, 6.2, 13.2), (8.6, 7.0, 14.6), 2)             # 小尾巴
    m.box((11.6, 12.0, 4.4), (12.4, 12.8, 5.2), 5, glow=True)   # 吐出來的水泡
    m.box((12.6, 13.4, 3.6), (13.2, 14.0, 4.2), 5, glow=True)
    return m


# 2026-11-03 照站長：夏日系列
def summer_pet_crab():
    """小螃蟹（稀有）：紅橘色的扁身體、兩顆眼睛豎在上面、兩隻大螯往前舉、每邊三隻腳。"""
    m = Model("summer_pet_crab", _pad([SUMMER_RED_DARK, SUMMER_RED, SUMMER_ORANGE, "#1a1a1a", "#ffffff", "#ffb0a0"]), "pet")
    m.materials = {0: 'scale', 1: 'scale', 2: 'scale', 3: 'plain', 4: 'plain', 5: 'plain'}
    m.box((11.0, 6.4, 4.4), (13.0, 7.8, 6.6), 2)             # 右邊：螯的臂
    m.box((12.4, 6.0, 2.0), (15.2, 8.8, 4.8), 1)             # 大螯
    m.box((13.8, 8.8, 2.2), (15.2, 10.0, 3.4), 1)            # 螯的上鉗（張開）
    m.box((12.4, 5.2, 2.2), (13.6, 6.0, 3.4), 0)             # 下鉗
    for i, z in enumerate((6.8, 8.6, 10.4)):
        m.box((11.2, 5.2, z), (14.0, 6.0, z + 0.8), 2, rot=(22.5, "z", (11.2, 5.6, z)))   # 三隻腳（往外翹）
        m.box((13.4, 3.4, z), (14.2, 5.6, z + 0.8), 0)
    m.box((9.2, 9.2, 5.6), (10.0, 11.2, 6.4), 0)             # 眼柄
    m.box((8.9, 11.2, 5.3), (10.3, 12.6, 6.7), 4)            # 眼睛（白）
    m.box((9.1, 11.6, 5.1), (10.1, 12.4, 5.3), 3)            # 瞳孔
    m.mirror_x()
    m.box((4.4, 5.6, 5.2), (11.6, 9.2, 11.0), 1)             # 身體
    m.box((5.0, 9.2, 5.8), (11.0, 9.8, 10.4), 0)             # 背上的紋
    m.box((5.2, 6.4, 4.8), (10.8, 8.4, 5.2), 5)              # 正面淺色的「臉」
    m.box((6.8, 6.8, 4.6), (9.2, 7.4, 4.8), 3)               # 小嘴（一條）
    m.box((5.4, 5.0, 5.6), (10.6, 5.6, 10.6), 0)             # 肚子
    return m


# 2026-11-03 照站長：夏日系列
def summer_pet_dolphin():
    """小海豚（傳說）：水平躺的流線身體（長約 10 格：前細、中段最粗、往後收成尾），頭前一個短嘴、背鰭、兩片水平的尾鰭；
    背深藍、肚子淡色；噴氣孔上方 3 顆發光的水花。"""
    m = Model("summer_pet_dolphin", _pad(["#1f4f8a", "#3a86c8", "#7fc8f0", "#e8f6ff", "#1a1a1a", SUMMER_FOAM, "#ffffff"]), "pet")
    m.materials = {0: 'plain', 1: 'plain', 2: 'plain', 3: 'plain', 4: 'plain'}
    # 身體：沿 z 軸（臉朝 north = z 小），一段一段變粗再變細
    segs = (((6.9, 6.9, 2.6), (9.1, 9.1, 4.0)),      # 頭前
            ((6.3, 6.4, 4.0), (9.7, 9.9, 5.8)),      # 頭
            ((5.8, 6.0, 5.8), (10.2, 10.4, 9.0)),    # 中段最粗
            ((6.2, 6.3, 9.0), (9.8, 10.0, 11.0)),    # 往後收
            ((6.8, 6.8, 11.0), (9.2, 9.3, 12.6)),
            ((7.3, 7.2, 12.6), (8.7, 8.6, 14.2)))    # 尾柄
    for a, b in segs:
        m.box(a, b, 1)
        m.box((a[0] + 0.3, b[1] - 0.01, a[2]), (b[0] - 0.3, b[1] + 0.3, b[2]), 0)          # 背：深藍
        m.box((a[0] + 0.3, a[1] - 0.3, a[2]), (b[0] - 0.3, a[1] + 0.01, b[2]), 3)          # 肚：淡色
    m.box((7.3, 7.2, 1.2), (8.7, 8.4, 2.6), 2)               # 短嘴
    m.box((7.5, 6.8, 1.4), (8.5, 7.2, 2.6), 3)
    m.box((6.2, 8.3, 3.6), (6.4, 9.0, 4.3), 4)               # 眼睛（兩側）
    m.box((9.6, 8.3, 3.6), (9.8, 9.0, 4.3), 4)
    m.box((7.6, 10.4, 4.6), (8.4, 10.6, 5.4), 4)             # 噴氣孔
    m.box((7.4, 10.3, 6.8), (8.6, 13.2, 9.2), 0, rot=(-22.5, "x", (8, 10.3, 8)))   # 背鰭（往後斜）
    m.box((10.0, 6.4, 5.4), (12.6, 7.2, 7.6), 2, rot=(-22.5, "z", (10.0, 6.8, 6.5)))   # 胸鰭
    m.box((3.4, 6.4, 5.4), (6.0, 7.2, 7.6), 2, rot=(22.5, "z", (6.0, 6.8, 6.5)))
    m.box((8.0, 7.5, 14.0), (11.8, 8.3, 16.4), 0, rot=(22.5, "y", (8.0, 7.9, 14.0)))   # 尾鰭：兩片水平、往後張開
    m.box((4.2, 7.5, 14.0), (8.0, 8.3, 16.4), 0, rot=(-22.5, "y", (8.0, 7.9, 14.0)))
    for (x, y, z, c, r) in ((8.0, 12.6, 4.6, 5, 0.6), (6.8, 13.8, 4.0, 6, 0.4), (9.4, 14.2, 5.4, 5, 0.45)):
        m.box((x - r, y - r, z - r), (x + r, y + r, z + r), c, rot=(45, "z", (x, y, z)), glow=True)   # 噴氣孔上方的水花
    return m


# 2026-11-03 照站長：夏日系列
def summer_pet_parrot():
    """鸚鵡（史詩）：紅身、黃翅、藍翅尖、藍紅相間的長尾、白臉灰喙。"""
    m = Model("summer_pet_parrot", _pad([SUMMER_RED_DARK, SUMMER_RED, "#ffe13a", SUMMER_SEA, SUMMER_DEEP, "#ffffff",
                                         "#1a1a1a", "#4a4a58", "#ffb000"]), "pet")
    m.materials = {0: 'feather', 1: 'feather', 2: 'feather', 3: 'feather', 4: 'feather', 5: 'plain', 7: 'plain', 8: 'feather'}
    m.box((6, 4.6, 5.6), (10, 9.6, 10.6), 1)                 # 身體
    m.box((6.2, 9.4, 4.4), (9.8, 13.2, 8.4), 1)              # 頭
    m.box((6.0, 10.0, 4.2), (6.2, 12.2, 6.4), 5)             # 白臉
    m.box((9.8, 10.0, 4.2), (10.0, 12.2, 6.4), 5)
    m.box((6.4, 10.0, 4.0), (9.6, 12.2, 4.4), 5)
    m.box((6.6, 10.8, 3.9), (7.3, 11.6, 4.0), 6)             # 眼睛
    m.box((8.7, 10.8, 3.9), (9.4, 11.6, 4.0), 6)
    m.box((7.1, 9.4, 2.6), (8.9, 10.8, 4.0), 7)              # 上喙（彎下來）
    m.box((7.5, 8.6, 3.0), (8.5, 9.4, 3.8), 7)
    m.box((7.3, 8.8, 3.6), (8.7, 9.4, 4.2), 6)               # 下喙
    m.box((4.8, 5.4, 5.8), (6.0, 9.4, 10.6), 2)              # 翅膀（黃）
    m.box((10.0, 5.4, 5.8), (11.2, 9.4, 10.6), 2)
    m.box((4.8, 4.0, 8.6), (6.0, 6.2, 12.2), 3)              # 翅尖（藍）
    m.box((10.0, 4.0, 8.6), (11.2, 6.2, 12.2), 3)
    m.box((5.0, 7.0, 7.4), (6.0, 8.0, 10.0), 8)              # 翅膀上一道橘
    m.box((10.0, 7.0, 7.4), (11.0, 8.0, 10.0), 8)
    m.box((7.0, 3.6, 10.4), (9.0, 5.0, 15.0), 1)             # 長尾（紅）
    m.box((6.2, 3.0, 11.0), (7.0, 4.4, 15.6), 3)             # 兩側藍尾羽
    m.box((9.0, 3.0, 11.0), (9.8, 4.4, 15.6), 3)
    m.box((7.4, 2.6, 15.0), (8.6, 3.8, 17.0), 4)
    m.box((6.6, 3.2, 6.4), (7.4, 4.6, 7.8), 7)               # 腳
    m.box((8.6, 3.2, 6.4), (9.4, 4.6, 7.8), 7)
    m.box((5.8, 2.4, 5.4), (10.2, 3.2, 8.8), 0)              # 站的小木棲
    return m


# 2026-11-03 照站長：夏日系列
def summer_pet_icecream():
    """冰淇淋精靈（稀有）：格子甜筒、下面一球草莓粉（有臉）、上面一球薄荷綠、頂上一顆櫻桃，還有彩色糖粒。"""
    m = Model("summer_pet_icecream", _pad([SUMMER_WOOD_LIGHT, SUMMER_SAND_DARK, SUMMER_CORAL, "#ffb0c8", "#8fe0b0", "#c8ffe0",
                                           SUMMER_RED, "#1a1a1a", "#ffe13a", SUMMER_SEA, "#ffffff"]), "pet")
    m.materials = {0: 'straw', 1: 'straw', 2: 'cloth', 3: 'cloth', 4: 'cloth', 5: 'cloth', 6: 'plain'}
    m.box((5.8, 6, 5.8), (10.2, 9, 10.2), 0)                 # 甜筒（往下收尖）
    m.box((6.4, 4, 6.4), (9.6, 6, 9.6), 0)
    m.box((7.0, 2.2, 7.0), (9.0, 4, 9.0), 0)
    m.box((7.5, 0.8, 7.5), (8.5, 2.2, 8.5), 1)
    m.box((5.7, 7.2, 5.7), (10.3, 7.6, 10.3), 1)             # 格子紋
    m.box((6.3, 5.0, 6.3), (9.7, 5.4, 9.7), 1)
    for x in (6.6, 8.6):
        m.box((x, 6.0, 5.7), (x + 0.5, 9.0, 5.8), 1)
        m.box((x, 6.0, 10.2), (x + 0.5, 9.0, 10.3), 1)
    m.box((5.0, 9, 5.0), (11.0, 13.2, 11.0), 2)              # 第一球：草莓粉
    m.box((5.6, 13.2, 5.6), (10.4, 13.9, 10.4), 2)
    m.box((5.2, 8.2, 5.6), (6.2, 9, 6.6), 3)                 # 滴下來的
    m.box((9.6, 8.0, 9.4), (10.6, 9, 10.4), 3)
    m.box((6.2, 10.8, 4.8), (7.0, 11.8, 5.0), 7)             # 臉：眼睛
    m.box((9.0, 10.8, 4.8), (9.8, 11.8, 5.0), 7)
    m.box((7.5, 9.8, 4.8), (8.5, 10.3, 5.0), 7)              # 嘴
    m.box((5.3, 10.0, 4.85), (6.0, 10.5, 5.0), 6)            # 臉紅
    m.box((10.0, 10.0, 4.85), (10.7, 10.5, 5.0), 6)
    m.box((5.5, 13.9, 5.5), (10.5, 17.6, 10.5), 4)           # 第二球：薄荷綠
    m.box((6.3, 17.6, 6.3), (9.7, 18.4, 9.7), 5)
    for (x, y, z, c) in ((5.3, 15.6, 6.6, 8), (5.3, 14.6, 8.8, 9), (10.5, 15.2, 7.2, 10), (10.5, 16.4, 9.2, 8),
                         (7.0, 17.6, 5.3, 9), (9.2, 16.8, 5.3, 8), (6.4, 15.0, 10.5, 10), (8.8, 16.2, 10.5, 9)):
        m.box((x - 0.3, y - 0.2, z - 0.3), (x + 0.3, y + 0.5, z + 0.3), c)          # 糖粒
    m.box((7.3, 18.4, 7.3), (8.7, 19.8, 8.7), 6)             # 櫻桃
    m.box((7.9, 19.8, 7.9), (8.1, 21.2, 8.1), 1)             # 櫻桃梗
    m.box((8.1, 20.8, 7.8), (9.0, 21.3, 8.2), 1)
    return m


# 2026-11-03 照站長：夏日系列
def summer_coral_bow_extras(m, flex):
    """珊瑚弓（史詩）：弓臂上長出橘色的珊瑚枝、握把跟弓臂鑲著發光的珍珠。"""
    m.box((8.8, 7.2, 6.9), (10.0, 8.8, 9.1), 9, glow=True)                        # 握把上的珍珠
    for sign in (1, -1):
        y = 8 + sign * 4.6
        m.box((9.2 - flex * 0.2, y - 0.5, 7.7), (11.2 - flex * 0.2, y + 0.5, 8.3), 8)              # 珊瑚枝（往外長）
        m.box((10.6 - flex * 0.2, y, 7.75), (11.3 - flex * 0.2, y + sign * 1.6, 8.25), 8)
        m.box((9.9 - flex * 0.2, y, 7.75), (10.5 - flex * 0.2, y + sign * 1.1, 8.25), 8)
        y2 = 8 + sign * 8.2
        m.box((7.4 - flex * 0.5, y2 - 0.45, 7.6), (8.3 - flex * 0.5, y2 + 0.45, 8.4), 9, glow=True)   # 弓臂上的小珍珠


# 2026-11-03 照站長：夏日系列
def summer_water_crossbow_extras(m):
    """水槍弩（史詩）：弩身下面掛著半透明的水箱（裡面看得到水位）、橘色的幫浦握把、前端一個橘色噴嘴。"""
    m.box((6.6, 3.4, 6.4), (10.6, 6.8, 9.6), 3)              # 水箱（半透明）
    m.box((6.9, 3.7, 6.7), (10.3, 5.4, 9.3), 9, glow=True)   # 裡面的水
    m.box((7.6, 2.6, 7.4), (9.6, 3.4, 8.6), 8)               # 水箱底的蓋
    m.box((9.4, 5.4, 7.3), (11.6, 6.8, 8.7), 8)              # 幫浦握把（橘）
    m.box((13.6, 7.2, 7.4), (14.8, 8.2, 8.6), 8)             # 噴嘴
    m.box((14.8, 7.5, 7.7), (15.4, 7.9, 8.3), 9, glow=True)  # 噴嘴口的一滴水


# 2026-11-03 照站長：夏日系列
def armor_tide_helm(colors, materials=None):
    """浪潮（傳說）的頭盔：圓滾滾的潛水頭盔，正面一個白框圓窗（玻璃發一點光）、兩側小圓窗、頂上一個閥、一圈鉚釘。"""
    m = Model("armor3d_tide_helm", colors, "headgear", materials)
    # 球形殼：一層一層往上下收，中段再疊一個轉 45 度的（橫切面變八角，看起來圓）
    m.box((2.0, 3.8, 2.0), (14.0, 4.8, 14.0), 6)             # 領口（深藍）
    m.box((1.2, 4.8, 1.2), (14.8, 6.4, 14.8), 1)
    m.box((0.6, 6.4, 0.6), (15.4, 13.8, 15.4), 1)
    m.box((2.6, 5.6, 2.6), (13.4, 14.6, 13.4), 1, rot=(45, "y", (8, 10, 8)))
    m.box((1.2, 13.8, 1.2), (14.8, 15.4, 14.8), 1)
    m.box((2.8, 15.4, 2.8), (13.2, 16.4, 13.2), 1)
    m.box((2.6, 5.6, 0.0), (13.4, 14.0, 0.6), 2)             # 正面大面罩的白框（兩塊疊成八角）
    m.box((1.6, 7.0, 0.04), (14.4, 12.6, 0.56), 2)
    m.box((3.4, 6.4, -0.2), (12.6, 13.2, 0.0), 3, glow=True) # 玻璃（淡藍、發光；約 9×7 格）
    m.box((2.4, 7.8, -0.18), (13.6, 11.8, 0.0), 3, glow=True)
    m.box((4.2, 11.4, -0.4), (6.8, 12.3, -0.2), 9, glow=True)   # 玻璃上的反光
    m.box((4.2, 10.6, -0.4), (5.0, 11.4, -0.2), 9, glow=True)
    for (x, y) in ((2.8, 6.0), (12.6, 6.0), (2.8, 13.2), (12.6, 13.2), (7.7, 5.2), (7.7, 13.6), (1.8, 9.5), (13.8, 9.5)):
        m.box((x, y, -0.1), (x + 0.6, y + 0.6, 0.0), 0)      # 鉚釘
    for x in (-0.2, 15.6):                                   # 兩側的小圓窗
        m.box((x, 7.4, 6.0), (x + 0.6, 11.8, 10.0), 2)
        m.box((x - 0.2 if x < 8 else x + 0.6, 8.2, 6.8), (x if x < 8 else x + 0.8, 11.0, 9.2), 3, glow=True)
    m.box((6.6, 16.4, 6.6), (9.4, 17.6, 9.4), 2)             # 頂上的閥
    m.box((5.4, 17.6, 7.5), (10.6, 18.4, 8.5), 0)
    m.box((7.5, 17.6, 5.4), (8.5, 18.4, 10.6), 0)
    m.box((1.4, 3.2, 1.4), (14.6, 3.8, 14.6), 2)             # 領口的白環
    m.box((12.0, 10.0, 15.0), (13.4, 11.0, 17.0), 4)         # 後面的氣管接頭（珊瑚粉）
    return m


# 2026-11-03 照站長：夏日系列
def armor_beach_helm(colors, materials=None):
    """沙灘衛士（史詩）的頭盔：草帽型——薄的八角寬簷伸出頭外 2.6 格、上面一個圓頂帽身（沙金），椰綠帽帶、右側一朵小花；
    簷下是空的，臉看得到（只有薄薄的護頰與護鼻）。"""
    m = Model("armor3d_beach_helm", colors, "headgear", materials)
    m.box((2.2, 5.0, 1.0), (4.0, 11.6, 1.8), 1)              # 薄護頰、護鼻（簷下留空讓臉看得到）
    m.box((12.0, 5.0, 1.0), (13.8, 11.6, 1.8), 1)
    m.box((7.3, 7.0, 0.6), (8.7, 11.6, 1.2), 2)
    m.box((1.4, 11.6, 1.4), (14.6, 12.4, 14.6), 4)           # 頭箍（帽子扣在這一圈上）
    m.box((-2.6, 12.6, 1.6), (18.6, 13.2, 14.4), 3)          # 薄的寬帽簷（三塊疊成八角；y 各差一點免得同一面打架）
    m.box((1.6, 12.64, -2.6), (14.4, 13.16, 18.6), 3)
    m.box((-0.8, 12.68, -0.8), (16.8, 13.12, 16.8), 3)
    m.box((-2.2, 13.22, 2.8), (18.2, 13.3, 13.2), 4)         # 簷上一圈草紋
    m.box((2.8, 13.24, -2.2), (13.2, 13.32, 18.2), 4)
    m.box((1.6, 13.2, 1.6), (14.4, 16.8, 14.4), 1)           # 圓頂帽身：一層一層往上收，中段再疊一個轉 45 度的
    m.box((2.4, 13.6, 2.4), (13.6, 17.4, 13.6), 1, rot=(45, "y", (8, 15, 8)))
    m.box((2.6, 16.8, 2.6), (13.4, 18.2, 13.4), 1)
    m.box((4.2, 18.2, 4.2), (11.8, 19.2, 11.8), 3)
    m.box((6.0, 19.2, 6.0), (10.0, 19.8, 10.0), 1)
    m.box((1.4, 13.6, 1.4), (14.6, 14.8, 14.6), 2)           # 椰綠帽帶
    m.box((2.2, 13.64, 2.2), (13.8, 14.76, 13.8), 2, rot=(45, "y", (8, 14.2, 8)))
    m.box((1.5, 15.6, 1.5), (14.5, 15.9, 14.5), 4)           # 帽身上的草紋
    m.box((14.4, 13.4, 4.6), (15.2, 15.6, 7.6), 9, glow=True)   # 右側帽帶上的小花（橘）
    m.box((14.6, 15.6, 5.6), (15.1, 16.4, 6.6), 9, glow=True)
    m.box((14.6, 12.6, 5.6), (15.1, 13.4, 6.6), 9, glow=True)
    m.box((15.2, 14.1, 5.6), (15.5, 14.9, 6.6), 3)
    m.box((14.5, 13.8, 7.6), (15.0, 14.6, 10.0), 5)          # 花旁邊一片葉子
    return m


# 2026-11-03 照站長：夏日系列
ARMOR3D["tide"] = ([SUMMER_DEEP, SUMMER_SEA, "#ffffff", SUMMER_FOAM, SUMMER_CORAL, "#d8fbff", "#083a5a", None,
                    None, "#ffffff", None, None, None, None, None, None], armor_tide_helm, "wave")
ARMOR3D["beach"] = ([SUMMER_WOOD, SUMMER_SAND, SUMMER_GREEN, SUMMER_SAND_LIGHT, SUMMER_SAND_DARK, SUMMER_GREEN_LIGHT, "#3a2410", None,
                     None, SUMMER_ORANGE, None, None, None, None, None, None], armor_beach_helm, "rope")


# ---- 清單：代號 → (模型們, 部位 → 模型) ----

# ======================================================================
# 2026-11-03 照站長：星潮祭（夏日活動）限定 sf_*：海妖觸鬚之翼、珍珠貝冠、潮汐三叉戟、小海妖、貝殼盾、小水母。
# 深藍紫的海妖＋粉白的珍珠貝＋青金的潮汐。翅膀、寵物的觸手用 box(part=...) 標骨頭（gen_cosmetic_bbmodels.py 分段擺動）。
# 這次之前：翅膀、寵物的骨頭是 bbmodel 那支程式照位置猜的（離中心遠的當外段、最低的 30% 當觸手），分不出一條一條的觸手。
# ======================================================================

SF_INK, SF_INK_MID, SF_INK_LIGHT = "#1a1040", "#2e1a6a", "#4a2a9a"      # 海妖的深藍紫
SF_SUCKER, SF_TIP = "#7fe8e0", "#b8fff8"                                   # 吸盤淺青、尖端的光
SF_PEARL, SF_PEARL_GLOW, SF_PINK, SF_PINK_LIGHT, SF_PINK_DARK = "#ffffff", "#e8f4ff", "#ffc0d0", "#ffe4ec", "#d88aa8"
SF_TEAL, SF_TEAL_DARK, SF_GOLD, SF_GOLD_LIGHT, SF_AQUA = "#1f8a8a", "#0e3a40", "#c9a43a", "#ffe08a", "#7fe8ff"


def _rot_end(sx, sy, length, angle):
    """從 (sx, sy) 往 +x 伸 length、繞 z 轉 angle 度之後，另一端在哪（x-y 平面）。"""
    a = math.radians(angle)
    return round(sx + length * math.cos(a), 3), round(sy + length * math.sin(a), 3)


# 2026-11-03 星潮祭
def sf_kraken(m):
    """海妖觸鬚之翼（傳說）：肩後一塊海妖的身，往外伸 4 條深藍紫的觸手當翅膀（上兩條往上捲、下兩條往下捲），每條三段、
    越外面越細，下緣一排淺青的吸盤、尖端發光。每一段都標骨頭（t{i}a_r/t{i}b_r/t{i}c_r），bbmodel 裡一段一段接著擺。"""
    m.box((8, 6.4, 7.2), (10.8, 11.4, 8.8), 1)                 # 肩後的身（深紫）
    m.box((8.4, 5.8, 7.4), (10.4, 6.4, 8.6), 0)
    m.box((8.4, 11.4, 7.4), (10.4, 12.0, 8.6), 0)
    tentacles = ((10.6, (22.5, 45, 45), (6.0, 5.0, 4.0)),      # 最上面那條往上捲
                 (9.4, (0, 22.5, 45), (7.0, 5.5, 4.0)),
                 (8.2, (0, -22.5, -45), (6.5, 5.0, 4.0)),
                 (7.0, (-22.5, -45, -45), (5.5, 4.5, 3.5)))    # 最下面那條往下捲
    heights, depths = (2.0, 1.5, 1.1), (1.8, 1.4, 1.0)
    for i, (y0, angles, lengths) in enumerate(tentacles):
        sx, sy = 10.6, y0
        path = ""
        for k in range(3):
            name = f"t{i}{'abc'[k]}_r"
            path = f"{path}/{name}" if path else name
            h, d, length, ang = heights[k], depths[k], lengths[k], angles[k]
            rot = (ang, "z", (sx, sy, 8)) if ang else None
            part = (path, (sx, sy, 8))
            m.box((sx, sy - h / 2, 8 - d / 2), (sx + length, sy + h / 2, 8 + d / 2), 1 if k < 2 else 2, rot=rot, part=part)
            step = 1.5
            u = 0.6
            while u + 0.7 <= length:                            # 下緣的吸盤（淺青、凸出來一點）
                m.box((sx + u, sy - h / 2 - 0.35, 7.55), (sx + u + 0.7, sy - h / 2 + 0.02, 8.45), 3, rot=rot, part=part)
                u += step
            if k == 2:                                          # 尖端的光
                m.box((sx + length - 0.1, sy - 0.4, 7.65), (sx + length + 1.3, sy + 0.4, 8.35), 4, rot=rot, part=part, glow=True)
            sx, sy = _rot_end(sx, sy, length, ang)


# 2026-11-03 星潮祭
def sf_pearl_crown():
    """珍珠貝冠（傳說）：額前一片扇貝殼（九道放射的稜、粉白相間、越中間越高），頂上托一顆發光的大珍珠；
    粉色的頭環，兩側各垂一串小珍珠。"""
    m = Model("sf_pearl_crown", _pad([SF_PINK, SF_PINK_LIGHT, "#fff8f0", SF_PINK_DARK, SF_PEARL, SF_PEARL_GLOW, SF_GOLD]), "headgear")
    m.materials = {0: 'shell', 1: 'shell', 2: 'shell', 3: 'shell', 6: 'metal'}
    ring(m, 13.4, 15.2, 1.1, 0.9, 1)                           # 頭環（粉白）
    ring(m, 13.2, 13.7, 1.0, 0.6, 3)                           # 下緣的深粉滾邊
    heights = (1.8, 2.8, 3.8, 4.6, 5.0, 4.6, 3.8, 2.8, 1.8)
    for i, h in enumerate(heights):                            # 額前的扇貝：九道稜
        x = 2.5 + i * 1.35
        m.box((x, 15.0, 0.9), (x + 1.3, 15.0 + h, 1.9), 0 if i % 2 == 0 else 1)
        m.box((x + 0.15, 15.0 + h, 1.0), (x + 1.15, 15.0 + h + 0.6, 1.8), 2)     # 稜頂的淺色
    m.box((2.3, 14.6, 0.8), (13.7, 15.4, 2.0), 3)              # 貝殼的鉸合（深粉）
    m.box((6.9, 20.5, 0.8), (9.1, 22.7, 2.0), 4, rot=(45, "z", (8, 21.6, 1.4)), glow=True)   # 頂上的大珍珠（方＋轉 45 度 = 八角）
    m.box((7.2, 20.8, 0.78), (8.8, 22.4, 2.02), 4, glow=True)
    for x in (3.2, 5.9, 8.0, 10.1, 12.8):                      # 頭環正面的小珍珠
        m.box((x - 0.4, 13.9, 0.75), (x + 0.4, 14.7, 1.1), 5, glow=True)
    for x0 in (0.5, 14.9):                                     # 兩側垂下來的珍珠串（左右各一串）
        for j, z in enumerate((3.0, 4.6, 6.2, 7.8, 9.4, 11.0)):
            y = 12.4 if j % 2 == 0 else 11.7
            m.box((x0, y, z - 0.4), (x0 + 0.6, y + 0.8, z + 0.4), 4, glow=True)
            m.box((x0 + 0.2, y + 0.8, z - 0.1), (x0 + 0.4, 13.4, z + 0.1), 6)   # 掛珍珠的金線
    return m


# 2026-11-03 星潮祭
def sf_tide_trident():
    """潮汐三叉戟（傳說）：青金色的三叉戟——青色長柄纏著海草、柄上掛一枚粉貝；金色橫樑、三根青叉，叉尖水藍發光，
    叉與叉之間一片會流動的水（water:flow 的發光動畫貼圖）。近戰武器、法杖都能套。"""
    m = Model("sf_tide_trident", _pad([SF_TEAL_DARK, SF_TEAL, SF_GOLD, SF_AQUA, "#2e8a4a", SF_PINK, ("#5fd8ff", 190), SF_GOLD_LIGHT,
                                       "#3aa655"]), "weapon")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal', 4: 'plain', 5: 'shell', 6: 'water:flow', 7: 'metal', 8: 'plain'}
    m.box((7.5, -5, 7.5), (8.5, 12, 8.5), 1)                   # 長柄（青）
    m.box((7.2, -5.8, 7.2), (8.8, -4.8, 8.8), 2)               # 金色柄尾
    for y in (-2.5, 6.5):
        m.box((7.3, y, 7.3), (8.7, y + 0.7, 8.7), 2)           # 金箍
    wraps = (((8.5, -1.6, 7.4), (8.9, -0.6, 8.6)), ((7.4, -0.8, 8.5), (8.6, 0.2, 8.9)), ((7.1, 0.0, 7.4), (7.5, 1.0, 8.6)),
             ((7.4, 0.8, 7.1), (8.6, 1.8, 7.5)), ((8.5, 1.6, 7.4), (8.9, 2.6, 8.6)), ((7.4, 2.4, 8.5), (8.6, 3.4, 8.9)),
             ((7.1, 3.2, 7.4), (7.5, 4.2, 8.6)), ((7.4, 4.0, 7.1), (8.6, 5.0, 7.5)), ((8.5, 4.8, 7.4), (8.9, 5.8, 8.6)))
    for i, (a, b) in enumerate(wraps):                         # 繞著柄盤上去的海草（一圈一圈換邊）
        m.box(a, b, 4 if i % 2 == 0 else 8)
    m.box((8.6, 5.6, 7.6), (9.8, 6.4, 8.4), 8)                 # 海草葉尖
    m.box((6.9, 2.6, 6.75), (9.1, 4.3, 7.15), 5)               # 掛在柄上的粉貝
    m.box((7.3, 4.3, 6.85), (8.7, 4.7, 7.1), 3, glow=True)     # 貝裡的一顆小珠
    m.box((4, 12, 7.4), (12, 13.2, 8.6), 2)                    # 金色橫樑
    m.box((6.8, 10.8, 6.8), (9.2, 12, 9.2), 7)                 # 橫樑下的金座
    m.box((7.3, 11.1, 6.6), (8.7, 11.8, 6.8), 3, glow=True)    # 金座正面的潮汐之眼
    m.box((7.3, 13.2, 7.3), (8.7, 20.6, 8.7), 1)               # 中間的叉
    m.box((7.6, 20.6, 7.6), (8.4, 22.4, 8.4), 3, glow=True)
    for x0 in (4.0, 10.8):
        m.box((x0, 13.2, 7.5), (x0 + 1.2, 18.4, 8.5), 1)       # 兩邊的叉
        m.box((x0 + 0.2, 18.4, 7.6), (x0 + 1.0, 19.8, 8.4), 3, glow=True)
        m.box((x0 - 0.2, 13.2, 7.4), (x0 + 1.4, 14.0, 8.6), 2)  # 叉根的金箍
    m.box((5.2, 13.4, 7.8), (7.3, 17.6, 8.2), 6, glow=True)    # 叉之間流動的水
    m.box((8.7, 13.4, 7.8), (10.8, 17.6, 8.2), 6, glow=True)
    m.tilt()
    return m


# 2026-11-03 星潮祭
def sf_pet_kraken():
    """小海妖（傳說）：圓滾滾的頭、一對大眼、臉頰粉紅，頭上一圈螢光青的斑點；底下 8 條短觸手往外捲（兩段，尖端發光）。
    每條觸手標骨頭 t{i}a/t{i}b，bbmodel 裡輪流擺、身體上下浮。"""
    m = Model("sf_pet_kraken", _pad([SF_INK, SF_INK_MID, SF_INK_LIGHT, SF_SUCKER, "#ffffff", "#1a1a24", "#ff9ad5", SF_TIP]), "pet")
    m.materials = {0: 'leather', 1: 'plain', 2: 'plain', 3: 'glow', 4: 'plain', 5: 'plain', 6: 'plain'}
    m.box((5.0, 7.6, 5.0), (11.0, 13.0, 11.0), 1)              # 頭
    m.box((4.4, 8.6, 5.6), (11.6, 12.2, 10.4), 1)              # 兩側再鼓一點（看起來圓）
    m.box((5.6, 8.6, 4.4), (10.4, 12.2, 11.6), 1)
    m.box((6.0, 13.0, 6.0), (10.0, 14.0, 10.0), 1)             # 頭頂
    m.box((6.8, 14.0, 6.8), (9.2, 14.5, 9.2), 2)
    m.box((5.2, 7.2, 5.2), (10.8, 7.6, 10.8), 0)               # 底（深）
    for x in (5.4, 8.6):                                       # 大眼睛（白）＋瞳孔
        m.box((x, 9.6, 4.2), (x + 2.0, 11.6, 4.4), 4)
        m.box((x + 0.6, 10.0, 4.1), (x + 1.5, 11.0, 4.2), 5)
        m.box((x + 0.6, 10.7, 4.05), (x + 0.9, 11.0, 4.1), 4)   # 眼裡的反光
    m.box((4.3, 9.0, 5.8), (4.5, 9.7, 6.8), 6)                 # 臉頰的粉紅
    m.box((11.5, 9.0, 5.8), (11.7, 9.7, 6.8), 6)
    m.box((7.4, 8.6, 4.3), (8.6, 8.9, 4.4), 5)                 # 小嘴
    spots = ((5.2, 12.0, 4.3, "z"), (9.4, 12.6, 4.3, "z"), (7.0, 13.6, 6.0, "y"), (9.4, 13.6, 8.6, "y"),
             (5.6, 11.6, 11.6, "z"), (9.8, 11.0, 11.6, "z"), (4.3, 11.2, 8.6, "x"), (11.6, 10.6, 7.4, "x"))
    for (x, y, z, axis) in spots:                              # 螢光青的斑點（貼在面上一點點厚）
        if axis == "z":
            m.box((x, y, z - 0.1 if z < 8 else z), (x + 0.9, y + 0.9, z if z < 8 else z + 0.1), 3, glow=True)
        elif axis == "y":
            m.box((x, 14.0, z), (x + 0.9, 14.1, z + 0.9), 3, glow=True)
        else:
            m.box((x if x < 8 else x, y, z), (x + 0.1, y + 0.9, z + 0.9), 3, glow=True)
    for i in range(8):                                         # 8 條短觸手：繞底邊一圈，往外捲
        ang = math.radians(i * 45)
        bx, bz = round(8 + 2.4 * math.cos(ang), 3), round(8 + 2.4 * math.sin(ang), 3)
        # 轉軸：在 ±x 的繞 z 轉、在 ±z 的繞 x 轉、斜角的輪流；角度的正負讓它往外翹
        axis = "z" if i % 4 == 0 else "x" if i % 4 == 2 else ("z" if i % 8 in (1, 5) else "x")
        if axis == "z":
            sign = 1 if math.cos(ang) > 0 else -1              # +x 側 +22.5 度會把下端往 +x 甩
        else:
            sign = -1 if math.sin(ang) > 0 else 1              # +z 側要 −22.5 度
        a1, a2 = sign * 22.5, sign * 45
        top = 7.6
        m.box((bx - 0.6, top - 2.0, bz - 0.6), (bx + 0.6, top, bz + 0.6), 1, rot=(a1, axis, (bx, top, bz)), part=(f"t{i}a", (bx, top, bz)))
        # 第一段轉完之後下端在哪：繞 z 轉 θ：(0, −2) → (2 sinθ, −2 cosθ)；繞 x 轉 θ：(−2, 0)（y, z）→ (−2 cosθ, −2 sinθ)
        t = math.radians(a1)
        if axis == "z":
            ex, ey, ez = bx + 2.0 * math.sin(t), top - 2.0 * math.cos(t), bz
        else:
            ex, ey, ez = bx, top - 2.0 * math.cos(t), bz - 2.0 * math.sin(t)
        ex, ey, ez = round(ex, 3), round(ey, 3), round(ez, 3)
        rot2 = (a2, axis, (ex, ey, ez))
        part2 = (f"t{i}a/t{i}b", (ex, ey, ez))
        m.box((ex - 0.45, ey - 1.6, ez - 0.45), (ex + 0.45, ey, ez + 0.45), 2, rot=rot2, part=part2)
        m.box((ex - 0.35, ey - 2.2, ez - 0.35), (ex + 0.35, ey - 1.6, ez + 0.35), 7, rot=rot2, part=part2, glow=True)   # 尖端的光
    return m


# 2026-11-03 星潮祭
def sf_shell_shield():
    """貝殼盾（史詩）：一大片扇貝——從底下的鉸合放射出去九道稜（粉橘到奶白一段一段變淺），外緣一排珍珠；
    背面一根木握把（盾、箭袋都能套）。"""
    m = Model("sf_shell_shield", _pad(["#ff9a6a", "#ffc8a8", "#fff2e4", "#d86a4a", SF_PEARL, SF_PEARL_GLOW, "#8a5a3a", "#ffb088"]), "offhand")
    m.materials = {0: 'shell', 1: 'shell', 2: 'shell', 3: 'shell', 6: 'wood', 7: 'shell'}
    m.box((2.6, 4.6, 7.4), (13.4, 12.6, 8.6), 7)               # 底板（扇形：方＋轉 45 度的方，底下被鉸合蓋住）
    m.box((4.6, 2.6, 7.42), (11.4, 14.6, 8.58), 7)
    m.box((3.0, 4.2, 7.44), (13.0, 12.8, 8.56), 7, rot=(45, "z", (8, 8.5, 8)))
    hinge = (8, 2.2, 8)
    for i, (ang, ox) in enumerate(((-45, 0), (-22.5, 0), (0, 0), (22.5, 0), (45, 0), (-45, -1.9), (-22.5, -1.6), (22.5, 1.6), (45, 1.9))):
        wide = i < 5
        w = 2.0 if wide else 1.3
        o = (hinge[0] + ox, hinge[1], 8)
        y0 = hinge[1]
        for k, (length, c) in enumerate(((4.2, 0), (4.0, 1), (3.4, 2))):   # 每道稜三段：粉橘 → 淺橘 → 奶白
            if not wide and k == 0:
                y0 += length                                   # 窄的稜從第二段才開始（根部被寬稜蓋住）
                continue
            m.box((o[0] - w / 2, y0, 7.0 if wide else 7.15), (o[0] + w / 2, y0 + length, 9.0 if wide else 8.85), c,
                  rot=(ang, "z", o) if ang else None)
            y0 += length
    for j in range(9):                                         # 外緣的珍珠（放射稜的頂端）
        ang = math.radians(90 - 45 + j * 11.25)
        px_, py_ = 8 + 12.2 * math.cos(ang), 2.2 + 12.2 * math.sin(ang)
        r = 0.55 if j % 2 == 0 else 0.4
        m.box((px_ - r, py_ - r, 7.4), (px_ + r, py_ + r, 8.6), 4 if j % 2 == 0 else 5, rot=(45, "z", (px_, py_, 8)), glow=True)
    m.box((5.4, 0.8, 7.1), (7.8, 3.0, 8.9), 3)                 # 鉸合的兩隻「耳」
    m.box((8.2, 0.8, 7.1), (10.6, 3.0, 8.9), 3)
    m.box((7.4, 1.6, 7.0), (8.6, 3.2, 9.0), 0)
    m.box((7.3, 5.0, 9.0), (8.7, 11.0, 9.7), 6)                # 背面的木握把
    m.box((6.6, 4.4, 9.0), (9.4, 5.0, 9.4), 6)
    m.box((6.6, 11.0, 9.0), (9.4, 11.6, 9.4), 6)
    return m


# 2026-11-03 星潮祭
def sf_pet_jellyfish():
    """小水母（史詩）：半透明淡藍的傘（傘緣一圈更淡的裙邊、傘頂幾顆青色的光點）、傘裡一顆粉紅的發光核、一對小眼睛；
    傘下 6 條飄帶觸手（兩段，尖端發光）。傘標骨頭 bell（bbmodel 裡一縮一放）、觸手 t{i}a/t{i}b（飄）。"""
    m = Model("sf_pet_jellyfish", _pad([("#9fe0ff", 150), ("#d8f6ff", 170), "#ff9ad5", SF_AQUA, ("#b8ecff", 190), "#1a1a24", "#ffffff"]), "pet")
    m.materials = {0: 'glass', 1: 'glass', 4: 'glass', 5: 'plain', 6: 'plain'}
    bell = ("bell", (8, 13.6, 8))
    m.box((6.8, 10.2, 6.8), (9.2, 12.6, 9.2), 2, glow=True, part=bell)         # 發光的核（先畫，傘最後）
    m.box((6.0, 11.0, 6.0), (10.0, 11.6, 10.0), 3, glow=True, part=bell)       # 核外一圈青光
    for (x, z) in ((6.2, 6.4), (9.0, 6.0), (7.6, 9.4), (9.6, 8.8), (5.8, 9.0)):
        m.box((x, 14.0, z), (x + 0.7, 14.15, z + 0.7), 3, glow=True, part=bell)  # 傘頂的光點
    m.box((6.4, 10.6, 4.3), (7.2, 11.4, 4.6), 5, part=bell)                     # 眼睛（在傘的前面）
    m.box((8.8, 10.6, 4.3), (9.6, 11.4, 4.6), 5, part=bell)
    m.box((6.5, 11.1, 4.25), (6.8, 11.4, 4.3), 6, part=bell)                    # 眼裡的反光
    m.box((8.9, 11.1, 4.25), (9.2, 11.4, 4.3), 6, part=bell)
    spots = ((5.4, 5.4), (10.6, 5.4), (5.4, 10.6), (10.6, 10.6), (4.9, 8.0), (11.1, 8.0))
    for i, (x, z) in enumerate(spots):                                          # 6 條飄帶觸手
        pa = (f"t{i}a", (x, 9.4, z))
        m.box((x - 0.35, 6.2, z - 0.35), (x + 0.35, 9.4, z + 0.35), 4, part=pa)
        pb = (f"t{i}a/t{i}b", (x, 6.2, z))
        m.box((x - 0.3, 3.2, z - 0.3), (x + 0.3, 6.2, z + 0.3), 4, part=pb)
        m.box((x - 0.3, 2.4, z - 0.3), (x + 0.3, 3.2, z + 0.3), 3, part=pb, glow=True)   # 尖端的光
    m.box((6.0, 13.0, 6.0), (10.0, 14.0, 10.0), 0, part=bell)                   # 半透明的傘（最後畫）
    m.box((4.6, 10.4, 4.6), (11.4, 13.0, 11.4), 0, part=bell)
    m.box((4.2, 9.4, 4.2), (11.8, 10.4, 11.8), 1, part=bell)                    # 傘緣的裙邊（更淡）
    for (a, b) in (((4.4, 8.8, 4.4), (6.2, 9.4, 5.2)), ((9.8, 8.8, 4.4), (11.6, 9.4, 5.2)), ((4.4, 8.8, 10.8), (6.2, 9.4, 11.6)),
                   ((9.8, 8.8, 10.8), (11.6, 9.4, 11.6)), ((4.4, 8.8, 7.2), (5.2, 9.4, 8.8)), ((10.8, 8.8, 7.2), (11.6, 9.4, 8.8))):
        m.box(a, b, 1, part=bell)                                               # 裙邊的波浪
    return m


# ======================================================================
# 2026-11-03 照站長：星潮祭限定 3D 戰甲「珊瑚海妖鎧」（sponsor.yml 的 sf_armor_coral，art armor3d_coral）。
#   做法跟浪潮（armor3d_tide）、沙灘衛士（armor3d_beach）一樣放進 ARMOR3D：3D 頭盔＋肩甲（肩甲伺服器那邊目前關著），
#   身、腿、腳是 gen_skin_art.py 的 coral 主題（深海藍紫底、肩上珊瑚、胸口大珍珠、腰間海草貝殼、護腿鱗片、背後兩條觸手）。
#   這次之前星潮祭的兌換沒有戰甲。
# ======================================================================

def armor_coral_helm(colors, materials=None):
    """珊瑚海妖鎧（傳說）的頭盔：深海藍紫的鱗片盔殼、淺紫的額飾與護鼻（開臉），額前一顆發光的大珍珠（淺紫的貝殼托著）；
    兩側太陽穴長出橘粉的珊瑚枝（往上、往外分岔，枝頭淺色的珊瑚蟲），頭頂一道一片片升高的鰭冠、鰭緣淡青的光。"""
    m = Model("armor3d_coral_helm", colors, "headgear", materials)
    helm_shell(m, 1, 2, open_face=True)
    m.box((6.4, 11.6, 0.5), (9.6, 12.2, 1.0), 2)              # 珍珠的貝殼托
    m.box((6.9, 12.0, 0.2), (9.1, 14.2, 0.9), 5, glow=True)   # 額前的大珍珠（方＋轉 45 度 = 八角）
    m.box((7.2, 12.3, 0.1), (8.8, 13.9, 0.95), 5, rot=(45, "z", (8, 13.1, 0.5)), glow=True)
    for (z0, h) in ((3.0, 1.4), (5.2, 2.4), (7.4, 3.2), (9.6, 2.6), (11.8, 1.6)):
        m.box((7.4, 16.4, z0), (8.6, 16.4 + h, z0 + 2.0), 2)                      # 鰭冠
        m.box((7.6, 16.4 + h, z0 + 0.3), (8.4, 16.4 + h + 0.4, z0 + 1.7), 9, glow=True)   # 鰭緣的光
    for side in (0, 1):                                       # 兩側太陽穴的珊瑚枝（左邊做好照 x → 16 − x 鏡射）
        def X(a, b):
            return (a, b) if side == 0 else (round(16 - b, 3), round(16 - a, 3))
        s = 1 if side == 0 else -1                            # 鏡射之後轉的方向相反
        x0, x1 = X(0.0, 1.4)
        m.box((x0, 9.0, 6.4), (x1, 15.2, 8.4), 3)             # 主幹（橘）
        x0, x1 = X(-0.6, 0.6)
        m.box((x0, 14.4, 6.6), (x1, 19.0, 8.0), 3, rot=(22.5 * s, "z", (X(0.6, 0.6)[0], 14.4, 7.3)))   # 往外分岔
        x0, x1 = X(0.6, 1.6)
        m.box((x0, 15.0, 6.8), (x1, 18.2, 7.8), 4, rot=(-22.5 * s, "z", (X(0.6, 0.6)[0], 15.0, 7.3)))  # 往內分岔（粉）
        x0, x1 = X(-1.6, 0.0)
        m.box((x0, 11.6, 7.0), (x1, 12.6, 8.0), 4)            # 外側橫出去的小枝
        x0, x1 = X(-2.2, -1.2)
        m.box((x0, 12.6, 7.1), (x1, 14.6, 7.9), 4)
        for (a, y, z) in ((-2.4, 14.6, 7.1), (-3.0, 18.4, 6.9), (1.6, 17.8, 7.0), (0.2, 15.2, 6.2)):
            x0, x1 = X(a, a + 0.8)
            m.box((x0, y, z), (x1, y + 0.7, z + 0.8), 10)     # 枝頭的珊瑚蟲（淺色）
    return m


ARMOR3D["coral"] = (["#1c1450", "#33228a", "#6a4ac0", "#ff7a4a", "#ff9ab0", "#f4ecff", "#0a0620", None,
                     "#2e8a5a", SF_AQUA, "#ffd8c8", None, None, None, None, None], armor_coral_helm, "coral",
                    {0: "scale", 1: "scale", 2: "metal", 3: "stone", 4: "stone", 5: "glow", 9: "glow", 10: "plain"})


def build_all():
    out = {}
    for fn in (star_greatsword, void_scythe, inferno_axe, frost_halberd, verdant_staff, blood_katana,
               moon_glaive, thunder_hammer, abyss_trident):
        m = fn()
        out[m.name] = ([m], {"blade": m.name, "staff": m.name})
    for fn in (star_aegis, void_tome, rune_shield):
        m = fn()
        out[m.name] = ([m], {"shield": m.name, "quiver": m.name})
    for fn in (star_crown, inferno_horns, frost_halo, verdant_wreath, void_mask, oni_mask):
        m = fn()
        out[m.name] = ([m], {"helmet": m.name})
    for name, colors, build in (
            ("seraph_wings", ["#c9a43a", "#ffe08a", "#ffffff", "#fff0c0", "#f4f8ff", "#5fe0ff",
                              None, None, None, None, None, None, None, None, None, None], seraph),
            ("demon_wings", ["#2a1418", "#5a0f1e", "#3a0a12", "#d0d0dc", "#ff3a4a",
                             None, None, None, None, None, None, None, None, None, None, None], demon),
            ("crystal_wings", ["#5fa8e0", "#8af0ff", "#cfefff", "#ffffff", "#eaffff",
                               None, None, None, None, None, None, None, None, None, None, None], crystal),
            ("moon_wings", ["#8a94b8", "#c8d0e8", "#eef2ff", "#9fd3ff", "#ffffff", "#e8d48a",
                            None, None, None, None, None, None, None, None, None, None], lunar),
            ("butterfly_wings", ["#3a1a5a", "#8a4fd8", "#d8a8ff", "#ff9ad5", "#ffffff", "#5fe0ff",
                                 None, None, None, None, None, None, None, None, None, None], butterfly)):
        r, l = wing(name, colors, build)
        out[name] = ([r, l], {"back_r": r.name, "back_l": l.name})
    halo = halo_back()
    out[halo.name] = ([halo], {"back": halo.name})
    # 累積贊助各階專屬（2026-10-23）
    for name, colors, build in (
            ("patron_wings_ember", ["#8a3a1e", "#ff9f5a", "#ffb98a", "#ffd8b0", "#ffe08a", "#ff5a2a",
                                    None, None, None, None, None, None, None, None, None, None], ember),
            ("patron_wings_galaxy", ["#4a2470", "#8a4fd8", "#d68cff", "#e3a8ff", "#ffffff", "#9fd3ff",
                                     None, None, None, None, None, None, None, None, None, None], galaxy)):
        r, l = wing(name, colors, build)
        out[name] = ([r, l], {"back_r": r.name, "back_l": l.name})
    ph = patron_halo()
    out[ph.name] = ([ph], {"back": ph.name})
    pc = patron_crown()
    out[pc.name] = ([pc], {"helmet": pc.name})
    for fn in (patron_wisp, patron_lantern, patron_star):
        m = fn()
        out[m.name] = ([m], {"pet": m.name})
    for fn in (pet_wisp, pet_dragon, pet_owl, pet_slime, pet_golem, pet_tome, pet_fox, pet_moon_rabbit, pet_bat, pet_jelly):
        m = fn()
        out[m.name] = ([m], {"pet": m.name})
    BOW = lambda *c: list(c) + [None] * (16 - len(c))
    for art, colors, extras, mats in (
            ("star_longbow", BOW("#8a6a1e", "#d4a83a", "#ffe08a", "#2a3050", "#f4f6ff", "#5fe0ff", "#c8cde0", "#d8fbff",
                                 "#c9a43a", "#7fd8ff"), star_longbow_extras, {}),
            ("verdant_bow", BOW("#3a2410", "#5a3a1e", "#a8743a", "#1f6a46", "#e8f0d0", "#3affa0", "#a8743a", "#4fc08a",
                                "#4fc08a", "#d8ffe8"), verdant_bow_extras, {8: "feather", 9: "plain"}),
            ("blood_bow", BOW("#1a0a10", "#3a0a14", "#5a0f1e", "#2a2a30", "#d0d0dc", "#ff3a4a", "#3a3a44", "#ff2040",
                              "#8a8a98", "#ff3a4a"), blood_bow_extras, {0: "wood", 3: "cloth"}),
            ("sun_bow", BOW("#8a3a10", "#e07a1a", "#ffd27f", "#4a2410", "#fff6d0", "#ffb000", "#a8743a", "#ffe08a",
                            "#c9a43a", "#ff7a1a"), sun_bow_extras, {})):
        models, parts = bow_set(art, colors, extras, mats)
        out[art] = (models, parts)
    for art, colors, extras, mats in (
            ("inferno_repeater", BOW("#2a1410", "#6a3a10", "#3a0e0a", None, "#e0d0c0", "#ff7a1a", "#6a3a10", "#ffc24a",
                                     "#8a8a8a", "#ffb000"), inferno_extras, {9: "glow"}),
            ("frost_arbalest", BOW("#1a2e44", "#5a6a7a", "#5fa8e0", None, "#eaffff", "#8af0ff", "#b8c8d8", "#cfefff",
                                   "#b8c8d8", "#eaffff"), frost_extras, {2: "crystal"}),
            ("iron_crossbow", BOW("#3a2a1e", "#5a4a3a", "#4a5a74", None, "#d0d0d0", "#6fa8ff", "#6a5a4a", "#c8c8c8",
                                  "#8a8a8a", "#6fa8ff"), iron_extras, {}),
            ("shadow_crossbow", BOW("#1a1024", "#3a2058", "#2a2a34", None, "#b4b4d0", "#d24bff", "#3a2a44", "#ff4be0",
                                    "#6e6e86", "#d24bff"), shadow_extras, {0: "cloth"})):
        models, parts = crossbow_set(art, colors, extras, mats)
        out[art] = (models, parts)
    for theme in ARMOR3D:
        models, parts, extra = armor_set(theme)
        out[f"armor3d_{theme}"] = (models, parts, extra)
    # 2026-10 S1 贊助禮包限定「創世」系列
    m = genesis_blade()
    out[m.name] = ([m], {"blade": m.name, "staff": m.name})
    m = genesis_staff()
    out[m.name] = ([m], {"blade": m.name, "staff": m.name})
    m = genesis_aegis()
    out[m.name] = ([m], {"shield": m.name, "quiver": m.name})
    r, l = wing("genesis_wings", GENESIS, genesis_wing)
    out["genesis_wings"] = ([r, l], {"back_r": r.name, "back_l": l.name})
    m = pet_genesis_whale()
    out[m.name] = ([m], {"pet": m.name})
    models, parts = bow_set("genesis_bow", BOW("#1b2a5c", "#2a3a7a", "#5a3fb0", "#0b1230", "#c8f6ff", "#39e7ff", "#c8d6f0", "#7fe8ff",
                                                 "#f2c14e", "#ff5fd2"), genesis_bow_extras,
                            {0: "metal", 1: "metal", 2: "crystal", 3: "leather", 8: "metal"})
    out["genesis_bow"] = (models, parts)
    models, parts = crossbow_set("genesis_crossbow", BOW("#0b1230", "#1b2a5c", "#2a3a7a", None, "#c8f6ff", "#39e7ff", "#c8d6f0", "#7fe8ff",
                                                           "#f2c14e", "#ff5fd2"), genesis_crossbow_extras,
                                                      {0: "metal", 1: "metal", 2: "metal", 8: "metal"})
    out["genesis_crossbow"] = (models, parts)
    models, parts, extra = genesis_armor_set()
    out["armor3d_genesis"] = (models, parts, extra)
    # 2026-10 萬燈夜（萬聖節活動）限定
    for fn in (hw_pumpkin_scythe, hw_broom_staff, hw_candy_cane):
        m = fn()
        out[m.name] = ([m], {"blade": m.name, "staff": m.name})
    for fn in (hw_ghost_lantern, hw_coffin_shield):
        m = fn()
        out[m.name] = ([m], {"shield": m.name, "quiver": m.name})
    for fn in (hw_pumpkin_head, hw_witch_hat, hw_devil_horns):
        m = fn()
        out[m.name] = ([m], {"helmet": m.name})
    r, l = wing("hw_bat_wings", ["#141418", "#2a1a2e", "#3a2444", "#d0d0dc", "#a84fff",
                                 None, None, None, None, None, None, None, None, None, None, None], hw_bat_wing)
    out["hw_bat_wings"] = ([r, l], {"back_r": r.name, "back_l": l.name})
    m = hw_wisp_halo()
    out[m.name] = ([m], {"back": m.name})
    for fn in (hw_pet_pumpkin, hw_pet_ghost, hw_pet_cat, hw_pet_skull):
        m = fn()
        out[m.name] = ([m], {"pet": m.name})
    models, parts = bow_set("hw_bat_bow", BOW("#141418", "#2a2a34", "#4a4a58", "#3a2410", "#d0d0dc", "#ff3a4a", "#5a4430", "#a84fff",
                                              "#3a2a44", "#ff2040"), hw_bat_bow_extras,
                            {0: "stone", 1: "stone", 2: "stone", 8: "leather"})
    out["hw_bat_bow"] = (models, parts)
    models, parts = crossbow_set("hw_pumpkin_crossbow", BOW("#3a2410", "#6a4420", "#5a4430", "#2e6a1e", "#e0d0c0", "#ffd23a", "#6a4420",
                                                            "#ffa63a", "#8a8a8a", "#e2761a"), hw_pumpkin_crossbow_extras,
                                                 {3: "plain", 9: "leather"})
    out["hw_pumpkin_crossbow"] = (models, parts)
    # 2026-11-03 照站長：夏日系列（抽獎箱）
    for fn in (summer_surfboard, summer_watermelon_hammer, summer_coconut_staff, summer_popsicle_blade):
        m = fn()
        out[m.name] = ([m], {"blade": m.name, "staff": m.name})
    for fn in (summer_parasol_shield, summer_lifebuoy):
        m = fn()
        out[m.name] = ([m], {"shield": m.name, "quiver": m.name})
    for fn in (summer_straw_hat, summer_goggles, summer_shades):
        m = fn()
        out[m.name] = ([m], {"helmet": m.name})
    for name, colors, build in (
            ("summer_wave_wings", _pad([SUMMER_DEEP, SUMMER_SEA, "#4fc8ec", SUMMER_FOAM, "#ffffff", "#d8fbff"]), summer_wave),
            ("summer_seagull_wings", _pad(["#c8ccd4", "#ffffff", "#8a8f98", "#2a2a30", "#e8eaf0"]), summer_gull)):
        r, l = wing(name, colors, build)
        out[name] = ([r, l], {"back_r": r.name, "back_l": l.name})
    m = summer_sun_halo()
    out[m.name] = ([m], {"back": m.name})
    for fn in (summer_pet_turtle, summer_pet_crab, summer_pet_dolphin, summer_pet_parrot, summer_pet_icecream):
        m = fn()
        out[m.name] = ([m], {"pet": m.name})
    models, parts = bow_set("summer_coral_bow", BOW("#c8405a", SUMMER_CORAL, "#ffb0c8", SUMMER_SAND, "#ffffff", SUMMER_FOAM, SUMMER_WOOD_LIGHT,
                                                    SUMMER_FOAM, SUMMER_ORANGE, "#fff6ff"), summer_coral_bow_extras,
                            {0: "stone", 1: "stone", 2: "stone", 8: "stone"})
    out["summer_coral_bow"] = (models, parts)
    models, parts = crossbow_set("summer_water_crossbow", BOW(SUMMER_ORANGE, SUMMER_SEA, "#ffe13a", ("#bfeeff", 150), "#ffffff", SUMMER_FOAM,
                                                              SUMMER_FOAM, "#ffffff", "#d86a10", SUMMER_SEA), summer_water_crossbow_extras,
                                                 {0: "plain", 1: "plain", 2: "plain", 3: "glass", 8: "plain", 9: "water"})
    out["summer_water_crossbow"] = (models, parts)
    # 戰甲 tide（浪潮）、beach（沙灘衛士）已經在 ARMOR3D 裡，上面 for theme in ARMOR3D 那一圈就做了
    # （2026-11-03 照站長：星潮祭限定的 coral（珊瑚海妖鎧）也是；這次之前 ARMOR3D 只有贊助、抽獎箱的戰甲）
    # 2026-11-03 照站長：星潮祭限定（活動兌換；sponsor.yml 的 sf_*）
    m = sf_tide_trident()
    out[m.name] = ([m], {"blade": m.name, "staff": m.name})
    m = sf_shell_shield()
    out[m.name] = ([m], {"shield": m.name, "quiver": m.name})
    m = sf_pearl_crown()
    out[m.name] = ([m], {"helmet": m.name})
    r, l = wing("sf_kraken_wings", _pad([SF_INK, SF_INK_MID, SF_INK_LIGHT, SF_SUCKER, SF_TIP, "#ff9ad5"]), sf_kraken,
                {0: 'leather', 1: 'leather', 2: 'leather', 3: 'plain'})
    out["sf_kraken_wings"] = ([r, l], {"back_r": r.name, "back_l": l.name})
    for fn in (sf_pet_kraken, sf_pet_jellyfish):
        m = fn()
        out[m.name] = ([m], {"pet": m.name})
    return out




# ---- 預覽（簡單的等角投影，檢查形狀；每一面照貼圖集取樣，看得到材質） ----

def rotate_point(p, rot):
    if not rot:
        return p
    ang = math.radians(rot["angle"])
    o = rot["origin"]
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    c, s = math.cos(ang), math.sin(ang)
    if rot["axis"] == "x":
        y, z = y * c - z * s, y * s + z * c
    elif rot["axis"] == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + o[0], y + o[1], z + o[2])


def render(model, textures, size=240, yaw=35, pitch=25, grid=4):
    """textures：model.textures() 回的 {檔名: Image}。每一面切成最多 grid×grid 小格，各取貼圖那一塊的平均色。"""
    img = Image.new("RGBA", (size, size), (40, 42, 54, 255))
    draw = ImageDraw.Draw(img, "RGBA")
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def proj(p):
        x, y, z = p[0] - 8, p[1] - 8, p[2] - 8
        x, z = x * cy - z * sy, x * sy + z * cy
        y, z = y * cp - z * sp, y * sp + z * cp
        scale = size / 40
        return (size / 2 + x * scale, size / 2 - y * scale, z)

    # 每一面四個角：照「貼圖左上、右上、右下、左下」的順序（v 往下 = y 往下；上下面 v = z）
    face_corners = {
        "north": [(0, 1, 0), (1, 1, 0), (1, 0, 0), (0, 0, 0)],
        "south": [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)],
        "west": [(0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)],
        "east": [(1, 1, 0), (1, 1, 1), (1, 0, 1), (1, 0, 0)],
        "up": [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],
        "down": [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
    }
    shade = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.62, "west": 0.62}
    loaded = {name: (im, im.load()) for name, im in textures.items()}
    polys = []
    for el in model.elements:
        lo, hi = el["from"], el["to"]
        for f, data in el["faces"].items():
            t = data["_tex"]
            im, px = loaded[f"{model.name}_glow.png" if t["glow"] else f"{model.name}.png"]
            tw = im.width
            u0, v0, u1, v1 = [c * tw / 16 for c in data["uv"]]
            pw, ph = max(1, int(round(u1 - u0))), max(1, int(round(v1 - v0)))
            gx, gy = min(grid, pw), min(grid, ph)
            pts = []
            for c in face_corners[f]:
                p = tuple(lo[i] if c[i] == 0 else hi[i] for i in range(3))
                pts.append(proj(rotate_point(p, el.get("rotation"))))
            k = 1.0 if el.get("light_emission") else shade[f]
            depth = sum(p[2] for p in pts) / 4
            tl, tr, br, bl = pts
            for j in range(gy):
                for i in range(gx):
                    # 小格的四個角（雙線性）
                    def at(s, tt):
                        x = (tl[0] * (1 - s) + tr[0] * s) * (1 - tt) + (bl[0] * (1 - s) + br[0] * s) * tt
                        y = (tl[1] * (1 - s) + tr[1] * s) * (1 - tt) + (bl[1] * (1 - s) + br[1] * s) * tt
                        return (x, y)
                    quad = [at(i / gx, j / gy), at((i + 1) / gx, j / gy), at((i + 1) / gx, (j + 1) / gy), at(i / gx, (j + 1) / gy)]
                    # 這一小格對應的貼圖像素平均
                    xa, xb = int(u0 + pw * i / gx), max(int(u0 + pw * i / gx) + 1, int(u0 + pw * (i + 1) / gx))
                    ya, yb = int(v0 + ph * j / gy), max(int(v0 + ph * j / gy) + 1, int(v0 + ph * (j + 1) / gy))
                    acc = [0, 0, 0, 0]
                    n = 0
                    for yy in range(ya, yb):
                        for xx in range(xa, xb):
                            c = px[min(xx, tw - 1), min(yy, im.height - 1)]
                            for q in range(4):
                                acc[q] += c[q]
                            n += 1
                    col = tuple(int(acc[q] / n * (k if q < 3 else 1)) for q in range(4))
                    polys.append((depth - 0.001 * (i + j), quad, col))
            polys.append((depth - 0.01, [(p[0], p[1]) for p in pts], None))   # 這一面的外框線
    polys.sort(key=lambda t: -t[0])
    for _, pts, col in polys:
        if col is None:
            draw.polygon(pts, outline=(0, 0, 0, 70))
        else:
            draw.polygon(pts, fill=col)
    return img


# ======================================================================
# 累積贊助各階專屬（2026-10-23）：用那一階的顏色，sponsor.yml 的 tiers.cosmetics 到階就送
#   3000 星芒 #7fe0c0：星芒之環（背飾）　5000 星輝 #f5d76e：星輝之靈（寵物）
#   10000 星耀 #ff9f5a：星耀之翼（背飾）、星耀提燈（寵物）　15000 星曜 #d68cff：星曜之翼（背飾）、星曜冠（頭飾）
#   25000 至尊 #ffd24a／#ff8fe0：至尊星核（寵物）
# ======================================================================

def patron_halo():
    m = Model("patron_halo", ["#2a8a6e", "#7fe0c0", "#d8fff2", "#ffffff",
                              "#a8f5dc", None, None, None,
                              None, None, None, None, None, None, None, None], "back")
    m.materials = {}
    r = 8.4
    for i in range(6):
        ang = i * 60 + 30
        cx = 8 + r * math.cos(math.radians(ang))
        cy = 8 + r * math.sin(math.radians(ang))
        # 六邊形的環：每一邊跟半徑方向垂直（30°、210° 的邊轉 −45°… 用 22.5 的倍數只能近似：直接用 45／−45／0）
        rot_angle = {30: -45, 90: 0, 150: 45, 210: -45, 270: 0, 330: 45}[ang]
        m.box((cx - 4.6, cy - 0.5, 7.5), (cx + 4.6, cy + 0.5, 8.5), 1, rot=(rot_angle, "z", (cx, cy, 8)) if rot_angle else None, glow=True)
    # 六芒星
    m.box((7.3, 2.5, 7.6), (8.7, 13.5, 8.4), 2, glow=True)
    m.box((2.5, 7.3, 7.6), (13.5, 8.7, 8.4), 2, rot=(45, "z", (8, 8, 8)), glow=True)
    m.box((2.5, 7.3, 7.6), (13.5, 8.7, 8.4), 2, rot=(-45, "z", (8, 8, 8)), glow=True)
    m.box((6.9, 6.9, 7.4), (9.1, 9.1, 8.6), 3, rot=(45, "z", (8, 8, 8)), glow=True)     # 核
    for i in range(6):
        ang = i * 60
        cx = 8 + 11.6 * math.cos(math.radians(ang))
        cy = 8 + 11.6 * math.sin(math.radians(ang))
        m.box((cx - 0.7, cy - 0.7, 7.7), (cx + 0.7, cy + 0.7, 8.3), 4, rot=(45, "z", (cx, cy, 8)), glow=True)   # 外圈的星點
    return m


def patron_wisp():
    m = Model("patron_wisp", ["#f5d76e", "#fff4c0", "#ffffff", "#ffe58a",
                              "#c9a43a", "#8a6a1e", None, None,
                              None, None, None, None, None, None, None, None], "pet")
    m.materials = {4: 'metal', 5: 'metal'}
    m.box((6, 6, 6), (10, 10, 10), 1, glow=True)             # 核
    m.box((5.2, 6.4, 6.4), (10.8, 9.6, 9.6), 0, glow=True)
    m.box((6.4, 5.2, 6.4), (9.6, 10.8, 9.6), 0, glow=True)
    m.box((6.4, 6.4, 5.2), (9.6, 9.6, 10.8), 0, glow=True)
    m.box((6.8, 8.2, 5.0), (7.6, 9.0, 5.2), 5)               # 眼睛
    m.box((8.4, 8.2, 5.0), (9.2, 9.0, 5.2), 5)
    ring(m, 7.6, 8.4, 1.6, 0.6, 4)                           # 金環（比星靈大）
    ring(m, 10.4, 11.0, 4.2, 0.5, 3, glow=True)              # 頭上的光環
    for (x, y, z) in ((2.6, 12.4, 8), (13.4, 3.6, 7), (8, 14, 12.4), (3.6, 3.6, 3.6), (12.6, 11.6, 12.2)):
        m.box((x - 0.5, y - 0.5, z - 0.5), (x + 0.5, y + 0.5, z + 0.5), 2, rot=(45, "z", (x, y, z)), glow=True)   # 星點
    return m


def ember(m):
    """星耀之翼：三道火焰狀的羽翼往外揚。"""
    m.box((8, 9, 7.4), (16, 10.6, 8.6), 0)                   # 翼骨
    m.box((15.4, 10, 7.5), (22, 11.6, 8.5), 0)
    for i, (x, length, c) in enumerate(((9, 7, 1), (12.5, 9.5, 1), (16, 12, 2), (19.5, 10, 2), (22.5, 7, 3))):
        m.box((x, 10 - length, 7.7), (x + 2.6, 10.4 + i * 0.3, 8.3), c)                  # 焰羽
        m.box((x + 0.5, 10 - length - 1.2, 7.8), (x + 2.1, 10 - length, 8.2), 4, glow=True)   # 羽尖的火光
    for i, x in enumerate(range(9, 23, 3)):
        m.box((x, 8.2 + i * 0.3, 8.3), (x + 3, 10.6 + i * 0.3, 8.8), 3)                 # 覆羽
    m.box((22, 11.4, 7.6), (25, 12.6, 8.4), 4, glow=True)    # 翼尖的星
    m.box((9.5, 5.5, 7.85), (14, 6.3, 8.15), 5, glow=True)   # 焰紋
    m.box((16.5, 3.0, 7.85), (21, 3.8, 8.15), 5, glow=True)


def patron_lantern():
    m = Model("patron_lantern", ["#5a3a1e", "#8a5a2a", "#ff9f5a", "#ffe08a",
                                 "#ffb98a", "#2a1a10", None, None,
                                 None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'wood', 1: 'wood', 5: 'metal'}
    m.box((7.4, 12.2, 7.4), (8.6, 13.6, 8.6), 0)             # 提把
    m.box((6.2, 11.4, 6.2), (9.8, 12.4, 9.8), 1)             # 頂蓋
    m.box((5.6, 5.0, 5.6), (10.4, 11.4, 10.4), 2, glow=True)  # 燈身（發光）
    for (x, z) in ((5.4, 5.4), (9.8, 5.4), (5.4, 9.8), (9.8, 9.8)):
        m.box((x, 4.8, z), (x + 0.8, 11.6, z + 0.8), 5)       # 四根框
    m.box((6.2, 4.0, 6.2), (9.8, 5.0, 9.8), 1)               # 底座
    m.box((7.0, 7.0, 7.0), (9.0, 9.4, 9.0), 3, glow=True)    # 燈芯
    for (x, y, z) in ((3.5, 13.5, 8), (12.5, 2.5, 7.5), (8, 15.5, 4.5)):
        m.box((x - 0.4, y - 0.4, z - 0.4), (x + 0.4, y + 0.4, z + 0.4), 4, glow=True)   # 飄出的火星
    return m


def galaxy(m):
    """星曜之翼：紫色晶翼，比晶翼多一層、帶星點。"""
    m.box((8, 9.2, 7.5), (13, 10.6, 8.5), 0)                 # 晶核
    shards = [((12, 9, 7.4), (24, 11.2, 8.6), 22.5, 1), ((11, 6, 7.5), (22, 7.8, 8.5), 0, 2),
              ((11, 3, 7.6), (19, 4.6, 8.4), -22.5, 1), ((13, 12, 7.5), (26, 13.8, 8.5), 45, 2),
              ((10, 0, 7.6), (16, 1.4, 8.4), -45, 3), ((14, 15, 7.6), (22, 16.2, 8.4), 45, 3)]
    for a, b, ang, c in shards:
        origin = (a[0], (a[1] + b[1]) / 2, 8)
        m.box(a, b, c, rot=(ang, "z", origin) if ang else None)
        tip_a = (b[0], a[1] + 0.3, a[2] + 0.2)
        tip_b = (b[0] + 2.4, b[1] - 0.3, b[2] - 0.2)
        m.box(tip_a, tip_b, 4, rot=(ang, "z", origin) if ang else None, glow=True)
    for (x, y) in ((15, 8), (19, 5), (21, 11), (17, 13.5)):
        m.box((x - 0.5, y - 0.5, 7.7), (x + 0.5, y + 0.5, 8.3), 5, rot=(45, "z", (x, y, 8)), glow=True)   # 星點


def patron_crown():
    m = Model("patron_crown", ["#6a3a9a", "#d68cff", "#4a2470", "#ffffff",
                               "#e3a8ff", "#9fd3ff", "#f4f6ff", None,
                               None, None, None, None, None, None, None, None], "headgear")
    m.materials = {0: 'metal', 1: 'metal', 2: 'metal'}
    ring(m, 13.2, 15.4, 1.2, 1.0, 0)
    ring(m, 13.0, 13.4, 1.0, 0.6, 2)
    for side in range(4):
        for (x, h, w) in ((2.6, 2.2, 1.0), (5.4, 3.4, 1.2), (7.3, 4.6, 1.4), (9.6, 3.4, 1.2), (12.4, 2.2, 1.0)):
            if side == 0:
                a, b = (x, 15.4, 1.2), (x + w, 15.4 + h, 2.0)
            elif side == 1:
                a, b = (x, 15.4, 14.0), (x + w, 15.4 + h, 14.8)
            elif side == 2:
                a, b = (1.2, 15.4, x), (2.0, 15.4 + h, x + w)
            else:
                a, b = (14.0, 15.4, x), (14.8, 15.4 + h, x + w)
            m.box(a, b, 1)
            tip = (a[0] + (0.2 if side < 2 else 0), 15.4 + h, a[2] + (0 if side < 2 else 0.2))
            m.box(tip, (tip[0] + (w - 0.4 if side < 2 else 0.8), 15.4 + h + 0.8, tip[2] + (0.8 if side < 2 else w - 0.4)), 4, glow=True)
    m.box((7.0, 20.0, 1.0), (9.0, 21.6, 2.2), 5, rot=(45, "z", (8, 20.8, 1.6)), glow=True)   # 正面最高那根上的星
    m.box((7.0, 13.6, 0.8), (9.0, 15.0, 1.2), 3, glow=True)   # 額前的寶石
    m.box((3.6, 13.8, 0.9), (4.6, 14.6, 1.2), 6, glow=True)
    m.box((11.4, 13.8, 0.9), (12.4, 14.6, 1.2), 6, glow=True)
    return m


def patron_star():
    m = Model("patron_star", ["#c9a43a", "#ffd24a", "#fff0b0", "#ffffff",
                              "#ff8fe0", "#ffe27a", "#ffb0ee", None,
                              None, None, None, None, None, None, None, None], "pet")
    m.materials = {0: 'plain'}
    m.box((6.4, 6.4, 6.4), (9.6, 9.6, 9.6), 2, rot=(45, "z", (8, 8, 8)), glow=True)   # 星核
    m.box((6.8, 6.8, 6.8), (9.2, 9.2, 9.2), 3, rot=(45, "x", (8, 8, 8)), glow=True)
    m.box((7.4, 1.5, 7.4), (8.6, 14.5, 8.6), 1, glow=True)                            # 四道星芒
    m.box((1.5, 7.4, 7.4), (14.5, 8.6, 8.6), 1, glow=True)
    m.box((7.4, 7.4, 2.5), (8.6, 8.6, 13.5), 5, glow=True)
    m.box((7.0, 8.2, 5.4), (7.8, 9.0, 5.6), 0)               # 眼睛
    m.box((8.2, 8.2, 5.4), (9.0, 9.0, 5.6), 0)
    ring(m, 7.5, 8.5, -1.5, 0.7, 4, glow=True)               # 粉色的外環
    ring(m, 7.7, 8.3, -3.2, 0.5, 6, glow=True)
    for (x, y, z) in ((-1, 13, 8), (17, 3, 8), (8, 17, 0), (8, -1, 16)):
        m.box((x - 0.6, y - 0.6, z - 0.6), (x + 0.6, y + 0.6, z + 0.6), 4, rot=(45, "z", (x, y, z)), glow=True)   # 繞著轉的星點
    return m


# 手做的模型：代號 → 部位（模型的貼圖要寫成 exilecore:item/skin3d/<代號>；tintindex 不用自己寫，讀進來時會補 0）
CUSTOM = {
    "fire_spear": ("blade", "staff"),   # 火尖槍（2026-10，玩家用 Blockbench 做的）
}


DIM_TINT = [0.42, 0.42, 0.48]   # 未擁有變灰的顏色（乘上去：偏暗、帶一點點藍灰）


def dim_tree(node):
    """深拷貝一棵物品模型樹，每個 {"type": "minecraft:model"} 節點加 tints（整個模型乘上 DIM_TINT）。"""
    node = json.loads(json.dumps(node))

    def walk(n):
        if isinstance(n, dict):
            if n.get("type") == "minecraft:model":
                n["tints"] = [{"type": "minecraft:constant", "value": list(DIM_TINT)}]
            for v in n.values():
                walk(v)
        elif isinstance(n, list):
            for v in n:
                walk(v)
    walk(node)
    return node


def item_definition(model):
    """物品定義：custom_model_data 的旗標 0 是 true 就用變灰的那棵樹，不然照原本的（見檔頭「未擁有變灰」）。"""
    return {"model": {"type": "minecraft:condition", "property": "minecraft:custom_model_data", "index": 0,
                      "on_true": dim_tree(model), "on_false": model}}


def add_tintindex(model_json):
    """手做的模型（Blockbench）：每一面補 tintindex 0，物品定義的 tints 才套得上。"""
    for el in model_json.get("elements", []):
        for face in el.get("faces", {}).values():
            face.setdefault("tintindex", 0)
    return model_json




def main():
    import shutil
    shutil.rmtree(ROOT, ignore_errors=True)   # 清掉上次的中間檔，免得舊貼圖混進來
    os.makedirs(ROOT, exist_ok=True)
    files = []
    everything = build_all()
    previews = []

    def write(rel, data):
        path = os.path.join(ROOT, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if isinstance(data, Image.Image):
            data.save(path, optimize=True)
        else:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
        files.append(rel)

    for art, entry in everything.items():
        models, parts = entry[0], entry[1]
        for rel, data in (entry[2] if len(entry) > 2 else {}).items():
            write(rel, data)
        for m in models:
            tex = m.textures()
            for fname, img in tex.items():
                write(f"assets/{NS}/textures/item/skin3d/{fname}", img)
                if fname.endswith("_glow.png"):
                    # 發光面的動畫貼圖：GLOW_FRAMES 格直排，每格 GLOW_FRAMETIME tick、格與格之間內插（亮度慢慢脈動）
                    write(f"assets/{NS}/textures/item/skin3d/{fname}.mcmeta",
                          {"animation": {"frametime": GLOW_FRAMETIME, "interpolate": True}})
            write(f"assets/{NS}/models/item/skin3d/{m.name}.json", m.json())
            previews.append((m.name, render(m, tex)))
        for part, model_name in parts.items():
            model = model_name if isinstance(model_name, dict) else {"type": "minecraft:model", "model": f"{NS}:item/skin3d/{model_name}"}
            write(f"assets/{NS}/items/skin/{art}_{part}.json", item_definition(model))
    # 手做的模型（Blockbench 匯出）：tools/models3d_custom/<代號>.json + <代號>.png，CUSTOM 寫部位（武器 = blade、staff）
    custom = os.path.join(os.path.dirname(__file__), "models3d_custom")
    for art, parts in CUSTOM.items():
        with open(os.path.join(custom, art + ".json"), encoding="utf-8") as f:
            write(f"assets/{NS}/models/item/skin3d/{art}.json", add_tintindex(json.load(f)))   # 2026-11-03：補 tintindex，變灰才套得上
        write(f"assets/{NS}/textures/item/skin3d/{art}.png", Image.open(os.path.join(custom, art + ".png")).convert("RGBA"))
        for part in parts:
            write(f"assets/{NS}/items/skin/{art}_{part}.json",
                  item_definition({"type": "minecraft:model", "model": f"{NS}:item/skin3d/{art}"}))
    # 打包成一個 zip（web/pack3d.zip）：UiPack 把裡面的檔案照路徑放進介面資源包；資料夾只是給人看、給預覽用
    import zipfile
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in sorted(files):
            info = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))  # 固定時間：沒改的話 zip 每次都一樣
            with open(os.path.join(ROOT, rel), "rb") as f:
                z.writestr(info, f.read(), zipfile.ZIP_DEFLATED)
    print(len(everything), "套,", len(files), "個檔案,", os.path.getsize(ZIP) // 1024, "KB")
    if "--preview" in sys.argv:
        out = sys.argv[sys.argv.index("--preview") + 1]
        cols, cell = 6, 240
        rows = (len(previews) + cols - 1) // cols
        sheet = Image.new("RGBA", (cols * cell, rows * (cell + 20)), (20, 20, 28, 255))
        d = ImageDraw.Draw(sheet)
        for i, (name, img) in enumerate(previews):
            x, y = (i % cols) * cell, (i // cols) * (cell + 20)
            sheet.paste(img, (x, y))
            d.text((x + 6, y + cell + 2), name, fill=(220, 220, 230, 255))
        sheet.save(out)


if __name__ == "__main__":
    main()
