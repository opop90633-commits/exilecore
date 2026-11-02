package tw.exilecore.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-02（C 組）：模組側邊的機制清單倒數用的鎖血機制時間。 */
class MechanicSecondsTest {

    @Test
    void secondsPerKind() {
        assertEquals(0, BossMechanicText.mechanicSeconds(null));
        assertEquals(0, BossMechanicText.mechanicSeconds(PhaseMechanic.Spec.NONE));
        assertEquals(20, BossMechanicText.mechanicSeconds(PhaseMechanic.Spec.zones(new PhaseMechanic.Zones(3, 1, 1, 20, 10, 5, 50, 5))));
        assertEquals(12, BossMechanicText.mechanicSeconds(PhaseMechanic.Spec.countdown(new PhaseMechanic.Countdown("滅世", 12, 2, 3, 0.8, 0, 0))));
        assertEquals(15, BossMechanicText.mechanicSeconds(PhaseMechanic.Spec.element(new PhaseMechanic.Element(15, 10, 0.2, 5, 2, 0.1))));
    }
}
