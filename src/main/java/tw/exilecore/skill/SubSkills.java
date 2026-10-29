package tw.exilecore.skill;

import java.util.Map;
import java.util.Set;

/**
 * 2026-10-29 照 PoE2：一顆寶石給兩個技能（Charged Staff = 增益 ChargedStaffPlayer＋攻擊 ChargedStaffShockwavePlayer），
 * 插在寶石上的輔助能輔助其中任何一個就掛得上，效果給吃得到的那個。這裡記「主技能 → 它帶的隱藏子技能」。
 * 這次之前雷縛長棍只照增益（法術、增益）驗，攻擊輔助掛不上，電震波也吃不到任何輔助。
 */
public final class SubSkills {

    /** 主技能 → 子技能（skills.yml 的隱藏技能代號）。 */
    static final Map<String, String> SUBS = Map.of("storm_staff", "proc_charged_shockwave");
    private static final Set<String> SUB_IDS = Set.copyOf(SUBS.values());

    private static volatile Map<String, SkillDefinition> skills = Map.of();

    private SubSkills() {
    }

    /** SkillConfig 讀完技能後呼叫。 */
    static void bind(final Map<String, SkillDefinition> parsed) {
        skills = parsed;
    }

    /** 這個技能帶的子技能（沒有回傳 null）。 */
    static SkillDefinition of(final SkillDefinition skill) {
        final String sub = skill == null ? null : SUBS.get(skill.id());
        return sub == null ? null : skills.get(sub);
    }

    /** 是不是某個寶石帶的子技能（隱藏的攻擊，但不是普攻：吃得到主技能的輔助）。 */
    static boolean isSub(final String skillId) {
        return SUB_IDS.contains(skillId);
    }
}
