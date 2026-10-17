package tw.exilecore.monster;

import tw.exilecore.core.config.LevelCurve;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.Rarity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;

/**
 * 掉落表（data/drops.yml）與擲骰流程：
 * 件數 = 稀有度基礎件數 ×（1 + 數量加成）→ 每件依分類權重抽通貨、裝備、未切割寶石或藥劑 →
 * 裝備從掉落等級 ≤ 怪物等級的基底依掉落權重抽（珠寶比較少見、至少是魔法的），稀有度權重再乘（1 + 稀有度加成）→ 物品等級 = 怪物等級。
 * 寶石與藥劑只決定「掉一顆、怪物幾級」，實際的等級與基底由技能、藥劑模組決定。
 * 純邏輯；做成 ItemStack 是 Bukkit 端的事。
 */
public final class DropTable {

    /** 掉落的種類。 */
    public enum Kind { CURRENCY, EQUIPMENT, SKILL_GEM, SUPPORT_GEM, FLASK }

    /**
     * 一件掉落。
     *
     * @param currency 通貨（CURRENCY）
     * @param item     裝備（EQUIPMENT）
     * @param level    怪物等級（寶石與藥劑用）
     * @param gem      指定的輔助寶石代號（SUPPORT_GEM：終局、極境的專屬輔助；null = 未切割輔助寶石）
     */
    public record Drop(Kind kind, CurrencyType currency, ItemData item, int level, String gem) {
        public Drop(final Kind kind, final CurrencyType currency, final ItemData item, final int level) {
            this(kind, currency, item, level, null);
        }

        public static Drop currency(final CurrencyType currency) {
            return new Drop(Kind.CURRENCY, currency, null, 0);
        }

        public static Drop equipment(final ItemData item) {
            return new Drop(Kind.EQUIPMENT, null, item, item.ilvl());
        }

        public static Drop special(final Kind kind, final int level) {
            return new Drop(kind, null, null, level);
        }

        /** 一顆指定的（切好的）輔助寶石：終局、極境的專屬輔助。 */
        public static Drop support(final String supportId, final int level) {
            return new Drop(Kind.SUPPORT_GEM, null, null, level, supportId);
        }

        /** 一顆指定的（切好的）技能寶石：劫盜的異質寶石；level 是寶石等級。 */
        public static Drop skill(final String skillId, final int gemLevel) {
            return new Drop(Kind.SKILL_GEM, null, null, gemLevel, skillId);
        }

        /** 是不是指定的寶石（專屬的輔助、劫盜的異質技能寶石；過濾器不藏、算值得提示）。 */
        public boolean isSpecificGem() {
            return (kind == Kind.SUPPORT_GEM || kind == Kind.SKILL_GEM) && gem != null;
        }

        public boolean isCurrency() {
            return kind == Kind.CURRENCY;
        }

        public boolean isEquipment() {
            return kind == Kind.EQUIPMENT;
        }
    }

    private final Map<Rarity, Integer> rarityWeights = new EnumMap<>(Rarity.class);
    /** 掉出來的裝備變成傳奇的權重（那個基底有能賭出來的傳奇才算；有地圖 Boss 也會掉的傳奇用 boss）。 */
    private double uniqueWeight;
    private double bossUniqueWeight;
    private final Map<MonsterRarity, Double> dropsPerRarity = new EnumMap<>(MonsterRarity.class);
    private int currencyWeight = 65;
    private int equipmentWeight = 35;
    private int skillGemWeight;
    private int supportGemWeight;
    private int flaskWeight;
    /** 魔法與稀有裝備掉落時是否未鑑定（PoE2 做法：未鑑定，找鑑定師 NPC 免費鑑定）。 */
    private boolean unidentified = true;
    /** 職業掉落權重（2026-10，drops.yml 的 class_bias）：自己職業的基底類 ×match、屬性相近的防具 ×partial。 */
    private boolean classBias = true;
    private double classMatch = 3.0;
    private double classPartial = 1.5;
    private final Map<MonsterRarity, Double> goldChance = new EnumMap<>(MonsterRarity.class);
    private final Map<MonsterRarity, Double> goldMultiplier = new EnumMap<>(MonsterRarity.class);
    private final TreeMap<Integer, int[]> goldAmount = new TreeMap<>();
    /**
     * 全服掉落加成（drops.yml 的 server_bonus；活動用，管理網頁可以直接改）：物品數量、稀有度（小數，0.5 = +50%）、金幣倍數。
     * 數量與稀有度由擲掉落的地方加進去（怪物、地圖寶箱：跟其他加成一起算，防刷的衰減也照算）；數量不影響金幣。
     * 金幣倍數在 {@link #rollGold} 裡乘，只乘掉出來的金幣，不影響照金幣表算的價格（商人、賭博、補給）。
     */
    private double serverQuantity;
    private double serverRarity;
    private double serverGold = 1;

    /** 全服掉落加成的範圍（百分比；金幣是倍數）。 */
    public static final double MAX_SERVER_BONUS = 500;
    public static final double MIN_SERVER_GOLD = 0.1;
    public static final double MAX_SERVER_GOLD = 10;

    public DropTable() {
        rarityWeights.put(Rarity.NORMAL, 70);
        rarityWeights.put(Rarity.MAGIC, 25);
        rarityWeights.put(Rarity.RARE, 5);
        dropsPerRarity.put(MonsterRarity.NORMAL, 0.35);
        dropsPerRarity.put(MonsterRarity.MAGIC, 1.5);
        dropsPerRarity.put(MonsterRarity.RARE, 3.0);
        dropsPerRarity.put(MonsterRarity.UNIQUE, 6.0);
        goldChance.put(MonsterRarity.NORMAL, 0.6);
        goldChance.put(MonsterRarity.MAGIC, 1.0);
        goldChance.put(MonsterRarity.RARE, 1.0);
        goldChance.put(MonsterRarity.UNIQUE, 1.0);
        goldMultiplier.put(MonsterRarity.NORMAL, 1.0);
        goldMultiplier.put(MonsterRarity.MAGIC, 3.0);
        goldMultiplier.put(MonsterRarity.RARE, 8.0);
        goldMultiplier.put(MonsterRarity.UNIQUE, 25.0);
        goldAmount.put(1, new int[] {2, 6});
        goldAmount.put(60, new int[] {90, 200});
        goldAmount.put(100, new int[] {200, 440});
    }

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("drops.yml");
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        final YamlNode rarityNode = root.node("rarity_weights");
        uniqueWeight = 0;
        bossUniqueWeight = 0;
        for (final String key : rarityNode.keys()) {
            // 2026-10：unique、boss_unique 是「這件裝備變成傳奇」的權重（只有能賭出傳奇的基底；跟上面三個一起比、也吃稀有度）
            if (key.equals("unique")) {
                uniqueWeight = Math.max(0, rarityNode.decimal(key, 0));
                continue;
            }
            if (key.equals("boss_unique")) {
                bossUniqueWeight = Math.max(0, rarityNode.decimal(key, 0));
                continue;
            }
            final Rarity rarity = Rarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(rarityNode.path() + "：不認得的稀有度 " + key);
            }
            rarityWeights.put(rarity, rarityNode.integer(key, 0));
        }
        final YamlNode perRarity = root.node("drops_per_rarity");
        for (final String key : perRarity.keys()) {
            final MonsterRarity rarity = MonsterRarity.parse(key, null);
            if (rarity == null) {
                throw new IllegalArgumentException(perRarity.path() + "：不認得的怪物稀有度 " + key);
            }
            dropsPerRarity.put(rarity, perRarity.decimal(key, 0));
        }
        final YamlNode category = root.node("category_weights");
        currencyWeight = category.integer("currency", currencyWeight);
        equipmentWeight = category.integer("equipment", equipmentWeight);
        skillGemWeight = category.integer("skill_gem", skillGemWeight);
        supportGemWeight = category.integer("support_gem", supportGemWeight);
        flaskWeight = category.integer("flask", flaskWeight);
        unidentified = root.bool("unidentified", unidentified);
        final YamlNode bias = root.node("class_bias");
        classBias = bias.bool("enabled", true);
        classMatch = clamp(bias.decimal("match", 3.0), 0, 20);
        classPartial = clamp(bias.decimal("partial", 1.5), 0, 20);
        // 全服加成：檔案沒寫（舊版的 drops.yml）= 沒有加成；寫錯的範圍夾回去，不讓整個掉落表載入失敗
        final YamlNode bonus = root.node("server_bonus");
        serverQuantity = clamp(bonus.decimal("quantity", 0), 0, MAX_SERVER_BONUS) / 100.0;
        serverRarity = clamp(bonus.decimal("rarity", 0), 0, MAX_SERVER_BONUS) / 100.0;
        serverGold = clamp(bonus.decimal("gold", 1), MIN_SERVER_GOLD, MAX_SERVER_GOLD);
        if (root.has("gold")) {
            final YamlNode gold = root.node("gold");
            final YamlNode chance = gold.node("chance");
            for (final String key : chance.keys()) {
                goldChance.put(parseMonsterRarity(chance, key), chance.decimal(key, 0));
            }
            final YamlNode multiplier = gold.node("multiplier");
            for (final String key : multiplier.keys()) {
                goldMultiplier.put(parseMonsterRarity(multiplier, key), multiplier.decimal(key, 1));
            }
            if (gold.has("amount")) {
                // 先讀進一份新的再換上去：寫錯時保留原本的表（商人、賭博、補給的價格都照這張表算）
                final TreeMap<Integer, int[]> parsed = new TreeMap<>();
                final YamlNode amount = gold.node("amount");
                for (final String key : amount.keys()) {
                    parsed.put(Integer.parseInt(key.trim()), amount.range(key));
                }
                if (parsed.isEmpty()) {
                    throw new IllegalArgumentException("drops.yml：gold.amount 至少要有一個等級");
                }
                goldAmount.clear();
                goldAmount.putAll(parsed);
            }
        }
    }

    private static double clamp(final double value, final double min, final double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    /** 全服的物品數量加成（小數；0.5 = +50%）。 */
    /** 照職業的新手裝備算這個職業的掉落調整（關掉或沒有職業 = 不調整）。 */
    public tw.exilecore.item.LootBias classBias(final tw.exilecore.item.ItemRepository repository,
                                                final java.util.Collection<String> weapons, final java.util.Collection<String> armour,
                                                final String shield) {
        if (!classBias || classMatch == 1 && classPartial == 1) {
            return tw.exilecore.item.LootBias.NONE;
        }
        return tw.exilecore.item.LootBias.fromStarter(repository, weapons, armour, shield, classMatch, classPartial);
    }

    public double serverQuantity() {
        return serverQuantity + tw.exilecore.sponsor.Boosts.quantity() + tw.exilecore.character.OnlineBonus.quantity(); // 2026-10：加上贊助的全服加倍、在線人數加成
    }

    /** 全服的物品稀有度加成（小數）。 */
    public double serverRarity() {
        return serverRarity + tw.exilecore.sponsor.Boosts.rarity() + tw.exilecore.character.OnlineBonus.rarity(); // 2026-10：加上贊助的全服加倍、在線人數加成
    }

    /** 全服的金幣倍數。 */
    public double serverGold() {
        return serverGold * tw.exilecore.sponsor.Boosts.gold() * tw.exilecore.character.OnlineBonus.gold(); // 2026-10：加上贊助的全服加倍、在線人數加成
    }

    private static MonsterRarity parseMonsterRarity(final YamlNode node, final String key) {
        final MonsterRarity rarity = MonsterRarity.parse(key, null);
        if (rarity == null) {
            throw new IllegalArgumentException(node.path() + "：不認得的怪物稀有度 " + key);
        }
        return rarity;
    }

    /** 這隻怪物掉多少金幣（0 = 不掉）：機率 → 等級表內插 × 稀有度倍率 ×（1 + 數量加成）× 全服的金幣倍數。 */
    public long rollGold(final int level, final MonsterRarity monsterRarity, final double quantityBonus, final RandomGenerator random) {
        if (random.nextDouble() >= goldChance.getOrDefault(monsterRarity, 0.0)) {
            return 0;
        }
        final int[] range = goldRange(level);
        final int lo = Math.min(range[0], range[1]);
        final int hi = Math.max(range[0], range[1]);
        final int base = lo == hi ? lo : lo + random.nextInt(hi - lo + 1);
        return Math.max(0, Math.round(base * goldMultiplier.getOrDefault(monsterRarity, 1.0) * Math.max(0, 1 + quantityBonus) * serverGold()));
    }

    /** 同等級普通怪平均掉幾個金幣（金幣範圍的中間值；掉落過濾換金幣用）。 */
    public double averageGold(final int level) {
        final int[] range = goldRange(level);
        return (range[0] + range[1]) / 2.0;
    }

    /** 等級對應的金幣範圍（線性內插）。 */
    public int[] goldRange(final int level) {
        return LevelCurve.intRange(goldAmount, level, new int[] {0, 0}, LevelCurve.Mode.LINEAR);
    }

    /**
     * 擲一隻怪物的掉落。
     *
     * @param level         怪物等級（= 物品等級）
     * @param monsterRarity 怪物稀有度
     * @param quantityBonus 數量加成（小數；怪物稀有度與玩家的 drop.quantity 相加）
     * @param rarityBonus   稀有度加成（小數）
     */
    public List<Drop> roll(final int level, final MonsterRarity monsterRarity, final double quantityBonus,
                           final double rarityBonus, final ItemGenerator generator,
                           final CurrencyRepository currencies, final RandomGenerator random) {
        return roll(level, monsterRarity, quantityBonus, rarityBonus, generator, currencies, random, tw.exilecore.item.LootBias.NONE);
    }

    /** 同上，裝備的基底照職業調整（{@link tw.exilecore.item.LootBias}；傳奇不吃）。 */
    public List<Drop> roll(final int level, final MonsterRarity monsterRarity, final double quantityBonus,
                           final double rarityBonus, final ItemGenerator generator,
                           final CurrencyRepository currencies, final RandomGenerator random,
                           final tw.exilecore.item.LootBias bias) {
        final double expected = dropsPerRarity.getOrDefault(monsterRarity, 0.0) * Math.max(0, 1 + quantityBonus);
        int count = (int) Math.floor(expected);
        if (random.nextDouble() < expected - count) {
            count++;
        }
        final List<Drop> drops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final Drop drop = rollOne(level, rarityBonus, generator, currencies, random, bias);
            if (drop != null) {
                drops.add(drop);
            }
        }
        return drops;
    }

    public Drop rollOne(final int level, final double rarityBonus, final ItemGenerator generator,
                        final CurrencyRepository currencies, final RandomGenerator random) {
        return rollOne(level, rarityBonus, generator, currencies, random, tw.exilecore.item.LootBias.NONE);
    }

    public Drop rollOne(final int level, final double rarityBonus, final ItemGenerator generator,
                        final CurrencyRepository currencies, final RandomGenerator random, final tw.exilecore.item.LootBias bias) {
        final int total = currencyWeight + equipmentWeight + skillGemWeight + supportGemWeight + flaskWeight;
        if (total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        if (pick < currencyWeight) {
            final CurrencyType currency = rollCurrency(level, currencies, random, rarityBonus);
            return currency == null ? null : Drop.currency(currency);
        }
        pick -= currencyWeight;
        if (pick >= equipmentWeight) {
            pick -= equipmentWeight;
            if (pick < skillGemWeight) {
                return Drop.special(Kind.SKILL_GEM, level);
            }
            pick -= skillGemWeight;
            return Drop.special(pick < supportGemWeight ? Kind.SUPPORT_GEM : Kind.FLASK, level);
        }
        // 傳奇照原本的權重擲（不吃職業調整，2026-10）：先用沒調過的權重抽一個基底擲傳奇，沒中才照職業抽這件的基底
        final ItemBase plain = generator.repository().pickDroppable(level, random);
        if (plain == null) {
            return null;
        }
        // 2026-10：能賭出傳奇的基底，掉落時也有機會直接是那個傳奇（權重吃稀有度）
        final ItemData unique = rollUnique(plain, level, rarityBonus, generator, random);
        if (unique != null) {
            if (unidentified) {
                unique.setIdentified(false);
            }
            return Drop.equipment(unique);
        }
        ItemBase base = plain;
        if (bias != null && !bias.none()) {
            final ItemBase biased = generator.repository().pickDroppable(level, random, bias);
            base = biased == null ? plain : biased;
        }
        Rarity rarity = rollRarity(rarityBonus, random);
        if (base.isJewel() && rarity == Rarity.NORMAL) {
            rarity = Rarity.MAGIC; // 珠寶掉出來至少是魔法的（普通的珠寶什麼都沒有）
        }
        final ItemData data = generator.generate(base, rarity, level, random);
        if (unidentified && rarity != Rarity.NORMAL) {
            data.setIdentified(false);
        }
        return Drop.equipment(data);
    }

    /** 依掉落權重抽一種通貨；只有掉落等級 ≤ 怪物等級的才會掉（大鑿星石要高等級的怪）。 */
    public CurrencyType rollCurrency(final int level, final CurrencyRepository currencies, final RandomGenerator random) {
        return rollCurrency(level, currencies, random, 0);
    }

    /**
     * 依掉落權重抽一種通貨，稀有度加成讓稀有的通貨比較常出現（2026-10）：
     * 每種的權重乘（1 + 稀有度 × 稀有程度），稀有程度照權重的對數算——最常見的那種 0（不變），最稀有的那種 1（乘滿）。
     * 例如稀有度 +100%：最稀有的通貨相對 ×2，中間的 ×1.5 左右，最常見的不變。
     */
    public CurrencyType rollCurrency(final int level, final CurrencyRepository currencies, final RandomGenerator random,
                                     final double rarityBonus) {
        final List<CurrencyType> eligible = new ArrayList<>();
        final List<Double> weights = new ArrayList<>();
        int max = 0;
        int min = Integer.MAX_VALUE;
        for (final CurrencyType currency : currencies.all()) {
            if (currency.dropLevel() <= level && currency.dropWeight() > 0) {
                eligible.add(currency);
                max = Math.max(max, currency.dropWeight());
                min = Math.min(min, currency.dropWeight());
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        double total = 0;
        for (final CurrencyType currency : eligible) {
            final double weight = currency.dropWeight() * (1 + Math.max(0, rarityBonus) * scarcity(currency.dropWeight(), min, max));
            weights.add(weight);
            total += weight;
        }
        double pick = random.nextDouble() * total;
        for (int i = 0; i < eligible.size(); i++) {
            pick -= weights.get(i);
            if (pick < 0) {
                return eligible.get(i);
            }
        }
        return eligible.getLast();
    }

    /** 稀有程度 0–1：權重 max 的是 0，權重 min 的是 1，中間照對數。 */
    static double scarcity(final double weight, final double min, final double max) {
        if (max <= min || weight >= max) {
            return 0;
        }
        return Math.max(0, Math.min(1, Math.log(max / weight) / Math.log(max / min)));
    }

    /** economy.yml 的 gamble.boss_rate_uniques（只能賭的稀有傳奇，例如踏骸之履）：怪物掉落一樣用 Boss 傳奇的機率。 */
    private static volatile java.util.Set<String> bossRateUniques = java.util.Set.of();

    /** 這件是不是照 Boss 機率的稀有傳奇（兌換券不能直接挑）。 */
    public static boolean bossRateUnique(final String id) {
        return bossRateUniques.contains(id);
    }

    public static void setBossRateUniques(final java.util.Collection<String> ids) {
        bossRateUniques = java.util.Set.copyOf(ids);
    }

    /** 能賭出傳奇的基底：照權重擲一次會不會變成傳奇（權重乘 1 + 稀有度）；不會或沒有能賭的傳奇回傳 null。 */
    ItemData rollUnique(final ItemBase base, final int level, final double rarityBonus, final ItemGenerator generator,
                        final RandomGenerator random) {
        if (uniqueWeight <= 0 && bossUniqueWeight <= 0) {
            return null;
        }
        final List<tw.exilecore.item.UniqueItem> pool = new ArrayList<>();
        boolean boss = false;
        for (final tw.exilecore.item.UniqueItem unique : generator.repository().uniques()) {
            if (unique.base().equals(base.id()) && unique.droppedBy("gamble") && unique.dropLevel() <= level && unique.weight() > 0) {
                pool.add(unique);
                boss |= unique.droppedBy("map_boss") || bossRateUniques.contains(unique.id()); // 2026-10-17：以前只有賭博照這份名單
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        final double bonus = Math.max(0, 1 + rarityBonus);
        final double weight = (boss ? bossUniqueWeight : uniqueWeight) * bonus;
        final double total = rarityWeights.getOrDefault(Rarity.NORMAL, 0)
                + (rarityWeights.getOrDefault(Rarity.MAGIC, 0) + rarityWeights.getOrDefault(Rarity.RARE, 0)) * bonus + weight;
        if (weight <= 0 || total <= 0 || random.nextDouble() * total >= weight) {
            return null;
        }
        return generator.generateUnique(ItemGenerator.pickUnique(pool, random), level, random);
    }

    /** 稀有度：魔法與稀有的權重乘（1 + 稀有度加成）。 */
    public Rarity rollRarity(final double rarityBonus, final RandomGenerator random) {
        final double normal = rarityWeights.getOrDefault(Rarity.NORMAL, 0);
        final double magic = rarityWeights.getOrDefault(Rarity.MAGIC, 0) * Math.max(0, 1 + rarityBonus);
        final double rare = rarityWeights.getOrDefault(Rarity.RARE, 0) * Math.max(0, 1 + rarityBonus);
        final double total = normal + magic + rare;
        if (total <= 0) {
            return Rarity.NORMAL;
        }
        final double pick = random.nextDouble() * total;
        if (pick < rare) {
            return Rarity.RARE;
        }
        if (pick < rare + magic) {
            return Rarity.MAGIC;
        }
        return Rarity.NORMAL;
    }
}
