"""2026-10-26：怪物、Boss 照 PoE2（poe2db／Path of Building；資料 tools/poe2_monster_data.txt）。
Boss 的 life／damage 是「比同等級傳奇怪」的倍率；傳奇怪已經照 PoE2 是普通怪的 ×29 生命、×0.65 傷害（monsters.yml）。
照階級對原作 Boss（poe2db 的 Life%／Damage%），同一階裡保留原本各隻之間的比例（新 = 原作 × 舊 ÷ 這一階舊的基準）：
  地圖 Boss、劫盜據點、生活島主、區域 Boss → 原作地圖 Boss（Morwyn、Vorrik）250% 生命、250% 傷害
  終局 Boss、潮汐統帥                       → 原作 Trialmaster／Zarokh 750% 生命、250% 傷害
  極境（終局再乘）                          → 原作 Arbiter of Ash 990%／450%：生命 ×1.32、傷害 ×1.8
  巔峰、事件巔峰、金庫之主、淵鯨、渦齒鯊王、開天者、南瓜王 → Arbiter of Ash 990% 生命、450% 傷害；元素抗性再 +20（共 50%）
階級倍率（地圖怪物 monster_tier、地圖 Boss boss_tier_life、終局 tier_life／tier_life_table）原作沒有（MonsterMapDifficulty 全部 1），拿掉。"""
import re
R = 'src/main/resources/data/'
TIER = {  # 階級 → (原作生命、原作傷害、舊生命基準、舊傷害基準、說明)
    'map':      (2.5, 2.5, 7.5, 1.6, '原作地圖 Boss 250%／250%'),
    'heist':    (2.5, 2.5, 51, 1.4, '原作地圖 Boss 250%／250%'),
    'zone':     (2.5, 2.5, 3.4, 1.3, '原作地圖 Boss 250%／250%'),
    'captain':  (2.5, 2.5, 8, 1.4, '原作地圖 Boss 250%／250%'),
    'final':    (7.5, 2.5, 19.1, 2.15, '原作 Trialmaster／Zarokh 750%／250%'),
    'uber':     (9.9 / 7.5, 4.5 / 2.5, 31.7, 1.5, '原作 Arbiter of Ash 990%／450%，比終局 ×1.32／×1.8'),
    'pinnacle': (9.9, 4.5, 1222.3, 3.2, '原作 Arbiter of Ash 990%／450%'),
}

def num(v):
    v = round(v, 3)
    return ('%d' % v) if v == int(v) else ('%g' % v)

def scaled(tier, key, old):
    life, dmg, ref_life, ref_dmg, _ = TIER[tier]
    return life * old / ref_life if key == 'life' else dmg * old / ref_dmg

def fix_line(line, tier, key):
    m = re.match(r'^(\s*' + key + r': )([\d.]+)(.*)$', line)
    old = float(m.group(2))
    return f'{m.group(1)}{num(scaled(tier, key, old))}   # 2026-10-26 照 PoE2（{TIER[tier][4]}；這次之前 {num(old)}）'

def fix_inline(line, tier):
    # captain: { ..., life: 8, damage: 1.4, ... }
    for key in ('life', 'damage'):
        line = re.sub(r'(\b' + key + r': )([\d.]+)', lambda m: m.group(1) + num(scaled(tier, key, float(m.group(2)))), line, 1)
    return line + f'   # 2026-10-26 照 PoE2（{TIER[tier][4]}）'

def add_mod(line, mod):
    return re.sub(r'mods: \[([^\]]*)\]', lambda m: 'mods: [' + ', '.join([x.strip() for x in m.group(1).split(',') if x.strip() and x.strip() != 'resistant'] + [mod]) + ']', line, 1)

def drop_resistant(line):
    return re.sub(r'mods: \[([^\]]*)\]', lambda m: 'mods: [' + ', '.join(x.strip() for x in m.group(1).split(',') if x.strip() and x.strip() != 'resistant') + ']', line, 1)

counts = {}
def bump(k):
    counts[k] = counts.get(k, 0) + 1

# ---------------- maps.yml ----------------
p = R + 'maps.yml'; s = open(p, encoding='utf-8').read()
s = s.replace('boss_tier_life: { from: 10, max: 20 }', 'boss_tier_life: { from: 10, max: 1 }   # 2026-10-26 照 PoE2：地圖 Boss 不照階級再乘（原作 MonsterMapBossDifficulty 全部 1）；這次之前 max 20')
s = s.replace('monster_tier: { from: 5, life: 2.2, damage: 1.3 }', 'monster_tier: { from: 5, life: 1.0, damage: 1.0 }   # 2026-10-26 照 PoE2：只看怪物等級（原作 MonsterMapDifficulty 全部 1）；這次之前 2.2／1.3')
s = re.sub(r'tier_life: \{ from: 5, max: 11.7 \}[^\n]*', 'tier_life: { from: 5, max: 1 }     # 2026-10-26 照 PoE2：終局、極境、劫盜終局不照階級再乘；這次之前 max 11.7', s)
s = re.sub(r'\n  tier_life_table: \[[^\]]*\]', '\n  # tier_life_table：2026-10-26 照 PoE2 拿掉（這次之前 T11 起 10.51 → T16 195）', s)
lines = s.split('\n')
# 先找出 finals 底下每一張圖有沒有 pinnacle
section = None; current = None; has_pinnacle = {}
for line in lines:
    m = re.match(r'^([a-z_]+):', line)
    if m:
        section = m.group(1); current = None; continue
    m = re.match(r'^  ([a-z_0-9]+):\s*$', line)
    if m and section in ('maps', 'finals'):
        current = m.group(1); has_pinnacle[current] = False; continue
    if section == 'finals' and current and re.match(r'^    pinnacle:', line):
        has_pinnacle[current] = True
section = None; current = None; block = None
for i, line in enumerate(lines):
    m = re.match(r'^([a-z_]+):', line)
    if m:
        section = m.group(1); current = None; block = None; continue
    if section == 'final':
        if re.match(r'^  boss:\s*$', line): block = 'final'; continue
        if re.match(r'^  uber:\s*$', line): block = 'uber'; continue
        if re.match(r'^  [a-z_]+:', line): block = None; continue
        ind = '    '
    elif section in ('maps', 'finals'):
        m = re.match(r'^  ([a-z_0-9]+):\s*$', line)
        if m: current = m.group(1); block = None; continue
        if re.match(r'^    boss:\s*$', line):
            block = 'map' if section == 'maps' else ('pinnacle' if has_pinnacle.get(current) else 'final'); continue
        if re.match(r'^    uber:\s*$', line): block = 'uber'; continue
        if re.match(r'^    [a-z_]+:', line): block = None; continue
        ind = '      '
    else:
        continue
    if block is None:
        continue
    for key in ('life', 'damage'):
        if re.match('^' + ind + key + r': [\d.]', line):
            lines[i] = fix_line(line, block, key); bump(('maps', block, key))
    if re.match('^' + ind + r'mods: \[', line):
        lines[i] = add_mod(line, 'pinnacle_resist') if block == 'pinnacle' else drop_resistant(line)
    # 極境：加上 50% 抗性（沒有 mods 就在 life 那一行後面加）
    if block == 'uber' and re.match('^' + ind + r'life: ', lines[i]) and not any(re.match('^' + ind + r'mods:', l) for l in lines[i + 1:i + 6]):
        lines[i] = lines[i] + '\n' + ind + 'mods: [pinnacle_resist]   # 2026-10-26 照 PoE2：極境元素抗性 50%（一般 Boss 30% + 20）'
s = '\n'.join(lines)
open(p, 'w', encoding='utf-8').write(s)

# ---------------- heist.yml ----------------
p = R + 'heist.yml'; s = open(p, encoding='utf-8').read()
lines = s.split('\n')
for i, line in enumerate(lines):
    if re.match(r'^      (life|damage): [\d.]', line):
        key = line.split(':')[0].strip(); lines[i] = fix_line(line, 'heist', key); bump(('heist', 'site', key))
    elif re.match(r'^    (life|damage): [\d.]', line):
        key = line.split(':')[0].strip(); lines[i] = fix_line(line, 'pinnacle', key); bump(('heist', 'finale', key))
    elif re.match(r'^    mods: \[armoured, resistant\]', line):
        lines[i] = add_mod(line, 'pinnacle_resist')
    elif re.match(r'^      mods: \[', line):
        lines[i] = drop_resistant(line)
open(p, 'w', encoding='utf-8').write('\n'.join(lines))

# ---------------- life.yml ----------------
p = R + 'life.yml'; s = open(p, encoding='utf-8').read()
lines = s.split('\n')
for i, line in enumerate(lines):
    if 'captain: {' in line:
        lines[i] = fix_inline(line, 'captain'); bump(('life', 'captain'))
    elif re.match(r'^  (life|damage): [\d.]', line) and ('605.5' in line or '3.3' == line.split(':')[1].strip() or '1195.7' in line or line.strip() == 'damage: 2.9'):
        key = line.split(':')[0].strip(); lines[i] = fix_line(line, 'pinnacle' if key == 'life' else 'pinnacle', key)
        # 渦齒鯊王舊的是終局 × 極境（19.1 × 31.7 = 605.5、2.2 × 1.5 = 3.3）：照巔峰階級算，基準用自己
        if '605.5' in line or line.strip() == 'damage: 3.3':
            old = float(line.split(':')[1].split('#')[0])
            lines[i] = '  ' + key + ': ' + ('9.9' if key == 'life' else '4.5') + f'   # 2026-10-26 照 PoE2（{TIER["pinnacle"][4]}；這次之前 {num(old)}）'
        bump(('life', 'boss', key))
    elif re.match(r'^  mods: \[', line):
        lines[i] = add_mod(line, 'pinnacle_resist')
open(p, 'w', encoding='utf-8').write('\n'.join(lines))

# ---------------- world_events.yml、festival.yml ----------------
for f, spec in (('world_events.yml', [('7333', 'pinnacle', 9.9), ('967', 'final', 7.5)]), ('festival.yml', [('5233', 'pinnacle', 9.9)])):
    p = R + f; s = open(p, encoding='utf-8').read()
    lines = s.split('\n')
    for i, line in enumerate(lines):
        for old, tier, value in spec:
            if re.match(r'^\s*life: ' + old + r'\b', line):
                ind = re.match(r'^(\s*)', line).group(1)
                lines[i] = f'{ind}life: {num(value)}   # 2026-10-26 照 PoE2（{TIER[tier][4]}，一個人的份；這次之前 {old}）'
                # 下一個 damage
                for j in range(i + 1, i + 12):
                    dm = re.match(r'^(\s*)damage: ([\d.]+)(.*)$', lines[j])
                    if dm:
                        newd = 4.5 if tier == 'pinnacle' else 2.5
                        lines[j] = f'{dm.group(1)}damage: {num(newd)}   # 2026-10-26 照 PoE2（這次之前 {dm.group(2)}）'
                        break
                bump((f, tier))
    open(p, 'w', encoding='utf-8').write('\n'.join(lines))

# ---------------- zones.yml（區域 Boss）----------------
p = R + 'zones.yml'; s = open(p, encoding='utf-8').read()
lines = s.split('\n')
for i, line in enumerate(lines):
    if re.match(r'^      (life|damage): [\d.]', line):
        key = line.split(':')[0].strip(); lines[i] = fix_line(line, 'zone', key); bump(('zones', key))
open(p, 'w', encoding='utf-8').write('\n'.join(lines))
for k, v in sorted(counts.items(), key=str):
    print(k, v)
