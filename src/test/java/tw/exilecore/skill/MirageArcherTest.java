package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;
import tw.exilecore.stats.StatSheet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「幻弓影照 0.5 原作重做」（PoE2 Mirage Archer）。 */
class MirageArcherTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws Exception {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data"));
    }

    @Test
    void mirageArcherIsAMetaGemForRangedAttacks() {
        final SkillDefinition archer = config.skill(DeadeyeMetas.ARCHER);
        assertTrue(DeadeyeMetas.hosts(archer));
        assertTrue(archer.weapons().containsAll(Set.of("bow", "crossbow", "spear")), "弓、弩、矛");
        assertNull(DeadeyeMetas.problem(archer, config.skill("successive_shots")), "弩的攻擊放得進去");
        assertNull(DeadeyeMetas.problem(archer, config.skill("rime_arrow")), "弓的攻擊放得進去");
        assertNotNull(DeadeyeMetas.problem(archer, config.skill("morning_light_column")), "法術不行");
        assertEquals(5.05, config.levelModifiers(DeadeyeMetas.ARCHER).stream().filter(m -> m.stat().equals("asc.mirage_seconds"))
                .findFirst().orElseThrow().value().at(1), 1e-9);
    }

    @Test
    void mirageDealsThirtyPercentLess() {
        final SkillBuild shot = SkillBuild.of(config, config.skill("successive_shots"), 20, 100, false, List.of(), Set.of(), new StatSheet());
        assertTrue(DeadeyeMetas.mirageBuild(shot, DeadeyeMetas.ARCHER).profile().local().stream()
                .anyMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.DAMAGE) && Math.abs(m.value() + 0.30) < 1e-9), "幻弓影更少 30%");
        assertTrue(DeadeyeMetas.mirageBuild(shot, DeadeyeMetas.MIRAGE).profile().local().stream()
                .anyMatch(m -> m.stat().equals(tw.exilecore.stats.StatKeys.DAMAGE) && Math.abs(m.value() + 0.50) < 1e-9), "幻影銳眼更少 50%");
    }

    @Test
    void qualityText() {
        final String advanced = SkillService.advancedQualityText(config.skill(DeadeyeMetas.ARCHER), 20);
        assertTrue(advanced.contains("幻影持續時間 +1 秒"), advanced);
    }
}
