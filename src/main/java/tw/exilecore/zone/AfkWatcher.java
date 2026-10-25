package tw.exilecore.zone;

import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 掛機偵測（2026-10）：營地以外的地方站在原地不走（只放技能、按住連續施放也算），
 * {@link #WARN_SECONDS} 秒後提醒「視為掛機」，{@link #KICK_SECONDS} 秒時傳回營地。
 * 自己按移動鍵（前後左右、跳）走出 {@link #MOVE_DISTANCE} 格才重新算：被怪物擊退、推走的不算。
 * 營地（含練習場）、創造與旁觀模式、死掉的、正在打地圖 Boss 的（Boss 還活著、人在 Boss 附近）不管。
 */
final class AfkWatcher implements org.bukkit.event.Listener {

    /** 幾秒沒走動算掛機（提醒）。 */
    static final int WARN_SECONDS = 180;
    /** 幾秒沒走動傳回營地。 */
    static final int KICK_SECONDS = 300;
    /** 離開原地幾格才算有走動（轉頭、原地跳、被擊退一點點不算）。 */
    static final double MOVE_DISTANCE = 2.0;

    private static final class Watch {
        Location anchor;
        long since;
        boolean warned;
        /** 上次重新算之後，自己按過移動鍵（被擊退的位移不算走動）。 */
        boolean steered;
    }

    /** 玩家按下移動鍵（按一下就放開的也抓得到；按住不放的在 {@link #tick} 裡看）。 */
    @org.bukkit.event.EventHandler(ignoreCancelled = true)
    public void onInput(final org.bukkit.event.player.PlayerInputEvent event) {
        if (steering(event.getInput())) {
            watches.computeIfAbsent(event.getPlayer().getUniqueId(), uuid -> new Watch()).steered = true;
        }
    }

    /** 有沒有按移動鍵（前後左右、跳）。 */
    static boolean steering(final org.bukkit.Input input) {
        return input != null && (input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump());
    }

    private final ExileCore plugin;
    private final ZoneService zones;
    private final Map<UUID, Watch> watches = new HashMap<>();

    AfkWatcher(final ExileCore plugin, final ZoneService zones) {
        this.plugin = plugin;
        this.zones = zones;
    }

    /** 每秒。 */
    void tick() {
        final long now = System.currentTimeMillis();
        watches.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final Location at = player.getLocation();
            final Watch watch = watches.computeIfAbsent(player.getUniqueId(), uuid -> new Watch());
            if (steering(player.getCurrentInput())) {
                watch.steered = true;
            }
            // 2026-10：被擊退的位移不算走動（要自己按過移動鍵）；打地圖 Boss 的時候不算掛機（召喚師站著打 3 分半也不會被傳走）
            final boolean walked = watch.anchor == null || moved(watch.anchor, at) && watch.steered;
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || player.isDead()
                    || zones.inTown(at) || walked || plugin.maps() != null && plugin.maps().inBossFight(player)) {
                watch.anchor = at;
                watch.since = now;
                watch.warned = false;
                watch.steered = false;
                continue;
            }
            final long idle = (now - watch.since) / 1000;
            if (idle >= KICK_SECONDS) {
                watch.anchor = null; // 傳送完重新算
                zones.afkToTown(player);
                continue;
            }
            if (idle >= WARN_SECONDS && !watch.warned) {
                watch.warned = true;
                final long left = KICK_SECONDS - idle;
                tw.exilecore.gui.Titles.show(player, plugin.text().parse("<#ffd27f>掛機中"), null, // 2026-10-26：不送空的小標題（會留空框）
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(4000), Duration.ofMillis(800)));
                player.sendMessage(plugin.text().prefixed("<#ffd27f>你已經 " + (WARN_SECONDS / 60) + " 分鐘沒有走動，視為掛機。<gray>再 "
                        + Math.max(1, Math.round(left / 60.0)) + " 分鐘不走動會把你傳回營地（走動一下就取消）"));
            } else if (watch.warned && KICK_SECONDS - idle <= 10 && (KICK_SECONDS - idle) % 5 == 0) {
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<red>掛機：" + (KICK_SECONDS - idle) + " 秒後傳回營地（走動一下就取消）"));
            }
        }
    }

    private static boolean moved(final Location anchor, final Location at) {
        if (anchor == null || anchor.getWorld() == null || !anchor.getWorld().equals(at.getWorld())) {
            return true;
        }
        final double dx = anchor.getX() - at.getX();
        final double dz = anchor.getZ() - at.getZ();
        final double dy = anchor.getY() - at.getY();
        return dx * dx + dz * dz > MOVE_DISTANCE * MOVE_DISTANCE || Math.abs(dy) > 3;
    }
}
