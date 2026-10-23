package tw.exilecore.economy;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.item.Rarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * data/economy.yml：通貨值多少金幣、交易與市集的手續費、通貨交易所、商人、賭博。純資料，不依賴 Bukkit。
 * 沒有這個檔時用這裡的預設值；寫錯時保留原本的設定。
 */
public final class EconomyConfig {

    public static final String FILE = "economy.yml";

    /** 手續費：價值的 percent%，至少 min 金幣。 */
    public record Fee(double percent, long min) {
        public long of(final long value) {
            return Math.max(min, Math.round(Math.max(0, value) * percent / 100.0));
        }

        /** 給人看的百分比（3.0 → "3"、2.5 → "2.5"）。 */
        public String percentText() {
            return percent == Math.rint(percent) ? Long.toString((long) percent) : Double.toString(percent);
        }
    }

    /**
     * 補給（商人的金幣商店）。價格 = 同等級普通怪平均掉的金幣 × kills（大約打幾隻同等級的怪會掉的金幣）。
     *
     * @param flaskKills    藥劑的價格倍數（等級＝藥劑的需求等級）
     * @param skillKills    未切割技能寶石的價格倍數（等級＝那個寶石等級的需求角色等級）
     * @param skillMaxLevel 未切割技能寶石最高賣到幾級
     * @param supportKills  未切割輔助寶石每一階的價格倍數（等級＝那一階開始掉的怪物等級）
     * @param unlocks       解鎖：start（一開始）與區域代號（打倒那個區域的 Boss 之後），照設定檔的順序
     */
    public record Shop(boolean enabled, double flaskKills, double skillKills, int skillMaxLevel, Map<Integer, Double> supportKills,
                       Map<String, Unlock> unlocks) {
        public Shop {
            supportKills = Collections.unmodifiableMap(new java.util.TreeMap<>(supportKills));
            unlocks = Collections.unmodifiableMap(new LinkedHashMap<>(unlocks));
        }
    }

    /** 解鎖到哪裡：技能寶石最高幾級、輔助寶石最高第幾階（0 = 還沒有）。 */
    public record Unlock(int skillLevel, int supportTier) {
    }

    /** 補給解鎖的「一開始」寫法。 */
    public static final String UNLOCK_START = "start";

    private final Map<String, Long> values = new LinkedHashMap<>();
    /** 跟著交易所成交價算出來的參考價值（沒有的通貨用 values 的設定值）；/ec reload 不會清掉。 */
    private volatile Map<String, Long> market = Map.of();
    private ReferenceValues.Settings reference;
    private Shop shop;
    private double tradeRange;
    private int tradeRequestSeconds;
    private int confirmDelaySeconds;
    private int tradeMaxItems;
    private Fee tradeFee;
    private int shopSlots;
    private Fee listingFee;
    private Fee purchaseFee;
    private int relistMinutes;
    private int maxPrice;
    private Fee exchangeFee;
    private int maxOrders;
    private int maxOrderAmount;
    private final Map<Rarity, Integer> vendorStock = new EnumMap<>(Rarity.class);
    private int vendorRefreshMinutes;
    private long vendorRefreshCostPerLevel;
    /** 馬上換一批 = 同等級普通怪平均掉的金幣 × 這個（0 = 用 refresh_cost_per_level）。 */
    private double vendorRefreshKills;
    private double vendorPriceBase;
    private double vendorPricePerLevel;
    /** 商人的價格 = 同等級普通怪平均掉的金幣 × 這個 × 稀有度倍率（0 = 用 base + per_level × 物品等級）。 */
    private double vendorKills;
    private final Map<Rarity, Double> vendorRarity = new EnumMap<>(Rarity.class);
    private double gambleBase;
    private double gamblePerLevel;
    /** 賭一件 = 同等級普通怪平均掉的金幣 × 這個（0 = 用 base + per_level × 等級）。 */
    private double gambleKills;
    private final Map<Rarity, Double> gambleWeights = new EnumMap<>(Rarity.class);
    /** 地圖 Boss 也會掉的傳奇（uniques.yml 的 sources 有 map_boss）在賭博裡的權重。 */
    private double gambleBossUnique;
    /** 2026-10-17：只能賭、但照 boss_unique 的機率出的傳奇（gamble.boss_rate_uniques）。 */
    private final java.util.Set<String> gambleBossRate = new java.util.HashSet<>();
    /**
     * 等級 → 同等級普通怪平均掉的金幣（drops.yml；經濟模組啟用時接上）。拿不到（0）時，價格退回 base + per_level 的舊算法。
     * 不跟著 /ec reload 換（不是設定檔的內容）。
     */
    private IntToDoubleFunction goldReference = level -> 0;

    public EconomyConfig() {
        reset();
    }

    private void reset() {
        values.clear();
        values.put("glimmer_orb", 10L);
        values.put("lustre_orb", 12L);
        values.put("whetstone", 20L);
        values.put("armour_scrap", 20L);
        values.put("bounty_orb", 60L);
        values.put("spark_orb", 60L);
        values.put("lesser_socket_orb", 75L);
        values.put("turmoil_orb", 100L);
        values.put("chance_orb", 120L);
        values.put("chisel", 190L);
        values.put("annul_orb", 200L);
        values.put("blessing_orb", 300L);
        values.put("socket_orb", 375L);
        values.put("zenith_orb", 1500L);
        values.put("oracle_orb", 2000L);
        values.put("greater_socket_orb", 2500L);
        values.put("gemcutter_prism", 375L); // 2026-10-23
        values.put("glassblower_bauble", 190L); // 2026-10-23
        values.put("sky_fragment", 6000L);
        reference = ReferenceValues.Settings.defaults();
        shop = defaultShop();
        tradeRange = 16;
        tradeRequestSeconds = 30;
        confirmDelaySeconds = 2;
        tradeMaxItems = 16;
        tradeFee = new Fee(3, 10);
        shopSlots = 27;
        listingFee = new Fee(2, 10);
        purchaseFee = new Fee(3, 10);
        relistMinutes = 10;
        maxPrice = 5000;
        exchangeFee = new Fee(2, 5);
        maxOrders = 10;
        maxOrderAmount = 5000;
        vendorStock.clear();
        vendorStock.put(Rarity.NORMAL, 10);
        vendorStock.put(Rarity.MAGIC, 6);
        vendorStock.put(Rarity.RARE, 2);
        vendorRefreshMinutes = 30;
        vendorRefreshCostPerLevel = 10;
        vendorRefreshKills = 18;
        vendorPriceBase = 20;
        vendorPricePerLevel = 4;
        vendorKills = 12;
        vendorRarity.clear();
        vendorRarity.put(Rarity.NORMAL, 1.0);
        vendorRarity.put(Rarity.MAGIC, 3.0);
        vendorRarity.put(Rarity.RARE, 10.0);
        vendorRarity.put(Rarity.UNIQUE, 20.0); // 商人不賣傳奇：只用在面對面交易的估價
        gambleBase = 100;
        gamblePerLevel = 25;
        gambleKills = 90;
        gambleWeights.clear();
        gambleWeights.put(Rarity.MAGIC, 55.0);
        gambleWeights.put(Rarity.RARE, 43.0);
        gambleWeights.put(Rarity.UNIQUE, 2.0);
        gambleBossUnique = 0.65;
        gambleBossRate.clear();
    }

    /** 讀 economy.yml；先讀進一份新的，全部檢查過才換上去。檔案不存在就用預設值。 */
    public void load(final Path dataFolder) throws IOException {
        final EconomyConfig fresh = new EconomyConfig();
        final Path file = dataFolder.resolve(FILE);
        if (Files.exists(file)) {
            fresh.parse(YamlNode.load(file));
        }
        copyFrom(fresh);
    }

    /** 從已經讀好的節點載入（單元測試用）。 */
    public void load(final YamlNode root) {
        final EconomyConfig fresh = new EconomyConfig();
        fresh.parse(root);
        copyFrom(fresh);
    }

    private void parse(final YamlNode root) {
        if (root.has("values")) {
            values.clear();
            final YamlNode node = root.node("values");
            for (final String id : node.keys()) {
                final long value = Math.round(node.decimal(id, 0));
                if (value < 0) {
                    throw new IllegalArgumentException(node.path() + "." + id + "：價值不能是負的");
                }
                if (value > 0) {
                    values.put(id, value);
                }
            }
        }
        final YamlNode ref = root.node("reference");
        final ReferenceValues.Settings refDefaults = ReferenceValues.Settings.defaults();
        final double maxStep = ref.decimal("max_step", refDefaults.maxStep());
        final double lowest = ref.decimal("min", refDefaults.lowest());
        final double highest = ref.decimal("max", refDefaults.highest());
        if (maxStep < 0 || maxStep > 1 || lowest <= 0 || lowest > 1 || highest < 1) {
            throw new IllegalArgumentException(ref.path() + "：max_step 要在 0 到 1 之間、min 在 0 到 1 之間（不含 0）、max 至少 1");
        }
        reference = new ReferenceValues.Settings(ref.bool("follow_market", refDefaults.enabled()),
                ref.string("anchor", refDefaults.anchor()).trim(), Math.max(1, Math.min(60, ref.integer("days", refDefaults.days()))),
                Math.max(1, ref.integer("min_pairs", refDefaults.minPairs())), maxStep, lowest, highest);
        if (reference.enabled() && !values.containsKey(reference.anchor())) {
            throw new IllegalArgumentException(ref.path() + ".anchor：基準通貨「" + reference.anchor() + "」要寫在 values 裡");
        }
        if (root.has("shop")) {
            shop = parseShop(root.node("shop"), shop);
        }
        final YamlNode trade = root.node("trade");
        tradeRange = positive(trade, "range", tradeRange);
        tradeRequestSeconds = (int) positive(trade, "request_seconds", tradeRequestSeconds);
        confirmDelaySeconds = Math.max(0, trade.integer("confirm_delay_seconds", confirmDelaySeconds));
        tradeMaxItems = Math.max(1, Math.min(16, trade.integer("max_items", tradeMaxItems)));
        tradeFee = fee(trade.node("fee"), tradeFee);
        final YamlNode market = root.node("market");
        shopSlots = Math.max(1, Math.min(45, market.integer("shop_slots", shopSlots)));
        listingFee = fee(market.node("listing_fee"), listingFee);
        purchaseFee = fee(market.node("purchase_fee"), purchaseFee);
        relistMinutes = Math.max(0, market.integer("relist_minutes", relistMinutes));
        maxPrice = Math.max(1, market.integer("max_price", maxPrice));
        final YamlNode exchange = root.node("exchange");
        exchangeFee = fee(exchange.node("fee"), exchangeFee);
        maxOrders = Math.max(1, exchange.integer("max_orders", maxOrders));
        maxOrderAmount = Math.max(1, exchange.integer("max_amount", maxOrderAmount));
        final YamlNode vendor = root.node("vendor");
        final YamlNode stock = vendor.node("stock");
        for (final Rarity rarity : new Rarity[] {Rarity.NORMAL, Rarity.MAGIC, Rarity.RARE}) {
            vendorStock.put(rarity, Math.max(0, stock.integer(key(rarity), vendorStock.get(rarity))));
        }
        if (stock() > 45) {
            throw new IllegalArgumentException(stock.path() + "：商人一次最多賣 45 件");
        }
        vendorRefreshMinutes = Math.max(1, vendor.integer("refresh_minutes", vendorRefreshMinutes));
        vendorRefreshCostPerLevel = Math.max(0, vendor.integer("refresh_cost_per_level", (int) vendorRefreshCostPerLevel));
        vendorRefreshKills = nonNegative(vendor, "refresh_kills", vendorRefreshKills);
        final YamlNode price = vendor.node("price");
        vendorPriceBase = Math.max(0, price.decimal("base", vendorPriceBase));
        vendorPricePerLevel = Math.max(0, price.decimal("per_level", vendorPricePerLevel));
        vendorKills = nonNegative(price, "kills", vendorKills);
        final YamlNode rarityNode = price.node("rarity");
        for (final Rarity rarity : Rarity.values()) {
            vendorRarity.put(rarity, Math.max(0, rarityNode.decimal(key(rarity), vendorRarity.get(rarity))));
        }
        final YamlNode gamble = root.node("gamble");
        final YamlNode gamblePrice = gamble.node("price");
        gambleBase = Math.max(0, gamblePrice.decimal("base", gambleBase));
        gamblePerLevel = Math.max(0, gamblePrice.decimal("per_level", gamblePerLevel));
        gambleKills = nonNegative(gamblePrice, "kills", gambleKills);
        final YamlNode weights = gamble.node("rarity_weights");
        for (final Rarity rarity : new Rarity[] {Rarity.MAGIC, Rarity.RARE, Rarity.UNIQUE}) {
            gambleWeights.put(rarity, nonNegative(weights, key(rarity), gambleWeights.get(rarity)));
        }
        gambleBossUnique = nonNegative(weights, "boss_unique", gambleBossUnique);
        if (gamble.has("boss_rate_uniques")) {
            for (final String id : gamble.strings("boss_rate_uniques")) {
                if (!id.isBlank()) {
                    gambleBossRate.add(id.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        tw.exilecore.monster.DropTable.setBossRateUniques(gambleBossRate); // 2026-10-17：怪物掉落也照這份名單用 Boss 的機率
        if (gambleWeights.get(Rarity.MAGIC) + gambleWeights.get(Rarity.RARE) <= 0) {
            throw new IllegalArgumentException(weights.path() + "：魔法與稀有的權重不能都是 0");
        }
    }

    private static double nonNegative(final YamlNode node, final String key, final double def) {
        final double value = node.decimal(key, def);
        if (value < 0) {
            throw new IllegalArgumentException(node.path() + "." + key + "：不能是負的");
        }
        return value;
    }

    private static Shop defaultShop() {
        final Map<String, Unlock> unlocks = new LinkedHashMap<>();
        unlocks.put(UNLOCK_START, new Unlock(4, 0));
        unlocks.put("coast", new Unlock(7, 1));
        unlocks.put("forest", new Unlock(10, 1));
        unlocks.put("ruins", new Unlock(13, 2));
        unlocks.put("swamp", new Unlock(16, 2));
        unlocks.put("mountain", new Unlock(18, 3));
        return new Shop(true, 30, 80, 18, Map.of(1, 100.0, 2, 200.0, 3, 400.0), unlocks);
    }

    private static Shop parseShop(final YamlNode node, final Shop def) {
        final double flask = node.node("flask").decimal("kills", def.flaskKills());
        final YamlNode skill = node.node("uncut_skill");
        final double skillKills = skill.decimal("kills", def.skillKills());
        final int maxLevel = skill.integer("max_level", def.skillMaxLevel());
        if (flask < 0 || skillKills < 0 || maxLevel < 1 || maxLevel > 20) {
            throw new IllegalArgumentException(node.path() + "：kills 不能是負的，uncut_skill.max_level 要在 1 到 20 之間");
        }
        final Map<Integer, Double> supportKills = new java.util.TreeMap<>(def.supportKills());
        final YamlNode supportNode = node.node("uncut_support").node("kills");
        if (!supportNode.keys().isEmpty()) {
            supportKills.clear();
            for (final String key : supportNode.keys()) {
                final int tier;
                try {
                    tier = Integer.parseInt(key.trim());
                } catch (final NumberFormatException e) {
                    throw new IllegalArgumentException(supportNode.path() + "：階級要是數字（1、2、3），卻是「" + key + "」");
                }
                final double kills = supportNode.decimal(key, 0);
                if (tier < 1 || kills < 0) {
                    throw new IllegalArgumentException(supportNode.path() + "." + key + "：階級至少 1、kills 不能是負的");
                }
                supportKills.put(tier, kills);
            }
        }
        final Map<String, Unlock> unlocks = new LinkedHashMap<>(def.unlocks());
        final YamlNode unlockNode = node.node("unlock");
        if (!unlockNode.keys().isEmpty()) {
            unlocks.clear();
            for (final String key : unlockNode.keys()) {
                final YamlNode entry = unlockNode.node(key);
                final Unlock unlock = new Unlock(Math.max(0, Math.min(20, entry.integer("skill_level", 0))),
                        Math.max(0, entry.integer("support_tier", 0)));
                unlocks.put(key.trim(), unlock);
            }
        }
        return new Shop(node.bool("enabled", def.enabled()), flask, skillKills, maxLevel, supportKills, unlocks);
    }

    private void copyFrom(final EconomyConfig other) {
        values.clear();
        values.putAll(other.values);
        reference = other.reference;
        shop = other.shop;
        tradeRange = other.tradeRange;
        tradeRequestSeconds = other.tradeRequestSeconds;
        confirmDelaySeconds = other.confirmDelaySeconds;
        tradeMaxItems = other.tradeMaxItems;
        tradeFee = other.tradeFee;
        shopSlots = other.shopSlots;
        listingFee = other.listingFee;
        purchaseFee = other.purchaseFee;
        relistMinutes = other.relistMinutes;
        maxPrice = other.maxPrice;
        exchangeFee = other.exchangeFee;
        maxOrders = other.maxOrders;
        maxOrderAmount = other.maxOrderAmount;
        vendorStock.clear();
        vendorStock.putAll(other.vendorStock);
        vendorRefreshMinutes = other.vendorRefreshMinutes;
        vendorRefreshCostPerLevel = other.vendorRefreshCostPerLevel;
        vendorRefreshKills = other.vendorRefreshKills;
        vendorPriceBase = other.vendorPriceBase;
        vendorPricePerLevel = other.vendorPricePerLevel;
        vendorKills = other.vendorKills;
        vendorRarity.clear();
        vendorRarity.putAll(other.vendorRarity);
        gambleBase = other.gambleBase;
        gamblePerLevel = other.gamblePerLevel;
        gambleKills = other.gambleKills;
        gambleWeights.clear();
        gambleWeights.putAll(other.gambleWeights);
        gambleBossUnique = other.gambleBossUnique;
        gambleBossRate.clear();
        gambleBossRate.addAll(other.gambleBossRate);
        tw.exilecore.monster.DropTable.setBossRateUniques(gambleBossRate);
    }

    private static String key(final Rarity rarity) {
        return rarity.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static double positive(final YamlNode node, final String key, final double def) {
        final double value = node.decimal(key, def);
        if (value <= 0) {
            throw new IllegalArgumentException(node.path() + "." + key + "：要大於 0");
        }
        return value;
    }

    private static Fee fee(final YamlNode node, final Fee def) {
        final double percent = node.decimal("percent", def.percent());
        final long min = Math.round(node.decimal("min", def.min()));
        if (percent < 0 || min < 0) {
            throw new IllegalArgumentException(node.path() + "：手續費不能是負的");
        }
        return new Fee(percent, min);
    }

    // ---- 通貨的價值 ----

    /**
     * 這種通貨值多少金幣（不能拿來標價的是 0）：跟著市價走時用算出來的參考價值，還沒算過（或那種通貨沒有）用設定值。
     */
    public long value(final String currencyId) {
        if (currencyId == null) {
            return 0;
        }
        final Long configured = values.get(currencyId);
        if (configured == null || configured <= 0) {
            return 0;
        }
        final Long fromMarket = reference.enabled() ? market.get(currencyId) : null;
        return fromMarket != null && fromMarket > 0 ? fromMarket : configured;
    }

    /** economy.yml 的設定值（不管市價）。 */
    public long configuredValue(final String currencyId) {
        return currencyId == null ? 0 : values.getOrDefault(currencyId, 0L);
    }

    /** 換上跟著市價算出來的參考價值（主執行緒）。 */
    public void setMarketValues(final Map<String, Long> values) {
        this.market = Map.copyOf(values);
    }

    /** 目前跟著市價算出來的參考價值（沒算過是空的）。 */
    public Map<String, Long> marketValues() {
        return market;
    }

    /** 參考價值是不是跟著市價走（設定打開、而且已經算過）。 */
    public boolean followsMarket() {
        return reference.enabled() && !market.isEmpty();
    }

    /** 參考價值跟著市價走的設定。 */
    public ReferenceValues.Settings reference() {
        return reference;
    }

    /** 補給（商人的金幣商店）的設定。 */
    public Shop shop() {
        return shop;
    }

    /** amount 個這種通貨值多少金幣。 */
    public long value(final String currencyId, final int amount) {
        return value(currencyId) * Math.max(0, amount);
    }

    /** 能拿來標價、在交易所買賣的通貨（照設定檔的順序）。 */
    public Map<String, Long> values() {
        return Collections.unmodifiableMap(values);
    }

    public boolean priceable(final String currencyId) {
        return value(currencyId) > 0;
    }

    // ---- 面對面交易 ----

    public double tradeRange() {
        return tradeRange;
    }

    public int tradeRequestSeconds() {
        return tradeRequestSeconds;
    }

    public int confirmDelaySeconds() {
        return confirmDelaySeconds;
    }

    public int tradeMaxItems() {
        return tradeMaxItems;
    }

    /** 面對面交易的手續費：每個人付自己收到的東西估價的 percent%（估價見 TradeFee）。 */
    public Fee tradeFee() {
        return tradeFee;
    }

    // ---- 市集 ----

    public int shopSlots() {
        return shopSlots;
    }

    /** 上架（或改價）的手續費。 */
    public long listingFee(final String currencyId, final int amount) {
        return listingFee.of(value(currencyId, amount));
    }

    /** 購買的手續費（買家付）。 */
    public long purchaseFee(final String currencyId, final int amount) {
        return purchaseFee.of(value(currencyId, amount));
    }

    public Fee listingFee() {
        return listingFee;
    }

    public Fee purchaseFee() {
        return purchaseFee;
    }

    public long relistMillis() {
        return relistMinutes * 60_000L;
    }

    public int relistMinutes() {
        return relistMinutes;
    }

    public int maxPrice() {
        return maxPrice;
    }

    // ---- 通貨交易所 ----

    /** 掛單的手續費（依掛出去的通貨）。 */
    public long exchangeFee(final String giveCurrency, final int giveAmount) {
        return exchangeFee.of(value(giveCurrency, giveAmount));
    }

    public Fee exchangeFee() {
        return exchangeFee;
    }

    public int maxOrders() {
        return maxOrders;
    }

    public int maxOrderAmount() {
        return maxOrderAmount;
    }

    // ---- 商人 ----

    /** 商人一批賣幾件這個稀有度的。 */
    public int stock(final Rarity rarity) {
        return vendorStock.getOrDefault(rarity, 0);
    }

    /** 一批總共幾件。 */
    public int stock() {
        int total = 0;
        for (final int count : vendorStock.values()) {
            total += count;
        }
        return total;
    }

    public long vendorRefreshMillis() {
        return vendorRefreshMinutes * 60_000L;
    }

    public int vendorRefreshMinutes() {
        return vendorRefreshMinutes;
    }

    /** 馬上換一批的價格：同等級普通怪平均掉的金幣 × refresh_kills（拿不到金幣表時：每級 refresh_cost_per_level）。 */
    public long vendorRefreshCost(final int level) {
        final double gold = referenceGold(level);
        if (vendorRefreshKills > 0 && gold > 0) {
            return Math.max(1, Math.round(vendorRefreshKills * gold));
        }
        return vendorRefreshCostPerLevel * Math.max(1, level);
    }

    /**
     * 商人賣的一件東西的價格（面對面交易也用這個估裝備值多少金幣）：
     * 物品等級的普通怪平均掉的金幣 × kills × 稀有度倍率（拿不到金幣表時：(base + per_level × 物品等級) × 稀有度倍率）。
     */
    public long vendorPrice(final Rarity rarity, final int itemLevel) {
        final double multiplier = vendorRarity.getOrDefault(rarity, 1.0);
        final double gold = referenceGold(itemLevel);
        if (vendorKills > 0 && gold > 0) {
            return Math.max(1, Math.round(vendorKills * gold * multiplier));
        }
        return Math.max(1, Math.round((vendorPriceBase + vendorPricePerLevel * Math.max(1, itemLevel)) * multiplier));
    }

    // ---- 賭博 ----

    /** 賭一件的價格：同等級普通怪平均掉的金幣 × kills（拿不到金幣表時：base + per_level × 等級）。 */
    public long gamblePrice(final int level) {
        final double gold = referenceGold(level);
        if (gambleKills > 0 && gold > 0) {
            return Math.max(1, Math.round(gambleKills * gold));
        }
        return Math.max(1, Math.round(gambleBase + gamblePerLevel * Math.max(1, level)));
    }

    /**
     * 一個基底的傳奇權重：能賭到的傳奇裡有地圖 Boss 也會掉的（bossPool）→ boss_unique；只有能賭的（劇情期間用的）→ unique；
     * 沒有能賭的傳奇 → 0（那份權重算成稀有，見 {@link #rollGamble(RandomGenerator, double)}）。
     */
    /** 這件只能賭的傳奇照 boss_unique 的機率出（gamble.boss_rate_uniques）。 */
    public boolean gambleBossRate(final String uniqueId) {
        return uniqueId != null && gambleBossRate.contains(uniqueId);
    }

    public double gambleUniqueWeight(final boolean anyUnique, final boolean bossPool) {
        if (!anyUnique) {
            return 0;
        }
        return bossPool ? gambleBossUnique : gambleWeights.getOrDefault(Rarity.UNIQUE, 0.0);
    }

    /** 賭到傳奇的機率（小數；僥倖石也用這個）：bossPool = 這個基底的傳奇有地圖 Boss 也會掉的。 */
    public double gambleUniqueChance(final boolean bossPool) {
        final double[] odds = gambleOdds(gambleUniqueWeight(true, bossPool));
        final double total = odds[0] + odds[1] + odds[2];
        return total <= 0 ? 0 : odds[2] / total;
    }

    /** 賭出來的稀有度（舊的寫法：有傳奇就用 unique 的權重）。 */
    public Rarity rollGamble(final RandomGenerator random, final boolean uniqueAvailable) {
        return rollGamble(random, gambleUniqueWeight(uniqueAvailable, false));
    }

    /**
     * 賭出來的稀有度。uniqueWeight 是這個基底的傳奇權重（{@link #gambleUniqueWeight}）；
     * 是 0（沒有能賭的傳奇）時，設定的 unique 權重算成稀有。
     */
    public Rarity rollGamble(final RandomGenerator random, final double uniqueWeight) {
        final double[] weights = gambleOdds(uniqueWeight);
        final double total = weights[0] + weights[1] + weights[2];
        if (total <= 0) {
            return Rarity.MAGIC;
        }
        final double pick = random.nextDouble() * total;
        if (pick < weights[0]) {
            return Rarity.MAGIC;
        }
        return pick < weights[0] + weights[1] ? Rarity.RARE : Rarity.UNIQUE;
    }

    /** 魔法、稀有、傳奇的權重（給選單算百分比）：uniqueWeight 是 0 時，unique 的權重算成稀有。 */
    public double[] gambleOdds(final double uniqueWeight) {
        final double magic = gambleWeights.getOrDefault(Rarity.MAGIC, 0.0);
        final double rare = gambleWeights.getOrDefault(Rarity.RARE, 0.0)
                + (uniqueWeight > 0 ? 0 : gambleWeights.getOrDefault(Rarity.UNIQUE, 0.0));
        return new double[] {magic, rare, Math.max(0, uniqueWeight)};
    }

    /** 這個稀有度的權重（說明用）。 */
    public double gambleWeight(final Rarity rarity) {
        return gambleWeights.getOrDefault(rarity, 0.0);
    }

    /** 地圖 Boss 也會掉的傳奇在賭博裡的權重。 */
    public double gambleBossUnique() {
        return gambleBossUnique;
    }

    // ---- 價格的金幣基準 ----

    /** 接上金幣表：等級 → 同等級普通怪平均掉的金幣（drops.yml）。 */
    public void goldReference(final IntToDoubleFunction reference) {
        this.goldReference = reference == null ? level -> 0 : reference;
    }

    /** 這個等級的普通怪平均掉的金幣；拿不到（模組還沒好、單元測試）是 0。 */
    public double referenceGold(final int level) {
        try {
            return Math.max(0, goldReference.applyAsDouble(Math.max(1, level)));
        } catch (final RuntimeException e) {
            return 0;
        }
    }
}
