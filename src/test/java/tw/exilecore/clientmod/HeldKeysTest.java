package tw.exilecore.clientmod;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模組按住的技能鍵：照原版滑鼠按住的頻率重複。 */
class HeldKeysTest {

    @Test
    void leftEveryTickRightEveryFour() {
        final HeldKeys keys = new HeldKeys(10, 4);
        assertTrue(keys.press(0, 100));
        assertFalse(keys.press(0, 101), "已經按著");
        assertTrue(keys.press(1, 100));
        assertTrue(keys.press(5, 100), "數字鍵格按下照樣放一次");
        assertEquals(List.of(), keys.repeats(100), "按下那個 tick 已經放過");
        assertEquals(List.of(0), keys.repeats(101));
        assertEquals(List.of(0), keys.repeats(103));
        assertEquals(List.of(0, 1), keys.repeats(104), "右鍵每 4 tick");
        keys.release(0);
        assertEquals(List.of(1), keys.repeats(108));
        assertFalse(keys.held(0));
        assertTrue(keys.held(5));
        assertTrue(keys.press(0, 109), "放開後可以再按");
    }

    @Test
    void stuckKeysTimeOut() {
        final HeldKeys keys = new HeldKeys(10, 4);
        keys.press(2, 0);
        assertEquals(List.of(), keys.repeats(HeldKeys.MAX_HOLD_TICKS + 1));
        assertFalse(keys.held(2), "放開的封包掉了：最多按住 2 分鐘");
        assertFalse(keys.press(10, 0));
        assertFalse(keys.press(-1, 0));
        keys.press(3, 0);
        keys.clear();
        assertFalse(keys.held(3));
    }
}
