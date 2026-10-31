package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-10-29 照 PoE2：巨靈的指令（和帶出來的分身、熔岩浪、甲蟲）照巨靈的數值算。 */
class DjinnCommandsTest {

    @Test
    void commandsBelongToTheirDjinn() {
        assertEquals("foul_sand_spirit", DjinnCommands.djinnFor("sand_spirit_trick"));
        assertEquals("foul_sand_spirit", DjinnCommands.djinnFor("asc_kelari_clone"));
        assertEquals("foul_sand_spirit", DjinnCommands.djinnFor("asc_corpse_beetle"));
        assertEquals("final_mirage_spirit", DjinnCommands.djinnFor("mirage_spirit_freeze"));
        assertEquals("flame_blade_spirit", DjinnCommands.djinnFor("blade_spirit_device.tornado"));
        assertEquals("flame_blade_spirit", DjinnCommands.djinnFor("asc_ruzhan_wave"));
    }

    @Test
    void othersAreYours() {
        assertNull(DjinnCommands.djinnFor("foul_sand_spirit"));
        assertNull(DjinnCommands.djinnFor("soul_sever_palm"));
        assertNull(DjinnCommands.djinnFor(null));
    }
}
