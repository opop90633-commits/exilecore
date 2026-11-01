package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;

import java.util.List;

/**
 * 2026-11-01 照 PoE2 0.5（PoB2 WyvernFlameBreathPlayer、WyvernDevourPlayer、WingBlastPlayer、WolfPouncePlayer）：
 * 熾息、噬咬、鼓翼風、掠影撲補上的機制用到的屬性代號與純邏輯（測試用）。都是 opt-in：skills.yml 那個技能寫了才有。
 * <ul>
 *   <li>擊退隨距離（generic_knockback_+%_final_at_min_distance／at_max_distance、generic_knockback_distance_limit）：
 *       離出手點 0 格時擊退更多 near%，到 limit 公尺（含以外）時更多 far%，中間照比例。基礎力道照 skill.knockback。</li>
 *   <li>跳躍鎖敵（flag.leap_lock）：準心指著（或附近）範圍內的敵人就跳到牠身邊。</li>
 *   <li>點燃疊加（active_skill_compounding_ignites）：同一次使用再點燃同一個敵人時，拿掉舊的，新的幅度 × 這次使用疊了幾次（AilmentState）。</li>
 *   <li>起手動畫（start_animation_duration_as_%_of_attack_time 400）：引導開始後隔幾次攻擊時間才噴第一下。</li>
 *   <li>獲得的能量（active_skill_energy_generated_+%_final −98）：這個技能的擊中、異常給主要寶石的能量更少 98%。</li>
 * </ul>
 */
public final class ShapeshiftParity {

    private ShapeshiftParity() {
    }

    /** 擊退：離出手點最近時更多幾 %（FLAT，%）。 */
    public static final String KNOCKBACK_NEAR = "knockback.near";
    /** 擊退：到 {@link #KNOCKBACK_LIMIT} 時更多幾 %（FLAT，%；−90 = 剩一成）。 */
    public static final String KNOCKBACK_FAR = "knockback.far";
    /** 擊退：幾公尺算最遠（FLAT，公尺；沒寫就不隨距離變）。 */
    public static final String KNOCKBACK_LIMIT = "knockback.limit";
    /** 跳躍鎖敵（掠影撲）。 */
    public static final String FLAG_LEAP_LOCK = "flag.leap_lock";
    /** 點燃疊加（熾息）。 */
    public static final String FLAG_COMPOUND_IGNITE = "flag.compound_ignite";
    /** 引導開始後隔幾次攻擊時間才出第一下（熾息 4）。 */
    public static final String START_ATTACKS = "channel.start_attacks";
    /** 這個技能給主要寶石的能量更多幾 %（FLAT，%；熾息 −98）。 */
    public static final String ENERGY_GENERATED = "meta.energy_generated";

    /** 噬咬：PoE2 base_melee_dash_range 40（4 公尺）＝最多衝幾格。 */
    public static final double DEVOUR_DASH = tw.exilecore.core.Units.metres(4);
    /** 噬咬：目標在這個距離（格，水平）以內就不衝、不砸，直接吃。 */
    public static final double DEVOUR_NEAR = 1.5;
    /** 噬咬：吃目標以外最多再吃幾具屍體（wyvern_devour_max_extra_corpses_to_consume 2）。 */
    public static final int DEVOUR_EXTRA = 2;
    /** 噬咬：落地猛擊（與順便吃屍體）的半徑（active_skill_base_area_of_effect_radius 18 = 1.8 公尺，未乘範圍）。 */
    public static final double DEVOUR_RADIUS = tw.exilecore.core.Units.metres(1.8);
    /** 噬咬：目標近時攻擊速度更多 −15%、遠（要衝）時更多 20%；衝的距離每 4 公尺總攻擊時間 +0.5 秒（照比例）。 */
    public static final double DEVOUR_NEAR_SPEED = -15;
    public static final double DEVOUR_FAR_SPEED = 20;
    public static final double DEVOUR_ADDED_SECONDS = 0.5;
    /** 噬咬落地猛擊的擊退：最近 −50%、1.6 公尺以外 −90%。 */
    public static final double DEVOUR_KNOCKBACK_NEAR = -50;
    public static final double DEVOUR_KNOCKBACK_FAR = -90;
    public static final double DEVOUR_KNOCKBACK_LIMIT = 1.6;

    /**
     * 擊退隨距離的倍率：distance 格、limit 格算最遠；0 格時 1 + near/100，limit 以外 1 + far/100，中間照比例（最少 0）。
     * limit ≤ 0 回傳 1（不隨距離變）。
     */
    public static double knockbackFactor(final double distance, final double limit, final double nearPercent, final double farPercent) {
        if (limit <= 0) {
            return 1;
        }
        final double t = Math.max(0, Math.min(1, distance / limit));
        return Math.max(0, 1 + (nearPercent + (farPercent - nearPercent) * t) / 100.0);
    }

    /** 同上，照技能寫的 knockback.near／far／limit（沒寫 limit 回傳 1）。 */
    public static double knockbackFactor(final SkillDefinition definition, final double distance) {
        final double limit = flat(definition.modifiers(), KNOCKBACK_LIMIT, 0);
        if (limit <= 0) {
            return 1;
        }
        return knockbackFactor(distance, tw.exilecore.core.Units.metres(limit), flat(definition.modifiers(), KNOCKBACK_NEAR, 0),
                flat(definition.modifiers(), KNOCKBACK_FAR, 0));
    }

    /** 修飾器裡 stat 的 FLAT 加總（沒有回傳 fallback）。 */
    static double flat(final List<Modifier> modifiers, final String stat, final double fallback) {
        double sum = 0;
        boolean found = false;
        for (final Modifier modifier : modifiers) {
            if (modifier.type() == ModType.FLAT && modifier.stat().equals(stat)) {
                sum += modifier.value();
                found = true;
            }
        }
        return found ? sum : fallback;
    }

    /** 技能本身有沒有寫這個旗標（值 > 0）。 */
    static boolean flag(final SkillDefinition definition, final String stat) {
        return definition != null && flat(definition.modifiers(), stat, 0) > 0;
    }

    /**
     * 噬咬：目標離你 distance 格（水平）時 {攻擊速度更多 %, 總攻擊時間 + 秒, 衝幾格}。
     * 近（≤ {@link #DEVOUR_NEAR}）：不衝、−15%、+0；遠：衝到目標前面（最多 4 格）、+20%、+0.5 秒 × 衝的距離 ÷ 4 格。
     */
    public static double[] devourTiming(final double distance) {
        if (distance <= DEVOUR_NEAR) {
            return new double[] {DEVOUR_NEAR_SPEED, 0, 0};
        }
        final double dash = Math.max(0, Math.min(DEVOUR_DASH, distance - DEVOUR_NEAR * 0.5));
        return new double[] {DEVOUR_FAR_SPEED, DEVOUR_ADDED_SECONDS * dash / DEVOUR_DASH, dash};
    }

    /** 噬咬：最遠能鎖定多遠的目標（衝 4 格 + 咬得到的距離）。 */
    public static double devourLockRange() {
        return DEVOUR_DASH + DEVOUR_NEAR;
    }

    /**
     * 準心挑一個點（屍體）：從 eye 沿 direction（單位向量）看，range 格內、離視線 tolerance 格內的點裡沿視線最近的；
     * 都沒有回傳 −1。points 每個是 {x, y, z}。純函式（測試用）。
     */
    public static int pickAlongRay(final double[] eye, final double[] direction, final List<double[]> points, final double range,
                                   final double tolerance) {
        int best = -1;
        double bestAlong = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            final double[] p = points.get(i);
            final double dx = p[0] - eye[0];
            final double dy = p[1] - eye[1];
            final double dz = p[2] - eye[2];
            final double along = dx * direction[0] + dy * direction[1] + dz * direction[2];
            if (along < 0 || along > range) {
                continue;
            }
            final double off2 = dx * dx + dy * dy + dz * dz - along * along;
            if (off2 <= tolerance * tolerance && along < bestAlong) {
                bestAlong = along;
                best = i;
            }
        }
        return best;
    }

    /** 引導開始後第一下晚幾 tick：attacks 次攻擊時間（interval tick）；attacks ≤ 0 回傳 0。 */
    public static long startDelayTicks(final double attacks, final double intervalTicks) {
        return attacks <= 0 || intervalTicks <= 0 ? 0 : Math.round(attacks * intervalTicks);
    }

    /** 這個技能給主要寶石的能量倍率（照 meta.energy_generated；沒寫回傳 1）。 */
    public static double energyScale(final SkillDefinition definition) {
        return definition == null ? 1 : Math.max(0, 1 + flat(definition.modifiers(), ENERGY_GENERATED, 0) / 100.0);
    }

    /** 屬性的說明（ModifierText）；不是這裡的回傳 null。 */
    static String describe(final String stat, final double value) {
        return switch (stat) {
            case KNOCKBACK_NEAR -> "離你越近的敵人擊退越遠（最近更多 " + ModifierText.number(value) + "%）";
            case KNOCKBACK_FAR -> "離你越遠的敵人擊退越近（最遠更多 " + ModifierText.number(value) + "%）";
            case KNOCKBACK_LIMIT -> "擊退照距離算到 " + ModifierText.number(tw.exilecore.core.Units.metres(value)) + " 格";
            case FLAG_LEAP_LOCK -> "準心指著範圍內的敵人時跳到牠身邊";
            case FLAG_COMPOUND_IGNITE -> "同一次使用造成的點燃會疊加：再點燃同一個敵人時，新的點燃幅度乘上這次疊了幾次";
            case START_ATTACKS -> "起手要 " + ModifierText.number(value) + " 次攻擊時間才開始噴";
            case ENERGY_GENERATED -> "這個技能給主要寶石的能量更" + (value < 0 ? "少 " : "多 ") + ModifierText.number(Math.abs(value)) + "%";
            default -> null;
        };
    }
}
