package tw.exilecore.skill;

import tw.exilecore.stats.Tags;

import java.util.Set;

/**
 * 2026-10-28 照 PoE2 的 Cascadable（RePoE poe2 skills.json 技能類型有 Cascadable 的）：連瀑（Spell Cascade）、三迴（Zarokh's Refrain）、
 * 雪封（Tul's Avalanche）、祭脈（Kulemak's Dominion 在祭柱那裡再放）、神幻雪崩（最後一次回響也連瀑）都只認這個。
 * 對照工具 tools/audit_poe2_cascadable.py（我們的技能 → PoE2 寶石 → 技能類型）。
 * <p>
 * 原作有對應的技能照原作：{@link #POE2} 是 Cascadable 的、{@link #NOT_POE2} 是原作有對應但不是 Cascadable 的；
 * 原作沒有對應的（我們自己的技能、昇華、Boss 專屬、輔助觸發的隱藏技能）照形狀：落在目標區域或以自己為中心的範圍法術
 * （定點的衝擊、圓形的地面效果、新星；不是引導的）。
 * <p>
 * 這次之前三個地方各寫一套（SkillService：衝擊、圓形地面；SupportGuards：再加法術新星；LineageRules：衝擊、圓形地面、新星，不算引導），
 * 而且只看形狀：詛咒（三元、凋魂、創痕、萎力、遲滯）、焰幕、霜壁、骸爆、屍焰爆掛不上連瀑，
 * 雷雲、烈陽珠、燼種、疫潮、野旋風、地火柱、雷流場、血鐮、冥契、炎核爆、精靈的指令、可趁之刻（原作不是 Cascadable）卻掛得上。
 */
public final class SkillCascade {

    /** 原作 Cascadable 的技能（RePoE；我們的代號）。 */
    public static final Set<String> REF = Set.of(
            "frost_star_blast", "falling_star", "flame_curtain", "corpse_burst", "flowing_flame_canopy", "erosion_curse_rift", "chill_crystal_mine", "frost_rampart", "gathered_thundercloud",
            "lashing_thorns", "filth_array", "bone_thorn_ring", "anomaly_devour_blast", "bone_bloom", "corpse_flame_burst", "thunder_drop", "fading_thunder_ring",
            "blade_spirit_settle",
            // 詛咒（原作每個詛咒都是 Cascadable：連瀑讓詛咒往兩側各多落一圈）
            "wound_scar_curse", "soul_wither_curse", "triple_element_curse", "strength_sap_curse", "sluggish_curse");

    /** 原作有對應、但不是 Cascadable 的（照形狀會被當成可以連瀑的）。 */
    public static final Set<String> NOT_REF = Set.of(
            "thunder_cloud", "scorching_sun_pearl", "cinder_sprout", "plague_taint", "wild_whirlwind", "volcano", "thunder_current_zone", "blood_sickle", "nether_contract",
            "flame_core_detonation",
            // 精靈的指令、昇華（原作只有魯兹安的清算是 Cascadable）
            "sand_spirit_trick", "sand_spirit_verdict", "sand_spirit_cruelty", "mirage_spirit_freeze", "blade_spirit_device", "gap_opportunity");

    private SkillCascade() {
    }

    /** 可以連瀑（PoE2 Cascadable）。 */
    public static boolean cascadable(final SkillDefinition skill) {
        if (skill == null || !skill.tags().contains(Tags.SPELL)) {
            return false;
        }
        if (REF.contains(skill.id())) {
            return true;
        }
        return !NOT_REF.contains(skill.id()) && shaped(skill);
    }

    /**
     * 連瀑往兩側隔多遠用的半徑：詛咒是詛咒圈的半徑（照等級、範圍倍率；build.radius() 對詛咒不是逐級的詛咒圈），其他是技能的範圍半徑。
     * 這次之前詛咒不能連瀑。
     */
    static double radius(final SkillBuild build) {
        final SkillParams.Mark mark = build.definition().mechanic() == Mechanic.MARK ? build.params().mark() : null;
        if (mark != null && mark.curse()) {
            return mark.radius(build.level()) * build.areaScale();
        }
        return build.radius();
    }

    /** 原作沒有對應的照形狀：落在目標區域或以自己為中心的範圍法術（不是引導的）。 */
    static boolean shaped(final SkillDefinition skill) {
        if (!skill.tags().contains(Tags.SPELL) || !skill.tags().contains(Tags.AREA) || skill.tags().contains("channelling")) {
            return false;
        }
        return skill.mechanic() == Mechanic.NOVA || skill.mechanic() == Mechanic.IMPACT
                || skill.mechanic() == Mechanic.ZONE && skill.params() != null && skill.params().zone() != null && !skill.params().zone().line();
    }
}
