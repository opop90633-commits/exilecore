package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 星圖天賦：出貨的設定、天賦點、配點順序、退點、點數變少時的修剪、效果加總與說明。 */
class AtlasTreeTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static final AtlasTree tree = new AtlasTree();
    static final MapConfig maps = new MapConfig();

    @BeforeAll
    static void load() throws IOException {
        tree.load(DATA);
        maps.load(DATA);
    }

    // ---- 出貨的設定 ----

    @Test
    void shippedTreeLoadsCleanly() {
        assertTrue(tree.problems().isEmpty(), "atlas_tree.yml 的問題：" + tree.problems());
        assertEquals(AtlasTree.MAX_BRANCHES, tree.branches().size(), "九條分支，介面一條一欄");
        final Set<String> ids = new HashSet<>();
        for (final AtlasTree.Branch branch : tree.branches()) {
            assertEquals(AtlasTree.MAX_NODES, branch.nodes().size(), branch.id() + "：四個節點");
            assertFalse(branch.description().isBlank(), branch.id() + "：分支要有說明");
            assertFalse(branch.icon().isBlank(), branch.id() + "：分支要有圖示（是不是原版物品由 IconTest 檢查）");
            for (final AtlasTree.Node node : branch.nodes()) {
                assertTrue(ids.add(node.id()), "代號重複：" + node.id());
                assertFalse(node.mods().isEmpty(), node.id() + "：要有效果");
                final boolean last = node.index() == branch.nodes().size() - 1;
                assertEquals(last, node.notable(), node.id() + "：最後一個是大天賦");
                assertEquals(last ? 2 : 1, node.cost(), node.id() + "：一般 1 點、大天賦 2 點");
                if (node.notable()) {
                    assertFalse(node.icon().isBlank(), node.id() + "：大天賦要有自己的圖示");
                }
            }
        }
        assertEquals(45, tree.totalCost(), "全部點滿 45 點");
    }

    @Test
    void indexListsTheTree() throws IOException {
        assertTrue(Files.readString(DATA.resolve("index.txt")).contains("data/atlas_tree.yml"), "新資料檔要列在 index.txt 才會同步到伺服器");
    }

    /** 拿得到的點數比全部點滿少（要挑），但至少能點滿一半以上。 */
    @Test
    void pointsForceChoices() {
        final int finals = maps.finals().size();
        final AtlasTree.Earned all = tree.earned(maps.maps().size(), finals, finals, maps.tiers().max());
        assertEquals(maps.maps().size() + finals + finals + 5, all.total());
        assertEquals(29, all.total(), "14 張地圖 + 5 個終局（含巔峰萬界之巔）+ 5 個極境 + 5 個階級");
        assertTrue(all.total() < tree.totalCost(), "點不滿：要挑");
        assertTrue(all.total() * 2 > tree.totalCost(), "至少點得滿一半");
    }

    /** 點了的代號存在一格（progress_value，2026-10 第 20 版加長成 VARCHAR 4096）：出貨的設定拿到全部天賦點時，最長的組合也存得下。 */
    @Test
    void longestAffordableAllocationFitsTheDatabase() {
        final int finals = maps.finals().size();
        final int budget = tree.earned(maps.maps().size(), finals, finals, maps.tiers().max()).total();
        // 上限：最長的 budget 個代號（每個至少 1 點）加上逗號
        final List<Integer> lengths = new ArrayList<>();
        for (final AtlasTree.Branch branch : tree.branches()) {
            for (final AtlasTree.Node node : branch.nodes()) {
                lengths.add(node.id().length());
            }
        }
        lengths.sort(java.util.Comparator.reverseOrder());
        int bound = Math.max(0, Math.min(budget, lengths.size()) - 1);
        for (int i = 0; i < Math.min(budget, lengths.size()); i++) {
            bound += lengths.get(i);
        }
        assertTrue(bound <= AtlasTree.MAX_STORED_LENGTH, "最長可能 " + bound + " 字，超過資料庫的 " + AtlasTree.MAX_STORED_LENGTH);
    }

    @Test
    void earnedPoints() {
        assertEquals(0, tree.earned(0, 0, 0, 0).total());
        final AtlasTree.Earned early = tree.earned(3, 0, 0, 5);
        assertEquals(3, early.maps());
        assertEquals(1, early.milestones(), "最高 T5：T4 那一點");
        assertEquals(4, early.total());
        assertEquals(7, tree.nextMilestone(5));
        assertEquals(4, tree.nextMilestone(0));
        assertEquals(0, tree.nextMilestone(16), "都拿了");
        final AtlasTree.Earned late = tree.earned(14, 2, 1, 13);
        assertEquals(14 + 2 + 1 + 4, late.total());
    }

    // ---- 配點 ----

    @Test
    void mustAllocateFromTheTop() {
        final String problem = tree.allocateProblem(List.of(), "wealth_2", 10);
        assertNotNull(problem);
        assertTrue(problem.contains("拾荒"), "說要先點哪一個：" + problem);
        assertNull(tree.allocateProblem(List.of(), "wealth_1", 10));
        assertNull(tree.allocateProblem(List.of("wealth_1"), "wealth_2", 10));
        assertTrue(tree.allocateProblem(List.of("wealth_1"), "wealth_1", 10).contains("已經點了"));
        assertEquals("沒有這個天賦", tree.allocateProblem(List.of(), "nope", 10));
    }

    @Test
    void notEnoughPointsSaysHowMany() {
        assertTrue(tree.allocateProblem(List.of(), "wealth_1", 0).contains("要 1 點，你還有 0 點"));
        final List<String> three = List.of("wealth_1", "wealth_2", "wealth_3");
        final String problem = tree.allocateProblem(three, "wealth_4", 4);
        assertNotNull(problem);
        assertTrue(problem.contains("要 2 點，你還有 1 點"), problem);
        assertNull(tree.allocateProblem(three, "wealth_4", 5));
    }

    @Test
    void refundTakesDeeperNodesOfTheSameBranch() {
        final List<String> allocated = List.of("wealth_1", "wealth_2", "packs_1", "wealth_3");
        assertEquals(List.of("wealth_2", "wealth_3"), tree.refund(allocated, "wealth_2"));
        assertEquals(List.of("packs_1"), tree.refund(allocated, "packs_1"));
        assertTrue(tree.refund(allocated, "wealth_4").isEmpty(), "沒點的退不了");
        assertTrue(tree.refund(allocated, "nope").isEmpty());
    }

    @Test
    void effectiveTrimsFromTheLastAllocated() {
        final List<String> stored = List.of("wealth_1", "wealth_2", "packs_1", "wealth_3", "wealth_4");
        assertEquals(stored, tree.effective(stored, 6));
        assertEquals(List.of("wealth_1", "wealth_2", "packs_1", "wealth_3"), tree.effective(stored, 5), "大天賦要 2 點：點數少一點就不算");
        assertEquals(List.of("wealth_1", "wealth_2"), tree.effective(stored, 2));
        assertTrue(tree.effective(stored, 0).isEmpty());
        // 點數不夠時從最後點的開始不算（不會跳過放不下的、留下更後面點的）
        assertEquals(List.of("packs_1", "wealth_1", "wealth_2", "wealth_3"),
                tree.effective(List.of("packs_1", "wealth_1", "wealth_2", "wealth_3", "wealth_4", "sustain_1"), 5));
        // 不認得的、順序不對的（前一個還沒點）不算
        assertEquals(List.of("wealth_1"), tree.effective(List.of("gone", "wealth_2", "wealth_1"), 10));
    }

    @Test
    void allocationStringRoundTrip() {
        assertEquals(List.of("a", "b"), AtlasTree.parseAllocation(" a, b,,a "));
        assertTrue(AtlasTree.parseAllocation(null).isEmpty());
        assertTrue(AtlasTree.parseAllocation("  ").isEmpty());
        assertEquals("a,b", AtlasTree.joinAllocation(List.of("a", "b")));
        assertEquals(List.of("wealth_1", "packs_1"), AtlasTree.parseAllocation(AtlasTree.joinAllocation(List.of("wealth_1", "packs_1"))));
    }

    @Test
    void spentCountsCosts() {
        assertEquals(5, tree.spent(List.of("wealth_1", "wealth_2", "wealth_3", "wealth_4")));
        assertEquals(1, tree.spent(List.of("wealth_1", "gone")));
    }

    // ---- 效果 ----

    @Test
    void bonusSumsAndDescribes() {
        final AtlasBonus bonus = tree.bonus(List.of("wealth_1", "wealth_2", "wealth_3", "wealth_4"));
        assertEquals(10, bonus.get("map.quantity"), 1e-9);
        assertEquals(14, bonus.get("map.rarity"), 1e-9);
        assertEquals(List.of("地圖的物品數量 +10%", "地圖的物品稀有度 +14%"), bonus.describe());
        assertSame(AtlasBonus.NONE, tree.bonus(List.of()));
        assertTrue(AtlasBonus.NONE.describe().isEmpty());
    }

    @Test
    void mapStatsPlusAddsTheMapPart() {
        final MapStats base = new MapStats(5, 65, 8, 9, 0, 0, 0, List.of(), List.of(), List.of());
        assertSame(base, base.plus(AtlasBonus.NONE));
        final AtlasBonus bonus = tree.bonus(List.of("wealth_1", "wealth_2", "packs_1", "packs_2", "packs_3"));
        final MapStats boosted = base.plus(bonus);
        assertEquals(11, boosted.quantity(), 1e-9);
        assertEquals(15, boosted.rarity(), 1e-9);
        assertEquals(5, boosted.packSize(), 1e-9);
        assertEquals(15, boosted.magicPacks(), 1e-9);
        assertEquals(15, boosted.rarePacks(), 1e-9);
        assertEquals(5, boosted.tier());
        assertEquals(65, boosted.monsterLevel());
    }

    @Test
    void eventKeysAndCounts() {
        final AtlasBonus bonus = tree.bonus(List.of("shrine_1", "shrine_2", "shrine_3", "shrine_4", "sustain_1", "sustain_2",
                "sustain_3", "sustain_4", "exile_1", "exile_2"));
        assertEquals(0.5, bonus.eventWeight(MapEventConfig.Kind.SHRINE), 1e-9);
        assertEquals(1, bonus.extraEvents(MapEventConfig.Kind.SHRINE));
        assertEquals(0.25, bonus.eventUnique(MapEventConfig.Kind.SHRINE), 1e-9, "2026-10：+50% → +25%");
        assertEquals(0.5, bonus.increased("atlas.shrine.duration"), 1e-9);
        assertEquals(0, bonus.extraEvents(MapEventConfig.Kind.BREACH));
        assertEquals(0.4, bonus.mapDrop(), 1e-9);
        assertEquals(0.2, bonus.tierUp(), 1e-9);
        assertEquals(1, bonus.bossMaps());
        assertEquals(1, bonus.count("atlas.exile.drops"));
        assertEquals(0.5, bonus.eventWeight(MapEventConfig.Kind.EXILE), 1e-9);
    }

    @Test
    void everyStatDescribesItself() {
        for (final String stat : AtlasTree.STATS.keySet()) {
            final String text = AtlasTree.describe(stat, 12.5);
            assertFalse(text.contains("{v}"), stat);
            assertTrue(text.contains("12.5"), stat + "：" + text);
        }
        assertEquals("地圖 Boss 多掉 1 張地圖", AtlasTree.describe("atlas.boss_maps", 1));
        // 出貨的每個效果鍵都認得
        for (final AtlasTree.Branch branch : tree.branches()) {
            for (final AtlasTree.Node node : branch.nodes()) {
                for (final AtlasTree.Mod mod : node.mods()) {
                    assertTrue(AtlasTree.STATS.containsKey(mod.stat()), node.id() + "：" + mod.stat());
                }
            }
        }
    }

    // ---- 寫錯的設定 ----

    @Test
    void badConfigReportsProblemsAndKeepsTheRest() {
        final StringBuilder yaml = new StringBuilder();
        yaml.append("points: { per_map: 2, tier_milestones: [8, x, 3] }\n");
        yaml.append("branches:\n");
        yaml.append("  a:\n    name: A\n    color: red\n    nodes:\n");
        yaml.append("      - { id: a1, mods: [{ stat: map.quantity, value: 5 }, { stat: nope, value: 1 }] }\n");
        yaml.append("      - { id: a1, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("      - { id: a2, cost: 0, mods: [{ stat: map.rarity, value: -5 }] }\n");
        yaml.append("      - { id: 'a 3', mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("      - { id: a4, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("      - { id: a5, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("      - { id: a6, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("  empty:\n    nodes: []\n");
        for (int i = 0; i < 10; i++) {
            yaml.append("  b").append(i).append(":\n    nodes:\n      - { id: b").append(i).append("_1, mods: [{ stat: map.quantity, value: 1 }] }\n");
        }
        final AtlasTree bad = AtlasTree.parse(YamlNode.load("atlas_tree.yml", new StringReader(yaml.toString())));
        final String problems = String.join("\n", bad.problems());
        assertTrue(problems.contains("不認得的效果「nope」"), problems);
        assertTrue(problems.contains("a1 重複"), problems);
        assertTrue(problems.contains("數值要大於 0"), problems);
        assertTrue(problems.contains("cost 要在 1–5"), problems);
        assertTrue(problems.contains("不能有逗號或空白"), problems);
        assertTrue(problems.contains("節點最多 4 個"), problems);
        assertTrue(problems.contains("分支 empty 沒有能用的節點"), problems);
        assertTrue(problems.contains("分支最多 9 條"), problems);
        assertTrue(problems.contains("#rrggbb"), problems);
        assertTrue(problems.contains("「x」不是數字"), problems);
        // 能用的照樣讀進來
        final AtlasTree.Branch a = bad.branch("a");
        assertNotNull(a);
        assertEquals(List.of("a1", "a2", "a4", "a5"), a.nodes().stream().map(AtlasTree.Node::id).toList());
        assertEquals(1, a.nodes().get(1).cost(), "cost 0 夾到 1");
        assertEquals("#f5b942", a.color());
        assertEquals(AtlasTree.MAX_BRANCHES, bad.branches().size());
        assertEquals(2, bad.points().perMap());
        assertEquals(List.of(3, 8), bad.points().tierMilestones(), "由小到大");
    }

    @Test
    void missingFileIsAnEmptyTree() throws IOException {
        final Path empty = Files.createTempDirectory("atlas-tree");
        final AtlasTree missing = new AtlasTree();
        missing.load(empty);
        assertTrue(missing.branches().isEmpty());
        assertFalse(missing.problems().isEmpty());
        assertEquals(AtlasTree.DEFAULT_POINTS, missing.points());
        final List<String> none = new ArrayList<>();
        assertSame(AtlasBonus.NONE, missing.bonus(none));
    }
}
