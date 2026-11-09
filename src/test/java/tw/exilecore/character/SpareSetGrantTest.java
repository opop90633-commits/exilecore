package tw.exilecore.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 2026-11-10 照站長「武器組 I 綁了武器賦予的技能在左鍵，切到 II 再切回 I，綁定會消失變普攻」。 */
class SpareSetGrantTest {

    @Test
    void swappingAwayKeepsTheBindAndSwappingBackRestoresIt() {
        final SkillLoadout loadout = new SkillLoadout();
        loadout.syncGranted(Map.of("flame_star_shot", 10), Map.of(), true);
        final int position = loadout.find("flame_star_shot");
        loadout.bind(0, position);

        // 切到武器組 II：這把武器在另一組
        loadout.setSpareGranted(Set.of("flame_star_shot"));
        loadout.syncGranted(Map.of(), Map.of(), true);
        assertEquals(position, loadout.find("flame_star_shot"), "另一組武器給的：留在原本那格");
        assertEquals(position, loadout.bound(0), "按鍵還指著它");
        assertTrue(loadout.onSpareSet(loadout.skill(position)), "按下去要先切武器組");
        assertTrue(loadout.dormant(loadout.skill(position)), "現在這一組不能直接用");

        // 切回武器組 I
        loadout.setSpareGranted(Set.of());
        loadout.syncGranted(Map.of("flame_star_shot", 10), Map.of(), true);
        assertEquals(position, loadout.find("flame_star_shot"));
        assertEquals(position, loadout.bound(0), "切回來綁定還在（這次之前變普攻）");
        assertFalse(loadout.dormant(loadout.skill(position)));
    }

    @Test
    void unequippingFromBothSetsStillRemoves() {
        final SkillLoadout loadout = new SkillLoadout();
        loadout.syncGranted(Map.of("flame_star_shot", 10), Map.of(), true);
        loadout.setSpareGranted(Set.of());
        loadout.syncGranted(Map.of(), Map.of(), true);
        assertEquals(-1, loadout.find("flame_star_shot"), "兩組都沒有那把武器：照舊拿掉");
    }
}
