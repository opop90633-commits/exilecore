package tw.exilecore.skill;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-28 照 PoE2 的 Cascadable（SkillCascade）：詛咒掛得上連瀑、三迴、雪封；原作不是 Cascadable 的（雷雲、烈陽珠、燼種、疫潮…）掛不上；
 * 原作沒有對應的照形狀。這次之前三元詛咒（elemental_weakness_curse）掛不上連瀑（站長回報）。
 */
class SkillCascadeTest {

    static SkillConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new SkillConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static SkillDefinition skill(final String id) {
        final SkillDefinition skill = config.skill(id);
        assertNotNull(skill, id);
        return skill;
    }

    private static String refusal(final String support, final String skill) {
        final SupportDefinition definition = config.support(support);
        assertNotNull(definition, support);
        return definition.refusal(skill(skill));
    }

    /** 原作的詛咒（我們有的五個）。 */
    static final List<String> CURSES = List.of("vulnerability_curse", "despair_curse", "elemental_weakness_curse", "enfeeble_curse", "temporal_curse");

    @Test
    void cursesTakeSpellCascadeAndTheLineageEchoes() {
        for (final String curse : CURSES) {
            assertTrue(skill(curse).tags().contains("curse"), curse);
            assertTrue(SkillCascade.cascadable(skill(curse)), curse);
            assertNull(refusal("spell_cascade", curse), curse + " 掛得上連瀑");
            assertTrue(config.support("spell_cascade").supports(skill(curse)), curse);
            assertNull(refusal("zarokhs_refrain", curse), curse + " 掛得上三迴");
            assertNull(refusal("tuls_avalanche", curse), curse + " 掛得上雪封");
        }
    }

    @Test
    void everyPlayerCurseIsCascadableButMarksAreNot() {
        for (final SkillDefinition skill : config.all()) {
            if (skill.hidden() || skill.mechanic() != Mechanic.MARK) {
                continue;
            }
            final boolean curse = skill.tags().contains("curse") && skill.tags().contains("spell");
            if (CURSES.contains(skill.id())) {
                assertTrue(curse, skill.id());
            } else {
                // 印記（獵殺、引雷、霜縛、血嗅、處決）、縛根、凋蝕術：原作不是 Cascadable
                assertFalse(SkillCascade.cascadable(skill), skill.id());
                assertNotNull(config.support("spell_cascade").refusal(skill), skill.id());
            }
        }
        assertFalse(SkillCascade.cascadable(skill("hunters_mark")));
        assertFalse(SkillCascade.cascadable(skill("entangle")), "PoE2 Entangle 不是 Cascadable");
    }

    @Test
    void followsPoe2WhereThereIsAnEquivalent() {
        // 原作 Cascadable、這次之前形狀不對掛不上的
        for (final String id : List.of("flame_wall", "ice_wall", "detonate_dead", "volatile_dead")) {
            assertTrue(SkillCascade.cascadable(skill(id)), id);
            assertNull(refusal("spell_cascade", id), id);
        }
        // 原作不是 Cascadable、這次之前照形狀掛得上的
        for (final String id : List.of("storm_orb", "sun_orb", "ember_seed", "contagion", "primal_tornado", "volcano", "galvanic_field", "reap",
                "dark_pact", "inferno_blast")) {
            assertFalse(SkillCascade.cascadable(skill(id)), id);
            assertNotNull(refusal("spell_cascade", id), id);
        }
        assertNotNull(refusal("zarokhs_refrain", "storm_orb"));
        // 照舊：落在目標區域、身邊一圈的
        for (final String id : List.of("comet", "firestorm", "frost_nova", "enervating_nova", "lightning_bolt", "bone_cage", "thunderstorm")) {
            assertTrue(SkillCascade.cascadable(skill(id)), id);
        }
        assertNotNull(refusal("spell_cascade", "fireball"));
    }

    @Test
    void listsOnlyNameRealSkills() {
        for (final String id : SkillCascade.POE2) {
            assertTrue(skill(id).tags().contains("spell"), id + " 要是法術");
        }
        for (final String id : SkillCascade.NOT_POE2) {
            assertTrue(skill(id).tags().contains("spell"), id); // 引導的（炎爆、地火柱）照形狀本來就不算，寫出來是對照原作
        }
        assertTrue(java.util.Collections.disjoint(SkillCascade.POE2, SkillCascade.NOT_POE2));
    }

    @Test
    void shapeFallbackOnlyForSkillsWithoutAPoe2Equivalent() {
        // 照形狀算可以連瀑的看得到的技能：都是原作沒有對應的（我們自己的、Boss 專屬）。多出新的要對 RePoE（tools/audit_poe2_cascadable.py），
        // 原作不是 Cascadable 就寫進 SkillCascade.NOT_POE2
        final Set<String> shaped = new TreeSet<>();
        for (final SkillDefinition skill : config.all()) {
            if (!skill.hidden() && SkillCascade.cascadable(skill) && !SkillCascade.POE2.contains(skill.id())) {
                shaped.add(skill.id());
            }
        }
        assertEquals(new TreeSet<>(List.of("abyssal_pull", "blood_altar", "breach_ring", "dawn_pillar", "void_meteor")), shaped);
    }

    @Test
    void cascadeSpacingUsesTheCurseRadius() {
        final SkillDefinition weakness = skill("elemental_weakness_curse");
        final SkillBuild build = SkillBuild.of(config, weakness, 20, 90, false, List.of(), Set.of(), new StatSheet());
        assertEquals(weakness.params().mark().radius(20) * build.areaScale(), SkillCascade.radius(build), 1e-9);
        assertEquals(tw.exilecore.core.Units.metres(3.1), weakness.params().mark().radius(20), 1e-9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），期望值照原作公尺換算
        // 連瀑範圍更少：詛咒圈跟著變小
        final SkillBuild smaller = build.withAreaAndProjectiles(build.areaScale() * Math.sqrt(0.8), build.projectiles());
        assertEquals(SkillCascade.radius(build) * Math.sqrt(0.8), SkillCascade.radius(smaller), 1e-9);
        final SkillDefinition comet = skill("comet");
        final SkillBuild cometBuild = SkillBuild.of(config, comet, 20, 90, false, List.of(), Set.of(), new StatSheet());
        assertEquals(cometBuild.radius(), SkillCascade.radius(cometBuild), 1e-9);
    }
}
