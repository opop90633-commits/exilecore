package tw.exilecore.skill;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 技能粒子的發送（2026-10，設定「隱藏其他玩家的技能特效」）：畫的時候記得是誰的技能（{@link #source}），
 * 關掉別人技能特效的玩家就不送給他；自己的技能、怪物與 Boss 的招式（警告圈）照常給每個人。
 * 外觀特效（贊助的粒子）不走這裡，照常顯示。只在主執行緒用。
 *
 * <p>技能的聲音也走這裡（{@link #sound}，2026-10）：自己的技能照原本的音量；別人的技能小聲
 * （{@link #OTHERS_VOLUME}），關掉別人技能特效的人完全不送；設定「技能音效」關掉的人連自己的也不送。
 * 怪物與 Boss 的招式照常給每個人。
 *
 * <p>封包量（2026-10，人多時大家的 PING 一起飆高）：玩家技能的粒子是一點一個封包、附近每個人各一份，
 * 營地四十個人時一招就是幾十倍。所以送的距離跟原版一樣 32 格（以前 64 格，面積是 4 倍）；
 * 附近別人超過 {@link #CROWD_FULL} 個時，別人那份照比例打薄（自己的技能照常全部送）；
 * 每個人的技能送了多少封包記在 {@link FxStats}，{@code /ec skill fx} 看誰送最多。
 */
public final class Fx {

    /** 送給附近多遠的玩家（跟原版粒子一樣 32 格；2026-10 從 64 格改回來）。 */
    private static final double RANGE_SQUARED = 32 * 32;

    /** 附近別人超過幾個時，別人看到的技能特效開始打薄（{@link FxStats#stride}）。 */
    static final int CROWD_FULL = 10;

    /** 誰的技能送了多少封包。 */
    private static final FxStats STATS = new FxStats();
    /** 打薄時輪流送的計數。 */
    private static int sequence;
    /** 這一點要送的別人（重複使用，只在主執行緒）。 */
    private static final List<Player> crowd = new ArrayList<>();

    /** 別人的技能聲音是原本音量的幾倍（一百人同場時不會一片爆炸聲）。 */
    static final float OTHERS_VOLUME = 0.35f;

    private static UUID source;
    /** 這個玩家關掉了別人的技能特效。 */
    private static Predicate<Player> hidesOthers = player -> false;

    private Fx() {
    }

    /** 這個玩家關掉了技能音效（自己的技能也不出聲）。 */
    private static Predicate<Player> mutesOwn = player -> false;

    /** 接上設定（角色模組啟用時）。 */
    public static void hidesOthers(final Predicate<Player> test) {
        hidesOthers = test == null ? player -> false : test;
    }

    /** 接上設定「技能音效」。 */
    public static void mutesOwn(final Predicate<Player> test) {
        mutesOwn = test == null ? player -> false : test;
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
        if (!filtered()) {
            world.playSound(at, sound, category, volume, pitch);
            return;
        }
        final double range = 16.0 * Math.max(1.0, volume); // 原版聽得到的距離
        final double rangeSquared = range * range;
        int sent = 0;
        final Snapshot snapshot = players(world); // 2026-10-27 效能：這個 tick 的玩家與位置只拿一次
        for (int i = 0; i < snapshot.players().length; i++) {
            final Player viewer = snapshot.players()[i];
            final double dx = snapshot.xyz()[i * 3] - at.getX();
            final double dy = snapshot.xyz()[i * 3 + 1] - at.getY();
            final double dz = snapshot.xyz()[i * 3 + 2] - at.getZ();
            if (dx * dx + dy * dy + dz * dz > rangeSquared || !viewer.isConnected()) {
                continue;
            }
            final boolean mine = viewer.getUniqueId().equals(source);
            final float heard = heard(volume, mine, mine && mutesOwn.test(viewer), !mine && hidesOthers.test(viewer));
            if (heard > 0) {
                viewer.playSound(at, sound, category, heard, pitch);
                sent++;
            }
        }
        STATS.add(Bukkit.getCurrentTick(), source, sent);
    }

    /** 現在畫的是誰的技能（null = 不是玩家的：怪物、Boss、環境），回傳前一個（用完 {@link #restore} 放回去）。 */
    public static UUID source(final UUID player) {
        final UUID previous = source;
        source = player;
        return previous;
    }

    public static void restore(final UUID previous) {
        source = previous;
    }

    /** 當作 owner 的技能畫（owner null = 照常給每個人）。 */
    public static void as(final UUID owner, final Runnable draw) {
        final UUID previous = source(owner);
        try {
            draw.run();
        } finally {
            restore(previous);
        }
    }

    /** 這個玩家關掉了別人的技能特效。 */
    public static boolean hides(final Player viewer) {
        return hidesOthers.test(viewer);
    }

    /** 這個玩家看不看得到現在的粒子。 */
    private static boolean sees(final Player viewer) {
        if (noPackOnly && hasPack.test(viewer)) {
            return false; // 2026-10-31：套了資源包的人看平面的地面圈
        }
        return source == null || viewer.getUniqueId().equals(source) || !hidesOthers.test(viewer);
    }

    /** 要不要一個一個送（有人關掉、而且是玩家的技能；或只送給沒套資源包的人）。 */
    private static boolean filtered() {
        return source != null || noPackOnly;
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

    private interface Send {
        void to(Player viewer);
    }

    // ---- 2026-10-27 效能：每個世界的玩家與位置每個 tick 只拿一次 ----
    // （所有地圖實例在同一個世界；以前每個粒子點都 world.getPlayers() 複製一份名單、每個人 getLocation() 一次，一招幾百個點就是幾萬次）

    /** 一個世界這個 tick 的玩家與位置。 */
    private record Snapshot(Player[] players, double[] xyz) {
    }

    private static final java.util.Map<World, Snapshot> snapshots = new java.util.HashMap<>();
    private static int snapshotTick = Integer.MIN_VALUE;

    private static Snapshot players(final World world) {
        final int tick = Bukkit.getCurrentTick();
        if (tick != snapshotTick) {
            snapshots.clear();
            snapshotTick = tick;
        }
        return snapshots.computeIfAbsent(world, key -> {
            final List<Player> list = key.getPlayers();
            final Player[] players = list.toArray(new Player[0]);
            final double[] xyz = new double[players.length * 3];
            for (int i = 0; i < players.length; i++) {
                final Location at = players[i].getLocation();
                xyz[i * 3] = at.getX();
                xyz[i * 3 + 1] = at.getY();
                xyz[i * 3 + 2] = at.getZ();
            }
            return new Snapshot(players, xyz);
        });
    }

    private static void each(final World world, final double x, final double y, final double z, final Send send) {
        Player owner = null;
        crowd.clear();
        final Snapshot snapshot = players(world);
        for (int i = 0; i < snapshot.players().length; i++) {
            final Player viewer = snapshot.players()[i];
            final double dx = snapshot.xyz()[i * 3] - x;
            final double dy = snapshot.xyz()[i * 3 + 1] - y;
            final double dz = snapshot.xyz()[i * 3 + 2] - z;
            if (dx * dx + dy * dy + dz * dz <= RANGE_SQUARED && viewer.isConnected() && sees(viewer)) {
                if (viewer.getUniqueId().equals(source)) {
                    owner = viewer;
                } else {
                    crowd.add(viewer);
                }
            }
        }
        int sent = 0;
        if (owner != null) {
            send.to(owner); // 自己的技能照常全部送
            sent++;
        }
        if (!crowd.isEmpty()) {
            final int stride = FxStats.stride(crowd.size(), CROWD_FULL);
            if (stride == 1 || Math.floorMod(sequence++, stride) == 0) {
                for (final Player viewer : crowd) {
                    send.to(viewer);
                }
                sent += crowd.size();
            }
            crowd.clear();
        }
        STATS.add(Bukkit.getCurrentTick(), source, sent);
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
    static boolean lowProfile(final World world, final Particle particle, final Location at) {
        if (world == null || at == null) {
            return false;
        }
        if (particle == Particle.FLASH) {
            return true; // 閃光直接不送
        }
        if (particle == Particle.LARGE_SMOKE) {
            spawn(world, Particle.SMOKE, at, 2, 0.25, 0.05, 0.25, 0.01);
            return true;
        }
        if (particle != Particle.EXPLOSION && particle != Particle.EXPLOSION_EMITTER) {
            return false;
        }
        final boolean big = particle == Particle.EXPLOSION_EMITTER;
        final double radius = big ? 2.2 : 1.2;
        final int points = big ? 16 : 10;
        final Location ground = at.clone().add(0, -Math.min(0.6, Math.max(0, at.getY() - Math.floor(at.getY()))) + 0.15, 0);
        final double start = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        for (int i = 0; i < points; i++) {
            final double angle = start + Math.PI * 2 * i / points;
            spawn(world, Particle.CRIT, ground.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), 1, 0, 0.05, 0, 0.05);
        }
        spawn(world, Particle.SMOKE, ground, big ? 6 : 3, radius * 0.3, 0.05, radius * 0.3, 0.01);
        return true;
    }

    /** 2026-10-27 照站長「燃燒地板要畫個外框」：燃燒地面的外圈（橘紅色的點排成一圈，貼地）。 */
    public static final Particle.DustOptions BURNING_EDGE = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 110, 20), 1.1f);

    /** 在地上畫一圈外框（radius 格；點的數量照周長，最少 12 個）。 */
    public static void groundOutline(final Location center, final double radius, final Particle.DustOptions dust) {
        final World world = center.getWorld();
        if (world == null || radius <= 0) {
            return;
        }
        final int points = (int) Math.max(12, Math.round(radius * Math.PI * 2 * 2.5));
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points;
            spawn(world, Particle.DUST, center.clone().add(Math.cos(angle) * radius, 0.08, Math.sin(angle) * radius), 1, dust);
        }
    }

    public static void spawn(final World world, final Particle particle, final Location at, final int count) {
        if (lowProfile(world, particle, at)) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, at, count);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count));
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final T data) {
        if (lowProfile(world, particle, at)) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, at, count, data);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, data));
    }

    public static void spawn(final World world, final Particle particle, final Location at, final int count, final double dx, final double dy,
                             final double dz) {
        if (lowProfile(world, particle, at)) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, at, count, dx, dy, dz);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, dx, dy, dz));
    }

    public static void spawn(final World world, final Particle particle, final Location at, final int count, final double dx, final double dy,
                             final double dz, final double extra) {
        if (lowProfile(world, particle, at)) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, at, count, dx, dy, dz, extra);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, dx, dy, dz, extra));
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final double dx,
                                 final double dy, final double dz, final double extra, final T data) {
        if (lowProfile(world, particle, at)) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, at, count, dx, dy, dz, extra, data);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, dx, dy, dz, extra, data));
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final double dx,
                                 final double dy, final double dz, final double extra, final T data, final boolean force) {
        if (lowProfile(world, particle, at)) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, at, count, dx, dy, dz, extra, data, force);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, dx, dy, dz, extra, data));
    }

    public static void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                             final double dx, final double dy, final double dz, final double extra) {
        if (lowProfile(world, particle, new Location(world, x, y, z))) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra);
            return;
        }
        each(world, x, y, z, viewer -> viewer.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra));
    }

    public static <T> void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                                 final double dx, final double dy, final double dz, final double extra, final T data) {
        if (lowProfile(world, particle, new Location(world, x, y, z))) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data);
            return;
        }
        each(world, x, y, z, viewer -> viewer.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data));
    }

    public static <T> void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                                 final double dx, final double dy, final double dz, final double extra, final T data, final boolean force) {
        if (lowProfile(world, particle, new Location(world, x, y, z))) {
            return; // 2026-10-31：擋視線的爆炸換成貼地的
        }
        if (!filtered()) {
            world.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data, force);
            return;
        }
        each(world, x, y, z, viewer -> viewer.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data));
    }

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
        if (data == null) {
            spawn(world, particle, point, count, spread, dy, spread, extra);
        } else {
            spawn(world, particle, point, count, spread, dy, spread, extra, data);
        }
    }

}
