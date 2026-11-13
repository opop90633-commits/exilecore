package tw.exilecore.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-12 照站長「延遲落地的技能：同一個鍵再按一次就能提早炸，提早炸按比例調整傷害」（不是原作的做法；原作只能用別的引爆技能提早引爆）：
 * <ul>
 *   <li>技能：爆裂榴彈、子母榴彈、炎爆矛、寒晶雷（{@link #SKILLS}）。</li>
 *   <li>什麼時候算「再按一次」：這一招<b>不能再放</b>的時候——冷卻中而且次數用完（榴彈、寒晶雷），或炎爆矛插滿上限；還能放就照常放
 *       （站長選的，免得存 3 次的榴彈、疊好幾支的炎爆矛只能有一個在場上）。</li>
 *   <li>引爆你放出去的那一招裡最舊的一個；放出去不到 {@link #MIN_AGE} tick 的不算（按住左鍵連發時不會一丟出去就炸）。</li>
 *   <li>按住不放不算：同一招兩次按鍵隔超過 {@link #FRESH_GAP} tick 才是新的一下（按住左鍵每 tick、右鍵每 4 tick 會送一次）。</li>
 *   <li>傷害 {@link #scale}：50% ＋ 50% × 經過的時間 ÷ 原本的倒數。寒晶雷不扣（提早炸已經少疊好幾層曝露）；
 *       子母榴彈的子榴彈不扣；炎爆矛被引爆類技能提早引爆照舊 100%（只有自己再按一次才扣）。</li>
 *   <li>引爆不算施放：不花魔力、不跑冷卻、不佔動作時間。</li>
 * </ul>
 */
final class EarlyDetonation {

    static final Set<String> SKILLS = Set.of("blast_grenade", "mother_child_grenade", "explosive_spear", "chill_crystal_mine");
    /** 提早炸不扣傷害的（寒晶雷）。 */
    static final Set<String> NO_SCALE = Set.of("chill_crystal_mine");
    /** 放出去至少幾 tick 才能提早炸（0.2 秒）。 */
    static final long MIN_AGE = 4;
    /** 同一招兩次按鍵隔幾 tick 以上才算新的一下。 */
    static final long FRESH_GAP = 5;
    /** 剛放出去馬上炸的傷害比例。 */
    static final double MIN_SCALE = 0.5;

    private final Map<String, Long> lastPress = new HashMap<>();

    private EarlyDetonation() {
    }

    static EarlyDetonation create() {
        return new EarlyDetonation();
    }

    /** 提早炸的傷害比例：50% ＋ 50% × elapsed ÷ total（夾在 50%～100%）。純函式。 */
    static double scale(final long elapsed, final long total) {
        if (total <= 0) {
            return 1.0;
        }
        final double done = Math.max(0, Math.min(1, (double) elapsed / total));
        return MIN_SCALE + (1 - MIN_SCALE) * done;
    }

    /** 這一招這次按鍵是不是新的一下（同時記下這次）；不是提早引爆的技能回傳 false。 */
    boolean press(final UUID player, final String skillId, final long now) {
        if (!SKILLS.contains(skillId)) {
            return false;
        }
        if (lastPress.size() > 4096) {
            lastPress.values().removeIf(tick -> now - tick > 200);
        }
        final Long previous = lastPress.put(player + ":" + skillId, now);
        return previous == null || now - previous > FRESH_GAP;
    }

    /** 純函式版（測試用）：上一次按鍵在 previous（null = 沒按過），這次在 now，算不算新的一下。 */
    static boolean fresh(final Long previous, final long now) {
        return previous == null || now - previous > FRESH_GAP;
    }

    void forget(final UUID player) {
        lastPress.keySet().removeIf(key -> key.startsWith(player + ":"));
    }
}
