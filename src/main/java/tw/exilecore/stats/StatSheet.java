package tw.exilecore.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 屬性表：一組基礎值加上一堆修飾器，用 PoE 公式結算。
 * <pre>結果 = (基礎 + Σ FLAT) × (1 + Σ INCREASED) × Π (1 + MORE)</pre>
 * 查詢帶「情境標籤」：只有標籤全部包含在情境裡的修飾器才會算進去，
 * 所以「增加 20% 火焰傷害」不會影響物理傷害的查詢。
 * <p>
 * 結果有快取；任何 {@link #setBase} 或 {@link #add} 都會清掉快取。
 * 這個類別不依賴 Bukkit，可以直接寫單元測試。
 */
public final class StatSheet {

    private final Map<String, Double> bases = new HashMap<>();
    private final Map<String, List<Modifier>> modifiers = new HashMap<>();
    private final Map<String, Double> cache = new HashMap<>();

    public StatSheet setBase(final String stat, final double value) {
        bases.put(stat, value);
        cache.clear();
        return this;
    }

    public double base(final String stat) {
        return bases.getOrDefault(stat, 0.0);
    }

    public StatSheet add(final Modifier modifier) {
        modifiers.computeIfAbsent(modifier.stat(), key -> new ArrayList<>()).add(modifier);
        cache.clear();
        return this;
    }

    public StatSheet addAll(final Collection<Modifier> list) {
        for (final Modifier modifier : list) {
            add(modifier);
        }
        return this;
    }

    /** 清掉所有修飾器（基礎值保留）。重算裝備時用。 */
    public StatSheet clearModifiers() {
        modifiers.clear();
        cache.clear();
        return this;
    }

    /** 沒有情境標籤的查詢：只有無標籤的修飾器會生效。 */
    public double get(final String stat) {
        return get(stat, Set.of());
    }

    /** 帶情境標籤的查詢。 */
    public double get(final String stat, final Set<String> contextTags) {
        final String cacheKey = contextTags.isEmpty() ? stat : stat + '|' + String.join(",", contextTags.stream().sorted().toList());
        final Double cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        final double result = compute(stat, contextTags);
        cache.put(cacheKey, result);
        return result;
    }

    /** 只取 FLAT 的總和（含基礎值），例如附加傷害。 */
    public double flat(final String stat, final Set<String> contextTags) {
        double sum = base(stat);
        for (final Modifier modifier : modifiers.getOrDefault(stat, List.of())) {
            if (modifier.type() == ModType.FLAT && modifier.appliesTo(contextTags)) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    /** 只取增加的總和（小數），例如把「增加 x% 攻擊速度」套到武器的基礎攻速上。 */
    public double increased(final String stat, final Set<String> contextTags) {
        double sum = 0;
        for (final Modifier modifier : modifiers.getOrDefault(stat, List.of())) {
            if (modifier.type() == ModType.INCREASED && modifier.appliesTo(contextTags)) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    /** 只取更多的連乘（1 = 沒有）。 */
    public double more(final String stat, final Set<String> contextTags) {
        double product = 1;
        for (final Modifier modifier : modifiers.getOrDefault(stat, List.of())) {
            if (modifier.type() == ModType.MORE && modifier.appliesTo(contextTags)) {
                product *= 1 + modifier.value();
            }
        }
        return product;
    }

    /** 把一個外部算好的基礎值（例如武器傷害）用這張表上某個屬性的增加／更多結算。 */
    public double apply(final String stat, final double baseValue, final Set<String> contextTags) {
        return baseValue * (1 + increased(stat, contextTags)) * more(stat, contextTags);
    }

    private double compute(final String stat, final Set<String> contextTags) {
        final double flat = flat(stat, contextTags);
        final double inc = increased(stat, contextTags);
        final double more = more(stat, contextTags);
        return flat * Math.max(0, 1 + inc) * more;
    }

    /** 除錯用：某個屬性的所有修飾器。 */
    public List<Modifier> modifiersOf(final String stat) {
        return List.copyOf(modifiers.getOrDefault(stat, List.of()));
    }

    /** 除錯用：所有修飾器。 */
    public List<Modifier> allModifiers() {
        final List<Modifier> all = new ArrayList<>();
        for (final List<Modifier> list : modifiers.values()) {
            all.addAll(list);
        }
        return all;
    }

    public Map<String, Double> bases() {
        return Map.copyOf(bases);
    }
}
