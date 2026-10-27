package tw.exilecore.stats;

/**
 * 2026-10-26：PoE2 0.5 天賦樹「有條件」那一類 stat 要用的新屬性鍵（事件發生時的效果；條件本身在 {@link TreeConditions}）。
 * 消費端在 {@link tw.exilecore.character.ConditionTracker}（變身、變回人形、消耗戰星、被重暈、消耗灌注、放混沌技能）
 * 與 SkillService 的舉盾。
 */
public final class ConditionKeys {

    /** 消耗一顆銳星時回復最大魔力的 x%（PoE2 recover_x%_of_maximum_mana_when_you_consume_a_power_charge）。 */
    public static final String MANA_ON_POWER_SPENT = "recover.mana_on_power_spent";
    /** 每消耗一顆磐星回復最大生命的 x%（PoE2 recover_%_life_per_endurance_charge_consumed）。 */
    public static final String LIFE_PER_ENDURANCE_SPENT = "recover.life_per_endurance_spent";
    /** 被重暈時 1 秒內回復最大生命的 x%（PoE2 regenerate_%_life_over_1_second_when_stunned）。 */
    public static final String LIFE_ON_STUNNED = "recover.life_on_stunned";
    /** 消耗元素灌注時獲得一顆銳星（旗標）。 */
    public static final String POWER_ON_INFUSION = "infusion.power_on_consume";
    /** 放混沌技能時失去最大生命與能量護盾的 x%。 */
    public static final String CHAOS_SKILL_SELF_DAMAGE = "chaos_skill.lose_life_es";
    /** 變回人形時：變身期間每秒給 x% 法術傷害（最多 {@link #REVERT_SPELL_CAP_FACTOR} 倍），持續 8 秒。 */
    public static final String REVERT_SPELL_PER_SECOND = "shapeshift.revert_spell_damage";
    /** 變回人形時用掉全部怒氣，每點回復最大生命的 x%。 */
    public static final String REVERT_RAGE_LIFE = "shapeshift.revert_rage_life";
    /** 舉盾的移動變慢（INCREASED；負的＝變慢得少）。 */
    public static final String GUARD_SLOW = "guard.slow";

    /** PoE2：「每秒 10%，最多 80%」＝每秒數值的 8 倍。 */
    public static final double REVERT_SPELL_CAP_FACTOR = 8;
    /** 變回人形的法術傷害持續幾 tick（PoE2：8 秒）。 */
    public static final int REVERT_SPELL_TICKS = 160;

    private ConditionKeys() {
    }
}
