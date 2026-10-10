package tw.exilecore.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import tw.exilecore.character.Gear;

/**
 * 把選單的點擊事件分派給對應的 {@link Menu}。優先度 LOWEST：先於通貨與換裝的監聽器取消事件。
 */
public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (touchesGearDisplay(player, event) && !menu.acceptsGearDisplay()) {
            // 角色面板裝備的顯示用複本（第 9 格、副手、防具）：不能放進倉庫、賣掉、丟進垃圾桶
            event.setCancelled(true);
            return;
        }
        if (menu.allowsItemMovement()) {
            menu.handleRawClick(event);
            return;
        }
        event.setCancelled(true);
        final Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        if (clicked.getHolder() instanceof Menu) {
            menu.onClick(event.getSlot(), event.getClick(), event);
        } else {
            menu.onBottomClick(event.getSlot(), event.getClick(), event);
        }
    }

    /** 這次點擊會不會動到顯示用的複本（點到的、游標上的、數字鍵或 F 換過來的）。 */
    private static boolean touchesGearDisplay(final Player player, final InventoryClickEvent event) {
        if (Gear.isDisplay(event.getCurrentItem()) || Gear.isDisplay(event.getCursor())) {
            return true;
        }
        if (event.getClick() == ClickType.NUMBER_KEY) {
            final int button = event.getHotbarButton();
            return button == Gear.HAND_SLOT || button >= 0 && Gear.isDisplay(player.getInventory().getItem(button));
        }
        return event.getClick() == ClickType.SWAP_OFFHAND;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(final InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            if (Gear.isDisplay(event.getOldCursor())) {
                event.setCancelled(true);
            } else if (menu.allowsItemMovement()) {
                menu.handleDrag(event);
            } else {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onClose(final InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.onClose();
        }
    }
}
