package tw.exilecore.ascendancy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

/** 2026-11-02 照站長：110、120 級「到了就有」的稱號：物品數量、稀有度各增加 5%。 */
class MilestoneTitleBonusTest {

    @Test
    void reachTitlesGiveQuantityAndRarity() {
        final List<Modifier> stats = LevelRaceService.reachBonus("逐星者");
        assertEquals(2, stats.size());
        assertEquals(StatKeys.DROP_QUANTITY, stats.get(0).stat());
        assertEquals(StatKeys.DROP_RARITY, stats.get(1).stat());
        for (final Modifier modifier : stats) {
            assertEquals(ModType.INCREASED, modifier.type());
            assertEquals(5, modifier.value(), 1e-9);
        }
    }
}
