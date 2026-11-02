package tw.exilecore.ascendancy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 2026-11-02 照站長：生命殘息吃殘骸輔助（和骸：多一個的機率）。 */
class LifeRemnantSupportTest {

    @Test
    void harmonicRemnantsRollsExtra() {
        assertEquals(1, AscGranted.lifeRemnantCount(0, 0.0), "沒有和骸：一個");
        assertEquals(2, AscGranted.lifeRemnantCount(20, 0.19), "和骸 II 20%：擲到 19 → 兩個");
        assertEquals(1, AscGranted.lifeRemnantCount(20, 0.2), "擲到 20 → 一個");
    }
}
