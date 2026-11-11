package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 2026-11-10 照站長：霆落矛估算、擲雷槍品質照原作。2026-11-11：一般的矛被擠掉只放一次電（不再 16 道）。 */
class LanceEstimateTest {

    @Test
    void fastThrowsPushOutWithOneZap() {
        // 每秒 3 支：同時 3 支，每支插 20 tick 就被擠掉：26 tick 才第一次放電 → 0 次；擠掉時放 1 次
        assertEquals(3 * 100 * 1 * 0.38, SkillService.lanceExtraDps(100, 3, 2.27, 0.38), 1e-6);
    }

    @Test
    void slowThrowsZapUntilExpiry() {
        // 每 2 秒 1 支：3 支要 120 tick 才擠（= 6 秒，時間到先消失，不算擠掉）：26、42、58、74、90、106 → 6 次
        assertEquals(0.5 * 100 * 6 * 0.5, SkillService.lanceExtraDps(100, 0.5, 2, 0.5), 1e-6);
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
