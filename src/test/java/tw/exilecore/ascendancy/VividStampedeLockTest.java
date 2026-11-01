package tw.exilecore.ascendancy;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照 PoE2 Vivid Stampede：每頭靈鹿 4 個落點間隔 1 公尺、朝鎖定的敵人躍去。 */
class VividStampedeLockTest {

    @Test
    void fourLandingsOneMetreApart() {
        assertEquals(4, AscPaths.STAG_LEAPS);
        assertEquals(tw.exilecore.core.Units.metres(1), AscPaths.STAG_LEAP, 1e-9);
    }

    @Test
    void lockDirectionPointsAtTarget() {
        final Vector dir = AscPaths.lockDirection(0, 0, 3, 4, new Vector(1, 0, 0));
        assertEquals(0.6, dir.getX(), 1e-9);
        assertEquals(0.8, dir.getZ(), 1e-9);
        final Vector same = AscPaths.lockDirection(1, 1, 1, 1, new Vector(0, 0, 1));
        assertEquals(1, same.getZ(), 1e-9);
    }
}
