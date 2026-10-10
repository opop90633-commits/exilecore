package tw.exilecore.guide;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 新手引導的步驟。 */
class GuideTest {

    @Test
    void stepsAdvanceInOrder() {
        assertEquals(0, Guide.current(new Guide.Snapshot(false, false, false, false, false, false, false, 1)), "沒角色：第一步");
        assertEquals(1, Guide.current(new Guide.Snapshot(true, false, false, false, false, false, false, 1)));
        assertEquals(2, Guide.current(new Guide.Snapshot(true, true, false, false, false, false, false, 1)));
        // 跳著完成：還是停在第一個沒完成的
        assertEquals(4, Guide.current(new Guide.Snapshot(true, true, false, true, true, true, true, 9)), "任務還沒接：停在接任務");
        assertEquals(7, Guide.current(new Guide.Snapshot(true, true, true, true, true, true, true, 4)));
        assertEquals(Guide.STEPS.size(), Guide.current(new Guide.Snapshot(true, true, true, true, true, true, true, 5)), "全部完成");
    }

    @Test
    void sidebarAndChat() {
        final List<String> sidebar = Guide.sidebar(2);
        assertEquals(3, sidebar.size());
        assertTrue(sidebar.get(0).contains("3/" + Guide.STEPS.size()));
        assertTrue(sidebar.get(1).contains("技能"));
        assertTrue(Guide.sidebar(Guide.STEPS.size()).isEmpty(), "做完了不顯示");
        final List<String> chat = Guide.chat(new Guide.Snapshot(true, true, false, false, false, false, false, 1), 2);
        assertEquals(Guide.STEPS.size() + 2, chat.size());
        assertTrue(chat.get(1).contains("✔"));
        assertTrue(chat.get(3).contains("➜"));
    }
}
