package tw.exilecore.combat;

import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/** combat 模組：接管原版傷害事件。要在 character 與 monster 之後啟用。 */
public final class CombatModule implements Module {

    private final ExileCore plugin;
    private final EnvironmentDamage environment = new EnvironmentDamage();

    public CombatModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "combat";
    }

    @Override
    public void enable() {
        environment.load(plugin.getConfig().getConfigurationSection("combat.environment"));
        plugin.getServer().getPluginManager().registerEvents(new CombatListener(plugin, environment), plugin);
    }

    @Override
    public void reload() {
        environment.load(plugin.getConfig().getConfigurationSection("combat.environment"));
    }

    @Override
    public void disable() {
    }
}
