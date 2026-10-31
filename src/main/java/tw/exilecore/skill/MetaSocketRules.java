package tw.exilecore.skill;

import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.Set;

/**
 * 2026-10-28 照 PoE2 0.5：主要寶石（引星、祈喚、咒擲、擊中時火焰法術）的插槽規則照原作輔助那一面的技能類型
 * （RePoE poe2 skills.json 的 support_gem.allowed_types／excluded_types；對照工具 tools/audit_poe2_meta_sockets.py）：
 * <ul>
 *   <li>暴擊、異變、翻滾引星，元祈、刈祈、障祈引星（SupportMetaCastOn…Player、Support…InvocationPlayer）：要 Spell 而且 Triggerable，
 *       不能是 InbuiltTrigger。</li>
 *   <li>殞從引星（SupportMetaCastOnMinionDeathPlayer）：同上，再加不能是 CreatesMinion（會產生召喚物的：掘骸）。</li>
 *   <li>擊中時火焰法術（SupportMetaCastFireSpellOnHitPlayer）：同上，再加要 Fire（{@link #FIRE}：原作有 Fire 類型、我們沒有 fire 標籤的也算）。</li>
 *   <li>咒擲（SupportSpellslingerPlayer）：同上，再加不能是 Persistent、UsedByProxy（我們的常駐技能本來就擋掉）。</li>
 *   <li>星咒圖騰（SupportMetaTotemSpellTotemPlayer）：要 Spell 而且 Totemable，不能是 Meta、HasUsageCondition、Cooldown、Triggered、Persistent
 *       （見 {@link TotemRules}）。</li>
 * </ul>
 * 原作有對應的技能照原作：{@link #TRIGGERABLE} 是原作 Triggerable、照形狀卻不會被當成可以觸發的；{@link #NOT_TRIGGERABLE} 是原作不是
 * （Spell 而且 Triggerable）、照形狀卻會被當成可以的。原作沒有對應的（Boss 專屬、昇華、輔助觸發的隱藏技能）照形狀：
 * 有 spell 標籤的法術、不是引導、不是常駐、不是本來就放下圖騰的，機制是投射物、新星、錐形、連鎖、衝擊、地面、屍體、印記其中一種。
 * <p>
 * 這次之前不看原作類型，一律照機制擋（常駐、召喚、戰吼、位移、增益、主要寶石不行）：雷遁（原作 Triggerable）插不進暴擊引星，
 * 引導的（碎骨飛矢、焚流、地火柱、炎核爆）、冥偶（原作 SummonsTotem）卻插得進去，
 * 殞從引星也收掘骸（原作 CreatesMinion）。
 */
final class MetaSocketRules {

    private MetaSocketRules() {
    }

    /** 原作 Spell 而且 Triggerable、照形狀卻不算的（我們的代號）。 */
    static final Set<String> TRIGGERABLE = Set.of(
            "thunder_escape",      // 雷遁（Lightning Warp）：位移，被觸發時瞬移到觸發的目標（SkillExecutor.warp）
            // 下面三個是武器賦予的（沒有寶石，插不進主要寶石）；我們做成跟著你的增益（guard），被觸發的路徑放不出來，見 MetaGems.engineProblem
            "might_seal",   // 力印（Sigil of Power）
            "holy_ground",       // 聖地（Consecrate）
            "corpse_devour_feast");  // 噬屍宴（Feast of Flesh）

    /** 原作不是（Spell 而且 Triggerable）、照形狀卻會被當成可以觸發的（我們的代號）。 */
    static final Set<String> NOT_TRIGGERABLE = Set.of(
            // 武器賦予、原作沒有 Triggerable 的（印記原作不是 Spell：SkillDefinition 早就拿掉印記的 spell 標籤，不用寫在這裡）
            "bone_bloom", "magic_absorb_art", "keen_star_drain", "blood_sickle");

    /** 原作 CreatesMinion 的法術（殞從引星不收）。 */
    static final Set<String> CREATES_MINION = Set.of(
            "dig_remains");     // 掘骸（Unearth）：骨刺之後把屍體變骨構體

    /** 原作有 Fire 類型、我們的標籤沒有 fire 的法術（擊中時火焰法術照樣收）。 */
    static final Set<String> FIRE = Set.of(
            "triple_element_curse"); // 元素要害（Elemental Weakness）：原作類型有 Fire、Cold、Lightning

    /** 擊中時火焰法術（原作 allowed_types 再 AND Fire）收不收：有 fire 標籤，或原作有 Fire 類型。 */
    static boolean fire(final SkillDefinition skill) {
        return skill != null && (skill.tags().contains(Tags.FIRE) || FIRE.contains(skill.id()));
    }

    /** 原作能不能被主要寶石觸發（Spell 而且 Triggerable、不是 InbuiltTrigger）。 */
    static boolean triggerable(final SkillDefinition skill) {
        if (skill == null || skill.hidden() || skill.type() != SkillType.SPELL || !skill.tags().contains(Tags.SPELL)) {
            return false;
        }
        if (TRIGGERABLE.contains(skill.id())) {
            return true;
        }
        return !NOT_TRIGGERABLE.contains(skill.id()) && shaped(skill);
    }

    /** 原作 CreatesMinion（殞從引星不收）。 */
    static boolean createsMinion(final SkillDefinition skill) {
        return skill != null && (CREATES_MINION.contains(skill.id()) || skill.mechanic() == Mechanic.SUMMON);
    }

    /** 原作沒有對應的照形狀：一般的傷害法術（不是引導、常駐、放下圖騰的；機制是投射物、新星、錐形、連鎖、衝擊、地面、屍體、印記）。 */
    static boolean shaped(final SkillDefinition skill) {
        if (!skill.tags().contains(Tags.SPELL) || skill.tags().contains("channelling") || skill.tags().contains("persistent")
                || skill.deploy() != null || SkillExecutor.hasFlag(skill, StatKeys.CHANNEL_STAGES)) {
            return false;
        }
        return switch (skill.mechanic()) {
            case PROJECTILE, NOVA, CONE, CHAIN, IMPACT, ZONE, CORPSE, MARK -> true;
            default -> false;
        };
    }
}
