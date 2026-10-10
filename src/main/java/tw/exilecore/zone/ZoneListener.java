package tw.exilecore.zone;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Location;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import tw.exilecore.ExileCore;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 區域的規則：區域裡沒有原版的自然生成（改由 {@link PackSpawner} 刷怪群）、營地是安全區、死亡回最後的檢查點。
 */
final class ZoneListener implements Listener {

    /** 戰鬥區域裡擋掉的原版生成原因（刷怪籠、指令、生怪蛋、插件生成照常）。 */
    private static final Set<SpawnReason> BLOCKED_IN_ZONES = EnumSet.of(
            SpawnReason.NATURAL, SpawnReason.JOCKEY, SpawnReason.PATROL,
            SpawnReason.REINFORCEMENTS, SpawnReason.VILLAGE_INVASION, SpawnReason.RAID, SpawnReason.TRAP);
    /** 營地裡只允許這些原因生成敵對生物（管理員測試用）。 */
    private static final Set<SpawnReason> ALLOWED_IN_TOWN = EnumSet.of(
            SpawnReason.COMMAND, SpawnReason.SPAWNER_EGG, SpawnReason.CUSTOM);

    private final ExileCore plugin;
    private final ZoneService zones;
    private final PackSpawner spawner;

    ZoneListener(final ExileCore plugin, final ZoneService zones, final PackSpawner spawner) {
        this.plugin = plugin;
        this.zones = zones;
        this.spawner = spawner;
    }

    /** 比怪物等級化（MONITOR）早，被擋掉的就不會被等級化。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Enemy) || plugin.mythic().spawningNow()) {
            return;
        }
        final Optional<Zone> zone = zones.zoneAt(event.getLocation());
        if (zone.isEmpty()) {
            return;
        }
        final SpawnReason reason = event.getSpawnReason();
        if (zone.get().town() ? !ALLOWED_IN_TOWN.contains(reason) : BLOCKED_IN_ZONES.contains(reason)) {
            event.setCancelled(true);
        }
    }

    /** 營地裡的玩家不會受傷（掉進虛空與 /kill 除外）。比戰鬥監聽器早，被取消的傷害不會被計算。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDamage(final EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        final EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) {
            return;
        }
        if (zones.inTown(player.getLocation())) {
            event.setCancelled(true);
            player.setFireTicks(0);
        }
    }

    /** 死亡後回到最後碰到的檢查點（沒有就營地）。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Location location = zones.respawnLocation(event.getPlayer());
        if (location != null) {
            event.setRespawnLocation(location);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        zones.forget(event.getPlayer());
    }

    @EventHandler
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        spawner.forget(event.getEntity().getUniqueId());
    }
}
