package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 19、20 級的未切割技能寶石只留三成三（2026-10，skills.yml 的 uncut_level_scale）。 */
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
        assertEquals(1.0, config.uncutLevelKeep(18), 1e-9);
        assertEquals(1.0, config.uncutLevelKeep(1), 1e-9);
    }

    @Test
    void keptShareMatchesTheScale() {
        final Random random = new Random(7);
        int kept20 = 0;
        int kept18 = 0;
        for (int i = 0; i < 20_000; i++) {
            if (config.keepsUncut(20, random)) {
                kept20++;
            }
            if (config.keepsUncut(18, random)) {
                kept18++;
            }
        }
        assertTrue(kept20 > 6_200 && kept20 < 7_000, "20 級留下約三成三：" + kept20);
        assertEquals(20_000, kept18, "18 級全部留下");
    }

    @Test
    void endgameDropsGetFewerTopGemsButSameLowerOnes() {
        // T16（怪物 84 級）：最高 20 級。以前 20/19/18 = 2/4/4 成，現在 20、19 只留三成三，18 不變
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
        assertTrue(counts[18] > 11_400 && counts[18] < 12_600, "18 級照舊 40%：" + counts[18]);
        assertTrue(none > 11_500 && none < 12_700, "沒掉的約 40%：" + none);
    }
}
