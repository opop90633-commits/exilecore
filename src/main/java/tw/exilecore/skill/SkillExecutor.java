package tw.exilecore.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 各種運作方式的實作：找目標、打下去、畫外觀。投射物交給 {@link ProjectileEngine}，
 * 留在地上、延遲發生的交給 {@link SkillZones}。技能從一個 {@link Origin} 放出來（玩家自己，或之後的圖騰、陷阱）。
 */
final class SkillExecutor {

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final ProjectileEngine projectiles;
    private final SkillZones zones;
    private final SkillDamage damage;

    SkillExecutor(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final ProjectileEngine projectiles,
                  final SkillZones zones, final SkillDamage damage) {
        this.plugin = plugin;
        this.targets = targets;
        this.visuals = visuals;
        this.projectiles = projectiles;
        this.zones = zones;
        this.damage = damage;
    }

    /** 花魔力之前的檢查；可以放回傳 null，不行回傳原因。 */
    String precheck(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (Snap.is(build)) {
            return snap().problem(player, build, preferred); // 2026-10-27 照 PoE2 Snap：要對準異常中的敵人或霜鑽
        }
        switch (build.definition().mechanic()) {
            case LEAP -> {
                if (landing(player, build.range()) == null) {
                    return "前方沒有可以落腳的地方";
                }
            }
            case DASH -> {
                if (hasFlag(build.definition(), StatKeys.FLAG_LIGHTNING_WARP)) { // 2026-10-26：雷遁要瞄準敵人
                    return targets.aimed(player, build.range(), 1.5) == null ? "準心沒有指著敵人" : null;
                }
                if (dashDestination(player, build.range()) == null) {
                    return "前方沒有空間";
                }
            }
            case MARK -> {
                if (!build.params().mark().curse() && markTarget(player, build, preferred) == null) {
                    return "準心沒有指著敵人";
                }
            }
            case CORPSE -> {
                if (corpseNear(Origin.of(player), build) == null) {
                    return "附近沒有屍體";
                }
            }
            case FLICKER -> {
                return flickerCheck(player, build, preferred);
            }
            default -> {
            }
        }
        return null;
    }

    /** 放出技能。跳躍會回傳滯空的 tick 數（其他回傳 0），期間不能做別的事。 */
    int execute(final Player player, final SkillRuntime runtime, final SkillBuild build, final LivingEntity preferred,
                final long now) {
        final Origin origin = Origin.of(player);
        switch (build.definition().mechanic()) {
            case STRIKE -> strike(origin, build, preferred);
            case PROJECTILE -> projectiles.launch(origin, build);
            case NOVA -> nova(origin, build);
            case CONE -> {
                cone(origin, build);
                extraFissure(origin, build); // 2026-10-27 照 PoE2 分裂地面
                if (Unearth.is(build)) {
                    unearth().raise(origin, build); // 2026-10-27 照 PoE2 Unearth：範圍內的屍體變骨構體
                }
            }
            case CHAIN -> chain(origin, build, preferred);
            case LEAP -> {
                return leap(player, runtime, build, now);
            }
            case DASH -> {
                if (hasFlag(build.definition(), StatKeys.FLAG_LIGHTNING_WARP)) {
                    warp(player, build);
                } else {
                    dash(player, build);
                }
            }
            case IMPACT -> impactOrSnap(origin, build, preferred);
            case ZONE -> {
                zone(origin, build);
                extraFissure(origin, build); // 2026-10-27 照 PoE2 分裂地面
            }
            case MARK -> mark(player, build, preferred);
            case CORPSE -> detonate(origin, build);
            case FLICKER -> flicker(player, build, preferred);
            default -> throw new IllegalStateException(build.definition().mechanic().name());
        }
        return 0;
    }

    /** 從別的地方放（圖騰、陷阱、雙詠）：不含跳躍、位移、瞬影、印記這些只有玩家本人能做的。 */
    void executeFrom(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        switch (build.definition().mechanic()) {
            case STRIKE -> strike(origin, build, preferred);
            case PROJECTILE -> projectiles.launch(origin, build);
            case NOVA -> nova(origin, build);
            case CONE -> {
                cone(origin, build);
                extraFissure(origin, build); // 2026-10-27 照 PoE2 分裂地面
            }
            case CHAIN -> chain(origin, build, preferred);
            case IMPACT -> impactOrSnap(origin, build, preferred);
            case ZONE -> {
                zone(origin, build);
                extraFissure(origin, build); // 2026-10-27 照 PoE2 分裂地面
            }
            case CORPSE -> detonate(origin, build);
            case MARK -> { // 2026-10：輔助觸發的減益（殘肢、目眩、曝露）：範圍詛咒落在目標身上，沒有目標就在原點
                final SkillParams.Mark spec = build.params().mark();
                if (spec.curse() && origin.owner() != null) {
                    curseAt(origin.owner(), build, preferred != null ? preferred.getLocation() : origin.feet(), spec);
                }
            }
            default -> {
            }
        }
    }

    // ---- 近戰打擊 ----

    /** 近戰擴散打到目標周圍幾格（再乘範圍效果）。 */
    static final double MELEE_SPLASH_RADIUS = 2.5;
    /** 打擊技能的額外目標最多幾個。 */
    static final int MAX_STRIKE_TARGETS = 6;

    /** 打擊技能多打幾個（只有 strike 標籤、原本只打一個的；照屬性表）。 */
    static int extraStrikeTargets(final StatSheet sheet, final SkillBuild build) {
        if (build.targets() != 1 || !build.profile().tags().contains(tw.exilecore.stats.Tags.STRIKE)) {
            return 0;
        }
        final double extra = build.profile().effective(sheet).flat(StatKeys.STRIKE_TARGETS, build.profile().tags());
        return Math.max(0, Math.min(MAX_STRIKE_TARGETS, (int) Math.round(extra)));
    }

    /** 近戰擴散：周圍的敵人受到這一下幾 %（只有近戰的打擊）。 */
    static double meleeSplash(final StatSheet sheet, final SkillBuild build) {
        if (!build.profile().tags().contains(tw.exilecore.stats.Tags.MELEE)) {
            return 0;
        }
        return Math.max(0, Math.min(200, build.profile().effective(sheet).flat(StatKeys.MELEE_SPLASH, build.profile().tags())));
    }

    private void strike(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        final double reach = build.reach();
        final Location from = origin.feet();
        final List<LivingEntity> hits = new ArrayList<>();
        final StatSheet ownerSheet = origin.owner() == null ? null : plugin.players().sheet(origin.owner());
        final int extraTargets = ownerSheet == null ? 0 : extraStrikeTargets(ownerSheet, build);
        if (build.targets() == 1) {
            LivingEntity target = null;
            if (preferred != null && targets.valid(preferred, origin.owner())
                    && preferred.getLocation().distanceSquared(from) <= Math.pow(reach + 1.5, 2)) {
                target = preferred;
            }
            if (target == null) {
                target = targets.aimed(origin, reach, 0.6);
            }
            if (target == null) {
                final List<LivingEntity> cone = targets.cone(origin, reach, build.angle());
                target = cone.isEmpty() ? null : cone.get(0);
            }
            if (target != null) {
                hits.add(target);
                if (extraTargets > 0) {
                    // 額外目標：先找扇形裡的，不夠再找第一個目標附近的
                    for (final LivingEntity other : targets.cone(origin, reach + 1, Math.max(build.angle(), 120))) {
                        if (hits.size() > extraTargets) {
                            break;
                        }
                        if (!hits.contains(other)) {
                            hits.add(other);
                        }
                    }
                    for (final LivingEntity other : targets.around(target.getLocation(), 3.0, origin.owner())) {
                        if (hits.size() > extraTargets) {
                            break;
                        }
                        if (!hits.contains(other)) {
                            hits.add(other);
                        }
                    }
                }
            }
        } else {
            hits.addAll(targets.cone(origin, reach, build.angle()));
            // 2026-10 手感：準心指到的敵人一定打得到（跟單一目標的刺擊一樣有輔助瞄準）；以前多目標的只看扇形，準心指著但偏出扇形的打不到
            final LivingEntity aimed = preferred != null && targets.valid(preferred, origin.owner())
                    && preferred.getLocation().distanceSquared(from) <= Math.pow(reach + 1.5, 2) ? preferred : targets.aimed(origin, reach, 0.6);
            if (aimed != null) {
                hits.remove(aimed);
                hits.add(0, aimed);
            }
            if (build.targets() > 0 && hits.size() > build.targets()) {
                hits.subList(build.targets(), hits.size()).clear();
            }
        }
        final SkillParams.Execute execute = build.params().execute();
        for (final LivingEntity target : hits) {
            SkillProfile profile = build.profile();
            boolean executed = false;
            if (execute != null && lifeFraction(target) < execute.below()) {
                profile = profile.scaled(1 + execute.more() / 100.0, "處決");
                executed = true;
            }
            damage.hit(origin.owner(), target, profile, knockback(from, target, build.knockback()));
            if (executed) {
                visuals.execute(SkillTargets.center(target));
            }
        }
        // 近戰擴散：每個打中的目標周圍的其他敵人也挨一下（打到的不重複）
        double splash = ownerSheet == null || hits.isEmpty() ? 0 : meleeSplash(ownerSheet, build);
        if (ownerSheet != null && !hits.isEmpty() && splash < tw.exilecore.stats.KeystoneOriginKeys.SPLASH_ON_PROC
                && build.profile().tags().contains(tw.exilecore.stats.Tags.MELEE)) {
            // 2026-10-27 照 PoE2 劈裂大地：「錘的打擊有 x% 機率造成擴散傷害」，擲中就照擴散輔助的 60% 擴散；這次之前平衡等效成一律 6% 擴散
            final double chance = build.profile().effective(ownerSheet).flat(tw.exilecore.stats.KeystoneOriginKeys.SPLASH_CHANCE, build.profile().tags());
            if (chance > 0 && plugin.random().nextDouble() * 100 < chance) {
                splash = tw.exilecore.stats.KeystoneOriginKeys.SPLASH_ON_PROC;
            }
        }
        if (splash > 0) {
            final SkillProfile splashProfile = build.profile().scaled(splash / 100.0, "近戰擴散");
            final double radius = MELEE_SPLASH_RADIUS * build.areaScale();
            final java.util.Set<LivingEntity> splashed = new java.util.HashSet<>(hits);
            for (final LivingEntity target : List.copyOf(hits)) {
                for (final LivingEntity other : targets.around(target.getLocation(), radius, origin.owner())) {
                    if (splashed.add(other)) {
                        damage.hit(origin.owner(), other, splashProfile, null);
                    }
                }
                tw.exilecore.skill.Fx.spawn(target.getWorld(), org.bukkit.Particle.SWEEP_ATTACK, SkillTargets.center(target), 2, radius * 0.3, 0.2, radius * 0.3, 0);
            }
        }
        // 2026-10-25：揮到的範圍裡的鐘、冰晶直接被打到（不用旁邊有敵人）
        targets.strike(origin.owner(), build.profile(), SkillTargets.coneShape(origin, reach, Math.max(build.angle(), 60)), new Object());
        visuals.strike(build.definition().visual(), origin, hits, reach, build.angle());
    }

    /** 目標還剩幾成生命（原版血量是等比例縮放的，直接看）。 */
    static double lifeFraction(final LivingEntity target) {
        final org.bukkit.attribute.AttributeInstance max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double top = max == null ? 20 : max.getValue();
        return top <= 0 ? 1 : target.getHealth() / top;
    }

    private static Vector knockback(final Location from, final LivingEntity target, final double strength) {
        if (strength <= 0) {
            return null;
        }
        final Vector direction = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (direction.lengthSquared() < 1e-6) {
            direction.setX(1);
        }
        return direction.normalize().multiply(strength).setY(0.3);
    }

    // ---- 範圍 ----

    private void nova(final Origin origin, final SkillBuild build) {
        final double radius = build.radius();
        Location center = origin.feet();
        // 冰新星（2026-10 PoE2）：自己的霜鑽還在飛就從那裡爆開
        if (origin.self() && hasFlag(build.definition(), tw.exilecore.stats.StatKeys.FLAG_NOVA_FROM_FROSTBOLT)) {
            final Location bolt = projectiles.latest(origin.owner().getUniqueId(), "frostbolt", center, 30);
            if (bolt != null) {
                center = bolt;
            }
        }
        for (final LivingEntity target : targets.around(center, radius, origin.owner())) {
            if (origin.canSee(target)) {
                damage.hit(origin.owner(), target, build.profile(), null);
            }
        }
        targets.strike(origin.owner(), build.profile(), SkillTargets.sphere(center, radius), new Object()); // 2026-10-25
        visuals.nova(build.definition().visual(), center, radius);
        final SkillParams.Zone ground = build.params().ground();
        if (ground != null) {
            zones.addCircle(origin.owner(), center, build.scaledRadius(ground.radius()), build.profile().scaled(ground.damage() / 100.0, "地面效果"),
                    build.definition().visual(), build.durationTicks(ground.duration()), ground.interval(), ground.pull());
        }
        final SkillParams.Aftershock aftershock = build.params().aftershock();
        if (aftershock != null) {
            // 2026-10-26：餘震的半徑、傷害照等級（PoE2 地動逐級）；天賦「你親自使用的猛擊的餘震範圍增加」
            final double shockArea = origin.self() && build.definition().tags().contains(tw.exilecore.stats.Tags.SLAM)
                    ? TreeMechanics.aftershockRadius(build.profile().effective(plugin.players().sheet(origin.owner())), build.profile().tags()) : 1.0;
            zones.addImpact(origin.owner(), center, radius * aftershock.radius(build.level()) * shockArea,
                    build.profile().scaled(1 + aftershock.more(build.level()) / 100.0, "餘震"),
                    build.definition().visual(), (int) Math.round(aftershock.delay() * 20));
        }
        if (origin.self()) {
            uniqueAftershocks(origin.owner(), build, center, radius);
        }
    }

    /** 2026-10-26（鑄父之錘，PoE2 Hrimnor's Hymn）：你自己使用的猛擊技能多造成幾次餘震（同樣的範圍與傷害，間隔 0.5 秒）。 */
    private void uniqueAftershocks(final Player owner, final SkillBuild build, final Location center, final double radius) {
        if (owner == null || !build.definition().tags().contains(tw.exilecore.stats.Tags.SLAM)) {
            return;
        }
        final tw.exilecore.stats.StatSheet sheet = build.profile().effective(plugin.players().sheet(owner));
        // 2026-10-26 照 PoE2 天賦：「猛擊 x% 機率多造成一次餘震」（錘、變身限定的帶標籤；兩套鍵都算）、「餘震範圍增加」
        final int extra = (int) Math.round(plugin.players().sheet(owner).flat(tw.exilecore.item.unique.UniqueStats.SLAM_AFTERSHOCK, java.util.Set.of()))
                + TreeMechanics.extraAftershocks(sheet, build.profile().tags(), plugin.random())
                + tw.exilecore.stats.TreeEffectKeys.fromChance(sheet.flat(tw.exilecore.stats.TreeEffectKeys.SLAM_AFTERSHOCK_CHANCE,
                        build.profile().tags()), plugin.random());
        final double shockRadius = radius * TreeMechanics.aftershockRadius(sheet, build.profile().tags());
        for (int i = 1; i <= Math.min(3, extra); i++) {
            zones.addImpact(owner, center, shockRadius, build.profile().scaled(1.0, "餘震"), build.definition().visual(), 10 * i);
        }
    }

    static boolean hasFlag(final SkillDefinition definition, final String flag) {
        for (final tw.exilecore.stats.Modifier modifier : definition.modifiers()) {
            if (modifier.stat().equals(flag) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    private void cone(final Origin origin, final SkillBuild build) {
        final double length = build.length();
        for (final LivingEntity target : targets.cone(origin, length, build.angle())) {
            damage.hit(origin.owner(), target, build.profile(), null);
        }
        targets.strike(origin.owner(), build.profile(), SkillTargets.coneShape(origin, length, build.angle()), new Object()); // 2026-10-25
        visuals.cone(build.definition().visual(), origin, length, build.angle());
        final SkillParams.Aftershock aftershock = build.params().aftershock();
        if (aftershock != null) {
            final org.bukkit.util.Vector facing = origin.facing().clone().setY(0);
            if (facing.lengthSquared() > 1.0E-6) {
                facing.normalize();
            }
            final Location center = origin.feet().clone().add(facing.multiply(length * 0.75));
            zones.addImpact(origin.owner(), center, length * aftershock.radius(build.level()),
                    build.profile().scaled(1 + aftershock.more(build.level()) / 100.0, "第二擊"),
                    build.definition().visual(), (int) Math.round(aftershock.delay() * 20));
        }
    }

    // ---- 連鎖 ----

    private void chain(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        final double range = build.range();
        final Location eye = origin.eye();
        LivingEntity current = null;
        if (preferred != null && targets.valid(preferred, origin.owner()) && preferred.getLocation().distance(origin.feet()) <= range) {
            current = preferred;
        }
        if (current == null) {
            current = targets.aimed(origin, range, 1.0);
        }
        if (current == null) {
            final List<LivingEntity> cone = targets.cone(origin, range, 40);
            current = cone.isEmpty() ? null : cone.get(0);
        }
        final List<Location> points = new ArrayList<>();
        points.add(eye.clone().add(origin.direction().clone().multiply(0.6)).subtract(0, 0.3, 0));
        // 2026-10-28 照 PoE2 Lightning Rod：閃電的連鎖可以跳到自己插在地上的雷樁矢箭上（算一次連鎖、箭充能後再炸一次）
        final boolean rods = origin.owner() != null && SkillZones.chainsToRods(build.profile());
        LightningRods.Rod rod = null;
        if (current == null && rods) {
            rod = zones.aimedRod(origin.owner(), eye, origin.direction(), range, 40);
        }
        if (current == null && rod == null) {
            // 沒有目標：往前劈一道空的閃電
            final RayTraceResult ray = origin.world().rayTraceBlocks(eye, origin.direction(), range, FluidCollisionMode.NEVER, true);
            final Vector end = ray == null ? eye.toVector().add(origin.direction().clone().multiply(range)) : ray.getHitPosition();
            points.add(end.toLocation(origin.world()));
            visuals.chain(points);
            return;
        }
        final Set<UUID> hit = new HashSet<>();
        // 2026-10-26 照 PoE2 天賦樹：「閃電技能 x% 機率多連鎖一次」
        final int chains = build.chains() + (origin.owner() == null || build.chains() <= 0 ? 0 : tw.exilecore.stats.TreeEffectKeys.fromChance(
                build.profile().effective(plugin.players().sheet(origin.owner())).flat(tw.exilecore.stats.TreeEffectKeys.CHAIN_CHANCE, build.profile().tags()),
                plugin.random()));
        final Set<LightningRods.Rod> rodsHit = new HashSet<>();
        for (int i = 0; i <= chains && (current != null || rod != null); i++) {
            final Location from;
            if (current != null) {
                from = SkillTargets.center(current);
                hit.add(current.getUniqueId());
                damage.hit(origin.owner(), current, build.profile(), null);
            } else {
                from = SkillZones.rodTop(rod.at.toLocation(origin.world()));
                rodsHit.add(rod);
                zones.chainToRod(origin.owner(), rod, build.profile());
            }
            points.add(from);
            if (i >= chains) {
                break;
            }
            final LivingEntity next = current != null ? targets.nearest(current, build.chainRange(), hit, origin.owner())
                    : targets.nearest(from, build.chainRange(), hit, origin.owner());
            final LightningRods.Rod nextRod = rods ? zones.nearestRod(origin.owner(), from, build.chainRange(), rodsHit) : null;
            // 近的先：敵人跟箭一樣近時打敵人
            if (nextRod != null && (next == null
                    || SkillZones.rodTop(nextRod.at.toLocation(origin.world())).distanceSquared(from) < SkillTargets.center(next).distanceSquared(from))) {
                current = null;
                rod = nextRod;
            } else {
                current = next;
                rod = null;
            }
        }
        visuals.chain(points);
    }

    // ---- 跳躍 ----

    private int leap(final Player player, final SkillRuntime runtime, final SkillBuild build, final long now) {
        final Location target = landing(player, build.range());
        if (target == null) {
            return 0;
        }
        final Location from = player.getLocation();
        final double dx = target.getX() - from.getX();
        final double dy = target.getY() - from.getY();
        final double dz = target.getZ() - from.getZ();
        final int ticks = SkillMath.leapTicks(Math.sqrt(dx * dx + dz * dz));
        final double[] velocity = SkillMath.leapVelocity(dx, dy, dz, ticks, SkillService.grounded(player));
        player.setFallDistance(0);
        player.setVelocity(new Vector(velocity[0], velocity[1], velocity[2]));
        runtime.leap = new SkillRuntime.Leap(now, ticks, build, target);
        visuals.leapStart(player);
        return ticks;
    }

    /** 跳躍落地：打周圍一圈。 */
    void land(final Player player, final SkillRuntime.Leap leap) {
        final double radius = leap.build().radius();
        final Location at = player.getLocation();
        for (final LivingEntity target : targets.around(at, radius, player)) {
            if (player.hasLineOfSight(target)) {
                damage.hit(player, target, leap.build().profile(), null);
            }
        }
        targets.strike(player, leap.build().profile(), SkillTargets.sphere(at, radius), new Object()); // 2026-10-25
        player.setFallDistance(0);
        visuals.leapLand(at, radius);
        uniqueAftershocks(player, leap.build(), at, radius); // 2026-10-26（鑄父之錘）
        if (plugin.skills() != null) {
            plugin.skills().supportMechanics().lineage().landed(player, leap.build(), at); // 2026-10-27 照 PoE2 燼躍：落地引爆屍體
        }
    }

    /**
     * 跳躍的落點：視線打到方塊就落在它前面，沒打到就往面向的方向跳 range 格；
     * 再從那裡往上 2 格、往下 6 格找站得住的地方。都沒有（例如前面是虛空）回傳 null。
     */
    Location landing(final Player player, final double range) {
        final Location eye = player.getEyeLocation();
        final Vector direction = eye.getDirection();
        final World world = player.getWorld();
        final RayTraceResult ray = world.rayTraceBlocks(eye, direction, range, FluidCollisionMode.NEVER, true);
        final Vector point;
        if (ray != null) {
            point = ray.getHitPosition().subtract(direction.clone().multiply(0.4));
        } else {
            point = player.getLocation().toVector().add(SkillTargets.facing(player).multiply(range));
        }
        final int x = point.getBlockX();
        final int z = point.getBlockZ();
        final int baseY = (int) Math.floor(point.getY());
        for (int dy = 2; dy >= -6; dy--) {
            final int y = baseY + dy;
            if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
                continue;
            }
            if (standable(world, x, y, z)) {
                return new Location(world, x + 0.5, y, z + 0.5, player.getLocation().getYaw(), player.getLocation().getPitch());
            }
        }
        return null;
    }

    private static boolean standable(final World world, final int x, final int y, final int z) {
        final Block feet = world.getBlockAt(x, y, z);
        final Block below = feet.getRelative(0, -1, 0);
        final Block head = feet.getRelative(0, 1, 0);
        return below.getType().isSolid() && !Tag.LEAVES.isTagged(below.getType())
                && feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid();
    }

    // ---- 位移 ----

    /** 位移的落點（沿著水平的面向走到被擋住為止）；走不出去回傳 null。 */
    Location dashDestination(final Player player, final double range) {
        final Location from = player.getLocation();
        final World world = player.getWorld();
        final Vector facing = SkillTargets.facing(player);
        final DashPath.Blocks blocks = new DashPath.Blocks() {
            @Override
            public boolean passable(final int x, final int y, final int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return false;
                }
                final Block block = world.getBlockAt(x, y, z);
                return block.isPassable() && !block.isLiquid();
            }

            @Override
            public boolean solid(final int x, final int y, final int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return false;
                }
                final Block block = world.getBlockAt(x, y, z);
                return block.getType().isSolid() && !Tag.LEAVES.isTagged(block.getType());
            }
        };
        final double[] end = DashPath.destination(from.getX(), from.getY(), from.getZ(), facing.getX(), facing.getZ(), range, blocks);
        if (end == null) {
            return null;
        }
        final Location to = new Location(world, end[0], end[1], end[2], from.getYaw(), from.getPitch());
        return to.distanceSquared(from) < 1.0 ? null : to;
    }

    private void dash(final Player player, final SkillBuild build) {
        final Location to = dashDestination(player, build.range());
        if (to == null) {
            return;
        }
        final Location from = player.getLocation();
        final SkillParams.Dash params = build.params().dash();
        final String visual = build.definition().visual();
        final Object strikeSource = new Object(); // 2026-10-25：起點、順路、終點同一下（鐘、冰晶只算一次）
        // 起點爆開
        final double startRadius = build.scaledRadius(params.startRadius());
        if (startRadius > 0) {
            targets.strike(player, build.profile(), SkillTargets.sphere(from, startRadius), strikeSource);
            for (final LivingEntity target : targets.around(from, startRadius, player)) {
                damage.hit(player, target, build.profile(), null);
            }
            visuals.nova(visual, from, startRadius);
        }
        // 順路打擊
        if (params.hitWidth() > 0) {
            targets.strike(player, build.profile(), SkillTargets.segment(from, to, params.hitWidth()), strikeSource);
            for (final LivingEntity target : targets.alongLine(from, to, params.hitWidth(), player)) {
                damage.hit(player, target, build.profile(), knockback(from, target, build.knockback()));
            }
        }
        // 沿路的地面效果
        final SkillParams.Zone ground = build.params().ground();
        if (ground != null) {
            final List<Location> points = new ArrayList<>();
            final double distance = from.distance(to);
            final int steps = Math.max(1, (int) Math.round(distance / 1.2));
            for (int i = 0; i <= steps; i++) {
                final Location point = from.clone().add(to.clone().subtract(from).toVector().multiply(i / (double) steps));
                point.setY(Math.floor(point.getY()) + 0.1);
                points.add(point);
            }
            zones.addPoints(player, points, build.scaledRadius(ground.radius()), build.profile().scaled(ground.damage() / 100.0, "地面效果"),
                    visual, build.durationTicks(ground.duration()), ground.interval(), ground.pull());
        }
        visuals.dash(visual, from, to);
        player.setFallDistance(0);
        tw.exilecore.core.Teleports.go(player, to);
        // 終點爆開
        final double endRadius = build.scaledRadius(params.endRadius());
        if (endRadius > 0) {
            targets.strike(player, build.profile(), SkillTargets.sphere(to, endRadius), strikeSource);
            for (final LivingEntity target : targets.around(to, endRadius, player)) {
                damage.hit(player, target, build.profile(), null);
            }
            visuals.nova(visual, to, endRadius);
        }
    }

    /**
     * 2026-10-26 照 PoE2 的引導衝鋒（盾衝撞上敵人、蠻衝一路踏地）：在 at 炸一圈（照 profile 的傷害），回傳打到的敵人。
     */
    List<LivingEntity> burst(final Player player, final SkillProfile profile, final Location at, final double radius, final double knockback,
                             final String visual) {
        targets.strike(player, profile, SkillTargets.sphere(at, radius), new Object());
        final List<LivingEntity> hit = targets.around(at, radius, player);
        for (final LivingEntity target : hit) {
            damage.hit(player, target, profile, knockback(at, target, knockback));
        }
        visuals.nova(visual, at, radius);
        return hit;
    }

    /** 2026-10-26：只打這一隻（盾衝撞上的那一隻另外挨的一下）。 */
    void hitOne(final Player player, final SkillProfile profile, final LivingEntity target, final Location from, final double knockback) {
        damage.hit(player, target, profile, knockback(from, target, knockback));
    }

    /** 蝕咒裂：找詛咒的範圍（PoE2 2.8 格）、一次最多幾個爆炸。 */
    static final double HEXBLAST_SEARCH = 2.8;
    static final int HEXBLAST_MAX = 10;

    /**
     * 2026-10-26 照 PoE2 Hexblast：準心處 2.8 格內每一隻被你詛咒的敵人，拿掉牠的詛咒、在牠身上炸開一圈混沌（技能的半徑、傷害），
     * 一次最多 10 個；沒有詛咒就沒有傷害。回傳炸了幾個。（原作只引爆過了一半時間的詛咒：我們沒記詛咒的開始時間，任何詛咒都算）
     */
    int hexblast(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (plugin.combat() == null) {
            return 0;
        }
        final Origin origin = Origin.of(player);
        final LivingEntity aimed = preferred != null && targets.valid(preferred, player) ? preferred : targets.aimed(origin, build.range(), 1.2);
        final Location center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
        final long now = plugin.getServer().getCurrentTick();
        final double radius = build.radius();
        int blasts = 0;
        for (final LivingEntity cursed : targets.around(center, HEXBLAST_SEARCH * Math.sqrt(Math.max(0.1, build.areaScale())), player)) {
            if (blasts >= HEXBLAST_MAX) {
                break;
            }
            boolean mine = false;
            for (final Debuffs.Debuff debuff : plugin.combat().debuffs().active(cursed.getUniqueId(), now)) {
                if (debuff.id().contains("_curse") && player.getUniqueId().equals(debuff.source())) {
                    mine = true;
                    break;
                }
            }
            if (!mine) {
                continue;
            }
            plugin.combat().debuffs().removeCurses(cursed.getUniqueId());
            blasts++;
            final Location at = cursed.getLocation();
            targets.strike(player, build.profile(), SkillTargets.sphere(at, radius), new Object());
            for (final LivingEntity victim : targets.around(at, radius, player)) {
                damage.hit(player, victim, build.profile(), null);
            }
            visuals.nova(build.definition().visual(), at, radius);
        }
        return blasts;
    }

    /** 稜光裂：脈衝的範圍（PoE2 5 格）、最多用掉幾隻的異常、冰爆與雷爆是火爆的幾倍（PoE2 350% ÷ 125%、140% ÷ 125%）。 */
    static final double SUNDER_PULSE = 5;
    static final int SUNDER_MAX = 20;
    static final double SUNDER_COLD = 2.8;
    static final double SUNDER_LIGHTNING = 1.12;

    /**
     * 2026-10-26 照 PoE2 Elemental Sundering：身邊 5 格的脈衝（不打人）用掉最多 20 隻敵人身上的冰凍、感電、點燃，
     * 每用掉一個在那隻身上炸開那種元素（技能的半徑；火的照技能的表、冰 2.8 倍、雷 1.12 倍，全部轉成那種元素、不會造成異常）。
     * 沒有異常就沒有傷害。回傳炸了幾個。（這次之前是準心處隨機一種元素的爆炸）
     */
    int sunder(final Player player, final SkillBuild build) {
        if (plugin.ailments() == null) {
            return 0;
        }
        final Location center = player.getLocation();
        final double radius = build.radius();
        int blasts = 0;
        int consumed = 0;
        visuals.nova(build.definition().visual(), center, SUNDER_PULSE * Math.sqrt(Math.max(0.1, build.areaScale())));
        for (final LivingEntity enemy : targets.around(center, SUNDER_PULSE * Math.sqrt(Math.max(0.1, build.areaScale())), player)) {
            if (consumed >= SUNDER_MAX) {
                break;
            }
            boolean any = false;
            for (final tw.exilecore.ailment.AilmentType type : new tw.exilecore.ailment.AilmentType[] {
                    tw.exilecore.ailment.AilmentType.IGNITE, tw.exilecore.ailment.AilmentType.FREEZE, tw.exilecore.ailment.AilmentType.SHOCK}) {
                if (!plugin.ailments().has(enemy, type)) {
                    continue;
                }
                plugin.ailments().consume(enemy, type, player); // 2026-10-27 無限循環：感電可能重新施加
                any = true;
                blasts++;
                final tw.exilecore.stats.DamageType element = switch (type) {
                    case FREEZE -> tw.exilecore.stats.DamageType.COLD;
                    case SHOCK -> tw.exilecore.stats.DamageType.LIGHTNING;
                    default -> tw.exilecore.stats.DamageType.FIRE;
                };
                final double scale = type == tw.exilecore.ailment.AilmentType.FREEZE ? SUNDER_COLD
                        : type == tw.exilecore.ailment.AilmentType.SHOCK ? SUNDER_LIGHTNING : 1.0;
                final SkillProfile base = build.profile();
                final SkillProfile blast = new SkillProfile(base.id() + "." + element.name().toLowerCase(java.util.Locale.ROOT), base.tags(),
                        base.effectiveness(), base.spellDamage(), base.spellDamageMax(), java.util.Map.of(element, 1.0), base.local())
                        .scaled(scale, build.definition().name())
                        .withLocal(List.of(tw.exilecore.stats.Modifier.more(StatKeys.IGNITE_CHANCE, -1.0, "稜光裂"),
                                tw.exilecore.stats.Modifier.more(StatKeys.FREEZE_BUILDUP, -1.0, "稜光裂"),
                                tw.exilecore.stats.Modifier.more(StatKeys.SHOCK_CHANCE, -1.0, "稜光裂")));
                final Location at = enemy.getLocation();
                for (final LivingEntity victim : targets.around(at, radius, player)) {
                    damage.hit(player, victim, blast, null);
                }
                visuals.nova(build.definition().visual(), at, radius);
            }
            if (any) {
                consumed++;
            }
        }
        return blasts;
    }

    // ---- 瞬影 ----

    /** 瞬影擊要打誰、瞬移到哪裡（已經轉好面向目標）。 */
    record FlickerPlan(LivingEntity target, Location destination) {
    }

    /** 最多看幾個看得到的候選目標（找不到落點才往下看；太多會一直打射線）。 */
    private static final int FLICKER_CANDIDATES = 8;
    /** 找不到目標或落點時，幾 tick 內直接回同一個原因（按住按鍵時不要每 tick 重算一次）。 */
    private static final int FLICKER_MISS_TICKS = 4;
    /** 站上去會出事的方塊：傳送門、火、蜘蛛網、細雪、甜莓叢、凋零玫瑰（液體另外擋）。 */
    private static final Set<org.bukkit.Material> FLICKER_HAZARDS = java.util.EnumSet.of(org.bukkit.Material.NETHER_PORTAL,
            org.bukkit.Material.END_PORTAL, org.bukkit.Material.END_GATEWAY, org.bukkit.Material.FIRE, org.bukkit.Material.SOUL_FIRE,
            org.bukkit.Material.COBWEB, org.bukkit.Material.POWDER_SNOW, org.bukkit.Material.SWEET_BERRY_BUSH,
            org.bukkit.Material.WITHER_ROSE);
    /** 腳下不能是這些（站著會燙）。 */
    private static final Set<org.bukkit.Material> FLICKER_BAD_GROUND = java.util.EnumSet.of(org.bukkit.Material.MAGMA_BLOCK,
            org.bukkit.Material.CAMPFIRE, org.bukkit.Material.SOUL_CAMPFIRE);

    /** 同一個 tick 裡施放前檢查算好的落點（真正施放時直接用，不用再算一次）。 */
    private UUID planFor;
    private long planTick = Long.MIN_VALUE;
    private FlickerPlan plan;
    /** 剛剛找不到的人：到哪個 tick、原因。 */
    private final java.util.Map<UUID, Miss> misses = new java.util.HashMap<>();

    private record Miss(long until, String reason) {
    }

    /** 施放前檢查：找得到目標與落點回傳 null（並記下來給這個 tick 的施放用），不行回傳原因。 */
    private String flickerCheck(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final long now = plugin.getServer().getCurrentTick();
        final Miss miss = misses.get(player.getUniqueId());
        if (miss != null && now < miss.until()) {
            return miss.reason();
        }
        final List<LivingEntity> candidates = flickerCandidates(player, build, preferred);
        final FlickerPlan found = flickerPlan(player, candidates);
        if (found == null) {
            final String reason = candidates.isEmpty()
                    ? String.format(java.util.Locale.ROOT, "%.0f 格內沒有看得到的敵人", build.range())
                    : "敵人旁邊沒有落腳的地方";
            if (misses.size() > 256) {
                misses.values().removeIf(old -> old.until() <= now);
            }
            misses.put(player.getUniqueId(), new Miss(now + FLICKER_MISS_TICKS, reason));
            return reason;
        }
        misses.remove(player.getUniqueId());
        planFor = player.getUniqueId();
        planTick = now;
        plan = found;
        return null;
    }

    /**
     * 瞬影擊的候選目標，照順序：排隊時點到的敵人、準心指的敵人、range 內由近到遠的敵人；
     * 都要看得到（不穿牆），最多 {@link #FLICKER_CANDIDATES} 個（看不到的不算數）。
     */
    List<LivingEntity> flickerCandidates(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final double range = build.range();
        final Location feet = player.getLocation();
        final List<LivingEntity> list = new ArrayList<>();
        if (preferred != null && targets.valid(preferred, player) && preferred.getWorld().equals(feet.getWorld())
                && preferred.getLocation().distanceSquared(feet) <= (range + 1.5) * (range + 1.5) && player.hasLineOfSight(preferred)) {
            list.add(preferred);
        }
        final LivingEntity aimed = targets.aimed(Origin.of(player), range, 0.8);
        if (aimed != null && !list.contains(aimed) && player.hasLineOfSight(aimed)) {
            list.add(aimed);
        }
        for (final LivingEntity near : targets.around(feet, range, player)) {
            if (list.size() >= FLICKER_CANDIDATES) {
                break;
            }
            if (!list.contains(near) && player.hasLineOfSight(near)) {
                list.add(near);
            }
        }
        return list;
    }

    /** 第一個找得到落點的候選目標；都沒有回傳 null。 */
    private FlickerPlan flickerPlan(final Player player, final List<LivingEntity> candidates) {
        for (final LivingEntity target : candidates) {
            final Location spot = flickerSpot(player, target);
            if (spot != null) {
                return new FlickerPlan(target, spot);
            }
        }
        return null;
    }

    /**
     * 目標旁邊的落點（{@link SkillMath#flickerSpots}：面向玩家那一側優先）：腳下有地可以站（踩在那一格的頂面上，
     * 半磚、地毯也行；岩漿塊、營火不行），玩家的碰撞箱放得下（方塊、船、世界邊界都不能擋）、
     * 碰到的格子沒有液體、傳送門、火、蜘蛛網這些，而且從那裡看得到目標（不會隔著牆）。
     * 高度先試目標腳下那層，再上一格、下一格、下兩格。面向轉向目標。
     */
    private Location flickerSpot(final Player player, final LivingEntity target) {
        final World world = target.getWorld();
        final Location at = target.getLocation();
        final org.bukkit.util.BoundingBox box = target.getBoundingBox();
        final double halfWidth = Math.max(box.getWidthX(), box.getWidthZ()) / 2;
        final Location from = player.getLocation();
        final Vector aim = SkillTargets.center(target).toVector();
        final int baseY = (int) Math.floor(at.getY() + 0.01);
        for (final double[] spot : SkillMath.flickerSpots(from.getX(), from.getZ(), at.getX(), at.getZ(), halfWidth)) {
            for (final int dy : new int[] {0, 1, -1, -2}) {
                final int y = baseY + dy;
                if (y - 1 < world.getMinHeight() || y + 2 >= world.getMaxHeight()) {
                    continue;
                }
                final double feet = standHeight(world, spot[0], y, spot[1]);
                if (Double.isNaN(feet)) {
                    continue;
                }
                final Vector eye = new Vector(spot[0], feet + 1.62, spot[1]);
                final Vector look = aim.clone().subtract(eye);
                final double distance = look.length();
                if (distance > 1e-6 && world.rayTraceBlocks(eye.toLocation(world), look.clone().multiply(1 / distance), distance,
                        FluidCollisionMode.NEVER, true) != null) {
                    continue; // 隔著牆（例如目標在一格寬的走道裡，旁邊那格在牆的另一邊）
                }
                final Location to = new Location(world, spot[0], feet, spot[1]);
                if (distance > 1e-6) {
                    to.setDirection(look);
                } else {
                    to.setYaw(from.getYaw());
                    to.setPitch(from.getPitch());
                }
                return to;
            }
        }
        return null;
    }

    /**
     * 玩家站在 (x, ?, z)、腳下是第 y − 1 格時腳底的高度（那一格的頂面：整格是 y、半磚 y − 0.5、地毯 y − 15/16）；
     * 站不了回傳 NaN：沒有地（空氣、草、花、液體、樹葉）、燙腳的地、碰撞箱塞不下、碰到液體或危險的方塊。
     */
    private static double standHeight(final World world, final double x, final int y, final double z) {
        final Block below = world.getBlockAt((int) Math.floor(x), y - 1, (int) Math.floor(z));
        if (below.isPassable() || below.isLiquid() || Tag.LEAVES.isTagged(below.getType()) || FLICKER_BAD_GROUND.contains(below.getType())) {
            return Double.NaN;
        }
        final double feet = below.getBoundingBox().getMaxY();
        if (feet < y - 1 + 1e-3 || feet > y + 1e-3) {
            return Double.NaN; // 比一格高的（柵欄、牆）或怪形狀：不站
        }
        final org.bukkit.util.BoundingBox body = new org.bukkit.util.BoundingBox(x - 0.3, feet + 1e-3, z - 0.3, x + 0.3, feet + 1.8, z + 0.3);
        if (world.hasCollisionsIn(body)) {
            return Double.NaN;
        }
        for (int bx = (int) Math.floor(body.getMinX()); bx <= (int) Math.floor(body.getMaxX()); bx++) {
            for (int bz = (int) Math.floor(body.getMinZ()); bz <= (int) Math.floor(body.getMaxZ()); bz++) {
                for (int by = (int) Math.floor(body.getMinY()); by <= (int) Math.floor(body.getMaxY()); by++) {
                    final Block block = world.getBlockAt(bx, by, bz);
                    if (block.isLiquid() || FLICKER_HAZARDS.contains(block.getType())) {
                        return Double.NaN;
                    }
                }
            }
        }
        return feet;
    }

    /**
     * 複擊的瞬影擊再閃一次（2026-10）：原本的目標還在、看得到就再打它，不然打準心指的或最近的。
     * 找不到敵人或落點回傳 false（剩下的不閃了）。
     */
    boolean flickerAgain(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final FlickerPlan found = flickerPlan(player, flickerCandidates(player, build, preferred));
        if (found == null) {
            return false;
        }
        planFor = player.getUniqueId();
        planTick = plugin.getServer().getCurrentTick();
        plan = found;
        flicker(player, build, preferred);
        return true;
    }

    /**
     * 瞬影擊：瞬移到目標旁邊（已經貼著就不動）、轉向它，打一下（跟近戰打擊一樣，那個目標優先）。
     * 傳送被別的插件擋掉就不打（不然會從原地打空）。
     */
    private void flicker(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final long now = plugin.getServer().getCurrentTick();
        FlickerPlan chosen = player.getUniqueId().equals(planFor) && planTick == now ? plan : null;
        planFor = null;
        plan = null;
        if (chosen == null || !targets.valid(chosen.target(), player)) {
            chosen = flickerPlan(player, flickerCandidates(player, build, preferred));
        }
        if (chosen == null) {
            return;
        }
        final Location from = player.getLocation();
        final Location to = chosen.destination();
        if (to.distanceSquared(from) >= SkillMath.FLICKER_MIN_MOVE * SkillMath.FLICKER_MIN_MOVE) {
            player.setFallDistance(0);
            player.setVelocity(new Vector());
            if (!tw.exilecore.core.Teleports.go(player, to)) {
                return;
            }
            visuals.dash(build.definition().visual(), from, to);
        }
        strike(Origin.of(player), build, chosen.target());
        // 2026-10-24（照 PoE2）：每顆銳星多瞬移打 2 下（優先打還沒打過的），出手速度更多 285%；連打期間不能拿銳星。
        // 銳星是要打下一下時才用（附近沒有敵人就不用掉，照 PoE2「沒有額外攻擊就不消耗」）
        final var charges = plugin.combat() == null ? null : plugin.combat().charges();
        if (charges == null || charges.count(player, "power") <= 0) {
            return;
        }
        final long step = Math.max(2, Math.round(build.intervalTicks(plugin.players().sheet(player)) / (1 + FLICKER_MORE_SPEED)));
        final java.util.Set<UUID> hit = new java.util.HashSet<>();
        hit.add(chosen.target().getUniqueId());
        charges.blockPower(player, now + step * FLICKER_PER_CHARGE + 5);
        flickerChain(player, build, hit, 0, step, 0);
    }

    /** 每顆銳星多瞬移打幾下。 */
    static final int FLICKER_PER_CHARGE = 2;
    /** 連打的出手速度更多（2.85 = 285%）。 */
    static final double FLICKER_MORE_SPEED = 2.85;
    /** 保險：一次瞬影擊最多連打幾下（銳星上限再高也不會無限連）。 */
    static final int FLICKER_MAX_CHAIN = 40;

    /**
     * 瞬影擊的連打：{@code paid} 是用掉的銳星還能打幾下；用完了、身上還有銳星就再用一顆（先確認有敵人可以打）。
     * 每一下之間隔 step tick；連打期間一直延長「不能拿銳星」。
     */
    private void flickerChain(final Player player, final SkillBuild build, final java.util.Set<UUID> hit, final int paid, final long step, final int done) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final var charges = plugin.combat() == null ? null : plugin.combat().charges();
            if (charges == null || !player.isOnline() || player.isDead() || done >= FLICKER_MAX_CHAIN) {
                return;
            }
            final List<LivingEntity> candidates = new ArrayList<>(flickerCandidates(player, build, null));
            candidates.sort((a, b) -> Boolean.compare(hit.contains(a.getUniqueId()), hit.contains(b.getUniqueId()))); // 沒打過的優先
            final FlickerPlan next = flickerPlan(player, candidates);
            if (next == null) {
                return; // 附近沒有敵人：連打結束（銳星沒用掉的留著）
            }
            int left = paid;
            if (left <= 0) {
                // 戰星輔助：恆星不扣、倍星這顆打 4 下；抑星不消耗（就不連打）
                final int benefit = charges.spend(player, "power", build.profile().effective(plugin.players().sheet(player)), build.profile().tags());
                if (benefit <= 0) {
                    return; // 銳星用完了
                }
                left = FLICKER_PER_CHARGE * benefit;
            }
            final Location from = player.getLocation();
            final Location to = next.destination();
            if (to.distanceSquared(from) >= SkillMath.FLICKER_MIN_MOVE * SkillMath.FLICKER_MIN_MOVE) {
                player.setFallDistance(0);
                player.setVelocity(new Vector());
                if (!tw.exilecore.core.Teleports.go(player, to)) {
                    return;
                }
                visuals.dash(build.definition().visual(), from, to);
            }
            hit.add(next.target().getUniqueId());
            strike(Origin.of(player), build, next.target());
            charges.blockPower(player, plugin.getServer().getCurrentTick() + step + 5);
            flickerChain(player, build, hit, left - 1, step, done + 1);
        }, step);
    }

    // ---- 延遲落下、地面效果、印記 ----

    /**
     * 2026-10-26（PoE2 的 Lightning Warp）：瞄準的敵人腳下留 8 秒感電地面；生命已經低到可以撲殺 → 瞬移進去炸開（撲殺它、炸周圍 3 格）；
     * 還沒低到 → 掛 4 秒標記，期間掉到門檻就自動瞬移過去。
     */
    private void warp(final Player player, final SkillBuild build) {
        final LivingEntity target = targets.aimed(player, build.range(), 1.5);
        if (target == null) {
            return;
        }
        if (plugin.skills() != null && plugin.skills().config().skill(WARP_GROUND) != null) {
            plugin.skills().procs().castAt(player, WARP_GROUND, build.definition().id(), target.getLocation());
        }
        if (zones.cullable(target)) {
            zones.warpInto(player, target, build);
        } else {
            zones.markWarp(player, target, build, WARP_MARK_TICKS);
            visuals.cast(build.definition().visual(), target.getLocation().add(0, 1, 0));
        }
    }

    /** 雷遁留下的感電地面（隱藏技能）。 */
    static final String WARP_GROUND = "proc_warp_shocked_ground";
    /** 雷遁標記多久（4 秒）。 */
    static final int WARP_MARK_TICKS = 80;

    /** 2026-10-26：一次射出存著的彈（Hailstorm Rounds）：上次放的 tick（玩家:技能）。 */
    private final java.util.Map<String, Long> rainFired = new java.util.HashMap<>();

    private Snap snap;
    private Unearth unearth;

    /** 2026-10-27 照 PoE2 Unearth（掘骸）：骨刺之後把屍體變骨構體（見 {@link Unearth}）。 */
    private Unearth unearth() {
        if (unearth == null) {
            unearth = new Unearth(plugin);
        }
        return unearth;
    }

    /** 2026-10-27 照 PoE2 Snap（噬異爆）：用掉敵人的異常炸開（見 {@link Snap}）。 */
    private Snap snap() {
        if (snap == null) {
            snap = new Snap(plugin, targets, projectiles, damage);
        }
        return snap;
    }

    private void impactOrSnap(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        if (Snap.is(build)) {
            snap().cast(origin, build, preferred);
            visuals.cast(build.definition().visual(), origin.eye());
        } else {
            impact(origin, build);
        }
    }

    private void impact(final Origin origin, final SkillBuild build) {
        final LivingEntity aimed = targets.aimed(origin, build.range(), 1.0);
        final Location center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
        final int delay = (int) Math.max(2, Math.round(build.params().delay() * 20 / fallSpeed(origin, build)));
        final String id = build.definition().id();
        final SkillConfig config = plugin.skills() == null ? null : plugin.skills().config();
        final SkillConfig.RainSpec rain = config == null ? null : config.rain(id);
        final SkillConfig.LodgeSpec lodge = config == null || origin.owner() == null ? null : config.lodge(id);
        final SkillConfig.RodSpec rod = config == null || origin.owner() == null ? null : config.rod(id);
        if (rod != null) {
            // 2026-10-28 照 PoE2 Lightning Rod（雷樁矢）：每一支箭落下炸開之後插在地上（多的投射物各落一支，平均散在準心周圍）
            final int count = Math.max(1, build.projectiles());
            final double spread = Math.max(1.5, build.radius() * 1.2);
            final double phase = plugin.random().nextDouble() * Math.PI * 2;
            for (int i = 0; i < count; i++) {
                final Location at = center.clone();
                if (i > 0) {
                    final double angle = phase + Math.PI * 2 * (i - 1) / (count - 1);
                    at.add(Math.cos(angle) * spread, 0, Math.sin(angle) * spread);
                }
                zones.addRodImpact(origin.owner(), at, build.radius(), build.profile(), build.definition().visual(), delay, id);
            }
        } else if (rain != null && origin.owner() != null) {
            // 2026-10-26（PoE2 Hailstorm Rounds）：一次射出整個彈匣，散在準心 area 格內陸續落下
            final String key = origin.owner().getUniqueId() + ":" + id;
            final long now = plugin.getServer().getCurrentTick();
            final int cap = Math.max(1, rain.store().intAt(build.level()));
            final Long last = rainFired.put(key, now);
            final int stored = last == null ? cap : (int) Math.min(cap, (now - last) / Math.max(1, Math.round(rain.interval() * 20)));
            final int count = Math.max(1, stored) * Math.max(1, build.projectiles()); // 原作每一發還多射一支（projectiles）
            for (int i = 0; i < count; i++) {
                final double angle = plugin.random().nextDouble() * Math.PI * 2;
                final double distance = Math.sqrt(plugin.random().nextDouble()) * rain.area();
                final Location at = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                final int extra = (int) Math.round(rain.seconds() * 20 * i / Math.max(1, count));
                zones.addImpact(origin.owner(), at, rain.radius() * build.areaScale(), build.profile(), build.definition().visual(), delay + extra);
            }
        } else if (lodge != null) {
            // 2026-10-26（PoE2 伏霆彈、寒凝彈）：一次落下好幾顆，落地後留在地上
            for (int i = 0; i < lodge.count(); i++) {
                final Location at = center.clone();
                if (lodge.count() > 1 && lodge.scatter() > 0) {
                    final double angle = Math.PI * 2 * i / lodge.count() + plugin.random().nextDouble();
                    final double distance = lodge.scatter() * (0.4 + 0.6 * plugin.random().nextDouble());
                    at.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
                }
                zones.addImpact(origin.owner(), at, build.radius(), build.profile(), build.definition().visual(), delay, id, build.level());
            }
        } else {
            // 2026-10-27 照 PoE2（召霆矢這類射到地上再落下的投射物技能）：多的投射物各射一支、各落一道，平均散在準心周圍；
            // 這次之前只落一道（多一發投射物的輔助只有懲罰沒有效果）
            final int count = build.definition().tags().contains(tw.exilecore.stats.Tags.PROJECTILE) ? Math.max(1, build.projectiles()) : 1;
            final double spread = Math.max(1.5, build.radius() * 1.2);
            final double phase = plugin.random().nextDouble() * Math.PI * 2;
            for (int i = 0; i < count; i++) {
                final Location at = center.clone();
                if (i > 0) {
                    final double angle = phase + Math.PI * 2 * (i - 1) / (count - 1);
                    at.add(Math.cos(angle) * spread, 0, Math.sin(angle) * spread);
                }
                zones.addImpact(origin.owner(), at, build.radius(), build.profile(), build.definition().visual(), delay);
            }
        }
        visuals.cast(build.definition().visual(), origin.eye());
    }

    /**
     * 2026-10-24：從天上掉下來的彈（弩台、帶投射物標籤的延遲落下）：投射物速度越快掉得越快（落下的等待時間 ÷ 倍率，0.25～4 倍）。
     * 其他延遲落下的（隕石這種不是投射物的）照原本的時間。
     */
    static boolean fallsAsProjectile(final SkillDefinition definition) {
        return definition.tags().contains(tw.exilecore.stats.Tags.PROJECTILE) || definition.weapons().contains("crossbow");
    }

    private double fallSpeed(final Origin origin, final SkillBuild build) {
        if (origin.owner() == null || !fallsAsProjectile(build.definition())) {
            return 1.0;
        }
        final StatSheet effective = build.profile().effective(plugin.players().sheet(origin.owner()));
        return Math.max(0.25, Math.min(4.0, effective.apply(StatKeys.SKILL_PROJECTILE_SPEED, 1.0, build.profile().tags())));
    }

    /** 2026-10-26 照 PoE2 天賦樹：「裂地／法球技能 +1 上限」（skill.limit，帶 fissure／orb 標籤）：同時存在的上限多幾個。 */
    private int zoneMax(final Origin origin, final SkillBuild build, final int max) {
        if (max <= 0 || origin.owner() == null) {
            return max;
        }
        return max + (int) Math.round(Math.max(0, build.profile().effective(plugin.players().sheet(origin.owner()))
                .flat(tw.exilecore.stats.KeystoneKeys.SKILL_LIMIT, build.profile().tags())));
    }

    private void zone(final Origin origin, final SkillBuild build) {
        final SkillParams.Zone params = build.params().zone();
        final String visual = build.definition().visual();
        final SkillProfile profile = build.profile().scaled(params.damage() / 100.0, "地面效果");
        final double radius = build.radius();
        if (params.line() && origin.owner() != null && StatKeys.flag(build.profile().effective(plugin.players().sheet(origin.owner())),
                tw.exilecore.stats.SupportExtraKeys.FLAG_FORTRESS)) {
            // 2026-10-27 照 PoE2 Fortress（環壁）：牆改成在準心指的地方圍成一圈（牆的長度加倍，圓周 = 兩倍長度）
            final LivingEntity aimed = targets.aimed(origin, Math.max(8, build.range()), 1.0);
            final Location center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, Math.max(8, build.range()));
            final double ring = Math.max(1.5, build.length() * 2 / (2 * Math.PI));
            final int steps = Math.max(6, (int) Math.round(2 * Math.PI * ring / Math.max(0.8, radius)));
            final List<Location> points = new ArrayList<>();
            for (int i = 0; i < steps; i++) {
                final double a = 2 * Math.PI * i / steps;
                points.add(ground(center.clone().add(Math.cos(a) * ring, 0, Math.sin(a) * ring)));
            }
            zones.addPoints(origin.owner(), points, radius, profile, visual, build.durationTicks(params.duration()), params.interval(), params.pull(),
                    build.definition().id(), zoneMax(origin, build, params.max()));
        } else if (params.line()) {
            // 面前橫向的一道（焰幕）：中心在面前 2 格，左右各半條長度
            final Vector facing = origin.facing();
            // 2026-10-27 照 PoE2 裂垣（Ahn's Citadel）：牆改成沿著一道往前的裂縫（從腳下往準心延伸），長度增加 65%
            final double wallLength = origin.owner() == null ? 0 : build.profile().effective(plugin.players().sheet(origin.owner()))
                    .flat(tw.exilecore.stats.LineageKeys.WALL_FISSURE, build.profile().tags());
            final double length = wallLength > 0 ? build.length() * Math.max(0.1, 1 + build.profile().effective(plugin.players().sheet(origin.owner()))
                    .increased(tw.exilecore.stats.LineageKeys.WALL_LENGTH, build.profile().tags())) : build.length();
            final Vector side = wallLength > 0 ? facing.clone().setY(0).normalize() : new Vector(-facing.getZ(), 0, facing.getX());
            final Location center = origin.feet().clone().add(facing.clone().multiply(wallLength > 0 ? 1.0 + length / 2 : 2.0));
            final List<Location> points = new ArrayList<>();
            final int steps = Math.max(1, (int) Math.round(length / Math.max(0.8, radius)));
            for (int i = 0; i <= steps; i++) {
                final double offset = -length / 2 + length * i / steps;
                final Location point = center.clone().add(side.clone().multiply(offset));
                points.add(ground(point));
            }
            zones.addPoints(origin.owner(), points, radius, profile, visual, build.durationTicks(params.duration()), params.interval(), params.pull(),
                    build.definition().id(), zoneMax(origin, build, params.max()));
        } else {
            final Location center;
            if (build.range() <= 0) {
                center = origin.feet().clone();
            } else {
                final LivingEntity aimed = targets.aimed(origin, build.range(), 1.0);
                center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
            }
            zones.addCircle(origin.owner(), center, radius, profile, visual, build.durationTicks(params.duration()), params.interval(), params.pull(),
                    build.definition().id(), zoneMax(origin, build, params.max()));
            DruidSupports.advanceStorm(plugin, zones, origin, build, center); // 2026-10-26 照 PoE2：行嵐、掠嵐讓風暴從腳下走過去
        }
        visuals.cast(visual, origin.eye());
    }

    /**
     * 2026-10-27 照 PoE2 分裂地面：裂地技能（fissure 標籤：炎脈裂地、骨裂地脈）有 x% 機率多一道裂縫，往左或右偏
     * {@link tw.exilecore.stats.KeystoneOriginKeys#EXTRA_FISSURE_ANGLE} 度；這次之前我們的裂地一次只出一道，平衡等效成裂地技能更多 10% 傷害。
     */
    private void extraFissure(final Origin origin, final SkillBuild build) {
        if (origin.owner() == null || !build.profile().tags().contains(tw.exilecore.stats.KeystoneKeys.FISSURE)) {
            return;
        }
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(plugin.players().sheet(origin.owner()));
        final java.util.Set<String> tags = build.profile().tags();
        // 2026-10-27 照 PoE2 Upheaval（多裂開 x 道，左右交替往外偏）、Branching Fissures（分岔 x 道，分岔的傷害、範圍更少）
        final int additional = (int) Math.round(effective.flat(tw.exilecore.stats.SupportExtraKeys.FISSURE_EXTRA, tags));
        for (int i = 1; i <= additional; i++) {
            turnedFissure(origin, build, Math.toRadians(tw.exilecore.stats.KeystoneOriginKeys.EXTRA_FISSURE_ANGLE) * ((i + 1) / 2) * (i % 2 == 0 ? -1 : 1));
        }
        // 2026-10-27 照 PoE2 狂裂（Kaom's Madness）：多裂出 3 道，往兩側輪流散開（每道再偏 15 度）
        final int kaom = (int) Math.round(effective.flat(tw.exilecore.stats.LineageKeys.EXTRA_FISSURES, tags));
        for (int i = 1; i <= Math.min(6, kaom); i++) {
            turnedFissure(origin, build, Math.toRadians(15.0 * ((i + 1) / 2)) * (i % 2 == 1 ? 1 : -1));
        }
        final int branches = (int) Math.round(effective.flat(tw.exilecore.stats.SupportExtraKeys.FISSURE_BRANCHES, tags));
        if (branches > 0) {
            final double less = effective.flat(tw.exilecore.stats.SupportExtraKeys.FISSURE_BRANCH_DAMAGE, tags) / 100.0;
            final double smaller = effective.flat(tw.exilecore.stats.SupportExtraKeys.FISSURE_BRANCH_AREA, tags) / 100.0;
            final SkillBuild branch = build.withProfile(build.profile().scaled(Math.max(0, 1 - less), "分岔裂地"))
                    .withAreaAndProjectiles(build.areaScale() * Math.sqrt(Math.max(0.1, 1 - smaller)), build.projectiles());
            // 2026-10-27 照 PoE2：分岔從主裂縫上岔出去（第 i 道在主裂縫的 i ÷ (x＋1) 處，左右交替偏 40 度）；這次之前從起點往兩旁各裂一道。
            // 扇形的裂縫往前長（沿著面向）；地面的一道（炎脈裂地）是面前橫的一條（沿著左右）
            final Vector along = origin.direction().clone().setY(0);
            final boolean cone = build.definition().mechanic() == Mechanic.CONE;
            final Vector axis = cone ? along : new Vector(-along.getZ(), 0, along.getX());
            for (int i = 1; i <= branches; i++) {
                final double at = cone ? build.length() * branchPoint(i, branches) : build.length() * (branchPoint(i, branches) - 0.5);
                final Vector shift = axis.lengthSquared() < 1e-6 ? new Vector() : axis.clone().normalize().multiply(at);
                final Origin from = new Origin(origin.owner(), origin.feet().clone().add(shift), origin.eye().clone().add(shift), origin.direction(),
                        origin.self());
                turnedFissure(from, branch, Math.toRadians(40) * (i % 2 == 0 ? -1 : 1));
            }
        }
        final double chance = effective.flat(tw.exilecore.stats.KeystoneOriginKeys.EXTRA_FISSURE_CHANCE, tags);
        if (chance <= 0 || plugin.random().nextDouble() * 100 >= chance) {
            return;
        }
        turnedFissure(origin, build, Math.toRadians(tw.exilecore.stats.KeystoneOriginKeys.EXTRA_FISSURE_ANGLE) * (plugin.random().nextBoolean() ? 1 : -1));
    }

    /** Branching Fissures：第 i 道（共 total 道）分岔從主裂縫的哪裡（長度的比例）岔出去。 */
    static double branchPoint(final int i, final int total) {
        return i / (double) (Math.max(1, total) + 1);
    }

    /** 往旁邊偏 angle（弧度）再裂一道。 */
    private void turnedFissure(final Origin origin, final SkillBuild build, final double angle) {
        final Vector dir = origin.direction().clone();
        final double x = dir.getX() * Math.cos(angle) - dir.getZ() * Math.sin(angle);
        final double z = dir.getX() * Math.sin(angle) + dir.getZ() * Math.cos(angle);
        final Origin turned = new Origin(origin.owner(), origin.feet(), origin.eye(), new Vector(x, dir.getY(), z), origin.self());
        if (build.definition().mechanic() == Mechanic.CONE) {
            cone(turned, build);
        } else {
            zone(turned, build);
        }
    }

    /** 這個點往下找地面（最多 4 格），讓牆站在地上而不是浮著。 */
    private static Location ground(final Location point) {
        final World world = point.getWorld();
        final int x = point.getBlockX();
        final int z = point.getBlockZ();
        for (int y = point.getBlockY() + 1; y >= point.getBlockY() - 4; y--) {
            if (y - 1 >= world.getMinHeight() && world.getBlockAt(x, y, z).isPassable() && !world.getBlockAt(x, y - 1, z).isPassable()) {
                return new Location(world, point.getX(), y, point.getZ());
            }
        }
        return point;
    }

    // ---- 骸爆 ----

    /** 準心指的點附近最近的屍體；沒有回傳 null。 */
    tw.exilecore.combat.Corpses.Corpse corpseNear(final Origin origin, final SkillBuild build) {
        final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
        final long now = plugin.getServer().getCurrentTick();
        final LivingEntity aimed = targets.aimed(origin, build.range(), 1.0);
        final Location point = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
        tw.exilecore.combat.Corpses.Corpse corpse = corpses.nearest(origin.world().getUID(), point.getX(), point.getZ(), build.params().search(), now);
        if (corpse == null) {
            // 準心附近沒有：找自己身邊的
            corpse = corpses.nearest(origin.world().getUID(), origin.feet().getX(), origin.feet().getZ(), build.params().search(), now);
        }
        return corpse;
    }

    private void detonate(final Origin origin, final SkillBuild build) {
        final tw.exilecore.combat.Corpses.Corpse corpse = corpseNear(origin, build);
        if (corpse == null) {
            return;
        }
        if (origin.owner() == null || !tw.exilecore.stats.TreeEffectKeys.corpseSaved(build.profile().effective(plugin.players().sheet(origin.owner())), plugin.random())) { // 2026-10-26 照 PoE2 天賦樹：x% 機率不毀掉屍體；2026-10-27 也算輔助（惜屍，PoE2 Corpse Conservation）
            plugin.combat().corpses().remove(corpse);
        }
        if (origin.owner() != null) {
            plugin.players().get(origin.owner()).markRecent(tw.exilecore.stats.Recently.CORPSE, plugin.getServer().getCurrentTick()); // 「最近消耗過屍體」
        }
        final Location center = new Location(origin.world(), corpse.x(), corpse.y() + 0.3, corpse.z());
        final double radius = build.radius();
        // 技能自己的傷害 ＋ 屍體最大生命的 x% 當火焰傷害（照法術附加一樣，走增加／更多）
        final double extra = corpse.maxLife() * build.params().percent().at(build.level()) / 100.0;
        final SkillProfile base = build.profile();
        final tw.exilecore.stats.DamagePacket min = base.spellDamage() == null ? new tw.exilecore.stats.DamagePacket() : new tw.exilecore.stats.DamagePacket(base.spellDamage());
        final tw.exilecore.stats.DamagePacket max = base.spellMax() == null ? new tw.exilecore.stats.DamagePacket() : new tw.exilecore.stats.DamagePacket(base.spellMax());
        min.add(tw.exilecore.stats.DamageType.FIRE, extra);
        max.add(tw.exilecore.stats.DamageType.FIRE, extra);
        final SkillProfile profile = new SkillProfile(base.id(), base.tags(), base.effectiveness(), min, max, base.conversion(), base.local());
        for (final LivingEntity target : targets.around(center, radius, origin.owner())) {
            damage.hit(origin.owner(), target, profile, null);
        }
        targets.strike(origin.owner(), profile, SkillTargets.sphere(center, radius), new Object()); // 2026-10-25
        visuals.detonate(build.definition().visual(), center, radius);
    }

    /** 詛咒（2026-10）：準心指的地方（指著敵人就是牠腳下）一圈的敵人全部套上減益。 */
    private void curse(final Player player, final SkillBuild build, final LivingEntity preferred, final SkillParams.Mark spec) {
        final Origin origin = Origin.of(player);
        final LivingEntity aimed = preferred != null && targets.valid(preferred, player) ? preferred : targets.aimed(origin, build.range(), 1.2);
        final Location center = aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
        curseAt(player, build, center, spec);
    }

    /** 2026-10-26 照 PoE2：詛咒落地之後 1.5 秒才生效（捷咒：更少 50%）；輔助觸發的減益（隱藏技能）馬上生效。 */
    static final double CURSE_DELAY_SECONDS = 1.5;

    private void curseAt(final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec) {
        if (build.definition().hidden()) {
            applyCurseAt(player, build, center, spec);
            return;
        }
        final tw.exilecore.stats.StatSheet curseSheet = build.profile().effective(plugin.players().sheet(player));
        // 2026-10-26 照 PoE2 天賦樹：「詛咒生效加快 x%」（stat 寫成減少 x% 延遲）：延遲 ×（1 ＋ 增加）；這次之前只吃輔助的更少
        final double more = Math.max(0, curseSheet.more(tw.exilecore.stats.SupportKeys.CURSE_DELAY, build.profile().tags()))
                * Math.max(0, 1 + curseSheet.increased(tw.exilecore.stats.SupportKeys.CURSE_DELAY, build.profile().tags()));
        final long delay = Math.round(CURSE_DELAY_SECONDS * 20 * more);
        final double radius = spec.radius(build.level()) * build.areaScale(); // 2026-10-26：詛咒的範圍照等級（PoE2 逐級）
        if (delay <= 0) {
            applyCurseAt(player, build, center, spec);
            return;
        }
        final Location at = center.clone();
        // 詛咒圈先畫出來，時間到了才落下
        for (int i = 0; i < 20; i++) {
            final double a = Math.PI * 2 * i / 20;
            Fx.spawn(at.getWorld(), org.bukkit.Particle.WITCH, at.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0, 0, 0, 0);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && player.getWorld().equals(at.getWorld())) {
                applyCurseAt(player, build, at, spec);
            }
        }, delay);
    }

    private void applyCurseAt(final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec) {
        if (plugin.skills() != null && plugin.skills().supportMechanics().lineage().curseGround(player, build, center, spec)) {
            return; // 2026-10-27 照 PoE2：蟾咒、咒光把詛咒改成詛咒地面（LineageMechanics 定時對站在裡面的敵人套詛咒）
        }
        if (plugin.skills() != null && plugin.skills().supportMechanics().extras().cursedGround(player, build, center, spec)) {
            return; // 2026-10-27 照 PoE2 Cursed Ground（咒地）：詛咒改成詛咒地面，站在裡面才被詛咒
        }
        applyCurseAt(player, build, center, spec, 0);
    }

    /** 2026-10-27：untilTick > 0 時詛咒到那個 tick 為止（詛咒地面：站在裡面才被詛咒）。 */
    void applyCurseAt(final Player player, final SkillBuild build, final Location center, final SkillParams.Mark spec, final long untilTick) {
        final double radius = spec.radius(build.level()) * build.areaScale(); // 2026-10-26：詛咒的範圍照等級（PoE2 逐級）
        final long until = untilTick > 0 ? untilTick
                : plugin.getServer().getCurrentTick() + build.durationTicks(spec.duration(build.level())); // 2026-10-26：持續時間照等級
        final List<String> tags = spec.tags().isEmpty() ? java.util.Collections.singletonList(null) : spec.tags();
        // 2026-10：削弱、遲滯這種詛咒：修飾器疊在敵人身上、移動變慢（詛咒效果照輔助的光環效果不吃，只看技能等級）
        final double scale = build.auraEffect(); // 2026-10：詛咒效果（輔助寶石）
        final List<tw.exilecore.stats.Modifier> effects = new java.util.ArrayList<>();
        for (final ScaledMod effect : spec.effects()) {
            effects.add(effect.toModifier(build.level(), scale, build.definition().name()));
        }
        final double slow = spec.slow().at(build.level()) / 100.0 * scale;
        // 2026-10-22：真正的詛咒（不是輔助觸發的殘肢、目眩這些隱藏技能）一隻敵人身上同時只能有你的一個，放新的拿掉舊的
        final boolean exclusive = !build.definition().hidden() && !LineageMechanics.ignoresCurseLimit(build); // 2026-10-27 照 PoE2 魅咒：不受詛咒上限限制
        // 2026-10-26 照 PoE2 天賦樹：「被你詛咒的敵人生命再生速度減少 x%」：疊在詛咒上（MonsterMechanics 的再生乘上去）
        final double regen = exclusive ? plugin.players().sheet(player).increased(tw.exilecore.stats.TreeEffectKeys.CURSE_ENEMY_REGEN, java.util.Set.of()) : 0;
        if (regen != 0) {
            effects.add(tw.exilecore.stats.Modifier.increased(tw.exilecore.stats.TreeEffectKeys.REGEN_RATE, regen, build.definition().name()));
        }
        // 2026-10-27 照 PoE2 天賦樹：末日厄運（詛咒過了一半效果增加）、命運終結（被你詛咒的敵人不能回充護盾）
        final tw.exilecore.stats.StatSheet ownerSheet = plugin.players().sheet(player);
        final double late = exclusive ? ownerSheet.increased(tw.exilecore.stats.KeystoneOriginKeys.CURSE_LATE_MAGNITUDE, java.util.Set.of()) : 0;
        final boolean noRecharge = exclusive && StatKeys.flag(ownerSheet, tw.exilecore.stats.KeystoneOriginKeys.CURSE_NO_ES_RECHARGE);
        final long applied = plugin.getServer().getCurrentTick();
        for (final LivingEntity target : targets.around(center, radius, player)) {
            // 2026-10-26（PoE2）：被詛咒的那一方「受到的詛咒效果」（魔法 −15%、稀有 −30%、傳奇 −50%，換界石再更少）
            final double self = curseEffectOnSelf(target);
            final List<tw.exilecore.stats.Modifier> targetEffects = self == 1.0 ? effects : scaled(effects, self);
            final List<Debuffs.Debuff> placed = new ArrayList<>();
            if (noRecharge) {
                plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff(tw.exilecore.stats.KeystoneOriginKeys.NO_ES_RECHARGE_ID,
                        player.getUniqueId(), until, 0, false));
            }
            for (final String tag : tags) {
                final Debuffs.Debuff debuff = new Debuffs.Debuff(build.definition().id() + (tag == null ? "" : "." + tag),
                        player.getUniqueId(), until, spec.taken() / 100.0 * scale * self, spec.selfOnly(), tag, targetEffects, slow * self);
                placed.add(debuff);
                if (exclusive) {
                    plugin.combat().debuffs().applyCurse(target.getUniqueId(), debuff, build.definition().id(), 1 + (int) Math.round(Math.max(0,
                            plugin.players().sheet(player).flat(tw.exilecore.stats.KeystoneKeys.CURSE_LIMIT, java.util.Set.of())))); // 2026-10-26 滅世之語
                } else {
                    plugin.combat().debuffs().apply(target.getUniqueId(), debuff);
                }
            }
            if (late > 0) {
                strengthenLate(target.getUniqueId(), placed, applied, until, 1 + late);
            }
            visuals.mark(build.definition().visual(), target);
            if (plugin.skills() != null) {
                plugin.skills().supportMechanics().lineage().cursePlaced(player, build, target, placed, applied, until); // 2026-10-27 照 PoE2：魅咒、寂咒
            }
        }
        visuals.nova(build.definition().visual(), center, radius);
    }

    /**
     * 2026-10-27 照 PoE2 末日厄運：詛咒的持續時間過了一半，效果（受到的傷害、修飾器、緩速）乘 factor。
     * 時間到的時候身上還是同一個詛咒（沒被重放、沒被換掉）才換；這次之前平衡等效成整段詛咒效果增加一半。
     */
    private void strengthenLate(final UUID target, final List<Debuffs.Debuff> placed, final long applied, final long until, final double factor) {
        final long half = tw.exilecore.stats.KeystoneOriginKeys.curseHalfway(applied, until);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            final long now = plugin.getServer().getCurrentTick();
            final Debuffs debuffs = plugin.combat().debuffs();
            for (final Debuffs.Debuff original : placed) {
                for (final Debuffs.Debuff current : debuffs.active(target, now)) {
                    if (current.equals(original)) {
                        debuffs.apply(target, new Debuffs.Debuff(current.id(), current.source(), current.untilTick(), current.taken() * factor,
                                current.selfOnly(), current.tag(), scaled(current.mods(), factor), current.slow() * factor));
                        break;
                    }
                }
            }
        }, Math.max(1, half - applied));
    }

    /** 2026-10-26：這隻怪身上的詛咒效果倍率（怪物的屬性表；玩家、沒等級化的是 1）。 */
    private double curseEffectOnSelf(final LivingEntity target) {
        if (target instanceof Player cursed) {
            // 2026-10-26 照 PoE2 天賦樹：玩家的「你身上的詛咒效果減少」（PvP 被詛咒時）；這次之前玩家一律 1
            return Math.max(0, plugin.players().sheet(cursed).apply(tw.exilecore.stats.StatKeys.CURSE_EFFECT_ON_SELF, 1.0, java.util.Set.of()));
        }
        if (plugin.monsters() == null) {
            return 1.0;
        }
        final java.util.Optional<tw.exilecore.monster.MonsterData> data = plugin.monsters().data(target);
        if (data.isEmpty()) {
            return 1.0;
        }
        return Math.max(0, plugin.monsters().sheet(target, data.get()).apply(tw.exilecore.stats.StatKeys.CURSE_EFFECT_ON_SELF, 1.0,
                java.util.Set.of()));
    }

    private static List<tw.exilecore.stats.Modifier> scaled(final List<tw.exilecore.stats.Modifier> effects, final double factor) {
        final List<tw.exilecore.stats.Modifier> list = new java.util.ArrayList<>(effects.size());
        for (final tw.exilecore.stats.Modifier m : effects) {
            list.add(new tw.exilecore.stats.Modifier(m.stat(), m.type(), m.value() * factor, m.tags(), m.source()));
        }
        return list;
    }

    LivingEntity markTarget(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final Origin origin = Origin.of(player);
        if (preferred != null && targets.valid(preferred, player) && preferred.getLocation().distance(player.getLocation()) <= build.range()) {
            return preferred;
        }
        // 2026-10-23：一定要準心指著敵人才放得出去（以前沒指到會自己挑前方 30° 內最近的一隻，常常點到不是想要的那隻）
        return targets.aimed(origin, build.range(), 1.2);
    }

    private void mark(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final SkillParams.Mark spec = build.params().mark();
        if (spec.curse()) {
            curse(player, build, preferred, spec);
            return;
        }
        final LivingEntity target = markTarget(player, build, preferred);
        if (target == null) {
            return;
        }
        final SkillParams.Mark params = build.params().mark();
        final long until = plugin.getServer().getCurrentTick() + build.durationTicks(params.duration(build.level()));
        final java.util.List<tw.exilecore.stats.Modifier> mods = new java.util.ArrayList<>();
        for (final ScaledMod effect : params.effects()) {
            mods.add(effect.toModifier(build.level(), build.auraEffect(), build.definition().name()));
        }
        // 2026-10-22：同時只能有一個印記，放新的會拿掉舊的（以前引雷印、霜縛印、血嗅印能同時掛在同一隻身上）
        plugin.combat().debuffs().applyMark(target.getUniqueId(), new Debuffs.Debuff(build.definition().id(), player.getUniqueId(), until,
                params.taken() / 100.0 * build.auraEffect(), params.selfOnly(), null, mods, 0));
        if (plugin.skills() != null) {
            plugin.skills().supportMechanics().markApplied(player, build, target, until); // 2026-10-26：烙印深、久印
            plugin.skills().spears().marked(player, build, target); // 2026-10-27 照 PoE2 Bloodhound's Mark
        }
        visuals.mark(build.definition().visual(), target);
    }
}
