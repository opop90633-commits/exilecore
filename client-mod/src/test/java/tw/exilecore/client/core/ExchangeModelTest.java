package tw.exilecore.client.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02 照站長：通貨交易所原生畫面的資料、排序、寫法。 */
class ExchangeModelTest {

    private static ExchangeModel model(final String json) {
        return new ExchangeModel(JsonParser.parseString(json).getAsJsonObject());
    }

    private static final String STATE = "{\"kind\":\"exchange\",\"open\":true,\"msg\":\"<green>好\","
            + "\"currencies\":[{\"id\":\"chaos\",\"n\":\"混沌石\",\"i\":\"minecraft:paper\",\"m\":\"c\",\"g\":\"基本\",\"have\":0,\"r\":-1},"
            + "{\"id\":\"exalt\",\"n\":\"崇高石\",\"i\":\"minecraft:paper\",\"have\":3,\"r\":-1},"
            + "{\"id\":\"divine\",\"n\":\"神聖石\",\"i\":\"minecraft:paper\",\"have\":50,\"r\":-1},"
            + "{\"id\":\"alch\",\"n\":\"點金石\",\"i\":\"minecraft:paper\",\"have\":0,\"r\":1},"
            + "{\"id\":\"regal\",\"n\":\"富豪石\",\"i\":\"minecraft:paper\",\"have\":9,\"r\":0}],"
            + "\"max\":5000,\"maxOrders\":10,\"gold\":1234,\"give\":\"divine\",\"want\":\"chaos\",\"giveAmount\":2,\"wantAmount\":300,"
            + "\"source\":\"市價\",\"asks\":[{\"lg\":1,\"lw\":150,\"stock\":400,\"orders\":2,\"mine\":false},{\"lg\":1,\"lw\":140,\"stock\":100,\"orders\":1}],"
            + "\"bids\":[{\"lg\":1,\"lw\":160,\"stock\":50,\"orders\":1,\"mine\":true}],\"ref\":[1,150],"
            + "\"market\":{\"last\":150,\"change24\":-2.5,\"vol24\":900,\"count24\":4,\"inverse\":false},"
            + "\"preview\":{\"received\":300,\"paid\":2,\"restGive\":0,\"restWant\":0,\"refund\":0,\"resting\":false,\"fee\":5},"
            + "\"orders\":[{\"id\":\"o1\",\"give\":\"divine\",\"want\":\"chaos\",\"giveTotal\":1,\"wantTotal\":160,\"taken\":0,\"remaining\":1,"
            + "\"confirmed\":false,\"ago\":\"3 分鐘前\"}]}";

    @Test
    void parsesState() {
        final ExchangeModel m = model(STATE);
        assertTrue(m.open);
        assertTrue(m.ready());
        assertEquals("<green>好", m.message);
        assertEquals(5, m.currencies.size());
        assertEquals("c", m.byId.get("chaos").model());
        assertNull(m.byId.get("exalt").model());
        assertEquals(50, m.have("divine"));
        assertEquals(0, m.have("nope"));
        assertEquals(2, m.asks.size());
        assertTrue(m.bids.get(0).mine());
        assertFalse(m.asks.get(1).mine());
        assertEquals(500, m.askTotal());
        assertEquals(150, m.ref[1]);
        assertNotNull(m.preview);
        assertEquals(5, m.preview.fee());
        assertFalse(m.orders.get(0).confirmed());
        assertEquals("3 分鐘前", m.orders.get(0).ago());
        assertEquals(1234, m.gold);
    }

    @Test
    void emptyStateIsSafe() {
        final ExchangeModel m = model("{}");
        assertFalse(m.ready());
        assertFalse(m.open);
        assertTrue(m.currencies.isEmpty());
        assertNull(m.ref);
        assertNull(m.market);
        assertNull(m.preview);
        assertEquals("", m.marketText());
        assertEquals("—", ExchangeModel.ratio("a", 1, "b", 0));
    }

    @Test
    void samePairIsNotReady() {
        assertFalse(model("{\"give\":\"a\",\"want\":\"a\"}").ready());
    }

    @Test
    void pickerSortsRecentThenHeldThenConfig() {
        final List<ExchangeModel.Currency> list = model(STATE).picker("");
        assertEquals(List.of("regal", "alch", "divine", "exalt", "chaos"), list.stream().map(ExchangeModel.Currency::id).toList());
    }

    @Test
    void pickerSearchesNameAndId() {
        assertEquals(List.of("divine"), model(STATE).picker(" 神聖 ").stream().map(ExchangeModel.Currency::id).toList());
        assertEquals(List.of("exalt"), model(STATE).picker("EXA").stream().map(ExchangeModel.Currency::id).toList());
        assertTrue(model(STATE).picker("沒有這個").isEmpty());
    }

    @Test
    void ratioFlipsWhenBelowOne() {
        assertEquals("1 混沌石 ≈ 150 神聖石", ExchangeModel.ratio("混沌石", 150, "神聖石", 1));
        assertEquals("150 神聖石 ≈ 1 混沌石", ExchangeModel.ratio("神聖石", 1, "混沌石", 150));
        assertEquals("1 a ≈ 2.5 b", ExchangeModel.ratio("a", 5, "b", 2));
        assertEquals("1 a ≈ 1,500 b", ExchangeModel.ratio("a", 1500, "b", 1));
    }

    @Test
    void levelAndMarketText() {
        final ExchangeModel m = model(STATE);
        assertEquals("150 混沌石 ≈ 1 神聖石", m.levelText(m.asks.get(0)));
        assertEquals("1 混沌石 ≈ 150 神聖石（24 小時 -2.5%）", m.marketText());
    }

    @Test
    void parseAmountClamps() {
        assertEquals(1, ExchangeModel.parseAmount("0", 100));
        assertEquals(100, ExchangeModel.parseAmount("1,000", 100));
        assertEquals(42, ExchangeModel.parseAmount(" 42 ", 100));
        assertEquals(-1, ExchangeModel.parseAmount("abc", 100));
        assertEquals(-1, ExchangeModel.parseAmount("", 100));
        assertEquals(-1, ExchangeModel.parseAmount(null, 100));
    }
}
