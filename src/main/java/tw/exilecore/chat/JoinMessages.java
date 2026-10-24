package tw.exilecore.chat;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerSettings;

/** 2026-10-24：照「過濾加入離開訊息」送上線、下線、死亡訊息（聊天模組與贊助的自訂上線訊息共用）。 */
public final class JoinMessages {

    private JoinMessages() {
    }

    /** 送給在線、沒開這個過濾的人；always 不管設定一定送（自己的訊息），skip 不送（剛下線的人）。 */
    public static void deliver(final ExileCore plugin, final Component message, final String filter, final Player always, final Player skip) {
        plugin.getServer().getConsoleSender().sendMessage(message);
        for (final Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.equals(skip)) {
                continue;
            }
            if (!viewer.equals(always) && PlayerSettings.on(plugin.players().find(viewer.getUniqueId()).orElse(null), filter)) {
                continue;
            }
            viewer.sendMessage(message);
        }
    }

    /** 上線訊息（照「過濾加入離開訊息」）。 */
    public static void join(final ExileCore plugin, final Component message, final Player joined) {
        deliver(plugin, message, PlayerSettings.HIDE_JOIN_LEAVE, joined, null);
    }
}
