package tw.exilecore.card;

import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.util.List;

/** cards 模組（2026-10-19）：星諭卡（data/cards.yml）。要在通貨、物品、倉庫、經濟、掉落、地圖模組之後啟用。 */
public final class CardModule implements Module {

    private final ExileCore plugin;
    private final CardConfig config = new CardConfig();
    private CardService service;

    public CardModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "cards";
    }

    @Override
    public void enable() throws Exception {
        load();
        service = new CardService(plugin, config);
        plugin.getServer().getPluginManager().registerEvents(new CardListener(plugin, service), plugin);
        plugin.getSLF4JLogger().info("星諭卡：{} 種", config.cards().size());
    }

    @Override
    public void reload() throws Exception {
        load();
    }

    @Override
    public void disable() {
    }

    private void load() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        final List<String> problems = config.validate(
                id -> plugin.currencies() != null && plugin.currencies().repository().get(id) != null,
                id -> plugin.items() != null && plugin.items().repository().unique(id) != null,
                source -> plugin.items() != null && !plugin.items().repository().uniquesFor(source, config.itemLevel()).isEmpty());
        for (final String problem : problems) {
            plugin.getSLF4JLogger().warn(problem);
        }
    }

    /** 星諭卡；模組沒啟用時為 null。 */
    public CardService service() {
        return service;
    }
}
