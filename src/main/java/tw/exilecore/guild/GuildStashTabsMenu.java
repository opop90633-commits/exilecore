package tw.exilecore.guild;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.market.MarketSearch;
import tw.exilecore.stash.StashCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * 公會倉庫的「全部頁籤」一覽（2026-10，倉庫最下面一排點這一頁的頁籤打開）：全部的頁一次看完——名稱、分類、幾格有東西，
 * 搜尋中每一頁標出幾件符合。左鍵打開那一頁；會長與幹部右鍵改名稱與分類、下面可以把全部頁照分類重新分頁。
 */
final class GuildStashTabsMenu extends Menu {

    private static final int SLOT_INFO = 4;
    private static final int PAGES_START = CONTENT_START;
    private static final int PAGES_END = CONTENT_END;
    private static final int PER_SCREEN = PAGES_END - PAGES_START;
    private static final int SLOT_SCREEN_PREV = 46;
    private static final int SLOT_SORT_ALL = 50;
    private static final int SLOT_SCREEN_NEXT = 52;

    private final GuildService service;
    private final long guildId;
    private final int current;
    /** 搜尋的關鍵字（null = 沒有在搜尋）。 */
    private final String search;
    private final List<String> terms;
    private final boolean viewOnly;
    /** 頁很多的時候一次看 36 頁，◀ ▶ 換批。 */
    private int screen;

    GuildStashTabsMenu(final ExileCore plugin, final Player player, final GuildService service, final long guildId, final int current,
                       final String search, final boolean viewOnly) {
        super(plugin, player, 6, GuildService.COLOR + "公會倉庫：全部頁籤");
        this.service = service;
        this.guildId = guildId;
        this.current = current;
        this.search = search == null || search.isBlank() ? null : search;
        this.terms = this.search == null ? List.of() : MarketSearch.terms(this.search);
        this.viewOnly = viewOnly;
        this.screen = Math.max(0, current) / PER_SCREEN;
    }

    private static int screens(final GuildStash stash) {
        return Math.max(1, (stash.pageCount() + PER_SCREEN - 1) / PER_SCREEN);
    }

    @Override
    protected void draw() {
        frame();
        final Guild guild = service.guild(guildId);
        final GuildStash stash = service.stash(guildId);
        set(SLOT_BACK, backButton("公會倉庫"));
        set(SLOT_CLOSE_BAR, closeButton());
        if (guild == null || stash == null) {
            return;
        }
        final boolean canManage = !viewOnly && service.canManageStash(guild, player);
        final int usable = service.usablePages(guild);
        set(SLOT_INFO, icon(Material.BOOKSHELF, "<#f5b942>公會倉庫的頁籤（" + stash.pageCount() + " 頁，共 " + stash.itemCount() + " 件）",
                "<gray>左鍵：打開那一頁" + (canManage ? "　右鍵：改名稱與分類" : ""),
                search == null ? "<dark_gray>公會升級、星幣可以多加倉庫頁" : "<#f5b942>搜尋「" + ItemRenderer.escape(search) + "」：發光的頁有符合的"));
        screen = Math.min(screen, screens(stash) - 1);
        for (int i = 0; i < PER_SCREEN; i++) {
            final int page = screen * PER_SCREEN + i;
            if (page < stash.pageCount()) {
                set(PAGES_START + i, pageIcon(stash, page, page >= usable, canManage));
            }
        }
        if (screens(stash) > 1) {
            set(SLOT_SCREEN_PREV, pageButton(false, screen, screens(stash)));
            set(SLOT_SCREEN_NEXT, pageButton(true, screen, screens(stash)));
        }
        if (canManage) {
            set(SLOT_SORT_ALL, icon(Material.COMPARATOR, "<#7fd4ff>全部頁照分類重新分頁",
                    "<gray>所有頁的東西照頁籤的分類重新放：",
                    "<gray>先放分類一樣的頁，再放大類的頁，最後放「全部」的頁，每頁疊好排好",
                    "<dark_gray>（分類在頁籤上右鍵設定；會記在公會紀錄裡）", "", "<yellow>左鍵：重新分頁"));
        }
        set(SLOT_HELP, helpButton(List.of(
                "每一頁可以取名字、設定放什麼（武器、副手、頭、身、腿、腳、戒指、項鍊、腰帶、珠寶、護符、其他，",
                "或「防具」「飾品」這種大類、全部）——會長與幹部在頁籤上右鍵設定",
                "倉庫裡 Shift＋點背包的東西：照分類放到對的頁；直接點：放進現在那一頁",
                "搜尋：名稱、詞綴、基底、稀有度都找得到，全部頁一起找，符合的會發光",
                "整理這一頁：能拿東西的人；全部頁重新分頁：會長與幹部")));
    }

    private ItemStack pageIcon(final GuildStash stash, final int page, final boolean locked, final boolean canManage) {
        final StashCategory category = stash.tabCategory(page);
        final boolean here = page == current;
        final int found = GuildStashMenu.matchesOn(plugin, stash, page, terms);
        final List<String> lore = new ArrayList<>();
        lore.add("<gray>第 " + (page + 1) + " 頁・放：<white>" + category.displayName() + (category == StashCategory.ALL ? "（什麼都放）" : ""));
        lore.add("<gray>" + stash.itemCount(page) + " / " + GuildStash.PAGE_SIZE + " 格有東西");
        if (locked) {
            lore.add("<red>只能拿出來，不能放（公會等級的頁數變少了）");
        }
        if (search != null) {
            lore.add(found > 0 ? "<#f5b942>符合搜尋 " + found + " 件" : "<dark_gray>沒有符合搜尋的");
        }
        lore.add("");
        lore.add(here ? "<green>剛才在這一頁" : "<yellow>左鍵：打開這一頁");
        if (canManage) {
            lore.add("<yellow>右鍵：改名稱與分類");
        }
        final ItemStack icon = icon(locked ? Material.BARRIER : category == StashCategory.ALL ? (here ? Material.ENDER_CHEST : Material.CHEST)
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
            service.reopenStash(player, guildId, current, search, viewOnly);
            return;
        }
        final Guild guild = service.guild(guildId);
        final GuildStash stash = service.stash(guildId);
        if (guild == null || stash == null) {
            player.closeInventory();
            return;
        }
        if (slot == SLOT_SORT_ALL && !viewOnly) {
            if (acceptAction(click)) {
                service.sortStashAll(player, guildId);
                redraw();
            }
            return;
        }
        if ((slot == SLOT_SCREEN_PREV || slot == SLOT_SCREEN_NEXT) && screens(stash) > 1) {
            screen = Math.floorMod(screen + (slot == SLOT_SCREEN_NEXT ? 1 : -1), screens(stash));
            redraw();
            return;
        }
        final int page = screen * PER_SCREEN + slot - PAGES_START;
        if (slot < PAGES_START || slot >= PAGES_END || page >= stash.pageCount()) {
            return;
        }
        if (click.isRightClick() && !viewOnly && service.canManageStash(guild, player)) {
            // 改完或取消都回到這個一覽
            service.editStashTab(player, guildId, page,
                    () -> new GuildStashTabsMenu(plugin, player, service, guildId, current, search, viewOnly).open());
        } else {
            service.reopenStash(player, guildId, page, search, viewOnly);
        }
    }
}
