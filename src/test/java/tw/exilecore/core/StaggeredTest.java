package tw.exilecore.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaggeredTest {

    @Test
    void everyoneOncePerRoundSpreadOut() {
        final List<UUID> online = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            online.add(UUID.randomUUID());
        }
        final Staggered staggered = new Staggered(300);
        final Map<UUID, Integer> done = new HashMap<>();
        int biggest = 0;
        for (int call = 0; call < 300; call++) {
            final List<UUID> batch = staggered.next(online);
            assertEquals(call == 0, staggered.newRound());
            biggest = Math.max(biggest, batch.size());
            batch.forEach(id -> done.merge(id, 1, Integer::sum));
        }
        assertEquals(100, done.size(), "一輪每個人都做到");
        assertTrue(done.values().stream().allMatch(n -> n == 1), "一輪只做一次");
        assertEquals(1, biggest, "100 人分 300 次：每次最多一個");
        staggered.next(online);
        assertTrue(staggered.newRound(), "次數到了才開新的一輪");
    }

    @Test
    void lateJoinerStillInThisRound() {
        final List<UUID> online = new ArrayList<>(List.of(UUID.randomUUID()));
        final Staggered staggered = new Staggered(10);
        int seen = staggered.next(online).size();
        final UUID late = UUID.randomUUID();
        online.add(late);
        boolean lateDone = false;
        for (int call = 1; call < 10; call++) {
            final List<UUID> batch = staggered.next(online);
            seen += batch.size();
            lateDone |= batch.contains(late);
        }
        assertTrue(lateDone, "中途上線的這一輪也做到");
        assertEquals(2, seen);
    }

    @Test
    void lastCallFinishesTheRound() {
        final List<UUID> online = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            online.add(UUID.randomUUID());
        }
        final Staggered staggered = new Staggered(3);
        int total = 0;
        for (int call = 0; call < 3; call++) {
            total += staggered.next(online).size();
        }
        assertEquals(7, total);
    }
}
