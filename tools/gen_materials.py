# -*- coding: utf-8 -*-
"""從 Paper API 的 org/bukkit/Material.java 產生 src/test/resources/materials.txt（單元測試用來檢查資料檔的 icon／material）。
用法：python tools/gen_materials.py <Material.java 的路徑> [輸出檔]
升級 Paper 之後跑一次（LEGACY_ 開頭的舊名字不列）。
"""
import re, sys

src = open(sys.argv[1], encoding='utf-8').read()
out = sys.argv[2] if len(sys.argv) > 2 else 'src/test/resources/materials.txt'
body = src[src.index('public enum Material'):]
names = set()
for m in re.finditer(r'^\s{4}([A-Z][A-Z0-9_]*)\s*(\(|,|;)', body, re.M):
    if not m.group(1).startswith('LEGACY_'):
        names.add(m.group(1))
    if m.group(2) == ';':
        break
with open(out, 'w', encoding='utf-8') as f:
    f.write('# org.bukkit.Material 的常數（Paper 26.2，不含 LEGACY_）：單元測試用它檢查資料檔的 icon／material 是不是原版物品。\n')
    f.write('# 升級 Paper 後用 tools/gen_materials.py 重產。\n')
    f.write('\n'.join(sorted(names)) + '\n')
print(len(names), '個物品 →', out)
