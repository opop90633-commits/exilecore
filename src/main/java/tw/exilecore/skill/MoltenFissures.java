package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.core.Units;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 0.5：熔岩地裂（Molten Fissure）——炎脈裂地（Volcanic Fissure）、熔裂踏（Fury of the Mountain）、
 * 回旋鍛錘戰吼震碎的裂地（Forge Hammer Fissures，{@link #createPath}）共用。
 * <ul>
 *   <li>地裂從起點往一個方向（鍛錘的是螺旋的折線）延伸，速度 {@link #SPEED_METRES} 公尺／秒；延伸的時候打到地裂上（半徑內）的敵人，
 *       同一次砸出來的地裂（含分岔）每隻只打一下；</li>
 *   <li>延伸完留在地上（炎脈裂地 12 秒、熔裂踏 12 秒、鍛錘 8 秒），同時有上限（炎脈裂地 8 組、熔裂踏 12 道、鍛錘 10 道；
 *       熔裂踏、鍛錘多的、分岔的裂縫各算一道；超過時最舊的消失）；</li>
 *   <li>你用「別的」猛擊技能打在自己的地裂上時，從碰到的地方沿著地裂往兩邊擴散一道餘震（地裂那個技能自己的傷害），
 *       同一道地裂 {@link #ERUPT_COOLDOWN_TICKS} tick 內只會被引爆一次；餘震不會再引爆別的地裂（0.2.0）。</li>
 * </ul>
 * 原作沒有寫的數字（分岔的位置、角度、長度、餘震擴散速度）照最接近的做法定在下面的常數。
 */
final class MoltenFissures {

    static final String VOLCANIC = "flame_vein_rift";
    static final String MOUNTAIN = "molten_crack_stomp";

    /** 地裂延伸速度（poe2db Volcanic Fissure：Projectile Speed 6 metres per second；熔裂踏原作沒寫，用同一個）。 */
    static final double SPEED_METRES = 6.0;
    /** 每 tick 延伸幾格。 */
    static final double STEP_PER_TICK = Units.metres(SPEED_METRES) / 20.0;
    /** 地裂取樣點的間距（格）。 */
    static final double SPACING = 0.5;
    /** 炎脈裂地：分岔 2 道（number_of_branching_fissures 2），從主地裂一半的地方往左右各岔一道。 */
    static final int VOLCANIC_BRANCHES = 2;
    static final double BRANCH_AT = 0.5;
    static final double BRANCH_ANGLE = Math.toRadians(35);
    static final double BRANCH_LENGTH = 0.6;
    /** 熔裂踏：每一下往前方 120° 內隨機的方向裂一道（bear_fury_of_the_mountain_front_facing_cone_angle 120）、最多 12 道、留 12 秒。 */
    static final double MOUNTAIN_ARC = 120;
    static final int MOUNTAIN_LIMIT = 12;
    static final double MOUNTAIN_SECONDS = 12;
    /** 熔裂踏地裂的長度（公尺；原作沒有寫，照炎脈裂地 1 級的長度）。 */
    static final double MOUNTAIN_LENGTH_METRES = 7;
    /** 餘震：0.3.0「不能 0.2 秒內觸發超過一次」、擴散速度（原作沒寫：地裂延伸速度的兩倍）。 */
    static final long ERUPT_COOLDOWN_TICKS = 4;
    static final double AFTERSHOCK_STEP_PER_TICK = STEP_PER_TICK * 2;

    /** 一次砸出來的地裂（主地裂＋分岔）：上限、命中照這個算。 */
    private static final class Group {
        final Player owner;
        final String skill;
        final List<Fissure> fissures = new ArrayList<>();
        final Set<UUID> hit = new HashSet<>();

        Group(final Player owner, final String skill) {
            this.owner = owner;
            this.skill = skill;
        }
    }

    private static final class Fissure {
        final Group group;
        final List<Location> points;
        final double radius;
        final SkillProfile profile;
        final String visual;
        final long until;
        double front;
        long lastErupt = Long.MIN_VALUE / 2;

        Fissure(final Group group, final List<Location> points, final double radius, final SkillProfile profile, final String visual, final long until) {
            this.group = group;
            this.points = points;
            this.radius = radius;
            this.profile = profile;
            this.visual = visual;
            this.until = until;
        }

        boolean travelling() {
            return front < points.size() - 1;
        }
    }

    /** 沿著地裂擴散的餘震：從 start 往兩邊。 */
    private static final class Aftershock {
        final Fissure fissure;
        final int start;
        final Set<UUID> hit = new HashSet<>();
        double spread;

        Aftershock(final Fissure fissure, final int start) {
            this.fissure = fissure;
            this.start = start;
        }

        boolean done() {
            return start - spread <= 0 && start + spread >= fissure.points.size() - 1;
        }
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final SkillDamage damage;
    private final Map<UUID, ArrayDeque<Group>> groups = new HashMap<>();
    private final List<Aftershock> aftershocks = new ArrayList<>();
    private org.bukkit.scheduler.BukkitTask task;

    MoltenFissures(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final SkillDamage damage) {
        this.plugin = plugin;
        this.targets = targets;
        this.visuals = visuals;
        this.damage = damage;
    }

    static boolean is(final String skillId) {
        return VOLCANIC.equals(skillId) || MOUNTAIN.equals(skillId);
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    // ---- 純函式（測試用） ----

    /** 這道地裂能不能再被引爆（上次引爆過 {@link #ERUPT_COOLDOWN_TICKS} tick 了沒）。 */
    static boolean shockReady(final long last, final long now) {
        return now - last >= ERUPT_COOLDOWN_TICKS;
    }

    /** 熔裂踏這一下的方向：面向左右各 arc/2 度內，roll（0～1）決定在哪。回傳 (x, z)，已正規化。 */
    static double[] arcDirection(final double facingX, final double facingZ, final double arcDegrees, final double roll) {
        final double length = Math.hypot(facingX, facingZ);
        final double fx = length < 1e-9 ? 1 : facingX / length;
        final double fz = length < 1e-9 ? 0 : facingZ / length;
        final double angle = Math.toRadians(arcDegrees) * (Math.max(0, Math.min(1, roll)) - 0.5);
        return new double[] {fx * Math.cos(angle) - fz * Math.sin(angle), fx * Math.sin(angle) + fz * Math.cos(angle)};
    }

    /** 長度 length 的地裂要幾個取樣點（含起點，至少 2 個）。 */
    static int pointCount(final double length) {
        return Math.max(2, (int) Math.round(Math.max(0, length) / SPACING) + 1);
    }

    /** 分岔第 i 道（0 起）的轉角：左右交替。 */
    static double branchAngle(final int i) {
        return BRANCH_ANGLE * ((i / 2) + 1) * (i % 2 == 0 ? 1 : -1);
    }

    /** 加入一組之後超過上限要收掉幾組最舊的。 */
    static int overflow(final int groupsAfterAdd, final int limit) {
        return limit <= 0 ? 0 : Math.max(0, groupsAfterAdd - limit);
    }

    // ---- 建立 ----

    /**
     * 砸出一道地裂（branches 道分岔）：從 from 往 direction 長 length 格、半徑 radius 格；profile 是延伸時的那一下，
     * 也是之後被引爆時的餘震；留 durationTicks；這個技能同時最多 limit 組（超過時最舊的那組消失）。回傳主地裂的取樣點數。
     */
    int create(final Player owner, final String skill, final Location from, final Vector direction, final double length, final double radius,
               final int branches, final SkillProfile profile, final String visual, final long durationTicks, final int limit) {
        if (owner == null || from.getWorld() == null) {
            return 0;
        }
        final Vector dir = direction.clone().setY(0);
        if (dir.lengthSquared() < 1e-9) {
            dir.setX(1);
        }
        dir.normalize();
        final long until = now() + Math.max(1, durationTicks);
        final Group group = new Group(owner, skill);
        final List<Location> main = path(from, dir, length);
        group.fissures.add(new Fissure(group, main, radius, profile, visual, until));
        for (int i = 0; i < branches && main.size() > 2; i++) {
            final Location at = main.get((int) Math.round((main.size() - 1) * BRANCH_AT));
            final double angle = branchAngle(i);
            final Vector turned = new Vector(dir.getX() * Math.cos(angle) - dir.getZ() * Math.sin(angle), 0,
                    dir.getX() * Math.sin(angle) + dir.getZ() * Math.cos(angle));
            final List<Location> branch = path(at, turned, length * BRANCH_LENGTH);
            final Fissure fissure = new Fissure(group, branch, radius, profile, visual, until);
            fissure.front = -(main.size() - 1) * BRANCH_AT; // 主地裂延伸到岔口才開始延伸
            group.fissures.add(fissure);
        }
        add(owner, group, limit, from);
        return main.size();
    }

    /**
     * 2026-11-01 照 PoE2 Forge Hammer（Fissures）：沿著折線 path（至少兩點；回旋鍛錘螺旋的一道、或從上面岔出去的分岔）裂一道熔岩地裂，
     * 半徑 radius 格；跟 {@link #create} 一樣延伸、留 durationTicks、被別的猛擊引爆。每一道各算一組（上限 limit 照道數算）。
     */
    void createPath(final Player owner, final String skill, final List<Location> path, final double radius, final SkillProfile profile,
                    final String visual, final long durationTicks, final int limit) {
        if (owner == null || path.size() < 2 || path.getFirst().getWorld() == null) {
            return;
        }
        final List<Location> points = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            final Location a = i == 1 ? path.getFirst() : points.getLast();
            final Location b = path.get(i);
            final Vector dir = new Vector(b.getX() - a.getX(), 0, b.getZ() - a.getZ());
            final double length = dir.length();
            if (length < 1e-6) {
                continue;
            }
            final List<Location> segment = path(a, dir.multiply(1 / length), length);
            points.addAll(i == 1 ? segment : segment.subList(1, segment.size()));
            if (segment.size() < pointCount(length)) {
                break; // 碰到牆就停
            }
        }
        if (points.size() < 2) {
            return;
        }
        final Group group = new Group(owner, skill);
        group.fissures.add(new Fissure(group, points, radius, profile, visual, now() + Math.max(1, durationTicks)));
        add(owner, group, limit, path.getFirst());
    }

    /** 加入一組；這個技能超過 limit 組時最舊的消失。 */
    private void add(final Player owner, final Group group, final int limit, final Location sound) {
        final ArrayDeque<Group> mine = groups.computeIfAbsent(owner.getUniqueId(), id -> new ArrayDeque<>());
        mine.removeIf(old -> old.fissures.getFirst().until <= now());
        mine.addLast(group);
        int remove = overflow(count(mine, group.skill), limit);
        for (final Iterator<Group> it = mine.iterator(); it.hasNext() && remove > 0; ) {
            if (it.next().skill.equals(group.skill)) {
                it.remove();
                remove--;
            }
        }
        sound.getWorld().playSound(sound, Sound.BLOCK_BASALT_BREAK, SoundCategory.PLAYERS, 1f, 0.6f);
        if (task == null) {
            task = plugin.repeat("skill.molten_fissures", this::tick, 1, 1);
        }
    }

    /** 玩家離開：拿掉他的地裂與餘震。 */
    void forget(final UUID owner) {
        groups.remove(owner);
        aftershocks.removeIf(shock -> shock.fissure.group.owner.getUniqueId().equals(owner));
    }

    private static int count(final ArrayDeque<Group> mine, final String skill) {
        int n = 0;
        for (final Group group : mine) {
            if (group.skill.equals(skill)) {
                n++;
            }
        }
        return n;
    }

    /** 取樣點：每 SPACING 格一點，貼著地面（最多往上 1、往下 3 格）；碰到牆（兩格高都擋住）就停。 */
    private static List<Location> path(final Location from, final Vector dir, final double length) {
        final List<Location> points = new ArrayList<>();
        final World world = from.getWorld();
        double y = from.getY();
        final int count = pointCount(length);
        for (int i = 0; i < count; i++) {
            final double along = Math.min(length, i * SPACING);
            final double x = from.getX() + dir.getX() * along;
            final double z = from.getZ() + dir.getZ() * along;
            final double ground = groundY(world, x, y, z);
            if (Double.isNaN(ground)) {
                break;
            }
            y = ground;
            points.add(new Location(world, x, y, z));
        }
        if (points.isEmpty()) {
            points.add(from.clone());
        }
        if (points.size() == 1) {
            points.add(points.getFirst().clone());
        }
        return points;
    }

    private static double groundY(final World world, final double x, final double y, final double z) {
        final int bx = (int) Math.floor(x);
        final int bz = (int) Math.floor(z);
        final int base = (int) Math.floor(y + 1e-6);
        for (int dy = 1; dy >= -3; dy--) {
            final int feet = base + dy;
            if (feet - 1 < world.getMinHeight() || feet + 1 >= world.getMaxHeight()) {
                continue;
            }
            if (world.getBlockAt(bx, feet, bz).isPassable() && !world.getBlockAt(bx, feet - 1, bz).isPassable()) {
                return feet;
            }
        }
        return world.getBlockAt(bx, base, bz).isPassable() ? y : Double.NaN;
    }

    // ---- 引爆 ----

    /**
     * 你用猛擊技能 slamSkill 打在 center 半徑 reach 的範圍：碰到的自己的地裂（不是同一個技能的）各擴散一道餘震。回傳引爆幾道。
     */
    int slammed(final Player owner, final String slamSkill, final Location center, final double reach) {
        final ArrayDeque<Group> mine = owner == null ? null : groups.get(owner.getUniqueId());
        if (mine == null || mine.isEmpty() || center == null || center.getWorld() == null) {
            return 0;
        }
        final long now = now();
        int erupted = 0;
        for (final Group group : mine) {
            if (group.skill.equals(slamSkill)) {
                continue;
            }
            for (final Fissure fissure : group.fissures) {
                if (fissure.until <= now || !shockReady(fissure.lastErupt, now) || fissure.front < 0) {
                    continue;
                }
                final int at = nearest(fissure, center, reach + fissure.radius);
                if (at >= 0) {
                    fissure.lastErupt = now;
                    aftershocks.add(new Aftershock(fissure, at));
                    erupted++;
                }
            }
        }
        if (erupted > 0) {
            center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.6f, 1.3f);
        }
        return erupted;
    }

    /** 品質的「多一次餘震」：剛砸出來的這組（最新的、這個技能的）從起點沿著地裂擴散一道餘震（delayTicks 後）。 */
    void aftershockNewest(final Player owner, final String skill, final long delayTicks) {
        final ArrayDeque<Group> mine = groups.get(owner.getUniqueId());
        if (mine == null) {
            return;
        }
        Group newest = null;
        for (final Group group : mine) {
            if (group.skill.equals(skill)) {
                newest = group;
            }
        }
        if (newest == null) {
            return;
        }
        final Group target = newest;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (final Fissure fissure : target.fissures) {
                if (fissure.until > now()) {
                    aftershocks.add(new Aftershock(fissure, 0));
                }
            }
        }, Math.max(1, delayTicks));
    }

    /** 地裂上離 center 最近、在 within 格內的取樣點（只算已經延伸到的）；沒有回傳 -1。 */
    private static int nearest(final Fissure fissure, final Location center, final double within) {
        int best = -1;
        double bestSq = within * within;
        final int reached = (int) Math.min(fissure.points.size() - 1, Math.floor(fissure.front));
        for (int i = 0; i <= reached; i++) {
            final Location point = fissure.points.get(i);
            if (point.getWorld() != center.getWorld() || Math.abs(point.getY() - center.getY()) > 3) {
                continue;
            }
            final double dx = point.getX() - center.getX();
            final double dz = point.getZ() - center.getZ();
            final double sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = i;
            }
        }
        return best;
    }

    // ---- 每 tick ----

    private void tick() {
        final long now = now();
        final double advance = STEP_PER_TICK / SPACING;
        for (final Iterator<Map.Entry<UUID, ArrayDeque<Group>>> it = groups.entrySet().iterator(); it.hasNext(); ) {
            final ArrayDeque<Group> mine = it.next().getValue();
            mine.removeIf(group -> !group.owner.isOnline() || group.fissures.getFirst().until <= now
                    || group.owner.getWorld() != group.fissures.getFirst().points.getFirst().getWorld());
            if (mine.isEmpty()) {
                it.remove();
                continue;
            }
            for (final Group group : mine) {
                for (final Fissure fissure : group.fissures) {
                    if (fissure.travelling()) {
                        final double before = fissure.front;
                        fissure.front = Math.min(fissure.points.size() - 1, fissure.front + advance);
                        travel(fissure, before, fissure.front);
                    } else if ((now + group.hashCode()) % 10 == 0) {
                        idle(fissure);
                    }
                }
            }
        }
        for (final Iterator<Aftershock> it = aftershocks.iterator(); it.hasNext(); ) {
            final Aftershock shock = it.next();
            final Fissure fissure = shock.fissure;
            if (!fissure.group.owner.isOnline() || fissure.until <= now) {
                it.remove();
                continue;
            }
            final double before = shock.spread;
            shock.spread += AFTERSHOCK_STEP_PER_TICK / SPACING;
            spread(shock, before, shock.spread);
            if (shock.done()) {
                it.remove();
            }
        }
        if (groups.isEmpty() && aftershocks.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 地裂從 before 延伸到 after（取樣點的索引）：打新蓋到的那段上的敵人（同一組每隻一下）。 */
    private void travel(final Fissure fissure, final double before, final double after) {
        if (after < 0) {
            return;
        }
        final int from = Math.max(0, (int) Math.floor(Math.max(0, before)));
        final int to = (int) Math.min(fissure.points.size() - 1, Math.ceil(after));
        if (to <= from && before >= 0) {
            return;
        }
        final Player owner = fissure.group.owner;
        final Location a = fissure.points.get(from);
        final Location b = fissure.points.get(Math.max(from, to));
        targets.strike(owner, fissure.profile, SkillTargets.segment(a, b, fissure.radius), fissure.group);
        for (final LivingEntity target : targets.alongLine(a, b, fissure.radius, owner)) {
            if (fissure.group.hit.add(target.getUniqueId())) {
                damage.hit(owner, target, fissure.profile, null);
            }
        }
        for (int i = from; i <= to; i++) {
            final Location point = fissure.points.get(i);
            Fx.spawn(point.getWorld(), Particle.LAVA, point.clone().add(0, 0.1, 0), 1, 0.15, 0, 0.15, 0);
            Fx.spawn(point.getWorld(), Particle.FLAME, point.clone().add(0, 0.15, 0), 2, fissure.radius * 0.4, 0.05, fissure.radius * 0.4, 0.01);
        }
    }

    /** 留在地上的地裂：每 0.5 秒冒一點煙火（不打傷害）。 */
    private void idle(final Fissure fissure) {
        for (int i = 0; i < fissure.points.size(); i += 2) {
            final Location point = fissure.points.get(i);
            Fx.spawn(point.getWorld(), Particle.SMALL_FLAME, point.clone().add(0, 0.1, 0), 1, 0.12, 0, 0.12, 0);
        }
    }

    /** 餘震從 start 往兩邊擴散 before → after：打新蓋到的取樣點附近的敵人（這道餘震每隻一下）。 */
    private void spread(final Aftershock shock, final double before, final double after) {
        final Fissure fissure = shock.fissure;
        final Player owner = fissure.group.owner;
        final int last = fissure.points.size() - 1;
        final int lowFrom = Math.max(0, (int) Math.floor(shock.start - after));
        final int lowTo = Math.max(0, (int) Math.ceil(shock.start - before));
        final int highFrom = Math.min(last, (int) Math.floor(shock.start + before));
        final int highTo = Math.min(last, (int) Math.ceil(shock.start + after));
        final SkillProfile profile = fissure.profile.scaled(1.0, "地裂餘震");
        hitRange(owner, fissure, shock, lowFrom, lowTo, profile);
        hitRange(owner, fissure, shock, highFrom, highTo, profile);
    }

    private void hitRange(final Player owner, final Fissure fissure, final Aftershock shock, final int from, final int to, final SkillProfile profile) {
        if (to < from) {
            return;
        }
        final Location a = fissure.points.get(from);
        final Location b = fissure.points.get(to);
        targets.strike(owner, profile, SkillTargets.segment(a, b, fissure.radius), shock);
        for (final LivingEntity target : targets.alongLine(a, b, fissure.radius, owner)) {
            if (shock.hit.add(target.getUniqueId())) {
                damage.hit(owner, target, profile, null);
            }
        }
        for (int i = from; i <= to; i++) {
            final Location point = fissure.points.get(i);
            Fx.spawn(point.getWorld(), Particle.LAVA, point.clone().add(0, 0.2, 0), 2, 0.2, 0.1, 0.2, 0);
        }
    }
}
