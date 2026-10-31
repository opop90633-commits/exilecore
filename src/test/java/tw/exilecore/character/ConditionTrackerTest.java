package tw.exilecore.character;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-26：天賦樹條件的狀態判斷（移動、低血、滿血、主手副手的位置）。 */
class ConditionTrackerTest {

    @Test
    void movingNeedsHalfABlockPerSecond() {
        assertTrue(ConditionTracker.isMoving(1.0, 10), "半秒走 1 格＝每秒 2 格");
        assertFalse(ConditionTracker.isMoving(0.1, 10), "原地小晃不算");
        assertFalse(ConditionTracker.isMoving(5, 0));
    }

    @Test
    void lowAndFullLife() {
        assertTrue(ConditionTracker.lowLife(0.35));
        assertFalse(ConditionTracker.lowLife(0.36));
        assertTrue(ConditionTracker.fullLife(1.0));
        assertFalse(ConditionTracker.fullLife(0.98));
    }

    @Test
    void handSlots() {
        assertEquals(tw.exilecore.item.ItemSlot.MAIN_HAND, PlayerState.JEWELLERY_SLOTS[ConditionTracker.MAIN_HAND]);
        assertEquals(tw.exilecore.item.ItemSlot.OFF_HAND, PlayerState.JEWELLERY_SLOTS[ConditionTracker.OFF_HAND]);
    }

    @Test
    void channelAndLeechStateAreReadable() {
        final PlayerState state = new PlayerState(java.util.UUID.randomUUID());
        assertTrue(state.recentTick("channel") < 0);
        state.markRecent("channel", 500);
        assertEquals(500, state.recentTick("channel"));
        assertEquals(0, state.leechPool(), 1e-9);
    }

    // ---- 2026-10-26：變身、夥伴、門檻條件、事件計數 ----

    @Test
    void countedConditionsParseTheThreshold() {
        assertEquals(new tw.exilecore.stats.TreeConditions.Counted("rage", 10), tw.exilecore.stats.TreeConditions.counted("rage10"));
        assertEquals(null, tw.exilecore.stats.TreeConditions.counted("rage"));
        assertEquals(null, tw.exilecore.stats.TreeConditions.counted("lifeflask10s"));
        assertTrue(tw.exilecore.stats.TreeConditions.known("totems3") && tw.exilecore.stats.TreeConditions.known("nowarcries2"));
        assertTrue(tw.exilecore.stats.TreeConditions.known("shapeshifted") && tw.exilecore.stats.TreeConditions.known("companion"));
        assertTrue(tw.exilecore.stats.TreeConditions.RESERVED.isEmpty(), "變身、夥伴接上了");
        assertEquals(new tw.exilecore.stats.Recently.Condition("attack", true), tw.exilecore.stats.Recently.condition("attack.speed@noattack"));
    }

    @Test
    void countedEventsUseTheirWindowAndDistinctIds() {
        final java.util.List<ConditionTracker.Noted> log = java.util.List.of(
                new ConditionTracker.Noted("spelltypes", "blazing_star_shell", 0), new ConditionTracker.Noted("spelltypes", "blazing_star_shell", 50),
                new ConditionTracker.Noted("spelltypes", "frost_drill", 100), new ConditionTracker.Noted("warcries", "a", 100));
        assertEquals(2, ConditionTracker.counted(log, "spelltypes", 160, true, 150), "火球兩次只算一種");
        assertEquals(3, ConditionTracker.counted(log, "spelltypes", 160, false, 150));
        assertEquals(1, ConditionTracker.counted(log, "spelltypes", 160, true, 250), "8 秒前的不算");
        assertEquals(1, ConditionTracker.counted(log, "warcries", 80, false, 150));
        assertEquals(0, ConditionTracker.counted(log, "warcries", 80, false, 181), "4 秒過了");
    }

    @Test
    void fullNoteLogEvictsTheSameKindFirst() {
        // 2026-10-27：記滿了丟記最多筆的那種裡最舊的；這次之前新的直接不記，被打一直洗會把放法術的計數擠掉
        final java.util.ArrayDeque<ConditionTracker.Noted> log = new java.util.ArrayDeque<>();
        ConditionTracker.append(log, new ConditionTracker.Noted("spelltypes", "blazing_star_shell", 0));
        for (int i = 0; i < ConditionTracker.NOTE_CAP + 50; i++) {
            ConditionTracker.append(log, new ConditionTracker.Noted("hitstaken", "x", 1));
        }
        ConditionTracker.append(log, new ConditionTracker.Noted("spelltypes", "frost_drill", 2));
        assertEquals(ConditionTracker.NOTE_CAP, log.size());
        assertEquals(2, ConditionTracker.counted(log, "spelltypes", 160, true, 2), "放過的法術沒被擠掉，新的也記得進去");
        ConditionTracker.append(log, new ConditionTracker.Noted("hitstaken", "x", 400));
        assertEquals(1, log.size(), "10 秒前的照樣丟掉");
    }

    @Test
    void revertSpellDamageIsPerSecondAndCapped() {
        assertEquals(30, ConditionTracker.revertSpellDamage(10, 3.9), 1e-9, "每整秒 10%");
        assertEquals(80, ConditionTracker.revertSpellDamage(10, 30), 1e-9, "最多 80%");
        assertEquals(0, ConditionTracker.revertSpellDamage(10, 0.5), 1e-9);
    }

    @Test
    void extraTargetTags() {
        final java.util.Set<String> tags = tw.exilecore.combat.TargetConditions.extraTags(
                new tw.exilecore.combat.TargetConditions.Extra(true, true, false, true, 2, tw.exilecore.stats.TreeConditions.VS_UNDEAD), false);
        assertTrue(tags.containsAll(java.util.Set.of("vs_pinned", "vs_exposed", "vs_slowed", "vs_notpoisoned", "vs_eleail1", "vs_eleail2", "vs_undead")));
        assertFalse(tags.contains("vs_eleail3") || tags.contains("vs_noeleail") || tags.contains("vs_maimed"));
        assertEquals(tw.exilecore.stats.TreeConditions.VS_UNDEAD, tw.exilecore.combat.TargetConditions.family(org.bukkit.entity.EntityType.ZOMBIE));
        assertEquals(tw.exilecore.stats.TreeConditions.VS_BEAST, tw.exilecore.combat.TargetConditions.family(org.bukkit.entity.EntityType.SPIDER));
        assertTrue(tw.exilecore.stats.TreeConditions.ENEMY.containsAll(tags));
    }
}
