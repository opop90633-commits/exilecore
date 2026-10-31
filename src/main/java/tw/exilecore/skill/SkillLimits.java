package tw.exilecore.skill;

import tw.exilecore.stats.KeystoneKeys;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;

import java.util.Set;

/**
 * 2026-10-28 照 PoE2 的「上限」（Limit）：原作技能說明裡寫「上限 N 個…」的技能（RePoE skills.json 的技能類型有 Limit），
 * 「+1 上限」（skill.limit，過剩 I／II、掠嵐、傳奇、天賦樹）讓它們同時存在的數量多幾個。
 * <p>
 * {@link #SKILLS} 是原作有 Limit、我們的引擎也真的有上限的技能：SkillDefinition 讀檔時自動補上 {@link #TAG} 標籤（過剩輔助 requires 看這個）。
 * 各個上限在哪裡加（2026-10-28 一起接上；這次之前只有地面效果 zone.max 吃 skill.limit）：
 * <ul>
 *   <li>圖騰（SkillBuild.totems，含星咒圖騰）、鐘（SkillDeployables：驚雷之鐘）、空無之鐘（AscPaths）</li>
 *   <li>地面效果（SkillExecutor.zoneMax：炎脈裂地、流焰天幕、雷雲、烈陽珠、積雷雲、野旋風、地火柱、穢陣、土壁擊、魯兹安的陷阱）</li>
 *   <li>召喚物上限（SkillBuild.minions，只有帶 limit 標籤的：喚屍、召喚焚魂、掘骸的骨構體、蟲噬群）</li>
 *   <li>龍捲風（裂空箭）、荊棘（荊棘矢）、毒苞（瘴苞雨）、鋸齒地面（地動）、插著的矛（炎爆矛、霆落矛）、旋風（颶風槍）、
 *       雷流場的電球、燼種、折光之鏡、腐屍爆的毒雲、祭品的骨柱、印記標記的目標</li>
 * </ul>
 * 原作有 Limit、但我們的版本沒有上限的不算（掛了也沒用）：蝕毒箭、瘴氣榴彈（毒霧不限）、地刺崩裂、回旋鍛錘（裂地是一次性的）、
 * 寒晶雷、退步凝晶、縛根、刺棘身、抽荊。
 * 2026-10-28：腐屍爆照 PoE2 改成毒雲（上限 6 團，DecomposeClouds），加進 {@link #SKILLS}；這次之前列在上面（機制跟原作不同）。
 */
public final class SkillLimits {

    /** 有「上限」的技能（過剩輔助要這個標籤）。 */
    public static final String TAG = "limit";

    /** 原作有 Limit、我們也有上限的技能代號。 */
    public static final Set<String> SKILLS = Set.of(
            // 圖騰、鐘
            "collapse_quake_totem", "nether_puppet", "skyward_crossbow_turret", "nailfall_crossbow_turret", "star_spell_totem", "startling_thunder_bell", "hollow_focus",
            // 地面效果
            "flame_vein_rift", "flowing_flame_canopy", "thunder_cloud", "scorching_sun_pearl", "gathered_thundercloud", "wild_whirlwind", "volcano", "filth_array",
            "earth_wall_strike", "blade_spirit_device", "root_bind",
            // 召喚物
            "corpse_summon", "summon_burning_souls", "dig_remains", "unearth_construct", "insect_devour_horde",
            // 各自的上限
            "sky_rend_arrow", "thorn_shaft", "miasma_bud_rain", "ground_tremor", "explosive_spear", "thunderfall_spear", "hurricane_spear",
            "thunder_current_zone", "cinder_sprout", "light_bend_mirror", "rotting_corpse_blast",
            // 祭品（各一根骨柱）、印記（各標記一個目標）
            "bloodthirst_rite", "bone_rite", "soulfire_rite",
            "prey_kill_mark", "thunder_lure_seal", "frostbind_seal", "blood_scent_seal");

    private SkillLimits() {
    }

    /** 這個技能的「+x 上限」（小數捨去、不會是負的）；effective 是施放者的屬性表疊上這個技能的輔助。 */
    public static int bonus(final StatSheet effective, final Set<String> tags) {
        if (effective == null) {
            return 0;
        }
        return (int) Math.floor(Math.max(0, effective.flat(KeystoneKeys.SKILL_LIMIT, tags)) + 1e-9);
    }

    /** 同上；owner 是施放者的屬性表（null 時只看技能自己的輔助）。 */
    public static int bonus(final StatSheet owner, final SkillProfile profile) {
        if (profile == null) {
            return 0;
        }
        if (owner == null) {
            return (int) Math.floor(Math.max(0, SkillZones.localFlat(profile, KeystoneKeys.SKILL_LIMIT)) + 1e-9);
        }
        return bonus(profile.effective(owner), profile.tags());
    }

    /** 同上（技能組合）。 */
    public static int bonus(final StatSheet owner, final SkillBuild build) {
        return build == null ? 0 : bonus(owner, build.profile());
    }
}
