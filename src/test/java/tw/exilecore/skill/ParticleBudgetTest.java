package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 粒子的預算（{@link ParticleBudget}，2026-10 打 Boss 時畫面偶爾卡一下）。 */
class ParticleBudgetTest {

    @Test
    void shapesShareTheBudgetButKeepAMinimum() {
        final ParticleBudget budget = new ParticleBudget(100);
        final long area = ParticleBudget.area(7, 0, 0);
        assertEquals(60, budget.allow(1, area, 60, 10));
        assertEquals(40, budget.allow(1, area, 60, 10), "剩多少給多少");
        assertEquals(10, budget.allow(1, area, 60, 10), "用完了還是給最少的點數（形狀看得出來）");
        assertEquals(5, budget.allow(1, area, 5, 10), "要的比最少的還少：照要的給");
        assertEquals(115, budget.spent(1, area));
        assertEquals(60, budget.allow(2, area, 60, 10), "下一個 tick 重新算");
        assertEquals(0, budget.allow(2, area, 0, 10));
    }

    @Test
    void separateFightsDoNotShareABudget() {
        final ParticleBudget budget = new ParticleBudget(100);
        final long here = ParticleBudget.area(7, 10, 10);
        final long sameArea = ParticleBudget.area(7, 100, 120);
        final long farAway = ParticleBudget.area(7, 1000, 10);
        final long otherWorld = ParticleBudget.area(8, 10, 10);
        assertEquals(here, sameArea, "同一個 128 格的區域");
        assertNotEquals(here, farAway);
        assertNotEquals(here, otherWorld);
        assertNotEquals(ParticleBudget.area(7, -10, 10), here, "負的座標是另一區");
        assertEquals(100, budget.allow(5, here, 100, 10));
        assertEquals(100, budget.allow(5, farAway, 100, 10), "別的地方的 Boss 戰不受影響");
        assertEquals(100, budget.allow(5, otherWorld, 100, 10));
        assertEquals(10, budget.allow(5, sameArea, 100, 10));
    }
}
