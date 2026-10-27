package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.GrantedSkillKeys;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-28 照 PoE2 0.5 Volatile Dead（屍焰爆；RePoE poe2 VolatileDeadPlayer、poe2db）：消耗 1 具屍體，做出 1 顆不穩定的核心——
 * 核心會去找敵人，身邊 1.5 格（吃範圍）內的敵人一直被點燃（「像造成 x 到 y 點火焰傷害一樣」點燃，逐級，見 {@link AsThough}），
 * 5 秒（吃持續時間）到了在原地爆炸，2.2 格（吃範圍）內的敵人受到這個技能的火焰傷害（爆炸的表很小，傷害主要在點燃）。
 * 這次之前屍焰爆是直接引爆屍體，拿點燃的那張表當擊中傷害、25% 點燃機率。
 */
final class VolatileCores {

    /** 核心持續 5 秒（base_skill_effect_duration 5000）。 */
    static final long DURATION_TICKS = 100;
    /** 點燃的範圍 1.5 格（VolatileDeadIgniteAuraPlayer 的 active_skill_base_area_of_effect_radius 15）。 */
    static final double IGNITE_RADIUS = 1.5;
    /** 每 10 tick 對身邊的敵人補一次點燃。 */
    static final long IGNITE_EVERY = 10;
    /** 核心每 tick 最多移動幾格（原作沒寫數值，這裡 4 格／秒）。 */
    static final double SPEED = 0.2;
    /** 核心找敵人的距離（格）。 */
    static final double SEEK_RANGE = 12;
    /** 追到這麼近就不再往前（貼著敵人轉）。 */
    static final double STOP_DISTANCE = 0.4;
    /** 每個玩家同時最多幾顆（防止刷太多拖慢伺服器）。 */
    static final int MAX_PER_PLAYER = 30;

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final SkillDamage damage;
    private final SkillVisuals visuals;
    private final Map<UUID, Integer> counts = new HashMap<>();

    VolatileCores(final ExileCore plugin, final SkillTargets targets, final SkillDamage damage, final SkillVisuals visuals) {
        this.plugin = plugin;
        this.targets = targets;
        this.damage = damage;
        this.visuals = visuals;
    }

    /** 這個技能是不是屍焰爆的核心（消耗屍體時做核心、不直接爆）。 */
    static boolean is(final StatSheet effective) {
        return StatKeys.flag(effective, GrantedSkillKeys.FLAG_VOLATILE_CORE);
    }

    /** 消耗幾具屍體做出幾顆核心：一具一顆（原作 volatile_dead_base_number_of_corpses_to_consume 1）。純函式（測試用）。 */
    static int cores(final int corpses) {
        return Math.max(0, corpses);
    }

    /** 核心持續幾 tick：5 秒 × 持續時間倍率。純函式（測試用）。 */
    static long duration(final double durationScale) {
        return Math.max(1, Math.round(DURATION_TICKS * Math.max(0.1, durationScale)));
    }

    /** 這一 tick（核心出現後第 age tick）要不要補點燃：出現的那一刻就點，之後每 10 tick。純函式（測試用）。 */
    static boolean igniteDue(final long age) {
        return age >= 0 && age % IGNITE_EVERY == 0;
    }

    /**
     * 核心往目標移動一步：最多 step 格，離目標剩 stop 格以內就不動（不會穿過去）。pos、target 是 {x, y, z}，回傳新的位置。純函式（測試用）。
     */
    static double[] seek(final double[] pos, final double[] target, final double step, final double stop) {
        final double dx = target[0] - pos[0];
        final double dy = target[1] - pos[1];
        final double dz = target[2] - pos[2];
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= stop || distance < 1e-9) {
            return pos.clone();
        }
        final double move = Math.min(step, distance - stop);
        return new double[] {pos[0] + dx / distance * move, pos[1] + dy / distance * move, pos[2] + dz / distance * move};
    }

    /** 從屍體的位置做出一顆核心（已經消耗掉屍體）。 */
    void spawn(final Player owner, final SkillBuild build, final Location at) {
        if (owner == null || at.getWorld() == null || counts.getOrDefault(owner.getUniqueId(), 0) >= MAX_PER_PLAYER) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(owner);
        final SkillProfile explosion = build.profile();
        final StatSheet effective = explosion.effective(sheet);
        final double[] range = AsThough.range(effective, explosion.tags());
        final SkillProfile igniteProfile = AsThough.profile(explosion, DamageType.FIRE, range[0], range[1]);
        final double fire = AsThough.amount(sheet, igniteProfile, DamageType.FIRE);
        final StatSheet igniteSheet = igniteProfile.effective(sheet);
        final long lifetime = duration(build.durationScale());
        final double igniteRadius = IGNITE_RADIUS * Math.max(0.1, build.areaScale());
        final double blast = build.radius();
        final String visual = build.definition().visual();
        final Location core = at.clone().add(0, 0.8, 0);
        final World world = core.getWorld();
        counts.merge(owner.getUniqueId(), 1, Integer::sum);
        world.playSound(core, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.6f, 0.7f);
        final BukkitTask[] task = new BukkitTask[1];
        final long[] age = {0};
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            final long now = age[0]++;
            final boolean ownerGone = !owner.isOnline() || owner.isDead() || !owner.getWorld().equals(world);
            if (ownerGone || now >= lifetime) {
                task[0].cancel();
                counts.computeIfPresent(owner.getUniqueId(), (k, v) -> v <= 1 ? null : v - 1);
                if (!ownerGone) {
                    explode(owner, explosion, core, blast, visual);
                }
                return;
            }
            // 找最近的敵人追過去
            LivingEntity nearest = null;
            double best = Double.MAX_VALUE;
            for (final LivingEntity enemy : targets.around(core, SEEK_RANGE, owner)) {
                final double d = enemy.getLocation().distanceSquared(core);
                if (d < best) {
                    best = d;
                    nearest = enemy;
                }
            }
            if (nearest != null) {
                final Location aim = SkillTargets.center(nearest);
                final double[] next = seek(new double[] {core.getX(), core.getY(), core.getZ()},
                        new double[] {aim.getX(), aim.getY(), aim.getZ()}, SPEED, STOP_DISTANCE);
                final Location moved = new Location(world, next[0], next[1], next[2]);
                if (moved.getBlock().isPassable()) {
                    core.setX(next[0]);
                    core.setY(next[1]);
                    core.setZ(next[2]);
                }
            }
            if (igniteDue(now) && fire > 0 && plugin.ailments() != null) {
                for (final LivingEntity victim : targets.around(core, igniteRadius, owner)) {
                    plugin.ailments().igniteFrom(owner, victim, fire, igniteSheet);
                }
            }
            if (now % 2 == 0) {
                Fx.spawn(world, Particle.FLAME, core, 4, 0.12, 0.12, 0.12, 0.01);
                Fx.spawn(world, Particle.SMALL_FLAME, core, 2, igniteRadius / 3, 0.1, igniteRadius / 3, 0.0);
            }
        }, 1L, 1L);
    }

    /** 時間到：原地爆炸，打 blast 格內的敵人（爆炸的那張表，火焰）。 */
    private void explode(final Player owner, final SkillProfile profile, final Location at, final double blast, final String visual) {
        if (damage != null) {
            for (final LivingEntity target : targets.around(at, blast, owner)) {
                damage.hit(owner, target, profile, null);
            }
        }
        targets.strike(owner, profile, SkillTargets.sphere(at, blast), new Object());
        visuals.detonate(visual, at, blast);
    }

    /** 這個玩家現在有幾顆核心（測試、除錯用）。 */
    int active(final UUID player) {
        return counts.getOrDefault(player, 0);
    }
}
