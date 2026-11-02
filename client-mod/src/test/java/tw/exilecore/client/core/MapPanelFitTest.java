package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長：地圖資訊框太長時縮短（不跟側邊面板疊）、側邊面板接在地圖資訊框下面。 */
class MapPanelFitTest {

    private static HudData.Part p(final String text) {
        return new HudData.Part(text, 0xFFFFFF);
    }

    private static WorldCodec.MapPanel panel(final int mods) {
        final List<List<HudData.Part>> lines = new ArrayList<>();
        for (int i = 0; i < mods; i++) {
            lines.add(List.of(p("詞綴 " + i)));
        }
        return new WorldCodec.MapPanel("沉船灣", 0x9FD3FF, 7, 70, lines, 5, "次", 41, 22, WorldCodec.BOSS_FIGHTING, "薩卡", 62,
                List.of(new WorldCodec.EventLine("裂隙", 0xC48BFF, "進行中", 60, WorldCodec.EVENT_ACTIVE)));
    }

    private static int width(final String text) {
        return text.length() * 6;
    }

    @Test
    void fitsAsIs() {
        final List<MapPanelLayout.Row> rows = MapPanelLayout.layout(panel(2), false, MapPanelFitTest::width);
        assertSame(rows, MapPanelLayout.fit(rows, List.of(), 4, 500, "F6 展開"), "放得下照原樣");
    }

    @Test
    void trimsModsAndEvents() {
        final WorldCodec.MapPanel p = panel(8);
        final List<MapPanelLayout.Row> rows = MapPanelLayout.layout(p, false, MapPanelFitTest::width);
        final int max = 80;
        final List<MapPanelLayout.Row> fit = MapPanelLayout.fit(rows, MapPanelLayout.layout(p, true, MapPanelFitTest::width),
                MapPanelLayout.keepRows(p), max, "F6 展開");
        assertTrue(MapPanelLayout.totalHeight(fit) <= max, "不超過可用的高度");
        assertEquals("…F6 展開", fit.getLast().left().getFirst().text(), "最後一行寫怎麼展開");
        for (int i = 0; i < 4; i++) {
            assertEquals(rows.get(i), fit.get(i), "名稱、傳送門、清除、Boss 一定留");
        }
        assertFalse(fit.get(fit.size() - 2).left().getFirst().text().equals("地圖詞綴"), "只剩小標題的不留");
    }

    @Test
    void collapsesWhenTiny() {
        final WorldCodec.MapPanel p = panel(8);
        final List<MapPanelLayout.Row> collapsed = MapPanelLayout.layout(p, true, MapPanelFitTest::width);
        assertSame(collapsed, MapPanelLayout.fit(MapPanelLayout.layout(p, false, MapPanelFitTest::width), collapsed, 4, 30, "F6 展開"));
    }

    @Test
    void panelsBelowMapPanel() {
        for (final int scale : new int[] {2, 3, 4}) {
            final int w = 1920 / scale;
            final int h = 1080 / scale;
            final HudLayout.Box base = HudLayout.panels(w, h);
            assertEquals(base.y(), HudLayout.panelsTop(w, h, null), "沒有地圖資訊框：40%");
            final HudLayout.Box map = new HudLayout.Box(w - 4 - MapPanelLayout.WIDTH, 80, MapPanelLayout.WIDTH, HudLayout.mapPanelLimit(h) - 80);
            final int top = HudLayout.panelsTop(w, h, map);
            assertTrue(top >= map.bottom() + 4, "GUI " + scale + "：接在地圖資訊框下面");
            assertTrue(top < base.bottom(), "GUI " + scale + "：下面還有位置");
            // 地圖資訊框被拖到左邊：不讓
            assertEquals(base.y(), HudLayout.panelsTop(w, h, new HudLayout.Box(4, 80, 150, 200)));
            // 很短的框（下緣在 40% 上面）：照 40%
            assertEquals(base.y(), HudLayout.panelsTop(w, h, new HudLayout.Box(w - 154, 80, 150, 20)));
        }
    }
}
