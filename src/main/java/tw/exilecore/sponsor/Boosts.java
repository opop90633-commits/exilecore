package tw.exilecore.sponsor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全服加倍（2026-10，贊助）現在的倍數：經驗、掉落數量、稀有度、金幣讀設定時再乘（加）這裡的值。
 * 純資料，不依賴 Bukkit；主執行緒改，讀的地方也在主執行緒。沒有贊助模組時全部是不加倍。
 */
public final class Boosts {

    /** 一種加倍：短名稱（「經驗」）、什麼時候結束（毫秒）、誰贊助的（最後一個）。 */
    public record Active(String type, String label, long until, String sponsor) {
    }

    private static final Map<String, Active> ACTIVE = new LinkedHashMap<>();
    private static volatile double experience = 1;
    private static volatile double quantity;
    private static volatile double rarity;
    private static volatile double gold = 1;

    private Boosts() {
    }

    /** 經驗再乘幾倍（1 = 沒有）。 */
    public static double experience() {
        return experience;
    }

    /** 掉落數量再加多少（小數，1 = +100%）。 */
    public static double quantity() {
        return quantity;
    }

    /** 掉落稀有度再加多少（小數）。 */
    public static double rarity() {
        return rarity;
    }

    /** 金幣再乘幾倍。 */
    public static double gold() {
        return gold;
    }

    /** 進行中的加倍（照開始的順序）。 */
    public static synchronized Map<String, Active> active() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(ACTIVE));
    }

    /** 換掉進行中的加倍與倍數（贊助模組每秒算一次）。 */
    static synchronized void set(final Map<String, Active> active, final double experience, final double quantity, final double rarity,
                                 final double gold) {
        ACTIVE.clear();
        ACTIVE.putAll(active);
        Boosts.experience = experience;
        Boosts.quantity = quantity;
        Boosts.rarity = rarity;
        Boosts.gold = gold;
    }

    /** 全部關掉（模組停用）。 */
    static void clear() {
        set(Map.of(), 1, 0, 0, 1);
    }
}
