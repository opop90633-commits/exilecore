package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 基底的固定詞綴（implicit）：
 * <pre>
 * implicit:
 *   text: "增加 {value}% 法術傷害"
 *   value: [10, 14]
 *   mods:
 *     - { stat: damage, type: INCREASED, value: "{value}", tags: [spell] }
 * </pre>
 * 除了 text／mods 以外的鍵都是變數範圍。
 */
public record Implicit(String text, Map<String, int[]> vars, List<AffixMod> mods) {

    public Implicit {
        vars = Map.copyOf(vars);
        mods = List.copyOf(mods);
    }

    public static Implicit parse(final YamlNode node) {
        final String text = node.requireString("text");
        final Map<String, int[]> vars = new LinkedHashMap<>();
        for (final String key : node.keys()) {
            if (key.equals("text") || key.equals("mods")) {
                continue;
            }
            vars.put(key, node.range(key));
        }
        final List<AffixMod> mods = new ArrayList<>();
        for (final YamlNode modNode : node.list("mods")) {
            mods.add(AffixMod.parse(modNode));
        }
        return new Implicit(text, vars, mods);
    }

    /**
     * 數值乘上 factor 的版本（2026-10：低物品等級的固定詞綴變小）：四捨五入、至少 1；上限 ≤ 3 的（次數、發數）不縮。
     */
    public Implicit scaled(final double factor) {
        if (factor >= 1 || vars.isEmpty()) {
            return this;
        }
        final Map<String, int[]> scaled = new LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : vars.entrySet()) {
            final int[] range = entry.getValue();
            if (Math.max(Math.abs(range[0]), Math.abs(range[1])) <= 3) {
                scaled.put(entry.getKey(), range);
                continue;
            }
            scaled.put(entry.getKey(), new int[] {scale(range[0], factor), scale(range[1], factor)});
        }
        return new Implicit(text, scaled, mods);
    }

    private static int scale(final int value, final double factor) {
        final long rounded = Math.round(value * factor);
        return value > 0 ? (int) Math.max(1, rounded) : value < 0 ? (int) Math.min(-1, rounded) : 0;
    }

    public Map<String, Integer> roll(final RandomGenerator random) {
        final Map<String, Integer> rolled = new LinkedHashMap<>();
        for (final Map.Entry<String, int[]> entry : vars.entrySet()) {
            final int lo = Math.min(entry.getValue()[0], entry.getValue()[1]);
            final int hi = Math.max(entry.getValue()[0], entry.getValue()[1]);
            rolled.put(entry.getKey(), lo == hi ? lo : lo + random.nextInt(hi - lo + 1));
        }
        return rolled;
    }

    public String render(final Map<String, Integer> rolledVars) {
        return Affix.substitute(text, rolledVars);
    }

    /** 物品描述用：數值後面接著範圍（MiniMessage，已跳脫）。 */
    public String renderWithRanges(final Map<String, Integer> rolledVars) {
        return Affix.substituteWithRanges(text, rolledVars, vars);
    }

    /** 只有範圍的文字（MiniMessage，已跳脫），例如「增加 (18–28)% 法術傷害」；上下限一樣的寫一個數字。 */
    public String rangeText() {
        final StringBuilder out = new StringBuilder();
        final java.util.regex.Matcher matcher = AffixMod.VARIABLE.matcher(text);
        int last = 0;
        while (matcher.find()) {
            out.append(Affix.escape(text.substring(last, matcher.start())));
            final int[] range = vars.get(matcher.group(1));
            if (range == null) {
                out.append(Affix.escape(matcher.group()));
            } else {
                final int divisor = AffixMod.divisorOf(matcher.group(2), text);
                final int lo = Math.min(range[0], range[1]);
                final int hi = Math.max(range[0], range[1]);
                out.append(lo == hi ? Affix.number(lo, divisor) : "(" + Affix.number(lo, divisor) + "–" + Affix.number(hi, divisor) + ")");
            }
            last = matcher.end();
        }
        out.append(Affix.escape(text.substring(last)));
        return out.toString();
    }

    public List<Modifier> modifiers(final Map<String, Integer> rolledVars, final String source) {
        final List<Modifier> list = new ArrayList<>();
        for (final AffixMod mod : mods) {
            list.add(mod.toModifier(rolledVars, source));
        }
        return list;
    }
}
