package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * 2026-11-01 照站長「要衝向目標的都要有鎖敵」：衝向目標的技能（鐵壁衝撞）開始時鎖定一個敵人——
 * 先找準心正指著的（射線有厚度），沒指到就找 range 內、離準心方向最近（夾角不超過 {@link #MAX_ANGLE} 度）、看得到的敵人。
 */
final class TargetLock {

    /** 沒指到時，離準心方向最多幾度內的敵人還算「準心附近」。 */
    static final double MAX_ANGLE = 25;
    /** 準心射線的厚度（格）。 */
    static final double RAY = 1.2;

    private TargetLock() {
    }

    /** 鎖一個敵人（沒有就 null）。 */
    static LivingEntity lock(final SkillTargets targets, final Origin origin, final double range) {
        final LivingEntity aimed = targets.aimed(origin, range, RAY);
        if (aimed != null) {
            return aimed;
        }
        final Location feet = origin.feet();
        final Vector look = origin.direction().clone().setY(0);
        if (look.lengthSquared() < 1e-6) {
            return null;
        }
        final List<LivingEntity> near = targets.around(feet, range, origin.owner());
        LivingEntity best = null;
        double bestAngle = Double.MAX_VALUE;
        for (final LivingEntity candidate : near) {
            final Location at = candidate.getLocation();
            final double angle = angleTo(look.getX(), look.getZ(), at.getX() - feet.getX(), at.getZ() - feet.getZ());
            if (angle <= MAX_ANGLE && angle < bestAngle && origin.canSee(candidate)) {
                best = candidate;
                bestAngle = angle;
            }
        }
        return best;
    }

    /** 水平方向 (lookX, lookZ) 跟 (dx, dz) 的夾角（度，0～180）；dx、dz 都是 0 當作 0 度。純函式（測試用）。 */
    static double angleTo(final double lookX, final double lookZ, final double dx, final double dz) {
        final double a = Math.hypot(lookX, lookZ);
        final double b = Math.hypot(dx, dz);
        if (a < 1e-9 || b < 1e-9) {
            return 0;
        }
        final double cos = Math.max(-1, Math.min(1, (lookX * dx + lookZ * dz) / (a * b)));
        return Math.toDegrees(Math.acos(cos));
    }

    /**
     * 衝鋒這一 tick 往哪裡跑：鎖定的目標還在就朝牠（水平），太近（已經貼上）或沒有就照準心的方向。純函式（測試用）。
     *
     * @return {x, z}（單位向量）
     */
    static double[] steer(final double fallbackX, final double fallbackZ, final double dx, final double dz, final boolean locked) {
        final double len = Math.hypot(dx, dz);
        if (!locked || len < 0.3) {
            final double f = Math.hypot(fallbackX, fallbackZ);
            return f < 1e-9 ? new double[] {1, 0} : new double[] {fallbackX / f, fallbackZ / f};
        }
        return new double[] {dx / len, dz / len};
    }
}
