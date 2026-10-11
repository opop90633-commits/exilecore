package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 加劇的流血（猩紅突襲）不會對移動中的目標再乘一次移動倍率（2026-10）。 */
class AggravatedBleedTest {

    @Test
    void normalBleedDoublesOnlyWhileTheTargetMoves() {
        final AilmentState.Ignite bleed = new AilmentState.Ignite(100, 200, null);
        assertEquals(1.0, AilmentState.bleedFactor(bleed, false, 2.0), 1e-9);
        assertEquals(2.0, AilmentState.bleedFactor(bleed, true, 2.0), 1e-9);
    }

    @Test
    void aggravatedBleedIsNeverDoubledAgain() {
        final AilmentState.Ignite bleed = new AilmentState.Ignite(300, 200, null, true);
        assertEquals(1.0, AilmentState.bleedFactor(bleed, false, 2.0), 1e-9);
        assertEquals(1.0, AilmentState.bleedFactor(bleed, true, 2.0), 1e-9);
    }

    @Test
    void stateRemembersWhichBleedIsAggravated() {
        final AilmentState state = new AilmentState();
        state.addBleed(100, 200, null);
        state.addBleed(300, 200, null, true);
        assertTrue(state.bleed(0).aggravated());
        assertEquals(300, state.bleed(0).dps(), 1e-9);
    }
}
