package tw.exilecore.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-23：攻擊命中率隨距離衰減（照 PoE2）。 */
class AccuracyFalloffTest {

    @Test
    void closeRangeKeepsFullAccuracy() {
        assertEquals(0, Formulas.accuracyFalloff(0));
        assertEquals(0, Formulas.accuracyFalloff(Formulas.ACCURACY_FALLOFF_START));
    }

    @Test
    void fallsLinearlyThenCaps() {
        final double mid = (Formulas.ACCURACY_FALLOFF_START + Formulas.ACCURACY_FALLOFF_END) / 2.0;
        assertEquals(Formulas.ACCURACY_FALLOFF_MAX / 2, Formulas.accuracyFalloff(mid), 1e-9);
        assertEquals(Formulas.ACCURACY_FALLOFF_MAX, Formulas.accuracyFalloff(Formulas.ACCURACY_FALLOFF_END), 1e-9);
        assertEquals(Formulas.ACCURACY_FALLOFF_MAX, Formulas.accuracyFalloff(100), 1e-9);
        assertTrue(Formulas.accuracyFalloff(5) < Formulas.accuracyFalloff(6));
    }
}
