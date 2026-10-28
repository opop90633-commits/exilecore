package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 0.5 Lightning Warp（雷遁）：瞄準滾雷珠、目標優先順序、滾雷珠不留感電地面。 */
class LightningWarpTest {

    @Test
    void cullableEnemyBeatsMerelyAimedEnemy() {
        // 前面擋著一隻還不能撲殺的，後面那隻可以撲殺：選可以撲殺的（0.5.0 修正）
        assertEquals(LightningWarp.Pick.CULL, LightningWarp.choose(12.0, null, true));
    }

    @Test
    void ballBeatsMerelyAimedEnemy() {
        assertEquals(LightningWarp.Pick.BALL, LightningWarp.choose(null, 15.0, true));
        assertEquals(LightningWarp.Pick.BALL, LightningWarp.choose(null, 3.0, false));
    }

    @Test
    void nearerOfCullableAndBallWinsTieGoesToEnemy() {
        assertEquals(LightningWarp.Pick.BALL, LightningWarp.choose(10.0, 4.0, true));
        assertEquals(LightningWarp.Pick.CULL, LightningWarp.choose(4.0, 10.0, true));
        assertEquals(LightningWarp.Pick.CULL, LightningWarp.choose(6.0, 6.0, true));
    }

    @Test
    void nonCullableEnemyAloneIsMarked() {
        assertEquals(LightningWarp.Pick.MARK, LightningWarp.choose(null, null, true));
        assertEquals(LightningWarp.Pick.NONE, LightningWarp.choose(null, null, false));
    }

    @Test
    void ballLeavesNoShockedGround() {
        assertFalse(LightningWarp.shockedGround(LightningWarp.Pick.BALL), "0.2.0：瞄準滾雷珠不留感電地面");
        assertTrue(LightningWarp.shockedGround(LightningWarp.Pick.CULL));
        assertTrue(LightningWarp.shockedGround(LightningWarp.Pick.MARK));
        assertFalse(LightningWarp.shockedGround(LightningWarp.Pick.NONE));
    }

    @Test
    void ballSkillIdExistsAndDescriptionMentionsIt() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        assertNotNull(config.skill(LightningWarp.BALL), "滾雷珠的技能代號");
        assertEquals("projectile", config.skill(LightningWarp.BALL).mechanic().name().toLowerCase());
        assertTrue(config.skill("storm_blink").description().contains("滾雷珠"));
    }
}
