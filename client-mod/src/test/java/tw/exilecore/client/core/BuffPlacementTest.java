package tw.exilecore.client.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02 照站長（第三版）：增益欄預設在聊天框上面、聊天打開疊到才淡出、從左往右排；
 * 所有 HUD 元件都能拖（側邊面板一個一個拖：拖出來改從面板群左上角算，不會跳）。
 */
class BuffPlacementTest {

    @Test
    void chatBoxMatchesVanilla() {
        final HudLayout.Box chat = HudLayout.chatBox(640, 360, 320, 90, 1.0);
        assertEquals(0, chat.x());
        assertEquals(328, chat.right(), "320 寬＋兩邊的底色");
        assertEquals(320, chat.bottom(), "下緣在畫面高 − 40");
        assertEquals(230, chat.y(), "收起時 10 行 × 9");
        final HudLayout.Box half = HudLayout.chatBox(640, 360, 320, 90, 0.5);
        assertEquals(164, half.right());
        assertEquals(275, half.y());
        assertEquals(50, HudLayout.chatBox(480, 270, 320, 180, 1.0).y(), "打開時 180 高：GUI 4 頂到上面 50");
    }

    @Test
    void buffAreaAboveChatAtEveryScale() {
        for (final int scale : new int[] {2, 3, 4}) {
            final int w = 1920 / scale;
            final int h = 1080 / scale;
            final HudLayout.Box chat = HudLayout.chatBox(w, h, 320, 90, 1.0);
            final HudLayout.Box area = HudLayout.buffArea(w, h, OrbLayout.size(1f), chat);
            assertFalse(area.intersects(chat), "GUI " + scale + "：不碰到聊天框");
            assertEquals(chat.y() - HudLayout.BUFF_CHAT_GAP, area.bottom());
            assertEquals(2, area.x(), "從畫面左邊開始");
            assertTrue(area.right() <= w / 2 - HudLayout.BUFF_CENTER_GAP, "不碰到準心、提示列");
            assertTrue(area.h() >= 5 * SideLayout.ROW, "GUI " + scale + "：至少 5 排");
        }
        // 聊天框調到很高、上面放不下：退回左球左邊
        final HudLayout.Box tall = HudLayout.chatBox(480, 270, 320, 180, 1.0);
        assertEquals(HudLayout.buffColumn(480, 270, 38), HudLayout.buffArea(480, 270, 38, tall));
    }

    @Test
    void fadesOnlyWhenChatOpenAndOverlapping() {
        final HudLayout.Box open = HudLayout.chatBox(640, 360, 320, 180, 1.0);
        final HudLayout.Box buffs = new HudLayout.Box(2, 150, 80, 60);
        assertEquals(1f, HudLayout.chatFade(buffs, false, open, 640, 360), "聊天沒開：照舊");
        assertEquals(HudLayout.CHAT_FADE, HudLayout.chatFade(buffs, true, open, 640, 360), "打開、疊到：淡出");
        assertEquals(1f, HudLayout.chatFade(new HudLayout.Box(400, 150, 80, 60), true, open, 640, 360), "拖到右邊、沒疊到：照舊");
        assertEquals(HudLayout.CHAT_FADE, HudLayout.chatFade(new HudLayout.Box(400, 350, 80, 8), true, open, 640, 360), "疊到最下面的輸入框");
        assertEquals(1f, HudLayout.chatFade(null, true, open, 640, 360));
    }

    @Test
    void columnsGrowRightFromLeftEdge() {
        final SideLayout.Result r = SideLayout.columnFromLeft(Collections.nCopies(7, 50), 2, 200, 140, 300);
        assertEquals(1f, r.scale());
        assertEquals(7, r.cells().size());
        assertEquals(2, r.cells().getFirst().x(), "左緣貼著畫面左邊");
        assertEquals(188, r.cells().getFirst().y(), "第一格在最下面");
        assertEquals(2 + 50 + SideLayout.GAP, r.cells().get(5).x(), "一欄 5 排，第二欄往右開");
        final HudLayout.Box box = SideLayout.bounds(r, Collections.nCopies(7, 50));
        assertEquals(new HudLayout.Box(2, 140, 104, 60), box);
        // 塞不下：縮小，再塞不下最後一格寫 +N
        final SideLayout.Result more = SideLayout.columnFromLeft(Collections.nCopies(40, 50), 2, 200, 140, 120);
        assertEquals(SideLayout.SMALL, more.scale());
        assertEquals(40, more.cells().size() + more.more());
        assertTrue(more.more() > 0);
        assertNull(SideLayout.bounds(new SideLayout.Result(1f, List.of(), 0), List.of()));
    }

    @Test
    void detachablePanelDoesNotJump() {
        HudDrag.clearForTest();
        final ModConfig config = new ModConfig();
        HudDrag.use(config);
        final HudDrag.Rect[] slot = {new HudDrag.Rect(488, 200, 150, 40)};
        HudDrag.register("panel.minions", "面板：召喚物", (w, h) -> slot[0], (w, h) -> new HudDrag.Rect(488, 144, 150, 11));
        assertFalse(HudDrag.moved("panel.minions"));
        assertEquals(slot[0], HudDrag.bounds("panel.minions", 640, 360), "沒拖過：在面板群裡的位置");
        final int[] start = HudDrag.dragStart("panel.minions", 640, 360);
        assertArrayEquals(new int[] {0, 56}, start, "從現在的位置開始拖");
        HudDrag.setOffset("panel.minions", start[0] - 100, start[1] + 10);
        assertEquals(new HudDrag.Rect(388, 210, 150, 40), HudDrag.bounds("panel.minions", 640, 360), "拖過：home ＋ 偏移");
        slot[0] = new HudDrag.Rect(488, 260, 150, 40); // 上面的面板變長：沒拖過的會往下跑，拖過的不動
        assertEquals(388, HudDrag.bounds("panel.minions", 640, 360).x());
        assertEquals(210, HudDrag.bounds("panel.minions", 640, 360).y());
        assertEquals(new HudDrag.Rect(488, 144, 150, 40), HudDrag.base("panel.minions", 640, 360));
        assertArrayEquals(new int[] {-100, 66}, HudDrag.dragStart("panel.minions", 640, 360), "拖過的：照現在的偏移繼續拖");
        HudDrag.reset("panel.minions");
        assertEquals(slot[0], HudDrag.bounds("panel.minions", 640, 360), "右鍵重設：回到面板群");
    }

    @Test
    void placeholdersForEveryDraggablePanel() {
        final List<Panels.Panel> some = new ArrayList<>(List.of(new Panels.Panel("quest", List.of(new HudData.Part("任務", 0xFFFFFF)), List.of())));
        final List<Panels.Panel> all = Panels.placeholders(some);
        final List<String> ids = all.stream().map(Panels.Panel::id).toList();
        assertEquals(List.of("combat", "status", "minions", "guide", "quest", "drops"), ids, "照順序；有內容的不重複、最近撿到（記分板版）不補");
        assertEquals("任務", all.get(4).title().getFirst().text(), "有內容的照舊");
        assertTrue(Panels.LABELS.get("minions").contains("召喚物"));
        assertTrue(Panels.LABELS.get("drops").contains("撿到"));
    }
}
