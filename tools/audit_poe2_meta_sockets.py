#!/usr/bin/env python3
"""2026-10-28：主要寶石能插哪些技能（skill/MetaGems.castProblem、MetaSocketRules、TotemRules）跟 PoE2 0.5 對照。

用法：python3 tools/audit_poe2_meta_sockets.py [RePoE poe2/data 目錄]

原作的主要寶石只看輔助那一面（RePoE skills.json 的 support_gem）的 allowed_types／excluded_types（逆波蘭式；excluded 任一項成立就不行）：
  暴擊、異變、翻滾、殞從引星，元祈、刈祈、障祈引星，咒擲，擊中時火焰法術  Spell AND Triggerable（擊中時火焰法術再 AND Fire）
                                                                     excluded InbuiltTrigger（殞從再加 CreatesMinion；咒擲再加 Persistent、UsedByProxy）
  星咒圖騰                                                            Spell AND Totemable；excluded Meta、HasUsageCondition、Cooldown、Triggered、Persistent、
                                                                     UsedByProxy AND NOT SupportedBySpellTotem
我們的技能 → PoE2 寶石照 tools/audit_poe2_quality.py 的對照（跟 audit_poe2_cascadable.py 一樣），技能類型看寶石賦予的第一個技能。
我們這邊的判斷照 Java 重算（MetaSocketRules 的清單＋形狀、引擎擋掉的機制、TotemRules 的清單、grantedOnly）；
MetaSocketRulesTest 檢查 Java 的形狀跟清單對得上。

印出每個（主要寶石, 技能）原作跟我們不一樣的：
  ALLOW   原作可以、我們不行
  REFUSE  原作不行、我們可以
寫在 DEVIATION 的（引擎做不到，原因寫在旁邊）另外列出來、不算錯。原作沒有對應的技能照形狀，不在這裡對。
"""
import importlib.util
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / 'tools'
JAVA = ROOT / 'src/main/java/tw/exilecore/skill/MetaSocketRules.java'
TOTEM = ROOT / 'src/main/java/tw/exilecore/skill/TotemRules.java'
DEFAULT_REPOE = '/tmp/claude-0/-home-claude/c46e873f-cded-5f11-aa2c-868ac53d5e12/scratchpad/repoe/poe2/data'

# 我們的主要寶石 → 原作輔助那一面
METAS = {
    'meta_crit': 'SupportMetaCastOnCritPlayer',
    'meta_ailment': 'SupportMetaCastOnElementalAilmentPlayer',
    'meta_dodge': 'SupportMetaCastOnDodgePlayer',
    'meta_minion_death': 'SupportMetaCastOnMinionDeathPlayer',
    'meta_elemental_invocation': 'SupportElementalInvocationPlayer',
    'meta_reaper_invocation': 'SupportReapersInvocationPlayer',
    'meta_barrier_invocation': 'SupportBarrierInvocationPlayer',
    'spellslinger': 'SupportSpellslingerPlayer',
    'fire_spell_on_hit': 'SupportMetaCastFireSpellOnHitPlayer',
    'totem_casting': 'SupportMetaTotemSpellTotemPlayer',
}

TRIGGER_DEV = '我們做成跟著你的增益（guard），被觸發的路徑放不出來；而且是武器賦予的，沒有寶石，本來就插不進主要寶石'
# (主要寶石 or '*', 技能) → 原因（原作可以、我們照舊不行）
DEVIATION = {
    ('*', 'sigil_of_power'): TRIGGER_DEV,
    ('*', 'consecrate'): TRIGGER_DEV,
    ('*', 'feast_of_flesh'): TRIGGER_DEV,
    # 2026-10-28 照 PoE2：雷遁、骸爆、五個詛咒、縛根交給星咒圖騰（TotemRules.spellTotemCastable）；這次之前列在這裡（圖騰放不出來）
}

ENGINE_REFUSED = {'aura', 'summon', 'warcry', 'rally', 'guard', 'desecrate', 'meta', 'leap', 'flicker'}
SHAPES = {'projectile', 'nova', 'cone', 'chain', 'impact', 'zone', 'corpse', 'mark'}
DEPLOYABLE_NOT = {'dash', 'leap', 'mark', 'corpse', 'summon', 'aura', 'warcry', 'flicker', 'rally', 'desecrate', 'meta', 'guard'}


def totem_castable(skill, mech):
    """照 TotemRules.spellTotemCastable：雷遁、有範圍的詛咒、屍體也行，其他照 SkillBuild.deployable。"""
    if mech == 'dash':
        return has_flag(skill, 'flag.lightning_warp')
    if mech == 'mark':
        r = (skill.get('mark') or {}).get('radius')
        return bool(r) and (max(r) if isinstance(r, list) else r) > 0
    if mech == 'corpse':
        return True
    return mech not in DEPLOYABLE_NOT


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def java_set(text, name):
    body = re.search(r'Set<String> ' + name + r' = Set\.of\((.*?)\);', text, re.S).group(1)
    body = re.sub(r'//[^\n]*', '', body)
    return set(re.findall(r'"([a-z0-9_]+)"', body))


def rpn(expr, types):
    """RePoE 的技能類型式子（逆波蘭式）；最後堆疊上任一項成立就算成立（跟 PoB 一樣）。"""
    if not expr:
        return None
    stack = []
    for token in expr:
        if token == 'AND':
            b, a = stack.pop(), stack.pop()
            stack.append(a and b)
        elif token == 'OR':
            b, a = stack.pop(), stack.pop()
            stack.append(a or b)
        elif token == 'NOT':
            stack.append(not stack.pop())
        else:
            stack.append(token in types)
    return any(stack)


def poe2_ok(support, types):
    allowed = rpn(support.get('allowed_types'), types)
    excluded = rpn(support.get('excluded_types'), types)
    return (allowed is None or allowed) and not excluded


def has_flag(skill, flag):
    return any(m.get('stat') == flag and float(m.get('value') or 0) > 0 for m in skill.get('modifiers') or [])


def ours_ok(meta, sid, skill, lists):
    """照 Java 重算：MetaGems.problem(spec, spell) == null。"""
    tags = list(skill.get('tags') or [])
    mech = skill.get('mechanic')
    if mech == 'mark' and not (skill.get('mark') or {}).get('radius') and 'spell' in tags:
        tags.remove('spell')  # SkillDefinition：印記（沒有範圍的）不算法術，拿掉 spell 標籤
    if skill.get('hidden') or skill.get('type') != 'spell' or 'spell' not in tags:
        return False
    if mech in ENGINE_REFUSED or 'persistent' in tags:
        return False
    if mech == 'dash' and not has_flag(skill, 'flag.lightning_warp'):
        return False
    if 'command' in tags:
        return False
    if meta == 'totem_casting':
        if float(skill.get('cooldown') or 0) > 0 or not totem_castable(skill, mech) or skill.get('totem') or skill.get('trap') or skill.get('bell'):
            return False
        return sid not in lists['NOT_SPELL_TOTEM'] and skill.get('source') not in ('weapon', 'ascendancy')
    if sid in lists['TRIGGERABLE']:
        trig = True
    elif sid in lists['NOT_TRIGGERABLE']:
        trig = False
    else:
        trig = ('channelling' not in tags and not skill.get('totem') and not skill.get('trap') and not skill.get('bell')
                and not has_flag(skill, 'channel.stages') and mech in SHAPES)
    if not trig:
        return False
    if meta == 'meta_minion_death' and (sid in lists['CREATES_MINION'] or mech == 'summon'):
        return False
    if meta == 'fire_spell_on_hit' and 'fire' not in tags and sid not in lists['FIRE']:
        return False
    return skill.get('source') not in ('weapon', 'ascendancy')


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_REPOE
    sk = json.load(open(Path(folder) / 'skills.json', encoding='utf-8'))
    gems = json.load(open(Path(folder) / 'skill_gems.json', encoding='utf-8'))
    q = load('audit_poe2_quality')
    byname, byskill = {}, {}
    for key, g in gems.items():
        if g.get('gem_type') == 'support' or (g.get('base_item') or {}).get('release_state') != 'released':
            continue
        name = g['base_item']['display_name']
        if 'Unique' not in key or name not in byname:
            byname[name] = g
        for sid in g['grants_skills']:
            byskill.setdefault(sid, g)
    spec = load('apply_poe2_gem_tiers').SPEC
    rmap = {k: v[0].split(':')[0] for k, v in load('audit_poe2_skills').MAP.items()}
    for k, v in load('audit_poe2_spells').S.items():
        if v.get('main'):
            rmap.setdefault(k, v['main'])
    text = JAVA.read_text(encoding='utf-8')
    lists = {n: java_set(text, n) for n in ('TRIGGERABLE', 'NOT_TRIGGERABLE', 'CREATES_MINION', 'FIRE')}
    lists['NOT_SPELL_TOTEM'] = java_set(TOTEM.read_text(encoding='utf-8'), 'NOT_SPELL_TOTEM')
    ours = yaml.safe_load(open(ROOT / 'src/main/resources/data/skills.yml', encoding='utf-8'))['skills']
    for meta in METAS:
        if meta not in ours or 'meta' not in ours[meta]:
            print('UNKNOWN   %s 不是主要寶石' % meta)
            return 1

    bad, dev, checked = 0, [], 0
    for meta, support_id in METAS.items():
        support = sk[support_id]['support_gem']
        for sid, skill in ours.items():
            if skill.get('hidden') or 'meta' in skill:
                continue
            gem = q.gem_of(sid, spec, rmap, byname, byskill)
            if gem is None:
                continue  # 原作沒有對應：照形狀
            types = set((sk.get(gem['grants_skills'][0], {}).get('active_skill') or {}).get('types') or [])
            # 原作：有寶石才插得進去（武器、昇華賦予的沒有寶石）
            want = poe2_ok(support, types) and skill.get('source') not in ('weapon', 'ascendancy')
            have = ours_ok(meta, sid, skill, lists)
            checked += 1
            if want == have:
                continue
            reason = DEVIATION.get((meta, sid)) or DEVIATION.get(('*', sid))
            if want and reason:
                dev.append((meta, sid))
                continue
            # 武器賦予的：兩邊都因為沒有寶石插不進去，上面已經一樣；這裡是真的不一樣
            print('%-7s %-26s %-28s [%s] %s' % ('ALLOW' if want else 'REFUSE', meta, sid, gem['base_item']['display_name'],
                                                ' '.join(sorted(types & {'Spell', 'Triggerable', 'Totemable', 'Cooldown', 'Channel',
                                                                         'Persistent', 'CreatesMinion', 'Fire', 'Mark'}))))
            bad += 1
    for meta, sid in dev:
        print('DEVIATION %-26s %-28s %s' % (meta, sid, DEVIATION.get((meta, sid)) or DEVIATION.get(('*', sid))))
    print('—— 對了 %d 組（主要寶石 × 原作有對應的技能）、照舊不行（DEVIATION）%d 組；有問題的 %d 組' % (checked, len(dev), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
