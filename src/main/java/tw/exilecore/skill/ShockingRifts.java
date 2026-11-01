package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.SupportSystemKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 Electromagnetism（磁暴）觸發的雷隙（TriggeredShockingRiftPlayer）：
 * 半徑 2 格，把 2.5 格內的敵人往中心吸；每 0.85 秒脈衝一次（每用掉一個感電，脈衝速度增加 25%），共 5 次，
 * 每一波打半徑內的敵人並阻礙 0.9 秒；每脈衝過一次更多 20% 傷害，最後一波用自己的（比較高的）傷害表。每個玩家同時最多 3 道（多的最舊的消失）。
 * 這次之前雷隙是一次炸開的新星（proc_shock_rift）。
 */
final class ShockingRifts {

    private static final class Rift {
        final UUID owner;
        final Location center;
        final SkillBuild pulse;
        final SkillBuild last;
        final long interval;
        final double radius;
        final double pullRadius;
        final long born;
        int performed;
        long nextPulse;
        boolean gone;
        org.bukkit.scheduler.BukkitTask task;

        Rift(final UUID owner, final Location center, final SkillBuild pulse, final SkillBuild last, final long interval, final double radius,
             final double pullRadius, final long born) {
            this.owner = owner;
            this.center = center;
            this.pulse = pulse;
            this.last = last;
            this.interval = interval;
            this.radius = radius;
            this.pullRadius = pullRadius;
            this.born = born;
            this.nextPulse = born + interval;
        }
    }

    private final ExileCore plugin;
    private final SkillTargets targets;
    private final List<Rift> rifts = new ArrayList<>();

    ShockingRifts(final ExileCore plugin, final SkillTargets targets) {
        this.plugin = plugin;
        this.targets = targets;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /** 在 at 撕開一道雷隙（pulse 是一般脈衝、last 是最後一波；consumed 是開這道用掉了幾個感電）。 */
    void open(final Player owner, final SkillBuild pulse, final SkillBuild last, final Location at, final int consumed) {
        final List<Rift> mine = new ArrayList<>();
        for (final Rift rift : rifts) {
            if (!rift.gone && rift.owner.equals(owner.getUniqueId())) {
                mine.add(rift);
            }
        }
        while (mine.size() >= SupportSystemKeys.RIFT_LIMIT) {
            close(mine.removeFirst()); // 清單照開的順序，第一個最舊
        }
        final double radius = pulse.radius() > 0 ? pulse.radius() : 2.0;
        final double pull = SupportSystemKeys.RIFT_PULL_RADIUS * Math.max(0.1, pulse.areaScale());
        final Rift rift = new Rift(owner.getUniqueId(), at.clone(), pulse, last, SupportSystemKeys.riftIntervalTicks(consumed), radius, pull, now());
        rifts.add(rift);
        rift.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(rift), 1L, 1L);
        Fx.as(rift.owner, () -> Fx.disk(at.getWorld(), Particle.ELECTRIC_SPARK, at.clone().add(0, 0.3, 0), 24, radius, 0.4, 0.02));
    }

    /** 這個位置在不在任何一道雷隙裡（磁暴：已經在雷隙裡的感電敵人不算進觸發的條件）。 */
    boolean inRift(final Location at) {
        for (final Rift rift : rifts) {
            if (!rift.gone && rift.center.getWorld() != null && rift.center.getWorld().equals(at.getWorld())
                    && rift.center.distanceSquared(at) <= rift.radius * rift.radius) {
                return true;
            }
        }
        return false;
    }

    /** 主人登出：他的雷隙全部收掉。 */
    void forget(final UUID owner) {
        for (final Rift rift : new ArrayList<>(rifts)) {
            if (rift.owner.equals(owner)) {
                close(rift);
            }
        }
    }

    private void close(final Rift rift) {
        rift.gone = true;
        if (rift.task != null) {
            rift.task.cancel();
        }
        rifts.remove(rift);
    }

    private void tick(final Rift rift) {
        final Player owner = plugin.getServer().getPlayer(rift.owner);
        if (rift.gone || owner == null || !owner.isOnline() || !owner.getWorld().equals(rift.center.getWorld())) {
            close(rift);
            return;
        }
        final long now = now();
        if ((now - rift.born) % SupportSystemKeys.RIFT_PULL_EVERY == 0) {
            for (final LivingEntity target : targets.around(rift.center, rift.pullRadius, owner)) {
                if (target.isValid() && !target.isDead() && !(target instanceof Player)) {
                    pull(target, rift.center, SupportSystemKeys.RIFT_PULL_STRENGTH); // 吸引不拉玩家（同 SkillZones 的漩渦，PvP）
                }
            }
            Fx.as(rift.owner, () -> Fx.disk(rift.center.getWorld(), Particle.ELECTRIC_SPARK, rift.center.clone().add(0, 0.4, 0), 10,
                    rift.pullRadius, 0.3, 0.01));
        }
        if (now < rift.nextPulse) {
            return;
        }
        final boolean last = SupportSystemKeys.riftFinalPulse(rift.performed);
        final SkillBuild build = last ? rift.last : rift.pulse;
        final SkillProfile profile = build.profile().scaled(SupportSystemKeys.riftPulseMore(rift.performed), "雷隙脈衝");
        rift.performed++;
        rift.nextPulse = now + rift.interval;
        if (plugin.combat() != null && plugin.combat().skillDamage() != null) {
            for (final LivingEntity target : targets.around(rift.center, rift.radius, owner)) {
                if (rift.gone) {
                    break;
                }
                if (!target.isValid() || target.isDead()) {
                    continue;
                }
                plugin.combat().skillDamage().hit(owner, target, profile, null);
                if (plugin.ailments() != null && target.isValid() && !target.isDead()) {
                    plugin.ailments().skillPhysicalDot(target, 0, 0, rift.owner, SupportSystemKeys.RIFT_HINDER_TICKS); // 每一波阻礙 0.9 秒
                }
            }
        }
        final double radius = rift.radius;
        Fx.as(rift.owner, () -> Fx.disk(rift.center.getWorld(), last ? Particle.END_ROD : Particle.ELECTRIC_SPARK, rift.center.clone().add(0, 0.6, 0),
                last ? 40 : 30, radius, 0.6, 0.05));
        if (last) {
            close(rift);
        }
    }

    /** 往中心拉一把（Boss 這種推不動的照擊退抗性折扣；同 SkillZones.pull）。 */
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
        velocity.setY(0.05);
        target.setVelocity(target.getVelocity().multiply(0.3).add(velocity));
    }
}
