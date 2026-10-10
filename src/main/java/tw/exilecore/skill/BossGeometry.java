package tw.exilecore.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss 特殊招式的幾何（光束、擴散環、安全區）：純函式，不依賴 Bukkit，單元測試直接算。
 * 角度一律是弧度、從 +X 軸往 +Z 量（跟 {@code Math.atan2(dz, dx)} 一樣）；水平面上的點用 (x, z)。
 */
public final class BossGeometry {

    private BossGeometry() {
    }

    /** 方向角（弧度）：從 (ox, oz) 看 (px, pz)。 */
    public static double angleTo(final double ox, final double oz, final double px, final double pz) {
        return Math.atan2(pz - oz, px - ox);
    }

    /** 兩個角度差多少（0 到 π）。 */
    public static double angleDifference(final double a, final double b) {
        final double diff = Math.abs(Math.IEEEremainder(a - b, Math.PI * 2));
        return Math.min(diff, Math.PI * 2 - diff);
    }

    /**
     * 光束掃射的進度：第 index 道光束在 progress（0 → 1）時的角度。
     * count 道光束平均分一圈（2 道 = 一前一後、4 道 = 十字），整組從 start 往 sign 的方向轉 sweep。
     */
    public static double beamAngle(final double start, final int index, final int count, final double sweep, final int sign,
                                   final double progress) {
        final double spacing = Math.PI * 2 / Math.max(1, count);
        return start + spacing * index + sign * sweep * Math.max(0, Math.min(1, progress));
    }

    /**
     * 這個點在不在光束裡：從 (ox, oz) 朝 angle 射出、長 length、左右各 halfWidth（再加 padding：目標的半個身寬）。
     */
    public static boolean inBeam(final double ox, final double oz, final double angle, final double length, final double halfWidth,
                                 final double px, final double pz, final double padding) {
        final double fx = Math.cos(angle);
        final double fz = Math.sin(angle);
        final double dx = px - ox;
        final double dz = pz - oz;
        final double along = dx * fx + dz * fz;
        final double side = Math.abs(dx * fz - dz * fx);
        return along >= -padding && along <= length + padding && side <= halfWidth + padding;
    }

    /** 擴散環現在打得到這個距離嗎：環的半徑 radius、厚度左右各 halfWidth。 */
    public static boolean onRing(final double distance, final double radius, final double halfWidth) {
        return Math.abs(distance - radius) <= halfWidth;
    }

    /** gaps 個缺口平均分一圈，第一個在 offset：每個缺口的中心角度。 */
    public static double[] gapCenters(final int gaps, final double offset) {
        final double[] centers = new double[Math.max(0, gaps)];
        for (int i = 0; i < centers.length; i++) {
            centers[i] = offset + Math.PI * 2 * i / centers.length;
        }
        return centers;
    }

    /** 這個方向在不在某個缺口裡（缺口張角 gapAngle，弧度）。 */
    public static boolean inGap(final double angle, final double[] gapCenters, final double gapAngle) {
        for (final double center : gapCenters) {
            if (angleDifference(angle, center) <= gapAngle / 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 第 index 個安全區（一共 count 個，平均分在 Boss 周圍，整組轉 offset）可以放的位置，照優先順序：
     * 先照原本的方向、離 Boss distance 格；那裡站不上去（牆、坑）就左右轉 15°、30°，再近一點（最近離 Boss safe + 1 格）。
     * 回傳 [x, z] 的偏移量（相對 Boss）。
     */
    public static List<double[]> safeSpotCandidates(final int index, final int count, final double distance, final double safe,
                                                    final double offset) {
        final List<double[]> candidates = new ArrayList<>();
        final double base = offset + Math.PI * 2 * index / Math.max(1, count);
        for (final double scale : new double[] {1, 0.8, 0.6, 0.45, 0.3}) {
            final double d = Math.max(safe + 1, distance * scale);
            for (final int turn : new int[] {0, 1, -1, 2, -2}) {
                final double angle = base + Math.toRadians(15) * turn;
                candidates.add(new double[] {Math.cos(angle) * d, Math.sin(angle) * d});
            }
        }
        return candidates;
    }

    /** 這個點在不在任何一個安全區裡（安全區的中心 [x, z]、半徑 radius）。 */
    public static boolean insideAny(final double px, final double pz, final List<double[]> centers, final double radius) {
        for (final double[] center : centers) {
            if (Math.hypot(px - center[0], pz - center[1]) <= radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * 引力漩渦這一 tick 把人往中心拉多少：方向朝 (ox, oz)、大小 strength，但不會把人拉過中心（離中心不到 strength 就只拉到旁邊）。
     * 回傳 [dx, dz]；已經在中心 0.6 格內回傳 [0, 0]。
     */
    public static double[] pullStep(final double ox, final double oz, final double px, final double pz, final double strength) {
        final double dx = ox - px;
        final double dz = oz - pz;
        final double distance = Math.hypot(dx, dz);
        if (distance <= 0.6) {
            return new double[] {0, 0};
        }
        final double step = Math.min(strength, distance - 0.6);
        return new double[] {dx / distance * step, dz / distance * step};
    }
}
