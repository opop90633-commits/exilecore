package tw.exilecore.vendor;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/** vendor 模組：營地的商人（買裝備、賭博）。設定在 data/economy.yml 的 vendor 與 gamble。 */
public final class VendorModule implements Module, Listener {

    private final ExileCore plugin;
    private VendorService service;

    public VendorModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "vendor";
    }

    @Override
    public void enable() {
        service = new VendorService(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void disable() {
    }

    public VendorService service() {
        return service;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        service.forget(event.getPlayer());
    }
}
