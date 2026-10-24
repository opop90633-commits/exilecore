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
 *   <li>狗熊哆嗦毛（bear_shake）：蹲低亂甩手、扭身甩頭、甩出水花跟毛，最後兩手高舉。2026-10-15：跟影片一樣左右、前後跨步繞小圈、小跳
 *       （<b>只有營地裡會真的移動</b>，跟閃身步一樣）。</li>
 *   <li>搖頭晃腦（head_bob）：馬步、一手伸出去，頭左右快速甩動。</li>
 *   <li>虛式「茈」（hollow_purple，2026-10-24）：舉手凝出蒼（藍）、赫（紅），合成茈（紫）往前射出一道光束（只有粒子，不會傷人）。</li>
 *   <li>被「世界斬」腰斬（world_slash，2026-10-24）：抱胸站著，一道斬線掃過腰，上半身往後仰倒到地上、腳也往後倒平，躺一陣子再爬起來。</li>
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
                tw.exilecore.gui.ActionBar.send(player, plugin.text().parse("<gray>動作冷卻中（" + String.format("%.1f", (COOLDOWN_MILLIS - (now - last)) / 1000.0) + " 秒）"));
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
            case "hollow_purple" -> hollowPurpleVanilla(player);
            case "world_slash" -> worldSlashVanilla(player);
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
            case "hollow_purple" -> 5050;
            case "world_slash" -> 6100;
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
                bearSteps(player, BEAR_STEPS);
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
            case "hollow_purple" -> hollowPurpleEffects(player);
            case "world_slash" -> worldSlashEffects(player);
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
     * 狗熊哆嗦毛的跨步（2026-10-15，照影片）：{第幾 tick, 往右幾像素（負的往左）, 往前幾像素（負的往後）, 往上跳多少（速度）}。
     * 左右來回之外再帶前後，整段走起來像在原地繞小圈（右前 → 左前 → 左後 → 右後…）。一像素大約 0.04 的速度（閃身步 10 像素＝0.4，大約半格）。
     */
    private static final double[][] BEAR_STEPS = {
            {5, 6, 4, 0}, {10, -5, 5, 0}, {20, -6, -2, 0}, {25, 6, -6, 0}, {31, 6, 2, 0}, {35, -6, 6, 0.25},
            {44, -4, -4, 0}, {52, 3, -3, 0}, {60, 3, 3, 0}, {68, -3, 3, 0}, {72, -6, -2, 0}, {76, 5, -3, 0}};
    /** 沒有 BetterModel 的版本（約 2 秒）：跨步的時間壓短，一樣繞一小圈。 */
    private static final double[][] BEAR_STEPS_VANILLA = {
            {3, 6, 4, 0}, {8, -6, 5, 0}, {14, -6, -4, 0}, {20, 6, -5, 0.25}, {26, 4, 3, 0}, {32, -4, -3, 0}};

    /** 照表跨步（左右＋前後；只有營地裡真的移動；其他地方不動，贊助不影響戰鬥）。 */
    private void bearSteps(final Player player, final double[][] steps) {
        if (!inTown(player)) {
            return;
        }
        final Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-4) {
            forward.setX(0).setZ(1);
        }
        forward.normalize();
        final Vector right = new Vector(-forward.getZ(), 0, forward.getX()); // 玩家的右手邊（跟閃身步一樣）
        for (final double[] step : steps) {
            later(player, (long) step[0], at -> {
                player.setVelocity(right.clone().multiply(step[1] * 0.04).add(forward.clone().multiply(step[2] * 0.04))
                        .setY(step[3] > 0 ? step[3] : 0.04));
                at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 0.15, 0), 3, 0.2, 0.03, 0.2, 0.01);
            });
        }
    }

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
        bearSteps(player, BEAR_STEPS_VANILLA); // 2026-10-15：左右跨步
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

    // ---- 虛式「茈」 ----

    /** 玩家面向的水平方向（長度 1；朝正上下看時當作朝南）。 */
    private static Vector flatForward(final Player player) {
        final Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-4) {
            forward.setX(0).setZ(1);
        }
        return forward.normalize();
    }

    /** 玩家的右手邊（跟閃身步的 effects 一樣）。 */
    private static Vector rightOf(final Vector forward) {
        return new Vector(-forward.getZ(), 0, forward.getX());
    }

    /**
     * 虛式「茈」的粒子（2026-10-24，時間對著 gen_emotes.py 的每一拍）：8 tick 右手旁凝出蒼（藍球）、24 tick 左手旁凝出赫（紅球）、
     * 41 tick 兩球往身前靠合成茈（紫球、越來越大）、55 tick 射出去：一道紫色光束往前最多 24 格，碰到方塊就炸開。
     * 只有粒子與音效，不會傷人（贊助不影響戰鬥）。手的位置跟著玩家轉頭算，所以邊轉邊做也對得上。
     */
    private void hollowPurpleEffects(final Player player) {
        final World world = player.getWorld();
        final Color blue = Color.fromRGB(0x3f8cff);
        final Color red = Color.fromRGB(0xff3b3b);
        final Color purple = Color.fromRGB(0xa64dff);
        later(player, 8, at -> world.playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f));
        later(player, 24, at -> world.playSound(at, Sound.ENTITY_BLAZE_AMBIENT, 0.8f, 0.6f));
        later(player, 41, at -> world.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 0.7f));
        later(player, 53, at -> world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 0.5f));
        final double[] beam = {0}; // 光束已經射到多遠（格）
        final boolean[] hit = {false};
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 100) {
                task.cancel();
                return;
            }
            final Location eye = player.getEyeLocation();
            final Vector forward = flatForward(player);
            final Vector right = rightOf(forward);
            final Location rightHand = eye.clone().add(forward.clone().multiply(0.45)).add(right.clone().multiply(0.45)).add(0, 0.15, 0);
            final Location leftHand = eye.clone().add(forward.clone().multiply(0.2)).add(right.clone().multiply(-0.9)).add(0, -0.3, 0);
            final Location front = eye.clone().add(forward.clone().multiply(1.3)).add(0, -0.2, 0);
            if (tick >= 8 && tick < 41) {
                orb(world, rightHand, blue, 0.12 + 0.18 * Math.min(1, (tick - 8) / 12.0), tick);
            }
            if (tick >= 24 && tick < 41) {
                orb(world, leftHand, red, 0.12 + 0.18 * Math.min(1, (tick - 24) / 12.0), tick);
            }
            if (tick >= 41 && tick < 55) {
                // 蒼、赫往身前靠，合成茈（越來越大、冒紫煙）
                final double f = Math.min(1, (tick - 41) / 8.0);
                orb(world, lerp(rightHand, front, f), blue, 0.3 * (1 - f) + 0.05, tick);
                orb(world, lerp(leftHand, front, f), red, 0.3 * (1 - f) + 0.05, tick);
                orb(world, front, purple, 0.2 + 0.45 * f, tick);
                world.spawnParticle(Particle.WITCH, front, 3, 0.25, 0.25, 0.25, 0);
                if (tick % 3 == 0) {
                    world.spawnParticle(Particle.END_ROD, front, 2, 0.3, 0.3, 0.3, 0.02);
                }
            }
            if (tick == 55) {
                world.spawnParticle(Particle.FLASH, front, 1);
                world.spawnParticle(Particle.EXPLOSION, front, 1);
                world.playSound(front, Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1.3f);
                world.playSound(front, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.8f);
                world.playSound(front, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 0.6f, 1.4f);
                world.spawnParticle(Particle.CLOUD, player.getLocation().add(0, 0.2, 0), 10, 0.5, 0.05, 0.5, 0.05);
            }
            if (tick > 55 && tick <= 75 && !hit[0]) {
                // 光束一 tick 往前 1.5 格：一圈圈紫色的環，碰到方塊就炸開停下
                final double from = beam[0];
                final double to = Math.min(24, from + 1.5);
                final Location origin = eye.clone().add(0, -0.2, 0);
                final Vector up = new Vector(0, 1, 0);
                for (double d = from; d < to; d += 0.5) {
                    final Location at = origin.clone().add(forward.clone().multiply(1.3 + d));
                    if (!at.getBlock().isPassable()) {
                        hit[0] = true;
                        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
                        world.spawnParticle(Particle.DUST, at, 40, 1.2, 1.2, 1.2, 0, new Particle.DustOptions(purple, 1.6f));
                        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.7f);
                        break;
                    }
                    final double radius = 0.9;
                    for (int i = 0; i < 10; i++) {
                        final double angle = Math.PI * 2 * i / 10 + tick * 0.4;
                        final Vector offset = right.clone().multiply(Math.cos(angle) * radius).add(up.clone().multiply(Math.sin(angle) * radius));
                        world.spawnParticle(Particle.DUST, at.clone().add(offset), 1, 0.02, 0.02, 0.02, 0, new Particle.DustOptions(purple, 1.5f));
                    }
                    world.spawnParticle(Particle.DUST, at, 2, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(0xe6c8ff), 1.2f));
                    if (tick % 2 == 0) {
                        world.spawnParticle(Particle.DRAGON_BREATH, at, 1, 0.2, 0.2, 0.2, 0.01);
                    }
                }
                beam[0] = to;
                if (tick % 4 == 0 && !hit[0]) {
                    world.spawnParticle(Particle.SONIC_BOOM, origin.clone().add(forward.clone().multiply(1.3 + to)), 1);
                }
            }
        });
    }

    /** 一顆會轉的光球：中間一點大的、外面一圈小的繞著轉。 */
    private static void orb(final World world, final Location centre, final Color colour, final double radius, final int tick) {
        world.spawnParticle(Particle.DUST, centre, 1, 0.03, 0.03, 0.03, 0, new Particle.DustOptions(colour, (float) (1.2 + radius * 2)));
        for (int i = 0; i < 6; i++) {
            final double angle = Math.PI * 2 * i / 6 + tick * 0.5;
            final double tilt = Math.sin(tick * 0.3 + i) * radius * 0.6;
            world.spawnParticle(Particle.DUST, centre.clone().add(Math.cos(angle) * radius, tilt, Math.sin(angle) * radius), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(colour, 0.8f));
        }
    }

    private static Location lerp(final Location from, final Location to, final double f) {
        return from.clone().add(to.clone().subtract(from).toVector().multiply(f));
    }

    /** 虛式「茈」（沒有 BetterModel）：照拍子揮手，粒子、音效一樣。 */
    private void hollowPurpleVanilla(final Player player) {
        final UUID id = player.getUniqueId();
        busy.add(id);
        hollowPurpleEffects(player);
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 100) {
                busy.remove(id);
                task.cancel();
                return;
            }
            switch (tick) {
                case 8, 55 -> player.swingMainHand();
                case 24 -> player.swingOffHand();
                case 41 -> {
                    player.swingMainHand();
                    player.swingOffHand();
                }
                default -> {
                }
            }
        });
    }

    // ---- 被「世界斬」腰斬 ----

    /**
     * 世界斬的粒子（2026-10-24，時間對著 gen_emotes.py）：23 tick 一道白色的斬線從左掃到右、橫過腰的高度、兩邊各 14 格長，
     * 之後淡掉；腰冒血 2 秒；41 tick 上半身落地的悶響、58 tick 腳倒平。只有粒子與音效，不會傷人。
     */
    private void worldSlashEffects(final Player player) {
        final World world = player.getWorld();
        final Color white = Color.fromRGB(0xf4f4ff);
        final Color blood = Color.fromRGB(0xb0001e);
        final org.bukkit.block.data.BlockData redstone = Material.REDSTONE_BLOCK.createBlockData();
        final Location[] cut = {null}; // 斬線的位置（斬下去那一刻定住，之後人倒了線不跟著動）
        final Vector[] axis = {null};
        later(player, 41, at -> {
            world.playSound(at, Sound.ENTITY_GENERIC_BIG_FALL, 0.7f, 0.8f);
            world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.1, 0), 8, 0.6, 0.05, 0.6, 0.02);
        });
        later(player, 58, at -> {
            world.playSound(at, Sound.ENTITY_GENERIC_SMALL_FALL, 0.6f, 0.9f);
            world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.1, 0), 5, 0.4, 0.05, 0.4, 0.02);
        });
        later(player, 98, at -> world.playSound(at, Sound.ENTITY_PLAYER_BREATH, 0.6f, 0.8f));
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 70) {
                task.cancel();
                return;
            }
            if (tick == 23) {
                final Location waist = player.getLocation().add(0, 1.0, 0);
                cut[0] = waist;
                axis[0] = rightOf(flatForward(player));
                world.playSound(waist, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.5f);
                world.playSound(waist, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.5f, 1.7f);
                world.playSound(waist, Sound.BLOCK_GLASS_BREAK, 0.7f, 0.5f);
                world.spawnParticle(Particle.SWEEP_ATTACK, waist, 1);
            }
            if (cut[0] == null) {
                return;
            }
            final int since = tick - 23;
            if (since <= 2) {
                // 從左掃到右：3 tick 掃完 28 格
                final double from = -14 + since * 28 / 3.0;
                final double to = from + 28 / 3.0;
                for (double d = from; d < to; d += 0.3) {
                    final Location at = cut[0].clone().add(axis[0].clone().multiply(d));
                    world.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(white, 1.6f));
                }
            } else if (since < 20) {
                // 線留著慢慢淡掉
                final float size = 1.4f - since * 0.06f;
                for (double d = -14; d <= 14; d += 0.6) {
                    final Location at = cut[0].clone().add(axis[0].clone().multiply(d));
                    world.spawnParticle(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0, new Particle.DustOptions(white, size));
                }
            }
            if (since >= 1 && since < 45 && tick % 2 == 0) {
                // 腰冒血（人倒了就跟著人）
                final Location waist = player.getLocation().add(0, since < 18 ? 1.0 : 0.4, 0);
                world.spawnParticle(Particle.DUST, waist, 4, 0.3, 0.1, 0.3, 0, new Particle.DustOptions(blood, 1.1f));
                world.spawnParticle(Particle.BLOCK, waist, 3, 0.25, 0.1, 0.25, 0.05, redstone);
            }
        });
    }

    /** 被「世界斬」腰斬（沒有 BetterModel）：站著不動，斬線掃過之後蹲下（倒地）一陣子再站起來；粒子、音效一樣。 */
    private void worldSlashVanilla(final Player player) {
        final UUID id = player.getUniqueId();
        busy.add(id);
        final boolean wasSneaking = player.isSneaking();
        worldSlashEffects(player);
        final float[] applied = {0};
        final int[] age = {0};
        repeat(task -> {
            final int tick = age[0]++;
            if (!player.isOnline() || player.isDead() || tick > 112) {
                if (player.isOnline()) {
                    turnHead(player, 0, -applied[0]);
                    player.setSneaking(wasSneaking);
                }
                busy.remove(id);
                task.cancel();
                return;
            }
            if (tick == 23) {
                player.swingMainHand();
                turnHead(player, 0, 25 - applied[0]); // 低頭看腰
                applied[0] = 25;
            }
            if (tick == 30) {
                player.setSneaking(true);
            }
            if (tick == 41) {
                turnHead(player, 0, 45 - applied[0]);
                applied[0] = 45;
            }
            if (tick == 100) {
                turnHead(player, 0, -applied[0]);
                applied[0] = 0;
                player.setSneaking(false);
            }
        });
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
