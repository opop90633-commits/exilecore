package tw.exilecore.clientmod;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.map.MapInstance;
import tw.exilecore.map.MapLayout;
import tw.exilecore.map.MapService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-02 照站長（C 組）：模組的小地圖（{@link FeedProtocol#MINIMAP}）。這次之前沒有小地圖。
 * <p>
 * 每 {@value #INTERVAL} tick 看一次招呼說 minimap:true 的人在哪張圖：換了圖送一次整張（格子、房間、Boss 房、事件），
 * 之後只送變化（事件開始／完成、Boss 出來／開打／死了、Boss 走了 {@value #BOSS_MOVE} 格以上），隊友的位置每 {@value #PARTY_EVERY} 次送一次；
 * 離開地圖送 clear。沒裝模組的人什麼都不送。
 */
final class MinimapFeed {

    /** 多久看一次（tick）。 */
    static final long INTERVAL = 10;
    /** 隊友的位置每幾次送一次（20 tick）。 */
    static final int PARTY_EVERY = 2;
    /** Boss 走多遠才重送位置（格）。 */
    static final double BOSS_MOVE = 2;

    /** 一個人現在看到的。 */
    private static final class Seen {
        int mapId = -1;
        List<FeedProtocol.Marker> markers = List.of();
        FeedProtocol.Boss boss;
        int round;
        String party = "";
    }

    private final ExileCore plugin;
    private final ClientFeeds feeds;
    private final Map<UUID, Seen> seen = new HashMap<>();

    MinimapFeed(final ExileCore plugin, final ClientFeeds feeds) {
        this.plugin = plugin;
        this.feeds = feeds;
    }

    void clear() {
        seen.clear();
    }

    void forget(final UUID uuid) {
        seen.remove(uuid);
    }

    /** 下一次重送整張。 */
    void resend(final UUID uuid) {
        final Seen state = seen.get(uuid);
        if (state != null) {
            state.mapId = -1;
        }
    }

    void tick() {
        final MapService maps = plugin.maps();
        for (final UUID uuid : feeds.players()) {
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !feeds.minimap(player)) {
                continue;
            }
            try {
                update(player, maps);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("星骸輔助模組：送 {} 的小地圖出錯", player.getName(), e);
            }
        }
        seen.keySet().removeIf(uuid -> !feeds.players().contains(uuid));
    }

    private void update(final Player player, final MapService maps) {
        final MapInstance instance = maps == null ? null : maps.instanceAt(player.getLocation());
        final Seen state = seen.computeIfAbsent(player.getUniqueId(), uuid -> new Seen());
        if (instance == null || instance.layout() == null) {
            if (state.mapId >= 0) {
                feeds.send(player, FeedProtocol.MINIMAP, FeedProtocol.minimapClear());
            }
            state.mapId = -2; // -2 = 不在地圖裡（已經送過 clear）
            return;
        }
        final List<FeedProtocol.Marker> markers = maps.markers(instance);
        final FeedProtocol.Boss boss = boss(instance);
        if (state.mapId != instance.id()) {
            state.mapId = instance.id();
            state.markers = markers;
            state.boss = boss;
            state.party = "";
            feeds.send(player, FeedProtocol.MINIMAP, FeedProtocol.minimapInit(instance.id(), instance.originX(), instance.originZ(),
                    instance.layout(), boss, markers));
        } else {
            final List<FeedProtocol.Marker> changed = FeedProtocol.changed(state.markers, markers);
            if (!changed.isEmpty()) {
                feeds.send(player, FeedProtocol.MINIMAP, FeedProtocol.minimapEvents(changed));
            }
            state.markers = markers;
            if (bossChanged(state.boss, boss)) {
                state.boss = boss;
                feeds.send(player, FeedProtocol.MINIMAP, FeedProtocol.minimapBoss(boss));
            }
        }
        if (++state.round % PARTY_EVERY == 0) {
            final List<FeedProtocol.Mate> mates = new ArrayList<>();
            final StringBuilder signature = new StringBuilder();
            for (final UUID other : instance.inside()) {
                final Player mate = plugin.getServer().getPlayer(other);
                if (mate == null || mate.equals(player) || !mate.getWorld().equals(player.getWorld())) {
                    continue;
                }
                final Location at = mate.getLocation();
                mates.add(new FeedProtocol.Mate(mate.getName(), at.getX(), at.getZ()));
                signature.append(mate.getName()).append(at.getBlockX()).append(',').append(at.getBlockZ()).append(';');
            }
            if (!signature.toString().equals(state.party)) {
                state.party = signature.toString();
                feeds.send(player, FeedProtocol.MINIMAP, FeedProtocol.minimapParty(mates));
            }
        }
    }

    /** Boss：還沒出來在 Boss 房中間（wait）；出來了照實體的位置（alive，開打了 fight）；打倒了 dead。 */
    private static FeedProtocol.Boss boss(final MapInstance instance) {
        final MapLayout.Room room = instance.layout().boss();
        final double roomX = room == null ? instance.originX() : instance.originX() + room.cx() + 0.5;
        final double roomZ = room == null ? instance.originZ() : instance.originZ() + room.cz() + 0.5;
        if (instance.bossKilled()) {
            return new FeedProtocol.Boss(roomX, roomZ, "dead");
        }
        final LivingEntity entity = instance.boss();
        if (entity != null && entity.isValid() && !entity.isDead()) {
            final Location at = entity.getLocation();
            return new FeedProtocol.Boss(at.getX(), at.getZ(), instance.fightStartTick() > 0 ? "fight" : "alive");
        }
        return room == null ? null : new FeedProtocol.Boss(roomX, roomZ, "wait");
    }

    /** 純函式：Boss 的狀態換了、或走了 {@value #BOSS_MOVE} 格以上。 */
    static boolean bossChanged(final FeedProtocol.Boss before, final FeedProtocol.Boss after) {
        if (after == null) {
            return false;
        }
        if (before == null || !before.state().equals(after.state())) {
            return true;
        }
        return Math.hypot(before.x() - after.x(), before.z() - after.z()) >= BOSS_MOVE;
    }
}
