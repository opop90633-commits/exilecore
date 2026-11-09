package tw.exilecore.admin;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.Rarity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-09 照站長「發放要能自訂物品品質、固定詞綴也要能調整數值」。 */
class AffixSpecQualityTest {

    @Test
    void qualityCatalystAndImplicitFlags() {
        final List<AffixSpec.Entry> entries = AffixSpec.parse("flag.quality:value=60;flag.catalyst.attack;flag.implicit:value=33");
        assertTrue(AffixSpec.affixesOf(entries).isEmpty(), "都是旗標，不是詞綴");
        final ItemData data = new ItemData("jring_breach", Rarity.RARE, 80);
        data.setImplicitVars(Map.of("value", 20));
        AffixSpec.applyFlags(data, entries);
        assertEquals(60, data.quality());
        assertEquals("attack", data.catalyst());
        assertEquals(33, data.implicitVars().get("value"));
        assertEquals("flag.quality:value=60;flag.catalyst.attack;flag.implicit:value=33", AffixSpec.format(entries));
    }
}
