"""詞綴照 poe2db（2026-10-26）：每條詞綴的階數、需求等級、權重、數值全部換成 poe2db 的值。
poe2_affix_tiers.txt 一行一條：id:等級/權重/數值,...;...（數值照我們 tiers 的變數順序，已經是遊戲顯示的值），id! = PoE2 這個部位沒有。"""
import re
import sys

DATA, *FILES = sys.argv[1:]
TAG = '2026-10-26 照 poe2db'
P2 = {}
for line in open(DATA, encoding='utf-8'):
    line = line.strip()
    if not line:
        continue
    if line.endswith('!'):
        P2[line[:-1]] = None
        continue
    k, v = line.split(':', 1)
    tiers = []
    for t in v.split(';'):
        lvl, w, nums = t.split('/')
        tiers.append((int(lvl), int(w), [float(x) for x in nums.split(',')]))
    P2[k] = tiers

TIER_RE = re.compile(r'^    - \{ tier: (-?\d+), ilvl: (\d+), weight: (\d+), (.*) \}\s*$')
VAR_RE = re.compile(r'(\w+): \[([^\]]*)\]')
log, seen = [], set()


def fmt(x):
    r = round(x)
    return str(int(r)) if abs(x - r) < 1e-6 else ('%g' % x)


def pick_names(names, n):
    if n == len(names):
        return names
    if n < len(names):
        if n == 1:
            return names[:1]
        return [names[round(i * (len(names) - 1) / (n - 1))] for i in range(n)]
    # 比原本多：最弱的名字往後補「初階」「微」等級的編號
    extra = [names[-1] + '·' + str(i + 2) for i in range(n - len(names))]
    return names + extra


for path in FILES:
    L = open(path, encoding='utf-8').read().split('\n')
    out, i = [], 0
    while i < len(L):
        m = re.match(r'^([a-z0-9_]+):\s*(#.*)?$', L[i])
        if not m or m.group(1) not in P2:
            out.append(L[i])
            i += 1
            continue
        aid = m.group(1)
        seen.add(aid)
        j = i + 1
        while j < len(L) and not re.match(r'^[a-z0-9_]+:\s*(#.*)?$', L[j]) and not L[j].startswith('# ===='):
            j += 1
        block = L[i:j]
        new = P2[aid]
        if new is None:
            for k, b in enumerate(block):
                mm = re.match(r'^  spawn_tags: (\[.*\])(.*)$', b)
                if mm:
                    block[k] = '  spawn_tags: [retired]   # %s：PoE2 這個部位沒有這條詞綴；這次之前 %s' % (TAG, mm.group(1))
            log.append('%s\t停用（PoE2 沒有）' % aid)
            out += block
            i = j
            continue
        text = '\n'.join(b for b in block if b.startswith('  text:') or b.lstrip().startswith('- { stat'))
        div = {v: int(d) for v, d in re.findall(r'\{(\w+)/(\d+)\}', text)}
        tier_idx = [k for k, b in enumerate(block) if TIER_RE.match(b)]
        pos = [k for k in tier_idx if int(TIER_RE.match(block[k]).group(1)) > 0]
        first = TIER_RE.match(block[pos[0]])
        varnames = [v for v, _ in VAR_RE.findall(first.group(4))]
        old_desc = []
        for k in pos:
            tm = TIER_RE.match(block[k])
            old_desc.append('%s/%s/%s' % (tm.group(2), tm.group(3), ','.join(x.strip() for _, vv in VAR_RE.findall(tm.group(4)) for x in vv.split(','))))
        lines = []
        for n, (lvl, w, nums) in enumerate(new, 1):
            if len(nums) != 2 * len(varnames):
                raise SystemExit('%s: %d 個數字對 %d 個變數' % (aid, len(nums), len(varnames)))
            parts = []
            for vi, v in enumerate(varnames):
                d = div.get(v, 1)
                parts.append('%s: [%s, %s]' % (v, fmt(nums[2 * vi] * d), fmt(nums[2 * vi + 1] * d)))
            lines.append('    - { tier: %d, ilvl: %d, weight: %d, %s }' % (n, lvl, w, ', '.join(parts)))
        new_desc = ['%d/%d/%s' % (l, w, ','.join(fmt(x * div.get(varnames[q // 2], 1)) for q, x in enumerate(nums))) for l, w, nums in new]
        # 換掉 positive tiers（負的特殊階留著）
        block = block[:pos[0]] + ['    # %s：階數、需求等級、權重、數值（%d 階 → %d 階）' % (TAG, len(pos), len(new))] + lines + [b for k, b in enumerate(block[pos[0]:], pos[0]) if k not in pos]
        for k, b in enumerate(block):
            mm = re.match(r'^(  names: \[)(.*)(\].*)$', b)
            if mm:
                names = [x.strip() for x in mm.group(2).split(',')]
                if len(names) != len(new):
                    block[k] = mm.group(1) + ', '.join(pick_names(names, len(new))) + mm.group(3)
        if old_desc != new_desc:
            log.append('%s\t%s\t→\t%s' % (aid, ';'.join(old_desc), ';'.join(new_desc)))
        out += block
        i = j
    s = '\n'.join(out)
    s = s.replace('# 2026-10-22：階級權重從「每高一階 ×0.8」改成「×0.7」（高階更難出：9 階的 T1 從約 3.9% 降到約 1.8%）；每條詞綴的權重總和不變（出現哪一條的機率不變，只改出哪一階）。',
                  '# 2026-10-26：階數、需求等級、權重、數值全部改成 poe2db 的原值（權重就是 PoE2 的 spawn weight，不再用 ×0.7 等比）；PoE2 這個部位沒有的詞綴停用。')
    open(path, 'w', encoding='utf-8').write(s)

missing = set(P2) - seen
print('\n'.join(log))
print('changed', len(log), 'missing', sorted(missing), file=sys.stderr)
