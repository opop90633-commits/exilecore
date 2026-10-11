package tw.exilecore.economy;

import tw.exilecore.ExileCore;
import tw.exilecore.currency.CurrencyType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * 經濟報表（排程在主執行緒，查詢都在資料庫執行緒）：
 * <ul>
 *   <li>每天一次（換日後第一次檢查、或 /ec economy update）：算全服存量、用交易所最近幾天的成交重算參考價值，換上新的參考價值。</li>
 *   <li>網頁「市場報告」的資料（/economy/data.json）：最近 30 天每天的產出、消耗、存量、參考價值，只有加總，沒有個人資料。</li>
 *   <li>/ec economy 的查詢。</li>
 * </ul>
 */
public final class EconomyReports {

    /** 網頁看幾天。 */
    public static final int WEB_DAYS = 30;

    /** 一天一次的結果。 */
    public record Daily(String day, Map<String, Long> stock, ReferenceValues.Result values) {
    }

    /** 一段時間的加總：每種東西（通貨代號或 gold）、每個來源的進出。 */
    public record Totals(String fromDay, String toDay, Map<String, Map<String, Long>> flows, Map<String, Long> stock,
                         String stockDay, Map<String, Long> valuesThen, Map<String, Integer> pairs) {
        /** 這種東西的產出（正的加起來）。 */
        public long produced(final String kind) {
            long total = 0;
            for (final long amount : flows.getOrDefault(kind, Map.of()).values()) {
                total += Math.max(0, amount);
            }
            return total;
        }

        /** 這種東西的消耗（負的加起來，回傳正數）。 */
        public long consumed(final String kind) {
            long total = 0;
            for (final long amount : flows.getOrDefault(kind, Map.of()).values()) {
                total += Math.max(0, -amount);
            }
            return total;
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ExileCore plugin;
    private final EconomyRepository repository;
    private final EconomyLedger ledger;
    /** 最近一次每天的工作是哪一天做的（主執行緒）。 */
    private String lastDaily;
    private boolean dailyRunning;
    private volatile byte[] webJson = "{\"days\":[]}".getBytes(StandardCharsets.UTF_8);

    public EconomyReports(final ExileCore plugin, final EconomyRepository repository, final EconomyLedger ledger) {
        this.plugin = plugin;
        this.repository = repository;
        this.ledger = ledger;
    }

    private EconomyConfig config() {
        return plugin.economy().config();
    }

    /** 啟動時：讀回最近一次算的參考價值；今天還沒算過就算一次。 */
    public void start() {
        plugin.database().async(repository::latestValues).whenComplete((snapshot, error) -> sync(() -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("讀取參考價值失敗，先用 economy.yml 的設定值：{}", error.getMessage());
                return;
            }
            if (snapshot.day() != null) {
                config().setMarketValues(snapshot.values());
                if (snapshot.day().equals(ledger.today())) {
                    lastDaily = snapshot.day();
                }
            }
            tick();
            refreshWeb();
        }));
    }

    /** 每天的工作是不是正在跑。 */
    public boolean running() {
        return dailyRunning;
    }

    /** 每分鐘：換日了就做每天的工作。 */
    public void tick() {
        if (!ledger.today().equals(lastDaily) && !dailyRunning) {
            runDaily();
        }
    }

    /**
     * 每天的工作：先把流水帳寫進去，再算存量與參考價值（資料庫執行緒），回到主執行緒換上新的參考價值。
     * 參考價值一定從「前一天」存下來的值算起（不是記憶體裡的）：同一天重算幾次（/ec economy update）都只走一步。
     * 還原備份中（存檔暫停）什麼都不寫，結果是 null。
     */
    public CompletableFuture<Daily> runDaily() {
        dailyRunning = true;
        ledger.flush();
        final String day = ledger.today();
        final EconomyConfig config = config();
        final ReferenceValues.Settings settings = config.reference();
        final Map<String, Long> configured = new LinkedHashMap<>(config.values());
        final Instant since = Instant.now().minus(settings.days(), ChronoUnit.DAYS);
        final CompletableFuture<Daily> result = new CompletableFuture<>();
        plugin.database().async(connection -> {
            if (plugin.characters().writesPaused()) {
                return null; // 還原備份中：不寫（還原完要重開伺服器）
            }
            final Map<String, Long> stock = repository.computeStock(connection);
            repository.saveStock(connection, day, stock);
            final List<ReferenceValues.Trade> trades = new ArrayList<>();
            for (final EconomyRepository.Trade trade : repository.tradesSince(connection, since)) {
                trades.add(new ReferenceValues.Trade(trade.give(), trade.giveAmount(), trade.want(), trade.wantAmount(),
                        trade.maker() == null ? null : trade.maker().toString(), trade.taker() == null ? null : trade.taker().toString()));
            }
            final Map<String, Long> previous = repository.valuesBefore(connection, day).values();
            final ReferenceValues.Result values = ReferenceValues.compute(trades, previous, configured, settings);
            repository.saveValues(connection, day, values.values(), values.pairs());
            return new Daily(day, stock, values);
        }).whenComplete((daily, error) -> sync(() -> {
            dailyRunning = false;
            if (error != null) {
                plugin.getSLF4JLogger().warn("經濟的每天工作（存量、參考價值）失敗，一分鐘後再試：{}", error.getMessage());
                result.completeExceptionally(error);
                return;
            }
            if (daily == null) {
                result.complete(null);
                return;
            }
            lastDaily = daily.day();
            if (config().reference().enabled()) {
                config().setMarketValues(daily.values().values());
            } else {
                config().setMarketValues(Map.of());
            }
            plugin.getSLF4JLogger().info("經濟：{} 的存量與參考價值算好了（{} 種通貨）", daily.day(), daily.values().values().size());
            refreshWeb();
            result.complete(daily);
        }));
        return result;
    }

    // ---- 查詢（/ec economy） ----

    /** 最近 days 天（含今天）的加總；查之前先把流水帳寫進去。 */
    public CompletableFuture<Totals> totals(final int days) {
        ledger.flush();
        final String from = ledger.daysAgo(days);
        final String to = ledger.today();
        return plugin.database().async(connection -> {
            final Map<String, Map<String, Long>> flows = new TreeMap<>();
            for (final EconomyRepository.Flow flow : repository.flowsSince(connection, from)) {
                flows.computeIfAbsent(flow.kind(), kind -> new TreeMap<>()).merge(flow.source(), flow.amount(), Long::sum);
            }
            // 最新一天的存量
            final Map<String, Long> stock = new HashMap<>();
            String stockDay = null;
            for (final EconomyRepository.DayValue row : repository.stockSince(connection, ledger.daysAgo(Math.max(days, 14)))) {
                if (stockDay == null || row.day().compareTo(stockDay) > 0) {
                    stockDay = row.day();
                    stock.clear();
                }
                if (row.day().equals(stockDay)) {
                    stock.put(row.kind(), row.amount());
                }
            }
            // 這段時間第一天的參考價值（看漲跌）
            final Map<String, Long> valuesThen = new HashMap<>();
            String firstDay = null;
            for (final EconomyRepository.DayValue row : repository.valuesSince(connection, from)) {
                if (firstDay == null) {
                    firstDay = row.day();
                }
                if (row.day().equals(firstDay)) {
                    valuesThen.put(row.kind(), row.amount());
                }
            }
            final EconomyRepository.ValueSnapshot latest = repository.latestValues(connection);
            return new Totals(from, to, flows, stock, stockDay, valuesThen, latest.pairs());
        });
    }

    /** 最近 days 天每個帳號的收入（多的在前）。 */
    public CompletableFuture<List<EconomyRepository.Earner>> earners(final int days) {
        ledger.flush();
        final String from = ledger.daysAgo(days);
        return plugin.database().async(connection -> repository.earnersSince(connection, from));
    }

    // ---- 網頁 ----

    /** 網頁的資料（JSON）。 */
    public byte[] webJson() {
        return webJson;
    }

    /** 重做網頁的資料（資料庫執行緒）。 */
    public void refreshWeb() {
        final List<EconomyReportJson.CurrencyMeta> currencies = currencyMeta();
        final EconomyConfig config = config();
        final boolean follow = config.followsMarket();
        final String anchor = config.reference().anchor();
        final ZonedDateTime now = ZonedDateTime.now(ledger.zone());
        final List<String> days = new ArrayList<>();
        final LocalDate today = now.toLocalDate();
        for (int i = WEB_DAYS - 1; i >= 0; i--) {
            days.add(today.minusDays(i).toString());
        }
        final String from = days.getFirst();
        plugin.database().async(connection -> {
            final List<EconomyRepository.Flow> flows = repository.flowsSince(connection, from);
            final List<EconomyRepository.DayValue> stock = repository.stockSince(connection, from);
            final List<EconomyRepository.DayValue> values = repository.valuesSince(connection, from);
            final Map<String, Integer> active = repository.activeAccountsSince(connection, from);
            return EconomyReportJson.build(days, currencies, flows, stock, values, active, follow, anchor, STAMP.format(now));
        }).whenComplete((json, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("市場報告的資料做不出來：{}", error.getMessage());
                return;
            }
            webJson = json;
        });
    }

    /**
     * 報告列的通貨：能標價的（照設定檔的順序），再加上沒有參考價值的（2026-10：映星鏡、地圖機制的通貨…——
     * 只列產出、消耗的數量，不折合金幣、不算進總價值；鑑定卷軸免費領，不列）。
     */
    public List<EconomyReportJson.CurrencyMeta> currencyMeta() {
        final List<EconomyReportJson.CurrencyMeta> list = new ArrayList<>();
        final Map<String, Long> values = config().values();
        for (final Map.Entry<String, Long> entry : values.entrySet()) {
            final CurrencyType type = plugin.currencies().repository().get(entry.getKey());
            list.add(new EconomyReportJson.CurrencyMeta(entry.getKey(), type == null ? entry.getKey() : type.name(), entry.getValue(), true));
        }
        for (final CurrencyType type : plugin.currencies().repository().all()) {
            if (values.containsKey(type.id()) || type.operation() == tw.exilecore.currency.CurrencyOperation.IDENTIFY
                    || type.name() == null || type.name().isBlank()) {
                continue;
            }
            list.add(new EconomyReportJson.CurrencyMeta(type.id(), type.name(), 0, false));
        }
        return list;
    }

    private void sync(final Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
