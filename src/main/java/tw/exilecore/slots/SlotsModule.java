package tw.exilecore.slots;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;

/**
 * slots 模組（2026-11-02 照站長：營地的拉霸機，用金幣下注、回收金幣；這次之前沒有）：data/slots.yml。
 * 要在經濟、NPC、委託（帳號進度、稱號）、贊助（時裝）模組之後啟用。
 */
public final class SlotsModule implements Module, Listener {

    private final ExileCore plugin;
    private final SlotsConfig config = new SlotsConfig();
    private SlotsService service;

    public SlotsModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "slots";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new SlotsService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        service.registerTitle();
        // 營地的機台：等劇情世界擺好營地 NPC（啟動後幾秒）再放
        plugin.getServer().getScheduler().runTaskLater(plugin, service::placeMachines, 200L);
        plugin.getSLF4JLogger().info("拉霸機：{}，押注 {}，期望回報率 {}", config.enabled() ? "開著" : "關著", config.bets(),
                String.format(java.util.Locale.ROOT, "%.3f", config.expectedReturn()));
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.registerTitle();
        }
    }

    @Override
    public void disable() {
        if (service != null) {
            service.settleAll(); // 還在轉的那一把結算（角色模組之後才停用：派的獎跟著存檔）
        }
    }

    private void load() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        final List<String> problems = config.validate(
                id -> plugin.currencies() != null && plugin.currencies().repository().get(id) != null,
                id -> plugin.economy() == null ? 0 : plugin.economy().config().value(id),
                id -> plugin.sponsors() == null || plugin.sponsors().config().cosmetics().containsKey(id));
        for (final String problem : problems) {
            plugin.getSLF4JLogger().warn(problem);
        }
    }

    /** 拉霸機；模組沒啟用時為 null。 */
    public SlotsService service() {
        return service;
    }

    /** 離線：還在轉的那一把先結算（最先處理，角色存檔之前）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.quit(event.getPlayer());
        }
    }

    /** 玩家指令：/slots 打開拉霸機（營地裡）。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return List.of(Commands.literal("slots").executes(context -> {
            final Entity executor = context.getSource().getExecutor();
            if (!(executor instanceof Player player)) {
                context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                return Command.SINGLE_SUCCESS;
            }
            open(plugin, player, false);
            return Command.SINGLE_SUCCESS;
        }).build());
    }

    /** 打開拉霸機（viaNpc = 右鍵機台打開的，不檢查在不在營地）。 */
    public static void open(final ExileCore plugin, final Player player, final boolean viaNpc) {
        final SlotsService slots = plugin.slots();
        if (slots == null) {
            player.sendMessage(plugin.text().prefixed("<red>拉霸機模組沒有啟用（看主控台的錯誤訊息）"));
            return;
        }
        if (!slots.config().enabled()) {
            player.sendMessage(plugin.text().prefixed("<gray>拉霸機暫停中"));
            return;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先建立角色（/char）"));
            return;
        }
        final String problem = slots.placeProblem(player, viaNpc);
        if (problem != null) {
            player.sendMessage(plugin.text().prefixed("<red>" + problem));
            return;
        }
        new SlotsMenu(plugin, player, slots).open();
    }
}
