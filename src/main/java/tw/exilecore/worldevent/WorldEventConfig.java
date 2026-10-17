package tw.exilecore.worldevent;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.skill.BossSkillUse;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** data/world_events.yml：不定時活動（開服活動）的設定。純資料，不依賴 Bukkit。 */
public final class WorldEventConfig {

    public static final String FILE = "world_events.yml";

    /** 寫好的地點。 */
    public record Spot(String world, double x, double y, double z) {
    }

    public record Extra(String currency, int amount, double chance) {
    }

    /**
     * @param lifePerPlayer 在場每多一個高等級玩家（等級 ≥ countLevel），生命多幾倍的基礎（0.5 = 每人 +50%）；最多算 maxCounted 人（0 = 沒有上限）
     */
    public record Boss(String name, String type, int level, double life, double lifePerPlayer, int countLevel, int maxCounted,
                       double damage, double scale,
                       List<String> mods, String intro, String defeat, List<BossSkillUse> skills,
                       String currency, int[] amount, double uniqueChance, List<String> uniques, List<Extra> extra) {
    }

    /** 2026-10：統帥的生命也照在場的高等級玩家人數（跟世界 Boss 一樣：lifePerPlayer、countLevel、maxCounted）。 */
    public record Commander(String name, String type, double life, double damage, List<BossSkillUse> skills,
                            double lifePerPlayer, int countLevel, int maxCounted) {
    }

    public record Invasion(String name, int waves, int waveSeconds, int baseCount, int perPlayer, int maxCount, int rarePerWave,
                           int magicPerWave, int levelMin, int levelMax, List<String> types, Commander commander,
                           String currency, double killChance, int rareAmount, int commanderAmount, int completeAmount) {
    }

    public enum Kind { TITLE, CURRENCY, UNIQUE }

    /** 兌換的一樣東西。 */
    public record ShopItem(String id, Kind kind, String titleId, String titleName, String titleColor, List<Modifier> titleStats,
                           String currency, int amount, String unique, int itemLevel, Map<String, Integer> cost) {
    }

    private boolean enabled = true;
    private String name = "開服活動";
    private int[] interval = {120, 240};
    private int firstDelay = 30;
    /** 整點開始（2026-10）：照 timezone 的時間，hours 列的那幾點整點開始；false 照 interval_minutes 隨機。 */
    private boolean onTheHour = true;
    private java.time.ZoneId timezone = java.time.ZoneId.of("Asia/Taipei");
    /** 整點輪流（2026-10）：雙數點世界 Boss、單數點入侵怪潮，2 小時一整輪；false 照 weights 隨機。 */
    private boolean alternate = true;
    private java.util.SortedSet<Integer> hours = new java.util.TreeSet<>(java.util.stream.IntStream.range(0, 24).boxed().toList()); // 每小時一次
    private int minPlayers = 1;
    private int warnMinutes = 2;
    private int durationMinutes = 15;
    private int emptyCloseSeconds = 90;
    private double radius = 40;
    private double campDistance = 24;
    private int bossWeight = 40;
    private int invasionWeight = 60;
    private final List<Spot> spots = new ArrayList<>();
    private Boss boss;
    private Invasion invasion;
    private final List<ShopItem> shop = new ArrayList<>();

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        final WorldEventConfig fresh = new WorldEventConfig();
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        } else {
            // 2026-10-18：檔案不存在也照空設定解析一次，boss／invasion 才有預設值（原本是 null，開選單 NPE）
            fresh.parse(YamlNode.of(FILE, java.util.Map.of()));
            fresh.enabled = false;
        }
        enabled = fresh.enabled;
        name = fresh.name;
        interval = fresh.interval;
        firstDelay = fresh.firstDelay;
        onTheHour = fresh.onTheHour;
        timezone = fresh.timezone;
        alternate = fresh.alternate;
        hours = fresh.hours;
        minPlayers = fresh.minPlayers;
        warnMinutes = fresh.warnMinutes;
        durationMinutes = fresh.durationMinutes;
        emptyCloseSeconds = fresh.emptyCloseSeconds;
        radius = fresh.radius;
        campDistance = fresh.campDistance;
        bossWeight = fresh.bossWeight;
        invasionWeight = fresh.invasionWeight;
        spots.clear();
        spots.addAll(fresh.spots);
        boss = fresh.boss;
        invasion = fresh.invasion;
        shop.clear();
        shop.addAll(fresh.shop);
    }

    void parse(final YamlNode root) {
        final YamlNode settings = root.node("settings");
        enabled = settings.bool("enabled", true);
        name = settings.string("name", name).trim();
        if (settings.has("interval_minutes")) {
            final int[] range = settings.range("interval_minutes");
            interval = new int[] {Math.max(10, range[0]), Math.max(Math.max(10, range[0]), range[1])};
        }
        firstDelay = Math.max(1, settings.integer("first_delay_minutes", firstDelay));
        final YamlNode schedule = settings.node("schedule");
        onTheHour = schedule.bool("on_the_hour", onTheHour);
        alternate = schedule.bool("alternate", alternate);
        final String zone = schedule.string("timezone", timezone.getId()).trim();
        try {
            timezone = java.time.ZoneId.of(zone);
        } catch (final java.time.DateTimeException e) {
            throw new IllegalArgumentException(schedule.path() + ".timezone：不認得的時區「" + zone + "」（例如 Asia/Taipei）");
        }
        if (schedule.has("hours")) {
            final java.util.SortedSet<Integer> list = new java.util.TreeSet<>();
            for (final String text : schedule.strings("hours")) {
                final int hour;
                try {
                    hour = Integer.parseInt(text.trim());
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(schedule.path() + ".hours：要寫 0 到 23 的數字，卻是「" + text + "」");
                }
                if (hour < 0 || hour > 23) {
                    throw new IllegalArgumentException(schedule.path() + ".hours：要寫 0 到 23 的數字，卻是 " + hour);
                }
                list.add(hour);
            }
            if (list.isEmpty()) {
                throw new IllegalArgumentException(schedule.path() + ".hours：至少要有一個整點");
            }
            hours = list;
        }
        minPlayers = Math.max(1, settings.integer("min_players", minPlayers));
        warnMinutes = Math.max(0, settings.integer("warn_minutes", warnMinutes));
        durationMinutes = Math.max(1, settings.integer("duration_minutes", durationMinutes));
        emptyCloseSeconds = Math.max(10, settings.integer("empty_close_seconds", emptyCloseSeconds));
        radius = Math.max(10, settings.decimal("radius", radius));
        campDistance = Math.max(5, settings.decimal("camp_distance", campDistance));
        final YamlNode weights = settings.node("weights");
        bossWeight = Math.max(0, weights.integer("boss", bossWeight));
        invasionWeight = Math.max(0, weights.integer("invasion", invasionWeight));
        for (final YamlNode spot : settings.list("locations")) {
            spots.add(new Spot(spot.requireString("world"), spot.decimal("x", 0), spot.decimal("y", 64), spot.decimal("z", 0)));
        }

        final YamlNode b = root.node("boss");
        final YamlNode br = b.node("rewards");
        final List<Extra> extra = new ArrayList<>();
        for (final YamlNode node : br.list("extra")) {
            extra.add(new Extra(node.requireString("currency"), Math.max(1, node.integer("amount", 1)), node.decimal("chance", 1)));
        }
        boss = new Boss(b.string("name", "開天者"), b.string("type", "IRON_GOLEM").toUpperCase(Locale.ROOT), Math.max(1, b.integer("level", 88)),
                Math.max(0.1, b.decimal("life", 90)), Math.max(0, b.decimal("life_per_player", 0.5)), Math.max(1, b.integer("count_level", 80)),
                Math.max(0, b.integer("max_counted_players", 0)), Math.max(0.1, b.decimal("damage", 3)),
                Math.max(0.3, b.decimal("scale", 1.5)), b.strings("mods"), b.string("intro", ""), b.string("defeat", ""),
                BossSkillUse.parseList(b, "skills"), br.string("currency", "event_titan_core"),
                br.has("amount") ? br.range("amount") : new int[] {3, 5}, Math.max(0, br.decimal("unique_chance", 0.02)),
                br.strings("uniques"), extra);

        final YamlNode i = root.node("invasion");
        final YamlNode c = i.node("commander");
        final YamlNode ir = i.node("rewards");
        final List<String> types = new ArrayList<>();
        for (final String type : i.strings("types")) {
            types.add(type.trim().toUpperCase(Locale.ROOT));
        }
        if (types.isEmpty()) {
            types.add("ZOMBIE");
        }
        invasion = new Invasion(i.string("name", "星潮入侵"), Math.max(1, i.integer("waves", 5)), Math.max(5, i.integer("wave_seconds", 25)),
                Math.max(1, i.integer("base_count", 10)), Math.max(0, i.integer("per_player", 4)), Math.max(1, i.integer("max_count", 40)),
                Math.max(0, i.integer("rare_per_wave", 2)), Math.max(0, i.integer("magic_per_wave", 5)),
                Math.max(1, i.integer("level_min", 10)), Math.max(1, i.integer("level_max", 84)), types,
                new Commander(c.string("name", "潮汐統帥"), c.string("type", "RAVAGER").toUpperCase(Locale.ROOT),
                        Math.max(0.1, c.decimal("life", 8)), Math.max(0.1, c.decimal("damage", 1.5)), BossSkillUse.parseList(c, "skills"),
                        Math.max(0, c.decimal("life_per_player", 0.5)), Math.max(1, c.integer("count_level", 80)),
                        Math.max(0, c.integer("max_counted_players", 0))),
                ir.string("currency", "event_swarm_shard"), Math.max(0, Math.min(1, ir.decimal("kill_chance", 0.35))),
                Math.max(0, ir.integer("rare_amount", 3)), Math.max(0, ir.integer("commander_amount", 10)),
                Math.max(0, ir.integer("complete_amount", 15)));

        for (final YamlNode node : root.list("shop")) {
            final String id = node.requireString("id").trim();
            final Kind kind;
            try {
                kind = Kind.valueOf(node.requireString("kind").trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(node.path() + ".kind：要寫 title、currency 或 unique");
            }
            final Map<String, Integer> cost = new LinkedHashMap<>();
            final YamlNode costNode = node.node("cost");
            for (final String currency : costNode.keys()) {
                cost.put(currency, Math.max(1, costNode.integer(currency, 1)));
            }
            if (cost.isEmpty()) {
                throw new IllegalArgumentException(node.path() + ".cost：要寫素材與數量");
            }
            String titleId = null;
            String titleName = null;
            String titleColor = null;
            List<Modifier> stats = List.of();
            if (kind == Kind.TITLE) {
                final YamlNode title = node.node("title");
                titleId = title.requireString("id").trim();
                titleName = title.requireString("name").trim();
                titleColor = title.string("color", "#ffffff").trim();
                stats = tw.exilecore.ascendancy.StatLines.parse(title.list("stats"), "稱號「" + titleName + "」");
            }
            shop.add(new ShopItem(id, kind, titleId, titleName, titleColor, stats, node.string("currency", null),
                    Math.max(1, node.integer("amount", 1)), node.string("unique", null), Math.max(1, node.integer("item_level", 84)),
                    Map.copyOf(cost)));
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String name() {
        return name;
    }

    public int[] interval() {
        return interval.clone();
    }

    public int firstDelay() {
        return firstDelay;
    }

    public boolean onTheHour() {
        return onTheHour;
    }

    public boolean alternate() {
        return alternate;
    }

    public java.time.ZoneId timezone() {
        return timezone;
    }

    public java.util.SortedSet<Integer> hours() {
        return java.util.Collections.unmodifiableSortedSet(hours);
    }

    public int minPlayers() {
        return minPlayers;
    }

    public int warnMinutes() {
        return warnMinutes;
    }

    public int durationMinutes() {
        return durationMinutes;
    }

    public int emptyCloseSeconds() {
        return emptyCloseSeconds;
    }

    public double radius() {
        return radius;
    }

    public double campDistance() {
        return campDistance;
    }

    public int bossWeight() {
        return bossWeight;
    }

    public int invasionWeight() {
        return invasionWeight;
    }

    public List<Spot> spots() {
        return List.copyOf(spots);
    }

    public Boss boss() {
        return boss;
    }

    public Invasion invasion() {
        return invasion;
    }

    public List<ShopItem> shop() {
        return List.copyOf(shop);
    }

    /** 素材、兌換的東西、招式代號有沒有寫錯（啟動、重讀時檢查）。 */
    public List<String> validate(final java.util.function.Predicate<String> currencyExists, final java.util.function.Predicate<String> uniqueExists,
                                 final java.util.function.Predicate<String> skillExists) {
        final List<String> problems = new ArrayList<>();
        if (boss != null) {
            check(problems, currencyExists, boss.currency(), "boss.rewards.currency");
            for (final String unique : boss.uniques()) {
                if (!uniqueExists.test(unique)) {
                    problems.add("boss.rewards.uniques：沒有傳奇 " + unique);
                }
            }
            for (final Extra extra : boss.extra()) {
                check(problems, currencyExists, extra.currency(), "boss.rewards.extra");
            }
            for (final BossSkillUse use : boss.skills()) {
                if (!skillExists.test(use.id())) {
                    problems.add("boss.skills：boss_skills.yml 沒有 " + use.id());
                }
            }
        }
        if (invasion != null) {
            check(problems, currencyExists, invasion.currency(), "invasion.rewards.currency");
            for (final BossSkillUse use : invasion.commander().skills()) {
                if (!skillExists.test(use.id())) {
                    problems.add("invasion.commander.skills：boss_skills.yml 沒有 " + use.id());
                }
            }
        }
        for (final ShopItem item : shop) {
            for (final String currency : item.cost().keySet()) {
                check(problems, currencyExists, currency, "shop." + item.id() + ".cost");
            }
            if (item.kind() == Kind.CURRENCY) {
                check(problems, currencyExists, item.currency(), "shop." + item.id() + ".currency");
            }
            if (item.kind() == Kind.UNIQUE && (item.unique() == null || !uniqueExists.test(item.unique()))) {
                problems.add("shop." + item.id() + ".unique：沒有傳奇 " + item.unique());
            }
        }
        return problems;
    }

    private static void check(final List<String> problems, final java.util.function.Predicate<String> exists, final String id, final String where) {
        if (id == null || !exists.test(id)) {
            problems.add(where + "：currencies.yml 沒有 " + id);
        }
    }
}
