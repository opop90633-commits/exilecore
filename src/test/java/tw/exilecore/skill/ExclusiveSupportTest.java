package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一個技能只能掛一顆專屬掉落的輔助寶石（{@link SupportDefinition#exclusiveConflict}，2026-10）。 */
class ExclusiveSupportTest {

    @Test
    void onlyOneExclusiveSupportPerSkill() throws IOException {
        final SkillConfig config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
        final List<SupportDefinition> exclusive = new ArrayList<>();
        SupportDefinition normal = null;
        for (final SupportDefinition support : config.supports()) {
            if (!support.cuttable() && !support.lineage()) { // 2026-10-27：血脈輔助（切不出來，但不算專屬）另外測（LineageSupportTest）
                exclusive.add(support);
            } else if (normal == null) {
                normal = support;
            }
        }
        assertTrue(exclusive.size() >= 2, "資料檔至少有兩顆專屬掉落的輔助");
        assertNotNull(normal);
        final SupportDefinition first = exclusive.get(0);
        final SupportDefinition second = exclusive.get(1);
        final SkillLoadout.Entry empty = new SkillLoadout.Entry("fireball", 5).withSockets(4);
        assertNull(SupportDefinition.exclusiveConflict(empty, -1, first, config::support), "第一顆專屬的可以");
        final SkillLoadout.Entry one = empty.withSupport(0, first.id());
        final String problem = SupportDefinition.exclusiveConflict(one, -1, second, config::support);
        assertNotNull(problem, "第二顆專屬的不行");
        assertTrue(problem.contains(first.name()), problem);
        assertNull(SupportDefinition.exclusiveConflict(one, 0, second, config::support), "換掉孔裡那顆專屬的可以");
        assertEquals(problem, SupportDefinition.exclusiveConflict(one, 2, second, config::support), "掛在別的孔不行");
        assertNull(SupportDefinition.exclusiveConflict(one, -1, normal, config::support), "一般的輔助照常可以掛");

        // 以前已經掛了兩顆專屬的：只有第一顆生效
        final SkillDefinition fireball = config.skill("fireball");
        final SupportDefinition heart = config.support("permafrost_heart");
        final SupportDefinition fury = config.support("skyburn_fury");
        final tw.exilecore.stats.StatSheet sheet = new tw.exilecore.stats.StatSheet();
        final SkillBuild both = SkillBuild.of(config, fireball, 5, 60, false, List.of(heart, fury), java.util.Set.of(), sheet);
        final SkillBuild onlyHeart = SkillBuild.of(config, fireball, 5, 60, false, List.of(heart), java.util.Set.of(), sheet);
        assertEquals(onlyHeart.supports().size(), both.supports().size(), "第二顆專屬的不算");
        assertTrue(both.supports().contains(heart));
    }
}
