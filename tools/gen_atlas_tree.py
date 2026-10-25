#!/usr/bin/env python3
"""產生 src/main/resources/data/atlas_tree.yml（星圖天賦樹，2026-10-21 起是一棵真正的樹）。

版面（方格座標，上下左右相鄰＝相連，跟 passives/tree.json 一樣）：
  - 中心 (0, 0) 是起點（不用點）。
  - 四條短輻條（上下左右各 2 格）接到半徑 3 的方形「內環」（24 格小天賦）。
  - 12 條主幹掛在內環外側，每邊 3 條（在 -2、0、+2 的位置），每條往外 7 格：小、小、大、小、小、大、尾端。
    尾端：6 條是核心天賦（3 點、有代價），其他 6 條是大天賦。
  - 同一邊相鄰的兩條主幹在第 6 格的高度用 1 格「橋」連起來，所以可以從寶箱繞到裂隙不用回中心。

改完直接跑：python3 tools/gen_atlas_tree.py；遊戲裡 /ec reload。節點代號改了的話，點過的人要重點（version 加一會全部退點）。
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'src/main/resources/data/atlas_tree.yml'

VERSION = 2
RING_RADIUS = 3
TRUNK_LENGTH = 7

# 內環與輻條的小天賦：照順序輪流
RING_MODS = [
    ('星塵', 'map.quantity', 2),
    ('星輝', 'map.rarity', 3),
    ('星潮', 'map.pack_size', 3),
    ('星識', 'atlas.experience', 3),
]
SPOKE_MODS = [('引路', 'atlas.map_drop', 5), ('拾穗', 'map.quantity', 2)]

# 12 條主幹：代號、名稱、顏色、圖示、說明、7 個節點（小／大／尾端）。
# 節點：(名稱, [(效果鍵, 數值), ...]) ；大天賦多一個圖示；核心另外寫 drawbacks。
# 每邊三條：北（上）、東（右）、南（下）、西（左），每邊由左到右／上到下。
TRUNKS = [
    # ---- 北 ----
    dict(id='sustain', name='地圖續航', color='#9fd3ff', icon='FILLED_MAP', description='地圖掉得更多、階級更高',
         nodes=[
             ('尋路', [('atlas.map_drop', 15)]),
             ('星階', [('atlas.tier_up', 8)]),
             ('拓荒', [('atlas.map_drop', 20), ('atlas.tier_up', 6)], 'COMPASS'),
             ('航線', [('atlas.map_drop', 15)]),
             ('遠征', [('atlas.boss_maps', 1)]),
             ('星圖之主', [('atlas.boss_maps', 1), ('atlas.tier_up', 12)], 'RECOVERY_COMPASS'),
             ('尋星者', [('atlas.chase_unique', 50)], 'NETHER_STAR', [('atlas.unique_less', 50)]),
         ]),
    dict(id='wealth', name='豐饒', color='#f5b942', icon='GOLD_INGOT', description='地圖的物品數量與稀有度',
         nodes=[
             ('拾荒', [('map.quantity', 3)]),
             ('鑑寶', [('map.rarity', 6)]),
             ('豐收', [('map.quantity', 4), ('map.rarity', 6)], 'GOLD_BLOCK'),
             ('囤積', [('map.quantity', 3)]),
             ('慧眼', [('map.rarity', 8)]),
             ('星骸的饋贈', [('map.quantity', 5), ('map.rarity', 10)], 'RAW_GOLD_BLOCK'),
             ('孤注一擲', [('map.quantity', 30)], 'TOTEM_OF_UNDYING', [('atlas.map_drop_less', 50)]),
         ]),
    dict(id='packs', name='群聚', color='#ff8c69', icon='ZOMBIE_HEAD', description='更多、更強的怪物群（掉落與經驗跟著變多）',
         nodes=[
             ('蜂擁', [('map.pack_size', 5)]),
             ('魔染', [('map.magic_packs', 15)]),
             ('獸潮', [('map.pack_size', 6), ('map.magic_packs', 10)], 'SKELETON_SKULL'),
             ('梟雄', [('map.rare_packs', 15)]),
             ('歷練', [('atlas.experience', 6)]),
             ('萬物歸巢', [('map.pack_size', 8), ('map.rare_packs', 15), ('atlas.experience', 5)], 'WITHER_SKELETON_SKULL'),
             ('無人之地', [('atlas.rare_only', 1), ('map.rare_packs', 100)], 'CREEPER_HEAD', [('atlas.experience_less', 30)]),
         ]),
    # ---- 東 ----
    dict(id='bosses', name='首領', color='#af6025', icon='DRAGON_HEAD', description='地圖 Boss 掉得更好',
         nodes=[
             ('討伐', [('atlas.boss_quantity', 15)]),
             ('戰利品', [('atlas.boss_unique', 20)]),
             ('斬首令', [('atlas.boss_quantity', 20), ('atlas.boss_rarity', 20)], 'IRON_SWORD'),
             ('深淵回響', [('atlas.uber_key', 40)]),
             ('王冠', [('atlas.boss_unique', 20)]),
             ('弒王者', [('atlas.boss_rarity', 30), ('atlas.boss_unique', 30), ('atlas.uber_key', 30)], 'NETHERITE_SWORD'),
             ('魔王的宴席', [('atlas.boss_double', 1)], 'GOLDEN_APPLE', [('atlas.no_monster_currency', 1)]),
         ]),
    dict(id='shrine', name='神龕', color='#9fe8c8', icon='SEA_LANTERN', description='更多神龕、增益更久',
         nodes=[
             ('虔誠', [('atlas.shrine.weight', 40)]),
             ('長明', [('atlas.shrine.duration', 20)]),
             ('天機', [('atlas.shrine.unique', 25), ('atlas.shrine.weight', 30)], 'LANTERN'),
             ('祈願', [('atlas.shrine.duration', 20)]),
             ('聖域', [('atlas.shrine.weight', 40)]),
             ('眾神垂憐', [('atlas.shrine.extra', 1), ('atlas.shrine.duration', 30)], 'BEACON'),
             ('神恩', [('atlas.shrine.extra', 1), ('atlas.shrine.unique', 50)], 'END_CRYSTAL'),
         ]),
    dict(id='strongbox', name='寶箱', color='#e0b050', icon='CHEST', description='更多寶箱、箱子裡更多東西',
         nodes=[
             ('藏寶圖', [('atlas.strongbox.weight', 40)]),
             ('滿載', [('atlas.strongbox.quantity', 20)]),
             ('秘藏', [('atlas.strongbox.unique', 25), ('atlas.strongbox.weight', 30)], 'TRAPPED_CHEST'),
             ('重鎖', [('atlas.strongbox.quantity', 20)]),
             ('藏匿', [('atlas.strongbox.weight', 40)]),
             ('寶庫之鑰', [('atlas.strongbox.extra', 1), ('atlas.strongbox.quantity', 30)], 'ENDER_CHEST'),
             ('金庫', [('atlas.strongbox.extra', 1), ('atlas.strongbox.unique', 50)], 'GOLD_BLOCK'),
         ]),
    # ---- 南 ----
    dict(id='breach', name='裂隙', color='#c48bff', icon='CRYING_OBSIDIAN', description='更多裂隙、開得更久',
         nodes=[
             ('裂縫', [('atlas.breach.weight', 40)]),
             ('延宕', [('atlas.breach.duration', 15)]),
             ('虛空之觸', [('atlas.breach.unique', 25), ('atlas.breach.weight', 30)], 'OBSIDIAN'),
             ('裂痕', [('atlas.breach.duration', 15)]),
             ('穿界', [('atlas.breach.weight', 40)]),
             ('裂界洪流', [('atlas.breach.extra', 1), ('atlas.breach.duration', 25)], 'END_PORTAL_FRAME'),
             ('界縫', [('atlas.breach.extra', 1), ('atlas.breach.unique', 50)], 'ECHO_SHARD'),
         ]),
    dict(id='exile', name='流寇', color='#ffff77', icon='IRON_HELMET', description='更多流寇、掉更多裝備',
         nodes=[
             ('通緝令', [('atlas.exile.weight', 40)]),
             ('搜刮', [('atlas.exile.drops', 1)]),
             ('懸賞', [('atlas.exile.unique', 25), ('atlas.exile.weight', 30)], 'CHAINMAIL_HELMET'),
             ('追緝', [('atlas.exile.weight', 40)]),
             ('繳械', [('atlas.exile.drops', 1)]),
             ('流寇之王', [('atlas.exile.extra', 1), ('atlas.exile.drops', 1)], 'NETHERITE_HELMET'),
             ('單一信仰', [('atlas.chosen_event', 2)], 'GOLDEN_HELMET', [('atlas.other_events_off', 1)]),
         ]),
    dict(id='rites', name='祭儀與迷霧', color='#ff5c6a', icon='RED_CANDLE', description='祭壇與幻霧更常出現、獎勵更多',
         nodes=[
             ('血燭', [('atlas.ritual.chance', 40)]),
             ('奉牲', [('atlas.ritual.tribute', 20)]),
             ('霧鏡', [('atlas.delirium.chance', 40), ('atlas.delirium.progress', 15)], 'TINTED_GLASS'),
             ('血償', [('atlas.ritual.tribute', 20)]),
             ('夢魘', [('atlas.delirium.chance', 40)]),
             ('瘋狂之宴', [('atlas.delirium.progress', 30), ('atlas.ritual.tribute', 30)], 'SOUL_LANTERN'),
             ('深淵裂縫', [('atlas.abyss.weight', 60), ('atlas.abyss.extra', 1), ('atlas.abyss.unique', 50)], 'SCULK'),
         ]),
    # ---- 西 ----
    dict(id='tower', name='塔與石板', color='#d8c8a0', icon='LODESTONE', description='塔罩得更廣、先驅石板更有效',
         nodes=[
             ('瞭望', [('atlas.tablet.effect', 10)]),
             ('築基', [('atlas.tablet.drop', 25)]),
             ('燈塔', [('atlas.tablet.drop', 25), ('atlas.tablet.effect', 10)], 'LANTERN'),
             ('鐫刻', [('atlas.tablet.effect', 15)]),
             ('採石', [('atlas.tablet.drop', 25)]),
             ('先驅之印', [('atlas.tablet.effect', 15), ('atlas.tablet.drop', 50)], 'LODESTONE'),
             ('群塔', [('atlas.tower.radius', 1), ('atlas.tablet.effect', 10)], 'CONDUIT'),
         ]),
    dict(id='corrupt', name='腐化', color='#b07aff', icon='SCULK_CATALYST', description='腐化區更常出現、加成更強、掉得更多',
         nodes=[
             ('侵蝕', [('atlas.corrupt.chance', 20)]),
             ('蔓延', [('atlas.corrupt.bonus', 15)]),
             ('污穢', [('atlas.corrupt.chance', 25), ('atlas.corrupt.bonus', 15)], 'SCULK_SENSOR'),
             ('深染', [('atlas.corrupt.bonus', 20)]),
             ('腐土', [('atlas.corrupt.chance', 25)]),
             ('腐化之種', [('atlas.corrupt.bonus', 30), ('atlas.corrupt.chance', 30)], 'SCULK_SHRIEKER'),
             ('腐化之心', [('atlas.all_corrupt', 1)], 'SCULK_CATALYST', [('atlas.monster_damage_more', 20)]),
         ]),
    dict(id='cards', name='星諭卡', color='#c9a0ff', icon='PAPER', description='星諭卡掉得更多、牌堆更好',
         nodes=[
             ('占卜', [('atlas.card.chance', 20)]),
             ('洗牌', [('atlas.card.deck', 10)]),
             ('星諭', [('atlas.card.chance', 25), ('atlas.card.deck', 10)], 'MAP'),
             ('抽牌', [('atlas.card.chance', 20)]),
             ('賭局', [('atlas.card.gamble', 10)]),
             ('命運之輪', [('atlas.card.chance', 40), ('atlas.card.deck', 20)], 'RECOVERY_COMPASS'),
             ('星牌師', [('atlas.card.chance', 30), ('atlas.card.gamble', 20)], 'BOOK'),
         ]),
]

# 哪一條主幹掛在哪：(邊, 沿著邊的偏移)；邊：N 往上、E 往右、S 往下、W 往左
SIDES = {'N': (0, -1), 'E': (1, 0), 'S': (0, 1), 'W': (-1, 0)}
PLACEMENT = [('N', -2), ('N', 0), ('N', 2), ('E', -2), ('E', 0), ('E', 2), ('S', -2), ('S', 0), ('S', 2), ('W', -2), ('W', 0), ('W', 2)]


def side_cell(side, offset, depth):
    """邊上偏移 offset、離內環 depth 格（0 = 內環上）的座標。"""
    dx, dy = SIDES[side]
    r = RING_RADIUS + depth
    if side in ('N', 'S'):
        return offset, dy * r
    return dx * r, offset


def main():
    nodes = []  # dict: id, name, x, y, trunk, cost, notable, keystone, icon, mods, drawbacks
    taken = {}

    def add(node):
        key = (node['x'], node['y'])
        assert key not in taken, (node['id'], taken[key] if key in taken else None)
        assert node['id'] not in {n['id'] for n in nodes}, node['id']
        taken[key] = node['id']
        nodes.append(node)

    # 輻條
    k = 0
    for side, (dx, dy) in SIDES.items():
        for depth in (1, 2):
            name, stat, value = SPOKE_MODS[(depth - 1) % len(SPOKE_MODS)]
            add(dict(id=f'sp_{side.lower()}{depth}', name=name, x=dx * depth, y=dy * depth, trunk='core', cost=1,
                     notable=False, keystone=False, icon='', mods=[(stat, value)], drawbacks=[]))
            k += 1
    # 內環：沿著方形走一圈
    ring = []
    r = RING_RADIUS
    for x in range(-r, r + 1):
        ring.append((x, -r))
    for y in range(-r + 1, r + 1):
        ring.append((r, y))
    for x in range(r - 1, -r - 1, -1):
        ring.append((x, r))
    for y in range(r - 1, -r, -1):
        ring.append((-r, y))
    for i, (x, y) in enumerate(ring):
        name, stat, value = RING_MODS[i % len(RING_MODS)]
        add(dict(id=f'ring_{i + 1}', name=name, x=x, y=y, trunk='core', cost=1, notable=False, keystone=False, icon='',
                 mods=[(stat, value)], drawbacks=[]))
    # 主幹
    for trunk, (side, offset) in zip(TRUNKS, PLACEMENT):
        assert len(trunk['nodes']) == TRUNK_LENGTH, trunk['id']
        for depth, entry in enumerate(trunk['nodes'], start=1):
            name, mods = entry[0], entry[1]
            icon = entry[2] if len(entry) > 2 else ''
            drawbacks = entry[3] if len(entry) > 3 else []
            keystone = bool(drawbacks)
            notable = bool(icon) and not keystone
            cost = 3 if keystone else 2 if notable else 1
            x, y = side_cell(side, offset, depth)
            add(dict(id=f"{trunk['id']}_{depth}", name=name, x=x, y=y, trunk=trunk['id'], cost=cost, notable=notable,
                     keystone=keystone, icon=icon, mods=mods, drawbacks=drawbacks))
    # 橋：同一邊相鄰主幹的第 6 格之間（偏移 -1、+1）
    for side in SIDES:
        for offset in (-1, 1):
            x, y = side_cell(side, offset, 6)
            add(dict(id=f'bridge_{side.lower()}{"l" if offset < 0 else "r"}', name='星橋', x=x, y=y, trunk='core', cost=1,
                     notable=False, keystone=False, icon='', mods=[('map.quantity', 2), ('map.rarity', 2)], drawbacks=[]))

    # 每個節點至少要跟另一個節點或起點相鄰（不然點不到）
    cells = set(taken) | {(0, 0)}
    for n in nodes:
        assert any((n['x'] + dx, n['y'] + dy) in cells for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))), n['id']

    total = sum(n['cost'] for n in nodes)
    out = [
        '# 星圖天賦（地圖天賦樹，PoE2 的 Atlas Passive Tree）：完成地圖拿天賦點，點了之後「自己開的地圖」更好（2026-10-25：終局只吃「極境鑰匙」那條；極境、巔峰、回響、試煉、塔、金庫不吃）。',
        '# 2026-10-21：從 9 欄直線改成一棵樹（方格座標，上下左右相鄰＝相連；中心 (0,0) 是起點，不用點）。',
        '# 由 tools/gen_atlas_tree.py 產生（改節點請改那支程式再跑一次）；改完 /ec reload：之後開的地圖照新的。',
        '#',
        '# version       改了的話舊角色登入時星圖天賦全部退回（節點代號或位置大改時用）',
        '# points        天賦點怎麼來（每個角色自己的）：per_map 星圖格子算出來的點數（atlas.yml 的 points：每完成一格 1 點、60 格之後每 3 格 1 點、',
        '#               塔與城塞各 2 點）乘幾倍、per_final 每打贏一個終局、per_uber 每打贏一個極境、tier_milestones 最高完成階級到這幾階各 +1、max 總點數上限。',
        '#               出貨：認真玩到 T16 大約 60–80 格 + 5×2 + 5×2 + 5 ≈ 85–105 點；全部點滿要 %d 點，所以要挑。' % total,
        '# trunks        主幹（介面上的分組與顏色）；core 是中心的輻條、內環與橋',
        '# nodes         每個節點：id（存在角色進度裡；改了代號點過的人要重點）、name、x/y 座標、trunk、cost（1–5）、',
        '#               notable 大天賦、keystone 核心（有 drawbacks 代價）、icon（原版物品；小天賦不用）、mods 效果',
        '# 效果鍵見 AtlasTree.STATS（百分比都是百分比點數：20 = 20%）：',
        '#   map.quantity / map.rarity / map.pack_size / map.magic_packs / map.rare_packs   地圖本身',
        '#   atlas.experience / atlas.map_drop / atlas.tier_up / atlas.boss_maps / atlas.boss_quantity / atlas.boss_rarity / atlas.boss_unique / atlas.uber_key',
        '#   atlas.<事件>.weight / .extra / .unique，atlas.shrine.duration、atlas.strongbox.quantity、atlas.breach.duration、atlas.exile.drops、',
        '#   atlas.ritual.chance / .tribute、atlas.delirium.chance / .progress',
        '#   2026-10-21 新：atlas.tower.radius 塔罩的半徑、atlas.tablet.effect 石板效果、atlas.tablet.drop 石板掉落、',
        '#   atlas.corrupt.chance / .bonus 腐化區、atlas.card.chance / .deck / .gamble 星諭卡、atlas.chase_unique 追逐級傳奇機率',
        '#   核心的代價（drawbacks）：atlas.map_drop_less、atlas.unique_less、atlas.experience_less、atlas.monster_damage_more、',
        '#   atlas.no_monster_currency、atlas.other_events_off；核心的旗標：atlas.boss_double、atlas.rare_only、atlas.all_corrupt、atlas.chosen_event',
        '',
        'version: %d' % VERSION,
        '',
        'points:',
        '  per_map: 1',
        '  per_final: 2',
        '  per_uber: 2',
        '  tier_milestones: [4, 7, 10, 13, 16]',
        '  max: 120                          # 2026-10-21：總點數上限（約整棵樹的 7 成：點不滿，要挑方向；0 = 沒有上限）',
        '',
        'trunks:',
        '  core: { name: 星核, color: "#e8e8f2", icon: NETHER_STAR, description: 中心的輻條、內環與橋：小小的通用加成 }',
    ]
    for t in TRUNKS:
        out.append('  %s: { name: %s, color: "%s", icon: %s, description: %s }' % (t['id'], t['name'], t['color'], t['icon'], t['description']))
    out.append('')
    out.append('nodes:')
    for n in nodes:
        mods = ', '.join('{ stat: %s, value: %s }' % (s, v) for s, v in n['mods'])
        line = '  - { id: %s, name: %s, x: %d, y: %d, trunk: %s, cost: %d' % (n['id'], n['name'], n['x'], n['y'], n['trunk'], n['cost'])
        if n['notable']:
            line += ', notable: true'
        if n['keystone']:
            line += ', keystone: true'
        if n['icon']:
            line += ', icon: %s' % n['icon']
        line += ', mods: [%s]' % mods
        if n['drawbacks']:
            line += ', drawbacks: [%s]' % ', '.join('{ stat: %s, value: %s }' % (s, v) for s, v in n['drawbacks'])
        line += ' }'
        out.append(line)
    out.append('')
    OUT.write_text('\n'.join(out), encoding='utf-8')
    print('wrote', OUT, len(nodes), 'nodes, total cost', total)


if __name__ == '__main__':
    main()
