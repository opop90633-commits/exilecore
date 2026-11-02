package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 2026-11-02：地圖標怪（op mobs）與沒走過的房間也畫。 */
class MinimapMobsTest {

    @Test
    void mobsPacketIsFlatTriples() {
        final MinimapData.Packet packet = MinimapData.parse("{\"v\":1,\"op\":\"mobs\",\"m\":[10,20,0, -5,7,2]}".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, packet.mobs().size());
        assertEquals(new MinimapData.Mob(-5, 7, 2), packet.mobs().get(1));
        assertEquals(0xFFFF77, packet.mobs().get(1).color(), "稀有黃");
        assertEquals(0xFFFFFF, packet.mobs().get(0).color(), "普通白");
    }

    @Test
    void unexploredFloorShowsDimmerWhenShowAll() {
        // 2×1：兩格都是地板（每格 2 位元：01 01 = 0b0101）
        final MinimapData data = new MinimapData(1, 0, 0, 1, 2, 1, new byte[] {0b0101}, java.util.List.of());
        assertEquals(0, data.color(0, 0, false), "沒走過：迷霧");
        assertNotEquals(0, data.color(0, 0, true), "沒走過也畫");
        data.reveal(0.5, 0.5, 0.6);
        assertNotEquals(data.color(0, 0, true), data.color(1, 0, true), "走過的跟沒走過的看得出來");
    }
}
