package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（1.4 B 組）：傷害數字的合併、壽命、動畫。 */
class FloatingNumbersTest {

    private static FxCodec.Number hit(final long value, final int rgb, final boolean crit, final int target) {
        return new FxCodec.Number(0, 64, 0, value, rgb, crit, false, false, null, target);
    }

    @Test
    void noCapAndExpire() {
        final FloatingNumbers numbers = new FloatingNumbers();
        for (int i = 0; i < 100; i++) {
            numbers.add(hit(10, 0xFFFFFF, false, 1), false);
        }
        assertEquals(100, numbers.size(), "沒有 16 個的上限");
        for (int i = 0; i < FloatingNumbers.LIFETIME; i++) {
            numbers.tick();
        }
        assertEquals(0, numbers.size());
    }

    @Test
    void mergeSameTarget() {
        final FloatingNumbers numbers = new FloatingNumbers();
        numbers.add(hit(100, 0xECECEC, false, 7), true);
        numbers.tick();
        final FloatingNumbers.Floater merged = numbers.add(hit(300, 0xFF8040, true, 7), true);
        assertEquals(1, numbers.size());
        assertEquals(400, merged.value);
        assertEquals(0xFF8040, merged.rgb, "顏色照最大的一下");
        assertTrue(merged.emphasis);
        numbers.add(hit(5, 0xFFFFFF, false, 8), true);
        assertEquals(2, numbers.size(), "別的目標不合併");
        numbers.add(new FxCodec.Number(0, 64, 0, 50, 0x7FC8FF, false, false, true, null, 7), true);
        assertEquals(3, numbers.size(), "護盾吃掉的分開");
        for (int i = 0; i < FloatingNumbers.MERGE_WINDOW; i++) {
            numbers.tick();
        }
        numbers.add(hit(1, 0xFFFFFF, false, 7), true);
        assertEquals(4, numbers.size(), "太久了不合併");
        final FloatingNumbers off = new FloatingNumbers();
        off.add(hit(1, 0, false, 7), false);
        off.add(hit(1, 0, false, 7), false);
        assertEquals(2, off.size(), "沒開合併");
    }

    @Test
    void labelsAndAnimation() {
        final FloatingNumbers numbers = new FloatingNumbers();
        assertEquals("1,234!", numbers.add(hit(1234, 0, true, 1), false).label());
        assertEquals("-56 火焰", numbers.add(new FxCodec.Number(0, 0, 0, 56, 0, false, true, false, "火焰", -1), false).label());
        assertEquals("閃避", numbers.add(new FxCodec.Number(0, 0, 0, -1, 0, false, false, false, "閃避", 1), false).label());
        assertEquals("12.3k", FloatingNumbers.format(12_345));
        assertEquals(0, FloatingNumbers.rise(0), 1e-9);
        assertEquals(1, FloatingNumbers.rise(1), 1e-9);
        assertTrue(FloatingNumbers.rise(0.5) > 0.5, "先快後慢");
        assertEquals(1f, FloatingNumbers.alpha(0.5f));
        assertEquals(0f, FloatingNumbers.alpha(1f));
        final FloatingNumbers.Floater crit = numbers.add(hit(1, 0, true, 2), false);
        final FloatingNumbers.Floater normal = numbers.add(hit(1, 0, false, 3), false);
        assertTrue(FloatingNumbers.scale(crit, 0) > FloatingNumbers.scale(normal, 0), "暴擊放大");
    }
}
