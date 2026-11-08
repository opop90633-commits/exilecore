package tw.exilecore.currency;

import org.junit.jupiter.api.Test;
import tw.exilecore.character.CurrencyPouch;
import tw.exilecore.stash.Stash;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 2026-11-08 照站長：萬燈夜的素材、舊的四種催化劑從遊戲刪掉，玩家手上的一律刪掉。 */
class RetiredCurrenciesTest {

    @Test
    void retiredCurrenciesAreGoneFromTheData() throws Exception {
        final CurrencyRepository currencies = new CurrencyRepository();
        currencies.load(Path.of("src/main/resources/data"));
        final String economy = Files.readString(Path.of("src/main/resources/data/economy.yml"));
        for (final String id : RetiredCurrencies.VALUES.keySet()) {
            assertNull(currencies.get(id), id);
            assertFalse(economy.contains("  " + id + ":"), "economy.yml 還有 " + id);
        }
    }

    @Test
    void pouchDropsRetiredCurrencies() {
        final CurrencyPouch pouch = CurrencyPouch.decode("event_candy=900,glimmer_orb=12,catalyst_crit=3,event_lamp_wick=4");
        assertEquals(12, pouch.amount("glimmer_orb"));
        assertEquals(0, pouch.amount("event_candy"));
        assertEquals(0, pouch.amount("catalyst_crit"));
        assertEquals("glimmer_orb=12", pouch.encode());
    }

    @Test
    void stashDropsRetiredCurrencies() {
        final Stash stash = new Stash();
        stash.setCurrency("event_candy", 500);
        stash.setCurrency("catalyst_resist", 7);
        stash.setCurrency("glimmer_orb", 3);
        assertEquals(java.util.Map.of("glimmer_orb", 3), stash.currencies());
    }
}
