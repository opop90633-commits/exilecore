package tw.exilecore.slots;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.random.RandomGenerator;

/**
 * 拉霸機（2026-11-02 照站長：營地用金幣下注的拉霸機，回收金幣；這次之前沒有）的規則，data/slots.yml。
 * 純資料、純函式，不依賴 Bukkit（單元測試直接用）：
 * <ul>
 *   <li>抽結果：照權重從「沒中」與每一種獎裡抽一個（{@link #roll}）。</li>
 *   <li>期望回報率：Σ 權重 × 倍數 ÷ 總權重（{@link #expectedReturn}）；通貨的數量無條件捨去，所以這是上限。</li>
 *   <li>通貨獎：數量 = 倍數 × 押注 ÷ 參考價值（{@link #currencyPrize}）。</li>
 *   <li>稱號：每個押注額一個，用那個押注額中頭獎才給那一個（{@link #titleFor}）。2026-11-02 照站長；
 *       這次之前只有一個稱號，押 1,000 以上中頭獎或保底（累積 3,000 把）給，保底已經拿掉。</li>
 * </ul>
 */
public final class SlotsConfig {

    public static final String FILE = "slots.yml";
    /** sponsor.yml 裡拉霸限定時裝的 limited。 */
    public static final String LIMITED_TAG = "拉霸機";

    /** 獎的種類。 */
    public enum Kind {
        GOLD, CURRENCY, COSMETIC, JACKPOT
    }

    /** 頭獎另外送的通貨：從 pool 抽，價值 = multiplier × 押注。 */
    public record Bonus(String pool, double multiplier) {
    }

    /**
     * 一種中獎的結果（滾輪上的一個圖案）。
     *
     * @param multiplier 金幣倍數；通貨是「價值幾倍押注」；時裝是「都有了改給幾倍金幣」
     */
    public record Outcome(String id, Kind kind, double multiplier, int weight, String symbol, String name, String pool, Bonus bonus) {

        /** 這個結果最多值幾倍押注（期望回報率的算法）。 */
        public double value() {
            return multiplier + (bonus == null ? 0 : bonus.multiplier());
        }
    }

    public record PoolEntry(String id, int weight) {
    }

    /** 抽到的通貨：哪一種、幾顆。 */
    public record CurrencyPrize(String id, int amount) {
    }

    /** 100 萬那一階沿用的舊稱號代號（2026-11-02 之前唯一的拉霸稱號「金運亨通」）：已經拿到的人直接算擁有這一階。 */
    public static final String LEGACY_TITLE_ID = "slots_fortune";

    /**
     * 拉霸限定稱號（2026-11-02 照站長：每個押注額一個，用那個押注額中頭獎才給；這次之前只有一個，還有保底）。
     *
     * @param bet 用這個押注額中頭獎才給（剛好這個押注額，押更大的不算）
     */
    public record Title(long bet, String id, String name, String color, List<Modifier> stats) {
        public Title {
            stats = List.copyOf(stats);
        }

        /** 這一把頭獎送不送這個稱號：押注剛好是這一階。 */
        public boolean jackpotGrants(final long spinBet) {
            return spinBet == bet;
        }

        /** 怪物掉落金幣增加幾 %（gold.find INCREASED 加總，顯示用）。 */
        public double goldFindPercent() {
            double sum = 0;
            for (final Modifier modifier : stats) {
                if (tw.exilecore.stats.StatKeys.GOLD_FIND.equals(modifier.stat()) && modifier.type() == tw.exilecore.stats.ModType.INCREASED) {
                    sum += modifier.value();
                }
            }
            return sum * 100;
        }
    }

    private boolean enabled = true;
    private int reels = 3;
    private int spinTicks = 30;
    private long minGapMillis = 0;
    private boolean townOnly = true;
    private int autoSpins = 10;
    private int autoGapTicks = 10;
    private int autoCooldownSeconds = 0;
    private double announceMultiplier = 50;
    private boolean autoPlace = true;
    private double machineX = 12.5;
    private Double machineY;
    private double machineZ = 14.5;
    private float machineYaw = 135f;
    private List<Long> bets = List.of(100L, 1000L, 10000L);
    private int loseWeight;
    private List<Outcome> outcomes = List.of();
    private Map<String, List<PoolEntry>> pools = Map.of();
    private List<String> cosmetics = List.of();
    private List<Title> titles = List.of();
    /** slots.yml 還是舊的單一 title 格式（沒有 titles）：照舊讀成最高押注那一階，validate 提醒換新格式。 */
    private boolean legacyTitleFormat;

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve(FILE);
        if (!Files.exists(file)) {
            enabled = false;
            outcomes = List.of();
            return;
        }
        load(YamlNode.load(file));
    }

    /** 核心邏輯（單元測試直接餵 YAML）；全部讀成功才換上。 */
    public void load(final YamlNode root) {
        final YamlNode settings = root.node("settings");
        final int parsedReels = settings.integer("reels", 3);
        if (parsedReels != 3 && parsedReels != 5) {
            throw new IllegalArgumentException(settings.path() + ".reels：要是 3 或 5");
        }
        final int parsedSpin = settings.integer("spin_ticks", 30);
        if (parsedSpin < 10 || parsedSpin > 200) {
            throw new IllegalArgumentException(settings.path() + ".spin_ticks：要在 10 到 200 之間");
        }
        final YamlNode auto = settings.node("auto");
        final YamlNode machine = root.node("machine");

        final List<Long> parsedBets = new ArrayList<>();
        final Object rawBets = root.raw("bets");
        if (!(rawBets instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(root.path() + ".bets：至少要一個押注額，寫成 [100, 1000, 10000]");
        }
        for (final Object value : list) {
            final long bet;
            try {
                bet = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value).trim());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(root.path() + ".bets：「" + value + "」不是數字");
            }
            if (bet <= 0 || (!parsedBets.isEmpty() && bet <= parsedBets.getLast())) {
                throw new IllegalArgumentException(root.path() + ".bets：要是正的、由小排到大（" + value + "）");
            }
            parsedBets.add(bet);
        }
        if (parsedBets.size() > 7) {
            throw new IllegalArgumentException(root.path() + ".bets：最多 7 種押注額（選單上一排放不下）");
        }

        final Map<String, List<PoolEntry>> parsedPools = new LinkedHashMap<>();
        final YamlNode poolNode = root.node("currency_pools");
        for (final String id : poolNode.keys()) {
            final List<PoolEntry> entries = new ArrayList<>();
            for (final YamlNode entry : poolNode.list(id)) {
                final int weight = entry.integer("weight", 1);
                if (weight <= 0) {
                    throw new IllegalArgumentException(entry.path() + ".weight：要大於 0");
                }
                entries.add(new PoolEntry(entry.requireString("id").trim(), weight));
            }
            if (entries.isEmpty()) {
                throw new IllegalArgumentException(poolNode.path() + "." + id + "：是空的");
            }
            parsedPools.put(id, List.copyOf(entries));
        }

        final int parsedLose = root.integer("lose_weight", 0);
        if (parsedLose < 0) {
            throw new IllegalArgumentException(root.path() + ".lose_weight：不能是負的");
        }
        final List<Outcome> parsedOutcomes = new ArrayList<>();
        final YamlNode outcomeNode = root.node("outcomes");
        for (final String id : outcomeNode.keys()) {
            final YamlNode node = outcomeNode.node(id);
            final Kind kind;
            try {
                kind = Kind.valueOf(node.requireString("kind").trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(node.path() + ".kind：要是 gold、currency、cosmetic 或 jackpot");
            }
            final double multiplier = node.decimal("multiplier", 0);
            final int weight = node.integer("weight", 0);
            if (multiplier <= 0) {
                throw new IllegalArgumentException(node.path() + ".multiplier：要大於 0");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException(node.path() + ".weight：要大於 0");
            }
            final String pool = node.has("pool") ? node.string("pool", "").trim() : null;
            if (kind == Kind.CURRENCY && (pool == null || !parsedPools.containsKey(pool))) {
                throw new IllegalArgumentException(node.path() + ".pool：沒有「" + pool + "」這個通貨獎池（currency_pools）");
            }
            Bonus bonus = null;
            if (node.has("bonus")) {
                final YamlNode bonusNode = node.node("bonus");
                final String bonusPool = bonusNode.requireString("pool").trim();
                if (!parsedPools.containsKey(bonusPool)) {
                    throw new IllegalArgumentException(bonusNode.path() + ".pool：沒有「" + bonusPool + "」這個通貨獎池（currency_pools）");
                }
                final double bonusMultiplier = bonusNode.decimal("multiplier", 0);
                if (bonusMultiplier <= 0) {
                    throw new IllegalArgumentException(bonusNode.path() + ".multiplier：要大於 0");
                }
                bonus = new Bonus(bonusPool, bonusMultiplier);
            }
            parsedOutcomes.add(new Outcome(id, kind, multiplier, weight, node.string("symbol", "GOLD_NUGGET").trim().toUpperCase(Locale.ROOT),
                    node.string("name", id).trim(), pool, bonus));
        }
        if (parsedOutcomes.size() < 2) {
            throw new IllegalArgumentException(outcomeNode.path() + "：至少要兩種結果（沒中的時候才擺得出「不一樣」的圖案）");
        }

        final List<String> parsedCosmetics = new ArrayList<>();
        for (final String id : root.strings("cosmetics")) {
            parsedCosmetics.add(id.trim());
        }

        // 2026-11-02 照站長：稱號每個押注額一個（titles 清單）；這次之前是單一的 title（還有保底的 pity_spins、pity_min_bet）
        final List<Title> parsedTitles = new ArrayList<>();
        boolean parsedLegacy = false;
        if (root.has("titles")) {
            for (final YamlNode node : root.list("titles")) {
                final Object rawBet = node.raw("bet");
                if (!(rawBet instanceof Number)) {
                    throw new IllegalArgumentException(node.path() + ".bet：要寫押注額（數字，bets 裡的其中一個）");
                }
                final String titleName = node.requireString("name").trim();
                parsedTitles.add(new Title(((Number) rawBet).longValue(), node.requireString("id").trim(), titleName,
                        node.string("color", "#ffd24a").trim(), tw.exilecore.ascendancy.StatLines.parse(node.list("stats"), "稱號「" + titleName + "」")));
            }
        } else if (root.node("title").has("id")) {
            // 改過的舊檔（升級時不會自動換新）：舊的單一稱號照舊登記成最高押注那一階（擁有的人不會掉加成），validate 提醒換新格式
            final YamlNode titleNode = root.node("title");
            final String titleName = titleNode.requireString("name").trim();
            parsedTitles.add(new Title(parsedBets.getLast(), titleNode.requireString("id").trim(), titleName, titleNode.string("color", "#ffd24a").trim(),
                    tw.exilecore.ascendancy.StatLines.parse(titleNode.list("stats"), "稱號「" + titleName + "」")));
            parsedLegacy = true;
        }
        final List<String> titleErrors = titleProblems(parsedBets, parsedTitles, false);
        if (!titleErrors.isEmpty()) {
            throw new IllegalArgumentException(titleErrors.getFirst());
        }

        // 期望回報率 ≥ 1 玩家穩賺、回收不了金幣：整份不換上（保留原本的設定）
        int total = parsedLose;
        double sum = 0;
        for (final Outcome outcome : parsedOutcomes) {
            total += outcome.weight();
            sum += outcome.weight() * outcome.value();
        }
        if (sum / total >= 1) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "%s：期望回報率 %.3f ≥ 1（玩家穩賺，回收不了金幣），調低倍數或加大 lose_weight",
                    root.path(), sum / total));
        }

        enabled = settings.bool("enabled", true);
        reels = parsedReels;
        spinTicks = parsedSpin;
        minGapMillis = 0; // 2026-11-02 照站長：拉霸不用冷卻（滾輪轉完才能拉下一把）；這次之前每人每秒最多一次（min_gap_millis）
        townOnly = settings.bool("town_only", true);
        autoSpins = Math.max(1, Math.min(100, auto.integer("spins", 10)));
        autoGapTicks = Math.max(0, auto.integer("gap_ticks", 10));
        autoCooldownSeconds = 0; // 2026-11-02 照站長：自動連拉結束後不用等；這次之前 cooldown_seconds（預設 30 秒）
        announceMultiplier = settings.decimal("announce_multiplier", 50);
        autoPlace = machine.bool("auto_place", true);
        machineX = machine.decimal("x", 12.5);
        machineY = machine.has("y") ? machine.decimal("y", 0) : null;
        machineZ = machine.decimal("z", 14.5);
        machineYaw = (float) machine.decimal("yaw", 135);
        bets = List.copyOf(parsedBets);
        loseWeight = parsedLose;
        outcomes = List.copyOf(parsedOutcomes);
        pools = java.util.Collections.unmodifiableMap(parsedPools);
        cosmetics = List.copyOf(parsedCosmetics);
        parsedTitles.sort(java.util.Comparator.comparingLong(Title::bet)); // 選單由小到大列
        titles = List.copyOf(parsedTitles);
        legacyTitleFormat = parsedLegacy;
    }

    /**
     * 稱號對不對得上押注額：押注額要在 bets 裡、同一個押注額不能兩個、代號不能重複；
     * missing = 也檢查每個押注額都有一個（讀檔時不擋，validate 才報）。
     */
    static List<String> titleProblems(final List<Long> bets, final List<Title> titles, final boolean missing) {
        final List<String> problems = new ArrayList<>();
        final java.util.Set<Long> seenBets = new java.util.HashSet<>();
        final java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (final Title title : titles) {
            if (!bets.contains(title.bet())) {
                problems.add("slots.yml：titles 的「" + title.name() + "」押注額 " + title.bet() + " 不在 bets 裡");
            }
            if (!seenBets.add(title.bet())) {
                problems.add("slots.yml：titles 的押注額 " + title.bet() + " 有兩個稱號（每個押注額剛好一個）");
            }
            if (title.id().isEmpty() || !seenIds.add(title.id())) {
                problems.add("slots.yml：titles 的代號「" + title.id() + "」重複了");
            }
        }
        if (missing) {
            for (final long bet : bets) {
                if (!seenBets.contains(bet)) {
                    problems.add("slots.yml：押注額 " + bet + " 沒有稱號（titles 每個押注額要剛好一個）");
                }
            }
        }
        return problems;
    }

    /** 對得上別的設定檔嗎（通貨、時裝存在，有參考價值）；回傳問題（空的 = 沒問題）。 */
    public List<String> validate(final Predicate<String> currencyExists, final ToLongFunction<String> value, final Predicate<String> cosmeticExists) {
        final List<String> problems = new ArrayList<>();
        for (final Map.Entry<String, List<PoolEntry>> pool : pools.entrySet()) {
            for (final PoolEntry entry : pool.getValue()) {
                if (!currencyExists.test(entry.id())) {
                    problems.add("slots.yml：currency_pools." + pool.getKey() + " 沒有「" + entry.id() + "」這種通貨");
                } else if (value.applyAsLong(entry.id()) <= 0) {
                    problems.add("slots.yml：currency_pools." + pool.getKey() + " 的「" + entry.id() + "」在 economy.yml 沒有參考價值（抽不到）");
                }
            }
        }
        for (final String id : cosmetics) {
            if (!cosmeticExists.test(id)) {
                problems.add("slots.yml：cosmetics 的「" + id + "」不是 sponsor.yml 的時裝");
            }
        }
        if (legacyTitleFormat) {
            problems.add("slots.yml：還是舊的 title（單一稱號、保底）格式，2026-11-02 起改成 titles（每個押注額一個），保底已經拿掉；照新檔的 titles 改");
        }
        problems.addAll(titleProblems(bets, titles, true));
        if (expectedReturn() >= 1) {
            problems.add(String.format(Locale.ROOT, "slots.yml：期望回報率 %.3f ≥ 1（玩家穩賺，回收不了金幣）", expectedReturn()));
        }
        return problems;
    }

    // ---- 純函式 ----

    /** 總權重（沒中＋每一種獎）。 */
    public int totalWeight() {
        int total = loseWeight;
        for (final Outcome outcome : outcomes) {
            total += outcome.weight();
        }
        return total;
    }

    /** 期望回報率（上限）：Σ 權重 × 最多值幾倍 ÷ 總權重。 */
    public double expectedReturn() {
        final int total = totalWeight();
        if (total <= 0) {
            return 0;
        }
        double sum = 0;
        for (final Outcome outcome : outcomes) {
            sum += outcome.weight() * outcome.value();
        }
        return sum / total;
    }

    /** 這種結果的機率。 */
    public double chance(final Outcome outcome) {
        final int total = totalWeight();
        return total <= 0 ? 0 : (double) outcome.weight() / total;
    }

    /** 抽一次：沒中回傳空的。 */
    public Optional<Outcome> roll(final RandomGenerator random) {
        final int total = totalWeight();
        if (total <= 0) {
            return Optional.empty();
        }
        int pick = random.nextInt(total);
        for (final Outcome outcome : outcomes) {
            pick -= outcome.weight();
            if (pick < 0) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty(); // 落在 lose_weight
    }

    /**
     * 通貨獎：價值 multiplier × bet 金幣。只從換得到至少一顆的通貨裡照權重抽一種，數量無條件捨去；
     * 一種都換不到（押注太小）回傳空的（改給金幣）。
     */
    public Optional<CurrencyPrize> currencyPrize(final String pool, final double multiplier, final long bet, final ToLongFunction<String> value,
                                                 final RandomGenerator random) {
        final List<PoolEntry> entries = pools.getOrDefault(pool, List.of());
        final double budget = multiplier * bet;
        final List<PoolEntry> eligible = new ArrayList<>();
        int total = 0;
        for (final PoolEntry entry : entries) {
            final long price = value.applyAsLong(entry.id());
            if (price > 0 && price <= budget) {
                eligible.add(entry);
                total += entry.weight();
            }
        }
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        int pick = random.nextInt(total);
        for (final PoolEntry entry : eligible) {
            pick -= entry.weight();
            if (pick < 0) {
                final long amount = (long) Math.floor(budget / value.applyAsLong(entry.id()));
                return Optional.of(new CurrencyPrize(entry.id(), (int) Math.max(1, Math.min(Integer.MAX_VALUE, amount))));
            }
        }
        return Optional.empty();
    }

    /** 金幣獎：multiplier × bet（無條件捨去）。 */
    public static long goldPrize(final double multiplier, final long bet) {
        return (long) Math.floor(multiplier * bet);
    }

    /**
     * 中間那一排的圖案（每個滾輪一個，數字是 {@link #outcomes} 的位置）：中了是全部一樣；沒中隨機擺，保證不會全部一樣。
     */
    public int[] line(final Outcome outcome, final RandomGenerator random) {
        final int[] line = new int[reels];
        if (outcome != null) {
            java.util.Arrays.fill(line, outcomes.indexOf(outcome));
            return line;
        }
        return loseLine(reels, outcomes.size(), random);
    }

    /** 沒中的一排：n 個滾輪，symbols 種圖案隨機擺，不會全部一樣（symbols ≥ 2）。 */
    public static int[] loseLine(final int reels, final int symbols, final RandomGenerator random) {
        final int[] line = new int[reels];
        for (int i = 0; i < reels; i++) {
            line[i] = random.nextInt(symbols);
        }
        boolean same = true;
        for (int i = 1; i < reels; i++) {
            same &= line[i] == line[0];
        }
        if (same && reels > 1 && symbols > 1) {
            final int at = random.nextInt(reels); // 剛好全部一樣：隨便換掉一格，換成別的圖案
            line[at] = Math.floorMod(line[at] + 1 + random.nextInt(symbols - 1), symbols);
        }
        return line;
    }

    /** 第 reel 個滾輪（0 起算）在第幾 tick 停：從左到右依序停，最後一個在 spin_ticks。 */
    public static int stopTick(final int reel, final int reels, final int spinTicks) {
        final int step = Math.max(2, spinTicks / (reels + 1));
        return Math.max(1, spinTicks - (reels - 1 - reel) * step);
    }

    // ---- 設定值 ----

    public boolean enabled() {
        return enabled;
    }

    public int reels() {
        return reels;
    }

    public int spinTicks() {
        return spinTicks;
    }

    public long minGapMillis() {
        return minGapMillis;
    }

    public boolean townOnly() {
        return townOnly;
    }

    public int autoSpins() {
        return autoSpins;
    }

    public int autoGapTicks() {
        return autoGapTicks;
    }

    public int autoCooldownSeconds() {
        return autoCooldownSeconds;
    }

    public double announceMultiplier() {
        return announceMultiplier;
    }

    public boolean autoPlace() {
        return autoPlace;
    }

    public double machineX() {
        return machineX;
    }

    /** 機台的高度；沒寫是 null（營地地面）。 */
    public Double machineY() {
        return machineY;
    }

    public double machineZ() {
        return machineZ;
    }

    public float machineYaw() {
        return machineYaw;
    }

    public List<Long> bets() {
        return bets;
    }

    public int loseWeight() {
        return loseWeight;
    }

    public List<Outcome> outcomes() {
        return outcomes;
    }

    public Map<String, List<PoolEntry>> pools() {
        return pools;
    }

    public List<String> cosmetics() {
        return cosmetics;
    }

    /** 拉霸限定稱號（照押注額由小到大的設定檔順序）；沒設定是空的。 */
    public List<Title> titles() {
        return titles;
    }

    /** 用這個押注額中頭獎給的稱號；沒有 null。 */
    public Title titleFor(final long bet) {
        for (final Title title : titles) {
            if (title.jackpotGrants(bet)) {
                return title;
            }
        }
        return null;
    }

    /** 這個代號是不是拉霸限定稱號；不是 null。 */
    public Title titleById(final String id) {
        for (final Title title : titles) {
            if (title.id().equals(id)) {
                return title;
            }
        }
        return null;
    }
}
