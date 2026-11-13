package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-12 照站長「1～60 等的怪物區那邊經驗 ×2」：character.yml 的 zone_experience。 */
class ZoneExperienceTest {

    @Test
    void campaignZonesDoubleExperience() throws Exception {
        final CharacterConfig config = new CharacterConfig();
        config.load(Path.of("src/main/resources/data"));
        assertEquals(2.0, config.zoneExperienceBonus());
        assertEquals(2.0, config.zoneExperience(true, false, 12));   // 潮濕海岸
        assertEquals(2.0, config.zoneExperience(true, false, 60));   // 最後一區
        assertEquals(1.0, config.zoneExperience(true, false, 61));   // 超過 60 不算
        assertEquals(1.0, config.zoneExperience(true, true, 0));     // 營地
        assertEquals(1.0, config.zoneExperience(false, false, 0));   // 地圖、劫盜（不在區域裡）
    }
}
