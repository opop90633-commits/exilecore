package tw.exilecore.monster;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTouchTest {

    @Test
    void lastTouchIsKeptEvenWithoutDamage() {
        final DamageLedger ledger = new DamageLedger();
        final UUID monster = UUID.randomUUID();
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        ledger.record(monster, a, 0);
        assertTrue(ledger.damageTo(monster).isEmpty());
        assertEquals(a, ledger.lastTouched(monster));
        ledger.touch(monster, b);
        assertEquals(b, ledger.lastTouched(monster));
        ledger.forget(monster);
        assertNull(ledger.lastTouched(monster));
    }
}
