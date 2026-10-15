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

    /**
     * 場地收縮（2026-10）：開始後 elapsed tick 時安全的圈的半徑——從 start 在 total tick 內線性縮到 end，之後維持 end。
     */
    public static double arenaRadius(final double start, final double end, final long elapsed, final long total) {
        if (elapsed <= 0) {
            return start;
        }
        if (total <= 0 || elapsed >= total) {
            return end;
        }
        return start + (end - start) * elapsed / (double) total;
    }

    /** 收縮中放殞星迷宮：最外圈不超過圈半徑的這個比例。 */
    public static final double MAZE_ARENA_SHARE = 0.8;
    /** 收縮中放殞星迷宮：最多幾圈。 */
    public static final int MAZE_ARENA_RINGS = 2;
    /** 收縮中放殞星迷宮：每圈至少幾個缺口。 */
    public static final int MAZE_ARENA_GAPS = 3;

    /**
     * 殞星迷宮跟萬界崩解（場地收縮）一起出現時（2026-10）：牆整個縮進安全圈裡、最多兩圈、每圈至少三個缺口，
     * 一定有路走。arenaRadius 是牆消失那一刻的圈半徑；沒有收縮（≤ 0）照原本的。回傳 {reach, rings, gaps}。
     */
    public static double[] mazeInArena(final double reach, final int rings, final int gaps, final double arenaRadius) {
        if (arenaRadius <= 0) {
            return new double[] {reach, rings, gaps};
        }
        return new double[] {Math.min(reach, arenaRadius * MAZE_ARENA_SHARE), Math.min(rings, MAZE_ARENA_RINGS),
                Math.max(gaps, MAZE_ARENA_GAPS)};
    }

    /** 場地收縮中：圈外的荒蕪、場地危害的風暴離圈邊至少再多幾格（2026-10-16）。 */
    public static final double ARENA_OUTSIDE_MARGIN = 2;
    /** 場地收縮中：只落一個的招（滅世殞星、它的荒蕪）最大是圈半徑的這個比例。 */
    public static final double ARENA_SINGLE_SHARE = 0.4;
    /** 場地收縮中：一次落好幾個的招（殞星三顆）每個最大是圈半徑的這個比例。 */
    public static final double ARENA_MULTI_SHARE = 0.3;
    /** 場地收縮中：落點散布最多是圈半徑的這個比例。 */
    public static final double ARENA_SCATTER_SHARE = 0.6;
    /** 場地收縮中：留在地上的（荒蕪）最多幾秒。 */
    public static final double ARENA_ZONE_SECONDS = 8;

    /**
     * 場地收縮（萬界崩解、天穹收攏）中落在腳下的招（2026-10）：範圍照圈的大小縮小，圈裡一定留得出地方躲；傷害不變。
     * arenaRadius 是落下那一刻的圈半徑（≤ 0 = 沒有收縮，照原本的）。回傳 {radius, scatter, duration}。
     */
    public static double[] fitArena(final double radius, final int count, final double scatter, final double duration,
                                    final boolean lingers, final double arenaRadius) {
        if (arenaRadius <= 0) {
            return new double[] {radius, scatter, duration};
        }
        final double share = count > 1 ? ARENA_MULTI_SHARE : ARENA_SINGLE_SHARE;
        return new double[] {Math.min(radius, arenaRadius * share), Math.min(scatter, arenaRadius * ARENA_SCATTER_SHARE),
                lingers ? Math.min(duration, ARENA_ZONE_SECONDS) : duration};
    }

    /**
     * 圈外鋪荒蕪的範圍（2026-10-15）：從圈邊往外一整個荒蕪的半徑再加 {@link #ARENA_OUTSIDE_MARGIN} 格（2026-10-16：離圈遠一點）
     * 到一開始的圈再外 2 格。
     * 回傳 {內, 外}；圈已經比外緣大（還沒開始縮）回傳 null。
     */
    public static double[] outsideBand(final double arenaRadius, final double startRadius, final double zoneRadius) {
        final double inner = arenaRadius + zoneRadius + ARENA_OUTSIDE_MARGIN;
        final double outer = startRadius + 2;
        return inner >= outer ? null : new double[] {inner, outer};
    }

    /** 環帶裡平均分布的距離（面積平均：外圈多一點）；roll 是 0～1。 */
    public static double inBand(final double inner, final double outer, final double roll) {
        return Math.sqrt(inner * inner + roll * (outer * outer - inner * inner));
    }

    /** 在圈外（身體完全出去才算：中心離圈心超過半徑再加半個身體）。 */
    public static boolean outsideArena(final double cx, final double cz, final double radius, final double px, final double pz,
                                       final double halfBody) {
        return Math.hypot(px - cx, pz - cz) > radius + halfBody;
    }

    /** 殘影（2026-10）的位置：count 個平均分在半徑 distance 的圈上（從 offset 弧度起），回傳相對圈心的 [dx, dz]。 */
    public static List<double[]> echoOffsets(final int count, final double distance, final double offset) {
        final List<double[]> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final double angle = offset + Math.PI * 2 * i / count;
            list.add(new double[] {Math.cos(angle) * distance, Math.sin(angle) * distance});
        }
        return list;
    }

    /**
     * 殘影出手的順序：照圈上的位置隔一個跳一個（12 個是 0、5、10、3、8…，像時鐘上的星衛輪流出手），
     * 連續兩道槍不會從隔壁射出來、疊在同一個地方。count 跟 step 互質才走得遍，不互質就照順序。
     */
    public static int[] echoOrder(final int count) {
        final int[] order = new int[count];
        int step = 1;
        for (final int candidate : new int[] {5, 7, 3, 2}) {
            if (candidate < count && gcd(candidate, count) == 1) {
                step = candidate;
                break;
            }
        }
        for (int i = 0; i < count; i++) {
            order[i] = (i * step) % count;
        }
        return order;
    }

    private static int gcd(final int a, final int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
