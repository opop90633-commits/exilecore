package tw.exilecore.worldgen;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 蜘蛛網碰不碰得到（{@link WebReach}，2026-10「跑步會拉腳」）：劇情世界區塊載入時照這條規則清。 */
class WebReachTest {

    /** 一個小世界：平的地面，加上另外放的方塊。 */
    private static final class Blocks implements WebReach.Blocks {

        private final Map<Long, String> placed = new HashMap<>();
        private final int ground;

        Blocks(final int ground) {
            this.ground = ground;
        }

        Blocks put(final int x, final int y, final int z, final String name) {
            placed.put(key(x, y, z), name);
            return this;
        }

        @Override
        public boolean solid(final int x, final int y, final int z) {
            final String name = placed.get(key(x, y, z));
            if (name != null) {
                return !name.equals("air") && !name.equals("cobweb");
            }
            return y <= ground;
        }

        @Override
        public boolean leaves(final int x, final int y, final int z) {
            final String name = placed.get(key(x, y, z));
            return name != null && name.endsWith("_leaves");
        }

        private static long key(final int x, final int y, final int z) {
            return ((long) (x & 0xffff) << 32) | ((long) (z & 0xffff) << 16) | (y & 0xffff);
        }
    }

    @Test
    void reachMatchesTheTerrainClearance() {
        assertEquals(4, WebReach.REACH, "跳到最高頭頂大約在腳下那格往上 4 格多");
        assertEquals(CampaignTerrain.WEB_CLEARANCE, WebReach.REACH + 1, "地形放的蜘蛛網剛好碰不到");
    }

    @Test
    void websOnTheGroundUpToFourAboveAreReachable() {
        final Blocks flat = new Blocks(64);
        for (int y = 65; y <= 68; y++) {
            assertTrue(WebReach.reachable(flat, 0, y, 0), "地面 64、蜘蛛網在 " + y);
        }
        assertFalse(WebReach.reachable(flat, 0, 69, 0), "地面往上 5 格：跳到最高頭頂只到 68 多一點");
    }

    @Test
    void neighbouringColumnsCount() {
        // 隔壁（含斜對角）高一格：站在格子邊上，身體會伸進蜘蛛網那一格
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                final Blocks step = new Blocks(64).put(dx, 65, dz, "stone");
                assertTrue(WebReach.reachable(step, 0, 69, 0), "隔壁 " + dx + "," + dz + " 高一格");
            }
        }
        final Blocks far = new Blocks(64).put(2, 65, 0, "stone");
        assertFalse(WebReach.reachable(far, 0, 69, 0), "隔兩格的伸不進來");
    }

    @Test
    void leavesAndTrunksAreNotSomewhereToStand() {
        // 樹冠：底下是樹葉（爬不上樹頂），地面在 6 格以下
        final Blocks canopy = new Blocks(60).put(0, 66, 0, "dark_oak_leaves").put(1, 66, 0, "dark_oak_leaves");
        assertFalse(WebReach.reachable(canopy, 0, 67, 0));
        // 旁邊是一整根樹幹：每一格上面都是樹幹，站不上去
        final Blocks trunk = new Blocks(60);
        for (int y = 61; y <= 70; y++) {
            trunk.put(-1, y, 0, "dark_oak_log");
        }
        assertFalse(WebReach.reachable(trunk, 0, 66, 0));
        // 大石頭（實心、上面空的）就站得上去
        final Blocks boulder = new Blocks(60).put(1, 62, 0, "stone");
        assertTrue(WebReach.reachable(boulder, 0, 66, 0), "石頭頂 62，蜘蛛網 66");
    }

    @Test
    void needsTwoBlocksOfRoomToStand() {
        // 隔壁 63 是石頭，可是 65～68 是一道牆：63 上面站不下，牆頂 68 又比蜘蛛網高
        final Blocks wall = new Blocks(60).put(1, 63, 0, "stone");
        for (int y = 65; y <= 68; y++) {
            wall.put(1, y, 0, "stone");
        }
        assertFalse(WebReach.reachable(wall, 0, 67, 0));
        final Blocks open = new Blocks(60).put(1, 63, 0, "stone");
        assertTrue(WebReach.reachable(open, 0, 67, 0), "上面空的：站在 63 跳起來碰得到 67");
    }
}
