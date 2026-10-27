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
 *   <li>要配一個節點，它必須與某個已配置的節點相連（tree.json 的 links；舊格式是上下左右相鄰）。</li>
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
    /** 相連的節點（2026-10-26：照 links；舊格式照上下左右）。 */
    private final Map<String, List<PassiveNode>> adjacency = new HashMap<>();
    /** 每一條連線（每條只記一次）。 */
    private final List<Link> links = new ArrayList<>();
    /** 格子 → 經過這一格的連線（含兩端的節點格），畫線用。 */
    private final Map<Long, List<CellWire>> wires = new HashMap<>();
    /** 2026-10-26：PoE2 的座標（珠寶半徑照原作的距離算）；沒寫的節點用格子座標 × {@link #unit}。 */
    private final Map<String, double[]> positions = new HashMap<>();
    /** 一格是幾個 PoE2 單位（tree.json 的 unit；PoE2 的樹 160）。 */
    private double unit = DEFAULT_UNIT;
    /** 2026-10-26 照 PoE2：有解鎖條件的節點（tree.json 的 unlock）；這次之前這些節點不在樹上。 */
    private final Map<String, Unlock> unlocks = new HashMap<>();
    /** 2026-10-27 照 PoE2 0.5：塗油限定的核心（tree.json 的 anoint）：不在樹上、不能配點，只能注入在項鍊上。 */
    private final Map<String, PassiveNode> anointOnly = new LinkedHashMap<>();

    /**
     * 解鎖條件：stat 不是 null 時要有那個昇華規則（例如觀星「未見之道」asc.unseen_path）；
     * 否則要先配 nodes 其中一個。
     */
    /** {@link #hidden} 的昇華代號寫這個：全部都顯示（管理、測試用）。 */
    public static final String SHOW_ALL = "*";

    public record Unlock(String stat, List<String> nodes, String ascendancy) {
        public Unlock(final String stat, final List<String> nodes) {
            this(stat, nodes, null);
        }
    }

    /**
     * 2026-10-26 照站長：這個節點對這個昇華（ascendancy.yml 的代號，沒選是 null）要不要藏起來——
     * 觀星專屬的節點只有觀星看得到（遊戲內天賦樹、網頁規劃器）；配點與規劃代碼不受影響。
     */
    public boolean hidden(final PassiveNode node, final String ascendancy) {
        if (SHOW_ALL.equals(ascendancy)) {
            return false;
        }
        final Unlock unlock = unlocks.get(node.id());
        return unlock != null && unlock.ascendancy() != null && !unlock.ascendancy().equals(ascendancy);
    }

    // 2026-10-26 照 PoE2：昇華改變配點規則的屬性（rules 裡放這些字串）
    /** 觀星「未見之道」：可以配觀星專屬節點。 */
    public static final String RULE_UNSEEN_PATH = "asc.unseen_path";
    /** 開路者「女術者之道」：可以從女術士（元素法師）起點配。 */
    public static final String RULE_PATH_SORCERESS = "asc.path_sorceress";
    /** 開路者「戰士之道」：可以從戰士（重甲鬥士）起點配。 */
    public static final String RULE_PATH_WARRIOR = "asc.path_warrior";
    /** 觀星「虛實交織」：已配基石中半徑（1150）內的非基石節點不用相連。 */
    public static final String RULE_ENTWINED = "asc.entwined";
    public static final Set<String> RULES = Set.of(RULE_UNSEEN_PATH, RULE_PATH_SORCERESS, RULE_PATH_WARRIOR, RULE_ENTWINED);
    /** 虛實交織的半徑（原作中半徑）。 */
    public static final double ENTWINED_RADIUS = 1150;
    public static final double DEFAULT_UNIT = 160;
    private int minX;
    private int maxX;
    private int minY;
    private int maxY;

    /** 一條連線。 */
    public record Link(PassiveNode a, PassiveNode b) {
    }

    /**
     * 一條連線在某一格的樣子：directions 是這一格往哪幾個方向有線（位元：1 上、2 右、4 下、8 左）。
     */
    public record CellWire(Link link, int directions) {
    }

    /** 方向的位元：上、右、下、左（跟 {@link TreeView} 的 edges 順序一樣）。 */
    public static final int UP = 1;
    public static final int RIGHT = 2;
    public static final int DOWN = 4;
    public static final int LEFT = 8;

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
        if (root.has("unit")) {
            tree.unit = root.get("unit").getAsDouble();
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
        final Map<String, List<String>> declared = new LinkedHashMap<>();
        for (final JsonElement element : array) {
            final JsonObject nodeObject = element.getAsJsonObject();
            final PassiveNode node = parseNode(nodeObject);
            if (nodeObject.has("px") && nodeObject.has("py")) {
                tree.positions.put(node.id(), new double[] {nodeObject.get("px").getAsDouble(), nodeObject.get("py").getAsDouble()});
            }
            final JsonObject unlock = nodeObject.getAsJsonObject("unlock");
            if (unlock != null) {
                tree.unlocks.put(node.id(), new Unlock(unlock.has("stat") ? unlock.get("stat").getAsString() : null,
                        strings(unlock.getAsJsonArray("nodes")), unlock.has("ascendancy") ? unlock.get("ascendancy").getAsString() : null));
            }
            final JsonArray linkArray = nodeObject.getAsJsonArray("links");
            if (linkArray != null) {
                final List<String> targets = new ArrayList<>();
                for (final JsonElement target : linkArray) {
                    targets.add(target.getAsString());
                }
                declared.put(node.id(), targets);
            }
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
        tree.buildLinks(declared, root.getAsJsonArray("wires"));
        final JsonArray anoint = root.getAsJsonArray("anoint");
        if (anoint != null) {
            for (final JsonElement element : anoint) {
                final PassiveNode node = parseNode(element.getAsJsonObject());
                if (node.type() != PassiveNode.Type.NOTABLE) {
                    throw new IllegalArgumentException("tree.json：塗油限定的 " + node.id() + " 不是核心");
                }
                if (tree.nodes.containsKey(node.id()) || tree.anointOnly.put(node.id(), node) != null) {
                    throw new IllegalArgumentException("tree.json：重複的節點 " + node.id());
                }
            }
        }
        return tree;
    }

    /** 建相連關係與畫線用的格子。declared 為空（舊格式）時上下左右相鄰就是相連。 */
    private void buildLinks(final Map<String, List<String>> declared, final JsonArray wireArray) {
        for (final PassiveNode node : ordered) {
            adjacency.put(node.id(), new ArrayList<>(4));
        }
        final Set<String> seen = new HashSet<>();
        if (declared.isEmpty()) {
            for (final PassiveNode node : ordered) {
                for (final int[] d : new int[][] {{1, 0}, {0, 1}}) {
                    final PassiveNode other = at(node.x() + d[0], node.y() + d[1]);
                    if (other != null) {
                        connect(node, other, seen);
                    }
                }
            }
        } else {
            for (final Map.Entry<String, List<String>> entry : declared.entrySet()) {
                final PassiveNode node = nodes.get(entry.getKey());
                for (final String target : entry.getValue()) {
                    final PassiveNode other = nodes.get(target);
                    if (other == null) {
                        throw new IllegalArgumentException("tree.json：" + node.id() + " 連到不存在的節點 " + target);
                    }
                    if (other.equals(node)) {
                        throw new IllegalArgumentException("tree.json：" + node.id() + " 連到自己");
                    }
                    connect(node, other, seen);
                }
            }
        }
        // 畫線：檔案裡有寫路線的照寫的走，沒有的（相鄰以外）走 L 形
        final Map<String, List<int[]>> routes = new HashMap<>();
        if (wireArray != null) {
            for (final JsonElement element : wireArray) {
                final JsonObject wire = element.getAsJsonObject();
                final List<int[]> cells = new ArrayList<>();
                for (final JsonElement cell : wire.getAsJsonArray("cells")) {
                    final JsonArray xy = cell.getAsJsonArray();
                    cells.add(new int[] {xy.get(0).getAsInt(), xy.get(1).getAsInt()});
                }
                routes.put(linkKey(wire.get("a").getAsString(), wire.get("b").getAsString()), cells);
            }
        }
        for (final Link link : links) {
            final List<int[]> path = new ArrayList<>();
            path.add(new int[] {link.a().x(), link.a().y()});
            final List<int[]> middle = routes.get(linkKey(link.a().id(), link.b().id()));
            if (middle != null) {
                path.addAll(middle);
            } else {
                int x = link.a().x();
                int y = link.a().y();
                while (x != link.b().x()) {
                    x += Integer.signum(link.b().x() - x);
                    if (x != link.b().x() || y != link.b().y()) {
                        path.add(new int[] {x, y});
                    }
                }
                while (y != link.b().y()) {
                    y += Integer.signum(link.b().y() - y);
                    if (y != link.b().y()) {
                        path.add(new int[] {x, y});
                    }
                }
            }
            path.add(new int[] {link.b().x(), link.b().y()});
            final Map<Long, Integer> masks = new LinkedHashMap<>();
            for (int i = 0; i + 1 < path.size(); i++) {
                final int[] from = path.get(i);
                final int[] to = path.get(i + 1);
                final int dx = to[0] - from[0];
                final int dy = to[1] - from[1];
                if (Math.abs(dx) + Math.abs(dy) != 1) {
                    throw new IllegalArgumentException("tree.json：連線 " + link.a().id() + "－" + link.b().id() + " 的路線不是一格一格接著的");
                }
                final int out = dx > 0 ? RIGHT : dx < 0 ? LEFT : dy > 0 ? DOWN : UP;
                final int in = out == RIGHT ? LEFT : out == LEFT ? RIGHT : out == DOWN ? UP : DOWN;
                masks.merge(key(from[0], from[1]), out, (p, q) -> p | q);
                masks.merge(key(to[0], to[1]), in, (p, q) -> p | q);
            }
            for (final Map.Entry<Long, Integer> entry : masks.entrySet()) {
                wires.computeIfAbsent(entry.getKey(), k -> new ArrayList<>(2)).add(new CellWire(link, entry.getValue()));
            }
        }
    }

    private void connect(final PassiveNode a, final PassiveNode b, final Set<String> seen) {
        if (!seen.add(linkKey(a.id(), b.id()))) {
            return;
        }
        adjacency.get(a.id()).add(b);
        adjacency.get(b.id()).add(a);
        links.add(new Link(a, b));
    }

    private static String linkKey(final String a, final String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
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
                // 2026-10-26：有條件的（while stationary、vs Blinded…，見 TreeConditions）換成條件寫法
                modifiers.add(tw.exilecore.stats.TreeConditions.apply(new Modifier(require(stat, "stat").getAsString(),
                        ModType.valueOf(stat.has("type") ? stat.get("type").getAsString().toUpperCase(Locale.ROOT) : "FLAT"),
                        require(stat, "value").getAsDouble(), tags, "天賦 " + (name.isEmpty() ? id : name)), condition(stat)));
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
        final Map<String, PassiveNode.Variant> variants = new HashMap<>();
        final JsonObject variantObject = object.getAsJsonObject("variants");
        if (variantObject != null) {
            for (final Map.Entry<String, JsonElement> entry : variantObject.entrySet()) {
                final JsonObject v = entry.getValue().getAsJsonObject();
                final String variantName = v.has("name") ? v.get("name").getAsString() : name;
                variants.put(entry.getKey().toLowerCase(Locale.ROOT), new PassiveNode.Variant(variantName,
                        parseStats(v.getAsJsonArray("stats"), variantName.isEmpty() ? id : variantName), strings(v.getAsJsonArray("description"))));
            }
        }
        return new PassiveNode(id, type, name, x, y, icon, modifiers, description, start, variants);
    }

    private static List<Modifier> parseStats(final JsonArray stats, final String label) {
        final List<Modifier> modifiers = new ArrayList<>();
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
                modifiers.add(tw.exilecore.stats.TreeConditions.apply(new Modifier(require(stat, "stat").getAsString(),
                        ModType.valueOf(stat.has("type") ? stat.get("type").getAsString().toUpperCase(Locale.ROOT) : "FLAT"),
                        require(stat, "value").getAsDouble(), tags, "天賦 " + label), condition(stat)));
            }
        }
        return modifiers;
    }

    /** 2026-10-26：stat 的條件（"condition": "stationary"）；沒有回傳 null。 */
    private static String condition(final JsonObject stat) {
        return stat.has("condition") ? stat.get("condition").getAsString() : null;
    }

    private static List<String> strings(final JsonArray array) {
        final List<String> list = new ArrayList<>();
        if (array != null) {
            for (final JsonElement line : array) {
                list.add(line.getAsString());
            }
        }
        return list;
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

    /**
     * 能注入（蒸餾情緒注入項鍊）的一個核心：天賦樹上的核心，或塗油限定的（anointOnly：天賦樹上沒有、不能配點，只能靠注入拿到）。
     */
    public record Anointable(PassiveNode node, boolean anointOnly) {
    }

    /**
     * 2026-10-27 照 PoE2 0.5：所有能注入的核心——天賦樹上的核心（照檔案順序）接著塗油限定的核心（anointOnly = true）。
     * 塗油限定的配方（三種情緒）在 data/passives/anoint_only.yml（{@link AnointRecipes}）。
     */
    public List<Anointable> anointable() {
        final List<Anointable> list = new ArrayList<>();
        for (final PassiveNode node : ordered) {
            if (node.type() == PassiveNode.Type.NOTABLE) {
                list.add(new Anointable(node, false));
            }
        }
        for (final PassiveNode node : anointOnly.values()) {
            list.add(new Anointable(node, true));
        }
        return list;
    }

    /** 2026-10-27：塗油限定的核心（照 tree.json 的順序）。 */
    public Collection<PassiveNode> anointOnly() {
        return java.util.Collections.unmodifiableCollection(anointOnly.values());
    }

    /** 2026-10-27：這個代號是不是塗油限定的核心。 */
    public boolean isAnointOnly(final String id) {
        return anointOnly.containsKey(id);
    }

    /** 2026-10-27：能注入的核心（樹上的核心或塗油限定的）；不是核心或找不到回傳 null。項鍊的注入、說明用這個查。 */
    public PassiveNode anointNode(final String id) {
        final PassiveNode node = nodes.get(id);
        if (node != null) {
            return node.type() == PassiveNode.Type.NOTABLE ? node : null;
        }
        return anointOnly.get(id);
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

    /** 相連的節點。 */
    public List<PassiveNode> neighbours(final PassiveNode node) {
        final List<PassiveNode> list = adjacency.get(node.id());
        return list == null ? List.of() : java.util.Collections.unmodifiableList(list);
    }

    /** 節點在 PoE2 座標的位置（沒寫的用格子座標 × 一格的單位）。 */
    public double[] position(final PassiveNode node) {
        final double[] p = positions.get(node.id());
        return p != null ? p : new double[] {node.x() * unit, node.y() * unit};
    }

    /** 兩個節點的距離（PoE2 單位；珠寶半徑：小 1000、中 1150、大 1300、超大 1500）。 */
    public double distance(final PassiveNode a, final PassiveNode b) {
        final double[] pa = position(a);
        final double[] pb = position(b);
        return Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
    }

    /** 一格是幾個 PoE2 單位。 */
    public double unit() {
        return unit;
    }

    /** 所有連線。 */
    public List<Link> links() {
        return java.util.Collections.unmodifiableList(links);
    }

    /** 經過這一格的連線（畫線用；沒有是空的）。 */
    public List<CellWire> wiresAt(final int x, final int y) {
        final List<CellWire> list = wires.get(key(x, y));
        return list == null ? List.of() : list;
    }

    // ---- 規則 ----

    /** 節點的解鎖條件；沒有是 null。 */
    public Unlock unlock(final PassiveNode node) {
        return unlocks.get(node.id());
    }

    /** 2026-10-26 照 PoE2：這個節點被解鎖條件擋住（沒有觀星「未見之道」、還沒配前置核心）。 */
    public boolean locked(final Set<String> allocated, final PassiveNode node, final Set<String> rules) {
        final Unlock unlock = unlocks.get(node.id());
        if (unlock == null) {
            return false;
        }
        if (unlock.stat() != null) {
            return !rules.contains(unlock.stat());
        }
        for (final String id : unlock.nodes()) {
            if (allocated.contains(id)) {
                return false;
            }
        }
        return true;
    }

    /** 出發點：自己的起點；開路者的女術者之道／戰士之道再加那個職業的起點。 */
    public List<PassiveNode> seeds(final PassiveNode start, final Set<String> rules) {
        final List<PassiveNode> list = new ArrayList<>();
        list.add(start);
        addSeed(list, rules, RULE_PATH_SORCERESS, "sorceress");
        addSeed(list, rules, RULE_PATH_WARRIOR, "marauder");
        return list;
    }

    private void addSeed(final List<PassiveNode> list, final Set<String> rules, final String rule, final String classId) {
        final PassiveNode extra = rules.contains(rule) ? start(classId) : null;
        if (extra != null && !list.contains(extra)) {
            list.add(extra);
        }
    }

    /** 虛實交織：節點不是基石、在某個（in 裡的）已配基石的中半徑內。 */
    public boolean entwined(final Set<String> in, final PassiveNode node, final Set<String> rules) {
        if (!rules.contains(RULE_ENTWINED) || node.type() == PassiveNode.Type.KEYSTONE || node.isStart()) {
            return false;
        }
        for (final String id : in) {
            final PassiveNode keystone = nodes.get(id);
            if (keystone != null && keystone.type() == PassiveNode.Type.KEYSTONE && distance(keystone, node) <= ENTWINED_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** 這個節點現在能不能配：還沒配、不是起點、與某個已配置節點（或起點）相鄰。 */
    public boolean canAllocate(final Set<String> allocated, final PassiveNode start, final PassiveNode node) {
        return canAllocate(allocated, start, node, Set.of());
    }

    /**
     * 同上，加上昇華的配點規則（{@link #RULES}；2026-10-26 照 PoE2：觀星專屬節點、開路者別的起點、虛實交織）。
     * 有解鎖條件沒達成的節點不能配。
     */
    public boolean canAllocate(final Set<String> allocated, final PassiveNode start, final PassiveNode node, final Set<String> rules) {
        if (node.isStart() || allocated.contains(node.id()) || locked(allocated, node, rules)) {
            return false;
        }
        final List<PassiveNode> seeds = seeds(start, rules);
        for (final PassiveNode neighbour : neighbours(node)) {
            if (seeds.contains(neighbour) || allocated.contains(neighbour.id())) {
                return true;
            }
        }
        return rules.contains(RULE_ENTWINED) && entwined(reachable(allocated, start, rules), node, rules);
    }

    /** 這個節點能不能退：已配置、退掉後其他已配置節點仍全部連得到起點。 */
    public boolean canRefund(final Set<String> allocated, final PassiveNode start, final PassiveNode node) {
        return canRefund(allocated, start, node, Set.of());
    }

    public boolean canRefund(final Set<String> allocated, final PassiveNode start, final PassiveNode node, final Set<String> rules) {
        if (node.isStart() || !allocated.contains(node.id())) {
            return false;
        }
        final Set<String> remaining = new HashSet<>(allocated);
        remaining.remove(node.id());
        return reachable(remaining, start, rules).size() == remaining.size();
    }

    /** 從起點出發、只走已配置節點能走到的節點集合（不含起點）。 */
    public Set<String> reachable(final Set<String> allocated, final PassiveNode start) {
        return reachable(allocated, start, Set.of());
    }

    /** 同上，加上昇華的配點規則；被解鎖條件擋住的節點不算連得到。 */
    public Set<String> reachable(final Set<String> allocated, final PassiveNode start, final Set<String> rules) {
        final Set<String> seen = new HashSet<>();
        final Deque<PassiveNode> queue = new ArrayDeque<>(seeds(start, rules));
        while (true) {
            while (!queue.isEmpty()) {
                final PassiveNode current = queue.poll();
                for (final PassiveNode neighbour : neighbours(current)) {
                    if (allocated.contains(neighbour.id()) && !locked(allocated, neighbour, rules) && seen.add(neighbour.id())) {
                        queue.add(neighbour);
                    }
                }
            }
            if (!rules.contains(RULE_ENTWINED)) {
                return seen;
            }
            // 虛實交織：連得到的基石附近、沒連上的節點也算（再從它們往外走）
            for (final String id : allocated) {
                final PassiveNode node = nodes.get(id);
                if (node != null && !seen.contains(id) && !locked(allocated, node, rules) && entwined(seen, node, rules)) {
                    seen.add(id);
                    queue.add(node);
                }
            }
            if (queue.isEmpty()) {
                return seen;
            }
        }
    }

    /**
     * 自動配路：從「起點與已配置節點」出發，只走未配置、非起點的節點，找到目標的最短路徑。
     * 回傳要配置的節點（依序，含目標）；目標已配置、是起點或走不到時為空。
     */
    public List<PassiveNode> pathTo(final Set<String> allocated, final PassiveNode start, final PassiveNode target) {
        return pathTo(allocated, start, target, Set.of());
    }

    /** 同上，加上昇華的配點規則（被解鎖條件擋住的節點不走；虛實交織能直接配的就只配目標）。 */
    public List<PassiveNode> pathTo(final Set<String> allocated, final PassiveNode start, final PassiveNode target, final Set<String> rules) {
        if (target.isStart() || allocated.contains(target.id()) || locked(allocated, target, rules)) {
            return List.of();
        }
        if (canAllocate(allocated, start, target, rules)) {
            return List.of(target);
        }
        final Map<String, PassiveNode> parent = new HashMap<>();
        final Deque<PassiveNode> queue = new ArrayDeque<>();
        final Set<String> seen = new HashSet<>();
        // 起點與所有已配置節點都是距離 0 的出發點
        for (final PassiveNode seed : seeds(start, rules)) {
            seen.add(seed.id());
            queue.add(seed);
        }
        for (final String id : allocated) {
            final PassiveNode node = nodes.get(id);
            if (node != null && seen.add(id)) {
                queue.add(node);
            }
        }
        while (!queue.isEmpty()) {
            final PassiveNode current = queue.poll();
            for (final PassiveNode neighbour : neighbours(current)) {
                if (neighbour.isStart() || locked(allocated, neighbour, rules) || !seen.add(neighbour.id())) {
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
        return prune(allocated, start, Set.of());
    }

    /** 同上，加上昇華的配點規則（例如退了「未見之道」之後，觀星專屬節點退回）。 */
    public Set<String> prune(final Set<String> allocated, final PassiveNode start, final Set<String> rules) {
        final Set<String> valid = reachable(allocated, start, rules);
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
        return modifiers(allocated, null);
    }

    /** 已配置節點提供的所有修飾器（照職業換成變體；classId 為 null 時不換）。 */
    public List<Modifier> modifiers(final Set<String> allocated, final String classId) {
        final List<Modifier> list = new ArrayList<>();
        for (final String id : allocated) {
            final PassiveNode node = nodes.get(id);
            if (node != null) {
                list.addAll(node.forClass(classId).modifiers());
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
        // 2026-10-26：觀星專屬節點也算（只看連線，不看解鎖條件）
        return reachable(others, any, Set.of(RULE_UNSEEN_PATH)).size() == others.size();
    }
}
