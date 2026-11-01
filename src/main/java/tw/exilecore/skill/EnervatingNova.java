package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 0.5 Enervating Nova（EnervatingNovaPlayer）：
 * <ul>
 *   <li>環：1 公尺內（active_skill_base_secondary_area_of_effect_radius 10，跟 2.6 公尺的外圈一起吃範圍）的敵人不受傷害；</li>
 *   <li>離你越遠傷害越高（new_shock_nova_distance_based_damage_+%_final 100）：內圈 0%，到外圈更多 100%，中間照距離線性
 *       （原作只寫外緣的數值，內外之間怎麼分佈沒寫，取線性）；</li>
 *   <li>這個技能的閃電傷害可以電擊（base_lightning_damage_can_electrocute；技能 modifiers 的 electrocute.chance 100）；</li>
 *   <li>被擊中的敵人 4 秒內（base_skill_effect_duration 4000，吃持續時間）受到的所有閃電傷害都能累積電擊（{@link #DEBUFF}，AilmentService 看）。</li>
 * </ul>
 * 這次之前是 2.6 格的整圈、沒有距離加成、也沒有電擊的來源。
 */
public final class EnervatingNova {

    public static final String ID = "fading_thunder_ring";
    /** 內圈半徑 ÷ 外圈半徑（1 ÷ 2.6）。 */
    static final double INNER_FRACTION = 1.0 / 2.6;
    /** 外緣更多幾倍（100%）。 */
    static final double EDGE_MORE = 1.0;
    /** 被擊中後「所有閃電傷害都能電擊」的減益代號（Debuffs）。 */
    public static final String DEBUFF = "enervated";
    /** 減益幾秒（4 秒，乘持續時間倍率）。 */
    static final double DEBUFF_SECONDS = 4;

    private EnervatingNova() {
    }

    static boolean is(final String skill) {
        return ID.equals(skill);
    }

    /** 這個距離（格）的敵人打不打得到：在內圈外、外圈內。純函式。 */
    static boolean inRing(final double distance, final double outer) {
        return distance >= outer * INNER_FRACTION && distance <= outer + 1e-9;
    }

    /** 這個距離的傷害倍率：內圈 1、外圈 2，中間線性（超出夾住）。純函式。 */
    static double distanceMore(final double distance, final double outer) {
        final double inner = outer * INNER_FRACTION;
        if (outer <= inner) {
            return 1.0;
        }
        final double t = Math.max(0, Math.min(1, (distance - inner) / (outer - inner)));
        return 1.0 + EDGE_MORE * t;
    }
}
