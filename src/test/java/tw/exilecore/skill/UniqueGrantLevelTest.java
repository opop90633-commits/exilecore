package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 2026-11-05 照站長「物品等級 84 的傳奇，技能等級要是 20」「一般（非傳奇）的武器也要 84 就 20 級」。 */
class UniqueGrantLevelTest {

    @Test
    void uniquesAt84GrantLevel20() throws Exception {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        assertEquals(20, config.grantedLevelFor(84, true), "傳奇 84 → 20 級");
        assertEquals(20, config.grantedLevelFor(86, true));
        assertEquals(20, config.grantedLevelFor(84, false), "2026-11-05 照站長：一般的武器也是 84 就 20 級");
        assertEquals(config.maxLevelFor(83), config.grantedLevelFor(83, false), "83 以下照表");
        assertEquals(config.maxLevelFor(83), config.grantedLevelFor(83, true), "83 以下照表");
        assertEquals(20, config.grantedLevelFor(90, false));
    }
}
