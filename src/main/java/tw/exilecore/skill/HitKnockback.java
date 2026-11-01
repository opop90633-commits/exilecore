package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 2026-11-01 照 PoE2（base_knockback_distance）：技能自己的擊中擊退，給投射物這類「SkillDamage.hit 傳 null、本來不擊退」的擊中用。
 * <p>
 * skills.yml 寫在技能最上層（要用的技能才寫，沒寫的完全不變）：
 * <pre>
 *   hit_knockback: 4                              # 投射物直接打中擊退 4 公尺（PoE2 base_knockback_distance 400 ÷ 100）
 *   hit_knockback: { distance: 4, explosion: true } # 爆炸（投射物停下來炸的那一圈）也擊退，從爆炸中心往外推
 * </pre>
 * 距離照公尺寫（讀進來乘 {@link tw.exilecore.core.Units#BLOCKS_PER_METRE}），再吃技能、輔助的 skill.knockback（FLAT 加、增加／更多乘）。
 * 方向：投射物照飛的方向（水平），爆炸照中心往外；換成 Minecraft 的速度照 {@link #speedFor(double)}（照原版的摩擦模擬推多遠）。
 * Boss（擊退抗性 100%）由 SkillDamage 的擊退事件照抗性縮放，推不動。
 */
public final class HitKnockback {

    /** 一個技能的擊退設定：distance 格；explosion = 爆炸也擊退。 */
    public record Spec(double distance, boolean explosion) {
    }

    private static volatile Map<String, Spec> specs = Map.of();

    /** 擊退時往上抬的速度（格／tick；離地才滑得遠，跟 SkillExecutor 的擊退一樣 0.3）。 */
    static final double LIFT = 0.3;
    /** 很短的擊退（不到 1 格）只抬一點點，看起來是被頂一下，不是跳起來。 */
    static final double SMALL_LIFT = 0.1;

    private HitKnockback() {
    }

    /** 讀一個技能的 hit_knockback（沒寫回傳 null）。 */
    static Spec parse(final YamlNode skill) {
        if (!skill.has("hit_knockback")) {
            return null;
        }
        final Object raw = skill.raw("hit_knockback");
        final double metres;
        boolean explosion = false;
        if (raw instanceof Number number) {
            metres = number.doubleValue();
        } else {
            final YamlNode node = skill.node("hit_knockback");
            metres = node.decimal("distance", 0);
            explosion = node.bool("explosion", false);
        }
        if (!(metres > 0) || metres > 30) {
            throw new IllegalArgumentException(skill.path() + ".hit_knockback：距離要在 0 到 30 格之間，卻是 " + metres);
        }
        return new Spec(metres * tw.exilecore.core.Units.BLOCKS_PER_METRE, explosion);
    }

    /** 換上新讀的設定（SkillConfig.load；技能代號 → 設定）。 */
    static void install(final Map<String, Spec> parsed) {
        specs = Map.copyOf(parsed);
    }

    /** 這個技能的設定（沒有回傳 null）。 */
    public static Spec get(final String skillId) {
        return skillId == null ? null : specs.get(skillId);
    }

    /** 測試用：目前讀進來的全部設定。 */
    static Map<String, Spec> all() {
        return new HashMap<>(specs);
    }

    /** 基礎距離吃 skill.knockback（FLAT 加在基礎上，再乘增加、更多）；沒有屬性表照基礎。純函式。 */
    static double distance(final double base, final StatSheet effective, final Set<String> tags) {
        if (effective == null) {
            return base;
        }
        return Math.max(0, effective.apply(StatKeys.SKILL_KNOCKBACK, base + effective.flat(StatKeys.SKILL_KNOCKBACK, tags), tags));
    }

    /** 往上抬多少（短的擊退抬得少）。純函式。 */
    static double lift(final double blocks) {
        return blocks < 1 ? SMALL_LIFT : LIFT;
    }

    /**
     * 照原版的實體物理推多遠：每 tick 先移動，再乘摩擦（空中 0.91、地上 0.546），垂直速度 (vy − 0.08) × 0.98，落地停止垂直。
     * 水平距離跟水平初速成正比，所以 1 格／tick 的初速推幾格算一次就好。純函式。
     */
    static double blocksPerSpeed(final double lift) {
        double y = 0;
        double vy = lift;
        double h = 1.0;
        double travelled = 0;
        boolean ground = lift <= 0;
        for (int tick = 0; tick < 200 && h > 1e-4; tick++) {
            travelled += h;
            if (!ground) {
                y += vy;
                vy = (vy - 0.08) * 0.98;
                if (y <= 0) {
                    ground = true;
                }
            }
            h *= ground ? 0.546 : 0.91;
        }
        return travelled;
    }

    /** 要推 blocks 格：水平初速（格／tick）。純函式。 */
    static double speedFor(final double blocks) {
        if (!(blocks > 0)) {
            return 0;
        }
        return blocks / blocksPerSpeed(lift(blocks));
    }

    /** 往 direction（水平）推 blocks 格的速度向量；方向是零向量時回傳 null。純函式。 */
    static Vector velocity(final Vector direction, final double blocks) {
        if (direction == null || !(blocks > 0)) {
            return null;
        }
        final Vector flat = direction.clone().setY(0);
        if (flat.lengthSquared() < 1e-9) {
            return null;
        }
        return flat.normalize().multiply(speedFor(blocks)).setY(lift(blocks));
    }

    /**
     * 投射物直接打中：照投射物飛的方向推（方向不能用時照施放者 → 目標）。這個技能沒寫 hit_knockback 回傳 null（照舊不擊退）。
     */
    static Vector projectile(final String skillId, final Location owner, final LivingEntity target, final Vector flying,
                             final StatSheet effective, final Set<String> tags) {
        final Spec spec = get(skillId);
        if (spec == null) {
            return null;
        }
        final double blocks = distance(spec.distance(), effective, tags);
        Vector direction = flying == null ? null : flying.clone().setY(0);
        if ((direction == null || direction.lengthSquared() < 1e-9) && owner != null) {
            direction = target.getLocation().toVector().subtract(owner.toVector());
        }
        return velocity(direction, blocks);
    }

    /** 爆炸：寫了 explosion: true 的才擊退，從爆炸中心往外推（站在正中間的照施放者 → 目標）。 */
    static Vector explosion(final String skillId, final Location center, final Location owner, final LivingEntity target,
                            final StatSheet effective, final Set<String> tags) {
        final Spec spec = get(skillId);
        if (spec == null || !spec.explosion()) {
            return null;
        }
        Vector direction = target.getLocation().toVector().subtract(center.toVector()).setY(0);
        if (direction.lengthSquared() < 0.04 && owner != null) {
            direction = target.getLocation().toVector().subtract(owner.toVector());
        }
        return velocity(direction, distance(spec.distance(), effective, tags));
    }
}
