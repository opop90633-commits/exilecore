package tw.exilecore.skill;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

/**
 * 2026-11-01 照 PoE2 0.5 Tornado（野旋風，PoB2 TornadoPlayer）：
 * <ul>
 *   <li>物理持續傷害（base_physical_damage_to_deal_per_minute 181 → 8765 → 186913 ÷ 60，{@link #PHYSICAL_PER_MINUTE}），不是擊中
 *       （不暴擊、不觸發擊中效果），吃法術傷害的加成（spell_damage_modifiers_apply_to_skill_dot）。這次之前每 0.5 秒擊中一次。</li>
 *   <li>蓋到元素地面（點燃、冰緩、感電地面，{@link ElementalGround}）時吸收那種地面：之後另外造成那種元素的持續傷害，
 *       每吸收一種元素各一份（poe2db「damage per second of each absorbed Elemental Damage type」），基礎是自己的逐級表
 *       （active_skill_base_elemental_damage_to_deal_per_minute 363 → 17530 → 373826 ÷ 60，{@link #ELEMENTAL_PER_MINUTE}）；
 *       並把地面的減益套給裡面的敵人（點燃地面：像被那團火燒一樣累積易燃、點燃；冰緩、感電地面：原作 PoB2／poe2db 沒寫幾 %，
 *       照我們玩家留下的冰緩地面、感電地面的值 {@value #GROUND_STATUS}%，見 {@link GroundSkills#NOVA_CHILL} 與 skills.yml 的 ground.shock）。</li>
 *   <li>往附近的敵人慢慢移動（tornado_movement_speed_+%_final −50）；原作 PoB2 沒有基礎移動速度，照我們的風暴移動（行嵐每秒 2.5 公尺）的一半。</li>
 *   <li>範圍邊緣緩速 30%（tornado_slow_%_at_max_range 30，從中心 0% 往外線性增加）。</li>
 *   <li>吸引（{@link #pullStep}）：每 50 毫秒（tornado_pull_interval_ms 50）把範圍內的敵人往中心拉剩下距離的 12%
 *       （tornado_pull_strength_% 12）；拉不動的（擊退抗性滿的、一直往外走的）1 秒內不再拉（tornado_failled_pull_cooldown_ms 1000）；
 *       tornado_pushiness 45 沒有說明文字，當作中心 0.45 公尺內不再拉（敵人不會全部疊在一點）。</li>
 * </ul>
 * PoB2 的 Tornado 只有一組 statSet（沒有 Bolt、Buff 這些段）；上限 1（number_of_tornados_allowed），額外品質 +0.1／1%（gem_quality.yml）。
 * 純資料與純函式；龍捲風本身在 {@link SkillZones}。
 */
public final class PrimalTornado {

    /** 野旋風的技能代號。 */
    public static final String ID = "wild_whirlwind";
    /** 元素持續傷害是物理的幾倍（只給說明、測試看；實際照 {@link #ELEMENTAL_PER_MINUTE}）。 */
    public static final double ELEMENT_MULTIPLIER = 2.0;
    /** PoB2 base_physical_damage_to_deal_per_minute（1–40 級）。 */
    static final int[] PHYSICAL_PER_MINUTE = {181, 264, 382, 516, 671, 847, 1048, 1278, 1541, 1842, 2186, 2580, 3031, 3549, 4144, 4827, 5613, 6518,
            7561, 8765, 10157, 11767, 13632, 15795, 18307, 21229, 24632, 28601, 33236, 38656, 45005, 52452, 61201, 71495, 83626, 97943, 114869,
            134909, 158678, 186913};
    /** PoB2 active_skill_base_elemental_damage_to_deal_per_minute（1–40 級；每吸收一種元素各一份）。 */
    static final int[] ELEMENTAL_PER_MINUTE = {363, 527, 763, 1033, 1341, 1694, 2096, 2556, 3082, 3683, 4372, 5160, 6063, 7098, 8287, 9654, 11225,
            13035, 15122, 17530, 20313, 23533, 27263, 31590, 36615, 42459, 49265, 57202, 66471, 77312, 90010, 104905, 122403, 142991, 167252,
            195887, 229738, 269819, 317355, 373826};
    /** 每 tick（50 毫秒）把敵人往中心拉剩下距離的幾成。 */
    public static final double PULL_STRENGTH = 0.12;
    /** 拉不動之後幾 tick 不再拉（1 秒）。 */
    public static final int PULL_FAILED_TICKS = 20;
    /** 中心幾公尺內不再拉（tornado_pushiness 45）。 */
    public static final double PUSHINESS_M = 0.45;
    /** 範圍邊緣的緩速（小數）。 */
    public static final double EDGE_SLOW = 0.30;
    /** 每秒移動幾公尺（2.5 × (1 − 50%)）。 */
    public static final double MOVE_METRES_PER_SECOND = 1.25;
    /** 找幾公尺內的敵人移過去。 */
    public static final double CHASE_RANGE_M = 8.0;
    /** 吸收的冰緩、感電地面的效果（%）。 */
    public static final double GROUND_STATUS = GroundSkills.NOVA_CHILL;

    private PrimalTornado() {
    }

    /** 離中心 distance 格（範圍半徑 radius）的敵人被緩速多少（小數）：中心 0，邊緣 {@value #EDGE_SLOW}。 */
    static double edgeSlow(final double distance, final double radius) {
        if (radius <= 0) {
            return 0;
        }
        return EDGE_SLOW * Math.max(0, Math.min(1, distance / radius));
    }

    private static int index(final int level) {
        return Math.max(1, Math.min(PHYSICAL_PER_MINUTE.length, level)) - 1;
    }

    /** 這一級的基礎物理每秒傷害（加成前）。 */
    static double physicalDps(final int level) {
        return PHYSICAL_PER_MINUTE[index(level)] / 60.0;
    }

    /** 這一級吸收一種元素的基礎每秒傷害（加成前）。 */
    static double elementalDps(final int level) {
        return ELEMENTAL_PER_MINUTE[index(level)] / 60.0;
    }

    /** 這一 tick 往中心拉幾格：離中心 distance 格，中心 deadZone 格內不拉。 */
    static double pullStep(final double distance, final double deadZone) {
        return distance <= deadZone ? 0 : Math.min(distance - deadZone, distance * PULL_STRENGTH);
    }

    /** 這一下拉了沒拉動：上一次拉的時候離中心 before 格，現在 after 格（沒有往中心靠近 0.01 格以上就算拉不動）。 */
    static boolean pullFailed(final double before, final double after) {
        return after > before - 0.01;
    }

    /** 這一下的基礎每秒傷害（逐級表是每秒的數字，下限上限取平均）。 */
    static double baseDps(final SkillProfile profile) {
        if (profile == null || profile.spellDamage() == null) {
            return 0;
        }
        final double min = profile.spellDamage().get(DamageType.PHYSICAL);
        final double max = profile.spellMax().get(DamageType.PHYSICAL);
        return Math.max(0, (min + max) / 2);
    }

    /**
     * 加成之後的每秒持續傷害：base × 這個技能（含輔助、法術傷害）對 type 的增加與更多 × 非異常持續傷害更多。
     * 法術傷害的加成照原作也算（技能標籤本來就有 spell）。
     */
    static double scaledDps(final StatSheet effective, final SkillProfile profile, final DamageType type, final double base, final double dotMore) {
        if (base <= 0) {
            return 0;
        }
        final double scaled = effective == null ? base : effective.apply(StatKeys.DAMAGE, base, profile.contextFor(type));
        return Math.max(0, scaled * Math.max(0, dotMore));
    }
}
