package tw.exilecore.map;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;
import tw.exilecore.monster.DropTable;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 地圖機制的獎勵（祭壇、幻霧共用）：照種類擲一份獎勵、交給玩家。
 * 獎勵種類見 {@link MapEventConfig.Reward}；傳奇從地圖 Boss 的池子抽、地圖照開圖的人的星圖、蒸餾情緒照階級權重。
 */
public final class MapRewards {

    /** 一份獎勵：種類與做好的物品（通貨可能好幾顆疊在一起）。 */
    record Bundle(MapEventConfig.Reward kind, List<ItemStack> items) {
        Bundle {
            items = List.copyOf(items);
        }

        /** 顯示用：第一件（通貨帶數量）。 */
        ItemStack display() {
            return items.getFirst().clone();
        }
    }

    private MapRewards() {
    }

    /** 依權重抽一種獎勵；權重都是 0 回傳 null。 */
    static MapEventConfig.Reward pick(final Map<MapEventConfig.Reward, Integer> weights, final RandomGenerator random) {
        int total = 0;
        for (final int weight : weights.values()) {
            total += Math.max(0, weight);
        }
        if (total <= 0) {
            return null;
        }
        int pick = random.nextInt(total);
        for (final Map.Entry<MapEventConfig.Reward, Integer> entry : weights.entrySet()) {
            pick -= Math.max(0, entry.getValue());
            if (pick < 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 隨機一種這類操作的通貨（掉落等級夠的）；沒有回傳 null。 */
    static CurrencyType pickCurrency(final ExileCore plugin, final CurrencyOperation operation, final int level, final RandomGenerator random) {
        final List<CurrencyType> options = new ArrayList<>();
        int total = 0;
        for (final CurrencyType type : plugin.currencies().repository().all()) {
            if (type.operation() == operation && type.dropLevel() <= level && weight(type) > 0) {
                options.add(type);
                total += weight(type);
            }
        }
        if (options.isEmpty()) {
            return null;
        }
        // 2026-10：星籤照 omen 的 weight 抽（去劣、保前後綴比較少）；其他（催化劑…）一樣機會
        int pick = random.nextInt(total);
        for (final CurrencyType type : options) {
            pick -= weight(type);
            if (pick < 0) {
                return type;
            }
        }
        return options.getLast();
    }

    private static int weight(final CurrencyType type) {
        return type.pickWeight(); // 2026-10-26：星籤照 omen.weight、星魄照階級（次級多、完美少）；以前星籤以外都是 1
    }

    /**
     * 擲一份獎勵。擲不出來（例如沒有能掉的傳奇）會退而求其次（稀有裝備）；什麼都做不出來回傳 null。
     *
     * @param instance 這張圖（等級與階級）
     * @param player   拿獎勵的人（地圖照他的星圖）
     * @param delirium 蒸餾情緒的階級與權重（EMOTION 用）
     * @param uniques  傳奇（UNIQUE）從哪個來源抽：祭壇 ritual、幻霧 delirium（沒有的話用地圖 Boss 的共通池）
     */
    static Bundle roll(final ExileCore plugin, final MapService service, final MapEventConfig.Reward kind, final MapInstance instance,
                       final Player player, final MapEventConfig.Delirium delirium, final String uniques, final RandomGenerator random) {
        final ItemGenerator generator = plugin.items().generator();
        final int level = instance.level();
        switch (kind) {
            case OMEN -> {
                final CurrencyType omen = pickCurrency(plugin, CurrencyOperation.OMEN, level, random);
                return omen == null ? roll(plugin, service, MapEventConfig.Reward.CURRENCY, instance, player, delirium, uniques, random)
                        : new Bundle(kind, List.of(plugin.currencies().create(omen, 1)));
            }
            case ESSENCE -> {
                final CurrencyType essence = pickCurrency(plugin, CurrencyOperation.ESSENCE, level, random);
                return essence == null ? roll(plugin, service, MapEventConfig.Reward.CURRENCY, instance, player, delirium, uniques, random)
                        : new Bundle(kind, List.of(plugin.currencies().create(essence, random.nextDouble() < 0.5 ? 2 : 1)));
            }
            case CATALYST -> {
                final CurrencyType catalyst = pickCurrency(plugin, CurrencyOperation.CATALYST, level, random);
                return catalyst == null ? roll(plugin, service, MapEventConfig.Reward.CURRENCY, instance, player, delirium, uniques, random)
                        : new Bundle(kind, List.of(plugin.currencies().create(catalyst, 1)));
            }
            case EMOTION -> {
                // 2026-10-27 照 PoE2：情緒有掉落等級（恐懼 69、苦難 73、孤寂 77），地圖等級不夠的那幾種不抽
                final String id = delirium == null ? null
                        : delirium.emotion(pickTier(tw.exilecore.currency.Crafting.emotionWeightsAt(delirium.emotions(), delirium.emotionWeights(),
                                level, plugin.currencies().repository()::get), random));
                final CurrencyType emotion = id == null ? null : plugin.currencies().repository().get(id);
                return emotion == null ? roll(plugin, service, MapEventConfig.Reward.CURRENCY, instance, player, delirium, uniques, random)
                        : new Bundle(kind, List.of(plugin.currencies().create(emotion, 1)));
            }
            case CURRENCY -> {
                final CurrencyType currency = plugin.monsterModule().drops().rollCurrency(level, plugin.currencies().repository(), random);
                if (currency == null) {
                    return null;
                }
                // 常見的多給幾顆（掉落權重越高越多）
                final int amount = currency.dropWeight() >= 5000 ? 3 + random.nextInt(3) : currency.dropWeight() >= 1000 ? 2 + random.nextInt(2) : 1;
                return new Bundle(kind, List.of(plugin.currencies().create(currency, amount)));
            }
            case RARE -> {
                // 2026-10：照分類（family）抽、照職業調整（傳奇不在這裡）
                final ItemBase base = plugin.items().repository().pickSold(level, random, plugin.characters().lootBias(player));
                if (base == null) {
                    return null;
                }
                return new Bundle(kind, List.of(plugin.items().toStack(generator.generate(base, Rarity.RARE, level, random))));
            }
            case JEWEL -> {
                final List<ItemBase> jewels = new ArrayList<>();
                for (final ItemBase base : plugin.items().repository().basesDroppableAt(Math.max(level, 20))) {
                    if (base.isJewel()) {
                        jewels.add(base);
                    }
                }
                if (jewels.isEmpty()) {
                    return roll(plugin, service, MapEventConfig.Reward.RARE, instance, player, delirium, uniques, random);
                }
                final ItemBase base = jewels.get(random.nextInt(jewels.size()));
                return new Bundle(kind, List.of(plugin.items().toStack(generator.generate(base, random.nextBoolean() ? Rarity.RARE : Rarity.MAGIC, level, random))));
            }
            case MAP -> {
                final ItemData map = MapDrops.roll(service.config(), generator, Math.max(1, instance.stats().tier()), service.completed(player), random,
                        instance.atlas().tierUp());
                return map == null ? roll(plugin, service, MapEventConfig.Reward.CURRENCY, instance, player, delirium, uniques, random)
                        : new Bundle(kind, List.of(plugin.items().toStack(map)));
            }
            case UNIQUE -> {
                // 祭壇、幻霧各有自己的專屬傳奇（uniques.yml 的 sources 寫 ritual、delirium）；沒有的話用地圖 Boss 的共通池
                List<UniqueItem> pool = uniques == null ? List.of() : plugin.items().repository().uniquesFor(uniques, level);
                if (pool.isEmpty()) {
                    pool = plugin.items().repository().uniquesFor("map_boss", level);
                }
                final UniqueItem unique = ItemGenerator.pickUnique(pool, random);
                if (unique == null) {
                    return roll(plugin, service, MapEventConfig.Reward.RARE, instance, player, delirium, uniques, random);
                }
                return new Bundle(kind, List.of(plugin.items().toStack(generator.generateUnique(unique, level, random))));
            }
            default -> {
                return null;
            }
        }
    }

    /** 照權重抽第幾階（1 起）。 */
    static int pickTier(final List<Integer> weights, final RandomGenerator random) {
        int total = 0;
        for (final int weight : weights) {
            total += Math.max(0, weight);
        }
        if (total <= 0) {
            return 1;
        }
        int pick = random.nextInt(total);
        for (int i = 0; i < weights.size(); i++) {
            pick -= Math.max(0, weights.get(i));
            if (pick < 0) {
                return i + 1;
            }
        }
        return weights.size();
    }

    /** 這種獎勵在祭壇要幾貢品（價格範圍裡隨機）。 */
    static int price(final MapEventConfig.Ritual ritual, final MapEventConfig.Reward kind, final RandomGenerator random) {
        final int[] range = ritual.prices().getOrDefault(kind, new int[] {100, 200});
        return range[0] + (range[1] > range[0] ? random.nextInt(range[1] - range[0] + 1) : 0);
    }

    /** 把一份掉落交給玩家（撿取、過濾、金幣照殺怪的規則）；撿取模組沒啟用就掉在地上。 */
    public static void deliver(final ExileCore plugin, final Player player, final Location at, final MonsterData data, final List<DropTable.Drop> all,
                               final long allGold) {
        // 2026-10-18：隱藏的個人掉落倍率（地圖事件的寶箱、流寇、裂隙、劫盜都從這裡交）
        final List<DropTable.Drop> rolled = new ArrayList<>(tw.exilecore.loot.LootPenalty.thin(player, all,
                java.util.concurrent.ThreadLocalRandom.current()));
        final long gold = tw.exilecore.loot.LootPenalty.gold(player, allGold);
        if (plugin.loot() != null && plugin.loot().handleKill(player, at, data, rolled, gold, drop -> MonsterListener.toStack(plugin, drop))) {
            return;
        }
        if (gold > 0) {
            plugin.gold().drop(at, gold, player);
        }
        for (final DropTable.Drop drop : rolled) {
            final ItemStack stack = MonsterListener.toStack(plugin, drop);
            if (stack != null) {
                MonsterListener.dropOwned(plugin, at, stack, player, MonsterListener.highlight(drop));
            }
        }
    }

    /** 把做好的物品直接給玩家（通貨先進通貨袋；其他先背包、再隨身背包，都滿了掉在腳下）；不經過掉落過濾（是他自己選的）。回傳有沒有掉在地上。 */
    public static boolean give(final ExileCore plugin, final Player player, final List<ItemStack> items) { // 2026-10-30：劫盜的大金庫也用
        boolean dropped = false;
        for (final ItemStack item : items) {
            final java.util.Optional<tw.exilecore.currency.CurrencyType> currency = plugin.currencies() == null
                    ? java.util.Optional.empty() : plugin.currencies().read(item);
            // 流水帳（2026-10）：祭壇、幻霧挑的通貨也算產出（以前沒記，星籤這類主要從這裡來的就變成「消耗比產出多」）
            if (plugin.economy() != null) {
                currency.ifPresent(type -> plugin.economy().ledger().produced(type.id(),
                        tw.exilecore.economy.EconomyLedger.EVENT, item.getAmount()));
            }
            ItemStack rest = item;
            if (plugin.loot() != null) {
                // 2026-10-16：通貨先進通貨袋（跟殺怪撿到的一樣），滿了才放背包
                rest = currency.isPresent()
                        ? plugin.loot().stowCurrency(player, plugin.players().get(player), currency.get(), item)
                        : plugin.loot().stow(player, plugin.players().get(player), item);
            } else {
                final Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                rest = leftover.isEmpty() ? null : leftover.values().iterator().next();
            }
            if (rest != null && !rest.isEmpty()) {
                plugin.personalDrops().dropForever(player, rest); // 2026-10-18：只有他撿得到、不會自己消失（以前沒歸屬，隊友撿得走）
                dropped = true;
            }
        }
        return dropped;
    }
}
