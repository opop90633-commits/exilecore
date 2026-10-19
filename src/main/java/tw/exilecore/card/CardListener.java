package tw.exilecore.card;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;

import java.util.UUID;

/**
 * 星諭卡的操作（2026-10-19）：
 * <ul>
 *   <li>拿在手上右鍵：兌換一套（牌堆：打開一個）；蹲著（Shift）右鍵：這種卡全部兌換（牌堆：整疊打開）。</li>
 *   <li>背包裡對卡按右鍵：兌換一套；Shift＋右鍵：背包裡這種卡全部兌換。牌堆一樣（打開一個／整疊）。</li>
 *   <li>撿起地上的卡、牌堆：直接進倉庫通貨頁的「星諭卡」（倉庫滿了才進通貨袋、背包）。</li>
 * </ul>
 * 倉庫裡的兌換、賭卡在倉庫選單（StashMenu）。
 */
final class CardListener implements Listener {

    private final ExileCore plugin;
    private final CardService service;

    CardListener(final ExileCore plugin, final CardService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private CurrencyType typeOf(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || plugin.currencies() == null) {
            return null;
        }
        final CurrencyType type = plugin.currencies().read(stack).orElse(null);
        return service.goesToStash(type) ? type : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        final Player player = event.getPlayer();
        final ItemStack hand = player.getInventory().getItemInMainHand();
        final CurrencyType type = typeOf(hand);
        if (type == null || !plugin.players().get(player).hasCharacter()) {
            return;
        }
        event.setCancelled(true);
        final boolean all = player.isSneaking();
        if (CardService.isDeck(type)) {
            service.openStack(player, hand, all);
            player.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        } else {
            service.exchangeInventory(player, type, all); // 背包裡這種卡加起來算（蹲著：全部換）
        }
    }

    /** 自己的背包畫面（沒有開別的箱子、選單）裡，對卡按右鍵／Shift＋右鍵。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getClickedInventory() instanceof PlayerInventory)) {
            return;
        }
        if (event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
            return; // 開著倉庫、選單、箱子時不搶右鍵（倉庫的「星諭卡」那一組另外處理）
        }
        if (event.getClick() != ClickType.RIGHT && event.getClick() != ClickType.SHIFT_RIGHT) {
            return;
        }
        if (!event.getCursor().isEmpty()) {
            return;
        }
        final ItemStack current = event.getCurrentItem();
        final CurrencyType type = typeOf(current);
        if (type == null || !plugin.players().get(player).hasCharacter()) {
            return;
        }
        event.setCancelled(true);
        final boolean all = event.getClick() == ClickType.SHIFT_RIGHT;
        if (CardService.isDeck(type)) {
            service.openStack(player, current, all);
            event.setCurrentItem(current.getAmount() <= 0 ? null : current);
        } else {
            service.exchangeInventory(player, type, all);
        }
    }

    /** 撿起卡：不進背包，直接進倉庫。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final PlayerAttemptPickupItemEvent event) {
        final ItemStack stack = event.getItem().getItemStack();
        final CurrencyType type = typeOf(stack);
        if (type == null) {
            return;
        }
        final Player player = event.getPlayer();
        final UUID owner = event.getItem().getOwner();
        if (owner != null && !owner.equals(player.getUniqueId())) {
            return; // 別人的：照原本的規則
        }
        if (!plugin.players().get(player).hasCharacter() || plugin.stash() == null || plugin.stash().stash(player).isEmpty()) {
            return; // 倉庫還沒載入：照一般通貨撿
        }
        event.setCancelled(true);
        event.getItem().remove();
        service.deposit(player, type, stack.getAmount());
        player.sendActionBar(plugin.text().parse("<#c9a0ff>" + tw.exilecore.item.ItemRenderer.escape(type.name()) + " ×" + stack.getAmount()
                + "<gray> 放進倉庫的「星諭卡」"));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 1.6f);
    }
}
