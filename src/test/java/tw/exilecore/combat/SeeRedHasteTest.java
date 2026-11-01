package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.SupportExtraKeys;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照 PoE2 See Red：流血的敵人移動速度增加（haste. 減益）跟變慢合起來。 */
class SeeRedHasteTest {

    @Test
    void hasteDebuffSpeedsUpUnlessPinned() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        assertEquals(0, debuffs.haste(target, 0), 1e-9);
        debuffs.apply(target, new Debuffs.Debuff(SupportExtraKeys.BLEEDER_HASTE_DEBUFF, UUID.randomUUID(), 100, 0, false, null,
                List.of(Modifier.increased(StatKeys.ATTACK_SPEED, 0.15, "流血加速"), Modifier.increased(StatKeys.MOVEMENT_SPEED, 0.15, "流血加速")), 0));
        assertEquals(0.15, debuffs.haste(target, 50), 1e-9);
        assertEquals(0, debuffs.haste(target, 100), 1e-9, "流血結束就沒了");
        assertEquals(-0.15, HitEffects.netSlow(0, 0.15), 1e-9, "負的 = 變快");
        assertEquals(0.15, HitEffects.netSlow(0.3, 0.15), 1e-9);
        assertEquals(1, HitEffects.netSlow(1, 0.15), 1e-9, "定身照樣不能動");
    }
}
