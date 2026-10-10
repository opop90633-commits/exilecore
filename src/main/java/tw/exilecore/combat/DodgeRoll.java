package tw.exilecore.combat;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 閃避翻滾（2026-10，PoE2）：按 Ctrl（原版的「衝刺」鍵）往移動的方向翻滾約 3～4 格；沒按方向鍵就往面向翻。
 * 翻滾的前 {@value #INVULNERABLE_TICKS} tick 怪物的擊中打不到你（環境傷害、持續傷害照算）。
 * 沒有冷卻，但一次翻滾的動作要 {@value #ROLL_TICKS} tick，做完才能再翻。被凍結、暈眩時不能翻；營地裡不翻。
 */
public final class DodgeRoll implements Listener {

    /** 一次翻滾的動作時間（tick）。 */
    public static final long ROLL_TICKS = 12;
    /** 技能標籤：有這個的擊中翻滾躲不掉（Boss 的全場大招，2026-10）。 */
    public static final String UNDODGEABLE_TAG = "undodgeable";
    /** 翻滾開始後多少 tick 打不到。 */
    public static final long INVULNERABLE_TICKS = 6;
    /** 水平速度（方塊／tick；地面摩擦下大約滑 3～4 格）。 */
    private static final double SPEED = 1.15;

    private final ExileCore plugin;
    private final Map<UUID, Long> rolledAt = new HashMap<>();
    private final Map<UUID, Boolean> sprintHeld = new HashMap<>();

    public DodgeRoll(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 正在翻滾的無敵時間內（怪物、其他玩家的擊中打不到）。 */
    public boolean invulnerable(final Player player) {
        if (tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.StatKeys.FLAG_BULWARK)) {
            return false; // 壁壘：翻滾不能躲傷害（改成受到的傷害更少）
        }
        final Long at = rolledAt.get(player.getUniqueId());
        return at != null && now() - at < INVULNERABLE_TICKS;
    }

    /** 正在翻滾（整個動作時間內）。 */
    public boolean rolling(final Player player) {
        final Long at = rolledAt.get(player.getUniqueId());
        return at != null && now() - at < ROLL_TICKS;
    }

    @EventHandler
    public void onInput(final PlayerInputEvent event) {
        final Player player = event.getPlayer();
        final var input = event.getInput();
        final boolean sprint = input.isSprint();
        final Boolean before = sprintHeld.put(player.getUniqueId(), sprint);
        if (!sprint || Boolean.TRUE.equals(before)) {
            return; // 只在按下的那一刻翻
        }
        if (player.getGameMode() == GameMode.SPECTATOR || player.isFlying() || player.isInsideVehicle() || player.isGliding()) {
            return;
        }
        if (plugin.zones() != null && plugin.zones().inTown(player.getLocation())) {
            return;
        }
        if (plugin.ailments() != null && plugin.ailments().isIncapacitated(player)) {
            return;
        }
        if (tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.StatKeys.FLAG_UNWAVERING)) {
            return; // 不動如山：不能翻滾
        }
        final long now = now();
        final Long last = rolledAt.get(player.getUniqueId());
        if (last != null && now - last < ROLL_TICKS) {
            return;
        }
        // 方向：照按著的方向鍵（相對面向），沒按就往前
        double forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
        double strafe = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
        if (forward == 0 && strafe == 0) {
            forward = 1;
        }
        final double yaw = Math.toRadians(player.getLocation().getYaw());
        final Vector look = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        final Vector left = new Vector(Math.cos(yaw), 0, Math.sin(yaw));
        final Vector direction = look.multiply(forward).add(left.multiply(strafe));
        if (direction.lengthSquared() < 1e-6) {
            return;
        }
        direction.normalize().multiply(player.isOnGround() ? SPEED : SPEED * 0.6);
        direction.setY(player.isOnGround() ? 0.12 : Math.max(-0.2, player.getVelocity().getY()));
        player.setVelocity(direction);
        player.setSprinting(false);
        rolledAt.put(player.getUniqueId(), now);
        final Location at = player.getLocation().add(0, 0.3, 0);
        player.getWorld().spawnParticle(Particle.CLOUD, at, 8, 0.3, 0.1, 0.3, 0.02);
        player.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.6f);
        if (plugin.skills() != null && plugin.skills().triggers() != null) {
            plugin.skills().triggers().dodge(player); // 2026-10：翻滾觸發
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        rolledAt.remove(event.getPlayer().getUniqueId());
        sprintHeld.remove(event.getPlayer().getUniqueId());
    }
}
