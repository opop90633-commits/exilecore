package tw.exilecore.item;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 傳奇物品的專屬外觀（{@code web/unique_art.txt}）：每件一張 16×16 的像素圖，用文字寫（字母 = 調色盤的顏色，. = 透明），
 * 介面資源包產生時畫成貼圖；物品上用 custom_model_data 的字串 {@code unique:代號} 選到它，
 * 沒套資源包的玩家看到的還是原本的物品。弓、弩另外有拉弓的幾張（{@code 代號_pulling_0..2}、{@code 代號_arrow}）。
 * 純資料，不依賴 Bukkit。
 */
public final class UniqueArt {

    public static final String RESOURCE = "/web/unique_art.txt";
    public static final int SIZE = 16;

    /** 一張圖：ARGB，列優先（[y][x]），0 = 透明。 */
    public record Sprite(String id, int[][] argb) {
        public int width() {
            return argb[0].length;
        }

        public int height() {
            return argb.length;
        }

        /** 放大 factor 倍（每個像素變成 factor×factor；拿在手上的矛要 32×32）。 */
        public Sprite scaled(final int factor) {
            final int[][] out = new int[height() * factor][width() * factor];
            for (int y = 0; y < out.length; y++) {
                for (int x = 0; x < out[y].length; x++) {
                    out[y][x] = argb[y / factor][x / factor];
                }
            }
            return new Sprite(id, out);
        }
    }

    private static volatile Map<String, Sprite> cached;

    private UniqueArt() {
    }

    /** 內建的那份（讀不到就是空的）。 */
    public static Map<String, Sprite> sprites() {
        Map<String, Sprite> result = cached;
        if (result == null) {
            try (InputStream in = UniqueArt.class.getResourceAsStream(RESOURCE)) {
                result = in == null ? Map.of() : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (final IOException | IllegalArgumentException e) {
                result = Map.of();
            }
            cached = result;
        }
        return result;
    }

    /** 有專屬外觀的傳奇代號（不含拉弓的那幾張）。 */
    public static Set<String> ids() {
        return sprites().keySet().stream().filter(id -> !isFrame(id)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 這件傳奇有沒有專屬外觀。 */
    public static boolean has(final String uniqueId) {
        return uniqueId != null && sprites().containsKey(uniqueId) && !isFrame(uniqueId);
    }

    /** 拉弓、上弦的附屬圖。 */
    static boolean isFrame(final String id) {
        return id.matches(".*_pulling_[0-9]$") || id.endsWith("_arrow");
    }

    /** 物品上 custom_model_data 字串的前綴。 */
    public static final String KEY_PREFIX = "unique:";

    /** 物品上 custom_model_data 的字串。 */
    public static String modelKey(final String uniqueId) {
        return KEY_PREFIX + uniqueId;
    }

    /** 解析檔案內容（單元測試直接餵字串）。 */
    public static Map<String, Sprite> parse(final String text) {
        final Map<String, Sprite> result = new LinkedHashMap<>();
        String id = null;
        Map<Character, Integer> palette = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        int lineNumber = 0;
        for (final String raw : text.split("\\R")) {
            lineNumber++;
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("item ")) {
                finish(result, id, palette, rows);
                id = line.substring(5).strip();
                if (!id.matches("[a-z0-9_]+")) {
                    throw new IllegalArgumentException("unique_art.txt 第 " + lineNumber + " 行：代號只能用小寫字母、數字、底線");
                }
                palette = new LinkedHashMap<>();
                rows = new ArrayList<>();
            } else if (line.length() > 2 && line.charAt(1) == '=') {
                final String hex = line.substring(2).strip().toLowerCase(Locale.ROOT);
                if (!hex.matches("[0-9a-f]{6}([0-9a-f]{2})?")) {
                    throw new IllegalArgumentException("unique_art.txt 第 " + lineNumber + " 行：顏色要寫 RRGGBB 或 RRGGBBAA");
                }
                final int rgb = Integer.parseInt(hex.substring(0, 6), 16);
                final int alpha = hex.length() == 8 ? Integer.parseInt(hex.substring(6), 16) : 0xFF;
                palette.put(line.charAt(0), (alpha << 24) | rgb);
            } else {
                if (id == null) {
                    throw new IllegalArgumentException("unique_art.txt 第 " + lineNumber + " 行：要先寫 item 代號");
                }
                if (line.length() != SIZE) {
                    throw new IllegalArgumentException("unique_art.txt 第 " + lineNumber + " 行（" + id + "）：每行要 " + SIZE + " 個字");
                }
                rows.add(line);
            }
        }
        finish(result, id, palette, rows);
        return Collections.unmodifiableMap(result);
    }

    private static void finish(final Map<String, Sprite> result, final String id, final Map<Character, Integer> palette, final List<String> rows) {
        if (id == null) {
            return;
        }
        if (rows.size() != SIZE) {
            throw new IllegalArgumentException("unique_art.txt：" + id + " 要 " + SIZE + " 行，卻是 " + rows.size());
        }
        final int[][] argb = new int[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final char ch = rows.get(y).charAt(x);
                if (ch == '.') {
                    continue;
                }
                final Integer color = palette.get(ch);
                if (color == null) {
                    throw new IllegalArgumentException("unique_art.txt：" + id + " 用了沒定義的顏色「" + ch + "」");
                }
                argb[y][x] = color;
            }
        }
        if (result.put(id, new Sprite(id, argb)) != null) {
            throw new IllegalArgumentException("unique_art.txt：" + id + " 重複");
        }
    }
}
