package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-10-27：蓄勢不能掛在本身就會蓄力的技能上（炎核爆這種寫在 charge 的也算）。 */
class ChannelFocusGuardTest {
    @Test
    void channelFocusRefusesChargeSkills() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final SupportDefinition focus = config.support("build_poise");
        assertNotNull(focus.refusal(config.skill("flame_core_detonation")), "炎核爆");
        assertNotNull(focus.refusal(config.skill("shattered_bone_darts")), "碎骨飛矢");
        assertNull(focus.refusal(config.skill("blazing_star_shell")), "一般法術可以掛");
    }
}
