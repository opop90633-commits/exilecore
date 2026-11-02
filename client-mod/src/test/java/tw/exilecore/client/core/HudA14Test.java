package tw.exilecore.client.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 模組 1.4（A 組）：招呼旗標、hud 新欄位、提示列、面板、異常圖示、每小時經驗、守護環。 */
class HudA14Test {

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void helloFlags() {
        final JsonObject root = JsonParser.parseString(new String(Protocol.hello(2, "1.4.0", true, true, true, true,
                Set.of(Protocol.FLAG_PANELS, Protocol.FLAG_NOTICE)), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(root.get("notice").getAsBoolean());
        assertTrue(root.get("panels").getAsBoolean());
        assertFalse(root.has("res"), "沒說的不寫");
        assertTrue(root.get("orbs").getAsBoolean());
        final JsonObject old = JsonParser.parseString(new String(Protocol.hello(2, "1.4.0", true, true, true, false),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(old.has("notice"), "舊的寫法：沒有旗標");
    }

    @Test
    void parseNewFields() {
        final HudData data = HudData.parse(bytes("{\"v\":1,\"t\":5,\"slots\":[],\"buffs\":[],"
                + "\"vit\":{\"hp\":10,\"mhp\":20,\"mp\":1,\"mmp\":2,\"st\":[{\"t\":\"燃 2.5s  \",\"c\":16742938},{\"t\":\"寒 30%\",\"c\":10475263}],"
                + "\"gd\":240,\"sf\":1,\"lv\":31,\"xp\":2500,\"xpn\":10000},"
                + "\"res\":{\"rg\":12,\"rgm\":30,\"rgd\":1,\"ch\":[{\"n\":\"暴擊球\",\"s\":\"✦\",\"c\":8377599,\"k\":2,\"m\":3}],\"o\":[{\"t\":\"連擊 5\",\"c\":1}]},"
                + "\"auras\":[\"憤怒\",\"純淨\"],\"pets\":[{\"n\":\"骷髏\",\"k\":3,\"m\":4}],\"loot\":[{\"n\":\"崇高石\",\"k\":2,\"c\":16777215,\"l\":100,\"t\":120}]}"));
        assertNotNull(data);
        assertEquals(240, data.self().guard());
        assertTrue(data.self().safe());
        assertEquals(31, data.self().level());
        assertEquals(0.25, data.self().progress(), 1e-9);
        assertEquals(2, data.self().status().size());
        assertEquals(12, data.res().rage());
        assertTrue(data.res().decaying());
        assertEquals(3, data.res().charges().getFirst().max());
        assertEquals(List.of("憤怒", "純淨"), data.sides().auras());
        assertEquals(4, data.sides().pets().getFirst().limit());
        assertEquals(80, data.sides().loot().getFirst().left(20));
        // 舊插件：什麼都沒有
        final HudData old = HudData.parse(bytes("{\"v\":1,\"t\":5,\"slots\":[],\"buffs\":[],\"vit\":{\"hp\":10,\"mhp\":20}}"));
        assertEquals(HudData.Self.NONE.level(), old.self().level());
        assertTrue(old.res().isEmpty());
        assertTrue(old.sides().auras().isEmpty());
    }

    @Test
    void targetInvulnerable() {
        final String base = "{\"v\":1,\"t\":5,\"slots\":[],\"buffs\":[],\"target\":{\"n\":\"王\",\"c\":1,\"r\":\"傳奇\",\"lv\":70,\"hp\":5,\"mhp\":9,\"sh\":-1,\"boss\":1,\"st\":[]";
        assertTrue(HudData.parse(bytes(base + ",\"inv\":1}}")).target().invulnerable());
        assertFalse(HudData.parse(bytes(base + "}}")).target().invulnerable(), "舊插件：沒有 = 打得動");
    }

    @Test
    void gloryAndImportant() {
        final HudData data = HudData.parse(bytes("{\"v\":1,\"t\":5,\"slots\":[{\"s\":4,\"id\":\"war_cry\",\"n\":\"戰吼\",\"i\":\"minecraft:book\",\"gl\":3,\"gn\":5},"
                + "{\"s\":5,\"id\":\"x\",\"n\":\"x\",\"i\":\"minecraft:book\"}],"
                + "\"buffs\":[{\"n\":\"神龕\",\"l\":900,\"t\":900,\"s\":-1,\"i\":1},{\"n\":\"疾速\",\"l\":40,\"t\":80,\"s\":-1}]}"));
        assertEquals(3, data.slots().getFirst().gloryNow());
        assertEquals(0.6, data.slots().getFirst().gloryFraction(), 1e-9);
        assertFalse(data.slots().getFirst().gloryReady());
        assertEquals(0, data.slots().get(1).gloryNeed(), "不用榮耀的沒有");
        assertTrue(data.buffs().getFirst().important());
        assertFalse(data.buffs().get(1).important());
        assertTrue(new HudData.Slot(0, "a", "a", "", null, true, 0, 0, 0, 1, 0, null, 5, 5).gloryReady());
    }

    @Test
    void sideLayoutColumn() {
        // 一欄放得下：右緣貼著球、從下往上
        final SideLayout.Result one = SideLayout.column(java.util.Collections.nCopies(5, 50), 156, 346, 192, 2);
        assertEquals(1f, one.scale());
        assertEquals(5, one.cells().size());
        assertEquals(346 - 12, one.cells().getFirst().y(), "第一個在最下面");
        assertEquals(156, one.cells().get(4).x());
        // 一欄 12 排、放不下開第二欄
        final SideLayout.Result two = SideLayout.column(java.util.Collections.nCopies(15, 50), 156, 346, 192, 2);
        assertEquals(15, two.cells().size());
        assertEquals(156 - 50 - 4, two.cells().get(12).x(), "第二欄往左");
        // 縮小
        final SideLayout.Result small = SideLayout.column(java.util.Collections.nCopies(12, 50), 76, 256, 147, 2);
        assertEquals(SideLayout.SMALL, small.scale(), "GUI 4：一欄 9 排放不下 12 個，縮小");
        assertEquals(12, small.cells().size());
        assertEquals(0, small.more());
        // 還是放不下：最後一格寫 +N
        final SideLayout.Result more = SideLayout.column(java.util.Collections.nCopies(40, 50), 76, 256, 147, 2);
        assertTrue(more.more() > 0);
        assertEquals(40, more.cells().size() + more.more());
    }

    @Test
    void sideLayoutStrip() {
        final SideLayout.Result fits = SideLayout.strip(List.of(30, 40), 14, 2, 156, 340);
        assertEquals(1f, fits.scale());
        assertEquals(2 + 30 + 4, fits.cells().get(1).x());
        final SideLayout.Result shrink = SideLayout.strip(List.of(30, 40), 14, 2, 70, 340);
        assertEquals(SideLayout.SMALL, shrink.scale(), "放不下先縮小");
        assertEquals(0, shrink.more());
        final SideLayout.Result more = SideLayout.strip(List.of(30, 40, 50), 14, 2, 70, 340);
        assertTrue(more.more() > 0, "還是放不下：+N");
    }

    @Test
    void buffOrder() {
        final int aura = -2;
        final List<HudData.Buff> buffs = List.of(
                new HudData.Buff("猛攻", 400, 400, 0, -1),
                new HudData.Buff("戰吼", 300, 300, 0, 4),
                new HudData.Buff("光環", 999, 999, 0, aura),
                new HudData.Buff("疾速", 60, 80, 0, -1),
                new HudData.Buff("神龕", 900, 900, 0, -1, true),
                new HudData.Buff("過期", 10, 80, 0, -1),
                new HudData.Buff("護符", 100, 200, 0, -1, true));
        final List<String> order = BuffOrder.sorted(buffs, 20, aura).stream().map(HudData.Buff::name).toList();
        assertEquals(List.of("護符", "神龕", "戰吼", "疾速", "猛攻", "光環"), order, "重要的、技能格的、快結束的在前；光環最後；過期的不列");
    }

    @Test
    void segments() {
        final List<List<HudData.Part>> segments = HudData.segments(List.of(new HudData.Part("燃 2.5s  ", 1), new HudData.Part("寒 30%", 2),
                new HudData.Part("  ", 3), new HudData.Part("咒·萎力 ", 4), new HudData.Part("4.0s", 5)));
        assertEquals(3, segments.size());
        assertEquals("燃 2.5s", segments.get(0).getFirst().text());
        assertEquals(2, segments.get(2).size(), "同一段裡換顏色的照樣接著");
        assertTrue(HudData.segments(List.of(new HudData.Part("   ", 1))).isEmpty());
    }

    @Test
    void statusIcons() {
        final List<StatusIcons.Icon> icons = StatusIcons.of(List.of(new HudData.Part("燃 2.5s  ", 0xff7a1a), new HudData.Part("寒 30%  ", 0x9fd6ff),
                new HudData.Part("毒×3 2.1s  ", 0x7fd13b), new HudData.Part("咒·萎力 12.0s  ", 0xc48bff), new HudData.Part("凍結 40%", 0x55ffff)));
        assertEquals(5, icons.size());
        assertEquals("燃", icons.get(0).label());
        assertEquals(2.5, icons.get(0).seconds(), 1e-9);
        assertEquals("1.5", icons.get(0).text(20), "自己倒數");
        assertEquals("0.0", icons.get(0).text(100), "倒數到 0 就停");
        assertTrue(icons.get(0).expired(100));
        assertEquals("30%", icons.get(1).text(100), "百分比不倒數");
        assertFalse(icons.get(1).expired(1000));
        assertEquals("毒", icons.get(2).label());
        assertEquals(3, icons.get(2).stacks());
        assertEquals("萎力", icons.get(3).label());
        assertTrue(icons.get(3).curse());
        assertEquals("12", icons.get(3).text(0), "10 秒以上寫整數");
        assertEquals("凍結", icons.get(4).label());
    }

    private static NoticeFeed.Packet notice(final String text, final long ticks, final int level) {
        return new NoticeFeed.Packet(List.of(new HudData.Part(text, 0xFFFFFF)), ticks, level);
    }

    @Test
    void noticeFeed() {
        final NoticeFeed feed = new NoticeFeed();
        feed.add(notice("+120 金幣", 40, 0), 0);
        feed.add(notice("魔力不足", 60, 1), 1);
        feed.add(notice("委託 2/5", 60, 0), 2);
        assertEquals(3, feed.visible(3, 4).size(), "疊起來");
        feed.add(notice("+80 金幣", 40, 0), 5);
        final List<NoticeFeed.Shown> shown = feed.visible(6, 4);
        assertEquals(3, shown.size(), "同一種的換掉，不往上疊");
        assertEquals("+80 金幣", NoticeFeed.plain(shown.getLast().entry().parts()), "換掉的移到最新");
        assertEquals(1, shown.getLast().alpha(), 1e-9, "換掉的不再淡入");
        feed.add(notice("A", 60, 0), 7);
        feed.add(notice("B", 60, 0), 8);
        assertEquals(NoticeFeed.MAX, feed.size(), "最多 4 則，舊的讓");
        assertEquals(2, feed.visible(9, 2).size(), "畫面矮只畫最新的幾則");
        // 警告停久一點
        assertEquals(96, NoticeFeed.duration(60, 1), 1e-9);
        assertEquals(60, NoticeFeed.duration(60, 0), 1e-9);
        assertEquals(NoticeFeed.MAX_TICKS, NoticeFeed.duration(100000, 0), 1e-9);
        final NoticeFeed warn = new NoticeFeed();
        warn.add(notice("掛機", 60, 1), 0);
        assertEquals(1, warn.visible(80, 4).size(), "一般的 60 tick 就沒了，警告還在");
        assertTrue(warn.visible(100, 4).isEmpty());
    }

    @Test
    void noticeAlpha() {
        assertEquals(0, NoticeFeed.alpha(0, 0, 60), 1e-9);
        assertEquals(1.0 / 3, NoticeFeed.alpha(1, 0, 60), 1e-9);
        assertEquals(1, NoticeFeed.alpha(30, 0, 60), 1e-9);
        assertEquals(0.5, NoticeFeed.alpha(54, 0, 60), 1e-9, "最後 12 tick 淡出");
        assertEquals(0, NoticeFeed.alpha(60, 0, 60), 1e-9);
        assertEquals(NoticeFeed.key("開鎖中：3／10"), NoticeFeed.key("開鎖中：4／10"));
        assertFalse(NoticeFeed.key("魔力不足").equals(NoticeFeed.key("生命不足")));
    }

    @Test
    void noticePacket() {
        final NoticeFeed.Packet packet = NoticeFeed.parse(bytes("{\"v\":1,\"p\":[{\"t\":\"魔力不足\",\"c\":16733525}],\"d\":60,\"lv\":1}"));
        assertEquals(60, packet.ticks());
        assertEquals(1, packet.level());
        assertNull(NoticeFeed.parse(bytes("{\"v\":1,\"p\":[{\"t\":\"  \",\"c\":1}]}")), "空白的不要");
        assertNull(NoticeFeed.parse(bytes("壞掉")));
    }

    private static Panels.Panel panel(final String id, final int lines) {
        final List<List<HudData.Part>> body = new java.util.ArrayList<>();
        for (int i = 0; i < lines; i++) {
            body.add(List.of(new HudData.Part("行 " + i, 0xFFFFFF)));
        }
        return new Panels.Panel(id, List.of(new HudData.Part(id, 0xFFFFFF)), body);
    }

    @Test
    void panelsParseAndMerge() {
        final List<Panels.Panel> server = Panels.parse(bytes("{\"v\":1,\"panels\":[{\"id\":\"quest\",\"t\":[{\"t\":\"任務\",\"c\":1}],"
                + "\"l\":[[{\"t\":\"第一章\",\"c\":2}]]},{\"id\":\"combat\",\"t\":[],\"l\":[]}]}"));
        assertEquals(2, server.size());
        final HudData.Sides sides = new HudData.Sides(List.of(), List.of(new HudData.Pet("骷髏", 3, 4)),
                List.of(new HudData.Loot("崇高石", 2, 0xFFFFFF, 100, 120), new HudData.Loot("過期的", 1, 0xFFFFFF, 10, 120)));
        final List<Panels.Panel> merged = Panels.merge(server, sides, 20);
        assertEquals(List.of("combat", "minions", "quest", "drops"), merged.stream().map(Panels.Panel::id).toList());
        assertEquals("3/4", merged.get(1).lines().getFirst().get(1).text());
        assertEquals(1, merged.get(3).lines().size(), "過期的不列");
        assertNull(Panels.parse(bytes("{}")));
    }

    @Test
    void panelsLayout() {
        final List<Panels.Panel> panels = List.of(panel("combat", 6), panel("quest", 10), panel("drops", 3));
        final List<Panels.Placed> roomy = Panels.layout(panels, 0, 400, false, Set.of());
        assertEquals(6, roomy.get(0).lines());
        assertEquals(0, roomy.get(1).hidden());
        // 擠：上面的先拿，下面的寫「…+N」
        final List<Panels.Placed> tight = Panels.layout(panels, 0, 120, false, Set.of());
        int last = 0;
        for (final Panels.Placed item : tight) {
            assertTrue(item.y() >= last, "不重疊");
            assertTrue(item.y() + item.height() <= 120, "不超過下緣");
            last = item.y() + item.height();
        }
        assertEquals(6, tight.get(0).lines());
        assertTrue(tight.get(1).hidden() > 0);
        // 收起
        final List<Panels.Placed> shut = Panels.layout(panels, 0, 400, false, Set.of("quest"));
        assertTrue(shut.get(1).collapsed());
        assertEquals(Panels.HEADER, shut.get(1).height());
        assertEquals(10, shut.get(1).hidden());
        assertTrue(Panels.layout(panels, 0, 400, true, Set.of()).stream().allMatch(Panels.Placed::collapsed));
    }

    @Test
    void xpRate() {
        final XpRate rate = new XpRate();
        rate.observe(0, 10, 100, 1000);
        assertEquals(-1, rate.perHour(10_000), 1e-9, "看太短不寫");
        rate.observe(30_000, 10, 600, 1000);
        rate.observe(60_000, 11, 200, 2000); // 升級：上一級剩 400 + 這一級 200
        assertEquals(1100 * 60.0, rate.perHour(60_000), 1e-6);
        rate.observe(70_000, 5, 0, 100); // 換角色
        assertEquals(-1, rate.perHour(70_000), 1e-9);
        assertEquals("12.3萬", XpRate.format(123_456));
        assertEquals("1.23億", XpRate.format(123_000_000));
        assertEquals("9,999", XpRate.format(9999));
        assertEquals("42.0%", XpRate.percent(0.42));
    }

    @Test
    void guardRing() {
        assertEquals(0, RingArt.fraction(0, 1000), 1e-9);
        assertEquals(0.24, RingArt.fraction(240, 1000), 1e-9);
        assertEquals(0.06, RingArt.fraction(1, 1000), 1e-9, "有就至少一小段");
        assertEquals(1, RingArt.fraction(5000, 1000), 1e-9);
        final int size = 40;
        final int[] half = RingArt.ring(size, 0.5);
        assertTrue((half[0 * size + size / 2] >>> 24) > 0, "正上方有");
        assertTrue((half[(size / 2) * size + size - 1] >>> 24) > 0, "右邊（順時針四分之一）有");
        assertEquals(0, half[(size / 2) * size] >>> 24, "左邊（四分之三）沒有");
        assertEquals(0, half[(size / 2) * size + size / 2] >>> 24, "中間是空的（球照常看得到）");
    }
}
