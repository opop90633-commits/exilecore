package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26：照站長刪掉的自創技能（血牙、元素脈衝、匯元衝擊）與自創的元素灌注輔助（焰蘊、霜蘊、雷蘊、灌元），
 * 以及照 PoE2 0.5 補上的德魯伊輔助（催綻、行嵐、掠嵐、蟄甲）。
 */
class DruidSupportsTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    @Test
    void homemadeSkillsAreRetiredAsUncutGems() {
        for (final String[] removed : new String[][] {{"wolf_rend", "血牙"}, {"elemental_pulse", "元素脈衝"}, {"infusion_bolt", "匯元衝擊"}}) {
            assertNull(config.skill(removed[0]), removed[0]);
            assertTrue(config.retiredSkill(removed[0]), removed[0]);
            assertEquals(removed[1], config.retiredSkillName(removed[0]));
            final GemData old = GemData.decode(GemData.skill(new SkillLoadout.Entry(removed[0], 12, 3, List.of("pierce", ""))).encode()).orElseThrow();
            assertEquals(GemData.uncutSkill(12), old.removedSkillReplacement(config), removed[0] + "：換成同等級的未切割技能寶石");
        }
    }

    @Test
    void homemadeInfusionSupportsAreRetiredAsUncutSupports() {
        for (final String id : List.of("ember_infusion", "frost_infusion", "storm_infusion", "infusion_release")) {
            assertNull(config.support(id), id);
            assertEquals(3, config.retiredSupportTier(id), id + "：換成第 3 階的未切割輔助寶石（原本是第 3 階）");
            // 存在物品裡的舊輔助寶石照樣讀得出來
            assertEquals(id, GemData.decode(GemData.support(id).encode()).orElseThrow().id());
        }
    }

    @Test
    void acceleratedGrowthSupportsPlantsThatAreUsed() {
        final SupportDefinition growth = config.support("hasten_bloom");
        assertNotNull(growth);
        assertEquals(3, growth.tier());
        for (final String plant : List.of("lashing_thorns", "root_bind", "miasma_bud_rain", "miasma_zone", "thorn_shaft")) {
            assertTrue(growth.supports(config.skill(plant)), plant);
        }
        assertFalse(growth.supports(config.skill("thorn_body")), "常駐的刺棘身不會放出去");
        assertFalse(growth.supports(config.skill("blazing_star_shell")));
        final SkillDefinition flower = config.skill("proc_accelerated_growth");
        assertNotNull(flower);
        assertEquals(Mechanic.IMPACT, flower.mechanic());
        assertTrue(flower.hidden());
    }

    @Test
    void stormSupportsOnlyFitStorms() {
        for (final String id : List.of("marching_gale", "sweeping_gale")) {
            final SupportDefinition support = config.support(id);
            assertNotNull(support, id);
            for (final String storm : List.of("flowing_flame_canopy", "gathered_thundercloud", "wild_whirlwind")) {
                assertNull(support.refusal(config.skill(storm)), id + " → " + storm);
            }
            assertFalse(support.supports(config.skill("blazing_star_shell")), id);
        }
    }

    @Test
    void stormsTravelAtRefSpeed() {
        final SkillDefinition firestorm = config.skill("flowing_flame_canopy");
        final Set<String> tags = firestorm.tags();
        final StatSheet plain = new StatSheet();
        assertEquals(0, DruidSupports.stormPerTick(firestorm, plain, tags), 1e-9, "沒掛不動");
        final StatSheet advancing = new StatSheet();
        advancing.addAll(config.support("marching_gale").modifiers());
        assertEquals(tw.exilecore.core.Units.metres(2.5) / 20, DruidSupports.stormPerTick(firestorm, advancing, tags), 1e-9, "行嵐：每秒 2.5 公尺"); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        final StatSheet morgana = new StatSheet();
        morgana.addAll(config.support("sweeping_gale").modifiers());
        assertEquals(tw.exilecore.core.Units.metres(2.5) * 1.6 / 20, DruidSupports.stormPerTick(firestorm, morgana, tags), 1e-9, "掠嵐：更多 60%");
        assertEquals(0, DruidSupports.stormPerTick(config.skill("blazing_star_shell"), advancing, config.skill("blazing_star_shell").tags()), 1e-9, "不是風暴不動");
        assertEquals("風暴從你腳下出發，往準心指的地方移動（每秒 2.5 格），到了就停在那裡", // 2026-10-30：1 公尺 = 1 格
                ModifierText.describe(new Modifier(DruidSupports.STORM_ADVANCE, ModType.FLAT, 25, Set.of(), "")));
        assertEquals("60% 更多風暴移動速度", ModifierText.describe(new Modifier(DruidSupports.STORM_TRAVEL_SPEED, ModType.MORE, 0.6, Set.of(), "")));
    }

    @Test
    void helbrymsHideSupportsShapeshiftSkills() {
        final SupportDefinition hide = config.support("dormant_shell");
        assertNotNull(hide);
        for (final String form : List.of("mountain_shake_claw", "swift_claw", "wing_blade_slash", "cold_night_howl")) {
            assertNull(hide.refusal(config.skill(form)), form);
        }
        assertFalse(hide.supports(config.skill("blazing_star_shell")));
        assertEquals(1.3, hide.manaMultiplier(), 1e-9);
    }
}
