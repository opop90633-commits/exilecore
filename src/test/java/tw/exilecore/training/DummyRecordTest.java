package tw.exilecore.training;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 打訓練假人的實測紀錄（2026-10）。 */
class DummyRecordTest {

    @Test
    void roundTripsThroughProgress() {
        final DummyRecord record = new DummyRecord(452_463, 9.5, "訓練假人・無防禦", 86, 1_791_661_000_000L);
        final DummyRecord back = DummyRecord.decode(record.encode());
        assertEquals(record, back);
        // 名字裡有分隔符號也讀得回來
        assertEquals("a|b", DummyRecord.decode(new DummyRecord(10, 8, "a|b", 0, 5).encode()).dummy());
    }

    @Test
    void badOrMissingTextIsNoRecord() {
        assertNull(DummyRecord.decode(null));
        assertNull(DummyRecord.decode(""));
        assertNull(DummyRecord.decode("abc"));
        assertNull(DummyRecord.decode("x|9.5|0|1|假人"));
        assertNull(DummyRecord.decode("0|9.5|0|1|假人"));
    }

    @Test
    void shortBurstsDoNotCount() {
        assertFalse(DummyRecord.qualifies(3.0, 10));
        assertFalse(DummyRecord.qualifies(9.5, 2));
        assertTrue(DummyRecord.qualifies(9.5, 5));
    }

    @Test
    void keepsTheBestUnlessItIsStale() {
        final long day = 24L * 60 * 60 * 1000;
        final DummyRecord old = new DummyRecord(1000, 10, "假人", 0, 0);
        assertTrue(new DummyRecord(1200, 10, "假人", 0, day).replaces(old));
        assertFalse(new DummyRecord(800, 10, "假人", 0, day).replaces(old));
        assertTrue(new DummyRecord(800, 10, "假人", 0, 4 * day).replaces(old));
        assertTrue(new DummyRecord(800, 10, "假人", 0, day).replaces(null));
    }
}
