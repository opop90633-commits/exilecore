package tw.exilecore.skill;

import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-10-29 照 PoE2 Blasphemy（poe2db.tw/tw/Blasphemy；第 8 階智慧寶石，Persistent、AoE、Aura、Meta）：咒環。
 * 主要寶石（常駐，綁在上方按鍵格），孔裡放詛咒的技能寶石（可以放好幾個，一孔一個），每個詛咒變成光環：
 * 一直詛咒你身邊的所有敵人（不用施放、沒有 1.5 秒的落地延遲）。
 * <ul>
 *   <li>每個插槽詛咒保留 60 精魂（吃保留效率：SkillBuild 的 aura.reservation；沒放詛咒不保留、也沒有效果）。</li>
 *   <li>插槽詛咒的效果更少 50%（1 級）～ 41%（20 級）～ 33%（40 級），照 {@link #LESS_MAGNITUDE}。</li>
 *   <li>插槽詛咒的基礎範圍半徑 +1 公尺（{@link tw.exilecore.core.Units#metres}，再乘詛咒自己的範圍倍率），以你為中心。</li>
 *   <li>品質：一般品質 (0—10)% 增加保留效率（gem_quality.yml）；額外的品質效果（進階奇術）插槽詛咒 (0—20)% 增加效果。</li>
 * </ul>
 * 其他孔的輔助套在插槽詛咒上（跟其他主要寶石一樣）；詛咒自己的等級、品質照放進去的那顆寶石。
 * 引擎的做法：光環每 0.5 秒（AuraService.tick）對你身邊的敵人重套一次短的詛咒（{@link #LINGER_TICKS}），離開範圍很快就消失；
 * 「一隻敵人身上同時只能有你的一個詛咒」照舊（原作 Blasphemy 的詛咒也算詛咒上限）。
 * 純邏輯（SkillService 算數值與套詛咒、AuraService 定時呼叫）。
 */
public final class Blasphemy {

    private Blasphemy() {
    }

    /** 咒環。 */
    public static final String ID = "curse_ring";

    /** 每個插槽詛咒保留多少精魂（skills.yml 的 aura.reserve 也寫 60：算保留效率用）。 */
    public static final double RESERVE_PER_CURSE = 60;

    /** 插槽詛咒的基礎範圍半徑多幾公尺（PoE2 +1 metre）。 */
    public static final double EXTRA_RADIUS_METRES = 1;

    /** 每次重套的詛咒延續幾 tick（光環每 10 tick 重套一次，多留一點不閃爍；離開範圍 1 秒內消失）。 */
    public static final long LINGER_TICKS = 20;

    /** 額外的品質效果（進階奇術）：插槽詛咒增加 x% 效果（gem_quality.yml advanced 的 blasphemy）。 */
    public static final String CURSE_MAGNITUDE = "curse_ring.curse_magnitude";

    /**
     * 插槽詛咒效果更少（%，咒環 1～40 級）。poe2db：1 級 50、5 級 48、10 級 46、15 級 43、20 級 41、30 級 36、40 級 33，
     * 中間照線性內插取整數（不會變多）。
     */
    static final double[] LESS_MAGNITUDE = {
        50, 50, 49, 49, 48, 48, 47, 47, 46, 46,
        45, 45, 44, 44, 43, 43, 42, 42, 41, 41,
        40, 40, 39, 39, 38, 38, 37, 37, 36, 36,
        36, 35, 35, 35, 34, 34, 34, 34, 33, 33};

    /** 這個技能是不是咒環。 */
    public static boolean hosts(final SkillDefinition skill) {
        return skill != null && ID.equals(skill.id());
    }

    /** 孔裡要放什麼（給玩家看）。 */
    public static String wants() {
        return "詛咒";
    }

    /** 這顆技能能不能放進咒環的孔；可以回傳 null，不行回傳原因（接在技能名字後面）。 */
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
        if (!curse(inner)) {
            return "只能放詛咒（印記、其他技能不行）";
        }
        return null;
    }

    /** 是不是詛咒（mechanic: mark、有範圍的）。 */
    static boolean curse(final SkillDefinition skill) {
        return skill != null && skill.mechanic() == Mechanic.MARK && skill.params().mark() != null && skill.params().mark().curse();
    }

    /** 咒環這一級插槽詛咒的效果更少幾 %。 */
    public static double lessMagnitude(final int level) {
        return LESS_MAGNITUDE[Math.max(1, Math.min(LESS_MAGNITUDE.length, level)) - 1];
    }

    /**
     * 算插槽詛咒時另外加的修飾器（要在算詛咒效果之前放進去）：效果更少 x%（照咒環等級），
     * 額外的品質效果的增加 y%（magnitudeQuality = 咒環的 {@link #CURSE_MAGNITUDE} 總和，百分點）。
     */
    public static List<Modifier> curseModifiers(final int hostLevel, final double magnitudeQuality) {
        final List<Modifier> list = new ArrayList<>();
        list.add(Modifier.more(StatKeys.CURSE_EFFECT, -lessMagnitude(hostLevel) / 100.0, "咒環"));
        if (magnitudeQuality > 0) {
            list.add(Modifier.increased(StatKeys.CURSE_EFFECT, magnitudeQuality / 100.0, "咒環（品質）"));
        }
        return list;
    }

    /** 插槽詛咒的基礎半徑（格，乘範圍倍率之前）：詛咒自己這一級的半徑 + 1 公尺。 */
    public static double baseRadius(final SkillParams.Mark spec, final int curseLevel) {
        return spec.radius(curseLevel) + tw.exilecore.core.Units.metres(EXTRA_RADIUS_METRES);
    }

    /** 半徑換成「詛咒這一級的半徑 + 1 公尺」的詛咒設定（其他照舊；SkillExecutor.applyCurseAt 再乘範圍倍率）。 */
    public static SkillParams.Mark widened(final SkillParams.Mark spec, final int curseLevel) {
        return new SkillParams.Mark(spec.durationByLevel(), spec.taken(), spec.selfOnly(), Scaled.of(baseRadius(spec, curseLevel)),
                spec.tags(), spec.effects(), spec.slow());
    }

    /** 保留的精魂（還沒算保留效率）：每個插槽詛咒 60。 */
    public static double reserve(final int curses) {
        return RESERVE_PER_CURSE * Math.max(0, curses);
    }

    /** 孔裡有幾個能用的詛咒（放錯的不算）。 */
    public static int curses(final SkillConfig config, final tw.exilecore.character.SkillLoadout.Entry host) {
        int count = 0;
        if (host != null) {
            for (final tw.exilecore.character.SkillLoadout.Entry spell : host.spells()) {
                if (problem(config.skill(spell.skillId())) == null) {
                    count++;
                }
            }
        }
        return count;
    }
}
