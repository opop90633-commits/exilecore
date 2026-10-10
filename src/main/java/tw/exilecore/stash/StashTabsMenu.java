package tw.exilecore.stash;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.item.ItemSearch;
import tw.exilecore.market.MarketSearch;

import java.util.ArrayList;
import java.util.List;

/**
 * 倉庫的「全部頁籤」一覽（2026-10，倉庫最下面一排點這一頁的頁籤打開）：全部的頁一次看完。
 * 左鍵打開那一頁、右鍵改名稱與分類；下面是通貨頁與「全部頁照分類重新分頁」。搜尋中每一頁標出幾件符合。
 * 打開這裡時倉庫已經關掉並存好，選了之後重新打開倉庫停在那一頁。
 */
final class StashTabsMenu extends Menu {

    private static final int SLOT_INFO = 4;
    private static final int PAGES_START = CONTENT_START;
    /** 2026-10：頁數上限 32（累積贊助、星幣買的頁），頁籤排滿中間四排；工具移到最下面一排。 */
    private static final int PAGES_END = CONTENT_END;
    private static final int SLOT_CURRENCY = 47;
    private static final int SLOT_MAPS = 48;
    private static final int SLOT_SORT_ALL = 50;
    private static final int SLOT_SPONSOR = 51;
    private static final int SLOT_SCREEN_PREV = 46;
    private static final int SLOT_SCREEN_NEXT = 52;
    private static final int PER_SCREEN = CONTENT_END - CONTENT_START;
    /** 頁很多的時候一次看 36 頁，◀ ▶ 換批。 */
    private int screen;

    private final StashService service;
    private final Stash stash;
    private final int current;
    /** 搜尋的關鍵字（null = 沒有在搜尋）。 */
    private final String search;
    private final List<String> terms;

    StashTabsMenu(final ExileCore plugin, final Player player, final StashService service, final Stash stash, final int current,
                  final String search) {
        super(plugin, player, 6, "<dark_gray>倉庫：全部頁籤");
        this.service = service;
        this.stash = stash;
        this.current = current;
        this.search = search == null || search.isBlank() ? null : search;
        this.terms = this.search == null ? List.of() : MarketSearch.terms(this.search);
        this.screen = Math.max(0, current) / PER_SCREEN;
    }

    private int screens() {
        return Math.max(1, (stash.pageCount() + PER_SCREEN - 1) / PER_SCREEN);
    }

    private int matchesOn(final int page) {
        if (terms.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (final ItemStack stack : stash.page(page)) {
            if (stack != null && !stack.isEmpty() && MarketSearch.matches(ItemSearch.text(plugin.items(), stack), terms)) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected void draw() {
        frame();
        set(SLOT_INFO, icon(Material.BOOKSHELF, "<#f5b942>倉庫的頁籤（" + stash.pageCount() + " 頁）",
                "<gray>左鍵：打開那一頁　右鍵：改名稱與分類",
                search == null ? "<dark_gray>功勳商店可以多換倉庫頁" : "<#f5b942>搜尋「" + ItemRenderer.escape(search) + "」：發光的頁有符合的"));
        screen = Math.min(screen, screens() - 1);
        for (int i = 0; i < PER_SCREEN; i++) {
            final int page = screen * PER_SCREEN + i;
            if (page < stash.pageCount()) {
                set(PAGES_START + i, pageIcon(page));
            }
        }
        if (screens() > 1) {
            set(SLOT_SCREEN_PREV, pageButton(false, screen, screens()));
            set(SLOT_SCREEN_NEXT, pageButton(true, screen, screens()));
        }
        int kinds = 0;
        for (final int amount : stash.currencies().values()) {
            if (amount > 0) {
                kinds++;
            }
        }
        set(SLOT_CURRENCY, icon(Material.SUNFLOWER, "<white>通貨頁", "<gray>存了 " + kinds + " 種通貨", "", "<yellow>左鍵：打開"));
        set(SLOT_MAPS, icon(Material.FILLED_MAP, "<white>地圖頁", "<gray>存了 " + stash.mapCount() + " / " + stash.mapCap() + " 張地圖",
                "<gray>照階級（T1–T16）分開放", "", "<yellow>左鍵：打開"));
        set(SLOT_SORT_ALL, icon(Material.COMPARATOR, "<#7fd4ff>全部頁照分類重新分頁",
                "<gray>所有物品頁的東西照頁籤的分類重新放：",
                "<gray>先放分類一樣的頁，再放「全部」的頁，每頁疊好排好",
                "<dark_gray>（分類在頁籤上右鍵設定）", "", "<yellow>左鍵：重新分頁"));
        if (plugin.sponsors() != null && plugin.sponsors().loaded(player)) {
            // 2026-10：贊助頁（月卡發的月卡幣、時裝幣放這裡）
            final var sponsors = plugin.sponsors();
            set(SLOT_SPONSOR, icon(Material.GOLD_NUGGET, "<#f5b942>贊助頁",
                    String.format(java.util.Locale.ROOT, "<gray>星幣 <white>%,d", sponsors.coins(player)),
                    "<gray>月卡幣 <white>" + sponsors.cardCoins(player) + "<gray>　時裝幣 <white>" + sponsors.fashionCoins(player),
                    "<dark_gray>月卡每天換日發到這裡", "", "<yellow>左鍵：打開贊助"));
        }
        set(SLOT_BACK, backButton("倉庫"));
        set(SLOT_HELP, helpButton(List.of(
                "每一頁可以取名字、設定放什麼（武器、副手、頭、身、腿、腳、戒指、項鍊、腰帶、珠寶、護符、其他，",
                "或「防具」「飾品」這種大類、全部）",
                "「收進倉庫」和「重新分頁」照分類放：先放分類一樣的頁，再放大類的頁，最後放「全部」的頁",
                "通貨進通貨頁、地圖進地圖頁（地圖頁滿了才放物品頁）",
                "倉庫裡：物品頁跟箱子一樣拖拉；◀ ▶ 換頁（Shift 跳 10 頁、右鍵到第一頁／最後一頁）；點這一頁的頁籤回到這裡",
                "搜尋：名稱、詞綴、基底、稀有度都找得到，符合的會發光")));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    private ItemStack pageIcon(final int page) {
        final StashCategory category = stash.tabCategory(page);
        final boolean here = page == current;
        final int found = matchesOn(page);
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>第 " + (page + 1) + " 頁・放：<white>" + category.displayName() + (category == StashCategory.ALL ? "（什麼都放）" : ""));
        lore.add("<gray>" + stash.itemCount(page) + " / " + Stash.PAGE_SIZE + " 格有東西");
        if (search != null) {
            lore.add(found > 0 ? "<#f5b942>符合搜尋 " + found + " 件" : "<dark_gray>沒有符合搜尋的");
        }
        lore.add("");
        lore.add(here ? "<green>剛才在這一頁" : "<yellow>左鍵：打開這一頁");
        lore.add("<yellow>右鍵：改名稱與分類");
        final ItemStack icon = icon(category == StashCategory.ALL ? (here ? Material.ENDER_CHEST : Material.CHEST)
                        : Menu.material(category.icon(), Material.CHEST),
                (here ? "<#f5b942>▶ " : "<white>") + ItemRenderer.escape(stash.tabLabel(page)), lore);
        if (found > 0) {
            withCount(icon, found);
        }
        return here ? highlight(icon) : found > 0 ? glowing(icon) : icon;
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_CLOSE_BAR) {
            goBack();
            return;
        }
        if (slot == SLOT_BACK) {
            service.open(player, false, search, current);
            return;
        }
        if (slot == SLOT_CURRENCY) {
            service.open(player, false, null, Stash.MAX_PAGES, false, current); // 通貨頁（「← 回物品頁」回到剛才那一頁）
            return;
        }
        if (slot == SLOT_MAPS) {
            service.open(player, false, null, StashMenu.MAP_PAGE, false, current); // 地圖頁（「← 回物品頁」回到剛才那一頁）
            return;
        }
        if (slot == SLOT_SORT_ALL) {
            if (!acceptAction(click)) {
                return;
            }
            new StashOrganizer(plugin).sortAll(stash);
            plugin.players().markDirty(player);
            plugin.characters().save(player);
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
            player.sendActionBar(plugin.text().parse("<green>全部頁照分類整理好了"));
            redraw();
            return;
        }
        if ((slot == SLOT_SCREEN_PREV || slot == SLOT_SCREEN_NEXT) && screens() > 1) {
            screen = Math.floorMod(screen + (slot == SLOT_SCREEN_NEXT ? 1 : -1), screens());
            redraw();
            return;
        }
        final int page = screen * PER_SCREEN + slot - PAGES_START;
        if (slot == SLOT_SPONSOR && plugin.sponsors() != null) {
            tw.exilecore.sponsor.SponsorModule.openMenu(plugin, player);
            return;
        }
        if (slot < PAGES_START || page >= stash.pageCount() || slot >= PAGES_END) {
            return;
        }
        if (click.isRightClick()) {
            // 改完或取消都回到這個一覽
            final Runnable back = () -> new StashTabsMenu(plugin, player, service, stash, current, search).open();
            StashMenu.editTab(plugin, player, stash, page, back, back);
        } else {
            service.open(player, false, search, page);
        }
    }
}
