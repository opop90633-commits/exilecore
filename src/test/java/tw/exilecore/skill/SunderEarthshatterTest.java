package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.Units;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-31 照站長：裂地斬 5 段區域、地刺崩裂的地刺照原作。 */
class SunderEarthshatterTest {

    @Test
    void sunderHasFiveGrowingAreas() {
        double total = 0;
        for (int i = 0; i < SkillExecutor.SUNDER_AREAS; i++) {
            total += SkillExecutor.sunderRadius(i); // 2026-11-01 照 PoE2 Sunder（skC1）：0.9 公尺起是每段的半徑；這次之前當直徑（sunderDiameter）
        }
        assertEquals(Units.metres(0.9 + 1.1 + 1.3 + 1.5 + 1.7), total, 1e-9, "0.9 公尺起、每段多 0.2 公尺");
        assertEquals(7, SkillExecutor.SUNDER_SHOCKWAVES);
    }

    @Test
    void earthSpikesFollowTheOriginal() {
        assertEquals(6, EarthSpikes.LIMIT);
        assertEquals(120, EarthSpikes.SPIKE_TICKS);
        assertEquals(0.5, EarthSpikes.WARCRY_MORE, 1e-9);
        assertEquals(Units.metres(2), EarthSpikes.BLAST_RADIUS, 1e-9);
    }
}
