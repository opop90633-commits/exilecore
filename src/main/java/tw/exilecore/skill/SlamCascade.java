package tw.exilecore.skill;

import tw.exilecore.core.Units;

/**
 * 2026-11-01 照 PoE2 0.5 Supercharged Slam（千鈞砸）的數值（RePoE／PoB2 SuperchargedSlamPlayer）：
 * <ul>
 *   <li>引導每一層（一次攻擊時間）更多 40% 傷害（channelled_slam_damage_+%_final_per_stage 40，乘層數；猛擊與餘震都吃）；</li>
 *   <li>猛擊 1.6 公尺（skills.yml nova.radius）；之後每一層一次餘震，間隔 0.25 秒（aftershock_delay_ms 250），
 *       第一次 1.2 公尺、之後每一次比前一次 +0.4 公尺（aftershock_radius_+_per_previous_aftershock 4，最多加 3 公尺）。</li>
 * </ul>
 * 這次之前：第 1 層不加傷害、之後每層更多 40%；不論幾層都只有一次 0.6 秒後的餘震（半徑 ×2.0、更多 60%）。
 */
final class SlamCascade {

    static final String ID = "thousand_weight_smash";
    /** 放開時是第幾層（Signatures.released 照 charge.stage_stat 給的 FLAT，每層 1）。 */
    static final String STAGES = "skill.slam_stages";
    static final double MORE_PER_STAGE = 0.40;
    static final double FIRST_AFTERSHOCK_M = 1.2;
    static final double AFTERSHOCK_STEP_M = 0.4;
    static final double AFTERSHOCK_STEP_CAP_M = 3.0;
    static final int AFTERSHOCK_GAP_TICKS = 5;
    /** 猛擊打在身前多遠（格；近戰的出手距離）。 */
    static final double IMPACT_AHEAD = 1.2;

    private SlamCascade() {
    }

    /** 放開時 stages 層：更多幾倍（1 + 40% × 層數）。純函式（測試用）。 */
    static double more(final int stages) {
        return 1 + MORE_PER_STAGE * Math.max(1, stages);
    }

    /** 第 index 次餘震（0 起算）的基礎半徑（格，未乘範圍）。純函式（測試用）。 */
    static double aftershockRadius(final int index) {
        return Units.metres(FIRST_AFTERSHOCK_M + Math.min(AFTERSHOCK_STEP_CAP_M, AFTERSHOCK_STEP_M * Math.max(0, index)));
    }

    /** 第 index 次餘震在猛擊之後幾 tick。純函式（測試用）。 */
    static int aftershockDelay(final int index) {
        return AFTERSHOCK_GAP_TICKS * (Math.max(0, index) + 1);
    }
}
