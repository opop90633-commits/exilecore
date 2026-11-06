package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.TreeKeys;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-06 照站長「點了免疫點燃會被反射嗎」：自焚反射回來的點燃也是「你被點燃」——免疫點燃擋得掉、
 * 身上的點燃幅度（自焚自己的 -40%）也要乘（AilmentService.reflectIgniteSelf）。
 */
class SelfImmolationReflectTest {

    @Test
    void immunityBlocksReflectedIgnite() {
        final StatSheet immune = new StatSheet().add(Modifier.flat("flag.immune.ignite", 1, "charm"));
        assertTrue(AilmentService.avoided(immune, StatKeys.AVOID_IGNITE, new Random(1)), "免疫點燃：反射回來的也不會燒");
        final StatSheet elemental = new StatSheet().add(Modifier.flat(StatKeys.FLAG_ELEMENTAL_AILMENT_IMMUNE, 1, "x"));
        assertTrue(AilmentService.avoided(elemental, StatKeys.AVOID_IGNITE, new Random(1)), "免疫元素異常同");
    }

    @Test
    void selfImmolationReducesReflectedMagnitude() {
        final StatSheet sheet = new StatSheet().add(new Modifier(TreeKeys.IGNITE_EFFECT_ON_SELF, ModType.INCREASED, -0.4, Set.of(), "p23630"));
        assertEquals(0.6, AilmentService.selfIgniteEffect(sheet), 1e-9, "自焚：減少 40% 身上的點燃幅度");
        assertEquals(1.0, AilmentService.selfIgniteEffect(new StatSheet()), 1e-9);
    }
}
