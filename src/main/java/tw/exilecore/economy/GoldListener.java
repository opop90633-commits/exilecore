package tw.exilecore.economy;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import tw.exilecore.ExileCore;

import java.util.Optional;
import java.util.UUID;

/**
 * 走過金幣就撿進帳號：不管背包滿不滿（金幣不占格子），所以聽「試著撿」的事件——背包滿了伺服器就不發一般的撿起事件。
 * 別人的那堆撿不到（金幣不能交易，也不能經過地上換手）；生物、漏斗之類的容器不能吸走金幣。
 */
public final class GoldListener implements Listener {

    private final ExileCore plugin;
    private final GoldService gold;

    public GoldListener(final ExileCore plugin, final GoldService gold) {
        this.plugin = plugin;
        this.gold = gold;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttempt(final PlayerAttemptPickupItemEvent event) {
        final Optional<Long> amount = gold.amountOf(event.getItem().getItemStack());
        if (amount.isEmpty()) {
            return;
        }
        event.setCancelled(true); // 不進背包
        final Player player = event.getPlayer();
        final UUID owner = event.getItem().getOwner();
        if (owner != null && !owner.equals(player.getUniqueId())) {
            return; // 別人的金幣
        }
        if (!gold.earn(player, amount.get(), EconomyLedger.DROP)) {
            return; // 還沒有角色：金幣留在地上
        }
        event.getItem().remove();
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.8f);
    }

    /** 玩家在上面處理；其他生物（狐狸、悅靈…）不能撿金幣。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(final EntityPickupItemEvent event) {
        if (gold.amountOf(event.getItem().getItemStack()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(final InventoryPickupItemEvent event) {
        if (gold.amountOf(event.getItem().getItemStack()).isPresent()) {
            event.setCancelled(true);
        }
    }
}
