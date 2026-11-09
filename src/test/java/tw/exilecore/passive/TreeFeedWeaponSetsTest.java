package tw.exilecore.passive;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09 照站長「client mod 的天賦畫面也要能點武器組的組」：狀態帶兩組武器組天賦、配武器組路徑先整條檢查。 */
class TreeFeedWeaponSetsTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws Exception {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    @Test
    void stateCarriesBothWeaponSets() {
        final JsonObject root = new JsonObject();
        TreeFeed.weaponSets(root, 2, 24, Set.of("b", "a"), Set.of("c"));
        assertEquals(2, root.get("wsa").getAsInt());
        assertEquals(24, root.get("wscap").getAsInt());
        assertEquals(2, root.get("wsu1").getAsInt());
        assertEquals(1, root.get("wsu2").getAsInt());
        assertEquals("a", root.getAsJsonArray("ws1").get(0).getAsString(), "照代號排序（簽章、快取穩定）");
        assertEquals("c", root.getAsJsonArray("ws2").get(0).getAsString());
        final JsonObject none = new JsonObject();
        TreeFeed.weaponSets(none, 0, 24, Set.of(), Set.of());
        assertFalse(none.has("wsa"), "沒有武器組不寫（舊模組本來就不認得）");
    }

    @Test
    void weaponPathIsCheckedAsAWhole() {
        final PassiveNode start = tree.start("witch");
        final List<PassiveNode> plain = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        PassiveNode current = start;
        while (plain.size() < 3) {
            PassiveNode next = null;
            for (final PassiveNode neighbour : tree.neighbours(current)) {
                if (!neighbour.isStart() && !neighbour.isSocket() && seen.add(neighbour.id())) {
                    next = neighbour;
                    break;
                }
            }
            assertNotNull(next);
            plain.add(next);
            current = next;
        }
        assertNull(TreeFeed.weaponPathProblem(plain, 0, 24, "第 I 組"));
        assertNotNull(TreeFeed.weaponPathProblem(plain, 22, 24, "第 I 組"), "放不下整條就整條不配");
        assertTrue(TreeFeed.weaponPathProblem(List.of(start), 0, 24, "第 I 組").contains("起點"));
        final PassiveNode socket = tree.nodes().stream().filter(PassiveNode::isSocket).findFirst().orElseThrow();
        assertTrue(TreeFeed.weaponPathProblem(List.of(plain.getFirst(), socket), 0, 24, "第 II 組").contains("珠寶孔"));
    }
}
