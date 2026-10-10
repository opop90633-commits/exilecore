package tw.exilecore.heist;

import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/**
 * heist 模組：劫盜合約、大劫盜、隱匿詞綴的揭露（data/heist.yml；NPC 是營地的盜賊首領）。
 * 要在地圖、怪物、物品、通貨模組之後啟用。
 */
public final class HeistModule implements Module {

    private final ExileCore plugin;
    private final HeistConfig config = new HeistConfig();
    private HeistService service;
    private BukkitTask task;

    public HeistModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "heist";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new HeistService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(service, plugin);
        plugin.getServer().getPluginManager().registerEvents(service.finale(), plugin); // 劫盜終局：金庫之主的收藏寶箱
        task = plugin.repeat("heist", service::tick, 20, 20);
        plugin.getSLF4JLogger().info("劫盜：{} 處據點", config.sites().size());
    }

    @Override
    public void reload() throws Exception {
        load();
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (service != null) {
            service.clear();
        }
    }

    private void load() throws Exception {
        final HeistConfig fresh = new HeistConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        config.copyFrom(fresh);
    }

    /** 劫盜；模組沒啟用時 null。 */
    public HeistService service() {
        return service;
    }
}
