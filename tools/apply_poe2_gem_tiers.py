#!/usr/bin/env python3
"""2026-10-27：技能寶石的階級（tier：未切割技能寶石要幾級才雕得出來）照 PoE2 0.5。
來源：poe2db.tw/us/Uncut_Skill_Gem 的 gemcutting 表（技能 1／3／5／7／9／11／13 階）與 poe2db.tw/us/Spirit_Gems（精魂寶石 4／8／14 階），
跟 RePoE poe2 data/skill_gems.json 的 crafting_level 一致（給了路徑就順便核對）。
SPEC：我們的代號 → (PoE2 名稱, PoE2 階級)。改到的 tier 行尾寫「2026-10-27 照 PoE2（名稱 第 N 階）；這次之前 X」，原本的註解留在後面。
可以重跑（已經一樣的不動）。
用法：python3 tools/apply_poe2_gem_tiers.py [RePoE 的 skill_gems.json]"""
import json
import re
import sys
from pathlib import Path

F = Path(__file__).resolve().parent.parent / 'src/main/resources/data/skills.yml'

SPEC = {
    # ---- 戰士（錘）----
    'leap_slam': ('Leap Slam', 7), 'earthquake': ('Earthquake', 1), 'boneshatter': ('Boneshatter', 1),
    'ancestral_totem': ('Ancestral Warrior Totem', 13), 'shockwave_totem': ('Shockwave Totem', 3),
    'fortifying_cry': ('Fortifying Cry', 9), 'infernal_cry': ('Infernal Cry', 3), 'seismic_cry': ('Seismic Cry', 11),
    'rolling_slam': ('Rolling Slam', 1), 'volcanic_fissure': ('Volcanic Fissure', 7), 'armour_breaker': ('Armour Breaker', 3),
    'shield_rush': ('Shield Charge', 3), 'shatter_spikes': ('Earthshatter', 7), 'magma_spray': ('Molten Blast', 5),
    'true_strike': ('Perfect Strike', 5), 'stampede': ('Stampede', 11), 'forge_hammer': ('Forge Hammer', 9),
    'hammer_of_gods': ('Hammer of the Gods', 13), 'sunder': ('Sunder', 9), 'supercharged_slam': ('Supercharged Slam', 11),
    'resonating_shield': ('Resonating Shield', 5), 'shield_wall': ('Shield Wall', 7), 'ancestral_cry': ('Ancestral Cry', 13),
    # ---- 遊俠（弓）----
    'caustic_arrow': ('Gas Arrow', 7), 'rain_of_arrows': ('Rain of Arrows', 9), 'lightning_arrow': ('Lightning Arrow', 1),
    'escape_shot': ('Escape Shot', 1), 'ice_shot': ('Ice Shot', 9), 'tornado_shot': ('Tornado Shot', 11),
    'hunters_mark': ("Sniper's Mark", 7), 'thunder_arrow': ('Stormcaller Arrow', 3), 'frost_volley': ('Freezing Salvo', 3),
    'shock_arrow': ('Electrocuting Arrow', 5), 'bramble_arrow': ('Vine Arrow', 3), 'venom_burst_arrow': ('Poisonburst Arrow', 1),
    'spiral_volley': ('Spiral Volley', 13), 'blast_arrow': ('Detonating Arrow', 9), 'chain_arrow': ('Shockchain Arrow', 11),
    'snipe': ('Snipe', 3), 'toxic_growth': ('Toxic Growth', 5), 'magnetic_salvo': ('Magnetic Salvo', 13),
    'voltaic_mark': ('Voltaic Mark', 7), 'freezing_mark': ('Freezing Mark', 7), 'ice_tipped_arrows': ('Ice-Tipped Arrows', 5),
    'linked_volley': ('Barrage', 5), 'toxic_domain': ('Toxic Domain', 13), 'lightning_rod': ('Lightning Rod', 1),
    # ---- 傭兵（弩）----
    'explosive_bolt': ('Explosive Shot', 7), 'permafrost_bolts': ('Permafrost Bolts', 1), 'rapid_fire': ('Rapid Shot', 5),
    'explosive_grenade': ('Explosive Grenade', 1), 'gas_grenade': ('Gas Grenade', 5), 'armour_piercing_bolt': ('Armour Piercing Rounds', 1),
    'concussion_grenade': ('Flash Grenade', 3), 'galvanic_bolts': ('Galvanic Shards', 5), 'siege_salvo': ('Siege Cascade', 13),
    'hail_rounds': ('Hailstorm Rounds', 11), 'piercing_rounds': ('High Velocity Rounds', 3), 'incendiary_shot': ('Incendiary Shot', 3),
    'frost_bolt_round': ('Ice Shards', 5), 'storm_bolts': ('Shockburst Rounds', 11), 'oil_grenade': ('Oil Grenade', 9),
    'voltaic_grenade': ('Voltaic Grenade', 7), 'cluster_grenade': ('Cluster Grenade', 13), 'glacial_bolt': ('Glacial Bolt', 7),
    'plasma_blast': ('Plasma Blast', 13), 'emergency_reload': ('Emergency Reload', 11), 'fragmentation_rounds': ('Fragmentation Rounds', 1),
    'stormblast_bolts': ('Stormblast Bolts', 9), 'siege_ballista': ('Siege Ballista', 9), 'artillery_ballista': ('Artillery Ballista', 7),
    # ---- 武僧（長棍）----
    'tempest_flurry': ('Tempest Flurry', 5), 'ice_strike': ('Ice Strike', 5), 'storm_wave': ('Storm Wave', 7),
    'killing_palm': ('Killing Palm', 1), 'tempest_bell': ('Tempest Bell', 3), 'gale_sweep': ('Wind Blast', 3),
    'vault_strike': ('Vaulting Impact', 5), 'whirling_rush': ('Whirling Assault', 11), 'siphon_strike': ('Siphoning Strike', 7),
    'reeling_palm': ('Staggering Palm', 3), 'frost_palm': ('Shattering Palm', 11), 'frost_wave': ('Wave of Frost', 7),
    'storm_staff': ('Charged Staff', 9), 'ruin_mantra': ('Mantra of Destruction', 9), 'falling_thunder': ('Falling Thunder', 1),
    'glacial_cascade': ('Glacial Cascade', 1), 'flicker_strike': ('Flicker Strike', 13), 'gathering_storm': ('Gathering Storm', 13),
    'frozen_locus': ('Frozen Locus', 1),
    # ---- 女獵人（矛）----
    'lightning_spear': ('Lightning Spear', 3), 'whirling_slash': ('Whirling Slash', 1), 'whirlwind_lance': ('Whirlwind Lance', 11),
    'storm_javelin': ('Storm Lance', 5), 'spear_thicket': ('Spearfield', 5), 'rapid_thrust': ('Rapid Assault', 5),
    'gale_spear': ('Twister', 1), 'serpent_fury': ("Wind Serpent's Fury", 13), 'prism_rend': ('Elemental Sundering', 11),
    'thunder_leap': ('Thunderous Leap', 9), 'blood_harvest': ('Blood Hunt', 7), 'sunfire_spear': ('Spear of Solaris', 13),
    'explosive_spear': ('Explosive Spear', 1), 'fangs_of_frost': ('Fangs of Frost', 3), 'disengage': ('Disengage', 1),
    'glacial_lance': ('Glacial Lance', 7), 'primal_strikes': ('Primal Strikes', 9), 'cull_the_weak': ('Cull The Weak', 3),
    'bloodhounds_mark': ("Bloodhound's Mark", 9), 'rake': ('Rake', 3), 'beast_companion': ('Tame Beast', 7),
    # ---- 女巫師（元素）----
    'fireball': ('Fireball', 3), 'frost_nova': ('Ice Nova', 1), 'spark': ('Spark', 1), 'arc': ('Arc', 5), 'comet': ('Comet', 11),
    'flame_wall': ('Flame Wall', 1), 'firestorm': ('Firestorm', 11), 'ember_volley': ('Ember Fusillade', 5),
    'storm_orb': ('Orb of Storms', 3), 'lightning_pillar': ('Lightning Conduit', 13), 'frost_mine': ('Frost Bomb', 1),
    'ice_wall': ('Frost Wall', 9), 'storm_blink': ('Lightning Warp', 9), 'inferno_blast': ('Flameblast', 13),
    'ember_seed': ('Living Bomb', 3), 'winter_eye': ('Eye of Winter', 13), 'mana_storm': ('Mana Tempest', 7),
    'flame_stream': ('Incinerate', 9), 'frostbolt': ('Frostbolt', 5), 'ball_lightning': ('Ball Lightning', 11),
    'frost_darts': ('Frost Darts', 3), 'snap': ('Snap', 5), 'elemental_weakness_curse': ('Elemental Weakness', 7),
    'temporal_curse': ('Temporal Chains', 7),
    # ---- 女巫（秘術）----
    'detonate_dead': ('Detonate Dead', 7), 'raise_zombie': ('Raise Zombie', 5), 'vulnerability_curse': ('Vulnerability', 7),
    'despair_curse': ('Despair', 9), 'enfeeble_curse': ('Enfeeble', 3), 'blood_offering': ('Pain Offering', 5),
    'bone_offering': ('Bone Offering', 11), 'soul_offering': ('Soul Offering', 13), 'hex_burst': ('Hexblast', 11),
    'bone_storm': ('Bonestorm', 5), 'skeletal_brute': ('Skeletal Brute', 13), 'skeletal_archers': ('Skeletal Sniper', 1),
    'skeletal_pyromancer': ('Skeletal Arsonist', 3), 'skeletal_stormcaller': ('Skeletal Storm Mage', 11),
    'skeletal_reaver': ('Skeletal Reaver', 9), 'skeletal_frostcaller': ('Skeletal Frost Mage', 5), 'skeletal_cleric': ('Skeletal Cleric', 13),
    'bone_spikes': ('Unearth', 1), 'dark_idol': ('Dark Effigy', 9), 'bound_spectre': ('Bind Spectre', 7),
    'essence_drain': ('Essence Drain', 3), 'contagion': ('Contagion', 1), 'profane_ritual': ('Profane Ritual', 7),
    'bone_cage': ('Bone Cage', 3),
    # ---- 德魯伊（原始）----
    'bear_slam': ('Furious Slam', 1), 'mountain_fury': ('Fury of the Mountain', 5), 'rampage': ('Rampage', 11),
    'walking_calamity': ('Walking Calamity', 13), 'lunar_assault': ('Lunar Assault', 1), 'pounce': ('Pounce', 3),
    'cross_slash': ('Cross Slash', 7), 'arctic_howl': ('Arctic Howl', 5), 'ferocious_roar': ('Ferocious Roar', 7),
    'lunar_blessing': ('Lunar Blessing', 13), 'wing_blast': ('Wing Blast', 3), 'rolling_magma': ('Rolling Magma', 3),
    'devour': ('Devour', 5), 'oil_barrage': ('Oil Barrage', 9), 'flame_breath': ('Flame Breath', 13),
    'thunderstorm': ('Thunderstorm', 5), 'primal_tornado': ('Tornado', 11), 'thrashing_vines': ('Thrashing Vines', 9),
    'volcano': ('Volcano', 1), 'entangle': ('Entangle', 1), 'totem_casting': ('Spell Totem', 7),
    # ---- 精魂寶石（Spirit_Gems：4／8／14 階）----
    'raging_spirits': ('Raging Spirits', 4), 'herald_of_ash': ('Herald of Ash', 4), 'herald_of_thunder': ('Herald of Thunder', 4),
    'herald_of_ice': ('Herald of Ice', 4), 'herald_of_blood': ('Herald of Blood', 4), 'herald_of_plague': ('Herald of Plague', 8),
    'defiance_banner': ('Defiance Banner', 8), 'war_banner': ('War Banner', 4), 'dread_banner': ('Dread Banner', 14),
    'frost_aegis': ('Arctic Armour', 4), 'attrition': ('Attrition', 4), 'barkskin': ('Barkskin', 8), 'briarpatch': ('Briarpatch', 4),
    'savage_fury': ('Savage Fury', 4), 'wolf_pack': ('Wolf Pack', 8), 'berserk': ('Berserk', 14),
    'overwhelming_presence': ('Overwhelming Presence', 8), 'withering_presence': ('Withering Presence', 4), 'blink': ('Blink', 8),
    'time_of_need': ('Time of Need', 8), 'ghost_dance': ('Ghost Dance', 4), 'scavenged_plating': ('Scavenged Plating', 4),
    'eternal_rage': ('Eternal Rage', 14), 'mana_remnants': ('Mana Remnants', 4), 'alchemists_boon': ("Alchemist's Boon", 14),
    'ravenous_swarm': ('Ravenous Swarm', 4), 'lingering_illusion': ('Lingering Illusion', 8), 'trail_of_caltrops': ('Trail of Caltrops', 8),
    'summon_mount': ('Rhoa Mount', 14), 'mirage_archer': ('Mirage Archer', 8), 'plague_bearer': ('Plague Bearer', 4),
    'iron_ward': ('Iron Ward', 4), 'wind_dancer': ('Wind Dancer', 4), 'magma_barrier': ('Magma Barrier', 4),
    'combat_frenzy': ('Combat Frenzy', 8), 'archmage': ('Archmage', 14), 'elemental_conflux': ('Elemental Conflux', 14),
    'charge_regulation': ('Charge Regulation', 14), 'convalescence': ('Convalescence', 4), 'grim_feast': ('Grim Feast', 4),
    'sacrifice': ('Sacrifice', 14), 'shard_scavenger': ('Shard Scavenger', 8), 'siphon_elements': ('Siphon Elements', 8),
    'elemental_trinity': ('Trinity', 14), 'meta_crit': ('Cast on Critical', 14), 'meta_ailment': ('Cast on Elemental Ailment', 14),
    'meta_dodge': ('Cast on Dodge', 14), 'meta_minion_death': ('Cast on Minion Death', 8),
    'meta_elemental_invocation': ('Elemental Invocation', 8), 'meta_reaper_invocation': ("Reaper's Invocation", 14),
    'meta_barrier_invocation': ('Barrier Invocation', 8),
}

# 沒有 PoE2 對應或原作不是寶石（不動）：架盾、招架（盾牌賦予）、撼山爪、疾爪、翼刃斬（變身賦予）、烈陽珠（0.5 寶石表上沒有）、
# 召喚骸骨兵與法器賦予的（source: weapon）、專屬掉落（source）、昇華賦予的。


def verify(path):
    gems = json.load(open(path, encoding='utf-8'))
    tiers = {}
    for v in gems.values():
        name = (v.get('base_item') or {}).get('display_name')
        if name and v.get('gem_type') != 'support' and v.get('crafting_level'):
            tiers[name] = max(tiers.get(name, 0), v['crafting_level'])
    bad = [(k, n, t, tiers.get(n)) for k, (n, t) in SPEC.items() if tiers.get(n) != t]
    for b in bad:
        print('RePoE 不一致：', b, file=sys.stderr)
    return not bad


def main():
    if len(sys.argv) > 1 and not verify(sys.argv[1]):
        sys.exit(1)
    lines = F.read_text(encoding='utf-8').split('\n')
    cur = None
    changed = []
    seen = set()
    for i, line in enumerate(lines):
        m = re.match(r'^  ([a-z_0-9]+):\s*(#.*)?$', line)
        if m:
            cur = m.group(1)
            continue
        if re.match(r'^\S', line):
            cur = None
            continue
        m = re.match(r'^    tier: (\d+)(.*)$', line)
        if not m or cur not in SPEC:
            continue
        seen.add(cur)
        old = int(m.group(1))
        name, tier = SPEC[cur]
        if old == tier:
            continue
        rest = m.group(2).strip()
        rest = re.sub(r'^#\s*', '', rest)
        note = '   # 2026-10-27 照 PoE2（%s 第 %d 階）；這次之前 %d' % (name, tier, old)
        if rest:
            note += '（%s）' % rest
        lines[i] = '    tier: %d%s' % (tier, note)
        changed.append((cur, name, old, tier))
    F.write_text('\n'.join(lines), encoding='utf-8')
    for c in changed:
        print('%s（%s）：%d → %d' % c)
    missing = sorted(set(SPEC) - seen)
    if missing:
        print('找不到 tier 行：', missing, file=sys.stderr)
    print('改了 %d 個' % len(changed))


if __name__ == '__main__':
    main()
