package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentState;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 蓄力放開（完美時機、疊層）與持續減益（2026-10 PoE2 化）。 */
class ChargeSpecTest {

    @Test
    void perfectWindowAroundTheCue() {
        final ChargeSpec snipe = new ChargeSpec(100, 0, 0, 0.25, 0, 0);
        assertEquals(20, snipe.perfectTick(20));
        assertTrue(snipe.isPerfect(20, 20));
        assertTrue(snipe.isPerfect(18, 20));
        assertTrue(snipe.isPerfect(26, 20));
        assertFalse(snipe.isPerfect(17, 20));
        assertFalse(snipe.isPerfect(27, 20));
        assertEquals(6, snipe.perfectTick(2), "太快也至少 6 tick，人反應得過來");
    }

    @Test
    void stagesFollowSpeed() {
        final ChargeSpec flame = new ChargeSpec(0, 0, 10, 0.25, 75, 12);
        assertEquals(1, flame.stagesAt(0, 1.0));
        assertEquals(2, flame.stagesAt(5, 1.0));
        assertEquals(10, flame.stagesAt(500, 1.0));
        assertEquals(3, flame.stagesAt(5, 2.0), "速度兩倍，每層 2.5 tick");
        assertFalse(flame.perfect());
    }

    @Test
    void debuffKeepsTheStrongerOne() {
        final AilmentState state = new AilmentState();
        final UUID a = UUID.randomUUID();
        state.addDebuff("contagion", new AilmentState.Debuff(100, 100, a, true, 0, 100));
        state.addDebuff("contagion", new AilmentState.Debuff(50, 200, a, true, 0, 50));
        assertEquals(100, state.debuffDps(0), 1e-9);
        assertEquals(100, state.debuffDps(150), 1e-9, "弱的那個比較長：留強的、延長到比較晚的");
        assertEquals(0, state.debuffDps(200), 1e-9);
    }
}
