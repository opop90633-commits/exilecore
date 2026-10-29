package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-10-29 照 PoE2：巨靈的指令（和帶出來的分身、熔岩浪、甲蟲）照巨靈的數值算。 */
class DjinnCommandsTest {

    @Test
    void commandsBelongToTheirDjinn() {
        assertEquals("kelari_djinn", DjinnCommands.djinnFor("kelari_deception"));
        assertEquals("kelari_djinn", DjinnCommands.djinnFor("asc_kelari_clone"));
        assertEquals("kelari_djinn", DjinnCommands.djinnFor("asc_corpse_beetle"));
        assertEquals("navira_djinn", DjinnCommands.djinnFor("navira_fracturing"));
        assertEquals("ruzhan_djinn", DjinnCommands.djinnFor("ruzhan_trap.tornado"));
        assertEquals("ruzhan_djinn", DjinnCommands.djinnFor("asc_ruzhan_wave"));
    }

    @Test
    void othersAreYours() {
        assertNull(DjinnCommands.djinnFor("kelari_djinn"));
        assertNull(DjinnCommands.djinnFor("killing_palm"));
        assertNull(DjinnCommands.djinnFor(null));
    }
}
