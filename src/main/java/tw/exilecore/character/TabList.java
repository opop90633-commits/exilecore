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

    /** 每 2 秒算一次的上下兩段（MiniMessage）；增益那段每半秒接在下面（2026-10-20）。 */
    private String header = "";
    private final java.util.Map<java.util.UUID, String> footers = new java.util.HashMap<>();
    /** 上次送出去的（沒變就不重送）。 */
    private final java.util.Map<java.util.UUID, String> sent = new java.util.HashMap<>();
    /** 增益一行寫幾個。 */
    private static final int BUFFS_PER_LINE = 3;

    void tick() {
        final String title = plugin.getConfig().getString("tab.title", DEFAULT_TITLE);
        final int online = plugin.getServer().getOnlinePlayers().size();
        final int max = plugin.getServer().getMaxPlayers();
        final double tps = Math.min(20.0, plugin.getServer().getTPS()[0]);
        final String tpsColor = tps >= 18 ? "#55ff55" : tps >= 15 ? "#ffd24a" : "#ff5555";
        header = title + "\n";
        footers.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        sent.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final int ping = player.getPing();
            final String pingColor = ping <= 80 ? "#55ff55" : ping <= 180 ? "#ffd24a" : "#ff5555";
            footers.put(player.getUniqueId(), "\n<#55ff55>在線玩家：<white>" + online + "/" + max
                    + "<dark_gray>　|　<#ff77cc>PING：<" + pingColor + ">" + ping + "ms"
                    + "<dark_gray>　|　<#55ffff>TPS：<" + tpsColor + ">" + String.format(Locale.ROOT, "%.1f", tps));
        }
        refresh();
    }

    /** 每半秒：把身上的增益（全部，含技能格上的）與剩幾秒接在 TAB 下面；內容沒變不重送。 */
    void refresh() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final String base = footers.get(player.getUniqueId());
            if (base == null) {
                continue;
            }
            final String footer = base + buffs(player);
            final String key = header + "\u0000" + footer;
            if (key.equals(sent.get(player.getUniqueId()))) {
                continue;
            }
            sent.put(player.getUniqueId(), key);
            player.sendPlayerListHeaderAndFooter(plugin.text().parse(header), plugin.text().parse(footer));
        }
    }

    /** 增益那段：「增益」標題＋每行 {@value #BUFFS_PER_LINE} 個「名稱 12s」，快結束的在前面；沒有增益是空字串。 */
    private String buffs(final Player player) {
        if (plugin.skills() == null) {
            return "";
        }
        final tw.exilecore.skill.SkillService.HudBuffs hud = plugin.skills().hudBuffs(player, false);
        final java.util.List<tw.exilecore.skill.SkillService.BuffTimer> all = new java.util.ArrayList<>(hud.others());
        for (final tw.exilecore.skill.SkillService.SlotTimer slot : hud.slots()) {
            if (!slot.missing()) {
                all.add(new tw.exilecore.skill.SkillService.BuffTimer(slot.name(), slot.ticks()));
            }
        }
        if (all.isEmpty()) {
            return "";
        }
        all.sort(java.util.Comparator.comparingLong(tw.exilecore.skill.SkillService.BuffTimer::ticks));
        final StringBuilder text = new StringBuilder("\n\n<#f5b942><b>增益</b>");
        for (int i = 0; i < all.size(); i++) {
            final tw.exilecore.skill.SkillService.BuffTimer buff = all.get(i);
            text.append(i % BUFFS_PER_LINE == 0 ? "\n" : "<dark_gray>　|　");
            text.append("<#f5b942>").append(tw.exilecore.item.ItemRenderer.escape(buff.name())).append(" ")
                    .append(buff.ticks() <= 100 ? "<#ff9a3c>" : "<white>").append(HotbarSides.timeText(buff.ticks()));
        }
        return text.toString();
    }
}
