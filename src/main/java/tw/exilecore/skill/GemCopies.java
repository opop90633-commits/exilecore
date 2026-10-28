package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;

/**
 * 2026-10-28 照 PoE2：同一個技能寶石可以放好幾顆：面板上兩顆、面板上一顆＋主要寶石孔裡一顆（例如觸發用的冰隕
 * ＋自己按的冰隕）、同一個主要寶石的孔裡放兩顆都可以。每一顆都是自己的技能：自己的等級、品質、輔助
 * （放進主要寶石孔裡的照主要寶石其他孔的輔助）。原作限制的是輔助：同一個技能不能掛兩顆一樣的輔助、
 * 專屬（一個角色一顆）的輔助只能掛一顆，這兩條照舊。
 * <p>
 * 照 PoE 的冷卻規則（poewiki Cooldown 3.11.0：「separate gems of the same skill with a cooldown」共用冷卻）：
 * 同一個技能的冷卻、次數、彈匣、放置上限、召喚物上限照技能算（執行期本來就用技能代號記），好幾顆不會多出冷卻或上限；
 * 主要寶石觸發的那一下照舊照技能的冷卻。
 * <p>
 * 例外（一個角色一顆，跟這次之前一樣）：常駐技能（保留精魂：光環、捷光環、增益、主要寶石、精魂召喚、戰旗這類）與
 * 能放技能寶石的宿主（英靈圖騰、星咒圖騰、引星、預告射擊、裂甲熊咆）。原作同一個常駐增益不疊加；
 * 我們的光環、主要寶石能量、宿主的插槽都照技能代號記，放兩顆只會多保留精魂、兩邊搶同一份能量，所以照舊擋（偏離：
 * 原作的暴擊時施放這類主要寶石可以開兩顆）。
 * <p>
 * 這次之前（2026-10-23 起）同一個技能只能有一顆：面板上有就不能再放進宿主孔裡，反過來也不行。
 */
public final class GemCopies {

    private GemCopies() {
    }

    /** 這個技能一個角色只能放一顆（常駐、宿主；見類別說明）。不認得的技能回傳 false。 */
    public static boolean singleCopy(final SkillConfig config, final String skillId) {
        final SkillDefinition definition = config == null || skillId == null ? null : config.skill(skillId);
        return definition != null && (definition.aura() != null || definition.mechanic() == Mechanic.AURA || config.meta(skillId) != null
                || SupportDefinition.hostsSkill(definition) || SocketedWarcry.hosts(definition));
    }

    /**
     * 技能寶石放進面板第 target 格的問題（只看「同一個技能放好幾顆」這條）；可以回傳 null。
     * 一般的技能不擋；一個角色只能一顆的（{@link #singleCopy}）面板上別格、或宿主孔裡已經有就不行。
     */
    public static String placeProblem(final SkillConfig config, final SkillLoadout loadout, final SkillLoadout.Entry entry, final int target) {
        final String own = copyProblem(config, loadout, entry.skillId(), target, -1, -1);
        if (own != null) {
            return own;
        }
        for (final SkillLoadout.Entry spell : entry.spells()) { // 寶石孔裡放著的技能也看（一般的法術不擋）
            final String inner = copyProblem(config, loadout, spell.skillId(), target, target, -1);
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    /**
     * 技能寶石放進面板第 host 格的宿主的第 index 孔的問題（只看「同一個技能放好幾顆」這條）；可以回傳 null。
     * 一般的技能不擋（面板上有、別的主要寶石裡有、同一個主要寶石別孔有都可以）。
     */
    public static String socketProblem(final SkillConfig config, final SkillLoadout loadout, final SkillLoadout.Entry entry, final int host,
                                       final int index) {
        return copyProblem(config, loadout, entry.skillId(), -1, host, index);
    }

    /** ignorePanel：不算面板這一格（換掉的那一格）；ignoreHost／ignoreIndex：不算這個宿主的這一孔（-1 = 整個宿主）。 */
    private static String copyProblem(final SkillConfig config, final SkillLoadout loadout, final String skillId, final int ignorePanel,
                                      final int ignoreHost, final int ignoreIndex) {
        if (!singleCopy(config, skillId)) {
            return null;
        }
        final SkillDefinition definition = config.skill(skillId);
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry other = loadout.skill(i);
            if (other == null) {
                continue;
            }
            if (i != ignorePanel && other.skillId().equals(skillId)) {
                return "面板上已經有" + definition.name() + "了（" + why(definition) + "）";
            }
            for (int s = 0; s < other.sockets(); s++) {
                final SkillLoadout.Entry spell = other.spellAt(s);
                if (spell != null && spell.skillId().equals(skillId) && !(i == ignoreHost && (ignoreIndex < 0 || ignoreIndex == s))) {
                    return definition.name() + "已經放在其他寶石的孔裡了（" + why(definition) + "）";
                }
            }
        }
        return null;
    }

    private static String why(final SkillDefinition definition) {
        return definition.aura() != null || definition.mechanic() == Mechanic.AURA
                ? "常駐技能一個角色只能放一顆，同一個增益不會疊加"
                : "能放技能寶石的宿主一個角色只能放一顆";
    }
}
