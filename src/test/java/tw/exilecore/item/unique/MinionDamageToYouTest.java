package tw.exilecore.item.unique;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-08 照站長（PoE2 Chober Chaber，信徒之錘）：召喚物傷害的增加、減少也套用在你身上，面板也看得到。 */
class MinionDamageToYouTest {

    @Test
    void minionIncreasesAndReductionsAlsoApplyToYou() {
        final StatSheet sheet = new StatSheet();
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 1.24, Set.of(Tags.MINION), "權杖"));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, -0.10, Set.of(Tags.MINION, "command"), "減少"));
        sheet.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, 0.30, Set.of(Tags.MINION), "更多不算"));
        sheet.add(Modifier.increased(StatKeys.DAMAGE, 0.20, "你自己的"));
        assertTrue(UniqueStats.minionDamageToYou(sheet).isEmpty(), "沒有信徒之錘不給");
        sheet.add(Modifier.flat(StatKeys.MINION_DAMAGE_AFFECTS_YOU, 1, "信徒之錘"));
        sheet.addAll(UniqueStats.minionDamageToYou(sheet));
        assertEquals(0.20 + 1.24, sheet.increased(StatKeys.DAMAGE, Set.of(Tags.ATTACK)), 1e-9);
        assertEquals(0.20 + 1.24 - 0.10, sheet.increased(StatKeys.DAMAGE, Set.of("command")), 1e-9);
        assertEquals(1.0, sheet.more(StatKeys.DAMAGE, Set.of(Tags.ATTACK)), 1e-9, "更多不套用");
    }
}
