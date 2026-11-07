package tw.exilecore.skill;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「讓我們的召喚物也比較散」：召喚物在目標周圍的站位（MinionService.standOffset）。 */
class MinionSpreadTest {

    @Test
    void rangedFanOutOnTheOwnersSideAtSeventyPercentRange() {
        final Set<Long> spots = new HashSet<>();
        for (long born = 0; born < 40; born++) {
            final Vector offset = MinionService.standOffset(born * 7, true, 12, 0.5, 10, 0); // 主人在目標的 +X 方向
            assertEquals(12 * MinionService.RANGED_STAND, offset.length(), 1e-9, "離目標射程的七成");
            final double degrees = Math.toDegrees(Math.atan2(offset.getZ(), offset.getX()));
            assertTrue(Math.abs(degrees) <= MinionService.RANGED_FAN + 1e-9, "在主人那一側 ±70° 裡：" + degrees);
            spots.add(Math.round(offset.getX() * 2) * 1000 + Math.round(offset.getZ() * 2));
        }
        assertTrue(spots.size() >= 30, "每隻站的位置不一樣：" + spots.size());
    }

    @Test
    void meleeRingStaysWithinReach() {
        double minAngle = 360;
        double maxAngle = -360;
        for (long born = 0; born < 20; born++) {
            final Vector offset = MinionService.standOffset(born, false, 2.4, 1.0, 0, 5);
            assertEquals(1.0 + 2.4 * 0.6, offset.length(), 1e-9, "碰撞箱邊緣外打擊距離的六成：碰得到");
            final double degrees = Math.toDegrees(Math.atan2(offset.getZ(), offset.getX()));
            minAngle = Math.min(minAngle, degrees);
            maxAngle = Math.max(maxAngle, degrees);
        }
        assertTrue(maxAngle - minAngle > 270, "近戰的繞一整圈");
    }

    @Test
    void ownerOnTopOfTheTargetStillGivesASpot() {
        assertEquals(12 * MinionService.RANGED_STAND, MinionService.standOffset(3, true, 12, 0.5, 0, 0).length(), 1e-9);
    }
}
