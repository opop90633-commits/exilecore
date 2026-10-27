#!/usr/bin/env python3
"""2026-10-28：非攻擊技能（法術、召喚、光環／常駐、使者、戰吼、印記／詛咒、戰旗、引星…）對照 PoE2 0.5（RePoE，跟 poe2db 同一份資料）。

把 skills.yml 裡每一個隨等級變的數值（逐級表、[等級 1, 等級 20] 兩點內插）跟固定值，和 RePoE 的 1–40 級比，印出不一樣的地方。
[a, b] 兩點內插但 PoE2 是 40 級的曲線也算不一樣（要換成逐級表）。另外也比 Java 裡寫死的逐級表（MinionAbilities、Signatures…）。
攻擊技能（type: attack）另外對，不在這裡；昇華（source: ascendancy）、Boss 專屬的技能、輔助觸發的隱藏技能也不在這裡。

用法：python3 tools/audit_poe2_spells.py [RePoE 的 data 資料夾] [--only 技能代號,...]
沒有不一樣的話結尾印「全部一致」。
"""
import json
import math
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
SKILLS = ROOT / 'src/main/resources/data/skills.yml'
JAVA = ROOT / 'src/main/java/tw/exilecore'
DEFAULT_REPOE = '/tmp/claude-0/-home-claude/c46e873f-cded-5f11-aa2c-868ac53d5e12/scratchpad/repoe/poe2/data'

# ---------------------------------------------------------------- RePoE

class RePoE:
    def __init__(self, folder):
        self.sk = json.load(open(Path(folder) / 'skills.json', encoding='utf-8'))

    def skill(self, sid):
        if sid not in self.sk:
            raise KeyError('RePoE 沒有 ' + sid)
        return self.sk[sid]

    def costs(self, sid, kind='Mana'):
        pl = self.skill(sid).get('per_level') or {}
        return [((pl.get(str(L)) or {}).get('costs') or {}).get(kind) for L in range(1, 41)]

    def cooldown(self, sid):
        v = self.skill(sid)
        pl = v.get('per_level') or {}
        per = [(pl.get(str(L)) or {}).get('cooldown') for L in range(1, 41)]
        if any(x is not None for x in per):
            return [None if x is None else x / 1000 for x in per]
        cd = (v.get('static') or {}).get('cooldown')
        return None if cd is None else [cd / 1000] * 40

    def spirit(self, sid):
        v = self.skill(sid)
        res = ((v.get('static') or {}).get('reservations') or {}).get('spirit')
        if res is not None:
            return [res] * 40
        pl = v.get('per_level') or {}
        per = [((pl.get(str(L)) or {}).get('reservations') or {}).get('Spirit') for L in range(1, 41)]
        return per if any(x is not None for x in per) else None

    def cast(self, sid):
        c = self.skill(sid).get('cast_time')
        return None if c is None else c / 1000

    def _set(self, sid, si):
        sets = self.skill(sid)['stat_sets']
        if isinstance(si, str):
            return next(s for s in sets if s['id'] == si)
        return sets[si]

    def stat(self, sid, st, si=None):
        """一個 stat 的 1–40 級（常數就 40 個一樣）。si = None：第一個有這個 stat 的 stat set。"""
        sets = self.skill(sid)['stat_sets'] if si is None else [self._set(sid, si)]
        for s in sets:
            static = (s.get('static') or {}).get('stats') or []
            idx = next((i for i, x in enumerate(static) if x['id'] == st), None)
            if idx is None:
                continue
            const = static[idx].get('value')
            out = []
            for L in range(1, 41):
                row = (s.get('per_level') or {}).get(str(L)) or {}
                vals = row.get('stats') or []
                v = vals[idx] if idx < len(vals) else None
                out.append(v['value'] if isinstance(v, dict) and 'value' in v else const)
            if any(x is not None for x in out):
                return out
        raise KeyError('%s 沒有 %s' % (sid, st))

    def crit(self, sid, si=0):
        s = self._set(sid, si)
        c = (s.get('static') or {}).get('crit_chance')
        return None if c is None else c / 100

    def damage(self, sid, si=0):
        """法術擊中傷害 [[最小, 最大], …]（各元素加起來）。"""
        s = self._set(sid, si)
        static = [x['id'] for x in (s.get('static') or {}).get('stats') or []]
        types = [m.group(1) for k in static for m in [re.match(r'(?:spell|secondary)_minimum_base_(\w+?)_damage$', k)] if m]
        if not types:
            raise KeyError('%s set %s 沒有法術傷害' % (sid, si))
        pre = 'spell' if any(k.startswith('spell_minimum') for k in static) else 'secondary'
        mins = [self.stat(sid, '%s_minimum_base_%s_damage' % (pre, t), si) for t in types]
        maxs = [self.stat(sid, '%s_maximum_base_%s_damage' % (pre, t), si) for t in types]
        return [[sum(m[L] or 0 for m in mins), sum(m[L] or 0 for m in maxs)] for L in range(40)]


# ---------------------------------------------------------------- 我們的數值

def leaves(x, path=''):
    if isinstance(x, dict):
        for k, v in x.items():
            yield from leaves(v, path + '.' + str(k) if path else str(k))
    elif isinstance(x, list):
        if x and all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in x):
            yield path, x
        elif x and all(isinstance(v, list) and len(v) == 2 for v in x):
            yield path, x
        else:
            for i, v in enumerate(x):
                lab = i
                if isinstance(v, dict) and 'stat' in v:
                    lab = v['stat'] + ('/' + v['type'] if v.get('type') else '')
                yield from leaves(v, '%s[%s]' % (path, lab))
    elif isinstance(x, (int, float)) and not isinstance(x, bool):
        yield path, x


def expand(raw):
    """Scaled 的寫法 → 40 級（照 Scaled.at：兩點內插、超過 20 級照趨勢、逐級表超過表長照最後兩級的比例）。"""
    if raw is None:
        return None
    if isinstance(raw, (int, float)):
        return [float(raw)] * 40
    if isinstance(raw, list) and raw and isinstance(raw[0], list):
        return [list(map(float, raw[min(i, len(raw) - 1)])) for i in range(40)]
    if len(raw) == 2:
        a, b = map(float, raw)
        out = []
        for L in range(1, 41):
            v = a + (b - a) * (L - 1) / 19.0
            if L > 20 and a >= 0 and b >= 0:
                v = max(0.0, v)
            out.append(v)
        return out
    t = list(map(float, raw))
    out = []
    for L in range(1, 41):
        if L <= len(t):
            out.append(t[L - 1])
        else:
            e, p = t[-1], t[-2]
            out.append(e if (p <= 0 or e <= 0) else e * (e / p) ** (L - len(t)))
    return out


def kind(raw):
    if isinstance(raw, (int, float)):
        return '固定值'
    if isinstance(raw, list) and len(raw) == 2 and not isinstance(raw[0], list):
        return '兩點內插'
    return '逐級表%d' % len(raw)


def java_array(rel, name):
    text = (JAVA / rel).read_text(encoding='utf-8')
    m = re.search(r'\b' + re.escape(name) + r'\s*=\s*(?:new \w+\[\]\s*)?\{([^}]*)\}', text)
    if not m:
        raise KeyError('%s 沒有 %s' % (rel, name))
    return [float(x) for x in m.group(1).replace('\n', ' ').split(',') if x.strip()]


def java_command_mana(skill):
    text = (JAVA / 'skill/MinionAbilities.java').read_text(encoding='utf-8')
    const = {'skeletal_brute': 'BRUTE', 'skeletal_archers': 'SNIPER', 'skeletal_pyromancer': 'ARSONIST',
             'skeletal_stormcaller': 'STORM', 'skeletal_frostcaller': 'FROST', 'skeletal_reaver': 'REAVER'}[skill]
    m = re.search(r'\b' + const + r',\s*new Command\("[^"]*",\s*([\d.]+),\s*(?:new int\[\]\s*\{([^}]*)\}|(\w+))\)', text)
    cooldown = float(m.group(1))
    arr = [float(x) for x in m.group(2).split(',')] if m.group(2) else java_array('skill/MinionAbilities.java', m.group(3))
    return cooldown, arr


# ---------------------------------------------------------------- 對照表
# 每個技能：main = PoE2 的主要技能（看魔力、施法時間、冷卻、暴擊、精魂）；dmg = (技能, stat set) 法術傷害；
# checks = [(我們的路徑, 來源, 換算, 容許誤差)]；來源 = (技能, stat[, stat set])，或 '_mana:技能' 這種
# skip = 不比的通用項（cast、mana…）；unrep = PoE2 有、我們引擎沒有的（列在報告裡，不亂做）

MS = lambda x: x / 1000            # 毫秒 → 秒
DM = lambda x: x / 10              # PoE2 單位（0.1 米）→ 格
PM = lambda x: x / 60              # 每分鐘 → 每秒
NEG = lambda x: -x
ID = lambda x: x

S = {
 'hunters_mark': dict(main='SnipersMarkPlayer', checks=[
    ('mark.effects[crit.bonus_taken/INCREASED].value', ('SnipersMarkPlayer', 'enemy_additional_critical_strike_multiplier_against_self'), ID),
    ('mark.duration', ('SnipersMarkPlayer', 'base_skill_effect_duration'), MS)]),
 'fireball': dict(main='FireballPlayer', dmg=('FireballPlayer', 0)),
 'frost_nova': dict(main='IceNovaPlayer', dmg=('IceNovaPlayer', 0), checks=[
    ('nova.radius', ('IceNovaPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('level_modifiers[chill.effect/MORE].value', ('IceNovaPlayer', 'active_skill_chill_effect_+%_final'), ID),
    ('level_modifiers[freeze.buildup/MORE].value', ('IceNovaPlayer', 'active_skill_hit_damage_freeze_multiplier_+%_final'), ID)]),
 'spark': dict(main='SparkPlayer', dmg=('SparkPlayer', 0), checks=[
    ('projectile.count', ('SparkPlayer', 'base_number_of_projectiles', 0), ID)]),
 'arc': dict(main='ArcPlayer', dmg=('ArcPlayer', 0), checks=[
    ('chain.chains', ('ArcPlayer', 'number_of_chains'), ID)]),
 'comet': dict(main='CometPlayer', dmg=('CometPlayer', 0), checks=[
    ('impact.radius', ('CometPlayer', 'active_skill_base_area_of_effect_radius', 0), DM),
    ('impact.delay', ('CometPlayer', 'comet_fall_time_ms', 0), MS)]),
 'flame_wall': dict(main='FlameWallPlayer', checks=[
    ('zone.length', ('FlameWallPlayer', 'wall_maximum_length', 0), DM),
    ('zone.max', ('FlameWallPlayer', 'number_of_allowed_firewalls', 0), ID),
    ('level_modifiers[skill.duration/INCREASED].value', ('FlameWallPlayer', 'base_skill_effect_duration', 0), lambda x: (x / 4500 - 1) * 100, 0.006)],
    note='焰幕的 damage 是每秒燃燒（PoE2 base_fire_damage_to_deal_per_minute ÷ 60，整數）；穿牆投射物的附加傷害沒做',
    dot=('FlameWallPlayer', 'base_fire_damage_to_deal_per_minute', 0)),
 'detonate_dead': dict(main='DetonateDeadPlayer', dmg=('DetonateDeadPlayer', 0), checks=[
    ('corpse.percent', ('DetonateDeadPlayer', 'corpse_explosion_monster_life_permillage_physical'), DM),
    ('corpse.radius', ('DetonateDeadPlayer', 'active_skill_base_area_of_effect_radius'), DM)]),
 'raise_zombie': dict(main='RaiseZombiePlayer', checks=[
    ('summon.max', ('RaiseZombiePlayer', 'base_number_of_zombies_allowed'), ID),
    ('summon.duration', ('RaiseZombiePlayer', 'base_skill_effect_duration'), MS),
    ('java:skill/MinionService.java:EMPOWERED_DAMAGE', ('RaiseZombiePlayer', 'power_charged_zombie_hit_damage_+%_final'), ID)]),
 'raging_spirits': dict(main='RagingSpiritsPlayer', skip={'cast'}, checks=[
    ('summon.max', ('RagingSpiritsPlayer', 'base_number_of_raging_spirits_allowed'), ID),
    ('summon.duration', ('RagingSpiritsPlayer', 'base_skill_effect_duration'), MS)]),
 'herald_of_ash': dict(main='HeraldOfAshPlayer', skip={'cast'}, checks=[
    ('aura.on_kill.overkill', ('HeraldOfAshPlayer', 'herald_of_ash_overkill_threshold_%', 0), ID),
    ('javafn:ashPercent', ('HeraldOfAshPlayer', 'herald_of_ash_burning_%_overkill_damage_per_minute', 1), PM, 0.001)]),
 'herald_of_thunder': dict(main='HeraldOfThunderPlayer', skip={'cast'}, checks=[
    ('javafn:thunderCharges', ('HeraldOfThunderPlayer', 'herald_of_thunder_storm_max_hits', 0), ID)]),
 'herald_of_ice': dict(main='HeraldOfIcePlayer', skip={'cast'}),
 'herald_of_plague': dict(main='HeraldOfPlaguePlayer', skip={'cast'}, checks=[
    ('javafn:plagueRadius', ('HeraldOfPlaguePlayer', 'herald_of_agony_poison_spread_on_kill_radius_base_magnitude_to_grant'), DM, 0.001),
    ('javafn:plagueHinderChance', ('HeraldOfPlaguePlayer', 'hinder_chance_%_on_spreading_poioson'), ID)]),
 'herald_of_blood': dict(main='HeraldOfBloodPlayer', skip={'cast'}),
 'fortifying_cry': dict(main='FortifyingCryPlayer', checks=[
    ('warcry.radius', ('FortifyingCryPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.duration', ('FortifyingCryPlayer', 'base_skill_effect_duration'), MS),
    ('warcry.empower.per', ('FortifyingCryPlayer', 'shielding_cry_an_additional_empowered_attack_per_X_mp'), ID),
    ('warcry.empower.cap', ('FortifyingCryPlayer', 'shielding_cry_an_additional_empowered_attack_per_X_mp_mp_cap'), ID),
    ('warcry.guard.per_5_power', ('FortifyingCryPlayer', 'shielding_cry_grant_X_guard_per_5_mp'), ID),
    ('warcry.guard.power_cap', ('FortifyingCryPlayer', 'shielding_cry_grant_X_guard_per_5_mp_mp_cap'), ID),
    ('warcry.guard.duration', ('FortifyingCryPlayer', 'base_secondary_skill_effect_duration'), MS)]),
 'infernal_cry': dict(main='InfernalCryPlayer', checks=[
    ('warcry.radius', ('InfernalCryPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.duration', ('InfernalCryPlayer', 'base_skill_effect_duration'), MS),
    ('warcry.empower.per', ('InfernalCryPlayer', 'warcry_empowers_per_X_monster_power'), ID),
    ('warcry.empower.cap', ('InfernalCryPlayer', 'warcry_empowers_per_X_monster_power_mp_cap'), ID),
    ('warcry.empower.gain_fire', ('InfernalCryPlayer', 'infernal_cry_exerted_attack_all_damage_%_to_gain_as_fire_%'), ID)]),
 'infernal_combust': dict(main=None, dmg=('InfernalCryCorpseExplosionPlayer', 0), checks=[
    ('nova.radius', ('InfernalCryCorpseExplosionPlayer', 'active_skill_base_area_of_effect_radius'), DM)]),
 'seismic_cry': dict(main='SeismicCryPlayer', dmg=('SeismicCryPlayer', 0), skip={'crit'}, checks=[
    ('warcry.radius', ('SeismicCryPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.empower.per', ('SeismicCryPlayer', 'warcry_empowers_per_X_monster_power'), ID),
    ('warcry.empower.cap', ('SeismicCryPlayer', 'warcry_empowers_per_X_monster_power_mp_cap'), ID),
    ('warcry.empower.aftershocks', ('SeismicCryPlayer', 'seismic_cry_slam_skill_additional_aftershocks'), ID)]),
 'blood_offering': dict(main='PainOfferingPlayer', checks=[
    ('warcry.radius', ('PainOfferingPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.duration', ('PainOfferingPlayer', 'base_skill_effect_duration'), MS),
    ('warcry.minion_buff[damage/INCREASED].value', ('PainOfferingPlayer', 'pain_offering_damage_+%'), ID),
    ('warcry.minion_buff[attack.speed/INCREASED].value', ('PainOfferingPlayer', 'pain_offering_attack_and_cast_speed_+%'), ID),
    ('warcry.minion_buff[cast.speed/INCREASED].value', ('PainOfferingPlayer', 'pain_offering_attack_and_cast_speed_+%'), ID)]),
 'bone_offering': dict(main='BoneOfferingPlayer', checks=[
    ('warcry.duration', ('BoneOfferingPlayer', 'base_skill_effect_duration'), MS),
    ('warcry.minion_buff[physical.reduction/FLAT].value', ('BoneOfferingPlayer', 'bone_offering_damage_taken_+%_final'), NEG)]),
 'soul_offering': dict(main='SoulOfferingPlayer', checks=[
    ('warcry.duration', ('SoulOfferingPlayer', 'base_skill_effect_duration'), MS),
    ('warcry.buff[damage/INCREASED].value', ('SoulOfferingPlayer', 'power_offering_buff_spell_damage_+%'), ID)]),
 'vulnerability_curse': dict(main='VulnerabilityPlayer', checks=[
    ('mark.radius', ('VulnerabilityPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('mark.duration', ('VulnerabilityPlayer', 'base_skill_effect_duration'), MS),
    ('mark.effects[armour/FLAT].value', ('VulnerabilityPlayer', 'base_skill_buff_total_armour_-_to_grant'), NEG)]),
 'despair_curse': dict(main='DespairPlayer', checks=[
    ('mark.radius', ('DespairPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('mark.duration', ('DespairPlayer', 'base_skill_effect_duration'), MS),
    ('mark.effects[resist.chaos/FLAT].value', ('DespairPlayer', 'base_skill_buff_chaos_damage_resistance_%_to_apply'), ID)]),
 'elemental_weakness_curse': dict(main='ElementalWeaknessPlayer', checks=[
    ('mark.radius', ('ElementalWeaknessPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('mark.duration', ('ElementalWeaknessPlayer', 'base_skill_effect_duration'), MS)] + [
    ('mark.effects[resist.%s/FLAT].value' % e, ('ElementalWeaknessPlayer', 'base_skill_buff_all_elements_resistance_%_to_apply'), ID)
    for e in ('fire', 'cold', 'lightning')]),
 'enfeeble_curse': dict(main='EnfeeblePlayer', checks=[
    ('mark.radius', ('EnfeeblePlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('mark.duration', ('EnfeeblePlayer', 'base_skill_effect_duration'), MS),
    ('mark.effects[damage/MORE].value', ('EnfeeblePlayer', 'base_skill_buff_damage_+%_final_to_apply'), ID)]),
 'temporal_curse': dict(main='TemporalChainsPlayer', checks=[
    ('mark.radius', ('TemporalChainsPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('mark.duration', ('TemporalChainsPlayer', 'base_skill_effect_duration'), MS),
    ('mark.slow', ('TemporalChainsPlayer', 'base_skill_debuff_action_speed_+%_final_to_inflict'), NEG)]),
 'firestorm': dict(main='FirestormPlayer', dmg=('FirestormPlayer', 0), checks=[
    ('zone.duration', ('FirestormPlayer', 'base_skill_effect_duration', 0), MS),
    ('zone.radius', ('FirestormPlayer', 'active_skill_base_secondary_area_of_effect_radius', 0), DM),
    ('zone.max', ('FirestormPlayer', 'firestorm_max_number_of_storms', 0), ID)]),
 'ember_volley': dict(main='EmberFusilladePlayer', dmg=('EmberFusilladePlayer', 0), checks=[
    ('projectile.explode', ('EmberFusilladePlayer', 'active_skill_base_area_of_effect_radius', 1), DM),
    ('java:skill/Signatures.java:EMBER_MAX', ('EmberFusilladePlayer', 'blazing_cluster_maximum_number_of_projectiles_allowed', 0), ID)]),
 'storm_orb': dict(main='OrbOfStormsPlayer', dmg=('OrbOfStormsPlayer', 0), checks=[
    ('zone.duration', ('OrbOfStormsPlayer', 'base_skill_effect_duration'), MS),
    ('zone.max', ('OrbOfStormsPlayer', 'orb_of_storms_base_maximum_number_of_orbs'), ID)],
    unrep=['Orb of Storms 雷彈連鎖 3→5→9 次（number_of_chains）：雷雲是地面效果，沒有連鎖', '每 4 秒一發、放法術時多一發、最多 6 發（用每 2 秒一下代替）']),
 'lightning_pillar': dict(main='LightningConduitPlayer', dmg=('LightningConduitPlayer', 0),
    unrep=['Lightning Conduit 打到感電的敵人多劈 1→2→2 道（lightning_conduit_x_additional_strikes_if_consumed_a_shock）']),
 'hex_burst': dict(main='HexblastPlayer', dmg=('HexblastPlayer', 0), checks=[
    ('impact.radius', ('HexblastPlayer', 'active_skill_base_area_of_effect_radius'), DM)]),
 'bone_storm': dict(main='BonestormPlayer', dmg=('BonestormPlayer', 0), skip={'mana'}, checks=[
    ('mana', '_costs:BonestormPlayer:ManaPerMinute', lambda x: math.floor(x / 60 + 0.5), 0)]),
 'frost_aegis': dict(main='ArcticArmourPlayer', skip={'crit', 'cooldown'}, checks=[
    ('level_modifiers[arctic.interval/FLAT].value', ('ArcticArmourPlayer', 'base_active_skill_buff_stack_gain_frequency_ms'), MS),
    ('level_modifiers[arctic.stages/FLAT].value', ('ArcticArmourPlayer', 'maximum_number_of_arctic_armour_stationary_stacks'), ID)]),
 'proc_arctic_burst': dict(main=None, checks=[
    ('damage', ('ArcticArmourPlayer', 'arctic_armour_minimum_added_cold_damage_per_stack'), ID, 0, 0),
    ('damage', ('ArcticArmourPlayer', 'arctic_armour_maximum_added_cold_damage_per_stack'), ID, 0, 1),
    ('modifiers[spell.base_crit/FLAT].value', '_crit:ArcticArmourPlayer', ID)]),
 'frost_mine': dict(main='FrostBombPlayer', dmg=('FrostBombPlayer', 0), checks=[
    ('impact.radius', ('FrostBombPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('impact.delay', ('FrostBombPlayer', 'base_skill_detonation_time'), MS),
    ('modifiers[chill.effect/MORE].value', ('FrostBombPlayer', 'active_skill_chill_as_though_damage_+%_final'), ID)],
    unrep=['Frost Bomb 元素曝露 20%＋每跳 2%（最多 50%）']),
 'ice_wall': dict(main='FrostWallPlayer', dmg=('FrostWallPlayer', 0), checks=[
    ('zone.length', ('FrostWallPlayer', 'wall_maximum_length', 0), DM),
    ('zone.duration', ('FrostWallPlayer', 'base_skill_effect_duration', 0), MS),
    ('cooldown_uses', '_stored:FrostWallPlayer', ID)],
    unrep=['Frost Wall 冰牆生命 36→12,714→33,734（frost_wall_maximum_life）：冰牆不能被打']),
 'storm_blink': dict(main='LightningWarpPlayer', dmg=('LightningWarpPlayer', 0), checks=[
    ('dash.end_radius', ('LightningWarpPlayer', 'active_skill_base_area_of_effect_radius'), DM)]),
 'inferno_blast': dict(main='FlameblastPlayer', dmg=('FlameblastPlayer', 0), skip={'mana'}, checks=[
    ('mana', '_costs:FlameblastPlayer:ManaPerMinute', lambda x: math.floor(x / 60 + 0.5), 0),
    ('charge.stages', ('FlameblastPlayer', 'flameblast_maximum_stages'), ID),
    ('charge.stage_more', ('FlameblastPlayer', 'charged_blast_spell_damage_+%_final_per_stack'), ID)]),
 'sun_orb': dict(main='SolarOrbPlayer', dmg=('SolarOrbPlayer', 0), checks=[
    ('zone.radius', ('SolarOrbPlayer', 'active_skill_base_area_of_effect_radius', 0), DM),
    ('zone.duration', ('SolarOrbPlayer', 'base_skill_effect_duration', 0), MS),
    ('zone.interval', ('SolarOrbPlayer', 'solar_orb_base_pulse_frequency_ms', 0), MS)]),
 'ember_seed': dict(main='LivingBombPlayer', dmg=('LivingBombPlayer', 0), checks=[
    ('impact.radius', ('LivingBombPlayer', 'active_skill_base_area_of_effect_radius'), DM)]),
 'winter_eye': dict(main='EyeOfWinterPlayer', dmg=('EyeOfWinterPlayer', 0)),
 'bone_spikes': dict(main='UnearthPlayer', dmg=('UnearthPlayer', 0), checks=[
    ('cone.length', ('UnearthPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('cone.angle', ('UnearthPlayer', 'bone_cone_angle'), ID)]),
 'unearth_construct': dict(main=None, checks=[
    ('summon.max', ('UnearthPlayer', 'base_number_of_skeletal_constructs_allowed'), ID),
    ('summon.duration', ('UnearthPlayer', 'base_skill_effect_duration'), MS)]),
 'dark_idol': dict(main='DarkEffigyPlayer', dmg=('DarkEffigyProjectilePlayer', 0), checks=[
    ('modifiers[spell.base_crit/FLAT].value', '_crit:DarkEffigyProjectilePlayer', ID),
    ('totem.duration', ('DarkEffigyPlayer', 'base_totem_duration'), MS),
    ('level_modifiers[totem.resist_chaos/FLAT].value', ('DarkEffigyPlayer', 'totem_chaos_resistance_%'), ID),
    ('level_modifiers[totem.resist_elemental/FLAT].value', ('DarkEffigyPlayer', 'totem_elemental_resistance_%'), ID)]),
 'storm_staff': dict(main='ChargedStaffPlayer', checks=[
    ('level_modifiers[charged_staff.min/FLAT].value', ('ChargedStaffPlayer', 'charged_staff_attack_minimum_added_lightning_damage_per_stack'), ID),
    ('level_modifiers[charged_staff.max/FLAT].value', ('ChargedStaffPlayer', 'charged_staff_attack_maximum_added_lightning_damage_per_stack'), ID)]),
 'ruin_mantra': dict(main='MantraOfDestructionPlayer', skip={'cast'}, checks=[
    ('guard.duration', ('MantraOfDestructionPlayer', 'base_skill_effect_duration'), MS),
    ('level_modifiers[mantra.chaos/FLAT].value', ('MantraOfDestructionPlayer', 'mantra_of_destruction_grant_all_damage_%_to_gain_as_chaos_with_attacks'), ID)]),
 'attrition': dict(main='AttritionPlayer', skip={'cast'}, checks=[
    ('level_modifiers[attrition.cap/FLAT].value', ('AttritionPlayer', 'skill_attrition_presence_max_seconds'), ID),
    ('level_modifiers[attrition.cull/FLAT].value', ('AttritionPlayer', 'skill_attrition_culling_strike_at_x_or_more_stacks'), ID)]),
 'mana_storm': dict(main='ManaTempestPlayer', checks=[
    ('level_modifiers[damage.extra.lightning/FLAT].value', ('ManaTempestPlayer', 'non_skill_base_all_damage_%_to_gain_as_lightning_with_spells_from_buff'), ID),
    ('level_modifiers[skill.split/FLAT].value', ('ManaTempestPlayer', 'mana_tempest_empowered_spells_split_to_X_additional_targets'), ID),
    ('guard.duration', ('ManaTempestPlayer', 'mana_tempest_effects_linger_X_ms'), MS)]),
 'flame_stream': dict(main='IncineratePlayer', skip={'mana'}, checks=[
    ('cone.length', ('IncineratePlayer', 'active_skill_base_area_of_effect_radius', 0), DM),
    ('cone.angle', ('IncineratePlayer', 'incinerate_cone_angle', 0), ID)],
    dot=('IncineratePlayer', 'base_fire_damage_to_deal_per_minute', 0), dot_div=4,
    note='焚流的 damage 是每 0.25 秒一下（PoE2 每秒火焰傷害 ÷ 4）；PoE2 0.5 的 Incinerate 沒有魔力消耗（燃料），mana 維持原值'),
 'raging_spirits_dummy': None,
 'skeletal_brute': dict(main=None, command=('CommandSkeletalBrutesPlayer', 'skeletal_brute')),
 'skeletal_archers': dict(main=None, command=('CommandSkeletalSniperPlayer', 'skeletal_archers')),
 'skeletal_pyromancer': dict(main=None, command=('CommandSkeletalArsonistPlayer', 'skeletal_pyromancer'), checks=[
    ('java:skill/MinionAbilities.java:DEMISE_THRESHOLD', ('CommandSkeletalArsonistPlayer', 'command_minion_target_ally_below_x_life'), ID)]),
 'skeletal_stormcaller': dict(main=None, command=('CommandSkeletalStormMagePlayer', 'skeletal_stormcaller')),
 'skeletal_frostcaller': dict(main=None, command=('CommandSkeletalFrostMagePlayer', 'skeletal_frostcaller')),
 'skeletal_reaver': dict(main=None, command=('CommandSkeletalReaversPlayer', 'skeletal_reaver')),
 'skeletal_cleric': dict(main=None, checks=[
    ('java:skill/MinionAbilities.java:HEAL_PER_MINUTE', ('HealSkeletonClericMinion', 'skeletal_cleric_grants_base_life_regeneration_rate_per_minute'), ID)]),
 'defiance_banner': dict(main='DefianceBannerPlayer', skip={'cast'}, checks=[
    ('warcry.radius', ('DefianceBannerPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.glory', ('DefianceBannerPlayer', 'active_skill_requires_X_glory'), ID),
    ('warcry.duration', ('DefianceBannerPlayer', 'base_skill_effect_duration'), lambda x: 6.0),
    ('level_modifiers[skill.duration/INCREASED].value', ('DefianceBannerPlayer', 'base_skill_effect_duration'), lambda x: (x / 6000 - 1) * 100, 0.006),
    ('warcry.buff[armour/MORE].value', 30), ('warcry.buff[evasion/MORE].value', 30), ('warcry.buff[movement.speed/INCREASED].value', 30)],
    unrep=['戰旗保留 30 精魂（DefianceBannerReservationPlayer）：戰旗是插下去的戰吼，不保留精魂']),
 'war_banner': dict(main='WarBannerPlayer', skip={'cast'}, checks=[
    ('warcry.radius', ('WarBannerPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.glory', ('WarBannerPlayer', 'active_skill_requires_X_glory'), ID),
    ('warcry.duration', ('WarBannerPlayer', 'base_skill_effect_duration'), lambda x: 6.0),
    ('level_modifiers[skill.duration/INCREASED].value', ('WarBannerPlayer', 'base_skill_effect_duration'), lambda x: (x / 6000 - 1) * 100, 0.006),
    ('warcry.buff[damage/MORE].value', 25), ('warcry.buff[accuracy/INCREASED].value', 50), ('warcry.buff[attack.speed/INCREASED].value', 25)],
    unrep=['戰旗保留 30 精魂（WarBannerReservationPlayer）']),
 'dread_banner': dict(main='DreadBannerPlayer', skip={'cast'}, checks=[
    ('warcry.radius', ('DreadBannerPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.glory', ('DreadBannerPlayer', 'active_skill_requires_X_glory'), ID),
    ('warcry.duration', ('DreadBannerPlayer', 'base_skill_effect_duration'), lambda x: 6.0),
    ('level_modifiers[skill.duration/INCREASED].value', ('DreadBannerPlayer', 'base_skill_effect_duration'), lambda x: (x / 6000 - 1) * 100, 0.006),
    ('warcry.buff[resist.max.fire/FLAT].value', 4), ('warcry.buff[resist.max.cold/FLAT].value', 4), ('warcry.buff[resist.max.lightning/FLAT].value', 4),
    ('warcry.buff[ailment.threshold/MORE].value', 60),
    ('warcry.buff[flask.charges_per_minute.life/FLAT].value', 30), ('warcry.buff[flask.charges_per_minute.mana/FLAT].value', 30)],
    unrep=['戰旗保留 30 精魂（DreadBannerReservationPlayer）']),
 'frostbolt': dict(main='FrostboltPlayer', dmg=('FrostboltPlayer', 0), checks=[
    ('level_modifiers[chill.effect/MORE].value', ('FrostboltPlayer', 'active_skill_chill_effect_+%_final', 0), ID)]),
 'ball_lightning': dict(main='BallLightningPlayer', dmg=('BallLightningPlayer', 0), checks=[
    ('modifiers[shock.chance/MORE].value', ('BallLightningPlayer', 'active_skill_shock_chance_+%_final', 0), ID),
    ('projectile.splash.radius', ('BallLightningPlayer', 'active_skill_base_area_of_effect_radius', 0), DM)]),
 'essence_drain': dict(main='EssenceDrainPlayer', dmg=('EssenceDrainPlayer', 0), checks=[
    ('modifiers[debuff.duration/FLAT].value', ('EssenceDrainPlayer', 'base_skill_effect_duration', 1), MS),
    ('level_modifiers[debuff.chaos_base/FLAT].value', ('EssenceDrainPlayer', 'base_chaos_damage_to_deal_per_minute', 1), PM, 0.051)]),
 'contagion': dict(main='ContagionPlayer', checks=[
    ('modifiers[debuff.duration/FLAT].value', ('ContagionPlayer', 'base_skill_effect_duration'), MS),
    ('impact.radius', ('ContagionPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('level_modifiers[debuff.chaos_base/FLAT].value', ('ContagionPlayer', 'base_chaos_damage_to_deal_per_minute'), PM, 0.051)]),
 'arctic_howl': dict(main='WolfArcticHowlPlayer', dmg=('WolfArcticHowlPlayer', 0), checks=[
    ('warcry.radius', ('WolfArcticHowlPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.duration', ('WolfArcticHowlPlayer', 'base_skill_effect_duration'), MS),
    ('warcry.empower.cold_per_5_power.min', ('WolfArcticHowlPlayer', 'wolf_warcry_empowered_attack_cold_damage_min_per_5_power_up_to_cap'), ID),
    ('warcry.empower.cold_per_5_power.max', ('WolfArcticHowlPlayer', 'wolf_warcry_empowered_attack_cold_damage_max_per_5_power_up_to_cap'), ID),
    ('warcry.ally_cold_per_5_power.min', ('WolfArcticHowlPlayer', 'wolf_warcry_buff_cold_damage_min_per_5_power_up_to_cap'), ID),
    ('warcry.ally_cold_per_5_power.max', ('WolfArcticHowlPlayer', 'wolf_warcry_buff_cold_damage_max_per_5_power_up_to_cap'), ID)]),
 'ferocious_roar': dict(main='FerociousRoarMetaPlayer', checks=[
    ('warcry.radius', ('FerociousRoarMetaPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.empower.per', ('FerociousRoarMetaPlayer', 'warcry_empowers_per_X_monster_power'), ID),
    ('warcry.empower.cap', ('FerociousRoarMetaPlayer', 'warcry_empowers_per_X_monster_power_mp_cap'), ID),
    ('warcry.rage_per_power', ('FerociousRoarMetaPlayer', 'bear_warcry_gain_X_centirage_per_enemy_power'), lambda x: x / 100),
    ('warcry.empower.armour_break', ('FerociousRoarMetaPlayer', 'warcry_grant_break_X_armour_on_hit'), ID),
    ('java:skill/SocketedWarcry.java:DAMAGE', ('SupportFerociousRoarPlayer', 'damage_+%'), ID)]),
 'lunar_blessing': dict(main='WolfLunarBlessingPlayer', checks=[
    ('warcry.radius', ('WolfLunarBlessingPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.duration', ('WolfLunarBlessingPlayer', 'base_skill_effect_duration'), MS),
    ('warcry.rage_extend', ('WolfLunarBlessingPlayer', 'skill_lunar_blessing_extend_buff_by_X_ms_per_rage'), MS),
    ('warcry.buff[damage.extra.cold/FLAT].value', ('WolfLunarBlessingPlayer', 'wolf_lunar_blessing_all_damage_%_to_gain_as_cold_damage'), ID),
    ('warcry.minion_buff[damage.extra.cold/FLAT].value', ('WolfLunarBlessingPlayer', 'wolf_lunar_blessing_all_damage_%_to_gain_as_cold_damage'), ID)]),
 'ancestral_cry': dict(main='AncestralCryPlayer', checks=[
    ('warcry.radius', ('AncestralCryPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('warcry.duration', ('AncestralCryPlayer', 'base_skill_effect_duration'), MS),
    ('warcry.glory', ('AncestralCryPlayer', 'active_skill_requires_X_glory'), ID),
    ('warcry.duration_per_endurance', ('AncestralCryPlayer', 'ancestral_cry_duration_+%_final_per_removable_endurance_charge'), ID),
    ('warcry.empower.extra_targets', ('AncestralCryPlayer', 'ancestral_cry_empowered_attacks_strike_X_additional_enemies'), ID)]),
 'thunderstorm': dict(main='ThunderstormPlayer', dmg=('ThunderstormPlayer', 0), checks=[
    ('zone.radius', ('ThunderstormPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('zone.duration', ('ThunderstormPlayer', 'base_skill_effect_duration'), MS)]),
 'primal_tornado': dict(main='TornadoPlayer', checks=[
    ('zone.radius', ('TornadoPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('zone.duration', ('TornadoPlayer', 'base_skill_effect_duration'), MS)],
    dot=('TornadoPlayer', 'base_physical_damage_to_deal_per_minute', 0),
    note='旋風的 damage 是每秒物理（PoE2 base_physical_damage_to_deal_per_minute ÷ 60）'),
 'thrashing_vines': dict(main='ThrashingVinesPlayer', dmg=('ThrashingVinesPlayer', 0), checks=[
    ('impact.radius', ('ThrashingVinesPlayer', 'active_skill_base_area_of_effect_radius'), DM)]),
 'volcano': dict(main='VolcanoPlayer', dmg=('VolcanoPlayer', 0), checks=[
    ('zone.radius', ('VolcanoPlayer', 'active_skill_base_secondary_area_of_effect_radius', 0), DM)],
    unrep=['Volcano 噴出的火山彈 3→5→6 顆（VolcanoProjectilePlayer base_number_of_projectiles）、引導充能 4 階']),
 'entangle': dict(main='EntanglePlayer', unrep=[
    'Entangle 實際是藤蔓（每條 1–1 → 29–43 物理、根數上限 4→8→13、每層移動 -15%）；我們做成詛咒式的緩速＋受到物理傷害增加，沒有逐級可以對']),
 'barkskin': dict(main='BarkskinPlayer', skip={'cast'}, checks=[
    ('aura.effects[barkskin.armour/FLAT].value', ('BarkskinPlayer', 'barkskin_armour_to_gain_as_%_of_energy_shield_lost_to_enemy_hits'), ID)]),
 'briarpatch': dict(main='BriarpatchPlayer', skip={'cast'}, unrep=[
    'Briarpatch 存物理傷害 1%（上限 9→256→3,424）長荊棘地；我們做成荊棘反傷 thorns [10, 60]／[20, 110]，沒有逐級可以對']),
 'savage_fury': dict(main='SavageFuryPlayer', skip={'cast'}, checks=[
    ('aura.fury.max', ('SavageFuryPlayer', 'wild_beast_maximum_energy'), ID),
    ('aura.fury.effects[damage/MORE].value', ('SavageFuryActivePlayer', 'wild_beast_damage_+%_final'), ID),
    ('aura.fury.life_loss', ('SavageFuryActivePlayer', 'wild_beast_life_lost_%_per_minute_nonlethal'), PM)]),
 'wolf_pack': dict(main='WolfPackPlayer', skip={'cast'}, checks=[
    ('summon.max', ('WolfPackPlayer', 'skill_wolf_pack_size'), ID)]),
 'frost_darts': dict(main='FrostDartsPlayer', dmg=('FrostDartsPlayer', 0), checks=[
    ('projectile.count', ('FrostDartsPlayer', 'base_number_of_projectiles', 0), ID)]),
 'profane_ritual': dict(main='ProfaneRitualPlayer', checks=[
    ('zone.radius', ('ProfaneRitualPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('zone.duration', ('ProfaneRitualPlayer', 'base_skill_effect_duration'), MS),
    ('zone.max', ('ProfaneRitualPlayer', 'ritual_of_power_maximum_number_of_rituals'), ID)],
    dot=('ProfaneRitualPlayer', 'base_chaos_damage_to_deal_per_minute', 0)),
 'berserk': dict(main='BerserkPlayer', skip={'cast'}, checks=[
    ('aura.effects[rage.effect/INCREASED].value', ('BerserkPlayer', 'skill_base_rage_effect_+%_to_apply'), ID),
    ('aura.life_cost.per_rage', ('BerserkPlayer', 'skill_base_life_loss_%_per_minute_per_rage_while_not_losing_rage_to_apply'), PM, 0.001)]),
 'overwhelming_presence': dict(main='OverwhelmingPresencePlayer', skip={'cast'}, checks=[
    ('aura.enemy.effects[stun.threshold/INCREASED].value', ('OverwhelmingPresencePlayer', 'skill_overwhelming_pressure_aura_enemy_stun_threshold_+%'), ID),
    ('aura.enemy.effects[ailment.threshold/INCREASED].value', ('OverwhelmingPresencePlayer', 'skill_overwhelming_pressure_aura_enemy_ailment_threshold_+%'), ID)]),
 'withering_presence': dict(main='WitheringPresencePlayer', skip={'cast'}, checks=[
    ('aura.enemy.withered_every', ('WitheringPresencePlayer', 'skill_withering_presence_frequency_ms'), MS)]),
 'voltaic_mark': dict(main='VoltaicMarkPlayer', checks=[
    ('mark.duration', ('VoltaicMarkPlayer', 'base_skill_effect_duration'), MS)],
    unrep=['Voltaic Mark：打被標記的敵人感電（電擊）累積增加 25→35→45%（thaumaturgist_mark_hit_damage_electrocute_multiplier_+%）、牠死時你獲得 30% 傷害為額外閃電；'
           '我們做成降閃電抗性 [-15, -30]＋受到傷害增加 10%（沒有「對某一個目標的累積倍率」）']),
 'freezing_mark': dict(main='FreezingMarkPlayer', checks=[
    ('mark.duration', ('FreezingMarkPlayer', 'base_skill_effect_duration'), MS)],
    unrep=['Freezing Mark：打被標記的敵人凍結累積更多 25→35→45%、牠死時你獲得 30% 傷害為額外冰冷；我們做成降冰冷抗性 [-15, -30]＋異常門檻 -30%']),
 'bloodhounds_mark': dict(main='BloodhoundsMarkPlayer', checks=[
    ('mark.duration', ('BloodhoundsMarkPlayer', 'base_skill_effect_duration'), MS)]),
 'ice_tipped_arrows': dict(main='IceTippedArrowsPlayer'),
 'linked_volley': dict(main='BarragePlayer', checks=[
    ('guard.duration', ('BarragePlayer', 'base_skill_effect_duration'), MS),
    ('barrage.repeats', ('BarragePlayer', 'empower_barrage_base_number_of_barrage_repeats'), ID),
    ('barrage.per_charge', ('BarragePlayer', 'empower_barrage_number_of_barrage_repeats_per_frenzy_charge'), ID),
    ('barrage.less', ('BarragePlayer', 'empower_barrage_damage_-%_final_with_repeated_projectiles'), ID),
    ('barrage.cooldown_attack_time', ('BarragePlayer', 'empower_barrage_cooldown_%_of_attack_time'), ID),
    ('barrage.max_cooldown', ('BarragePlayer', 'empowered_barrage_maximum_cooldown_ms'), MS)]),
 'emergency_reload': dict(main='EmergencyReloadPlayer', skip={'cast'}, checks=[
    ('guard.duration', ('EmergencyReloadPlayer', 'base_skill_effect_duration'), MS),
    ('guard.effects[damage/MORE].value', ('EmergencyReloadPlayer', 'emergency_reload_damage_+%_final'), ID)]),
 'blink': dict(main='BlinkPlayer', skip={'mana', 'cast', 'cooldown'}, checks=[
    ('cooldown', ('BlinkPlayer', '_cooldown'), lambda x: 4.5),
    ('level_modifiers[skill.cooldown/MORE].value', ('BlinkPlayer', '_cooldown'), lambda x: (x / 4.5 - 1) * 100, 0.006)],
    unrep=['Blink 保留 60 精魂、不花魔力（BlinkReservationPlayer）：位移技能不能保留精魂，維持每次 [4, 8] 魔力',
           'Blink 距離 4→4.9→5.5 米（blink_travel_distance）：dash.range 不能隨等級，取 5']),
 'time_of_need': dict(main='TimeOfNeedPlayer', skip={'cast'}, checks=[
    ('aura.effects[blessing.life/FLAT].value', ('TimeOfNeedPlayer', 'skill_time_of_need_gain_x_life'), ID),
    ('level_modifiers[blessing.interval/FLAT].value', ('TimeOfNeedPlayer', 'base_cooldown_modifiable_repeat_interval_ms'), MS)]),
 'ghost_dance': dict(main='GhostDancePlayer', skip={'cast'}, checks=[
    ('aura.effects[shroud.es_regen/FLAT].value', ('GhostDancePlayer', 'skill_base_ghost_dance_grants_%_evasion_as_es_regeneration_per_minute_if_have_lost_ghost_dance_shroud_recently'), PM),
    ('level_modifiers[shroud.interval/FLAT].value', ('GhostDancePlayer', 'base_cooldown_modifiable_repeat_interval_ms'), MS)]),
 'scavenged_plating': dict(main='ScavengedPlatingPlayer', skip={'cast'}, unrep=[
    'Scavenged Plating：破甲時疊層（每層護甲更多 4%、最多 10 層、8 秒）＋荊棘 2–2 → 43–64 → 124–186；我們做成護甲／破甲增加 [20, 40]，沒有逐級可以對']),
 'eternal_rage': dict(main='EternalRagePlayer', skip={'cast'}, checks=[
    ('aura.effects[rage.regen/FLAT].value', ('EternalRagePlayer', 'ceaseless_rage_base_rage_regeneration_per_minute'), PM)]),
 'mana_remnants': dict(main='ManaRemnantsPlayer', skip={'cast'}, unrep=[
    'Mana Remnants：暴擊異常中的敵人每 2 秒、擊殺異常中的敵人 25% 留下殘骸，每個回 28→281→738 魔力；我們做成魔力回復增加 [20, 40]＋擊殺回 [1, 2] 魔力，沒有殘骸']),
 'alchemists_boon': dict(main='AlchemistsBoonPlayer', skip={'cast'}, checks=[
    ('aura.effects[flask.charges_per_minute.life/FLAT].value', ('AlchemistsBoonPlayer', 'skill_alchemists_boon_generate_x_charges_for_any_flask_per_minute'), ID),
    ('aura.effects[flask.charges_per_minute.mana/FLAT].value', ('AlchemistsBoonPlayer', 'skill_alchemists_boon_generate_x_charges_for_any_flask_per_minute'), ID)],
    unrep=["Alchemist's Boon：藥劑回復也給範圍內的隊友 15→34→54%"]),
 'ravenous_swarm': dict(main='RavenousSwarmPlayer', skip={'cast'}, checks=[
    ('summon.max', ('RavenousSwarmPlayer', 'ravenous_swarm_maximum_swarm_count'), ID),
    ('summon.duration', ('RavenousSwarmPlayer', 'base_skill_effect_duration'), lambda x: 6.0),
    ('summon.interval', ('RavenousSwarmPlayer', 'base_cooldown_modifiable_repeat_interval_ms'), MS),
    ('level_modifiers[skill.duration/INCREASED].value', ('RavenousSwarmPlayer', 'base_skill_effect_duration'), lambda x: (x / 6000 - 1) * 100, 0.006)]),
 'lingering_illusion': dict(main='LingeringIllusionPlayer', skip={'cast'}, checks=[
    ('aura.effects[charge.remnant_extra/FLAT].value', ('LingeringIllusionSpawnPlayer', 'chance_%_to_create_additional_remnant'), ID)]),
 'trail_of_caltrops': dict(main='TrailOfCaltropsPlayer', skip={'cast'}),
 'mirage_archer': dict(main='MetaMirageArcherPlayer', skip={'cast'}, unrep=['Mirage Archer 幻影持續 5.05→6→7 秒（MirageArcherSpawnPlayer）：我們的幻影一直跟著']),
 'plague_bearer': dict(main='PlagueBearerPlayer', skip={'cast'}, checks=[
    ('java:skill/StoredReleases.java:PLAGUE_CAP', ('PlagueBearerPlayer', 'plague_bearer_maximum_stored_poison_damage'), ID)]),
 'iron_ward': dict(main='IronWardPlayer', skip={'cast'}, checks=[
    ('java:skill/StoredReleases.java:IRON_WARD_CAP', ('IronWardPlayer', 'warding_steel_maximum_stored_mitigated_physical_damage'), ID),
    ('java:skill/StoredReleases.java:IRON_WARD_PERCENT', ('IronWardPlayer', 'warding_steel_gains_%_of_damage_from_mitigated_physical_damage'), ID)]),
 'wind_dancer': dict(main='WindDancerPlayer', skip={'cast'}, unrep=[
    'Wind Dancer 每 1.5→1.12→0.82 秒疊一階（最多 3 階、每階閃避更多 10%）、被擊中時放疾風：我們直接給滿階閃避更多 30%']),
 'magma_barrier': dict(main='MagmaBarrierPlayer', skip={'cast'}, checks=[
    ('aura.effects[block.chance/INCREASED].value', ('MagmaBarrierPlayer', 'skill_igneous_shield_grants_block_chance_+%'), ID)]),
 'combat_frenzy': dict(main='CombatFrenzyPlayer', skip={'cast'}, checks=[
    ('aura.effects[charge.event_cooldown.%s/FLAT].value' % e, ('CombatFrenzyPlayer', 'skill_combat_frenzy_x_ms_cooldown'), MS)
    for e in ('freeze', 'electrocute', 'pin')]),
 'archmage': dict(main='ArchmagePlayer', skip={'cast'}, checks=[
    ('aura.effects[damage.extra_per_mana.lightning/FLAT].value', ('ArchmagePlayer', 'archmage_all_damage_%_to_gain_as_lightning_to_grant_to_non_channelling_spells_per_100_max_mana'), ID),
    ('aura.effects[skill.cost_max_mana/FLAT].value', ('ArchmagePlayer', 'archmage_max_mana_permyriad_to_add_to_non_channelled_spell_mana_cost'), lambda x: x / 100)]),
 'elemental_conflux': dict(main='ElementalConfluxPlayer', skip={'cast'}, checks=[
    ('aura.effects[damage/MORE].value', ('ElementalConfluxPlayer', 'skill_elemental_conflux_active_element_damage_+%_final'), ID)]),
 'herald_plague_burst': dict(main=None, checks=[
    ('nova.radius', ('HeraldOfPlaguePlayer', 'herald_of_agony_poison_spread_on_kill_radius_base_magnitude_to_grant'), DM)]),
 'bone_cage': dict(main='BoneCagePlayer', dmg=('BoneCagePlayer', 0), checks=[
    ('zone.radius', ('BoneCagePlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('zone.duration', ('BoneCagePlayer', 'base_skill_effect_duration'), MS)]),
 'snap': dict(main='SnapPlayer', dmg=('SnapPlayer', 0), checks=[
    ('impact.radius', ('SnapPlayer', 'active_skill_base_area_of_effect_radius', 0), DM),
    ('level_modifiers[snap.lightning_min/FLAT].value', ('SnapPlayer', 'spell_minimum_base_lightning_damage', 1), ID),
    ('level_modifiers[snap.lightning_max/FLAT].value', ('SnapPlayer', 'spell_maximum_base_lightning_damage', 1), ID),
    ('level_modifiers[snap.fire_min/FLAT].value', ('SnapPlayer', 'spell_minimum_base_fire_damage', 2), ID),
    ('level_modifiers[snap.fire_max/FLAT].value', ('SnapPlayer', 'spell_maximum_base_fire_damage', 2), ID)]),
 'charge_regulation': dict(main='ChargeRegulationPlayer', skip={'cast'}, checks=[
    ('aura.effects[crit.chance/MORE].value', ('ChargeRegulationPlayer', 'charge_mastery_crit_chance_+%_final_with_power_charges'), ID),
    ('aura.effects[attack.speed/INCREASED].value', ('ChargeRegulationPlayer', 'charge_mastery_skill_speed_+%_with_frenzy_charges'), ID),
    ('aura.effects[cast.speed/INCREASED].value', ('ChargeRegulationPlayer', 'charge_mastery_skill_speed_+%_with_frenzy_charges'), ID)] + [
    ('aura.effects[%s/MORE].value' % d, ('ChargeRegulationPlayer', 'charge_mastery_armour_evasion_energy_shield_+%_final_with_endurance_charges'), ID)
    for d in ('armour', 'evasion', 'es.max')]),
 'convalescence': dict(main='ConvalescencePlayer', skip={'cast'}, checks=[
    ('level_modifiers[convalescence.duration/FLAT].value', ('ConvalescenceActivePlayer', 'base_skill_effect_duration'), MS)]),
 'grim_feast': dict(main='GrimFeastPlayer', skip={'cast'}, checks=[
    ('level_modifiers[grim.cap/FLAT].value', ('GrimFeastPlayer', 'grim_remnant_max_health_stored'), ID)]),
 'sacrifice': dict(main='SacrificePlayer', skip={'cast'}, checks=[
    ('level_modifiers[sacrifice.slower/FLAT].value', ('SacrificePlayer', 'harvester_minion_resummon_speed_+%_final'), ID)]),
 'shard_scavenger': dict(main='ShardScavengerPlayer', skip={'cast'}, checks=[
    ('level_modifiers[shard.interval/FLAT].value', ('ShardScavengerPlayer', 'base_secondary_skill_effect_duration'), MS)]),
 'siphon_elements': dict(main='SiphonElementsPlayer', skip={'cast'}, checks=[
    ('aura.effects[remnant.pickup_range/INCREASED].value', ('SiphonElementsPlayer', 'remnant_pickup_range_+%'), ID),
    ('aura.effects[infusion.remnant_on_ailment/FLAT].value', ('SiphonElementsPlayer', 'infusion_remnants_%_chance_to_spawn_cold_infusion_on_freezing_an_enemy'), ID)]),
 'elemental_trinity': dict(main='TrinityPlayer', skip={'cast'}, checks=[
    ('aura.effects[trinity.gain/FLAT].value', ('TrinityPlayer', 'trinity_resonance_to_grant'), ID),
    ('aura.effects[trinity.more/FLAT].value', ('TrinityPlayer', 'trinity_damage_+%_final_to_grant_per_50_resonance'), ID)]),
 'parry': dict(main=None, checks=[
    ('guard.effects[stun.threshold/INCREASED].value', ('ParryPlayer', 'stun_threshold_+%_final_while_performing_action'), ID)],
    unrep=['Parry 在 PoE2 是攻擊（副手盾 65% 傷害、只有 1 級），招架成功給敵人受到的傷害增加 50%（2 秒）；我們做成舉盾式的招架姿勢']),
 'summon_mount': dict(main='RhoaMountPlayer', skip={'cast', 'spirit'}, unrep=['Rhoa Mount 保留 100 精魂：坐騎是位移技能，不保留精魂']),
 # ---- 寶石本身的隱藏部分（使者的爆炸、放出、幻身）----
 'herald_ash_burst': dict(main=None, checks=[
    ('nova.radius', ('HeraldOfAshPlayer', 'active_skill_base_area_of_effect_radius', 1), DM)],
    note='燼使不放這招（照過量擊殺點燃，AuraService），damage [9, 15] 只是名字用的'),
 'proc_lingering_illusion': dict(main=None, checks=[
    ('summon.duration', ('LingeringIllusionSpawnPlayer', 'base_skill_effect_duration'), MS)]),
 'plague_bearer_release': dict(main=None, checks=[
    ('nova.radius', ('PlagueBearerNovaPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('modifiers[stun.buildup/MORE].value', ('PlagueBearerNovaPlayer', 'active_skill_hit_damage_stun_multiplier_+%_final'), ID),
    ('modifiers[poison.chance/FLAT].value', ('PlagueBearerNovaPlayer', 'base_chance_to_poison_on_hit_%'), ID)]),
 'iron_ward_release': dict(main=None, checks=[
    ('nova.radius', ('IronWardNovaPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('modifiers[stun.buildup/MORE].value', ('IronWardNovaPlayer', 'active_skill_hit_damage_stun_multiplier_+%_final'), ID)]),
 'totem_casting': dict(main='SummonMetaTotemSpellTotemPlayer', checks=[
    ('meta.totems', ('SummonMetaTotemSpellTotemPlayer', 'base_number_of_totems_allowed'), ID),
    ('meta.charges', ('SummonMetaTotemSpellTotemPlayer', 'active_skill_requires_X_power_or_endurance_charges'), ID),
    ('meta.per_power', ('SummonMetaTotemSpellTotemPlayer', 'spell_totem_damage_+%_final_per_power_charge_consumed'), ID),
    ('meta.per_endurance', ('SummonMetaTotemSpellTotemPlayer', 'spell_totem_maximum_life_+%_final_per_endurance_charge_consumed'), ID),
    ('meta.less_cast_speed', ('SupportMetaTotemSpellTotemPlayer', 'support_spell_totem_cast_speed_+%_final'), NEG),
    ('level_modifiers[totem.resist_elemental/FLAT].value', ('SummonMetaTotemSpellTotemPlayer', 'totem_elemental_resistance_%'), ID),
    ('level_modifiers[totem.resist_chaos/FLAT].value', ('SummonMetaTotemSpellTotemPlayer', 'totem_chaos_resistance_%'), ID)]),
 'meta_crit': dict(main='MetaCastOnCritPlayer', skip={'cast'}, checks=[
    ('meta.energy_more', ('MetaCastOnCritPlayer', 'energy_generated_+%'), ID),
    ('meta.less_damage', ('SupportMetaCastOnCritPlayer', 'trigger_meta_gem_damage_+%_final'), NEG)]),
 'meta_ailment': dict(main='MetaCastOnElementalAilmentPlayer', skip={'cast'}, checks=[
    ('meta.energy_more', ('MetaCastOnElementalAilmentPlayer', 'energy_generated_+%'), ID)]),
 'meta_dodge': dict(main='MetaCastOnDodgePlayer', skip={'cast'}, checks=[
    ('meta.energy_more', ('MetaCastOnDodgePlayer', 'energy_generated_+%'), ID)]),
 'meta_minion_death': dict(main='MetaCastOnMinionDeathPlayer', skip={'cast'}, checks=[
    ('meta.energy_more', ('MetaCastOnMinionDeathPlayer', 'energy_generated_+%'), ID)]),
 'meta_elemental_invocation': dict(main='MetaElementalInvocationPlayer', skip={'cast'}, checks=[
    ('meta.energy_more', ('MetaElementalInvocationPlayer', 'energy_generated_+%'), ID),
    ('meta.max_energy', ('MetaElementalInvocationPlayer', 'generic_ongoing_trigger_maximum_energy'), ID)]),
 'meta_reaper_invocation': dict(main='MetaReapersInvocationPlayer', skip={'cast'}, checks=[
    ('meta.energy_more', ('MetaReapersInvocationPlayer', 'energy_generated_+%'), ID),
    ('meta.energy', ('MetaReapersInvocationPlayer', 'melee_kill_invocation_gain_X_centienergy_per_monster_power_on_melee_kill'), lambda x: x / 100),
    ('meta.max_energy', ('MetaReapersInvocationPlayer', 'generic_ongoing_trigger_maximum_energy'), ID)]),
 'meta_barrier_invocation': dict(main='MetaBarrierInvocationPlayer', skip={'cast'}, checks=[
    ('meta.energy_more', ('MetaBarrierInvocationPlayer', 'energy_generated_+%'), ID),
    ('meta.energy', ('MetaBarrierInvocationPlayer', 'barrier_invocation_gain_1_energy_per_X_energy_shield_damage_taken_when_hit'), lambda x: 1 / x),
    ('meta.max_energy', ('MetaBarrierInvocationPlayer', 'generic_ongoing_trigger_maximum_energy'), ID)]),
 # ---- 武器給的法術（PoE2 法杖、長杖、權杖本身帶的技能；不花魔力）----
 'chaos_bolt': dict(main='WeaponGrantedChaosboltPlayer', dmg=('WeaponGrantedChaosboltPlayer', 0), checks=[
    ('level_modifiers[withered.on_hit/FLAT].value', ('WeaponGrantedChaosboltPlayer', 'apply_x_wither_on_hit'), ID)]),
 'bone_blast': dict(main='BoneBlastPlayer', dmg=('BoneBlastPlayer', 0), checks=[
    ('impact.radius', ('BoneBlastPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('modifiers[bleed.chance/FLAT].value', ('BoneBlastPlayer', 'base_chance_to_inflict_bleeding_%'), ID)]),
 'mana_drain': dict(main='ManaDrainPlayer', checks=[
    ('level_modifiers[mana.on_hit/FLAT].value', ('ManaDrainPlayer', 'mana_drain_base_mana_leech_amount'), ID)]),
 'power_siphon': dict(main='PowerSiphonPlayer', dmg=('PowerSiphonPlayer', 0), checks=[
    ('level_modifiers[charge.extra/FLAT].value', ('PowerSiphonPlayer', 'chance_to_gain_1_more_charge_%'), ID)]),
 'volatile_dead': dict(main='VolatileDeadPlayer', dmg=('VolatileDeadPlayer', 1), skip={'crit'}, checks=[
    ('corpse.radius', ('VolatileDeadPlayer', 'active_skill_base_area_of_effect_radius', 1), DM),
    ('level_modifiers[as_though.min/FLAT].value', ('VolatileDeadPlayer', 'spell_minimum_base_fire_damage', 2), ID),
    ('level_modifiers[as_though.max/FLAT].value', ('VolatileDeadPlayer', 'spell_maximum_base_fire_damage', 2), ID)]),
 'galvanic_field': dict(main='GalvanicFieldBuffPlayer', dmg=('GalvanicFieldBuffPlayer', 1), skip={'crit'}, checks=[
    ('modifiers[spell.base_crit/FLAT].value', '_crit:GalvanicFieldBuffPlayer:1', ID),
    ('zone.radius', ('GalvanicFieldBuffPlayer', 'active_skill_base_area_of_effect_radius', 1), DM),
    ('zone.duration', ('GalvanicFieldBuffPlayer', 'base_skill_effect_duration', 1), MS),
    ('zone.interval', ('GalvanicFieldBuffPlayer', 'base_galvanic_field_beam_delay_ms', 1), MS)]),
 'decompose': dict(main='CorpseCloudPlayer', dmg=('CorpseCloudPlayer', 1), skip={'crit'}, checks=[
    ('modifiers[spell.base_crit/FLAT].value', '_crit:CorpseCloudPlayer:1', ID),
    ('corpse.radius', ('CorpseCloudPlayer', 'active_skill_base_area_of_effect_radius', 1), DM)]),
 'exsanguinate': dict(main='ExsanguinatePlayer', dmg=('ExsanguinatePlayer', 0), checks=[
    ('chain.chains', ('ExsanguinatePlayer', 'number_of_chains'), ID)]),
 'wither': dict(main='WitherPlayer', checks=[
    ('mark.radius', ('WitherPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('mark.duration', ('WitherPlayer', 'active_skill_withered_base_duration_ms'), MS)]),
 'coiling_bolts': dict(main='CoilingBoltsPlayer', checks=[
    ('damage_by_type.physical', ('CoilingBoltsPlayer', '_dmg', 0), ID),
    ('damage_by_type.chaos', ('CoilingBoltsPlayer', '_dmg', 1), ID),
    ('modifiers[skill.split/FLAT].value', ('CoilingBoltsPlayer', 'projectile_number_to_split', 0), ID)]),
 'firebolt': dict(main='FireboltPlayer', dmg=('FireboltPlayer', 0), checks=[
    ('projectile.explode', ('FireboltPlayer', 'active_skill_base_area_of_effect_radius', 1), DM)]),
 'freezing_shards': dict(main='FreezingShardsPlayer', dmg=('FreezingShardsPlayer', 0), checks=[
    ('projectile.count', ('FreezingShardsPlayer', 'base_number_of_projectiles'), ID)]),
 'lightning_bolt': dict(main='LightningBoltPlayer', dmg=('LightningBoltPlayer', 0), checks=[
    ('impact.radius', ('LightningBoltPlayer', 'active_skill_base_area_of_effect_radius'), DM)]),
 'sigil_of_power': dict(main='SigilOfPowerPlayer', checks=[
    ('guard.duration', ('SigilOfPowerPlayer', 'base_skill_effect_duration'), lambda x: 10.0),
    ('level_modifiers[skill.duration/INCREASED].value', ('SigilOfPowerPlayer', 'base_skill_effect_duration'), lambda x: (x / 10000 - 1) * 100, 0.006)]),
 'soulrend': dict(main='SoulrendPlayer', dmg=('SoulrendPlayer', 0), checks=[
    ('modifiers[debuff.duration/FLAT].value', ('SoulrendPlayer', 'base_skill_effect_duration', 1), MS),
    ('level_modifiers[debuff.chaos_base/FLAT].value', ('SoulrendPlayer', 'base_chaos_damage_to_deal_per_minute', 1), PM, 0.051)]),
 'reap': dict(main='ReapPlayer', dmg=('ReapPlayer', 0), checks=[
    ('impact.radius', ('ReapPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('level_modifiers[life.on_cull_flat/FLAT].value', ('ReapPlayer', 'active_skill_life_gained_on_cull'), ID)]),
 'enervating_nova': dict(main='EnervatingNovaPlayer', dmg=('EnervatingNovaPlayer', 0), checks=[
    ('nova.radius', ('EnervatingNovaPlayer', 'active_skill_base_area_of_effect_radius'), DM),
    ('level_modifiers[electrocute.buildup/MORE].value', ('EnervatingNovaPlayer', 'active_skill_electrocutes_as_though_dealt_damage_+%_final'), ID)]),
 'consecrate': dict(main='StaffConsecratePlayer', checks=[
    ('guard.duration', ('StaffConsecratePlayer', 'base_skill_effect_duration'), MS),
    ('guard.effects[life.regen_percent/FLAT].value', ('StaffConsecratePlayer', 'consecrated_ground_effect_+%'), lambda x: 5 * (1 + x / 100), 0.001)]),
 'dark_pact': dict(main='DarkPactPlayer', dmg=('DarkPactPlayer', 0), checks=[
    ('nova.radius', ('DarkPactPlayer', 'active_skill_base_area_of_effect_radius'), DM)]),
 'feast_of_flesh': dict(main='FeastOfFleshPlayer', checks=[
    ('guard.duration', ('FeastOfFleshPlayer', 'feast_of_flesh_recovery_duration'), MS),
    ('guard.effects[life.regen/FLAT].value', ('FeastOfFleshPlayer', 'feast_of_flesh_recover_X_life_per_corpse_consumed'), ID),
    ('guard.effects[mana.regen/FLAT].value', ('FeastOfFleshPlayer', 'feast_of_flesh_recover_X_mana_per_corpse_consumed'), ID)]),
 'heart_of_ice': dict(main='HeartOfIcePlayer', skip={'cast', 'crit'}, checks=[
    ('level_modifiers[as_though.min/FLAT].value', ('HeartOfIcePlayer', 'spell_minimum_base_cold_damage'), ID),
    ('level_modifiers[as_though.max/FLAT].value', ('HeartOfIcePlayer', 'spell_maximum_base_cold_damage'), ID)]),
 'discipline': dict(main='DisciplinePlayer', skip={'cast'}, checks=[
    ('aura.effects[es.max/FLAT].value', ('DisciplinePlayer', 'base_skill_buff_total_maximum_energy_shield_+_to_apply'), ID)]),
 'malice': dict(main='MalicePlayer', skip={'cast'}, checks=[
    ('aura.enemy.crit_weakness_every', ('MalicePlayer', 'critical_chance_against_aura_apply_brittle_every_x_ms'), MS)]),
 'purity_of_fire': dict(main='PurityOfFirePlayer', skip={'cast'}, checks=[
    ('aura.effects[resist.fire/FLAT].value', ('PurityOfFirePlayer', 'base_skill_buff_fire_damage_resistance_%_to_apply'), ID)]),
 'purity_of_ice': dict(main='PurityOfIcePlayer', skip={'cast'}, checks=[
    ('aura.effects[resist.cold/FLAT].value', ('PurityOfIcePlayer', 'base_skill_buff_cold_damage_resistance_%_to_apply'), ID)]),
 'purity_of_lightning': dict(main='PurityOfLightningPlayer', skip={'cast'}, checks=[
    ('aura.effects[resist.lightning/FLAT].value', ('PurityOfLightningPlayer', 'base_skill_buff_lightning_damage_resistance_%_to_apply'), ID)]),
 'impurity': dict(main='ImpurityPlayer', skip={'cast'}, checks=[
    ('aura.effects[resist.chaos/FLAT].value', ('ImpurityPlayer', 'base_skill_buff_chaos_damage_resistance_%_to_apply'), ID)]),
 'spellslinger': dict(main='MetaSpellslingerPlayer', skip={'cast'}, checks=[
    ('meta.energy_more', ('MetaSpellslingerPlayer', 'energy_generated_+%'), ID),
    ('meta.max_energy', ('MetaSpellslingerPlayer', 'generic_ongoing_trigger_maximum_energy'), ID)]),
 'unleash_spell': dict(main='UnleashPlayer', checks=[
    ('guard.duration', ('UnleashPlayer', 'base_skill_effect_duration'), MS),
    ('barrage.repeats', ('UnleashPlayer', 'staff_unleash_number_of_seals_for_next_skill'), ID),
    ('barrage.cooldown_attack_time', ('UnleashPlayer', 'staff_unleash_cooldown_%_of_cast_time'), ID)]),
 'fulmination': dict(main='FulminationPlayer', skip={'cast'}, checks=[
    ('level_modifiers[fulmination.less/FLAT].value', ('FulminationPlayer', 'skill_wrath_aura_shared_damage_+%_to_other_shocked_enemies_in_presence'), NEG)]),
 'mirror_of_refraction': dict(main='MirrorOfRefractionPlayer', skip={'cast'}, checks=[
    ('level_modifiers[mirror.interval/FLAT].value', ('MirrorOfRefractionPlayer', 'base_cooldown_modifiable_repeat_interval_ms'), MS)]),
 'spiraling_conspiracy': dict(main='SummonSpiralingConspiracyPlayer', checks=[
    ('level_modifiers[raven.interval/FLAT].value', ('SummonSpiralingConspiracyPlayer', 'tangmazu_raven_added_to_your_flock_every_x_msec_a_rare_or_unique_enemy_is_in_your_presence'), MS)]),
 # ---- 召喚（精魂）：每隻保留另外核對過（召喚物精魂保留核對），這裡只比數量、魔力等 ----
 'summon_skeletons': dict(main='SummonSkeletalWarriorsPlayer', skip={'cast'}, checks=[
    ('aura.free', ('SummonSkeletalWarriorsPlayer', 'first_X_minions_have_0_base_spirit_reservation'), ID),
    ('summon.count', ('SummonSkeletalWarriorsPlayer', 'skill_desired_initial_amount'), ID)]),
 'bound_spectre': dict(main='BindSpectrePlayer', skip={'cast', 'mana'}, unrep=[
    'Bind Spectre 綁定時施法 3 秒、花 22→99→243 魔力；我們綁在按鍵格常駐，沒有施放']),
 'beast_companion': dict(main='TameBeastPlayer', skip={'cast', 'mana'}, unrep=[
    'Tame Beast 是把野獸變成同伴的施法（8→11.8→14.8 秒的靈體、22→99→243 魔力）；我們的荒原狼伴是固定的狼，同伴的數值照 PoE2 召喚物表']),
}
S = {k: v for k, v in S.items() if v is not None}

NO_EQUIVALENT = {
 'proc_twisted_pact': '扭曲契約是專屬的輔助觸發技能，PoE2 0.5 沒有對應的寶石',
 'fortifying_shield_wave': '堅壁戰吼盾波：PoE2 FortifyingCryShockwavePlayer 是攻擊（副手武器傷害 × 50→204→905%），我們做成戰吼傷害（另一個代理對攻擊）',
 'proc_gain_endurance': '內部用（給戰星）', 'proc_gain_frenzy': '內部用', 'proc_gain_power': '內部用',
}


# ---------------------------------------------------------------- 比對

class Finding:
    def __init__(self, skill, field, ours, want, note='', fixable=True):
        self.skill, self.field, self.ours, self.want, self.note, self.fixable = skill, field, ours, want, note, fixable


def fmt(v):
    if isinstance(v, list) and len(v) == 2 and isinstance(v[0], list):
        return v
    if isinstance(v, float):
        r = round(v, 4)
        return int(r) if r == int(r) else r
    return v


def lv3(arr):
    return '%s / %s / %s' % (fmt(arr[0]), fmt(arr[19]), fmt(arr[39]))


def compare(ours40, want40, tol):
    bad = []
    for i, (a, b) in enumerate(zip(ours40, want40)):
        if b is None or a is None:
            continue
        if isinstance(b, list):
            if abs(a[0] - b[0]) > tol + 1e-9 or abs(a[1] - b[1]) > tol + 1e-9:
                bad.append(i + 1)
        elif abs(a - b) > tol + 1e-9:
            bad.append(i + 1)
    return bad


JAVA_FN = {
    # 這幾個在 Java 裡寫成公式（AuraService），照公式算出 1–40 級來比
    'ashPercent': lambda L: 50.0 / 3 + (max(1, L) - 1) / 3.0,
    'thunderCharges': lambda L: 5 if L >= 31 else 4 if L >= 21 else 3 if L >= 11 else 2,
    'plagueRadius': lambda L: 1.5 + 0.1 * ((min(40, max(1, L)) - 1) // 5),
    'plagueHinderChance': lambda L: 10 + (min(40, max(1, L)) - 1),
}


def source(r, src):
    if isinstance(src, (int, float)):
        return [float(src)] * 40
    if isinstance(src, str):
        parts = src.split(':')
        if parts[0] == '_costs':
            return r.costs(parts[1], parts[2])
        if parts[0] == '_crit':
            c = r.crit(parts[1], int(parts[2]) if len(parts) > 2 else 0)
            return [c] * 40
        if parts[0] == '_stored':
            return [(r.skill(parts[1]).get('static') or {}).get('stored_uses')] * 40
        raise ValueError(src)
    sid, st = src[0], src[1]
    si = src[2] if len(src) > 2 else None
    if st == '_cooldown':
        return r.cooldown(sid)
    if st == '_dmg':
        return r.damage(sid, si or 0)
    return r.stat(sid, st, si)


def audit(r, only=None):
    Y = yaml.safe_load(SKILLS.read_text(encoding='utf-8'))['skills']
    findings, checked, notes = [], [], []
    for k, spec in S.items():
        if only and k not in only:
            continue
        if k not in Y:
            findings.append(Finding(k, '（技能不在 skills.yml）', None, None, fixable=False))
            continue
        sk = Y[k]
        if sk.get('type') == 'attack':
            continue
        checked.append(k)
        ours = dict(leaves(sk))
        skip = spec.get('skip', set())
        main = spec.get('main')

        def check(field, ours_raw, want, tol=1e-6, note='', idx=None):
            if want is None or all(w is None for w in want):
                return
            if ours_raw is None:
                findings.append(Finding(k, field, '（沒寫）', want, note))
                return
            o40 = expand(ours_raw)
            if idx is not None:
                o40 = [x[idx] for x in o40]
            bad = compare(o40, want, tol)
            varies = len({json.dumps(w) for w in want if w is not None}) > 1
            if bad or (varies and kind(ours_raw) == '兩點內插' and idx is None):
                findings.append(Finding(k, field + ('[%d]' % idx if idx is not None else ''), ours_raw, want,
                                        note or ('兩點內插（PoE2 是逐級）' if not bad else '第 %s 級不一樣' % bad[:6])))

        if main:
            if 'mana' not in skip:
                mana = r.costs(main)
                if any(x is not None for x in mana):
                    check('mana', sk.get('mana'), mana)
            if 'cast' not in skip and r.cast(main) and sk.get('mechanic') not in ('aura',):
                check('cast_time', sk.get('cast_time', 0.75), [r.cast(main)] * 40)
            if 'cooldown' not in skip:
                cd = r.cooldown(main)
                if cd and len(set(cd)) == 1 and sk.get('mechanic') not in ('aura',):
                    check('cooldown', sk.get('cooldown'), cd)
            if 'spirit' not in skip:
                sp = r.spirit(main)
                if sp and (sk.get('aura') or {}).get('per_minion') is None:
                    check('aura.reserve', (sk.get('aura') or {}).get('reserve'), sp)
        if 'dmg' in spec:
            sid, si = spec['dmg']
            want = r.damage(sid, si)
            check('damage', sk.get('damage'), want)
            if 'crit' not in skip:
                c = r.crit(sid, si)
                if c is not None:
                    check('modifiers[spell.base_crit/FLAT].value', ours.get('modifiers[spell.base_crit/FLAT].value'), [c] * 40)
        if 'dot' in spec:
            sid, st, si = spec['dot']
            div = spec.get('dot_div', 1)
            per_sec = [x / 60 / div for x in r.stat(sid, st, si)]
            dmg = sk.get('damage')
            if isinstance(dmg, list) and dmg and isinstance(dmg[0], list) and spec.get('dot_div', 1) == 1:
                # 每秒 DoT：存成 [x, x] 整數（四捨五入）
                want = [[round(x), round(x)] for x in per_sec]
                check('damage（每秒）', dmg, want, tol=0)
            elif isinstance(dmg, list) and dmg and isinstance(dmg[0], list):
                # 焚流：每下 = 每秒 ÷ 4，±20%（整數）
                want = [[math.floor(x * 0.8 + 1e-9), math.floor(x * 1.2 + 0.5)] for x in per_sec]
                bad = compare(expand(dmg), want, 1)
                if bad:
                    findings.append(Finding(k, 'damage（每 0.25 秒一下）', dmg, want, '第 %s 級差超過 1' % bad[:6]))
        if 'command' in spec:
            csid, ck = spec['command']
            cooldown, mana = java_command_mana(ck)
            m = r.costs(csid)
            want_cd = r.cooldown(csid)
            mana40 = [mana[min(i, len(mana) - 1)] for i in range(40)]
            bad = compare(mana40, m, 0)
            if bad:
                findings.append(Finding(k, 'java:MinionAbilities 指令魔力（%s）' % csid, mana, m, '第 %s 級不一樣（表只有 %d 級，之後停在最後一級）' % (bad[:6], len(mana))))
            if want_cd and abs(want_cd[0] - cooldown) > 1e-6:
                findings.append(Finding(k, 'java:MinionAbilities 指令冷卻（%s）' % csid, cooldown, want_cd, ''))
        for c in spec.get('checks', []):
            field, src = c[0], c[1]
            fn = c[2] if len(c) > 2 else ID
            tol = c[3] if len(c) > 3 else 1e-6
            idx = c[4] if len(c) > 4 else None
            want = [None if x is None else (fn(x) if not isinstance(x, list) else x) for x in source(r, src)]
            if field.startswith('java:'):
                _, rel, name = field.split(':')
                arr = java_array(rel, name)
                arr40 = [arr[min(i, len(arr) - 1)] for i in range(40)]
                bad = compare(arr40, want, tol)
                if bad:
                    findings.append(Finding(k, field, arr, want, '第 %s 級不一樣（表 %d 級）' % (bad[:6], len(arr))))
                continue
            if field.startswith('javafn:'):
                f = JAVA_FN[field.split(':')[1]]
                vals = [f(L) for L in range(1, 41)]
                bad = compare(vals, want, tol)
                if bad:
                    findings.append(Finding(k, field, vals, want, '第 %s 級不一樣' % bad[:6]))
                continue
            check(field, ours.get(field) if field != 'damage' else sk.get('damage'), want, tol, idx=idx)
        for u in spec.get('unrep', []):
            notes.append((k, u))
        if spec.get('note'):
            notes.append((k, '（說明）' + spec['note']))
    return Y, checked, findings, notes


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    only = None
    if '--only' in sys.argv:
        only = set(sys.argv[sys.argv.index('--only') + 1].split(','))
        args = [a for a in args if a != sys.argv[sys.argv.index('--only') + 1]]
    r = RePoE(args[0] if args else DEFAULT_REPOE)
    Y, checked, findings, notes = audit(r, only)
    print('核對 %d 個技能' % len(checked))
    for f in findings:
        o = f.ours
        if isinstance(o, list) and len(o) >= 20:
            o = '%s（%s）' % (lv3(expand(o)), kind(o))
        w = lv3(f.want) if isinstance(f.want, list) and len(f.want) == 40 else f.want
        print('不一樣 %-24s %-48s 我們 %s → PoE2 %s  %s' % (f.skill, f.field, o, w, f.note))
    print()
    for k, n in notes:
        print('沒做   %-24s %s' % (k, n))
    print()
    for k, n in NO_EQUIVALENT.items():
        print('無對應 %-24s %s' % (k, n))
    print()
    print('全部一致' if not findings else '不一樣：%d 處' % len(findings))


if __name__ == '__main__':
    main()
