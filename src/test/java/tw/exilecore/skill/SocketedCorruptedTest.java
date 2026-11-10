package tw.exilecore.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

/** 2026-11-10 照站長「腐化 +1 的衣服、放進引星的技能」：插槽裡已腐化的寶石也吃，脫下就沒有。 */
class SocketedCorruptedTest {

    @Test
    void socketedCorruptedGemFollowsCurrentGear() {
        final StatSheet wearing = new StatSheet();
        wearing.add(Modifier.flat(tw.exilecore.item.unique.VaalKeys.GEM_CORRUPTED_LEVEL, 1, "祭紋華袍"));
        assertEquals(1, SkillService.socketedGem(wearing, null, 0, List.of(), true).sheet().flat(StatKeys.SKILL_LEVEL, java.util.Set.of()), 1e-9);
        assertEquals(0, SkillService.socketedGem(wearing, null, 0, List.of(), false).sheet().flat(StatKeys.SKILL_LEVEL, java.util.Set.of()), 1e-9,
                "沒腐化的不吃");
        assertEquals(0, SkillService.socketedGem(new StatSheet(), null, 0, List.of(), true).sheet().flat(StatKeys.SKILL_LEVEL, java.util.Set.of()), 1e-9,
                "換回原本的衣服：沒有");
    }
}
