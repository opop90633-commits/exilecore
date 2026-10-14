package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HotbarLayoutTest {

    @Test
    void classicWhenUnset() {
        assertSame(HotbarLayout.CLASSIC, HotbarLayout.of(Map.of()));
        assertEquals("3–8", HotbarLayout.CLASSIC.skillKeysText());
        assertArrayEquals(new int[] {0, 1}, HotbarLayout.CLASSIC.flaskSlots(new int[] {0, 1}));
    }

    @Test
    void legacyLateIsFiveSix() {
        final HotbarLayout layout = HotbarLayout.of(Map.of(HotbarLayout.PROGRESS, "late"));
        assertArrayEquals(new int[] {4, 5}, layout.flaskSlots(new int[] {0, 1}));
        assertEquals("1、2、3、4、7、8", layout.skillKeysText());
    }

    @Test
    void customSlotsLeaveSixSkillKeys() {
        final HotbarLayout layout = HotbarLayout.of(Map.of(HotbarLayout.PROGRESS, "7,2"));
        assertArrayEquals(new int[] {7, 2}, layout.flaskSlots(new int[] {0, 1}));
        assertEquals(6, layout.skillKeys());
        assertEquals(0, layout.skillSlot(0));
        assertEquals(3, layout.skillSlot(2));
        assertEquals(-1, layout.bindForSlot(7));
        assertEquals(SkillLoadout.KEYS + 5, layout.bindForSlot(6));
        assertEquals("7,2", layout.encode());
        assertEquals("1–6", HotbarLayout.custom(6, 7).skillKeysText());
    }

    @Test
    void invalidFallsBackToClassic() {
        assertNull(HotbarLayout.custom(3, 3));
        assertNull(HotbarLayout.custom(8, 1));
        assertSame(HotbarLayout.CLASSIC, HotbarLayout.of(Map.of(HotbarLayout.PROGRESS, "3,3")));
        assertSame(HotbarLayout.CLASSIC, HotbarLayout.of(Map.of(HotbarLayout.PROGRESS, "x")));
    }
}
