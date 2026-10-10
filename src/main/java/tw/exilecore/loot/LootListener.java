package tw.exilecore.loot;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.gui.MainMenu;

/** Shift＋F 打開選單；登出時不再追蹤隨身背包視窗。 */
final class LootListener implements Listener {

    private final ExileCore plugin;
    private final LootService service;

    LootListener(final ExileCore plugin, final LootService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** Shift＋F：選單（F 本身在戰鬥中不做事：裝備的顯示用複本不能換手）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(final PlayerSwapHandItemsEvent event) {
        final Player player = event.getPlayer();
        if (!player.isSneaking() || player.isDead()) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (!state.isLoaded()) {
            return;
        }
        event.setCancelled(true);
        new MainMenu(plugin, player).open();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        service.forget(event.getPlayer());
    }
}
