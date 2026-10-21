package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-21：只加某一種傷害的減益（凋息、縛根）：不算進整下的倍率，只算那一種傷害；技能有沒有那個標籤都一樣。 */
class DebuffsTypedTest {

    @Test
    void typedDebuffOnlyAddsItsDamageType() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID player = UUID.randomUUID();
        debuffs.apply(target, new Debuffs.Debuff("aura.withering_presence." + player, player, 100, 0.22, false, "chaos"));
        // 整下的倍率不算它（物理攻擊、混沌法術都一樣）
        assertEquals(1.0, debuffs.takenMultiplier(target, player, 10, Set.of("attack", "physical")), 1e-9);
        assertEquals(1.0, debuffs.takenMultiplier(target, player, 10, Set.of("spell", "chaos")), 1e-9);
        // 只加混沌，誰打的都算（不是只對施加者）
        assertEquals(0.22, debuffs.typedTaken(target, player, 10, DamageType.CHAOS), 1e-9);
        assertEquals(0.22, debuffs.typedTaken(target, null, 10, DamageType.CHAOS), 1e-9);
        assertEquals(0.0, debuffs.typedTaken(target, player, 10, DamageType.PHYSICAL), 1e-9);
        // 過期了就沒有
        assertEquals(0.0, debuffs.typedTaken(target, player, 200, DamageType.CHAOS), 1e-9);
    }

    @Test
    void untaggedDebuffStillScalesTheWholeHit() {
        final Debuffs debuffs = new Debuffs();
        final UUID target = UUID.randomUUID();
        final UUID player = UUID.randomUUID();
        debuffs.apply(target, new Debuffs.Debuff("warcry", player, 100, 0.1, false));
        assertEquals(1.1, debuffs.takenMultiplier(target, player, 10, Set.of("attack")), 1e-9);
        assertEquals(0.0, debuffs.typedTaken(target, player, 10, DamageType.CHAOS), 1e-9);
    }
}
