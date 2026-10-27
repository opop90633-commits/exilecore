#!/usr/bin/env python3
"""
2026-10：照 PoE2 的白裝基底分階（每種武器、防具、飾品都有很多階的基底，高階的基底本身數值就高）。

讀 tools/poe2_bases/*.tsv（從 poe2db 整理的 PoE2 基底資料），產生 src/main/resources/data/bases/*.yml 後面
「# ---- PoE2 分階基底（tools/gen_poe2_bases.py 產生）----」那一段（重跑會整段換掉；檔案前面手寫的部分不動）。

數值：
- 攻速、暴擊率、格擋率、固有詞綴、需求等級照 PoE2。
- 傷害、護甲／閃避／能量護盾、屬性需求：照 PoE2 各階之間的相對高低，但整體曲線對到我們原本的強度
  （原本一種一個基底、隨物品等級 ×(1 + 0.035 × 等級)；新的基底寫 fixed: true 不再隨物品等級縮放）。
  做法：同一類基底用 PoE2 的數值對等級做一條直線，每一階的數值 = PoE2 數值 × 原本那條曲線 ÷ 那條直線。
- 法杖、長杖、權杖 PoE2 沒有基礎傷害（給的是技能），照原本的曲線給，固有詞綴沿用原本的。
- PoE2 的手套放到護腿（我們沒有手套欄）。
- 名稱是自己取的（階級的形容詞＋種類），不用 PoE 的名字。

2026-10 第二版（collapse）：基底數值改回跟物品等級走，同一類「只差數值大小」的分階合併成一個（留最高那一階，
drop_level 1、寫 base_level／implicit_level），其他階 retired ＋ merged_into（舊物品、uniques.yml 的 stat_base 照原本的固定數值）。
"""
import csv
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / 'src/main/resources/data/bases'
SRC = ROOT / 'tools/poe2_bases'
MARK = '# ---- PoE2 分階基底（tools/gen_poe2_bases.py 產生；重跑會整段換掉）----'
DMG_PER_LEVEL = 0.035
REQ_PER_LEVEL = 0.02

ADJ = ['樸素', '舊木', '鐵箍', '淬鐵', '鍛紋', '精鐵', '鋼脊', '鑄紋', '戰痕', '銅鑲', '獵紋', '熔鋼', '寒鐵', '雷紋',
       '赤銅', '秘銀', '暗鋼', '星紋', '隕鐵', '曜石', '霜晶', '炎晶', '天鐵', '古王', '聖紋', '龍骨', '淵鋼', '星骸',
       '天穹', '永曜', '輝冠', '始源']

# 武器：PoE2 類別 → (我們原本的基底, 新代號開頭, 名稱的種類詞)
WEAPONS = {
    'mace1h': ('war_hammer', 'mace1', '戰錘'), 'mace2h': ('great_hammer', 'mace2', '巨錘'),
    'spear': ('spear', 'spear', '長矛'), 'quarterstaff': ('quarterstaff', 'qstaff', '長棍'),
    'talisman': ('talisman', 'talis', '獸符'), 'bow': ('bow', 'bow', '弓'), 'crossbow': ('crossbow', 'xbow', '弩'),
    'wand': ('wand', 'wand', '法杖'), 'staff': ('staff', 'staff', '長杖'), 'sceptre': ('sceptre', 'sceptre', '權杖'),
}
NO_POE2_DAMAGE = {'wand', 'staff', 'sceptre'}
# 2026-10-26：這三類換成 bases/weapons_casters.yml（照 PoE2：沒有傷害、賦予技能），這裡產生的全部退役、合併到那邊的第 1 把
CASTER_SUCCESSOR = {'wand': 'cwand_01', 'staff': 'cstaff_01', 'sceptre': 'csceptre_01'}
SKIP = {'Kalguuran Forgehammer', 'Trarthan Cannon', 'Runic Fork', 'Heartwood Shortbow',
        'Abyssal Signet', 'Grasping Ring', 'Breach Ring', 'Fine Breach Ring', 'Ring of Potential',
        'Corona Amulet', 'Lament Amulet', 'Portent Amulet', 'Absent Amulet', 'Veridical Chain',
        'Unset Ring', 'Refined Breach Ring', 'Stalking Belt', 'Dusk Ring', 'Gloam Ring', 'Penumbra Ring', 'Tenebrous Ring',
        'Bereaved Amulet', 'Ominous Amulet', 'Void Amulet', 'Illusory Chain', 'Runemastered Illusory Chain',
        'Tracker Belt', 'Primal Markings'}

# 防具：PoE2 類別 → (欄位, 我們原本的基底字尾, 新代號開頭)
ARMOUR_SLOTS = {'body': ('CHEST', 'body', 'body'), 'helmet': ('HEAD', 'helmet', 'helm'),
                'gloves': ('LEGS', 'leggings', 'legs'), 'boots': ('FEET', 'boots', 'boots')}
ATTR_OLD = {'str': 'plate', 'dex': 'leather', 'int': 'silk', 'str_dex': 'scale', 'dex_int': 'wrap', 'str_int': None}
ATTR_TAGS = {'str': ['str_armour', 'str_only'], 'dex': ['dex_armour', 'dex_only'], 'int': ['int_armour', 'int_only'],
             'str_dex': ['str_armour', 'dex_armour', 'str_dex_armour'], 'dex_int': ['dex_armour', 'int_armour', 'dex_int_armour'],
             'str_int': ['str_armour', 'int_armour', 'str_int_armour']}
NOUN = {
    'body': {'str': '胸鎧', 'dex': '皮甲', 'int': '法袍', 'str_dex': '鱗甲', 'str_int': '聖鎧', 'dex_int': '纏衣'},
    'helmet': {'str': '戰盔', 'dex': '皮帽', 'int': '頭冠', 'str_dex': '鱗盔', 'str_int': '聖盔', 'dex_int': '兜帽'},
    'gloves': {'str': '腿鎧', 'dex': '皮褲', 'int': '絲褲', 'str_dex': '鱗護腿', 'str_int': '聖護腿', 'dex_int': '纏腿'},
    'boots': {'str': '戰靴', 'dex': '皮靴', 'int': '絲履', 'str_dex': '鱗靴', 'str_int': '聖靴', 'dex_int': '纏履'},
}
SHIELDS = {('shield', 'str'): ('tower_shield', 'tshield', '塔盾'), ('shield', 'str_dex'): ('kite_shield', 'kshield', '鳶盾'),
           ('shield', 'str_int'): (None, 'cshield', '聖徽盾'), ('buckler', ''): ('buckler', 'buckler', '圓盾'),
           ('focus', ''): ('spirit_shield', 'focus', '法器')}


def num(text, default=0.0):
    text = (text or '').strip()
    try:
        return float(text)
    except ValueError:
        return default


def rng(text):
    """「(20—30)」或「20」→ [20, 30]。"""
    m = re.match(r'\(?\s*([\d.]+)\s*(?:[—–-]\s*([\d.]+))?\s*\)?', text)
    a = float(m.group(1))
    b = float(m.group(2)) if m.group(2) else a
    return [a, b]


def ints(r):
    return [int(round(r[0])), int(round(r[1]))]


R = r'(\([\d.]+—[\d.]+\)|[\d.]+)'


def implicit(text):
    """PoE2 的固有詞綴（中文）→ 我們的寫法；認不得的回傳 None。"""
    if not text:
        return None
    zh = re.split(r'\s[|/]\s', text)[0].strip()
    zh = zh.split('；')[0]
    rules = [
        (r'^\+' + R + r'%至格擋率$', '+{value}% 格擋率', [('block.chance', 'FLAT', None)]),
        (r'^\+' + R + r'層最大盛怒$', '+{value} 怒氣上限', [('rage.max', 'FLAT', None)]),
        (r'^增加' + R + r'%暈眩累積$', '增加 {value}% 暈眩累積', [('stun.buildup', 'INCREASED', None)]),
        (r'^\+' + R + r'%的暴擊傷害加成$', '+{value}% 暴擊傷害加成', [('crit.bonus', 'FLAT', None)]),
        (r'^增加' + R + r'%移動速度$', '增加 {value}% 移動速度', [('movement.speed', 'INCREASED', None)]),
        (r'^必定擊中$', '攻擊必定命中', [('flag.always_hit', 'FLAT', 1)]),
        (r'^增加' + R + r'%弩箭速度$', '增加 {value}% 投射物速度', [('skill.projectile_speed', 'INCREASED', None)]),
        (r'^增加' + R + r'%箭矢速度$', '增加 {value}% 投射物速度', [('skill.projectile_speed', 'INCREASED', None)]),
        (r'^裝填額外' + R + r'發弩箭$', '彈匣多 {value} 發', [('crossbow.clip', 'FLAT', None)]),
        (r'^增加' + R + r'%召喚物造成的傷害$', '增加 {value}% 召喚物傷害', [('damage', 'INCREASED', '[minion]')]),
        (r'^\+' + R + r'精魂$', '+{value} 最大精魂', [('spirit.max', 'FLAT', None)]),
        (r'^每秒回復' + R + r'%生命$', '每秒回復 {value}% 最大生命', [('life.regen_percent', 'FLAT', None)]),
        (r'^增加' + R + r'%暈眩門檻$', '增加 {value}% 暈眩門檻', [('stun.threshold', 'INCREASED', None)]),
        (r'^增加' + R + r'%魔力回復率$', '增加 {value}% 魔力回復速度', [('mana.regen', 'INCREASED', None)]),
        (r'^增加' + R + r'%能量護盾充能率$', '增加 {value}% 能量護盾回充速度', [('es.recharge', 'INCREASED', None)]),
        (r'^增加' + R + r'%元素異常狀態門檻$', '增加 {value}% 元素異常門檻', [('ailment.threshold', 'INCREASED', None)]),
        (r'^生命值所受的' + R + r'%傷害由魔力扣除$', '受到的傷害有 {value}% 先從魔力扣', [('damage.from_mana', 'FLAT', None)]),
        (r'^減少' + R + r'%身上元素異常狀態時間$', '你身上的元素異常持續時間減少 {value}%', [('ailment.self_duration', 'INCREASED', '-')]),
        (r'^\+' + R + r'最大生命$', '+{value} 最大生命', [('life.max', 'FLAT', None)]),
        (r'^\+' + R + r'最大魔力$', '+{value} 最大魔力', [('mana.max', 'FLAT', None)]),
        (r'^\+' + R + r'最大能量護盾$', '+{value} 最大能量護盾', [('es.max', 'FLAT', None)]),
        (r'^\+' + R + r'%火焰抗性$', '+{value}% 火焰抗性', [('resist.fire', 'FLAT', None)]),
        (r'^\+' + R + r'%冰冷抗性$', '+{value}% 冰冷抗性', [('resist.cold', 'FLAT', None)]),
        (r'^\+' + R + r'%閃電抗性$', '+{value}% 閃電抗性', [('resist.lightning', 'FLAT', None)]),
        (r'^\+' + R + r'%混沌抗性$', '+{value}% 混沌抗性', [('resist.chaos', 'FLAT', None)]),
        (r'^\+' + R + r'%全元素抗性$', '+{value}% 全部元素抗性',
         [('resist.fire', 'FLAT', None), ('resist.cold', 'FLAT', None), ('resist.lightning', 'FLAT', None)]),
        (r'^\+' + R + r'%火焰與冰冷抗性$', '+{value}% 火焰與冰冷抗性', [('resist.fire', 'FLAT', None), ('resist.cold', 'FLAT', None)]),
        (r'^\+' + R + r'%火焰與閃電抗性$', '+{value}% 火焰與閃電抗性', [('resist.fire', 'FLAT', None), ('resist.lightning', 'FLAT', None)]),
        (r'^\+' + R + r'%冰冷與閃電抗性$', '+{value}% 冰冷與閃電抗性', [('resist.cold', 'FLAT', None), ('resist.lightning', 'FLAT', None)]),
        (r'^\+' + R + r'命中值$', '+{value} 命中', [('accuracy', 'FLAT', None)]),
        (r'^增加' + R + r'%施法速度$', '增加 {value}% 施法速度', [('cast.speed', 'INCREASED', None)]),
        (r'^增加' + R + r'%攻擊速度$', '增加 {value}% 攻擊速度', [('attack.speed', 'INCREASED', None)]),
        (r'^增加' + R + r'%最大生命$', '增加 {value}% 最大生命', [('life.max', 'INCREASED', None)]),
        (r'^增加' + R + r'%最大魔力$', '增加 {value}% 最大魔力', [('mana.max', 'INCREASED', None)]),
        (r'^增加' + R + r'%混沌傷害$', '增加 {value}% 混沌傷害', [('damage', 'INCREASED', '[chaos]')]),
        (r'^增加' + R + r'%找到的物品稀有度$', '增加 {value}% 物品掉落稀有度', [('drop.rarity', 'INCREASED', None)]),
        (r'^增加' + R + r'%命中值$', '增加 {value}% 命中', [('accuracy', 'INCREASED', None)]),
        (r'^增加' + R + r'%攻擊暴擊率$', '增加 {value}% 攻擊暴擊率', [('crit.chance', 'INCREASED', '[attack]')]),
        (r'^' + R + r'每秒生命回復$', '每秒回復 {value} 生命', [('life.regen', 'FLAT', None)]),
        (r'^\+' + R + r'點力量$', '+{value} 力量', [('attr.str', 'FLAT', None)]),
        (r'^\+' + R + r'點敏捷$', '+{value} 敏捷', [('attr.dex', 'FLAT', None)]),
        (r'^\+' + R + r'點智慧$', '+{value} 智慧', [('attr.int', 'FLAT', None)]),
        (r'^\+' + R + r'點全部能力值$', '+{value} 全部屬性', [('attr.str', 'FLAT', None), ('attr.dex', 'FLAT', None), ('attr.int', 'FLAT', None)]),
        (r'^增加' + R + r'%藥劑的生命恢復$', '增加 {value}% 藥劑的生命回復', [('flask.life_recovery', 'INCREASED', None)]),
        (r'^增加' + R + r'%藥劑的魔力恢復$', '增加 {value}% 藥劑的魔力回復', [('flask.mana_recovery', 'INCREASED', None)]),
        (r'^\+' + R + r'護甲值$', '+{value} 護甲', [('armour', 'FLAT', None)]),
        (r'^攻擊每擊中一名敵人，獲得' + R + r'生命$', '攻擊每擊中一個敵人回復 {value} 生命', [('life.on_hit', 'FLAT', '[attack]')]),
        (r'^攻擊有' + R + r'%機率在擊中時造成中毒$', '攻擊 +{value}% 中毒機率', [('poison.chance', 'FLAT', '[attack]')]),
        (r'^攻擊有' + R + r'%機率造成流血$', '攻擊 +{value}% 流血機率', [('bleed.chance', 'FLAT', '[attack]')]),
        (r'^有100%機率穿透一個敵人$', '投射物多穿透 {value} 個敵人', [('skill.pierce', 'FLAT', 1)]),
    ]
    for pattern, out, mods in rules:
        m = re.match(pattern, zh)
        if m:
            value = rng(m.group(1)) if m.groups() else None
            return {'text': out, 'value': value, 'mods': mods}
    m = re.match(r'^攻擊附加' + R + r'至' + R + r'(物理|火焰|冰冷|閃電)傷害$', zh)
    if m:
        kind = {'物理': 'physical', '火焰': 'fire', '冰冷': 'cold', '閃電': 'lightning'}[m.group(3)]
        return {'text': '攻擊附加 {min} 到 {max} ' + m.group(3) + '傷害', 'min': rng(m.group(1)), 'max': rng(m.group(2)),
                'mods': [('damage.added.min', 'FLAT', '[attack, ' + kind + ']'), ('damage.added.max', 'FLAT', '[attack, ' + kind + ']')]}
    return None


def implicit_yaml(imp):
    out = ['  implicit:', '    text: "%s"' % imp['text']]
    if 'min' in imp:
        out.append('    min: %s' % ints(imp['min']))
        out.append('    max: %s' % ints(imp['max']))
        var = ['min', 'max']
    elif imp['value'] is not None:
        out.append('    value: %s' % fmt_range(imp['value']))
        var = ['value', 'value', 'value']
    else:
        var = []
    out.append('    mods:')
    for i, (stat, typ, extra) in enumerate(imp['mods']):
        if isinstance(extra, int):
            out.append('      - { stat: %s, type: %s, value: %d }' % (stat, typ, extra))
            continue
        v = '{%s}' % (var[i] if i < len(var) else 'value')
        tags = ''
        if extra == '-':
            v = '-' + v
        elif extra:
            tags = ', tags: ' + extra
        out.append('      - { stat: %s, type: %s, value: "%s"%s }' % (stat, typ, v, tags))
    return out


def fmt_range(r):
    if all(abs(x - round(x)) < 1e-9 for x in r):
        return '[%d, %d]' % (round(r[0]), round(r[1]))
    return '[%s, %s]' % (r[0], r[1])


def linfit(points):
    """PoE2 的數值對等級的平滑曲線：log(v) = a + b·log(L + 5)（冪次曲線，低等級也貼得住）。"""
    import math
    pts = [(p[0], p[1]) for p in points if p[1] > 0]
    if not pts:
        return lambda x: 1
    if len(pts) < 2:
        return lambda x: pts[0][1]
    xs = [math.log(p[0] + 5) for p in pts]
    ys = [math.log(p[1]) for p in pts]
    n = len(pts)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    b = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx if sxx else 0
    a = my - b * mx
    return lambda x: math.exp(a + b * math.log(x + 5))


def old_bases():
    out = {}
    for f in DATA.glob('*.yml'):
        text = f.read_text(encoding='utf-8')
        if MARK in text:
            text = text[:text.index(MARK)]
        for k, v in (yaml.safe_load(text) or {}).items():
            if isinstance(v, dict):
                out[k] = v
    return out


def read(name):
    with open(SRC / name, encoding='utf-8') as fh:
        return list(csv.DictReader(fh, delimiter='\t'))


def level_of(row):
    return max(1, int(num(row.get('level'), 1)))


def reqs(rows, old_req, levels):
    """屬性需求：PoE2 的總和對等級做直線，乘上原本的曲線（原本的需求 ×(1 + 0.02 × 等級)）。"""
    old_total = sum(old_req)
    pts = [(level_of(r), num(r['str']) + num(r['dex']) + num(r['int'])) for r in rows]
    pts = [p for p in pts if p[1] > 0]
    fit = linfit(pts) if pts else None
    out = []
    for r in rows:
        lv = level_of(r)
        target = old_total * (1 + REQ_PER_LEVEL * (lv - 1))
        vals = [num(r['str']), num(r['dex']), num(r['int'])]
        total = sum(vals)
        if total <= 0 or fit is None:
            # PoE2 沒寫：照原本的比例
            out.append([int(round(x * (1 + REQ_PER_LEVEL * (lv - 1)))) for x in old_req])
            continue
        scale = target / fit(lv)
        out.append([int(round(x * scale)) for x in vals])
    return out


def unique_names(prefix_list, noun, count):
    names = []
    for i in range(count):
        adj = prefix_list[i] if i < len(prefix_list) else prefix_list[i % len(prefix_list)] + '・' + str(i // len(prefix_list) + 1)
        names.append(adj + noun)
    return names


def emit(lines, bid, fields):
    lines.append('')
    lines.append(bid + ':')
    lines.extend(fields)


def weapons(old):
    rows = read('weapons_a.tsv') + read('weapons_b.tsv')
    out = []
    for cat, (old_id, prefix, noun) in WEAPONS.items():
        ob = old[old_id]
        group = [r for r in rows if r['category'] == cat and r['name_en'] not in SKIP]
        seen = set()
        uniq = []
        for r in group:
            key = r['name_en'] + '|' + r['implicit']
            if key in seen:
                continue
            seen.add(key)
            uniq.append(r)
        group = sorted(uniq, key=level_of)
        names = unique_names(ADJ, noun, len(group))
        old_dmg = ob['damage']['physical']
        old_avg = (old_dmg[0] + old_dmg[1]) / 2
        old_aps = ob['aps']
        old_req = [ob['requirements'].get('str', 0), ob['requirements'].get('dex', 0), ob['requirements'].get('int', 0)]
        req = reqs(group, old_req, None)
        # PoE2 每秒傷害（物理＋元素）對等級的直線
        def dps(r):
            total = (num(r['phys_min']) + num(r['phys_max'])) / 2
            for part in (r['other_dmg'] or '').split(','):
                if ':' in part:
                    a, b = part.split(':')[1].split('-')
                    total += (float(a) + float(b)) / 2
            return total * num(r['aps'], old_aps)
        fit = linfit([(level_of(r), dps(r)) for r in group]) if cat not in NO_POE2_DAMAGE else None
        for i, r in enumerate(group):
            lv = level_of(r)
            curve = 1 + DMG_PER_LEVEL * (lv - 1)
            bid = '%s_%02d' % (prefix, i + 1)
            f = ['  name: %s' % names[i], '  fixed: true', '  family: %s' % prefix, '  material: %s' % ob['material']]
            if ob.get('model'):
                f.append('  model: %s' % ob['model'])
            f.append('  slot: %s' % ob['slot'])
            f.append('  tags: [%s]' % ', '.join(ob['tags']))
            f.append('  drop_level: %d' % lv)
            f.append('  requirements: { level: %d, str: %d, dex: %d, int: %d }' % (lv, *req[i]))
            if cat in NO_POE2_DAMAGE:
                d = [max(1, int(round(old_dmg[0] * curve))), max(2, int(round(old_dmg[1] * curve)))]
                f.append('  damage: { physical: %s }' % d)
                f.append('  aps: %s' % old_aps)
                f.append('  crit: %s' % ob.get('crit', 5.0))
            else:
                aps = num(r['aps'], old_aps)
                target_dps = old_avg * old_aps * curve * dps(r) / fit(lv)
                own_dps = dps(r)
                scale = target_dps / own_dps if own_dps > 0 else 1
                scale = 1  # 2026-10-26：武器傷害改成 PoE2 原值（不再對到原本的曲線；物品等級超過 base_level 也不再往上長，見 ItemStats.damageFactor）
                parts = []
                pmin, pmax = num(r['phys_min']), num(r['phys_max'])
                if pmax > 0:
                    parts.append('physical: [%d, %d]' % (max(1, round(pmin * scale)), max(2, round(pmax * scale))))
                for part in (r['other_dmg'] or '').split(','):
                    if ':' in part:
                        kind, val = part.split(':')
                        a, b = val.split('-')
                        parts.append('%s: [%d, %d]' % (kind.strip(), max(1, round(float(a) * scale)), max(2, round(float(b) * scale))))
                f.append('  damage: { %s }' % ', '.join(parts))
                f.append('  aps: %s' % aps)
                crit = num(r['crit'], ob.get('crit', 5.0))
                f.append('  crit: %s' % (crit if crit > 0 else ob.get('crit', 5.0)))
            if ob.get('sockets'):
                f.append('  sockets: %d' % ob['sockets'])
            imp = implicit(r['implicit']) if cat not in NO_POE2_DAMAGE else None
            if imp:
                f.extend(implicit_yaml(imp))
            elif cat in NO_POE2_DAMAGE and ob.get('implicit'):
                f.extend(old_implicit(ob['implicit']))
            emit(out, bid, f)
    return out


def old_implicit(imp):
    out = ['  implicit:', '    text: "%s"' % imp['text']]
    for key in ('value', 'min', 'max'):
        if key in imp:
            out.append('    %s: %s' % (key, imp[key]))
    out.append('    mods:')
    for m in imp['mods']:
        parts = ['stat: %s' % m['stat'], 'type: %s' % m['type'], 'value: "%s"' % m['value'] if isinstance(m['value'], str) else 'value: %s' % m['value']]
        if m.get('tags'):
            parts.append('tags: [%s]' % ', '.join(m['tags']))
        out.append('      - { %s }' % ', '.join(parts))
    return out


def defences(rows, key_old, old_vals, levels):
    """每一種防禦（armour／evasion／es）各自做直線，乘上原本的曲線。"""
    result = [dict() for _ in rows]
    for stat in ('armour', 'evasion', 'es'):
        base_old = old_vals.get(stat, 0)
        pts = [(level_of(r), num(r[stat])) for r in rows if num(r[stat]) > 0]
        if not pts or base_old <= 0:
            continue
        fit = linfit(pts)
        for i, r in enumerate(rows):
            v = num(r[stat])
            if v <= 0:
                continue
            lv = level_of(r)
            result[i][stat] = max(1, int(round(base_old * (1 + DMG_PER_LEVEL * (lv - 1)) * v / fit(lv))))
    return result


def armour(old):
    rows = read('armour.tsv')
    out = []
    for cat, (slot, suffix, prefix) in ARMOUR_SLOTS.items():
        for attr, old_prefix in ATTR_OLD.items():
            group = sorted([r for r in rows if r['category'] == cat and r['attr'] == attr and r['name_en'] not in SKIP], key=level_of)
            if not group:
                continue
            if old_prefix:
                ob = old['%s_%s' % (old_prefix, suffix)]
                old_vals = {'armour': ob.get('armour', 0), 'evasion': ob.get('evasion', 0), 'es': ob.get('es', 0)}
                old_req = [ob['requirements'].get('str', 0), ob['requirements'].get('dex', 0), ob['requirements'].get('int', 0)]
                material, model = ob['material'], ob.get('model')
            else:  # 力智：原本沒有，用力量與智慧的各一半多一點
                p, s = old['plate_' + suffix], old['silk_' + suffix]
                old_vals = {'armour': round(p['armour'] * 0.55), 'es': round(s['es'] * 0.55)}
                old_req = [p['requirements'].get('str', 0) // 2 + 2, 0, s['requirements'].get('int', 0) // 2 + 2]
                material, model = p['material'], p.get('model')
            noun = NOUN[cat][attr]
            names = unique_names(ADJ, noun, len(group))
            defs = defences(group, None, old_vals, None)
            req = reqs(group, old_req, None)
            for i, r in enumerate(group):
                lv = level_of(r)
                bid = '%s_%s_%02d' % (prefix, attr, i + 1)
                f = ['  name: %s' % names[i], '  fixed: true', '  family: %s_%s' % (prefix, attr), '  material: %s' % material]
                if model:
                    f.append('  model: %s' % model)
                f.append('  slot: %s' % slot)
                f.append('  tags: [armour, %s, %s]' % (suffix, ', '.join(ATTR_TAGS[attr])))
                f.append('  drop_level: %d' % lv)
                f.append('  requirements: { level: %d, str: %d, dex: %d, int: %d }' % (lv, *req[i]))
                for stat in ('armour', 'evasion', 'es'):
                    if stat in defs[i]:
                        f.append('  %s: %d' % (stat, defs[i][stat]))
                imp = implicit(r['implicit'])
                if imp:
                    f.extend(implicit_yaml(imp))
                emit(out, bid, f)
    return out


JEWEL_NAMES = {  # PoE2 英文名 → 我們的名字（自己取的）
    'Iron Ring': '鍛環指環', 'Lazuli Ring': '青晶指環', 'Ruby Ring': '炎晶指環', 'Sapphire Ring': '霜晶指環',
    'Topaz Ring': '雷晶指環', 'Amethyst Ring': '紫蝕指環', 'Emerald Ring': '鷹眼指環', 'Pearl Ring': '詠珠指環',
    'Prismatic Ring': '三晶指環', 'Gold Ring': '金耀指環', 'Life Ring': '生脈指環', 'Mana Ring': '魔泉指環',
    'Vitalic Ring': '生脈指環', 'Mnemonic Ring': '憶念指環', 'Kinetic Ring': '勁擊指環', 'Oneiric Ring': '夢紋指環',
    'Biostatic Ring': '恆息指環',
    'Crimson Amulet': '赤血墜飾', 'Azure Amulet': '蒼泉墜飾', 'Amber Amulet': '烈珀墜飾', 'Jade Amulet': '青翠墜飾',
    'Lapis Amulet': '藍智墜飾', 'Lunar Amulet': '月盾墜飾', 'Bloodstone Amulet': '血晶墜飾', 'Stellar Amulet': '星輝墜飾',
    'Solar Amulet': '日魄墜飾', 'Gold Amulet': '金耀墜飾', 'Pearlescent Amulet': '珠光墜飾',
    'Rawhide Belt': '鞣皮束帶', 'Linen Belt': '麻紋束帶', 'Wide Belt': '寬幅束帶', 'Long Belt': '長結束帶',
    'Plate Belt': '鐵片束帶', 'Ornate Belt': '紋飾束帶', 'Invocation Belt': '詠咒束帶', 'Mail Belt': '環鏈束帶',
    'Double Belt': '雙扣束帶', 'Heavy Belt': '重革束帶', 'Utility Belt': '百用束帶', 'Fine Belt': '精工束帶',
    'Invoking Belt': '詠咒束帶', 'Sinew Belt': '筋索束帶', 'Forking Belt': '岔雷束帶',
}
JEWEL_SLOT = {'ring': ('iron_ring', 'RING', 'ring'), 'amulet': ('amber_amulet', 'AMULET', 'amulet'),
              'belt': ('leather_belt', 'BELT', 'belt')}


def jewellery(old):
    rows = read('misc.tsv')
    out = []
    used = set()
    for cat, (old_id, slot, tag) in JEWEL_SLOT.items():
        ob = old[old_id]
        group = sorted([r for r in rows if r['category'] == cat and r['name_en'] not in SKIP], key=level_of)
        n = 0
        for r in group:
            imp = implicit(r['implicit'])
            if imp is None:
                continue
            name = JEWEL_NAMES.get(r['name_en'])
            if r['name_en'] == 'Two-Stone Ring':
                name = {'+{value}% 火焰與冰冷抗性': '炎霜指環', '+{value}% 火焰與閃電抗性': '炎雷指環',
                        '+{value}% 冰冷與閃電抗性': '霜雷指環'}.get(imp['text'], name)
            if name is None or name in used:
                name = (name or ADJ[n % len(ADJ)] + {'ring': '指環', 'amulet': '墜飾', 'belt': '束帶'}[cat])
                k = 2
                base = name
                while name in used:
                    name = base + '・' + str(k)
                    k += 1
            used.add(name)
            n += 1
            lv = level_of(r)
            bid = 'j%s_%02d' % (cat, n)
            f = ['  name: %s' % name, '  fixed: true', '  family: j%s' % cat,
                 '  drop_weight: %d' % {'ring': 40, 'amulet': 20, 'belt': 20}[cat], '  material: %s' % ob['material']]
            if ob.get('model'):
                f.append('  model: %s' % ob['model'])
            f += ['  slot: %s' % slot, '  tags: [jewellery, %s]' % tag, '  drop_level: %d' % lv,
                  '  requirements: { level: %d }' % lv]
            f.extend(implicit_yaml(imp))
            emit(out, bid, f)
    return out


def shields(old):
    rows = read('misc.tsv')
    out = []
    for (cat, attr), (old_id, prefix, noun) in SHIELDS.items():
        group = sorted([r for r in rows if r['category'] == cat and (r['attr'] or '') == attr and r['name_en'] not in SKIP],
                       key=level_of)
        if not group:
            continue
        if old_id:
            ob = old[old_id]
            old_vals = {'armour': ob.get('armour', 0), 'evasion': ob.get('evasion', 0), 'es': ob.get('es', 0)}
            old_req = [ob['requirements'].get('str', 0), ob['requirements'].get('dex', 0), ob['requirements'].get('int', 0)]
            tags = ob['tags']
            material, model, slot = ob['material'], ob.get('model'), ob['slot']
            old_block = ob.get('block', 0)
            sockets = ob.get('sockets', 0)
        else:
            t, s = old['tower_shield'], old['spirit_shield']
            old_vals = {'armour': round(t['armour'] * 0.55), 'es': round(s['es'] * 0.55)}
            old_req = [t['requirements'].get('str', 0) // 2 + 2, 0, s['requirements'].get('int', 0) // 2 + 2]
            tags = ['shield', 'str_armour', 'int_armour', 'str_int_armour', 'str_int_shield']
            material, model, slot = t['material'], t.get('model'), t['slot']
            old_block = t.get('block', 0)
            sockets = t.get('sockets', 0)
        names = unique_names(ADJ, noun, len(group))
        defs = defences(group, None, old_vals, None)
        req = reqs(group, old_req, None)
        for i, r in enumerate(group):
            lv = level_of(r)
            bid = '%s_%02d' % (prefix, i + 1)
            f = ['  name: %s' % names[i], '  fixed: true', '  family: %s' % prefix, '  material: %s' % material]
            if model:
                f.append('  model: %s' % model)
            f += ['  slot: %s' % slot, '  tags: [%s]' % ', '.join(tags), '  drop_level: %d' % lv,
                  '  requirements: { level: %d, str: %d, dex: %d, int: %d }' % (lv, *req[i])]
            for stat in ('armour', 'evasion', 'es'):
                if stat in defs[i]:
                    f.append('  %s: %d' % (stat, defs[i][stat]))
            block = num(r['block'])
            if block > 0 or old_block > 0:
                f.append('  block: %s' % (block if block > 0 else old_block))
            if sockets:
                f.append('  sockets: %d' % sockets)
            imp = implicit(r['implicit'])
            if imp:
                f.extend(implicit_yaml(imp))
            elif old_id == 'spirit_shield' and ob.get('implicit'):
                f.extend(old_implicit(ob['implicit']))
            emit(out, bid, f)
    return out


def quivers(old):
    rows = [r for r in read('weapons_b.tsv') if r['category'] == 'quiver']
    ob = old['serrated_quiver']
    out = []
    names = {'Broadhead Quiver': '寬鏃箭袋', 'Fire Quiver': '焰羽箭袋', 'Sacral Quiver': '祀血箭袋', 'Two-Point Quiver': '叉鏃箭袋',
             'Blunt Quiver': '鈍鏃箭袋', 'Toxic Quiver': '毒羽箭袋', 'Serrated Quiver': '鋸鏃箭袋', 'Primed Quiver': '疾裝箭袋',
             'Penetrating Quiver': '貫甲箭袋', 'Volant Quiver': '飛羽箭袋', 'Visceral Quiver': '刺心箭袋'}
    n = 0
    for r in sorted(rows, key=level_of):
        imp = implicit(r['implicit'])
        if imp is None:
            continue
        n += 1
        lv = level_of(r)
        f = ['  name: %s' % names.get(r['name_en'], ADJ[n] + '箭袋'), '  fixed: true', '  family: quiver', '  drop_weight: 50',
             '  material: %s' % ob['material']]
        if ob.get('model'):
            f.append('  model: %s' % ob['model'])
        f += ['  slot: %s' % ob['slot'], '  tags: [quiver]', '  drop_level: %d' % lv, '  requirements: { level: %d }' % lv]
        f.extend(implicit_yaml(imp))
        emit(out, 'quiver_%02d' % n, f)
    return out


# 2026-10：合併後留下來的飾品、箭袋每一種換一個原版物品當圖示（同一欄位不重複，免得戒指全部長一樣）；
# 混合屬性「力量＋智慧」的防具用鑽石的樣子（原本跟純力量一樣是鐵的）。都是沒有右鍵效果的物品。
ICON = {
    'jring_02': 'NAUTILUS_SHELL', 'jring_03': 'RESIN_BRICK', 'jring_04': 'DIAMOND', 'jring_05': 'GOLD_NUGGET',
    'jring_06': 'SHULKER_SHELL', 'jring_07': 'IRON_NUGGET', 'jring_08': 'COPPER_NUGGET', 'jring_09': 'RAW_GOLD',
    'jring_10': 'RAW_COPPER', 'jring_11': 'BRICK', 'jring_12': 'RAW_IRON', 'jring_13': 'NETHERITE_INGOT',
    'jring_14': 'NETHER_BRICK', 'jring_15': 'CLAY_BALL', 'jring_16': 'IRON_INGOT', 'jring_17': 'POPPED_CHORUS_FRUIT',
    'jamulet_01': 'GLISTERING_MELON_SLICE', 'jamulet_02': 'ECHO_SHARD', 'jamulet_03': 'NETHERITE_SCRAP',
    'jamulet_04': 'TURTLE_SCUTE', 'jamulet_05': 'PRISMARINE_SHARD', 'jamulet_06': 'PRISMARINE_CRYSTALS',
    'jamulet_07': 'ARMADILLO_SCUTE', 'jamulet_08': 'RABBIT_FOOT', 'jamulet_09': 'AMETHYST_SHARD', 'jamulet_10': 'SUGAR',
    'jamulet_11': 'GOLD_INGOT',
    'jbelt_01': 'LEATHER', 'jbelt_02': 'RABBIT_HIDE', 'jbelt_03': 'IRON_CHAIN', 'jbelt_04': 'STRING', 'jbelt_05': 'LEAD',
    'jbelt_06': 'COPPER_CHAIN', 'jbelt_07': 'OXIDIZED_COPPER_CHAIN',
    'quiver_01': 'LEATHER', 'quiver_02': 'BLAZE_POWDER', 'quiver_03': 'GLISTERING_MELON_SLICE', 'quiver_04': 'SPYGLASS',
    'quiver_05': 'BRICK', 'quiver_06': 'FERMENTED_SPIDER_EYE', 'quiver_07': 'RED_DYE', 'quiver_08': 'FEATHER',
    'quiver_09': 'FLINT', 'quiver_10': 'PHANTOM_MEMBRANE', 'quiver_11': 'ECHO_SHARD',
}


def collapse(lines):
    """
    2026-10 第二版：基底數值改回跟物品等級走，同一類裡「只差數值大小」的那幾階合併成一個。
    分組：武器看（類別、暴擊、攻速、有哪幾種傷害、固定詞綴）；其他看（類別、固定詞綴、標籤、格擋、有哪幾種防禦）。
    每一組留最高的那一階（它的數值最準），改成 drop_level 1、寫 base_level（數值是哪個物品等級的）、
    implicit_level（固定詞綴的數值在這個物品等級以上才是滿的）；其他階寫 retired（舊物品照常用原本的固定數值）
    ＋ merged_into（合併到哪一個）。
    """
    blocks = []
    for line in lines:
        if line and not line.startswith(' '):
            blocks.append([line[:-1], []])
        elif blocks and line:
            blocks[-1][1].append(line)
    parsed = {bid: yaml.safe_load(bid + ':\n' + '\n'.join(fields))[bid] for bid, fields in blocks}
    groups = {}
    for bid, _ in blocks:
        v = parsed[bid]
        imp = (v.get('implicit') or {}).get('text')
        if 'damage' in v:
            key = (v['family'], v.get('crit'), v.get('aps'), tuple(sorted(v['damage'])), imp)
        else:
            key = (v['family'], imp, tuple(sorted(v.get('tags', []))), v.get('block'),
                   tuple(x for x in ('armour', 'evasion', 'es') if x in v))
        groups.setdefault(key, []).append(bid)
    rep_of = {}
    info = {}
    for members in groups.values():
        rep = max(members, key=lambda b: (parsed[b]['drop_level'], b))
        rep_imp = parsed[rep].get('implicit')
        same_imp = [parsed[b]['drop_level'] for b in members if parsed[b].get('implicit') == rep_imp]
        info[rep] = (parsed[rep]['drop_level'], min(same_imp))
        for b in members:
            rep_of[b] = rep
    out = []
    for bid, fields in blocks:
        rep = rep_of[bid]
        new = []
        successor = CASTER_SUCCESSOR.get(parsed[bid].get('family')) if bid == rep else None
        for f in fields:
            if successor and f.startswith('  name:'):
                # 2026-10-26：法杖、長杖、權杖換成 bases/weapons_casters.yml（tools/gen_poe2_casters.py）：留下來的那一階也退役
                new.append(f)
                new.append('  retired: true          # 2026-10-26：換成照 PoE2 的法杖、長杖、權杖（bases/weapons_casters.yml，沒有傷害、賦予技能）；舊物品照常能用')
                new.append('  merged_into: %s' % successor)
                continue
            if bid == rep:
                if f == '  fixed: true':
                    base_level, implicit_level = info[bid]
                    new.append('  base_level: %d          # 數值是物品等級 %d 的值，照物品等級縮放' % (base_level, base_level))
                    if parsed[bid].get('implicit'):
                        new.append('  implicit_level: %d' % implicit_level)
                    continue
                if f.startswith('  drop_level:'):
                    new.append('  drop_level: 1')
                    continue
                if f.startswith('  requirements:'):
                    f = re.sub(r'level: \d+', 'level: 1', f, count=1)
                if f.startswith('  material:'):
                    if bid in ICON:
                        f = '  material: %s' % ICON[bid]
                    elif parsed[bid].get('family', '').endswith('_str_int') and 'IRON_' in f:
                        f = f.replace('IRON_', 'DIAMOND_')
            elif f == '  fixed: true':
                new.append(f)
                new.append('  retired: true')
                new.append('  merged_into: %s' % rep)
                continue
            new.append(f)
        emit(out, bid, new)
    return out


def write(file, lines):
    path = DATA / file
    text = path.read_text(encoding='utf-8')
    if MARK in text:
        text = text[:text.index(MARK)]
    text = text.rstrip('\n') + '\n\n' + MARK + '\n' + '\n'.join(lines).lstrip('\n') + '\n'
    path.write_text(text, encoding='utf-8')


def main():
    old = old_bases()
    w = collapse(weapons(old))
    a = collapse(armour(old))
    s = collapse(shields(old))
    j = collapse(jewellery(old))
    q = collapse(quivers(old))
    write('weapons.yml', w)
    write('armour.yml', a)
    write('shields.yml', s)
    write('jewellery.yml', j)
    write('quivers.yml', q)
    for name, lines in (('weapons', w), ('armour', a), ('shields', s), ('jewellery', j), ('quivers', q)):
        print(name, sum(1 for line in lines if line and not line.startswith(' ')))


if __name__ == '__main__':
    main()
