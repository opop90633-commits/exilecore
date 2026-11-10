package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 2026-11-10 照站長：霆落矛估算補引爆、擲雷槍品質照原作。 */
class LanceEstimateTest {

    @Test
    void fastThrowsDetonateEveryLanceWithoutZapping() {
        // 每秒 3 支：每支插 6.7 tick 就被下一支引爆，來不及放電；每支引爆 16 道 × 2.27 倍
        assertEquals(3 * 100 * 16 * 2.27, SkillService.lanceExtraDps(100, 3, 2.27, 0.38), 1e-6);
    }

    @Test
    void slowThrowsAlsoZap() {
        // 每 2 秒 1 支（40 tick）：26 tick 第一次放電，之後每 16 tick（42 > 40）→ 1 次
        assertEquals(0.5 * 100 * (16 * 2 + 1 * 0.5), SkillService.lanceExtraDps(100, 0.5, 2, 0.5), 1e-6);
    }

    @Test
    void lightningSpearQualityAddsBoltsNotSpears() throws Exception {
        final String yml = Files.readString(Path.of("src/main/resources/data/gem_quality.yml"));
        final int at = yml.indexOf("thunder_lance_throw:");
        final String block = yml.substring(at, yml.indexOf("\n", yml.indexOf("- {", at)));
        assertTrue(block.contains("splash.targets_extra"), block);
        assertFalse(block.contains("skill.projectiles"), "主矛永遠 1 支");
    }
}
