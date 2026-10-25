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
    /** 常駐的粒子：這麼遠內沒有玩家就不畫（2026-10；傷害照算）。 */
    static final double AMBIENT_VIEW = 48;

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
        /** 不疊加的群組（2026-10）：同一個施放者、同一個群組的地面效果疊在一起，同一個敵人每個間隔只打一次；null = 各算各的。 */
        final String exclusive;
        long nextPulse;
        /** 已經從清單拿掉了（到期、被新的擠掉、毒霧引爆、主人登出）：正在跑的那一輪更新看到就跳過。 */
        boolean gone;

        Zone(final UUID owner, final String skill, final World world, final List<Location> points, final double radius, final SkillProfile profile,
             final String visual, final long untilTick, final int intervalTicks, final double pull, final long now, final String exclusive) {
            this.exclusive = exclusive;
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

    /** 2026-10-24（穢陣，PoE2 Profane Ritual 的「儀式完成時獲得一顆銳星」）：地面效果自然結束時照 charge.zone_end.代號 給戰星。 */
    private void completed(final Player owner, final Zone zone) {
        if (plugin.combat() == null || plugin.combat().charges() == null || zone.profile == null) {
            return;
        }
        final tw.exilecore.stats.StatSheet effective = zone.profile.effective(plugin.players().sheet(owner));
        for (final tw.exilecore.charge.ChargeType type : plugin.combat().charges().config().types()) {
            final int count = (int) Math.round(effective.flat(tw.exilecore.charge.ChargeService.ZONE_END + type.id(), zone.profile.tags()));
            if (count > 0) {
                plugin.combat().charges().grant(owner, type.id(), count);
            }
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
    /** 不疊加的地面效果：「施放者｜群組｜敵人」→ 上一次被打的 tick。 */
    private final java.util.Map<String, Long> exclusiveHits = new java.util.HashMap<>();

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
        add(owner, List.of(center.clone()), radius, profile, visual, durationTicks, intervalSeconds, pull, skill, limit, null);
    }

    /**
     * 圓形的地面效果；exclusive 不是 null 的話，同一個施放者、同一個 exclusive 的地面效果疊在一起不疊加
     * （一次射好幾發、每發各留一灘的技能：瘴苞雨、黑油雨）。
     */
    void addCircle(final Player owner, final Location center, final double radius, final SkillProfile profile, final String visual,
                   final int durationTicks, final double intervalSeconds, final double pull, final String skill, final int limit,
                   final String exclusive) {
        add(owner, List.of(center.clone()), radius, profile, visual, durationTicks, intervalSeconds, pull, skill, limit, exclusive);
    }

    /** 沿著幾個點的地面效果（一道線、走過的路）。 */
    void addPoints(final Player owner, final List<Location> points, final double radius, final SkillProfile profile, final String visual,
                   final int durationTicks, final double intervalSeconds, final double pull) {
        addPoints(owner, points, radius, profile, visual, durationTicks, intervalSeconds, pull, null, 0);
    }

    /** 沿著幾個點的地面效果；skill 這個技能同時最多 limit 個（0 = 不另外限制）。 */
    void addPoints(final Player owner, final List<Location> points, final double radius, final SkillProfile profile, final String visual,
                   final int durationTicks, final double intervalSeconds, final double pull, final String skill, final int limit) {
        add(owner, points, radius, profile, visual, durationTicks, intervalSeconds, pull, skill, limit, null);
    }

    private void add(final Player owner, final List<Location> points, final double radius, final SkillProfile profile, final String visual,
                     final int durationTicks, final double intervalSeconds, final double pull, final String skill, final int limit,
                     final String exclusive) {
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
                oldestSame.gone = true;
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
            oldest.gone = true;
            zones.remove(oldest);
        }
        final int interval = (int) Math.max(1, Math.round(intervalSeconds * 20));
        // 地面效果不是投射物、不是近戰打擊：拿掉這些標籤，加上範圍（吃「增加範圍傷害」）
        final SkillProfile zoneProfile = profile.derive(profile.id() + ".zone", Set.of(Tags.AREA), Set.of(Tags.PROJECTILE, Tags.MELEE, Tags.STRIKE));
        // 2026-10：同一個施放者、同一招的地面效果疊在一起不疊加——同一個敵人每個間隔只被其中一灘打一次
        //（以前只有一次射好幾發的技能這樣；單發的可以疊到 10 灘，站著不動的 Boss 吃 10 倍）。沒指定群組就用技能代號。
        final String group = exclusive != null ? exclusive : profile.id();
        zones.add(new Zone(owner.getUniqueId(), skill, points.getFirst().getWorld(), points, radius, zoneProfile, visual,
                now + Math.max(1, durationTicks), interval, pull, now, group));
    }

    /** 不疊加的地面效果：這個敵人在這個間隔內已經被同一群組的另一灘打過了嗎；沒有就記下這次。 */
    private boolean exclusiveBlocked(final Zone zone, final UUID victim, final long now) {
        if (zone.exclusive == null) {
            return false;
        }
        final String key = zone.owner + "|" + zone.exclusive + "|" + victim;
        final Long last = exclusiveHits.get(key);
        if (last != null && now - last < zone.intervalTicks) {
            return true;
        }
        exclusiveHits.put(key, now);
        return false;
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
            targets.strike(owner, impact.profile(), SkillTargets.sphere(impact.center(), impact.radius()), impact); // 2026-10-25：落下的一擊打到鐘、冰晶
            Fx.as(impact.owner(), () -> visuals.blast(impact.visual(), impact.center(), impact.radius()));
        }
    }

    private void tickZones(final long now) {
        if (zones.isEmpty()) {
            exclusiveHits.clear();
            return;
        }
        if (now % 200 == 0 && !exclusiveHits.isEmpty()) {
            exclusiveHits.values().removeIf(tick -> now - tick > 200); // 打過的紀錄不一直留著
        }
        // 2026-10：照一份清單跑——打到怪會觸發輔助、擊殺效果，它們可能再放一灘地面（或引爆毒霧）改到 zones，
        // 直接在 zones 上跑會丟 ConcurrentModificationException，那一 tick 剩下的地面效果全部被跳過
        boolean expired = false;
        for (final Zone zone : new ArrayList<>(zones)) {
            if (zone.gone) {
                continue; // 這一輪裡被擠掉、引爆了
            }
            final Player owner = plugin.getServer().getPlayer(zone.owner);
            if (now >= zone.untilTick || owner == null || !owner.getWorld().equals(zone.world)) {
                zone.gone = true;
                expired = true;
                if (now >= zone.untilTick && owner != null && owner.getWorld().equals(zone.world)) {
                    completed(owner, zone); // 2026-10-24（穢陣）：撐到結束給戰星
                }
                continue;
            }
            if (now % 2 == 0 && watched(zone)) {
                Fx.as(zone.owner, () -> visuals.zoneAmbient(zone.visual, zone.points, zone.radius, now));
            }
            if (now < zone.nextPulse) {
                continue;
            }
            zone.nextPulse = now + zone.intervalTicks;
            // 一波裡每隻只打一次：線的幾個圓一次掃完（2026-10，以前每個圓各掃一次）
            for (final LivingEntity target : targets.aroundPoints(zone.points, zone.radius, owner)) {
                if (zone.gone) {
                    break; // 打到一半這一灘被引爆、擠掉了
                }
                if (!target.isValid() || target.isDead()) {
                    continue; // 同一波裡前面那一下的連帶效果先把牠打死了
                }
                if (exclusiveBlocked(zone, target.getUniqueId(), now)) {
                    continue; // 同一招疊在一起的另一灘剛打過（2026-10）
                }
                if (zone.pull > 0 && !(target instanceof Player)) {
                    // 吸引不拉玩家（2026-10，PvP：虛空裂隙、颶風槍的漩渦只拉怪物與召喚物，打到玩家照樣有傷害）
                    pull(target, zone.points.getFirst(), zone.pull);
                }
                damage.hit(owner, target, zone.profile, null);
            }
            Fx.as(zone.owner, () -> visuals.zonePulse(zone.visual, zone.points, zone.radius));
        }
        if (expired) {
            zones.removeIf(zone -> zone.gone);
        }
    }

    /** 這一灘附近有沒有人在看（頭尾兩個點任一個 {@value #AMBIENT_VIEW} 格內有玩家）。 */
    private boolean watched(final Zone zone) {
        final Location first = zone.points.getFirst();
        final Location last = zone.points.getLast();
        final double limit = AMBIENT_VIEW * AMBIENT_VIEW;
        for (final Player viewer : zone.world.getPlayers()) {
            final Location at = viewer.getLocation();
            if (at.distanceSquared(first) <= limit || at.distanceSquared(last) <= limit) {
                return true;
            }
        }
        return false;
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
                    zone.gone = true;
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
        zones.removeIf(zone -> {
            if (zone.owner.equals(owner)) {
                zone.gone = true;
                return true;
            }
            return false;
        });
        impacts.removeIf(impact -> impact.owner().equals(owner));
    }

    void clear() {
        for (final Zone zone : zones) {
            zone.gone = true;
        }
        zones.clear();
        impacts.clear();
    }

    int zoneCount() {
        return zones.size();
    }
}
