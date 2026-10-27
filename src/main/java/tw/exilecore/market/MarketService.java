package tw.exilecore.market;

import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyConfig;
import tw.exilecore.economy.TradeLogRepository;
import tw.exilecore.economy.Wallet;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSearch;
import tw.exilecore.item.Rarity;
import tw.exilecore.npc.NpcType;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 市集（PoE2 的非同步交易）與通貨交易所。
 * <ul>
 *   <li>商店頁：把背包裡的東西用通貨標價上架（付金幣手續費），東西離開背包放在市集；上架後一段時間內不能下架或改價。</li>
 *   <li>市集：搜尋所有人上架的東西，直接買（付通貨＋金幣手續費），賣家不用在線；賣家的收入自動放進他的通貨（離線的等上線）。</li>
 *   <li>通貨交易所：掛單「用 A 換 B」，價格合得來就自動成交；成交的通貨也自動入袋（2026-10，像 PoE2）。</li>
 * </ul>
 * 啟動時把所有上架、收入、掛單讀進記憶體；每個操作改完記憶體後，用 CharacterService.commit 把操作的資料表與玩家存檔
 * 放在同一個交易寫入。新上架的東西、新的收入、新的掛單要等寫進資料庫後才能被別人買、領、成交。
 */
public final class MarketService {

    /** 市集掌櫃附近幾格內可以用 /market。 */
    private static final double NPC_RANGE = 8;

    /** 搜尋的排序。 */
    enum Sort {
        PRICE("價格低到高"), NEWEST("最新上架");

        private final String displayName;

        Sort(final String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    private final ExileCore plugin;
    private final MarketRepository repository = new MarketRepository();
    private final Map<String, Listing> listings = new LinkedHashMap<>();
    private final Map<String, Proceed> proceeds = new LinkedHashMap<>();
    private final ExchangeBook book = new ExchangeBook();

    public MarketService(final ExileCore plugin) {
        this.plugin = plugin;
    }

    // ---- 月卡：市集、交易所不收手續費（2026-10）----

    /** 有月卡：上架、改價、購買、掛單都不收手續費。 */
    public boolean feeFree(final Player player) {
        return plugin.sponsors() != null && plugin.sponsors().cardActive(player);
    }

    public long listingFee(final Player player, final String currency, final int amount) {
        return feeFree(player) ? 0 : config().listingFee(currency, amount);
    }

    /**
     * 上架（改價）的手續費（2026-10）：照「上架的東西本身的估價」收，跟面對面交易一樣（裝備照商人價、通貨照參考價值），
     * 標多高多低都一樣；有月卡免費。
     */
    public long listingFee(final Player player, final ItemStack item) {
        return feeFree(player) ? 0 : config().listingFee().of(itemValue(item));
    }

    /** 一件東西估成多少金幣（跟面對面交易的手續費同一套）。 */
    public long itemValue(final ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        final tw.exilecore.trade.TradeFee.Received received;
        final java.util.Optional<tw.exilecore.currency.CurrencyType> currency = plugin.currencies().read(stack);
        if (currency.isPresent()) {
            received = new tw.exilecore.trade.TradeFee.Currency(currency.get().id(), stack.getAmount());
        } else {
            final java.util.Optional<ItemData> data = plugin.items().read(stack);
            received = data.isPresent() ? new tw.exilecore.trade.TradeFee.Gear(data.get().rarity(), data.get().ilvl(), stack.getAmount())
                    : new tw.exilecore.trade.TradeFee.Other(stack.getAmount());
        }
        return tw.exilecore.trade.TradeFee.value(config(), received);
    }

    public long purchaseFee(final Player player, final String currency, final int amount) {
        return feeFree(player) ? 0 : config().purchaseFee(currency, amount);
    }

    public long exchangeFee(final Player player, final String currency, final int amount) {
        return feeFree(player) ? 0 : config().exchangeFee(currency, amount);
    }

    EconomyConfig config() {
        return plugin.economy().config();
    }

    Wallet wallet() {
        return plugin.economy().wallet();
    }

    ExchangeBook book() {
        return book;
    }

    // ---- 2026-10-25：交易所的星幣（帳號裡的贊助星幣） ----

    static boolean starCoin(final String id) {
        return Wallet.STAR_COIN.equals(id);
    }

    /** 星幣能不能在交易所用（null = 可以）。 */
    private String starProblem(final Player player) {
        if (plugin.sponsors() == null) {
            return "贊助沒有開放，星幣不能交易";
        }
        return plugin.sponsors().exchangeProblem(player);
    }

    /** 加減星幣，回傳跟交易所資料一起寫的工作（null = 失敗，什麼都沒改）。 */
    private tw.exilecore.character.CharacterRepository.ExtraWork starWork(final Player player, final long delta, final String what) {
        return plugin.sponsors() == null ? null : plugin.sponsors().exchangeCoins(player, delta, what);
    }

    // ---- 載入 ----

    /** 啟動時（主執行緒）：把資料庫裡的上架、收入、掛單讀進來。 */
    public void load() throws SQLException {
        final List<MarketRepository.ListingRow> listingRows = plugin.database().sync(repository::loadListings);
        final List<MarketRepository.ProceedRow> proceedRows = plugin.database().sync(repository::loadProceeds);
        final List<MarketRepository.OrderRow> orderRows = plugin.database().sync(repository::loadOrders);
        listings.clear();
        proceeds.clear();
        for (final ExchangeBook.Order order : List.copyOf(book.all())) {
            book.remove(order.id());
        }
        int broken = 0;
        for (final MarketRepository.ListingRow row : listingRows) {
            final ItemStack item;
            try {
                item = ItemStack.deserializeBytes(row.item());
            } catch (final RuntimeException e) {
                broken++;
                plugin.getSLF4JLogger().error("市集：上架 {}（{}）的物品讀不出來，先不顯示（資料庫裡保留）", row.id(), row.sellerName(), e);
                continue;
            }
            // 上架中的舊物品：舊版畫的重畫、舊版的傳奇照目前的定義補正（買到的跟看到的一樣；2026-10-26 起補正過的寫回資料庫）
            boolean refreshed = plugin.items().refresh(item);
            if (plugin.skills() != null) {
                refreshed |= plugin.skills().gems().refresh(item); // 寶石：技能、輔助改過名字的重畫
            }
            final ItemData data = plugin.items().read(item).orElse(null);
            final String search = refreshed ? searchText(item, data) : row.searchText();
            if (refreshed) {
                // 2026-10-26：補正過的（詞綴照新資料換算、重畫）寫回資料庫，下次啟動不用再換；這次之前要到改價或賣出才更新
                final byte[] bytes = item.serializeAsBytes();
                final String listingId = row.id();
                plugin.database().async(connection -> {
                    repository.updateItem(connection, listingId, bytes, search);
                    return null;
                }).whenComplete((ignored, error) -> {
                    if (error != null) {
                        plugin.getSLF4JLogger().warn("市集：上架 {} 補正後的物品寫不回資料庫：{}", listingId, error.toString());
                    }
                });
            }
            listings.put(row.id(), new Listing(row.id(), row.account(), row.sellerName(), item, row.itemUuid(),
                    search,
                    category(item, data), data == null ? null : data.rarity(), row.priceCurrency(), row.priceAmount(),
                    row.listedAt().toEpochMilli(), null, true));
        }
        for (final MarketRepository.ProceedRow row : proceedRows) {
            proceeds.put(row.id(), new Proceed(row.id(), row.account(), row.currencyId(), row.amount(), row.source(), row.note(),
                    row.createdAt().toEpochMilli(), null, true));
        }
        for (final MarketRepository.OrderRow row : orderRows) {
            final ExchangeBook.Order order = new ExchangeBook.Order(row.id(), row.account(), row.playerName(), row.give(),
                    row.giveAmount(), row.want(), row.wantAmount(), row.createdAt().toEpochMilli(),
                    row.giveAmount() - row.taken(), true, null);
            book.add(order);
        }
        plugin.getSLF4JLogger().info("市集：{} 件上架、{} 筆收入待領、{} 張掛單{}", listings.size(), proceeds.size(), book.all().size(),
                broken > 0 ? "（" + broken + " 件讀不出來）" : "");
    }

    /** 卡住的操作寫進資料庫之後：那些操作建立的上架、收入、掛單可以用了。 */
    public void confirm(final Set<String> opIds) {
        for (final Listing listing : listings.values()) {
            if (listing.opId != null && opIds.contains(listing.opId)) {
                listing.confirmed = true;
            }
        }
        for (final Proceed proceed : proceeds.values()) {
            if (proceed.opId != null && opIds.contains(proceed.opId)) {
                proceed.confirmed = true;
            }
        }
        for (final String opId : opIds) {
            book.confirm(opId);
        }
    }

    private void confirm(final String opId) {
        confirm(Set.of(opId));
    }

    // ---- 查詢 ----

    /** 搜尋（只列可以買的）。 */
    List<Listing> search(final String query, final Listing.Category category, final Rarity rarity, final Sort sort) {
        return search(query, category, rarity, sort, listing -> true);
    }

    /** 搜尋，另外再篩一次（例如「我能用的」「價格上限」）。 */
    List<Listing> search(final String query, final Listing.Category category, final Rarity rarity, final Sort sort,
                         final java.util.function.Predicate<Listing> filter) {
        final List<String> terms = MarketSearch.terms(query);
        final List<Listing> result = new ArrayList<>();
        for (final Listing listing : listings.values()) {
            if (!listing.confirmed || !filter.test(listing)) {
                continue;
            }
            if (category != Listing.Category.ALL && listing.category != category) {
                continue;
            }
            if (rarity != null && listing.rarity != rarity) {
                continue;
            }
            if (MarketSearch.matches(listing.searchText, terms)) {
                result.add(listing);
            }
        }
        if (sort == Sort.PRICE) {
            result.sort(Comparator.<Listing>comparingLong(listing -> config().value(listing.priceCurrency, listing.priceAmount))
                    .thenComparingLong(listing -> listing.listedAt));
        } else {
            result.sort(Comparator.<Listing>comparingLong(listing -> listing.listedAt).reversed());
        }
        return result;
    }

    // ---- 比價與篩選 ----

    /** 同類的代號：同一件傳奇、同一個基底與稀有度、同一種寶石與等級、同一種藥劑或通貨。 */
    String similarKey(final ItemStack item) {
        final ItemData data = plugin.items().read(item).orElse(null);
        if (data != null) {
            return data.unique() != null ? "unique:" + data.unique() : "base:" + data.base() + ":" + data.rarity().name();
        }
        if (plugin.skills() != null) {
            final tw.exilecore.skill.GemData gem = plugin.skills().gems().read(item).orElse(null);
            if (gem != null) {
                return "gem:" + gem.kind().name() + ":" + gem.id() + ":" + gem.level();
            }
        }
        if (plugin.flasks() != null) {
            final tw.exilecore.flask.FlaskService.FlaskData flask = plugin.flasks().read(item).orElse(null);
            if (flask != null) {
                return "flask:" + flask.base();
            }
        }
        final tw.exilecore.currency.CurrencyType currency = plugin.currencies().read(item).orElse(null);
        if (currency != null) {
            return "currency:" + currency.id();
        }
        return "item:" + item.getType().name() + ":" + tw.exilecore.stash.StashOrganizer.plainName(item);
    }

    private String similarKey(final Listing listing) {
        if (listing.similarKey == null) {
            listing.similarKey = similarKey(listing.item);
        }
        return listing.similarKey;
    }

    /** /ec reload 之後：物品資料可能改了，同類代號與裝備需求重算。 */
    void clearCaches() {
        for (final Listing listing : listings.values()) {
            listing.similarKey = null;
            listing.requirements = null;
        }
    }

    /** 市集上跟這件同類、別人上架的東西（便宜的在前），標價時參考。價值不明的（通貨被拿掉了）不算。 */
    List<Listing> similar(final ItemStack item, final UUID exclude) {
        final String key = similarKey(item);
        final List<Listing> result = new ArrayList<>();
        for (final Listing listing : listings.values()) {
            if (listing.confirmed && !listing.seller.equals(exclude) && key.equals(similarKey(listing))
                    && config().value(listing.priceCurrency, listing.priceAmount) > 0) {
                result.add(listing);
            }
        }
        result.sort(Comparator.<Listing>comparingLong(listing -> config().value(listing.priceCurrency, listing.priceAmount))
                .thenComparingLong(listing -> listing.listedAt));
        return result;
    }

    /** 裝備需求（不是裝備、地圖回傳 {@link Listing#NONE}）。 */
    private ItemBase.Requirements requirements(final Listing listing) {
        if (listing.requirements == null) {
            final ItemData data = plugin.items().read(listing.item).orElse(null);
            final ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
            listing.requirements = base == null || base.isMap() ? Listing.NONE
                    : tw.exilecore.item.ItemStats.requirements(plugin.items().repository(), base, data);
        }
        return listing.requirements;
    }

    /** 這個角色穿不穿得上（等級、力量、敏捷、智慧）；穿得上回傳 null，否則是缺什麼（例如「等級 32」）。 */
    String requirementProblem(final Player player, final Listing listing) {
        final ItemBase.Requirements req = requirements(listing);
        if (req == Listing.NONE) {
            return null;
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        final tw.exilecore.stats.StatSheet sheet = state.sheet();
        if (state.level() < req.level()) {
            return "等級 " + req.level();
        }
        if (sheet.get(tw.exilecore.stats.StatKeys.STR) < req.str()) {
            return "力量 " + req.str();
        }
        if (sheet.get(tw.exilecore.stats.StatKeys.DEX) < req.dex()) {
            return "敏捷 " + req.dex();
        }
        if (sheet.get(tw.exilecore.stats.StatKeys.INT) < req.intelligence()) {
            return "智慧 " + req.intelligence();
        }
        return null;
    }

    /** 這件是不是裝備（有等級、屬性需求）。 */
    boolean isGear(final Listing listing) {
        return requirements(listing) != Listing.NONE;
    }

    /** 某個帳號的商店頁（早的在前）。 */
    List<Listing> shopOf(final UUID account) {
        final List<Listing> list = new ArrayList<>();
        for (final Listing listing : listings.values()) {
            if (listing.seller.equals(account)) {
                list.add(listing);
            }
        }
        return list;
    }

    Listing listing(final String id) {
        return listings.get(id);
    }

    /** 某個帳號可以領的收入（還在寫入中的不算）。 */
    List<Proceed> proceedsOf(final UUID account, final boolean confirmedOnly) {
        final List<Proceed> list = new ArrayList<>();
        for (final Proceed proceed : proceeds.values()) {
            if (proceed.account.equals(account) && (!confirmedOnly || proceed.confirmed)) {
                list.add(proceed);
            }
        }
        return list;
    }

    public int listingCount() {
        return listings.size();
    }

    public int proceedCount() {
        return proceeds.size();
    }

    public int orderCount() {
        return book.all().size();
    }

    /** 物品的 UUID → 在市集的哪裡（查複製用）：上架中的。 */
    public Map<String, String> listedItemUuids() {
        final Map<String, String> map = new LinkedHashMap<>();
        for (final Listing listing : listings.values()) {
            if (listing.itemUuid != null) {
                map.put(listing.itemUuid, "市集（" + listing.sellerName + " 的商店頁）");
            }
        }
        return map;
    }

    // ---- 能不能用 ----

    /** 營地裡、市集掌櫃旁邊、或管理員：可以用市集。 */
    public boolean canUseHere(final Player player) {
        if (player.hasPermission(ExileCore.PERMISSION_ADMIN)) {
            return true;
        }
        if (plugin.zones() != null && plugin.zones().inTown(player.getLocation())) {
            return true;
        }
        for (final Entity entity : player.getNearbyEntities(NPC_RANGE, NPC_RANGE, NPC_RANGE)) {
            if (plugin.npcs() != null && plugin.npcs().type(entity).orElse(null) == NpcType.MARKET) {
                return true;
            }
        }
        return false;
    }

    /** 做操作前的共同檢查；null = 可以。 */
    String problem(final Player player) {
        final String problem = plugin.characters().transactionProblem(player);
        if (problem != null) {
            return problem;
        }
        if (!canUseHere(player)) {
            return "市集只能在營地使用";
        }
        if (plugin.trade() != null && plugin.trade().inTrade(player)) {
            return "正在交易中";
        }
        return null;
    }

    // ---- 商店頁：上架、改價、下架 ----

    /**
     * 通貨交易所最多掛幾張單（2026-10）：economy.yml 的 exchange.max_orders，加上跟商店頁一樣多出來的格數
     * （累積贊助、星幣買的上架格多幾格，掛單就多幾張）。
     */
    public int maxOrders(final Player player) {
        final int base = config().maxOrders();
        return Math.min(tw.exilecore.sponsor.SponsorConfig.MAX_LISTINGS, base + Math.max(0, shopSlots(player) - config().shopSlots()));
    }

    /** 這個人的商店頁最多放幾件（economy.yml 的 market.shop_slots；累積贊助的照 sponsor.yml 的 listings，取大的，再加星幣買的；2026-10）。 */
    public int shopSlots(final Player player) {
        final int base = config().shopSlots();
        if (plugin.sponsors() == null) {
            return base;
        }
        // 累積贊助送的上限（跟一般的取大的），再加用星幣買的
        return Math.min(tw.exilecore.sponsor.SponsorConfig.MAX_LISTINGS,
                Math.max(base, plugin.sponsors().tierListings(player)) + plugin.sponsors().boughtListings(player));
    }

    /** 商店頁滿了的時候補一句：累積贊助可以多放（已經是最高的不寫）。 */
    String slotsHint(final Player player) {
        return plugin.sponsors() == null ? "" : plugin.sponsors().listingsHint(player);
    }

    /**
     * 把背包第 slot 格（要跟 snapshot 一樣）用 amount 個 currency 上架。
     *
     * @return null = 成功；否則是原因
     */
    String list(final Player player, final int slot, final ItemStack snapshot, final String currency, final int amount) {
        final String problem = problem(player);
        if (problem != null) {
            return problem;
        }
        final String priceProblem = priceProblem(currency, amount);
        if (priceProblem != null) {
            return priceProblem;
        }
        final UUID account = player.getUniqueId();
        if (shopOf(account).size() >= shopSlots(player)) {
            return "商店頁滿了（" + shopSlots(player) + " 件）：先下架一些" + slotsHint(player);
        }
        final ItemStack current = player.getInventory().getItem(slot);
        if (current == null || current.isEmpty() || !current.isSimilar(snapshot) || current.getAmount() != snapshot.getAmount()) {
            return "那一格的東西變了，請重新選";
        }
        if (plugin.gold().amountOf(current).isPresent()) {
            return "金幣不能上架";
        }
        if (plugin.items().read(current).map(ItemData::locked).orElse(false)) {
            return "這件鎖定了（/lock 解除後才能上架）";
        }
        if (tw.exilecore.life.LifeService.isHook(current)) {
            return "星索鉤綁定帳號，不能上架"; // 2026-10-27：這次之前上架後再領一把＝複製
        }
        if (current.hasData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS)
                || current.hasData(io.papermc.paper.datacomponent.DataComponentTypes.CONTAINER)) {
            // 2026-10-21 漏洞修正（照面對面交易）：容器裡的東西估不到價，手續費變最低；裡面鎖定的東西也會被賣掉
            return "收納袋、界伏盒這類容器不能上架（把裡面的東西拿出來再上架）";
        }
        final long fee = listingFee(player, current);
        if (!plugin.gold().take(player, fee, tw.exilecore.economy.EconomyLedger.FEE_LIST)) {
            return String.format(Locale.ROOT, "金幣不夠：上架手續費 %,d", fee);
        }
        final ItemStack item = current.clone();
        player.getInventory().setItem(slot, null);
        if (plugin.flasks() != null) {
            plugin.flasks().ensureSlots(player); // 上架的是藥劑欄的藥劑：那格馬上換成空瓶
        }
        final ItemData data = plugin.items().read(item).orElse(null);
        final String opId = UUID.randomUUID().toString();
        final long now = System.currentTimeMillis();
        final Listing listing = new Listing(UUID.randomUUID().toString(), account, player.getName(), item,
                data == null ? null : data.uuid(), searchText(item, data), category(item, data), data == null ? null : data.rarity(),
                currency, amount, now, opId, false);
        listings.put(listing.id, listing);
        final MarketRepository.ListingRow row = new MarketRepository.ListingRow(listing.id, account, player.getName(),
                item.serializeAsBytes(), listing.itemUuid, listing.searchText, currency, amount, Instant.ofEpochMilli(now));
        final List<TradeLogRepository.Entry> log = List.of(new TradeLogRepository.Entry(opId, TradeLogRepository.LIST,
                listing.itemUuid, Wallet.describe(item) + "（標價 " + wallet().price(currency, amount) + "）", item.getAmount(),
                account, player.getName(), null, "商店頁"));
        plugin.players().markDirty(player);
        plugin.characters().commit(List.of(player), connection -> {
            repository.insertListing(connection, row);
            plugin.economy().tradeLog().insert(connection, log);
        }, opId, "市集上架（" + player.getName() + "）").thenAccept(ok -> {
            if (ok) {
                confirm(opId);
            }
        });
        plugin.getSLF4JLogger().info("[market] {} 上架 {}，{}", player.getName(), Wallet.describe(item), wallet().price(currency, amount));
        return null;
    }

    String priceProblem(final String currency, final int amount) {
        final CurrencyType type = plugin.currencies().repository().get(currency);
        if (type == null || !config().priceable(currency)) { // 2026-10：未切割寶石也能拿來標價（通貨頁上的都可以）
            return "這種通貨不能拿來標價";
        }
        if (amount < 1 || amount > config().maxPrice()) {
            return "價格要在 1 到 " + config().maxPrice() + " 之間";
        }
        return null;
    }

    /** 上架後還要等多久才能下架或改價（毫秒；0 = 可以）。 */
    long lockedFor(final Listing listing) {
        return Math.max(0, listing.listedAt + config().relistMillis() - System.currentTimeMillis());
    }

    String reprice(final Player player, final String listingId, final String currency, final int amount) {
        final String problem = problem(player);
        if (problem != null) {
            return problem;
        }
        final Listing listing = listings.get(listingId);
        if (listing == null || !listing.seller.equals(player.getUniqueId())) {
            return "這件已經不在你的商店頁了";
        }
        if (!listing.confirmed) {
            return "上架還在存檔中，請稍等";
        }
        final String priceProblem = priceProblem(currency, amount);
        if (priceProblem != null) {
            return priceProblem;
        }
        final long locked = lockedFor(listing);
        if (locked > 0) {
            return "上架 " + config().relistMinutes() + " 分鐘內不能改價（還要 " + duration(locked) + "）";
        }
        final long fee = listingFee(player, listing.item);
        if (!plugin.gold().take(player, fee, tw.exilecore.economy.EconomyLedger.FEE_LIST)) {
            return String.format(Locale.ROOT, "金幣不夠：改價也要付上架手續費 %,d", fee);
        }
        final long now = System.currentTimeMillis();
        listing.priceCurrency = currency;
        listing.priceAmount = amount;
        listing.listedAt = now;
        final String id = listing.id;
        plugin.characters().commit(List.of(player), connection -> repository.updatePrice(connection, id, currency, amount,
                Instant.ofEpochMilli(now)), null, "市集改價（" + player.getName() + "）");
        return null;
    }

    String unlist(final Player player, final String listingId) {
        final String problem = problem(player);
        if (problem != null) {
            return problem;
        }
        final Listing listing = listings.get(listingId);
        if (listing == null || !listing.seller.equals(player.getUniqueId())) {
            return "這件已經不在你的商店頁了（可能剛被買走）";
        }
        if (!listing.confirmed) {
            return "上架還在存檔中，請稍等";
        }
        final long locked = lockedFor(listing);
        if (locked > 0) {
            return "上架 " + config().relistMinutes() + " 分鐘內不能下架（還要 " + duration(locked) + "）";
        }
        if (!wallet().fits(player, listing.item)) {
            return "背包和隨身背包都放不下";
        }
        listings.remove(listing.id);
        // 上面確認過收得下；萬一還是放不下，剩下的掉在腳下（不能放回商店頁：放進去的那部分會變成兩份）
        wallet().dropFor(player, wallet().give(player, listing.item.clone()));
        final String opId = UUID.randomUUID().toString();
        final List<TradeLogRepository.Entry> log = List.of(new TradeLogRepository.Entry(opId, TradeLogRepository.UNLIST,
                listing.itemUuid, Wallet.describe(listing.item), listing.item.getAmount(), null, "商店頁",
                player.getUniqueId(), player.getName()));
        final String id = listing.id;
        plugin.players().markDirty(player);
        plugin.characters().commit(List.of(player), connection -> {
            repository.deleteListing(connection, id);
            plugin.economy().tradeLog().insert(connection, log);
        }, opId, "市集下架（" + player.getName() + "）");
        return null;
    }

    // ---- 市集：購買 ----

    /** 買一件；currency、amount 是玩家看到的價格（中途改價就不買）。 */
    String buy(final Player player, final String listingId, final String currency, final int amount) {
        final String problem = problem(player);
        if (problem != null) {
            return problem;
        }
        final Listing listing = listings.get(listingId);
        if (listing == null || !listing.confirmed) {
            return "這件已經賣掉或下架了";
        }
        if (listing.seller.equals(player.getUniqueId())) {
            return "這是你自己上架的（到商店頁下架）";
        }
        if (!listing.priceCurrency.equals(currency) || listing.priceAmount != amount) {
            return "賣家剛改了價格，請重新看一次";
        }
        final long fee = purchaseFee(player, currency, amount);
        if (plugin.gold().balance(player) < fee) {
            return String.format(Locale.ROOT, "金幣不夠：購買手續費 %,d", fee);
        }
        final int have = wallet().available(player, currency);
        if (have < amount) {
            return "通貨不夠：要 " + wallet().price(currency, amount) + "，你有 " + have + " 個（通貨袋＋背包＋倉庫）";
        }
        if (!wallet().fits(player, listing.item)) {
            return "背包和隨身背包都放不下";
        }
        plugin.gold().take(player, fee, tw.exilecore.economy.EconomyLedger.FEE_BUY);
        wallet().take(player, currency, amount);
        listings.remove(listing.id);
        // 確認過收得下；萬一還是放不下，掉在腳下（只有自己撿得到）
        wallet().dropFor(player, wallet().give(player, listing.item.clone()));
        final String opId = UUID.randomUUID().toString();
        final long now = System.currentTimeMillis();
        final Proceed proceed = new Proceed(UUID.randomUUID().toString(), listing.seller, currency, amount, "market",
                "賣出 " + Wallet.describe(listing.item) + " 給 " + player.getName(), now, opId, false);
        proceeds.put(proceed.id, proceed);
        final MarketRepository.ProceedRow proceedRow = new MarketRepository.ProceedRow(proceed.id, proceed.account,
                currency, amount, proceed.source, proceed.note, Instant.ofEpochMilli(now));
        final List<TradeLogRepository.Entry> log = List.of(
                new TradeLogRepository.Entry(opId, TradeLogRepository.MARKET, listing.itemUuid, Wallet.describe(listing.item),
                        listing.item.getAmount(), listing.seller, listing.sellerName, player.getUniqueId(), player.getName()),
                new TradeLogRepository.Entry(opId, TradeLogRepository.MARKET, null, currencyName(currency), amount,
                        player.getUniqueId(), player.getName(), listing.seller, listing.sellerName));
        final String id = listing.id;
        plugin.players().markDirty(player);
        plugin.characters().commit(List.of(player), connection -> {
            if (repository.deleteListing(connection, id) == 0) {
                plugin.getSLF4JLogger().warn("市集：成交的上架 {} 在資料庫裡已經不在了", id);
            }
            repository.insertProceed(connection, proceedRow);
            plugin.economy().tradeLog().insert(connection, log);
        }, opId, "市集購買（" + player.getName() + " 向 " + listing.sellerName + "）").thenAccept(ok -> {
            if (ok) {
                confirm(opId);
            }
        });
        plugin.getSLF4JLogger().info("[market] {} 向 {} 買了 {}，{}＋{} 金幣手續費", player.getName(), listing.sellerName,
                Wallet.describe(listing.item), wallet().price(currency, amount), fee);
        final Player seller = plugin.getServer().getPlayer(listing.seller);
        if (seller != null) {
            seller.sendMessage(plugin.text().prefixed("<#f5b942>市集：<gray>你的 ").append(listing.item.effectiveName())
                    .append(plugin.text().parse("<gray> 賣掉了，收入 <white>" + ItemRenderer.escape(wallet().price(currency, amount))
                            + "</white>（稍後自動放進你的通貨）")));
            seller.playSound(seller.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        }
        return null;
    }

    // ---- 收入 ----

    /** 領走所有收入（倉庫通貨頁 → 通貨袋 → 背包）；放不下的留著。 */
    String collect(final Player player) {
        final String problem = problem(player);
        if (problem != null) {
            return problem;
        }
        if (proceedsOf(player.getUniqueId(), true).isEmpty()) {
            return proceedsOf(player.getUniqueId(), false).isEmpty() ? "沒有可以領的收入" : "收入還在存檔中，請稍等";
        }
        deposit(player, false);
        return null;
    }

    /** 自動入袋時「放不下」只提醒一次（放得下了再重來）。 */
    private final Set<UUID> warnedFull = new java.util.HashSet<>();

    /**
     * 成交自動入袋（2026-10，像 PoE2）：交易所成交、市集賣出的收入不用再去領，在線上就直接放進通貨
     * （倉庫通貨頁 → 通貨袋 → 背包），離線的等上線。不限地點；角色資料處理中、交易中、資料庫有問題時先不放，下次再試。
     * 定時呼叫（{@link MarketModule}）。
     */
    public void autoCollect(final Player player) {
        if (proceedsOf(player.getUniqueId(), true).isEmpty() || plugin.characters().transactionProblem(player) != null
                || (plugin.trade() != null && plugin.trade().inTrade(player))) {
            return;
        }
        // 開著別的介面（倉庫、隨身背包、拆解…）時先不放，免得跟介面裡的東西打架；市集與交易所的介面可以
        final org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() != org.bukkit.event.inventory.InventoryType.CRAFTING
                && !(top.getHolder() instanceof ExchangeMenu || top.getHolder() instanceof OrdersMenu || top.getHolder() instanceof MarketMenu)) {
            return;
        }
        deposit(player, true);
    }

    /** 把確定的收入放進通貨；完全放不下的那幾筆不動（不寫資料庫），放了一部分的剩下的變成新的一筆。 */
    private void deposit(final Player player, final boolean auto) {
        final List<Proceed> mine = proceedsOf(player.getUniqueId(), true);
        final String opId = UUID.randomUUID().toString();
        final List<String> removed = new ArrayList<>();
        final List<MarketRepository.ProceedRow> leftovers = new ArrayList<>();
        final Map<String, Integer> collected = new LinkedHashMap<>();
        final long now = System.currentTimeMillis();
        boolean blocked = false;
        tw.exilecore.character.CharacterRepository.ExtraWork stars = null;
        for (final Proceed proceed : mine) {
            final int rest;
            if (starCoin(proceed.currencyId)) {
                // 2026-10-25：交易所換到的星幣：加進帳號（帳號還沒載入就先留著）
                final tw.exilecore.character.CharacterRepository.ExtraWork work = starWork(player, proceed.amount, "交易所收入");
                rest = work == null ? proceed.amount : 0;
                stars = tw.exilecore.character.CharacterRepository.ExtraWork.both(stars, work);
            } else {
                rest = wallet().deposit(player, proceed.currencyId, proceed.amount);
            }
            if (rest >= proceed.amount) {
                blocked = true; // 一個都放不下：這筆不動
                continue;
            }
            proceeds.remove(proceed.id);
            removed.add(proceed.id);
            collected.merge(proceed.currencyId, proceed.amount - rest, Integer::sum);
            if (rest > 0) {
                blocked = true;
                // 放不下的部分變成一筆新的收入，下次再領
                final Proceed left = new Proceed(UUID.randomUUID().toString(), proceed.account, proceed.currencyId, rest,
                        proceed.source, proceed.note, now, opId, false);
                proceeds.put(left.id, left);
                leftovers.add(new MarketRepository.ProceedRow(left.id, left.account, left.currencyId, rest, left.source, left.note,
                        Instant.ofEpochMilli(now)));
            }
        }
        if (!removed.isEmpty()) {
            final tw.exilecore.character.CharacterRepository.ExtraWork starsFinal = stars;
            plugin.characters().commit(List.of(player), connection -> {
                repository.deleteProceeds(connection, removed);
                for (final MarketRepository.ProceedRow row : leftovers) {
                    repository.insertProceed(connection, row);
                }
                if (starsFinal != null) {
                    starsFinal.run(connection);
                }
            }, opId, (auto ? "收入自動入袋（" : "領取市集收入（") + player.getName() + "）").thenAccept(ok -> {
                if (ok) {
                    confirm(opId);
                }
            });
        }
        final List<String> parts = new ArrayList<>();
        for (final Map.Entry<String, Integer> entry : collected.entrySet()) {
            parts.add(ItemRenderer.escape(wallet().price(entry.getKey(), entry.getValue())));
        }
        if (parts.isEmpty()) {
            if (!auto || warnedFull.add(player.getUniqueId())) {
                player.sendMessage(plugin.text().prefixed("<red>" + (auto ? "交易收入：" : "") + "倉庫通貨頁、通貨袋、背包都放不下了"
                        + (auto ? "<gray>（空出位置後會自動放進來，或用 /market collect）" : "")));
            }
            return;
        }
        if (!blocked) {
            warnedFull.remove(player.getUniqueId());
        }
        player.sendMessage(plugin.text().prefixed((auto ? "<#f5b942>交易收入入袋：" : "<green>領到收入：") + "<white>" + String.join("、", parts)
                + "</white><gray>（先放倉庫的通貨頁）" + (blocked ? "<yellow>；放不下的還留著，空出位置後會再放" : "")));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
    }

    // ---- 常用通貨（交易所，2026-10）：最近選過、交易過的（只記在記憶體，重開伺服器就清掉）----

    private final Map<UUID, java.util.Deque<String>> recentCurrencies = new java.util.HashMap<>();

    void remember(final UUID account, final String currencyId) {
        if (currencyId == null) {
            return;
        }
        final java.util.Deque<String> list = recentCurrencies.computeIfAbsent(account, key -> new java.util.ArrayDeque<>());
        list.remove(currencyId);
        list.addFirst(currencyId);
        while (list.size() > 12) {
            list.removeLast();
        }
    }

    /** 2026-10-18：下線的人的「背包滿了」提示紀錄與常用通貨清掉（以前一直累積）；MarketModule 每 10 秒叫一次。 */
    void forgetOffline() {
        warnedFull.removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        recentCurrencies.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
    }

    List<String> recent(final UUID account) {
        final java.util.Deque<String> list = recentCurrencies.get(account);
        return list == null ? List.of() : List.copyOf(list);
    }

    // ---- 通貨交易所 ----

    /** 掛一張單：最多拿 giveAmount 個 give，想換 wantAmount 個 want。 */
    String placeOrder(final Player player, final String give, final int giveAmount, final String want, final int wantAmount) {
        final String problem = problem(player);
        if (problem != null) {
            return problem;
        }
        if (give == null || want == null) {
            return "先選要給什麼、要換什麼";
        }
        if (give.equals(want)) {
            return "給的跟要的不能一樣";
        }
        if (!config().priceable(give) || !config().priceable(want)) {
            return "這種通貨不能在交易所買賣";
        }
        if (starCoin(give) || starCoin(want)) {
            final String starProblem = starProblem(player);
            if (starProblem != null) {
                return starProblem;
            }
        }
        final int max = config().maxOrderAmount();
        if (giveAmount < 1 || wantAmount < 1 || giveAmount > max || wantAmount > max) {
            return "數量要在 1 到 " + max + " 之間";
        }
        final UUID account = player.getUniqueId();
        final int orders = maxOrders(player);
        if (book.of(account).size() >= orders) {
            return "你已經掛了 " + orders + " 張單：先取消一些";
        }
        final long fee = exchangeFee(player, give, giveAmount);
        if (plugin.gold().balance(player) < fee) {
            return String.format(Locale.ROOT, "金幣不夠：掛單手續費 %,d", fee);
        }
        if (wallet().available(player, give) < giveAmount) {
            return "通貨不夠：要 " + wallet().price(give, giveAmount) + "，你有 " + wallet().available(player, give) + " 個";
        }
        final ExchangeBook.Result preview = book.simulate(give, giveAmount, want, wantAmount, account);
        if (preview.paid() <= 0 && !preview.resting()) {
            return "這個比例掛不出去：給的數量太少，換不到 1 個";
        }
        remember(account, want);
        remember(account, give);
        // 先成交，剩下的掛著
        final ExchangeBook.Result result = book.match(give, giveAmount, want, wantAmount, account);
        plugin.gold().take(player, fee, tw.exilecore.economy.EconomyLedger.FEE_EXCHANGE);
        tw.exilecore.character.CharacterRepository.ExtraWork stars = null;
        if (starCoin(give)) {
            // 2026-10-25：付星幣：從帳號扣（上面檢查過夠、帳號載入了），跟掛單一起寫
            stars = starWork(player, -(long) (result.paid() + result.restGive()), "交易所下單");
        } else {
            wallet().take(player, give, result.paid() + result.restGive());
        }
        final String opId = UUID.randomUUID().toString();
        final long now = System.currentTimeMillis();
        final List<MarketRepository.ProceedRow> proceedRows = new ArrayList<>();
        final List<String[]> fillRows = new ArrayList<>(); // {fillId, orderId, taken}
        final List<String> finishedOrders = new ArrayList<>();
        final List<TradeLogRepository.Entry> log = new ArrayList<>();
        // 成交價（參考價值跟著市價走用）：這次下單的人（吃單）付 makerGot 個 give 給舊單的主人，拿到 makerGave 個 want
        final List<tw.exilecore.economy.EconomyRepository.Trade> trades = new ArrayList<>();
        for (final ExchangeBook.Fill fill : result.fills()) {
            final ExchangeBook.Order maker = fill.maker();
            trades.add(new tw.exilecore.economy.EconomyRepository.Trade(Instant.ofEpochMilli(now), give, fill.makerGot(), want,
                    fill.makerGave(), maker.account(), account));
            // 舊單的主人收到新單付的通貨
            final Proceed proceed = new Proceed(UUID.randomUUID().toString(), maker.account(), give, fill.makerGot(), "exchange",
                    "交易所：" + wallet().price(maker.give(), fill.makerGave()) + " 換到 " + wallet().price(give, fill.makerGot()),
                    now, opId, false);
            proceeds.put(proceed.id, proceed);
            proceedRows.add(new MarketRepository.ProceedRow(proceed.id, proceed.account, give, fill.makerGot(), "exchange",
                    proceed.note, Instant.ofEpochMilli(now)));
            if (maker.lots() <= 0) {
                finishedOrders.add(maker.id());
            } else {
                fillRows.add(new String[] {UUID.randomUUID().toString(), maker.id(), Integer.toString(fill.makerGave())});
            }
            log.add(new TradeLogRepository.Entry(opId, TradeLogRepository.EXCHANGE, null, currencyName(give), fill.makerGot(),
                    account, player.getName(), maker.account(), maker.playerName()));
            log.add(new TradeLogRepository.Entry(opId, TradeLogRepository.EXCHANGE, null, currencyName(want), fill.makerGave(),
                    maker.account(), maker.playerName(), account, player.getName()));
            final Player makerPlayer = plugin.getServer().getPlayer(maker.account());
            if (makerPlayer != null) {
                makerPlayer.sendMessage(plugin.text().prefixed("<#f5b942>通貨交易所：<gray>你的單成交了 <white>"
                        + ItemRenderer.escape(wallet().price(maker.give(), fill.makerGave())) + " → "
                        + ItemRenderer.escape(wallet().price(give, fill.makerGot())) + "</white>（稍後自動放進你的通貨）"));
            }
        }
        // 自己收到的：直接放（倉庫通貨頁 → 通貨袋 → 背包），放不下的變成收入
        final int notDeposited;
        if (result.received() > 0 && starCoin(want)) {
            final tw.exilecore.character.CharacterRepository.ExtraWork got = starWork(player, result.received(), "交易所成交");
            notDeposited = got == null ? result.received() : 0;
            stars = tw.exilecore.character.CharacterRepository.ExtraWork.both(stars, got);
        } else {
            notDeposited = result.received() > 0 ? wallet().deposit(player, want, result.received()) : 0;
        }
        if (notDeposited > 0) {
            final Proceed proceed = new Proceed(UUID.randomUUID().toString(), account, want, notDeposited, "exchange",
                    "交易所成交（放不下的部分）", now, opId, false);
            proceeds.put(proceed.id, proceed);
            proceedRows.add(new MarketRepository.ProceedRow(proceed.id, account, want, notDeposited, "exchange", proceed.note,
                    Instant.ofEpochMilli(now)));
        }
        MarketRepository.OrderRow orderRow = null;
        if (result.resting()) {
            final ExchangeBook.Order order = new ExchangeBook.Order(UUID.randomUUID().toString(), account, player.getName(), give,
                    result.restGive(), want, result.restWant(), now, result.restGive(), false, opId);
            book.add(order);
            orderRow = new MarketRepository.OrderRow(order.id(), account, player.getName(), give, result.restGive(), want,
                    result.restWant(), Instant.ofEpochMilli(now), 0);
        }
        final MarketRepository.OrderRow restingRow = orderRow;
        final tw.exilecore.character.CharacterRepository.ExtraWork starsFinal = stars;
        plugin.characters().commit(List.of(player), connection -> {
            if (starsFinal != null) {
                starsFinal.run(connection); // 2026-10-25：星幣跟掛單、成交同一個交易
            }
            for (final String[] fill : fillRows) {
                repository.insertFill(connection, fill[0], fill[1], Integer.parseInt(fill[2]));
            }
            for (final String id : finishedOrders) {
                repository.deleteOrder(connection, id);
            }
            for (final MarketRepository.ProceedRow row : proceedRows) {
                repository.insertProceed(connection, row);
            }
            if (restingRow != null) {
                repository.insertOrder(connection, restingRow);
            }
            plugin.economy().tradeLog().insert(connection, log);
            plugin.economy().repository().insertTrades(connection, trades);
        }, opId, "交易所下單（" + player.getName() + "）").thenAccept(ok -> {
            if (ok) {
                confirm(opId);
            }
        });
        final StringBuilder message = new StringBuilder("<green>交易所：");
        if (result.received() > 0) {
            message.append("<gray>馬上成交，換到 <white>").append(ItemRenderer.escape(wallet().price(want, result.received())))
                    .append("</white>（付了 ").append(ItemRenderer.escape(wallet().price(give, result.paid()))).append("）");
        }
        if (result.resting()) {
            message.append(result.received() > 0 ? "；" : "").append("<gray>掛單：<white>")
                    .append(ItemRenderer.escape(wallet().price(give, result.restGive()))).append(" 換 ")
                    .append(ItemRenderer.escape(wallet().price(want, result.restWant()))).append("</white>，等別人成交");
        }
        if (result.refund() > 0) {
            message.append("<dark_gray>（用不到的 ").append(result.refund()).append(" 個沒有拿走）");
        }
        message.append(String.format(Locale.ROOT, "<dark_gray>　手續費 %,d 金幣", fee));
        player.sendMessage(plugin.text().prefixed(message.toString()));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        return null;
    }

    String cancelOrder(final Player player, final String orderId) {
        final String problem = problem(player);
        if (problem != null) {
            return problem;
        }
        final ExchangeBook.Order order = book.get(orderId);
        if (order == null || !order.account().equals(player.getUniqueId())) {
            return "這張單已經不在了（可能剛好全部成交）";
        }
        if (!order.confirmed()) {
            return "掛單還在存檔中，請稍等";
        }
        if (starCoin(order.give())) {
            final String starProblem = starProblem(player);
            if (starProblem != null) {
                return starProblem;
            }
        }
        book.remove(order.id());
        final int refund = order.remaining();
        tw.exilecore.character.CharacterRepository.ExtraWork stars = null;
        final int rest;
        if (refund > 0 && starCoin(order.give())) {
            stars = starWork(player, refund, "取消交易所掛單退回");
            rest = stars == null ? refund : 0; // 萬一加不進去：變成收入，下次再領
        } else {
            rest = refund > 0 ? wallet().deposit(player, order.give(), refund) : 0;
        }
        final String opId = UUID.randomUUID().toString();
        final long now = System.currentTimeMillis();
        MarketRepository.ProceedRow leftover = null;
        if (rest > 0) {
            final Proceed proceed = new Proceed(UUID.randomUUID().toString(), order.account(), order.give(), rest, "exchange",
                    "取消掛單退回（放不下的部分）", now, opId, false);
            proceeds.put(proceed.id, proceed);
            leftover = new MarketRepository.ProceedRow(proceed.id, proceed.account, proceed.currencyId, rest, "exchange", proceed.note,
                    Instant.ofEpochMilli(now));
        }
        final MarketRepository.ProceedRow leftoverRow = leftover;
        final String id = order.id();
        final tw.exilecore.character.CharacterRepository.ExtraWork starsFinal = stars;
        plugin.characters().commit(List.of(player), connection -> {
            repository.deleteOrder(connection, id);
            if (starsFinal != null) {
                starsFinal.run(connection);
            }
            if (leftoverRow != null) {
                repository.insertProceed(connection, leftoverRow);
            }
        }, opId, "取消掛單（" + player.getName() + "）").thenAccept(ok -> {
            if (ok) {
                confirm(opId);
            }
        });
        player.sendMessage(plugin.text().prefixed("<gray>已取消掛單，退回 <white>" + ItemRenderer.escape(wallet().price(order.give(), refund))
                + "</white>" + (rest > 0 ? "<yellow>（放不下的 " + rest + " 個先留著，空出位置後自動放進來）" : "")));
        return null;
    }

    // ---- 文字 ----

    String currencyName(final String id) {
        final CurrencyType type = plugin.currencies().repository().get(id);
        return type == null ? (starCoin(id) ? "星幣" : id) : type.name();
    }

    static String duration(final long millis) {
        final long seconds = Math.max(1, millis / 1000);
        return seconds >= 60 ? seconds / 60 + " 分 " + seconds % 60 + " 秒" : seconds + " 秒";
    }

    static String ago(final long millis) {
        final long minutes = Math.max(0, (System.currentTimeMillis() - millis) / 60_000);
        if (minutes < 1) {
            return "剛剛";
        }
        if (minutes < 60) {
            return minutes + " 分鐘前";
        }
        if (minutes < 60 * 24) {
            return minutes / 60 + " 小時前";
        }
        return minutes / 60 / 24 + " 天前";
    }

    /** 搜尋用的文字：名稱、說明（含詞綴）、稀有度、基底（小寫）。 */
    String searchText(final ItemStack item, final ItemData data) {
        return ItemSearch.text(plugin.items(), item);
    }

    Listing.Category category(final ItemStack item, final ItemData data) {
        if (data == null) {
            return Listing.Category.OTHER;
        }
        final ItemBase base = plugin.items().repository().base(data.base());
        if (base == null) {
            return Listing.Category.OTHER;
        }
        if (base.isMap()) {
            return Listing.Category.MAP;
        }
        if (base.isWeapon()) {
            return Listing.Category.WEAPON;
        }
        if (base.isJewellery()) {
            return Listing.Category.JEWELLERY;
        }
        return Listing.Category.ARMOUR; // 防具與盾
    }
}
