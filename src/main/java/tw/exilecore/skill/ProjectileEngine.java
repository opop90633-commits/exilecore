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

    /** 2026-10-26：碎片可以疊加的技能標籤（PoE2 的 Merging）。 */
    static final String MERGING = "merging";
    /** 2026-10-26：引導累積、放開一起射出的骨刺（Bonestorm）：每一支都可以打中同一個敵人。 */
    static final String SPIKE_VOLLEY = "spike_volley";


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
        /** 2026-10-26（穿雲箭袋）：已經穿透過幾次。 */
        int pierced;
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
        /** 2026-10-26：霜岔已經從凍結的敵人分裂過、躍霆已經從感電的敵人連鎖過（一發只有一次）。 */
        boolean forkedFrozen;
        boolean chainedShocked;
        /** 2026-10-26：魔湧的分裂已經算過（一發只分裂一次）。 */
        boolean split;
        /** 2026-10-28 照 PoE2（纏蝕箭）：射出時擲到的「多射幾發」改成多分裂幾個目標。 */
        int splitBonus;
        /** 2026-10-26 照 PoE2（熔球）：每次落地都炸一圈、往前彈；還能彈幾次（-1 = 不是這種）。 */
        int hops = -1;
        /** 2026-10-26 照 PoE2（遠目獻祭）：無法與地形碰撞（穿過方塊）。 */
        boolean noTerrain;

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
    private record Tornado(String skill, World world, Vector center, double radius, long untilTick, TornadoSpec spec, String visual, int level) {
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
        // 2026-10-28 照 PoE2（纏蝕箭）：投射物數量的修飾器改加在分裂目標數上——機率多射的也一樣，擲到幾發就多分裂幾個目標（每一發的 splitBonus）；
        // 這次之前一律多射
        final boolean toSplit = owner != null && tw.exilecore.stats.StatKeys.flag(build.profile().effective(plugin.players().sheet(owner)),
                tw.exilecore.stats.GrantedSkillKeys.FLAG_PROJECTILES_TO_SPLIT);
        int rolled = 0;
        if (build.definition().isAttack() && (count < SkillBuild.MAX_PROJECTILES || toSplit)) {
            // 2026-10：「x% 機率多射一發」（PoE2 弓、箭袋）：超過 100% 的部分必定多射，剩下的擲機率
            final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(owner));
            final double chance = effective.flat(tw.exilecore.stats.StatKeys.PROJECTILE_CHANCE, build.profile().tags()) / 100.0;
            if (chance > 0) {
                int extra = (int) Math.floor(chance);
                if (random.nextDouble() < chance - extra) {
                    extra++;
                }
                rolled += extra;
            }
        }
        if (owner != null && (count < SkillBuild.MAX_PROJECTILES || toSplit)) {
            // 2026-10-26 照 PoE2 天賦樹：「法術技能 x% 機率多射 2 發投射物」（修飾器寫 spell 標籤）
            final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(owner));
            final double two = effective.flat(tw.exilecore.stats.TreeEffectKeys.PROJECTILE_CHANCE_TWO, build.profile().tags());
            if (two > 0) {
                rolled += 2 * tw.exilecore.stats.TreeEffectKeys.fromChance(two, random);
            }
        }
        final int splitBonus = toSplit ? rolled : 0;
        if (!toSplit) {
            count = Math.min(SkillBuild.MAX_PROJECTILES, count + rolled);
        }
        // 2026-10-27 照 PoE2（炎爆矛）：飛到準心指的點、插在那裡（一發、投射物數量不能改）；這次之前拋物線，落在哪裡看角度
        final Location aimedAt = owner == null || plugin.skills() == null ? null : plugin.skills().spears().landingPoint(origin, build);
        if (aimedAt != null) {
            final Vector start = eye.toVector().add(new Vector(0, -0.15, 0));
            final Vector path = aimedAt.toVector().add(new Vector(0, 0.1, 0)).subtract(start);
            final double distance = Math.max(0.5, path.length());
            final int ticks = (int) Math.max(1, Math.ceil(distance / Math.max(0.1, build.speed())));
            spawn(owner, build, build.profile(), origin.world(), start, path.multiply(1 / distance), distance / ticks, distance - 1e-6,
                    new HashSet<>(), false, build.definition().visual());
            visuals.cast(build.definition().visual(), eye);
            return;
        }
        final double spread = count > 1 && build.spread() < 1 ? Math.min(60, 12.0 * (count - 1)) : build.spread();
        final List<double[]> directions = SkillMath.fan(look.getX(), look.getY(), look.getZ(), count, spread);
        final Set<UUID> volley = new HashSet<>();
        final String visual = build.definition().visual();
        // 2026-10-26（PoE2 的 Merging）：弩的碎片彈每一片都可以打中同一個敵人（貼臉一發的碎片全部打在同一隻身上）
        final boolean merging = build.definition().tags().contains(MERGING) || build.definition().tags().contains(SPIKE_VOLLEY);
        // 2026-10-26 照 PoE2（纏蝕箭）：每一種傷害各一發（物理一發、混沌一發，各自打中）
        final List<SkillProfile> split = splitByType(owner, build.profile());
        int index = 0;
        for (final double[] d : directions) {
            final Vector direction = new Vector(d[0], d[1], d[2]);
            final Vector start = eye.toVector().add(direction.clone().multiply(0.4)).add(new Vector(0, -0.15, 0));
            final SkillProfile profile = split.isEmpty() ? build.profile() : split.get(index++ % split.size());
            final Shot shot = spawn(owner, build, profile, origin.world(), start, direction, build.speed(), build.range(),
                    merging || !split.isEmpty() ? new HashSet<>() : volley, false, visual);
            if (shot != null) {
                shot.splitBonus = splitBonus;
            }
        }
        visuals.cast(visual, eye);
    }

    /**
     * 2026-10-26 照 PoE2（纏蝕箭的物理、混沌兩發）：技能有 flag.split_damage_types 就照法術傷害的種類拆成幾個只帶一種傷害的版本
     * （每一種保留原本的數值）；沒有回傳空的。
     */
    private List<SkillProfile> splitByType(final Player owner, final SkillProfile profile) {
        if (profile.spellDamage() == null || owner == null
                || !tw.exilecore.stats.StatKeys.flag(profile.effective(plugin.players().sheet(owner)), tw.exilecore.stats.SkillMechanicKeys.FLAG_SPLIT_TYPES)) {
            return List.of();
        }
        final List<SkillProfile> result = new ArrayList<>();
        for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
            if (profile.spellDamage().get(type) <= 0) {
                continue;
            }
            final tw.exilecore.stats.DamagePacket min = new tw.exilecore.stats.DamagePacket().set(type, profile.spellDamage().get(type));
            final tw.exilecore.stats.DamagePacket max = new tw.exilecore.stats.DamagePacket().set(type, profile.spellMax().get(type));
            result.add(new SkillProfile(profile.id(), profile.tags(), profile.effectiveness(), min, max, profile.conversion(), profile.local()));
        }
        return result.size() > 1 ? result : List.of();
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
            if (!secondary && shot.chainsLeft > 0) {
                // 2026-10-26 照 PoE2 天賦樹：「閃電技能 x% 機率多連鎖一次」（會連鎖的投射物才有）
                shot.chainsLeft += tw.exilecore.stats.TreeEffectKeys.fromChance(
                        effective.flat(tw.exilecore.stats.TreeEffectKeys.CHAIN_CHANCE, profile.tags()), random);
            }
            if (!secondary) {
                // 2026-10-27 照 PoE2 天賦樹：「過去 8 秒近戰擊中過時，投射物 x% 機率分裂」
                shot.forksLeft += tw.exilecore.stats.TreeEffectKeys.fromChance(
                        effective.flat(tw.exilecore.stats.TreeLastKeys.FORK_CHANCE, profile.tags()), random) > 0 ? 1 : 0;
                final double terrain = effective.flat(tw.exilecore.stats.StatKeys.CHAIN_TERRAIN, profile.tags());
                if (terrain > 0 && (terrain >= 100 || random.nextDouble() * 100 < terrain)) {
                    shot.terrainChains = Math.max(1, (int) Math.floor(terrain / 100)); // 2026-10-27 照 PoE2 Ricochet：100 一次（多的投射物變成從地形連鎖幾次）
                }
                // 2026-10-26 照 PoE2 遠目獻祭（不朽恨意，烏拉曼）：每一發隨機一種：多分裂一次、多連鎖一次、多從地形連鎖一次、無法與地形碰撞
                if (tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.passive.ConquestEffects.SIGHT)) {
                    switch (random.nextInt(4)) {
                        case 0 -> shot.forksLeft++;
                        case 1 -> shot.chainsLeft++;
                        case 2 -> shot.terrainChains++;
                        default -> shot.noTerrain = true;
                    }
                }
            }
        }
        if (shot.fuseLeft > 0) {
            // 2026-10：引信時間（長引信、短引信）、黏彈
            final tw.exilecore.stats.StatSheet effective = profile.effective(plugin.players().sheet(owner));
            final double scale = effective.apply(tw.exilecore.stats.StatKeys.GRENADE_FUSE, 1.0, profile.tags());
            shot.fuseLeft = (int) Math.max(1, Math.round(shot.fuseLeft * Math.max(0.2, Math.min(3.0, scale))));
            shot.sticky = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.StatKeys.FLAG_NO_BOUNCE);
            // 2026-10-26 照 PoE2（熔球）：每次落地都炸一圈往前彈，彈幾次照 projectile.hops（逐級）
            final int hops = (int) Math.round(effective.flat(tw.exilecore.stats.SkillMechanicKeys.PROJECTILE_HOPS, profile.tags()));
            if (hops > 0 && !secondary) {
                shot.hops = hops;
                shot.sticky = false;
            }
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
                if (shots.containsKey(shot.id)) {
                    refract(shot); // 2026-10-26：法術投射物碰到折光之鏡，往四周複製 7 發
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
            final SkillProfile profile = shot.profile.scaled(1 - spec.copyLess(tornado.level()) / 100.0, "龍捲風複製"); // 2026-10-26：照等級
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

    /** 2026-10-26（PoE2 的 Mirror of Refraction）：法術投射物碰到主人的鏡子 → 鏡子碎掉，從鏡子往四周射 7 發一樣的（複製的還能再被別的鏡子複製）。 */
    private void refract(final Shot shot) {
        if (shot.arrow != null || shot.returning || shot.build.definition().isAttack() || plugin.skills() == null) {
            return;
        }
        final GrantedAuras granted = plugin.skills().granted();
        if (granted == null) {
            return;
        }
        final org.bukkit.Location mirror = granted.shatter(shot.owner, shot.world, shot.position);
        if (mirror == null) {
            return;
        }
        final Player owner = plugin.getServer().getPlayer(shot.owner);
        if (owner == null) {
            return;
        }
        final double start = random.nextDouble() * Math.PI * 2;
        final Vector center = mirror.toVector();
        for (int i = 0; i < GrantedAuras.MIRROR_COPIES; i++) {
            final double angle = start + i * Math.PI * 2 / GrantedAuras.MIRROR_COPIES;
            final Vector direction = new Vector(Math.cos(angle), 0.02, Math.sin(angle)).normalize();
            spawn(owner, shot.build, shot.profile, shot.world, center.clone().add(direction.clone().multiply(0.8)), direction,
                    shot.build.speed(), shot.build.range(), new HashSet<>(), false, shot.visual());
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
        final Location arrowAt = arrow.getLocation(); // 2026-10-25：這一 tick 飛過的路上的冰晶、鐘直接被打到
        targets.strike(owner, shot.profile, SkillTargets.segment(arrowAt.clone().subtract(arrow.getVelocity()), arrowAt, 0.3), shot);
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
        final RayTraceResult block = shot.noTerrain ? null
                : shot.world.rayTraceBlocks(start, shot.direction, shot.speed, FluidCollisionMode.NEVER, true);
        final double travel = block == null ? shot.speed : block.getHitPosition().distance(from);
        RayTraceResult entity = travel <= 1e-6 || !shot.contact() ? null
                : shot.world.rayTraceEntities(start, shot.direction, travel, shot.size,
                        candidate -> targets.valid(candidate, owner) && !shot.hit.contains(candidate.getUniqueId()));
        if (entity == null && travel > 1e-6 && shot.contact()) {
            entity = lowTarget(shot, owner, start, travel); // 2026-10：矮的怪（洞穴蜘蛛、蠹魚）從頭頂飛過去打不到
        }
        if (travel > 1e-6 && shot.contact()) { // 2026-10-25：飛過的路上的冰晶、鐘直接被打到
            final double reach = entity != null ? entity.getHitPosition().distance(from) : travel;
            targets.strike(owner, shot.profile, SkillTargets.segment(start, start.clone().add(shot.direction.clone().multiply(reach)), shot.size), shot);
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

    /** 熔球每一跳往前多快、往上多快（每 tick 格數）。 */
    static final double HOP_FORWARD = 0.45;
    static final double HOP_UP = 0.42;

    /** 榴彈碰到方塊：彈開（沿著碰到的面反彈，速度打折）。 */
    private void bounce(final Shot shot, final BlockFace face, final Vector at) {
        if (shot.hops >= 0 && face == BlockFace.UP) {
            // 2026-10-26 照 PoE2 Rolling Magma：落地炸一圈，再往前彈（Chains 3／5／6 次），彈完最後一下炸完就沒了
            final Player owner = plugin.getServer().getPlayer(shot.owner);
            if (owner == null) {
                remove(shot);
                return;
            }
            final Location landed = at.toLocation(shot.world);
            if (shot.hops == 0) {
                stop(shot, owner, landed);
                return;
            }
            shot.hops--;
            blast(shot, owner, landed);
            final Vector forward = shot.direction.clone().setY(0);
            if (forward.lengthSquared() < 1e-6) {
                forward.setX(1);
            }
            final Vector velocity = forward.normalize().multiply(HOP_FORWARD).setY(HOP_UP);
            shot.position = at;
            shot.speed = velocity.length();
            shot.direction = velocity.multiply(1 / shot.speed);
            shot.fuseLeft = Math.max(shot.fuseLeft, 200); // 彈跳次數決定什麼時候結束，不看引信
            shot.ticksLeft = Math.max(shot.ticksLeft, 200);
            return;
        }
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
        final tw.exilecore.stats.StatSheet ownerSheet = plugin.players().sheet(owner);
        // 2026-10-26（穿燼、霜岔、躍霆，PoE2）：打中前看目標身上的異常——穿透點燃中的、從凍結的分裂、第一次打中感電的一定連鎖
        boolean pierceFree = false;
        boolean forkFrozen = false;
        boolean chainShocked = false;
        if (plugin.ailments() != null) {
            final tw.exilecore.stats.StatSheet effective = shot.profile.effective(ownerSheet);
            pierceFree = tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.SupportKeys.PIERCE_IGNITED)
                    && plugin.ailments().has(target, tw.exilecore.ailment.AilmentType.IGNITE)
                    // 2026-10-26 照 PoE2 天賦樹：投射物穿透完全破甲的敵人
                    || tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.KeystoneKeys.PIERCE_BROKEN)
                    && Double.isInfinite(plugin.ailments().armourBroken(target));
            forkFrozen = !shot.secondary && !shot.forkedFrozen && tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.SupportKeys.FORK_FROZEN)
                    && plugin.ailments().isFrozen(target);
            chainShocked = !shot.chainedShocked && tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.SupportKeys.CHAIN_SHOCKED)
                    && plugin.ailments().has(target, tw.exilecore.ailment.AilmentType.SHOCK);
        }
        // 2026-10-27 照 PoE2 天賦樹：「投射物 x% 機率穿透你 3 格內的敵人」（不算穿透次數）
        if (!pierceFree) {
            pierceFree = TreeLeftovers.pierceClose(shot.profile.effective(ownerSheet), shot.profile.tags(), owner, target, random);
        }
        // 2026-10-26（穿雲箭袋）：每穿透過一次，擊中傷害與暴擊率增加
        damage.hit(owner, target, tw.exilecore.item.unique.UniqueMechanics.pierceProfile(shot.profile, shot.pierced, ownerSheet), null);
        splash(shot, owner, target, at);
        if (!shot.secondary && shot.build.params().scatter() != null && shot.build.params().scatter().onHit()) {
            scatter(shot, owner, at); // 2026-10-26 照 PoE2（凝霜箭）：打中敵人時往前方噴出冰片
        }
        if (!shot.secondary && !shot.split) {
            split(shot, owner, target, ownerSheet); // 2026-10-26 照 PoE2（魔湧強化的法術）：分裂射向附近其他敵人
        }
        if (shot.forksLeft > 0) {
            fork(shot, owner, at);
        } else if (forkFrozen) {
            shot.forkedFrozen = true;
            shot.forksLeft = 1;
            fork(shot, owner, at);
        }
        if (pierceFree) {
            visuals.impact(shot.visual(), at);
            return null; // 穿過去，不算穿透次數
        }
        if (shot.pierceLeft > 0) {
            shot.pierceLeft--;
            shot.pierced++;
            visuals.impact(shot.visual(), at);
            // 2026-10-26（穿雲箭袋）：攻擊的投射物穿透至少 x 次之後折返
            if (!shot.secondary && !shot.returning && shot.build.definition().isAttack()
                    && tw.exilecore.item.unique.UniqueMechanics.returnsAfter(ownerSheet, shot.profile.tags(), shot.pierced)) {
                stopEffects(shot, owner, at, true);
                startReturn(shot);
            }
            return null;
        }
        if (chainShocked && shot.chainsLeft <= 0) {
            shot.chainedShocked = true;
            shot.chainsLeft = 1;
        }
        // 2026-10-28 照 PoE2（纏蝕箭）：可以從任意多個被詛咒的敵人連鎖（不算連鎖次數）、連鎖距離增加 50%；這次之前只有連鎖次數、固定 10 格
        final tw.exilecore.stats.StatSheet chainSheet = shot.profile.effective(ownerSheet);
        final boolean chainCursed = tw.exilecore.stats.StatKeys.flag(chainSheet, tw.exilecore.stats.GrantedSkillKeys.FLAG_CHAIN_CURSED);
        final boolean targetCursed = chainCursed && plugin.combat() != null
                && plugin.combat().debuffs().cursed(target.getUniqueId(), plugin.getServer().getCurrentTick());
        if (tw.exilecore.stats.GrantedSkillKeys.canChain(shot.chainsLeft, chainCursed, targetCursed)) {
            final LivingEntity next = targets.nearest(target, tw.exilecore.stats.GrantedSkillKeys.chainRange(SkillBuild.PROJECTILE_CHAIN_RANGE,
                    chainSheet.increased(tw.exilecore.stats.GrantedSkillKeys.CHAIN_RANGE, shot.profile.tags())), shot.hit, owner);
            if (next != null) {
                if (tw.exilecore.stats.GrantedSkillKeys.consumesChain(chainCursed, targetCursed)) {
                    shot.chainsLeft--;
                }
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
        final int level = shot.build.level(); // 2026-10-26：雷束的數量、傷害照等級（PoE2 雷鳴箭逐級）
        final SkillProfile profile = shot.profile.scaled(splash.damage(level) / 100.0, "濺射");
        final List<Location> points = new ArrayList<>();
        points.add(SkillTargets.center(target));
        int count = 0;
        // 2026-10-28 照 PoE2 Lightning Rod：閃電的雷束也可以射向自己插在地上的雷樁矢箭（佔一道雷束，箭充能後再炸一次）；
        // 箭跟敵人照距離排，近的先（這次之前只射敵人）
        final List<LightningRods.Rod> rods = zones != null && SkillZones.chainsToRods(profile)
                ? zones.rodsAround(owner, target.getLocation(), splash.radius()) : List.of();
        final List<LivingEntity> enemies = targets.around(target.getLocation(), splash.radius(), owner);
        if (!rods.isEmpty()) {
            final Location from = SkillTargets.center(target);
            enemies.removeIf(other -> other == target);
            enemies.sort(java.util.Comparator.comparingDouble(other -> other.getLocation().distanceSquared(target.getLocation())));
            int e = 0;
            int r = 0;
            while (count < splash.targets(level) && (e < enemies.size() || r < rods.size())) {
                final LivingEntity enemy = e < enemies.size() ? enemies.get(e) : null;
                final LightningRods.Rod rod = r < rods.size() ? rods.get(r) : null;
                if (rod != null && (enemy == null
                        || rod.at.distanceSquared(target.getLocation().toVector()) < enemy.getLocation().distanceSquared(target.getLocation()))) {
                    r++;
                    if (zones.chainToRod(owner, rod, profile)) {
                        count++;
                        points.add(SkillZones.rodTop(rod.at.toLocation(from.getWorld())));
                    }
                } else {
                    e++;
                    count++;
                    damage.hit(owner, enemy, profile, null);
                    points.add(SkillTargets.center(enemy));
                }
            }
            if (points.size() > 1) {
                visuals.splash(shot.visual(), points);
            }
            return;
        }
        for (final LivingEntity other : enemies) {
            if (other == target || count >= splash.targets(level)) {
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

    /** 2026-10-26：這一發的技能描述帶不帶某個只對這一下有效的旗標。 */
    private static boolean hasLocal(final SkillProfile profile, final String stat) {
        for (final tw.exilecore.stats.Modifier modifier : profile.local()) {
            if (stat.equals(modifier.stat()) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 分裂：從打中的地方再往左右各飛出一發（歧射輔助）。 */
    private void fork(final Shot shot, final Player owner, final Location at) {
        shot.forksLeft--;
        final SkillProfile profile = shot.profile.scaled(0.8, "歧射");
        // 2026-10-26 照 PoE2 天賦樹：「投射物分裂時 x% 機率多分出一發」（正前方再一發）
        final double extraFork = owner == null ? 0
                : profile.effective(plugin.players().sheet(owner)).flat(tw.exilecore.stats.TreeEffectKeys.FORK_EXTRA_CHANCE, profile.tags());
        final double[] angles = extraFork > 0 && random.nextDouble() * 100 < extraFork ? new double[] {-30, 0, 30} : new double[] {-30, 30};
        for (final double degrees : angles) {
            final Vector direction = shot.direction.clone().rotateAroundY(Math.toRadians(degrees)).normalize();
            final Vector start = at.toVector().add(direction.clone().multiply(0.5));
            final Shot forked = spawn(owner, shot.build, profile, shot.world, start, direction, shot.speed, Math.max(6, shot.build.range() / 2),
                    shot.hit, true, shot.visual());
            if (forked != null) {
                // 2026-10：分裂出來的兩發從被打中的敵人身上出發，往下射時馬上插進地板爆炸，又炸到原本那隻（單體每次多吃 2 × 80%）。
                // 照 PoE：分裂的投射物不會再打到分裂出來的那個敵人，爆炸也一樣
                forked.struck.addAll(shot.struck);
                if (tw.exilecore.item.unique.UniqueMechanics.pierceAfterFork(plugin.players().sheet(owner), profile.tags())) {
                    forked.pierceLeft = Math.max(forked.pierceLeft, 50); // 2026-10-26（裂穹）：分裂之後穿透所有目標
                }
            }
        }
    }

    /** 2026-10-26（熔球）：落地炸一圈（不移除、每一跳都可以再打到同一個敵人）。 */
    private void blast(final Shot shot, final Player owner, final Location at) {
        final double radius = shot.explode > 0 ? shot.explode : 1.5;
        targets.strike(owner, shot.profile, SkillTargets.sphere(at, radius), new Object());
        for (final LivingEntity victim : targets.around(at, radius, owner)) {
            damage.hit(owner, victim, shot.profile, null);
        }
        visuals.explosion(shot.visual(), at, radius);
    }

    /** 停下：會爆炸的打周圍（這一發直接打中的不再打一次），再做停下來的效果，然後移除。 */
    private void stop(final Shot shot, final Player owner, final Location at) {
        if (shot.explode > 0) {
            final boolean burstPoison = PoisonOnly.burst(plugin.players().sheet(owner), shot.profile);
            targets.strike(owner, shot.profile, SkillTargets.sphere(at, shot.explode), new Object()); // 2026-10-25：爆炸也打到冰晶、鐘
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
                if (burstPoison) {
                    PoisonOnly.apply(plugin, owner, victim, shot.profile); // 2026-10-26 照 PoE2（爆毒矢）：毒爆不打人、像打中一樣上毒
                    continue;
                }
                damage.hit(owner, victim, shot.profile, null);
            }
            visuals.explosion(shot.visual(), at, shot.explode);
            // 2026-10-26 照 PoE2 天賦：榴彈 x% 機率再爆一次（0.5 秒後同一個地方）
            if (shot.fuseLeft >= 0 && !shot.secondary && shot.profile.tags().contains(tw.exilecore.stats.Tags.GRENADE)
                    && random.nextDouble() * 100 < shot.profile.effective(plugin.players().sheet(owner))
                    .flat(tw.exilecore.stats.TreeNewMechanicKeys.GRENADE_TWICE, shot.profile.tags())) {
                final SkillProfile profile = shot.profile;
                final double radius = shot.explode;
                final String visual = shot.visual();
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!owner.isOnline() || !owner.getWorld().equals(at.getWorld())) {
                        return;
                    }
                    for (final LivingEntity victim : targets.around(at, radius, owner)) {
                        damage.hit(owner, victim, profile, null);
                    }
                    visuals.explosion(visual, at, radius);
                }, 10);
            }
        } else {
            visuals.impact(shot.visual(), at);
        }
        stopEffects(shot, owner, at, true);
        remove(shot);
    }

    /** 停下來的地方：散射、地面效果、漩渦、閃現。 */
    private void stopEffects(final Shot shot, final Player owner, final Location at, final boolean reached) {
        final SkillParams params = shot.build.params();
        // 2026-10-27 照 PoE2 亞馬遜「元素湧動」：用掉湧動的投射物飛行結束時另外觸發一發湧動爆炸（分裂、散射出來的不算）
        if (!shot.secondary && !shot.returning && hasLocal(shot.profile, SkillResources.SURGE_BLAST) && plugin.skills() != null) {
            plugin.skills().resources().surgeBlast(owner, at);
        }
        if (!shot.secondary && params.scatter() != null && !params.scatter().onHit() && reached) {
            scatter(shot, owner, at);
        }
        if (!shot.secondary && !shot.returning && owner != null && plugin.skills() != null) {
            plugin.skills().spears().landed(owner, shot.build, shot.profile, at, shot.direction); // 2026-10-27 照 PoE2：擲矛（怒星炸開）、炎爆矛（插地；怒星十字照飛的方向）
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
            mine.add(new Tornado(id, at.getWorld(), at.toVector(), radius, plugin.getServer().getCurrentTick() + ticks, tornado, shot.visual(), shot.build.level()));
            while (mine.size() > tornado.max()) {
                mine.removeFirst();
            }
        }
        if (params.ground() != null && !hasLocal(shot.profile, OriginalSkills.NO_GROUND)) { // 2026-10-26：黑油雨的帶電油彈不留油地
            final SkillParams.Zone ground = params.ground();
            // 2026-10：同一招留下的地面效果疊在一起只算一灘（同一個敵人每個間隔只打一次）；單發、多發都一樣
            final String exclusive = shot.build.definition().id();
            // 同時最多幾灘：資料檔有寫 max 照它；一次射好幾發的最多「一次射幾發」灘（再放就換掉最舊的）
            final int limit = ground.max() > 0 ? ground.max() : shot.build.projectiles() > 1 ? shot.build.projectiles() : 0;
            zones.addCircle(owner, at, shot.build.scaledRadius(ground.radius()), shot.profile.scaled(ground.damage() / 100.0, "地面效果"),
                    shot.visual(), shot.build.durationTicks(ground.duration()), ground.interval(), ground.pull(),
                    limit > 0 ? shot.build.definition().id() : null, limit, exclusive);
        }
        final SkillConfig.LodgeSpec lodge = plugin.skills() == null ? null : plugin.skills().config().lodge(shot.build.definition().id());
        if (lodge != null && reached) {
            zones.lodge(owner, shot.build.definition().id(), shot.build.level(), at); // 2026-10-26 照 PoE2（瘴苞雨）：落地留下毒苞，之後引爆
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
        final SkillProfile profile = scatter.effectiveness() > 0 ? shardProfile(shot, scatter) : shot.profile.scaled(scatter.damage() / 100.0, "散射");
        final Set<UUID> volley = new HashSet<>(shot.struck); // 散射的不再打剛打中的那隻
        final Vector base = shot.direction.clone().setY(0);
        if (base.lengthSquared() < 1e-6) {
            base.setX(1);
        }
        base.normalize();
        final List<double[]> directions = SkillMath.fan(base.getX(), 0, base.getZ(), scatter.count(shot.build.level()), scatter.spread());
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

    /**
     * 2026-10-26 照 PoE2（凝霜箭的冰片）：小的用自己的傷害效益與轉換；技能自己帶的「凍結累積更多」不給小的（0.5 拿掉了），
     * 輔助、其他技能修飾器照舊。
     */
    private static SkillProfile shardProfile(final Shot shot, final SkillParams.Scatter scatter) {
        final SkillProfile p = shot.profile;
        final List<tw.exilecore.stats.Modifier> own = shot.build.definition().modifiers();
        final List<tw.exilecore.stats.Modifier> local = new ArrayList<>();
        for (final tw.exilecore.stats.Modifier modifier : p.local()) {
            if (!(modifier.stat().equals(tw.exilecore.stats.StatKeys.FREEZE_BUILDUP) && own.contains(modifier))) {
                local.add(modifier);
            }
        }
        return new SkillProfile(p.id(), p.tags(), scatter.effectiveness(), p.spellDamage(), p.spellDamageMax(),
                scatter.convert().isEmpty() ? p.conversion() : scatter.convert(), local);
    }

    /** 分裂的投射物最遠找多遠的敵人。 */
    static final double SPLIT_RANGE = 10;

    /**
     * 2026-10-26 照 PoE2 的 Split（魔湧強化的法術）：第一次打中敵人時，往附近 n 個其他的敵人各射出一發（同樣的傷害；
     * 分裂出來的不會再分裂、不打剛打中的那隻）。n = 技能與身上的 {@link ManaTempest#SPLIT}（看技能的標籤，攻擊吃不到 spell 的）。
     */
    private void split(final Shot shot, final Player owner, final LivingEntity target, final tw.exilecore.stats.StatSheet ownerSheet) {
        shot.split = true;
        // 2026-10-28 照 PoE2（纏蝕箭）：分裂目標數＋（投射物數量改加在分裂上的技能）多的投射物、擲到的多射；品質的小數照原作捨去，
        // 最多 GrantedSkillKeys.MAX_SPLIT；這次之前四捨五入、最多 5
        final tw.exilecore.stats.StatSheet effective = shot.profile.effective(ownerSheet);
        final int count = tw.exilecore.stats.GrantedSkillKeys.splitTargets(effective.flat(ManaTempest.SPLIT, shot.profile.tags()) + shot.splitBonus,
                effective.flat(tw.exilecore.stats.StatKeys.SKILL_PROJECTILES, shot.profile.tags()),
                tw.exilecore.stats.StatKeys.flag(effective, tw.exilecore.stats.GrantedSkillKeys.FLAG_PROJECTILES_TO_SPLIT));
        if (count <= 0 || shot.build.params().blink()) {
            return;
        }
        final Set<UUID> exclude = new HashSet<>(shot.hit);
        final Vector from = SkillTargets.center(target).toVector();
        for (int i = 0; i < count; i++) {
            final LivingEntity next = targets.nearest(target, SPLIT_RANGE, exclude, owner);
            if (next == null) {
                break;
            }
            exclude.add(next.getUniqueId());
            final Vector direction = SkillTargets.center(next).toVector().subtract(from);
            if (direction.lengthSquared() < 1e-6) {
                continue;
            }
            direction.normalize();
            final Shot piece = spawn(owner, shot.build, shot.profile, shot.world, from.clone().add(direction.clone().multiply(0.6)), direction,
                    shot.speed, SPLIT_RANGE + 2, shot.hit, true, shot.visual());
            if (piece != null) {
                piece.struck.addAll(shot.struck);
            }
        }
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
                tw.exilecore.core.Teleports.go(owner, to);
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

    /**
     * 2026-10-27（噬異爆對霜鑽施放，照 PoE2 Snap）：準心射線上（從 eye 往 direction，range 格內、離射線 tolerance 格內）
     * 最近的一發自己的某技能投射物的位置；consume 時把那一發移除（能量被用掉）。沒有回傳 null。
     */
    Location aimedShot(final UUID owner, final String skillId, final Location eye, final Vector direction, final double range,
                       final double tolerance, final boolean consume) {
        Shot best = null;
        double bestAlong = Double.MAX_VALUE;
        final Vector from = eye.toVector();
        for (final Shot shot : shots.values()) {
            if (!shot.owner.equals(owner) || !shot.build.definition().id().equals(skillId) || !shot.world.equals(eye.getWorld())) {
                continue;
            }
            final Vector offset = shot.position.clone().subtract(from);
            final double along = offset.dot(direction);
            if (along < 0 || along > range || offset.lengthSquared() - along * along > tolerance * tolerance) {
                continue;
            }
            if (along < bestAlong) {
                bestAlong = along;
                best = shot;
            }
        }
        if (best == null) {
            return null;
        }
        final Location at = best.position.toLocation(best.world);
        if (consume) {
            remove(best);
        }
        return at;
    }

    int active(final UUID owner) {
        return counts.getOrDefault(owner, 0);
    }
}
