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
            // 2026-10-27 照 PoE2 0.5：塗油限定的核心（天賦樹上沒有）也查得到（PassiveTree.anointNode）
            final PassiveNode node = current == null ? null : current.anointNode(id);
            if (node == null) {
                return null;
            }
            final java.util.List<tw.exilecore.stats.Modifier> mods = new java.util.ArrayList<>();
            for (final tw.exilecore.stats.Modifier mod : node.modifiers()) {
                mods.add(new tw.exilecore.stats.Modifier(mod.stat(), mod.type(), mod.value(), mod.tags(), "注入 " + node.name()));
            }
            return new tw.exilecore.item.ItemStats.Instil(node.name(), mods, node.description());
        });
        // 2026-11-04（妄想症，PoE2 Megalomaniac）：「配置隨機核心天賦」的候選——天賦樹上的核心（塗油限定的不算）
        final java.util.List<String> notables = new java.util.ArrayList<>();
        final java.util.Map<String, String> notableNames = new java.util.HashMap<>();
        for (final PassiveTree.Anointable anointable : loaded.anointable()) {
            if (!anointable.anointOnly()) {
                notables.add(anointable.node().id());
                notableNames.put(anointable.node().id(), anointable.node().name());
            }
        }
        tw.exilecore.item.UniquePicks.setNotables(new tw.exilecore.item.UniquePicks.Pool(notables, notableNames::get));
        // 2026-11-07 照站長「瓦爾傳奇照原作」（熔肉之晶，PoE2「Random 1 Keystone Passive Skill [1,33]」）：「配置隨機基石」的候選——天賦樹上的基石
        final java.util.List<String> keystones = new java.util.ArrayList<>();
        final java.util.Map<String, PassiveNode> keystoneNodes = new java.util.HashMap<>();
        for (final PassiveNode node : loaded.ordered()) {
            if (node.type() == PassiveNode.Type.KEYSTONE) {
                keystones.add(node.id());
                keystoneNodes.put(node.id(), node);
            }
        }
        tw.exilecore.item.UniquePicks.setKeystones(new tw.exilecore.item.UniquePicks.Pool(keystones, id -> keystoneNodes.containsKey(id) ? keystoneNodes.get(id).name() : null),
                id -> keystoneNodes.containsKey(id) ? keystoneNodes.get(id).description() : null);
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
