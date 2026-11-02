package tw.exilecore.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.clientmod.UiFeed;
import tw.exilecore.clientmod.UiText;
import tw.exilecore.item.ItemRenderer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-11-02 照站長（D 組）：星圖（六角格）與星圖天賦的原生畫面（模組 {@code AtlasScreen}、{@code PassiveTreeScreen}）的伺服器這一邊。
 * 這次之前只有箱子選單 {@link AtlasMenu}（奇數列偏移模擬六角格，一次 45 格）與 {@link AtlasTreeMenu}；沒裝模組的人照舊用它們。
 * <ul>
 *   <li>星圖（tree = false）：每個角色的星圖是程序生成、無限大的，沒有共用的靜態資料；狀態裡直接帶看得到的格子（營地、解鎖的、
 *       旁邊的迷霧），每格的名稱、說明照箱子選單（{@link AtlasMenu#nativeCell}，Component 轉成簡單的顏色文字），加上畫法的旗標。
 *       最多 {@link #MAX_CELLS} 格（離畫面中心近的先）。動作 pick（左鍵：挑背包、倉庫的地圖開這一格；mark：只指定；clear：取消指定）。</li>
 *   <li>星圖天賦（tree = true）：靜態資料是整棵樹（座標、連線、說明、點數），照雜湊快取；狀態是點了哪些、能點哪些、點數。
 *       動作 alloc（一條路徑，點數要夠全部）、refund（免費，連不回中心的一起退）、use（單一信仰換事件）、reset（全部退回）。</li>
 * </ul>
 */
public final class AtlasFeed implements UiFeed.Source {

    public static final String KIND_ATLAS = "atlas";
    public static final String KIND_TREE = "atlastree";
    /** 星圖一次最多送幾格。 */
    static final int MAX_CELLS = 3000;
    /** 星圖天賦的起點（星核）在靜態資料裡的代號。 */
    static final String ROOT = "@root";
    /** 星圖天賦畫面上一格是幾個單位（跟天賦樹的 PoE2 座標差不多大，模組的縮放才一致）。 */
    static final int TREE_UNIT = 160;

    private final ExileCore plugin;
    private final boolean tree;
    private AtlasTree cachedTree;
    private byte[] cachedJson;

    public AtlasFeed(final ExileCore plugin, final boolean tree) {
        this.plugin = plugin;
        this.tree = tree;
    }

    private MapService service() {
        return plugin.maps();
    }

    @Override
    public String kind() {
        return tree ? KIND_TREE : KIND_ATLAS;
    }

    @Override
    public String flag() {
        return UiFeed.FLAG_ATLAS;
    }

    @Override
    public String problem(final Player player) {
        if (service() == null) {
            return "地圖模組沒有啟用（看主控台的錯誤訊息）";
        }
        if (!plugin.players().get(player).hasCharacter()) {
            return "先建立角色（/char）";
        }
        return null;
    }

    /** 有裝模組的玩家打開原生畫面（回傳 true）；不然回傳 false，照舊開箱子選單。focus 是星圖的中心（null = 上次打完的地圖）。 */
    public static boolean openNative(final ExileCore plugin, final Player player, final boolean tree, final long[] focus) {
        final var mod = plugin.clientMod();
        if (mod == null || mod.ui() == null) {
            return false;
        }
        JsonObject args = null;
        if (focus != null) {
            args = new JsonObject();
            args.addProperty("q", focus[0]);
            args.addProperty("r", focus[1]);
        }
        return mod.ui().open(player, tree ? KIND_TREE : KIND_ATLAS, args);
    }

    // ---- 靜態資料（星圖天賦）----

    @Override
    public byte[] staticJson(final Player player) {
        if (!tree || service() == null) {
            return null;
        }
        final AtlasTree current = service().tree();
        synchronized (this) {
            if (current != cachedTree || cachedJson == null) {
                cachedJson = treeJson(current).toString().getBytes(StandardCharsets.UTF_8);
                cachedTree = current;
            }
            return cachedJson;
        }
    }

    /** 純函式：星圖天賦整棵樹（起點 {@value #ROOT} 放第一個）。 */
    public static JsonObject treeJson(final AtlasTree tree) {
        final JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("kind", KIND_TREE);
        root.addProperty("tree", tree.version());
        root.addProperty("unit", TREE_UNIT);
        final JsonArray nodes = new JsonArray();
        final java.util.Map<String, Integer> index = new java.util.HashMap<>();
        final JsonObject core = new JsonObject();
        core.addProperty("i", ROOT);
        core.addProperty("x", AtlasTree.ROOT_X * TREE_UNIT);
        core.addProperty("y", AtlasTree.ROOT_Y * TREE_UNIT);
        core.addProperty("k", "o");
        core.addProperty("n", "星核");
        final JsonArray coreLines = new JsonArray();
        coreLines.add("<gray>起點：從這裡往外點");
        core.add("d", coreLines);
        core.addProperty("c", 0);
        nodes.add(core);
        index.put(ROOT, 0);
        for (final AtlasTree.Node node : tree.nodes()) {
            index.put(node.id(), index.size());
            final AtlasTree.Trunk trunk = tree.trunk(node.trunk());
            final String color = trunk == null ? "#f5b942" : trunk.color();
            final JsonObject object = new JsonObject();
            object.addProperty("i", node.id());
            object.addProperty("x", node.x() * TREE_UNIT);
            object.addProperty("y", node.y() * TREE_UNIT);
            object.addProperty("k", node.keystone() ? "k" : node.notable() ? "n" : "s");
            object.addProperty("n", ItemRenderer.escape(node.name()));
            object.addProperty("c", node.cost());
            object.addProperty("col", color);
            final JsonArray lines = new JsonArray();
            if (trunk != null && !"core".equals(trunk.id())) {
                lines.add("<" + color + ">" + ItemRenderer.escape(trunk.name()));
            }
            for (final AtlasTree.Mod mod : node.mods()) {
                lines.add("<white>" + ItemRenderer.escape(AtlasTree.describe(mod.stat(), mod.value())));
            }
            for (final AtlasTree.Mod mod : node.drawbacks()) {
                lines.add("<red>" + ItemRenderer.escape(AtlasTree.describe(mod.stat(), mod.value())));
            }
            lines.add("<dark_gray>" + (node.keystone() ? "核心・" : node.notable() ? "大天賦・" : "") + node.cost() + " 點　(" + node.x() + ", " + node.y() + ")");
            object.add("d", lines);
            nodes.add(object);
        }
        root.add("nodes", nodes);
        final JsonArray links = new JsonArray();
        final Set<String> seen = new HashSet<>();
        for (final AtlasTree.Node node : tree.nodes()) {
            if (tree.touchesRoot(node)) {
                links.add(0);
                links.add(index.get(node.id()));
            }
            for (final AtlasTree.Node other : tree.neighbours(node)) {
                final String key = node.id().compareTo(other.id()) < 0 ? node.id() + "|" + other.id() : other.id() + "|" + node.id();
                if (seen.add(key)) {
                    links.add(index.get(node.id()));
                    links.add(index.get(other.id()));
                }
            }
        }
        root.add("links", links);
        root.add("var", new JsonObject());
        root.add("starts", new JsonObject());
        return root;
    }

    // ---- 狀態 ----

    @Override
    public JsonObject state(final Player player, final JsonObject args) {
        return tree ? treeState(player) : atlasState(player, args);
    }

    private JsonObject treeState(final Player player) {
        final MapService service = service();
        final AtlasTree atlasTree = service.tree();
        final AtlasTree.Earned earned = service.atlasEarned(player);
        final List<String> allocated = service.atlasAllocated(player);
        final int left = Math.max(0, earned.total() - atlasTree.spent(allocated));
        final JsonObject root = new JsonObject();
        root.addProperty("start", ROOT);
        root.add("seeds", new JsonArray());
        final JsonArray owned = new JsonArray();
        allocated.forEach(owned::add);
        root.add("owned", owned);
        final JsonArray avail = new JsonArray();
        final JsonArray poor = new JsonArray();
        for (final AtlasTree.Node node : atlasTree.nodes()) {
            if (allocated.contains(node.id()) || !atlasTree.connected(node, allocated)) {
                continue;
            }
            (node.cost() <= left ? avail : poor).add(node.id());
        }
        root.add("avail", avail);
        root.add("poor", poor);
        root.add("locked", new JsonArray());
        root.add("hidden", new JsonArray());
        root.add("fixed", new JsonArray());
        root.addProperty("points", left);
        root.addProperty("refund", "<yellow>右鍵退回（免費，連不回中心的一起退）");
        final List<String> info = new ArrayList<>();
        info.add("<gray>天賦點：還沒用 <white>" + left + "<gray> / 總共 " + earned.total() + (earned.capped() ? " <dark_gray>（到上限了）" : ""));
        info.add("<gray>第 " + (AtlasSetMenu.activeSet(service.atlasData(player)) + 1) + " 組使用中<dark_gray>（右上換組，月卡）");
        final List<String> effects = service.atlasBonus(player).describe();
        for (int i = 0; i < Math.min(6, effects.size()); i++) {
            info.add("<green>• " + ItemRenderer.escape(effects.get(i)));
        }
        if (effects.size() > 6) {
            info.add("<dark_gray>…還有 " + (effects.size() - 6) + " 項");
        }
        root.add("info", strings(info));
        final JsonObject use = new JsonObject();
        for (final String id : allocated) {
            final AtlasTree.Node node = atlasTree.node(id);
            if (node != null && node.mods().stream().anyMatch(mod -> "atlas.chosen_event".equals(mod.stat()))) {
                use.addProperty(id, "<yellow>選的事件：" + MapEventConfig.sourceName(MapEventConfig.uniqueSource(service.faithKind(player)))
                        + "<gray>（左鍵換下一種）");
            }
        }
        root.add("use", use);
        // 2026-11-02 照站長：右上的組別鈕（月卡；沒有月卡只能切回第 1 組）
        root.add("sets", tw.exilecore.passive.TreeFeed.sets(AtlasSetMenu.activeSet(service.atlasData(player)),
                plugin.sponsors() != null && plugin.sponsors().cardActive(player)));
        root.add("over", new JsonObject());
        root.add("jewels", new JsonArray());
        root.addProperty("reset", !allocated.isEmpty());
        return root;
    }

    private JsonObject atlasState(final Player player, final JsonObject args) {
        final MapService service = service();
        final AtlasMenu menu = new AtlasMenu(plugin, player, service);
        final AtlasProgress atlas = service.atlasProgress(player);
        final AtlasGrid grid = atlas.grid();
        final AtlasGrid.Cell chosen = menu.prepareNative(atlas);
        long[] center = null;
        if (args != null && args.has("q") && args.has("r")) {
            center = new long[] {args.get("q").getAsLong(), args.get("r").getAsLong()};
            args.remove("q"); // 只在打開時移一次
            args.remove("r");
        }
        final Set<Long> unlocked = atlas.unlocked();
        final Set<Long> keys = new LinkedHashSet<>();
        keys.add(AtlasGrid.key(0, 0));
        for (final long key : unlocked) {
            keys.add(key);
            final int q = AtlasGrid.qOf(key);
            final int r = AtlasGrid.rOf(key);
            for (final int[] direction : AtlasGrid.DIRECTIONS) {
                final long near = AtlasGrid.key(q + direction[0], r + direction[1]);
                if (!unlocked.contains(near) && !grid.cell(q + direction[0], r + direction[1]).isEmpty()) {
                    keys.add(near); // 迷霧
                }
            }
        }
        final long[] last = AtlasProgress.decodeOne(service.atlasData(player).get(AtlasProgress.LAST_MAP_KEY));
        final long[] focus = center != null ? center : last != null ? last : new long[] {0, 0};
        final List<Long> ordered = new ArrayList<>(keys);
        if (ordered.size() > MAX_CELLS) {
            ordered.sort(Comparator.comparingInt(key -> AtlasGrid.distance(AtlasGrid.qOf(key), AtlasGrid.rOf(key), (int) focus[0], (int) focus[1])));
            ordered.subList(MAX_CELLS, ordered.size()).clear();
        }
        final JsonArray cells = new JsonArray();
        for (final long key : ordered) {
            final int q = AtlasGrid.qOf(key);
            final int r = AtlasGrid.rOf(key);
            final AtlasGrid.Cell cell = grid.cell(q, r);
            final boolean camp = q == 0 && r == 0;
            final boolean isUnlocked = camp || unlocked.contains(key);
            final JsonObject object = new JsonObject();
            object.addProperty("q", q);
            object.addProperty("r", r);
            if (!isUnlocked) {
                object.addProperty("k", "fog");
                object.addProperty("n", "<#555555>迷霧（" + AtlasMenu.where(q, r) + "）");
                final JsonArray lore = new JsonArray();
                lore.add("<#aaaaaa>完成旁邊的格子就會看到");
                object.add("l", lore);
                cells.add(object);
                continue;
            }
            final AtlasMenu.NativeCell shown = menu.nativeCell(atlas, chosen, q, r);
            object.addProperty("k", camp ? "camp" : switch (cell.kind()) {
                case BOSS -> "boss";
                case TOWER -> "tower";
                case CITADEL -> "citadel";
                default -> "map";
            });
            final boolean done = camp || atlas.isCompleted(q, r);
            if (done) {
                object.addProperty("done", true);
            }
            if (!camp && cell.isMap()) {
                object.addProperty("t", cell.tier());
            }
            if (!done && cell.purified()) {
                object.addProperty("ring", "purify");
            } else if (shown.corrupt()) {
                object.addProperty("ring", "corrupt");
            } else if (!done && shown.coverage() > 0) {
                object.addProperty("ring", shown.coverage() == 2 ? "tablet" : "range");
            }
            if (chosen != null && chosen.q() == q && chosen.r() == r) {
                object.addProperty("ch", true);
            }
            final ItemStack icon = shown.icon();
            object.addProperty("n", UiText.of(icon.effectiveName(), 0xFFFFFF));
            final List<Component> lore = icon.lore();
            object.add("l", strings(UiText.lines(lore == null ? List.of() : lore, 0xAA00AA)));
            cells.add(object);
        }
        final JsonObject root = new JsonObject();
        root.add("cells", cells);
        // 塔範圍的大外框：送塔的位置與半徑，模組自己算哪些格子在範圍內（空格、迷霧也算，框才連得起來；跟箱子選單一樣營地不算）
        final JsonArray towers = new JsonArray();
        final List<AtlasGrid.Cell> rangeTowers = new ArrayList<>(atlas.completedTowers());
        rangeTowers.addAll(atlas.openTowers());
        for (final AtlasGrid.Cell tower : rangeTowers) {
            final JsonArray at = new JsonArray();
            at.add(tower.q());
            at.add(tower.r());
            towers.add(at);
        }
        root.add("towers", towers);
        root.addProperty("radius", service.atlasConfig().tower().radius() + service.atlasBonus(player).count("atlas.tower.radius"));
        final JsonArray focusArray = new JsonArray();
        focusArray.add(focus[0]);
        focusArray.add(focus[1]);
        root.add("focus", focusArray);
        if (chosen != null) {
            final JsonArray chosenArray = new JsonArray();
            chosenArray.add(chosen.q());
            chosenArray.add(chosen.r());
            root.add("chosen", chosenArray);
        }
        final ItemStack info = menu.nativeInfo(atlas, chosen);
        final List<String> infoLines = new ArrayList<>();
        infoLines.add(UiText.of(info.effectiveName(), 0xFFFFFF));
        infoLines.addAll(UiText.lines(info.lore() == null ? List.of() : info.lore(), 0xAAAAAA));
        final ItemStack button = AtlasMenu.treeButton(player, service);
        infoLines.add("");
        infoLines.add(UiText.of(button.effectiveName(), 0xFFFFFF));
        root.add("info", strings(infoLines));
        root.addProperty("points", service.atlasUnspent(player));
        root.addProperty("cut", keys.size() > ordered.size());
        return root;
    }

    private static JsonArray strings(final List<String> lines) {
        final JsonArray array = new JsonArray();
        lines.forEach(array::add);
        return array;
    }

    /**
     * 純函式：星圖天賦路徑預覽「整條路加起來的效果」（照 {@link AtlasBonus#describe} 的順序與寫法，代價是紅色；最多 10 行）。
     * 2026-11-02 照站長；這次之前（箱子選單）只寫路上幾個、共幾點。
     */
    static List<String> summaryLines(final AtlasTree tree, final List<String> ids) {
        final List<String> lines = new ArrayList<>();
        final List<String> known = new ArrayList<>();
        for (final String id : ids) {
            if (tree.node(id) != null) {
                known.add(id);
            }
        }
        if (known.size() <= 1) {
            return lines;
        }
        final AtlasBonus bonus = tree.bonus(known);
        final List<String> total = new ArrayList<>();
        for (final String stat : AtlasTree.STATS.keySet()) {
            if (bonus.get(stat) > 0) {
                total.add("<white>　" + ItemRenderer.escape(AtlasTree.describe(stat, bonus.get(stat))));
            }
        }
        for (final String stat : AtlasTree.DRAWBACKS.keySet()) {
            if (bonus.get(stat) > 0) {
                total.add("<red>　" + ItemRenderer.escape(AtlasTree.describe(stat, bonus.get(stat))));
            }
        }
        if (total.isEmpty()) {
            return lines;
        }
        lines.add("<#77aaff>整條路加起來：");
        lines.addAll(total.subList(0, Math.min(10, total.size())));
        if (total.size() > 10) {
            lines.add("<dark_gray>　…還有 " + (total.size() - 10) + " 項");
        }
        return lines;
    }

    @Override
    public List<String> summary(final Player player, final List<String> path) {
        return tree && service() != null ? summaryLines(service().tree(), path) : List.of();
    }

    @Override
    public String signature(final Player player) {
        final MapService service = service();
        if (service == null) {
            return null;
        }
        if (tree) {
            return service.atlasAllocated(player).hashCode() + ":" + service.atlasUnspent(player) + ":" + service.faithKind(player)
                    + ":" + AtlasSetMenu.activeSet(service.atlasData(player)) + ":" + (plugin.sponsors() != null && plugin.sponsors().cardActive(player));
        }
        // 2026-11-02 照站長（星圖打開會卡）：只看星圖的進度（帳號進度裡還有委託、稱號…，那些變了不用重送整張星圖）；這次之前整份帳號進度算雜湊
        int hash = 0;
        for (final java.util.Map.Entry<String, String> entry : service.atlasData(player).entrySet()) {
            if (MapService.isAtlasKey(entry.getKey())) {
                hash += entry.hashCode();
            }
        }
        return hash + ":" + service.atlasUnspent(player);
    }

    // ---- 動作 ----

    @Override
    public String action(final Player player, final String action, final JsonObject message) {
        return tree ? treeAction(player, action, message) : atlasAction(player, action, message);
    }

    private String atlasAction(final Player player, final String action, final JsonObject message) {
        if (!"pick".equals(action) || !message.has("q") || !message.has("r")) {
            return null;
        }
        final MapService service = service();
        final int q = message.get("q").getAsInt();
        final int r = message.get("r").getAsInt();
        final String mode = message.has("mode") ? message.get("mode").getAsString() : "open";
        final AtlasProgress atlas = service.atlasProgress(player);
        if (!atlas.isUnlocked(q, r)) {
            return "<gray>這一格還沒解鎖（完成旁邊的格子）";
        }
        final AtlasGrid.Cell cell = atlas.grid().cell(q, r);
        if (!cell.isMap()) {
            return cell.kind() == AtlasGrid.Kind.TOWER ? "<gray>塔從地圖裝置的「塔與城塞」開" : "<gray>城塞從地圖裝置的「塔與城塞」開（第一次不用碎片）";
        }
        final MapConfig.MapDef map = service.config().find(cell.mapId());
        final String name = map == null ? String.valueOf(cell.mapId()) : map.name();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.5f);
        if ("clear".equals(mode)) {
            service.chooseCell(player, null);
            return "<gray>取消指定：開圖時自動選最近的未完成格";
        }
        service.chooseCell(player, cell);
        if ("open".equals(mode) && map != null) {
            if (AtlasMapPickMenu.anyFor(plugin, player, service, map)) {
                new AtlasMapPickMenu(plugin, player, service, map, cell).open(); // 挑哪一張地圖照舊用箱子選單
                if (plugin.clientMod() != null && plugin.clientMod().ui() != null) {
                    plugin.clientMod().ui().closed(player);
                }
                return null;
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
            return "<red>背包和倉庫都沒有 " + ItemRenderer.escape(name) + "<gray>（已經指定這一格：拿到這種地圖後從地圖裝置開）";
        }
        return "<yellow>下次開 " + ItemRenderer.escape(name) + " 用「" + AtlasMenu.where(q, r) + "」";
    }

    /** 純函式：檢查星圖天賦的路徑（依序每一步都連得到、總點數夠），回傳 null 表示可以，不然是問題。 */
    static String checkPath(final AtlasTree tree, final List<String> allocated, final List<String> path, final int left) {
        if (path.isEmpty() || path.size() > 200) {
            return "連不到中心";
        }
        final List<String> simulated = new ArrayList<>(allocated);
        int cost = 0;
        for (final String id : path) {
            final AtlasTree.Node node = tree.node(id);
            if (node == null) {
                return "樹改版了，請重新打開星圖天賦";
            }
            if (simulated.contains(id)) {
                return "「" + node.name() + "」已經點了";
            }
            if (!tree.connected(node, simulated)) {
                return "路徑接不上（配點可能剛變了），請再點一次";
            }
            simulated.add(id);
            cost += node.cost();
        }
        if (cost > left) {
            return "天賦點不夠：" + (path.size() > 1 ? "路上 " + path.size() + " 個共" : "要") + " " + cost + " 點，你還有 " + left + " 點";
        }
        return null;
    }

    private String treeAction(final Player player, final String action, final JsonObject message) {
        final MapService service = service();
        final AtlasTree atlasTree = service.tree();
        final List<String> allocated = service.atlasAllocated(player);
        switch (action) {
            case "set" -> {
                // 2026-11-02 照站長：原生星圖天賦右上的組別鈕（跟箱子選單的星圖天賦組同一套規則）
                final int set = message.has("set") && message.get("set").isJsonPrimitive() ? message.get("set").getAsInt() : -1;
                if (set == AtlasSetMenu.activeSet(service.atlasData(player))) {
                    return null;
                }
                final String problem = new AtlasSetMenu(plugin, player, service).switchTo(set);
                return problem == null ? "<#bcd4ff>星圖天賦換成第 " + (set + 1) + " 組" : "<red>" + ItemRenderer.escape(problem);
            }
            case "alloc" -> {
                final List<String> path = new ArrayList<>();
                if (message.has("path") && message.get("path").isJsonArray()) {
                    for (final JsonElement element : message.getAsJsonArray("path")) {
                        if (element.isJsonPrimitive()) {
                            path.add(element.getAsString());
                        }
                    }
                }
                final String problem = checkPath(atlasTree, allocated, path, service.atlasUnspent(player));
                if (problem != null) {
                    return fail(player, problem);
                }
                for (final String id : path) {
                    final String failed = service.allocateAtlas(player, id);
                    if (failed != null) {
                        return fail(player, failed);
                    }
                }
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.3f);
                final AtlasTree.Node last = atlasTree.node(path.getLast());
                return "<green>點了「" + ItemRenderer.escape(last.name()) + "」" + (path.size() > 1 ? "<gray>（連同路上的 " + (path.size() - 1) + " 個）" : "");
            }
            case "refund" -> {
                final String id = message.has("id") ? message.get("id").getAsString() : "";
                final AtlasTree.Node node = atlasTree.node(id);
                if (node == null || !allocated.contains(id)) {
                    return "<gray>這個天賦還沒點";
                }
                final List<String> removed = service.refundAtlas(player, id);
                int points = 0;
                for (final String other : removed) {
                    final AtlasTree.Node otherNode = atlasTree.node(other);
                    points += otherNode == null ? 0 : otherNode.cost();
                }
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f);
                return "<yellow>退回「" + ItemRenderer.escape(node.name()) + "」" + (removed.size() > 1 ? "（連同連不回中心的 " + (removed.size() - 1) + " 個）" : "")
                        + "<gray>，拿回 " + points + " 點";
            }
            case "use" -> {
                final String id = message.has("id") ? message.get("id").getAsString() : "";
                final AtlasTree.Node node = atlasTree.node(id);
                if (node == null || !allocated.contains(id) || node.mods().stream().noneMatch(mod -> "atlas.chosen_event".equals(mod.stat()))) {
                    return null;
                }
                final MapEventConfig.Kind next = service.cycleFaith(player);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
                return "<yellow>單一信仰：改選 " + MapEventConfig.sourceName(MapEventConfig.uniqueSource(next));
            }
            case "reset" -> {
                if (allocated.isEmpty()) {
                    return "<gray>還沒點任何天賦";
                }
                final int count = service.resetAtlas(player);
                player.playSound(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.6f, 1.0f);
                return "<yellow>退回了 " + count + " 個星圖天賦（免費）";
            }
            default -> {
                return null;
            }
        }
    }

    private static String fail(final Player player, final String problem) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
        return "<red>" + ItemRenderer.escape(problem);
    }

    @Override
    public void chest(final Player player) {
        if (tree) {
            new AtlasTreeMenu(plugin, player, service()).open();
        } else {
            new AtlasMenu(plugin, player, service()).open();
        }
    }
}
