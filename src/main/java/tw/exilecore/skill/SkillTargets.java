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
            return true;
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

    List<LivingEntity> cone(final Origin origin, final double length, final double angle) {
        final Location from = origin.feet();
        final Vector facing = origin.facing();
        final List<LivingEntity> list = new ArrayList<>();
        for (final LivingEntity living : around(from, length, origin.owner())) {
            final double dx = living.getLocation().getX() - from.getX();
            final double dz = living.getLocation().getZ() - from.getZ();
            if (SkillMath.inCone(facing.getX(), facing.getZ(), dx, dz, angle) && origin.canSee(living)) {
                list.add(living);
            }
        }
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

    /** 身體中間的位置（畫線、爆炸用）。 */
    static Location center(final LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() / 2, 0);
    }

}
