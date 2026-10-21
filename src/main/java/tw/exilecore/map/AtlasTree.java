package tw.exilecore.map;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 星圖天賦（地圖天賦樹，data/atlas_tree.yml）：完成地圖拿天賦點，點了之後自己開的地圖更好。純邏輯，不依賴 Bukkit。
 * <p>
 * 2026-10-21：從「9 欄直線」改成一棵樹：節點放在方格座標上，上下左右相鄰＝相連（跟被動天賦樹 passives/tree.json 一樣），
 * 中心 (0, 0) 是起點（不用點、不佔節點），要從起點一路點出去；退掉一個節點時，連不回起點的也一起退。
 * <ul>
 *   <li>天賦點：每完成一張不同的地圖、每打贏一個終局、每打贏一個極境各幾點（points）；最高完成階級到 {@code tier_milestones} 的每一階再 +1。</li>
 *   <li>節點分小天賦（1 點）、大天賦（notable，2 點）、核心（keystone，3 點，有代價 drawbacks）；主幹（trunk）只是介面上的分組與顏色。</li>
 *   <li>點了哪些存在角色進度的 {@link #PROGRESS_KEY}（照點的順序，逗號隔開）。設定改了、點數變少時，從最後點的開始不算
 *       （{@link #effective}），存的不用改。{@code version} 改了全部退點（{@link #version()}，MapService 登入時比）。</li>
 *   <li>效果算開地圖的那個人的，整張地圖（含隊友）都吃；開著的地圖不會因為之後改天賦而變。</li>
 * </ul>
 */
public final class AtlasTree {

    /** 角色進度裡記點了哪些天賦的鍵。故意不用 atlas. 開頭：任務、委託用「atlas. 開頭的鍵」數完成過幾張地圖。 */
    public static final String PROGRESS_KEY = "atlastree";
    /** 角色進度裡記「上次照哪一版的樹點的」的鍵（版本不同就全部退點）。 */
    public static final String VERSION_KEY = "atlastree_v";
    /** 一個節點最多要幾點。 */
    public static final int MAX_COST = 5;
    /** 存點了哪些的那一格最多幾個字（資料庫的 progress_value：第 20 版遷移加長成 VARCHAR(4096)；以前是 255）。 */
    public static final int MAX_STORED_LENGTH = 4096;
    /** 起點的座標。 */
    public static final int ROOT_X = 0;
    public static final int ROOT_Y = 0;

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** 上、右、下、左。 */
    private static final int[][] DIRECTIONS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /** 一條效果：效果鍵（{@link #STATS}）與數值（都是正的）。 */
    public record Mod(String stat, double value) {
    }

    /**
     * 一個天賦。
     *
     * @param trunk     屬於哪條主幹（介面上的分組；{@code core} 是中心的輻條、內環與橋）
     * @param cost      要幾點
     * @param notable   大天賦（介面上用自己的圖示）
     * @param keystone  核心（有代價）
     * @param icon      大天賦、核心的圖示（原版物品名稱；空的用主幹的）
     * @param drawbacks 核心的代價（{@link #DRAWBACKS}）
     */
    public record Node(String id, String trunk, int x, int y, String name, int cost, boolean notable, boolean keystone, String icon,
                       List<Mod> mods, List<Mod> drawbacks) {
        public Node {
            mods = List.copyOf(mods);
            drawbacks = List.copyOf(drawbacks);
        }
    }

    /**
     * 一條主幹（介面上的分組）。
     *
     * @param icon  圖示（原版物品名稱）
     * @param color 名稱的顏色（#rrggbb）
     */
    public record Trunk(String id, String name, String icon, String color, String description) {
    }

    /**
     * 天賦點怎麼來。
     *
     * @param perMap         星圖格子算出來的點數（{@link AtlasProgress#cellPoints}）乘幾倍
     * @param perFinal       每打贏一個終局
     * @param perUber        每打贏一個極境
     * @param tierMilestones 最高完成階級到這幾階，各 +1（由小到大）
     */
    public record Points(int perMap, int perFinal, int perUber, List<Integer> tierMilestones) {
        public Points {
            tierMilestones = List.copyOf(tierMilestones);
        }
    }

    /** 一個角色拿到的天賦點：各來源幾點。 */
    public record Earned(int maps, int finals, int ubers, int milestones) {
        public int total() {
            return maps + finals + ubers + milestones;
        }
    }

    /** 認得的效果與說明的寫法（{v} 換成數值）；介面照這個順序列。 */
    static final Map<String, String> STATS;
    /** 核心的代價：效果鍵與說明。 */
    static final Map<String, String> DRAWBACKS;

    static {
        final Map<String, String> stats = new LinkedHashMap<>();
        stats.put("map.quantity", "地圖的物品數量 +{v}%");
        stats.put("map.rarity", "地圖的物品稀有度 +{v}%");
        stats.put("map.pack_size", "怪物群大小 +{v}%");
        stats.put("map.magic_packs", "魔法怪物群增加 {v}%");
        stats.put("map.rare_packs", "稀有怪物群增加 {v}%");
        stats.put("atlas.experience", "地圖裡的怪給的經驗 +{v}%");
        stats.put("atlas.map_drop", "地圖怪掉地圖的機率增加 {v}%");
        stats.put("atlas.tier_up", "掉落的地圖高一階的機率 +{v}%");
        stats.put("atlas.boss_maps", "地圖 Boss 多掉 {v} 張地圖");
        stats.put("atlas.boss_quantity", "地圖 Boss 的物品數量 +{v}%");
        stats.put("atlas.boss_rarity", "地圖 Boss 的物品稀有度 +{v}%");
        stats.put("atlas.boss_unique", "地圖 Boss 掉傳奇的機率增加 {v}%（終局、追逐級不算）");
        stats.put("atlas.uber_key", "終局 Boss 掉極境鑰匙的機率增加 {v}%");
        stats.put("atlas.chase_unique", "追逐級傳奇的機率增加 {v}%");
        stats.put("atlas.shrine.weight", "神龕出現的機會增加 {v}%");
        stats.put("atlas.shrine.extra", "每張地圖多 {v} 座神龕");
        stats.put("atlas.shrine.duration", "神龕增益的持續時間 +{v}%");
        stats.put("atlas.shrine.unique", "神龕掉專屬傳奇的機率增加 {v}%");
        stats.put("atlas.strongbox.weight", "寶箱出現的機會增加 {v}%");
        stats.put("atlas.strongbox.extra", "每張地圖多 {v} 個寶箱");
        stats.put("atlas.strongbox.quantity", "寶箱的物品數量 +{v}%");
        stats.put("atlas.strongbox.unique", "寶箱掉專屬傳奇的機率增加 {v}%");
        stats.put("atlas.breach.weight", "裂隙出現的機會增加 {v}%");
        stats.put("atlas.breach.extra", "每張地圖多 {v} 個裂隙");
        stats.put("atlas.breach.duration", "裂隙開著的時間 +{v}%（怪多幾波）");
        stats.put("atlas.breach.unique", "裂隙掉專屬傳奇的機率增加 {v}%");
        stats.put("atlas.exile.weight", "流寇出現的機會增加 {v}%");
        stats.put("atlas.exile.extra", "每張地圖多 {v} 個流寇");
        stats.put("atlas.exile.drops", "流寇多掉 {v} 件稀有裝備");
        stats.put("atlas.exile.unique", "流寇掉專屬傳奇的機率增加 {v}%");
        stats.put("atlas.abyss.weight", "深淵裂縫出現的機會增加 {v}%");
        stats.put("atlas.abyss.extra", "每張地圖多 {v} 條深淵裂縫");
        stats.put("atlas.abyss.unique", "深淵裂縫掉專屬傳奇的機率增加 {v}%");
        stats.put("atlas.ritual.chance", "祭壇出現的機率增加 {v}%");
        stats.put("atlas.ritual.tribute", "祭壇的貢品 +{v}%");
        stats.put("atlas.delirium.chance", "幻霧出現的機率增加 {v}%");
        stats.put("atlas.delirium.progress", "幻霧的獎勵進度 +{v}%");
        // 2026-10-21：塔與石板、腐化區、星諭卡（第二、三階的系統；鍵先認得，效果在那些系統上線後生效）
        stats.put("atlas.tower.radius", "塔罩到的範圍 +{v} 格");
        stats.put("atlas.tablet.effect", "先驅石板的效果 +{v}%");
        stats.put("atlas.tablet.drop", "先驅石板的掉落機率增加 {v}%");
        stats.put("atlas.corrupt.chance", "腐化區出現的機率增加 {v}%");
        stats.put("atlas.corrupt.bonus", "腐化區的加成 +{v}%");
        stats.put("atlas.card.chance", "星諭卡的掉落機率增加 {v}%");
        stats.put("atlas.card.deck", "星諭牌堆開出稀有卡的權重增加 {v}%");
        stats.put("atlas.card.gamble", "賭卡便宜 {v}%");
        // 核心的旗標（1 = 有）
        stats.put("atlas.boss_double", "地圖 Boss 的掉落全部 ×1.5（傳奇機率、地圖、星諭卡）");
        stats.put("atlas.rare_only", "地圖裡只有稀有怪與 Boss");
        stats.put("atlas.all_corrupt", "所有格子當作腐化區");
        stats.put("atlas.chosen_event", "選一種事件：每張地圖保證 {v} 個");
        STATS = Collections.unmodifiableMap(stats);

        final Map<String, String> drawbacks = new LinkedHashMap<>();
        drawbacks.put("atlas.map_drop_less", "地圖怪掉地圖的機率減少 {v}%");
        drawbacks.put("atlas.unique_less", "其他傳奇的機率減少 {v}%");
        drawbacks.put("atlas.experience_less", "地圖裡的怪給的經驗減少 {v}%");
        drawbacks.put("atlas.monster_damage_more", "怪物傷害更多 {v}%");
        drawbacks.put("atlas.no_monster_currency", "地圖裡的一般怪不掉通貨");
        drawbacks.put("atlas.other_events_off", "其他事件不出現");
        DRAWBACKS = Collections.unmodifiableMap(drawbacks);
    }

    /** 沒寫 points 時：星圖格子的點數 ×1、終局 2、極境 2 點，最高完成階級 T4、T7、T10、T13、T16 各 1 點。 */
    public static final Points DEFAULT_POINTS = new Points(1, 2, 2, List.of(4, 7, 10, 13, 16));

    private int version = 1;
    private Points points = DEFAULT_POINTS;
    private Map<String, Trunk> trunks = Map.of();
    private Map<String, Node> nodes = Map.of();
    private Map<Long, Node> byPosition = Map.of();
    private List<Node> ordered = List.of();
    private List<String> problems = List.of();
    private int minX, maxX, minY, maxY;

    /** 讀 data/atlas_tree.yml；沒有這個檔案就是空的天賦樹（記一個問題）。 */
    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("atlas_tree.yml");
        if (!Files.exists(file)) {
            version = 1;
            points = DEFAULT_POINTS;
            trunks = Map.of();
            nodes = Map.of();
            byPosition = Map.of();
            ordered = List.of();
            problems = List.of("atlas_tree.yml 不存在：星圖天賦是空的");
            return;
        }
        apply(YamlNode.load(file));
    }

    /** 讀一份設定（測試、體檢用）。 */
    public static AtlasTree parse(final YamlNode root) {
        final AtlasTree tree = new AtlasTree();
        tree.apply(root);
        return tree;
    }

    private void apply(final YamlNode root) {
        final List<String> found = new ArrayList<>();
        final YamlNode pointNode = root.node("points");
        final Set<Integer> milestones = new java.util.TreeSet<>();
        if (pointNode.has("tier_milestones")) {
            for (final String value : pointNode.strings("tier_milestones")) {
                try {
                    final int tier = Integer.parseInt(value.trim());
                    if (tier > 0) {
                        milestones.add(tier);
                    }
                } catch (final NumberFormatException e) {
                    found.add("atlas_tree.yml：points.tier_milestones 的「" + value + "」不是數字（不算）");
                }
            }
        } else {
            milestones.addAll(DEFAULT_POINTS.tierMilestones());
        }
        final Points readPoints = new Points(Math.max(0, pointNode.integer("per_map", DEFAULT_POINTS.perMap())),
                Math.max(0, pointNode.integer("per_final", DEFAULT_POINTS.perFinal())),
                Math.max(0, pointNode.integer("per_uber", DEFAULT_POINTS.perUber())), new ArrayList<>(milestones));

        final Map<String, Trunk> readTrunks = new LinkedHashMap<>();
        final YamlNode trunkNodes = root.node("trunks");
        for (final String trunkId : trunkNodes.keys()) {
            final YamlNode trunk = trunkNodes.node(trunkId);
            String color = trunk.string("color", "#f5b942").trim();
            if (!COLOR.matcher(color).matches()) {
                found.add("atlas_tree.yml：" + trunkId + " 的 color「" + color + "」要寫成 #rrggbb（先用金色）");
                color = "#f5b942";
            }
            readTrunks.put(trunkId, new Trunk(trunkId, trunk.string("name", trunkId), trunk.string("icon", "FILLED_MAP"), color,
                    trunk.string("description", "")));
        }

        final Map<String, Node> readNodes = new LinkedHashMap<>();
        final Map<Long, Node> readPositions = new LinkedHashMap<>();
        final List<Node> readOrdered = new ArrayList<>();
        int readMinX = 0, readMaxX = 0, readMinY = 0, readMaxY = 0;
        for (final YamlNode entry : root.list("nodes")) {
            final Node node = readNode(entry, readNodes, readTrunks, found);
            if (node == null) {
                continue;
            }
            final long key = key(node.x(), node.y());
            if (node.x() == ROOT_X && node.y() == ROOT_Y) {
                found.add("atlas_tree.yml：" + node.id() + " 放在起點 (0, 0) 上（不算）");
                continue;
            }
            if (readPositions.containsKey(key)) {
                found.add("atlas_tree.yml：" + node.id() + " 跟 " + readPositions.get(key).id() + " 放在同一格 (" + node.x() + ", " + node.y() + ")（第二個不算）");
                continue;
            }
            readNodes.put(node.id(), node);
            readPositions.put(key, node);
            readOrdered.add(node);
            readMinX = Math.min(readMinX, node.x());
            readMaxX = Math.max(readMaxX, node.x());
            readMinY = Math.min(readMinY, node.y());
            readMaxY = Math.max(readMaxY, node.y());
        }
        // 每個節點都要連得到起點（不然永遠點不到）
        final Set<String> all = new HashSet<>(readNodes.keySet());
        final Set<String> reachable = reachableFromRoot(all, readPositions);
        for (final Node node : readOrdered) {
            if (!reachable.contains(node.id())) {
                found.add("atlas_tree.yml：" + node.id() + " (" + node.x() + ", " + node.y() + ") 連不到起點（永遠點不到）");
            }
        }
        version = Math.max(1, root.integer("version", 1));
        points = readPoints;
        trunks = Collections.unmodifiableMap(readTrunks);
        nodes = Collections.unmodifiableMap(readNodes);
        byPosition = Collections.unmodifiableMap(readPositions);
        ordered = List.copyOf(readOrdered);
        minX = readMinX;
        maxX = readMaxX;
        minY = readMinY;
        maxY = readMaxY;
        problems = List.copyOf(found);
    }

    private static Node readNode(final YamlNode entry, final Map<String, Node> existing, final Map<String, Trunk> trunks,
                                 final List<String> found) {
        final String id = entry.string("id", "").trim();
        if (id.isEmpty()) {
            found.add(entry.path() + "：缺少 id（這個節點不算）");
            return null;
        }
        if (id.contains(",") || id.contains(" ")) {
            found.add(entry.path() + "：id「" + id + "」不能有逗號或空白（這個節點不算）");
            return null;
        }
        if (existing.containsKey(id)) {
            found.add("atlas_tree.yml：天賦代號 " + id + " 重複了（第二個不算）");
            return null;
        }
        if (!entry.has("x") || !entry.has("y")) {
            found.add(entry.path() + "（" + id + "）：缺少 x、y 座標（這個節點不算）");
            return null;
        }
        final List<Mod> mods = readMods(entry, "mods", STATS, found);
        final List<Mod> drawbacks = readMods(entry, "drawbacks", DRAWBACKS, found);
        if (mods.isEmpty()) {
            found.add(entry.path() + "（" + id + "）：沒有任何效果");
        }
        final boolean keystone = entry.bool("keystone", false);
        final boolean notable = !keystone && entry.bool("notable", false);
        if (keystone && drawbacks.isEmpty()) {
            found.add(entry.path() + "（" + id + "）：核心要有代價（drawbacks）");
        }
        if (!keystone && !drawbacks.isEmpty()) {
            found.add(entry.path() + "（" + id + "）：只有核心（keystone: true）才有代價，這裡的 drawbacks 不算");
        }
        int cost = entry.integer("cost", keystone ? 3 : notable ? 2 : 1);
        if (cost < 1 || cost > MAX_COST) {
            found.add(entry.path() + "（" + id + "）：cost 要在 1–" + MAX_COST + " 之間（先夾到範圍內）");
            cost = Math.max(1, Math.min(MAX_COST, cost));
        }
        final String trunk = entry.string("trunk", "core").trim();
        if (!trunks.containsKey(trunk)) {
            found.add(entry.path() + "（" + id + "）：trunk「" + trunk + "」沒有寫在 trunks 裡（介面上用預設顏色）");
        }
        return new Node(id, trunk, entry.integer("x", 0), entry.integer("y", 0), entry.string("name", id), cost, notable, keystone,
                entry.string("icon", ""), mods, keystone ? drawbacks : List.of());
    }

    private static List<Mod> readMods(final YamlNode entry, final String key, final Map<String, String> known, final List<String> found) {
        final List<Mod> mods = new ArrayList<>();
        for (final YamlNode mod : entry.list(key)) {
            final String stat = mod.string("stat", "").trim();
            final double value = mod.decimal("value", 0);
            if (!known.containsKey(stat)) {
                found.add(mod.path() + "：不認得的效果「" + stat + "」（不會生效）");
                continue;
            }
            if (!(value > 0)) {
                found.add(mod.path() + "：" + stat + " 的數值要大於 0（不會生效）");
                continue;
            }
            mods.add(new Mod(stat, value));
        }
        return mods;
    }

    private static long key(final int x, final int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    // ---- 查詢 ----

    public int version() {
        return version;
    }

    public Points points() {
        return points;
    }

    /** 主幹（介面上的分組）；照檔案的順序。 */
    public Collection<Trunk> trunks() {
        return trunks.values();
    }

    /** 這條主幹；沒有回傳 null。 */
    public Trunk trunk(final String id) {
        return id == null ? null : trunks.get(id);
    }

    /** 這個代號的天賦；沒有回傳 null。 */
    public Node node(final String id) {
        return id == null ? null : nodes.get(id);
    }

    /** 這一格的天賦；沒有回傳 null。 */
    public Node at(final int x, final int y) {
        return byPosition.get(key(x, y));
    }

    /** 全部節點，照檔案的順序。 */
    public List<Node> nodes() {
        return ordered;
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

    /** 上下左右相鄰的節點。 */
    public List<Node> neighbours(final Node node) {
        return neighbours(node.x(), node.y(), byPosition);
    }

    private static List<Node> neighbours(final int x, final int y, final Map<Long, Node> positions) {
        final List<Node> list = new ArrayList<>(4);
        for (final int[] direction : DIRECTIONS) {
            final Node other = positions.get(key(x + direction[0], y + direction[1]));
            if (other != null) {
                list.add(other);
            }
        }
        return list;
    }

    /** 跟起點 (0, 0) 相鄰。 */
    public boolean touchesRoot(final Node node) {
        return Math.abs(node.x() - ROOT_X) + Math.abs(node.y() - ROOT_Y) == 1;
    }

    /** 讀設定時發現的問題（啟動時記在主控台）。 */
    public List<String> problems() {
        return problems;
    }

    /** 全部點滿要幾點。 */
    public int totalCost() {
        int total = 0;
        for (final Node node : nodes.values()) {
            total += node.cost();
        }
        return total;
    }

    // ---- 天賦點 ----

    /**
     * 一個角色拿到的天賦點。
     *
     * @param completedMaps   星圖格子算出來的點數（{@link AtlasProgress#cellPoints}；舊的：完成過幾張不同的一般地圖）
     * @param completedFinals 打贏過幾個終局
     * @param ubersDefeated   打贏過幾個極境
     * @param bestTier        完成過的最高階
     */
    public Earned earned(final int completedMaps, final int completedFinals, final int ubersDefeated, final int bestTier) {
        int milestones = 0;
        for (final int tier : points.tierMilestones()) {
            if (bestTier >= tier) {
                milestones++;
            }
        }
        return new Earned(Math.max(0, completedMaps) * points.perMap(), Math.max(0, completedFinals) * points.perFinal(),
                Math.max(0, ubersDefeated) * points.perUber(), milestones);
    }

    /** 下一個階級里程碑（最高完成階級到幾階再拿一點）；都拿了回傳 0。 */
    public int nextMilestone(final int bestTier) {
        for (final int tier : points.tierMilestones()) {
            if (bestTier < tier) {
                return tier;
            }
        }
        return 0;
    }

    // ---- 配點 ----

    /** 存的字串 → 點的順序（空的略過、重複的只算第一個）。 */
    public static List<String> parseAllocation(final String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        final Set<String> ids = new LinkedHashSet<>();
        for (final String part : stored.split(",")) {
            final String id = part.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** 點的順序 → 存的字串。 */
    public static String joinAllocation(final List<String> allocated) {
        return String.join(",", allocated);
    }

    /** 短代碼的開頭（2026-10-21）。 */
    public static final String CODE_PREFIX = "A";
    private static final Pattern SHORT_CODE = Pattern.compile("A[A-Za-z0-9_-]+");

    /**
     * 網頁規劃器、/atlas import 用的短代碼（2026-10-21）：「A」＋每個節點一個位元（照檔案裡的順序）的 base64url，全部點滿也只有 22 字。
     * 以前是「代號,代號,…」，點了 30 個左右就超過聊天框一行 256 字，後面的被截掉。順序不重要（匯入時照 {@link #orderFromRoot} 排）。
     */
    public String encodeCode(final Collection<String> ids) {
        final byte[] bytes = new byte[(ordered.size() + 7) / 8];
        for (int i = 0; i < ordered.size(); i++) {
            if (ids.contains(ordered.get(i).id())) {
                bytes[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return CODE_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 短代碼或舊的「代號,代號」都讀得懂；讀不懂回傳空的。 */
    public List<String> decodeCode(final String code) {
        final String trimmed = code == null ? "" : code.trim();
        if (!SHORT_CODE.matcher(trimmed).matches()) {
            return parseAllocation(trimmed);
        }
        final byte[] bytes;
        try {
            bytes = java.util.Base64.getUrlDecoder().decode(trimmed.substring(CODE_PREFIX.length()));
        } catch (final IllegalArgumentException e) {
            return List.of();
        }
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < ordered.size() && (i >> 3) < bytes.length; i++) {
            if ((bytes[i >> 3] >> (i & 7) & 1) != 0) {
                ids.add(ordered.get(i).id());
            }
        }
        return ids;
    }

    /**
     * 實際有效的天賦：照點的順序一個一個看，這個天賦還在、跟起點或已經有效的天賦相鄰、點數還夠才算。
     * 設定改了、點數變少（管理指令清掉星圖）時，從最後點的開始不算；存的字串不用改。
     */
    public List<String> effective(final List<String> stored, final int budget) {
        final List<String> result = new ArrayList<>();
        final Set<String> accepted = new LinkedHashSet<>();
        int spent = 0;
        for (final String id : stored) {
            final Node node = nodes.get(id);
            if (node == null || accepted.contains(id) || !connected(node, accepted)) {
                continue;
            }
            if (spent + node.cost() > budget) {
                break; // 點數不夠：這個和之後點的都不算（從最後點的開始不算）
            }
            spent += node.cost();
            accepted.add(id);
            result.add(id);
        }
        return result;
    }

    /** 這些天賦用掉幾點。 */
    public int spent(final Collection<String> allocated) {
        int total = 0;
        for (final String id : allocated) {
            final Node node = nodes.get(id);
            if (node != null) {
                total += node.cost();
            }
        }
        return total;
    }

    /** 這個節點現在能不能點：跟起點相鄰，或跟某個已點的相鄰。 */
    public boolean connected(final Node node, final Collection<String> allocated) {
        if (touchesRoot(node)) {
            return true;
        }
        for (final Node neighbour : neighbours(node)) {
            if (allocated.contains(neighbour.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 能不能點這個天賦；不能的話回傳原因（給玩家看），可以回傳 null。
     *
     * @param allocated 目前有效的天賦（{@link #effective}）
     * @param budget    拿到的天賦點
     */
    public String allocateProblem(final List<String> allocated, final String id, final int budget) {
        final Node node = nodes.get(id);
        if (node == null) {
            return "沒有這個天賦";
        }
        if (allocated.contains(id)) {
            return "已經點了（右鍵退回）";
        }
        if (!connected(node, allocated)) {
            return "要先點到旁邊的天賦（從中心一路點過來）";
        }
        final int left = budget - spent(allocated);
        if (node.cost() > left) {
            return "天賦點不夠：要 " + node.cost() + " 點，你還有 " + Math.max(0, left) + " 點";
        }
        return null;
    }

    /**
     * 退掉一個天賦：連同退掉之後連不回起點的那些。回傳退掉的（先是這個，再照原本點的順序）；沒點這個天賦回傳空的。
     */
    public List<String> refund(final List<String> allocated, final String id) {
        final Node node = nodes.get(id);
        if (node == null || !allocated.contains(id)) {
            return List.of();
        }
        final Set<String> remaining = new LinkedHashSet<>(allocated);
        remaining.remove(id);
        final Set<String> stillConnected = reachableFromRoot(remaining, byPosition);
        final List<String> removed = new ArrayList<>();
        removed.add(id);
        for (final String other : allocated) {
            if (!other.equals(id) && !stillConnected.contains(other)) {
                removed.add(other);
            }
        }
        return removed;
    }

    /** 從起點出發、只走這些節點能走到的節點（不含起點）。 */
    private static Set<String> reachableFromRoot(final Set<String> allocated, final Map<Long, Node> positions) {
        final Set<String> seen = new HashSet<>();
        final Deque<Node> queue = new ArrayDeque<>();
        for (final Node first : neighbours(ROOT_X, ROOT_Y, positions)) {
            if (allocated.contains(first.id()) && seen.add(first.id())) {
                queue.add(first);
            }
        }
        while (!queue.isEmpty()) {
            final Node current = queue.poll();
            for (final Node neighbour : neighbours(current.x(), current.y(), positions)) {
                if (allocated.contains(neighbour.id()) && seen.add(neighbour.id())) {
                    queue.add(neighbour);
                }
            }
        }
        return seen;
    }

    /**
     * 自動配路：從「起點與已點的天賦」出發，只走還沒點的節點，找到目標的最短路徑（照點數最少）。
     * 回傳要點的節點（依序，含目標）；目標已點或走不到時為空。
     */
    public List<Node> pathTo(final Collection<String> allocated, final Node target) {
        if (target == null || allocated.contains(target.id())) {
            return List.of();
        }
        // 權重 = cost：用 Dijkstra（節點少，簡單的 O(n²) 就夠）
        final Map<String, Integer> distance = new LinkedHashMap<>();
        final Map<String, Node> parent = new LinkedHashMap<>();
        final Set<String> done = new HashSet<>();
        for (final Node first : neighbours(ROOT_X, ROOT_Y, byPosition)) {
            distance.put(first.id(), allocated.contains(first.id()) ? 0 : first.cost());
        }
        for (final String id : allocated) {
            final Node node = nodes.get(id);
            if (node != null) {
                distance.put(id, 0);
                for (final Node neighbour : neighbours(node)) {
                    if (!allocated.contains(neighbour.id())) {
                        final int d = neighbour.cost();
                        if (d < distance.getOrDefault(neighbour.id(), Integer.MAX_VALUE)) {
                            distance.put(neighbour.id(), d);
                            parent.put(neighbour.id(), node);
                        }
                    }
                }
            }
        }
        while (true) {
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (final Map.Entry<String, Integer> entry : distance.entrySet()) {
                if (!done.contains(entry.getKey()) && entry.getValue() < bestDistance) {
                    best = entry.getKey();
                    bestDistance = entry.getValue();
                }
            }
            if (best == null) {
                return List.of();
            }
            done.add(best);
            if (best.equals(target.id())) {
                break;
            }
            final Node current = nodes.get(best);
            for (final Node neighbour : neighbours(current)) {
                if (done.contains(neighbour.id())) {
                    continue;
                }
                final int d = bestDistance + (allocated.contains(neighbour.id()) ? 0 : neighbour.cost());
                if (d < distance.getOrDefault(neighbour.id(), Integer.MAX_VALUE)) {
                    distance.put(neighbour.id(), d);
                    parent.put(neighbour.id(), current);
                }
            }
        }
        final List<Node> path = new ArrayList<>();
        Node step = target;
        while (step != null && !allocated.contains(step.id())) {
            path.add(step);
            step = parent.get(step.id());
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * 把一組天賦（網頁規劃器、天賦組）排成「從起點往外」的順序：每一輪把跟起點或已排進去的相鄰的加進去（離起點近的先）。
     * 連不到起點的不會排進去。
     */
    public List<String> orderFromRoot(final Collection<String> planned) {
        final Set<String> want = new LinkedHashSet<>();
        for (final String id : planned) {
            if (nodes.containsKey(id)) {
                want.add(id);
            }
        }
        final List<String> result = new ArrayList<>();
        final Set<String> placed = new HashSet<>();
        final Deque<Node> queue = new ArrayDeque<>();
        for (final Node first : neighbours(ROOT_X, ROOT_Y, byPosition)) {
            if (want.contains(first.id()) && placed.add(first.id())) {
                queue.add(first);
                result.add(first.id());
            }
        }
        while (!queue.isEmpty()) {
            final Node current = queue.poll();
            for (final Node neighbour : neighbours(current)) {
                if (want.contains(neighbour.id()) && placed.add(neighbour.id())) {
                    queue.add(neighbour);
                    result.add(neighbour.id());
                }
            }
        }
        return result;
    }

    // ---- 效果 ----

    /** 這些天賦的效果加總（核心的代價也放進去，鍵在 {@link #DRAWBACKS}）。 */
    public AtlasBonus bonus(final Collection<String> allocated) {
        final Map<String, Double> values = new LinkedHashMap<>();
        for (final String id : allocated) {
            final Node node = nodes.get(id);
            if (node == null) {
                continue;
            }
            for (final Mod mod : node.mods()) {
                values.merge(mod.stat(), mod.value(), Double::sum);
            }
            for (final Mod mod : node.drawbacks()) {
                values.merge(mod.stat(), mod.value(), Double::sum);
            }
        }
        return values.isEmpty() ? AtlasBonus.NONE : new AtlasBonus(values);
    }

    /** 一條效果的說明（「地圖的物品數量 +3%」）；代價也認得。 */
    public static String describe(final String stat, final double value) {
        String text = STATS.get(stat);
        if (text == null) {
            text = DRAWBACKS.get(stat);
        }
        return text == null ? stat + " " + number(value) : text.replace("{v}", number(value));
    }

    /** 這個效果鍵是不是核心的代價。 */
    public static boolean isDrawback(final String stat) {
        return DRAWBACKS.containsKey(stat);
    }

    /** 3.0 → 「3」、2.5 → 「2.5」。 */
    static String number(final double value) {
        final double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.format(Locale.ROOT, "%.1f", rounded);
    }
}
