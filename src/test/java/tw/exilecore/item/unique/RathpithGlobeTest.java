package tw.exilecore.item.unique;

import org.junit.jupiter.api.Test;
import tw.exilecore.skill.SkillConfig;
import tw.exilecore.skill.SkillProfiles;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2 Rathpith Globe（獻祭之心）：非引導法術額外消耗 6% 最大生命；非引導法術的情境標籤。 */
class RathpithGlobeTest {

    @Test
    void extraLifeCostOnlyForNonChannellingSpells() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(UniqueStats.SPELL_EXTRA_LIFE_COST, ModType.FLAT, 6, Set.of(), "獻祭之心"));
        assertEquals(60, UniqueCosts.extraLifeCost(sheet, Set.of(Tags.SPELL, Tags.NON_CHANNELLING), 1000), 1e-9);
        assertEquals(0, UniqueCosts.extraLifeCost(sheet, Set.of(Tags.SPELL, "channelling"), 1000), 1e-9, "引導法術不用");
        assertEquals(0, UniqueCosts.extraLifeCost(sheet, Set.of(Tags.ATTACK), 1000), 1e-9, "攻擊不用");
    }

    @Test
    void nonChannellingTagOnSpellsOnly() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        assertTrue(SkillProfiles.build(config, config.skill("blazing_star_shell"), 1, Set.of()).tags().contains(Tags.NON_CHANNELLING), "熾星彈");
        assertFalse(SkillProfiles.build(config, config.skill("shattered_bone_darts"), 1, Set.of()).tags().contains(Tags.NON_CHANNELLING), "碎骨飛矢是引導");
    }
}
