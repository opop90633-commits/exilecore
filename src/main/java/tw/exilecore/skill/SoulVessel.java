package tw.exilecore.skill;

import tw.exilecore.stats.Tags;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」：PoE2 Spirit Vessel（poe2db：靈魂容器；遺忘看守賦予，固定 16 級；
 * PoB2 SpiritVesselPlayer＋SpiritVesselSupport）。主要寶石（夥伴、常駐、召喚物、Meta），綁在上方按鍵格就叫出一隻會變形的靈魂容器夥伴，
 * 牠用孔裡巨熊、狼人、飛龍技能的複製打人（照召喚物算：召喚物的屬性表、召喚物等級＝寶石等級 × 2）。
 * <ul>
 *   <li>孔裡只能放帶 shapeshift 又帶 bear／werewolf／wyvern 的技能（原作 SpiritVesselSupport 的 requireSkillTypes；內建觸發的不行）。
 *       原作 ignore_shapeshift_talisman_requirement：不用拿獸魂符。</li>
 *   <li>孔裡每一個不同的技能讓靈魂容器造成 20% 更多傷害（spirit_vessel_damage_+%_final_per_skill_socketed_in_meta_gem 20；
 *       一條「更多」= 20% × 種數，{@link #damageMore}）。</li>
 *   <li>品質：召喚物 (0—20)% 更多最大生命（gem_quality.yml）。</li>
 *   <li>只有一隻（skill_desired_amount_override 1，不吃 +召喚物數量）；死了 12 秒後自己回來（is_resummoning_minion）。</li>
 * </ul>
 * 純邏輯（GemService 驗孔、SkillService 算孔裡的技能、MinionService 讓牠出手）。
 */
public final class SoulVessel {

    private SoulVessel() {
    }

    /** 靈魂容器。 */
    public static final String ID = "soul_vessel";

    /** 孔裡每一個不同的技能給的更多傷害（%）。 */
    public static final double MORE_PER_SKILL = 20;

    /** 用了某個形態的技能之後，外觀維持在那個形態多久（tick；玩家變身是 8 秒）。 */
    public static final long FORM_TICKS = 160;

    /** 這個技能是不是靈魂容器。 */
    public static boolean hosts(final SkillDefinition skill) {
        return skill != null && ID.equals(skill.id());
    }

    /** 孔裡要放什麼（給玩家看）。 */
    public static String wants() {
        return "巨熊、狼人或飛龍";
    }

    /** 這顆技能能不能放進靈魂容器的孔；可以回傳 null，不行回傳原因。 */
    public static String problem(final SkillDefinition inner) {
        if (inner == null || inner.hidden()) {
            return "不認得的技能";
        }
        if (inner.grantedOnly()) {
            return MetaGems.GRANTED_ONLY;
        }
        if (inner.tags().contains(Tags.META) || SupportDefinition.hostsSkill(inner) || SocketedWarcry.hosts(inner)) {
            return "主要寶石不能放";
        }
        if (form(inner.tags()) == null) {
            return "只能放巨熊、狼人、飛龍的變身技能";
        }
        return null;
    }

    /** 標籤是哪一種形態（熊形態、狼人形態、飛龍形態，跟玩家變身的增益名稱一樣）；不是變身技能回傳 null。 */
    public static String form(final Set<String> tags) {
        if (tags == null || !tags.contains(Tags.SHAPESHIFT)) {
            return null;
        }
        if (tags.contains(Tags.BEAR)) {
            return "熊形態";
        }
        if (tags.contains(Tags.WEREWOLF)) {
            return "狼人形態";
        }
        if (tags.contains(Tags.WYVERN)) {
            return "飛龍形態";
        }
        return null;
    }

    /** 頭套模型代號（ShapeshiftCostumes 的 form_bear…）；不是變身形態回傳 null。 */
    public static String headModel(final String form) {
        if (form == null) {
            return null;
        }
        return switch (form) {
            case "熊形態" -> "form_bear";
            case "狼人形態" -> "form_werewolf";
            case "飛龍形態" -> "form_wyvern";
            default -> null;
        };
    }

    /** 孔裡有幾個不同的技能（同一個技能放兩顆只算一次；放錯的不算）。 */
    public static int distinct(final Collection<String> socketedIds) {
        final Set<String> seen = new HashSet<>();
        if (socketedIds != null) {
            for (final String id : socketedIds) {
                if (id != null && !id.isBlank()) {
                    seen.add(id);
                }
            }
        }
        return seen.size();
    }

    /** 靈魂容器更多幾 % 傷害：每個不同的技能 20%（0 個就沒有）。 */
    public static double damageMore(final int distinctSkills) {
        return MORE_PER_SKILL * Math.max(0, distinctSkills);
    }

    /** 靈魂容器會不會自己用這個技能（攻擊才用；戰吼、增益放進去只算種數，原作的召喚物技能類型是攻擊）。 */
    public static boolean uses(final SkillDefinition inner) {
        return inner != null && inner.isAttack() && problem(inner) == null;
    }

    /** 這個技能的攻擊間隔（tick）：靈魂容器自己的攻擊間隔 ÷ 技能的攻擊速度倍率（最少 4 tick）。 */
    public static long attackTicks(final int vesselTicks, final double skillAttackSpeed) {
        final double speed = skillAttackSpeed > 0 ? skillAttackSpeed : 1.0;
        return Math.max(4, Math.round(vesselTicks / speed));
    }
}
