package tw.exilecore.skill;

import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.character.StarterKits;
import tw.exilecore.module.Module;

/**
 * skill 模組（階段 3）：施放組合鍵、技能面板、投射物、技能腳本引擎的各種運作方式。
 * 要在 character、monster、combat 之後啟用（技能命中走 combat 的傷害管道）。
 */
public final class SkillModule implements Module {

    private final ExileCore plugin;
    private final SkillConfig config = new SkillConfig();
    private SkillService service;
    private BukkitTask task;

    public SkillModule(final ExileCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "skill";
    }

    @Override
    public void enable() throws Exception {
        if (plugin.players() == null || plugin.monsters() == null || !plugin.modules().enabledIds().contains("combat")) {
            throw new IllegalStateException("角色、怪物或戰鬥模組沒有啟用（看上面的錯誤訊息），技能模組無法運作");
        }
        config.load(plugin.dataFolder().resolve("data"));
        final SkillService created = new SkillService(plugin, config, plugin.combat().skillDamage());
        final PluginManager manager = plugin.getServer().getPluginManager();
        manager.registerEvents(new SkillInputListener(plugin, created), plugin);
        manager.registerEvents(created.projectiles(), plugin);
        manager.registerEvents(created.deployables(), plugin);
        manager.registerEvents(created.minions(), plugin);
        manager.registerEvents(created.auras(), plugin);
        manager.registerEvents(created.resources(), plugin); // 怒氣（2026-10 PoE2）
        manager.registerEvents(created.triggers(), plugin); // 觸發（2026-10）
        manager.registerEvents(created.procs(), plugin); // 輔助觸發的專屬技能（2026-10）
        manager.registerEvents(created.crystals(), plugin); // 冰晶（2026-10）
        manager.registerEvents(created.mounts(), plugin); // 坐騎（2026-10）
        manager.registerEvents(created.warcries().taunts(), plugin); // 嘲諷：持續時間內只打嘲諷的人
        manager.registerEvents(created.bossSkills(), plugin); // 放招時暫停、沒來得及恢復的原版 AI：載入時恢復
        created.bossSkills().recoverLoaded();
        plugin.players().addBuffSource(created.deployables()::modifiers); // 圖騰給施放者的增益
        plugin.players().addBuffSource(created.minions()::modifiers); // 魔像給主人的增益
        plugin.players().addBuffSource(created.auras()::modifiers); // 光環：自己的效果、保留、隊友給的
        plugin.players().addBuffSource(created.buffs()::modifiers); // 戰吼、護符的增益
        plugin.combat().setMinions(created.minions()); // 召喚物走戰鬥模組的傷害管道
        created.deployables().sweep(); // 上次當機留下的圖騰、陷阱
        created.minions().sweep(); // 上次當機留下的召喚物
        service = created;
        if (plugin.currencies() != null) {
            // 未切割寶石算通貨（2026-10）：通貨袋、倉庫、交易所、管理員發放做出來的是寶石；舊的寶石（只有寶石標記）也認得
            final GemService gems = created.gems();
            plugin.currencies().setGemHooks(
                    (type, amount) -> type.uncutGem() ? GemData.fromCurrencyId(type.id()).map(data -> gems.create(data, amount)).orElse(null) : null,
                    stack -> gems.read(stack).map(GemData::currencyId).orElse(null));
        }
        if (plugin.monsterModule() != null && plugin.monsterModule().effects() != null) {
            plugin.monsterModule().effects().reattachAll(); // 怪物模組先啟用：已經在世界裡的怪物補掛招式
        }
        task = plugin.repeat("skill", created::tick, 1, 1);
        plugin.getSLF4JLogger().info("技能：{} 個（另有 {} 種普攻）；Boss 招式 {} 個", config.visible().size(),
                config.all().size() - config.visible().size(), config.bossSkills().skills().size());
        warnStarterSkills();
    }

    @Override
    public void reload() throws Exception {
        config.load(plugin.dataFolder().resolve("data"));
        plugin.getSLF4JLogger().info("技能：{} 個（另有 {} 種普攻）；Boss 招式 {} 個", config.visible().size(),
                config.all().size() - config.visible().size(), config.bossSkills().skills().size());
        if (service != null) {
            service.bossSkills().refresh(); // 正在打的 Boss 下一招就用新的招式組
        }
        warnStarterSkills();
    }

    /** character.yml 新手裝備的起始技能對不到技能（打錯字、改名）；skills.yml 沒有新武器的普攻（自己改過的舊檔）。 */
    private void warnStarterSkills() {
        if (plugin.combat() != null && plugin.combat().charges() != null) {
            final tw.exilecore.charge.ChargeConfig charges = plugin.combat().charges().config();
            for (final String problem : config.chargeProblems(id -> charges.type(id) != null)) {
                plugin.getSLF4JLogger().warn(problem);
            }
        }
        if (!config.missingDefaults().isEmpty()) {
            plugin.getSLF4JLogger().warn("skills.yml 沒有 {}（自己改過的舊檔？新版在 skills.yml.new）：弩先用射箭、長棍與矛先用一般近戰的普攻",
                    String.join("、", config.missingDefaults()));
        }
        if (plugin.characters() == null) {
            return;
        }
        for (final String problem : StarterKits.skillProblems(plugin.characters().config(), id -> {
            final SkillDefinition skill = config.skill(id);
            return skill != null && !skill.hidden();
        })) {
            plugin.getSLF4JLogger().warn(problem);
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (service != null) {
            service.shutdown();
        }
        if (plugin.combat() != null) {
            plugin.combat().setMinions(null);
        }
        if (plugin.currencies() != null) {
            plugin.currencies().setGemHooks(null, null);
        }
    }

    public SkillService service() {
        return service;
    }
}
