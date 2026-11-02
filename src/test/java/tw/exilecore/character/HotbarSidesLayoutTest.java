package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.skill.SkillService;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長：資源包版的增益一欄一欄往上排（放不下改小字、最後才 +N）、排序、光環那一行的顏色記號。 */
class HotbarSidesLayoutTest {

    @Test
    void allFitInOneColumn() {
        final List<Integer> widths = Collections.nCopies(5, 40);
        final HotbarSides.Placement placement = HotbarSides.placeBuffs(widths, Collections.nCopies(5, 30), -164, 238);
        assertFalse(placement.small());
        assertEquals(5, placement.slots().size());
        assertEquals(0, placement.more());
        assertEquals(-164, placement.slots().getFirst().right(), "右緣貼著生命球左邊");
        assertEquals(4, placement.slots().get(4).row(), "往上排");
    }

    @Test
    void secondColumnThenSmallThenMore() {
        // 大字：一欄 9 個、寬 40 → 第二欄要到 −164−40−6−40 = −250，超過 −238：改小字
        final HotbarSides.Placement small = HotbarSides.placeBuffs(Collections.nCopies(12, 40), Collections.nCopies(12, 30), -164, 238);
        assertTrue(small.small());
        assertEquals(12, small.slots().size(), "小字一欄 11 個、第二欄放得下");
        assertEquals(0, small.more());
        // 畫面寬（限制放寬）時大字開第二欄
        final HotbarSides.Placement wide = HotbarSides.placeBuffs(Collections.nCopies(12, 40), Collections.nCopies(12, 30), -164, 400);
        assertFalse(wide.small());
        assertEquals(-164 - 40 - 6, wide.slots().get(9).right(), "第二欄往左開");
        assertEquals(0, wide.slots().get(9).row());
        // 小字也塞不下：最後一格寫 +N
        final HotbarSides.Placement more = HotbarSides.placeBuffs(Collections.nCopies(30, 40), Collections.nCopies(30, 36), -164, 238);
        assertTrue(more.small());
        assertEquals(30, more.slots().size() + more.more(), "放得下的加 +N 剛好是全部");
        assertTrue(more.more() > 1);
    }

    @Test
    void importantAndEndingFirst() {
        final SkillService.HudBuffs buffs = new SkillService.HudBuffs(
                List.of(new SkillService.SlotTimer(2, "火焰之牆", 160, false), new SkillService.SlotTimer(4, "戰吼", 0, true),
                        new SkillService.SlotTimer(5, "炎爆", 0, false, "3/5", false)),
                List.of(new SkillService.BuffTimer("猛攻", 240), new SkillService.BuffTimer("神龕", 900, true),
                        new SkillService.BuffTimer("疾速", 70), new SkillService.BuffTimer("護符", 160, true)));
        final List<String> order = HotbarSides.buffLines(buffs).stream().map(line -> line.tier() + ":" + line.ticks()).toList();
        // 0 = 護符、神龕；1 = 技能格的（沒了的提醒 ticks 0 最前）；2 = 其他；同一級快結束的在前。榮耀（炎爆）不在這裡
        assertEquals(List.of("0:160", "0:900", "1:0", "1:160", "2:70", "2:240"), order);
    }

    @Test
    void auraColorMark() {
        assertEquals("#3cd1ff", HotbarSides.auraColor(120, 255));
        assertEquals("#ffd1fd", HotbarSides.auraColor(2000, 253), "寬度最多記 510");
    }

    // ---- 2026-11-02 照站長（第三版）：右下角 ----

    @Test
    void cornerFirstColumnUsesAllRowsLaterColumnsOnlyAboveOrb() {
        final HotbarSides.Corner corner = HotbarSides.placeCorner(Collections.nCopies(12, 60), Collections.nCopies(12, 45));
        assertFalse(corner.small());
        assertEquals(12, corner.slots().size());
        assertEquals(0, corner.slots().getFirst().right(), "右緣貼著畫面右邊（著色器再空 3 格）");
        assertEquals(0, corner.slots().getFirst().row(), "第一欄從快捷欄那一排開始");
        assertEquals(7, corner.slots().get(7).row());
        assertEquals(-66, corner.slots().get(8).right(), "第二欄往左開（60＋空 6）");
        assertEquals(HotbarSides.CORNER_UPPER, corner.slots().get(8).row(), "第二欄只用魔力球上面那幾排");
    }

    @Test
    void cornerNeverWiderThanRoomBesideChat() {
        // 第三欄會超過 146 寬：改小字；小字也放不下：最後一格寫 +N
        final HotbarSides.Corner corner = HotbarSides.placeCorner(Collections.nCopies(30, 60), Collections.nCopies(30, 45));
        assertTrue(corner.small());
        assertEquals(30, corner.slots().size() + corner.more());
        assertTrue(corner.more() > 1);
        assertTrue(corner.moreAt() != null);
        for (final HotbarSides.Slot slot : corner.slots()) {
            assertTrue(-slot.right() + 45 <= HotbarSides.CORNER_WIDTH, "整塊不超過聊天框右邊");
            assertTrue(slot.row() < HotbarSides.CORNER_ROWS, "不碰到記分板");
        }
        final HotbarSides.Corner few = HotbarSides.placeCorner(List.of(50, 70), List.of(40, 50));
        assertEquals(0, few.more());
        assertEquals(null, few.moreAt());
    }

    @Test
    void fallbackColorsAreMarked() {
        assertEquals("#f4ba43", HotbarSides.fallback("#f5b942"));
        assertEquals("#fefcfe", HotbarSides.fallback("white"));
        assertEquals("#aba9ab", HotbarSides.fallback("gray"));
    }

    @Test
    void sidebarLootOnlyForPackWithoutModPanel() {
        assertTrue(PlayerStateService.sidebarLoot(true, false));
        assertFalse(PlayerStateService.sidebarLoot(true, true), "模組畫「撿到」面板：不重複");
        assertFalse(PlayerStateService.sidebarLoot(false, false), "沒套資源包：照舊不顯示");
        final tw.exilecore.gui.SidebarLayout.Item item = PlayerStateService.sidebarItem("混沌石<b>", 3, "c:chaos", "#ffd27f");
        assertEquals("<#ffd27f>混沌石\\<b>", item.text());
        assertEquals(3, item.amount());
        assertEquals("#c8c8c8", PlayerStateService.sidebarItem("x", 1, null, "bad").color());
    }

    @Test
    void lootFeedTellsListener() {
        final LootFeed feed = new LootFeed();
        final java.util.UUID id = java.util.UUID.randomUUID();
        final List<String> seen = new java.util.ArrayList<>();
        feed.listen((player, count, entry) -> seen.add(count + "/" + entry.count()));
        feed.add(id, "混沌石", 2, null, "#ffd27f", 0);
        feed.add(id, "混沌石", 1, null, "#ffd27f", 5);
        assertEquals(List.of("2/2", "1/3"), seen, "這次加的數量、合併後的數量");
    }
}
