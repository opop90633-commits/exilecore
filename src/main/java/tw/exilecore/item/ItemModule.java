package tw.exilecore.item;

import tw.exilecore.ExileCore;
import tw.exilecore.core.DataFiles;
import tw.exilecore.module.Module;

import java.nio.file.Path;
import java.util.List;
import java.util.random.RandomGenerator;

/** item 模組：載入基底與詞綴資料、提供 ItemService。 */
public final class ItemModule implements Module {

    private final ExileCore plugin;
    private final ItemRepository repository = new ItemRepository();
    private final RandomGenerator random;
    private ItemService service;

    public ItemModule(final ExileCore plugin, final RandomGenerator random) {
        this.plugin = plugin;
        this.random = random;
    }

    @Override
    public String id() {
        return "item";
    }

    @Override
    public void enable() throws Exception {
        final List<String> copied = DataFiles.copyDefaults(plugin);
        if (!copied.isEmpty()) {
            plugin.getSLF4JLogger().info("已複製 {} 個預設資料檔到 plugins/ExileCore/data/", copied.size());
        }
        final Path data = plugin.dataFolder().resolve("data");
        repository.load(data);
        final boolean resourcePack = plugin.getConfig().getBoolean("resource-pack.enabled", false);
        service = new ItemService(repository, new ItemGenerator(repository), new ItemCodec(plugin),
                new ItemRenderer(repository, resourcePack), random);
        plugin.getSLF4JLogger().info("物品資料：{} 個基底、{} 條詞綴", repository.baseCount(), repository.affixCount());
    }

    @Override
    public void reload() throws Exception {
        repository.load(plugin.dataFolder().resolve("data"));
        plugin.getSLF4JLogger().info("物品資料重載：{} 個基底、{} 條詞綴", repository.baseCount(), repository.affixCount());
    }

    @Override
    public void disable() {
    }

    public ItemService service() {
        return service;
    }
}
