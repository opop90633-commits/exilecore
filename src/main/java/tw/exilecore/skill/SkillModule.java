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
    private BukkitTask treeTask;
    private BukkitTask leftoverTask;

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
        if (plugin.items() != null) { // 2026-10-28：法杖、長杖、權杖的說明寫賦予的技能（等級照物品等級，跟 PlayerStateService.grantedSkills 一樣）
            plugin.items().renderer().setGrantedLabel((baseId, ilvl) -> {
                final String id = plugin.items().repository().grantedSkill(baseId);
                final SkillDefinition skill = id == null ? null : config.skill(id);
                return skill == null ? null : "Lv." + config.maxLevelFor(Math.max(1, ilvl)) + " " + skill.name();
            });
        }
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
        manager.registerEvents(created.supportMechanics(), plugin); // 2026-10-26：照 PoE2 重做的輔助寶石與戰吼
        manager.registerEvents(created.supportMechanics().lineage(), plugin); // 2026-10-27 照 PoE2 0.5：血脈輔助寶石
        manager.registerEvents(created.supportMechanics().extras(), plugin); // 2026-10-27：照 PoE2 0.5 補上的一般輔助寶石
        manager.registerEvents(created.granted(), plugin); // 2026-10-26：雷霆共鳴、折光之鏡、鴉群密謀
        manager.registerEvents(created.persistentBuffs(), plugin); // 2026-10-26：護時、幻步、木甲、不熄怒
        manager.registerEvents(created.storedReleases(), plugin); // 2026-10-27 照 PoE2：鐵壁守、瘟囊爆存傷害
        manager.registerEvents(created.originals(), plugin); // 2026-10-26：照 PoE2 原作機制重做的技能
        manager.registerEvents(created.spears(), plugin); // 2026-10-27 照 PoE2：血嗅印（死掉時的血爆）
        manager.registerEvents(created.stageEffects(), plugin); // 2026-10-28 照 PoE2：矛頭、冰爆、震脈掌的投射物
        manager.registerEvents(created.leftovers(), plugin); // 2026-10-28 照 PoE2：冰片門檻、血爆範圍、裂甲（StageLeftovers）
        manager.registerEvents(created.stageAttacks(), plugin); // 2026-10-28 照 PoE2：交錯爪的印記震波、驚雷刺的鹿靈、瘴域的毒苞
        created.originals().start();
        plugin.players().addBuffSource(created.persistentBuffs()::modifiers); // 2026-10-26：木甲的護甲
        manager.registerEvents(created.chargeEffects(), plugin); // 2026-10-24：戰星輔助
        manager.registerEvents(created.crystals(), plugin); // 冰晶（2026-10）
        manager.registerEvents(created.mounts(), plugin); // 坐騎（2026-10）
        manager.registerEvents(created.warcries().taunts(), plugin); // 嘲諷：持續時間內只打嘲諷的人
        manager.registerEvents(created.bossSkills(), plugin); // 放招時暫停、沒來得及恢復的原版 AI：載入時恢復
        created.bossSkills().recoverLoaded();
        plugin.players().addBuffSource(created.deployables()::modifiers); // 圖騰給施放者的增益
        plugin.players().addBuffSource(created.minions()::modifiers); // 魔像給主人的增益
        plugin.players().addBuffSource(created.auras()::modifiers); // 光環：自己的效果、保留、隊友給的
        plugin.players().addBuffSource(created.buffs()::modifiers); // 戰吼、護符的增益
        // 2026-10-26：天賦樹的新機制（統治者、易爆、切割、傀儡大師、秘術增強、先祖強化、衝刺…）
        final TreeMechanics treeMechanics = new TreeMechanics(plugin, created);
        created.setTreeMechanics(treeMechanics);
        manager.registerEvents(treeMechanics, plugin);
        plugin.players().addBuffSource(treeMechanics::modifiers);
        plugin.players().conditions().register(tw.exilecore.stats.TreeConditions.ARCHON, (player, state) -> treeMechanics.archon(player) != null);
        // 2026-10-28 照 PoE2 Trinity 的品質：親和加起來超過 250 時技能速度增加（鼎元訣的 effect.attack.speed@trinityhigh）
        plugin.players().conditions().register(tw.exilecore.stats.TreeConditions.TRINITY_HIGH, (player, state) -> {
            if (plugin.combat() == null || plugin.combat().trinity() == null) {
                return false;
            }
            final double[] affinity = plugin.combat().trinity().resonance(player.getUniqueId(), plugin.getServer().getCurrentTick());
            return affinity[0] + affinity[1] + affinity[2] > tw.exilecore.stats.TreeConditions.TRINITY_HIGH_TOTAL;
        });
        treeTask = plugin.repeat("tree-mechanics", treeMechanics::tick, TreeMechanics.PERIOD, TreeMechanics.PERIOD);
        // 2026-10-27 照 PoE2：天賦樹最後一批（存在範圍的進出、恫嚇、榮耀、祭品、地面…）
        final TreeLeftovers treeLeftovers = new TreeLeftovers(plugin, created);
        created.setTreeLeftovers(treeLeftovers);
        manager.registerEvents(treeLeftovers, plugin);
        treeLeftovers.registerConditions(plugin.players().conditions());
        leftoverTask = plugin.repeat("tree-leftovers", treeLeftovers::tick, TreeLeftovers.PERIOD, TreeLeftovers.PERIOD);
        plugin.combat().setMinions(created.minions()); // 召喚物走戰鬥模組的傷害管道
        plugin.combat().skillDamage().setHitProxy(created.minions()::djinnHitter); // 2026-10-29 照 PoE2：巨靈的指令照巨靈的數值算
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
        if (treeTask != null) {
            treeTask.cancel();
            treeTask = null;
        }
        if (leftoverTask != null) {
            leftoverTask.cancel();
            leftoverTask = null;
        }
        if (service != null) {
            service.shutdown();
        }
        if (plugin.combat() != null) {
            plugin.combat().setMinions(null);
            plugin.combat().skillDamage().setHitProxy(null);
        }
        if (plugin.currencies() != null) {
            plugin.currencies().setGemHooks(null, null);
        }
    }

    public SkillService service() {
        return service;
    }
}
