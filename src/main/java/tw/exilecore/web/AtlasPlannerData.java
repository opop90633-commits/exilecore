package tw.exilecore.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tw.exilecore.map.AtlasTree;

import java.nio.charset.StandardCharsets;

/**
 * 星圖天賦樹給網頁規劃器（web/atlas.html）的資料（2026-10-21）：版本、天賦點的規則、主幹、每個節點的座標、點數、效果說明。純邏輯。
 */
public final class AtlasPlannerData {

    private AtlasPlannerData() {
    }

    public static byte[] json(final AtlasTree tree) {
        final JsonObject root = new JsonObject();
        root.addProperty("version", tree.version());
        root.addProperty("totalCost", tree.totalCost());
        root.addProperty("rootX", AtlasTree.ROOT_X);
        root.addProperty("rootY", AtlasTree.ROOT_Y);
        final JsonObject points = new JsonObject();
        points.addProperty("perMap", tree.points().perMap());
        points.addProperty("perFinal", tree.points().perFinal());
        points.addProperty("perUber", tree.points().perUber());
        final JsonArray milestones = new JsonArray();
        for (final int tier : tree.points().tierMilestones()) {
            milestones.add(tier);
        }
        points.add("tierMilestones", milestones);
        root.add("points", points);
        final JsonObject trunks = new JsonObject();
        for (final AtlasTree.Trunk trunk : tree.trunks()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("name", trunk.name());
            entry.addProperty("color", trunk.color());
            entry.addProperty("description", trunk.description());
            trunks.add(trunk.id(), entry);
        }
        root.add("trunks", trunks);
        final JsonArray nodes = new JsonArray();
        for (final AtlasTree.Node node : tree.nodes()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("id", node.id());
            entry.addProperty("name", node.name());
            entry.addProperty("x", node.x());
            entry.addProperty("y", node.y());
            entry.addProperty("trunk", node.trunk());
            entry.addProperty("cost", node.cost());
            entry.addProperty("notable", node.notable());
            entry.addProperty("keystone", node.keystone());
            final JsonArray mods = new JsonArray();
            for (final AtlasTree.Mod mod : node.mods()) {
                mods.add(AtlasTree.describe(mod.stat(), mod.value()));
            }
            entry.add("mods", mods);
            final JsonArray drawbacks = new JsonArray();
            for (final AtlasTree.Mod mod : node.drawbacks()) {
                drawbacks.add(AtlasTree.describe(mod.stat(), mod.value()));
            }
            entry.add("drawbacks", drawbacks);
            final JsonArray stats = new JsonArray();
            for (final AtlasTree.Mod mod : node.mods()) {
                final JsonObject stat = new JsonObject();
                stat.addProperty("stat", mod.stat());
                stat.addProperty("value", mod.value());
                stats.add(stat);
            }
            for (final AtlasTree.Mod mod : node.drawbacks()) {
                final JsonObject stat = new JsonObject();
                stat.addProperty("stat", mod.stat());
                stat.addProperty("value", mod.value());
                stat.addProperty("drawback", true);
                stats.add(stat);
            }
            entry.add("stats", stats);
            nodes.add(entry);
        }
        root.add("nodes", nodes);
        return new Gson().toJson(root).getBytes(StandardCharsets.UTF_8);
    }
}
