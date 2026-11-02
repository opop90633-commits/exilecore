package tw.exilecore.clientmod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 模組 1.4（A 組）：招呼的功能旗標、提示封包、hud 的 vit／res 新欄位、側邊面板、元件拆字段。 */
class HudA14Test {

    @Test
    void helloFlags() {
        final byte[] bytes = "{\"protocol\":2,\"modVersion\":\"1.4.0\",\"hud\":true,\"hearts\":true,\"effects\":true,\"orbs\":true,\"notice\":true,\"panels\":true,\"res\":false,\"status\":true,\"x\":1}"
                .getBytes(StandardCharsets.UTF_8);
        final ClientModProtocol.Hello hello = ClientModProtocol.decodeHello(bytes);
        assertTrue(hello.has(ClientModProtocol.FLAG_NOTICE));
        assertTrue(hello.has(ClientModProtocol.FLAG_PANELS));
        assertTrue(hello.has(ClientModProtocol.FLAG_STATUS));
        assertFalse(hello.has(ClientModProtocol.FLAG_RES), "false 不算");
        assertFalse(hello.has("x"), "不是布林不算");
        assertFalse(hello.has("orbs"), "原本的欄位不算旗標");
        assertTrue(hello.orbs());
        // 寫回去再讀一樣
        final ClientModProtocol.Hello again = ClientModProtocol.decodeHello(ClientModProtocol.encodeHello(hello));
        assertEquals(hello, again);
    }

    @Test
    void oldHelloHasNoFlags() {
        final ClientModProtocol.Hello hello = ClientModProtocol.decodeHello(
                "{\"protocol\":2,\"modVersion\":\"1.3.0\",\"hud\":true,\"hearts\":true,\"effects\":true,\"orbs\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals(Set.of(), hello.flags());
        assertFalse(hello.has(ClientModProtocol.FLAG_NOTICE), "1.3 的模組：照原版動作列");
        assertEquals(new ClientModProtocol.Hello(2, "1.3.0", true, true, true, true), hello);
    }

    @Test
    void noticePacket() {
        final List<MiniParts.Part> parts = MiniParts.parse("<red>魔力不足");
        final JsonObject root = JsonParser.parseString(new String(ClientModProtocol.encodeNotice(parts, 60,
                ClientModProtocol.noticeLevel(parts)), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, root.get("v").getAsInt());
        assertEquals(60, root.get("d").getAsLong());
        assertEquals(1, root.get("lv").getAsInt());
        assertEquals("魔力不足", root.getAsJsonArray("p").get(0).getAsJsonObject().get("t").getAsString());
        final JsonObject info = JsonParser.parseString(new String(ClientModProtocol.encodeNotice(MiniParts.parse("<#f5b942>+120 金幣"), 40, 0),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(info.has("lv"), "一般的不寫等級");
    }

    @Test
    void noticeLevel() {
        assertEquals(1, ClientModProtocol.noticeLevel(MiniParts.parse("<red>先打倒守衛")));
        assertEquals(1, ClientModProtocol.noticeLevel(MiniParts.parse("<#ff5f5f>極境")));
        assertEquals(1, ClientModProtocol.noticeLevel(MiniParts.parse("<dark_red>不行")));
        assertEquals(0, ClientModProtocol.noticeLevel(MiniParts.parse("<#ff7a3a>煉獄之炎 3／5")), "橘色不算警告");
        assertEquals(0, ClientModProtocol.noticeLevel(MiniParts.parse("<gray>入場保護結束")));
        assertEquals(0, ClientModProtocol.noticeLevel(MiniParts.parse("<#f5b942>+120 金幣")));
        assertEquals(1, ClientModProtocol.noticeLevel(List.of(new MiniParts.Part("  ", 0xFFFFFF), new MiniParts.Part("掛機", 0xFF5555))),
                "前面的空白跳過");
    }

    private static HudSnapshot.Vitals vit() {
        return new HudSnapshot.Vitals(800, 1000, 0, 0, 0, 50, 120, 0);
    }

    @Test
    void selfFields() {
        final HudSnapshot snapshot = new HudSnapshot(List.of(), List.of(), null, vit(),
                new HudSnapshot.Self(MiniParts.parse("<#ff7a1a>燃 2.5s  <#9fd6ff>寒 30%"), 240.4, true, 31, 4200, 10000), null);
        final JsonObject vit = JsonParser.parseString(snapshot.toJson(0)).getAsJsonObject().getAsJsonObject("vit");
        final JsonArray st = vit.getAsJsonArray("st");
        assertEquals(2, st.size());
        assertEquals(0xff7a1a, st.get(0).getAsJsonObject().get("c").getAsInt());
        assertEquals(240, vit.get("gd").getAsLong());
        assertEquals(1, vit.get("sf").getAsInt());
        assertEquals(31, vit.get("lv").getAsInt());
        assertEquals(4200, vit.get("xp").getAsLong());
        assertEquals(10000, vit.get("xpn").getAsLong());
        // 沒有的不寫
        final JsonObject bare = JsonParser.parseString(new HudSnapshot(List.of(), List.of(), null, vit(),
                new HudSnapshot.Self(List.of(), 0.5, false, 0, 0, 0), null).toJson(0)).getAsJsonObject().getAsJsonObject("vit");
        assertFalse(bare.has("st"));
        assertFalse(bare.has("gd"), "不到 1 點守護不寫");
        assertFalse(bare.has("sf"));
        assertFalse(bare.has("lv"), "沒有角色不寫");
        assertEquals(800, bare.get("hp").getAsLong(), "原本的欄位照舊");
    }

    @Test
    void resourcesField() {
        final HudSnapshot.Resources res = new HudSnapshot.Resources(12, 30, true,
                List.of(new HudSnapshot.Charge("暴擊球", "✦", 0x7fd4ff, 2, 3)), MiniParts.parse("<#f5b942>連擊 5"));
        final JsonObject root = JsonParser.parseString(new HudSnapshot(List.of(), List.of(), null, vit(), null, res).toJson(0)).getAsJsonObject();
        final JsonObject o = root.getAsJsonObject("res");
        assertEquals(12, o.get("rg").getAsInt());
        assertEquals(30, o.get("rgm").getAsInt());
        assertEquals(1, o.get("rgd").getAsInt());
        final JsonObject charge = o.getAsJsonArray("ch").get(0).getAsJsonObject();
        assertEquals(2, charge.get("k").getAsInt());
        assertEquals(3, charge.get("m").getAsInt());
        assertEquals("✦", charge.get("s").getAsString());
        assertEquals("連擊 5", o.getAsJsonArray("o").get(0).getAsJsonObject().get("t").getAsString());
        final JsonObject empty = JsonParser.parseString(new HudSnapshot(List.of(), List.of(), null, vit(), null,
                new HudSnapshot.Resources(0, 30, false, List.of(), List.of())).toJson(0)).getAsJsonObject();
        assertFalse(empty.has("res"), "什麼都沒有不寫");
        assertFalse(JsonParser.parseString(new HudSnapshot(List.of(), List.of(), null, vit()).toJson(0)).getAsJsonObject().has("res"),
                "舊的建構子：沒有 res");
    }

    @Test
    void targetInvulnerableAndSides() {
        final HudSnapshot.Target target = new HudSnapshot.Target("王", 0xffffff, "傳奇", 70, 5, 9, -1, true, List.of(), true);
        final HudSnapshot.Sides sides = new HudSnapshot.Sides(List.of("憤怒"), List.of(new HudSnapshot.Pet("骷髏", 3, 4), new HudSnapshot.Pet("烏鴉", 2, 0)),
                List.of(new HudSnapshot.Loot("崇高石", 2, 0xffffff, 1100, 120)));
        final JsonObject root = JsonParser.parseString(new HudSnapshot(List.of(), List.of(), target, vit(), null, null, sides).toJson(1000))
                .getAsJsonObject();
        assertEquals(1, root.getAsJsonObject("target").get("inv").getAsInt());
        assertEquals("憤怒", root.getAsJsonArray("auras").get(0).getAsString());
        assertEquals(4, root.getAsJsonArray("pets").get(0).getAsJsonObject().get("m").getAsInt());
        assertFalse(root.getAsJsonArray("pets").get(1).getAsJsonObject().has("m"), "沒有上限不寫");
        assertEquals(100, root.getAsJsonArray("loot").get(0).getAsJsonObject().get("l").getAsLong(), "還剩幾 tick");
        final JsonObject plain = JsonParser.parseString(new HudSnapshot(List.of(), List.of(),
                new HudSnapshot.Target("怪", 1, "普通", 1, 1, 1, -1, false, List.of()), vit()).toJson(0)).getAsJsonObject();
        assertFalse(plain.getAsJsonObject("target").has("inv"), "打得動不寫");
        assertFalse(plain.has("auras"));
        // 倒數不算變化：簽章用結束的 tick
        assertEquals(new HudSnapshot(List.of(), List.of(), null, vit(), null, null, sides).signature(),
                new HudSnapshot(List.of(), List.of(), null, vit(), null, null, sides).signature());
    }

    @Test
    void gloryAndImportant() {
        final HudSnapshot.Slot glory = new HudSnapshot.Slot(4, "war_cry", "戰吼", "minecraft:book", null, true, 0, 0, 0, 1, 0, null, 3, 5);
        final HudSnapshot.Slot plain = new HudSnapshot.Slot(5, "x", "x", "minecraft:book", null, true, 0, 0, 0, 1, 0, null);
        final JsonObject root = JsonParser.parseString(new HudSnapshot(List.of(glory, plain),
                List.of(new HudSnapshot.Buff("神龕", 100, 100, 0, -1, true), new HudSnapshot.Buff("疾速", 100, 100, 0, -1)), null, vit())
                .toJson(0)).getAsJsonObject();
        final JsonObject first = root.getAsJsonArray("slots").get(0).getAsJsonObject();
        assertEquals(3, first.get("gl").getAsInt());
        assertEquals(5, first.get("gn").getAsInt());
        assertFalse(root.getAsJsonArray("slots").get(1).getAsJsonObject().has("gn"), "不用榮耀的不寫");
        assertEquals(1, root.getAsJsonArray("buffs").get(0).getAsJsonObject().get("i").getAsInt());
        assertFalse(root.getAsJsonArray("buffs").get(1).getAsJsonObject().has("i"));
        assertTrue(tw.exilecore.skill.SkillService.importantBuff("charm.amber"));
        assertTrue(tw.exilecore.skill.SkillService.importantBuff("shrine.speed"));
        assertTrue(tw.exilecore.skill.SkillService.importantBuff("unique.shrine.x"));
        assertFalse(tw.exilecore.skill.SkillService.importantBuff("onslaught"));
    }

    @Test
    void panels() {
        final List<HudPanels.Panel> panels = HudPanels.of(
                List.of("<#ff6b6b><b>⚔ 戰鬥數據</b>", " <gray>輸出 <white>1,234<gray>/秒"),
                List.of(), "<#8fd3ff><b>狀態</b>",
                List.of(),
                List.of("<#f5b942><b>任務</b>", "<white>▶ <#7fd4ff>第一章", "<gray> 打倒 5 隻", "", "<dark_gray>/quests 任務日誌"),
                List.of(" <#ffcc55>崇高石"), "<#f5b942><b>最近撿到</b>");
        assertEquals(List.of("combat", "quest", "loot"), panels.stream().map(HudPanels.Panel::id).toList(), "空的那段不列");
        assertEquals(4, panels.get(1).lines().size());
        assertEquals("<#f5b942><b>最近撿到</b>", panels.get(2).title());
        final JsonObject root = JsonParser.parseString(HudPanels.toJson(panels)).getAsJsonObject();
        final JsonObject combat = root.getAsJsonArray("panels").get(0).getAsJsonObject();
        assertEquals("⚔ 戰鬥數據", combat.getAsJsonArray("t").get(0).getAsJsonObject().get("t").getAsString());
        assertEquals(1, combat.getAsJsonArray("l").size());
        assertEquals(0, JsonParser.parseString(HudPanels.toJson(List.of())).getAsJsonObject().getAsJsonArray("panels").size());
        // 尾端的空白行拿掉
        assertEquals(List.of("a"), HudPanels.of(List.of("t", "a", "", " "), List.of(), "", List.of(), List.of(), List.of(), "").getFirst().lines());
    }

    @Test
    void componentParts() {
        final Component message = Component.text("先打倒守衛", NamedTextColor.RED)
                .append(Component.text("（還有 3 隻）", TextColor.color(0xaaaaaa)))
                .append(Component.text("！"));
        final List<MiniParts.Part> parts = ComponentParts.of(message);
        assertEquals(List.of(new MiniParts.Part("先打倒守衛", 0xFF5555), new MiniParts.Part("（還有 3 隻）", 0xAAAAAA),
                new MiniParts.Part("！", 0xFF5555)), parts, "子元件沒寫顏色就照外層");
        assertEquals(List.of(new MiniParts.Part("ab", 0xFFFFFF)), ComponentParts.of(Component.text("a").append(Component.text("b"))));
        assertTrue(ComponentParts.of(null).isEmpty());
    }
}
