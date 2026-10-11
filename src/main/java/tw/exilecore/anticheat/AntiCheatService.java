package tw.exilecore.anticheat;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 防作弊（2026-10）：只管「改遊戲數值」那一類——移動速度、飛行、懸空、瞬移。腳本、連點不管。
 * <p>
 * 每刻看每個人走了多遠（不靠客戶端的 onGround）：
 * <ul>
 *   <li>上限照伺服器自己算的移動速度屬性（天賦、裝備、藥水給的加速都在裡面）、衝刺跳躍、冰面，再放寬一些。
 *       技能、翻滾、擊退給的速度（伺服器設的 velocity）照大小加進上限幾刻，不會誤判。</li>
 *   <li>飛行只看「沒踩在東西上卻不往下掉」或「跳得比原版高太多」；能飛的（創造、營地飛行的贊助者）、滑翔、騎東西、
 *       漂浮或緩降效果、跳躍藥水、在水裡、爬梯子都不檢查。</li>
 *   <li>登入、傳送、重生、換世界、被打之後 {@link AntiCheatConfig#graceTicks} 刻內不檢查。</li>
 * </ul>
 * 違規到 1 就拉回最後一個正常的位置（不會卡進方塊：位置是他自己站過的）；一段時間內拉回太多次就踢出。
 * 管理員（exilecore.admin）在線會收到通知；有 exilecore.anticheat.bypass 權限的人完全不檢查。
 */
public final class AntiCheatService implements Listener {

    static final String BYPASS = "exilecore.anticheat.bypass";
    static final String ADMIN = "exilecore.admin";
    /** 原版走路 0.1 的移動速度 = 每秒 4.317 格；衝刺跳躍能到約 7.1 格。 */
    private static final double WALK_PER_SECOND = 4.317;
    private static final double SPRINT_JUMP_FACTOR = 1.7;
    private static final double ICE_FACTOR = 2.6;
    /** 伺服器給的速度（技能、翻滾、擊退）：這幾刻把它加進上限。 */
    private static final int VELOCITY_GRACE_TICKS = 30;

    private final ExileCore plugin;
    private AntiCheatConfig config;
    private final Map<UUID, Tracked> tracked = new HashMap<>();
    private BukkitTask task;
    private long tick;

    /** 一個玩家的追蹤狀態。 */
    private final class Tracked {
        final MovementCheck check;
        Location last;
        Location lastGood;
        long graceUntil;
        long velocityUntil;
        double velocityBonus;
        final Deque<Long> strikes = new ArrayDeque<>();

        Tracked(final Location at) {
            check = new MovementCheck(config.movement());
            last = at.clone();
            lastGood = at.clone();
            graceUntil = tick + config.graceTicks();
        }
    }

    public AntiCheatService(final ExileCore plugin, final AntiCheatConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    void start() {
        stop();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::everyTick, 1, 1);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        tracked.clear();
    }

    void reload(final AntiCheatConfig fresh) {
        config = fresh;
        tracked.clear();
    }

    public AntiCheatConfig config() {
        return config;
    }

    // ---- 每刻 ----

    private void everyTick() {
        tick++;
        if (!config.enabled()) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                inspect(player);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("[防作弊] 檢查 {} 時出錯：{}", player.getName(), e.toString());
            }
        }
    }

    private void inspect(final Player player) {
        if (exempt(player)) {
            tracked.remove(player.getUniqueId());
            return;
        }
        final Location now = player.getLocation();
        final Tracked state = tracked.computeIfAbsent(player.getUniqueId(), id -> new Tracked(now));
        if (state.last.getWorld() != now.getWorld()) {
            state.check.reset();
            state.last = now.clone();
            state.lastGood = now.clone();
            state.graceUntil = tick + config.graceTicks();
            return;
        }
        final double dx = now.getX() - state.last.getX();
        final double dz = now.getZ() - state.last.getZ();
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        final double vertical = now.getY() - state.last.getY();
        state.last = now.clone();
        if (tick < state.graceUntil) {
            state.check.reset();
            state.lastGood = now.clone();
            return;
        }
        final boolean supported = supported(player, now);
        final boolean flightCheck = config.flight() && !player.getAllowFlight() && !player.isGliding() && !player.isInsideVehicle()
                && !player.isRiptiding() && !player.isClimbing() && !player.isInWater() && !player.isInLava()
                && !player.hasPotionEffect(PotionEffectType.LEVITATION) && !player.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST) && tick >= state.velocityUntil && !nearBouncy(now);
        final double allowed = config.speed() ? allowedPerSecond(player, now, state) : Double.MAX_VALUE;
        final MovementCheck.Verdict verdict = state.check.tick(horizontal, vertical, supported, allowed, flightCheck);
        if (verdict == MovementCheck.Verdict.OK) {
            if (supported) {
                state.lastGood = now.clone();
            }
            return;
        }
        punish(player, state, verdict, now);
    }

    /** 這些人不檢查：有 bypass 權限、創造或旁觀、死掉、正在飛（能飛的）、滑翔、騎東西。 */
    private static boolean exempt(final Player player) {
        return player.hasPermission(BYPASS) || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isDead() || player.isFlying() || player.isGliding() || player.isInsideVehicle();
    }

    /** 這一秒最多能走幾格：移動速度屬性 × 衝刺跳躍 × 冰面，加上伺服器給的速度。 */
    private double allowedPerSecond(final Player player, final Location at, final Tracked state) {
        final AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        final double speed = attribute == null ? 0.1 : Math.max(0.05, attribute.getValue());
        double allowed = speed / 0.1 * WALK_PER_SECOND * SPRINT_JUMP_FACTOR;
        if (onIce(at)) {
            allowed *= ICE_FACTOR;
        }
        if (player.isInWater()) {
            allowed = Math.max(allowed, 9.0); // 海豚恩惠、激流
        }
        if (player.isRiptiding()) {
            allowed = Math.max(allowed, 40.0);
        }
        if (tick < state.velocityUntil) {
            allowed += state.velocityBonus;
        }
        return allowed;
    }

    /** 有踩在東西上：腳下 0.35 格內有會擋人的方塊，或在液體、梯子、鷹架、蜘蛛網、蜂蜜裡。 */
    private static boolean supported(final Player player, final Location at) {
        if (player.isInWater() || player.isInLava() || player.isClimbing()) {
            return true;
        }
        final BoundingBox box = player.getBoundingBox();
        final double y = box.getMinY() - 0.35;
        final double[] xs = {box.getMinX() + 0.01, box.getMaxX() - 0.01};
        final double[] zs = {box.getMinZ() + 0.01, box.getMaxZ() - 0.01};
        for (double checkY = y; checkY <= box.getMinY() + 0.05; checkY += 0.35) {
            for (final double x : xs) {
                for (final double z : zs) {
                    final Block block = at.getWorld().getBlockAt((int) Math.floor(x), (int) Math.floor(checkY), (int) Math.floor(z));
                    if (holds(block)) {
                        return true;
                    }
                }
            }
            final Block center = at.getWorld().getBlockAt((int) Math.floor(at.getX()), (int) Math.floor(checkY), (int) Math.floor(at.getZ()));
            if (holds(center)) {
                return true;
            }
        }
        return false;
    }

    private static boolean holds(final Block block) {
        final Material type = block.getType();
        if (type.isAir()) {
            return false;
        }
        if (block.isCollidable() || type.isSolid()) {
            return true;
        }
        return type == Material.COBWEB || type == Material.SCAFFOLDING || type == Material.LADDER || type == Material.VINE
                || type == Material.POWDER_SNOW || type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN
                || Tag.CLIMBABLE.isTagged(type);
    }

    private static boolean onIce(final Location at) {
        for (int dy = 1; dy <= 2; dy++) {
            final Material below = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - dy, at.getBlockZ()).getType();
            if (Tag.ICE.isTagged(below)) {
                return true;
            }
        }
        return false;
    }

    /** 腳下附近有史萊姆方塊（彈起來不是伺服器設的 velocity）。 */
    private static boolean nearBouncy(final Location at) {
        for (int dy = 0; dy <= 3; dy++) {
            if (at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - dy, at.getBlockZ()).getType() == Material.SLIME_BLOCK) {
                return true;
            }
        }
        return false;
    }

    // ---- 出手 ----

    private void punish(final Player player, final Tracked state, final MovementCheck.Verdict verdict, final Location now) {
        final long nowMillis = System.currentTimeMillis();
        state.strikes.addLast(nowMillis);
        while (!state.strikes.isEmpty() && nowMillis - state.strikes.peekFirst() > config.strikeWindowSeconds() * 1000L) {
            state.strikes.pollFirst();
        }
        final String kind = verdict == MovementCheck.Verdict.FLY ? "飛行／懸空" : "超速";
        final String detail = verdict == MovementCheck.Verdict.FLY
                ? String.format(Locale.ROOT, "空中 %d 刻", state.check.airTicks())
                : String.format(Locale.ROOT, "一秒走了 %.1f 格，上限 %.1f", state.check.windowSum(), state.check.lastAllowed());
        final Location back = state.lastGood.clone();
        back.setYaw(now.getYaw());
        back.setPitch(now.getPitch());
        state.check.reset();
        state.graceUntil = tick + 10;
        player.setVelocity(new Vector());
        player.teleportAsync(back).thenRun(() -> {
            if (player.isOnline()) {
                state.last = player.getLocation();
            }
        });
        final int strikes = state.strikes.size();
        plugin.getSLF4JLogger().warn("[防作弊] {} {}（{}）拉回 {} —— 最近 {} 秒第 {} 次", player.getName(), kind, detail,
                describe(back), config.strikeWindowSeconds(), strikes);
        if (config.tellPlayer()) {
            player.sendActionBar(plugin.text().parse("<red>移動異常（" + kind + "），已拉回"));
        }
        if (config.notifyAdmins()) {
            for (final Player admin : plugin.getServer().getOnlinePlayers()) {
                if (admin.hasPermission(ADMIN) && !admin.equals(player)) {
                    admin.sendMessage(plugin.text().prefixed("<#ff8a3d>[防作弊] <white>" + ItemRenderer.escape(player.getName()) + " <gray>" + kind
                            + "（" + detail + "），拉回。最近 " + config.strikeWindowSeconds() + " 秒第 " + strikes + " 次"));
                }
            }
        }
        if (config.strikesToKick() > 0 && strikes >= config.strikesToKick()) {
            state.strikes.clear();
            plugin.getSLF4JLogger().warn("[防作弊] {} 踢出：{} 秒內移動異常 {} 次", player.getName(), config.strikeWindowSeconds(), strikes);
            if (config.notifyAdmins()) {
                for (final Player admin : plugin.getServer().getOnlinePlayers()) {
                    if (admin.hasPermission(ADMIN)) {
                        admin.sendMessage(plugin.text().prefixed("<#ff8a3d>[防作弊] <white>" + ItemRenderer.escape(player.getName())
                                + " <red>踢出<gray>：" + config.strikeWindowSeconds() + " 秒內移動異常 " + strikes + " 次"));
                    }
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.kick(plugin.text().parse("<red>移動異常太多次（" + kind + "）\n<gray>請關掉會改移動速度、飛行的模組再進來"));
                }
            });
        }
    }

    private static String describe(final Location at) {
        return String.format(Locale.ROOT, "%s %.1f, %.1f, %.1f", at.getWorld() == null ? "?" : at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
    }

    // ---- 不檢查的時機 ----

    private void grace(final Player player, final int ticks) {
        final Tracked state = tracked.get(player.getUniqueId());
        if (state != null) {
            state.graceUntil = Math.max(state.graceUntil, tick + ticks);
            state.check.reset();
        }
    }

    /** 伺服器設的速度（技能、翻滾、擊退、風彈）：這幾刻把它加進上限、也不算飛行。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(final PlayerVelocityEvent event) {
        final Tracked state = tracked.get(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        final Vector velocity = event.getVelocity();
        final double horizontal = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        // 每刻的速度 × 20 = 一秒；速度會衰減，所以用一半再加一點保險
        final double bonus = Math.max(4.0, horizontal * 20 * 0.7 + Math.abs(velocity.getY()) * 10);
        state.velocityUntil = Math.max(state.velocityUntil, tick + VELOCITY_GRACE_TICKS);
        state.velocityBonus = Math.max(tick < state.velocityUntil ? state.velocityBonus : 0, bonus);
        state.check.reset();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        final Tracked state = tracked.get(event.getPlayer().getUniqueId());
        if (state != null) {
            state.last = event.getTo().clone();
            state.lastGood = event.getTo().clone();
            state.graceUntil = Math.max(state.graceUntil, tick + config.graceTicks());
            state.check.reset();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        tracked.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(final PlayerChangedWorldEvent event) {
        tracked.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        tracked.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        tracked.remove(event.getPlayer().getUniqueId());
    }

    /** 被打：擊退的速度通常也會走 PlayerVelocityEvent，這裡再保險一次。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            grace(player, 15);
        }
    }

    /** 管理員看的狀態（/ec anticheat）。 */
    public String status(final Player player) {
        final Tracked state = tracked.get(player.getUniqueId());
        if (state == null) {
            return exempt(player) ? "不檢查（有 bypass 權限、創造、旁觀、飛行、滑翔或騎乘中）" : "還沒有資料";
        }
        return String.format(Locale.ROOT, "最近一秒走了 %.2f 格（上限 %.2f）、違規點數 %.2f、空中 %d 刻、最近 %d 秒拉回 %d 次",
                state.check.windowSum(), state.check.lastAllowed(), state.check.violation(), state.check.airTicks(),
                config.strikeWindowSeconds(), state.strikes.size());
    }
}
