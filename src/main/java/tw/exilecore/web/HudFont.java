package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 快捷欄兩側的小字（2026-10：開著的光環在左、召喚物數量在右）。資源包的字型 {@value #FONT}：
 * <ul>
 *   <li>位移字元（space）：往左、往右移幾個 GUI 像素（2 的次方，組起來任何距離都行）；</li>
 *   <li>字：產生資源包時用伺服器的字型把要用到的字（技能名稱、數字…）畫成點陣，往下移到快捷欄那一排（動作列在快捷欄上面 50 幾格）。
 *       每個字的寬度是固定的（中文 9、其他 6），所以算得出整段多寬、放得準。</li>
 * </ul>
 * 動作列整段是置中的：主要那段（生命、魔力…）照原版字型估寬度，兩側的字前後用位移字元補成淨寬 0，
 * 不會把主要那段推歪；估的寬度差一點時兩側只會偏幾個像素。
 */
public final class HudFont {

    public static final String FONT = "exilecore:hud";
    /** 位移字元：負的 2^i（i = 0..10）、正的 2^i。 */
    private static final char NEG = '';
    private static final char POS = '';
    private static final int SHIFT_BITS = 11;
    /** 圖示：光環的星、召喚物的骷髏。 */
    public static final char STAR = '';
    public static final char SKULL = '';
    /** 一般的字從這裡開始編號。 */
    private static final char GLYPH = '';
    private static final int MAX_GLYPHS = 0xF8FF - GLYPH;
    /** 字畫在 16×16 的格子裡，顯示 8 格高（跟原版的中文一樣是兩倍解析度）。 */
    private static final int CELL = 16;
    /** 動作列的字頂在 畫面高 − 72；快捷欄的中間在 畫面高 − 11：往下 57 格，8 格高的字就在快捷欄中間。 */
    private static final int ASCENT = 7 - 57;
    private static final int WIDE = 9;
    /** 2026-10：直排（召喚物一行一種）用的字型：第 k 行往上移 k × {@value #ROW_HEIGHT} 格（第 0 行就是 {@link #FONT}）。 */
    public static final int ROWS = 32; // 2026-10：實際上不設限（32 行已經頂到畫面上方）
    public static final int ROW_HEIGHT = 10;
    private static final int NARROW = 6;

    private record Glyph(char code, int advance) {
    }

    private static volatile Map<Character, Glyph> glyphs = Map.of();

    private HudFont() {
    }

    /** 第 row 行用的字型（0 = 快捷欄那一排，越大越上面）。 */
    public static String font(final int row) {
        return row <= 0 ? FONT : FONT + "_r" + Math.min(ROWS - 1, row);
    }

    /** 這段字都畫得出來（產生資源包時有收進去）。 */
    public static boolean ready() {
        return !glyphs.isEmpty();
    }

    /** text 的寬度（GUI 像素，含字距）；有字沒收進字型回傳 -1。空白算 4。 */
    public static int width(final String text) {
        final Map<Character, Glyph> map = glyphs;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == ' ') {
                width += 4;
            } else if (c == STAR || c == SKULL) {
                width += WIDE;
            } else {
                final Glyph glyph = map.get(c);
                if (glyph == null) {
                    return -1;
                }
                width += glyph.advance;
            }
        }
        return width;
    }

    /** 換成字型裡的字（空白換成往右 4）；先用 {@link #width} 確定都有。 */
    public static String encode(final String text) {
        final Map<Character, Glyph> map = glyphs;
        final StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == ' ') {
                out.append(shift(4));
            } else if (c == STAR || c == SKULL) {
                out.append(c);
            } else {
                final Glyph glyph = map.get(c);
                if (glyph != null) {
                    out.append(glyph.code);
                }
            }
        }
        return out.toString();
    }

    /** 移 px 個 GUI 像素（負的往左）的位移字元。 */
    public static String shift(final int px) {
        final StringBuilder out = new StringBuilder();
        final int abs = Math.abs(px);
        for (int bit = SHIFT_BITS - 1; bit >= 0; bit--) {
            if ((abs & (1 << bit)) != 0) {
                out.append((char) ((px < 0 ? NEG : POS) + bit));
            }
        }
        if (abs >= (1 << SHIFT_BITS)) {
            // 超過範圍：補最大的幾次
            int rest = abs - (abs & ((1 << SHIFT_BITS) - 1));
            while (rest > 0) {
                out.append((char) ((px < 0 ? NEG : POS) + SHIFT_BITS - 1));
                rest -= 1 << (SHIFT_BITS - 1);
            }
        }
        return out.toString();
    }

    /**
     * 原版字型的寬度（GUI 像素，含字距）：英數照原版 ascii 的寬度；其他字照 unifont 每個字實際的寬度
     * （2026-10：web/unifont_adv.bin，tools 用 GNU Unifont 算的；原版會照筆畫裁掉兩邊，所以每個中文字寬度不一樣）。
     */
    public static double estimateVanilla(final String plain) {
        final byte[] table = unifont();
        double width = 0;
        for (int i = 0; i < plain.length(); i++) {
            final char c = plain.charAt(i);
            if (c < 0x80) {
                width += asciiAdvance(c);
            } else if (Character.isHighSurrogate(c)) {
                width += 8;
                i++;
            } else if (fullWidth(c)) {
                width += c >= 0xAC00 && c <= 0xD7AF ? 8 : 9;
            } else {
                final int advance = table == null ? 0 : table[c] & 0xFF;
                width += advance > 0 ? advance : 8.5;
            }
        }
        return width;
    }

    /**
     * 原版 font/include/unifont.json 的 size_overrides（2026-10）：這些範圍（中日文、標點【】、全形、韓文）不照筆畫裁，
     * 一律當 16 寬 → 寬度 9（韓文音節從第 1 欄起算 → 8）。之前照筆畫算（7～8），中文越多差越多，TAB 欄位就對不齊。
     */
    private static boolean fullWidth(final char c) {
        return (c >= 0x3001 && c <= 0x30FF) || (c >= 0x3200 && c <= 0x9FFF) || (c >= 0x1100 && c <= 0x11FF)
                || (c >= 0x3130 && c <= 0x318F) || (c >= 0xA960 && c <= 0xA97F) || (c >= 0xAC00 && c <= 0xD7FF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF01 && c <= 0xFF5E);
    }

    private static volatile byte[] unifontTable;

    private static byte[] unifont() {
        byte[] table = unifontTable;
        if (table == null) {
            try (java.io.InputStream in = HudFont.class.getResourceAsStream("/web/unifont_adv.bin")) {
                table = in == null ? new byte[0] : in.readAllBytes();
            } catch (final IOException e) {
                table = new byte[0];
            }
            unifontTable = table;
        }
        return table.length == 65536 ? table : null;
    }

    private static int asciiAdvance(final char c) {
        return switch (c) {
            case ' ' -> 4;
            case '!', '\'', ',', '.', ':', ';', '|', 'i' -> 2;
            case '`', 'l' -> 3;
            case '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
            case '<', '>', 'f', 'k' -> 5;
            case '@', '~' -> 7;
            default -> c < 0x20 ? 0 : 6;
        };
    }

    // ---- 產生資源包 ----

    /** 把 texts 裡用到的字畫進字型（assets/exilecore/font/hud.json）。 */
    static void build(final Map<String, byte[]> files, final Collection<String> texts) throws IOException {
        final TreeSet<Character> wanted = new TreeSet<>();
        for (final char c : "0123456789/+×-".toCharArray()) {
            wanted.add(c);
        }
        for (final String text : texts) {
            if (text == null) {
                continue;
            }
            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (c > ' ' && !Character.isSurrogate(c) && !Character.isISOControl(c)) {
                    wanted.add(c);
                }
            }
        }
        final Font font = pickFont(wanted);
        final Map<Character, Glyph> map = new HashMap<>();
        final JsonArray providers = new JsonArray();

        final JsonObject space = new JsonObject();
        space.addProperty("type", "space");
        final JsonObject advances = new JsonObject();
        for (int bit = 0; bit < SHIFT_BITS; bit++) {
            advances.addProperty(String.valueOf((char) (NEG + bit)), -(1 << bit));
            advances.addProperty(String.valueOf((char) (POS + bit)), 1 << bit);
        }
        space.add("advances", advances);
        providers.add(space);

        // 圖示（自己畫的點陣）
        files.put("assets/exilecore/textures/font/hud_icons.png", png(icons()));
        providers.add(bitmap("exilecore:font/hud_icons.png", String.valueOf(STAR) + SKULL));

        // 字：每列 16 個
        final java.util.List<Character> list = new java.util.ArrayList<>(wanted);
        if (list.size() > MAX_GLYPHS) {
            list.subList(MAX_GLYPHS, list.size()).clear();
        }
        final int columns = 16;
        final int rows = Math.max(1, (list.size() + columns - 1) / columns);
        final BufferedImage sheet = new BufferedImage(columns * CELL, rows * CELL, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(Color.WHITE);
        final JsonArray chars = new JsonArray();
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            final char c = list.get(i);
            final char code = (char) (GLYPH + i);
            final boolean wide = c >= 0x2E80;
            final int cellWidth = wide ? CELL : 10;
            final int x = (i % columns) * CELL;
            final int y = (i / columns) * CELL;
            final Font use = font.canDisplay(c) ? font : new Font(Font.DIALOG, Font.PLAIN, font.getSize());
            g.setFont(use);
            final FontMetrics m = g.getFontMetrics(use);
            final int w = m.charWidth(c);
            final int baseline = y + (CELL - m.getHeight()) / 2 + m.getAscent();
            g.setClip(x, y, cellWidth - 1, CELL);
            g.drawString(String.valueOf(c), x + Math.max(0, (cellWidth - 1 - w) / 2), baseline);
            g.setClip(null);
            // 最右邊一欄放一個幾乎透明的點：客戶端照最右邊不透明的那一欄算字寬，這樣每個字的寬度都是固定的
            sheet.setRGB(x + cellWidth - 1, y, 0x01FFFFFF);
            map.put(c, new Glyph(code, cellWidth / 2 + 1));
            row.append(code);
            if (row.length() == columns) {
                chars.add(row.toString());
                row = new StringBuilder();
            }
        }
        g.dispose();
        if (!row.isEmpty()) {
            while (row.length() < columns) {
                row.append('\u0000');
            }
            chars.add(row.toString());
        }
        if (!list.isEmpty()) {
            files.put("assets/exilecore/textures/font/hud_text.png", png(sheet));
            final JsonObject text = new JsonObject();
            text.addProperty("type", "bitmap");
            text.addProperty("file", "exilecore:font/hud_text.png");
            text.addProperty("ascent", ASCENT);
            text.addProperty("height", 8);
            text.add("chars", chars);
            providers.add(text);
        }
        final JsonObject root = new JsonObject();
        root.add("providers", providers);
        files.put("assets/exilecore/font/hud.json", root.toString().getBytes(StandardCharsets.UTF_8));
        // 直排用（2026-10）：召喚物名稱用到的字，第 r 行整排往上移 r × ROW_HEIGHT。
        // 字型規定 ascent 不能比 height 大，所以每一格下面補透明的空間（格子變高、顯示的高度跟著變大，字的大小不變）
        final java.util.List<Character> rowChars = new java.util.ArrayList<>();
        for (final String text : rowTexts) {
            for (int i = 0; text != null && i < text.length(); i++) {
                final char c = text.charAt(i);
                if (map.containsKey(c) && !rowChars.contains(c)) {
                    rowChars.add(c);
                }
            }
        }
        for (final char c : "0123456789/+".toCharArray()) {
            if (map.containsKey(c) && !rowChars.contains(c)) {
                rowChars.add(c);
            }
        }
        final BufferedImage iconImage = icons();
        final int rowColumns = 16;
        final int rowRows = Math.max(1, (rowChars.size() + rowColumns - 1) / rowColumns);
        for (int r = 1; r < ROWS; r++) {
            final int padPx = r * ROW_HEIGHT * 2; // 貼圖是兩倍解析度
            final int cellH = CELL + padPx;
            final JsonArray rowProviders = new JsonArray();
            rowProviders.add(space.deepCopy());
            // 圖示
            final BufferedImage icons = new BufferedImage(CELL * 2, cellH, BufferedImage.TYPE_INT_ARGB);
            icons.getGraphics().drawImage(iconImage, 0, 0, null);
            files.put("assets/exilecore/textures/font/hud_icons_r" + r + ".png", png(icons));
            rowProviders.add(paddedBitmap("exilecore:font/hud_icons_r" + r + ".png", List.of(String.valueOf(STAR) + SKULL), r));
            // 字
            if (!rowChars.isEmpty()) {
                final BufferedImage padded = new BufferedImage(rowColumns * CELL, rowRows * cellH, BufferedImage.TYPE_INT_ARGB);
                final Graphics2D pg = padded.createGraphics();
                final List<String> lines = new java.util.ArrayList<>();
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < rowChars.size(); i++) {
                    final int index = list.indexOf(rowChars.get(i));
                    final int sx = (index % columns) * CELL;
                    final int sy = (index / columns) * CELL;
                    final int dx = (i % rowColumns) * CELL;
                    final int dy = (i / rowColumns) * cellH;
                    pg.drawImage(sheet, dx, dy, dx + CELL, dy + CELL, sx, sy, sx + CELL, sy + CELL, null);
                    line.append(map.get(rowChars.get(i)).code);
                    if (line.length() == rowColumns) {
                        lines.add(line.toString());
                        line = new StringBuilder();
                    }
                }
                pg.dispose();
                if (!line.isEmpty()) {
                    while (line.length() < rowColumns) {
                        line.append('\u0000');
                    }
                    lines.add(line.toString());
                }
                files.put("assets/exilecore/textures/font/hud_text_r" + r + ".png", png(padded));
                rowProviders.add(paddedBitmap("exilecore:font/hud_text_r" + r + ".png", lines, r));
            }
            final JsonObject rowRoot = new JsonObject();
            rowRoot.add("providers", rowProviders);
            files.put("assets/exilecore/font/hud_r" + r + ".json", rowRoot.toString().getBytes(StandardCharsets.UTF_8));
        }
        glyphs = Map.copyOf(map);
    }

    /** 要收進字型的字（技能名稱…）：產生資源包之前由 {@link WebModule} 給。 */
    private static volatile Collection<String> texts = java.util.List.of();

    static void useTexts(final Collection<String> value) {
        texts = java.util.List.copyOf(value);
    }

    static Collection<String> texts() {
        return texts;
    }

    /** 直排第 r 行的點陣：格子下面補了 r × ROW_HEIGHT（顯示格數）的透明空間，整排往上移同樣多。 */
    private static JsonObject paddedBitmap(final String file, final List<String> rows, final int r) {
        final JsonObject bitmap = new JsonObject();
        bitmap.addProperty("type", "bitmap");
        bitmap.addProperty("file", file);
        bitmap.addProperty("ascent", ASCENT + r * ROW_HEIGHT);
        bitmap.addProperty("height", 8 + r * ROW_HEIGHT);
        final JsonArray chars = new JsonArray();
        rows.forEach(chars::add);
        bitmap.add("chars", chars);
        return bitmap;
    }

    /** 直排（召喚物）要用到的字：召喚技能的名稱。 */
    private static volatile Collection<String> rowTexts = java.util.List.of();

    static void useRowTexts(final Collection<String> value) {
        rowTexts = java.util.List.copyOf(value);
    }

    private static JsonObject bitmap(final String file, final String row) {
        final JsonObject bitmap = new JsonObject();
        bitmap.addProperty("type", "bitmap");
        bitmap.addProperty("file", file);
        bitmap.addProperty("ascent", ASCENT);
        bitmap.addProperty("height", 8);
        final JsonArray chars = new JsonArray();
        chars.add(row);
        bitmap.add("chars", chars);
        return bitmap;
    }

    /** 伺服器上找一個畫得出中文的字型（Windows：微軟正黑體…；都沒有就用 Dialog）。 */
    private static Font pickFont(final Collection<Character> wanted) {
        final String[] names = {"Microsoft JhengHei", "Microsoft JhengHei UI", "PMingLiU", "MingLiU", "Microsoft YaHei",
                "Noto Sans CJK TC", "Noto Sans TC", "Noto Sans CJK SC", "PingFang TC", "SimSun", Font.DIALOG};
        final String probe = "喚屍光環";
        for (final String name : names) {
            final Font font = new Font(name, Font.PLAIN, 15);
            if (font.canDisplayUpTo(probe) == -1) {
                return font;
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, 15);
    }

    /** 星（光環）與骷髏（召喚物），16×16 白色點陣（顯示時照文字顏色著色）。 */
    private static BufferedImage icons() {
        final BufferedImage image = new BufferedImage(CELL * 2, CELL, BufferedImage.TYPE_INT_ARGB);
        final String[] star = {
                "................",
                ".......##.......",
                ".......##.......",
                "......####......",
                "......####......",
                ".##############.",
                "..############..",
                "....########....",
                ".....######.....",
                "....########....",
                "....###..###....",
                "...###....###...",
                "...##......##...",
                "..##........##..",
                "................",
                "...............,"};
        final String[] skull = {
                "................",
                ".....######.....",
                "...##########...",
                "..############..",
                "..############..",
                "..###..##..###..",
                "..##....#...##..",
                "..###..##..###..",
                "..############..",
                "...####..####...",
                "....########....",
                "....#.#..#.#....",
                "....########....",
                ".....######.....",
                "................",
                "...............,"};
        draw(image, star, 0);
        draw(image, skull, CELL);
        return image;
    }

    private static void draw(final BufferedImage image, final String[] rows, final int offset) {
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                final char c = rows[y].charAt(x);
                if (c == '#') {
                    image.setRGB(offset + x, y, 0xFFFFFFFF);
                } else if (c == ',') {
                    image.setRGB(offset + x, y, 0x01FFFFFF); // 固定字寬（最右一欄）
                }
            }
        }
    }

    private static byte[] png(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
