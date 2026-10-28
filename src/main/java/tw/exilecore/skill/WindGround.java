package tw.exilecore.skill;

import tw.exilecore.item.unique.UniqueStats;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-10-28 照 PoE2 貝雷克的戒指、元素之章：「會被元素地面強化的疾風技能視為受到點燃／冰緩／感電地面強化」
 * 「…可以同時被好幾種元素地面強化」。疾風技能＝旋矛擊的旋風、旋風矛（{@link Signatures}、{@link Twisters}，
 * 碰到元素地面變成元素旋風的那兩種，見 {@link ElementalGround}）。
 * <ul>
 *   <li>沒有「好幾種」：沒踩到元素地面時，視為踩在裝備寫的那一種上（好幾件寫不同的，照火、冰、電的順序取第一種）。</li>
 *   <li>有「好幾種」：踩到的那一種＋裝備寫的每一種都算，各獲得那種元素的額外傷害。</li>
 * </ul>
 * 這次之前這幾條做不出來，貝雷克的冰與雷之握改成「對感電的敵人增加 20% 傷害」。
 *
 * @param forced   裝備讓疾風技能視為受到哪幾種元素地面強化（照火、冰、電的順序）
 * @param multiple 可以同時被好幾種強化
 */
record WindGround(List<DamageType> forced, boolean multiple) {

    static final WindGround NONE = new WindGround(List.of(), false);

    WindGround {
        forced = List.copyOf(forced);
    }

    /** 從擁有者的屬性表讀。 */
    static WindGround of(final StatSheet sheet) {
        if (sheet == null) {
            return NONE;
        }
        final List<DamageType> forced = new ArrayList<>();
        if (sheet.flat(UniqueStats.WIND_GROUND_FIRE, Set.of()) > 0) {
            forced.add(DamageType.FIRE);
        }
        if (sheet.flat(UniqueStats.WIND_GROUND_COLD, Set.of()) > 0) {
            forced.add(DamageType.COLD);
        }
        if (sheet.flat(UniqueStats.WIND_GROUND_LIGHTNING, Set.of()) > 0) {
            forced.add(DamageType.LIGHTNING);
        }
        final boolean multiple = StatKeys.flag(sheet, UniqueStats.WIND_GROUND_MULTIPLE);
        return forced.isEmpty() && !multiple ? NONE : new WindGround(forced, multiple);
    }

    /** 沒踩到元素地面時視為哪一種（沒有回傳 null）：旋風一放出來就是元素旋風（特效照這個）。 */
    DamageType primary() {
        return forced.isEmpty() ? null : forced.getFirst();
    }

    /** 這個旋風算是被哪幾種元素地面強化（ground = 實際踩到的或已經變成的元素，可以是 null）。 */
    List<DamageType> elements(final DamageType ground) {
        final List<DamageType> list = new ArrayList<>();
        if (ground != null) {
            list.add(ground);
        }
        if (multiple || list.isEmpty()) {
            for (final DamageType type : forced) {
                if (!list.contains(type)) {
                    list.add(type);
                }
                if (!multiple) {
                    break;
                }
            }
        }
        return list;
    }

    /** 每一種元素各獲得 gain% 傷害為那種元素的額外傷害。 */
    List<Modifier> gains(final DamageType ground, final double gain, final String why) {
        final List<Modifier> list = new ArrayList<>();
        for (final DamageType type : elements(ground)) {
            list.add(Modifier.flat(StatKeys.damageExtra(type), gain, why));
        }
        return list;
    }
}
