package tw.exilecore.market;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** 商店頁裡的一件東西（在記憶體裡；資料庫是 market_listings）。只在主執行緒用。 */
final class Listing {

    /** 搜尋分類。 */
    enum Category {
        ALL("全部"), WEAPON("武器"), ARMOUR("防具"), JEWELLERY("飾品"), MAP("地圖"), OTHER("其他");

        private final String displayName;

        Category(final String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    final String id;
    final UUID seller;
    final String sellerName;
    final ItemStack item;
    final String itemUuid;
    /** 小寫的純文字（名稱、說明、詞綴、稀有度、基底），搜尋用。 */
    final String searchText;
    final Category category;
    /** 稀有度（不是本插件的裝備為 null）。 */
    final tw.exilecore.item.Rarity rarity;
    String priceCurrency;
    int priceAmount;
    long listedAt;
    /** 建立它的操作；寫進資料庫之前別人不能買（萬一寫入失敗，不會有人買到不存在的東西）。 */
    final String opId;
    boolean confirmed;
    /** 同類比價用的代號（第一次用到才算）。 */
    String similarKey;
    /** 裝備需求（第一次用到才算；不是裝備是 NONE）。 */
    tw.exilecore.item.ItemBase.Requirements requirements;
    static final tw.exilecore.item.ItemBase.Requirements NONE = new tw.exilecore.item.ItemBase.Requirements(0, 0, 0, 0);

    Listing(final String id, final UUID seller, final String sellerName, final ItemStack item, final String itemUuid,
            final String searchText, final Category category, final tw.exilecore.item.Rarity rarity, final String priceCurrency,
            final int priceAmount, final long listedAt, final String opId, final boolean confirmed) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.item = item;
        this.itemUuid = itemUuid;
        this.searchText = searchText;
        this.category = category;
        this.rarity = rarity;
        this.priceCurrency = priceCurrency;
        this.priceAmount = priceAmount;
        this.listedAt = listedAt;
        this.opId = opId;
        this.confirmed = confirmed;
    }
}
