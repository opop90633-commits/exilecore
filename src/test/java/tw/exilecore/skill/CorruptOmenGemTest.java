package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import tw.exilecore.character.SkillLoadout;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** 2026-11-04 照站長「讓技能介面那格也能放必變籤」：技能寶石腐化的擲骰（必變籤靠 changed 重擲）。 */
class CorruptOmenGemTest {

    @Test
    void changedMatchesTheRealDifference() {
        final SkillLoadout.Entry entry = new SkillLoadout.Entry("blazing_star_shell", 20, 3, List.of("pierce", "", "chain")).withQuality(20);
        final SplittableRandom random = new SplittableRandom(7);
        int unchanged = 0;
        for (int i = 0; i < 4000; i++) {
            final GemService.CorruptRoll roll = GemService.rollCorrupt(entry, random);
            assertTrue(roll.updated().corrupted());
            final boolean differs = roll.updated().level() != entry.level() || roll.updated().sockets() != entry.sockets()
                    || roll.updated().quality() != entry.quality();
            assertEquals(differs, roll.changed(), roll.result());
            if (!roll.changed()) {
                unchanged++;
                assertEquals("沒有變化", roll.result());
            }
        }
        // 沒變化大約 25% 多一點（品質擲回原值也算沒變化）
        assertTrue(unchanged > 800 && unchanged < 1500, "unchanged=" + unchanged);
    }

    @Test
    void cannotCorruptTwiceOrGranted() {
        assertNull(GemService.corruptProblem(new SkillLoadout.Entry("blazing_star_shell", 5)));
        assertNotNull(GemService.corruptProblem(null));
        assertNotNull(GemService.corruptProblem(new SkillLoadout.Entry("blazing_star_shell", 5).withCorrupted(true)));
        assertNotNull(GemService.corruptProblem(new SkillLoadout.Entry("flame_star_shot", 16).withGranted(true)));
    }
}
