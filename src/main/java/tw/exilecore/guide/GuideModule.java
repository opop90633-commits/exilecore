package tw.exilecore.guide;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/** guide 模組：新手引導（側欄最上面那幾行、/guide）。要在角色、任務、技能、藥劑模組之後啟用。 */
public final class GuideModule implements Module, Listener {

    private final ExileCore plugin;
    private GuideService service;
    private BukkitTask task;

    public GuideModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "guide";
    }

    @Override
    public void enable() {
        service = new GuideService(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.repeat("guide", service::tick, 60, 40);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public GuideService service() {
        return service;
    }

    /** 2026-10-20：第一次死亡的提示。 */
    @EventHandler
    public void onDeath(final org.bukkit.event.entity.PlayerDeathEvent event) {
        if (service != null) {
            service.onDeath(event.getEntity());
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        if (service != null) {
            service.forget(event.getPlayer());
        }
    }
}
