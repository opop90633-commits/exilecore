package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-11-08 照站長「拾息放不上素痕湧」：會留灌注殘骸的素痕湧掛得上拾息；不留殘骸的照舊不行。 */
class GatherBreathTest {

    @Test
    void siphonElementsTakesGatherBreath() throws Exception {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final SupportDefinition gather = config.support("gather_breath");
        assertNull(gather.socketRefusal(config.skill("element_trace_surge"), List.of()));
        assertNull(gather.socketRefusal(config.skill("magic_spring_well"), List.of()));
        assertNotNull(gather.socketRefusal(config.skill("frost_drill"), List.of()), "霜鑽不留殘骸");
    }
}
