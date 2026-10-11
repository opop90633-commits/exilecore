package tw.exilecore.passive;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassiveTreeTest {

    static PassiveTree tree;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
    }

    @Test
    void treeIsConnectedAndHasAllStarts() {
        assertTrue(tree.size() >= 650, "節點數 " + tree.size());
        assertEquals(3, tree.version(), "加了德魯伊、八個職業的樹是第 3 版（舊角色的配點全部退回）");
        assertTrue(tree.isConnected());
        for (final tw.exilecore.character.CharacterClass type : tw.exilecore.character.CharacterClass.values()) {
            final String cls = type.id();
            final PassiveNode start = tree.start(cls);
            assertNotNull(start, cls + " 要有起點");
            assertTrue(start.isStart());
            assertEquals(cls, start.startClass());
            // 起點是葉子：只連一格，別的職業的路不會穿過它（起點不能配、也不能當路）
            assertEquals(1, tree.neighbours(start).size(), cls + " 的起點不是葉子");
        }
        assertEquals(tw.exilecore.character.CharacterClass.values().length, tree.starts().size());
        int keystones = 0;
        int notables = 0;
        int sockets = 0;
        for (final PassiveNode node : tree.nodes()) {
            if (node.type() == PassiveNode.Type.KEYSTONE) {
                keystones++;
            } else if (node.type() == PassiveNode.Type.NOTABLE) {
                notables++;
            } else if (node.isSocket()) {
                sockets++;
                assertTrue(node.modifiers().isEmpty(), "珠寶孔本身沒有修飾器（珠寶才有）：" + node.id());
                assertEquals(1, tree.neighbours(node).size(), "珠寶孔是掛在路上的葉子：" + node.id());
                assertFalse(tree.neighbours(node).getFirst().isSocket(), "珠寶孔不會接在另一個珠寶孔上");
            }
            assertTrue(node.isStart() || node.isSocket() || !node.modifiers().isEmpty(), node.id() + " 沒有修飾器");
            assertTrue(node.isStart() || !node.description().isEmpty(), node.id() + " 沒有說明");
        }
        assertTrue(keystones >= 16, "基石 " + keystones);
        assertTrue(notables >= 60, "核心 " + notables);
        assertEquals(18, sockets, "珠寶孔：每區 2 個 + 中心 2 個");
        // 珠寶孔放在原本的 716 個節點後面，異常狀態的群組（2026-10）再放在珠寶孔後面（舊的規劃代碼的位元順序不變）
        final List<PassiveNode> ordered = tree.ordered();
        final int ailmentEnd = 734 + 16 * 5;
        final int critEnd = ailmentEnd + 4 * 5;
        final int chargeEnd = critEnd + 3;
        assertEquals(chargeEnd + 61, ordered.size(), "716 ＋ 18 珠寶孔 ＋ 16 個異常狀態的叉 ＋ 4 個暴擊的叉（各 5 個）＋ 3 個戰星的核心"
                + " ＋ 61 個第 3 版新加的（德魯伊與 PoE2 機制的群組）");
        for (int i = 716; i < 734; i++) {
            assertTrue(ordered.get(i).isSocket(), "第 " + i + " 個節點應該是珠寶孔");
        }
        int ailmentNotables = 0;
        for (int i = 734; i < ailmentEnd; i++) {
            final PassiveNode node = ordered.get(i);
            assertFalse(node.isSocket() || node.isStart(), "第 " + i + " 個節點應該是異常狀態群組的");
            if (node.type() == PassiveNode.Type.NOTABLE) {
                ailmentNotables++; // 2026-10 第 3 版：這 16 個核心改成照 PoE2 的各種核心，不再限定異常狀態
            }
        }
        assertEquals(16, ailmentNotables, "原本異常狀態群組的 16 個核心");
        // 暴擊的群組（2026-10）：法術暴擊（元素法師、混沌術士）、攻擊暴擊（弓箭手、長矛獵手）；小點只對法術／攻擊有效
        // （第 3 版：群組的核心改成照 PoE2 的一般核心，只看小點）
        int spellCrit = 0;
        int attackCrit = 0;
        for (int i = ailmentEnd; i < critEnd; i++) {
            final PassiveNode node = ordered.get(i);
            assertFalse(node.isSocket() || node.isStart(), "第 " + i + " 個節點應該是暴擊群組的");
            assertTrue(Math.abs(node.x()) <= 21 && Math.abs(node.y()) <= 21, node.id() + "：不超出原本的範圍");
            if (node.type() == PassiveNode.Type.NOTABLE) {
                continue;
            }
            for (final tw.exilecore.stats.Modifier modifier : node.modifiers()) {
                assertTrue(modifier.stat().equals(tw.exilecore.stats.StatKeys.CRIT_CHANCE)
                        || modifier.stat().equals(tw.exilecore.stats.StatKeys.CRIT_MULTI), node.id() + "：暴擊群組只有暴擊");
                if (modifier.tags().contains(tw.exilecore.stats.Tags.SPELL)) {
                    spellCrit++;
                } else {
                    assertTrue(modifier.tags().contains(tw.exilecore.stats.Tags.ATTACK), node.id() + "：要嘛法術、要嘛攻擊");
                    attackCrit++;
                }
            }
        }
        assertEquals(8, spellCrit, "兩組法術暴擊：8 個小點");
        assertEquals(8, attackCrit);
        // 原本戰星的三個核心（2026-10）：掛在旁邊的葉子（不會變成新的捷徑）。
        // 第 3 版改成照 PoE2 的一般核心，戰星的機率移到別的核心（星馳、麻木、精算）上
        for (int i = critEnd; i < chargeEnd; i++) {
            final PassiveNode node = ordered.get(i);
            assertEquals(PassiveNode.Type.NOTABLE, node.type(), node.id());
            assertEquals(1, tree.neighbours(node).size(), node.id() + " 是葉子");
            assertEquals(node.modifiers().size(), node.description().size(), node.id() + "：每條效果一行說明");
        }
        // 怒星、銳星、磐星在樹上都拿得到
        final java.util.Set<String> charges = new java.util.HashSet<>();
        for (final PassiveNode node : tree.nodes()) {
            for (final tw.exilecore.stats.Modifier modifier : node.modifiers()) {
                if (tw.exilecore.charge.ChargeText.isChargeStat(modifier.stat())) {
                    charges.add(tw.exilecore.charge.ChargeText.chargeOf(modifier.stat()));
                }
            }
        }
        assertEquals(java.util.Set.of("power", "frenzy", "endurance"), charges);
    }

    @Test
    void allocationMustBeAdjacentAndRefundMustKeepConnectivity() {
        final PassiveNode start = tree.start("witch");
        final Set<String> allocated = new HashSet<>();
        final PassiveNode center = tree.at(0, 0);
        assertNotNull(center);
        final PassiveNode first = closestToCenter(tree.neighbours(start), allocated, start);
        assertNotNull(first);
        assertTrue(tree.canAllocate(allocated, start, first));

        // 不相鄰的節點不能配
        final PassiveNode far = tree.start("marauder");
        assertFalse(tree.canAllocate(allocated, start, far), "起點不能配");
        assertFalse(tree.canAllocate(allocated, start, center), "中心離起點很遠");

        // 沿著混沌術士支線一路配到中心（每步挑離中心最近的鄰居）
        allocated.add(first.id());
        PassiveNode current = first;
        int steps = 0;
        while (!current.equals(center) && steps < 30) {
            final PassiveNode chosen = closestToCenter(tree.neighbours(current), allocated, start);
            assertNotNull(chosen, "從 " + current.id() + " 走不到中心");
            assertTrue(manhattan(chosen) < manhattan(current), "要越走越近：" + current.id() + " → " + chosen.id());
            assertTrue(tree.canAllocate(allocated, start, chosen));
            allocated.add(chosen.id());
            current = chosen;
            steps++;
        }
        assertTrue(allocated.contains(center.id()));

        // 中間的節點退掉會斷線，最外面的可以退
        assertFalse(tree.canRefund(allocated, start, first), "退掉第一格會讓後面全部斷線");
        assertTrue(tree.canRefund(allocated, start, center), "最尾端可以退");
        assertEquals(allocated.size(), tree.reachable(allocated, start).size());

        // 樹改版：塞一個連不到的節點進去，prune 要把它清掉
        allocated.add(tree.neighbours(far).get(0).id());
        final Set<String> removed = tree.prune(allocated, start);
        assertEquals(1, removed.size());
    }

    @Test
    void shortestPathAndSearch() {
        final PassiveNode start = tree.start("marauder");
        final Set<String> allocated = new HashSet<>();
        final PassiveNode center = tree.at(0, 0);
        final List<PassiveNode> path = tree.pathTo(allocated, start, center);
        assertFalse(path.isEmpty());
        assertEquals(center, path.get(path.size() - 1));
        // 路徑上每一步都與前一步（或起點）相鄰，而且是最短的（曼哈頓距離 = 起點到中心）
        PassiveNode previous = start;
        for (final PassiveNode step : path) {
            assertEquals(1, Math.abs(step.x() - previous.x()) + Math.abs(step.y() - previous.y()), step.id() + " 沒貼著上一步");
            assertTrue(tree.canAllocate(allocated, start, step));
            allocated.add(step.id());
            previous = step;
        }
        assertEquals(distance(start, center), path.size(), "自動配路是最短的路");
        assertTrue(path.size() >= Math.abs(start.x()) + Math.abs(start.y()));
        // 已配置的節點不會再出現在路徑裡；已配置的目標回傳空
        assertTrue(tree.pathTo(allocated, start, center).isEmpty());
        final PassiveNode witchStart = tree.start("witch");
        assertTrue(tree.pathTo(allocated, start, witchStart).isEmpty(), "起點不能當目標");

        final List<PassiveNode> life = tree.search("生命");
        assertTrue(life.size() >= 10, "生命相關節點 " + life.size());
        assertTrue(life.get(0).type() != PassiveNode.Type.SMALL, "核心與基石排前面");
        assertTrue(tree.search("不存在的字").isEmpty());
        assertTrue(tree.search("  ").isEmpty());
    }

    /** 從起點出發（不經過其他起點）走到目標要幾步。 */
    private static int distance(final PassiveNode from, final PassiveNode to) {
        final java.util.Map<String, Integer> steps = new java.util.HashMap<>();
        final java.util.ArrayDeque<PassiveNode> queue = new java.util.ArrayDeque<>();
        steps.put(from.id(), 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            final PassiveNode current = queue.poll();
            if (current.equals(to)) {
                return steps.get(current.id());
            }
            for (final PassiveNode next : tree.neighbours(current)) {
                if (!next.isStart() && !steps.containsKey(next.id())) {
                    steps.put(next.id(), steps.get(current.id()) + 1);
                    queue.add(next);
                }
            }
        }
        return -1;
    }

    private static int manhattan(final PassiveNode node) {
        return Math.abs(node.x()) + Math.abs(node.y());
    }

    private static PassiveNode closestToCenter(final List<PassiveNode> candidates, final Set<String> allocated, final PassiveNode start) {
        PassiveNode best = null;
        for (final PassiveNode node : candidates) {
            if (node.isStart() || allocated.contains(node.id())) {
                continue;
            }
            if (best == null || manhattan(node) < manhattan(best)) {
                best = node;
            }
        }
        return best;
    }

    @Test
    void modifiersFeedTheStatSheet() {
        // 2026-10：中心是「星幕」（增加 15% 格擋率）；力量、生命改看「崩山」（+15 力量）與「獸魂共鳴」（增加 5% 最大生命）
        final PassiveNode center = tree.at(0, 0);
        final PassiveNode mountain = tree.at(-16, 17);
        final PassiveNode beast = tree.at(-8, 0);
        assertEquals("星幕", center.name());
        assertEquals("崩山", mountain.name());
        assertEquals("獸魂共鳴", beast.name());
        final StatSheet sheet = new StatSheet();
        sheet.setBase(StatKeys.STR, 20);
        sheet.setBase(StatKeys.LIFE_MAX, 100);
        sheet.setBase(StatKeys.BLOCK_CHANCE, 20);
        final List<Modifier> modifiers = tree.modifiers(Set.of(center.id(), mountain.id(), beast.id()));
        sheet.addAll(modifiers);
        assertEquals(35, sheet.get(StatKeys.STR), 1e-9);
        assertEquals(105, sheet.get(StatKeys.LIFE_MAX), 1e-9);
        assertEquals(23, sheet.get(StatKeys.BLOCK_CHANCE), 1e-9);

        // 基石是開關（2026-10：「必中」照 PoE2 改成「穩手無鋒」：命中加倍、無法暴擊，不再必中）
        PassiveNode keystone = null;
        for (final PassiveNode node : tree.nodes()) {
            if (node.type() == PassiveNode.Type.KEYSTONE && node.name().equals("穩手無鋒")) {
                keystone = node;
            }
        }
        assertNotNull(keystone);
        final StatSheet flags = new StatSheet();
        flags.addAll(keystone.modifiers());
        assertTrue(StatKeys.flag(flags, StatKeys.FLAG_NO_CRIT));
        assertFalse(StatKeys.flag(flags, StatKeys.FLAG_ALWAYS_HIT));
        assertFalse(StatKeys.flag(flags, StatKeys.FLAG_CHAOS_IMMUNE));
    }
}
