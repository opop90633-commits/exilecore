package tw.exilecore.client.core;

/**
 * 2026-11-02 照站長：生命球、魔力球放哪裡（跟資源包的 tw.exilecore.web.OrbFont 一樣）。純 Java（測試用）。
 * <ul>
 *   <li>貼著快捷欄兩側：左球的右緣、右球的左緣離畫面中間 {@value #INNER} 格（快捷欄半寬 91＋副手格 29＋空 2），
 *       在副手格（左手慣用時在右邊）與攻擊冷卻指示（快捷欄右邊 97～115）的外側；不放在畫面角落（左下是聊天、右下是字幕）。</li>
 *   <li>球底貼著畫面底往上 1 格，高度 {@value OrbArt#BOX} × 縮放；縮放大到球頂會超過聊天欄最下面那行（畫面底往上 {@value #CHAT_LINE}）時縮小。</li>
 *   <li>1.3：有球時模組的技能列放在快捷欄正上方、增益列在左球外側兩排（HudRenderer）；1.2 是把它們推到球的外側（會疊到字幕、聊天）。</li>
 * </ul>
 */
public final class OrbLayout {

    public static final int INNER = 91 + 29 + 2;
    /** 聊天欄最下面那行的下緣在畫面底往上幾格。 */
    public static final int CHAT_LINE = 40;
    /** 球跟旁邊的東西至少空幾格。 */
    public static final int GAP = 4;

    private OrbLayout() {
    }

    /** 球的大小（GUI 像素）：照縮放，但不超過聊天欄最下面那行。 */
    public static int size(final float scale) {
        final int wanted = Math.round(OrbArt.BOX * (scale > 0 ? scale : 1));
        return Math.max(16, Math.min(CHAT_LINE - 2, wanted));
    }

    /** 左球的左緣（GUI 像素）。 */
    public static int leftX(final int guiWidth, final int size) {
        return guiWidth / 2 - INNER - size;
    }

    /** 右球的左緣。 */
    public static int rightX(final int guiWidth) {
        return guiWidth / 2 + INNER;
    }

    /** 兩顆球的上緣。 */
    public static int top(final int guiHeight, final int size) {
        return guiHeight - 1 - size;
    }

    /** 球外側（相對畫面中間）：兩側的東西至少要從這裡開始（跟資源包的 HotbarSides.ORB_EDGE 一樣）。 */
    public static int outer(final int size) {
        return INNER + size + GAP;
    }
}
