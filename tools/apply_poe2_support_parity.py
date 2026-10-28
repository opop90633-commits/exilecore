#!/usr/bin/env python3
"""2026-10-27：站長「輔助也比照 poe2db」——之前沒有對照 PoE2 的 79 個輔助（supports.yml 沒寫「照 PoE2」的，不含專屬掉落）逐一對照 PoE2 0.5。
來源：RePoE poe2 data/skill_gems.json（階級 crafting_level）、data/skills.json（stat_text、cost_multiplier）、PoB 的 constantStats、poe2db 寶石頁。
SPEC：我們的代號 → (PoE2 名稱, PoE2 階級或 None（0.5 切不出來的，階級不動）, 魔耗倍率或 None（不動）, modifiers 或 None（效果不動）, 說明或 None（不動）)。
階級換算跟 apply_poe2_supports.py 一樣：PoE2 第 1 階 → 1、第 2–3 階 → 2、第 4–5 階 → 3。有 I／II／III 的照「保留最高階」用最高那一階的數值。
每一塊改完在 modifiers 上面留一行「2026-10-27 照 PoE2 …；這次之前 …」。可以重跑（已經寫過 2026-10-27 註解的整塊照 SPEC 重寫）。
NO_COUNTERPART：PoE2 0.5 沒有對應的（不動，交給站長決定）。"""
import re
from pathlib import Path

F = Path(__file__).resolve().parent.parent / 'src/main/resources/data/supports.yml'


def m(stat, typ, value, tags=None):
    t = ', tags: [%s]' % ', '.join(tags) if tags else ''
    return '{ stat: %s, type: %s, value: %s%s }' % (stat, typ, value, t)


def tier(p):
    return None if p is None else (1 if p == 1 else 2 if p <= 3 else 3)


SPEC = {
    # ---- 數值照原作改 ----
    'ignition': ('Ignite I', 1, 1.2, [m('ignite.chance', 'MORE', 100)],
                 '被輔助的技能點燃機率更多 100%（易燃更多 100%）。'),
    'precision_aim': ('Heightened Accuracy I', 1, 1.1, [m('accuracy', 'MORE', 50)],
                      '被輔助的攻擊命中值更多 50%。'),
    'meat_shield': ('Meat Shield II', 3, 1.0, [m('life.max', 'MORE', 67, ['minion']), m('damage', 'MORE', -40, ['minion'])],
                    '被輔助的召喚物受到的傷害更少 40%（做成生命更多 67%，一樣耐打），但造成的傷害更少 40%。'),
    'fire_penetration': ('Fire Penetration I', 2, 1.2, None, None),
    'cold_penetration': ('Cold Penetration', 2, 1.2, None, None),
    'lightning_penetration': ('Lightning Penetration', 2, 1.2, None, None),
    'giant_minions': ('Hulking Minions', 4, 1.3, None, None),
    'devastate': ('Devastate', None, 1.2, None, None),
    'excoriate': ('Excoriate', None, 1.15, None, None),
    'see_red': ('See Red', None, None, None, None),
    'greatwood': ('Greatwood II', None, 1.15, None, None),
    'arms_length': ('Arms Length', None, 1.1, [m('skill.knockback', 'MORE', 40)],
                    '被輔助的近戰攻擊擊退距離更多 40%。'),
    'cadence': ('Cadence', 2, None, None, None),
    'nova_projectiles': ('Nova Projectiles I', 2, 1.0, [m('flag.projectile_nova', 'FLAT', 1)],
                         '被輔助的投射物改成往四周一圈射出。'),
    'fiery_death': ('Fiery Death', 2, 1.2, None, None),
    'bursting_plague': ('Bursting Plague', 2, 1.2, None, None),
    'bone_shrapnel': ('Bone Shrapnel', 2, 1.0, None, None),
    'flame_pillar': ('Flame Pillar', None, 1.15, None, None),
    'electromagnetism': ('Electromagnetism', 3, 1.0, None, None),
    # 2026-10-28 照站長刪掉震符印（supports.yml 的 retired）
    'corrupting_cry': ('Corrupting Cry II', 5, 1.0, None, None),
    'caltrops': ('Caltrops', 3, 1.2, None, None),
    'decaying_hex': ('Decaying Hex', 3, 1.3, None, None),
    'volcanic_eruption': ('Volcanic Eruption', 2, 1.2, None, None),
    'wind_wave': ('Wind Wave', None, 1.0, None, None),
    'static_shocks': ('Static Shocks', 3, 1.0, None, None),
    'frozen_vortex': ('Frozen Vortex', None, 1.2, None, None),
    'charged_mark': ('Charged Mark', 5, 1.0, None, None),
    'aftershock': ('Aftershock II', 4, 1.0, [m('proc.use', 'FLAT', 35, ['proc_aftershock'])],
                   '被輔助的猛擊砸下時，有 35% 機率在面前再震一次（傷害是原本那一下的 60%，吃同樣的加成）。'),
    'tremors': ('Tremors', None, 1.0, None, None),
    'maim': ('Maim', 2, 1.0, None, None),
    'blind': ('Blind II', 5, 1.1, None, None),
    'hinder': ('Hinder', None, 1.0, None, None),
    # 2026-10-28：culmination 改成照 PoE2 的頂點 II（自己的一份連擊，SkillResources.culminate），supports.yml 手寫；不要再用這支腳本蓋掉
    'practiced_combo': ('Practiced Combo', 3, 1.0, None, None),
    'flow': ('Flow', None, 1.0, None, None),
    'glacier': ('Glacier', 2, 1.0, None, None),
    'icicle': ('Icicle', 2, 1.0, None, None),
    'hoarfrost': ('Hoarfrost', None, 1.0, None, None),
    'crystalline_shards': ('Crystalline Shards', 1, 1.2, None, None),
    'admixture': ('Admixture', 2, 1.2, [m('poison.magnitude_vs_bleeding', 'MORE', 20), m('bleed.magnitude', 'MORE', 10)],
                  '被輔助的技能對流血中的敵人造成的中毒更多 20%；流血也更痛一點。'),
    'burgeon': ('Burgeon II', 4, 1.2, [m('channel.burgeon_per_second', 'FLAT', 20), m('channel.burgeon_cap', 'FLAT', 40)],
                '被輔助的引導技能每引導 1 秒傷害更多 20%，最多更多 40%（放開型的照按住多久、連續出手的照連續放了多久）。只能掛在引導或蓄力的技能上。'),
    'dazzle': ('Dazzle', None, 1.0, None, None),
    'fan_the_flames': ('Fan The Flames II', 4, 1.0, None, None),
    'frost_nexus': ('Frost Nexus', 2, 1.2, None, None),
    'impact_shockwave': ('Impact Shockwave', 1, 1.0, [m('proc.heavy_stun', 'FLAT', 100, ['proc_impact_shockwave'])],
                         '被輔助的打擊重暈敵人時，在牠身上震出一圈衝擊，打到周圍的敵人。'),
    'impending_doom': ('Impending Doom', 1, 1.3, None, None),
    'reverberate': ('Reverberate', None, 1.2, None, None),
    'spreading_frost': ('Spreading Frost', None, 1.0, None, None),
    'premeditation': ('Premeditation', None, 1.3, [m('grant.damage', 'MORE', 15, ['attack'])],
                      '被輔助的戰吼增益期間，你的攻擊傷害更多 15%。'),
    'break_posture': ('Break Posture', None, 1.0, None, None),
    'innervate': ('Innervate', 1, 1.0, None, None),
    'charm_bounty': ('Charm Bounty', None, 1.0, None, None),
    'perpetual_charge': ('Perpetual Charge', 1, 1.3, None, None),
    'heightened_charges': ('Heightened Charges', 2, 1.15, None, None),
    'inhibitor': ('Inhibitor', 2, None, None, None),
    'ferocity': ('Ferocity', None, None, None, None),
    'jagged_ground': ('Jagged Ground I', 2, 1.2, None, None),
    'creeping_chill': ('Creeping Chill', 1, None, None, None),
    'energy_retention': ('Energy Retention', 3, 1.1, None, None),
    'energy_capacitor': ('Energy Capacitor', 3, 1.3, None, None),
    'dazing': ('Daze', None, 1.0, None, None),
}

# PoE2 0.5 沒有對應（或只剩同名但機制完全不同、或跟別的輔助重複）的：不動，回報給站長。
NO_COUNTERPART = {
    'elemental_leech': '元素汲取（PoE2 只有物理攻擊偷取）',
    'warcry_power': '強勢戰吼（多強化次數）',
    'exposure': '破元（PoE2 拆成火／冰／電曝露三顆，觸發條件是點燃／凍結／感電）',
    'martial_tempo': '快刀（0.5 已拿掉）',
    'frostbite': '徹寒',
    'unbound_ailments': '縱蝕',
    'chaos_penetration': '蝕穿（PoE2 沒有混沌穿透輔助）',
    'cold_snap_support': '凜冽',
    'searing_heat': '灼熾',
    'storm_surge': '雷湧',
    'hunter_focus': '盯梢',
    'killing_spree': '嗜殺',
    'momentum_kill': '殺勢（PoE2 Momentum 是移動距離，不是擊殺）',
    'chance_to_freeze': '凝凍（跟凍結 glacial＝PoE2 Freeze 重複）',
    'herald_infusion': '灌宣（跟致命捷光 deadly_herald＝PoE2 Deadly Herald 重複）',
    'charge_power': '聚銳（擊中給銳星）',
    'charge_frenzy': '聚怒（擊中給怒星）',
}


def main():
    text = F.read_text(encoding='utf-8')
    done, missing = [], []
    for sid, (poe, ptier, mm, mods, desc) in SPEC.items():
        mt = re.search(r'\n  %s:[^\n]*\n((?:    [^\n]*\n|      [^\n]*\n)+)' % re.escape(sid), text)
        if not mt:
            missing.append(sid)
            continue
        block = mt.group(1)
        if '2026-10-27 照 PoE2' in block:
            done.append(sid)
            continue
        old_mods = re.findall(r'^      - (\{[^\n]*\})', block, re.M)
        old_mm = re.search(r'^    mana_multiplier: ([^\s#]+)', block, re.M)
        old_tier = re.search(r'^    tier: (\d+)', block, re.M)
        old_desc = re.search(r'^    description: ([^\n]*)', block, re.M)
        new = block
        changes = []
        t = tier(ptier)
        if t is not None and old_tier and int(old_tier.group(1)) != t:
            new = re.sub(r'^    tier: \d+[^\n]*', '    tier: %d' % t, new, count=1, flags=re.M)
            changes.append('tier %s' % old_tier.group(1))
        if mm is not None and (not old_mm or float(old_mm.group(1)) != mm):
            if old_mm:
                new = re.sub(r'^    mana_multiplier: [^\n]*', '    mana_multiplier: %s' % mm, new, count=1, flags=re.M)
            else:
                new = re.sub(r'^(    modifiers:)', '    mana_multiplier: %s\n\\1' % mm, new, count=1, flags=re.M)
            changes.append('魔耗 %s' % (old_mm.group(1) if old_mm else '1.0'))
        if mods is not None:
            new = re.sub(r'^      - \{[^\n]*\n', '', new, flags=re.M)
            new = new.replace('    modifiers:\n', '    modifiers:\n' + ''.join('      - %s\n' % x for x in mods), 1)
            changes.append('；'.join(x.strip('{} ') for x in old_mods))
        if desc is not None and old_desc and old_desc.group(1) != desc:
            new = new.replace('    description: %s\n' % old_desc.group(1), '    description: %s\n' % desc, 1)
            changes.append('說明「%s」' % old_desc.group(1))
        ptxt = '第 %d 階' % ptier if ptier else '0.5 切不出來'
        if changes:
            note = '    # 2026-10-27 照 PoE2 %s（%s；poe2db／RePoE）；這次之前 %s\n' % (poe, ptxt, '、'.join(changes))
        else:
            note = '    # 2026-10-27 照 PoE2 %s（%s；poe2db／RePoE）核對過：階級、魔耗、效果跟原作一樣或已是平衡等效，不用改\n' % (poe, ptxt)
        new = new.replace('    modifiers:\n', note + '    modifiers:\n', 1)
        text = text[:mt.start(1)] + new + text[mt.end(1):]
        done.append(sid)
    F.write_text(text, encoding='utf-8')
    print('處理了', len(done), '沒找到', missing)
    print('沒有 PoE2 對應（不動）：', len(NO_COUNTERPART))
    for k, v in NO_COUNTERPART.items():
        print('  %s %s' % (k, v))


if __name__ == '__main__':
    main()
