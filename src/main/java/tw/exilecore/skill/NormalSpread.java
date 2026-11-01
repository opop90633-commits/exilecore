package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * 2026-11-01 照 PoE2 Permafrost Bolts（PoB2：projectiles_normal_distribution_spread_standard_deviation 14、burst_shot_cone_angle 40）：
 * 寫了 spread_normal 的技能，一次射出的投射物不是在扇形裡平均排開，而是每一發各自照常態分布擲一個左右偏角（中間密、兩邊疏），
 * 偏角夾在 projectile.spread（錐形 40 度，左右各 20 度）裡；超出的重擲，擲幾次都超出才夾在邊上。
 * 輔助把扇形變寬／變窄時，標準差照同樣的比例縮放。沒寫的技能照舊（SkillMath.fan）。
 * <pre>
 *   spread_normal: { sd: 14, cone: 40 }   # 標準差 14 度；cone 是寫這個標準差時的錐形角度（跟 projectile.spread 一樣）
 * </pre>
 */
public final class NormalSpread {

    /** sd：標準差（度）；cone：寫 sd 時的錐形全角（度）。 */
    public record Spec(double sd, double cone) {
    }

    private static volatile Map<String, Spec> specs = Map.of();

    /** 超出錐形重擲幾次。 */
    static final int REROLLS = 8;

    private NormalSpread() {
    }

    static Spec parse(final YamlNode skill) {
        if (!skill.has("spread_normal")) {
            return null;
        }
        final YamlNode node = skill.node("spread_normal");
        final double sd = node.decimal("sd", 0);
        final double cone = node.decimal("cone", 0);
        if (!(sd > 0) || sd > 90 || !(cone > 0) || cone > 180) {
            throw new IllegalArgumentException(node.path() + "：sd 要在 0 到 90 度、cone 要在 0 到 180 度之間");
        }
        return new Spec(sd, cone);
    }

    static void install(final Map<String, Spec> parsed) {
        specs = Map.copyOf(parsed);
    }

    public static Spec get(final String skillId) {
        return skillId == null ? null : specs.get(skillId);
    }

    static Map<String, Spec> all() {
        return new HashMap<>(specs);
    }

    /**
     * 一發的左右偏角（度）：gaussian 回傳標準常態亂數；標準差照 spread ÷ cone 縮放，夾在 ±spread/2 裡（超出重擲 {@link #REROLLS} 次）。純函式。
     */
    static double angle(final Spec spec, final double spread, final DoubleSupplier gaussian) {
        final double half = Math.max(0, spread) / 2;
        if (half <= 0) {
            return 0;
        }
        final double sd = spec.sd() * spread / spec.cone();
        double degrees = gaussian.getAsDouble() * sd;
        for (int i = 0; i < REROLLS && Math.abs(degrees) > half; i++) {
            degrees = gaussian.getAsDouble() * sd;
        }
        return Math.max(-half, Math.min(half, degrees));
    }

    /** count 發的方向（繞垂直軸轉，高低角照原本的方向；跟 SkillMath.fan 同一種旋轉）。純函式。 */
    static List<double[]> fan(final double dx, final double dy, final double dz, final int count, final Spec spec, final double spread,
                              final DoubleSupplier gaussian) {
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        final double ux = length == 0 ? 0 : dx / length;
        final double uy = length == 0 ? 0 : dy / length;
        final double uz = length == 0 ? 1 : dz / length;
        final List<double[]> list = new ArrayList<>();
        for (int i = 0; i < Math.max(1, count); i++) {
            final double radians = Math.toRadians(count <= 1 ? 0 : angle(spec, spread, gaussian));
            final double cos = Math.cos(radians);
            final double sin = Math.sin(radians);
            list.add(new double[] {ux * cos - uz * sin, uy, ux * sin + uz * cos});
        }
        return list;
    }
}
