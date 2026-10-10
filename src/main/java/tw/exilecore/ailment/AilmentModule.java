package tw.exilecore.ailment;

import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/**
 * ailment 模組（階段 3 批次 3）：元素異常（點燃、冰緩、凍結、感電）。
 * 戰鬥模組在打中時通知這裡（{@link AilmentService#onHit}），這裡每半秒扣點燃的血、更新減速與凍結。
 * 要在 character、monster 之後啟用。
 */
public final class AilmentModule implements Module {

    private final ExileCore plugin;
    private final AilmentConfig config = new AilmentConfig();
    private AilmentService service;
    private BukkitTask task;

    public AilmentModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "ailment";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.players() == null || plugin.monsters() == null) {
            throw new IllegalStateException("角色或怪物模組沒有啟用（看上面的錯誤訊息），異常狀態模組無法運作");
        }
        config.load(plugin.dataFolder().resolve("data"));
        final AilmentService created = new AilmentService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(new AilmentListener(created), plugin);
        service = created;
        created.recoverLoaded(); // 上次關伺服器或當機前沒來得及還原的怪物與玩家
        task = plugin.repeat("ailment", created::tick, AilmentService.TICK_INTERVAL, AilmentService.TICK_INTERVAL);
        plugin.getSLF4JLogger().info("異常狀態：點燃、冰緩、凍結、感電、流血、中毒、暈眩、電擊");
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (service != null) {
            service.clearAll();
        }
    }

    public AilmentService service() {
        return service;
    }
}
