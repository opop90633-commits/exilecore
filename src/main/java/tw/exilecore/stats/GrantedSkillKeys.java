package tw.exilecore.stats;

/**
 * 2026-10-28 照 PoE2 0.5 重做法杖、長杖、權杖賦予的技能（寒心、屍焰爆、血鐮、銳星虹吸、纏蝕箭）時新加的屬性代號
 * （不放進 StatKeys，方便合併）。
 */
public final class GrantedSkillKeys {

    private GrantedSkillKeys() {
    }

    /**
     * FLAT：撲殺時獲得固定的生命（不是最大生命的 %）。2026-10-28 照 PoE2 血鐮 active_skill_life_gained_on_cull（1 級 5、20 級 188、40 級 829）；
     * 這次之前引擎只有 {@link StatKeys#LIFE_ON_CULL}（最大生命的 %）。
     */
    public static final String LIFE_ON_CULL_FLAT = "life.on_cull_flat";

    /**
     * FLAT：「像造成 x 到 y 點傷害一樣」施加異常（PoE2 的 as though dealing）：基礎傷害的最小、最大（逐級，寫在 level_modifiers）。
     * 照這個技能的加成算成傷害（增加、更多都吃，不擲暴擊），只拿來算異常，不打人。寒心的冰緩、屍焰爆核心的點燃用。
     */
    public static final String AS_THOUGH_MIN = "as_though.min";
    public static final String AS_THOUGH_MAX = "as_though.max";

    /** FLAG：常駐光環：你存在範圍（臨在）內的敵人一直被冰緩，冰緩照 {@link #AS_THOUGH_MIN}～{@link #AS_THOUGH_MAX} 的冰冷傷害算（寒心）。 */
    public static final String FLAG_PRESENCE_CHILL = "flag.presence_chill";

    /** FLAG：消耗屍體不直接爆炸，改成做出一顆會追敵人、點燃身邊敵人、時間到才爆炸的核心（屍焰爆）。 */
    public static final String FLAG_VOLATILE_CORE = "flag.volatile_core";

    /** FLAG：撲殺給的戰星不照怪物稀有度多給（銳星虹吸：每次撲殺就是一顆，多的只看「多一顆」的機率）。 */
    public static final String FLAG_NO_RARITY_CHARGES = "flag.charge_no_rarity_bonus";

    /** 撲殺時回復多少生命：最大生命 × percent% ＋ 固定 flat。純函式（測試用）。 */
    public static double cullLife(final double maxLife, final double percent, final double flat) {
        return Math.max(0, maxLife * percent / 100.0) + Math.max(0, flat);
    }

    /**
     * FLAG：投射物數量的修飾器改成加在分裂目標數上（不多射）。2026-10-28 照 PoE2 纏蝕箭 projectile_count_modifiers_apply_to_split_targets_instead；
     * 這次之前多一發投射物就是多射一發。
     */
    public static final String FLAG_PROJECTILES_TO_SPLIT = "flag.projectiles_to_split";

    /**
     * FLAG：打中被詛咒的敵人時一定連鎖（不算連鎖次數，可以從任意多個被詛咒的敵人連鎖）。2026-10-28 照 PoE2 纏蝕箭
     * 「can chain from any number of Cursed targets」；這次之前不會連鎖。
     */
    public static final String FLAG_CHAIN_CURSED = "flag.chain_from_cursed";

    /** INCREASED：投射物連鎖找下一個目標的距離。2026-10-28 照 PoE2 纏蝕箭「50% increased chaining range」；這次之前固定 10 格。 */
    public static final String CHAIN_RANGE = "chain.range";

    /**
     * FLAT：這個技能給的暴擊弱點持續幾秒（有寫就取代預設的 4 秒；品質再加上去）。2026-10-28 照 PoE2 血鐮「Critical Weakness duration is 6 seconds」、
     * 品質 +(0—2) 秒；這次之前一律 4 秒。
     */
    public static final String CRIT_WEAKNESS_SECONDS = "crit_weakness.seconds";

    /** 分裂目標數最多幾個（效能上限）。 */
    public static final int MAX_SPLIT = 10;

    /**
     * 打中時分裂出幾發：分裂屬性（品質的小數照原作捨去）＋（有 {@link #FLAG_PROJECTILES_TO_SPLIT} 時）多的投射物數量，0～{@link #MAX_SPLIT}。純函式。
     */
    public static int splitTargets(final double split, final double extraProjectiles, final boolean projectilesToSplit) {
        final double total = split + (projectilesToSplit ? extraProjectiles : 0);
        return (int) Math.max(0, Math.min(MAX_SPLIT, Math.floor(total + 1e-9)));
    }

    /** 射出幾發：有 {@link #FLAG_PROJECTILES_TO_SPLIT} 時投射物數量的修飾器不算（只射基礎數量）。純函式。 */
    public static int projectileCount(final int base, final double extra, final boolean projectilesToSplit, final int max) {
        final long count = projectilesToSplit ? base : Math.round(base + extra);
        return (int) Math.max(1, Math.min(max, count));
    }

    /** 連鎖距離：基礎 × (1 + 增加)。純函式。 */
    public static double chainRange(final double base, final double increased) {
        return base * Math.max(0.1, 1 + increased);
    }

    /** 這一下打中能不能連鎖：還有連鎖次數，或技能能從被詛咒的敵人連鎖而目標被詛咒。純函式。 */
    public static boolean canChain(final int chainsLeft, final boolean chainCursed, final boolean targetCursed) {
        return chainsLeft > 0 || chainCursed && targetCursed;
    }

    /** 連鎖時要不要扣一次連鎖次數：從被詛咒的敵人連鎖的不扣。純函式。 */
    public static boolean consumesChain(final boolean chainCursed, final boolean targetCursed) {
        return !(chainCursed && targetCursed);
    }

    /** 暴擊弱點持續幾 tick：有寫秒數（&gt; 0）就照它，不然用預設。純函式。 */
    public static long critWeaknessTicks(final double seconds, final long defaultTicks) {
        return seconds > 0 ? Math.max(1, Math.round(seconds * 20)) : defaultTicks;
    }
}
