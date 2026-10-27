#!/usr/bin/env python3
"""產生 src/main/resources/web/currency_art.txt：23 種通貨與未切割寶石（每一級一張，右下角寫等級）的 16×16 像素圖（格式同 unique_art.txt）。

球狀的通貨（各種「石」）共用同一個球的陰影模板，換顏色與球心的記號；磨刀石、護甲碎片、製圖釘、
天穹碎片、深淵星核、鑑定卷軸、蒸餾情緒各自畫。改完直接跑：python3 tools/gen_currency_art.py
2026-10-27：可以指定專案位置與輸出檔、順便輸出星籤的預覽圖：
  python3 tools/gen_currency_art.py [--root 專案資料夾] [--out currency_art.txt 的位置] [--omen-preview 預覽.png]
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'src/main/resources/web/currency_art.txt'

# 球：16×16，字母 = 陰影層：o 外框、d 暗面、m 中間、l 亮面、h 高光；. 透明
ORB = [
    '................',
    '.....oooooo.....',
    '...oommmmmmoo...',
    '..omllmmmmmmmo..',
    '..ollhlmmmmmmo..',
    '.omlhhlmmmmmmmo.',
    '.omllmmmmmmmddo.',
    '.ommmmmmmmmmddo.',
    '.ommmmmmmmmdddo.',
    '.ommmmmmmmddddo.',
    '.ommmmmmmdddddo.',
    '..ommmmmddddddo.',
    '..oodmmdddddoo..',
    '...oodddddoo....',
    '.....oooooo.....',
    '................',
]

# 球心的記號（蓋在球上，用 1–4 的字母換成記號的顏色；. = 不畫）
GLYPHS = {
    'dot': [
        '................',
        '................',
        '................',
        '................',
        '................',
        '................',
        '.......11.......',
        '......1221......',
        '......1221......',
        '.......11.......',
        '................',
        '................',
        '................',
        '................',
        '................',
        '................',
    ],
    'plus': [
        '................',
        '................',
        '................',
        '................',
        '................',
        '.......11.......',
        '.......11.......',
        '.....111111.....',
        '.....111111.....',
        '.......11.......',
        '.......11.......',
        '................',
        '................',
        '................',
        '................',
        '................',
    ],
    'star': [
        '................',
        '................',
        '................',
        '.......1........',
        '.......1........',
        '......111.......',
        '....1111111.....',
        '.....11211......',
        '......111.......',
        '.....11.11......',
        '....1.....1.....',
        '................',
        '................',
        '................',
        '................',
        '................',
    ],
    'swirl': [
        '................',
        '................',
        '................',
        '................',
        '......1111......',
        '.....1....1.....',
        '....1..11..1....',
        '....1.1..1.1....',
        '....1.1.21.1....',
        '....1..11..1....',
        '.....1....1.....',
        '......1111......',
        '................',
        '................',
        '................',
        '................',
    ],
    'cross': [
        '................',
        '................',
        '................',
        '................',
        '....1......1....',
        '.....1....1.....',
        '......1..1......',
        '.......11.......',
        '.......11.......',
        '......1..1......',
        '.....1....1.....',
        '....1......1....',
        '................',
        '................',
        '................',
        '................',
    ],
    'eye': [
        '................',
        '................',
        '................',
        '................',
        '................',
        '.....111111.....',
        '....1......1....',
        '...1...22...1...',
        '...1...22...1...',
        '....1......1....',
        '.....111111.....',
        '................',
        '................',
        '................',
        '................',
        '................',
    ],
    'ring': [
        '................',
        '................',
        '................',
        '................',
        '......1111......',
        '.....1....1.....',
        '....1......1....',
        '....1......1....',
        '....1......1....',
        '....1......1....',
        '.....1....1.....',
        '......1111......',
        '................',
        '................',
        '................',
        '................',
    ],
    'ring2': [
        '................',
        '................',
        '................',
        '.....111111.....',
        '....1......1....',
        '...1..1111..1...',
        '...1.1....1.1...',
        '...1.1....1.1...',
        '...1.1....1.1...',
        '...1.1....1.1...',
        '...1..1111..1...',
        '....1......1....',
        '.....111111.....',
        '................',
        '................',
        '................',
    ],
    'ring3': [
        '................',
        '................',
        '....11111111....',
        '...1........1...',
        '..1..111111..1..',
        '..1.1......1.1..',
        '..1.1..22..1.1..',
        '..1.1.2222.1.1..',
        '..1.1.2222.1.1..',
        '..1.1..22..1.1..',
        '..1.1......1.1..',
        '..1..111111..1..',
        '...1........1...',
        '....11111111....',
        '................',
        '................',
    ],
    'question': [
        '................',
        '................',
        '................',
        '......1111......',
        '.....1....1.....',
        '..........1.....',
        '.........1......',
        '........1.......',
        '.......1........',
        '.......1........',
        '................',
        '.......1........',
        '................',
        '................',
        '................',
        '................',
    ],
    'wave': [
        '................',
        '................',
        '................',
        '................',
        '................',
        '....11....11....',
        '...1..1..1..1...',
        '..1....11....1..',
        '................',
        '....11....11....',
        '...1..1..1..1...',
        '..1....11....1..',
        '................',
        '................',
        '................',
        '................',
    ],
    'none': ['................'] * 16,
}

def mix(a, b, t):
    """兩個 RRGGBB 之間取 t（0 = a、1 = b）。"""
    ra, ga, ba = int(a[0:2], 16), int(a[2:4], 16), int(a[4:6], 16)
    rb, gb, bb = int(b[0:2], 16), int(b[2:4], 16), int(b[4:6], 16)
    return '%02x%02x%02x' % (round(ra + (rb - ra) * t), round(ga + (gb - ga) * t), round(ba + (bb - ba) * t))


# 球的顏色：外框最暗、暗面介於外框與中間之間、亮面、高光偏白
def shades(dark, mid, light):
    return {'o': dark, 'd': mix(mid, dark, 0.45), 'm': mid, 'l': light, 'h': mix(light, 'ffffff', 0.6)}

ORBS = {
    # 代號: (球色, 記號, 記號色 1, 記號色 2)
    'glimmer_orb':        (shades('4a5a6a', '8fa6b8', 'c8d8e6'), 'dot',      'f8fbff', 'ffffff'),
    'lustre_orb':         (shades('5a4a1a', 'c9a227', 'f0d36a'), 'plus',     'fff3c0', None),
    'bounty_orb':         (shades('2f5a2a', '5fae4a', '9fe08a'), 'star',     'fff7a0', 'ffffff'),
    'spark_orb':          (shades('6a2a10', 'e06a1a', 'ffb060'), 'cross',    'fff3c0', None),
    'turmoil_orb':        (shades('2a2a5a', '5a5ac8', '9a9aff'), 'swirl',    'e8e8ff', 'ffffff'),
    'corrupt_orb':        (shades('2a0808', '9a1818', 'e04848'), 'eye',      'ffd0d0', 'ffffff'),   # 2026-10-21：蝕變石（瓦爾寶珠）
    'fracture_orb':       (shades('4a3a20', 'b08850', 'ecd09a'), 'cross',    'fff6e0', '5a3a10'),   # 2026-10-21：定星石（破裂石）
    'annul_orb':          (shades('101014', '2a2a36', '4a4a5e'), 'ring',     '9a9ad0', None),
    'zenith_orb':         (shades('5a3a00', 'e0a010', 'ffd861'), 'star',     'ffffff', 'fff3c0'),
    'resonance_orb':      (shades('0a2a30', '1aa0b0', '7af0ff'), 'ring',     'ffd861', 'fff3c0'),   # 2026-10-18：共鳴石（青色球、金色環）
    'oracle_orb':         (shades('3a1a5a', '8a3fd0', 'c77dff'), 'eye',      'ffffff', '2a0a4a'),
    'blessing_orb':       (shades('5a4a10', 'd8c040', 'fff0a0'), 'plus',     'ffffff', None),
    'lesser_socket_orb':  (shades('1a3a4a', '2f7fa8', '7fd1ff'), 'ring',     'e8f8ff', None),
    'socket_orb':         (shades('1a3a4a', '2f7fa8', '7fd1ff'), 'ring2',    'e8f8ff', None),
    'greater_socket_orb': (shades('1a3a4a', '2f7fa8', '7fd1ff'), 'ring3',    'e8f8ff', 'ffffff'),
    'chance_orb':         (shades('5a2a4a', 'c84a9a', 'ff8ad0'), 'question', 'ffffff', None),
}

# 其他（直接畫）：palette 字母 → 顏色，接著 16 行
def _deck_rows():
    """2026-10-21：星諭牌堆：三張疊著的牌背（深藍底、金框、正面那張中間一顆星）。"""
    grid = [['.'] * 16 for _ in range(16)]

    def card(x0, y0, star):
        for y in range(y0, y0 + 12):
            for x in range(x0, x0 + 9):
                edge = x in (x0, x0 + 8) or y in (y0, y0 + 11)
                frame = x in (x0 + 1, x0 + 7) or y in (y0 + 1, y0 + 10)
                grid[y][x] = 'O' if edge else ('G' if x == x0 + 7 or y == y0 + 10 else 'F') if frame else \
                    ('b' if (x + y) % 3 == 0 else 'B')
        if star:
            cx, cy = x0 + 4, y0 + 5
            for (dx, dy) in ((0, -2), (-2, 0), (2, 0), (0, 2)):
                grid[cy + dy][cx + dx] = 's'
            for (dx, dy) in ((0, -1), (-1, 0), (1, 0), (0, 1), (0, 0)):
                grid[cy + dy][cx + dx] = 'S'

    card(6, 1, False)
    card(4, 2, False)
    card(2, 3, True)
    return [''.join(r) for r in grid]


OTHERS = {
    'stacked_deck': ({'O': '141418', 'F': 'c8a04a', 'G': '7a5a24', 'B': '1e2a5a', 'b': '2e4290', 'S': 'fff0b0', 's': 'd0a850'},
                     _deck_rows()),
    'identify_scroll': ({'a': 'e8d8a8', 'b': 'c8b078', 'c': '8a6a3a', 'd': 'fff6d8', 'e': '5a3a1a'}, [
        '................',
        '....cccccccc....',
        '...cadddddddc...',
        '...cadddddddc...',
        '...cadeeeeddc...',
        '...cadddddddc...',
        '...cadeeedddc...',
        '...cadddddddc...',
        '...cadeeeeddc...',
        '...cadddddddc...',
        '...cadeeedddc...',
        '...cadddddddc...',
        '...cbbbbbbbbc...',
        '....cccccccc....',
        '................',
        '................',
    ]),
    'whetstone': ({'a': '3a3a40', 'b': '6a6a74', 'c': '9a9aa6', 'd': 'c8c8d0', 'e': '2a2a2e'}, [
        '................',
        '................',
        '................',
        '....aaaaaaaa....',
        '...abcccccccba..',
        '...abcdddddcba..',
        '...abcdddddcba..',
        '...abccccccccba.',
        '...abbbbbbbbbea.',
        '...aeeeeeeeeeea.',
        '....aaaaaaaaaa..',
        '................',
        '................',
        '................',
        '................',
        '................',
    ]),
    'armour_scrap': ({'a': '3a3a40', 'b': '7a7a86', 'c': 'a8a8b4', 'd': 'd8d8e0', 'e': '55555f', 'r': '8a4a2a'}, [
        '................',
        '....aaaaaa......',
        '...abbbbbba.....',
        '..abcccccbba....',
        '..abcdddccbea...',
        '..abcddccbbea...',
        '..abcccbbbeea...',
        '..abbbbbeeea....',
        '...aeeeeeea.....',
        '....aaaaaa.r....',
        '..........rr....',
        '.........r......',
        '................',
        '................',
        '................',
        '................',
    ]),
    'chisel': ({'a': '2a2a2e', 'b': '8a8a96', 'c': 'c8c8d0', 'd': '5a3a1a', 'e': '8a5a2a', 'f': 'f0f0f4'}, [
        '................',
        '............aa..',
        '...........afca.',
        '..........afcba.',
        '.........afcbba.',
        '........afcbba..',
        '.......afcbba...',
        '......adebba....',
        '.....adeea......',
        '....adeea.......',
        '...adeea........',
        '..adeea.........',
        '..adda..........',
        '..aa............',
        '................',
        '................',
    ]),
    'sky_fragment': ({'a': '2a1a4a', 'b': '5a3a9a', 'c': 'a070e0', 'd': 'e0c8ff', 'e': 'ffffff', 'f': 'ffd861'}, [
        '................',
        '.......a........',
        '......aba.......',
        '......abba......',
        '.....abcba......',
        '.....abcdba.....',
        '....abcddcba....',
        '....abcdedcba...',
        '...abccdefdcba..',
        '...abccddedcba..',
        '..abcccddccba...',
        '..abbccccbba....',
        '...abbbbbba.....',
        '....aaaaaa......',
        '................',
        '................',
    ]),
    # 映星鏡（複製裝備的通貨，2026-10）：金框的橢圓手鏡，鏡面是冷藍色，左上一道反光、右下映著一顆星
    'star_mirror': ({'a': '4a2f06', 'b': 'c8961e', 'c': 'f7dd7a', 'd': '274a78', 'e': '5fa8e0', 'f': 'bfe6ff',
                     'g': 'ffffff', 'h': 'c9a7ff'}, [
        '.....aaaaaa.....',
        '...aabccccbaa...',
        '..abcddddddcba..',
        '.abcdfffeeeedca.',
        '.acdfgfeeeeeeda.',
        '.acdffeeeeeeeda.',
        '.acdfeeeeeheeda.',
        '.acdeeeeehgheda.',
        '.acdeeeeeeheeda.',
        '.abcdeeeeeeedca.',
        '..abcddddddcba..',
        '...aabccccbaa...',
        '.....aabbaa.....',
        '......abca......',
        '......abca......',
        '......aaaa......',
    ]),
    # 深淵星核（開極境的鑰匙）：裂開的暗紅球，中間燒得發白
    'abyss_core': ({'a': '120814', 'b': '3a0f2a', 'c': '7a1a3a', 'd': 'd0304a', 'e': 'ff8a6a', 'f': 'fff0d0'}, [
        '................',
        '.....aaaaaa.....',
        '...aabbbbbbaa...',
        '..abbbcbbbbbba..',
        '..abbcdcbbcbba..',
        '.abbbcdcbcdcbba.',
        '.abcccdedddcbba.',
        '.abbcdeffedcbba.',
        '.abbcdeffedccba.',
        '.abccddeedcbbba.',
        '.abbcbcddcbcbba.',
        '..abcbbcdcbbba..',
        '..abbbbbcbbbba..',
        '...aabbbbbbaa...',
        '.....aaaaaa.....',
        '................',
    ]),
    # 星幣券（2026-10）：金色的票券，左邊一排撕線，中間一顆藍白的星（贊助模組的券掛在 paper 上）
    'star_voucher': ({'o': '5a3a0c', 'l': 'fff2b8', 'y': 'ffd24a', 'd': 'c8922a', 'p': '9a6a1c', 's': '5fc8ff', 'w': 'ffffff'}, [
        '................',
        '................',
        '................',
        '.oooooooooooooo.',
        '.ollplllllslllo.',
        '.olyyyyyyywyydo.',
        '..oypyyyyswsyyo.',
        '..pyyyyyswwwsyp.',
        '..oypyyyyswsyyo.',
        '.olyyyyyyywyydo.',
        '.olypyyyyysyydo.',
        '.oddddddddddddo.',
        '.oooooooooooooo.',
        '................',
        '................',
        '................',
    ]),
}

# 蒸餾情緒：小瓶子，液體顏色不同
VIAL = [
    '................',
    '......aaaa......',
    '......abba......',
    '.......aa.......',
    '......abba......',
    '.....abccba.....',
    '....abccccba....',
    '....abccccba....',
    '....abcdddba....',
    '....abdddeba....',
    '....abddeeba....',
    '....abdeeeba....',
    '.....abeeba.....',
    '......aaaa......',
    '................',
    '................',
]
VIALS = {
    'distilled_ire':      ('ff8a7a', 'd03a2a', '8a1a10'),
    'distilled_guilt':    ('a0a8ff', '5a5ad0', '2a2a8a'),
    'distilled_greed':    ('ffe08a', 'e0a020', '8a5a10'),
    'distilled_paranoia': ('a0ffb0', '3ac060', '1a6a30'),
}


def orb_rows(colours, glyph, g1, g2):
    palette = {}
    letters = 'abcdefghijklmnopqrstuvwxyz'
    def key(colour):
        for k, v in palette.items():
            if v == colour:
                return k
        k = letters[len(palette)]
        palette[k] = colour
        return k
    rows = []
    g = GLYPHS[glyph]
    for y in range(16):
        row = ''
        for x in range(16):
            cell = ORB[y][x]
            gc = g[y][x]
            if cell == '.':
                row += '.'
            elif gc == '1' and g1:
                row += key(g1)
            elif gc == '2' and (g2 or g1):
                row += key(g2 or g1)
            else:
                row += key(colours[cell])
        rows.append(row)
    return palette, rows


# ---- 未切割寶石（2026-10）：每一級一張，右下角寫等級（技能寶石 1–20、輔助寶石第 1–3 階） ----
# 技能寶石：一大一小的星骸結晶，等級越高顏色越亮（1–5 青灰、6–10 青、11–15 藍紫、16–19 紫紅、20 金）；
# 輔助寶石：一塊圓的碎星（第 1 階白、第 2 階淡藍、第 3 階金）。數字白色、外面一圈深色，寫在左上角
# （右下角是原版顯示堆疊數量的地方，未切割寶石會疊，不要擋到）。
SHARD = [
    '................',
    '......o.........',
    '.....olo........',
    '....olhmo.......',
    '...olhlmmo..o...',
    '...olllmmo.olo..',
    '..olhlmmmdoolmo.',
    '..ollmmmmdolmmdo',
    '.olllmmmddolmdo.',
    '.ollmmmmddoodo..',
    '.olmmmmmdddo.o..',
    '..olmmmdddo.....',
    '...oommddoo.....',
    '.....oooo.......',
    '................',
    '................',
]
CHUNK = [
    '................',
    '................',
    '.....oooooo.....',
    '...oohhllmmoo...',
    '..olhhllmmmmdo..',
    '..ollllmmmmmdo..',
    '.ollllmmmmmmddo.',
    '.ollmmmmmmmmddo.',
    '.olmmmmmmmmdddo.',
    '.ommmmmmmmddddo.',
    '..ommmmmmdddddo.',
    '..oodmmdddddoo..',
    '....oodddooo....',
    '......ooo.......',
    '................',
    '................',
]
DIGITS = {
    '0': ['111', '101', '101', '101', '111'], '1': ['010', '110', '010', '010', '111'],
    '2': ['111', '001', '111', '100', '111'], '3': ['111', '001', '111', '001', '111'],
    '4': ['101', '101', '111', '001', '001'], '5': ['111', '100', '111', '001', '111'],
    '6': ['111', '100', '111', '101', '111'], '7': ['111', '001', '001', '010', '010'],
    '8': ['111', '101', '111', '101', '111'], '9': ['111', '101', '111', '001', '111'],
}
SKILL_BANDS = [  # (到幾級為止, 外框, 暗面, 中間, 亮面, 高光)
    (5, '1c2a2e', '3c6a70', '5f9aa0', '93c8cc', 'd8f4f4'),
    (10, '0e2a3a', '1f6f8f', '33a6c8', '7fdcf0', 'e0fbff'),
    (15, '1a1640', '3a3a9a', '5a63d0', '96a2f4', 'e6e9ff'),
    (19, '2e0f3a', '7a2a8a', 'b04cc0', 'e08ef0', 'fbe6ff'),
    (20, '3a2a08', 'a8781e', 'd9a933', 'f7dd7a', 'fff8dc'),
    (21, '3a0808', '8a1e1e', 'd03838', 'f78a7a', 'ffe0dc'),   # 2026-10：Lv.21（劫盜大金庫）
]
SUPPORT_TIERS = {
    1: ('3a3a40', '9a9aa8', 'c8c8d4', 'e8e8f0', 'ffffff'),
    2: ('1e3448', '5a8ab0', '8ec0e0', 'c4e4f6', 'f4fcff'),
    3: ('3a2a08', 'b0863a', 'dcb05a', 'f4d890', 'fff6dc'),
}


def number_overlay(rows, text):
    """把數字（白色、外面一圈深色）畫在 rows 的左上角；rows 是 16 個 list。"""
    x0, y0 = 1, 1
    pixels = set()
    for i, ch in enumerate(text):
        for dy, line in enumerate(DIGITS[ch]):
            for dx, bit in enumerate(line):
                if bit == '1':
                    pixels.add((x0 + i * 4 + dx, y0 + dy))
    for (x, y) in list(pixels):
        for ox in (-1, 0, 1):
            for oy in (-1, 0, 1):
                nx, ny = x + ox, y + oy
                if 0 <= nx < 16 and 0 <= ny < 16 and (nx, ny) not in pixels:
                    rows[ny][nx] = 'k'
    for (x, y) in pixels:
        rows[y][x] = 'n'


def gem_item(template, colours, text):
    o, d, m, l, h = colours
    # 圖往右下移，左上角留給數字
    shifted = ['................'] * 2 + [('.' + r)[:16] for r in template[:14]]
    rows = [list(r) for r in shifted]
    number_overlay(rows, text)
    shade = {'o': o, 'd': d, 'm': m, 'l': l, 'h': h, 'k': '14141a', 'n': 'ffffff'}
    palette = {}
    letters = 'abcdefghijklmnopqrstuvwxyz'
    out = []
    for r in rows:
        line = ''
        for c in r:
            if c == '.':
                line += '.'
                continue
            colour = shade[c]
            key = next((k for k, v in palette.items() if v == colour), None)
            if key is None:
                key = letters[len(palette)]
                palette[key] = colour
            line += key
        out.append(line)
    return palette, out


def gems():
    items = []
    for level in range(1, 22):
        band = next(b for b in SKILL_BANDS if level <= b[0])
        items.append(('uncut_skill_%d' % level, *gem_item(SHARD, band[1:], str(level))))
    for tier, colours in SUPPORT_TIERS.items():
        items.append(('uncut_support_%d' % tier, *gem_item(CHUNK, colours, str(tier))))
    return items


# ---- 2026-10：精髓、催化劑、星籤、劫盜道具、揭露石（以前用原版物品） ----
# 共用：字母 o 外框、d 暗、m 中、l 亮、h 高光、r 記號
def shaded(template, colours):
    o, d, m, l, h = colours[:5]
    r = colours[5] if len(colours) > 5 else 'ffffff'
    shade = {'o': o, 'd': d, 'm': m, 'l': l, 'h': h, 'r': r}
    palette = {}
    letters = 'abcdefghijklmnopqrstuvwxyz'
    out = []
    for row in template:
        line = ''
        for c in row:
            if c == '.':
                line += '.'
                continue
            colour = shade[c]
            key = next((k for k, v in palette.items() if v == colour), None)
            if key is None:
                key = letters[len(palette)]
                palette[key] = colour
            line += key
        out.append(line)
    return palette, out

ESSENCE = [  # 一大兩小的結晶簇
    '................',
    '.......o........',
    '......olo.......',
    '.....olhmo......',
    '.....olhmo..o...',
    '..o..olmmo.olo..',
    '.olo.olmmdoolmo.',
    '.olmoolmmdolmmo.',
    '.olmdolmmdolmdo.',
    '.olmdolmmdolmdo.',
    '..omdolmmdoomo..',
    '..oodolmmddoo...',
    '...ooommmdoo....',
    '....oooooooo....',
    '................',
    '................',
]
ESSENCES = {  # 代號: (外框, 暗, 中, 亮, 高光)
    'essence_body':     ('3a0a10', '8a1a2a', 'd0303a', 'ff8080', 'ffe0e0'),
    'essence_mind':     ('0a1a3a', '1a4a9a', '3a7ae0', '8ab8ff', 'e8f2ff'),
    'essence_flame':    ('3a1404', 'a8400a', 'f07a1a', 'ffc060', 'fff0c0'),
    'essence_frost':    ('0a2a3a', '2a7aa0', '5fc8f0', 'b0ecff', 'f0fcff'),
    'essence_storm':    ('3a3404', 'a89a10', 'f0d82a', 'fff08a', 'fffbe0'),
    'essence_ruin':     ('1a0a2a', '4a1a7a', '8a3ac8', 'c88aff', 'f4e6ff'),
    'essence_battle':   ('2a1a0a', '7a5a2a', 'b8904a', 'e8c88a', 'fff4dc'),
    'essence_sorcery':  ('1a0a3a', '3a2a9a', '6a5ae0', 'a8a0ff', 'eeeaff'),
    'essence_haste':    ('0a2a14', '1a7a3a', '3ac060', '8af0a0', 'e6ffec'),
    'essence_infinite': ('2a2a2a', '6a6a7a', 'a8a8c0', 'e0e0f0', 'ffffff'),
    'essence_seeking':  ('2a0a2a', '7a1a6a', 'c83aa8', 'ff8ae0', 'ffe6f8'),
    'essence_torment':  ('100808', '3a1414', '6a2020', 'a04a4a', 'e08a8a'),
    'essence_ward':     ('0a2030', '2a6a8a', '6ab8d8', 'b8f0ff', 'ffffff'),   # 2026-10 能盾星魄：淡青的護盾光
}
CATALYST = [  # 圓底燒瓶
    '................',
    '......oooo......',
    '.......oho......',
    '.......olo......',
    '.......olo......',
    '......olllo.....',
    '.....olhllmo....',
    '....olhmmmmmo...',
    '....olmrrrrmo...',
    '...olmrrrrrrmo..',
    '...olrrrrrrrdo..',
    '...omrrrrrrrdo..',
    '....odrrrrrdo...',
    '.....oddddoo....',
    '......oooo......',
    '................',
]
CATALYSTS = {  # 代號: 液體顏色（瓶子固定淡藍玻璃）
    'catalyst_resist': 'e0a020', 'catalyst_life': 'd0303a', 'catalyst_attack': 'c86a2a', 'catalyst_caster': '6a5ae0',
    'catalyst_crit': 'f0d82a', 'catalyst_elemental': '3ab0e0', 'catalyst_phys_chaos': '8a3ac8', 'catalyst_attribute': '5fbf5f',
    'catalyst_speed': '3ac0a0', 'catalyst_defence': '8a8aa0',
}
# 2026-10-21：石板（以前星籤畫在這上面，2026-10-27 星籤改畫成籤，見下面的 OMEN_TAG；現在只剩先驅石板用）。
OMEN = [  # 石板（記號另外蓋在中間 8×8）
    '................',
    '................',
    '....oooooooo....',
    '...ollllllmmo...',
    '...olhllllmmo...',
    '...ollllllmmo...',
    '...ollllllmmo...',
    '...ollllllmdo...',
    '...ollllllmdo...',
    '...olllllmmdo...',
    '...ollmmmmddo...',
    '...olmmmmdddo...',
    '...ommmmddddo...',
    '....oooooooo....',
    '................',
    '................',
]


# 2026-10-21：先驅石板（AtlasTablets）：同一塊石板，底色一律青灰，記號照種類
TABLET_GLYPHS = {
    'shrine': ['...rr...', '..rrrr..', '...rr...', '...rr...', '...rr...', '..rrrr..', '.rrrrrr.', '........'],
    'strongbox': ['........', '.rrrrrr.', '.r....r.', '.rrrrrr.', '.r.rr.r.', '.r....r.', '.rrrrrr.', '........'],
    'breach': ['...r....', '...r....', '..r.....', '..r.....', '...r....', '....r...', '....r...', '...r....'],
    'exile': ['..rrrr..', '.r....r.', '.r.rr.r.', '.r....r.', '..rrrr..', '...rr...', '..rrrr..', '.rrrrrr.'],
    'abyss': ['........', 'r......r', 'rr....rr', '.rr..rr.', '..rrrr..', '...rr...', '........', '........'],
    'ritual': ['...rr...', '..r..r..', '.r....r.', '.r....r.', '..r..r..', '...rr...', '...rr...', '..rrrr..'],
    'delirium': ['rr....rr', '.rr..rr.', '..rrrr..', '...rr...', '..rrrr..', '.rr..rr.', 'rr....rr', '........'],
    'boss': ['r..rr..r', 'r.rrrr.r', 'rrrrrrrr', 'rrrrrrrr', '.rrrrrr.', '.rrrrrr.', '........', '........'],
    'bounty': ['...rr...', '..rrrr..', '.rrrrrr.', '.rrrrrr.', '..rrrr..', '...rr...', '...rr...', '..rrrr..'],
}
_TABLET_SLAB = ('14201c', '203428', '2c4838', '3a5c48', '5a8068')
TABLET_COLOURS = {'shrine': 'ffe9a0', 'strongbox': 'ffd24a', 'breach': 'c77dff', 'exile': 'ffff77', 'abyss': '5ad0c8',
                  'ritual': 'ff6a5a', 'delirium': 'c8d0e0', 'boss': 'ff9a4a', 'bounty': '8af08a'}


def tablet_rows(glyph):
    rows = [list(r) for r in OMEN]
    for y, line in enumerate(TABLET_GLYPHS[glyph]):
        for x, c in enumerate(line):
            if c == 'r':
                rows[4 + y][4 + x] = 'r'
    return [''.join(r) for r in rows]



# 2026-10-27 站長：「星籤 我希望用材質包去畫」：26 種星籤每種一張自己的圖（這次之前只有 6 種有圖、畫在石板上，其他的照原版物品）。
#   造型：一張掛著繩結的「籤」（符紙）；底色照稀有度（OmenRarity：普通銀灰、稀有藍、珍貴紫、極稀有金褐，跟 omen.weight 同一套門檻），
#   頂端的色帶是「哪一顆石頭觸發」（至臻金、豐饒綠、湮滅灰紫、亂流藍、星火橘、蝕變紅、天啟紫、僥倖粉、星魄青、揭露淡紫），
#   中間的記號是作用：← 前綴、→ 後綴、«／» 星火（前綴／後綴最多）、兩個＋ 雙詞綴、＝ 同類、燒瓶 催化、刀 去劣、
#   盾＋✕（盾在保住的那一邊、✕ 在拿掉的那一邊）去前綴／去後綴、雙 ✕ 雙湮滅、太陽 光明、滴落 必變、羽毛 恩澤、光環 聖化、
#   盾 不碎、王冠 古代（變傳奇）、眼睛＋箭頭 揭露前綴／後綴。稀有度的外框由資源包另外畫（UiPack 的 frame），圖裡不畫。
OMEN_TAG = [  # 字母：o 外框、d 暗、m 中、l 亮、h 高光、a 頂端色帶、c 繩結；中間 10×10（第 4–13 列、第 3–12 行）放記號
    '.......cc.......',
    '......c..c......',
    '.oooooooooooooo.',
    '.ohaaaaaaaaaado.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.olmmmmmmmmmmdo.',
    '.oddddddddddddo.',
    '..oooooooooooo..',
]
_TAG_AREA = (4, 3, 10, 10)  # 記號區：起始列、起始行、高、寬（記號置中）

# 記號（大小不限，最多 10×10，置中）：r 主色、s 主色的暗色、w 亮色、x 拿掉（✕）、k 銀（盾、刀刃、玻璃）、n 握柄
_SHIELD_KEEP_LEFT = [  # 左邊盾（保住前綴）、右邊 ✕（拿掉後綴）
    'kkkkk.....',
    'krrrk.x..x',
    'krwrk..xx.',
    'krrrk..xx.',
    'krrrk.x..x',
    '.krk......',
    '..k.......',
]
OMEN_MARKS = {
    'left': ['...r....', '..rr....', '.rrrrrrr', 'rrrrrrrr', '.rrrrrrr', '..rr....', '...r....'],
    'right': ['....r...', '....rr..', 'rrrrrrr.', 'rrrrrrrr', 'rrrrrrr.', '....rr..', '....r...'],
    'double_left': ['...r...r', '..rr..rr', '.rr..rr.', 'rr..rr..', '.rr..rr.', '..rr..rr', '...r...r'],
    'double_right': ['r...r...', 'rr..rr..', '.rr..rr.', '..rr..rr', '.rr..rr.', 'rr..rr..', 'r...r...'],
    'plus2': ['..r.....', '..r.....', 'rrrrr...', '..r..r..', '..r..r..', '...rrrrr', '.....r..', '.....r..'],
    'same': ['.r....r.', 'rwr..rwr', '.r....r.', '........', '.rrrrrr.', '........', '.rrrrrr.'],
    'flask': ['..kkkk..', '...kk...', '...kk...', '..krrk..', '.krwrrk.', 'krrrrrrk', 'krrrrrrk', '.kkkkkk.'],
    'knife': ['.......kw', '......kkw', '.....kkk.', '....kkk..', '...kkk...', '..nn.....', '.nn......', 'nn.......'],
    'keep_left': _SHIELD_KEEP_LEFT,
    'keep_right': [row[::-1] for row in _SHIELD_KEEP_LEFT],
    'cross2': ['xx..xx....', '.xxxx.....', '..xx......', '.xxxxxx..x', 'xx..xxxxx.', '......xx..', '.....xxxx.', '....xx..xx'],
    'sun': ['r...r...r', '.r.....r.', '...rrr...', '..rrwrr..', 'r.rwwwr.r', '..rrwrr..', '...rrr...', '.r.....r.', 'r...r...r'],
    'drip': ['ssssssss', 'srrwrrrs', 'srrrrrrs', '.srrrrs.', '.sr..rs.', '..r..r..', '..r.....', '..s.....'],
    'feather': ['......ww', '....wwrw', '...wrrw.', '..wrrw..', '.wrrw...', '.rrw....', '.rw.....', 'r.......'],
    'halo': ['..wwww..', '.w....w.', '..wwww..', '...rr...', '..rrrr..', '.rrrrrr.', '..rrrr..', '...rr...'],
    'shield': ['kkkkkkkk', 'krrrrrrk', 'krwrrrrk', 'krrrrrrk', 'krrrrrrk', '.krrrrk.', '..krrk..', '...kk...'],
    'crown': ['r...r...r', 'rr.rrr.rr', 'rrrrrrrrr', 'rrrrrrrrr', '.rwrrrwr.', '.rrrrrrr.'],
    'eye_left': ['..rrrrr..', '.r.www.r.', 'r.wwsww.r', '.r.www.r.', '..rrrrr..', '.........', '.w.......', 'wwwwwwwww', '.w.......'],
    'eye_right': ['..rrrrr..', '.r.www.r.', 'r.wwsww.r', '.r.www.r.', '..rrrrr..', '.........', '.......w.', 'wwwwwwwww', '.......w.'],
}
# 底色：照稀有度（跟 tw.exilecore.currency.OmenRarity 的門檻一樣：權重 60 以上普通、25～59 稀有、6～24 珍貴、5 以下極稀有）
OMEN_BODY = {  # (外框, 暗, 中, 亮, 高光)
    'common':   ('16181c', '2c3036', '3c424a', '525a64', '7a8490'),
    'rare':     ('0a1424', '14284a', '1e3a66', '2c5288', '4a74b0'),
    'precious': ('180c24', '2c1844', '3e2460', '563484', '7a52aa'),
    'exalted':  ('1e1206', '3a240c', '543612', '704a1a', '9a6a2a'),
}
OMEN_RARITY_COLOUR = {'common': 'c9d3de', 'rare': '4fa8ff', 'precious': 'c77dff', 'exalted': 'ffb43c'}  # 預覽圖用
# 頂端色帶：哪一顆石頭觸發（omen.triggers）
OMEN_TRIGGER_BAND = {
    'ADD_AFFIX_RARE': 'ffd861', 'MAGIC_TO_RARE': '9fe08a', 'REMOVE_AFFIX': '9a9ad0', 'REMOVE_AND_ADD': '7a8aff',
    'UPGRADE_TO_RARE': 'ffa050', 'CORRUPT': 'e04848', 'REROLL_VALUES': 'c77dff', 'CHANCE': 'ff8ad0',
    'ESSENCE': '7ae0d0', 'UNVEIL': 'e0b0ff',
}
# 代號: (記號, 主色, 其他顏色)；去前綴／去後綴的盾在哪一邊照 effect（KEEP_PREFIXES＝盾在左）
OMENS = {
    'omen_sinistral':                 ('left', 'ffd861', {}),
    'omen_dextral':                   ('right', 'ffd861', {}),
    'omen_sinistral_coronation':      ('left', '9fe08a', {}),
    'omen_dextral_coronation':        ('right', '9fe08a', {}),
    'omen_abundance':                 ('plus2', 'ffd861', {}),
    'omen_homogenising_exaltation':   ('same', 'ffd861', {}),
    'omen_homogenising_coronation':   ('same', '9fe08a', {}),
    'omen_catalysing_exaltation':     ('flask', 'ffd861', {}),
    'omen_whittling':                 ('knife', 'ff5a4a', {}),
    'omen_sinistral_guard':           ('keep', '9a9ad0', {}),
    'omen_dextral_guard':             ('keep', '9a9ad0', {}),
    'omen_dextral_erasure':           ('keep', '7a8aff', {}),
    'omen_sinistral_erasure':         ('keep', '7a8aff', {}),
    'omen_greater_annulment':         ('cross2', 'c77dff', {'x': 'c77dff'}),
    'omen_light':                     ('sun', 'ffe066', {'w': 'fffbe0'}),
    'omen_sinistral_alchemy':         ('double_left', 'ffa050', {}),
    'omen_dextral_alchemy':           ('double_right', 'ffa050', {}),
    'omen_corruption':                ('drip', 'd02828', {'s': '140404', 'w': 'ff9090'}),
    'omen_blessed':                   ('feather', 'ffe9a0', {}),
    'omen_sanctification':            ('halo', 'ffffff', {'w': 'ffd861'}),
    'omen_chance':                    ('shield', 'ff8ad0', {}),
    'omen_ancients':                  ('crown', 'e0902a', {'w': '7af0ff'}),
    'omen_sinistral_crystallisation': ('keep', '7ae0d0', {}),
    'omen_dextral_crystallisation':   ('keep', '7ae0d0', {}),
    'omen_sinistral_necromancy':      ('eye_left', 'e0b0ff', {'s': '2a0a3a'}),
    'omen_dextral_necromancy':        ('eye_right', 'e0b0ff', {'s': '2a0a3a'}),
}


def omen_specs():
    """currencies.yml 裡每一種星籤（operation: OMEN）：代號 → (名稱, 觸發, 效果, 權重)。不裝 yaml 套件：照縮排找。"""
    import re
    specs = {}
    current = None
    name = None
    path = ROOT / 'src/main/resources/data/currencies.yml'
    for line in path.read_text(encoding='utf-8').split('\n'):
        top = re.match(r'^([a-z0-9_]+):\s*(#.*)?$', line)
        if top:
            current, name = top.group(1), None
            continue
        m = re.match(r'^  name:\s*(.+?)\s*(#.*)?$', line)
        if m and current:
            name = m.group(1).strip('"')
        m = re.match(r'^  omen:\s*\{\s*triggers:\s*\[([A-Z_, ]+)\],\s*effect:\s*([A-Z_]+),\s*weight:\s*(\d+)', line)
        if m and current:
            specs[current] = (name, m.group(1).split(',')[0].strip(), m.group(2), int(m.group(3)))
    return specs


def omen_rarity(weight):
    return 'common' if weight >= 60 else 'rare' if weight >= 25 else 'precious' if weight >= 6 else 'exalted'


def omen_item(cid, spec):
    _, trigger, effect, weight = spec
    mark, main, extra = OMENS[cid]
    if mark == 'keep':
        mark = 'keep_left' if effect == 'KEEP_PREFIXES' else 'keep_right'
    o, d, m, l, h = OMEN_BODY[omen_rarity(weight)]
    colours = {'o': o, 'd': d, 'm': m, 'l': l, 'h': h, 'a': OMEN_TRIGGER_BAND.get(trigger, 'c9d3de'), 'c': 'd8c8a0',
               'z': mix(m, '000000', 0.55),  # 記號的影子（右下各一格）
               'r': main, 's': mix(main, '000000', 0.5), 'w': 'ffffff', 'x': 'ff5a4a', 'k': 'dfe6ee', 'n': '8a5a2a'}
    colours.update(extra)
    glyph = OMEN_MARKS[mark]
    top, left, height, width = _TAG_AREA
    gh, gw = len(glyph), max(len(r) for r in glyph)
    assert gh <= height and gw <= width, (cid, gh, gw)
    oy, ox = top + (height - gh) // 2, left + (width - gw) // 2
    rows = [list(r) for r in OMEN_TAG]
    cells = {(oy + y, ox + x): c for y, line in enumerate(glyph) for x, c in enumerate(line) if c != '.'}
    for (y, x) in cells:  # 先畫影子，記號再蓋上去
        sy, sx = y + 1, x + 1
        if (sy, sx) not in cells and top <= sy < top + height and left <= sx < left + width:
            rows[sy][sx] = 'z'
    for (y, x), c in cells.items():
        rows[y][x] = c
    palette, out = {}, []
    letters = 'abcdefghijklmnopqrstuvwxyz'
    for row in rows:
        line = ''
        for c in row:
            if c == '.':
                line += '.'
                continue
            colour = colours[c]
            key = next((k for k, v in palette.items() if v == colour), None)
            if key is None:
                key = letters[len(palette)]
                palette[key] = colour
            line += key
        out.append(line)
    return palette, out


def omens():
    specs = omen_specs()
    missing = sorted(set(specs) - set(OMENS))
    if missing:
        print('沒有畫的星籤（照原版物品）：', ', '.join(missing))
    return [(cid, *omen_item(cid, specs[cid])) for cid in OMENS if cid in specs]


def omen_preview(path, scale=8):
    """2026-10-27：星籤的預覽圖（放大 scale 倍、排成格子、底下標名稱與稀有度），給站長看。要 Pillow。"""
    from PIL import Image, ImageDraw, ImageFont
    specs = omen_specs()
    items = omens()
    cols = 6
    cell_w, cell_h = 16 * scale + 80, 16 * scale + 56
    rows = (len(items) + cols - 1) // cols
    img = Image.new('RGB', (cols * cell_w + 20, rows * cell_h + 20), (24, 24, 28))
    draw = ImageDraw.Draw(img)
    font = None
    for candidate in ('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc', '/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc',
                      'C:/Windows/Fonts/msjh.ttc', '/System/Library/Fonts/PingFang.ttc'):
        try:
            font = ImageFont.truetype(candidate, 16, index=2 if candidate.endswith('NotoSansCJK-Regular.ttc') else 0)
            break
        except OSError:
            continue
    font = font or ImageFont.load_default()
    for i, (cid, palette, art) in enumerate(items):
        name, _, _, weight = specs[cid]
        rarity = omen_rarity(weight)
        frame = tuple(int(OMEN_RARITY_COLOUR[rarity][j:j + 2], 16) for j in (0, 2, 4))
        x0 = 10 + (i % cols) * cell_w + 40
        y0 = 10 + (i // cols) * cell_h + 8
        draw.rectangle([x0 - 4, y0 - 4, x0 + 16 * scale + 3, y0 + 16 * scale + 3], fill=(44, 44, 52), outline=frame, width=2)
        for y, line in enumerate(art):
            for x, c in enumerate(line):
                if c != '.':
                    v = palette[c]
                    draw.rectangle([x0 + x * scale, y0 + y * scale, x0 + (x + 1) * scale - 1, y0 + (y + 1) * scale - 1],
                                   fill=tuple(int(v[j:j + 2], 16) for j in (0, 2, 4)))
        label = '%s（%s）' % (name, {'common': '普通', 'rare': '稀有', 'precious': '珍貴', 'exalted': '極稀有'}[rarity])
        tw = draw.textlength(label, font=font)
        draw.text((x0 + 8 * scale - tw / 2, y0 + 16 * scale + 10), label, fill=frame, font=font)
    img.save(path)
    print('wrote', path)



SCROLL = [  # 捲起來的契約
    '................',
    '..oooooooooooo..',
    '.ohllllllllllmo.',
    '..oooooooooooo..',
    '...olllllllmo...',
    '...olrrrrrlmo...',
    '...ollllllldo...',
    '...olrrrrllmo...',
    '...ollllllldo...',
    '...olrrrrrlmo...',
    '...ollllllldo...',
    '...ollllrrlmo...',
    '..oooooooooooo..',
    '.ohllllllllllmo.',
    '..oooooooooooo..',
    '................',
]
KEY = [
    '................',
    '....oooo........',
    '...ohllmo.......',
    '..ohlrrlmo......',
    '..olr..rmo......',
    '..olr..rmo......',
    '..omlrrmdo......',
    '...omddmoo......',
    '....oommloo.....',
    '......oomlo.....',
    '.......omloo....',
    '........omlmo...',
    '.......oomloo...',
    '........omlmo...',
    '.........ooo....',
    '................',
]
RUNE = [  # 揭露石：一塊發光符文的紫色石頭
    '................',
    '......oooo......',
    '....oohllmoo....',
    '...ohllllmmmo...',
    '..ohllrrrmmmdo..',
    '..ollrlllrmmdo..',
    '.ollllrrrmmmddo.',
    '.olllllllrmmddo.',
    '.ollllrrrmmmddo.',
    '..ommlllmmmddo..',
    '..ommmmmmmdddo..',
    '...ommmmdddoo...',
    '....oodddoo.....',
    '......ooo.......',
    '................',
    '................',
]
CUT_GEM = [  # 技能寶石：切割好的八角形寶石（有刻面）
    '................',
    '.....oooooo.....',
    '....ohhllmmo....',
    '...ohlhllmmdo...',
    '..ohllhlmmmddo..',
    '..ollllmmmmddo..',
    '..oddddddddddo..',
    '..ollmmmmmdddo..',
    '..olmmmmmmmddo..',
    '...olmmmmmddo...',
    '....olmmmddo....',
    '.....olmmdo.....',
    '......olmo......',
    '.......oo.......',
    '................',
    '................',
]
ROUND_GEM = [  # 輔助寶石：鑲在金屬環裡的圓形寶石
    '................',
    '.....rrrrrr.....',
    '...rrooooooorr..',
    '..rroohhllmoorr.',
    '..roohlllmmmdor.',
    '.rrohllmmmmmddor',
    '.rrollmmmmmmddor',
    '.rrolmmmmmmmddor',
    '.rrolmmmmmmdddor',
    '.rroommmmmdddoor',
    '..roodmmmdddoor.',
    '..rrooddddddorr.',
    '...rrooooooorr..',
    '.....rrrrrrr....',
    '................',
    '................',
]
GEMS_CUT = {
    'gem_skill':   (CUT_GEM,   ('0a1a3a', '1a3a9a', '3a6ae0', '8ab0ff', 'eaf2ff')),
    'gem_support': (ROUND_GEM, ('1a2a2a', '2a6a6a', '4ab0a8', '9ae8dc', 'f0fffc', 'b8902e')),
}
# 2026-10-18：事件巔峰的入場券（鑰匙：圓環把手＋齒），每個事件一個顏色；共鳴石用 ORBS（雙色）
TICKET_KEY = [
    '................',
    '....ooo.........',
    '...olhlo........',
    '..olhmmlo.......',
    '..olmddmo.......',
    '..olmddmo.......',
    '...olmmlo.......',
    '....ooddo.......',
    '......odmo......',
    '......odmmo.....',
    '......oddmmo....',
    '......oddorro...',
    '......oddoro....',
    '......oddorro...',
    '.......ooo.oo...',
    '................',
]
TICKETS = {
    'ticket_shrine':    ('3a2a08', 'a07a1a', 'e8c04a', 'fff0a0', 'ffffff', 'f5b942'),   # 神龕：金
    'ticket_strongbox': ('2a1a08', '7a4a1a', 'c88a3a', 'f0c070', 'fff4d0', 'ffa040'),   # 寶箱：銅
    'ticket_breach':    ('1a0828', '4a1a7a', '8a3ad8', 'cf8aff', 'f0d8ff', 'ff6ad8'),   # 裂隙：紫
    'ticket_exile':     ('2a0808', '7a1818', 'c83a2a', 'ff7a5a', 'ffd0c0', 'ffd23a'),   # 流寇：紅
    'ticket_ritual':    ('180404', '5a0a0a', 'a81a1a', 'e05050', 'ffb0b0', '40ff80'),   # 祭壇：血紅
    'ticket_delirium':  ('18181c', '4a4a58', '8a8aa0', 'd0d0e0', 'ffffff', 'ff80ff'),   # 幻霧：灰白
    'ticket_abyss':     ('04101c', '103a50', '2a7aa0', '5ec4ff', 'c0f0ff', '9dff7a'),   # 深淵：深藍
}

EXTRA = {
    'heist_contract':   (SCROLL, ('3a2a14', '8a6a3a', 'c8a878', 'f0e0c0', 'fffaf0', '6a2a14')),
    'heist_blueprint':  (SCROLL, ('0a1a3a', '1a4a8a', '2a6ab0', '3a7ac8', '8ab8f0', 'e8f4ff')),
    'heist_finale_key': (KEY,    ('3a2a08', 'a8781e', 'd9a933', 'f7dd7a', 'fff8dc', '3a2a08')),
    'unveil_stone':     (RUNE,   ('1a0a2a', '3a1a5a', '6a3a9a', '9a6ac8', 'd8b8ff', 'f0d0ff')),
    # 開服活動的素材（2026-10）
    'event_swarm_shard': (ESSENCE, ('0a2a2a', '1a6a6a', '2ab0a0', '7ae8d8', 'e0fff8')),
    'event_titan_core':  (RUNE,    ('2a1a04', '7a4a10', 'd09020', 'ffd060', 'fff4c0', 'ffffff')),
    # 巔峰（萬界之巔）的入場碎片（2026-10）：四塊碎星，顏色照掉落的終局 Boss
    'fragment_crucible': (SHARD, ('3a1404', 'a8400a', 'f08a1a', 'ffd060', 'fff4c0')),   # 曜帝熔爐：金橘
    'fragment_frost':    (SHARD, ('0a2a3a', '2a7aa0', '5fc8f0', 'b0ecff', 'f6fdff')),   # 永凍王座：冰藍
    'fragment_bone':     (SHARD, ('1a1a14', '6a6450', 'b8b08a', 'e8e2c8', 'ffffff')),   # 冥河渡口：骨白
    'fragment_ember':    (SHARD, ('2a0606', '7a1414', 'c8301a', 'ff7a3a', 'ffd0a0')),   # 餘燼堡壘：暗紅
}


# 符文（2026-10）：一塊刻了記號的石板，記號照元素上色（r）
RUNE_SLAB = [
    '................',
    '.....oooooo.....',
    '...oohllllmoo...',
    '..ohllllllmmmo..',
    '..ollllllmmmmo..',
    '.ohllllllmmmmdo.',
    '.olllllllmmmmdo.',
    '.olllllmmmmmddo.',
    '.ollllmmmmmmddo.',
    '.olllmmmmmmmddo.',
    '.olmmmmmmmmdddo.',
    '..ommmmmmmddddo.',
    '..ommmmmmdddoo..',
    '...oommdddoo....',
    '.....ooooo......',
    '................',
]
RUNE_GLYPHS = {  # 8×8，放在石板第 4~11 行、第 4~11 欄
    'iron':  ['...rr...', '..rrrr..', '...rr...', '.rrrrrr.', '...rr...', '...rr...', '..rrrr..', '........'],
    'ember': ['....r...', '...rr...', '..rrr.r.', '..rrrrr.', '.rrrrrr.', '.rrrrrr.', '..rrrr..', '........'],
    'frost': ['...r....', '.r.r.r..', '..rrr...', 'rrrrrrr.', '..rrr...', '.r.r.r..', '...r....', '........'],
    'storm': ['....rr..', '...rr...', '..rr....', '.rrrrr..', '...rr...', '..rr....', '.rr.....', '........'],
    'mind':  ['..rrrr..', '.r....r.', 'r..rr..r', 'r.rrrr.r', 'r..rr..r', '.r....r.', '..rrrr..', '........'],
    'swift': ['........', 'rr..rr..', '.rr..rr.', '..rr..rr', '.rr..rr.', 'rr..rr..', '........', '........'],
}
RUNE_COLOURS = {
    'iron': 'e0e4ec', 'ember': 'ff8a2a', 'frost': '7fe0ff', 'storm': 'ffe04a', 'mind': '8ab0ff', 'swift': '9affc0',
}
VOUCHER = [  # 蓋了火漆印的券
    '................',
    '................',
    '.oooooooooooooo.',
    '.ollllllllllllo.',
    '.olrrrrrrrrrlmo.',
    '.ollllllllllmmo.',
    '.olrrrrrrlllmmo.',
    '.ollllllllllmmo.',
    '.olrrrrlllodddo.',
    '.ollllllllohhdo.',
    '.olmmmmmmmodhdo.',
    '.ommmmmmmmmoddo.',
    '.oooooooooooooo.',
    '................',
    '................',
    '................',
]


# ---- 2026-10-21 星諭卡：卡框照稀有度上色（跟裝備的稀有度同色：常見＝普通白、中階＝魔法藍、稀有＝稀有黃、追逐＝傳奇橘），
#   中間 10×9 是每張卡自己的圖，下面一條是卡名的紙條。卡的等級從 data/cards.yml 讀（改了等級重跑就會換框）。
CARD_FRAME = [  # O 外框、F 卡框（稀有度色）、G 卡框暗面、* 卡圖（10×9）、S 紙條、s 紙條上的字、h 追逐卡的角
    '..OOOOOOOOOOOO..',
    '.OFFFFFFFFFFFFO.',
    '.OF**********GO.',
    '.OF**********GO.',
    '.OF**********GO.',
    '.OF**********GO.',
    '.OF**********GO.',
    '.OF**********GO.',
    '.OF**********GO.',
    '.OF**********GO.',
    '.OF**********GO.',
    '.OFGGGGGGGGGGGO.',
    '.OFSSsSsssSsSGO.',
    '.OFSSSSSSSSSSGO.',
    '.OGGGGGGGGGGGGO.',
    '..OOOOOOOOOOOO..',
]
CARD_RARITY = {  # cards.yml 的 tier → (卡框, 卡框暗面)；顏色同 Rarity.java
    'common': ('ffffff', '9a9a9a'),
    'mid': ('8888ff', '4a4aa8'),
    'rare': ('ffff77', 'a8a830'),
    'chase': ('af6025', '6a3410'),
}
CARD_ART = {  # 代號: (底色, {a: 色, b: 色, c: 色, d: 色}, 10×9 的圖；. = 底色)
    'card_tide_mark': ('16324e', {'a': '9ad8ff', 'b': 'd8c08a', 'c': 'a8905a'}, [
        '..........', '.aaa...aaa', 'a...a.a...', '.....a....', '..........',
        '.aaa...aaa', 'a...a.a...', 'bbbbbbbbbb', 'bcbbbbcbbb']),
    'card_moss_whisper': ('14261a', {'a': '6ad05a', 'b': '3a8a3a', 'c': '8a8a90', 'd': '5a5a62'}, [
        '..........', '....aa....', '..aabbaa..', '.abaaaaba.', '.cccccccc.',
        'cccdcccccc', 'cccccccdcc', 'ccdccccccc', 'dddddddddd']),
    'card_ash_ledger': ('241612', {'a': 'e8dcc0', 'b': '6a5a4a', 'c': 'ff8a2a', 'd': '5a5050'}, [
        '..........', 'aaaaa.aadd', 'abbba.addc', 'aaaaa.aadd', 'abbba.adcd',
        'aaaaa.addd', 'abbba.addc', 'aaaaa.aadd', '..........']),
    'card_ferry_ticket': ('1a3a3a', {'a': 'f0e6c8', 'b': '7a6a50', 'c': 'c8303a'}, [
        '..........', 'aaaaaaaaaa', 'abbbbbaaca', 'aaaaaaacca', '.abbbbacc.',
        'aaaaaaacca', 'abbbbbaaca', 'aaaaaaaaaa', '..........']),
    'card_wanted_poster': ('2a1e14', {'a': 'e0cc98', 'b': 'd8a878', 'c': '3a2a1a', 'd': 'a83030'}, [
        'aaaaaaaaaa', 'aaaccccaaa', 'aacbbbbcaa', 'aabcbbcbaa', 'aabbbbbbaa',
        'aaabddbaaa', 'aaaaaaaaaa', 'adddddddda', 'aaaaaaaaaa']),
    'card_pine_hunter': ('0e1a2e', {'a': '2a8a4a', 'b': '6a4a2a', 'c': 'ffe07a'}, [
        '.......c..', '....a.ccc.', '...aaa.c..', '..aaaaa...', '...aaa....',
        '.aaaaaaa..', 'aaaaaaaaa.', '....b.....', '....b.....']),
    'card_miner_note': ('1e1a18', {'a': 'e8dcc0', 'b': '8a5a2a', 'c': 'a8b0b8', 'd': '6a5a4a'}, [
        'cccccc....', '.c..b.....', '.....b....', '......b...', '..aaaa.b..',
        '..adda..b.', '..aaaa....', '..adda....', '..aaaa....']),
    'card_tomb_oath': ('181820', {'a': 'ffe07a', 'b': '3a3a2a', 'c': '8a8a98'}, [
        '...cccc...', '..cccccc..', '.cccaaccc.', '.caaaaaac.', '.cccaaccc.',
        '.cccaaccc.', '.cccccccc.', '.cccccccc.', 'bbbbbbbbbb']),
    'card_spore_sac': ('10202a', {'a': '5af0e0', 'b': 'c8e8d8', 'c': 'b0ff8a'}, [
        '.c.....c..', '....c.....', '..aaaaaa..', '.aaacaaaa.', 'aaaaaacaaa',
        '....bb....', '.c..bb..c.', '....bb....', '...bbbb...']),
    'card_war_banner': ('2a1410', {'a': 'c8303a', 'b': '8a6a4a', 'c': 'ffa02a'}, [
        'b...c.....', 'baaaacc...', 'baaaaacc..', 'baacaaaa..', 'baaaaaa...',
        'baaaa.....', 'b.........', 'b.........', 'b.........']),
    'card_tide_oracle': ('102a40', {'a': 'f0f6ff', 'b': '6ac8ff', 'c': '2a5aa0'}, [
        '..........', '...aaaa...', '.aa.cc.aa.', 'a..cccc..a', '.aa.cc.aa.',
        '...aaaa...', '..........', 'bb..bb..bb', '..bb..bb..']),
    'card_red_caravan': ('3a1a10', {'a': '8a5a2a', 'b': 'c85a3a', 'c': 'ffd24a'}, [
        '..........', '..aaaaaa..', '..acccca..', '..aaaaaa..', '..acaaca..',
        '..aaaaaa..', '..........', '.bbb...bb.', 'bbbbbbbbbb']),
    'card_fallen_page': ('142030', {'a': 'e8e0d0', 'b': '6a6a7a', 'c': 'ffe07a', 'd': 'd8f0ff'}, [
        '.......c..', '......cc..', '.....cc...', '.aaaaa....', '.abbaa....',
        '.aaaaa..d.', '.abbba....', '.aaaaa.d..', 'd...d...d.']),
    'card_stargazer': ('0c1028', {'a': '8a6a4a', 'b': 'c8a050', 'c': 'fff0b0'}, [
        'c.......c.', '.....c....', '..c.....bb', '.......bbb', '.....bbb..',
        '...bbb....', '....a.....', '...a.a....', '..a...a...']),
    'card_shrine_ash': ('1e1a14', {'a': 'b0b0b8', 'b': '8a6a30', 'c': 'ff8a3a'}, [
        '...a......', '....a.....', '...a..a...', '..a..a....', '...a..a...',
        '....c.c...', '.bbbbbbbb.', '..bbbbbb..', '...bbbb...']),
    'card_key_mould': ('1a1810', {'a': '6a5a30', 'c': 'ffd24a'}, [
        '..........', '.ccc......', 'c...c.....', 'c...cccccc', 'c...c..c.c',
        '.ccc...c.c', '..........', 'aaaaaaaaaa', '..........']),
    'card_rift_whisper': ('120a20', {'a': 'e0a0ff', 'b': '8a3ad0'}, [
        '.....a....', '....aa....', '...bab....', '...aab....', '..baa.....',
        '...aab....', '....ab....', '...aa.....', '...a......']),
    'card_exile_bounty': ('1a1414', {'a': 'ffd24a', 'b': '6a4a2a', 'c': 'd0d8e0'}, [
        'c........c', '.c......c.', '..c....c..', '...c..c...', '....cc....',
        '...b..b...', '..b.aa.b..', '....aa....', '..........']),
    'card_blood_page': ('1a0c0c', {'a': 'e8dcc0', 'b': '6a5a4a', 'c': 'c8202a'}, [
        '.aaaaaaa..', '.abbbbba..', '.aaaaaaa..', '.abbbaaa..', '.aaaaccc..',
        '.abbcccc..', '.aaaacca..', '.aaaaaca..', '......c...']),
    'card_fog_reflection': ('1a1a22', {'a': 'b8c0d0', 'b': '8a8aa8', 'c': '2a2a3a', 'd': '5a5a6a'}, [
        '...bbbb...', '..baaaab..', '.bacaacab.', '.baaaaaab.', '.bacaacab.',
        '.baaccaab.', '..baaaab..', '...bbbb...', 'dd.dddd.dd']),
    'card_rift_echo': ('0a0a14', {'a': '5ad0c8', 'c': '000000'}, [
        '.a......a.', '..a....a..', '.a..aa..a.', 'a..a..a..a', 'a..acca..a',
        'a..a..a..a', '.a..aa..a.', '..a....a..', '.a......a.']),
    'card_frost_mirror': ('0e1e30', {'a': 'b8ecff', 'b': 'c8d0e0', 'c': 'ffffff'}, [
        '...bbbb...', '..baaaab..', '.baacaaab.', '.bacccaab.', '.baacaaab.',
        '..baaaab..', '...bbbb...', '....bb....', '....bb....']),
    'card_styx_fare': ('0e1418', {'a': 'e0b040', 'b': '1a3a3a', 'c': '8a6a20'}, [
        '..........', '...aaaa...', '..acccca..', '.acaaaaca.', '.acaccaca.',
        '.acaaaaca.', '..acccca..', '...aaaa...', 'bbbbbbbbbb']),
    'card_ember_order': ('2a1008', {'a': 'e8d8b8', 'b': '6a5040', 'c': 'ff7a2a', 'd': 'c8202a'}, [
        '.c..c..c..', 'aaaaaaaaaa', 'abbbbbbbba', 'aaaaaaaaaa', 'abbbbbaaaa',
        'aaaaaaaaaa', 'abbbbddaaa', 'aaaaaddaaa', '.c.c..c.c.']),
    'card_starbody_shade': ('140c22', {'a': 'ffd24a', 'b': 'b07aff', 'c': 'c8303a'}, [
        '..........', '.a..a..a..', '.aa.a.aa..', '.aaaaaaa..', '.acaacaa..',
        '.aaaaaaa..', '..........', 'b..b...b..', '.b...b...b']),
    'card_void_resonance': ('0c0818', {'a': 'b07aff', 'c': '000000'}, [
        '....aa....', '..a....a..', '.a.cccc.a.', 'a.cccccc.a', 'a.cccccc.a',
        '.a.cccc.a.', '..a....a..', '....aa....', '..........']),
    'card_awakener_echo': ('100820', {'a': 'ffffff', 'b': 'b07aff', 'c': 'ffd24a'}, [
        '....a.....', '....a.....', '..b.a.b...', '...bab....', 'aaaacaaaa.',
        '...bab....', '..b.a.b...', '....a.....', '....a.....']),
    'card_star_chaser': ('0c1430', {'a': 'ffe8a0', 'b': 'c8d0e0', 'c': 'ffffff'}, [
        '........cc', '.......ccc', '.....aac..', '...aaa....', '.aaa......',
        'a.........', '..b.......', '.bbb......', 'b.b.......']),
    'card_mirror_star': ('140c22', {'a': '3a2a5a', 'b': 'c8a050', 'c': 'fff0b0'}, [
        '..bbbbbb..', '.baaaaaab.', '.baacaaab.', '.bacccaab.', '.baacacab.',
        '.baaacccb.', '.baaaacab.', '..bbbbbb..', '....bb....']),
}


def card_tiers():
    """data/cards.yml 的每張卡是哪一個等級（不裝 yaml 套件：照縮排找 card_ 開頭的鍵與底下的 tier:）。"""
    tiers = {}
    current = None
    path = ROOT / 'src/main/resources/data/cards.yml'
    for line in path.read_text(encoding='utf-8').split('\n'):
        stripped = line.split('#', 1)[0].rstrip()
        if stripped.startswith('  card_') and stripped.endswith(':') and not stripped.startswith('   '):
            current = stripped.strip()[:-1]
        elif current and stripped.strip().startswith('tier:'):
            tiers[current] = stripped.split(':', 1)[1].strip()
            current = None
    return tiers


def cards():
    items = []
    tiers = card_tiers()
    missing = sorted(set(tiers) - set(CARD_ART))
    if missing:
        print('沒有畫的星諭卡（照原本的紙）：', ', '.join(missing))
    for cid, (bg, colours, art) in CARD_ART.items():
        frame, frame_dark = CARD_RARITY.get(tiers.get(cid, 'common'), CARD_RARITY['common'])
        palette = {'O': '141418', 'F': frame, 'G': frame_dark, 'S': 'e8dcc0', 's': '8a7a60', 'B': bg}
        palette.update(colours)
        assert len(art) == 9 and all(len(r) == 10 for r in art), cid
        rows = []
        for y, line in enumerate(CARD_FRAME):
            out = ''
            for x, c in enumerate(line):
                if c == '*':
                    a = art[y - 2][x - 3]
                    out += 'B' if a == '.' else a
                else:
                    out += c
            rows.append(out)
        if tiers.get(cid) == 'chase':  # 追逐卡：四個角亮一點
            palette['h'] = 'ffd8a0'
            for (y, x) in ((1, 2), (1, 13), (14, 2), (14, 13)):
                rows[y] = rows[y][:x] + 'h' + rows[y][x + 1:]
        # 調色盤的字母要跟其他圖一樣是單一字元；這裡用到的：O F G S s B h a b c d
        used = set(''.join(rows)) - {'.'}
        items.append((cid, {k: v for k, v in palette.items() if k in used}, rows))
    return items


def runes():
    items = []
    for rid, glyph in RUNE_GLYPHS.items():
        rows = [list(r) for r in RUNE_SLAB]
        for y, line in enumerate(glyph):
            for x, c in enumerate(line):
                if c == 'r' and rows[4 + y][4 + x] != '.':
                    rows[4 + y][4 + x] = 'r'
        colours = ('1a1a22', '34343f', '545462', '7a7a8c', 'a8a8bc', RUNE_COLOURS[rid])
        items.append(('rune_' + rid, *shaded([''.join(r) for r in rows], colours)))
    # 傳奇兌換券：米色紙、紅色火漆（d/h 用在印上，所以另外上色）
    pal, rows = shaded(VOUCHER, ('5a3a1a', '9a1a1a', 'd8c8a0', 'f0e4c4', 'ff6a5a', '8a6a3a'))
    items.append(('unique_voucher', pal, rows))
    return items


def extras():
    items = []
    for cid, colours in ESSENCES.items():
        items.append((cid, *shaded(ESSENCE, colours)))
    for cid, liquid in CATALYSTS.items():
        items.append((cid, *shaded(CATALYST, ('2a3440', mix(liquid, '000000', 0.45), 'a8c8d8', 'd8ecf4', 'ffffff', liquid))))
    items.extend(omens())   # 2026-10-27：26 種星籤都畫成籤（這次之前 6 種畫在石板上）
    for kind, colour in TABLET_COLOURS.items():   # 2026-10-21：先驅石板
        items.append(('tablet_' + kind, *shaded(tablet_rows(kind), _TABLET_SLAB + (colour,))))
    for cid, (template, colours) in list(EXTRA.items()) + list(GEMS_CUT.items()):
        items.append((cid, *shaded(template, colours)))
    for cid, colours in TICKETS.items():   # 2026-10-18：入場券
        items.append((cid, *shaded(TICKET_KEY, colours)))
    return items


# ======================================================================
# 2026-10-27 站長：「通貨那邊也檢查一遍，沒圖示的補上」。這次之前 currencies.yml 有 135 種沒有圖（照原版物品）：
#   高階／完美的石頭、分級的符文與星魄、新的符文（體魄、壯力、決意、泉思、復甦、磐石、明眸）、新的星魄（防禦、尋寶、命中、施速、
#   統御、狂亂）、新的催化劑、新的蒸餾情緒、琢星稜鏡、凝露琉璃、星紋蝕刻器、預見鎖。
# 分級（次級／高階／完美）一律從同一家的基本款自動衍生（tier_variants），以後新增分級的通貨不用再手畫：
#   次級：顏色暗一點、淡一點；高階：右上角一顆銀色星芒；完美：右上角金色星芒、顏色亮一點。
# ======================================================================
RUNE_GLYPHS.update({
    'body':        ['........', '.rr..rr.', 'rrrrrrrr', 'rrrrrrrr', '.rrrrrr.', '..rrrr..', '...rr...', '........'],   # 心
    'robust':      ['rrrrrrr.', 'rrrrrrr.', '..rrr...', '..rrr...', '..rrr...', '.rrrrr..', 'rrrrrrr.', '........'],   # 鐵砧
    'resolve':     ['rrrrrrr.', 'r.....r.', 'r..r..r.', 'r.rrr.r.', '.r.r.r..', '..r.r...', '...r....', '........'],   # 盾
    'inspiration': ['...r....', '...r....', '..rrr...', '.rrrrr..', 'rrrrrrr.', 'rrrrrrr.', '.rrrrr..', '..rrr...'],   # 水滴
    'rebirth':     ['....rr..', '...rrr..', '..rrr...', '.r.rr.r.', '..rrrr..', '...rr...', '...r....', '..rrr...'],   # 新芽
    'stone':       ['...r....', '..rrr...', '..rrr...', '.rrrrr..', '.rrrrr..', 'rrrrrrr.', 'rrrrrrr.', '........'],   # 山
    'vision':      ['........', '..rrrr..', '.r....r.', 'r..rr..r', 'r.rrrr.r', '.r....r.', '..rrrr..', '........'],   # 眼
})
RUNE_COLOURS.update({
    'body': 'ff6a6a', 'robust': 'e0a060', 'resolve': 'c88aff', 'inspiration': '5ad8ff', 'rebirth': '7aff7a',
    'stone': 'c8bea8', 'vision': 'ff8ab0',
})
ESSENCES.update({  # 新的星魄（基本款）；次級／高階／完美照 tier_variants 衍生
    'essence_enhancement': ('1a2030', '4a5a70', '8a9ab0', 'c8d4e4', 'ffffff'),   # 防禦：鋼灰
    'essence_opulence':    ('3a2a04', 'a8780a', 'f0c020', 'ffe680', 'fffbe0'),   # 尋寶：金
    'essence_accuracy':    ('0a2a24', '1a7a6a', '2ac0a8', '8af0e0', 'eafffb'),   # 命中：青綠
    'essence_alacrity':    ('14143a', '3a4aa8', '6a8af0', 'b0c4ff', 'f0f4ff'),   # 施速：淡藍紫
    'essence_command':     ('1a1e14', '4a5a3a', '8a9a6a', 'c8d4a8', 'f8fff0'),   # 統御（召喚物）：骨綠
    'essence_hysteria':    ('1a0414', '6a0a4a', 'c01a8a', 'ff6ad0', 'ffe0f4'),   # 狂亂：洋紅
})
CATALYSTS.update({  # 照 PoE2 0.5 的催化劑（2026-10-27 新增的 7 種）
    'catalyst_mana': '3a7ae0', 'catalyst_physical': 'b8904a', 'catalyst_fire': 'f07a1a', 'catalyst_cold': '5fc8f0',
    'catalyst_lightning': 'f0d82a', 'catalyst_chaos': '8a3ac8', 'catalyst_minion': '8a9a6a',
})
VIALS.update({  # 新的蒸餾情緒（亮、中、暗）
    'distilled_envy':       ('d0ff8a', '7ac020', '3a6a10'),
    'distilled_disgust':    ('d8d89a', '8a8a3a', '4a4a1a'),
    'distilled_despair':    ('a8b8c8', '5a6a80', '2a3440'),
    'distilled_fear':       ('d0a0ff', '8a4ad0', '4a1a8a'),
    'distilled_suffering':  ('ff8aa8', 'c02a5a', '6a0a2a'),
    'distilled_isolation':  ('a0f0ff', '3ab0d0', '1a5a7a'),
    'distilled_melancholy': ('a0a0e0', '4a4aa0', '20205a'),
    'distilled_ferocity':   ('ffc08a', 'e0701a', '7a3a0a'),
    'distilled_contempt':   ('8ae0c8', '2a8a7a', '0a3a34'),
})
ORBS.update({
    'glassblower_bauble': (shades('3a5a6a', 'a8d8e8', 'e0f8ff'), 'ring', 'ffffff', None),   # 凝露琉璃：透明的玻璃球
})
PRISM = [  # 琢星稜鏡：三角稜鏡＋射進來的光（r）
    '................',
    '........o.......',
    '.......oho......',
    '.......olmo.....',
    '......ohlmo.....',
    '......olmmdo....',
    '.....ohlmmdo....',
    '.rrr.ollmmddo...',
    '....ohlmmmddo...',
    '....ollmmmdddo..',
    '...ohlmmmmdddo..',
    '...oooooooooooo.',
    '................',
    '................',
    '................',
    '................',
]
ETCHER = [  # 星紋蝕刻器：木柄、鋼桿、紫色晶尖（r）
    '................',
    '............oo..',
    '...........orho.',
    '..........orrro.',
    '.........olrro..',
    '........olmoo...',
    '.......olmo.....',
    '......olmo......',
    '.....olmo.......',
    '....oddo........',
    '...oddo.........',
    '..oddo..........',
    '.oddo...........',
    '.ooo............',
    '................',
    '................',
]
HAIR_LOCK = [  # 預見鎖：一綹編起來的銀髮，紅繩綁著（r）
    '................',
    '......oooo......',
    '.....ohllo......',
    '.....olmmo......',
    '......omlo......',
    '.....olmmo......',
    '.....orrro......',
    '......olmo......',
    '.....ollmo......',
    '......omdo......',
    '.....olmdo......',
    '......omdo......',
    '.....olmo.......',
    '....olmo........',
    '....omo.........',
    '....oo..........',
]
EXTRA.update({
    'gemcutter_prism': (PRISM,     ('0a2a3a', '3a8ab0', '8ae0f0', 'd0f8ff', 'ffffff', 'ffd861')),
    'arcanist_etcher': (ETCHER,    ('1a1420', '7a4e24', '8a92a6', 'd6dcea', 'ffffff', 'b06aff')),
    'hinekora_lock':   (HAIR_LOCK, ('2a2a34', '8a8aa0', 'c8c8d8', 'eeeef8', 'ffffff', 'd0303a')),
})

TIER_PREFIXES = ('lesser_', 'greater_', 'perfect_')
TIER_SUFFIXES = ('_lesser', '_greater', '_perfect')
SPARKLE = {'greater': ('c8d8f0', 'ffffff'), 'perfect': ('ffc83a', 'fff6c0')}
_LETTERS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'


def currency_ids():
    """currencies.yml 的通貨代號（不裝 yaml 套件：照縮排找第一層）。"""
    import re
    path = ROOT / 'src/main/resources/data/currencies.yml'
    return [m.group(1) for m in re.finditer(r'^([a-z0-9_]+):\s*(#.*)?$', path.read_text(encoding='utf-8'), re.M)]


def tier_of(cid):
    """(基本款代號, 分級)；不是分級的回傳 None。"""
    for p in TIER_PREFIXES:
        if cid.startswith(p):
            return cid[len(p):], p[:-1]
    for s in TIER_SUFFIXES:
        if cid.endswith(s):
            return cid[:-len(s)], s[1:]
    return None


def tier_variant(palette, rows, tier):
    """從基本款衍生分級的圖：次級暗淡、高階銀星芒、完美金星芒（右上角 3×3 的十字）。"""
    if tier == 'lesser':
        pal = {k: mix(v, '34343c', 0.32) for k, v in palette.items()}
        return pal, list(rows)
    pal = dict(palette)
    if tier == 'perfect':
        pal = {k: mix(v, 'ffffff', 0.10) for k, v in pal.items()}
    free = [c for c in _LETTERS if c not in pal]
    arm, core, dark = free[0], free[1], free[2]
    pal[arm], pal[core] = SPARKLE[tier]
    pal[dark] = '141418'
    grid = [list(r) for r in rows]
    star = {(13, 1): arm, (12, 2): arm, (13, 2): core, (14, 2): arm, (13, 3): arm}
    for (x, y), c in star.items():   # 星芒外面描一圈深色（只描在透明的地方）
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if (nx, ny) not in star and 0 <= nx < 16 and 0 <= ny < 16 and grid[ny][nx] == '.':
                grid[ny][nx] = dark
    for (x, y), c in star.items():
        grid[y][x] = c
    return pal, [''.join(r) for r in grid]


def tier_variants(have):
    """currencies.yml 裡還沒有圖、而且基本款有圖的分級通貨：自動衍生。have = 代號 → (palette, rows)。"""
    out = []
    for cid in currency_ids():
        if cid in have:
            continue
        t = tier_of(cid)
        if t and t[0] in have:
            out.append((cid, *tier_variant(*have[t[0]], t[1])))
    return out


def preview(path, entries, scale=4, cols=16):
    """2026-10-27：全部通貨的預覽圖（放大 scale 倍）。要 Pillow。"""
    from PIL import Image
    pad = 2
    rows_n = (len(entries) + cols - 1) // cols
    img = Image.new('RGB', (cols * (16 * scale + pad) + pad, rows_n * (16 * scale + pad) + pad), (40, 40, 44))
    for k, (cid, palette, art) in enumerate(entries):
        ox, oy = pad + (k % cols) * (16 * scale + pad), pad + (k // cols) * (16 * scale + pad)
        for y, line in enumerate(art):
            for x, c in enumerate(line):
                if c != '.':
                    v = palette[c]
                    rgb = tuple(int(v[j:j + 2], 16) for j in (0, 2, 4))
                    for a in range(scale):
                        for b in range(scale):
                            img.putpixel((ox + x * scale + a, oy + y * scale + b), rgb)
    img.save(path)
    print('preview', path)


def parse_art(text):
    """currency_art.txt → 代號 → (palette, rows)。"""
    found = {}
    cid, pal, rows = None, {}, []
    for line in text.split('\n') + ['']:
        if line.startswith('item ') or not line.strip():
            if cid and len(rows) == 16:
                found[cid] = (pal, rows)
            cid, pal, rows = (line[5:].strip(), {}, []) if line.startswith('item ') else (None, {}, [])
            continue
        s = line.strip()
        if s.startswith('#'):
            continue
        if len(s) > 2 and s[1] == '=' and cid:
            pal[s[0]] = s[2:]
        elif cid:
            rows.append(s)
    return found


PREVIEW = ROOT / 'tools/currency_art_preview.png'


def main():
    out = ['# 《星骸》通貨的像素圖（16×16；格式同 unique_art.txt：item 代號；palette 字母=RRGGBB；接著 16 行，. = 透明）。',
           '# 由 tools/gen_currency_art.py 產生（改顏色、記號請改那支程式再跑一次）；改完重啟（資源包重新產生）。', '']
    items = []
    for cid, (colours, glyph, g1, g2) in ORBS.items():
        items.append((cid, *orb_rows(colours, glyph, g1, g2)))
    for cid, (palette, rows) in OTHERS.items():
        items.append((cid, palette, rows))
    for cid, (light, mid, dark) in VIALS.items():
        palette = {'a': '2a2a36', 'b': 'd8e8f0', 'c': light, 'd': mid, 'e': dark}
        items.append((cid, palette, VIAL))
    items.extend(gems())
    items.extend(extras())
    items.extend(runes())
    items.extend(cards())
    for cid, palette, rows in items:
        out.append('item ' + cid)
        for k, v in palette.items():
            out.append('  %s=%s' % (k, v))
        for row in rows:
            assert len(row) == 16, (cid, row)
            out.append('  ' + row)
        out.append('')
    # 手畫、沒在這支程式裡的項目（星魄 essence_blaze／活動素材 event_candy……）：從舊檔照抄保留
    have = {cid for cid, _, _ in items}
    old = parse_art(OUT.read_text(encoding='utf-8')) if OUT.exists() else {}
    # 2026-10-27：自動衍生的分級（tier_variants）每次重算，不從舊檔照抄
    derived = {cid for cid in old if tier_of(cid) and (tier_of(cid)[0] in have or tier_of(cid)[0] in old)
               and cid not in have}
    if OUT.exists():
        kept = 0
        block = []
        for line in OUT.read_text(encoding='utf-8').split('\n') + ['']:
            if line.startswith('item ') or line == '':
                if block and block[0].startswith('item ') and block[0][5:].strip() not in have \
                        and block[0][5:].strip() not in derived:
                    out.extend(block + ['']); kept += 1
                block = []
            if line:
                block.append(line)
        if kept:
            print('kept', kept, 'hand-drawn items')
    sprites = {cid: (pal, rows) for cid, pal, rows in items}
    sprites.update({cid: old[cid] for cid in old if cid not in sprites and cid not in derived})
    variants = tier_variants(sprites)
    for cid, palette, rows in variants:
        out.append('item ' + cid)
        for k, v in palette.items():
            out.append('  %s=%s' % (k, v))
        out.extend('  ' + row for row in rows)
        out.append('')
    print('tier variants', len(variants))
    OUT.write_text('\n'.join(out), encoding='utf-8')
    print('wrote', OUT, len(items), 'items')
    missing = [cid for cid in currency_ids() if cid not in sprites and cid not in {v[0] for v in variants}]
    if missing:
        print('還沒有圖的通貨（照原版物品）：', ', '.join(missing))
    try:
        sprites.update({cid: (pal, rows) for cid, pal, rows in variants})
        preview(PREVIEW, [(cid, *sprites[cid]) for cid in sprites])
    except ImportError:
        pass


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', help='專案資料夾（讀 data/*.yml；預設是這支程式的上一層）')
    parser.add_argument('--out', help='currency_art.txt 的位置（預設是 <root>/src/main/resources/web/currency_art.txt）')
    parser.add_argument('--omen-preview', help='另外輸出星籤的預覽圖（PNG，放大 8 倍）')
    args = parser.parse_args()
    if args.root:
        ROOT = Path(args.root).resolve()
        OUT = ROOT / 'src/main/resources/web/currency_art.txt'
        PREVIEW = ROOT / 'tools/currency_art_preview.png'
    if args.out:
        OUT = Path(args.out).resolve()
    main()
    if args.omen_preview:
        omen_preview(args.omen_preview)
