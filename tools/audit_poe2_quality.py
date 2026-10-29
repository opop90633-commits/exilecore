#!/usr/bin/env python3
"""2026-10-28：技能寶石的品質效果（data/gem_quality.yml 的一般品質）跟 PoE2 0.5 對照。

用法：python3 tools/audit_poe2_quality.py [RePoE poe2/data 目錄] [技能 id ...]（給技能 id 只印那幾個、連對的也印）

來源：RePoE poe2 skill_gems.json（寶石 → grants_skills）＋ skills.json 每個 stat set 的 static.quality_stats
（數值是每 1% 品質的千分之一單位：1000 = 每 1% 品質 +1）。RePoE 每顆寶石只列一般的品質效果；
poe2db 寶石頁 Additional Effects From Quality 的第二條是額外的品質效果（昇華・進階奇術才有），寫在 gem_quality.yml 的 advanced，這裡不對。
RePoE 漏掉、poe2db 有寫的（例如掠閃），寫在 POE2DB。

我們的技能 → PoE2 寶石：tools/apply_poe2_gem_tiers.py 的 SPEC（寶石名稱）；法器賦予的（source: weapon）照
tools/audit_poe2_skills.py 的 MAP、tools/audit_poe2_spells.py 的 main（RePoE 技能 → 寶石）。

換算（RULES：PoE2 屬性 → 我們的屬性、種類、倍數；OVERRIDE：某個技能自己的換法）：
  - 半徑 +x（0.1 格一單位）照 gem_quality.yml 開頭的約定「+0.1 格 ≈ +10% 範圍」→ skill.area INCREASED（只限 RADIUS_AREA 的技能：範圍真的會跟著變）
  - 毫秒 → 秒（圖騰、減益持續時間）
UNREP：原作的品質效果我們的引擎沒有對應屬性（照舊用通用規則，平衡等效）——列出原因。
  2026-10-28：這次之前的 112 顆 UNREP 都照原作接上了引擎（OVERRIDE_Q）；做不到一模一樣的照平衡等效寫（APPROX，列出原因）。
印出：OK／APPROX（照平衡等效，原因在 APPROX）／MISMATCH／MISSING（表上沒寫但可以照原作寫）／UNREP／NONE（原作這顆的品質沒有效果，表上要寫 []）／EXTRA（表上有、原作沒有對應）。
"""
import importlib.util
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / 'tools'
SKILLS = ROOT / 'src/main/resources/data/skills.yml'
QUALITY = ROOT / 'src/main/resources/data/gem_quality.yml'
DEFAULT_REPOE = '/tmp/claude-0/-home-claude/c46e873f-cded-5f11-aa2c-868ac53d5e12/scratchpad/repoe/poe2/data'

# PoE2 屬性 → (我們的屬性, 種類, 倍數, 標籤)：每 1% 品質 = RePoE 值 / 1000 × 倍數
RULES = {
    'attack_speed_+%': ('attack.speed', 'INCREASED', 1, None),
    'base_cooldown_speed_+%': ('cooldown.recovery', 'INCREASED', 1, None),
    'base_number_of_projectiles': ('skill.projectiles', 'FLAT', 1, None),
    'number_of_chains': ('skill.chains', 'FLAT', 1, None),
    'base_projectile_speed_+%': ('skill.projectile_speed', 'INCREASED', 1, None),
    'active_skill_cast_speed_+%_final': ('cast.speed', 'MORE', 1, None),
    'base_cast_speed_+%': ('cast.speed', 'INCREASED', 1, None),
    'active_skill_critical_strike_chance_+%_final': ('crit.chance', 'MORE', 1, None),
    'active_skill_ignite_chance_+%_final': ('ignite.chance', 'MORE', 1, None),      # 易燃幅度
    'active_skill_ignite_effect_+%_final': ('ignite.magnitude', 'MORE', 1, None),
    'active_skill_ignite_duration_+%_final': ('ignite.duration', 'MORE', 1, None),
    'active_skill_chill_effect_+%_final': ('chill.effect', 'MORE', 1, None),
    'active_skill_chill_as_though_damage_+%_final': ('chill.effect', 'MORE', 1, None),   # 跟寒晶雷的 modifiers 同一種寫法
    'active_skill_lightning_damage_+%_final': ('damage', 'MORE', 1, ['lightning']),
    'active_skill_shock_chance_+%_final': ('shock.chance', 'MORE', 1, None),
    'shock_duration_+%': ('shock.duration', 'INCREASED', 1, None),
    'active_skill_shock_duration_+%_final': ('shock.duration', 'MORE', 1, None),
    'shock_effect_+%': ('shock.effect', 'INCREASED', 1, None),
    'active_skill_hit_damage_freeze_multiplier_+%_final': ('freeze.buildup', 'MORE', 1, None),
    'active_skill_poison_effect_+%_final': ('poison.magnitude', 'MORE', 1, None),
    'active_skill_minion_hit_damage_stun_multiplier_+%_final': ('stun.buildup', 'MORE', 1, None),
    'active_skill_minion_damage_+%_final': ('damage', 'MORE', 1, None),
    'active_skill_minion_life_+%_final': ('life.max', 'MORE', 1, None),
    'minion_maximum_life_%_to_gain_as_maximum_energy_shield': ('minion.life_as_es', 'FLAT', 1, None),
    'curse_effect_+%': ('curse.effect', 'INCREASED', 1, None),
    'aura_effect_+%': ('aura.effect', 'INCREASED', 1, None),
    'base_reservation_efficiency_+%': ('aura.reservation', 'INCREASED', -1, None),   # 效率增加 = 保留減少（SkillBuild.reservationFactor）
    'energy_generated_+%': ('meta.energy_gain', 'INCREASED', 1, None),
    'skill_effect_duration_+%': ('skill.duration', 'INCREASED', 1, None),
    'base_totem_duration': ('totem.duration_flat', 'FLAT', 0.001, None),                 # 毫秒 → 秒
    'active_skill_area_of_effect_+%_final': ('skill.area', 'MORE', 1, None),
    'armour_break_amount_+%': ('armour.break', 'INCREASED', 1, None),
    'base_chance_to_daze_%': ('daze.chance', 'FLAT', 1, None),
    'attacks_chance_to_blind_on_hit_%': ('blind.chance', 'FLAT', 1, None),
    'inflict_exposure_on_hit_%_chance': ('exposure.chance', 'FLAT', 1, None),
    'base_number_of_crossbow_bolts': ('crossbow.clip', 'FLAT', 1, None),
    'melee_range_+': ('melee.range', 'FLAT', 1, None),                                   # 同樣 0.1 格一單位
    'gain_x_rage_on_attack_hit': ('rage.on_hit', 'FLAT', 1, None),
    'active_skill_damage_+%_final_vs_chilled_enemies': ('damage.vs_chilled', 'MORE', 1, None),
    'active_skill_damage_+%_final_vs_burning_enemies': ('damage.vs_ignited', 'MORE', 1, None),
    'active_skill_damage_+%_final_vs_immobilised_enemies': ('damage', 'MORE', 1, ['vs_immobilised']),
    'active_skill_damage_+%_final_vs_fully_broken_armour': ('damage', 'MORE', 1, ['vs_brokenarmour']),
    'active_skill_base_physical_damage_%_to_gain_as_cold': ('damage.gain.cold', 'FLAT', 1, None),
    'base_reduce_enemy_cold_resistance_%': ('penetration', 'FLAT', 1, ['cold']),
    'base_reduce_enemy_lightning_resistance_%': ('penetration', 'FLAT', 1, ['lightning']),
    'chance_to_gain_1_more_charge_%': ('charge.extra', 'FLAT', 1, None),
    'base_chance_to_not_consume_corpse_%': ('corpse.save_chance', 'FLAT', 1, None),
    'spell_skills_fire_2_additional_projectiles_final_chance_%': ('skill.projectile_chance_two', 'FLAT', 1, None),
    'perfect_timing_window_ms_+%': ('perfect_timing.window', 'INCREASED', 1, None),
    'recover_%_maximum_mana_on_cull': ('mana.on_cull', 'FLAT', 1, None),
    'active_skill_electrocutes_as_though_dealt_damage_+%_final': ('electrocute.buildup', 'MORE', 1, None),
    'projectile_number_to_split': ('skill.split', 'FLAT', 1, None),
    'twister_%_chance_for_additional_twister': ('twister.extra_chance', 'FLAT', 1, None),
    'lightning_rod_%_chance_for_additional_burst_on_landing': ('lightning_rod.extra_burst', 'FLAT', 1, None),
    'tornado_shot_projectile_damage_+%_final': ('tornado.copy_more', 'FLAT', 1, None),
    'sandstorm_swipe_storm_damage_+%_final_per_stage': ('whirl.collapse_more_per_stage', 'FLAT', 1, None),
    'active_skill_pins_as_though_dealt_damage_+%_final': ('pin.as_though_damage', 'FLAT', 1, None),
    'chance_%_to_spawn_another_infusion_remnant': ('snap.extra_remnant', 'FLAT', 1, None),
    'base_number_of_earthquakes_allowed': ('limit.jagged', 'FLAT', 1, None),
    'skill_base_oil_exposure_-_to_total_elemental_resistance': ('oil.exposure', 'FLAT', 1, None),
    'base_number_of_raging_spirits_allowed': ('skill.minions', 'FLAT', 1, None),
    'skill_wolf_pack_size': ('skill.minions', 'FLAT', 1, None),
    'consume_frenzy_power_and_endurance_charge_every_x_ms': ('grant.charge.regulation_consume', 'FLAT', 0.001, None),
    # 戰吼（stats/WarcryKeys.java）
    'infernal_cry_exerted_attack_all_damage_%_to_gain_as_fire_%': ('warcry.empower_gain_fire', 'FLAT', 1, None),
    'seismic_cry_slam_skill_aftershock_damage_+%_final': ('warcry.aftershock_more', 'FLAT', 1, None),
    'ancestral_cry_duration_+%_final_per_removable_endurance_charge': ('warcry.duration_per_endurance', 'FLAT', 1, None),
    'wolf_warcry_buff_freeze_buildup_+%': ('warcry.ally_freeze_buildup', 'FLAT', 1, None),
    'bear_warcry_gain_X_centirage_per_enemy_power': ('warcry.rage_per_power', 'FLAT', 0.01, None),   # 百分之一點怒氣
    'warcry_grant_damage_+%_to_exerted_attacks': ('socketed_warcry.empowered_damage', 'FLAT', 1, None),
    'wolf_lunar_blessing_all_damage_%_to_gain_as_cold_damage': ('grant.damage.extra.cold', 'FLAT', 1, None),
}
RADIUS = ('active_skill_base_area_of_effect_radius', 'active_skill_base_secondary_area_of_effect_radius')
# 半徑換成範圍（照 gem_quality.yml 的約定）的技能：範圍真的跟著 skill.area 變
RADIUS_AREA = {'caustic_arrow', 'detonate_dead', 'firestorm', 'prism_rend', 'blood_harvest', 'hammer_of_gods', 'explosive_spear',
               'ball_lightning', 'thrashing_vines', 'volcano', 'glacial_lance', 'toxic_domain', 'bone_blast', 'storm_wave', 'dark_idol',
               'cross_slash'}
# 某個技能自己的換法：{技能: {PoE2 屬性: (我們的屬性, 種類, 倍數, 標籤)}}
OVERRIDE = {
    'fortifying_cry': {'active_skill_base_area_of_effect_radius': ('warcry.trigger_radius_flat', 'FLAT', 0.1, None)},  # 0.1 格 → 格
    'snipe': {'active_skill_base_area_of_effect_radius': ('skill.explosion_radius', 'FLAT', 0.1, None)},
    'blood_offering': {'active_skill_base_area_of_effect_radius': ('warcry.radius_flat', 'FLAT', 0.1, None)},
    'reap': {'base_skill_effect_duration': ('crit_weakness.seconds', 'FLAT', 0.001, None)},
    'essence_drain': {'base_skill_effect_duration': ('debuff.duration', 'FLAT', 0.001, None)},
    'soulrend': {'base_skill_effect_duration': ('debuff.duration', 'FLAT', 0.001, None)},
    # 旋風 +2 秒：我們沒有「+秒」的技能持續時間，換算成 8 秒的 +25%
    'primal_tornado': {'base_skill_effect_duration': ('skill.duration', 'INCREASED', 0.001 / 8 * 100, None)},
    # 瘴苞雨的「投射物」跟「上限」是同一條；上限（number_of_poisonbloom_arrow_bloom_allowed）沒有對應，見 UNREP_PART
    # 2026-10-28：驚雷劈每用掉一顆銳星更多 x%（加在 50% 上，SkillExecutor.thunderBolts）
    'falling_thunder': {'lightning_strike_damage_+%_final_per_power_charge': ('falling_thunder.per_charge_more', 'FLAT', 1, None)},
}
# RePoE 漏掉、poe2db 寶石頁有的一般品質效果（每 1% 品質的值，已經是千分之一單位）
POE2DB = {
    'blink': [('base_cooldown_speed_+%', 500)],   # poe2db Blink：(0—10)% increased Cooldown Recovery Rate
    'skeletal_pyromancer': [('base_number_of_projectiles', 100)],   # poe2db Skeletal Arsonist：Fires +(0—2) Projectiles（RePoE 只有占位）
}
# apply_poe2_gem_tiers.SPEC 沒有、也不在攻擊／法術對照表的寶石（我們的代號 → PoE2 名稱）
NAMES = {'parry': 'Parry'}
# 原作有、我們的引擎沒有對應屬性的（照舊用通用規則）→ 原因
UNREP = {
}
# 2026-10-28：這次照原作接上的（這次之前列在 UNREP，照通用規則）：{技能: {PoE2 屬性: (我們的屬性, 種類, 倍數, 標籤) 或好幾條的 list，
#   'MERGED' = 跟同一顆的第一條是我們的同一個屬性（素痕湧三種元素共用一個機率）}}。屬性鍵見 stats/GemQualityKeys.java；
#   effect.＊ 是加在增益本身的數值上（SkillBuild.withQualityEffects）。
OVERRIDE_Q = {
    'killing_palm': {'recover_%_maximum_mana_on_cull': ('mana.on_cull_over_time', 'FLAT', 1, None)},   # 斷魂掌：撲殺後 4 秒內回復（master 的新屬性）
    'alchemists_boon': {'skill_alchemists_boon_generate_x_charges_for_any_flask_per_minute': [('effect.flask.charges_per_minute.life', 'FLAT', 1.0, None), ('effect.flask.charges_per_minute.mana', 'FLAT', 1.0, None)]},
    'attrition': {'skill_attrition_culling_strike_at_x_or_more_stacks': ('attrition.cull', 'FLAT', 1.0, None)},
    'barkskin': {'barkskin_armour_to_gain_as_%_of_energy_shield_lost_to_enemy_hits': ('effect.barkskin.armour', 'FLAT', 1.0, None)},
    'bear_slam': {'active_skill_stun_threshold_+%_while_performing_action': ('action.stun_threshold', 'FLAT', 1.0, None)},
    'berserk': {'skill_base_rage_effect_+%_to_apply': ('effect.rage.effect', 'INCREASED', 1.0, None)},
    'blast_arrow': {'detonating_arrow_max_number_of_stages': ('charge.stages_flat', 'FLAT', 1.0, None)},
    'bloodhounds_mark': {'hunters_mark_%_of_phys_damage_over_time_as_build_up': ('bloodhound.stun_percent', 'FLAT', 1.0, None)},
    'bone_cage': {'active_skill_pins_as_though_dealt_damage_+%_final': ('pin.as_though_damage', 'FLAT', 1.0, None)},
    'bone_spikes': {'base_skill_effect_duration': ('skill.base_duration_flat', 'FLAT', 0.001, None), 'minion_movement_speed_+%': ('movement.speed', 'INCREASED', 1.0, ['minion'])},
    'briarpatch': {'spells_chance_to_hinder_on_hit_%': ('effect.hinder.on_hit', 'FLAT', 1.0, ['spell'])},
    'combat_frenzy': {'chance_to_gain_1_more_charge_%': [('effect.charge.event_extra.freeze', 'FLAT', 2.0, None), ('effect.charge.event_extra.electrocute', 'FLAT', 2.0, None), ('effect.charge.event_extra.pin', 'FLAT', 2.0, None)]},
    'comet': {'base_spell_%_chance_to_echo': ('spell.echo_chance', 'FLAT', 1.0, None)},
    # 2026-10-28：瘟疫改成單體（SkillExecutor.singleImpact），多感染的敵人照原作是多一個目標；這次之前列在 APPROX（範圍落點各多落一下）
    'contagion': {'contagion_number_of_additional_targets': ('skill.extra_targets', 'FLAT', 1.0, None)},
    'convalescence': {'base_skill_effect_duration': ('convalescence.duration', 'FLAT', 0.001, None)},
    'devour': {'wyvern_devour_flask_charm_charge_gain_chance_%': ('corpse.flask_charge_chance', 'FLAT', 1.0, None)},
    'discipline': {'base_skill_buff_energy_shield_recharge_rate_+%_to_apply': ('effect.es.recharge', 'INCREASED', 1.0, None)},
    'disengage': {'disengage_%_chance_for_additional_shockwave': ('shockwave.extra_chance', 'FLAT', 1.0, None)},
    'elemental_conflux': {'skill_elemental_conflux_active_element_damage_+%_final': [('effect.damage', 'MORE', 1.0, ['fire', 'if_conflux:fire']), ('effect.damage', 'MORE', 1.0, ['cold', 'if_conflux:cold']), ('effect.damage', 'MORE', 1.0, ['lightning', 'if_conflux:lightning'])]},
    'elemental_trinity': {'trinity_skill_speed_+%_while_all_resonance_is_at_least_250_to_grant': [('effect.attack.speed@trinityhigh', 'INCREASED', 1.0, None), ('effect.cast.speed@trinityhigh', 'INCREASED', 1.0, None)]},
    'ember_volley': {'ember_fusillade_damage_+%_final_per_ember_fired': ('ember.more_per_previous', 'FLAT', 1.0, None)},
    'entangle': {'skill_additional_fissure_chance_%': ('skill.additional_fissure_chance', 'FLAT', 1.0, None)},
    'eternal_rage': {'ceaseless_rage_base_rage_regeneration_per_minute': ('effect.rage.regen', 'FLAT', 0.016667, None)},
    'exsanguinate': {'blood_tendrils_beam_count': ('skill.chains', 'FLAT', 1.0, None)},
    'fangs_of_frost': {'chance_to_not_consume_parried_%': ('parried.keep_chance', 'FLAT', 1.0, None)},
    'flame_breath': {'channelled_skill_suppress_ongoing_rage_cost_for_first_X_ms': ('charge.rage_free_seconds', 'FLAT', 0.001, None)},
    'freezing_mark': {'freezing_mark_hit_damage_freeze_multiplier_+%_final': ('effect.freeze.buildup_taken', 'MORE', 1.0, None)},
    'frost_aegis': {'maximum_number_of_arctic_armour_stationary_stacks': ('arctic.stages', 'FLAT', 1.0, None)},
    'frost_mine': {'active_skill_all_elemental_exposure_compounding_magnitude_cap': ('exposure.cap_flat', 'FLAT', 1.0, None)},
    'frostbolt': {'active_skill_projectile_damage_+%_final_if_pierced_enemy': ('projectile.pierced_more', 'FLAT', 1.0, None)},
    'fulmination': {'skill_wrath_aura_shared_damage_+%_to_other_shocked_enemies_in_presence': ('fulmination.less', 'FLAT', -1.0, None)},
    'gale_sweep': {'wind_blast_damage_+%_final_from_distance': ('damage.close_more', 'FLAT', 1.0, None)},
    'grim_feast': {'grim_remnant_health_stored_in_globe_%': ('grim.share', 'FLAT', 1.0, None)},
    'herald_of_ash': {'herald_of_ash_burning_%_overkill_damage_per_minute': ('herald.ash_overkill', 'FLAT', 0.016667, None)},
    'herald_of_blood': {'skill_herald_bleeding_enemies_explode_for_%_blood_loss_as_unscalable_physical_damage': ('herald.blood_share', 'FLAT', 1.0, None)},
    'herald_of_ice': {'active_skill_base_area_of_effect_radius': ('herald.ice_radius', 'FLAT', 0.1, None)},
    'herald_of_plague': {'hinder_chance_%_on_spreading_poioson': ('herald.plague_hinder', 'FLAT', 1.0, None)},
    'herald_of_thunder': {'herald_of_thunder_storm_max_hits': ('herald.thunder_hits', 'FLAT', 1.0, None)},
    'hex_burst': {'hexblast_damage_+%_final_per_second_remaining_curse_duration': ('hexblast.more_per_second', 'FLAT', 1.0, None)},
    'hunters_mark': {'enemy_additional_critical_strike_multiplier_against_self': ('effect.crit.bonus_taken', 'INCREASED', 1.0, None)},
    'impurity': {'base_skill_buff_chaos_damage_resistance_%_to_apply': ('effect.resist.chaos', 'FLAT', 1.0, None)},
    'incendiary_shot': {'ignite_duration_+%_per_shotgun_pellet_hit': ('ignite.duration_per_pellet', 'FLAT', 1.0, None)},
    'iron_ward': {'active_skill_hit_damage_stun_multiplier_+%_final': ('stun.buildup', 'MORE', 1.0, None)},
    'lingering_illusion': {'chance_%_to_create_additional_remnant': ('effect.charge.remnant_extra', 'FLAT', 1.0, None)},
    'linked_volley': {'empower_barrage_damage_-%_final_with_repeated_projectiles': ('barrage.less', 'FLAT', 1.0, None)},
    'magma_barrier': {'skill_igneous_shield_grants_block_chance_+%': ('effect.block.chance', 'INCREASED', 1.0, None)},
    'malice': {'base_skill_effect_duration': ('malice.weakness_seconds', 'FLAT', 0.001, None)},
    'mana_drain': {'mana_drain_base_mana_leech_amount': ('mana.on_hit', 'FLAT', 1.0, None)},
    'mana_storm': {'mana_tempest_effects_linger_X_ms': ('mana_tempest.linger', 'FLAT', 0.001, None)},
    'meta_elemental_invocation': {'generic_ongoing_trigger_maximum_energy': ('meta.max_energy_flat', 'FLAT', 1.0, None)},
    'mirror_of_refraction': {'base_number_of_projectiles': ('mirror.copies', 'FLAT', 1.0, None)},
    'piercing_rounds': {'active_skill_projectile_damage_+%_final_if_pierced_enemy': ('projectile.pierced_more', 'FLAT', 1.0, None)},
    'plague_bearer': {'plague_bearer_gains_%_of_damage_from_inflicted_poisons': ('plague_bearer.percent', 'FLAT', 1.0, None)},
    'pounce': {'skill_wolf_mark_damage_taken_+%_cap': ('pounce.mark_cap', 'FLAT', 1.0, None), 'skill_wolf_mark_damage_taken_+%_per_nearby_enemy': ('pounce.mark_per_enemy', 'FLAT', 1.0, None)},
    'purity_of_fire': {'base_skill_buff_fire_damage_resistance_%_to_apply': ('effect.resist.fire', 'FLAT', 1.0, None)},
    'purity_of_ice': {'base_skill_buff_cold_damage_resistance_%_to_apply': ('effect.resist.cold', 'FLAT', 1.0, None)},
    'purity_of_lightning': {'base_skill_buff_lightning_damage_resistance_%_to_apply': ('effect.resist.lightning', 'FLAT', 1.0, None)},
    'rain_of_arrows': {'rain_of_arrows_arrow_speed_+%': ('skill.projectile_speed', 'INCREASED', 1.0, None)},
    'raise_zombie': {'raise_zombie_empowerment_effect_+%': ('zombie.empower_effect', 'FLAT', 1.0, None)},
    'rake': {'base_additional_damage_from_distance_+%_final': ('charge.distance_more', 'FLAT', 1.0, None)},
    'rampage': {'channelled_skill_suppress_ongoing_rage_cost_for_first_X_ms': ('charge.rage_free_seconds', 'FLAT', 0.001, None)},
    'rapid_fire': {'maximum_number_of_crossbow_heat_stacks': ('crossbow.clip', 'FLAT', 1.0, None)},
    'rapid_thrust': {'rapid_assault_%_chance_to_attach_additional_spear': ('spear_tip.extra_chance', 'FLAT', 1.0, None)},
    'ravenous_swarm': {'active_skill_base_area_of_effect_radius': ('swarm.radius', 'FLAT', 0.1, None)},
    'rolling_magma': {'active_skill_damage_+%_final_per_time_chained': ('damage.more_per_chain', 'FLAT', 1.0, None)},
    'rolling_slam': {'active_skill_stun_threshold_+%_while_performing_action': ('action.stun_threshold', 'FLAT', 1.0, None)},
    'ruin_mantra': {'active_skill_required_number_of_combo_stacks': ('mantra.combo', 'FLAT', 1.0, None)},
    'sacrifice': {'harvester_minion_resummon_speed_+%_final': ('sacrifice.slower', 'FLAT', 1.0, None)},
    'savage_fury': {'wild_beast_damage_+%_final': ('effect.damage', 'MORE', 1.0, None)},
    'serpent_fury': {'active_skill_knockback_distance_+%_final': ('skill.knockback', 'MORE', 1.0, None)},
    'shield_rush': {'base_additional_damage_from_distance_+%_final': ('charge.distance_more', 'FLAT', 1.0, None)},
    'shield_wall': {'shield_wall_damage_+%_final_when_slammed': ('wall.self_more', 'FLAT', 1.0, None)},
    'shock_arrow': {'electrocuting_arrow_%_damage_gained_as_extra_lightning_on_debuffed_target': ('rod.gain_lightning', 'FLAT', 1.0, None)},
    'siphon_elements': {'infusion_remnants_%_chance_to_spawn_cold_infusion_on_freezing_an_enemy': ('effect.infusion.remnant_on_ailment', 'FLAT', 1.0, None), 'infusion_remnants_%_chance_to_spawn_fire_infusion_on_igniting_an_enemy': 'MERGED', 'infusion_remnants_%_chance_to_spawn_lightning_infusion_on_shocking_an_enemy': 'MERGED'},
    'skeletal_cleric': {'skeletal_cleric_revived_skeletons_immune_for_X_ms': ('cleric.revive_immune', 'FLAT', 0.001, None)},
    'skeletal_pyromancer': {'base_number_of_projectiles': ('minion.extra_projectiles', 'FLAT', 1.0, None)},
    'skeletal_reaver': {'minion_rage_effect_+%': ('minion.rage_effect', 'FLAT', 1.0, None)},
    'soul_offering': {'base_skill_effect_duration': ('skill.base_duration_flat', 'FLAT', 0.001, None)},
    'spear_thicket': {'hazard_rearm_%_chance': ('hazard.rearm_chance', 'FLAT', 1.0, None)},
    'spiraling_conspiracy': {'base_skill_area_of_effect_+%': ('skill.area', 'INCREASED', 1.0, None)},
    'stampede': {'slam_aftershock_chance_%': ('slam.aftershock_chance', 'FLAT', 1.0, None)},
    'storm_javelin': {'base_number_of_overcharged_spears_allowed': ('lance.overcharged_extra', 'FLAT', 1.0, None)},
    'sunder': {'accuracy_range_penalty_+%': ('accuracy.range_penalty', 'INCREASED', 1.0, None)},
    'sunfire_spear': {'chance_to_retain_40%_of_glory_on_use_%': ('glory.retain_chance', 'FLAT', 1.0, None)},
    'supercharged_slam': {'channelled_slam_max_stages': ('charge.stages_flat', 'FLAT', 1.0, None)},
    'tempest_bell': {'tempest_bell_damage_+%_final_per_time_hit': ('bell.more_per_hit', 'FLAT', 1.0, None)},
    'thunder_leap': {'thunderous_leap_%_chance_for_shocked_ground_when_detonating': ('detonate.shocked_ground_chance', 'FLAT', 1.0, None)},
    'thunderstorm': {'thunderstorm_wet_debuff_hit_damage_freeze_multiplier_+%_final': ('drenched.freeze_more', 'FLAT', 1.0, None), 'thunderstorm_wet_debuff_shock_chance_on_+%_final': ('drenched.shock_more', 'FLAT', 1.0, None)},
    'time_of_need': {'skill_time_of_need_gain_x_life': ('effect.blessing.life', 'FLAT', 1.0, None)},
    'trail_of_caltrops': {'maximum_caltrops_allowed': ('caltrops.max', 'FLAT', 1.0, None)},
    'volcanic_fissure': {'slam_aftershock_chance_%': ('slam.aftershock_chance', 'FLAT', 1.0, None)},
    'voltaic_mark': {'thaumaturgist_mark_hit_damage_electrocute_multiplier_+%': ('effect.electrocute.buildup_taken', 'INCREASED', 1.0, None)},
    'walking_calamity': {'walking_calamity_non_skill_base_all_damage_%_to_gain_as_fire': ('effect.damage.extra.fire', 'FLAT', 1.0, None)},
    'whirlwind_lance': {'sandstorm_swipe_storm_damage_+%_final_per_stage': ('whirl.collapse_more_per_stage', 'FLAT', 1.0, None)},
    'wing_blast': {'wing_blast_chance_to_gain_power_charge_on_stun_%': ('charge.event.stun_primed.power', 'FLAT', 1.0, None)},
    'withering_presence': {'base_skill_effect_duration': ('withering.seconds', 'FLAT', 0.001, None)},
    'wyvern_rend': {'wyvern_devour_base_all_damage_%_to_gain_as_lightning': ('effect.damage.gain.lightning', 'FLAT', 1.0, None)},
}
# 2026-10-28：做不到一模一樣、照平衡等效的（APPROX）：{技能: (表上該寫的 [(stat, type, per, tags)], 原因)}；[] = 我們沒有那個機制，品質沒有效果
APPROX = {
    'dark_pact': ([('damage', 'MORE', 0.9615, ())], '冥契沒有犧牲召喚物生命：照原作 13% 的比例把 +2.5% 換成更多 19.2% 傷害'),
    'mana_remnants': ([('effect.mana.regen', 'INCREASED', 0.285, ()), ('effect.mana.on_kill', 'FLAT', 0.0142, ())], '魔泉湧沒有殘留物（魔力回復＋擊殺回魔）：照 20 級每顆 281 魔力的比例把 +40 換成兩者各多 14.2%'),
    'scavenged_plating': ([('effect.armour', 'INCREASED', 0.2, ()), ('effect.armour.break', 'INCREASED', 0.2, ())], '拾鱗是一直有的增益（沒有甲片層數）：8 秒 +2 秒的持續時間估平均多一成層數，換成護甲、破甲各增加 4%'),
    'summon_mount': ([('mount.life_more', 'FLAT', 1.0, ())], '坐騎打不死、沒有生命：更多 20% 最大生命換成騎著時的重暈門檻更多 20%'),
    'feast_of_flesh': ([('corpses.flat', 'FLAT', 0.15, ())], '噬屍宴固定算 3 具屍體（不用屍體）：品質多的 3 具照原作是超過 5 具的部分，身邊超過 5 具時才多吃'),
    'mirage_archer': ([('skill.base_duration_flat', 'FLAT', 0.05, ())], '幻弓影的幻影跟著每一次弓攻擊（沒有持續時間）：+1 秒換成你停手之後幻影再多射 1 秒'),
    'shatter_spikes': ([('spike.more_per_spike', 'FLAT', 0.3, ())], '地刺崩裂沒有實體的地刺：照最近 6 秒內用了幾次算地刺數（最多 6 根）'),
    'bone_offering': ([('offering.shield_lost_less', 'FLAT', 1.5, ())], '骸骨祭用物理減傷代替護盾：護盾「第一下超過最大生命 25% 的擊中」時算消失'),
    'beast_companion': ([], '沒有捕捉野獸的鬼火（原作這條只影響捕捉、不影響戰鬥）：沒有效果'),
    'bramble_arrow': ([], '荊棘矢沒有「植物中毒」：沒有效果'),
    'decompose': ([], '腐屍爆沒有毒雲：沒有效果'),
    'profane_ritual': ([('corpse.save_chance', 'FLAT', 0.5, ())], '2026-10-29：穢陣要屍體了，(0—10)% 不毀掉屍體照天賦樹同一個鍵（這次之前穢陣不用屍體：沒有效果）'),
    'sigil_of_power': ([], '力印是跟著你的增益（不用站在印裡），半徑沒有作用：沒有效果'),
    'vault_strike': ([], '躍棍崩擊沒有破勢：沒有效果'),
    'wind_dancer': ([], '風之舞步沒有被擊中時的疾風爆炸：沒有效果'),
}
# 原作有好幾條、只有其中幾條做得出來的 → 做不出來的那條
UNREP_PART = {
    'toxic_growth': ('number_of_poisonbloom_arrow_bloom_allowed', '膿苞上限：沒有做上限'),
}


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def targets(ours):
    """會掉、可以加品質的技能寶石：不是隱藏技能，也不是昇華、Boss 掉的（法器賦予的照樣有寶石）。"""
    return [k for k, v in ours.items() if not v.get('hidden') and v.get('source') in (None, 'weapon')]


def gem_of(skill, spec, rmap, byname, byskill):
    name = spec[skill][0] if skill in spec else NAMES.get(skill)
    if name:
        g = byname.get(name)
        if g is not None:
            return g
    return byskill.get(rmap.get(skill))


def poe2_lines(gem, sk):
    """這顆寶石的一般品質效果 [(PoE2 屬性, 每 1% 的千分之一值)]，照 grants_skills 的順序，重複的只算一次。"""
    out = []
    for sid in gem['grants_skills']:
        for stat_set in sk.get(sid, {}).get('stat_sets', []):
            for q in (stat_set.get('static') or {}).get('quality_stats') or []:
                for stat, value in q['stats'].items():
                    if stat != 'dummy_stat_display_nothing' and all(o[0] != stat for o in out):
                        out.append((stat, value))
    return out


def expected(skill, lines):
    """照 RULES／OVERRIDE 換成我們的 [(stat, type, per, tags)]；換不了的回傳 (None, 那條 PoE2 屬性)。"""
    out = []
    for stat, value in lines:
        if skill in UNREP_PART and UNREP_PART[skill][0] == stat:
            continue
        rule = OVERRIDE_Q.get(skill, {}).get(stat) or OVERRIDE.get(skill, {}).get(stat) or RULES.get(stat)
        if rule is None and stat in RADIUS and skill in RADIUS_AREA:
            rule = ('skill.area', 'INCREASED', 10, None)
        if rule is None:
            return None, stat
        if rule == 'MERGED':
            continue
        for ours, kind, factor, tags in (rule if isinstance(rule, list) else [rule]):
            out.append((ours, kind, round(value / 1000 * factor, 4), tuple(sorted(tags or ()))))
    return out, None


def ours_lines(entry):
    return [(e["stat"], e["type"], round(float(e["per"]), 4), tuple(sorted(e.get('tags') or ()))) for e in entry or []]


def main():
    args = sys.argv[1:]
    folder = args.pop(0) if args and Path(args[0]).is_dir() else DEFAULT_REPOE
    only = set(args)
    sk = json.load(open(Path(folder) / 'skills.json', encoding='utf-8'))
    gems = json.load(open(Path(folder) / 'skill_gems.json', encoding='utf-8'))
    byname, byskill = {}, {}
    for key, g in gems.items():
        if g.get('gem_type') == 'support' or (g.get('base_item') or {}).get('release_state') != 'released':
            continue
        name = g['base_item']['display_name']
        if 'Unique' not in key or name not in byname:   # 同名的獨特物品賦予版（UniqueEarthbound…）讓一般的優先
            byname[name] = g
        for sid in g['grants_skills']:
            byskill.setdefault(sid, g)
    spec = load('apply_poe2_gem_tiers').SPEC
    rmap = {k: v[0].split(':')[0] for k, v in load('audit_poe2_skills').MAP.items()}
    for k, v in load('audit_poe2_spells').S.items():
        if v.get('main'):
            rmap.setdefault(k, v['main'])
    ours = yaml.safe_load(open(SKILLS, encoding='utf-8'))['skills']
    table = yaml.safe_load(open(QUALITY, encoding='utf-8'))
    table.pop('advanced', None)
    counts = {}
    for skill in targets(ours):
        gem = gem_of(skill, spec, rmap, byname, byskill)
        if gem is None:
            status, detail = 'NOEQUIV', '找不到 PoE2 寶石（照通用規則）'
            if skill in table:
                status, detail = 'EXTRA', '表上有，但找不到 PoE2 寶石'
        else:
            lines = POE2DB.get(skill) or poe2_lines(gem, sk)
            have = ours_lines(table.get(skill)) if skill in table else None
            if skill in UNREP:
                status, detail = ('UNREP', UNREP[skill]) if have is None else ('EXTRA', '列在 UNREP 卻寫在表上')
            elif skill in APPROX:
                want = [(st, ty, round(pe, 6), tuple(tg)) for st, ty, pe, tg in APPROX[skill][0]]
                status = 'APPROX' if have is not None and sorted(have) == sorted(want) else 'MISMATCH'
                detail = APPROX[skill][1] if status == 'APPROX' else '表上 %s，平衡等效該寫 %s' % (have, want)
            elif not lines:
                status = 'OK' if have == [] else 'NONE'
                detail = '原作的品質沒有效果' + ('' if have == [] else '：表上要寫 []')
            else:
                want, bad = expected(skill, lines)
                if want is None:
                    status, detail = 'MISSING', '沒有換算規則：%s（加 RULES／OVERRIDE，或寫進 UNREP）' % bad
                elif have is None:
                    status, detail = 'MISSING', '應該是 %s' % want
                elif sorted(have) != sorted(want):
                    status, detail = 'MISMATCH', '表上 %s，原作換算 %s' % (have, want)
                else:
                    status, detail = 'OK', str(want)
                if skill in UNREP_PART and status == 'OK':
                    detail += '（%s 沒做：%s）' % UNREP_PART[skill]
            detail = '[%s] %s' % (gem['base_item']['display_name'], detail)
        counts[status] = counts.get(status, 0) + 1
        if (only and skill in only) or (not only and status not in ('OK', 'UNREP', 'NOEQUIV', 'APPROX')):
            print('%-9s %-26s %s' % (status, skill, detail))
    print('—— %d 個技能：%s' % (sum(counts.values()), '、'.join('%s %d' % kv for kv in sorted(counts.items()))))
    if not only:
        print('UNREP（照通用規則）：' + ' '.join(sorted(k for k in UNREP if k in ours)))
        print('APPROX（做不到一模一樣，照平衡等效；原因用技能代號查）：' + ' '.join(sorted(k for k in APPROX if k in ours)))
    return 0 if not any(counts.get(s) for s in ('MISMATCH', 'MISSING', 'NONE', 'EXTRA')) else 1


if __name__ == '__main__':
    sys.exit(main())
