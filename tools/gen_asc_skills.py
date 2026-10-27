#!/usr/bin/env python3
"""2026-10-26：產生昇華天賦賦予的技能（PoE2 的 Grants Skill）與它們用的隱藏技能，放進 skills.yml 的 BEGIN/END ascendancy 那一段。
逐級表（1–40 級）照原作資料（RePoE repoe-fork/poe2 data/skills.json，就是 poe2db 的數值）。
效果寫在 tw.exilecore.ascendancy.AscendancyEffects／AscGranted（這裡只有數值、範圍、外觀）。
用法：python3 tools/gen_asc_skills.py <RePoE 的 skills.json> > /tmp/asc.yml，再換掉 skills.yml 裡 BEGIN/END ascendancy 之間的那段。"""
import json, sys

S = json.load(open(sys.argv[1], encoding='utf-8'))
LV = [str(i) for i in range(1, 41)]


def statset(sid, setid=None):
    v = S[sid]
    for ss in v['stat_sets']:
        if setid is None or ss['id'] == setid:
            return ss
    raise KeyError(setid)


def col(sid, stat, setid=None, default=None):
    ss = statset(sid, setid)
    ids = [x['id'] for x in ss['static'].get('stats', [])]
    const = {x['id']: x.get('value') for x in ss['static'].get('stats', []) if x.get('value') is not None}
    out = []
    last = default
    for L in LV:
        row = ss['per_level'].get(L)
        val = None
        if row:
            if stat == 'damage_multiplier':
                val = row.get('damage_multiplier')
            else:
                arr = row.get('stats') or []
                if stat in ids:
                    i = ids.index(stat)
                    if i < len(arr) and arr[i] is not None and 'value' in arr[i]:
                        val = arr[i]['value']
        if val is None:
            val = const.get(stat, last)
        last = val
        out.append(val)
    return out


def mana(sid):
    out = []
    last = 0
    for L in LV:
        row = S[sid]['per_level'].get(L) or {}
        m = (row.get('costs') or {}).get('Mana')
        if m is None:
            m = (S[sid].get('static', {}).get('costs') or {}).get('Mana', last)
        last = m
        out.append(m)
    return out


def cooldown(sid):
    out = []
    for L in LV:
        row = S[sid]['per_level'].get(L) or {}
        c = row.get('cooldown', S[sid].get('static', {}).get('cooldown'))
        out.append(c)
    return out


def dmg(sid, element, setid=None):
    lo = col(sid, 'spell_minimum_base_%s_damage' % element, setid)
    hi = col(sid, 'spell_maximum_base_%s_damage' % element, setid)
    return [[a, b] for a, b in zip(lo, hi)]


def eff(sid, setid=None, scale=100.0):
    return [round((x if x is not None else 100) / scale, 2) for x in col(sid, 'damage_multiplier', setid)]


def fl(v):
    return '[' + ', '.join(fmt(x) for x in v) + ']'


def fmt(x):
    if isinstance(x, float):
        return ('%.3f' % x).rstrip('0').rstrip('.')
    return str(x)


def dt(v):
    return '[' + ', '.join('[%s, %s]' % (fmt(a), fmt(b)) for a, b in v) + ']'


def secs(ms):
    return [round(x / 1000.0, 3) for x in ms]


SRC = '2026-10-26 照 PoE2（poe2db %s 1–40 級）'
out = ['  # ==== BEGIN ascendancy：2026-10-26 昇華天賦賦予的技能與它們用的隱藏技能（tools/gen_asc_skills.py 產生，別手改；效果在 AscendancyEffects／AscGranted）====']


# 2026-10-27 圖示改得更直觀：有右鍵動作的原版物品（劍、箭）換掉，同一位昇華裡重複的分開；隱藏技能也補上圖示（這次之前沒寫，顯示成書）
ICON_NOTE = '   # 2026-10-27 圖示：%s'
ICON_FIX = {
    'manifest_weapon': ('NETHERITE_INGOT', '這次之前 IRON_SWORD（劍會干擾施放）'),
    'supporting_fire': ('FEATHER', '這次之前 ARROW（箭矢會被原版弓當成彈藥）'),
    'called_shots': ('GLOW_INK_SAC', '發光的標記；這次之前 SPECTRAL_ARROW（箭矢會被原版弓當成彈藥）'),
    'time_freeze': ('QUARTZ', '凍住的時間；這次之前 CLOCK（跟時間短縮一樣）'),
    'moment_of_vulnerability': ('FLINT', '破綻；這次之前 CLOCK（跟時間短縮一樣）'),
    'kelari_deception': ('GUNPOWDER', '會炸開的沙分身；這次之前 GOLD_NUGGET（跟克拉里本身一樣）'),
    'kelari_judgment': ('GOLD_INGOT', '這次之前 GOLD_NUGGET（跟克拉里本身一樣）'),
    'navira_oasis': ('NAUTILUS_SHELL', '這次之前 HEART_OF_THE_SEA（跟納維拉本身一樣）'),
    'navira_well': ('LAPIS_LAZULI', '回魔力的井；這次之前 HEART_OF_THE_SEA（跟納維拉本身一樣）'),
    'ruzhan_fury': ('BLAZE_POWDER', '這次之前 BLAZE_ROD（跟魯兹安本身一樣）'),
    'ruzhan_trap': ('COAL', '悶燒的陷阱；這次之前 BLAZE_ROD（跟魯兹安本身一樣）'),
    'into_the_breach': ('POPPED_CHORUS_FRUIT', '遁入裂痕；這次之前 AMETHYST_SHARD（跟高尚禦盾一樣）'),
    'archon_of_chayula': ('NETHERITE_SCRAP', '這次之前 ECHO_SHARD（跟虛空幻影一樣）'),
    'apocalypse': ('FIREWORK_STAR', '天降隕石、冰雹、雷擊；這次之前 NETHER_STAR（跟無縛化身一樣）'),
    'demon_form': ('FERMENTED_SPIDER_EYE', '這次之前 MAGMA_CREAM（跟魯兹安的清算一樣）'),
}
HIDDEN_ICONS = {
    'ancestral_spirits': 'PHANTOM_MEMBRANE', 'asc_combust': 'GUNPOWDER',
    'asc_expression_fire': 'BLAZE_POWDER', 'asc_expression_cold': 'PRISMARINE_SHARD', 'asc_expression_lightning': 'COPPER_INGOT',
    'asc_void_explosion': 'ECHO_SHARD', 'asc_chayula_will': 'FERMENTED_SPIDER_EYE',
    'elemental_storm_fire': 'MAGMA_CREAM', 'elemental_storm_cold': 'PRISMARINE_CRYSTALS', 'elemental_storm_lightning': 'GLOWSTONE_DUST',
    'asc_kelari_clone': 'SUGAR', 'asc_corpse_beetle': 'BONE', 'asc_ruzhan_wave': 'MAGMA_CREAM', 'asc_ruzhan_zealotry': 'BLAZE_ROD',
    'asc_apocalypse_fire': 'MAGMA_CREAM', 'asc_apocalypse_cold': 'QUARTZ', 'asc_apocalypse_lightning': 'COPPER_INGOT',
    'asc_aftershock': 'FLINT', 'asc_surge_fire': 'MAGMA_CREAM', 'asc_surge_cold': 'PRISMARINE_SHARD', 'asc_surge_lightning': 'GLOWSTONE_DUST', 'asc_shocked_ground': 'GLOWSTONE_DUST', 'asc_chilled_ground': 'PRISMARINE_CRYSTALS',
}


def block(sid, lines):
    out.append('')
    out.append('  %s:' % sid)
    lines = list(lines)
    if sid in ICON_FIX:
        lines = [('icon: %s' % ICON_FIX[sid][0] + ICON_NOTE % ICON_FIX[sid][1]) if line.startswith('icon: ') else line for line in lines]
    if sid in HIDDEN_ICONS and not any(line.startswith('icon: ') for line in lines):
        lines.insert(1, 'icon: %s' % HIDDEN_ICONS[sid] + ICON_NOTE % '照元素、作用補上；這次之前沒寫，顯示成書')
    for line in lines:
        out.append('    ' + line)


ASC = 'source: ascendancy   # 2026-10-26：昇華天賦賦予（PoE2 的 Grants Skill），沒有寶石'
MARK = '- { stat: asc.skill_mark, type: FLAT, value: 1 }'

# ---------- 狂嘯者：包覆於翠玉中 ----------
block('encase_in_jade', [
    'name: 包覆於翠玉中',
    'description: 用掉身上全部的玉石，每層獲得等同最大生命 6% 的守護（4～4.95 秒）。有守護時不能獲得玉石。',
    'icon: EMERALD', 'type: spell', 'tier: 1', 'tags: [physical, duration]', ASC,
    'mechanic: warcry',
    'warcry:',
    '  cry: false',
    '  radius: 2',
    '  duration: 4',
    '  taunt: false',
    '  allies: false',
    '  buff:',
    '    ' + MARK,
    'cast_time: 0.3', 'cooldown: 0.5   # 原作沒有冷卻（引擎的戰吼要有冷卻；0.5 秒＝施法時間）',
    'mana: 15   # ' + SRC % 'Encase in Jade', 'visual: enduring_cry',
    'level_modifiers:',
    '  - { stat: asc.jade_guard_seconds, type: FLAT, value: %s }' % fl(secs(col('EncaseInJadePlayer', 'base_skill_effect_duration'))),
])

# ---------- 狂嘯者：先祖之魂（召喚圖騰時觸發） ----------
block('ancestral_spirits', [
    'name: 先祖之魂', 'hidden: true', 'type: spell', 'tags: [minion, duration]',
    'mechanic: summon',
    'summon: { kind: WITHER_SKELETON, count: 1, max: 10, fixed_max: true, duration: 8, life: 1.0, damage: 1.0, attack_speed: 1.0, speed: 1.2, reach: 2.5, weapon: STONE_AXE }   # 2026-10-26 照 PoE2 Ancestral Spirits：召喚圖騰時觸發（跟圖騰一起消失：圖騰撐 8 秒）',
    'cast_time: 0.1', 'mana: 0', 'visual: ancestral_totem',
])

# ---------- 鑄星：鍛鍊武器、幻化武器、擊中時火焰法術 ----------
temper_mana = [max(1, round(x / 60 * 1.125)) for x in [(S['TemperWeaponPlayer']['per_level'].get(L) or {}).get('costs', {}).get('ManaPerMinute', 0) for L in LV]]
block('temper_weapon', [
    'name: 鍛鍊武器',
    'description: 在鐵砧上鍛打主手的近戰武器：之後 12 次近戰攻擊擊中時觸發燃爆（3 公尺的火焰爆炸）。冷卻 5 秒。',
    'icon: IRON_INGOT', 'type: spell', 'tier: 1', 'tags: [duration, fire]', ASC,
    'mechanic: warcry',
    'warcry:',
    '  cry: false',
    '  radius: 2',
    '  duration: 30',
    '  taunt: false',
    '  allies: false',
    '  buff:',
    '    ' + MARK,
    'cast_time: 1.125   # 原作要引導（每敲一下強化 3 次，最多 12 次）；引擎沒有這種引導，敲完一次給滿 12 次',
    'cooldown: 5',
    'mana: %s   # %s；原作引導中每分鐘扣，這裡換算成一次 1.125 秒' % (fl(temper_mana), SRC % 'Temper Weapon'),
    'visual: heavy_strike',
])
block('asc_combust', [
    'name: 燃爆', 'hidden: true', 'type: attack', 'tags: [attack, area, fire]',
    'mechanic: nova', 'nova: { radius: 3 }   # 2026-10-26 照 PoE2 Combust：3 公尺、物理全部轉火焰、不會被閃避',
    'attack_speed: 1.0',
    'effectiveness: %s   # %s' % (fl(eff('TemperWeaponCombustionPlayer')), SRC % 'Combust'),
    'convert: { fire: 1.0 }', 'mana: 0', 'visual: fireball',
    'modifiers:', '  - { stat: flag.always_hit, type: FLAT, value: 1 }',
])
block('manifest_weapon', [
    'name: 幻化武器',
    'description: 常駐：複製你主手的近戰武器，變成一把不會死的夥伴跟著你戰鬥。每 1 點力量增加 1% 傷害、每 3 點敏捷增加 1% 攻擊速度。不保留精魂。',
    'icon: IRON_SWORD', 'type: spell', 'tier: 1', 'tags: [minion, persistent]', ASC,
    'mechanic: aura',
    'aura: { reserve: 0, radius: 8, allies: false }',
    'summon: { kind: VEX, count: 1, max: 1, fixed_max: true, duration: 0, life: 10, damage: 1.6, attack_speed: 1.0, speed: 1.4, reach: 2.5, weapon: IRON_SWORD,',
    '          resists: { fire: 75, cold: 75, lightning: 75, chaos: 75 } }   # 2026-10-26 照 PoE2 Manifest Weapon：不會死（生命 10 倍、抗性滿）',
    'cast_time: 0.5', 'cooldown: 1.5', 'mana: 0', 'visual: summon_skeletons',
])
block('fire_spell_on_hit', [
    'name: 擊中時火焰法術',
    'description: 主要寶石（常駐）：綁在上方按鍵格就開著，不保留精魂。你用近戰攻擊擊中敵人時，每點怪物強度獲得 3 點能量，能量到上限（插槽法術的基礎施法時間每 0.1 秒 10 點，全部加起來）就觸發插在它裡面的所有火焰法術、失去全部能量。插槽只能放火焰法術（可以放好幾個，全部一起觸發；其他孔的輔助都套在它們上面），不花魔力。',
    'icon: FLINT', 'type: spell', 'tier: 1', 'tags: [persistent, meta, fire]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false }',
    'meta: { kind: melee_hit, energy: 3, energy_more: %s }   # %s（能量獲得增加）' % (fl(col('MetaCastFireSpellOnHitPlayer', 'energy_generated_+%')), SRC % 'Fire Spell on Melee Hit'),
    'mana: 0', 'visual: rallying_cry',
])

# ---------- 斷咒：巫術護盾 ----------
block('sorcery_ward', [
    'name: 巫術護盾',
    'description: 常駐：身上有一層屏障，替你承受擊中的元素傷害（最多等同護甲與閃避值的 30%，上限 32000）。停止承受傷害或打破之後 8～6.1 秒立刻充滿。不保留精魂。',
    'icon: PRISMARINE_CRYSTALS', 'type: spell', 'tier: 1', 'tags: [persistent, fire, cold, lightning]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: aura_purity',
    'level_modifiers:',
    '  - { stat: asc.ward_delay, type: FLAT, value: %s }   # %s' % (fl(secs(col('SorceryWardPlayer', 'aegis_recharge_delay_ms'))), SRC % 'Sorcery Ward'),
])

# ---------- 弩衛：支援火力 ----------
# 2026-10-27 照 PoE2 0.5（Supporting Fire／Command: Death From Above，RePoE）：常駐召喚一名不會受傷的砲手站在你身後，只在你下指令時行動；
#   按技能鍵下指令「從天而降」：朝準心射信號彈，之後每 0.75 秒一輪箭雨、共 6 輪，每輪打 14 × 14 公尺內的敵人 763% 傷害（冷卻 10 秒、魔力 8～83）。
#   砲手每 1 點你的力量增加 1% 傷害、每 3 點敏捷增加 1% 範圍（MinionService.deathFromAbove）。
#   這次之前砲手不是召喚物：一個 4 秒冷卻、不花魔力的箭雨（攻擊，20 級 150%）。
block('supporting_fire', [
    'name: 支援火力',
    'description: 常駐：召集一名不會受傷的砲手在你身後就位，只在你下指令時行動。按技能鍵下指令「從天而降」：朝準心射出信號彈，之後每 0.75 秒一輪箭雨、共 6 輪，每輪對 14 × 14 公尺內的敵人造成 763% 的攻擊傷害（冷卻 10 秒）。砲手每 1 點你的力量增加 1% 傷害、每 3 點敏捷增加 1% 範圍。不保留精魂。',
    'icon: ARROW', 'type: spell', 'tier: 1', 'tags: [minion, persistent, command]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false }',
    'summon: { kind: SKELETON, ally: true, count: 1, max: 1, fixed_max: true, duration: 0, life: 1.0, damage: 1.0, attack_speed: 1.0, speed: 1.2, reach: 2.0, weapon: CROSSBOW }   # 不會受傷、只聽指令（MinionAbilities.COMMAND_ONLY）',
    'cast_time: 0.7   # 照 PoE2 指令的施放時間', 'cooldown: 1.5', 'mana: 0   # 指令的魔力 8～83 照 MinionAbilities（從天而降）', 'visual: summon_skeletons',
])

# ---------- 嵌晶：高尚禦盾 ----------
block('virtuous_barrier', [
    'name: 高尚禦盾',
    'description: 常駐：每隔一陣子獲得一顆隨機屬性的寶珠，被擊中時失去一顆。力量寶珠：增加 2% 最大生命；智慧寶珠：增加 5% 生命與魔力再生速度；敏捷寶珠：增加 5% 護甲、閃避與能量護盾。每種最多 3 顆加上面板上需要那種屬性的技能數（單一屬性的算兩次）。不保留精魂。',
    'icon: AMETHYST_SHARD', 'type: spell', 'tier: 1', 'tags: [persistent]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: aura_armour',
    'level_modifiers:',
    '  - { stat: asc.mote_interval, type: FLAT, value: %s }   # %s' % (fl(secs(col('VirtuousBarrierPlayer', 'base_active_skill_buff_stack_gain_frequency_ms'))), SRC % 'Virtuous Barrier'),
])

# ---------- 獵姬：元素湧動的湧動爆炸（2026-10-27 照 PoE2：另外一發爆炸） ----------
# 用掉湧動的投射物飛行結束時（SkillResources／ProjectileEngine）在那裡觸發：1.8 公尺、攻擊傷害 50%～181%（1～20 級）、物理全部轉成那種元素。
# 這次之前做不到另一發爆炸：投射物自己在終點爆開 1.8 公尺、獲得 100% 傷害為額外的那種元素傷害（平衡等效）。
for element, setid, visual in (('fire', 'AmazonTriggerFireSurgePlayer', 'fireball'), ('cold', 'AmazonTriggerColdSurgePlayer', 'frost_nova'),
                               ('lightning', 'AmazonTriggerLightningSurgePlayer', 'spark')):
    block('asc_surge_' + element, [
        'name: %s湧動爆炸' % {'fire': '火焰', 'cold': '冰冷', 'lightning': '閃電'}[element], 'hidden: true', 'type: attack',
        'tags: [attack, area, %s]' % element,
        'mechanic: nova', 'nova: { radius: 1.8 }   # 2026-10-27 照 PoE2 Elemental Surge：爆炸半徑 1.8 公尺',
        'attack_speed: 1.0',
        'effectiveness: %s   # %s' % (fl(eff('AmazonTriggerElementalSurgePlayer', setid)), SRC % 'Elemental Surge'),
        'convert: { %s: 1.0 }' % element, 'mana: 0', 'visual: ' + visual,
    ])

# ---------- 儀祭：鮮血沸騰、儀式獻祭、女王進軍 ----------
block('blood_boil', [
    'name: 鮮血沸騰',
    'description: 常駐：存在範圍內的非傳奇敵人每 2～1.52 秒累積一個血泡。牠死掉時血泡爆開，每個血泡給 1.5 公尺內的敵人一層腐化之血（每秒受到死掉那隻最大生命 2% 的物理傷害，5 秒；每個血泡範圍更多 20%）。不保留精魂。',
    'icon: NETHER_BRICK', 'type: spell', 'tier: 1', 'tags: [persistent, physical, duration, area]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: aura_life',
    'level_modifiers:',
    '  - { stat: asc.boil_interval, type: FLAT, value: %s }   # %s' % (fl(secs(col('BloodBoilPlayer', 'blood_boil_application_interval_ms'))), SRC % 'Blood Boil'),
])
block('ritual_sacrifice', [
    'name: 儀式獻祭',
    'description: 獻祭附近一具稀有怪物的屍體，在一段時間內獲得牠的詞綴（82～120 秒）；附近沒有的話改成割自己最大生命的 20%，獲得一個隨機的怪物詞綴（取代上一個這樣拿到的）。施放 5 秒（可以慢慢移動）。',
    'icon: BONE', 'type: spell', 'tier: 1', 'tags: [duration]', ASC,
    'mechanic: warcry',
    'warcry:',
    '  cry: false',
    '  radius: 2',
    '  duration: 1',
    '  taunt: false',
    '  allies: false',
    '  buff:',
    '    ' + MARK,
    'cast_time: 5', 'cooldown: 1', 'mana: 20   # ' + SRC % 'Ritual Sacrifice', 'visual: intimidating_cry',
    'level_modifiers:',
    '  - { stat: asc.sacrifice_seconds, type: FLAT, value: %s }' % fl(secs(col('RitualSacrificePlayer', 'base_skill_effect_duration'))),
])
qp_min = col('QueensProcessionReservationPlayer', 'main_hand_weapon_minimum_physical_damage')
qp_max = col('QueensProcessionReservationPlayer', 'main_hand_weapon_maximum_physical_damage')
qp_self_min = col('QueensProcessionReservationPlayer', 'active_skill_minimum_physical_self_damage_to_take')
qp_self_max = col('QueensProcessionReservationPlayer', 'active_skill_maximum_physical_self_damage_to_take')
block('queens_procession', [
    'name: 女王進軍',
    'description: 常駐：衝刺時化成一群蝗蟲，每 0.5 秒打 3 公尺內的所有敵人（物理攻擊，不看武器）；打中時你受到一點物理傷害、獲得一層蟲群前進（每層更多 2% 傷害，最多 50 層，停止衝刺 4 秒後失去）。不保留精魂。',
    'icon: SUGAR', 'type: spell', 'tier: 1', 'tags: [persistent, physical, area]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: aura_haste',
    'level_modifiers:',
    '  - { stat: asc.swarm_min, type: FLAT, value: %s }   # %s（基礎傷害 × 傷害效益）' % (fl([round(a * m / 100) for a, m in zip(qp_min, col('QueensProcessionReservationPlayer', 'damage_multiplier'))]), SRC % "Queen's Procession"),
    '  - { stat: asc.swarm_max, type: FLAT, value: %s }' % fl([round(a * m / 100) for a, m in zip(qp_max, col('QueensProcessionReservationPlayer', 'damage_multiplier'))]),
    '  - { stat: asc.swarm_self, type: FLAT, value: %s }' % fl([round((a + b) / 2) for a, b in zip(qp_self_min, qp_self_max)]),
])

# ---------- 星矢：預告射擊、幻影銳眼 ----------
block('called_shots', [
    'name: 預告射擊',
    'description: 常駐：每 5 秒把你面板上的印記技能放在 10 公尺內一個還沒被標記的敵人身上；印記被用掉時再放到另一個敵人身上（最多每 5 秒一次）。不保留精魂。',
    'icon: SPECTRAL_ARROW', 'type: spell', 'tier: 1', 'tags: [persistent, mark]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: hunters_mark',
    'level_modifiers:',
    '  - { stat: asc.called_seconds, type: FLAT, value: 5 }   # 2026-10-26 照 PoE2（Called Shots 每 5 秒）',
])
block('mirage_deadeye', [
    'name: 幻影銳眼',
    'description: 常駐：發射遠程投射物攻擊時，在你站的地方留下一個幻影（4～5 秒，冷卻 10 秒），幻影跟著你重複使用投射物攻擊，傷害更少 50%。不保留精魂。',
    'icon: PHANTOM_MEMBRANE', 'type: spell', 'tier: 1', 'tags: [persistent]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: aura_evasion',
    'level_modifiers:',
    '  - { stat: asc.mirage_seconds, type: FLAT, value: %s }   # %s' % (fl(secs(col('MirageDeadeyeSpawnPlayer', 'base_skill_effect_duration'))), SRC % 'Mirage Deadeye'),
])

# ---------- 御風：五種混合物 ----------
CONC = [
    ('bleeding_concoction', '流血混合物', 'BleedingConcoctionPlayer', 'physical', 'NETHER_BRICK', 5,
     '用掉魔力藥劑 5 點充能，丟出一瓶藥劑在落點爆炸（1.8 公尺），造成物理攻擊傷害（不看武器），一定造成流血並加劇。',
     ['- { stat: bleed.chance, type: FLAT, value: 100 }', '- { stat: asc.aggravate_on_hit, type: FLAT, value: 1 }']),
    ('shattering_concoction', '粉碎混合物', 'ShatteringConcoctionPlayer', 'cold', 'PRISMARINE_SHARD', 11,
     '用掉魔力藥劑 5 點充能，丟出一瓶藥劑在落點爆炸（1.8 公尺），造成冰冷攻擊傷害（不看武器）並讓敵人曝露。',
     ['- { stat: exposure.chance, type: FLAT, value: 100 }']),
    ('explosive_concoction', '爆裂物', 'ExplosiveConcoctionPlayer', 'fire', 'BLAZE_POWDER', 5,
     '用掉魔力藥劑 5 點充能，丟出一瓶藥劑在落點爆炸（1.8 公尺），造成火焰攻擊傷害（不看武器），易燃更多 100%；另外對附近最多 4 個點燃中的敵人各丟一瓶小的。',
     ['- { stat: ignite.chance, type: MORE, value: 100 }']),
    ('fulminating_concoction', '烈性混合物', 'FulminatingConcoctionPlayer', 'lightning', 'GLOWSTONE_DUST', 8,
     '用掉魔力藥劑 5 點充能，丟出一瓶藥劑在落點爆炸（1.8 公尺），造成閃電攻擊傷害（不看武器），感電機率更多 200%、感電效果更多 50%。',
     ['- { stat: shock.chance, type: MORE, value: 200 }', '- { stat: shock.effect, type: INCREASED, value: 50 }']),
    ('acidic_concoction', '酸性靈藥', 'AcidicConcoctionPlayer', 'physical', 'SLIME_BALL', 5,
     '用掉魔力藥劑 5 點充能，丟出一瓶藥劑在落點爆炸（1.8 公尺），造成物理攻擊傷害（不看武器），不會造成中毒；擊中時消耗敵人身上的中毒，造成剩餘傷害 150% 的爆裂。',
     ['- { stat: poison.chance, type: MORE, value: -100 }', '- { stat: asc.consume_poison, type: FLAT, value: 150 }']),
]
for sid, name, src, element, icon, crit, desc, mods in CONC:
    lo = col(src, 'main_hand_weapon_minimum_%s_damage' % element)
    hi = col(src, 'main_hand_weapon_maximum_%s_damage' % element)
    block(sid, [
        'name: ' + name, 'description: ' + desc,
        'icon: ' + icon, 'type: attack', 'tier: 1', 'tags: [attack, projectile, area, grenade, %s]' % element, ASC,
        'mechanic: impact', 'impact: { range: 16, delay: 0.6, radius: 1.8 }   # 2026-10-26 照 PoE2（1.8 公尺）',
        'attack_speed: 0.85   # 照 PoE2（攻擊速度 -15%）',
        'effectiveness: %s   # %s（傷害效益）' % (fl(eff(src)), SRC % name),
        'mana: 0   # 照 PoE2：不花魔力，用掉魔力藥劑 5 點充能（AscendancyEffects）',
        'visual: explosive_grenade',
        'modifiers:',
        '  - { stat: flag.no_weapon_damage, type: FLAT, value: 1 }   # 不看武器傷害（原作：This damage is not based on your unarmed damage）',
    ] + ['  ' + m for m in mods] + [
        'level_modifiers:',
        '  - { stat: damage.added.min, type: FLAT, value: %s, tags: [%s] }   # 原作的基礎傷害' % (fl(lo), element),
        '  - { stat: damage.added.max, type: FLAT, value: %s, tags: [%s] }' % (fl(hi), element),
    ])

# ---------- 喚雷：元素展現、無縛化身、冥想 ----------
EXP = [('fire', 'ElementalExpressionFirePlayer', '火之展現', 'mechanic: nova', 'nova: { radius: 3.6 }', 7, 'fireball'),
       ('cold', 'ElementalExpressionColdPlayer', '冰之展現', 'mechanic: projectile', 'projectile: { count: 3, spread: 30, speed: 1.4, range: 18, size: 0.5, pierce: 20 }', 12, 'ice_shot'),
       ('lightning', 'ElementalExpressionLightningPlayer', '雷之展現', 'mechanic: chain', 'chain: { range: 14, chains: 7, chain_range: 8 }', 9, 'arc')]
for element, setid, name, mech, params, crit, visual in EXP:
    block('asc_expression_' + element, [
        'name: ' + name, 'hidden: true', 'type: spell', 'tags: [spell, %s, %s]' % (element, 'area' if element == 'fire' else 'projectile' if element == 'cold' else 'chain'),
        mech, params,
        'damage: %s   # %s' % (dt(dmg('ElementalExpressionTriggeredPlayer', element, setid)), SRC % 'Elemental Expression'),
        'damage_types: { %s: 1.0 }' % element, 'cast_time: 0.5', 'effectiveness: 1.0', 'mana: 0', 'visual: ' + visual,
        'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: %d }' % crit,
    ])
ua_dur = secs(col('UnboundAvatarPlayer', 'base_skill_effect_duration'))
block('unbound_avatar', [
    'name: 無縛化身',
    'description: 讓敵人冰緩、感電、點燃時獲得 1 點無縛怒火（冰凍、電擊 6 點），滿 100 點就能用：成為無縛 8～9.9 秒，元素傷害更多 40%、元素異常機率更多 80%、凍結與電擊累積更多 40%。冷卻 12 秒。',
    'icon: NETHER_STAR', 'type: spell', 'tier: 1', 'tags: [duration, fire, cold, lightning]', ASC,
    'mechanic: warcry',
    'warcry:',
    '  cry: false', '  radius: 2', '  duration: %s' % fmt(ua_dur[0]), '  taunt: false', '  allies: false',
    '  buff:',
    '    - { stat: damage, type: MORE, value: 40, tags: [elemental] }',
    '    - { stat: ignite.chance, type: MORE, value: 80 }',
    '    - { stat: shock.chance, type: MORE, value: 80 }',
    '    - { stat: chill.effect, type: MORE, value: 80 }',
    '    - { stat: freeze.buildup, type: MORE, value: 40 }',
    '    - { stat: electrocute.buildup, type: MORE, value: 40 }',
    'cast_time: 0.2', 'cooldown: 12', 'mana: 0   # 照 PoE2：不花魔力，要 100 點無縛怒火',
    'resource: { glory: 100, glory_from: ailment }   # 2026-10-26 照 PoE2 Unbound Avatar：無縛怒火當作這個技能的榮耀（元素異常時獲得）',
    'visual: star_cry',
    'level_modifiers:',
    '  - { stat: asc.buff_seconds, type: FLAT, value: %s }   # %s（無縛持續時間）' % (fl(ua_dur), SRC % 'Unbound Avatar'),
])
# 2026-10-27 照 PoE2 Meditate：真的引導（按住，Channelling 的 recharge 模式）：按住期間回充能量護盾（不用等延遲，回充速度更多 24～100%），
#   回充可以溢出到最大的 1.5 倍（溢出的部分是守護），受到傷害或溢出滿了就結束；引導中移動更少 70%。冷卻在開始時就算。
#   這次之前沒有這種引導：放一次立刻回充約 2 秒份。
block('meditate', [
    'name: 冥想',
    'description: 引導（按住；數字鍵按一下開始、再按一下結束）：能量護盾立刻開始回充（回充速度更多 24～100%），回充可以溢出到最大能量護盾的 1.5 倍（溢出的部分變成守護）。受到傷害或溢出滿了就結束；引導中移動更少 70%。',
    'icon: LAPIS_LAZULI', 'type: spell', 'tier: 1', 'tags: [duration]', ASC,
    'mechanic: warcry',
    'warcry:',
    '  cry: false', '  radius: 2', '  duration: 2', '  taunt: false', '  allies: false',
    '  buff:',
    '    - { stat: es.recharge, type: MORE, value: %s }' % fl(col('MeditatePlayer', 'meditate_energy_shield_recharge_rate_+%_final')),
    'charge: { mode: recharge, slow: 70, interrupt_on_hit: true }   # 2026-10-27 照 PoE2：受到傷害就中斷（movement_speed_+%_final_while_performing_action -70）',
    'cast_time: 1.0',
    'cooldown: %s   # %s（技能最高 20 級，用 20 級的冷卻）' % (fmt(secs(cooldown('MeditatePlayer'))[19]), SRC % 'Meditate'),
    'mana: 0   # 照 PoE2：不花魔力', 'visual: aura_es',
])

# ---------- 虛步：虛空幻影、遁入裂痕、夏烏拉統治者 ----------
block('void_illusion', [
    'name: 虛空幻影',
    'description: 常駐：翻滾時在原地留下一個只有 1 點生命的幻影（2 秒）；被敵人打掉時爆炸（3 公尺）。最多存 3 次、每 4 秒回一次。不保留精魂。',
    'icon: ECHO_SHARD', 'type: spell', 'tier: 1', 'tags: [persistent, duration]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: void_rift',
    'level_modifiers:',
    '  - { stat: asc.illusion_seconds, type: FLAT, value: 2 }   # 2026-10-26 照 PoE2（Void Illusion 幻影 2 秒）',
])
block('asc_void_explosion', [
    'name: 虛空爆裂', 'hidden: true', 'type: attack', 'tags: [attack, area, physical]',
    'mechanic: nova', 'nova: { radius: 3 }',
    'attack_speed: 1.0', 'effectiveness: %s   # %s' % (fl(eff('VoidIllusionSpawnPlayer')), SRC % 'Void Illusion'),
    'mana: 0', 'visual: void_rift',
])
block('into_the_breach', [
    'name: 遁入裂痕',
    'description: 常駐：在身邊打開裂隙，每 1.4～1.21 秒在 8.5 公尺內顯現一個夏烏拉之炎（25 秒），走過去撿起：紫色每層獲得 7% 傷害為額外混沌（最多 10 層）、藍色偷取 20% 最大魔力、紅色偷取 20% 最大生命。不保留精魂。',
    'icon: AMETHYST_SHARD', 'type: spell', 'tier: 1', 'tags: [persistent]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: void_rift',
    'level_modifiers:',
    '  - { stat: asc.flame_interval, type: FLAT, value: %s }   # %s' % (fl(secs(col('IntoTheBreachPlayer', 'breach_flame_spawn_rate_ms'))), SRC % 'Into the Breach'),
    '  - { stat: asc.flame_effect, type: FLAT, value: %s }' % fl(col('IntoTheBreachPlayer', 'remnant_effect_+%')),
])
block('archon_of_chayula', [
    'name: 夏烏拉統治者',
    'description: 用混沌傷害擊中敵人時照牠的強度獲得榮耀，滿 100 點就能用：成為夏烏拉的統治者 10 秒，物理與混沌傷害更多 20%、擊中 25% 機率凋零敵人 4 秒，每 3 秒觸發夏烏拉之志（追著敵人的混沌龍捲）。',
    'icon: ECHO_SHARD', 'type: spell', 'tier: 1', 'tags: [duration, chaos]', ASC,
    'mechanic: warcry',
    'warcry:',
    '  cry: false', '  radius: 2', '  duration: 10', '  taunt: false', '  allies: false', '  glory: 100',
    '  buff:',
    '    - { stat: damage, type: MORE, value: 20, tags: [physical] }',
    '    - { stat: damage, type: MORE, value: 20, tags: [chaos] }',
    'cast_time: 1.0', 'mana: 0   # 照 PoE2：不花魔力，要 100 榮耀',
    'resource: { glory_from: chaos_hit }   # 2026-10-26 照 PoE2 Archon of Chayula（要 100 榮耀，寫在 warcry.glory）',
    'visual: void_rift',
])
block('asc_chayula_will', [
    'name: 夏烏拉之志', 'hidden: true', 'type: attack', 'tags: [attack, area, chaos, duration]',
    'mechanic: zone', 'zone: { range: 0, radius: 2.8, duration: 8, interval: 0.2, damage: 100, max: 0 }   # 2026-10-26 照 PoE2 Chayula\'s Will：2.8 公尺、8 秒',
    'attack_speed: 1.0', 'effectiveness: %s   # %s' % (fl(eff('ArchonOfChayulaTornadoPlayer')), SRC % "Chayula's Will"),
    'convert: { chaos: 0.7 }', 'mana: 0', 'visual: void_rift',
])

# ---------- 星焰：元素風暴（法術暴擊時觸發） ----------
for element in ('fire', 'cold', 'lightning'):
    name = {'fire': '火焰風暴', 'cold': '冰冷風暴', 'lightning': '閃電風暴'}[element]
    setid = {'fire': 'ElementalStormFirePlayer', 'cold': 'ElementalStormColdPlayer', 'lightning': 'ElementalStormLightningPlayer'}[element]
    block('elemental_storm_' + element, [
        'name: ' + name, 'hidden: true', 'type: spell', 'tags: [spell, area, duration, %s]' % element,
        'mechanic: zone', 'zone: { range: 0, radius: 1.8, duration: 5, interval: 0.25, damage: 100, max: 10 }   # 2026-10-26 照 PoE2 Elemental Storm（同時最多 10 個：引擎上限）',
        'damage: %s   # %s' % (dt(dmg('ElementalStormPlayer', element, setid)), SRC % 'Elemental Storm'),
        'damage_types: { %s: 1.0 }' % element, 'cast_time: 0.5', 'effectiveness: 0.25', 'mana: 0',
        'visual: %s' % {'fire': 'flame_wall', 'cold': 'blizzard', 'lightning': 'storm_wave'}[element],
        'modifiers:', '  - { stat: flag.no_crit, type: FLAT, value: 1 }', '  - { stat: hinder.on_hit, type: FLAT, value: 100 }',
    ])

# ---------- 法袍：時間短縮、時間凍結、命定苦痛、時空裂隙 ----------
block('time_snap', [
    'name: 時間短縮',
    'description: 操弄時間，重置你其他技能的冷卻。冷卻 40～32.4 秒。',
    'icon: CLOCK', 'type: spell', 'tier: 1', 'tags: []', ASC,
    'mechanic: warcry',
    'warcry:', '  cry: false', '  radius: 2', '  duration: 1', '  taunt: false', '  allies: false', '  buff:', '    ' + MARK,
    'cast_time: 0.6', 'cooldown: %s   # %s（技能最高 20 級，用 20 級的冷卻）' % (fmt(secs(cooldown('TimeSnapPlayer'))[19]), SRC % 'Time Snap'),
    'mana: 20', 'visual: star_cry',
])
block('time_freeze', [
    'name: 時間凍結',
    'description: 放出一道 10 公尺的波，讓範圍內的敵人時間停止 3 秒（30 秒內每被停過一次，時間減半）。冷卻 65～55.5 秒。',
    'icon: CLOCK', 'type: spell', 'tier: 1', 'tags: [area, duration]', ASC,
    'mechanic: warcry',
    'warcry:', '  cry: false', '  radius: 10', '  duration: 3', '  taunt: false', '  allies: false', '  buff:', '    ' + MARK,
    'cast_time: 0.7', 'cooldown: %s   # %s（技能最高 20 級，用 20 級的冷卻）' % (fmt(secs(cooldown('TimeFreezePlayer'))[19]), SRC % 'Time Freeze'),
    'mana: 20', 'visual: frost_nova',
])
block('inevitable_agony', [
    'name: 命定苦痛',
    'description: 對準心附近（1.5～3.1 公尺）的敵人施加減益 12 秒：記下牠們受到的擊中傷害的 25%，減益結束（或足以處決）時一次失去那麼多生命。冷卻 2 秒。',
    'icon: GHAST_TEAR', 'type: spell', 'tier: 1', 'tags: [area, duration]', ASC,
    'mechanic: warcry',
    'warcry:', '  cry: false', '  radius: 2', '  duration: 1', '  taunt: false', '  allies: false', '  buff:', '    ' + MARK,
    'cast_time: 0.7', 'cooldown: 2', 'mana: %s   # %s' % (fl(mana('InevitableAgonyPlayer')), SRC % 'Inevitable Agony'),
    'visual: hunters_mark',
    'level_modifiers:',
    '  - { stat: asc.agony_radius, type: FLAT, value: %s }' % fl([x / 10 for x in col('InevitableAgonyPlayer', 'active_skill_base_area_of_effect_radius')]),
])
block('temporal_rift', [
    'name: 時空裂隙',
    'description: 一直記下你最近 4 秒的殘影（每 0.25 秒一個）；施放時回到最舊的殘影那裡，生命、魔力、能量護盾也回到那時候的數值。冷卻 7～5.1 秒。',
    'icon: RECOVERY_COMPASS', 'type: spell', 'tier: 1', 'tags: [movement]', ASC,
    'mechanic: warcry',
    'warcry:', '  cry: false', '  radius: 2', '  duration: 1', '  taunt: false', '  allies: false', '  buff:', '    ' + MARK,
    'cast_time: 0.1', 'cooldown: %s   # %s（技能最高 20 級，用 20 級的冷卻）' % (fmt(secs(cooldown('TemporalRiftPlayer'))[19]), SRC % 'Temporal Rift'),
    'mana: 0   # 照 PoE2：不花魔力', 'visual: frostblink',
])

# ---------- 沙靈：三位巨靈與指令 ----------
for sid, name, desc, icon in (
        ('kelari_djinn', '黯染飛砂．克拉里', '常駐：召喚沙之巨靈克拉里（不會受傷），牠跟在你身邊，只在你下指令（克拉里的欺瞞、審判、惡言）時行動，指令要克拉里在你身邊 20 公尺內；牠的擊中施加暴擊弱點、暴擊傷害加成增加 50%。不保留精魂。', 'GOLD_NUGGET'),
        ('navira_djinn', '最終幻象．納維拉', '常駐：召喚水之巨靈納維拉（不會受傷），牠跟在你身邊，只在你下指令（納維拉的綠洲、水井、封凍）時行動，指令要納維拉在你身邊 20 公尺內；每次下指令觸發納維拉的寧靜：附近友軍魔力再生增加 10～67%，4 秒。不保留精魂。', 'HEART_OF_THE_SEA'),
        ('ruzhan_djinn', '赤炎利劍．魯兹安', '常駐：召喚火之巨靈魯兹安（不會受傷），牠跟在你身邊：你使用造成傷害的技能時，牠瞬移到目標旁用狂熱斬擊打前方的敵人並讓牠們曝露（冷卻 4 秒），最近每用過一個造成傷害的技能更多 10% 傷害。不保留精魂。', 'BLAZE_ROD')):
    # 2026-10-27 照 PoE2：巨靈是有實體、不會受傷、只聽指令的召喚物（MinionService，MinionAbilities.COMMAND_ONLY）；這次之前沒有實體，指令直接在準心的地方生效
    block(sid, [
        'name: ' + name, 'description: ' + desc,
        'icon: ' + icon, 'type: spell', 'tier: 1', 'tags: [persistent, minion]', ASC,
        'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
        'summon: { kind: VEX, ally: true, count: 1, max: 1, fixed_max: true, duration: 0, life: 1.0, damage: 1.0, attack_speed: 1.0, speed: 1.4, reach: 2.0, weapon: %s }'
        % {'kelari_djinn': 'GOLD_NUGGET', 'navira_djinn': 'HEART_OF_THE_SEA', 'ruzhan_djinn': 'BLAZE_ROD'}[sid],
        'mana: 0', 'visual: %s' % {'kelari_djinn': 'aura_precision', 'navira_djinn': 'aura_mana', 'ruzhan_djinn': 'aura_fire'}[sid],
        'level_modifiers:',
        {'kelari_djinn': '  - { stat: asc.djinn_crit_bonus, type: FLAT, value: 50 }   # 2026-10-26 照 PoE2（克拉里的擊中：暴擊傷害加成增加 50%）',
         'navira_djinn': '  - { stat: asc.calming_regen, type: FLAT, value: %s }   # 2026-10-26 照 PoE2（納維拉的寧靜 1 級 10%%～20 級 67%%，中間內插）' % fl([round(10 + 57 * i / 19) for i in range(20)]),
         'ruzhan_djinn': '  - { stat: asc.zealotry_cooldown, type: FLAT, value: 4 }   # 2026-10-26 照 PoE2（魯兹安的狂熱斬擊冷卻 4 秒）'}[sid],
    ])
block('kelari_deception', [
    'name: 克拉里的欺瞞',
    'description: 指令（要先召喚克拉里）：克拉里瞬移到準心的地方炸開 2.5 公尺，造成物理法術傷害，並留下一個 4 秒的分身；分身還在時再用，分身也會爆炸（多 2 層暴擊弱點）。連續使用每次更多 50% 傷害（最多 300%）。最多存 3 次，冷卻 5 秒。',
    'icon: GOLD_NUGGET', 'type: spell', 'tier: 1', 'tags: [spell, area, physical, command]', ASC,
    'mechanic: impact', 'impact: { range: 20, delay: 0.1, radius: 2.5 }',
    'damage: [16, 23]   # 2026-10-26 照 PoE2 Kelari\'s Deception（1 級 16～23，照法術的等級成長）',
    'damage_types: { physical: 1.0 }', 'cast_time: 0.8', 'effectiveness: 1.0', 'cooldown: 5', 'mana: 0   # 照 PoE2：指令不花魔力',
    'visual: earthquake',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 15 }', '  - { stat: crit_weakness.on_hit, type: FLAT, value: 1 }',
    '  - { stat: crit.bonus, type: INCREASED, value: 50 }',
])
block('asc_kelari_clone', [
    'name: 克拉里的分身', 'hidden: true', 'type: spell', 'tags: [spell, area, physical]',
    'mechanic: nova', 'nova: { radius: 2.5 }',
    'damage: [19, 28]   # 2026-10-26 照 PoE2（分身爆炸 1 級 19～28）', 'damage_types: { physical: 1.0 }', 'cast_time: 0.5', 'effectiveness: 1.0', 'mana: 0',
    'visual: earthquake',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 15 }', '  - { stat: crit_weakness.on_hit, type: FLAT, value: 3 }',
    '  - { stat: crit.bonus, type: INCREASED, value: 50 }',
])
block('kelari_judgment', [
    'name: 克拉里的審判',
    'description: 指令（要先召喚克拉里）：克拉里在準心的地方懸起一排刀刃，落下時造成震波（3.5 公尺）的物理法術傷害，暈眩累積更多 100%、多 4 層暴擊弱點。原作要引導（最多 4 段，每段更多 125% 傷害），這裡照 3 段算。冷卻 12 秒。',
    'icon: GOLD_NUGGET', 'type: spell', 'tier: 1', 'tags: [spell, area, physical, command]', ASC,
    'mechanic: impact', 'impact: { range: 20, delay: 0.6, radius: 3.5 }',
    'damage: [53, 81]   # 2026-10-26 照 PoE2 Kelari\'s Judgment（1 級 15～23，引導 3 段：更多 250% → ×3.5）',
    'damage_types: { physical: 1.0 }', 'cast_time: 1.5', 'effectiveness: 1.0', 'cooldown: 12', 'mana: 0   # 照 PoE2：指令不花魔力',
    'visual: earthquake',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 15 }', '  - { stat: crit_weakness.on_hit, type: FLAT, value: 5 }',
    '  - { stat: crit.bonus, type: INCREASED, value: 50 }', '  - { stat: stun.buildup, type: MORE, value: 100 }',
])
block('kelari_malediction', [
    'name: 克拉里的惡言',
    'description: 常駐（要先召喚克拉里）：自動消耗存在範圍內的屍體，每具生出 2 隻屍甲蟲（最多 4～8 隻，每 1.5～0.9 秒一具）；甲蟲衝向附近的敵人爆炸（1.5 公尺物理傷害，對傳奇更多 40%、20% 機率致盲）。不保留精魂。',
    'icon: RABBIT_FOOT', 'type: spell', 'tier: 1', 'tags: [persistent, minion]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: aura_precision',
    'level_modifiers:',
    '  - { stat: asc.beetle_interval, type: FLAT, value: %s }   # %s' % (fl(secs(col('CorpseBeetlesPlayer', 'base_secondary_skill_effect_duration'))), SRC % "Kelari's Malediction"),
    '  - { stat: asc.beetle_max, type: FLAT, value: %s }' % fl(col('CorpseBeetlesPlayer', 'maximum_corpse_beetles_allowed')),
])
block('asc_corpse_beetle', [
    'name: 屍甲蟲爆', 'hidden: true', 'type: spell', 'tags: [spell, area, physical]',
    'mechanic: nova', 'nova: { radius: 1.5 }',
    'damage: [9, 14]   # 2026-10-26 照 PoE2 Sandblast（1 級 9～14）', 'damage_types: { physical: 1.0 }', 'cast_time: 0.5', 'effectiveness: 1.0', 'mana: 0',
    'visual: blight_nova',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 15 }', '  - { stat: crit_weakness.on_hit, type: FLAT, value: 1 }',
    '  - { stat: crit.bonus, type: INCREASED, value: 50 }', '  - { stat: blind.chance, type: FLAT, value: 20 }',
    '  - { stat: damage.vs_unique, type: INCREASED, value: 40 }',
])
block('navira_oasis', [
    'name: 納維拉的綠洲',
    'description: 指令（要先召喚納維拉）：在你腳下 8 公尺變成綠洲 15 秒：站在裡面的你與隊友每秒獲得 0.5 點藥劑充能，受到的傷害 30% 回復為生命、20% 回復為魔力。冷卻 8 秒。',
    'icon: HEART_OF_THE_SEA', 'type: spell', 'tier: 1', 'tags: [area, duration, command]', ASC,
    'mechanic: warcry',
    'warcry:', '  cry: false', '  radius: 8', '  duration: 15', '  taunt: false', '  allies: false', '  buff:', '    ' + MARK,
    'cast_time: 1.8', 'cooldown: 8', 'mana: 0   # 照 PoE2：指令不花魔力', 'visual: aura_mana',
])
block('navira_well', [
    'name: 納維拉的水井',
    'description: 指令（要先召喚納維拉）：能量護盾立刻開始回充，6 秒內能量護盾回充速度增加 10%。冷卻 12 秒。',
    'icon: HEART_OF_THE_SEA', 'type: spell', 'tier: 1', 'tags: [duration, command]', ASC,
    'mechanic: warcry',
    'warcry:', '  cry: false', '  radius: 2', '  duration: 6', '  taunt: false', '  allies: false',
    '  buff:', '    - { stat: es.recharge, type: INCREASED, value: 10 }',
    'cast_time: 2.1', 'cooldown: 12', 'mana: 0   # 照 PoE2：指令不花魔力', 'visual: aura_es',
])
block('navira_fracturing', [
    'name: 納維拉的封凍',
    'description: 指令（要先召喚納維拉）：讓準心附近 6 公尺內冰緩中的敵人腳下碎裂，造成冰冷法術傷害，凍結累積更多 200%、照更多 200% 的傷害算冰緩。冷卻 5 秒。',
    'icon: PRISMARINE_SHARD', 'type: spell', 'tier: 1', 'tags: [spell, area, cold, command]', ASC,
    'mechanic: impact', 'impact: { range: 20, delay: 0.3, radius: 6 }',
    'damage: [44, 66]   # 2026-10-26 照 PoE2 Navira\'s Fracturing（1 級 44～66）', 'damage_types: { cold: 1.0 }', 'cast_time: 1.2',
    'effectiveness: 1.0', 'cooldown: 5', 'mana: 0   # 照 PoE2：指令不花魔力', 'visual: frost_nova',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 12 }', '  - { stat: freeze.buildup, type: MORE, value: 200 }',
    '  - { stat: chill.effect, type: MORE, value: 200 }', '  - { stat: asc.chilled_only, type: FLAT, value: 1 }',
])
block('ruzhan_reckoning', [
    'name: 魯兹安的清算',
    'description: 指令（要先召喚魯兹安）：魯兹安瞬移到準心的地方，對身邊 1.5 公尺造成火焰法術傷害，接著劍砸地面掀起一道 4 公尺的熔岩浪（點燃效果更多 100%）。冷卻 12 秒。',
    'icon: MAGMA_CREAM', 'type: spell', 'tier: 1', 'tags: [spell, area, fire, command]', ASC,
    'mechanic: impact', 'impact: { range: 20, delay: 0.4, radius: 1.5 }',
    'damage: [8, 13]   # 2026-10-26 照 PoE2 Ruzhan\'s Reckoning（1 級 8～13；熔岩浪 29～44 是另一段）', 'damage_types: { fire: 1.0 }', 'cast_time: 2.3',
    'effectiveness: 1.0', 'cooldown: 12', 'mana: 0   # 照 PoE2：指令不花魔力', 'visual: comet',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 7 }',
])
block('asc_ruzhan_wave', [
    'name: 熔岩浪', 'hidden: true', 'type: spell', 'tags: [spell, area, fire]',
    'mechanic: nova', 'nova: { radius: 4 }',
    'damage: [29, 44]   # 2026-10-26 照 PoE2（熔岩浪 1 級 29～44）', 'damage_types: { fire: 1.0 }', 'cast_time: 0.5', 'effectiveness: 1.0', 'mana: 0',
    'visual: flame_wall',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 7 }', '  - { stat: ignite.magnitude, type: MORE, value: 100 }',
])
block('ruzhan_fury', [
    'name: 魯兹安的怒火',
    'description: 指令（要先召喚魯兹安）：魯兹安朝前方連射兩輪各 20 顆火燼，打中時爆炸（0.9 公尺），對被禁錮的敵人更多 60% 傷害。冷卻 7 秒。',
    'icon: BLAZE_ROD', 'type: spell', 'tier: 1', 'tags: [spell, projectile, area, fire, command]', ASC,
    'mechanic: projectile', 'projectile: { count: 20, spread: 50, speed: 1.4, range: 22, size: 0.3, explode: 0.9, burst: { count: 2, interval: 6 } }',
    'damage: [3, 4]   # 2026-10-26 照 PoE2 Ruzhan\'s Fury（每顆 1 級 3～4）', 'damage_types: { fire: 1.0 }', 'cast_time: 2.3',
    'effectiveness: 1.0', 'cooldown: 7', 'mana: 0   # 照 PoE2：指令不花魔力', 'visual: fireball',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 7 }   # 對被禁錮的敵人更多 60%（AscGranted）',
])
block('ruzhan_trap', [
    'name: 魯兹安的陷阱',
    'description: 指令（要先召喚魯兹安）：魯兹安把巨劍擲到準心的地方，在 5 公尺內撒下 8 個符文（6 秒）；敵人踩到就噴出火焰龍捲（1.2 公尺、4 秒、每 0.7 秒一下，易燃更多 400%）。冷卻 8 秒。',
    'icon: BLAZE_ROD', 'type: spell', 'tier: 1', 'tags: [spell, area, fire, duration, command]', ASC,
    'mechanic: zone', 'zone: { range: 20, radius: 5, duration: 6, interval: 0.7, damage: 100, max: 3 }   # 原作 8 個符文、踩到才噴；引擎照一塊 5 公尺的地面每 0.7 秒打一下',
    'damage: [5, 7]   # 2026-10-26 照 PoE2 Ruzhan\'s Trap（龍捲 1 級 5～7）', 'damage_types: { fire: 1.0 }', 'cast_time: 1.0',
    'effectiveness: 1.0', 'cooldown: 8', 'mana: 0   # 照 PoE2：指令不花魔力', 'visual: flame_wall',
    'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: 7 }', '  - { stat: ignite.chance, type: MORE, value: 400 }',
])
block('asc_ruzhan_zealotry', [
    'name: 魯兹安的狂熱', 'hidden: true', 'type: spell', 'tags: [spell, area, fire]',
    'mechanic: cone', 'cone: { length: 4, angle: 90 }',
    'damage: [10, 16]   # 原作是巨靈的斬擊（沒有公開傷害表）；平衡等效照清算的量', 'damage_types: { fire: 1.0 }', 'cast_time: 0.5', 'effectiveness: 1.0', 'mana: 0',
    'visual: cleave',
    'modifiers:', '  - { stat: exposure.chance, type: FLAT, value: 100 }',
])

# ---------- 疫喚：召喚獄炎獵犬、惡魔變身 ----------
block('infernal_hound', [
    'name: 召喚獄炎獵犬',
    'description: 常駐：召喚一隻會復活的獄炎獵犬跟著你，讓牠身邊 2.5 公尺的敵人點燃（照獵犬最大生命的 20% 當作火焰傷害）。不保留精魂。',
    'icon: BLAZE_POWDER', 'type: spell', 'tier: 1', 'tags: [minion, persistent, fire]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false }',
    'summon: { kind: WOLF, count: 1, max: 1, fixed_max: true, duration: 0, life: 1.5, damage: 1.0, attack_speed: 1.2, speed: 1.4, reach: 2.0 }   # 2026-10-26 照 PoE2 Summon Infernal Hound（點燃光環在 AscGranted）',
    'cast_time: 0.5', 'cooldown: 1.5', 'mana: 0', 'visual: raise_zombie',
])
df_cast = col('DemonFormPlayer', 'demon_form_grants_cast_speed_+%')
block('demon_form', [
    'name: 惡魔變身',
    'description: 變成惡魔：施法速度增加 12～25%，每秒獲得 1 層惡魔烈焰（最多 10 層），每層更多 3% 法術傷害，但每層每秒失去 0.8～18.5 生命。生命剩 1、使用法術以外的技能或再按一次就變回人形。冷卻 15 秒（變身期間不計時）。',
    'icon: MAGMA_CREAM', 'type: spell', 'tier: 1', 'tags: [shapeshift, duration]', ASC,
    'mechanic: warcry',
    'warcry:', '  cry: false', '  radius: 2', '  duration: 30', '  taunt: false', '  allies: false',
    '  buff:', '    - { stat: cast.speed, type: INCREASED, value: %s }' % fl(df_cast),
    'cast_time: 1.0', 'cooldown: 15', 'mana: %s   # %s' % (fl(mana('DemonFormPlayer')), SRC % 'Demon Form'),
    'visual: herald_ash',
    'level_modifiers:',
    '  - { stat: asc.demon_life_loss, type: FLAT, value: %s }   # 每層每秒失去的生命' % fl([round(x / 60, 2) for x in col('DemonFormPlayer', 'demon_form_life_loss_per_minute_per_stack')]),
])

# ---------- 血術：生命痕跡 ----------
block('life_remnants', [
    'name: 生命痕跡',
    'description: 常駐：擊殺敵人時 25% 機率、擊中敵人時（最多每 3 秒一次）產生一個生命痕跡（8 秒），走過去撿起回復生命（可以溢出到 1.5 倍最大生命，溢出的部分變成守護）。不保留精魂。',
    'icon: GLISTERING_MELON_SLICE', 'type: spell', 'tier: 1', 'tags: [persistent]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: aura_life',
    'level_modifiers:',
    '  - { stat: asc.remnant_life, type: FLAT, value: %s }   # %s' % (fl(col('LifeRemnantsPlayer', 'life_remnants_gain_per_globe')), SRC % 'Life Remnants'),
])

# ---------- 觀星：可趁之刻、命運同調 ----------
block('moment_of_vulnerability', [
    'name: 可趁之刻',
    'description: 放出 4 公尺的時間新星造成物理法術傷害；打中蓄勢待暈、待電擊、待凍結的敵人時，牠的時間殘影替牠承受禁錮，對殘影造成的傷害有 60～98% 也打在本體上（同一隻 10 秒一次）。冷卻 1 秒。',
    'icon: CLOCK', 'type: spell', 'tier: 1', 'tags: [spell, area, physical]', ASC,
    'mechanic: nova', 'nova: { radius: 4 }',
    'damage: %s   # %s' % (dt(dmg('MomentOfVulnerabilityPlayer', 'physical')), SRC % 'Moment of Vulnerability'),
    'damage_types: { physical: 1.0 }', 'cast_time: 0.65', 'effectiveness: 1.0', 'cooldown: 1',
    'mana: %s' % fl(mana('MomentOfVulnerabilityPlayer')), 'visual: frost_nova',
    'level_modifiers:',
    '  - { stat: asc.rip_share, type: FLAT, value: %s }' % fl(col('MomentOfVulnerabilityPlayer', 'time_rip_damage_share_%')),
])
block('align_fate', [
    'name: 命運同調',
    'description: 常駐：每 12～9.15 秒（冷卻回復也會加快），另一條時間線的你出現一次，替你施放面板上一個能造成傷害、沒有冷卻的法術；下一個殘影出現前你自己施放同一個法術就同調命運：那一下更多 30% 傷害。面板上每有一個這樣的法術，殘影出現得更頻繁 25%。不保留精魂。',
    'icon: COMPASS', 'type: spell', 'tier: 1', 'tags: [persistent]', ASC,
    'mechanic: aura', 'aura: { reserve: 0, radius: 8, allies: false, effects: [] }',
    'mana: 0', 'visual: aura_purity',
    'level_modifiers:',
    '  - { stat: asc.visage_interval, type: FLAT, value: %s }   # %s' % (fl(secs(col('AlignFatePlayer', 'base_cooldown_modifiable_repeat_interval_ms'))), SRC % 'Align Fate'),
])

# ---------- 獸巫：壞滅災變 ----------
block('apocalypse', [
    'name: 壞滅災變',
    'description: 用元素傷害擊中敵人時照牠的強度獲得榮耀，滿 100 點就能用：成為行走的災厄 12 秒，每 0.75 秒在 7 公尺內觸發一種天災：隕石（2 秒後落下）、冰雹、雷擊。',
    'icon: NETHER_STAR', 'type: spell', 'tier: 1', 'tags: [area, duration, fire, cold, lightning]', ASC,
    'mechanic: warcry',
    'warcry:', '  cry: false', '  radius: 7', '  duration: 12', '  taunt: false', '  allies: false', '  glory: 100', '  buff:', '    ' + MARK,
    'cast_time: 1.0', 'mana: %s   # %s' % (fl(mana('ApocalypsePlayer')), SRC % 'Apocalypse'),
    'resource: { glory_from: elemental_hit }   # 2026-10-26 照 PoE2 Apocalypse（要 100 榮耀，寫在 warcry.glory）',
    'visual: star_cry',
])
for element, sid, name, mech, params, crit, visual, setid in (
        ('fire', 'ApocalypseFirePlayer', '隕石', 'mechanic: impact', 'impact: { range: 2, delay: 2, radius: 1.6 }', 7, 'comet', None),
        ('cold', 'ApocalypseColdPlayer', '冰雹', 'mechanic: impact', 'impact: { range: 2, delay: 0.3, radius: 2 }', 12, 'blizzard', None),
        ('lightning', 'ApocalypseLightningPlayer', '雷擊', 'mechanic: impact', 'impact: { range: 2, delay: 1.0, radius: 1.6 }', 10, 'lightning_arrow', 'ApocalypseLightningFinalStrikePlayer')):
    block('asc_apocalypse_' + element, [
        'name: ' + name, 'hidden: true', 'type: spell', 'tags: [spell, area, %s]' % element,
        mech, params,
        'damage: %s   # %s' % (dt(dmg(sid, element, setid)), SRC % 'Apocalypse'),
        'damage_types: { %s: 1.0 }' % element, 'cast_time: 0.5', 'effectiveness: 1.0', 'mana: 0', 'visual: ' + visual,
        'modifiers:', '  - { stat: spell.base_crit, type: FLAT, value: %d }' % crit,
    ])

# ---------- 鐵衛、喚雷、狂嘯者：餘震、地面 ----------
block('asc_aftershock', [
    'name: 餘震', 'hidden: true', 'type: attack', 'tags: [attack, melee, area, slam, physical]',
    'mechanic: nova', 'parent_damage: 1.0   # 2026-10-26 照 PoE2：餘震跟原本那一下同樣的傷害、同樣的範圍（天崩地裂、劈山碎石）',
    'nova: { radius: 2.6 }', 'attack_speed: 1.0', 'effectiveness: 1.0', 'mana: 0', 'visual: slam',
])
block('asc_shocked_ground', [
    'name: 感電地面', 'hidden: true', 'type: spell', 'tags: [spell, area, lightning, duration]',
    'mechanic: zone', 'zone: { range: 0, radius: 2.0, duration: 6, interval: 0.5, damage: 1, max: 4 }   # 2026-10-26 照 PoE2 Shocked Ground：6 秒',
    'damage: [1, 2]', 'damage_types: { lightning: 1.0 }', 'cast_time: 0.5', 'effectiveness: 0.1', 'mana: 0', 'visual: spark',
    'modifiers:', '  - { stat: ground.effect, type: FLAT, value: 1 }', '  - { stat: ground.shock, type: FLAT, value: 20 }',
])
block('asc_chilled_ground', [
    'name: 冰緩地面', 'hidden: true', 'type: spell', 'tags: [spell, area, cold, duration]',
    'mechanic: zone', 'zone: { range: 0, radius: 2.0, duration: 6, interval: 0.5, damage: 1, max: 4 }   # 2026-10-26 照 PoE2 Chilled Ground：6 秒',
    'damage: [1, 2]', 'damage_types: { cold: 1.0 }', 'cast_time: 0.5', 'effectiveness: 0.1', 'mana: 0', 'visual: frost_nova',
    'modifiers:', '  - { stat: ground.effect, type: FLAT, value: 1 }', '  - { stat: ground.chill, type: FLAT, value: 20 }',
])

out.append('')
out.append('  # ==== END ascendancy ====')
print('\n'.join(out))
