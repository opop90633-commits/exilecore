package tw.exilecore.event;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * event 模組：每月活動（data/events.yml）：主題輪替、每月挑戰、限定稱號。要在委託模組之後啟用（進度存在帳號進度裡、稱號由委託模組發）。
 */
public final class EventModule implements Module, Listener {

    /** 多久檢查一次換月（tick）。 */
    private static final long MONTH_TICKS = 60 * 20;

    private final ExileCore plugin;
    private final EventConfig config = new EventConfig();
    private MonthlyEventService service;
    private BukkitTask task;

    public EventModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "event";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.bounties() == null) {
            throw new IllegalStateException("委託模組沒有啟用（看上面的錯誤訊息），每月活動的進度沒地方存");
        }
        load();
        service = new MonthlyEventService(plugin, config);
        service.registerTitles();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.repeat("event.month", service::tick, MONTH_TICKS, MONTH_TICKS);
        if (plugin.quests() != null) {
            // 任務追蹤（畫面右邊的側欄）接上這個月的挑戰進度：打怪時看得到數字在動
            plugin.quests().addTrackerSection(player -> plugin.events() == null ? List.of() : plugin.events().trackerLines(player));
        }
        final EventConfig.Theme theme = service.theme();
        plugin.getSLF4JLogger().info("每月活動：{}（{} 個主題，{}）", theme == null ? "關" : theme.name(), config.themes().size(),
                config.force().isEmpty() ? "照月份輪替" : "settings.force 指定");
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.registerTitles();
            service.refreshTrackers(); // 主題或挑戰改了：側欄跟著換
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void load() throws Exception {
        final EventConfig fresh = new EventConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        final List<String> mods = new ArrayList<>();
        if (plugin.monsters() != null) {
            for (final tw.exilecore.monster.MonsterConfig.MonsterMod mod : plugin.monsters().config().mods()) {
                mods.add(mod.id());
            }
        } else {
            for (final EventConfig.Theme theme : fresh.themes()) {
                if (theme.rareMod() != null) {
                    mods.add(theme.rareMod());
                }
            }
        }
        final List<String> problems = fresh.validate(mods, List.of("shrine", "strongbox", "breach", "exile", "abyss")); // 2026-10-18：＋深淵裂縫（保險箱不照權重，不能加權）
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("events.yml 有 " + problems.size() + " 個問題：\n  " + String.join("\n  ", problems));
        }
        config.load(plugin.dataFolder().resolve("data"));
    }

    /** 執行期；模組沒啟用時為 null。 */
    public MonthlyEventService service() {
        return service;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.forget(event.getPlayer());
        }
    }

    /** 玩家指令：/event 每月活動。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return List.of(Commands.literal("event").executes(context -> {
            final Entity executor = context.getSource().getExecutor();
            if (!(executor instanceof Player player)) {
                context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                return Command.SINGLE_SUCCESS;
            }
            if (plugin.events() == null) {
                player.sendMessage(plugin.text().prefixed("<red>每月活動模組沒有啟用（看主控台的錯誤訊息）"));
                return Command.SINGLE_SUCCESS;
            }
            new EventMenu(plugin, player, plugin.events()).open();
            return Command.SINGLE_SUCCESS;
        }).build());
    }
}
