package tw.exilecore.ascendancy;

import tw.exilecore.stats.Modifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 分身獎勵（純邏輯）：帳號裡每種職業等級最高的那個角色，到了設定的等級，帳號裡的每個角色都拿到那個職業的加成。
 * 同一種職業只算最高的那一個；只給到達的最高那一階（不是加起來）。
 */
public final class Legacy {

    /** 帳號裡的一個角色。 */
    public record Member(String classId, String name, int level) {
    }

    /**
     * 一種職業的分身獎勵。
     *
     * @param best 這種職業等級最高的角色（沒有這種職業的角色 = null）
     * @param tier 到達的那一階（還沒到第一階 = null）
     * @param next 下一階（已經最高 = null）
     */
    public record Bonus(String classId, Member best, AscendancyConfig.Tier tier, AscendancyConfig.Tier next) {
        public boolean active() {
            return tier != null;
        }
    }

    private Legacy() {
    }

    /** 每種有設定分身獎勵的職業（照設定檔的順序）。 */
    public static List<Bonus> bonuses(final AscendancyConfig config, final Collection<Member> roster) {
        final List<Bonus> list = new ArrayList<>();
        for (final Map.Entry<String, List<AscendancyConfig.Tier>> entry : config.legacy().entrySet()) {
            Member best = null;
            for (final Member member : roster) {
                if (member.classId().equals(entry.getKey()) && (best == null || member.level() > best.level())) {
                    best = member;
                }
            }
            AscendancyConfig.Tier reached = null;
            AscendancyConfig.Tier next = null;
            for (final AscendancyConfig.Tier tier : entry.getValue()) {
                if (best != null && best.level() >= tier.level()) {
                    reached = tier;
                } else if (next == null) {
                    next = tier;
                }
            }
            list.add(new Bonus(entry.getKey(), best, reached, next));
        }
        return list;
    }

    /** 拿到的加成（修飾器的來源寫「分身獎勵（職業 Lv.30）」）。 */
    public static List<Modifier> modifiers(final List<Bonus> bonuses, final java.util.function.Function<String, String> className) {
        final List<Modifier> list = new ArrayList<>();
        for (final Bonus bonus : bonuses) {
            if (bonus.tier() == null) {
                continue;
            }
            final String source = "分身獎勵（" + className.apply(bonus.classId()) + " Lv." + bonus.tier().level() + "）";
            for (final Modifier modifier : bonus.tier().stats()) {
                list.add(new Modifier(modifier.stat(), modifier.type(), modifier.value(), modifier.tags(), source));
            }
        }
        return list;
    }
}
