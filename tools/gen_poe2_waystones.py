"""2026-10-26：換界石詞綴照 PoE2 0.5 的完整詞綴池重新產生（tools/poe2_waystones.json，RePoE poe2 mods.json 匯出）。
- 每一個換界石階段帶只出那一段的數值（Affix.banded：T1–5 → ilvl 1、T6–10 → 66、T11–15 → 75、T16 → 84）。
- 每個詞綴家族權重一樣、各自一組（不再共用 map_monster_extra／map_monster_ailment）。
- 獎勵照原作：換界石掉落更多（map.waystones）、物品稀有度、怪物群大小、魔法與稀有怪物群（+稀有怪的詞綴機率更多）、怪物效能。
  （之前把「換界石掉落更多」當成物品數量 map.quantity，原作的換界石詞綴沒有物品數量。）
跑法：python3 tools/gen_poe2_waystones.py（覆寫 maps.yml 的「前綴／後綴」區段，停用的舊詞綴不動）。"""
import json, re
P = 'src/main/resources/data/affixes/maps.yml'
D = json.load(open('tools/poe2_waystones.json', encoding='utf-8'))
mods = {m['id']: m for m in D['mods']}
BAND = {'map_key_highest': (0, 84), 'map_key_high': (1, 75), 'map_key_medium': (2, 66), 'map_key_low': (3, 1), 'default': (0, 1)}
WEIGHT = 1000

# 家族 → (我們的代號, 前後綴, 名稱（高 → 低階）, 說明, 主效果, 主效果的變數（照 PoE2 effect_stats 的順序）, 正負)
FAM = {
 'MapMonsterFireDamage': ('map_monster_fire', 'prefix', ['焚天的', '焚燒的', '灼熱的', '溫熱的'], '怪物獲得 {value}% 傷害為額外火焰傷害', ['- { stat: "monster:damage.gain.fire", type: FLAT, value: "{value}" }'], ['value']),
 'MapMonsterColdDamage': ('map_monster_cold', 'prefix', ['冰獄的', '冰封的', '霜凍的', '微寒的'], '怪物獲得 {value}% 傷害為額外冰冷傷害', ['- { stat: "monster:damage.gain.cold", type: FLAT, value: "{value}" }'], ['value']),
 'MapMonsterLightningDamage': ('map_monster_lightning', 'prefix', ['雷獄的', '雷鳴的', '帶電的', '微電的'], '怪物獲得 {value}% 傷害為額外閃電傷害', ['- { stat: "monster:damage.gain.lightning", type: FLAT, value: "{value}" }'], ['value']),
 'MapMonsterChaosDamage': ('map_monster_chaos', 'prefix', ['深淵的', '腐蝕的', '蝕化的', '微蝕的'], '怪物獲得 {value}% 傷害為額外混沌傷害', ['- { stat: "monster:damage.gain.chaos", type: FLAT, value: "{value}" }'], ['value']),
 'MapMonsterDamage': ('map_monster_damage', 'prefix', ['嗜血的', '兇殘的', '粗暴的', '好鬥的'], '怪物傷害增加 {value}%', ['- { stat: "monster:damage", type: INCREASED, value: "{value}" }'], ['value']),
 'MapMonsterFast': ('map_monster_speed', 'prefix', ['迅猛的', '狂奔的'], '怪物攻擊、施法與移動速度增加 {value}%', ['- { stat: "monster:attack.speed", type: INCREASED, value: "{value}" }', '- { stat: "monster:cast.speed", type: INCREASED, value: "{value}" }', '- { stat: "monster:movement.speed", type: INCREASED, value: "{value}" }'], ['value']),
 'MapMonsterCriticalStrikesAndDamage': ('map_monster_crit', 'prefix', ['必殺的', '精準的', '致命的', '銳利的'], '怪物暴擊率增加 {value}%，暴擊傷害加成 +{multi}%', ['- { stat: "monster:crit.chance", type: INCREASED, value: "{value}" }', '- { stat: "monster:crit.multi", type: FLAT, value: "{multi}" }'], ['value', 'multi']),
 'MapMonsterLife': ('map_monster_life', 'prefix', ['不滅的', '強韌的', '堅實的', '結實的'], '怪物生命更多 {value}%', ['- { stat: "monster:life.max", type: MORE, value: "{value}" }'], ['value']),
 'MapPoisoning': ('map_monster_poison', 'prefix', ['劇毒的', '毒性的', '腐臭的', '帶毒的'], '怪物擊中時 {value}% 機率造成中毒', ['- { stat: "monster:poison.chance", type: FLAT, value: "{value}" }'], ['value']),
 'MapBleeding': ('map_monster_bleed', 'prefix', ['血腥的', '撕裂的', '割裂的', '見血的'], '怪物擊中時 {value}% 機率造成流血', ['- { stat: "monster:bleed.chance", type: FLAT, value: "{value}" }'], ['value']),
 'MapMonstersArmourBreak': ('map_monster_armour_break', 'prefix', ['碎甲的', '裂甲的', '破甲的', '鑿甲的'], '怪物造成的物理傷害有 {value}% 作為破甲', ['- { stat: "monster:armour.break", type: FLAT, value: "{value}" }'], ['value']),
 'MapMonstersAccuracy': ('map_monster_accuracy', 'prefix', ['神射的', '精準的', '銳眼的', '明目的'], '怪物命中值增加 {value}%', ['- { stat: "monster:accuracy", type: INCREASED, value: "{value}" }'], ['value']),
 'MapMonstersStunBuildup': ('map_monster_stun', 'prefix', ['震擊的', '重擊的', '猛擊的', '撞擊的'], '怪物擊中造成的暈眩累積增加 {value}%', ['- { stat: "monster:stun.buildup", type: INCREASED, value: "{value}" }'], ['value']),
 'MapMonstersElementalPenetration': ('map_monster_penetration', 'prefix', ['穿透的', '貫穿的', '透甲的', '滲透的'], '怪物的元素傷害穿透 {value}% 元素抗性', ['- { stat: "monster:penetration", type: FLAT, value: "{value}", tags: [fire] }', '- { stat: "monster:penetration", type: FLAT, value: "{value}", tags: [cold] }', '- { stat: "monster:penetration", type: FLAT, value: "{value}", tags: [lightning] }'], ['value']),
 'MapMonstersAllResistances': ('map_monster_resist', 'suffix', ['之不屈', '之抗性', '之堅守', '之防護'], '怪物 +{value}% 全部元素抗性', ['- { stat: "monster:resist.fire", type: FLAT, value: "{value}" }', '- { stat: "monster:resist.cold", type: FLAT, value: "{value}" }', '- { stat: "monster:resist.lightning", type: FLAT, value: "{value}" }'], ['value']),
 'MapMonstersStunAndAilmentThreshold': ('map_monster_ailment_threshold', 'suffix', ['之冷靜', '之沉著', '之穩重', '之鎮定'], '怪物的暈眩門檻增加 {stun}%，元素異常門檻增加 {value}%', ['- { stat: "monster:stun.threshold", type: INCREASED, value: "{stun}" }', '- { stat: "monster:ailment.threshold", type: INCREASED, value: "{value}" }'], ['stun', 'value']),
 'MapMonstersAilmentChance': ('map_monster_elemental_ailments', 'suffix', ['之元素暴走', '之元素紊亂', '之元素躁動', '之元素不穩'], '怪物的元素異常效果增加 {value}%', ['- { stat: "monster:ignite.chance", type: INCREASED, value: "{value}" }', '- { stat: "monster:shock.chance", type: INCREASED, value: "{value}" }', '- { stat: "monster:freeze.buildup", type: INCREASED, value: "{value}" }'], ['value']),
 'MapMonstersBaseSelfCriticalMultiplier': ('map_monster_crit_taken', 'suffix', ['之鐵骨', '之硬頸', '之厚皮', '之韌肉'], '怪物受到的暴擊額外傷害減少 {value}%', ['- { stat: "monster:crit.bonus_taken", type: INCREASED, value: "-{value}" }'], ['value']),
 'MapPlayerMaxResists': ('map_player_max_resist', 'suffix', ['之末日', '之詛咒', '之衰弱', '之削減'], '玩家 {value}% 抗性上限', ['- { stat: "player:resist.max", type: FLAT, value: "{value}" }'], ['value']),
 'MapPlayerReducedRegen': ('map_player_regen', 'suffix', ['之焦土', '之枯萎', '之乾涸', '之乾燥'], '玩家的生命與能量護盾回復速度更少 {value}%', ['- { stat: "player:life.regen", type: MORE, value: "-{value}" }', '- { stat: "player:es.recharge", type: MORE, value: "-{value}" }', '- { stat: "player:flask.life_recovery", type: MORE, value: "-{value}" }'], ['value']),
 'MapPlayerCooldownRecovery': ('map_player_cooldown', 'suffix', ['之遲鈍', '之停滯', '之凝固', '之緩慢'], '玩家的冷卻回復速度更少 {value}%', ['- { stat: "player:cooldown.recovery", type: MORE, value: "-{value}" }'], ['value']),
 # ---- 2026-10-26 新增（原作有、我們之前沒有）----
 'MapMonstersArmoured': ('map_monster_armoured', 'suffix', ['之重甲'], '怪物身披重甲：護甲增加 {value}%', ['- { stat: "monster:armour", type: INCREASED, value: "{value}" }'], ['value']),
 'MapMonstersEvasive': ('map_monster_evasive', 'suffix', ['之迅影'], '怪物身手矯健：閃避增加 {value}%', ['- { stat: "monster:evasion", type: INCREASED, value: "{value}" }'], ['value']),
 'MapMonstersEnergyShield': ('map_monster_energy_shield', 'suffix', ['之星盾'], '怪物獲得最大生命 {value}% 的額外能量護盾', ['- { stat: "monster:monster.shield_percent", type: FLAT, value: "{value}" }'], ['value']),
 'MapMonstersCurseEffectOnSelfFinal': ('map_monster_curse_effect', 'suffix', ['之辟邪', '之驅咒', '之護符', '之淨身'], '怪物受到的詛咒效果更少 {value}%', ['- { stat: "monster:curse.effect_on_self", type: MORE, value: "-{value}" }'], ['value']),
 'MapPlayersGainReducedFlaskCharges': ('map_player_flask_charges', 'suffix', ['之乾渴', '之焦渴', '之口渴', '之微渴'], '玩家獲得的藥劑充能減少 {value}%', ['- { stat: "player:flask.charges_gained", type: INCREASED, value: "-{value}" }'], ['value']),
 'MapPlayerEnfeeblement': ('map_player_curse_enfeeble', 'suffix', ['之萎力'], '玩家會週期性地被萎力詛咒纏上（造成的傷害更少）', ['- { stat: map.curse_zone.enfeeble, type: FLAT, value: 1 }'], []),
 'MapPlayerTemporalChains': ('map_player_curse_temporal', 'suffix', ['之遲滯'], '玩家會週期性地被遲滯詛咒纏上（變慢、冷卻變長）', ['- { stat: map.curse_zone.temporal_chains, type: FLAT, value: 1 }'], []),
 'MapPlayerElementalWeakness': ('map_player_curse_elemental', 'suffix', ['之三元'], '玩家會週期性地被三元詛咒纏上（元素抗性降低）', ['- { stat: map.curse_zone.elemental_weakness, type: FLAT, value: 1 }'], []),
}
GROUND = {  # 原作同一組（MapSpreadGroundEffect）三種地面
 'MapSpreadBurningGround': ('map_ground_ignited', ['之煉獄', '之焦原', '之火場', '之餘燼'], '地圖有一塊塊點燃的地面', 'fire'),
 'MapSpreadChilledGround': ('map_ground_chilled', ['之冰原', '之寒地', '之霜徑', '之冷霧'], '地圖有一塊塊冰緩的地面', 'chill'),
 'MapSpreadShockedGround': ('map_ground_shocked', ['之雷原', '之電野', '之雷痕', '之靜電'], '地圖有一塊塊感電的地面', 'shock'),
}

def reward_lines(r):
    out = []
    effect = None
    for k, v in r.items():
        if k == 'more_waystones_found_%':
            out.append(f'- {{ stat: map.waystones, type: FLAT, value: {v} }}   # 換界石掉落更多 {v}%')
        elif k == 'more_item_rarity_%':
            out.append(f'- {{ stat: map.rarity, type: FLAT, value: {v} }}')
        elif k == 'more_pack_size_%':
            out.append(f'- {{ stat: map.pack_size, type: FLAT, value: {v} }}')
        elif k.startswith('more_magic_and_rare_monsters'):
            out.append(f'- {{ stat: map.magic_packs, type: FLAT, value: {v} }}')
            out.append(f'- {{ stat: map.rare_packs, type: FLAT, value: {v} }}')
            out.append(f'- {{ stat: map.rare_mod_chance, type: FLAT, value: {v} }}   # 稀有怪有更多機率帶詞綴')
        elif k == 'more_monster_effectiveness_%':
            out.append(f'- {{ stat: "monster:effectiveness", type: MORE, value: {v} }}   # 怪物效能更多 {v}%')
            effect = v
        else:
            raise SystemExit('不認得的獎勵 ' + k)
    return out, effect

def family_mods(group, ids=None):
    fam = next(f for f in D['families'] if f['group'] == group and (ids is None or set(f['mods']) & set(ids)))
    return [mods[i] for i in fam['mods'] if i in mods and mods[i].get('rollable') == 'rollable']

def rng(s, neg):
    lo, hi = s['min'], s['max']
    if neg:
        lo, hi = sorted((-lo, -hi))
    return f'[{lo}, {hi}]'

def block(aid, kind, group, names, text, main, var_names, ms, neg=False):
    ms = sorted(ms, key=lambda m: BAND[m['spawn_weights'][0]['tag']][0])
    rewards, effect = reward_lines(ms[0]['reward_lines'])
    t = text + (f'；怪物效能更多 {effect}%' if effect else '')
    lines = [f'{aid}:', f'  type: {kind}', f'  group: {group}', '  spawn_tags: [map]', f'  names: [{", ".join(names[:len(ms)])}]', f'  text: "{t}"', '  tiers:']
    for m in ms:
        tier, ilvl = BAND[m['spawn_weights'][0]['tag']]
        vars_ = ''.join(f', {n}: {rng(s, neg)}' for n, s in zip(var_names, m['effect_stats']))
        if not var_names:
            vars_ = ', value: [1, 1]'   # 沒有數值的詞綴（週期詛咒）：引擎要求至少一個變數
        lines.append(f'    - {{ tier: {tier}, ilvl: {ilvl}, weight: {WEIGHT}{vars_} }}')
    lines.append('  mods:')
    lines += ['    ' + l for l in main]
    lines += ['    ' + l for l in rewards]
    return '\n'.join(lines)

NEG = {'MapMonstersCurseEffectOnSelfFinal', 'MapPlayersGainReducedFlaskCharges', 'MapPlayerCooldownRecovery', 'MapPlayerReducedRegen'}
out_pre, out_suf = [], []
for group, (aid, kind, names, text, main, var_names) in FAM.items():
    ms = family_mods(group)
    b = block(aid, kind, aid, names, text, main, var_names, ms, neg=group in NEG)
    (out_pre if kind == 'prefix' else out_suf).append(b)
# 地面效果：三種同一組
for prefix, (aid, names, text, kind) in GROUND.items():
    ms = [m for m in D['mods'] if m['id'].startswith(prefix) and m.get('rollable') == 'rollable']
    ms = sorted(ms, key=lambda m: BAND[m['spawn_weights'][0]['tag']][0])
    rewards, effect = reward_lines(ms[0]['reward_lines'])
    t = text + (f'；怪物效能更多 {effect}%' if effect else '')
    lines = [f'{aid}:', '  type: suffix', '  group: map_ground', '  spawn_tags: [map]', f'  names: [{", ".join(names)}]', f'  text: "{t}"', '  tiers:']
    for m in ms:
        tier, ilvl = BAND[m['spawn_weights'][0]['tag']]
        st = {s['id']: s['max'] for s in m['all_stats']}
        patches = st['map_ground_effect_patches_per_100_tiles']
        extra = f', fire: [{st["map_base_ground_fire_damage_to_deal_per_10_seconds"]}, {st["map_base_ground_fire_damage_to_deal_per_10_seconds"]}]' if kind == 'fire' else ''
        lines.append(f'    - {{ tier: {tier}, ilvl: {ilvl}, weight: {WEIGHT}, value: [{patches}, {patches}]{extra} }}')
    radius = {s['id']: s['max'] for s in ms[0]['all_stats']}['map_ground_effect_radius']
    lines.append('  mods:')
    if kind == 'fire':
        lines.append('    - { stat: map.ground.fire, type: FLAT, value: "{fire}" }   # 每 10 秒的火焰傷害（68 級怪物的強度，照等級換算）')
    elif kind == 'chill':
        ch = {s['id']: s['max'] for s in ms[0]['all_stats']}['map_ground_ice_base_magnitude']
        lines.append(f'    - {{ stat: map.ground.chill, type: FLAT, value: {ch} }}   # 冰緩 {ch}%')
    else:
        lines.append('    - { stat: map.ground.shock, type: FLAT, value: 1 }')
    lines.append('    - { stat: map.ground.patches, type: FLAT, value: "{value}" }   # 每 100 個地圖區塊幾塊')
    lines.append(f'    - {{ stat: map.ground.radius, type: FLAT, value: {radius} }}   # 原作的單位（÷10 = 格）')
    lines += ['    ' + l for l in rewards]
    out_suf.append('\n'.join(lines))

s = open(P, encoding='utf-8').read()
start = s.index('# ---- 前綴：讓怪物變強（PoE2')
end = s.index('# ======== 停用的舊詞綴')
new = ('# ---- 前綴：讓怪物變強（PoE2 0.5；tools/gen_poe2_waystones.py 產生）----\n\n' + '\n\n'.join(out_pre)
       + '\n\n# ---- 後綴（PoE2 0.5）----\n\n' + '\n\n'.join(out_suf) + '\n\n')
s = s[:start] + new + s[end:]
open(P, 'w', encoding='utf-8').write(s)
print(len(out_pre), len(out_suf))
