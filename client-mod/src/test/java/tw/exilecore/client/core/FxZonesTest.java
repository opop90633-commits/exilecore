package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模組畫的持續範圍：START／UPDATE／STOP、到期、上限、清掉。 */
class FxZonesTest {

    private static final FxCodec.Style FLAME = new FxCodec.Style("flame", FxCodec.DATA_NONE, 0, 0, 0, 0, null, 0, 0, 0);

    static FxCodec.Effect zone(final long id, final int phase, final double radius, final int duration, final boolean important) {
        return new FxCodec.Effect(FxCodec.SHAPE_AREA, phase, id, important, phase == FxCodec.PHASE_STOP ? null : FLAME, 10, 64, 10, 10, 64, 10,
                radius, 20, 1, duration, 0, 0.5, 0, 0);
    }

    @Test
    void startUpdateStop() {
        final FxZones zones = new FxZones();
        zones.apply(zone(1, FxCodec.PHASE_START, 3, 200, false), 0);
        zones.apply(zone(2, FxCodec.PHASE_START, 2, -1, true), 0);
        assertEquals(2, zones.size());
        final FxZones.Zone first = zones.get(1);
        first.cache = "地面高度";
        zones.apply(zone(1, FxCodec.PHASE_UPDATE, 5, 150, false), 50);
        assertSame(first, zones.get(1), "UPDATE 改同一個");
        assertEquals(5, zones.get(1).effect().radius());
        assertEquals(0, zones.get(1).started());
        assertEquals(50, zones.get(1).updated());
        assertNull(zones.get(1).cache, "位置／半徑變了：快取清掉");
        assertEquals(50 + 150 + FxZones.GRACE_TICKS, zones.get(1).expires());
        zones.apply(zone(1, FxCodec.PHASE_STOP, 0, 0, false), 60);
        assertNull(zones.get(1));
        zones.apply(zone(1, FxCodec.PHASE_STOP, 0, 0, false), 61); // 重複的 STOP 沒事
        zones.apply(zone(99, FxCodec.PHASE_STOP, 0, 0, false), 61); // 不認得的編號沒事
        assertEquals(1, zones.size());
        // ONCE 不記
        zones.apply(new FxCodec.Effect(FxCodec.SHAPE_BURST, FxCodec.PHASE_ONCE, 0, false, FLAME, 0, 0, 0, 0, 0, 0, 0, 5, 1, 0, 0, 0, 0, 0), 62);
        assertEquals(1, zones.size());
    }

    @Test
    void updateWithoutStartCreates() {
        final FxZones zones = new FxZones();
        zones.apply(zone(5, FxCodec.PHASE_UPDATE, 3, 100, false), 10);
        assertNotNull(zones.get(5), "START 那包比較晚到／掉了：UPDATE 當 START");
    }

    @Test
    void expiresAfterDurationOrSafetyTimeout() {
        final FxZones zones = new FxZones();
        zones.apply(zone(1, FxCodec.PHASE_START, 3, 100, false), 0);
        zones.apply(zone(2, FxCodec.PHASE_START, 3, -1, false), 0);
        assertEquals(0, zones.tick(100 + FxZones.GRACE_TICKS - 1));
        assertEquals(1, zones.tick(100 + FxZones.GRACE_TICKS), "STOP 掉了：時間到 + 寬限自己拿掉");
        assertNull(zones.get(1));
        assertNotNull(zones.get(2), "不知道多久的留著");
        zones.apply(zone(2, FxCodec.PHASE_UPDATE, 4, -1, false), 1000); // UPDATE 重算
        assertEquals(0, zones.tick(FxZones.UNKNOWN_TICKS + 999));
        assertEquals(1, zones.tick(FxZones.UNKNOWN_TICKS + 1000), "不知道多久的：最多留 15 分鐘");
        assertEquals(0, zones.size());
    }

    @Test
    void clockGoingBackwardsDropsZones() {
        final FxZones zones = new FxZones();
        zones.apply(zone(1, FxCodec.PHASE_START, 3, -1, false), 500);
        assertEquals(1, zones.tick(10));
    }

    @Test
    void capKeepsImportant() {
        final FxZones zones = new FxZones();
        zones.apply(zone(0, FxCodec.PHASE_START, 3, -1, true), 0);
        for (int i = 1; i <= FxZones.MAX_ZONES + 10; i++) {
            zones.apply(zone(i, FxCodec.PHASE_START, 3, -1, false), i);
        }
        assertEquals(FxZones.MAX_ZONES, zones.size());
        assertNotNull(zones.get(0), "重要的（最舊）留著，丟最舊的不重要的");
        assertNull(zones.get(1));
        assertNotNull(zones.get(FxZones.MAX_ZONES + 10));
    }

    @Test
    void clearOnDisconnect() {
        final FxZones zones = new FxZones();
        zones.apply(zone(1, FxCodec.PHASE_START, 3, -1, false), 0);
        zones.apply(zone(2, FxCodec.PHASE_START, 3, -1, true), 0);
        assertEquals(2, zones.snapshot().size());
        zones.clear();
        assertEquals(0, zones.size());
        assertTrue(zones.zones().isEmpty());
    }
}
