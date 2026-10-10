package tw.exilecore.pvp;

import org.junit.jupiter.api.Test;
import tw.exilecore.worldgen.BufferSink;
import tw.exilecore.worldgen.Palette;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 競技場的場地：對稱、梯子貼著掩體而且爬得上頂、每個掩體頂都到得了、地板連通、出生點空曠、圍牆與屏障。 */
class ArenaLayoutTest {

    static final ArenaLayout LAYOUT = ArenaLayout.standard();
    static final int Y = ArenaLayout.FLOOR_Y;

    @Test
    void symmetricInAllEightDirections() {
        final Set<ArenaLayout.Box> boxes = new HashSet<>(LAYOUT.boxes());
        final Set<ArenaLayout.Ladder> ladders = new HashSet<>(LAYOUT.ladders());
        final Set<ArenaLayout.Spawn> spawns = new HashSet<>(LAYOUT.spawns());
        for (int op = 0; op < 8; op++) {
            for (final ArenaLayout.Box box : LAYOUT.boxes()) {
                assertTrue(boxes.contains(ArenaLayout.transform(box, op)), "對稱 " + op + "：" + box);
            }
            for (final ArenaLayout.Ladder ladder : LAYOUT.ladders()) {
                assertTrue(ladders.contains(ArenaLayout.transform(ladder, op)), "對稱 " + op + "：" + ladder);
            }
            for (final ArenaLayout.Spawn spawn : LAYOUT.spawns()) {
                assertTrue(spawns.contains(ArenaLayout.transform(spawn, op)), "對稱 " + op + "：" + spawn);
            }
        }
        // 每一種掩體都有
        for (final ArenaLayout.Kind kind : ArenaLayout.Kind.values()) {
            assertTrue(LAYOUT.boxes().stream().anyMatch(box -> box.kind() == kind), kind.name());
        }
    }

    @Test
    void eightSpawnsBehindCoverFacingTheCenter() {
        assertEquals(8, LAYOUT.spawns().size());
        for (final ArenaLayout.Spawn spawn : LAYOUT.spawns()) {
            assertTrue(ArenaLayout.inside(spawn.x() + 0.5, spawn.z() + 0.5), spawn.toString());
            for (int y = Y + 1; y <= Y + 2; y++) {
                assertFalse(LAYOUT.coverAt(spawn.x(), y, spawn.z()), "出生點要空：" + spawn);
                assertNull(LAYOUT.ladderAt(spawn.x(), y, spawn.z()), spawn.toString());
            }
            // 離掩體至少 3 格（不會一出來就卡在牆邊）
            for (final ArenaLayout.Box box : LAYOUT.boxes()) {
                if (!box.grounded()) {
                    continue;
                }
                final int dx = Math.max(0, Math.max(box.x1() - spawn.x(), spawn.x() - box.x2()));
                final int dz = Math.max(0, Math.max(box.z1() - spawn.z(), spawn.z() - box.z2()));
                assertTrue(Math.max(dx, dz) >= 3, spawn + " 離 " + box + " 太近");
            }
            // 面向場地中央
            final double yaw = Math.toRadians(spawn.yaw());
            final double fx = -Math.sin(yaw);
            final double fz = Math.cos(yaw);
            final double length = Math.hypot(spawn.x(), spawn.z());
            assertTrue((fx * -spawn.x() + fz * -spawn.z()) / length > 0.99, "要看向中央：" + spawn);
        }
    }

    @Test
    void yawFollowsTheGameConvention() {
        assertEquals(90, ArenaLayout.yawTowardsCenter(27, 0), 1e-3, "在東邊：看西邊");
        assertEquals(270, ArenaLayout.yawTowardsCenter(-27, 0), 1e-3, "在西邊：看東邊");
        assertEquals(180, ArenaLayout.yawTowardsCenter(0, 27), 1e-3, "在南邊：看北邊");
        assertEquals(0, ArenaLayout.yawTowardsCenter(0, -27), 1e-3, "在北邊：看南邊");
        assertEquals(135, ArenaLayout.yawTowardsCenter(26, 26), 1e-3);
    }

    @Test
    void laddersHugCoverAndReachTheTop() {
        assertFalse(LAYOUT.ladders().isEmpty());
        final Set<String> cells = new HashSet<>();
        for (final ArenaLayout.Ladder ladder : LAYOUT.ladders()) {
            assertEquals(Y + 1, ladder.y1(), "梯子從地上開始：" + ladder);
            final int bx = ladder.x() - ladder.dx();
            final int bz = ladder.z() - ladder.dz();
            for (int y = ladder.y1(); y <= ladder.y2(); y++) {
                assertFalse(LAYOUT.coverAt(ladder.x(), y, ladder.z()), "梯子不能在掩體裡：" + ladder);
                assertTrue(LAYOUT.coverAt(bx, y, bz), "梯子後面要有方塊貼著：" + ladder + " y=" + y);
                assertTrue(cells.add(ladder.x() + "," + y + "," + ladder.z()), "梯子重疊：" + ladder);
            }
            // 爬到頂：後面那格就是掩體的頂，上面兩格是空的（站得上去）
            assertFalse(LAYOUT.coverAt(bx, ladder.y2() + 1, bz), "梯子要爬到頂：" + ladder);
            assertFalse(LAYOUT.coverAt(bx, ladder.y2() + 2, bz), ladder.toString());
            // 梯子前面那格是空的（走得到）
            assertFalse(LAYOUT.coverAt(ladder.x() + ladder.dx(), Y + 1, ladder.z() + ladder.dz()), "梯子前面被擋住：" + ladder);
            assertTrue(ArenaLayout.inside(ladder.x(), ladder.z()), ladder.toString());
        }
    }

    /** 柱子以外的掩體頂都到得了：自己有梯子，或頂跟有梯子的掩體頂接在一起（橋）。 */
    @Test
    void everyCoverTopIsReachable() {
        final List<ArenaLayout.Box> tops = LAYOUT.boxes().stream().filter(box -> box.kind() != ArenaLayout.Kind.PILLAR).toList();
        final Set<ArenaLayout.Box> reached = new HashSet<>();
        for (final ArenaLayout.Ladder ladder : LAYOUT.ladders()) {
            for (final ArenaLayout.Box box : tops) {
                if (box.contains(ladder.x() - ladder.dx(), ladder.y2(), ladder.z() - ladder.dz()) && box.y2() == ladder.y2()) {
                    reached.add(box);
                }
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (final ArenaLayout.Box box : tops) {
                if (reached.contains(box)) {
                    continue;
                }
                for (final ArenaLayout.Box other : List.copyOf(reached)) {
                    if (other.y2() == box.y2() && touches(box, other)) {
                        reached.add(box);
                        grew = true;
                        break;
                    }
                }
            }
        }
        for (final ArenaLayout.Box box : tops) {
            assertTrue(reached.contains(box), "爬不上去：" + box);
        }
        // 每種爬得上去的掩體都至少有一把梯子（不是只靠橋）
        for (final ArenaLayout.Kind kind : new ArenaLayout.Kind[] {ArenaLayout.Kind.TOWER, ArenaLayout.Kind.CRATE, ArenaLayout.Kind.WALL}) {
            assertTrue(LAYOUT.ladders().stream().anyMatch(ladder -> LAYOUT.boxes().stream().anyMatch(box -> box.kind() == kind
                    && box.contains(ladder.x() - ladder.dx(), ladder.y1(), ladder.z() - ladder.dz()))), kind + " 要有梯子");
        }
    }

    /** 兩個長方體的頂面重疊或邊貼著（水平）。 */
    private static boolean touches(final ArenaLayout.Box a, final ArenaLayout.Box b) {
        return a.x1() <= b.x2() + 1 && b.x1() <= a.x2() + 1 && a.z1() <= b.z2() && b.z1() <= a.z2()
                || a.z1() <= b.z2() + 1 && b.z1() <= a.z2() + 1 && a.x1() <= b.x2() && b.x1() <= a.x2();
    }

    @Test
    void floorIsConnectedAndCoversKeepAGapToTheWalls() {
        final Set<Long> blocked = new HashSet<>();
        for (final ArenaLayout.Box box : LAYOUT.boxes()) {
            assertTrue(box.x1() >= -ArenaLayout.HALF + 2 && box.x2() <= ArenaLayout.HALF - 2
                    && box.z1() >= -ArenaLayout.HALF + 2 && box.z2() <= ArenaLayout.HALF - 2, "掩體離圍牆至少兩格：" + box);
            if (!box.grounded()) {
                assertTrue(box.y1() >= Y + 3, "離地的橋底下要能走（兩格高）：" + box);
                continue;
            }
            for (int x = box.x1(); x <= box.x2(); x++) {
                for (int z = box.z1(); z <= box.z2(); z++) {
                    blocked.add(key(x, z));
                }
            }
        }
        final ArenaLayout.Spawn start = LAYOUT.spawns().getFirst();
        final Set<Long> seen = new HashSet<>();
        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {start.x(), start.z()});
        seen.add(key(start.x(), start.z()));
        while (!queue.isEmpty()) {
            final int[] at = queue.poll();
            for (final int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int x = at[0] + d[0];
                final int z = at[1] + d[1];
                if (Math.abs(x) > ArenaLayout.HALF || Math.abs(z) > ArenaLayout.HALF || blocked.contains(key(x, z)) || !seen.add(key(x, z))) {
                    continue;
                }
                queue.add(new int[] {x, z});
            }
        }
        final int side = ArenaLayout.HALF * 2 + 1;
        assertEquals(side * side - blocked.size(), seen.size(), "地板有走不到的地方");
        for (final ArenaLayout.Spawn spawn : LAYOUT.spawns()) {
            assertTrue(seen.contains(key(spawn.x(), spawn.z())), "出生點之間走得到：" + spawn);
        }
    }

    private static long key(final int x, final int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    @Test
    void buildWritesFloorWallsBarrierCoversAndLadders() {
        final BufferSink sink = new BufferSink();
        LAYOUT.build(sink);
        sink.compact();
        final Map<String, Integer> blocks = new HashMap<>();
        for (int i = 0; i < sink.size(); i++) {
            blocks.put(sink.x(i) + "," + sink.y(i) + "," + sink.z(i), sink.block(i));
        }
        assertTrue(blocks.size() < 120_000, "方塊數 " + blocks.size());
        final int wall = ArenaLayout.WALL;
        // 地板整片都有
        for (int x = -ArenaLayout.HALF; x <= ArenaLayout.HALF; x++) {
            for (int z = -ArenaLayout.HALF; z <= ArenaLayout.HALF; z++) {
                assertNotNull(blocks.get(x + "," + Y + "," + z), "地板 " + x + "," + z);
            }
        }
        // 圍牆從地板到牆高，上面一路屏障
        final int barrier = Palette.id("barrier");
        for (int i = -wall; i <= wall; i += 7) {
            for (int y = Y; y <= Y + ArenaLayout.WALL_HEIGHT + 1; y++) {
                assertNotNull(blocks.get(wall + "," + y + "," + i), "圍牆 " + i + " y=" + y);
                assertNotNull(blocks.get(i + "," + y + "," + -wall), "圍牆 " + i + " y=" + y);
            }
            assertEquals(barrier, blocks.get(wall + "," + ArenaLayout.BARRIER_TOP + "," + i));
            assertEquals(barrier, blocks.get(-wall + "," + (Y + ArenaLayout.WALL_HEIGHT + 2) + "," + i));
        }
        // 梯子的方向與高台
        assertEquals(Palette.id("ladder[facing=east]"), blocks.get("3," + (Y + 1) + ",0"));
        assertEquals(Palette.id("ladder[facing=west]"), blocks.get("-3," + (Y + 4) + ",0"));
        assertEquals(Palette.id("ladder[facing=north]"), blocks.get("0," + (Y + 1) + ",-3"));
        assertEquals(Palette.id("ladder[facing=south]"), blocks.get("0," + (Y + 2) + ",3"));
        assertNotNull(blocks.get("0," + (Y + 4) + ",0"), "高台頂");
        assertNull(blocks.get("0," + (Y + 5) + ",0"), "高台上面是空的");
        // 橋：上面有、底下兩格空
        assertNotNull(blocks.get("15," + (Y + 3) + ",10"));
        assertNull(blocks.get("15," + (Y + 1) + ",10"));
        assertNull(blocks.get("15," + (Y + 2) + ",10"));
        // 出生點的地墊中間是燈
        final ArenaLayout.Spawn spawn = LAYOUT.spawns().getFirst();
        assertEquals(Palette.id("sea_lantern"), blocks.get(spawn.x() + "," + Y + "," + spawn.z()));
        // 場外什麼都沒有
        for (final String at : blocks.keySet()) {
            final String[] parts = at.split(",");
            assertTrue(Math.abs(Integer.parseInt(parts[0])) <= wall && Math.abs(Integer.parseInt(parts[2])) <= wall, "跑出場外：" + at);
            assertTrue(Integer.parseInt(parts[1]) >= ArenaLayout.CLEAR_MIN_Y && Integer.parseInt(parts[1]) <= ArenaLayout.BARRIER_TOP, at);
        }
    }

    @Test
    void insideMatchesTheFloor() {
        assertTrue(ArenaLayout.inside(0, 0));
        assertTrue(ArenaLayout.inside(-30, 30.99));
        assertFalse(ArenaLayout.inside(31, 0), "圍牆那一格不算");
        assertFalse(ArenaLayout.inside(-30.01, 0));
    }
}
