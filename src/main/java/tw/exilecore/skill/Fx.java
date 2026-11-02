package tw.exilecore.skill;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 粒子特效的發送（技能、怪物、地圖的粒子都走這裡；只在主執行緒用）。
 *
 * <p><b>送給誰（2026-11-02 照站長「伺服器粒子太多」）</b>：一律一個人一個人送（{@code player.spawnParticle}），只送給同一個世界、
 * 水平 {@link #viewRange} 格內、上下 {@link #viewRange} 格內的玩家（預設 24 格，config 的 {@code particles.view-range}）。
 * 以前 {@code world.spawnParticle} 在 Paper 預設是「強制顯示」：整個世界 512 格內的人都收到（所有地圖實例在同一個世界），
 * 客戶端的「粒子：最少」也擋不掉。現在只有重要的（{@link #important}：Boss／怪物招式的預警、地圖機制的危險區）才強制顯示、
 * 送到 {@link #importantRange} 格（預設 48，config {@code particles.important-range}）。
 *
 * <p><b>誰的特效</b>（{@link #source}／{@link #as}）＋<b>重要與否</b>（{@link #important}／{@link #with}）＋看的人的
 * <b>特效等級</b>（設定「特效等級」高／中／低）→ 送不送、密度幾成，規則在 {@link FxPolicy#density}。不知道是誰的算環境（照常顯示）。
 * 設定「別人的技能特效」關掉的人不收別人的技能粒子（重要的照常）；別人的技能聲音小聲（{@link #OTHERS_VOLUME}）。
 *
 * <p><b>持續的範圍</b>（地面效果、雲、裂隙、聖化地面、光環、旗幟……）走 {@link #zone}：最多每 {@link FxPolicy#REDRAW_TICKS} tick 重畫一次，
 * 一圈的點數照周長（{@link FxPolicy#ringPoints}）、裡面稀疏撒（{@link FxPolicy#areaPoints}），同一個人疊在一起的同一種只畫一個
 * （{@link FxPolicy.Merge}）。傷害的時間不受影響（只是畫）。一次性的圈用 {@link #ring}；形狀特別、還沒換成 zone 的持續特效用
 * {@link #redrawDue} 限制頻率、{@link #ambient} 標成「範圍裡面的點」。
 *
 * <p><b>客戶端模組</b>：每次要畫的東西都先描述成 {@link FxEffect}（形狀、中心／終點、半徑、粒子與資料、顆數、這個人的密度、持續時間、
 * 穩定編號、誰的、重要與否；欄位說明在 {@link FxEffect}）。{@link #setRouter} 接上 {@link ViewerRouter} 之後，對每個「照規則看得到」的人先問
 * router：回傳 true（這個人由模組畫）就不送粒子給他。持續範圍給 router 的是 {@link FxEffect.Phase#START}（每個人一次，附半徑與持續時間；
 * 後來才走近的人在下一次重畫時收到）、半徑或位置變了 {@link FxEffect.Phase#UPDATE}、結束 {@link FxEffect.Phase#STOP}
 * （{@link #zoneStop}，或 {@link #ZONE_IDLE_TICKS} tick 沒再畫就自動結束；只送給收過 START 的人）；一次性的是 {@link FxEffect.Phase#ONCE}。
 *
 * <p>封包量：附近別人超過 {@link #CROWD_FULL} 個時，別人那份照比例打薄（自己的技能照常全部送）；
 * 送了多少封包記在 {@link FxStats}，{@code /ec skill fx} 看誰送最多。
 */
public final class Fx {

    /** 一般粒子送給多遠的玩家（水平與上下各幾格；config {@code particles.view-range}）。 */
    private static double viewRange = 24;
    /** 重要的粒子送給多遠的玩家（config {@code particles.important-range}）。 */
    private static double importantRange = 48;

    /** 附近別人超過幾個時，別人看到的技能特效開始打薄（{@link FxStats#stride}）。 */
    static final int CROWD_FULL = 10;

    /** 持續範圍幾 tick 沒再畫就算結束（送 STOP 給模組）。 */
    static final int ZONE_IDLE_TICKS = 60;

    /** 誰的技能送了多少封包。 */
    private static final FxStats STATS = new FxStats();
    /** 一顆一顆送的點打薄時的計數。 */
    private static long sequence;

    /** 別人的技能聲音是原本音量的幾倍（一百人同場時不會一片爆炸聲）。 */
    static final float OTHERS_VOLUME = 0.35f;

    /** 現在畫的是誰的（null = 不是玩家的：怪物、Boss、環境）。 */
    private static UUID source;
    /** 現在畫的是重要的（Boss／怪物招式的預警、地圖機制的危險區）。 */
    private static boolean important;
    /** 現在畫的是哪一種（一次性、持續範圍的外框、持續範圍裡面）。 */
    private static FxPolicy.Kind kind = FxPolicy.Kind.ONE_SHOT;

    /** 這個玩家關掉了別人的技能特效。 */
    private static Predicate<Player> hidesOthers = player -> false;
    /** 這個玩家關掉了技能音效（自己的技能也不出聲）。 */
    private static Predicate<Player> mutesOwn = player -> false;
    /** 這個玩家的特效等級。 */
    private static Function<Player, FxPolicy.Quality> quality = player -> FxPolicy.Quality.HIGH;

    /** 客戶端模組接手畫的人（null = 沒有模組，全部送粒子）。 */
    private static ViewerRouter router;

    private Fx() {
    }

    /**
     * 客戶端模組的接口：Fx 決定這個人看得到這個特效（{@code fx.density()} 是他的密度，一定 &gt; 0）之後先問這裡；
     * 回傳 true = 這個人由模組畫，不送粒子給他。只在主執行緒呼叫，要很快（每顆粒子、每個看得到的人都會問）。
     */
    public interface ViewerRouter {
        boolean handle(Player viewer, FxEffect fx);
    }

    /** 接上客戶端模組（null = 拿掉）。 */
    public static void setRouter(final ViewerRouter value) {
        router = value;
    }

    /** 接上設定（角色模組啟用時）。 */
    public static void hidesOthers(final Predicate<Player> test) {
        hidesOthers = test == null ? player -> false : test;
    }

    /** 接上設定「技能音效」。 */
    public static void mutesOwn(final Predicate<Player> test) {
        mutesOwn = test == null ? player -> false : test;
    }

    /** 接上設定「特效等級」。 */
    public static void quality(final Function<Player, FxPolicy.Quality> lookup) {
        quality = lookup == null ? player -> FxPolicy.Quality.HIGH : lookup;
    }

    /** 送多遠（config {@code particles.view-range}、{@code particles.important-range}）。 */
    public static void ranges(final double view, final double importantView) {
        viewRange = Math.max(4, view);
        importantRange = Math.max(viewRange, importantView);
    }

    /** 一般粒子送多遠（格）。 */
    public static double viewRange() {
        return viewRange;
    }

    /**
     * 這個玩家聽到現在這個聲音的音量（0 = 不送）。
     *
     * @param mine   是他自己的技能
     * @param muted  他關掉了技能音效
     * @param hides  他關掉了別人的技能特效
     */
    static float heard(final float volume, final boolean mine, final boolean muted, final boolean hides) {
        if (mine) {
            return muted ? 0 : volume;
        }
        return hides ? 0 : volume * OTHERS_VOLUME;
    }

    /** 技能的聲音：不是玩家的技能照常播給附近每個人；玩家的技能照每個人的設定送。 */
    public static void sound(final Location at, final Sound sound, final SoundCategory category, final float volume, final float pitch) {
        final World world = at == null ? null : at.getWorld();
        if (sound == null || world == null) {
            return;
        }
        if (source == null && !noPackOnly) {
            world.playSound(at, sound, category, volume, pitch);
            return;
        }
        final double range = 16.0 * Math.max(1.0, volume); // 原版聽得到的距離
        final double rangeSquared = range * range;
        int sent = 0;
        final Snapshot snapshot = players(world); // 2026-10-27 效能：這個 tick 的玩家與位置只拿一次
        for (int i = 0; i < snapshot.players.length; i++) {
            final Player viewer = snapshot.players[i];
            final double dx = snapshot.xyz[i * 3] - at.getX();
            final double dy = snapshot.xyz[i * 3 + 1] - at.getY();
            final double dz = snapshot.xyz[i * 3 + 2] - at.getZ();
            if (dx * dx + dy * dy + dz * dz > rangeSquared || !viewer.isConnected()) {
                continue;
            }
            final boolean mine = viewer.getUniqueId().equals(source);
            final float heard = heard(volume, mine, mine && mutesOwn.test(viewer), !mine && snapshot.hides[i]);
            if (heard > 0) {
                viewer.playSound(at, sound, category, heard, pitch);
                sent++;
            }
        }
        STATS.add(Bukkit.getCurrentTick(), source, sent);
    }

    // ---- 誰的、重要與否 ----

    /** 現在畫的是誰的技能（null = 不是玩家的：怪物、Boss、環境），回傳前一個（用完 {@link #restore} 放回去）。 */
    public static UUID source(final UUID player) {
        final UUID previous = source;
        source = player;
        return previous;
    }

    public static void restore(final UUID previous) {
        source = previous;
    }

    /** 當作 owner 的技能畫（owner null = 環境，照常給每個人）。 */
    public static void as(final UUID owner, final Runnable draw) {
        final UUID previous = source(owner);
        try {
            draw.run();
        } finally {
            restore(previous);
        }
    }

    /** 2026-11-02：裡面畫的是重要的（Boss／怪物招式的預警、地圖機制的危險區）：每個人都全部看得到、送遠一點、強制顯示。 */
    public static void important(final Runnable draw) {
        with(null, true, draw);
    }

    /** 2026-11-02：當作 owner 的（null = 環境）、重要與否畫。 */
    public static void with(final UUID owner, final boolean isImportant, final Runnable draw) {
        final UUID previous = source(owner);
        final boolean before = important;
        important = isImportant;
        try {
            draw.run();
        } finally {
            important = before;
            restore(previous);
        }
    }

    /** 現在畫的是誰的（null = 環境）。 */
    public static UUID owner() {
        return source;
    }

    /** 現在畫的是不是重要的。 */
    public static boolean isImportant() {
        return important;
    }

    /**
     * 2026-11-02：裡面畫的是持續範圍裡面撒的點（還沒換成 {@link #zone} 的持續特效用）：低特效等級的人看別人的這種不送。
     */
    public static void ambient(final Runnable draw) {
        final FxPolicy.Kind before = kind;
        kind = FxPolicy.Kind.FILL;
        try {
            draw.run();
        } finally {
            kind = before;
        }
    }

    /** 2026-11-02：照原樣送（不把大爆炸換成貼地的）：外觀特效（贊助的表情動作）用。 */
    private static boolean exact;

    /** 2026-11-02：裡面的粒子照原樣送（{@link #lowProfile} 不換）；外觀特效用，其他規則（距離、特效等級）照常。 */
    public static void exact(final Runnable draw) {
        final boolean before = exact;
        exact = true;
        try {
            draw.run();
        } finally {
            exact = before;
        }
    }

    /** 這個玩家關掉了別人的技能特效。 */
    public static boolean hides(final Player viewer) {
        return hidesOthers.test(viewer);
    }

    /** 2026-10-31：這個玩家套了介面資源包（看得到平面的地面圈）。 */
    private static Predicate<Player> hasPack = player -> false;
    /** 2026-10-31：現在畫的粒子只送給沒套資源包的人（套了的看 {@link GroundDecals} 的平面圖）。 */
    private static boolean noPackOnly;

    /** 接上「有沒有套資源包」。 */
    public static void hasPack(final Predicate<Player> test) {
        hasPack = test == null ? player -> false : test;
    }

    /** 2026-10-31：裡面畫的粒子只送給沒套資源包的人。 */
    public static void withoutPack(final Runnable draw) {
        final boolean previous = noPackOnly;
        noPackOnly = true;
        try {
            draw.run();
        } finally {
            noPackOnly = previous;
        }
    }

    // ---- 2026-10-27 效能：每個世界的玩家、位置與設定每個 tick 只拿一次 ----
    // （所有地圖實例在同一個世界；以前每個粒子點都 world.getPlayers() 複製一份名單、每個人 getLocation() 一次，一招幾百個點就是幾萬次）

    /** 一個世界這個 tick 的玩家、位置與設定。 */
    private static final class Snapshot {
        final Player[] players;
        final UUID[] ids;
        final double[] xyz;
        final FxPolicy.Quality[] quality;
        final boolean[] hides;
        final boolean[] pack;

        Snapshot(final Player[] players) {
            this.players = players;
            this.ids = new UUID[players.length];
            this.xyz = new double[players.length * 3];
            this.quality = new FxPolicy.Quality[players.length];
            this.hides = new boolean[players.length];
            this.pack = new boolean[players.length];
            for (int i = 0; i < players.length; i++) {
                final Player player = players[i];
                final Location at = player.getLocation();
                ids[i] = player.getUniqueId();
                xyz[i * 3] = at.getX();
                xyz[i * 3 + 1] = at.getY();
                xyz[i * 3 + 2] = at.getZ();
                quality[i] = qualityOf(player);
                hides[i] = hidesOthers.test(player);
                pack[i] = hasPack.test(player);
            }
        }
    }

    private static FxPolicy.Quality qualityOf(final Player player) {
        try {
            final FxPolicy.Quality q = quality.apply(player);
            return q == null ? FxPolicy.Quality.HIGH : q;
        } catch (final RuntimeException e) {
            return FxPolicy.Quality.HIGH;
        }
    }

    private static final Map<World, Snapshot> snapshots = new HashMap<>();
    private static int snapshotTick = Integer.MIN_VALUE;

    private static Snapshot players(final World world) {
        final int tick = Bukkit.getCurrentTick();
        if (tick != snapshotTick) {
            snapshots.clear();
            snapshotTick = tick;
            sweepZones(tick);
        }
        return snapshots.computeIfAbsent(world, key -> new Snapshot(key.getPlayers().toArray(new Player[0])));
    }

    /** 設定改了（特效等級、別人的技能特效）：這個 tick 的快取丟掉，下一顆粒子重新拿。 */
    public static void settingsChanged() {
        snapshots.clear();
    }

    /** 這個人看現在這個特效的密度（0 = 不送）。 */
    private static double density(final Snapshot snapshot, final int i, final UUID owner, final boolean isImportant,
                                  final FxPolicy.Kind what) {
        if (noPackOnly && snapshot.pack[i]) {
            return 0; // 2026-10-31：套了資源包的人看平面的地面圈
        }
        return FxPolicy.density(snapshot.quality[i], FxPolicy.Relation.of(owner, snapshot.ids[i]), isImportant, what, snapshot.hides[i]);
    }

    /** 這一點在不在這個人看得到的範圍。 */
    private static boolean near(final Snapshot snapshot, final int i, final double x, final double y, final double z, final double range) {
        return FxPolicy.inView(snapshot.xyz[i * 3] - x, snapshot.xyz[i * 3 + 1] - y, snapshot.xyz[i * 3 + 2] - z, range);
    }

    /** 這個人由客戶端模組畫（router 接手）。 */
    private static boolean routed(final Player viewer, final FxEffect fx) {
        try {
            return router != null && router.handle(viewer, fx);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /** 看得到的人（重複使用，只在主執行緒）。 */
    private static int[] candidates = new int[64];
    private static double[] candidateDensity = new double[64];

    /**
     * 所有一般粒子最後都到這裡：照規則一個人一個人送（完整的參數，跟 {@code Player#spawnParticle} 最長的那個一樣）。
     */
    private static void emit(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                             final double dx, final double dy, final double dz, final double extra, final Object data) {
        if (world == null || particle == null) {
            return;
        }
        final Snapshot snapshot = players(world);
        if (snapshot.players.length == 0) {
            return;
        }
        final boolean isImportant = important;
        final UUID owner = source;
        final double range = isImportant ? importantRange : viewRange;
        if (candidates.length < snapshot.players.length) {
            candidates = new int[snapshot.players.length];
            candidateDensity = new double[snapshot.players.length];
        }
        // 先挑出看得到的人（別人很多時照比例打薄，自己的技能照常全部送）
        int n = 0;
        int others = 0;
        for (int i = 0; i < snapshot.players.length; i++) {
            if (!near(snapshot, i, x, y, z, range)) {
                continue;
            }
            final double d = density(snapshot, i, owner, isImportant, kind);
            if (d <= 0 || skip != null && skip.routedTo.contains(snapshot.ids[i])) {
                continue; // 看不到，或模組在畫這個持續特效
            }
            candidates[n] = i;
            candidateDensity[n] = d;
            n++;
            if (owner != null && !isImportant && !owner.equals(snapshot.ids[i])) {
                others++;
            }
        }
        if (n == 0) {
            return;
        }
        final long seq = sequence++;
        final boolean othersThisPoint = others <= CROWD_FULL || Math.floorMod(seq, FxStats.stride(others, CROWD_FULL)) == 0;
        FxEffect fx = null;
        int sent = 0;
        for (int k = 0; k < n; k++) {
            final int i = candidates[k];
            final double d = candidateDensity[k];
            if (owner != null && !isImportant && !owner.equals(snapshot.ids[i]) && !othersThisPoint) {
                continue;
            }
            final Player viewer = snapshot.players[i];
            if (router != null) {
                if (fx == null) {
                    fx = new FxEffect(count <= 1 ? FxEffect.Shape.POINT : FxEffect.Shape.BURST, FxEffect.Phase.ONCE, 0, world, x, y, z, x, y, z,
                            Math.max(dx, dz), particle, data, dx, dy, dz, extra, count, 1.0, 0, owner, isImportant);
                }
                if (routed(viewer, fx.withDensity(d))) {
                    continue;
                }
            }
            final int amount;
            if (count <= 1) {
                if (!FxPolicy.keep(seq + i * 7L, d)) {
                    continue;
                }
                amount = count;
            } else {
                amount = FxPolicy.scaledCount(count, d);
            }
            if (!viewer.isConnected()) {
                continue;
            }
            viewer.spawnParticle(particle, x, y, z, amount, dx, dy, dz, extra, data, isImportant);
            sent++;
        }
        STATS.add(Bukkit.getCurrentTick(), owner, sent);
    }

    // ---- 統計（/ec skill fx） ----

    /** 上一段時間（10 秒）技能特效送最多封包的前 limit 名。 */
    static List<FxStats.Entry> top(final int limit) {
        return STATS.top(Bukkit.getCurrentTick(), limit);
    }

    /** 上一段時間所有玩家技能特效送出的封包總數。 */
    static long total() {
        return STATS.total(Bukkit.getCurrentTick());
    }

    /** 上一段時間多長（秒）。 */
    static double lastSeconds() {
        return STATS.lastSeconds();
    }

    /**
     * 2026-10-31 照站長（爆炸擋視線）：原版的大爆炸（EXPLOSION、EXPLOSION_EMITTER）、大團煙（LARGE_SMOKE）、閃光（FLASH）
     * 會整片蓋住畫面——換成貼地往外散的一圈小火花＋一點點煙（範圍看得出來、不擋怪物）。所有走 Fx 的特效都適用。
     *
     * @return 換掉了（呼叫的人就不用再送原本的）
     */
    static boolean lowProfile(final World world, final Particle particle, final double x, final double y, final double z) {
        if (world == null) {
            return false;
        }
        if (particle == Particle.FLASH) {
            return true; // 閃光直接不送
        }
        if (particle == Particle.LARGE_SMOKE) {
            emit(world, Particle.SMOKE, x, y, z, 2, 0.25, 0.05, 0.25, 0.01, null);
            return true;
        }
        if (particle != Particle.EXPLOSION && particle != Particle.EXPLOSION_EMITTER) {
            return false;
        }
        final boolean big = particle == Particle.EXPLOSION_EMITTER;
        final double radius = big ? 2.2 : 1.2;
        final int points = big ? 16 : 10;
        final double groundY = y - Math.min(0.6, Math.max(0, y - Math.floor(y))) + 0.15;
        final double start = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        for (int i = 0; i < points; i++) {
            final double angle = start + Math.PI * 2 * i / points;
            emit(world, Particle.CRIT, x + Math.cos(angle) * radius, groundY, z + Math.sin(angle) * radius, 1, 0, 0.05, 0, 0.05, null);
        }
        emit(world, Particle.SMOKE, x, groundY, z, big ? 6 : 3, radius * 0.3, 0.05, radius * 0.3, 0.01, null);
        return true;
    }

    // ---- 一般的粒子（跟 World#spawnParticle 一樣的參數；沒寫 offset 是 0、沒寫 extra 是 1，跟 Bukkit 一樣） ----

    public static void spawn(final World world, final Particle particle, final Location at, final int count) {
        spawn(world, particle, at.getX(), at.getY(), at.getZ(), count, 0, 0, 0, 1, null);
    }

    public static void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count) {
        spawn(world, particle, x, y, z, count, 0, 0, 0, 1, null);
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final T data) {
        spawn(world, particle, at.getX(), at.getY(), at.getZ(), count, 0, 0, 0, 1, data);
    }

    public static <T> void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                                 final T data) {
        spawn(world, particle, x, y, z, count, 0, 0, 0, 1, data);
    }

    public static void spawn(final World world, final Particle particle, final Location at, final int count, final double dx, final double dy,
                             final double dz) {
        spawn(world, particle, at.getX(), at.getY(), at.getZ(), count, dx, dy, dz, 1, null);
    }

    public static void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                             final double dx, final double dy, final double dz) {
        spawn(world, particle, x, y, z, count, dx, dy, dz, 1, null);
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final double dx, final double dy,
                                 final double dz, final T data) {
        spawn(world, particle, at.getX(), at.getY(), at.getZ(), count, dx, dy, dz, 1, data);
    }

    public static <T> void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                                 final double dx, final double dy, final double dz, final T data) {
        spawn(world, particle, x, y, z, count, dx, dy, dz, 1, data);
    }

    public static void spawn(final World world, final Particle particle, final Location at, final int count, final double dx, final double dy,
                             final double dz, final double extra) {
        spawn(world, particle, at.getX(), at.getY(), at.getZ(), count, dx, dy, dz, extra, null);
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final double dx,
                                 final double dy, final double dz, final double extra, final T data) {
        spawn(world, particle, at.getX(), at.getY(), at.getZ(), count, dx, dy, dz, extra, data);
    }

    /** force 不看了（2026-11-02）：只有重要的（{@link #important}）才強制顯示。 */
    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final double dx,
                                 final double dy, final double dz, final double extra, final T data, final boolean force) {
        spawn(world, particle, at.getX(), at.getY(), at.getZ(), count, dx, dy, dz, extra, data);
    }

    public static void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                             final double dx, final double dy, final double dz, final double extra) {
        spawn(world, particle, x, y, z, count, dx, dy, dz, extra, null);
    }

    public static <T> void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                                 final double dx, final double dy, final double dz, final double extra, final T data) {
        if (!exact && lowProfile(world, particle, x, y, z)) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        emit(world, particle, x, y, z, count, dx, dy, dz, extra, data);
    }

    /** force 不看了（2026-11-02）：只有重要的（{@link #important}）才強制顯示。 */
    public static <T> void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                                 final double dx, final double dy, final double dz, final double extra, final T data, final boolean force) {
        spawn(world, particle, x, y, z, count, dx, dy, dz, extra, data);
    }

    // ---- 地上一圈（一次性）與持續範圍 ----

    /** 2026-10-27 照站長「燃燒地板要畫個外框」：燃燒地面的外圈（橘紅色的點排成一圈，貼地）。 */
    public static final Particle.DustOptions BURNING_EDGE = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 110, 20), 1.1f);

    /** 在地上畫一圈外框（一次性；點數照周長，{@link FxPolicy#ringPoints}）。 */
    public static void groundOutline(final Location center, final double radius, final Particle.DustOptions dust) {
        ring(center.getWorld(), center.clone().add(0, 0.08, 0), radius, Particle.DUST, dust, source, important);
    }

    /** 一次性的一圈（現在的「誰的」「重要與否」）。 */
    public static void ring(final World world, final Location center, final double radius, final Particle particle, final Object data) {
        ring(world, center, radius, particle, data, source, important);
    }

    /**
     * 2026-11-02：一次性的一圈（高度就是 center 的 y）：點數照周長每 0.85 格一點（6–48），每個人照自己的密度打薄。
     *
     * @param data        粒子資料（DUST 的顏色大小等；沒有 null）
     * @param owner       誰的（null = 環境）
     * @param isImportant 重要的（預警、危險區）
     */
    public static void ring(final World world, final Location center, final double radius, final Particle particle, final Object data,
                            final UUID owner, final boolean isImportant) {
        if (world == null || center == null || radius <= 0 || particle == null) {
            return;
        }
        final Snapshot snapshot = players(world);
        final double range = (isImportant ? importantRange : viewRange) + radius;
        final FxEffect fx = router == null ? null : new FxEffect(FxEffect.Shape.RING, FxEffect.Phase.ONCE, 0, world, center.getX(), center.getY(),
                center.getZ(), center.getX(), center.getY(), center.getZ(), radius, particle, data, 0, 0, 0, 0,
                FxPolicy.ringPoints(radius, 1), 1.0, 0, owner, isImportant);
        final double start = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        int sent = 0;
        for (int i = 0; i < snapshot.players.length; i++) {
            if (!near(snapshot, i, center.getX(), center.getY(), center.getZ(), range)) {
                continue;
            }
            final double d = density(snapshot, i, owner, isImportant, kind);
            final int points = FxPolicy.ringPoints(radius, d);
            if (points <= 0 || skip != null && skip.routedTo.contains(snapshot.ids[i])) {
                continue;
            }
            final Player viewer = snapshot.players[i];
            if (fx != null && routed(viewer, fx.withDensity(d))) {
                continue;
            }
            if (viewer.isConnected()) {
                sent += drawRing(viewer, center.getX(), center.getY(), center.getZ(), radius, points, start, particle, data, isImportant);
            }
        }
        STATS.add(Bukkit.getCurrentTick(), owner, sent);
    }

    private static int drawRing(final Player viewer, final double x, final double y, final double z, final double radius, final int points,
                                final double start, final Particle particle, final Object data, final boolean force) {
        for (int p = 0; p < points; p++) {
            final double angle = start + Math.PI * 2 * p / points;
            viewer.spawnParticle(particle, x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius, 1, 0, 0, 0, 0, data, force);
        }
        return points;
    }

    /**
     * 持續範圍的樣子：外框（edge）與裡面稀疏撒的點（fill；null = 只有外框）；fillHeight 是裡面的點離地多高（隨機 0–fillHeight），
     * fillExtra 是裡面的點的 extra（速度）。
     */
    public record Look(Particle edge, Object edgeData, Particle fill, Object fillData, double fillHeight, double fillExtra) {

        /** 只有外框。 */
        public static Look edge(final Particle particle, final Object data) {
            return new Look(particle, data, null, null, 0, 0);
        }

        /** 外框＋裡面同一種。 */
        public static Look filled(final Particle particle, final Object data) {
            return new Look(particle, data, particle, data, 0.5, 0);
        }

        /** 外框＋裡面另一種。 */
        public static Look of(final Particle edge, final Object edgeData, final Particle fill, final Object fillData) {
            return new Look(edge, edgeData, fill, fillData, 0.5, 0.01);
        }
    }

    /** 一個持續範圍（{@link #zone}）。 */
    private static final class ZoneState {
        final long id;
        FxEffect start;
        long lastDrawn = Long.MIN_VALUE;
        long lastSeen;
        /** 已經收到 START、由模組畫的人。 */
        final Set<UUID> routedTo = new HashSet<>(2);

        ZoneState(final long id) {
            this.id = id;
        }
    }

    private static final Map<Object, ZoneState> zones = new HashMap<>();
    private static long zoneIds;
    private static final FxPolicy.Throttle THROTTLE = new FxPolicy.Throttle(FxPolicy.REDRAW_TICKS);
    private static final FxPolicy.Merge MERGE = new FxPolicy.Merge(FxPolicy.REDRAW_TICKS);

    /** 合併時算「同一種」的：粒子＋資料（顏色）。 */
    private record ZoneType(Particle particle, Object data) {
    }

    /** 持續範圍的鍵：同一個東西的第幾個點（一個地面效果有好幾個點時）。 */
    public record Part(Object owner, int index) {
    }

    /**
     * 2026-11-02：持續範圍（地面效果、雲、裂隙、聖化地面、光環、旗幟……）：畫的那邊照原本的頻率呼叫（每 tick 也可以），
     * 這裡最多每 {@link FxPolicy#REDRAW_TICKS} tick 真的畫一次（外框照周長、裡面稀疏撒；同一個人疊在一起的同一種只畫一個），
     * 第一次送模組 START、半徑或位置變了送 UPDATE；結束時呼叫 {@link #zoneStop}（忘了的話 {@link #ZONE_IDLE_TICKS} tick 後自動 STOP）。
     * DUST 的點大一點（存在久一點），重畫變慢看起來差不多。
     *
     * @param key            這個範圍的鍵（同一個範圍每次都要是同一個：地面效果的物件本身，或 {@link Part}）
     * @param center         圓心（y 是外框的高度）
     * @param remainingTicks 還剩多久（不知道 -1）
     * @param owner          誰的（null = 環境）
     * @param isImportant    重要的（預警、危險區）
     */
    public static void zone(final Object key, final Location center, final double radius, final Look look, final int remainingTicks,
                            final UUID owner, final boolean isImportant) {
        final World world = center == null ? null : center.getWorld();
        if (key == null || world == null || radius <= 0 || look == null || look.edge() == null) {
            return;
        }
        final long now = Bukkit.getCurrentTick();
        final Snapshot snapshot = players(world);
        final FxEffect start = describe(look.fill() == null ? FxEffect.Shape.RING : FxEffect.Shape.AREA, world, center, radius, look.edge(),
                look.edgeData(), look.fillHeight(), look.fillExtra(), FxPolicy.ringPoints(radius, 1), remainingTicks, owner, isImportant);
        final ZoneState state = lifecycle(key, start, remainingTicks);
        if (!THROTTLE.due(state.lastDrawn, now)) {
            return;
        }
        state.lastDrawn = now;
        final boolean draw = MERGE.claim(owner, new ZoneType(look.edge(), look.edgeData()), center.getX(), center.getY(), center.getZ(), radius, now);
        final Object edgeData = longer(look.edge(), look.edgeData());
        final Object fillData = look.fill() == null ? null : longer(look.fill(), look.fillData());
        final double range = (isImportant ? importantRange : viewRange) + radius;
        final java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        final double begin = random.nextDouble() * Math.PI * 2;
        int sent = 0;
        for (int i = 0; i < snapshot.players.length; i++) {
            if (!near(snapshot, i, center.getX(), center.getY(), center.getZ(), range)) {
                continue;
            }
            final double edgeDensity = density(snapshot, i, owner, isImportant, FxPolicy.Kind.OUTLINE);
            final double fillDensity = look.fill() == null ? 0 : density(snapshot, i, owner, isImportant, FxPolicy.Kind.FILL);
            if (edgeDensity <= 0 && fillDensity <= 0 || claimed(state, snapshot, i, start, Math.max(edgeDensity, fillDensity))) {
                continue; // 看不到，或模組在畫
            }
            final Player viewer = snapshot.players[i];
            if (!draw || !viewer.isConnected()) {
                continue; // 同一個人疊在一起的同一種，這一輪已經畫過一個
            }
            sent += drawRing(viewer, center.getX(), center.getY(), center.getZ(), radius, FxPolicy.ringPoints(radius, edgeDensity), begin,
                    look.edge(), edgeData, isImportant);
            final int fill = FxPolicy.areaPoints(radius, fillDensity);
            for (int p = 0; p < fill; p++) {
                final double dist = radius * Math.sqrt(random.nextDouble());
                final double angle = random.nextDouble() * Math.PI * 2;
                viewer.spawnParticle(look.fill(), center.getX() + Math.cos(angle) * dist,
                        center.getY() + 0.05 + random.nextDouble() * look.fillHeight(), center.getZ() + Math.sin(angle) * dist, 1, 0, 0, 0,
                        look.fillExtra(), fillData, isImportant);
            }
            sent += fill;
        }
        STATS.add(now, owner, sent);
    }

    /**
     * 2026-11-02：形狀特別的持續特效（龍捲風的螺旋、一排一排的牆、跟著人走的光環……）：模組那邊一樣收到 START／UPDATE／STOP
     * （shape、半徑、粒子照這裡給的），粒子由 draw 自己畫，但最多每 interval tick 畫一次，而且不送給模組在畫的人；
     * 裡面畫的算持續範圍裡面的點（低特效等級的人看別人的這種不送）。誰的、重要與否照現在的（{@link #as}、{@link #important}）。
     */
    public static void track(final Object key, final Location center, final double radius, final FxEffect.Shape shape, final Particle particle,
                             final Object data, final int remainingTicks, final int interval, final Runnable draw) {
        track(key, center, null, radius, shape, particle, data, remainingTicks, interval, draw);
    }

    /** 同 {@link #track}，LINE／BEAM 用：從 from 到 to（width 是寬的一半，放在 radius）。 */
    public static void track(final Object key, final Location center, final Location end, final double radius, final FxEffect.Shape shape,
                             final Particle particle, final Object data, final int remainingTicks, final int interval, final Runnable draw) {
        final World world = center == null ? null : center.getWorld();
        if (key == null || world == null || draw == null) {
            return;
        }
        final long now = Bukkit.getCurrentTick();
        final UUID owner = source;
        final boolean isImportant = important;
        FxEffect start = describe(shape, world, center, Math.max(0, radius), particle, data, 0, 0, 0, remainingTicks, owner, isImportant);
        if (end != null) {
            start = new FxEffect(start.shape(), start.phase(), 0, world, start.x(), start.y(), start.z(), end.getX(), end.getY(), end.getZ(),
                    start.radius(), particle, data, 0, 0, 0, 0, 0, 1.0, remainingTicks, owner, isImportant);
        }
        final ZoneState state = lifecycle(key, start, remainingTicks);
        if (state.lastDrawn != Long.MIN_VALUE && now - state.lastDrawn < Math.max(1, interval) && now >= state.lastDrawn) {
            return;
        }
        state.lastDrawn = now;
        if (router != null) {
            final Snapshot snapshot = players(world);
            final double range = (isImportant ? importantRange : viewRange) + Math.max(0, radius);
            for (int i = 0; i < snapshot.players.length; i++) {
                if (near(snapshot, i, center.getX(), center.getY(), center.getZ(), range)) {
                    final double d = density(snapshot, i, owner, isImportant, FxPolicy.Kind.FILL);
                    if (d > 0) {
                        claimed(state, snapshot, i, start, d);
                    }
                }
            }
        }
        final ZoneState before = skip;
        final FxPolicy.Kind kindBefore = kind;
        skip = state.routedTo.isEmpty() ? null : state;
        kind = FxPolicy.Kind.FILL;
        try {
            draw.run();
        } finally {
            skip = before;
            kind = kindBefore;
        }
    }

    /** {@link #track} 正在畫的那個（裡面的粒子不送給模組在畫的人）。 */
    private static ZoneState skip;

    private static FxEffect describe(final FxEffect.Shape shape, final World world, final Location center, final double radius,
                                     final Particle particle, final Object data, final double dy, final double extra, final int count,
                                     final int remainingTicks, final UUID owner, final boolean isImportant) {
        return new FxEffect(shape, FxEffect.Phase.START, 0, world, center.getX(), center.getY(), center.getZ(), center.getX(), center.getY(),
                center.getZ(), radius, particle, data, 0, dy, 0, extra, count, 1.0, remainingTicks, owner, isImportant);
    }

    /** 拿到（第一次就建立）這個持續範圍；半徑或位置變了送 UPDATE 給模組在畫的人。 */
    private static ZoneState lifecycle(final Object key, final FxEffect describe, final int remainingTicks) {
        final long now = Bukkit.getCurrentTick();
        ZoneState state = zones.get(key);
        if (state == null) {
            state = new ZoneState(++zoneIds);
            zones.put(key, state);
        }
        state.lastSeen = now;
        final FxEffect start = new FxEffect(describe.shape(), FxEffect.Phase.START, state.id, describe.world(), describe.x(), describe.y(),
                describe.z(), describe.ex(), describe.ey(), describe.ez(), describe.radius(), describe.particle(), describe.data(), describe.dx(),
                describe.dy(), describe.dz(), describe.extra(), describe.count(), 1.0, remainingTicks, describe.owner(), describe.important());
        if (state.start == null || moved(state.start, start)) {
            if (state.start != null && !state.routedTo.isEmpty()) {
                final FxEffect update = start.withPhase(FxEffect.Phase.UPDATE, remainingTicks);
                for (final UUID id : state.routedTo) {
                    final Player viewer = Bukkit.getPlayer(id);
                    if (viewer != null) {
                        routed(viewer, update);
                    }
                }
            }
            state.start = start;
        }
        return state;
    }

    /** 這個人由模組畫這個持續範圍（已經收過 START，或現在問 router 接手了）。 */
    private static boolean claimed(final ZoneState state, final Snapshot snapshot, final int i, final FxEffect start, final double d) {
        if (state.routedTo.contains(snapshot.ids[i])) {
            return true;
        }
        if (router != null && routed(snapshot.players[i], state.start.withDensity(d))) {
            state.routedTo.add(snapshot.ids[i]);
            return true;
        }
        return false;
    }

    /** 2026-11-02：持續範圍結束（送 STOP 給由模組畫的人）。 */
    public static void zoneStop(final Object key) {
        final ZoneState state = key == null ? null : zones.remove(key);
        if (state != null) {
            stop(state);
        }
    }

    /**
     * 2026-11-02：形狀特別、還沒換成 {@link #zone} 的持續特效：這個鍵這一輪要不要畫（最多每 {@link FxPolicy#REDRAW_TICKS} tick 一次）。
     */
    public static boolean redrawDue(final Object key) {
        return redrawDue(key, FxPolicy.REDRAW_TICKS);
    }

    /** 同上，自訂最短間隔（tick）。 */
    public static boolean redrawDue(final Object key, final int interval) {
        if (key == null) {
            return true;
        }
        final long now = Bukkit.getCurrentTick();
        final long[] last = redraws.computeIfAbsent(key, k -> new long[]{Long.MIN_VALUE, now});
        last[1] = now;
        if (last[0] != Long.MIN_VALUE && now - last[0] < interval && now >= last[0]) {
            return false;
        }
        last[0] = now;
        return true;
    }

    /** redrawDue 的鍵 → {上次畫, 上次問}。 */
    private static final Map<Object, long[]> redraws = new HashMap<>();

    private static void stop(final ZoneState state) {
        if (state.routedTo.isEmpty() || state.start == null) {
            return;
        }
        final FxEffect stop = state.start.withPhase(FxEffect.Phase.STOP, 0);
        for (final UUID id : state.routedTo) {
            final Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                routed(viewer, stop);
            }
        }
        state.routedTo.clear();
    }

    /** 太久沒再畫的持續範圍算結束（每秒看一次）。 */
    private static long lastSweep = Long.MIN_VALUE;

    private static void sweepZones(final long now) {
        if (lastSweep != Long.MIN_VALUE && now - lastSweep < 20 && now >= lastSweep) {
            return;
        }
        lastSweep = now;
        for (final Iterator<ZoneState> it = zones.values().iterator(); it.hasNext(); ) {
            final ZoneState state = it.next();
            if (now - state.lastSeen > ZONE_IDLE_TICKS || now < state.lastSeen) {
                it.remove();
                stop(state);
            }
        }
        redraws.values().removeIf(last -> now - last[1] > ZONE_IDLE_TICKS || now < last[1]);
    }

    /** 半徑或位置變了（送 UPDATE）。 */
    private static boolean moved(final FxEffect before, final FxEffect now) {
        final double dx = before.x() - now.x();
        final double dz = before.z() - now.z();
        return Math.abs(before.radius() - now.radius()) > 0.1 || dx * dx + dz * dz > 0.25 || Math.abs(before.y() - now.y()) > 0.5;
    }

    /** DUST 的點大一點（存在久一點；持續範圍重畫變成 0.5 秒一次，看起來一樣連續）。 */
    private static final Map<Particle.DustOptions, Particle.DustOptions> LONGER = new java.util.IdentityHashMap<>();

    private static Object longer(final Particle particle, final Object data) {
        if (particle != Particle.DUST || !(data instanceof Particle.DustOptions dust)) {
            return data;
        }
        if (LONGER.size() > 256) {
            LONGER.clear();
        }
        return LONGER.computeIfAbsent(dust, key -> new Particle.DustOptions(key.getColor(), FxPolicy.longerSize(key.getSize())));
    }

    // ---- 一次性的範圍 ----

    /**
     * 2026-10-31 照站長：範圍特效平均散在整個圓裡（半徑 radius），不是集中在中間。原版的 offset 是常態分布，
     * 寫成「半徑的一半」時大部分粒子只落在半徑一半以內，看起來範圍只有一半大。分成幾小團、每團隨機落在圓內。
     */
    public static void disk(final World world, final Particle particle, final Location at, final int count, final double radius,
                            final double dy, final double extra) {
        disk(world, particle, at, count, radius, dy, extra, null);
    }

    public static <T> void disk(final World world, final Particle particle, final Location at, final int count, final double radius,
                                final double dy, final double extra, final T data) {
        if (count <= 0) {
            return;
        }
        // 2026-10-31 照站長（簡化）：外框一圈＋裡面稀疏幾顆，總數約原本的一半。外框把範圍的邊畫清楚，比整片撒滿更看得出多大；
        //   這次之前（同一天稍早）是 count 顆分成幾小團隨機撒在圓內
        final double r = Math.max(0, radius);
        final int budget = Math.max(MIN_DISK_PARTICLES, (int) Math.round(count * DISK_BUDGET));
        if (r < 0.6) {
            spawnOne(world, particle, at, budget, r, dy, extra, data); // 很小的範圍：一團就好
            return;
        }
        // 外框：照周長每 RING_SPACING 格一顆，最多用掉預算的 2/3（至少 8、最多 32 顆）；起點隨機轉，每次不會畫在同一個位置
        final int ring = Math.max(8, Math.min(32, Math.min((int) Math.ceil(2 * Math.PI * r / RING_SPACING), budget * 2 / 3)));
        final java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        final double start = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < ring; i++) {
            final double angle = start + Math.PI * 2 * i / ring;
            spawnOne(world, particle, at.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r), 1, 0, Math.min(dy, 0.1), extra * 0.3, data);
        }
        // 裡面：剩下的預算一顆一顆平均撒在圓內（不集中在中間）
        final int fill = Math.max(2, budget - ring);
        for (int i = 0; i < fill; i++) {
            final double dist = r * Math.sqrt(random.nextDouble());
            final double angle = random.nextDouble() * Math.PI * 2;
            spawnOne(world, particle, at.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist), 1, 0, dy, extra, data);
        }
    }

    /** 範圍特效的粒子數是原本 count 的幾成（2026-10-31 簡化）。 */
    static final double DISK_BUDGET = 0.5;
    /** 範圍特效至少幾顆。 */
    static final int MIN_DISK_PARTICLES = 4;
    /** 外框每隔幾格一顆。 */
    static final double RING_SPACING = 0.7;

    private static <T> void spawnOne(final World world, final Particle particle, final Location point, final int count, final double spread,
                                     final double dy, final double extra, final T data) {
        spawn(world, particle, point.getX(), point.getY(), point.getZ(), count, spread, dy, spread, extra, data);
    }

}
