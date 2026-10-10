package tw.exilecore.stash;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/**
 * stash 模組：帳號共用的倉庫（物品頁 4 頁起，功勳可以多換；＋通貨頁）。存檔掛在角色存檔上（同一個交易）。
 * 玩家指令 {@code /stash}：在營地或倉庫管理員旁邊打開倉庫。
 */
public final class StashModule implements Module, Listener {

    private final ExileCore plugin;
    private StashService service;

    public StashModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "stash";
    }

    @Override
    public void enable() {
        service = new StashService(plugin);
        plugin.characters().setSaveExtension(service::snapshot);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 插件重載時已經在線的玩家
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            service.load(player);
        }
    }

    @Override
    public void disable() {
        // 關掉開著的倉庫：關閉時會把畫面同步回倉庫並存檔。之後角色模組停用時的最後存檔也會帶上倉庫，
        // 所以這裡不清快取。
        if (service != null) {
            service.closeAll();
        }
    }

    public StashService service() {
        return service;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        service.load(event.getPlayer());
    }

    /** 最後才卸載：角色模組的登出存檔（NORMAL）已經把倉庫一起拍成快照了。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        service.unload(event.getPlayer());
    }

    /** /stash 指令。 */
    public static LiteralCommandNode<CommandSourceStack> command(final ExileCore plugin) {
        return Commands.literal("stash")
                .executes(context -> {
                    if (context.getSource().getExecutor() instanceof Player player) {
                        if (plugin.stash() == null) {
                            player.sendMessage(plugin.text().prefixed("<red>倉庫模組沒有啟用（看主控台的錯誤訊息）"));
                        } else {
                            plugin.stash().open(player, false);
                        }
                    } else {
                        context.getSource().getSender().sendMessage(plugin.text().prefixed("<red>這個指令要在遊戲內使用"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
