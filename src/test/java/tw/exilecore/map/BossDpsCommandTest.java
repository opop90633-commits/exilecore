package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-21：/ec bossdps 的統計（分位數、區間、數字的寫法）。 */
class BossDpsCommandTest {

    @Test
    void percentilesAndBuckets() {
        final List<Double> values = List.of(10_000.0, 50_000.0, 120_000.0, 400_000.0, 2_000_000.0);
        assertEquals(120_000, BossDpsCommand.percentile(values, 0.5), 1e-9);
        assertEquals(2_000_000, BossDpsCommand.percentile(values, 1.0), 1e-9);
        assertEquals(0, BossDpsCommand.percentile(List.of(), 0.5), 1e-9);
        final String buckets = BossDpsCommand.buckets(values);
        assertTrue(buckets.startsWith("<3.0 萬 <white>1</white>"), buckets);
        assertTrue(buckets.contains("100 萬–300 萬 <white>1</white>"), buckets);
        assertEquals("14.2 萬", BossDpsCommand.wan(142_000));
        assertEquals("150 萬", BossDpsCommand.wan(1_500_000));
        assertEquals("1.05 億", BossDpsCommand.wan(105_000_000));
        assertEquals("4,500", BossDpsCommand.wan(4_500));
    }
}
