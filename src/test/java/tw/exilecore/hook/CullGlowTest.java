package tw.exilecore.hook;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 2026-10-28 照 PoE2 Killing Palm：可撲殺的敵人只在施放者畫面上發光（PacketEvents 實體資料）；沒有 PacketEvents 退回紅點。 */
class CullGlowTest {

    @Test
    void flagsKeepEntityStateAndAddOutline() {
        assertEquals(0x40, CullGlow.flags(false, false, false, false, false, false, false, true) & 0xFF);
        assertEquals(0x00, CullGlow.flags(false, false, false, false, false, false, false, false) & 0xFF);
        // 著火、隱形照原本的，再加上發光
        assertEquals(0x61, CullGlow.flags(true, false, false, false, true, false, false, true) & 0xFF);
        // 拿掉外框時，本來就在發光的（別的效果讓它發光）保留
        assertEquals(0x40, CullGlow.flags(false, false, false, false, false, true, false, false) & 0xFF);
        assertEquals(0xC0, CullGlow.flags(false, false, false, false, false, false, true, true) & 0xFF);
        assertEquals(0x1A, CullGlow.flags(false, true, true, true, false, false, false, false) & 0xFF);
    }

    @Test
    void clearsOnlyThoseNoLongerCullable() {
        assertEquals(Set.of(1, 3), CullGlow.cleared(Set.of(1, 2, 3), Set.of(2, 4)));
        assertEquals(Set.of(), CullGlow.cleared(Set.of(), Set.of(2)));
    }

    @Test
    void withoutPacketEventsFallsBackToParticles() {
        assertFalse(CullGlow.active(), "沒有啟用（沒有 PacketEvents）：OriginalSkills 照舊冒紅點");
        CullGlow.update(null, java.util.List.of()); // 沒啟用時什麼都不做、不會丟例外
    }
}
