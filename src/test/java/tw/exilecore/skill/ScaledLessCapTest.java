package tw.exilecore.skill;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-31：萎力詛咒配一堆詛咒效果變成「124% 更少傷害」，倍率變負的反而沒減傷。 */
class ScaledLessCapTest {

    @Test
    void scaledLessIsCappedAndNeverNegative() {
        final ScaledMod enfeeble = new ScaledMod("damage", ModType.MORE, Scaled.of(-34), true, Set.of());
        assertEquals(-0.75, enfeeble.toModifier(30, 3.65, "").value(), 1e-9, "放大後最多 75% 更少");
        assertEquals(-0.34, enfeeble.toModifier(30, 1.0, "").value(), 1e-9, "沒放大照原值");
        final ScaledMod none = new ScaledMod("damage", ModType.MORE, Scaled.of(-100), true, Set.of());
        assertEquals(-1.0, none.toModifier(1, 2.0, "").value(), 1e-9, "本來就 -100% 的照寫的");
        final StatSheet sheet = new StatSheet();
        final StatSheet over = sheet.overlay(List.of(Modifier.more("damage", -1.24, "")));
        assertEquals(0, over.more("damage", Set.of()), 1e-9, "超過 100% 更少當作 0，不會變負的");
    }
}
