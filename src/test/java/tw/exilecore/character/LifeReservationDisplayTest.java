package tw.exilecore.character;

import org.junit.jupiter.api.Test;
import tw.exilecore.stats.LineageKeys;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 照 PoE2：貝達特改成真的保留生命；動作列、原版愛心的保留生命顯示。 */
class LifeReservationDisplayTest {

    @Test
    void beidatReservesLifeInsteadOfLoweringMaximum() {
        final StatSheet one = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.LIFE_MAX, 1000, "測試"),
                Modifier.flat(StatKeys.BEIDAT_HAND, 1, "貝達特之手")));
        assertEquals(1000, PlayerState.maxLife(one), 1e-6, "最大生命不變（這次之前 750）");
        assertEquals(25, PlayerState.innateReservedLifePercent(one), 1e-9);
        assertEquals(0.25, PlayerState.reservedLifeFraction(one), 1e-9);
        assertEquals(750, PlayerState.unreservedLife(one), 1e-6);
        assertEquals(250, PlayerState.reservedLife(one), 1e-6);

        final StatSheet all = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.LIFE_MAX, 1000, "測試"),
                Modifier.flat(StatKeys.BEIDAT_HAND, 1, "手"), Modifier.flat(StatKeys.BEIDAT_GAZE, 1, "眼"),
                Modifier.flat(StatKeys.BEIDAT_WILL, 1, "志"), Modifier.flat(LineageKeys.LIFE_RESERVED, 10, "血契保留")));
        assertEquals(1000, PlayerState.maxLife(all), 1e-6);
        assertEquals(75, PlayerState.innateReservedLifePercent(all), 1e-9);
        assertEquals(0.85, PlayerState.reservedLifeFraction(all), 1e-9, "貝達特 75% + 血契 10% 加起來");
        assertEquals(150, PlayerState.unreservedLife(all), 1e-6);

        final StatSheet none = new StatSheet().overlay(List.of(Modifier.flat(StatKeys.LIFE_MAX, 1000, "測試")));
        assertEquals(0, PlayerState.innateReservedLifePercent(none), 1e-9);
        assertEquals(0, PlayerState.reservedLife(none), 1e-9);
    }

    @Test
    void actionBarShowsReservedLife() {
        assertEquals("", PlayerStateService.reservedLifeSuffix(0));
        assertEquals("", PlayerStateService.reservedLifeSuffix(0.3));
        assertEquals("（保留 200）", PlayerStateService.reservedLifeSuffix(200));
        assertEquals("（保留 1,250）", PlayerStateService.reservedLifeSuffix(1250));
    }

    @Test
    void vanillaHeartsFollowUnreservedLife() {
        assertEquals(1.0, PlayerStateService.vanillaHeartFraction(800, 800), 1e-9, "沒被保留的回滿＝滿心");
        assertEquals(0.5, PlayerStateService.vanillaHeartFraction(400, 800), 1e-9);
        assertEquals(1.0, PlayerStateService.vanillaHeartFraction(900, 800), 1e-9);
        assertEquals(0, PlayerStateService.vanillaHeartFraction(100, 0), 1e-9);
    }
}
