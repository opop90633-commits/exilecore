package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-29 照站長：技能面板的品質照實際生效的算（寶石＋昇華「技能寶石品質」＋天賦樹）。 */
class SkillQualityDisplayTest {

    @Test
    void panelShowsGemPlusAscendancyQuality() {
        final StatSheet sheet = new StatSheet();
        sheet.add(Modifier.flat(tw.exilecore.ascendancy.AscKeys.SKILL_QUALITY, 2, "技能寶石品質"));
        sheet.add(Modifier.flat(tw.exilecore.ascendancy.AscKeys.SKILL_QUALITY, 2, "技能寶石品質"));
        assertEquals(4, SkillService.ascendancyQuality(sheet));
        assertEquals(4, SkillService.extraQuality(sheet));
        final SkillLoadout.Entry gem = new SkillLoadout.Entry("blazing_star_shell", 10).withQuality(10);
        assertEquals(" <#8888ff>品質 14%", SkillService.qualityTag(gem, SkillService.extraQuality(sheet)));
        assertEquals(" <#8888ff>品質 10%", SkillService.qualityTag(gem));
        assertEquals("<gray>（寶石 10% ＋昇華 4%）", SkillService.qualitySources(gem, 4, 0));
        assertEquals("", SkillService.qualitySources(gem, 0, 0), "只有寶石自己的就不寫來源");
        final SkillLoadout.Entry plain = new SkillLoadout.Entry("blazing_star_shell", 10);
        assertEquals(" <#8888ff>品質 4%", SkillService.qualityTag(plain, 4), "沒有品質的寶石也照昇華的顯示");
        assertEquals("<gray>（昇華 4%）", SkillService.qualitySources(plain, 4, 0));
    }
}
