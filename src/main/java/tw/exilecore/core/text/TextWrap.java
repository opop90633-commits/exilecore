package tw.exilecore.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-26：物品說明（lore）的長行自動換行（站長：「有些敘述有點長」——寶石說明一整行橫跨整個螢幕）。
 * <p>
 * 規則：
 * <ul>
 *   <li>寬度照 Minecraft 預設字型粗估：中文、全形字算 2，半形算 1；一行最多 {@link #MAX_WIDTH}（42 個中文字寬）。</li>
 *   <li>盡量在標點（，。；、：！？）後面斷，但斷點太前面（不到 {@link #PREFER_RATIO} 行寬）就改在最後一個能斷的地方斷。</li>
 *   <li>能斷的地方：空白、中文字的前後；英文、數字連在一起的（Lv.5、+40%、3-5）不拆開。
 *       避頭尾：「，。）」不放下一行開頭、「（「」不放這一行結尾。</li>
 *   <li>在元件上切（不是在 MiniMessage 字串上切），每一段保留原本的顏色、粗體、斜體——標籤跨行等於下一行自動重開。</li>
 *   <li>接續的那幾行在最外層掛 {@link #CONTINUATION} 的 insertion 標記，網頁（{@code web.Profiles}）照這個把它們接回同一行
 *       （網頁有自己的換行）。</li>
 * </ul>
 * 這次之前說明一行寫多長就多長。
 */
public final class TextWrap {

    /** 一行最多的顯示寬度（中文字算 2）：42 個中文字。 */
    public static final int MAX_WIDTH = 84;

    /** 在標點斷的話，這一行至少要有這麼寬（行寬的比例），不然斷得太短。 */
    static final double PREFER_RATIO = 0.55;

    /** 換行後接續行的標記（元件的 insertion，說明框裡用不到這個欄位）。 */
    public static final String CONTINUATION = "exilecore:wrap";
    /** 接續行、而且斷行的地方原本有空白（接回去要補一個空白）。 */
    public static final String CONTINUATION_SPACE = "exilecore:wrap_space";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 後面斷比較好的標點。 */
    private static final String PREFERRED = "，。；、：！？";
    /** 不能放在行首的字。 */
    private static final String NO_START = "，。；、：！？）」』】〉》…～・·,.;:!?)]}%";
    /** 不能放在行尾的字。 */
    private static final String NO_END = "（「『【〈《([{";

    private TextWrap() {
    }

    /** 一個字的顯示寬度：中文、全形 2，其他 1。 */
    public static int width(final int cp) {
        if (cp >= 0x1100 && cp <= 0x115F
                || cp >= 0x2E80 && cp <= 0x303E
                || cp >= 0x3041 && cp <= 0x33FF
                || cp >= 0x3400 && cp <= 0x4DBF
                || cp >= 0x4E00 && cp <= 0x9FFF
                || cp >= 0xA000 && cp <= 0xA4CF
                || cp >= 0xAC00 && cp <= 0xD7A3
                || cp >= 0xF900 && cp <= 0xFAFF
                || cp >= 0xFE30 && cp <= 0xFE4F
                || cp >= 0xFF00 && cp <= 0xFF60
                || cp >= 0xFFE0 && cp <= 0xFFE6
                || cp >= 0x20000 && cp <= 0x3FFFD) {
            return 2;
        }
        return 1;
    }

    /** 一段純文字的顯示寬度。 */
    public static int width(final String text) {
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            total += width(cp);
            i += Character.charCount(cp);
        }
        return total;
    }

    /** 一行元件（含子元件）的顯示寬度。 */
    public static int width(final Component component) {
        return width(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component));
    }

    /** 這一行是不是自動換行切出來的接續行。 */
    public static boolean continuation(final Component line) {
        return line != null && (CONTINUATION.equals(line.insertion()) || CONTINUATION_SPACE.equals(line.insertion()));
    }

    /** 接續行接回上一行時要不要補空白。 */
    public static boolean spaceBefore(final Component line) {
        return line != null && CONTINUATION_SPACE.equals(line.insertion());
    }

    /** MiniMessage 的多行說明 → 元件（關掉原版說明預設的斜體），長行自動換行。 */
    public static List<Component> lines(final List<String> miniMessage) {
        final List<Component> out = new ArrayList<>(miniMessage.size() + 4);
        for (final String line : miniMessage) {
            out.addAll(wrap(MINI.deserialize(line).decoration(TextDecoration.ITALIC, false), MAX_WIDTH));
        }
        return out;
    }

    /** 已經是元件的說明：長行自動換行（短的原樣保留，同一個物件）。 */
    public static List<Component> lore(final List<Component> lore) {
        if (lore == null) {
            return null;
        }
        boolean any = false;
        for (final Component line : lore) {
            if (line != null && width(line) > MAX_WIDTH) {
                any = true;
                break;
            }
        }
        if (!any) {
            return lore;
        }
        final List<Component> out = new ArrayList<>(lore.size() + 4);
        for (final Component line : lore) {
            out.addAll(line == null ? java.util.Collections.singletonList(null) : wrap(line, MAX_WIDTH));
        }
        return out;
    }

    private record Segment(String text, Style style) {
    }

    /** 一行元件照寬度切成幾行；不用切（或切不了：有翻譯鍵之類的元件）回傳只有原本那一行。 */
    public static List<Component> wrap(final Component line, final int maxWidth) {
        final List<Segment> segments = new ArrayList<>();
        if (!flatten(line, Style.empty(), segments)) {
            return List.of(line);
        }
        final StringBuilder plain = new StringBuilder();
        for (final Segment segment : segments) {
            plain.append(segment.text());
        }
        final String text = plain.toString();
        if (width(text) <= maxWidth) {
            return List.of(line);
        }
        final List<int[]> ranges = breakRanges(text, maxWidth);
        final List<Component> out = new ArrayList<>(ranges.size());
        for (int r = 0; r < ranges.size(); r++) {
            final int from = ranges.get(r)[0];
            final int to = ranges.get(r)[1];
            final net.kyori.adventure.text.TextComponent.Builder builder = Component.text();
            if (r > 0) {
                builder.insertion(text.charAt(from - 1) == ' ' ? CONTINUATION_SPACE : CONTINUATION);
            }
            int offset = 0;
            for (final Segment segment : segments) {
                final int start = offset;
                final int end = offset + segment.text().length();
                offset = end;
                final int a = Math.max(start, from);
                final int b = Math.min(end, to);
                if (a < b) {
                    builder.append(Component.text(segment.text().substring(a - start, b - start), segment.style()));
                }
            }
            out.add(builder.build());
        }
        return out;
    }

    /** 攤平成「文字＋完整樣式」；遇到不是純文字的元件回傳 false。 */
    private static boolean flatten(final Component component, final Style parent, final List<Segment> out) {
        if (!(component instanceof TextComponent textComponent)) {
            return false;
        }
        final Style style = parent.merge(component.style());
        if (!textComponent.content().isEmpty()) {
            out.add(new Segment(textComponent.content(), style.insertion(null)));
        }
        for (final Component child : component.children()) {
            if (!flatten(child, style, out)) {
                return false;
            }
        }
        return true;
    }

    /** 純文字照寬度斷行（斷點兩邊的空白去掉）。 */
    public static List<String> wrap(final String text, final int maxWidth) {
        final List<String> out = new ArrayList<>();
        for (final int[] range : breakRanges(text, maxWidth)) {
            out.add(text.substring(range[0], range[1]));
        }
        return out;
    }

    /** 每行一組 [開始, 結束)（字元位置，已經去掉斷點兩邊的空白）。 */
    static List<int[]> breakRanges(final String text, final int maxWidth) {
        final List<int[]> out = new ArrayList<>();
        final int length = text.length();
        int start = 0;
        while (start < length) {
            // 往後量到超過寬度為止，記下能斷的地方
            int used = 0;
            int i = start;
            int lastBreak = -1;
            int lastBreakWidth = 0;
            int preferred = -1;
            int preferredWidth = 0;
            boolean overflow = false;
            while (i < length) {
                final int cp = text.codePointAt(i);
                final int w = width(cp);
                if (i > start && canBreakBefore(text, i)) {
                    lastBreak = i;
                    lastBreakWidth = used;
                    final int prev = text.codePointBefore(i);
                    if (PREFERRED.indexOf(prev) >= 0) {
                        preferred = i;
                        preferredWidth = used;
                    }
                }
                if (used + w > maxWidth && cp != ' ') {
                    overflow = true;
                    break;
                }
                used += w;
                i += Character.charCount(cp);
            }
            int end;
            if (!overflow) {
                end = length;
            } else if (preferred > start && preferredWidth >= maxWidth * PREFER_RATIO) {
                end = preferred;
            } else if (lastBreak > start) {
                end = lastBreak;
            } else {
                end = Math.max(i, start + Character.charCount(text.codePointAt(start))); // 沒地方斷：硬切
            }
            int trimmedEnd = end;
            while (trimmedEnd > start && text.charAt(trimmedEnd - 1) == ' ') {
                trimmedEnd--;
            }
            if (trimmedEnd > start) {
                out.add(new int[]{start, trimmedEnd});
            }
            start = end;
            while (start < length && text.charAt(start) == ' ') {
                start++;
            }
        }
        return out;
    }

    /** 位置 i 前面能不能斷行。 */
    private static boolean canBreakBefore(final String text, final int i) {
        final int prev = text.codePointBefore(i);
        final int next = text.codePointAt(i);
        if (next == ' ') {
            return false; // 在空白後面斷（空白留在上一行尾、會被去掉）
        }
        if (NO_START.indexOf(next) >= 0 || NO_END.indexOf(prev) >= 0) {
            return false;
        }
        if (prev == ' ') {
            return true;
        }
        // 英數字連在一起的不拆；中文字前後都能斷
        return width(prev) == 2 || width(next) == 2;
    }
}
