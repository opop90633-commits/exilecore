package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-20（照 PoE）：圖騰攻擊的間隔吃放圖騰的人的攻速／施法速度，有下限。 */
class TotemSpeedTest {

    @Test
    void fasterOwnersMakeFasterTotems() {
        assertEquals(20, SkillDeployables.totemTicks(1.0, 1.0), "沒有加成：照 interval");
        assertEquals(10, SkillDeployables.totemTicks(1.0, 2.0), "攻速兩倍：間隔一半");
        assertEquals(16, SkillDeployables.totemTicks(1.0, 1.25));
        assertEquals(40, SkillDeployables.totemTicks(1.0, 0.5), "更少攻速：變慢");
        assertEquals(SkillDeployables.TOTEM_MIN_TICKS, SkillDeployables.totemTicks(0.3, 10), "再快也有下限");
        assertEquals(200, SkillDeployables.totemTicks(1.0, 0), "倍率不會變成 0（除以零）");
    }

    /** 2026-10-21：英靈照錘子技能的攻擊時間 ×1.5，再除以攻速；越慢的技能叫得越慢。 */
    @Test
    void ancestorsFollowTheLinkedSkillsAttackTime() {
        assertEquals(30, SkillDeployables.ancestorTicks(1.0, 1.0, 1.0), "攻速 1、技能 100%：1.5 秒");
        assertEquals(67, SkillDeployables.ancestorTicks(1.0, 0.45, 1.0), "45% 的慢技能：3.33 秒");
        assertEquals(17, SkillDeployables.ancestorTicks(1.2, 0.45, 3.33), "武器 1.2、攻速 333%");
        assertEquals(15, SkillDeployables.ancestorTicks(1.0, 1.0, 2.0), "攻速兩倍：間隔一半");
        assertEquals(SkillDeployables.TOTEM_MIN_TICKS, SkillDeployables.ancestorTicks(2.0, 1.5, 20), "再快也有下限");
    }
}
