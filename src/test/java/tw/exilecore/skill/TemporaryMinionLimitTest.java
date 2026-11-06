package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.passive.PassiveTree;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-06 照站長：消耗軍團、眾所周知的「臨時召喚物技能 +2 召喚物數量上限」只給有持續時間的召喚技能；
 * 這次之前沒有標籤，永久的灰狼隨、獵狼、野獸馴化也多 2 隻。
 */
class TemporaryMinionLimitTest {

    @Test
    void onlyTimedSummonsGetTheTag() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        for (final String id : new String[]{"corpse_summon", "unearth_construct", "insect_devour_horde", "summon_burning_souls"}) {
            assertTrue(SkillProfiles.build(config, config.skill(id), 1, Set.of()).tags().contains(Tags.TEMPORARY_MINION), id);
        }
        for (final String id : new String[]{"grey_wolf_escort", "proc_predator_wolf", "wild_beast_taming", "summon_skeletons"}) {
            assertFalse(SkillProfiles.build(config, config.skill(id), 1, Set.of()).tags().contains(Tags.TEMPORARY_MINION), id);
        }
    }

    @Test
    void treeLimitBonusOnlyForTemporaryMinionSkills() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final PassiveTree tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        final StatSheet sheet = new StatSheet();
        for (final String node : new String[]{"p47420", "p57079"}) { // 消耗軍團、眾所周知
            for (final Modifier modifier : tree.node(node).modifiers()) {
                sheet.add(modifier);
            }
        }
        final Set<String> corpse = SkillProfiles.build(config, config.skill("corpse_summon"), 1, Set.of()).tags();
        final Set<String> wolves = SkillProfiles.build(config, config.skill("grey_wolf_escort"), 1, Set.of()).tags();
        assertEquals(4, sheet.flat(StatKeys.SKILL_MINIONS, corpse), 1e-9, "喚屍吃兩個天賦");
        assertEquals(0, sheet.flat(StatKeys.SKILL_MINIONS, wolves), 1e-9, "灰狼隨是永久的，不吃");
    }
}
