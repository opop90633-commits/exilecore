package tw.exilecore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 2026-11-10 照站長「腐化技能寶石 +1 好像沒有運作」：基底後來才加的固定詞綴，舊物品補上數值。 */
class LateImplicitTest {

    @Test
    void oldRegaliaGetsItsCorruptedGemLevel() throws Exception {
        final ItemRepository items = new ItemRepository();
        items.load(Path.of("src/main/resources/data"));
        final ItemData old = new ItemData("body_str_dex_int_01", Rarity.RARE, 84); // 11/8 之前做的：沒有固定詞綴的變數
        assertTrue(old.implicitVars().isEmpty());
        AffixRescale.apply(items, old);
        assertEquals(1, old.implicitVars().get("value"), "+1 全部已腐化技能寶石等級");
    }
}
