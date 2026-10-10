package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /stats 與角色面板的「特殊效果」：同樣的加在一起、不同標籤分開、沒有就不列。 */
class SpecialEffectLinesTest {

    @Test
    void sameEffectsAddUpAndFlagsAreListed() {
        assertTrue(SpecialEffectLines.of(new StatSheet().add(Modifier.flat(StatKeys.LIFE_MAX, 50, "戒指"))).isEmpty());
        final StatSheet sheet = new StatSheet()
                .add(Modifier.flat(StatKeys.LIFE_ON_KILL, 3, "潮痕"))
                .add(Modifier.flat(StatKeys.LIFE_ON_KILL, 2, "流浪者"))
                .add(Modifier.flat(StatKeys.SKILL_LEVEL, 1, "天穹之心"))
                .add(new Modifier(StatKeys.SKILL_LEVEL, ModType.FLAT, 2, Set.of(Tags.SPELL, Tags.FIRE), "焚城"))
                .add(Modifier.flat(StatKeys.FLAG_CHAOS_IMMUNE, 1, "空殼"));
        assertEquals(List.of("+1 全部技能等級", "+2 火焰法術技能等級", "擊殺時回復 5% 最大生命", "免疫混沌傷害（最大生命 1）"),
                SpecialEffectLines.of(sheet));
    }

    @Test
    void chargeSourcesAreListedAndAddUp() {
        final StatSheet sheet = new StatSheet()
                .add(Modifier.flat(StatKeys.chargeChance("frenzy"), 15, "怒星之潮"))
                .add(Modifier.flat(StatKeys.chargeChance("frenzy"), 10, "戒指"))
                .add(Modifier.flat(StatKeys.chargeOnHit("frenzy"), 8, "劍"))
                .add(Modifier.flat(StatKeys.chargeMax("power"), 1, "頭盔"));
        assertEquals(List.of("+1 銳星上限", "擊殺時 25% 機率獲得一顆怒星", "擊中時 8% 機率獲得一顆怒星"), SpecialEffectLines.of(sheet),
                "照銳星、怒星、磐星的順序；同一種加在一起");
    }
}
