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
    /**
     * 一棵昇華小樹從起點往外最多幾層（選單 9 欄：第 0、2、4、6、8 欄放天賦，中間放連線）。
     * 2026-10-26 照 PoE2：最深 5 層、分岔的往下多一排（選單上下捲動），不再限制幾條分支；這次之前最多 4 條、每條 4 個。
     */
    public static final int MAX_DEPTH = 5;
    /** 一棵昇華小樹最多幾個天賦（含選項）。 */
    public static final int MAX_NODES = 40;
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
     * <p>
     * 2026-10-26 照 PoE2 0.5 的昇華樹：一個天賦可以接好幾個（分岔）、requires 可以寫好幾個（Ritualist、Invoker 有環：
     * 跟 PoE2 一樣「跟已配的天賦相連」就能配，連線不分方向）；「選一個」的天賦（choice）底下的選項（option_of）只能配一個，
     * 選項不花點數（點數花在 choice 本身）。這次之前每個天賦只有一個 requires、一條分支一個接一個。
     *
     * @param requires 相連的、比較靠近起點的天賦（空的 = 直接接在起點；第一個是畫面上畫在它左邊的那個）
     * @param cost     幾個昇華點（選項是 0）
     * @param icon     原版物品名稱
     * @param stats    給的修飾器（引擎格式）
     * @param note     補充說明（可以沒有）
     * @param lines    說明行（照原作寫；空的 = 照 stats 自動寫）
     * @param optionOf 這是哪個「選一個」天賦的選項（null = 不是選項）
     * @param choice   這是「選一個」的天賦（配了之後從底下的選項選一個）
     * @param notable  核心天賦（原作的大天賦；選單上發光、選昇華的畫面只列這些）
     * @param unlock   2026-10-27 照 PoE2（靈狩者「聖潔一統」的 unlock_constraint）：這幾個都配了才能配（空的 = 沒有這個條件）；
     *                 這種天賦接在起點、可以不花點數（cost: 0），不當作連線的路
     */
    public record Node(String id, String name, List<String> requires, int cost, String icon, List<Modifier> stats, String note,
                       List<String> lines, String optionOf, boolean choice, boolean notable, List<String> unlock) {
        public Node {
            requires = List.copyOf(requires);
            stats = List.copyOf(stats);
            lines = List.copyOf(lines);
            unlock = unlock == null ? List.of() : List.copyOf(unlock);
        }

        /** 2026-10-27 以前的寫法（沒有 unlock）。 */
        public Node(final String id, final String name, final List<String> requires, final int cost, final String icon, final List<Modifier> stats,
                    final String note, final List<String> lines, final String optionOf, final boolean choice, final boolean notable) {
            this(id, name, requires, cost, icon, stats, note, lines, optionOf, choice, notable, List.of());
        }

        /** 2026-10-26 以前的寫法（一個 requires；說明照 stats 寫）。 */
        public Node(final String id, final String name, final String requires, final int cost, final String icon, final List<Modifier> stats,
                    final String note) {
            this(id, name, requires == null ? List.of() : List.of(requires), cost, icon, stats, note, List.of(), null, false, false, List.of());
        }

        /** 直接接在起點（不用先配別的）。 */
        public boolean root() {
            return requires.isEmpty();
        }

        /**
         * 2026-10-27 照站長（血術士的占血術免費）：直接接在起點、不花點數、沒有 unlock 的天賦——選了這個昇華職業就自動配好，不能退。
         */
        public boolean granted() {
            return cost == 0 && root() && unlock.isEmpty() && !option();
        }

        /** 「選一個」天賦的選項。 */
        public boolean option() {
            return optionOf != null;
        }

        /** 玩家看的說明：有寫 lines 就照 lines，沒有就照 stats 自動寫。 */
        public List<String> display() {
            return tw.exilecore.stats.KeywordText.distinct(lines.isEmpty() ? StatLines.describeAll(stats) : lines); // 2026-10-27：同一行只寫一次
        }

        /** 2026-10-27：補充說明（跟說明行一樣的、空的回傳 null）；玩家只看一次。 */
        public String shownNote() {
            if (note == null || note.isBlank() || display().contains(note)) {
                return null;
            }
            return note;
        }

        /** 2026-10-27 照站長：說明裡只寫了關鍵字的（撲殺、毀滅打擊…）自動補一行「關鍵字：說明」，每個只補一次。 */
        public List<String> explanations() {
            final List<String> shown = new java.util.ArrayList<>(display());
            if (shownNote() != null) {
                shown.add(shownNote());
            }
            return tw.exilecore.stats.KeywordText.explain(shown);
        }

        /** 2026-10-27：這個天賦賦予的技能（grant_skill.代號，照 stats 的順序）。 */
        public List<String> grantedSkills() {
            final List<String> out = new java.util.ArrayList<>();
            for (final Modifier modifier : stats) {
                if (modifier.stat().startsWith(AscKeys.GRANT_SKILL) && !out.contains(modifier.stat().substring(AscKeys.GRANT_SKILL.length()))) {
                    out.add(modifier.stat().substring(AscKeys.GRANT_SKILL.length()));
                }
            }
            return out;
        }
    }

    /** 昇華天賦在選單（9 欄）上的位置：第幾排、第幾層（離起點幾步，0 = 直接接在起點）。 */
    public record Cell(Node node, int row, int depth) {
    }

    /**
     * 衝等活動（2026-10）：每個昇華職業第一個升到 level 級的角色拿那個昇華的專屬稱號（全服只有一個，拿了就沒了，沒有加成）。
     *
     * @param enabled 開著
     * @param level   幾級
     * @param titles  昇華代號 → 稱號名稱（沒寫的用「巔峰」＋昇華名稱）
     */
    /**
     * @param firstTitle      全服第一個到 level 級的稱號（2026-10，只有一個；空白 = 不發）
     * @param reachTitle      到 level 級就有的稱號（2026-10，不限量；空白 = 不發）
     * @param reachExperience 擁有 reachTitle 的帳號經驗多幾 %（2026-10-22：帳號裡每一隻到 level 級的角色各算一次）
     * @param announceLevels  升到這些等級時全服廣播（2026-10）
     * @param reachMaxCharacters 燃星者進化最多算幾隻角色（2026-10-22：7 隻 × 5% = 35%）
     */
    public record LevelRace(boolean enabled, int level, java.util.Map<String, String> titles, String firstTitle, String reachTitle,
                            double reachExperience, List<Integer> announceLevels, int reachMaxCharacters) {
        public LevelRace {
            titles = java.util.Map.copyOf(titles);
            firstTitle = firstTitle == null ? "" : firstTitle.trim();
            reachTitle = reachTitle == null ? "" : reachTitle.trim();
            reachExperience = Math.max(0, reachExperience);
            announceLevels = announceLevels == null ? List.of() : List.copyOf(announceLevels);
            reachMaxCharacters = Math.max(1, reachMaxCharacters);
        }

        public LevelRace(final boolean enabled, final int level, final java.util.Map<String, String> titles) {
            this(enabled, level, titles, "百星之首", "燃星者", 5, List.of(100, 110, 120), 7);
        }

        public static final String FIRST_TITLE_ID = "race_first";
        public static final String REACH_TITLE_ID = "race_reach";
        /** 全服第一個的紀錄在 level_race 表裡的代號（不會跟昇華代號撞到）。 */
        public static final String FIRST_KEY = "*server";

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

        /** 直接接在起點的天賦（照設定檔的順序）。 */
        public List<Node> roots() {
            final List<Node> roots = new ArrayList<>();
            for (final Node node : nodes) {
                if (node.root()) {
                    roots.add(node);
                }
            }
            return roots;
        }

        /**
         * 畫在這個天賦右邊的那幾個（2026-10-26 分岔：可以好幾個；requires 的第一個是它的）。
         * 這次之前是 child()，只會回傳一個。
         */
        public List<Node> children(final String nodeId) {
            final List<Node> children = new ArrayList<>();
            for (final Node node : nodes) {
                if (!node.root() && nodeId.equals(node.requires().getFirst())) {
                    children.add(node);
                }
            }
            return children;
        }

        /** 「選一個」天賦的選項（照設定檔的順序）。 */
        public List<Node> options(final String choiceId) {
            final List<Node> options = new ArrayList<>();
            for (final Node node : nodes) {
                if (choiceId.equals(node.optionOf())) {
                    options.add(node);
                }
            }
            return options;
        }

        /** 跟這個天賦相連的（連線不分方向：它的 requires，加上 requires 寫了它的）。 */
        public List<Node> neighbours(final String nodeId) {
            final List<Node> list = new ArrayList<>();
            final Node self = node(nodeId);
            for (final Node node : nodes) {
                if (node.id().equals(nodeId)) {
                    continue;
                }
                if (node.requires().contains(nodeId) || self != null && self.requires().contains(node.id())) {
                    list.add(node);
                }
            }
            return list;
        }

        /**
         * 選單上的位置：從每個接在起點的天賦往右畫，分岔的往下多一排（每個沒有後續的天賦一排，前面的天賦跟第一個後續同一排）。
         * 繞成一圈的（requires 的第一個一路找回自己）畫不出來，略過。
         */
        public List<Cell> layout() {
            final List<Cell> cells = new ArrayList<>();
            final int[] row = {0};
            final Set<String> seen = new HashSet<>();
            for (final Node root : roots()) {
                place(root, 0, row, cells, seen);
            }
            return cells;
        }

        private void place(final Node node, final int depth, final int[] row, final List<Cell> cells, final Set<String> seen) {
            if (!seen.add(node.id())) {
                return;
            }
            cells.add(new Cell(node, row[0], depth));
            final List<Node> children = children(node.id());
            boolean first = true;
            for (final Node child : children) {
                if (seen.contains(child.id())) {
                    continue;
                }
                if (!first) {
                    row[0]++;
                }
                first = false;
                place(child, depth + 1, row, cells, seen);
            }
            if (node.root() && cells.getLast().row() == row[0]) {
                row[0]++; // 下一個接在起點的從新的一排開始
            }
        }

        /** 選單有幾排。 */
        public int rows() {
            int rows = 0;
            for (final Cell cell : layout()) {
                rows = Math.max(rows, cell.row() + 1);
            }
            return rows;
        }

        /**
         * 每一排的天賦（網頁規劃器的 branches；2026-10-26 以前是一條一條的分支，現在是選單上的每一排，
         * 分岔出去的那一排從分岔的地方開始，第一個天賦的 depth 說它在第幾層）。
         */
        public List<List<Node>> branches() {
            final List<List<Node>> rows = new ArrayList<>();
            for (final Cell cell : layout()) {
                while (rows.size() <= cell.row()) {
                    rows.add(new ArrayList<>());
                }
                rows.get(cell.row()).add(cell.node());
            }
            return rows;
        }
    }

    /** 分身獎勵的一階：這個等級起給這些修飾器。 */
    public record Tier(int level, List<Modifier> stats) {
        public Tier {
            stats = List.copyOf(stats);
        }
    }

    /**
     * 昇華天賦的版本（2026-10-26，比照天賦樹的 version）：改了昇華天賦就加 1，角色記的版本（asc.version）不一樣時
     * 已配的昇華天賦全部退回（昇華職業留著、點數拿回來）。沒寫 = 1。
     */
    private int version = 1;
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
        version = other.version;
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
        version = root.integer("version", 1);
        final YamlNode s = root.node("settings");
        settings = new Settings(s.integer("cooldown_seconds", 60), s.integer("weekly_opens", 8));
        final YamlNode race = root.node("level_race");
        final java.util.Map<String, String> raceTitles = new java.util.LinkedHashMap<>();
        final YamlNode titlesNode = race.node("titles");
        for (final String id : titlesNode.keys()) {
            raceTitles.put(id, titlesNode.string(id, "").trim());
        }
        final List<Integer> announce = new ArrayList<>();
        if (race.has("announce_levels")) {
            for (final String value : race.strings("announce_levels")) {
                try {
                    announce.add(Integer.parseInt(value.trim()));
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException("level_race.announce_levels：「" + value + "」不是數字");
                }
            }
        } else {
            announce.addAll(List.of(100, 110, 120));
        }
        levelRace = new LevelRace(race.bool("enabled", false), race.integer("level", 100), raceTitles,
                race.string("first_title", "百星之首"), race.string("reach_title", "燃星者"), race.decimal("reach_experience", 5), announce,
                race.integer("reach_max_characters", 7));

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
                // 2026-10-26：requires 可以寫一個或一串；選項（option_of）接在它的「選一個」天賦上、不花點數
                final String optionOf = blankToNull(n.string("option_of", null));
                final List<String> requires = new ArrayList<>();
                for (final String required : n.strings("requires")) {
                    if (!required.isBlank() && !requires.contains(required.trim())) {
                        requires.add(required.trim());
                    }
                }
                if (optionOf != null && requires.isEmpty()) {
                    requires.add(optionOf);
                }
                final List<String> lines = new ArrayList<>();
                for (final String line : n.strings("lines")) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
                final List<String> unlock = new ArrayList<>(); // 2026-10-27：都配了才能配（聖潔一統）
                for (final String required : n.strings("unlock")) {
                    if (!required.isBlank() && !unlock.contains(required.trim())) {
                        unlock.add(required.trim());
                    }
                }
                nodes.add(new Node(nodeId, nodeName, requires, n.integer("cost", optionOf != null ? 0 : 1),
                        n.string("icon", "AMETHYST_SHARD").trim(), stats(n.list("stats"), "昇華「" + nodeName + "」"),
                        blankToNull(n.string("note", null)), lines, optionOf, n.bool("choice", false), n.bool("notable", false), unlock));
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
            checkAscendancy(problems, ascendancy, classIds, totalPoints());
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

    private static void checkAscendancy(final List<String> problems, final Ascendancy ascendancy, final Collection<String> classIds,
                                        final int points) {
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
        if (ascendancy.nodes().size() > MAX_NODES) {
            problems.add(where + ".nodes：最多 " + MAX_NODES + " 個天賦");
        }
        final List<Integer> idLengths = new ArrayList<>();
        int choices = 0;
        for (final Node node : ascendancy.nodes()) {
            final String nodeWhere = where + ".nodes." + node.id();
            idLengths.add(node.id().length() + 1);
            if (node.name().isBlank()) {
                problems.add(nodeWhere + "：沒有名字");
            }
            if (node.option()) {
                final Node choice = ascendancy.node(node.optionOf());
                if (choice == null || !choice.choice()) {
                    problems.add(nodeWhere + ".option_of：" + node.optionOf() + " 不是這個昇華職業「選一個」的天賦（要寫 choice: true）");
                }
                if (!node.requires().equals(List.of(node.optionOf()))) {
                    problems.add(nodeWhere + ".requires：選項只能接在它的「選一個」天賦 " + node.optionOf() + " 上");
                }
                if (node.cost() != 0) {
                    problems.add(nodeWhere + ".cost：選項不花點數（點數花在「選一個」的天賦上）");
                }
            } else if (node.cost() < 1 && (node.unlock().isEmpty() && !node.root() || node.cost() < 0)) {
                problems.add(nodeWhere + ".cost：至少 1 點（寫了 unlock 的、直接接在起點的可以是 0）");
            }
            for (final String required : node.unlock()) {
                if (ascendancy.node(required) == null || required.equals(node.id()) || ascendancy.node(required).option()) {
                    problems.add(nodeWhere + ".unlock：這個昇華職業沒有（不是選項的）天賦 " + required);
                }
            }
            if (!node.unlock().isEmpty() && !node.root()) {
                problems.add(nodeWhere + ".unlock：寫了 unlock 的天賦要直接接在起點（不寫 requires）");
            }
            if (node.choice()) {
                choices++;
                if (ascendancy.options(node.id()).size() < 2) {
                    problems.add(nodeWhere + "：「選一個」的天賦至少要有兩個選項（option_of: " + node.id() + "）");
                }
            }
            for (final String required : node.requires()) {
                if (ascendancy.node(required) == null || required.equals(node.id())) {
                    problems.add(nodeWhere + ".requires：這個昇華職業沒有天賦 " + required);
                } else if (ascendancy.node(required).option()) {
                    problems.add(nodeWhere + ".requires：不能接在選項 " + required + " 後面");
                }
            }
            if (node.stats().isEmpty() && node.lines().isEmpty() && !node.choice()) {
                problems.add(nodeWhere + ".stats：沒有任何加成");
            }
            checkStats(problems, nodeWhere, node.stats());
        }
        // 存進角色進度的是配了的那幾個（最多 points 個花點數的＋每個「選一個」的一個選項）：最長的那幾個加起來要放得下
        idLengths.sort(java.util.Comparator.reverseOrder());
        int length = -1;
        for (int i = 0; i < Math.min(idLengths.size(), Math.max(1, points) + choices); i++) {
            length += idLengths.get(i);
        }
        if (length > NODES_VALUE_MAX) {
            problems.add(where + ".nodes：天賦代號太長（配滿可能要 " + length + " 字，最多 " + NODES_VALUE_MAX + "），代號短一點");
        }
        // 每個天賦都要接得到起點（連線不分方向）、畫得進選單（requires 的第一個一路往回找不能繞圈、最多 MAX_DEPTH 層）
        final Set<String> reached = new HashSet<>();
        final java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>(ascendancy.roots());
        while (!queue.isEmpty()) {
            final Node node = queue.poll();
            if (reached.add(node.id())) {
                queue.addAll(ascendancy.neighbours(node.id()));
            }
        }
        if (ascendancy.roots().isEmpty()) {
            problems.add(where + ".nodes：沒有直接接在起點的天賦（不寫 requires 的）");
        }
        final Map<String, Integer> depth = new LinkedHashMap<>();
        for (final Cell cell : ascendancy.layout()) {
            depth.put(cell.node().id(), cell.depth());
            if (cell.depth() >= MAX_DEPTH) {
                problems.add(where + ".nodes." + cell.node().id() + "：離起點太遠（最多 " + MAX_DEPTH + " 層，選單放不下）");
            }
        }
        for (final Node node : ascendancy.nodes()) {
            if (!reached.contains(node.id())) {
                problems.add(where + ".nodes." + node.id() + "：接不到起點");
            } else if (!depth.containsKey(node.id())) {
                problems.add(where + ".nodes." + node.id() + "：requires 的第一個繞成一圈了（畫不出來）");
            }
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

    /** 昇華天賦的版本（2026-10-26）。 */
    public int version() {
        return version;
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
