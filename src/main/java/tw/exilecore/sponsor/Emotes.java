package tw.exilecore.sponsor;

import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 動作（2026-10，時裝的一種）：在「贊助 → 動作」把擁有的動作綁到 F、Q 或 Shift＋Q，按了就做。
 * 四個動作照「閃身步｜浪子踢球｜狗熊哆嗦毛｜搖頭晃腦」的影片（原版的人物動不了手腳，用移動、蹲、揮手、轉頭與粒子表現）：
 * <ul>
 *   <li>閃身步（sidestep）：左跨一步、收回、右跨一步、收回，留下殘影。<b>只有營地裡會真的移動</b>（贊助不影響戰鬥）。</li>
 *   <li>浪子踢球（kick_ball）：張開雙手，小跳往後勾腳、交叉步、再勾另一腳。</li>
 *   <li>狗熊哆嗦毛（bear_shake）：蹲低亂甩手、扭身甩頭、甩出水花跟毛，最後兩手高舉。</li>
 *   <li>搖頭晃腦（head_bob）：馬步、一手伸出去，頭左右快速甩動。</li>
 * </ul>
 * 每個人 3 秒冷卻；Q 在背包介面裡丟東西的那一下不算。
 */
public final class Emotes implements Listener {

    private static final long COOLDOWN_MILLIS = 3000;

    private final ExileCore plugin;
    private final SponsorService service;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Map<UUID, Integer> inventoryClickTick = new HashMap<>();
    private final Map<UUID, Boolean> sideLeft = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final List<BukkitTask> running = new ArrayList<>();
    private final List<ItemDisplay> balls = new ArrayList<>();
    /** 67 頭上那個「67」（2026-10）：關服、重讀時一起收掉。 */
    private final List<org.bukkit.entity.TextDisplay> sixSevenLabels = new ArrayList<>();
    /** 每個人擺著的沙發（2026-10：人走開沙發留在原地；一個人同時只有一張）。 */
    private final Map<UUID, List<org.bukkit.entity.BlockDisplay>> sofas = new HashMap<>();
    /** 沙發擺多久（刻）：人可以先走開，沙發留著。 */
    private static final long SOFA_TICKS = 1200;

    /** 有裝 BetterModel 時用玩家自己的皮膚演（整個伺服器只接一次：/ec reload 重建這個類別也不重接）。 */
    private static BetterModelEmotes betterModel;
    private static boolean betterModelChecked;

    Emotes(final ExileCore plugin, final SponsorService service) {
        this.plugin = plugin;
        this.service = service;
        if (!betterModelChecked) {
            betterModelChecked = true;
            betterModel = BetterModelEmotes.create(plugin);
        }
    }

    // ---- 按鍵 ----

    @EventHandler(priority = EventPriority.LOW)
    public void onSwap(final PlayerSwapHandItemsEvent event) {
        final Player player = event.getPlayer();
        if (player.isSneaking()) {
            return; // Shift＋F 是選單
        }
        if (fire(player, SponsorService.EmoteKey.F)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(final PlayerDropItemEvent event) {
        final Player player = event.getPlayer();
        final Integer tick = inventoryClickTick.get(player.getUniqueId());
        if (tick != null && plugin.getServer().getCurrentTick() - tick <= 1) {
            return; // 背包介面裡丟東西
        }
        if (fire(player, player.isSneaking() ? SponsorService.EmoteKey.SNEAK_Q : SponsorService.EmoteKey.Q)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(final InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            inventoryClickTick.put(player.getUniqueId(), plugin.getServer().getCurrentTick());
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        lastUse.remove(id);
        inventoryClickTick.remove(id);
        sideLeft.remove(id);
    }

    /** 這個按鍵有綁動作就做（回傳 true＝按鍵被動作用掉了，原本的功能取消）。 */
    private boolean fire(final Player player, final SponsorService.EmoteKey key) {
        if (!service.loaded(player) || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        final SponsorConfig.Cosmetic emote = service.emoteOn(player, key);
        if (emote == null) {
            return false;
        }
        play(player, emote, false);
        return true;
    }

    /** 做一次動作（menu = 從選單試做，冷卻一樣算）。 */
    void play(final Player player, final SponsorConfig.Cosmetic emote, final boolean quietCooldown) {
        if (player.isDead() || !player.isValid() || busy.contains(player.getUniqueId())) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) {
            if (!quietCooldown) {
                player.sendActionBar(plugin.text().parse("<gray>動作冷卻中（" + String.format("%.1f", (COOLDOWN_MILLIS - (now - last)) / 1000.0) + " 秒）"));
            }
            return;
        }
        lastUse.put(player.getUniqueId(), now);
        // 2026-10：有 BetterModel 就用它演（手腳都會動）；音效、粒子照我們的
        if (betterModel != null && betterModel.play(player, emote.emote())) {
            // 冷卻算到動畫演完（分解動作比較長）
            lastUse.put(player.getUniqueId(), now + Math.max(0, modelLengthMillis(emote.emote()) - COOLDOWN_MILLIS));
            effects(player, emote.emote());
            if ("six_seven".equals(emote.emote())) {
                sixSevenLabel(player, modelLengthMillis("six_seven") / 50);
            }
            return;
        }
        if ("six_seven".equals(emote.emote())) {
            sixSevenLabel(player, 51); // 沒有 BetterModel 的版本大約 2.5 秒
        }
        switch (emote.emote()) {
            case "sidestep" -> sidestep(player);
            case "kick_ball" -> kickBall(player);
            case "bear_shake" -> bearShake(player);
            case "head_bob" -> headBob(player);
            case "king_sit" -> sofa(player, SOFA_TICKS); // 沒有 BetterModel：只擺沙發
            case "love_you" -> loveYouVanilla(player);
            case "six_seven" -> sixSevenVanilla(player);
            default -> {
            }
        }
    }

    /** BetterModel 的動畫多長（毫秒；2026-10 改成分解動作、慢一些，tools/gen_emotes.py 印出來的長度）：演完才能再做下一個。 */
    private static long modelLengthMillis(final String emote) {
        return switch (emote) {
            case "sidestep" -> 4800;
            case "kick_ball" -> 4450;
            case "bear_shake" -> 5050;
            case "head_bob" -> 4400;
            case "king_sit" -> 10700;
            case "love_you" -> 3900; // 2026-10（第三次）：快兩成（一邊 0.65 秒、兩個來回，共 3.85 秒）；左手一直揮
            case "six_seven" -> 3550;
            default -> COOLDOWN_MILLIS;
        };
    }

    private void later(final Player player, final long delay, final java.util.function.Consumer<Location> body) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !player.isDead()) {
                body.accept(player.getLocation());
            }
        }, delay);
    }

    /** BetterModel 演的時候配的音效與粒子（動作本身由模型演；時間對著 gen_emotes.py 的每一拍）。 */
    private void effects(final Player player, final String emote) {
        final World world = player.getWorld();
        switch (emote) {
            case "sidestep" -> {
                // 2026-10 位移感（小幅）：閃出去的那一下留殘影；營地裡真的往旁邊滑半格左右、停完再滑回來（其他地方只有殘影，贊助不影響戰鬥）
                final boolean move = inTown(player);
                final Vector forward = player.getLocation().getDirection().setY(0);
                if (forward.lengthSquared() < 1.0e-4) {
                    forward.setX(0).setZ(1);
                }
                forward.normalize();
                final Vector right = new Vector(-forward.getZ(), 0, forward.getX()); // 玩家的右手邊
                final long[][] steps = {{19, 1}, {54, -1}}; // {第幾 tick 閃出去, 方向}；停 0.95 秒後收回
                for (final long[] step : steps) {
                    final Vector side = right.clone().multiply(step[1]);
                    later(player, step[0], at -> {
                        afterimage(at);
                        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.5f);
                        world.playSound(at, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.4f, 1.8f);
                        world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.2, 0), 6, 0.3, 0.05, 0.3, 0.02);
                        if (move) {
                            player.setVelocity(side.clone().multiply(0.4).setY(0.06)); // 小幅滑一步（大約半格）
                        } else {
                            afterimage(at.clone().add(side.clone().multiply(0.5)));
                        }
                    });
                    for (long t = step[0] + 1; t < step[0] + 6; t++) {
                        later(player, t, at -> {
                            final Location body = at.clone().add(0, 0.9, 0);
                            world.spawnParticle(Particle.CLOUD, body, 2, 0.15, 0.4, 0.15, 0.01);
                            world.spawnParticle(Particle.DUST, body, 4, 0.2, 0.5, 0.2, 0, new Particle.DustOptions(Color.fromRGB(0xbfe8ff), 0.8f));
                        });
                    }
                    later(player, step[0] + 19, at -> {
                        if (move) {
                            player.setVelocity(side.clone().multiply(-0.4).setY(0.04));
                        }
                        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.5f, 1.4f);
                    });
                }
            }
            case "kick_ball" -> {
                later(player, 7, at -> world.playSound(at, Sound.ENTITY_PHANTOM_FLAP, 0.5f, 1.4f));      // 張手
                for (final long tick : new long[] {28, 49}) {                                              // 勾腳、交叉
                    later(player, tick, at -> {
                        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.5f, 1.5f);
                        world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.3, 0), 4, 0.2, 0.05, 0.2, 0.02);
                    });
                }
            }
            case "bear_shake" -> {
                later(player, 0, at -> world.playSound(at, Sound.ENTITY_POLAR_BEAR_AMBIENT, 0.8f, 0.9f));
                // 2026-10 重抓：每跨一步、換一個大姿勢甩一點水（時間對 gen_emotes.py 的 bear_steps）
                for (final long tick : new long[] {3, 9, 14, 20, 26, 31, 36, 40, 44, 80}) {
                    later(player, tick, at -> world.spawnParticle(Particle.SPLASH, at.clone().add(0, 1.0, 0), 12, 0.4, 0.4, 0.4, 0.1));
                }
                for (final long tick : new long[] {9, 26, 36, 40}) { // 跨步、小跳落地
                    later(player, tick, at -> world.playSound(at, Sound.ENTITY_POLAR_BEAR_STEP, 0.5f, 1.2f));
                }
                later(player, 48, at -> world.playSound(at, Sound.ENTITY_WOLF_SHAKE, 1f, 0.7f)); // 哆嗦那一段（2.4～3.6 秒）
                final ItemStack fur = new ItemStack(Material.BROWN_WOOL);
                for (long tick = 48; tick <= 72; tick += 2) {
                    later(player, tick, at -> {
                        final Location body = at.clone().add(0, 1.0, 0);
                        world.spawnParticle(Particle.SPLASH, body, 8, 0.4, 0.4, 0.4, 0.12);
                        world.spawnParticle(Particle.ITEM, body, 2, 0.25, 0.35, 0.25, 0.12, fur);
                    });
                }
            }
            case "six_seven" -> {
                // 兩手一上一下掂（gen_emotes.py 的 ss_steps：0.3 秒起每 0.21 秒換一次）
                for (int i = 0; i < 12; i++) {
                    final long tick = Math.round((0.3 + 0.21 * (i + 1)) * 20);
                    final float pitch = i % 2 == 0 ? 1.2f : 1.5f;
                    later(player, tick, at -> world.playSound(at, Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, pitch));
                }
            }
            case "love_you" -> {
                // 害羞地摀嘴、左右挪（gen_emotes.py 的 love_steps）。2026-10：不冒愛心了；每挪到一邊往下一沉時輕輕一聲
                for (final long tick : new long[] {15, 28, 41, 54}) { // 沉下去的那一格（0.77、1.42、2.07、2.72 秒）
                    later(player, tick, at -> world.playSound(at, Sound.BLOCK_WOOL_STEP, 0.35f, 1.5f));
                }
                later(player, 2, at -> world.playSound(at, Sound.ENTITY_CAT_PURREOW, 0.6f, 1.4f));
                later(player, 41, at -> world.playSound(at, Sound.ENTITY_CAT_AMBIENT, 0.6f, 1.6f));
            }
            case "king_sit" -> {
                sofa(player, SOFA_TICKS);
                standUpWhenLeaving(player, 214);
                world.playSound(player.getLocation(), Sound.BLOCK_WOOL_PLACE, 1f, 0.7f);
                later(player, 7, at -> world.playSound(at, Sound.ENTITY_PLAYER_BIG_FALL, 0.4f, 1.4f)); // 坐下
            }
            case "head_bob" -> {
                // 2026-10 重抓：0.58 秒起每 0.1～0.11 秒甩一次頭（gen_emotes.py 的 bob_steps），手每甩出去一次掃一聲
                double t = 0.58;
                for (int i = 0; i < 24; i++) {
                    t += i < 8 ? 0.11 : 0.1;
                    final long tick = Math.round(t * 20);
                    later(player, tick, at -> world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.25f, 1.9f));
                    if (i % 4 == 1) {
                        later(player, tick, at -> world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.3f, 1.7f));
                    }
                }
            }
            default -> {
            }
        }
    }

    /**
     * 王之坐姿的沙發（2026-10）：在玩家身後擺一張方塊做的沙發（座墊、靠背、兩邊扶手；顯示用的方塊，不擋路），ticks 之後收掉。
     * 照玩家身體的方向擺：座墊在腳下偏後、靠背在後面。
     */
    private void sofa(final Player player, final long ticks) {
        final Location at = player.getLocation();
        at.setPitch(0);
        at.setYaw(player.getBodyYaw());
        final World world = at.getWorld();
        final List<org.bukkit.entity.BlockDisplay> parts = new ArrayList<>();
        removeSofa(player.getUniqueId()); // 同一個人再坐一次：舊的那張收掉
        sofas.put(player.getUniqueId(), parts);
        // {材質, x, y, z, 寬, 高, 深}（顯示座標：+z 是玩家前面、+x 是玩家左邊；格）
        final Object[][] pieces = {
                {Material.LIGHT_GRAY_WOOL, -0.8, 0.0, -0.62, 1.6, 0.33, 0.9},    // 座墊
                {Material.GRAY_WOOL, -0.8, 0.0, -0.9, 1.6, 1.1, 0.3},            // 靠背
                {Material.GRAY_WOOL, -1.05, 0.0, -0.9, 0.28, 0.62, 1.15},        // 扶手
                {Material.GRAY_WOOL, 0.77, 0.0, -0.9, 0.28, 0.62, 1.15},
        };
        for (final Object[] piece : pieces) {
            parts.add(world.spawn(at, org.bukkit.entity.BlockDisplay.class, display -> {
                display.setBlock(((Material) piece[0]).createBlockData());
                display.setPersistent(false);
                display.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(((Double) piece[1]).floatValue(), ((Double) piece[2]).floatValue(), ((Double) piece[3]).floatValue()),
                        new org.joml.Quaternionf(),
                        new org.joml.Vector3f(((Double) piece[4]).floatValue(), ((Double) piece[5]).floatValue(), ((Double) piece[6]).floatValue()),
                        new org.joml.Quaternionf()));
            }));
        }
        world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.4, 0), 12, 0.5, 0.2, 0.5, 0.02);
        final UUID owner = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (sofas.get(owner) != parts) {
                return; // 已經換了一張（或收掉了）
            }
            removeSofa(owner);
            if (at.isWorldLoaded()) {
                world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.4, 0), 10, 0.5, 0.2, 0.5, 0.02);
            }
        }, ticks);
    }

    private void removeSofa(final UUID owner) {
        final List<org.bukkit.entity.BlockDisplay> parts = sofas.remove(owner);
        if (parts == null) {
            return;
        }
        for (final org.bukkit.entity.BlockDisplay part : parts) {
            if (part.isValid()) {
                part.remove();
            }
        }
    }

    /**
     * 王之坐姿（2026-10）：坐著的時候一走動（離開座位約半格、跳起來、換世界）就站起來，可以直接走開；沙發留在原地。
     * 站起來之後只算一般的 3 秒冷卻。
     */
    private void standUpWhenLeaving(final Player player, final int length) {
        final Location seat = player.getLocation();
        final UUID id = player.getUniqueId();
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (tick > length || !player.isOnline() || player.isDead()) {
                task.cancel();
                return;
            }
            if (tick < 8) {
                return; // 剛坐下的那一下不算
            }
            final Location now = player.getLocation();
            final boolean left = !now.getWorld().equals(seat.getWorld())
                    || square(now.getX() - seat.getX()) + square(now.getZ() - seat.getZ()) > 0.25
                    || Math.abs(now.getY() - seat.getY()) > 0.5;
            if (left) {
                if (betterModel != null) {
                    betterModel.stop(player);
                }
                lastUse.put(id, System.currentTimeMillis());
                task.cancel();
            }
        });
    }

    private static double square(final double value) {
        return value * value;
    }

    private boolean inTown(final Player player) {
        return plugin.skills() != null && plugin.skills().inTown(player);
    }

    private void repeat(final java.util.function.Consumer<BukkitTask> body) {
        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                final BukkitTask self = holder[0];
                if (self == null) {
                    return;
                }
                body.accept(self);
                if (self.isCancelled()) {
                    running.remove(self);
                }
            }
        }.runTaskTimer(plugin, 1, 1);
        running.add(holder[0]);
    }

    // ---- 閃身步 ----

    /**
     * 照影片：先往左跨一大步壓低身子、手往身前甩，收回中間；再往右跨一步、收回來（約 2 秒）。
     * 每一步原地留下殘影。只有營地裡會真的移動，其他地方只有殘影與動作（贊助不影響戰鬥）。
     */
    private void sidestep(final Player player) {
        final UUID id = player.getUniqueId();
        busy.add(id);
        final World world = player.getWorld();
        final boolean wasSneaking = player.isSneaking();
        final boolean move = inTown(player);
        final Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-4) {
            forward.setX(0).setZ(1);
        }
        forward.normalize();
        final Vector right = new Vector(forward.getZ(), 0, -forward.getX());
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 36) {
                if (player.isOnline()) {
                    player.setSneaking(wasSneaking);
                }
                busy.remove(id);
                task.cancel();
                return;
            }
            // 0：往左跨、8：收回、18：往右跨、26：收回
            if (tick == 0 || tick == 18) {
                final Vector side = right.clone().multiply(tick == 0 ? -1 : 1);
                afterimage(player.getLocation());
                player.setSneaking(true);
                if (tick == 0) {
                    player.swingOffHand();
                } else {
                    player.swingMainHand();
                }
                world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 1.6f);
                world.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.4f, 1.8f);
                if (move) {
                    player.setVelocity(side.clone().multiply(0.95).setY(0.12));
                } else {
                    afterimage(player.getLocation().add(side.clone().multiply(1.6)));
                }
            }
            if (tick == 8 || tick == 26) {
                final Vector back = right.clone().multiply(tick == 8 ? 1 : -1);
                player.setSneaking(false);
                if (move) {
                    player.setVelocity(back.clone().multiply(0.95).setY(0.08));
                }
                world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.5f, 1.4f);
            }
            if (tick % 2 == 0 && (tick < 8 || tick >= 18 && tick < 26)) {
                final Location at = player.getLocation().add(0, 0.9, 0);
                world.spawnParticle(Particle.CLOUD, at, 2, 0.15, 0.4, 0.15, 0.01);
                world.spawnParticle(Particle.DUST, at, 4, 0.2, 0.5, 0.2, 0, new Particle.DustOptions(Color.fromRGB(0xbfe8ff), 0.8f));
            }
        });
    }

    /** 殘影：用淡藍色的點畫出一個人形，停在原地慢慢散掉。 */
    private void afterimage(final Location at) {
        final World world = at.getWorld();
        final Vector forward = at.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-4) {
            forward.setX(0).setZ(1);
        }
        forward.normalize();
        final Vector right = new Vector(forward.getZ(), 0, -forward.getX());
        final List<Vector> points = new ArrayList<>();
        for (double y = 0.05; y < 0.75; y += 0.12) { // 兩條腿
            points.add(right.clone().multiply(0.12).setY(y));
            points.add(right.clone().multiply(-0.12).setY(y));
        }
        for (double y = 0.75; y < 1.42; y += 0.11) { // 身體
            for (double w = -0.22; w <= 0.23; w += 0.11) {
                points.add(right.clone().multiply(w).setY(y));
            }
        }
        for (double y = 0.8; y < 1.4; y += 0.12) { // 手
            points.add(right.clone().multiply(0.34).setY(y));
            points.add(right.clone().multiply(-0.34).setY(y));
        }
        for (int i = 0; i < 12; i++) { // 頭
            final double angle = Math.PI * 2 * i / 12;
            points.add(right.clone().multiply(Math.cos(angle) * 0.2).setY(1.65 + Math.sin(angle) * 0.2));
        }
        final int[] age = {0};
        repeat(task -> {
            if (age[0] >= 8) {
                task.cancel();
                return;
            }
            final float size = 1.1f - age[0] * 0.1f;
            final Color colour = Color.fromRGB(0xa8dcff);
            for (final Vector point : points) {
                world.spawnParticle(Particle.DUST, at.clone().add(point), 1, 0.01, 0.01, 0.01, 0, new Particle.DustOptions(colour, size));
            }
            if (age[0] == 0) {
                world.spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(0, 1, 0), 1);
            }
            age[0] += 2;
        });
    }

    // ---- 浪子踢球 ----

    /**
     * 照影片（沒有球）：兩手像翅膀一樣張開，小跳一下、一腳往後勾起；再交叉步小跳、往後勾另一腳，回到站姿（約 1.5 秒）。
     * 原版的人物動不了手腳，用揮手、蹲、小跳與腳邊的煙塵表現。只有營地裡會小跳。
     */
    private void kickBall(final Player player) {
        final UUID id = player.getUniqueId();
        busy.add(id);
        final World world = player.getWorld();
        final boolean wasSneaking = player.isSneaking();
        final boolean move = inTown(player);
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 30) {
                if (player.isOnline()) {
                    player.setSneaking(wasSneaking);
                }
                busy.remove(id);
                task.cancel();
                return;
            }
            if (tick == 0) {
                // 張開雙手
                player.swingMainHand();
                player.swingOffHand();
                world.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.5f, 1.4f);
            }
            if (tick == 4 || tick == 16) {
                // 小跳、往後勾腳
                if (move && player.isOnGround()) {
                    player.setVelocity(player.getVelocity().setY(0.32));
                }
                player.setSneaking(true);
                if (tick == 4) {
                    player.swingOffHand();
                } else {
                    player.swingMainHand();
                }
                final Location foot = player.getLocation().subtract(player.getLocation().getDirection().setY(0).normalize().multiply(0.5)).add(0, 0.4, 0);
                world.spawnParticle(Particle.CLOUD, foot, 4, 0.12, 0.08, 0.12, 0.02);
                world.spawnParticle(Particle.CRIT, foot, 6, 0.15, 0.15, 0.15, 0.15);
                world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.6f, 1.5f);
            }
            if (tick == 8 || tick == 20) {
                player.setSneaking(false);
            }
            if (tick == 12) {
                // 交叉步
                player.swingMainHand();
                world.spawnParticle(Particle.POOF, player.getLocation().add(0, 0.1, 0), 4, 0.25, 0.02, 0.25, 0.01);
                world.playSound(player.getLocation(), Sound.BLOCK_WOOL_STEP, 0.6f, 1.2f);
            }
            if (tick == 26) {
                player.swingMainHand();
                player.swingOffHand();
            }
        });
    }

    private static boolean solid(final World world, final Vector at) {
        final Block block = world.getBlockAt(at.getBlockX(), (int) Math.floor(at.getY() - 0.15), at.getBlockZ());
        return !block.isPassable();
    }

    private void remove(final ItemDisplay ball, final World world) {
        if (ball.isValid()) {
            world.spawnParticle(Particle.POOF, ball.getLocation(), 5, 0.1, 0.1, 0.1, 0.02);
            ball.remove();
        }
        balls.remove(ball);
    }

    // ---- 狗熊哆嗦毛 ----

    /**
     * 照影片：兩腳張開蹲低，兩手在身前、頭上亂甩，身子左右扭、甩頭，最後兩手高舉成 X（約 2 秒）。
     * 用蹲起、左右手輪流揮、甩頭與水花、毛屑表現。
     */
    private void bearShake(final Player player) {
        final UUID id = player.getUniqueId();
        busy.add(id);
        final World world = player.getWorld();
        final boolean wasSneaking = player.isSneaking();
        world.playSound(player.getLocation(), Sound.ENTITY_POLAR_BEAR_AMBIENT, 0.8f, 0.9f);
        world.playSound(player.getLocation(), Sound.ENTITY_WOLF_SHAKE, 1f, 0.7f);
        final ItemStack fur = new ItemStack(Material.BROWN_WOOL);
        final float[] turned = {0};
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick >= 40) {
                if (player.isOnline()) {
                    turnHead(player, -turned[0], 0);
                    player.setSneaking(wasSneaking);
                }
                busy.remove(id);
                task.cancel();
                return;
            }
            if (tick < 32) {
                if (tick % 2 == 0) {
                    player.setSneaking(tick % 6 != 4); // 大多蹲著，偶爾站起來一下
                    if (tick % 4 == 0) {
                        player.swingMainHand();
                    } else {
                        player.swingOffHand();
                    }
                }
                // 身子左右扭、甩頭
                final float yaw = (float) (Math.sin(tick / 4.0 * Math.PI) * 32);
                turnHead(player, yaw - turned[0], 0);
                turned[0] = yaw;
                if (tick == 12 || tick == 24) {
                    world.playSound(player.getLocation(), Sound.ENTITY_WOLF_SHAKE, 0.9f, 0.8f);
                }
                final Location body = player.getLocation().add(0, 1.0, 0);
                final double angle = tick * 1.3;
                for (int i = 0; i < 3; i++) {
                    final double a = angle + i * Math.PI * 2 / 3;
                    final Location at = body.clone().add(Math.cos(a) * 0.45, (i - 1) * 0.35, Math.sin(a) * 0.45);
                    world.spawnParticle(Particle.SPLASH, at, 4, 0.05, 0.05, 0.05, 0.1);
                }
                world.spawnParticle(Particle.FALLING_WATER, body, 2, 0.35, 0.5, 0.35, 0);
                if (tick % 3 == 0) {
                    world.spawnParticle(Particle.ITEM, body, 2, 0.25, 0.35, 0.25, 0.12, fur);
                }
            } else if (tick == 32) {
                // 兩手高舉
                turnHead(player, -turned[0], 0);
                turned[0] = 0;
                player.setSneaking(false);
                player.swingMainHand();
                player.swingOffHand();
                world.spawnParticle(Particle.SPLASH, player.getLocation().add(0, 2.1, 0), 12, 0.4, 0.1, 0.4, 0.2);
                world.playSound(player.getLocation(), Sound.ENTITY_POLAR_BEAR_WARNING, 0.5f, 1.3f);
            }
        });
    }

    // ---- 搖頭晃腦 ----

    /**
     * 只轉頭（相對的）。2026-10：原本用「相對傳送」轉，在這版伺服器會把人傳到 (0, 0, 0) 掉進虛空，改用 setRotation（位置完全不動）。
     */
    private static void turnHead(final Player player, final float yaw, final float pitch) {
        if (Math.abs(yaw) < 0.01f && Math.abs(pitch) < 0.01f) {
            return;
        }
        final Location at = player.getLocation();
        player.setRotation(at.getYaw() + yaw, Math.max(-90f, Math.min(90f, at.getPitch() + pitch)));
    }

    private void headBob(final Player player) {
        final UUID id = player.getUniqueId();
        busy.add(id);
        final World world = player.getWorld();
        final boolean wasSneaking = player.isSneaking();
        final float[] applied = {0, 0}; // 到現在轉了多少（結束時轉回去）
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 40) {
                if (player.isOnline()) {
                    turnHead(player, -applied[0], -applied[1]);
                    player.setSneaking(wasSneaking);
                }
                busy.remove(id);
                task.cancel();
                return;
            }
            if (tick == 0) {
                player.setSneaking(true); // 馬步
                player.swingMainHand();   // 一手伸出去
                world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.8f);
            }
            if (tick >= 4 && tick < 34) {
                // 頭左右快速甩：6 tick 一來回，帶一點上下
                final double phase = (tick - 4) / 6.0 * Math.PI * 2;
                final float yaw = (float) (Math.sin(phase) * 34);
                final float pitch = (float) (Math.sin(phase * 2) * 6);
                turnHead(player, yaw - applied[0], pitch - applied[1]);
                applied[0] = yaw;
                applied[1] = pitch;
                if ((tick - 4) % 3 == 0) {
                    final Location head = player.getLocation().add(0, 1.55, 0);
                    world.spawnParticle(Particle.CLOUD, head, 1, 0.15, 0.05, 0.15, 0.01);
                    world.playSound(head, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.25f, 1.9f);
                }
                if (tick % 10 == 0) {
                    player.swingOffHand();
                }
            }
            if (tick == 34) {
                turnHead(player, -applied[0], -applied[1]);
                applied[0] = 0;
                applied[1] = 0;
                player.setSneaking(false); // 收回站好
                player.swingMainHand();
            }
        });
    }

    /** 我真的特別愛你（沒有 BetterModel）：慢慢左右搖頭、揮手，大約 3.2 秒（2026-10：不冒愛心、放慢）。 */
    private void loveYouVanilla(final Player player) {
        final UUID id = player.getUniqueId();
        busy.add(id);
        final World world = player.getWorld();
        final float[] applied = {0};
        final int[] age = {0};
        world.playSound(player.getLocation(), Sound.ENTITY_CAT_PURREOW, 0.6f, 1.4f);
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 64) {
                if (player.isOnline()) {
                    turnHead(player, -applied[0], 0);
                }
                busy.remove(id);
                task.cancel();
                return;
            }
            final float yaw = (float) (Math.sin(tick / 8.0 * Math.PI) * 14); // 一來一回 0.8 秒
            turnHead(player, yaw - applied[0], 0);
            applied[0] = yaw;
            if (tick % 8 == 0) {
                player.swingMainHand();
            }
        });
    }

    /** 67（沒有 BetterModel）：左右手輪流揮、頭左右歪，大約 2.5 秒。 */
    private void sixSevenVanilla(final Player player) {
        final UUID id = player.getUniqueId();
        busy.add(id);
        final World world = player.getWorld();
        final float[] applied = {0};
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 50) {
                if (player.isOnline()) {
                    turnHead(player, -applied[0], 0);
                }
                busy.remove(id);
                task.cancel();
                return;
            }
            if (tick % 4 == 0) {
                final boolean left = tick % 8 == 0;
                if (left) {
                    player.swingOffHand();
                } else {
                    player.swingMainHand();
                }
                final float yaw = left ? 8 : -8;
                turnHead(player, yaw - applied[0], 0);
                applied[0] = yaw;
                world.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, left ? 1.2f : 1.5f);
            }
        });
    }

    /**
     * 67 頭上的「67」（2026-10）：一個文字顯示騎在玩家頭上（跟著走、所有人都看得到），在名字上面；
     * 跟著兩手掂的節奏一大一小地跳（0.3 秒起每 0.21 秒），演完收掉。
     */
    private void sixSevenLabel(final Player player, final long ticks) {
        final org.bukkit.entity.TextDisplay label = player.getWorld().spawn(player.getLocation().add(0, 2.2, 0),
                org.bukkit.entity.TextDisplay.class, display -> {
                    display.text(plugin.text().parse("<b><gradient:#ffd23a:#ff6a3d>67</gradient></b>"));
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    display.setShadowed(true);
                    display.setSeeThrough(false);
                    display.setPersistent(false);
                    display.setInterpolationDuration(3);
                    display.setTransformation(sixSevenScale(1.6f));
                });
        if (!player.addPassenger(label)) {
            label.remove();
            return;
        }
        sixSevenLabels.add(label);
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!label.isValid() || !player.isOnline() || player.isDead() || tick >= ticks) {
                if (label.isValid()) {
                    label.remove();
                }
                sixSevenLabels.remove(label);
                task.cancel();
                return;
            }
            // 每 0.21 秒（約 4 tick）換一手：大小跟著跳一下
            if (tick >= 6 && (tick - 6) % 4 == 0) {
                label.setInterpolationDelay(0);
                label.setTransformation(sixSevenScale((tick - 6) % 8 == 0 ? 2.0f : 1.6f));
            }
        });
    }

    /** 67 的文字大小（往上抬，在名字上面）。 */
    private static Transformation sixSevenScale(final float scale) {
        return new Transformation(new Vector3f(0, 0.7f, 0), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf());
    }

    /** 關服、重讀：還在跑的動作停掉、球收掉。 */
    void stop() {
        for (final BukkitTask task : new ArrayList<>(running)) {
            task.cancel();
        }
        running.clear();
        for (final ItemDisplay ball : new ArrayList<>(balls)) {
            if (ball.isValid()) {
                ball.remove();
            }
        }
        balls.clear();
        for (final org.bukkit.entity.TextDisplay label : new ArrayList<>(sixSevenLabels)) {
            if (label.isValid()) {
                label.remove();
            }
        }
        sixSevenLabels.clear();
        for (final UUID owner : new ArrayList<>(sofas.keySet())) {
            removeSofa(owner);
        }
        busy.clear();
    }
}
