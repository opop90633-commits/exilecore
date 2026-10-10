package tw.exilecore.skill;

import tw.exilecore.web.UiPack;

/**
 * 招式預警平貼在地上的圖（資源包的 {@value UiPack#MARK_MODEL}…）怎麼擺：用哪張圖、轉幾度、放多大、中心往哪裡偏。純邏輯，不依賴 Bukkit。
 * <p>
 * 圖是一片水平的平面，正中間對著物品展示實體的位置。原版畫物品展示實體時會先把模型繞 Y 軸轉半圈，
 * 所以圖的「上方」（扇形張開的方向、長條的長度方向）最後朝 +Z（南）；再繞 Y 軸轉 {@link Placement#yaw()}，
 * 朝 +Z 的就變成朝 (sin yaw, cos yaw)，見 {@link #pointing}。
 * <p>
 * progress 是填滿的進度（0–1）：範圍外框用 1；「慢慢填滿」那一片用前搖的進度——圓從中心往外長、扇形從頂點往外長、
 * 長條從起點往前長。
 */
final class GroundMarks {

    /** 放大的最小值（0 會讓客戶端算不出法向量）。 */
    static final double MIN_SCALE = 0.02;

    /**
     * 擺法。
     *
     * @param model   圖名（{@link UiPack#markNames()} 其中一個）
     * @param yaw     繞 Y 軸轉幾弧度（右手定則：+X 往 -Z 轉為正）
     * @param scaleX  寬（格）
     * @param scaleZ  長（格）
     * @param offsetX 平面中心離實體位置多遠（世界座標，格）
     * @param offsetZ 同上
     */
    record Placement(String model, double yaw, double scaleX, double scaleZ, double offsetX, double offsetZ) {
    }

    private GroundMarks() {
    }

    /** 圓：中心在實體的位置，半徑 radius × progress。 */
    static Placement disc(final double radius, final double progress) {
        final double size = Math.max(MIN_SCALE, radius * 2 * clamp(progress));
        return new Placement("disc_b" + UiPack.markBucket(radius * 2), 0, size, size, 0, 0);
    }

    /** 只有外框的圓（大範圍的邊界，裡面另外標安全的地方）。 */
    static Placement ring(final double radius) {
        final double size = Math.max(MIN_SCALE, radius * 2);
        return new Placement("ring_b" + UiPack.markBucket(size), 0, size, size, 0, 0);
    }

    /**
     * 扇形：頂點在實體的位置，朝 (fx, fz) 張開 angle 度，長 length × progress。
     * 圖的半徑是半張圖，所以放大成 2 × 長度。
     */
    static Placement sector(final double fx, final double fz, final double length, final double angle, final double progress) {
        final double size = Math.max(MIN_SCALE, length * 2 * clamp(progress));
        return new Placement(UiPack.markSector(angle) + "_b" + UiPack.markBucket(length * 2), yawFor(fx, fz), size, size, 0, 0);
    }

    /** 長條：從實體的位置朝 (fx, fz) 往前 length × progress，左右各 halfWidth。 */
    static Placement strip(final double fx, final double fz, final double length, final double halfWidth, final double progress) {
        final double reach = Math.max(MIN_SCALE, length * clamp(progress));
        final double[] direction = normalized(fx, fz);
        return new Placement("rect", yawFor(fx, fz), Math.max(MIN_SCALE, halfWidth * 2), reach,
                direction[0] * reach / 2, direction[1] * reach / 2);
    }

    /** 要轉幾度，圖的上方才會朝 (fx, fz)。 */
    static double yawFor(final double fx, final double fz) {
        final double[] direction = normalized(fx, fz);
        return Math.atan2(direction[0], direction[1]);
    }

    /** 轉了 yaw 之後，圖的上方朝哪裡（x, z）。 */
    static double[] pointing(final double yaw) {
        return new double[] {Math.sin(yaw), Math.cos(yaw)};
    }

    /** 繞 Y 軸轉 yaw 的四元數（x, y, z, w）。 */
    static float[] quaternion(final double yaw) {
        return new float[] {0f, (float) Math.sin(yaw / 2), 0f, (float) Math.cos(yaw / 2)};
    }

    private static double[] normalized(final double fx, final double fz) {
        final double length = Math.hypot(fx, fz);
        return length < 1e-9 ? new double[] {0, 1} : new double[] {fx / length, fz / length};
    }

    private static double clamp(final double progress) {
        return Double.isNaN(progress) ? 0 : Math.max(0, Math.min(1, progress));
    }
}
