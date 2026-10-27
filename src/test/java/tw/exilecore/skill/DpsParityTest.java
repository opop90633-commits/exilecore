package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeMechanicKeys;
import tw.exilecore.stats.TreeNewMechanicKeys;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：技能每秒傷害、網頁跟戰鬥一樣（雙持輪流、夥伴傷害、痛極生力）。 */
class DpsParityTest {

    @Test
    void dualWieldAveragesByTime() {
        // 主手每秒 1 下、每秒 100；副手每秒 2 下、每秒 300（一下 150）：輪流兩下 1.5 秒打 250
        final SkillService.DpsRow main = new SkillService.DpsRow(0, 0, "x", "斬擊", 100, 120, 0, 100, 1, 0.1, 150);
        final SkillService.DpsRow off = new SkillService.DpsRow(0, 0, "x", "斬擊", 300, 300, 0, 150, 2, 0.2, 150);
        final SkillService.DpsRow both = SkillService.alternate(main, off);
        assertEquals(250 / 1.5, both.dps(), 1e-9);
        assertEquals(2 / 1.5, both.rate(), 1e-9);
        assertSame(main, SkillService.alternate(main, null));
    }

    @Test
    void companionDamageIsDerivedFromSheet() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.4, Set.of("minion", TreeMechanicKeys.COMPANION), "夥伴"));
        assertTrue(TreeMechanics.companionDamageModifiers(sheet).isEmpty(), "沒點那個天賦");
        sheet.add(Modifier.flat(TreeNewMechanicKeys.FLAG_COMPANION_DAMAGE_TO_YOU, 1, "天賦"));
        assertEquals(0.4, TreeMechanics.companionDamageModifiers(sheet).get(0).value(), 1e-9, "網頁、面板、戰鬥都從屬性表算");
    }

    @Test
    void skillDpsSheetIncludesPainAttunementAtFullLife() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(StatKeys.FLAG_PAIN_ATTUNEMENT, 1, "痛極生力"));
        assertEquals(0.7, SkillService.withLifeCrit(sheet).more(StatKeys.CRIT_BONUS, Set.of()), 1e-9, "照滿血估：暴擊傷害加成更少 30%");
    }
}
