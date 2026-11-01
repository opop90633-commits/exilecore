package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：蓄勢不能掛在本身就會蓄力的技能上；2026-11-01：蓄勢（原作沒有）照站長整個刪掉。 */
class ChannelFocusGuardTest {
    @Test
    void channelFocusIsDeleted() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        assertNull(config.support("build_poise"), "蓄勢刪掉");
        assertTrue(config.deletedSupport("build_poise"), "舊的寶石直接移除，不換成未切割");
    }
}
