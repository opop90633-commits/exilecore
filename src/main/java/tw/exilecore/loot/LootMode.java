package tw.exilecore.loot;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.monster.DropTable;

import java.util.List;

/**
 * 2026-11-14 照站長「新增兩種模式」：每個角色的掉落模式（角色進度 {@link #KEY}，隨時可以切換，組隊各自照自己的）。
 * <ul>
 *   <li><b>一般掉落</b>（預設）：照現在的掉落率，掉的東西都能交易。</li>
 *   <li><b>綁定掉落</b>：Boss 掉的傳奇、專屬技能寶石、專屬輔助寶石的機率 ×{@value #BOSS_MORE}；血脈輔助寶石（路上的怪、Boss 都算）×{@value #LINEAGE_MORE}；
 *       這些掉出來綁定帳號（{@link tw.exilecore.item.AccountBound}：不能交易、上架、放公會倉庫、丟在地上；自己帳號的倉庫可以共用）。
 *       其他掉落（通貨、稀有裝備、地圖…）照一般，也能交易。</li>
 * </ul>
 * 加倍的做法：Boss 掉落多擲 {@value #BOSS_MORE} − 1 次，只留傳奇與指定的寶石（每次獨立擲，期望值剛好 ×5，機率高的也不會被 100% 卡住）；
 * 血脈照樣多擲。
 */
public final class LootMode {

    /** 角色進度的鍵：值 {@link #BOUND} 是綁定掉落，其他（沒寫）是一般掉落。 */
    public static final String KEY = "loot.mode";
    public static final String BOUND = "bound";
    public static final String TRADE = "trade";
    /** Boss 的傳奇、專屬技能寶石、專屬輔助寶石 ×5。 */
    public static final int BOSS_MORE = 5;
    /** 血脈輔助寶石 ×4。 */
    public static final int LINEAGE_MORE = 4;

    /** 2026-11-14 照站長「綁定的裝備和寶石拆解、賣給商人的價格除 5」。 */
    public static final int SALVAGE_DIVISOR = 5;

    /** amount ÷ {@link #SALVAGE_DIVISOR}：不整除的照餘數的機率多 1（期望值剛好 1/5，1 個也有 20% 拿到）。 */
    public static int divide(final int amount, final java.util.random.RandomGenerator random) {
        if (amount <= 0) {
            return 0;
        }
        final int whole = amount / SALVAGE_DIVISOR;
        final int rest = amount % SALVAGE_DIVISOR;
        return whole + (rest > 0 && random.nextInt(SALVAGE_DIVISOR) < rest ? 1 : 0);
    }

    private LootMode() {
    }

    /** 這個角色是不是綁定掉落（沒有角色、null 都算一般）。 */
    public static boolean bound(final ExileCore plugin, final Player player) {
        if (player == null || plugin.players() == null) {
            return false;
        }
        final tw.exilecore.character.PlayerState state = plugin.players().get(player);
        return state.hasCharacter() && BOUND.equals(state.progress().get(KEY));
    }

    public static void set(final ExileCore plugin, final Player player, final boolean bound) {
        plugin.characters().setProgress(player, KEY, bound ? BOUND : TRADE);
    }

    /** Boss 掉落裡會被加倍、綁定的：傳奇裝備、指定的技能或輔助寶石（專屬、血脈）。 */
    public static boolean boosted(final DropTable.Drop drop) {
        return drop != null && (drop.isEquipment() && drop.item() != null && drop.item().unique() != null || drop.isSpecificGem());
    }

    /** 從 from 起的掉落裡，會綁定的那幾種換成綁定的。 */
    public static void bindFrom(final List<DropTable.Drop> drops, final int from) {
        for (int i = Math.max(0, from); i < drops.size(); i++) {
            if (boosted(drops.get(i)) && !drops.get(i).bound()) {
                drops.set(i, drops.get(i).asBound());
            }
        }
    }

    /** 給玩家看的模式說明。 */
    public static String describe(final boolean bound) {
        return bound
                ? "綁定掉落：Boss 的傳奇、專屬技能寶石、專屬輔助寶石 ×" + BOSS_MORE + "，血脈輔助寶石 ×" + LINEAGE_MORE
                        + "；這些掉出來綁定帳號（不能交易、上架、放公會倉庫、丟在地上；拆解、賣商人只拿 1/" + SALVAGE_DIVISOR + "）"
                : "一般掉落：照原本的掉落率，掉的東西都能交易";
    }
}
