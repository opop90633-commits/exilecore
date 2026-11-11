package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-11：熔魂嘯的先祖加成（打擊與猛擊）、火山步／火山噴發能吃的輔助掛得上。 */
class AncestralCryTest {
    private final SkillConfig config = load();

    private static SkillConfig load() {
        final SkillConfig config = new SkillConfig();
        try {
            config.load(Path.of("src/main/resources/data"));
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return config;
    }

    @Test
    void boostsStrikesAndSlams() {
        final WarcrySpec.Empower empower = config.skill("molten_soul_shriek").warcry().empower();
        assertTrue(empower.ancestral());
        assertTrue(empower.whileActive());
        assertEquals(2, empower.extraTargets());
        boolean slam = false;
        boolean strike = false;
        for (final SkillDefinition skill : config.all()) {
            if (skill.hidden() || !skill.isAttack()) {
                continue;
            }
            if (skill.tags().contains(tw.exilecore.stats.Tags.SLAM)) {
                assertTrue(empower.accepts(skill), skill.id());
                slam = true;
            }
            if (skill.tags().contains(tw.exilecore.stats.Tags.STRIKE)) {
                assertTrue(empower.accepts(skill), skill.id());
                strike = true;
            }
        }
        assertTrue(slam && strike);
    }

    @Test
    void slamAndMeleeSupportsFitThroughTriggeredSkills() {
        final SkillDefinition cry = config.skill("molten_soul_shriek");
        for (final String id : List.of("shattered_earth", "hug_range", "long_stay")) {
            final SupportDefinition support = config.support(id);
            assertTrue(support.supports(cry, List.of()), id + "：" + support.refusal(cry, List.of()));
        }
        // 別的戰吼沒有觸發技能：照舊掛不上
        final SkillDefinition other = config.skill("armor_rend_bear_roar");
        assertFalse(config.support("shattered_earth").supports(other, List.of()));
    }
}
