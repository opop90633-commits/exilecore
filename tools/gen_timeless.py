#!/usr/bin/env python3
"""2026-10-26：把 PoE2 的永恆珠寶（Timeless Jewel）資料轉成 src/main/resources/data/passives/timeless.json。

輸入：
  PoB-PoE2 的 src/Data/TimelessJewelData/LegionPassives.lua（第一個參數；kalguur_*、abyss_* 的基石、核心、小點、附加）
  tools/poe2_tree/（跟 gen_poe2_tree.py 共用：stat_map.json、overrides.json、templates_zh.json 把 PoE2 stat 換成引擎的屬性鍵與中文）

輸出：timeless.json
  legions.<代號>：name（台服名）、conquerors（征服者：名字與基石）、keystones、notables、small（小天賦被換成的；沒有 = 不換）、
  attribute（屬性點多給的；沒有 = 不加）。每個節點的 lines：text（中文）、stats（引擎修飾器）、per（每多少貢禮算一次；0 = 固定）、
  active（false = 引擎還沒有這個效果，只顯示說明、標「尚未生效」）。

PoE2 版 PoB 沒有「種子 → 換成哪個核心」的官方查表（PoE1 的 LUT 檔），所以種子對應在 Java（TimelessJewels）用確定性的雜湊自己做。

用法：python3 tools/gen_timeless.py <LegionPassives.lua>
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_poe2_tree as g  # noqa: E402  （只用它的 convert、zh_line；不會跑它的 main）

OUT = os.path.join(HERE, '..', 'src', 'main', 'resources', 'data', 'passives', 'timeless.json')
INACTIVE = g.INACTIVE

# 台服名稱（poe2db.tw/tw）。Buried Animosity 台服頁面沒有列，自己翻。
NAMES = {
    'Black Scythe Training': '黑暗血鐮訓練', 'Circular Teachings': '循環教義', 'Knightly Tenets': '騎士信條',
    'Scorched Earth': '焦土', 'War Tactics': '戰爭策略', 'Kalguuran Forged': '卡爾葛鍛造', 'Born of Middengard': '密登加德之裔',
    'Force of Will': '意志之力', 'Runic Flows': '符文奔流', 'Siege Mentality': '圍攻心理', 'Rune Knight': '魔符騎士',
    'Fiery Leadership': '激情領導', 'Druidic Alliance': '德魯伊同盟', 'Steel and Sorcery': '堅鋼與巫術', "Triskelion's Light": '三曲之光',
    'Stormtossed Voyager': '暴風航行者', 'Wrest Control': '強奪控制', 'Firedancer': '火焰舞者', "Mercenary's Lot": '僱用兵之命',
    "Vorana's Fury": '沃拉娜之怒', 'Sensible Precautions': '明智預防', 'Aim for the Jugular': '瞄準要害', 'Survival Plan': '生存計畫',
    'War of Attrition': '消耗戰', 'Guerilla Warfare': '游擊戰', 'One for the Road': '餞行酒', 'Targeted Strike': '指定打擊',
    'Steel Bastion': '鋼鐵堡壘', 'Forceful Energies': '強力能量', 'Winter Forest': '寒冬之林', 'Fight as One': '並肩作戰',
    'Summer Meadows': '盛夏草原', 'Druidic Training': '德魯伊訓練', 'Corrupted Vision': '腐化視界', 'Runic Tattoos': '符文紋身',
    'Furs and Leather': '毛皮與皮革', 'Sudden Hail': '突降冰雹', 'Natural Energies': '自然能量', 'Oaken Form': '橡木形態',
    "Spider's Lesson": '毒蛛的教訓',
    'Sacrifice of Flesh': '血肉獻祭', 'Sacrifice of Loyalty': '忠誠獻祭', 'Sacrifice of Mind': '心靈獻祭', 'Sacrifice of Blood': '鮮血獻祭',
    'Sacrifice of Sight': '遠目獻祭', 'Tribute': '貢禮',
    'Disciple of Darkness': '黑暗門徒', 'Creature of Death': '死亡之獸', 'Creature of Magic': '魔法之獸', 'Quickened Corpses': '屍體激勵',
    'Ambivalent Command': '矛盾命令', 'Boneshedder': '骸骨削切', 'Unfiltered Brutality': '純粹殘暴', 'Derogatory Gaze': '貶損凝視',
    'Abyssal Conduit': '深淵連結', 'Expedited Horrors': '加深恐懼', 'Confined Exaltation': '侷限喜悅', 'Arcane Ascension': '秘術昇華',
    'Ravenous Mind': '貪婪思想', 'Gluttonous Presence': '暴食存在', 'Unrestrained Intellect': '無縛智慧', 'Cruel Strength': '殘忍力量',
    'Opportunistic Dexterity': '投機敏捷', 'Unreal Potential': '虛幻潛力', 'I Have Darkvision': '吾有黯視', 'Domineering Commands': '跋扈命令',
    'Worthy Tithes': '值得的稅負', 'Buried Animosity': '埋藏敵意', 'Fast Rot': '快速腐爛', 'Hollow Bones': '空洞骸骨',
    'Ancient Bastion': '遠古堅堡', 'Deformed Steel': '殘破堅鋼', 'Mystic Brew': '神秘精釀', 'Resilient Wards': '堅韌守護',
    'Dreadful Fortitude': '可敬的堅毅', 'Barbed Allegiance': '帶刺忠誠',
}

# 樣板沒有中文的說明行（台服 poe2db 的寫法；基石照台服原文）
TEXT_ZH = {
    'Gain no inherent bonus from Strength': '無法獲得力量的固有加成',
    'Gain no inherent bonus from Dexterity': '無法獲得敏捷的固有加成',
    'Gain no inherent bonus from Intelligence': '無法獲得智慧的固有加成',
    '1% increased Energy Shield per 2 Strength': '每 2 點力量增加 1% 能量護盾',
    '1% increased Armour per 2 Dexterity': '每 2 點敏捷增加 1% 護甲',
    '1% increased Evasion Rating per 2 Intelligence': '每 2 點智慧增加 1% 閃避值',
    '+2% to Maximum Fire Resistance': '+2% 最大火焰抗性',
    '10% increased Cold Exposure Effect': '增加 10% 冰冷曝露效果',
    '10% increased Fire Exposure Effect': '增加 10% 火焰曝露效果',
    '10% increased Lightning Exposure Effect': '增加 10% 閃電曝露效果',
    'When taking damage from Hits, 20% of Life Loss is prevented, then 150% of Life Loss prevented this way is Lost over 4 seconds':
        '當承受擊中傷害時，損失生命值的 20% 將會被避免，並在 4 秒內損失減免數值的 150%',
    'You deal 1% more Damage per 2 total Power of your Undead Minions': '你的不死召喚物每有 2 強度總值，你造成 1% 更多傷害',
    'Undead Minions have 25% less maximum Life': '不死召喚物具有 25% 更少最大生命',
    'Mana Recovery from Regeneration Overflows maximum Mana': '來自回復的魔力恢復會溢出最大魔力',
    '50% less Mana Regeneration Rate': '50% 更少魔力回復率',
    'Regenerate 1 Life per second per 16 Life spent in the past 4 seconds': '過去 4 秒內每消耗 16 生命即每秒回復 1 生命',
    '20% more Life Cost of Skills': '20% 更多技能生命消耗',
    'Projectiles do one of the following at random:': '投射物將會隨機進行下列其中一個行為：',
    'Fork an additional time': '・額外分裂一次',
    'Chain an additional time': '・額外連鎖一次',
    'Chain from Terrain an additional time': '・從地形額外連鎖一次',
    'Cannot collide with targets': '・無法與地形碰撞',
    '+5 to Tribute': '+5 貢禮',
    '+3 to Tribute': '+3 貢禮',
    '20% increased Tribute': '增加 20% 貢禮',
    'Minions lose 2% Life when following Commands': '召喚物聽從指令時損失 2% 生命',
    '1% of damage taken Recouped as Life': '承受的傷害有 1% 補償為生命',
    '1% of damage taken Recouped as Mana': '承受的傷害有 1% 補償為魔力',
    'Minions deal 1% increased damage': '增加 1% 召喚物的傷害',
    '5% increased Attack Critical Hit Chance': '增加 5% 攻擊暴擊率',
    '1% reduced Duration of Damaging Ailments': '減少 1% 傷害型異常狀態持續時間',
    'Projectiles have 10% chance for an additional Projectile when Forking': '投射物分裂時有 10% 機率多一個投射物',
    '1% increased damage taken': '增加 1% 受到的傷害',
    '3% faster Curse Activation': '詛咒生效速度加快 3%',
    '1% increased Spirit Reservation Efficiency of Skills': '增加 1% 技能的精魂保留效率',
    '2% reduced Presence Area of Effect': '減少 2% 臨在範圍',
    '1% of Maximum Life Converted to Energy Shield': '1% 最大生命轉換為能量護盾',
    '2% increased Mana Recovery rate': '增加 2% 魔力回復速度',
    '2% increased Life Recovery rate': '增加 2% 生命回復速度',
    '2% increased Parried Debuff Duration': '增加 2% 招架減益持續時間',
    '1% chance when you gain a Charge to gain an additional Charge': '獲得戰星時有 1% 機率多獲得一顆',
    '2% reduced Light Radius': '減少 2% 照亮範圍',
    'Empowered Attacks deal 2% increased damage': '增加 2% 強化攻擊的傷害',
    '4% increased Warcry Speed': '增加 4% 戰吼速度',
    'Remnants you create have 2% increased effect': '你產生的殘骸效果增加 2%',
    'Inherent loss of Rage is 2% slower': '怒氣自然流失減慢 2%',
    'Your Heavy Stun buildup empties 1% faster': '你的重度暈眩累積消退加快 1%',
    '5% increased Armour, Evasion and Energy Shield from Equipped Shield': '增加 5% 已裝備盾牌的護甲、閃避值與能量護盾',
    'Charms applied to you have 1% increased Effect': '增加 1% 你身上的護符效果',
    '+0.5% to Thorns Critical Hit Chance': '+0.5% 荊棘暴擊率',
    'Minions have 4% increased Cooldown Recovery Rate': '增加 4% 召喚物的冷卻回復速度',
    'Minions have 3% increased Attack and Cast Speed': '增加 3% 召喚物的攻擊與施放速度',
}

# stat_map 沒有、或要換成更準的屬性鍵（值是 PoE2 的原始數字，換算照 gen_poe2_tree.scale）
EXTRA = {
    'base_maximum_fire_damage_resistance_%': [('resist.max.fire', 'FLAT', 1, [])],
    'base_maximum_cold_damage_resistance_%': [('resist.max.cold', 'FLAT', 1, [])],
    'base_maximum_lightning_damage_resistance_%': [('resist.max.lightning', 'FLAT', 1, [])],
    'base_self_freeze_duration_-%': [('ailment.self_duration.freeze', 'INCREASED', -1, [])],
    'base_self_ignite_duration_-%': [('ailment.self_duration.ignite', 'INCREASED', -1, [])],
    'base_self_shock_duration_-%': [('ailment.self_duration.shock', 'INCREASED', -1, [])],
    'recover_%_maximum_mana_on_kill': [('mana.on_kill', 'FLAT', 1, [])],
    'curse_duration_+%': None,  # 換成 base_curse_duration_+%（見 ALIAS）
    # 2026-10-26 照 PoE2：這次之前這些都標「尚未生效」。屬性鍵的效果在 Java（ConquestEffects 的說明）
    'arcane_surge_effect_on_self_+%': [('arcane_surge.effect', 'INCREASED', 1, [])],
    'cold_exposure_effect_+%': [('exposure.effect.cold', 'INCREASED', 1, [])],
    'fire_exposure_effect_+%': [('exposure.effect.fire', 'INCREASED', 1, [])],
    'lightning_exposure_effect_+%': [('exposure.effect.lightning', 'INCREASED', 1, [])],
    'shield_armour_evasion_energy_shield_+%': [('shield.defences', 'INCREASED', 1, [])],
    'minion_cooldown_recovery_+%': [('cooldown.recovery', 'INCREASED', 1, ['minion'])],
    'minions_lose_%_life_when_following_commands': [('minion.command_life_loss', 'FLAT', 1, ['minion'])],
    'chance_to_fork_extra_projectile_%': [('skill.fork_extra_chance', 'FLAT', 1, [])],
    'base_damage_taken_+%': [('debuff.taken.' + t, 'FLAT', 1, []) for t in ('physical', 'fire', 'cold', 'lightning', 'chaos')],
    'curse_delay_+%': [('curse.delay', 'INCREASED', -1, [])],  # 「加快 x%」= 延遲減少 x%（SkillExecutor 照天賦樹的寫法）
    'base_spirit_reservation_efficiency_+%': [('aura.reservation', 'INCREASED', -1, [])],
    'maximum_life_%_to_convert_to_maximum_energy_shield': [('life.convert_es', 'FLAT', 1, [])],
    'mana_recovery_rate_+%': [('mana.regen', 'INCREASED', 1, [])],
    'life_recovery_rate_+%': [('life.recovery_rate', 'INCREASED', 1, [])],
    'chance_to_gain_1_more_charge_%': [('charge.extra', 'FLAT', 1, [])],
    'light_radius_+%': [],  # Minecraft 沒有照亮範圍；原作的照亮範圍也只影響畫面，沒有別的效果
    'warcry_speed_+%': [('warcry.speed', 'INCREASED', 1, [])],
    'remnant_effect_+%': [('remnant.effect', 'INCREASED', 1, [])],
    'rage_decay_speed_+%': [('rage.decay', 'INCREASED', -1, [])],  # 「流失減慢 x%」= 流失速度減少 x%
    'charm_effect_+%': [('charm.effect', 'INCREASED', 1, [])],
    'heavy_stun_poise_decay_rate_+%': [('stun.decay_rate', 'INCREASED', 1, [])],
    'additional_thorns_critical_strike_chance_permyriad': [('thorns.crit', 'FLAT', 1, [])],
}
# 2026-10-26 照 PoE2：深淵族的五個基石（效果在 Java：ConquestEffects、CombatListener、ProjectileEngine…）；
# 每一行說明配的修飾器（這次之前只有卡爾葛的三個基石生效，深淵族的全部標「尚未生效」）
KEYSTONE_LINES = {
    'keystone_alternate_strength_bonus': [[('flag.timeless_alt_str', 'FLAT', 1, [])], []],
    'keystone_alternate_dexterity_bonus': [[('flag.timeless_alt_dex', 'FLAT', 1, [])], []],
    'keystone_alternate_intelligence_bonus': [[('flag.timeless_alt_int', 'FLAT', 1, [])], []],
    'keystone_amanamus_defiance': [[('flag.sacrifice_flesh', 'FLAT', 1, [])]],
    'keystone_kulemaks_sovereignty': [[('flag.sacrifice_loyalty', 'FLAT', 1, [])], [('life.max', 'MORE', -0.25, ['minion', 'undead'])]],
    'keystone_kurgals_ambition': [[('flag.sacrifice_mind', 'FLAT', 1, [])], [('mana.regen', 'MORE', -0.5, [])]],
    'keystone_tecrods_brutality': [[('flag.sacrifice_blood', 'FLAT', 1, [])], [('skill.life_cost', 'MORE', 0.2, [])]],
    'keystone_ulamans_vision': [[('flag.sacrifice_sight', 'FLAT', 1, [])], [], [], [], []],
}
ALIAS = {'curse_duration_+%': 'base_curse_duration_+%'}
PER = re.compile(r'_per_(\d+)_tribute$')
PER_TEXT = re.compile(r' per (\d+) Tribute(?: you have)?')


def convert(stat_id, value):
    stat_id = ALIAS.get(stat_id, stat_id)
    if stat_id in EXTRA and EXTRA[stat_id] is not None:
        return [dict(stat=s, type=t, value=g.scale(t, value * k), tags=tags) for s, t, k, tags in EXTRA[stat_id]]
    if stat_id in EXTRA and EXTRA[stat_id] == []:
        return []
    return g.convert(stat_id, value)


def zh(text):
    if text in TEXT_ZH:
        return TEXT_ZH[text]
    out = g.zh_line(text)
    return out


def node(raw):
    """LegionPassives 的一個節點 → {id, name, en, lines}；每一行說明配它的 stat（照 sortedStats 的順序與說明行一一對應）。"""
    lines = []
    sd = list(raw['sd'])
    stats = list(raw.get('sortedStats') or raw['stats'].keys())
    keystone = raw.get('ks')
    if keystone:
        # 基石：說明照台服原文，效果在 Java（開關＋各行直接給的修飾器）
        per_line = KEYSTONE_LINES.get(stats[0])
        for i, text in enumerate(sd):
            mods = per_line[i] if per_line is not None and i < len(per_line) else None
            lines.append(dict(text=zh(text) + ('' if per_line is not None else INACTIVE),
                              stats=[dict(stat=st, type=t, value=v, tags=tags) for st, t, v, tags in (mods or [])],
                              per=0, active=per_line is not None))
        return dict(id=raw['id'], name=NAMES.get(raw['dn'], raw['dn']), en=raw['dn'], lines=lines)
    # 一般節點：說明行與 stat 照順序對（PoE2 一行一個 stat；三曲之光是 4 行 4 個 stat）
    by_text = {}
    for stat_id in stats:
        by_text[stat_id] = raw['stats'][stat_id]
    pairs = match_lines(sd, stats, raw['stats'])
    for text, stat_id in pairs:
        value = raw['stats'][stat_id]['min']
        per = 0
        base_id = stat_id
        base_text = text
        m = PER.search(stat_id)
        if m:
            per = int(m.group(1))
            base_id = stat_id[:m.start()]
            base_text = PER_TEXT.sub('', text)
            if 'reduced' in text.split():
                value = -value  # 「減少」的 per-tribute 行：PoE2 的 stat 值是正的、說明寫 reduced
        if base_id == 'tribute_+%':
            # 黑暗門徒「增加 20% 貢禮」：Java 算貢禮的時候讀 tribute_increase
            lines.append(dict(text=zh(base_text), stats=[], per=0, active=True, tribute_increase=value))
            continue
        mods = convert(base_id, value)
        active = mods is not None
        shown = zh(base_text)
        if per:
            shown += '（每 %d 點貢禮）' % per
        if base_id == 'light_radius_+%':
            shown += '（原作只影響畫面；這裡沒有照亮範圍，沒有效果）'
        lines.append(dict(text=shown + ('' if active else INACTIVE), stats=mods or [], per=per, active=active))
    return dict(id=raw['id'], name=NAMES.get(raw['dn'], raw['dn']), en=raw['dn'], lines=lines)


def match_lines(sd, stats, table):
    """說明行 ↔ stat：PoE2 的 sd 與 sortedStats 不一定同順序，用數字對（每行的第一個數字 = 那個 stat 的值）。"""
    remaining = list(stats)
    out = []
    for text in sd:
        numbers = [float(x) for x in g.NUMBER.findall(text)]
        hit = None
        for stat_id in remaining:
            v = abs(table[stat_id]['min'])
            if numbers and any(abs(n - v) < 1e-9 for n in numbers):
                hit = stat_id
                break
        if hit is None:
            hit = remaining[0]
        remaining.remove(hit)
        out.append((text, hit))
    return out


def main():
    src = sys.argv[1]
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    data = lua_to_json(open(src, encoding='utf-8').read())
    nodes = {n['id']: n for n in data['nodes']}
    additions = {a['id']: a for a in data['additions']}
    kalguur = dict(
        name='卡爾葛', unique='heroic_tragedy',
        conquerors=[dict(id='vorana', name='沃拉娜', keystone='kalguur_keystone_1'),
                    dict(id='medved', name='梅德偉', keystone='kalguur_keystone_2'),
                    dict(id='olroth', name='奧爾羅斯', keystone='kalguur_keystone_3')],
        keystones=[node(nodes['kalguur_keystone_%d' % i]) for i in range(1, 4)],
        notables=[node(nodes['kalguur_notable%d' % i]) for i in range(1, 38)],
        small=None, attribute=None)
    abyss = dict(
        name='深淵族', unique='undying_hate',
        conquerors=[dict(id='amanamu', name='阿姆納姆', keystone='abyss_keystone_1'),
                    dict(id='kulemak', name='骷髏馬克', keystone='abyss_keystone_2'),
                    dict(id='kurgal', name='柯戈', keystone='abyss_keystone_3'),
                    dict(id='tecrod', name='特克羅德', keystone='abyss_keystone_4'),
                    dict(id='ulaman', name='烏拉曼', keystone='abyss_keystone_5')],
        keystones=[node(nodes['abyss_keystone_%d' % i]) for i in range(1, 6)],
        notables=[node(nodes['abyss_notable_%d' % i]) for i in range(1, 31)],
        small=tribute(nodes['abyss_small_tribute'], 5), attribute=tribute(additions['abyss_attribute_tribute'], 3))
    out = dict(source='原作的 Path of Building 2：TimelessJewelData/LegionPassives.lua（kalguur_*、abyss_*）', radius=1500,
               legions=dict(kalguur=kalguur, abyss=abyss))
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print('wrote', OUT)


def tribute(raw, amount):
    """「+N 貢禮」：貢禮本身不是屬性，只給深淵族核心的「每 N 點貢禮」算（tribute 欄）。"""
    return dict(id=raw['id'], name='貢禮', en='Tribute', tribute=amount,
                lines=[dict(text='+%d 貢禮' % amount, stats=[], per=0, active=True)])


def lua_to_json(src):
    body = src[src.index('return') + 6:]
    body = re.sub(r'--[^\n]*', '', body)
    body = re.sub(r'\["([^"]*)"\]\s*=', r'"\1":', body)
    body = re.sub(r'\[(\d+)\]\s*=', r'"\1":', body)
    toks = re.findall(r'"(?:\\.|[^"\\])*"|[{}:,]|[^\s{}:,"]+', body)

    def parse(i):
        t = toks[i]
        if t == '{':
            i += 1
            items, d, is_dict = [], {}, False
            while toks[i] != '}':
                if toks[i + 1] == ':':
                    k = json.loads(toks[i])
                    v, i = parse(i + 2)
                    d[k] = v
                    is_dict = True
                else:
                    v, i = parse(i)
                    items.append(v)
                if toks[i] == ',':
                    i += 1
            return (d if is_dict else items), i + 1
        if t.startswith('"'):
            return json.loads(t), i + 1
        if t in ('true', 'false'):
            return t == 'true', i + 1
        try:
            return (float(t) if '.' in t else int(t)), i + 1
        except ValueError:
            return t, i + 1

    value, _ = parse(0)
    return value


if __name__ == '__main__':
    main()
