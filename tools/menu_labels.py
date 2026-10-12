"""
主選單「圖示＋中文」版面的背景圖（2026-10）：python tools/menu_labels.py [字型] [輸出] [main|sponsor]
輸出 src/main/resources/web/menu/main_menu.png，資源包（UiPack）把它當成選單標題的一個字畫在箱子介面上。

改了按鈕的名稱、位置（MainMenu.LABELLED）要重新跑一次。需要 Pillow 與 Noto Sans CJK TC（或其他中文字型，改 FONT）。
圖是 2 倍解析度：箱子介面 176×126（GUI 像素）→ 352×252；字 8 GUI 像素高（跟聊天欄一樣大）。
"""
import sys
from PIL import Image, ImageDraw, ImageFont

FONT = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
OUT = sys.argv[2] if len(sys.argv) > 2 else "src/main/resources/web/menu/main_menu.png"
SCALE = 2
W, H = 176, 126
PANEL = (198, 198, 198, 255)  # 箱子介面的底色 #C6C6C6
TEXT = (63, 63, 63, 255)      # 跟標題字一樣的深灰

# 圖示在第 0、2、4 排，名稱寫在下面那一排（第 1、3、5 排）；空字串 = 那格沒有按鈕
LAYOUTS = {
    "main": {
        1: ["角色", "面板", "天賦", "技能", "稱號", "選角", "教學", "圖鑑", "設定"],
        3: ["背包", "倉庫", "過濾", "拆解", "公會", "市集", "隊伍", "DC", "贊助"],
        5: ["營地", "傳送", "任務", "委託", "活動", "競技", "突發", "", ""],
    },
    # 贊助選單（2026-10，SponsorMenus.Main.LABELLED）：買東西的（擴充、改名卡、全服加倍、抽獎箱）收在「星城」（星幣商城）裡
    "sponsor": {
        1: ["贊助", "月卡", "卡幣", "累積", "商城", "", "名色", "稱號", "飛行"],
        3: ["星城", "星券", "", "", "", "", "", "", ""],
        5: ["時裝", "幻化", "動作", "", "", "", "", "", ""],
    },
}
LAYOUT = sys.argv[3] if len(sys.argv) > 3 else "main"
LABELS = LAYOUTS[LAYOUT]

img = Image.new("RGBA", (W * SCALE, H * SCALE), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
font = ImageFont.truetype(FONT, 8 * SCALE, index=3)  # TTC 裡第 4 個是 TC（繁體）
for row, names in LABELS.items():
    top = 17 + 18 * row
    # 蓋掉這一排的格子框（這排不放東西，只寫字）
    draw.rectangle([7 * SCALE, top * SCALE, (7 + 18 * 9) * SCALE - 1, (top + 18) * SCALE - 1], fill=PANEL)
    for col, name in enumerate(names):
        if not name:
            continue
        cx = (8 + 18 * col + 8) * SCALE
        cy = (top + 9) * SCALE
        # 太寬的（例如 Discord）縮小字，塞進一格（16 GUI 像素）
        size = 8 * SCALE
        cell_font = font
        box = draw.textbbox((0, 0), name, font=cell_font)
        while box[2] - box[0] > 16 * SCALE and size > 8:
            size -= 1
            cell_font = ImageFont.truetype(FONT, size, index=3)
            box = draw.textbbox((0, 0), name, font=cell_font)
        w, h = box[2] - box[0], box[3] - box[1]
        draw.text((cx - w / 2 - box[0], cy - h / 2 - box[1]), name, font=cell_font, fill=TEXT)
# 右上角一個幾乎透明的點：原版量字寬時會裁掉右邊全透明的欄位，留著它寬度才固定是 176（標題的位移照這個算）
img.putpixel((W * SCALE - 1, 0), (198, 198, 198, 1))
img.save(OUT)
print("wrote", OUT, img.size)
