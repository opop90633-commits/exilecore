package tw.exilecore.stats;

/**
 * 2026-10-26：天賦樹（PoE2 0.5）新用到的屬性鍵（stat_map 的 new_key）。不放進 {@link StatKeys}，免得那個檔案一直變大；
 * 每個鍵寫消費的地方。
 */
public final class TreeKeys {

    private TreeKeys() {
    }

    /** FLAT：獲得等同最大能量護盾 x% 的額外暈眩門檻（PoE2）。屬性表算完時換成 FLAT stun.threshold（PlayerStateService）。 */
    public static final String STUN_THRESHOLD_FROM_ES = "stun.threshold_from_es";
    /** FLAT：獲得等同最大能量護盾 x% 的額外異常門檻。換成 FLAT ailment.threshold（AilmentService 把它加在基礎門檻上）。 */
    public static final String AILMENT_THRESHOLD_FROM_ES = "ailment.threshold_from_es";
    /** FLAT：獲得最大能量護盾 x% 的額外凍結門檻。換成 FLAT freeze.threshold。 */
    public static final String FREEZE_THRESHOLD_FROM_ES = "freeze.threshold_from_es";
    /** INCREASED：凍結門檻（只對凍結累積有效）；FLAT：額外的凍結門檻（由上面換算）。AilmentService 的凍結累積用。 */
    public static final String FREEZE_THRESHOLD = "freeze.threshold";

    /** FLAT：技能魔力消耗的 x% 改成消耗生命（可帶標籤，例如 spell）。SkillService 付消耗時用。 */
    public static final String LIFE_COST_PERCENT = "skill.life_cost_percent";
    /** INCREASED：技能的生命消耗（改成生命的那部分）。 */
    public static final String LIFE_COST = "skill.life_cost";

    /** INCREASED：釘住累積（引擎的釘住是擊中擲機率：機率乘這個）。HitEffects 用。 */
    public static final String PIN_BUILDUP = "pin.buildup";

    /** INCREASED：完全破甲的效果（受到的物理傷害增加的那 x%）。CombatListener 用。 */
    public static final String ARMOUR_BREAK_EFFECT = "armour.break_effect";

    /** INCREASED：你身上的點燃傷害（負的＝減少）。AilmentService 用。 */
    public static final String IGNITE_EFFECT_ON_SELF = "ignite.effect_on_self";
    /** INCREASED：你身上的冰緩效果。 */
    public static final String CHILL_EFFECT_ON_SELF = "chill.effect_on_self";
    /** INCREASED：你身上的感電效果。 */
    public static final String SHOCK_EFFECT_ON_SELF = "shock.effect_on_self";

    /** 屬性表算完時：「等同最大能量護盾 x% 的暈眩／異常／凍結門檻」換成 FLAT 門檻（護盾要先算好）。 */
    public static void applyEsThresholds(final StatSheet sheet) {
        final double es = sheet.get(StatKeys.ES_MAX);
        if (es <= 0) {
            return;
        }
        final String[][] pairs = {{STUN_THRESHOLD_FROM_ES, StatKeys.STUN_THRESHOLD}, {AILMENT_THRESHOLD_FROM_ES, StatKeys.AILMENT_THRESHOLD},
                {FREEZE_THRESHOLD_FROM_ES, FREEZE_THRESHOLD}};
        for (final String[] pair : pairs) {
            final double percent = sheet.flat(pair[0], java.util.Set.of());
            if (percent > 0) {
                sheet.add(Modifier.flat(pair[1], Math.floor(es * percent / 100.0), "能量護盾"));
            }
        }
    }

    /** 凍結門檻：異常門檻 ×（1 ＋ 增加凍結門檻）＋ 額外的凍結門檻。 */
    public static double freezeThreshold(final double ailmentThreshold, final StatSheet defender) {
        return Math.max(0, ailmentThreshold * Math.max(0, 1 + defender.increased(FREEZE_THRESHOLD, java.util.Set.of()))
                + Math.max(0, defender.flat(FREEZE_THRESHOLD, java.util.Set.of())));
    }

    /** 自己身上的異常效果倍率（點燃、冰緩、感電；負的增加＝減少，最低 0）。 */
    public static double selfEffect(final StatSheet defender, final String stat) {
        return Math.max(0, 1 + defender.increased(stat, java.util.Set.of()));
    }
}
