package tw.exilecore.ascendancy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照 PoE2 0.5 補上的三個昇華：靈狩者（Spirit Walker）、鳴鐘武師（Martial Artist）、淵井骸主（Abyssal Lich）。
 * 樹的相連照原作（PoB 0_5 tree.json），聖潔一統要三個技能天賦都配了才能配、不花點數；淵井骸主跟骸主同一棵樹、換掉 8 個天賦。
 */
class AscNewPathsTest {

    static AscendancyConfig config;

    @BeforeAll
    static void load() throws IOException {
        config = new AscendancyConfig();
        config.load(Path.of("src/main/resources/data"));
    }

    private static Map<String, String> allTrials(final String ascendancy) {
        final Map<String, String> progress = new HashMap<>();
        for (final AscendancyConfig.Trial trial : config.trials()) {
            progress.put(Ascension.trialKey(trial.id()), "100");
        }
        progress.put(Ascension.CHOSEN, ascendancy);
        return progress;
    }

    @Test
    void threeNewAscendanciesBelongToTheirClasses() {
        assertEquals("huntress", config.ascendancy("spiritwalker").classId());
        assertEquals("monk", config.ascendancy("martialartist").classId());
        assertEquals("witch", config.ascendancy("abysslich").classId());
        // 原作的核心天賦都在
        for (final String id : List.of("idolatry", "vivid_stampede", "morrigan", "wild_protector", "catha", "natural_order", "primal_bounty",
                "mhacha", "sacred_unity")) {
            assertTrue(config.ascendancy("spiritwalker").node(id).notable(), id);
        }
        for (final String id : List.of("runic_meridians", "hollow_focus", "hollow_resonance", "hollow_form", "mountain", "stonefist",
                "martial_adept", "martial_master")) {
            assertTrue(config.ascendancy("martialartist").node(id).notable(), id);
        }
        assertEquals(List.of("wild_protector"), config.ascendancy("spiritwalker").node("wild_protector").grantedSkills());
        assertEquals(List.of("abyssal_apparition"), config.ascendancy("abysslich").node("steward").grantedSkills());
    }

    @Test
    void sacredUnityNeedsAllThreeSpiritsAndIsFree() {
        final AscendancyConfig.Ascendancy walker = config.ascendancy("spiritwalker");
        final AscendancyConfig.Node sacred = walker.node("sacred_unity");
        assertEquals(0, sacred.cost());
        assertEquals(List.of("vivid_stampede", "wild_protector", "primal_bounty"), sacred.unlock());
        final Map<String, String> progress = allTrials("spiritwalker");
        progress.put(Ascension.NODES, "move_speed,vivid_stampede,companion_damage,wild_protector");
        Ascension ascension = new Ascension(config, progress, "huntress", 80);
        assertEquals("先配「蠻荒厚賜」", ascension.allocateProblem(sacred));
        progress.put(Ascension.NODES, "move_speed,vivid_stampede,companion_damage,wild_protector,crit_chance,primal_bounty");
        ascension = new Ascension(config, progress, "huntress", 80);
        assertNull(ascension.allocateProblem(sacred));
        progress.put(Ascension.NODES, ascension.withNode(sacred));
        ascension = new Ascension(config, progress, "huntress", 80);
        assertTrue(ascension.isAllocated(sacred));
        assertEquals(6, ascension.spent(), "神聖合一不花點數");
        assertEquals(2, ascension.available());
        // 不能當作連線的路：退掉荒野守護之前要先退聖潔一統
        assertNotNull(ascension.refundProblem(walker.node("wild_protector")));
        assertNull(ascension.refundProblem(sacred));
    }

    @Test
    void abyssalLichSharesTheLichTreeWithEightNodesReplaced() {
        final AscendancyConfig.Ascendancy lich = config.ascendancy("lich");
        final AscendancyConfig.Ascendancy abyss = config.ascendancy("abysslich");
        assertEquals(lich.nodes().size(), abyss.nodes().size());
        for (final String id : List.of("soulless_form", "eternal_life", "crystalline", "eldritch_empowerment", "incessant_cacophony")) {
            assertEquals(lich.node(id).lines(), abyss.node(id).lines(), id);
        }
        assertEquals(List.of("steward"), abyss.children("es_power").stream().map(AscendancyConfig.Node::id).toList());
        assertEquals(List.of("umbral_well"), abyss.children("minion_reservation").stream().map(AscendancyConfig.Node::id).toList());
        assertNull(abyss.node("price_power"));
        assertNull(abyss.node("necromantic_conduit"));
        assertEquals(new Modifier(StatKeys.AURA_RESERVATION, ModType.INCREASED, -0.06, Set.of("minion"), "昇華「召喚物保留效用」"),
                abyss.node("minion_reservation").stats().getFirst());
    }

    @Test
    void umbralSoulsGiveTheReplacedSkeletonsBuff() {
        assertEquals(8, AscPaths.UMBRAL.size(), "八種骷髏");
        final Modifier warrior = AscPaths.UMBRAL.get("summon_skeletons").getFirst();
        assertEquals(0.15, warrior.value(), 1e-9);
        assertEquals(Set.of("attack"), warrior.tags());
        assertEquals(0.35, AscPaths.UMBRAL.get("skeletal_frostcaller").getFirst().value(), 1e-9);
    }

    @Test
    void idolatryCountsEmptyRuneSocketsAsIdols() {
        final StatSheet sheet = new StatSheet();
        AscBuild.idolatryModifiers(sheet, 3, 2, 10, 2, -4);
        assertEquals(0.30, sheet.increased(StatKeys.DAMAGE, Set.of("minion", "companion")), 1e-9);
        assertEquals(0, sheet.increased(StatKeys.DAMAGE, Set.of()), 1e-9, "只給夥伴");
        assertEquals(-0.06, sheet.increased(StatKeys.AURA_RESERVATION, Set.of()), 1e-9);
        assertEquals(-8, sheet.flat(StatKeys.RESIST_FIRE, Set.of()), 1e-9);
    }

    @Test
    void stonefistIgnoresLegRequirementsOnly() {
        final List<Modifier> asc = List.of(Modifier.flat(AscKeys.STONEFIST, 50, "岩拳之道"));
        assertTrue(AscBuild.ignoresRequirements(asc, tw.exilecore.item.ItemSlot.LEGS));
        assertFalse(AscBuild.ignoresRequirements(asc, tw.exilecore.item.ItemSlot.CHEST));
        assertFalse(AscBuild.ignoresRequirements(List.of(), tw.exilecore.item.ItemSlot.LEGS));
    }

    @Test
    void newNodesHaveNoMissingConsumersOrPendingLines() {
        for (final String id : List.of("spiritwalker", "martialartist", "abysslich")) {
            for (final AscendancyConfig.Node node : config.ascendancy(id).nodes()) {
                assertFalse(node.stats().isEmpty(), id + "." + node.id());
                for (final String line : node.lines()) {
                    assertFalse(line.endsWith(AscendancyLayout.PENDING), id + "." + node.id() + "：" + line);
                }
                for (final Modifier modifier : node.stats()) {
                    assertNull(StatLines.problem(modifier), id + "." + node.id());
                }
            }
        }
    }
}
