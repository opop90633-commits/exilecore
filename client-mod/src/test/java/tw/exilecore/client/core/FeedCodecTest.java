package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（C 組）：預警、小地圖、死亡回顧的封包（跟插件 FeedProtocolTest 同樣的格式）、招呼的旗標。 */
class FeedCodecTest {

    private static byte[] b(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void helloFlags() {
        final byte[] base = Protocol.hello(2, "1.4.0", true, true, true, true);
        assertSame(base, Features.withFlags(base, false, false, false, false), "都關掉原封不動（跟 1.3 一樣）");
        final String text = new String(Features.withFlags(base, true, false, true, true), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"alerts\":true"));
        assertFalse(text.contains("minimap"));
        assertTrue(text.contains("\"death\":true"));
        assertTrue(text.contains("\"tooltip\":true"));
        assertTrue(text.contains("\"orbs\":true"), "原本的欄位留著");
    }

    @Test
    void alertsStackAndExpire() {
        final AlertData board = new AlertData();
        board.apply(AlertData.parse(b("{\"v\":1,\"op\":\"show\",\"id\":\"a\",\"lane\":\"top\",\"p\":1,\"t\":[{\"t\":\"⚠ 隕石\",\"c\":16733525}],\"d\":10}")), 0);
        board.apply(AlertData.parse(b("{\"v\":1,\"op\":\"show\",\"id\":\"b\",\"lane\":\"top\",\"p\":3,\"t\":[{\"t\":\"標記\",\"c\":1}],\"d\":10,\"cd\":40,"
                + "\"at\":[1,64,2],\"k\":\"safe\"}")), 2);
        board.apply(AlertData.parse(b("{\"v\":1,\"op\":\"show\",\"id\":\"s\",\"lane\":\"side\",\"t\":[],\"l\":[[{\"t\":\"站滿光圈\",\"c\":1}]],\"d\":0,\"cd\":200}")), 2);
        final List<AlertData.Shown> top = board.top(3);
        assertEquals(2, top.size(), "同時好幾則");
        assertEquals("b", top.get(0).alert().id(), "優先度高的在前");
        assertTrue(top.get(0).alert().safe());
        assertArrayEquals(new double[] {1, 64, 2}, top.get(0).alert().at());
        assertEquals(39, top.get(0).countdownLeft(3));
        assertEquals(1, board.side(3).size());
        assertEquals("站滿光圈", board.side(3).getFirst().alert().lines().getFirst().getFirst().text());
        // 每 5 tick 重送一次同代號的：不會疊起來，留的時間往後延
        board.apply(AlertData.parse(b("{\"v\":1,\"op\":\"show\",\"id\":\"a\",\"lane\":\"top\",\"p\":1,\"t\":[],\"d\":10}")), 8);
        assertEquals(2, board.top(11).size());
        assertEquals(1, board.top(15).size(), "b 在 12 過期、a 延到 18");
        assertTrue(board.top(18).isEmpty());
        assertEquals(1, board.side(1000).size(), "d=0 一直留著");
        board.apply(AlertData.parse(b("{\"v\":1,\"op\":\"clear\",\"id\":\"*\"}")), 1000);
        assertTrue(board.isEmpty());
        assertNull(AlertData.parse(b("壞掉")));
    }

    @Test
    void topLimit() {
        final AlertData board = new AlertData();
        for (int i = 0; i < 6; i++) {
            board.apply(AlertData.parse(b("{\"op\":\"show\",\"id\":\"x" + i + "\",\"p\":" + (i % 4) + ",\"t\":[],\"d\":100}")), i);
        }
        assertEquals(AlertData.MAX_TOP, board.top(6).size());
    }

    @Test
    void arrow() {
        // 面向南（yaw 0）、安全區在南邊：正前方
        assertEquals(0, AlertData.arrowDegrees(0, 0, 0f, 0, 10, true), 1e-6);
        // 面向南、安全區在西邊（-x）：右手邊 → +90
        assertEquals(90, AlertData.arrowDegrees(0, 0, 0f, -10, 0, true), 1e-6);
        // 危險區在南邊：往外逃 = 背後
        assertEquals(180, Math.abs(AlertData.arrowDegrees(0, 0, 0f, 0, 10, false)), 1e-6);
        // 面向西（yaw 90）、目標在西：正前方
        assertEquals(0, AlertData.arrowDegrees(0, 0, 90f, -10, 0, true), 1e-6);
        assertEquals(-90, AlertData.wrap(270), 1e-9);
    }

    @Test
    void seats() {
        assertArrayEquals(new int[] {0}, AlertData.seats(1));
        assertArrayEquals(new int[] {1, 2, 0}, AlertData.seats(3));
        assertArrayEquals(new int[] {1, 2, 0, 3}, AlertData.seats(4));
    }

    private static String tiles() {
        // 4×2：第一排 地板 地板 牆 液體；第二排 空 地板 地板 地板
        final int[] values = {1, 1, 2, 3, 0, 1, 1, 1};
        final byte[] out = new byte[2];
        for (int i = 0; i < values.length; i++) {
            out[i >> 2] |= (byte) (values[i] << ((i & 3) * 2));
        }
        return Base64.getEncoder().encodeToString(out);
    }

    @Test
    void minimap() {
        final MinimapData.Packet init = MinimapData.parse(b("{\"v\":1,\"op\":\"init\",\"map\":7,\"ox\":100,\"oz\":200,\"y\":64,\"size\":8,\"res\":2,"
                + "\"w\":4,\"h\":2,\"tiles\":\"" + tiles() + "\",\"rooms\":[{\"x\":102,\"z\":201,\"hx\":2,\"hz\":2,\"r\":0,\"k\":\"BOSS\"}],"
                + "\"boss\":{\"x\":105,\"z\":201,\"st\":\"wait\"},\"events\":[{\"i\":0,\"x\":101,\"z\":203,\"k\":\"shrine\",\"n\":\"神龕\",\"c\":1,\"st\":0}]}"));
        assertNotNull(init);
        final MinimapData data = init.data();
        assertEquals(MinimapData.FLOOR, data.tile(0, 0));
        assertEquals(MinimapData.WALL, data.tile(2, 0));
        assertEquals(MinimapData.LIQUID, data.tile(3, 0));
        assertEquals(MinimapData.EMPTY, data.tile(0, 1));
        assertEquals(MinimapData.EMPTY, data.tile(9, 9));
        assertEquals("BOSS", data.bossRoom().kind());
        assertEquals(1.5, data.cellX(103), 1e-9);
        assertTrue(data.contains(101, 201));
        assertFalse(data.contains(150, 201));
        // 迷霧：還沒走過都是透明
        assertEquals(0, data.color(0, 0));
        assertEquals(0, data.explored(), 1e-9);
        assertTrue(data.reveal(101, 201, 3));
        assertFalse(data.reveal(101, 201, 3), "同一個地方再走一次沒有新的");
        assertTrue(data.seen(0, 0));
        assertTrue(data.color(0, 0) != 0);
        assertTrue(data.explored() > 0 && data.explored() < 1);
        // 之後只送變化
        data.apply(MinimapData.parse(b("{\"op\":\"events\",\"events\":[{\"i\":0,\"x\":101,\"z\":203,\"k\":\"shrine\",\"n\":\"神龕\",\"c\":1,\"st\":2}]}")));
        assertTrue(data.events().getFirst().done());
        data.apply(MinimapData.parse(b("{\"op\":\"boss\",\"boss\":{\"x\":106,\"z\":202,\"st\":\"dead\"}}")));
        assertEquals("dead", data.boss().state());
        data.apply(MinimapData.parse(b("{\"op\":\"party\",\"party\":[{\"n\":\"阿明\",\"x\":101,\"z\":202}]}")));
        assertEquals("阿明", data.party().getFirst().name());
        assertEquals("clear", MinimapData.parse(b("{\"op\":\"clear\"}")).op());
        assertNull(MinimapData.parse(b("{\"op\":\"init\",\"w\":0,\"h\":0}")));
    }

    @Test
    void death() {
        final DeathData data = DeathData.parse(b("{\"v\":1,\"lines\":[[{\"t\":\"━━━ \",\"c\":5592405},{\"t\":\"☠ 死亡回顧\",\"c\":16733525},{\"t\":\" ━━━━\",\"c\":1}],"
                + "[{\"t\":\"✖ 穿心 \",\"c\":16733525},{\"t\":\"火焰\",\"c\":1}]]}"));
        assertEquals("☠ 死亡回顧", data.title());
        assertEquals(1, data.body().size());
        assertNull(DeathData.parse(b("{\"lines\":[]}")));
    }
}
