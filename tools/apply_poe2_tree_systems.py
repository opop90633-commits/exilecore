#!/usr/bin/env python3
"""2026-10-26：把天賦樹的「系統」類 stat（status needs_tag／new_mechanic）接到引擎做好的系統上，改寫 stat_map.json 與 overrides.json。

每一條：stat id → (status, [(屬性鍵, 型態, 標籤)], 說明)。status 寫 mapped（照 PoE2）或 approx（意思相近，說明寫差在哪）。
說明裡有 negate 的數值反過來（「減少」寫成負的增加）。要固定數值或倍率的寫在 OVERRIDES（同 overrides.json 的格式）。
跑完再跑 tools/gen_poe2_tree.py 產生 tree.json。這個檔可以重跑（結果一樣）。
"""
import json
import os

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'poe2_tree')
MINION_COMPANION = ['minion', 'companion']
PRESENCE = ['minion', 'in_presence']

MAP = {
    # ---- 夥伴（skills.yml 的 beast_companion、wolf_pack 帶 companion；MinionSheets 只把 [minion, companion] 給夥伴）----
    'companion_damage_+%': ('mapped', [('damage', 'INCREASED', MINION_COMPANION)], '夥伴'),
    'companion_maximum_life_+%': ('mapped', [('life.max', 'INCREASED', MINION_COMPANION)], '夥伴'),
    'companion_attack_speed_+%': ('mapped', [('attack.speed', 'INCREASED', MINION_COMPANION)], '夥伴'),
    'companion_movement_speed_%': ('mapped', [('movement.speed', 'INCREASED', MINION_COMPANION)], '夥伴'),
    'companion_elemental_resistance_%': ('mapped', [('resist.fire', 'FLAT', MINION_COMPANION), ('resist.cold', 'FLAT', MINION_COMPANION),
                                                     ('resist.lightning', 'FLAT', MINION_COMPANION)], '夥伴'),
    'companion_chaos_resistance_%': ('mapped', [('resist.chaos', 'FLAT', MINION_COMPANION)], '夥伴'),
    'companion_%_damage_as_chaos': ('mapped', [('damage.extra.chaos', 'FLAT', MINION_COMPANION)], '夥伴'),
    'companion_%_damage_as_cold': ('mapped', [('damage.extra.cold', 'FLAT', MINION_COMPANION)], '夥伴'),
    'companion_chance_to_poison_on_hit_%': ('mapped', [('poison.chance', 'FLAT', MINION_COMPANION)], '夥伴'),
    'reservation_efficiency_+%_of_companion_skills': ('mapped', [('aura.reservation', 'INCREASED', ['companion'])],
                                                      '夥伴技能的保留效率（efficiency：數值反過來）'),
    # ---- 強化攻擊（戰吼強化的那幾下帶 empowered）----
    'empowered_attack_damage_+%': ('mapped', [('damage', 'INCREASED', ['attack', 'empowered'])], '強化攻擊'),
    'empowered_attack_hit_damage_stun_multiplier_+%': ('mapped', [('stun.buildup', 'INCREASED', ['attack', 'empowered'])], '強化攻擊'),
    'hit_damage_freeze_multiplier_+%_with_empowered_attacks': ('mapped', [('freeze.buildup', 'INCREASED', ['attack', 'empowered'])], '強化攻擊'),
    'non_skill_base_all_damage_%_to_gain_as_cold_with_empowered_attacks': ('mapped', [('damage.extra.cold', 'FLAT', ['attack', 'empowered'])], '強化攻擊'),
    'empowered_attack_physical_damage_%_to_gain_as_fire': ('mapped', [('damage.gain.fire', 'FLAT', ['attack', 'empowered'])], '強化攻擊'),
    # ---- 戰旗（defiance／war／dread_banner 帶 banner、area、duration）----
    'banner_area_of_effect_+%': ('mapped', [('skill.area', 'INCREASED', ['banner'])], '戰旗'),
    'banner_duration_+%': ('mapped', [('skill.duration', 'INCREASED', ['banner'])], '戰旗'),
    'banner_aura_effect_+%': ('mapped', [('warcry.effect', 'INCREASED', ['banner'])], '戰旗（我們的戰旗算戰吼類，增益吃戰吼效果）'),
    # 榮耀：我們拿掉了榮耀（2026-10），戰旗改成冷卻；「榮耀獲得增加」= 戰旗更快能再插（冷卻回復速度）
    'glory_generation_+%': ('approx', [('cooldown.recovery', 'INCREASED', ['banner'])], '榮耀（戰旗用冷卻代替榮耀：當作戰旗的冷卻回復速度）'),
    'glory_generation_+%_for_banners': ('approx', [('cooldown.recovery', 'INCREASED', ['banner'])], '榮耀（戰旗用冷卻代替榮耀：當作戰旗的冷卻回復速度）'),
    # ---- 招架 ----
    'parried_magnitude_+%': ('mapped', [('parry.parried_effect', 'INCREASED', [])], '招架'),
    'parry_skill_effect_duration_+%': ('mapped', [('parry.parried_duration', 'INCREASED', [])], '招架'),
    'parry_area_of_effect_+%': ('mapped', [('skill.area', 'INCREASED', ['parry'])], '招架橫掃'),
    'parry_damage_+%': ('mapped', [('damage', 'INCREASED', ['parry'])], '招架橫掃'),
    'parry_hit_damage_stun_multiplier_+%': ('mapped', [('stun.buildup', 'INCREASED', ['parry'])], '招架橫掃'),
    # ---- 引導、觸發、Invocation ----
    'channelled_skill_damage_+%': ('mapped', [('damage', 'INCREASED', ['channelling'])], '引導技能'),
    'triggered_spell_spell_damage_+%': ('mapped', [('damage', 'INCREASED', ['spell', 'triggered'])], '主要寶石觸發的法術'),
    'invocation_spell_damage_+%': ('mapped', [('damage', 'INCREASED', ['invocation'])], 'Invocation 觸發的法術'),
    'invocation_spell_critical_strike_chance_+%': ('mapped', [('crit.chance', 'INCREASED', ['invocation'])], 'Invocation 觸發的法術'),
    'invocation_spell_critical_strike_multiplier_+': ('mapped', [('crit.bonus', 'INCREASED', ['invocation'])], 'Invocation 觸發的法術'),
    'invocation_skill_maximum_energy_+%': ('mapped', [('meta.energy_max', 'INCREASED', ['invocation'])], 'Invocation 主要寶石'),
    'ascendancy_energy_generated_+%_final': ('mapped', [('meta.energy_gain', 'MORE', [])], '主要寶石'),
    'trigger_skills_refund_half_energy_spent_chance_%': ('mapped', [('meta.energy_refund', 'FLAT', [])], '主要寶石'),
    # ---- 危害、植物、引爆、弩台、祭品（skills.yml 加標籤）----
    'hazard_damage_+%': ('mapped', [('damage', 'INCREASED', ['hazard'])], '危害'),
    'hazard_duration_+%': ('mapped', [('skill.duration', 'INCREASED', ['hazard'])], '危害'),
    'hazard_area_of_effect_+%': ('mapped', [('skill.area', 'INCREASED', ['hazard'])], '危害'),
    'jagged_ground_effect_+%': ('mapped', [('jagged.effect', 'INCREASED', [])], '碎地（碎地輔助）'),
    'jagged_ground_duration_+%': ('mapped', [('jagged.duration', 'INCREASED', [])], '碎地（碎地輔助）'),
    # 2026-10-27 照 PoE2 魔力消耗效率（CostKeys：消耗 ÷ (1 + 效率)）；這次之前 approx 成 skill.cost 的減少
    'base_mana_cost_efficiency_+%_of_command_skills': ('mapped', [('skill.mana_cost_efficiency', 'INCREASED', ['command'])], '指令技能的魔力消耗效率（CostKeys：消耗 ÷ (1 + 效率)）'),
    'plant_skill_damage_+%': ('mapped', [('damage', 'INCREASED', ['plant'])], '植物'),
    'detonator_skill_area_of_effect_+%': ('mapped', [('skill.area', 'INCREASED', ['detonator'])], '引爆'),
    'detonator_skill_damage_+%': ('mapped', [('damage', 'INCREASED', ['detonator'])], '引爆'),
    'ballista_damage_+%': ('mapped', [('damage', 'INCREASED', ['totem', 'ballista'])], '弩台'),
    'ballista_critical_hit_chance_+%': ('mapped', [('crit.chance', 'INCREASED', ['totem', 'ballista'])], '弩台'),
    'ballista_critical_damage_+%': ('mapped', [('crit.bonus', 'INCREASED', ['totem', 'ballista'])], '弩台'),
    'ballista_attack_speed_+%': ('mapped', [('attack.speed', 'INCREASED', ['totem', 'ballista'])], '弩台'),
    'attack_skills_additional_ballista_totems_allowed': ('mapped', [('skill.totems', 'FLAT', ['attack', 'ballista'])], '弩台'),
    'melee_attack_skills_additional_totems_allowed': ('mapped', [('skill.totems', 'FLAT', ['attack', 'melee'])], '圖騰'),
    'ascendency_tactician_totem_skill_speed_+%_final': ('mapped', [('attack.speed', 'MORE', ['totem']), ('cast.speed', 'MORE', ['totem'])], '圖騰'),
    'offering_duration_+%': ('mapped', [('skill.duration', 'INCREASED', ['offering'])], '祭品'),
    'offering_area_of_effect_+%': ('mapped', [('skill.area', 'INCREASED', ['offering'])], '祭品'),
    'offering_spells_effect_+%': ('mapped', [('warcry.effect', 'INCREASED', ['offering'])], '祭品（我們的祭品算戰吼類，增益吃戰吼效果）'),
    # ---- 戰星 ----
    'chance_to_gain_1_more_power_charge_%': ('mapped', [('charge.extra.power', 'FLAT', [])], '戰星'),
    'chance_to_gain_1_more_frenzy_charge_%': ('mapped', [('charge.extra.frenzy', 'FLAT', [])], '戰星'),
    'chance_to_gain_1_more_endurance_charge_%': ('mapped', [('charge.extra.endurance', 'FLAT', [])], '戰星'),
    'gain_maximum_power_charges_on_power_charge_gained_%_chance': ('mapped', [('charge.fill.power', 'FLAT', [])], '戰星'),
    'gain_maximum_frenzy_charges_on_frenzy_charge_gained_%_chance': ('mapped', [('charge.fill.frenzy', 'FLAT', [])], '戰星'),
    'gain_maximum_endurance_charges_on_endurance_charge_gained_%_chance': ('mapped', [('charge.fill.endurance', 'FLAT', [])], '戰星'),
    # ---- 殘影（我們只有幻身留下的殘影）----
    'remnant_pickup_range_+%': ('mapped', [('remnant.pickup_range', 'INCREASED', [])], '殘影'),
    'remnant_recover_%_life_on_pickup': ('mapped', [('remnant.life_on_pickup', 'FLAT', [])], '殘影'),
    'remnant_recover_%_mana_on_pickup': ('mapped', [('remnant.mana_on_pickup', 'FLAT', [])], '殘影'),
    'created_remnants_have_%_chance_to_duplicate_pick_up_results': ('mapped', [('remnant.double_chance', 'FLAT', [])], '殘影'),
    'chance_%_to_create_additional_remnant': ('approx', [('charge.remnant_extra', 'FLAT', [])], '殘影（只有幻身會留殘影）'),
    # ---- 元素灌注 ----
    'infusion_duration_+%': ('mapped', [('infusion.duration', 'INCREASED', [])], '灌注'),
    'chance_to_not_consume_infusion_%': ('mapped', [('infusion.keep_chance', 'FLAT', [])], '灌注'),
    'additional_infusion_gain_chance_%': ('mapped', [('infusion.extra_chance', 'FLAT', [])], '灌注'),
    'maximum_fire_infusion_stacks': ('mapped', [('infusion.max.fire', 'FLAT', [])], '灌注'),
    'maximum_cold_infusion_stacks': ('mapped', [('infusion.max.cold', 'FLAT', [])], '灌注'),
    'maximum_lightning_infusion_stacks': ('mapped', [('infusion.max.lightning', 'FLAT', [])], '灌注'),
    'additional_maximum_infusion_stacks': ('mapped', [('infusion.max', 'FLAT', [])], '灌注'),
    # ---- 存在範圍內的友軍（只有召喚物；隊友還沒做）----
    'allies_in_presence_damage_+%': ('approx', [('damage', 'INCREASED', PRESENCE)], '存在範圍內的召喚物（隊友還沒做）'),
    'allies_in_presence_attack_speed_+%': ('approx', [('attack.speed', 'INCREASED', PRESENCE)], '存在範圍內的召喚物（隊友還沒做）'),
    'allies_in_presence_cast_speed_+%': ('approx', [('cast.speed', 'INCREASED', PRESENCE)], '存在範圍內的召喚物（隊友還沒做）'),
    'allies_in_presence_critical_strike_chance_+%': ('approx', [('crit.chance', 'INCREASED', PRESENCE)], '存在範圍內的召喚物（隊友還沒做）'),
    'allies_in_presence_accuracy_rating': ('approx', [('accuracy', 'FLAT', PRESENCE)], '存在範圍內的召喚物（隊友還沒做）'),
    # ---- 減益免疫、其他現成的系統 ----
    'immune_to_maim': ('mapped', [('flag.maim_immune', 'FLAT', [])], '免疫'),
    'immune_to_hinder': ('mapped', [('flag.hinder_immune', 'FLAT', [])], '免疫'),
    'cannot_be_blinded': ('mapped', [('flag.blind_immune', 'FLAT', [])], '免疫'),
    'exposure_you_inflict_lowers_affected_resistance_by_extra_%': ('mapped', [('exposure.extra', 'FLAT', [])], '曝露（加在基礎 20% 上）'),
    'blind_duration_+%': ('mapped', [('blind.duration', 'INCREASED', [])], '致盲'),
    'hinder_duration_+%': ('mapped', [('hinder.duration', 'INCREASED', [])], '阻礙'),
    'enemies_you_blind_have_critical_strike_chance_+%': ('mapped', [('blind.enemy_crit', 'INCREASED', [])], '致盲'),
    'daze_duration_+%': ('mapped', [('daze.duration', 'INCREASED', [])], '眩暈'),
    'self_poison_duration_+%': ('mapped', [('ailment.self_duration.poison', 'INCREASED', [])], '自己身上的中毒'),
    'natures_sustenance_additional_maximum_rage': ('mapped', [('rage.max', 'FLAT', [])], '怒氣'),
    'charms_%_chance_to_not_consume_charges': ('mapped', [('charm.free_chance', 'FLAT', [])], '護符'),
    'area_of_effect_+%_with_herald_skills': ('mapped', [('skill.area', 'INCREASED', ['herald'])], '捷光環'),
    'projectile_speed_+%_with_crossbow_skills': ('mapped', [('skill.projectile_speed', 'INCREASED', ['crossbow'])], '弩'),
    'grenade_fuse_duration_+%': ('mapped', [('grenade.fuse', 'INCREASED', [])], '榴彈'),
    'grenade_skill_number_of_additional_projectiles': ('mapped', [('skill.projectiles', 'FLAT', ['grenade'])], '榴彈'),
    'cold_skill_gem_level_+': ('mapped', [('skill.level', 'FLAT', ['cold'])], '技能等級'),
    'chaos_skill_gem_level_+': ('mapped', [('skill.level', 'FLAT', ['chaos'])], '技能等級'),
    'seal_gain_frequency_+%': ('mapped', [('seal.frequency', 'INCREASED', [])], '封印'),
    'slam_skill_area_of_effect_+%': ('mapped', [('skill.area', 'INCREASED', ['slam'])], '猛擊'),
    'combo_falloff_speed_+%': ('approx', [('combo.duration', 'INCREASED', [])], '連擊（流失變慢當作連擊撐更久；negate）'),
}

# 要固定數值或倍率的（同 overrides.json：value 固定、scale 乘上 stat 的值）
OVERRIDES = {
    # 「存在範圍內的敵人有曝露」：曝露 = 三種元素抗性 −20%（臨在的 presence.resist）；曝露效果的加成不算
    'enemies_in_presence_have_exposure': [{'stat': 'presence.resist.fire', 'type': 'FLAT', 'value': 20},
                                          {'stat': 'presence.resist.cold', 'type': 'FLAT', 'value': 20},
                                          {'stat': 'presence.resist.lightning', 'type': 'FLAT', 'value': 20}],
    # 「獲得連擊時多一層」= 多累積一層的機率 100%
    'additional_combo_gain_on_hit': [{'stat': 'combo.extra_chance', 'type': 'FLAT', 'value': 100}],
}


def main():
    path = os.path.join(HERE, 'stat_map.json')
    with open(path, encoding='utf-8') as f:
        stat_map = json.load(f)
    changed = 0
    for sid, (status, suggest, note) in MAP.items():
        entry = stat_map.get(sid)
        if entry is None:
            raise SystemExit('stat_map 沒有 ' + sid)
        entry['status'] = status
        entry['suggest'] = [{'stat': s, 'type': t, 'tags': tags} for s, t, tags in suggest]
        entry['note'] = '2026-10-26 系統：' + note
        changed += 1
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(stat_map, f, ensure_ascii=False, indent=1)
        f.write('\n')
    opath = os.path.join(HERE, 'overrides.json')
    with open(opath, encoding='utf-8') as f:
        overrides = json.load(f)
    for sid, rule in OVERRIDES.items():
        if sid not in stat_map:
            raise SystemExit('stat_map 沒有 ' + sid)
        overrides[sid] = rule
    with open(opath, 'w', encoding='utf-8') as f:
        json.dump(overrides, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print('stat_map 改了', changed, '條；overrides', len(OVERRIDES), '條')


if __name__ == '__main__':
    main()
