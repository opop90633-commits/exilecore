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
    void eachThrowDetonatesThePreviousLance() {
        // 每秒 3 支：每支插 6.7 tick 就被下一次丟引爆，來不及放電；每次引爆 16 道 × 2.27 倍 × 0.8（霆落矛自己引爆）
        assertEquals(3 * 100 * 16 * 2.27 * 0.8, SkillService.lanceExtraDps(100, 3, 2.27, 0.38), 1e-6);
    }

    @Test
    void slowThrowsZapThenDetonate() {
        // 每 2 秒 1 支（40 tick）：16、32 放兩次電，再被下一支引爆
        assertEquals(0.5 * 100 * (2 * 0.5 + 16 * 2 * 0.8), SkillService.lanceExtraDps(100, 0.5, 2, 0.5), 1e-6);
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
