package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 輔助觸發的專屬技能的詳細說明（2026-10）。 */
class ProcTextTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void everyProcSkillHasDetails() {
        for (final SupportDefinition support : config.supports()) {
            for (final var modifier : support.modifiers()) {
                if (ProcText.isProc(modifier)) {
                    final String id = modifier.tags().iterator().next();
                    assertFalse(ProcText.details(id).isEmpty(), support.id() + " 觸發的 " + id + " 要有說明");
                }
            }
        }
    }

    @Test
    void novaShowsRadiusAndDamage() {
        final List<String> lines = ProcText.details("proc_shock_rift");
        assertTrue(lines.stream().anyMatch(l -> l.contains("半徑")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("閃電傷害") && l.contains("20 級")), lines.toString());
    }

    @Test
    void gemTooltipListsDetailsUnderTheProcLine() {
        final List<String> lines = GemService.effectLines(config.support("magnetic_storm"));
        int proc = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("觸發「")) {
                proc = i;
            }
        }
        assertTrue(proc >= 0 && lines.get(proc + 1).contains("半徑"), lines.toString());
        assertTrue(lines.getLast().contains(ProcText.RULES), "最後一行是觸發的規則");
    }
}
