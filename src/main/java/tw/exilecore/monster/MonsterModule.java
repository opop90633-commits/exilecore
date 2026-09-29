package tw.exilecore.monster;

import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.nio.file.Path;

/** monster 模組：原版生物等級化、稀有度、詞綴、掉落。 */
public final class MonsterModule implements Module {

    private final ExileCore plugin;
    private final MonsterConfig config;
    private final DropTable drops;
    private MonsterService service;

    public MonsterModule(final ExileCore plugin, final MonsterConfig config, final DropTable drops) {
        this.plugin = plugin;
        this.config = config;
        this.drops = drops;
    }

    @Override
    public String id() {
        return "monster";
    }

    @Override
    public void enable() throws Exception {
        final Path data = plugin.dataFolder().resolve("data");
        config.load(data);
        drops.load(data);
        service = new MonsterService(plugin, config, plugin.items().random());
        plugin.getServer().getPluginManager().registerEvents(new MonsterListener(plugin, service, drops), plugin);
        plugin.getSLF4JLogger().info("怪物設定：等級 {} 到 {}，{} 種詞綴", config.minLevel(), config.maxLevel(), config.mods().size());
    }

    @Override
    public void reload() throws Exception {
        final Path data = plugin.dataFolder().resolve("data");
        config.load(data);
        drops.load(data);
    }

    @Override
    public void disable() {
    }

    public MonsterService service() {
        return service;
    }

    public DropTable drops() {
        return drops;
    }
}
