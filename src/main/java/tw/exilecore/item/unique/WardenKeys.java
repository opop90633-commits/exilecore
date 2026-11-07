package tw.exilecore.item.unique;

import java.util.Set;

/**
 * 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」：遺忘看守（PoE2 Forgotten Warden，野紋纏衣）用到的新屬性鍵與純邏輯。
 * 登記方式跟 {@link VaalKeys} 一樣（資料檢查 {@link UniqueStats#all()}）。
 * <ul>
 *   <li>{@link #DEFLECTION_PER_50_MISSING_ES}：每失去 50 能量護盾 +x 偏斜值（CombatListener 在被擊中那一下照當下的護盾加進防守方的表）。</li>
 *   <li>{@link #COMPANION_TAKES_DEFLECTED}：被偏斜的擊中傷害有 x% 先由可受傷害的夥伴的生命承受
 *       （CombatListener.applyToPlayer；跟天賦樹的 {@code companion.takes_damage}、伴償同一套 MinionService.absorbForOwner）。</li>
 *   <li>{@link #GRANT_SOUL_VESSEL}：賦予技能靈魂容器（值 16 = 固定 16 級，UniqueDerived.grantedSkillLevels）。</li>
 * </ul>
 */
public final class WardenKeys {

    private WardenKeys() {
    }

    /** FLAT：每失去 50 能量護盾 +x 偏斜值（PoE2 deflection_rating_per_50_missing_maximum_energy_shield）。 */
    public static final String DEFLECTION_PER_50_MISSING_ES = "deflection.per_50_missing_es";
    /** FLAT：被偏斜的擊中傷害有 x% 先由夥伴承受（PoE2 companion_takes_%_damage_from_deflected_hits_before_you）。 */
    public static final String COMPANION_TAKES_DEFLECTED = "companion.takes_damage_deflected";
    /** 賦予技能：靈魂容器（skills.yml 的 soul_vessel）。 */
    public static final String GRANT_SOUL_VESSEL = UniqueStats.GRANT_SKILL + "soul_vessel";

    /** 「每失去 50」的那一格（能量護盾）。 */
    public static final double MISSING_ES_STEP = 50;

    /** 進角色屬性表的屬性。 */
    public static final Set<String> STATS = Set.of(DEFLECTION_PER_50_MISSING_ES, COMPANION_TAKES_DEFLECTED, GRANT_SOUL_VESSEL);

    /**
     * 每失去 50 能量護盾給的偏斜值：floor((最大護盾 − 目前護盾) ÷ 50) × perStep（照 PoE2「per X」的寫法無條件捨去；
     * 護盾是滿的、沒有護盾上限、perStep ≤ 0 都是 0）。
     */
    public static double deflectionFromMissingEs(final double perStep, final double maxEs, final double es) {
        if (perStep <= 0 || maxEs <= 0) {
            return 0;
        }
        final double missing = Math.max(0, maxEs - Math.max(0, es));
        return Math.floor(missing / MISSING_ES_STEP + 1e-9) * perStep;
    }

    /**
     * 被偏斜的那一下要夥伴先承受多少：total × percent%（percent 最多 100）；沒有偏斜、沒有詞綴回傳 0。
     * 真正扣多少看夥伴剩多少生命（MinionService.absorbForOwner 最多扣到剩 1 點）。
     */
    public static double deflectedShare(final boolean deflected, final double total, final double percent) {
        if (!deflected || total <= 0 || percent <= 0) {
            return 0;
        }
        return total * Math.min(100, percent) / 100.0;
    }
}
