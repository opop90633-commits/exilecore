package tw.exilecore.gui;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import tw.exilecore.ExileCore;

/**
 * 2026-10-23：所有「動作列的提示」都從這裡送（以前各處直接 {@code sendActionBar}）。
 * <p>
 * 隊伍框（{@link tw.exilecore.party.PartyHud}）是畫在動作列那一行的字（著色器把它搬到左上角），
 * 動作列一換成別的提示（裝上裝備、通貨、選單訊息…），隊伍框就跟著不見，要等下一次血量那一行（半秒一次）才回來——
 * 提示一直來的時候整個框就一直閃、一直消失。這裡把隊伍框接在提示後面一起送（淨寬 0，不影響提示的位置）。
 */
public final class ActionBar {

    private ActionBar() {
    }

    public static void send(final Audience audience, final ComponentLike message) {
        if (audience == null) {
            return;
        }
        Component frames = null;
        if (audience instanceof Player player && org.bukkit.Bukkit.isPrimaryThread()) { // 非主執行緒（資料庫回呼）不碰玩家狀態
            try {
                final ExileCore plugin = JavaPlugin.getPlugin(ExileCore.class);
                if (plugin.players() != null) {
                    frames = plugin.players().partyFrames(player);
                }
            } catch (final RuntimeException ignored) {
                // 插件還沒載入好（或單元測試）：只送提示
            }
        }
        audience.sendActionBar(frames == null ? message : Component.text().append(message).append(frames).build());
    }
}
