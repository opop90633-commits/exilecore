package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.SupportRemakeKeys;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 照 PoE2：巨型是開關（生命、傷害各更多 20%），巨魄、巨伴、巨大追隨者一起用也只算一次。 */
class GiganticTest {

    @Test
    void giganticCountsOnce() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final SupportDefinition hulking = config.support("huge_soul");
        for (final Modifier modifier : hulking.modifiers()) {
            assertFalse(modifier.stat().equals(StatKeys.LIFE_MAX) || modifier.stat().equals(StatKeys.DAMAGE), "巨魄本身不再另外寫生命、傷害");
        }
        final StatSheet owner = new StatSheet();
        owner.add(new Modifier(SupportRemakeKeys.MINIONS_GIGANTIC, ModType.FLAT, 1, Set.of(Tags.MINION), "巨大追隨者"));
        assertTrue(MinionEstimates.gigantic(null, owner), "主人點了巨大追隨者");
        assertFalse(MinionEstimates.gigantic(null, new StatSheet()));
        assertEquals(0.20, MinionEstimates.GIGANTIC_MORE, 1e-9);
    }
}
