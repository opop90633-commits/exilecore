package tw.exilecore.combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import tw.exilecore.ExileCore;

import java.util.UUID;

/**
 * 2026-10-23：玩家死掉、下線、離開地圖（換世界或傳送到很遠的地方）時，拿掉他在怪物身上留下的減益：
 * 詛咒、印記、曝露、眩暈、致盲、癱瘓、戰吼的恫嚇（{@link Debuffs}），以及點燃、流血、中毒、混沌減益（異常狀態）。
 * 不然人走了，怪物還帶著他的詛咒與持續傷害，留下來的隊友或下一個進來的人白撿便宜（傷害也還算在他頭上）。
 */
final class SourceCleanup implements Listener {

    /** 同一個世界裡傳送超過這麼多格算「離開」（地圖之間、回營地；技能的位移、閃現遠遠不到）。 */
    static final double FAR = 48;

    private final ExileCore plugin;
    private final Debuffs debuffs;

    SourceCleanup(final ExileCore plugin, final Debuffs debuffs) {
        this.plugin = plugin;
        this.debuffs = debuffs;
    }

    /** 同一個世界、傳送距離 FAR 以內的不算離開。純函式（測試用）。 */
    static boolean leaves(final boolean sameWorld, final double distanceSquared) {
        return !sameWorld || distanceSquared > FAR * FAR;
    }

    void clear(final UUID player) {
        debuffs.removeSource(player);
        if (plugin.ailments() != null) {
            plugin.ailments().removeSource(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        clear(event.getEntity().getUniqueId());
        PlayerArmourBreak.clear(event.getEntity().getUniqueId()); // 2026-10-27：被破的護甲死掉就恢復；這次之前帶到復活後（下線、換角色在 PlayerStateService）
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(final PlayerChangedWorldEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        final Location from = event.getFrom();
        final Location to = event.getTo();
        final Player player = event.getPlayer();
        if (to == null || from.getWorld() == null || to.getWorld() == null) {
            return;
        }
        final boolean same = from.getWorld().equals(to.getWorld());
        if (same && leaves(true, from.distanceSquared(to))) {
            clear(player.getUniqueId()); // 換世界的由 onWorld 處理
        }
    }
}
