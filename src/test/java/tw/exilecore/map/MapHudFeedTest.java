package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-02（模組 1.4 B 組）：地圖資訊框的小工具。 */
class MapHudFeedTest {

    @Test
    void colorTags() {
        assertEquals(0x9FE8C8, MapHudFeed.color("<#9fe8c8>", 0));
        assertEquals(0x9FE8C8, MapHudFeed.color("#9fe8c8", 0));
        assertEquals(0xFFAA00, MapHudFeed.color("gold", 0));
        assertEquals(0x123456, MapHudFeed.color("", 0x123456));
        assertEquals(0x123456, MapHudFeed.color(null, 0x123456));
    }

    @Test
    void percent() {
        assertEquals(0, MapEvents.percent(-1));
        assertEquals(50, MapEvents.percent(0.5));
        assertEquals(100, MapEvents.percent(3));
    }
}
