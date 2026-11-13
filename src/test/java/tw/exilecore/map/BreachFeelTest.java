package tw.exilecore.map;

import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.monster.MonsterRarity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-12 照站長「進度條，殺怪會延長；滿 100% 變成穩定裂隙，分波冒稀有怪（每波 5 隻），首領是維倫；專屬的巢生怪」（PoE2 0.5）。
 * 這次之前（同一天稍早）測的是 PoE1 手感版（圈自己長、殺怪推）。
 */
class BreachFeelTest {

    static final Path DATA = Path.of("src/main/resources/data");

    private static MapEventConfig config() throws IOException {
        final MapEventConfig config = new MapEventConfig();
        config.load(DATA);
        return config;
    }

    @Test
    void readsLordsAndHivebornWithRealMods() throws IOException {
        final MapEventConfig.BreachFeel feel = config().breachFeel();
        assertEquals(5, feel.lords().size());
        final MonsterConfig monsters = new MonsterConfig();
        monsters.load(DATA);
        for (final MapEventConfig.BreachLord lord : feel.lords()) {
            for (final String mod : lord.mods()) {
                assertNotNull(monsters.mod(mod), lord.id() + " 的 " + mod);
            }
        }
        for (final String mod : feel.vruunMods()) {
            assertNotNull(monsters.mod(mod), "維倫的 " + mod);
        }
        assertTrue(feel.hiveborn().size() >= 3, "專屬的巢生怪");
        for (final MapEventConfig.Hiveborn each : feel.hiveborn()) {
            assertNotNull(org.bukkit.entity.EntityType.valueOf(each.type()), each.type());
            assertTrue(each.name().startsWith("巢生"), each.name());
        }
        final Map<String, Integer> picked = new HashMap<>();
        final Random random = new Random(3);
        for (int i = 0; i < 3900; i++) {
            picked.merge(feel.pickLord(random).id(), 1, Integer::sum);
        }
        assertTrue(picked.get("xoph") > picked.get("chayula") * 2, "夏烏拉比較少見：" + picked);
    }

    @Test
    void stableBreachIsWavesOfFiveThenVruun() throws IOException {
        final MapEventConfig.BreachFeel feel = config().breachFeel();
        assertEquals(5, feel.stableWaveSize(), "PoE2 0.5.5：每波 5 隻");
        assertTrue(feel.stableWaves() >= 1);
        assertEquals("費許的元帥・維倫", feel.vruunName());
        assertTrue(feel.vruunLife() > 1);
        assertEquals(0, feel.maxHands(), "緊握之手刪除（2026-11-12）");
    }

    @Test
    void killingExtendsTheTimerAndFastKillersStabilise() throws IOException {
        final MapEventConfig.BreachFeel feel = config().breachFeel();
        assertTrue(feel.killTime(MonsterRarity.RARE, 0) > feel.killTime(MonsterRarity.MAGIC, 0));
        assertTrue(feel.killTime(MonsterRarity.MAGIC, 0) > feel.killTime(MonsterRarity.NORMAL, 0));
        assertTrue(feel.killTime(MonsterRarity.NORMAL, 0.5) > feel.killTime(MonsterRarity.NORMAL, 0), "星圖天賦「開得更久」");
        assertEquals(feel.capSeconds(), feel.addTime(feel.capSeconds() - 0.1, 5, 0), 1e-9, "最多累積到上限");
        final double fast = secondsToStable(feel, 5);
        assertTrue(fast > 0 && fast < 25, "一秒殺 5 隻會穩定：" + fast);
        assertEquals(-1, secondsToStable(feel, 1.5), 1e-9, "一秒殺 1.5 隻：時間先用完");
    }

    /** 照 MapEvents.tickBreach（每 10 tick 算一次）模擬：每秒殺 kills 隻一般怪，幾秒穩定；時間先用完回傳 -1。 */
    private static double secondsToStable(final MapEventConfig.BreachFeel feel, final double kills) {
        double left = feel.startSeconds();
        double points = 0;
        double t = 0;
        while (t < feel.maxSeconds()) {
            left = feel.addTime(left, feel.killTime(MonsterRarity.NORMAL, 0) * kills * 0.5, 0);
            points += feel.killPoints(MonsterRarity.NORMAL) * kills * 0.5;
            left -= 0.5;
            t += 0.5;
            if (points >= feel.needPoints()) {
                return t;
            }
            if (left <= 0) {
                return -1;
            }
        }
        return -1;
    }
}
