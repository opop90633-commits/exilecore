package tw.exilecore.core.config;

import java.util.Map;
import java.util.NavigableMap;

/**
 * 「等級 → 數值」錨點表的內插。超出表的範圍取最近的端點。純函式，不依賴 Bukkit。
 * <ul>
 *   <li>{@link Mode#GEOMETRIC 等比}：value = a ×（b ÷ a）^t，錨點之間每一級照固定的比例成長。
 *       難度相關的表（怪物生命、怪物傷害、法術成長）用這個：線性內插會每 10 級循環一次
 *       「剛過錨點漲很多、快到下一個錨點只漲一點」，等比內插每級漲一樣多 %（PoE 的怪物數值就是這種形狀）。
 *       兩端有 0 或負數時等比算不出來，改用線性。</li>
 *   <li>{@link Mode#LINEAR 線性}：節奏相關的表（每隻怪的經驗、金幣、升級經驗、退點金幣）照原本的設計用線性，
 *       那些表是照「每級殺幾隻」「每級賺多少」排的，改成等比會讓錨點之間變少。</li>
 * </ul>
 */
public final class LevelCurve {

    /** 內插方式。 */
    public enum Mode {
        LINEAR,
        GEOMETRIC
    }

    private LevelCurve() {
    }

    /** 在 a 與 b 之間取 t（0 到 1）。等比模式兩端都是正數才用等比，否則線性。 */
    public static double between(final double a, final double b, final double t, final Mode mode) {
        if (mode == Mode.GEOMETRIC && a > 0 && b > 0) {
            return a * Math.pow(b / a, t);
        }
        return a + (b - a) * t;
    }

    /** 等比內插（{@link #between(double, double, double, Mode)} 的簡寫）。 */
    public static double between(final double a, final double b, final double t) {
        return between(a, b, t, Mode.GEOMETRIC);
    }

    /** 等級表在 level 的值；表是空的回傳 fallback。 */
    public static double at(final NavigableMap<Integer, Double> table, final double level, final double fallback, final Mode mode) {
        final Map.Entry<Integer, Double> floor = table.floorEntry((int) Math.floor(level));
        final Map.Entry<Integer, Double> ceiling = table.ceilingEntry((int) Math.ceil(level));
        if (floor == null) {
            return ceiling == null ? fallback : ceiling.getValue();
        }
        if (ceiling == null || ceiling.getKey().equals(floor.getKey())) {
            return floor.getValue();
        }
        final double t = (level - floor.getKey()) / (ceiling.getKey() - floor.getKey());
        return between(floor.getValue(), ceiling.getValue(), t, mode);
    }

    /** 同上，整數表（升級經驗、退點金幣），四捨五入。 */
    public static long atLong(final NavigableMap<Integer, Long> table, final int level, final long fallback, final Mode mode) {
        final Map.Entry<Integer, Long> floor = table.floorEntry(level);
        final Map.Entry<Integer, Long> ceiling = table.ceilingEntry(level);
        if (floor == null) {
            return ceiling == null ? fallback : ceiling.getValue();
        }
        if (ceiling == null || ceiling.getKey().equals(floor.getKey())) {
            return floor.getValue();
        }
        final double t = (level - floor.getKey()) / (double) (ceiling.getKey() - floor.getKey());
        return Math.round(between(floor.getValue(), ceiling.getValue(), t, mode));
    }

    /** 範圍表（[最小, 最大]，例如怪物傷害）：兩個數字各自內插。回傳複本。 */
    public static double[] range(final NavigableMap<Integer, double[]> table, final double level, final double[] fallback, final Mode mode) {
        final Map.Entry<Integer, double[]> floor = table.floorEntry((int) Math.floor(level));
        final Map.Entry<Integer, double[]> ceiling = table.ceilingEntry((int) Math.ceil(level));
        if (floor == null) {
            return (ceiling == null ? fallback : ceiling.getValue()).clone();
        }
        if (ceiling == null || ceiling.getKey().equals(floor.getKey())) {
            return floor.getValue().clone();
        }
        final double t = (level - floor.getKey()) / (ceiling.getKey() - floor.getKey());
        return new double[] {
                between(floor.getValue()[0], ceiling.getValue()[0], t, mode),
                between(floor.getValue()[1], ceiling.getValue()[1], t, mode)};
    }

    /** 整數範圍表（金幣），四捨五入。回傳複本。 */
    public static int[] intRange(final NavigableMap<Integer, int[]> table, final int level, final int[] fallback, final Mode mode) {
        final Map.Entry<Integer, int[]> floor = table.floorEntry(level);
        final Map.Entry<Integer, int[]> ceiling = table.ceilingEntry(level);
        if (floor == null) {
            return (ceiling == null ? fallback : ceiling.getValue()).clone();
        }
        if (ceiling == null || ceiling.getKey().equals(floor.getKey())) {
            return floor.getValue().clone();
        }
        final double t = (level - floor.getKey()) / (double) (ceiling.getKey() - floor.getKey());
        return new int[] {
                (int) Math.round(between(floor.getValue()[0], ceiling.getValue()[0], t, mode)),
                (int) Math.round(between(floor.getValue()[1], ceiling.getValue()[1], t, mode))};
    }
}
