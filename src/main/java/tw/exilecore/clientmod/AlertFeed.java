package tw.exilecore.clientmod;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.Collection;
import java.util.List;

/**
 * 2026-11-02 照站長（C 組）：Boss 預警改走模組的預警封包（{@link FeedProtocol#ALERT}）。
 * 這次之前全部寫成原版的大標題：只有一個槽、後送的蓋掉前面的，每 5 tick 重送一次 400 毫秒的字。
 * <p>
 * 呼叫的地方照這樣寫：{@code if (!AlertFeed.show(plugin, player, alert)) { player.showTitle(原本的標題); }}——
 * 模組收得到就送預警、回傳 true（插件不送大標題）；沒裝模組、舊模組、招呼沒寫 alerts 的回傳 false，照原本的大標題。
 */
public final class AlertFeed {

    private AlertFeed() {
    }

    /** 送一則預警；回傳 true = 模組會畫（呼叫的地方就不要再送大標題）。 */
    public static boolean show(final ExileCore plugin, final Player player, final FeedProtocol.Alert alert) {
        final ClientFeeds feeds = ClientFeeds.of(plugin);
        if (feeds == null || player == null || !feeds.alerts(player)) {
            return false;
        }
        feeds.send(player, FeedProtocol.ALERT, FeedProtocol.alert(alert));
        return true;
    }

    /** 收掉一則（id "*" = 全部）。沒裝模組的什麼都不做。 */
    public static void clear(final ExileCore plugin, final Player player, final String id) {
        final ClientFeeds feeds = ClientFeeds.of(plugin);
        if (feeds != null && player != null && feeds.alerts(player)) {
            feeds.send(player, FeedProtocol.ALERT, FeedProtocol.clearAlert(id));
        }
    }

    /** 換階段、護盾、狂暴那種「大字＋副標題」（MiniMessage 兩行）改成上面那排的一則，留 ticks。 */
    public static boolean banner(final ExileCore plugin, final Player player, final String id, final String[] lines, final int ticks) {
        return show(plugin, player, FeedProtocol.Alert.top(id, 1, lines[0], lines.length > 1 ? lines[1] : "", ticks));
    }

    /**
     * 換階段：側邊的機制清單（這個階段要做什麼＋剩餘時間），同時上面那排跳一則「第 N 階段」。
     *
     * @param countdown 鎖血機制的時間（tick；沒有 0）
     */
    public static boolean phase(final ExileCore plugin, final Player player, final String id, final String[] title, final List<String> notes,
                                final int countdown) {
        if (!show(plugin, player, FeedProtocol.Alert.side(id, title[0], notes, countdown))) {
            return false;
        }
        show(plugin, player, FeedProtocol.Alert.top(id + ":banner", 1, title[0], title.length > 1 ? title[1] : "", 60));
        return true;
    }

    /** 最近的那一個點（安全區的箭頭指最近的）；沒有 null。 */
    public static Location nearest(final Location from, final Collection<Location> spots) {
        Location best = null;
        double distance = Double.MAX_VALUE;
        for (final Location spot : spots) {
            if (spot == null || spot.getWorld() == null || !spot.getWorld().equals(from.getWorld())) {
                continue;
            }
            final double d = spot.distanceSquared(from);
            if (d < distance) {
                distance = d;
                best = spot;
            }
        }
        return best;
    }

    /** 純函式：{x, z} 的清單裡離 (x, z) 最近的；空的回傳 null。 */
    public static double[] nearest(final double x, final double z, final List<double[]> spots) {
        double[] best = null;
        double distance = Double.MAX_VALUE;
        for (final double[] spot : spots) {
            final double d = (spot[0] - x) * (spot[0] - x) + (spot[1] - z) * (spot[1] - z);
            if (d < distance) {
                distance = d;
                best = spot;
            }
        }
        return best;
    }
}
