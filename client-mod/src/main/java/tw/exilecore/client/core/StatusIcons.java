package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-11-02 照站長（1.4）：自己身上的異常、詛咒（插件 vit 的 st，跟目標框一樣的字段：「燃 2.5s  寒 30%  毒×3 2.1s  咒·萎力 4.0s」）
 * 拆成生命球旁邊的小圖示：圖示上的字（label）、層數、下面的數字（剩幾秒自己倒數，或百分比）。純 Java（測試用）。
 */
public final class StatusIcons {

    /** 一個圖示。seconds ≥ 0 = 倒數（送出時剩幾秒；畫的時候減掉過了多久），不然 value 照寫。curse = 詛咒（紫框）。 */
    public record Icon(String label, int stacks, String value, double seconds, int color, boolean curse) {

        /** 過了 elapsed tick 之後下面要寫的字。 */
        public String text(final double elapsed) {
            if (seconds < 0) {
                return value;
            }
            final double left = Math.max(0, seconds - elapsed / 20.0);
            return left >= 10 ? Long.toString(Math.round(Math.ceil(left))) : String.format(java.util.Locale.ROOT, "%.1f", left);
        }

        /** 倒數完了（可以不畫了；伺服器下一包會拿掉）。 */
        public boolean expired(final double elapsed) {
            return seconds >= 0 && seconds - elapsed / 20.0 <= -0.5;
        }
    }

    private static final Pattern SECONDS = Pattern.compile("^(\\d+(?:\\.\\d+)?)s$");
    private static final Pattern STACKS = Pattern.compile("^(.+?)×(\\d+)$");

    private StatusIcons() {
    }

    public static List<Icon> of(final List<HudData.Part> parts) {
        final List<Icon> icons = new ArrayList<>();
        for (final List<HudData.Part> segment : HudData.segments(parts)) {
            final StringBuilder text = new StringBuilder();
            for (final HudData.Part part : segment) {
                text.append(part.text());
            }
            final String whole = text.toString().trim();
            if (whole.isEmpty()) {
                continue;
            }
            final int color = segment.getFirst().color();
            final int space = whole.indexOf(' ');
            String label = space < 0 ? whole : whole.substring(0, space);
            final String value = space < 0 ? "" : whole.substring(space + 1).trim();
            int stacks = 0;
            final Matcher stack = STACKS.matcher(label);
            if (stack.matches()) {
                label = stack.group(1);
                stacks = Integer.parseInt(stack.group(2));
            }
            final boolean curse = label.startsWith("咒·");
            final int dot = label.indexOf('·');
            if (dot >= 0 && dot + 1 < label.length()) {
                label = label.substring(dot + 1); // 「咒·萎力」→「萎力」、「曝·火」→「火」
            }
            if (label.length() > 2) {
                label = label.substring(0, 2);
            }
            final Matcher seconds = SECONDS.matcher(value);
            icons.add(new Icon(label, stacks, value, seconds.matches() ? Double.parseDouble(seconds.group(1)) : -1, color, curse));
        }
        return icons;
    }
}
