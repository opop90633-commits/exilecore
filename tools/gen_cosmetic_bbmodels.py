#!/usr/bin/env python3
"""
3D 寵物與背飾的 BetterModel 版（2026-11-03 照站長）：把 tools/gen_models3d.py 堆出來的翅膀、光輪、寵物轉成 .bbmodel
（Blockbench 格式、骨架＋循環動畫），BetterModel 掛在「玩家」實體上演；伺服器端在 BetterModelCosmetics.java。
這次之前：背飾、寵物是放在騎在玩家身上的 ItemDisplay 上、每 tick 自己算變形（ModelCosmetics.java）；沒裝 BetterModel 時還是走那條。

模型資料直接 import gen_models3d 的 build_all() 拿（不碰那個檔）；貼圖用 Model.textures() 的 PIL 圖（跟 uv 一定一致），
沒有這個方法時才從 src/main/resources/web/pack3d.zip 讀 assets/exilecore/textures/item/skin3d/<name>.png。

座標換算（兩套慣例的差別，實測過的依據寫在 ModelCosmetics.java 與 gen_emotes.py）：
  - 物品模型：0～16、中心 8,8,8；ItemDisplay 掛在玩家身上時 +Z 是玩家的前面、−Z 後面、+X 玩家的左手邊（yaw 0 面向 +Z）。
  - BetterModel（Blockbench 實體模型，跟 steve 一樣）：原點在玩家腳底、16 單位 = 1 格、模型面向 −Z，角色的右手在 +X、後面是 +Z。
  所以物品模型 → bbmodel 是「繞 Y 轉 180 度」：(x, y, z) → (−(x−8)·s, (y−8)·s, −(z−8)·s) ＋ 偏移；
  方塊自己的 rotation：x、z 軸的角度變號、y 軸不變；north↔south、east↔west 的面對調（uv 不用動）。
  偏移（格 × 16）：翅膀 (0, +1.32, +0.42)＝(0, 21.12, 6.72)、光輪再 +0.15 高、+0.08 後；寵物左後上方 (−0.85, +1.35, +0.7)＝(−13.6, 21.6, 11.2)。
  縮放：翅膀 0.9、光輪 0.8、寵物 0.55（bbmodel 沒有整體 scale：方塊座標、轉軸全部先乘）。
動畫值的慣例照 format_version 5.0（跟 emotes 的 bbmodel 一樣；BetterModel 讀 5.x 時 rotation 的 x、z 會變號、position 的 x、z 變號再 ÷16）。
骨頭名稱避開 BetterModel 的標籤前綴（h_、b_、p_、glow_…）。
2026-11-03 星潮祭：模型可以自己標骨頭——gen_models3d 的 box(part=("路徑", 轉軸))（element 的 "_part"）；有標的模型照標記分骨頭
（tagged_bones：路徑 a/b 是 b 掛在 a 下面、沒標的方塊在 root），動畫照 TAGGED_ANIMS 裡那一套（海妖觸手一段一段接著擺、
小海妖 8 條觸手輪流擺、小水母的傘一縮一放）。這次之前只能照位置猜（翅膀離中心遠的當外段、寵物最低的 30% 當觸手）。

輸出：src/main/resources/cosmetics_bm/<art>.bbmodel（模型名稱 ec_<art>）、index.txt（每行「<art> <種類> <動畫,…>」）。
用法：python3 tools/gen_cosmetic_bbmodels.py [--check] [--preview 側視圖.png]
"""
import base64
import io
import json
import math
import os
import sys
import uuid
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
sys.path.insert(0, HERE)
import gen_models3d as g3  # noqa: E402

OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "cosmetics_bm")
ZIP = os.path.join(ROOT, "src", "main", "resources", "web", "pack3d.zip")
NS = uuid.UUID("5c1e0a9a-7b3d-4c62-9d0e-ec0b1a2c3d4e")
PREFIX = "ec_"

# 偏移（單位：1/16 格）與縮放
WING_OFFSET = (0.0, 1.32 * 16, 0.42 * 16)
WING_SCALE = 0.9
HALO_OFFSET = (0.0, (1.32 + 0.15) * 16, (0.42 + 0.08) * 16)
HALO_SCALE = 0.8
PET_OFFSET = (-0.85 * 16, 1.35 * 16, 0.7 * 16)
PET_SCALE = 0.55
# 物品模型 → BetterModel 要不要繞 Y 轉 180 度（True = 跟現在 ItemDisplay 看到的一模一樣，寵物的臉照舊朝後、對著鏡頭）
FLIP = True

FACE_SWAP = {"north": "south", "south": "north", "east": "west", "west": "east", "up": "up", "down": "down"}
FACES = ("north", "east", "south", "west", "up", "down")


def uid(*parts):
    return str(uuid.uuid5(NS, "/".join(str(p) for p in parts)))


def r3(v):
    return [round(float(x), 4) for x in v]


# ---- 座標換算 ----

def convert_point(p, scale, offset):
    x, y, z = ((p[0] - 8) * scale, (p[1] - 8) * scale, (p[2] - 8) * scale)
    if FLIP:
        x, z = -x, -z
    return (x + offset[0], y + offset[1], z + offset[2])


def convert_cube(el, scale, offset, tex_sizes):
    """物品模型的 element → bbmodel 的 cube（uv 照搬：物品模型 0..16 → 貼圖像素）。"""
    a = convert_point(el["from"], scale, offset)
    b = convert_point(el["to"], scale, offset)
    lo = [min(a[i], b[i]) for i in range(3)]
    hi = [max(a[i], b[i]) for i in range(3)]
    cube = {"from": r3(lo), "to": r3(hi), "origin": r3(lo), "rotation": [0, 0, 0]}
    rot = el.get("rotation")
    if rot:
        axis, angle = rot["axis"], float(rot["angle"])
        if FLIP and axis in ("x", "z"):
            angle = -angle
        cube["rotation"] = [angle if axis == "x" else 0, angle if axis == "y" else 0, angle if axis == "z" else 0]
        cube["origin"] = r3(convert_point(rot["origin"], scale, offset))
    faces = {}
    for face in FACES:
        faces[face] = {"uv": [0, 0, 0, 0], "texture": None}
    for face, data in el["faces"].items():
        target = FACE_SWAP[face] if FLIP else face
        tex = data.get("texture", "#0")
        index = int(str(tex).lstrip("#")) if str(tex).lstrip("#").isdigit() else 0
        size = tex_sizes.get(index, 16)
        s = size / 16.0
        out = {"uv": [round(v * s, 4) for v in data["uv"]], "texture": index}
        if FLIP and face in ("up", "down"):
            out["rotation"] = 180  # 轉了 180 度：上下面的圖跟著轉（材質是色塊，看不出差別）
        faces[target] = out
    cube["faces"] = faces
    if el.get("light_emission"):
        cube["light_emission"] = int(el["light_emission"])
    if el.get("_part"):   # 2026-11-03 星潮祭：模型自己標的骨頭（路徑、轉軸換算到 bbmodel 座標）
        cube["_part"] = {"path": el["_part"]["path"], "pivot": r3(convert_point(el["_part"]["pivot"], scale, offset))}
    return cube


# ---- 貼圖 ----

def textures_of(model):
    """{索引: (檔名, PIL 圖)}：0 = 主貼圖、1 = 發光（直排的動畫格）。"""
    images = {}
    if hasattr(model, "textures") and callable(model.textures):
        images = dict(model.textures())
    else:
        from PIL import Image
        with zipfile.ZipFile(ZIP) as z:
            for suffix in ("", "_glow"):
                path = f"assets/exilecore/textures/item/skin3d/{model.name}{suffix}.png"
                if path in z.namelist():
                    images[f"{model.name}{suffix}.png"] = Image.open(io.BytesIO(z.read(path))).convert("RGBA")
    out = {}
    main = images.get(f"{model.name}.png")
    if main is None:
        raise SystemExit(f"{model.name}：沒有貼圖")
    out[0] = (f"{model.name}.png", main)
    glow = images.get(f"{model.name}_glow.png")
    if glow is not None:
        out[1] = (f"{model.name}_glow.png", glow)
    return out


def texture_json(index, name, img, model_name):
    w, h = img.size
    frames = h // w if h > w else 1
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    tex = {"path": "", "name": name, "folder": "", "namespace": "", "id": str(index), "width": w, "height": h,
           "uv_width": w, "uv_height": w if frames > 1 else h, "particle": index == 0, "use_as_default": False,
           "layers_enabled": False, "sync_to_project": "", "render_mode": "default", "render_sides": "auto",
           "frame_time": int(getattr(g3, "GLOW_FRAMETIME", 3)) if frames > 1 else 1, "frame_order_type": "loop",
           "frame_order": "", "frame_interpolate": False, "visible": True, "mode": "bitmap", "saved": True,
           "uuid": uid("tex", model_name, index), "relative_path": "",
           "source": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode("ascii")}
    return tex, w


# ---- 骨架 ----

class Bone:
    def __init__(self, name, origin):
        self.name = name
        self.origin = tuple(origin)
        self.cubes = []      # cube dict
        self.children = []   # Bone
        self.uuid = None

    def all_cubes(self):
        out = list(self.cubes)
        for c in self.children:
            out.extend(c.all_cubes())
        return out


def center(cube):
    return [(cube["from"][i] + cube["to"][i]) / 2 for i in range(3)]


def bounds(cubes):
    lo = [min(c["from"][i] for c in cubes) for i in range(3)]
    hi = [max(c["to"][i] for c in cubes) for i in range(3)]
    return lo, hi


def split(cubes, pick):
    """分成（挑中的, 其他的）。"""
    yes = [c for c in cubes if pick(c)]
    no = [c for c in cubes if not pick(c)]
    return yes, no


def wing_bones(cubes, origin):
    """翅膀：root → wing_r（BetterModel 的 +X = 角色右邊）／wing_l → inner／outer（離中心超過 60% 的方塊）。"""
    root = Bone("root", origin)
    for side, sign in (("r", 1), ("l", -1)):
        mine = [c for c in cubes if sign * (center(c)[0] - origin[0]) > 0]
        if not mine:
            continue
        wing = Bone(f"wing_{side}", origin)
        reach = max(abs(center(c)[0] - origin[0]) for c in mine)
        outer_cubes, inner_cubes = split(mine, lambda c: abs(center(c)[0] - origin[0]) > 0.6 * reach)
        inner = Bone(f"inner_{side}", origin)
        inner.cubes = inner_cubes
        wing.children.append(inner)
        if outer_cubes:
            outer = Bone(f"outer_{side}", (origin[0] + sign * 0.6 * reach, origin[1], origin[2]))
            outer.cubes = outer_cubes
            wing.children.append(outer)
        root.children.append(wing)
    return root


# 寵物的部位：名稱關鍵字 → (骨頭名, 怎麼挑, 轉軸位置, 動畫)
def pet_part(art):
    key = art.lower()
    if any(k in key for k in ("dolphin", "fox", "cat", "rabbit")):
        return "tail"
    if any(k in key for k in ("dragon", "owl", "parrot", "bat")):
        return "wings"
    if "jelly" in key:
        return "tentacles"
    if "crab" in key:
        return "legs"
    return None


def pet_bones(art, cubes, origin):
    root = Bone("root", origin)
    part = pet_part(art)
    lo, hi = bounds(cubes)
    rest = cubes
    if part == "tail":
        # 尾巴：物品模型 z 最大的那一側 25%；FLIP 之後是 bbmodel 的 z 最小側（後面的方向在換算時一起翻了）
        axis_lo, axis_hi = lo[2], hi[2]
        edge = axis_lo + 0.25 * (axis_hi - axis_lo) if FLIP else axis_hi - 0.25 * (axis_hi - axis_lo)
        tail, rest = split(cubes, lambda c: (center(c)[2] < edge) if FLIP else (center(c)[2] > edge))
        if tail and rest:
            tlo, thi = bounds(tail)
            bone = Bone("tail", (((tlo[0] + thi[0]) / 2), (tlo[1] + thi[1]) / 2, edge))
            bone.cubes = tail
            root.children.append(bone)
        else:
            rest = cubes
    elif part == "wings":
        mid = (lo[0] + hi[0]) / 2
        span = (hi[0] - lo[0]) / 2
        picked = []
        for side, sign in (("r", 1), ("l", -1)):
            mine, _ = split(cubes, lambda c, s=sign: s * (center(c)[0] - mid) > 0.5 * span)
            if mine:
                wlo, whi = bounds(mine)
                bone = Bone(f"wing_{side}", (mid + sign * 0.5 * span, (wlo[1] + whi[1]) / 2, (wlo[2] + whi[2]) / 2))
                bone.cubes = mine
                root.children.append(bone)
                picked.extend(mine)
        rest = [c for c in cubes if c not in picked]
    elif part in ("tentacles", "legs"):
        edge = lo[1] + 0.3 * (hi[1] - lo[1])
        low, rest = split(cubes, lambda c: center(c)[1] < edge)
        if low and rest:
            llo, lhi = bounds(low)
            bone = Bone(part, ((llo[0] + lhi[0]) / 2, edge, (llo[2] + lhi[2]) / 2))
            bone.cubes = low
            root.children.append(bone)
        else:
            rest = cubes
    root.cubes = rest
    return root


def tagged_bones(cubes, origin):
    """2026-11-03 星潮祭：照方塊的 _part 分骨頭。路徑 "a/b/c" = root → a → b → c；轉軸是標在方塊上的那一個（同一個骨頭取第一個），
    中間沒方塊的骨頭借第一個子骨頭的轉軸；沒標的方塊放 root。"""
    root = Bone("root", origin)
    by_path = {"": root}
    for cube in cubes:
        part = cube.pop("_part", None)
        if not part:
            root.cubes.append(cube)
            continue
        names = part["path"].split("/")
        for depth in range(len(names)):
            path = "/".join(names[:depth + 1])
            if path not in by_path:
                bone = Bone(names[depth], part["pivot"])
                by_path["/".join(names[:depth])].children.append(bone)
                by_path[path] = bone
        by_path[part["path"]].cubes.append(cube)
    return root


def bones_named(bone, prefix=""):
    """名字以 prefix 開頭的骨頭（含子孫）。"""
    out = [bone] if bone.name.startswith(prefix) and bone.name != "root" else []
    for c in bone.children:
        out.extend(bones_named(c, prefix))
    return out


# ---- 動畫 ----

def wave(amplitude, period, phase=0.0, offset=0.0):
    """正弦：振幅（度或像素）、週期（秒）、相位延遲（秒）。"""
    return lambda t: offset + amplitude * math.sin(2 * math.pi * (t - phase) / period)


def keyframes(name, bone, channel, length, fx=None, fy=None, fz=None, step=None, interp="catmullrom"):
    """每 step 秒取一格（頭尾都有，loop 才接得順）。"""
    step = step or length / 16
    n = max(1, int(round(length / step)))
    frames = []
    for i in range(n + 1):
        t = round(i * length / n, 4)
        x = fx(t) if fx else 0.0
        y = fy(t) if fy else 0.0
        z = fz(t) if fz else 0.0
        frames.append({"channel": channel, "data_points": [{"x": str(round(x, 3)), "y": str(round(y, 3)), "z": str(round(z, 3))}],
                       "uuid": uid("kf", name, bone.name, channel, i), "time": t, "color": -1, "interpolation": interp})
    return frames


def animation(name, length, tracks):
    """tracks：[(bone, keyframes)]"""
    animators = {}
    for bone, frames in tracks:
        if not frames:
            continue
        animators.setdefault(bone.uuid, {"name": bone.name, "type": "bone", "keyframes": []})["keyframes"].extend(frames)
    return {"uuid": uid("anim", name), "name": name, "loop": "loop", "override": False, "length": round(length, 4),
            "snapping": 24, "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "",
            "animators": animators}


def find(bone, name):
    if bone.name == name:
        return bone
    for c in bone.children:
        got = find(c, name)
        if got:
            return got
    return None


def wing_animations(root):
    anims = []
    for anim_name, length, swing, extra, phase in (("idle", 3.0, 12, 8, 0.25), ("move", 1.2, 28, 10, 0.1)):
        tracks = []
        for side, sign in (("r", 1), ("l", -1)):
            wing = find(root, f"wing_{side}")
            if wing is None:
                continue
            tracks.append((wing, keyframes(anim_name, wing, "rotation", length, fy=wave(sign * swing, length))))
            outer = find(root, f"outer_{side}")
            if outer is not None:
                tracks.append((outer, keyframes(anim_name, outer, "rotation", length, fy=wave(sign * extra, length, phase))))
        anims.append(animation(anim_name, length, tracks))
    return anims


def halo_animations(root):
    frames = []
    for i in range(4):
        frames.append({"channel": "rotation", "data_points": [{"x": "0", "y": "0", "z": str(i * 90)}],
                       "uuid": uid("kf", "idle", "root", "rotation", i), "time": round(i * 3.0, 4), "color": -1, "interpolation": "linear"})
    frames.append({"channel": "rotation", "data_points": [{"x": "0", "y": "0", "z": "360"}],
                   "uuid": uid("kf", "idle", "root", "rotation", 4), "time": 12.0, "color": -1, "interpolation": "linear"})
    return [animation("idle", 12.0, [(root, frames)])]


def pet_animations(art, root):
    length = 4.0
    tracks = [(root, keyframes("idle", root, "position", length, fy=wave(0.15 * 16, length))),
              (root, keyframes("idle", root, "rotation", length, fy=wave(6, length, 1.0)))]
    part = pet_part(art)
    if part == "tail":
        tail = find(root, "tail")
        if tail:
            tracks.append((tail, keyframes("idle", tail, "rotation", length, fx=wave(18, 1.0), step=0.125)))
    elif part == "wings":
        for side, sign in (("r", 1), ("l", -1)):
            wing = find(root, f"wing_{side}")
            if wing:
                tracks.append((wing, keyframes("idle", wing, "rotation", length, fz=wave(sign * 25, 1.0), step=0.125)))
    elif part == "tentacles":
        bone = find(root, "tentacles")
        if bone:
            tracks.append((bone, keyframes("idle", bone, "rotation", length, fx=wave(10, 2.0), step=0.25)))
    elif part == "legs":
        bone = find(root, "legs")
        if bone:
            # 2026-11-03：站長說 0.6 秒，4 秒一輪不能整除會接不順，用 0.5 秒
            tracks.append((bone, keyframes("idle", bone, "rotation", length, fz=wave(12, 0.5), step=0.0625)))
    return [animation("idle", length, tracks)]


def kraken_wing_animations(root):
    """海妖觸鬚之翼：每條觸手三段（t{i}a_r → t{i}b_r → t{i}c_r）繞 z 擺，越外面擺越大、相位越晚（像波從肩膀傳到尖端）；
    四條觸手相位錯開；左邊鏡射所以角度反號。idle 慢、move 快而大。"""
    anims = []
    for anim_name, length, amp, extra in (("idle", 3.0, (6, 9, 13), 0), ("move", 1.2, (10, 16, 24), 0)):
        tracks = []
        for side, sign in (("r", 1), ("l", -1)):
            for i in range(8):
                for depth, letter in enumerate("abc"):
                    bone = find(root, f"t{i}{letter}_{side}")
                    if bone is None:
                        continue
                    phase = length * (0.12 * i + 0.1 * depth)
                    tracks.append((bone, keyframes(anim_name, bone, "rotation", length, fz=wave(sign * amp[depth], length, phase))))
        anims.append(animation(anim_name, length, tracks))
    return anims


def kraken_pet_animations(root):
    """小海妖：身體上下浮（root，同其他寵物），8 條觸手（t{i}a → t{i}b）輪流擺——每條差 1/8 個週期，外段擺得大一點、晚一點。
    觸手的轉軸在 ±x 的是 z、在 ±z 的是 x、斜角的輪流（跟 gen_models3d.sf_pet_kraken 一樣），所以 x、z 兩個方向都給一樣的波。"""
    length = 4.0
    tracks = [(root, keyframes("idle", root, "position", length, fy=wave(0.15 * 16, length))),
              (root, keyframes("idle", root, "rotation", length, fy=wave(6, length, 1.0)))]
    for i in range(8):
        for depth, letter in enumerate("ab"):
            bone = find(root, f"t{i}{letter}")
            if bone is None:
                continue
            axis = "z" if i % 4 == 0 else "x" if i % 4 == 2 else ("z" if i % 8 in (1, 5) else "x")
            amp = (8, 14)[depth]
            f = wave(amp, 2.0, 2.0 * (i / 8 + 0.12 * depth))
            tracks.append((bone, keyframes("idle", bone, "rotation", length, fx=f if axis == "x" else None, fz=f if axis == "z" else None, step=0.125)))
    return [animation("idle", length, tracks)]


def jellyfish_animations(root):
    """小水母：傘（bell）一縮一放——橫向 1±0.09、直向反過來 1∓0.06，2 秒一下；6 條飄帶觸手（t{i}a → t{i}b）慢慢飄，
    每條相位錯開、外段晚一點；整隻上下浮。"""
    length = 4.0
    tracks = [(root, keyframes("idle", root, "position", length, fy=wave(0.12 * 16, length)))]
    bell = find(root, "bell")
    if bell is not None:
        tracks.append((bell, keyframes("idle", bell, "scale", length, fx=wave(0.09, 2.0, offset=1.0), fy=wave(-0.06, 2.0, offset=1.0),
                                       fz=wave(0.09, 2.0, offset=1.0), step=0.125)))
    for i in range(6):
        for depth, letter in enumerate("ab"):
            bone = find(root, f"t{i}{letter}")
            if bone is None:
                continue
            amp = (7, 11)[depth]
            tracks.append((bone, keyframes("idle", bone, "rotation", length, fx=wave(amp, 2.0, 2.0 * (i / 6 + 0.15 * depth)),
                                           fz=wave(amp * 0.6, 2.0, 2.0 * (i / 6 + 0.15 * depth) + 0.5), step=0.125)))
    return [animation("idle", length, tracks)]


# 2026-11-03 星潮祭：有標骨頭的模型各自的動畫（art → 函式(root)）
TAGGED_ANIMS = {
    "sf_kraken_wings": kraken_wing_animations,
    "sf_pet_kraken": kraken_pet_animations,
    "sf_pet_jellyfish": jellyfish_animations,
}


# ---- 組 bbmodel ----

def assign_uuids(bone, model_name, path=""):
    bone.uuid = uid("bone", model_name, path + bone.name)
    for i, cube in enumerate(bone.cubes):
        cube["uuid"] = uid("cube", model_name, path + bone.name, i)
        cube["name"] = bone.name
    for c in bone.children:
        assign_uuids(c, model_name, path + bone.name + "/")


def outliner(bone):
    node = {"name": bone.name, "origin": r3(bone.origin), "rotation": [0, 0, 0], "bone_pivot": r3(bone.origin), "color": 0,
            "uuid": bone.uuid, "export": True, "mirror_uv": False, "isOpen": True, "locked": False, "visibility": True,
            "autouv": 0, "children": [c["uuid"] for c in bone.cubes] + [outliner(c) for c in bone.children]}
    return node


def groups(bone, out):
    out.append({"name": bone.name, "origin": r3(bone.origin), "rotation": [0, 0, 0], "uuid": bone.uuid, "export": True,
                "visibility": True, "locked": False, "autouv": 0, "color": 0})
    for c in bone.children:
        groups(c, out)
    return out


def bbmodel(model_name, root, textures, resolution, anims):
    assign_uuids(root, model_name)
    cubes = root.all_cubes()
    elements = []
    for i, cube in enumerate(cubes):
        el = {"name": cube["name"], "box_uv": False, "rescale": False, "locked": False, "light_emission": cube.get("light_emission", 0),
              "render_order": "default", "allow_mirror_modeling": True, "from": cube["from"], "to": cube["to"], "autouv": 0,
              "color": i % 8, "origin": cube["origin"], "rotation": cube["rotation"], "faces": cube["faces"], "type": "cube",
              "uuid": cube["uuid"]}
        elements.append(el)
    return {"meta": {"format_version": "5.0", "model_format": "free", "box_uv": False}, "name": model_name,
            "model_identifier": model_name, "visible_box": [1, 1, 0], "variable_placeholders": "",
            "variable_placeholder_buttons": [], "timeline_setups": [], "unhandled_root_fields": {},
            "resolution": {"width": resolution, "height": resolution}, "elements": elements, "groups": groups(root, []),
            "outliner": [outliner(root)], "textures": textures, "animations": anims}


def build_one(art, models, parts):
    """回 (bbmodel dict, 種類)。"""
    kind = "wings" if "back_r" in parts else "halo" if "back" in parts else "pet" if "pet" in parts else None
    if kind is None:
        return None, None
    scale, offset = {"wings": (WING_SCALE, WING_OFFSET), "halo": (HALO_SCALE, HALO_OFFSET), "pet": (PET_SCALE, PET_OFFSET)}[kind]
    # 貼圖：每個物品模型自己的（翅膀兩片的雜訊種子不一樣，所以 _r、_l 各一組）；bbmodel 的 textures 依序排、面的 texture 改成排進去的索引
    tex_json = []
    cubes = []
    for m in models:
        tex = textures_of(m)
        remap, sizes = {}, {}
        for index in sorted(tex):
            name, img = tex[index]
            j, size = texture_json(len(tex_json), name, img, PREFIX + art)
            remap[index] = len(tex_json)
            sizes[index] = size
            tex_json.append(j)
        for el in m.elements:
            cube = convert_cube(el, scale, offset, sizes)
            for face in cube["faces"].values():
                if face["texture"] is not None:
                    face["texture"] = remap[face["texture"]]
            cubes.append(cube)
    tagged = any("_part" in c for c in cubes)   # 2026-11-03 星潮祭：模型自己標了骨頭
    if tagged:
        root = tagged_bones(cubes, offset)
    elif kind == "wings":
        root = wing_bones(cubes, offset)
    elif kind == "halo":
        root = Bone("root", offset)
        root.cubes = cubes
    else:
        root = pet_bones(art, cubes, offset)
    assign_uuids(root, PREFIX + art)  # 動畫的 animators 用骨頭的 uuid 當 key，先編號
    if tagged:
        anims = TAGGED_ANIMS[art](root)
    else:
        anims = wing_animations(root) if kind == "wings" else halo_animations(root) if kind == "halo" else pet_animations(art, root)
    data = bbmodel(PREFIX + art, root, tex_json, tex_json[0]["uv_width"], anims)
    return data, kind


def select(all_models):
    for art, value in all_models.items():
        models, parts = value[0], value[1]
        if any(k in parts for k in ("back_r", "back", "pet")):
            yield art, models, parts


# ---- 檢查 ----

def check(path, data, expected_cubes):
    raw = json.loads(open(path, encoding="utf-8").read())
    assert raw == data
    assert len(raw["elements"]) == expected_cubes, (path, len(raw["elements"]), expected_cubes)
    ids = {e["uuid"] for e in raw["elements"]}
    seen = set()

    def walk(node):
        assert node["uuid"] not in seen
        seen.add(node["uuid"])
        for c in node["children"]:
            if isinstance(c, str):
                assert c in ids, (path, c)
                ids.discard(c)
            else:
                walk(c)
    for node in raw["outliner"]:
        walk(node)
    assert not ids, (path, "有方塊不在骨架裡", len(ids))
    group_ids = {g["uuid"] for g in raw["groups"]}
    assert seen == group_ids
    for e in raw["elements"]:
        assert all(e["from"][i] <= e["to"][i] for i in range(3))
        for face in FACES:
            f = e["faces"][face]
            assert len(f["uv"]) == 4
            if f["texture"] is not None:
                assert 0 <= f["texture"] < len(raw["textures"])
                size = raw["textures"][f["texture"]]["uv_width"]
                assert all(0 <= v <= size + 1e-6 for v in f["uv"]), (path, f)
    for a in raw["animations"]:
        assert a["loop"] == "loop" and a["length"] > 0
        for bone_id, animator in a["animators"].items():
            assert bone_id in group_ids, (path, a["name"], animator["name"])
            times = sorted({k["time"] for k in animator["keyframes"]})
            assert times[0] == 0 and abs(times[-1] - a["length"]) < 1e-6, (path, a["name"], times[0], times[-1])
            for k in animator["keyframes"]:
                assert k["channel"] in ("rotation", "position", "scale")
                p = k["data_points"][0]
                for ch in ("x", "y", "z"):
                    float(p[ch])
    for t in raw["textures"]:
        base64.b64decode(t["source"].split(",", 1)[1])
    for e in raw["elements"]:
        for i in range(3):
            assert -64 < e["from"][i] < 64 and -64 < e["to"][i] < 64, (path, e["from"], e["to"])


def preview(path, written):
    """側視圖（從角色的右邊看：橫軸 z、前面 −Z 在左）：人 1.8 格高，翅膀在肩胛、光輪在翅膀上面一點、寵物在左後上方。"""
    from PIL import Image, ImageDraw
    S = 5                      # 一單位（1/16 格）幾像素
    Z0, Z1, Y0, Y1 = -24, 40, -2, 40
    img = Image.new("RGB", ((Z1 - Z0) * S + 20, (Y1 - Y0) * S + 20), (24, 24, 30))
    d = ImageDraw.Draw(img)

    def px(z, y):
        return (int((z - Z0) * S) + 10, int((Y1 - y) * S) + 10)

    def rect(a, b, color):
        (x0, y0), (x1, y1) = px(*a), px(*b)
        d.rectangle([min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)], outline=color)
    # 人：腳底 y=0、頭頂 28.8（1.8 格）；身體 z −2..2、頭 z −4..4
    rect((-2, 0), (2, 22.5), (200, 200, 200))
    rect((-4, 22.5), (4, 28.8), (200, 200, 200))
    d.text(px(5, 30), "head top 1.8", fill=(200, 200, 200))
    d.line([px(Z0, 0), px(Z1, 0)], fill=(90, 90, 90))
    d.text(px(Z0 + 1, -0.5), "front -Z  <-  ->  back +Z", fill=(140, 140, 140))
    for y in (8, 16, 24, 32):
        d.line([px(Z0, y), px(Z1, y)], fill=(45, 45, 55))
        d.text(px(Z0 + 1, y + 1.5), f"{y / 16:.1f}", fill=(90, 90, 110))
    colors = {"wings": (120, 200, 255), "halo": (255, 220, 120), "pet": (255, 140, 200)}
    for art, kind, data in written:
        for e in data["elements"]:
            rect((e["from"][2], e["from"][1]), (e["to"][2], e["to"][1]), colors[kind])
    for kind, (y, z) in (("wings", (WING_OFFSET[1], WING_OFFSET[2])), ("halo", (HALO_OFFSET[1], HALO_OFFSET[2])),
                         ("pet", (PET_OFFSET[1], PET_OFFSET[2]))):
        x, yy = px(z, y)
        d.ellipse([x - 3, yy - 3, x + 3, yy + 3], fill=colors[kind])
        d.text((x + 5, yy - 5), f"{kind} y={y / 16:.2f} z={z / 16:.2f}", fill=colors[kind])
    img.save(path)


def main():
    argv = sys.argv[1:]
    do_check = "--check" in argv
    prev = argv[argv.index("--preview") + 1] if "--preview" in argv else None
    os.makedirs(OUT_DIR, exist_ok=True)
    all_models = g3.build_all()
    written = []
    index = []
    for art, models, parts in select(all_models):
        data, kind = build_one(art, models, parts)
        if data is None:
            continue
        path = os.path.join(OUT_DIR, f"{art}.bbmodel")
        with open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, ensure_ascii=False, separators=(",", ":")))
        expected = sum(len(m.elements) for m in models)
        if do_check:
            check(path, data, expected)
        written.append((art, kind, data))
        index.append(f"{art} {kind} {','.join(a['name'] for a in data['animations'])}")
    with open(os.path.join(OUT_DIR, "index.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(index) + "\n")
    for art, kind, data in written:
        bones = len(data["groups"])
        print(f"{art:28} {kind:5} cubes={len(data['elements']):3} bones={bones} anims={[a['name'] for a in data['animations']]} "
              f"tex={[(t['width'], t['height']) for t in data['textures']]}")
    print(f"wrote {len(written)} bbmodel → {OUT_DIR}")
    if prev:
        preview(prev, [w for w in written if w[0] in ("seraph_wings", "halo_back", "pet_fox")])
        print("preview", prev)


if __name__ == "__main__":
    main()
