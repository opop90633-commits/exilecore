package tw.exilecore.skill;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

/**
 * 2026-10-26 照 PoE2：「不打人、像打中一樣讓敵人中毒」（Poisons as though hitting）：爆毒矢的毒爆、蝕毒箭與瘴氣榴彈的毒霧。
 * 這次之前這些都是真的打一下（再擲中毒機率）。
 */
final class PoisonOnly {

    private PoisonOnly() {
    }

    /** 這個技能的毒（照 profile 打中時的物理＋混沌，吃中毒的加成）。每一次呼叫算一次，沒有中毒回傳 false。 */
    static boolean apply(final ExileCore plugin, final Player owner, final LivingEntity target, final SkillProfile profile) {
        if (plugin.ailments() == null || owner == null || target == null) {
            return false;
        }
        final StatSheet sheet = plugin.players().sheet(owner);
        final double base = base(sheet, profile);
        return base > 0 && plugin.ailments().poisonFrom(owner, target, base, profile.effective(sheet));
    }

    /** 「像打中一樣」的那一下：物理＋混沌（減免前、不含暴擊）。 */
    static double base(final StatSheet ownerSheet, final SkillProfile profile) {
        final DamagePacket expected = DamageCalculator.expectedOutgoing(ownerSheet, profile);
        return expected.get(DamageType.PHYSICAL) + expected.get(DamageType.CHAOS);
    }

    /** 這個技能的地面效果只上毒、不打人。 */
    static boolean ground(final StatSheet ownerSheet, final SkillProfile profile) {
        return StatKeys.flag(profile.effective(ownerSheet), tw.exilecore.stats.SkillMechanicKeys.FLAG_GROUND_POISON_ONLY);
    }

    /** 這個技能的投射物爆炸只上毒、不打人。 */
    static boolean burst(final StatSheet ownerSheet, final SkillProfile profile) {
        return StatKeys.flag(profile.effective(ownerSheet), tw.exilecore.stats.SkillMechanicKeys.FLAG_BURST_POISON_ONLY);
    }

    /** 這個技能的地面效果要被點燃才會燒（火油榴彈）。 */
    static boolean dormant(final StatSheet ownerSheet, final SkillProfile profile) {
        return StatKeys.flag(profile.effective(ownerSheet), tw.exilecore.stats.SkillMechanicKeys.FLAG_GROUND_NEEDS_IGNITE);
    }
}
