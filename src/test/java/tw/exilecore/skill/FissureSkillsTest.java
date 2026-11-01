package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2（PoB2 CreatesFissure）：冰脊裂、熔裂踏、雷浪、回旋鍛錘是裂地技能。 */
class FissureSkillsTest {

    static SkillConfig config;
    static final List<String> IDS = List.of(FissureSkills.GLACIAL, FissureSkills.FURY, FissureSkills.STORM_WAVE, FissureSkills.FORGE);

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void createsFissureSkillsCarryTheTagAndTakeFissureSupports() {
        for (final String id : IDS) {
            final SkillDefinition skill = config.skill(id);
            assertNotNull(skill, id);
            assertTrue(skill.tags().contains(tw.exilecore.stats.KeystoneKeys.FISSURE), id + " 是裂地技能");
            for (final String support : List.of("split_crack_ii", "land_uplift_ii", "frenzied_rift")) {
                assertNotNull(config.support(support), support);
                assertNull(config.support(support).refusal(skill), support + " 掛得上 " + id);
            }
        }
        // 雙震（Tectonic Slams）照原作不能輔助裂地技能：熔裂踏、回旋鍛錘是猛擊，現在擋下來
        for (final String id : List.of(FissureSkills.FURY, FissureSkills.FORGE)) {
            assertNotNull(config.support("twin_quake").refusal(config.skill(id)), "雙震不能掛 " + id);
        }
    }

    @Test
    void fissureHelpers() {
        assertEquals(5, FissureSkills.forgeCount(5, 0, 0, false));
        assertEquals(5 + 2 + 3 + 1, FissureSkills.forgeCount(5, 2, 3, true), "隆起、狂裂、分裂地面都加進螺旋的道數");
        assertEquals(5 + 6, FissureSkills.forgeCount(5, 0, 9, false), "狂裂最多 6 道（同 SkillExecutor）");
        assertEquals(Math.sqrt(0.65), FissureSkills.branchLinear(35), 1e-9);
        final double[] turned = FissureSkills.rotate(1, 0, Math.PI / 2);
        assertEquals(0, turned[0], 1e-9);
        assertEquals(1, turned[1], 1e-9);
        final double[] ray = {1, 0, 0, 1};
        assertArrayEquals(new double[] {1, 0, 1, 0}, FissureSkills.alongSpiral(ray, 4, 0.25), 1e-9);
        assertArrayEquals(new double[] {2, 1, 0, 1}, FissureSkills.alongSpiral(ray, 4, 0.75), 1e-9);
    }

    @Test
    void furyPoundsOpenOneFissureInTheFrontArc() {
        assertEquals(-Math.toRadians(60), FissureSkills.furyTurn(0), 1e-9, "前方 120 度（bear_fury_of_the_mountain_front_facing_cone_angle）");
        assertEquals(Math.toRadians(60), FissureSkills.furyTurn(1), 1e-9);
        assertEquals(0, FissureSkills.furyTurn(0.5), 1e-9);
        final SkillDefinition fury = config.skill(FissureSkills.FURY);
        assertEquals(tw.exilecore.core.Units.metres(1.0), fury.params().radius().at(20), 1e-9, "裂縫寬 1 公尺（active_skill_base_area_of_effect_radius 10）");
        assertNull(fury.params().ground(), "原作沒有燃燒地面");
        assertEquals(12, FissureSkills.FURY_MAX);
        assertEquals(12, FissureSkills.FURY_SECONDS, 1e-9);
        assertEquals(8, SkillStages.get(FissureSkills.FORGE, "fissure").duration(), 1e-9, "鍛錘的裂地留 8 秒（Fissures base_skill_effect_duration 8000）");
        assertEquals(10, FissureSkills.FORGE_MAX);
    }

    @Test
    void moltenFissureLimitAndAftershockRules() {
        // 2026-11-01：熔岩地裂併成一份（MoltenFissures，炎脈裂地、熔裂踏、回旋鍛錘共用）：overflow 收的是「加進去之後」的道數
        assertEquals(0, MoltenFissures.overflow(10, 10));
        assertEquals(1, MoltenFissures.overflow(11, 10), "滿了再加一道：最舊的消失");
        assertEquals(3, MoltenFissures.overflow(13, 10));
        assertTrue(MoltenFissures.shockReady(0, 4), "0.2 秒一次");
        assertFalse(MoltenFissures.shockReady(0, 3));
        assertEquals(5, MoltenFissures.pointCount(2), "2 格長的地裂每 0.5 格一點（含頭尾）");
        assertEquals(tw.exilecore.core.Units.metres(MoltenFissures.MOUNTAIN_LENGTH_METRES), FissureSkills.FURY_LENGTH, 1e-9, "熔裂踏的地裂跟炎脈裂地一樣長");
    }

    @Test
    void onlyStormWaveIsAWaveFissure() {
        assertFalse(FissureSkills.wave(build(FissureSkills.FORGE)), "鍛錘的裂縫是戰吼震碎的熔岩裂地，不是擲出去那一下");
        assertTrue(FissureSkills.wave(build(FissureSkills.STORM_WAVE)));
        assertFalse(FissureSkills.wave(build(FissureSkills.GLACIAL)));
    }

    private static SkillDefinition build(final String id) {
        return config.skill(id);
    }
}
