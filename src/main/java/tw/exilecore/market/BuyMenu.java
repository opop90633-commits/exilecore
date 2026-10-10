package tw.exilecore.market;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.gui.Menu;
import tw.exilecore.item.ItemRenderer;

import java.util.Locale;

/** 市集的購買確認：看清楚東西、價格、手續費、你有多少，再按確認。 */
final class BuyMenu extends Menu {

    private static final int SLOT_INFO = 11;
    private static final int SLOT_ITEM = 13;
    private static final int SLOT_CONFIRM = 15;
    private static final int SLOT_BACK = 18;

    private final MarketService service;
    private final Listing listing;
    private final MarketMenu.State state;
    /** 打開時看到的價格（賣家中途改價就不買）。 */
    private final String currency;
    private final int amount;

    BuyMenu(final ExileCore plugin, final Player player, final MarketService service, final Listing listing, final MarketMenu.State state) {
        super(plugin, player, 3, "<dark_gray>市集：確認購買");
        this.service = service;
        this.listing = listing;
        this.state = state;
        this.currency = listing.priceCurrency;
        this.amount = listing.priceAmount;
    }

    @Override
    protected void draw() {
        for (int i = 0; i < 27; i++) {
            set(i, filler());
        }
        set(SLOT_ITEM, listing.item.clone());
        final long fee = service.purchaseFee(player, currency, amount);
        final int have = service.wallet().available(player, currency);
        final long gold = plugin.gold().balance(player);
        set(SLOT_INFO, icon(Material.BOOK, "<white>價格",
                "<#f5b942>" + ItemRenderer.escape(service.wallet().price(currency, amount)),
                String.format(Locale.ROOT, "<gray>手續費 <#f5b942>%,d 金幣", fee),
                "",
                (have >= amount ? "<green>" : "<red>") + "你有 " + have + " 個" + ItemRenderer.escape(service.currencyName(currency)),
                String.format(Locale.ROOT, "<dark_gray>　通貨袋 %d、背包 %d、倉庫 %d", service.wallet().pouch(player, currency),
                        service.wallet().inventory(player, currency), service.wallet().stash(player, currency)),
                String.format(Locale.ROOT, "%s你有 %,d 金幣", gold >= fee ? "<green>" : "<red>", gold),
                "",
                "<gray>賣家：<white>" + ItemRenderer.escape(listing.sellerName)));
        final boolean affordable = have >= amount && gold >= fee;
        set(SLOT_CONFIRM, affordable
                ? glowing(icon(Material.LIME_CONCRETE, "<green>確認購買", "<gray>付錢後東西直接進背包（滿了放隨身背包）", "",
                "<yellow>左鍵：購買"))
                : icon(Material.RED_CONCRETE, "<red>錢不夠", "<gray>通貨要放在通貨袋、背包或倉庫的通貨頁"));
        set(SLOT_BACK, icon(Material.ARROW, "<white>← 回市集"));
    }

    @Override
    protected void onClick(final int slot, final ClickType click, final InventoryClickEvent event) {
        if (slot == SLOT_BACK) {
            new MarketMenu(plugin, player, service, state).open();
            return;
        }
        if (slot != SLOT_CONFIRM || !acceptAction(click)) {
            return;
        }
        final String problem = service.buy(player, listing.id, currency, amount);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            redraw();
            return;
        }
        player.sendMessage(plugin.text().prefixed("<green>買到了：").append(listing.item.effectiveName())
                .append(plugin.text().parse("<gray>（" + ItemRenderer.escape(service.wallet().price(currency, amount)) + "）")));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
        new MarketMenu(plugin, player, service, state).open();
    }
}
