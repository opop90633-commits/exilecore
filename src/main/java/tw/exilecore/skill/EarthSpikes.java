package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.core.Units;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-31 照站長：地刺崩裂（PoE2 Earthshatter）照原作做：
 * <ul>
 *   <li>錘子砸出一條裂縫，裂縫上 {@link #FISSURE_RADIUS} 格內的敵人吃一下（技能本身的傷害）；</li>
 *   <li>裂縫盡頭冒出一根地刺，留在地上 {@link #SPIKE_TICKS} tick，最多 {@link #LIMIT} 根；</li>
 *   <li>超過 {@link #LIMIT} 根時最舊的那根自己震碎爆炸；在地刺附近放戰吼也會把地刺震碎，戰吼震碎的爆炸更多 50% 傷害；</li>
 *   <li>爆炸半徑 {@link #BLAST_RADIUS} 格，傷害照 skills.yml 的 aftershock.more（140%→507% ÷ 45%→140%）；時間到自己消失的不爆。</li>
 * </ul>
 */
final class EarthSpikes {

    static final String ID = "earth_spike_eruption";
    static final long SPIKE_TICKS = 120;
    static final int LIMIT = 6;
    /** 戰吼震碎的爆炸更多幾成。 */
    static final double WARCRY_MORE = 0.5;
    static final double FISSURE_LENGTH = Units.metres(5);
    static final double FISSURE_RADIUS = Units.metres(1);
    static final double BLAST_RADIUS = Units.metres(2);

    private static final class Spike {
        final Player owner;
        final Location at;
        final SkillBuild build;
        final long until;
        final BlockDisplay display;

        Spike(final Player owner, final Location at, final SkillBuild build, final long until, final BlockDisplay display) {
            this.owner = owner;
            this.at = at;
            this.build = build;
            this.until = until;
            this.display = display;
        }
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final SkillDamage damage;
    private final Map<UUID, ArrayDeque<Spike>> spikes = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask task;

    EarthSpikes(final ExileCore plugin, final SkillTargets targets, final SkillVisuals visuals, final SkillDamage damage) {
        this.plugin = plugin;
        this.targets = targets;
        this.visuals = visuals;
        this.damage = damage;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 這個人地上還有幾根地刺（品質：每根更多 x% 擊中傷害）。 */
    int count(final UUID owner) {
        final ArrayDeque<Spike> mine = spikes.get(owner);
        if (mine == null) {
            return 0;
        }
        final long now = now();
        int alive = 0;
        for (final Spike spike : mine) {
            if (spike.until > now) {
                alive++;
            }
        }
        return alive;
    }

    /** 砸一下：裂縫打一條、盡頭留一根地刺。 */
    void cast(final Origin origin, final SkillBuild build) {
        final Player owner = origin.owner();
        final Vector facing = origin.facing().clone().setY(0);
        if (facing.lengthSquared() < 1e-6) {
            facing.setX(1);
        }
        facing.normalize();
        final double scale = build.areaScale();
        final Location from = origin.feet().clone();
        final Location to = from.clone().add(facing.clone().multiply(FISSURE_LENGTH * scale));
        to.setY(groundY(to, from.getY()));
        final double width = FISSURE_RADIUS * scale;
        if (owner != null) {
            targets.strike(owner, build.profile(), point -> distanceToSegment(point, from, to) <= width, new Object());
            for (final LivingEntity target : targets.alongLine(from, to, width, owner)) {
                damage.hit(owner, target, build.profile(), null);
            }
        }
        final int steps = Math.max(2, (int) Math.round(from.distance(to) / 1.2));
        for (int i = 1; i <= steps; i++) {
            visuals.impact(build.definition().visual(), from.clone().add(to.clone().subtract(from).toVector().multiply(i / (double) steps)));
        }
        from.getWorld().playSound(from, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1f, 0.7f);
        if (owner != null) {
            raise(owner, to, build);
        }
    }

    private static double groundY(final Location at, final double fallback) {
        final org.bukkit.World world = at.getWorld();
        for (int dy = 1; dy >= -3; dy--) {
            final org.bukkit.block.Block below = world.getBlockAt(at.getBlockX(), (int) Math.floor(fallback) + dy - 1, at.getBlockZ());
            if (below.getType().isSolid() && !below.getRelative(0, 1, 0).getType().isSolid()) {
                return below.getY() + 1;
            }
        }
        return fallback;
    }

    private static double distanceToSegment(final Location point, final Location a, final Location b) {
        final Vector ab = b.toVector().subtract(a.toVector()).setY(0);
        final Vector ap = point.toVector().subtract(a.toVector()).setY(0);
        final double len = ab.lengthSquared();
        final double t = len <= 1e-9 ? 0 : Math.max(0, Math.min(1, ap.dot(ab) / len));
        return ap.subtract(ab.multiply(t)).length();
    }

    private void raise(final Player owner, final Location at, final SkillBuild build) {
        final BlockDisplay display = at.getWorld().spawn(at.clone().add(-0.45, 0, -0.45), BlockDisplay.class, d -> {
            final PointedDripstone data = (PointedDripstone) Material.POINTED_DRIPSTONE.createBlockData();
            data.setThickness(PointedDripstone.Thickness.TIP);
            data.setVerticalDirection(org.bukkit.block.BlockFace.UP);
            d.setBlock(data);
            d.setPersistent(false);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.9f, 1.8f, 0.9f), new Quaternionf()));
        });
        at.getWorld().playSound(at, Sound.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 1f, 0.8f);
        final ArrayDeque<Spike> mine = spikes.computeIfAbsent(owner.getUniqueId(), id -> new ArrayDeque<>());
        mine.addLast(new Spike(owner, at.clone(), build, now() + SPIKE_TICKS, display));
        while (mine.size() > LIMIT) {
            shatter(mine.pollFirst(), 1.0); // 超過上限：最舊的那根自己震碎爆炸
        }
        if (task == null) {
            task = plugin.repeat("skill.earth_spikes", this::tick, 10, 10);
        }
    }

    /** 放戰吼：中心 radius 格內自己的地刺全部震碎（爆炸更多 50%）。 */
    void warcry(final Player player, final Location center, final double radius) {
        final ArrayDeque<Spike> mine = spikes.get(player.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            return;
        }
        final List<Spike> hit = new java.util.ArrayList<>();
        for (final Iterator<Spike> it = mine.iterator(); it.hasNext(); ) {
            final Spike spike = it.next();
            if (spike.at.getWorld() == center.getWorld() && spike.at.distanceSquared(center) <= radius * radius) {
                it.remove();
                hit.add(spike);
            }
        }
        for (final Spike spike : hit) {
            shatter(spike, 1.0 + WARCRY_MORE);
        }
    }

    private void shatter(final Spike spike, final double more) {
        if (spike == null) {
            return;
        }
        if (spike.display.isValid()) {
            spike.display.remove();
        }
        if (!spike.owner.isOnline() || spike.owner.getWorld() != spike.at.getWorld()) {
            return;
        }
        final SkillBuild build = spike.build;
        final SkillParams.Aftershock aftershock = build.params().aftershock();
        final double blast = aftershock == null ? 1.0 : 1 + aftershock.more(build.level()) / 100.0;
        final SkillProfile profile = build.profile().scaled(blast * more, more > 1 ? "地刺碎裂（戰吼）" : "地刺碎裂");
        final double radius = BLAST_RADIUS * build.areaScale();
        final Location center = spike.at.clone().add(0, 0.5, 0);
        targets.strike(spike.owner, profile, SkillTargets.sphere(center, radius), new Object());
        for (final LivingEntity target : targets.around(center, radius, spike.owner)) {
            damage.hit(spike.owner, target, profile, null);
        }
        visuals.nova(build.definition().visual(), spike.at, radius);
        spike.at.getWorld().playSound(spike.at, Sound.BLOCK_POINTED_DRIPSTONE_BREAK, SoundCategory.PLAYERS, 1.2f, 0.6f);
    }

    /** 時間到的地刺消失（不爆）。 */
    private void tick() {
        final long now = now();
        for (final Iterator<Map.Entry<UUID, ArrayDeque<Spike>>> it = spikes.entrySet().iterator(); it.hasNext(); ) {
            final ArrayDeque<Spike> mine = it.next().getValue();
            mine.removeIf(spike -> {
                final boolean gone = spike.until <= now || !spike.owner.isOnline() || !spike.display.isValid();
                if (gone && spike.display.isValid()) {
                    spike.display.remove();
                }
                return gone;
            });
            if (mine.isEmpty()) {
                it.remove();
            }
        }
        if (spikes.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 關閉、重載：全部收掉。 */
    void clear() {
        for (final ArrayDeque<Spike> mine : spikes.values()) {
            for (final Spike spike : mine) {
                if (spike.display.isValid()) {
                    spike.display.remove();
                }
            }
        }
        spikes.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
