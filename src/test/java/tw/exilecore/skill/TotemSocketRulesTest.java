package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 2026-10-27 照 PoE2：星咒圖騰、英靈圖騰能插哪些技能（原作的 Totemable、使用條件、引導…），
 * 以及圖騰輪流用孔裡的每個技能（{@link SkillDeployables#rotate}）。
 */
class TotemSocketRulesTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void spellTotemBlocksNonTotemableSpells() {
        final MetaSpec totem = config.meta("star_spell_totem");
        for (final String id : TotemRules.NOT_SPELL_TOTEM) {
            assertNotNull(config.skill(id), id);
            assertNotNull(MetaGems.problem(totem, config.skill(id)), id + "：原作不能由圖騰施放");
        }
        for (final String id : List.of("blazing_star_shell", "chain_thunder", "roaming_thunder", "burning_current", "falling_star", "frost_drill")) {
            assertNull(MetaGems.problem(totem, config.skill(id)), id + " 原作可以");
        }
        // 引星（不是圖騰）機制上照樣能觸發這幾個；2026-10-28 照 PoE2：雷流場是武器賦予的技能（沒有寶石），所以插不進任何主要寶石
        assertNull(MetaGems.castProblem(config.meta("critical_guide_star"), config.skill("thunder_current_zone")));
        assertEquals(MetaGems.GRANTED_ONLY, MetaGems.problem(config.meta("critical_guide_star"), config.skill("thunder_current_zone")));
    }

    @Test
    void ancestralBlocksUsageConditionChannelAndNonTotemable() {
        for (final String id : TotemRules.NOT_ANCESTRAL) {
            assertNotNull(config.skill(id), id);
            assertNotNull(SkillService.linkedProblem(config.skill(id)), id);
        }
        assertNotNull(SkillService.linkedProblem(config.skill("hero_spirit_totem")), "主要寶石不能插");
        assertNotNull(SkillService.linkedProblem(config.skill("thousand_weight_smash")), "千鈞砸：引導");
        assertNotNull(SkillService.linkedProblem(config.skill("charged_blow")), "蓄勁一擊：引導");
        for (final String id : List.of("spine_crush", "ground_tremor", "field_quake_hammers", "sunder", "plate_crush_hammer", "lava_splash")) {
            assertNull(SkillService.linkedProblem(config.skill(id)), id + " 原作可以");
        }
    }

    @Test
    void totemRotatesThroughSocketedSkills() {
        assertEquals("a", SkillDeployables.rotate("a", List.of(), 5), "只有一個就一直用它");
        final List<String> order = new java.util.ArrayList<>();
        for (int turn = 0; turn < 6; turn++) {
            order.add(SkillDeployables.rotate("a", List.of("b", "c"), turn));
        }
        assertEquals(List.of("a", "b", "c", "a", "b", "c"), order, "照孔的順序輪流");
    }
}
