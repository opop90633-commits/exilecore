package tw.exilecore.training;

import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/**
 * training 模組：營地的訓練假人（data/dummies.yml）與練習場。要在怪物、區域、戰鬥、地圖模組之後啟用。
 */
public final class TrainingModule implements Module {

    private final ExileCore plugin;
    private final DummyConfig config = new DummyConfig();
    private TrainingService service;
    private BukkitTask tick;
    private BukkitTask maintain;

    public TrainingModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "training";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.monsters() == null || plugin.zones() == null) {
            throw new IllegalStateException("怪物或區域模組沒有啟用（看上面的錯誤訊息），訓練假人無法運作");
        }
        config.load(plugin.dataFolder().resolve("data"));
        final TrainingService created = new TrainingService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(new TrainingListener(plugin, created), plugin);
        plugin.monsters().addAugmenter(created::augment);
        service = created;
        tick = plugin.repeat("training", created::tick, 5, 5);
        maintain = plugin.repeat("training.dummies", created::maintain, 40, 100);
        plugin.getSLF4JLogger().info("訓練假人：{} 隻（營地落點旁邊；data/dummies.yml）", config.dummies().size());
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
        if (tick != null) {
            tick.cancel();
            tick = null;
        }
        if (maintain != null) {
            maintain.cancel();
            maintain = null;
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    public TrainingService service() {
        return service;
    }
}
