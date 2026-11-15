package tw.exilecore.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-15 照站長「照這樣改」：撲殺門檻照 PoE2 在傷害算進去之前檢查，打完剩下的在門檻以下也照舊。 */
class CullCheckTest {

    @Test
    void thresholdOverHundredCullsFullLifeEvenWithZeroDamage() {
        assertTrue(CombatListener.shouldCull(100, 0, 100, 1.0325), "白怪 35% × 2.95：滿血、0 傷害也撲殺");
    }

    @Test
    void killingBlowOnCullableTargetCountsAsCull() {
        assertTrue(CombatListener.shouldCull(30, 500, 100, 0.35), "擊中前 30%（門檻 35%）：這一下直接打死也算撲殺");
    }

    @Test
    void hitThatLeavesTargetUnderThresholdStillCulls() {
        assertTrue(CombatListener.shouldCull(80, 50, 100, 0.35), "打完剩 30%：照舊撲殺");
    }

    @Test
    void healthyTargetKilledOutrightIsNotACull() {
        assertFalse(CombatListener.shouldCull(80, 500, 100, 0.35), "擊中前 80%、一下打死：一般擊殺");
        assertFalse(CombatListener.shouldCull(80, 10, 100, 0.35), "打完還有 70%：不撲殺");
        assertFalse(CombatListener.shouldCull(100, 0, 100, 0), "沒有門檻");
    }
}
