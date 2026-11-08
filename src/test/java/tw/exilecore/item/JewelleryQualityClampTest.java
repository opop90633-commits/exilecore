package tw.exilecore.item;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-11-08 照站長「照原作」：戒指、項鍊洗掉「+x% 最大品質」之後品質照樣留著（原作 Breach Ring 品質 70%、沒有那條）；
 * 上限照基底＋那條算（催化劑、瓦爾灌注器用）。
 */
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
    void qualityStaysAfterTheMaxQualityLineIsGone() {
        final ItemData data = ring(70);
        AffixRescale.apply(repository, data);
        assertEquals(70, data.quality(), "照原作：不壓回去");
        assertEquals(40, AffixRescale.jewelleryQualityMax(repository, data), "裂隙指環 20%＋20%");
    }

    @Test
    void theEssenceLineRaisesTheCap() {
        final ItemData data = ring(60);
        data.affixes().add(new RolledAffix("ess_breach_quality", 1, Map.of("value", 20)));
        assertEquals(60, AffixRescale.jewelleryQualityMax(repository, data));
    }
}
