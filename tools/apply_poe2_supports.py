#!/usr/bin/env python3
"""2026-10-26：把效果跟 PoE2 一樣的輔助改成 poe2db 的數值（tools/poe2_supports/*.tsv 是抓下來的原文）。
SPEC：我們的代號 → (PoE2 名稱, PoE2 階級, 魔耗倍率或 None（精魂輔助）, modifiers（None = 不動）)。
階級換算：PoE2 第 1 階 → 1、第 2–3 階 → 2、第 4–5 階 → 3（沒寫階級的不動）。
每一塊改完在 modifiers 上面留一行註解寫原作名稱與這次之前的值。可以重跑（已經改過的會照 SPEC 再寫一次）。"""
import re
from pathlib import Path

F = Path(__file__).resolve().parent.parent / 'src/main/resources/data/supports.yml'

def m(stat, typ, value, tags=None):
    t = ', tags: [%s]' % ', '.join(tags) if tags else ''
    return '{ stat: %s, type: %s, value: %s%s }' % (stat, typ, value, t)

def tier(p):
    return None if p is None else (1 if p == 1 else 2 if p <= 3 else 3)

SPEC = {
    'increased_area': ('Magnified Area I', 1, 1.3, [m('skill.area', 'INCREASED', 35)]),
    'faster_projectiles': ('Projectile Acceleration I', 1, 1.2, [m('skill.projectile_speed', 'MORE', 40)]),
    'pierce': ('Pierce I', 1, 1.0, [m('skill.pierce', 'FLAT', 1)]),
    'faster_attacks': ('Rapid Attacks I', 1, 1.0, [m('attack.speed', 'INCREASED', 15)]),
    'faster_casting': ('Rapid Casting I', 1, 1.0, [m('cast.speed', 'INCREASED', 15)]),
    'increased_duration': ('Prolonged Duration I', 1, 1.2, [m('skill.duration', 'MORE', 30)]),
    'prolonged_duration': ('Prolonged Duration II', 3, 1.2, [m('skill.duration', 'MORE', 35)]),
    'persistent_ground': ('Persistent Ground I', 2, 1.0, None),
    'concentrated_effect': ('Concentrated Area', 1, 1.0, [m('skill.area', 'MORE', -50), m('damage', 'MORE', 30, ['area'])]),
    'chain': ('Chain I', 1, 1.0, [m('skill.chains', 'FLAT', 1), m('damage', 'MORE', -30)]),
    'life_leech': ('Life Leech I', 2, 1.0, [m('leech.life', 'FLAT', 12)]),
    'mana_leech': ('Mana Leech', 2, 1.0, [m('leech.mana', 'FLAT', 8)]),
    'scouring_flame': ('Scouring Flame', None, 1.0, None),
    'first_blood': ('Bleed I', 1, 1.2, [m('bleed.chance', 'FLAT', 50)]),
    'deep_cuts': ('Deep Cuts I', 3, 1.0, [m('damage', 'MORE', -25), m('bleed.magnitude', 'MORE', 75)]),
    'virulence': ('Deadly Poison I', 2, 1.0, [m('damage', 'MORE', -25), m('poison.magnitude', 'MORE', 75)]),
    'concussive': ('Stun I', 2, 1.2, [m('stun.buildup', 'MORE', 50)]),
    'glacial': ('Freeze', 2, 1.2, [m('freeze.buildup', 'MORE', 50)]),
    'static_charge': ('Shock', 1, 1.2, [m('shock.chance', 'MORE', 100)]),
    'cooldown_recovery': ('Cooldown Recovery I', 3, 1.3, [m('skill.cooldown', 'INCREASED', -20)]),
    'swift_cooldown': ('Cooldown Recovery II', 4, 1.3, [m('skill.cooldown', 'INCREASED', -23)]),
    'fork': ('Fork', 3, 1.1, None),
    'spell_echo': ('Spell Echo', 2, 1.5, [m('flag.echo', 'FLAT', 1), m('cast.speed', 'MORE', -30)]),
    'multiple_projectiles': ('Multishot II', 2, 1.0, [m('skill.projectiles', 'FLAT', 2), m('damage', 'MORE', -25),
                                                      m('attack.speed', 'MORE', -20), m('cast.speed', 'MORE', -20)]),
    'scattershot': ('Multishot I', 1, 1.0, [m('skill.projectiles', 'FLAT', 2), m('damage', 'MORE', -35),
                                            m('attack.speed', 'MORE', -20), m('cast.speed', 'MORE', -20)]),
    'elemental_focus': ('Elemental Focus', 3, 1.0, [m('damage', 'MORE', 25, ['elemental']), m('flag.no_elemental_ailments', 'FLAT', 1)]),
    'brutality': ('Brutality I', 1, 1.2, [m('damage', 'MORE', 25, ['physical']), m('damage', 'MORE', -100, ['elemental']),
                                          m('damage', 'MORE', -100, ['chaos'])]),
    'rage': ('Rage I', 1, 1.0, [m('rage.on_hit', 'FLAT', 3)]),
    'considered_casting': ('Considered Casting', 2, 1.15, [m('cast.speed', 'MORE', -15), m('damage', 'MORE', 35, ['spell'])]),
    'overcharge': ('Overcharge', 4, 1.0, [m('shock.effect', 'INCREASED', 50)]),
    'heavy_swing': ('Heavy Swing', 4, 1.0, [m('damage', 'MORE', 35, ['physical']), m('attack.speed', 'MORE', -10)]),
    'close_quarters': ('Close Combat I', 3, 1.2, [m('damage.close', 'MORE', 20)]),
    'elemental_army': ('Elemental Army', 1, 1.0, [m('resist.fire', 'FLAT', 30, ['minion']), m('resist.cold', 'FLAT', 30, ['minion']),
                                                 m('resist.lightning', 'FLAT', 30, ['minion'])]),
    'controlled_destruction': ('Controlled Destruction', 1, 1.0, [m('damage', 'MORE', 25), m('crit.chance', 'MORE', -100)]),
    'sturdy_totem': ('Hardy Totems I', 2, 1.1, [m('totem.life', 'MORE', 40)]),
    'execute': ('Execute I', 1, 1.1, [m('damage.vs_low_life', 'FLAT', 40)]),
    'fire_mastery': ('Fire Mastery', 5, 1.0, None),
    'cold_mastery': ('Cold Mastery', 5, 1.0, None),
    'lightning_mastery': ('Lightning Mastery', 5, 1.0, None),
    'chaos_mastery': ('Chaos Mastery', 5, 1.0, None),
    'physical_mastery': ('Physical Mastery', 5, 1.0, None),
    'minion_mastery': ('Minion Mastery', 5, 1.0, None),
    'deliberation': ('Deliberation', 3, 1.0, [m('damage', 'MORE', 20)]),
    'overreach': ('Overreach', 2, 1.0, None),
    'bloodlust': ('Bloodlust', None, 1.0, None),
    'deep_freeze': ('Deep Freeze', 1, 1.2, None),
    'lasting_shock': ('Lasting Shock', 2, 1.0, None),
    'expanse': ('Expanse', 1, 1.0, [m('skill.area', 'INCREASED', 50), m('skill.added_cooldown', 'FLAT', 8)]),
    'excise': ('Excise', 2, 1.0, None),
    'execrate': ('Execrate', 3, 1.0, None),
    'hourglass': ('Hourglass', 1, 1.0, None),
    'heightened_curse': ('Heightened Curse', 1, 1.4, None),
    'ritualistic_curse': ('Ritualistic Curse', 3, 1.0, None),
    'ambush': ('Ambush', 1, 1.0, None),
    'zenith': ('Zenith I', 1, 1.2, None),
    'clash': ('Clash', 3, 1.2, None),
    'elemental_armament': ('Elemental Armament I', 1, 1.2, None),
    'knockback': ('Knockback', 3, 1.0, None),
    'overextend': ('Overextend', None, 1.15, None),
    'clarity': ('Clarity I', 1, None, [m('support.spirit', 'FLAT', 10), m('grant.mana.regen', 'INCREASED', 30)]),
    'vitality': ('Vitality I', 1, None, None),
    'cannibalism': ('Cannibalism I', 3, None, None),
    'thornskin': ('Thornskin I', 2, None, [m('support.spirit', 'FLAT', 20), m('grant.thorns.damage', 'INCREASED', 40)]),
    'cool_headed': ('Cool Headed', 3, None, [m('support.spirit', 'FLAT', 15), m('grant.ailment.self_duration.ignite', 'INCREASED', -50)]),
    'strong_hearted': ('Strong Hearted', 3, None, [m('support.spirit', 'FLAT', 15), m('grant.ailment.self_duration.shock', 'INCREASED', -50)]),
    'warm_blooded': ('Warm Blooded', 3, None, [m('support.spirit', 'FLAT', 15), m('grant.ailment.self_duration.freeze', 'INCREASED', -50)]),
    'herbalism': ('Herbalism I', 2, None, None),
    'precision': ('Precision I', 1, None, [m('support.spirit', 'FLAT', 10), m('grant.accuracy', 'INCREASED', 30)]),
    'mysticism': ('Mysticism I', 2, None, [m('support.spirit', 'FLAT', 15), m('grant.damage', 'INCREASED', 30, ['spell'])]),
    'upwelling': ('Upwelling I', 2, None, [m('support.spirit', 'FLAT', 15), m('grant.damage', 'INCREASED', 30, ['minion'])]),
    'deadly_herald': ('Deadly Herald', 3, None, [m('support.spirit', 'FLAT', 20), m('damage', 'MORE', 30)]),
    'armour_demolisher': ('Armour Demolisher I', 1, 1.0, None),
    'eternal_flame': ('Eternal Flame I', 1, 1.2, None),
    'extraction': ('Extraction', 3, 1.0, None),
    'projectile_deceleration': ('Projectile Deceleration I', 1, 1.0, None),
    'compressed_duration': ('Compressed Duration I', 1, 1.0, None),
    'life_drain': ('Life Drain', 1, 1.0, None),
    'soul_drain': ('Soul Drain', 3, 1.0, None),
    'cold_attunement': ('Cold Attunement', 1, 1.0, [m('damage.gain.cold', 'FLAT', 25), m('damage', 'MORE', -50, ['fire']),
                                                   m('damage', 'MORE', -50, ['lightning'])]),
    'lightning_attunement': ('Lightning Attunement', 1, 1.0, [m('damage.gain.lightning', 'FLAT', 25), m('damage', 'MORE', -50, ['fire']),
                                                             m('damage', 'MORE', -50, ['cold'])]),
    'pinpoint_critical': ('Pinpoint Critical', 2, 1.0, [m('crit.chance', 'MORE', 60), m('crit.bonus', 'MORE', -30)]),
    'potent_exposure': ('Potent Exposure', 2, 1.0, [m('exposure.effect', 'INCREASED', 20)]),
    'long_fuse': ('Long Fuse I', 4, 1.0, None),
    'short_fuse': ('Short Fuse I', 2, 1.15, [m('grenade.fuse', 'MORE', -40)]),
    'adhesive_grenades': ('Adhesive Grenades I', 2, 1.0, None),
    'ammo_conservation': ('Ammo Conservation I', 2, 1.0, [m('crossbow.ammo_save', 'FLAT', 20)]),
    'double_barrel': ('Double Barrel I', 1, 1.1, [m('crossbow.clip', 'FLAT', 1), m('crossbow.reload_speed', 'MORE', -30)]),
    'streamlined_rounds': ('Streamlined Rounds', 3, 1.2, [m('crossbow.clip', 'FLAT', 5), m('attack.speed', 'MORE', 50), m('damage', 'MORE', -60),
                                                         m('crossbow.reload_speed', 'MORE', -50)]),
    'nimble_reload': ('Nimble Reload', 4, 1.0, [m('crossbow.reload_speed', 'INCREASED', 60)]),
    'auto_reload': ('Auto Reload', None, 1.0, None),
    'overabundance': ('Overabundance I', 1, 1.0, None),
    'bounty': ('Bounty I', 2, 1.0, None),
    'life_bounty': ('Life Bounty', None, 1.0, None),
    'mana_bounty': ('Mana Bounty', None, 1.0, None),
    'lifetap': ('Lifetap', 2, 1.0, None),
    'bidding': ('Bidding I', 2, 1.3, None),
    'essence_harvest': ('Essence Harvest', 3, 1.0, None),
    'deathmarch': ('Deathmarch', 2, 1.0, None),
    'murderous_intent': ('Murderous Intent', None, 1.2, None),
}

def main():
    text = F.read_text(encoding='utf-8')
    done, missing = [], []
    for sid, (poe, ptier, mm, mods) in SPEC.items():
        mt = re.search(r'\n  %s:[^\n]*\n((?:    [^\n]*\n|      [^\n]*\n)+)' % re.escape(sid), text)
        if not mt:
            missing.append(sid)
            continue
        block = mt.group(1)
        old_mods = re.findall(r'^      - (\{[^\n]*\})', block, re.M)
        old_mm = re.search(r'^    mana_multiplier: ([^\s#]+)', block, re.M)
        old_tier = re.search(r'^    tier: (\d+)', block, re.M)
        new = block
        new = re.sub(r'^    # 2026-10-26 照 PoE2[^\n]*\n', '', new, flags=re.M)
        t = tier(ptier)
        if t is not None and old_tier:
            new = re.sub(r'^    tier: \d+[^\n]*', '    tier: %d' % t, new, count=1, flags=re.M)
        if mm is not None:
            if old_mm:
                new = re.sub(r'^    mana_multiplier: [^\n]*', '    mana_multiplier: %s' % mm, new, count=1, flags=re.M)
            else:
                new = re.sub(r'^(    modifiers:)', '    mana_multiplier: %s\n\\1' % mm, new, count=1, flags=re.M)
        if mods is not None:
            new = re.sub(r'^      - \{[^\n]*\n', '', new, flags=re.M)
            new = new.replace('    modifiers:\n', '    modifiers:\n' + ''.join('      - %s\n' % x for x in mods), 1)
        was = 'tier %s、魔耗 %s、%s' % (old_tier.group(1) if old_tier else '-', old_mm.group(1) if old_mm else '-',
                                    '；'.join(x.strip('{} ') for x in old_mods) if mods is not None else '效果不動')
        note = '    # 2026-10-26 照 PoE2 %s（poe2db）；這次之前 %s\n' % (poe, was)
        new = new.replace('    modifiers:\n', note + '    modifiers:\n', 1)
        text = text[:mt.start(1)] + new + text[mt.end(1):]
        done.append(sid)
    F.write_text(text, encoding='utf-8')
    print('改了', len(done), '沒找到', missing)

if __name__ == '__main__':
    main()
