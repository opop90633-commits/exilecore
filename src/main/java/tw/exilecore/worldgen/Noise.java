package tw.exilecore.worldgen;

/**
 * 決定性的雜訊：同樣的種子與座標永遠得到同樣的值（世界生成可以平行、可以重跑，單元測試也能比對）。
 * 值雜訊（格點隨機值＋五次平滑內插）與多層疊加（fbm），範圍都是 [-1, 1]。
 */
public final class Noise {

    private Noise() {
    }

    /** 64 位元的格點雜湊（SplitMix64 的混合函式）。 */
    public static long hash(final long seed, final long x, final long z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    /** 格點上的隨機值，[0, 1)。 */
    public static double random01(final long seed, final long x, final long z) {
        return (hash(seed, x, z) >>> 11) * 0x1.0p-53;
    }

    /** 格點上的隨機整數，[0, bound)。 */
    public static int randomInt(final long seed, final long x, final long z, final int bound) {
        return (int) Math.floorMod(hash(seed, x, z), (long) Math.max(1, bound));
    }

    /** 平滑的二維值雜訊，[-1, 1]；座標的單位是格點（呼叫端自己除以波長）。 */
    public static double value(final long seed, final double x, final double z) {
        final long x0 = (long) Math.floor(x);
        final long z0 = (long) Math.floor(z);
        final double fx = fade(x - x0);
        final double fz = fade(z - z0);
        final double a = random01(seed, x0, z0);
        final double b = random01(seed, x0 + 1, z0);
        final double c = random01(seed, x0, z0 + 1);
        final double d = random01(seed, x0 + 1, z0 + 1);
        final double top = a + (b - a) * fx;
        final double bottom = c + (d - c) * fx;
        return (top + (bottom - top) * fz) * 2 - 1;
    }

    /** 一維版本。 */
    public static double value1(final long seed, final double x) {
        return value(seed, x, 0.5);
    }

    /** 多層疊加：每層波長減半、振幅減半，結果正規化到 [-1, 1]。 */
    public static double fbm(final long seed, final double x, final double z, final int octaves) {
        double sum = 0;
        double amplitude = 1;
        double norm = 0;
        double frequency = 1;
        for (int i = 0; i < octaves; i++) {
            sum += value(seed + i * 1013L, x * frequency, z * frequency) * amplitude;
            norm += amplitude;
            amplitude *= 0.5;
            frequency *= 2;
        }
        return sum / norm;
    }

    /** 五次平滑（Perlin 的 6t⁵−15t⁴+10t³）。 */
    public static double fade(final double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** 0 到 1 的平滑階梯：t ≤ edge0 為 0，t ≥ edge1 為 1。 */
    public static double smoothstep(final double edge0, final double edge1, final double t) {
        if (edge1 == edge0) {
            return t < edge0 ? 0 : 1;
        }
        final double x = Math.max(0, Math.min(1, (t - edge0) / (edge1 - edge0)));
        return x * x * (3 - 2 * x);
    }

    public static double lerp(final double a, final double b, final double t) {
        return a + (b - a) * t;
    }
}
