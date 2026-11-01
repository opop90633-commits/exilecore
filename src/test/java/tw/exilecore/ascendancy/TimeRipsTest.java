package tw.exilecore.ascendancy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-11-01 照 PoE2 Moment of Vulnerability（乘隙之機）的時間殘影、Kelari's Deception 的連續使用。 */
class TimeRipsTest {

    @Test
    void formPrefersFreezeThenElectrocuteThenStun() {
        assertEquals(TimeRips.Form.FREEZE, TimeRips.form(true, true, true));
        assertEquals(TimeRips.Form.ELECTROCUTE, TimeRips.form(false, true, true));
        assertEquals(TimeRips.Form.STUN, TimeRips.form(false, false, true));
        assertNull(TimeRips.form(false, false, false), "沒有蓄勢待禁錮就不拉殘影");
    }

    @Test
    void sharedDamageIsSharePercent() {
        assertEquals(60, TimeRips.sharedDamage(100, 60), 1e-9);
        assertEquals(148, TimeRips.sharedDamage(100, 128 + 20), 1e-9, "40 級 128% + 品質 20%");
        assertEquals(0, TimeRips.sharedDamage(0, 60), 1e-9);
        assertEquals(0, TimeRips.sharedDamage(100, -1), 1e-9);
        assertEquals(200, TimeRips.IMMUNITY_TICKS);
    }

    @Test
    void deceptionSequenceCapsAt300Percent() {
        assertEquals(0, AscGranted.deceptionMore(0, 0), 1e-9);
        assertEquals(1.0, AscGranted.deceptionMore(2, 0), 1e-9);
        assertEquals(3.0, AscGranted.deceptionMore(6, 0), 1e-9);
        assertEquals(1.2, AscGranted.deceptionMore(2, 10), 1e-9, "品質 20%：每次 60%");
        assertEquals(3.0, AscGranted.deceptionMore(6, 10), 1e-9, "上限 300%");
    }
}
