package tw.exilecore.flask;

import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/**
 * flask 模組（階段 3 批次 2）：PoE2 的生命與魔力藥劑，放在快捷欄的藥劑欄，按數字鍵喝。
 * 要在 character、monster 之後啟用（殺怪充能要讀怪物稀有度）。
 */
public final class FlaskModule implements Module {

    private final ExileCore plugin;
    private final FlaskConfig config = new FlaskConfig();
    private FlaskService service;
    private BukkitTask task;
    private BukkitTask charmTask;
    private final CharmService charms;

    public FlaskModule(final ExileCore plugin) {
        this.plugin = plugin;
        this.charms = new CharmService(plugin);
    }

    @Override
    public String id() {
        return "flask";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.players() == null || plugin.monsters() == null) {
            throw new IllegalStateException("角色或怪物模組沒有啟用（看上面的錯誤訊息），藥劑模組無法運作");
        }
        config.load(plugin.dataFolder().resolve("data"));
        final FlaskService created = new FlaskService(plugin, config);
        charms.load(plugin.dataFolder().resolve("data"));
        created.setCharms(charms);
        if (plugin.items() != null) {
            plugin.items().renderer().setFlaskDecorator(created::decorate); // 2026-10：藥劑、護符是物品引擎的基底，畫完詞綴由這裡補說明
        }
        plugin.getServer().getPluginManager().registerEvents(new FlaskListener(plugin, created), plugin);
        plugin.getServer().getPluginManager().registerEvents(charms, plugin);
        charmTask = plugin.repeat("charm", charms::tick, 4, 4);
        service = created;
        task = plugin.repeat("flask", created::tick, FlaskService.TICK_INTERVAL, FlaskService.TICK_INTERVAL);
        plugin.getSLF4JLogger().info("藥劑：{} 種，藥劑欄在快捷欄 {}；護符 {} 種", config.bases().size(), created.slotText(), charms.size());
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        charms.load(plugin.dataFolder().resolve("data"));
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (charmTask != null) {
            charmTask.cancel();
            charmTask = null;
        }
    }

    public FlaskService service() {
        return service;
    }
}
