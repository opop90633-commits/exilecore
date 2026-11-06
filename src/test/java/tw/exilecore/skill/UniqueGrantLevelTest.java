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
        // 2026-11-07 照站長：82 以上就 20 級；這次之前 84
        assertEquals(20, config.grantedLevelFor(82, true), "傳奇 82 → 20 級");
        assertEquals(20, config.grantedLevelFor(82, false), "一般的 82 → 20 級");
        assertEquals(config.maxLevelFor(81), config.grantedLevelFor(81, false), "81 以下照表");
        assertEquals(20, config.grantedLevelFor(84, true), "傳奇 84 → 20 級");
        assertEquals(20, config.grantedLevelFor(86, true));
        assertEquals(20, config.grantedLevelFor(84, false), "2026-11-05 照站長：一般的武器也是 84 就 20 級");
        assertEquals(20, config.grantedLevelFor(83, false));
        assertEquals(20, config.grantedLevelFor(83, true));
        assertEquals(20, config.grantedLevelFor(90, false));
    }
}
