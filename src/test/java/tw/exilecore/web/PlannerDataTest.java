package tw.exilecore.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 網頁規劃器的資料：節點順序＝代碼的位元順序、職業起點、點數規則。 */
class PlannerDataTest {

    @Test
    void nodesFollowTheCodeOrder() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        final byte[] bytes = PlannerData.json(tree, List.of(
                new PlannerData.ClassInfo("witch", "混沌術士", "#aa55ff"),
                new PlannerData.ClassInfo("nobody", "沒有起點的職業", "#ffffff")), 100, 1, 24);
        final JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(TreeCode.PREFIX, root.get("code").getAsString());
        assertEquals(tree.version(), root.get("version").getAsInt());
        assertEquals(100, root.get("levelCap").getAsInt());
        assertEquals(24, root.get("bonusPoints").getAsInt());

        final JsonArray classes = root.getAsJsonArray("classes");
        assertEquals(1, classes.size(), "沒有起點的職業不列出來");
        assertEquals(tree.start("witch").id(), classes.get(0).getAsJsonObject().get("start").getAsString());

        final JsonArray nodes = root.getAsJsonArray("nodes");
        assertEquals(tree.size(), nodes.size());
        int i = 0;
        for (final PassiveNode node : tree.ordered()) {
            final JsonObject object = nodes.get(i++).getAsJsonObject();
            assertEquals(node.id(), object.get("id").getAsString());
            assertEquals(node.x(), object.get("x").getAsInt());
            assertEquals(node.y(), object.get("y").getAsInt());
            assertFalse(object.get("name").getAsString().isEmpty());
        }
    }
}
