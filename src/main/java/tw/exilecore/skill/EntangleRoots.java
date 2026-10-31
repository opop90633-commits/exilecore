package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-30 照 PoE2 Entangle（縛根，poe2db／RePoE EntanglePlayer）：往前爬的一道纏根裂隙。
 * <ul>
 *   <li>裂隙長 8 公尺，從腳前往面向爬出去，打中路上的敵人一次（裂隙的傷害）。</li>
 *   <li>裂隙存在 8 秒；期間每 0.1 秒在 1.5 公尺內的一個敵人身上纏一條藤蔓（藤蔓的傷害，一次擊中）。</li>
 *   <li>每條藤蔓讓敵人減速 15%，最多纏 3 條，最多纏 1 秒，被拉離纏住的點超過 2 公尺就斷掉。</li>
 *   <li>同時最多 4～13 道（照等級；吃「上限」）；多的收掉最早的。</li>
 * </ul>
 * 這次之前縛根是詛咒（6 秒內受到的物理傷害增加、會跟其他詛咒互相頂掉），原作沒有詛咒標籤也不是詛咒。
 * 繁茂（{@link Overgrowth}：積雷雲範圍內、無限生長的機率）的裂隙消失慢 25%、每個敵人多纏 3 條藤蔓。
 */
final class EntangleRoots {

    static final String ID = "root_bind";
    /** 每 0.1 秒纏一條。 */
    static final long VINE_EVERY_TICKS = 2;
    /** 藤蔓最多纏 1 秒。 */
    static final long VINE_TICKS = 20;
    /** 每個敵人最多 3 條。 */
    static final int MAX_VINES = 3;
    /** 每條減速 15%。 */
    static final double SLOW_PER_VINE = 0.15;
    /** 藤蔓拉長到 2 公尺就斷（吃範圍）。 */
    static final double VINE_LENGTH_METRES = 2.0;
    /** 裂隙爬完全長要幾 tick。 */
    static final long CRAWL_TICKS = 10;
    /** 裂隙本身打中的寬度（半徑，格）。 */
    static final double PATH_RADIUS = 0.8;
    /** 照 RePoE：同時最多幾道（1～40 級）。 */
    static final int[] LIMIT = {4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8, 9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11, 11,
        12, 12, 12, 12, 13, 13, 13, 13};
    /** 照 RePoE：藤蔓每一下的基礎傷害 ÷ 裂隙的基礎傷害（平均；1～40 級）。 */
    private static final int[][] FISSURE = {{4, 6}, {5, 8}, {7, 10}, {9, 13}, {11, 17}, {14, 21}, {17, 25}, {20, 30}, {24, 36}, {28, 42},
        {32, 49}, {38, 57}, {44, 65}, {50, 75}, {58, 86}, {66, 99}, {75, 113}, {86, 129}, {98, 147}, {111, 167}, {127, 190}, {144, 216},
        {164, 246}, {186, 279}, {211, 317}, {240, 360}, {272, 408}, {309, 464}, {351, 527}, {400, 599}, {454, 682}, {517, 776}, {589, 883},
        {671, 1006}, {765, 1148}, {873, 1310}, {997, 1496}, {1140, 1710}, {1305, 1957}, {1494, 2241}};
    private static final int[][] VINE = {{1, 1}, {1, 2}, {2, 2}, {2, 3}, {3, 4}, {3, 5}, {4, 6}, {5, 7}, {6, 8}, {7, 10}, {8, 12}, {9, 14},
        {11, 16}, {12, 18}, {14, 21}, {16, 24}, {19, 28}, {22, 33}, {25, 37}, {29, 43}, {33, 49}, {38, 57}, {43, 65}, {50, 75}, {57, 86},
        {66, 99}, {76, 114}, {87, 131}, {100, 150}, {116, 173}, {133, 200}, {154, 230}, {177, 266}, {205, 308}, {237, 356}, {275, 412},
        {319, 478}, {370, 555}, {430, 645}, {501, 751}};

    /** 這個等級的同時上限（不含「上限」加成）。 */
    static int limit(final int level) {
        return LIMIT[Math.max(1, Math.min(LIMIT.length, level)) - 1];
    }

    /** 藤蔓一下是裂隙一下的幾倍。 */
    static double vineRatio(final int level) {
        final int i = Math.max(1, Math.min(VINE.length, level)) - 1;
        return (VINE[i][0] + VINE[i][1]) / (double) (FISSURE[i][0] + FISSURE[i][1]);
    }

    /** 一道裂隙。 */
    static final class Root {
        final UUID owner;
        final List<Location> points;
        final long born;
        long until;
        /** 繁茂了（{@link Overgrowth}）。 */
        boolean overgrown;
        final double attachRadius;
        final double vineLength;
        SkillProfile fissure;
        SkillProfile vine;
        final Set<UUID> struck = new HashSet<>();
        int revealed;
        long nextVine;
        /** 2026-10-31：裂隙路徑打到的寬度（吃範圍效果；這次之前固定 PATH_RADIUS）。 */
        double pathRadius = PATH_RADIUS;

        Root(final UUID owner, final List<Location> points, final long born, final long until, final double attachRadius, final double vineLength,
             final SkillProfile fissure, final SkillProfile vine) {
            this.owner = owner;
            this.points = points;
            this.born = born;
            this.until = until;
            this.attachRadius = attachRadius;
            this.vineLength = vineLength;
            this.fissure = fissure;
            this.vine = vine;
        }
    }

    /** 一條纏著的藤蔓。 */
    private record Vine(Root root, Location anchor, long until) {
    }

    private final List<Root> roots = new ArrayList<>();
    /** 敵人 → 身上的藤蔓。 */
    private final Map<UUID, List<Vine>> vines = new HashMap<>();

    void add(final Root root, final int limit) {
        final List<Root> mine = new ArrayList<>();
        for (final Root existing : roots) {
            if (existing.owner.equals(root.owner)) {
                mine.add(existing);
            }
        }
        for (int i = 0; i <= mine.size() - Math.max(1, limit); i++) {
            remove(mine.get(i));
        }
        roots.add(root);
    }

    private void remove(final Root root) {
        roots.remove(root);
        for (final List<Vine> list : vines.values()) {
            list.removeIf(vine -> vine.root == root);
        }
    }

    void forget(final UUID owner) {
        for (final Root root : List.copyOf(roots)) {
            if (root.owner.equals(owner)) {
                remove(root);
            }
        }
    }

    void clear() {
        roots.clear();
        vines.clear();
    }

    boolean isEmpty() {
        return roots.isEmpty() && vines.isEmpty();
    }

    /** 每 tick：裂隙往前爬、打路上的敵人；每 0.1 秒纏一條藤蔓；藤蔓到期或拉斷；更新減速。 */
    void tick(final long now, final org.bukkit.Server server, final SkillTargets targets, final tw.exilecore.combat.SkillDamage damage,
              final tw.exilecore.combat.Debuffs debuffs, final java.util.function.BiPredicate<UUID, Location> thunderstorm,
              final java.util.function.Consumer<Root> overgrew) {
        final Iterator<Root> iterator = roots.iterator();
        final List<Root> expired = new ArrayList<>();
        while (iterator.hasNext()) {
            final Root root = iterator.next();
            final Player owner = server.getPlayer(root.owner);
            if (owner == null || now >= root.until || root.points.isEmpty() || !owner.getWorld().equals(root.points.getFirst().getWorld())) {
                expired.add(root);
                continue;
            }
            // 往前爬：爬到的那幾段打一次路上的敵人
            final int shown = (int) Math.min(root.points.size(), Math.ceil(root.points.size() * Math.min(1.0, (now - root.born + 1) / (double) CRAWL_TICKS)));
            for (int i = root.revealed; i < shown; i++) {
                final Location point = root.points.get(i);
                Fx.spawn(point.getWorld(), Particle.HAPPY_VILLAGER, point.clone().add(0, 0.15, 0), 5, 0.3, 0.05, 0.3, 0);
                for (final LivingEntity target : targets.around(point, root.pathRadius, owner)) {
                    if (root.struck.add(target.getUniqueId())) {
                        damage.hit(owner, target, root.fissure, null);
                    }
                }
            }
            root.revealed = shown;
            // 繁茂：積雷雲範圍內（每 0.5 秒看一次裂隙中間）；剩下的時間 ÷ 0.75（消失慢 25%）、每個敵人多纏 3 條
            if (!root.overgrown && (now - root.born) % 10 == 0 && thunderstorm.test(root.owner, root.points.get(root.points.size() / 2))) {
                overgrow(root, now, overgrew);
            }
            if ((now - root.born) % 10 == 0) {
                for (int i = 0; i < root.revealed; i += 2) {
                    final Location point = root.points.get(i);
                    Fx.spawn(point.getWorld(), Particle.COMPOSTER, point.clone().add(0, 0.15, 0), 1, 0.25, 0.02, 0.25, 0);
                }
            }
            // 每 0.1 秒纏一條：範圍內藤蔓最少的敵人（一樣多挑最近的）
            if (now >= root.nextVine && root.revealed > 0) {
                root.nextVine = now + VINE_EVERY_TICKS;
                LivingEntity pick = null;
                Location anchor = null;
                final int cap = maxVines(root);
                int fewest = cap;
                double best = Double.MAX_VALUE;
                for (int i = 0; i < root.revealed; i++) {
                    final Location point = root.points.get(i);
                    for (final LivingEntity target : targets.around(point, root.attachRadius, owner)) {
                        final int count = count(target.getUniqueId(), now);
                        final double distance = target.getLocation().distanceSquared(point);
                        if (count < fewest || count == fewest && count < cap && distance < best) {
                            fewest = count;
                            best = distance;
                            pick = target;
                            anchor = point;
                        }
                    }
                }
                if (pick != null && fewest < cap) {
                    vines.computeIfAbsent(pick.getUniqueId(), id -> new ArrayList<>()).add(new Vine(root, anchor, now + VINE_TICKS));
                    damage.hit(owner, pick, root.vine, null);
                    drawVine(anchor, pick);
                }
            }
        }
        for (final Root root : expired) {
            remove(root);
        }
        // 藤蔓：到期、敵人死了、被拉離纏住的點太遠就斷；剩幾條就減速幾成
        final Iterator<Map.Entry<UUID, List<Vine>>> entries = vines.entrySet().iterator();
        while (entries.hasNext()) {
            final Map.Entry<UUID, List<Vine>> entry = entries.next();
            final org.bukkit.entity.Entity entity = server.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                entries.remove();
                continue;
            }
            entry.getValue().removeIf(vine -> now >= vine.until || !vine.anchor.getWorld().equals(living.getWorld())
                    || vine.anchor.distanceSquared(living.getLocation()) > vine.root.vineLength * vine.root.vineLength);
            if (entry.getValue().isEmpty()) {
                entries.remove();
                continue;
            }
            final int count = Math.min(MAX_VINES + Overgrowth.ENTANGLE_EXTRA_VINES, entry.getValue().size());
            if (debuffs != null) {
                debuffs.apply(entry.getKey(), new tw.exilecore.combat.Debuffs.Debuff("root_bind.vines", entry.getValue().getFirst().root.owner,
                        now + 3, 0, false, null, List.of(), SLOW_PER_VINE * count));
            }
            if (now % 5 == 0) {
                for (final Vine vine : entry.getValue()) {
                    drawVine(vine.anchor, living);
                }
            }
        }
    }

    /** 這道裂隙每個敵人最多纏幾條（繁茂多 3 條）。 */
    static int maxVines(final Root root) {
        return MAX_VINES + (root.overgrown ? Overgrowth.ENTANGLE_EXTRA_VINES : 0);
    }

    /** 繁茂：剩下的時間 ÷ 0.75。 */
    static void overgrow(final Root root, final long now, final java.util.function.Consumer<Root> overgrew) {
        root.overgrown = true;
        root.until = now + Math.round(Math.max(0, root.until - now) / (1 - Overgrowth.ENTANGLE_SLOWER));
        overgrew.accept(root);
    }

    private int count(final UUID target, final long now) {
        final List<Vine> list = vines.get(target);
        if (list == null) {
            return 0;
        }
        int count = 0;
        for (final Vine vine : list) {
            if (vine.until > now) {
                count++;
            }
        }
        return count;
    }

    private static void drawVine(final Location anchor, final LivingEntity target) {
        final Location to = target.getLocation().add(0, target.getHeight() * 0.4, 0);
        if (!anchor.getWorld().equals(to.getWorld())) {
            return;
        }
        final Vector step = to.toVector().subtract(anchor.toVector());
        final int steps = Math.max(2, (int) Math.ceil(step.length() / 0.35));
        step.multiply(1.0 / steps);
        final Location at = anchor.clone().add(0, 0.1, 0);
        for (int i = 0; i <= steps; i++) {
            Fx.spawn(at.getWorld(), Particle.COMPOSTER, at, 1, 0, 0, 0, 0);
            at.add(step);
        }
    }
}
