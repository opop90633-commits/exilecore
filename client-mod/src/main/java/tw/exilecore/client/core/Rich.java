package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 2026-11-02 照站長（D 組）：插件送來的說明文字（簡單的 MiniMessage：{@code <#rrggbb>}、{@code <gray>}… 換顏色，其他標籤拿掉，
 * {@code \<} 是 {@code <}、{@code \\} 是 {@code \}）拆成「一段字、一個顏色」。跟插件的 MiniParts 同一套規則。純 Java（測試用）。
 */
public final class Rich {

    /** 一段字；color 是 0xRRGGBB。 */
    public record Run(String text, int color) {
    }

    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA), Map.entry("dark_green", 0x00AA00),
            Map.entry("dark_aqua", 0x00AAAA), Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
            Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA), Map.entry("grey", 0xAAAAAA), Map.entry("dark_gray", 0x555555),
            Map.entry("dark_grey", 0x555555), Map.entry("blue", 0x5555FF), Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF),
            Map.entry("red", 0xFF5555), Map.entry("light_purple", 0xFF55FF), Map.entry("yellow", 0xFFFF55), Map.entry("white", 0xFFFFFF));

    private Rich() {
    }

    public static List<Run> parse(final String text) {
        return parse(text, 0xFFFFFF);
    }

    /** fallback：沒寫顏色的字。 */
    public static List<Run> parse(final String text, final int fallback) {
        final List<Run> runs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return runs;
        }
        int color = fallback;
        final StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && (text.charAt(i + 1) == '<' || text.charAt(i + 1) == '\\')) {
                current.append(text.charAt(i + 1)); // \< 是 <、\\ 是 \（插件 ItemRenderer.escape 的寫法）
                i += 2;
                continue;
            }
            if (c == '<') {
                final int close = text.indexOf('>', i);
                if (close > i) {
                    final String tag = text.substring(i + 1, close).trim().toLowerCase(Locale.ROOT);
                    final Integer next = colorOf(tag);
                    if (next != null && next != color) {
                        flush(runs, current, color);
                        color = next;
                    } else if (tag.startsWith("/") && color != fallback && closesColor(tag)) {
                        flush(runs, current, color);
                        color = fallback;
                    }
                    i = close + 1;
                    continue;
                }
            }
            current.append(c);
            i++;
        }
        flush(runs, current, color);
        return runs;
    }

    public static String plain(final String text) {
        final StringBuilder out = new StringBuilder();
        for (final Run run : parse(text)) {
            out.append(run.text());
        }
        return out.toString();
    }

    /**
     * 太長的一行折成好幾行（中文沒有空白，照字寬折；英文也照字折，不另外找空白）。
     *
     * @param width 一個字串有多寬（畫面的字型）
     */
    public static List<List<Run>> wrap(final List<Run> line, final int maxWidth, final ToIntFunction<String> width) {
        final List<List<Run>> out = new ArrayList<>();
        List<Run> current = new ArrayList<>();
        int used = 0;
        for (final Run run : line) {
            final StringBuilder piece = new StringBuilder();
            for (int i = 0; i < run.text().length(); ) {
                final int cp = run.text().codePointAt(i);
                final String ch = new String(Character.toChars(cp));
                final int w = width.applyAsInt(ch);
                if (used + w > maxWidth && used > 0) {
                    if (!piece.isEmpty()) {
                        current.add(new Run(piece.toString(), run.color()));
                        piece.setLength(0);
                    }
                    out.add(current);
                    current = new ArrayList<>();
                    used = 0;
                    if (ch.equals(" ")) {
                        i += Character.charCount(cp);
                        continue; // 折行的地方的空白不要
                    }
                }
                piece.append(ch);
                used += w;
                i += Character.charCount(cp);
            }
            if (!piece.isEmpty()) {
                current.add(new Run(piece.toString(), run.color()));
            }
        }
        out.add(current);
        return out;
    }

    private static boolean closesColor(final String tag) {
        final String name = tag.substring(1);
        return name.startsWith("#") || name.startsWith("color") || NAMED.containsKey(name);
    }

    private static Integer colorOf(final String tag) {
        String name = tag;
        if (name.startsWith("color:") || name.startsWith("c:")) {
            name = name.substring(name.indexOf(':') + 1);
        }
        if (name.startsWith("#") && name.length() == 7) {
            try {
                return Integer.parseInt(name.substring(1), 16);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return NAMED.get(name);
    }

    private static void flush(final List<Run> runs, final StringBuilder current, final int color) {
        if (current.isEmpty()) {
            return;
        }
        if (!runs.isEmpty() && runs.getLast().color() == color) {
            final Run last = runs.removeLast();
            runs.add(new Run(last.text() + current, color));
        } else {
            runs.add(new Run(current.toString(), color));
        }
        current.setLength(0);
    }
}
