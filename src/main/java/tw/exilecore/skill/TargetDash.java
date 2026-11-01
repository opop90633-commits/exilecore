package tw.exilecore.skill;

import java.util.List;

/**
 * 2026-11-01 照 PoE2 0.5（站長：「要衝向目標的都要有鎖敵」）：先衝到目標身邊再打的近戰技能（PoB base_melee_dash_range）。
 * <ul>
 *   <li>剔殘（Cull the Weak）7.5 公尺、引雷棍（Siphoning Strike）6.5 公尺、血獵（Blood Hunt）5 公尺。</li>
 *   <li>鎖敵：先看準心鎖定／指著的敵人，沒有就挑距離內、離準心方向最近（夾角不超過 {@link #LOCK_ANGLE} 度）、看得到的敵人
 *       （{@link #best}）。目標已經在打擊距離內就不衝；附近沒有可以鎖的敵人就原地打（跟原作一樣可以空揮）。</li>
 *   <li>躍棍崩擊（Vaulting Impact）也用同一套鎖敵：準心附近 {@link #LOCK_ANGLE} 度內、跳躍距離內有敵人就跳到牠身上，沒有就跳到準心指的地方。</li>
 * </ul>
 * 這是技能自己的位移（不是近戰條件位移 MeleeStep）。純邏輯，不依賴 Bukkit；衝過去、打由 SkillExecutor 做。
 */
final class TargetDash {

    static final String CULL_THE_WEAK = "cripple_purge";
    static final String SIPHONING_STRIKE = "thunder_draw_staff";
    static final String BLOOD_HUNT = "blood_hunt";
    static final String VAULTING_IMPACT = "leaping_staff_crash";

    /** 鎖敵：離準心方向最多幾度的敵人可以鎖（原作是滑鼠底下的敵人；我們用準心附近的錐形代替）。 */
    static final double LOCK_ANGLE = 35;

    private TargetDash() {
    }

    /** 這個技能衝刺的距離（格）；不是衝刺打擊的技能回傳 0。 */
    static double range(final String skillId) {
        if (skillId == null) {
            return 0;
        }
        return switch (skillId) {
            case CULL_THE_WEAK -> tw.exilecore.core.Units.metres(7.5);    // base_melee_dash_range 75
            case SIPHONING_STRIKE -> tw.exilecore.core.Units.metres(6.5); // base_melee_dash_range 65
            case BLOOD_HUNT -> tw.exilecore.core.Units.metres(5.0);       // base_melee_dash_range 50
            default -> 0;
        };
    }

    /** 躍棍崩擊：跳躍要鎖敵。 */
    static boolean locksLeap(final String skillId) {
        return VAULTING_IMPACT.equals(skillId);
    }

    /** 視線 look 跟 (dx, dy, dz) 的夾角（度）；長度是 0 回傳 180。 */
    static double angle(final double lookX, final double lookY, final double lookZ, final double dx, final double dy, final double dz) {
        final double a = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        final double b = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (a < 1e-9 || b < 1e-9) {
            return 180;
        }
        final double cos = Math.max(-1, Math.min(1, (lookX * dx + lookY * dy + lookZ * dz) / (a * b)));
        return Math.toDegrees(Math.acos(cos));
    }

    /**
     * 候選目標（從眼睛到目標中心的位移 {dx, dy, dz}、水平距離 horizontal 另外給在第 4 格）裡挑要鎖的：
     * 水平距離在 range 內、跟視線夾角不超過 maxAngle 的，夾角最小的那個（一樣就近的）。都不行回傳 -1。
     */
    static int best(final double lookX, final double lookY, final double lookZ, final List<double[]> candidates, final double range,
                    final double maxAngle) {
        int best = -1;
        double bestAngle = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            final double[] c = candidates.get(i);
            final double horizontal = c.length > 3 ? c[3] : Math.sqrt(c[0] * c[0] + c[2] * c[2]);
            if (!(horizontal <= range)) {
                continue;
            }
            final double angle = angle(lookX, lookY, lookZ, c[0], c[1], c[2]);
            if (angle > maxAngle) {
                continue;
            }
            if (angle < bestAngle - 1e-9 || Math.abs(angle - bestAngle) <= 1e-9 && horizontal < bestDistance) {
                best = i;
                bestAngle = angle;
                bestDistance = horizontal;
            }
        }
        return best;
    }

    /** 要不要衝：目標水平距離超過打擊距離才衝。 */
    static boolean needsDash(final double horizontal, final double reach) {
        return horizontal > reach;
    }
}
