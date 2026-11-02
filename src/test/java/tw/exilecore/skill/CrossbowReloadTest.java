package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長：弩裝填減半、背景裝填。 */
class CrossbowReloadTest {

    @Test
    void reloadHalvedAndBackgroundRefill() {
        assertEquals(0.5, SkillService.RELOAD_SCALE, 1e-9);
        assertFalse(SkillService.backgroundReloaded(null, 0L, 10, 100), "彈匣滿的不用補");
        assertFalse(SkillService.backgroundReloaded(3, 95L, 10, 100), "剛射過，還沒到裝填時間");
        assertTrue(SkillService.backgroundReloaded(3, 90L, 10, 100), "超過一次裝填時間沒射，補滿");
        assertFalse(SkillService.backgroundReloaded(0, 0L, 10, 100), "空了走一般裝填");
    }
}
