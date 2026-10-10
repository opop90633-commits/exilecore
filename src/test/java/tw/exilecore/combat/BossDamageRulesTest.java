package tw.exilecore.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boss 只吃玩家（與召喚物）的傷害（2026-10）：原版的環境傷害沒經過換算（原版 1 點血 = Boss 幾百點生命，
 * 怪物又沒有受傷後的無敵時間），所以 Boss 不吃；掉出世界、/kill、世界邊界、其他插件直接打的照吃。
 */
class BossDamageRulesTest {

    @Test
    void bossesIgnoreVanillaEnvironmentDamage() {
        for (final String cause : new String[] {"SUFFOCATION", "FALL", "FIRE", "FIRE_TICK", "LAVA", "DROWNING", "BLOCK_EXPLOSION",
                "ENTITY_EXPLOSION", "CONTACT", "CRAMMING", "FREEZE", "HOT_FLOOR", "LIGHTNING", "MAGIC", "POISON", "WITHER", "FALLING_BLOCK"}) {
            assertFalse(BossDamageRules.takesEnvironment(cause), cause);
        }
        for (final String cause : new String[] {"VOID", "KILL", "WORLD_BORDER", "CUSTOM", "SUICIDE"}) {
            assertTrue(BossDamageRules.takesEnvironment(cause), cause);
        }
    }
}
