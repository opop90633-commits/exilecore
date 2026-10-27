#!/usr/bin/env python3
"""2026-10-27：退役（retired）防具／盾基底的護甲、閃避、能量護盾、格擋改成 PoE2 原值。

10-26 只改了這些基底的需求，數值還是舊的；傳奇的 stat_base 指的就是這些退役的階，
所以 Kaom's Heart 那類傳奇的基底防禦一直偏低。這支照 audit 的 base_requirements.tsv
（poe2db／RePoE 0.5 的值）改，舊值寫在同一行的註解。

用法：python3 tools/fix_retired_base_defences.py <repo 根目錄> <base_requirements.tsv>
"""
import csv
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1])
TSV = Path(sys.argv[2])
DATA = ROOT / 'src/main/resources/data/bases'
FILES = ['armour.yml', 'shields.yml']
TODAY = '2026-10-27'
KEYS = (('armour', 'poe2_armour'), ('evasion', 'poe2_evasion'), ('es', 'poe2_es'), ('block', 'poe2_block'))

rows = {r['our_id']: r for r in csv.DictReader(open(TSV, encoding='utf-8'), delimiter='\t')
        if r['status'] == 'retired' and r['poe2_name']}
changes = []
warnings = []


def fmt(key, v):
    if key == 'block':
        return '%.1f' % v
    return str(int(round(v)))


def edit(bid, block, row):
    name = row['poe2_name']
    want = {k: float(row[c]) for k, c in KEYS if row[c].strip()}
    have = set()
    out = []
    insert_at = None
    for line in block:
        for key, _ in KEYS:
            m = re.match(r'^  %s: ([\d.]+)(.*)$' % key, line)
            if not m:
                continue
            have.add(key)
            if key not in want:
                warnings.append('%s：%s 有 %s，PoE2 沒有（保留）' % (bid, name, key))
                break
            old = float(m.group(1))
            if abs(old - want[key]) > 1e-6:
                line = '  %s: %s   # %s 照 PoE2 原值（%s）；這次之前 %s' % (key, fmt(key, want[key]), TODAY, name, m.group(1))
                changes.append((bid, key, m.group(1), fmt(key, want[key])))
            insert_at = len(out) + 1
            break
        if line.startswith('  requirements:') and insert_at is None:
            insert_at = len(out) + 1
        out.append(line)
    missing = [k for k, _ in KEYS if k in want and k not in have]
    for key in missing:
        out.insert(insert_at, '  %s: %s   # %s 照 PoE2 補上（%s）；這次之前沒有' % (key, fmt(key, want[key]), TODAY, name))
        insert_at += 1
        changes.append((bid, key, '-', fmt(key, want[key])))
    return out


def process(path):
    lines = path.read_text(encoding='utf-8').split('\n')
    out = []
    i = 0
    while i < len(lines):
        m = re.match(r'^([a-z0-9_]+):(\s*#.*)?$', lines[i])
        if not m:
            out.append(lines[i])
            i += 1
            continue
        bid = m.group(1)
        j = i + 1
        while j < len(lines) and (lines[j].startswith(' ') or lines[j] == '' and j + 1 < len(lines) and lines[j + 1].startswith(' ')):
            j += 1
        block = lines[i:j]
        if bid in rows:
            block = edit(bid, block, rows[bid])
        out.extend(block)
        i = j
    path.write_text('\n'.join(out), encoding='utf-8')


for f in FILES:
    process(DATA / f)
print('changes', len(changes))
for c in changes:
    print('\t'.join(c))
for w in warnings:
    print('WARN', w)
