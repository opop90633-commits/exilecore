package tw.exilecore.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.function.ToDoubleBiFunction;

/**
 * 2026-10-28 照 PoE2：地面效果每一波的兩種「不是整圈都打」的打法（純邏輯，不依賴 Bukkit）。
 * <ul>
 *   <li>雷彈（雷雲，PoE2 Orb of Storms）：每一波對範圍內隨機一個敵人放一道雷，再連鎖 chains 次
 *       （每次跳到上一個目標 chainRange 格內最近、還沒被這道雷打過的敵人）；</li>
 *   <li>火山彈（地火柱，PoE2 Volcano）：每一波噴出 count 顆，各落在範圍內隨機一點，炸到落點 radius 格內（量到身體的邊，SkillTargets.around）的敵人。</li>
 * </ul>
 * 這次之前這兩招都是每一波打範圍內全部的敵人（連鎖次數、火山彈數量沒有作用）。
 * 另外 {@link #distribute}：天柱雷（PoE2 Lightning Conduit）追加的雷隨機分給打到的敵人。
 */
final class ZonePulses {

    private ZonePulses() {
    }

    /**
     * 一道雷彈打到的目標（照順序）：第一個是 inRange 裡隨機一個，之後每次連鎖到 candidates 裡離上一個 chainRange 格內最近、還沒打過的。
     *
     * @param inRange    雷雲範圍內的敵人（第一下從這裡挑）
     * @param candidates 可以被連鎖到的敵人（通常是雷雲附近一大圈的）
     * @param chains     連鎖幾次
     * @param distance   兩個目標的距離
     */
    static <T> List<T> boltChain(final List<T> inRange, final List<T> candidates, final int chains, final double chainRange,
                                 final ToDoubleBiFunction<T, T> distance, final RandomGenerator random) {
        final List<T> hit = new ArrayList<>();
        if (inRange.isEmpty()) {
            return hit;
        }
        T current = inRange.get(random.nextInt(inRange.size()));
        hit.add(current);
        for (int i = 0; i < chains; i++) {
            T best = null;
            double bestDistance = Double.MAX_VALUE;
            for (final T candidate : candidates) {
                if (hit.contains(candidate)) {
                    continue;
                }
                final double d = distance.applyAsDouble(current, candidate);
                if (d <= chainRange && d < bestDistance) {
                    best = candidate;
                    bestDistance = d;
                }
            }
            if (best == null) {
                break;
            }
            hit.add(best);
            current = best;
        }
        return hit;
    }

    /**
     * 2026-10-28 照 PoE2 Lightning Conduit（「追加的雷隨機分給打到的目標」）：bolts 道雷各挑 hits 裡隨機一個（可以重複）。
     */
    static <T> List<T> distribute(final List<T> hits, final int bolts, final RandomGenerator random) {
        final List<T> picked = new ArrayList<>(Math.max(0, bolts));
        for (int i = 0; i < bolts && !hits.isEmpty(); i++) {
            picked.add(hits.get(random.nextInt(hits.size())));
        }
        return picked;
    }

    /** 火山彈的落點：半徑 radius 的圓裡均勻隨機 count 個（相對圓心的 {x, z}）。 */
    static List<double[]> showerPoints(final int count, final double radius, final RandomGenerator random) {
        final List<double[]> points = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            final double r = radius * Math.sqrt(random.nextDouble());
            final double a = random.nextDouble() * Math.PI * 2;
            points.add(new double[] {Math.cos(a) * r, Math.sin(a) * r});
        }
        return points;
    }
}
