package tw.exilecore.life;

import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** data/life.yml：生活玩法「星骸之海」的設定。純資料，不依賴 Bukkit（材質只存名字）。 */
public final class LifeConfig {

    public static final String FILE = "life.yml";

    public enum Place { FLOOR, EDGE, ABOVE }

    public record Material(String id, String name, String icon) {
    }

    public record Drift(String id, double weight, String display, Map<String, int[]> gives, String kind, int[] rolls, int xp) {
        public boolean bottle() {
            return "bottle".equals(kind);
        }

        public boolean crate() {
            return "crate".equals(kind);
        }
    }

    public record Hook(int tier, String name, double range, int pullTicks, double doubleChance) {
    }

    /** station：研究台、淨水器、烤爐、料理台、海水盆、菜圃、灑水器（一般的筏方塊 null）。 */
    public record Item(String id, String name, String block, Place place, String description, String station) {
    }

    /** 料理（成品，可以交易）：吃了 minutes 分鐘的加成（同時只有一種）、回 stamina 點精力。 */
    public record Food(String id, String name, String icon, int minutes, int stamina, List<tw.exilecore.stats.Modifier> stats, String description) {
    }

    /** 研究：放進材料（用掉）解鎖配方。 */
    public record Research(String id, String name, int level, Map<String, Integer> needs, String description) {
    }

    /** 作物：planter 是 sea（海水盆）或 soil（菜圃）。 */
    public record Crop(String id, String name, String seed, String planter, int minutes, int water, boolean sprinkler,
                       Map<String, int[]> harvest, int[] seedsBack, String sprout, String ripe, int xp, int level) {
    }

    public record Fish(String material, double weight, int level, double nightWeight) {
    }

    public record Fishing(double[] waitSeconds, int biteTicks, int stamina, int xp, List<Fish> fish) {
    }

    public record Purifier(int cooldownSeconds, int water, int stamina, int xp) {
    }

    public record Shark(int minLevel, int[] intervalSeconds, String type, double life, double scale, int biteSeconds, int fleeSeconds,
                        Map<String, int[]> drops, int xp) {
    }

    /** item（筏的方塊、設施）、hook（升級星索鉤）、food（料理，要在 station 做）三選一；research 要先研究。 */
    public record Recipe(String id, String item, int hook, String food, int amount, Map<String, Integer> cost, int level, int stamina, int xp,
                         String research, String station) {
        public boolean hookUpgrade() {
            return hook > 0;
        }
    }

    public record SizeStep(int level, int size) {
    }

    /** 小島的守衛隊長（沉船的船長、環礁的守衛）。 */
    public record Captain(String name, String type, double life, double damage, List<String> skills) {
    }

    /** 小島。 */
    public record Island(String id, String name, int level, int stamina, int travelSeconds, int minutes, List<String> monsterTypes,
                         int[] count, int magic, int rare, Captain captain, int chests, Map<String, int[]> loot, double currencyChance,
                         List<String> currencies, double hornShard, boolean letter, int xp) {
    }

    public record Weather(String id, String name, double weight, int seconds, double drift, double starShard) {
    }

    public record Title(String id, String name, String color, List<tw.exilecore.stats.Modifier> stats) {
    }

    /** 淵鯨的一個階段：生命低於 at 進入（傷害更多 damage%；shieldCount &gt; 0 叫出潮核）。 */
    public record WhalePhase(double at, String name, String message, double damage, int shieldCount, String shieldName, double shieldLife,
                             int shieldSeconds, double shieldHeal) {
    }

    /** 淵鯨的掉落：kind 是 unique／skill／support／currency／material／pool。 */
    public record WhaleDrop(String kind, String id, int amount, double chance) {
    }

    /** 深淵巨獸「淵鯨」。 */
    public record Whale(String name, String type, String like, double life, double damage, double scale, List<String> mods, int attempts,
                        int enrageAfter, double enrageDamage, int minutes, int xp, String hornName, Map<String, Integer> hornCost, int hornLevel,
                        String intro, String defeat, List<tw.exilecore.skill.BossSkillUse> skills, List<String[]> hazards, List<WhalePhase> phases,
                        List<WhaleDrop> drops) {
    }

    private boolean enabled;
    private String name = "星骸之海";
    private String color = "#3fc8e8";
    private ZoneId timezone = ZoneId.of("Asia/Taipei");
    private int plotSpacing = 512;
    private int plotsPerRow = 40;
    private int seaLevel = 62;
    private int roamRadius = 200;
    private int buildHeight = 12;
    private int staminaBase = 100;
    private int staminaPer5 = 5;
    private int staminaMax = 150;
    private int maxLevel = 50;
    private double levelBase = 20;
    private double levelFactor = 6;
    private double levelExponent = 1.5;
    private List<SizeStep> sizes = List.of(new SizeStep(1, 4));
    private List<Hook> hooks = List.of();
    private Map<String, Material> materials = Map.of();
    private double[] spawnSeconds = {1.0, 2.2};
    private int maxDrifts = 20;
    private double driftSpeed = 0.06;
    private int driftStamina = 0;   // 2026-10-16：撈漂流物花幾點精力（0 = 不花）
    private List<Drift> drifts = List.of();
    private double bottleCurrencyChance = 0.3;
    private int bottleDailyCurrency = 10;
    private List<String> bottleCurrencies = List.of();
    private List<String> letters = List.of();
    private int netCapacity = 10;
    private double netRadius = 1.6;
    private Map<String, Item> items = Map.of();
    private List<Recipe> recipes = List.of();
    private Map<String, Food> foods = Map.of();
    private Map<String, Research> research = Map.of();
    private Map<String, Crop> crops = Map.of();
    private Fishing fishing = new Fishing(new double[] {3, 8}, 30, 1, 4, List.of());
    private Purifier purifier = new Purifier(60, 2, 1, 2);
    private Shark shark;
    private String freshWater = "fresh_water";
    private int drinkStamina = 5;
    private int dailyRestore = 40;
    private Map<String, Island> islands = Map.of();
    private int[] weatherInterval = {600, 900};
    private List<Weather> weathers = List.of();
    private Map<String, Title> titles = Map.of();
    private Whale whale;

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        final LifeConfig fresh = new LifeConfig();
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        } else {
            fresh.enabled = false;
        }
        copy(fresh);
    }

    private void copy(final LifeConfig o) {
        enabled = o.enabled;
        name = o.name;
        color = o.color;
        timezone = o.timezone;
        plotSpacing = o.plotSpacing;
        plotsPerRow = o.plotsPerRow;
        seaLevel = o.seaLevel;
        roamRadius = o.roamRadius;
        buildHeight = o.buildHeight;
        staminaBase = o.staminaBase;
        staminaPer5 = o.staminaPer5;
        staminaMax = o.staminaMax;
        maxLevel = o.maxLevel;
        levelBase = o.levelBase;
        levelFactor = o.levelFactor;
        levelExponent = o.levelExponent;
        sizes = o.sizes;
        hooks = o.hooks;
        materials = o.materials;
        spawnSeconds = o.spawnSeconds;
        maxDrifts = o.maxDrifts;
        driftSpeed = o.driftSpeed;
        driftStamina = o.driftStamina;
        drifts = o.drifts;
        bottleCurrencyChance = o.bottleCurrencyChance;
        bottleDailyCurrency = o.bottleDailyCurrency;
        bottleCurrencies = o.bottleCurrencies;
        letters = o.letters;
        netCapacity = o.netCapacity;
        netRadius = o.netRadius;
        items = o.items;
        recipes = o.recipes;
        foods = o.foods;
        research = o.research;
        crops = o.crops;
        fishing = o.fishing;
        purifier = o.purifier;
        shark = o.shark;
        freshWater = o.freshWater;
        drinkStamina = o.drinkStamina;
        dailyRestore = o.dailyRestore;
        islands = o.islands;
        weatherInterval = o.weatherInterval;
        weathers = o.weathers;
        titles = o.titles;
        whale = o.whale;
    }

    void parse(final YamlNode root) {
        final YamlNode s = root.node("settings");
        enabled = s.bool("enabled", true);
        name = s.string("name", name).trim();
        color = s.string("color", color).trim();
        final String zone = s.string("timezone", timezone.getId()).trim();
        try {
            timezone = ZoneId.of(zone);
        } catch (final java.time.DateTimeException e) {
            throw new IllegalArgumentException(s.path() + ".timezone：不認得的時區「" + zone + "」");
        }
        plotSpacing = Math.max(128, s.integer("plot_spacing", plotSpacing));
        plotsPerRow = Math.max(1, s.integer("plots_per_row", plotsPerRow));
        seaLevel = Math.max(10, Math.min(200, s.integer("sea_level", seaLevel)));
        roamRadius = Math.max(32, Math.min(plotSpacing / 2 - 8, s.integer("roam_radius", roamRadius)));
        buildHeight = Math.max(3, Math.min(40, s.integer("build_height", buildHeight)));

        final YamlNode st = root.node("stamina");
        staminaBase = Math.max(1, st.integer("base", staminaBase));
        staminaPer5 = Math.max(0, st.integer("per_5_levels", staminaPer5));
        staminaMax = Math.max(staminaBase, st.integer("max", staminaMax));

        final YamlNode lv = root.node("level");
        maxLevel = Math.max(1, lv.integer("max", maxLevel));
        levelBase = Math.max(1, lv.decimal("base", levelBase));
        levelFactor = Math.max(0, lv.decimal("factor", levelFactor));
        levelExponent = Math.max(0.5, lv.decimal("exponent", levelExponent));

        final List<SizeStep> steps = new ArrayList<>();
        for (final YamlNode node : root.list("raft_size")) {
            steps.add(new SizeStep(Math.max(1, node.integer("level", 1)), Math.max(2, Math.min(plotSpacing / 2, node.integer("size", 4)))));
        }
        steps.sort(java.util.Comparator.comparingInt(SizeStep::level));
        if (steps.isEmpty() || steps.getFirst().level() > 1) {
            steps.addFirst(new SizeStep(1, 4));
        }
        sizes = List.copyOf(steps);

        final List<Hook> hookList = new ArrayList<>();
        for (final YamlNode node : root.list("hooks")) {
            hookList.add(new Hook(Math.max(0, node.integer("tier", hookList.size())), node.string("name", "星索鉤").trim(),
                    Math.max(3, node.decimal("range", 8)), Math.max(1, node.integer("pull_ticks", 8)),
                    Math.max(0, Math.min(1, node.decimal("double", 0)))));
        }
        hookList.sort(java.util.Comparator.comparingInt(Hook::tier));
        if (hookList.isEmpty()) {
            hookList.add(new Hook(0, "繩索鉤", 8, 8, 0));
        }
        for (int i = 0; i < hookList.size(); i++) {
            if (hookList.get(i).tier() != i) {
                throw new IllegalArgumentException("hooks：tier 要從 0 開始連續（第 " + (i + 1) + " 個是 " + hookList.get(i).tier() + "）");
            }
        }
        hooks = List.copyOf(hookList);

        final Map<String, Material> mats = new LinkedHashMap<>();
        final YamlNode m = root.node("materials");
        for (final String id : m.keys()) {
            final YamlNode node = m.node(id);
            mats.put(id, new Material(id, node.requireString("name").trim(), node.string("icon", "PAPER").trim().toUpperCase(Locale.ROOT)));
        }
        materials = Collections.unmodifiableMap(mats);

        final YamlNode d = root.node("drift");
        if (d.has("spawn_seconds")) {
            final List<String> range = d.strings("spawn_seconds");
            try {
                final double low = Double.parseDouble(range.get(0).trim());
                final double high = range.size() > 1 ? Double.parseDouble(range.get(1).trim()) : low;
                spawnSeconds = new double[] {Math.max(0.2, low), Math.max(Math.max(0.2, low), high)};
            } catch (final RuntimeException e) {
                throw new IllegalArgumentException(d.path() + ".spawn_seconds：要寫 [最短, 最長] 秒");
            }
        }
        maxDrifts = Math.max(1, d.integer("max_per_player", maxDrifts));
        driftSpeed = Math.max(0.01, Math.min(0.5, d.decimal("speed", driftSpeed)));
        driftStamina = Math.max(0, d.integer("stamina", driftStamina));
        final List<Drift> driftList = new ArrayList<>();
        for (final YamlNode node : d.list("items")) {
            final String id = node.requireString("id").trim();
            final Map<String, int[]> gives = new LinkedHashMap<>();
            final YamlNode g = node.node("gives");
            for (final String mat : g.keys()) {
                final int[] r = g.range(mat);
                gives.put(mat, new int[] {Math.max(0, r[0]), Math.max(Math.max(0, r[0]), r[1])});
            }
            final String kind = node.string("kind", "").trim();
            if (gives.isEmpty() && kind.isEmpty()) {
                throw new IllegalArgumentException(node.path() + "：漂流物要寫 gives 或 kind（bottle、crate）");
            }
            driftList.add(new Drift(id, Math.max(0, node.decimal("weight", 1)), node.string("display", "PAPER").trim().toUpperCase(Locale.ROOT),
                    Collections.unmodifiableMap(gives), kind.isEmpty() ? null : kind, node.has("rolls") ? node.range("rolls") : new int[] {3, 5},
                    Math.max(0, node.integer("xp", 3))));
        }
        drifts = List.copyOf(driftList);

        final YamlNode b = root.node("bottle");
        bottleCurrencyChance = Math.max(0, Math.min(1, b.decimal("currency_chance", bottleCurrencyChance)));
        bottleDailyCurrency = Math.max(0, b.integer("daily_currency", bottleDailyCurrency));
        bottleCurrencies = List.copyOf(b.strings("currencies"));
        letters = List.copyOf(b.strings("letters"));

        final YamlNode n = root.node("net");
        netCapacity = Math.max(1, n.integer("capacity", netCapacity));
        netRadius = Math.max(0.5, Math.min(4, n.decimal("radius", netRadius)));

        final Map<String, Item> itemMap = new LinkedHashMap<>();
        final YamlNode it = root.node("items");
        for (final String id : it.keys()) {
            final YamlNode node = it.node(id);
            final Place place;
            try {
                place = Place.valueOf(node.string("place", "above").trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(node.path() + ".place：要寫 floor、edge 或 above");
            }
            final String station = node.string("station", "").trim();
            if (!station.isEmpty() && !STATIONS.contains(station)) {
                throw new IllegalArgumentException(node.path() + ".station：要寫 " + String.join("、", STATIONS));
            }
            itemMap.put(id, new Item(id, node.requireString("name").trim(), node.requireString("block").trim().toUpperCase(Locale.ROOT), place,
                    node.string("description", "").trim(), station.isEmpty() ? null : station));
        }
        final java.util.Set<String> blocks = new java.util.HashSet<>();
        for (final Item item : itemMap.values()) {
            if (!blocks.add(item.block())) {
                throw new IllegalArgumentException("items：方塊 " + item.block() + " 重複了（拆下來要分得出是哪一樣）");
            }
        }
        items = Collections.unmodifiableMap(itemMap);

        final List<Recipe> recipeList = new ArrayList<>();
        final java.util.Set<String> ids = new java.util.HashSet<>();
        for (final YamlNode node : root.list("recipes")) {
            final String id = node.requireString("id").trim();
            if (!ids.add(id)) {
                throw new IllegalArgumentException(node.path() + ".id：重複的 " + id);
            }
            final Map<String, Integer> cost = new LinkedHashMap<>();
            final YamlNode c = node.node("cost");
            for (final String mat : c.keys()) {
                cost.put(mat, Math.max(1, c.integer(mat, 1)));
            }
            final String item = node.string("item", null);
            final int hook = Math.max(0, node.integer("hook", 0));
            final String food = node.string("food", null);
            if ((item == null ? 0 : 1) + (hook == 0 ? 0 : 1) + (food == null ? 0 : 1) != 1) {
                throw new IllegalArgumentException(node.path() + "：配方要寫 item（做出什麼）、hook（升級星索鉤到第幾階）或 food（料理），只能寫一個");
            }
            if (item != null && !itemMap.containsKey(item.trim())) {
                throw new IllegalArgumentException(node.path() + ".item：items 裡沒有 " + item);
            }
            if (hook >= hooks.size()) {
                throw new IllegalArgumentException(node.path() + ".hook：hooks 只有到第 " + (hooks.size() - 1) + " 階");
            }
            final String station = node.string("station", "").trim();
            if (!station.isEmpty() && !STATIONS.contains(station)) {
                throw new IllegalArgumentException(node.path() + ".station：要寫 " + String.join("、", STATIONS));
            }
            if (food != null && station.isEmpty()) {
                throw new IllegalArgumentException(node.path() + "：料理要寫 station（grill 烤爐或 kitchen 料理台）");
            }
            final String need = node.string("research", "").trim();
            recipeList.add(new Recipe(id, item == null ? null : item.trim(), hook, food == null ? null : food.trim(), Math.max(1, node.integer("amount", 1)),
                    Collections.unmodifiableMap(cost), Math.max(1, node.integer("level", 1)), Math.max(0, node.integer("stamina", 0)),
                    Math.max(0, node.integer("xp", 0)), need.isEmpty() ? null : need, station.isEmpty() ? null : station));
        }
        recipes = List.copyOf(recipeList);

        parsePhaseTwo(root);
        parsePhaseThree(root);
    }

    /** 設施（items 的 station）。 */
    public static final List<String> STATIONS = List.of("research", "purifier", "grill", "kitchen", "planter_sea", "planter_soil", "sprinkler",
            "helm", "sail");

    /** 料理、研究、作物、釣魚、淨水、渦齒鯊、喝水。 */
    private void parsePhaseTwo(final YamlNode root) {
        final Map<String, Food> foodMap = new LinkedHashMap<>();
        final YamlNode f = root.node("foods");
        for (final String id : f.keys()) {
            final YamlNode node = f.node(id);
            final String foodName = node.requireString("name").trim();
            foodMap.put(id, new Food(id, foodName, node.string("icon", "COOKED_COD").trim().toUpperCase(Locale.ROOT),
                    Math.max(1, node.integer("minutes", 30)), Math.max(0, node.integer("stamina", 0)),
                    tw.exilecore.ascendancy.StatLines.parse(node.list("stats"), "料理「" + foodName + "」"), node.string("description", "").trim()));
        }
        foods = Collections.unmodifiableMap(foodMap);

        final Map<String, Research> researchMap = new LinkedHashMap<>();
        final YamlNode r = root.node("research");
        for (final String id : r.keys()) {
            final YamlNode node = r.node(id);
            final Map<String, Integer> needs = new LinkedHashMap<>();
            final YamlNode n = node.node("needs");
            for (final String mat : n.keys()) {
                needs.put(mat, Math.max(1, n.integer(mat, 1)));
            }
            researchMap.put(id, new Research(id, node.requireString("name").trim(), Math.max(1, node.integer("level", 1)),
                    Collections.unmodifiableMap(needs), node.string("description", "").trim()));
        }
        research = Collections.unmodifiableMap(researchMap);

        final Map<String, Crop> cropMap = new LinkedHashMap<>();
        final YamlNode c = root.node("crops");
        for (final String id : c.keys()) {
            final YamlNode node = c.node(id);
            final Map<String, int[]> harvest = new LinkedHashMap<>();
            final YamlNode h = node.node("harvest");
            for (final String mat : h.keys()) {
                final int[] range = h.range(mat);
                harvest.put(mat, new int[] {Math.max(0, range[0]), Math.max(Math.max(0, range[0]), range[1])});
            }
            final String planter = node.string("planter", "soil").trim();
            if (!planter.equals("sea") && !planter.equals("soil")) {
                throw new IllegalArgumentException(node.path() + ".planter：要寫 sea（海水盆）或 soil（菜圃）");
            }
            cropMap.put(id, new Crop(id, node.requireString("name").trim(), node.requireString("seed").trim(), planter,
                    Math.max(1, node.integer("minutes", 20)), Math.max(0, node.integer("water", 0)), node.bool("sprinkler", false),
                    Collections.unmodifiableMap(harvest), node.has("seeds_back") ? node.range("seeds_back") : new int[] {1, 2},
                    node.string("sprout", "WHEAT_SEEDS").trim().toUpperCase(Locale.ROOT), node.string("ripe", "WHEAT").trim().toUpperCase(Locale.ROOT),
                    Math.max(0, node.integer("xp", 5)), Math.max(1, node.integer("level", 1))));
        }
        crops = Collections.unmodifiableMap(cropMap);

        final YamlNode fi = root.node("fishing");
        final List<Fish> fishList = new ArrayList<>();
        for (final YamlNode node : fi.list("fish")) {
            fishList.add(new Fish(node.requireString("material").trim(), Math.max(0, node.decimal("weight", 1)), Math.max(1, node.integer("level", 1)),
                    Math.max(0, node.decimal("night_weight", node.decimal("weight", 1)))));
        }
        double[] wait = {3, 8};
        if (fi.has("wait_seconds")) {
            final List<String> range = fi.strings("wait_seconds");
            wait = new double[] {Math.max(0.5, Double.parseDouble(range.get(0).trim())),
                    Math.max(0.5, Double.parseDouble(range.get(range.size() > 1 ? 1 : 0).trim()))};
        }
        fishing = new Fishing(wait, Math.max(5, fi.integer("bite_ticks", 30)), Math.max(0, fi.integer("stamina", 1)),
                Math.max(0, fi.integer("xp", 4)), List.copyOf(fishList));

        final YamlNode pu = root.node("purifier");
        purifier = new Purifier(Math.max(1, pu.integer("cooldown_seconds", 60)), Math.max(1, pu.integer("water", 2)),
                Math.max(0, pu.integer("stamina", 1)), Math.max(0, pu.integer("xp", 2)));

        final YamlNode w = root.node("water");
        freshWater = w.string("material", freshWater).trim();
        drinkStamina = Math.max(0, w.integer("drink_stamina", drinkStamina));
        dailyRestore = Math.max(0, w.integer("daily_restore", dailyRestore));

        final YamlNode sh = root.node("shark");
        if (root.has("shark") && sh.bool("enabled", true)) {
            final Map<String, int[]> drops = new LinkedHashMap<>();
            final YamlNode d = sh.node("drops");
            for (final String mat : d.keys()) {
                final int[] range = d.range(mat);
                drops.put(mat, new int[] {Math.max(0, range[0]), Math.max(Math.max(0, range[0]), range[1])});
            }
            shark = new Shark(Math.max(1, sh.integer("min_level", 5)), sh.has("interval_seconds") ? sh.range("interval_seconds") : new int[] {240, 420},
                    sh.string("type", "DOLPHIN").trim().toUpperCase(Locale.ROOT), Math.max(0.1, sh.decimal("life", 3)),
                    Math.max(0.3, sh.decimal("scale", 1.5)), Math.max(1, sh.integer("bite_seconds", 5)), Math.max(10, sh.integer("flee_seconds", 90)),
                    Collections.unmodifiableMap(drops), Math.max(0, sh.integer("xp", 20)));
        } else {
            shark = null;
        }
    }

    /** 小島、海象、稱號、淵鯨。 */
    private void parsePhaseThree(final YamlNode root) {
        final Map<String, Island> islandMap = new LinkedHashMap<>();
        final YamlNode is = root.node("islands");
        for (final String id : is.keys()) {
            if (!ISLAND_KINDS.contains(id)) {
                throw new IllegalArgumentException(is.path() + "." + id + "：小島只有 " + String.join("、", ISLAND_KINDS) + "（形狀寫在程式裡）");
            }
            final YamlNode node = is.node(id);
            final YamlNode m = node.node("monsters");
            final List<String> types = new ArrayList<>();
            for (final String type : m.strings("types")) {
                types.add(type.trim().toUpperCase(Locale.ROOT));
            }
            if (types.isEmpty()) {
                types.add("ZOMBIE");
            }
            Captain captain = null;
            if (node.has("captain")) {
                final YamlNode c = node.node("captain");
                captain = new Captain(c.requireString("name").trim(), c.string("type", "WITHER_SKELETON").trim().toUpperCase(Locale.ROOT),
                        Math.max(0.1, c.decimal("life", 8)), Math.max(0.1, c.decimal("damage", 1.4)), List.copyOf(c.strings("skills")));
            }
            final Map<String, int[]> loot = new LinkedHashMap<>();
            final YamlNode l = node.node("loot");
            for (final String mat : l.keys()) {
                final int[] range = l.range(mat);
                loot.put(mat, new int[] {Math.max(0, range[0]), Math.max(Math.max(0, range[0]), range[1])});
            }
            final YamlNode cur = node.node("currency");
            islandMap.put(id, new Island(id, node.requireString("name").trim(), Math.max(1, node.integer("level", 1)),
                    Math.max(0, node.integer("stamina", 10)), Math.max(5, node.integer("travel_seconds", 60)), Math.max(3, node.integer("minutes", 20)),
                    List.copyOf(types), m.has("count") ? m.range("count") : new int[] {4, 6}, Math.max(0, m.integer("magic", 1)),
                    Math.max(0, m.integer("rare", 0)), captain, Math.max(0, node.integer("chests", 1)), Collections.unmodifiableMap(loot),
                    Math.max(0, Math.min(1, cur.decimal("chance", 0))), List.copyOf(cur.strings("ids")),
                    Math.max(0, Math.min(1, node.decimal("horn_shard", 0))), node.bool("letter", false), Math.max(0, node.integer("xp", 40))));
        }
        islands = Collections.unmodifiableMap(islandMap);

        final YamlNode w = root.node("weather");
        weatherInterval = w.has("interval_seconds") ? w.range("interval_seconds") : new int[] {600, 900};
        final List<Weather> list = new ArrayList<>();
        for (final YamlNode node : w.list("events")) {
            list.add(new Weather(node.requireString("id").trim(), node.requireString("name").trim(), Math.max(0, node.decimal("weight", 1)),
                    Math.max(10, node.integer("seconds", 120)), Math.max(0, node.decimal("drift", 1)), Math.max(0, node.decimal("star_shard", 1))));
        }
        weathers = List.copyOf(list);

        final Map<String, Title> titleMap = new LinkedHashMap<>();
        final YamlNode t = root.node("titles");
        for (final String id : t.keys()) {
            final YamlNode node = t.node(id);
            final String titleName = node.requireString("name").trim();
            titleMap.put(id, new Title(id, titleName, node.string("color", "#ffffff").trim(),
                    tw.exilecore.ascendancy.StatLines.parse(node.list("stats"), "稱號「" + titleName + "」")));
        }
        titles = Collections.unmodifiableMap(titleMap);

        whale = null;
        if (root.has("whale")) {
            final YamlNode wh = root.node("whale");
            final YamlNode horn = wh.node("horn");
            final Map<String, Integer> hornCost = new LinkedHashMap<>();
            final YamlNode hc = horn.node("cost");
            for (final String mat : hc.keys()) {
                hornCost.put(mat, Math.max(1, hc.integer(mat, 1)));
            }
            final List<String[]> hazards = new ArrayList<>();
            for (final YamlNode h : wh.list("hazards")) {
                hazards.add(new String[] {h.requireString("skill").trim(), String.valueOf(Math.max(2, h.integer("every", 10))),
                        String.valueOf(Math.max(0, h.integer("phase", 0)))});
            }
            final List<WhalePhase> phases = new ArrayList<>();
            for (final YamlNode ph : wh.list("phases")) {
                final YamlNode sh = ph.node("shield");
                phases.add(new WhalePhase(Math.max(0.01, Math.min(0.99, ph.decimal("at", 0.5))), ph.string("name", "").trim(),
                        ph.string("message", "").trim(), Math.max(0, ph.decimal("damage", 0)), Math.max(0, sh.integer("count", 0)),
                        sh.string("name", "潮核").trim(), Math.max(0.1, sh.decimal("life", 5)), Math.max(5, sh.integer("duration", 30)),
                        Math.max(0, sh.decimal("heal", 25))));
            }
            phases.sort((a, b) -> Double.compare(b.at(), a.at()));
            final List<WhaleDrop> drops = new ArrayList<>();
            for (final YamlNode d : wh.list("drops")) {
                String kind = null;
                for (final String k : List.of("unique", "skill", "support", "currency", "material", "pool")) {
                    if (d.has(k)) {
                        kind = k;
                    }
                }
                if (kind == null) {
                    throw new IllegalArgumentException(d.path() + "：要寫 unique、skill、support、currency、material 或 pool 其中一個");
                }
                drops.add(new WhaleDrop(kind, d.requireString(kind).trim(), Math.max(1, d.integer("amount", 1)), Math.max(0, Math.min(1, d.decimal("chance", 0)))));
            }
            final YamlNode enrage = wh.node("enrage");
            whale = new Whale(wh.requireString("name").trim(), wh.string("type", "HAPPY_GHAST").trim().toUpperCase(Locale.ROOT),
                    wh.string("like", "awakener").trim(), Math.max(0.1, wh.decimal("life", 50)), Math.max(0.1, wh.decimal("damage", 2.9)),
                    Math.max(0.3, wh.decimal("scale", 1)), List.copyOf(wh.strings("mods")), Math.max(1, wh.integer("attempts", 2)),
                    Math.max(30, enrage.integer("after", 360)), Math.max(0, enrage.decimal("damage", 150)), Math.max(3, wh.integer("minutes", 10)),
                    Math.max(0, wh.integer("xp", 500)), horn.string("name", "深淵號角").trim(), Collections.unmodifiableMap(hornCost),
                    Math.max(1, horn.integer("level", 40)), wh.string("intro", "").trim(), wh.string("defeat", "").trim(),
                    List.copyOf(tw.exilecore.skill.BossSkillUse.parseList(wh, "skills")), List.copyOf(hazards), List.copyOf(phases), List.copyOf(drops));
        }
    }

    /** 小島的種類（形狀寫在 LifeIslands）。 */
    public static final List<String> ISLAND_KINDS = List.of("reef", "ruin", "wreck", "atoll");

    /** 材料、通貨、方塊名字有沒有寫錯。 */
    public List<String> validate(final Predicate<String> currencyExists, final Predicate<String> materialExists) {
        final List<String> problems = new ArrayList<>();
        for (final Material material : materials.values()) {
            if (!materialExists.test(material.icon())) {
                problems.add("materials." + material.id() + ".icon：不認得的物品 " + material.icon());
            }
        }
        for (final Drift drift : drifts) {
            if (!materialExists.test(drift.display())) {
                problems.add("drift." + drift.id() + ".display：不認得的物品 " + drift.display());
            }
            for (final String mat : drift.gives().keySet()) {
                if (!materials.containsKey(mat)) {
                    problems.add("drift." + drift.id() + ".gives：materials 裡沒有 " + mat);
                }
            }
            if (drift.kind() != null && !drift.bottle() && !drift.crate()) {
                problems.add("drift." + drift.id() + ".kind：要寫 bottle 或 crate");
            }
        }
        for (final String currency : bottleCurrencies) {
            if (!currencyExists.test(currency)) {
                problems.add("bottle.currencies：currencies.yml 沒有 " + currency);
            }
        }
        for (final Item item : items.values()) {
            if (!materialExists.test(item.block())) {
                problems.add("items." + item.id() + ".block：不認得的方塊 " + item.block());
            }
        }
        for (final Recipe recipe : recipes) {
            for (final String mat : recipe.cost().keySet()) {
                if (!materials.containsKey(mat)) {
                    problems.add("recipes." + recipe.id() + ".cost：materials 裡沒有 " + mat);
                }
            }
            if (recipe.food() != null && !foods.containsKey(recipe.food())) {
                problems.add("recipes." + recipe.id() + ".food：foods 裡沒有 " + recipe.food());
            }
            if (recipe.research() != null && !research.containsKey(recipe.research())) {
                problems.add("recipes." + recipe.id() + ".research：research 裡沒有 " + recipe.research());
            }
        }
        for (final Food food : foods.values()) {
            if (!materialExists.test(food.icon())) {
                problems.add("foods." + food.id() + ".icon：不認得的物品 " + food.icon());
            }
        }
        for (final Research entry : research.values()) {
            for (final String mat : entry.needs().keySet()) {
                if (!materials.containsKey(mat)) {
                    problems.add("research." + entry.id() + ".needs：materials 裡沒有 " + mat);
                }
            }
        }
        for (final Crop crop : crops.values()) {
            if (!materials.containsKey(crop.seed())) {
                problems.add("crops." + crop.id() + ".seed：materials 裡沒有 " + crop.seed());
            }
            for (final String mat : crop.harvest().keySet()) {
                if (!materials.containsKey(mat)) {
                    problems.add("crops." + crop.id() + ".harvest：materials 裡沒有 " + mat);
                }
            }
            if (!materialExists.test(crop.sprout()) || !materialExists.test(crop.ripe())) {
                problems.add("crops." + crop.id() + "：sprout／ripe 不認得的物品");
            }
        }
        for (final Fish fish : fishing.fish()) {
            if (!materials.containsKey(fish.material())) {
                problems.add("fishing.fish：materials 裡沒有 " + fish.material());
            }
        }
        if (!foods.isEmpty() || !crops.isEmpty()) {
            if (!materials.containsKey(freshWater)) {
                problems.add("water.material：materials 裡沒有 " + freshWater);
            }
        }
        for (final Island island : islands.values()) {
            for (final String mat : island.loot().keySet()) {
                if (!materials.containsKey(mat)) {
                    problems.add("islands." + island.id() + ".loot：materials 裡沒有 " + mat);
                }
            }
            for (final String currency : island.currencies()) {
                if (!currencyExists.test(currency)) {
                    problems.add("islands." + island.id() + ".currency：currencies.yml 沒有 " + currency);
                }
            }
            for (final String type : island.monsterTypes()) {
                if (!entityExists(type)) {
                    problems.add("islands." + island.id() + ".monsters：不認得的生物 " + type);
                }
            }
            if (island.captain() != null && !entityExists(island.captain().type())) {
                problems.add("islands." + island.id() + ".captain：不認得的生物 " + island.captain().type());
            }
        }
        if (!islands.isEmpty() && !materials.containsKey("horn_shard")) {
            problems.add("materials：小島要有 horn_shard（深淵號角碎片）");
        }
        if (whale != null) {
            if (!entityExists(whale.type())) {
                problems.add("whale.type：不認得的生物 " + whale.type());
            }
            for (final String mat : whale.hornCost().keySet()) {
                if (!materials.containsKey(mat)) {
                    problems.add("whale.horn.cost：materials 裡沒有 " + mat);
                }
            }
            for (final WhaleDrop drop : whale.drops()) {
                if ("currency".equals(drop.kind()) && !currencyExists.test(drop.id())) {
                    problems.add("whale.drops：currencies.yml 沒有 " + drop.id());
                }
                if ("material".equals(drop.kind()) && !materials.containsKey(drop.id())) {
                    problems.add("whale.drops：materials 裡沒有 " + drop.id());
                }
            }
        }
        if (shark != null) {
            for (final String mat : shark.drops().keySet()) {
                if (!materials.containsKey(mat)) {
                    problems.add("shark.drops：materials 裡沒有 " + mat);
                }
            }
        }
        return problems;
    }

    // ---- 計算 ----

    /** 從 level 升到 level+1 要幾點生活經驗。 */
    public long xpToNext(final int level) {
        if (level >= maxLevel) {
            return 0;
        }
        return Math.round(levelBase + levelFactor * Math.pow(level, levelExponent));
    }

    /** 總生活經驗是幾級（1 起）。 */
    public int levelOf(final long totalXp) {
        int level = 1;
        long left = totalXp;
        while (level < maxLevel) {
            final long need = xpToNext(level);
            if (left < need) {
                break;
            }
            left -= need;
            level++;
        }
        return level;
    }

    /** 到 level 級總共要幾點。 */
    public long xpAt(final int level) {
        long total = 0;
        for (int l = 1; l < Math.min(level, maxLevel); l++) {
            total += xpToNext(l);
        }
        return total;
    }

    /** 這個等級的筏最大幾×幾。 */
    public int raftSize(final int level) {
        int size = sizes.getFirst().size();
        for (final SizeStep step : sizes) {
            if (level >= step.level()) {
                size = step.size();
            }
        }
        return size;
    }

    /** 下一次筏變大要幾級（已經最大回傳 -1）。 */
    public SizeStep nextSize(final int level) {
        for (final SizeStep step : sizes) {
            if (step.level() > level) {
                return step;
            }
        }
        return null;
    }

    /** 這個等級的精力上限。 */
    public int staminaMax(final int level) {
        return Math.min(staminaMax, staminaBase + staminaPer5 * (level / 5));
    }

    /** 某一階的鉤子（超過最高階用最高階）。 */
    public Hook hook(final int tier) {
        return hooks.get(Math.max(0, Math.min(hooks.size() - 1, tier)));
    }

    /** 方塊 → 做出它的東西（不是筏的方塊 null）。 */
    public Item itemByBlock(final String block) {
        for (final Item item : items.values()) {
            if (item.block().equals(block)) {
                return item;
            }
        }
        return null;
    }

    /** 照權重抽一種漂流物。 */
    /** 抽一個漂流物；boost 是某個代號的權重倍數（星潮：星骸碎片 ×8）。 */
    public Drift rollDrift(final double roll, final String boosted, final double boost) {
        double total = 0;
        for (final Drift drift : drifts) {
            total += drift.weight() * (drift.id().equals(boosted) ? boost : 1);
        }
        double at = roll * total;
        for (final Drift drift : drifts) {
            at -= drift.weight() * (drift.id().equals(boosted) ? boost : 1);
            if (at < 0) {
                return drift;
            }
        }
        return drifts.getLast();
    }

    public Drift rollDrift(final double roll) {
        double total = 0;
        for (final Drift drift : drifts) {
            total += drift.weight();
        }
        double at = roll * total;
        for (final Drift drift : drifts) {
            at -= drift.weight();
            if (at < 0) {
                return drift;
            }
        }
        return drifts.getLast();
    }

    /** 今天（設定的時區）的代號，例如 20261016。 */
    public String day(final long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(timezone).toLocalDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    public boolean enabled() {
        return enabled;
    }

    public String name() {
        return name;
    }

    public String color() {
        return color;
    }

    public int plotSpacing() {
        return plotSpacing;
    }

    public int plotsPerRow() {
        return plotsPerRow;
    }

    public int seaLevel() {
        return seaLevel;
    }

    public int roamRadius() {
        return roamRadius;
    }

    public int buildHeight() {
        return buildHeight;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public List<SizeStep> sizes() {
        return sizes;
    }

    public List<Hook> hooks() {
        return hooks;
    }

    public Map<String, Material> materials() {
        return materials;
    }

    public double[] spawnSeconds() {
        return spawnSeconds.clone();
    }

    public int maxDrifts() {
        return maxDrifts;
    }

    public double driftSpeed() {
        return driftSpeed;
    }

    /** 撈一個漂流物花幾點精力（2026-10-16；0 = 不花）。 */
    public int driftStamina() {
        return driftStamina;
    }

    public List<Drift> drifts() {
        return drifts;
    }

    public double bottleCurrencyChance() {
        return bottleCurrencyChance;
    }

    public int bottleDailyCurrency() {
        return bottleDailyCurrency;
    }

    public List<String> bottleCurrencies() {
        return bottleCurrencies;
    }

    public List<String> letters() {
        return letters;
    }

    public int netCapacity() {
        return netCapacity;
    }

    public double netRadius() {
        return netRadius;
    }

    public Map<String, Item> items() {
        return items;
    }

    public List<Recipe> recipes() {
        return recipes;
    }

    private static boolean entityExists(final String type) {
        try {
            org.bukkit.entity.EntityType.valueOf(type);
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /** 淵鯨（沒寫 null）。 */
    public Whale whale() {
        return whale;
    }

    public Map<String, Island> islands() {
        return islands;
    }

    public int[] weatherInterval() {
        return weatherInterval.clone();
    }

    public List<Weather> weathers() {
        return weathers;
    }

    public Map<String, Title> titles() {
        return titles;
    }

    /** 照權重抽一種海象（沒有 null）。 */
    public Weather rollWeather(final double roll) {
        double total = 0;
        for (final Weather weather : weathers) {
            total += weather.weight();
        }
        double at = roll * total;
        for (final Weather weather : weathers) {
            at -= weather.weight();
            if (at < 0) {
                return weather;
            }
        }
        return weathers.isEmpty() ? null : weathers.getLast();
    }

    public Map<String, Food> foods() {
        return foods;
    }

    public Map<String, Research> research() {
        return research;
    }

    public Map<String, Crop> crops() {
        return crops;
    }

    public Fishing fishing() {
        return fishing;
    }

    public Purifier purifier() {
        return purifier;
    }

    /** 渦齒鯊（沒寫或關掉 null）。 */
    public Shark shark() {
        return shark;
    }

    public String freshWater() {
        return freshWater;
    }

    public int drinkStamina() {
        return drinkStamina;
    }

    public int dailyRestore() {
        return dailyRestore;
    }

    /** 照權重抽一條魚（level 是生活等級；night 晚上）。 */
    public String rollFish(final int level, final boolean night, final double roll) {
        double total = 0;
        for (final Fish fish : fishing.fish()) {
            if (level >= fish.level()) {
                total += night ? fish.nightWeight() : fish.weight();
            }
        }
        if (total <= 0) {
            return null; // 2026-10-18：沒有解鎖的魚（或權重都是 0）：不要落到下面回傳等級不夠的第一條
        }
        double at = roll * total;
        for (final Fish fish : fishing.fish()) {
            if (level >= fish.level()) {
                at -= night ? fish.nightWeight() : fish.weight();
                if (at < 0) {
                    return fish.material();
                }
            }
        }
        return fishing.fish().isEmpty() ? null : fishing.fish().getFirst().material();
    }

    /** 這個設施的方塊（items 裡 station 是它的；沒有 null）。 */
    public Item station(final String station) {
        for (final Item item : items.values()) {
            if (station.equals(item.station())) {
                return item;
            }
        }
        return null;
    }
}
