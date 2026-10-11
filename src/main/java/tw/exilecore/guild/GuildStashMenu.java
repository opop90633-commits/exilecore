package tw.exilecore.guild;

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
import tw.exilecore.stash.StashCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * 公會倉庫（/guild stash，或公會選單裡的「公會倉庫」）：上面五排是這一頁的 45 格，最下面一排是工具。
 * <ul>
 *   <li>點上面的東西：拿到自己的背包（要有權限：會長設定的「誰能拿」）。</li>
 *   <li>點自己背包裡的東西：放進這一頁；Shift＋點：照頁籤的分類放到對的頁。</li>
 *   <li>最下面一排（2026-10）：回公會、這一頁的頁籤（左鍵全部頁籤一覽、右鍵改名稱與分類）、搜尋、◀、說明、▶、整理這一頁、關閉。</li>
 * </ul>
 * 不能拖、不能用游標拿著放（好幾個人同時開著，一次搬一件，每一步都對過才動）。別人放、拿東西時畫面會跟著更新。
 * <p>
 * 搜尋（跟個人倉庫一樣）：符合的發光、不符合的變成灰色玻璃，頁籤上寫這一頁與其他頁各有幾件；搜尋中只能看不能搬，
 * 點一件符合的東西或「結束搜尋」回到一般畫面。
 */
public final class GuildStashMenu extends Menu {

    private static final int SLOT_BACK_TO_GUILD = SLOT_BACK;
    /** 這一頁的頁籤。 */
    private static final int SLOT_TAB = SLOT_BACK + 1;
    private static final int SLOT_SEARCH = SLOT_BACK + 2;
    private static final int SLOT_INFO = SLOT_HELP;
    /** 整理這一頁；搜尋中是「結束搜尋」。 */
    private static final int SLOT_SORT = SLOT_NEXT + 1;

    private final GuildService service;
    private final long guildId;
    /** 管理員查看別的公會的倉庫（/guild admin stash）：只能看，不能拿放。 */
    private final boolean viewOnly;
    private int page;
    /** 搜尋的關鍵字（空的 = 沒有在搜尋）。 */
    private String query = "";
    private List<String> terms = List.of();

    GuildStashMenu(final ExileCore plugin, final Player player, final GuildService service, final long guildId, final int page) {
        this(plugin, player, service, guildId, page, false, null);
    }

    GuildStashMenu(final ExileCore plugin, final Player player, final GuildService service, final long guildId, final int page,
                   final boolean viewOnly, final String search) {
        super(plugin, player, 6, GuildService.COLOR + (viewOnly ? "公會倉庫（管理員查看）" : "公會倉庫")
                + (search == null || search.isBlank() ? "" : "：搜尋「" + ItemRenderer.escape(search.trim()) + "」"));
        this.service = service;
        this.guildId = guildId;
        this.page = page;
        this.viewOnly = viewOnly;
        if (search != null && !search.isBlank()) {
            this.query = search.trim();
            this.terms = MarketSearch.terms(search);
        }
    }

    long guildId() {
        return guildId;
    }

    private boolean searching() {
        return !terms.isEmpty();
    }

    private boolean matches(final ItemStack stack) {
        return stack != null && !stack.isEmpty() && MarketSearch.matches(ItemSearch.text(plugin.items(), stack), terms);
    }

    /** 這一頁有幾件符合搜尋。 */
    static int matchesOn(final ExileCore plugin, final GuildStash stash, final int page, final List<String> terms) {
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
    protected void onOpen() {
        service.stashOpened(player, this);
    }

    @Override
    protected void onClose() {
        service.stashClosed(player, this);
    }

    @Override
    protected void draw() {
        final Guild guild = service.guild(guildId);
        final GuildStash stash = service.stash(guildId);
        final ItemStack bar = bar();
        for (int i = 0; i < 9; i++) {
            set(SLOT_BACK + i, bar);
        }
        if (guild == null || stash == null) {
            return;
        }
        page = Math.max(0, Math.min(page, stash.pageCount() - 1));
        final ItemStack[] items = stash.page(page);
        for (int i = 0; i < GuildStash.PAGE_SIZE; i++) {
            if (items[i] == null || items[i].isEmpty()) {
                set(i, null);
            } else if (!searching()) {
                set(i, items[i].clone());
            } else if (matches(items[i])) {
                set(i, highlight(appendLore(items[i].clone(), "", "<#f5b942>✔ 符合搜尋", "<yellow>左鍵：結束搜尋，停在這一頁")));
            } else {
                set(i, icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>（不符合搜尋）"));
            }
        }
        final int pages = stash.pageCount();
        final int usable = service.usablePages(guild);
        final boolean locked = page >= usable;
        final GuildRank mine = guild.rank(player.getUniqueId());
        final boolean canTake = !viewOnly && mine != null && mine.atLeast(guild.withdrawRank());
        final boolean canManage = !viewOnly && service.canManageStash(guild, player);
        set(SLOT_BACK_TO_GUILD, viewOnly ? closeButton() : backButton("公會"));
        if (pages > 1) {
            set(SLOT_PREV, jumpButton(false, page, pages)); // 頁數多的時候：Shift 跳 10 頁、右鍵到頭尾
            set(SLOT_NEXT, jumpButton(true, page, pages));
        }
        // 這一頁的頁籤
        final StashCategory category = stash.tabCategory(page);
        final List<String> tab = new ArrayList<>();
        tab.add("<gray>第 " + (page + 1) + "／" + pages + " 頁・放：<white>" + category.displayName() + (category == StashCategory.ALL ? "（什麼都放）" : ""));
        tab.add("<gray>" + stash.itemCount(page) + " / " + GuildStash.PAGE_SIZE + " 格有東西");
        if (locked) {
            tab.add("<red>這一頁現在只能拿出來，不能放（公會等級的頁數變少了）");
        }
        if (searching()) {
            int total = 0;
            for (int i = 0; i < pages; i++) {
                total += matchesOn(plugin, stash, i, terms);
            }
            final int here = matchesOn(plugin, stash, page, terms);
            tab.add("<#f5b942>這一頁符合 " + here + " 件" + (total > here ? "，其他頁 " + (total - here) + " 件" : ""));
        }
        tab.add("");
        tab.add("<yellow>左鍵：全部頁籤一覽<dark_gray>（換頁、看每頁有什麼、重新分頁）");
        if (canManage) {
            tab.add("<yellow>右鍵：改這一頁的名稱與分類");
        }
        set(SLOT_TAB, icon(locked ? Material.BARRIER : category == StashCategory.ALL ? Material.ENDER_CHEST : Menu.material(category.icon(), Material.CHEST),
                "<#f5b942>▶ " + ItemRenderer.escape(stash.tabLabel(page)), tab));
        set(SLOT_SEARCH, icon(Material.SPYGLASS, "<#f5b942>搜尋",
                searching() ? "<gray>目前：<white>" + ItemRenderer.escape(query) : "<gray>名稱、詞綴、基底、稀有度都找得到（全部頁一起找）",
                "<dark_gray>空白隔開＝都要有，例如「火焰 抗性」", "", "<yellow>左鍵：輸入關鍵字"));
        if (searching()) {
            set(SLOT_SORT, icon(Material.BARRIER, "<white>結束搜尋", "<gray>回到可以搬東西的畫面（停在這一頁）", "", "<yellow>左鍵：結束"));
        } else if (canTake) {
            set(SLOT_SORT, icon(Material.COMPARATOR, "<#7fd4ff>整理這一頁", "<gray>同樣的疊在一起，照種類、稀有度、等級排好",
                    "<dark_gray>全部頁照分類重新分頁在「全部頁籤一覽」裡（會長、幹部）", "", "<yellow>左鍵：整理"));
        }
        final List<String> info = new ArrayList<>();
        info.add("<gray>" + GuildService.label(guild) + " <gray>Lv." + guild.level() + "，倉庫 " + usable + " 頁");
        info.add("");
        if (viewOnly) {
            info.add("<#f5b942>管理員查看：<white>只能看，不能拿、不能放");
            info.add("<gray>搜尋、換頁、全部頁籤一覽都可以用");
            info.add("<gray>誰放誰拿看 /guild admin log 公會");
        } else {
            info.add("<yellow>點自己背包裡的東西：<white>放進這一頁");
            info.add("<yellow>Shift＋點背包裡的東西：<white>照頁籤的分類放到對的頁");
            info.add(canTake ? "<yellow>點上面的東西：<white>拿到自己的背包"
                    : "<red>你不能拿東西<gray>（現在" + GuildService.withdrawText(guild.withdrawRank()) + "才能拿）");
            info.add("");
            info.add("<gray>一次搬一件（一疊算一件），不能拖。");
            info.add("<gray>誰放、誰拿、誰整理都有紀錄（公會選單的「紀錄」）。");
            info.add("<gray>金幣、鎖定的裝備、容器不能放。");
            info.add("<gray>頁籤的名稱與分類、全部頁重新分頁：會長與幹部。");
            final int nextAt = nextPageLevel(guild);
            if (nextAt > 0) {
                info.add("<dark_gray>公會 Lv." + nextAt + " 多一頁");
            }
        }
        set(SLOT_INFO, helpButton(info));
        set(SLOT_CLOSE_BAR, closeButton());
    }

    /** 下一次多一頁是公會幾級；沒有了回傳 0。 */
    private int nextPageLevel(final Guild guild) {
        final int now = service.config().stashPages(guild.level());
        for (int level = guild.level() + 1; level <= service.config().maxLevel(); level++) {
            if (service.config().stashPages(level) > now) {
                return level;
            }
        }
        return 0;
    }

    private void endSearch() {
        terms = List.of();
        query = "";
        redraw();
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        final GuildStash stash = service.stash(guildId);
        if (slot >= 0 && slot < GuildStash.PAGE_SIZE) {
            if (searching()) {
                // 搜尋中只能看：點符合的東西＝結束搜尋，停在這一頁
                if (stash != null && page < stash.pageCount() && matches(stash.page(page)[slot])) {
                    endSearch();
                }
            } else if (!viewOnly && acceptAction(click)) {
                service.withdraw(player, this, page, slot);
            }
            return;
        }
        final int pages = stash == null ? 1 : stash.pageCount();
        switch (slot) {
            case SLOT_BACK_TO_GUILD -> {
                if (viewOnly) {
                    goBack();
                } else {
                    service.openMenu(player);
                }
            }
            case SLOT_TAB -> {
                if (stash == null) {
                    return;
                }
                final int here = page;
                final String keep = searching() ? query : null;
                if (click.isRightClick() && !viewOnly) {
                    service.editStashTab(player, guildId, here, () -> service.reopenStash(player, guildId, here, keep, false));
                } else {
                    new GuildStashTabsMenu(plugin, player, service, guildId, here, keep, viewOnly).open();
                }
            }
            case SLOT_SEARCH -> {
                final int here = page;
                final String before = searching() ? query : null;
                plugin.dialogs().text(player, "<dark_gray>公會倉庫搜尋", List.of("<gray>名稱、詞綴、基底都找得到；空白隔開＝每個都要有", "<gray>全部頁一起找"),
                        "關鍵字", query, 50,
                        text -> service.reopenStash(player, guildId, here, text, viewOnly),
                        () -> service.reopenStash(player, guildId, here, before, viewOnly));
            }
            case SLOT_SORT -> {
                if (searching()) {
                    endSearch();
                } else if (!viewOnly && acceptAction(click)) {
                    service.sortStashPage(player, this, page);
                }
            }
            case SLOT_PREV -> {
                if (pages > 1) {
                    page = stepPage(page, pages, false, click);
                    redraw();
                }
            }
            case SLOT_NEXT -> {
                if (pages > 1) {
                    page = stepPage(page, pages, true, click);
                    redraw();
                }
            }
            case SLOT_CLOSE_BAR -> goBack();
            default -> {
            }
        }
    }

    @Override
    protected void onBottomClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < 0 || slot >= 36 || viewOnly) {
            return; // 只收背包主要的 36 格（防具、副手不在這個畫面上）
        }
        if (searching()) {
            player.sendActionBar(plugin.text().parse("<gray>搜尋中只能看：先按「結束搜尋」再放東西"));
            return;
        }
        if (acceptAction(click)) {
            if (click.isShiftClick()) {
                service.depositByCategory(player, this, slot);
            } else {
                service.deposit(player, this, page, slot);
            }
        }
    }
}
