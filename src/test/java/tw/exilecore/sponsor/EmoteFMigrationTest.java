package tw.exilecore.sponsor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-11-08 照站長「武器切換系統」：F 改成切換武器組，以前綁在 F 的動作搬到 Q／Shift＋Q。 */
class EmoteFMigrationTest {

    @Test
    void fIsNoLongerAnEmoteKey() {
        assertFalse(Arrays.stream(SponsorService.EmoteKey.values()).anyMatch(key -> key.name().equals("F")));
    }

    @Test
    void legacyFMovesToTheFirstFreeKey() {
        assertEquals(SponsorService.EmoteKey.Q, SponsorService.emoteTarget(null, "dance"));
        assertEquals(SponsorService.EmoteKey.SNEAK_Q, SponsorService.emoteTarget("wave", null));
        assertNull(SponsorService.emoteTarget("wave", "dance"), "Q、Shift＋Q 都綁了：取消");
        assertEquals("sp.emk.F", SponsorService.LEGACY_EMOTE_F);
    }
}
