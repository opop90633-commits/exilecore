package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-31 照站長：寶石上的標籤（tags_add）也吃技能等級：三元詛咒吃火焰、冰冷、閃電法術技能等級。 */
class GemTagLevelTest {

    @Test
    void elementalWeaknessTakesEachElementLevel() throws Exception {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final SkillDefinition curse = config.skill("triple_element_curse");
        final Set<String> tags = SkillBuild.levelTags(curse);
        assertTrue(tags.containsAll(Set.of("fire", "cold", "lightning", "spell")), tags.toString());
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(StatKeys.SKILL_LEVEL, 2, "火").withTags(Set.of("spell", "fire")));
        sheet.add(Modifier.flat(StatKeys.SKILL_LEVEL, 1, "冰").withTags(Set.of("spell", "cold")));
        sheet.add(Modifier.flat(StatKeys.SKILL_LEVEL, 3, "雷").withTags(Set.of("lightning")));
        assertEquals(6, Math.round(sheet.flat(StatKeys.SKILL_LEVEL, tags)));
    }
}
