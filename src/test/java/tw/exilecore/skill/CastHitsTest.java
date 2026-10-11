package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 面板每秒傷害：放一次打幾下的量（2026-10，以前只算第一下）。 */
class CastHitsTest {

    @Test
    void plainSkillIsOneHit() {
        assertEquals(1.0, SkillService.castHits(1, false, 0), 1e-9);
        assertEquals(1.0, SkillService.castHits(0, false, 60), 1e-9);
    }

    @Test
    void everyBurstVolleyCounts() {
        assertEquals(4.0, SkillService.castHits(4, false, 0), 1e-9);
    }

    @Test
    void aftershockAddsItsOwnBiggerHit() {
        // 千鈞砸：餘震更多 60% → 一下＋1.6 下
        assertEquals(2.6, SkillService.castHits(1, true, 60), 1e-9);
        // 地動：餘震更多 300%
        assertEquals(5.0, SkillService.castHits(1, true, 300), 1e-9);
    }
}
