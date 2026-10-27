package tw.exilecore.passive;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.AnointKeys;
import tw.exilecore.stats.Modifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-27 照 PoE2 0.5：塗油限定的核心（天賦樹上沒有、只能注入在項鍊上）：tree.json 的 anoint 有 16 個（連枷的 4 個照站長不放），
 * 不在樹上（不能配點、沒有連線），都有效果（沒有「尚未生效」），都有原作的三種情緒配方；能注入的核心清單有它們。
 */
class AnointOnlyTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    @Test
    void sixteenAnointOnlyNotablesOffTheTree() {
        assertEquals(16, tree.anointOnly().size());
        final Set<String> names = new HashSet<>();
        for (final PassiveNode node : tree.anointOnly()) {
            names.add(node.name());
            assertEquals(PassiveNode.Type.NOTABLE, node.type());
            assertNull(tree.node(node.id()), node.id() + " 不能在樹上");
            assertTrue(tree.isAnointOnly(node.id()));
            assertTrue(tree.anointNode(node.id()) == node);
            assertFalse(node.modifiers().isEmpty(), node.name() + " 沒有效果");
            for (final String line : node.description()) {
                assertFalse(line.contains("尚未生效"), node.name() + "：" + line);
                assertFalse(line.contains("Flail") || line.contains("連枷"), node.name() + "：" + line);
            }
        }
        assertTrue(names.containsAll(List.of("增幅血肉", "模範", "巨著", "統御", "神幻雪崩", "光明壁壘", "沙漠之蔑", "岡姆之祝")), names.toString());
    }

    @Test
    void anointableListsTreeNotablesThenAnointOnly() {
        final List<PassiveTree.Anointable> all = tree.anointable();
        long onTree = tree.nodes().stream().filter(n -> n.type() == PassiveNode.Type.NOTABLE).count();
        assertEquals(onTree + 16, all.size());
        assertEquals(16, all.stream().filter(PassiveTree.Anointable::anointOnly).count());
        // 樹上的核心照檔案順序在前、塗油限定的在後
        assertFalse(all.getFirst().anointOnly());
        assertTrue(all.getLast().anointOnly());
        // 不是核心的（小天賦、起點）注入查不到
        final PassiveNode start = tree.start("marauder");
        assertNull(tree.anointNode(start.id()));
    }

    @Test
    void everyAnointOnlyNotableHasAThreeEmotionRecipe() {
        final AnointRecipes recipes = AnointRecipes.get();
        assertEquals(16, recipes.all().size());
        for (final PassiveNode node : tree.anointOnly()) {
            final AnointRecipes.Recipe recipe = recipes.recipe(node.id());
            assertNotNull(recipe, node.name());
            assertEquals(node.name(), recipe.name());
            assertEquals(3, recipe.emotions().size());
        }
        // 原作的配方有順序：巨著與模範用的是同樣三種情緒，只差順序
        assertEquals(List.of("distilled_ferocity", "distilled_despair", "distilled_isolation"), recipes.recipe("p39568").emotions());
        assertEquals(List.of("distilled_ferocity", "distilled_isolation", "distilled_despair"), recipes.recipe("p20686").emotions());
        assertEquals("強效蒸餾兇猛", recipes.emotionNames().get("distilled_ferocity"));
    }

    @Test
    void newKeysAreUsedByAnointNotables() {
        final Set<String> stats = new HashSet<>();
        for (final PassiveNode node : tree.anointOnly()) {
            for (final Modifier mod : node.modifiers()) {
                stats.add(mod.stat().contains("@") ? mod.stat().substring(0, mod.stat().indexOf('@')) : mod.stat());
            }
        }
        for (final String key : List.of(AnointKeys.FLAG_WARCRY_FIRE_SPELL_ANCESTRAL, AnointKeys.VOLATILITY_CHAOS_DOT, AnointKeys.COMMAND_REVIVE_CHANCE,
                AnointKeys.MINION_REVIVE_ARCHON, AnointKeys.SHIELD_BLIND, AnointKeys.RAISE_SHIELD_PARRIED, AnointKeys.CHILLED_GROUND_FIRE_TAKEN,
                AnointKeys.IGNITED_GROUND_COLD_TAKEN, AnointKeys.CHARM_EFFECT_PER_EMPTY)) {
            assertTrue(stats.contains(key), key + "：" + stats);
        }
    }

    @Test
    void pureFormulas() {
        // 危境增長：每層 1%，20 層 = 增加 20% 中毒強度
        assertEquals(0.20, AnointKeys.perilPoison(1, 20), 1e-9);
        assertEquals(0, AnointKeys.perilPoison(1, 0), 1e-9);
        // 巨著：開 3 格、裝 1 個 → 2 個空格 × 100%
        assertEquals(2.0, AnointKeys.emptyCharmEffect(100, 3, 1), 1e-9);
        assertEquals(0, AnointKeys.emptyCharmEffect(100, 2, 2), 1e-9);
        assertEquals(0, AnointKeys.emptyCharmEffect(0, 3, 0), 1e-9);
    }
}
