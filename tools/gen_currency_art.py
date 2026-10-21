#!/usr/bin/env python3
"""產生 src/main/resources/web/currency_art.txt：23 種通貨與未切割寶石（每一級一張，右下角寫等級）的 16×16 像素圖（格式同 unique_art.txt）。

球狀的通貨（各種「石」）共用同一個球的陰影模板，換顏色與球心的記號；磨刀石、護甲碎片、製圖釘、
天穹碎片、深淵星核、鑑定卷軸、蒸餾情緒各自畫。改完直接跑：python3 tools/gen_currency_art.py
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
# 2026-10-21：星籤每一種一個記號（以前只有符文顏色不同，祭壇的獎勵裡分不出來）。
#   石板顏色分兩家：加詞綴的（加前綴、加後綴、雙詞綴）是藍灰石板，移除時用的（去劣、保前綴、保後綴）是暗紅石板；
#   記號：← 加前綴、→ 加後綴、大小兩個＋ 雙詞綴、✕ 去劣、左半邊塗滿的盾 保前綴、右半邊塗滿的盾 保後綴。
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
OMEN_GLYPHS = {  # 8×8，蓋在第 4–11 列、第 4–11 行（r = 記號）
    'left': ['...r....', '..rr....', '.rrrrrrr', 'rrrrrrrr', 'rrrrrrrr', '.rrrrrrr', '..rr....', '...r....'],
    'right': ['....r...', '....rr..', 'rrrrrrr.', 'rrrrrrrr', 'rrrrrrrr', 'rrrrrrr.', '....rr..', '....r...'],
    'double': ['.....r..', '....rrr.', '..r..r..', '..r.....', 'rrrrr...', '..r.....', '..r.....', '........'],
    'cross': ['rr....rr', 'rrr..rrr', '.rrrrrr.', '..rrrr..', '..rrrr..', '.rrrrrr.', 'rrr..rrr', 'rr....rr'],
    'guard_left': ['rrrrrrrr', 'rrrr...r', 'rrrr...r', 'rrrr...r', 'rrrr...r', '.rrr..r.', '..rr.r..', '...rr...'],
    'guard_right': ['rrrrrrrr', 'r...rrrr', 'r...rrrr', 'r...rrrr', 'r...rrrr', '.r..rrr.', '..r.rr..', '...rr...'],
}
_ADD_SLAB = ('10101c', '1e1e30', '2c2c46', '3a3a5c', '5a5a80')      # 藍灰石板（加詞綴）
_REMOVE_SLAB = ('1c0c0c', '301414', '45201f', '5a2a28', '7a4440')   # 暗紅石板（移除時用）
OMENS = {  # 代號: (石板, 記號, 記號顏色)
    'omen_sinistral': (_ADD_SLAB, 'left', 'c77dff'),
    'omen_dextral': (_ADD_SLAB, 'right', 'ffd24a'),
    'omen_abundance': (_ADD_SLAB, 'double', '6ae08a'),
    'omen_whittling': (_REMOVE_SLAB, 'cross', 'ff6a5a'),
    'omen_sinistral_guard': (_REMOVE_SLAB, 'guard_left', 'c77dff'),
    'omen_dextral_guard': (_REMOVE_SLAB, 'guard_right', 'ffd24a'),
}


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


def omen_rows(glyph):
    rows = [list(r) for r in OMEN]
    for y, line in enumerate(OMEN_GLYPHS[glyph]):
        for x, c in enumerate(line):
            if c == 'r':
                rows[4 + y][4 + x] = 'r'
    return [''.join(r) for r in rows]


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
    for cid, (slab, glyph, colour) in OMENS.items():
        items.append((cid, *shaded(omen_rows(glyph), slab + (colour,))))
    for kind, colour in TABLET_COLOURS.items():   # 2026-10-21：先驅石板
        items.append(('tablet_' + kind, *shaded(tablet_rows(kind), _TABLET_SLAB + (colour,))))
    for cid, (template, colours) in list(EXTRA.items()) + list(GEMS_CUT.items()):
        items.append((cid, *shaded(template, colours)))
    for cid, colours in TICKETS.items():   # 2026-10-18：入場券
        items.append((cid, *shaded(TICKET_KEY, colours)))
    return items


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
    if OUT.exists():
        kept = 0
        block = []
        for line in OUT.read_text(encoding='utf-8').split('\n') + ['']:
            if line.startswith('item ') or line == '':
                if block and block[0].startswith('item ') and block[0][5:].strip() not in have:
                    out.extend(block + ['']); kept += 1
                block = []
            if line:
                block.append(line)
        if kept:
            print('kept', kept, 'hand-drawn items')
    OUT.write_text('\n'.join(out), encoding='utf-8')
    print('wrote', OUT, len(items), 'items')


if __name__ == '__main__':
    main()
