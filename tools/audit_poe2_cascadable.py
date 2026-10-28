#!/usr/bin/env python3
"""2026-10-28：可以連瀑的技能（skill/SkillCascade.java 的 POE2、NOT_POE2）跟 PoE2 0.5 的 Cascadable 對照。

用法：python3 tools/audit_poe2_cascadable.py [RePoE poe2/data 目錄]

連瀑（Spell Cascade）、三迴（Zarokh's Refrain）、雪封（Tul's Avalanche）、雙詠（Spell Echo）在原作都只認技能類型有 Cascadable 的技能
（RePoE skills.json 的 support_gem.allowed_types = ['Cascadable']）。我們的技能 → PoE2 寶石照 tools/audit_poe2_quality.py 的對照。

印出：
  MISSING   原作是 Cascadable、SkillCascade.POE2 沒寫（也不在 DEVIATION）
  WRONG     寫在 POE2，原作卻不是 Cascadable
  CONFLICT  寫在 NOT_POE2，原作卻是 Cascadable
  UNMAPPED  寫在 POE2／NOT_POE2，卻找不到原作的寶石（精靈指令、昇華這些另外列在 EXTRA_POE2 確認過）
原作有對應、不是 Cascadable、但我們照形狀會當成可以連瀑的，要寫進 NOT_POE2：Java 那邊 SkillCascadeTest 用形狀檢查（Python 這邊不重算形狀）。
"""
import importlib.util
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / 'tools'
JAVA = ROOT / 'src/main/java/tw/exilecore/skill/SkillCascade.java'
DEFAULT_REPOE = '/tmp/claude-0/-home-claude/c46e873f-cded-5f11-aa2c-868ac53d5e12/scratchpad/repoe/poe2/data'

# 原作是 Cascadable、我們照舊不算的 → 原因
DEVIATION = {
    'consecrate': '我們的聖地是跟著你的增益（guard），不是地上的一塊範圍，連瀑沒有東西可以多放',
    'inevitable_agony': '昇華賦予、借戰吼實作（不帶 spell 標籤），法術的輔助本來就掛不上',
}
# 沒有寶石、不在寶石對照表裡的（精靈的指令、昇華）：RePoE 技能 → Cascadable 與否（手動對過）
EXTRA_POE2 = {
    'ruzhan_reckoning': ('MeteorFireDjinn', True),
    'kelari_deception': ('ExplosiveTeleportSandDjinn', False),
    'kelari_judgment': ('HandSlamSandDjinn', False),
    'navira_fracturing': ('ChilledGroundBurstWaterDjinn', False),
    'ruzhan_trap': ('FireRuneFireDjinn', False),
    'moment_of_vulnerability': ('MomentOfVulnerabilityPlayer', False),
}


def load(name):
    spec = importlib.util.spec_from_file_location(name, TOOLS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def java_set(text, name):
    body = re.search(r'Set<String> ' + name + r' = Set\.of\((.*?)\);', text, re.S).group(1)
    return set(re.findall(r'"([a-z0-9_]+)"', body))


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
    poe2, not_poe2 = java_set(text, 'POE2'), java_set(text, 'NOT_POE2')
    ours = yaml.safe_load(open(ROOT / 'src/main/resources/data/skills.yml', encoding='utf-8'))['skills']

    def types(sid):
        return (sk.get(sid, {}).get('active_skill') or {}).get('types') or []

    bad = 0
    for skill in ours:
        if skill in EXTRA_POE2:
            sid, casc = EXTRA_POE2[skill]
            if ('Cascadable' in types(sid)) != casc:
                print('%-9s %-26s EXTRA_POE2 寫錯：%s' % ('WRONG', skill, sid))
                bad += 1
            name = sid
        else:
            gem = q.gem_of(skill, spec, rmap, byname, byskill)
            if gem is None:
                if skill in poe2 or skill in not_poe2:
                    print('%-9s %-26s 找不到原作的寶石' % ('UNMAPPED', skill))
                    bad += 1
                continue
            casc = any('Cascadable' in types(sid) for sid in gem['grants_skills'])
            name = gem['base_item']['display_name']
        if casc and skill not in poe2 and skill not in DEVIATION:
            print('%-9s %-26s [%s] 原作是 Cascadable' % ('MISSING', skill, name))
            bad += 1
        elif not casc and skill in poe2:
            print('%-9s %-26s [%s] 原作不是 Cascadable' % ('WRONG', skill, name))
            bad += 1
        elif casc and skill in not_poe2:
            print('%-9s %-26s [%s] 原作是 Cascadable' % ('CONFLICT', skill, name))
            bad += 1
    print('—— POE2 %d 個、NOT_POE2 %d 個、照舊不算（DEVIATION）：%s；有問題的 %d 個' % (
        len(poe2), len(not_poe2), ' '.join(sorted(DEVIATION)), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
