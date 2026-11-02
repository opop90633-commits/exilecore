package tw.exilecore.client.core;

/**
 * 2026-11-02 照站長（1.4）：整個畫面的區塊配置（有球的時候；GUI 縮放 2、3、4 都不重疊，HudLayoutTest 檢查）。純 Java（測試用）。
 * <pre>
 *  ┌──────────────────────────────────────────────────────────┐
 *  │            Boss 血條（原版，最上面往下排）     ┌────────┐ │
 *  │            目標框（推到最後一條 Boss 血條下面） 右上角：地圖資訊框、小地圖（B、C 組）│
 *  │                                                ┌────────┐ │
 *  │                                                │ 面板群 │ │  右側中段（畫面高度 40% 起）：戰鬥數據、狀態、光環、召喚物、引導、任務、撿到
 *  │                                                │（寬度不 │ │  （寬度避開畫面中間的 Boss 血條、提示列）
 *  │                       ＋準心                   │ 超過）  │ │
 *  │              提示列（最多 4 則，往上疊）        └────────┘ │
 *  │              （動作列）                                    │
 *  │   異常圖示     技能列（快捷欄正上方）                       │
 *  │ 增益＋光環 (生命球)[副手] 快捷欄 [  ] (魔力球) 戰鬥資源      │
 *  └──────────────────────────────────────────────────────────┘
 * </pre>
 * 左球外側只放跟生命有關的（上面是異常圖示，球框是守護環），右球外側只放戰鬥資源；增益、光環在左球左邊（1.3 的位置），
 * 其他（召喚物、撿到的東西）一律是右邊的面板。聊天欄打開時會蓋到左下（原版的聊天本來就會蓋到這一區）。
 */
public final class HudLayout {

    /** 一個區塊（GUI 像素，x、y 是左上角）。 */
    public record Box(int x, int y, int w, int h) {
        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public boolean intersects(final Box other) {
            return w > 0 && h > 0 && other.w > 0 && other.h > 0 && x < other.right() && other.x < right() && y < other.bottom()
                    && other.y < bottom();
        }
    }

    /** 原版 Boss 血條：第一條在 y=12，一條佔 19 格（條 5 格＋名字），超過畫面 1/3 就不畫了。 */
    public static final int BOSS_FIRST = 12;
    public static final int BOSS_STEP = 19;
    public static final int BOSS_WIDTH = 182;
    /** 目標框的寬度、高度（scale 1）。 */
    public static final int TARGET_WIDTH = 168; // 150＋背景兩邊各 2＋打不動時左邊的鎖頭
    public static final int TARGET_HEIGHT = 32;
    /** 異常圖示的大小與間距。 */
    public static final int ICON = 14;
    public static final int ICON_STEP = 16;
    /** 提示列：最下面那則的下緣在畫面底往上幾格（動作列在往上 72～62 那一行）、一則的高度。 */
    public static final int NOTICE_BOTTOM = 84;
    public static final int NOTICE_LINE = 11;
    /**
     * 面板群的上緣在畫面高度的幾成（右上角讓給地圖資訊框、小地圖、原版的狀態效果圖示；2026-11-02 照站長從右側中段開始）、
     * 下緣在畫面底往上幾格（下面是戰鬥資源、字幕）、最寬多少。
     */
    public static final double PANEL_TOP = 0.4;
    public static final int PANEL_BOTTOM = 46;
    public static final int PANEL_MAX_WIDTH = 150;
    /** 戰鬥資源最寬多少；比 {@link #COMPACT_RES} 窄就只畫點點與細條、不寫字。 */
    public static final int RES_MAX_WIDTH = 150;
    public static final int COMPACT_RES = 110;

    private HudLayout() {
    }

    /** 畫了幾條 Boss 血條（超過畫面 1/3 的不畫）。 */
    public static int bossBarsDrawn(final int bars, final int guiHeight) {
        int y = BOSS_FIRST;
        int drawn = 0;
        for (int i = 0; i < bars; i++) {
            drawn++;
            y += BOSS_STEP;
            if (y >= guiHeight / 3) {
                break;
            }
        }
        return drawn;
    }

    /** 最後一條 Boss 血條的下緣（沒有是 0）。 */
    public static int bossBarsBottom(final int bars, final int guiHeight) {
        final int drawn = bossBarsDrawn(bars, guiHeight);
        return drawn == 0 ? 0 : BOSS_FIRST + BOSS_STEP * (drawn - 1) + 5;
    }

    /** 2026-11-02 照站長：目標框的 y——設定的位置，但至少在最後一條 Boss 血條下面 6 格（這次之前寫死在 32，第二條以後的血條被蓋住）。 */
    public static int targetY(final int configY, final int bars, final int guiHeight) {
        return bars <= 0 ? configY : Math.max(configY, bossBarsBottom(bars, guiHeight) + 6);
    }

    /** 異常圖示一排幾個：只在左球正上方（球多寬就放幾個；2026-11-02 照站長：左邊是增益欄，不再往左排）。 */
    public static int iconsPerRow(final int orbSize) {
        return Math.max(1, (orbSize + 2) / ICON_STEP);
    }

    /** 異常圖示最多幾排。 */
    public static final int ICON_ROWS = 4;

    /** 守護量的字：左球正上方（異常圖示的下面、球的上面那 5 格）。 */
    public static Box guardText(final int guiWidth, final int guiHeight, final int orbSize) {
        return new Box(OrbLayout.leftX(guiWidth, orbSize), OrbLayout.top(guiHeight, orbSize) - 5, orbSize, 5);
    }

    /** 異常圖示的區塊：左球正上方、右緣對齊左球右緣，由下往上排（最多 {@link #ICON_ROWS} 排；畫不下的寫「+N」在最後一格）。 */
    public static Box statusIcons(final int guiWidth, final int guiHeight, final int orbSize, final int count) {
        if (count <= 0) {
            return new Box(0, 0, 0, 0);
        }
        final int perRow = iconsPerRow(orbSize);
        final int rows = Math.min(ICON_ROWS, (count + perRow - 1) / perRow);
        final int columns = Math.min(count, perRow);
        final int right = guiWidth / 2 - OrbLayout.INNER;
        final int bottom = OrbLayout.top(guiHeight, orbSize) - 5;
        return new Box(right - columns * ICON_STEP + 2, bottom - rows * ICON_STEP + 2, columns * ICON_STEP - 2, rows * ICON_STEP - 2);
    }

    /** 戰鬥資源的區塊：右球外側、跟球同高。 */
    public static Box resources(final int guiWidth, final int guiHeight, final int orbSize) {
        final int x = guiWidth / 2 + OrbLayout.outer(orbSize);
        return new Box(x, OrbLayout.top(guiHeight, orbSize), Math.max(0, Math.min(RES_MAX_WIDTH, guiWidth - 2 - x)), orbSize);
    }

    /**
     * 2026-11-02 照站長：有倒數的增益那一區（左球左邊，從 {@link #BUFF_COLUMN_BOTTOM} 往上排到畫面中間的準心區下面，往左到畫面左邊）。
     * 這次之前（1.3、1.4 第一版）是左球外側最下面兩、三排，跟光環混在一起，放不下就「+N」。
     */
    public static Box buffColumn(final int guiWidth, final int guiHeight, final int orbSize) {
        final int right = guiWidth / 2 - OrbLayout.outer(orbSize);
        final int top = buffTop(guiHeight);
        return new Box(2, top, Math.max(0, right - 2), guiHeight - BUFF_COLUMN_BOTTOM - top);
    }

    // ---- 2026-11-02 照站長（第三版）：增益欄預設放在聊天框上面、聊天打開時淡出 ----

    /** 原版聊天框最下面那行的下緣在畫面底往上幾格（ChatComponent：畫面高 − 40）。 */
    public static final int CHAT_BOTTOM = 40;
    /** 原版聊天框預設：寬 320（chatWidth 1.0）、收起時高 90（10 行 × 9，chatHeightUnfocused 0.4375）、打開時高 180。 */
    public static final int CHAT_WIDTH = 320;
    public static final int CHAT_HEIGHT = 90;
    public static final int CHAT_HEIGHT_FOCUSED = 180;
    /** 聊天打開時最下面的輸入框（畫面底往上 14～2，整個畫面寬）。 */
    public static final int CHAT_INPUT = 14;

    /**
     * 原版聊天框的範圍（純函式，測試用）：左下角，右緣是 (寬 + 8) × 縮放（字從 4 開始，底色兩邊各多 4），下緣在畫面高 − 40。
     *
     * @param width  ChatComponent.getWidth（GUI 像素，縮放 1）
     * @param height ChatComponent.getHeight（收起或打開的那個，GUI 像素，縮放 1）
     * @param scale  chatScale
     */
    public static Box chatBox(final int guiWidth, final int guiHeight, final int width, final int height, final double scale) {
        final int bottom = guiHeight - CHAT_BOTTOM;
        final int h = Math.min(bottom, (int) Math.ceil(height * scale));
        return new Box(0, bottom - h, Math.min(guiWidth, (int) Math.ceil((width + 8) * scale)), h);
    }

    /** 增益欄的右緣最多到畫面中間往左幾格（讓給準心、提示列、目標框）。 */
    public static final int BUFF_CENTER_GAP = 100;
    /** 增益欄跟聊天框之間空幾格。 */
    public static final int BUFF_CHAT_GAP = 3;

    /**
     * 2026-11-02 照站長：增益欄的預設範圍（純函式，測試用）：<b>聊天框（收起時）的上面</b>，從畫面左邊往右一欄一欄排、每欄由下往上
     * （{@link SideLayout#columnFromLeft}），最上面到畫面高度 1/4（左上角讓給隊伍框、機制清單），最右到畫面中間往左 {@value #BUFF_CENTER_GAP} 格。
     * 聊天框上面放不下一排（畫面很矮、聊天框調得很高）時退回 {@link #buffColumn}（左球左邊）。
     * 這次之前是 {@link #buffColumn}：左球左邊從畫面底往上排，第 3 排以上進到聊天框（打開聊天、剛收到訊息時疊在一起）。
     * 玩家可以在拖動 HUD 的畫面（O 鍵 → 拖動）拖到別的地方。
     *
     * @param chat 聊天框收起時的範圍（{@link #chatBox}）
     */
    public static Box buffArea(final int guiWidth, final int guiHeight, final int orbSize, final Box chat) {
        final int bottom = chat.y() - BUFF_CHAT_GAP;
        final int top = guiHeight / 4;
        final int right = guiWidth / 2 - BUFF_CENTER_GAP;
        if (bottom - top < SideLayout.ROW || right - 2 < 48) {
            return buffColumn(guiWidth, guiHeight, orbSize);
        }
        return new Box(2, top, right - 2, bottom - top);
    }

    /** 聊天打開時增益欄的不透明度（0～1）：疊到聊天框或輸入框才淡出。 */
    public static final float CHAT_FADE = 0.3f;

    /**
     * 2026-11-02 照站長：聊天打開時，增益欄（拖過的位置）疊到聊天框（打開時的範圍）或最下面的輸入框就淡出到半透明，不然照舊。純函式（測試用）。
     *
     * @param chatOpen 聊天畫面開著
     * @param chat     聊天框打開時的範圍（{@link #chatBox}，高度用 chatHeightFocused）
     */
    public static float chatFade(final Box buffs, final boolean chatOpen, final Box chat, final int guiWidth, final int guiHeight) {
        if (!chatOpen || buffs == null) {
            return 1f;
        }
        final Box input = new Box(0, guiHeight - CHAT_INPUT, guiWidth, CHAT_INPUT);
        return buffs.intersects(chat) || buffs.intersects(input) ? CHAT_FADE : 1f;
    }

    /** 增益欄最下面那格的下緣在畫面底往上幾格（下面是光環那一行）。 */
    public static final int BUFF_COLUMN_BOTTOM = 14;

    /** 增益欄最上面到哪（畫面中間的準心區往下 12 格）。 */
    public static int buffTop(final int guiHeight) {
        return guiHeight / 2 + 12;
    }

    /** 2026-11-02 照站長：固定的光環那一行（畫面最底下，從左邊往右排到左球外側）。 */
    public static Box auraStrip(final int guiWidth, final int guiHeight, final int orbSize) {
        final int right = guiWidth / 2 - OrbLayout.outer(orbSize);
        return new Box(2, guiHeight - AURA_BOTTOM - 11, Math.max(0, right - 2), 11);
    }

    /** 光環那一行的下緣在畫面底往上幾格。 */
    public static final int AURA_BOTTOM = 1;

    /** 1.4 第一版：伺服器不寫快捷欄那一排的光環時，增益列最下面那排的下緣在畫面底往上幾格（沒有球時的增益列還在用）。 */
    public static final int BUFF_BOTTOM_SIDES = 3;

    /** 增益列最下面那排的下緣在畫面底往上幾格（跟 HudRenderer.BUFF_BOTTOM 一樣）。 */
    public static final int BUFF_BOTTOM = 15;

    /**
     * 面板群多寬：不超過 {@link #PANEL_MAX_WIDTH}，也不碰到畫面中間的 Boss 血條、技能列（畫面窄的時候寧可窄一點，底才能放到戰鬥資源上面；
     * 至少 {@link #PANEL_MIN_WIDTH}，再窄就讓底縮到動作列上面）。
     */
    public static int panelWidth(final int guiWidth) {
        final int wide = Math.min(PANEL_MAX_WIDTH, (guiWidth - BOSS_WIDTH) / 2 - 6);
        final int besideSkills = guiWidth - 2 - (guiWidth / 2 + SKILL_HALF + 2);
        return Math.max(60, besideSkills >= PANEL_MIN_WIDTH ? Math.min(wide, besideSkills) : wide);
    }

    public static final int PANEL_MIN_WIDTH = 120;

    /**
     * 2026-11-02 照站長：地圖資訊框的下緣最多到畫面高度的幾成（再長就縮短，{@link MapPanelLayout#fit}）：下面留給側邊面板。
     */
    public static final double MAP_PANEL_LIMIT = 0.55;

    public static int mapPanelLimit(final int guiHeight) {
        return (int) Math.round(guiHeight * MAP_PANEL_LIMIT);
    }

    /**
     * 2026-11-02 照站長：側邊面板從哪裡開始：畫面高度 {@link #PANEL_TOP} 成，但地圖資訊框在同一欄（左右有重疊）而且比較低時接在它下面 4 格。
     * 純函式（測試用）。
     *
     * @param map 地圖資訊框這一幀的範圍（含拖過的偏移；沒畫 null）
     */
    public static int panelsTop(final int guiWidth, final int guiHeight, final Box map) {
        final Box base = panels(guiWidth, guiHeight);
        if (map == null || map.w() <= 0 || map.right() <= base.x() || map.x() >= base.right() || map.y() >= base.bottom()) {
            return base.y();
        }
        return Math.max(base.y(), map.bottom() + 4);
    }

    /** 面板群的區塊。 */
    public static Box panels(final int guiWidth, final int guiHeight) {
        final int width = panelWidth(guiWidth);
        final int x = guiWidth - 2 - width;
        // 面板群的左緣進到技能列、動作列那幾排的範圍（畫面很窄）時：底改到動作列上面
        final int bottom = x < guiWidth / 2 + SKILL_HALF + 2 ? guiHeight - PANEL_BOTTOM_NARROW : guiHeight - PANEL_BOTTOM;
        final int top = (int) Math.round(guiHeight * PANEL_TOP);
        return new Box(x, top, width, Math.max(Panels.HEADER, bottom - top));
    }

    /** 有球時技能列的半寬（10 格 × 20 − 2）、窄畫面時面板群的下緣在畫面底往上幾格（動作列上面）。 */
    public static final int SKILL_HALF = 99;
    public static final int PANEL_BOTTOM_NARROW = 74;

    /** 提示列最多畫幾則：不超過 {@link NoticeFeed#MAX}，最上面那則也不碰到準心（畫面中間往下 10 格）。 */
    public static int noticeLines(final int guiHeight) {
        return Math.max(1, Math.min(NoticeFeed.MAX, (guiHeight - NOTICE_BOTTOM - guiHeight / 2 - 10) / NOTICE_LINE));
    }

    /** 提示列一則最寬多少（畫面中間往兩邊，不碰到右邊的面板群）。 */
    public static int noticeWidth(final int guiWidth) {
        // 2026-11-02：也不超過兩顆球的內側（左球上面疊了好幾排異常圖示時不碰到）；這次之前最寬 280
        return Math.min(2 * (OrbLayout.INNER - 2), 2 * (guiWidth / 2 - panelWidth(guiWidth) - 8));
    }

    /** 提示列的區塊（畫 lines 則）。 */
    public static Box notices(final int guiWidth, final int guiHeight, final int lines) {
        final int width = noticeWidth(guiWidth);
        return new Box(guiWidth / 2 - width / 2, guiHeight - NOTICE_BOTTOM - lines * NOTICE_LINE, width, lines * NOTICE_LINE);
    }
}
