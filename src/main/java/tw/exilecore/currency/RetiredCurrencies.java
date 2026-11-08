package tw.exilecore.currency;

import java.util.Map;

/**
 * 2026-11-08 照站長「通貨裡的萬聖節素材全刪除」「這些舊的也幫我刪除」：從遊戲裡整個拿掉的通貨（currencies.yml 已經沒有）。
 * 玩家手上的一律刪掉、不補償：
 * <ul>
 *   <li>通貨袋、倉庫通貨頁：讀進來就略過（下次存檔就不見了）。</li>
 *   <li>背包、隨身背包、倉庫、公會倉庫裡的實體物品：補正時（{@link CurrencyService#refresh}）數量歸零；/ec refreshitems 補離線的。</li>
 *   <li>市集：賣這些的上架刪掉；用這些標價的改用基準通貨照舊參考價值換算；交易所掛單取消（付出去的是別的通貨就退成收入）；這些的收入刪掉。</li>
 * </ul>
 * 純邏輯，不依賴 Bukkit。
 */
public final class RetiredCurrencies {

    private RetiredCurrencies() {
    }

    /** 代號 → 拿掉前的參考價值（金幣，economy.yml 的 values；市集用它們標價的換成基準通貨）。 */
    public static final Map<String, Long> VALUES = Map.of(
            "event_candy", 30L,          // 萬燈糖（2026-10 萬聖節「萬燈夜」）
            "event_lamp_wick", 2500L,    // 魂燈芯（萬燈夜）
            "catalyst_resist", 108L,     // 抗性催化劑（舊）
            "catalyst_crit", 120L,       // 暴擊催化劑（舊）
            "catalyst_elemental", 108L,  // 元素催化劑（舊）
            "catalyst_phys_chaos", 96L); // 物理與混沌催化劑（舊）

    public static boolean retired(final String id) {
        return id != null && VALUES.containsKey(id);
    }
}
