package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10：未鑑定、需求不夠的裝備穿不上去。 */
class EquipRuleTest {

    private static final ItemBase.Requirements REQ = new ItemBase.Requirements(30, 40, 0, 20);

    private static ItemData item(final boolean identified) {
        final ItemData data = new ItemData("helmet", Rarity.RARE, 60);
        data.setIdentified(identified);
        return data;
    }

    @Test
    void unidentifiedCannotBeWorn() {
        assertTrue(PlayerStateService.equipProblem(item(false), REQ, 100, 999, 999, 999).contains("鑑定"));
    }

    @Test
    void requirementsMustBeMet() {
        assertNull(PlayerStateService.equipProblem(item(true), REQ, 30, 40, 0, 20), "剛好夠");
        assertTrue(PlayerStateService.equipProblem(item(true), REQ, 29, 40, 0, 20).contains("等級不足"));
        assertTrue(PlayerStateService.equipProblem(item(true), REQ, 30, 39, 0, 20).contains("力量不足"));
        assertTrue(PlayerStateService.equipProblem(item(true), REQ, 30, 40, 0, 19).contains("智慧不足"));
    }
}
