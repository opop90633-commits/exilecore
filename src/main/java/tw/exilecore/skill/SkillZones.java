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
final class SkillZones implements SkillTargets.Strikeable {

    static final int MAX_PER_PLAYER = 10;
    /** 常駐的粒子：這麼遠內沒有玩家就不畫（2026-10；傷害照算）。 */
    static final double AMBIENT_VIEW = 48;

    /** 一塊地面效果：points 是它蓋到的圓心（圓一個、線好幾個），每個半徑 radius。 */
    private static final class Zone {
        final UUID owner;
        final String skill;
        final World world;
        final List<Location> points;
        double radius;
        /** 2026-10-27 照 PoE2 Encroaching Ground：一開始的半徑、每秒範圍增加多少（小數）、最多增加多少、放下的 tick。 */
        double baseRadius;
        double growPerSecond;
        double growMax;
        long born;
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
        /** 2026-10-26 照 PoE2（火油榴彈）：火油被你的火焰點燃了（點燃之前不燒、只緩速）。 */
        boolean lit;
        /** 2026-10-26 照 PoE2 Advancing Storm（行嵐、掠嵐）：往這裡移動（null = 不動）、每 tick 幾格。 */
        Location driftTo;
        double driftPerTick;

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
    private record Impact(UUID owner, Location center, double radius, SkillProfile profile, String visual, long atTick, long startTick,
                          String lodge, int level) {
    }

    /** 2026-10-26：留在地上的彈（伏霆彈：被你別的技能打到才炸；寒凝彈的冰晶：武裝後敵人踩到就炸）。 */
    private record Lodged(UUID owner, String parent, Location at, SkillConfig.LodgeSpec spec, int level, long placed, long until,
                          long armedAt, double delayed, double rearm) {
        /** 2026-10-27：delayed = Delayed Reaction 的更多 x%（> 0：只在放下的那一刻或時間到時觸發）；rearm = Rearm 的機率 %。 */
        Lodged(final UUID owner, final String parent, final Location at, final SkillConfig.LodgeSpec spec, final int level, final long placed,
               final long until) {
            this(owner, parent, at, spec, level, placed, until, placed + Math.round(spec.arm() * 20), 0, 0);
        }
    }

    private final List<Lodged> lodged = new ArrayList<>();

    /** 2026-10-26：雷遁的標記（敵人 → 誰掛的、用哪個技能、到哪個 tick）。 */
    private record WarpMark(UUID owner, LivingEntity target, SkillBuild build, long until) {
    }

    private final java.util.Map<UUID, WarpMark> warpMarks = new java.util.HashMap<>();

    /** 這隻怪的生命是不是已經低到撲殺門檻（普通 35%、魔法 20%、稀有 10%、傳奇與 Boss 5%）。 */
    boolean cullable(final LivingEntity target) {
        if (plugin.monsters() == null || target == null || target.isDead()) {
            return false;
        }
        final org.bukkit.attribute.AttributeInstance max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (max == null || max.getValue() <= 0) {
            return false;
        }
        final double threshold = plugin.monsters().data(target).map(tw.exilecore.combat.CombatListener::cullThreshold).orElse(0.0);
        return threshold > 0 && target.getHealth() <= max.getValue() * threshold;
    }

    void markWarp(final Player owner, final LivingEntity target, final SkillBuild build, final int ticks) {
        warpMarks.put(target.getUniqueId(), new WarpMark(owner.getUniqueId(), target, build, now() + ticks));
    }

    /** 瞬移進目標、炸開（技能本身有撲殺：生命在門檻以下的直接倒下）；成功給一層閃電灌注。 */
    void warpInto(final Player owner, final LivingEntity target, final SkillBuild build) {
        final Location at = target.getLocation();
        at.setYaw(owner.getLocation().getYaw());
        at.setPitch(owner.getLocation().getPitch());
        owner.teleport(at);
        final double radius = Math.max(0.5, build.params().dash().endRadius().at(build.level()) * build.areaScale());
        for (final LivingEntity hit : targets.around(at, radius, owner)) {
            damage.hit(owner, hit, build.profile(), null);
        }
        Fx.as(owner.getUniqueId(), () -> visuals.blast(build.definition().visual(), at, radius));
        if (plugin.skills() != null) {
            plugin.skills().resources().addInfusion(owner, 2, 1);
        }
    }

    private void tickWarps(final long now) {
        if (warpMarks.isEmpty() || now % 2 != 0) {
            return;
        }
        final List<WarpMark> due = new ArrayList<>();
        for (final Iterator<WarpMark> it = warpMarks.values().iterator(); it.hasNext(); ) {
            final WarpMark mark = it.next();
            final Player owner = plugin.getServer().getPlayer(mark.owner());
            if (owner == null || now >= mark.until() || !mark.target().isValid() || mark.target().isDead()
                    || !owner.getWorld().equals(mark.target().getWorld())) {
                it.remove();
            } else if (cullable(mark.target())) {
                it.remove();
                due.add(mark);
            }
        }
        for (final WarpMark mark : due) {
            final Player owner = plugin.getServer().getPlayer(mark.owner());
            if (owner != null && !owner.isDead()) {
                warpInto(owner, mark.target(), mark.build());
            }
        }
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
        targets.addStrikeable(this); // 2026-10-26：伏霆彈被別的技能打到就引爆
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
            // 2026-10-26（昇華・頻繁暴雨）：元素技能上限 +2
            if (same >= limit + tw.exilecore.ascendancy.AscendancyEffects.limitBonus(owner, profile.tags()) && oldestSame != null) {
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
        SkillProfile zoneProfile = profile.derive(profile.id() + ".zone", Set.of(Tags.AREA), Set.of(Tags.PROJECTILE, Tags.MELEE, Tags.STRIKE));
        // 2026-10-26（急蝕，PoE2）：地面效果算非異常的持續傷害：「非異常持續傷害更多」照乘；持續不到 1 秒的另外再乘
        final double dotMore = localMore(profile, tw.exilecore.stats.SupportKeys.DAMAGE_SKILL_DOT)
                * (durationTicks < 20 ? localMore(profile, tw.exilecore.stats.SupportKeys.DAMAGE_SKILL_DOT_SHORT) : 1.0);
        if (dotMore != 1.0) {
            zoneProfile = zoneProfile.scaled(dotMore, "持續傷害");
        }
        // 2026-10：同一個施放者、同一招的地面效果疊在一起不疊加——同一個敵人每個間隔只被其中一灘打一次
        //（以前只有一次射好幾發的技能這樣；單發的可以疊到 10 灘，站著不動的 Boss 吃 10 倍）。沒指定群組就用技能代號。
        final String group = exclusive != null ? exclusive : profile.id();
        final Zone zone = new Zone(owner.getUniqueId(), skill, points.getFirst().getWorld(), points, radius, zoneProfile, visual,
                now + Math.max(1, durationTicks), interval, pull, now, group);
        // 2026-10-27 照 PoE2 Encroaching Ground：地面效果每秒範圍增加 x%（最多 y%）
        zone.baseRadius = radius;
        zone.born = now;
        zone.growPerSecond = localFlat(profile, tw.exilecore.stats.SupportExtraKeys.ENCROACH) / 100.0;
        zone.growMax = localFlat(profile, tw.exilecore.stats.SupportExtraKeys.ENCROACH_MAX_KEY) / 100.0;
        zones.add(zone);
    }

    /** 2026-10-26 照 PoE2 Advancing Storm：owner 剛放下的這個技能的圓形地面效果改成從 from 出發，每 tick 往 to 移動 perTick 格，到了就停。 */
    void drift(final Player owner, final String skill, final Location from, final Location to, final double perTick) {
        Zone newest = null;
        for (final Zone zone : zones) {
            if (!zone.gone && zone.owner.equals(owner.getUniqueId()) && skill.equals(zone.skill) && zone.points.size() == 1) {
                newest = zone; // 清單照放的順序，最後一個是剛放的
            }
        }
        if (newest == null || perTick <= 0 || !from.getWorld().equals(newest.world)) {
            return;
        }
        final Location point = newest.points.getFirst();
        point.setX(from.getX());
        point.setY(from.getY());
        point.setZ(from.getZ());
        newest.driftTo = to.clone();
        newest.driftPerTick = perTick;
    }

    /** 移動中的地面效果往目的地走一步。 */
    private static void step(final Zone zone) {
        final Location point = zone.points.getFirst();
        final Vector left = zone.driftTo.toVector().subtract(point.toVector());
        final double distance = left.length();
        if (distance <= zone.driftPerTick) {
            point.setX(zone.driftTo.getX());
            point.setY(zone.driftTo.getY());
            point.setZ(zone.driftTo.getZ());
            zone.driftTo = null;
            return;
        }
        point.add(left.multiply(zone.driftPerTick / distance));
    }

    /** 2026-10-27：技能自己的（輔助寶石的）某個屬性的 FLAT 加總。 */
    static double localFlat(final SkillProfile profile, final String stat) {
        double sum = 0;
        for (final tw.exilecore.stats.Modifier modifier : profile.local()) {
            if (modifier.stat().equals(stat) && modifier.type() == tw.exilecore.stats.ModType.FLAT) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    /** 2026-10-26：技能自己的（輔助寶石的）某個屬性的「更多」連乘。 */
    static double localMore(final SkillProfile profile, final String stat) {
        double product = 1;
        for (final tw.exilecore.stats.Modifier modifier : profile.local()) {
            if (modifier.stat().equals(stat) && modifier.type() == tw.exilecore.stats.ModType.MORE) {
                product *= 1 + modifier.value();
            }
        }
        return product;
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
        impacts.add(new Impact(owner.getUniqueId(), center.clone(), radius, profile, visual, now + Math.max(1, delayTicks), now, null, 1));
    }

    /** 2026-10-26：落下之後留在地上（skills.yml 的 lodge；parent 是放它的技能，level 是那個技能的等級）。 */
    void addImpact(final Player owner, final Location center, final double radius, final SkillProfile profile, final String visual,
                   final int delayTicks, final String parent, final int level) {
        final long now = now();
        impacts.add(new Impact(owner.getUniqueId(), center.clone(), radius, profile, visual, now + Math.max(1, delayTicks), now, parent, level));
    }

    /** 留一顆在地上（同一個技能超過上限就先拿掉最舊的）。 */
    void lodge(final Player owner, final String parent, final int level, final Location at) {
        final SkillConfig.LodgeSpec spec = plugin.skills() == null ? null : plugin.skills().config().lodge(parent);
        if (spec == null) {
            return;
        }
        final long now = now();
        int mine = 0;
        for (final Lodged bolt : lodged) {
            if (bolt.owner().equals(owner.getUniqueId()) && bolt.parent().equals(parent)) {
                mine++;
            }
        }
        for (final Iterator<Lodged> it = lodged.iterator(); it.hasNext() && mine >= spec.max(); ) {
            final Lodged bolt = it.next();
            if (bolt.owner().equals(owner.getUniqueId()) && bolt.parent().equals(parent)) {
                it.remove();
                mine--;
            }
        }
        // 2026-10-27 照 PoE2 Delayed Reaction／Rearm（危險區域：寒凝彈的冰晶這種踩到才觸發的）：放下的那一刻有敵人就觸發，
        // 不然只在時間到時觸發（持續時間更少 70%、更多 x% 傷害）；觸發後 x% 機率過一下子重新布置
        final boolean hazard = spec.proximity() && plugin.skills().config().skill(parent) != null
                && plugin.skills().config().skill(parent).tags().contains(tw.exilecore.stats.TreeMechanicKeys.HAZARD);
        final double delayed = hazard ? plugin.skills().supportFlat(owner, parent, tw.exilecore.stats.SupportExtraKeys.DELAYED_REACTION) : 0;
        final double rearm = hazard ? plugin.skills().supportFlat(owner, parent, tw.exilecore.stats.SupportExtraKeys.REARM) : 0;
        final long until = now + Math.round(spec.duration() * 20 * (delayed > 0 ? tw.exilecore.stats.SupportExtraKeys.DELAYED_REACTION_DURATION : 1));
        final Lodged bolt = new Lodged(owner.getUniqueId(), parent, at.clone(), spec, level, now, until,
                now + Math.round(spec.arm() * 20), delayed, rearm);
        if (delayed > 0 && !targets.around(bolt.at(), spec.trigger(), owner).isEmpty()) {
            detonate(owner, bolt, now); // 放下的那一刻就有敵人：立刻觸發
            rearm(bolt, now);
            return;
        }
        lodged.add(bolt);
    }

    /** 2026-10-27 照 PoE2 Rearm：觸發之後 rearm% 機率在原地重新布置（過 {@link tw.exilecore.stats.SupportExtraKeys#REARM_TICKS} 再武裝）。 */
    private void rearm(final Lodged bolt, final long now) {
        if (bolt.rearm() <= 0 || plugin.random().nextDouble() * 100 >= bolt.rearm()) {
            return;
        }
        final long duration = Math.round(bolt.spec().duration() * 20 * (bolt.delayed() > 0 ? tw.exilecore.stats.SupportExtraKeys.DELAYED_REACTION_DURATION : 1));
        lodged.add(new Lodged(bolt.owner(), bolt.parent(), bolt.at(), bolt.spec(), bolt.level(), now, now + duration,
                now + Math.max(Math.round(bolt.spec().arm() * 20), tw.exilecore.stats.SupportExtraKeys.REARM_TICKS), bolt.delayed(), bolt.rearm()));
    }

    /** 被打到的時候（SkillTargets.strike）：你別的技能打到伏霆彈就引爆（同一個技能自己不會）。 */
    @Override
    public void struck(final Player owner, final SkillProfile skill, final java.util.function.Predicate<Location> inside, final Object source) {
        if (owner == null || lodged.isEmpty()) {
            return;
        }
        final String by = SupportProcs.baseId(skill.id());
        final List<Lodged> hit = new ArrayList<>();
        for (final Iterator<Lodged> it = lodged.iterator(); it.hasNext(); ) {
            final Lodged bolt = it.next();
            if (!bolt.spec().proximity() && bolt.owner().equals(owner.getUniqueId()) && !bolt.parent().equals(by) && inside.test(bolt.at())) { // 伏霆彈（不是危險區域）
                it.remove();
                hit.add(bolt);
            }
        }
        for (final Lodged bolt : hit) {
            detonate(owner, bolt, now());
        }
    }

    private void detonate(final Player owner, final Lodged bolt, final long now) {
        final SkillConfig config = plugin.skills().config();
        final SkillDefinition sub = config.skill(bolt.spec().skill());
        if (sub == null) {
            return;
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(owner);
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(owner);
        SkillBuild build = SkillBuild.of(config, sub, bolt.level(), state.level(), false, List.of(), state.weaponTags(), sheet);
        if (bolt.spec().rampMore() > 0) { // 寒凝彈：武裝後越久越痛
            final double armed = (now - bolt.armedAt()) / 20.0;
            final double more = bolt.spec().rampMore() / 100.0 * Math.max(0, Math.min(1, armed / bolt.spec().rampSeconds()));
            if (more > 0) {
                build = build.withProfile(build.profile().withLocal(List.of(tw.exilecore.stats.Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE, more, sub.name()))));
            }
        }
        if (bolt.delayed() > 0) { // 2026-10-27 照 PoE2 Delayed Reaction：更多 x% 傷害
            build = build.withProfile(build.profile().withLocal(List.of(tw.exilecore.stats.Modifier.more(tw.exilecore.stats.StatKeys.DAMAGE,
                    bolt.delayed() / 100.0, "遲爆"))));
        }
        final SkillProfile profile = build.profile();
        final double radius = build.radius();
        for (final LivingEntity target : targets.around(bolt.at(), radius, owner)) {
            damage.hit(owner, target, profile, null);
        }
        Fx.as(bolt.owner(), () -> visuals.blast(sub.visual(), bolt.at(), radius));
    }

    private void tickLodged(final long now) {
        if (lodged.isEmpty()) {
            return;
        }
        final List<Lodged> boom = new ArrayList<>();
        for (final Iterator<Lodged> it = lodged.iterator(); it.hasNext(); ) {
            final Lodged bolt = it.next();
            final Player owner = plugin.getServer().getPlayer(bolt.owner());
            if (owner == null || now >= bolt.until() || !owner.getWorld().equals(bolt.at().getWorld())) {
                it.remove();
                if (owner != null && now >= bolt.until() && (bolt.spec().expire() || bolt.delayed() > 0) && owner.getWorld().equals(bolt.at().getWorld())) {
                    boom.add(bolt); // 2026-10-26 照 PoE2（瘴苞雨）：時間到自己引爆；2026-10-27 遲爆（Delayed Reaction）：時間到觸發
                }
                continue;
            }
            final boolean armed = now >= bolt.armedAt();
            if (bolt.spec().proximity() && armed && bolt.delayed() <= 0 && now % 4 == 0
                    && !targets.around(bolt.at(), bolt.spec().trigger(), owner).isEmpty()) {
                it.remove();
                boom.add(bolt);
                continue;
            }
            if ((now - bolt.placed()) % 10 == 0 && now >= bolt.placed()) {
                final org.bukkit.Particle particle = bolt.spec().proximity() ? (armed ? org.bukkit.Particle.SNOWFLAKE : org.bukkit.Particle.WHITE_ASH)
                        : org.bukkit.Particle.ELECTRIC_SPARK;
                Fx.as(bolt.owner(), () -> bolt.at().getWorld().spawnParticle(particle, bolt.at().clone().add(0, 0.2, 0), 3, 0.1, 0.1, 0.1, 0));
            }
        }
        for (final Lodged bolt : boom) {
            final Player owner = plugin.getServer().getPlayer(bolt.owner());
            if (owner != null) {
                detonate(owner, bolt, now);
                rearm(bolt, now); // 2026-10-27 照 PoE2 Rearm
            }
        }
    }

    /** 每 tick。 */
    void tick() {
        final long now = now();
        tickImpacts(now);
        tickLodged(now);
        tickWarps(now);
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
            if (impact.lodge() != null) {
                lodge(owner, impact.lodge(), impact.level(), impact.center()); // 2026-10-26：落下後留在地上（伏霆彈、寒凝彈）
            }
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
            if (zone.driftTo != null) {
                step(zone); // 2026-10-26（行嵐、掠嵐）：移動中的風暴
            }
            if (zone.growPerSecond > 0) {
                // 2026-10-27 照 PoE2 Encroaching Ground：範圍隨時間變大（半徑 = √範圍倍率）
                zone.radius = zone.baseRadius * Math.sqrt(1 + Math.min(zone.growMax, zone.growPerSecond * (now - zone.born) / 20.0));
            }
            if (now % 2 == 0 && watched(zone)) {
                Fx.as(zone.owner, () -> visuals.zoneAmbient(zone.visual, zone.points, zone.radius, now));
            }
            if (now < zone.nextPulse) {
                continue;
            }
            zone.nextPulse = now + zone.intervalTicks;
            // 2026-10-26 照 PoE2：毒霧不打人、只上毒（蝕毒箭、瘴氣榴彈）；火油要被點燃才燒（火油榴彈），點燃之前只緩速
            final tw.exilecore.stats.StatSheet ownerSheet = zone.profile == null ? null : plugin.players().sheet(owner);
            final boolean poisonOnly = ownerSheet != null && PoisonOnly.ground(ownerSheet, zone.profile);
            final boolean unlit = ownerSheet != null && !zone.lit && PoisonOnly.dormant(ownerSheet, zone.profile);
            // 2026-10-27 照 PoE2 天賦樹（「站在感電／冰緩／點燃地面上時」）：這一波站在裡面的玩家記一下
            final String groundEvent = TreeLeftovers.groundEvent(zone.profile, poisonOnly || unlit);
            TreeLeftovers.groundPulse(plugin, zone.points, zone.radius, groundEvent, now);
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
                // 2026-10-27 照 PoE2（塗油限定 沙漠之蔑）：站在冰緩／點燃地面上的敵人受到的火焰／冰冷傷害增加（先給再打）
                TreeLeftovers.scornGround(plugin, owner, ownerSheet, target, groundEvent, zone.intervalTicks, now);
                SupportExtras.drench(plugin, owner, target, zone.skill, zone.intervalTicks, now); // 2026-10-27 照 PoE2：積雷雲範圍內的敵人濕透
                if (zone.pull > 0 && !(target instanceof Player)) {
                    // 吸引不拉玩家（2026-10，PvP：虛空裂隙、颶風槍的漩渦只拉怪物與召喚物，打到玩家照樣有傷害）
                    pull(target, zone.points.getFirst(), zone.pull);
                }
                if (GroundEffects.is(zone.profile)) {
                    GroundEffects.apply(plugin, owner, target, zone.profile, zone.intervalTicks); // 2026-10-26 照 PoE2：冰緩／感電地面不打傷害
                    continue;
                }
                if (poisonOnly) {
                    PoisonOnly.apply(plugin, owner, target, zone.profile);
                    continue;
                }
                if (unlit) {
                    if (plugin.ailments() != null && !(target instanceof Player)) {
                        // 2026-10-26 照 PoE2：火油的緩速照技能（火油榴彈 40%、黑油雨 20%，oil.slow），有寫 oil.exposure 的再給曝露
                        final double slow = OriginalSkills.oilValue(zone.profile, OriginalSkills.OIL_SLOW);
                        plugin.ailments().groundOnMonster(target, slow > 0 ? slow / 100.0 : OIL_SLOW, 0, zone.intervalTicks + 5);
                        OriginalSkills.oilExposure(plugin, owner, target, zone.profile, zone.intervalTicks + 5);
                    }
                    continue;
                }
                // 2026-10-26（速蝕，PoE2 Intense Agony）：滿血的敵人受到的非異常持續傷害更多
                final double fullLife = SupportMechanics.fullLife(target) ? localMore(zone.profile, tw.exilecore.stats.SupportKeys.DOT_VS_FULL_LIFE) : 1.0;
                damage.hit(owner, target, fullLife == 1.0 ? zone.profile : zone.profile.scaled(fullLife, "對滿血的敵人"), null);
            }
            Fx.as(zone.owner, () -> visuals.zonePulse(zone.visual, zone.points, zone.radius));
        }
        if (expired) {
            zones.removeIf(zone -> zone.gone);
        }
    }

    /**
     * 2026-10-27（Rime）：owner 留下、蓋到 at 的地面效果裡，技能描述上 stat 的 FLAT 最大值（輔助寶石給的；沒有回傳 0）。
     */
    double groundFlat(final UUID owner, final Location at, final String stat) {
        double best = 0;
        for (final Zone zone : zones) {
            if (zone.gone || zone.profile == null || !zone.owner.equals(owner) || zone.world != at.getWorld()) {
                continue;
            }
            double value = 0;
            for (final tw.exilecore.stats.Modifier modifier : zone.profile.local()) {
                if (modifier.stat().equals(stat) && modifier.type() == tw.exilecore.stats.ModType.FLAT) {
                    value += modifier.value();
                }
            }
            if (value <= best) {
                continue;
            }
            for (final Location point : zone.points) {
                final double dx = point.getX() - at.getX();
                final double dz = point.getZ() - at.getZ();
                if (dx * dx + dz * dz <= zone.radius * zone.radius && Math.abs(point.getY() - at.getY()) <= 3) {
                    best = value;
                    break;
                }
            }
        }
        return best;
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

    /** 2026-10-26 照 PoE2（火油榴彈）：火油讓敵人緩速 40%。 */
    static final double OIL_SLOW = 0.4;

    /**
     * 2026-10-26 照 PoE2（火油榴彈）：你的火焰打在 at 附近：蓋到那裡、還沒點燃的火油點燃（之後照地面效果燒）。回傳點燃了幾灘。
     */
    int igniteOil(final Player owner, final Location at) {
        int count = 0;
        final tw.exilecore.stats.StatSheet ownerSheet = plugin.players().sheet(owner);
        for (final Zone zone : zones) {
            if (zone.gone || zone.lit || !zone.owner.equals(owner.getUniqueId()) || !zone.world.equals(at.getWorld()) || zone.profile == null
                    || !PoisonOnly.dormant(ownerSheet, zone.profile)) {
                continue;
            }
            for (final Location point : zone.points) {
                if (point.distanceSquared(at) <= (zone.radius + 0.5) * (zone.radius + 0.5)) {
                    zone.lit = true;
                    zone.nextPulse = now();
                    count++;
                    Fx.as(zone.owner, () -> visuals.blast("fireball", point, zone.radius));
                    break;
                }
            }
        }
        return count;
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
        lodged.removeIf(bolt -> bolt.owner().equals(owner));
        warpMarks.values().removeIf(mark -> mark.owner().equals(owner));
    }

    void clear() {
        for (final Zone zone : zones) {
            zone.gone = true;
        }
        zones.clear();
        impacts.clear();
        lodged.clear();
    }

    int zoneCount() {
        return zones.size();
    }
}
