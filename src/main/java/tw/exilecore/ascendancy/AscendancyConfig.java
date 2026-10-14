package tw.exilecore.ascendancy;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.map.MapConfig;
import tw.exilecore.stats.Modifier;
import tw.exilecore.zone.ZoneConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 昇華、試煉、分身獎勵的設定（data/ascendancy.yml；純資料，不依賴 Bukkit）。
 * 先讀進一份新的，{@link #validate} 檢查過（職業、區域、怪物詞綴、屬性都對得上）才換上去。
 */
public final class AscendancyConfig {

    public static final String FILE = "ascendancy.yml";
    /** 代號最長幾個字（角色進度的鍵是 trial.代號）。 */
    static final int MAX_ID = 24;
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** 試煉最多幾個（選單一列）。 */
    public static final int MAX_TRIALS = 7;
    /** 一棵昇華小樹最多幾條分支、每條最多幾個（選單的格子）。 */
    public static final int MAX_BRANCHES = 4;
    public static final int MAX_DEPTH = 4;
    /** 配的昇華天賦存成一筆「a,b,c」（progress_value 最長 255 字）。 */
    static final int NODES_VALUE_MAX = 250;
    /** 試煉的怪物等級上限（地圖最高 68）。 */
    static final int MAX_MONSTER_LEVEL = 100;

    /**
     * @param cooldownSeconds 開了一個試煉之後多久才能再開
     * @param weeklyOpens     每個角色每週最多開幾次試煉（0 = 不限）
     */
    public record Settings(int cooldownSeconds, int weeklyOpens) {
    }

    /**
     * 解鎖條件（都要符合；沒寫的不用）。
     *
     * @param trial 先完成的試煉（null = 不用）
     * @param boss  打倒過 Boss 的區域（null = 不用）
     * @param maps  完成過幾張不同的地圖
     * @param level 角色等級
     */
    public record Requirements(String trial, String boss, int maps, int level) {
    }

    /**
     * 一個試煉。
     *
     * @param level    怪物等級
     * @param points   第一次完成給幾個昇華點
     * @param rooms    版面大約幾個房間
     * @param quantity Boss 的物品數量加成（百分比點數）
     * @param rarity   Boss 的物品稀有度加成
     */
    public record Trial(String id, String name, Requirements requires, int level, int points, String theme, int rooms,
                        int quantity, int rarity, String lore, List<ZoneConfig.SpawnEntry> monsters, MapConfig.Boss boss) {
        public Trial {
            monsters = List.copyOf(monsters);
        }

        /** 試煉的「地圖」（地圖世界裡的一個實例）。 */
        public MapConfig.MapDef map() {
            return new MapConfig.MapDef("trial_" + id, name, null, 0, level, theme, 0, 0, List.of(), false, monsters, boss, lore, false);
        }
    }

    /**
     * 一個昇華天賦。
     *
     * @param requires 要先配的天賦（同一條分支的前一個；null = 分支的第一個）
     * @param cost     幾個昇華點
     * @param icon     原版物品名稱
     * @param stats    給的修飾器（引擎格式）
     * @param note     補充說明（可以沒有）
     */
    public record Node(String id, String name, String requires, int cost, String icon, List<Modifier> stats, String note) {
        public Node {
            stats = List.copyOf(stats);
        }
    }

    /**
     * 衝等活動（2026-10）：每個昇華職業第一個升到 level 級的角色拿那個昇華的專屬稱號（全服只有一個，拿了就沒了，沒有加成）。
     *
     * @param enabled 開著
     * @param level   幾級
     * @param titles  昇華代號 → 稱號名稱（沒寫的用「巔峰」＋昇華名稱）
     */
    public record LevelRace(boolean enabled, int level, java.util.Map<String, String> titles) {
        public LevelRace {
            titles = java.util.Map.copyOf(titles);
        }

        /** 這個昇華的稱號名稱。 */
        public String title(final Ascendancy ascendancy) {
            final String name = titles.get(ascendancy.id());
            return name == null || name.isBlank() ? "巔峰" + ascendancy.name() : name;
        }

        /** 這個昇華的稱號代號。 */
        public static String titleId(final String ascendancyId) {
            return "race_" + ascendancyId;
        }
    }

    /** 一個昇華職業。 */
    public record Ascendancy(String id, String classId, String name, String color, String icon, String summary, List<Node> nodes) {
        public Ascendancy {
            nodes = List.copyOf(nodes);
        }

        public Node node(final String nodeId) {
            for (final Node node : nodes) {
                if (node.id().equals(nodeId)) {
                    return node;
                }
            }
            return null;
        }

        /** 名字加顏色（MiniMessage）。 */
        public String colored() {
            return "<" + color + ">" + name.replace("\\", "\\\\").replace("<", "\\<") + "</" + color + ">";
        }

        /**
         * 分支：每條從第一個（沒有 requires）開始，一個接一個（照設定檔的順序）。
         * 檢查過的設定才保證每個天賦剛好在一條分支裡。
         */
        public List<List<Node>> branches() {
            final List<List<Node>> branches = new ArrayList<>();
            for (final Node root : nodes) {
                if (root.requires() != null) {
                    continue;
                }
                final List<Node> branch = new ArrayList<>();
                Node current = root;
                final Set<String> seen = new HashSet<>();
                while (current != null && seen.add(current.id())) {
                    branch.add(current);
                    current = child(current.id());
                }
                branches.add(branch);
            }
            return branches;
        }

        /** 接在這個天賦後面的那一個（沒有回傳 null）。 */
        public Node child(final String nodeId) {
            for (final Node node : nodes) {
                if (nodeId.equals(node.requires())) {
                    return node;
                }
            }
            return null;
        }
    }

    /** 分身獎勵的一階：這個等級起給這些修飾器。 */
    public record Tier(int level, List<Modifier> stats) {
        public Tier {
            stats = List.copyOf(stats);
        }
    }

    private Settings settings = new Settings(60, 8);
    private LevelRace levelRace = new LevelRace(false, 100, java.util.Map.of());
    private final Map<String, Trial> trials = new LinkedHashMap<>();
    private final Map<String, Ascendancy> ascendancies = new LinkedHashMap<>();
    /** 職業 → 分身獎勵（等級由低到高）。 */
    private final Map<String, List<Tier>> legacy = new LinkedHashMap<>();

    /** 讀 data/ascendancy.yml（不存在 = 沒有試煉、沒有昇華、沒有分身獎勵）。 */
    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        final AscendancyConfig fresh = new AscendancyConfig();
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        copyFrom(fresh);
    }

    /** 從已經讀好的節點載入（單元測試用）。 */
    public void load(final YamlNode root) {
        final AscendancyConfig fresh = new AscendancyConfig();
        fresh.parse(root);
        copyFrom(fresh);
    }

    /** 換成另一份（模組先讀進新的一份、檢查過才換）。 */
    public void copyFrom(final AscendancyConfig other) {
        settings = other.settings;
        levelRace = other.levelRace;
        trials.clear();
        trials.putAll(other.trials);
        ascendancies.clear();
        ascendancies.putAll(other.ascendancies);
        legacy.clear();
        legacy.putAll(other.legacy);
    }

    private void parse(final YamlNode root) {
        final YamlNode s = root.node("settings");
        settings = new Settings(s.integer("cooldown_seconds", 60), s.integer("weekly_opens", 8));
        final YamlNode race = root.node("level_race");
        final java.util.Map<String, String> raceTitles = new java.util.LinkedHashMap<>();
        final YamlNode titlesNode = race.node("titles");
        for (final String id : titlesNode.keys()) {
            raceTitles.put(id, titlesNode.string(id, "").trim());
        }
        levelRace = new LevelRace(race.bool("enabled", false), race.integer("level", 100), raceTitles);

        final YamlNode trialsNode = root.node("trials");
        for (final String id : trialsNode.keys()) {
            final YamlNode node = trialsNode.node(id);
            checkId(id, node.path());
            final YamlNode req = node.node("requires");
            final Requirements requires = new Requirements(blankToNull(req.string("trial", null)), blankToNull(req.string("boss", null)),
                    req.integer("maps", 0), req.integer("level", 0));
            trials.put(id, new Trial(id, node.requireString("name").trim(), requires, node.integer("level", 1), node.integer("points", 2),
                    node.string("theme", "ruins").trim().toLowerCase(Locale.ROOT), node.integer("rooms", 7), node.integer("quantity", 0),
                    node.integer("rarity", 0), node.string("lore", "").trim(), MapConfig.parseMonsters(node.node("monsters")),
                    MapConfig.parseBoss(node.node("boss"))));
        }

        final YamlNode ascNode = root.node("ascendancies");
        for (final String id : ascNode.keys()) {
            final YamlNode node = ascNode.node(id);
            checkId(id, node.path());
            final String name = node.requireString("name").trim();
            final List<Node> nodes = new ArrayList<>();
            final YamlNode nodesNode = node.node("nodes");
            for (final String nodeId : nodesNode.keys()) {
                final YamlNode n = nodesNode.node(nodeId);
                checkId(nodeId, n.path());
                final String nodeName = n.requireString("name").trim();
                nodes.add(new Node(nodeId, nodeName, blankToNull(n.string("requires", null)), n.integer("cost", 1),
                        n.string("icon", "AMETHYST_SHARD").trim(), stats(n.list("stats"), "昇華「" + nodeName + "」"),
                        blankToNull(n.string("note", null))));
            }
            ascendancies.put(id, new Ascendancy(id, node.requireString("class").trim().toLowerCase(Locale.ROOT), name,
                    node.string("color", "#c77dff").trim(), node.string("icon", "NETHER_STAR").trim(), node.string("summary", "").trim(), nodes));
        }

        final YamlNode legacyNode = root.node("legacy");
        for (final String classId : legacyNode.keys()) {
            final YamlNode node = legacyNode.node(classId);
            final List<Tier> tiers = new ArrayList<>();
            for (final String key : node.keys()) {
                final int level;
                try {
                    level = Integer.parseInt(key.trim());
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(node.path() + "：要寫成「等級: [加成…]」，卻是「" + key + "」");
                }
                tiers.add(new Tier(level, stats(node.list(key), "分身獎勵")));
            }
            tiers.sort((a, b) -> Integer.compare(a.level(), b.level()));
            legacy.put(classId.trim().toLowerCase(Locale.ROOT), tiers);
        }
    }

    /** { stat, type, value, tags }：INCREASED／MORE 的 value 是百分比（跟 monsters.yml 一樣）。 */
    private static List<Modifier> stats(final List<YamlNode> list, final String source) {
        return StatLines.parse(list, source);
    }

    private static void checkId(final String id, final String where) {
        if (!ID.matcher(id).matches() || id.length() > MAX_ID) {
            throw new IllegalArgumentException(where + "：代號只能用小寫英文、數字、底線，最多 " + MAX_ID + " 個字");
        }
    }

    private static String blankToNull(final String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    // ---- 檢查 ----

    /**
     * 檢查代號都對得上。
     *
     * @param classIds  職業代號
     * @param bossZones 有區域 Boss 的區域（null = 區域模組沒啟用，不檢查）
     * @param mods      怪物詞綴（null = 怪物模組沒啟用，不檢查）
     * @param themes    地圖版面的主題
     * @param levelCap  等級上限
     * @return 問題（空的 = 沒問題）
     */
    public List<String> validate(final Collection<String> classIds, final Collection<String> bossZones, final Collection<String> mods,
                                 final Collection<String> themes, final int levelCap) {
        final List<String> problems = new ArrayList<>();
        if (levelRace.enabled() && (levelRace.level() < 2 || levelRace.level() > levelCap)) {
            problems.add("level_race.level：要在 2 到等級上限 " + levelCap + " 之間");
        }
        for (final String id : levelRace.titles().keySet()) {
            if (!ascendancies.containsKey(id)) {
                problems.add("level_race.titles." + id + "：沒有這個昇華職業");
            }
        }
        if (settings.cooldownSeconds() < 0) {
            problems.add("settings.cooldown_seconds：不能是負的");
        }
        if (settings.weeklyOpens() < 0) {
            problems.add("settings.weekly_opens：不能是負的（0 = 不限）");
        }
        if (trials.size() > MAX_TRIALS) {
            problems.add("trials：最多 " + MAX_TRIALS + " 個試煉（選單放得下的）");
        }
        for (final Trial trial : trials.values()) {
            checkTrial(problems, trial, bossZones, mods, themes, levelCap);
        }
        for (final Trial trial : trials.values()) {
            final Set<String> seen = new HashSet<>();
            String current = trial.id();
            while (current != null && trials.containsKey(current)) {
                if (!seen.add(current)) {
                    problems.add("trials." + trial.id() + ".requires.trial：前置試煉繞成一圈了");
                    break;
                }
                current = trials.get(current).requires().trial();
            }
        }
        final Map<String, Integer> perClass = new LinkedHashMap<>();
        for (final Ascendancy ascendancy : ascendancies.values()) {
            checkAscendancy(problems, ascendancy, classIds);
            perClass.merge(ascendancy.classId(), 1, Integer::sum);
        }
        for (final Map.Entry<String, Integer> entry : perClass.entrySet()) {
            if (entry.getValue() > 4) {
                problems.add("ascendancies：職業 " + entry.getKey() + " 有 " + entry.getValue() + " 個昇華職業（最多 4 個，選擇畫面放得下的）");
            }
        }
        if (!ascendancies.isEmpty() && totalPoints() <= 0) {
            problems.add("trials：有昇華職業卻沒有給昇華點的試煉");
        }
        for (final Map.Entry<String, List<Tier>> entry : legacy.entrySet()) {
            final String where = "legacy." + entry.getKey();
            if (!classIds.contains(entry.getKey())) {
                problems.add(where + "：沒有職業 " + entry.getKey() + "（可以用 " + String.join("、", classIds) + "）");
            }
            int previous = 0;
            for (final Tier tier : entry.getValue()) {
                if (tier.level() < 1 || tier.level() > levelCap) {
                    problems.add(where + "." + tier.level() + "：等級要在 1 到 " + levelCap + " 之間");
                }
                if (tier.level() == previous) {
                    problems.add(where + "." + tier.level() + "：等級重複");
                }
                previous = tier.level();
                if (tier.stats().isEmpty()) {
                    problems.add(where + "." + tier.level() + "：沒有加成");
                }
                checkStats(problems, where + "." + tier.level(), tier.stats());
            }
        }
        return problems;
    }

    private void checkTrial(final List<String> problems, final Trial trial, final Collection<String> bossZones,
                            final Collection<String> mods, final Collection<String> themes, final int levelCap) {
        final String where = "trials." + trial.id();
        if (trial.name().isBlank()) {
            problems.add(where + "：沒有名字");
        }
        final Requirements requires = trial.requires();
        if (requires.trial() != null && (!trials.containsKey(requires.trial()) || requires.trial().equals(trial.id()))) {
            problems.add(where + ".requires.trial：沒有試煉 " + requires.trial());
        }
        if (requires.boss() != null && bossZones != null && !bossZones.contains(requires.boss())) {
            problems.add(where + ".requires.boss：區域 " + requires.boss() + " 沒有區域 Boss");
        }
        if (requires.maps() < 0) {
            problems.add(where + ".requires.maps：不能是負的");
        }
        if (requires.level() < 0 || requires.level() > levelCap) {
            problems.add(where + ".requires.level：要在 0 到 " + levelCap + " 之間");
        }
        if (trial.level() < 1 || trial.level() > MAX_MONSTER_LEVEL) {
            problems.add(where + ".level：怪物等級要在 1 到 " + MAX_MONSTER_LEVEL + " 之間");
        }
        if (trial.points() < 1) {
            problems.add(where + ".points：至少 1 點");
        }
        if (!themes.contains(trial.theme())) {
            problems.add(where + ".theme：不認得的主題 " + trial.theme() + "（可以用 " + String.join("、", new java.util.TreeSet<>(themes)) + "）");
        }
        if (trial.rooms() < 5 || trial.rooms() > 12) {
            problems.add(where + ".rooms：要在 5 到 12 之間");
        }
        if (trial.quantity() < 0 || trial.rarity() < 0) {
            problems.add(where + "：quantity、rarity 不能是負的");
        }
        if (mods != null) {
            for (final String mod : trial.boss().mods()) {
                if (!mods.contains(mod)) {
                    problems.add(where + ".boss.mods：沒有怪物詞綴 " + mod);
                }
            }
        }
    }

    private static void checkAscendancy(final List<String> problems, final Ascendancy ascendancy, final Collection<String> classIds) {
        final String where = "ascendancies." + ascendancy.id();
        if (!classIds.contains(ascendancy.classId())) {
            problems.add(where + ".class：沒有職業 " + ascendancy.classId() + "（可以用 " + String.join("、", classIds) + "）");
        }
        if (ascendancy.name().isBlank()) {
            problems.add(where + "：沒有名字");
        }
        if (!COLOR.matcher(ascendancy.color()).matches()) {
            problems.add(where + ".color：要寫成 \"#rrggbb\"");
        }
        if (ascendancy.nodes().isEmpty()) {
            problems.add(where + ".nodes：沒有任何昇華天賦");
            return;
        }
        int length = -1;
        for (final Node node : ascendancy.nodes()) {
            final String nodeWhere = where + ".nodes." + node.id();
            length += node.id().length() + 1;
            if (node.name().isBlank()) {
                problems.add(nodeWhere + "：沒有名字");
            }
            if (node.cost() < 1) {
                problems.add(nodeWhere + ".cost：至少 1 點");
            }
            if (node.requires() != null && (ascendancy.node(node.requires()) == null || node.requires().equals(node.id()))) {
                problems.add(nodeWhere + ".requires：這個昇華職業沒有天賦 " + node.requires());
            }
            if (node.stats().isEmpty()) {
                problems.add(nodeWhere + ".stats：沒有任何加成");
            }
            checkStats(problems, nodeWhere, node.stats());
        }
        if (length > NODES_VALUE_MAX) {
            problems.add(where + ".nodes：天賦代號加起來太長（" + length + " 字，最多 " + NODES_VALUE_MAX + "），代號短一點");
        }
        // 只能是一條一條的分支：每個天賦後面最多接一個、不能繞圈；分支數與長度要放得進選單
        final Map<String, Integer> children = new LinkedHashMap<>();
        for (final Node node : ascendancy.nodes()) {
            if (node.requires() != null) {
                children.merge(node.requires(), 1, Integer::sum);
            }
        }
        for (final Map.Entry<String, Integer> entry : children.entrySet()) {
            if (entry.getValue() > 1) {
                problems.add(where + ".nodes." + entry.getKey() + "：後面接了 " + entry.getValue() + " 個天賦（一條分支只能一個接一個）");
            }
        }
        int placed = 0;
        final List<List<Node>> branches = ascendancy.branches();
        for (final List<Node> branch : branches) {
            placed += branch.size();
            if (branch.size() > MAX_DEPTH) {
                problems.add(where + ".nodes." + branch.getFirst().id() + "：一條分支最多 " + MAX_DEPTH + " 個天賦");
            }
        }
        if (branches.size() > MAX_BRANCHES) {
            problems.add(where + ".nodes：最多 " + MAX_BRANCHES + " 條分支（沒有 requires 的天賦）");
        }
        if (placed != ascendancy.nodes().size()) {
            problems.add(where + ".nodes：有天賦接不到任何一條分支（requires 繞成一圈了）");
        }
    }

    private static void checkStats(final List<String> problems, final String where, final List<Modifier> stats) {
        for (final Modifier modifier : stats) {
            final String problem = StatLines.problem(modifier);
            if (problem != null) {
                problems.add(where + ".stats：" + problem);
            }
        }
    }

    // ---- 查詢 ----

    public Settings settings() {
        return settings;
    }

    /** 全部試煉（照設定檔的順序）。 */
    public Collection<Trial> trials() {
        return Collections.unmodifiableCollection(trials.values());
    }

    public Trial trial(final String id) {
        return id == null ? null : trials.get(id);
    }

    /** 衝等活動的設定。 */
    public LevelRace levelRace() {
        return levelRace;
    }

    public Collection<Ascendancy> ascendancies() {
        return Collections.unmodifiableCollection(ascendancies.values());
    }

    public Ascendancy ascendancy(final String id) {
        return id == null ? null : ascendancies.get(id);
    }

    /** 這個職業的昇華職業（照設定檔的順序）。 */
    public List<Ascendancy> forClass(final String classId) {
        final List<Ascendancy> list = new ArrayList<>();
        for (final Ascendancy ascendancy : ascendancies.values()) {
            if (ascendancy.classId().equals(classId)) {
                list.add(ascendancy);
            }
        }
        return list;
    }

    /** 職業 → 分身獎勵（等級由低到高）。 */
    public Map<String, List<Tier>> legacy() {
        return Collections.unmodifiableMap(legacy);
    }

    /** 所有試煉加起來給幾個昇華點。 */
    public int totalPoints() {
        int total = 0;
        for (final Trial trial : trials.values()) {
            total += trial.points();
        }
        return total;
    }
}
