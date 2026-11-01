package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 0.5：打出一道長方形範圍的近戰攻擊（PoB 的 active_skill_base_area_of_effect_radius 是長度、secondary 是半寬）。
 * <ul>
 *   <li>雷浪（Storm Wave）：長棍一揮，地面裂出一道往前 8 公尺、半寬 1 公尺的閃電裂隙，瞬間打到裡面的敵人
 *       （這次之前是一顆往前飛 12 格的投射物）。品質每 1% 半寬 +0.05 公尺（{@link #WIDTH_QUALITY}）。</li>
 *   <li>蛇嵐突（Wind Serpent's Fury）：突刺是一道往前 10 公尺、半寬 1.7 公尺的長條（這次之前 35° 扇形），風蛇沿同一道。</li>
 * </ul>
 * 長度照 skills.yml 的 cone.length（已經乘範圍），半寬照同一個範圍倍率縮放。純邏輯，不依賴 Bukkit。
 */
final class StormWave {

    static final String STORM_WAVE = "thunder_surge";
    static final String WIND_SERPENT = "snake_squall_thrust";
    /** 品質：半寬 +x 公尺（FLAT，gem_quality.yml 每 1% 0.05）。 */
    static final String WIDTH_QUALITY = "storm_wave.width";

    private StormWave() {
    }

    /** 這個技能是長方形範圍的話，未乘範圍的半寬（公尺）；不是回傳 0。 */
    static double halfWidthMetres(final String skillId) {
        if (STORM_WAVE.equals(skillId)) {
            return 1.0;  // active_skill_base_secondary_area_of_effect_radius 10
        }
        if (WIND_SERPENT.equals(skillId)) {
            return 1.7;  // active_skill_base_secondary_area_of_effect_radius 17
        }
        return 0;
    }

    static boolean is(final String skillId) {
        return halfWidthMetres(skillId) > 0;
    }

    /** 實際半寬（格）：(基礎 + 品質) 公尺換成格，再乘範圍的線性倍率。 */
    static double halfWidth(final String skillId, final double qualityMetres, final double areaScale) {
        final double base = halfWidthMetres(skillId);
        return base <= 0 ? 0 : tw.exilecore.core.Units.metres(base + Math.max(0, qualityMetres)) * Math.max(0.1, areaScale);
    }

    /** (dx, dz) 在不在從原點往 (fx, fz) 方向、長 length、半寬 half 的長方形裡（往後不算，貼身 0.5 格內算）。 */
    static boolean inside(final double fx, final double fz, final double dx, final double dz, final double length, final double half) {
        final double norm = Math.sqrt(fx * fx + fz * fz);
        if (norm < 1e-9) {
            return false;
        }
        final double ux = fx / norm;
        final double uz = fz / norm;
        final double along = dx * ux + dz * uz;
        final double side = Math.abs(dx * uz - dz * ux);
        return along >= -0.5 && along <= length && side <= half;
    }
}
