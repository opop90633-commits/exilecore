package tw.exilecore.economy;

import java.util.List;

/**
 * 經濟流水帳的來源（純資料，不依賴 Bukkit；網頁的資料、單元測試也用）：每一筆產出或消耗是從哪裡來、到哪裡去。
 * 正的是產出（東西進到遊戲裡），負的是消耗（東西離開遊戲）。{@link EconomyLedger} 也有一份一樣的常數。
 */
public final class EconomySources {

    // ---- 產出（正的）----
    /** 怪物掉落（金幣：怪物掉的金幣；通貨：掉落）。 */
    public static final String DROP = "drop";
    /** 過濾器把隱藏的東西換成金幣。 */
    public static final String FILTER = "filter";
    /** 拆解（金幣與品質通貨）。 */
    public static final String SALVAGE = "salvage";
    /** NPC 免費給的（鑑定卷軸）。 */
    public static final String NPC = "npc";
    /** 管理員指令（給的是正的、扣的是負的）。 */
    public static final String ADMIN = "admin";
    /** 功勳兌換（天穹碎片）。 */
    public static final String MERITS = "merits";

    // ---- 消耗（負的）----
    /** 用在裝備、地圖上（僥倖石碎掉也算）。 */
    public static final String CRAFT = "craft";
    /** 鑿星石開技能的輔助孔。 */
    public static final String SKILL = "skill";
    /** 地圖裝置用掉的（天穹碎片）。 */
    public static final String MAP_DEVICE = "map_device";
    /** 劫盜用掉的（合約、藍圖）。 */
    public static final String HEIST = "heist";
    /** 垃圾桶刪掉的。 */
    public static final String TRASH = "trash";
    /** 刪掉角色時通貨袋裡的。 */
    public static final String DELETE = "delete";
    /** 向商人買裝備。 */
    public static final String VENDOR = "vendor";
    /** 商人馬上換一批。 */
    public static final String VENDOR_REFRESH = "vendor_refresh";
    /** 賭博。 */
    public static final String GAMBLE = "gamble";
    /** 補給（商人的金幣商店）。 */
    public static final String SHOP = "shop";
    /** 天賦退點。 */
    public static final String RESPEC = "respec";
    /** 面對面交易的手續費。 */
    public static final String FEE_TRADE = "fee_trade";
    /** 市集上架（改價）的手續費。 */
    public static final String FEE_LIST = "fee_list";
    /** 市集購買的手續費。 */
    public static final String FEE_BUY = "fee_buy";
    /** 交易所掛單的手續費。 */
    public static final String FEE_EXCHANGE = "fee_exchange";
    /** 公會：建立公會的費用、捐給公會換公會經驗。 */
    public static final String GUILD = "guild";

    /** 來源給人看的名字。 */
    public static String sourceName(final String source) {
        return switch (source) {
            case DROP -> "怪物掉落";
            case FILTER -> "過濾換金幣";
            case SALVAGE -> "拆解";
            case NPC -> "NPC 給的";
            case ADMIN -> "管理員";
            case MERITS -> "功勳兌換";
            case CRAFT -> "做裝備、地圖";
            case SKILL -> "開技能孔";
            case MAP_DEVICE -> "地圖裝置";
            case HEIST -> "劫盜";
            case TRASH -> "垃圾桶";
            case DELETE -> "刪除角色";
            case VENDOR -> "商人";
            case VENDOR_REFRESH -> "商人換貨";
            case GAMBLE -> "賭博";
            case SHOP -> "補給";
            case RESPEC -> "天賦退點";
            case FEE_TRADE -> "交易手續費";
            case FEE_LIST -> "上架手續費";
            case FEE_BUY -> "購買手續費";
            case FEE_EXCHANGE -> "交易所手續費";
            case GUILD -> "公會（建立、捐獻）";
            default -> source;
        };
    }

    /** 全部的來源（網頁的名稱表照這個順序）。 */
    public static final List<String> ALL = List.of(DROP, FILTER, SALVAGE, NPC, ADMIN, MERITS, CRAFT, SKILL, MAP_DEVICE, HEIST, TRASH, DELETE, VENDOR, VENDOR_REFRESH,
            GAMBLE, SHOP, RESPEC, FEE_TRADE, FEE_LIST, FEE_BUY, FEE_EXCHANGE, GUILD);

    private EconomySources() {
    }
}
