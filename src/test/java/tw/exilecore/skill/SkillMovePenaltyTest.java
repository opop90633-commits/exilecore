package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeEffectKeys;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-26 照 PoE2：移動中使用技能的移動速度懲罰（SkillMovePenalty）。 */
class SkillMovePenaltyTest {

    @Test
    void baseFollowsPoe2() {
        assertEquals(0.70, SkillMovePenalty.base(Mechanic.PROJECTILE, false, false, false), 1e-9, "大部分技能：更少 70%");
        assertEquals(0.70, SkillMovePenalty.base(Mechanic.STRIKE, false, false, false), 1e-9, "近戰也照 70%（原作會停下來）");
        assertEquals(0.50, SkillMovePenalty.base(Mechanic.ZONE, true, false, false), 1e-9, "召喚物指令：更少 50%");
        assertEquals(0, SkillMovePenalty.base(Mechanic.DASH, false, false, false), 1e-9, "位移技能不算");
        assertEquals(0, SkillMovePenalty.base(Mechanic.LEAP, false, false, false), 1e-9);
        assertEquals(0, SkillMovePenalty.base(Mechanic.AURA, false, false, false), 1e-9, "光環開關不算");
        assertEquals(0, SkillMovePenalty.base(Mechanic.GUARD, false, false, false), 1e-9, "舉盾自己有減速");
        assertEquals(0, SkillMovePenalty.base(Mechanic.PROJECTILE, false, false, true), 1e-9, "騎著坐騎：原作 -100% 懲罰");
    }

    @Test
    void reductionsMultiply() {
        final StatSheet sheet = new StatSheet();
        assertEquals(0.70, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9);
        // 天賦樹：減少 5% ＋ 減少 3%
        sheet.add(Modifier.increased(TreeEffectKeys.ACTION_PENALTY, -0.05, "樹"));
        sheet.add(Modifier.increased(TreeEffectKeys.ACTION_PENALTY, -0.03, "樹"));
        assertEquals(0.70 * 0.92, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9);
        // 衝刺突擊：更少 30%；鑲嵌的寶石（綠）：更少 40%
        sheet.add(Modifier.more(TreeEffectKeys.ACTION_PENALTY, -0.30, "衝刺突擊"));
        sheet.add(Modifier.more(TreeEffectKeys.ACTION_PENALTY, -0.40, "鑲嵌的寶石"));
        assertEquals(0.70 * 0.92 * 0.7 * 0.6, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9);
        // 舉盾、引導用的也一起算（同一個屬性）
        assertEquals(0.50 * 0.92 * 0.7 * 0.6, TreeEffectKeys.actionPenalty(sheet, 0.50), 1e-9);
    }

    @Test
    void deliberationMakesItWorseAndClamps() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.more(TreeEffectKeys.ACTION_PENALTY, 0.30, "凝息"));
        assertEquals(0.91, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9, "凝息：更多 30% → 91%");
        sheet.add(Modifier.increased(TreeEffectKeys.ACTION_PENALTY, 1.0, "測試"));
        assertEquals(1.0, SkillMovePenalty.penalty(sheet, 0.70, Set.of()), 1e-9, "最多停住，不會倒退");
        assertEquals(0, SkillMovePenalty.penalty(sheet, 0, Set.of()), 1e-9, "不算的技能還是 0");
    }
}
