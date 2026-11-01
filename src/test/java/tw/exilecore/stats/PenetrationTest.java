package tw.exilecore.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照 PoE2：穿透只能把抗性降到 0，負抗性不再降。 */
class PenetrationTest {
    @Test
    void penetrationStopsAtZero() {
        assertEquals(10, DamageCalculator.penetrated(40, 30), 1e-9);
        assertEquals(0, DamageCalculator.penetrated(20, 30), 1e-9, "最多降到 0");
        assertEquals(-20, DamageCalculator.penetrated(-20, 30), 1e-9, "負抗性不變");
        assertEquals(0, DamageCalculator.penetrated(0, 30), 1e-9);
        assertEquals(75, DamageCalculator.penetrated(75, 0), 1e-9);
    }
}
