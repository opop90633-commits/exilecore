package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-11-02 照站長：PoE 風格的生命球（左）、魔力球（右），資源包的部分（沒裝星骸輔助模組的人；組字在
 * {@link tw.exilecore.character.OrbHud}）。這次之前生命、能量護盾、魔力是原版愛心、藍色的吸收愛心、經驗等級＋動作列的數字。
 * <p>
 * 字型 {@value #FONT}（不用著色器）：
 * <ul>
 *   <li>球的每一層是一個字（{@value #CELL}×{@value #CELL} 的圖、顯示 {@value OrbArt#BOX} 格高；ascent 讓它從動作列那一行往下移到
 *       畫面底往上 {@value #TOP} 格，也就是快捷欄的高度、聊天欄最下面那行的下面）。生命、魔力、能量護盾（混沌免疫）的液面、
 *       能量護盾罩層、保留（生命、魔力共用）、心靈昇華標記各 {@value #LEVELS} 段預先畫好；疊的順序：液體、罩層、保留、框、心靈昇華、數字。
 *       2026-11-02 第二版照站長改成 PoE 1 的樣子；這次之前能量護盾是框外面的外環。</li>
 *   <li>數字是 3×5 的小點陣（白色＋黑邊，照文字顏色上色），每一行一套字（ascent 不同）。</li>
 *   <li>左右的位置用 {@link HudFont#shift} 的位移字元，以畫面中間為準（動作列是置中的）：貼著快捷欄、在副手格與攻擊冷卻指示的外側。</li>
 * </ul>
 * 原版的愛心（坐騎的除外）、飢餓條換成透明的圖（球取代它們；盔甲、氧氣、坐騎血量照原位）。
 */
public final class OrbFont {

    public static final String FONT = "exilecore:orb";
    /** 液面、外環、保留各分幾段。 */
    public static final int LEVELS = 20;
    /** 圖的解析度是 GUI 像素的兩倍。 */
    static final int CELL = OrbArt.BOX * 2;
    /** 球頂在畫面底往上幾格（球底在往上 1 格；聊天欄最下面那行在往上 40 格）。 */
    public static final int TOP = 39;
    /** 球的字實際往前走幾格（38 格寬的圖＋字距 1）。 */
    public static final int ORB_ADVANCE = OrbArt.BOX + 1;
    /** 數字的字實際往前走幾格（5 格寬＋字距 1）。 */
    public static final int DIGIT_ADVANCE = 6;
    /** 左球的右緣、右球的左緣離畫面中間多遠：快捷欄半寬 91＋副手格 29（左手慣用時副手在右邊，攻擊冷卻指示也在右邊 97～115）＋空 2。 */
    public static final int INNER = 91 + 29 + 2;
    /** 球外側到畫面中間多遠（快捷欄兩側的字要從這裡再往外）。 */
    public static final int OUTER = INNER + OrbArt.BOX;

    /**
     * 字碼（圖的 6 排 × 21 格）：生命、魔力、能量護盾（混沌免疫的液體）0～20；能量護盾罩層 1～20＋框；保留 1～20；心靈昇華 1～20。
     */
    static final char LIFE = '';
    static final char MANA = '';
    static final char SHIELD_LIQUID = '';
    static final char SHIELD = '';
    static final char FRAME = '';
    static final char RESERVED = '';
    static final char MOM = '';
    /** 每排最後一格沒用到的（圖是空的）。 */
    static final char BLANK_RESERVED = '';
    static final char BLANK_MOM = '';
    /** 數字：第 line 行從 DIGITS + 16 × line 開始，順序照 {@link #DIGIT_SET}。 */
    public static final char DIGITS = '';
    public static final String DIGIT_SET = "0123456789,/.km%";
    /** 數字可以放的行（球格子裡的 y，GUI 像素）；最後一行在框的下緣（心靈昇華的比例）。 */
    public static final int[] LINES = {9, 12, 15, 18, 21, 29};

    private static volatile boolean ready;

    private OrbFont() {
    }

    /** 產生過資源包（字型在裡面）。 */
    public static boolean ready() {
        return ready;
    }

    // ---- 字（OrbHud 用）----

    public static char life(final int segment) {
        return (char) (LIFE + clampSegment(segment));
    }

    public static char mana(final int segment) {
        return (char) (MANA + clampSegment(segment));
    }

    /** 混沌免疫：整顆是能量護盾的液體。 */
    public static char shieldLiquid(final int segment) {
        return (char) (SHIELD_LIQUID + clampSegment(segment));
    }

    /** 能量護盾罩層第 segment 段（1～20；0 不用畫）。 */
    public static char shield(final int segment) {
        return (char) (SHIELD + Math.max(1, clampSegment(segment)) - 1);
    }

    /** 保留的生命、魔力第 segment 段（1～20；0 不用畫）。 */
    public static char reserved(final int segment) {
        return (char) (RESERVED + Math.max(1, clampSegment(segment)) - 1);
    }

    /** 心靈昇華標記第 segment 段（1～20；0 不用畫）。 */
    public static char mom(final int segment) {
        return (char) (MOM + Math.max(1, clampSegment(segment)) - 1);
    }

    public static char frame() {
        return FRAME;
    }

    private static int clampSegment(final int segment) {
        return Math.max(0, Math.min(LEVELS, segment));
    }

    /** 第 line 行（{@link #LINES} 的 index）的字 c；不認得的字回傳 0。 */
    public static char digit(final int line, final char c) {
        final int index = DIGIT_SET.indexOf(c);
        if (index < 0 || line < 0 || line >= LINES.length) {
            return 0;
        }
        return (char) (DIGITS + line * 16 + index);
    }

    /** 排字用的寬度：逗號、小數點 2，其他 4（3 格的字＋1 格空）。 */
    public static int step(final char c) {
        return c == ',' || c == '.' ? 2 : 4;
    }

    /** 一段數字看起來多寬（含黑邊）。 */
    public static int width(final String text) {
        int width = 1;
        for (int i = 0; i < text.length(); i++) {
            width += step(text.charAt(i));
        }
        return width;
    }

    /**
     * 球上的數字：一萬以下照千分位（「1,234」），以上縮寫（「12.3k」「123k」「1.2m」），最多 5 個字，球上放得下。
     */
    public static String compact(final long value) {
        final long v = Math.max(0, value);
        if (v < 10_000) {
            return v < 1000 ? Long.toString(v) : v / 1000 + "," + String.format(Locale.ROOT, "%03d", v % 1000);
        }
        if (v < 100_000) {
            return v / 1000 + "." + v % 1000 / 100 + "k";
        }
        if (v < 1_000_000) {
            return v / 1000 + "k";
        }
        if (v < 10_000_000) {
            return v / 1_000_000 + "." + v % 1_000_000 / 100_000 + "m";
        }
        return Math.min(99_999, v / 1_000_000) + "m";
    }

    // ---- 產生資源包 ----

    /** 原版的愛心（坐騎的不算）與飢餓條：換成透明的。 */
    static final List<String> HIDDEN_SPRITES = hiddenSprites();

    private static List<String> hiddenSprites() {
        final List<String> out = new java.util.ArrayList<>();
        for (final String c : List.of("container", "container_blinking", "container_hardcore", "container_hardcore_blinking")) {
            out.add("heart/" + c);
        }
        for (final String type : List.of("", "poisoned_", "withered_", "absorbing_", "frozen_")) {
            for (final String hardcore : List.of("", "hardcore_")) {
                for (final String part : List.of("full", "half")) {
                    for (final String blink : List.of("", "_blinking")) {
                        out.add("heart/" + type + hardcore + part + blink);
                    }
                }
            }
        }
        for (final String food : List.of("food_empty", "food_half", "food_full", "food_empty_hunger", "food_half_hunger", "food_full_hunger")) {
            out.add(food);
        }
        return List.copyOf(out);
    }

    static void build(final Map<String, byte[]> files) throws IOException {
        // 球的每一層：6 排 × 21 格
        final int columns = LEVELS + 1;
        final int rowCount = 6;
        final BufferedImage sheet = new BufferedImage(columns * CELL, rowCount * CELL, BufferedImage.TYPE_INT_ARGB);
        final StringBuilder[] rows = new StringBuilder[rowCount];
        for (int r = 0; r < rowCount; r++) {
            rows[r] = new StringBuilder();
        }
        final double phase = 0.8; // 資源包的液面是靜止的：固定一點點波紋
        for (int k = 0; k <= LEVELS; k++) {
            final double level = k / (double) LEVELS;
            put(sheet, k, 0, OrbArt.liquid(CELL, OrbArt.Liquid.LIFE, level, 0, phase));
            put(sheet, k, 1, OrbArt.liquid(CELL, OrbArt.Liquid.MANA, level, 0, phase));
            put(sheet, k, 2, OrbArt.liquid(CELL, OrbArt.Liquid.SHIELD, level, 0, phase));
            rows[0].append(life(k));
            rows[1].append(mana(k));
            rows[2].append(shieldLiquid(k));
            if (k < LEVELS) {
                final double next = (k + 1) / (double) LEVELS;
                put(sheet, k, 3, OrbArt.shield(CELL, next, phase));
                put(sheet, k, 4, OrbArt.reserved(CELL, next));
                put(sheet, k, 5, OrbArt.mom(CELL, next));
                rows[3].append(shield(k + 1));
                rows[4].append(reserved(k + 1));
                rows[5].append(mom(k + 1));
            }
        }
        put(sheet, LEVELS, 3, OrbArt.frame(CELL));
        put(sheet, LEVELS, 4, new int[CELL * CELL]);
        put(sheet, LEVELS, 5, new int[CELL * CELL]);
        rows[3].append(FRAME);
        rows[4].append(BLANK_RESERVED);
        rows[5].append(BLANK_MOM);
        final JsonArray rowArray = new JsonArray();
        for (final StringBuilder row : rows) {
            rowArray.add(row.toString());
        }
        files.put("assets/exilecore/textures/font/orb.png", png(sheet));

        final JsonArray providers = new JsonArray();
        final JsonObject orbs = new JsonObject();
        orbs.addProperty("type", "bitmap");
        orbs.addProperty("file", "exilecore:font/orb.png");
        orbs.addProperty("ascent", ascent(0));
        orbs.addProperty("height", OrbArt.BOX);
        orbs.add("chars", rowArray);
        providers.add(orbs);

        // 數字：一張圖，每一行的字各用一個 provider（ascent 不同）
        files.put("assets/exilecore/textures/font/orb_digits.png", png(digitSheet()));
        for (int line = 0; line < LINES.length; line++) {
            final StringBuilder chars = new StringBuilder();
            for (int i = 0; i < DIGIT_SET.length(); i++) {
                chars.append(digit(line, DIGIT_SET.charAt(i)));
            }
            final JsonObject digits = new JsonObject();
            digits.addProperty("type", "bitmap");
            digits.addProperty("file", "exilecore:font/orb_digits.png");
            digits.addProperty("ascent", ascent(LINES[line]));
            digits.addProperty("height", 7);
            final JsonArray row = new JsonArray();
            row.add(chars.toString());
            digits.add("chars", row);
            providers.add(digits);
        }
        final JsonObject root = new JsonObject();
        root.add("providers", providers);
        files.put("assets/exilecore/font/orb.json", root.toString().getBytes(StandardCharsets.UTF_8));

        final byte[] clear = png(new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB));
        for (final String sprite : HIDDEN_SPRITES) {
            files.put("assets/minecraft/textures/gui/sprites/hud/" + sprite + ".png", clear);
        }
        ready = true;
    }

    /**
     * 球格子裡第 y 格的字要用的 ascent：動作列的字（ascent 7）頂在畫面高 − 72，ascent 每少 1 往下 1 格；
     * 球頂在畫面高 − {@value #TOP}。
     */
    static int ascent(final int y) {
        return 7 - (72 - TOP) - y;
    }

    private static void put(final BufferedImage sheet, final int column, final int row, final int[] pixels) {
        final int x0 = column * CELL;
        final int y0 = row * CELL;
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                sheet.setRGB(x0 + x, y0 + y, pixels[y * CELL + x]);
            }
        }
        // 最右上角一個幾乎透明的點：客戶端照最右邊不透明的那一欄算字寬，這樣每個字都是 38 格寬（往前走 39）
        if (sheet.getRGB(x0 + CELL - 1, y0) >>> 24 == 0) {
            sheet.setRGB(x0 + CELL - 1, y0, 0x01FFFFFF);
        }
    }

    /** 3×5 的數字（窄的字 1 格），一格 5×7：白色的字、外面一圈黑邊。 */
    private static final String[][] DIGIT_ART = {
            {"###", "#.#", "#.#", "#.#", "###"}, // 0
            {".#.", "##.", ".#.", ".#.", "###"}, // 1
            {"###", "..#", "###", "#..", "###"}, // 2
            {"###", "..#", ".##", "..#", "###"}, // 3
            {"#.#", "#.#", "###", "..#", "..#"}, // 4
            {"###", "#..", "###", "..#", "###"}, // 5
            {"###", "#..", "###", "#.#", "###"}, // 6
            {"###", "..#", "..#", ".#.", ".#."}, // 7
            {"###", "#.#", "###", "#.#", "###"}, // 8
            {"###", "#.#", "###", "..#", "###"}, // 9
            {".", ".", ".", "#", "#"},           // ,
            {"..#", "..#", ".#.", "#..", "#.."}, // /
            {".", ".", ".", ".", "#"},           // .
            {"#..", "#.#", "##.", "#.#", "#.#"}, // k
            {"...", "###", "###", "#.#", "#.#"}, // m
            {"#..", "..#", ".#.", "#..", "..#"}, // %
    };

    static BufferedImage digitSheet() {
        final BufferedImage image = new BufferedImage(5 * DIGIT_SET.length(), 7, BufferedImage.TYPE_INT_ARGB);
        for (int g = 0; g < DIGIT_ART.length; g++) {
            final String[] art = DIGIT_ART[g];
            final int x0 = g * 5;
            final boolean[][] body = new boolean[7][5];
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < art[y].length(); x++) {
                    body[y + 1][x + 1] = art[y].charAt(x) == '#';
                }
            }
            for (int y = 0; y < 7; y++) {
                for (int x = 0; x < 5; x++) {
                    if (body[y][x]) {
                        image.setRGB(x0 + x, y, 0xFFFFFFFF);
                        continue;
                    }
                    boolean near = false;
                    for (int oy = -1; oy <= 1 && !near; oy++) {
                        for (int ox = -1; ox <= 1; ox++) {
                            final int nx = x + ox;
                            final int ny = y + oy;
                            if (nx >= 0 && nx < 5 && ny >= 0 && ny < 7 && body[ny][nx]) {
                                near = true;
                                break;
                            }
                        }
                    }
                    if (near) {
                        image.setRGB(x0 + x, y, 0xE0000000);
                    }
                }
            }
            if (image.getRGB(x0 + 4, 0) >>> 24 == 0) {
                image.setRGB(x0 + 4, 0, 0x01FFFFFF); // 固定字寬（往前走 6）
            }
        }
        return image;
    }

    private static byte[] png(final BufferedImage image) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
