package tw.exilecore.stats;

import java.util.Locale;
import java.util.Set;

/**
 * 「最近…」的條件（2026-10，PoE2 的 recently：4 秒內）。
 * <p>
 * 裝備、珠寶的詞綴把條件寫在屬性代號後面：{@code 屬性@事件}（最近發生過才有效）或 {@code 屬性@no事件}（最近沒發生過才有效），
 * 例如 {@code evasion@nohit}、{@code damage@projhit}、{@code stun.threshold@nostun}。
 * 事件由各處在發生時記到 {@link tw.exilecore.character.PlayerState#markRecent}，屬性表重算時照條件決定那條詞綴算不算
 * （{@link tw.exilecore.character.PlayerStateService}）；條件變了就重算。
 * <p>
 * 兩個不是「最近」而是「效果中」的條件：{@link #LIFE_FLASK}、{@link #MANA_FLASK}（生命／魔力藥劑的回復還在進行）。
 */
public final class Recently {

    /** 最近被擊中。 */
    public static final String HIT = "hit";
    /** 最近翻滾過。 */
    public static final String DODGE = "dodge";
    /** 最近格擋過。 */
    public static final String BLOCK = "block";
    /** 最近用過護符（護符觸發）。 */
    public static final String CHARM = "charm";
    /** 最近喝過藥劑。 */
    public static final String FLASK = "flask";
    /** 生命藥劑效果中（回復還在進行）。 */
    public static final String LIFE_FLASK = "lifeflask";
    /** 魔力藥劑效果中。 */
    public static final String MANA_FLASK = "manaflask";
    /** 最近用投射物攻擊擊中過敵人。 */
    public static final String PROJECTILE_HIT = "projhit";
    /** 最近用近戰擊中過敵人。 */
    public static final String MELEE_HIT = "meleehit";
    /** 最近擊中過敵人（任何技能）。 */
    public static final String ENEMY_HIT = "enemyhit";
    /** 最近暴擊過。 */
    public static final String CRIT = "crit";
    /** 最近擊殺過。 */
    public static final String KILL = "kill";
    /** 最近被重暈過。 */
    public static final String STUN = "stun";
    /** 最近消耗過屍體（骸爆這類）。 */
    public static final String CORPSE = "corpse";
    /** 最近消耗過銳星／怒星／磐星。 */
    public static final String POWER = "power";
    public static final String FRENZY = "frenzy";
    public static final String ENDURANCE = "endurance";

    public static final Set<String> EVENTS = Set.of(HIT, DODGE, BLOCK, CHARM, FLASK, LIFE_FLASK, MANA_FLASK, PROJECTILE_HIT, MELEE_HIT, ENEMY_HIT,
            CRIT, KILL, STUN, CORPSE, POWER, FRENZY, ENDURANCE);

    /** 一個條件：哪個事件、要不要取反（{@code @no…}）。 */
    public record Condition(String event, boolean negated) {
        public String key() {
            return "@" + (negated ? "no" : "") + event;
        }
    }

    private Recently() {
    }

    /** 屬性代號裡有沒有條件（{@code @}）。 */
    public static boolean hasCondition(final String stat) {
        return stat.indexOf('@') > 0;
    }

    /** 拿掉條件的屬性代號。 */
    public static String stat(final String stat) {
        final int at = stat.indexOf('@');
        return at > 0 ? stat.substring(0, at) : stat;
    }

    /** 屬性代號後面的條件（{@code @hit}、{@code @nohit}…）；沒有或不認得回傳 null。 */
    public static Condition condition(final String stat) {
        final int at = stat.indexOf('@');
        if (at <= 0) {
            return null;
        }
        final String text = stat.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        // 2026-10-26：天賦樹的狀態條件（靜止、被包圍、低血…，見 TreeConditions）也走這裡
        if (EVENTS.contains(text) || TreeConditions.isSelf(text)) {
            return new Condition(text, false);
        }
        if (text.startsWith("no") && TreeConditions.isSelf(text.substring(2))) {
            return new Condition(text.substring(2), true);
        }
        return null;
    }

    /** 條件的中文（詞綴說明、審核用）。 */
    public static String describe(final Condition condition) {
        final String event = switch (condition.event()) {
            case HIT -> "被擊中";
            case DODGE -> "翻滾";
            case BLOCK -> "格擋";
            case CHARM -> "用過護符";
            case FLASK -> "喝過藥劑";
            case LIFE_FLASK -> "生命藥劑效果中";
            case MANA_FLASK -> "魔力藥劑效果中";
            case PROJECTILE_HIT -> "用投射物攻擊擊中";
            case MELEE_HIT -> "近戰擊中";
            case ENEMY_HIT -> "擊中敵人";
            case CRIT -> "暴擊";
            case KILL -> "擊殺";
            case STUN -> "被重暈";
            case CORPSE -> "消耗屍體";
            case POWER -> "消耗銳星";
            case FRENZY -> "消耗怒星";
            case ENDURANCE -> "消耗磐星";
            default -> condition.event();
        };
        if (LIFE_FLASK.equals(condition.event()) || MANA_FLASK.equals(condition.event())) {
            return condition.negated() ? "不在" + event : event;
        }
        return "最近" + (condition.negated() ? "沒有" : "") + event + "過";
    }
}
