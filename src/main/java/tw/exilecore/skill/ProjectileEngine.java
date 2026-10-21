package tw.exilecore.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 投射物（設計文件「技能腳本引擎」的 projectile）：
 * <ul>
 *   <li>魔法彈（法杖、長杖、熾星彈、游雷、榴彈）：自己的位置與方向，每 tick 用射線找方塊與敵人，粒子畫軌跡；
 *       有重力的（榴彈）走拋物線、碰到方塊彈開、引信到了自己炸。</li>
 *   <li>箭（弓、弩的技能）：用原版箭矢（沒有重力、撿不起來、不存檔）飛行與碰撞，
 *       打中時取消原版的命中，改用技能的傷害；打到方塊就消失。</li>
 * </ul>
 * 打中敵人之後照 PoE 的順序：分裂 → 還能穿透就穿過去 → 還能連鎖就轉向附近另一個敵人 → 都不行就停下
 * （會爆炸的在這裡爆炸、會散射的散射、留地面效果或漩渦、瞬影箭把施放者傳過去、回歸的飛回來）。
 * 每個玩家同時最多 {@link #MAX_PER_PLAYER} 發，全部在同一個每 tick 的排程裡更新。
 */
final class ProjectileEngine implements Listener {

    static final int MAX_PER_PLAYER = 60;
    /** 回歸的投射物最多飛回來幾格（施放者跑太遠就算了）。 */
    static final double RETURN_RANGE = 48;

    /** 一發投射物。 */
    static final class Shot {
        final long id;
        final UUID owner;
        final SkillBuild build;
        final SkillProfile profile;
        final World world;
        final double size;
        final double explode;
        final double wobble;
        final double gravity;
        final Arrow arrow;
        /** 同一次施放的所有投射物共用：一個敵人一次施放只會被直接打中一次（多發不能近距離全部打在同一隻身上）；回歸時換成自己的一份。 */
        Set<UUID> hit;
        /** 這一發自己直接打中的敵人：爆炸不再打它們。 */
        final Set<UUID> struck = new HashSet<>();
        /** 散射、分裂出來的小投射物：不再散射、不再分裂、不回歸。 */
        final boolean secondary;
        Vector position;
        /** 單位方向。 */
        Vector direction;
        /** 目前每 tick 飛幾格（有重力的會變）。 */
        double speed;
        int ticksLeft;
        int fuseLeft;
        int pierceLeft;
        int chainsLeft;
        int forksLeft;
        /** 回歸中：往施放者飛，路上再打一次。 */
        boolean returning;
        /** 榴彈滾到停了：不再動，只等引信。 */
        boolean resting;
        /** 黏彈（2026-10 輔助）：碰到方塊就黏住，不彈跳。 */
        boolean sticky;
        /** 2026-10-21（PoE2 腐化箭袋）：碰到地形還能連鎖幾次（射出時照機率擲好）。 */
        int terrainChains;

        Shot(final long id, final UUID owner, final SkillBuild build, final SkillProfile profile, final World world, final Vector position,
             final Vector direction, final double speed, final double range, final Arrow arrow, final Set<UUID> hit, final boolean secondary) {
            this.id = id;
            this.owner = owner;
            this.build = build;
            this.profile = profile;
            this.world = world;
            this.position = position;
            this.direction = direction;
            this.speed = speed;
            this.size = build.size();
            this.explode = build.explode();
            this.wobble = build.wobble();
            this.gravity = build.params().gravity();
            this.arrow = arrow;
            this.hit = hit;
            this.secondary = secondary;
            this.ticksLeft = (int) Math.ceil(range / Math.max(0.1, speed));
            this.fuseLeft = build.params().fuse() > 0 ? (int) Math.max(1, Math.round(build.params().fuse() * 20)) : -1;
            this.pierceLeft = build.pierce();
            this.chainsLeft = build.chains();
            this.forksLeft = secondary ? 0 : build.forks();
        }

        String visual() {
            return build.definition().visual();
        }

        /** 有引信的榴彈不直接打敵人（彈來彈去，時間到了炸）。 */
        boolean contact() {
            return fuseLeft < 0;
        }
    }

    /** 還沒射出的後續幾波（連射）。 */
    // 2026-10-18：記住整個 Origin，圖騰／陷阱／召喚物放出的後續波次才會照原位置方向射，不是從玩家身上
    private record Volley(UUID owner, Origin origin, SkillBuild build, long atTick, int remaining) {
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final SkillDamage damage;
    private final RandomGenerator random;
    private final Map<Long, Shot> shots = new LinkedHashMap<>();
    /** 原版箭矢的 UUID → 投射物代號。 */
    private final Map<UUID, Long> arrows = new HashMap<>();
    private final Map<UUID, Integer> counts = new HashMap<>();
    private final List<Volley> volleys = new ArrayList<>();
    private SkillZones zones;
    private long nextId = 1;
    /** 龍捲風（2026-10，照 PoE2 的龍捲射擊）：施放者 → 他現在的龍捲風（最新的在最後）。 */
    private final Map<UUID, List<Tornado>> tornados = new HashMap<>();

    /** 一陣龍捲風：在哪、多大、到哪個 tick、設定、用哪個技能的特效。 */
    private record Tornado(String skill, World world, Vector center, double radius, long untilTick, TornadoSpec spec, String visual) {
    }

    ProjectileEngine(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final SkillDamage damage,
                     final RandomGenerator random) {
        this.plugin = plugin;
        this.targets = targets;
        this.visuals = visuals;
        this.damage = damage;
        this.random = random;
    }

    void setZones(final SkillZones zones) {
        this.zones = zones;
    }

    /** 發射：依數量排成水平扇形。連射的技能排後面幾波。 */
    void launch(final Origin origin, final SkillBuild build) {
        launchVolley(origin, build);
        final SkillParams.Burst burst = build.params().burst();
        if (burst != null && burst.count() > 1) {
            volleys.add(new Volley(origin.owner().getUniqueId(), origin, build, plugin.getServer().getCurrentTick() + burst.interval(), burst.count() - 1));
        }
    }

    private void launchVolley(final Origin origin, final SkillBuild build) {
        final Player owner = origin.owner();
        final Location eye = origin.eye();
        // 整圈散開的（雷網陷阱）用水平的方向，不然朝下看時整圈都射進地裡
        final Vector look = build.spread() >= 360 - 1e-9 ? origin.facing() : origin.direction();
        int count = build.projectiles();
        if (build.definition().isAttack() && count < SkillBuild.MAX_PROJECTILES) {
            // 2026-10：「x% 機率多射一發」（PoE2 弓、箭袋）：超過 100% 的部分必定多射，剩下的擲機率
            final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(owner));
            final double chance = effective.flat(tw.exilecore.stats.StatKeys.PROJECTILE_CHANCE, build.profile().tags()) / 100.0;
            if (chance > 0) {
                int extra = (int) Math.floor(chance);
                if (random.nextDouble() < chance - extra) {
                    extra++;
                }
                count = Math.min(SkillBuild.MAX_PROJECTILES, count + extra);
            }
        }
        final double spread = count > 1 && build.spread() < 1 ? Math.min(60, 12.0 * (count - 1)) : build.spread();
        final List<double[]> directions = SkillMath.fan(look.getX(), look.getY(), look.getZ(), count, spread);
        final Set<UUID> volley = new HashSet<>();
        final String visual = build.definition().visual();
        for (final double[] d : directions) {
            final Vector direction = new Vector(d[0], d[1], d[2]);
            final Vector start = eye.toVector().add(direction.clone().multiply(0.4)).add(new Vector(0, -0.15, 0));
            spawn(owner, build, build.profile(), origin.world(), start, direction, build.speed(), build.range(), volley, false, visual);
        }
        visuals.cast(visual, eye);
    }

    /** 做出一發（主要的或散射、分裂出來的）。 */
    private Shot spawn(final Player owner, final SkillBuild build, final SkillProfile profile, final World world, final Vector start,
                       final Vector direction, final double speed, final double range, final Set<UUID> volley, final boolean secondary,
                       final String visual) {
        if (counts.getOrDefault(owner.getUniqueId(), 0) >= MAX_PER_PLAYER) {
            return null;
        }
        final long id = nextId++;
        final Shot shot;
        if (build.arrow()) {
            final Location at = start.toLocation(world);
            final Arrow arrow = world.spawn(at, Arrow.class, created -> {
                created.setShooter(owner);
                created.setGravity(false);
                created.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                created.setPersistent(false);
                created.setDamage(0);
                created.setSilent(true);
                final boolean burning = "burning_arrow".equals(visual) || "explosive_bolt".equals(visual);
                created.setCritical(!burning && !"permafrost_bolt".equals(visual) && !"ice_shot".equals(visual));
                if (burning) {
                    created.setFireTicks(20 * 30);
                }
                created.setVelocity(direction.clone().multiply(speed));
            });
            shot = new Shot(id, owner.getUniqueId(), build, profile, world, arrow.getLocation().toVector(), direction, speed, range, arrow, volley, secondary);
            arrows.put(arrow.getUniqueId(), id);
        } else {
            shot = new Shot(id, owner.getUniqueId(), build, profile, world, start, direction, speed, range, null, volley, secondary);
        }
        if (shot.fuseLeft < 0) {
            // 2026-10-21：射出時擲的機率：x% 機率穿透（箭袋）、x% 機率從地形連鎖（腐化箭袋；分裂、散射出來的不擲）
            final tw.exilecore.stats.StatSheet effective = profile.effective(plugin.players().sheet(owner));
            shot.pierceLeft += extraFromChance(effective.flat(tw.exilecore.stats.StatKeys.PIERCE_CHANCE, profile.tags()), random);
            if (!secondary) {
                final double terrain = effective.flat(tw.exilecore.stats.StatKeys.CHAIN_TERRAIN, profile.tags());
                if (terrain > 0 && (terrain >= 100 || random.nextDouble() * 100 < terrain)) {
                    shot.terrainChains = 1;
                }
            }
        }
        if (shot.fuseLeft > 0) {
            // 2026-10：引信時間（長引信、短引信）、黏彈
            final tw.exilecore.stats.StatSheet effective = profile.effective(plugin.players().sheet(owner));
            final double scale = effective.apply(tw.exilecore.stats.StatKeys.GRENADE_FUSE, 1.0, profile.tags());
            shot.fuseLeft = (int) Math.max(1, Math.round(shot.fuseLeft * Math.max(0.2, Math.min(3.0, scale))));
            shot.sticky = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_NO_BOUNCE);
        }
        shots.put(id, shot);
        counts.merge(owner.getUniqueId(), 1, Integer::sum);
        return shot;
    }

    /** 每 tick：魔法彈前進一段、檢查碰撞；箭矢檢查壽命與是否插進方塊；連射的下一波。 */
    void tick() {
        final long now = plugin.getServer().getCurrentTick();
        if (!volleys.isEmpty()) {
            final List<Volley> due = new ArrayList<>();
            volleys.removeIf(volley -> {
                if (now >= volley.atTick()) {
                    due.add(volley);
                    return true;
                }
                return false;
            });
            for (final Volley volley : due) {
                final Player owner = plugin.getServer().getPlayer(volley.owner());
                if (owner == null || !owner.isValid() || owner.isDead()) {
                    continue;
                }
                // 2026-10-18：非本人放出的照原位置方向；原點的世界已卸載或玩家換了世界就不再射（原點實體被打掉時照最後位置射完這串）
                final Origin from = volley.origin();
                if (!from.self() && (!from.feet().isWorldLoaded() || !owner.getWorld().equals(from.world()))) {
                    continue;
                }
                launchVolley(from.self() ? Origin.of(owner) : Origin.at(owner, from.feet(), from.direction()), volley.build());
                if (volley.remaining() > 1) {
                    volleys.add(new Volley(volley.owner(), from, volley.build(), now + volley.build().params().burst().interval(), volley.remaining() - 1));
                }
            }
        }
        final List<Shot> snapshot = new ArrayList<>(shots.values());
        for (final Shot shot : snapshot) {
            if (!shots.containsKey(shot.id)) {
                continue; // 這個 tick 裡已經被移除
            }
            final UUID previous = Fx.source(shot.owner); // 2026-10：隱藏別人的技能特效
            try {
                if (shot.arrow != null) {
                    tickArrow(shot);
                } else {
                    tickBolt(shot);
                }
                if (shots.containsKey(shot.id)) {
                    absorb(shot, now); // 2026-10：飛進自己的龍捲風被吸進去、往外射出複製的
                }
            } finally {
                Fx.restore(previous);
            }
        }
        if (!tornados.isEmpty()) {
            tornados.values().forEach(list -> list.removeIf(tornado -> tornado.untilTick() <= now));
            tornados.values().removeIf(List::isEmpty);
        }
    }

    /**
     * 龍捲風吸投射物（2026-10，照 PoE2）：施放者自己射的攻擊投射物（箭、弩箭、長矛；不是龍捲風技能本身、不是複製出來的）
     * 飛進他的龍捲風就被吸進去，從龍捲風往外一圈射出 copies 支（傷害更少 copy_less%、最遠 copy_range 格、不會再被複製）。
     */
    private void absorb(final Shot shot, final long now) {
        if (shot.secondary || shot.returning || !shot.build.definition().isAttack()) {
            return;
        }
        final List<Tornado> mine = tornados.get(shot.owner);
        if (mine == null || mine.isEmpty() || plugin.skills() == null
                || plugin.skills().config().tornado(shot.build.definition().id()) != null) {
            return; // 龍捲風技能自己的箭是要落地捲風的，不吸
        }
        final Vector at = shot.arrow != null ? shot.arrow.getLocation().toVector() : shot.position;
        for (final Tornado tornado : mine) {
            if (tornado.untilTick() <= now || !tornado.world().equals(shot.world)) {
                continue;
            }
            final double dx = at.getX() - tornado.center().getX();
            final double dz = at.getZ() - tornado.center().getZ();
            final double dy = at.getY() - tornado.center().getY();
            if (dx * dx + dz * dz > tornado.radius() * tornado.radius() || dy < -1 || dy > 4) {
                continue;
            }
            final Player owner = plugin.getServer().getPlayer(shot.owner);
            remove(shot);
            if (owner == null) {
                return;
            }
            final TornadoSpec spec = tornado.spec();
            final SkillProfile profile = shot.profile.scaled(1 - spec.copyLess() / 100.0, "龍捲風複製");
            final double start = random.nextDouble() * Math.PI * 2;
            final Vector center = tornado.center().clone().add(new Vector(0, 1.0, 0));
            final Set<UUID> volley = new HashSet<>();
            for (int i = 0; i < spec.copies(); i++) {
                final double angle = start + i * Math.PI * 2 / Math.max(1, spec.copies());
                final Vector direction = new Vector(Math.cos(angle), 0.02, Math.sin(angle)).normalize();
                spawn(owner, shot.build, profile, shot.world, center.clone().add(direction.clone().multiply(0.6)), direction,
                        Math.max(0.6, shot.build.speed() * 0.8), spec.copyRange(), volley, true, shot.visual());
            }
            visuals.scatter(tornado.visual(), center.toLocation(shot.world));
            return;
        }
    }

    private void tickArrow(final Shot shot) {
        final Arrow arrow = shot.arrow;
        if (!arrow.isValid()) {
            remove(shot);
            return;
        }
        final Player owner = plugin.getServer().getPlayer(shot.owner);
        if (owner == null) {
            remove(shot);
            return;
        }
        if (shot.returning) {
            // 回歸：原版箭矢每 tick 重新指向施放者；這一步會經過施放者身邊就算到了（不會一直在旁邊打轉）
            final Vector to = owner.getEyeLocation().toVector().subtract(arrow.getLocation().toVector());
            final double distance = to.length();
            if (arrived(distance, shot.speed) || distance > RETURN_RANGE || --shot.ticksLeft <= 0) {
                remove(shot);
                return;
            }
            shot.direction = to.multiply(1 / distance);
            arrow.setVelocity(shot.direction.clone().multiply(shot.speed));
            visuals.arrowTrail(shot.visual(), arrow.getLocation());
            return;
        }
        if (arrow.isInBlock()) {
            // 平常在 onProjectileHit 就處理掉了；萬一沒有，照樣停下（會爆炸的在這裡爆炸）
            stop(shot, owner, arrow.getLocation());
            return;
        }
        visuals.arrowTrail(shot.visual(), arrow.getLocation());
        if (--shot.ticksLeft <= 0) {
            if (shot.build.returning() && !shot.secondary) {
                stopEffects(shot, owner, arrow.getLocation(), false);
                startReturn(shot);
                return;
            }
            stop(shot, owner, arrow.getLocation());
        }
    }

    /** 回歸的投射物這一 tick 會不會經過施放者身邊。 */
    private static boolean arrived(final double distance, final double speed) {
        return SkillMath.returnArrived(distance, speed);
    }

    private void tickBolt(final Shot shot) {
        final Player owner = plugin.getServer().getPlayer(shot.owner);
        if (owner == null || !owner.getWorld().equals(shot.world)) {
            remove(shot);
            return;
        }
        if (shot.resting) {
            if (--shot.fuseLeft <= 0) {
                stop(shot, owner, shot.position.toLocation(shot.world));
            }
            return;
        }
        if (shot.returning) {
            final Vector to = owner.getEyeLocation().toVector().subtract(shot.position);
            final double distance = to.length();
            if (arrived(distance, shot.speed) || distance > RETURN_RANGE) {
                visuals.impact(shot.visual(), shot.position.toLocation(shot.world));
                remove(shot);
                return;
            }
            shot.direction = to.multiply(1 / distance);
        } else if (shot.wobble > 0) {
            final double degrees = (random.nextDouble() * 2 - 1) * shot.wobble;
            shot.direction.rotateAroundY(Math.toRadians(degrees));
            shot.direction.setY(shot.direction.getY() * 0.7);
            shot.direction.normalize();
        }
        if (shot.gravity > 0 && !shot.returning) {
            // 拋物線：速度向量往下掉
            final Vector velocity = shot.direction.clone().multiply(shot.speed);
            velocity.setY(velocity.getY() - shot.gravity);
            shot.speed = velocity.length();
            shot.direction = shot.speed < 1e-6 ? new Vector(0, -1, 0) : velocity.multiply(1 / shot.speed);
        }
        final Vector from = shot.position.clone();
        final Location start = from.toLocation(shot.world);
        if (!shot.world.isChunkLoaded(start.getBlockX() >> 4, start.getBlockZ() >> 4)) {
            remove(shot);
            return;
        }
        final RayTraceResult block = shot.world.rayTraceBlocks(start, shot.direction, shot.speed, FluidCollisionMode.NEVER, true);
        final double travel = block == null ? shot.speed : block.getHitPosition().distance(from);
        RayTraceResult entity = travel <= 1e-6 || !shot.contact() ? null
                : shot.world.rayTraceEntities(start, shot.direction, travel, shot.size,
                        candidate -> targets.valid(candidate, owner) && !shot.hit.contains(candidate.getUniqueId()));
        if (entity == null && travel > 1e-6 && shot.contact()) {
            entity = lowTarget(shot, owner, start, travel); // 2026-10：矮的怪（洞穴蜘蛛、蠹魚）從頭頂飛過去打不到
        }
        if (entity != null && entity.getHitEntity() instanceof LivingEntity target) {
            final Vector at = entity.getHitPosition();
            visuals.trail(shot.visual(), shot.world, from, at);
            shot.position = at;
            final Vector next = onEntityHit(shot, owner, target, at.toLocation(shot.world));
            if (next != null) {
                shot.direction = next; // 連鎖：轉向下一個敵人
            }
            return;
        }
        if (block != null) {
            final Vector at = block.getHitPosition().subtract(shot.direction.clone().multiply(0.2));
            visuals.trail(shot.visual(), shot.world, from, at);
            if (shot.fuseLeft > 0 && !shot.returning) {
                bounce(shot, block.getHitBlockFace(), at);
                return;
            }
            if (shot.returning) {
                // 回歸的碰到牆：直接穿過去（從牆的另一邊繼續飛回來）
                shot.position = at.add(shot.direction.clone().multiply(0.6));
                return;
            }
            if (shot.terrainChains > 0) {
                // 2026-10-21：從地形連鎖：轉向附近最近、還沒打過的敵人
                final LivingEntity next = targets.nearest(at.toLocation(shot.world), SkillBuild.PROJECTILE_CHAIN_RANGE, shot.hit, owner);
                if (next != null) {
                    shot.terrainChains--;
                    final Vector direction = SkillTargets.center(next).toVector().subtract(at);
                    final double distance = direction.length();
                    if (distance > 1e-6) {
                        shot.position = at;
                        shot.direction = direction.multiply(1 / distance);
                        shot.ticksLeft = (int) Math.ceil((distance + 3) / Math.max(0.1, shot.speed)) + 2;
                        visuals.impact(shot.visual(), at.toLocation(shot.world));
                        return;
                    }
                }
            }
            stop(shot, owner, at.toLocation(shot.world));
            return;
        }
        final Vector next = from.clone().add(shot.direction.clone().multiply(shot.speed));
        visuals.trail(shot.visual(), shot.world, from, next);
        shot.position = next;
        if (shot.fuseLeft > 0) {
            if (--shot.fuseLeft <= 0) {
                stop(shot, owner, next.toLocation(shot.world));
            }
            return;
        }
        if (--shot.ticksLeft <= 0) {
            if (shot.returning) {
                remove(shot); // 回歸的飛太久（施放者一直跑）：消失，不再做停下來的效果
                return;
            }
            if (shot.build.returning() && !shot.secondary) {
                stopEffects(shot, owner, next.toLocation(shot.world), false);
                startReturn(shot);
                return;
            }
            visuals.fizzle(shot.visual(), next.toLocation(shot.world));
            stopEffects(shot, owner, next.toLocation(shot.world), false);
            remove(shot);
        }
    }

    /** x% 機率多一次：每 100% 必定多一次，剩下的擲一次（PoE2 的「超越」算法）。 */
    static int extraFromChance(final double percent, final RandomGenerator random) {
        if (percent <= 0) {
            return 0;
        }
        int extra = (int) (percent / 100);
        final double rest = percent - extra * 100;
        if (rest > 0 && random.nextDouble() * 100 < rest) {
            extra++;
        }
        return extra;
    }

    /** 矮的怪比照這個高度算（大約一個人高）：投射物從腰的高度飛出去，蜘蛛、蠹魚只有半格高，從牠頭上飛過去。 */
    static final double LOW_TARGET_HEIGHT = 1.6;

    /**
     * 2026-10：比 {@link #LOW_TARGET_HEIGHT} 矮的怪，碰撞箱往上拉到這個高度再算一次（洞穴蜘蛛、蠹魚、史萊姆小隻的：
     * 投射物在牠們頭頂上飛過、明明穿過去卻打不到）。只拉高、不加寬，所以不會打到旁邊的。
     */
    private RayTraceResult lowTarget(final Shot shot, final Player owner, final Location start, final double travel) {
        final Vector from = start.toVector();
        final Vector to = from.clone().add(shot.direction.clone().multiply(travel));
        final org.bukkit.util.BoundingBox sweep = org.bukkit.util.BoundingBox.of(from, to).expand(shot.size + 0.5, LOW_TARGET_HEIGHT, shot.size + 0.5);
        RayTraceResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final org.bukkit.entity.Entity candidate : shot.world.getNearbyEntities(sweep)) {
            if (!(candidate instanceof LivingEntity) || shot.hit.contains(candidate.getUniqueId()) || !targets.valid(candidate, owner)) {
                continue;
            }
            final org.bukkit.util.BoundingBox box = candidate.getBoundingBox();
            if (box.getHeight() >= LOW_TARGET_HEIGHT) {
                continue; // 一般高的照原本的算法（上面已經沒打到）
            }
            final org.bukkit.util.BoundingBox tall = box.clone().expand(shot.size, 0, shot.size, shot.size, LOW_TARGET_HEIGHT - box.getHeight(), shot.size);
            final RayTraceResult hit = tall.rayTrace(from, shot.direction, travel);
            if (hit == null) {
                continue;
            }
            final double distance = hit.getHitPosition().distanceSquared(from);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new RayTraceResult(hit.getHitPosition(), candidate, hit.getHitBlockFace());
            }
        }
        return best;
    }

    /** 榴彈碰到方塊：彈開（沿著碰到的面反彈，速度打折）。 */
    private void bounce(final Shot shot, final BlockFace face, final Vector at) {
        if (shot.sticky) {
            shot.position = at;
            shot.resting = true;
            shot.speed = 0;
            return;
        }
        final Vector velocity = shot.direction.clone().multiply(shot.speed);
        if (face != null) {
            switch (face) {
                case UP, DOWN -> velocity.setY(-velocity.getY() * 0.45);
                case NORTH, SOUTH -> velocity.setZ(-velocity.getZ() * 0.5);
                case EAST, WEST -> velocity.setX(-velocity.getX() * 0.5);
                default -> velocity.multiply(-0.4);
            }
            if (face == BlockFace.UP) {
                velocity.setX(velocity.getX() * 0.65);
                velocity.setZ(velocity.getZ() * 0.65);
            }
        } else {
            velocity.multiply(-0.4);
        }
        shot.position = at;
        if (face == BlockFace.UP && Math.abs(velocity.getY()) < 0.12 && velocity.length() < 0.35) {
            // 滾不動了：躺在地上等引信（不再每兩 tick 碰一次地板叮叮響）
            shot.resting = true;
            shot.speed = 0;
            return;
        }
        shot.speed = Math.max(0.02, velocity.length());
        shot.direction = velocity.multiply(1 / Math.max(1e-6, velocity.length()));
        visuals.bounce(shot.visual(), at.toLocation(shot.world));
        if (--shot.fuseLeft <= 0) {
            final Player owner = plugin.getServer().getPlayer(shot.owner);
            if (owner != null) {
                stop(shot, owner, at.toLocation(shot.world));
            } else {
                remove(shot);
            }
        }
    }

    /**
     * 打中一個敵人：先打它（濺射、分裂），再看穿透、連鎖、停下。
     *
     * @return 連鎖時的新方向（單位向量）；穿透或停下回傳 null
     */
    private Vector onEntityHit(final Shot shot, final Player owner, final LivingEntity target, final Location at) {
        shot.hit.add(target.getUniqueId());
        shot.struck.add(target.getUniqueId());
        damage.hit(owner, target, shot.profile, null);
        splash(shot, owner, target, at);
        if (shot.forksLeft > 0) {
            fork(shot, owner, at);
        }
        if (shot.pierceLeft > 0) {
            shot.pierceLeft--;
            visuals.impact(shot.visual(), at);
            return null;
        }
        if (shot.chainsLeft > 0) {
            final LivingEntity next = targets.nearest(target, SkillBuild.PROJECTILE_CHAIN_RANGE, shot.hit, owner);
            if (next != null) {
                shot.chainsLeft--;
                final Vector to = SkillTargets.center(next).toVector();
                final Vector direction = to.subtract(at.toVector());
                final double distance = direction.length();
                if (distance > 1e-6) {
                    direction.multiply(1 / distance);
                    shot.ticksLeft = (int) Math.ceil((distance + 3) / Math.max(0.1, shot.speed)) + 2;
                    visuals.impact(shot.visual(), at);
                    return direction;
                }
            }
        }
        if (shot.build.returning() && !shot.secondary && !shot.returning) {
            visuals.impact(shot.visual(), at);
            stopEffects(shot, owner, at, true);
            startReturn(shot);
            return null;
        }
        stop(shot, owner, at);
        return null;
    }

    /** 濺射：目標附近再電到幾個敵人（雷鳴箭）。 */
    private void splash(final Shot shot, final Player owner, final LivingEntity target, final Location at) {
        final SkillParams.Splash splash = shot.build.params().splash();
        if (splash == null) {
            return;
        }
        final SkillProfile profile = shot.profile.scaled(splash.damage() / 100.0, "濺射");
        final List<Location> points = new ArrayList<>();
        points.add(SkillTargets.center(target));
        int count = 0;
        for (final LivingEntity other : targets.around(target.getLocation(), splash.radius(), owner)) {
            if (other == target || count >= splash.targets()) {
                continue;
            }
            count++;
            damage.hit(owner, other, profile, null);
            points.add(SkillTargets.center(other));
        }
        if (points.size() > 1) {
            visuals.splash(shot.visual(), points);
        }
    }

    /** 分裂：從打中的地方再往左右各飛出一發（歧射輔助）。 */
    private void fork(final Shot shot, final Player owner, final Location at) {
        shot.forksLeft--;
        final SkillProfile profile = shot.profile.scaled(0.8, "歧射");
        for (final double degrees : new double[] {-30, 30}) {
            final Vector direction = shot.direction.clone().rotateAroundY(Math.toRadians(degrees)).normalize();
            final Vector start = at.toVector().add(direction.clone().multiply(0.5));
            final Shot forked = spawn(owner, shot.build, profile, shot.world, start, direction, shot.speed, Math.max(6, shot.build.range() / 2),
                    shot.hit, true, shot.visual());
            if (forked != null) {
                // 2026-10：分裂出來的兩發從被打中的敵人身上出發，往下射時馬上插進地板爆炸，又炸到原本那隻（單體每次多吃 2 × 80%）。
                // 照 PoE：分裂的投射物不會再打到分裂出來的那個敵人，爆炸也一樣
                forked.struck.addAll(shot.struck);
            }
        }
    }

    /** 停下：會爆炸的打周圍（這一發直接打中的不再打一次），再做停下來的效果，然後移除。 */
    private void stop(final Shot shot, final Player owner, final Location at) {
        if (shot.explode > 0) {
            for (final LivingEntity victim : targets.around(at, shot.explode, owner)) {
                if (shot.struck.contains(victim.getUniqueId())) {
                    continue;
                }
                // 2026-10：一次射好幾發的（瘴苞雨、磁霆齊發…）落點疊在一起時，同一次施放對同一個敵人只算一下——
                // 被其中一發直接打中、或已經被別發的爆炸炸到的，其他發的爆炸不再打（以前貼臉可以吃到每一發的爆炸）。
                // 散射、分裂出來的小投射物照舊（各炸各的）
                if (!shot.secondary && shot.build.projectiles() > 1 && !shot.hit.add(victim.getUniqueId())) {
                    continue;
                }
                damage.hit(owner, victim, shot.profile, null);
            }
            visuals.explosion(shot.visual(), at, shot.explode);
        } else {
            visuals.impact(shot.visual(), at);
        }
        stopEffects(shot, owner, at, true);
        remove(shot);
    }

    /** 停下來的地方：散射、地面效果、漩渦、閃現。 */
    private void stopEffects(final Shot shot, final Player owner, final Location at, final boolean reached) {
        final SkillParams params = shot.build.params();
        if (!shot.secondary && params.scatter() != null && reached) {
            scatter(shot, owner, at);
        }
        if (shot.secondary || zones == null) {
            return;
        }
        final TornadoSpec tornado = plugin.skills() == null ? null : plugin.skills().config().tornado(shot.build.definition().id());
        if (tornado != null) {
            // 2026-10（照 PoE2 的龍捲射擊）：箭停下來的地方捲起龍捲風，持續打範圍內的敵人；同時最多 max 個（多的最舊的先消失）
            final double radius = shot.build.scaledRadius(tornado.radius());
            final int ticks = shot.build.durationTicks(tornado.duration());
            final String id = shot.build.definition().id();
            zones.addCircle(owner, at, radius, shot.profile.scaled(tornado.damage() / 100.0, "龍捲風"), shot.visual(), ticks,
                    tornado.interval(), 0, id, tornado.max(), id + ".tornado");
            final List<Tornado> mine = tornados.computeIfAbsent(owner.getUniqueId(), uuid -> new ArrayList<>());
            mine.add(new Tornado(id, at.getWorld(), at.toVector(), radius, plugin.getServer().getCurrentTick() + ticks, tornado, shot.visual()));
            while (mine.size() > tornado.max()) {
                mine.removeFirst();
            }
        }
        if (params.ground() != null) {
            final SkillParams.Zone ground = params.ground();
            // 2026-10：同一招留下的地面效果疊在一起只算一灘（同一個敵人每個間隔只打一次）；單發、多發都一樣
            final String exclusive = shot.build.definition().id();
            // 同時最多幾灘：資料檔有寫 max 照它；一次射好幾發的最多「一次射幾發」灘（再放就換掉最舊的）
            final int limit = ground.max() > 0 ? ground.max() : shot.build.projectiles() > 1 ? shot.build.projectiles() : 0;
            zones.addCircle(owner, at, shot.build.scaledRadius(ground.radius()), shot.profile.scaled(ground.damage() / 100.0, "地面效果"),
                    shot.visual(), shot.build.durationTicks(ground.duration()), ground.interval(), ground.pull(),
                    limit > 0 ? shot.build.definition().id() : null, limit, exclusive);
        }
        if (params.vortex() != null) {
            final SkillParams.Vortex vortex = params.vortex();
            // 漩渦自己一組：同一招的漩渦之間不疊加，但不跟同一招的地面效果互相擋
            zones.addCircle(owner, at, vortex.radius() * shot.build.areaScale(), shot.profile.scaled(vortex.damage() / 100.0, "漩渦"),
                    shot.visual(), shot.build.durationTicks(vortex.duration()), vortex.interval(), vortex.pull(), null, 0,
                    shot.build.definition().id() + ".vortex");
        }
        if (params.blink() && !shot.returning) {
            blink(owner, at);
        }
        if (plugin.skills() != null) { // 2026-10：冰晶（凍壁矢這類）在落點留下
            final int crystalCount = IceCrystals.count(shot.build, plugin.players().sheet(owner));
            if (crystalCount > 0) {
                final org.bukkit.util.Vector dir = shot.direction == null ? new org.bukkit.util.Vector(1, 0, 0) : shot.direction.clone();
                plugin.skills().crystals().spawn(owner, shot.build, at, crystalCount, new org.bukkit.util.Vector(-dir.getZ(), 0, dir.getX()));
            }
        }
    }

    /** 散射：從停下來的地方往四周飛出幾支小的（裂空箭）。 */
    private void scatter(final Shot shot, final Player owner, final Location at) {
        final SkillParams.Scatter scatter = shot.build.params().scatter();
        final SkillProfile profile = shot.profile.scaled(scatter.damage() / 100.0, "散射");
        final Set<UUID> volley = new HashSet<>(shot.struck); // 散射的不再打剛打中的那隻
        final Vector base = shot.direction.clone().setY(0);
        if (base.lengthSquared() < 1e-6) {
            base.setX(1);
        }
        base.normalize();
        final List<double[]> directions = SkillMath.fan(base.getX(), 0, base.getZ(), scatter.count(), scatter.spread());
        final Vector start = at.toVector().add(new Vector(0, 0.2, 0));
        for (final double[] d : directions) {
            final Vector direction = new Vector(d[0], 0.05, d[2]).normalize();
            final Shot piece = spawn(owner, shot.build, profile, shot.world, start.clone().add(direction.clone().multiply(0.4)), direction,
                    Math.max(0.6, shot.build.speed() * 0.8), scatter.range(), volley, true, shot.visual());
            if (piece != null) {
                piece.struck.addAll(shot.struck); // 會爆炸的話也不炸剛打中的那隻（同分裂）
            }
        }
        visuals.scatter(shot.visual(), at);
    }

    /** 瞬影箭：施放者瞬移到投射物停下來的地方（找得到站的地方才傳）。 */
    private void blink(final Player owner, final Location at) {
        if (!owner.isValid() || owner.isDead() || !owner.getWorld().equals(at.getWorld())
                || owner.getLocation().distanceSquared(at) > RETURN_RANGE * RETURN_RANGE) {
            return;
        }
        final World world = at.getWorld();
        final int x = at.getBlockX();
        final int z = at.getBlockZ();
        for (int dy = 1; dy >= -4; dy--) {
            final int y = at.getBlockY() + dy;
            if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
                continue;
            }
            if (world.getBlockAt(x, y, z).isPassable() && world.getBlockAt(x, y + 1, z).isPassable() && !world.getBlockAt(x, y - 1, z).isPassable()) {
                final Location from = owner.getLocation();
                final Location to = new Location(world, x + 0.5, y, z + 0.5, from.getYaw(), from.getPitch());
                visuals.blink(from, to);
                owner.setFallDistance(0);
                owner.teleport(to);
                return;
            }
        }
    }

    /** 回歸：掉頭飛回施放者身邊，路上可以再打一次（剛打過的也算）。 */
    private void startReturn(final Shot shot) {
        shot.returning = true;
        shot.hit = new HashSet<>(); // 自己的一份：同一次施放的其他投射物不受影響，路上的敵人可以再打一次
        shot.pierceLeft = 50;
        shot.chainsLeft = 0;
        shot.forksLeft = 0;
        shot.ticksLeft = (int) (RETURN_RANGE / Math.max(0.1, shot.speed)) + 5;
        if (shot.arrow != null && shot.arrow.isValid()) {
            shot.arrow.setVelocity(shot.direction.clone().multiply(-shot.speed));
        }
        shot.direction.multiply(-1);
    }

    /** 原版箭矢的碰撞：打到敵人改用技能傷害（原版命中取消），打到方塊就消失。 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(final ProjectileHitEvent event) {
        final Long id = arrows.get(event.getEntity().getUniqueId());
        if (id == null) {
            return;
        }
        final Shot shot = shots.get(id);
        if (shot == null) {
            event.setCancelled(true);
            event.getEntity().remove();
            return;
        }
        final Entity hitEntity = event.getHitEntity();
        if (hitEntity != null) {
            // 原版的命中一律不要（傷害、擊退、點燃、終界使者閃避）；不是敵人就穿過去
            event.setCancelled(true);
            if (!(hitEntity instanceof LivingEntity target) || !targets.valid(target, plugin.getServer().getPlayer(shot.owner)) || shot.hit.contains(target.getUniqueId())) {
                return;
            }
            final Player owner = plugin.getServer().getPlayer(shot.owner);
            if (owner == null) {
                remove(shot);
                return;
            }
            final Vector next = onEntityHit(shot, owner, target, event.getEntity().getLocation());
            if (shots.containsKey(shot.id) && shot.arrow != null && shot.arrow.isValid()) {
                if (next != null) {
                    shot.direction = next.clone();
                    shot.arrow.setVelocity(next.multiply(shot.speed));
                } else if (shot.returning) {
                    shot.arrow.setVelocity(shot.direction.clone().multiply(shot.speed));
                }
            }
            return;
        }
        if (event.getHitBlock() != null) {
            event.setCancelled(true);
            final Player owner = plugin.getServer().getPlayer(shot.owner);
            if (owner == null) {
                remove(shot);
                return;
            }
            if (shot.returning) {
                return; // 回歸的箭矢碰到牆：下一 tick 再往施放者的方向推
            }
            if (shot.build.returning() && !shot.secondary) {
                stopEffects(shot, owner, event.getEntity().getLocation(), true);
                startReturn(shot);
                return;
            }
            stop(shot, owner, event.getEntity().getLocation());
        }
    }

    private void remove(final Shot shot) {
        if (shots.remove(shot.id) == null) {
            return;
        }
        if (shot.arrow != null) {
            arrows.remove(shot.arrow.getUniqueId());
            if (shot.arrow.isValid()) {
                shot.arrow.remove();
            }
        }
        counts.computeIfPresent(shot.owner, (uuid, count) -> count <= 1 ? null : count - 1);
    }

    /** 某個玩家的投射物全部移除（登出、換角色）。 */
    void forget(final UUID owner) {
        for (final Shot shot : new ArrayList<>(shots.values())) {
            if (shot.owner.equals(owner)) {
                remove(shot);
            }
        }
        volleys.removeIf(volley -> volley.owner().equals(owner));
        tornados.remove(owner);
    }

    /** 全部移除（插件停用）。 */
    void removeAll() {
        for (final Shot shot : new ArrayList<>(shots.values())) {
            remove(shot);
        }
        volleys.clear();
    }

    /** 這個玩家某個技能最新的一發還在飛的投射物的位置（離 near 不超過 range 格）；沒有回傳 null。 */
    Location latest(final UUID owner, final String skillId, final Location near, final double range) {
        Shot best = null;
        for (final Shot shot : shots.values()) {
            if (shot.owner.equals(owner) && shot.build.definition().id().equals(skillId) && shot.world.equals(near.getWorld())
                    && shot.position.distanceSquared(near.toVector()) <= range * range && (best == null || shot.id > best.id)) {
                best = shot;
            }
        }
        return best == null ? null : best.position.toLocation(best.world);
    }

    int active(final UUID owner) {
        return counts.getOrDefault(owner, 0);
    }
}
