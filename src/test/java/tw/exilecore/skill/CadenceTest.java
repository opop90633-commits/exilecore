package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.SupportExtraKeys;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照 PoE2 Cadence（連拍）：最近 4 秒內每用過一次攻擊速度更多 8%，4 秒內 6 次鎖 10 秒。 */
class CadenceTest {

    @Test
    void countsOnlyUsesWithinFourSecondsBeforeThisOne() {
        final ArrayDeque<Long> uses = new ArrayDeque<>();
        assertEquals(0, SupportExtras.cadenceRecord(uses, 0), "第一次沒有加成");
        assertEquals(1, SupportExtras.cadenceRecord(uses, 20));
        assertEquals(2, SupportExtras.cadenceRecord(uses, 40));
        assertEquals(1, SupportExtras.cadenceRecord(uses, 20 + SupportExtraKeys.CADENCE_RECENT_TICKS + 1), "4 秒前的那兩次（0、20）不算，只剩 40 那次");
    }

    @Test
    void refNumbers() {
        assertEquals(6, SupportExtraKeys.CADENCE_LIMIT);
        assertEquals(80, SupportExtraKeys.CADENCE_RECENT_TICKS);
        assertEquals(200, SupportExtraKeys.CADENCE_LOCK_TICKS);
    }
}
