package tw.exilecore.economy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.core.db.Database;
import tw.exilecore.item.Rarity;
import tw.exilecore.market.MarketSearch;

import java.io.StringReader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** data/economy.yml、手續費、商人與賭博的價格、交易紀錄、搜尋。 */
class EconomyTest {

    static Database database;
    static final TradeLogRepository log = new TradeLogRepository();

    @BeforeAll
    static void connect() throws SQLException {
        database = new Database(LoggerFactory.getLogger("test"), Path.of("build/tmp"));
        database.connect("jdbc:h2:mem:exilecore_economy;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @AfterAll
    static void close() {
        database.close();
    }

    @Test
    void realFileLoadsAndFeesFollowIt() throws Exception {
        final EconomyConfig config = new EconomyConfig();
        config.load(Path.of("src/main/resources/data"));
        assertEquals(1500, config.value("zenith_orb"));
        assertEquals(30, config.value("glimmer_orb"));
        assertTrue(config.value("turmoil_orb") > config.value("zenith_orb"), "亂流石比至臻石稀有（兩天 219 比 1339 顆）");
        assertTrue(config.value("annul_orb") > config.value("chance_orb"), "湮滅石比僥倖石稀有");
        assertEquals(0, config.value("identify_scroll"), "鑑定卷軸不能拿來標價");
        assertFalse(config.priceable("identify_scroll"));
        assertTrue(config.priceable("chance_orb"));
        // 上架 2%（至少 10）、購買 3%（至少 10）、交易所 2%（至少 5）
        assertEquals(300, config.listingFee("zenith_orb", 10), "10 個至臻石 = 15,000 金幣 × 2%");
        assertEquals(10, config.listingFee("glimmer_orb", 1), "便宜的東西收最低 10");
        assertEquals(450, config.purchaseFee("zenith_orb", 10));
        assertEquals(5, config.exchangeFee("glimmer_orb", 2), "2 × 30 × 2% 不到最低 5");
        assertEquals(500, config.exchangeFee("bounty_orb", 50), "50 × 500 × 2%");
        assertEquals(27, config.shopSlots());
        assertEquals(10 * 60_000L, config.relistMillis());
        // 商人：(20 + 4 × 物品等級) × 稀有度倍率
        assertEquals(60, config.vendorPrice(Rarity.NORMAL, 10));
        assertEquals(780, config.vendorPrice(Rarity.MAGIC, 60));
        assertEquals(2600, config.vendorPrice(Rarity.RARE, 60));
        assertEquals(18, config.stock(), "傳奇的倍率只用來估價，商人不賣傳奇");
        assertEquals(0, config.stock(Rarity.UNIQUE));
        assertEquals(600, config.vendorRefreshCost(60));
        // 賭博：100 + 25 × 等級
        assertEquals(125, config.gamblePrice(1));
        assertEquals(1600, config.gamblePrice(60));
    }

    @Test
    void shopAndReferenceSettingsMatchTheDefaults() throws Exception {
        final EconomyConfig config = new EconomyConfig();
        config.load(Path.of("src/main/resources/data"));
        assertEquals(ReferenceValues.Settings.defaults(), config.reference(), "economy.yml 的 reference 跟程式的預設值一樣");
        final EconomyConfig.Shop shop = config.shop();
        assertEquals(new EconomyConfig().shop(), shop, "economy.yml 的 shop 跟程式的預設值一樣（舊的 economy.yml 沒有這段時行為一樣）");
        assertTrue(shop.enabled());
        assertEquals(30, shop.flaskKills());
        assertEquals(80, shop.skillKills());
        assertEquals(18, shop.skillMaxLevel());
        assertEquals(Map.of(1, 100.0, 2, 200.0, 3, 400.0), shop.supportKills());
        assertEquals(List.of(EconomyConfig.UNLOCK_START, "coast", "forest", "ruins", "swamp", "mountain"), List.copyOf(shop.unlocks().keySet()));
        assertEquals(new EconomyConfig.Unlock(4, 0), shop.unlocks().get(EconomyConfig.UNLOCK_START));
        assertEquals(new EconomyConfig.Unlock(18, 3), shop.unlocks().get("mountain"));
        // 解鎖一區比一區多（不會打完後面的 Boss 反而變少）
        int skill = 0;
        int support = 0;
        for (final EconomyConfig.Unlock unlock : shop.unlocks().values()) {
            assertTrue(unlock.skillLevel() >= skill && unlock.supportTier() >= support, shop.unlocks().toString());
            skill = unlock.skillLevel();
            support = unlock.supportTier();
        }
        assertEquals(shop.skillMaxLevel(), skill, "打完最後一區能買到最高等級");
    }

    @Test
    void shopSectionParsesAndRejectsBadValues() {
        final EconomyConfig config = new EconomyConfig();
        config.load(YamlNode.load("economy.yml", new StringReader("""
                shop:
                  enabled: false
                  flask: { kills: 12 }
                  uncut_skill: { kills: 50, max_level: 10 }
                  uncut_support:
                    kills: { 1: 70 }
                  unlock:
                    start: { skill_level: 2 }
                    coast: { skill_level: 10, support_tier: 1 }
                """)));
        final EconomyConfig.Shop shop = config.shop();
        assertFalse(shop.enabled());
        assertEquals(12, shop.flaskKills());
        assertEquals(10, shop.skillMaxLevel());
        assertEquals(Map.of(1, 70.0), shop.supportKills(), "寫了就整份換掉");
        assertEquals(new EconomyConfig.Unlock(2, 0), shop.unlocks().get(EconomyConfig.UNLOCK_START));
        assertEquals(2, shop.unlocks().size());
        assertThrows(IllegalArgumentException.class, () ->
                config.load(YamlNode.load("economy.yml", new StringReader("shop:\n  uncut_skill: { max_level: 25 }\n"))));
        assertThrows(IllegalArgumentException.class, () ->
                config.load(YamlNode.load("economy.yml", new StringReader("shop:\n  uncut_support:\n    kills: { two: 5 }\n"))));
        assertThrows(IllegalArgumentException.class, () ->
                config.load(YamlNode.load("economy.yml", new StringReader("shop:\n  flask: { kills: -1 }\n"))));
        assertFalse(config.shop().enabled(), "寫錯時保留原本的");
    }

    @Test
    void referenceValuesFollowTheMarketOnlyWhenEnabled() {
        final EconomyConfig config = new EconomyConfig();
        config.load(YamlNode.load("economy.yml", new StringReader("values:\n  glimmer_orb: 10\n  zenith_orb: 1500\n")));
        assertFalse(config.followsMarket(), "還沒算過");
        config.setMarketValues(Map.of("glimmer_orb", 14L, "identify_scroll", 99L));
        assertTrue(config.followsMarket());
        assertEquals(14, config.value("glimmer_orb"), "用算出來的參考價值");
        assertEquals(10, config.configuredValue("glimmer_orb"));
        assertEquals(1500, config.value("zenith_orb"), "沒算到的用設定值");
        assertEquals(0, config.value("identify_scroll"), "設定檔裡沒有的通貨還是不能標價");
        assertEquals(140, config.value("glimmer_orb", 10));
        assertEquals(config.listingFee().of(1400), config.listingFee("glimmer_orb", 100), "手續費也照參考價值算");
        assertTrue(config.listingFee().of(1400) > config.listingFee().of(1000));
        // /ec reload：重讀設定檔不會清掉算好的參考價值
        config.load(YamlNode.load("economy.yml", new StringReader("values:\n  glimmer_orb: 11\n  zenith_orb: 1500\n")));
        assertEquals(14, config.value("glimmer_orb"));
        assertEquals(11, config.configuredValue("glimmer_orb"));
        // 關掉跟著市價：一律用設定值
        config.load(YamlNode.load("economy.yml", new StringReader("values:\n  glimmer_orb: 11\n  zenith_orb: 1500\nreference:\n  follow_market: false\n")));
        assertFalse(config.followsMarket());
        assertEquals(11, config.value("glimmer_orb"));
        // 基準通貨一定要有設定值
        assertThrows(IllegalArgumentException.class, () ->
                config.load(YamlNode.load("economy.yml", new StringReader("values:\n  glimmer_orb: 10\nreference:\n  anchor: zenith_orb\n"))));
        assertThrows(IllegalArgumentException.class, () ->
                config.load(YamlNode.load("economy.yml", new StringReader("reference:\n  max_step: 2\n"))));
    }

    @Test
    void gambleOnlyRollsUniquesWhenTheBaseHasOne() {
        final EconomyConfig config = new EconomyConfig();
        final Random random = new Random(7);
        final Map<Rarity, Integer> without = new EnumMap<>(Rarity.class);
        final Map<Rarity, Integer> with = new EnumMap<>(Rarity.class);
        for (int i = 0; i < 20_000; i++) {
            without.merge(config.rollGamble(random, false), 1, Integer::sum);
            with.merge(config.rollGamble(random, true), 1, Integer::sum);
        }
        assertFalse(without.containsKey(Rarity.UNIQUE), "沒有能賭的傳奇就不會出傳奇");
        assertTrue(with.getOrDefault(Rarity.UNIQUE, 0) > 200 && with.get(Rarity.UNIQUE) < 700, "2% 左右：" + with);
        assertTrue(with.get(Rarity.MAGIC) > 10_000 && with.get(Rarity.MAGIC) < 12_000, "55% 左右：" + with);
    }

    @Test
    void pricesFollowTheGoldTable() throws Exception {
        final EconomyConfig config = new EconomyConfig();
        config.load(Path.of("src/main/resources/data"));
        final tw.exilecore.monster.DropTable drops = new tw.exilecore.monster.DropTable();
        drops.load(Path.of("src/main/resources/data"));
        config.goldReference(drops::averageGold);
        // 同等級普通怪平均掉的金幣（drops.yml）：30 級 50、60 級 145、90 級 270、100 級 320
        assertEquals(24_300, config.gamblePrice(90), "賭博 × 90");
        assertEquals(28_800, config.gamblePrice(100));
        assertEquals(13_050, config.gamblePrice(60));
        assertEquals(4_500, config.gamblePrice(30));
        assertEquals(3_240, config.vendorPrice(Rarity.NORMAL, 90), "商人 × 12 × 稀有度倍率");
        assertEquals(9_720, config.vendorPrice(Rarity.MAGIC, 90));
        assertEquals(32_400, config.vendorPrice(Rarity.RARE, 90));
        assertEquals(6_000, config.vendorPrice(Rarity.RARE, 30));
        assertEquals(4_860, config.vendorRefreshCost(90), "馬上換一批 × 18");
        // 拿不到金幣表（模組還沒好）：退回舊的算法
        config.goldReference(level -> 0);
        assertEquals(2_350, config.gamblePrice(90));
        assertEquals(3_800, config.vendorPrice(Rarity.RARE, 90), "(20 + 4 × 90) × 10");
        assertEquals(900, config.vendorRefreshCost(90));
        // kills 寫 0 也用舊的算法
        final EconomyConfig legacy = new EconomyConfig();
        legacy.load(YamlNode.load("economy.yml", new StringReader("gamble:\n  price: { kills: 0, base: 100, per_level: 25 }\n")));
        legacy.goldReference(drops::averageGold);
        assertEquals(2_350, legacy.gamblePrice(90));
        assertEquals(32_400, legacy.vendorPrice(Rarity.RARE, 90), "沒寫的照預設（kills 12）");
    }

    @Test
    void bossUniquesAreHalfAsFastToGambleAsToFarm() throws Exception {
        final EconomyConfig config = new EconomyConfig();
        config.load(Path.of("src/main/resources/data"));
        assertEquals(0.65, config.gambleBossUnique(), 1e-9);
        assertEquals(new EconomyConfig().gambleBossUnique(), config.gambleBossUnique(), 1e-9, "程式的預設值跟 economy.yml 一樣");
        assertEquals(2.0, config.gambleUniqueWeight(true, false), 1e-9, "只能賭的（劇情期間用的）還是 2");
        assertEquals(0.65, config.gambleUniqueWeight(true, true), 1e-9, "地圖 Boss 也會掉的 0.65");
        assertEquals(0, config.gambleUniqueWeight(false, true), 1e-9, "沒有能賭的傳奇");
        final double[] odds = config.gambleOdds(0);
        assertEquals(45, odds[1], 1e-9, "沒有傳奇：傳奇的權重算成稀有");
        final Random random = new Random(11);
        int uniques = 0;
        final int rolls = 200_000;
        for (int i = 0; i < rolls; i++) {
            if (config.rollGamble(random, config.gambleBossUnique()) == Rarity.UNIQUE) {
                uniques++;
            }
        }
        final double chance = 0.65 / (55 + 43 + 0.65);
        assertEquals(chance, uniques / (double) rolls, 0.001, "一次約 0.66%");
        // 照地圖 Boss 的 5% 校準：一張 T16 大約賺 10 萬金幣（含 Boss 與拆解），90～100 級全部拿去賭，
        // 賭到一件 Boss 傳奇的機會大約是打那隻 Boss（5%）的一半
        final tw.exilecore.monster.DropTable drops = new tw.exilecore.monster.DropTable();
        drops.load(Path.of("src/main/resources/data"));
        config.goldReference(drops::averageGold);
        final tw.exilecore.map.MapConfig maps = new tw.exilecore.map.MapConfig();
        maps.load(Path.of("src/main/resources/data"));
        final double boss = maps.drops().bossUniqueChance();
        for (final int level : new int[] {90, 100}) {
            final double perMap = 100_000.0 / config.gamblePrice(level) * chance;
            assertTrue(perMap > boss * 0.4 && perMap < boss * 0.6, level + " 級：一張圖的金幣賭到 Boss 傳奇 " + perMap + "，打 Boss " + boss);
        }
    }

    @Test
    void badFileKeepsTheOldSettings() {
        final EconomyConfig config = new EconomyConfig();
        config.load(YamlNode.load("economy.yml", new StringReader("market:\n  shop_slots: 9\n")));
        assertEquals(9, config.shopSlots());
        assertThrows(IllegalArgumentException.class, () ->
                config.load(YamlNode.load("economy.yml", new StringReader("values:\n  zenith_orb: -5\n"))));
        assertEquals(9, config.shopSlots(), "寫錯時保留原本的");
        assertEquals(1500, config.value("zenith_orb"));
        assertThrows(IllegalArgumentException.class, () ->
                config.load(YamlNode.load("economy.yml", new StringReader("gamble:\n  rarity_weights: { magic: 0, rare: 0 }\n"))));
    }

    @Test
    void tradeLogIsWrittenOnceAndQueryable() throws Exception {
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final String item = UUID.randomUUID().toString();
        final List<TradeLogRepository.Entry> entries = List.of(
                new TradeLogRepository.Entry("t1", TradeLogRepository.TRADE, item, "稀有 長劍「血月」", 1, alice, "Alice", bob, "Bob"),
                new TradeLogRepository.Entry("t1", TradeLogRepository.TRADE, null, "至臻石（通貨袋）", 3, bob, "Bob", alice, "Alice"));
        database.sync(c -> {
            log.insert(c, entries);
            log.insert(c, entries); // 重試：同一個 tradeId 先刪再寫，不會重複
            return null;
        });
        final List<TradeLogRepository.Row> rows = database.sync(c -> log.byItem(c, item, 10));
        assertEquals(1, rows.size());
        assertEquals("Alice", rows.getFirst().entry().fromName());
        assertEquals(bob, rows.getFirst().entry().toAccount());
        assertEquals(2, database.sync(c -> log.byName(c, "Bob", 10)).size());
        // 太長的說明截斷，不會讓整筆交易寫不進去
        database.sync(c -> {
            log.insert(c, List.of(new TradeLogRepository.Entry("t2", TradeLogRepository.MARKET, null, "很".repeat(400), 1,
                    null, "商店頁很長很長很長的名字", alice, "Alice")));
            return null;
        });
        final TradeLogRepository.Row longRow = database.sync(c -> log.byName(c, "Alice", 10)).getFirst();
        assertEquals(255, longRow.entry().itemDesc().length());
    }

    @Test
    void searchNeedsEveryTerm() {
        assertEquals(List.of("火焰", "抗性"), MarketSearch.terms("  火焰　抗性 "), "全形空白也算分隔");
        final String text = "稀有 長劍「血月」\n+20% 火焰抗性\n增加 10% 攻擊速度\n稀有\n長劍\n".toLowerCase();
        assertTrue(MarketSearch.matches(text, MarketSearch.terms("火焰 抗性")));
        assertTrue(MarketSearch.matches(text, MarketSearch.terms("長劍")));
        assertFalse(MarketSearch.matches(text, MarketSearch.terms("火焰 冰冷")));
        assertTrue(MarketSearch.matches(text, MarketSearch.terms("")), "沒有關鍵字＝全部");
        assertTrue(MarketSearch.matches("max life +50".toLowerCase(), MarketSearch.terms("LIFE")), "不分大小寫");
    }
}
