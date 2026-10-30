package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 melee_conditional_step_distance：近戰條件位移（旋矛擊往前踏 2.6 格…）。 */
class MeleeStepTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static YamlNode yaml(final String text) {
        return YamlNode.load("測試", new StringReader(text));
    }

    @Test
    void noSkillStepsBeforeAttacking() {
        // 2026-10-29 照站長：出手前往前踏一步先全部拿掉（2026-10-28 曾照 PoE2 melee_conditional_step_distance 加在 24 個近戰技能上），
        //   MeleeStep 的程式留著，skills.yml 寫回 step 就會再生效
        for (final String id : List.of("whirling_slash", "boneshatter", "armour_breaker", "earthquake", "falling_thunder",
                "fangs_of_frost", "ice_strike", "lunar_assault", "primal_strikes", "rapid_thrust", "rolling_slam", "sunder",
                "tempest_bell", "volcanic_fissure", "cull_the_weak", "siphon_strike", "reeling_palm", "frost_palm",
                "wolf_shred", "wyvern_rend", "bear_maul", "default_melee", "default_quarterstaff", "default_spear",
                "leap_slam", "rake", "killing_palm", "rift_slash", "blood_harvest", "mountain_fury", "fireball")) {
            assertNull(config.step(id), id);
        }
        assertTrue(config.skill("whirling_slash").description().startsWith("揮矛轉一圈"));
    }

    @Test
    void conditionalStepStopsInReachAndCapsAtDistance() {
        final MeleeStep.Spec spec = new MeleeStep.Spec(2.6, true);
        assertEquals(0, MeleeStep.towardTarget(spec, 1.5, 1.8), 1e-9, "已經打得到：不動");
        assertEquals(1.2, MeleeStep.towardTarget(spec, 3.0, 1.8), 1e-9, "走到剛好打得到就停");
        assertEquals(2.6, MeleeStep.towardTarget(spec, 10, 1.8), 1e-9, "最多 2.6");
        assertEquals(0, MeleeStep.towardTarget(spec, 1.9, 1.8), 1e-9, "只差一點點：不抖");
        assertEquals(2.6, MeleeStep.towardGround(spec, Double.POSITIVE_INFINITY), 1e-9, "準心指著遠方：踏滿");
        assertEquals(1.0, MeleeStep.towardGround(spec, 1.0), 1e-9, "準心指著前面 1 格的地面：踏到那裡");
        assertEquals(0, MeleeStep.towardGround(spec, 0.1), 1e-9, "指著腳下：不動");
        assertEquals(0, MeleeStep.towardTarget(spec, Double.NaN, 1.8), 1e-9);
        final MeleeStep.Spec always = new MeleeStep.Spec(1.5, false);
        assertEquals(1.5, MeleeStep.towardTarget(always, 0.5, 3), 1e-9, "不是有條件的：照樣踏滿");
        assertEquals(1.5, MeleeStep.towardGround(always, 0.1), 1e-9);
    }

    @Test
    void parsesShortAndLongForms() {
        assertNull(MeleeStep.parse(yaml("a: 1")));
        final MeleeStep.Spec number = MeleeStep.parse(yaml("step: 1.7"));
        assertEquals(tw.exilecore.core.Units.metres(1.7), number.distance(), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        assertTrue(number.conditional());
        final MeleeStep.Spec map = MeleeStep.parse(yaml("step: { distance: 2.6, conditional: false }"));
        assertEquals(tw.exilecore.core.Units.metres(2.6), map.distance(), 1e-9);
        assertTrue(!map.conditional());
        assertThrows(IllegalArgumentException.class, () -> MeleeStep.parse(yaml("step: { conditional: true }")));
        assertThrows(IllegalArgumentException.class, () -> MeleeStep.parse(yaml("step: 20")));
    }

    @Test
    void stepPathLandsExactlyAndStopsAtWalls() {
        final DashPath.Blocks open = flat(1000);
        final double[] end = DashPath.destination(0.5, 64, 0.5, 1, 0, 1.7, open, DashPath.evenStep(1.7));
        assertNotNull(end);
        assertEquals(2.2, end[0], 1e-9, "1.7 格剛好走 1.7（以前一步 0.5 只會走到 1.5）");
        final double[] half = DashPath.destination(0.5, 64, 0.5, 1, 0, 0.5, open, DashPath.evenStep(0.5));
        assertNotNull(half, "重錘 0.5 格也走得動");
        assertEquals(1.0, half[0], 1e-9);
        final double[] wall = DashPath.destination(0.5, 64, 0.5, 1, 0, 2.6, flat(2), DashPath.evenStep(2.6));
        assertNotNull(wall);
        assertTrue(wall[0] + DashPath.HALF_WIDTH <= 2.0 + 1e-9, "不穿牆、身體不卡進牆：" + wall[0]);
        assertNull(DashPath.destination(0.5, 64, 0.5, 1, 0, 2.6, flat(1), DashPath.evenStep(2.6)), "貼著牆：不動");
        assertEquals(0.5, DashPath.evenStep(2.0), 1e-9);
        assertEquals(2.6 / 6, DashPath.evenStep(2.6), 1e-9);
        // 原本的位移不受影響（一步 0.5）
        assertEquals(10.5, DashPath.destination(0.5, 64, 0.5, 1, 0, 10, open)[0], 1e-9);
    }

    @Test
    void tooltipLine() {
        assertNull(SkillService.stepLine(null));
        final String line = SkillService.stepLine(new MeleeStep.Spec(tw.exilecore.core.Units.metres(2.6), true));
        assertTrue(line.contains("最多 <white>2.6</white> 格") && line.contains("打得到就不動"), line);
    }

    /** 平地：地板在 y = 63，x ≥ wallAtX 是牆。 */
    private static DashPath.Blocks flat(final int wallAtX) {
        return new DashPath.Blocks() {
            @Override
            public boolean passable(final int x, final int y, final int z) {
                return x < wallAtX && y >= 64;
            }

            @Override
            public boolean solid(final int x, final int y, final int z) {
                return y <= 63;
            }
        };
    }
}
