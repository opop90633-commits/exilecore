package tw.exilecore.admin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-30 照站長：管理網頁發放可以指定哪一條詞綴破裂（代號或階級後面加「!」）。 */
class AffixSpecTest {

    @Test
    void fracturedMarkerRoundTrips() {
        final List<AffixSpec.Entry> entries = AffixSpec.parse("life_max@2!:value=80;resist_fire@1");
        assertTrue(entries.get(0).fractured());
        assertEquals("life_max", entries.get(0).id());
        assertEquals(2, entries.get(0).tier());
        assertFalse(entries.get(1).fractured());
        assertEquals("life_max@2!:value=80;resist_fire@1", AffixSpec.format(entries));
        assertTrue(AffixSpec.parse("life_max!").get(0).fractured(), "沒寫階級也可以");
    }

    @Test
    void onlyOneFracturedAffix() {
        assertThrows(IllegalArgumentException.class, () -> AffixSpec.parse("life_max!;resist_fire!"));
    }
}
