#!/usr/bin/env python3
"""2026-10-30 照站長「珠寶能洗到的詞綴照原作補齊、改成同類只出一條」：
1. 補上 PoE2 0.5 有、我們沒有的珠寶詞綴（一般珠寶 affixes/jewels.yml 的 j2_*、遺時珠寶 affixes/jewels_radius.yml 的 jr_*）。
   屬性換算照天賦樹（apply_poe2_tree_systems.py、apply_poe2_tree_new_mechanics.py、gen_timeless.py）同一條 PoE2 屬性的寫法。
   遺時珠寶：原作沒有寫是小天賦還是核心天賦，照我們已經對過的那些（poe2db）的規律：半徑數值 ÷ 一般珠寶數值 ≤ 0.21 是小天賦、≥ 0.24 是核心天賦。
   2026-10-30 對過 poe2db.tw/us/Time-Lost_Jewels：這次補的 35 條小天賦／核心天賦與數值範圍全部跟原作一樣。
2. PoE2 同一個 mod group 的詞綴同一顆珠寶只能出一條：我們的 group 改成同一個（取那幾條代號最小的）。
來源：RePoE poe2 data/mods.json（repoe-fork/poe2）。用法：python3 tools/apply_poe2_jewel_missing.py <mods.json>；可以重跑（已經有的代號不再加）。"""
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
NORMAL = ROOT / 'src/main/resources/data/affixes/jewels.yml'
RADIUS = ROOT / 'src/main/resources/data/affixes/jewels_radius.yml'
TAGMAP = {'strjewel': 'ruby', 'dexjewel': 'emerald', 'intjewel': 'sapphire',
          'str_radius_jewel': 'ruby', 'dex_radius_jewel': 'emerald', 'int_radius_jewel': 'sapphire'}

# PoE2 屬性 → (代號字尾, 文字（{value}）, [(stat, type, value 寫法, tags)])
M = {
    'armour_break_and_sundered_armour_duration_+%': ('armour_break_duration', '增加 {value}% 破甲持續時間', [('armour.break_duration', 'INCREASED', '{value}', [])]),
    'blind_effect_+%': ('blind_effect', '增加 {value}% 致盲效果', [('blind.effect', 'INCREASED', '{value}', [])]),
    'critical_hit_damaging_ailment_effect_+%': ('critical_ailment_effect', '增加 {value}% 你以暴擊造成的傷害型異常狀態幅度',
        [('ignite.magnitude', 'INCREASED', '{value}', ['crit_hit']), ('bleed.magnitude', 'INCREASED', '{value}', ['crit_hit']),
         ('poison.magnitude', 'INCREASED', '{value}', ['crit_hit'])]),
    'curse_delay_+%': ('curse_delay', '詛咒生效速度加快 {value}%', [('curse.delay', 'INCREASED', '-{value}', [])]),
    'damage_+%_against_enemies_with_fully_broken_armour': ('damage_vs_armour_broken', '對完全破甲的敵人增加 {value}% 傷害',
        [('damage', 'INCREASED', '{value}', ['vs_brokenarmour'])]),
    'debuff_time_passed_+%': ('debuff_expiry', '你身上的減益效果消退速度加快 {value}%', [('ailment.self_duration', 'INCREASED', '-{value}', [])]),
    'empowered_attack_damage_+%': ('empowered_attack_damage', '增加 {value}% 強化攻擊的傷害', [('damage', 'INCREASED', '{value}', ['attack', 'empowered'])]),
    'energy_generated_+%': ('energy', '增加 {value}% 引星獲得的能量', [('meta.energy_gain', 'INCREASED', '{value}', [])]),
    'damaging_ailments_deal_damage_+%_faster': ('faster_ailments', '傷害型異常狀態的傷害速度加快 {value}%', [('ailment.speed', 'INCREASED', '{value}', [])]),
    'energy_shield_from_focus_+%': ('focus_energy_shield', '增加 {value}% 已裝備法器的能量護盾', [('focus.es', 'INCREASED', '{value}', [])]),
    'chance_to_fork_extra_projectile_%': ('forking_projectiles', '投射物分裂時有 {value}% 機率額外產生 1 個投射物', [('skill.fork_extra_chance', 'FLAT', '{value}', [])]),
    'freeze_threshold_+%': ('freeze_threshold', '增加 {value}% 凍結門檻', [('freeze.threshold', 'INCREASED', '{value}', [])]),
    'base_skill_cost_life_instead_of_mana_%': ('life_cost', '{value}% 的技能魔力消耗轉換為生命消耗', [('skill.life_cost_percent', 'FLAT', '{value}', [])]),
    'mark_use_speed_+%': ('mark_cast_speed', '增加 {value}% 印記技能的使用速度', [('cast.speed', 'INCREASED', '{value}', ['mark'])]),
    'offering_duration_+%': ('offering_duration', '增加 {value}% 祭品的持續時間', [('skill.duration', 'INCREASED', '{value}', ['offering'])]),
    'offering_life_+%': ('offering_life', '增加 {value}% 祭品的最大生命', [('offering.life', 'INCREASED', '{value}', [])]),
    'hit_damage_pin_multiplier_+%': ('pin_buildup', '增加 {value}% 釘身累積', [('pin.buildup', 'INCREASED', '{value}', [])]),
    'quiver_mod_effect_+%': ('quiver_effect', '增加 {value}% 已裝備箭袋提供的加成', [('quiver.effect', 'INCREASED', '{value}', [])]),
    'shield_armour_evasion_energy_shield_+%': ('shield_defences', '增加 {value}% 已裝備盾牌的護甲、閃避值與能量護盾', [('shield.defences', 'INCREASED', '{value}', [])]),
    'stun_threshold_+_from_%_maximum_energy_shield': ('stun_threshold_from_es', '獲得額外暈眩門檻，等同最大能量護盾的 {value}%', [('stun.threshold_from_es', 'FLAT', '{value}', [])]),
    'ailment_threshold_+_from_%_maximum_energy_shield': ('ailment_threshold_from_es', '獲得額外異常狀態門檻，等同最大能量護盾的 {value}%', [('ailment.threshold_from_es', 'FLAT', '{value}', [])]),
    'summon_totem_cast_speed_+%': ('totem_placement_speed', '增加 {value}% 圖騰放置速度', [('totem.placement_speed', 'INCREASED', '{value}', [])]),
    'triggered_spell_spell_damage_+%': ('triggered_spell_damage', '觸發的法術，增加 {value}% 法術傷害', [('damage', 'INCREASED', '{value}', ['spell', 'triggered'])]),
    'warcry_speed_+%': ('warcry_speed', '增加 {value}% 戰吼速度', [('warcry.speed', 'INCREASED', '{value}', [])]),
    'withered_magnitude_+%': ('withered_effect', '增加 {value}% 凋零幅度', [('withered.effect', 'INCREASED', '{value}', [])]),
    'parry_damage_+%': ('parry_damage', '增加 {value}% 招架傷害', [('damage', 'INCREASED', '{value}', ['parry'])]),
    'parry_skill_effect_duration_+%': ('parried_debuff_duration', '增加 {value}% 被招架減益持續時間', [('parry.parried_duration', 'INCREASED', '{value}', [])]),
    'parry_stun_threshold_+%_during_parry': ('stun_threshold_during_parry', '招架時，增加 {value}% 暈眩門檻', [('stun.threshold@parrying', 'INCREASED', '{value}', [])]),
    'companion_damage_+%': ('companion_damage', '增加 {value}% 夥伴的傷害', [('damage', 'INCREASED', '{value}', ['minion', 'companion'])]),
    'companion_maximum_life_+%': ('companion_life', '增加 {value}% 夥伴的最大生命', [('life.max', 'INCREASED', '{value}', ['minion', 'companion'])]),
    'hazard_damage_+%': ('hazard_damage', '增加 {value}% 危害傷害', [('damage', 'INCREASED', '{value}', ['hazard'])]),
    'chance_to_inflict_incision_on_attack_hit_%': ('incision_chance', '攻擊擊中有 {value}% 機率施加切割', [('incision.chance', 'FLAT', '{value}', ['attack'])]),
    'glory_generation_+%_for_banners': ('banner_glory', '增加 {value}% 榮耀產生', [('cooldown.recovery', 'INCREASED', '{value}', ['banner'])]),
    'banner_area_of_effect_+%': ('banner_area', '增加 {value}% 旗幟技能的範圍效果', [('skill.area', 'INCREASED', '{value}', ['banner'])]),
    'banner_duration_+%': ('banner_duration', '增加 {value}% 旗幟技能的持續時間', [('skill.duration', 'INCREASED', '{value}', ['banner'])]),
    'plant_skill_damage_+%': ('plant_damage', '增加 {value}% 植物技能的傷害', [('damage', 'INCREASED', '{value}', ['plant'])]),
}


def snake(x):
    return re.sub(r'(?<!^)(?=[A-Z])', '_', x).lower()


def main():
    mods = json.load(open(sys.argv[1], encoding='utf-8'))
    jw = {}
    for k, v in mods.items():
        if v.get('domain') != 'misc' or v['generation_type'] not in ('prefix', 'suffix'):
            continue
        sw = {w['tag']: w['weight'] for w in v.get('spawn_weights', [])}
        tags = [t for t in TAGMAP if sw.get(t, 0) > 0]
        if tags:
            jw[k] = dict(gen=v['generation_type'], tags=sorted({TAGMAP[t] for t in tags}, key=['ruby', 'emerald', 'sapphire'].index),
                         radius=any('radius' in t for t in tags), groups=v['groups'], stat=v['stats'][0]['id'],
                         lo=v['stats'][0]['min'], hi=v['stats'][0]['max'])
    normal_text = NORMAL.read_text(encoding='utf-8')
    radius_text = RADIUS.read_text(encoding='utf-8')
    existing = set(yaml.safe_load(normal_text)) | set(yaml.safe_load(radius_text))
    add_n, add_r, added = [], [], {}
    for k, v in jw.items():
        if v['stat'] not in M:
            continue
        suffix, text, stats = M[v['stat']]
        lo, hi = v['lo'], v['hi']
        negative = lo < 0
        if negative:
            lo, hi = -hi, -lo
        if v['radius']:
            normal = jw.get('Jewel' + k[len('JewelRadius'):])
            ratio = abs(v['hi']) / max(1e-9, abs(normal['hi'])) if normal else 0.5
            notable = ratio >= 0.24
            sid = 'jr_' + suffix
            prefix = 'radius_notable:' if notable else 'radius:'
            shown = ('範圍內的核心天賦也各給：' if notable else '範圍內的小天賦也各給：') + text
            spawn = ['r_' + t for t in v['tags']]
            names = '[遺時的]' if v['gen'] == 'prefix' else '[之遺時]'
            tier = '    - { tier: 1, ilvl: 1, weight: 1000, value: [%d, %d] }' % (lo, hi)
        else:
            sid = 'j2_' + suffix
            prefix = ''
            shown = text
            spawn = v['tags']
            names = '[星骸的, 精煉的, 雕琢的]' if v['gen'] == 'prefix' else '[之星骸, 之精煉, 之雕琢]'
            tier = '    - { tier: 3, ilvl: 1, weight: 1000, value: [%d, %d] }' % (lo, hi)
        if sid in existing:
            continue
        lines = ['%s:' % sid,
                 '  # 2026-10-30 照 PoE2 0.5（RePoE %s：%s %s～%s）補上；屬性換算照天賦樹同一條' % (k, v['stat'], v['lo'], v['hi']),
                 '  type: %s' % v['gen'], '  group: %s' % sid, '  spawn_tags: [%s]' % ', '.join(spawn), '  names: %s' % names,
                 '  text: "%s"' % shown, '  chips: []', '  tiers:', tier, '  mods:']
        for stat, typ, val, tags in stats:
            t = ', tags: [%s]' % ', '.join(tags) if tags else ''
            lines.append('    - { stat: %s%s, type: %s, value: "%s"%s }' % (prefix, stat, typ, val, t))
        (add_r if v['radius'] else add_n).append('\n'.join(lines))
        added[k] = sid
    header = '\n# ---- 2026-10-30 照 PoE2 0.5 補齊（tools/apply_poe2_jewel_missing.py）----\n\n'
    if add_n:
        normal_text = normal_text.rstrip('\n') + '\n' + header + '\n\n'.join(add_n) + '\n'
    if add_r:
        radius_text = radius_text.rstrip('\n') + '\n' + header + '\n\n'.join(add_r) + '\n'
    NORMAL.write_text(normal_text, encoding='utf-8')
    RADIUS.write_text(radius_text, encoding='utf-8')
    print('added normal', len(add_n), 'radius', len(add_r))


if __name__ == '__main__':
    main()


# ---- 同一個 PoE2 mod group 的詞綴共用 group（同一顆珠寶只出一條）----
def regroup(mods_path, pairs):
    """pairs：我們的代號 → PoE2 mod 代號（已經對好的）。同一個 PoE2 group（一般、遺時分開算）的改成同一個 group。"""
    mods = json.load(open(mods_path, encoding='utf-8'))
    sets = {}
    for ours, poe in pairs.items():
        for g in mods[poe]['groups']:
            sets.setdefault((ours.startswith('jr_'), g), set()).add(ours)
    target = {}
    for members in sets.values():
        if len(members) > 1:
            name = min(members)
            for m in members:
                target[m] = min(target.get(m, name), name)
    changed = 0
    for path in (NORMAL, RADIUS):
        text = path.read_text(encoding='utf-8')
        for sid, group in target.items():
            pat = re.compile(r'^(%s:\n(?:  .*\n|\s*\n)*?  group: )(\S+)(.*)$' % re.escape(sid), re.M)
            m = pat.search(text)
            if m and m.group(2) != group:
                text = text[:m.start()] + m.group(1) + group + '   # 2026-10-30 照 PoE2：跟同一類（mod group %s）共用，同一顆只出一條；這次之前 %s' % (
                    [g for (r, g), s in sets.items() if sid in s and len(s) > 1][0], m.group(2)) + text[m.end():]
                changed += 1
        path.write_text(text, encoding='utf-8')
    print('regrouped', changed)
