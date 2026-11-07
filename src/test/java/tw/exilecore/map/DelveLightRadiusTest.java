package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：星脈深坑的回光半徑照每個人的光照範圍放大。 */
class DelveLightRadiusTest {

    @Test
    void lightRadiusScales() {
        assertEquals(12, Delve.lightRadius(12, 0), 1e-9);
        assertEquals(15, Delve.lightRadius(12, 0.25), 1e-9, "增加 25% 光照範圍");
        assertEquals(0, Delve.lightRadius(12, -2), 1e-9, "最少 0");
    }
}
