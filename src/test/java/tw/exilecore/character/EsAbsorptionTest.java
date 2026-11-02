package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-02：沒裝模組的人看到的能量護盾（原版吸收愛心）。 */
class EsAbsorptionTest {

    @Test
    void proportionalToLife() {
        assertEquals(0, PlayerStateService.esAbsorption(0, 500, 1000), "沒有護盾");
        assertEquals(0, PlayerStateService.esAbsorption(100, 0, 1000), "沒有護盾上限");
        assertEquals(20, PlayerStateService.esAbsorption(500, 500, 1000), "護盾是生命的一半：一排");
        assertEquals(40, PlayerStateService.esAbsorption(1000, 1000, 1000), "跟生命一樣：兩排");
        assertEquals(PlayerStateService.VANILLA_MAX_HEALTH, PlayerStateService.esAbsorption(5000, 5000, 1), "護盾流（生命 1）：最多兩排");
        assertEquals(1, PlayerStateService.esAbsorption(1, 5000, 1000), "有護盾就至少半顆");
        assertEquals(10, PlayerStateService.esAbsorption(200, 1000, 800), "照沒被保留的生命算");
    }
}
