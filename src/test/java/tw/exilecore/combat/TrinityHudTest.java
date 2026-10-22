package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinityHudTest {

    @Test
    void showsResonanceAndBonus() {
        final Trinity trinity = new Trinity();
        final UUID id = UUID.randomUUID();
        assertEquals("", trinity.hudText(id, 0), "沒有共鳴不顯示");
        trinity.hit(id, new DamagePacket().set(DamageType.FIRE, 100), 0);
        final String afterFire = trinity.hudText(id, 0);
        assertTrue(afterFire.contains("火 0") && afterFire.contains("冰 25") && afterFire.contains("電 25"), afterFire);
        assertTrue(!afterFire.contains("元素傷害"), "最低是 0：沒有加成");
        trinity.hit(id, new DamagePacket().set(DamageType.COLD, 100), 1);
        final String both = trinity.hudText(id, 1);
        assertTrue(both.contains("火 25") && both.contains("冰 25") && both.contains("<b>50</b>"), both);
        assertTrue(both.contains("元素傷害 <white>+20%") && both.contains("穿透 15%"), both);
    }
}
