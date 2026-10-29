package tw.exilecore.map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tw.exilecore.currency.CurrencyOperation;
import tw.exilecore.currency.CurrencyRepository;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.item.ItemRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-18：事件巔峰——地圖事件的入場券（map_events.yml 的 ticket、currencies.yml 的 ticket_*）與用一張券開的巔峰
 * （maps.yml finals 底下 pinnacle.ticket：沒有 atlas、requires 空、不算星圖的終局點數）。
 */
class EventPinnacleTest {

    static final Path DATA = Path.of("src/main/resources/data");
    static MapConfig maps;
    static MapEventConfig events;
    static CurrencyRepository currencies;

    @BeforeAll
    static void load() throws IOException {
        final ItemRepository items = new ItemRepository();
        items.load(DATA);
        maps = new MapConfig();
        maps.load(DATA);
        assertEquals(List.of(), maps.validate(items));
        events = new MapEventConfig();
        events.load(DATA);
        currencies = new CurrencyRepository();
        currencies.load(DATA);
    }

    @Test
    void ticketsParseForEveryEventExceptTheSafe() {
        assertEquals(List.of("shrine", "strongbox", "breach", "exile", "ritual", "delirium", "abyss"), List.copyOf(events.tickets().keySet()));
        for (final Map.Entry<String, MapEventConfig.Ticket> entry : events.tickets().entrySet()) {
            final MapEventConfig.Ticket ticket = entry.getValue();
            assertEquals("ticket_" + entry.getKey(), ticket.currency(), entry.getKey());
            // 2026-10-20：照事件出現的頻率調（7 種都約 60 張圖 1 張）
            assertEquals(Map.of("shrine", 0.022, "strongbox", 0.022, "breach", 0.044, "abyss", 0.044, "ritual", 0.03, "delirium", 0.056,
                    "exile", 0.067).get(entry.getKey()), ticket.chance(), 1e-9, entry.getKey());
            assertEquals(entry.getKey(), events.ticketSource(ticket.currency()), "券 → 事件");
        }
        assertNull(events.ticket(MapEventConfig.Kind.SAFE), "保險箱沒有入場券");
        assertNull(events.ticket("safe"));
        assertNull(events.ticketSource("sky_fragment"));
        for (final MapEventConfig.Kind kind : MapEventConfig.Kind.values()) {
            if (kind == MapEventConfig.Kind.SAFE) {
                continue;
            }
            assertEquals(events.ticket(MapEventConfig.uniqueSource(kind)), events.ticket(kind), kind.name());
        }
        assertEquals(events.ticket("ritual"), events.ticket(MapEventConfig.RITUAL_SOURCE));
        assertEquals(events.ticket("delirium"), events.ticket(MapEventConfig.DELIRIUM_SOURCE));
    }

    @Test
    void everyTicketIsACurrencyWithNoUseAndNoDropWeight() {
        for (final MapEventConfig.Ticket ticket : events.tickets().values()) {
            final CurrencyType type = currencies.get(ticket.currency());
            assertNotNull(type, ticket.currency() + " 要在 currencies.yml 裡");
            assertEquals(CurrencyOperation.NONE, type.operation(), ticket.currency());
            assertEquals(0, type.dropWeight(), ticket.currency() + "：不走掉落表");
            assertEquals(10, type.stack(), ticket.currency());
            assertTrue(type.name().endsWith("之鑰"), type.name());
            assertTrue(type.description() != null && type.description().contains("事件巔峰"), ticket.currency() + " 的說明要寫怎麼用");
        }
    }

    @Test
    void eventPinnaclesOpenWithOneTicketAndStayOffTheAtlas() {
        final List<MapConfig.MapDef> pinnacles = maps.eventPinnacles();
        assertFalse(pinnacles.isEmpty(), "maps.yml 要有事件巔峰");
        final Set<String> tickets = new java.util.HashSet<>();
        for (final MapConfig.MapDef map : pinnacles) {
            final MapConfig.Pinnacle pinnacle = maps.pinnacle(map.id());
            assertNotNull(pinnacle, map.id());
            assertTrue(maps.isEventPinnacle(map.id()));
            assertTrue(maps.finalInfo(map.id()).isEventPinnacle());
            assertNotNull(pinnacle.ticket(), map.id() + " 要寫 ticket");
            assertTrue(pinnacle.fragments().isEmpty(), map.id() + "：用券開就沒有碎片");
            assertEquals(List.of(pinnacle.ticket()), pinnacle.costs(), "開門用掉的就是那一張券");
            assertTrue(tickets.add(pinnacle.ticket()), map.id() + " 的券跟別隻重複");
            assertNotNull(currencies.get(pinnacle.ticket()), map.id() + " 的券 " + pinnacle.ticket() + " 要在 currencies.yml 裡");
            assertNotNull(events.ticketSource(pinnacle.ticket()), map.id() + " 的券要有某種事件掉（map_events.yml 的 ticket）");
            // 不在星圖上、沒有要先打贏的終局、不給首殺點數
            assertFalse(map.onAtlas(), map.id() + " 不在星圖上");
            assertEquals(-1, map.col());
            assertEquals(-1, map.row());
            assertEquals(List.of(), maps.finalInfo(map.id()).requires(), map.id() + "：requires 要是空的");
            assertEquals(0, maps.finalPoints(map.id()), map.id() + "：不給星圖的終局點數");
            assertTrue(Atlas.finalRequirementsMet(maps, map, Set.of()), "沒有要先打贏的終局：有券就能開");
            assertEquals("", Atlas.missingRequirements(maps, map, Set.of()));
            // 跟萬界之巔同一套：只有 Boss 房、兩道門、每秒傷害上限、最高階再高幾級、具名、三個階段
            assertTrue(map.isFinal());
            assertTrue(map.boss().named(), map.id());
            assertEquals(2, pinnacle.attempts(), map.id());
            assertEquals(4000000, pinnacle.dpsCap(), 1e-9, map.id()); // 2026-10-29 照站長：400 萬起；2026-10-27 照站長：200 萬起遞減（這次之前 100 萬）
            assertFalse(pinnacle.dpsOver().isEmpty(), map.id());
            assertEquals(0.65, pinnacle.cooldown(), 1e-9, map.id());
            assertEquals(maps.tiers().max(), maps.pinnacleStats(map.id(), pinnacle).tier(), map.id());
            assertEquals(maps.pinnacleStats("awakener", maps.pinnacle("awakener")).monsterLevel(),
                    maps.pinnacleStats(map.id(), pinnacle).monsterLevel(), map.id() + "：怪物等級跟萬界之巔一樣");
            assertEquals(3, map.boss().phases().size(), map.id());
            assertNull(maps.uber(map.id()), map.id() + "：巔峰沒有極境版本");
        }
        // 萬界之巔還是集碎片的那一種
        assertEquals(List.of("awakener"), maps.fragmentPinnacles().stream().map(MapConfig.MapDef::id).toList());
        assertFalse(maps.isEventPinnacle("awakener"));
        assertNull(maps.pinnacle("awakener").ticket());
        assertEquals(4, maps.pinnacle("awakener").costs().size(), "萬界之巔：四種碎片各一個");
        assertEquals(4, maps.regularFinals().size(), "事件巔峰不算一般終局");
        for (final MapConfig.MapDef map : maps.regularFinals()) {
            assertTrue(map.onAtlas(), map.id());
        }
        for (final Atlas.Connector connector : Atlas.connectors(maps)) {
            assertFalse(maps.isEventPinnacle(connector.from()) || maps.isEventPinnacle(connector.to()), "事件巔峰不畫連線");
        }
    }

    @Test
    void ticketPinnacleParsesWithoutAtlasAndRejectsMixedCosts(@org.junit.jupiter.api.io.TempDir final Path dir) throws IOException {
        final String base = """
                settings: { tiers: [61, 62, 63, 64] }
                maps:
                  a:
                    base: map_t1
                    atlas: [0, 2]
                    start: true
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 甲 }
                final:
                  id: f
                  name: 終局
                  atlas: [8, 2]
                  monsters: { ZOMBIE: 1 }
                  boss: { name: 乙, named: true }
                finals:
                  p:
                    name: 事件巔峰
                    requires: []
                    points: 0
                    monsters: { ZOMBIE: 1 }
                    boss: { name: 丙, named: true }
                    pinnacle:
                      ticket: ticket_shrine
                      %s
                """;
        Files.writeString(dir.resolve("maps.yml"), String.format(base, ""));
        final MapConfig config = new MapConfig();
        config.load(dir);
        assertEquals("ticket_shrine", config.pinnacle("p").ticket());
        assertTrue(config.pinnacle("p").fragments().isEmpty());
        assertTrue(config.isEventPinnacle("p"));
        assertFalse(config.find("p").onAtlas(), "沒寫 atlas 也讀得起來");
        assertEquals(List.of("p"), config.eventPinnacles().stream().map(MapConfig.MapDef::id).toList());
        assertEquals(List.of(), config.fragmentPinnacles());
        assertEquals(List.of("f"), config.regularFinals().stream().map(MapConfig.MapDef::id).toList());
        assertEquals(0, config.finalPoints("p"));
        // 寫了 ticket 又寫 fragments：拒絕
        Files.writeString(dir.resolve("maps.yml"), String.format(base,
                "fragments: [{ currency: fragment_x, from: f, chance: 0.1 }]"));
        assertThrows(IllegalArgumentException.class, () -> new MapConfig().load(dir));
        // 沒有 ticket 也沒有 fragments：拒絕
        Files.writeString(dir.resolve("maps.yml"), String.format(base, "").replace("ticket: ticket_shrine", "attempts: 2"));
        assertThrows(IllegalArgumentException.class, () -> new MapConfig().load(dir));
        // 一般的巔峰（集碎片）沒寫 atlas：還是要拒絕
        Files.writeString(dir.resolve("maps.yml"), String.format(base, "").replace("ticket: ticket_shrine",
                "fragments: [{ currency: fragment_x, from: f, chance: 0.1 }]"));
        assertThrows(IllegalArgumentException.class, () -> new MapConfig().load(dir));
    }

    @Test
    void ticketLinesInMapEventsOverrideTheDefaults(@org.junit.jupiter.api.io.TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("map_events.yml"), """
                shrine:
                  ticket: { currency: ticket_custom, chance: 0.5 }
                ritual:
                  ticket: { chance: 0 }
                """);
        final MapEventConfig config = new MapEventConfig();
        config.load(dir);
        assertEquals(new MapEventConfig.Ticket("ticket_custom", 0.5), config.ticket(MapEventConfig.Kind.SHRINE));
        assertEquals("shrine", config.ticketSource("ticket_custom"));
        assertEquals(new MapEventConfig.Ticket("ticket_ritual", 0), config.ticket(MapEventConfig.RITUAL_SOURCE), "只改機率，代號照預設");
        assertNull(config.ticketSource("ticket_ritual"), "機率 0 = 沒有事件掉它");
        assertEquals(new MapEventConfig.Ticket("ticket_breach", 0.02), config.ticket(MapEventConfig.Kind.BREACH), "沒寫照預設 2%");
        assertEquals("神龕", MapEventConfig.sourceName("shrine"));
        assertEquals("深淵裂縫", MapEventConfig.sourceName("abyss"));
        // 空的設定檔：全部預設
        Files.writeString(dir.resolve("map_events.yml"), "settings: {}\n");
        final MapEventConfig fresh = new MapEventConfig();
        fresh.load(dir);
        assertEquals(7, fresh.tickets().size());
        assertEquals(new MapEventConfig.Ticket("ticket_shrine", 0.02), fresh.ticket(MapEventConfig.Kind.SHRINE));
    }
}
