package tw.exilecore.clientmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-11-02：把插件的簡單 MiniMessage 文字（{@code <#ff7b3d>燃 2.5s  <#c48bff>咒·萎力}，AilmentService.statusText、hexText 產生的）
 * 拆成「一段字、一個顏色」給模組畫。只認 {@code <#rrggbb>} 與常見的顏色名稱；其他標籤（{@code </…>}、{@code <bold>}…）拿掉。
 * {@code \<} 是被跳脫的 {@code <}。純函式（測試用）。
 */
public final class MiniParts {

    /** 一段字。color 是 0xRRGGBB。 */
    public record Part(String text, int color) {
    }

    static final int DEFAULT_COLOR = 0xFFFFFF;

    private static final Map<String, Integer> NAMED = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("dark_blue", 0x0000AA), Map.entry("dark_green", 0x00AA00),
            Map.entry("dark_aqua", 0x00AAAA), Map.entry("dark_red", 0xAA0000), Map.entry("dark_purple", 0xAA00AA),
            Map.entry("gold", 0xFFAA00), Map.entry("gray", 0xAAAAAA), Map.entry("grey", 0xAAAAAA), Map.entry("dark_gray", 0x555555),
            Map.entry("dark_grey", 0x555555), Map.entry("blue", 0x5555FF), Map.entry("green", 0x55FF55), Map.entry("aqua", 0x55FFFF),
            Map.entry("red", 0xFF5555), Map.entry("light_purple", 0xFF55FF), Map.entry("yellow", 0xFFFF55), Map.entry("white", 0xFFFFFF));

    private MiniParts() {
    }

    public static List<Part> parse(final String text) {
        final List<Part> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        int color = DEFAULT_COLOR;
        final StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '<') {
                current.append('<');
                i += 2;
                continue;
            }
            if (c == '<') {
                final int close = text.indexOf('>', i);
                if (close > i) {
                    final String tag = text.substring(i + 1, close).trim().toLowerCase(Locale.ROOT);
                    final Integer next = colorOf(tag);
                    if (next != null && next != color) {
                        flush(parts, current, color);
                        color = next;
                    } else if (tag.startsWith("/") && color != DEFAULT_COLOR && closesColor(tag)) {
                        flush(parts, current, color);
                        color = DEFAULT_COLOR;
                    }
                    i = close + 1;
                    continue;
                }
            }
            current.append(c);
            i++;
        }
        flush(parts, current, color);
        return parts;
    }

    /** 全部的字（拿掉標籤）。 */
    public static String plain(final String text) {
        final StringBuilder out = new StringBuilder();
        for (final Part part : parse(text)) {
            out.append(part.text());
        }
        return out.toString();
    }

    /** 2026-11-02：拆好的字段接起來的字。 */
    public static String plainOf(final List<Part> parts) {
        final StringBuilder out = new StringBuilder();
        for (final Part part : parts) {
            out.append(part.text());
        }
        return out.toString();
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

    private static void flush(final List<Part> parts, final StringBuilder current, final int color) {
        if (current.isEmpty()) {
            return;
        }
        if (!parts.isEmpty() && parts.getLast().color() == color) {
            final Part last = parts.removeLast();
            parts.add(new Part(last.text() + current, color));
        } else {
            parts.add(new Part(current.toString(), color));
        }
        current.setLength(0);
    }
}
