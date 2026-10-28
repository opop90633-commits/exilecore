package tw.exilecore.skill;

import org.bukkit.Location;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.stats.DamageType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 2026-10-28 照 PoE2（poe2db Whirling Slash、Twister 的 InteractsWithElementalGround）：元素地面（Elemental Ground Surfaces）——
 * 點燃地面、冰緩地面、感電地面。旋矛擊的旋風捲在元素地面上就變成那種元素的旋風；旋風矛的旋風飛過元素地面就變成那種元素的旋風。
 * <p>
 * 我們的元素地面散在好幾個地方（技能的冰緩／感電地面 SkillZones、熾陽之矛的點燃地面 StageLeftovers、寒冰輔助的冰緩地面
 * SupportMechanics、炎爆矛的燃燒地面 SpearMechanics、怪物的足跡與地圖詞綴 MonsterMechanics），玩家技能放的在這裡登記
 * （{@link #mark}），怪物的照 MonsterMechanics 自己的清單查（{@link #at}）。
 * 這次之前沒有這個登記，旋風不會變成元素的。
 */
final class ElementalGround {

    /** 一塊元素地面（world 是世界的 UUID；until 是消失的 tick）。 */
    record Surface(UUID world, double x, double y, double z, double radius, DamageType element, long until) {

        /** 離 (px, py, pz) reach 格內的圓（水平距離；高度差 1.5 格內）有沒有碰到這塊地面。 */
        boolean touches(final UUID inWorld, final double px, final double py, final double pz, final double reach, final long now) {
            if (now >= until || !world.equals(inWorld) || Math.abs(py - y) > HEIGHT) {
                return false;
            }
            final double dx = px - x;
            final double dz = pz - z;
            final double r = radius + Math.max(0, reach);
            return dx * dx + dz * dz <= r * r;
        }
    }

    /** 高度差幾格內算踩在上面。 */
    static final double HEIGHT = 1.5;
    /** 最多記幾塊（超過的最舊的先丟）。 */
    static final int MAX = 400;

    private static final List<Surface> SURFACES = new ArrayList<>();

    private ElementalGround() {
    }

    /** 登記一塊元素地面（只登記火焰、冰冷、閃電；其他的不算元素地面）。 */
    static void mark(final Location center, final double radius, final DamageType element, final long until) {
        if (center == null || center.getWorld() == null || element == null || !element.isElemental() || radius <= 0) {
            return;
        }
        add(SURFACES, new Surface(center.getWorld().getUID(), center.getX(), center.getY(), center.getZ(), radius, element, until));
    }

    /** 純邏輯：加進清單（超過上限丟最舊的）。 */
    static void add(final List<Surface> list, final Surface surface) {
        list.add(surface);
        while (list.size() > MAX) {
            list.removeFirst();
        }
    }

    /** 純邏輯：碰到的元素地面裡最新的那一塊的元素（同時踩到好幾種時照最新放的）；都沒碰到回傳 null。順便清掉過期的。 */
    static DamageType find(final List<Surface> list, final UUID world, final double x, final double y, final double z, final double reach,
                           final long now) {
        list.removeIf(surface -> now >= surface.until());
        for (int i = list.size() - 1; i >= 0; i--) {
            final Surface surface = list.get(i);
            if (surface.touches(world, x, y, z, reach, now)) {
                return surface.element();
            }
        }
        return null;
    }

    /** 點燃、冰緩、感電地面是哪種元素（其他異常的地面不是元素地面，回傳 null）。 */
    static DamageType of(final AilmentType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case IGNITE -> DamageType.FIRE;
            case CHILL, FREEZE -> DamageType.COLD;
            case SHOCK, ELECTROCUTE -> DamageType.LIGHTNING;
            default -> null;
        };
    }

    /** at 附近 reach 格內有沒有元素地面（玩家技能登記的、怪物的足跡與地圖詞綴）；有的話回傳元素，沒有回傳 null。 */
    static DamageType at(final ExileCore plugin, final Location at, final double reach) {
        if (at == null || at.getWorld() == null) {
            return null;
        }
        final long now = plugin.getServer().getCurrentTick();
        final DamageType own = find(SURFACES, at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), reach, now);
        if (own != null) {
            return own;
        }
        if (plugin.monsterModule() == null || plugin.monsterModule().mechanics() == null) {
            return null;
        }
        return of(plugin.monsterModule().mechanics().groundAt(at, reach, now));
    }

    /** 測試用：清掉登記的。 */
    static void clear() {
        SURFACES.clear();
    }
}
