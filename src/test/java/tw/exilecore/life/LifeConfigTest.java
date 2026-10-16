package tw.exilecore.life;

import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/life.yml（2026-10 生活玩法「星骸之海」）。 */
class LifeConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");

    @Test
    void shippedLifeLoads() throws IOException {
        final LifeConfig config = new LifeConfig();
        config.load(DATA);
        assertTrue(config.enabled());
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        assertEquals(List.of(), config.validate(id -> currencies.get(id) != null, name -> org.bukkit.Material.matchMaterial(name) != null));
        // 等級：1 級起、50 級封頂；總經驗大概是一個月的量
        assertEquals(1, config.levelOf(0));
        assertEquals(50, config.levelOf(Long.MAX_VALUE / 4));
        final long total = config.xpAt(50);
        assertTrue(total > 20_000 && total < 80_000, "到 50 級總共 " + total);
        assertEquals(config.levelOf(config.xpAt(20)), 20);
        // 筏的大小照等級
        assertEquals(4, config.raftSize(1));
        assertEquals(4, config.raftSize(9));
        assertEquals(9, config.raftSize(10));
        assertEquals(31, config.raftSize(50));
        assertArrayEquals(new int[] {-2, 1}, LifeService.extent(4));
        assertArrayEquals(new int[] {-4, 4}, LifeService.extent(9));
        assertArrayEquals(new int[] {-15, 15}, LifeService.extent(31));
        // 精力
        assertEquals(100, config.staminaMax(1));
        assertEquals(150, config.staminaMax(50));
        // 漂流物抽得到每一種；筏的方塊拆得回來
        assertEquals("driftwood", config.rollDrift(0).id());
        assertEquals("crate", config.rollDrift(0.9999).id());
        for (final LifeConfig.Item item : config.items().values()) {
            assertEquals(item, config.itemByBlock(item.block()));
        }
        // 第二階段：料理、研究、作物、釣魚、鯊魚
        assertEquals(7, config.foods().size());
        for (final LifeConfig.Food food : config.foods().values()) {
            assertTrue(!food.stats().isEmpty(), food.id());
        }
        for (final LifeConfig.Recipe recipe : config.recipes()) {
            if (recipe.food() != null) {
                assertTrue(recipe.station() != null, recipe.id() + " 要在烤爐或料理台做");
            }
        }
        for (final String station : LifeConfig.STATIONS) {
            assertTrue(config.station(station) != null, "設施 " + station + " 要有方塊");
        }
        assertEquals("silver_fish", config.rollFish(1, false, 0.99), "1 級只釣得到銀鱗魚");
        assertEquals("lantern_fish", config.rollFish(30, false, 0.999));
        assertTrue(config.shark() != null);
        assertTrue(config.crops().get("sunberry").sprinkler());
        // 鉤子一階比一階遠
        for (int i = 1; i < config.hooks().size(); i++) {
            assertTrue(config.hook(i).range() > config.hook(i - 1).range());
        }
        // 第三階段：四座島照等級排、環礁掉號角碎片；海象抽得到；12 封瓶中信；稱號
        assertEquals(List.copyOf(LifeConfig.ISLAND_KINDS), List.copyOf(config.islands().keySet()));
        int last = 0;
        for (final LifeConfig.Island island : config.islands().values()) {
            assertTrue(island.level() > last, island.id());
            last = island.level();
            assertTrue(!island.loot().isEmpty(), island.id());
        }
        assertTrue(config.islands().get("atoll").hornShard() > 0);
        assertTrue(config.islands().get("atoll").captain() != null);
        assertTrue(config.islands().get("ruin").letter());
        assertEquals("fog", config.rollWeather(0).id());
        assertEquals("star_tide", config.rollWeather(0.9999).id());
        assertEquals(12, config.letters().size());
        assertTrue(config.titles().containsKey("letter_finder") && config.titles().containsKey("sea_wanderer"));
        // 星潮：星骸碎片的權重 ×8 → 抽到的機率變高
        int plain = 0;
        int boosted = 0;
        for (int i = 0; i < 10_000; i++) {
            plain += "star_shard".equals(config.rollDrift(i / 10_000.0).id()) ? 1 : 0;
            boosted += "star_shard".equals(config.rollDrift(i / 10_000.0, "star_shard", 8).id()) ? 1 : 0;
        }
        assertTrue(boosted > plain * 5, plain + " → " + boosted);
        // 淵鯨：比照巔峰（萬界之巔）、兩個階段、號角要環礁的碎片；專屬掉落都在
        final LifeConfig.Whale whale = config.whale();
        assertTrue(whale != null);
        assertEquals("awakener", whale.like());
        assertEquals(2, whale.phases().size());
        assertTrue(whale.phases().get(0).at() > whale.phases().get(1).at());
        assertTrue(whale.hornCost().containsKey("horn_shard"));
        assertTrue(whale.skills().stream().anyMatch(use -> use.fromPhase() == 2), "第二階段才有的招");
        final java.util.Set<String> drops = new java.util.HashSet<>();
        for (final LifeConfig.WhaleDrop drop : whale.drops()) {
            drops.add(drop.kind() + ":" + drop.id());
        }
        assertTrue(drops.containsAll(java.util.Set.of("unique:abyss_echo", "unique:tidewhisper_bow", "unique:tide_devourer",
                "skill:tide_call", "support:tidal_resonance")), drops.toString());
        assertTrue(config.titles().containsKey("abyss_hunter"));
    }
}
