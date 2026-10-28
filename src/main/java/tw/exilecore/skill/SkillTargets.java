package tw.exilecore.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 技能找目標：我們等級化過的怪物；有給施放者（owner）時，競技場裡另外加上他能打的敵方玩家與他們的召喚物（PvP）。
 * 不打 NPC、動物、自己、隊友。
 */
final class SkillTargets {

    /** 範圍技能的高度容許（腳底高度差）。 */
    static final double HEIGHT = 2.5;

    private final ExileCore plugin;

    SkillTargets(final ExileCore plugin) {
        this.plugin = plugin;
    }

    // ---- 2026-10-25：可以直接被打的東西（驚雷之鐘、冰晶）----

    /** 技能打到一塊範圍時通知它（不用有敵人）。source：同一次出手／同一顆投射物是同一個物件（同一下只算一次）。 */
    interface Strikeable {
        void struck(Player owner, SkillProfile skill, java.util.function.Predicate<Location> inside, Object source);
    }

    /** 東西本身的大小：範圍邊緣差這麼多也算打到。 */
    static final double STRIKE_PADDING = 0.6;

    private final List<Strikeable> strikeables = new ArrayList<>();

    void addStrikeable(final Strikeable strikeable) {
        strikeables.add(strikeable);
    }

    /** 你的技能打到這塊範圍（每個技能執行打擊的地方呼叫；地面效果一波一波的不呼叫）。 */
    void strike(final Player owner, final SkillProfile skill, final java.util.function.Predicate<Location> inside, final Object source) {
        if (owner == null || skill == null || strikeables.isEmpty()) {
            return;
        }
        for (final Strikeable strikeable : strikeables) {
            strikeable.struck(owner, skill, inside, source);
        }
    }

    /** 圓：水平 radius 內（加上東西的大小）、高度上下 HEIGHT 格內。 */
    static java.util.function.Predicate<Location> sphere(final Location center, final double radius) {
        final double r = radius + STRIKE_PADDING;
        return at -> at.getWorld().equals(center.getWorld()) && Math.abs(at.getY() - center.getY()) <= HEIGHT
                && square(at.getX() - center.getX()) + square(at.getZ() - center.getZ()) <= r * r;
    }

    /** 扇形：跟 {@link #cone(Origin, double, double)} 一樣（水平在扇形裡，或貼在身邊 1 格內）。 */
    static java.util.function.Predicate<Location> coneShape(final Origin origin, final double length, final double angle) {
        final Location from = origin.feet();
        final Vector facing = origin.facing();
        final double r = length + STRIKE_PADDING;
        return at -> {
            if (!at.getWorld().equals(from.getWorld())) {
                return false;
            }
            final double dx = at.getX() - from.getX();
            final double dz = at.getZ() - from.getZ();
            final double horizontal = dx * dx + dz * dz;
            final double dy = at.getY() - from.getY();
            return horizontal <= r * r && dy >= -HEIGHT && dy <= HEIGHT + Math.sqrt(horizontal) * CONE_HEIGHT_SLOPE
                    && (horizontal <= 1.0 || SkillMath.inCone(facing.getX(), facing.getZ(), dx, dz, angle));
        };
    }

    /** 線段：水平離 from → to 不超過 width（加上東西的大小），高度在兩端上下 HEIGHT 格內。 */
    static java.util.function.Predicate<Location> segment(final Location from, final Location to, final double width) {
        final double w = width + STRIKE_PADDING;
        final double low = Math.min(from.getY(), to.getY()) - HEIGHT;
        final double high = Math.max(from.getY(), to.getY()) + HEIGHT;
        return at -> at.getWorld().equals(from.getWorld()) && at.getY() >= low && at.getY() <= high
                && SkillMath.pointSegmentDistance(at.getX(), at.getZ(), from.getX(), from.getZ(), to.getX(), to.getZ()) <= w;
    }

    private static double square(final double value) {
        return value * value;
    }

    /** 怪物（沒有施放者：不打玩家）。 */
    boolean valid(final Entity entity) {
        return valid(entity, null);
    }

    /** 怪物；owner 不是 null 時，競技場裡他能打的敵方玩家與他們的召喚物也算。 */
    boolean valid(final Entity entity, final Player owner) {
        if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
            return false;
        }
        if (entity instanceof Player target) {
            return owner != null && pvp(owner, target);
        }
        if (plugin.monsters().isMonster(living)) {
            // 2026-10：訓練假人每人一組——別人那組的（看不到）不算目標；自己有一組時共用的那組也不算
            return plugin.training() == null || plugin.training().canTarget(living, owner);
        }
        if (owner == null) {
            return false;
        }
        final tw.exilecore.combat.MinionHooks minions = plugin.combat() == null ? null : plugin.combat().minions();
        if (minions == null || !minions.isMinion(living)) {
            return false;
        }
        final Player other = minions.owner(living);
        return other != null && pvp(owner, other);
    }

    private boolean pvp(final Player owner, final Player target) {
        final tw.exilecore.pvp.PvpService arena = plugin.pvp();
        return arena != null && arena.canAttack(owner, target);
    }

    /** 玩家面向的水平方向（抬頭或低頭到底時用身體的朝向）。 */
    static Vector facing(final Player player) {
        final Vector direction = player.getEyeLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 1e-6) {
            final double yaw = Math.toRadians(player.getLocation().getYaw());
            return new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        return direction.normalize();
    }

    /** 視線前方 range 內第一個敵人（射線有厚度 raySize，不穿牆）。 */
    LivingEntity aimed(final Player player, final double range, final double raySize) {
        return aimed(Origin.of(player), range, raySize);
    }

    LivingEntity aimed(final Origin origin, final double range, final double raySize) {
        final Location eye = origin.eye();
        final Vector direction = origin.direction();
        final World world = origin.world();
        final RayTraceResult blocks = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        final double max = blocks == null ? range : blocks.getHitPosition().distance(eye.toVector());
        if (max <= 0.05) {
            return null;
        }
        final Player owner = origin.owner();
        final RayTraceResult hit = world.rayTraceEntities(eye, direction, max, raySize, entity -> valid(entity, owner));
        return hit != null && hit.getHitEntity() instanceof LivingEntity living ? living : null;
    }

    /** 準心上找到的敵人跟它沿著準心的距離（格）。 */
    record Aimed(LivingEntity entity, double along) {
    }

    /** 準心沿著視線能看多遠（碰到牆就停，最多 range 格）。 */
    double aimReach(final Origin origin, final double range) {
        final RayTraceResult blocks = origin.world().rayTraceBlocks(origin.eye(), origin.direction(), range, FluidCollisionMode.NEVER, true);
        return blocks == null ? range : blocks.getHitPosition().distance(origin.eye().toVector());
    }

    /**
     * 2026-10-28（雷遁）：跟 {@link #aimed(Origin, double, double)} 一樣，但只算符合 filter 的敵人（前面擋著不符合的也照樣穿過去找）；
     * 連沿著準心的距離一起回傳。沒有回傳 null。
     */
    Aimed aimedWhere(final Origin origin, final double range, final double raySize,
                     final java.util.function.Predicate<LivingEntity> filter) {
        final double max = aimReach(origin, range);
        if (max <= 0.05) {
            return null;
        }
        final Player owner = origin.owner();
        final RayTraceResult hit = origin.world().rayTraceEntities(origin.eye(), origin.direction(), max, raySize,
                entity -> valid(entity, owner) && entity instanceof LivingEntity living && filter.test(living));
        if (hit == null || !(hit.getHitEntity() instanceof LivingEntity living)) {
            return null;
        }
        return new Aimed(living, hit.getHitPosition().distance(origin.eye().toVector()));
    }

    /**
     * center 周圍 radius 內的敵人，近的在前面。距離量到身體的碰撞箱（水平），
     * 高度上碰撞箱要在 center 上下 HEIGHT 格內。
     */
    List<LivingEntity> around(final Location center, final double radius) {
        return around(center, radius, null);
    }

    /** 同上；owner：施放者（競技場裡他能打的敵方玩家也算，null = 只有怪物）。 */
    List<LivingEntity> around(final Location center, final double radius, final Player owner) {
        final List<LivingEntity> list = new ArrayList<>();
        final double reach = radius + 3; // 大隻的怪中心比較遠
        for (final Entity entity : center.getWorld().getNearbyEntities(center, reach, HEIGHT + 3, reach, entity -> valid(entity, owner))) {
            final LivingEntity living = (LivingEntity) entity;
            final BoundingBox box = living.getBoundingBox();
            if (boxDistanceSquared(center, box) <= radius * radius
                    && box.getMaxY() >= center.getY() - HEIGHT && box.getMinY() <= center.getY() + HEIGHT) {
                list.add(living);
            }
        }
        list.sort(Comparator.comparingDouble(entity -> boxDistanceSquared(center, entity.getBoundingBox())));
        return list;
    }

    /**
     * 好幾個圓（一道線、走過的路）蓋到的敵人，每隻只列一次（2026-10：以前每個圓各掃一次再去重複，一道 9 個圓的牆就掃 9 次）。
     * 判定跟 {@link #around} 一樣：碰撞箱離任何一個圓心 radius 內、高度上下 HEIGHT 格內。順序不保證。
     */
    List<LivingEntity> aroundPoints(final List<Location> points, final double radius, final Player owner) {
        if (points.size() == 1) {
            return around(points.getFirst(), radius, owner);
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (final Location point : points) {
            minX = Math.min(minX, point.getX());
            minY = Math.min(minY, point.getY());
            minZ = Math.min(minZ, point.getZ());
            maxX = Math.max(maxX, point.getX());
            maxY = Math.max(maxY, point.getY());
            maxZ = Math.max(maxZ, point.getZ());
        }
        final Location first = points.getFirst();
        final Location middle = new Location(first.getWorld(), (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
        final double reach = radius + 3; // 大隻的怪中心比較遠
        final List<LivingEntity> list = new ArrayList<>();
        for (final Entity entity : first.getWorld().getNearbyEntities(middle, (maxX - minX) / 2 + reach, (maxY - minY) / 2 + HEIGHT + 3,
                (maxZ - minZ) / 2 + reach, entity -> valid(entity, owner))) {
            final LivingEntity living = (LivingEntity) entity;
            final BoundingBox box = living.getBoundingBox();
            for (final Location point : points) {
                if (boxDistanceSquared(point, box) <= radius * radius
                        && box.getMaxY() >= point.getY() - HEIGHT && box.getMinY() <= point.getY() + HEIGHT) {
                    list.add(living);
                    break;
                }
            }
        }
        return list;
    }

    /** 水平面上，點到碰撞箱的距離平方（在箱子裡面是 0）。 */
    static double boxDistanceSquared(final Location point, final BoundingBox box) {
        final double dx = Math.max(0, Math.max(box.getMinX() - point.getX(), point.getX() - box.getMaxX()));
        final double dz = Math.max(0, Math.max(box.getMinZ() - point.getZ(), point.getZ() - box.getMaxZ()));
        return dx * dx + dz * dz;
    }

    /** 玩家前方扇形（長度 length、張角 angle 度）內、看得到的敵人，近的在前面。 */
    List<LivingEntity> cone(final Player player, final double length, final double angle) {
        return cone(Origin.of(player), length, angle);
    }

    /** 2026-10-24：扇形往外每 1 格水平距離，高度多算幾格（約 30°；會飛的怪）。 */
    static final double CONE_HEIGHT_SLOPE = 0.6;

    List<LivingEntity> cone(final Origin origin, final double length, final double angle) {
        final Location from = origin.feet();
        final Vector facing = origin.facing();
        final Location eye = origin.eye();
        final Vector look = origin.direction();
        final List<LivingEntity> list = new ArrayList<>();
        // 2026-10-24：以前只找腳底上下 2.5 格內的（around），會飛的怪打不到：
        // 現在範圍內的都看——水平在扇形裡、高度在「腳邊 2.5 格、越遠張越開」內的算；照著視線抬頭（低頭）看過去的也算
        final double reach = length + 3;
        for (final Entity entity : from.getWorld().getNearbyEntities(from, reach, reach, reach, entity -> valid(entity, origin.owner()))) {
            final LivingEntity living = (LivingEntity) entity;
            final BoundingBox box = living.getBoundingBox();
            if (boxDistanceSquared(from, box) > length * length && boxDistanceSquared(eye, box) > length * length) {
                continue;
            }
            final double dx = box.getCenterX() - from.getX();
            final double dz = box.getCenterZ() - from.getZ();
            final double horizontal = Math.sqrt(dx * dx + dz * dz);
            final double dyNearest = box.getMinY() > from.getY() ? box.getMinY() - from.getY()
                    : box.getMaxY() < from.getY() ? box.getMaxY() - from.getY() : 0;
            final boolean flat = SkillMath.inCone(facing.getX(), facing.getZ(), dx, dz, angle)
                    && SkillMath.coneHeightOk(dyNearest, horizontal, HEIGHT, CONE_HEIGHT_SLOPE);
            final boolean aimed = !flat && SkillMath.inAim(look.getX(), look.getY(), look.getZ(),
                    box.getCenterX() - eye.getX(), box.getCenterY() - eye.getY(), box.getCenterZ() - eye.getZ(), angle);
            if ((flat || aimed) && origin.canSee(living)) {
                list.add(living);
            }
        }
        list.sort(Comparator.comparingDouble((LivingEntity entity) -> boxDistanceSquared(from, entity.getBoundingBox())));
        return list;
    }

    /** 準心指的點：range 內打到方塊就是打到的地方，不然就是往前 range 格；再往下找地面（最多 16 格：地圖的屏障天花板在地板上 12 格），找不到就用原本的點。 */
    Location aimPoint(final Origin origin, final double range) {
        final Location eye = origin.eye();
        final World world = origin.world();
        final RayTraceResult ray = world.rayTraceBlocks(eye, origin.direction(), range, FluidCollisionMode.NEVER, true);
        final Vector point = ray != null ? ray.getHitPosition().subtract(origin.direction().clone().multiply(0.3))
                : eye.toVector().add(origin.direction().clone().multiply(range));
        final Location at = point.toLocation(world);
        final int x = at.getBlockX();
        final int z = at.getBlockZ();
        for (int y = at.getBlockY() + 1; y >= at.getBlockY() - 16; y--) {
            if (y - 1 < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            if (world.getBlockAt(x, y, z).isPassable() && !world.getBlockAt(x, y - 1, z).isPassable()) {
                return new Location(world, at.getX(), y, at.getZ());
            }
        }
        return at;
    }

    /** 離一段線段（from → to）不超過 width 的敵人（順路打擊；owner 同 {@link #around(Location, double, Player)}）。 */
    List<LivingEntity> alongLine(final Location from, final Location to, final double width, final Player owner) {
        final Location mid = from.clone().add(to).multiply(0.5);
        final double half = from.distance(to) / 2;
        final List<LivingEntity> list = new ArrayList<>();
        for (final LivingEntity living : around(mid, half + width, owner)) {
            final Location at = living.getLocation();
            if (SkillMath.pointSegmentDistance(at.getX(), at.getZ(), from.getX(), from.getZ(), to.getX(), to.getZ()) <= width) {
                list.add(living);
            }
        }
        return list;
    }

    /** from 附近 radius 內最近、還沒打過、看得到 from 的敵人（連鎖用；owner 同 {@link #around(Location, double, Player)}）。 */
    LivingEntity nearest(final LivingEntity from, final double radius, final Set<UUID> exclude, final Player owner) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        final Location center = from.getLocation();
        for (final LivingEntity living : around(center, radius, owner)) {
            if (exclude.contains(living.getUniqueId()) || living == from) {
                continue;
            }
            final double distance = living.getLocation().distanceSquared(center);
            if (distance < bestDistance && from.hasLineOfSight(living)) {
                best = living;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 2026-10-21：某個位置（投射物碰到牆的地方）附近最近、還沒打過、從那裡看得到的敵人（從地形連鎖用）。 */
    LivingEntity nearest(final Location from, final double radius, final Set<UUID> exclude, final Player owner) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final LivingEntity living : around(from, radius, owner)) {
            if (exclude.contains(living.getUniqueId())) {
                continue;
            }
            final Location target = center(living);
            final double distance = target.distanceSquared(from);
            if (distance >= bestDistance || distance < 1e-6) {
                continue;
            }
            final org.bukkit.util.Vector direction = target.toVector().subtract(from.toVector());
            if (from.getWorld().rayTraceBlocks(from, direction, Math.sqrt(distance), org.bukkit.FluidCollisionMode.NEVER, true) == null) {
                best = living;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 身體中間的位置（畫線、爆炸用）。 */
    static Location center(final LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() / 2, 0);
    }

}
