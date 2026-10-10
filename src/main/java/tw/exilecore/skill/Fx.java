package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * 技能粒子的發送（2026-10，設定「隱藏其他玩家的技能特效」）：畫的時候記得是誰的技能（{@link #source}），
 * 關掉別人技能特效的玩家就不送給他；自己的技能、怪物與 Boss 的招式（警告圈）照常給每個人。
 * 外觀特效（贊助的粒子）不走這裡，照常顯示。只在主執行緒用。
 */
public final class Fx {

    /** 送給附近多遠的玩家（跟原版粒子的距離差不多）。 */
    private static final double RANGE_SQUARED = 64 * 64;

    private static UUID source;
    /** 這個玩家關掉了別人的技能特效。 */
    private static Predicate<Player> hidesOthers = player -> false;

    private Fx() {
    }

    /** 接上設定（角色模組啟用時）。 */
    public static void hidesOthers(final Predicate<Player> test) {
        hidesOthers = test == null ? player -> false : test;
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
        return source == null || viewer.getUniqueId().equals(source) || !hidesOthers.test(viewer);
    }

    /** 要不要一個一個送（有人關掉、而且是玩家的技能）。 */
    private static boolean filtered() {
        return source != null;
    }

    private interface Send {
        void to(Player viewer);
    }

    private static void each(final World world, final double x, final double y, final double z, final Send send) {
        for (final Player viewer : world.getPlayers()) {
            final Location at = viewer.getLocation();
            final double dx = at.getX() - x;
            final double dy = at.getY() - y;
            final double dz = at.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= RANGE_SQUARED && sees(viewer)) {
                send.to(viewer);
            }
        }
    }

    public static void spawn(final World world, final Particle particle, final Location at, final int count) {
        if (!filtered()) {
            world.spawnParticle(particle, at, count);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count));
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final T data) {
        if (!filtered()) {
            world.spawnParticle(particle, at, count, data);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, data));
    }

    public static void spawn(final World world, final Particle particle, final Location at, final int count, final double dx, final double dy,
                             final double dz) {
        if (!filtered()) {
            world.spawnParticle(particle, at, count, dx, dy, dz);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, dx, dy, dz));
    }

    public static void spawn(final World world, final Particle particle, final Location at, final int count, final double dx, final double dy,
                             final double dz, final double extra) {
        if (!filtered()) {
            world.spawnParticle(particle, at, count, dx, dy, dz, extra);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, dx, dy, dz, extra));
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final double dx,
                                 final double dy, final double dz, final double extra, final T data) {
        if (!filtered()) {
            world.spawnParticle(particle, at, count, dx, dy, dz, extra, data);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, dx, dy, dz, extra, data));
    }

    public static <T> void spawn(final World world, final Particle particle, final Location at, final int count, final double dx,
                                 final double dy, final double dz, final double extra, final T data, final boolean force) {
        if (!filtered()) {
            world.spawnParticle(particle, at, count, dx, dy, dz, extra, data, force);
            return;
        }
        each(world, at.getX(), at.getY(), at.getZ(), viewer -> viewer.spawnParticle(particle, at, count, dx, dy, dz, extra, data));
    }

    public static void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                             final double dx, final double dy, final double dz, final double extra) {
        if (!filtered()) {
            world.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra);
            return;
        }
        each(world, x, y, z, viewer -> viewer.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra));
    }

    public static <T> void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                                 final double dx, final double dy, final double dz, final double extra, final T data) {
        if (!filtered()) {
            world.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data);
            return;
        }
        each(world, x, y, z, viewer -> viewer.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data));
    }

    public static <T> void spawn(final World world, final Particle particle, final double x, final double y, final double z, final int count,
                                 final double dx, final double dy, final double dz, final double extra, final T data, final boolean force) {
        if (!filtered()) {
            world.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data, force);
            return;
        }
        each(world, x, y, z, viewer -> viewer.spawnParticle(particle, x, y, z, count, dx, dy, dz, extra, data));
    }
}
