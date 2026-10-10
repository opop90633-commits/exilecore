package tw.exilecore.world;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.worldgen.CampaignLayout;
import tw.exilecore.worldgen.CampaignStructures;

import java.nio.file.Path;

/**
 * 管理指令：
 * <pre>
 * /ec world          劇情世界與地圖世界的狀態
 * /ec world tp       傳送到劇情世界的營地（管理員看建築用；一般玩家用 /town）
 * /ec world device   傳送到地圖裝置前面
 * /ec world setup    重新把區域版面與營地 NPC 擺到劇情世界（舊版面會先備份一次）
 * </pre>
 */
public final class WorldCommand {

    private WorldCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node(final ExileCore plugin) {
        return Commands.literal("world")
                .executes(context -> status(plugin, context.getSource().getSender()))
                .then(Commands.literal("tp").executes(context -> teleport(plugin, context.getSource().getSender(), false)))
                .then(Commands.literal("device").executes(context -> teleport(plugin, context.getSource().getSender(), true)))
                .then(Commands.literal("setup").executes(context -> {
                    final CampaignWorld worlds = plugin.world().worlds();
                    if (worlds == null) {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>世界模組沒有啟用"));
                    } else {
                        worlds.setup(context.getSource().getSender());
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static int status(final ExileCore plugin, final CommandSender sender) {
        final CampaignWorld worlds = plugin.world().worlds();
        if (worlds == null) {
            sender.sendMessage(plugin.text().prefixed("<red>世界模組沒有啟用"));
            return Command.SINGLE_SUCCESS;
        }
        final World campaign = worlds.campaign();
        final World maps = worlds.maps();
        sender.sendMessage(plugin.text().prefixed("<gray>劇情世界：" + (campaign == null ? "<red>沒有載入" : "<white>" + campaign.getName()
                + " <gray>（已載入 " + campaign.getLoadedChunks().length + " 個區塊）")));
        sender.sendMessage(plugin.text().parse("<gray>地圖世界：" + (maps == null ? "<red>沒有載入" : "<white>" + maps.getName()
                + " <gray>（已載入 " + maps.getLoadedChunks().length + " 個區塊）")));
        if (campaign != null) {
            sender.sendMessage(plugin.text().parse("<gray>劇情世界的資料夾：<white>" + ItemRenderer.escape(folder(plugin, campaign))
                    + " <dark_gray>（關伺服器後刪掉，下次啟動會重新產生、重新擺版面與 NPC）"));
        }
        sender.sendMessage(plugin.text().parse("<gray>營地在 (0, " + (CampaignLayout.CAMP_Y + 1) + ", 7)，五個區域往東各 "
                + CampaignLayout.ZONE_LENGTH + " 格；/ec world tp 過去、/ec world setup 重擺版面與 NPC"));
        return Command.SINGLE_SUCCESS;
    }

    /** 世界資料夾，盡量寫成相對於伺服器資料夾的路徑。 */
    private static String folder(final ExileCore plugin, final World world) {
        final Path path = world.getWorldPath().toAbsolutePath().normalize();
        try {
            final Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
            return path.startsWith(root) ? root.relativize(path).toString() : path.toString();
        } catch (final RuntimeException e) {
            return path.toString();
        }
    }

    private static int teleport(final ExileCore plugin, final CommandSender sender, final boolean device) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
            return Command.SINGLE_SUCCESS;
        }
        final CampaignWorld worlds = plugin.world().worlds();
        final Location target = worlds == null ? null
                : worlds.at(device ? CampaignStructures.deviceReturn() : CampaignLayout.campSpawn());
        if (target == null) {
            sender.sendMessage(plugin.text().prefixed("<red>劇情世界沒有載入"));
            return Command.SINGLE_SUCCESS;
        }
        player.teleport(target);
        return Command.SINGLE_SUCCESS;
    }
}
