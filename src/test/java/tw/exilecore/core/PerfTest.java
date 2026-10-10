package tw.exilecore.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /ec perf 的計時：每秒一格，平均成每 tick 的毫秒。 */
class PerfTest {

    @Test
    void averagesPerTickOverTheWindow() {
        final Perf perf = new Perf();
        for (int second = 0; second < 10; second++) {
            for (int call = 0; call < 20; call++) {
                perf.record("map", 2_000_000); // 每次 2 毫秒，每秒 20 次
            }
            perf.record("combat.hit", second == 3 ? 30_000_000 : 1_000_000);
            perf.roll();
        }
        final List<Perf.Stat> stats = perf.stats(10);
        assertEquals("map", stats.getFirst().name(), "花最多時間的排前面");
        assertEquals(2.0, stats.getFirst().msPerTick(), 1e-9, "每 tick 2 毫秒");
        assertEquals(20.0, stats.getFirst().callsPerSecond(), 1e-9);
        final Perf.Stat combat = stats.get(1);
        assertEquals(30.0, combat.maxMs(), 1e-9, "最慢的一次");
        assertEquals((9 * 1.0 + 30.0) / (10 * 20.0), combat.msPerTick(), 1e-9);
    }

    @Test
    void oldSecondsFallOutOfTheWindow() {
        final Perf perf = new Perf();
        perf.record("old", 5_000_000);
        for (int second = 0; second < Perf.WINDOW + 5; second++) {
            perf.roll();
        }
        assertTrue(perf.stats(60).isEmpty(), "60 秒前的資料不算");
        final String value = perf.time("work", () -> "ok");
        assertEquals("ok", value);
        perf.roll();
        assertEquals(1, perf.stats(10).size());
    }
}
