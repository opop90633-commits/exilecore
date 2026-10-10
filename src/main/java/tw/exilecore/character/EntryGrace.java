package tw.exilecore.character;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import tw.exilecore.ExileCore;

/**
 * 入場保護（PoE 的 grace period）：傳送進地圖、傳送到檢查點、登入、重生之後，還沒移動前不會受到任何傷害，怪物也不會盯上你。
 * 動了（按方向鍵或跳）、放技能、打到東西（召喚物打到也算）、再傳送、死亡就解除；被怪物推開超過 {@link #MOVE_DISTANCE} 格也算動了。
 * 在營地（本來就不會被打）不給。沒有時間上限：站著不動可以一直安全（跟 PoE 一樣）。
 * <p>
 * 要給保護的地方（進地圖、傳點…）在傳送「之後」呼叫 {@link #grant}：傳送本身會先把舊的保護收掉。
 */
public final class EntryGrace implements Listener {

    /** 離開落點幾格（水平）算移動了：被怪推一下、落地滑一點不算。 */
    static final double MOVE_DISTANCE = 1.0;
    /** 動作列上的字。 */
    static final String HUD = "<#9fe8c8>⛨ 入場保護<gray>（移動後解除）";
    private static final Particle.DustOptions RING = new Particle.DustOptions(Color.fromRGB(0x9FE8C8), 0.9f);

    private final ExileCore plugin;
    private final PlayerStateService states;

    EntryGrace(final ExileCore plugin, final PlayerStateService states) {
        this.plugin = plugin;
        this.states = states;
    }

    /** 給保護：以玩家現在的位置為落點。在營地、已經死了就不給。 */
    public void grant(final Player player) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        final Location here = player.getLocation();
        if (plugin.zones() != null && plugin.zones().inTown(here)) {
            return;
        }
        final PlayerState state = states.get(player);
        state.setSafeSpot(here);
        states.display(player, state);
    }

    /** 這個玩家在入場保護中嗎。 */
    public boolean isSafe(final Player player) {
        final PlayerState state = states.all().get(player.getUniqueId());
        return state != null && state.isSafe();
    }

    /** 這個實體是在入場保護中的玩家嗎。 */
    public boolean isSafe(final Entity entity) {
        return entity instanceof Player player && isSafe(player);
    }

    /**
     * 解除保護。
     *
     * @param announce 要不要在動作列提示（移動、施放之類玩家自己做的事提示；傳送、離線不用）
     */
    public void end(final Player player, final boolean announce) {
        final PlayerState state = states.all().get(player.getUniqueId());
        if (state == null || !state.isSafe()) {
            return;
        }
        state.setSafeSpot(null);
        if (announce && player.isOnline()) {
            state.notice("<gray>入場保護結束", plugin.getServer().getCurrentTick() + 40);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.5f, 0.8f);
            states.display(player, state);
        }
    }

    /** 每 10 tick（由 {@link PlayerStateService#tick} 呼叫）：走離落點就解除；還在就在腳邊畫一圈。 */
    void tick(final Player player, final PlayerState state) {
        final Location spot = state.safeSpot();
        if (spot == null) {
            return;
        }
        final Location here = player.getLocation();
        if (!here.getWorld().equals(spot.getWorld())) {
            end(player, false);
            return;
        }
        final double dx = here.getX() - spot.getX();
        final double dz = here.getZ() - spot.getZ();
        if (dx * dx + dz * dz > MOVE_DISTANCE * MOVE_DISTANCE) {
            end(player, true);
            return;
        }
        for (int i = 0; i < 8; i++) {
            final double angle = Math.PI * 2 * i / 8;
            here.getWorld().spawnParticle(Particle.DUST, here.getX() + Math.cos(angle) * 0.7, here.getY() + 0.1, here.getZ() + Math.sin(angle) * 0.7,
                    1, 0, 0, 0, 0, RING);
        }
    }

    // ---- 解除的時機 ----

    /** 按了方向鍵或跳：動了。 */
    @EventHandler
    public void onInput(final PlayerInputEvent event) {
        final var input = event.getInput();
        if ((input.isForward() || input.isBackward() || input.isLeft() || input.isRight() || input.isJump()) && isSafe(event.getPlayer())) {
            end(event.getPlayer(), true);
        }
    }

    /** 任何傳送都先收掉；要給的地方（進地圖、傳點）在傳送之後再給。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        end(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        end(event.getEntity(), false);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        end(event.getPlayer(), false);
    }

    // ---- 給的時機（進地圖、傳點在 MapService、ZoneService 裡；這裡是登入與重生） ----

    /** 登入：人在哪就在哪給（地圖關了被送回營地的那幾 tick 之後再看）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> grant(player), 10);
    }

    /** 重生在檢查點：跟 PoE 一樣有保護。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> grant(player), 2);
    }

    // ---- 保護的內容 ----

    /** 不會受到任何傷害（怪物、招式、地面效果、環境都算）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (isSafe(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** 怪物不會把保護中的玩家當目標（原版 AI 與我們自己 setTarget 的都會經過這裡）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(final EntityTargetLivingEntityEvent event) {
        if (isSafe(event.getTarget())) {
            event.setCancelled(true);
        }
    }
}
