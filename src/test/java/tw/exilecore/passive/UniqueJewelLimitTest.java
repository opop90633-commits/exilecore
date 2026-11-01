package tw.exilecore.passive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 2026-11-01 照站長：傳奇珠寶是「每一種各限一顆」，不是全部傳奇珠寶只算一顆。 */
class UniqueJewelLimitTest {

    @Test
    void eachDifferentUniqueJewelCounts() {
        final Set<String> seen = new HashSet<>();
        assertTrue(JewelSockets.countsUnique(seen, "megalomaniac"), "第一種");
        assertTrue(JewelSockets.countsUnique(seen, "from_nothing"), "不同種的也算");
        assertFalse(JewelSockets.countsUnique(seen, "megalomaniac"), "同一種的第二顆不算");
    }
}
