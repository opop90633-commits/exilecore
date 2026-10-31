package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29 照站長：權杖自帶的光環（律盾、焰守…）上掛的輔助不用精魂。 */
class SceptreAuraSpiritTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SkillConfig config;
    static ItemRepository repository;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(DATA);
        repository = new ItemRepository();
        repository.load(DATA);
    }

    private static SkillBuild build(final String skill, final String... supports) {
        return SkillBuild.of(config, config.skill(skill), 20, 90, false,
                java.util.Arrays.stream(supports).map(config::support).toList(), Set.of(), new StatSheet());
    }

    @Test
    void sceptreAurasAreRecognised() {
        for (final String id : List.of("law_shield", "evil_thought", "flame_guard", "frost_guard", "thunder_guard", "corrode_guard",
                "cold_heart", "thunder_resonance")) {
            assertTrue(SkillService.sceptreGrants(repository, id), id);
        }
        assertTrue(SkillService.sceptreGrants(repository, "summon_skeletons"), "權杖也給骸骨兵，但它不是光環（SkillService.sceptreAura 另外擋）");
        assertFalse(SkillService.sceptreGrants(repository, "erosion_star_shot"), "法杖給的不算");
    }

    @Test
    void supportSpiritIsDropped() {
        final SkillBuild withVitality = build("law_shield", "life_spring");
        assertTrue(withVitality.reserve() > 0, "一般情況：泉命要精魂");
        assertEquals(0, withVitality.withoutSupportSpirit().reserve(), 1e-9, "權杖的律盾：輔助免精魂");
        final SkillBuild plain = build("law_shield");
        assertEquals(plain.reserve(), plain.withoutSupportSpirit().reserve(), 1e-9);
    }
}
