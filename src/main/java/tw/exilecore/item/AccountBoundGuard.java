package tw.exilecore.item;

import org.bukkit.GameMode;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import tw.exilecore.ExileCore;

/**
 * 2026-10-28 綁定帳號的物品（{@link AccountBound}）不能經過地上、原版的箱子（箱子、木桶、漏斗、界伏盒…）或物品展示框換手。
 * 我們自己的選單（倉庫、隨身背包這些 {@link tw.exilecore.gui.Menu}）不管：放不放得進去由各自的選單決定
 * （公會倉庫在 GuildService 擋）。創造模式照原版（管理員整理東西）。
 */
public final class AccountBoundGuard implements Listener {

    private final ExileCore plugin;

    public AccountBoundGuard(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private void tell(final Player player, final AccountBound.Channel channel) {
        tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>" + AccountBound.problem(true, channel)));
    }

    private static boolean creative(final Player player) {
        return player.getGameMode() == GameMode.CREATIVE;
    }

    /**
     * 丟東西：放得回背包才擋（背包滿了取消的話伺服器塞不回去，東西會不見；那種情況 CharacterListener 會讓它掉在腳下、
     * 永遠只有丟的人看得到撿得到，一樣不會換手）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(final PlayerDropItemEvent event) {
        final ItemStack stack = event.getItemDrop().getItemStack();
        if (AccountBound.isBound(stack) && !creative(event.getPlayer()) && fits(event.getPlayer(), stack)) {
            event.setCancelled(true);
            tell(event.getPlayer(), AccountBound.Channel.DROP);
        }
    }

    private static boolean fits(final Player player, final ItemStack stack) {
        int room = 0;
        for (final ItemStack current : player.getInventory().getStorageContents()) {
            if (current == null || current.isEmpty()) {
                return true;
            }
            if (current.isSimilar(stack)) {
                room += Math.max(0, current.getMaxStackSize() - current.getAmount());
            }
        }
        return room >= stack.getAmount();
    }

    /** 物品展示框：拿著綁定的東西點展示框不能放上去。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrame(final PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) {
            return;
        }
        final ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (AccountBound.isBound(hand) && !creative(event.getPlayer())) {
            event.setCancelled(true);
            tell(event.getPlayer(), AccountBound.Channel.CONTAINER);
        }
    }

    /** 上面開的是原版的容器（不是我們的選單、也不是自己的背包或合成格）。 */
    private static boolean foreign(final Inventory top) {
        if (top == null) {
            return false;
        }
        final InventoryHolder holder = top.getHolder(false);
        if (holder instanceof tw.exilecore.gui.Menu || holder instanceof Player) {
            return false;
        }
        return switch (top.getType()) {
            case CRAFTING, CREATIVE, PLAYER, WORKBENCH, ENCHANTING, ANVIL, GRINDSTONE, SMITHING, STONECUTTER, LOOM,
                 CARTOGRAPHY, MERCHANT -> false; // 合成類的格子關掉會還給自己（我們的物品本來就不能拿去做原版合成：CustomItemGuard）
            default -> true;
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(final InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || creative(player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        if (!foreign(top)) {
            return;
        }
        final boolean clickedTop = event.getClickedInventory() == top;
        boolean moving = false;
        if (clickedTop) {
            // 游標上拿著綁定的東西放進去；或用數字鍵、副手鍵把綁定的東西換進去
            moving = AccountBound.isBound(event.getCursor())
                    || event.getAction() == InventoryAction.HOTBAR_SWAP && event.getHotbarButton() >= 0
                            && AccountBound.isBound(player.getInventory().getItem(event.getHotbarButton()))
                    || event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                            && AccountBound.isBound(player.getInventory().getItemInOffHand());
        } else if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            moving = AccountBound.isBound(event.getCurrentItem()); // shift 點自己背包的
        }
        if (moving) {
            event.setCancelled(true);
            tell(player, AccountBound.Channel.CONTAINER);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(final InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || creative(player)) {
            return;
        }
        final Inventory top = event.getView().getTopInventory();
        if (!foreign(top) || !AccountBound.isBound(event.getOldCursor())) {
            return;
        }
        for (final int raw : event.getRawSlots()) {
            if (raw < top.getSize()) {
                event.setCancelled(true);
                tell(player, AccountBound.Channel.CONTAINER);
                return;
            }
        }
    }
}
