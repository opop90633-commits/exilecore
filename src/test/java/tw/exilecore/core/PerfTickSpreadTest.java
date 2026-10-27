package tw.exilecore.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27 效能：擊殺爆炸每 tick 上限（TickCapQueue）、攤開檢查（Spread）。 */
class PerfTickSpreadTest {

    @Test
    void explosionsAreCappedPerTickAndAllEventuallyRun() {
        final TickCapQueue<Integer> queue = new TickCapQueue<>(20);
        assertTrue(queue.add(0), "空的時候排第一件：呼叫的人要排下一個 tick");
        for (int i = 1; i < 50; i++) {
            assertFalse(queue.add(i));
        }
        final List<Integer> done = new ArrayList<>();
        assertTrue(queue.drain(done::add), "第一個 tick 做不完");
        assertEquals(20, done.size(), "每 tick 最多 20 次");
        assertTrue(queue.drain(done::add));
        assertFalse(queue.drain(done::add), "第三個 tick 做完");
        assertEquals(IntStream.range(0, 50).boxed().toList(), done, "每一件都做到、順序不變");
    }

    @Test
    void chainedExplosionsQueuedDuringDrainWaitForNextTick() {
        final TickCapQueue<Integer> queue = new TickCapQueue<>(20);
        queue.add(1);
        final List<Integer> done = new ArrayList<>();
        final boolean more = queue.drain(item -> {
            done.add(item);
            queue.add(item + 1); // 炸死的怪再炸：排到下一個 tick（跟以前一樣不遞迴）
        });
        assertEquals(List.of(1), done);
        assertTrue(more);
        assertEquals(1, queue.size());
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        final TickCapQueue<Integer> queue = new TickCapQueue<>(5);
        for (int i = 0; i < 3; i++) {
            queue.add(i);
        }
        final List<Integer> done = new ArrayList<>();
        queue.drain(item -> {
            if (item == 1) {
                throw new IllegalStateException("boom");
            }
            done.add(item);
        });
        assertEquals(List.of(0, 2), done);
        assertTrue(queue.takeError() instanceof IllegalStateException);
        assertNull(queue.takeError(), "拿了就清掉");
    }

    @Test
    void spreadVisitsEveryItemOncePerRound() {
        final Spread<Integer> spread = new Spread<>(10);
        assertTrue(spread.due());
        spread.refill(IntStream.range(0, 95).boxed().toList());
        final List<Integer> seen = new ArrayList<>();
        int calls = 0;
        while (!spread.due()) {
            final List<Integer> slice = spread.next();
            assertTrue(slice.size() <= 10, "一次最多 ceil(95/10)");
            seen.addAll(slice);
            calls++;
        }
        assertEquals(10, calls, "一輪 10 次");
        assertEquals(IntStream.range(0, 95).boxed().toList(), seen, "每個剛好一次");
        assertEquals(0, spread.remaining());
    }
}
