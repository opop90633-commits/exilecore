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

    @org.junit.jupiter.api.Test
    void dealtIsSplitBySkillAndDamageType() {
        final CombatMeter meter = new CombatMeter();
        // 兩秒內：瘴苞雨打兩下（物理＋火焰）、召喚骸骨兵打一下、點燃跳一次、一下沒有名字的
        meter.dealt(0, "瘴苞雨", java.util.Map.of(tw.exilecore.stats.DamageType.PHYSICAL, 600.0, tw.exilecore.stats.DamageType.FIRE, 200.0));
        meter.dealt(20, "瘴苞雨", java.util.Map.of(tw.exilecore.stats.DamageType.PHYSICAL, 600.0, tw.exilecore.stats.DamageType.FIRE, 200.0));
        meter.dealt(25, "召喚骸骨兵", java.util.Map.of(tw.exilecore.stats.DamageType.PHYSICAL, 300.0));
        meter.dealtDot(30, tw.exilecore.ailment.AilmentType.IGNITE, tw.exilecore.stats.DamageType.FIRE, 100.0);
        meter.dealt(35, java.util.Map.of(tw.exilecore.stats.DamageType.COLD, 50.0));
        final CombatMeter.Snapshot snapshot = meter.snapshot(39);
        org.junit.jupiter.api.Assertions.assertEquals(2.0, snapshot.seconds(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(2050 / 2.0, snapshot.dps(), 1e-9, "總數照舊：全部加起來");
        org.junit.jupiter.api.Assertions.assertEquals(3, snapshot.skills().size(), "沒有名字的那一下不列");
        org.junit.jupiter.api.Assertions.assertEquals("瘴苞雨", snapshot.skills().get(0).name());
        org.junit.jupiter.api.Assertions.assertEquals(800.0, snapshot.skills().get(0).total(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(600.0, snapshot.skills().get(0).amounts()[tw.exilecore.stats.DamageType.PHYSICAL.ordinal()], 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(200.0, snapshot.skills().get(0).amounts()[tw.exilecore.stats.DamageType.FIRE.ordinal()], 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals("召喚骸骨兵", snapshot.skills().get(1).name());
        org.junit.jupiter.api.Assertions.assertEquals(CombatMeter.dotName(tw.exilecore.ailment.AilmentType.IGNITE), snapshot.skills().get(2).name());

        // 側欄：技能明細一個技能兩行（名字＋佔比、類型），沒開就是原本的樣子
        final java.util.List<String> detail = CombatMeter.lines(snapshot, true);
        org.junit.jupiter.api.Assertions.assertTrue(detail.get(2).contains("瘴苞雨") && detail.get(2).contains("800") && detail.get(2).contains("78%"), detail.get(2));
        org.junit.jupiter.api.Assertions.assertTrue(detail.get(3).contains("物理 600") && detail.get(3).contains("火焰 200"), detail.get(3));
        org.junit.jupiter.api.Assertions.assertTrue(detail.get(detail.size() - 1).contains("承受"));
        org.junit.jupiter.api.Assertions.assertTrue(detail.size() <= tw.exilecore.gui.SidebarLayout.MAX_ROWS + 1);
        org.junit.jupiter.api.Assertions.assertEquals(CombatMeter.lines(snapshot), CombatMeter.lines(snapshot, false));
        org.junit.jupiter.api.Assertions.assertTrue(CombatMeter.lines(snapshot).stream().noneMatch(line -> line.contains("瘴苞雨")));

        // 整場統計：總傷害（不是每秒）
        final CombatMeter.Fight fight = meter.fight();
        org.junit.jupiter.api.Assertions.assertEquals(2000.0, fight.total(), 1e-9, "有名字的加起來");
        org.junit.jupiter.api.Assertions.assertEquals(1600.0, fight.skills().get(0).total(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(36 / 20.0, fight.seconds(), 1e-9);
        final java.util.List<String> report = CombatMeter.report(fight);
        org.junit.jupiter.api.Assertions.assertTrue(report.get(1).contains("瘴苞雨") && report.get(1).contains("80%"), report.get(1));
        org.junit.jupiter.api.Assertions.assertTrue(report.get(2).contains("物理 1,200") && report.get(2).contains("75%"), report.get(2));

        // 很多技能：只列前幾個，其他併一行；視窗過了的技能清掉；停手之後重新開打整場歸零
        final CombatMeter many = new CombatMeter();
        for (int i = 0; i < 7; i++) {
            many.dealt(0, "技能" + i, java.util.Map.of(tw.exilecore.stats.DamageType.PHYSICAL, 100.0 * (i + 1)));
        }
        final java.util.List<String> crowded = CombatMeter.lines(many.snapshot(10), true);
        org.junit.jupiter.api.Assertions.assertTrue(crowded.stream().anyMatch(line -> line.contains("其他 3 個")), crowded.toString());
        org.junit.jupiter.api.Assertions.assertTrue(crowded.size() <= tw.exilecore.gui.SidebarLayout.MAX_ROWS + 1, "側欄放得下");
        org.junit.jupiter.api.Assertions.assertTrue(many.snapshot(CombatMeter.WINDOW_TICKS + 20).skills().isEmpty());
        many.dealt(1000, "技能0", java.util.Map.of(tw.exilecore.stats.DamageType.PHYSICAL, 10.0));
        org.junit.jupiter.api.Assertions.assertEquals(10.0, many.fight().total(), 1e-9);
        org.junit.jupiter.api.Assertions.assertTrue(CombatMeter.report(new CombatMeter().fight()).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals("很長很長的技能…", CombatMeter.shorten("很長很長的技能名字"));
    }
}
