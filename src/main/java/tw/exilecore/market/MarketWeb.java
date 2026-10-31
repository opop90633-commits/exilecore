package tw.exilecore.market;

import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyArt;
import tw.exilecore.currency.CurrencyGroups;
import tw.exilecore.currency.CurrencyType;
import tw.exilecore.economy.EconomyRepository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-31 照站長：通貨交易所的行情網頁（/market）。每分鐘重做一次資料：
 * 掛單在主執行緒抄一份（只抄價格與數量），成交與每天的參考價值在資料庫執行緒讀（交易紀錄裡已經有的成交全部算進走勢，最多一年）。
 */
public final class MarketWeb {

    /** 走勢最多看幾天。 */
    static final int HISTORY_DAYS = 365;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ExileCore plugin;
    private final MarketService service;
    private volatile byte[] json = "{\"currencies\":[],\"daily\":{},\"hourly\":{},\"orders\":[]}".getBytes(StandardCharsets.UTF_8);
    private boolean running;

    MarketWeb(final ExileCore plugin, final MarketService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 最近一次做好的資料。 */
    public byte[] json() {
        return json;
    }

    /** 重做（主執行緒呼叫）。 */
    void refresh() {
        if (running || plugin.economy() == null || plugin.database() == null) {
            return;
        }
        final var config = plugin.economy().config();
        final String anchor = config.reference().anchor();
        final List<ExchangeMarketJson.Meta> metas = new ArrayList<>();
        final Map<String, Long> current = new LinkedHashMap<>();
        for (final String id : config.values().keySet()) {
            final long value = config.value(id);
            if (MarketService.starCoin(id)) {
                if (plugin.sponsors() != null) {
                    metas.add(new ExchangeMarketJson.Meta(id, "星幣", CurrencyGroups.Group.BASIC.displayName(), false));
                    current.put(id, value);
                }
                continue;
            }
            final CurrencyType type = plugin.currencies().repository().get(id);
            if (type == null) {
                continue;
            }
            metas.add(new ExchangeMarketJson.Meta(id, type.name(), CurrencyGroups.of(type).displayName(), CurrencyArt.artOf(type) != null));
            current.put(id, value);
        }
        final List<ExchangeMarketJson.OrderView> orders = new ArrayList<>();
        for (final ExchangeBook.Order order : service.book().all()) {
            if (order.confirmed() && order.lots() > 0) {
                orders.add(new ExchangeMarketJson.OrderView(order.give(), order.want(), order.lotGive(), order.lotWant(), order.lots()));
            }
        }
        final ZoneId zone = plugin.economy().ledger().zone();
        final Instant now = Instant.now();
        final String fromDay = LocalDate.now(zone).minusDays(HISTORY_DAYS).toString();
        final EconomyRepository repository = plugin.economy().repository();
        running = true;
        plugin.database().async(connection -> {
            final List<ExchangeMarketJson.TradeRow> trades = new ArrayList<>();
            for (final EconomyRepository.Trade trade : repository.tradesSince(connection, now.minus(HISTORY_DAYS, ChronoUnit.DAYS))) {
                trades.add(new ExchangeMarketJson.TradeRow(trade.at(), trade.give(), trade.giveAmount(), trade.want(), trade.wantAmount()));
            }
            final Map<String, Map<String, Long>> dayValues = new HashMap<>();
            for (final EconomyRepository.DayValue row : repository.valuesSince(connection, fromDay)) {
                dayValues.computeIfAbsent(row.day(), k -> new HashMap<>()).put(row.kind(), row.amount());
            }
            return ExchangeMarketJson.build(metas, anchor, current, dayValues, trades, orders, zone, now,
                    STAMP.format(ZonedDateTime.ofInstant(now, zone)));
        }).whenComplete((built, error) -> {
            running = false;
            if (error != null) {
                plugin.getSLF4JLogger().warn("通貨行情網頁的資料做不出來：{}", error.getMessage());
                return;
            }
            json = built;
        });
    }

    /** 通貨的圖示（PNG）；沒有圖回傳 null。 */
    public byte[] icon(final String id) {
        final CurrencyType type = id == null ? null : plugin.currencies().repository().get(id);
        final String art = CurrencyArt.artOf(type);
        final var sprite = art == null ? null : CurrencyArt.sprites().get(art);
        if (sprite == null) {
            return null;
        }
        try {
            return tw.exilecore.web.UiPack.spritePng(sprite);
        } catch (final java.io.IOException e) {
            return null;
        }
    }
}
