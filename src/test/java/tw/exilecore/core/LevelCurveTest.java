package tw.exilecore.core;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.LevelCurve;
import tw.exilecore.core.config.LevelCurve.Mode;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelCurveTest {

    @Test
    void betweenIsGeometricForPositiveEndsAndLinearOtherwise() {
        assertEquals(20.0, LevelCurve.between(10, 40, 0.5, Mode.GEOMETRIC), 1e-9, "10 與 40 的等比中間是 20");
        assertEquals(25.0, LevelCurve.between(10, 40, 0.5, Mode.LINEAR), 1e-9, "線性是 25");
        assertEquals(10.0, LevelCurve.between(10, 40, 0), 1e-9);
        assertEquals(40.0, LevelCurve.between(10, 40, 1), 1e-9);
        assertEquals(5.0, LevelCurve.between(0, 10, 0.5, Mode.GEOMETRIC), 1e-9, "有 0 用線性");
        assertEquals(0.0, LevelCurve.between(-2, 2, 0.5, Mode.GEOMETRIC), 1e-9, "有負數用線性");
    }

    @Test
    void tableInterpolatesBetweenAnchorsAndClampsOutside() {
        final NavigableMap<Integer, Double> table = new TreeMap<>(Map.of(1, 20.0, 11, 200.0, 21, 400.0));
        assertEquals(20.0, LevelCurve.at(table, 1, 0, Mode.GEOMETRIC), 1e-9);
        assertEquals(20.0, LevelCurve.at(table, 0, 0, Mode.GEOMETRIC), 1e-9, "表的前面取第一個");
        assertEquals(400.0, LevelCurve.at(table, 99, 0, Mode.GEOMETRIC), 1e-9, "表的後面取最後一個");
        assertEquals(Math.sqrt(20 * 200.0), LevelCurve.at(table, 6, 0, Mode.GEOMETRIC), 1e-9);
        assertEquals(110.0, LevelCurve.at(table, 6, 0, Mode.LINEAR), 1e-9);
        // 等比：每一級的成長比例在錨點之間都一樣
        final double ratio = LevelCurve.at(table, 2, 0, Mode.GEOMETRIC) / LevelCurve.at(table, 1, 0, Mode.GEOMETRIC);
        for (int level = 2; level < 11; level++) {
            assertEquals(ratio, LevelCurve.at(table, level + 1, 0, Mode.GEOMETRIC) / LevelCurve.at(table, level, 0, Mode.GEOMETRIC), 1e-9);
        }
        assertEquals(7.0, LevelCurve.at(new TreeMap<>(), 5, 7, Mode.GEOMETRIC), 1e-9, "空表用 fallback");
    }

    @Test
    void longAndRangeTables() {
        final NavigableMap<Integer, Long> costs = new TreeMap<>(Map.of(20, 60L, 40, 400L, 60, 1500L));
        assertEquals(230, LevelCurve.atLong(costs, 30, 0, Mode.LINEAR));
        assertEquals(155, LevelCurve.atLong(costs, 30, 0, Mode.GEOMETRIC));
        assertEquals(60, LevelCurve.atLong(costs, 10, 0, Mode.LINEAR));
        assertEquals(1500, LevelCurve.atLong(costs, 70, 0, Mode.LINEAR));
        final NavigableMap<Integer, double[]> damage = new TreeMap<>(Map.of(1, new double[] {2, 4}, 11, new double[] {8, 16}));
        assertArrayEquals(new double[] {4, 8}, LevelCurve.range(damage, 6, new double[] {0, 0}, Mode.GEOMETRIC), 1e-9);
        final NavigableMap<Integer, int[]> gold = new TreeMap<>(Map.of(1, new int[] {2, 6}, 11, new int[] {8, 24}));
        assertArrayEquals(new int[] {5, 15}, LevelCurve.intRange(gold, 6, new int[] {0, 0}, Mode.LINEAR));
        assertArrayEquals(new int[] {4, 12}, LevelCurve.intRange(gold, 6, new int[] {0, 0}, Mode.GEOMETRIC));
        // 回傳的是複本，改了不會動到表
        LevelCurve.range(damage, 1, new double[] {0, 0}, Mode.GEOMETRIC)[0] = 999;
        assertEquals(2.0, damage.get(1)[0], 1e-9);
    }
}
