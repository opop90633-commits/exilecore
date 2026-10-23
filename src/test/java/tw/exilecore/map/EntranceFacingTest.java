package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-23：進圖時面向主路第一段走道（以前一律朝東，常常一進來就對著牆）。 */
class EntranceFacingTest {

    @Test
    void yawFollowsMinecraftConvention() {
        assertEquals(-90f, MapLayout.yaw(1, 0), 1e-4);
        assertEquals(0f, MapLayout.yaw(0, 1), 1e-4);
        assertEquals(90f, MapLayout.yaw(-1, 0), 1e-4);
        assertEquals(180f, Math.abs(MapLayout.yaw(0, -1)), 1e-4);
    }

    @Test
    void playersFaceTheMainPathNotAWall() {
        for (final MapLayout.Style style : MapLayout.Style.values()) {
            for (long seed = 1; seed <= 40; seed++) {
                final MapLayout layout = MapLayout.generate(seed * 7919, 6 + (int) (seed % 6), style);
                final float yaw = layout.entranceYaw();
                final double dx = -Math.sin(Math.toRadians(yaw));
                final double dz = Math.cos(Math.toRadians(yaw));
                final MapLayout.Spot start = new MapLayout.Spot(layout.start().cx(), layout.start().cz());
                // 從起點房間中心往面向的方向一路走出房間（房間半徑再多 2 格）都還是地板：是走道的方向，不是牆
                final int reach = Math.max(layout.start().halfX(), layout.start().halfZ()) + 2;
                for (int step = 1; step <= reach; step++) {
                    final int x = (int) Math.round(start.x() + dx * step);
                    final int z = (int) Math.round(start.z() + dz * step);
                    assertTrue(layout.isFloor(x, z), style + " seed " + seed + " 面向 " + yaw + " 第 " + step + " 格是牆");
                }
            }
        }
    }
}
