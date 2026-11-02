package tw.exilecore.economy;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 經濟的流水帳（主執行緒）：每種通貨與金幣，每天從哪裡來（產出）、到哪裡去（消耗），以及每個帳號每天的收入。
 * 先記在記憶體，每 5 分鐘與關伺服器時一起寫進資料庫（economy_flow、economy_income）；寫入失敗整批留著下次再寫。
 * <p>
 * 只記「進出遊戲」：怪物掉的、拆解出來的、NPC 給的是產出；做裝備用掉的、手續費、商人、垃圾桶是消耗。
 * 玩家之間換手（交易、市集、交易所）不算，東西還在遊戲裡。
 */
public final class EconomyLedger {

    /** 金幣（其他 kind 是通貨代號）。 */
    public static final String GOLD = EconomyRepository.GOLD;

    // ---- 來源（見 EconomySources；這裡留一份一樣的常數，呼叫的地方不用改）----
    public static final String DROP = EconomySources.DROP;
    public static final String FILTER = EconomySources.FILTER;
    public static final String SALVAGE = EconomySources.SALVAGE;
    public static final String NPC = EconomySources.NPC;
    public static final String ADMIN = EconomySources.ADMIN;
    public static final String MERITS = EconomySources.MERITS;
    public static final String EVENT = EconomySources.EVENT;
    public static final String CRAFT = EconomySources.CRAFT;
    public static final String SKILL = EconomySources.SKILL;
    public static final String MAP_DEVICE = EconomySources.MAP_DEVICE;
    public static final String TRASH = EconomySources.TRASH;
    public static final String DELETE = EconomySources.DELETE;
    public static final String VENDOR = EconomySources.VENDOR;
    public static final String VENDOR_REFRESH = EconomySources.VENDOR_REFRESH;
    public static final String GAMBLE = EconomySources.GAMBLE;
    public static final String SHOP = EconomySources.SHOP;
    public static final String RESPEC = EconomySources.RESPEC;
    public static final String FEE_TRADE = EconomySources.FEE_TRADE;
    public static final String FEE_LIST = EconomySources.FEE_LIST;
    public static final String FEE_BUY = EconomySources.FEE_BUY;
    public static final String FEE_EXCHANGE = EconomySources.FEE_EXCHANGE;
    // 2026-10-18：新的消耗來源（活動兌換、管理員扣除、拆解用掉）
    public static final String EVENT_SHOP = EconomySources.EVENT_SHOP;
    public static final String ADMIN_TAKE = EconomySources.ADMIN_TAKE;
    public static final String SALVAGE_COST = EconomySources.SALVAGE_COST;
    public static final String CARDS = EconomySources.CARDS; // 2026-10-19 星諭卡
    public static final String CARD_GAMBLE = EconomySources.CARD_GAMBLE;
    public static final String SLOTS_BET = EconomySources.SLOTS_BET; // 2026-11-02 拉霸機
    public static final String SLOTS = EconomySources.SLOTS;

    /** 來源給人看的名字。 */
    public static String sourceName(final String source) {
        return EconomySources.sourceName(source);
    }

    private record Key(String day, String kind, String source) {
    }

    private record IncomeKey(String day, UUID account) {
    }

    private static final class Earned {
        private String name;
        private int kills;
        private long gold;
        private long currencyValue;
    }

    private final ExileCore plugin;
    private final EconomyRepository repository;
    private final ZoneId zone = ZoneId.systemDefault();
    private Map<Key, Long> pending = new HashMap<>();
    private Map<IncomeKey, Earned> income = new HashMap<>();

    public EconomyLedger(final ExileCore plugin, final EconomyRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /** 今天（伺服器時區）："2026-10-02"。 */
    public String today() {
        return LocalDate.now(zone).toString();
    }

    /** 往前 days 天（含今天）的第一天。 */
    public String daysAgo(final int days) {
        return LocalDate.now(zone).minusDays(Math.max(0, days - 1)).toString();
    }

    public ZoneId zone() {
        return zone;
    }

    /**
     * 2026-11-02 照站長：管理員（op、exilecore.admin、economy.yml 的 report.exclude_players）的取得與消耗不記進市場報告。
     * 用法：{@code ledger.of(player).produced(...)}；player 是 null（不知道是誰）照記。這次之前每個人都記。
     */
    public Scope of(final org.bukkit.entity.Player player) {
        return player != null && excluded(player) ? Scope.SKIP : all;
    }

    /** 同上，只知道名字（用掉通貨的紀錄）。 */
    public Scope ofName(final String name) {
        return name != null && plugin.economy() != null && plugin.economy().config().reportExcluded(name) ? Scope.SKIP : all;
    }

    /** 這個玩家的進出不記進市場報告。 */
    public boolean excluded(final org.bukkit.entity.Player player) {
        if (player == null) {
            return false;
        }
        if (player.isOp() || player.hasPermission("exilecore.admin")) {
            return true;
        }
        return plugin.economy() != null && plugin.economy().config().reportExcluded(player.getName());
    }

    /** 記帳的對象：照記（{@link #of} 一般玩家）或不記（管理員）。 */
    public static class Scope {
        static final Scope SKIP = new Scope(null);
        private final EconomyLedger ledger;

        Scope(final EconomyLedger ledger) {
            this.ledger = ledger;
        }

        public void produced(final String kind, final String source, final long amount) {
            if (ledger != null) {
                ledger.produced(kind, source, amount);
            }
        }

        public void consumed(final String kind, final String source, final long amount) {
            if (ledger != null) {
                ledger.consumed(kind, source, amount);
            }
        }

        public void gold(final String source, final long delta) {
            if (ledger != null) {
                ledger.gold(source, delta);
            }
        }
    }

    private final Scope all = new Scope(this);

    /** 記一筆進出：delta 正的是產出、負的是消耗。 */
    public void record(final String kind, final String source, final long delta) {
        if (kind == null || source == null || delta == 0) {
            return;
        }
        pending.merge(new Key(today(), kind, source), delta, Long::sum);
    }

    /** 產出 amount 個（amount ≤ 0 不記）。 */
    public void produced(final String kind, final String source, final long amount) {
        if (amount > 0) {
            record(kind, source, amount);
        }
    }

    /** 消耗 amount 個（amount ≤ 0 不記）。 */
    public void consumed(final String kind, final String source, final long amount) {
        if (amount > 0) {
            record(kind, source, -amount);
        }
    }

    /** 金幣的進出（正的產出、負的消耗）。 */
    public void gold(final String source, final long delta) {
        record(GOLD, source, delta);
    }

    /** 一個帳號殺了一隻怪拿到的東西：金幣（含過濾換的）、通貨折合的金幣。 */
    public void income(final Player player, final int kills, final long gold, final long currencyValue) {
        if (kills <= 0 && gold <= 0 && currencyValue <= 0) {
            return;
        }
        final Earned earned = income.computeIfAbsent(new IncomeKey(today(), player.getUniqueId()), key -> new Earned());
        earned.name = player.getName();
        earned.kills += Math.max(0, kills);
        earned.gold += Math.max(0, gold);
        earned.currencyValue += Math.max(0, currencyValue);
    }

    /** 還沒寫進資料庫的筆數（/ec economy 看）。 */
    public int pendingCount() {
        return pending.size() + income.size();
    }

    /** 寫進資料庫（背景執行緒）；失敗整批放回去，下次再寫。 */
    /** 2026-11-02：清掉市場報告時，記憶體裡還沒寫進去的產出消耗也丟掉（收入照留）。 */
    public void discardPending() {
        pending = new HashMap<>();
    }

    public void flush() {
        if (pending.isEmpty() && income.isEmpty()) {
            return;
        }
        final Map<Key, Long> flows = pending;
        final Map<IncomeKey, Earned> earned = income;
        pending = new HashMap<>();
        income = new HashMap<>();
        final List<EconomyRepository.Flow> flowRows = new ArrayList<>();
        for (final Map.Entry<Key, Long> entry : flows.entrySet()) {
            flowRows.add(new EconomyRepository.Flow(entry.getKey().day(), entry.getKey().kind(), entry.getKey().source(), entry.getValue()));
        }
        final List<EconomyRepository.Income> incomeRows = new ArrayList<>();
        for (final Map.Entry<IncomeKey, Earned> entry : earned.entrySet()) {
            final Earned value = entry.getValue();
            incomeRows.add(new EconomyRepository.Income(entry.getKey().day(), entry.getKey().account(), value.name, value.kills,
                    value.gold, value.currencyValue));
        }
        plugin.database().async(connection -> {
            if (plugin.characters().writesPaused()) {
                return null; // 還原備份中：這段時間的進出屬於被丟掉的那條時間線，不寫
            }
            repository.addAll(connection, flowRows, incomeRows);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error == null) {
                return;
            }
            plugin.getSLF4JLogger().warn("經濟流水帳寫入失敗，下次再寫：{}", error.getMessage());
            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> requeue(flows, earned));
            }
        });
    }

    private void requeue(final Map<Key, Long> flows, final Map<IncomeKey, Earned> earned) {
        for (final Map.Entry<Key, Long> entry : flows.entrySet()) {
            pending.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        for (final Map.Entry<IncomeKey, Earned> entry : earned.entrySet()) {
            final Earned current = income.computeIfAbsent(entry.getKey(), key -> new Earned());
            final Earned old = entry.getValue();
            current.name = current.name == null ? old.name : current.name;
            current.kills += old.kills;
            current.gold += old.gold;
            current.currencyValue += old.currencyValue;
        }
    }
}
