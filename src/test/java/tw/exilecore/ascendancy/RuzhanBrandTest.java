package tw.exilecore.ascendancy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照 PoE2 Ruzhan's Brand：炎刃．羅贊自帶的指令。 */
class RuzhanBrandTest {

    @Test
    void manaFollowsTheRefRange() {
        assertEquals(2, AscGranted.brandMana(1), "原作 2～29 的下限");
        assertEquals(29, AscGranted.brandMana(20), "原作 2～29 的上限");
        assertEquals(2, AscGranted.brandMana(0));
    }

    @Test
    void onlyThreeBrandsPerPlayerOldestGoesFirst() {
        final UUID me = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        final List<AscGranted.Brand> list = new ArrayList<>();
        list.add(new AscGranted.Brand(other, null, 1, 1, null));
        for (int i = 0; i < 4; i++) {
            list.add(new AscGranted.Brand(me, null, 10 + i, 1, null));
        }
        final List<AscGranted.Brand> early = AscGranted.trimBrands(list, me, AscGranted.BRAND_LIMIT);
        assertEquals(1, early.size(), "超過上限的那個提早炸掉");
        assertEquals(10, early.get(0).explodeAt());
        assertEquals(4, list.size(), "別人的不動，自己的剩 3 個");
        assertEquals(other, list.get(0).owner());
        assertEquals(11, list.get(1).explodeAt(), "最舊的（10）先拿掉");
    }
}
