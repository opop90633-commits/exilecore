package tw.exilecore.passive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 整棵天賦樹：節點、位置索引、相鄰關係與配點規則。純資料，不依賴 Bukkit，可以在單元測試裡載入真實資料檔。
 * <p>
 * 規則（與 PoE 相同）：
 * <ul>
 *   <li>職業起點自動配置、不可退。</li>
 *   <li>要配一個節點，它必須與某個已配置的節點相鄰。</li>
 *   <li>要退一個節點，退掉後其他已配置的節點仍要全部連得到起點（不能把路切斷）。</li>
 * </ul>
 */
public final class PassiveTree {

    private final Map<String, PassiveNode> nodes = new LinkedHashMap<>();
    /** 節點在檔案裡的順序（規劃器代碼的位元順序）。 */
    private final List<PassiveNode> ordered = new ArrayList<>();
    /** 天賦樹的版本（tree.json 的 version）：節點增減時加一，舊的規劃代碼就不能用。 */
    private int version = 1;
    private final Map<Long, PassiveNode> byPosition = new HashMap<>();
    private final Map<String, String> starts = new HashMap<>();
    private int minX;
    private int maxX;
    private int minY;
    private int maxY;

    public static PassiveTree load(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(reader);
        }
    }

    public static PassiveTree load(final Reader reader) {
        final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        final PassiveTree tree = new PassiveTree();
        if (root.has("version")) {
            tree.version = root.get("version").getAsInt();
        }
        final JsonObject startsObject = root.getAsJsonObject("starts");
        if (startsObject != null) {
            for (final Map.Entry<String, JsonElement> entry : startsObject.entrySet()) {
                tree.starts.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsString());
            }
        }
        final JsonArray array = root.getAsJsonArray("nodes");
        if (array == null || array.isEmpty()) {
            throw new IllegalArgumentException("tree.json：沒有 nodes");
        }
        boolean first = true;
        for (final JsonElement element : array) {
            final PassiveNode node = parseNode(element.getAsJsonObject());
            if (tree.nodes.put(node.id(), node) != null) {
                throw new IllegalArgumentException("tree.json：重複的節點 " + node.id());
            }
            tree.ordered.add(node);
            final long key = key(node.x(), node.y());
            if (tree.byPosition.put(key, node) != null) {
                throw new IllegalArgumentException("tree.json：格子 (" + node.x() + ", " + node.y() + ") 有兩個節點");
            }
            if (first) {
                tree.minX = tree.maxX = node.x();
                tree.minY = tree.maxY = node.y();
                first = false;
            } else {
                tree.minX = Math.min(tree.minX, node.x());
                tree.maxX = Math.max(tree.maxX, node.x());
                tree.minY = Math.min(tree.minY, node.y());
                tree.maxY = Math.max(tree.maxY, node.y());
            }
        }
        for (final Map.Entry<String, String> entry : tree.starts.entrySet()) {
            final PassiveNode node = tree.nodes.get(entry.getValue());
            if (node == null || !node.isStart()) {
                throw new IllegalArgumentException("tree.json：職業 " + entry.getKey() + " 的起點 " + entry.getValue() + " 不存在或不是起點");
            }
        }
        return tree;
    }

    private static PassiveNode parseNode(final JsonObject object) {
        final String id = require(object, "id").getAsString();
        final PassiveNode.Type type = PassiveNode.Type.parse(require(object, "type").getAsString());
        final String name = object.has("name") ? object.get("name").getAsString() : "";
        final int x = require(object, "x").getAsInt();
        final int y = require(object, "y").getAsInt();
        final String icon = object.has("icon") ? object.get("icon").getAsString() : "GRAY_STAINED_GLASS_PANE";
        final List<Modifier> modifiers = new ArrayList<>();
        final JsonArray stats = object.getAsJsonArray("stats");
        if (stats != null) {
            for (final JsonElement element : stats) {
                final JsonObject stat = element.getAsJsonObject();
                final Set<String> tags = new HashSet<>();
                final JsonArray tagArray = stat.getAsJsonArray("tags");
                if (tagArray != null) {
                    for (final JsonElement tag : tagArray) {
                        tags.add(tag.getAsString());
                    }
                }
                modifiers.add(new Modifier(require(stat, "stat").getAsString(),
                        ModType.valueOf(stat.has("type") ? stat.get("type").getAsString().toUpperCase(Locale.ROOT) : "FLAT"),
                        require(stat, "value").getAsDouble(), tags, "天賦 " + (name.isEmpty() ? id : name)));
            }
        }
        final List<String> description = new ArrayList<>();
        final JsonArray descriptionArray = object.getAsJsonArray("description");
        if (descriptionArray != null) {
            for (final JsonElement line : descriptionArray) {
                description.add(line.getAsString());
            }
        }
        final String start = object.has("start") ? object.get("start").getAsString().toLowerCase(Locale.ROOT) : null;
        return new PassiveNode(id, type, name, x, y, icon, modifiers, description, start);
    }

    private static JsonElement require(final JsonObject object, final String key) {
        final JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("tree.json：節點缺少欄位 " + key + "：" + object);
        }
        return element;
    }

    private static long key(final int x, final int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    // ---- 查詢 ----

    public PassiveNode node(final String id) {
        return nodes.get(id);
    }

    /** 節點在檔案裡的順序。 */
    public List<PassiveNode> ordered() {
        return java.util.Collections.unmodifiableList(ordered);
    }

    public int version() {
        return version;
    }

    public PassiveNode at(final int x, final int y) {
        return byPosition.get(key(x, y));
    }

    public Collection<PassiveNode> nodes() {
        return nodes.values();
    }

    public int size() {
        return nodes.size();
    }

    public int minX() {
        return minX;
    }

    public int maxX() {
        return maxX;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    /** 某個職業的起點節點；沒有這個職業就回傳 null。 */
    public PassiveNode start(final String classId) {
        final String id = starts.get(classId.toLowerCase(Locale.ROOT));
        return id == null ? null : nodes.get(id);
    }

    public Map<String, String> starts() {
        return Map.copyOf(starts);
    }

    /** 上下左右相鄰的節點。 */
    public List<PassiveNode> neighbours(final PassiveNode node) {
        final List<PassiveNode> list = new ArrayList<>(4);
        addIfPresent(list, node.x() + 1, node.y());
        addIfPresent(list, node.x() - 1, node.y());
        addIfPresent(list, node.x(), node.y() + 1);
        addIfPresent(list, node.x(), node.y() - 1);
        return list;
    }

    private void addIfPresent(final List<PassiveNode> list, final int x, final int y) {
        final PassiveNode node = at(x, y);
        if (node != null) {
            list.add(node);
        }
    }

    // ---- 規則 ----

    /** 這個節點現在能不能配：還沒配、不是起點、與某個已配置節點（或起點）相鄰。 */
    public boolean canAllocate(final Set<String> allocated, final PassiveNode start, final PassiveNode node) {
        if (node.isStart() || allocated.contains(node.id())) {
            return false;
        }
        for (final PassiveNode neighbour : neighbours(node)) {
            if (neighbour.id().equals(start.id()) || allocated.contains(neighbour.id())) {
                return true;
            }
        }
        return false;
    }

    /** 這個節點能不能退：已配置、退掉後其他已配置節點仍全部連得到起點。 */
    public boolean canRefund(final Set<String> allocated, final PassiveNode start, final PassiveNode node) {
        if (node.isStart() || !allocated.contains(node.id())) {
            return false;
        }
        final Set<String> remaining = new HashSet<>(allocated);
        remaining.remove(node.id());
        return reachable(remaining, start).size() == remaining.size();
    }

    /** 從起點出發、只走已配置節點能走到的節點集合（不含起點）。 */
    public Set<String> reachable(final Set<String> allocated, final PassiveNode start) {
        final Set<String> seen = new HashSet<>();
        final Deque<PassiveNode> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            final PassiveNode current = queue.poll();
            for (final PassiveNode neighbour : neighbours(current)) {
                if (allocated.contains(neighbour.id()) && seen.add(neighbour.id())) {
                    queue.add(neighbour);
                }
            }
        }
        return seen;
    }

    /**
     * 自動配路：從「起點與已配置節點」出發，只走未配置、非起點的節點，找到目標的最短路徑。
     * 回傳要配置的節點（依序，含目標）；目標已配置、是起點或走不到時為空。
     */
    public List<PassiveNode> pathTo(final Set<String> allocated, final PassiveNode start, final PassiveNode target) {
        if (target.isStart() || allocated.contains(target.id())) {
            return List.of();
        }
        final Map<String, PassiveNode> parent = new HashMap<>();
        final Deque<PassiveNode> queue = new ArrayDeque<>();
        final Set<String> seen = new HashSet<>();
        // 起點與所有已配置節點都是距離 0 的出發點
        seen.add(start.id());
        queue.add(start);
        for (final String id : allocated) {
            final PassiveNode node = nodes.get(id);
            if (node != null && seen.add(id)) {
                queue.add(node);
            }
        }
        while (!queue.isEmpty()) {
            final PassiveNode current = queue.poll();
            for (final PassiveNode neighbour : neighbours(current)) {
                if (neighbour.isStart() || !seen.add(neighbour.id())) {
                    continue;
                }
                parent.put(neighbour.id(), current);
                if (neighbour.equals(target)) {
                    final List<PassiveNode> path = new ArrayList<>();
                    PassiveNode step = neighbour;
                    while (step != null && !allocated.contains(step.id()) && !step.isStart()) {
                        path.add(step);
                        step = parent.get(step.id());
                    }
                    java.util.Collections.reverse(path);
                    return path;
                }
                queue.add(neighbour);
            }
        }
        return List.of();
    }

    /** 名稱或說明含這段文字的節點（不分大小寫）。 */
    public List<PassiveNode> search(final String text) {
        final String needle = text.trim().toLowerCase(Locale.ROOT);
        final List<PassiveNode> result = new ArrayList<>();
        if (needle.isEmpty()) {
            return result;
        }
        for (final PassiveNode node : nodes.values()) {
            if (node.name().toLowerCase(Locale.ROOT).contains(needle)) {
                result.add(node);
                continue;
            }
            for (final String line : node.description()) {
                if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                    result.add(node);
                    break;
                }
            }
        }
        // 核心與基石排前面，看搜尋結果比較有用
        result.sort((a, b) -> Integer.compare(order(b.type()), order(a.type())));
        return result;
    }

    private static int order(final PassiveNode.Type type) {
        return switch (type) {
            case KEYSTONE -> 3;
            case NOTABLE -> 2;
            default -> 1;
        };
    }

    /** 把資料庫載入的配點清掉連不到起點的部分（樹改版後可能發生），回傳被清掉的節點。 */
    public Set<String> prune(final Set<String> allocated, final PassiveNode start) {
        final Set<String> valid = reachable(allocated, start);
        final Set<String> removed = new HashSet<>();
        for (final String id : allocated) {
            if (!valid.contains(id)) {
                removed.add(id);
            }
        }
        allocated.removeAll(removed);
        return removed;
    }

    /** 已配置節點提供的所有修飾器。 */
    public List<Modifier> modifiers(final Set<String> allocated) {
        final List<Modifier> list = new ArrayList<>();
        for (final String id : allocated) {
            final PassiveNode node = nodes.get(id);
            if (node != null) {
                list.addAll(node.modifiers());
            }
        }
        return list;
    }

    /** 整棵樹從某個起點出發是否全部連通（資料檔檢查用）。 */
    public boolean isConnected() {
        if (nodes.isEmpty()) {
            return false;
        }
        final PassiveNode any = nodes.values().iterator().next();
        final Set<String> others = new HashSet<>(nodes.keySet());
        others.remove(any.id());
        return reachable(others, any).size() == others.size();
    }
}
