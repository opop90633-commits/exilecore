package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-11-01 照 PoE2（Cursed Ground addSkillTypes Limit）：詛咒掛了咒地才掛得上過剩（+1 上限 → 多一塊詛咒地面）。 */
class CursedGroundLimitTest {
    @Test
    void overabundanceNeedsCursedGround() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final SkillDefinition curse = config.skill("sluggish_curse");
        final SupportDefinition over = config.support("brim_over_ii");
        assertNotNull(over.refusal(curse, List.of()), "單獨的詛咒沒有上限");
        assertNull(over.refusal(curse, List.of("hex_field")), "掛了咒地就有上限");
    }
}
