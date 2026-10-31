package tw.exilecore.skill;

import tw.exilecore.charge.ChargeService;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Set;

/**
 * 2026-10-28 照 PoE2 0.5 Hollow Form（鳴鐘武師昇華賦予的主要寶石；Attack、Melee、Sustained、Channelling、Meta）：
 * 孔裡放近戰攻擊的技能寶石，按住引導，在準心附近（離你 6 格內）一直顯現星體幻影，每個幻影用孔裡的近戰攻擊打離它最近的敵人一次就消失
 * （好幾個照孔的順序輪流）。
 * <ul>
 *   <li>每顯現一個幻影花孔裡那個攻擊 80% 的消耗；有銳星就用掉 1 顆、多 2 個幻影（倍星讓效果加倍時多 4 個）。</li>
 *   <li>孔裡的攻擊：不能消耗戰星、攻擊速度更少 30%（幻影出現的間隔 = 那個攻擊的攻擊時間 ÷ 0.7；引導開始的前搖是 30%）、傷害更少 15%。</li>
 *   <li>不能放引導技能、有使用條件的技能（原作 Conditional：驚雷之鐘要連擊、天罰巨鎚要榮耀）。</li>
 * </ul>
 * 引擎的限制（平衡上不另外補償）：位移、跳躍、瞬影的近戰攻擊會把玩家本人帶過去，幻影放不出來，所以不能放；
 * 會放下圖騰的、常駐的、戰吼也不能放（原作這些本來就不是 Hollow Form 能用的攻擊）。
 * 孔裡攻擊自己的冷卻照算（幻影共用一個，跟你自己按的那一顆分開；原作沒寫，避免有冷卻的大招每個幻影都放一次）。
 * 原作品質「(0—10)% 機率不拿掉銳星但照樣算用掉」：賦予的技能沒有寶石、沒有品質，不用做。
 * 原作引導中移動速度更少 80%：照站長「放技能減速全部移除」（2026-10-27），跟其他引導技能一樣不減速。
 * <p>
 * 這次之前（2026-10-27）是按一次（施法 1.4 秒、冷卻 0.5 秒）的戰吼型技能，幻影用「你最近使用的近戰攻擊」，沒有孔。
 * 純邏輯（SkillService、{@link HollowImages} 執行）。
 */
public final class HollowForm {

    private HollowForm() {
    }

    /** 空無型態。 */
    public static final String ID = "hollow_form";
    /** 孔裡的攻擊傷害更少（%）。 */
    public static final double LESS_DAMAGE = 15;
    /** 孔裡的攻擊速度更少（%）：幻影的間隔照這個拉長。 */
    public static final double LESS_ATTACK_SPEED = 30;
    /** 每個幻影花孔裡那個攻擊消耗的幾成。 */
    public static final double COST_SHARE = 0.8;
    /** 用掉一顆銳星多幾個幻影。 */
    public static final int EXTRA_IMAGES = 2;
    /** 幻影出現在離你幾格內（PoE2 6 米）。 */
    public static final double RADIUS = tw.exilecore.core.Units.metres(6); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    /** 引導開始到第一個幻影：一次間隔的幾成（PoE2 channel start 30% of attack time）。 */
    public static final double START_SHARE = 0.3;
    /** 同一次的額外幻影（銳星）一個隔幾 tick 出現。 */
    public static final long EXTRA_STAGGER = 3;

    /** 有使用條件的近戰攻擊（原作 Conditional／HasUsageCondition；我們的代號）。 */
    static final Set<String> CONDITIONAL = Set.of(
            "tempest_bell",     // 驚雷之鐘：要連擊
            "hammer_of_gods");  // 天罰巨鎚：要榮耀

    /** 這個技能是不是空無型態。 */
    public static boolean hosts(final SkillDefinition skill) {
        return skill != null && ID.equals(skill.id());
    }

    /** 孔裡要放什麼（給玩家看）。 */
    public static String wants() {
        return "近戰攻擊";
    }

    /** 這顆技能能不能放進空無型態的孔；可以回傳 null，不行回傳原因（接在技能名字後面）。 */
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
        if (!inner.isAttack() || !inner.tags().contains(Tags.MELEE)) {
            return "只能放近戰攻擊";
        }
        if (inner.tags().contains("channelling") || inner.tags().contains("channel") || SupportDefinition.channels(inner)) {
            return "引導技能不行";
        }
        if (CONDITIONAL.contains(inner.id())) {
            return "有使用條件的技能不行";
        }
        final Mechanic mechanic = inner.mechanic();
        if (mechanic == Mechanic.DASH || mechanic == Mechanic.LEAP || mechanic == Mechanic.FLICKER) {
            return "位移、跳躍的攻擊不行";
        }
        if (mechanic == Mechanic.AURA || mechanic == Mechanic.WARCRY || mechanic == Mechanic.SUMMON || mechanic == Mechanic.GUARD
                || mechanic == Mechanic.META || inner.deploy() != null || inner.tags().contains(Tags.PERSISTENT)) {
            return "圖騰、常駐、戰吼不行";
        }
        return null;
    }

    /** 這顆輔助套在空無型態自己身上：恆星、倍星（空無型態用掉銳星時）。 */
    public static boolean appliesToHost(final SupportDefinition support) {
        return support.needsChargeSpend();
    }

    /** 幻影出現的間隔（tick）：孔裡那個攻擊的攻擊間隔 ÷ (1 − 30%)。 */
    public static double stepTicks(final double attackIntervalTicks) {
        return Math.max(1, attackIntervalTicks) / (1 - LESS_ATTACK_SPEED / 100.0);
    }

    /** 引導開始到第一個幻影（tick）：一次間隔的 30%。 */
    public static long startTicks(final double stepTicks) {
        return Math.max(1, Math.round(stepTicks * START_SHARE));
    }

    /** 一個幻影的消耗：孔裡那個攻擊消耗的 80%（四捨五入）。 */
    public static int imageCost(final int socketedCost) {
        return socketedCost <= 0 ? 0 : (int) Math.round(socketedCost * COST_SHARE);
    }

    /** 這一次顯現幾個幻影：counted = 銳星算用掉幾顆（0 沒有、1 一般、2 倍星）。 */
    public static int images(final int counted) {
        return 1 + EXTRA_IMAGES * Math.max(0, counted);
    }

    /** 幻影用的那一下：傷害更少 15%、不能消耗戰星。 */
    public static SkillBuild imageBuild(final SkillBuild attack) {
        return attack.withProfile(attack.profile().withLocal(List.of(
                Modifier.more(StatKeys.DAMAGE, -LESS_DAMAGE / 100.0, "虛空形態"),
                Modifier.flat(ChargeService.CANNOT_CONSUME, 1, "虛空形態"))));
    }
}
