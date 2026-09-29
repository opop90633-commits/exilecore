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

    public List<Modifier> modifiers(final Map<String, Integer> rolledVars, final String source) {
        final List<Modifier> list = new ArrayList<>();
        for (final AffixMod mod : mods) {
            list.add(mod.toModifier(rolledVars, source));
        }
        return list;
    }
}
