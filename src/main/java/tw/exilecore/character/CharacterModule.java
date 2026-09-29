package tw.exilecore.character;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.Map;
import java.util.UUID;

/**
 * character 模組（階段 1 的最小版本）：玩家狀態、回復排程、原版資源系統的接管。
 */
public final class CharacterModule implements Module {

    /** 每幾 tick 跑一次回復與顯示。 */
    private static final int TICK_PERIOD = 10;

    private final ExileCore plugin;
    private final PlayerStateService states;
    private BukkitTask task;

    public CharacterModule(final ExileCore plugin, final PlayerStateService states) {
        this.plugin = plugin;
        this.states = states;
    }

    @Override
    public String id() {
        return "character";
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(new CharacterListener(plugin, states), plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            final long currentTick = plugin.getServer().getCurrentTick();
            for (final Map.Entry<UUID, PlayerState> entry : states.all().entrySet()) {
                final Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    states.tick(player, entry.getValue(), currentTick, TICK_PERIOD);
                }
            }
        }, TICK_PERIOD, TICK_PERIOD);
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            states.get(player);
        }
    }

    @Override
    public void reload() {
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            states.markDirty(player);
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
