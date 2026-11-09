package tw.exilecore.combat;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatSheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-11-07 照站長「瓦爾傳奇照原作」：存在範圍內的敵人閃電抗性等於你的。 */
class PresenceEqualResistTest {
    @Test
    void setsMonsterResistToYours() {
        final StatSheet monster = new StatSheet().setBase(DamageType.LIGHTNING.mitigationStat(), 40);
        final Modifier mod = Presence.equalResist(monster, DamageType.LIGHTNING, -20);
        assertEquals(-20, monster.overlay(java.util.List.of(mod)).get(DamageType.LIGHTNING.mitigationStat()), 1e-9);
        assertNull(Presence.equalResist(monster, DamageType.LIGHTNING, 40), "一樣就不用改");
    }
}
