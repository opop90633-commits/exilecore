package tw.exilecore.salvage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.item.ItemData;
import tw.exilecore.item.ItemRepository;
import tw.exilecore.item.Rarity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 拆解台的獎勵計算（用 jar 裡的 salvage.yml 與基底資料）。 */
class SalvageConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static SalvageConfig config;
    static ItemRepository items;

    @BeforeAll
    static void load() throws IOException {
        config = new SalvageConfig();
        config.load(DATA);
        items = new ItemRepository();
        items.load(DATA);
    }

    @Test
    void goldScalesWithItemLevelAndRarity() {
        // (4 + 1.5 × 物品等級) × 稀有度倍率
        assertEquals(6, config.gold(new ItemData("short_sword", Rarity.NORMAL, 1)));
        assertEquals(136, config.gold(new ItemData("short_sword", Rarity.RARE, 20)));
        assertEquals(940, config.gold(new ItemData("plate_body", Rarity.UNIQUE, 60)));
    }

    @Test
    void gemsAndFlasksGiveGold() {
        assertEquals(14, config.goldForGem(false, 1), "技能寶石：8 + 6 × 等級");
        assertEquals(128, config.goldForGem(false, 20));
        assertEquals(40, config.goldForGem(true, 1), "輔助寶石：15 + 25 × 階級");
        assertEquals(90, config.goldForGem(true, 3));
        assertEquals(4, config.goldForFlask(1), "藥劑：3 + 需求等級");
        assertEquals(63, config.goldForFlask(60));
    }

    @Test
    void qualityCurrencyDependsOnItemKind() throws IOException {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        assertEquals("whetstone", config.currencyFor(items.base("short_sword")));
        assertEquals("armour_scrap", config.currencyFor(items.base("plate_helmet")));
        assertEquals("armour_scrap", config.currencyFor(items.base("tower_shield")));
        assertNull(config.currencyFor(items.base("iron_ring")), "飾品不給品質通貨");
        assertNotNull(currencies.get(config.currencyFor(items.base("short_sword"))), "設定的通貨要存在");
        assertNotNull(currencies.get(config.currencyFor(items.base("plate_helmet"))));
    }

    @Test
    void qualityGivesGuaranteedCurrencyAndRarityGivesAChance() {
        final ItemData sword = new ItemData("short_sword", Rarity.UNIQUE, 30);
        sword.setQuality(20);
        assertEquals(2, config.guaranteedCurrency(sword));
        // 傳奇 100% 再多 1 個
        final SalvageConfig.Reward reward = config.reward(items.base("short_sword"), sword, fixed(0.99));
        assertEquals("whetstone", reward.currencyId());
        assertEquals(3, reward.currencyAmount());

        final ItemData plain = new ItemData("short_sword", Rarity.NORMAL, 5);
        final SalvageConfig.Reward nothing = config.reward(items.base("short_sword"), plain, fixed(0.5));
        assertNull(nothing.currencyId(), "普通、沒品質、機率沒中：只有金幣");
        assertEquals(0, nothing.currencyAmount());
        assertEquals(config.gold(plain), nothing.gold());

        final SalvageConfig.Reward ring = config.reward(items.base("iron_ring"), new ItemData("iron_ring", Rarity.RARE, 40), fixed(0.0));
        assertNull(ring.currencyId());
    }

    @Test
    void mapsGiveGoldAndChisels() throws IOException {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(DATA);
        assertEquals("chisel", config.currencyFor(items.base("map_wreck_bay")), "地圖拆出星盤刻刀");
        assertNotNull(currencies.get("chisel"));
        // 地圖的物品等級 = 那一階的怪物等級：T1 = 61 → (4 + 1.5 × 61) × 1 = 95.5 → 96；T16 稀有 = (4 + 1.5 × 84) × 4 = 520
        final ItemData t1 = new ItemData("map_wreck_bay", Rarity.NORMAL, 61);
        t1.setMapTier(1);
        assertEquals(96, config.gold(items.base("map_wreck_bay"), t1));
        final ItemData t16 = new ItemData("map_wreck_bay", Rarity.RARE, 84);
        t16.setMapTier(16);
        t16.setQuality(20);
        assertEquals(520, config.gold(items.base("map_wreck_bay"), t16));
        final SalvageConfig.Reward reward = config.reward(items.base("map_wreck_bay"), t16, fixed(0.0));
        assertEquals("chisel", reward.currencyId());
        assertEquals(3, reward.currencyAmount(), "品質 20% 給 2 個，稀有 50% 機率再 1 個（亂數 0 → 中）");
        assertEquals(520, reward.gold());
        assertEquals(config.gold(t16), config.gold(null, t16), "沒給基底時照裝備算");
    }

    /** nextDouble() 永遠回傳同一個值的亂數。 */
    private static RandomGenerator fixed(final double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    void freeCurrenciesSalvageForNothing() {
        // 2026-10：沒有參考價值的通貨（鑑定卷軸這種跟 NPC 免費領的）拆不出金幣（以前一個至少 1 金幣，一疊 40 個白賺 40）
        assertEquals(0, config.goldForCurrency(0, 40));
        assertEquals(4 * 40, config.goldForCurrency(40, 40), "參考價值 40 × 10% = 一個 4 金幣");
        assertEquals(1, config.goldForCurrency(3, 1), "有價值的最少還是 1 金幣");
    }
}
