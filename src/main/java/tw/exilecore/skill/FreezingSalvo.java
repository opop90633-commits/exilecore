package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 Freezing Salvo（凜霜扇矢，PoB2 FreezingSalvoPlayer）：
 * <ul>
 *   <li>每一次「正常的」重複（解開封印、多的投射物換成的重複）再多重複一次，瞄準準心 secondary radius（3 公尺，吃範圍）內一個你的冰裂，
 *       那個冰裂立刻引爆（For each time it Repeats normally, it will also Repeat an additional time targeting a different Ice Fragment）；</li>
 *   <li>投射物數量的修飾器改成加在重複次數上（base_modifiers_to_number_of_projectiles_instead_apply_to_repeats）。</li>
 * </ul>
 * 屬性代號與純函式，不依賴 Bukkit。這次之前冰裂引爆沒做、多的投射物照樣一次多射。
 */
public final class FreezingSalvo {

    /** FLAT（格）：重複時找冰裂的範圍（0 = 這個技能不瞄冰裂）。 */
    public static final String FRAGMENT_RADIUS = "salvo.fragment_radius";
    /** FLAT（旗標）：投射物數量的修飾器改成加在重複次數上。 */
    public static final String PROJECTILES_TO_REPEATS = "salvo.projectiles_to_repeats";
    /** FLAT（記號，SkillService 加在封印重複的那幾下上）：這一下是正常的重複。 */
    public static final String REPEATING = "salvo.repeating";

    private FreezingSalvo() {
    }

    /** 多的投射物換成幾次重複：投射物 − 1（至少 0）。 */
    public static int repeatsFromProjectiles(final int projectiles) {
        return Math.max(0, projectiles - 1);
    }
}
