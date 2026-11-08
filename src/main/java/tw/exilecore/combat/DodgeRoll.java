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
 * 翻滾的前 {@value #INVULNERABLE_TICKS} tick 躲開近戰攻擊與投射物的擊中（環境傷害、持續傷害照算）。
 * 2026-10-27 照 PoE2（poe2db Dodge Roll：「不給無敵」「翻滾開始時躲開投射物與近戰攻擊」）：法術、範圍、地面的擊中照樣打得到
 * （{@link #avoids}）；這次之前前 6 tick 全部的擊中都打不到（等於無敵時間）。
 * 沒有冷卻，但一次翻滾的動作要 {@value #ROLL_TICKS} tick，做完才能再翻。被凍結、暈眩時不能翻；營地裡也能翻（2026-10）。
 */
public final class DodgeRoll implements Listener {

    /** 一次翻滾的動作時間（tick）。 */
    public static final long ROLL_TICKS = 12;
    /** 技能標籤：有這個的擊中翻滾躲不掉（Boss 的全場大招，2026-10）。 */
    public static final String UNDODGEABLE_TAG = "undodgeable";
    /** 翻滾開始後多少 tick 躲得開近戰攻擊與投射物。 */
    public static final long INVULNERABLE_TICKS = 6;
    /** 水平速度（方塊／tick；地面摩擦下大約滑 3～4 格）。 */
    private static final double SPEED = 1.15;
    /** 2026-10-26：一次翻滾大約幾格（「翻滾距離 +x 格」換算成倍率用）。 */
    public static final double BASE_METRES = 3.5;
    /** 2026-11-07（希特克拉多的假說）：翻滾犧牲生命換的守護持續幾 tick（PoE2 守護一般 4 秒）。 */
    static final long SACRIFICE_GUARD_TICKS = 80;

    /** 2026-11-07（希特克拉多的假說）：翻滾要犧牲多少生命——最大生命的 percent%，最多扣到剩 1 點。 */
    public static double sacrificed(final double life, final double maxLife, final double percent) {
        return Math.max(0, Math.min(maxLife * Math.max(0, percent) / 100.0, life - 1));
    }

    private final ExileCore plugin;
    private final Map<UUID, Long> rolledAt = new HashMap<>();
    private final Map<UUID, Boolean> sprintHeld = new HashMap<>();

    public DodgeRoll(final ExileCore plugin) {
        this.plugin = plugin;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /**
     * 2026-10-27 照 PoE2：翻滾躲得開的擊中只有近戰攻擊與投射物（PoE2 沒有無敵時間）；法術、範圍、地面躲不開。
     *
     * @param melee      這一下是近戰攻擊
     * @param projectile 這一下是投射物
     */
    public static boolean avoids(final boolean melee, final boolean projectile) {
        return melee || projectile;
    }

    /** 正在翻滾的開頭（躲得開近戰攻擊與投射物的那段；見 {@link #avoids}）。 */
    public boolean invulnerable(final Player player) {
        if (tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.StatKeys.FLAG_BULWARK)) {
            return false; // 壁壘：翻滾不能躲傷害（改成受到的傷害更少）
        }
        final Long at = rolledAt.get(player.getUniqueId());
        return at != null && now() - at < INVULNERABLE_TICKS;
    }

    /** 2026-10-31 照站長：翻滾一開始 0.1 秒完全無敵（法術、範圍、地面的擊中也躲得開；Boss 的全場大招照樣躲不開）。 */
    public static final long IFRAME_TICKS = 2;

    /** 2026-10-31：翻滾一開始的無敵時間（{@link #IFRAME_TICKS}）。 */
    public boolean iframe(final Player player) {
        final Long at = rolledAt.get(player.getUniqueId());
        return at != null && now() - at < IFRAME_TICKS && invulnerable(player);
    }

    /** 正在翻滾（整個動作時間內）。 */
    public boolean rolling(final Player player) {
        final Long at = rolledAt.get(player.getUniqueId());
        return at != null && now() - at < ROLL_TICKS;
    }

    /**
     * 2026-10-26（天賦樹「衝刺時」）：衝刺中＝按住翻滾鍵（Ctrl）而且不在翻滾動作裡（PoE2 翻滾後按住繼續跑；移動與否由呼叫的人看）。
     */
    public boolean sprinting(final Player player) {
        return Boolean.TRUE.equals(sprintHeld.get(player.getUniqueId())) && !rolling(player);
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
        // 2026-10：營地裡也能翻（以前不行；營地不會受傷，翻滾只是移動用）
        if (plugin.ailments() != null && plugin.ailments().isIncapacitated(player)) {
            return;
        }
        if (tw.exilecore.stats.StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.StatKeys.FLAG_UNWAVERING)) {
            return; // 不動如山：不能翻滾
        }
        final long now = now();
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
        // 2026-11-01 照 PoE2 Blink（取代翻滾）：掠閃開著、冷卻好了就改放掠閃（往翻滾的方向閃），不翻滾；冷卻中照常翻滾
        if (plugin.skills() != null && plugin.skills().blinkDodge(player, direction.clone().normalize())) {
            return;
        }
        final Long last = rolledAt.get(player.getUniqueId());
        if (last != null && now - last < ROLL_TICKS) {
            return;
        }
        // 2026-10：翻滾距離（PoE2 鞋子的褻瀆詞綴）：衝刺的速度照比例放大，最多兩倍
        // 2026-10-26（鷹師之弓）：「翻滾距離 +x 格」照一次翻滾約 BASE_METRES 格換算；（相位之履）翻滾時穿過敵人
        final tw.exilecore.stats.StatSheet rollSheet = plugin.players().sheet(player);
        final double distance = Math.max(0.5, Math.min(2, 1 + rollSheet.increased(tw.exilecore.stats.StatKeys.DODGE_DISTANCE, java.util.Set.of())
                + rollSheet.flat(tw.exilecore.item.unique.UniqueStats.DODGE_METRES, java.util.Set.of()) / BASE_METRES));
        if (tw.exilecore.stats.StatKeys.flag(rollSheet, tw.exilecore.item.unique.UniqueStats.DODGE_PHASE) && player.isCollidable()) {
            player.setCollidable(false);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> player.setCollidable(true), ROLL_TICKS);
        }
        direction.normalize().multiply((player.isOnGround() ? SPEED : SPEED * 0.6) * distance);
        direction.setY(player.isOnGround() ? 0.12 : Math.max(-0.2, player.getVelocity().getY()));
        player.setVelocity(direction);
        player.setSprinting(false);
        rolledAt.put(player.getUniqueId(), now);
        plugin.players().get(player).markRecent(tw.exilecore.stats.Recently.DODGE, now); // 「最近翻滾過」
        // 2026-11-04（源於永恆，PoE2）：翻滾時獲得超重（減緩 10%，可疊加，再翻一次全部重新計時）
        final double heavy = rollSheet.flat(tw.exilecore.item.unique.UniqueStats.OVERENCUMBER_ON_DODGE, java.util.Set.of());
        if (heavy > 0 && plugin.ailments() != null) {
            plugin.ailments().overencumber(player, heavy);
        }
        // 2026-11-07 照站長「瓦爾傳奇照原作」（希特克拉多的假說）：閃避翻滾時犧牲 x% 最大生命（不會因此死掉，最少留 1），獲得等量的守護（4 秒）
        final double sacrifice = rollSheet.flat(tw.exilecore.item.unique.VaalKeys.GUARD_ON_DODGE_SACRIFICE, java.util.Set.of());
        if (sacrifice > 0) {
            final tw.exilecore.character.PlayerState rollState = plugin.players().get(player);
            final double paid = sacrificed(rollState.life(), rollState.maxLife(), sacrifice);
            if (paid > 0) {
                rollState.setLife(rollState.life() - paid);
                rollState.addGuard(paid, now + SACRIFICE_GUARD_TICKS);
                plugin.players().display(player, rollState);
            }
        }
        if (plugin.guide() != null) {
            plugin.guide().milestone(player, tw.exilecore.guide.GuideService.DODGE); // 2026-10-30 新手教學
        }
        final Location at = player.getLocation().add(0, 0.3, 0);
        tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.CLOUD, at, 8, 0.3, 0.1, 0.3, 0.02);
        player.getWorld().playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, 1.6f);
        if (plugin.skills() != null && plugin.skills().triggers() != null) {
            plugin.skills().triggers().dodge(player, BASE_METRES * distance); // 2026-10：翻滾觸發（2026-10-26：帶翻滾距離，翻滾引星照格給能量）
            plugin.skills().supportMechanics().lineage().dodged(player); // 2026-10-27 照 PoE2 連膛：翻滾時裝子彈
            tw.exilecore.ascendancy.AscendancyEffects.dodged(player); // 2026-11-08 照 PoE2 0.5 Mirage Archer：翻滾時留下幻弓影的幻影
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        rolledAt.remove(event.getPlayer().getUniqueId());
        sprintHeld.remove(event.getPlayer().getUniqueId());
    }
}
