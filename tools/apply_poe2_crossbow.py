#!/usr/bin/env python3
"""2026-10-26：弩技能的原作機制（poe2db；tools/poe2_skills/poe2_xbow_*.txt）。改 skills.yml 裡 8 個弩技能的機制欄位，
傷害效益、魔耗表不動（之前已經照 poe2db）。新增 4 個隱藏技能（引爆、冰晶爆炸、脈衝、雷束、碎裂錐形）。可以重跑。"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / 'src/main/resources/data/skills.yml'
SRC = ROOT / 'tools/poe2_skills'

def rows(slug):
    out = []
    for line in (SRC / ('poe2_xbow_%s.txt' % slug)).read_text(encoding='utf-8').splitlines():
        if re.match(r'^\d+\t', line):
            out.append(line.split('\t'))
    assert len(out) == 40, slug
    return out

def col(slug, i):
    return [int(float(r[i])) for r in rows(slug)]

def L(v):
    return '[' + ', '.join(str(x) for x in v) + ']'

def block(text, sid):
    m = re.search(r'(\n  %s:\n)((?:    [^\n]*\n|\s*\n(?=    ))+)' % re.escape(sid), text)
    assert m, sid
    return m

def set_key(body, key, value, comment=''):
    """把 `    key: …` 那一行換掉（沒有就加在 mana 前面）；value=None 就刪掉。"""
    line = '    %s: %s%s\n' % (key, value, ('   # ' + comment) if comment else '') if value is not None else ''
    pat = re.compile(r'^    %s:[^\n]*\n(?:    # （更早）[^\n]*\n)*' % re.escape(key), re.M)
    if pat.search(body):
        return pat.sub(lambda _: line, body, count=1)
    if value is None:
        return body
    return re.sub(r'^(    mana:)', line.replace('\\', '\\\\') + r'\1', body, count=1, flags=re.M)

def set_mods(body, mods, level_mods=None):
    body = re.sub(r'^    modifiers:[^\n]*\n(?:      [^\n]*\n)*', '', body, flags=re.M)
    body = re.sub(r'^    level_modifiers:[^\n]*\n(?:      [^\n]*\n)*', '', body, flags=re.M)
    out = body.rstrip('\n') + '\n'
    if mods:
        out += '    modifiers:\n' + ''.join('      - %s\n' % m for m in mods)
    if level_mods:
        out += '    level_modifiers:   # 2026-10-26 照 PoE2 逐級\n' + ''.join('      - %s\n' % m for m in level_mods)
    return out

NOTE = '2026-10-26 弩照 PoE2（poe2db %s）'
EDITS = {}

def edit(sid):
    def deco(f):
        EDITS[sid] = f
        return f
    return deco

@edit('stormblast_bolts')
def _(b):
    b = re.sub(r'^    # PoE2 Stormblast Bolts：[^\n]*\n', '', b, flags=re.M)
    b = set_key(b, 'description', '往準心指的地方射出三支帶電的弩彈，落地時電到一小圈，之後插在地上 10 秒；被你別的技能打到就炸開一圈閃電（同時最多 30 支）。')
    b = set_key(b, 'impact', '{ range: 24, delay: 0.15, radius: 0.8 }', NOTE % 'Stormblast Bolts：落地 0.8 公尺')
    b = set_key(b, 'lodge', '{ skill: proc_stormblast_burst, count: 3, scatter: 1.5, duration: 10, max: 30 }', '被引爆時 1.6 公尺')
    return set_mods(b, [])

@edit('frost_bolt_round')
def _(b):
    b = set_key(b, 'description', '快速往準心連射冰彈，每一發落地留下一顆冰晶；冰晶 1.5 秒後武裝，敵人踩到就炸開一圈冰冷（武裝越久越痛，2 秒後更多 400%），同時最多 16 顆。')
    b = set_key(b, 'tags', '[attack, projectile, area, cold, duration]')
    b = set_key(b, 'mechanic', 'impact')
    b = set_key(b, 'projectile', None)
    b = set_key(b, 'impact', '{ range: 22, delay: 0.15, radius: 0.6 }', NOTE % 'Ice Shards：彈打在地上')
    b = set_key(b, 'lodge', '{ skill: proc_ice_shard_burst, count: 1, duration: 20, max: 16, proximity: true, arm: 1.5, trigger: 1.2, ramp_more: 400, ramp_seconds: 2 }')
    b = set_key(b, 'attack_speed', '5.5', NOTE % 'Ice Shards 550%')
    return set_mods(b, [], ['{ stat: chill.effect, type: MORE, value: %s }' % L(col('Ice_Shards', 1))])

@edit('storm_bolts')
def _(b):
    b = set_key(b, 'description', '快速連射帶電的弩矢；打中有閃電異常（感電、電擊）的敵人時炸出一圈脈衝。自己不會造成感電。')
    b = set_key(b, 'attack_speed', '4', NOTE % 'Shockburst Rounds 400%')
    return set_mods(b, ['{ stat: crossbow.reload_speed, type: MORE, value: -20 }',
                        '{ stat: shock.chance, type: MORE, value: -100 }',
                        '{ stat: electrocute.chance, type: MORE, value: -100 }',
                        '{ stat: proc.vs_shocked, type: FLAT, value: 100, tags: [proc_shockburst_pulse] }',
                        '{ stat: proc.vs_electrocuted, type: FLAT, value: 100, tags: [proc_shockburst_pulse] }'])

@edit('hail_rounds')
def _(b):
    b = set_key(b, 'description', '平常慢慢存冰雹彈（最多 32～78 發，隨技能等級）；放的時候把存著的全部射出去，落在準心周圍 4 格內，每一發炸開一小圈，容易凍結。')
    b = set_key(b, 'impact', '{ range: 22, delay: 0.3, radius: 1.0 }', NOTE % 'Hailstorm Rounds：每一發 1 公尺')
    b = set_key(b, 'rain', '{ area: 4, radius: 1.0, seconds: 1.0, interval: 0.4, store: %s }' % L(col('Hailstorm_Rounds', 1)),
                '存彈速度＝裝填時間 ÷ 2.5（原作 150% 更多裝填速度）')
    b = set_key(b, 'clip', None)
    b = set_key(b, 'attack_speed', '0.55', NOTE % 'Hailstorm Rounds 55%')
    return set_mods(b, ['{ stat: crossbow.reload_speed, type: MORE, value: 150 }   # 原作 150% 更多裝填速度'],
                    ['{ stat: freeze.buildup, type: MORE, value: %s }' % L(col('Hailstorm_Rounds', 2)),
                            '{ stat: chill.effect, type: MORE, value: %s }' % L(col('Hailstorm_Rounds', 3))])

@edit('galvanic_bolts')
def _(b):
    b = set_key(b, 'description', '弩一次噴出一把扇形的帶電碎片；碎片打中敵人時放出一道雷束，連鎖到附近另一個敵人。')
    b = set_key(b, 'tags', '[attack, projectile, lightning, merging, chain]')
    b = set_key(b, 'projectile', '{ arrow: true, count: 8, spread: 30, speed: 2.5, range: 16 }', NOTE % 'Galvanic Shards：8 片、50 公尺／秒')
    return set_mods(b, ['{ stat: proc.hit, type: FLAT, value: 100, tags: [proc_galvanic_beam] }'])

@edit('permafrost_bolts')
def _(b):
    b = re.sub(r'^    # 2026-10 加強清群[^\n]*\n', '', b, flags=re.M)
    b = set_key(b, 'description', '一次扇形射出一排貫穿的冰霜碎片（穿透 2～5 個，隨技能等級），最後打到的敵人後面再碎成一小片錐形；凍結累積、冰緩效果大幅提高。')
    b = set_key(b, 'projectile', '{ arrow: true, count: 10, spread: 24, speed: 1.4, range: 36 }', NOTE % 'Permafrost Bolts：10 片、28 公尺／秒；穿透逐級')
    return set_mods(b, ['{ stat: proc.hit, type: FLAT, value: 100, tags: [proc_permafrost_shatter] }'],
                    ['{ stat: skill.pierce, type: FLAT, value: %s }' % L(col('Permafrost_Bolts', 2)),
                     '{ stat: freeze.buildup, type: MORE, value: %s }' % L(col('Permafrost_Bolts', 1)),
                     '{ stat: chill.effect, type: MORE, value: %s }' % L(col('Permafrost_Bolts', 3))])

@edit('incendiary_shot')
def _(b):
    b = set_key(b, 'description', '弩噴出一把扇形的火焰碎片，打中的敵人後面再碎成一小片錐形；點燃很容易、燒得很快。')
    b = set_key(b, 'projectile', '{ arrow: true, count: 10, spread: 30, speed: 1.71, range: 14 }', NOTE % 'Incendiary Shot：10 片、34.2 公尺／秒')
    return set_mods(b, ['{ stat: ignite.chance, type: FLAT, value: 25 }',
                        '{ stat: ignite.chance, type: MORE, value: 300 }   # 原作 300% 更多易燃',
                        '{ stat: ignite.duration, type: MORE, value: -66.7 }   # 原作點燃傷害快 200%：同樣的總傷害、時間剩三分之一（這裡用更短代替）',
                        '{ stat: ignite.magnitude, type: MORE, value: 200 }',
                        '{ stat: proc.hit, type: FLAT, value: 100, tags: [proc_incendiary_shatter] }'])

@edit('armour_piercing_bolt')
def _(b):
    b = set_key(b, 'description', '快速連射沉重的弩矢，每一發擊中都破掉固定量的護甲（隨技能等級大幅提高）。')
    b = set_key(b, 'clip', L(col('Armour_Piercing_Rounds', 1)), NOTE % 'Armour Piercing Rounds 12～31 發')
    b = set_key(b, 'attack_speed', '4.25', NOTE % 'Armour Piercing Rounds 425%')
    return set_mods(b, [], ['{ stat: armour.break_flat, type: FLAT, value: %s }' % L(col('Armour_Piercing_Rounds', 2))])

HIDDEN = {
    'proc_stormblast_burst': '''    name: 伏霆爆
    hidden: true
    type: attack
    tags: [attack, area, lightning]
    mechanic: nova
    nova: { radius: 1.6 }   # 2026-10-26 照 PoE2 Stormblast Bolts 的爆炸：1.6 公尺、115%→470%
    convert: { lightning: 1.0 }
    attack_speed: 1.0
    effectiveness: [1.15, 4.70]
    visual: lightning_arrow
''',
    'proc_ice_shard_burst': '''    name: 冰晶迸
    hidden: true
    type: attack
    tags: [attack, area, cold]
    mechanic: nova
    nova: { radius: 2.5 }   # 2026-10-26 照 PoE2 Ice Shards 的冰晶：2.5 公尺、20%→82%
    convert: { cold: 1.0 }
    attack_speed: 1.0
    effectiveness: [0.20, 0.82]
    visual: frost_nova
''',
    'proc_shockburst_pulse': '''    name: 雷暴脈衝
    hidden: true
    type: attack
    tags: [attack, area, lightning]
    mechanic: nova
    nova: { radius: 1.8 }   # 2026-10-26 照 PoE2 Shockburst Rounds 的脈衝：1.8 公尺、30%→109%
    convert: { lightning: 1.0 }
    attack_speed: 1.0
    effectiveness: [0.30, 1.09]
    visual: lightning_arrow
''',
    'proc_galvanic_beam': '''    name: 雷屑光束
    hidden: true
    type: attack
    tags: [attack, chain, lightning]
    mechanic: chain
    chain: { range: 8, chains: 1, chain_range: 6 }   # 2026-10-26 照 PoE2 Galvanic Shards 的雷束：連鎖 1 次、75%→272%
    convert: { lightning: 1.0 }
    attack_speed: 1.0
    effectiveness: [0.75, 2.72]
    visual: arc
''',
    'proc_permafrost_shatter': '''    name: 凍土碎裂
    hidden: true
    type: attack
    tags: [attack, area, cold]
    mechanic: cone
    cone: { length: 2.7, angle: 40 }   # 2026-10-26 照 PoE2 Permafrost Bolts：打到的敵人後面碎成 2.7 公尺的錐形（同一發的傷害）
    convert: { cold: 0.8 }
    attack_speed: 1.0
    effectiveness: 1.0
    parent_damage: 1.0
    visual: ice_cone
''',
    'proc_incendiary_shatter': '''    name: 燃焰碎裂
    hidden: true
    type: attack
    tags: [attack, area, fire]
    mechanic: cone
    cone: { length: 1.9, angle: 60 }   # 2026-10-26 照 PoE2 Incendiary Shot：打到的敵人後面碎成 1.9 公尺的錐形（同一發的傷害）
    convert: { fire: 0.8 }
    attack_speed: 1.0
    effectiveness: 1.0
    parent_damage: 1.0
    visual: explosive_bolt
''',
}

def main():
    text = SKILLS.read_text(encoding='utf-8')
    for sid, f in EDITS.items():
        m = block(text, sid)
        text = text[:m.start(2)] + f(m.group(2)) + text[m.end(2):]
    for sid, body in HIDDEN.items():
        m = re.search(r'(\n  %s:\n)((?:    [^\n]*\n|\s*\n(?=    ))+)' % re.escape(sid), text)
        if m:
            text = text[:m.start(2)] + body + text[m.end(2):]
        else:
            text = text.rstrip('\n') + '\n\n  %s:\n%s' % (sid, body)
    SKILLS.write_text(text, encoding='utf-8')
    print('ok')

if __name__ == '__main__':
    main()
