package tw.exilecore.guild;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;

/**
 * guild 模組：公會（/guild、/g）。
 * <ul>
 *   <li>建立要角色等級與金幣；一個帳號一個公會；會長、幹部、成員三種階級。</li>
 *   <li>公會頻道（/g 訊息）、名字旁邊的公會標籤（TAB、聊天）。</li>
 *   <li>公會等級：成員殺怪、捐金幣拿公會經驗；每一級的加成全公會的人都有（屬性表的來源「公會加成」）。</li>
 *   <li>公會倉庫：大家共用，誰放誰拿都有紀錄；頁數跟著公會等級變多。</li>
 *   <li>公會排行：/guild top、公會選單、網頁排行榜的「公會」。</li>
 * </ul>
 * 設定在 data/guilds.yml；公會的資料在資料庫（guilds、guild_members、guild_stash、guild_log）。
 * 要在角色、經濟、怪物模組之後啟用。
 */
public final class GuildModule implements Module {

    private final ExileCore plugin;
    private final GuildConfig config = new GuildConfig();
    private GuildService service;
    private BukkitTask task;
    /** 屬性表的來源只註冊一次（拿不掉；模組停用後回傳空的）。 */
    private boolean sourceRegistered;

    public GuildModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "guild";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.players() == null || plugin.characters() == null || plugin.economy() == null || !plugin.database().isConnected()) {
            throw new IllegalStateException("角色、經濟模組沒有啟用或資料庫沒有連線（看上面的錯誤訊息），公會模組無法運作");
        }
        config.load(plugin.dataFolder().resolve("data"));
        final GuildService created = new GuildService(plugin, config, new GuildRepository());
        created.load();
        plugin.getServer().getPluginManager().registerEvents(new GuildListener(created), plugin);
        if (!sourceRegistered) {
            sourceRegistered = true;
            plugin.players().addModifierSource(player -> service == null ? List.of() : service.modifiers(player));
        }
        service = created;
        task = plugin.repeat("guild", created::tick, 20, 20);
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        if (service != null) {
            service.reloaded();
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    /** 公會；還沒啟用時為 null。 */
    public GuildService service() {
        return service;
    }

    /** 玩家指令：/guild、/g。 */
    public static List<LiteralCommandNode<CommandSourceStack>> commands(final ExileCore plugin) {
        return GuildCommand.build(plugin);
    }
}
