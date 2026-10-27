package tw.exilecore.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 角色面板上護甲、閃避、命中的百分比。 */
class DefenceEstimatesTest {

    @Test
    void percentagesFollowTheCombatFormulas() {
        // 怪物一下 80–120（平均 100）、命中 500、閃避 400；角色護甲 1000、閃避 2000、命中 600
        final DefenceEstimates e = DefenceEstimates.of(80, 120, 500, 400, 1000, 2000, 600);
        assertEquals(100, e.monsterHit(), 1e-9);
        assertEquals(1000.0 / (1000 + 10 * 100), e.armourReduction(), 1e-9, "護甲 ÷ (護甲 + 10 × 傷害)");
        assertEquals(1000.0 / (1000 + 10 * 300), e.armourReductionBigHit(), 1e-9);
        assertTrue(e.armourReductionBigHit() < e.armourReduction(), "大的一下減得比較少");
        // 2026-10-27 照 PoE2：怪物打玩家用怪物的命中公式（這次之前 1 − hitChance(500, 2000)）
        assertEquals(1 - Formulas.monsterHitChance(500, 2000), e.evadeChance(), 1e-9);
        assertEquals(Formulas.hitChance(600, 400), e.hitChance(), 1e-9);
    }

    @Test
    void noDefencesMeansNoReduction() {
        final DefenceEstimates e = DefenceEstimates.of(10, 20, 100, 0, 0, 0, 50);
        assertEquals(0, e.armourReduction(), 1e-9);
        assertEquals(0, e.evadeChance(), 1e-9, "沒有閃避：一定被打中");
        assertEquals(1, e.hitChance(), 1e-9, "怪物沒有閃避：一定打中");
    }
}
