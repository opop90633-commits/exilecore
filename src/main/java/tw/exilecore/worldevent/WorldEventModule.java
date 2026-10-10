package tw.exilecore.worldevent;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;

/** worldevent 模組：不定時活動（data/world_events.yml；開服活動）。要在委託模組之後啟用（稱號由委託模組發）。 */
public final class WorldEventModule implements Module, Listener {

    private final ExileCore plugin;
    private final WorldEventConfig config = new WorldEventConfig();
    private WorldEventService service;
    private BukkitTask task;

    public WorldEventModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "worldevent";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new WorldEventService(plugin, config);
        service.registerTitles();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.repeat("worldevent.tick", service::tick, 20, 20);
        plugin.getSLF4JLogger().info("不定時活動：{}（兌換 {} 樣；{}）", config.enabled() ? config.name() : "關", config.shop().size(),
                config.onTheHour() ? config.timezone().getId() + " 的整點 " + config.hours() : "每 " + config.interval()[0] + "–" + config.interval()[1] + " 分鐘一次");
    }

    @Override
    public void reload() throws Exception {
        load();
        if (service != null) {
            service.registerTitles();
            service.reschedule();
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
        }
    }

    private void load() throws Exception {
        final WorldEventConfig fresh = new WorldEventConfig();
        fresh.load(plugin.dataFolder().resolve("data"));
        final List<String> problems = fresh.validate(
                id -> plugin.currencies() == null || plugin.currencies().repository().get(id) != null,
                id -> plugin.items() == null || plugin.items().repository().unique(id) != null,
                id -> plugin.skills() == null || plugin.skills().config().bossSkills().skill(id) != null);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("world_events.yml 有 " + problems.size() + " 個問題：\n  " + String.join("\n  ", problems));
        }
        config.load(plugin.dataFolder().resolve("data"));
    }

    public WorldEventService service() {
        return service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        if (service == null || service.role(event.getEntity()) == null) {
            return;
        }
        final Player killer = event.getEntity().getKiller();
        service.onDeath(event.getEntity(), killer);
    }
}
