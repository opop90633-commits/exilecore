package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-01 照 PoE2 Glacial Bolt（「creates two walls of Ice Crystals at the end of its flight」、wall_maximum_length 63 → 6.3 格）：
 * 寫了 crystal_walls 的技能，冰晶不是排成一直線，而是從落點長出 walls 道牆，每道最長 length 格、冰晶間隔 spacing 格，
 * 每道牆跟飛行方向夾 angle 度（往左右對稱張開；forward = true 往前張，false 往回張）。
 * 每道牆幾顆照冰晶總數（crystal.count）平均分，放不下 length 的截掉。沒寫的技能照舊（IceCrystals 一排）。
 * <pre>
 *   crystal_walls: { walls: 2, length: 6.3, spacing: 1.6, angle: 45, forward: true }
 * </pre>
 */
public final class CrystalWalls {

    public record Spec(int walls, double length, double spacing, double angle, boolean forward) {
    }

    private static volatile Map<String, Spec> specs = Map.of();

    private CrystalWalls() {
    }

    static Spec parse(final YamlNode skill) {
        if (!skill.has("crystal_walls")) {
            return null;
        }
        final YamlNode node = skill.node("crystal_walls");
        final int walls = node.integer("walls", 2);
        final double length = node.decimal("length", 0) * tw.exilecore.core.Units.BLOCKS_PER_METRE;
        final double spacing = node.decimal("spacing", 1.6);
        final double angle = node.decimal("angle", 90);
        if (walls < 1 || walls > 4 || !(length > 0) || length > 30 || !(spacing > 0.3) || angle < 0 || angle > 90) {
            throw new IllegalArgumentException(node.path() + "：walls 1–4、length 0–30 格、spacing 0.3 格以上、angle 0–90 度");
        }
        return new Spec(walls, length, spacing, angle, node.bool("forward", true));
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

    /** 每道牆最多幾顆（length 放得下的）。純函式。 */
    static int perWallCap(final Spec spec) {
        return Math.max(1, (int) Math.floor((spec.length() - spec.spacing() / 2) / spec.spacing() + 1e-9) + 1);
    }

    /** 第 wall 道牆的方向角（度，相對飛行方向；左右對稱，往回張的加 180）。純函式。 */
    static double wallAngle(final Spec spec, final int wall) {
        final double side;
        if (spec.walls() == 1) {
            side = 0;
        } else {
            side = -spec.angle() + 2 * spec.angle() * wall / (spec.walls() - 1);
        }
        return spec.forward() ? side : 180 - side;
    }

    /**
     * 冰晶的水平位移（{dx, dz}，相對落點）：flight 是飛行方向（水平分量 fx, fz）。每道牆第一顆離落點 spacing/2，往外每 spacing 一顆。純函式。
     */
    static List<double[]> offsets(final Spec spec, final double fx, final double fz, final int total) {
        final double length = Math.sqrt(fx * fx + fz * fz);
        final double ux = length < 1e-9 ? 1 : fx / length;
        final double uz = length < 1e-9 ? 0 : fz / length;
        final int perWall = Math.min(perWallCap(spec), Math.max(1, (int) Math.ceil(total / (double) spec.walls())));
        final List<double[]> list = new ArrayList<>();
        int left = Math.max(0, total);
        for (int wall = 0; wall < spec.walls() && left > 0; wall++) {
            final double radians = Math.toRadians(wallAngle(spec, wall));
            final double cos = Math.cos(radians);
            final double sin = Math.sin(radians);
            final double wx = ux * cos - uz * sin;
            final double wz = ux * sin + uz * cos;
            for (int i = 0; i < perWall && left > 0; i++, left--) {
                final double d = spec.spacing() / 2 + i * spec.spacing();
                list.add(new double[] {wx * d, wz * d});
            }
        }
        return list;
    }
}
