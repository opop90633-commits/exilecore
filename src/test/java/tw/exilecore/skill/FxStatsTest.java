package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 技能特效的封包統計與人多時的打薄（{@link FxStats}，2026-10 人多時大家的 PING 一起飆高）。 */
class FxStatsTest {

    private static final UUID A = new UUID(1, 1);
    private static final UUID B = new UUID(2, 2);

    @Test
    void ranksSourcesByPacketsOfTheLastWindow() {
        final FxStats stats = new FxStats();
        stats.add(0, A, 100);
        stats.add(10, B, 300);
        stats.add(20, A, 50);
        stats.add(30, null, 7);
        assertTrue(stats.top(100, 10).isEmpty(), "第一段還沒結算");
        final List<FxStats.Entry> top = stats.top(FxStats.WINDOW_TICKS, 10);
        assertEquals(3, top.size());
        assertEquals(B, top.get(0).source());
        assertEquals(300, top.get(0).packets());
        assertEquals(30.0, top.get(0).perSecond(), 1e-9, "10 秒送 300 個 = 每秒 30 個");
        assertEquals(A, top.get(1).source());
        assertEquals(150, top.get(1).packets());
        assertEquals(FxStats.OTHERS, top.get(2).source(), "不是玩家的技能另外算");
        assertEquals(457, stats.total(FxStats.WINDOW_TICKS));
        assertEquals(1, stats.top(FxStats.WINDOW_TICKS, 1).size(), "只要前幾名");
    }

    @Test
    void anIdleStretchClearsTheOldNumbers() {
        final FxStats stats = new FxStats();
        stats.add(0, A, 100);
        stats.add(FxStats.WINDOW_TICKS, B, 5); // 結算 A 的那段，開始新的一段
        assertEquals(A, stats.top(FxStats.WINDOW_TICKS, 10).get(0).source());
        assertEquals(B, stats.top(2L * FxStats.WINDOW_TICKS, 10).get(0).source(), "下一段只有 B");
        assertTrue(stats.top(10L * FxStats.WINDOW_TICKS, 10).isEmpty(), "很久沒人放技能：沒有舊數字");
    }

    @Test
    void crowdsThinOutOtherPlayersEffects() {
        assertEquals(1, FxStats.stride(0, 10));
        assertEquals(1, FxStats.stride(10, 10), "人不多：每點都送");
        assertEquals(2, FxStats.stride(11, 10));
        assertEquals(4, FxStats.stride(40, 10), "40 個人看：每 4 點送 1 點");
        assertEquals(1, FxStats.stride(50, 0), "關掉打薄");
    }
}
