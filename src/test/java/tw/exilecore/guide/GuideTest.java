package tw.exilecore.guide;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 2026-10-20 第二階段：跟主線章節連動——還沒到那一章的步驟不出現、也不擋住後面的；真的全做完才算完成。 */
    @Test
    void secondPhaseFollowsTheStory() {
        final Guide.Snapshot fresh = new Guide.Snapshot(true, true, true, true, true, true, true, 5);
        assertEquals(Guide.STEPS.size(), Guide.current(fresh), "第一階段做完、主線還沒接：沒有可以做的步驟");
        assertFalse(Guide.allDone(fresh), "但不算全部完成（主線往前走會有下一步）");
        final Guide.Snapshot chapter1 = new Guide.Snapshot(true, true, true, true, true, true, true, 8,
                1, 0, 1, false, false, false, false, 1, false, false, false, false, 0);
        assertEquals("gem", Guide.STEPS.get(Guide.current(chapter1)).id(), "第 1 章開始：先教雕琢寶石");
        final Guide.Snapshot gemDone = new Guide.Snapshot(true, true, true, true, true, true, true, 8,
                1, 0, 2, false, false, false, false, 1, false, false, false, false, 0);
        assertEquals("support", Guide.STEPS.get(Guide.current(gemDone)).id());
        final Guide.Snapshot chapter2 = new Guide.Snapshot(true, true, true, true, true, true, true, 20,
                2, 2, 3, true, true, true, false, 4, false, false, false, false, 0);
        assertEquals("salvage", Guide.STEPS.get(Guide.current(chapter2)).id(), "通貨、倉庫做了：第 2 章完成教拆解");
        final Guide.Snapshot trialDone = new Guide.Snapshot(true, true, true, true, true, true, true, 40,
                4, 4, 3, true, true, true, true, Guide.FLASK_LEVEL, true, true, false, false, 0);
        assertEquals("ascend", Guide.STEPS.get(Guide.current(trialDone)).id(), "試煉過了就教選昇華");
        final Guide.Snapshot all = new Guide.Snapshot(true, true, true, true, true, true, true, 70,
                6, 6, 3, true, true, true, true, Guide.FLASK_LEVEL, true, true, true, true, 3);
        assertEquals(Guide.STEPS.size(), Guide.current(all));
        assertTrue(Guide.allDone(all));
        final List<String> chat = Guide.chat(chapter1, Guide.current(chapter1));
        assertTrue(chat.stream().anyMatch(line -> line.contains("主線再往前走")), "還沒解鎖的步驟灰掉並說明");
    }

    @Test
    void hintsCoverEveryKind() {
        for (final String id : List.of(Hints.UNCUT_SKILL, Hints.UNCUT_SUPPORT, Hints.UNIDENTIFIED, Hints.CURRENCY, Hints.ESSENCE, Hints.OMEN,
                Hints.CATALYST, Hints.CARD, Hints.MAP, Hints.FLASK, Hints.JEWEL, Hints.DEATH, Hints.LEVEL10, Hints.LEVEL20)) {
            assertTrue(Hints.MESSAGES.containsKey(id), id);
        }
    }

    @Test
    void sidebarAndChat() {
        final List<String> sidebar = Guide.sidebar(2);
        assertTrue(sidebar.size() >= 3, "標題、步驟、提示（提示會斷成好幾行）");
        assertTrue(sidebar.get(0).contains("3/" + Guide.STEPS.size()));
        assertTrue(sidebar.get(1).contains("技能"));
        // 2026-10-22：每一步的提示在側欄都斷成不超過側欄寬度的幾行，接起來就是原本那一句
        for (int i = 0; i < Guide.STEPS.size(); i++) {
            final List<String> lines = Guide.wrap(Guide.STEPS.get(i).hint(), Guide.SIDEBAR_WIDTH, 99);
            assertTrue(lines.size() <= Guide.SIDEBAR_HINT_LINES, Guide.STEPS.get(i).id() + " 斷成 " + lines.size() + " 行：" + lines);
            for (final String line : lines) {
                assertTrue(line.codePoints().map(Guide::width).sum() <= Guide.SIDEBAR_WIDTH, Guide.STEPS.get(i).id() + "：" + line);
            }
            assertEquals(Guide.STEPS.get(i).hint().replace(" ", ""), String.join("", lines).replace(" ", ""), Guide.STEPS.get(i).id());
        }
        assertEquals(java.util.List.of("一二三四五六七八", "九十甲乙丙丁戊…"), Guide.wrap("一二三四五六七八九十甲乙丙丁戊己庚辛", 16, 2), "超過行數用…收尾");
        assertTrue(Guide.sidebar(Guide.STEPS.size()).isEmpty(), "做完了不顯示");
        final List<String> chat = Guide.chat(new Guide.Snapshot(true, true, false, false, false, false, false, 1), 2);
        assertEquals(Guide.STEPS.size() + 2, chat.size());
        assertTrue(chat.get(1).contains("✔"));
        assertTrue(chat.get(3).contains("➜"));
    }
}
