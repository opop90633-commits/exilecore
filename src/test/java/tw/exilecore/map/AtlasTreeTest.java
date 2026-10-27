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

/** 星圖天賦（2026-10-21 起是一棵樹）：出貨的設定、天賦點、配點順序、退點、點數變少時的修剪、自動配路、效果加總與說明。 */
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
        assertEquals(13, tree.trunks().size(), "12 條主幹 + 中心（core）");
        assertTrue(tree.size() >= 100 && tree.size() <= 160, "約 100 多個節點：" + tree.size());
        final Set<String> ids = new HashSet<>();
        int keystones = 0;
        int notables = 0;
        for (final AtlasTree.Node node : tree.nodes()) {
            assertTrue(ids.add(node.id()), "代號重複：" + node.id());
            assertFalse(node.mods().isEmpty(), node.id() + "：要有效果");
            assertNotNull(tree.trunk(node.trunk()), node.id() + "：主幹 " + node.trunk() + " 要寫在 trunks 裡");
            assertSame(node, tree.at(node.x(), node.y()));
            assertFalse(node.x() == AtlasTree.ROOT_X && node.y() == AtlasTree.ROOT_Y, node.id() + "：不能放在起點");
            if (node.keystone()) {
                keystones++;
                assertEquals(3, node.cost(), node.id() + "：核心 3 點");
                assertFalse(node.drawbacks().isEmpty(), node.id() + "：核心要有代價");
                assertFalse(node.icon().isBlank(), node.id() + "：核心要有自己的圖示");
            } else if (node.notable()) {
                notables++;
                assertEquals(2, node.cost(), node.id() + "：大天賦 2 點");
                assertFalse(node.icon().isBlank(), node.id() + "：大天賦要有自己的圖示");
                assertTrue(node.drawbacks().isEmpty());
            } else {
                assertEquals(1, node.cost(), node.id() + "：小天賦 1 點");
            }
            assertFalse(tree.neighbours(node).isEmpty() && !tree.touchesRoot(node), node.id() + "：孤立的節點");
        }
        assertEquals(6, keystones, "六個核心");
        assertTrue(notables >= 24, "每條主幹至少兩個大天賦：" + notables);
        for (final AtlasTree.Trunk trunk : tree.trunks()) {
            assertFalse(trunk.description().isBlank(), trunk.id() + "：主幹要有說明");
            assertFalse(trunk.icon().isBlank(), trunk.id() + "：主幹要有圖示（是不是原版物品由 IconTest 檢查）");
        }
        assertTrue(tree.totalCost() > 120, "全部點滿要一百多點：" + tree.totalCost());
        assertTrue(tree.version() >= 2, "2026-10-21 改成樹：version 從 2 起");
    }

    @Test
    void indexListsTheTree() throws IOException {
        assertTrue(Files.readString(DATA.resolve("index.txt")).contains("data/atlas_tree.yml"), "新資料檔要列在 index.txt 才會同步到伺服器");
    }

    /** 拿得到的點數比全部點滿少（要挑），但至少能點滿三分之一。 */
    @Test
    void pointsForceChoices() {
        // 2026-10-18：事件巔峰（用入場券開的）不算星圖的終局點數（MapService.atlasEarned 略過）
        final int finals = maps.finals().size() - maps.eventPinnacles().size();
        // 2026-10-21：一般地圖的點數照新星圖的格子（AtlasProgress.cellPoints）：認真玩到 T16 大約 60 點
        final AtlasTree.Earned all = tree.earned(60, finals, finals, maps.tiers().max());
        assertEquals(60 + finals * 2 + finals * 2 + 5, all.total());
        assertTrue(all.total() < tree.totalCost(), "點不滿：要挑");
        assertTrue(all.total() * 3 > tree.totalCost(), "至少點得滿三分之一：" + all.total() + " / " + tree.totalCost());
    }

    /** 點了的代號存在一格（progress_value，2026-10 第 20 版加長成 VARCHAR 4096）：出貨的設定拿到全部天賦點時，最長的組合也存得下。 */
    @Test
    void longestAffordableAllocationFitsTheDatabase() {
        final int finals = maps.finals().size() - maps.eventPinnacles().size();
        final int budget = tree.earned(120, finals, finals, maps.tiers().max()).total(); // 走很遠的人：120 點
        final List<Integer> lengths = new ArrayList<>();
        for (final AtlasTree.Node node : tree.nodes()) {
            lengths.add(node.id().length());
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
        assertEquals(3, early.maps(), "星圖格子的點數 ×1");
        assertEquals(1, early.milestones(), "最高 T5：T4 那一點");
        assertEquals(4, early.total());
        assertEquals(7, tree.nextMilestone(5));
        assertEquals(4, tree.nextMilestone(0));
        assertEquals(0, tree.nextMilestone(16), "都拿了");
        final AtlasTree.Earned late = tree.earned(14, 2, 1, 13);
        assertEquals(14 + 2 * 2 + 1 * 2 + 4, late.total());
        assertFalse(late.capped());
        // 2026-10-21：總點數上限 120（約整棵樹的 7 成）
        final AtlasTree.Earned far = tree.earned(500, 5, 4, 16);
        assertEquals(120, far.total(), "超過上限的不算");
        assertEquals(500 + 5 * 2 + 4 * 2 + 5, far.raw());
        assertTrue(far.capped());
        assertTrue(tree.points().max() < tree.totalCost(), "上限點不滿整棵樹：要挑");
        assertTrue(tree.points().max() * 10 >= tree.totalCost() * 6, "上限至少點得滿六成：" + tree.points().max() + " / " + tree.totalCost());
    }

    // ---- 配點 ----

    @Test
    void mustAllocateOutwardFromTheRoot() {
        // 輻條第一格跟起點相鄰，可以直接點；第二格要先點第一格
        assertNull(tree.allocateProblem(List.of(), "sp_n1", 10));
        final String problem = tree.allocateProblem(List.of(), "sp_n2", 10);
        assertNotNull(problem);
        assertTrue(problem.contains("旁邊"), "說要先點旁邊的：" + problem);
        assertNull(tree.allocateProblem(List.of("sp_n1"), "sp_n2", 10));
        assertTrue(tree.allocateProblem(List.of("sp_n1"), "sp_n1", 10).contains("已經點了"));
        assertEquals("沒有這個天賦", tree.allocateProblem(List.of(), "nope", 10));
        // 內環從輻條接上去：(0,-2) 的上面是內環的 (0,-3)
        final AtlasTree.Node ringTop = tree.at(0, -3);
        assertNotNull(ringTop);
        assertNull(tree.allocateProblem(List.of("sp_n1", "sp_n2"), ringTop.id(), 10));
    }

    @Test
    void notEnoughPointsSaysHowMany() {
        assertTrue(tree.allocateProblem(List.of(), "sp_n1", 0).contains("要 1 點，你還有 0 點"));
        final List<String> path = List.of("sp_n1", "sp_n2", tree.at(0, -3).id(), "wealth_1", "wealth_2");
        final String problem = tree.allocateProblem(path, "wealth_3", 6);
        assertNotNull(problem);
        assertTrue(problem.contains("要 2 點，你還有 1 點"), problem);
        assertNull(tree.allocateProblem(path, "wealth_3", 7));
    }

    @Test
    void refundTakesWhateverLosesTheRoot() {
        final String ring = tree.at(0, -3).id();
        final List<String> allocated = List.of("sp_n1", "sp_n2", ring, "wealth_1", "sp_e1", "wealth_2");
        // 退掉 sp_n2：內環那格與豐饒的兩格都連不回中心；sp_e1 還在
        assertEquals(List.of("sp_n2", ring, "wealth_1", "wealth_2"), tree.refund(allocated, "sp_n2"));
        assertEquals(List.of("sp_e1"), tree.refund(allocated, "sp_e1"));
        assertEquals(List.of("wealth_2"), tree.refund(allocated, "wealth_2"), "尾端只退自己");
        assertTrue(tree.refund(allocated, "wealth_3").isEmpty(), "沒點的退不了");
        assertTrue(tree.refund(allocated, "nope").isEmpty());
    }

    @Test
    void effectiveTrimsFromTheLastAllocated() {
        final String ring = tree.at(0, -3).id();
        final List<String> stored = List.of("sp_n1", "sp_n2", ring, "wealth_1", "wealth_2", "wealth_3");
        assertEquals(stored, tree.effective(stored, 7));
        assertEquals(List.of("sp_n1", "sp_n2", ring, "wealth_1", "wealth_2"), tree.effective(stored, 6), "大天賦要 2 點：點數少一點就不算");
        assertEquals(List.of("sp_n1", "sp_n2"), tree.effective(stored, 2));
        assertTrue(tree.effective(stored, 0).isEmpty());
        // 點數不夠時從最後點的開始不算（不會跳過放不下的、留下更後面點的）
        assertEquals(List.of("sp_e1", "sp_n1", "sp_n2", ring),
                tree.effective(List.of("sp_e1", "sp_n1", "sp_n2", ring, "wealth_1", "sp_s1"), 4));
        // 不認得的、順序不對的（還沒連到）不算
        assertEquals(List.of("sp_n1"), tree.effective(List.of("gone", "sp_n2", "sp_n1"), 10));
    }

    @Test
    void pathToFindsTheCheapestRoute() {
        // 從空的到豐饒第 1 格：輻條 2 格 + 內環 1 格 + 目標 = 4 個
        final List<AtlasTree.Node> path = tree.pathTo(List.of(), tree.node("wealth_1"));
        assertEquals(4, path.size(), path.toString());
        assertEquals("wealth_1", path.get(path.size() - 1).id());
        assertEquals("sp_n1", path.get(0).id());
        // 已經點到內環：只剩目標
        assertEquals(List.of(tree.node("wealth_1")), tree.pathTo(List.of("sp_n1", "sp_n2", tree.at(0, -3).id()), tree.node("wealth_1")));
        assertTrue(tree.pathTo(List.of("sp_n1"), tree.node("sp_n1")).isEmpty(), "已點的沒有路");
        // 橋：從豐饒的第 6 格往隔壁的群聚走，不用回中心
        final List<String> wealth = new ArrayList<>(List.of("sp_n1", "sp_n2", tree.at(0, -3).id()));
        for (int i = 1; i <= 6; i++) {
            wealth.add("wealth_" + i);
        }
        final List<AtlasTree.Node> hop = tree.pathTo(wealth, tree.node("packs_6"));
        assertEquals(2, hop.size(), "一格橋 + 目標：" + hop);
        assertTrue(hop.get(0).id().startsWith("bridge_"), hop.get(0).id());
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
        assertEquals(7, tree.spent(List.of("wealth_1", "wealth_2", "wealth_3", "wealth_7")), "1 + 1 + 2（大天賦）+ 3（核心）");
        assertEquals(1, tree.spent(List.of("wealth_1", "gone")));
    }

    // ---- 效果 ----

    @Test
    void bonusSumsAndDescribes() {
        final AtlasBonus bonus = tree.bonus(List.of("wealth_1", "wealth_2", "wealth_3"));
        // 2026-10-27 照 PoE2：星圖天賦沒有物品數量，數量換成稀有度（這次之前數量 7、稀有度 12）
        assertEquals(0, bonus.get("map.quantity"), 1e-9);
        assertEquals(26, bonus.get("map.rarity"), 1e-9);
        assertEquals(List.of("地圖的物品稀有度 +26%"), bonus.describe());
        assertSame(AtlasBonus.NONE, tree.bonus(List.of()));
        assertTrue(AtlasBonus.NONE.describe().isEmpty());
    }

    @Test
    void keystoneDrawbacksAreCountedAndDescribed() {
        final AtlasBonus bonus = tree.bonus(List.of("wealth_7"));
        // 2026-10-21：孤注一擲 50 → 30；2026-10-27 照 PoE2：數量 30 換成稀有度 30＋怪物群 15
        assertEquals(0, bonus.get("map.quantity"), 1e-9);
        assertEquals(30, bonus.get("map.rarity"), 1e-9);
        assertEquals(15, bonus.get("map.pack_size"), 1e-9);
        assertEquals(50, bonus.get("atlas.map_drop_less"), 1e-9);
        assertTrue(bonus.describe().contains("地圖怪掉地圖的機率減少 50%"), bonus.describe().toString());
        assertTrue(AtlasTree.isDrawback("atlas.map_drop_less"));
        assertFalse(AtlasTree.isDrawback("map.quantity"));
    }

    @Test
    void mapStatsPlusAddsTheMapPart() {
        final MapStats base = new MapStats(5, 65, 8, 9, 0, 0, 0, List.of(), List.of(), List.of());
        assertSame(base, base.plus(AtlasBonus.NONE));
        final AtlasBonus bonus = tree.bonus(List.of("wealth_1", "wealth_2", "packs_1", "packs_2", "packs_4"));
        final MapStats boosted = base.plus(bonus);
        assertEquals(8, boosted.quantity(), 1e-9, "2026-10-27：拾荒不再給數量（這次之前 11）");
        assertEquals(21, boosted.rarity(), 1e-9, "2026-10-27：拾荒改給稀有度 6（這次之前 15）");
        assertEquals(5, boosted.packSize(), 1e-9);
        assertEquals(15, boosted.magicPacks(), 1e-9);
        assertEquals(15, boosted.rarePacks(), 1e-9);
        assertEquals(5, boosted.tier());
        assertEquals(65, boosted.monsterLevel());
    }

    @Test
    void eventKeysAndCounts() {
        final AtlasBonus bonus = tree.bonus(List.of("shrine_1", "shrine_2", "shrine_3", "shrine_6", "sustain_1", "sustain_2",
                "sustain_3", "sustain_6", "exile_1", "exile_2"));
        assertEquals(0.7, bonus.eventWeight(MapEventConfig.Kind.SHRINE), 1e-9, "40 + 30");
        assertEquals(1, bonus.extraEvents(MapEventConfig.Kind.SHRINE));
        assertEquals(0.25, bonus.eventUnique(MapEventConfig.Kind.SHRINE), 1e-9);
        assertEquals(0.5, bonus.increased("atlas.shrine.duration"), 1e-9);
        assertEquals(0, bonus.extraEvents(MapEventConfig.Kind.BREACH));
        assertEquals(0.35, bonus.mapDrop(), 1e-9);
        assertEquals(0.26, bonus.tierUp(), 1e-9);
        assertEquals(1, bonus.bossMaps());
        assertEquals(1, bonus.count("atlas.exile.drops"));
        assertEquals(0.4, bonus.eventWeight(MapEventConfig.Kind.EXILE), 1e-9);
    }

    @Test
    void everyStatDescribesItself() {
        for (final String stat : AtlasTree.STATS.keySet()) {
            final String text = AtlasTree.describe(stat, 12.5);
            assertFalse(text.contains("{v}"), stat);
            assertTrue(text.contains("12.5") || !AtlasTree.STATS.get(stat).contains("{v}"), stat + "：" + text);
        }
        for (final String stat : AtlasTree.DRAWBACKS.keySet()) {
            assertFalse(AtlasTree.describe(stat, 12.5).contains("{v}"), stat);
        }
        assertEquals("地圖 Boss 多掉 1 張地圖", AtlasTree.describe("atlas.boss_maps", 1));
        // 出貨的每個效果鍵都認得
        for (final AtlasTree.Node node : tree.nodes()) {
            for (final AtlasTree.Mod mod : node.mods()) {
                assertTrue(AtlasTree.STATS.containsKey(mod.stat()), node.id() + "：" + mod.stat());
            }
            for (final AtlasTree.Mod mod : node.drawbacks()) {
                assertTrue(AtlasTree.DRAWBACKS.containsKey(mod.stat()), node.id() + "：" + mod.stat());
            }
        }
    }

    // ---- 寫錯的設定 ----

    @Test
    void badConfigReportsProblemsAndKeepsTheRest() {
        final StringBuilder yaml = new StringBuilder();
        yaml.append("version: 3\n");
        yaml.append("points: { per_map: 2, tier_milestones: [8, x, 3] }\n");
        yaml.append("trunks:\n  a: { name: A, color: red }\n");
        yaml.append("nodes:\n");
        yaml.append("  - { id: a1, x: 0, y: -1, trunk: a, mods: [{ stat: map.quantity, value: 5 }, { stat: nope, value: 1 }] }\n");
        yaml.append("  - { id: a1, x: 0, y: -2, trunk: a, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("  - { id: a2, x: 0, y: -2, trunk: a, cost: 0, mods: [{ stat: map.rarity, value: -5 }] }\n");
        yaml.append("  - { id: 'a 3', x: 1, y: 0, trunk: a, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("  - { id: a4, x: 0, y: 0, trunk: a, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("  - { id: a5, x: 5, y: 5, trunk: zz, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("  - { id: a6, x: 0, y: -3, trunk: a, keystone: true, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("  - { id: a7, x: 0, y: -2, trunk: a, mods: [{ stat: map.rarity, value: 5 }] }\n");
        yaml.append("  - { id: a8, trunk: a, mods: [{ stat: map.rarity, value: 5 }] }\n");
        final AtlasTree bad = AtlasTree.parse(YamlNode.load("atlas_tree.yml", new StringReader(yaml.toString())));
        final String problems = String.join("\n", bad.problems());
        assertTrue(problems.contains("不認得的效果「nope」"), problems);
        assertTrue(problems.contains("a1 重複"), problems);
        assertTrue(problems.contains("數值要大於 0"), problems);
        assertTrue(problems.contains("cost 要在 1–5"), problems);
        assertTrue(problems.contains("不能有逗號或空白"), problems);
        assertTrue(problems.contains("放在起點"), problems);
        assertTrue(problems.contains("連不到起點"), problems);
        assertTrue(problems.contains("trunk「zz」"), problems);
        assertTrue(problems.contains("核心要有代價"), problems);
        assertTrue(problems.contains("同一格"), problems);
        assertTrue(problems.contains("缺少 x、y"), problems);
        assertTrue(problems.contains("「x」不是數字"), problems);
        assertTrue(problems.contains("要寫成 #rrggbb"), problems);
        assertEquals(3, bad.version());
        assertEquals(2, bad.points().perMap());
        assertEquals(List.of(3, 8), bad.points().tierMilestones(), "照大小排、壞的略過");
        assertNotNull(bad.node("a1"));
        assertNotNull(bad.node("a2"), "cost 夾到 1 之後還在");
        assertEquals(1, bad.node("a2").cost());
        assertNull(bad.node("a 3"));
        assertNull(bad.node("a4"), "起點上的不算");
        assertNull(bad.node("a7"), "同一格的第二個不算");
        assertNull(bad.node("a8"), "沒座標的不算");
        assertEquals(List.of("a1", "a2"), bad.effective(List.of("a1", "a2"), 10), "a2 (0,-2) 跟 a1 (0,-1) 相鄰：可以點");
    }

    @Test
    void missingFileIsEmpty() throws IOException {
        final AtlasTree empty = new AtlasTree();
        empty.load(Files.createTempDirectory("atlas"));
        assertEquals(0, empty.size());
        assertEquals(1, empty.problems().size());
        assertEquals(AtlasTree.DEFAULT_POINTS, empty.points());
        assertEquals("沒有這個天賦", empty.allocateProblem(List.of(), "x", 1));
        assertSame(AtlasBonus.NONE, empty.bonus(List.of("x")));
    }
}
