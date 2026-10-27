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

    /** 2026-10-27：單次超過門檻寫一次警告，同區段 30 秒內再超過只計數，下次警告一起報。 */
    @Test
    void spikesWarnOncePerQuietWindow() {
        final Perf perf = new Perf();
        final long[] now = {0};
        perf.clock(() -> now[0]);
        final java.util.List<Perf.Spike> warned = new java.util.ArrayList<>();
        perf.onSpike(20, warned::add);
        perf.record("skill.minions", 5_000_000); // 5 毫秒：不算
        assertTrue(warned.isEmpty());
        perf.record("skill.minions", 45_000_000);
        assertEquals(1, warned.size());
        assertEquals("skill.minions", warned.getFirst().name());
        assertEquals(45.0, warned.getFirst().ms(), 1e-9);
        now[0] += 1_000_000_000L; // 1 秒後又卡兩次：不寫
        perf.record("skill.minions", 30_000_000);
        perf.record("skill.minions", 30_000_000);
        perf.record("stats.rebuild", 25_000_000); // 別的區段照樣寫
        assertEquals(2, warned.size());
        now[0] += Perf.SPIKE_QUIET_NANOS;
        perf.record("skill.minions", 60_000_000);
        assertEquals(3, warned.size());
        assertEquals(2, warned.get(2).suppressed(), "期間略過的兩次一起報");
    }
}
