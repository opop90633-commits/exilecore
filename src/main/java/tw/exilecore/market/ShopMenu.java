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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 我的商店頁：上面是你上架的東西（左鍵下架、右鍵改價），點你背包裡的東西開始上架（下一步選價格）。
 */
final class ShopMenu extends Menu {

    private static final int SLOT_INFO = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_PAGE_PREV = 47;
    private static final int SLOT_PAGE_NEXT = 51;
    private static final int PER_PAGE = 45;
    /** 2026-10：上架格可以買很多，一頁 45 件、◀ ▶ 換頁。 */
    private int page;

    private final MarketService service;
    private final MarketMenu.State state;
    private final List<Listing> shown = new ArrayList<>();

    ShopMenu(final ExileCore plugin, final Player player, final MarketService service, final MarketMenu.State state) {
        super(plugin, player, 6, "<dark_gray>我的商店頁");
        this.service = service;
        this.state = state;
    }

    @Override
    protected void draw() {
        shown.clear();
        shown.addAll(service.shopOf(player.getUniqueId()));
        final int capacity = service.shopSlots(player);
        page = Math.max(0, Math.min(page, pages(capacity) - 1));
        for (int slot = 0; slot < PER_PAGE; slot++) {
            final int i = page * PER_PAGE + slot;
            if (i < shown.size()) {
                set(slot, display(shown.get(i)));
            } else if (i < capacity) {
                set(slot, null);
            } else {
                set(slot, icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>（商店頁最多 " + capacity + " 件）"));
            }
        }
        for (int i = 45; i < 54; i++) {
            set(i, filler());
        }
        if (pages(capacity) > 1) {
            set(SLOT_PAGE_PREV, jumpButton(false, page, pages(capacity)));
            set(SLOT_PAGE_NEXT, jumpButton(true, page, pages(capacity)));
        }
        set(SLOT_INFO, icon(Material.BOOK, "<white>商店頁（" + shown.size() + " / " + capacity + "）",
                "<gray>點你背包裡的東西：上架（下一步選價格）",
                "<gray>上架後東西離開背包，放在市集裡",
                "<gray>別人在市集買走時你不用在線，收入到市集領（/market）",
                "",
                String.format(Locale.ROOT, "<gray>上架手續費：東西估價的 %s%%（至少 %,d 金幣，跟標價無關），改價也算一次",
                        trim(service.config().listingFee().percent()), service.config().listingFee().min()),
                "<gray>上架 " + service.config().relistMinutes() + " 分鐘內不能下架或改價",
                "<dark_gray>金幣不能上架"));
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回市集"));
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack display(final Listing listing) {
        final List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("<#f5b942>標價：" + ItemRenderer.escape(service.wallet().price(listing.priceCurrency, listing.priceAmount)));
        lines.add("<dark_gray>上架 " + MarketService.ago(listing.listedAt));
        if (!listing.confirmed) {
            lines.add("<yellow>存檔中…");
        } else {
            final long locked = service.lockedFor(listing);
            if (locked > 0) {
                lines.add("<gray>還要 " + MarketService.duration(locked) + " 才能下架或改價");
            } else {
                lines.add("<yellow>左鍵：下架（回到背包）");
                lines.add("<yellow>右鍵：改價");
            }
        }
        return appendLore(listing.item.clone(), lines);
    }

    private int pages(final int capacity) {
        return Math.max(1, (Math.max(capacity, shown.size()) + PER_PAGE - 1) / PER_PAGE);
    }

    static String trim(final double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            new MarketMenu(plugin, player, service, state).open();
            return;
        }
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (slot == SLOT_PAGE_PREV || slot == SLOT_PAGE_NEXT) {
            final int pages = pages(service.shopSlots(player));
            if (pages > 1) {
                page = stepPage(page, pages, slot == SLOT_PAGE_NEXT, click);
                redraw();
            }
            return;
        }
        final int index = page * PER_PAGE + slot;
        if (slot >= PER_PAGE || index >= shown.size()) {
            return;
        }
        final Listing listing = shown.get(index);
        if (click.isRightClick()) {
            if (!listing.confirmed || service.lockedFor(listing) > 0) {
                fail(!listing.confirmed ? "上架還在存檔中，請稍等"
                        : "上架 " + service.config().relistMinutes() + " 分鐘內不能改價（還要 " + MarketService.duration(service.lockedFor(listing)) + "）");
                return;
            }
            new PriceMenu(plugin, player, service, state, listing).open();
            return;
        }
        if (!acceptAction(click)) {
            return;
        }
        final String problem = service.unlist(player, listing.id);
        if (problem != null) {
            fail(problem);
            return;
        }
        player.sendMessage(plugin.text().prefixed("<gray>已下架：").append(listing.item.effectiveName())
                .append(plugin.text().parse("<gray>（回到背包）")));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        redraw();
    }

    @Override
    protected void onBottomClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot < 0 || slot >= 36) {
            return;
        }
        final ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (plugin.gold().amountOf(stack).isPresent()) {
            fail("金幣不能上架");
            return;
        }
        if (tw.exilecore.flask.FlaskService.isPlaceholder(stack)) {
            fail("這是空的藥劑欄（提示），不是東西");
            return;
        }
        if (shown.size() >= service.shopSlots(player)) {
            fail("商店頁滿了（" + service.shopSlots(player) + " 件）：先下架一些" + service.slotsHint(player));
            return;
        }
        new PriceMenu(plugin, player, service, state, slot, stack.clone()).open();
    }

    private void fail(final String message) {
        player.sendActionBar(plugin.text().parse("<red>" + message));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
    }
}
