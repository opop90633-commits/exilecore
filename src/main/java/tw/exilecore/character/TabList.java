package tw.exilecore.character;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;

import java.util.Locale;

/**
 * TAB 列表的上下兩段（2026-10）：上面是伺服器名稱，下面是在線人數、自己的延遲（PING）、伺服器的 TPS。
 * 名稱寫在 config.yml 的 tab.title（MiniMessage）；每 2 秒更新一次。
 */
final class TabList {

    static final String DEFAULT_TITLE = "<#7fd4ff>《 <#f5b942><b>星骸</b> <#7fd4ff>》";

    private final ExileCore plugin;

    TabList(final ExileCore plugin) {
        this.plugin = plugin;
    }

    void tick() {
        final String title = plugin.getConfig().getString("tab.title", DEFAULT_TITLE);
        final int online = plugin.getServer().getOnlinePlayers().size();
        final int max = plugin.getServer().getMaxPlayers();
        final double tps = Math.min(20.0, plugin.getServer().getTPS()[0]);
        final String tpsColor = tps >= 18 ? "#55ff55" : tps >= 15 ? "#ffd24a" : "#ff5555";
        final net.kyori.adventure.text.Component header = plugin.text().parse(title + "\n");
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final int ping = player.getPing();
            final String pingColor = ping <= 80 ? "#55ff55" : ping <= 180 ? "#ffd24a" : "#ff5555";
            final String footer = "\n<#55ff55>在線玩家：<white>" + online + "/" + max
                    + "<dark_gray>　|　<#ff77cc>PING：<" + pingColor + ">" + ping + "ms"
                    + "<dark_gray>　|　<#55ffff>TPS：<" + tpsColor + ">" + String.format(Locale.ROOT, "%.1f", tps);
            player.sendPlayerListHeaderAndFooter(header, plugin.text().parse(footer));
        }
    }
}
