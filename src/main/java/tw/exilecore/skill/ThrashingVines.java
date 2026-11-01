package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 0.5 Thrashing Vines（抽荊，PoB2 ThrashingVinesPlayer、poe2db）：在準心指的地方造一塊 4 公尺的藤蔓生長區
 * （active_skill_base_tertiary_area_of_effect_radius 40；上限 1 塊，base_number_of_awakened_forests_allowed 1），持續 8 秒；
 * 每隔「施法時間的 50%」（awakened_forest_spawn_vines_per_X%_cast_time 50）在區域裡隨機的地方長出一條 2.5 公尺的巨藤
 * （active_skill_base_area_of_effect_radius 25）砸下去，打到藤上的敵人。附近有敵人時藤朝牠長（同一個敵人 0.5 秒內最多被瞄一次，
 * thrashing_vines_retarget_cooldown_ms 500）。繁茂（在積雷雲裡）時藤更大 50%、更多 50% 傷害（{@link Overgrowth}）。
 * 這次之前是準心處 0.4 秒後打一下 2.5 格的圓。
 * <p>
 * 藤的寬度原作沒寫成玩家看得到的數字；PoB2 的 secondary radius 10（1 公尺）當作藤的寬度（兩邊各 0.5 公尺）。純資料與純函式；
 * 長藤在 {@link SkillZones}。
 */
public final class ThrashingVines {

    /** 抽荊的技能代號。 */
    public static final String ID = "lashing_thorns";
    /** 藤長幾公尺（吃範圍加成）。 */
    public static final double VINE_LENGTH_M = 2.5;
    /** 藤的半寬（公尺；吃範圍加成）。 */
    public static final double VINE_HALF_WIDTH_M = 0.5;
    /** 兩條藤之間是施法時間的幾倍。 */
    public static final double GROWTH_FRACTION = 0.5;
    /** 同一個敵人幾 tick 內只被瞄一次。 */
    public static final int RETARGET_TICKS = 10;

    private ThrashingVines() {
    }

    /** 兩條藤之間隔幾 tick：這個技能一次施法要 castTicks tick（含施法速度），取一半（至少 1）。 */
    static int growthTicks(final double castTicks) {
        return Math.max(1, (int) Math.round(Math.max(0, castTicks) * GROWTH_FRACTION));
    }

    /** 點 (px, pz) 到線段 (ax, az)–(bx, bz) 的水平距離。 */
    static double distanceToSegment(final double px, final double pz, final double ax, final double az, final double bx, final double bz) {
        final double dx = bx - ax;
        final double dz = bz - az;
        final double lengthSq = dx * dx + dz * dz;
        double t = lengthSq <= 1e-12 ? 0 : ((px - ax) * dx + (pz - az) * dz) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        final double cx = ax + t * dx - px;
        final double cz = az + t * dz - pz;
        return Math.sqrt(cx * cx + cz * cz);
    }

    /**
     * 朝目標長的藤的起點（相對目標的 x、z）：從目標往 angle 方向退半條藤（藤從起點穿過目標往前長）。
     */
    static double[] rootBehind(final double length, final double angle) {
        return new double[] {Math.cos(angle) * length / 2, Math.sin(angle) * length / 2};
    }
}
