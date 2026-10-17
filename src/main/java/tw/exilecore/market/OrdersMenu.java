package tw.exilecore.market;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;

/** 我的掛單：看成交了多少，左鍵取消（沒成交的部分退回）。 */
final class OrdersMenu extends Menu {

    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;

    private final MarketService service;
    private final MarketMenu.State state;
    private final ExchangeMenu.Choice choice;
    private final List<ExchangeBook.Order> shown = new ArrayList<>();

    OrdersMenu(final ExileCore plugin, final Player player, final MarketService service, final MarketMenu.State state,
               final ExchangeMenu.Choice choice) {
        super(plugin, player, 6, "<dark_gray>我的掛單");
        this.service = service;
        this.state = state;
        this.choice = choice;
    }

    @Override
    protected void draw() {
        shown.clear();
        shown.addAll(service.book().of(player.getUniqueId()));
        for (int i = 0; i < 45; i++) {
            set(i, i < shown.size() ? display(shown.get(i)) : null);
        }
        if (shown.isEmpty()) {
            set(22, icon(Material.GLASS_PANE, "<gray>沒有掛單"));
        }
        for (int i = 45; i < 54; i++) {
            set(i, filler());
        }
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回交易所"));
        set(SLOT_CLOSE, closeButton());
    }

    private ItemStack display(final ExchangeBook.Order order) {
        final CurrencyType type = plugin.currencies().repository().get(order.give());
        final ItemStack icon = type == null ? new ItemStack(Material.PAPER) : plugin.currencies().create(type, 1);
        icon.setAmount(Math.max(1, Math.min(icon.getMaxStackSize(), order.remaining())));
        return appendLore(icon, "",
                "<#7fd4ff>給 " + order.giveTotal() + " " + ItemRenderer.escape(service.currencyName(order.give()))
                        + " 換 " + order.wantTotal() + " " + ItemRenderer.escape(service.currencyName(order.want())),
                "<gray>已經成交 " + order.taken() + " / " + order.giveTotal() + "（成交的自動放進你的通貨）",
                "<dark_gray>掛了 " + MarketService.ago(order.createdAt()),
                order.confirmed() ? "<yellow>左鍵：取消（沒成交的 " + order.remaining() + " 個退回）" : "<yellow>存檔中…");
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            new ExchangeMenu(plugin, player, service, state, choice).open();
            return;
        }
        if (slot == SLOT_CLOSE) {
            goBack();
            return;
        }
        if (slot >= 45 || slot >= shown.size() || !acceptAction(click)) { // 2026-10-18：只畫前 45 張，最下排是裝飾格（以前點到會取消看不到的單）
            return;
        }
        final String problem = service.cancelOrder(player, shown.get(slot).id());
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        }
        redraw();
    }
}
