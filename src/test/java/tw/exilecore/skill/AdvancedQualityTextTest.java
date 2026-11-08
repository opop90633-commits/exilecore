package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.GemQuality;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-08 照站長「子母榴彈、天罰巨鎚的品質沒有顯示進階奇術的」。 */
class AdvancedQualityTextTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws Exception {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        GemQuality.load(Path.of("src/main/resources/data"));
    }

    @Test
    void newAdvancedQualityStatsHaveText() {
        assertEquals("50% 機率不消耗榮耀", SkillService.advancedQualityText(config.skill("heaven_punish_maul"), 20));
        assertEquals("引爆時間減少 40%", SkillService.advancedQualityText(config.skill("mother_child_grenade"), 20));
    }
}
