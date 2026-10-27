package tw.exilecore.passive;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 規劃代碼、匯入規劃、說明加總、畫面狀態（用真的 tree.json）。 */
class TreePlanningTest {

    static PassiveTree tree;
    static PassiveNode witch;

    @BeforeAll
    static void load() throws IOException {
        tree = PassiveTree.load(Path.of("src/main/resources/data/passives/tree.json"));
        witch = tree.start("witch");
    }

    /** 從起點往外走 count 步（每次挑第一個能配的鄰居），模擬玩家配的點。 */
    static Set<String> walk(final PassiveNode start, final int count) {
        final Set<String> allocated = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            PassiveNode next = null;
            for (final PassiveNode node : tree.ordered()) {
                if (tree.canAllocate(allocated, start, node)) {
                    next = node;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            allocated.add(next.id());
        }
        return allocated;
    }

    // ---- 代碼 ----

    @Test
    void codeRoundTrips() throws Exception {
        final Set<String> allocated = walk(witch, 25);
        final String code = TreeCode.encode(tree, "witch", allocated);
        assertTrue(code.startsWith("EC1." + tree.version() + ".witch."), code);
        assertTrue(code.length() < 80, "代碼要短到能貼進聊天欄：" + code.length());
        final TreeCode.Decoded decoded = TreeCode.decode(tree, code);
        assertEquals("witch", decoded.classId());
        assertEquals(allocated, decoded.nodes());
        // 網址也能直接貼
        assertEquals(allocated, TreeCode.decode(tree, "http://localhost:25580/tree#" + code).nodes());
        // 空的規劃
        assertTrue(TreeCode.decode(tree, TreeCode.encode(tree, "ranger", Set.of())).nodes().isEmpty());
    }

    @Test
    void attributeChoicesTravelInTheCode() throws Exception {
        // 2026-10：屬性點（+5 任一屬性）選的力量／敏捷／智慧接在代碼後面一段；沒選的不佔位置
        final Set<String> allocated = walk(witch, 40);
        final java.util.List<String> attributeNodes = new java.util.ArrayList<>();
        for (final PassiveNode node : tree.ordered()) {
            if (allocated.contains(node.id()) && node.attributeChoice() > 0) {
                attributeNodes.add(node.id());
            }
        }
        assertTrue(attributeNodes.size() >= 6, "走 40 步至少經過幾個屬性點：" + attributeNodes.size());
        assertEquals(5, tree.node(attributeNodes.getFirst()).attributeChoice());
        // 都沒選：跟以前的代碼一模一樣（四段）
        final String plain = TreeCode.encode(tree, "witch", allocated);
        assertEquals(plain, TreeCode.encode(tree, "witch", allocated, java.util.Map.of()));
        assertEquals(4, plain.split("\\.").length);
        assertTrue(TreeCode.decode(tree, plain).attributes().isEmpty());
        // 選了幾個：多一段，解回來一樣；沒選的不在裡面
        final java.util.Map<String, String> chosen = new java.util.LinkedHashMap<>();
        chosen.put(attributeNodes.get(0), "dex");
        chosen.put(attributeNodes.get(2), "int");
        chosen.put(attributeNodes.get(5), "str");
        final String code = TreeCode.encode(tree, "witch", allocated, chosen);
        assertTrue(code.startsWith(plain + "."), code);
        final TreeCode.Decoded decoded = TreeCode.decode(tree, code);
        assertEquals(allocated, decoded.nodes());
        assertEquals(chosen, decoded.attributes());
        assertEquals(chosen, TreeCode.decode(tree, "http://localhost:25580/tree#" + code).attributes(), "網址也能直接貼");
        // 不是屬性點的、沒配的、亂寫的值：不算
        final java.util.Map<String, String> noise = new java.util.LinkedHashMap<>(chosen);
        noise.put("no_such_node", "dex");
        noise.put(attributeNodes.get(1), "luck");
        assertEquals(code, TreeCode.encode(tree, "witch", allocated, noise));
        // 全部都選：每個屬性點都帶得回來
        final java.util.Map<String, String> all = new java.util.LinkedHashMap<>();
        for (int i = 0; i < attributeNodes.size(); i++) {
            all.put(attributeNodes.get(i), new String[] {"str", "dex", "int"}[i % 3]);
        }
        assertEquals(all, TreeCode.decode(tree, TreeCode.encode(tree, "witch", allocated, all)).attributes());
        // 壞掉的屬性段
        assertThrows(TreeCode.InvalidCode.class, () -> TreeCode.decode(tree, plain + ".$$$"), "壞掉的 base64");
        assertThrows(TreeCode.InvalidCode.class, () -> TreeCode.decode(tree, plain + "." + "A".repeat(400)), "比屬性點還多");
        assertThrows(TreeCode.InvalidCode.class, () -> TreeCode.decode(tree, code + ".AA"), "六段");
    }

    @Test
    void startNodesAreNeverInTheCode() throws Exception {
        final Set<String> withStart = new HashSet<>(walk(witch, 3));
        withStart.add(witch.id());
        final TreeCode.Decoded decoded = TreeCode.decode(tree, TreeCode.encode(tree, "witch", withStart));
        assertFalse(decoded.nodes().contains(witch.id()));
        assertEquals(3, decoded.nodes().size());
    }

    @Test
    void badCodesAreRejectedWithAReason() {
        assertThrows(TreeCode.InvalidCode.class, () -> TreeCode.decode(tree, "hello"));
        assertThrows(TreeCode.InvalidCode.class, () -> TreeCode.decode(tree, "EC1.999.witch.AA"), "版本不同");
        assertThrows(TreeCode.InvalidCode.class, () -> TreeCode.decode(tree, "EC1." + tree.version() + ".paladin.AA"), "沒有這個職業");
        assertThrows(TreeCode.InvalidCode.class, () -> TreeCode.decode(tree, "EC1." + tree.version() + ".witch.$$$"), "壞掉的 base64");
        final String tooLong = "EC1." + tree.version() + ".witch." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[tree.size() / 8 + 4]);
        assertThrows(TreeCode.InvalidCode.class, () -> TreeCode.decode(tree, tooLong), "比樹還長");
    }

    // ---- 匯入 ----

    @Test
    void importAddsAlongThePlanUntilPointsRunOut() throws Exception {
        final Set<String> plan = walk(witch, 20);
        final TreePlan.Plan result = TreePlan.compute(tree, witch, Set.of(), plan, 8);
        assertEquals(8, result.added().size());
        assertEquals(8, result.result().size());
        assertEquals(12, result.later().size());
        assertTrue(result.removed().isEmpty());
        assertEquals(result.result().size(), tree.reachable(result.result(), witch).size(), "配完一定連得到起點");
        // 照離起點近的先配：第一個一定跟起點相鄰
        assertTrue(tree.neighbours(result.added().getFirst()).contains(witch));
    }

    @Test
    void importKeepsWhatYouHaveAndRefundsTheRest() throws Exception {
        final Set<String> plan = walk(witch, 12);
        final Set<String> current = new LinkedHashSet<>(walk(witch, 6));
        // 目前多配了一個規劃以外、但連得上的節點
        PassiveNode extra = null;
        for (final PassiveNode node : tree.ordered()) {
            if (!plan.contains(node.id()) && tree.canAllocate(current, witch, node)) {
                extra = node;
                break;
            }
        }
        if (extra != null) {
            current.add(extra.id());
        }
        final TreePlan.Plan result = TreePlan.compute(tree, witch, current, plan, 2);
        for (final String id : current) {
            if (plan.contains(id)) {
                assertTrue(result.result().contains(id), "規劃裡已經配的要保留：" + id);
            }
        }
        if (extra != null) {
            assertTrue(result.removed().contains(extra), "規劃以外的要退掉");
            // 退掉的那一點拿回來用：2 ＋ 1 點
            assertEquals(3, result.added().size());
        }
        assertEquals(result.result().size(), tree.reachable(result.result(), witch).size());
        assertTrue(result.changes());
    }

    @Test
    void plannedNodesThatGetCutOffAreRefundedAndSpent() throws Exception {
        // 2026-10-26：主天賦樹換成 PoE2 的樹（明確連線）之後，這個測試改用舊的第 3 版格子樹（測試資源）驗 TreePlan 的退點／重配邏輯
        final PassiveTree tree = PassiveTree.load(Path.of("src/test/resources/passives/tree_v3_grid.json"));
        final PassiveNode witch = tree.start("witch");
        // 混沌術士的輻條走到內環 (-2,-4)，現在往右繞：(-1,-4)、(0,-4) 再沿中心的輻條往下到 (0,-1)；
        // 規劃改往左繞內環一圈半：(-3,-4)…(-4,0)、(-3,0)…(0,0)，再接回 (0,-1)
        final List<String> prefix = List.of("nm3_m7", "nm3_m6", "nm3_m5", "nm2_m5", "nm2_m4");
        final List<String> tail = List.of("nm1_m4", "n0_m4", "n0_m3", "n0_m2");
        final String end = "n0_m1";
        final List<String> detour = List.of("nm3_m4", "nm4_m4", "nm4_m3", "nm4_m2", "nm4_m1", "nm4_0", "nm3_0", "nm2_0", "nm1_0", "n0_0");
        final Set<String> current = new LinkedHashSet<>(prefix);
        current.addAll(tail);
        current.add(end);
        final Set<String> plan = new LinkedHashSet<>(prefix);
        plan.addAll(detour);
        plan.add(end);
        for (final String id : plan) {
            assertTrue(tree.node(id) != null, "樹改了，這個測試要換節點：" + id);
        }
        assertEquals(current.size(), tree.reachable(current, witch).size());
        assertEquals(plan.size(), tree.reachable(plan, witch).size());
        // 沒有可用的點：退掉右邊四點＋接不回來的 (0,-1) 共五點，五點全部拿來繞路，剩下的等之後
        final TreePlan.Plan result = TreePlan.compute(tree, witch, current, plan, 0);
        assertEquals(5, result.removed().size());
        assertEquals(5, result.added().size(), "退回來的點都要用掉");
        assertEquals(6, result.later().size());
        assertEquals(result.result().size(), tree.reachable(result.result(), witch).size());
        assertTrue(result.added().size() <= result.removed().size());
        // 多 5 點還不夠：配到 (0,0) 會把 (0,-1) 接回來（不退了），等於要 10 點、只有 5＋4 點
        final TreePlan.Plan five = TreePlan.compute(tree, witch, current, plan, 5);
        assertEquals(9, five.added().size());
        assertEquals(5, five.removed().size());
        assertEquals(2, five.later().size());
        // 多 6 點就能整個配完，(0,-1) 保留
        final TreePlan.Plan six = TreePlan.compute(tree, witch, current, plan, 6);
        assertTrue(six.result().contains(end));
        assertTrue(six.later().isEmpty());
        assertEquals(4, six.removed().size());
        assertEquals(10, six.added().size());
    }

    @Test
    void randomImportsAreAlwaysAffordableAndConnected() throws Exception {
        final java.util.Random random = new java.util.Random(42);
        for (int round = 0; round < 300; round++) {
            final Set<String> current = randomTree(random, 5 + random.nextInt(40));
            final Set<String> plan = randomTree(random, 5 + random.nextInt(60));
            final int available = random.nextInt(8);
            final TreePlan.Plan result = TreePlan.compute(tree, witch, current, plan, available);
            assertTrue(result.added().size() <= available + result.removed().size(), "點數不夠還配");
            assertEquals(result.result().size(), tree.reachable(result.result(), witch).size(), "斷掉的樹");
            for (final String id : result.result()) {
                assertTrue(plan.contains(id), "配了規劃以外的");
            }
        }
    }

    /** 從混沌術士起點隨便長 size 點。 */
    static Set<String> randomTree(final java.util.Random random, final int size) {
        final Set<String> allocated = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            final List<PassiveNode> options = new java.util.ArrayList<>();
            for (final PassiveNode node : tree.ordered()) {
                if (tree.canAllocate(allocated, witch, node)) {
                    options.add(node);
                }
            }
            if (options.isEmpty()) {
                break;
            }
            allocated.add(options.get(random.nextInt(options.size())).id());
        }
        return allocated;
    }

    @Test
    void samePlanMeansNoChanges() throws Exception {
        final Set<String> current = walk(witch, 10);
        final TreePlan.Plan result = TreePlan.compute(tree, witch, current, current, 5);
        assertFalse(result.changes());
        assertTrue(result.later().isEmpty());
    }

    @Test
    void planFromAnotherClassIsRejected() {
        final Set<String> rangerPlan = walk(tree.start("ranger"), 5);
        assertThrows(TreePlan.InvalidPlan.class, () -> TreePlan.compute(tree, witch, Set.of(), rangerPlan, 10));
    }

    // ---- 加總 ----

    @Test
    void summaryAddsNumbersOfTheSameLine() {
        assertEquals(List.of("+30 力量", "增加 14% 最大生命", "增加 1.5% 攻擊速度", "免疫混沌傷害"),
                PassiveSummary.sumLines(List.of("+10 力量", "增加 6% 最大生命", "+20 力量", "增加 8% 最大生命",
                        "增加 0.5% 攻擊速度", "增加 1% 攻擊速度", "免疫混沌傷害", "免疫混沌傷害")));
        assertEquals(List.of("附加 3 到 7 物理傷害"), PassiveSummary.sumLines(List.of("附加 1 到 3 物理傷害", "附加 2 到 4 物理傷害")),
                "一行裡有兩個數字時各自相加");
        assertTrue(PassiveSummary.sum(tree.ordered()).size() > 10);
    }

    // ---- 畫面 ----

    @Test
    void tileStatesFollowTheRules() {
        final Set<String> allocated = walk(witch, 3);
        final TreeView view = new TreeView(tree, witch, allocated, Set.of(), Set.of());
        assertEquals(TreeView.NodeState.OWNED, view.state(witch));
        for (final String id : allocated) {
            assertEquals(TreeView.NodeState.OWNED, view.state(tree.node(id)));
        }
        int available = 0;
        for (final PassiveNode node : tree.ordered()) {
            if (view.state(node) == TreeView.NodeState.AVAILABLE) {
                available++;
                assertTrue(tree.canAllocate(allocated, witch, node));
            }
        }
        assertTrue(available > 0);
        // 起點的格子：每個相鄰的方向都有線，配了的那一條是金色
        final TreeView.Tile tile = view.tile(witch.x(), witch.y());
        int edges = 0;
        for (final TreeView.EdgeState edge : tile.edges()) {
            if (edge != null) {
                edges++;
            }
        }
        assertEquals(tree.neighbours(witch).size(), edges);
        final PassiveNode first = tree.node(allocated.iterator().next());
        assertEquals(TreeView.EdgeState.OWNED, view.edge(witch, first));
        // 空格沒有節點也沒有線
        final TreeView.Tile empty = view.tile(tree.maxX() + 5, tree.maxY() + 5);
        assertNull(empty.node());
        assertTrue(java.util.Arrays.stream(empty.edges()).allMatch(java.util.Objects::isNull));
    }

    @Test
    void regionsCoverEveryNodeOnce() {
        final TreeView view = new TreeView(tree, witch, Set.of(), Set.of(), Set.of());
        final List<TreeView.Region> regions = view.regions(9, 5);
        assertTrue(regions.size() <= 45);
        final Set<String> seen = new HashSet<>();
        for (final TreeView.Region region : regions) {
            assertTrue(region.column() < 9 && region.row() < 5);
            for (final PassiveNode node : region.nodes()) {
                assertTrue(seen.add(node.id()), "一個節點只在一區：" + node.id());
                assertTrue(region.contains(node.x(), node.y()));
            }
        }
        assertEquals(tree.size(), seen.size(), "每個節點都在某一區");
        // 起點所在的區，地標是自己的起點
        for (final TreeView.Region region : regions) {
            if (region.contains(witch.x(), witch.y())) {
                assertEquals(witch, region.landmark());
            }
        }
    }
}
