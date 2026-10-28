package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照站長「放技能時一直自動幫我按左鍵」：伺服器揮手（右鍵、數字鍵施放）的回音不能被當成左鍵。
 * 這次之前只擋右鍵之後 3 tick，延遲 300 毫秒時回音第 6 tick 才到，被當成左鍵。
 */
class ServerSwingTest {

    /** 照 SkillInputListener 的判斷：從揮手推出來的左鍵（點空氣、冒險模式點方塊）算不算，然後同一個封包的揮手事件吃掉一筆回音。 */
    private static boolean syntheticLeft(final ServerSwing.Ledger ledger, final long lastRight, final long now) {
        final boolean counted = !ledger.pending(now) && SkillMath.airClick(lastRight, -1, now);
        if (now != lastRight) {
            ledger.consume(now);
        }
        return counted;
    }

    @Test
    void echoOfRightClickCastIsNotALeftClick() {
        final ServerSwing.Ledger ledger = new ServerSwing.Ledger();
        final int window = ServerSwing.window(300);
        // 右鍵在 tick 100 施放、伺服器揮手；300 毫秒延遲，回音在 tick 106 到
        ledger.record(100, window);
        assertTrue(SkillMath.airClick(100, -1, 106), "這次之前：3 tick 的視窗擋不到");
        assertFalse(syntheticLeft(ledger, 100, 106), "回音不算左鍵");
        assertTrue(syntheticLeft(ledger, 100, 130), "之後真的左鍵點空氣照算");
    }

    @Test
    void hotbarCastEchoesAreIgnoredEvenWithoutRightClick() {
        final ServerSwing.Ledger ledger = new ServerSwing.Ledger();
        // 數字鍵施放沒有記右鍵的時間：以前回音一定被當成左鍵
        ledger.record(200, ServerSwing.window(120));
        assertFalse(syntheticLeft(ledger, -1, 203));
        assertTrue(syntheticLeft(ledger, -1, 204), "回音已經回來了，下一下是真的左鍵");
    }

    @Test
    void holdingRightClickKeepsCastingWithoutLeftCasts() {
        final ServerSwing.Ledger ledger = new ServerSwing.Ledger();
        final int window = ServerSwing.window(250);
        int lefts = 0;
        // 按住右鍵：每 4 tick 施放一次；每個回音晚 5 tick 到（右鍵的時間已經過了 3 tick 的視窗）
        for (long t = 0; t < 200; t++) {
            long lastRight = t - t % 4;
            if (t % 4 == 0) {
                ledger.record(t, window);
            }
            if (t >= 5 && (t - 5) % 4 == 0 && syntheticLeft(ledger, lastRight, t)) {
                lefts++;
            }
        }
        assertEquals(0, lefts, "按住右鍵不會一直自動放左鍵");
    }

    @Test
    void queuedLeftEchoLoopIsBroken() {
        // 左鍵排隊放出來時伺服器揮手 → 回音被當成左鍵 → 又排隊 → 又揮手…（這次之前會一直循環）
        final ServerSwing.Ledger ledger = new ServerSwing.Ledger();
        final int window = ServerSwing.window(200);
        long swingAt = 50;
        ledger.record(swingAt, window);
        int loops = 0;
        for (int i = 0; i < 10; i++) {
            final long echoAt = swingAt + 5;
            if (!syntheticLeft(ledger, -1, echoAt)) {
                break;
            }
            loops++;
            swingAt = echoAt + 3;
            ledger.record(swingAt, window);
        }
        assertEquals(0, loops);
    }

    @Test
    void ownSwingOfTheSameRightClickDoesNotEatTheEcho() {
        // 拿斧頭、鋤頭右鍵：客戶端自己揮手（同一個 tick 到），伺服器施放也揮手（回音晚一個來回）
        final ServerSwing.Ledger ledger = new ServerSwing.Ledger();
        ledger.record(100, ServerSwing.window(300));
        assertFalse(syntheticLeft(ledger, 100, 100), "客戶端自己的揮手：右鍵同一個 tick，不吃回音");
        assertFalse(syntheticLeft(ledger, 100, 106), "回音還是被擋下來");
        assertTrue(syntheticLeft(ledger, 100, 110));
    }

    @Test
    void ledgerBounds() {
        final ServerSwing.Ledger ledger = new ServerSwing.Ledger();
        ledger.record(10, 8);
        assertFalse(ledger.pending(10), "跟伺服器揮手同一個 tick 收到的揮手不是回音");
        assertTrue(ledger.pending(11));
        assertTrue(ledger.pending(18));
        assertFalse(ledger.pending(19), "回音沒回來：過了就不等，不會一直吃掉真的左鍵");
        assertFalse(ledger.consume(19));
        for (int i = 0; i < 20; i++) {
            ledger.record(30, 8);
        }
        int eaten = 0;
        while (ledger.consume(31)) {
            eaten++;
        }
        assertEquals(ServerSwing.MAX_PENDING, eaten, "最多等 8 筆");
        assertEquals(ServerSwing.MIN_WINDOW, ServerSwing.window(0));
        assertEquals(6 + ServerSwing.WINDOW_MARGIN, ServerSwing.window(300));
        assertEquals(ServerSwing.MAX_WINDOW, ServerSwing.window(5000));
        assertEquals(ServerSwing.MIN_WINDOW, ServerSwing.window(-1));
    }
}
