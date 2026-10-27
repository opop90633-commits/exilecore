package tw.exilecore.item.unique;

import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Set;

/**
 * 2026-10-26：傳奇的減免效果（{@link tw.exilecore.stats.DamageCalculator#mitigate} 呼叫）。純函式，不依賴 Bukkit。
 * <ul>
 *   <li>{@link UniqueStats#TAKEN_PHYSICAL_AS_FIRE}：受到的物理傷害有 x% 先換成火焰（照火焰抗性減免）。</li>
 *   <li>{@link UniqueStats#ARMOUR_DEFEND}：防禦時護甲多 x%（畫面上的護甲不變）。</li>
 *   <li>{@link UniqueStats#PHYSICAL_REDUCTION_CAP}：物理傷害減免的上限改成 x%（比原本的低才算）。</li>
 *   <li>{@link UniqueStats#CRUSHED}：被壓碎的怪物物理傷害減免少 x%（可以變成負的，受到更多）。</li>
 *   <li>{@link UniqueStats#TAKEN_ATTACK_PHYSICAL}：攻擊擊中的物理傷害減掉 x 點（減免之後扣，最低 0）。</li>
 * </ul>
 */
public final class UniqueDefence {

    private UniqueDefence() {
    }

    /** 受到的物理傷害有一部分視為火焰傷害；沒有這個效果回傳同一個封包。 */
    public static DamagePacket reroute(final DamagePacket incoming, final StatSheet defender) {
        final double percent = Math.min(100, defender.flat(UniqueStats.TAKEN_PHYSICAL_AS_FIRE, Set.of()));
        final double physical = incoming.get(DamageType.PHYSICAL);
        if (percent <= 0 || physical <= 0) {
            return incoming;
        }
        final DamagePacket copy = new DamagePacket(incoming);
        final double moved = physical * percent / 100.0;
        copy.set(DamageType.PHYSICAL, physical - moved);
        copy.add(DamageType.FIRE, moved);
        return copy;
    }

    /** 防禦時的護甲倍率（1 = 不變）。 */
    public static double armourScale(final StatSheet defender) {
        return 1 + Math.max(0, defender.flat(UniqueStats.ARMOUR_DEFEND, Set.of())) / 100.0;
    }

    /** 物理傷害減免的上限：寫了 physical.reduction_cap 的取比較低的那個。 */
    public static double reductionCap(final StatSheet defender, final double cap) {
        final double own = defender.flat(UniqueStats.PHYSICAL_REDUCTION_CAP, Set.of());
        return own > 0 ? Math.min(cap, own / 100.0) : cap;
    }

    /** 被壓碎：物理傷害減免要扣掉多少（小數）。 */
    public static double crushed(final StatSheet defender) {
        return Math.max(0, defender.flat(UniqueStats.CRUSHED, Set.of())) / 100.0;
    }

    /** 攻擊擊中的物理傷害扣掉固定值（寫 projectile 標籤的只算投射物攻擊）。 */
    public static double flatTaken(final StatSheet defender, final Set<String> skillTags, final double taken) {
        if (taken <= 0 || skillTags == null || !skillTags.contains(Tags.ATTACK)) {
            return taken;
        }
        final double flat = defender.flat(UniqueStats.TAKEN_ATTACK_PHYSICAL, skillTags);
        return flat > 0 ? Math.max(0, taken - flat) : taken;
    }
}
