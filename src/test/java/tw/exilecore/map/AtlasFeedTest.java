package tw.exilecore.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.clientmod.UiWire;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（D 組）星圖天賦原生畫面的資料：整棵樹（起點、連線）與路徑檢查（相連、點數）。 */
class AtlasFeedTest {

    static final AtlasTree tree = new AtlasTree();

    @BeforeAll
    static void load() throws Exception {
        tree.load(Path.of("src/main/resources/data"));
    }

    @Test
    void treeJson() {
        final JsonObject json = AtlasFeed.treeJson(tree);
        final JsonArray nodes = json.getAsJsonArray("nodes");
        assertEquals(tree.size() + 1, nodes.size(), "多一個起點（星核）");
        assertEquals(AtlasFeed.ROOT, nodes.get(0).getAsJsonObject().get("i").getAsString());
        final JsonArray links = json.getAsJsonArray("links");
        int rootLinks = 0;
        for (int i = 0; i < links.size(); i += 2) {
            if (links.get(i).getAsInt() == 0) {
                rootLinks++;
            }
        }
        assertEquals(tree.nodes().stream().filter(tree::touchesRoot).count(), rootLinks, "接在星核的都連到起點");
        final byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
        System.out.println("星圖天賦靜態資料：" + raw.length + " 位元組，壓縮後 " + UiWire.deflate(raw).length);
    }

    @Test
    void summary() {
        final AtlasTree.Node far = tree.nodes().stream().filter(n -> tree.pathTo(List.of(), n).size() >= 3).findFirst().orElseThrow();
        final List<String> path = tree.pathTo(List.of(), far).stream().map(AtlasTree.Node::id).toList();
        final List<String> lines = AtlasFeed.summaryLines(tree, path);
        assertEquals("<#77aaff>整條路加起來：", lines.getFirst());
        final List<String> described = tree.bonus(path).describe();
        assertEquals(Math.min(10, described.size()) + 1 + (described.size() > 10 ? 1 : 0), lines.size(), "跟效果說明一樣多行");
        assertTrue(lines.get(1).endsWith(described.getFirst()));
        assertTrue(AtlasFeed.summaryLines(tree, List.of(path.getFirst())).isEmpty());
    }

    @Test
    void checkPath() {
        final AtlasTree.Node far = tree.nodes().stream().filter(n -> tree.pathTo(List.of(), n).size() >= 3).findFirst().orElseThrow();
        final List<String> path = tree.pathTo(List.of(), far).stream().map(AtlasTree.Node::id).toList();
        int cost = 0;
        for (final String id : path) {
            cost += tree.node(id).cost();
        }
        assertNull(AtlasFeed.checkPath(tree, List.of(), path, cost));
        assertNotNull(AtlasFeed.checkPath(tree, List.of(), path, cost - 1), "點數不夠");
        final List<String> reversed = new ArrayList<>(path);
        java.util.Collections.reverse(reversed);
        assertNotNull(AtlasFeed.checkPath(tree, List.of(), reversed, 999), "倒過來接不上");
        assertTrue(AtlasFeed.checkPath(tree, List.of(), List.of("nope"), 999).contains("改版"));
    }
}
