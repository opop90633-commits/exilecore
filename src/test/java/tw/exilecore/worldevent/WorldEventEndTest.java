package tw.exilecore.worldevent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 2026-11-05 照站長「開天者的活動日期訂到 11/11，兌換延長 7 天」。 */
class WorldEventEndTest {

    private static long at(final int month, final int day, final int hour, final int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute).atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli();
    }

    @Test
    void eventsEndOn1111AndShopOn1118() throws Exception {
        final WorldEventConfig config = new WorldEventConfig();
        config.load(Path.of("src/main/resources/data"));
        assertFalse(config.eventsOver(at(11, 11, 23, 59)));
        assertTrue(config.eventsOver(at(11, 12, 0, 0)));
        assertFalse(config.shopClosed(at(11, 18, 23, 59)));
        assertTrue(config.shopClosed(at(11, 19, 0, 0)));
    }
}
