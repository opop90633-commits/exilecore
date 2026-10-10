package tw.exilecore.market;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通貨交易所的掛單簿（PoE2 的 Currency Exchange）。純邏輯，不依賴 Bukkit，單元測試直接跑。
 * <p>
 * 一張單是「拿 giveTotal 個 A 換 wantTotal 個 B」。通貨不能切開，所以一張單只能照它的最簡比例整批成交：
 * 例如「10 個 A 換 30 個 B」的一批是「1 個 A 換 3 個 B」。新的單先跟價格合得來的舊單成交（舊單的價格，便宜的先、早掛的先），
 * 剩下的照自己的價格（無條件捨去，不會比你開的價格貴）掛著等別人。
 */
public final class ExchangeBook {

    /** 一張掛單。remaining 是還沒成交的掛出去的通貨。 */
    public static final class Order {
        private final String id;
        private final UUID account;
        private final String playerName;
        private final String give;
        private final int giveTotal;
        private final String want;
        private final int wantTotal;
        private final long createdAt;
        private final int lotGive;
        private final int lotWant;
        private int remaining;
        /** 掛單的那筆操作已經寫進資料庫（之前別人不能跟它成交）。 */
        private boolean confirmed;
        private final String opId;

        public Order(final String id, final UUID account, final String playerName, final String give, final int giveTotal,
                     final String want, final int wantTotal, final long createdAt, final int remaining, final boolean confirmed,
                     final String opId) {
            if (giveTotal <= 0 || wantTotal <= 0) {
                throw new IllegalArgumentException("數量要大於 0");
            }
            this.id = id;
            this.account = account;
            this.playerName = playerName;
            this.give = give;
            this.giveTotal = giveTotal;
            this.want = want;
            this.wantTotal = wantTotal;
            this.createdAt = createdAt;
            final int divisor = gcd(giveTotal, wantTotal);
            this.lotGive = giveTotal / divisor;
            this.lotWant = wantTotal / divisor;
            // 只能整批成交：剩下的數量不是一批的倍數時，多出來的零頭算不能成交（取消時退回）
            this.remaining = Math.max(0, Math.min(giveTotal, remaining));
            this.confirmed = confirmed;
            this.opId = opId;
        }

        public String id() {
            return id;
        }

        public UUID account() {
            return account;
        }

        public String playerName() {
            return playerName;
        }

        public String give() {
            return give;
        }

        public int giveTotal() {
            return giveTotal;
        }

        public String want() {
            return want;
        }

        public int wantTotal() {
            return wantTotal;
        }

        public long createdAt() {
            return createdAt;
        }

        /** 一批：拿出 lotGive 個 give，換 lotWant 個 want。 */
        public int lotGive() {
            return lotGive;
        }

        public int lotWant() {
            return lotWant;
        }

        /** 還沒成交的數量（掛出去的通貨）。 */
        public int remaining() {
            return remaining;
        }

        /** 還能成交幾批。 */
        public int lots() {
            return remaining / lotGive;
        }

        /** 還想換到多少（照比例）。 */
        public int remainingWant() {
            return lots() * lotWant;
        }

        /** 已經成交掉的數量（掛出去的通貨）。 */
        public int taken() {
            return giveTotal - remaining;
        }

        public boolean confirmed() {
            return confirmed;
        }

        public String opId() {
            return opId;
        }

        void confirm() {
            confirmed = true;
        }
    }

    /**
     * 跟一張舊單的成交。
     *
     * @param maker      被成交的舊單
     * @param lots       幾批
     * @param makerGave  舊單拿出來的（新單收到的）數量
     * @param makerGot   舊單收到的（新單付出的）數量
     */
    public record Fill(Order maker, int lots, int makerGave, int makerGot) {
    }

    /**
     * 下一張新單的結果。
     *
     * @param fills    跟哪些舊單成交了
     * @param paid     新單付出去的（成交掉的）
     * @param received 新單收到的
     * @param restGive 剩下要掛著的數量（掛出去的通貨；0 = 不掛）
     * @param restWant 剩下要掛著的想換到的數量
     * @param refund   用不到、不用拿走的數量（給的 − 付出去的 − 掛著的）
     */
    public record Result(List<Fill> fills, int paid, int received, int restGive, int restWant, int refund) {
        public boolean resting() {
            return restGive > 0 && restWant > 0;
        }
    }

    private final Map<String, Order> orders = new LinkedHashMap<>();

    public void add(final Order order) {
        orders.put(order.id(), order);
    }

    public Order get(final String id) {
        return orders.get(id);
    }

    public Order remove(final String id) {
        return orders.remove(id);
    }

    public Collection<Order> all() {
        return Collections.unmodifiableCollection(orders.values());
    }

    /** 某個帳號的掛單（早的在前）。 */
    public List<Order> of(final UUID account) {
        final List<Order> list = new ArrayList<>();
        for (final Order order : orders.values()) {
            if (order.account().equals(account)) {
                list.add(order);
            }
        }
        return list;
    }

    public void confirm(final String opId) {
        for (final Order order : orders.values()) {
            if (opId.equals(order.opId())) {
                order.confirm();
            }
        }
    }

    /**
     * 想用 give 換 want 的舊單裡，「給 want、要 give」的那些（可以跟一張「給 give、要 want」的新單成交的），
     * 依價格（每個 want 要多少 give，便宜的在前）再依時間排好。不含自己的單、還沒寫進資料庫的單。
     */
    public List<Order> makersFor(final String give, final String want, final UUID taker) {
        final List<Order> list = new ArrayList<>();
        for (final Order order : orders.values()) {
            if (order.give().equals(want) && order.want().equals(give) && order.confirmed() && order.lots() > 0
                    && (taker == null || !order.account().equals(taker))) {
                list.add(order);
            }
        }
        // 舊單的價格（新單的角度）：每個 want 要付 order.wantTotal / order.giveTotal 個 give
        list.sort(Comparator.<Order>comparingDouble(order -> (double) order.wantTotal() / order.giveTotal())
                .thenComparingLong(Order::createdAt));
        return list;
    }

    /**
     * 新單：最多拿 giveAmount 個 give，想換 wantAmount 個 want。跟舊單成交（改掉舊單的剩餘數量，用完的舊單移除），
     * 回傳結果；剩下的要不要掛著由呼叫端決定（{@link Result#resting()} 的數量照這裡算）。
     */
    public Result match(final String give, final int giveAmount, final String want, final int wantAmount, final UUID taker) {
        return run(give, giveAmount, want, wantAmount, taker, true);
    }

    /** 試算（不改任何單）：跟 {@link #match} 一樣的結果，下單前給玩家看。 */
    public Result simulate(final String give, final int giveAmount, final String want, final int wantAmount, final UUID taker) {
        return run(give, giveAmount, want, wantAmount, taker, false);
    }

    private Result run(final String give, final int giveAmount, final String want, final int wantAmount, final UUID taker,
                       final boolean apply) {
        if (giveAmount <= 0 || wantAmount <= 0) {
            throw new IllegalArgumentException("數量要大於 0");
        }
        final List<Fill> fills = new ArrayList<>();
        int paid = 0;
        int received = 0;
        for (final Order maker : makersFor(give, want, taker)) {
            final int needWant = wantAmount - received;
            final int budget = giveAmount - paid;
            if (needWant <= 0 || budget <= 0) {
                break;
            }
            // 價格合不合：舊單每個 want 要 maker.wantTotal / maker.giveTotal 個 give，不能比新單願意付的 giveAmount / wantAmount 貴
            if ((long) maker.wantTotal() * wantAmount > (long) giveAmount * maker.giveTotal()) {
                break; // 後面的只會更貴
            }
            final int byBudget = budget / maker.lotWant();
            final int byNeed = (needWant + maker.lotGive() - 1) / maker.lotGive();
            final int lots = Math.min(maker.lots(), Math.min(byBudget, byNeed));
            if (lots <= 0) {
                continue; // 這張的一批太大，看下一張
            }
            final int gave = lots * maker.lotGive();
            final int got = lots * maker.lotWant();
            paid += got;
            received += gave;
            fills.add(new Fill(maker, lots, gave, got));
            if (apply) {
                maker.remaining -= gave;
                if (maker.lots() <= 0) {
                    orders.remove(maker.id());
                }
            }
        }
        int restGive = 0;
        int restWant = 0;
        if (received < wantAmount) {
            restWant = wantAmount - received;
            // 照新單原本的價格算剩下要押多少（無條件捨去：不會比你開的價格貴）
            restGive = (int) Math.min(giveAmount - paid, (long) restWant * giveAmount / wantAmount);
            if (restGive <= 0) {
                restGive = 0;
                restWant = 0;
            }
        }
        final int refund = giveAmount - paid - restGive;
        return new Result(fills, paid, received, restGive, restWant, refund);
    }

    static int gcd(final int a, final int b) {
        int x = Math.abs(a);
        int y = Math.abs(b);
        while (y != 0) {
            final int t = x % y;
            x = y;
            y = t;
        }
        return Math.max(1, x);
    }
}
