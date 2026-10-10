package tw.exilecore.map;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 星圖天賦（地圖天賦樹，data/atlas_tree.yml）：完成地圖拿天賦點，點了之後自己開的地圖更好。純邏輯，不依賴 Bukkit。
 * <ul>
 *   <li>天賦點：每完成一張不同的地圖、每打贏一個終局、每打贏一個極境各 +1；最高完成階級到 {@code tier_milestones} 的每一階再 +1。</li>
 *   <li>天賦分成幾條分支（最多 {@value #MAX_BRANCHES} 條，介面上一條一欄），每條最多 {@value #MAX_NODES} 個節點，
 *       要從上往下點；最後一個通常是大天賦（貴一點）。</li>
 *   <li>退點免費：退掉一個，同一條分支下面已經點的一起退。</li>
 *   <li>點了哪些存在角色進度的 {@link #PROGRESS_KEY}（照點的順序，逗號隔開）。設定改了、點數變少時，從最後點的開始不算
 *       （{@link #effective}），存的不用改。</li>
 *   <li>效果算開地圖的那個人的，整張地圖（含隊友）都吃；開著的地圖不會因為之後改天賦而變。</li>
 * </ul>
 */
public final class AtlasTree {

    /** 角色進度裡記點了哪些天賦的鍵。故意不用 atlas. 開頭：任務、委託用「atlas. 開頭的鍵」數完成過幾張地圖。 */
    public static final String PROGRESS_KEY = "atlastree";
    /** 介面一欄一條分支：最多 9 條。 */
    public static final int MAX_BRANCHES = 9;
    /** 一條分支最多 4 個節點（介面第 2–5 排）。 */
    public static final int MAX_NODES = 4;
    /** 一個節點最多要幾點。 */
    public static final int MAX_COST = 5;
    /** 存點了哪些的那一格最多幾個字（資料庫的 progress_value 是 VARCHAR(255)）。 */
    public static final int MAX_STORED_LENGTH = 255;

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** 一條效果：效果鍵（{@link #STATS}）與數值（都是正的）。 */
    public record Mod(String stat, double value) {
    }

    /**
     * 一個天賦。
     *
     * @param branch  屬於哪條分支
     * @param index   在分支裡的第幾個（0 起；要先點前一個）
     * @param cost    要幾點
     * @param notable 大天賦（介面上用自己的圖示）
     * @param icon    大天賦的圖示（原版物品名稱；空的用分支的）
     */
    public record Node(String id, String branch, int index, String name, int cost, boolean notable, String icon, List<Mod> mods) {
        public Node {
            mods = List.copyOf(mods);
        }
    }

    /**
     * 一條分支。
     *
     * @param icon  分支的圖示（原版物品名稱）
     * @param color 名稱的顏色（#rrggbb）
     */
    public record Branch(String id, String name, String icon, String color, String description, List<Node> nodes) {
        public Branch {
            nodes = List.copyOf(nodes);
        }
    }

    /**
     * 天賦點怎麼來。
     *
     * @param perMap         每完成一張不同的地圖（不含終局）
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
        stats.put("atlas.ritual.chance", "祭壇出現的機率增加 {v}%");
        stats.put("atlas.ritual.tribute", "祭壇的貢品 +{v}%");
        stats.put("atlas.delirium.chance", "幻霧出現的機率增加 {v}%");
        stats.put("atlas.delirium.progress", "幻霧的獎勵進度 +{v}%");
        STATS = Collections.unmodifiableMap(stats);
    }

    /** 沒寫 points 時：地圖、終局、極境各 1 點，最高完成階級 T4、T7、T10、T13、T16 各 1 點。 */
    public static final Points DEFAULT_POINTS = new Points(1, 1, 1, List.of(4, 7, 10, 13, 16));

    private Points points = DEFAULT_POINTS;
    private List<Branch> branches = List.of();
    private Map<String, Node> nodes = Map.of();
    private List<String> problems = List.of();

    /** 讀 data/atlas_tree.yml；沒有這個檔案就是空的天賦樹（記一個問題）。 */
    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("atlas_tree.yml");
        if (!Files.exists(file)) {
            points = DEFAULT_POINTS;
            branches = List.of();
            nodes = Map.of();
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

        final List<Branch> readBranches = new ArrayList<>();
        final Map<String, Node> readNodes = new LinkedHashMap<>();
        final YamlNode all = root.node("branches");
        for (final String branchId : all.keys()) {
            if (readBranches.size() >= MAX_BRANCHES) {
                found.add("atlas_tree.yml：分支最多 " + MAX_BRANCHES + " 條（介面一條一欄），" + branchId + " 不會出現");
                continue;
            }
            final YamlNode branchNode = all.node(branchId);
            final List<Node> list = new ArrayList<>();
            final List<YamlNode> entries = branchNode.list("nodes");
            for (final YamlNode entry : entries) {
                if (list.size() >= MAX_NODES) {
                    found.add("atlas_tree.yml：" + branchId + " 的節點最多 " + MAX_NODES + " 個，第 " + (MAX_NODES + 1) + " 個以後不會出現");
                    break;
                }
                final Node node = readNode(entry, branchId, list.size(), readNodes, found);
                if (node != null) {
                    list.add(node);
                    readNodes.put(node.id(), node);
                }
            }
            if (list.isEmpty()) {
                found.add("atlas_tree.yml：分支 " + branchId + " 沒有能用的節點（不會出現）");
                continue;
            }
            String color = branchNode.string("color", "#f5b942").trim();
            if (!COLOR.matcher(color).matches()) {
                found.add("atlas_tree.yml：" + branchId + " 的 color「" + color + "」要寫成 #rrggbb（先用金色）");
                color = "#f5b942";
            }
            readBranches.add(new Branch(branchId, branchNode.string("name", branchId), branchNode.string("icon", "FILLED_MAP"), color,
                    branchNode.string("description", ""), list));
        }
        points = readPoints;
        branches = List.copyOf(readBranches);
        nodes = Collections.unmodifiableMap(readNodes);
        problems = List.copyOf(found);
    }

    private static Node readNode(final YamlNode entry, final String branchId, final int index, final Map<String, Node> existing,
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
        final List<Mod> mods = new ArrayList<>();
        for (final YamlNode mod : entry.list("mods")) {
            final String stat = mod.string("stat", "").trim();
            final double value = mod.decimal("value", 0);
            if (!STATS.containsKey(stat)) {
                found.add(mod.path() + "：不認得的效果「" + stat + "」（不會生效）");
                continue;
            }
            if (!(value > 0)) {
                found.add(mod.path() + "：" + stat + " 的數值要大於 0（不會生效）");
                continue;
            }
            mods.add(new Mod(stat, value));
        }
        if (mods.isEmpty()) {
            found.add(entry.path() + "（" + id + "）：沒有任何效果");
        }
        final boolean notable = entry.bool("notable", false);
        int cost = entry.integer("cost", notable ? 2 : 1);
        if (cost < 1 || cost > MAX_COST) {
            found.add(entry.path() + "（" + id + "）：cost 要在 1–" + MAX_COST + " 之間（先夾到範圍內）");
            cost = Math.max(1, Math.min(MAX_COST, cost));
        }
        return new Node(id, branchId, index, entry.string("name", id), cost, notable, entry.string("icon", ""), mods);
    }

    // ---- 查詢 ----

    public Points points() {
        return points;
    }

    public List<Branch> branches() {
        return branches;
    }

    /** 這個代號的天賦；沒有回傳 null。 */
    public Node node(final String id) {
        return id == null ? null : nodes.get(id);
    }

    /** 這條分支；沒有回傳 null。 */
    public Branch branch(final String id) {
        for (final Branch branch : branches) {
            if (branch.id().equals(id)) {
                return branch;
            }
        }
        return null;
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
     * @param completedMaps   完成過幾張不同的一般地圖
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

    /**
     * 實際有效的天賦：照點的順序一個一個看，這個天賦還在、同一條的前一個也有效、點數還夠才算。
     * 設定改了、點數變少（管理指令清掉星圖）時，從最後點的開始不算；存的字串不用改。
     */
    public List<String> effective(final List<String> stored, final int budget) {
        final List<String> result = new ArrayList<>();
        final Set<String> accepted = new LinkedHashSet<>();
        int spent = 0;
        for (final String id : stored) {
            final Node node = nodes.get(id);
            if (node == null || accepted.contains(id) || !previousTaken(node, accepted)) {
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
        if (!previousTaken(node, allocated)) {
            final Branch branch = branch(node.branch());
            final Node previous = branch == null || node.index() == 0 ? null : branch.nodes().get(node.index() - 1);
            return "要先點上面的「" + (previous == null ? "前一個天賦" : previous.name()) + "」";
        }
        final int left = budget - spent(allocated);
        if (node.cost() > left) {
            return "天賦點不夠：要 " + node.cost() + " 點，你還有 " + Math.max(0, left) + " 點";
        }
        return null;
    }

    /**
     * 退掉一個天賦：連同同一條分支裡更下面、已經點了的。回傳退掉的（從上到下）；沒點這個天賦回傳空的。
     */
    public List<String> refund(final List<String> allocated, final String id) {
        final Node node = nodes.get(id);
        if (node == null || !allocated.contains(id)) {
            return List.of();
        }
        final Branch branch = branch(node.branch());
        final List<String> removed = new ArrayList<>();
        if (branch == null) {
            removed.add(id);
            return removed;
        }
        for (final Node other : branch.nodes()) {
            if (other.index() >= node.index() && allocated.contains(other.id())) {
                removed.add(other.id());
            }
        }
        return removed;
    }

    /** 同一條分支的前一個點了沒（第一個不用）。 */
    private boolean previousTaken(final Node node, final Collection<String> allocated) {
        if (node.index() == 0) {
            return true;
        }
        final Branch branch = branch(node.branch());
        return branch != null && node.index() - 1 < branch.nodes().size() && allocated.contains(branch.nodes().get(node.index() - 1).id());
    }

    // ---- 效果 ----

    /** 這些天賦的效果加總。 */
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
        }
        return values.isEmpty() ? AtlasBonus.NONE : new AtlasBonus(values);
    }

    /** 一條效果的說明（「地圖的物品數量 +3%」）。 */
    public static String describe(final String stat, final double value) {
        final String text = STATS.get(stat);
        return text == null ? stat + " " + number(value) : text.replace("{v}", number(value));
    }

    /** 3.0 → 「3」、2.5 → 「2.5」。 */
    static String number(final double value) {
        final double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.format(Locale.ROOT, "%.1f", rounded);
    }
}
