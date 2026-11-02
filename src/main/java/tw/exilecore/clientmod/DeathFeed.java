package tw.exilecore.clientmod;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.List;

/**
 * 2026-11-02 照站長（C 組）：死亡回顧送給模組畫成死亡畫面旁邊的面板（{@link FeedProtocol#DEATH}）。
 * 這次之前死掉時印在聊天欄（現在沒裝模組的人照舊）；{@code /deaths} 不管有沒有裝模組都照舊印在聊天欄。
 */
public final class DeathFeed {

    private DeathFeed() {
    }

    /** 送死亡回顧；回傳 true = 模組會畫（呼叫的地方就不要再印到聊天欄）。 */
    public static boolean send(final ExileCore plugin, final Player player, final List<String> lines) {
        final ClientFeeds feeds = ClientFeeds.of(plugin);
        if (feeds == null || player == null || lines == null || lines.isEmpty() || !feeds.death(player)) {
            return false;
        }
        feeds.send(player, FeedProtocol.DEATH, FeedProtocol.death(lines));
        return true;
    }
}
