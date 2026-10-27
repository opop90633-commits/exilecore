#!/usr/bin/env python3
"""2026-10-26：把 8 個原本跟 PoE2 做法不同的技能改成對應的 PoE2 技能（逐級表在 tools/poe2_skills/poe2_skill_*.txt，poe2db 抓的）。
骨棘陣→Unearth、跳電矢→Shockchain Arrow、霜浪→Wave of Frost、雷遁→Lightning Warp、震脈掌→Staggering Palm、
寒魄掌→Shattering Palm、焰幕→Flame Wall、凜霜扇矢→Freezing Salvo。整塊重寫（舊的寫法留在 git 歷史）。可以重跑。"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / 'src/main/resources/data/skills.yml'
SRC = ROOT / 'tools/poe2_skills'

def table(slug):
    rows = []
    for line in (SRC / ('poe2_skill_%s.txt' % slug)).read_text(encoding='utf-8').splitlines():
        if re.match(r'^\d+\t', line):
            rows.append(line.split('\t'))
    assert len(rows) == 40, slug
    return rows

def pct(v):
    return round(float(v.strip().rstrip('%')) / 100, 2)

def rng(v):
    a, b = v.split('-')
    return [int(float(a)), int(float(b))]

def num(v):
    x = float(v.strip().rstrip('/s'))
    return int(x) if x == int(x) else x

def L(values):
    return '[' + ', '.join(str(v) for v in values) + ']'

def D(values):
    return '[' + ', '.join('[%d, %d]' % tuple(v) for v in values) + ']'

def col(rows, i, f):
    return [f(r[i]) for r in rows]

NOTE = '2026-10-26 照 PoE2（poe2db %s 1–40 級）'
BLOCKS = {}

r = table('Unearth')
BLOCKS['bone_spikes'] = f'''    name: 骨棘陣
    description: 面前一片扇形地面竄出骨刺，刺傷範圍內的敵人。
    icon: BONE   # 2026-10：原本 BONE_BLOCK（原版右鍵有動作：放方塊、穿戴、吃、使用…會干擾施放）
    type: spell
    tier: 5
    tags: [spell, area, physical]
    mechanic: cone   # 2026-10-26 照 PoE2 Unearth：面前 6 公尺的錐形（以前是準心的一圈）；把屍體變成骨構體的召喚沒做
    cone: {{ length: 6, angle: 60 }}
    damage: {D(col(r, 1, rng))}   # {NOTE % 'Unearth'}
    damage_types: {{ physical: 1.0 }}
    cast_time: 0.95
    effectiveness: 1.0
    mana: {L(col(r, 2, num))}
    visual: puncture
    modifiers:
      - {{ stat: spell.base_crit, type: FLAT, value: 15 }}
'''

r = table('Shockchain_Arrow')
BLOCKS['chain_arrow'] = f'''    name: 跳電矢
    description: 射出一支帶電的箭，打中後跳到附近的敵人身上好幾次，每打中一個都在牠身邊炸出一圈電擊波。物理傷害大部分轉成閃電；不會造成感電。
    icon: COPPER_INGOT
    type: attack
    tier: 7
    tags: [attack, projectile, lightning, chain, area]
    weapons: [bow]
    mechanic: projectile
    projectile: {{ arrow: true, speed: 1.63, range: 36 }}   # 2026-10-26 照 PoE2 Shockchain Arrow：32.6 公尺／秒
    convert: {{ lightning: 0.8 }}
    attack_speed: 0.7
    effectiveness: {L(col(r, 1, pct))}   # {NOTE % 'Shockchain Arrow 箭本身'}
    mana: {L(col(r, 2, num))}
    visual: lightning_arrow
    modifiers:   # 原作：最多連鎖 8 次（第一次打到感電中的敵人多 3 次）；連鎖完插地變雷樁、追蹤異常的敵人沒做
      - {{ stat: skill.chains, type: FLAT, value: 5 }}
      - {{ stat: shock.chance, type: MORE, value: -100 }}
      - {{ stat: proc.hit, type: FLAT, value: 100, tags: [proc_shockchain_wave] }}
'''
BLOCKS['proc_shockchain_wave'] = f'''    name: 跳電震波
    hidden: true
    type: attack
    tags: [attack, projectile, area, lightning]
    mechanic: nova
    nova: {{ radius: 2.5 }}   # 2026-10-26 照 PoE2 Shockchain Arrow 的電擊波：2.5 公尺
    convert: {{ lightning: 1.0 }}
    attack_speed: 1.0
    effectiveness: {L(col(r, 3, pct))}   # {NOTE % 'Shockchain Arrow 電擊波'}
    visual: lightning_arrow
'''

r = table('Wave_of_Frost')
BLOCKS['frost_wave'] = f'''    name: 霜浪
    description: 往後一翻，同時朝前方送出一道貫穿的冰浪，打中整條路上的敵人；凍結累積、冰緩效果都大幅提高。
    icon: PRISMARINE_SHARD   # 2026-10：原本 PACKED_ICE（原版右鍵有動作：放方塊、穿戴、吃、使用…會干擾施放）
    type: attack
    tier: 7
    tags: [attack, projectile, cold]
    weapons: [quarterstaff]
    mechanic: projectile
    projectile: {{ speed: 1.1, range: 6, size: 1.3, pierce: 20 }}   # 2026-10-26 照 PoE2 Wave of Frost：冰浪 6 公尺（往後翻沒做）
    convert: {{ cold: 0.8 }}
    attack_speed: 0.55
    effectiveness: {L(col(r, 1, pct))}   # {NOTE % 'Wave of Frost'}
    mana: {L(col(r, 2, num))}
    visual: storm_wave
    modifiers:   # 原作逐級 300%→810%（取 20 級的 585%）
      - {{ stat: freeze.buildup, type: MORE, value: 585 }}
      - {{ stat: chill.effect, type: MORE, value: 585 }}
'''

r = table('Lightning_Warp')
BLOCKS['storm_blink'] = f'''    name: 雷遁
    description: 瞄準一個敵人，在牠腳下留下 8 秒的感電地面。牠的生命已經低到可以撲殺（普通 30%、魔法 20%、稀有 10%、傳奇 5%）就瞬移進去把牠炸掉，炸到周圍 3 格，並獲得一層閃電灌注；還沒低到就掛 4 秒標記，期間掉到門檻就自動瞬移過去。
    icon: COPPER_INGOT   # 2026-10：原本 LIGHTNING_ROD（原版右鍵有動作：放方塊、穿戴、吃、使用…會干擾施放）
    type: spell
    tier: 5
    tags: [spell, lightning, movement, area, duration]
    mechanic: dash
    dash: {{ range: 20, end_radius: 3.0 }}   # 2026-10-26 照 PoE2 Lightning Warp：爆炸 3 公尺、撲殺；沒有冷卻
    damage: {D(col(r, 1, rng))}   # {NOTE % 'Lightning Warp'}
    damage_types: {{ lightning: 1.0 }}
    cast_time: 0.5
    effectiveness: 1.0
    mana: {L(col(r, 2, num))}
    visual: dash
    modifiers:
      - {{ stat: spell.base_crit, type: FLAT, value: 11 }}
      - {{ stat: flag.culling, type: FLAT, value: 1 }}
      - {{ stat: flag.lightning_warp, type: FLAT, value: 1 }}
'''
BLOCKS['proc_warp_shocked_ground'] = '''    name: 感電地面
    hidden: true
    type: spell
    tags: [spell, area, lightning, duration]
    mechanic: zone
    zone: { range: 0, radius: 3.0, duration: 8, interval: 0.5, damage: 1, max: 3 }   # 2026-10-26 照 PoE2 Lightning Warp：3 公尺、8 秒（原作不造成傷害，這裡每下 1% 只為了感電）
    damage: [1, 1]
    damage_types: { lightning: 1.0 }
    cast_time: 0.5
    effectiveness: 1.0
    visual: arc
    modifiers:
      - { stat: shock.chance, type: FLAT, value: 100 }
'''

r = table('Staggering_Palm')
BLOCKS['reeling_palm'] = f'''    name: 震脈掌
    description: 一掌打在敵人身上，暈眩累積大幅提高。
    icon: SLIME_BALL
    type: attack
    tier: 3
    tags: [attack, melee, strike, physical]
    weapons: [quarterstaff]
    mechanic: strike
    strike: {{ reach: 4.0, angle: 60, targets: 1 }}   # 2026-10-26 照 PoE2 Staggering Palm（重暈後長棍攻擊多射投射物的增益沒做）
    attack_speed: 0.75
    effectiveness: {L(col(r, 1, pct))}   # {NOTE % 'Staggering Palm'}
    mana: {L(col(r, 2, num))}
    visual: killing_palm
    modifiers:
      - {{ stat: stun.buildup, type: MORE, value: 30 }}
'''

r = table('Shattering_Palm')
BLOCKS['frost_palm'] = f'''    name: 寒魄掌
    description: 一掌打在敵人身上，寒氣在牠周圍炸成一圈冰晶爆炸。物理傷害大部分轉成冰冷。
    icon: PRISMARINE_CRYSTALS   # 2026-10：原本 SNOW_BLOCK（原版右鍵有動作：放方塊、穿戴、吃、使用…會干擾施放）
    type: attack
    tier: 5
    tags: [attack, melee, strike, area, cold]
    weapons: [quarterstaff]
    mechanic: strike
    strike: {{ reach: 4.0, angle: 60, targets: 1 }}   # 2026-10-26 照 PoE2 Shattering Palm：打一下＋冰晶爆炸（原作冰晶要另外打到門檻才碎，這裡打中就炸）
    convert: {{ cold: 0.6 }}
    attack_speed: 0.75
    effectiveness: {L(col(r, 1, pct))}   # {NOTE % 'Shattering Palm 打擊'}
    mana: {L(col(r, 2, num))}
    visual: ice_cone
    modifiers:
      - {{ stat: proc.hit, type: FLAT, value: 100, tags: [proc_shattering_burst] }}
'''
BLOCKS['proc_shattering_burst'] = f'''    name: 碎冰爆
    hidden: true
    type: attack
    tags: [attack, melee, area, cold]
    mechanic: nova
    nova: {{ radius: 1.6 }}   # 2026-10-26 照 PoE2 Shattering Palm 的冰晶爆炸：1.6 公尺
    convert: {{ cold: 1.0 }}
    attack_speed: 1.0
    effectiveness: {L(col(r, 3, pct))}   # {NOTE % 'Shattering Palm 爆炸'}
    visual: frost_nova
'''

r = table('Flame_Wall')
dps = col(r, 1, num)
BLOCKS['flame_wall'] = f'''    name: 焰幕
    description: 在面前升起一道火牆，站在裡面的敵人一直受到火焰傷害、容易點燃。擋走廊用。同時最多三道。
    icon: BLAZE_POWDER   # 2026-10：原本 CAMPFIRE（原版右鍵有動作：放方塊、穿戴、吃、使用…會干擾施放）
    type: spell
    tier: 3
    tags: [spell, area, fire, duration]
    mechanic: zone
    zone: {{ shape: line, length: {L(col(r, 4, num))}, radius: 1.0, duration: 6.4, interval: 0.5, damage: 50, max: 3 }}   # 2026-10-26 照 PoE2 Flame Wall：長度逐級、持續 4.5→7.9 秒（取 20 級 6.4）、最多 3 道
    damage: {D([[v, v] for v in [max(1, round(x)) for x in dps]])}   # {NOTE % 'Flame Wall 每秒火焰傷害'}；每 0.5 秒打一次 50%（穿過火牆的投射物附加火焰沒做）
    damage_types: {{ fire: 1.0 }}
    cast_time: 1.0
    effectiveness: 1.0
    mana: {L(col(r, 2, num))}
    visual: flame_wall
    modifiers:
      - {{ stat: spell.base_crit, type: FLAT, value: 5 }}
      - {{ stat: ignite.chance, type: FLAT, value: 100 }}   # 原作：一定點燃（照逐級的「當作造成 X 傷害」）
'''

r = table('Freezing_Salvo')
BLOCKS['frost_volley'] = f'''    name: 凜霜扇矢
    description: 平常會慢慢蓄冰封印（每 0.75 秒一個，最多 10 個）；射的時候全部解開，每個封印多射一次冰箭。物理傷害大部分轉成冰冷。
    icon: PRISMARINE_SHARD   # 2026-10：原本 PACKED_ICE（原版右鍵有動作：放方塊、穿戴、吃、使用…會干擾施放）
    type: attack
    tier: 5
    tags: [attack, projectile, cold, area]
    weapons: [bow]
    mechanic: projectile
    projectile: {{ speed: 0.375, range: 24, size: 0.4, explode: 1.2 }}   # 2026-10-26 照 PoE2 Freezing Salvo：7.5 公尺／秒、擊中 1.2 公尺（冰碎片引爆沒做）
    convert: {{ cold: 0.8 }}
    attack_speed: 1.0
    effectiveness: {L(col(r, 1, pct))}   # {NOTE % 'Freezing Salvo'}
    mana: {L(col(r, 2, num))}
    visual: ice_shot
    modifiers:   # 原作凍結累積、冰緩效果逐級 30%→108%（取 20 級的 68%）
      - {{ stat: seal.max, type: FLAT, value: 10 }}
      - {{ stat: seal.interval, type: FLAT, value: 0.75 }}
      - {{ stat: seal.repeat, type: FLAT, value: 1 }}
      - {{ stat: freeze.buildup, type: MORE, value: 68 }}
      - {{ stat: chill.effect, type: MORE, value: 68 }}
'''

def main():
    text = SKILLS.read_text(encoding='utf-8')
    for sid, body in BLOCKS.items():
        pat = re.compile(r'(\n  %s:\n)((?:    [^\n]*\n|      [^\n]*\n|\s*\n(?=    ))+)' % re.escape(sid))
        mt = pat.search(text)
        if mt:
            text = text[:mt.start(2)] + body + text[mt.end(2):]
        else:  # 新的隱藏技能放在檔尾
            text = text.rstrip('\n') + '\n\n  %s:\n%s' % (sid, body)
    SKILLS.write_text(text, encoding='utf-8')
    print('ok', list(BLOCKS))

if __name__ == '__main__':
    main()
