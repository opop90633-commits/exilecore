package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tw.exilecore.passive.PassiveNode;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.passive.TreeCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 網頁天賦規劃器要的資料（/tree/data.json）：節點（照檔案順序＝代碼的位元順序）、職業起點、點數規則。純邏輯。
 */
public final class PlannerData {

    /** 一個職業。color 是 #rrggbb。 */
    public record ClassInfo(String id, String name, String color) {
    }

    /** 一個昇華天賦（給規劃器的昇華預覽）。lines 是效果的說明；note 是補充（沒有是 null）。 */
    public record AscendancyNode(String name, int cost, List<String> lines, String note) {
        public AscendancyNode {
            lines = List.copyOf(lines);
        }
    }

    /** 一個昇華職業：branches 是每條分支（從第一個天賦往後接）。color 是 #rrggbb。 */
    public record AscendancyInfo(String id, String classId, String name, String color, String summary, List<List<AscendancyNode>> branches) {
        public AscendancyInfo {
            branches = List.copyOf(branches);
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PlannerData() {
    }

    /** 職業的主屬性（str／dex／int；一樣高的照力量、敏捷、智慧的順序）：屬性點沒選的時候就是它。不認得的職業算力量。 */
    static String mainAttribute(final String classId) {
        for (final tw.exilecore.character.CharacterClass type : tw.exilecore.character.CharacterClass.values()) {
            if (type.id().equalsIgnoreCase(classId)) {
                final int s = type.strength();
                final int d = type.dexterity();
                final int i = type.intelligence();
                return s >= d && s >= i ? "str" : d >= i ? "dex" : "int";
            }
        }
        return "str";
    }

    public static byte[] json(final PassiveTree tree, final List<ClassInfo> classes, final int levelCap, final int pointsPerLevel,
                              final int bonusPoints) {
        return json(tree, classes, levelCap, pointsPerLevel, bonusPoints, List.of(), 0);
    }

    /**
     * 同上，再帶昇華職業（2026-10：規劃器可以預覽每個職業的昇華天賦；只看，不進代碼）。
     *
     * @param ascendancyPoints 昇華點最多幾點
     */
    public static byte[] json(final PassiveTree tree, final List<ClassInfo> classes, final int levelCap, final int pointsPerLevel,
                              final int bonusPoints, final List<AscendancyInfo> ascendancies, final int ascendancyPoints) {
        final JsonObject root = new JsonObject();
        root.addProperty("code", TreeCode.PREFIX);
        root.addProperty("version", tree.version());
        root.addProperty("levelCap", levelCap);
        root.addProperty("pointsPerLevel", pointsPerLevel);
        root.addProperty("bonusPoints", bonusPoints);
        final JsonArray classArray = new JsonArray();
        final Map<String, String> starts = tree.starts();
        for (final ClassInfo info : classes) {
            final String start = starts.get(info.id().toLowerCase(Locale.ROOT));
            if (start == null) {
                continue;
            }
            final JsonObject object = new JsonObject();
            object.addProperty("id", info.id());
            object.addProperty("name", info.name());
            object.addProperty("color", info.color());
            object.addProperty("start", start);
            object.addProperty("attr", mainAttribute(info.id())); // 2026-10：屬性點沒選的時候算哪個屬性（跟遊戲一樣：職業的主屬性）
            classArray.add(object);
        }
        root.add("classes", classArray);
        final JsonArray nodes = new JsonArray();
        for (final PassiveNode node : tree.ordered()) {
            final JsonObject object = new JsonObject();
            object.addProperty("id", node.id());
            object.addProperty("x", node.x());
            object.addProperty("y", node.y());
            object.addProperty("type", node.type().name().toLowerCase(Locale.ROOT));
            object.addProperty("name", node.name().isEmpty() ? node.type().displayName() : node.name());
            final JsonArray lines = new JsonArray();
            for (final String line : node.description()) {
                lines.add(line);
            }
            object.add("lines", lines);
            if (node.attributeChoice() > 0) {
                object.addProperty("attr", node.attributeChoice()); // 2026-10：屬性點（+x 任一屬性），規劃器裡可以選力量／敏捷／智慧
            }
            nodes.add(object);
        }
        root.add("nodes", nodes);
        // 2026-10：珠寶孔周圍的範圍（遺時珠寶：基本幾格，「更大」「巨大」各多幾格；算法跟遊戲一樣：距離 ≤ 半徑 + 0.5）
        final JsonArray radii = new JsonArray();
        radii.add(tw.exilecore.passive.JewelSockets.BASE_RADIUS);
        radii.add(tw.exilecore.passive.JewelSockets.BASE_RADIUS + 1);
        radii.add(tw.exilecore.passive.JewelSockets.BASE_RADIUS + 2);
        root.add("jewelRadii", radii);
        root.addProperty("ascendancyPoints", ascendancyPoints);
        final JsonArray ascendancyArray = new JsonArray();
        for (final AscendancyInfo info : ascendancies) {
            final JsonObject object = new JsonObject();
            object.addProperty("id", info.id());
            object.addProperty("class", info.classId());
            object.addProperty("name", info.name());
            object.addProperty("color", info.color());
            object.addProperty("summary", info.summary() == null ? "" : info.summary());
            final JsonArray branches = new JsonArray();
            for (final List<AscendancyNode> branch : info.branches()) {
                final JsonArray branchArray = new JsonArray();
                for (final AscendancyNode node : branch) {
                    final JsonObject nodeObject = new JsonObject();
                    nodeObject.addProperty("name", node.name());
                    nodeObject.addProperty("cost", node.cost());
                    final JsonArray lines = new JsonArray();
                    for (final String line : node.lines()) {
                        lines.add(line);
                    }
                    nodeObject.add("lines", lines);
                    if (node.note() != null && !node.note().isBlank()) {
                        nodeObject.addProperty("note", node.note());
                    }
                    branchArray.add(nodeObject);
                }
                branches.add(branchArray);
            }
            object.add("branches", branches);
            ascendancyArray.add(object);
        }
        root.add("ascendancies", ascendancyArray);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }
}
