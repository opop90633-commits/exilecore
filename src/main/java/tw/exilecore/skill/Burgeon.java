package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.List;
import java.util.Set;

/**
 * 2026-10-27 照 PoE2 0.5 Burgeon（漸熾）：被輔助的引導技能每引導 1 秒更多 x% 傷害，最多 cap%（Burgeon I 每秒 10%、Burgeon II 每秒 20%，都是最多 40%；
 * supports.yml 照「I／II 只留最高階」用 II）。
 * 引導的秒數：ChargeSpec 的引導（放開型、連續出手型）照按住多久（{@link Signatures#applyRelease}）；channel.stages 的「按住一直放」
 * 照連續放了多久（SkillService）。這次之前漸熾是「每層蓄力更多 5%」（channel.more），只有掛了蓄勢（channel.stages）才有用。
 */
final class Burgeon {

    /** FLAT %：每引導 1 秒更多幾 % 傷害。 */
    static final String PER_SECOND = "channel.burgeon_per_second";
    /** FLAT %：最多更多幾 %。 */
    static final String CAP = "channel.burgeon_cap";

    private Burgeon() {
    }

    /** 引導了 seconds 秒，更多幾 %（0～cap）。 */
    static double more(final double perSecond, final double cap, final double seconds) {
        if (perSecond <= 0 || seconds <= 0) {
            return 0;
        }
        final double raw = perSecond * seconds;
        return cap > 0 ? Math.min(cap, raw) : raw;
    }

    /** 屬性表（技能的 effective）上的漸熾：引導了 seconds 秒，更多幾 %。 */
    static double more(final StatSheet effective, final double seconds) {
        if (effective == null) {
            return 0;
        }
        return more(effective.flat(PER_SECOND, Set.of()), effective.flat(CAP, Set.of()), seconds);
    }

    /** 加上漸熾的傷害（沒掛或沒引導就原樣）。 */
    static SkillBuild apply(final SkillBuild build, final StatSheet sheet, final double seconds) {
        if (sheet == null) {
            return build;
        }
        final double more = more(build.profile().effective(sheet), seconds);
        if (more <= 0) {
            return build;
        }
        return build.withProfile(build.profile().withLocal(List.of(Modifier.more(StatKeys.DAMAGE, more / 100.0, "漸熾"))));
    }
}
