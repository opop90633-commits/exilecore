package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照 PoE2（雷爆藥劑：感電效果更多 50%）：更多的感電效果也算進感電幅度。 */
class ShockMoreEffectTest {

    @Test
    void moreAndIncreasedMultiply() {
        assertEquals(0.5, AilmentService.combinedIncrease(0, 1.5), 1e-9);
        assertEquals(1.25, AilmentService.combinedIncrease(0.5, 1.5), 1e-9);
        assertEquals(0.3, AilmentService.combinedIncrease(0.3, 1.0), 1e-9);
        assertEquals(-1.0, AilmentService.combinedIncrease(-2, 1.5), 1e-9);
    }
}
