package tw.exilecore.bounty;

import tw.exilecore.ascendancy.StatLines;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 每日委託、每週回響、功勳兌換的設定（data/bounties.yml；純資料，不依賴 Bukkit）。
 * <p>
 * 先讀進一份新的，{@link #validate} 檢查過（區域、怪物詞綴、地圖主題對得上）才換上去。
 */
public final class BountyConfig {

    /** 經驗獎勵照幾級為止的預設值（劇情打完的等級）。 */
    public static final int DEFAULT_EXPERIENCE_LEVEL_CAP = 60;

    public static final String FILE = "bounties.yml";
    /** 每天最多幾個每日委託（帳號進度的鍵 bounty.d0 … bounty.d5）。 */
    public static final int MAX_DAILY = 6;
    /** 倉庫最多能多買幾頁。 */
    public static final int MAX_STASH_PAGES = 8;
    /** 最多多買幾個角色格。 */
    public static final int MAX_CHARACTER_SLOTS = 6;
    /** 稱號最多幾個字。 */
    public static final int TITLE_MAX = 8;
    /** 任務追蹤一行最多幾個字（委託的說明）。 */
    public static final int TEXT_MAX = 18;
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** 每日委託的目標。 */
    public enum Goal {
        /** 擊殺怪物（可以限魔法以上、稀有以上）。 */
        KILL,
        /** 從怪物身上撿回東西（機率，不佔背包）。 */
        COLLECT,
        /** 打倒 Boss（區域 Boss、地圖 Boss、回響都算）。 */
        BOSS,
        /** 完成地圖（地圖 Boss 倒下時在地圖裡）。 */
        MAPS
    }

    /** 算哪些怪。 */
    public enum Filter {
        ANY("any"),
        MAGIC("magic"),
        RARE("rare");

        private final String id;

        Filter(final String id) {
            this.id = id;
        }

        static Filter parse(final String text, final String where) {
            for (final Filter filter : values()) {
                if (filter.id.equals(text)) {
                    return filter;
                }
            }
            throw new IllegalArgumentException(where + "：rarity 要是 any、magic 或 rare，卻是「" + text + "」");
        }
    }

    /**
     * 一種每日委託。
     *
     * @param text       委託的說明（{count} 換成數量、{item} 換成東西的名字）
     * @param count      要幾個
     * @param item       COLLECT：撿的東西
     * @param chance     COLLECT：普通怪掉的機率（魔法怪 2 倍、稀有以上必掉）
     * @param merits     完成給的功勳
     * @param experience 完成給的經驗：升到下一級所需經驗的幾分之幾（0.15 = 15%；滿級沒有）
     * @param weight     抽到的權重
     */
    public record Template(String id, String text, Goal goal, int count, Filter filter, String item, double chance,
                           long merits, double experience, int weight) {

        /** 要能跑地圖的角色才抽得到。 */
        public boolean needsMaps() {
            return goal == Goal.MAPS;
        }

        /** 「擊殺 150 隻怪物」。 */
        public String describe() {
            return text.replace("{count}", String.valueOf(count)).replace("{item}", item == null ? "" : item);
        }
    }

    /**
     * 回響 Boss 血量掉到 at 以下時的階段（召喚那個區域的怪、加屬性、變快）。
     *
     * @param summon 召喚幾隻（那個區域的第一種原版怪物）
     */
    public record PhaseDef(double at, String name, String message, int summon, double speed, List<AffixMod> stats) {
        public PhaseDef {
            stats = List.copyOf(stats);
        }
    }

    /**
     * 每週回響：打倒過的區域 Boss 強化版，在自己的實例裡（地圖世界的格子），每個帳號每週一次獎勵。
     *
     * @param experience 經驗：升到下一級所需經驗的幾分之幾
     * @param prefix     Boss 名字前面加的字（「回響・」）
     * @param life       生命：區域 Boss 的幾倍
     * @param damage     傷害：區域 Boss 的幾倍
     * @param scale      體型：區域 Boss 的幾倍
     * @param mods       多加的怪物詞綴
     * @param quantity   掉落的物品數量加成（百分比點數）
     * @param rarity     物品稀有度加成（百分比點數）
     * @param rooms      版面大約幾個房間（含起點與 Boss 房，5–12）
     * @param attempts   每週最多開幾次（打倒前可以重開，例如全倒了；打倒就算完成）
     * @param themes     區域 → 版面主題
     */
    public record Weekly(boolean enabled, long merits, double experience, String prefix, double life, double damage, double scale,
                         List<String> mods, double quantity, double rarity, int rooms, int attempts, int cooldownSeconds,
                         Map<String, String> themes, String lore, PhaseDef phase) {
        public Weekly {
            mods = List.copyOf(mods);
            themes = Collections.unmodifiableMap(new LinkedHashMap<>(themes));
        }

        /** 這個區域的版面主題（沒寫用廢墟）。 */
        public String theme(final String zone) {
            return themes.getOrDefault(zone, "ruins");
        }
    }

    /**
     * 一個稱號（TAB 列表與聊天時名字前面的字）。
     *
     * @param stats 擁有就有的永久加成（像分身獎勵：整個帳號的角色都吃得到，不用戴；寫法跟昇華天賦一樣）
     */
    /**
     * 擊殺稱號（2026-10）：同一個帳號打倒這隻 Boss（boss = maps.yml 的終局代號，極境才算；heist_finale = 金庫之主）kills 次就發。
     */
    public record BossTitle(String boss, int kills, String bossName, Title title) {
    }

    public record Title(String id, String name, String color, long price, List<Modifier> stats) {
        public Title {
            stats = stats == null ? List.of() : List.copyOf(stats);
        }

        /** 沒有加成的稱號。 */
        public Title(final String id, final String name, final String color, final long price) {
            this(id, name, color, price, List.of());
        }
    }

    /**
     * 功勳兌換。
     *
     * @param stashPages     多買第 1、2… 頁倉庫的價格（買完就沒了）
     * @param flask          這個等級最好的生命或魔力藥劑一瓶
     * @param fragment       天穹碎片一片
     * @param fragmentWeekly 天穹碎片每週最多換幾片
     * @param characterSlots 多買第 1、2… 個角色格的價格（買完就沒了；帳號共用）
     */
    public record Shop(boolean enabled, List<Long> stashPages, long flask, long fragment, int fragmentWeekly, Map<String, Title> titles,
                       List<Long> characterSlots) {
        public Shop {
            stashPages = List.copyOf(stashPages);
            titles = Collections.unmodifiableMap(new LinkedHashMap<>(titles));
            characterSlots = characterSlots == null ? List.of() : List.copyOf(characterSlots);
        }

        /** 沒有角色格的（舊的呼叫法）。 */
        public Shop(final boolean enabled, final List<Long> stashPages, final long flask, final long fragment, final int fragmentWeekly,
                    final Map<String, Title> titles) {
            this(enabled, stashPages, flask, fragment, fragmentWeekly, titles, List.of());
        }
    }

    private int levelGap = 10;
    /** 經驗獎勵：角色超過這個等級時照這一級的升級經驗算（0 = 不限）。 */
    private int experienceLevelCap = DEFAULT_EXPERIENCE_LEVEL_CAP;
    /** 沒有 daily 這一段 = 沒有每日委託。 */
    private int dailyCount;
    private final Map<String, Template> templates = new LinkedHashMap<>();
    private Weekly weekly = defaultWeekly();
    private Shop shop = new Shop(false, List.of(), 0, 0, 0, Map.of());

    private static Weekly defaultWeekly() {
        return new Weekly(false, 0, 0, "回響・", 2.5, 1.25, 1.1, List.of(), 0, 0, 5, 3, 60, Map.of(), "", null);
    }

    /** 讀 data/bounties.yml（不存在 = 沒有委託）。 */
    private final Map<String, BossTitle> bossTitles = new LinkedHashMap<>();

    /** 功勳兌換的鑰匙類通貨（2026-10，shop.keys）：currency 通貨代號、price 一個多少功勳、weekly 每週最多換幾個。 */
    public record ShopKey(String currency, long price, int weekly, String note) {
    }

    private final List<ShopKey> shopKeys = new ArrayList<>();

    /** 功勳兌換的鑰匙類通貨（照設定檔的順序）。 */
    public List<ShopKey> shopKeys() {
        return shopKeys;
    }

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        final BountyConfig fresh = new BountyConfig();
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        copyFrom(fresh);
    }

    /** 從已經讀好的節點載入（單元測試用）。 */
    public void load(final YamlNode root) {
        final BountyConfig fresh = new BountyConfig();
        fresh.parse(root);
        copyFrom(fresh);
    }

    public void copyFrom(final BountyConfig other) {
        levelGap = other.levelGap;
        experienceLevelCap = other.experienceLevelCap;
        dailyCount = other.dailyCount;
        templates.clear();
        templates.putAll(other.templates);
        weekly = other.weekly;
        shop = other.shop;
        bossTitles.clear();
        bossTitles.putAll(other.bossTitles);
        shopKeys.clear();
        shopKeys.addAll(other.shopKeys);
    }

    private void parse(final YamlNode root) {
        levelGap = Math.max(0, root.node("settings").integer("level_gap", 10));
        experienceLevelCap = Math.max(0, root.node("settings").integer("experience_level_cap", DEFAULT_EXPERIENCE_LEVEL_CAP));
        final YamlNode daily = root.node("daily");
        dailyCount = root.has("daily") ? daily.integer("count", 3) : 0;
        final YamlNode templateNode = daily.node("templates");
        for (final String id : templateNode.keys()) {
            final YamlNode node = templateNode.node(id);
            checkId(id, node.path());
            templates.put(id, template(id, node));
        }
        if (root.has("weekly")) {
            weekly = weekly(root.node("weekly"));
        }
        if (root.has("shop")) {
            shop = shop(root.node("shop"));
            for (final YamlNode key : root.node("shop").list("keys")) {
                final String currency = key.string("currency", "").trim();
                final long price = key.integer("price", 0);
                final int weekly = key.integer("weekly", 0);
                if (!currency.isEmpty() && price > 0 && weekly > 0) {
                    shopKeys.add(new ShopKey(currency, price, weekly, key.string("note", "").trim()));
                }
            }
        }
        final YamlNode bossNode = root.node("boss_titles");
        for (final String boss : bossNode.keys()) {
            final YamlNode node = bossNode.node(boss);
            final String id = node.string("id", "kill_" + boss).trim();
            checkId(id, node.path());
            final String name = node.requireString("name").trim();
            final int kills = Math.max(1, node.integer("kills", 100));
            bossTitles.put(boss, new BossTitle(boss, kills, node.string("boss_name", boss).trim(),
                    new Title(id, name, node.string("color", "#ffffff").trim(), 0, StatLines.parse(node.list("stats"), "稱號「" + name + "」"))));
        }
    }

    /** 擊殺稱號（Boss 代號 → 稱號）。 */
    public Map<String, BossTitle> bossTitles() {
        return bossTitles;
    }

    private static Template template(final String id, final YamlNode node) {
        final String where = node.path();
        final List<Template> found = new ArrayList<>();
        final String text = node.requireString("text").trim();
        final long merits = Math.max(0, node.integer("merits", 0));
        final double experience = node.decimal("experience", 0) / 100.0;
        final int weight = node.integer("weight", 1);
        if (node.has("kill")) {
            final YamlNode kill = node.node("kill");
            found.add(new Template(id, text, Goal.KILL, kill.integer("count", 1), Filter.parse(kill.string("rarity", "any").trim(), kill.path()),
                    null, 0, merits, experience, weight));
        }
        if (node.has("collect")) {
            final YamlNode collect = node.node("collect");
            found.add(new Template(id, text, Goal.COLLECT, collect.integer("count", 1),
                    Filter.parse(collect.string("rarity", "any").trim(), collect.path()), collect.requireString("item").trim(),
                    collect.decimal("chance", 0.05), merits, experience, weight));
        }
        if (node.has("boss")) {
            found.add(new Template(id, text, Goal.BOSS, node.node("boss").integer("count", 1), Filter.ANY, null, 0, merits, experience, weight));
        }
        if (node.has("maps")) {
            found.add(new Template(id, text, Goal.MAPS, node.node("maps").integer("count", 1), Filter.ANY, null, 0, merits, experience, weight));
        }
        if (found.size() != 1) {
            throw new IllegalArgumentException(where + "：每個委託要剛好一個目標（kill、collect、boss、maps），"
                    + (found.isEmpty() ? "卻沒有" : "卻有 " + found.size() + " 個"));
        }
        return found.getFirst();
    }

    private static Weekly weekly(final YamlNode node) {
        final Map<String, String> themes = new LinkedHashMap<>();
        final YamlNode themeNode = node.node("themes");
        for (final String zone : themeNode.keys()) {
            themes.put(zone, themeNode.requireString(zone).trim().toLowerCase(Locale.ROOT));
        }
        PhaseDef phase = null;
        if (node.has("phase")) {
            final YamlNode p = node.node("phase");
            final List<AffixMod> stats = new ArrayList<>();
            for (final YamlNode stat : p.list("stats")) {
                stats.add(AffixMod.parse(stat));
            }
            phase = new PhaseDef(p.decimal("at", 0.5), p.string("name", "共鳴").trim(), blankToNull(p.string("message", null)),
                    Math.max(0, p.integer("summon", 0)), p.decimal("speed", 0), stats);
        }
        final List<String> mods = new ArrayList<>();
        for (final String mod : node.strings("mods")) {
            mods.add(mod.trim());
        }
        return new Weekly(node.bool("enabled", true), Math.max(0, node.integer("merits", 0)), node.decimal("experience", 0) / 100.0,
                node.string("prefix", "回響・"), node.decimal("life", 2.5), node.decimal("damage", 1.25), node.decimal("scale", 1.1), mods,
                node.decimal("quantity", 0), node.decimal("rarity", 0), node.integer("rooms", 5), node.integer("attempts", 3),
                Math.max(0, node.integer("cooldown_seconds", 60)), themes, node.string("lore", "").trim(), phase);
    }

    private static Shop shop(final YamlNode node) {
        final List<Long> pages = prices(node, "stash_pages");
        final List<Long> slots = prices(node, "character_slots");
        final YamlNode fragment = node.node("fragment");
        final Map<String, Title> titles = new LinkedHashMap<>();
        final YamlNode titleNode = node.node("titles");
        for (final String id : titleNode.keys()) {
            final YamlNode title = titleNode.node(id);
            checkId(id, title.path());
            final String name = title.requireString("name").trim();
            titles.put(id, new Title(id, name, title.string("color", "#ffffff").trim(), title.integer("price", 0),
                    StatLines.parse(title.list("stats"), "稱號「" + name + "」")));
        }
        return new Shop(node.bool("enabled", true), pages, node.integer("flask", 0), fragment.integer("price", 0),
                fragment.integer("weekly", 0), titles, slots);
    }

    /** 一串價格（例如 stash_pages: [200, 300]）；沒寫是空的。 */
    private static List<Long> prices(final YamlNode node, final String key) {
        final List<Long> prices = new ArrayList<>();
        final Object raw = node.raw(key);
        if (raw instanceof List<?> list) {
            for (final Object value : list) {
                if (!(value instanceof Number number)) {
                    throw new IllegalArgumentException(node.path() + "." + key + "：要是價格的清單，例如 [200, 300]");
                }
                prices.add(number.longValue());
            }
        } else if (raw != null) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要是價格的清單，例如 [200, 300]");
        }
        return prices;
    }

    private static void checkId(final String id, final String where) {
        if (!ID.matcher(id).matches() || id.length() > 24) {
            throw new IllegalArgumentException(where + "：代號只能用小寫英文、數字、底線，最多 24 個字");
        }
    }

    private static String blankToNull(final String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    // ---- 檢查 ----

    /**
     * 檢查數值與代號。
     *
     * @param bossZones 有區域 Boss 的區域（回響的主題）
     * @param mods      怪物詞綴（monsters.yml 的 mods）
     * @param themes    地圖的版面主題
     * @return 問題（空的 = 沒問題）
     */
    public List<String> validate(final Collection<String> bossZones, final Collection<String> mods, final Set<String> themes) {
        final List<String> problems = new ArrayList<>();
        if (dailyCount < 0 || dailyCount > MAX_DAILY) {
            problems.add("daily.count：要在 0 到 " + MAX_DAILY + " 之間");
        }
        int withoutMaps = 0;
        for (final Template template : templates.values()) {
            final String where = "daily.templates." + template.id();
            if (!template.needsMaps()) {
                withoutMaps++;
            }
            if (template.count() < 1) {
                problems.add(where + "：count 至少 1");
            }
            if (template.weight() < 1) {
                problems.add(where + ".weight：至少 1");
            }
            if (template.experience() < 0 || template.experience() > 2) {
                problems.add(where + ".experience：要在 0 到 200 之間（升級所需經驗的百分比）");
            }
            if (template.goal() == Goal.COLLECT && (template.chance() <= 0 || template.chance() > 1)) {
                problems.add(where + ".collect.chance：要在 0 到 1 之間（不含 0）");
            }
            if (template.goal() == Goal.COLLECT && template.item().isBlank()) {
                problems.add(where + ".collect.item：要寫撿什麼");
            }
            if (template.text().isBlank()) {
                problems.add(where + ".text：沒有說明");
            } else if (template.describe().length() > TEXT_MAX) {
                problems.add(where + ".text：「" + template.describe() + "」太長（任務追蹤一行最多 " + TEXT_MAX + " 個字）");
            }
        }
        if (dailyCount > 0 && withoutMaps < dailyCount) {
            problems.add("daily.templates：不用跑地圖的委託只有 " + withoutMaps + " 種，少於每天 " + dailyCount + " 個（還在升級的角色抽不滿）");
        }
        if (weekly.enabled()) {
            if (weekly.life() <= 0 || weekly.damage() <= 0 || weekly.scale() <= 0) {
                problems.add("weekly：life、damage、scale 要是正的");
            }
            if (weekly.rooms() < 5 || weekly.rooms() > 12) {
                problems.add("weekly.rooms：要在 5 到 12 之間");
            }
            if (weekly.attempts() < 1) {
                problems.add("weekly.attempts：每週至少能開 1 次");
            }
            if (weekly.experience() < 0 || weekly.experience() > 5) {
                problems.add("weekly.experience：要在 0 到 500 之間（升級所需經驗的百分比）");
            }
            for (final Map.Entry<String, String> entry : weekly.themes().entrySet()) {
                if (!bossZones.contains(entry.getKey())) {
                    problems.add("weekly.themes." + entry.getKey() + "：沒有這個有 Boss 的區域");
                }
                if (!themes.contains(entry.getValue())) {
                    problems.add("weekly.themes." + entry.getKey() + "：不認得的主題 " + entry.getValue() + "（可以用 " + themes + "）");
                }
            }
            for (final String mod : weekly.mods()) {
                if (!mods.contains(mod)) {
                    problems.add("weekly.mods：沒有怪物詞綴 " + mod);
                }
            }
            if (weekly.phase() != null && (weekly.phase().at() <= 0 || weekly.phase().at() >= 1)) {
                problems.add("weekly.phase.at：要在 0 到 1 之間（血量比例）");
            }
        }
        if (shop.enabled()) {
            if (shop.stashPages().size() > MAX_STASH_PAGES) {
                problems.add("shop.stash_pages：最多多買 " + MAX_STASH_PAGES + " 頁");
            }
            for (final long price : shop.stashPages()) {
                if (price < 0) {
                    problems.add("shop.stash_pages：價格不能是負的");
                }
            }
            if (shop.characterSlots().size() > MAX_CHARACTER_SLOTS) {
                problems.add("shop.character_slots：最多多買 " + MAX_CHARACTER_SLOTS + " 個");
            }
            for (final long price : shop.characterSlots()) {
                if (price < 0) {
                    problems.add("shop.character_slots：價格不能是負的");
                }
            }
            if (shop.flask() < 0 || shop.fragment() < 0 || shop.fragmentWeekly() < 0) {
                problems.add("shop：flask、fragment 的價格與每週數量不能是負的");
            }
            int idLength = 0;
            for (final Title title : shop.titles().values()) {
                idLength += title.id().length() + 1;
            }
            // 2026-10-26：跟著 MeritShop.VALUE_MAX 加長（這次之前寫死 250）
            if (idLength > MeritShop.VALUE_MAX) {
                problems.add("shop.titles：稱號的代號加起來太長（買過的稱號記在一筆帳號進度裡，最多 " + MeritShop.VALUE_MAX + " 個字）");
            }
            for (final Title title : shop.titles().values()) {
                final String where = "shop.titles." + title.id();
                if (title.name().isBlank() || title.name().codePointCount(0, title.name().length()) > TITLE_MAX) {
                    problems.add(where + ".name：要 1 到 " + TITLE_MAX + " 個字");
                }
                if (!COLOR.matcher(title.color()).matches()) {
                    problems.add(where + ".color：要寫成 \"#rrggbb\"，卻是「" + title.color() + "」");
                }
                if (title.price() < 0) {
                    problems.add(where + ".price：不能是負的");
                }
                for (final Modifier stat : title.stats()) {
                    final String problem = StatLines.problem(stat);
                    if (problem != null) {
                        problems.add(where + ".stats：" + problem);
                    }
                }
            }
        }
        return problems;
    }

    // ---- 查詢 ----

    /** 擊殺、撿東西、打 Boss 只算「怪物等級 ≥ 角色等級 − 這個值」的怪。 */
    public int levelGap() {
        return levelGap;
    }

    /** 經驗獎勵照幾級的升級經驗算為止（0 = 不限）。 */
    public int experienceLevelCap() {
        return experienceLevelCap;
    }

    /**
     * 委託、回響給的經驗：升到下一級所需經驗 × fraction；角色超過 experience_level_cap 級時照那一級的升級經驗算
     * （高等級的經驗主要靠打怪，跟升級經驗表一起越後面越難）。滿級 0。
     *
     * @param experienceToNext 等級 → 升到下一級需要的經驗（character.yml）
     */
    public long rewardExperience(final double fraction, final int level, final java.util.function.IntToLongFunction experienceToNext) {
        if (fraction <= 0 || experienceToNext.applyAsLong(level) <= 0) {
            return 0;
        }
        final int basis = experienceLevelCap > 0 ? Math.min(level, experienceLevelCap) : level;
        return Math.max(0, Math.round(fraction * experienceToNext.applyAsLong(basis)));
    }

    /** 每天幾個每日委託。 */
    public int dailyCount() {
        return Math.max(0, Math.min(MAX_DAILY, dailyCount));
    }

    public Map<String, Template> templates() {
        return Collections.unmodifiableMap(templates);
    }

    public Template template(final String id) {
        return id == null ? null : templates.get(id);
    }

    public Weekly weekly() {
        return weekly;
    }

    public Shop shop() {
        return shop;
    }
}
