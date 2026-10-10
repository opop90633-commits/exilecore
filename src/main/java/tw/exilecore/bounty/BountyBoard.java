package tw.exilecore.bounty;

import tw.exilecore.monster.MonsterRarity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 一個帳號的每日委託與每週回響（純邏輯）：讀帳號進度，做一件事（殺怪、打倒 Boss、完成地圖），
 * 算出要寫回帳號進度的變動（{@link #writes()}）與發生了什麼（{@link #events()}）。
 * <p>
 * 每日委託一格一個鍵（{@code bounty.d0}…），值是「哪一天|哪一種|計數[|done]」：每一格各寫各的，
 * 一格完成時只寫那一格，不會蓋掉別格的計數；日期不是今天的格子就是還沒產生（今天第一次用到時一次產生一整批）。
 * 每週回響是 {@code bounty.weekly}：「哪一週|done」。
 */
public final class BountyBoard {

    public static final String SLOT_PREFIX = "bounty.d";
    public static final String WEEKLY = "bounty.weekly";
    /** 這週開了幾次回響：「2026-W40|2」。 */
    public static final String ECHO = "bounty.echo";
    private static final String DONE = "done";

    /**
     * 一格每日委託。
     *
     * @param template 委託的種類（設定檔的代號）
     */
    public record Daily(int slot, String day, String template, int count, boolean done) {

        public String encode() {
            return day + "|" + template + "|" + count + (done ? "|" + DONE : "");
        }
    }

    /** 要寫回帳號進度的一筆（value 為 null = 刪掉）。 */
    public record Write(String key, String value) {
    }

    /** 發生了什麼。 */
    public sealed interface Event permits Generated, Counted, Completed, WeeklyCompleted {
    }

    /** 今天的委託產生了（新的一天、第一次用到）。 */
    public record Generated(List<Daily> dailies) implements Event {
    }

    /** 一格的計數變了（還沒完成）。 */
    public record Counted(Daily daily, BountyConfig.Template template) implements Event {
    }

    /** 一格完成了（要給獎勵）。 */
    public record Completed(Daily daily, BountyConfig.Template template) implements Event {
    }

    /** 這週的回響完成了（要給獎勵）。 */
    public record WeeklyCompleted(String week) implements Event {
    }

    private final BountyConfig config;
    private final Map<String, String> progress;
    private final String day;
    private final String week;
    private final Map<String, String> writes = new LinkedHashMap<>();
    private final List<Event> events = new ArrayList<>();

    /**
     * @param progress 帳號進度（複製一份來改）
     * @param day      今天（{@link BountyClock#day}）
     * @param week     這週（{@link BountyClock#week}）
     */
    public BountyBoard(final BountyConfig config, final Map<String, String> progress, final String day, final String week) {
        this.config = config;
        this.progress = new HashMap<>(progress);
        this.day = day;
        this.week = week;
    }

    public static String key(final int slot) {
        return SLOT_PREFIX + slot;
    }

    /** 讀一格；寫壞的、空的回傳 null。 */
    public static Daily parse(final int slot, final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String[] parts = value.split("\\|", -1);
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        try {
            return new Daily(slot, parts[0], parts[1], Math.max(0, Integer.parseInt(parts[2])), parts.length > 3 && DONE.equals(parts[3]));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** 這個值是不是一格完成了的委託（完成的那一筆要跟獎勵一起寫）。 */
    public static boolean isDone(final String value) {
        final Daily daily = parse(0, value);
        return daily != null && daily.done();
    }

    /** 同一個帳號同一天的亂數種子（同一天重新產生還是同一批）。 */
    public static long seed(final UUID account, final String day) {
        long seed = account.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ account.getLeastSignificantBits();
        seed = seed * 31 + day.hashCode();
        return new SplittableRandom(seed).nextLong();
    }

    // ---- 讀 ----

    /** 這一格今天的委託（不是今天的、種類已經不在設定檔裡的回傳 null）。 */
    public Daily slot(final int slot) {
        final Daily daily = parse(slot, progress.get(key(slot)));
        if (daily == null || !daily.day().equals(day) || config.template(daily.template()) == null) {
            return null;
        }
        return daily;
    }

    /** 今天的委託（照格子的順序；還沒產生的格子不在裡面）。 */
    public List<Daily> today() {
        final List<Daily> list = new ArrayList<>();
        for (int slot = 0; slot < config.dailyCount(); slot++) {
            final Daily daily = slot(slot);
            if (daily != null) {
                list.add(daily);
            }
        }
        return list;
    }

    /** 今天還有格子沒產生。 */
    public boolean needsGeneration() {
        return today().size() < config.dailyCount();
    }

    /** 今天完成了幾個。 */
    public int doneToday() {
        int done = 0;
        for (final Daily daily : today()) {
            if (daily.done()) {
                done++;
            }
        }
        return done;
    }

    public BountyConfig.Template template(final Daily daily) {
        return config.template(daily.template());
    }

    public boolean weeklyDone() {
        final String value = progress.get(WEEKLY);
        return value != null && value.equals(week + "|" + DONE);
    }

    /** 這週開了幾次回響。 */
    public int echoAttempts() {
        final String value = progress.get(ECHO);
        if (value == null) {
            return 0;
        }
        final String[] parts = value.split("\\|", -1);
        if (parts.length != 2 || !parts[0].equals(week)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(parts[1]));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** 開了一次回響（記下來，每週有上限）。 */
    public void useEchoAttempt() {
        put(ECHO, week + "|" + (echoAttempts() + 1));
    }

    public String day() {
        return day;
    }

    public String week() {
        return week;
    }

    // ---- 產生 ----

    /**
     * 把今天還沒產生的格子補上（已經有的格子不動）：從可以抽的委託裡照權重抽、不重複。
     *
     * @param seed         {@link #seed}（同一天同一個帳號一樣）
     * @param mapsUnlocked 角色能不能跑地圖（不能就不抽要完成地圖的委託）
     */
    public void generate(final long seed, final boolean mapsUnlocked) {
        if (!needsGeneration()) {
            return;
        }
        final Set<String> taken = new HashSet<>();
        for (final Daily daily : today()) {
            taken.add(daily.template());
        }
        final SplittableRandom random = new SplittableRandom(seed);
        final List<BountyConfig.Template> pool = new ArrayList<>();
        for (final BountyConfig.Template template : config.templates().values()) {
            if ((mapsUnlocked || !template.needsMaps()) && !taken.contains(template.id())) {
                pool.add(template);
            }
        }
        final List<Daily> created = new ArrayList<>();
        for (int slot = 0; slot < config.dailyCount(); slot++) {
            if (slot(slot) != null) {
                continue;
            }
            final BountyConfig.Template picked = pick(pool, random);
            if (picked == null) {
                break; // 委託種類不夠（檢查會擋下來，這裡只是不要當掉）
            }
            pool.remove(picked);
            final Daily daily = new Daily(slot, day, picked.id(), 0, false);
            put(key(slot), daily.encode());
            created.add(daily);
        }
        if (!created.isEmpty()) {
            events.add(new Generated(List.copyOf(created)));
        }
    }

    private static BountyConfig.Template pick(final List<BountyConfig.Template> pool, final SplittableRandom random) {
        int total = 0;
        for (final BountyConfig.Template template : pool) {
            total += Math.max(1, template.weight());
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (final BountyConfig.Template template : pool) {
            roll -= Math.max(1, template.weight());
            if (roll < 0) {
                return template;
            }
        }
        return pool.getLast();
    }

    // ---- 動作 ----

    /**
     * 殺了一隻怪（有份的每個人各算一次）。
     *
     * @param boss 是不是 Boss（區域 Boss、地圖 Boss、回響）
     */
    public void kill(final int monsterLevel, final int playerLevel, final MonsterRarity rarity, final boolean boss,
                     final RandomGenerator random) {
        if (!atLevel(monsterLevel, playerLevel)) {
            return;
        }
        for (final Daily daily : today()) {
            if (daily.done()) {
                continue;
            }
            final BountyConfig.Template template = template(daily);
            final boolean counts = switch (template.goal()) {
                case KILL -> matches(template.filter(), rarity);
                case COLLECT -> matches(template.filter(), rarity) && random.nextDouble() < dropChance(template, rarity);
                case BOSS -> boss;
                case MAPS -> false;
            };
            if (counts) {
                advance(daily, template);
            }
        }
    }

    /** 完成了一張地圖（地圖 Boss 倒下時在地圖裡）。 */
    public void mapCompleted(final int mapLevel, final int playerLevel) {
        if (!atLevel(mapLevel, playerLevel)) {
            return;
        }
        for (final Daily daily : today()) {
            final BountyConfig.Template template = template(daily);
            if (!daily.done() && template.goal() == BountyConfig.Goal.MAPS) {
                advance(daily, template);
            }
        }
    }

    /** 怪物（地圖）的等級夠不夠：不能比角色低超過 level_gap 級。 */
    public boolean atLevel(final int monsterLevel, final int playerLevel) {
        return monsterLevel >= playerLevel - config.levelGap();
    }

    private void advance(final Daily daily, final BountyConfig.Template template) {
        final int count = Math.min(template.count(), daily.count() + 1);
        final boolean done = count >= template.count();
        final Daily next = new Daily(daily.slot(), daily.day(), daily.template(), count, done);
        put(key(daily.slot()), next.encode());
        events.add(done ? new Completed(next, template) : new Counted(next, template));
    }

    static boolean matches(final BountyConfig.Filter filter, final MonsterRarity rarity) {
        return switch (filter) {
            case ANY -> true;
            case MAGIC -> rarity != MonsterRarity.NORMAL;
            case RARE -> rarity == MonsterRarity.RARE || rarity == MonsterRarity.UNIQUE;
        };
    }

    /** 撿東西的機率：普通怪照設定、魔法怪 2 倍、稀有以上必掉。 */
    static double dropChance(final BountyConfig.Template template, final MonsterRarity rarity) {
        return switch (rarity) {
            case RARE, UNIQUE -> 1;
            case MAGIC -> Math.min(1, template.chance() * 2);
            default -> template.chance();
        };
    }

    /** 這週的回響完成了；已經完成過回傳 false（不再給獎勵）。 */
    public boolean completeWeekly() {
        if (weeklyDone()) {
            return false;
        }
        put(WEEKLY, week + "|" + DONE);
        events.add(new WeeklyCompleted(week));
        return true;
    }

    // ---- 管理員 ----

    /** 直接完成第 slot 格（從 0 開始）；回傳有沒有動。 */
    public boolean forceComplete(final int slot) {
        final Daily daily = slot(slot);
        if (daily == null || daily.done()) {
            return false;
        }
        final BountyConfig.Template template = template(daily);
        final Daily next = new Daily(slot, day, daily.template(), template.count(), true);
        put(key(slot), next.encode());
        events.add(new Completed(next, template));
        return true;
    }

    /** 今天的委託全部清掉（下次用到時重新產生；同一天的種子一樣，會抽到同一批）。 */
    public void clearToday() {
        for (int slot = 0; slot < BountyConfig.MAX_DAILY; slot++) {
            if (progress.containsKey(key(slot))) {
                put(key(slot), null);
            }
        }
    }

    /** 這週的回響變回沒完成、開的次數歸零。 */
    public void resetWeekly() {
        if (progress.containsKey(WEEKLY)) {
            put(WEEKLY, null);
        }
        if (progress.containsKey(ECHO)) {
            put(ECHO, null);
        }
    }

    // ---- 結果 ----

    private void put(final String key, final String value) {
        if (value == null) {
            progress.remove(key);
        } else {
            progress.put(key, value);
        }
        writes.put(key, value);
    }

    public List<Write> writes() {
        final List<Write> list = new ArrayList<>();
        for (final Map.Entry<String, String> entry : writes.entrySet()) {
            list.add(new Write(entry.getKey(), entry.getValue()));
        }
        return list;
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    /** 改過之後的帳號進度（測試用）。 */
    public Map<String, String> progress() {
        return Collections.unmodifiableMap(progress);
    }
}
