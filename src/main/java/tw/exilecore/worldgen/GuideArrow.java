package tw.exilecore.worldgen;

import java.util.ArrayList;
import java.util.List;

/**
 * 路標的形狀（純邏輯）：鋪在地上、5×5 的箭頭，中心在 (cx, cz)，指向 (dx, dz)。
 * 東南西北（dx、dz 一個是 0）或斜的四個方向（dx、dz 都是 ±1）。
 * <pre>
 *  往東：            往東南：
 *  . . X . .         X . . . .
 *  . . . X .         . X . . .
 *  X X X X X         . . X . X
 *  . . . X .         . . . X X
 *  . . X . .         . . X X X
 * </pre>
 */
public final class GuideArrow {

    private GuideArrow() {
    }

    /** 箭頭的每一格（{x, z}，世界或相對座標都可以：只是加上中心）。 */
    public static List<int[]> cells(final int cx, final int cz, final int dx, final int dz) {
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1 || (dx == 0 && dz == 0)) {
            throw new IllegalArgumentException("方向要是 -1、0、1，而且不能兩個都是 0");
        }
        final List<int[]> cells = new ArrayList<>(9);
        if (dx != 0 && dz != 0) {
            // 斜的：一條斜線＋箭頭尖端的兩個邊
            for (int i = -2; i <= 2; i++) {
                cells.add(new int[] {cx + i * dx, cz + i * dz});
            }
            cells.add(new int[] {cx + 2 * dx, cz + dz});
            cells.add(new int[] {cx + 2 * dx, cz});
            cells.add(new int[] {cx + dx, cz + 2 * dz});
            cells.add(new int[] {cx, cz + 2 * dz});
            return cells;
        }
        // 正的：沿著方向一條線（a），垂直方向（c）的兩邊是箭頭
        final int px = -dz;
        final int pz = dx;
        for (int a = -2; a <= 2; a++) {
            cells.add(new int[] {cx + a * dx, cz + a * dz});
        }
        for (final int c : new int[] {-1, 1}) {
            cells.add(new int[] {cx + dx + c * px, cz + dz + c * pz});
            cells.add(new int[] {cx + 2 * c * px, cz + 2 * c * pz});
        }
        return cells;
    }
}
