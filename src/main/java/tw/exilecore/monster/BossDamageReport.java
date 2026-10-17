package tw.exilecore.monster;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Boss 倒下時的傷害排行（2026-10）：打過這隻 Boss 的人（傷害紀錄裡有、在線、同一個世界）每人在聊天欄收到一份，
 * 前 10 名照傷害排（2026-10-17：5 → 10；前 5 名不滿的寫「無」，第 6～10 名有人才列），最後一行是自己的傷害與佔比。
 * 區域 Boss、地圖 Boss（含終局、極境、金庫之主）都算（{@link MonsterData#boss()}）；召喚出來的小怪不算。
 */
public final class BossDamageReport {

    /** 列幾名。 */
    static final int TOP = 10;
    /** 沒人也要寫「無」的名次（前幾名）。 */
    static final int ALWAYS = 5;
    /** 第 1～5 名的顏色（金、白、橙、紫、藍），第 6～10 名灰綠。 */
    private static final String[] RANK_COLORS = {"#ffd84a", "#f0f0f0", "#ff9a3c", "#c48bff", "#6fd3ff",
            "#9fc9b0", "#9fc9b0", "#9fc9b0", "#9fc9b0", "#9fc9b0"};
    private static final String LINE = "<#c0392b><st>                                                            </st>";

    private BossDamageReport() {
    }

    /** 一名：玩家、傷害。 */
    record Entry(UUID player, double damage) {
    }

    /** 照傷害由高到低（一樣的照原本的順序：先打的在前）。 */
    static List<Entry> ranking(final Map<UUID, Double> damage) {
        final List<Entry> list = new ArrayList<>();
        for (final Map.Entry<UUID, Double> entry : damage.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                list.add(new Entry(entry.getKey(), entry.getValue()));
            }
        }
        list.sort((a, b) -> Double.compare(b.damage(), a.damage()));
        return list;
    }

    /** 傷害數字：一萬以下寫整數，以上寫「1.76 萬」「3.2 億」。 */
    static String amount(final double value) {
        if (value >= 100_000_000) {
            return trim(String.format(Locale.ROOT, "%.2f", value / 100_000_000)) + " 億";
        }
        if (value >= 10_000) {
            return trim(String.format(Locale.ROOT, "%.2f", value / 10_000)) + " 萬";
        }
        return String.format(Locale.ROOT, "%,d", Math.round(value));
    }

    private static String trim(final String number) {
        return number.contains(".") ? number.replaceAll("0+$", "").replaceAll("\\.$", "") : number;
    }

    static String percent(final double part, final double total) {
        if (total <= 0) {
            return "0%";
        }
        final double p = part / total * 100;
        return p >= 99.95 ? "100%" : String.format(Locale.ROOT, p >= 10 ? "%.0f%%" : "%.1f%%", p);
    }

    /** Boss 倒下：送排行給打過的人。 */
    public static void send(final ExileCore plugin, final LivingEntity boss, final MonsterData data, final Map<UUID, Double> damage) {
        final List<Entry> ranking = ranking(damage);
        if (ranking.isEmpty()) {
            return;
        }
        double total = 0;
        for (final Entry entry : ranking) {
            total += entry.damage();
        }
        final List<String> lines = new ArrayList<>();
        lines.add(LINE);
        lines.add("<#ff5555><b>BOSS</b> <#f5b942>" + ItemRenderer.escape(data.name()) + " <gray>已被擊倒");
        lines.add("");
        for (int i = 0; i < TOP; i++) {
            final String color = RANK_COLORS[i];
            if (i < ranking.size()) {
                final Entry entry = ranking.get(i);
                lines.add(" <" + color + ">" + (i + 1) + ". " + ItemRenderer.escape(name(plugin, entry.player())) + "<gray>： <" + color + ">"
                        + amount(entry.damage()) + " <dark_gray>（" + percent(entry.damage(), total) + "）");
            } else if (i < ALWAYS) {
                lines.add(" <" + color + ">" + (i + 1) + ". <dark_gray>無");
            }
        }
        lines.add("");
        final String header = String.join("\n", lines);
        for (int i = 0; i < ranking.size(); i++) {
            final Entry entry = ranking.get(i);
            final Player player = plugin.getServer().getPlayer(entry.player());
            if (player == null || !player.isOnline() || !player.getWorld().equals(boss.getWorld())) {
                continue;
            }
            final String own = " <#6fe3c6>❖ 你造成的傷害 <gray>- <#6fe3c6>" + amount(entry.damage()) + " <dark_gray>（第 " + (i + 1) + " 名，"
                    + percent(entry.damage(), total) + "）";
            player.sendMessage(plugin.text().parse(header + "\n" + own + "\n" + LINE));
        }
    }

    /** 玩家的名字：在線的用角色名稱，不在線的用玩家 ID。 */
    private static String name(final ExileCore plugin, final UUID id) {
        final Player online = plugin.getServer().getPlayer(id);
        if (online != null) {
            return plugin.displayName(online);
        }
        final OfflinePlayer offline = plugin.getServer().getOfflinePlayer(id);
        return offline.getName() == null ? "？" : offline.getName();
    }
}
