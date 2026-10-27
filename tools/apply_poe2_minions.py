"""2026-10-26：召喚物照 PoE2（poe2db 寶石表 + Path of Building 的 Minions.lua 倍率）。
召喚物等級 = 寶石等級 × 2、PoE2 召喚物表（summon.ally: true）、精魂逐級表、上限、持續時間。
2026-10-26 第二輪（能量護盾 es、召喚物的技能與指令、附魂術改精魂召喚、荒原狼伴 ×1.25 與更多傷害、焰術士 1.6 格／70% 轉火焰…）
直接改在 skills.yml，程式在 MinionAbilities／MinionService；這支只認得第一輪之前的寫法，不要再跑。"""
import re, sys
P = 'src/main/resources/data/skills.yml'
s = open(P, encoding='utf-8').read()

W = [60, 44, 33, 27, 25, 25, 23, 23, 23, 22, 22, 22, 21, 21, 21, 21, 20, 20, 20, 20, 19, 19, 19, 19, 19, 18, 18, 18, 18, 18, 17, 17, 17, 17, 17, 16, 16, 16, 16, 16]
SPIRIT = {
    'summon_skeletons': W, 'skeletal_cleric': W,
    'skeletal_brute': [165, 119, 91, 74, 63, 63, 58, 58, 58, 54, 54, 54, 51, 51, 51, 51, 48, 48, 48, 48, 46, 46, 46, 46, 46, 45, 45, 45, 45, 45, 44, 44, 44, 44, 44, 43, 43, 43, 43, 43],
    'skeletal_archers': [90, 65, 50, 41, 37, 37, 35, 35, 35, 33, 33, 33, 31, 31, 31, 31, 30, 30, 30, 30, 29, 29, 29, 29, 29, 28, 28, 28, 28, 28, 27, 27, 27, 27, 27, 26, 26, 26, 26, 26],
    'skeletal_pyromancer': [90, 65, 50, 47, 47, 47, 44, 44, 44, 42, 42, 42, 40, 40, 40, 40, 39, 39, 39, 39, 38, 38, 38, 38, 38, 37, 37, 37, 37, 37, 36, 36, 36, 36, 36, 35, 35, 35, 35, 35],
    'skeletal_stormcaller': [120, 87, 66, 54, 49, 49, 45, 45, 45, 42, 42, 42, 40, 40, 40, 40, 39, 39, 39, 39, 38, 38, 38, 38, 38, 37, 37, 37, 37, 37, 36, 36, 36, 36, 36, 35, 35, 35, 35, 35],
    'skeletal_frostcaller': [120, 87, 66, 54, 49, 49, 46, 46, 46, 43, 43, 43, 41, 41, 41, 41, 39, 39, 39, 39, 38, 38, 38, 38, 38, 37, 37, 37, 37, 37, 36, 36, 36, 36, 36, 35, 35, 35, 35, 35],
}
SPIRIT['skeletal_reaver'] = SPIRIT['skeletal_archers']
UNEARTH = [8, 8, 8, 10, 10, 10, 12, 12, 12, 14, 14, 14, 16, 16, 16, 18, 18, 18, 20, 20, 20, 22, 22, 22, 24, 24, 24, 26, 26, 26, 28, 28, 28, 30, 30, 30, 32, 32, 32, 34]
WOLVES = [3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 9, 9, 9, 9]
SWARM = [6, 6.2, 6.4, 6.6, 6.8, 7, 7.2, 7.4, 7.6, 7.8, 8, 8.2, 8.4, 8.6, 8.8, 9, 9.2, 9.4, 9.6, 9.8, 10, 10.2, 10.4, 10.6, 10.8, 11, 11.2, 11.4, 11.6, 11.8, 11.9, 12, 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7, 12.8]

def lst(v):
    return '[' + ', '.join(str(x) for x in v) + ']'

# 技能 → (PoE2 那隻, max, duration, life, damage, attack_speed(=1/攻擊時間), 其他 summon 欄位, 要拿掉的 summon 欄位)
NOLIMIT = 40   # PoE2 骸骨沒有數量上限，只看精魂（我們的召喚物總數另外有 50 隻的上限）
M = {
    'raise_zombie':        ('Raised Zombie',       10, 20, 0.70, 0.75, 0.8,  '', []),
    'summon_skeletons':    ('Skeletal Warrior',    NOLIMIT, 0, 0.88, 0.70, 1.0, 'armour: 0.5, stats: [{ stat: block.chance, type: FLAT, value: 30 }]', []),
    'raging_spirits':      ('Raging Spirit',       10, 5, 0.25, 0.70, 1.4, 'spread: 0.2', ['resists']),
    'beast_companion':     ('Wolf Companion',      1, 0, 1.10, 0.70, 1.25, 'evasion: 0.3, spread: 0.2', []),
    'skeletal_brute':      ('Skeletal Brute',      NOLIMIT, 0, 1.20, 2.00, round(1/1.75, 3), 'armour: 0.7, stats: [{ stat: stun.buildup, type: MORE, value: 50 }]', []),
    'skeletal_archers':    ('Skeletal Sniper',     NOLIMIT, 0, 0.55, 1.15, round(1/1.5, 3), '', []),
    'skeletal_pyromancer': ('Skeletal Arsonist',   NOLIMIT, 0, 0.55, 0.70, 1.0, 'resists: { fire: 50 }', ['resists']),
    'skeletal_stormcaller':('Skeletal Storm Mage', NOLIMIT, 0, 0.53, 0.65, 1.0, 'resists: { lightning: 50 }', ['resists']),
    'skeletal_frostcaller':('Skeletal Frost Mage', NOLIMIT, 0, 0.53, 0.60, 1.0, 'resists: { cold: 50 }', ['resists']),
    'bone_raise':          ('Bone Construct',      UNEARTH, 15, 0.35, 0.75, round(1/1.06, 3), '', []),
    'skeletal_reaver':     ('Skeletal Reaver',     NOLIMIT, 0, 0.70, 0.46, round(1/0.83, 3), 'armour: 0.2', []),
    'bound_spectre':       ('Spectre（照原本那隻怪；我們沒有每種怪的倍率，用 1 倍）', [2, 3], 0, 1.0, 1.0, 1.0, '', []),
    'skeletal_cleric':     ('Skeletal Cleric',     NOLIMIT, 0, 0.53, 0.65, 1.0, '', []),
    'wolf_pack':           ('Wolf Companion',      WOLVES, 0, 1.10, 0.70, 1.25, 'evasion: 0.3, spread: 0.2', []),
    'ravenous_swarm':      ('Ravenous Swarm',      3, 6, 0.70, 0.84, 1.0, 'interval: 6', []),
}

def block(skill):
    m = re.search(r'\n  ' + skill + r':\n(.*?)(?=\n  [a-z_0-9]+:\n|\n\n)', s, re.S)
    assert m, skill
    return m

def remove_key(body, key):
    # 拿掉 summon 行內的 key: {...} 或 key: value
    body = re.sub(r',\s*' + key + r':\s*\{[^}]*\}', '', body)
    body = re.sub(r',\s*' + key + r':\s*[^,}\n]+', '', body)
    return body

for skill, (src, mx, dur, life, dmg, aps, extra, drop) in M.items():
    m = block(skill)
    b = m.group(1)
    sm = re.search(r'    summon: \{.*?\}\n(?=    [a-z_#])', b + '\n    x', re.S)
    # summon 區塊（可能兩行；裡面有 { } 的子區塊）
    start = b.index('    summon: {')
    depth = 0; i = start + len('    summon: ')
    while True:
        c = b[i]
        if c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: break
        i += 1
    old = b[start:i + 1]
    new = old
    for k in drop:
        new = remove_key(new, k)
    new = re.sub(r'max: (\[[^\]]*\]|\d+)', 'max: ' + (lst(mx) if isinstance(mx, list) else str(mx)), new)
    new = re.sub(r'duration: [\d.]+', 'duration: ' + str(dur), new)
    new = re.sub(r'life: [\d.]+', 'life: ' + str(life), new)
    new = re.sub(r'damage: [\d.]+', 'damage: ' + str(dmg), new)
    new = re.sub(r'attack_speed: [\d.]+', 'attack_speed: ' + str(aps), new)
    new = re.sub(r'(kind: \w+, )', r'\1ally: true, ', new, 1)
    if extra:
        new = new[:-1].rstrip() + ',\n              ' + extra + ' }'
    note = f'    # 2026-10-26 照 PoE2（{src}）：召喚物等級 = 寶石等級 × 2、PoE2 召喚物表 × 生命 {life}、傷害 {dmg}、攻擊時間 {round(1/aps, 2)} 秒\n'
    b2 = b.replace(old, note.lstrip(' ') and note[4:] + '    ' + new if False else new)
    b2 = b2.replace('    ' + new, note + '    ' + new, 1) if not b2.startswith(new) else b2
    # 精魂
    if skill in SPIRIT:
        b2 = re.sub(r'per_minion: \[[^\]]*\]', 'per_minion: ' + lst(SPIRIT[skill]), b2)
    s = s[:m.start(1)] + b2 + s[m.end(1):]

# 灰狼隨：整群固定 60 精魂（PoE2 Wolf Pack）；蟲噬群：固定 30 精魂、每 6 秒一群、最多 3 群、每群 6 → 9.8 秒
s = s.replace("aura: { reserve: 0, per_minion: [60, 25], radius: 8, allies: false }   # 2026-10：照 PoE2 改成每隻保留精魂（1 級 60 → 20 級 25），付得起幾隻叫幾隻",
              "aura: { reserve: 60, radius: 8, allies: false }   # 2026-10-26 照 PoE2 Wolf Pack：整群固定保留 60 精魂，3 → 6 隻（40 級 9 隻）")
m = block('ravenous_swarm'); b = m.group(1)
b2 = b.replace("aura: { reserve: 0, per_minion: [60, 20], radius: 8, allies: false }   # 2026-10：照 PoE2 改成每隻保留精魂（1 級 60 → 20 級 20），付得起幾隻叫幾隻",
               "aura: { reserve: 30, radius: 8, allies: false }   # 2026-10-26 照 PoE2：固定保留 30 精魂")
assert b2 != b
b2 = b2.rstrip('\n') + '\n    level_modifiers:   # 2026-10-26 照 PoE2：每群活 6 → 9.8 秒（40 級 12.8）\n      - { stat: skill.duration, type: INCREASED, value: ' + lst([round((d / 6 - 1) * 100, 2) for d in SWARM]) + ' }'
s = s[:m.start(1)] + b2 + s[m.end(1):]

# 說明
D = {
 'raise_zombie': ('同時最多八到十隻', '同時最多十隻'),
 'summon_skeletons': ('動作快、打得痛，但不耐打。同時最多六到八個。', '有 30% 機率格擋。數量沒有上限，只看精魂。'),
 'raging_spirits': ('同時最多八到十個', '同時最多十個'),
 'skeletal_brute': ('同時最多兩個。', '數量沒有上限，只看精魂。'),
 'skeletal_archers': ('同時最多四到六個。', '數量沒有上限，只看精魂。'),
 'skeletal_pyromancer': ('同時最多兩到三個。', '數量沒有上限，只看精魂。'),
 'skeletal_stormcaller': ('同時最多兩到三個。', '數量沒有上限，只看精魂。'),
 'skeletal_frostcaller': ('同時最多兩到三個。', '數量沒有上限，只看精魂。'),
 'skeletal_reaver': ('同時最多四到六個。', '數量沒有上限，只看精魂。'),
 'skeletal_cleric': ('同時最多一到兩個。', '數量沒有上限，只看精魂。'),
 'bone_raise': ('二十秒後散掉。可以一直放。', '十五秒後散掉。可以一直放，同時最多八到二十個。'),
 'wolf_pack': ('有三隻狼一直跟著你，跑得快、咬得兇。死掉的 8 秒後自己補回來。每一隻都要保留精魂（技能等級越高每隻越少），精魂付得起幾隻就叫幾隻。',
               '有一群狼一直跟著你（三隻，技能等級越高越多，20 級六隻），跑得快、咬得兇。死掉的 8 秒後自己補回來。整群保留 60 精魂。'),
 'ravenous_swarm': ('一群蟲子一直跟著你，飛去咬附近的敵人並讓牠們中毒。死掉的 8 秒後自己補回來。每一隻都要保留精魂（技能等級越高每隻越少），精魂付得起幾隻就叫幾隻。',
                    '每 6 秒放出一群蟲子跟著你，飛去咬附近的敵人並讓牠們中毒。每群活 6 秒（技能等級越高越久，20 級 9.8 秒），同時最多三群。保留 30 精魂。'),
}
for skill, (a, b_) in D.items():
    m = block(skill); body = m.group(1)
    assert a in body, (skill, a)
    s = s[:m.start(1)] + body.replace(a, b_, 1) + s[m.end(1):]
open(P, 'w', encoding='utf-8').write(s)
print('ok')
