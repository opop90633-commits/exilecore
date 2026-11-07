package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」：符文保護（PoE2 Runic Ward）的吸收與回復。 */
class RunicWardTest {

    @Test
    void nonLethalDamageIsUntouched() {
        assertArrayEquals(new double[] {50, 0}, RunicWard.absorb(100, 50, 40), 1e-9);
        assertArrayEquals(new double[] {99, 0}, RunicWard.absorb(100, 99, 40), 1e-9, "剩 1 點不算致死");
    }

    @Test
    void lethalHitLeavesOneLifeAndWardTakesTheRest() {
        // 100 生命吃 130：生命扣 99 剩 1，多的 31 由符文保護吃
        assertArrayEquals(new double[] {99, 31}, RunicWard.absorb(100, 130, 40), 1e-9);
        // 剛好吃得下
        assertArrayEquals(new double[] {99, 40}, RunicWard.absorb(100, 139, 40), 1e-9);
        // 剛好扣到 0 也算致死
        assertArrayEquals(new double[] {99, 1}, RunicWard.absorb(100, 100, 40), 1e-9);
    }

    @Test
    void wardThatCannotAbsorbStillDies() {
        assertArrayEquals(new double[] {140, 0}, RunicWard.absorb(100, 140, 40), 1e-9, "吃不下照樣死，符文保護不用");
        assertArrayEquals(new double[] {500, 0}, RunicWard.absorb(100, 500, 0), 1e-9, "沒有符文保護");
    }

    @Test
    void belowOneLifeKeepsCurrentLife() {
        assertArrayEquals(new double[] {0, 5}, RunicWard.absorb(0.5, 5, 40), 1e-9);
    }

    @Test
    void regeneratesFivePercentPerSecond() {
        assertEquals(2, RunicWard.regen(40, 1), 1e-9);
        assertEquals(0.1, RunicWard.regen(40, 0.05), 1e-9);
        assertEquals(0, RunicWard.regen(0, 1), 1e-9);
    }

    @Test
    void playerStateSpendsWardOnlyWhenSaved() {
        final double[] once = RunicWard.absorb(10, 12, 30);
        assertEquals(9, once[0], 1e-9);
        assertEquals(3, once[1], 1e-9);
    }
}
