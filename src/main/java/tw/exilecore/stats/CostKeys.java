package tw.exilecore.stats;

import java.util.Set;

/**
 * 2026-10-27 照 PoE2 的「消耗效率」（Cost Efficiency）：效率不是「減少消耗」，而是把消耗除掉：
 * <pre>
 *   消耗 ＝（基礎消耗 ＋ 額外的固定消耗）×（1 ＋ Σ增加／減少消耗）× Π更多／更少消耗 ÷（1 ＋ Σ增加的消耗效率）
 * </pre>
 * 增加 50% 效率 → 消耗變成 1 ÷ 1.5 ＝ 2/3；增加 100% → 一半。效率也可以是負的（減少 30% 效率 → 消耗 ÷ 0.7）。
 * <ul>
 *   <li>{@link #COST_EFFICIENCY}「消耗效率」：所有消耗（魔力、生命、怒氣）都吃。</li>
 *   <li>{@link #MANA_COST_EFFICIENCY}「魔力消耗效率」：只有魔力消耗吃。</li>
 *   <li>{@link #LIFE_COST_EFFICIENCY}「生命消耗效率」：只有生命消耗吃。</li>
 * </ul>
 * 三個都是 INCREASED，照技能的標籤查（「攻擊的消耗效率」就是標籤 attack）。「增加／減少魔力消耗」還是 {@link StatKeys#SKILL_COST}。
 * 這次之前「增加 x% 消耗效率」一律近似成 skill.cost 減少 x%（效率 50% 只省 50% 而不是 1/3；兩條加起來到 100% 就不用魔力）。
 */
public final class CostKeys {

    /** INCREASED：消耗效率（魔力、生命、怒氣都算）。 */
    public static final String COST_EFFICIENCY = "skill.cost_efficiency";
    /** INCREASED：魔力消耗效率（只算魔力）。 */
    public static final String MANA_COST_EFFICIENCY = "skill.mana_cost_efficiency";
    /** INCREASED：生命消耗效率（只算生命）。 */
    public static final String LIFE_COST_EFFICIENCY = "skill.life_cost_efficiency";

    /** 效率最低算到減少 90%（消耗最多 10 倍），不會除以 0 或變負的。 */
    private static final double MIN_DIVISOR = 0.1;

    private CostKeys() {
    }

    /** 1 ＋ 效率（夾在 0.1 以上）：消耗要除的數。 */
    public static double divisor(final double efficiency) {
        return Math.max(MIN_DIVISOR, 1 + efficiency);
    }

    /** 魔力消耗的效率（消耗效率 ＋ 魔力消耗效率）。 */
    public static double manaEfficiency(final StatSheet sheet, final Set<String> tags) {
        return sheet == null ? 0 : sheet.increased(COST_EFFICIENCY, tags) + sheet.increased(MANA_COST_EFFICIENCY, tags);
    }

    /** 生命消耗的效率（消耗效率 ＋ 生命消耗效率）。 */
    public static double lifeEfficiency(final StatSheet sheet, final Set<String> tags) {
        return sheet == null ? 0 : sheet.increased(COST_EFFICIENCY, tags) + sheet.increased(LIFE_COST_EFFICIENCY, tags);
    }

    /** 其他消耗（怒氣…）的效率：只吃消耗效率。 */
    public static double otherEfficiency(final StatSheet sheet, final Set<String> tags) {
        return sheet == null ? 0 : sheet.increased(COST_EFFICIENCY, tags);
    }

    /**
     * 魔力消耗：(基礎 ＋ 固定) × 消耗倍率（增加 × 更多）÷ (1 ＋ 效率)，四捨五入。
     *
     * @param base       基礎消耗加上固定多的（祕法之心的「多最大魔力的 x%」）
     * @param multiplier skill.cost 的（1 ＋ 增加）× 更多
     * @param efficiency 魔力消耗的效率（{@link #manaEfficiency}）
     */
    public static int manaCost(final double base, final double multiplier, final double efficiency) {
        return (int) Math.round(base * Math.max(0, multiplier) / divisor(efficiency));
    }

    /**
     * 已經算好的魔力消耗（除過魔力效率）改成生命消耗時要乘的數：先乘回魔力的效率（生命消耗不吃魔力消耗效率），
     * 再除生命的效率。血魔法、「x% 魔力消耗改成生命消耗」、血祭術都用這個。
     */
    public static double manaToLife(final StatSheet sheet, final Set<String> tags) {
        return divisor(manaEfficiency(sheet, tags)) / divisor(lifeEfficiency(sheet, tags));
    }
}
