package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（C 組）：可拖動 HUD 元件的註冊、偏移、夾住、下緣（B 組的地圖資訊框接在小地圖下面）。 */
class HudDragTest {

    @Test
    void registerOffsetAndBottom() {
        HudDrag.clearForTest();
        final ModConfig config = new ModConfig();
        HudDrag.use(config);
        HudDrag.register("minimap", "小地圖", (w, h) -> new HudDrag.Rect(w - 76, 4, 72, 79));
        HudDrag.register("hidden", "不畫", (w, h) -> null);
        assertEquals(83, HudDrag.bottomOf("minimap", 640, 360, 0));
        assertEquals(7, HudDrag.bottomOf("hidden", 640, 360, 7), "這一幀不畫：回傳 fallback");
        assertEquals(0, HudDrag.bottomOf("沒有", 640, 360, 0));
        HudDrag.setOffset("minimap", -10, 20);
        assertEquals(103, HudDrag.bottomOf("minimap", 640, 360, 0));
        assertEquals(554, HudDrag.bounds("minimap", 640, 360).x());
        assertArrayEquals(new int[] {-10, 20}, config.layout.get("minimap"), "存在設定裡");
        HudDrag.frame(640, 360);
        assertEquals(103, HudDrag.bottomOf("minimap"));
        HudDrag.setOffset("minimap", 0, 0);
        assertTrue(config.layout.isEmpty(), "回到原位不存");
        HudDrag.setOffset("minimap", 3, 3);
        HudDrag.resetAll();
        assertArrayEquals(new int[] {0, 0}, HudDrag.offset("minimap"));
        assertNull(HudDrag.bounds("hidden", 640, 360));
        assertEquals(2, HudDrag.elements().size());
    }

    @Test
    void clampAndSnap() {
        final HudDrag.Rect base = new HudDrag.Rect(564, 4, 72, 79);
        assertArrayEquals(new int[] {4, -4}, HudDrag.clampOffset(base, 50, -50, 640, 360), "不能拖出畫面");
        assertArrayEquals(new int[] {-564, 277}, HudDrag.clampOffset(base, -9999, 9999, 640, 360));
        assertEquals(8, HudDrag.snap(7, 4));
        assertEquals(-4, HudDrag.snap(-5, 4));
        assertEquals(7, HudDrag.snap(7, 1));
    }

    @Test
    void configResetAndSanitize() {
        final ModConfig config = new ModConfig();
        config.showAlerts = false;
        config.minimapSize = 500;
        config.layout.put("x", new int[] {1, 2});
        config.layout.put("bad", new int[] {1});
        config.sanitized();
        assertEquals(160, config.minimapSize);
        assertEquals(1, config.layout.size());
        config.autoUpdate = false;
        config.resetDisplay();
        assertTrue(config.showAlerts);
        assertTrue(config.layout.isEmpty());
        assertEquals(false, config.autoUpdate, "自動更新的設定不動");
    }
}
