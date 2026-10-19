package tw.exilecore.card;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.core.config.YamlNode;
import tw.exilecore.currency.CurrencyGroups;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRepository;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 星諭卡（2026-10-19）：資料檔讀得進來、每張卡與獎勵都對得上、掉落的地點、賭卡 0～2 倍。 */
class CardConfigTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static CardConfig config;
    static CurrencyRepository currencies;
    static ItemRepository items;

    @BeforeAll
    static void load() throws IOException {
        config = new CardConfig();
        config.load(DATA);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
        items = new ItemRepository();
        items.load(DATA);
    }

    @Test
    void shippedCardsAreConsistent() {
        assertTrue(config.cards().size() >= 25, "星諭卡至少 25 種，實際 " + config.cards().size());
        final List<String> problems = config.validate(id -> currencies.get(id) != null, id -> items.unique(id) != null,
                source -> !items.uniquesFor(source, config.itemLevel()).isEmpty());
        assertEquals(List.of(), problems);
        for (final CardConfig.Card card : config.cards().values()) {
            final CurrencyType type = currencies.get(card.id());
            assertEquals(CurrencyGroups.Group.CARDS, CurrencyGroups.of(type), card.id() + "：在「星諭卡」那一組");
            assertEquals(0, type.dropWeight(), card.id() + "：卡不從一般通貨的掉落表掉（照 cards.yml 擲）");
            assertTrue(type.description().contains(String.valueOf(card.set())), card.id() + "：說明寫了一套幾張");
            assertTrue(config.gambleCost(card.tier()) > 0, card.id() + "：賭卡要花金幣");
        }
        assertTrue(currencies.get(CardConfig.DECK).dropWeight() > 0, "星諭牌堆從一般通貨的掉落表掉");
    }

    @Test
    void everyAtlasMapAndEventHasACard() {
        for (final String map : List.of("wreck_bay", "moss_grove", "ash_street", "mist_ferry", "outer_city", "frost_pines", "abyss_mine",
                "glow_grotto", "burning_field", "tide_temple", "red_sands", "starfall_tundra")) {
            assertFalse(config.forKill(map, 16, false).isEmpty(), map + "：路上的怪會掉卡");
        }
        assertFalse(config.forKill("royal_tomb", 1, true).isEmpty(), "王陵地宮的 Boss");
        assertTrue(config.forKill("royal_tomb", 16, false).stream().allMatch(card -> card.sources().getFirst().kind().equals("any")),
                "王陵地宮路上的怪只掉任何地圖都有的卡");
        for (final String event : List.of("shrine", "strongbox", "breach", "exile", "ritual", "delirium", "abyss")) {
            assertEquals(1, config.forEvent(event, 1).size(), event + "：完成事件有一張卡");
        }
        assertTrue(config.forEvent("breach", 1).stream().noneMatch(card -> card.sources().stream().anyMatch(s -> s.kind().equals("any"))));
    }

    /** 2026-10-19：事件巔峰的 Boss 掉自己事件的卡；追逐卡（任何地圖都能掉的）只佔一點點，不能變成提款機。 */
    @Test
    void eventPinnaclesDropTheirEventCard() {
        for (final String boss : List.of("shrine_warden", "strongbox_mimic", "breach_lord", "exile_king", "ritual_priest", "delirium_mirror", "abyss_lich")) {
            final List<CardConfig.Card> pool = config.forKill(boss, 16, true);
            assertTrue(pool.stream().anyMatch(card -> card.tier() == CardConfig.Tier.RARE), boss + "：有自己事件的卡");
            final int total = pool.stream().mapToInt(CardConfig.Card::weight).sum();
            final int chase = pool.stream().filter(card -> card.tier() == CardConfig.Tier.CHASE).mapToInt(CardConfig.Card::weight).sum();
            assertTrue(chase * 100 < total, boss + "：追逐卡不到 1%，實際 " + chase + " / " + total);
        }
    }

    /** 2026-10-19：沒有自己的卡的地方（王陵路上的怪、終局的小怪）追逐卡不能變常見：補「什麼都沒有」。 */
    @Test
    void placesWithoutTheirOwnCardDoNotFavourChaseCards() {
        final Random random = new Random(5);
        for (final String place : List.of("royal_tomb", "sky_crucible", "frost_throne", "awakener")) {
            final List<CardConfig.Card> pool = config.forKill(place, 16, false);
            int chase = 0;
            final int n = 100_000;
            for (int i = 0; i < n; i++) {
                final CardConfig.Card card = config.pickKill(pool, random);
                if (card != null && card.tier() == CardConfig.Tier.CHASE) {
                    chase++;
                }
            }
            assertTrue(chase < n / 100, place + "：擲到卡時追逐卡不到 1%，實際 " + chase + " / " + n);
        }
        final List<CardConfig.Card> wreck = config.forKill("wreck_bay", 16, false);
        assertTrue(config.pickKill(wreck, new Random(1)) != null, "有自己的卡的地圖照舊一定抽到一張");
    }

    @Test
    void chaseCardsNeedHighTiersAndAreRare() {
        assertTrue(config.forKill("wreck_bay", 13, false).stream().noneMatch(card -> card.tier() == CardConfig.Tier.CHASE),
                "T13 以下沒有追逐卡");
        final List<CardConfig.Card> t16 = config.forKill("wreck_bay", 16, false);
        assertTrue(t16.stream().anyMatch(card -> card.tier() == CardConfig.Tier.CHASE), "T16 有追逐卡");
        final Random random = new Random(7);
        int chase = 0;
        for (int i = 0; i < 100_000; i++) {
            if (CardConfig.pick(t16, random).tier() == CardConfig.Tier.CHASE) {
                chase++;
            }
        }
        assertTrue(chase > 0 && chase < 1000, "T16 掉的卡裡追逐卡不到 1%，實際 " + chase + " / 100000");
    }

    /** 2026-10-19：星諭牌堆照等級打折：稀有卡大約一成、常見卡最多。 */
    @Test
    void stackedDeckFavoursCommonCards() {
        final Random random = new Random(3);
        final java.util.Map<CardConfig.Tier, Integer> seen = new java.util.EnumMap<>(CardConfig.Tier.class);
        final int n = 200_000;
        for (int i = 0; i < n; i++) {
            seen.merge(config.pickAny(random).tier(), 1, Integer::sum);
        }
        final double rare = seen.getOrDefault(CardConfig.Tier.RARE, 0) / (double) n;
        final double common = seen.getOrDefault(CardConfig.Tier.COMMON, 0) / (double) n;
        assertTrue(rare > 0.05 && rare < 0.2, "稀有卡約一成，實際 " + rare);
        assertTrue(common > rare, "常見卡比稀有卡常見");
        assertTrue(seen.getOrDefault(CardConfig.Tier.CHASE, 0) < n / 200, "追逐卡很少");
    }

    @Test
    void gambleGivesZeroToTwiceUniformly() {
        final Random random = new Random(11);
        final int[] seen = new int[21];
        for (int i = 0; i < 210_000; i++) {
            final int result = CardConfig.gamble(10, random);
            assertTrue(result >= 0 && result <= 20, "10 張賭出 0～20 張，實際 " + result);
            seen[result]++;
        }
        for (int value = 0; value <= 20; value++) {
            assertTrue(seen[value] > 8000 && seen[value] < 12000, value + " 張的次數不平均：" + seen[value]);
        }
        assertEquals(0, CardConfig.gamble(0, random));
    }

    @Test
    void parsingChecksTheRules() {
        final CardConfig parsed = new CardConfig();
        parsed.load(yaml("""
                settings: { gamble: { common: 10 } }
                cards:
                  card_x: { tier: common, set: 3, weight: 5, sources: [map:wreck_bay, event:breach], min_tier: 4, reward: { currency: chisel, amount: 2 } }
                """));
        final CardConfig.Card card = parsed.card("card_x");
        assertNotNull(card);
        assertEquals(new CardConfig.Reward("currency", "chisel", 2), card.reward());
        assertTrue(card.dropsFromKill("wreck_bay", 4, false));
        assertFalse(card.dropsFromKill("wreck_bay", 3, false), "低於 min_tier 不掉");
        assertFalse(card.dropsFromKill("moss_grove", 16, false));
        assertTrue(card.dropsFromEvent("breach", 4));
        assertEquals(10, parsed.gambleCost(CardConfig.Tier.COMMON));
        assertNull(CardConfig.pick(List.of(), new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> new CardConfig().load(yaml("cards: { x_card: { set: 3, sources: [any], reward: { essence: 1 } } }")),
                "代號要 card_ 開頭");
        assertThrows(IllegalArgumentException.class, () -> new CardConfig().load(yaml("cards: { card_y: { set: 3, sources: [nowhere], reward: { essence: 1 } } }")));
        assertThrows(IllegalArgumentException.class, () -> new CardConfig().load(yaml("cards: { card_z: { set: 3, sources: [any] } }")),
                "要寫獎勵");
    }

    @Test
    void listedForServerSync() throws IOException {
        assertTrue(java.nio.file.Files.readString(DATA.resolve("index.txt")).contains("data/cards.yml"),
                "新資料檔要列在 index.txt 才會同步到伺服器");
    }

    private static YamlNode yaml(final String text) {
        return YamlNode.load("cards.yml", new StringReader(text));
    }
}
