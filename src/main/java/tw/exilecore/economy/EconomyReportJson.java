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

    /**
     * 一種通貨的名稱與設定值（主執行緒先抓好，帶到資料庫執行緒用）。
     *
     * @param priced 有參考價值（economy.yml 的 values）；沒有的（映星鏡、地圖機制的通貨…，2026-10）只列數量，不折合金幣
     */
    public record CurrencyMeta(String id, String name, long configured, boolean priced) {
        public CurrencyMeta(final String id, final String name, final long configured) {
            this(id, name, configured, configured > 0);
        }
    }

    /** 網頁上可以選的期間（來源、去處各算一份）。 */
    static final int[] PERIODS = {7, 30};

    private EconomyReportJson() {
    }

    /** 組網頁的 JSON。 */
    /**
     * 市場報告不列的來源。2026-11-02 照站長：管理員發放、管理員扣除也列出來（算進產出、消耗，來源寫「管理員發放」）；
     * 這次之前（2026-10-19 起）不列，管理員補償的通貨被玩家用掉時只看得到消耗、看不到來源。
     */
    static final java.util.Set<String> HIDDEN_SOURCES = java.util.Set.of();

    /**
     * 2026-11-02 照站長：只會消耗的用途（開技能孔、地圖裝置、劫盜、做裝備）：這些的正數是退還（{@link EconomyLedger#refunded}），
     * 算成那一天的消耗變少（最少 0），不算產出、不列在來源。
     */
    static final java.util.Set<String> REFUNDABLE = java.util.Set.of(EconomySources.SKILL, EconomySources.MAP_DEVICE, EconomySources.HEIST,
            EconomySources.CRAFT);

    /**
     * 2026-11-02：管理員發放、扣除從這天起才列。之前的那幾列混著管理員發給自己測試用的（那時候還沒排除管理員），列出來會灌水。
     */
    static final String ADMIN_SHOWN_FROM = "2026-11-02";

    /** 這一列不列在報告上（純函式，測試用）。 */
    static boolean hidden(final EconomyRepository.Flow flow) {
        if (HIDDEN_SOURCES.contains(flow.source())) {
            return true;
        }
        final boolean admin = EconomySources.ADMIN.equals(flow.source()) || EconomySources.ADMIN_TAKE.equals(flow.source());
        return admin && flow.day().compareTo(ADMIN_SHOWN_FROM) < 0;
    }

    static byte[] build(final List<String> days, final List<CurrencyMeta> currencies, final List<EconomyRepository.Flow> flows,
                        final List<EconomyRepository.DayValue> stock, final List<EconomyRepository.DayValue> values,
                        final Map<String, Integer> active, final boolean follow, final String anchor, final String generated) {
        return build(days, currencies, flows, stock, values, active, follow, anchor, generated, Map.of(), Map.of());
    }

    /** 帳本裡追逐級傳奇的種類前綴（LootService.CHASE_KIND）。 */
    static final String CHASE = "chase:";

    /**
     * 同上；chaseNames：追逐級傳奇代號 → 名稱（2026-11-02 照站長：市場報告多一段「追逐級傳奇」，每種出了幾件、從哪裡來；
     * 名單裡的就算還沒出過也列，才看得出還沒出現過）。
     */
    static byte[] build(final List<String> days, final List<CurrencyMeta> currencies, final List<EconomyRepository.Flow> flows,
                        final List<EconomyRepository.DayValue> stock, final List<EconomyRepository.DayValue> values,
                        final Map<String, Integer> active, final boolean follow, final String anchor, final String generated,
                        final Map<String, String> chaseNames, final Map<String, Long> chaseHeld) {
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
            if (hidden(flow)) {
                continue; // 2026-10-19：管理員發放、扣除不列在公開的市場報告（資料庫裡照記，存量照實際）
            }
            final boolean refund = flow.amount() > 0 && REFUNDABLE.contains(flow.source()); // 2026-11-02：退還＝消耗變少
            if (refund) {
                consumed.computeIfAbsent(flow.kind(), kind -> new long[days.size()])[i] -= flow.amount();
            } else if (flow.amount() > 0) {
                produced.computeIfAbsent(flow.kind(), kind -> new long[days.size()])[i] += flow.amount();
            } else {
                consumed.computeIfAbsent(flow.kind(), kind -> new long[days.size()])[i] += -flow.amount();
            }
            for (final int period : PERIODS) {
                if (i >= days.size() - period) {
                    (flow.amount() > 0 && !refund ? sources : sinks).get(period).computeIfAbsent(flow.kind(), kind -> new TreeMap<>())
                            .merge(flow.source(), refund ? -flow.amount() : Math.abs(flow.amount()), Long::sum);
                }
            }
        }
        // 2026-11-02：退還比那天的消耗多（前一天用掉、今天退回來）的不算成負的
        for (final long[] perDay : consumed.values()) {
            for (int d = 0; d < perDay.length; d++) {
                perDay[d] = Math.max(0, perDay[d]);
            }
        }
        for (final int period : PERIODS) {
            for (final Map<String, Long> bySource : sinks.get(period).values()) {
                bySource.values().removeIf(amount -> amount <= 0);
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
            object.addProperty("priced", meta.priced());
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
        final JsonArray chase = new JsonArray();
        final java.util.Set<String> chaseIds = new java.util.LinkedHashSet<>(chaseNames.keySet());
        for (final String kind : java.util.stream.Stream.concat(produced.keySet().stream(), consumed.keySet().stream()).toList()) {
            if (kind.startsWith(CHASE)) {
                chaseIds.add(kind.substring(CHASE.length()));
            }
        }
        chaseIds.addAll(chaseHeld.keySet());
        for (final String id : chaseIds) {
            final JsonObject object = new JsonObject();
            object.addProperty("held", chaseHeld.getOrDefault(id, 0L)); // 2026-11-02：全服現有（管理員的不算）
            object.addProperty("id", id);
            object.addProperty("name", chaseNames.getOrDefault(id, id));
            object.add("produced", longs(produced.get(CHASE + id), days.size()));
            object.add("sources", periods(sources, CHASE + id));
            object.add("consumed", longs(consumed.get(CHASE + id), days.size())); // 2026-11-02：傳奇兌換換掉的
            object.add("sinks", periods(sinks, CHASE + id));
            chase.add(object);
        }
        root.add("chase", chase);
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
