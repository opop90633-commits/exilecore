package tw.exilecore.skill;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;
import tw.exilecore.stats.TreeMechanicKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2026-10-27 照 PoE2「圖騰是你的友軍」（站長）：「你存在範圍內的友軍…」（修飾器寫 [minion, in_presence]）也給在你存在範圍內的圖騰。
 * 這次之前只給召喚物。圖騰不是召喚物：只寫「召喚物…」（只有 minion 標籤）、「夥伴…」、「不死召喚物…」的照舊不給圖騰。
 * <ul>
 *   <li>進攻的（傷害、附加傷害、攻擊／施法速度、暴擊、命中…）：疊在圖騰用的那個技能上（技能本來就照你的屬性表算，
 *       你自己的「增加 x% 傷害」已經算進去；這裡只加「給友軍」的那幾條，你自己不會吃到，不會重複）；</li>
 *   <li>防禦的：抗性加在圖騰的抗性上（上限照圖騰的）、生命再生（每秒固定值；「每秒再生你最大生命的 x%」照你的最大生命）。</li>
 * </ul>
 * 純函式，不依賴 Bukkit。
 */
public final class TotemAllies {

    private TotemAllies() {
    }

    /** 給友軍的條件標籤以外、會讓圖騰拿不到的條件（夥伴、不死召喚物：圖騰不是）。 */
    private static final Set<String> NOT_TOTEM = Set.of(TreeMechanicKeys.COMPANION, tw.exilecore.passive.ConquestEffects.UNDEAD);

    /** 圖騰本身的防禦（不疊到技能上）。 */
    static final Set<String> DEFENCE = Set.of(StatKeys.RESIST_FIRE, StatKeys.RESIST_COLD, StatKeys.RESIST_LIGHTNING, StatKeys.RESIST_CHAOS,
            StatKeys.LIFE_REGEN, StatKeys.LIFE_REGEN_PERCENT);

    /** 主人屬性表裡「你存在範圍內的友軍…」的修飾器（拿掉 minion、in_presence 標籤）；不含只給夥伴、不死召喚物的。 */
    public static List<Modifier> fromOwner(final StatSheet owner) {
        final List<Modifier> list = new ArrayList<>();
        for (final Modifier modifier : owner.allModifiers()) {
            final Set<String> tags = modifier.tags();
            if (!tags.contains(Tags.MINION) || !tags.contains(TreeMechanicKeys.IN_PRESENCE)
                    || !java.util.Collections.disjoint(tags, NOT_TOTEM)) {
                continue;
            }
            final Set<String> rest = new HashSet<>(tags);
            rest.remove(Tags.MINION);
            rest.remove(TreeMechanicKeys.IN_PRESENCE);
            list.add(modifier.withTags(rest));
        }
        return list;
    }

    /** 疊到圖騰技能上的（進攻的那些）。 */
    public static List<Modifier> offence(final List<Modifier> allies) {
        final List<Modifier> list = new ArrayList<>();
        for (final Modifier modifier : allies) {
            if (!DEFENCE.contains(modifier.stat())) {
                list.add(modifier);
            }
        }
        return list;
    }

    /** 圖騰的抗性加多少：{火焰, 冰冷, 閃電, 混沌}（只算 FLAT）。 */
    public static double[] resists(final List<Modifier> allies) {
        final StatSheet sheet = new StatSheet().addAll(allies);
        return new double[] {sheet.flat(StatKeys.RESIST_FIRE, Set.of()), sheet.flat(StatKeys.RESIST_COLD, Set.of()),
                sheet.flat(StatKeys.RESIST_LIGHTNING, Set.of()), sheet.flat(StatKeys.RESIST_CHAOS, Set.of())};
    }

    /** 圖騰每秒再生幾點生命：固定值 + 「你最大生命的 x%」。 */
    public static double regen(final List<Modifier> allies, final double ownerMaxLife) {
        final StatSheet sheet = new StatSheet().addAll(allies);
        return Math.max(0, sheet.flat(StatKeys.LIFE_REGEN, Set.of())
                + Math.max(0, ownerMaxLife) * sheet.flat(StatKeys.LIFE_REGEN_PERCENT, Set.of()) / 100.0);
    }

    /** 圖騰用的技能疊上給友軍的進攻修飾器（沒有就是原本那個）。 */
    public static SkillBuild withAllies(final SkillBuild build, final List<Modifier> offence) {
        if (build == null || offence == null || offence.isEmpty()) {
            return build;
        }
        return build.withProfile(build.profile().withLocal(offence));
    }

    /** 抗性的索引：火焰 0、冰冷 1、閃電 2、混沌 3；物理 -1。 */
    static int index(final DamageType type) {
        return switch (type) {
            case FIRE -> 0;
            case COLD -> 1;
            case LIGHTNING -> 2;
            case CHAOS -> 3;
            default -> -1;
        };
    }
}
