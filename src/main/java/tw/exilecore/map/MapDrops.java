package tw.exilecore.map;

import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 地圖掉落（純邏輯）：掉哪一張、幾階、什麼稀有度；傳奇抽哪一件。PoE2 換界石的做法，階級寫在地圖物品上：
 * <ul>
 *   <li>階級 = 這次的基本階級（地圖怪 = 那張圖的階級，劇情 Boss = 1），有機率 +1（星圖完成越多機率越高），
 *       最高到「掉落上限」（一開始 T4，星圖每完成一張不同的地圖高一階）。</li>
 *   <li>哪一張 = 玩家已經解鎖的地圖（跟 PoE 一樣不會掉廢圖）；解鎖了還沒完成的（星圖的前線）比較常掉。</li>
 *   <li>稀有度：階級越高越常是魔法、稀有（詞綴越多）；物品等級 = 那一階的怪物等級（高階的地圖詞綴比較兇）。</li>
 * </ul>
 */
public final class MapDrops {

    private MapDrops() {
    }

    /**
     * 擲一張地圖。
     *
     * @param baseTier  這次掉落的基本階級（地圖怪 = 那張圖的階級，劇情 Boss = 1）
     * @param completed 撿到的人完成過的地圖（null = 不看星圖：任何地圖都可能，沒有掉落上限）
     * @return 地圖物品；沒有任何地圖可以掉時回傳 null
     */
    public static ItemData roll(final MapConfig config, final ItemGenerator generator, final int baseTier, final Set<String> completed,
                                final RandomGenerator random) {
        return roll(config, generator, baseTier, completed, random, 0);
    }

    /**
     * 同上，高一階的機率再加上 tierUpBonus（星圖天賦，小數；還是不超過掉落上限）。
     */
    public static ItemData roll(final MapConfig config, final ItemGenerator generator, final int baseTier, final Set<String> completed,
                                final RandomGenerator random, final double tierUpBonus) {
        if (config.maps().isEmpty()) {
            return null;
        }
        final int done = completed == null ? 0 : Atlas.completedMaps(config, completed);
        final int cap = completed == null ? config.tiers().max() : Atlas.dropTierCap(config, done);
        int tier = Math.max(1, Math.min(cap, baseTier));
        if (tier < cap && random.nextDouble() < Math.min(1.0, Atlas.tierUpChance(config, done) + Math.max(0, tierUpBonus))) {
            tier++;
        }
        final List<MapConfig.MapDef> options = new ArrayList<>();
        final List<Integer> weights = new ArrayList<>();
        int total = 0;
        for (final MapConfig.MapDef map : config.maps()) {
            if (completed == null || Atlas.unlocked(config, map, completed)) {
                final int weight = completed != null && !completed.contains(map.id()) ? config.drops().frontierWeight() : 1;
                options.add(map);
                weights.add(weight);
                total += weight;
            }
        }
        if (options.isEmpty() || total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        MapConfig.MapDef chosen = options.getLast();
        for (int i = 0; i < options.size(); i++) {
            pick -= weights.get(i);
            if (pick < 0) {
                chosen = options.get(i);
                break;
            }
        }
        return create(config, generator, chosen, rollRarity(config, tier, random), tier, random);
    }

    /** 依 maps.yml 的 drops.rarity（T1）與 drops.rarity_top（最高階）照階級內插的權重擲稀有度。 */
    static Rarity rollRarity(final MapConfig config, final int tier, final RandomGenerator random) {
        final Map<Rarity, Double> weights = rarityWeights(config, tier);
        double total = 0;
        for (final double weight : weights.values()) {
            total += weight;
        }
        if (total <= 0) {
            return Rarity.NORMAL;
        }
        double pick = random.nextDouble() * total;
        for (final Rarity rarity : new Rarity[] {Rarity.NORMAL, Rarity.MAGIC, Rarity.RARE}) {
            pick -= weights.getOrDefault(rarity, 0.0);
            if (pick < 0) {
                return rarity;
            }
        }
        return Rarity.RARE;
    }

    /** 某一階掉落時的稀有度權重（T1 照 rarity，最高階照 rarity_top，中間線性內插）。 */
    public static Map<Rarity, Double> rarityWeights(final MapConfig config, final int tier) {
        final int max = config.tiers().max();
        final double t = max <= 1 ? 0 : (config.tiers().clamp(tier) - 1) / (double) (max - 1);
        final Map<Rarity, Double> weights = new EnumMap<>(Rarity.class);
        for (final Rarity rarity : new Rarity[] {Rarity.NORMAL, Rarity.MAGIC, Rarity.RARE}) {
            final double low = config.drops().rarity().getOrDefault(rarity, 0);
            final double high = config.drops().rarityTop().getOrDefault(rarity, 0);
            weights.put(rarity, Math.max(0, low + (high - low) * t));
        }
        return weights;
    }

    /** 指定一張地圖、稀有度、階級（掉落、管理指令、測試用）：物品等級 = 那一階的怪物等級，已鑑定。 */
    public static ItemData create(final MapConfig config, final ItemGenerator generator, final MapConfig.MapDef map, final Rarity rarity,
                                  final int tier, final RandomGenerator random) {
        final ItemBase base = generator.repository().base(map.base());
        if (base == null) {
            return null;
        }
        final int clamped = config.tiers().clamp(tier);
        final ItemData data = generator.generate(base, rarity, config.tiers().level(clamped), random);
        data.setMapTier(clamped);
        data.setIdentified(true);
        return data;
    }

    /** 從一個來源（final、map_boss、地圖代號）抽一件傳奇；沒有回傳 null。 */
    public static ItemData unique(final ItemGenerator generator, final String source, final int itemLevel, final RandomGenerator random) {
        final UniqueItem unique = ItemGenerator.pickUnique(generator.repository().uniquesFor(source, itemLevel), random);
        return unique == null ? null : generator.generateUnique(unique, itemLevel, random);
    }

    /** 「2026-10-02|3」→ 今天的第幾次；不是今天（或寫壞）就是 0。 */
    static int dailyCount(final String value, final String today) {
        if (value == null) {
            return 0;
        }
        final int bar = value.indexOf('|');
        if (bar <= 0 || !value.substring(0, bar).equals(today)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.substring(bar + 1).trim()));
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
