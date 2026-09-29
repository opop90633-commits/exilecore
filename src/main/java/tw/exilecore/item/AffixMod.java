package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 詞綴（或固定詞綴）裡的一條修飾器範本：
 * <pre>
 * - { stat: life.max, type: FLAT, value: "{value}" }
 * - { stat: damage, type: INCREASED, value: "{value}", percent: true, tags: [fire] }
 * - { stat: damage.added.min, type: FLAT, value: "{min}", tags: [attack, fire] }
 * </pre>
 * {@code value} 可以是變數名（大括號）或固定數字；{@code percent: true} 表示設定檔寫的是百分比點數，
 * 進引擎時除以 100。
 *
 * @param stat     屬性鍵
 * @param type     型態
 * @param variable 變數名（null = 用 constant）
 * @param constant 固定值
 * @param percent  是否要除以 100
 * @param tags     修飾器標籤
 */
public record AffixMod(String stat, ModType type, String variable, double constant, boolean percent, Set<String> tags) {

    public AffixMod {
        tags = Set.copyOf(tags);
    }

    public static AffixMod parse(final YamlNode node) {
        final String stat = node.requireString("stat");
        final ModType type = ModType.valueOf(node.string("type", "FLAT").trim().toUpperCase(Locale.ROOT));
        final String valueText = node.string("value", "{value}").trim();
        String variable = null;
        double constant = 0;
        if (valueText.startsWith("{") && valueText.endsWith("}")) {
            variable = valueText.substring(1, valueText.length() - 1).trim();
        } else {
            try {
                constant = Double.parseDouble(valueText);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(node.path() + ".value：要寫成 {變數} 或數字，卻是「" + valueText + "」");
            }
        }
        final boolean percent = node.bool("percent", type != ModType.FLAT);
        return new AffixMod(stat, type, variable, constant, percent, new HashSet<>(node.strings("tags")));
    }

    /** 用擲出的變數值做出真正的修飾器。 */
    public Modifier toModifier(final Map<String, Integer> vars, final String source) {
        double value = constant;
        if (variable != null) {
            final Integer rolled = vars.get(variable);
            if (rolled == null) {
                throw new IllegalStateException(source + "：缺少變數 " + variable);
            }
            value = rolled;
        }
        if (percent) {
            value /= 100.0;
        }
        return new Modifier(stat, type, value, tags, source);
    }
}
