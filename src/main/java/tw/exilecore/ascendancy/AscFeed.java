package tw.exilecore.ascendancy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.clientmod.UiFeed;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-11-02 照站長（D 組）：昇華天賦的原生畫面（模組 {@code PassiveTreeScreen} 的昇華模式）的伺服器這一邊。
 * 這次之前只有箱子選單 {@link AscendancyTreeMenu}（往右長的小樹，太高要 ▲ ▼ 捲）；沒裝模組的人照舊用它。
 * <p>
 * 昇華的樹很小（十幾個天賦），而且說明跟配了沒有關係（亮暗、技能說明），所以沒有靜態資料：每次狀態都帶整棵樹
 * （位置照箱子選單的排法：第幾層往右、第幾排往下；連線照 requires）。動作 alloc（一個天賦，跟箱子選單一樣要相連）、
 * refund（花金幣的模組會先要玩家再按一次確認）、skill（看賦予的技能，箱子選單）。
 */
public final class AscFeed implements UiFeed.Source {

    static final String KIND = "asc";
    static final String ROOT = "@root";
    /** 一層、一排在畫面上隔幾個單位（跟天賦樹的座標差不多大）。 */
    static final int STEP_X = 320;
    static final int STEP_Y = 240;

    private final ExileCore plugin;

    private AscFeed(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** UiFeed 用。 */
    public static UiFeed.Source create(final ExileCore plugin) {
        return new AscFeed(plugin);
    }

    /** 有裝模組的玩家打開原生畫面（回傳 true）；不然回傳 false，照舊開箱子選單。 */
    static boolean openNative(final ExileCore plugin, final Player player) {
        final var mod = plugin.clientMod();
        return mod != null && mod.ui() != null && mod.ui().open(player, KIND, null);
    }

    private AscendancyService service() {
        return plugin.ascendancy();
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String flag() {
        return UiFeed.FLAG_TREE;
    }

    @Override
    public String problem(final Player player) {
        final AscendancyService service = service();
        if (service == null) {
            return "昇華模組沒有啟用";
        }
        if (!service.ready(player)) {
            return "先選一個角色（/char）";
        }
        return service.ascension(player).chosen() == null ? "還沒有選昇華職業（/ascend）" : null;
    }

    /** 純函式：天賦在畫面上的位置 {x, y}（起點在第 -1 層、所有排的中間）。 */
    static int[] position(final int depth, final int row) {
        return new int[] {(depth + 1) * STEP_X, row * STEP_Y};
    }

    @Override
    public JsonObject state(final Player player, final JsonObject args) {
        final AscendancyService service = service();
        final Ascension ascension = service.ascension(player);
        final AscendancyConfig.Ascendancy chosen = ascension.chosen();
        final JsonObject root = new JsonObject();
        root.addProperty("inline", true);
        root.addProperty("title", chosen.colored());
        final List<AscendancyConfig.Cell> cells = chosen.layout();
        int rows = 1;
        for (final AscendancyConfig.Cell cell : cells) {
            rows = Math.max(rows, cell.row() + 1);
        }
        final JsonArray nodes = new JsonArray();
        final Map<String, Integer> index = new HashMap<>();
        final JsonObject start = new JsonObject();
        start.addProperty("i", ROOT);
        start.addProperty("x", 0);
        start.addProperty("y", Math.round((rows - 1) * STEP_Y / 2.0));
        start.addProperty("k", "o");
        start.addProperty("n", chosen.colored());
        final JsonArray startLines = new JsonArray();
        startLines.add("<gray><i>" + AscendancyService.escape(chosen.summary()));
        start.add("d", startLines);
        nodes.add(start);
        index.put(ROOT, 0);
        final JsonArray owned = new JsonArray();
        final JsonArray avail = new JsonArray();
        final JsonArray fixed = new JsonArray();
        final JsonObject refundCost = new JsonObject();
        for (final AscendancyConfig.Cell cell : cells) {
            final AscendancyConfig.Node node = cell.node();
            if (index.containsKey(node.id())) {
                continue;
            }
            index.put(node.id(), index.size());
            final boolean mine = ascension.isAllocated(node);
            final String problem = mine ? null : ascension.allocateProblem(node);
            final List<String> lines = AscendancyLayout.describe(chosen, node, mine || problem == null, service.skillLookup());
            if (mine) {
                owned.add(node.id());
                final String refund = ascension.refundProblem(node);
                if (refund != null) {
                    fixed.add(node.id());
                    lines.add("<dark_gray>" + AscendancyService.escape(refund) + "才能退這個");
                } else {
                    final long cost = service.refundCost(player, node);
                    refundCost.addProperty(node.id(), cost);
                    lines.add(cost <= 0 ? "<yellow>右鍵：退掉（免費）"
                            : String.format(Locale.ROOT, "<yellow>右鍵：退掉（<#f5b942>%,d<yellow> 金幣，再按一次確認）", cost));
                }
            } else if (problem == null) {
                avail.add(node.id());
                lines.add(node.option() ? "<yellow>左鍵：選這個" : "<yellow>左鍵：配點");
            } else {
                lines.add("<red>" + AscendancyService.escape(problem));
            }
            if (!node.grantedSkills().isEmpty()) {
                lines.add("<gray>Shift＋左鍵：看賦予的技能");
            }
            final int[] at = position(cell.depth(), cell.row());
            final JsonObject object = new JsonObject();
            object.addProperty("i", node.id());
            object.addProperty("x", at[0]);
            object.addProperty("y", at[1]);
            object.addProperty("k", node.notable() ? "n" : "s");
            object.addProperty("n", (node.notable() ? "<" + chosen.color() + ">" : "<white>") + AscendancyService.escape(node.name()));
            object.addProperty("c", node.cost());
            final JsonArray lineArray = new JsonArray();
            lines.forEach(lineArray::add);
            object.add("d", lineArray);
            if (!node.grantedSkills().isEmpty()) {
                object.addProperty("skill", true);
            }
            nodes.add(object);
        }
        final JsonArray links = new JsonArray();
        for (final AscendancyConfig.Cell cell : cells) {
            final AscendancyConfig.Node node = cell.node();
            final Integer to = index.get(node.id());
            if (node.requires().isEmpty()) {
                links.add(0);
                links.add(to);
            }
            for (final String parent : node.requires()) {
                final Integer from = index.get(parent);
                if (from != null) {
                    links.add(from);
                    links.add(to);
                }
            }
        }
        root.add("nodes", nodes);
        root.add("links", links);
        root.addProperty("start", ROOT);
        root.add("seeds", new JsonArray());
        root.add("owned", owned);
        root.add("avail", avail);
        root.add("locked", new JsonArray());
        root.add("hidden", new JsonArray());
        root.add("fixed", fixed);
        root.add("rc", refundCost);
        root.addProperty("gold", plugin.players().get(player).gold()); // 2026-11-02 照站長：退點確認寫你有多少金幣
        root.addProperty("points", ascension.available());
        final JsonArray info = new JsonArray();
        info.add("<gray>昇華點：共 <white>" + ascension.points() + "</white>，已配 <white>" + ascension.spent() + "</white>，可配 <white>" + ascension.available());
        info.add("<dark_gray>每個試煉第一次完成給昇華點");
        info.add(String.format(Locale.ROOT, "<gray>你有 <#f5b942>%,d<gray> 金幣", plugin.players().get(player).gold()));
        root.add("info", info);
        root.add("use", new JsonObject());
        root.add("over", new JsonObject());
        root.add("jewels", new JsonArray());
        return root;
    }

    @Override
    public String signature(final Player player) {
        final AscendancyService service = service();
        if (service == null || !service.ready(player)) {
            return null;
        }
        final Ascension ascension = service.ascension(player);
        return ascension.allocated().size() + ":" + ascension.available() + ":" + ascension.points() + ":"
                + plugin.players().get(player).progress().get(Ascension.NODES) + ":" + plugin.players().get(player).gold();
    }

    @Override
    public String action(final Player player, final String action, final JsonObject message) {
        final AscendancyService service = service();
        final String id = message.has("id") && message.get("id").isJsonPrimitive() ? message.get("id").getAsString() : "";
        final AscendancyConfig.Ascendancy chosen = service.ascension(player).chosen();
        AscendancyConfig.Node node = null;
        for (final AscendancyConfig.Node candidate : chosen.nodes()) {
            if (candidate.id().equals(id)) {
                node = candidate;
            }
        }
        if (node == null) {
            return null;
        }
        switch (action) {
            case "alloc" -> {
                return result(player, service.allocate(player, id), Sound.BLOCK_AMETHYST_BLOCK_CHIME, "<green>配了 " + AscendancyService.escape(node.name()));
            }
            case "refund" -> {
                return result(player, service.refund(player, id), Sound.UI_BUTTON_CLICK, "<yellow>退了 " + AscendancyService.escape(node.name()));
            }
            case "skill" -> {
                service.openSkillPreview(player, node, () -> {
                    if (!openNative(plugin, player)) {
                        new AscendancyTreeMenu(plugin, player, service).open();
                    }
                });
                if (plugin.clientMod() != null && plugin.clientMod().ui() != null) {
                    plugin.clientMod().ui().closed(player);
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static String result(final Player player, final String problem, final Sound success, final String ok) {
        if (problem != null) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return "<red>" + AscendancyService.escape(problem);
        }
        player.playSound(player.getLocation(), success, 0.8f, 1.2f);
        return ok;
    }

    @Override
    public void chest(final Player player) {
        new AscendancyTreeMenu(plugin, player, service()).open();
    }
}
