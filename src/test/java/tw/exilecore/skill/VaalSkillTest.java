package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.item.unique.VaalKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：+x 全部已腐化（法術）技能寶石等級。 */
class VaalSkillTest {
    @Test
    void corruptedGemLevelsOnlyForCorruptedKeepTags() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(VaalKeys.GEM_CORRUPTED_LEVEL, 1, "華儀"));
        sheet.add(new Modifier(VaalKeys.GEM_CORRUPTED_LEVEL, tw.exilecore.stats.ModType.FLAT, 4, Set.of(Tags.SPELL), "御令"));
        assertTrue(SkillService.corruptedLevels(false, sheet).isEmpty(), "沒腐化的不吃");
        final List<Modifier> levels = SkillService.corruptedLevels(true, sheet);
        final StatSheet gem = sheet.overlay(levels);
        assertEquals(5, gem.flat(StatKeys.SKILL_LEVEL, Set.of(Tags.SPELL)), 1e-9);
        assertEquals(1, gem.flat(StatKeys.SKILL_LEVEL, Set.of(Tags.ATTACK)), 1e-9, "[spell] 只算法術");
    }
}
