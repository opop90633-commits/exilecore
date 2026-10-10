package tw.exilecore.trade;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次面對面交易：兩邊各放東西、各自按確認。東西在成交前都還在自己身上（這裡只記「放了背包第幾格的哪一件」），
 * 成交時再核對一次、一次搬完，所以中途當機或斷線什麼都不會少。
 */
final class TradeSession {

    /** 放上交易視窗的一樣東西。 */
    sealed interface Offer permits ItemOffer, PouchOffer {
    }

    /**
     * 背包裡的一格。
     *
     * @param slot     背包格（0–35）
     * @param snapshot 放上去那一刻的樣子（成交時核對：同一件、數量一樣）
     */
    record ItemOffer(int slot, ItemStack snapshot) implements Offer {
    }

    /** 隨身背包通貨袋裡的通貨。 */
    record PouchOffer(String currencyId, int amount) implements Offer {
    }

    /** 一邊。 */
    static final class Side {
        final Player player;
        final List<Offer> offers = new ArrayList<>();
        boolean confirmed;
        /** 這一邊最後一次改內容的 tick（對方在那之後一小段時間內不能按確認）。 */
        long lastChange = -1_000_000L;
        TradeMenu menu;
        /** 左邊顯示通貨袋（選要放哪種通貨）而不是自己放的東西。 */
        boolean pouchView;
        /** 按確認那一刻看到的手續費（成交時不一樣就不成交：例如中途 /ec reload 改了費率）。 */
        long confirmedFee;
        /** 視窗上次畫的時候的金幣（金幣變了要重畫，「金幣不夠」才不會一直掛著）。 */
        long shownGold = -1;

        Side(final Player player) {
            this.player = player;
        }

        int pouchOffered(final String currencyId) {
            int total = 0;
            for (final Offer offer : offers) {
                if (offer instanceof PouchOffer pouch && pouch.currencyId().equals(currencyId)) {
                    total += pouch.amount();
                }
            }
            return total;
        }

        boolean offersSlot(final int slot) {
            for (final Offer offer : offers) {
                if (offer instanceof ItemOffer item && item.slot() == slot) {
                    return true;
                }
            }
            return false;
        }
    }

    final String id = UUID.randomUUID().toString();
    final Side a;
    final Side b;
    boolean finished;

    TradeSession(final Player a, final Player b) {
        this.a = new Side(a);
        this.b = new Side(b);
    }

    Side side(final Player player) {
        return a.player.getUniqueId().equals(player.getUniqueId()) ? a : b;
    }

    Side other(final Side side) {
        return side == a ? b : a;
    }

    boolean involves(final UUID uuid) {
        return a.player.getUniqueId().equals(uuid) || b.player.getUniqueId().equals(uuid);
    }
}
