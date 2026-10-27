package tw.exilecore.skill;

import java.util.Set;

/**
 * 2026-10-27 照 PoE2：哪些技能能插進星咒圖騰（Spell Totem）、英靈圖騰（Ancestral Warrior Totem）。
 * 原作（PoB 資料 SupportMetaTotemSpellTotemPlayer／SupportAncestralWarriorTotemPlayer）：
 * <ul>
 *   <li>星咒圖騰：要「法術」且「可由圖騰施放（Totemable）」；不能是主要寶石（Meta）、有使用條件（HasUsageCondition）、
 *       有冷卻、被觸發（Triggered）、常駐（Persistent）的技能。</li>
 *   <li>英靈圖騰：要「攻擊」且「可由圖騰施放」（錘子技能）；不能是主要寶石、有使用條件、有冷卻、引導（Channel）、被觸發、常駐的技能。</li>
 * </ul>
 * 冷卻、常駐、召喚、位移…已經在 {@link MetaGems#problem}、{@link SkillService#linkedProblem} 照機制擋掉；
 * 這裡補的是那些規則擋不到的（逐一對過 skills.yml 所有看得到的技能）：主要寶石、常駐、引導（channelling 標籤：千鈞砸、蓄勁一擊、擂盾），
 * 以及原作沒有 Totemable 或有使用條件的（下面兩個清單）。這次之前這些都能放。
 */
final class TotemRules {

    private TotemRules() {
    }

    /** 星咒圖騰不能放的法術（技能代號）：原作沒有 Totemable。 */
    static final Set<String> NOT_SPELL_TOTEM = Set.of(
            "profane_ritual",   // 穢陣（Profane Ritual）
            "bone_blast",       // 骨綻（Bone Blast）
            "galvanic_field");  // 雷流場（Galvanic Field）

    /** 英靈圖騰不能放的錘子技能（技能代號）。 */
    static final Set<String> NOT_ANCESTRAL = Set.of(
            "hammer_of_gods",     // 天罰巨鎚（Hammer of the Gods）：有使用條件（要榮耀）
            "resonating_shield",  // 擂盾（Resonating Shield）：引導
            "shield_wall");       // 土壁擊（Shield Wall）：原作沒有 Totemable

    /** 星咒圖騰：這個法術原作能不能交給圖騰放；可以回傳 null，不行回傳原因。 */
    static String spellTotemProblem(final SkillDefinition spell) {
        if (spell.tags().contains(tw.exilecore.stats.Tags.META) || spell.tags().contains("persistent")) {
            return "不能交給法術圖騰放（主要寶石、常駐技能不行）";
        }
        return NOT_SPELL_TOTEM.contains(spell.id()) ? "不能交給法術圖騰放（原作這個法術不能由圖騰施放）" : null;
    }

    /** 英靈圖騰：這個錘子技能原作能不能給英靈用；可以回傳 null，不行回傳原因。 */
    static String ancestralProblem(final SkillDefinition skill) {
        if (skill.tags().contains(tw.exilecore.stats.Tags.META) || skill.tags().contains("persistent")) {
            return "不能是主要寶石、常駐技能";
        }
        if (skill.tags().contains("channelling") || skill.tags().contains("channel")) {
            return "不能是引導技能";
        }
        return NOT_ANCESTRAL.contains(skill.id()) ? switch (skill.id()) {
            case "hammer_of_gods" -> "原作有使用條件的技能不行（要榮耀）";
            case "resonating_shield" -> "不能是引導技能";
            default -> "原作這個技能不能交給圖騰";
        } : null;
    }
}
