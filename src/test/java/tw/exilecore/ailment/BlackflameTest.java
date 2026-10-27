package tw.exilecore.ailment;

import org.junit.jupiter.api.Test;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.combat.HitEffects;
import tw.exilecore.item.unique.UniqueStats;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-28 照 PoE2 Blackflame（黯炎）：混沌點燃、凋零也加受到的火焰傷害、點燃中的敵人身上凋零不會失效。 */
class BlackflameTest {

    @Test
    void chaosIgniteUsesChaosResistance() {
        assertFalse(AilmentService.chaosIgnite(new StatSheet()), "沒有黯炎是火焰點燃");
        final StatSheet attacker = new StatSheet().add(Modifier.flat(UniqueStats.IGNITE_AS_CHAOS, 1, "黯炎"));
        assertTrue(AilmentService.chaosIgnite(attacker));
        final StatSheet monster = new StatSheet().add(Modifier.flat(StatKeys.RESIST_MAX, 75, "x"))
                .add(Modifier.flat(DamageType.FIRE.mitigationStat(), 50, "x"))
                .add(Modifier.flat(DamageType.CHAOS.mitigationStat(), 20, "x"));
        assertEquals(0.5, AilmentService.igniteResist(monster, DamageType.FIRE), 1e-9, "火焰點燃吃火焰抗性");
        assertEquals(0.8, AilmentService.igniteResist(monster, DamageType.CHAOS), 1e-9, "混沌點燃吃混沌抗性");
        final StatSheet immune = new StatSheet().add(Modifier.flat(StatKeys.FLAG_CHAOS_IMMUNE, 1, "x"));
        assertEquals(0, AilmentService.igniteResist(immune, DamageType.CHAOS), 1e-9, "混沌免疫的不受混沌點燃的傷害");
    }

    @Test
    void chaosIgniteKeepsItsTypeOnTheState() {
        final AilmentState state = new AilmentState();
        state.addIgnite(10, 100, null, true);
        assertTrue(state.ignite(0).chaos());
        assertEquals(DamageType.CHAOS, state.ignite(0).damageType());
        final AilmentState fire = new AilmentState();
        fire.addIgnite(10, 100, null);
        assertEquals(DamageType.FIRE, fire.ignite(0).damageType());
    }

    @Test
    void witheredAlsoAddsFireTakenOnlyForThatSource() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID blackflame = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        HitEffects.addWithered(debuffs, target, blackflame, 0, 3, HitEffects.WITHERED_TICKS);
        assertEquals(0, debuffs.typedTaken(target, blackflame, 1, DamageType.FIRE), 1e-9, "沒掛上來之前凋零只加混沌");
        debuffs.setWitherFireSources(blackflame::equals);
        assertEquals(0.15, debuffs.typedTaken(target, blackflame, 1, DamageType.FIRE), 1e-9, "3 層凋零：受到的火焰傷害也增加 15%");
        assertEquals(0.15, debuffs.typedTaken(target, other, 1, DamageType.FIRE), 1e-9, "凋零不是只對施加者，誰打都算");
        assertEquals(0.15, debuffs.typedTaken(target, blackflame, 1, DamageType.CHAOS), 1e-9, "混沌照舊");
        assertEquals(0, debuffs.typedTaken(target, blackflame, 1, DamageType.COLD), 1e-9);

        final UUID target2 = UUID.randomUUID();
        HitEffects.addWithered(debuffs, target2, other, 0, 2, HitEffects.WITHERED_TICKS);
        assertEquals(0, debuffs.typedTaken(target2, blackflame, 1, DamageType.FIRE), 1e-9, "別人施加的凋零不加火焰");
    }

    @Test
    void witheredHeldWhileIgnitedResumesAfterwards() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID source = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        HitEffects.addWithered(debuffs, target, source, 0, 2, HitEffects.WITHERED_TICKS); // 到 80 tick
        HitEffects.addWithered(debuffs, target, other, 0, 1, HitEffects.WITHERED_TICKS);
        // 點燃 10 秒：每 10 tick 延 10 tick，等於暫停
        for (long now = 0; now < 200; now += 10) {
            assertTrue(debuffs.hasWitheredFrom(target, source, now));
            debuffs.holdWithered(target, source, now, 10);
        }
        assertEquals(2, HitEffects.stacks(debuffs, target, "withered.", 200) , "點燃期間自己的凋零沒有失效（別人的已經沒了）");
        assertFalse(debuffs.hasWitheredFrom(target, other, 200));
        // 點燃結束後照剩下的 80 tick 倒數
        assertEquals(2, HitEffects.stacks(debuffs, target, "withered.", 279));
        assertEquals(0, HitEffects.stacks(debuffs, target, "withered.", 280));
    }
}
