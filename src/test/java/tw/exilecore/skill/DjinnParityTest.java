package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatKeys;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2（poe2db）：瓦拉什塔門徒（沙靈使）的納維拉、魯兹安與指令。 */
class DjinnParityTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static double level(final String skill, final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(skill)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        return 0;
    }

    @Test
    void naviraCommandsScaleWithLevel() {
        // 2026-11-01 照 PoE2 遊戲檔 Navira's Oasis：30→80%、20→50% 照娜瑟菈等級 1→100 線性（娜瑟菈等級 = 技能等級 × 2）
        assertEquals(30 + 50 * 39 / 99.0, level("mirage_spirit_haven", "asc.oasis_life_recoup", 20), 0.01);
        assertEquals(30 + 50 * 79 / 99.0, level("mirage_spirit_haven", "asc.oasis_life_recoup", 40), 0.01);
        assertEquals(20 + 30 * 39 / 99.0, level("mirage_spirit_haven", "asc.oasis_mana_recoup", 20), 0.01);
        // poe2db 的 68 級怪物頁面：63.84%、40.3%（娜瑟菈等級 68 = 技能 34 級）
        assertEquals(63.84, level("mirage_spirit_haven", "asc.oasis_life_recoup", 34), 0.01);
        // Navira's Calming：1 級 10%、20 級 67%
        assertEquals(10, level("final_mirage_spirit", "asc.calming_regen", 1), 1e-9);
        assertEquals(67, level("final_mirage_spirit", "asc.calming_regen", 20), 1e-9);
        assertNotNull(config.skill("mirage_spirit_spring"));
    }

    @Test
    void ruzhansTrapThrowsAProjectileAndTornadoesComeFromRunes() {
        final SkillDefinition trap = config.skill("blade_spirit_device");
        assertEquals(Mechanic.PROJECTILE, trap.mechanic(), "原作：擲出的巨劍是投射物，落地才撒符文");
        final SkillDefinition tornado = config.skill("asc_ruzhan_tornado");
        assertNotNull(tornado);
        assertTrue(tornado.hidden());
        assertEquals(Mechanic.ZONE, tornado.mechanic());
        assertTrue(tornado.modifiers().stream().anyMatch(m -> m.stat().equals(StatKeys.IGNITE_CHANCE)), "易燃幅度更多 400%");
        assertEquals(Mechanic.CONE, config.skill("asc_ruzhan_wave").mechanic(), "熔岩浪是往前的一道浪");
    }
}
