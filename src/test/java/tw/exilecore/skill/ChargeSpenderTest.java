package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-16 照站長「炎爆矛有消耗怒星，恆星卻寫不會生效」：會消耗戰星的技能都要認得。 */
class ChargeSpenderTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void spearsAndStaffConsumeCharges() {
        for (final String id : new String[] {"explosive_spear", Bonestorm.ID, "startle_thunder_chop", "thunderfall_spear"}) {
            assertTrue(config.consumesCharges(config.skill(id)), id);
        }
    }

    @Test
    void plainSpellDoesNot() {
        assertFalse(config.consumesCharges(config.skill("frost_shockwave")));
    }
}
