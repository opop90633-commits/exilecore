package tw.exilecore.item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-10-27 效能：裝備比較（{@link ItemComparer}）要不要真的重算。屬性表每次重算（增益、充能一變就會）以前都排一次、
 * 最多每 2 秒整個背包重新解析一次；現在每 2 秒那次先看背包的指紋（每格的物品資料、比較標記、手上拿第幾格）跟上次比完時一樣
 * 就不做。身上的裝備換了、背包被點、撿到東西照樣馬上重比（{@link #force}）。純邏輯，不依賴 Bukkit。
 */
final class CompareGate {

    /** 玩家 → 上次比完時背包的指紋。 */
    private final Map<UUID, Integer> applied = new HashMap<>();

    /** 背包的指紋跟上次比完時不一樣（或還沒比過）：要重比。 */
    boolean changed(final UUID player, final int inventorySignature) {
        final Integer last = applied.get(player);
        return last == null || last != inventorySignature;
    }

    /** 比完了：記下比完之後的背包指紋。 */
    void applied(final UUID player, final int inventorySignature) {
        applied.put(player, inventorySignature);
    }

    /** 下次一定重比（身上的裝備換了）。 */
    void force(final UUID player) {
        applied.remove(player);
    }

    void forget(final UUID player) {
        applied.remove(player);
    }
}
