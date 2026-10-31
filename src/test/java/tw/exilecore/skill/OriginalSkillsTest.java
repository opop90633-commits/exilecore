package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：照 PoE2 原作機制重做的技能（OriginalSkills）的資料與純計算。 */
class OriginalSkillsTest {

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
        throw new AssertionError(skill + " 沒有 " + stat);
    }

    @Test
    void signaturesAndHiddenSkillsExist() {
        assertEquals("charged_staff", config.signature(OriginalSkills.STAFF));
        assertEquals("mantra", config.signature(OriginalSkills.MANTRA));
        assertEquals("galvanic", config.signature(OriginalSkills.GALVANIC));
        assertEquals("living_bomb", config.signature(OriginalSkills.SEED));
        assertEquals("calamity", config.signature(OriginalSkills.CALAMITY));
        for (final String id : List.of(OriginalSkills.ARCTIC_BURST, OriginalSkills.STAFF_WAVE, OriginalSkills.GALVANIC_BOLT, OriginalSkills.CALTROP,
                OriginalSkills.METEOR, OriginalSkills.WING_WAVE, OriginalSkills.WOLF, OriginalSkills.FRAGMENT_SHRED, OriginalSkills.FRAGMENT_ARROW,
                OriginalSkills.FRAGMENT_ESCAPE)) {
            assertNotNull(config.skill(id), id);
            assertTrue(config.skill(id).hidden(), id);
        }
        for (final String id : OriginalSkills.HEAT_CONSUMERS.keySet()) {
            assertNotNull(config.skill(id), id);
        }
        assertEquals(50, config.resource(OriginalSkills.CALAMITY).glory());
        assertEquals("rage_at_max", config.resource(OriginalSkills.CALAMITY).gloryFrom());
        assertTrue(config.skill(OriginalSkills.CALAMITY).cooldown() <= 0, "照 PoE2：沒有冷卻，要榮耀");
    }

    @Test
    void levelTablesFollowRef() {
        assertEquals(1.25, level(OriginalSkills.ARCTIC, OriginalSkills.ARCTIC_INTERVAL, 1), 1e-9);
        assertEquals(5, level(OriginalSkills.ARCTIC, OriginalSkills.ARCTIC_STAGES, 20), 1e-9);
        assertEquals(22, level(OriginalSkills.STAFF, OriginalSkills.STAFF_MAX, 20), 1e-9);
        assertEquals(69, level(OriginalSkills.MANTRA, OriginalSkills.MANTRA_CHAOS, 20), 1e-9);
        assertEquals(59, level(OriginalSkills.ATTRITION, OriginalSkills.ATTRITION_CAP, 20), 1e-9);
        assertEquals(31, level(OriginalSkills.ATTRITION, OriginalSkills.ATTRITION_CULL, 20), 1e-9);
        assertEquals(3.95, level(OriginalSkills.CONVALESCENCE, OriginalSkills.CONVALESCENCE_TIME, 20), 1e-9);
        assertEquals(66124, level(OriginalSkills.GRIM, OriginalSkills.GRIM_CAP, 20), 1e-9);
        assertEquals(-40, level(OriginalSkills.SACRIFICE, OriginalSkills.SACRIFICE_SLOWER, 20), 1e-9);
        assertEquals(6.05, level(OriginalSkills.SHARD, OriginalSkills.SHARD_INTERVAL, 20), 1e-9);
        assertEquals(337, level(OriginalSkills.DEVOUR, OriginalSkills.DEVOUR_REGEN, 20), 1e-9);
        assertEquals(6, level(OriginalSkills.POUNCE, OriginalSkills.WOLF_LIMIT, 20), 1e-9);
        assertEquals(11, level(OriginalSkills.ESCAPE, OriginalSkills.ESCAPE_FRAGMENTS, 20), 1e-9);
    }

    @Test
    void attritionAndCalamityMath() {
        assertEquals(0, OriginalSkills.attritionMore(1.5, 40), 1e-9);
        assertEquals(10, OriginalSkills.attritionMore(21, 40), 1e-9, "每 2 秒更多 1%");
        assertEquals(40, OriginalSkills.attritionMore(500, 40), 1e-9, "上限");
        assertEquals(3, OriginalSkills.calamityInterval(1), 1e-9, "0.15 秒一顆");
        assertEquals(1.5, OriginalSkills.calamityInterval(3), 1e-9, "多兩個敵人：頻率 +100%");
        assertEquals(1, OriginalSkills.calamityInterval(20), 1e-9, "最多 +200%");
    }

    @Test
    void oilValuesComeFromTheSkill() {
        final SkillProfile profile = new SkillProfile("black_oil_rain", Set.of(Tags.ATTACK), 1.0, null, null, java.util.Map.of(),
                List.of(Modifier.flat(OriginalSkills.OIL_SLOW, 20, "黑油雨"), Modifier.flat(OriginalSkills.OIL_EXPOSURE, 20, "黑油雨")));
        assertEquals(20, OriginalSkills.oilValue(profile, OriginalSkills.OIL_SLOW), 1e-9);
        assertEquals(0, OriginalSkills.oilValue(profile, "other"), 1e-9);
        assertNull(config.skill("thornburr_steps").cooldown() > 0 ? "冷卻" : null);
        assertTrue(new StatSheet().allModifiers().isEmpty());
    }
}
