#!/usr/bin/env python3
"""2026-10-26：把天賦樹「新機制」類 stat（stat_map 原本 status new_mechanic）接到引擎新做的系統上（tw.exilecore.skill.TreeMechanics、
屬性鍵 tw.exilecore.stats.TreeNewMechanicKeys），改寫 stat_map.json 與 overrides.json。

格式同 tools/apply_poe2_tree_systems.py：stat id → (status, [(屬性鍵, 型態, 標籤)], 說明)。status：mapped（照 PoE2）或 approx（做不到一樣，
平衡相當，說明寫差在哪）。說明裡有 negate 的數值反過來、permyriad 的除以 100。要固定數值或倍率的寫在 OVERRIDES。
跑完再跑 tools/gen_poe2_tree.py 產生 tree.json。這個檔可以重跑（結果一樣）。
"""
import json
import os

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'poe2_tree')
IMMOBILISE = ['pin.buildup', 'freeze.buildup', 'electrocute.buildup']

MAP = {
    # ---- 統治者（PoE2 Archon：被觸發的強化狀態，10 秒；失去後 20 秒恢復期）----
    'archon_duration_+%': ('mapped', [('archon.duration', 'INCREASED', [])], '統治者'),
    'archon_effect_on_you_+%': ('mapped', [('archon.effect', 'INCREASED', [])], '統治者'),
    'archon_delay_expires_x%_faster': ('mapped', [('archon.recovery_speed', 'INCREASED', [])], '統治者（恢復期 ÷ (1 + x)）'),
    'archon_grants_base_critical_strike_multiplier_+': ('mapped', [('archon.grant.crit_bonus', 'INCREASED', [])], '統治者'),
    'archon_grants_critical_strike_chance_+%': ('mapped', [('archon.grant.crit_chance', 'INCREASED', [])], '統治者'),
    'archon_no_recovery_period': ('mapped', [('flag.archon_no_recovery', 'FLAT', [])], '統治者'),
    'chance_%_to_gain_archon_of_undeath_on_using_command_skill': ('mapped', [('archon.undeath_on_command', 'FLAT', [])], '統治者（不死）'),
    'gain_archon_elemental_after_spending_100%_of_your_maximum_mana': ('mapped', [('archon.elemental_on_mana', 'FLAT', [])], '統治者（元素）'),
    'archon_grants_all_elemental_resistance_+%': ('mapped', [('archon.grant.resist', 'FLAT', [])], '統治者'),
    'archon_grants_movement_speed_+%': ('mapped', [('archon.grant.movement', 'INCREASED', [])], '統治者'),
    'gain_archon_elemental_when_energy_shield_recharge_starts': ('mapped', [('archon.elemental_on_recharge', 'FLAT', [])], '統治者（元素）'),
    # ---- 易爆（PoE2 Volatility：4 秒後爆炸，每層 100 物理傷害，之後 10 秒易爆之力）----
    'volatility_on_kill_%_chance': ('mapped', [('volatility.on_kill', 'FLAT', [])], '易爆'),
    'volatility_detonation_delay_+%': ('mapped', [('volatility.delay', 'INCREASED', [])], '易爆'),
    'gain_X_volatility_on_persistent_minion_death': ('mapped', [('volatility.on_minion_death', 'FLAT', [])], '易爆（常駐召喚物死亡）'),
    'maximum_volatility_allowed': ('mapped', [('volatility.max', 'FLAT', [])], '易爆（最大層數改成這個數）'),
    'volatility_critical_strike_chance_+%_to_grant': ('mapped', [('volatility.power_crit', 'FLAT', [])], '易爆之力'),
    'volatility_refresh_%_chance': ('mapped', [('volatility.refresh', 'FLAT', [])], '易爆'),
    'immune_to_volatility_damage': ('mapped', [('flag.volatility_immune', 'FLAT', [])], '易爆'),
    # ---- 切割（Incision）與加劇流血 ----
    'chance_to_inflict_incision_on_attack_hit_%': ('mapped', [('incision.chance', 'FLAT', ['attack'])], '切割'),
    'chance_to_inflict_10_incision_on_attack_hit_%': ('mapped', [('incision.chance_ten', 'FLAT', ['attack'])], '切割'),
    'incision_effect_+%': ('mapped', [('incision.effect', 'INCREASED', [])], '切割'),
    'incision_you_inflict_applies_%_increased_physical_damage_taken': ('mapped', [('incision.physical_taken', 'FLAT', [])], '切割'),
    'base_aggravate_bleeding_on_attack_hit_chance_%': ('mapped', [('bleed.aggravate_chance', 'FLAT', ['attack'])], '加劇流血（傳奇用的同一個鍵）'),
    'aggravate_bleeding_on_exerted_attack_hit_chance_%': ('mapped', [('bleed.aggravate_chance', 'FLAT', ['attack', 'empowered'])], '加劇流血（強化攻擊）'),
    'aggravate_bleeding_on_attack_crit_chance_%': ('mapped', [('bleed.aggravate_crit', 'FLAT', ['attack'])], '加劇流血（暴擊）'),
    'aggravate_bleeding_older_than_ms_on_hit': ('approx', [('bleed.aggravate_older', 'FLAT', ['attack'])],
                                                 '加劇流血（流血沒記開始時間：從第一次看到牠在流血算起，到了就加劇牠身上你的全部流血）'),
    # ---- 傀儡大師（Puppet Master）----
    'surpassing_chance_%_to_gain_1_puppeteer_stack_on_using_command_skill': ('mapped', [('puppet.chance', 'FLAT', [])], '傀儡大師（超越機率）'),
    'puppet_master_effect_+%': ('mapped', [('puppet.effect', 'INCREASED', [])], '傀儡大師'),
    'max_puppet_master_stacks_+': ('mapped', [('puppet.max', 'FLAT', [])], '傀儡大師'),
    'puppet_master_duration_+%': ('mapped', [('puppet.duration', 'INCREASED', [])], '傀儡大師'),
    # ---- 秘術增強（Arcane Surge；湧詠輔助改成照 PoE2 給秘術增強）----
    'arcane_surge_effect_on_self_+%': ('mapped', [('arcane_surge.effect', 'INCREASED', [])], '秘術增強'),
    'arcane_surge_mana_regen_modifier_on_you_applies_to_life_regen_instead': ('mapped', [('flag.arcane_surge_life', 'FLAT', [])], '秘術增強'),
    'base_should_have_arcane_surge_from_stat': ('mapped', [('flag.arcane_surge_always', 'FLAT', [])], '秘術增強'),
    # ---- 先祖強化、餘震 ----
    'ancestral_boosted_area_of_effect_+%': ('mapped', [('ancestral.area', 'INCREASED', [])], '先祖強化'),
    'next_attack_is_ancestrally_boosted_for_x_seconds_on_heavy_stunning_unique_or_rare_enemy': (
        'mapped', [('ancestral.after_heavy_stun', 'FLAT', [])], '先祖強化（秒）'),
    'ancestral_slam_every_other_slam': ('mapped', [('flag.ancestral_every_other_slam', 'FLAT', [])], '先祖強化'),
    'explode_enemies_for_10%_life_as_fire_on_kill_with_empowered_attacks_chance_%': (
        'mapped', [('kill.explode_empowered', 'FLAT', [])], '強化攻擊擊殺爆炸'),
    'your_mace_slam_aftershock_chance_%': ('mapped', [('aftershock.chance', 'FLAT', ['mace'])], '餘震（錘）'),
    'slam_aftershock_chance_%': ('mapped', [('aftershock.chance', 'FLAT', [])], '餘震'),
    'shapeshift_slam_skill_aftershock_chance_%': ('mapped', [('aftershock.chance', 'FLAT', ['shapeshift'])], '餘震（變身）'),
    'your_aftershock_area_of_effect_+%': ('mapped', [('aftershock.area', 'INCREASED', [])], '餘震'),
    # ---- 衝刺 ----
    'sprint_movement_speed_+%': ('mapped', [('sprint.movement', 'INCREASED', [])], '衝刺（翻滾後按住翻滾鍵）'),
    # ---- 圖騰、弩台 ----
    'attack_and_cast_speed_+%_on_placing_totem': ('mapped', [('totem.recent_speed', 'FLAT', [])], '最近放過圖騰'),
    'ballista_hit_damage_immobilisation_multiplier_+%': ('mapped', [(k, 'INCREASED', ['ballista']) for k in IMMOBILISE],
                                                         '弩台（禁錮 = 釘住、凍結、電擊的累積）'),
    'totem_placement_range_+%': ('approx', [('totem.placement_speed', 'INCREASED', [])],
                                 '圖騰（我們的圖騰固定放在面前，沒有放置距離：當作放圖騰的速度）'),
    'recoup_%_of_damage_taken_by_your_totems_as_life': ('approx', [('totem.recoup', 'FLAT', [])], '圖騰（補償照偷取的速度 1 秒內回完，原作 4 秒）'),
    'totems_nearby_enemies_damage_taken_+%': ('mapped', [('totem.presence_taken', 'FLAT', [])], '圖騰'),
    # ---- 重暈、釘住、眩暈 ----
    'recover_%_life_on_heavy_stunning_rare_or_unique_enemy': ('mapped', [('stun.heavy_rare_life', 'FLAT', [])], '重暈'),
    'gain_endurance_charge_on_heavy_stunning_rare_or_unique_enemy': ('mapped', [('stun.heavy_rare_endurance', 'FLAT', [])], '重暈'),
    'armour_break_%_of_armour_on_heavy_stunning': ('mapped', [('stun.heavy_armour_break', 'FLAT', [])], '重暈'),
    'heavy_stuns_have_culling_strike': ('approx', [('flag.heavy_stun_cull', 'FLAT', [])],
                                        '重暈（重暈判定在傷害之後：當作擊中重暈中的敵人有撲殺）'),
    'heavy_stun_poise_decay_rate_+%': ('approx', [('stun.recovery', 'INCREASED', [])],
                                       '重暈（玩家不會被重暈、沒有自己的重暈條：當作暈眩回復）'),
    'daze_enemies_on_block_with_shield_raised_chance_%': ('mapped', [('daze.on_shield_block', 'FLAT', [])], '眩暈（舉盾格擋）'),
    'physical_spell_damage_can_pin_on_critical_hit': ('mapped', [('flag.spell_crit_pin', 'FLAT', [])], '釘住'),
    'break_%_armour_on_pin': ('mapped', [('pin.armour_break', 'FLAT', [])], '釘住'),
    'enemies_are_maimed_for_x_seconds_after_becoming_unpinned': ('mapped', [('pin.maim_after', 'FLAT', [])], '釘住後癱瘓（秒）'),
    # ---- 危害、碎地 ----
    'hazard_hit_damage_immobilisation_multiplier_+%': ('mapped', [(k, 'INCREASED', ['hazard']) for k in IMMOBILISE],
                                                       '危害（禁錮 = 釘住、凍結、電擊的累積）'),
    'hazard_rearm_%_chance': ('approx', [('skill.duration', 'INCREASED', ['hazard'])],
                              '危害（我們的危害是一直有效的區域，不會被踩掉：重新啟動的機率當作危害持續時間）'),
    'hazard_base_debuff_slow_magnitude_+%': ('mapped', [('slow.magnitude', 'INCREASED', ['hazard'])], '危害的減速'),
    'jagged_ground_enemy_damage_taken_+%': ('mapped', [('jagged.taken', 'FLAT', [])], '碎地'),
    # ---- 殘影 ----
    'remnant_effect_+%': ('approx', [('remnant.effect', 'INCREASED', [])], '殘影（給戰星的：效果當作多一顆的機率；回復照乘）'),
    # ---- 戰旗、榮耀 ----
    'base_banner_resist_all_elements_%_to_apply': ('mapped', [('banner.resist', 'FLAT', [])], '戰旗'),
    'chance_to_not_consume_glory_%': ('mapped', [('glory.keep_chance', 'FLAT', [])], '榮耀'),
    'on_banner_expiry_recover_%_of_required_glory': ('approx', [('banner.glory_refund', 'FLAT', [])],
                                                     '戰旗（我們的戰旗是立即的增益：放下時就回復）'),
    # ---- 凋零、阻礙、致盲、癱瘓、曝露 ----
    'minions_have_%_chance_to_inflict_wither_on_hit': ('mapped', [('withered.minion_chance', 'FLAT', [])], '凋零'),
    'withered_enemies_deal_+%_damage': ('mapped', [('withered.weaken', 'FLAT', [])], '凋零（negate：減少寫成正的）'),
    'enemies_near_marked_enemy_are_blinded': ('mapped', [('flag.mark_blind_nearby', 'FLAT', [])], '致盲'),
    'immune_to_exposure': ('approx', [('flag.exposure_immune', 'FLAT', [])], '曝露（怪物不會對玩家施加曝露：等於已經免疫）'),
    'deflected_hits_cannot_directly_inflict_maim_on_self': ('approx', [('flag.deflect_no_maim', 'FLAT', [])],
                                                            '癱瘓（怪物不會讓玩家癱瘓：等於已經是這樣）'),
    'withered_effect_on_self_+%': ('approx', [('withered.self_effect', 'INCREASED', [])], '凋零（怪物不會讓玩家凋零：等於已經是這樣）'),
    # ---- 存在範圍內的敵人 ----
    'enemies_in_presence_life_regeneration_+%': ('mapped', [('presence.enemy_regen', 'INCREASED', [])], '存在範圍內的敵人（有回復詞綴的怪）'),
    'enemies_in_presence_cooldown_recovery_+%': ('approx', [('presence.enemy_cooldown', 'INCREASED', [])],
                                                 '存在範圍內的敵人（只有 Boss 招式有冷卻）'),
    # ---- 怒氣 ----
    'gain_max_rage_on_rage_gain_from_hit_%_chance': ('mapped', [('rage.fill_chance', 'FLAT', [])], '怒氣'),
    'rage_gained_on_life_flask_use': ('mapped', [('rage.on_life_flask', 'FLAT', [])], '怒氣'),
    # ---- 招架 ----
    'active_block_maximum_distance_+%': ('approx', [('skill.area', 'INCREASED', ['parry'])],
                                         '招架（我們的招架擋得下任何距離的擊中，沒有招架距離：當作招架橫掃的範圍）'),
    'parry_successfully_parrying_melee_attack_gives_damage_+%_to_your_next_ranged_attack': (
        'mapped', [('parry.next_ranged', 'FLAT', [])], '招架（近的算近戰）'),
    'parry_successfully_parrying_projectile_gives_damage_+%_to_your_next_melee_attack': (
        'mapped', [('parry.next_melee', 'FLAT', [])], '招架（遠的算投射物）'),
    # ---- 護符 ----
    'charms_%_chance_on_use_to_use_another_charm_without_consuming_charges': ('mapped', [('charm.chain_chance', 'FLAT', [])], '護符'),
    'recover_%_maximum_mana_on_charm_use': ('mapped', [('charm.mana_on_use', 'FLAT', [])], '護符'),
    'primalist_charm_charges_gained_+%_final': ('mapped', [('charm.charges_gained', 'MORE', [])], '護符'),
    # ---- 主要寶石、連擊、捷光環、弩、榴彈、疾速 ----
    'invocation_spell_chance_to_cost_half_energy_%': ('mapped', [('meta.energy_refund', 'FLAT', ['invocation'])],
                                                      'Invocation（一半能量 = 觸發時退還一半）'),
    'combo_finisher_damage_+%_up_to_40%': ('mapped', [('combo.consumed_damage', 'FLAT', [])], '連擊（用掉的）'),
    'additional_critical_strike_chance_permyriad_with_herald_skills': ('mapped', [('crit.base_extra', 'FLAT', ['herald'])],
                                                                       '捷光環的基礎暴擊率（permyriad）'),
    '%_chance_for_crossbow_reload_to_be_instant': ('mapped', [('crossbow.instant_reload', 'FLAT', [])], '弩'),
    'grenade_skill_cooldown_count_+': ('mapped', [('grenade.cooldown_uses', 'FLAT', ['grenade'])], '榴彈'),
    'grenade_skill_%_chance_to_explode_twice': ('mapped', [('grenade.twice_chance', 'FLAT', ['grenade'])], '榴彈'),
    'chance_to_gain_onslaught_on_kill_with_axes_%': ('mapped', [('onslaught.on_kill_tagged', 'FLAT', ['axe'])], '疾速（斧擊殺）'),
    # ---- 夥伴 ----
    'companion_onslaught_on_kill_%': ('mapped', [('companion.onslaught_on_kill', 'FLAT', [])], '夥伴（擊殺時疾速）'),
    'companions_gain_your_strength': ('approx', [('flag.companion_strength', 'FLAT', [])], '夥伴（召喚物不吃屬性：只給力量的生命）'),
    'companion_damage_increases_and_reductions_also_affects_you': ('mapped', [('flag.companion_damage_to_you', 'FLAT', [])], '夥伴傷害'),
    'life_leeched_from_hits_also_leeches_same_amount_to_companions': ('approx', [('flag.companion_leech', 'FLAT', [])],
                                                                      '夥伴（召喚物沒有偷取：直接回復同樣多）'),
    'companion_takes_%_damage_before_you': ('approx', [('companion.takes_damage', 'FLAT', [])], '夥伴（夥伴至少留 1 點生命）'),
    'life_recovery_from_flasks_applies_to_companions': ('approx', [('flag.companion_flask', 'FLAT', [])], '夥伴（藥劑的回復量一次給夥伴）'),
    # ---- 植物 ----
    'chance_for_plants_to_be_overgrown_%': ('approx', [('damage', 'INCREASED', ['plant'])],
                                            '植物（我們的植物沒有茂生：立即茂生的機率當作植物傷害增加）'),
    # ---- 裝備欄位 ----
    'base_body_armour_permyriad_to_gain_as_physical_thorns_damage': ('mapped', [('body.armour_thorns', 'FLAT', [])],
                                                                     '身體護甲的荊棘（permyriad）'),
    'maximum_energy_shield_from_body_armour_+%': ('mapped', [('body.es', 'INCREASED', [])], '身體護甲'),
    'body_armour_evasion_rating_+%': ('mapped', [('body.evasion', 'INCREASED', [])], '身體護甲'),
    'armour_+_from_%_strength_requirements_from_boots_gloves_helmets': ('mapped', [('armour.from_str_requirement', 'FLAT', [])],
                                                                        '護甲（鞋子、手套、頭盔的力量需求）'),
}

# 要固定數值或倍率的（同 overrides.json：value 固定、scale 乘上 stat 的值）
# 2026-10-26 合併：德魯伊之怒（原初飢餓）、野湧咒、雙夥伴、自動祈喚由基石那邊（KeystoneKeys）做了，這裡不再寫
OVERRIDES = {
    # 「被你詛咒的敵人被阻礙，移動速度減少 15%」（stat 的值是 1）
    'enemies_you_curse_have_15%_hinder': [{'stat': 'curse.hinder', 'type': 'FLAT', 'value': 15}],
    # 祭品的最大生命：我們的祭品（祭柱）沒有生命、不會被打掉，當作祭品持續時間（一半；平衡相當）
    'offering_life_+%': [{'stat': 'skill.duration', 'type': 'INCREASED', 'scale': 0.5, 'tags': ['offering']}],
    # 「+1 戰旗上限」：我們的戰旗是立即的增益（不是插在地上的區域），多一支 ≈ 覆蓋更久：戰旗持續時間增加 25%
    'number_of_additional_banners_allowed': [{'stat': 'skill.duration', 'type': 'INCREASED', 'scale': 25, 'tags': ['banner']}],
    # 「離開戰旗範圍後增益多留 x 秒」：我們的戰旗增益跟著人走：多 x 秒 ÷ 基礎 8 秒 = 持續時間增加
    'banner_buff_lingers_for_X_seconds_after_leaving_area': [{'stat': 'skill.duration', 'type': 'INCREASED', 'scale': 12.5, 'tags': ['banner']}],
    # 「存在範圍內的友軍榮耀獲得增加」：召喚物不會累積榮耀：當作你的榮耀獲得增加（一半）
    'allies_in_presence_glory_generation_+%': [{'stat': 'glory.gain', 'type': 'INCREASED', 'scale': 0.5}],
}


# 「受統治者增益影響時…」：條件 archon（TreeConditions.ARCHON，TreeMechanics 接上）
CONDITIONAL = {
    'immune_to_freeze_chill_while_archon': [('flag.immune.freeze', 'FLAT', []), ('flag.immune.chill', 'FLAT', [])],
    'immune_to_shock_while_archon': [('flag.immune.shock', 'FLAT', [])],
    'immune_to_bleeding_while_archon': [('flag.immune.bleed', 'FLAT', [])],
    'energy_shield_delay_-%_while_affected_by_archon': [('es.delay', 'INCREASED', [])],
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
        entry['note'] = '2026-10-26 新機制：' + note
        changed += 1
    for sid, suggest in CONDITIONAL.items():
        entry = stat_map[sid]
        entry['status'] = 'conditional'
        entry['condition'] = 'archon'
        entry['suggest'] = [{'stat': s, 'type': t, 'tags': tags} for s, t, tags in suggest]
        entry['note'] = '2026-10-26 新機制：受統治者增益影響時' + ('（negate：加快寫成負的）' if sid.startswith('energy') else '')
        changed += 1
    for sid in OVERRIDES:
        entry = stat_map.get(sid)
        if entry is None:
            raise SystemExit('stat_map 沒有 ' + sid)
        if entry['status'] == 'new_mechanic':
            entry['status'] = 'approx'
            entry['note'] = '2026-10-26 新機制：寫在 overrides.json'
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(stat_map, f, ensure_ascii=False, indent=1)
        f.write('\n')
    opath = os.path.join(HERE, 'overrides.json')
    with open(opath, encoding='utf-8') as f:
        overrides = json.load(f)
    for sid, rule in OVERRIDES.items():
        overrides[sid] = rule
    with open(opath, 'w', encoding='utf-8') as f:
        json.dump(overrides, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print('stat_map 改了', changed, '條；overrides', len(OVERRIDES), '條')


if __name__ == '__main__':
    main()
