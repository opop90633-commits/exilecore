package tw.exilecore.npc;

import tw.exilecore.ExileCore;
import tw.exilecore.module.Module;

/** npc 模組：營地 NPC（鑑定師、倉庫管理員、拆解師、傳送師）。 */
public final class NpcModule implements Module {

    private final ExileCore plugin;
    private NpcService service;

    public NpcModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "npc";
    }

    @Override
    public void enable() {
        service = new NpcService(plugin);
        plugin.getServer().getPluginManager().registerEvents(new NpcListener(service), plugin);
        // 已經載入的 NPC（插件重載）：還沒有皮膚的套一個
        for (final org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (final org.bukkit.entity.Mannequin mannequin : world.getEntitiesByClass(org.bukkit.entity.Mannequin.class)) {
                service.ensureSkin(mannequin);
            }
        }
    }

    /** /ec reload：照 config.yml 的 npc_skins 重新套皮膚（2026-10）。 */
    @Override
    public void reload() {
        if (service == null) {
            return;
        }
        for (final org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (final org.bukkit.entity.Mannequin mannequin : world.getEntitiesByClass(org.bukkit.entity.Mannequin.class)) {
                service.ensureSkin(mannequin);
            }
        }
    }

    @Override
    public void disable() {
    }

    public NpcService service() {
        return service;
    }
}
