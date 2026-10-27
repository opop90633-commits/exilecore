package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeKeys;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** 2026-10-26 照 PoE2 天賦樹：「x% 的技能魔力消耗改成生命消耗」「增加技能的生命消耗」。 */
class LifeCostSplitTest {

    @Test
    void convertsPartOfTheManaCost() {
        final StatSheet sheet = new StatSheet();
        assertArrayEquals(new int[] {0, 20}, SkillService.lifeCostSplit(20, false, sheet, Set.of(Tags.ATTACK)), "沒有天賦：全扣魔力");
        assertArrayEquals(new int[] {20, 0}, SkillService.lifeCostSplit(20, true, sheet, Set.of(Tags.ATTACK)), "血魔法：全扣生命");
        sheet.add(Modifier.flat(TreeKeys.LIFE_COST_PERCENT, 15, "樹"));
        sheet.add(new Modifier(TreeKeys.LIFE_COST_PERCENT, ModType.FLAT, 15, Set.of(Tags.SPELL), "樹"));
        assertArrayEquals(new int[] {3, 17}, SkillService.lifeCostSplit(20, false, sheet, Set.of(Tags.ATTACK)));
        assertArrayEquals(new int[] {6, 14}, SkillService.lifeCostSplit(20, false, sheet, Set.of(Tags.SPELL)), "法術另外 15%");
        sheet.add(Modifier.increased(TreeKeys.LIFE_COST, 1.0, "樹"));
        assertArrayEquals(new int[] {12, 14}, SkillService.lifeCostSplit(20, false, sheet, Set.of(Tags.SPELL)), "生命消耗增加 100%");
        assertArrayEquals(new int[] {0, 0}, SkillService.lifeCostSplit(0, false, sheet, Set.of(Tags.SPELL)));
    }
}
