package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 2026-10-30 照站長（未切割輔助寶石選技能後，那個技能是不是真的能裝上那顆輔助）：照 PoE2 0.5 的技能類型，
 * 我們的標籤規則讓掛、原作不讓掛的組合擋掉（data/support_poe2.yml，tools/gen_poe2_support_types.py 產生）。
 * {@link SupportDefinition#refusal} 最先問這裡；宿主寶石照孔裡的技能驗，不在表上。
 */
public final class Poe2SupportRules {

    /** 資料檔名。 */
    public static final String FILE = "support_poe2.yml";

    /** 輔助 → 技能 → 擋掉的原因。 */
    private static volatile Map<String, Map<String, String>> blocked = Map.of();

    private Poe2SupportRules() {
    }

    /** 這顆輔助掛在這個技能上原作不讓掛的原因；可以掛（或不在表上）回傳 null。 */
    static String refusal(final String supportId, final String skillId) {
        final Map<String, String> skills = blocked.get(supportId);
        return skills == null ? null : skills.get(skillId);
    }

    /** 讀 support_poe2.yml（SkillConfig.load 呼叫）；技能或輔助代號不認得就報錯（資料跟設定對不上了，要重產）。 */
    static Map<String, Map<String, String>> parse(final YamlNode root, final Set<String> skills, final Set<String> supports) {
        final Map<String, Map<String, String>> out = new HashMap<>();
        final YamlNode section = root.node("blocked");
        if (section == null) {
            return out;
        }
        for (final String support : section.keys()) {
            if (!supports.contains(support)) {
                throw new IllegalArgumentException(FILE + "：不認得的輔助 " + support + "（重跑 tools/gen_poe2_support_types.py）");
            }
            final YamlNode reasons = section.node(support);
            final Map<String, String> bySkill = new HashMap<>();
            for (final String reason : reasons.keys()) {
                for (final String skill : reasons.strings(reason)) {
                    if (!skills.contains(skill)) {
                        throw new IllegalArgumentException(FILE + "：" + support + " 寫了不認得的技能 " + skill + "（重跑 tools/gen_poe2_support_types.py）");
                    }
                    bySkill.put(skill, reason);
                }
            }
            out.put(support, Map.copyOf(bySkill));
        }
        return out;
    }

    static void install(final Map<String, Map<String, String>> rules) {
        blocked = Map.copyOf(rules);
    }
}
