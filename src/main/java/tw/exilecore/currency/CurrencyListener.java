package tw.exilecore.currency;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;

/**
 * 通貨的使用介面：把通貨拿在游標上，左鍵點一件裝備就套用一顆。
 * 背包、箱子、之後的倉庫與交易視窗都是同一套邏輯。
 */
public final class CurrencyListener implements Listener {

    private final ExileCore plugin;
    private final CurrencyService currencies;

    public CurrencyListener(final ExileCore plugin, final CurrencyService currencies) {
        this.plugin = plugin;
        this.currencies = currencies;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        final ItemStack cursor = event.getCursor();
        final ItemStack target = event.getCurrentItem();
        if (cursor.isEmpty() || target == null || target.isEmpty()) {
            return;
        }
        if (!currencies.isCurrency(cursor)) {
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }
        if (!plugin.items().isEquipment(target)) {
            // 通貨點到非裝備：照原版行為（交換／堆疊）
            return;
        }
        event.setCancelled(true);

        final CurrencyService.Result result = currencies.apply(cursor, target, player.getName());
        switch (result) {
            case APPLIED -> {
                cursor.setAmount(cursor.getAmount() - 1);
                event.getView().setCursor(cursor.getAmount() <= 0 ? ItemStack.empty() : cursor);
                event.setCurrentItem(target);
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
            }
            case NOT_APPLICABLE -> {
                final String name = currencies.read(cursor).map(CurrencyType::name).orElse("這顆通貨");
                player.sendActionBar(plugin.text().parse("<red>" + name + " 不能用在這件物品上"));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            }
            default -> {
                // 不是裝備或不是通貨：不會走到這裡
            }
        }
    }
}
