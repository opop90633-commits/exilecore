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

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PlannerData() {
    }

    public static byte[] json(final PassiveTree tree, final List<ClassInfo> classes, final int levelCap, final int pointsPerLevel,
                              final int bonusPoints) {
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
            nodes.add(object);
        }
        root.add("nodes", nodes);
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }
}
