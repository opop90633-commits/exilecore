package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-29 照 PoE2 Hand of Chayula（poe2db.tw/tw/Hand_of_Chayula；第 9 階敏捷／智慧長棍寶石，AoE、Melee、Strike、Chaos、Meta）：夏烏拉之手。
 * 主要寶石：衝向 6.5 公尺內的一個敵人打一掌（73% 物理轉混沌、總攻擊時間 +0.35 秒、不會被閃避），
 * 打完把孔裡的印記放在那個敵人身上（印記效果增加 20%→29%（20 級）→39%（40 級）），孔裡的詛咒套在那個敵人的位置（詛咒持續時間增加 100%）。
 * <ul>
 *   <li>孔裡只能放印記或詛咒（RePoE SupportHandOfChayulaPlayer：allowed_types AppliesCurse、Mark），可以放好幾個，每一掌全部觸發。</li>
 *   <li>孔裡的技能吃其他孔的輔助；其他孔的輔助能輔助這一掌的（攻擊、近戰…）也套在這一掌上。</li>
 *   <li>品質：插槽詛咒 (0—20)% 增加持續時間、插槽印記 (0—10)% 增加效果；額外的品質效果：(0—50)% 減少魔力消耗（gem_quality.yml）。</li>
 * </ul>
 * 原作沒有這顆之前沒有。純邏輯（SkillExecutor 衝過去打、SkillService 算孔裡的技能與觸發）。
 */
public final class ChayulaHand {

    private ChayulaHand() {
    }

    /** 夏烏拉之手。 */
    public static final String ID = "glow_jade_hand";

    /** 衝刺距離（PoE2 base_melee_dash_range 65 = 6.5 公尺）。 */
    static final double DASH_RANGE = tw.exilecore.core.Units.metres(6.5);

    /** 品質：插槽詛咒增加 x% 持續時間（gem_quality.yml 的 hand_of_chayula）。 */
    public static final String CURSE_DURATION = "chayula.curse_duration";
    /** 品質：插槽印記增加 x% 效果。 */
    public static final String MARK_EFFECT = "chayula.mark_effect";

    /** 插槽詛咒增加的持續時間（%）。 */
    static final double CURSE_DURATION_INCREASED = 100;

    /** 插槽印記增加的效果（%，1～40 級；RePoE SupportHandOfChayulaPlayer mark_effect_+%）。 */
    static final double[] MARK_EFFECT_BY_LEVEL = {
        20, 20, 21, 21, 22, 22, 23, 23, 24, 24, 25, 25, 26, 26, 27, 27, 28, 28, 29, 29,
        30, 30, 31, 31, 32, 32, 33, 33, 34, 34, 35, 35, 36, 36, 37, 37, 38, 38, 39, 39};

    public static boolean hosts(final SkillDefinition skill) {
        return skill != null && ID.equals(skill.id());
    }

    static boolean is(final SkillBuild build) {
        return build != null && hosts(build.definition());
    }

    /** 孔裡要放什麼（給玩家看）。 */
    public static String wants() {
        return "印記或詛咒";
    }

    /** 這一級插槽印記增加幾 % 效果。 */
    public static double markEffect(final int level) {
        return MARK_EFFECT_BY_LEVEL[Math.max(1, Math.min(MARK_EFFECT_BY_LEVEL.length, level)) - 1];
    }

    /** 這顆技能能不能放進夏烏拉之手的孔；可以回傳 null，不行回傳原因（接在技能名字後面）。 */
    public static String problem(final SkillDefinition inner) {
        if (inner == null || inner.hidden()) {
            return "不認得的技能";
        }
        if (inner.grantedOnly()) {
            return MetaGems.GRANTED_ONLY;
        }
        if (inner.tags().contains(Tags.META) || SupportDefinition.hostsSkill(inner) || SocketedWarcry.hosts(inner)) {
            return "主要寶石不能放";
        }
        if (inner.mechanic() != Mechanic.MARK || inner.params().mark() == null) {
            return "只能放印記或詛咒";
        }
        return null;
    }

    /**
     * 算插槽技能時另外加的修飾器：印記增加效果（這一級 + 品質）；詛咒增加 100% 持續時間（+ 品質）。
     *
     * @param markQuality  夏烏拉之手的 {@link #MARK_EFFECT} 品質總和（百分點）
     * @param curseQuality 夏烏拉之手的 {@link #CURSE_DURATION} 品質總和（百分點）
     */
    public static List<Modifier> modifiers(final SkillDefinition inner, final int hostLevel, final double markQuality, final double curseQuality) {
        final List<Modifier> list = new ArrayList<>();
        final SkillParams.Mark mark = inner.params().mark();
        if (mark != null && mark.curse()) {
            list.add(Modifier.increased(StatKeys.SKILL_DURATION, (CURSE_DURATION_INCREASED + curseQuality) / 100.0, "霞瑤之手"));
        } else {
            list.add(Modifier.increased(StatKeys.MARK_EFFECT, (markEffect(hostLevel) + markQuality) / 100.0, "霞瑤之手"));
        }
        return list;
    }
}
