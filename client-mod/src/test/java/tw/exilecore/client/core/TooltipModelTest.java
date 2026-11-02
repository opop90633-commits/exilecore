package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（C 組）：裝備說明的精簡版、詞綴階級（照插件 ItemRenderer 的排法）。 */
class TooltipModelTest {

    private static TooltipModel.Seg s(final String text, final int color) {
        return new TooltipModel.Seg(text, color);
    }

    private static TooltipModel.Line l(final TooltipModel.Seg... segs) {
        return new TooltipModel.Line(List.of(segs));
    }

    /** 一件稀有的頭盔（跟 ItemRenderer.buildLore 一樣的排法）。 */
    private static List<TooltipModel.Line> rareHelmet() {
        return List.of(
                l(s("鐵盔 ", 0xFFFF77), s("稀有", 0x555555)),
                l(s("護甲：", 0xAAAAAA), s("120", 0xFFFFFF)),
                l(s("━━━━━━━━━━━━━━", 0x555555)),
                l(s("+12", 0xA0A0FF), s("(10–15)", TooltipModel.RANGE_COLOR), s(" 最大生命 ", 0xA0A0FF), s("(固定)", 0x555555)),
                l(s("━━━━━━━━━━━━━━", 0x555555)),
                l(s("前綴 1/3・後綴 1/3", 0xAAAAAA)),
                l(s("◆ ", 0x7F88D8), s("+", 0xB4BCFF), s("25", 0xB4BCFF), s("(20–30)", TooltipModel.RANGE_COLOR), s(" 最大生命", 0xB4BCFF),
                        s(" (T3)", 0x555555)),
                l(s("◇ ", 0xD0A06A), s("+", 0xFFD9A0), s("30", 0xFFD9A0), s("(26–30)", TooltipModel.RANGE_COLOR), s("% 火焰抗性", 0xFFD9A0),
                        s(" (T1)", 0x555555)),
                l(s("━━━━━━━━━━━━━━", 0x555555)),
                l(s("物品等級 72", 0x555555)),
                l(s("", -1)),
                l(s("比身上的 ", 0x555555), s("舊盔", 0xAAAAAA), s("：", 0x555555)),
                l(s("  ", 0x555555), s("生命 +12", 0x55FF55)));
    }

    private static final String JSON = "{\"version\":1,\"base\":\"iron_helmet\",\"rarity\":\"RARE\",\"ilvl\":72,\"identified\":true,"
            + "\"affixes\":[{\"id\":\"life\",\"tier\":3,\"vars\":{\"value\":25},\"ranges\":{\"value\":[20,30]},\"side\":\"prefix\"},"
            + "{\"id\":\"fire\",\"tier\":1,\"vars\":{\"value\":30},\"ranges\":{\"value\":[26,30]},\"side\":\"suffix\"}]}";

    @Test
    void parse() {
        final TooltipModel.ItemInfo info = TooltipModel.parseEquipment(JSON);
        assertNotNull(info);
        assertEquals(72, info.ilvl());
        assertEquals(2, info.affixes().size());
        assertEquals(20, info.affixes().getFirst().ranges().get("value")[0]);
        assertFalse(info.unique());
        assertNull(TooltipModel.parseEquipment("{\"x\":1}"));
        assertNull(TooltipModel.parseEquipment("壞掉"));
        assertNull(TooltipModel.parseEquipment(null));
    }

    @Test
    void compact() {
        final List<TooltipModel.Line> out = TooltipModel.compact(rareHelmet(), TooltipModel.parseEquipment(JSON));
        final List<String> plain = out.stream().map(TooltipModel.Line::plain).toList();
        assertEquals("鐵盔 稀有", plain.get(0));
        assertEquals("+12 最大生命 (固定)", plain.get(1));
        assertEquals("◆ +25 最大生命", plain.get(2), "範圍、階級拿掉");
        assertEquals("◇ +30% 火焰抗性", plain.get(3));
        assertTrue(plain.contains("比身上的 舊盔："), "比較那幾行留著");
        assertTrue(plain.contains("  生命 +12"));
        assertFalse(plain.contains("護甲：120"));
        assertFalse(plain.contains("物品等級 72"));
        assertTrue(plain.getLast().contains("Shift") && plain.getLast().contains("Alt"));
    }

    @Test
    void tiers() {
        final List<TooltipModel.Line> out = TooltipModel.tiers(rareHelmet(), TooltipModel.parseEquipment(JSON));
        final String all = String.join("\n", out.stream().map(TooltipModel.Line::plain).toList());
        assertTrue(all.contains("物品等級 72"));
        assertTrue(all.contains("T3 前綴　+25 最大生命"), all);
        assertTrue(all.contains("T1 後綴"), all);
        assertTrue(all.contains("25 （20–30）"), all);
        assertTrue(all.contains("50%"), all);
        assertTrue(all.contains("100%"), "30 在 26–30 是滿的");
        final List<TooltipModel.Row> rows = TooltipModel.rows(rareHelmet(), TooltipModel.parseEquipment(JSON));
        assertEquals(3, rows.size());
        assertEquals("固定", rows.getFirst().kind());
        assertEquals(0, rows.getFirst().tier());
    }

    @Test
    void rollsAndFallback() {
        assertEquals(1, TooltipModel.rolls("增加 25(20–30)% 法術傷害").size());
        assertEquals(0.5, TooltipModel.rolls("增加 25(20–30)%").getFirst().fraction(), 1e-9);
        assertEquals(2, TooltipModel.rolls("附加 3(2–4) 到 10(8–12) 物理傷害").size());
        assertEquals(1.0, TooltipModel.rolls("-5(-1～-5)% 抗性上限").getFirst().fraction(), 1e-9, "負的照絕對值");
        // lore 沒寫範圍：用 custom_data 的
        final TooltipModel.ItemInfo info = TooltipModel.parseEquipment(JSON);
        final List<TooltipModel.Roll> rolls = TooltipModel.fromData("◆ +25 最大生命 (T3)", 3, info);
        assertEquals(1, rolls.size());
        assertEquals(20, rolls.getFirst().low(), 1e-9);
        assertTrue(TooltipModel.fromData("◆ +99 最大生命 (T3)", 3, info).isEmpty());
    }

    @Test
    void gemAndUnique() {
        final List<TooltipModel.Line> gem = List.of(l(s("力量 技能寶石", 0xFF5555)), l(s("[攻擊][範圍]", 0xAAAAAA)), l(s("揮出一道火焰。", 0xAAAAAA)),
                l(s("需要單手武器", 0xAAAAAA)), l(s("需要角色等級 12", 0xAAAAAA)), l(s("", -1)), l(s("輔助孔：2／5", 0xAAAAAA)));
        final List<String> plain = TooltipModel.compact(gem, TooltipModel.gem()).stream().map(TooltipModel.Line::plain).toList();
        assertEquals(List.of("力量 技能寶石", "[攻擊][範圍]", "揮出一道火焰。", "需要角色等級 12", "按住 Shift：完整說明"), plain);
        assertEquals(gem, TooltipModel.tiers(gem, TooltipModel.gem()), "寶石沒有詞綴階級：照原本的");

        final TooltipModel.ItemInfo unique = TooltipModel.parseEquipment("{\"base\":\"x\",\"rarity\":\"UNIQUE\",\"affixes\":[]}");
        final List<TooltipModel.Line> lore = List.of(l(s("鐵盔 ", 0xAF6025), s("傳奇", 0x555555)), l(s("品質：", 0xAAAAAA), s("+20%", 0x8888FF)),
                l(s("+40", TooltipModel.UNIQUE_AFFIX), s("(30–50)", TooltipModel.RANGE_COLOR), s(" 最大生命", TooltipModel.UNIQUE_AFFIX)),
                l(s("<i>傳說…", 0xAF6025)));
        final List<String> compact = TooltipModel.compact(lore, unique).stream().map(TooltipModel.Line::plain).toList();
        assertEquals("+40 最大生命", compact.get(1));
        assertEquals(3, compact.size());
        assertEquals("傳奇", TooltipModel.rows(lore, unique).getFirst().kind());
    }
}
