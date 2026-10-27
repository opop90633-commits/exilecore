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
        final SupportDefinition focus = config.support("channel_focus");
        assertNotNull(focus.refusal(config.skill("inferno_blast")), "炎核爆");
        assertNotNull(focus.refusal(config.skill("bone_storm")), "碎骨飛矢");
        assertNull(focus.refusal(config.skill("fireball")), "一般法術可以掛");
    }
}
