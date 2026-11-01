package tw.exilecore.ascendancy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01：生命痕跡、魔力痕跡照原作（直接吃到身上也不減半，站長決定改回來）；夏烏拉之炎照原作的數值（直接生效）。 */
class AutoRemnantTest {
    @Test
    void remnantsKeepPoe2Numbers() {
        assertEquals(0.25, AscGranted.MANA_REMNANT_KILL_CHANCE, 1e-9, "魔力痕跡：擊殺 25%");
        assertEquals(40, AscGranted.MANA_REMNANT_CRIT_TICKS, "魔力痕跡：暴擊最多每 2 秒一次");
        // 2026-11-01 照站長：夏烏拉之炎 1.4～1.21 秒一個、紫炎每層 7%、紅藍炎偷取 20%
        assertEquals(1.0, AscGranted.FLAME_INTERVAL_SCALE);
        assertEquals(7, AscGranted.FLAME_PURPLE);
        assertEquals(0.20, AscGranted.FLAME_LEECH, 1e-9);
    }
}
