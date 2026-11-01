package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 0.5 Thunderstorm（積雷雲，PoB2 ThunderstormPlayer）：雷雨範圍（2.3 公尺）裡每 0.35±0.15 秒
 * （lightning_storm_hit_frequency_ms 350、…_variance_ms 150）在隨機一點劈下一道 0.8 公尺（active_skill_base_secondary_area_of_effect_radius 8）
 * 的雷，打中的每一個敵人全額傷害；同一個敵人 0.5 秒內只會被劈一次（lightning_storm_hit_prevention_duration_ms 500）。
 * 範圍內的敵人一直濕透（{@link SupportExtras#drench}）。這次之前整片每 0.5 秒打範圍內全部的敵人 55%（平衡等效）。
 * 純資料與純函式；劈雷在 {@link SkillZones}。
 */
public final class StormStrikes {

    /** 積雷雲的技能代號。 */
    public static final String ID = "gathered_thundercloud";
    /** 每一道雷的半徑（公尺；吃範圍加成）。 */
    public static final double STRIKE_RADIUS_M = 0.8;
    /** 兩道雷之間平均幾毫秒、上下差幾毫秒。 */
    public static final double FREQUENCY_MS = 350;
    public static final double VARIANCE_MS = 150;
    /** 同一個敵人幾 tick 內只會被劈一次（500 毫秒）。 */
    public static final int HIT_GAP_TICKS = 10;

    /**
     * FLAT（逐級，0 = 不限）：濕透套不到等級高於這個的敵人（PoB2 thunderstorm_wet_debuff_cannot_apply_to_targets_above_level：
     * 1 級 20 → 10 級 52 → 17 級 78，18 級起 0）。寫法跟詛咒的 curse.max_target_level 一樣。
     */
    public static final String WET_MAX_LEVEL = "drenched.max_target_level";

    private StormStrikes() {
    }

    /** 濕透的等級上限：cap ≤ 0 不限；敵人等級高於 cap 就套不上。 */
    static boolean aboveWetLevel(final double cap, final int targetLevel) {
        return cap > 0 && targetLevel > cap;
    }

    /** 下一道雷隔幾 tick（roll 是 0～1 的亂數：0.2～0.5 秒 → 4～10 tick）。 */
    static int nextGapTicks(final double roll) {
        final double ms = FREQUENCY_MS + (2 * Math.max(0, Math.min(1, roll)) - 1) * VARIANCE_MS;
        return Math.max(1, (int) Math.round(ms / 50.0));
    }

    /** 半徑 radius 的圓裡均勻的一點（相對圓心的 x、z；u、v 是 0～1 的亂數）。 */
    static double[] point(final double radius, final double u, final double v) {
        final double r = Math.max(0, radius) * Math.sqrt(Math.max(0, Math.min(1, u)));
        final double a = 2 * Math.PI * v;
        return new double[] {r * Math.cos(a), r * Math.sin(a)};
    }

    /** 這個敵人上一次在 last tick 被劈（沒有是 null），現在 now 能不能再劈。 */
    static boolean canHit(final Long last, final long now) {
        return last == null || now - last >= HIT_GAP_TICKS;
    }
}
