package tw.exilecore.passive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02 照站長：觀星「虛實交織」的跳點（已配基石中半徑內的非基石不用相連）、退點的連通性、跳點接出去的路徑，
 * 「不見之途」專屬節點的旗標。網頁規劃器（planner.html 的 TREE_RULES）與模組畫面照同一套規則。
 */
class EntwinedRulesTest {

    /**
     * 小棵的樹：S－a－K（基石，px 320）－b－c－d－j2－j1－K2（基石）；j1 離 K 800（範圍內）、j2 離 K 1600（範圍外），
     * U 是觀星專屬（接在 a 旁邊）。
     */
    static final String TREE = """
            {"version": 1, "unit": 160, "starts": {"witch": "S"}, "nodes": [
              {"id": "S", "type": "start", "x": 0, "y": 0, "px": 0, "py": 0, "links": ["a"]},
              {"id": "a", "type": "small", "x": 1, "y": 0, "px": 160, "py": 0, "links": ["K", "U"]},
              {"id": "K", "type": "keystone", "x": 2, "y": 0, "px": 320, "py": 0, "links": ["b"]},
              {"id": "b", "type": "small", "x": 3, "y": 0, "px": 480, "py": 0, "links": ["c"]},
              {"id": "c", "type": "small", "x": 4, "y": 0, "px": 2400, "py": 0, "links": ["d"]},
              {"id": "d", "type": "small", "x": 5, "y": 0, "px": 2400, "py": 1600, "links": ["j2"]},
              {"id": "j2", "type": "small", "x": 6, "y": 0, "px": 320, "py": 1600, "links": ["j1"]},
              {"id": "j1", "type": "small", "x": 7, "y": 0, "px": 320, "py": 800, "links": ["K2"]},
              {"id": "K2", "type": "keystone", "x": 8, "y": 0, "px": 320, "py": 1000},
              {"id": "U", "type": "notable", "x": 9, "y": 0, "px": 200, "py": 300,
               "unlock": {"stat": "asc.unseen_path", "ascendancy": "starseer"}}]}""";

    static final Set<String> ENTWINED = Set.of(PassiveTree.RULE_ENTWINED);

    static PassiveTree tree;
    static PassiveNode start;

    @BeforeAll
    static void load() {
        tree = PassiveTree.load(new StringReader(TREE));
        start = tree.start("witch");
    }

    static PassiveNode n(final String id) {
        return tree.node(id);
    }

    @Test
    void jumpOnlyInsideRadiusAndNotKeystones() {
        final Set<String> allocated = Set.of("a", "K");
        assertTrue(tree.canAllocate(allocated, start, n("j1"), ENTWINED), "j1 離 K 800，在中半徑 1150 內");
        assertFalse(tree.canAllocate(allocated, start, n("j1"), Set.of()), "沒有虛實交織要相連");
        assertFalse(tree.canAllocate(allocated, start, n("j2"), ENTWINED), "j2 離 K 1600，範圍外");
        assertFalse(tree.canAllocate(allocated, start, n("K2"), ENTWINED), "基石不能跳");
        assertFalse(tree.canAllocate(Set.of("a"), start, n("j1"), ENTWINED), "要先配到基石");
        assertEquals(List.of(n("j1")), tree.jumps(allocated, start, ENTWINED), "b 跟 K 相連不算跳點；U 沒有不見之途");
        assertEquals(List.of(), tree.jumps(allocated, start, Set.of()));
        assertEquals(List.of(n("K")), tree.entwinedAnchors(allocated, start, ENTWINED));
        assertEquals(List.of(), tree.entwinedAnchors(allocated, start, Set.of()));
        assertEquals(Set.of("b", "j1"), TreeFeed.available(tree, start, allocated, ENTWINED), "模組的 avail 含跳點");
    }

    @Test
    void pathStartsFromJump() {
        final Set<String> allocated = Set.of("a", "K");
        assertEquals(List.of(n("j1"), n("j2")), tree.pathTo(allocated, start, n("j2"), ENTWINED), "從跳點 j1 接過去");
        assertEquals(List.of(n("b"), n("c"), n("d"), n("j2")), tree.pathTo(allocated, start, n("j2"), Set.of()));
        assertEquals(List.of(n("j1")), tree.pathTo(allocated, start, n("j1"), ENTWINED));
    }

    @Test
    void refundKeepsJumpedNodesAnchored() {
        final Set<String> allocated = Set.of("a", "K", "j1", "j2");
        assertEquals(allocated, tree.reachable(allocated, start, ENTWINED), "跳點配的不靠連線也算連得到");
        assertEquals(Set.of("a", "K"), tree.reachable(allocated, start, Set.of()));
        assertFalse(tree.canRefund(allocated, start, n("K"), ENTWINED), "退了基石，跳點配的會斷");
        assertFalse(tree.canRefund(allocated, start, n("j1"), ENTWINED), "j2 只靠 j1 接著（離 K 太遠）");
        assertTrue(tree.canRefund(allocated, start, n("j2"), ENTWINED));
        assertTrue(tree.canRefund(Set.of("a", "K", "j1"), start, n("j1"), ENTWINED));
        assertFalse(tree.canRefund(allocated, start, n("a"), ENTWINED), "a 斷了，基石跟著斷");
    }

    @Test
    void unseenNodes() {
        assertTrue(tree.unseen(n("U")));
        assertFalse(tree.unseen(n("a")));
        assertFalse(tree.canAllocate(Set.of("a"), start, n("U"), ENTWINED));
        assertTrue(tree.canAllocate(Set.of("a"), start, n("U"), Set.of(PassiveTree.RULE_UNSEEN_PATH)));
        final JsonObject json = TreeFeed.staticJson(tree);
        int flagged = 0;
        for (final JsonElement element : json.getAsJsonArray("nodes")) {
            if (element.getAsJsonObject().has("u")) {
                assertEquals("U", element.getAsJsonObject().get("i").getAsString());
                flagged++;
            }
        }
        assertEquals(1, flagged);
        assertEquals(PassiveTree.ENTWINED_RADIUS, json.get("jr").getAsDouble());
    }

    @Test
    void realTreeHas176Unseen() throws Exception {
        final PassiveTree real = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        assertEquals(176, real.nodes().stream().filter(real::unseen).count());
        final JsonObject json = TreeFeed.staticJson(real);
        long flagged = 0;
        for (final JsonElement element : json.getAsJsonArray("nodes")) {
            flagged += element.getAsJsonObject().has("u") ? 1 : 0;
        }
        assertEquals(176, flagged);
    }

    @Test
    void rulesOfModifiers() {
        final List<Modifier> mods = List.of(new Modifier(PassiveTree.RULE_ENTWINED, ModType.FLAT, 1, Set.of(), "t"),
                new Modifier(PassiveTree.RULE_UNSEEN_PATH, ModType.FLAT, 0, Set.of(), "t"),
                new Modifier("life.max", ModType.FLAT, 10, Set.of(), "t"));
        assertEquals(Set.of(PassiveTree.RULE_ENTWINED), PassiveTree.rulesOf(mods), "數值 0 的、不是規則的不算");
        assertEquals(List.of("sorceress", "marauder"), List.copyOf(PassiveTree.PATH_STARTS.values()));
    }
}
