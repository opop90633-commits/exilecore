package tw.exilecore.item;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 2026-10-28 綁定帳號的物品（第一個用的是職業轉職卷）：物品上記 {@code exilecore:account_bound}。
 * 照 PoE2 的帳號綁定：自己的角色之間可以用（背包、隨身背包、帳號倉庫都能放），但不能換到別人手上——
 * 面對面交易、市集上架、公會倉庫、丟在地上、放進原版的箱子或物品展示框都擋掉；垃圾桶也不收（免得手滑丟掉）。
 * 這次之前沒有通用的記號，只有星索鉤自己一套（LifeService.isHook）。
 * <p>
 * 擋的地方：TradeService（交易）、MarketService（上架）、GuildService（公會倉庫）、TrashMenu（垃圾桶）、
 * ClassScrollListener（丟、原版容器、物品展示框）。拆解、賣給商人本來就只收裝備、寶石、藥劑、通貨，這種物品不會被收。
 */
public final class AccountBound {

    /** 物品上的記號（值 1）。用固定的命名空間：沒有插件物件的地方（測試、靜態方法）也能讀。 */
    public static final NamespacedKey KEY = new NamespacedKey("exilecore", "account_bound");

    /** 會讓東西換到別人手上（或消失）的地方。 */
    public enum Channel {
        TRADE, MARKET, GUILD_STASH, DROP, CONTAINER, TRASH
    }

    private AccountBound() {
    }

    /** 這件是綁定帳號的嗎。 */
    public static boolean isBound(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return false;
        }
        final Byte value = stack.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.BYTE);
        return value != null && value != 0;
    }

    /** 標成綁定帳號（做物品的時候呼叫）。 */
    public static void mark(final ItemMeta meta) {
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
    }

    /**
     * 綁定帳號的東西在這個地方為什麼不行（純邏輯，給玩家看的一句話）；不是綁定的回傳 null。
     *
     * @param bound   這件是不是綁定帳號的
     * @param channel 要放去哪裡
     */
    public static String problem(final boolean bound, final Channel channel) {
        if (!bound) {
            return null;
        }
        return switch (channel) {
            case TRADE -> "綁定帳號的物品不能交易";
            case MARKET -> "綁定帳號的物品不能上架";
            case GUILD_STASH -> "綁定帳號的物品不能放進公會倉庫";
            case DROP -> "綁定帳號的物品不能丟在地上";
            case CONTAINER -> "綁定帳號的物品只能放在自己的背包、隨身背包或倉庫";
            case TRASH -> "綁定帳號的物品不能丟進垃圾桶";
        };
    }

    /** 方便用：直接看物品。 */
    public static String problem(final ItemStack stack, final Channel channel) {
        return problem(isBound(stack), channel);
    }
}
