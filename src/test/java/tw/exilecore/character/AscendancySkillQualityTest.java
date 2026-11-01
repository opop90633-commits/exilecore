package tw.exilecore.character;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 2026-11-01 照站長：職業昇華賦予的技能品質固定 20%；武器賦予的照武器品質。 */
class AscendancySkillQualityTest {

    @Test
    void ascendancySkillsAlwaysHaveTwentyQuality() {
        final Map<String, Integer> quality = PlayerStateService.ascendancyQuality(
                Map.of("staff_skill", 13), Set.of("staff_skill", "wand_skill"), Set.of("staff_skill", "wand_skill", "asc_skill"));
        assertEquals(20, PlayerStateService.ASCENDANCY_SKILL_QUALITY);
        assertEquals(20, quality.get("asc_skill"), "昇華賦予的：固定 20%");
        assertEquals(13, quality.get("staff_skill"), "武器賦予的：照武器的品質");
        assertEquals(null, quality.get("wand_skill"), "武器沒有品質：沒有品質");
    }
}
