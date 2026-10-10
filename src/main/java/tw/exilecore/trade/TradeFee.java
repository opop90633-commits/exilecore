package tw.exilecore.trade;

import tw.exilecore.economy.EconomyConfig;
import tw.exilecore.item.Rarity;

import java.util.Collection;

/**
 * 面對面交易的手續費（跟市集的購買手續費一樣，免得大家改用面對面交易躲手續費）。
 * <p>
 * 兩邊各付自己的：每個人付「自己收到的東西」估成金幣的 economy.yml {@code trade.fee} 的 percent%，至少 min；
 * 什麼都沒收到不用付。估價：
 * <ul>
 *   <li>通貨：economy.yml 的 values（鑑定卷軸這種不能標價的算 0）。</li>
 *   <li>裝備（含地圖）：商人的價格（{@link EconomyConfig#vendorPrice}：物品等級的普通怪平均掉的金幣 × kills × 稀有度倍率，
 *       拿不到金幣表時 (base + per_level × 物品等級) × 稀有度倍率）。</li>
 *   <li>寶石、藥劑、其他東西：0（只收最低手續費）。</li>
 * </ul>
 * 純計算，不依賴 Bukkit（測得到）。
 */
public final class TradeFee {

    /** 收到的一樣東西（估價用）。 */
    public sealed interface Received permits Currency, Gear, Other {
    }

    /** 通貨：通貨袋裡的，或背包裡的一疊。 */
    public record Currency(String currencyId, int amount) implements Received {
    }

    /** 裝備、地圖。 */
    public record Gear(Rarity rarity, int itemLevel, int amount) implements Received {
    }

    /** 其他東西（寶石、藥劑…）：算 0。 */
    public record Other(int amount) implements Received {
    }

    /**
     * 估價與手續費。
     *
     * @param count 收到幾樣（0 = 什麼都沒收到，不用付）
     * @param value 收到的東西估成多少金幣
     * @param fee   要付的手續費
     */
    public record Quote(int count, long value, long fee) {
        public static final Quote NONE = new Quote(0, 0, 0);

        public boolean nothing() {
            return count == 0;
        }
    }

    private TradeFee() {
    }

    /** 一樣東西估成多少金幣。 */
    public static long value(final EconomyConfig config, final Received thing) {
        return switch (thing) {
            case Currency currency -> multiply(config.value(currency.currencyId()), currency.amount());
            case Gear gear -> multiply(config.vendorPrice(gear.rarity(), gear.itemLevel()), gear.amount());
            case Other other -> 0;
        };
    }

    /** 收到這些東西要付多少手續費；什麼都沒收到是 0。 */
    public static Quote quote(final EconomyConfig config, final Collection<? extends Received> received) {
        if (received.isEmpty()) {
            return Quote.NONE;
        }
        long total = 0;
        for (final Received thing : received) {
            total = add(total, value(config, thing));
        }
        return new Quote(received.size(), total, config.tradeFee().of(total));
    }

    /** 不會溢位的乘法（設定檔寫了很大的價值時封頂）；數量是負的算 0。 */
    static long multiply(final long value, final int amount) {
        if (value <= 0 || amount <= 0) {
            return 0;
        }
        try {
            return Math.multiplyExact(value, amount);
        } catch (final ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    static long add(final long a, final long b) {
        try {
            return Math.addExact(a, b);
        } catch (final ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
