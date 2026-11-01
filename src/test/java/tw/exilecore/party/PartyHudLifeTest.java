package tw.exilecore.party;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** 2026-11-01 照 PoE2 保留生命：隊伍框的血條分成目前生命、空的、被保留的三段（加起來 84 格）。 */
class PartyHudLifeTest {

    @Test
    void reservedPartIsSeparateFromMissingLife() {
        assertArrayEquals(new double[] {84, 0, 0}, PartyHud.lifeSegments(1000, 1000, 1000), 1e-9, "沒保留、滿血");
        assertArrayEquals(new double[] {42, 42, 0}, PartyHud.lifeSegments(500, 1000, 1000), 1e-9, "沒保留、半血");
        assertArrayEquals(new double[] {63, 0, 21}, PartyHud.lifeSegments(750, 750, 1000), 1e-9, "保留 25%、沒保留的滿了");
        assertArrayEquals(new double[] {42, 21, 21}, PartyHud.lifeSegments(500, 750, 1000), 1e-9, "保留 25%、500/1000");
        assertArrayEquals(new double[] {0, 84, 0}, PartyHud.lifeSegments(0, 0, 0), 1e-9, "最大生命 0");
    }
}
