package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatSheet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「不用保留的技能，掛晨曦祝福要加 20 精魂保留」：技能本身不保留，輔助加的那一份照樣保留。 */
class GrantedAuraSupportSpiritTest {

    @Test
    void supportSpiritStaysOnReservationFreeSkills() throws Exception {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final SupportDefinition daybreak = config.support("daybreak_boon");
        final SkillDefinition aura = config.visible().stream()
                .filter(skill -> skill.aura() != null && skill.aura().reserve() > 0 && daybreak.socketRefusal(skill, List.of()) == null)
                .findFirst().orElseThrow();
        final SkillBuild plain = SkillBuild.of(config, aura, 20, 100, false, List.of(), Set.of(), new StatSheet());
        final SkillBuild boosted = SkillBuild.of(config, aura, 20, 100, false, List.of(daybreak), Set.of(), new StatSheet());
        assertEquals(0, plain.withoutReservation().reserve(), 1e-9, "沒輔助：不用保留");
        assertTrue(boosted.reserve() > plain.reserve(), aura.id());
        assertEquals(20, boosted.withoutReservation().reserve(), 0.5, aura.id() + "：晨曦祝福的 +20 照樣保留");
    }
}
