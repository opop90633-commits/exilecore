package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinityHudTest {

    /** 2026-10-26 照 PoE2 0.5 Trinity：打最多的元素 +親和、其他 −3，三種加起來每 30 點更多 x%；這次之前另外兩種 +25、照最低的、有穿透。 */
    @Test
    void showsAffinityAndBonus() {
        final Trinity trinity = new Trinity();
        final UUID id = UUID.randomUUID();
        assertEquals("", trinity.hudText(id, 0), "沒有親和不顯示");
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(Trinity.GAIN_STAT, 13, "鼎元訣"));
        sheet.add(Modifier.flat(Trinity.MORE_STAT, 6, "鼎元訣"));
        trinity.hit(id, new DamagePacket().set(DamageType.FIRE, 100), 0, sheet);
        final String afterFire = trinity.hudText(id, 0);
        assertTrue(afterFire.contains("火 13") && afterFire.contains("冰 0") && afterFire.contains("電 0"), afterFire);
        assertTrue(!afterFire.contains("元素傷害"), "不到 30 點：沒有加成");
        trinity.hit(id, new DamagePacket().set(DamageType.FIRE, 100), 1, sheet);
        trinity.hit(id, new DamagePacket().set(DamageType.COLD, 100), 2, sheet);
        final String both = trinity.hudText(id, 2);
        assertTrue(both.contains("火 23") && both.contains("冰 13"), both);
        assertTrue(both.contains("元素傷害 <white>+6%"), both);
        assertEquals(1, trinity.bonus(id, 2, sheet).size());
        // 8 秒沒拿到開始每秒掉 10
        assertEquals(13, trinity.resonance(id, 2 + 160)[1], 1e-9);
        assertEquals(3, trinity.resonance(id, 2 + 180)[1], 1e-9);
    }
}
