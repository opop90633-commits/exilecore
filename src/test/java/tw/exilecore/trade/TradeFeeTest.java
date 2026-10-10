package tw.exilecore.trade;

import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.economy.EconomyConfig;
import tw.exilecore.item.Rarity;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 面對面交易的手續費：估價、各付各的、什麼都沒收到不用付、跟市集一樣的費率。 */
class TradeFeeTest {

    static EconomyConfig real() throws Exception {
        final EconomyConfig config = new EconomyConfig();
        config.load(Path.of("src/main/resources/data"));
        return config;
    }

    static EconomyConfig of(final String yaml) {
        final EconomyConfig config = new EconomyConfig();
        config.load(YamlNode.load("economy.yml", new StringReader(yaml)));
        return config;
    }

    @Test
    void realFileUsesTheMarketRate() throws Exception {
        final EconomyConfig config = real();
        assertEquals(3, config.tradeFee().percent());
        assertEquals(10, config.tradeFee().min());
        assertEquals("3", config.tradeFee().percentText());
        assertEquals(config.purchaseFee().percent(), config.tradeFee().percent(), "跟市集的購買手續費一樣，面對面交易躲不掉手續費");
        // 傳奇：商人不賣，只用來估價（(20 + 4 × 60) × 20）
        assertEquals(5200, config.vendorPrice(Rarity.UNIQUE, 60));
    }

    @Test
    void nothingReceivedPaysNothing() throws Exception {
        final TradeFee.Quote quote = TradeFee.quote(real(), List.of());
        assertTrue(quote.nothing());
        assertEquals(0, quote.fee(), "只送東西給別人（自己什麼都沒收到）不用付");
    }

    @Test
    void currencyAndGearAreValued() throws Exception {
        final EconomyConfig config = real();
        // 10 個至臻石 = 15,000 金幣 → 3% = 450，跟在市集用 10 個至臻石買東西的手續費一樣
        final TradeFee.Quote zenith = TradeFee.quote(config, List.of(new TradeFee.Currency("zenith_orb", 10)));
        assertEquals(15_000, zenith.value());
        assertEquals(450, zenith.fee());
        assertEquals(config.purchaseFee("zenith_orb", 10), zenith.fee());
        // 稀有 60 級裝備：(20 + 4 × 60) × 10 = 2,600 → 78
        final TradeFee.Quote rare = TradeFee.quote(config, List.of(new TradeFee.Gear(Rarity.RARE, 60, 1)));
        assertEquals(2_600, rare.value());
        assertEquals(78, rare.fee());
        // 一起收：價值加起來再算一次（不會因為放在同一次交易就變便宜，也不會每樣各收一次最低）
        final TradeFee.Quote both = TradeFee.quote(config, List.of(new TradeFee.Gear(Rarity.RARE, 60, 1),
                new TradeFee.Currency("zenith_orb", 3)));
        assertEquals(2_600 + 4_500, both.value());
        assertEquals(213, both.fee());
        assertEquals(2, both.count());
    }

    @Test
    void cheapOrUnpricedThingsPayTheMinimum() throws Exception {
        final EconomyConfig config = real();
        assertEquals(10, TradeFee.quote(config, List.of(new TradeFee.Other(1))).fee(), "寶石、藥劑：算 0，收最低");
        assertEquals(10, TradeFee.quote(config, List.of(new TradeFee.Currency("identify_scroll", 40))).fee(), "不能標價的通貨算 0");
        assertEquals(10, TradeFee.quote(config, List.of(new TradeFee.Gear(Rarity.NORMAL, 10, 1))).fee(), "60 金幣的 3% 不到 10");
        assertEquals(0, TradeFee.value(config, new TradeFee.Currency("glimmer_orb", -5)), "數量是負的算 0");
    }

    @Test
    void eachSidePaysForWhatTheyReceive() throws Exception {
        final EconomyConfig config = real();
        // A 給一件稀有裝備，B 給 2 個至臻石：A 收到至臻石付 90，B 收到裝備付 78
        final List<TradeFee.Received> fromA = List.of(new TradeFee.Gear(Rarity.RARE, 60, 1));
        final List<TradeFee.Received> fromB = List.of(new TradeFee.Currency("zenith_orb", 2));
        assertEquals(90, TradeFee.quote(config, fromB).fee());
        assertEquals(78, TradeFee.quote(config, fromA).fee());
    }

    @Test
    void settingsAreReadAndChecked() {
        final EconomyConfig config = of("trade:\n  fee: { percent: 2.5, min: 0 }\nvendor:\n  price:\n    rarity: { unique: 50 }\n");
        assertEquals("2.5", config.tradeFee().percentText());
        final TradeFee.Quote free = TradeFee.quote(config, List.of(new TradeFee.Other(1)));
        assertFalse(free.nothing(), "有收到東西，只是不用錢");
        assertEquals(0, free.fee());
        assertEquals(5, TradeFee.quote(config, List.of(new TradeFee.Currency("annul_orb", 1))).fee(), "200 × 2.5%");
        assertEquals((20 + 4 * 60) * 50, config.vendorPrice(Rarity.UNIQUE, 60));
        // 寫錯（負的）：整份不換，保留原本的
        assertThrows(IllegalArgumentException.class, () ->
                config.load(YamlNode.load("economy.yml", new StringReader("trade:\n  fee: { percent: -1, min: 10 }\n"))));
        assertEquals(2.5, config.tradeFee().percent());
        // 沒寫 fee：用預設值（舊的 economy.yml 也能用）
        assertEquals(3, of("trade:\n  range: 8\n").tradeFee().percent());
        assertEquals(10, of("trade:\n  range: 8\n").tradeFee().min());
    }

    @Test
    void hugeValuesDoNotOverflow() {
        final EconomyConfig config = of("values:\n  zenith_orb: 9000000000000000000\n");
        final TradeFee.Quote quote = TradeFee.quote(config, List.of(new TradeFee.Currency("zenith_orb", 5000),
                new TradeFee.Currency("zenith_orb", 5000)));
        assertEquals(Long.MAX_VALUE, quote.value(), "封頂，不會變成負的");
        assertTrue(quote.fee() > 0);
        assertEquals(Long.MAX_VALUE, TradeFee.add(Long.MAX_VALUE, 1));
        assertEquals(Long.MAX_VALUE, TradeFee.multiply(Long.MAX_VALUE / 2, 3));
        assertEquals(0, TradeFee.multiply(100, 0));
    }
}
