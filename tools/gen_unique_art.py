#!/usr/bin/env python3
"""
2026-10：替沒有專屬圖的傳奇（PoE2 改版新增的那批）產生 16×16 圖，附加在 src/main/resources/web/unique_art.txt 後面。

做法：拿同一個基底（沒有就同一個欄位）的既有傳奇圖當底，照這件傳奇的主題（詞綴裡的火焰／冰冷／閃電／混沌／生命…）
把有顏色的像素轉到那個色相，再在固定位置點一顆寶石亮點，所以每件都長得不一樣。弓、弩的拉弓、搭箭的分格圖一起產生。
已經有圖的傳奇不動。改完直接跑：python3 tools/gen_unique_art.py
"""
import colorsys
import hashlib
import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / 'src/main/resources/web/unique_art.txt'
DATA = ROOT / 'src/main/resources/data'
MARK = '# ---- 2026-10 自動產生（tools/gen_unique_art.py）----'
FRAMES = ['_pulling_0', '_pulling_1', '_pulling_2', '_arrow']
KIND_TAGS = {'bow', 'crossbow', 'quarterstaff', 'staff', 'wand', 'sceptre', 'mace', 'axe', 'sword', 'dagger', 'claw', 'spear', 'flail',
             'one_hand', 'two_hand', 'shield', 'buckler', 'focus', 'quiver', 'helmet', 'body', 'boots', 'leggings', 'gloves',
             'ring', 'amulet', 'belt', 'ruby', 'emerald', 'sapphire'}
THEMES = [('火焰', 0.04), ('點燃', 0.04), ('冰冷', 0.57), ('凍結', 0.57), ('冰緩', 0.57), ('閃電', 0.14), ('感電', 0.14),
          ('混沌', 0.78), ('中毒', 0.30), ('生命', 0.0), ('魔力', 0.62), ('能量護盾', 0.52), ('召喚物', 0.33), ('暴擊', 0.9)]


def parse(text):
    sprites = {}
    for block in re.split(r'\n(?=item )', text):
        lines = block.strip('\n').split('\n')
        if not lines or not lines[0].startswith('item '):
            continue
        name = lines[0][5:].strip()
        pal = {}
        rows = []
        for ln in lines[1:]:
            ln = ln.strip()
            if re.match(r'^.=[0-9a-fA-F]{6,8}$', ln):
                pal[ln[0]] = ln[2:]
            elif ln and not ln.startswith('#'):
                rows.append(ln)
        sprites[name] = (pal, rows)
    return sprites


def recolor(pal, hue, seed):
    out = {}
    for k, hexc in pal.items():
        r, g, b = (int(hexc[i:i + 2], 16) / 255 for i in (0, 2, 4))
        alpha = hexc[6:8] if len(hexc) > 6 else ''
        h, l, s = colorsys.rgb_to_hls(r, g, b)
        if s > 0.18 and hue is not None:
            h = (hue + (h - 0.5) * 0.08) % 1.0
            s = min(1.0, s * 1.05)
        elif hue is None:
            h = (h + seed) % 1.0
        r, g, b = colorsys.hls_to_rgb(h, l, s)
        out[k] = '%02x%02x%02x%s' % (int(r * 255), int(g * 255), int(b * 255), alpha)
    return out


def gem(pal, rows, hue, seed):
    """在最亮的不透明像素旁點一顆寶石（兩格）。"""
    letters = [c for c in 'ZYXWV' if c not in pal]
    if len(letters) < 2:
        return pal, rows
    gh = (hue if hue is not None else seed) % 1.0
    r, g, b = colorsys.hls_to_rgb(gh, 0.55, 0.9)
    r2, g2, b2 = colorsys.hls_to_rgb(gh, 0.85, 0.9)
    pal = dict(pal)
    pal[letters[0]] = '%02x%02x%02x' % (int(r * 255), int(g * 255), int(b * 255))
    pal[letters[1]] = '%02x%02x%02x' % (int(r2 * 255), int(g2 * 255), int(b2 * 255))
    grid = [list(r) for r in rows]
    cells = [(x, y) for y in range(len(grid)) for x in range(len(grid[y])) if grid[y][x] != '.']
    if not cells:
        return pal, rows
    # 挑圖形中間偏上的一格
    cx = sum(c[0] for c in cells) / len(cells)
    cy = sum(c[1] for c in cells) / len(cells)
    best = min(cells, key=lambda c: (c[0] - cx) ** 2 + (c[1] - cy + 1.5) ** 2)
    x, y = best
    grid[y][x] = letters[0]
    if x + 1 < len(grid[y]) and grid[y][x + 1] != '.':
        grid[y][x + 1] = letters[0]
    if y - 1 >= 0 and grid[y - 1][x] != '.':
        grid[y - 1][x] = letters[1]
    return pal, [''.join(r) for r in grid]


def block(name, pal, rows):
    return '\n'.join(['item ' + name] + ['  %s=%s' % (k, v) for k, v in pal.items()] + ['  ' + r for r in rows])


def main():
    text = ART.read_text(encoding='utf-8')
    # 2026-10-18：只附加、不重做——檔案裡已經有的圖（不管是手畫的還是之前產生的）一律當底圖、原樣保留
    sprites = parse(text)
    uniques = yaml.safe_load((DATA / 'uniques.yml').read_text(encoding='utf-8'))
    bases, kinds = {}, {}
    for f in (DATA / 'bases').glob('*.yml'):
        for k, v in (yaml.safe_load(f.read_text(encoding='utf-8')) or {}).items():
            if isinstance(v, dict):
                bases[k] = v.get('slot')
                # 2026-10-18：同欄位再照「種類」分（弓、弩、長棍、單手錘、矛、頭盔、戒指、赤星晶…），
                # 免得長棍拿弩的圖、珠寶拿戒指的圖
                kinds[k] = (v.get('slot'),) + tuple(sorted(t for t in (v.get('tags') or []) if t in KIND_TAGS))
    by_base, by_kind, by_slot = {}, {}, {}
    for uid, u in uniques.items():
        if isinstance(u, dict) and uid in sprites and not uid.endswith(tuple(FRAMES)):
            by_base.setdefault(u['base'], []).append(uid)
            by_kind.setdefault(kinds.get(u['base']), []).append(uid)
            by_slot.setdefault(bases.get(u['base']), []).append(uid)
    out = [MARK + '（' + __import__('datetime').date.today().isoformat() + '）']
    made = 0
    for uid, u in uniques.items():
        if not isinstance(u, dict) or u.get('retired') or uid in sprites:
            continue
        pool = by_base.get(u['base']) or by_kind.get(kinds.get(u['base'])) or by_slot.get(bases.get(u['base'])) or []
        if not pool:
            print('沒有底圖：', uid, u['base'])
            continue
        if not by_base.get(u['base']) and not by_kind.get(kinds.get(u['base'])):
            print('只找到同欄位的底圖（種類不同）：', uid, u['base'], kinds.get(u['base']))
        digest = int(hashlib.md5(uid.encode()).hexdigest(), 16)
        tpl = pool[digest % len(pool)]
        words = ' '.join(str(m.get('text', '')) for m in u.get('mods', []) if isinstance(m, dict)) + u.get('name', '')
        hue = None
        for word, h in THEMES:
            if word in words:
                hue = h
                break
        seed = (digest % 1000) / 1000.0
        for suffix in [''] + FRAMES:
            if tpl + suffix not in sprites:
                continue
            pal, rows = sprites[tpl + suffix]
            pal = recolor(pal, hue, seed)
            pal, rows = gem(pal, rows, hue, seed + 0.5)
            out.append(block(uid + suffix, pal, rows))
        made += 1
    if made:
        ART.write_text(text.rstrip('\n') + '\n\n' + '\n\n'.join(out) + '\n', encoding='utf-8')
    print('傳奇圖：新產生', made, '件')


if __name__ == '__main__':
    main()
