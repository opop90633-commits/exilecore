package tw.exilecore.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 市場報告網頁的資料（/economy/data.json；純資料，不依賴 Bukkit，單元測試也用）：最近 30 天每天的產出、消耗、存量、參考價值，
 * 來源與去處分最近 7 天、30 天各一份，每天有幾個帳號在打怪。只有加總，沒有個人資料。
 */
public final class EconomyReportJson {

    /** 一種通貨的名稱與設定值（主執行緒先抓好，帶到資料庫執行緒用）。 */
    public record CurrencyMeta(String id, String name, long configured) {
    }

    /** 網頁上可以選的期間（來源、去處各算一份）。 */
    static final int[] PERIODS = {7, 30};

    private EconomyReportJson() {
    }

    /** 組網頁的 JSON。 */
    static byte[] build(final List<String> days, final List<CurrencyMeta> currencies, final List<EconomyRepository.Flow> flows,
                        final List<EconomyRepository.DayValue> stock, final List<EconomyRepository.DayValue> values,
                        final Map<String, Integer> active, final boolean follow, final String anchor, final String generated) {
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < days.size(); i++) {
            index.put(days.get(i), i);
        }
        final Map<String, long[]> produced = new HashMap<>();
        final Map<String, long[]> consumed = new HashMap<>();
        // 來源與去處：最近 7 天、30 天各一份（period → 種類 → 來源 → 數量）
        final Map<Integer, Map<String, Map<String, Long>>> sources = new TreeMap<>();
        final Map<Integer, Map<String, Map<String, Long>>> sinks = new TreeMap<>();
        for (final int period : PERIODS) {
            sources.put(period, new HashMap<>());
            sinks.put(period, new HashMap<>());
        }
        for (final EconomyRepository.Flow flow : flows) {
            final Integer i = index.get(flow.day());
            if (i == null || flow.amount() == 0) {
                continue;
            }
            if (flow.amount() > 0) {
                produced.computeIfAbsent(flow.kind(), kind -> new long[days.size()])[i] += flow.amount();
            } else {
                consumed.computeIfAbsent(flow.kind(), kind -> new long[days.size()])[i] += -flow.amount();
            }
            for (final int period : PERIODS) {
                if (i >= days.size() - period) {
                    (flow.amount() > 0 ? sources : sinks).get(period).computeIfAbsent(flow.kind(), kind -> new TreeMap<>())
                            .merge(flow.source(), Math.abs(flow.amount()), Long::sum);
                }
            }
        }
        final Map<String, Long[]> stockByKind = byDay(stock, index, days.size());
        final Map<String, Long[]> valueByKind = byDay(values, index, days.size());

        final JsonObject root = new JsonObject();
        root.addProperty("generated", generated);
        root.addProperty("follow", follow);
        root.addProperty("anchor", anchor);
        final JsonArray dayArray = new JsonArray();
        days.forEach(dayArray::add);
        root.add("days", dayArray);
        final JsonArray currencyArray = new JsonArray();
        for (final CurrencyMeta meta : currencies) {
            final JsonObject object = new JsonObject();
            object.addProperty("id", meta.id());
            object.addProperty("name", meta.name());
            object.addProperty("configured", meta.configured());
            object.add("produced", longs(produced.get(meta.id()), days.size()));
            object.add("consumed", longs(consumed.get(meta.id()), days.size()));
            object.add("stock", nullableLongs(stockByKind.get(meta.id()), days.size()));
            object.add("value", nullableLongs(valueByKind.get(meta.id()), days.size()));
            object.add("sources", periods(sources, meta.id()));
            object.add("sinks", periods(sinks, meta.id()));
            currencyArray.add(object);
        }
        root.add("currencies", currencyArray);
        final JsonObject gold = new JsonObject();
        gold.add("produced", longs(produced.get(EconomyRepository.GOLD), days.size()));
        gold.add("consumed", longs(consumed.get(EconomyRepository.GOLD), days.size()));
        gold.add("stock", nullableLongs(stockByKind.get(EconomyRepository.GOLD), days.size()));
        gold.add("sources", periods(sources, EconomyRepository.GOLD));
        gold.add("sinks", periods(sinks, EconomyRepository.GOLD));
        root.add("gold", gold);
        final JsonArray activeArray = new JsonArray();
        for (final String day : days) {
            activeArray.add(active.getOrDefault(day, 0));
        }
        root.add("active", activeArray);
        final JsonObject names = new JsonObject();
        for (final String source : EconomySources.ALL) {
            names.addProperty(source, EconomySources.sourceName(source));
        }
        root.add("sourceNames", names);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Long[]> byDay(final List<EconomyRepository.DayValue> rows, final Map<String, Integer> index, final int size) {
        final Map<String, Long[]> map = new HashMap<>();
        for (final EconomyRepository.DayValue row : rows) {
            final Integer i = index.get(row.day());
            if (i != null) {
                map.computeIfAbsent(row.kind(), kind -> new Long[size])[i] = row.amount();
            }
        }
        return map;
    }

    private static JsonArray longs(final long[] values, final int size) {
        final JsonArray array = new JsonArray();
        for (int i = 0; i < size; i++) {
            array.add(values == null ? 0 : values[i]);
        }
        return array;
    }

    private static JsonArray nullableLongs(final Long[] values, final int size) {
        final JsonArray array = new JsonArray();
        for (int i = 0; i < size; i++) {
            if (values == null || values[i] == null) {
                array.add(JsonNull.INSTANCE);
            } else {
                array.add(values[i]);
            }
        }
        return array;
    }

    /** {"7": {來源: 數量}, "30": {...}}。 */
    private static JsonObject periods(final Map<Integer, Map<String, Map<String, Long>>> byPeriod, final String kind) {
        final JsonObject object = new JsonObject();
        for (final Map.Entry<Integer, Map<String, Map<String, Long>>> entry : byPeriod.entrySet()) {
            object.add(Integer.toString(entry.getKey()), map(entry.getValue().get(kind)));
        }
        return object;
    }

    private static JsonObject map(final Map<String, Long> values) {
        final JsonObject object = new JsonObject();
        if (values != null) {
            for (final Map.Entry<String, Long> entry : values.entrySet()) {
                object.addProperty(entry.getKey(), entry.getValue());
            }
        }
        return object;
    }
}
