package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 Bonestorm（碎骨飛矢；Path of Building pob2 Data/Skills/act_int.lua 的 BonestormPlayer，第二組數值 Explosion、
 * StatDescriptions/Specific_Skill_Stat_Descriptions/bonestorm_statset_1）：
 * <ul>
 *   <li>骨刺打中時炸開（active_skill_base_area_of_effect_radius 5 = 0.5 公尺；爆炸自己的逐級表，skills.yml 的 stages.explosion）</li>
 *   <li>放出時用掉身上全部的銳星（PoE2 的 Power Charge；SkillConsumesPowerChargesOnUse）：有用掉就爆炸半徑 +0.7 公尺
 *       （bone_spear_power_charged_aoe_+ 7），每用掉一顆再更多 40% 範圍（bone_spear_power_charged_aoe_+%_final_per_additional_power_charge 40；
 *       Path of Building 照用掉的顆數 RemovablePowerCharge 乘）。半徑先加 0.7 再乘範圍（CalcOffence：baseRadius = radius + radiusExtra）</li>
 * </ul>
 * 骨刺與爆炸都是法術擊中 100% 穿刺、穿刺效果更多 200%（spells_impale_on_hit_%_chance、active_skill_impale_magnitude_+%_final；
 * skills.yml 的 modifiers，KeystoneEffects.impale）。純邏輯的部分不依賴 Bukkit。
 */
final class Bonestorm {

    private Bonestorm() {
    }

    static final String ID = "shattered_bone_darts";
    /** 爆炸的基礎半徑（公尺 = 格）。 */
    static final double BASE_RADIUS = 0.5;
    /** 用掉銳星時半徑 +（公尺 = 格）。 */
    static final double CHARGED_RADIUS_PLUS = 0.7;
    /** 每用掉一顆銳星更多幾 % 範圍。 */
    static final double AREA_MORE_PER_CHARGE = 40;
    /** 一次最多用掉幾顆（防呆；身上的銳星上限遠小於這個）。 */
    static final int MAX_CONSUMED = 20;

    /**
     * 用掉 charges 顆（倍星算兩顆）之後，爆炸半徑是沒用掉時的幾倍：(0.5 + 0.7) ÷ 0.5 × √(1 + 0.4 × 顆數)；沒用掉是 1。純函式（測試用）。
     */
    static double radiusScale(final int charges) {
        if (charges <= 0) {
            return 1.0;
        }
        return (BASE_RADIUS + CHARGED_RADIUS_PLUS) / BASE_RADIUS * Math.sqrt(1 + AREA_MORE_PER_CHARGE / 100.0 * charges);
    }
}
