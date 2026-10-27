#!/usr/bin/env python3
"""2026-10-26：產生法杖、長杖、權杖賦予的技能（PoE2 的 Grants Skill）到 skills.yml 的一段。
逐級表（1–40 級）來自 poe2db，存在 tools/poe2_granted/granted_tables.tsv（技能、說明、每一級用 ; 分開，同一級的多個值用 | 分開，範圍用 ,）。
用法：python3 tools/gen_granted_skills.py > /tmp/granted.yml，再把輸出換掉 skills.yml 裡 BEGIN/END granted 之間的那段。"""
import sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
rows = {}
for line in open(os.path.join(HERE, 'poe2_granted', 'granted_tables.tsv'), encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    if len(p) >= 3:
        rows[p[0]] = [lvl.split('|') for lvl in p[2].split(';') if lvl]

def col(name, i=0):
    return [r[i] for r in rows[name]]

def ranges(name, i=0):
    out = []
    for v in col(name, i):
        a, b = v.split(',')[:2]
        out.append([int(float(a)), int(float(b))])
    return out

def nums(name, i=0, sep=None):
    out = []
    for v in col(name, i):
        if sep:
            v = v.split(sep)[0]
        x = float(v)
        out.append(int(x) if x == int(x) else x)
    return out

def summed(name):
    """兩欄傷害（物理＋混沌）加起來，回傳 (表, 第一欄占的比例)。"""
    a, b = ranges(name, 0), ranges(name, 1)
    total = [[x[0] + y[0], x[1] + y[1]] for x, y in zip(a, b)]
    share = sum(x[0] + x[1] for x in a) / sum(t[0] + t[1] for t in total)
    return total, round(share, 2)

def fl(v):
    return '[' + ', '.join(str(x) for x in v) + ']'

def dmg(v):
    return '[' + ', '.join('[%d, %d]' % (a, b) for a, b in v) + ']'

SRC = '2026-10-26 照 PoE2（poe2db %s 1–40 級）'
out = []
def skill(sid, name, desc, icon, tags, mechanic, params, cast, crit=None, damage=None, poe=None, types=None, mods=(), extra=(), visual='staff_bolt', level_mods=(), tag_note=None):
    out.append(f'  {sid}:')
    out.append(f'    name: {name}')
    out.append(f'    description: {desc}')
    out.append(f'    icon: {icon}')
    out.append('    type: spell')
    out.append('    tier: 1')
    out.append(f'    tags: [{", ".join(tags)}]' + (f'   # {tag_note}' if tag_note else ''))
    out.append('    source: weapon   # 2026-10-26：法杖、長杖、權杖賦予（PoE2 的 Grants Skill），沒有寶石')
    out.append(f'    mechanic: {mechanic}')
    if params:
        out.append(f'    {params}')
    for e in extra:
        out.append(f'    {e}')
    if damage is not None:
        out.append(f'    damage: {dmg(damage)}   # {SRC % poe}')
        out.append(f'    damage_types: {{ {", ".join(f"{k}: {v}" for k, v in types.items())} }}')
        out.append('    effectiveness: 1.0')
    out.append(f'    cast_time: {cast}')
    out.append('    mana: 0   # PoE2：武器賦予的技能不花魔力')
    out.append(f'    visual: {visual}')
    ms = list(mods)
    if crit is not None:
        ms.insert(0, f'{{ stat: spell.base_crit, type: FLAT, value: {crit} }}')
    if ms:
        out.append('    modifiers:')
        for m in ms:
            out.append(f'      - {m}')
    if level_mods:
        out.append('    level_modifiers:')
        for m in level_mods:
            out.append(f'      - {m}')
    out.append('')

# ---- 法杖 ----
cb = ranges('Chaos_Bolt')
skill('chaos_bolt', '蝕星彈', '射出一顆混沌能量彈，擊中時給敵人凋零（每層受到的混沌傷害增加 5%，最多 10 層）。法杖賦予。',
      'ECHO_SHARD', ['spell', 'projectile', 'chaos'], 'projectile', 'projectile: { speed: 1.9, range: 30, size: 0.3 }   # PoE2：38 公尺／秒',
      0.75, 7, cb, 'Chaos Bolt', {'chaos': 1.0},
      level_mods=['{ stat: withered.on_hit, type: FLAT, value: %s }   # %s 逐級（1 級 1 層 → 20 級 3 層 → 40 級 5 層）；這次之前固定 2 層' % (fl(nums('Chaos_Bolt', 1)), '2026-10-26 照 PoE2')], visual='chaos_bolt')
skill('bone_blast', '骨綻', '準心指的地方 1 秒後從地底炸出骨刺，打傷一小圈敵人，40% 機率流血。法杖賦予。',
      'BONE', ['spell', 'area', 'physical', 'duration'], 'impact', 'impact: { range: 22, delay: 1.0, radius: 1.0 }   # PoE2：儀式半徑 1 公尺、1 秒',
      0.75, 14, ranges('Bone_Blast'), 'Bone Blast', {'physical': 1.0},
      mods=['{ stat: bleed.chance, type: FLAT, value: 40 }'], visual='detonate_dead')
md = nums('Mana_Drain')
skill('mana_drain', '吸魔術', '吸取面前一片敵人的魔力：每打到一隻敵人回復魔力（隨技能等級），並阻礙牠（移動速度變慢 30%，4 秒）。幾乎不造成傷害（每下 1 點）。法杖賦予。',
      'LAPIS_LAZULI', ['spell'], 'cone', 'cone: { reach: 5, angle: 70 }',
      0.85, None, [[1, 1]] * 40, 'Mana Drain（PoE2 不造成傷害；這裡每下 1 點，才算得到擊中）', {'chaos': 1.0},
      mods=['{ stat: hinder.on_hit, type: FLAT, value: 100 }'],
      level_mods=['{ stat: mana.on_hit, type: FLAT, value: %s }   # %s 逐級（30 → %d → %d）；這次之前固定 %d（原作是 70%% 較慢的吸取，這裡打中就回）' % (fl(md), '2026-10-26 照 PoE2', md[19], md[-1], md[19])],
      visual='aura_mana')
ps = [[a, b] for a, b in ranges('Power_Siphon')]
skill('power_siphon', '銳星虹吸', '朝前方射出一道虹吸彈，擊中有撲殺效果；撲殺或打死敵人時獲得一顆銳星。法杖賦予。',
      'AMETHYST_SHARD', ['spell', 'physical'], 'projectile', 'projectile: { speed: 2.0, range: 24, size: 0.3 }   # 2026-10-26 照 PoE2：原作沒有投射物類型（這裡還是用彈道找目標）',
      0.7, 15, ps, 'Power Siphon', {'physical': 1.0},
      mods=['{ stat: flag.culling, type: FLAT, value: 1 }', '{ stat: charge.event.kill.power, type: FLAT, value: 100 }'], visual='wand_bolt')
skill('volatile_dead', '屍焰爆', '引爆準心附近的一具屍體，炸出火焰，波及周圍 2.2 格的敵人並容易點燃。法杖賦予。',
      'BLAZE_POWDER', ['spell', 'area', 'fire'], 'corpse', 'corpse: { range: 16, search: 6, radius: 2.2, percent: 0 }   # PoE2：爆炸 2.2 公尺',
      0.8, 7, ranges('Volatile_Dead', 1), 'Volatile Dead（爆炸那一下）', {'fire': 1.0},
      mods=['{ stat: ignite.chance, type: FLAT, value: 25 }'], visual='detonate_dead')
skill('galvanic_field', '雷流場', '在準心的地方放一個電場，6 秒內每 0.6 秒電一次範圍內的敵人。法杖賦予。',
      'COPPER_INGOT', ['spell', 'area', 'lightning', 'duration', 'chain', 'orb'], 'zone', 'zone: { range: 18, radius: 1.9, duration: 6, interval: 0.6, damage: 100, max: 1 }   # PoE2：1.9 公尺、6 秒、每 0.6 秒',
      0.65, 9, ranges('Galvanic_Field'), 'Galvanic Field', {'lightning': 1.0}, visual='arc',
      tag_note='2026-10-26 照 PoE2（poe2db 寶石標籤）加 orb（Galvanic Field 是法球：天賦樹「法球技能」的加成看它）；這次之前 [spell, area, lightning, duration, chain]')
skill('decompose', '腐屍爆', '讓準心附近的一具屍體腐爛爆開，火焰波及周圍一小圈的敵人。法杖賦予。',
      'GUNPOWDER', ['spell', 'area', 'fire', 'chaos', 'duration'], 'corpse', 'corpse: { range: 16, search: 6, radius: 1.6, percent: 0 }   # PoE2：1.6 公尺',
      0.8, 8, ranges('Decompose'), 'Decompose', {'fire': 1.0}, visual='detonate_dead')
skill('exsanguinate', '血脈牽', '射出一道血光打中前方的敵人，再連鎖跳到另外兩個敵人（共 3 個），讓牠們流血。法杖賦予。',
      'NETHER_BRICK', ['spell', 'chain', 'physical', 'duration'], 'chain', 'chain: { range: 12, chains: 2, chain_range: 7 }   # PoE2：連鎖 2 次、共 3 個目標',
      1.0, 8, ranges('Exsanguinate'), 'Exsanguinate', {'physical': 1.0},
      mods=['{ stat: bleed.chance, type: FLAT, value: 100 }'], visual='arc')
wt = nums('Wither')
skill('wither', '凋蝕術', '按住引導：準心處的一圈凋零之地慢慢跟著準心移動，每一次施法間隔讓圈裡的敵人多一層凋零（每層受到的混沌傷害增加）；引導中移動速度更少 70%。放開結束。法杖賦予。',
      'COAL', ['spell', 'area', 'chaos', 'duration', 'channelling'], 'mark',
      'charge: { mode: pulse }   # 2026-10-26 照 PoE2 的引導（Wither：按住期間一直作用；這次之前一按一次、一次給 2 層）',
      0.25, visual='blight_nova',
      extra=['mark: { range: 22, radius: 2.5, duration: %s, taken: 0 }   # 2026-10-26 照 PoE2：凋零 2 秒（1 級）→ %s 秒（40 級），每次作用一層；這次之前 %s 秒固定、一次給 debuff.taken.chaos 10' % (fl(wt), wt[-1], wt[19])],
      tag_note='2026-10-26 照 PoE2 加 channelling 標籤（天賦樹的系統）；這次之前 [spell, area, chaos, duration]')
cbt, share = summed('Coiling_Bolts')
skill('coiling_bolts', '纏蝕箭', '同時射出兩道纏繞的弩箭：一道物理、一道混沌，各自打中。法杖賦予。',
      'ECHO_SHARD', ['spell', 'projectile', 'chaos'], 'projectile', 'projectile: { count: 2, speed: 1.2, range: 30, size: 0.3 }   # 2026-10-26 照 PoE2 Coiling Bolts：物理、混沌各一發（flag.split_damage_types）；這次之前一發 50／50',
      1.0, 7, cbt, 'Coiling Bolts（物理＋混沌加起來）', {'physical': share, 'chaos': round(1 - share, 2)}, visual='chaos_bolt',
      mods=['{ stat: flag.split_damage_types, type: FLAT, value: 1 }   # 2026-10-26 照 PoE2：兩發各帶一種傷害（各是表上那一種的數值，加起來跟以前一發一樣）'],
      tag_note='2026-10-26 照 PoE2（poe2db 寶石標籤）拿掉 physical（Coiling Bolts 的標籤只有法術、投射物、混沌；傷害照樣一半物理）；這次之前 [spell, projectile, physical, chaos]')

# ---- 長杖 ----
skill('firebolt', '焰星彈', '射出一顆慢慢飛的火焰彈，擊中時小範圍爆炸。長杖賦予。',
      'BLAZE_POWDER', ['spell', 'projectile', 'area', 'fire'], 'projectile', 'projectile: { speed: 0.52, range: 26, size: 0.35, explode: 0.7 }   # PoE2：10.4 公尺／秒、爆炸 0.7 公尺',
      0.9, 8, ranges('Firebolt'), 'Firebolt', {'fire': 1.0}, visual='fireball')   # 2026-10-26 照 PoE2：拿掉點燃機率 +20%（原作沒有）
skill('freezing_shards', '霜晶扇', '朝前方扇形射出 5 片冰晶，凍結累積更多 100%。長杖賦予。',
      'PRISMARINE_SHARD', ['spell', 'projectile', 'cold'], 'projectile', 'projectile: { count: 5, spread: 30, speed: 0.8, range: 22, size: 0.25 }   # PoE2：16 公尺／秒、5 片',
      1.2, 11, ranges('Freezing_Shards'), 'Freezing Shards', {'cold': 1.0}, mods=['{ stat: freeze.buildup, type: MORE, value: 100 }'], visual='ice_shot')
skill('lightning_bolt', '落雷', '準心指的地方落下一道閃電，打傷一小圈敵人，感電機率更多 200%。長杖賦予。',
      'GLOWSTONE_DUST', ['spell', 'area', 'lightning'], 'impact', 'impact: { range: 22, delay: 0.1, radius: 0.8 }   # PoE2：半徑 0.8 公尺',
      0.85, 10, ranges('Lightning_Bolt'), 'Lightning Bolt', {'lightning': 1.0},
      mods=['{ stat: shock.chance, type: MORE, value: 200 }   # 2026-10-26 照 PoE2：只有感電機率更多 200%（這次之前另外 +15%）'], visual='lightning_arrow')
sp = nums('Sigil_of_Power', 0)
skill('sigil_of_power', '力印', '在腳下畫一個法印：10 秒內你的法術傷害更多（隨技能等級）。冷卻 10 秒。長杖賦予。',
      'ENCHANTED_BOOK', ['spell', 'duration', 'area'], 'guard', None, 0.6,
      extra=['weapons: [staff]', 'guard:', '  duration: 10', '  slow: 0', '  effects:',
             '    - { stat: damage, type: MORE, value: %s, tags: [spell] }   # PoE2：每階 %s%%（逐級）× 2 階' % (fl([v * 2 for v in sp]), fl(sp[:3])[:-1] + '…]'),
             'cooldown: 10'], visual='aura_purity',
      level_mods=['{ stat: skill.duration, type: INCREASED, value: %s }   # 2026-10-26 照 PoE2：法印持續 10 → 11.9 → 13.9 秒（逐級）' % fl([round((v / 10 - 1) * 100, 2) for v in nums('Sigil_of_Power', 1)])])
sr = ranges('Soulrend', 0); dps = nums('Soulrend', 1)
ratio = round(sum(dps) / sum((a + b) / 2 for a, b in sr) * 100)
skill('soulrend', '裂魂彈', '射出一道慢慢飛、穿透所有敵人的靈魂彈，造成混沌傷害，留下混沌持續傷害，並阻礙敵人。長杖賦予。',
      'ECHO_SHARD', ['spell', 'projectile', 'chaos', 'duration'], 'projectile', 'projectile: { speed: 0.4, range: 24, size: 0.6, pierce: 20 }   # PoE2：8 公尺／秒、穿透全部',
      0.8, 13, sr, 'Soulrend', {'chaos': 1.0},
      mods=['{ stat: debuff.duration, type: FLAT, value: 0.6 }   # 2026-10-26 照 PoE2：0.6 秒（彈經過時一直重新給）；這次之前 2 秒', '{ stat: hinder.on_hit, type: FLAT, value: 100 }'],
      level_mods=['{ stat: debuff.chaos, type: FLAT, value: %s }   # 2026-10-26 照 PoE2：每秒混沌傷害照原作表 ÷ 那一級擊中平均（逐級）；這次之前固定 %d%%' % (fl([round(d / ((a + b) / 2) * 100) for d, (a, b) in zip(dps, sr)]), ratio)],
      visual='chaos_bolt')
skill('reap', '血鐮', '準心指的地方揮下一道血鐮，有撲殺效果，擊中時給敵人 3 層暴擊弱點（每層擊中牠的暴擊率 +0.5%）。長杖賦予。',
      'NETHERITE_SCRAP', ['spell', 'area', 'physical', 'duration'], 'impact', 'impact: { range: 18, delay: 0.3, radius: 2.5 }   # 2026-10-26 照 PoE2：2.5 公尺（這次之前 2.0）',
      1.2, 14, ranges('Reap'), 'Reap', {'physical': 1.0},
      mods=['{ stat: flag.culling, type: FLAT, value: 1 }', '{ stat: crit_weakness.on_hit, type: FLAT, value: 3 }'],
      visual='slash')
skill('enervating_nova', '衰雷環', '以自己為中心炸出一圈閃電（1 到 2.6 格的環），觸電累積更多。長杖賦予。',
      'GLOWSTONE_DUST', ['spell', 'area', 'lightning', 'nova', 'duration'], 'nova', 'nova: { radius: 2.6 }   # PoE2：環 1–2.6 公尺',
      1.0, 11, ranges('Enervating_Nova', 0), 'Enervating Nova', {'lightning': 1.0},
      level_mods=['{ stat: electrocute.buildup, type: MORE, value: %s }   # 2026-10-26 照 PoE2 逐級（100 → 157 → 202）；這次之前固定 100' % fl(nums('Enervating_Nova', 1))], visual='frost_nova')
cs = nums('Consecrate')
skill('consecrate', '聖地', '在腳下奉獻一塊聖地：8 秒內每秒回復 5% 最大生命（聖地效果隨技能等級增加）。長杖賦予。',
      'GLOW_INK_SAC', ['spell', 'area', 'duration', 'nova'], 'guard', None, 1.25,
      extra=['weapons: [staff]', 'guard:', '  duration: 8', '  slow: 0', '  effects:',
             '    - { stat: life.regen_percent, type: FLAT, value: %s }   # PoE2：5%% × (1 + 聖地效果增加 0%%→%d%%)' % (fl([round(5 * (1 + c / 100), 2) for c in cs]), cs[-1]),
             '# 2026-10-26 照 PoE2：沒有冷卻（這次之前 8 秒；原作是站在聖地上才回，這裡是跟著你的增益）'], visual='aura_life')
skill('dark_pact', '冥契', '以自己為中心炸出一圈混沌能量。長杖賦予。',
      'COAL', ['spell', 'area', 'chaos', 'nova', 'minion'], 'nova', 'nova: { radius: 2.5 }',
      0.7, 7, ranges('Dark_Pact', 0), 'Dark Pact', {'chaos': 1.0}, visual='blight_nova')
ff = col('Feast_of_Flesh')
lifes = [int(v.split(',')[0]) for v in ff]
skill('feast_of_flesh', '噬屍宴', '吞下周圍的屍體：3 秒內回復生命與魔力（不用屍體，固定算 3 具的量）。冷卻 6 秒。長杖賦予。',
      'BONE', ['spell', 'area', 'nova'], 'guard', None, 1.3,
      extra=['weapons: [staff]', 'guard:', '  duration: 3', '  slow: 0', '  effects:',
             '    - { stat: life.regen, type: FLAT, value: %s }   # PoE2：每具 21→%d 生命、3 秒；× 3 具 ÷ 3 秒' % (fl(lifes), lifes[-1]),
             '    - { stat: mana.regen, type: FLAT, value: %s }' % fl(lifes),
             'cooldown: 6'], visual='aura_life')
hi = ranges('Heart_of_Ice')
skill('heart_of_ice', '寒心', '瞬間以自己為中心爆出一圈寒氣，冰緩效果更多 100%。冷卻 4 秒。長杖、權杖賦予。',
      'PRISMARINE_CRYSTALS', ['spell', 'area', 'cold', 'nova'], 'nova', 'nova: { radius: 4.0 }',
      0.1, 11, hi, 'Heart of Ice（PoE2 瞬發；這裡最短 0.1 秒）', {'cold': 1.0}, mods=['{ stat: chill.effect, type: MORE, value: 100 }'], extra=['cooldown: 4'], visual='frost_nova')

# ---- 權杖（精魂光環；PoE2 武器賦予的光環不保留精魂）----
def aura(sid, name, desc, icon, effects=None, enemy=None, visual='aura_purity', tags=('aura',), tag_note=None):
    out.append(f'  {sid}:')
    out.append(f'    name: {name}')
    out.append(f'    description: {desc}')
    out.append(f'    icon: {icon}')
    out.append('    type: spell')
    out.append('    tier: 1')
    out.append(f'    tags: [{", ".join(tags)}]' + (f'   # {tag_note}' if tag_note else ''))
    out.append('    source: weapon   # 2026-10-26：權杖賦予（PoE2 的 Grants Skill），沒有寶石')
    out.append('    mechanic: aura')
    out.append('    aura:')
    out.append('      reserve: 0   # PoE2：權杖賦予的光環不寫保留（權杖本身給 100 精魂）')
    out.append('      radius: 8')
    out.append('      allies: true')
    if effects:
        out.append('      effects:')
        for e in effects:
            out.append(f'        - {e}')
    if enemy:
        out.append(f'      enemy: {enemy}')
    out.append('    # 2026-10-26 照 PoE2：常駐光環即時開關、沒有冷卻（這次之前寫 cast_time 0.5、cooldown 1.5，光環本來就不吃）')
    out.append('    mana: 0')
    out.append(f'    visual: {visual}')
    out.append('')

aura('discipline', '律盾', '光環：你和範圍內的隊友最大能量護盾增加（隨技能等級）。權杖賦予。', 'AMETHYST_SHARD',
     ['{ stat: es.max, type: FLAT, value: %s }   # %s' % (fl(nums('Discipline')), SRC % 'Discipline')], visual='aura_mana')
aura('malice', '惡念', '光環：範圍內的敵人每隔一小段時間（0.7 秒 → 0.4 秒，隨技能等級）得到 1 層暴擊弱點（每層擊中牠的暴擊率 +0.5%，最多 20 層）。權杖賦予。', 'COAL',
     enemy='{ taken: 0, crit_weakness_every: %s }   # %s（31～39 級照原作 0.005 秒一級，表裡四捨五入了）' % (fl([0.7, 0.69, 0.68, 0.67, 0.66, 0.65, 0.64, 0.63, 0.62, 0.61, 0.6, 0.59, 0.58, 0.57, 0.56, 0.55, 0.54, 0.53, 0.52, 0.51, 0.5, 0.49, 0.48, 0.47, 0.46, 0.45, 0.44, 0.43, 0.42, 0.41, 0.405, 0.4, 0.395, 0.39, 0.385, 0.38, 0.375, 0.37, 0.365, 0.36]), SRC % 'Malice'), visual='blight_nova')
# 2026-10-27 圖示：三種守照元素分開（這次之前都是 GLOWSTONE_DUST）；腐屍爆、噬屍宴不用腐肉（會被吃掉）、血脈牽不用紅石（右鍵會放紅石線）
PURITY_ICON = {'fire': 'MAGMA_CREAM', 'cold': 'QUARTZ', 'lightning': 'GLOWSTONE_DUST'}
for sid, name, stat, el, tag in [('purity_of_fire', '焰守', 'resist.fire', '火焰', 'fire'), ('purity_of_ice', '霜守', 'resist.cold', '冰冷', 'cold'),
                                 ('purity_of_lightning', '雷守', 'resist.lightning', '閃電', 'lightning')]:
    aura(sid, name, f'光環：你和範圍內的隊友{el}抗性提高（隨技能等級）。權杖賦予。', PURITY_ICON[tag],
         ['{ stat: %s, type: FLAT, value: %s }   # %s' % (stat, fl(nums('Purity_of_Fire')), SRC % 'Purity of Fire／Ice／Lightning（三個同一張表）')],
         tags=('aura', tag), tag_note='2026-10-26 照 PoE2（poe2db 寶石標籤）加 %s（Purity of 系列有那種元素的標籤）；這次之前 [aura]' % tag)
aura('impurity', '蝕守', '光環：你和範圍內的隊友混沌抗性提高（隨技能等級）。權杖賦予。', 'COAL',
     ['{ stat: resist.chaos, type: FLAT, value: %s }   # %s' % (fl(nums('Impurity')), SRC % 'Impurity')],
     tags=('aura', 'chaos'), tag_note='2026-10-26 照 PoE2（poe2db 寶石標籤）加 chaos（Impurity 有混沌標籤）；這次之前 [aura]')

# ---- 2026-10-26 第二批：原作機制比較特殊的 5 個 ----
ss = nums('Spellslinger')
out.append('  spellslinger:')
out.append('    name: 咒擲')
out.append('    description: 主要寶石（常駐、祈喚，決鬥法杖賦予，不保留精魂）：開著時你自己施放法術就獲得能量（基礎施法時間每 0.1 秒 2 點，隨技能等級增加），最多存 500 點。按它的按鍵（冷卻 0.2 秒）就花掉能量觸發所有插槽法術：每次花它們的上限加起來（基礎施法時間每 0.1 秒 10 點），能量夠就連續觸發好幾次。插槽是放在它孔裡的法術（第 1 孔起，可以放好幾個，全部一起觸發；其他孔的輔助套在它們上面），被觸發的不花魔力。')
out.append('    icon: ECHO_SHARD')
out.append('    type: spell')
out.append('    tier: 1')
out.append('    tags: [persistent, meta, invocation]')
out.append('    source: weapon   # 2026-10-26：法杖賦予（PoE2 的 Spellslinger），沒有寶石')
out.append('    mechanic: aura')
out.append('    # 2026-10-26 照 PoE2：只有決鬥法杖（Dueling Wand，bases/weapons_casters.yml 的 grants）賦予；RePoE 的 MetaSpellslingerPlayer 沒有 reservations、')
out.append('    #   poe2db 也沒有 Reservation（其他引星都有），所以不保留精魂（reserve 0 不變，查證後確認）')
out.append('    aura: { reserve: 0, radius: 8, allies: false }')
out.append('    # 2026-10-26 照 PoE2 Spellslinger 是 Invocation：能量存到 500、按下去才觸發；這次之前能量滿了自動觸發')
out.append('    meta: { kind: spell_cast, energy: 2, max_energy: 500, energy_more: %s }   # %s' % (fl(ss), SRC % 'Spellslinger：能量獲得增加 0→57%'))
out.append('    cast_time: 0.1   # 2026-10-26 照 PoE2：常駐，即時開關（原作 0；這裡最短 0.1）')
out.append('    cooldown: 0.2')
out.append('    mana: 0')
out.append('    visual: aura_haste')
out.append('')
un = nums('Unleash')
out.append('  unleash_spell:')
out.append('    name: 封釋')
out.append('    description: 長杖賦予：4 秒內你下一個可以重複的法術（沒有冷卻的、會造成傷害的）會自動再放 2 次（不花魔力，重複的那幾次傷害更少 50%）。用掉之後冷卻＝1 秒＋那個法術施法時間的 12 倍（20 級 10.1 倍）。')
out.append('    icon: ENCHANTED_BOOK')
out.append('    type: spell')
out.append('    tier: 1')
out.append('    tags: [duration, cooldown]')
out.append('    source: weapon   # 2026-10-26：長杖賦予（PoE2 的 Unleash），沒有寶石')
out.append('    weapons: [staff]')
out.append('    mechanic: guard')
out.append('    guard:')
out.append('      duration: 4')
out.append('      slow: 0')
out.append('    barrage:')
out.append('      spells: true')
out.append('      repeats: 2')
out.append('      less: 50   # 2026-10-26 照 PoE2 Unleash：重複的那幾次更少 50% 傷害；這次之前 0')
out.append('      gap: 0.25')
out.append('      cooldown_attack_time: %s   # %s' % (fl(un), SRC % 'Unleash：加到冷卻的施法時間 %'))
out.append('      max_cooldown: 120')
out.append('    cast_time: 0.75')
out.append('    cooldown: 1')
out.append('    mana: 0')
out.append('    visual: aura_purity')
out.append('')

def granted_aura(sid, name, desc, icon, stat, values, poe, visual, cast='0.5', tags=('aura',), tag_note=None, no_cast=False):
    out.append(f'  {sid}:')
    out.append(f'    name: {name}')
    out.append(f'    description: {desc}')
    out.append(f'    icon: {icon}')
    out.append('    type: spell')
    out.append('    tier: 1')
    out.append(f'    tags: [{", ".join(tags)}]' + (f'   # {tag_note}' if tag_note else ''))
    out.append('    source: weapon   # 2026-10-26：武器賦予（PoE2），沒有寶石；效果在程式 GrantedAuras')
    out.append('    mechanic: aura')
    out.append('    aura: { reserve: 0, radius: 8, allies: false }')
    if no_cast:
        out.append('    # 2026-10-26 照 PoE2：常駐即時開關、沒有冷卻（這次之前寫 cast_time 0.5、cooldown 1.5，本來就不吃）')
    else:
        out.append(f'    cast_time: {cast}')
        out.append('    cooldown: 1.5')
    out.append('    mana: 0')
    out.append(f'    visual: {visual}')
    out.append('    level_modifiers:')
    out.append('      - { stat: %s, type: FLAT, value: %s }   # %s' % (stat, fl(values), SRC % poe))
    out.append('')

granted_aura('fulmination', '雷霆共鳴', '常駐（權杖賦予）：你的技能第一次打中身邊感電中的敵人時，也打身邊其他感電中的敵人（最多 8 個）；多打的那幾下更少 90%（20 級 71%），全部轉成閃電，不會造成感電。',
             'GLOWSTONE_DUST', 'fulmination.less', nums('Fulmination'), 'Fulmination：多打的更少 %', 'herald_thunder', no_cast=True,
             tags=('aura', 'persistent', 'lightning'), tag_note='2026-10-26 照 PoE2（poe2db 寶石標籤）加 persistent、lightning（Fulmination 是常駐增益、閃電）；這次之前 [aura]')
granted_aura('mirror_of_refraction', '折光之鏡', '常駐（長杖賦予）：身邊每隔一段時間（3.9 秒 → 2 秒，隨技能等級；冷卻回復加快）出現一面鏡子，最多 4 面、每面 8 秒。你的法術投射物碰到鏡子就把它打碎，從鏡子往四周複製 7 發（複製的還能再被別的鏡子複製）。',
             'AMETHYST_SHARD', 'mirror.interval', nums('Mirror_of_Refraction'), 'Mirror of Refraction：鏡子出現的間隔（秒）', 'aura_purity', no_cast=True,
             tags=('aura', 'persistent'), tag_note='2026-10-26 照 PoE2（poe2db 寶石標籤）加 persistent（Mirror of Refraction 是常駐增益）；這次之前 [aura]')
# 2026-10-26 照 PoE2：振翅熵流（2 公尺持續傷害、每隻烏鴉更多 5%）與指令亂鴉狂舞（施放 1 秒），程式在 GrantedAuras
granted_aura('spiraling_conspiracy', '鴉群密謀', '常駐（長杖賦予）：身邊有稀有、傳奇敵人時每 2 秒（20 級 1.05 秒）加入一隻烏鴉；你或召喚物殺敵時，每 1 點怪物強度（一般 1、魔法 2、稀有 5、傳奇 10）加入一隻（每隻留 20 秒、最多 40 隻）。烏鴉群在你身邊 2 格造成混沌與物理的持續傷害，每一隻烏鴉傷害與範圍更多 5%。按這個鍵下指令「亂鴉狂舞」：用掉 10 隻烏鴉，準心出現兩面鏡子 5 秒，中間的敵人每 0.12 秒被打一下（550%，七成混沌；冷卻 10 秒）。',
             'FEATHER', 'raven.interval', nums('Spiraling_Conspiracy', 0, '|'), 'Spiraling Conspiracy：加入一隻烏鴉的間隔（秒）', 'blight_nova', cast='1.0   # 2026-10-26 照 PoE2 指令（亂鴉狂舞）的施放時間',
             tags=('aura', 'persistent', 'physical', 'chaos'), tag_note='2026-10-26 照 PoE2（poe2db 寶石標籤）加 persistent、physical、chaos（Spiraling Conspiracy 是常駐、物理、混沌）；這次之前 [aura]')
out.append('  proc_raven_flock:')
out.append('    name: 鴉群')
out.append('    icon: FEATHER   # 2026-10-27 圖示：烏鴉群（跟鴉群密謀同一個）；這次之前沒寫，顯示成書')
out.append('    hidden: true')
out.append('    type: spell')
out.append('    tags: [spell, area, physical, chaos]')
out.append('    mechanic: nova')
out.append('    nova: { radius: 2.0 }   # 2026-10-26 照 PoE2 振翅熵流：2 公尺、每秒混沌＋物理照寶石等級（MinionAbilities.ENTROPY_*），每隻烏鴉更多 5%；這裡的傷害只剩標籤用。這次之前 3.0、每隻烏鴉一份')
RAVEN = [[max(1, round(1 * (20 / 1) ** ((lv - 1) / 19))), max(2, round(2 * (37 / 2) ** ((lv - 1) / 19)))] for lv in range(1, 41)]
out.append('    damage: %s   # 2026-10-26 修正：以前寫 [[1, 2], [20, 37]] 會被當成 1 級、2 級的逐級表（2 級就 20～37）；改成 40 級表（1 級 1～2 → 20 級 20～37，照同樣的倍率往後推）' % dmg(RAVEN))
out.append('    damage_types: { physical: 0.5, chaos: 0.5 }')
out.append('    cast_time: 0.5')
out.append('    effectiveness: 1.0')
out.append('    visual: blight_nova')
out.append('')

print('  # ==== BEGIN granted：2026-10-26 法杖、長杖、權杖賦予的技能（tools/gen_granted_skills.py 產生，別手改）====')
print('\n'.join(out).rstrip())
print('  # ==== END granted ====')
