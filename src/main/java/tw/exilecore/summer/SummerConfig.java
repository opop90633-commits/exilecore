package tw.exilecore.summer;

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

/**
 * data/summer_festival.yml：夏日活動「星潮祭」（2026-11-03 照站長）的設定。純資料，不依賴 Bukkit。
 * 骨架照萬燈夜的 FestivalConfig，但是獨立的檔、獨立的素材（貝殼、珍珠），兩個活動可以同時存在。
 */
public final class SummerConfig {

    public static final String FILE = "summer_festival.yml";
    public static final String SHELL = "event_shell";
    public static final String PEARL = "event_pearl";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    public record Arena(int x, int z, int radius) {
    }

    /** 沙灘挖寶。 */
    public record Dig(int spotsPerDay, int shells, double chestChance, int[] guards, String guardType, int guardSeconds, double pearlChance,
                      int dailyPearl) {
    }

    /** 西瓜大亂鬥。 */
    public record Melee(SortedSet<Integer> hours, int warnMinutes, int seconds, int spawnEvery, int spawnCount, double goldenChance,
                        int[] topPearls, int shellsPerPoint, int globalTarget, int globalQuantity, int globalMinutes) {
    }

    /**
     * 深潮海妖（世界 Boss）。
     * 2026-11-03 照站長「招式要更多」：phases 是換階段的生命比例（遞減；第 i 個以下就是第 i+1 階段），skills 裡寫 phase: n 的那一招
     * 第 n 階段起才用（BossSkillUse.fromPhase）；這次之前 Host.phase() 永遠 0、phase 寫了也沒用。
     */
    public record Boss(String name, String type, SortedSet<Integer> hours, int warnMinutes, int durationMinutes, int emptyCloseSeconds,
                       int level, double life, double lifePerPlayer, int countLevel, int maxCounted, double damage, double scale,
                       List<String> mods, String intro, String defeat, List<BossSkillUse> skills,
                       int[] pearl, int shells, double cosmeticChance, List<Double> phases) {
        public Boss {
            phases = phases == null ? List.of() : List.copyOf(phases);
        }

        /** 生命比例 fraction（0～1）現在是第幾階段（0 = 一開始；純函式，測試用）。 */
        public int phaseAt(final double fraction) {
            int phase = 0;
            for (final double at : phases) {
                if (fraction <= at) {
                    phase++;
                }
            }
            return phase;
        }
    }

    /** 2026-11-03：讀 boss.phases（0～1 的比例，遞減，最多 10 個）；沒寫是空的（沒有階段）。 */
    static List<Double> phases(final YamlNode b) {
        final List<Double> out = new ArrayList<>();
        final Object raw = b.raw("phases");
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(b.path() + ".phases：要寫成清單（[0.66, 0.33]）");
        }
        for (final Object item : list) {
            final double at;
            try {
                at = Double.parseDouble(String.valueOf(item).trim());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(b.path() + ".phases：要寫 0 到 1 之間的比例，卻是「" + item + "」");
            }
            if (at <= 0 || at >= 1) {
                throw new IllegalArgumentException(b.path() + ".phases：要在 0 到 1 之間（不含），卻是 " + at);
            }
            if (!out.isEmpty() && at >= out.getLast()) {
                throw new IllegalArgumentException(b.path() + ".phases：要由大到小（先到的階段寫前面）");
            }
            out.add(at);
        }
        if (out.size() > 10) {
            throw new IllegalArgumentException(b.path() + ".phases：最多 10 個階段");
        }
        return out;
    }

    /** 漂流瓶與地圖裡的貝殼。 */
    public record Bottle(double normal, double magic, int rareShells, int bossShells, double rare, double boss, int seconds,
                         double letterChance, int[] shells, List<String> letters, int mapExtra, int mapPearls) {
    }

    public record Challenges(int krakenCount, int krakenPearl, int digDays, int digPearl, int meleeCount, int meleeShells,
                             int bottleCount, int bottleShells, String title) {
    }

    public record Title(String id, String name, String color, List<Modifier> stats) {
    }

    public enum Kind { COSMETIC, TITLE, CURRENCY }

    public record ShopItem(String id, Kind kind, String cosmetic, String title, String currency, int amount, Map<String, Integer> cost) {
    }

    private boolean enabled;
    private String name = "星潮祭";
    private String color = "#2ec4ff";
    private ZoneId timezone = ZoneId.of("Asia/Taipei");
    private LocalDateTime start = LocalDateTime.of(2026, 11, 4, 0, 0);
    private LocalDateTime end = LocalDateTime.of(2026, 11, 25, 23, 59);   // 2026-11-03 站長：拉長成三週（原本 11-18）
    private LocalDateTime shopUntil = LocalDateTime.of(2026, 12, 2, 23, 59);
    private Arena arena = new Arena(0, -400, 40);
    private Dig dig;
    private Melee melee;
    private Boss boss;
    private Bottle bottle;
    private Challenges challenges;
    private Map<String, Title> titles = Map.of();
    private List<ShopItem> shop = List.of();

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        final SummerConfig fresh = new SummerConfig();
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        } else {
            fresh.parse(YamlNode.of(FILE, Map.of()));
            fresh.enabled = false;
        }
        copy(fresh);
    }

    private void copy(final SummerConfig fresh) {
        enabled = fresh.enabled;
        name = fresh.name;
        color = fresh.color;
        timezone = fresh.timezone;
        start = fresh.start;
        end = fresh.end;
        shopUntil = fresh.shopUntil;
        arena = fresh.arena;
        dig = fresh.dig;
        melee = fresh.melee;
        boss = fresh.boss;
        bottle = fresh.bottle;
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
        arena = new Arena(a.integer("x", 0), a.integer("z", -400), Math.max(24, Math.min(80, a.integer("radius", 40))));

        final YamlNode d = root.node("dig");
        // 藏寶點用位元記（一個 int），最多 30 個
        dig = new Dig(Math.max(1, Math.min(30, d.integer("spots_per_day", 6))), Math.max(0, d.integer("shells", 20)),
                clamp01(d.decimal("chest_chance", 0.25)), range(d, "guards", 3, 5),
                d.string("guard_type", "DROWNED").trim().toUpperCase(Locale.ROOT), Math.max(10, d.integer("guard_seconds", 45)),
                clamp01(d.decimal("pearl_chance", 0.35)), Math.max(0, d.integer("daily_pearl", 5)));

        final YamlNode m = root.node("melee");
        meleeMinute = Math.max(0, Math.min(59, m.integer("minute", 0))); // 2026-11-03 照站長：錯開整點（不定時活動在整點）
        final SortedSet<Integer> meleeHours = hours(m, List.of(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23));
        melee = new Melee(meleeHours, Math.max(0, m.integer("warn_minutes", 2)), Math.max(30, m.integer("seconds", 120)),
                Math.max(1, m.integer("spawn_every", 4)), Math.max(1, Math.min(40, m.integer("spawn_count", 6))),
                clamp01(m.decimal("golden_chance", 0.08)), tops(m), Math.max(0, m.integer("shells_per_point", 3)),
                Math.max(1, m.integer("global_target", 3000)), Math.max(0, m.integer("global_quantity", 30)),
                Math.max(1, m.integer("global_minutes", 60)));

        final YamlNode b = root.node("boss");
        bossMinute = Math.max(0, Math.min(59, b.integer("minute", 0)));
        final SortedSet<Integer> bossHours = hours(b, List.of(12, 19, 22));
        final YamlNode br = b.node("rewards");
        boss = new Boss(b.string("name", "深潮海妖・珊瑚之喉").trim(), b.string("type", "DROWNED").trim().toUpperCase(Locale.ROOT),
                bossHours, Math.max(0, b.integer("warn_minutes", 2)),
                Math.max(1, b.integer("duration_minutes", 15)), Math.max(30, b.integer("empty_close_seconds", 180)),
                Math.max(1, b.integer("level", 88)), Math.max(0.1, b.decimal("life", 44.36)), Math.max(0, b.decimal("life_per_player", 1.0)),
                Math.max(1, b.integer("count_level", 80)), Math.max(0, b.integer("max_counted_players", 0)),
                Math.max(0.1, b.decimal("damage", 4.5)), Math.max(0.3, b.decimal("scale", 1.8)), b.strings("mods"),
                b.string("intro", ""), b.string("defeat", ""), BossSkillUse.parseList(b, "skills"),
                range(br, "pearl", 3, 5), Math.max(0, br.integer("shells", 50)), clamp01(br.decimal("cosmetic_chance", 0.02)), phases(b));

        final YamlNode bo = root.node("bottle");
        final List<String> letters = new ArrayList<>();
        for (final String letter : bo.strings("letters")) {
            if (!letter.isBlank()) {
                letters.add(letter.trim());
            }
        }
        if (letters.isEmpty()) {
            letters.add("潮水會把一切帶回來。");
        }
        bottle = new Bottle(clamp01(bo.decimal("normal", 0.05)), clamp01(bo.decimal("magic", 0.15)), Math.max(0, bo.integer("rare_shells", 3)),
                Math.max(0, bo.integer("boss_shells", 10)), clamp01(bo.decimal("rare", 0.04)), clamp01(bo.decimal("boss", 0.3)),
                Math.max(10, bo.integer("seconds", 90)), clamp01(bo.decimal("letter_chance", 0.6)), range(bo, "shells", 15, 30),
                List.copyOf(letters), Math.max(0, bo.integer("map_extra", 3)), Math.max(0, bo.integer("map_pearls", 2)));

        final YamlNode ch = root.node("challenges");
        challenges = new Challenges(Math.max(1, ch.node("kraken").integer("count", 5)), Math.max(0, ch.node("kraken").integer("pearl", 10)),
                Math.max(1, ch.node("dig").integer("days", 7)), Math.max(0, ch.node("dig").integer("pearl", 15)),
                Math.max(1, ch.node("melee").integer("count", 10)), Math.max(0, ch.node("melee").integer("shells", 300)),
                Math.max(1, ch.node("bottle").integer("count", 30)), Math.max(0, ch.node("bottle").integer("shells", 300)),
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

    /** 整點的列表（0～23），沒寫用預設。 */
    private static SortedSet<Integer> hours(final YamlNode node, final List<Integer> fallback) {
        final SortedSet<Integer> hours = new TreeSet<>();
        for (final String text : node.strings("hours")) {
            try {
                final int hour = Integer.parseInt(text.trim());
                if (hour < 0 || hour > 23) {
                    throw new NumberFormatException();
                }
                hours.add(hour);
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(node.path() + ".hours：要寫 0 到 23 的數字，卻是「" + text + "」");
            }
        }
        if (hours.isEmpty()) {
            hours.addAll(fallback);
        }
        return java.util.Collections.unmodifiableSortedSet(hours);
    }

    /** 大亂鬥前幾名的珍珠（[3, 2, 1]）。 */
    private static int[] tops(final YamlNode node) {
        final List<String> texts = node.strings("top_pearls");
        if (texts.isEmpty()) {
            return new int[] {3, 2, 1};
        }
        final int[] tops = new int[texts.size()];
        for (int i = 0; i < tops.length; i++) {
            try {
                tops[i] = Math.max(0, Integer.parseInt(texts.get(i).trim()));
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(node.path() + ".top_pearls：要寫數字，卻是「" + texts.get(i) + "」");
            }
        }
        return tops;
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
            throw new IllegalArgumentException(node.path() + "." + key + "：時間要寫成「2026-11-18 23:59」，卻是「" + text + "」");
        }
    }

    /** 素材、時裝、招式代號有沒有寫錯（啟動、重讀時檢查）。 */
    public List<String> validate(final Predicate<String> currencyExists, final Predicate<String> cosmeticExists,
                                 final Predicate<String> skillExists) {
        final List<String> problems = new ArrayList<>();
        for (final String id : List.of(SHELL, PEARL)) {
            if (!currencyExists.test(id)) {
                problems.add("currencies.yml 沒有 " + id);
            }
        }
        if (boss != null) {
            for (final BossSkillUse use : boss.skills()) {
                if (!skillExists.test(use.id())) {
                    problems.add("boss.skills：boss_skills.yml 沒有 " + use.id());
                }
                if (use.fromPhase() > boss.phases().size()) {
                    problems.add("boss.skills：" + use.id() + " 寫 phase: " + use.fromPhase() + "，但 boss.phases 只有 " + boss.phases().size() + " 個階段，永遠用不到");
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

    /** 活動進行中（挖寶、大亂鬥、海妖、漂流瓶都在這段時間）。 */
    public boolean running(final long millis) {
        if (!enabled) {
            return false;
        }
        final LocalDateTime now = local(millis);
        return !now.isBefore(start) && now.isBefore(end.plusMinutes(1)); // 寫 23:59 = 到 24:00
    }

    /** 兌換商店開著（活動期間＋結束後到 shop_until）。 */
    public boolean shopOpen(final long millis) {
        if (!enabled) {
            return false;
        }
        final LocalDateTime now = local(millis);
        return !now.isBefore(start) && now.isBefore(shopUntil.plusMinutes(1));
    }

    /** 今天（活動時區）的代號，例如 20261104。 */
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

    public Dig dig() {
        return dig;
    }

    public Melee melee() {
        return melee;
    }

    /** 2026-11-03 照站長（時間錯開）：大亂鬥、海妖在 hours 那幾點的第幾分開始（0 = 整點）。 */
    private int meleeMinute;
    private int bossMinute;

    public int meleeMinute() {
        return meleeMinute;
    }

    public int bossMinute() {
        return bossMinute;
    }

    public Boss boss() {
        return boss;
    }

    public Bottle bottle() {
        return bottle;
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
