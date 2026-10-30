#!/usr/bin/env python3
"""2026-10-30 照站長「未切割輔助寶石選技能後，那個技能是不是真的能裝上那顆輔助」：輔助能不能掛照 PoE2 0.5 的技能類型
（RePoE skills.json 的 support_gem.allowed_types／excluded_types，照 PoB 的 doesTypeExpressionMatch：allowed 也看召喚物的技能類型，excluded 只看技能自己）。
產生 src/main/resources/data/support_poe2.yml：我們的規則（requires／excludes／防呆）讓掛、原作不讓掛的組合（技能、輔助都對得到原作寶石的才算；
宿主寶石（引星、星咒圖騰…）照孔裡的技能驗，不在這裡）。原作讓掛、我們擋掉的不動（大多是「掛了沒有效果」的防呆）。
用法：先跑一次 SupMatrix（印出我們現在的判斷：技能 \t 輔助 \t 1/0 \t 原因 \t c/x \t h），再
  python3 tools/gen_poe2_support_types.py [RePoE poe2/data] [matrix.tsv]
要重產時先把 support_poe2.yml 清空（blocked: {}）再印 matrix，不然已經擋掉的會被當成我們本來就不讓掛。
技能對照照 audit_poe2_quality.gem_of；輔助對照照 apply_poe2_*supports* 的 SPEC、supports.yml／supports_lineage.yml 代號那行或註解的英文名。"""
import json, re, sys, importlib.util, collections
from pathlib import Path
ROOT=Path('/tmp/claude-0/build'); TOOLS=ROOT/'tools'
R=sys.argv[1] if len(sys.argv)>1 else '/home/claude/repoe-fork/poe2/data'
MATRIX=sys.argv[2] if len(sys.argv)>2 else '/tmp/claude-0/probe/matrix.tsv'
sk=json.load(open(R+'/skills.json')); gems=json.load(open(R+'/skill_gems.json'))
def load(n):
    sp=importlib.util.spec_from_file_location(n,TOOLS/(n+'.py')); m=importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m
# support name map
sup_by_name={}
act_by_name={}; act_by_skill={}
for k,g in gems.items():
    bi=g.get('base_item') or {}
    if bi.get('release_state')!='released': continue
    nm=bi.get('display_name')
    if g.get('gem_type')=='support' or 'Support' in k:
        sup_by_name.setdefault(nm,g)
    else:
        if 'Unique' not in k or nm not in act_by_name: act_by_name[nm]=g
        for s in g['grants_skills']: act_by_skill.setdefault(s,g)
ours_sup={}
cands={}
import unicodedata
def norm(x): return unicodedata.normalize('NFKD',x).encode('ascii','ignore').decode().replace('’',"'").strip().lower()
sup_norm={norm(k):v for k,v in sup_by_name.items()}
for f in ['supports.yml','supports_lineage.yml']:
    lines=(ROOT/'src/main/resources/data'/f).read_text().split('\n')
    cur=None
    for ln in lines:
        m=re.match(r'^  ([a-z0-9_]+):(.*)$',ln)
        if m:
            cur=m.group(1); rest=m.group(2)
            cands.setdefault(cur,[])
            for e in re.findall(r"[A-Z][A-Za-z\u00C0-\u017F'\u2019 \-]+[A-Za-z]",rest): cands[cur].append(e)
            continue
        if cur and ln.startswith('    #'):
            for e in re.findall(r"照 PoE2[ 0-9.]*[（(]?([A-Z][A-Za-z\u00C0-\u017F'\u2019 \-]+[A-Za-z])",ln): cands[cur].append(e)
names={}
for mod in ['apply_poe2_supports','apply_poe2_support_parity','apply_poe2_missing_supports']:
    for k,v in load(mod).SPEC.items(): names.setdefault(k,v[0])
for eng,ours in load('apply_poe2_missing_supports').ALREADY.items():
    names.setdefault(ours.split('（')[0],eng)
def sup_gem(sid):
    lst=([names[sid]] if sid in names else [])+cands.get(sid,[])
    for cand in lst:
        c=norm(cand)
        for n in [c, c+' i', c+' ii', c+' iii', re.sub(r' (i|ii|iii)$','',c)]:
            if n in sup_norm: return sup_norm[n]
    return None
q=load('audit_poe2_quality')
spec=load('apply_poe2_gem_tiers').SPEC
rmap={k:v[0].split(':')[0] for k,v in load('audit_poe2_skills').MAP.items()}
for k,v in load('audit_poe2_spells').S.items():
    if v.get('main'): rmap.setdefault(k,v['main'])
def skill_gem(sid): return q.gem_of(sid,spec,rmap,act_by_name,act_by_skill)
def match(expr,types):
    st=[]
    for t in expr:
        if t=='OR': b=st.pop();a=st.pop();st.append(a or b)
        elif t=='AND': b=st.pop();a=st.pop();st.append(a and b)
        elif t=='NOT': st.append(not st.pop())
        else: st.append(t in types)
    return any(st)
def poe2_ok(sg, gem):
    sup=None
    for s in sg['grants_skills']:
        if (sk.get(s) or {}).get('support_gem'): sup=sk[s]['support_gem']; break
    if sup is None: return None
    res=[]
    for s in gem['grants_skills']:
        a=sk.get(s,{}).get('active_skill') or {}
        types=set(a.get('types') or []); mt=set(a.get('minion_types') or [])
        if sup.get('excluded_types') and match(sup['excluded_types'],types): res.append(False); continue
        if not sup.get('allowed_types'): res.append(True); continue
        res.append(match(sup['allowed_types'],types|mt))
    return any(res) if res else None
rows=[l.rstrip('\n').split('\t') for l in open(MATRIX)]
skills_yml=__import__('yaml').safe_load(open(ROOT/'src/main/resources/data/skills.yml'))['skills']
diff=collections.defaultdict(list); unm_s=set(); unm_g=set(); n=0
for skill,sup,ok,why,cut,host in rows:
    if skills_yml.get(skill,{}).get('source') not in (None,'weapon'): continue
    g=skill_gem(skill); sg=sup_gem(sup)
    if g is None: unm_s.add(skill); continue
    if sg is None: unm_g.add(sup); continue
    p=poe2_ok(sg,g)
    if p is None: continue
    n+=1
    if host: continue
    if p!=(ok=='1'): diff[(sup, 'ours_yes' if ok=='1' else 'ours_no')].append((skill,why,host))

ZH = {
    'Attack': '攻擊', 'Spell': '法術', 'Damage': '會造成傷害的', 'CrossbowAmmoSkill': '弩彈藥', 'CrossbowSkill': '弩',
    'Persistent': '常駐', 'Projectile': '投射物', 'Buff': '增益', 'Melee': '近戰', 'AppliesCurse': '詛咒',
    'DamageOverTime': '持續傷害', 'SummonsTotem': '圖騰', 'CreatesMinion': '召喚', 'DegenOnlySpellDamage': '只造成持續傷害的法術',
    'Slam': '猛擊', 'Area': '範圍', 'Minion': '召喚物', 'Cooldown': '冷卻', 'IceCrystal': '冰晶', 'AffectedByCooldownRate': '冷卻',
    'UsedByTotem': '圖騰放的', 'MinionsCanExplode': '召喚物會爆炸的', 'MeleeSingleTarget': '近戰單體', 'Warcry': '戰吼',
    'Chains': '連鎖', 'CreatesGroundEffect': '留下地面效果的', 'EmpowersOtherSkill': '會強化其他技能的', 'Wall': '牆',
    'ComboStacking': '累積連擊的', 'Spear': '矛', 'GeneratesCharges': '會產生戰星的', 'Duration': '持續時間',
    'Jumping': '跳躍', 'UsableWhileMoving': '可以邊走邊用的', 'ConsumesCharges': '會消耗戰星的', 'DetonatesAfterTime': '延時引爆的',
    'ProjectileNumber': '投射物數量會變的', 'Travel': '位移', 'Unleashable': '可以封詠的', 'Grenade': '榴彈',
    'SpecialAncestralBoost': '英靈強化的', 'Nova': '新星', 'Cold': '冰冷', 'Lightning': '閃電', 'RangedAttack': '遠程攻擊',
    'CommandableMinion': '可以下指令的召喚物', 'ObjectDurability': '有耐久的物件', 'Wind': '風', 'Fire': '火焰',
    'IsBlasphemy': '褻瀆', 'Chaos': '混沌', 'GeneratesRemnants': '會留下殘骸的', 'Physical': '物理', 'Bow': '弓',
    'Triggered': '觸發', 'UsedByProxy': '代放', 'Instant': '瞬發', 'Channel': '引導', 'HasUsageCondition': '有使用條件的',
    'MinionsAreUndamagable': '召喚物不會受傷的', 'ProjectileNoCollision': '投射物不碰撞的', 'SkillConsumesFreeze': '消耗凍結的',
    'SkillGrantedBySupport': '輔助給的', 'CannotChain': '不能連鎖的', 'SkillConsumesIgnite': '消耗點燃的', 'HasSeals': '有封印的',
    'ProjectilesNumberModifiersNotApplied': '投射物數量固定的', 'SkillConsumesPowerChargesOnUse': '消耗暴星的', 'HasReservation': '保留精魂的',
    'NoAttackOrCastTime': '沒有出手時間的', 'UnlimitedTotems': '圖騰不限數量的', 'Aura': '光環', 'ReservationBecomesCost': '保留改成消耗的',
    'Meta': '引星', 'FixedCastTime': '施放時間固定的', 'ConsumesFullyBrokenArmour': '消耗破甲的', 'SkillConsumesShock': '消耗感電的',
    'SkillConsumesBleeding': '消耗流血的', 'Trapped': '陷阱', 'RemoteMined': '地雷', 'SkillConsumesFrenzyChargesOnUse': '消耗狂星的',
    'SingleLevelSkill': '只有一級的', 'SkillConsumesEnduranceChargesOnUse': '消耗磐星的', 'Rain': '落雨', 'Herald': '捷',
    'SkillConsumesPoison': '消耗中毒的', 'OngoingSkill': '持續中的', 'SkillConsumesParried': '消耗招架的', 'GroundTargetedProjectile': '射向地面的投射物',
    'Vaal': '瓦爾', 'RequiresCharges': '要戰星的', 'CannotCreateJaggedGround': '不能留下碎地的', 'IndeterminateEmpowermentAmount': '強化次數不固定的',
    'Sustained': '持續', 'HasNoCost': '沒有消耗的', 'CannotTerrainChain': '不能在地形上連鎖的', 'CreatesFissure': '裂地', 'CannotSpiritStrike': '不能靈擊的',
}


def zh(t):
    return ZH.get(t, t)


def render(expr):
    """allowed 的 RPN 寫成中文（剩在堆疊上的是「或」）。"""
    st = []
    for t in expr:
        if t == 'OR':
            b, a = st.pop(), st.pop()
            st.append(a + '或' + b)
        elif t == 'AND':
            b, a = st.pop(), st.pop()
            st.append(b + a if a in ('常駐', '召喚物') or a.endswith('的') else a + b if b in ('增益',) else a + '且' + b)
        elif t == 'NOT':
            st.append('不是' + st.pop())
        elif t.startswith('SupportedBy'):
            st.append('')
        else:
            st.append(zh(t))
    parts = []
    for x in st:
        if x and x not in parts:
            parts.append(x)
    if '會造成傷害的' in parts:
        return '會造成傷害的'  # 「會造成傷害的」已經包含攻擊、弩彈藥
    if '攻擊' in parts:
        parts = [x for x in parts if x not in ('弩', '弩彈藥')]  # 弩、弩彈藥都是攻擊
    return parts[0] if len(parts) == 1 else '、'.join(parts[:-1]) + '或' + parts[-1]


def reason(sg, gem):
    sup = [sk[x]['support_gem'] for x in sg['grants_skills'] if (sk.get(x) or {}).get('support_gem')][0]
    types = set()
    for s in gem['grants_skills']:
        types |= set((sk.get(s, {}).get('active_skill') or {}).get('types') or [])
    if sup.get('excluded_types') and match(sup['excluded_types'], types):
        for t in sup['excluded_types']:
            if t not in ('AND', 'OR', 'NOT') and not t.startswith('SupportedBy') and t in types:
                return zh(t) + '技能不行'
        return '這種技能不行'
    text = render(sup.get('allowed_types') or [])
    return ('要' + text + '技能') if text else '這種技能不行'


# 我們的效果跟原作不同、照原作的技能類型擋會變成沒有技能掛得上的（照舊用我們的標籤規則）
EXEMPT = {}   # 2026-10-30：堅物（durability）改成照原作（鐘、雷樁矢、龍捲風多用 2 次），不用再例外
out = collections.defaultdict(lambda: collections.defaultdict(list))
for (sup, kind), v in sorted(diff.items()):
    if kind != 'ours_yes' or sup in EXEMPT:
        continue
    sg = sup_gem(sup)
    for skill, _, _ in v:
        out[sup][reason(sg, skill_gem(skill))].append(skill)
lines = ['# 2026-10-30 照 PoE2 0.5 的技能類型（tools/gen_poe2_support_types.py 產生，不要手改）：我們的規則讓掛、原作不讓掛的輔助與技能。',
         '# 輔助 → { 擋掉的原因（遊戲裡「X 不能輔助這個技能（原因）」）: [技能代號] }。宿主寶石照孔裡的技能驗，不在這裡。',
         'blocked:']
for sup in sorted(out):
    lines.append('  %s:' % sup)
    for why, skills in sorted(out[sup].items()):
        lines.append('    "%s": [%s]' % (why.replace('"', ''), ', '.join(sorted(set(skills)))))
(ROOT / 'src/main/resources/data/support_poe2.yml').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print('supports', len(out), 'pairs', sum(len(x) for d in out.values() for x in d.values()))
