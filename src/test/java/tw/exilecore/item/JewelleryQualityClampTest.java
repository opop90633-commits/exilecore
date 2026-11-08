package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-08 照站長「裂隙指環 60% 品質正常嗎」：拿掉「+x% 最大品質」之後品質壓回上限；用過瓦爾灌注器的最多上限 +10%。 */
class JewelleryQualityClampTest {

    static ItemRepository repository;

    @BeforeAll
    static void load() throws Exception {
        repository = new ItemRepository();
        repository.load(Path.of("src/main/resources/data"));
    }

    private static ItemData ring(final int quality) {
        final ItemData data = new ItemData("jring_breach", Rarity.RARE, 84);
        data.setQualityUpTo(quality, quality);
        return data;
    }

    @Test
    void withoutTheEssenceLineQualityFallsBackToFortyPercent() {
        final ItemData data = ring(60);
        AffixRescale.apply(repository, data);
        assertEquals(40, data.quality(), "裂隙指環 20%＋20%，沒用過灌注器壓回 40%");
    }

    @Test
    void infusedRingsKeepTenPercentOverTheCap() {
        final ItemData data = ring(60);
        data.setVaalInfused(true);
        AffixRescale.apply(repository, data);
        assertEquals(50, data.quality());
    }

    @Test
    void theEssenceLineKeepsSixtyPercent() {
        final ItemData data = ring(60);
        data.affixes().add(new RolledAffix("ess_breach_quality", 1, Map.of("value", 20)));
        AffixRescale.apply(repository, data);
        assertEquals(60, data.quality());
        assertEquals(60, AffixRescale.jewelleryQualityMax(repository, data));
    }
}
