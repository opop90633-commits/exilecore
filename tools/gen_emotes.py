#!/usr/bin/env python3
"""
產生 src/main/resources/emotes/exile_emotes.bbmodel（2026-10）：BetterModel 的玩家模型（用玩家自己的皮膚，12 個部位），
骨架照 BetterModel 內建的 steve.bbmodel（tools/steve_base.bbmodel，MIT），動作照「閃身步｜浪子踢球｜狗熊哆嗦毛｜搖頭晃腦」影片寫關鍵幀。

角度慣例（跟 steve 的 roll 動作一樣）：模型面向 -Z（北），角色的右手在 +X。
- 垂下的手腳 x 正 = 往前抬；z 正 = 往 +X（角色的右邊）擺。所以右手往外側舉是 z 正、左手是 z 負。
- 頭、身體 x 負 = 往前低（點頭、前傾）；y 正 = 往角色的左邊轉。
- 位置的單位是像素（1/16 格）。
改完直接跑：python3 tools/gen_emotes.py
"""
import json, uuid, copy
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / 'tools/steve_base.bbmodel'
OUT = ROOT / 'src/main/resources/emotes/exile_emotes.bbmodel'

model = json.loads(BASE.read_text(encoding='utf-8'))
bones = {g['name']: g['uuid'] for g in model['groups']}
NS = uuid.UUID('6f6a2b1e-2c55-4e1d-9a7f-31c2f8d0e001')

MIRROR = {'pra_right_arm': 'pla_left_arm', 'prfa_right_forearm': 'plfa_left_forearm', 'prl_right_leg': 'pll_left_leg',
          'prfl_right_foreleg': 'plfl_left_foreleg'}
MIRROR.update({v: k for k, v in MIRROR.items()})

def mirror(pose):
    out = {}
    for key, val in pose.items():
        bone, ch = key
        x, y, z = val
        nb = MIRROR.get(bone, bone)
        out[(nb, ch)] = (-x, y, z) if ch == 'position' else (x, -y, -z)
    return out

def P(**kw):
    """P(pra=(x,y,z), root_pos=(x,y,z)…)：簡寫 → {(骨頭, 頻道): 值}"""
    alias = {'root': 'player_root', 'hip': 'phip_hip', 'waist': 'pw_waist', 'chest': 'pc_chest', 'head': 'h_ph_head',
             'pra': 'pra_right_arm', 'prfa': 'prfa_right_forearm', 'pla': 'pla_left_arm', 'plfa': 'plfa_left_forearm',
             'prl': 'prl_right_leg', 'prfl': 'prfl_right_foreleg', 'pll': 'pll_left_leg', 'plfl': 'plfl_left_foreleg'}
    out = {}
    for k, v in kw.items():
        if k.endswith('_pos'):
            out[(alias[k[:-4]], 'position')] = v
        else:
            out[(alias[k], 'rotation')] = v
    return out

def merge(*poses):
    out = {}
    for p in poses:
        out.update(p)
    return out

ALL_BONES = ['player_root', 'phip_hip', 'pw_waist', 'pc_chest', 'h_ph_head', 'pra_right_arm', 'prfa_right_forearm',
             'pla_left_arm', 'plfa_left_forearm', 'prl_right_leg', 'prfl_right_foreleg', 'pll_left_leg', 'plfl_left_foreleg']

def animation(name, length, frames, interp='catmullrom'):
    """frames：[(時間, 姿勢)]；姿勢裡沒寫到的骨頭在那一格回到 0（所以每一格都是完整的姿勢）。"""
    animators = {}
    used = set()
    for _, pose in frames:
        used.update(pose.keys())
    for t, _ in frames:
        pass
    for bone, ch in sorted(used | {(b, 'rotation') for b in ALL_BONES}):
        if bone not in bones:
            raise SystemExit('沒有這個骨頭：' + bone)
        anim = animators.setdefault(bones[bone], {'name': bone, 'type': 'bone', 'keyframes': []})
        for t, pose in frames:
            x, y, z = pose.get((bone, ch), (0, 0, 0))
            anim['keyframes'].append({
                'channel': ch, 'data_points': [{'x': str(x), 'y': str(y), 'z': str(z)}],
                'uuid': str(uuid.uuid5(NS, f'{name}/{bone}/{ch}/{t}')), 'time': round(t, 4), 'color': -1,
                'interpolation': interp,
            })
    # 只有 0 的頻道不用寫
    for anim in animators.values():
        by_channel = {}
        for k in anim['keyframes']:
            by_channel.setdefault(k['channel'], []).append(k)
        keep = []
        for ch, ks in by_channel.items():
            if any(any(float(v) != 0 for v in k['data_points'][0].values()) for k in ks):
                keep.extend(ks)
        anim['keyframes'] = keep
    animators = {k: v for k, v in animators.items() if v['keyframes']}
    return {'uuid': str(uuid.uuid5(NS, name)), 'name': name, 'loop': 'once', 'override': False, 'length': length,
            'snapping': 24, 'selected': False, 'anim_time_update': '', 'blend_weight': '', 'start_delay': '', 'loop_delay': '',
            'animators': animators}

ZERO = {}

def seq(steps, tail=0.05):
    """分解動作（2026-10，照影片一拍一拍的樣子）：[(姿勢, 換過去花幾秒, 停住幾秒)]，從站姿開始，線性換姿勢、每個姿勢停一下。
    回傳 (長度, frames)。"""
    frames = [(0.0, ZERO)]
    t = 0.0
    for pose, move, hold in steps:
        t += move
        frames.append((round(t, 3), pose))
        if hold > 0:
            t += hold
            frames.append((round(t, 3), pose))
    return round(t + tail, 3), frames

def held(name, steps):
    length, frames = seq(steps)
    return animation(name, length, frames, interp='linear')

# ---- 閃身步（影片 0:02～0:13）：往右邊閃一大步壓低、手往身前甩 → 收回站好 → 往左閃 → 收回 ----
# 往角色的左邊閃（mirror 成右邊）：左腳跨出去彎膝、右腳伸直，上身往左前倒、頭跟著轉過去往下看，左手甩到身前、右手往外
lunge_l = P(root_pos=(-10, -3, 0),   # 2026-10：-8 → -10（小幅的位移感，大約 0.6 格）
            root=(0, 0, 12), chest=(-22, 18, 0), head=(-15, 25, 0),
            pll=(35, 0, -28), plfl=(-45, 0, 0), prl=(0, 0, 30),
            pla=(45, 0, 30), plfa=(35, 0, 0), pra=(-15, 0, 45), prfa=(10, 0, 0))
lunge_r = mirror(lunge_l)
ready = P(pra=(0, 0, 12), pla=(0, 0, -12), prl=(0, 0, 4), pll=(0, 0, -4))      # 站好、手微微張開
wind_r = merge(ready, P(root=(0, 0, 4), chest=(0, 10, 0), pra=(25, 0, 25)))     # 閃之前右手先帶一下
sidestep = held('sidestep', [
    (ready, 0.3, 0.35), (wind_r, 0.2, 0.1), (lunge_r, 0.25, 0.7), (ready, 0.35, 0.45),
    (lunge_l, 0.25, 0.7), (ready, 0.35, 0.45), (ZERO, 0.3, 0)])   # 2026-10：右、左各一次（之前右邊多踏一次）

# ---- 浪子踢球（影片 0:13～0:17）：兩手往旁邊張開 → 小跳壓低 → 右腳往後勾（上身前傾）→ 右腳交叉到左腳前面、踮腳、手肘彎起來 → 收 ----
wings = P(pra=(-5, 0, 70), prfa=(0, 0, 15), pla=(-5, 0, -70), plfa=(0, 0, -15))
crouch = merge(wings, P(root_pos=(0, -2, 0), chest=(-12, 0, 0), head=(-8, 0, 0), prl=(20, 0, 0), prfl=(-25, 0, 0),
                        pll=(20, 0, 0), plfl=(-25, 0, 0)))
kick_r = merge(wings, P(root_pos=(0, 1, 0), chest=(-18, 8, 0), head=(-10, 10, 0), prl=(-25, 0, 0), prfl=(-100, 0, 0),
                        pll=(5, 0, 0), pra=(-10, 0, 75), pla=(-10, 0, -80)))
cross = P(root_pos=(0, 1, 0), chest=(0, -12, 0), head=(5, 22, 0), prl=(18, 0, -22), prfl=(-20, 0, 0), pll=(-5, 0, 4),
          pra=(0, 0, 65), prfa=(0, 0, 45), pla=(0, 0, -65), plfa=(0, 0, -45))
settle = P(pra=(0, 0, 25), pla=(0, 0, -25))
kick_ball = held('kick_ball', [
    (wings, 0.35, 0.5), (crouch, 0.2, 0.15), (kick_r, 0.2, 0.75), (cross, 0.3, 1.0),
    (settle, 0.35, 0.3), (ZERO, 0.3, 0)])

# ---- 狗熊哆嗦毛（影片 0:26～0:33，2026-10 照逐格重抓、整段加快）：
#      身前抖手 → 往右一跨、手往後甩 → 回來大字張手 → 蹲馬步兩手舉起彎著 → 往左跨、兩手平舉像飛機 →
#      往右跨、一手高舉 → 右弓步平舉 → 往左小跳、兩手 V、抬膝 → 兩手抱頭蹲 → 大字平舉 →
#      （特寫）兩手在頭上交叉 ↔ 張開，身子左右晃、一直哆嗦 → 抱頭 → 大字 Y → 腳收、V 字 → 手放下 → 站好
#      整段會左右來回跨步（root_pos 的 x：+ 是角色的右邊，±6 像素大約 0.4 格）
BW = dict(prl=(15, 0, 28), prfl=(-20, 0, 0), pll=(15, 0, -28), plfl=(-20, 0, 0))     # 大字蹲的腳
b_shiver = P(root_pos=(0, -1, 0), chest=(-8, 6, 0), head=(-4, -8, 0), pra=(60, 0, -20), prfa=(70, 0, 0),
             pla=(60, 0, 20), plfa=(70, 0, 0), prl=(8, 0, 4), pll=(8, 0, -4), prfl=(-12, 0, 0), plfl=(-12, 0, 0))
b_right = P(root_pos=(6, -1.5, 0), root=(0, 0, -10), chest=(-10, -10, 0), head=(-5, -18, 0),
            prl=(10, 0, 26), prfl=(-25, 0, 0), pll=(0, 0, -6), pra=(-25, 0, 35), pla=(-25, 0, -30))
b_spread = P(root_pos=(-1, -3, 0), **BW, pra=(-5, 0, 55), pla=(-5, 0, -55), chest=(-4, 0, 0))
b_cactus = P(root_pos=(-2, -4, 0), **BW, pra=(0, 0, 150), prfa=(0, 0, -60), pla=(0, 0, -150), plfa=(0, 0, 60), head=(8, 0, 0))
b_plane_l = P(root_pos=(-6, -2, 0), root=(0, 0, 10), pll=(15, 0, -30), plfl=(-30, 0, 0), prl=(0, 0, 18),
              pra=(-5, 0, 95), pla=(-5, 0, -95), chest=(0, 10, 0), head=(0, 15, 0))
b_reach_r = P(root_pos=(4, -1.5, 0), root=(0, 0, -12), prl=(12, 0, 28), prfl=(-25, 0, 0), pll=(0, 0, -10),
              pra=(0, 0, 165), prfa=(0, 0, -20), pla=(-5, 0, -75), chest=(-6, -12, 0), head=(5, -15, 0))
b_plane_r = P(root_pos=(7, -2.5, 0), root=(0, 0, -12), prl=(18, 0, 32), prfl=(-35, 0, 0), pll=(0, 0, -16),
              pra=(-5, 0, 95), pla=(-5, 0, -95), chest=(-8, -10, 0), head=(0, -20, 0))
b_hopv = P(root_pos=(-3, 1.5, 0), root=(0, 0, 6), prl=(60, 0, 8), prfl=(-75, 0, 0), pll=(0, 0, -6),
           pra=(0, 0, 150), pla=(0, 0, -150), head=(10, 0, 0))
b_headhands = P(root_pos=(-4, -3.5, 0), **BW, pra=(0, 0, 165), prfa=(0, 0, -75), pla=(0, 0, -165), plfa=(0, 0, 75), head=(-10, 0, 0))
b_wide_out = P(root_pos=(-2, -2.5, 0), **BW, pra=(0, 0, 92), pla=(0, 0, -92))
def b_shake(side):
    """特寫那段的哆嗦：兩手在頭上交叉（side=1）↔ 張開往上（side=-1），身子跟著左右晃、頭甩。"""
    if side > 0:
        arms = dict(pra=(10, 0, 168), prfa=(0, 0, -70), pla=(10, 0, -168), plfa=(0, 0, 70))
    else:
        arms = dict(pra=(-10, 0, 120), prfa=(0, 0, 25), pla=(-10, 0, -120), plfa=(0, 0, -25))
    return P(root_pos=(-2 * side, -3.5, 0), root=(0, 0, 7 * side), **BW, **arms,
             chest=(-8, 14 * side, 6 * side), waist=(0, 10 * side, 0), head=(0, -22 * side, 12 * side))
b_y = P(root_pos=(1, -3, 0), prl=(12, 0, 34), prfl=(-15, 0, 0), pll=(12, 0, -34), plfl=(-15, 0, 0),
        pra=(0, 0, 135), prfa=(0, 0, -15), pla=(0, 0, -135), plfa=(0, 0, 15), head=(8, 0, 0))
b_v = P(root_pos=(0, -1, 0), prl=(0, 0, 10), pll=(0, 0, -10), pra=(0, 0, 150), pla=(0, 0, -150), head=(10, 0, 0))
b_lower = P(pra=(0, 0, 80), prfa=(0, 0, 60), pla=(0, 0, -80), plfa=(0, 0, -60))
bear_steps = [(b_shiver, 0.14, 0.12), (b_right, 0.18, 0.08), (b_spread, 0.17, 0.05), (b_cactus, 0.15, 0.12),
              (b_plane_l, 0.18, 0.08), (b_reach_r, 0.2, 0.07), (b_plane_r, 0.15, 0.08), (b_hopv, 0.17, 0.05),
              (b_headhands, 0.14, 0.05), (b_wide_out, 0.14, 0.08)]
for i in range(10):   # 哆嗦：一下 0.12 秒
    bear_steps.append((b_shake(1 if i % 2 == 0 else -1), 0.12, 0))
bear_steps += [(b_headhands, 0.14, 0.05), (b_y, 0.15, 0.15), (b_v, 0.17, 0.2), (b_lower, 0.2, 0.08), (ZERO, 0.22, 0)]
bear_shake = held('bear_shake', bear_steps)

# ---- 搖頭晃腦（影片 0:38～0:45，2026-10 照逐格重抓、整段加快）：
#      右手往外一甩、跨成馬步 → 右拳收到胸前、左手護胸 → 右手往旁邊畫一大圈甩出去（身子往左倒）→
#      拳往上收到臉旁 → 回胸前；一圈大約 0.5 秒，重複，頭一直左右快甩 → 最後右手往旁邊伸直停住 → 收腳站好
horse = P(root_pos=(0, -3, 0), prl=(15, 0, 30), prfl=(-15, 0, 0), pll=(15, 0, -30), plfl=(-15, 0, 0),
          pla=(70, 0, 25), plfa=(80, 0, 0))                                      # 馬步、左手護胸
swing_out = P(pra=(10, 0, 45), chest=(0, -8, 0))
def hb(arm, head_side, lean=0):
    """arm：chest 胸前 / out 甩出去 / high 往上甩 / face 收到臉旁；head_side：頭甩向哪邊（1 左、-1 右）"""
    arms = {'chest': dict(pra=(70, 0, -25), prfa=(80, 0, 0)),
            'out': dict(pra=(10, 0, 88), prfa=(0, 0, 8)),
            'high': dict(pra=(-5, 0, 125), prfa=(0, 0, 10)),
            'face': dict(pra=(60, 0, 35), prfa=(95, 0, 0))}[arm]
    return merge(horse, P(**arms, root=(0, 0, lean), chest=(0, 10 * head_side, -4 * lean),
                          head=(0, 34 * head_side, -10 * head_side)))
bob_steps = [(swing_out, 0.15, 0.1), (merge(horse, P(pra=(70, 0, -25), prfa=(80, 0, 0), head=(-5, 0, 0))), 0.18, 0.15)]
cycle = [('high', 0.5), ('out', 1), ('face', 0), ('chest', 0)]               # 一圈：往上甩 → 甩出去 → 收到臉旁 → 胸前
side = 1
for rep in range(6):
    for arm, lean in cycle:
        bob_steps.append((hb(arm, side, 4 * lean), 0.11 if rep < 2 else 0.1, 0))
        side = -side
bob_steps += [(merge(horse, P(pra=(0, 0, 90), prfa=(0, 0, 0), head=(0, -15, 0), chest=(0, -6, 0))), 0.18, 0.5),
              (P(pra=(0, 0, 20)), 0.25, 0.1), (ZERO, 0.22, 0)]
head_bob = held('head_bob', bob_steps)

# ---- 我真的特別愛你（2026-10，照貼圖逐格重抓）：兩手摀著嘴，整個人慢慢往一邊挪過去、到了輕輕一蹲（彈一下）、停一下，
#      再挪到另一邊、蹲一下；一來一回大約 1.1 秒，做三次。頭跟著微微歪，摀嘴的手輪流輕拍
def shy(side, dip, pat):
    hands = dict(pra=(70, 40, -3), prfa=(100 + (8 if pat > 0 else 0), 0, 0),
                 pla=(70, -40, 3), plfa=(100 + (8 if pat < 0 else 0), 0, 0))
    return P(root_pos=(2.6 * side, -1.0 * dip, 0), root=(0, 0, 4 * side), chest=(-6 - 2 * dip, 4 * side, 0),
             head=(-10 - 3 * dip, 6 * side, -5 * side), prl=(6 * dip, 0, 3), pll=(6 * dip, 0, -3),
             prfl=(-10 * dip, 0, 0), plfl=(-10 * dip, 0, 0), **hands)
love_steps = [(shy(0, 0, 0), 0.25, 0.1)]
for i in range(6):
    side = -1 if i % 2 == 0 else 1          # 先往角色的左邊挪
    love_steps += [(shy(side, 0, side), 0.3, 0), (shy(side, 1, side), 0.1, 0), (shy(side, 0.3, side), 0.14, 0)]
love_steps += [(shy(0, 0, 0), 0.25, 0.05),
               (P(pra=(20, 0, -10), prfa=(30, 0, 0), pla=(20, 0, 10), plfa=(30, 0, 0), head=(-6, 0, 0)), 0.25, 0.1), (ZERO, 0.22, 0)]
love_you = held('love_you', love_steps)

# ---- 67（2026-10，照影片：兩手手心朝上放在身前，一上一下輪流掂，頭跟著歪、身子晃）----
def sixseven(side):
    hi, lo = dict(x=30, f=82), dict(x=8, f=66)
    r, l = (hi, lo) if side > 0 else (lo, hi)
    return P(pra=(r['x'], 0, 26), prfa=(r['f'], 0, 0), pla=(l['x'], 0, -26), plfa=(l['f'], 0, 0),
             chest=(0, -6 * side, 0), head=(4, -8 * side, -10 * side), root_pos=(0, -0.5 if side > 0 else 0, 0))
ss_steps = [(sixseven(1), 0.25, 0.05)]
for i in range(12):
    ss_steps.append((sixseven(-1 if i % 2 == 0 else 1), 0.21, 0))
ss_steps += [(P(pra=(25, 0, 15), prfa=(60, 0, 0), pla=(25, 0, -15), plfa=(60, 0, 0), head=(6, 0, 0)), 0.2, 0.25), (ZERO, 0.22, 0)]
six_seven = held('six_seven', ss_steps)

# ---- 王之坐姿（2026-10）：往後一坐（沙發是 Emotes.java 另外擺的方塊）、雙腳張開、上身往後靠、兩手搭在扶手上，坐一陣子再起來 ----
sit = P(root_pos=(0, -5.6, 0), prl=(88, 0, 22), prfl=(-88, 0, 0), pll=(88, 0, -22), plfl=(-88, 0, 0),
        waist=(14, 0, 0), chest=(4, 0, 0), head=(-14, 0, 0),
        pra=(18, 0, 30), prfa=(55, 0, 0), pla=(18, 0, -30), plfa=(55, 0, 0))
sit_breathe = merge(sit, P(chest=(7, 0, 0), head=(-16, 6, 0)))
sit_look = merge(sit, P(head=(-12, -14, 0)))
king_sit = held('king_sit', [
    (merge(sit, P(root_pos=(0, -3, 0))), 0.35, 0), (sit, 0.3, 1.2), (sit_breathe, 0.8, 0.6), (sit, 0.8, 0.8),
    (sit_look, 0.6, 1.2), (sit, 0.6, 0.8), (sit_breathe, 0.8, 0.4), (sit, 0.6, 0.2), (ZERO, 0.6, 0)])

model['animations'] = [sidestep, kick_ball, bear_shake, head_bob, king_sit, love_you, six_seven]
model['name'] = 'exile_emotes'
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(model, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
print('wrote', OUT, {a['name']: a['length'] for a in model['animations']}, [a['name'] for a in model['animations']], sum(len(a['animators']) for a in model['animations']), 'animators')
