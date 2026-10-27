package tw.exilecore.combat;

import org.bukkit.scheduler.BukkitTask;
import tw.exilecore.ExileCore;
import tw.exilecore.charge.ChargeConfig;
import tw.exilecore.charge.ChargeService;
import tw.exilecore.module.Module;

/** combat 模組：接管原版傷害事件，提供技能命中的傷害管道。要在 character 與 monster 之後啟用。 */
public final class CombatModule implements Module {

    private final ExileCore plugin;
    private final EnvironmentDamage environment = new EnvironmentDamage();
    private final SkillDamage skillDamage = new SkillDamage();
    /** 怪物身上的減益（印記、戰吼）。 */
    private final Debuffs debuffs = new Debuffs();
    /** 剛死的怪物（骸爆用）。 */
    private final Corpses corpses = new Corpses();
    private DamageNumbers numbers;
    /** 戰鬥數據（側欄的每秒輸出、承受）。 */
    private CombatHud hud;
    private BukkitTask hudTask;
    private CombatListener listener;
    /** 閃避翻滾（Ctrl）。 */
    private DodgeRoll dodge;
    private KillEffects killEffects;
    /** 昇華的招牌天賦。 */
    private Signatures signatures;
    private BukkitTask signatureTask;
    /** 死亡回顧（最後幾下是誰打的）。 */
    private final DeathRecap recap;
    /** 玩家的召喚物（技能模組接上；沒有就是 null）。 */
    private MinionHooks minions;
    /** 球（銳星、怒星、磐星）：種類在 data/charges.yml。 */
    private final ChargeConfig chargeConfig = new ChargeConfig();
    private ChargeService charges;
    private BukkitTask chargeTask;
    /** 曝露、定身、眩暈與減益的移動變慢（2026-10）。 */
    private HitEffects hitEffects;
    private org.bukkit.scheduler.BukkitTask keystoneTask;
    private BukkitTask hitEffectTask;
    private BukkitTask presenceTask;
    private tw.exilecore.item.unique.UniqueMechanics uniques;
    private BukkitTask uniqueTask;
    /** 2026-10-27：身上減益的冷卻時間這類放進屬性表（遲滯詛咒）。 */
    private BukkitTask selfDebuffTask;

    public CombatModule(final ExileCore plugin) {
        this.plugin = plugin;
        this.recap = new DeathRecap(plugin);
    }

    /** 死亡回顧：玩家挨的每一下、死掉時印出來。 */
    public DeathRecap recap() {
        return recap;
    }

    /** 技能模組啟用時接上召喚物。 */
    public void setMinions(final MinionHooks minions) {
        this.minions = minions;
    }

    /** 玩家的召喚物在傷害管道裡怎麼算；技能模組沒啟用時 null。 */
    public MinionHooks minions() {
        return minions;
    }

    @Override
    public String id() {
        return "combat";
    }

    @Override
    public void enable() throws Exception {
        environment.load(plugin.getConfig().getConfigurationSection("combat.environment"));
        try {
            chargeConfig.load(plugin.dataFolder().resolve("data"));
        } catch (final java.io.IOException | RuntimeException e) {
            // charges.yml 寫錯不該讓整個戰鬥模組起不來：先沒有球，主控台說哪裡錯
            plugin.getLogger().warning("charges.yml 讀不了，這次啟動先沒有球（銳星、怒星、磐星）：" + e.getMessage());
            chargeConfig.replace(java.util.Map.of());
        }
        charges = new ChargeService(plugin, chargeConfig);
        numbers = new DamageNumbers(plugin);
        hud = new CombatHud(plugin);
        hudTask = plugin.repeat("combat-hud", hud::tick, CombatHud.PERIOD, CombatHud.PERIOD);
        listener = new CombatListener(plugin, environment, skillDamage, numbers, debuffs, recap);
        listener.setHud(hud);
        listener.setMonsterKnockback(plugin.getConfig().getBoolean("combat.monster-knockback", false));
        listener.setCharges(charges);
        signatures = new Signatures(plugin);
        listener.setSignatures(signatures);
        signatureTask = plugin.repeat("signatures", signatures::tick, Signatures.PERIOD, Signatures.PERIOD);
        dodge = new DodgeRoll(plugin);
        listener.setDodge(dodge);
        plugin.getServer().getPluginManager().registerEvents(dodge, plugin);
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        plugin.getServer().getPluginManager().registerEvents(recap, plugin);
        plugin.getServer().getPluginManager().registerEvents(skillDamage, plugin);
        plugin.getServer().getPluginManager().registerEvents(charges, plugin);
        killEffects = new KillEffects(plugin, skillDamage, charges);
        plugin.getServer().getPluginManager().registerEvents(killEffects, plugin);
        plugin.players().addBuffSource(killEffects::modifiers); // 疾速
        plugin.players().addBuffSource(charges::modifiers); // 銳星、怒星、磐星
        chargeTask = plugin.repeat("charges", charges::tick, ChargeService.TICK_INTERVAL, ChargeService.TICK_INTERVAL);
        hitEffects = new HitEffects(plugin, debuffs);
        plugin.getServer().getPluginManager().registerEvents(hitEffects, plugin);
        // 2026-10-26：PoE2 天賦樹一次性的特殊效果（格擋、補償、擊退、感電擴散、擊殺回復…）
        final KeystoneEffects keystones = new KeystoneEffects(plugin, debuffs);
        plugin.getServer().getPluginManager().registerEvents(keystones, plugin);
        plugin.players().addBuffSource(keystones::modifiers); // 過去 10 秒每格擋一次的移動速度
        keystoneTask = plugin.repeat("keystones", keystones::tick, 20, 20); // 寶石匠的戰星產生器
        hitEffectTask = plugin.repeat("hit-effects", hitEffects::tick, 2, 2);
        // 2026-10-23：玩家死掉、下線、離開地圖時拿掉他在怪物身上留下的減益與持續傷害
        plugin.getServer().getPluginManager().registerEvents(new SourceCleanup(plugin, debuffs), plugin);
        final Presence presence = new Presence(plugin, debuffs, skillDamage); // 2026-10：臨在（範圍內的敵人…）
        presenceTask = plugin.repeat("presence", presence::tick, Presence.PERIOD, Presence.PERIOD);
        // 2026-10-26：照 PoE2 0.5 的傳奇特殊效果（壓碎、深淵消耗、點燃擴散、燃燒地面、週期神龕…）
        uniques = new tw.exilecore.item.unique.UniqueMechanics(plugin, debuffs, corpses);
        plugin.getServer().getPluginManager().registerEvents(uniques, plugin);
        uniqueTask = plugin.repeat("unique-mechanics", uniques::tick, tw.exilecore.item.unique.UniqueMechanics.PERIOD,
                tw.exilecore.item.unique.UniqueMechanics.PERIOD);
        // 2026-10-27：玩家身上減益的「技能冷卻時間增加」（遲滯詛咒）放進屬性表；這次之前只在擊中時疊上，冷卻完全沒變
        final SelfDebuffs selfDebuffs = new SelfDebuffs(plugin, debuffs);
        plugin.players().addSituationalSource(selfDebuffs::modifiers);
        selfDebuffTask = plugin.repeat("self-debuffs", selfDebuffs::tick, SelfDebuffs.PERIOD, SelfDebuffs.PERIOD);
    }

    /** 2026-10-26：傳奇的特殊效果；模組沒啟用時 null。 */
    public tw.exilecore.item.unique.UniqueMechanics uniques() {
        return uniques;
    }

    /** 球（銳星、怒星、磐星）；模組沒啟用時 null。 */
    public ChargeService charges() {
        return charges;
    }

    /** 三相輔助的元素共鳴（2026-10-22）；模組沒啟用時 null。 */
    public Trinity trinity() {
        return listener == null ? null : listener.trinity();
    }

    /** 昇華的招牌天賦；模組沒啟用時 null。 */
    public Signatures signatures() {
        return signatures;
    }

    /** 擊殺時的特殊效果（傳奇）；模組沒啟用時 null。 */
    public KillEffects killEffects() {
        return killEffects;
    }

    /** 怪物身上的減益（印記：受到的傷害增加）。 */
    public Debuffs debuffs() {
        return debuffs;
    }

    /** 剛死的怪物留下的屍體（骸爆）。 */
    public Corpses corpses() {
        return corpses;
    }

    /** 技能命中的傷害管道（skill 模組用）。 */
    public SkillDamage skillDamage() {
        return skillDamage;
    }

    @Override
    public void reload() throws Exception {
        environment.load(plugin.getConfig().getConfigurationSection("combat.environment"));
        try {
            chargeConfig.load(plugin.dataFolder().resolve("data"));
        } catch (final java.io.IOException | RuntimeException e) {
            // charges.yml 寫錯不該讓整個戰鬥模組起不來：先沒有球，主控台說哪裡錯
            plugin.getLogger().warning("charges.yml 讀不了，這次啟動先沒有球（銳星、怒星、磐星）：" + e.getMessage());
            chargeConfig.replace(java.util.Map.of());
        }
        if (listener != null) {
            listener.setMonsterKnockback(plugin.getConfig().getBoolean("combat.monster-knockback", false));
        }
    }

    /** 閃避翻滾；模組沒啟用時 null。 */
    public DodgeRoll dodge() {
        return dodge;
    }

    /** 傷害數字。 */
    public DamageNumbers numbers() {
        return numbers;
    }

    /** 戰鬥數據（側欄）；模組沒啟用時 null。 */
    public CombatHud hud() {
        return hud;
    }

    @Override
    public void disable() {
        if (numbers != null) {
            numbers.clear();
        }
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
        if (hud != null) {
            hud.clearAll();
        }
        if (chargeTask != null) {
            chargeTask.cancel();
            chargeTask = null;
        }
        if (charges != null) {
            charges.clearAll();
        }
        if (presenceTask != null) {
            presenceTask.cancel();
            presenceTask = null;
        }
        if (selfDebuffTask != null) {
            selfDebuffTask.cancel();
            selfDebuffTask = null;
        }
        if (uniqueTask != null) {
            uniqueTask.cancel();
            uniqueTask = null;
        }
        if (hitEffectTask != null) {
            hitEffectTask.cancel();
            hitEffectTask = null;
        }
        if (keystoneTask != null) {
            keystoneTask.cancel();
            keystoneTask = null;
        }
        if (hitEffects != null) {
            hitEffects.clear();
        }
    }
}
