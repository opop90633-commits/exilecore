package tw.exilecore.map;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import tw.exilecore.ExileCore;

import java.util.EnumSet;
import java.util.Set;

/**
 * 地圖的事件：在地圖裡死亡（重生回營地）、登入時人在已經清掉的格子裡（送回營地）、
 * 地圖裡原版法術生出來的小怪（喚魔者的惱鬼、史萊姆分裂）算進這張地圖但不掉東西、終局伏守者的黑暗效果拿掉、
 * 區塊重新載入時回來的舊 Boss（這張圖已經生了新的）移除。
 */
final class MapListener implements Listener {

    /** 這些原因生出來的怪物算「召喚」：跟著地圖的等級與詞綴，但不掉東西、不給經驗。 */
    private static final Set<CreatureSpawnEvent.SpawnReason> SUMMONED = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.SPELL, CreatureSpawnEvent.SpawnReason.SLIME_SPLIT,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS, CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK,
            CreatureSpawnEvent.SpawnReason.JOCKEY, CreatureSpawnEvent.SpawnReason.MOUNT, CreatureSpawnEvent.SpawnReason.DEFAULT,
            CreatureSpawnEvent.SpawnReason.ENDER_PEARL, CreatureSpawnEvent.SpawnReason.TRAP);

    private final ExileCore plugin;
    private final MapService service;

    MapListener(final ExileCore plugin, final MapService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 2026-10：還沒開打的地圖 Boss 不找目標（原版 AI 追蹤範圍 64 格，會穿過門口去追隔壁房間的人）。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossTarget(final org.bukkit.event.entity.EntityTargetEvent event) {
        if (event.getTarget() != null && event.getEntity() instanceof LivingEntity boss
                && boss.getPersistentDataContainer().has(service.bossKey()) && service.bosses().dormant(boss)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        service.markDeath(event.getEntity());
    }

    /** 比區域的重生（HIGH）晚：在地圖裡死的回營地的地圖裝置前面。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Location location = service.respawnFor(event.getPlayer());
        if (location != null) {
            event.setRespawnLocation(location);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            // 2026-10-17：上次觀戰時伺服器關掉（沒還原模式）：改回原本的模式、回營地
            if (service.spectators().restoreStale(player)) {
                service.sendToCamp(player);
                player.sendMessage(plugin.text().prefixed("<gray>上次觀戰到一半伺服器重開了，回到營地"));
                return;
            }
            if (service.world() == null || !player.getWorld().equals(service.world())
                    || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            final MapInstance instance = service.instanceAt(player.getLocation());
            if (!service.belongs(player, instance)) {
                service.sendToCamp(player);
                player.sendMessage(plugin.text().prefixed("<gray>你離線的時候那張地圖關閉了，回到營地"));
            } else if (instance.usesAttempts()) {
                // 2026-10-17：終局、極境、巔峰、金庫之主：下線算離開這一門（以前重登可以留在裡面、不扣門繼續打）
                service.sendToCamp(player);
                player.sendMessage(plugin.text().prefixed("<gray>下線算離開這一門，回到營地（門還亮著就能走進去挑戰下一門）"));
            }
        }, 5);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.forget(event.getPlayer());
    }

    /** 地圖裡原版法術生的怪：加上實例標記（等級、地圖詞綴），標成召喚（不掉東西）。比怪物等級化（MONITOR）早。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (!SUMMONED.contains(event.getSpawnReason()) || service.world() == null
                || !event.getLocation().getWorld().equals(service.world())) {
            return;
        }
        final MapInstance instance = service.instanceAt(event.getLocation());
        if (instance == null) {
            event.setCancelled(true);
            return;
        }
        final LivingEntity entity = event.getEntity();
        if (entity.getType() == org.bukkit.entity.EntityType.VEX) {
            event.setCancelled(true); // 喚魔者召喚的惱鬼會穿牆飛出地圖、飛到天花板上面
            return;
        }
        service.tag(entity, instance, false);
        plugin.monsters().markSummoned(entity);
    }

    /** 地圖怪的傳送（終界使者）：不能傳到地圖外面、不能傳到屏障天花板上面。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(final org.bukkit.event.entity.EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living) || event.getTo() == null) {
            return;
        }
        final MapInstance instance = service.instanceOf(living);
        if (instance != null && !instance.insideWalls(event.getTo())) {
            event.setCancelled(true);
        }
    }

    /** 終局的伏守者不給黑暗效果（看不到東西打不了）。 */
    @EventHandler(ignoreCancelled = true)
    public void onPotion(final EntityPotionEffectEvent event) {
        if (event.getCause() == EntityPotionEffectEvent.Cause.WARDEN && service.world() != null
                && event.getEntity().getWorld().equals(service.world())) {
            event.setCancelled(true);
        }
    }

    /**
     * 區塊重新載入時舊的 Boss 跟著回來，但這張圖已經生了一隻新的（找舊的那時它還沒載入）：舊的移除，
     * 不然兩隻 Boss 一起出招，看起來就像同時放兩招。下一 tick 才移除（載入的事件裡不能動實體）。
     */
    @EventHandler
    public void onEntityAdd(final EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living) || !living.getPersistentDataContainer().has(service.bossKey())) {
            return;
        }
        final MapInstance instance = service.instanceOf(living);
        final LivingEntity current = instance == null ? null : instance.boss();
        // 2026-10-17：Boss 已經打倒了，又有一隻帶 Boss 標記的回來（舊區塊載入）：一樣移除
        if (instance != null && instance.bossKilled() && (current == null || !current.getUniqueId().equals(living.getUniqueId()))) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (living.isValid()) {
                    living.remove();
                }
            });
            return;
        }
        if (current == null || !current.isValid() || current.getUniqueId().equals(living.getUniqueId())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            final LivingEntity now = instance.boss();
            if (living.isValid() && now != null && now.isValid() && !now.getUniqueId().equals(living.getUniqueId())) {
                living.remove();
            }
        });
    }
}
