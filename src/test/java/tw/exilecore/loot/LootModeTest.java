package tw.exilecore.loot;

import org.junit.jupiter.api.Test;
import tw.exilecore.monster.DropTable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-14 照站長「新增兩種模式」：綁定掉落只綁傳奇與指定的寶石，其他照常能交易。 */
class LootModeTest {

    @Test
    void onlySpecificGemsAreBoostedAndBound() {
        final List<DropTable.Drop> drops = new ArrayList<>();
        drops.add(DropTable.Drop.special(DropTable.Kind.SUPPORT_GEM, 60)); // 隨機的輔助寶石（不是指定的）
        drops.add(DropTable.Drop.support("lineage_test", 80));
        drops.add(DropTable.Drop.skill("boss_skill", 20));
        assertFalse(LootMode.boosted(drops.get(0)));
        assertTrue(LootMode.boosted(drops.get(1)));
        LootMode.bindFrom(drops, 0);
        assertFalse(drops.get(0).bound(), "隨機寶石照常能交易");
        assertTrue(drops.get(1).bound());
        assertTrue(drops.get(2).bound());
        assertEquals("lineage_test", drops.get(1).gem());
        assertEquals(80, drops.get(1).level());
    }

    @Test
    void bindFromLeavesEarlierDropsAlone() {
        final List<DropTable.Drop> drops = new ArrayList<>();
        drops.add(DropTable.Drop.support("a", 1));
        drops.add(DropTable.Drop.support("b", 1));
        LootMode.bindFrom(drops, 1);
        assertFalse(drops.get(0).bound());
        assertTrue(drops.get(1).bound());
    }

    @Test
    void multipliersMatchTheRequest() {
        assertEquals(5, LootMode.BOSS_MORE);
        assertEquals(4, LootMode.LINEAGE_MORE);
    }

    @Test
    void salvageOfBoundItemsIsAFifth() {
        final java.util.Random random = new java.util.Random(1);
        assertEquals(2, LootMode.divide(10, random));
        assertEquals(0, LootMode.divide(0, random));
        long total = 0;
        for (int i = 0; i < 100_000; i++) {
            total += LootMode.divide(1, random);
        }
        assertEquals(0.2, total / 100_000.0, 0.01, "1 個平均拿 0.2 個");
    }
}
