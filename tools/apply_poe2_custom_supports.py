#!/usr/bin/env python3
"""2026-11-01 照站長「28 顆以原作某顆為基準自己改的一般輔助，改成跟原作一樣」。
來源：poe2db.tw（Fire Attunement、Feeding Frenzy I/II、Meat Shield I、Rapid Attacks II、Minion Instability、
Magnified Area II、Deep Cuts II、Payload 的寶石頁）。階級照 apply_poe2_supports.py 的換算（原作 1 → 1、2–3 → 2、4–5 → 3）。

- 原作有同一顆、我們還沒有的 8 顆：改成原作（代號、名稱不變）。
- 原作有、我們已經有同一顆的 6 顆，加上兇猛召喚（Feeding Frenzy I，站長刪掉、併進 II）：併入那一顆（merged；玩家的寶石直接換過去）。
- 原作沒有的 13 顆：拿掉（retired；換成同階的未切割輔助寶石）。
可以重跑（已經改過的不動）。用法：python3 tools/apply_poe2_custom_supports.py
"""
import re
from pathlib import Path

F = Path(__file__).resolve().parent.parent / 'src/main/resources/data/supports.yml'

NOTE = '    # 2026-11-01 照 PoE2 {en}（poe2db：第 {t} 階、消耗 {cost}%）；這次之前是自己照原作基準改的（{old}）\n'

CONVERT = {
    'added_fire': dict(en='Fire Attunement', t=1, cost=100, old='只算物理那份', body='''    name: 引火
    description: 被輔助的技能獲得等同於傷害 25% 的額外火焰傷害，但造成的冰冷、閃電傷害更少 50%。
    icon: BLAZE_POWDER
    tier: 1
    requires: [attack, spell]
    mana_multiplier: 1.0
{note}    modifiers:
      - { stat: damage.extra.fire, type: FLAT, value: 25 }
      - { stat: damage, type: MORE, value: -50, tags: [cold] }
      - { stat: damage, type: MORE, value: -50, tags: [lightning] }
'''),
    'bloodthirst_summon': dict(en='Feeding Frenzy II', t=4, cost=100, old='多了攻擊速度、用最大生命更少 15% 代替受到更多傷害', body='''    name: 嗜殺召喚
    description: 被輔助的技能召出來的召喚物造成更多 30% 傷害，但受到的傷害更多 15%。只有召喚技能能掛。
    icon: BLAZE_POWDER
    tier: 3
    requires: [minion]
    mana_multiplier: 1.0
{note}    modifiers:
      - { stat: damage, type: MORE, value: 30, tags: [minion] }
      - { stat: damage.taken, type: FLAT, value: 15, tags: [minion] }
'''),
    'minion_life': dict(en='Meat Shield I', t=1, cost=100, old='生命更多 40%、每秒回復 1%、傷害更少 20%', body='''    name: 堅韌召喚
    description: 被輔助的技能召出來的召喚物受到的傷害更少 35%，但造成的傷害更少 35%。只有召喚技能能掛。
    icon: BONE
    tier: 1
    requires: [minion]
    mana_multiplier: 1.0
{note}    modifiers:
      - { stat: damage.taken, type: FLAT, value: -35, tags: [minion] }
      - { stat: damage, type: MORE, value: -35, tags: [minion] }
'''),
    'minion_speed': dict(en='Rapid Attacks II', t=4, cost=100, old='召喚物攻擊速度增加 20%、移動速度增加 25%', body='''    name: 迅捷召喚
    description: 被輔助的攻擊打得更快（攻擊速度增加 25%）。
    icon: SUGAR
    tier: 3
    requires: [attack]
    excludes: [herald]
    mana_multiplier: 1.0
{note}    modifiers:
      - { stat: attack.speed, type: INCREASED, value: 25 }
'''),
    'grudge_cinder': dict(en='Minion Instability', t=2, cost=100, old='圖騰被打時反擊、照圖騰生命算的火彈', body='''    name: 怨燼
    description: 被輔助的技能召出來的召喚物被打到低血（35% 以下）時炸開，對周圍的敵人造成牠最大生命 15% 的火焰傷害（一隻只炸一次）。只有召喚技能能掛。
    icon: MAGMA_CREAM
    tier: 2
    requires: [minion]
    mana_multiplier: 1.0
{note}    modifiers:
      - { stat: minion.instability, type: FLAT, value: 15, tags: [minion] }
    notes: ["原作頁面沒寫爆炸半徑，暫定 3 格"]
'''),
    'quake_zone': dict(en='Magnified Area II', t=3, cost=130, old='範圍增加 30% 再加範圍傷害更多 10%', body='''    name: 震域
    description: 被輔助的技能範圍增加 45%。
    icon: SLIME_BALL
    tier: 2
    requires: [area]
    mana_multiplier: 1.3
{note}    modifiers:
      - { stat: skill.area, type: INCREASED, value: 45 }
'''),
    'blood_collapse': dict(en='Deep Cuts II', t=5, cost=100, old='流血更多 50%、持續增加 30%、流血機率 +15%、擊中更少 25%', body='''    name: 血崩
    description: 被輔助的技能造成的流血強度更多 100%，但擊中傷害更少 30%（流血照沒減之前的擊中算；要配會讓敵人流血的攻擊）。
    icon: NETHER_BRICK
    tier: 3
    requires: [attack]
    mana_multiplier: 1.0
{note}    modifiers:
      - { stat: bleed.magnitude, type: MORE, value: 100 }
      - { stat: damage.hit, type: MORE, value: -30 }
'''),
    'extra_shells': dict(en='Payload', t=4, cost=100, old='多丟一顆、傷害更少 20%、冷卻增加 50%', body='''    name: 增彈
    description: 被輔助的技能丟出的榴彈有 50% 機率再爆一次，但冷卻回復速度減少 70%。
    icon: GUNPOWDER
    tier: 3
    requires: [grenade]
    mana_multiplier: 1.0
{note}    modifiers:
      - { stat: grenade.twice_chance, type: FLAT, value: 50 }
      - { stat: cooldown.recovery, type: INCREASED, value: -70 }
'''),
}

# 原作有、我們已經有同一顆：併入（舊代號: (併入的, 舊名稱, 原作)）
MERGE = {
    'savage_flare': ('keen_point', '暴烈', 'Pinpoint Critical'),
    'critical_damage': ('blast_peak', '暴傷', 'Supercritical'),
    'added_chaos': ('decay_infuse', '蝕化', 'Chaos Attunement'),
    'curse_extend': ('broad_hex', '咒延', 'Ritualistic Curse'),
    'drive_servants': ('quick_strike', '驅役', 'Rapid Attacks I'),
    'quick_load_gear': ('quick_strike', '速裝機括', 'Rapid Attacks I'),
    'minion_damage': ('bloodthirst_summon', '兇猛召喚', 'Feeding Frenzy（站長：刪掉 I，只留 II）'),
}

# 原作沒有：拿掉（舊代號: 階級）
RETIRE = {
    'echo_remnant': (1, '餘韻'), 'burst': (2, '迸發'), 'sweep_strike': (2, '掃擊'), 'weighty_arrow': (2, '重矢'),
    'post_wrath': (2, '樁怒'), 'sinking_blow': (2, '沉擊'), 'far_howl': (1, '遠嘯'), 'curse_seize': (2, '趁咒'),
    'vs_chilled': (2, '趁寒'), 'vs_ignited': (2, '趁焰'), 'vs_shocked': (2, '趁雷'), 'vs_poisoned': (2, '趁毒'), 'vs_bleeding': (2, '趁血'),
}


def blocks(text):
    """supports: 底下每一顆的 (代號, 開始行, 結束行)；結束行是下一顆（或檔尾）。"""
    lines = text.split('\n')
    start = lines.index('supports:')
    found = []
    for i in range(start + 1, len(lines)):
        m = re.match(r'^  ([a-z_0-9]+):', lines[i])
        if m:
            found.append((m.group(1), i))
    out = []
    for n, (key, i) in enumerate(found):
        end = found[n + 1][1] if n + 1 < len(found) else len(lines)
        # 往回把下一顆前面的註解、空行留給下一顆
        j = end
        while j - 1 > i and (lines[j - 1].strip() == '' or lines[j - 1].startswith('  #')):
            j -= 1
        out.append((key, i, j))
    return lines, out


def main():
    text = F.read_text(encoding='utf-8')
    lines, found = blocks(text)
    keyed = {k: (i, j) for k, i, j in found}
    drop = set(MERGE) | set(RETIRE)
    new = []
    i = 0
    changed = []
    while i < len(lines):
        hit = next(((k, a, b) for k, (a, b) in keyed.items() if a == i), None)
        if hit is None:
            new.append(lines[i])
            i += 1
            continue
        key, a, b = hit
        if key in drop:
            changed.append(key)
            i = b
            continue
        if key in CONVERT and '2026-11-01 照 PoE2' not in '\n'.join(lines[a:b]):
            spec = CONVERT[key]
            header = lines[a]
            body = spec['body'].replace('{note}', NOTE.format(**spec))
            new.append(header)
            new.extend(body.rstrip('\n').split('\n'))
            changed.append(key)
            i = b
            continue
        new.extend(lines[a:b])
        i = b
    text = '\n'.join(new)
    # retired、merged 的表
    ret = ''.join('  %s: %d   # 2026-11-01 照站長：%s（原作沒有，換成同階的未切割輔助寶石）\n' % (k, t, n)
                  for k, (t, n) in RETIRE.items() if re.search(r'^  %s:' % k, text.split('\nmerged:')[0], re.M) is None)
    if ret:
        text = text.replace('\nmerged:', '\n  # 2026-11-01 照站長「以原作某顆為基準自己改的輔助，改成跟原作一樣」：原作沒有的 13 顆拿掉\n' + ret.rstrip('\n') + '\n\n# placeholder\nmerged:', 1)
        text = text.replace('\n\n# placeholder\nmerged:', '\n\n' + '# 2026-10-26：照站長「輔助寶石有分 I II III 的一律保留最高的」' if False else '\n\n# placeholder\nmerged:')
    mer = ''.join('  %s: { into: %s, name: %s }   # 2026-11-01 照站長：原作 %s 我們已經有了，併過去\n' % (k, into, n, en)
                  for k, (into, n, en) in MERGE.items() if not re.search(r'^  %s: \{ into' % k, text, re.M))
    if mer:
        anchor = '\nsupports:\n'
        text = text.replace(anchor, '\n  # 2026-11-01 照站長「以原作某顆為基準自己改的輔助，改成跟原作一樣」：原作有同一顆的併過去\n'
                            + mer.rstrip('\n') + '\n' + anchor, 1)
    text = text.replace('\n\n# placeholder\nmerged:', '\nmerged:')
    F.write_text(text, encoding='utf-8')
    print('改了：', ', '.join(changed))


if __name__ == '__main__':
    main()
