#!/usr/bin/env python3
"""2026-10-26：產生 data/gem_colours.yml（技能、輔助寶石的顏色：r 力量、g 敏捷、b 智慧；昇華・嵌晶的寶石機制用）。
原作的顏色照 RePoE（repoe-fork/poe2 data/skill_gems.json 的 color），用名字對上我們的代號（skills.yml／supports.yml 代號後面註解的原作名、
或代號本身）；對不上的照標籤推（近戰、猛擊、戰吼、圖騰、火焰、物理 → 紅；投射物、弓、弩、矛、冰冷、中毒 → 綠；法術、召喚物、詛咒、光環、閃電、混沌 → 藍）。
2026-10-27：技能的顏色改成照 GemAttributes.java（RePoE 的屬性分配，主要的那個屬性；分配一樣多照力量、敏捷、智慧的順序），
輔助照 SupportColours.java——程式那兩張表是準的，這裡跟著產生，兩邊才不會不一樣（GemAttributesTest 會檢查）；兩張表都沒有的才照上面的舊規則。
用法：python3 tools/gen_gem_colours.py <RePoE 的 skill_gems.json> > src/main/resources/data/gem_colours.yml"""
import json, re, sys, os
import yaml
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, 'src/main/resources/data')
gems = json.load(open(sys.argv[1], encoding='utf-8'))
byname = {}
for v in gems.values():
    color = v.get('color')
    if color not in ('r', 'g', 'b'):
        continue
    for n in [(v.get('base_item') or {}).get('display_name'), v.get('support_name'), v.get('skill_name')]:
        if n:
            byname.setdefault(n.lower(), color)


def comments(path, section):
    out = {}
    inside = False
    for line in open(path, encoding='utf-8'):
        if re.match(r'^%s:\s*$' % section, line):
            inside = True
            continue
        if inside and re.match(r'^[a-z_]+:', line):
            inside = False
        if inside:
            m = re.match(r'^  ([a-z_0-9]+):\s*(?:#\s*(.*))?$', line)
            if m:
                out[m.group(1)] = m.group(2) or ''
    return out


def lookup(sid, comment):
    cands = []
    m = re.match(r"([A-Za-z' ,\-]+)", comment.strip())
    if m:
        cands.append(m.group(1).strip())
    cands.append(sid.replace('_', ' '))
    for c in cands:
        c = c.lower().strip()
        for v in (c, c + ' i', c + ' support'):
            if v in byname:
                return byname[v]
    return None


RED = {'melee', 'strike', 'slam', 'warcry', 'totem', 'fire', 'physical', 'mace', 'axe', 'sword', 'armour', 'banner'}
GREEN = {'projectile', 'bow', 'crossbow', 'spear', 'cold', 'poison', 'movement', 'travel', 'grenade', 'mark', 'quarterstaff', 'dagger', 'claw'}
BLUE = {'spell', 'minion', 'curse', 'aura', 'herald', 'lightning', 'chaos', 'meta', 'persistent', 'wand', 'staff', 'corpse'}
WORDS = {'bleed': 'physical', 'ignite': 'fire', 'chill': 'cold', 'freeze': 'cold', 'shock': 'lightning'}


def guess(tags, default):
    tags = set(tags or [])
    score = {'r': len(tags & RED), 'g': len(tags & GREEN), 'b': len(tags & BLUE)}
    best = max(score.values())
    if best == 0:
        return default
    for c in (default, 'r', 'g', 'b'):
        if score[c] == best:
            return c


JAVA = os.path.join(ROOT, 'src/main/java/tw/exilecore/skill')
PRIMARY = {'S': 'r', 'D': 'g', 'I': 'b', 'SD': 'r', 'DI': 'g', 'SI': 'r', 'S75I25': 'r'}


def java_skill_colours():
    """GemAttributes.java 的 entry("S", "id id ...") 表 → 代號: 顏色。"""
    src = open(os.path.join(JAVA, 'GemAttributes.java'), encoding='utf-8').read()
    out = {}
    for m in re.finditer(r'entry\("([A-Z0-9]+)",((?:\s*(?://[^\n]*\n)?\s*\+?\s*"[^"]*")+)\)', src):
        for ids in re.findall(r'"([^"]*)"', m.group(2)):
            for sid in ids.split():
                out[sid] = PRIMARY[m.group(1)]
    return out


def java_support_colours():
    """SupportColours.java 的 RED／GREEN／BLUE 清單 → 代號: 顏色。"""
    src = open(os.path.join(JAVA, 'SupportColours.java'), encoding='utf-8').read()
    out = {}
    for colour, letter in (('RED', 'r'), ('GREEN', 'g'), ('BLUE', 'b')):
        m = re.search(r'\n\s*%s, // [^\n]*\n((?:\s*(?://[^\n]*\n)?\s*\+?\s*"[^"]*"\s*\n?)+)' % colour, src)
        if m:
            for ids in re.findall(r'"([^"]*)"', m.group(1)):
                for sid in ids.split():
                    out[sid] = letter
    return out


def main():
    java_skills = java_skill_colours()
    java_supports = java_support_colours()
    skills = yaml.safe_load(open(os.path.join(DATA, 'skills.yml'), encoding='utf-8'))['skills']
    supports = yaml.safe_load(open(os.path.join(DATA, 'supports.yml'), encoding='utf-8'))['supports']
    sc = comments(os.path.join(DATA, 'skills.yml'), 'skills')
    pc = comments(os.path.join(DATA, 'supports.yml'), 'supports')
    out = ['# 2026-10-26：技能、輔助寶石的顏色（r 力量、g 敏捷、b 智慧），昇華・嵌晶的寶石機制用（鑲嵌的寶石、整合效率、奇術注入、鑲嵌物）。',
           '# tools/gen_gem_colours.py 產生：原作的顏色照原作資料（名字對得上的），對不上的照標籤推。改完 /ec reload。',
           '# 2026-10-27：技能照 GemAttributes.java、輔助照 SupportColours.java 產生（程式也以那兩張表為準）；兩張表都沒有的（武器、昇華賦予的）才照上面的規則。',
           'skills:']
    matched = [0, 0]
    for sid, d in skills.items():
        if not isinstance(d, dict) or d.get('hidden'):
            continue
        c = java_skills.get(sid) or lookup(sid, sc.get(sid, ''))
        if c:
            matched[0] += 1
        else:
            tags = list(d.get('tags') or []) + list(d.get('weapons') or [])
            c = guess(tags, 'r' if d.get('type') == 'attack' else 'b')
        out.append('  %s: %s' % (sid, c))
    out.append('supports:')
    for sid, d in supports.items():
        if not isinstance(d, dict):
            continue
        c = java_supports.get(sid) or lookup(sid, pc.get(sid, ''))
        if c:
            matched[1] += 1
        else:
            tags = list(d.get('requires') or [])
            for m in d.get('modifiers') or []:
                if not isinstance(m, dict):
                    continue
                tags.extend(m.get('tags') or [])
                stat = str(m.get('stat', ''))
                for k in ('fire', 'cold', 'lightning', 'chaos', 'poison', 'bleed', 'ignite', 'chill', 'freeze', 'shock', 'projectile', 'minion',
                          'curse', 'aura'):
                    if k in stat:
                        tags.append(WORDS.get(k, k))
            c = guess(tags, 'r')
        out.append('  %s: %s' % (sid, c))
    print('\n'.join(out))
    print('matched skills %d supports %d' % tuple(matched), file=sys.stderr)


if __name__ == '__main__':
    main()
