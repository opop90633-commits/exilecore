package tw.exilecore.ascendancy;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

/**
 * 2026-10-26 照 PoE2 0.5 換昇華天賦時新做的機制（屬性鍵與計算；戰鬥的「這一下乘多少」從 combat.Signatures 呼叫）。
 * 這次之前沒有這些機制（原本的遠星是自己的數字：5 格外每遠 1 格更多 2%）。
 * <ul>
 *   <li>零點射擊（銳眼）：投射物對 3.5 公尺內的目標擊中傷害更多 20%，到 7 公尺遞減成 0%。</li>
 *   <li>遠射（銳眼）：投射物對 3.5 公尺內的目標更多 0%，到 7 公尺遞增成 20%。</li>
 *   <li>德魯伊之冠（薩滿）：每 2 點怒氣，法術傷害更多 1%。</li>
 * </ul>
 * 一格當一公尺。
 */
public final class AscendancyMechanics {

    /** FLAT：零點射擊，數值是最多更多幾 %（20）。 */
    public static final String POINT_BLANK = "asc.point_blank";
    /** FLAT：遠射，數值是最多更多幾 %（20）。 */
    public static final String FAR_SHOT = "asc.far_shot";
    /** FLAT：每 2 點怒氣，法術傷害更多幾 %（1）。 */
    public static final String DRUIDIC_CHAMPION = "asc.druidic_champion";

    /** 距離從幾公尺開始變（之前都是最大或 0）。 */
    static final double NEAR = 3.5;
    /** 到幾公尺變完。 */
    static final double FAR = 7.0;

    private AscendancyMechanics() {
    }

    /** 零點射擊：距離 distance 公尺時更多幾倍（maxPercent = 20 → 3.5 公尺內 1.2，7 公尺外 1.0）。 */
    static double pointBlank(final double maxPercent, final double distance) {
        return 1 + maxPercent / 100.0 * (1 - progress(distance));
    }

    /** 遠射：距離 distance 公尺時更多幾倍（3.5 公尺內 1.0，7 公尺外 1 + maxPercent%）。 */
    static double farShot(final double maxPercent, final double distance) {
        return 1 + maxPercent / 100.0 * progress(distance);
    }

    /** 3.5 公尺內 0、7 公尺外 1，中間照比例。 */
    private static double progress(final double distance) {
        return Math.max(0, Math.min(1, (distance - NEAR) / (FAR - NEAR)));
    }

    /** 德魯伊之冠：rage 點怒氣時法術更多幾倍。 */
    static double druidicChampion(final double percentPerTwo, final int rage) {
        return 1 + Math.max(0, rage / 2) * percentPerTwo / 100.0;
    }

    /**
     * 2026-10-27：德魯伊之冠換成修飾器（面板「現在」那張表用，BuffImpact）：法術更多 x%（照目前怒氣）；沒有是空的。純函式（測試用）。
     * 戰鬥照舊在 {@link #outgoing} 乘（不會重複：面板的表不進戰鬥）。
     */
    public static java.util.List<tw.exilecore.stats.Modifier> druidicModifiers(final StatSheet sheet, final int rage) {
        final double champion = sheet.flat(DRUIDIC_CHAMPION, java.util.Set.of());
        final double more = champion > 0 ? druidicChampion(champion, rage) - 1 : 0;
        if (more <= 0) {
            return java.util.List.of();
        }
        return java.util.List.of(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DAMAGE, tw.exilecore.stats.ModType.MORE, more,
                java.util.Set.of(Tags.SPELL), "德魯伊之冠"));
    }

    /** 玩家自己打出去的這一下，昇華新機制乘多少（combat.Signatures.outgoing 呼叫）。 */
    public static double outgoing(final ExileCore plugin, final Player attacker, final StatSheet sheet, final SkillProfile skill,
                                  final LivingEntity target) {
        double more = 1.0;
        final double pointBlank = sheet.flat(POINT_BLANK, java.util.Set.of());
        final double farShot = sheet.flat(FAR_SHOT, java.util.Set.of());
        if ((pointBlank > 0 || farShot > 0) && skill.tags().contains(Tags.PROJECTILE) && attacker.getWorld() == target.getWorld()) {
            final double distance = attacker.getLocation().distance(target.getLocation());
            if (pointBlank > 0) {
                more *= pointBlank(pointBlank, distance);
            }
            if (farShot > 0) {
                more *= farShot(farShot, distance);
            }
        }
        final double champion = sheet.flat(DRUIDIC_CHAMPION, java.util.Set.of());
        if (champion > 0 && skill.tags().contains(Tags.SPELL) && plugin.skills() != null && plugin.skills().resources() != null) {
            more *= druidicChampion(champion, plugin.skills().resources().rage(attacker));
        }
        return more;
    }
}
