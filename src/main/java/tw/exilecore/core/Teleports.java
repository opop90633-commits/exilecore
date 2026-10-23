package tw.exilecore.core;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 2026-10-23：傳送玩家前先把騎在身上的背飾（展示實體）卸下來。
 * Paper 在送出 PlayerTeleportEvent 之前就先檢查「身上有乘客就不傳」，監聽事件來不及卸；
 * 有翅膀的人回營地、開地圖全部傳不動。插件裡傳玩家都走這裡；背飾下一 tick 會自己騎回去。
 */
public final class Teleports {

    private Teleports() {
    }

    /** 卸下騎在玩家身上的展示實體（背飾）；別的乘客（例如抱著的生物）不動。 */
    public static void dismount(final Player player) {
        if (player.getPassengers().isEmpty()) {
            return;
        }
        for (final Entity passenger : new ArrayList<>(player.getPassengers())) {
            if (passenger instanceof Display) {
                player.removePassenger(passenger);
            }
        }
    }

    public static boolean go(final Player player, final Location to) {
        dismount(player);
        return player.teleport(to);
    }

    public static CompletableFuture<Boolean> async(final Player player, final Location to) {
        dismount(player);
        return player.teleportAsync(to);
    }
}
