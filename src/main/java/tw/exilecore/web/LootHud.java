package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 撿到東西的角落提示（2026-10）：快捷欄右邊、從快捷欄那一排往上疊的小卡片（稀有度顏色的框＋物品圖示＋名稱 ×數量），
 * 最新的在最下面，幾秒後變暗、消失。畫在動作列上（{@link tw.exilecore.character.HotbarSides#renderLoot}）。
 * <p>
 * 資源包的字型 {@code exilecore:loothud_0} … {@code _5}：第 r 個整排往上移 r × {@value #ROW_HEIGHT} 格。
 * 框與圖示沿用「最近撿到」卡片（{@link LootCards}）的貼圖與字元；名稱的字用 GNU Unifont 畫（跟原版的中文同一套），
 * 收資料檔（data/*.yml）裡出現過的所有字——物品、通貨、傳奇、詞綴的名稱都在裡面。
 * 往上最多 5 行：再高 ascent 會比 height 大（字型不允許），就要像 {@link HudFont} 那樣補透明的空間，貼圖會變很大。
 */
public final class LootHud {

    /** 第 0～5 行（0 = 快捷欄那一排）。 */
    public static final int ROWS = 6;
    public static final int ROW_HEIGHT = 10;
    /** 字從這裡開始編號（跟框、位移、圖示的字元錯開）。 */
    private static final char TEXT_START = '';
    private static final int MAX_TEXT = 0xF8FF - TEXT_START;
    private static final int CELL = 16;
    /** 跟 {@link HudFont} 一樣：動作列的字往下 57 格，8 格高的字在快捷欄中間。 */
    private static final int BASE_ASCENT = 7 - 57;
    private static final int WIDE = 9;
    private static final int NARROW = 6;

    private record Glyph(char code, int advance) {
    }

    private static volatile Map<Character, Glyph> glyphs = Map.of();
    private static volatile Collection<String> texts = List.of();

    private LootHud() {
    }

    /** 第 row 行的字型。 */
    public static String font(final int row) {
        return "exilecore:loothud_" + Math.max(0, Math.min(ROWS - 1, row));
    }

    /** 資源包有做出來（字都收好了）。 */
    public static boolean ready() {
        return !glyphs.isEmpty();
    }

    /** 要收進字型的字：產生資源包之前由 {@link WebModule} 給（資料檔的內容）。 */
    static void useTexts(final Collection<String> value) {
        texts = List.copyOf(value);
    }

    /** text 的寬度（GUI 像素，含字距）；沒收進字型的字不算（畫的時候也跳過）。空白算 4。 */
    public static int width(final String text) {
        final Map<Character, Glyph> map = glyphs;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == ' ') {
                width += 4;
            } else {
                final Glyph glyph = map.get(c);
                if (glyph != null) {
                    width += glyph.advance;
                }
            }
        }
        return width;
    }

    /** 換成字型裡的字（空白換成往右 4；沒收進字型的字跳過）。 */
    public static String encode(final String text) {
        final Map<Character, Glyph> map = glyphs;
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == ' ') {
                out.append(LootCards.shift(4));
            } else {
                final Glyph glyph = map.get(c);
                if (glyph != null) {
                    out.append(glyph.code);
                }
            }
        }
        return out.toString();
    }

    /** 移 px 個像素（負的往左）。 */
    public static String shift(final int px) {
        return LootCards.shift(px);
    }

    /** 一張卡片的總寬度（框的左緣到右緣）。 */
    public static int cardWidth(final String text) {
        return 2 + 1 + 8 + 3 + width(text) + 3 + 2;
    }

    /**
     * 一張卡片（MiniMessage，要包在 {@link #font(int)} 裡面）：畫完筆停在卡片的右緣。
     *
     * @param color 框與名稱的顏色（#rrggbb）
     * @param icon  圖示代號（{@link LootCards#icons()}；null = 通用的）
     * @param text  名稱（純文字，含「×數量」）
     * @param faded 快消失了：變暗
     */
    public static String card(final String color, final String icon, final String text, final boolean faded) {
        final int textWidth = width(text);
        final int inner = 1 + 8 + 3 + textWidth + 3;
        final StringBuilder frame = new StringBuilder();
        frame.append(LootCards.LEFT).append(shift(-1));
        int rest = inner;
        for (int bit = LootCards.MID_BITS - 1; bit >= 0; bit--) {
            while (rest >= (1 << bit)) {
                frame.append((char) (LootCards.MID + bit)).append(shift(-1)); // 每個字後面原版多空 1 像素：退回來
                rest -= 1 << bit;
            }
        }
        frame.append(LootCards.RIGHT).append(shift(-1));
        final int total = 2 + inner + 2;
        final String frameColor = faded ? "#3a3a3a" : color;
        final String textColor = faded ? "#707070" : color;
        // 框畫完回到框裡面（左 2＋留白 1）、圖示（8 寬＋字距 1）、空 2、名稱，最後補到右緣
        return "<" + frameColor + ">" + frame + "</" + frameColor + ">" + shift(-(total - 3))
                + (faded ? "<#707070>" : "<white>") + LootCards.iconChar(icon) + shift(2)
                + "<" + textColor + ">" + encode(text) + "</" + textColor + ">"
                + shift(total - (3 + 9 + 2 + textWidth));
    }

    // ---- 產生資源包 ----

    /** 寫 assets/exilecore/font/loothud_0..5.json 與名稱的字的貼圖（要在 {@link UiPack#lootFont} 之後：沿用它的貼圖）。 */
    static void build(final Map<String, byte[]> files) throws IOException {
        final TreeSet<Character> wanted = new TreeSet<>();
        for (char c = '!'; c <= '~'; c++) {
            wanted.add(c);
        }
        wanted.add('×');
        for (final String text : texts) {
            for (int i = 0; text != null && i < text.length(); i++) {
                final char c = text.charAt(i);
                if (c > ' ' && !Character.isSurrogate(c) && !Character.isISOControl(c) && (c < 0xE000 || c > 0xF8FF)) {
                    wanted.add(c);
                }
            }
        }
        final List<Character> list = new ArrayList<>(wanted);
        if (list.size() > MAX_TEXT) {
            list.subList(MAX_TEXT, list.size()).clear();
        }
        // 名稱的字：16 欄的大圖，每格 16×16（顯示 8 格高）
        final int columns = 16;
        final int sheetRows = Math.max(1, (list.size() + columns - 1) / columns);
        final BufferedImage sheet = new BufferedImage(columns * CELL, sheetRows * CELL, BufferedImage.TYPE_INT_ARGB);
        final Map<Character, Glyph> map = new HashMap<>();
        final JsonArray textChars = new JsonArray();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            final char c = list.get(i);
            final char code = (char) (TEXT_START + i);
            final boolean wide = c >= 0x2E80;
            final int cellWidth = wide ? CELL : 10;
            final int x = (i % columns) * CELL;
            final int y = (i / columns) * CELL;
            final byte[] uni = HudFont.unifontGlyph(c);
            if (uni != null) {
                final int w = uni[0];
                final int left = x + Math.max(0, (cellWidth - 1 - Math.min(w, cellWidth - 1)) / 2);
                for (int gy = 0; gy < CELL; gy++) {
                    final int bits = (uni[1 + gy * 2] & 0xFF) << 8 | uni[2 + gy * 2] & 0xFF;
                    for (int gx = 0; gx < Math.min(w, cellWidth - 1); gx++) {
                        if ((bits & 1 << (15 - gx)) != 0) {
                            sheet.setRGB(left + gx, y + gy, 0xFFFFFFFF);
                        }
                    }
                }
            }
            // 最右邊一欄放一個幾乎透明的點：客戶端照最右邊不透明的那一欄算字寬，這樣每個字的寬度都是固定的
            sheet.setRGB(x + cellWidth - 1, y, 0x01FFFFFF);
            map.put(c, new Glyph(code, wide ? WIDE : NARROW));
            line.append(code);
            if (line.length() == columns) {
                textChars.add(line.toString());
                line = new StringBuilder();
            }
        }
        if (!line.isEmpty()) {
            while (line.length() < columns) {
                line.append('\u0000');
            }
            textChars.add(line.toString());
        }
        files.put("assets/exilecore/textures/font/loothud_text.png", png(sheet));

        // 圖示：沿用 loot_icons.png（UiPack.lootFont 畫的），一樣 16 欄
        final List<String> icons = LootCards.icons();
        final int iconRows = (icons.size() + columns - 1) / columns;
        final JsonArray iconChars = new JsonArray();
        for (int row = 0; row < iconRows; row++) {
            final StringBuilder icon = new StringBuilder();
            for (int column = 0; column < columns; column++) {
                final int index = row * columns + column;
                icon.append(index < icons.size() ? (char) (LootCards.ICON_START + index) : '\u0000');
            }
            iconChars.add(icon.toString());
        }

        final JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        final JsonObject advances = new JsonObject();
        for (int bit = 0; bit < LootCards.SHIFT_BITS; bit++) {
            advances.addProperty(String.valueOf((char) (LootCards.NEG + bit)), -(1 << bit));
            advances.addProperty(String.valueOf((char) (LootCards.POS + bit)), 1 << bit);
        }
        space.add("advances", advances);

        for (int r = 0; r < ROWS; r++) {
            final int ascent = BASE_ASCENT + r * ROW_HEIGHT; // r ≤ 5 時 ≤ 0，不會比 height 大
            final JsonArray providers = new JsonArray();
            providers.add(space.deepCopy());
            providers.add(bitmap("exilecore:font/loot_left.png", ascent + 1, 9, List.of(String.valueOf(LootCards.LEFT))));
            providers.add(bitmap("exilecore:font/loot_right.png", ascent + 1, 9, List.of(String.valueOf(LootCards.RIGHT))));
            for (int bit = 0; bit < LootCards.MID_BITS; bit++) {
                providers.add(bitmap("exilecore:font/loot_mid_" + (1 << bit) + ".png", ascent + 1, 9,
                        List.of(String.valueOf((char) (LootCards.MID + bit)))));
            }
            providers.add(bitmap("exilecore:font/loot_icons.png", ascent, 8, strings(iconChars)));
            providers.add(bitmap("exilecore:font/loothud_text.png", ascent, 8, strings(textChars)));
            final JsonObject root = new JsonObject();
            root.add("providers", providers);
            files.put("assets/exilecore/font/loothud_" + r + ".json", root.toString().getBytes(StandardCharsets.UTF_8));
        }
        glyphs = Map.copyOf(map);
    }

    private static List<String> strings(final JsonArray array) {
        final List<String> out = new ArrayList<>();
        array.forEach(element -> out.add(element.getAsString()));
        return out;
    }

    private static JsonObject bitmap(final String file, final int ascent, final int height, final List<String> rows) {
        final JsonObject bitmap = new JsonObject();
        bitmap.addProperty("type", "bitmap");
        bitmap.addProperty("file", file);
        bitmap.addProperty("ascent", ascent);
        bitmap.addProperty("height", height);
        final JsonArray chars = new JsonArray();
        rows.forEach(chars::add);
        bitmap.add("chars", chars);
        return bitmap;
    }

    private static byte[] png(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
