package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemBase;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemSlot;
import tw.exilecore.item.Rarity;
import tw.exilecore.stats.KeystoneKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2 0.5（poe2db Sceptres）：權杖是單手武器，主手、副手都能放；不能兩把權杖；
 * 雙手武器配權杖只有荒野領主（獸魂符、非傳奇）。這次之前副手的權杖只有荒野領主才行。
 */
class SceptreHandTest {

    private static ItemBase base(final String id, final ItemSlot slot, final String... tags) {
        return new ItemBase(id, id, "STONE", null, slot, Set.of(tags), 1, new ItemBase.Requirements(1, 0, 0, 0), Map.of(),
                1.2, 5, 0, 0, 0, 0, 0, null, 10, false, null, 1, 1, null);
    }

    private static final ItemBase SCEPTRE = base("sceptre", ItemSlot.MAIN_HAND, "weapon", Tags.ONE_HAND, "mace", "sceptre", "melee");
    private static final ItemBase WAND = base("wand", ItemSlot.MAIN_HAND, "weapon", Tags.ONE_HAND, "wand", "caster");
    private static final ItemBase MACE = base("mace", ItemSlot.MAIN_HAND, "weapon", Tags.ONE_HAND, Tags.MACE, Tags.MELEE);
    private static final ItemBase BOW = base("bow", ItemSlot.MAIN_HAND, "weapon", Tags.TWO_HAND, Tags.BOW);
    private static final ItemBase TALISMAN = base("talis", ItemSlot.MAIN_HAND, "weapon", Tags.TWO_HAND, Tags.TALISMAN, Tags.MELEE);
    private static final ItemBase SHIELD = base("shield", ItemSlot.OFF_HAND, "shield", "str_armour");
    private static final ItemBase FOCUS = base("focus", ItemSlot.OFF_HAND, "shield", "focus");

    @Test
    void sceptreGoesInTheOffHandWithAOneHandedWeapon() {
        final StatSheet none = new StatSheet();
        assertTrue(Gear.sceptreOffHand(WAND, SCEPTRE, none), "魔杖＋副手權杖");
        assertTrue(Gear.sceptreOffHand(MACE, SCEPTRE, none), "單手錘＋副手權杖");
        assertTrue(Gear.sceptreOffHand(null, SCEPTRE, none), "主手空著");
        assertTrue(Gear.sceptreOffHand(WAND, SCEPTRE, null), "不用看天賦");
        assertNull(Gear.offHandSceptreProblem(WAND, SCEPTRE, new ItemData("sceptre", Rarity.UNIQUE, 80), none), "單手武器配傳奇權杖也行");
    }

    @Test
    void noTwoSceptresAndNoTwoHandersExceptLordOfTheWilds() {
        final StatSheet none = new StatSheet();
        assertFalse(Gear.sceptreOffHand(SCEPTRE, SCEPTRE, none));
        assertEquals("不能同時拿兩把權杖", Gear.offHandSceptreProblem(SCEPTRE, SCEPTRE, null, none));
        assertFalse(Gear.sceptreOffHand(BOW, SCEPTRE, none));
        assertNotNull(Gear.offHandSceptreProblem(BOW, SCEPTRE, null, none));
        assertFalse(Gear.sceptreOffHand(TALISMAN, SCEPTRE, none), "沒點荒野領主");
        final StatSheet lord = new StatSheet().add(Modifier.flat(KeystoneKeys.FLAG_LORD_OF_WILDS, 1, "測試"));
        assertTrue(Gear.sceptreOffHand(TALISMAN, SCEPTRE, lord));
        assertNull(Gear.offHandSceptreProblem(TALISMAN, SCEPTRE, new ItemData("sceptre", Rarity.RARE, 80), lord));
        assertNotNull(Gear.offHandSceptreProblem(TALISMAN, SCEPTRE, new ItemData("sceptre", Rarity.UNIQUE, 80), lord), "荒野領主只收非傳奇");
        assertFalse(Gear.sceptreOffHand(WAND, MACE, none), "不是權杖");
    }

    @Test
    void sceptreInTheMainHandStillTakesAShieldOrFocus() {
        assertNull(Gear.pairProblem(SCEPTRE, SHIELD));
        assertNull(Gear.pairProblem(SCEPTRE, FOCUS));
    }
}
