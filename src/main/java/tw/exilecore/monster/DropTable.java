package tw.exilecore.monster;

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
import java.util.random.RandomGenerator;

/**
 * 掉落表（data/drops.yml）與擲骰流程：
 * 件數 = 稀有度基礎件數 ×（1 + 數量加成）→ 每件依分類權重抽通貨或裝備 →
 * 裝備從掉落等級 ≤ 怪物等級的基底抽，稀有度權重再乘（1 + 稀有度加成）→ 物品等級 = 怪物等級。
 * 純邏輯；做成 ItemStack 是 Bukkit 端的事。
 */
public final class DropTable {

    /** 一件掉落：通貨或裝備二選一。 */
    public record Drop(CurrencyType currency, ItemData item) {
        public boolean isCurrency() {
            return currency != null;
        }
    }

    private final Map<Rarity, Integer> rarityWeights = new EnumMap<>(Rarity.class);
    private final Map<MonsterRarity, Double> dropsPerRarity = new EnumMap<>(MonsterRarity.class);
    private int currencyWeight = 65;
    private int equipmentWeight = 35;
    private boolean unidentified = true;

    public DropTable() {
        rarityWeights.put(Rarity.NORMAL, 70);
        rarityWeights.put(Rarity.MAGIC, 25);
        rarityWeights.put(Rarity.RARE, 5);
        dropsPerRarity.put(MonsterRarity.NORMAL, 0.35);
        dropsPerRarity.put(MonsterRarity.MAGIC, 1.5);
        dropsPerRarity.put(MonsterRarity.RARE, 3.0);
        dropsPerRarity.put(MonsterRarity.UNIQUE, 6.0);
    }

    public void load(final Path dataFolder) throws IOException {
        final Path file = dataFolder.resolve("drops.yml");
        if (!Files.exists(file)) {
            return;
        }
        final YamlNode root = YamlNode.load(file);
        final YamlNode rarityNode = root.node("rarity_weights");
        for (final String key : rarityNode.keys()) {
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
        unidentified = root.bool("unidentified", unidentified);
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
        final double expected = dropsPerRarity.getOrDefault(monsterRarity, 0.0) * Math.max(0, 1 + quantityBonus);
        int count = (int) Math.floor(expected);
        if (random.nextDouble() < expected - count) {
            count++;
        }
        final List<Drop> drops = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final Drop drop = rollOne(level, rarityBonus, generator, currencies, random);
            if (drop != null) {
                drops.add(drop);
            }
        }
        return drops;
    }

    public Drop rollOne(final int level, final double rarityBonus, final ItemGenerator generator,
                        final CurrencyRepository currencies, final RandomGenerator random) {
        final int total = currencyWeight + equipmentWeight;
        if (total <= 0) {
            return null;
        }
        if (random.nextInt(total) < currencyWeight) {
            final CurrencyType currency = rollCurrency(currencies, random);
            return currency == null ? null : new Drop(currency, null);
        }
        final List<ItemBase> bases = generator.repository().basesDroppableAt(level);
        if (bases.isEmpty()) {
            return null;
        }
        final ItemBase base = bases.get(random.nextInt(bases.size()));
        final Rarity rarity = rollRarity(rarityBonus, random);
        final ItemData data = generator.generate(base, rarity, level, random);
        if (!unidentified) {
            data.setIdentified(true);
        }
        return new Drop(null, data);
    }

    public CurrencyType rollCurrency(final CurrencyRepository currencies, final RandomGenerator random) {
        int total = 0;
        for (final CurrencyType currency : currencies.all()) {
            total += Math.max(0, currency.dropWeight());
        }
        if (total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        for (final CurrencyType currency : currencies.all()) {
            pick -= Math.max(0, currency.dropWeight());
            if (pick < 0) {
                return currency;
            }
        }
        return null;
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
