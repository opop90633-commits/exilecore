package tw.exilecore.festival;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.skill.BossSkillUse;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;

/** data/festival.yml：節慶活動（2026-10 萬聖節「萬燈夜」）的設定。純資料，不依賴 Bukkit。 */
public final class FestivalConfig {

    public static final String FILE = "festival.yml";
    public static final String CANDY = "event_candy";
    public static final String WICK = "event_lamp_wick";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    public record Arena(int x, int z, int radius) {
    }

    public record Lanterns(int count, int ring, int guardSeconds, int[] ghosts, String ghostType, int ghostSeconds, int dailyWick,
                           int globalTarget, int globalQuantity, int globalMinutes) {
    }

    public record Boss(String name, String type, SortedSet<Integer> hours, int warnMinutes, int durationMinutes, int emptyCloseSeconds,
                       int level, double life, double lifePerPlayer, int countLevel, int maxCounted, double damage, double scale,
                       List<String> mods, String intro, String defeat, List<BossSkillUse> skills,
                       int[] wick, int candy, double cosmeticChance) {
    }

    public record Candy(double normal, double magic, int rare, int boss, double pumpkinRare, double pumpkinBoss, int pumpkinSeconds,
                        int[] treat, double trickChance, int[] trickGhosts, int trickSeconds, int[] trickReward) {
    }

    public record Challenges(int kingCount, int kingWick, int pumpkinCount, int pumpkinCandy, int lanternDays, int lanternWick, String title) {
    }

    public record Title(String id, String name, String color, List<Modifier> stats) {
    }

    public enum Kind { COSMETIC, TITLE, CURRENCY }

    public record ShopItem(String id, Kind kind, String cosmetic, String title, String currency, int amount, Map<String, Integer> cost) {
    }

    private boolean enabled;
    private String name = "萬燈夜";
    private String color = "#ff8a1e";
    private ZoneId timezone = ZoneId.of("Asia/Taipei");
    private LocalDateTime start = LocalDateTime.of(2026, 10, 16, 0, 0);
    private LocalDateTime end = LocalDateTime.of(2026, 10, 31, 23, 59);
    private LocalDateTime shopUntil = LocalDateTime.of(2026, 11, 7, 23, 59);
    private Arena arena = new Arena(0, 400, 40);
    private Lanterns lanterns;
    private Boss boss;
    private Candy candy;
    private Challenges challenges;
    private Map<String, Title> titles = Map.of();
    private List<ShopItem> shop = List.of();

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        final FestivalConfig fresh = new FestivalConfig();
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        } else {
            fresh.parse(YamlNode.of(FILE, Map.of()));
            fresh.enabled = false;
        }
        copy(fresh);
    }

    private void copy(final FestivalConfig fresh) {
        enabled = fresh.enabled;
        name = fresh.name;
        color = fresh.color;
        timezone = fresh.timezone;
        start = fresh.start;
        end = fresh.end;
        shopUntil = fresh.shopUntil;
        arena = fresh.arena;
        lanterns = fresh.lanterns;
        boss = fresh.boss;
        candy = fresh.candy;
        challenges = fresh.challenges;
        titles = fresh.titles;
        shop = fresh.shop;
    }

    void parse(final YamlNode root) {
        final YamlNode settings = root.node("settings");
        enabled = settings.bool("enabled", true);
        name = settings.string("name", name).trim();
        color = settings.string("color", color).trim();
        final String zone = settings.string("timezone", timezone.getId()).trim();
        try {
            timezone = ZoneId.of(zone);
        } catch (final java.time.DateTimeException e) {
            throw new IllegalArgumentException(settings.path() + ".timezone：不認得的時區「" + zone + "」");
        }
        start = date(settings, "start", start);
        end = date(settings, "end", end);
        shopUntil = date(settings, "shop_until", end);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(settings.path() + "：end 要在 start 之後");
        }
        if (shopUntil.isBefore(end)) {
            shopUntil = end;
        }

        final YamlNode a = root.node("arena");
        arena = new Arena(a.integer("x", 0), a.integer("z", 400), Math.max(20, Math.min(80, a.integer("radius", 40))));

        final YamlNode l = root.node("lanterns");
        lanterns = new Lanterns(Math.max(1, Math.min(32, l.integer("count", 13))),
                Math.max(6, Math.min(arena.radius() - 3, l.integer("ring", arena.radius() - 8))),
                Math.max(1, l.integer("guard_seconds", 10)), range(l, "ghosts", 3, 5),
                l.string("ghost_type", "VEX").trim().toUpperCase(Locale.ROOT), Math.max(10, l.integer("ghost_seconds", 60)),
                Math.max(0, l.integer("daily_wick", 5)), Math.max(1, l.integer("global_target", 1300)),
                Math.max(0, l.integer("global_quantity", 10)), Math.max(1, l.integer("global_minutes", 60)));

        final YamlNode b = root.node("boss");
        final SortedSet<Integer> hours = new TreeSet<>();
        for (final String text : b.strings("hours")) {
            try {
                final int hour = Integer.parseInt(text.trim());
                if (hour < 0 || hour > 23) {
                    throw new NumberFormatException();
                }
                hours.add(hour);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(b.path() + ".hours：要寫 0 到 23 的數字，卻是「" + text + "」");
            }
        }
        if (hours.isEmpty()) {
            hours.addAll(List.of(13, 20, 23));
        }
        final YamlNode br = b.node("rewards");
        boss = new Boss(b.string("name", "燈魂領主・瓜首").trim(), b.string("type", "WITHER_SKELETON").trim().toUpperCase(Locale.ROOT),
                java.util.Collections.unmodifiableSortedSet(hours), Math.max(0, b.integer("warn_minutes", 2)),
                Math.max(1, b.integer("duration_minutes", 15)), Math.max(30, b.integer("empty_close_seconds", 180)),
                Math.max(1, b.integer("level", 88)), Math.max(0.1, b.decimal("life", 300)), Math.max(0, b.decimal("life_per_player", 1.0)),
                Math.max(1, b.integer("count_level", 80)), Math.max(0, b.integer("max_counted_players", 0)),
                Math.max(0.1, b.decimal("damage", 2.6)), Math.max(0.3, b.decimal("scale", 1.8)), b.strings("mods"),
                b.string("intro", ""), b.string("defeat", ""), BossSkillUse.parseList(b, "skills"),
                range(br, "wick", 3, 5), Math.max(0, br.integer("candy", 50)), clamp01(br.decimal("cosmetic_chance", 0.02)));

        final YamlNode c = root.node("candy");
        candy = new Candy(clamp01(c.decimal("normal", 0.05)), clamp01(c.decimal("magic", 0.15)), Math.max(0, c.integer("rare", 3)),
                Math.max(0, c.integer("boss", 10)), clamp01(c.decimal("pumpkin_rare", 0.04)), clamp01(c.decimal("pumpkin_boss", 0.3)),
                Math.max(10, c.integer("pumpkin_seconds", 90)), range(c, "treat", 15, 30), clamp01(c.decimal("trick_chance", 0.3)),
                range(c, "trick_ghosts", 4, 6), Math.max(10, c.integer("trick_seconds", 60)), range(c, "trick_reward", 30, 60));

        final YamlNode ch = root.node("challenges");
        challenges = new Challenges(Math.max(1, ch.node("king").integer("count", 5)), Math.max(0, ch.node("king").integer("wick", 10)),
                Math.max(1, ch.node("pumpkin").integer("count", 30)), Math.max(0, ch.node("pumpkin").integer("candy", 300)),
                Math.max(1, ch.node("lantern").integer("days", 7)), Math.max(0, ch.node("lantern").integer("wick", 15)),
                ch.string("title", "").trim());

        final Map<String, Title> titleMap = new LinkedHashMap<>();
        final YamlNode t = root.node("titles");
        for (final String id : t.keys()) {
            final YamlNode node = t.node(id);
            final String titleName = node.requireString("name").trim();
            titleMap.put(id, new Title(id, titleName, node.string("color", "#ffffff").trim(),
                    tw.exilecore.ascendancy.StatLines.parse(node.list("stats"), "稱號「" + titleName + "」")));
        }
        titles = java.util.Collections.unmodifiableMap(titleMap);
        if (!challenges.title().isEmpty() && !titles.containsKey(challenges.title())) {
            throw new IllegalArgumentException(ch.path() + ".title：titles 裡沒有 " + challenges.title());
        }

        final List<ShopItem> items = new ArrayList<>();
        final java.util.Set<String> ids = new java.util.HashSet<>();
        for (final YamlNode node : root.list("shop")) {
            final String id = node.requireString("id").trim();
            if (!ids.add(id)) {
                throw new IllegalArgumentException(node.path() + ".id：重複的 " + id);
            }
            final Kind kind;
            try {
                kind = Kind.valueOf(node.requireString("kind").trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(node.path() + ".kind：要寫 cosmetic、title 或 currency");
            }
            final Map<String, Integer> cost = new LinkedHashMap<>();
            final YamlNode costNode = node.node("cost");
            for (final String currency : costNode.keys()) {
                cost.put(currency, Math.max(1, costNode.integer(currency, 1)));
            }
            if (cost.isEmpty()) {
                throw new IllegalArgumentException(node.path() + ".cost：要寫素材與數量");
            }
            final String title = node.string("title", null);
            if (kind == Kind.TITLE && (title == null || !titles.containsKey(title.trim()))) {
                throw new IllegalArgumentException(node.path() + ".title：titles 裡沒有 " + title);
            }
            if (kind == Kind.COSMETIC && node.string("cosmetic", null) == null) {
                throw new IllegalArgumentException(node.path() + ".cosmetic：要寫時裝代號");
            }
            items.add(new ShopItem(id, kind, trim(node.string("cosmetic", null)), trim(title), trim(node.string("currency", null)),
                    Math.max(1, node.integer("amount", 1)), java.util.Collections.unmodifiableMap(cost)));
        }
        shop = List.copyOf(items);
    }

    private static String trim(final String text) {
        return text == null ? null : text.trim();
    }

    private static double clamp01(final double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static int[] range(final YamlNode node, final String key, final int min, final int max) {
        if (!node.has(key)) {
            return new int[] {min, max};
        }
        final int[] range = node.range(key);
        final int low = Math.max(0, range[0]);
        return new int[] {low, Math.max(low, range[1])};
    }

    private static LocalDateTime date(final YamlNode node, final String key, final LocalDateTime fallback) {
        if (!node.has(key)) {
            return fallback;
        }
        final String text = node.string(key, "").trim();
        try {
            return LocalDateTime.parse(text, DATE);
        } catch (final DateTimeParseException e) {
            throw new IllegalArgumentException(node.path() + "." + key + "：時間要寫成「2026-10-31 23:59」，卻是「" + text + "」");
        }
    }

    /** 素材、時裝、招式代號有沒有寫錯（啟動、重讀時檢查）。 */
    public List<String> validate(final Predicate<String> currencyExists, final Predicate<String> cosmeticExists,
                                 final Predicate<String> skillExists) {
        final List<String> problems = new ArrayList<>();
        for (final String id : List.of(CANDY, WICK)) {
            if (!currencyExists.test(id)) {
                problems.add("currencies.yml 沒有 " + id);
            }
        }
        if (boss != null) {
            for (final BossSkillUse use : boss.skills()) {
                if (!skillExists.test(use.id())) {
                    problems.add("boss.skills：boss_skills.yml 沒有 " + use.id());
                }
            }
        }
        for (final ShopItem item : shop) {
            for (final String currency : item.cost().keySet()) {
                if (!currencyExists.test(currency)) {
                    problems.add("shop." + item.id() + ".cost：currencies.yml 沒有 " + currency);
                }
            }
            if (item.kind() == Kind.CURRENCY && (item.currency() == null || !currencyExists.test(item.currency()))) {
                problems.add("shop." + item.id() + ".currency：currencies.yml 沒有 " + item.currency());
            }
            if (item.kind() == Kind.COSMETIC && !cosmeticExists.test(item.cosmetic())) {
                problems.add("shop." + item.id() + ".cosmetic：sponsor.yml 沒有時裝 " + item.cosmetic());
            }
        }
        return problems;
    }

    // ---- 時間 ----

    private LocalDateTime local(final long millis) {
        return java.time.Instant.ofEpochMilli(millis).atZone(timezone).toLocalDateTime();
    }

    /** 活動進行中（點燈、南瓜王、糖果都在這段時間）。 */
    public boolean running(final long millis) {
        if (!enabled) {
            return false;
        }
        final LocalDateTime now = local(millis);
        return !now.isBefore(start) && !now.isAfter(end);
    }

    /** 兌換商店開著（活動期間＋結束後到 shop_until）。 */
    public boolean shopOpen(final long millis) {
        if (!enabled) {
            return false;
        }
        final LocalDateTime now = local(millis);
        return !now.isBefore(start) && !now.isAfter(shopUntil);
    }

    /** 今天（活動時區）的代號，例如 20261016。 */
    public String day(final long millis) {
        return local(millis).toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public String format(final LocalDateTime time) {
        return time.format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.ROOT));
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

    public ZoneId timezone() {
        return timezone;
    }

    public LocalDateTime start() {
        return start;
    }

    public LocalDateTime end() {
        return end;
    }

    public LocalDateTime shopUntil() {
        return shopUntil;
    }

    public Arena arena() {
        return arena;
    }

    public Lanterns lanterns() {
        return lanterns;
    }

    public Boss boss() {
        return boss;
    }

    public Candy candy() {
        return candy;
    }

    public Challenges challenges() {
        return challenges;
    }

    public Map<String, Title> titles() {
        return titles;
    }

    public List<ShopItem> shop() {
        return shop;
    }
}
