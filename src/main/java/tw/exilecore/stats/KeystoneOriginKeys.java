package tw.exilecore.stats;

import java.util.Set;

/**
 * 2026-10-27 照站長「天賦基石有些沒照原作的，看能不能比照原作」：把之前做成平衡等效的基石與核心天賦換回 PoE2 0.5 的原作機制時新加的屬性鍵與純計算
 * （不放進 {@link StatKeys}／{@link KeystoneKeys}，方便合併）。Bukkit 那一邊在 {@code tw.exilecore.combat.KeystoneEffects}、
 * {@code tw.exilecore.combat.Impales}、技能執行（SkillExecutor／SkillService）與屬性表（PlayerStateService）。
 */
public final class KeystoneOriginKeys {

    private KeystoneOriginKeys() {
    }

    // ---- 穿刺（PoE2 Impale）：Impales ----
    /** FLAT %：法術擊中時穿刺的機率（帶 spell 標籤）。 */
    public static final String IMPALE_SPELL_CHANCE = "impale.spell_chance";
    /** INCREASED：你造成的穿刺效果（帶 spell 標籤 = 法術造成的穿刺）。 */
    public static final String IMPALE_MAGNITUDE = "impale.magnitude";
    /** FLAT（只在一下擊中的屬性表上）：這一下攻擊抽出的穿刺，加在減免前的物理傷害（不再吃增加、更多、暴擊）。DamageCalculator。 */
    public static final String IMPALE_EXTRACT = "impale.extract";
    /** PoE2：穿刺存下擊中減免前物理傷害的 30%。 */
    public static final double IMPALE_BASE = 0.30;
    /** PoE2：一隻敵人身上最多 60 個穿刺（超過時丟掉最弱的）。 */
    public static final int IMPALE_MAX = 60;

    // ---- 異常 ----
    /** FLAT %：用掉敵人身上的感電時重新施加它的機率（無限循環）。AilmentService.consume。 */
    public static final String SHOCK_REAPPLY = "shock.reapply_on_consume";

    // ---- 技能 ----
    /** FLAT %：裂地技能多出一道裂縫的機率（分裂地面；帶 fissure 標籤）。SkillExecutor。 */
    public static final String EXTRA_FISSURE_CHANCE = "skill.additional_fissure_chance";
    /** 多出的那道裂縫往旁邊偏幾度。 */
    public static final double EXTRA_FISSURE_ANGLE = 25;
    /** FLAT %：打擊技能造成擴散傷害的機率（劈裂大地：錘的打擊；帶 strike、mace 標籤）；擲中就照 {@link #SPLASH_ON_PROC}% 擴散。 */
    public static final String SPLASH_CHANCE = "skill.melee_splash_chance";
    /** 擴散傷害（PoE2 Splash）打到周圍的敵人幾 %：跟擴散輔助一樣 60。 */
    public static final double SPLASH_ON_PROC = 60;
    /** 旗標：可連瀑的法術（定點落下的範圍法術）最後一次回響也往目標區域的兩側各落一次（神幻雪崩）。SkillService 的回響。 */
    public static final String FINAL_ECHO_CASCADE = "flag.final_echo_cascade";

    // ---- 詛咒 ----
    /** INCREASED：詛咒的持續時間過了一半之後，效果增加 x（末日厄運）。SkillExecutor 的詛咒。 */
    public static final String CURSE_LATE_MAGNITUDE = "curse.late_magnitude";
    /** 旗標：被你詛咒的敵人不能回充能量護盾（命運終結；怪物「護盾的」詞綴的護盾）。MonsterMechanics。 */
    public static final String CURSE_NO_ES_RECHARGE = "flag.curse_no_es_recharge";
    /** 不能回充護盾的標記（Debuffs 的代號，跟詛咒同時結束）。 */
    public static final String NO_ES_RECHARGE_ID = "keystone.no_es_recharge";

    // ---- 擊中、擊殺 ----
    /** INCREASED：箭飛得越遠暴擊率越高，飛滿 {@link #ARROW_FULL_METRES} 格給滿（狙擊手）。CombatListener 的條件傷害。 */
    public static final String ARROW_DISTANCE_CRIT = "crit.arrow_distance";
    /** 狙擊手：7 格給滿。 */
    public static final double ARROW_FULL_METRES = 7;
    /** FLAT %：殺死燃燒中的敵人時牠爆炸的機率，造成牠最大生命十分之一的火焰傷害（暴烈衝擊）。KeystoneEffects.onDeath。 */
    public static final String BURNING_EXPLODE_CHANCE = "kill.burning_explode_chance";
    /** 燃燒的敵人爆炸：最大生命的十分之一。 */
    public static final double BURNING_EXPLODE_SHARE = 0.1;

    // ---- 防具的移動懲罰（PoE2 Armour Movement Penalty） ----
    /** 旗標：無視防具的所有移動懲罰（氣勢）。PlayerStateService。 */
    public static final String IGNORE_ARMOUR_MOVE_PENALTY = "flag.ignore_armour_move_penalty";

    // ---- 2026-10-27：巨人之血的雙持見 character.DualWield、Gear.giantOffHand（這裡原本的平衡等效拿掉了） ----
    /** 穿刺存下的傷害：減免前的物理傷害 × 30% ×（1 ＋ 增加穿刺效果）。 */
    public static double impaleAmount(final double physical, final double magnitudeIncreased) {
        return Math.max(0, physical) * IMPALE_BASE * Math.max(0, 1 + magnitudeIncreased);
    }

    /** 狙擊手：飛了 metres 格的箭，暴擊率增加多少（照距離線性，7 格給滿 max）。 */
    public static double arrowCrit(final double max, final double metres) {
        if (max <= 0 || metres <= 0) {
            return 0;
        }
        return max * Math.min(1, metres / ARROW_FULL_METRES);
    }

    /**
     * PoE2 防具的移動懲罰（Path of Building PoE2 的 MovementPenalty，BaseItemTypes 的移動速度）：身體護甲純力量 5%、力量混合 4%、其他 3%；
     * 盾牌純力量 3%、力量混合 1.5%；圓盾（敏捷）、法器沒有。其他欄位 0。
     *
     * @param slot 欄位名稱（ItemSlot.name()）
     */
    public static double armourMovePenalty(final String slot, final Set<String> tags) {
        if (tags == null) {
            return 0;
        }
        final boolean str = tags.contains("str_armour");
        final boolean hybrid = str && (tags.contains("dex_armour") || tags.contains("int_armour"));
        if ("CHEST".equals(slot) && tags.contains("body")) {
            return !str ? 0.03 : hybrid ? 0.04 : 0.05;
        }
        if ("OFF_HAND".equals(slot) && tags.contains("shield") && !tags.contains("focus")) {
            return !str ? 0 : hybrid ? 0.015 : 0.03;
        }
        return 0;
    }

    /** 詛咒過了一半的那一刻（tick）：施加的 tick ＋（結束 − 施加）／2。 */
    public static long curseHalfway(final long applied, final long until) {
        return applied + Math.max(0, until - applied) / 2;
    }
}
