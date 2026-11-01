package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.HashSet;
import java.util.Set;

/**
 * 2026-11-01 照 PoE2 Plasma Blast（「cannot be fired at all unless fully charged」）：寫了 charge.full_only: true 的放開型引導，
 * 要按住滿一次攻擊時間（這一招的攻擊間隔，含「總攻擊時間 +x 秒」）才射得出去；還沒滿就放開＝取消（不出手、不扣消耗），
 * 滿了之後可以一直按著，放開才射（Signatures.tickCharge）。沒寫的引導照舊。
 */
public final class FullCharge {

    private static volatile Set<String> skills = Set.of();

    /** 滿了之後最多再按住幾 tick（之後自動射出；原作可以一直按，這裡留 10 秒的保險）。 */
    static final long HOLD_AFTER_FULL = 200;

    private FullCharge() {
    }

    static boolean parse(final YamlNode skill) {
        return skill.has("charge") && skill.node("charge").bool("full_only", false);
    }

    static void install(final Set<String> parsed) {
        skills = Set.copyOf(parsed);
    }

    /** 這一招是不是要充滿才能射。 */
    public static boolean required(final String skillId) {
        return skillId != null && skills.contains(skillId);
    }

    /** 充滿要幾 tick（照攻擊間隔，至少 1）。純函式。 */
    static long fullTicks(final double intervalTicks) {
        return Math.max(1, Math.round(intervalTicks));
    }

    /** 按住 held tick 放開：要不要取消（還沒充滿）。純函式。 */
    static boolean cancels(final long held, final double intervalTicks) {
        return held < fullTicks(intervalTicks);
    }

    /** 充能進度的提示（name 已經跳脫過）：還沒滿寫百分比，滿了寫「放開射出」。純函式。 */
    static String progressText(final String name, final long held, final double intervalTicks) {
        final long full = fullTicks(intervalTicks);
        if (held >= full) {
            return "<#7fe3ff>" + name + " 充能完成<gray>（放開射出）";
        }
        return "<#ffd27f>" + name + " 充能 <white>" + Math.min(99, held * 100 / full) + "%";
    }

    /** 測試用。 */
    static Set<String> all() {
        return new HashSet<>(skills);
    }
}
