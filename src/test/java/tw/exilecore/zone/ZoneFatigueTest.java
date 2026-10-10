package tw.exilecore.zone;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防刷：疲勞到 free 之前全額，之後降到 floor；會恢復；每少一級提示一次；Boss 算比較多隻。 */
class ZoneFatigueTest {

    private static final ZoneFatigue.Settings SETTINGS = new ZoneFatigue.Settings(true, 500, 1000, 0.2, 15, 50);

    @Test
    void shippedZonesFileMatchesTheDefaults() throws Exception {
        final ZoneConfig config = new ZoneConfig();
        config.load(Path.of("src/main/resources/data"));
        assertEquals(ZoneFatigue.Settings.defaults(), config.fatigue(), "zones.yml 的 fatigue 跟程式的預設值一樣（舊的 zones.yml 沒有這段時行為一樣）");
        assertEquals(SETTINGS, config.fatigue());
    }

    @Test
    void multiplierCurve() {
        assertEquals(1, ZoneFatigue.multiplier(0, SETTINGS));
        assertEquals(1, ZoneFatigue.multiplier(500, SETTINGS));
        assertEquals(0.6, ZoneFatigue.multiplier(1000, SETTINGS), 1e-9, "走了一半：100% 到 20% 的中間");
        assertEquals(0.2, ZoneFatigue.multiplier(1500, SETTINGS), 1e-9);
        assertEquals(0.2, ZoneFatigue.multiplier(99_999, SETTINGS), 1e-9, "最低就是 floor");
        assertEquals(1, ZoneFatigue.multiplier(99_999, new ZoneFatigue.Settings(false, 500, 1000, 0.2, 15, 50)), "關掉");
    }

    @Test
    void decayRecoversPerMinute() {
        assertEquals(485, ZoneFatigue.decay(500, 60_000, 15), 1e-9);
        assertEquals(0, ZoneFatigue.decay(100, 60 * 60_000L, 15), "恢復完了不會變負的");
        assertEquals(100, ZoneFatigue.decay(100, -5, 15), "時間倒退當作沒過");
    }

    @Test
    void farmingTapersAndAnnouncesEachLevelOnce() {
        final ZoneFatigue fatigue = new ZoneFatigue();
        final UUID account = UUID.randomUUID();
        int announcements = 0;
        double last = 1;
        long now = 0;
        // 每秒殺一隻、殺 3000 隻（每秒恢復 0.25，淨增 0.75：第 2000 隻左右到底）
        for (int i = 0; i < 3000; i++) {
            final ZoneFatigue.Kill kill = fatigue.onKill(account, "mountain", 1, now, SETTINGS);
            assertTrue(kill.multiplier() <= last + 1e-9, "只會越來越少");
            last = kill.multiplier();
            if (kill.announce()) {
                announcements++;
            }
            now += 1000;
        }
        assertEquals(0.2, last, 1e-9);
        assertEquals(4, announcements, "100% 以下、80%、60%、40% 以下各一次（每 20% 一級）");
        assertEquals(1, fatigue.current(UUID.randomUUID(), "mountain", now, SETTINGS), "別的帳號不受影響");
        assertEquals(1, fatigue.current(account, "coast", now, SETTINGS), "別的區域不受影響");
        // 離開兩小時：恢復 1800 點，剩 450（不到 500）＝全額，再殺不會馬上提示
        now += 2 * 60 * 60_000L;
        assertEquals(1, fatigue.current(account, "mountain", now, SETTINGS));
        final ZoneFatigue.Kill fresh = fatigue.onKill(account, "mountain", 1, now, SETTINGS);
        assertEquals(1, fresh.multiplier());
        assertFalse(fresh.announce());
    }

    @Test
    void bossCountsAsManyKillsAndPruneDropsRecoveredStates() {
        final ZoneFatigue fatigue = new ZoneFatigue();
        final UUID account = UUID.randomUUID();
        for (int i = 0; i < 11; i++) {
            fatigue.onKill(account, "mountain", SETTINGS.bossWeight(), 0, SETTINGS);
        }
        // 11 × 50 = 550 點：已經超過 500
        assertTrue(fatigue.current(account, "mountain", 0, SETTINGS) < 1);
        assertEquals(1, fatigue.size());
        fatigue.prune(10 * 60 * 60_000L, SETTINGS);
        assertEquals(0, fatigue.size(), "十小時後恢復完了，清掉");
        fatigue.onKill(account, "mountain", 1, 0, SETTINGS);
        fatigue.reset(account);
        assertEquals(0, fatigue.size());
    }
}
