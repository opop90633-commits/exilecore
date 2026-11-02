package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長（1.4）：1920×1080 在 GUI 縮放 2、3、4，有球、東西最多的時候，各區塊都不重疊。 */
class HudLayoutTest {

    private static Map<String, HudLayout.Box> regions(final int w, final int h, final int bossBars, final boolean sides) {
        final int size = OrbLayout.size(1f);
        final int cx = w / 2;
        final Map<String, HudLayout.Box> boxes = new LinkedHashMap<>();
        boxes.put("快捷欄＋副手格", new HudLayout.Box(cx - 91 - 29, h - 23, 182 + 58, 23));
        boxes.put("生命球", new HudLayout.Box(OrbLayout.leftX(w, size), OrbLayout.top(h, size), size, size));
        boxes.put("魔力球", new HudLayout.Box(OrbLayout.rightX(w), OrbLayout.top(h, size), size, size));
        boxes.put("技能列", new HudLayout.Box(cx - 99, h - 37 - 18, 198, 18));
        boxes.put("盔甲（往上移 15）", new HudLayout.Box(cx - 91, h - 64, 182, 9));
        // 2026-11-02 照站長（第三版）：增益欄在聊天框（收起時，預設 320 寬、10 行）上面（不碰聊天框見 BuffPlacementTest；
        //   原版聊天框本來就蓋到左球那一帶，不放進這張表）
        boxes.put("增益欄", HudLayout.buffArea(w, h, size, HudLayout.chatBox(w, h, HudLayout.CHAT_WIDTH, HudLayout.CHAT_HEIGHT, 1.0)));
        boxes.put("光環", HudLayout.auraStrip(w, h, size));
        boxes.put("守護量", HudLayout.guardText(w, h, size));
        boxes.put("異常圖示", HudLayout.statusIcons(w, h, size, 99));
        boxes.put("戰鬥資源", HudLayout.resources(w, h, size));
        boxes.put("提示列", HudLayout.notices(w, h, HudLayout.noticeLines(h)));
        boxes.put("面板群", HudLayout.panels(w, h));
        final int bottom = HudLayout.bossBarsBottom(bossBars, h);
        boxes.put("Boss 血條", new HudLayout.Box(cx - 91, 3, 182, bottom - 3));
        boxes.put("目標框", new HudLayout.Box(cx - HudLayout.TARGET_WIDTH / 2, HudLayout.targetY(32, bossBars, h) - 2,
                HudLayout.TARGET_WIDTH, HudLayout.TARGET_HEIGHT));
        boxes.put("準心", new HudLayout.Box(cx - 8, h / 2 - 8, 16, 16));
        return boxes;
    }

    @Test
    void noOverlapAtGuiScales() {
        for (final int scale : new int[] {2, 3, 4}) {
            final int w = 1920 / scale;
            final int h = 1080 / scale;
            for (final int bars : new int[] {0, 1, 3}) {
              for (final boolean sides : new boolean[] {false, true}) {
                final Map<String, HudLayout.Box> boxes = regions(w, h, bars, sides);
                final List<String> names = new ArrayList<>(boxes.keySet());
                for (int i = 0; i < names.size(); i++) {
                    final HudLayout.Box a = boxes.get(names.get(i));
                    assertTrue(a.x() >= 0 && a.right() <= w && a.y() >= 0 && a.bottom() <= h,
                            "GUI " + scale + "：" + names.get(i) + " 超出畫面 " + a);
                    for (int k = i + 1; k < names.size(); k++) {
                        final HudLayout.Box b = boxes.get(names.get(k));
                        assertFalse(a.intersects(b), "GUI " + scale + "、Boss 血條 " + bars + " 條：" + names.get(i) + " " + a + " 碰到 "
                                + names.get(k) + " " + b);
                    }
                }
              }
            }
        }
    }

    @Test
    void panelsLeaveTopRight() {
        for (final int scale : new int[] {2, 3, 4}) {
            final int h = 1080 / scale;
            final HudLayout.Box panels = HudLayout.panels(1920 / scale, h);
            assertTrue(panels.y() >= Math.round(h * 0.4), "右上角讓給地圖資訊框、小地圖：面板從畫面高度 40% 開始");
            assertTrue(panels.h() >= 60, "GUI " + scale + "：面板至少放得下幾行");
        }
    }

    @Test
    void compactAtGui4() {
        assertTrue(HudLayout.resources(480, 270, OrbLayout.size(1f)).w() < HudLayout.COMPACT_RES, "GUI 4：資源只畫點點與細條");
        assertTrue(HudLayout.resources(640, 360, OrbLayout.size(1f)).w() >= HudLayout.COMPACT_RES);
        assertEquals(3, HudLayout.noticeLines(270), "GUI 4：提示列少一則，不碰到準心");
        assertEquals(4, HudLayout.noticeLines(360));
    }

    @Test
    void targetBelowBossBars() {
        assertEquals(32, HudLayout.targetY(32, 0, 360));
        assertEquals(32, HudLayout.targetY(32, 1, 360), "一條：原本的位置就在下面");
        assertEquals(12 + 19 + 5 + 6, HudLayout.targetY(32, 2, 360));
        assertEquals(12 + 19 * 2 + 5 + 6, HudLayout.targetY(32, 3, 360));
        // 超過畫面 1/3 的血條原版不畫：不用再往下推
        assertEquals(HudLayout.bossBarsBottom(10, 270), HudLayout.bossBarsBottom(20, 270));
        assertTrue(HudLayout.bossBarsBottom(20, 270) < 270 / 3 + 10);
    }
}
