package tw.exilecore.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天裡的物品連結（PoE 在聊天框貼物品的做法）：訊息裡的 {@code [i]}、{@code [item]}、{@code [物品]} 換成手上的物品，
 * {@code [i1]}～{@code [i9]} 換成快捷列第 1～9 格，滑過去看完整的物品說明。不分大小寫；一則訊息最多 {@link #MAX_LINKS} 個，
 * 多的照原樣留著。純邏輯，不依賴 Bukkit。
 */
public final class ItemLinks {

    /** 一則訊息最多幾個物品連結（物品說明很長，太多會讓訊息太大）。 */
    public static final int MAX_LINKS = 3;
    /** 手上（主手）。 */
    public static final int MAIN_HAND = -1;

    private static final Pattern TOKEN = Pattern.compile("\\[(?:i|item|物品)([1-9])?]", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ItemLinks() {
    }

    /** 訊息的一段。 */
    public sealed interface Part permits Text, Link {
    }

    /** 一般文字。 */
    public record Text(String text) implements Part {
    }

    /**
     * 物品連結。
     *
     * @param token 原本打的字（例如 {@code [i]}；那一格沒有東西時照原樣顯示）
     * @param slot  {@link #MAIN_HAND} 或快捷列的格子（0～8）
     */
    public record Link(String token, int slot) implements Part {
    }

    /** 切成文字與物品連結（沒有連結時只有一段文字）。 */
    public static List<Part> parse(final String message) {
        return parse(message, MAX_LINKS);
    }

    static List<Part> parse(final String message, final int max) {
        final String text = message == null ? "" : message;
        final List<Part> parts = new ArrayList<>();
        final Matcher matcher = TOKEN.matcher(text);
        int last = 0;
        int links = 0;
        while (links < max && matcher.find()) {
            if (matcher.start() > last) {
                parts.add(new Text(text.substring(last, matcher.start())));
            }
            final String digit = matcher.group(1);
            parts.add(new Link(matcher.group(), digit == null ? MAIN_HAND : digit.charAt(0) - '1'));
            last = matcher.end();
            links++;
        }
        if (last < text.length() || parts.isEmpty()) {
            parts.add(new Text(text.substring(last)));
        }
        return parts;
    }

    /** 有沒有物品連結。 */
    public static boolean hasLinks(final List<Part> parts) {
        for (final Part part : parts) {
            if (part instanceof Link) {
                return true;
            }
        }
        return false;
    }

    /** 用到的格子（照出現的順序、不重複）。 */
    public static Set<Integer> slots(final List<Part> parts) {
        final Set<Integer> slots = new LinkedHashSet<>();
        for (final Part part : parts) {
            if (part instanceof Link link) {
                slots.add(link.slot());
            }
        }
        return slots;
    }
}
