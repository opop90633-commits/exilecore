package tw.exilecore.zone;

import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * zone 模組：區域（等級、營地安全區、進區提示）、刷怪群、檢查點與傳送、區域 Boss。
 */
public final class ZoneModule implements Module {

    private final ExileCore plugin;
    private final ZoneConfig config = new ZoneConfig();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private ZoneService service;
    private PackSpawner spawner;
    private BossService bosses;

    public ZoneModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "zone";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.monsters() == null || plugin.npcs() == null) {
            throw new IllegalStateException("怪物或 NPC 模組沒有啟用（看上面的錯誤訊息），區域模組無法運作");
        }
        final ZoneService created = new ZoneService(plugin, config);
        created.load();
        service = created;
        spawner = new PackSpawner(plugin, service);
        bosses = new BossService(plugin, service);
        final PluginManager manager = plugin.getServer().getPluginManager();
        manager.registerEvents(new ZoneListener(plugin, service, spawner), plugin);
        manager.registerEvents(bosses, plugin);
        schedule();
        logSummary();
    }

    @Override
    public void reload() throws Exception {
        bosses.shutdown();
        service.load();
        spawner.reload();
        schedule();
        logSummary();
    }

    @Override
    public void disable() {
        cancelTasks();
        if (bosses != null) {
            bosses.shutdown();
        }
        if (spawner != null) {
            spawner.removeAll();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    private void schedule() {
        cancelTasks();
        final int interval = config.spawner().intervalTicks();
        tasks.add(plugin.repeat("zone.track", service::trackPlayers, 10, 10));
        tasks.add(plugin.repeat("zone.markers", () -> {
            service.tickMarkers();
            service.sweepTowns();
        }, 40, 40));
        tasks.add(plugin.repeat("zone.spawner", spawner::tick, interval, interval));
        tasks.add(plugin.repeat("zone.boss", bosses::tick, 20, 20));
        final AfkWatcher afk = new AfkWatcher(plugin, service);
        tasks.add(plugin.repeat("zone.afk", afk::tick, 20, 20)); // 掛機偵測：3 分鐘提醒、5 分鐘傳回營地
    }

    private void cancelTasks() {
        for (final BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    private void logSummary() {
        int placed = 0;
        for (final Zone zone : service.zones()) {
            if (zone.placed()) {
                placed++;
            }
        }
        plugin.getSLF4JLogger().info("區域：{} 個（已擺放 {} 個）{}", service.zones().size(), placed,
                service.camp().isPresent() ? "" : "，還沒有營地（/ec zone autolayout）");
    }

    public ZoneService service() {
        return service;
    }

    PackSpawner spawner() {
        return spawner;
    }

    BossService bosses() {
        return bosses;
    }
}
