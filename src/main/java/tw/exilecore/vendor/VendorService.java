package tw.exilecore.vendor;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.economy.EconomyConfig;
import tw.exilecore.economy.EconomyLedger;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.economy.Wallet;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemGenerator;
import tw.exilecore.item.Rarity;
import tw.exilecore.item.UniqueItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 商人（PoE2）：賣你這個等級用得到的裝備（每個角色自己一批，定時或升級時換），以及賭博（花金幣買未鑑定的某種基底）。
 * 賣東西給商人就是拆解師（金幣＋品質通貨）。
 */
public final class VendorService {

    /** 傳奇的掉落來源：可以賭出來（uniques.yml 的 sources 寫 gamble）。 */
    public static final String GAMBLE_SOURCE = "gamble";
    /** 地圖 Boss 的共通傳奇池（uniques.yml 的 sources）：賭得到的話用 economy.yml 的 boss_unique 權重。 */
    public static final String BOSS_SOURCE = "map_boss";

    /** 一個角色目前的貨。 */
    static final class Stock {
        final long characterId;
        final int level;
        final long generatedAt;
        final List<ItemStack> items;

        Stock(final long characterId, final int level, final long generatedAt, final List<ItemStack> items) {
            this.characterId = characterId;
            this.level = level;
            this.generatedAt = generatedAt;
            this.items = items;
        }
    }

    private final ExileCore plugin;
    private final Map<UUID, Stock> stocks = new HashMap<>();

    public VendorService(final ExileCore plugin) {
        this.plugin = plugin;
    }

    EconomyConfig config() {
        return plugin.economy().config();
    }

    // ---- 商人的貨 ----

    /** 這個角色目前的貨（沒有、換角色、升級、到時間了就換一批）。 */
    Stock stock(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final long characterId = state.hasCharacter() ? state.character().id() : -1;
        final Stock current = stocks.get(player.getUniqueId());
        final long now = System.currentTimeMillis();
        if (current == null || current.characterId != characterId || current.level != state.level()
                || now - current.generatedAt >= config().vendorRefreshMillis()) {
            final Stock fresh = new Stock(characterId, state.level(), now, generate(player, state.level()));
            stocks.put(player.getUniqueId(), fresh);
            return fresh;
        }
        return current;
    }

    /** 下次自動換貨還要多久（毫秒）。 */
    long nextRefresh(final Stock stock) {
        return Math.max(0, stock.generatedAt + config().vendorRefreshMillis() - System.currentTimeMillis());
    }

    /**
     * 一批貨（2026-10）：基底照分類（family）抽、照職業調整（跟掉落一樣，見 {@link tw.exilecore.item.LootBias}），
     * 排好：照裝備欄位（武器、副手、頭、身、腿、腳、戒指、項鍊、腰帶），同欄位稀有、魔法、普通。
     */
    private List<ItemStack> generate(final Player player, final int level) {
        final List<ItemStack> items = new ArrayList<>();
        final tw.exilecore.item.ItemRepository repository = plugin.items().repository();
        final List<ItemBase> pool = new ArrayList<>(repository.basesSoldAt(Math.max(1, level)));
        if (pool.isEmpty()) {
            return items;
        }
        final tw.exilecore.item.LootBias bias = plugin.characters() == null ? tw.exilecore.item.LootBias.NONE : plugin.characters().lootBias(player);
        final RandomGenerator random = plugin.items().random();
        final List<java.util.Map.Entry<ItemBase, ItemStack>> rolled = new ArrayList<>();
        for (final Rarity rarity : new Rarity[] {Rarity.RARE, Rarity.MAGIC, Rarity.NORMAL}) {
            for (int i = 0; i < config().stock(rarity); i++) {
                final ItemBase base = tw.exilecore.item.ItemRepository.pickByFamily(pool, random, bias);
                if (base != null) {
                    rolled.add(java.util.Map.entry(base, plugin.items().create(base, rarity, level))); // 生成出來就是已鑑定的
                }
            }
        }
        rolled.sort(Comparator.comparingInt((java.util.Map.Entry<ItemBase, ItemStack> entry) -> entry.getKey().slot().ordinal())); // 穩定排序：同欄位維持稀有→普通
        for (final var entry : rolled) {
            items.add(entry.getValue());
        }
        return items;
    }

    /** 商人的分類（2026-10）：購買、賭博分頁都能只看某一類。 */
    enum Category {
        ALL("全部"), WEAPON("武器"), OFF_HAND("副手（盾、箭袋、法器）"), HEAD("頭部"), CHEST("身體"), LEGS("腿部"), FEET("腳部"),
        JEWELLERY("飾品（戒指、項鍊、腰帶）");

        final String label;

        Category(final String label) {
            this.label = label;
        }

        boolean matches(final tw.exilecore.item.ItemSlot slot) {
            return switch (this) {
                case ALL -> true;
                case WEAPON -> slot == tw.exilecore.item.ItemSlot.MAIN_HAND;
                case OFF_HAND -> slot == tw.exilecore.item.ItemSlot.OFF_HAND;
                case HEAD -> slot == tw.exilecore.item.ItemSlot.HEAD;
                case CHEST -> slot == tw.exilecore.item.ItemSlot.CHEST;
                case LEGS -> slot == tw.exilecore.item.ItemSlot.LEGS;
                case FEET -> slot == tw.exilecore.item.ItemSlot.FEET;
                case JEWELLERY -> slot == tw.exilecore.item.ItemSlot.RING || slot == tw.exilecore.item.ItemSlot.AMULET
                        || slot == tw.exilecore.item.ItemSlot.BELT;
            };
        }

        Category step(final int direction) {
            final Category[] all = values();
            return all[Math.floorMod(ordinal() + direction, all.length)];
        }

        static Category parse(final String text) {
            for (final Category category : values()) {
                if (category.name().equalsIgnoreCase(text)) {
                    return category;
                }
            }
            return ALL;
        }
    }

    /** 這一件商品的裝備欄位（讀不到回傳 null）。 */
    tw.exilecore.item.ItemSlot slotOf(final ItemStack stack) {
        return plugin.items().read(stack).map(data -> plugin.items().repository().base(data.base()))
                .map(ItemBase::slot).orElse(null);
    }

    /**
     * 賭博列的基底（2026-10 第二版）：這個等級買得到的全部（同一類只差數值的已經合併成一個，留下來的都是不同的變化），
     * 依欄位、類別排好（同一類排在一起）。
     */
    List<ItemBase> bases(final int level) {
        final List<ItemBase> list = new ArrayList<>(plugin.items().repository().basesSoldAt(Math.max(1, level)));
        // 同欄位裡同一類（family，照資料檔的順序）排在一起；同一類裡：沒有固定詞綴的在前、傷害種類少的在前、再照暴擊、攻速
        final java.util.Map<String, Integer> familyOrder = new java.util.HashMap<>();
        for (final ItemBase base : list) {
            familyOrder.putIfAbsent(base.family(), familyOrder.size());
        }
        list.sort(Comparator.comparing((ItemBase base) -> base.slot().ordinal())
                .thenComparingInt(base -> familyOrder.get(base.family()))
                .thenComparing(base -> base.implicit() != null)
                .thenComparingInt(base -> base.damage().size())
                .thenComparingDouble(ItemBase::crit).thenComparingDouble(ItemBase::aps)
                .thenComparingDouble(base -> base.armour() + base.evasion() + base.energyShield())
                .thenComparing(ItemBase::id));
        return list;
    }

    /** 商人賣的一件的價格。 */
    long price(final ItemStack stack) {
        final ItemData data = plugin.items().read(stack).orElse(null);
        return data == null ? 0 : config().vendorPrice(data.rarity(), data.ilvl());
    }

    /**
     * 買 shown 這一批的第 index 件；null = 成功。
     * shown 是畫面上看到的那一批：已經換了一批（時間到、升級）就不買，免得買到沒看過的東西。
     */
    String buy(final Player player, final Stock shown, final int index) {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            return problem;
        }
        final Stock stock = stock(player);
        if (stock != shown) {
            return "商人剛換了一批貨，再看一下";
        }
        final List<ItemStack> list = stock.items;
        if (index < 0 || index >= list.size()) {
            return "這件已經賣掉了";
        }
        final ItemStack item = list.get(index);
        final long price = price(item);
        if (plugin.gold().balance(player) < price) {
            return String.format(Locale.ROOT, "金幣不夠：要 %,d", price);
        }
        if (!wallet().fits(player, item)) {
            return "背包和隨身背包都放不下";
        }
        plugin.gold().take(player, price, EconomyLedger.VENDOR);
        list.remove(index);
        deliver(player, item, "向商人購買", price);
        return null;
    }

    /** 花金幣馬上換一批；null = 成功。 */
    String refresh(final Player player) {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            return problem;
        }
        final PlayerState state = plugin.players().get(player);
        final long cost = config().vendorRefreshCost(state.level());
        if (!plugin.gold().take(player, cost, EconomyLedger.VENDOR_REFRESH)) {
            return String.format(Locale.ROOT, "金幣不夠：換一批要 %,d", cost);
        }
        stocks.put(player.getUniqueId(), new Stock(state.character().id(), state.level(), System.currentTimeMillis(), generate(player, state.level())));
        plugin.characters().save(player);
        return null;
    }

    // ---- 賭博 ----

    /** 這個基底能賭出來的傳奇（物品等級要夠）。 */
    /** 這個基底賭得到的追逐級傳奇（不看等級：等級不夠的選單上寫幾級起）。 */
    List<UniqueItem> chaseUniques(final ItemBase base) {
        return plugin.items().generator().chaseUniques(base.id(), Integer.MAX_VALUE);
    }

    List<UniqueItem> gambleUniques(final ItemBase base, final int itemLevel) {
        final List<UniqueItem> list = new ArrayList<>();
        for (final UniqueItem unique : plugin.items().repository().uniques()) {
            if (unique.base().equals(base.id()) && unique.droppedBy(GAMBLE_SOURCE) && unique.dropLevel() <= itemLevel && unique.weight() > 0) {
                list.add(unique);
            }
        }
        return list;
    }

    /**
     * 這個基底的傳奇權重：能賭到的傳奇裡有地圖 Boss 也會掉的 → boss_unique（照 Boss 的 5% 校準，比較低），
     * 只有能賭的（劇情期間用的）→ unique；沒有 → 0。
     */
    double uniqueWeight(final List<UniqueItem> uniques) {
        return config().gambleUniqueWeight(!uniques.isEmpty(), bossPool(uniques));
    }

    /** 能賭到的傳奇裡有地圖 Boss 也會掉的（用 boss_unique 的權重）。 */
    static boolean bossPool(final List<UniqueItem> uniques) {
        for (final UniqueItem unique : uniques) {
            if (unique.droppedBy(BOSS_SOURCE)) {
                return true;
            }
        }
        return false;
    }

    /** 賭得到傳奇的最低物品等級（＝角色等級；傳奇與它的基底都要到等級）；沒有能賭的傳奇回傳 0。 */
    int lowestGambleUniqueLevel() {
        int lowest = 0;
        for (final UniqueItem unique : plugin.items().repository().uniques()) {
            if (!unique.droppedBy(GAMBLE_SOURCE) || unique.weight() <= 0) {
                continue;
            }
            final ItemBase base = plugin.items().repository().base(unique.base());
            if (base == null || !base.isWearable()) {
                continue; // 商人不賣地圖、珠寶的基底
            }
            final int level = Math.max(unique.dropLevel(), base.dropLevel());
            if (lowest == 0 || level < lowest) {
                lowest = level;
            }
        }
        return lowest;
    }

    /** 賭一件 base；null = 成功（買到的東西會說是什麼）。 */
    String gamble(final Player player, final ItemBase base) {
        return gamble(player, base, -1, 0);
    }

    /**
     * slot：放進背包的哪一格（2026-10，Shift＋左鍵連續賭只放背包上面三排；-1 = 照一般的放法）。
     * itemLevel：賭出來的物品等級（0 或超過角色等級 = 照角色等級；選單一律照角色等級）。
     */
    String gamble(final Player player, final ItemBase base, final int slot, final int itemLevel) {
        return gamble(player, base, slot, itemLevel, null);
    }

    /**
     * 賭一件。batch 不是 null 時不存檔，只把紀錄放進去（Shift 一次賭一排：最後呼叫 {@link #commitBatch} 一次存完；
     * 2026-10：以前每一件都整個角色＋倉庫存一次檔，賭一排就卡）。
     */
    String gamble(final Player player, final ItemBase base, final int slot, final int itemLevel, final List<TradeLogRepository.Entry> batch) {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            return problem;
        }
        final PlayerState state = plugin.players().get(player);
        final int level = itemLevel <= 0 ? state.level() : Math.max(1, Math.min(state.level(), itemLevel));
        if (base.dropLevel() > level || !base.isWearable() || plugin.items().repository().retiredBase(base.id())) {
            return "這個等級還賭不到這種基底";
        }
        final long price = config().gamblePrice(level);
        if (plugin.gold().balance(player) < price) {
            return String.format(Locale.ROOT, "金幣不夠：賭一件要 %,d", price);
        }
        final RandomGenerator random = plugin.items().random();
        final List<UniqueItem> uniques = gambleUniques(base, level);
        // 追逐級傳奇（2026-10）：先擲一次跟小怪掉落、僥倖石一樣的極低機率（這個基底、這個等級有追逐級的才擲）
        final List<UniqueItem> chase = plugin.items().generator().chaseUniques(base.id(), level);
        final boolean chaseHit = !chase.isEmpty() && random.nextDouble() < ItemGenerator.chaseChance();
        final Rarity rarity = chaseHit ? Rarity.UNIQUE : config().rollGamble(random, uniqueWeight(uniques));
        final ItemData data;
        if (chaseHit) {
            data = plugin.items().generator().generateUnique(ItemGenerator.pickUnique(chase, random), level, random);
        } else if (rarity == Rarity.UNIQUE) {
            data = plugin.items().generator().generateUnique(ItemGenerator.pickUnique(uniques, random), level, random);
        } else {
            data = plugin.items().generator().generate(base, rarity, level, random);
        }
        data.setIdentified(false); // 照 PoE2：賭到的是未鑑定的，拿去鑑定師才知道是什麼
        final ItemStack item = plugin.items().toStack(data);
        if (!wallet().fits(player, item)) {
            return "背包和隨身背包都放不下"; // 還沒收錢：骰出來的這件就當沒發生
        }
        plugin.gold().take(player, price, EconomyLedger.GAMBLE);
        final ItemStack there = slot < 0 ? null : player.getInventory().getItem(slot);
        if (slot >= 0 && (there == null || there.isEmpty())) {
            player.getInventory().setItem(slot, item);
        } else {
            wallet().dropFor(player, wallet().give(player, item));
        }
        if (batch != null) {
            batch.add(entry(player, item, "賭博", price));
        } else {
            log(player, item, "賭博", price);
        }
        if (chaseHit && plugin.loot() != null) {
            plugin.loot().broadcastChase(player, data); // 追逐級：全服廣播
        }
        return null;
    }

    // ---- 補給（金幣商店） ----

    /** 補給賣的東西。 */
    enum SupplyKind { LIFE_FLASK, MANA_FLASK, UNCUT_SKILL, UNCUT_SUPPORT }

    /**
     * 補給的一格：要不要賣、為什麼不賣。
     *
     * @param item   東西（數量 1；鎖住的也有，畫成灰色用）
     * @param price  一個的價格
     * @param locked 不能買的原因（null = 能買）
     */
    record Supply(SupplyKind kind, int level, ItemStack item, long price, String locked) {
    }

    EconomyConfig.Shop shop() {
        return config().shop();
    }

    /** 補給：這個角色現在解鎖到哪裡（開頭的 start ＋ 打倒過 Boss 的區域，取最大）。 */
    EconomyConfig.Unlock shopUnlock(final Player player) {
        int skill = 0;
        int support = 0;
        for (final Map.Entry<String, EconomyConfig.Unlock> entry : shop().unlocks().entrySet()) {
            if (unlocked(player, entry.getKey())) {
                skill = Math.max(skill, entry.getValue().skillLevel());
                support = Math.max(support, entry.getValue().supportTier());
            }
        }
        return new EconomyConfig.Unlock(skill, support);
    }

    /** start 一開始就有；其他要打倒那個區域的 Boss。 */
    private boolean unlocked(final Player player, final String key) {
        if (EconomyConfig.UNLOCK_START.equals(key)) {
            return true;
        }
        if (plugin.zones() == null) {
            return false;
        }
        return plugin.zones().zone(key).map(zone -> plugin.zones().bossDefeated(player, zone)).orElse(false);
    }

    /** 要怎樣才解鎖：第一個能賣到這個等級（階級）、還沒打倒 Boss 的區域。 */
    private String unlockHint(final Player player, final boolean skill, final int level) {
        for (final Map.Entry<String, EconomyConfig.Unlock> entry : shop().unlocks().entrySet()) {
            final int reach = skill ? entry.getValue().skillLevel() : entry.getValue().supportTier();
            if (reach >= level && !unlocked(player, entry.getKey())) {
                final String zone = plugin.zones() == null ? entry.getKey()
                        : plugin.zones().zone(entry.getKey()).map(tw.exilecore.zone.Zone::name).orElse(entry.getKey());
                return "打倒「" + zone + "」的區域 Boss 解鎖";
            }
        }
        return "補給不賣";
    }

    /** 這個等級的參考金幣：同等級普通怪平均掉的金幣（至少 1）。 */
    private double goldAt(final int level) {
        return Math.max(1, plugin.monsterModule().drops().averageGold(Math.max(1, level)));
    }

    private static long price(final double kills, final double gold) {
        return Math.max(1, Math.round(kills * gold));
    }

    /** 補給畫面上的全部格子（藥劑、技能寶石 1–最高、輔助寶石每一階）。 */
    List<Supply> supplies(final Player player) {
        final List<Supply> list = new ArrayList<>();
        final PlayerState state = plugin.players().get(player);
        final int level = state.level();
        final EconomyConfig.Shop shop = shop();
        if (plugin.flasks() != null) {
            for (final SupplyKind kind : new SupplyKind[] {SupplyKind.LIFE_FLASK, SupplyKind.MANA_FLASK}) {
                final tw.exilecore.flask.FlaskBase base = bestFlask(kind, level);
                if (base != null) {
                    list.add(new Supply(kind, base.level(), plugin.flasks().create(base, base.charges()),
                            price(shop.flaskKills(), goldAt(base.level())), null));
                }
            }
        }
        if (plugin.skills() != null) {
            final tw.exilecore.skill.SkillConfig skills = plugin.skills().config();
            final EconomyConfig.Unlock unlock = shopUnlock(player);
            final int usable = skills.maxLevelFor(level);
            for (int gem = 1; gem <= shop.skillMaxLevel(); gem++) {
                final String locked = gem > unlock.skillLevel() ? unlockHint(player, true, gem)
                        : gem > usable ? "需要角色等級 " + skills.requiredLevel(gem) : null;
                list.add(new Supply(SupplyKind.UNCUT_SKILL, gem,
                        plugin.skills().gems().create(tw.exilecore.skill.GemData.uncutSkill(gem), 1),
                        price(shop.skillKills(), goldAt(skills.requiredLevel(gem))), locked));
            }
            for (final Map.Entry<Integer, Double> entry : shop.supportKills().entrySet()) {
                final int tier = entry.getKey();
                if (tier > tw.exilecore.skill.SupportDefinition.MAX_TIER) {
                    continue;
                }
                final String locked = tier > unlock.supportTier() ? unlockHint(player, false, tier) : null;
                list.add(new Supply(SupplyKind.UNCUT_SUPPORT, tier,
                        plugin.skills().gems().create(tw.exilecore.skill.GemData.uncutSupport(tier), 1),
                        price(entry.getValue(), goldAt(skills.supportTierLevel(tier))), locked));
            }
        }
        return list;
    }

    /** 這個等級用得到的最好那一種藥劑（生命或魔力）；沒有回傳 null。 */
    private tw.exilecore.flask.FlaskBase bestFlask(final SupplyKind kind, final int level) {
        final tw.exilecore.flask.FlaskKind want = kind == SupplyKind.LIFE_FLASK ? tw.exilecore.flask.FlaskKind.LIFE : tw.exilecore.flask.FlaskKind.MANA;
        tw.exilecore.flask.FlaskBase best = null;
        for (final tw.exilecore.flask.FlaskBase base : plugin.flasks().config().bases()) {
            if (base.kind() == want && base.level() <= Math.max(1, level) && (best == null || base.level() > best.level())) {
                best = base;
            }
        }
        return best;
    }

    /**
     * 買補給：kind、level 是畫面上那一格（買的時候照現在的進度與等級再算一次）；amount 個。null = 成功。
     */
    String buySupply(final Player player, final SupplyKind kind, final int level, final int amount) {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            return problem;
        }
        if (!shop().enabled()) {
            return "補給沒有開（economy.yml 的 shop.enabled）";
        }
        Supply supply = null;
        for (final Supply candidate : supplies(player)) {
            if (candidate.kind() == kind && candidate.level() == level) {
                supply = candidate;
                break;
            }
        }
        if (supply == null) {
            return kind == SupplyKind.LIFE_FLASK || kind == SupplyKind.MANA_FLASK ? "補給的藥劑換了（你升級了），再看一下" : "補給沒有賣這個";
        }
        if (supply.locked() != null) {
            return supply.locked();
        }
        final int count = kind == SupplyKind.LIFE_FLASK || kind == SupplyKind.MANA_FLASK ? 1
                : Math.max(1, Math.min(supply.item().getMaxStackSize(), amount));
        final long total = supply.price() * count;
        if (plugin.gold().balance(player) < total) {
            return String.format(Locale.ROOT, "金幣不夠：要 %,d", total);
        }
        final ItemStack item = supply.item().asQuantity(count);
        // 藥劑：那一種的藥劑欄空著就直接放進去（跟撿到一樣），不用背包有空位
        final boolean toFlaskSlot = count == 1 && plugin.flasks() != null && plugin.flasks().canEquip(player, item);
        if (!toFlaskSlot && !wallet().fits(player, item)) {
            return "背包和隨身背包都放不下";
        }
        plugin.gold().take(player, total, EconomyLedger.SHOP);
        if (plugin.currencies() != null) {
            // 未切割寶石算通貨（2026-10）：商人賣出的也記進流水帳的產出
            plugin.currencies().read(item).ifPresent(type -> plugin.economy().ledger().produced(type.id(), EconomyLedger.SHOP, count));
        }
        if (toFlaskSlot && plugin.flasks().equipIfEmpty(player, item)) {
            log(player, item, "補給", total);
            return null;
        }
        deliver(player, item, "補給", total);
        return null;
    }

    /** 東西給玩家（背包 → 隨身背包），記一筆紀錄並存檔。 */
    private void deliver(final Player player, final ItemStack item, final String what, final long price) {
        // 檢查過收得下；萬一放不下掉在腳下（只有自己撿得到）
        wallet().dropFor(player, wallet().give(player, item));
        log(player, item, what, price);
    }

    private TradeLogRepository.Entry entry(final Player player, final ItemStack item, final String what, final long price) {
        final String uuid = plugin.items().read(item).map(ItemData::uuid).orElse(null);
        return new TradeLogRepository.Entry(UUID.randomUUID().toString(), TradeLogRepository.VENDOR, uuid,
                Wallet.describe(item) + String.format(Locale.ROOT, "（%s，%,d 金幣）", what, price), item.getAmount(),
                null, "商人", player.getUniqueId(), player.getName());
    }

    /** 記一筆紀錄（誰跟商人買了什麼、多少錢）並存檔。 */
    private void log(final Player player, final ItemStack item, final String what, final long price) {
        commitBatch(player, List.of(entry(player, item, what, price)), what);
    }

    /** 一批紀錄一次存檔（角色＋倉庫只拍一次快照、資料庫只寫一個交易）。 */
    void commitBatch(final Player player, final List<TradeLogRepository.Entry> entries, final String what) {
        if (entries.isEmpty()) {
            return;
        }
        plugin.players().markDirty(player);
        plugin.characters().commit(List.of(player), connection -> plugin.economy().tradeLog().insert(connection, entries), entries.getFirst().tradeId(),
                what + "（" + player.getName() + "）");
    }

    private Wallet wallet() {
        return plugin.economy().wallet();
    }

    public void forget(final Player player) {
        stocks.remove(player.getUniqueId());
    }
}
