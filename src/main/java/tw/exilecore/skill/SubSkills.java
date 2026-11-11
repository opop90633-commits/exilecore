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
    static final Map<String, String> SUBS = Map.of("thunderbind_staff", "proc_charged_shockwave");
    private static final Set<String> SUB_IDS = Set.copyOf(SUBS.values());

    private static volatile Map<String, SkillDefinition> skills = Map.of();
    /** 2026-11-11：戰吼期間觸發的子技能（熔魂嘯的火山步、火山噴發）：也算子技能，吃得到主技能的輔助。 */
    private static volatile Set<String> triggeredIds = Set.of();

    private SubSkills() {
    }

    /** SkillConfig 讀完技能後呼叫。 */
    static void bind(final Map<String, SkillDefinition> parsed) {
        skills = parsed;
        final java.util.Set<String> ids = new java.util.HashSet<>();
        for (final SkillDefinition skill : parsed.values()) {
            if (skill.warcry() != null) {
                if (skill.warcry().extras().steps() != null) {
                    ids.add(skill.warcry().extras().steps());
                }
                if (skill.warcry().extras().eruption() != null) {
                    ids.add(skill.warcry().extras().eruption());
                }
            }
        }
        triggeredIds = Set.copyOf(ids);
    }

    /** 這個技能帶的子技能（沒有回傳 null）。 */
    static SkillDefinition of(final SkillDefinition skill) {
        final String sub = skill == null ? null : SUBS.get(skill.id());
        return sub == null ? null : skills.get(sub);
    }

    /**
     * 2026-11-11 照站長「碎地放不進熔魂嘯」（PoE2：Ancestral Cry 的寶石標籤有攻擊、近戰、猛擊——它觸發的火山步、火山噴發吃這顆寶石的輔助）：
     * 戰吼期間觸發的技能（warcry.volcanic 的火山步、火山噴發）。沒有回傳空的。
     */
    static java.util.List<SkillDefinition> triggered(final SkillDefinition skill) {
        if (skill == null || skill.warcry() == null) {
            return java.util.List.of();
        }
        final java.util.List<SkillDefinition> list = new java.util.ArrayList<>(2);
        for (final String id : new String[]{skill.warcry().extras().steps(), skill.warcry().extras().eruption()}) {
            final SkillDefinition sub = id == null ? null : skills.get(id);
            if (sub != null) {
                list.add(sub);
            }
        }
        return list;
    }

    /** 是不是某個寶石帶的子技能（隱藏的攻擊，但不是普攻：吃得到主技能的輔助）。 */
    static boolean isSub(final String skillId) {
        return SUB_IDS.contains(skillId) || triggeredIds.contains(skillId);
    }
}
