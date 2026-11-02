package tw.exilecore.client.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（D 組）原生畫面的純函式：分段接收、顏色文字、路徑預覽、搜尋、鏡頭、六角格、快取。 */
class UiCoreTest {

    static byte[] deflate(final byte[] raw) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    /** 跟插件一樣切段。 */
    static List<byte[]> frames(final String name, final byte[] raw, final int chunk) {
        final byte[] packed = deflate(raw);
        final long hash = UiWire.hash(raw);
        final int count = Math.max(1, (packed.length + chunk - 1) / chunk);
        final List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final int from = i * chunk;
            final byte[] part = java.util.Arrays.copyOfRange(packed, from, Math.min(packed.length, from + chunk));
            out.add(UiWire.encodeFrame(name, hash, i, count, packed.length, part));
        }
        return out;
    }

    @Test
    void assemblerJoinsChunksInAnyOrder() {
        final StringBuilder json = new StringBuilder("{\"x\":[");
        for (int i = 0; i < 5000; i++) {
            json.append(i == 0 ? "" : ",").append(i * 7919 % 10007);
        }
        json.append("]}");
        final byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);
        final List<byte[]> frames = frames("passive.static", raw, 1000);
        assertTrue(frames.size() > 2);
        final UiWire.Assembler assembler = new UiWire.Assembler();
        UiWire.Blob blob = null;
        for (int i = frames.size() - 1; i >= 0; i--) {
            final UiWire.Blob got = assembler.accept(UiWire.decode(frames.get(i)));
            if (i > 0) {
                assertNull(got, "還沒收齊");
                assertNull(assembler.accept(UiWire.decode(frames.get(i))), "重複的不算");
            } else {
                blob = got;
            }
        }
        assertNotNull(blob);
        assertEquals(json.toString(), blob.text());
        assertEquals(UiWire.hash(raw), blob.hash());
        assertEquals(0, assembler.pending());
    }

    @Test
    void assemblerRestartsOnNewHashAndRejectsBadData() {
        final UiWire.Assembler assembler = new UiWire.Assembler();
        final List<byte[]> old = frames("a.state", "{\"v\":1,\"pad\":\"0123456789012345678901234567890123456789\"}".getBytes(StandardCharsets.UTF_8), 16);
        assembler.accept(UiWire.decode(old.getFirst()));
        final List<byte[]> fresh = frames("a.state", "{\"v\":2}".getBytes(StandardCharsets.UTF_8), 4096);
        assertEquals("{\"v\":2}", assembler.accept(UiWire.decode(fresh.getFirst())).text(), "新的雜湊把舊的丟掉");
        final byte[] bad = UiWire.encodeFrame("b", 1234, 0, 1, 3, new byte[] {1, 2, 3});
        assertNull(assembler.accept(UiWire.decode(bad)), "解不開／雜湊不對的不要");
        assertNull(UiWire.decode(new byte[3]));
        assertEquals(0x00ff00ff00ff00ffL, UiWire.parseHex(UiWire.hex(0x00ff00ff00ff00ffL)));
        assertEquals(-1L, UiWire.parseHex("ffffffffffffffff"));
        assertEquals(0, UiWire.parseHex("zz"));
    }

    @Test
    void actions() {
        final JsonObject action = JsonParser.parseString(new String(UiWire.actionPath("passive", List.of("p1", "p2")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("passive", action.get("t").getAsString());
        assertEquals("alloc", action.get("a").getAsString());
        assertEquals(2, action.getAsJsonArray("path").size());
        assertEquals("p9", JsonParser.parseString(new String(UiWire.actionId("asc", "refund", "p9"), StandardCharsets.UTF_8))
                .getAsJsonObject().get("id").getAsString());
    }

    @Test
    void helloFlags() {
        final byte[] hello = "{\"protocol\":2,\"orbs\":true}".getBytes(StandardCharsets.UTF_8);
        final JsonObject root = JsonParser.parseString(new String(Features.withUi(hello, true, true), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(root.get("treeui").getAsBoolean());
        assertTrue(root.get("atlasui").getAsBoolean());
        assertTrue(root.get("orbs").getAsBoolean(), "別組的旗標留著");
        assertSame(hello, Features.withUi(hello, false, false), "都關掉原封不動");
        final byte[] bad = "nope".getBytes(StandardCharsets.UTF_8);
        assertSame(bad, Features.withUi(bad, true, true));
    }

    @Test
    void richText() {
        assertEquals(List.of(new Rich.Run("塔：", 0xAAAAAA), new Rich.Run("<爬>", 0xF5B942)), Rich.parse("<gray>塔：<#f5b942>\\<爬>"));
        assertEquals("ab", Rich.plain("<bold>a</bold><red>b"));
        assertEquals(List.of(new Rich.Run("x", 0x123456)), Rich.parse("x", 0x123456));
        final List<List<Rich.Run>> wrapped = Rich.wrap(Rich.parse("<red>一二三四<white>五六"), 3, s -> 1);
        assertEquals(2, wrapped.size());
        assertEquals("一二三", wrapped.get(0).get(0).text());
        assertEquals("四", wrapped.get(1).get(0).text());
        assertEquals("五六", wrapped.get(1).get(1).text());
    }

    /**
     * 小樹：  o0(起點) - s1 - s2 - n3
     *                       \- s4 - k5 (k5 也接 n3)
     *         o6（別的職業的起點）- s7 - s2（不能經過 o6）
     */
    static String treeJson() {
        return "{\"kind\":\"passive\",\"unit\":100,\"nodes\":["
                + "{\"i\":\"o0\",\"x\":0,\"y\":0,\"k\":\"o\",\"n\":\"起點\"},"
                + "{\"i\":\"s1\",\"x\":100,\"y\":0,\"k\":\"s\",\"n\":\"生命\",\"d\":[\"+10 最大生命\"]},"
                + "{\"i\":\"s2\",\"x\":200,\"y\":0,\"k\":\"s\",\"n\":\"護甲\",\"d\":[\"增加 10% 護甲\"]},"
                + "{\"i\":\"n3\",\"x\":300,\"y\":0,\"k\":\"n\",\"n\":\"堅毅\",\"d\":[\"增加 30% 最大生命\"]},"
                + "{\"i\":\"s4\",\"x\":200,\"y\":100,\"k\":\"s\",\"n\":\"力量\",\"d\":[\"+10 力量\"]},"
                + "{\"i\":\"k5\",\"x\":300,\"y\":100,\"k\":\"k\",\"n\":\"不屈\",\"d\":[\"生命不會低於 1\"]},"
                + "{\"i\":\"o6\",\"x\":200,\"y\":-200,\"k\":\"o\",\"n\":\"別的起點\"},"
                + "{\"i\":\"s7\",\"x\":200,\"y\":-100,\"k\":\"s\",\"n\":\"小生命\",\"d\":[\"+5 最大生命\"]}"
                + "],\"links\":[0,1, 1,2, 2,3, 1,4, 4,5, 5,3, 6,7, 7,2],"
                + "\"var\":{\"s1\":{\"witch\":{\"n\":\"魔力\",\"d\":[\"+10 最大魔力\"]}}}}";
    }

    static TreeModel model(final String state) {
        final TreeModel.Data data = TreeModel.parseData("passive", 1, treeJson());
        return new TreeModel(data, TreeModel.parseState(JsonParser.parseString(state).getAsJsonObject(), data));
    }

    @Test
    void pathPreview() {
        final TreeModel model = model("{\"start\":\"o0\",\"owned\":[],\"avail\":[\"s1\"],\"points\":5}");
        final TreeModel.Data d = model.data;
        assertEquals(List.of(d.indexOf("s1")), model.path(d.indexOf("s1"), false), "現在就能配：只有自己");
        assertEquals(List.of(d.indexOf("s1"), d.indexOf("s2"), d.indexOf("n3")), model.path(d.indexOf("n3"), false));
        assertEquals(List.of(d.indexOf("s1"), d.indexOf("s4"), d.indexOf("k5")), model.path(d.indexOf("k5"), false));
        assertEquals(List.of(d.indexOf("s1"), d.indexOf("s2"), d.indexOf("s7")), model.path(d.indexOf("s7"), false), "不經過別的職業的起點");
        assertTrue(model.path(d.indexOf("o6"), false).isEmpty(), "起點不能配");
        assertTrue(model.path(d.indexOf("o0"), false).isEmpty());
        assertTrue(model.path(d.indexOf("n3"), true).isEmpty(), "昇華：只有現在能配的");
        assertEquals(3, model.cost(model.path(d.indexOf("n3"), false)));

        final TreeModel owned = model("{\"start\":\"o0\",\"owned\":[\"s1\",\"s4\"],\"avail\":[\"s2\",\"k5\"],\"points\":5}");
        assertEquals(List.of(d.indexOf("s2"), d.indexOf("n3")), owned.path(d.indexOf("n3"), false), "從已配的往外（同樣長的路照順序）");
        assertEquals(TreeModel.NodeState.OWNED, owned.stateOf(d.indexOf("o0"), null), "自己的起點算已配");

        final TreeModel blocked = model("{\"start\":\"o0\",\"owned\":[],\"avail\":[\"s1\"],\"locked\":[\"s2\"],\"hidden\":[\"s4\"],\"points\":5}");
        assertTrue(blocked.path(d.indexOf("n3"), false).isEmpty(), "被擋住、藏起來的都不能走");
        assertTrue(blocked.path(d.indexOf("s2"), false).isEmpty());
        assertFalse(blocked.visible(d.indexOf("s4")));
    }

    @Test
    void costWeightedPath() {
        // 星圖天賦：照點數走（貴的路不走）
        final String json = "{\"kind\":\"atlastree\",\"nodes\":["
                + "{\"i\":\"@root\",\"x\":0,\"y\":0,\"k\":\"o\",\"c\":0},"
                + "{\"i\":\"a\",\"x\":1,\"y\":0,\"c\":5},{\"i\":\"b\",\"x\":0,\"y\":1,\"c\":1},{\"i\":\"c\",\"x\":1,\"y\":1,\"c\":1},"
                + "{\"i\":\"t\",\"x\":2,\"y\":1,\"c\":2}],\"links\":[0,1, 1,4, 0,2, 2,3, 3,4]}";
        final TreeModel.Data data = TreeModel.parseData("atlastree", 2, json);
        final TreeModel model = new TreeModel(data, TreeModel.parseState(JsonParser.parseString(
                "{\"start\":\"@root\",\"owned\":[],\"avail\":[\"b\"],\"poor\":[\"a\"],\"points\":3}").getAsJsonObject(), data));
        final List<Integer> path = model.path(data.indexOf("t"), false);
        assertEquals(List.of(data.indexOf("b"), data.indexOf("c"), data.indexOf("t")), path, "走便宜的那條");
        assertEquals(4, model.cost(path));
        assertEquals(TreeModel.NodeState.POOR, model.stateOf(data.indexOf("a"), null));
    }

    @Test
    void searchAndVariants() {
        final TreeModel model = model("{\"start\":\"o0\",\"class\":\"witch\",\"owned\":[],\"avail\":[\"s1\"],\"points\":5,"
                + "\"over\":{\"s2\":{\"n\":\"征服的護甲\",\"d\":[\"<#c48bff>增加 20% 最大生命\"]}}}");
        final TreeModel.Data d = model.data;
        assertEquals("魔力", model.name(d.indexOf("s1")), "職業變體");
        assertEquals("征服的護甲", model.name(d.indexOf("s2")), "征服換掉名稱");
        final List<Integer> hits = model.search("最大生命");
        assertEquals(d.indexOf("n3"), hits.getFirst(), "核心排前面");
        assertTrue(hits.contains(d.indexOf("s2")), "換掉的說明也搜得到");
        assertFalse(hits.contains(d.indexOf("s1")), "變體不是生命了");
        assertTrue(model.search("  ").isEmpty());
        assertEquals(d.indexOf("k5"), model.search("不屈").getFirst());
        assertEquals(d.indexOf("n3"), model.nearest(290, 5, 50));
        assertEquals(-1, model.nearest(1000, 1000, 50));
    }

    @Test
    void tooltipLines() {
        final TreeModel model = model("{\"start\":\"o0\",\"owned\":[\"s1\"],\"avail\":[\"s2\",\"s4\"],\"fixed\":[],\"points\":2,"
                + "\"refund\":\"<yellow>右鍵退點（免費）\"}");
        final TreePainter.View view = new TreePainter.View();
        final int n3 = model.data.indexOf("n3");
        view.hover = n3;
        view.setPath(model.path(n3, false));
        final List<String> lines = TreePainter.tooltip(model, n3, view);
        assertTrue(lines.getLast().contains("共 2 點"), lines.toString());
        assertTrue(TreePainter.tooltip(model, model.data.indexOf("s1"), new TreePainter.View()).getLast().contains("右鍵退點"));
        assertTrue(TreePainter.tooltip(model, model.data.indexOf("s2"), new TreePainter.View()).getLast().contains("左鍵配置"));
    }

    @Test
    void guards() {
        final TreeModel paid = model("{\"start\":\"o0\",\"owned\":[\"s1\",\"s2\"],\"avail\":[\"n3\",\"s4\"],\"points\":9,"
                + "\"rcost\":3200,\"gold\":1000}");
        final int s2 = paid.data.indexOf("s2");
        final TreeGuard.Pending refund = TreeGuard.refund(paid, s2);
        assertNotNull(refund, "要花金幣：先確認");
        assertTrue(refund.refund());
        assertTrue(String.join("|", refund.lines()).contains("3,200"), refund.lines().toString());
        assertTrue(String.join("|", refund.lines()).contains("1,000"));
        assertTrue(String.join("|", refund.lines()).contains("金幣不夠"));
        assertTrue(String.join("|", refund.lines()).contains("護甲"), "寫退哪個節點");
        final TreeModel free = model("{\"start\":\"o0\",\"owned\":[\"s1\"],\"points\":9,\"rcost\":0,\"gold\":10}");
        assertNull(TreeGuard.refund(free, free.data.indexOf("s1")), "免費的不用確認");
        assertTrue(TreeGuard.pathNeedsConfirm(5, 5));
        assertFalse(TreeGuard.pathNeedsConfirm(4, 5));
        assertFalse(TreeGuard.pathNeedsConfirm(50, 0), "0 = 不確認");
        final TreeGuard.Pending path = TreeGuard.path(paid, paid.data.indexOf("k5"), List.of(paid.data.indexOf("s4"), paid.data.indexOf("k5")), 2);
        assertFalse(path.refund());
        assertTrue(path.lines().get(path.lines().size() - 1).contains("點別的地方"), "移開滑鼠不再取消（確認框的按鈕點得到）");
        final double[] rects = {0, 0, 100, 50, 10, 30, 40, 48, 60, 30, 90, 48};
        assertTrue(UiPanels.inside(rects, 1, 20, 35));
        assertFalse(UiPanels.inside(rects, 2, 20, 35));
        assertFalse(UiPanels.inside(null, 0, 1, 1));
    }

    @Test
    void summaryInTooltip() {
        final TreeModel model = model("{\"start\":\"o0\",\"owned\":[],\"avail\":[\"s1\"],\"points\":5}");
        final int n3 = model.data.indexOf("n3");
        final TreePainter.View view = new TreePainter.View();
        view.hover = n3;
        view.setPath(model.path(n3, false));
        view.summary = null;
        assertTrue(TreePainter.tooltip(model, n3, view).getLast().contains("計算中"));
        view.summary = List.of("<#77aaff>整條路加起來：", "<white>　+10 最大生命");
        final List<String> lines = TreePainter.tooltip(model, n3, view);
        assertEquals("<white>　+10 最大生命", lines.getLast());
        view.summary = List.of();
        assertTrue(TreePainter.tooltip(model, n3, view).getLast().contains("整條路配上"), "沒有能力可以加總就不寫");
    }

    @Test
    void camera() {
        final Camera camera = new Camera(0, 0, 1);
        camera.viewport(0, 0, 200, 100);
        assertEquals(100, camera.toScreenX(0));
        assertEquals(50, camera.toScreenY(0));
        camera.zoomAt(150, 50, 2);
        assertEquals(50, camera.toWorldX(150), 1e-9, "縮放時滑鼠指著的地方不動");
        assertEquals(2, camera.scale, 1e-9);
        camera.pan(20, 0);
        assertEquals(40, camera.toWorldX(150), 1e-9);
        camera.maxScale = 3;
        camera.zoomAt(0, 0, 100);
        assertEquals(3, camera.scale, 1e-9, "夾在上限");
        final double scale = camera.fit(-100, -50, 100, 50, 200, 100, 0);
        assertEquals(1, scale, 1e-9);
        assertEquals(0, camera.cx, 1e-9);
        camera.center(999, 0);
        camera.keepInside(-10, -10, 10, 10);
        assertEquals(10, camera.cx, 1e-9);
    }

    @Test
    void hexGrid() {
        for (int q = -5; q <= 5; q++) {
            for (int r = -5; r <= 5; r++) {
                final int[] back = HexGrid.at(HexGrid.x(q, r), HexGrid.y(q, r));
                assertEquals(q, back[0]);
                assertEquals(r, back[1]);
                final int[] near = HexGrid.at(HexGrid.x(q, r) + 0.4, HexGrid.y(q, r) - 0.4);
                assertEquals(q, near[0], "格子裡面的點");
                assertEquals(r, near[1]);
            }
        }
        // 鄰居的中心距離都是 √3（真正的六角格）
        for (final int[] d : HexGrid.DIRECTIONS) {
            assertEquals(Math.sqrt(3), Math.hypot(HexGrid.x(d[0], d[1]), HexGrid.y(d[0], d[1])), 1e-9);
        }
        final double[] east = HexGrid.edge(0, 1);
        assertEquals(Math.sqrt(3) / 2, east[0], 1e-9, "往東的邊在 x = √3/2");
        assertEquals(Math.sqrt(3) / 2, east[2], 1e-9);
        assertEquals("(0, 0)", HexGrid.where(0, 0));
        assertEquals("(0, -1)", HexGrid.where(0, 1), "跟箱子選單的座標一樣（AtlasMenu.where）");
        assertEquals("(2, -2)", HexGrid.where(1, 2));
        assertEquals(2, HexGrid.distance(1, 1));
    }

    @Test
    void atlasModelBorders() {
        final AtlasModel model = new AtlasModel(JsonParser.parseString("{\"cells\":["
                + "{\"q\":0,\"r\":0,\"k\":\"camp\",\"done\":true},"
                + "{\"q\":1,\"r\":0,\"k\":\"tower\",\"n\":\"塔\",\"l\":[\"<gray>爬到頂\"]},"
                + "{\"q\":2,\"r\":0,\"k\":\"map\",\"t\":3,\"ring\":\"range\"},"
                + "{\"q\":1,\"r\":1,\"k\":\"fog\"}],\"focus\":[1,0],\"open\":true,\"towers\":[[1,0]],\"radius\":1}").getAsJsonObject());
        final AtlasModel.Cell tower = model.at(1, 0);
        assertTrue(model.inRange(2, 0));
        assertTrue(model.inRange(1, -1), "沒送的空格也算在範圍內");
        assertFalse(model.inRange(0, 0), "營地不算");
        assertFalse(model.border(1, 0, 0), "東邊的鄰居也在範圍內");
        assertTrue(model.border(1, 0, 3), "西邊（營地）不在");
        assertTrue(model.border(2, 0, 0), "範圍的邊");
        assertEquals(6, model.rangeCells().size(), "半徑 1 的 7 格扣掉營地");
        assertTrue(AtlasModel.linked(model.at(0, 0), tower));
        assertFalse(AtlasModel.linked(tower, model.at(1, 1)), "迷霧不連");
        assertEquals(List.of("塔", "<gray>爬到頂"), AtlasPainter.tooltip(tower));
        assertSame(tower, AtlasPainter.at(model, HexGrid.x(1, 0) + 0.2, HexGrid.y(1, 0)));
        assertEquals(1, model.focus[0]);
        assertTrue(model.open);
    }

    @Test
    void cache(@TempDir final Path folder) throws Exception {
        final byte[] raw = treeJson().getBytes(StandardCharsets.UTF_8);
        final long hash = UiWire.hash(raw);
        final TreeCache cache = new TreeCache(folder);
        assertNull(cache.get("passive", hash));
        assertNotNull(cache.put("passive", hash, raw));
        assertSame(cache.get("passive", hash), cache.get("passive", hash), "記憶體裡的");
        final TreeCache reopened = new TreeCache(folder);
        assertEquals(8, reopened.get("passive", hash).size(), "下次開遊戲從磁碟讀");
        assertNull(reopened.get("passive", hash + 1), "雜湊不對");
        final byte[] other = "{\"nodes\":[]}".getBytes(StandardCharsets.UTF_8);
        reopened.put("passive", UiWire.hash(other), other);
        try (var files = Files.list(folder)) {
            assertEquals(1, files.count(), "同一種只留最新的");
        }
        assertNull(reopened.put("../x", 1, raw), "種類的名字只能是英數字");
        Files.writeString(folder.resolve(TreeCache.fileName("atlastree", 5)), "{}");
        assertNull(new TreeCache(folder).get("atlastree", 5), "內容跟雜湊對不上的不用");
    }
}
