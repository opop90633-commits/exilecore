package tw.exilecore.item;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 詞綴（或固定詞綴）裡的一條修飾器範本：
 * <pre>
 * - { stat: life.max, type: FLAT, value: "{value}" }
 * - { stat: damage, type: INCREASED, value: "{value}", percent: true, tags: [fire] }
 * - { stat: damage.added.min, type: FLAT, value: "{min}", tags: [attack, fire] }
 * - { stat: skill.cooldown, type: INCREASED, value: "-{value}" }   # 擲出來的數字取負（「冷卻時間減少 x%」）
 * - { stat: life.regen_percent, type: FLAT, value: "{value/10}" }  # 擲出來的整數除以 10（「每秒回復 0.6% 最大生命」）
 * </pre>
 * {@code value} 可以是變數名（大括號，前面可以加負號，名字後面可以接 {@code /10}、{@code /100} 表示小數）或固定數字；
 * {@code percent: true} 表示設定檔寫的是百分比點數，進引擎時除以 100。
 * <p>
 * 擲骰一律是整數（階級範圍寫整數）；要小數的詞綴把範圍寫成放大 10 倍的整數，文字與修飾器都用 {@code {名字/10}}。
 * <p>
 * stat 以 {@code local.} 開頭的是「本地」詞綴：只改這件武器自己的基礎數值（見 {@link LocalMods}），不進角色的屬性表。
 *
 * @param stat     屬性鍵
 * @param type     型態
 * @param variable 變數名（null = 用 constant）
 * @param constant 固定值
 * @param percent  是否要除以 100
 * @param tags     修飾器標籤
 * @param negate   變數值取負（{@code "-{value}"}）
 * @param divisor  變數值先除以這個數（1、10、100）；固定值不受影響
 */
public record AffixMod(String stat, ModType type, String variable, double constant, boolean percent, Set<String> tags, boolean negate,
                       int divisor) {

    /** 變數的寫法：{名字} 或 {名字/10}（小數用）。 */
    static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z0-9_]+)(?:/(\\d+))?}");

    /** 舊的建構方式（不取負）。 */
    public AffixMod(final String stat, final ModType type, final String variable, final double constant, final boolean percent,
                    final Set<String> tags) {
        this(stat, type, variable, constant, percent, tags, false, 1);
    }

    public AffixMod(final String stat, final ModType type, final String variable, final double constant, final boolean percent,
                    final Set<String> tags, final boolean negate) {
        this(stat, type, variable, constant, percent, tags, negate, 1);
    }

    public AffixMod {
        tags = Set.copyOf(tags);
        if (divisor != 1 && divisor != 10 && divisor != 100) {
            throw new IllegalArgumentException("詞綴的小數除數只能是 1、10、100，卻是 " + divisor);
        }
    }

    public static AffixMod parse(final YamlNode node) {
        final String stat = node.requireString("stat");
        final ModType type = ModType.valueOf(node.string("type", "FLAT").trim().toUpperCase(Locale.ROOT));
        final String valueText = node.string("value", "{value}").trim();
        String variable = null;
        double constant = 0;
        boolean negate = false;
        int divisor = 1;
        final boolean negated = valueText.startsWith("-{");
        final String body = negated ? valueText.substring(1) : valueText;
        final Matcher matcher = VARIABLE.matcher(body);
        if (matcher.matches()) {
            variable = matcher.group(1);
            negate = negated;
            divisor = divisorOf(matcher.group(2), node.path());
        } else {
            try {
                constant = Double.parseDouble(valueText);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(node.path() + ".value：要寫成 {變數} 或數字，卻是「" + valueText + "」");
            }
        }
        final boolean percent = node.bool("percent", type != ModType.FLAT);
        return new AffixMod(stat, type, variable, constant, percent, new HashSet<>(node.strings("tags")), negate, divisor);
    }

    /** {名字/N} 的 N；沒寫是 1，只收 10、100。 */
    static int divisorOf(final String text, final String where) {
        if (text == null) {
            return 1;
        }
        final int divisor = Integer.parseInt(text);
        if (divisor != 10 && divisor != 100) {
            throw new IllegalArgumentException(where + "：小數除數只能寫 /10 或 /100，卻是 /" + text);
        }
        return divisor;
    }

    /** 只改武器自己的基礎數值、不進角色屬性表的詞綴。 */
    public boolean isLocal() {
        return LocalMods.isLocal(stat);
    }

    /** 用擲出的變數值做出真正的修飾器。 */
    public Modifier toModifier(final Map<String, Integer> vars, final String source) {
        double value = constant;
        if (variable != null) {
            final Integer rolled = vars.get(variable);
            if (rolled == null) {
                throw new IllegalStateException(source + "：缺少變數 " + variable);
            }
            value = (negate ? -rolled : rolled) / (double) divisor;
        }
        if (percent) {
            value /= 100.0;
        }
        return new Modifier(stat, type, value, tags, source);
    }
}
