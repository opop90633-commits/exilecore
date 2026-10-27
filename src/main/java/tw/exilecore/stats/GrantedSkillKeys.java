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
}
