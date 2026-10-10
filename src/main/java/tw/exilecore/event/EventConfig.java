package tw.exilecore.event;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 每月活動的設定（data/events.yml）：主題照月份輪替，每個主題有對地圖的影響、一組每月挑戰與限定稱號。純資料，不依賴 Bukkit。
 */
public final class EventConfig {

    public static final String FILE = "events.yml";
    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    /** 挑戰的種類。 */
    public enum Kind {
        /** 擊殺怪物。 */
        KILL("擊殺"),
        /** 擊殺稀有以上。 */
        RARE("擊殺稀有"),
        /** 打倒 Boss。 */
        BOSS("打倒 Boss"),
        /** 完成地圖。 */
        MAPS("完成地圖"),
        /** 完成地圖事件。 */
        EVENTS("地圖事件"),
        /** 打倒帶主題詞綴的稀有怪。 */
        THEMED("主題擊殺");

        private final String displayName;

        Kind(final String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /**
         * 一次擊殺算哪幾種挑戰：都算「擊殺」；稀有以上多算「擊殺稀有」；Boss 多算「打倒 Boss」；
         * 帶著這個月主題詞綴（rareMod）的稀有怪多算「主題擊殺」。
         *
         * @param mods    怪物身上的詞綴代號
         * @param rareMod 這個月主題的詞綴（null = 這個主題沒有）
         */
        public static List<Kind> forKill(final MonsterRarity rarity, final boolean boss, final List<String> mods, final String rareMod) {
            final boolean rare = rarity == MonsterRarity.RARE || rarity == MonsterRarity.UNIQUE;
            final List<Kind> kinds = new ArrayList<>(4);
            kinds.add(KILL);
            if (rare) {
                kinds.add(RARE);
            }
            if (boss) {
                kinds.add(BOSS);
            }
            if (rare && rareMod != null && mods.contains(rareMod)) {
                kinds.add(THEMED);
            }
            return kinds;
        }

        static Kind parse(final String text, final String where) {
            for (final Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(text.trim())) {
                    return kind;
                }
            }
            throw new IllegalArgumentException(where + "：kind 要是 kill、rare、boss、maps、events 或 themed，卻是「" + text + "」");
        }
    }

    /**
     * 一個每月挑戰。
     *
     * @param id     代號（同一個主題裡不重複）
     * @param kind   種類
     * @param text   說明（{count} 換成數量）
     * @param count  要幾次
     * @param merits 完成給的功勳
     */
    public record Challenge(String id, Kind kind, String text, int count, int merits) {
        public String rendered() {
            return text.replace("{count}", String.valueOf(count));
        }
    }

    /**
     * 限定稱號（名字、顏色）；代號是「event.主題代號」。
     *
     * @param stats 擁有就有的永久加成（整個帳號的角色都吃得到，不用戴）
     */
    public record Title(String name, String color, List<tw.exilecore.stats.Modifier> stats) {
        public Title {
            stats = stats == null ? List.of() : List.copyOf(stats);
        }

        /** 沒有加成的稱號。 */
        public Title(final String name, final String color) {
            this(name, color, List.of());
        }
    }

    /**
     * 一個主題。
     *
     * @param quantity       地圖怪物多掉幾 % 物品數量
     * @param rarity         多幾 % 稀有度
     * @param rareMod        稀有怪一定多的詞綴（null = 沒有）
     * @param eventBoost     事件權重加成（shrine、strongbox、breach、exile → 加多少）
     * @param ritualChance   祭壇機率（負的 = 照 map_events.yml）
     * @param deliriumChance 幻霧機率（負的 = 照 map_events.yml）
     * @param bonusMerits    全部挑戰完成另外給的功勳
     */
    public record Theme(String id, String name, String color, String lore, double quantity, double rarity, String rareMod,
                        Map<String, Integer> eventBoost, double ritualChance, double deliriumChance, List<Challenge> challenges,
                        Title title, int bonusMerits) {
        public Theme {
            eventBoost = Map.copyOf(eventBoost);
            challenges = List.copyOf(challenges);
        }

        /** 限定稱號的代號（存在帳號的稱號清單裡）。 */
        public String titleId() {
            return "event." + id;
        }

        public Challenge challenge(final String challengeId) {
            for (final Challenge challenge : challenges) {
                if (challenge.id().equals(challengeId)) {
                    return challenge;
                }
            }
            return null;
        }
    }

    private boolean enabled = true;
    private String force = "";
    private boolean announce = true;
    private final List<Theme> themes = new ArrayList<>();

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        final EventConfig fresh = new EventConfig();
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        enabled = fresh.enabled;
        force = fresh.force;
        announce = fresh.announce;
        themes.clear();
        themes.addAll(fresh.themes);
    }

    private void parse(final YamlNode root) {
        final YamlNode settings = root.node("settings");
        enabled = settings.bool("enabled", true);
        force = settings.string("force", "").trim();
        announce = settings.bool("announce", true);
        final YamlNode themesNode = root.node("themes");
        for (final String id : themesNode.keys()) {
            final YamlNode node = themesNode.node(id);
            if (!ID.matcher(id).matches() || id.length() > 24) {
                throw new IllegalArgumentException(node.path() + "：代號只能用小寫英文、數字、底線，最多 24 個字");
            }
            final Map<String, Integer> boost = new LinkedHashMap<>();
            final YamlNode boostNode = node.node("event_boost");
            for (final String kind : boostNode.keys()) {
                boost.put(kind.trim().toLowerCase(Locale.ROOT), Math.max(0, boostNode.integer(kind, 0)));
            }
            final List<Challenge> challenges = new ArrayList<>();
            for (final YamlNode challengeNode : node.list("challenges")) {
                final String challengeId = challengeNode.requireString("id").trim();
                if (!ID.matcher(challengeId).matches()) {
                    throw new IllegalArgumentException(challengeNode.path() + ".id：只能用小寫英文、數字、底線");
                }
                for (final Challenge existing : challenges) {
                    if (existing.id().equals(challengeId)) {
                        throw new IllegalArgumentException(challengeNode.path() + ".id：「" + challengeId + "」重複了");
                    }
                }
                final int count = challengeNode.integer("count", 0);
                if (count <= 0) {
                    throw new IllegalArgumentException(challengeNode.path() + ".count：要大於 0");
                }
                challenges.add(new Challenge(challengeId, Kind.parse(challengeNode.requireString("kind"), challengeNode.path()),
                        challengeNode.string("text", challengeId), count, Math.max(0, challengeNode.integer("merits", 0))));
            }
            final YamlNode titleNode = node.node("title");
            final Title title = titleNode.has("name") ? new Title(titleNode.requireString("name").trim(), titleNode.string("color", "#ffffff").trim(),
                    tw.exilecore.ascendancy.StatLines.parse(titleNode.list("stats"), "稱號「" + titleNode.requireString("name").trim() + "」")) : null;
            final String rareMod = node.string("rare_mod", "").trim();
            themes.add(new Theme(id, node.string("name", id).trim(), node.string("color", "#ffffff").trim(), node.string("lore", "").trim(),
                    node.decimal("quantity", 0), node.decimal("rarity", 0), rareMod.isEmpty() ? null : rareMod, boost,
                    node.has("ritual_chance") ? node.decimal("ritual_chance", -1) : -1,
                    node.has("delirium_chance") ? node.decimal("delirium_chance", -1) : -1,
                    challenges, title, Math.max(0, node.integer("bonus_merits", 0))));
        }
        if (!force.isEmpty() && theme(force) == null) {
            throw new IllegalArgumentException("events.yml 的 settings.force：沒有「" + force + "」這個主題");
        }
    }

    /** 檢查主題用到的怪物詞綴是不是存在；回傳問題清單（空的 = 沒問題）。 */
    public List<String> validate(final java.util.Collection<String> monsterMods, final java.util.Collection<String> eventKinds) {
        final List<String> problems = new ArrayList<>();
        for (final Theme theme : themes) {
            if (theme.rareMod() != null && !monsterMods.contains(theme.rareMod())) {
                problems.add("events.yml 的 " + theme.id() + ".rare_mod：monsters.yml 沒有「" + theme.rareMod() + "」這個詞綴");
            }
            for (final String kind : theme.eventBoost().keySet()) {
                if (!eventKinds.contains(kind)) {
                    problems.add("events.yml 的 " + theme.id() + ".event_boost：沒有「" + kind + "」這種事件（shrine、strongbox、breach、exile）");
                }
            }
            boolean themed = false;
            for (final Challenge challenge : theme.challenges()) {
                themed |= challenge.kind() == Kind.THEMED;
            }
            if (themed && theme.rareMod() == null) {
                problems.add("events.yml 的 " + theme.id() + "：有 themed 的挑戰，但沒有 rare_mod");
            }
            if (theme.title() != null) {
                for (final tw.exilecore.stats.Modifier stat : theme.title().stats()) {
                    final String problem = tw.exilecore.ascendancy.StatLines.problem(stat);
                    if (problem != null) {
                        problems.add("events.yml 的 " + theme.id() + ".title.stats：" + problem);
                    }
                }
            }
        }
        return problems;
    }

    public boolean enabled() {
        return enabled && !themes.isEmpty();
    }

    public boolean announce() {
        return announce;
    }

    public String force() {
        return force;
    }

    public List<Theme> themes() {
        return List.copyOf(themes);
    }

    public Theme theme(final String id) {
        for (final Theme theme : themes) {
            if (theme.id().equals(id)) {
                return theme;
            }
        }
        return null;
    }

    /** 這個月的主題：settings.force 指定的，不然照月份輪替（1 月第 1 個…不夠就循環）；沒有主題 null。 */
    public Theme themeFor(final YearMonth month) {
        if (themes.isEmpty()) {
            return null;
        }
        if (!force.isEmpty()) {
            final Theme forced = theme(force);
            if (forced != null) {
                return forced;
            }
        }
        return themes.get((month.getMonthValue() - 1) % themes.size());
    }

    /**
     * 從 from 起（含）下一次輪到這個主題的月份；最多往後看 24 個月，輪不到（settings.force 指定了別的主題）回傳 null。
     * 稱號選單用它寫「下次輪到：2027 年 3 月」。
     */
    public YearMonth nextMonthFor(final Theme theme, final YearMonth from) {
        if (theme == null) {
            return null;
        }
        for (int i = 0; i < 24; i++) {
            final YearMonth month = from.plusMonths(i);
            final Theme current = themeFor(month);
            if (current != null && current.id().equals(theme.id())) {
                return month;
            }
        }
        return null;
    }

    /** 月份的代號（存檔用）：2026-10。 */
    public static String monthKey(final YearMonth month) {
        return String.format(Locale.ROOT, "%04d-%02d", month.getYear(), month.getMonthValue());
    }
}
