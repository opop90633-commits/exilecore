package tw.exilecore.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-05 照站長「召喚物移動速度快一點會變得笨笨的」：找路的速度倍數照上限壓下來。 */
class MinionPathSpeedTest {

    @Test
    void capsOnlyFastMinions() {
        assertEquals(1.2, MinionService.pathSpeed(0.46, 1.2), 1e-9, "殭屍 ×2 照原本的倍數");
        assertEquals(MinionService.MAX_PATH_SPEED / 0.9, MinionService.pathSpeed(0.9, 1.5), 1e-9, "太快的壓到上限");
        assertEquals(0.5, MinionService.pathSpeed(5, 1.5), 1e-9, "最少 0.5 倍");
    }
}
