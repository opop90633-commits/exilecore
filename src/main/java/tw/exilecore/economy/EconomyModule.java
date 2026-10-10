package tw.exilecore.economy;

import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/**
 * economy 模組：帳號共用的金幣、通貨的價值與手續費（data/economy.yml）、付款與收款的共同做法、交易紀錄，
 * 以及經濟報表：流水帳（每天的產出與消耗）、全服存量、跟著交易所成交價走的參考價值（/ec economy、網頁的市場報告）。
 * 面對面交易、市集、通貨交易所、商人各自是一個模組，都用這裡的東西。
 */
public final class EconomyModule implements Module {

    /** 流水帳多久寫一次資料庫（tick）。 */
    private static final long FLUSH_TICKS = 20L * 60 * 5;
    /** 多久檢查一次換日（tick）。 */
    private static final long DAY_CHECK_TICKS = 20L * 60;

    private final ExileCore plugin;
    private final EconomyConfig config = new EconomyConfig();
    private final TradeLogRepository tradeLog = new TradeLogRepository();
    private final EconomyRepository repository = new EconomyRepository();
    private final EconomyLedger ledger;
    private final EconomyReports reports;
    private GoldService gold;
    private Wallet wallet;
    private BukkitTask flushTask;
    private BukkitTask dayTask;
    private BukkitTask webTask;

    public EconomyModule(final ExileCore plugin) {
        this.plugin = plugin;
        this.ledger = new EconomyLedger(plugin, repository);
        this.reports = new EconomyReports(plugin, repository, ledger);
    }

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public void enable() {
        try {
            config.load(plugin.dataFolder().resolve("data"));
        } catch (final Exception e) {
            // 金幣不能因為 economy.yml 寫錯就整個停掉：用預設值
            plugin.getSLF4JLogger().error("讀取 data/economy.yml 失敗，先用預設值：{}", e.getMessage());
        }
        // 商人、賭博、面對面交易的估價照「同等級普通怪平均掉的金幣」算（drops.yml，怪物模組在這個模組之後載入，用的時候才去拿）
        config.goldReference(level -> plugin.monsterModule() == null || plugin.monsterModule().drops() == null
                ? 0 : plugin.monsterModule().drops().averageGold(level));
        // 全部模組啟用之後檢查一次：拿不到金幣表的話價格會退回舊的算法（便宜很多），在主控台說一聲
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (config.referenceGold(60) <= 0) {
                plugin.getSLF4JLogger().warn("商人、賭博、面對面交易的估價拿不到 drops.yml 的金幣表（怪物模組沒有啟用？），先用 economy.yml 的 base／per_level 舊算法");
            }
        });
        gold = new GoldService(plugin, ledger);
        wallet = new Wallet(plugin);
        plugin.getServer().getPluginManager().registerEvents(new GoldListener(plugin, gold), plugin);
        flushTask = plugin.repeat("經濟流水帳", ledger::flush, FLUSH_TICKS, FLUSH_TICKS);
        dayTask = plugin.repeat("經濟換日", reports::tick, DAY_CHECK_TICKS, DAY_CHECK_TICKS);
        // 網頁的市場報告每 10 分鐘更新一次（流水帳寫進去之後）
        webTask = plugin.repeat("市場報告", reports::refreshWeb, FLUSH_TICKS * 2 + 100, FLUSH_TICKS * 2);
        reports.start();
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data")); // 寫錯時丟出例外，保留原本的設定
        if (!config.reference().enabled()) {
            config.setMarketValues(java.util.Map.of()); // 關掉跟著市價走：馬上回到設定值
        }
    }

    @Override
    public void disable() {
        for (final BukkitTask task : new BukkitTask[] {flushTask, dayTask, webTask}) {
            if (task != null) {
                task.cancel();
            }
        }
        flushTask = null;
        dayTask = null;
        webTask = null;
        ledger.flush(); // 排進資料庫執行緒：關資料庫前會等它寫完
    }

    public GoldService gold() {
        return gold;
    }

    public EconomyConfig config() {
        return config;
    }

    public Wallet wallet() {
        return wallet;
    }

    public TradeLogRepository tradeLog() {
        return tradeLog;
    }

    /** 流水帳：每種通貨與金幣的產出與消耗。 */
    public EconomyLedger ledger() {
        return ledger;
    }

    /** 經濟報表：每天的工作、查詢、網頁資料。 */
    public EconomyReports reports() {
        return reports;
    }

    /** 經濟報表的資料表（交易所記成交用）。 */
    public EconomyRepository repository() {
        return repository;
    }
}
