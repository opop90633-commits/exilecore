package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（1.4 B 組）：地圖資訊框的排版。 */
class MapPanelLayoutTest {

    /** 假的字寬：中文 9、其他 6。 */
    static final ToIntFunction<String> WIDTH = s -> s.codePoints().map(c -> c > 0x2E80 ? 9 : 6).sum();

    static WorldCodec.MapPanel panel(final int boss) {
        return new WorldCodec.MapPanel("沉船灣 T7", 0x9FD3FF, 7, 70,
                List.of(List.of(new HudData.Part("怪物生命增加 30%，而且怪物造成的傷害增加 25%，而且非常長的一行詞綴", 0xFFFF77))), 5, "次", 42, 30,
                boss, "潮汐統帥", 62,
                List.of(new WorldCodec.EventLine("裂隙", 0xC48BFF, "還有 12 秒", 40, WorldCodec.EVENT_ACTIVE),
                        new WorldCodec.EventLine("保險箱", 0xC8D0D8, "已開", -1, WorldCodec.EVENT_DONE)));
    }

    private static String text(final List<HudData.Part> parts) {
        final StringBuilder out = new StringBuilder();
        parts.forEach(p -> out.append(p.text()));
        return out.toString();
    }

    @Test
    void expanded() {
        final List<MapPanelLayout.Row> rows = MapPanelLayout.layout(panel(WorldCodec.BOSS_FIGHTING), false, WIDTH);
        assertEquals("沉船灣 T7", text(rows.get(0).left()));
        assertEquals("T7 Lv.70", text(rows.get(0).right()));
        assertEquals("剩 5 次", text(rows.get(1).left()));
        assertEquals(30, rows.get(2).progress(), "清除比例畫進度");
        assertEquals("戰鬥中 62%", text(rows.get(3).right()));
        assertEquals(62, rows.get(3).progress());
        final int max = Math.round((MapPanelLayout.WIDTH - 6) / MapPanelLayout.SMALL);
        final long modRows = rows.stream().filter(r -> !r.left().isEmpty() && r.left().getFirst().color() == 0xFFFF77).count();
        assertTrue(modRows >= 2, "太長的詞綴換行");
        for (final MapPanelLayout.Row row : rows) {
            if (row.small()) {
                assertTrue(WIDTH.applyAsInt(text(row.left())) <= max, text(row.left()));
            }
        }
        final MapPanelLayout.Row breach = rows.stream().filter(r -> text(r.left()).equals("裂隙")).findFirst().orElseThrow();
        assertEquals(40, breach.progress());
        final MapPanelLayout.Row safe = rows.stream().filter(r -> text(r.left()).equals("保險箱")).findFirst().orElseThrow();
        assertTrue(safe.dim(), "結束的事件暗一點");
        assertEquals(-1, safe.progress());
    }

    @Test
    void collapsedAndClear() {
        final List<MapPanelLayout.Row> rows = MapPanelLayout.layout(panel(WorldCodec.BOSS_KILLED), true, WIDTH);
        assertEquals(2, rows.size());
        assertEquals("剩 5 次・清除 30%・Boss 已擊殺", text(rows.get(1).left()));
        assertTrue(MapPanelLayout.layout(WorldCodec.CLEAR, false, WIDTH).isEmpty());
        assertTrue(MapPanelLayout.layout(null, false, WIDTH).isEmpty());
    }

    @Test
    void wrapKeepsColors() {
        final List<List<HudData.Part>> lines = MapPanelLayout.wrap(List.of(new HudData.Part("ab", 1), new HudData.Part("cdef", 2)), WIDTH, 24);
        assertEquals(2, lines.size());
        assertEquals("ab", lines.get(0).get(0).text());
        assertEquals("cd", lines.get(0).get(1).text());
        assertEquals(2, lines.get(1).getFirst().color());
    }
}
