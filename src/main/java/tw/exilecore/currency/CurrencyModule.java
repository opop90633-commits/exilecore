package tw.exilecore.currency;

import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/** currency 模組：載入通貨定義、拖曳使用。 */
public final class CurrencyModule implements Module {

    private final ExileCore plugin;
    private final CurrencyRepository repository = new CurrencyRepository();
    private CurrencyService service;

    public CurrencyModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "currency";
    }

    @Override
    public void enable() throws Exception {
        repository.load(plugin.dataFolder().resolve("data"));
        final boolean resourcePack = plugin.getConfig().getBoolean("resource-pack.enabled", false);
        service = new CurrencyService(plugin, plugin.getSLF4JLogger(), repository, plugin.items(), resourcePack);
        plugin.getServer().getPluginManager().registerEvents(new CurrencyListener(plugin, service), plugin);
        plugin.getSLF4JLogger().info("通貨：{} 種", repository.size());
    }

    @Override
    public void reload() throws Exception {
        repository.load(plugin.dataFolder().resolve("data"));
    }

    @Override
    public void disable() {
    }

    public CurrencyService service() {
        return service;
    }
}
