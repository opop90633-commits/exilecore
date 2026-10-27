package tw.exilecore.ailment;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 死亡、登出、卸載時清掉異常；擋掉凍結外觀帶來的原版凍傷；恢復上次沒來得及還原的 AI 與外觀。 */
final class AilmentListener implements Listener {

    private final AilmentService service;

    AilmentListener(final AilmentService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        service.spreadDebuffs(event.getEntity()); // 2026-10：疫潮死掉時傳染（清掉之前先看）
        service.clear(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        service.clear(event.getPlayer());
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        service.recover(event.getPlayer());
    }

    @EventHandler
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        // 2026-10-27：玩家換世界（走傳送門）也會觸發這個：玩家不在這裡清（登出、死亡另外清）；這次之前走傳送門就把身上的異常洗掉
        if (event.getEntity() instanceof LivingEntity living && !(living instanceof org.bukkit.entity.Player)) {
            service.clear(living);
        }
    }

    /** 凍結時身上的「凍僵」只是外觀，原版每 2 秒的凍傷不算。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFreezeDamage(final EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FREEZE && event.getEntity() instanceof LivingEntity living
                && service.hasFrozenVisual(living)) {
            event.setCancelled(true);
        }
    }

    /** 區塊卸載、換維度或當機來不及還原的（暫停的 AI、火、凍僵、名字標記），載入時還原。 */
    @EventHandler
    public void onAdd(final EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            service.recover(living);
        }
    }
}
