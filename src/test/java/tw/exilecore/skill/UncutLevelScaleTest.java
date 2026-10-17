package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 19、20 級的未切割技能寶石只留三成三（2026-10，skills.yml 的 uncut_level_scale）；2026-10-18：18 級留三成。 */
class UncutLevelScaleTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void shippedScaleOnlyTouchesTheTopLevels() {
        assertEquals(0.33, config.uncutLevelKeep(20), 1e-9);
        assertEquals(0.33, config.uncutLevelKeep(19), 1e-9);
        assertEquals(0.3, config.uncutLevelKeep(18), 1e-9); // 2026-10-18：18 級也只留三成
        assertEquals(1.0, config.uncutLevelKeep(17), 1e-9);
        assertEquals(1.0, config.uncutLevelKeep(1), 1e-9);
    }

    @Test
    void keptShareMatchesTheScale() {
        final Random random = new Random(7);
        int kept20 = 0;
        int kept18 = 0;
        int kept17 = 0;
        for (int i = 0; i < 20_000; i++) {
            if (config.keepsUncut(20, random)) {
                kept20++;
            }
            if (config.keepsUncut(18, random)) {
                kept18++;
            }
            if (config.keepsUncut(17, random)) {
                kept17++;
            }
        }
        assertTrue(kept20 > 6_200 && kept20 < 7_000, "20 級留下約三成三：" + kept20);
        assertTrue(kept18 > 5_600 && kept18 < 6_400, "18 級留下約三成（2026-10-18）：" + kept18);
        assertEquals(20_000, kept17, "17 級全部留下");
    }

    @Test
    void endgameDropsGetFewerTopGemsButSameLowerOnes() {
        // T16（怪物 84 級）：最高 20 級。以前 20/19/18 = 2/4/4 成，現在 20、19 只留三成三，18 留三成（2026-10-18）
        final Random random = new Random(11);
        final int[] counts = new int[21];
        int none = 0;
        for (int i = 0; i < 30_000; i++) {
            final int level = config.rollUncutLevel(84, random);
            if (config.keepsUncut(level, random)) {
                counts[level]++;
            } else {
                none++;
            }
        }
        assertTrue(counts[20] > 1_800 && counts[20] < 2_200, "20 級約 0.2 × 0.33 = 6.6%：" + counts[20]);
        assertTrue(counts[19] > 3_700 && counts[19] < 4_300, "19 級約 0.4 × 0.33 = 13.2%：" + counts[19]);
        assertTrue(counts[18] > 3_300 && counts[18] < 3_900, "18 級約 0.4 × 0.3 = 12%：" + counts[18]);
        assertTrue(none > 19_900 && none < 21_000, "沒掉的約 68%：" + none);
    }
}
