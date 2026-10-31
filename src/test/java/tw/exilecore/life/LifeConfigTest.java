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
        // 等級：1 級起、80 級封頂（2026-10-23：50 → 80）；到 50 級總經驗大概是一個月的量
        assertEquals(1, config.levelOf(0));
        assertEquals(80, config.levelOf(Long.MAX_VALUE / 4));
        final long total = config.xpAt(50);
        assertTrue(total > 20_000 && total < 80_000, "到 50 級總共 " + total);
        assertTrue(config.xpAt(80) > total * 2, "到 80 級總共 " + config.xpAt(80));
        assertEquals(config.levelOf(config.xpAt(20)), 20);
        // 筏的大小照等級
        assertEquals(4, config.raftSize(1));
        assertEquals(4, config.raftSize(9));
        assertEquals(9, config.raftSize(10));
        assertEquals(31, config.raftSize(50));
        assertEquals(41, config.raftSize(80));
        assertArrayEquals(new int[] {-2, 1}, LifeService.extent(4));
        assertArrayEquals(new int[] {-4, 4}, LifeService.extent(9));
        assertArrayEquals(new int[] {-15, 15}, LifeService.extent(31));
        // 精力
        assertEquals(100, config.staminaMax(1));
        assertEquals(150, config.staminaMax(50));
        assertEquals(180, config.staminaMax(80));
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
        assertEquals("lantern_fish", config.rollFish(30, false, null, "shallow", 0, 0.999));
        // 2026-10-23：魚圖鑑：只有晚上、只在某種海象、只在某個海域的魚；筏燈讓晚上的魚變多
        assertTrue(config.fishing().fish().size() >= 13);
        assertEquals(null, config.fish("moon_eel").weather());
        assertTrue(config.fish("moon_eel").weight() == 0 && config.fish("moon_eel").nightWeight() > 0, "月影鰻只有晚上");
        assertTrue(config.fish("coral_fish").available(false, null, "shallow") && !config.fish("coral_fish").available(true, null, "shallow"));
        assertTrue(config.fish("fog_ghost").available(false, "fog", "shallow") && !config.fish("fog_ghost").available(false, null, "shallow"));
        assertTrue(config.fish("coelacanth").available(false, null, "deep") && !config.fish("coelacanth").available(false, null, "shallow"));
        int moon = 0;
        int moonLamps = 0;
        for (int i = 0; i < 10_000; i++) {
            moon += "moon_eel".equals(config.rollFish(80, true, null, "shallow", 0, i / 10_000.0)) ? 1 : 0;
            moonLamps += "moon_eel".equals(config.rollFish(80, true, null, "shallow", 4, i / 10_000.0)) ? 1 : 0;
        }
        assertTrue(moonLamps > moon, moon + " → " + moonLamps);
        for (int i = 0; i < 100; i++) {
            assertTrue(!"moon_eel".equals(config.rollFish(80, false, null, "shallow", 4, i / 100.0)));
        }
        final LifeConfig.Fish mackerel = config.fish("blue_mackerel");
        assertEquals(mackerel.size()[0], LifeConfig.fishSize(mackerel, 0), 0.01);
        assertEquals(mackerel.size()[1], LifeConfig.fishSize(mackerel, 1), 0.01);
        assertTrue(LifeConfig.fishSize(mackerel, 0.5) < (mackerel.size()[0] + mackerel.size()[1]) / 2.0, "大魚比較少");
        assertTrue(config.shark() != null);
        assertTrue(config.crops().get("sunberry").sprinkler());
        // 鉤子一階比一階遠
        for (int i = 1; i < config.hooks().size(); i++) {
            assertTrue(config.hook(i).range() > config.hook(i - 1).range());
        }
        // 第三階段：島照等級排（原本四座）、環礁掉號角碎片；海象抽得到；12 封瓶中信；稱號
        assertTrue(config.islands().keySet().containsAll(LifeConfig.ISLAND_KINDS));
        assertEquals(8, config.islands().size()); // 2026-10-23：補給島、鯊巢礁、藏寶島、淵底遺跡
        for (final LifeConfig.Island island : config.islands().values()) {
            assertTrue(LifeConfig.ISLAND_KINDS.contains(island.shape()), island.id());
            assertTrue(!island.loot().isEmpty(), island.id());
        }
        assertEquals(java.util.Map.of("treasure_map", 1), config.islands().get("treasure").needs());
        assertEquals("wreck", config.islands().get("treasure").shape());
        assertEquals(0, config.islands().get("supply").count()[1], "補給島沒有怪");
        assertEquals("ruin", config.islands().get("deep_ruin").shape());
        assertTrue(config.islands().get("atoll").hornShard() > 0);
        assertTrue(config.islands().get("atoll").captain() != null);
        assertTrue(config.islands().get("ruin").letter());
        // 2026-10-27：寶箱的通貨照權重抽（淵底遺跡：至臻 50、天穹碎片 44，神諭石、大鑿星石、深淵星核各 2）；寫成清單的島每種一樣
        final LifeConfig.Island deep = config.islands().get("deep_ruin");
        assertEquals(5, deep.currencies().size());
        assertEquals("zenith_orb", deep.pickCurrency(0.0));
        assertEquals("abyss_core", deep.pickCurrency(0.99));
        int high = 0;
        for (int i = 0; i < 1000; i++) {
            final String id = deep.pickCurrency((i + 0.5) / 1000);
            if (id.equals("oracle_orb") || id.equals("greater_socket_orb") || id.equals("abyss_core")) {
                high++;
            }
        }
        assertEquals(60, high, "三種高價的合計 6%");
        assertEquals("lustre_orb", config.islands().get("ruin").pickCurrency(0.5), "清單：每種 1/3");
        assertEquals("fog", config.rollWeather(0).id());
        assertEquals("seabirds", config.rollWeather(0.9999).id());
        // 2026-10-23：夜光潮只在晚上；漩渦、海鳥群的倍數
        for (int i = 0; i < 100; i++) {
            assertTrue(!"glow_tide".equals(config.rollWeather(i / 100.0, false).id()));
        }
        boolean glow = false;
        for (int i = 0; i < 100; i++) {
            glow |= "glow_tide".equals(config.rollWeather(i / 100.0, true).id());
        }
        assertTrue(glow);
        LifeConfig.Weather whirlpool = null;
        for (final LifeConfig.Weather weather : config.weathers()) {
            if (weather.id().equals("whirlpool")) {
                whirlpool = weather;
            }
        }
        assertTrue(whirlpool != null);
        assertEquals(20.0, whirlpool.boost().get("crate"));
        int crates = 0;
        for (int i = 0; i < 10_000; i++) {
            crates += config.rollDrift(i / 10_000.0, whirlpool.boost()).crate() ? 1 : 0;
        }
        assertTrue(crates > 500, "漩渦的漂流箱 " + crates);
        // 海域
        assertEquals(3, config.zones().size());
        assertEquals("shallow", config.zone(null).id());
        assertEquals("shallow", config.zone("nope").id());
        assertEquals(4.0, config.zone("starfall").boost().get("star_shard"));
        assertTrue(config.zone("deep").sharkLife() > 1 && config.zone("deep").level() > 1);
        final java.util.Map<String, Double> both = LifeConfig.combine(whirlpool.boost(), config.zone("deep").boost());
        assertEquals(30.0, both.get("crate"), 0.001); // 20 × 1.5
        assertEquals(2.0, both.get("scrap"), 0.001);
        // 委託：同一天同一個帳號抽到的一樣、不重複、只抽等級夠的
        assertEquals(3, config.ordersPerDay());
        final List<LifeConfig.OrderToday> a = config.ordersFor(123L, "20261023", 10);
        final List<LifeConfig.OrderToday> b = config.ordersFor(123L, "20261023", 10);
        assertEquals(3, a.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).def().id(), b.get(i).def().id());
            assertTrue(a.get(i).def().level() <= 10, a.get(i).def().id());
            assertTrue(a.get(i).amount() >= a.get(i).def().amount()[0] && a.get(i).amount() <= a.get(i).def().amount()[1]);
        }
        assertEquals(3, a.stream().map(o -> o.def().id()).distinct().count());
        // 大賽、藏寶圖、設施、魚籠、鯊王、通貨配方、新鉤子、稱號
        assertEquals(3, config.tournamentRewards().size());
        assertTrue(config.treasureFromBottle() > 0 && config.materials().containsKey(LifeConfig.TREASURE_MAP));
        assertTrue(config.lampDrift() > 0 && config.sailDrift() > 0 && config.bedStamina() > 0);
        assertTrue(config.trapMinutes() > 0 && !config.trapFish().isEmpty());
        assertTrue(config.sharkKing() != null && config.sharkKing().drops().containsKey("abyss_pearl"));
        assertEquals(java.util.Map.of("sky_fragment", 2, "zenith_orb", 5), config.sharkKing().guaranteed());
        assertEquals("sky_crucible", config.sharkKing().like());
        assertTrue(config.recipes().stream().anyMatch(r -> "glassblower_bauble".equals(r.currency())));
        assertTrue(config.recipes().stream().anyMatch(r -> "shark_bait".equals(r.tool())));
        assertEquals(4, config.hooks().size());
        assertTrue(config.titles().keySet().containsAll(java.util.Set.of("sea_master", "angler", "fish_champion", "shark_slayer")));
        assertEquals("2026W43", config.week(java.time.ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, java.time.ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()));
        assertEquals("2026W44", config.week(java.time.ZonedDateTime.of(2026, 10, 26, 0, 30, 0, 0, java.time.ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()));
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
                "skill:summon_tide", "support:ebb_flow_resonance", "unique:undertow_coil", "unique:whalesong_pearl", "skill:whale_gulp_vortex",
                "support:dark_current_pull", "support:whale_exhale")), drops.toString()); // 2026-10-18：淵鯨再多 2 件傳奇、1 顆技能、2 顆輔助
        assertTrue(config.titles().containsKey("abyss_hunter"));
    }
}
