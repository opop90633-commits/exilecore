package tw.exilecore.passive;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

import java.nio.file.Path;

/** passive 模組：載入天賦樹資料，提供樹與配點規則給 character 模組與 GUI。 */
public final class PassiveModule implements Module {

    private final ExileCore plugin;
    private PassiveTree tree;

    public PassiveModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "passive";
    }

    @Override
    public void enable() throws Exception {
        load();
    }

    @Override
    public void reload() throws Exception {
        load();
        if (plugin.characters() != null) {
            plugin.characters().treeReloaded(); // 換了一版樹：線上的角色全部退點
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.players().markDirty(player);
        }
    }

    private void load() throws Exception {
        final Path file = plugin.dataFolder().resolve("data").resolve("passives").resolve("tree.json");
        final PassiveTree loaded = PassiveTree.load(file);
        if (!loaded.isConnected()) {
            throw new IllegalArgumentException("tree.json：天賦樹不是連通的（有節點走不到）");
        }
        this.tree = loaded;
        // 注入在項鍊上的核心：物品引擎用這個查核心（樹改版後找不到的核心，項鍊會寫「已失效」）
        tw.exilecore.item.ItemStats.setInstils(id -> {
            final PassiveTree current = this.tree;
            final PassiveNode node = current == null ? null : current.node(id);
            if (node == null || node.type() != PassiveNode.Type.NOTABLE) {
                return null;
            }
            final java.util.List<tw.exilecore.stats.Modifier> mods = new java.util.ArrayList<>();
            for (final tw.exilecore.stats.Modifier mod : node.modifiers()) {
                mods.add(new tw.exilecore.stats.Modifier(mod.stat(), mod.type(), mod.value(), mod.tags(), "注入 " + node.name()));
            }
            return new tw.exilecore.item.ItemStats.Instil(node.name(), mods, node.description());
        });
        plugin.getSLF4JLogger().info("天賦樹：第 {} 版，{} 個節點，{} 個職業起點", loaded.version(), loaded.size(), loaded.starts().size());
        final java.util.List<String> missing = new java.util.ArrayList<>();
        for (final tw.exilecore.character.CharacterClass type : tw.exilecore.character.CharacterClass.values()) {
            if (loaded.start(type.id()) == null) {
                missing.add(type.displayName());
            }
        }
        if (!missing.isEmpty()) {
            plugin.getSLF4JLogger().warn("天賦樹沒有這些職業的起點：{}，他們配不了天賦（tree.json 自己改過的舊檔？新版在 passives/tree.json.new）",
                    String.join("、", missing));
        }
    }

    @Override
    public void disable() {
    }

    /** 目前的天賦樹；模組啟用失敗時為 null（角色照常運作，只是沒有天賦）。 */
    public PassiveTree tree() {
        return tree;
    }
}
