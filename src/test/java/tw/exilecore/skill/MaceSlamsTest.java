package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatSheet;

/** 2026-11-01 照 PoE2 0.5：錘／熊的猛擊（MaceSlams、MoltenFissures）的純邏輯與資料。 */
class MaceSlamsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void leapSlamAddsUpToHalfASecondByDistance() {
        assertEquals(0, MaceSlams.leapAddedSeconds(2.0, 9), 1e-9, "3 公尺內不加");
        assertEquals(0, MaceSlams.leapAddedSeconds(3.0, 9), 1e-9);
        assertEquals(0.25, MaceSlams.leapAddedSeconds(6.0, 9), 1e-9, "一半的距離加一半");
        assertEquals(0.5, MaceSlams.leapAddedSeconds(9.0, 9), 1e-9, "最遠 +0.5 秒");
        assertEquals(0.5, MaceSlams.leapAddedSeconds(12.0, 9), 1e-9, "不超過 0.5 秒");
    }

    @Test
    void furiousSlamSecondShockwaveIsTangentWithinTwentyDegrees() {
        assertEquals(4.0, MaceSlams.furiousOffset(2.0), 1e-9);
        assertEquals(-Math.toRadians(20), MaceSlams.furiousTurn(0), 1e-9);
        assertEquals(0, MaceSlams.furiousTurn(0.5), 1e-9);
        assertEquals(Math.toRadians(20), MaceSlams.furiousTurn(1), 1e-9);
    }

    @Test
    void boneshatterWaveFromUniqueDealsFiftyPercentMore() {
        assertEquals(1.0, MaceSlams.boneshatterWaveFactor(false, 0), 1e-9);
        assertEquals(1.5, MaceSlams.boneshatterWaveFactor(true, 0), 1e-9);
        assertEquals(1.05, MaceSlams.boneshatterWaveFactor(true, -30), 1e-9, "傳奇 ×1.5、雙持 ×0.7");
    }

    @Test
    void fissureGeometry() {
        final double[] straight = MoltenFissures.arcDirection(0, 1, 120, 0.5);
        assertEquals(0, straight[0], 1e-9);
        assertEquals(1, straight[1], 1e-9);
        final double[] edge = MoltenFissures.arcDirection(0, 1, 120, 1.0);
        assertEquals(Math.cos(Math.toRadians(60)), edge[1], 1e-9, "最邊邊偏 60 度");
        assertEquals(1, Math.hypot(edge[0], edge[1]), 1e-9);
        assertEquals(15, MoltenFissures.pointCount(7), "7 格每 0.5 格一點");
        assertEquals(2, MoltenFissures.pointCount(0));
        assertTrue(MoltenFissures.branchAngle(0) > 0 && MoltenFissures.branchAngle(1) < 0, "左右交替");
        assertEquals(0, MoltenFissures.overflow(8, 8));
        assertEquals(1, MoltenFissures.overflow(9, 8));
        assertEquals(0, MoltenFissures.overflow(20, 0), "沒有上限");
    }

    @Test
    void maceSlamsLoseThirtyPercentDamageWhileDualWielding() {
        for (final String id : List.of("soaring_smash", "ground_tremor", "spine_crush", "field_quake_hammers")) {
            final SkillBuild build = SkillBuild.of(config, config.skill(id), 20, 90, false, List.of(), Set.of(), new StatSheet());
            assertEquals(-30, SkillService.dualWieldDamagePercent(build.profile()), 1e-9, id);
            assertSame(build, SkillService.dualWieldDamage(build, false), id + "：沒有雙持不變");
        }
        final SkillBuild fissure = SkillBuild.of(config, config.skill("flame_vein_rift"), 20, 90, false, List.of(), Set.of(), new StatSheet());
        assertEquals(0, SkillService.dualWieldDamagePercent(fissure.profile()), 1e-9, "炎脈裂地是攻速更少，不是傷害");
        assertEquals("雙持時傷害更少 30%", ModifierText.describe(new Modifier(SkillService.DUAL_WIELD_DAMAGE, ModType.FLAT, -30, Set.of(), "")));
        assertEquals("雙持時攻擊速度更少 30%", ModifierText.describe(new Modifier(SkillService.DUAL_WIELD_ATTACK_SPEED, ModType.FLAT, -30, Set.of(), "")));
    }

    @Test
    void fissureDurationsAndLimits() {
        final SkillParams.Zone zone = config.skill("flame_vein_rift").params().zone();
        assertEquals(12, zone.duration(), 1e-9, "Volcanic Fissure 12 秒");
        assertEquals(8, zone.max());
        assertEquals(12, MoltenFissures.MOUNTAIN_LIMIT);
        assertEquals(12, MoltenFissures.MOUNTAIN_SECONDS, 1e-9);
    }
}
