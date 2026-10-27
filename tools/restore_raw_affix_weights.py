#!/usr/bin/env python3
"""2026-10-27：藥劑、珠寶、遺時珠寶、揭露詞綴的權重改回 PoE2 原始資料（拿掉 2026-10-22 的「每高一階 ×0.875」重分配）。

poe2db 這幾類詞綴寫「Modifier weight information cannot be obtained from game files」；遊戲資料（RePoE、PoB 匯出的
spawn_weights）每一階都是同一個權重 1。所以照原始資料：每一階一樣重（我們的單位 ×1000 = 1000）。
珠寶（affixes/jewels.yml 的紅／綠／藍珠寶詞綴）PoE2 只有一個範圍（等級 1）：三階（物品等級 1／50／75）合成一階，
範圍是三階合起來（= poe2db 的範圍），代號用新的 3；舊的 0／1／2 由 AffixRescale 換到這一階（數值在新範圍內不動）。
退役（spawn_tags 有 retired）的不動。

用法：python3 tools/restore_raw_affix_weights.py <repo 根目錄>
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1])
AFF = ROOT / 'src/main/resources/data/affixes'
TODAY = '2026-10-27'
RAW = 1000
TIER_RE = re.compile(r'^(    - \{ tier: (-?\d+), ilvl: (\d+), weight: )(\d+)(, .*\})(\s*#.*)?$')
VAR_RE = re.compile(r'(\w+): \[(-?[\d.]+), (-?[\d.]+)\]')
stats = {}


def blocks(lines):
    i = 0
    while i < len(lines):
        m = re.match(r'^([a-z0-9_]+):(\s*#.*)?$', lines[i])
        if not m:
            yield None, lines[i:i + 1]
            i += 1
            continue
        j = i + 1
        while j < len(lines) and (lines[j].startswith(' ') or lines[j] == '' and j + 1 < len(lines) and lines[j + 1].startswith(' ')):
            j += 1
        yield m.group(1), lines[i:j]
        i = j


def retired(block):
    return any(re.match(r'^  spawn_tags: \[[^\]]*\bretired\b', l) for l in block)


def reweight(block, fname):
    out = []
    for line in block:
        m = TIER_RE.match(line)
        if m and int(m.group(4)) != RAW:
            line = '%s%d%s   # %s 照 PoE2 原始權重（每階一樣）；這次之前 %s' % (m.group(1), RAW, m.group(5), TODAY, m.group(4))
            stats[fname] = stats.get(fname, 0) + 1
        out.append(line)
    return out


def merge_jewel(bid, block):
    tiers = [(i, TIER_RE.match(l)) for i, l in enumerate(block) if TIER_RE.match(l)]
    if len(tiers) <= 1:
        return reweight(block, 'jewels.yml')
    ranges = {}
    order = []
    for _, m in tiers:
        for name, lo, hi in VAR_RE.findall(m.group(5)):
            lo, hi = float(lo), float(hi)
            if name not in ranges:
                ranges[name] = [lo, hi]
                order.append(name)
            else:
                ranges[name][0] = min(ranges[name][0], lo)
                ranges[name][1] = max(ranges[name][1], hi)

    def num(x):
        return str(int(x)) if x == int(x) else '%g' % x
    vars_text = ', '.join('%s: [%s, %s]' % (n, num(ranges[n][0]), num(ranges[n][1])) for n in order)
    old = '；'.join('T%s 物品等級 %s 權重 %s %s' % (m.group(2), m.group(3), m.group(4), m.group(5)[2:-2]) for _, m in tiers)
    first = tiers[0][0]
    last = tiers[-1][0]
    new = ['    # %s 照 PoE2：珠寶詞綴只有一個範圍（等級 1，權重每條一樣）；這次之前三階：%s' % (TODAY, old),
           '    - { tier: 3, ilvl: 1, weight: %d, %s }' % (RAW, vars_text)]
    stats['jewels.yml merged'] = stats.get('jewels.yml merged', 0) + 1
    return block[:first] + new + block[last + 1:]


def process(fname, fn):
    path = AFF / fname
    lines = path.read_text(encoding='utf-8').split('\n')
    out = []
    for bid, block in blocks(lines):
        if bid is None or retired(block):
            out.extend(block)
        else:
            out.extend(fn(bid, block))
    path.write_text('\n'.join(out), encoding='utf-8')


for f in ('flasks.yml', 'jewels_radius.yml', 'unveiled.yml'):
    process(f, lambda bid, block, f=f: reweight(block, f))
process('jewels.yml', merge_jewel)
print(stats)
