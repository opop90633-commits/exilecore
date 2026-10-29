package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.monster.MonsterConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-27：終局選單（每隻 Boss 一顆按鈕）的排法，與建議每秒（算 Boss 元素抗性、各階遞增）。 */
class FinalBossMenuTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static MapConfig config;
    static MonsterConfig monsters;

    @BeforeAll
    static void load() throws IOException {
        config = new MapConfig();
        config.load(DATA);
        monsters = new MonsterConfig();
        monsters.load(DATA);
    }

    @Test
    void fourBossesSitEvenlyInTheSecondRow() {
        assertEquals(List.of(10, 12, 14, 16), FinalBossMenu.bossSlots(4));
        assertEquals(List.of(13), FinalBossMenu.bossSlots(1), "一隻放正中間");
        assertEquals(List.of(10, 11, 12, 13, 14, 15, 16), FinalBossMenu.bossSlots(7));
        final List<Integer> nine = FinalBossMenu.bossSlots(9);
        assertEquals(9, nine.size());
        assertEquals(9, new HashSet<>(nine).size(), "不重疊");
        assertTrue(nine.stream().allMatch(slot -> slot >= 9 && slot < 27 && slot % 9 > 0 && slot % 9 < 8), "第二、三排，左右留邊：" + nine);
    }

    @Test
    void regularFinalsAreTheFourBosses() {
        final List<MapConfig.MapDef> finals = config.regularFinals();
        assertEquals(4, finals.size(), "天穹熔爐、永凍王座、冥河渡口、餘燼堡壘");
        assertTrue(finals.stream().allMatch(map -> map.boss() != null));
    }

    @Test
    void recommendedDpsCountsBossResistance() {
        final MapConfig.MapDef crucible = config.finalMap();
        // 傳奇 30% 元素抗性：面板要多 1 ÷ 0.7
        assertEquals(0.7, MapService.resistFactor(monsters, crucible.boss().mods()), 1e-9);
        final MapConfig.Uber uber = config.uber(crucible.id());
        assertEquals(0.5, MapService.resistFactor(monsters, uber.merge(crucible.boss()).mods()), 1e-9, "極境 +20% = 50%");
        final int max = config.tiers().max();
        final int level = config.tiers().level(max) + config.finalLevelBonus();
        final double life = monsters.lifeAt(level) * monsters.rarity(tw.exilecore.monster.MonsterRarity.UNIQUE).lifeMultiplier()
                * crucible.boss().life() * config.finalBossLifeMultiplier(max);
        assertEquals(life / MapService.RECOMMENDED_SECONDS / 0.7,
                MapService.recommendedDps(monsters, crucible.boss(), level, config.finalBossLifeMultiplier(max)), 1e-6);
    }

    @Test
    void recommendedDpsGrowsWithTierAndStaysBelowUberAndPinnacle() {
        for (final MapConfig.MapDef map : config.regularFinals()) {
            double previous = 0;
            for (int tier = 1; tier <= config.tiers().max(); tier++) {
                final double dps = MapService.recommendedDps(monsters, map.boss(), config.tiers().level(tier) + config.finalLevelBonus(),
                        config.finalBossLifeMultiplier(tier));
                assertTrue(dps > previous, map.id() + " T" + tier + "：建議每秒要一階比一階高");
                previous = dps;
            }
            final MapConfig.Uber uber = config.uber(map.id());
            if (uber != null) {
                final double uberDps = MapService.recommendedDps(monsters, uber.merge(map.boss()),
                        config.uberStats(map.id(), uber).monsterLevel(), config.uberLifeMultiplier()); // 2026-10-27：再乘 boss_life.uber
                assertTrue(uberDps > previous, map.id() + " 極境要比 T" + config.tiers().max() + " 難");
            }
        }
    }

    /** 2026-10-27 照站長：建議每秒（技能面板、單人 3 分半）巔峰 100 萬、極境 50 萬、終局 T16 25 萬；T1 不比以前難。 */
    @Test
    void recommendedDpsMatchesTheOwnersTargets() {
        final int max = config.tiers().max();
        for (final MapConfig.MapDef map : config.regularFinals()) {
            final double top = MapService.recommendedDps(monsters, map.boss(), config.tiers().level(max) + config.finalLevelBonus(),
                    config.finalBossLifeMultiplier(max));
            assertEquals(500000, top, 5000, map.id() + " T" + max); // 2026-10-29 照站長：終局 Boss 血量 ×2（這次之前 25 萬）
            final double t1 = MapService.recommendedDps(monsters, map.boss(), config.tiers().level(1) + config.finalLevelBonus(),
                    config.finalBossLifeMultiplier(1));
            assertTrue(t1 < 20000, map.id() + " T1：2026-10-29 照站長 ×2（以前 0.92 萬）：" + t1);
            final MapConfig.Uber uber = config.uber(map.id());
            assertEquals(1000000, MapService.recommendedDps(monsters, uber.merge(map.boss()), config.uberStats(map.id(), uber).monsterLevel(),
                    config.uberLifeMultiplier()), 10000, map.id() + " 極境（2026-10-29 照站長 ×2，這次之前 50 萬）");
        }
        assertEquals(8, config.pinnacles().size(), "萬界之巔＋7 隻事件巔峰");
        for (final MapConfig.MapDef map : config.pinnacles()) {
            assertEquals(2000000, MapService.recommendedDps(monsters, map.boss(), // 2026-10-29 照站長 ×2（這次之前 100 萬）
                    config.pinnacleStats(map.id(), config.pinnacle(map.id())).monsterLevel(), config.pinnacleLifeMultiplier(map.id())),
                    10000, map.id() + " 巔峰");
        }
    }
}
