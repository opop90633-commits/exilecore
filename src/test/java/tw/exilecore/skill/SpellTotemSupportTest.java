package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-10-22：星咒圖騰只收有作用的輔助：要圖騰的、只加施法速度或持續時間的；持久、久地讓圖騰撐更久；灌元、致命這些掛不上去。 */
class SpellTotemSupportTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void onlyUsefulSupportsFitTheSpellTotem() {
        final SkillDefinition totem = config.skill("totem_casting");
        for (final String id : new String[] {"increased_duration", "persistent_ground", "faster_casting", "arcane_surge",
                "urgent_totems", "overabundance", "totem_fury", "sturdy_totem"}) {
            assertNull(config.support(id).refusal(totem), id);
        }
        for (final String id : new String[] {"infusion_release", "critical_strikes", "added_fire", "considered_casting", "excise"}) {
            assertNotNull(config.support(id).refusal(totem), id);
        }
        // 一般法術照舊
        assertNull(config.support("infusion_release").refusal(config.skill("fireball")));
    }
}
