#!/usr/bin/env python3
"""2026-10-26：照 PoE2 的法杖、長杖、權杖基底（poe2db 的 Wands／Staves／Sceptres）寫 src/main/resources/data/bases/weapons_casters.yml。

PoE2 的這三種武器沒有自己的傷害：拿著的時候賦予一個技能（Grants Skill，寫在 grants，見 ItemRepository.grantedSkill），
等級照物品等級（寶石的需求等級表倒推）。權杖另外給 +100 精魂。名稱是自己取的。
跑：python3 tools/gen_poe2_casters.py
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'src/main/resources/data/bases/weapons_casters.yml'

# (代號, 名稱, PoE2 名稱, 等級, 力量, 智慧, 賦予的技能)
WANDS = [
    ('cwand_01', '枯枝法杖', 'Withered Wand', 1, 0, 0, 'chaos_bolt'),
    ('cwand_02', '骸骨法杖', 'Bone Wand', 2, 0, 0, 'bone_blast'),
    ('cwand_03', '調律法杖', 'Attuned Wand', 2, 0, 0, 'mana_drain'),
    ('cwand_04', '虹吸法杖', 'Siphoning Wand', 11, 0, 23, 'power_siphon'),
    ('cwand_05', '炎屍法杖', 'Volatile Wand', 16, 0, 31, 'volatile_dead'),
    ('cwand_06', '電流法杖', 'Galvanic Wand', 25, 0, 46, 'galvanic_field'),
    ('cwand_07', '腐蝕法杖', 'Acrid Wand', 33, 0, 60, 'decompose'),
    ('cwand_08', '血祭法杖', 'Offering Wand', 38, 0, 68, 'exsanguinate'),
    ('cwand_09', '霜蝕法杖', 'Frigid Wand', 45, 0, 80, 'chaos_bolt'),
    ('cwand_10', '刑苦法杖', 'Torture Wand', 49, 0, 87, 'chaos_bolt'),
    ('cwand_11', '銳芒法杖', 'Critical Wand', 52, 0, 92, 'chaos_bolt'),
    ('cwand_12', '太古法杖', 'Primordial Wand', 56, 0, 99, 'wither'),
    ('cwand_13', '纏蝕法杖', 'Twisted Wand', 65, 0, 114, 'coiling_bolts'),
    ('cwand_14', '決鬥法杖', 'Dueling Wand', 65, 0, 114, 'spellslinger'),
]
STAVES = [
    ('cstaff_01', '灰燼長杖', 'Ashen Staff', 1, 0, 0, 'firebolt'),
    ('cstaff_02', '凝冰長杖', 'Gelid Staff', 2, 0, 0, 'freezing_shards'),
    ('cstaff_03', '雷鳴長杖', 'Voltaic Staff', 2, 0, 0, 'lightning_bolt'),
    ('cstaff_04', '精靈長杖', 'Spriggan Staff', 11, 0, 23, 'firebolt'),
    ('cstaff_05', '炎木長杖', 'Pyrophyte Staff', 16, 0, 31, 'sun_orb'),
    ('cstaff_06', '鳴鐘長杖', 'Chiming Staff', 25, 0, 46, 'sigil_of_power'),
    ('cstaff_07', '裂魂長杖', 'Rending Staff', 33, 0, 60, 'soulrend'),
    ('cstaff_08', '血鐮長杖', 'Reaping Staff', 38, 0, 68, 'reap'),
    ('cstaff_09', '冰錐長杖', 'Icicle Staff', 45, 0, 80, 'firebolt'),
    ('cstaff_10', '麻痺長杖', 'Paralysing Staff', 52, 0, 92, 'enervating_nova'),
    ('cstaff_11', '聖紋長杖', 'Sanctified Staff', 56, 0, 99, 'consecrate'),
    ('cstaff_12', '冥暗長杖', 'Dark Staff', 65, 0, 114, 'dark_pact'),
    ('cstaff_13', '饑渴長杖', 'Ravenous Staff', 65, 0, 114, 'feast_of_flesh'),
    ('cstaff_14', '永凍長杖', 'Permafrost Staff', 75, 0, 131, 'heart_of_ice'),
    ('cstaff_15', '咆哮長杖', 'Roaring Staff', 49, 0, 87, 'unleash_spell'),
    ('cstaff_16', '棲鴉長杖', 'Perching Staff', 65, 0, 114, 'spiraling_conspiracy'),
    ('cstaff_17', '映鏡長杖', 'Reflecting Staff', 70, 0, 122, 'mirror_of_refraction'),
]
SCEPTRES = [
    ('csceptre_01', '骸鈴權杖', 'Rattling Sceptre', 1, 0, 0, 'summon_skeletons'),
    ('csceptre_02', '堅忍權杖', 'Stoic Sceptre', 6, 0, 12, 'discipline'),
    ('csceptre_03', '狼牙權杖', 'Lupine Sceptre', 12, 11, 24, 'summon_skeletons'),
    ('csceptre_04', '凶兆權杖', 'Omen Sceptre', 16, 12, 25, 'malice'),
    ('csceptre_05', '赭土權杖', 'Ochre Sceptre', 21, 19, 40, 'summon_skeletons'),
    ('csceptre_06', '焰龕權杖', 'Shrine Sceptre（Purity of Fire）', 26, 17, 38, 'purity_of_fire'),
    ('csceptre_07', '霜龕權杖', 'Shrine Sceptre（Purity of Ice）', 26, 17, 38, 'purity_of_ice'),
    ('csceptre_08', '雷龕權杖', 'Shrine Sceptre（Purity of Lightning）', 26, 17, 38, 'purity_of_lightning'),
    ('csceptre_09', '蝕龕權杖', 'Shrine Sceptre（Impurity）', 26, 17, 38, 'impurity'),
    ('csceptre_10', '吞噬權杖', 'Devouring Sceptre', 33, 28, 60, 'summon_skeletons'),
    ('csceptre_11', '寒心權杖', 'Clasped Sceptre', 36, 30, 65, 'heart_of_ice'),
    ('csceptre_12', '虔誠權杖', 'Devotional Sceptre', 45, 26, 63, 'summon_skeletons'),
    ('csceptre_13', '芳香權杖', 'Aromatic Sceptre', 52, 43, 92, 'summon_skeletons'),
    ('csceptre_14', '聖徒權杖', 'Pious Sceptre', 58, 48, 102, 'summon_skeletons'),
    ('csceptre_15', '聖化權杖', 'Hallowed Sceptre', 65, 54, 114, 'summon_skeletons'),
    ('csceptre_16', '怒雷權杖', 'Wrath Sceptre', 49, 41, 87, 'fulmination'),
]
KINDS = [
    ('wand', '法杖', WANDS, 'BLAZE_ROD', 'exilecore:weapon/wand', '[weapon, one_hand, wand, ranged, caster]', 3, None),
    ('staff', '長杖', STAVES, 'BREEZE_ROD', 'exilecore:weapon/staff', '[weapon, two_hand, staff, ranged, caster]', 6, None),
    ('sceptre', '權杖', SCEPTRES, 'GOLDEN_HOE', 'exilecore:weapon/sceptre', '[weapon, one_hand, sceptre, caster, melee]', 3,
     ['  implicit:', '    text: "+{value} 精魂"', '    value: [100, 100]', '    mods:', '      - { stat: spirit.max, type: FLAT, value: "{value}" }']),
]


def main():
    out = ['# 2026-10-26：照 PoE2 的法杖、長杖、權杖（tools/gen_poe2_casters.py 產生，別手改）。',
           '# 沒有自己的傷害：拿著時賦予 grants 的技能（技能面板上多一格，等級照物品等級；拿掉武器就不能用）。',
           '# 拿著法杖、長杖時沒綁技能的鍵放賦予的法術（PoE2 的法杖、長杖沒有普攻）。權杖另外給 +100 精魂。',
           '# 舊的 wand_14、staff_17、sceptre_16（還有合併進去的那些）退役，合併到這裡的第 1 把（舊物品照常能用、賦予它的技能）。']
    for family, cname, rows, material, model, tags, sockets, implicit in KINDS:
        out.append('')
        out.append('# ---- %s ----' % cname)
        for bid, name, poe, level, s, i, grant in rows:
            out.append('')
            out.append('%s:   # PoE2 %s' % (bid, poe))
            out.append('  name: %s' % name)
            out.append('  fixed: true')
            out.append('  family: %s' % family)
            out.append('  material: %s' % material)
            out.append('  model: %s' % model)
            out.append('  slot: MAIN_HAND')
            out.append('  tags: %s' % tags)
            out.append('  drop_level: %d' % level)
            out.append('  requirements: { level: %d, str: %d, dex: 0, int: %d }' % (level, s, i))
            out.append('  aps: 1.2          # 沒有傷害，只是名義上的攻速')
            out.append('  crit: 5.0')
            out.append('  sockets: %d' % sockets)
            out.append('  grants: %s' % grant)
            if implicit:
                out.extend(implicit)
    OUT.write_text('\n'.join(out) + '\n', encoding='utf-8')
    print('寫了', OUT, sum(len(k[2]) for k in KINDS), '個基底')


if __name__ == '__main__':
    main()
