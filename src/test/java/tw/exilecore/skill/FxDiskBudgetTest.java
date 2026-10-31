package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-31：範圍特效簡化後的粒子數（外框＋裡面）大約是原本的一半。 */
class FxDiskBudgetTest {

    private static int total(final int count, final double r) {
        final int budget = Math.max(Fx.MIN_DISK_PARTICLES, (int) Math.round(count * Fx.DISK_BUDGET));
        final int ring = Math.max(8, Math.min(32, Math.min((int) Math.ceil(2 * Math.PI * r / Fx.RING_SPACING), budget * 2 / 3)));
        return ring + Math.max(2, budget - ring);
    }

    @Test
    void roughlyHalfOfBefore() {
        assertTrue(total(30, 3) <= 16, "30 顆、半徑 3：" + total(30, 3));
        assertTrue(total(40, 4) <= 21, "40 顆、半徑 4：" + total(40, 4));
        assertTrue(total(14, 2) <= 10, "14 顆、半徑 2：" + total(14, 2));
    }
}
