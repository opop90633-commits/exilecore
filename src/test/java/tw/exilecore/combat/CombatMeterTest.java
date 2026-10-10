package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.stats.DamageType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 戰鬥數據（{@link CombatMeter}，2026-10）：每秒輸出、每秒承受與類型、最大一擊。 */
class CombatMeterTest {

    @Test
    void dpsCountsFromTheStartOfTheFightAtLeastOneSecond() {
        final CombatMeter meter = new CombatMeter();
        meter.dealt(1000, Map.of(DamageType.FIRE, 500.0));
        assertEquals(500, meter.snapshot(1000).dps(), 1e-9, "第一下：至少算 1 秒，不會算成一秒一萬");
        meter.dealt(1020, Map.of(DamageType.FIRE, 300.0, DamageType.PHYSICAL, 200.0));
        meter.dealt(1039, Map.of(DamageType.PHYSICAL, 1000.0));
        final CombatMeter.Snapshot snapshot = meter.snapshot(1039);
        assertEquals(2.0, snapshot.seconds(), 1e-9, "打了 2 秒");
        assertEquals(1000, snapshot.dps(), 1e-9);
        assertEquals(400, snapshot.dealt()[DamageType.FIRE.ordinal()], 1e-9);
        assertEquals(600, snapshot.dealt()[DamageType.PHYSICAL.ordinal()], 1e-9);
    }

    @Test
    void oldHitsLeaveTheWindowAndAPauseStartsOver() {
        final CombatMeter meter = new CombatMeter();
        for (long tick = 0; tick < 400; tick += 20) {
            meter.dealt(tick, DamageType.COLD, 100);
        }
        final CombatMeter.Snapshot steady = meter.snapshot(399);
        assertEquals(5.0, steady.seconds(), 1e-9, "打很久了：照最近 5 秒算");
        assertEquals(100, steady.dps(), 10, "每秒一下 100");
        assertTrue(meter.active(480));
        assertFalse(meter.active(481), "停手超過 5 秒：收起來");
        meter.dealt(2000, DamageType.COLD, 50);
        final CombatMeter.Snapshot again = meter.snapshot(2000);
        assertEquals(50, again.dps(), 1e-9, "重新開打：以前的不算");
        assertEquals(1.0, again.seconds(), 1e-9);
    }

    @Test
    void takenIsSplitByTypeAndTheBiggestHitIsKept() {
        final CombatMeter meter = new CombatMeter();
        // 一下 300：火 2、物理 1 的比例（扣掉的護盾＋生命照比例分）
        meter.taken(100, Map.of(DamageType.FIRE, 200.0, DamageType.PHYSICAL, 100.0), 300);
        meter.taken(110, DamageType.CHAOS, 40);
        meter.taken(115, Map.of(), 60); // 沒有類型：算物理
        final CombatMeter.Snapshot snapshot = meter.snapshot(119);
        assertEquals(400, snapshot.takenPerSecond(), 1e-9, "不到 1 秒算 1 秒");
        assertEquals(200, snapshot.taken()[DamageType.FIRE.ordinal()], 1e-9);
        assertEquals(160, snapshot.taken()[DamageType.PHYSICAL.ordinal()], 1e-9);
        assertEquals(40, snapshot.taken()[DamageType.CHAOS.ordinal()], 1e-9);
        assertEquals(300, snapshot.biggest(), 1e-9);
        assertEquals(DamageType.FIRE, snapshot.biggestType(), "最大一擊主要是火焰");
        assertEquals(0, snapshot.dps(), 1e-9);
    }

    @Test
    void sidebarLinesShowOutputTakenTypesAndBiggestHit() {
        final CombatMeter meter = new CombatMeter();
        meter.dealt(100, Map.of(DamageType.FIRE, 1404.0, DamageType.PHYSICAL, 936.0, DamageType.LIGHTNING, 0.2));
        meter.taken(100, Map.of(DamageType.COLD, 1.0), 250);
        final List<String> lines = CombatMeter.lines(meter.snapshot(100));
        assertEquals(CombatMeter.TITLE, lines.getFirst());
        assertTrue(lines.get(1).contains("2,340"), lines.get(1));
        assertEquals("  <#ff8040>火焰 1,404 <#ececec>物理 936", lines.get(2), "多到少；太小的不寫");
        assertTrue(lines.get(3).contains("250"), lines.get(3));
        assertEquals("  <#80d8ff>冰冷 250", lines.get(4));
        assertEquals(" <gray>最大一擊 <white>250 <#80d8ff>冰冷", lines.get(5));
        final List<String> quiet = CombatMeter.lines(new CombatMeter().snapshot(0));
        assertEquals(3, quiet.size(), "沒有數字：只有標題、輸出、承受");
        assertTrue(quiet.get(2).contains("<white>0"));
    }

    @Test
    void damageOverTimeIsListedOnItsOwnLineAndIsNeverTheBiggestHit() {
        final CombatMeter meter = new CombatMeter();
        meter.dealt(100, Map.of(DamageType.PHYSICAL, 1000.0));
        meter.dealtDot(105, AilmentType.BLEED, DamageType.PHYSICAL, 150);
        meter.dealtDot(110, AilmentType.POISON, DamageType.CHAOS, 80);
        meter.takenDot(110, AilmentType.POISON, DamageType.CHAOS, 500);
        meter.taken(112, Map.of(DamageType.FIRE, 1.0), 120);
        final CombatMeter.Snapshot snapshot = meter.snapshot(119);
        assertEquals(1150, snapshot.dealt()[DamageType.PHYSICAL.ordinal()], 1e-9, "流血也算在物理裡");
        assertEquals(1230, snapshot.dps(), 1e-9);
        assertEquals(150, snapshot.dotDealt()[AilmentType.BLEED.ordinal()], 1e-9);
        assertEquals(80, snapshot.dotDealt()[AilmentType.POISON.ordinal()], 1e-9);
        assertEquals(500, snapshot.taken()[DamageType.CHAOS.ordinal()], 1e-9);
        assertEquals(500, snapshot.dotTaken()[AilmentType.POISON.ordinal()], 1e-9);
        assertEquals(120, snapshot.biggest(), 1e-9, "持續傷害的一跳不算最大一擊");
        assertEquals(DamageType.FIRE, snapshot.biggestType());
        final List<String> lines = CombatMeter.lines(snapshot);
        assertEquals("  <gray>持續 <#ff4a4a>流血 150 <#7fd13b>中毒 80", lines.get(3), "輸出下面一行：持續傷害分開列");
        assertTrue(lines.get(4).contains("承受"), lines.get(4));
        assertEquals("  <gray>持續 <#7fd13b>中毒 500", lines.get(6), "承受下面一行");
        assertEquals(" <gray>最大一擊 <white>120 <#ff8040>火焰", lines.get(7));
        assertEquals(8, lines.size());
    }

    @Test
    void bigNumbersAreShortened() {
        assertEquals("99,999", CombatMeter.number(99_999));
        assertEquals("12.3萬", CombatMeter.number(123_456));
        assertEquals("1.23億", CombatMeter.number(123_456_789));
    }

    @Test
    void combatGoesOnTopOfTheSidebar() {
        final List<String> combat = List.of(CombatMeter.TITLE, " 輸出", " 承受");
        final List<String> quest = List.of("任務", " 第一步");
        final List<String> lines = tw.exilecore.gui.SidebarLayout.compose(combat, List.of(), quest, List.of());
        assertEquals(List.of(CombatMeter.TITLE, " 輸出", " 承受", "", "任務", " 第一步"), lines);
        assertEquals(quest, tw.exilecore.gui.SidebarLayout.compose(List.of(), List.of(), quest, List.of()), "沒在戰鬥：跟以前一樣");
    }
}
