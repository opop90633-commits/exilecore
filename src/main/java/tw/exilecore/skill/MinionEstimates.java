package tw.exilecore.skill;

import tw.exilecore.stats.LineageKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-27：召喚物從主人屬性表、掛的輔助換算出來的修飾器——戰鬥（MinionService.rebuild）、召喚物說明、/stats 與網頁的召喚物每秒傷害共用，
 * 數字才會一樣。這次之前巨伴的閃避與偏斜、「夥伴獲得你的力量」的生命只在戰鬥裡有，看看我怎麼辦事的只有戰鬥與說明有。
 */
final class MinionEstimates {

    private MinionEstimates() {
    }

    /**
     * 巨伴（Tangmazu's Thurible：召喚物獲得你 x% 的閃避值與偏斜值）、夥伴獲得你的力量（天賦：力量給的生命，每點 2，換成召喚物的單位）。純函式（測試用）。
     *
     * @param lifeFactor PoE2 生命換成我們的單位（{@link MinionSheets#poe2Factors} 的第二格）
     * @param companion  這隻算不算夥伴（殘影不算）
     */
    static List<Modifier> ownerDerived(final SkillBuild build, final StatSheet ownerSheet, final double lifeFactor, final boolean companion) {
        final List<Modifier> out = new ArrayList<>();
        if (build == null || ownerSheet == null) {
            return out;
        }
        final double gigantic = LineageRules.total(build.supports(), LineageKeys.GIGANTIC);
        if (gigantic > 0) {
            out.add(Modifier.flat(StatKeys.EVASION, Math.max(0, ownerSheet.get(StatKeys.EVASION)) * gigantic / 100.0, "巨伴"));
            out.add(Modifier.flat(StatKeys.DEFLECTION, Math.max(0, ownerSheet.get(StatKeys.DEFLECTION)) * gigantic / 100.0, "巨伴"));
        }
        if (companion && build.definition().tags().contains(tw.exilecore.stats.TreeMechanicKeys.COMPANION)
                && StatKeys.flag(ownerSheet, tw.exilecore.stats.TreeNewMechanicKeys.FLAG_COMPANION_STRENGTH)) {
            out.add(Modifier.flat(StatKeys.LIFE_MAX, tw.exilecore.stats.Formulas.lifeFromStrength(
                    Math.max(0, ownerSheet.get(StatKeys.STR))) * lifeFactor, "你的力量"));
        }
        return out;
    }

    /** 估計用（召喚物說明、召喚物每秒傷害）：上面那些，再加看看我怎麼辦事的（當作在存在範圍內）。 */
    static List<Modifier> estimate(final SkillBuild build, final StatSheet ownerSheet, final double lifeFactor) {
        final List<Modifier> out = ownerDerived(build, ownerSheet, lifeFactor, true);
        if (ownerSheet != null) {
            out.addAll(tw.exilecore.ascendancy.AscendancyEffects.minionWeaponFrom(ownerSheet));
        }
        return out;
    }
}
