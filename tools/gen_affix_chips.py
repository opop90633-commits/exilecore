"""《星骸》詞綴標籤（2026-10）：每個標籤畫成一張 16 格高的小方塊（顯示 8 格高，兩倍解析度），
放進 src/main/resources/web/chips/<代號>.png；清單（代號、名稱、底色）要跟 AffixChips.java 一樣。
改完重跑：python tools/gen_affix_chips.py（要有中文字型，例如 Noto Sans CJK）。"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'src/main/resources/web/chips'
CHIPS = [  # 代號, 名稱, 底色
    ('damage', '傷害', '#a83232'), ('physical', '物理', '#6e6e6e'), ('elemental', '元素', '#b0702a'),
    ('fire', '火焰', '#c8461e'), ('cold', '冰冷', '#2a72b0'), ('lightning', '閃電', '#a88a14'),
    ('chaos', '混沌', '#74389e'), ('attack', '攻擊', '#3a5a9a'), ('caster', '法術', '#5a48a8'),
    ('speed', '速度', '#2a8064'), ('critical', '暴擊', '#b0602a'), ('life', '生命', '#b03a4a'),
    ('mana', '魔力', '#3a48b0'), ('armour', '護甲', '#4a5a7a'), ('evasion', '閃避', '#3a7448'),
    ('energy_shield', '能量護盾', '#3e62a0'), ('block', '格擋', '#5a5a64'), ('resistance', '抗性', '#806428'),
    ('attribute', '屬性', '#686848'), ('minion', '召喚物', '#4a7438'), ('ailment', '異常', '#84466a'),
    ('gem', '技能', '#2a6684'), ('aura', '光環', '#6e5e24'), ('spirit', '精魂', '#56669a'),
    ('curse', '詛咒', '#5a2a6a'), ('flask', '藥劑', '#346a6a'), ('charm', '護符', '#5a6a34'),
    ('projectile', '投射物', '#4a6070'),
    # 2026-10：技能寶石的標籤
    ('area', '範圍', '#5a6a7a'), ('melee', '近戰', '#7a4a3a'), ('strike', '打擊', '#8a5a3a'), ('slam', '猛擊', '#7a5a2a'),
    ('duration', '持續', '#4a6a5a'), ('cooldown', '冷卻', '#4a5a6a'), ('movement', '移動', '#2a7a6a'), ('travel', '突進', '#2a6a5a'),
    ('nova', '新星', '#6a5a8a'), ('chain', '連鎖', '#6a6a2a'), ('mark', '印記', '#8a3a5a'), ('grenade', '榴彈', '#7a6a3a'),
    ('corpse', '屍體', '#5a4a4a'), ('totem', '圖騰', '#6a4a2a'), ('bell', '鐘', '#8a7a3a'), ('trap', '陷阱', '#5a5a3a'),
    ('herald', '捷光環', '#7a6a2a'), ('warcry', '戰吼', '#8a4a2a'), ('persistent', '常駐', '#4a5a7a'), ('command', '指令', '#4a6a3a'),
    ('crystal', '冰晶', '#3a7a9a'), ('shapeshift', '變身', '#6a4a7a'), ('bear', '熊形態', '#6a4a2a'), ('werewolf', '狼人形態', '#4a4a6a'),
    ('wyvern', '飛龍形態', '#3a6a6a'), ('summon', '召喚', '#4a7438'),
    ('ranged', '遠程', '#3a5a7a'),   # 2026-10：遠程召喚物（骸骨射手、術士）
]
FONTS = ['/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
         'C:/Windows/Fonts/msjhbd.ttc', 'C:/Windows/Fonts/msjh.ttc']


def font():
    for path in FONTS:
        try:
            return ImageFont.truetype(path, 12, index=3 if path.endswith('.ttc') and 'Noto' in path else 0)
        except OSError:
            continue
    raise SystemExit('找不到中文字型')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    f = font()
    for cid, name, bg in CHIPS:
        box = f.getbbox(name)
        tw = box[2] - box[0]
        w = tw + 8
        img = Image.new('RGBA', (w, 16), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        d.rounded_rectangle((0, 1, w - 1, 15), radius=3, fill=bg)
        d.text((4 - box[0], 8 - (box[1] + box[3]) / 2), name, font=f, fill='#ffffff')
        img.save(OUT / (cid + '.png'))
    (OUT / 'index.txt').write_text('\n'.join(c[0] for c in CHIPS) + '\n', encoding='utf-8')
    print('wrote', len(CHIPS), 'chips')


if __name__ == '__main__':
    main()
