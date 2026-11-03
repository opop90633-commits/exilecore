package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-03 照站長（祕法之心插不進霆髓）＋照 PoE2（PoB2：Archmage 有 Lightning 類型，Lightning Mastery 只要 Lightning）：
 * 只加技能等級的輔助照「吃技能等級的標籤」驗，跟裝備的 +閃電技能等級一致。
 */
class LevelSupportTagsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void lightningMasteryGoesOnArchmage() {
        final SkillDefinition archmage = config.skill("arcane_heart");
        assertTrue(SkillBuild.levelTags(archmage).contains("lightning"), "裝備的 +閃電技能等級吃得到");
        assertNull(config.support("thunder_marrow").socketRefusal(archmage, List.of()), "霆髓插得進去");
        assertNotNull(config.support("frost_marrow").socketRefusal(archmage, List.of()), "霜髓還是不行（沒有冰冷標籤）");
    }
}
