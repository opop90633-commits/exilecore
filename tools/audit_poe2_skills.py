#!/usr/bin/env python3
"""2026-10-28：攻擊技能逐級數值跟 PoE2 0.5（RePoE／poe2db 1–40 級）對照。

用法：python3 tools/audit_poe2_skills.py [RePoE poe2/data 目錄] [技能 id ...]（給技能 id 只印那幾個、連對的也印）

對 skills.yml 每個 type: attack 的技能（法術另外對；tornado_shot 另外處理；2026-10-28 whirling_slash、gale_spear 也照這裡對了），照 MAP 找到 RePoE 的技能／stat set：
  - effectiveness ↔ damage_multiplier（%／100；沒寫＝100%）
  - mana ↔ costs.Mana（原始值，伺服器另外整體減半）
  - attack_speed ↔ 100% + attack_speed_multiplier
  - cooldown ↔ cooldown（毫秒／1000）
  - 其他每一個數字（含 level_modifiers、機制參數）拿去比對所有逐級屬性（換算：原值、/10、/100、/1000、1+x/100）。
    2026-10-28：level_modifiers 的 stage.代號（第二段的傷害效益 %，StageAttacks）對 stat set 的 damage_multiplier、
    limit.代號（逐級上限）對那個屬性；技能自己寫的 proc.事件 不是數值，略過。
[a, b] 兩個數字是 Scaled 的線性內插（1 級 a、20 級 b，往外推）；PoE2 是非線性的 40 級表時也算不一樣，要換成逐級表。
2026-10-28：分段傷害（skills.yml 的 stages）：STAGE_SETS 指定每一段對哪一個 stat set，effectiveness 要跟那組的 damage_multiplier 逐級一樣
（STAGE 列；寫成一個數字的要每一級都一樣），其他數字（半徑、次數、修飾器）照一般的方式對。
印出：OK／MISMATCH／INTERP（兩點內插不符逐級表）／UNMATCHED（找不到對應的屬性，人工看）。
"""
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / 'src/main/resources/data/skills.yml'
SKIP = {'tornado_shot'}   # 2026-10-28：whirling_slash、whirling_slash_collapse、gale_spear 照 PoE2 改完，拿掉略過

# 我們的技能 id → RePoE 來源（第一個是主要的：傷害效益、魔力、攻速、冷卻照它；「技能:statset」指定 stat set）
MAP = {
    'leap_slam': ['LeapSlamPlayer'],
    # 2026-10-28：旋矛擊（揮的那一圈的半徑＝旋風那組的 1.8 格）、旋風崩散（Whirlwind 那組）、旋風矛
    'whirling_slash': ['WhirlingSlashPlayer', 'WhirlingSlashPlayer:WhirlingSlashSandstormPlayer'],
    'whirling_slash_collapse': ['WhirlingSlashPlayer:WhirlingSlashSandstormPlayer', 'WhirlingSlashPlayer'],
    'gale_spear': ['TwisterPlayer'],
    'earthquake': ['EarthquakePlayer', 'EarthquakePlayer:EarthquakeAftershockPlayer'],
    'boneshatter': ['BoneshatterPlayer'],
    'ancestral_totem': ['AncestralWarriorTotemPlayer'],
    'caustic_arrow': ['GasArrowPlayer'],
    'rain_of_arrows': ['RainOfArrowsPlayer'],
    'lightning_arrow': ['LightningArrowPlayer'],
    'escape_shot': ['EscapeShotPlayer', 'EscapeShotIceFragmentPlayer'],
    'raise_guard': ['ShieldBlockPlayer'],
    'ice_shot': ['IceShotPlayer'],
    'explosive_bolt': ['ExplosiveShotPlayer', 'ExplosiveShotAmmoPlayer'],
    'permafrost_bolts': ['PermafrostBoltsPlayer', 'PermafrostBoltsAmmoPlayer'],
    'rapid_fire': ['RapidShotPlayer', 'RapidShotAmmoPlayer'],
    'explosive_grenade': ['ExplosiveGrenadePlayer'],
    'gas_grenade': ['ToxicGrenadePlayer'],
    'tempest_flurry': ['TempestFlurryPlayer'],
    'ice_strike': ['IceStrikePlayer'],
    'storm_wave': ['StormWavePlayer'],
    'killing_palm': ['KillingPalmPlayer'],
    'tempest_bell': ['TempestBellPlayer'],
    'lightning_spear': ['LightningSpearPlayer'],
    'whirlwind_lance': ['WhirlwindLancePlayer'],
    'shockwave_totem': ['ShockwaveTotemQuakePlayer', 'ShockwaveTotemPlayer'],
    'herald_thunder_bolt': ['HeraldOfThunderPlayer'],
    'herald_ice_shatter': ['HeraldOfIcePlayer'],
    'armour_piercing_bolt': ['ArmourPiercingBoltsPlayer', 'ArmourPiercingBoltsAmmoPlayer'],
    'concussion_grenade': ['FlashGrenadePlayer'],
    'rolling_slam': ['RollingSlamPlayer'],
    'volcanic_fissure': ['VolcanicFissurePlayer'],
    'armour_breaker': ['ArmourBreakerPlayer'],
    'shield_rush': ['ShieldChargePlayer'],
    'gale_sweep': ['GaleStrikePlayer'],
    'vault_strike': ['VaultingImpactPlayer'],
    'whirling_rush': ['WhirlingAssaultPlayer'],
    'siphon_strike': ['SiphoningStrikePlayer'],
    'thunder_arrow': ['StormcallerArrowPlayer'],
    'frost_volley': ['FreezingSalvoPlayer'],
    'galvanic_bolts': ['GalvanicShardsPlayer', 'GalvanicShardsAmmoPlayer'],
    'siege_salvo': ['SiegeCascadePlayer', 'SiegeCascadeAmmoPlayer'],
    'hail_rounds': ['HailstormRoundsPlayer', 'HailstormRoundsAmmoPlayer'],
    'storm_javelin': ['StormLancePlayer'],
    'spear_thicket': ['SpearfieldPlayer'],
    'rapid_thrust': ['RapidAssaultPlayer'],
    'shatter_spikes': ['EarthshatterPlayer'],
    'magma_spray': ['MoltenBlastPlayer'],   # 碎片（MoltenBlastSecondaryPlayer）跟主彈同一張表
    'true_strike': ['PerfectStrikePlayer'],
    'stampede': ['StampedePlayer'],
    'forge_hammer': ['ForgeHammerPlayer'],
    'reeling_palm': ['StaggeringPalmPlayer', 'StaggeringPalmProjectilePlayer'],
    'reeling_palm_bolt': ['StaggeringPalmProjectilePlayer'],   # 2026-10-28：震脈掌增益期間長棍攻擊多射的投射物（飛的樣子）
    'frost_palm': ['ShatteringPalmPlayer'],
    'frost_wave': ['WaveOfFrostPlayer'],
    'shock_arrow': ['ElectrocutingArrowPlayer'],
    'bramble_arrow': ['VineArrowPlayer'],
    'venom_burst_arrow': ['PoisonBurstArrowPlayer'],
    'spiral_volley': ['SpiralVolleyPlayer'],
    'blast_arrow': ['DetonatingArrowPlayer'],
    'chain_arrow': ['ShockchainArrowPlayer'],
    'piercing_rounds': ['HighVelocityRoundsPlayer', 'HighVelocityRoundsAmmoPlayer'],
    'incendiary_shot': ['IncendiaryShotPlayer', 'IncendiaryShotAmmoPlayer'],
    'frost_bolt_round': ['IceShardsPlayer', 'IceShardsAmmoPlayer'],
    'storm_bolts': ['ShockburstRoundsPlayer', 'ShockburstRoundsAmmoPlayer'],
    'oil_grenade': ['OilGrenadePlayer'],
    'voltaic_grenade': ['ShockGrenadePlayer'],
    'cluster_grenade': ['ClusterGrenadePlayer'],
    'serpent_fury': ['WindSerpentsFuryPlayer'],
    'prism_rend': ['ElementalSunderingPlayer'],
    'thunder_leap': ['ThunderousLeapPlayer'],
    'blood_harvest': ['BloodHuntPlayer'],
    'sunfire_spear': ['SpearOfSolarisPlayer'],
    'hammer_of_gods': ['HammerOfTheGodsPlayer'],
    'falling_thunder': ['FallingThunderPlayer'],
    'glacial_cascade': ['GlacialCascadePlayer'],
    'spear_throw': ['SpearThrowPlayer'],
    'explosive_spear': ['ExplosiveSpearPlayer'],
    'fangs_of_frost': ['FangsOfFrostPlayer'],
    'snipe': ['SnipePlayer'],
    'bear_maul': ['BearMaulPlayer'],
    'bear_slam': ['FuriousSlamPlayer'],
    'mountain_fury': ['BearFuryOfTheMountainPlayer'],
    'rampage': ['BearRampagePlayer'],
    'walking_calamity': ['WalkingCalamityPlayer', 'WalkingCalamityMeteorPlayer:WalkingCalamityMeteorLargePlayer'],
    'wolf_shred': ['WolfShredPlayer'],
    'lunar_assault': ['LunarAssaultPlayer'],
    'pounce': ['WolfPouncePlayer', 'WolfPounceSummonWolfPlayer'],
    'cross_slash': ['WolfCrossSlashPlayer'],
    'ancestral_volcanic_steps': ['AncestralCryShockwavePlayer'],
    'ancestral_volcanic_eruption': ['AncestralCryProjectilePlayer'],
    'lunar_moonbeam': ['WolfLunarBlessingBeamAttackPlayer'],
    'wyvern_rend': ['WyvernRendPlayer'],
    'wing_blast': ['WingBlastPlayer'],
    'rolling_magma': ['RollingMagmaPlayer'],
    'devour': ['WyvernDevourPlayer'],
    'oil_barrage': ['OilBarragePlayer'],
    'flame_breath': ['WyvernFlameBreathPlayer'],
    'sunder': ['SunderPlayer'],
    'supercharged_slam': ['SuperchargedSlamPlayer'],
    'resonating_shield': ['ResonatingShieldPlayer'],
    'flicker_strike': ['FlickerStrikePlayer'],
    'gathering_storm': ['GatheringStormPlayer'],
    'disengage': ['DisengagePlayer'],
    'glacial_lance': ['GlacialLancePlayer'],
    'primal_strikes': ['PrimalStrikesPlayer'],
    'cull_the_weak': ['CullTheWeakPlayer'],
    'toxic_growth': ['ToxicGrowthPlayer'],
    'magnetic_salvo': ['MagneticSalvoPlayer'],
    'glacial_bolt': ['GlacialBoltPlayer', 'GlacialBoltAmmoPlayer'],
    'plasma_blast': ['PlasmaBlastPlayer', 'PlasmaBlastAmmoPlayer'],
    'herald_blood_burst': ['HeraldOfBloodPlayer:HeraldOfBloodExplosionPlayer', 'HeraldOfBloodPlayer'],
    'toxic_domain': ['ToxicDomainPlayer'],
    'shield_wall': ['ShieldWallPlayer'],
    'frozen_locus': ['FrozenLocusPlayer'],
    'rake': ['RakePlayer'],
    'lightning_rod': ['LightningRodPlayer'],
    'fragmentation_rounds': ['FragmentationRoundsPlayer', 'FragmentationRoundsAmmoPlayer'],
    'stormblast_bolts': ['StormblastBoltsPlayer', 'StormblastBoltsAmmoPlayer'],
    'siege_ballista': ['SiegeBallistaProjectilePlayer', 'SiegeBallistaPlayer'],
    'artillery_ballista': ['ArtilleryBallistaProjectilePlayer', 'ArtilleryBallistaPlayer'],
    # 觸發／附屬的攻擊
    'proc_crystal_burst': ['FrozenLocusPlayer:FrozenLocusExplodePlayer'],
    'proc_stomping_ground': ['StompingGroundShockwavePlayer'],
    'proc_impact_shockwave': ['ImpactShockwaveSupportPlayer'],
    'proc_side_aftershock': ['SupportSlamAftershocksPlayer'],
    'proc_armour_explosion': ['ArmourExplosionPlayer'],
    'proc_volcanic_eruption': ['TriggeredVolcanicEruptionPlayer'],
    'proc_snipe_blast': ['SnipePlayer:SnipeExplosionPlayer'],
    'proc_snipe_ice_blast': ['SnipePlayer:SnipeFrozenExplosionPlayer'],
    'spear_throw_blast': ['SpearThrowPlayer:SpearThrowFrenzyChargePlayer'],
    'explosive_spear_blast': ['ExplosiveSpearPlayer:ExplosiveSpearExplodePlayer'],
    'explosive_spear_infused': ['ExplosiveSpearPlayer:ExplosiveSpearInfusedExplodePlayer'],
    'bloodhound_blast': ['BloodhoundsMarkExplosionPlayer'],
    'proc_toxic_pustule': ['ToxicGrowthPlayer:ToxicGrowthBurstPlayer'],
    'proc_storm_shockwave': ['GatheringStormPlayer:GatheringStormExplodePlayer', 'GatheringStormPlayer'],
    'proc_perfect_wave': ['PerfectStrikePlayer:PerfectStrikeShockwavePlayer'],
    'storm_lance_bolt': ['StormLancePlayer:StormLanceBeamPlayer', 'StormLancePlayer'],
    'storm_lance_burst': ['StormLancePlayer:StormLanceDetonatedBeamPlayer', 'StormLancePlayer'],
    'proc_blood_hunt_burst': ['BloodHuntPlayer:BloodHuntExplosionPlayer'],
    'proc_boneshatter_wave': ['BoneshatterPlayer:BoneshatterShockwavePlayer'],
    'proc_siphon_shockwave': ['SiphoningStrikePlayer:SiphoningStrikePulsePlayer'],
    'proc_magma_spray': ['MagmaSprayPlayer'],
    'proc_disengage_shockwave': ['DisengagePlayer:DisengageShockwavePlayer'],
    'proc_parry_sweep': ['ParryPlayer'],
    'proc_shockchain_wave': ['ShockchainArrowPlayer:ShockchainArrowExplosionPlayer'],
    'proc_shattering_burst': ['ShatteringPalmExplosionPlayer'],
    'proc_stormblast_burst': ['StormblastBoltsPlayer:StormblastBoltsExplosionPlayer'],
    'proc_ice_shard_burst': ['IceShardsPlayer:IceShardsShardPlayer'],
    'proc_shockburst_pulse': ['ShockburstRoundsPlayer:ShockburstRoundsExplosionPlayer'],
    'proc_galvanic_beam': ['GalvanicShardsPlayer:GalvanicShardsBeamPlayer'],
    'proc_permafrost_shatter': ['PermafrostBoltsPlayer:PermafrostBoltsConePlayer'],
    'proc_incendiary_shatter': ['IncendiaryShotPlayer:IncendiaryShotConePlayer'],
    'asc_combust': ['InfernalCryCorpseExplosionPlayer', 'TemperWeaponCombustionPlayer'],
    'asc_surge_fire': ['AmazonTriggerElementalSurgePlayer'],
    'asc_surge_cold': ['AmazonTriggerElementalSurgePlayer'],
    'asc_surge_lightning': ['AmazonTriggerElementalSurgePlayer'],
    'bleeding_concoction': ['BleedingConcoctionPlayer'],
    'shattering_concoction': ['ShatteringConcoctionPlayer'],
    'explosive_concoction': ['ExplosiveConcoctionPlayer'],
    'fulminating_concoction': ['FulminatingConcoctionPlayer'],
    'acidic_concoction': ['AcidicConcoctionPlayer'],
    'asc_void_explosion': ['VoidIllusionSpawnPlayer', 'VoidIllusionPlayer'],
    'asc_chayula_will': ['ArchonOfChayulaTornadoPlayer'],
    'proc_charged_shockwave': ['ChargedStaffShockwavePlayer'],
    'proc_calamity_meteor': ['WalkingCalamityMeteorPlayer'],
    'proc_wing_shockwave': ['WingBlastPlayer:WingBlastStunExplosionPlayer'],
    'proc_ice_fragment_shred': ['IceFragmentsPlayer'],
    'proc_ice_fragment_arrow': ['IceTippedArrowsIceFragmentPlayer'],
    'proc_ice_fragment_escape': ['EscapeShotIceFragmentPlayer'],
    'asc_vivid_stag': ['VividStampedePlayer'],
    'asc_hollow_bell': ['HollowFocusPlayer'],
    'asc_hollow_ring': ['HollowResonancePlayer'],
    # 2026-10-28：攻擊的第二段（StageAttacks；同一下換段的寫在原技能 level_modifiers 的 stage.代號，這裡是另外打出去的那一段）
    'proc_cross_shockwave': ['WolfCrossSlashPlayer:WolfCrossSlashMarkConsumedPlayer'],
    'proc_glacial_lance_burst': ['GlacialLancePlayer:GlacialLanceWallsPlayer', 'GlacialLancePlayer'],
    'proc_primal_spirit': ['PrimalStrikesPlayer:PrimalStrikesStagWavePlayer'],
    'proc_domain_pustule': ['ToxicDomainPlayer:ToxicDomainPustuleExplosion'],
    'proc_glacial_bolt_wall': ['GlacialBoltPlayer:GlacialBoltWallPlayer'],
    'proc_frag_shatter': ['FragmentationRoundsPlayer:FragmentationRoundsFreezeShatterPlayer'],
    # 2026-10-28：分段之後剩下的（StageLeftovers）
    'proc_storm_tempest_shockwave': ['GatheringStormPlayer:GatheringStormExplodeBetterPlayer', 'GatheringStormPlayer'],
}
# 沒有 PoE2 對應（我們自己的普攻、Boss 掉的技能、照比例觸發的通用餘震…）
NO_EQUIV = {
    'default_melee', 'default_bow', 'default_wand', 'default_staff', 'default_crossbow', 'default_quarterstaff',
    'default_spear', 'rift_slash', 'starfall_volley', 'chain_lash', 'nameless_backstab', 'bone_fissure',
    'proc_aftershock', 'proc_tremor', 'asc_aftershock',
}


def load_repoe(d):
    return json.load(open(Path(d) / 'skills.json', encoding='utf-8'))


def series_of(sk, src):
    """{名稱: [40 個值]}。技能層級的叫「技能#cost.Mana」之類，stat set 的叫「statset.屬性」（damage_multiplier 沒寫＝100）。
    「技能:statset」只取那個 stat set。"""
    sid, _, ssid = src.partition(':')
    e = sk[sid]
    out = {}
    pl = e.get('per_level', {})
    st = e.get('static', {}) or {}
    levels = [str(L) for L in range(1, 41)]
    if len(pl) >= 40:
        for cname in set(k for L in levels for k in (pl[L].get('costs') or {})) | set((st.get('costs') or {})):
            out[sid + '#cost.' + cname] = [((pl[L].get('costs') or {}).get(cname, (st.get('costs') or {}).get(cname, 0))) for L in levels]
        if any('cooldown' in pl[L] for L in levels) or 'cooldown' in st:
            out[sid + '#cooldown'] = [pl[L].get('cooldown', st.get('cooldown', 0)) for L in levels]
        if any('stored_uses' in pl[L] for L in levels) or 'stored_uses' in st:
            out[sid + '#stored_uses'] = [pl[L].get('stored_uses', st.get('stored_uses', 0)) for L in levels]
    out[sid + '#attack_speed_multiplier'] = [st.get('attack_speed_multiplier', 0)] * 40
    for ss in e.get('stat_sets', []):
        if ssid and ss['id'] != ssid:
            continue
        spl = ss['per_level']
        sst = ss.get('static', {}) or {}
        if len(spl) < 40:
            continue
        p = ss['id'] + '.'
        out[p + 'damage_multiplier'] = [spl[L].get('damage_multiplier', sst.get('damage_multiplier', 100)) for L in levels]
        if 'crit_chance' in sst or any('crit_chance' in spl[L] for L in levels):
            out[p + 'crit_chance'] = [spl[L].get('crit_chance', sst.get('crit_chance', 0)) for L in levels]
        for i, s in enumerate(sst.get('stats', [])):
            if s.get('type') == 'implicit':
                continue
            vals = []
            for L in levels:
                lst = spl[L].get('stats') or []
                v = lst[i] if i < len(lst) else None
                vals.append(v['value'] if v and 'value' in v else s.get('value', 0))
            out[p + s['id']] = vals
    return out


def main_set(sk, src):
    sid, _, ssid = src.partition(':')
    return sid, ssid or sk[sid]['stat_sets'][0]['id']


def interp(a, b, L):
    return a + (b - a) * (L - 1) / 19.0


TRANSFORMS = [
    ('x', lambda v: v),
    ('/100', lambda v: v / 100.0),
    ('/10', lambda v: v / 10.0),
    ('/1000', lambda v: v / 1000.0),
    ('1+x/100', lambda v: 1 + v / 100.0),
    ('-x', lambda v: -v),
]


def close(a, b):
    return abs(a - b) <= max(0.0051, abs(b) * 0.003)


def ours_at(v, L):
    if isinstance(v, list):
        if len(v) == 2:
            return interp(v[0], v[1], L)
        return v[L - 1] if L <= len(v) else v[-1]
    return v


def classify(v, ser):
    """回傳 (狀態, 變換) 或 None。"""
    for tn, f in TRANSFORMS:
        t = [f(x) for x in ser]
        if isinstance(v, list) and len(v) == 2:
            if close(v[0], t[0]) and close(v[1], t[19]):
                ok = all(close(ours_at(v, L), t[L - 1]) for L in range(1, 41))
                return ('OK' if ok else 'INTERP', tn)
        elif isinstance(v, list):
            if len(v) == 40 and all(close(v[i], t[i]) for i in range(40)):
                return ('OK', tn)
        else:
            if all(close(v, x) for x in t) and t[0] != 0:
                return ('OK', tn)
    return None


def walk(v, path, out):
    if isinstance(v, dict):
        for k, x in v.items():
            walk(x, path + [str(k)], out)
    elif isinstance(v, list):
        if v and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in v):
            out.append(('.'.join(path), v))
        else:
            for i, x in enumerate(v):
                if isinstance(x, dict) and 'stat' in x:
                    walk(x, path + [x['stat']], out)
                else:
                    walk(x, path + [str(i)], out)
    elif isinstance(v, (int, float)) and not isinstance(v, bool):
        out.append(('.'.join(path), v))


def fmt(v):
    if isinstance(v, list):
        if len(v) <= 2:
            return str(v)
        return '[%s…%s…%s](%d)' % (v[0], v[19] if len(v) > 19 else '-', v[-1], len(v))
    return str(v)


def series_fmt(s):
    return '%s/%s/%s' % (s[0], s[19], s[39])


def ratio_more(S, num, den):
    """副段傷害寫成「比主段更多幾 %」。"""
    a, b = S[num], S[den]
    return [round(a[i] / b[i] * 100 - 100) for i in range(40)]


def ratio_pct(S, num, den):
    """副段傷害寫成主段的幾 %。"""
    a, b = S[num], S[den]
    return [round(a[i] / b[i] * 100) for i in range(40)]


# 我們的寫法跟 PoE2 不是同一個數字、要換算的欄位：(yml 路徑, 說明, 算法)
DERIVED = {
    'earthquake': [
        ('nova.aftershock.radius', '餘震半徑 ÷ 衝擊半徑', lambda S: [round(x / 18, 3) for x in S['EarthquakeAftershockPlayer.active_skill_base_area_of_effect_radius']]),
        ('nova.aftershock.more', '餘震傷害 ÷ 衝擊傷害 − 1', lambda S: ratio_more(S, 'EarthquakeAftershockPlayer.damage_multiplier', 'EarthquakePlayer.damage_multiplier')),
    ],
    'lightning_arrow': [
        ('projectile.splash.damage', '雷束（Arc）÷ 箭', lambda S: ratio_pct(S, 'LightningArrowArcPlayer.damage_multiplier', 'LightningArrowPlayer.damage_multiplier')),
    ],
    'lightning_spear': [
        ('projectile.splash.damage', '雷彈 ÷ 主矛', lambda S: ratio_pct(S, 'LightningSpearSecondaryProjectilePlayer.damage_multiplier', 'LightningSpearPlayer.damage_multiplier')),
    ],
    'caustic_arrow': [
        ('projectile.ground.damage', '毒霧 ÷ 箭', lambda S: ratio_pct(S, 'GasArrowGasDegenPlayer.damage_multiplier', 'GasArrowPlayer.damage_multiplier')),
    ],
    'gas_grenade': [
        ('projectile.ground.damage', '毒氣 ÷ 榴彈', lambda S: ratio_pct(S, 'ToxicGrenadeCloudPlayer.damage_multiplier', 'ToxicGrenadePlayer.damage_multiplier')),
    ],
    'rolling_slam': [
        ('cone.aftershock.more', '第二下 ÷ 第一下 − 1', lambda S: ratio_more(S, 'RollingSlamSecondSlamPlayer.damage_multiplier', 'RollingSlamFirstSlamPlayer.damage_multiplier')),
    ],
    'shatter_spikes': [
        ('nova.aftershock.more', '地刺 ÷ 衝擊 − 1', lambda S: ratio_more(S, 'EarthshatterSpikePlayer.damage_multiplier', 'EarthshatterPlayer.damage_multiplier')),
    ],
    'sunder': [
        ('cone.aftershock.more', '震波 ÷ 主波 − 1', lambda S: ratio_more(S, 'SunderShockwavePlayer.damage_multiplier', 'SunderPlayer.damage_multiplier')),
    ],
    'fangs_of_frost': [   # 2026-10-28：冰霜爆裂的凍結累積、冰緩效果（200% → 404%）寫成比刺擊本身（level_modifiers 100% → 202%）再更多幾 %
        ('stages.burst.modifiers.freeze.buildup.value', '(1+爆裂)÷(1+刺擊)−1', lambda S: [round(((1 + b / 100) / (1 + a / 100) - 1) * 100, 1) for a, b in zip(
            S['FangsOfFrostPlayer.active_skill_hit_damage_freeze_multiplier_+%_final'], S['FangsOfFrostBurstPlayer.active_skill_hit_damage_freeze_multiplier_+%_final'])]),
        ('stages.burst.modifiers.chill.effect.value', '(1+爆裂)÷(1+刺擊)−1', lambda S: [round(((1 + b / 100) / (1 + a / 100) - 1) * 100, 1) for a, b in zip(
            S['FangsOfFrostPlayer.active_skill_chill_effect_+%_final'], S['FangsOfFrostBurstPlayer.active_skill_chill_effect_+%_final'])]),
    ],
    'pounce': [
        ('level_modifiers.skill.cooldown.value', '冷卻 ÷ 6 秒 − 1', lambda S: [round((x / 6000 - 1) * 100, 2) for x in S['WolfPouncePlayer#cooldown']]),
    ],
    # 2026-10-28：寒錐爆的冰緩、凍結累積疊在冰槍自己的上面（ownSubBuild 帶著原技能的 level_modifiers），寫成再更多幾 %
    'proc_glacial_lance_burst': [
        ('level_modifiers.chill.effect.value', '(1 + 爆炸) ÷ (1 + 冰槍) − 1', lambda S: [round(((100 + a) / (100 + b) - 1) * 100, 2) for a, b in zip(
            S['GlacialLanceWallsPlayer.active_skill_chill_effect_+%_final'], S['GlacialLancePlayer.active_skill_chill_effect_+%_final'])]),
        ('level_modifiers.freeze.buildup.value', '(1 + 爆炸) ÷ (1 + 冰槍) − 1', lambda S: [round(((100 + a) / (100 + b) - 1) * 100, 2) for a, b in zip(
            S['GlacialLanceWallsPlayer.active_skill_hit_damage_freeze_multiplier_+%_final'],
            S['GlacialLancePlayer.active_skill_hit_damage_freeze_multiplier_+%_final'])]),
    ],
}

# 2026-10-28：分段傷害（skills.yml 的 stages）：(技能 id, 段) → 那一段的 stat set（「技能:statset」，沒寫技能就是 MAP 的第一個來源）
STAGE_SETS = {
    ('tempest_flurry', 'third'): 'TempestFlurryPlayerThirdStrike',
    ('tempest_flurry', 'final'): 'TempestFlurryPlayerFinalStrike',
    ('ice_strike', 'final'): 'IceStrikeThirdAttackPlayer',
    ('tempest_bell', 'impact'): 'TempestBellSlamPlayer',
    ('whirlwind_lance', 'whirlwind'): 'WhirlwindLanceStormPlayer',
    ('plasma_blast', 'explosion'): 'PlasmaBlastExplosionPlayer',
    ('siege_salvo', 'bolt'): 'SiegeCascadePlayer',
    ('siege_salvo', 'explosion'): 'SiegeCascadeExplodePlayer',
    ('storm_javelin', 'infused'): 'StormLanceInfusedPlayer',
    ('rapid_thrust', 'last'): 'RapidAssaultFinalHitPlayer',
    ('rapid_thrust', 'explosion'): 'RapidAssaultDetonation',
    ('stampede', 'slam'): 'StampedeSlamPlayer',
    ('forge_hammer', 'recall'): 'ForgeHammerRecallPlayer',
    ('reeling_palm', 'projectile'): 'StaggeringPalmProjectilePlayer:StaggeringPalmProjectilePlayer',
    ('piercing_rounds', 'vs_armour_broken'): 'HighVelocityRoundsArmourBrokenPlayer',
    ('serpent_fury', 'serpent'): 'WindSerpentsFurySnakePlayer',
    ('prism_rend', 'cold'): 'ElementalSunderingColdPlayer',
    ('prism_rend', 'lightning'): 'ElementalSunderingLightningPlayer',
    ('sunfire_spear', 'pulse'): 'SpearOfSolarisPulsePlayer',
    ('glacial_cascade', 'final'): 'GlacialCascadeLastSpikePlayer',
    ('fangs_of_frost', 'burst'): 'FangsOfFrostBurstPlayer',
    # 2026-10-28：分段之後剩下的（StageLeftovers）
    ('sunfire_spear', 'ground'): 'SpearOfSolarisGroundPlayer',
    ('stampede', 'explosion'): 'StampedeEruptionPlayer',
    ('forge_hammer', 'fissure'): 'ForgeHammerFissurePlayer',
}


def stage_rows(sk, sid, sdef):
    """2026-10-28：每一段的 effectiveness 跟指定的 stat set 的 damage_multiplier 逐級比。"""
    rows = []
    for key, st in (sdef.get('stages') or {}).items():
        target = STAGE_SETS.get((sid, key))
        path = 'stages.%s.effectiveness' % key
        if not target:
            rows.append(('UNMATCHED', path, fmt(st.get('effectiveness')), 'STAGE_SETS 沒寫這一段對哪個 stat set'))
            continue
        src = target if ':' in target else MAP[sid][0].split(':')[0] + ':' + target
        ser = series_of(sk, src).get(src.split(':')[1] + '.damage_multiplier')
        if ser is None:
            rows.append(('UNMATCHED', path, fmt(st.get('effectiveness')), '找不到 ' + src))
            continue
        USED.add(src.split(':')[1] + '.damage_multiplier')
        exp = [x / 100.0 for x in ser]
        v = st.get('effectiveness')
        if isinstance(v, list) and len(v) == 40:
            ok = all(close(v[i], exp[i]) for i in range(40))
        elif isinstance(v, (int, float)):
            ok = all(close(v, x) for x in exp)
        else:
            ok = False
        rows.append(('OK' if ok else 'MISMATCH', 'STAGE ' + path, fmt(v), '%s %s' % (src, series_fmt(exp))))
    return rows


# 刻意跟 PoE2 寫法不同、報告裡不算錯的（路徑 → 原因）
NOTES = {
    ('plasma_blast', 'attack_speed'): '原作「總攻擊時間 +2 秒」，照弩 1.6 次／秒換算成 0.24',
    ('proc_frag_shatter', 'cooldown'): '觸發的爆炸，1 tick 的觸發間隔（每一隻凍結的敵人各炸一次）',
    ('proc_storm_tempest_shockwave', 'cooldown'): '觸發的震波，1 tick 的觸發間隔（同時炸好幾口鐘時每口都放）',
    ('proc_shattering_burst', 'cooldown'): '觸發的爆炸，1 tick 的觸發間隔（每隻碎掉的敵人各炸一次）',
}

USED = set()


class Track(dict):
    """記下 DERIVED 用到哪些 RePoE 數列（報告裡就不會列成「沒對到」）。"""

    def __getitem__(self, k):
        USED.add(k)
        return dict.__getitem__(self, k)


def near(a, b):
    return abs(a - b) <= max(1.01, abs(b) * 0.006) if abs(b) > 5 else abs(a - b) < 0.006


def audit(sk, sid, sdef):
    rows = []
    srcs = MAP[sid]
    msid, mset = main_set(sk, srcs[0])
    allser = {}
    for src in srcs:
        allser.update(series_of(sk, src))
    hidden = bool(sdef.get('hidden'))
    dm = allser.get(mset + '.damage_multiplier')
    cd = allser.get(msid + '#cooldown')
    mana_key = msid + '#cost.Mana'
    if not any(allser.get(mana_key) or []):
        mana_key = next((k for k, v in allser.items() if k.endswith('#cost.Mana') and any(v)), None)
    mana = allser.get(mana_key) if mana_key else None
    expect = {
        'effectiveness': [x / 100.0 for x in dm] if dm else None,
        'mana': mana,
        'attack_speed': [1 + x / 100.0 for x in allser[msid + '#attack_speed_multiplier']],
        'cooldown': [x / 1000.0 for x in cd] if cd else None,
    }
    per_sec = any(k.endswith('#cost.ManaPerMinute') for k in allser) and not mana
    derived = {p: (d, f(Track(allser))) for p, d, f in DERIVED.get(sid, [])}
    fields = []
    walk({k: v for k, v in sdef.items() if k not in ('tier', 'damage')}, [], fields)
    rows.extend(stage_rows(sk, sid, sdef))   # 2026-10-28：分段傷害
    for path, v in fields:
        if path.startswith('stages.') and path.endswith('.effectiveness'):
            continue   # 上面 stage_rows 照指定的 stat set 對過了
        if path in derived:
            d, exp = derived[path]
            if isinstance(v, list):
                ok = all(near(ours_at(v, L), exp[L - 1]) for L in range(1, 41))
            else:   # 固定值（引擎不能逐級）：PoE2 兩張表相除只有進位誤差時，跟中位數差 1% 以內算對
                med = sorted(exp)[20]
                ok = abs(v - med) <= max(0.006, abs(med) * 0.01) and max(exp) - min(exp) <= max(0.02, abs(med) * 0.05)
            rows.append(('OK' if ok else 'DERIVED', path + '（' + d + '）', fmt(v), series_fmt(exp)))
            continue
        if path.startswith('modifiers.proc.'):
            rows.append(('OK', path + '（觸發的隱藏技能，100% 機率，不是數值）', fmt(v), ''))
            continue
        if path in expect:
            exp = expect[path]
            if exp is None and (sid, path) in NOTES:
                rows.append(('OK', path + '（' + NOTES[(sid, path)] + '）', fmt(v), ''))
                continue
            if path == 'attack_speed' and hidden:
                rows.append(('OK', path + '（觸發的技能，不用攻擊時間）', fmt(v), ''))
                continue
            if path == 'mana' and per_sec:
                rows.append(('OK', path + '（PoE2 是每分鐘魔耗的引導，照之前的換算不動）', fmt(v), ''))
                continue
            if exp is None:
                rows.append(('NOREF', path, fmt(v), '-'))
                continue
            if isinstance(v, list) and len(v) == 2:
                ok = all(close(ours_at(v, L), exp[L - 1]) for L in range(1, 41))
                st = 'OK' if ok else ('INTERP' if close(v[0], exp[0]) and close(v[1], exp[19]) else 'MISMATCH')
            elif isinstance(v, list):
                st = 'OK' if len(v) == 40 and all(close(v[i], exp[i]) for i in range(40)) else 'MISMATCH'
            else:
                st = 'OK' if all(close(v, x) for x in exp) else 'MISMATCH'
            if st != 'OK' and path == 'cooldown' and 'level_modifiers.skill.cooldown.value' in derived:
                st = 'OK'   # 固定冷卻＋逐級的冷卻縮短（DERIVED 另外核對）
            if st == 'OK' and path == 'effectiveness':
                USED.add(mset + '.damage_multiplier')
            if st == 'OK' and path == 'mana':
                USED.add(mana_key)
            if st != 'OK' and path in ('effectiveness', 'mana'):
                # 主要的 stat set 不對時，看其他 stat set／來源（例如彈藥技能付魔力、技能其實照第二段傷害）
                key = 'damage_multiplier' if path == 'effectiveness' else 'cost.Mana'
                f = (lambda x: x / 100.0) if path == 'effectiveness' else (lambda x: x)
                for name, ser in allser.items():
                    if not name.endswith(key):
                        continue
                    e2 = [f(x) for x in ser]
                    if isinstance(v, list) and len(v) == 40 and all(close(v[i], e2[i]) for i in range(40)):
                        st, exp = 'OK', e2
                        USED.add(name)
                        break
            if st != 'OK' and (sid, path) in NOTES:
                st, path = 'OK', path + '（' + NOTES[(sid, path)] + '）'
            rows.append((st, path, fmt(v), series_fmt([round(x, 4) for x in exp])))
            continue
        hit = None
        for name, ser in allser.items():
            r = classify(v, ser)
            if r:
                hit = (r[0], name, r[1])
                if r[0] == 'OK':
                    break
        if hit:
            USED.add(hit[1])
            rows.append((hit[0], path, fmt(v), '%s %s (%s)' % (hit[1], hit[2], series_fmt(allser[hit[1]]))))
        elif isinstance(v, list) or 'modifiers' in path:
            rows.append(('UNMATCHED', path, fmt(v), ''))
        else:
            rows.append(('OK', path + '（PoE2 沒有對應的數字，幾何／手感參數）', fmt(v), ''))
    # 武器傷害寫成 [[小, 大] × 40]（嗅血爆、混合物、血爆）
    dmg = sdef.get('damage')
    if isinstance(dmg, list) and dmg and isinstance(dmg[0], list):
        best = None
        for kmin in [k for k in allser if re.search(r'minimum_\w*damage$', k)]:
            kmax = kmin.replace('minimum', 'maximum')
            if kmax not in allser:
                continue
            exp = list(zip(allser[kmin], allser[kmax]))
            ok = len(dmg) == 40 and all(abs(dmg[i][0] - exp[i][0]) <= 0.5 and abs(dmg[i][1] - exp[i][1]) <= 0.5 for i in range(40))
            USED.update({kmin, kmax})
            best = ('OK' if ok else 'MISMATCH', 'damage', '%s…%s…%s' % (dmg[0], dmg[min(19, len(dmg) - 1)], dmg[-1]),
                    '%s %s/%s/%s' % (kmin, list(exp[0]), list(exp[19]), list(exp[39])))
            if ok:
                break
        rows.append(best or ('UNMATCHED', 'damage', str(dmg[0]), ''))
    pd = sdef.get('parent_damage')
    if isinstance(pd, (int, float)) and ':' in srcs[0] and dm:
        rows.append(('MISMATCH', 'parent_damage', str(pd), '這組有自己的傷害表 %s，要寫 own' % series_fmt(dm)))
    for k in ('effectiveness', 'mana'):
        if hidden and k == 'mana':
            continue
        if expect[k] is not None and k not in [p for p, _ in fields] and len(set(expect[k])) > 1:
            rows.append(('MISSING', k, '-', series_fmt(expect[k])))
    return rows, allser


def varying_unused(allser):
    out = []
    used_vals = [allser[n] for n in USED if n in allser]
    for name, ser in allser.items():
        if ser in used_vals and not name.endswith('damage_multiplier'):
            continue
        if name in USED or len(set(ser)) <= 1 or name.endswith('#cost.ManaPerMinute'):
            continue
        out.append('%s=%s' % (name, series_fmt(ser)))
    return out


def main():
    args = sys.argv[1:]
    repoe = '/tmp/claude-0/-home-claude/c46e873f-cded-5f11-aa2c-868ac53d5e12/scratchpad/repoe/poe2/data'
    if args and Path(args[0]).is_dir():
        repoe = args.pop(0)
    only = set(args)
    sk = load_repoe(repoe)
    data = yaml.safe_load(SKILLS.read_text(encoding='utf-8'))['skills']
    atk = [k for k, v in data.items() if isinstance(v, dict) and v.get('type') == 'attack']
    results = []
    for sid in atk:
        if sid in SKIP:
            print('== %s（另外處理，略過）' % sid)
        elif sid in NO_EQUIV or sid not in MAP:
            print('== %s %s：沒有 PoE2 對應' % (sid, data[sid].get('name')))
        else:
            rows, allser = audit(sk, sid, data[sid])
            results.append((sid, rows, allser))
    n = 0
    for sid, rows, allser in results:
        if only and sid not in only:
            continue
        n += 1
        bad = [r for r in rows if r[0] != 'OK']
        print('== %s %s ← %s%s' % (sid, data[sid].get('name'), ', '.join(MAP[sid]), '' if bad else '  全部一致'))
        for r in rows:
            if r[0] != 'OK' or only:
                print('   %-9s %-40s ours=%s  poe2=%s' % r)
        varying = varying_unused(allser)
        if varying:
            print('   PoE2 逐級但沒對到：' + '; '.join(varying))
    print('checked', n, file=sys.stderr)


if __name__ == '__main__':
    main()
