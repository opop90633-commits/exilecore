package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長「骸令、骸令 II 一起掛只有第一顆生效，面板看不出來」：同系列沒生效的找得出來；三兆不把它算進其他輔助。 */
class SameFamilySupportTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws Exception {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void secondOfTheSameFamilyIsReported() {
        final SkillDefinition trick = config.skill("sand_spirit_trick");
        final SupportDefinition two = config.support("bone_command_ii");
        final SupportDefinition one = config.support("bone_command");
        final SupportDefinition triple = config.support("triple_portent");
        final Map<SupportDefinition, SupportDefinition> ignored = SkillBuild.sameFamilyIgnored(List.of(two, one, triple), trick);
        assertEquals(Map.of(one, two), ignored);
        assertEquals(0, LineageRules.conditionalLevels(SkillBuild.effectiveSupports(List.of(two, one, triple), trick, java.util.Set.of()), trick),
                "同系列只算一顆 → 只有 1 顆其他輔助，三兆不給等級");
        assertTrue(SkillBuild.sameFamilyIgnored(List.of(two, triple), trick).isEmpty());
    }
}
