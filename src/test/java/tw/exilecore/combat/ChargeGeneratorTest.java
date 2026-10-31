package tw.exilecore.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-31：奇術發動機給最高能力值的戰星。 */
class ChargeGeneratorTest {

    @Test
    void highestAttributeDecidesTheCharge() {
        for (final double roll : new double[] {0, 0.3, 0.6, 0.99}) {
            assertEquals("power", KeystoneEffects.highestCharge(26, 163, 194, roll));
            assertEquals("frenzy", KeystoneEffects.highestCharge(26, 200, 194, roll));
            assertEquals("endurance", KeystoneEffects.highestCharge(300, 163, 194, roll));
        }
        assertEquals("endurance", KeystoneEffects.highestCharge(100, 100, 50, 0.1));
        assertEquals("frenzy", KeystoneEffects.highestCharge(100, 100, 50, 0.9));
        assertEquals("power", KeystoneEffects.highestCharge(1, 1, 1, 1.0));
    }
}
