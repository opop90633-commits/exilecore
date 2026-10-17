package tw.exilecore.market;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * market 模組：市集（商店頁、搜尋、購買、收入）與通貨交易所。
 * 玩家指令 {@code /market}（營地或市集掌櫃旁邊）。
 */
public final class MarketModule implements Module {

    private final ExileCore plugin;
    private MarketService service;
    private org.bukkit.scheduler.BukkitTask autoCollect;

    public MarketModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "market";
    }

    @Override
    public void enable() throws Exception {
        final MarketService created = new MarketService(plugin);
        created.load();
        plugin.characters().onRecovered(created::confirm);
        service = created;
        // 2026-10：成交自動入袋（像 PoE2）：每 10 秒把線上玩家確定了的收入放進通貨（離線的上線後下一輪就放）
        autoCollect = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            final MarketService current = service;
            if (current == null) {
                return;
            }
            for (final Player online : plugin.getServer().getOnlinePlayers()) {
                try {
                    current.autoCollect(online);
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().warn("收入自動入袋失敗（{}）：{}", online.getName(), e.toString());
                }
            }
            current.forgetOffline(); // 2026-10-18：下線的人的提示紀錄、常用通貨清掉
        }, 200L, 200L);
    }

    @Override
    public void reload() {
        // 物品基底（裝備需求）可能改了：比價與「我能用的」用的快取重算
        if (service != null) {
            service.clearCaches();
        }
    }

    @Override
    public void disable() {
        // 市集的東西都在資料庫裡（每個操作當下就寫了），這裡不用存
        if (autoCollect != null) {
            autoCollect.cancel();
            autoCollect = null;
        }
    }

    public MarketService service() {
        return service;
    }

    /** 打開市集（檢查位置）。 */
    public static void open(final ExileCore plugin, final Player player) {
        final MarketService service = plugin.market();
        if (service == null) {
            player.sendMessage(plugin.text().prefixed("<red>市集模組沒有啟用（看主控台的錯誤訊息）"));
            return;
        }
        if (!plugin.players().get(player).hasCharacter()) {
            player.sendMessage(plugin.text().prefixed("<red>先選一個角色（/char）"));
            return;
        }
        if (!service.canUseHere(player)) {
            player.sendMessage(plugin.text().prefixed("<red>市集只能在營地打開"));
            return;
        }
        new MarketMenu(plugin, player, service).open();
    }

    /** /market、/market shop、/market exchange、/market collect。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return List.of(Commands.literal("market")
                .executes(context -> run(plugin, context, (service, player) -> open(plugin, player)))
                .then(Commands.literal("shop").executes(context -> run(plugin, context, (service, player) -> {
                    if (service.canUseHere(player)) {
                        new ShopMenu(plugin, player, service, new MarketMenu.State()).open();
                    } else {
                        player.sendMessage(plugin.text().prefixed("<red>市集只能在營地打開"));
                    }
                })))
                .then(Commands.literal("exchange").executes(context -> run(plugin, context, (service, player) -> {
                    if (service.canUseHere(player)) {
                        new ExchangeMenu(plugin, player, service, new MarketMenu.State()).open();
                    } else {
                        player.sendMessage(plugin.text().prefixed("<red>通貨交易所只能在營地打開"));
                    }
                })))
                .then(Commands.literal("collect").executes(context -> run(plugin, context, (service, player) -> {
                    final String problem = service.collect(player);
                    if (problem != null) {
                        player.sendMessage(plugin.text().prefixed("<red>" + problem));
                    }
                })))
                .build());
    }

    private static int run(final ExileCore plugin, final CommandContext<CommandSourceStack> context,
                           final BiConsumer<MarketService, Player> action) {
        if (!(context.getSource().getExecutor() instanceof Player player)) {
            context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.market() == null) {
            player.sendMessage(plugin.text().prefixed("<red>市集模組沒有啟用（看主控台的錯誤訊息）"));
            return Command.SINGLE_SUCCESS;
        }
        action.accept(plugin.market(), player);
        return Command.SINGLE_SUCCESS;
    }
}
