#!/usr/bin/env python3
"""2026-10-27：注入（蒸餾情緒 → 項鍊）的配方表，照 PoE2 0.5 每個核心固定的三種情緒（有順序）。

資料來源：GGG 官方天賦樹匯出（PoB PoE2 的 src/TreeData/0_5/tree.json，每個核心的 "recipe"），
抽出來存在 tools/poe2_tree/instil_recipes.tsv（節點 id、英文名、三種情緒）；poe2db 單一天賦頁的「Anoint」表跟這份一致
（例：Enduring Deflection = Suffering + Greed + Despair）。

用法：
  抽表：python3 tools/gen_instil_recipes.py extract <PoB 0_5/tree.json> tools/poe2_tree/instil_recipes.tsv
  產生：python3 tools/gen_instil_recipes.py build tools/poe2_tree/instil_recipes.tsv src/main/resources/data/passives/tree.json \
          src/main/resources/data/instil.yml
"""
import json
import sys

EMOTIONS = ['Ire', 'Guilt', 'Greed', 'Paranoia', 'Envy', 'Disgust', 'Despair', 'Fear', 'Suffering', 'Isolation']
IDS = {e: 'distilled_' + e.lower() for e in EMOTIONS}


def extract(tree_path, out_path):
    nodes = json.load(open(tree_path, encoding='utf-8'))['nodes']
    rows = []
    for key, node in nodes.items():
        if isinstance(node, dict) and node.get('recipe'):
            rows.append((int(key), node.get('name', ''), node['recipe']))
    rows.sort()
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write('node\tname\temotion1\temotion2\temotion3\n')
        for key, name, recipe in rows:
            f.write('%d\t%s\t%s\n' % (key, name, '\t'.join(recipe)))
    print('rows', len(rows))


def build(tsv_path, tree_path, out_path):
    ours = {n['id']: n for n in json.load(open(tree_path, encoding='utf-8'))['nodes'] if n['type'] == 'notable'}
    lines = open(tsv_path, encoding='utf-8').read().strip().split('\n')[1:]
    out = []
    skipped_other = []
    for line in lines:
        key, name, *recipe = line.split('\t')
        node_id = 'p' + key
        if node_id not in ours:
            continue
        if any(e not in IDS for e in recipe):
            skipped_other.append(name)
            continue
        out.append((node_id, ours[node_id].get('name', name), name, [IDS[e] for e in recipe]))
    missing = sorted(n.get('en', k) for k, n in ours.items() if k not in {o[0] for o in out})
    with open(out_path, 'w', encoding='utf-8') as f:
        f.write('# 注入（蒸餾情緒 → 項鍊）的配方：照 PoE2 0.5，每個核心固定三種情緒（照順序放；同一種可以重複）。\n')
        f.write('# tools/gen_instil_recipes.py 產生（資料來源：GGG 官方天賦樹匯出的 recipe，poe2db 單一天賦頁的 Anoint 表一致）。改完 /ec reload。\n')
        f.write('# 沒有列在這裡的核心（PoE2 也沒有配方的 %d 個）不能注入；這次之前（2026-10-27 前）任何核心都能注入，\n' % len(missing))
        f.write('# 花 3 顆同一種情緒（看離天賦樹中心多遠：≤7 格怒火、≤11 愧疚、≤15 貪婪、更遠妄想）。已經注入的項鍊照舊生效。\n')
        f.write('# 情緒的順序（便宜 → 稀有）：' + '、'.join(IDS[e] for e in EMOTIONS) + '\n')
        f.write('recipes:\n')
        for node_id, zh, en, recipe in out:
            f.write('  %s: [%s]   # %s\n' % (node_id, ', '.join(recipe), zh))
    print('recipes', len(out), 'not instillable', len(missing), 'skipped (other emotions)', skipped_other)


if __name__ == '__main__':
    if sys.argv[1] == 'extract':
        extract(sys.argv[2], sys.argv[3])
    else:
        build(sys.argv[2], sys.argv[3], sys.argv[4])
