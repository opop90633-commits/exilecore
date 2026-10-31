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
            "filth_array",   // 穢陣（Profane Ritual）
            "bone_bloom",       // 骨綻（Bone Blast）
            "thunder_current_zone");  // 雷流場（Galvanic Field）

    /** 英靈圖騰不能放的錘子技能（技能代號）。 */
    static final Set<String> NOT_ANCESTRAL = Set.of(
            "heaven_punish_maul",     // 天罰巨鎚（Hammer of the Gods）：有使用條件（要榮耀）
            "shield_drum",  // 擂盾（Resonating Shield）：引導
            "earth_wall_strike");       // 土壁擊（Shield Wall）：原作沒有 Totemable

    /**
     * 2026-10-28 照 PoE2 Spell Totem（Totemable）：我們的圖騰（SkillDeployables 的圖騰施法 → SkillExecutor.executeFrom）放得出來的機制。
     * 除了一般的形狀（{@link SkillBuild#deployable}），原作 Totemable 的這幾種也照原作交給圖騰：
     * <ul>
     *   <li>雷遁（Lightning Warp）：圖騰只對範圍內撲殺得到的敵人放，<b>圖騰自己</b>瞬移進去炸開、留感電地面
     *       （閃電灌注給圖騰、不給你；不掛標記、不瞄準滾雷珠）。</li>
     *   <li>詛咒（有範圍的 mark：萎力、遲滯、脆弱、凋魂、元素要害、縛根）：詛咒圈落在圖騰的目標身上，算你的詛咒（佔你的詛咒上限）。</li>
     *   <li>屍體（骸爆）：引爆圖騰目標附近的屍體（沒有就圖騰身邊的）；附近沒有屍體圖騰就換放別的法術。</li>
     * </ul>
     * 一般衝刺（閃現、鏡影穿霧…）、跳躍、印記（沒有範圍的）照舊不行（原作也不是 Totemable 或有冷卻）。
     * 這次之前位移、印記、屍體一律不能交給圖騰（雷遁、骸爆、五個詛咒、縛根原作 Totemable 卻插不進星咒圖騰）。
     */
    static boolean spellTotemCastable(final SkillDefinition spell) {
        return switch (spell.mechanic()) {
            case DASH -> SkillExecutor.hasFlag(spell, tw.exilecore.stats.StatKeys.FLAG_LIGHTNING_WARP);
            case MARK -> spell.params() != null && spell.params().mark() != null && spell.params().mark().curse();
            case CORPSE -> true;
            default -> SkillBuild.deployable(spell);
        };
    }

    /** 星咒圖騰：這個法術原作能不能交給圖騰放；可以回傳 null，不行回傳原因。 */
    static String spellTotemProblem(final SkillDefinition spell) {
        if (spell.tags().contains(tw.exilecore.stats.Tags.META) || spell.tags().contains("persistent")) {
            return "不能交給法術圖騰放（主要寶石、常駐技能不行）";
        }
        return NOT_SPELL_TOTEM.contains(spell.id()) ? "不能交給法術圖騰放（這個法術不能由圖騰施放）" : null;
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
            case "heaven_punish_maul" -> "有使用條件的技能不行（要榮耀）";
            case "shield_drum" -> "不能是引導技能";
            default -> "這個技能不能交給圖騰";
        } : null;
    }
}
