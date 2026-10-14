package tw.exilecore.gui;

import org.junit.jupiter.api.Test;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillDefinition;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10：圖鑑搜尋技能也找效果的文字（光環、戰吼、印記給的）。 */
class CodexSearchTest {

    @Test
    void auraEffectsAreSearchable() throws IOException {
        final SkillConfig skills = new SkillConfig();
        skills.load(Path.of("src/main/resources/data"));
        int withEffects = 0;
        for (final SkillDefinition definition : skills.all()) {
            final String text = String.join(" ", CodexMenu.skillSearchText(definition)); // 每個技能都寫得出來，不會丟例外
            if (definition.aura() != null && !definition.aura().effects().isEmpty()) {
                assertTrue(CodexMenu.skillEffectTexts(definition).size() >= definition.aura().effects().size(), definition.id());
                withEffects++;
            }
            if (definition.id().equals("battle_rage")) {
                assertTrue(text.contains("怒氣"), "狂戰之怒的效果寫的是怒氣：" + text);
            }
        }
        assertTrue(withEffects > 5);
    }
}
