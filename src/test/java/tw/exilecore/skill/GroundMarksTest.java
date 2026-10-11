package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.web.UiPack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 招式預警平貼在地上的圖怎麼擺（{@link GroundMarks}，2026-10）。 */
class GroundMarksTest {

    /** 四元數 (x, y, z, w) 轉一個向量。 */
    private static double[] rotate(final float[] q, final double[] v) {
        final double qx = q[0];
        final double qy = q[1];
        final double qz = q[2];
        final double qw = q[3];
        // t = 2 × (q.xyz × v)；v' = v + w t + q.xyz × t
        final double tx = 2 * (qy * v[2] - qz * v[1]);
        final double ty = 2 * (qz * v[0] - qx * v[2]);
        final double tz = 2 * (qx * v[1] - qy * v[0]);
        return new double[] {
            v[0] + qw * tx + (qy * tz - qz * ty),
            v[1] + qw * ty + (qz * tx - qx * tz),
            v[2] + qw * tz + (qx * ty - qy * tx)};
    }

    @Test
    void sectorsOpenTowardsTheFacing() {
        final double[][] facings = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {0.6, -0.8}, {-3, 4}};
        for (final double[] facing : facings) {
            final GroundMarks.Placement placement = GroundMarks.sector(facing[0], facing[1], 10, 90, 1);
            final double length = Math.hypot(facing[0], facing[1]);
            final double[] pointing = GroundMarks.pointing(placement.yaw());
            assertEquals(facing[0] / length, pointing[0], 1e-9);
            assertEquals(facing[1] / length, pointing[1], 1e-9);
            // 原版畫物品展示實體時先轉半圈：圖的上方（模型的 -Z）變成 +Z，再用四元數轉到面向的方向
            final double[] turned = rotate(GroundMarks.quaternion(placement.yaw()), new double[] {0, 0, 1});
            assertEquals(facing[0] / length, turned[0], 1e-6, "四元數跟角度轉的是同一個方向");
            assertEquals(0, turned[1], 1e-6);
            assertEquals(facing[1] / length, turned[2], 1e-6);
            assertEquals(20, placement.scaleX(), 1e-9, "圖的半徑是半張圖：放大成 2 × 長度");
            assertEquals(0, placement.offsetX(), 1e-9, "頂點在實體的位置");
        }
    }

    @Test
    void fillGrowsFromTheMiddleOrTheStart() {
        assertEquals(5, GroundMarks.disc(5, 0.5).scaleX(), 1e-9, "半徑 5 的圓長到一半：直徑 5");
        assertEquals(GroundMarks.MIN_SCALE, GroundMarks.disc(5, 0).scaleX(), 1e-9, "剛開始：很小但不是 0");
        assertEquals(10, GroundMarks.disc(5, 3).scaleX(), 1e-9, "超過 1 照 1");
        final GroundMarks.Placement strip = GroundMarks.strip(0, 2, 12, 1.5, 0.25);
        assertEquals("rect", strip.model());
        assertEquals(3, strip.scaleX(), 1e-9, "寬 = 2 × 半寬");
        assertEquals(3, strip.scaleZ(), 1e-9, "長 12 長到四分之一");
        assertEquals(0, strip.offsetX(), 1e-9);
        assertEquals(1.5, strip.offsetZ(), 1e-9, "中心在起點往前長度的一半");
        final double[] pointing = GroundMarks.pointing(strip.yaw());
        assertEquals(0, pointing[0], 1e-9);
        assertEquals(1, pointing[1], 1e-9);
        // 2026-10：外框照大小分三種粗細（_b0 直徑 ≤ 10、_b1 ≤ 22、_b2 更大）
        assertEquals("ring_b2", GroundMarks.ring(20).model());
        assertEquals("ring_b0", GroundMarks.ring(4).model());
        assertEquals("disc_b1", GroundMarks.disc(8, 0.5).model(), "粗細照整個範圍算，不是長到哪裡");
        assertEquals(40, GroundMarks.ring(20).scaleX(), 1e-9);
    }

    @Test
    void sectorPicturesComeInFiveDegreeSteps() {
        assertEquals("sector_35", UiPack.markSector(37));
        assertEquals("sector_40", UiPack.markSector(38));
        assertEquals("sector_5", UiPack.markSector(1), "至少一格");
        assertEquals("sector_300", UiPack.markSector(300));
        assertEquals("disc", UiPack.markSector(358), "差不多一整圈：用圓");
        assertEquals("disc", UiPack.markSector(720));
        for (int degrees = 5; degrees < 360; degrees += 5) {
            assertTrue(UiPack.markNames().contains(UiPack.markSector(degrees)), "每種角度都有圖：" + degrees);
            for (int bucket = 0; bucket < UiPack.MARK_STROKES.length; bucket++) {
                assertTrue(UiPack.markNames().contains(UiPack.markSector(degrees) + "_b" + bucket), "每種粗細都有圖：" + degrees);
            }
        }
        assertEquals((3 + 71) + (2 + 71) * 3, UiPack.markNames().size(), "圓、外框、長條、71 張扇形；圓、外框、扇形另外各三種外框粗細");
    }
}
