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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // 2026-10-26：永恆珠寶的族、征服者與基石（網頁換征服者時顯示用）
        final JsonObject timeless = root.getAsJsonObject("timeless");
        assertEquals(1500, timeless.get("radius").getAsDouble());
        final JsonObject kalguur = timeless.getAsJsonArray("legions").get(0).getAsJsonObject();
        assertEquals(1, kalguur.get("code").getAsInt());
        assertEquals("heroic_tragedy", kalguur.get("unique").getAsString());
        assertEquals("循環教義", kalguur.getAsJsonArray("conquerors").get(1).getAsJsonObject().get("keystone").getAsString());
        assertEquals("undying_hate", timeless.getAsJsonArray("legions").get(1).getAsJsonObject().get("unique").getAsString());
        int i = 0;
        for (final PassiveNode node : tree.ordered()) {
            final JsonObject object = nodes.get(i++).getAsJsonObject();
            assertEquals(node.id(), object.get("id").getAsString());
            assertEquals(node.x(), object.get("x").getAsInt());
            assertEquals(node.y(), object.get("y").getAsInt());
            assertFalse(object.get("name").getAsString().isEmpty());
            // 2026-10：屬性點帶 attr（+幾），其他節點沒有這個欄位
            assertEquals(node.attributeChoice() > 0, object.has("attr"), node.id());
            if (node.attributeChoice() > 0) {
                assertEquals(node.attributeChoice(), object.get("attr").getAsInt());
            }
        }
        // 職業的主屬性：屬性點沒選的時候算它（混沌術士是智慧）
        assertEquals("int", classes.get(0).getAsJsonObject().get("attr").getAsString());
        assertEquals("str", PlannerData.mainAttribute("marauder"));
        assertEquals("dex", PlannerData.mainAttribute("ranger"));
        assertEquals("str", PlannerData.mainAttribute("mercenary"), "力量跟敏捷一樣高：照力量");
        assertEquals("dex", PlannerData.mainAttribute("monk"), "敏捷跟智慧一樣高：照敏捷");
        assertEquals("str", PlannerData.mainAttribute("druid"));
        // 沒給昇華：空的清單。2026-10-26 照 PoE2：珠寶孔的範圍是原作單位（遺時珠寶小 1000、中 1150、大 1300，永恆珠寶 1500），
        // 節點帶原作座標 px/py 算距離（這次之前是格子：基本 2 格，更大 3、巨大 4）
        assertEquals(0, root.getAsJsonArray("ascendancies").size());
        final com.google.gson.JsonArray radii = root.getAsJsonArray("jewelRadii");
        assertEquals(List.of(1000.0, 1150.0, 1300.0, 1500.0), List.of(radii.get(0).getAsDouble(), radii.get(1).getAsDouble(),
                radii.get(2).getAsDouble(), radii.get(3).getAsDouble()));
        final PassiveNode first = tree.ordered().getFirst();
        assertEquals(Math.round(tree.position(first)[0]), nodes.get(0).getAsJsonObject().get("px").getAsLong());
        assertEquals(Math.round(tree.position(first)[1]), nodes.get(0).getAsJsonObject().get("py").getAsLong());
    }

    /** 2026-10：昇華預覽的資料——每個昇華職業的分支與每個天賦的名稱、點數、效果說明。 */
    @Test
    void ascendanciesTravelWithTheirBranches() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        final PlannerData.AscendancyInfo info = new PlannerData.AscendancyInfo("stormcaller", "witch", "喚雷者", "#7fb3ff", "閃電與感電",
                List.of(List.of(new PlannerData.AscendancyNode("雷心", 2, List.of("增加 20% 閃電傷害"), null),
                                new PlannerData.AscendancyNode("雷殛", 4, List.of("感電效果 +30%", "擊中感電的敵人 +10% 暴擊率"), "要先配雷心")),
                        List.of(new PlannerData.AscendancyNode("靜電", 2, List.of("+10% 感電機率"), ""))));
        final byte[] bytes = PlannerData.json(tree, List.of(new PlannerData.ClassInfo("witch", "混沌術士", "#aa55ff")), 100, 1, 24, List.of(info), 8);
        final JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(8, root.get("ascendancyPoints").getAsInt());
        final JsonObject first = root.getAsJsonArray("ascendancies").get(0).getAsJsonObject();
        assertEquals("stormcaller", first.get("id").getAsString());
        assertEquals("witch", first.get("class").getAsString());
        assertEquals("#7fb3ff", first.get("color").getAsString());
        final JsonArray branches = first.getAsJsonArray("branches");
        assertEquals(2, branches.size());
        final JsonObject second = branches.get(0).getAsJsonArray().get(1).getAsJsonObject();
        assertEquals("雷殛", second.get("name").getAsString());
        assertEquals(4, second.get("cost").getAsInt());
        assertEquals(2, second.getAsJsonArray("lines").size());
        assertEquals("要先配雷心", second.get("note").getAsString());
        assertFalse(branches.get(1).getAsJsonArray().get(0).getAsJsonObject().has("note"), "空的補充不寫");
    }

    /** 2026-10-26 照 PoE2 的昇華樹會分岔：每個天賦帶 requires（任一個配了就能配）、depth、row、「選一個」的選項。 */
    @Test
    void ascendancyNodesCarryTheirBranching() throws IOException {
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        final PlannerData.AscendancyInfo info = new PlannerData.AscendancyInfo("gemling", "mercenary", "嵌晶", "#7fb3ff", "寶石",
                List.of(List.of(new PlannerData.AscendancyNode("技能寶石品質", 1, List.of("所有技能品質 +2%"), null, "quality", List.of(), 0, 0,
                                        null, false, false),
                                new PlannerData.AscendancyNode("嵌體寶石", 1, List.of(), null, "implanted", List.of("quality"), 1, 0, null, true, false),
                                new PlannerData.AscendancyNode("強化鑲嵌物", 0, List.of("所有需求力量的技能等級 +2"), null, "bolster",
                                        List.of("implanted"), 2, 0, "implanted", false, false)),
                        List.of(new PlannerData.AscendancyNode("機動鑲嵌物", 0, List.of("所有需求敏捷的技能等級 +2"), null, "motoric",
                                List.of("implanted"), 2, 1, "implanted", false, false))));
        final byte[] bytes = PlannerData.json(tree, List.of(new PlannerData.ClassInfo("mercenary", "重弩手", "#d9a066")), 100, 1, 24, List.of(info), 8);
        final JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        final JsonArray branches = root.getAsJsonArray("ascendancies").get(0).getAsJsonObject().getAsJsonArray("branches");
        final JsonObject quality = branches.get(0).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(0, quality.getAsJsonArray("requires").size(), "接在起點");
        assertEquals(0, quality.get("depth").getAsInt());
        final JsonObject implanted = branches.get(0).getAsJsonArray().get(1).getAsJsonObject();
        assertEquals("quality", implanted.getAsJsonArray("requires").get(0).getAsString());
        assertTrue(implanted.get("choice").getAsBoolean());
        final JsonObject motoric = branches.get(1).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(2, motoric.get("depth").getAsInt(), "分岔出去的那一排從第 2 層開始");
        assertEquals(1, motoric.get("row").getAsInt());
        assertEquals("implanted", motoric.get("optionOf").getAsString());
        assertFalse(quality.has("optionOf"));
    }
}
