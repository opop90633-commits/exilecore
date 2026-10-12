package tw.exilecore.gui;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import tw.exilecore.ExileCore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 畫面右邊的側欄（計分板的側欄，每個玩家自己一張）：打怪時最上面是戰鬥數據（戰鬥模組給內容，停手就收起來），
 * 再來是任務追蹤（任務模組給內容），下面接「最近撿到」（掉落模組給內容，{@link #LOOT_TICKS} 後收起來）。
 * 排版在 {@link SidebarLayout}；內容沒變就不重畫，只改有變的那幾行。
 */
public final class Sidebar implements Listener {

    /** 最近撿到的東西在側欄停多久（tick）。 */
    public static final long LOOT_TICKS = 160;
    /** 多久檢查一次到期（tick）。 */
    public static final long TICK_PERIOD = 10;

    private static final String OBJECTIVE = "exilecore_sidebar";
    /** 每一行的代號（看不到；顯示的是 customName）。 */
    private static final String[] ENTRIES = {"§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a", "§b", "§c", "§d", "§e"};

    private final ExileCore plugin;
    private final Map<UUID, View> views = new HashMap<>();

    /** 一個玩家的側欄。 */
    private static final class View {
        private Scoreboard board;
        private List<String> combat = List.of();
        private List<String> status = List.of();
        private List<String> guide = List.of();
        private List<String> quest = List.of();
        private final SidebarLayout.Feed loot = new SidebarLayout.Feed();
        /** 畫在計分板上的內容（第一行是標題）；收起來時是空的。 */
        private List<String> shown = List.of();
    }

    public Sidebar(final ExileCore plugin) {
        this.plugin = plugin;
    }

    /** 任務追蹤那一段（第一行是標題）；空的 = 不顯示任務（關掉了、沒有進行中的任務）。 */
    public void quests(final Player player, final List<String> lines) {
        if (!player.isOnline()) {
            return; // 下線後才排到的工作：不要留下側欄的資料
        }
        final View view = views.computeIfAbsent(player.getUniqueId(), key -> new View());
        view.quest = List.copyOf(lines);
        render(player, view);
    }

    /** 戰鬥數據那一段（第一行是標題）；空的 = 不顯示（沒在戰鬥、關掉了）。 */
    public void combat(final Player player, final List<String> lines) {
        if (!player.isOnline()) {
            return;
        }
        final View view = views.computeIfAbsent(player.getUniqueId(), key -> new View());
        if (view.combat.equals(lines)) {
            return;
        }
        view.combat = List.copyOf(lines);
        render(player, view);
    }

    /** 狀態那一段（開著的光環、召喚物數量；不含標題）；空的 = 不顯示。 */
    public void status(final Player player, final List<String> lines) {
        if (!player.isOnline()) {
            return;
        }
        final View view = views.get(player.getUniqueId());
        if (view == null && lines.isEmpty()) {
            return;
        }
        final View target = view == null ? views.computeIfAbsent(player.getUniqueId(), key -> new View()) : view;
        if (target.status.equals(lines)) {
            return;
        }
        target.status = List.copyOf(lines);
        render(player, target);
    }

    /** 新手引導那一段（第一行是標題）；空的 = 不顯示（做完了、關掉了）。 */
    public void guide(final Player player, final List<String> lines) {
        if (!player.isOnline()) {
            return;
        }
        final View view = views.computeIfAbsent(player.getUniqueId(), key -> new View());
        if (view.guide.equals(lines)) {
            return;
        }
        view.guide = List.copyOf(lines);
        render(player, view);
    }

    /** 撿到值得注意的東西：加進「最近撿到」（同名的合併數量）。 */
    public void loot(final Player player, final List<SidebarLayout.Item> items) {
        if (items.isEmpty() || !player.isOnline()) {
            return;
        }
        final View view = views.computeIfAbsent(player.getUniqueId(), key -> new View());
        final long expires = plugin.getServer().getCurrentTick() + LOOT_TICKS;
        for (final SidebarLayout.Item item : items) {
            view.loot.add(item, expires);
        }
        render(player, view);
    }

    /** 一張卡片：名稱的寬度照原版字型算。 */
    private static String card(final SidebarLayout.Item item, final String name, final boolean faded) {
        final String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(name));
        return tw.exilecore.web.LootCards.card(item.color() == null ? "#c8c8c8" : item.color(), item.icon(), name,
                (int) Math.ceil(tw.exilecore.web.HudFont.estimateVanilla(plain)), faded);
    }

    /** 每 {@link #TICK_PERIOD} tick：到期的「最近撿到」拿掉、快到期的變暗（render 沒變就不重送）。 */
    public void tick() {
        final long now = plugin.getServer().getCurrentTick();
        for (final Map.Entry<UUID, View> entry : views.entrySet()) {
            final View view = entry.getValue();
            if (view.loot.isEmpty()) {
                continue;
            }
            view.loot.expire(now);
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                render(player, view);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
    }

    /** 插件停用：所有人換回主計分板。 */
    public void clearAll() {
        for (final Map.Entry<UUID, View> entry : views.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            final View view = entry.getValue();
            if (player != null && player.isOnline() && view.board != null && player.getScoreboard() == view.board) {
                player.setScoreboard(plugin.getServer().getScoreboardManager().getMainScoreboard());
            }
        }
        views.clear();
    }

    private void render(final Player player, final View view) {
        // 2026-10：「最近撿到」畫成卡片（套了資源包）；舊的在上、新的在下，快消失時變暗
        final boolean cards = plugin.web() != null && plugin.web().hasPack(player);
        final List<String> lootRows = view.loot.rows(plugin.getServer().getCurrentTick(), cards ? Sidebar::card : null);
        final List<String> lines = SidebarLayout.withStatus(view.status,
                SidebarLayout.compose(view.combat, view.guide, view.quest, lootRows));
        if (lines.isEmpty()) {
            if (view.board != null && player.getScoreboard() == view.board) {
                player.setScoreboard(plugin.getServer().getScoreboardManager().getMainScoreboard());
            }
            view.shown = List.of();
            return;
        }
        if (lines.equals(view.shown) && player.getScoreboard() == view.board) {
            return;
        }
        if (view.board == null) {
            view.board = plugin.getServer().getScoreboardManager().getNewScoreboard();
            tw.exilecore.skill.MinionCollision.prepare(view.board); // 2026-10：召喚物不推擠玩家（每張計分板都要有那個隊伍）
        }
        // 換回這張計分板之前別的東西可能動過它：當作整張重畫
        final List<String> previous = player.getScoreboard() == view.board ? view.shown : List.of();
        Objective objective = view.board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = view.board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, plugin.text().parse(lines.getFirst()));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            objective.numberFormat(NumberFormat.blank());
        } else if (previous.isEmpty() || !previous.getFirst().equals(lines.getFirst())) {
            objective.displayName(plugin.text().parse(lines.getFirst()));
        }
        final int rows = Math.min(ENTRIES.length, lines.size() - 1);
        final boolean sameShape = previous.size() == lines.size();
        for (int i = 0; i < rows; i++) {
            if (sameShape && previous.get(i + 1).equals(lines.get(i + 1))) {
                continue; // 這一行沒變（行數一樣，分數也一樣）
            }
            final Score score = objective.getScore(ENTRIES[i]);
            score.setScore(rows - i);
            score.customName(plugin.text().parse(lines.get(i + 1)));
        }
        final int before = previous.isEmpty() ? ENTRIES.length : previous.size() - 1;
        for (int i = rows; i < before; i++) {
            view.board.resetScores(ENTRIES[i]);
        }
        if (player.getScoreboard() != view.board) {
            player.setScoreboard(view.board);
        }
        view.shown = List.copyOf(lines);
    }
}
