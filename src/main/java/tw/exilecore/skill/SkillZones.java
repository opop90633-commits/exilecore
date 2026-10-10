package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 會留在地上、過一下才發生的效果，全部在同一個每 tick 的排程裡更新：
 * <ul>
 *   <li>地面效果（zone）：一塊區域（圓或一道線）每隔一段時間打一次裡面的敵人（焰幕、霜嵐、毒氣、燃步的燃燒地面）。</li>
 *   <li>漩渦（vortex）：地面效果再加上把敵人往中心拉。</li>
 *   <li>延遲落下（impact）：幾秒後在一個點炸開一圈（萬箭齊落、隕星、餘震）。</li>
 * </ul>
 * 傷害都算施放者的（走 {@link SkillDamage}）。施放者下線、換世界就清掉；每個玩家最多 {@link #MAX_PER_PLAYER} 個。
 */
final class SkillZones {

    static final int MAX_PER_PLAYER = 10;

    /** 一塊地面效果：points 是它蓋到的圓心（圓一個、線好幾個），每個半徑 radius。 */
    private static final class Zone {
        final UUID owner;
        final String skill;
        final World world;
        final List<Location> points;
        final double radius;
        final SkillProfile profile;
        final String visual;
        final long untilTick;
        final int intervalTicks;
        final double pull;
        long nextPulse;

        Zone(final UUID owner, final String skill, final World world, final List<Location> points, final double radius, final SkillProfile profile,
             final String visual, final long untilTick, final int intervalTicks, final double pull, final long now) {
            this.owner = owner;
            this.skill = skill;
            this.world = world;
            this.points = points;
            this.radius = radius;
            this.profile = profile;
            this.visual = visual;
            this.untilTick = untilTick;
            this.intervalTicks = intervalTicks;
            this.pull = pull;
            this.nextPulse = now + Math.min(intervalTicks, 5);
        }
    }

    /** 一次延遲落下。 */
    private record Impact(UUID owner, Location center, double radius, SkillProfile profile, String visual, long atTick, long startTick) {
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final SkillDamage damage;
    private final List<Zone> zones = new ArrayList<>();
    private final List<Impact> impacts = new ArrayList<>();

    SkillZones(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final SkillDamage damage) {
        this.plugin = plugin;
        this.targets = targets;
        this.visuals = visuals;
        this.damage = damage;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 圓形的地面效果。 */
    void addCircle(final Player owner, final Location center, final double radius, final SkillProfile profile, final String visual,
                   final int durationTicks, final double intervalSeconds, final double pull) {
        addCircle(owner, center, radius, profile, visual, durationTicks, intervalSeconds, pull, null, 0);
    }

    /** 圓形的地面效果；skill 這個技能同時最多 limit 個（0 = 不另外限制）。 */
    void addCircle(final Player owner, final Location center, final double radius, final SkillProfile profile, final String visual,
                   final int durationTicks, final double intervalSeconds, final double pull, final String skill, final int limit) {
        add(owner, List.of(center.clone()), radius, profile, visual, durationTicks, intervalSeconds, pull, skill, limit);
    }

    /** 沿著幾個點的地面效果（一道線、走過的路）。 */
    void addPoints(final Player owner, final List<Location> points, final double radius, final SkillProfile profile, final String visual,
                   final int durationTicks, final double intervalSeconds, final double pull) {
        addPoints(owner, points, radius, profile, visual, durationTicks, intervalSeconds, pull, null, 0);
    }

    /** 沿著幾個點的地面效果；skill 這個技能同時最多 limit 個（0 = 不另外限制）。 */
    void addPoints(final Player owner, final List<Location> points, final double radius, final SkillProfile profile, final String visual,
                   final int durationTicks, final double intervalSeconds, final double pull, final String skill, final int limit) {
        add(owner, points, radius, profile, visual, durationTicks, intervalSeconds, pull, skill, limit);
    }

    private void add(final Player owner, final List<Location> points, final double radius, final SkillProfile profile, final String visual,
                     final int durationTicks, final double intervalSeconds, final double pull, final String skill, final int limit) {
        if (points.isEmpty()) {
            return;
        }
        final long now = now();
        if (skill != null && limit > 0) {
            // 2026-10：同一個技能的地面效果同時最多 limit 個（焰幕、霜嵐以前可以疊到 10 個），超過的話最舊的先消失
            int same = 0;
            Zone oldestSame = null;
            for (final Zone zone : zones) {
                if (zone.owner.equals(owner.getUniqueId()) && skill.equals(zone.skill)) {
                    same++;
                    if (oldestSame == null || zone.untilTick < oldestSame.untilTick) {
                        oldestSame = zone;
                    }
                }
            }
            if (same >= limit && oldestSame != null) {
                zones.remove(oldestSame);
            }
        }
        int mine = 0;
        Zone oldest = null;
        for (final Zone zone : zones) {
            if (zone.owner.equals(owner.getUniqueId())) {
                mine++;
                if (oldest == null || zone.untilTick < oldest.untilTick) {
                    oldest = zone;
                }
            }
        }
        if (mine >= MAX_PER_PLAYER && oldest != null) {
            zones.remove(oldest);
        }
        final int interval = (int) Math.max(1, Math.round(intervalSeconds * 20));
        // 地面效果不是投射物、不是近戰打擊：拿掉這些標籤，加上範圍（吃「增加範圍傷害」）
        final SkillProfile zoneProfile = profile.derive(profile.id() + ".zone", Set.of(Tags.AREA), Set.of(Tags.PROJECTILE, Tags.MELEE, Tags.STRIKE));
        zones.add(new Zone(owner.getUniqueId(), skill, points.getFirst().getWorld(), points, radius, zoneProfile, visual,
                now + Math.max(1, durationTicks), interval, pull, now));
    }

    /** 延遲落下：atTick 時在 center 半徑 radius 內打一圈。 */
    void addImpact(final Player owner, final Location center, final double radius, final SkillProfile profile, final String visual,
                   final int delayTicks) {
        final long now = now();
        impacts.add(new Impact(owner.getUniqueId(), center.clone(), radius, profile, visual, now + Math.max(1, delayTicks), now));
    }

    /** 每 tick。 */
    void tick() {
        final long now = now();
        tickImpacts(now);
        tickZones(now);
    }

    private void tickImpacts(final long now) {
        if (impacts.isEmpty()) {
            return;
        }
        final Iterator<Impact> iterator = impacts.iterator();
        final List<Impact> due = new ArrayList<>();
        while (iterator.hasNext()) {
            final Impact impact = iterator.next();
            final Player owner = plugin.getServer().getPlayer(impact.owner());
            if (owner == null || !owner.getWorld().equals(impact.center().getWorld())) {
                iterator.remove();
                continue;
            }
            if (now >= impact.atTick()) {
                iterator.remove();
                due.add(impact);
            } else if ((now - impact.startTick()) % 3 == 0) {
                Fx.as(impact.owner(), () -> visuals.impactWarning(impact.visual(), impact.center(), impact.radius(),
                        (now - impact.startTick()) / (double) Math.max(1, impact.atTick() - impact.startTick())));
            }
        }
        for (final Impact impact : due) {
            final Player owner = plugin.getServer().getPlayer(impact.owner());
            if (owner == null) {
                continue;
            }
            for (final LivingEntity target : targets.around(impact.center(), impact.radius(), owner)) {
                damage.hit(owner, target, impact.profile(), null);
            }
            Fx.as(impact.owner(), () -> visuals.blast(impact.visual(), impact.center(), impact.radius()));
        }
    }

    private void tickZones(final long now) {
        if (zones.isEmpty()) {
            return;
        }
        final Iterator<Zone> iterator = zones.iterator();
        while (iterator.hasNext()) {
            final Zone zone = iterator.next();
            final Player owner = plugin.getServer().getPlayer(zone.owner);
            if (now >= zone.untilTick || owner == null || !owner.getWorld().equals(zone.world)) {
                iterator.remove();
                continue;
            }
            if (now % 2 == 0) {
                Fx.as(zone.owner, () -> visuals.zoneAmbient(zone.visual, zone.points, zone.radius, now));
            }
            if (now < zone.nextPulse) {
                continue;
            }
            zone.nextPulse = now + zone.intervalTicks;
            final Set<UUID> hit = new java.util.HashSet<>();
            for (final Location point : zone.points) {
                for (final LivingEntity target : targets.around(point, zone.radius, owner)) {
                    if (!hit.add(target.getUniqueId())) {
                        continue; // 一波裡每隻只打一次（線的幾個圓會重疊）
                    }
                    if (zone.pull > 0 && !(target instanceof Player)) {
                        // 吸引不拉玩家（2026-10，PvP：虛空裂隙、颶風槍的漩渦只拉怪物與召喚物，打到玩家照樣有傷害）
                        pull(target, zone.points.getFirst(), zone.pull);
                    }
                    damage.hit(owner, target, zone.profile, null);
                }
            }
            Fx.as(zone.owner, () -> visuals.zonePulse(zone.visual, zone.points, zone.radius));
        }
    }

    /** 往中心拉一把（Boss 這種推不動的照抗性折扣）。 */
    private static void pull(final LivingEntity target, final Location center, final double strength) {
        final Vector to = center.toVector().subtract(target.getLocation().toVector()).setY(0);
        final double distance = to.length();
        if (distance < 0.6) {
            return;
        }
        final org.bukkit.attribute.AttributeInstance resistance = target.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
        final double scale = 1 - Math.max(0, Math.min(1, resistance == null ? 0 : resistance.getValue()));
        if (scale <= 0) {
            return;
        }
        final Vector velocity = to.multiply(Math.min(strength, distance) / distance * scale);
        velocity.setY(0.08);
        target.setVelocity(target.getVelocity().multiply(0.3).add(velocity));
    }

    /** 會被火焰引爆的毒霧（2026-10 PoE2：毒氣箭、毒氣榴彈）。 */
    static final Set<String> GAS = Set.of("caustic_arrow", "gas_grenade");
    /** 毒霧引爆：一跳傷害的幾倍。 */
    static final double GAS_BURST = 6.0;

    /**
     * 火焰打中 at 附近的敵人：自己的毒霧有蓋到那裡就炸開（半徑 ×1.3，傷害是毒霧一跳的 {@value #GAS_BURST} 倍），毒霧消失。
     *
     * @return 炸了幾團
     */
    int detonateGas(final Player owner, final Location at) {
        int count = 0;
        final Iterator<Zone> iterator = zones.iterator();
        final List<Zone> burst = new ArrayList<>();
        while (iterator.hasNext()) {
            final Zone zone = iterator.next();
            if (!zone.owner.equals(owner.getUniqueId()) || !GAS.contains(zone.visual) || !zone.world.equals(at.getWorld())) {
                continue;
            }
            for (final Location point : zone.points) {
                if (point.distanceSquared(at) <= (zone.radius + 0.5) * (zone.radius + 0.5)) {
                    iterator.remove();
                    burst.add(zone);
                    break;
                }
            }
        }
        for (final Zone zone : burst) {
            final Location center = zone.points.getFirst();
            final double radius = zone.radius * 1.3;
            final SkillProfile blast = zone.profile.scaled(GAS_BURST, "毒霧引爆");
            for (final LivingEntity target : targets.around(center, radius, owner)) {
                damage.hit(owner, target, blast, null);
            }
            Fx.as(zone.owner, () -> visuals.blast("fireball", center, radius));
            count++;
        }
        return count;
    }

    /** 某個玩家的全部移除（登出、換角色）。 */
    void forget(final UUID owner) {
        zones.removeIf(zone -> zone.owner.equals(owner));
        impacts.removeIf(impact -> impact.owner().equals(owner));
    }

    void clear() {
        zones.clear();
        impacts.clear();
    }

    int zoneCount() {
        return zones.size();
    }
}
