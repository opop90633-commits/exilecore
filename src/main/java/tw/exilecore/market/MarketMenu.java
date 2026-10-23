package tw.exilecore.market;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.Rarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 市集（/market，或右鍵市集掌櫃）：搜尋所有人上架的東西，點一下確認購買。
 * 最下面一列：搜尋（表單：關鍵字、分類、稀有度、我能用的、價格上限、排序）、分類、稀有度、「我能用的」、排序、換頁、
 * 我的商店頁、通貨交易所、領收入。裝備會標出你穿不穿得上。
 */
public final class MarketMenu extends Menu {

    /** 搜尋條件（換頁、回來時保留）。 */
    static final class State {
        String query = "";
        Listing.Category category = Listing.Category.ALL;
        Rarity rarity;
        MarketService.Sort sort = MarketService.Sort.PRICE;
        int page;
        /** 只看這個角色穿得上的裝備。 */
        boolean usable;
        /** 價格上限（別種通貨照參考價值換算比較）；maxAmount 是 0 = 不限。 */
        String maxCurrency;
        int maxAmount;

        boolean filtered() {
            return !query.isBlank() || category != Listing.Category.ALL || rarity != null || usable || maxAmount > 0;
        }
    }

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_SEARCH = 45;
    private static final int SLOT_CATEGORY = 46;
    private static final int SLOT_RARITY = 47;
    private static final int SLOT_USABLE = 48;
    private static final int SLOT_SORT = 49;
    private static final int SLOT_PAGE = 50;
    private static final int SLOT_SHOP = 51;
    private static final int SLOT_EXCHANGE = 52;
    private static final int SLOT_PROCEEDS = 53;

    private final MarketService service;
    private final State state;
    private final List<Listing> shown = new ArrayList<>();

    public MarketMenu(final ExileCore plugin, final Player player, final MarketService service) {
        this(plugin, player, service, new State());
    }

    MarketMenu(final ExileCore plugin, final Player player, final MarketService service, final State state) {
        super(plugin, player, 6, "<dark_gray>市集" + (state.query.isBlank() ? "" : "：搜尋「" + ItemRenderer.escape(state.query) + "」"));
        this.service = service;
        this.state = state;
    }

    @Override
    protected void draw() {
        final List<Listing> results = service.search(state.query, state.category, state.rarity, state.sort, this::passes);
        final int pages = Math.max(1, (results.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        state.page = Math.max(0, Math.min(state.page, pages - 1));
        shown.clear();
        for (int i = 0; i < PAGE_SIZE; i++) {
            final int index = state.page * PAGE_SIZE + i;
            if (index >= results.size()) {
                set(i, null);
                continue;
            }
            final Listing listing = results.get(index);
            shown.add(listing);
            set(i, display(listing));
        }
        if (results.isEmpty()) {
            set(22, icon(Material.GLASS_PANE, "<gray>沒有符合的東西",
                    state.filtered() ? "<dark_gray>放寬條件，或右鍵「搜尋」清除全部條件" : "<dark_gray>還沒有人上架；你可以到「我的商店頁」上架"));
        }
        final List<String> searchLore = new ArrayList<>();
        searchLore.add(state.query.isBlank() ? "<gray>關鍵字：全部" : "<gray>關鍵字：<white>" + ItemRenderer.escape(state.query));
        if (state.maxAmount > 0 && state.maxCurrency != null) {
            searchLore.add("<gray>價格上限：<white>" + ItemRenderer.escape(service.wallet().price(state.maxCurrency, state.maxAmount))
                    + " <dark_gray>（別種通貨照參考比例換算）");
        }
        searchLore.add("<dark_gray>名稱、詞綴、基底都找得到；空白隔開＝都要有");
        searchLore.add("");
        searchLore.add("<yellow>左鍵：搜尋與篩選（一次設好）");
        searchLore.add(state.filtered() ? "<yellow>右鍵：清除全部條件" : "");
        final ItemStack searchIcon = icon(Material.SPYGLASS, "<#f5b942>搜尋" + (state.filtered() ? "（有條件）" : ""), searchLore);
        set(SLOT_SEARCH, state.filtered() ? glowing(searchIcon) : searchIcon);
        set(SLOT_CATEGORY, icon(Material.CHEST, "<#f5b942>分類：" + state.category.displayName(), "<yellow>左鍵：下一個　右鍵：全部"));
        set(SLOT_RARITY, icon(Material.AMETHYST_SHARD, "<#f5b942>稀有度：" + (state.rarity == null ? "全部" : state.rarity.displayName()),
                "<yellow>左鍵：下一個　右鍵：全部"));
        final ItemStack usable = icon(state.usable ? Material.LIME_DYE : Material.GRAY_DYE,
                (state.usable ? "<green>" : "<gray>") + "只看我能用的：" + (state.usable ? "開" : "關"),
                "<gray>裝備只列出這個角色穿得上的（等級、力量、敏捷、智慧）",
                "<dark_gray>寶石、地圖、藥劑不受影響", "", "<yellow>左鍵：" + (state.usable ? "關掉" : "打開"));
        set(SLOT_USABLE, state.usable ? glowing(usable) : usable);
        set(SLOT_SORT, icon(Material.COMPARATOR, "<#f5b942>排序：" + state.sort.displayName(), "<dark_gray>不同通貨的價格照參考比例換算比較",
                "<yellow>左鍵：切換"));
        // 2026-10-23：資源包的翻頁圖示（跟其他選單的上一頁／下一頁同一套；最後一頁換成往左：只能往回翻）
        set(SLOT_PAGE, uiIcon(state.page < pages - 1 ? "nav_next" : "nav_prev", Material.ARROW,
                "<white>第 " + (state.page + 1) + " / " + pages + " 頁",
                "<gray>共 " + results.size() + " 件", "",
                state.page < pages - 1 ? "<yellow>左鍵：下一頁" : "<dark_gray>已經是最後一頁",
                state.page > 0 ? "<yellow>右鍵：上一頁　Shift＋右鍵：第一頁" : ""));
        final int mine = service.shopOf(player.getUniqueId()).size();
        set(SLOT_SHOP, icon(Material.BARREL, "<#f5b942>我的商店頁",
                "<gray>上架了 " + mine + " / " + service.shopSlots(player) + " 件",
                "<dark_gray>把背包裡的東西用通貨標價，賣家不用在線",
                "", "<yellow>左鍵：打開"));
        set(SLOT_EXCHANGE, icon(Material.GOLD_INGOT, "<#f5b942>通貨交易所",
                "<gray>掛單「用 A 換 B」，價格合得來就自動成交",
                "<gray>你掛著 " + service.book().of(player.getUniqueId()).size() + " 張單",
                "", "<yellow>左鍵：打開"));
        final List<Proceed> proceeds = service.proceedsOf(player.getUniqueId(), false);
        final List<String> lore = new ArrayList<>();
        if (proceeds.isEmpty()) {
            lore.add("<gray>目前沒有收入");
        } else {
            final Map<String, Integer> totals = new java.util.LinkedHashMap<>();
            for (final Proceed proceed : proceeds) {
                totals.merge(proceed.currencyId, proceed.amount, Integer::sum);
            }
            lore.add("<gray>賣掉的東西、交易所成交的通貨：");
            for (final Map.Entry<String, Integer> entry : totals.entrySet()) {
                lore.add("<white>　" + ItemRenderer.escape(service.wallet().price(entry.getKey(), entry.getValue())));
            }
            lore.add("<dark_gray>先放倉庫的通貨頁，滿了放通貨袋、背包");
            lore.add("");
            lore.add("<yellow>左鍵：全部領走");
        }
        final ItemStack proceedsIcon = icon(Material.EMERALD, "<#f5b942>領取收入（" + proceeds.size() + " 筆）", lore);
        set(SLOT_PROCEEDS, proceeds.isEmpty() ? proceedsIcon : glowing(proceedsIcon));
    }

    private ItemStack display(final Listing listing) {
        final ItemStack stack = listing.item.clone();
        final List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("<#f5b942>價格：" + ItemRenderer.escape(service.wallet().price(listing.priceCurrency, listing.priceAmount)));
        lines.add("<gray>賣家：<white>" + ItemRenderer.escape(listing.sellerName) + "</white>　<dark_gray>上架 "
                + MarketService.ago(listing.listedAt));
        if (service.isGear(listing)) {
            final String problem = service.requirementProblem(player, listing);
            lines.add(problem == null ? "<green>✔ 你穿得上" : "<red>✘ 你穿不上（要" + problem + "）");
        }
        if (listing.seller.equals(player.getUniqueId())) {
            lines.add("<dark_gray>這是你上架的");
        } else {
            lines.add(String.format(Locale.ROOT, "<yellow>左鍵：購買<dark_gray>（手續費 %,d 金幣）",
                    service.purchaseFee(player, listing.priceCurrency, listing.priceAmount)));
        }
        return appendLore(stack, lines);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < PAGE_SIZE) {
            if (slot < shown.size()) {
                final Listing listing = shown.get(slot);
                if (!listing.seller.equals(player.getUniqueId())) {
                    new BuyMenu(plugin, player, service, listing, state).open();
                }
            }
            return;
        }
        switch (slot) {
            case SLOT_SEARCH -> {
                if (click.isRightClick()) {
                    state.query = "";
                    state.category = Listing.Category.ALL;
                    state.rarity = null;
                    state.usable = false;
                    state.maxAmount = 0;
                    state.page = 0;
                    reopen();
                } else {
                    promptSearch();
                }
            }
            case SLOT_USABLE -> {
                state.usable = !state.usable;
                state.page = 0;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, state.usable ? 1.4f : 1.0f);
                redraw();
            }
            case SLOT_CATEGORY -> {
                final Listing.Category[] all = Listing.Category.values();
                state.category = click.isRightClick() ? Listing.Category.ALL : all[(state.category.ordinal() + 1) % all.length];
                state.page = 0;
                redraw();
            }
            case SLOT_RARITY -> {
                final Rarity[] all = Rarity.values();
                if (click.isRightClick()) {
                    state.rarity = null;
                } else {
                    state.rarity = state.rarity == null ? all[0] : state.rarity.ordinal() + 1 < all.length ? all[state.rarity.ordinal() + 1] : null;
                }
                state.page = 0;
                redraw();
            }
            case SLOT_SORT -> {
                state.sort = state.sort == MarketService.Sort.PRICE ? MarketService.Sort.NEWEST : MarketService.Sort.PRICE;
                redraw();
            }
            case SLOT_PAGE -> {
                if (click.isRightClick()) {
                    state.page = click.isShiftClick() ? 0 : Math.max(0, state.page - 1);
                } else {
                    state.page++; // 超過最後一頁時畫面會自己停在最後一頁
                }
                player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.0f);
                redraw();
            }
            case SLOT_SHOP -> new ShopMenu(plugin, player, service, state).open();
            case SLOT_EXCHANGE -> new ExchangeMenu(plugin, player, service, state).open();
            case SLOT_PROCEEDS -> {
                if (!acceptAction(click)) {
                    return;
                }
                final String problem = service.collect(player);
                if (problem != null) {
                    tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + problem));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                }
                redraw();
            }
            default -> {
            }
        }
    }

    /** 跳出搜尋表單：關鍵字、分類、稀有度、「我能用的」、價格上限、排序一次設好。 */
    private void promptSearch() {
        final List<tw.exilecore.gui.Dialogs.Option> categories = new ArrayList<>();
        for (final Listing.Category category : Listing.Category.values()) {
            categories.add(new tw.exilecore.gui.Dialogs.Option(category.name(), category.displayName()));
        }
        final List<tw.exilecore.gui.Dialogs.Option> rarities = new ArrayList<>();
        rarities.add(new tw.exilecore.gui.Dialogs.Option("ALL", "全部"));
        for (final Rarity rarity : Rarity.values()) {
            rarities.add(new tw.exilecore.gui.Dialogs.Option(rarity.name(), rarity.displayName()));
        }
        final List<tw.exilecore.gui.Dialogs.Option> sorts = new ArrayList<>();
        for (final MarketService.Sort sort : MarketService.Sort.values()) {
            sorts.add(new tw.exilecore.gui.Dialogs.Option(sort.name(), sort.displayName()));
        }
        final List<tw.exilecore.gui.Dialogs.Option> currencies = new ArrayList<>();
        for (final String id : service.config().values().keySet()) {
            final tw.exilecore.currency.CurrencyType type = plugin.currencies().repository().get(id);
            if (type != null && !type.uncutGem()) { // 標價不用未切割寶石
                currencies.add(new tw.exilecore.gui.Dialogs.Option(id, ItemRenderer.escape(service.currencyName(id))));
            }
        }
        final List<tw.exilecore.gui.Dialogs.Field> fields = new ArrayList<>(List.of(
                new tw.exilecore.gui.Dialogs.Text("query", "關鍵字", state.query, 50),
                new tw.exilecore.gui.Dialogs.Choice("category", "分類", categories, state.category.name()),
                new tw.exilecore.gui.Dialogs.Choice("rarity", "稀有度", rarities, state.rarity == null ? "ALL" : state.rarity.name()),
                new tw.exilecore.gui.Dialogs.Toggle("usable", "只看我穿得上的裝備", state.usable),
                new tw.exilecore.gui.Dialogs.Text("max", "價格上限（數量，空白＝不限）", state.maxAmount > 0 ? Integer.toString(state.maxAmount) : "", 6)));
        if (!currencies.isEmpty()) {
            fields.add(new tw.exilecore.gui.Dialogs.Choice("maxCurrency", "上限的通貨", currencies,
                    state.maxCurrency != null ? state.maxCurrency : currencies.getFirst().id()));
        }
        fields.add(new tw.exilecore.gui.Dialogs.Choice("sort", "排序", sorts, state.sort.name()));
        plugin.dialogs().form(player, "<dark_gray>市集搜尋",
                List.of("<gray>名稱、詞綴、基底都找得到；空白隔開＝每個都要有",
                        "<dark_gray>關鍵字空白＝全部；價格上限：別種通貨標價的照參考比例換算比較"),
                fields, "搜尋", answers -> {
                    state.query = answers.get("query").trim();
                    state.category = parse(Listing.Category.class, answers.get("category"), Listing.Category.ALL);
                    state.rarity = answers.get("rarity").equals("ALL") ? null : parse(Rarity.class, answers.get("rarity"), null);
                    state.usable = answers.on("usable");
                    state.sort = parse(MarketService.Sort.class, answers.get("sort"), MarketService.Sort.PRICE);
                    final String max = answers.get("max").trim();
                    state.maxAmount = 0;
                    if (!max.isEmpty()) {
                        try {
                            state.maxAmount = Math.max(0, Integer.parseInt(max)); // 欄位最多 6 位數
                        } catch (final NumberFormatException e) {
                            player.sendMessage(plugin.text().prefixed("<red>價格上限要是數字，這次不限"));
                        }
                    }
                    final String maxCurrency = answers.get("maxCurrency");
                    state.maxCurrency = maxCurrency.isEmpty() ? null : maxCurrency;
                    if (state.maxCurrency == null) {
                        state.maxAmount = 0;
                    }
                    state.page = 0;
                    reopen();
                }, this::reopen);
    }

    /** 「我能用的」與價格上限。 */
    private boolean passes(final Listing listing) {
        if (state.usable && service.requirementProblem(player, listing) != null) {
            return false;
        }
        if (state.maxAmount > 0 && state.maxCurrency != null) {
            final long value = service.config().value(listing.priceCurrency, listing.priceAmount);
            return value > 0 && value <= service.config().value(state.maxCurrency, state.maxAmount); // 價值不明的不算在上限內
        }
        return true;
    }

    private static <E extends Enum<E>> E parse(final Class<E> type, final String name, final E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (final IllegalArgumentException e) {
            return fallback;
        }
    }

    /** 標題會變（搜尋字），所以重開一個。 */
    private void reopen() {
        new MarketMenu(plugin, player, service, state).open();
    }
}
