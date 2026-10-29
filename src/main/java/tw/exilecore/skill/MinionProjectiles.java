package tw.exilecore.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-29 照 PoE2：遠程召喚物（骸骨射手的箭、焰術士的火彈、霜術士的冰箭）射出真的會飛的投射物。
 * <ul>
 *   <li>每一發有自己的位置、方向、速度（{@link MinionTypes.Projectiles#speedOf}：吃疾矢、緩彈這些投射物速度），每 tick 用射線找方塊與敵人；</li>
 *   <li>多射的那幾發一律射出，照玩家的投射物（{@link ProjectileEngine}）一樣排成水平扇形（{@link SkillMath#projectileSpread}），各飛各的；</li>
 *   <li>同一次射擊的所有投射物共用一份「打過了」：一個敵人一次射擊只會被直接打中一次（跟玩家的一樣，照 PoE2 投射物不能重複打同一個目標）；</li>
 *   <li>打中敵人之後照玩家的順序：分裂（第一次打中時往左右 30° 分成兩發、傷害更少 20%）→ 還能穿透就穿過去 → 還能連鎖就轉向附近另一個敵人 → 停下。</li>
 * </ul>
 * 打中時怎麼算傷害交給 {@link Handler}（MinionService：還是用原版傷害事件、傷害來源是召喚物，CombatListener 換成主人技能描述算，
 * 擊殺、偷取、異常、條件傷害、破甲照舊歸主人／召喚物）。召喚物死掉、主人下線，飛在半空的投射物就消失。
 * 這次之前召喚物的彈道是直接命中的（畫一道粒子就算打到），投射物速度沒有作用，多射的只在 60° 內有別的敵人才射。
 */
final class MinionProjectiles {

    /** 一個玩家的召喚物同時最多幾發（跟玩家自己的 {@link ProjectileEngine#MAX_PER_PLAYER} 一樣）。 */
    static final int MAX_PER_OWNER = 60;
    /** 分裂往左右各偏幾度（跟玩家的一樣）。 */
    static final double FORK_ANGLE = 30;
    /** 分裂出來的那兩發的傷害倍率（跟玩家的 ProjectileEngine.fork 一樣：更少 20%）。 */
    static final double FORK_DAMAGE = 0.8;

    /** 打中、停下時要做的事（MinionService 給）。 */
    interface Handler {
        /** 這一發直接打中 target（at = 打中的位置）。 */
        void hit(Shot shot, LivingEntity target, Location at);

        /** 這一發停下來了（撞到方塊、飛完射程、打中之後不再穿透／連鎖）；afterHit = 是剛打中敵人停下的。 */
        default void stop(final Shot shot, final Location at, final boolean afterHit) {
        }
    }

    /** 一發投射物。 */
    static final class Shot {
        final UUID owner;
        final LivingEntity shooter;
        final World world;
        final String kind;
        final double size;
        final double range;
        final Handler handler;
        /** 同一次射擊的所有投射物共用：打過的敵人不會再被直接打中。 */
        final Set<UUID> hit;
        final boolean secondary;
        /** 傷害倍率（分裂出來的 0.8）。 */
        final double damage;
        Vector position;
        Vector direction;
        final double speed;
        int ticksLeft;
        int pierceLeft;
        int chainsLeft;
        boolean forks;

        Shot(final UUID owner, final LivingEntity shooter, final World world, final String kind, final Vector position, final Vector direction,
             final double speed, final double range, final MinionTypes.Projectiles mechanics, final Set<UUID> hit, final boolean secondary,
             final double damage, final Handler handler) {
            this.owner = owner;
            this.shooter = shooter;
            this.world = world;
            this.kind = kind;
            this.position = position;
            this.direction = direction;
            this.speed = speed;
            this.range = range;
            this.size = "arrow".equals(kind) ? 0.3 : 0.4;
            this.hit = hit;
            this.secondary = secondary;
            this.damage = damage;
            this.handler = handler;
            this.ticksLeft = ticks(range, speed);
            this.pierceLeft = mechanics.pierce();
            this.chainsLeft = mechanics.chains();
            this.forks = !secondary && mechanics.fork();
        }

        /** 這一發的傷害倍率（分裂出來的更少 20%）。 */
        double damage() {
            return damage;
        }
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final List<Shot> shots = new ArrayList<>();

    MinionProjectiles(final ExileCore plugin, final SkillTargets targets) {
        this.plugin = plugin;
        this.targets = targets;
    }

    // ---- 純函式（測試用） ----

    /** 飛完 range 格要幾 tick（至少 1）。 */
    static int ticks(final double range, final double speed) {
        return (int) Math.max(1, Math.ceil(range / Math.max(0.1, speed)));
    }

    /**
     * 一次射擊每一發的方向（單位向量 {x, y, z}）：count = 1 ＋ 多射的發數；照玩家的投射物排成水平扇形
     * （{@link SkillMath#projectileSpread}：每多一發多 12°、最多 60°；中間那發對準瞄準的方向）。不管附近有沒有別的敵人都射。
     */
    static List<double[]> spread(final double dx, final double dy, final double dz, final int count) {
        final int n = Math.max(1, count);
        return SkillMath.fan(dx, dy, dz, n, SkillMath.projectileSpread(n, 0));
    }

    // ---- 發射、每 tick ----

    /**
     * 從 shooter 的眼睛朝 aimAt 射一次：1 ＋ mechanics.extra() ＋ bonus 發，排成扇形，共用一份「打過了」。
     *
     * @param speed 每 tick 幾格（已經乘好投射物速度）
     * @param range 最遠飛幾格
     */
    void launch(final UUID owner, final LivingEntity shooter, final Location aimAt, final String kind, final MinionTypes.Projectiles mechanics,
                final int bonus, final double speed, final double range, final Handler handler) {
        final Location eye = shooter.getEyeLocation();
        final Vector aim = aimAt.toVector().subtract(eye.toVector());
        if (aim.lengthSquared() < 1e-6) {
            aim.copy(eye.getDirection());
        }
        final Set<UUID> volley = new HashSet<>();
        for (final double[] d : spread(aim.getX(), aim.getY(), aim.getZ(), 1 + Math.max(0, mechanics.extra()) + Math.max(0, bonus))) {
            final Vector direction = new Vector(d[0], d[1], d[2]);
            final Vector start = eye.toVector().add(direction.clone().multiply(0.4)).add(new Vector(0, -0.15, 0));
            add(new Shot(owner, shooter, eye.getWorld(), kind, start, direction, speed, range, mechanics, volley, false, 1.0, handler));
        }
        if ("arrow".equals(kind)) {
            shooter.getWorld().playSound(shooter.getLocation(), org.bukkit.Sound.ENTITY_ARROW_SHOOT, 0.4f, 1.2f);
        }
    }

    private void add(final Shot shot) {
        int mine = 0;
        for (final Shot other : shots) {
            if (other.owner.equals(shot.owner)) {
                mine++;
            }
        }
        if (mine < MAX_PER_OWNER) {
            shots.add(shot);
        }
    }

    /** 每 tick：每一發往前飛一段、檢查碰撞。 */
    void tick() {
        if (shots.isEmpty()) {
            return;
        }
        for (final Shot shot : new ArrayList<>(shots)) {
            if (!shots.contains(shot)) {
                continue;
            }
            final UUID previous = Fx.source(shot.owner); // 隱藏別人的技能特效
            try {
                tickOne(shot);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("召喚物的投射物更新出錯", e);
                shots.remove(shot);
            } finally {
                Fx.restore(previous);
            }
        }
    }

    /** 主人的召喚物投射物全部收掉（下線、換角色）。 */
    void forget(final UUID owner) {
        shots.removeIf(shot -> shot.owner.equals(owner));
    }

    void clear() {
        shots.clear();
    }

    private void tickOne(final Shot shot) {
        final Player owner = plugin.getServer().getPlayer(shot.owner);
        if (owner == null || !shot.shooter.isValid() || shot.shooter.isDead() || !shot.world.equals(shot.shooter.getWorld())) {
            shots.remove(shot); // 召喚物死了：傷害事件要有召喚物當來源，飛在半空的就算了
            return;
        }
        final Vector from = shot.position.clone();
        final Location start = from.toLocation(shot.world);
        if (!shot.world.isChunkLoaded(start.getBlockX() >> 4, start.getBlockZ() >> 4)) {
            shots.remove(shot);
            return;
        }
        final RayTraceResult block = shot.world.rayTraceBlocks(start, shot.direction, shot.speed, FluidCollisionMode.NEVER, true);
        final double travel = block == null ? shot.speed : block.getHitPosition().distance(from);
        final RayTraceResult entity = travel <= 1e-6 ? null
                : shot.world.rayTraceEntities(start, shot.direction, travel, shot.size,
                        candidate -> candidate instanceof LivingEntity && candidate != shot.shooter && !shot.hit.contains(candidate.getUniqueId())
                                && targets.valid(candidate, owner));
        if (entity != null && entity.getHitEntity() instanceof LivingEntity target) {
            final Vector at = entity.getHitPosition();
            trail(shot.world, from, at, shot.kind);
            shot.position = at;
            onEntityHit(shot, owner, target, at.toLocation(shot.world));
            return;
        }
        if (block != null) {
            final Vector at = block.getHitPosition().subtract(shot.direction.clone().multiply(0.2));
            trail(shot.world, from, at, shot.kind);
            stop(shot, at.toLocation(shot.world), false);
            return;
        }
        final Vector next = from.clone().add(shot.direction.clone().multiply(shot.speed));
        trail(shot.world, from, next, shot.kind);
        shot.position = next;
        if (--shot.ticksLeft <= 0) {
            stop(shot, next.toLocation(shot.world), false);
        }
    }

    /** 打中敵人：照玩家的順序 分裂 → 穿透 → 連鎖 → 停下。 */
    private void onEntityHit(final Shot shot, final Player owner, final LivingEntity target, final Location at) {
        shot.hit.add(target.getUniqueId());
        shot.handler.hit(shot, target, at);
        if (!shots.contains(shot)) {
            return;
        }
        if (shot.forks) {
            shot.forks = false;
            for (final double degrees : new double[] {-FORK_ANGLE, FORK_ANGLE}) {
                final Vector direction = shot.direction.clone().rotateAroundY(Math.toRadians(degrees)).normalize();
                final Vector start = at.toVector().add(direction.clone().multiply(0.5));
                // 分裂出來的：跟玩家的一樣共用「打過了」（不會再打分裂出來的那隻）、射程一半（至少 6 格）、穿透連鎖照原本的
                add(new Shot(shot.owner, shot.shooter, shot.world, shot.kind, start, direction, shot.speed, Math.max(6, shot.range / 2),
                        new MinionTypes.Projectiles(0, shot.pierceLeft, shot.chainsLeft, false, 1.0), shot.hit, true,
                        shot.damage * FORK_DAMAGE, shot.handler));
            }
        }
        if (shot.pierceLeft > 0) {
            shot.pierceLeft--;
            return; // 穿過去，繼續飛
        }
        if (shot.chainsLeft > 0) {
            final LivingEntity next = targets.nearest(target, SkillBuild.PROJECTILE_CHAIN_RANGE, shot.hit, owner);
            if (next != null) {
                final Vector direction = SkillTargets.center(next).toVector().subtract(at.toVector());
                final double distance = direction.length();
                if (distance > 1e-6) {
                    shot.chainsLeft--;
                    shot.direction = direction.multiply(1 / distance);
                    shot.ticksLeft = ticks(distance + 3, shot.speed) + 2;
                    return;
                }
            }
        }
        stop(shot, at, true);
    }

    private void stop(final Shot shot, final Location at, final boolean afterHit) {
        shots.remove(shot);
        shot.handler.stop(shot, at, afterHit);
    }

    /** 這一 tick 飛過的那一段：一串粒子。 */
    static void trail(final World world, final Vector a, final Vector b, final String kind) {
        final Vector step = b.clone().subtract(a);
        final double length = step.length();
        if (length < 0.05) {
            return;
        }
        final org.bukkit.Particle particle = switch (kind) {
            case "fire" -> org.bukkit.Particle.FLAME;
            case "cold" -> org.bukkit.Particle.SNOWFLAKE;
            case "lightning" -> org.bukkit.Particle.ELECTRIC_SPARK;
            case "chaos" -> org.bukkit.Particle.WITCH;
            default -> org.bukkit.Particle.CRIT;
        };
        step.multiply(0.5 / length);
        final Location at = a.toLocation(world);
        for (double d = 0; d < length; d += 0.5) {
            Fx.spawn(world, particle, at, 1, 0, 0, 0, 0);
            at.add(step);
        }
    }
}
