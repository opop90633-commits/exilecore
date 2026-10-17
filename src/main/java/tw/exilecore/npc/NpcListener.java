package tw.exilecore.npc;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;

/** NPC 的互動：右鍵觸發功能；打不死（取消所有傷害，戰鬥監聽器因此也不會處理）。 */
public final class NpcListener implements Listener {

    private final NpcService npcs;

    public NpcListener(final NpcService npcs) {
        this.npcs = npcs;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(final PlayerInteractEntityEvent event) {
        final Optional<NpcType> type = npcs.type(event.getRightClicked());
        if (type.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        // 主手與副手各會觸發一次，只處理主手
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        npcs.interact(event.getPlayer(), type.get(), event.getRightClicked());
    }

    /** 區塊載入時：還沒有皮膚的舊 NPC 隨機套一個（見 NpcService.ensureSkin）。 */
    @EventHandler
    public void onEntitiesLoad(final org.bukkit.event.world.EntitiesLoadEvent event) {
        for (final org.bukkit.entity.Entity entity : event.getEntities()) {
            npcs.ensureSkin(entity);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(final EntityDamageEvent event) {
        if (npcs.isNpc(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 2026-10-17：跟 NPC 對話中，怪物不會把你當目標。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(final org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof org.bukkit.entity.Player player && npcs.talking(player)) {
            event.setCancelled(true);
        }
    }

    /** 自己出手打東西：對話保護結束。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(final org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof org.bukkit.entity.Player player) {
            npcs.stopTalking(player.getUniqueId());
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof org.bukkit.entity.Player player) {
            npcs.stopTalking(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        npcs.forget(event.getPlayer().getUniqueId());
    }
}
