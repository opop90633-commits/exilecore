package tw.exilecore.passive;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26：天賦樹整棵換成 PoE2 0.5 的樹（tools/gen_poe2_tree.py 產生；第 4 版，舊角色的配點全部退回）。
 * 相連照 tree.json 的 links，不相鄰的連線走 wires 畫在中間的空格上。
 */
class PassiveTreeTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    /** 2026-10-26 照 PoE2：觀星專屬節點（unlock）要有「未見之道」才能配；開路者的女術者之道可以從女術士起點配。 */
    @Test
    void unlockRulesAndExtraStarts() {
        int oracle = 0;
        PassiveNode locked = null;
        PassiveNode gate = null;
        for (final PassiveNode node : tree.ordered()) {
            final PassiveTree.Unlock unlock = tree.unlock(node);
            if (unlock == null || unlock.stat() == null) {
                continue;
            }
            assertEquals(PassiveTree.RULE_UNSEEN_PATH, unlock.stat());
            oracle++;
            for (final PassiveNode other : tree.neighbours(node)) {
                if (locked == null && tree.unlock(other) == null && !other.isStart()) {
                    locked = node;
                    gate = other;
                }
            }
        }
        assertEquals(176, oracle, "原作 176 個觀星專屬節點");
        assertNotNull(locked);
        // 2026-10-26 照站長：只有觀星看得到（別的昇華、沒選昇華都藏起來）
        assertFalse(tree.hidden(locked, "starseer"));
        assertTrue(tree.hidden(locked, null));
        assertTrue(tree.hidden(locked, "wildshaman"));
        assertFalse(tree.hidden(gate, null));
        final PassiveNode start = tree.start("druid");
        final Set<String> allocated = new HashSet<>();
        for (final PassiveNode step : tree.pathTo(allocated, start, gate)) {
            allocated.add(step.id());
        }
        assertTrue(allocated.contains(gate.id()));
        assertFalse(tree.canAllocate(allocated, start, locked), "沒有未見之道不能配");
        assertTrue(tree.canAllocate(allocated, start, locked, Set.of(PassiveTree.RULE_UNSEEN_PATH)));
        final Set<String> with = new HashSet<>(allocated);
        with.add(locked.id());
        assertEquals(with.size(), tree.reachable(with, start, Set.of(PassiveTree.RULE_UNSEEN_PATH)).size());
        assertEquals(Set.of(locked.id()), tree.prune(with, start), "退了未見之道：觀星節點退回");

        // 開路者（遊俠起點）：女術者之道可以直接配女術士起點旁邊的節點
        final PassiveNode ranger = tree.start("ranger");
        final PassiveNode sorceressNext = tree.neighbours(tree.start("sorceress")).get(0);
        assertFalse(tree.canAllocate(Set.of(), ranger, sorceressNext));
        assertTrue(tree.canAllocate(Set.of(), ranger, sorceressNext, Set.of(PassiveTree.RULE_PATH_SORCERESS)));
        assertEquals(List.of(sorceressNext), tree.pathTo(Set.of(), ranger, sorceressNext, Set.of(PassiveTree.RULE_PATH_SORCERESS)));
    }

    @Test
    void treeIsThePoe2Tree() {
        assertEquals(6, tree.version(), "第 5 版：拿掉連枷節點；第 6 版：加回觀星專屬等有解鎖條件的節點（舊角色的配點全部退回）");
        assertTrue(tree.size() > 3800, "PoE2 0.5 的主樹（扣掉裝飾、連不到的、Oracle 專屬的）：" + tree.size());
        assertTrue(tree.isConnected());
        int keystones = 0;
        int notables = 0;
        int sockets = 0;
        int starts = 0;
        int attributes = 0;
        for (final PassiveNode node : tree.nodes()) {
            switch (node.type()) {
                case KEYSTONE -> keystones++;
                case NOTABLE -> notables++;
                case SOCKET -> sockets++;
                case START -> starts++;
                default -> {
                }
            }
            if (node.attributeChoice() == 5) {
                attributes++;
            }
            assertTrue(node.isStart() || !node.description().isEmpty(), node.id() + " 沒有說明");
            assertTrue(node.isStart() || node.isSocket() || !node.name().isEmpty(), node.id() + " 沒有名稱");
            for (final PassiveNode other : tree.neighbours(node)) {
                assertTrue(tree.neighbours(other).contains(node), "連線要雙向：" + node.id() + "－" + other.id());
            }
            assertFalse(tree.neighbours(node).isEmpty(), node.id() + " 沒有連線");
        }
        assertEquals(33, keystones, "PoE2 0.5 的 33 個基石");
        assertTrue(notables > 900, "核心 " + notables);
        assertEquals(12, sockets, "珠寶孔（Sinister 的 6 個連不到，不放）");
        assertEquals(6, starts, "PoE2 只有 6 個起點");
        assertTrue(attributes > 250, "+5 任一屬性的點 " + attributes);
        // 每個職業都有起點；遊俠與長矛獵手、元素法師與混沌術士共用（PoE2 一樣）
        for (final tw.exilecore.character.CharacterClass type : tw.exilecore.character.CharacterClass.values()) {
            final PassiveNode start = tree.start(type.id());
            assertNotNull(start, type.id() + " 要有起點");
            assertTrue(start.isStart());
            assertTrue(tree.neighbours(start).size() >= 2, type.id() + " 的起點往外有好幾條路");
        }
        assertEquals(tree.start("ranger"), tree.start("huntress"));
        assertEquals(tree.start("sorceress"), tree.start("witch"));
        assertNotEquals(tree.start("marauder"), tree.start("mercenary"));
        // 起點彼此不相連（起點不能當路）
        for (final PassiveNode node : tree.nodes()) {
            if (node.isStart()) {
                for (final PassiveNode other : tree.neighbours(node)) {
                    assertFalse(other.isStart(), node.id() + " 連到別的起點");
                }
            }
        }
    }

    @Test
    void wiresDrawEveryLinkCellByCell() {
        // 每條連線在格子上都畫得出來：兩端的節點格都有往那條線的方向，中間每一格都接得起來
        for (final PassiveTree.Link link : tree.links()) {
            final PassiveNode a = link.a();
            boolean found = false;
            for (final PassiveTree.CellWire wire : tree.wiresAt(a.x(), a.y())) {
                if (wire.link().equals(link)) {
                    assertEquals(1, Integer.bitCount(wire.directions()), "端點只有一個方向：" + a.id());
                    found = true;
                }
            }
            assertTrue(found, "連線 " + a.id() + "－" + link.b().id() + " 在起點格上沒畫");
        }
        // 畫面：相連的節點之間一定有線
        final PassiveNode start = tree.start("witch");
        final TreeView view = new TreeView(tree, start, Set.of(), Set.of(), Set.of());
        final TreeView.Tile tile = view.tile(start.x(), start.y());
        assertTrue(TreeView.hasWire(tile), "起點那一格有往外的線");
        assertEquals(TreeView.EdgeState.LIT, TreeView.brightest(tile), "起點連出去的線：一端配了、另一端能配");
    }

    @Test
    void linksReplaceGridAdjacency() {
        // 沒有 links 的舊格式：上下左右相鄰就是相連；有 links：只照 links（格子相鄰也不算）
        final String grid = """
                {"version": 1, "starts": {"witch": "s"}, "nodes": [
                  {"id": "s", "type": "start", "x": 0, "y": 0},
                  {"id": "a", "type": "small", "x": 1, "y": 0, "stats": [], "description": ["a"]},
                  {"id": "b", "type": "small", "x": 1, "y": 1, "stats": [], "description": ["b"]}]}""";
        final PassiveTree old = PassiveTree.load(new StringReader(grid));
        assertEquals(1, old.neighbours(old.node("s")).size());
        assertEquals(2, old.neighbours(old.node("a")).size());
        final String linked = """
                {"version": 1, "starts": {"witch": "s"}, "nodes": [
                  {"id": "s", "type": "start", "x": 0, "y": 0, "links": ["b"]},
                  {"id": "a", "type": "small", "x": 1, "y": 0, "stats": [], "description": ["a"]},
                  {"id": "b", "type": "small", "x": 3, "y": 2, "stats": [], "description": ["b"], "links": ["a"]}]}""";
        final PassiveTree poe2 = PassiveTree.load(new StringReader(linked));
        final PassiveNode s = poe2.node("s");
        assertEquals(List.of(poe2.node("b")), poe2.neighbours(s), "格子相鄰的 a 沒有連線就不算");
        assertTrue(poe2.canAllocate(Set.of(), s, poe2.node("b")));
        assertFalse(poe2.canAllocate(Set.of(), s, poe2.node("a")));
        assertTrue(poe2.canAllocate(Set.of("b"), s, poe2.node("a")));
        // 沒寫路線的連線走 L 形：(0,0)→(3,0)→(3,2)，中間的空格有線
        assertTrue(TreeView.hasWire(new TreeView(poe2, s, Set.of(), Set.of(), Set.of()).tile(2, 0)));
        assertTrue(TreeView.hasWire(new TreeView(poe2, s, Set.of(), Set.of(), Set.of()).tile(3, 1)));
        // 連到不存在的節點、路線斷掉都擋下來
        assertThrows(IllegalArgumentException.class, () -> PassiveTree.load(new StringReader(linked.replace("\"links\": [\"a\"]", "\"links\": [\"zz\"]"))));
        assertThrows(IllegalArgumentException.class, () -> PassiveTree.load(new StringReader(linked.replace("]}]}",
                "]}], \"wires\": [{\"a\": \"s\", \"b\": \"b\", \"cells\": [[1, 1]]}]}"))));
    }

    @Test
    void allocationFollowsLinksAndRefundKeepsConnectivity() {
        final PassiveNode start = tree.start("witch");
        final PassiveNode target = keystone("Chaos Inoculation");
        final Set<String> allocated = new HashSet<>();
        final List<PassiveNode> path = tree.pathTo(allocated, start, target);
        assertFalse(path.isEmpty());
        assertEquals(target, path.getLast());
        assertEquals(distance(start, target), path.size(), "自動配路是最短的路");
        PassiveNode previous = start;
        for (final PassiveNode step : path) {
            assertTrue(tree.neighbours(previous).contains(step), step.id() + " 沒連著上一步");
            assertTrue(tree.canAllocate(allocated, start, step));
            allocated.add(step.id());
            previous = step;
        }
        assertFalse(tree.canAllocate(allocated, start, tree.start("marauder")), "起點不能配");
        assertFalse(tree.canRefund(allocated, start, path.getFirst()), "退掉第一點會讓後面全部斷線");
        assertTrue(tree.canRefund(allocated, start, target), "最尾端可以退");
        assertTrue(tree.pathTo(allocated, start, target).isEmpty(), "已配置的目標回傳空");
        // 樹改版：塞一個連不到的節點進去，prune 要把它清掉
        final PassiveNode far = tree.neighbours(tree.start("marauder")).getFirst();
        allocated.add(far.id());
        assertEquals(Set.of(far.id()), tree.prune(allocated, start));
    }

    @Test
    void keystonesAndNodesFeedTheStatSheet() {
        final StatSheet flags = new StatSheet();
        flags.addAll(keystone("Resolute Technique").modifiers());
        assertTrue(StatKeys.flag(flags, StatKeys.FLAG_NO_CRIT), "堅毅之心：無法暴擊");
        assertFalse(StatKeys.flag(flags, StatKeys.FLAG_ALWAYS_HIT));
        final StatSheet blood = new StatSheet();
        blood.addAll(keystone("Blood Magic").modifiers());
        assertTrue(StatKeys.flag(blood, "flag.blood_magic"));
        // 一個「最大生命增加」的核心真的加到屬性表
        PassiveNode life = null;
        for (final PassiveNode node : tree.nodes()) {
            // 2026-10-26：核心的其他行也陸續生效了，只要求「最大生命」這條只有一條、沒標籤
            if (node.type() == PassiveNode.Type.NOTABLE && node.modifiers().stream().filter(m -> m.stat().equals(StatKeys.LIFE_MAX)).count() == 1
                    && node.modifiers().getFirst().stat().equals(StatKeys.LIFE_MAX) && node.modifiers().getFirst().tags().isEmpty()) {
                life = node;
                break;
            }
        }
        assertNotNull(life);
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.LIFE_MAX, 100);
        sheet.addAll(tree.modifiers(Set.of(life.id())));
        assertEquals(100 * (1 + life.modifiers().getFirst().value()), sheet.get(StatKeys.LIFE_MAX), 1e-9);
        // 生效的說明行跟修飾器一起，沒生效的標「尚未生效」
        int inactive = 0;
        int lines = 0;
        for (final PassiveNode node : tree.nodes()) {
            for (final String line : node.description()) {
                lines++;
                if (line.endsWith("（尚未生效）")) {
                    inactive++;
                }
            }
            if (!node.modifiers().isEmpty()) {
                assertTrue(node.description().stream().anyMatch(l -> !l.endsWith("（尚未生效）")), node.id());
            }
        }
        assertTrue(inactive < lines / 2, "大部分的說明行都要生效：" + inactive + " / " + lines);
        assertFalse(tree.search("生命").isEmpty());
        assertTrue(tree.search("不存在的字").isEmpty());
    }

    @Test
    void classVariantsChangeTheNode() {
        // PoE2：女巫、德魯伊、女獵人在某些節點拿到不同的效果
        PassiveNode varied = null;
        for (final PassiveNode node : tree.nodes()) {
            if (node.variants().containsKey("druid")) {
                varied = node;
                break;
            }
        }
        assertNotNull(varied);
        final PassiveNode druid = varied.forClass("druid");
        assertNotEquals(varied.modifiers(), druid.modifiers());
        assertEquals(varied.id(), druid.id());
        assertEquals(varied, varied.forClass("marauder"));
        assertEquals(varied, varied.forClass(null));
        final List<Modifier> mods = tree.modifiers(Set.of(varied.id()), "druid");
        assertEquals(druid.modifiers().size(), mods.size());
        int witch = 0;
        int huntress = 0;
        for (final PassiveNode node : tree.nodes()) {
            witch += node.variants().containsKey("witch") ? 1 : 0;
            huntress += node.variants().containsKey("huntress") ? 1 : 0;
        }
        assertTrue(witch > 10 && huntress > 10, witch + " " + huntress);
    }

    private static PassiveNode keystone(final String english) {
        final Map<String, String> ids = Map.of("Chaos Inoculation", "p56349", "Resolute Technique", "p44017", "Blood Magic", "p51749");
        final PassiveNode node = tree.node(ids.get(english));
        assertNotNull(node, english);
        assertEquals(PassiveNode.Type.KEYSTONE, node.type());
        return node;
    }

    /** 從起點出發（不經過其他起點）走到目標要幾步。 */
    private static int distance(final PassiveNode from, final PassiveNode to) {
        final Map<String, Integer> steps = new HashMap<>();
        final ArrayDeque<PassiveNode> queue = new ArrayDeque<>();
        steps.put(from.id(), 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            final PassiveNode current = queue.poll();
            if (current.equals(to)) {
                return steps.get(current.id());
            }
            for (final PassiveNode next : tree.neighbours(current)) {
                if (!next.isStart() && !steps.containsKey(next.id())) {
                    steps.put(next.id(), steps.get(current.id()) + 1);
                    queue.add(next);
                }
            }
        }
        return -1;
    }
}
