package tw.exilecore.clientmod;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（模組 1.4 B 組）：挑附近的怪、只送有變化的。 */
class MobBarFeedTest {

    private static WorldHudProtocol.MobBar bar(final int id, final int life) {
        return new WorldHudProtocol.MobBar(id, life, -1, WorldHudProtocol.RARITY_NORMAL, false);
    }

    @Test
    void selectBossFirstThenNearest() {
        final List<MobBarFeed.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            candidates.add(new MobBarFeed.Candidate(bar(i, 255), i * i));
        }
        candidates.add(new MobBarFeed.Candidate(new WorldHudProtocol.MobBar(999, 255, -1, WorldHudProtocol.RARITY_UNIQUE, true), 27 * 27));
        candidates.add(new MobBarFeed.Candidate(bar(1000, 255), 40 * 40)); // 範圍外
        final List<WorldHudProtocol.MobBar> picked = MobBarFeed.select(candidates, 28, 10);
        assertEquals(10, picked.size());
        assertEquals(999, picked.getFirst().entityId(), "Boss 一定列在最前面");
        assertEquals(0, picked.get(1).entityId());
        assertEquals(8, picked.getLast().entityId());
        assertTrue(MobBarFeed.select(candidates, 28, 100).stream().noneMatch(b -> b.entityId() == 1000));
    }

    @Test
    void trackerSendsOnlyChanges() {
        final MobBarFeed.Tracker tracker = new MobBarFeed.Tracker();
        final byte[] first = tracker.next(List.of(bar(1, 255), bar(2, 255)));
        final WorldHudProtocol.Mobs full = WorldHudProtocol.decodeMobs(first);
        assertNotNull(full);
        assertTrue(full.full());
        assertEquals(2, full.bars().size());
        assertNull(tracker.next(List.of(bar(1, 255), bar(2, 255))), "沒變不送");
        assertNull(tracker.next(List.of(bar(1, 254), bar(2, 255))), "掉一點點（持續傷害）不送");
        final WorldHudProtocol.Mobs hit = WorldHudProtocol.decodeMobs(tracker.next(List.of(bar(1, 200), bar(3, 255))));
        assertNotNull(hit);
        assertFalse(hit.full());
        assertEquals(List.of(1, 3), hit.bars().stream().map(WorldHudProtocol.MobBar::entityId).toList());
        assertEquals(List.of(2), hit.removed());
        assertEquals(2, tracker.size());
        // 無敵、詛咒這類狀態變了也要送
        final WorldHudProtocol.MobBar cursed = new WorldHudProtocol.MobBar(1, 200, -1, 0, false, false, WorldHudProtocol.MARK_CURSED);
        assertNotNull(tracker.next(List.of(cursed, bar(3, 255))));
        tracker.reset();
        assertTrue(WorldHudProtocol.decodeMobs(tracker.next(List.of(bar(3, 255)))).full(), "換世界後送完整的");
    }

    @Test
    void changedRules() {
        assertTrue(MobBarFeed.changed(null, bar(1, 255)));
        assertTrue(MobBarFeed.changed(bar(1, 1), bar(1, 0)), "死了（0）一定送");
        assertTrue(MobBarFeed.changed(bar(1, 254), bar(1, 255)), "回滿一定送");
        assertFalse(MobBarFeed.changed(bar(1, 100), bar(1, 101)));
        final WorldHudProtocol.MobBar shielded = new WorldHudProtocol.MobBar(1, 100, 50, 0, false);
        assertTrue(MobBarFeed.changed(bar(1, 100), shielded), "護盾出現");
        assertTrue(MobBarFeed.changed(shielded, new WorldHudProtocol.MobBar(1, 100, 40, 0, false)));
        assertTrue(MobBarFeed.changed(bar(1, 100), new WorldHudProtocol.MobBar(1, 100, -1, 0, false, true, 0)), "開始無敵");
    }
}
