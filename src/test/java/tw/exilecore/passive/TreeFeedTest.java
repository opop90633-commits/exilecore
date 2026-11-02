package tw.exilecore.passive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.clientmod.UiWire;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（D 組）天賦樹原生畫面的資料：靜態資料（照出貨的 tree.json）、能配的節點、路徑檢查、屬性點的順序。 */
class TreeFeedTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws Exception {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    @Test
    void staticJsonCoversTheTree() {
        final JsonObject json = TreeFeed.staticJson(tree);
        final JsonArray nodes = json.getAsJsonArray("nodes");
        assertEquals(tree.size(), nodes.size());
        assertEquals(tree.links().size() * 2, json.getAsJsonArray("links").size());
        final JsonObject first = nodes.get(0).getAsJsonObject();
        final PassiveNode node = tree.ordered().getFirst();
        assertEquals(node.id(), first.get("i").getAsString());
        assertEquals(Math.round(tree.position(node)[0]), first.get("x").getAsLong(), "照 PoE2 的座標");
        assertEquals(tree.starts().size(), json.getAsJsonObject("starts").size());
        assertTrue(json.getAsJsonObject("var").size() > 0, "有職業變體");
        final byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
        final byte[] packed = UiWire.deflate(raw);
        System.out.println("天賦樹靜態資料：" + raw.length + " 位元組，壓縮後 " + packed.length + "，切 " + UiWire.encode("passive.static", raw, UiWire.DEFAULT_CHUNK).size() + " 段");
        assertTrue(packed.length < 600_000, "壓縮後要小（" + packed.length + "）");
        assertEquals(json.toString(), TreeFeed.staticJson(tree).toString(), "同一棵樹每次一樣（雜湊才穩定）");
    }

    @Test
    void availableMatchesCanAllocate() {
        final PassiveNode start = tree.start("witch");
        final Set<String> allocated = new HashSet<>();
        // 從起點往外配幾個
        PassiveNode current = start;
        for (int i = 0; i < 6; i++) {
            PassiveNode next = null;
            for (final PassiveNode neighbour : tree.neighbours(current)) {
                if (!neighbour.isStart() && !allocated.contains(neighbour.id()) && tree.canAllocate(allocated, start, neighbour)) {
                    next = neighbour;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            allocated.add(next.id());
            current = next;
        }
        for (final Set<String> rules : List.of(Set.<String>of(), Set.of(PassiveTree.RULE_PATH_WARRIOR), Set.of(PassiveTree.RULE_UNSEEN_PATH))) {
            final Set<String> expected = new HashSet<>();
            for (final PassiveNode node : tree.nodes()) {
                if (tree.canAllocate(allocated, start, node, rules)) {
                    expected.add(node.id());
                }
            }
            assertEquals(expected, TreeFeed.available(tree, start, allocated, rules), "規則 " + rules);
        }
    }

    @Test
    void checkPathFollowsTheRules() {
        final PassiveNode start = tree.start("witch");
        final PassiveNode far = tree.nodes().stream().filter(n -> n.type() == PassiveNode.Type.NOTABLE)
                .filter(n -> tree.pathTo(Set.of(), start, n).size() > 4).findFirst().orElseThrow();
        final List<String> path = tree.pathTo(Set.of(), start, far).stream().map(PassiveNode::id).toList();
        final Object[] ok = TreeFeed.checkPath(tree, start, Set.of(), Set.of(), path, null);
        assertNull(ok[1]);
        assertEquals(path.size(), ((List<?>) ok[0]).size());
        final List<String> reversed = new java.util.ArrayList<>(path);
        java.util.Collections.reverse(reversed);
        assertNotNull(TreeFeed.checkPath(tree, start, Set.of(), Set.of(), reversed, null)[1], "倒過來接不上");
        assertNotNull(TreeFeed.checkPath(tree, start, Set.of(), Set.of(), List.of("nope"), null)[1], "沒有的節點");
        assertNotNull(TreeFeed.checkPath(tree, start, Set.of(), Set.of(), List.of(), null)[1], "空的");
        assertNotNull(TreeFeed.checkPath(tree, start, Set.of(path.getFirst()), Set.of(), path, null)[1], "已經配過的");
    }

    @Test
    void summaryMatchesTheChestMenu() {
        final PassiveNode start = tree.start("witch");
        final PassiveNode far = tree.nodes().stream().filter(n -> n.type() == PassiveNode.Type.NOTABLE)
                .filter(n -> tree.pathTo(Set.of(), start, n).size() > 4).findFirst().orElseThrow();
        final List<PassiveNode> path = tree.pathTo(Set.of(), start, far);
        final List<String> lines = TreeFeed.summaryLines(tree, path.stream().map(PassiveNode::id).toList());
        final List<String> total = PassiveSummary.sum(path);
        assertEquals("<#77aaff>整條路加起來：", lines.getFirst());
        assertEquals(Math.min(10, total.size()) + 1 + (total.size() > 10 ? 1 : 0), lines.size());
        assertEquals("<white>　" + tw.exilecore.item.ItemRenderer.escape(total.getFirst()), lines.get(1), "跟箱子選單同一段");
        assertTrue(TreeFeed.summaryLines(tree, List.of(far.id())).isEmpty(), "一個節點不用加總");
        assertTrue(TreeFeed.summaryLines(tree, List.of("nope", "nope2")).isEmpty());
    }

    @Test
    void attributeCycle() {
        assertEquals("dex", TreeFeed.nextAttribute("str", false));
        assertEquals("int", TreeFeed.nextAttribute("dex", false));
        assertEquals("str", TreeFeed.nextAttribute("int", false));
        assertEquals("dmg", TreeFeed.nextAttribute("int", true));
        assertEquals("cost", TreeFeed.nextAttribute("def", true));
        assertEquals("str", TreeFeed.nextAttribute("cost", true));
        assertEquals("o", TreeFeed.kindCode(PassiveNode.Type.START));
    }
}
