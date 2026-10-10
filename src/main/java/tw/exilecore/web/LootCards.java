package tw.exilecore.web;

import tw.exilecore.currency.CurrencyArt;
import tw.exilecore.item.UniqueArt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「最近撿到」的卡片（2026-10）：右邊側欄每一行畫成一張小卡片——稀有度顏色的底框、物品的小圖示、名稱。
 * 用資源包的字型 {@value #FONT} 拼出來：框是幾個白色的字（照文字的顏色著色），圖示是通貨／傳奇的像素圖縮成 8×8。
 * 伺服器與資源包（{@link UiPack}）共用這裡的字元對照，兩邊一定對得上。
 */
public final class LootCards {

    /** 卡片用的字型。 */
    public static final String FONT = "exilecore:loot";
    /** 框的左邊、右邊（2 像素寬、9 像素高）。 */
    static final char LEFT = '';
    static final char RIGHT = '';
    /** 框的中間：寬 1、2、4…128 像素（上下兩條線＋淡淡的底色）。 */
    static final char MID = '';
    static final int MID_BITS = 8;
    /** 位移：往左 1、2、4…（NEG）、往右（POS）。 */
    static final char NEG = '';
    static final char POS = '';
    static final int SHIFT_BITS = 9;
    /** 圖示從這裡開始編號（照 {@link #icons()} 的順序）。 */
    static final char ICON_START = '';
    /** 沒有專屬圖的東西用的通用圖示。 */
    public static final String GENERIC = "g:item";
    /** 珠寶的通用圖示。 */
    public static final String JEWEL = "g:jewel";

    private static volatile List<String> iconKeys;
    private static volatile Map<String, Character> iconChars;

    private LootCards() {
    }

    /** 所有圖示的代號：通用的兩個、通貨的圖（c:圖的代號）、傳奇的圖（u:傳奇代號），照字母排。 */
    public static List<String> icons() {
        List<String> keys = iconKeys;
        if (keys == null) {
            final List<String> list = new ArrayList<>();
            list.add(GENERIC);
            list.add(JEWEL);
            CurrencyArt.sprites().keySet().stream().sorted().forEach(id -> list.add("c:" + id));
            UniqueArt.ids().stream().sorted().forEach(id -> list.add("u:" + id));
            keys = List.copyOf(list);
            final Map<String, Character> chars = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                chars.put(keys.get(i), (char) (ICON_START + i));
            }
            iconChars = Map.copyOf(chars);
            iconKeys = keys;
        }
        return keys;
    }

    /** 這個圖示的字元（沒有就用通用的）。 */
    static char iconChar(final String key) {
        icons();
        final Character c = key == null ? null : iconChars.get(key);
        return c != null ? c : iconChars.get(GENERIC);
    }

    /** 移 px 個像素（負的往左）。 */
    static String shift(final int px) {
        final StringBuilder out = new StringBuilder();
        final int abs = Math.abs(px);
        int rest = abs;
        for (int bit = SHIFT_BITS - 1; bit >= 0; bit--) {
            while (rest >= (1 << bit)) {
                out.append((char) ((px < 0 ? NEG : POS) + bit));
                rest -= 1 << bit;
            }
        }
        return out.toString();
    }

    /**
     * 一張卡片（MiniMessage）。
     *
     * @param color     框的顏色（#rrggbb）
     * @param icon      圖示代號（{@link #icons()}；null = 通用的）
     * @param name      名稱（MiniMessage，自己帶顏色）
     * @param nameWidth 名稱的寬度（像素，{@link HudFont#estimateVanilla}）
     * @param faded     快消失了：框、圖示、字都變暗
     */
    public static String card(final String color, final String icon, final String name, final int nameWidth, final boolean faded) {
        // 框：左 2＋內容＋右 2；內容 = 留白 1、圖示 8、空 3、名稱、留白 3
        final int inner = 1 + 8 + 3 + Math.max(0, nameWidth) + 3;
        final StringBuilder frame = new StringBuilder();
        frame.append(LEFT).append(shift(-1));
        int rest = inner;
        for (int bit = MID_BITS - 1; bit >= 0; bit--) {
            while (rest >= (1 << bit)) {
                frame.append((char) (MID + bit)).append(shift(-1)); // 每個字後面原版會多空 1 像素：退回來
                rest -= 1 << bit;
            }
        }
        frame.append(RIGHT).append(shift(-1));
        final int total = 2 + inner + 2;
        final String frameColor = faded ? "#3a3a3a" : color;
        return "<font:" + FONT + "><" + frameColor + ">" + frame + "</" + frameColor + ">"
                + shift(-(total - 3)) // 回到框裡面：左邊 2＋留白 1
                + (faded ? "<#707070>" : "<white>") + iconChar(icon) + shift(2) + "</font>"
                + (faded ? "<#707070>" + stripColors(name) : name)
                + "<font:" + FONT + ">" + shift(4) + "</font>";
    }

    /** 快消失時名稱改成一樣的暗灰色：拿掉 MiniMessage 的顏色標籤。 */
    private static String stripColors(final String mini) {
        return mini.replaceAll("<(/?)(#[0-9a-fA-F]{6}|[a-z_]+)>", "").replaceAll("<[^>]*>", "");
    }
}
