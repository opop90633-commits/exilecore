package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

/**
 * 2026-11-01 照 PoE2 0.5：弩技能補上的機制（只給寫了對應欄位／旗標的技能，其他技能不變）。
 * <ul>
 *   <li>子榴彈（Cluster Grenade 的 Mini Grenades）：技能寫了 stages.bomblet，主榴彈炸開時散出的是子榴彈——
 *       自己的傷害表與轉換（stage 的 effectiveness、convert）、固定 count 顆（number_of_projectiles_cannot_be_modified）、
 *       彈跳到引信 after 秒才各自炸 radius 公尺（ProjectileEngine.bomblets）。</li>
 *   <li>{@link #FLAG_DETONATES_GRENADES}（Explosive Shot）：投射物爆炸時，範圍內你自己還沒炸的榴彈一起炸。</li>
 *   <li>{@link #FLAG_CONTACT_BURST}（Oil Grenade）：榴彈碰到敵人就在那裡炸開（不用等引信）。</li>
 * </ul>
 */
public final class CrossbowParity {

    /** skills.yml 的 stages 裡子榴彈那一段的代號。 */
    public static final String BOMBLET = "bomblet";
    /** FLAT 1：這個技能的投射物爆炸時引爆範圍內你的榴彈（爆裂弩箭）。 */
    public static final String FLAG_DETONATES_GRENADES = "flag.explosion_detonates_grenades";
    /** FLAT 1：榴彈碰到敵人就炸開（火油榴彈）。 */
    public static final String FLAG_CONTACT_BURST = "flag.grenade_contact_burst";

    /** 子榴彈射出去的仰角（方向向量的 y，水平分量正規化前是 1）。 */
    static final double BOMBLET_LIFT = 0.35;
    /** 子榴彈最多幾顆（效能保險）。 */
    static final int MAX_BOMBLETS = 40;

    private CrossbowParity() {
    }

    /** 技能自己（profile 的 local）有沒有這個旗標。 */
    static boolean has(final SkillProfile profile, final String flag) {
        if (profile == null) {
            return false;
        }
        for (final Modifier modifier : profile.local()) {
            if (flag.equals(modifier.stat()) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }

    static boolean detonatesGrenades(final SkillProfile profile) {
        return has(profile, FLAG_DETONATES_GRENADES);
    }

    static boolean burstsOnEnemy(final SkillProfile profile) {
        return has(profile, FLAG_CONTACT_BURST);
    }

    /** 這個等級散出幾顆子榴彈（stage 的 count，1～{@value #MAX_BOMBLETS}；沒寫 1 顆）。純函式。 */
    static int bombletCount(final SkillStages.Stage stage, final int level) {
        return Math.max(1, Math.min(MAX_BOMBLETS, stage.count().intAt(level)));
    }

    /** 子榴彈的引信（tick）：stage 的 after 秒；沒寫照主榴彈的引信。純函式。 */
    static int bombletFuseTicks(final SkillStages.Stage stage, final double primaryFuse) {
        final double seconds = stage.after() > 0 ? stage.after() : primaryFuse;
        return (int) Math.max(1, Math.round(seconds * 20));
    }

    /** 每顆飛多遠：scatter.range 的 50%～100%（roll 0～1；原作 projectile_distance_variance）。純函式。 */
    static double bombletRange(final double range, final double roll) {
        return Math.max(1, range * (0.5 + 0.5 * Math.max(0, Math.min(1, roll))));
    }

    /**
     * 要落在 reach 格外：仰角 {@link #BOMBLET_LIFT} 的拋物線初速（格／tick；ProjectileEngine 的重力每 tick 減 gravity、沒有阻力，
     * 平地射程 = 2v²·sinθ·cosθ ÷ g）。沒有重力照 0.6。純函式。
     */
    static double bombletSpeed(final double reach, final double gravity) {
        if (!(gravity > 0)) {
            return 0.6;
        }
        final double angle = Math.atan(BOMBLET_LIFT);
        return Math.max(0.2, Math.sqrt(reach * gravity / (2 * Math.sin(angle) * Math.cos(angle))));
    }
}
