package tw.exilecore.core;

import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-21 效能：怪物（旋風使者）射出去沒打中的旋風彈會一直飛下去、跟著區塊存檔，越積越多
 * （spark：全服 299 個，有的卡在沒人的區塊一次 64 個）。怪物射的旋風彈不存檔、5 秒後拿掉；區塊載入時舊的也清掉。
 * 玩家自己丟的不管。
 */
public final class StrayProjectiles implements Listener {

    /** 怪物射的旋風彈最多活幾 tick。 */
    static final long WIND_CHARGE_LIFETIME = 100;

    private final Plugin plugin;

    public StrayProjectiles(final Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(final ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof AbstractWindCharge charge) || charge.getShooter() instanceof Player) {
            return;
        }
        charge.setPersistent(false);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (charge.isValid()) {
                charge.remove();
            }
        }, WIND_CHARGE_LIFETIME);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(final EntitiesLoadEvent event) {
        List<Entity> stale = null;
        for (final Entity entity : event.getEntities()) {
            if (entity instanceof AbstractWindCharge charge && !(charge.getShooter() instanceof Player)) {
                if (stale == null) {
                    stale = new ArrayList<>();
                }
                stale.add(entity);
            }
        }
        if (stale == null) {
            return;
        }
        final List<Entity> remove = stale;
        plugin.getServer().getScheduler().runTask(plugin, () -> { // 載入事件裡不直接移除，下一刻再拿掉
            for (final Entity entity : remove) {
                if (entity.isValid()) {
                    entity.remove();
                }
            }
        });
    }
}
