package tw.exilecore.skill;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentText;
import tw.exilecore.character.CharacterService;
import tw.exilecore.character.PlayerState;
import tw.exilecore.character.SkillLoadout;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.item.ItemRenderer;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 施放流程（設計文件「施放方式」）：按鍵 → 找綁定的技能（沒綁就是依武器的普攻）→ 算出這次的數值（含輔助寶石）→
 * 檢查 → 扣魔力 → 依攻速或施法速度排下一次 → 放出。還沒到下一次的時間就排隊（只留最後一個按鍵）。
 */
public final class SkillService {

    /** 起始技能送過了（角色進度）：技能寶石可以拿出面板，不能靠清空面板一直拿新的。 */
    public static final String PROGRESS_STARTER = "skill.starter";

    /** 按鍵格子目前會放什麼：技能（面板上的那一格）或普攻（entry 為 null）。 */
    public record Resolved(SkillDefinition definition, SkillLoadout.Entry entry) {
        public boolean isDefault() {
            return entry == null;
        }

        public int level() {
            return entry == null ? 1 : entry.level();
        }
    }

    private final ExileCore plugin;
    private final SkillConfig config;
    private final SkillTargets targets;
    private final SkillVisuals visuals;
    private final ProjectileEngine projectiles;
    private final SkillZones zones;
    private final SkillExecutor executor;
    private final SkillDeployables deployables;
    private final MetaGems metas; // 2026-10：主要寶石（法術圖騰、暴擊時施放這類）
    private final MinionService minions;
    private final SkillBuffs buffs;
    private final AuraService auras;
    /** 常駐的地面圈（套了資源包的人看平面圈，取代一直畫的粒子）。 */
    private final GroundDecals decals;

    public GroundDecals decals() {
        return decals;
    }
    private final Warcries warcries;
    /** 照 PoE2 做的專屬機制（2026-10）：蓄力放開、旋矛擊的旋風、霆落矛插地、星火連珠懸浮。 */
    private final Signatures signatures;
    /** 2026-10-26 照 PoE2 的引導：逐秒扣魔力、衝鋒、持續作用。 */
    private final Channelling channelling;
    /** 2026-10-28 照 PoE2 Hollow Form：空無型態引導中顯現幻影。 */
    private final HollowImages hollowImages;
    /** 2026-10-26 照 PoE2：魔湧（Mana Tempest）的風暴。 */
    private final ManaTempest manaTempest;
    /** 2026-10-26 照 PoE2：移動中使用技能的移動速度懲罰（放技能的那段時間移動更少 70%）。
     *  2026-10-27 照站長「放技能減速全部移除」：不再套用，只留 tick／forget 清掉舊版留下的慢速走路速度與修飾器。 */
    private final SkillMovePenalty movePenalty;
    /** 蓄力技能放開之後要等這一招攻擊／施法間隔的幾成才能做別的（蓄力的時間就是出手的時間）。 */
    static final double RELEASE_RECOVERY = 0.25;
    /** 連段、榮耀、元素灌注（2026-10 PoE2）。 */
    private final SkillResources resources;
    private final BossSkills bossSkills;
    private final GemService gems;
    private final HoldToCast hold;
    private final Map<UUID, SkillRuntime> runtimes = new HashMap<>();

    public SkillService(final ExileCore plugin, final SkillConfig config, final SkillDamage damage) {
        this.plugin = plugin;
        this.config = config;
        this.grantedDamage = damage;
        this.targets = new SkillTargets(plugin);
        this.visuals = new SkillVisuals(plugin.random());
        // 套了介面資源包的人看地上的平面標示（DangerZones），Boss 招式的警告粒子不用再送給他們
        visuals.marks(player -> plugin.web() != null && plugin.web().hasPack(player));
        this.projectiles = new ProjectileEngine(plugin, targets, visuals, damage, plugin.random());
        this.zones = new SkillZones(plugin, targets, visuals, damage);
        this.projectiles.setZones(zones);
        this.executor = new SkillExecutor(plugin, targets, visuals, projectiles, zones, damage);
        this.deployables = new SkillDeployables(plugin, targets, visuals, executor, damage);
        this.metas = new MetaGems(plugin, this);
        // 2026-10：設定「別人的技能特效」關掉的人不送別人的技能粒子（Fx）
        Fx.hidesOthers(viewer -> !tw.exilecore.character.PlayerSettings.enabled(plugin.players().get(viewer),
                tw.exilecore.character.PlayerSettings.OTHERS_FX));
        // 2026-10：設定「技能音效」關掉的人，自己的技能也不出聲
        Fx.mutesOwn(viewer -> !tw.exilecore.character.PlayerSettings.enabled(plugin.players().get(viewer),
                tw.exilecore.character.PlayerSettings.SKILL_SOUNDS));
        this.minions = new MinionService(plugin, config, targets, visuals);
        this.buffs = new SkillBuffs(plugin);
        this.auras = new AuraService(plugin, config, this, targets, visuals, executor); // 2026-10-26：焚心業火拿掉，不用再傳 damage
        this.decals = new GroundDecals(plugin);
        this.auras.decals(decals);
        this.warcries = new Warcries(plugin, targets, visuals, buffs);
        this.resources = new SkillResources(plugin, config);
        this.bossSkills = new BossSkills(plugin, config, visuals, damage, plugin.random());
        this.minions.setAuras(auras);
        this.minions.setTaunts(warcries.taunts()); // 2026-10-17：召喚物的吼叫也走共用的嘲諷
        this.gems = new GemService(plugin, this);
        this.hold = new HoldToCast(plugin, this);
        this.signatures = new Signatures(plugin, this);
        this.channelling = new Channelling(plugin, this);
        this.hollowImages = new HollowImages(plugin, this);
        this.manaTempest = new ManaTempest(plugin, this);
        this.movePenalty = new SkillMovePenalty(plugin);
    }

    /** 戰吼（嘲諷的監聽要註冊）。 */
    Warcries warcries() {
        return warcries;
    }

    public SkillConfig config() {
        return config;
    }

    /** 寶石物品（未切割、技能、輔助）的建立與使用。 */
    public GemService gems() {
        return gems;
    }

    ProjectileEngine projectiles() {
        return projectiles;
    }

    SkillTargets targets() {
        return targets;
    }

    /** 2026-10-26 照 PoE2 天賦：榴彈技能多幾次冷卻次數（只算有冷卻的榴彈技能）。 */
    private int grenadeUses(final UUID uuid, final String skillId) {
        final SkillDefinition definition = config.skill(skillId);
        final Player online = plugin.getServer().getPlayer(uuid);
        if (definition == null || online == null || !definition.tags().contains(tw.exilecore.stats.Tags.GRENADE) || definition.cooldown() <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.round(plugin.players().sheet(online).flat(tw.exilecore.stats.TreeNewMechanicKeys.GRENADE_COOLDOWN_USES,
                definition.tags())));
    }

    /** 2026-10-27 照 PoE2 Second Wind：冷卻次數的倍率。 */
    private int secondWind(final UUID uuid, final String skillId) {
        final Player online = plugin.getServer().getPlayer(uuid);
        return online == null ? 1 : supportMechanics().extras().cooldownUsesFactor(online, skillId);
    }

    SkillRuntime runtime(final Player player) {
        return runtimes.computeIfAbsent(player.getUniqueId(), uuid -> {
            final SkillRuntime created = new SkillRuntime();
            // 2026-10-26：照 PoE2 可以存好幾次的冷卻；天賦「榴彈技能 +1 冷卻次數」
            // 2026-10-27 照 PoE2 Second Wind：冷卻次數加倍；裂潮、星律（血脈輔助）多的次數也算在裡面
            created.cooldownUses = key -> { // 2026-10-28：觸發的那一顆（代號後面加 .meta）照技能本身的次數
                final String id = key.endsWith(MetaGems.TRIGGERED_SUFFIX) ? key.substring(0, key.length() - MetaGems.TRIGGERED_SUFFIX.length()) : key;
                return (config.cooldownUses(id) + grenadeUses(player.getUniqueId(), id)
                        + supportMechanics().lineage().cooldownUses(plugin.getServer().getPlayer(player.getUniqueId()), id)) * secondWind(player.getUniqueId(), id);
            };
            return created;
        });
    }

    /**
     * 別的模組接走了這一下右鍵（地圖的寶箱、祭壇…）：這個 tick 與下一個 tick 的右鍵都不施放技能，
     * 不然點寶箱會順便放一發技能（世界保護把方塊互動標成拒絕，技能監聽器本來會把它當成施放）。
     */
    public void consumeRightClick(final Player player) {
        final SkillRuntime runtime = runtime(player);
        final long now = plugin.getServer().getCurrentTick();
        runtime.rightTriggerTick = now;
        runtime.lastRightTick = now;
        runtime.suppressRightUntil = Math.max(runtime.suppressRightUntil, now + 1);
    }

    void forget(final Player player) {
        runtimes.remove(player.getUniqueId());
        manaTempest.clear(player.getUniqueId()); // 2026-10-26：魔湧的風暴
        projectiles.forget(player.getUniqueId());
        zones.forget(player.getUniqueId());
        deployables.forget(player.getUniqueId());
        minions.forget(player.getUniqueId());
        buffs.forget(player.getUniqueId());
        walkers.remove(player.getUniqueId());
        auras.forget(player.getUniqueId());
        signatures.forget(player.getUniqueId());
        spears().forget(player.getUniqueId()); // 2026-10-27：插著的炎爆矛、血嗅印
        stageEffects().forget(player.getUniqueId()); // 2026-10-28：矛頭、震脈掌的增益
        executor.forgetDecompose(player.getUniqueId()); // 2026-10-28：腐屍爆的毒雲
        movePenalty.forget(player); // 2026-10-27：放技能的移動懲罰（這次之前離線的玩家一直留著）
    }

    SkillZones zones() {
        return zones;
    }

    /** 放在地上的東西（圖騰、陷阱、鐘）。 */
    /** 「別人的技能特效」設定改了：圖騰、陷阱馬上藏起來或顯示。 */
    public void refreshDeployableVisibility(final Player viewer) {
        deployables.refresh(viewer);
    }

    SkillDeployables deployables() {
        return deployables;
    }

    /** 2026-10-26：天賦樹的新機制（統治者、易爆、切割、傀儡大師、秘術增強、先祖強化…）；SkillModule 接上。 */
    private TreeMechanics treeMechanics;

    void setTreeMechanics(final TreeMechanics treeMechanics) {
        this.treeMechanics = treeMechanics;
    }

    /** 天賦樹的新機制；技能模組沒啟用時 null。 */
    public TreeMechanics treeMechanics() {
        return treeMechanics;
    }

    /** 2026-10-27 照 PoE2：天賦樹最後一批（TreeLeftovers；SkillModule 接上）。 */
    private TreeLeftovers treeLeftovers;

    void setTreeLeftovers(final TreeLeftovers treeLeftovers) {
        this.treeLeftovers = treeLeftovers;
    }

    public TreeLeftovers treeLeftovers() {
        return treeLeftovers;
    }

    /** 玩家的召喚物（殭屍、骷髏、焚魂、魔像）。 */
    private SkillTriggers triggers;

    /** 主要寶石（2026-10）。 */
    /** 2026-10-28：畫面上方資源條的主要寶石能量（ResourceBar 用；沒有回傳空字串）。 */
    public String metaEnergyHud(final org.bukkit.entity.Player player) {
        final PlayerState state = plugin.players().get(player);
        return state == null || !state.hasCharacter() ? "" : metas().hudText(player, state);
    }

    MetaGems metas() {
        return metas;
    }

    private SupportProcs procs;
    private ChargeEffects chargeEffects;
    private IceCrystals crystals;
    private Mounts mounts;

    /** 坐騎（2026-10）。 */
    Mounts mounts() {
        if (mounts == null) {
            mounts = new Mounts(plugin, this);
        }
        return mounts;
    }

    /** 冰晶（2026-10）。 */
    IceCrystals crystals() {
        if (crystals == null) {
            crystals = new IceCrystals(plugin, this);
        }
        return crystals;
    }

    private GrantedAuras granted;
    private SkillDamage grantedDamage;

    /** 2026-10-26：雷霆共鳴、折光之鏡、鴉群密謀（權杖、長杖賦予）。 */
    GrantedAuras granted() {
        if (granted == null) {
            granted = new GrantedAuras(plugin, this, grantedDamage);
        }
        return granted;
    }

    private MarkActivation markActivation;

    /** 2026-10-28 照 PoE2：引雷印、霜縛印被電擊／凍結時觸發（印記用掉、放印記的人獲得額外元素傷害的增益）。 */
    MarkActivation markActivation() {
        if (markActivation == null) {
            markActivation = new MarkActivation(plugin, this);
        }
        return markActivation;
    }

    /** 2026-10-28：敵人被電擊、凍結了（AilmentService 呼叫）：身上的引雷印、霜縛印觸發。 */
    public void markAilment(final LivingEntity target, final tw.exilecore.ailment.AilmentType type) {
        markActivation().ailment(target, type);
    }

    private DruidSupports druidSupports;

    /** 2026-10-26 照 PoE2 0.5 補的德魯伊輔助（行嵐、掠嵐、蟄甲）。 */
    public DruidSupports druidSupports() {
        if (druidSupports == null) {
            druidSupports = new DruidSupports(plugin, this);
        }
        return druidSupports;
    }

    private SupportMechanics supportMechanics;

    /** 2026-10-26：照 PoE2 0.5 重做的輔助寶石與戰吼的機制。 */
    public SupportMechanics supportMechanics() {
        if (supportMechanics == null) {
            supportMechanics = new SupportMechanics(plugin, this);
        }
        return supportMechanics;
    }

    private PersistentBuffs persistentBuffs;
    private OriginalSkills originals;
    private SpearMechanics spears;
    /** 2026-10-28 照 PoE2：打中之後才發生的分段（三連刺的矛頭、霜牙刺的冰爆、震脈掌的投射物）。 */
    private StageEffects stageEffects;

    public StageEffects stageEffects() {
        if (stageEffects == null) {
            stageEffects = new StageEffects(plugin, this);
        }
        return stageEffects;
    }

    /** 2026-10-27 照 PoE2 0.5 的矛技能：擲矛、炎爆矛（插地、怒星）、血嗅印（失血累積重暈、血爆），見 {@link SpearMechanics}。 */
    public SpearMechanics spears() {
        if (spears == null) {
            spears = new SpearMechanics(plugin, this);
        }
        return spears;
    }

    private StageAttacks stageAttacks;
    private StageLeftovers leftovers;

    /** 2026-10-28：專屬機制（旋風矛的旋風從 ProjectileEngine 交給它）。 */
    Signatures signatures() {
        return signatures;
    }

    /** 2026-10-28 照 PoE2：分段之後剩下的機制（雷暴震波、鐘的異常、冰片門檻、血爆範圍、箭雨、土牆、旋風…，見 {@link StageLeftovers}）。 */
    public StageLeftovers leftovers() {
        if (leftovers == null) {
            leftovers = new StageLeftovers(plugin, this);
        }
        return leftovers;
    }

    /** 2026-10-28 照 PoE2：攻擊技能的第二段（自己的逐級表）、逐級上限、盾技能（見 {@link StageAttacks}）。 */
    public StageAttacks stageAttacks() {
        if (stageAttacks == null) {
            stageAttacks = new StageAttacks(plugin, this);
        }
        return stageAttacks;
    }

    /** 2026-10-26：照 PoE2 原作機制重做的技能（凜冬之殼、雷縛長棍、崩滅心咒…，見 {@link OriginalSkills}）。 */
    public OriginalSkills originals() {
        if (originals == null) {
            originals = new OriginalSkills(plugin, this);
        }
        return originals;
    }

    private StoredReleases storedReleases;

    /** 2026-10-27 照 PoE2：鐵壁守、瘟囊爆（常駐存傷害、按鍵放出）。 */
    StoredReleases storedReleases() {
        if (storedReleases == null) {
            storedReleases = new StoredReleases(plugin, this);
        }
        return storedReleases;
    }

    /** 2026-10-26：護時、幻步、木甲、不熄怒（照 PoE2 只給自己的常駐增益）。 */
    PersistentBuffs persistentBuffs() {
        if (persistentBuffs == null) {
            persistentBuffs = new PersistentBuffs(plugin, this);
        }
        return persistentBuffs;
    }

    /** 輔助寶石觸發的專屬技能（2026-10）。 */
    SupportProcs procs() {
        if (procs == null) {
            procs = new SupportProcs(plugin, this);
        }
        return procs;
    }

    /** 2026-10-24（熔殼）：這個光環從哪個 tick 開著的；沒開回傳 -1。 */
    long auraSince(final Player player, final String skillId) {
        return auras.since(player.getUniqueId(), skillId);
    }

    /** 2026-10-24：消耗、產生戰星的輔助（狂速、久咒、碎地、蔓寒、雷星、完磐、祭柱）。 */
    public ChargeEffects chargeEffects() {
        if (chargeEffects == null) {
            chargeEffects = new ChargeEffects(plugin, this);
        }
        return chargeEffects;
    }

    /** 從某個位置放一個隱藏技能（輔助觸發）。 */
    void procCast(final Origin origin, final SkillBuild build, final LivingEntity preferred) {
        if (build.definition().mechanic() == Mechanic.WARCRY) {
            // 2026-10：隱藏的戰吼（給自己增益、戰星）：一律在玩家本人身上放
            if (origin.owner() != null) {
                warcries.shout(origin.owner(), runtime(origin.owner()), build);
            }
            return;
        }
        executor.executeFrom(origin, build, preferred);
    }

    /**
     * 別的模組回報的觸發事件（2026-10）：armour_broken（讓敵人完全破甲）、stun_applied（讓敵人重暈）、minion_death（自己的召喚物死掉）。
     * 被輔助的技能（skillId，面板上的）有掛 proc.事件 的輔助就擲機率放。
     */
    public void procEvent(final Player player, final String skillId, final String event, final org.bukkit.Location at, final LivingEntity target) {
        if (player == null || skillId == null) {
            return;
        }
        procs().event(player, skillId, event, at, target);
        supportMechanics().event(player, skillId, event, target); // 2026-10-26：冰嚙、霜仇嘯（凍結時）
        originals().event(player, skillId, event, target); // 2026-10-26 照 PoE2：鼓翼風的震波
        if ("stun_applied".equals(event) && target != null) {
            supportMechanics().gainGlory(player, "heavy_stun", warcries.power(target)); // 2026-10-26 照 PoE2：天罰巨鎚的榮耀（重暈照怪物強度）
        }
    }

    /** 最後一下打這隻怪的技能（是這個玩家打的、1 秒內）；沒有就 null。 */
    public String lastSkill(final LivingEntity entity, final Player player) {
        return procs().lastSkill(entity, player);
    }

    /** 技能面板上這個技能掛的輔助裡，某個屬性的 FLAT 加總（2026-10：召喚物死掉回魔力、骸恤這類只看輔助的效果）。 */
    public double supportFlat(final Player player, final String skillId, final String stat) {
        return player == null || skillId == null ? 0 : procs().supportFlat(player, skillId, stat);
    }

    /**
     * 2026-10-28 照 PoE2 技能品質：技能面板上這個技能的品質（寶石＋昇華＋天賦樹的技能品質）給的某個 FLAT 屬性（gem_quality.yml）；
     * 不在面板上回傳 0。常駐技能、隱藏技能這種不是用玩家放出去的組合算的效果用這個讀。
     */
    public double qualityFlat(final Player player, final String skillId, final String stat) {
        final SkillBuild build = equippedBuild(player, skillId);
        return build == null ? 0 : build.qualityFlat(stat);
    }

    /** 2026-10-28：技能面板上這個技能現在的組合（不在面板上回傳 null）。 */
    public SkillBuild equippedBuild(final Player player, final String skillId) {
        if (player == null || skillId == null || plugin.players() == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        final SkillDefinition definition = config.skill(skillId);
        final int position = state == null ? -1 : state.skills().find(skillId);
        if (definition == null || position < 0 || state.skills().skill(position) == null) {
            return null;
        }
        return build(state, plugin.players().sheet(player), definition, state.skills().skill(position));
    }

    /**
     * 2026-10-30 照 PoE2：昇華技能（面板上的那顆）品質給的某個 FLAT 屬性（gem_quality.yml；含嵌晶、天賦樹加的品質）；
     * 沒放在面板上、沒有品質回傳 0。昇華那邊（AscGranted、AscPaths）照等級讀的數值加上這個。
     */
    public double ascQualityFlat(final Player player, final String skillId, final String stat) {
        final SkillBuild build = equippedBuild(player, skillId);
        return build == null ? 0 : build.qualityFlat(stat);
    }

    /** 觸發（2026-10）。 */
    public SkillTriggers triggers() {
        if (triggers == null) {
            triggers = new SkillTriggers(plugin, this);
        }
        return triggers;
    }

    // ---- 弩的彈匣（2026-10，照 PoE2）：每發用掉一支，打完自動裝填 ----

    // 2026-10-26：照 PoE2——彈匣大小是每個技能自己的（skills.yml 的 clip，沒寫是 1 發），
    // 裝填時間是弩基底自己的（bases/weapons.yml 的 reload，0.7～1.1 秒），技能的「更多／更少裝填速度」寫在技能的 modifiers。
    // 這次之前：所有弩技能共用 5 發、裝填 0.35 秒
    static final double DEFAULT_RELOAD_SECONDS = 0.8; // 弩基底沒寫裝填時間的時候（舊物品）
    /** 指令型召喚物技能一次最多幾隻聽命（2026-10）。 */
    static final int COMMAND_MAX = 8;

    /** 要用彈匣的技能：弩的攻擊（沒有冷卻的）。 */
    static boolean ammoSkill(final SkillDefinition definition) {
        return definition.isAttack() && !definition.hidden() && definition.weapons().contains("crossbow") && definition.cooldown() <= 0;
    }

    static int clipSize(final SkillConfig config, final SkillBuild build, final tw.exilecore.stats.StatSheet sheet) {
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(sheet);
        final int base = config.clip(build.definition().id(), build.level());
        return Math.max(1, Math.min(200, base + (int) Math.floor(effective.flat(StatKeys.CROSSBOW_CLIP, build.profile().tags()) + 1e-6))); // 2026-10-28：無條件捨去（品質的彈匣 +x）；這次之前四捨五入
    }

    static int reloadTicks(final SkillBuild build, final tw.exilecore.stats.StatSheet sheet) {
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(sheet);
        final double speed = Math.max(0.1, effective.apply(StatKeys.CROSSBOW_RELOAD, 1.0, build.profile().tags()));
        final double weapon = sheet.flat(StatKeys.WEAPON_RELOAD, java.util.Set.of());
        final double seconds = weapon > 0 ? weapon : DEFAULT_RELOAD_SECONDS;
        return (int) Math.max(2, Math.round(seconds * 20 / speed));
    }

    private void useAmmo(final Player player, final SkillRuntime runtime, final PlayerState state, final SkillDefinition definition,
                         final SkillBuild build, final tw.exilecore.stats.StatSheet sheet, final long now) {
        final int clip = clipSize(config, build, sheet);
        int left = Math.min(clip, runtime.ammo.getOrDefault(definition.id(), clip));
        plugin.players().conditions().note(player, tw.exilecore.stats.TreeConditions.AMMO_TYPES, definition.id(), now); // 2026-10-27：過去 10 秒射過幾種彈藥
        final double save = build.profile().effective(sheet).flat(StatKeys.CROSSBOW_AMMO_SAVE, build.profile().tags())
                + (state.recently(tw.exilecore.item.unique.UniqueStats.RECENT_RELOAD, now) // 2026-10-26（破城機弩）：最近裝填過
                ? sheet.flat(tw.exilecore.item.unique.UniqueStats.CROSSBOW_AMMO_SAVE_RELOADED, java.util.Set.of()) : 0);
        if (plugin.random().nextDouble() * 100 >= save) {
            // 2026-10-26（歃盟弩）：發射最後一發時犧牲生命，不消耗那一發
            final double sacrifice = sheet.flat(tw.exilecore.item.unique.UniqueStats.CROSSBOW_SACRIFICE_LAST, java.util.Set.of());
            if (left == 1 && sacrifice > 0 && state.life() > sacrifice) {
                state.setLife(state.life() - sacrifice);
            } else {
                left--;
            }
        }
        if (left <= 0 && StatKeys.flag(build.profile().effective(sheet), tw.exilecore.stats.LineageKeys.NO_RELOAD)) {
            runtime.ammo.put(definition.id(), 0); // 2026-10-27 照 PoE2 連膛：不會自己裝填，翻滾才裝
            state.notice(String.format(Locale.ROOT, "<#c9d6e3>%s 彈匣空了：翻滾來裝填", ItemRenderer.escape(definition.name())), now + 30);
        } else if (left <= 0 && plugin.random().nextDouble() * 100 < build.profile().effective(sheet).flat(
                tw.exilecore.stats.TreeNewMechanicKeys.CROSSBOW_INSTANT_RELOAD, build.profile().tags())) {
            runtime.ammo.remove(definition.id()); // 2026-10-26 照 PoE2 天賦：x% 機率裝填直接完成（彈匣補滿、不用等）
            supportMechanics().extras().boltsReloaded(player, definition.id(), clip, now); // 2026-10-27 照 PoE2 Fresh Clip
            state.notice(String.format(Locale.ROOT, "<#c9d6e3>%s 立即裝填", ItemRenderer.escape(definition.name())), now + 20);
        } else if (left <= 0) {
            runtime.ammo.remove(definition.id());
            final int ticks = reloadTicks(build, sheet);
            runtime.reloadDone.put(definition.id(), now + ticks);
            state.notice(String.format(Locale.ROOT, "<#c9d6e3>%s 裝填中（%.1f 秒）", ItemRenderer.escape(definition.name()), ticks / 20.0), now + ticks);
        } else {
            runtime.ammo.put(definition.id(), left);
            state.notice(String.format(Locale.ROOT, "<#c9d6e3>%s <white>%d</white>／%d", ItemRenderer.escape(definition.name()), left, clip), now + 20);
        }
        plugin.players().display(player, state);
    }

    /**
     * 2026-10-26（歃盟弩，PoE2 The Last Lament）：替所有弩技能裝填一發：彈匣沒滿的多一發，裝填中的直接裝好一發。
     */
    public void loadBolt(final Player player) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        if (runtime == null) {
            return;
        }
        runtime.ammo.replaceAll((id, left) -> left + 1);
        for (final String id : List.copyOf(runtime.reloadDone.keySet())) {
            runtime.reloadDone.remove(id);
            runtime.ammo.put(id, 1);
        }
        for (final String id : runtime.ammo.keySet()) {
            supportMechanics().extras().boltsReloaded(player, id, 1, plugin.getServer().getCurrentTick()); // 2026-10-27 照 PoE2 Fresh Clip
        }
    }

    /**
     * 2026-10-26（踏骸之履）：在某個位置觸發一個技能（不花魔力、沒有冷卻）；等級照角色等級能用的最高那一級。技能不存在就不做。
     */
    public void triggerSkill(final Player player, final String skillId, final Location at) {
        final SkillDefinition definition = config.skill(skillId);
        if (definition == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        // 2026-10-29 照 PoE2：技能面板上有這個技能（先祖之魂這類賦予的技能可以插輔助）就用面板上的組合（等級、輔助）；這次之前一律沒有輔助
        final SkillBuild equipped = equippedBuild(player, skillId);
        final SkillBuild build = equipped != null ? equipped : SkillBuild.of(config, definition, config.maxLevelFor(state.level()), state.level(), false, List.of(),
                state.weaponTags(), plugin.players().sheet(player));
        if (definition.mechanic() == Mechanic.SUMMON && definition.summon() != null) {
            // 2026-10-30 修正：召喚技能（先祖之魂）被觸發時在圖騰旁叫出來；這次之前交給 procCast，召喚不在那裡處理，什麼都沒發生
            if (at.getWorld() != null && player.isOnline()) {
                final org.bukkit.util.Vector side = SkillTargets.facing(player).clone().setY(0);
                final Location spot = at.clone().add(side.lengthSquared() < 1e-6 ? new org.bukkit.util.Vector(1, 0, 0) : side.normalize());
                minions.summonAt(player, build, List.of(spot));
            }
            return;
        }
        procCast(Origin.at(player, at.clone().add(0, 0.2, 0), SkillTargets.facing(player)), build, null);
    }

    /** 2026-10-29：在某個位置觸發一個技能，指定等級並多加 extra（克拉里的分身照欺瞞的等級、吃連續使用的更多傷害）。不花魔力、沒有冷卻。 */
    public void triggerSkill(final Player player, final String skillId, final Location at, final int level, final List<Modifier> extra) {
        final SkillDefinition definition = config.skill(skillId);
        if (definition == null || at.getWorld() == null || !player.isOnline()) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        SkillBuild build = SkillBuild.of(config, definition, level, state.level(), false, List.of(), state.weaponTags(), plugin.players().sheet(player));
        if (!extra.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(extra));
        }
        procCast(Origin.at(player, at.clone().add(0, 0.2, 0), SkillTargets.facing(player)), build, null);
    }

    // ---- 2026-10-26：昇華天賦原本（尚未生效）的機制（tw.exilecore.ascendancy.AscendancyEffects）用的入口 ----

    /** 用 parent 這一下的傷害（含輔助，乘 share）在 at 放隱藏技能 subId（餘震）。subId 不存在就不做。 */
    public void castWithParent(final Player player, final String subId, final SkillBuild parent, final double share, final Location at,
                               final org.bukkit.util.Vector facing) {
        final SkillDefinition sub = config.skill(subId);
        if (sub == null || parent == null || at.getWorld() == null) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        SkillBuild build = SkillBuild.of(config, sub, parent.level(), state.level(), false, List.of(), state.weaponTags(), plugin.players().sheet(player));
        build = build.withProfile(parent.profile().scaled(share, sub.name()).derive(sub.id(), sub.tags(), java.util.Set.of()));
        procCast(Origin.at(player, at.clone().add(0, 0.2, 0), facing), build, null);
    }

    /** 同一個技能（同一個 build）在 at 再放一次（回響、重複）：不花魔力、不看冷卻。 */
    /** 2026-10-30：別的地方放的技能落在 at（幻影銳眼的霆落矛插地）。 */
    public void proxyLanded(final Player player, final SkillBuild build, final Location at) {
        if (player != null && build != null && at != null && player.isOnline()) {
            signatures.proxyLanded(player, build, at, now());
        }
    }

    public void recast(final Player player, final SkillBuild build, final Location at, final org.bukkit.util.Vector facing, final LivingEntity target) {
        if (build == null || at.getWorld() == null || !player.isOnline()) {
            return;
        }
        procCast(Origin.at(player, at, facing), build, target);
    }

    /** 面板上要靠 source 累積榮耀的技能加 amount 點（啟示錄：元素擊中、夏烏拉統治者：混沌擊中）。 */
    public void gainGlory(final Player player, final String source, final double amount) {
        supportMechanics().gainGlory(player, source, amount);
    }

    /** 面板上的某個技能直接加 amount 點榮耀（待價而沽：鬥旗每秒 5 點）。 */
    public void addGlory(final Player player, final String skillId, final double amount) {
        supportMechanics().addGloryDirect(player, skillId, amount);
    }

    /** 這個技能現在有幾點榮耀。 */
    public double glory(final Player player, final String skillId) {
        return supportMechanics().glory(player, skillId);
    }

    /** 你放著的圖騰：位置、技能代號、生命、最大生命（堅木之壘、堤防光環）。 */
    public record TotemView(Location feet, String skillId, double life, double maxLife) {
    }

    public List<TotemView> totems(final java.util.UUID owner) {
        return deployables.totemViews(owner);
    }

    /** 從離 near 最近的那根圖騰扣 amount 生命；回傳實際扣了多少（沒有圖騰是 0）。 */
    public double drainTotem(final java.util.UUID owner, final Location near, final double amount) {
        return deployables.drainNearest(owner, near, amount);
    }

    /** 重置其他技能的冷卻（時間短縮）。 */
    public void resetCooldowns(final Player player, final String except) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        if (runtime == null) {
            return;
        }
        for (final String id : List.copyOf(runtime.cooldowns.keySet())) {
            if (!id.equals(except)) {
                runtime.cooldowns.remove(id);
                showCooldown(player, id, 0);
            }
        }
        for (final String id : List.copyOf(runtime.cooldownSlots.keySet())) {
            if (!id.equals(except)) {
                runtime.cooldownSlots.remove(id);
                showCooldown(player, id, 0);
            }
        }
    }

    /** 提早拿掉一個增益（元素執政官、惡魔變身結束）。 */
    public void removeBuff(final Player player, final String id) {
        buffs.remove(player, id);
    }

    /** 這個常駐技能（光環）現在有沒有開著（巨靈、巫術護盾、鮮血沸騰…）。 */
    public boolean auraOn(final Player player, final String skillId) {
        return auras.isActive(player.getUniqueId(), skillId);
    }

    /** skills.yml 的 level_modifiers 裡某個屬性在這一級的值（沒寫是 0）。 */
    public double levelValue(final String skillId, final String stat, final int level) {
        for (final ScaledMod mod : config.levelModifiers(skillId)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        return 0;
    }

    /** 這個角色能用的最高技能等級（昇華賦予的技能照這個）。 */
    public int grantedLevel(final Player player) {
        return config.maxLevelFor(Math.max(1, plugin.players().get(player).level()));
    }

    /**
     * 面板上的某個法術免費放一次（命運同調的殘影、預告射擊）：含它自己的輔助，不花魔力、不看施法時間，有冷卻的照冷卻。
     * 面板上沒有這個技能回傳 false。
     */
    public boolean castForFree(final Player player, final String skillId, final LivingEntity target, final double scale, final String source) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        final SkillDefinition definition = config.skill(skillId);
        if (position < 0 || definition == null) {
            return false;
        }
        final SkillLoadout.Entry entry = state.skills().skill(position);
        if (definition.mechanic() == Mechanic.MARK) {
            final StatSheet sheet = plugin.players().sheet(player);
            final SkillBuild build = build(state, sheet, definition, entry);
            procCast(target == null ? Origin.of(player) : Origin.towards(player, player.getLocation(), target), build, target);
            return true;
        }
        metaCast(player, definition, entry, target, scale, source);
        return true;
    }

    /**
     * 2026-10-28 照 PoE2：星矢的主要寶石（預告射擊、幻影銳眼）孔裡能用的技能，照孔的順序；輔助是宿主其他孔的（不合的略過）。
     * 屬性需求不足、武器不對、放錯的略過；面板上沒有宿主或孔裡沒有回傳空的。
     */
    public List<SkillBuild> deadeyeSocketed(final Player player, final String hostId) {
        final PlayerState state = plugin.players().get(player);
        final SkillDefinition hostDefinition = config.skill(hostId);
        final int position = state.skills().find(hostId);
        final SkillLoadout.Entry host = position < 0 ? null : state.skills().skill(position);
        if (host == null || !DeadeyeMetas.hosts(hostDefinition)) {
            return List.of();
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final List<SkillBuild> list = new ArrayList<>();
        for (final SkillLoadout.Entry inner : GemAttributes.usable(config, innerEntries(host), sheet)) {
            final SkillDefinition definition = config.skill(inner.skillId());
            if (definition != null && DeadeyeMetas.problem(hostDefinition, definition) == null && definition.usableWith(state.weaponTags())) {
                list.add(build(state, sheet, definition, inner));
            }
        }
        return list;
    }

    /**
     * 2026-10-28 照 PoE2 Hollow Form：空無型態孔裡能用的近戰攻擊，照孔的順序；輔助是空無型態其他孔的（不合的略過）。
     * 屬性需求不足、武器不對、放錯的略過；面板上沒有空無型態或孔裡沒有回傳空的。
     */
    public List<SkillBuild> hollowSocketed(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(HollowForm.ID);
        final SkillLoadout.Entry host = position < 0 ? null : state.skills().skill(position);
        if (host == null || !HollowForm.hosts(config.skill(HollowForm.ID))) {
            return List.of();
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final List<SkillBuild> list = new ArrayList<>();
        for (final SkillLoadout.Entry inner : GemAttributes.usable(config, innerEntries(host), sheet)) {
            final SkillDefinition definition = config.skill(inner.skillId());
            if (definition != null && HollowForm.problem(definition) == null && definition.usableWith(state.weaponTags())) {
                list.add(build(state, sheet, definition, inner));
            }
        }
        return list;
    }

    /**
     * 2026-10-29：空無型態的孔裡放了技能、卻沒有一個現在能用的時候，每一顆為什麼不能用（「旋棍擊：要拿長棍」「破甲擊：力量不足…」）；
     * 孔是空的回傳 null。這次之前一律說「孔裡要放近戰攻擊」，放了武器不對、能力值不夠的技能看不出原因（放了也沒反應）。
     */
    public String hollowUnusable(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(HollowForm.ID);
        final SkillLoadout.Entry host = position < 0 ? null : state.skills().skill(position);
        if (host == null) {
            return null;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final List<String> reasons = new ArrayList<>();
        for (final SkillLoadout.Entry inner : innerEntries(host)) {
            final SkillDefinition definition = config.skill(inner.skillId());
            if (definition == null) {
                continue;
            }
            final String problem = HollowForm.problem(definition);
            final String attributes = GemAttributes.problem(config, inner, sheet);
            final String why = problem != null ? problem : attributes != null ? attributes
                    : !definition.usableWith(state.weaponTags()) ? "要拿" + definition.weaponText() : null;
            if (why != null) {
                reasons.add(definition.name() + "：" + why);
            }
        }
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    /** 2026-10-28 照 PoE2：星矢主要寶石自己的數值（吃其他孔的迅返、長留：幻影的冷卻、持續時間）；面板上沒有回傳 null。 */
    public SkillBuild deadeyeHost(final Player player, final String hostId) {
        final PlayerState state = plugin.players().get(player);
        final SkillDefinition hostDefinition = config.skill(hostId);
        final int position = state.skills().find(hostId);
        if (position < 0 || hostDefinition == null) {
            return null;
        }
        return build(state, plugin.players().sheet(player), hostDefinition, state.skills().skill(position));
    }

    /**
     * 2026-10-28 照 PoE2 Called Shots：把預告射擊孔裡的印記放在 target 身上（不花魔力、不受印記數量限制）。
     * respectCooldown：照印記自己的冷卻（吃冷卻恢復；定時放與補放要等，用掉時的擴散不用），放了就開始冷卻。回傳有沒有放。
     */
    public boolean castSocketedMark(final Player player, final SkillBuild build, final LivingEntity target, final boolean respectCooldown) {
        if (build == null || target == null || !target.isValid() || !player.isOnline() || build.params().mark() == null
                || build.params().mark().curse() || plugin.combat() == null) {
            return false;
        }
        final SkillRuntime runtime = runtime(player);
        final long now = now();
        if (respectCooldown) {
            final String cooldownKey = build.definition().id() + MetaGems.TRIGGERED_SUFFIX; // 2026-10-28 照站長：預告射擊孔裡的印記跟手動放的另一顆冷卻分開
            if (runtime.cooldownLeft(cooldownKey, now) > 0) {
                return false;
            }
            runtime.startCooldown(cooldownKey, now, build.cooldownTicks());
        }
        executor.markOn(player, build, target, true);
        return true;
    }

    /** 2026-10-28 照 PoE2 0.5：幻影銳眼的幻影用引導／蓄力的攻擊：要引導幾 tick、放開那一下的數值（疊滿；有完美時機的在完美時機放開）。 */
    public record MirageRelease(long holdTicks, SkillBuild build) {
    }

    /** 同上；不是引導／蓄力的技能 holdTicks 是 0、數值不變。 */
    public MirageRelease mirageRelease(final Player player, final SkillBuild build) {
        return mirageRelease(config.charge(build.definition().id()), build, plugin.players().sheet(player));
    }

    static MirageRelease mirageRelease(final ChargeSpec raw, final SkillBuild build, final StatSheet sheet) {
        if (raw == null || raw.mode() != ChargeSpec.Mode.RELEASE) {
            return new MirageRelease(0, build);
        }
        final ChargeSpec spec = ChargeSpec.withQuality(raw, build);
        final double interval = build.intervalTicks(sheet);
        long hold = 0;
        int stages = 1;
        if (spec.stages() > 0) {
            final double speed = Signatures.stageSpeed(build.definition(), spec, interval);
            hold = (long) Math.ceil(spec.stageTicks(speed) * (spec.stages() - 1));
            stages = spec.stages();
        }
        if (spec.perfect()) {
            hold = Math.max(hold, spec.perfectTick(interval));
        }
        hold = Math.max(1, hold);
        return new MirageRelease(hold, Signatures.released(build, spec, stages, spec.perfect(), sheet, hold / 20.0));
    }

    /** 直接給灌注（0 火、1 冰、2 電；拆射灌注）。 */
    public void addInfusion(final Player player, final int element, final int amount) {
        resources.addInfusion(player, element, amount);
    }

    /** 立刻裝滿（震裝、急裝）：skillId = null 是全部的弩技能。 */
    void reloadNow(final Player player, final String skillId) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        if (runtime == null) {
            return;
        }
        plugin.players().get(player).markRecent(tw.exilecore.item.unique.UniqueStats.RECENT_RELOAD, plugin.getServer().getCurrentTick()); // 2026-10-26：「最近裝填過」
        // 2026-10-27 照 PoE2 Fresh Clip：裝滿的那幾顆算這個技能裝填的
        final java.util.Set<String> reloaded = new java.util.HashSet<>(runtime.ammo.keySet());
        reloaded.addAll(runtime.reloadDone.keySet());
        for (final String id : reloaded) {
            if (skillId == null || skillId.equals(id)) {
                final int clip = clipOf(player, id);
                final int left = runtime.reloadDone.containsKey(id) ? 0 : runtime.ammo.getOrDefault(id, clip);
                supportMechanics().extras().boltsReloaded(player, id, clip - left, plugin.getServer().getCurrentTick());
            }
        }
        if (skillId == null) {
            runtime.ammo.clear();
            runtime.reloadDone.clear();
        } else {
            runtime.ammo.remove(skillId);
            runtime.reloadDone.remove(skillId);
        }
    }

    /** 2026-10-27：技能面板上這個弩技能的彈匣大小（不在面板上回傳 0）。 */
    private int clipOf(final Player player, final String skillId) {
        final PlayerState state = plugin.players().get(player);
        final SkillDefinition definition = config.skill(skillId);
        final int position = state.skills().find(skillId);
        if (definition == null || position < 0) {
            return 0;
        }
        final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
        return clipSize(config, build(state, sheet, definition, state.skills().skill(position)), sheet);
    }

    /** 輔助觸發「放技能時」的位置：猛擊在面前、詛咒與印記在準心指的地方、其他在腳下。 */
    private org.bukkit.Location useLocation(final Player player, final SkillDefinition definition, final SkillBuild build, final LivingEntity preferred) {
        if (definition.mechanic() == Mechanic.MARK || definition.mechanic() == Mechanic.IMPACT || definition.mechanic() == Mechanic.ZONE) {
            final Origin origin = Origin.of(player);
            final LivingEntity aimed = preferred != null ? preferred : targets.aimed(origin, build.range(), 1.2);
            return aimed != null ? aimed.getLocation() : targets.aimPoint(origin, build.range());
        }
        if (definition.tags().contains(tw.exilecore.stats.Tags.SLAM) || definition.mechanic() == Mechanic.CONE) {
            return player.getLocation().add(SkillTargets.facing(player).multiply(2.5));
        }
        return player.getLocation();
    }

    /**
     * 主要寶石觸發插槽裡的法術（2026-10，照 PoE2）：不花魔力、不看冷卻與施法時間；傷害乘 scale（暴擊時施放更少 20%…）。
     * 技能描述的代號後面接 {@link MetaGems#TRIGGERED_SUFFIX}：打出來的暴擊不會再幫暴擊時施放充能。
     */
    void metaCast(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry, final LivingEntity target,
                  final double scale, final String source) {
        metaCast(player, definition, entry, target, scale, source, java.util.Set.of());
    }

    /** 同上；metaTags 是觸發它的主要寶石的標籤（2026-10-26：Invocation 類觸發的法術算 PoE2 的 Invocated Spells）。 */
    void metaCast(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry, final LivingEntity target,
                  final double scale, final String source, final java.util.Set<String> metaTags) {
        metaCast(player, null, definition, entry, target, scale, source, metaTags);
    }

    /**
     * 2026-10-26：同上；host 是觸發它的主要寶石在面板上的項目（null = 不知道）。知道的話照宿主算輔助（{@link #innerSupportsOf}：
     * 骰運這種掛在主要寶石上、給觸發出來的技能的輔助才吃得到；這次之前觸發的法術用 supportsOf，骰運被當成不合的跳過）。
     */
    void metaCast(final Player player, final SkillLoadout.Entry host, final SkillDefinition definition, final SkillLoadout.Entry entry,
                  final LivingEntity target, final double scale, final String source, final java.util.Set<String> metaTags) {
        // 插槽的檢查在 MetaGems 已經做過，這裡只是保險：只看引擎放不放得出來
        // （2026-10-28 照 PoE2：雷遁這種原作能被觸發的位移可以；這次之前用暴擊引星的整套規則，位移一律不行）
        if (definition.hidden() || definition.type() != SkillType.SPELL || !definition.tags().contains(tw.exilecore.stats.Tags.SPELL)
                || MetaGems.engineProblem(definition) != null
                || definition.tags().contains("command")) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillBuild base = host == null ? build(state, sheet, definition, entry)
                : metaBuild(state, sheet, host, config.skill(host.skillId()), definition, entry);
        SkillBuild build = supportMechanics().lineage().triggered(base.rollElement(sheet, plugin.items().random())); // 2026-10-27 照 PoE2：骰禍的隨機範圍
        build = build.withProfile(build.profile().scaled(scale, source)
                .derive(build.profile().id() + MetaGems.TRIGGERED_SUFFIX, triggeredTags(metaTags), java.util.Set.of()));
        // 2026-10-30 照 PoE2 Cast on Minion Death 的額外品質效果（進階奇術）：插槽法術被觸發時傷害增加 x%（看主要寶石的品質）
        final double triggeredDamage = host == null ? 0 : qualityFlat(player, host.skillId(), tw.exilecore.stats.GemQualityKeys.META_TRIGGERED_DAMAGE);
        if (triggeredDamage > 0) {
            build = build.withProfile(build.profile().withLocal(List.of(new tw.exilecore.stats.Modifier(tw.exilecore.stats.StatKeys.DAMAGE,
                    tw.exilecore.stats.ModType.INCREASED, triggeredDamage / 100.0, java.util.Set.of(), "主要寶石品質（觸發時）"))));
        }
        final SkillRuntime runtime = runtime(player);
        final long now = now();
        // 2026-10-17：有冷卻的法術被觸發也照冷卻（以前能量一滿就放，冷卻等於沒有）
        // 2026-10-28 照站長：觸發的那一顆（主要寶石孔裡）跟手動施放的另一顆冷卻分開算；手動的兩顆才共用冷卻；這次之前全部共用
        final String cooldownKey = definition.id() + MetaGems.TRIGGERED_SUFFIX;
        if (runtime.cooldownLeft(cooldownKey, now) > 0) {
            return;
        }
        if (build.cooldownTicks() > 0) {
            runtime.startCooldown(cooldownKey, now, build.cooldownTicks());
        }
        plugin.players().conditions().onTriggered(player, now); // 2026-10-26：天賦樹「最近觸發過技能」
        // 2026-10-26：被觸發的法術也用掉灌注（照 PoE2：觸發的法術是玩家放的，吃得到身上的灌注；圖騰不走這裡、不吃）
        //   以前只有手動施放（performInner）才用，引星插槽法術用灌注的等於沒用
        final int[] infusionUsed = new int[3];
        final List<tw.exilecore.stats.Modifier> infused = resources.consumeInfusion(player, build.profile().effective(sheet),
                build.profile().tags(), infusionUsed);
        if (!infused.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(infused));
        }
        final String infusionText = resources.infusionUsed(player, ItemRenderer.escape(definition.name()), infusionUsed);
        if (infusionText != null) {
            state.notice(infusionText, now + 40);
        }
        final java.util.UUID previousFx = Fx.source(player.getUniqueId());
        try {
            executor.execute(player, runtime, build, target, now);
            // 2026-10：被觸發的法術也算「放了這個技能」——咒爆、朽咒這類「放下時」的輔助才會觸發
            //   （以前只有手動施放才呼叫，翻滾、暴擊時施放的詛咒掛咒爆／朽咒都打不出來）
            procs().onUse(player, definition, useLocation(player, definition, build, target), true); // 2026-10-27：觸發出來的也標成觸發的
            // 2026-10-27 照 PoE2 Fluke「recover … of cost on use」：回復的是實際付的消耗；主要寶石觸發的法術不花魔力，回 0
            //   （這次之前照法術的魔力消耗白給 0～20% 魔力）
            flukeRefund(state, build, sheet, 0);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("主要寶石觸發 {} 失敗", definition.id(), e);
        } finally {
            Fx.restore(previousFx);
        }
    }

    /**
     * 2026-10-26 照 PoE2 Fluke（骰運）：「Skills Triggered by Supported Skills recover between 0% and 20% of cost on use」——
     * 每次觸發回復 0～x% 這個法術實際付的魔力（spentMana）。2026-10-27：主要寶石觸發的法術不花魔力，所以回 0
     * （這次之前照法術本來的魔力消耗白給魔力）。
     */
    private void flukeRefund(final PlayerState state, final SkillBuild build, final StatSheet sheet, final double spentMana) {
        final double percent = sheet == null ? 0 : build.profile().effective(sheet).flat(tw.exilecore.stats.SupportKeys.FLUKE_REFUND, build.profile().tags());
        final double gained = flukeRecovery(spentMana, percent, plugin.items().random().nextDouble());
        if (gained > 0) {
            state.setMana(state.mana() + gained);
        }
    }

    /** 2026-10-26：骰運回復的魔力：消耗 × 0～percent%（roll 是 0～1 的隨機數）。 */
    static double flukeRecovery(final double cost, final double percent, final double roll) {
        return cost <= 0 || percent <= 0 ? 0 : cost * Math.min(100, percent) / 100.0 * Math.max(0, Math.min(1, roll));
    }

    /**
     * 2026-10-26：主要寶石觸發的那個法術的數值：輔助照宿主算（{@link #innerSupportsOf}，跟法術圖騰一樣），品質照放進去的寶石。
     */
    SkillBuild metaBuild(final PlayerState state, final StatSheet sheet, final SkillLoadout.Entry host, final SkillDefinition hostDefinition,
                         final SkillDefinition spell, final SkillLoadout.Entry spellEntry) {
        return SkillBuild.of(config, spell, spellEntry.level(), state.level(), false, innerSupportsOf(host, hostDefinition, spell),
                state.weaponTags(), sheet, resonantOf(spellEntry), qualityModifiers(spell, spellEntry.quality() + extraQuality(sheet)));
    }

    /** 2026-10-26 照 PoE2：主要寶石觸發的法術帶 triggered（天賦「被觸發的法術…」）；Invocation 類觸發的再帶 invocation。 */
    static java.util.Set<String> triggeredTags(final java.util.Set<String> metaTags) {
        return metaTags.contains(tw.exilecore.stats.TreeMechanicKeys.INVOCATION)
                ? java.util.Set.of(tw.exilecore.stats.TreeMechanicKeys.TRIGGERED, tw.exilecore.stats.TreeMechanicKeys.INVOCATION)
                : java.util.Set.of(tw.exilecore.stats.TreeMechanicKeys.TRIGGERED);
    }

    /** 夥伴能不能出來：有角色、不在營地（營地裡收起來）。 */
    boolean eligibleForCompanion(final Player player) {
        // 2026-10-17：觀戰中（旁觀模式）不叫精魂召喚物出來
        return plugin.players().get(player).hasCharacter() && !inTown(player)
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR;
    }

    /** 2026-10-17：收掉這個人的召喚物、圖騰、陷阱、鐘（倒下變觀戰時）；精魂召喚物觀戰中不會再叫出來。 */
    public void dismissSummons(final Player player) {
        minions.forget(player.getUniqueId());
        deployables.forget(player.getUniqueId());
    }

    public MinionService minions() {
        return minions;
    }

    /** 光環（含捷）。 */
    AuraService auras() {
        return auras;
    }

    /** 有時限的增益（戰吼）。 */
    SkillBuffs buffs() {
        return buffs;
    }

    /** Boss 招式（地圖、區域的 Boss 掛上來）。 */
    public BossSkills bossSkills() {
        return bossSkills;
    }

    /** 開著的光環的名字（動作列：快捷欄左邊）。 */
    public java.util.List<String> auraNames(final Player player) {
        return auras.hudNames(player.getUniqueId());
    }

    /** 召喚物數量（動作列：快捷欄右邊）。 */
    public java.util.List<MinionService.HudCount> minionCounts(final Player player) {
        return minions.hudCounts(player.getUniqueId());
    }

    /** 給玩家一個有時限的增益（神龕之類；同代號再給一次就重新計時）。 */
    public void buff(final Player player, final String id, final String name, final int ticks, final List<tw.exilecore.stats.Modifier> modifiers) {
        // 2026-10-26 照 PoE2 天賦樹：「你身上的增益流逝變慢 x%」：持續時間 ÷（1 − x%）
        buffs.apply(player, id, name, plugin.players() == null ? ticks
                : tw.exilecore.stats.TreeEffectKeys.buffTicks(plugin.players().get(player).sheet(), ticks), modifiers); // 不重算（重算時也可能放增益）
    }

    /** 這個代號的增益還在不在（2026-10：護符效果還在時，同一種護符不再觸發）。 */
    public boolean buffActive(final Player player, final String id) {
        for (final SkillBuffs.Buff buff : buffs.active(player.getUniqueId())) {
            if (buff.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 這個技能是不是正開著的光環（技能面板用：發光、標「開啟中」；2026-10-26 天賦樹「受到先鋒影響時」也用，改成 public）。 */
    public boolean auraActive(final Player player, final String skillId) {
        return auras.isActive(player.getUniqueId(), skillId);
    }

    /** 2026-10-26（蟄甲）：現在的形態名稱（熊形態、狼人形態、飛龍形態）；不是獸形回傳空字串。 */
    String formName(final Player player) {
        for (final SkillBuffs.Buff buff : buffs.active(player.getUniqueId())) {
            if (buff.id().equals(FORM_BUFF)) {
                return buff.name();
            }
        }
        return "";
    }

    /** 2026-10-26（天賦樹「變身時」）：現在是不是獸形（熊、狼人、飛龍的形態增益還在）。 */
    public boolean shapeshifted(final Player player) {
        return buffActive(player, FORM_BUFF);
    }

    /** 2026-10-26（天賦樹條件）：有沒有代號以 prefix 開頭的增益（舉盾 guard…、護符 charm.…）。 */
    public boolean buffActivePrefix(final Player player, final String prefix) {
        for (final SkillBuffs.Buff buff : buffs.active(player.getUniqueId())) {
            if (buff.id().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 2026-10-26（天賦樹「招架時」）：代號以 prefix 開頭的增益裡有沒有這個屬性（舉盾增益帶 flag.parry＝招架中）。 */
    public boolean buffHasStat(final Player player, final String prefix, final String stat) {
        for (final SkillBuffs.Buff buff : buffs.active(player.getUniqueId())) {
            if (buff.id().startsWith(prefix)) {
                for (final tw.exilecore.stats.Modifier modifier : buff.modifiers()) {
                    if (modifier.stat().equals(stat)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 2026-10-29 照 PoE2 Ancestral Spirits（先祖之引）：先祖之魂跟著召喚它的圖騰——圖騰沒了它也沒了。
     * 每秒（AscendancyEffects）照現在放著的圖騰數只留最新的那幾隻；沒有這個天賦就全部收掉。
     */
    public void tetherSpirits(final Player player, final boolean active) {
        minions.trimOldest(player.getUniqueId(), "ancestral_spirits", active ? totemCount(player) : 0);
    }

    /** 2026-10-26（天賦樹「有圖騰時」「每個圖騰」）：放著幾個圖騰。 */
    public int totemCount(final Player player) {
        return deployables.totems(player.getUniqueId());
    }

    /** 2026-10-26 照 PoE2「堅木之壘」：擊中的傷害先由你最近的圖騰承受 amount；回傳圖騰實際吃掉多少。 */
    public double totemAbsorb(final Player player, final double amount) {
        return deployables.absorbHit(player, amount);
    }

    /** 2026-10-26 照 PoE2「誰打牠就打誰」：你（totemHit = 圖騰、英靈）打中怪物時，怪物鎖定圖騰或轉回你。 */
    public void totemAggro(final Player player, final LivingEntity victim, final boolean totemHit) {
        deployables.aggro(player, victim, totemHit);
    }

    /** 2026-10-26：這個實體是不是圖騰（怪物的招式也打得到圖騰）。 */
    public boolean isTotem(final org.bukkit.entity.Entity entity) {
        return deployables.isTotem(entity);
    }

    /** 2026-10-26 照 PoE2：怪物的招式打到圖騰。 */
    public void totemHitByMonster(final org.bukkit.entity.Entity totem, final LivingEntity attacker, final tw.exilecore.stats.SkillProfile profile) {
        deployables.hitByMonster(totem, attacker, profile);
    }

    /** 給 /stats 與角色面板：開著的光環、隊友給的光環、還有效的增益、戰吼的強化。 */
    public List<String> statusLines(final Player player) {
        final List<String> lines = new ArrayList<>(auras.lines(player));
        final long now = now();
        for (final SkillBuffs.Buff buff : buffs.active(player.getUniqueId())) {
            lines.add(String.format(Locale.ROOT, "<#f5b942>%s<gray>：%s（剩 %.1f 秒）", ItemRenderer.escape(buff.name()),
                    ItemRenderer.escape(String.join("、", ModifierText.describeAll(buff.modifiers()))), (buff.untilTick() - now) / 20.0));
        }
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        if (runtime != null && runtime.exert != null && runtime.exert.active(now)) {
            lines.add(String.format(Locale.ROOT, "<#f5b942>戰吼強化<gray>：接下來 <white>%d</white> 次攻擊更多 <white>%d%%</white> 傷害",
                    runtime.exert.left(), Math.round(runtime.exert.more())));
        }
        if (runtime != null) {
            for (final SkillRuntime.Empowered each : runtime.empowered.values()) {
                if (each.active(now)) { // 2026-10-26：照 PoE2 每個戰吼各自的強化
                    lines.add(String.format(Locale.ROOT, "<#f5b942>%s 的強化<gray>：%s（剩 %.1f 秒）", ItemRenderer.escape(each.name()),
                            each.left() >= WarcrySpec.Empower.ALL ? "全部的" + each.spec().kindText()
                                    : "接下來 <white>" + each.left() + "</white> 次" + each.spec().kindText(), (each.untilTick() - now) / 20.0));
                }
            }
        }
        if (runtime != null && runtime.barrage != null && runtime.barrage.active(now)) {
            lines.add(String.format(Locale.ROOT, "<#ffd27f>%s<gray>：下一次" + (runtime.barrage.spec().spells() ? "法術" : "弓／矛的投射物攻擊") + "重複 <white>%d</white> 次（剩 %.1f 秒）",
                    ItemRenderer.escape(runtime.barrage.name()), runtime.barrage.repeats(), (runtime.barrage.untilTick() - now) / 20.0));
        }
        return lines;
    }

    /** 快捷欄上的增益倒數（2026-10-20）：沒綁在技能格上的增益（神龕、護符、擊殺後、滑鼠鍵的戰吼……）。 */
    public record BuffTimer(String name, long ticks) {
    }

    /** 技能格上方的倒數：hotbarSlot 是快捷列第幾格（0 起算）；missing = 增益沒了（戰鬥中提醒補放）。 */
    public record SlotTimer(int hotbarSlot, String name, long ticks, boolean missing) {
    }

    public record HudBuffs(List<SlotTimer> slots, List<BuffTimer> others) {
        public static final HudBuffs EMPTY = new HudBuffs(List.of(), List.of());

        public boolean isEmpty() {
            return slots.isEmpty() && others.isEmpty();
        }
    }

    /** 不列的增益：一直刷新、沒有真正倒數的（踏骸之履）。 */
    private static final java.util.Set<String> UNTIMED_BUFFS = java.util.Set.of("corpsewalker");

    /**
     * 快捷欄的增益倒數（2026-10-20）：技能格上的技能自己給的增益（戰吼、舉盾、連發）畫在那一格上方；
     * 戰吼沒了而且在戰鬥中，那一格畫提醒。其他的增益照快結束的排前面。
     */
    public HudBuffs hudBuffs(final Player player, final boolean fighting) {
        final long now = now();
        final java.util.Map<String, SkillBuffs.Buff> active = new java.util.LinkedHashMap<>();
        for (final SkillBuffs.Buff buff : buffs.active(player.getUniqueId())) {
            if (!UNTIMED_BUFFS.contains(buff.id())) {
                active.put(buff.id(), buff);
            }
        }
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        final SkillRuntime.Barrage barrage = runtime != null && runtime.barrage != null && runtime.barrage.active(now) ? runtime.barrage : null;
        final List<SlotTimer> slots = new ArrayList<>();
        final java.util.Set<String> onSlot = new java.util.HashSet<>();
        boolean barrageOnSlot = false;
        final PlayerState state = plugin.players().get(player);
        if (state.hasCharacter()) {
            final tw.exilecore.character.HotbarLayout layout = tw.exilecore.character.HotbarLayout.of(state);
            for (int index = 0; index < layout.skillKeys(); index++) {
                final SkillLoadout.Entry entry = state.skills().boundSkill(SkillLoadout.KEYS + index);
                final SkillDefinition definition = entry == null ? null : config.skill(entry.skillId());
                if (definition == null || definition.hidden()) {
                    continue;
                }
                final int hotbar = layout.skillSlot(index);
                long left = -1;
                for (final String id : List.of(definition.id(), "guard." + definition.id())) {
                    final SkillBuffs.Buff buff = active.get(id);
                    if (buff != null) {
                        left = Math.max(left, buff.untilTick() - now);
                        onSlot.add(id);
                    }
                }
                if (barrage != null && barrage.skillId().equals(definition.id())) {
                    left = Math.max(left, barrage.untilTick() - now);
                    barrageOnSlot = true;
                }
                if (left > 0) {
                    slots.add(new SlotTimer(hotbar, definition.name(), left, false));
                } else if (fighting && definition.mechanic() == Mechanic.WARCRY && definition.warcry() != null
                        && !definition.warcry().buff().isEmpty()) {
                    slots.add(new SlotTimer(hotbar, definition.name(), 0, true)); // 戰吼的增益沒了：提醒補放
                }
            }
        }
        final List<BuffTimer> others = new ArrayList<>();
        for (final SkillBuffs.Buff buff : active.values()) {
            if (!onSlot.contains(buff.id())) {
                others.add(new BuffTimer(buff.name(), buff.untilTick() - now));
            }
        }
        if (barrage != null && !barrageOnSlot) {
            others.add(new BuffTimer(barrage.name(), barrage.untilTick() - now));
        }
        if (plugin.combat() != null && plugin.combat().killEffects() != null) {
            final long onslaught = plugin.combat().killEffects().onslaughtLeft(player);
            if (onslaught > 0) {
                others.add(new BuffTimer("疾速", onslaught));
            }
        }
        if (slots.isEmpty() && others.isEmpty()) {
            return HudBuffs.EMPTY;
        }
        others.sort(java.util.Comparator.comparingLong(BuffTimer::ticks));
        return new HudBuffs(slots, others);
    }

    /** 增益名稱用到的字（資源包的快捷欄字型要收進去）：護符、神龕以外固定的幾個。 */
    public static final List<String> HUD_BUFF_TEXTS = List.of("擊殺後", "疾速", "增益", "熊形態", "狼人形態", "飛龍形態", "斬獲腰環（）");

    SkillExecutor executor() {
        return executor;
    }

    /** 按住左鍵連續施放（方塊距離、挖掘速度、武器的攻擊距離）。 */
    Channelling channelling() {
        return channelling;
    }

    /** 2026-10-26（PoE2 蠻衝：用的時候不會獲得怒氣）：這個玩家正在引導不能獲得怒氣的技能。 */
    public boolean channelBlocksRage(final Player player) {
        return channelling.blocksRage(runtimes.get(player.getUniqueId()));
    }

    /**
     * 2026-10-26 照 PoE2 的引導：扣一次引導的消耗（開始時、之後每秒）。血魔法、「x% 魔力消耗改成生命」照放技能的算法；
     * 扣不起回傳原因（不扣），扣了回傳 null。
     */
    String payChannel(final Player player, final PlayerState state, final SkillBuild build, final int cost) {
        if (cost <= 0) {
            return null;
        }
        final boolean bloodMagic = StatKeys.flag(state.sheet(), StatKeys.FLAG_BLOOD_MAGIC)
                || StatKeys.flag(build.profile().effective(state.sheet()), StatKeys.FLAG_BLOOD_MAGIC);
        final int[] split = lifeCostSplit(cost, bloodMagic, build.profile().effective(state.sheet()), build.profile().tags());
        if (split[0] > 0 && state.life() <= split[0]) {
            return "生命不足（要 " + split[0] + " 生命）";
        }
        if (split[1] > 0 && state.mana() + 1e-9 < split[1]) {
            return "魔力不足（要 " + split[1] + "）";
        }
        state.setLife(state.life() - split[0]);
        state.setMana(state.mana() - split[1]);
        if (split[0] > 0) {
            supportMechanics().lifeSpent(player, build.definition().id(), split[0]);
        }
        tw.exilecore.item.unique.UniqueCosts.afterManaPaid(state, state.sheet(), split[1]);
        manaTempest.spent(player, split[0] + split[1]); // 2026-10-26：魔湧（引導的逐秒消耗也算）
        plugin.players().display(player, state);
        return null;
    }

    HoldToCast hold() {
        return hold;
    }

    void shutdown() {
        if (crystals != null) {
            crystals.clearAll();
            warcries.clearSpikes(); // 2026-10：祭柱
        }
        if (mounts != null) {
            mounts.clearAll(); // 2026-10：坐騎
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            hold.clear(player);
        }
        projectiles.removeAll();
        decals.clear();
        zones.clear();
        deployables.clear();
        minions.clear();
        buffs.clear();
        auras.clear();
        bossSkills.clear();
        runtimes.clear();
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /**
     * 2026-10-26 照 PoE2 天賦樹：技能消耗拆成 {生命, 魔力}。血魔法整個改扣生命；不然「x% 的魔力消耗改成生命消耗」（最多 100%）那部分扣生命。
     * 扣生命的部分再乘「增加技能的生命消耗」。這次之前只有血魔法會扣生命。
     */
    static int[] lifeCostSplit(final int cost, final boolean bloodMagic, final StatSheet sheet, final Set<String> tags) {
        if (cost <= 0) {
            return new int[] {0, 0};
        }
        final double percent = bloodMagic ? 100 : Math.max(0, Math.min(100, sheet.flat(tw.exilecore.stats.TreeKeys.LIFE_COST_PERCENT, tags)));
        final int converted = (int) Math.round(cost * percent / 100.0);
        // 2026-10-27 照 PoE2：改扣生命的那份不吃魔力消耗效率、改吃生命消耗效率（CostKeys.manaToLife）；這次之前直接照魔力的數字
        final int life = converted <= 0 ? 0
                : (int) Math.round(converted * tw.exilecore.stats.CostKeys.manaToLife(sheet, tags)
                        * Math.max(0, 1 + sheet.increased(tw.exilecore.stats.TreeKeys.LIFE_COST, tags))
                        * Math.max(0, sheet.more(tw.exilecore.stats.TreeKeys.LIFE_COST, tags))); // 2026-10-26：鮮血獻祭的「更多技能生命消耗」
        return new int[] {life, cost - converted};
    }

    /** 走技能系統的玩家：有角色、生存或冒險模式、活著。創造與旁觀模式照原版（方便蓋東西）。 */
    public boolean eligible(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final GameMode mode = player.getGameMode();
        return state.isLoaded() && state.hasCharacter() && !player.isDead() && !state.isDead()
                && (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE);
    }

    /** 營地是安全區，不能戰鬥；營地的練習場（訓練假人旁邊）例外。 */
    public boolean inTown(final Player player) {
        if (plugin.life() != null && plugin.life().lifeMode(player.getLocation())) {
            return true; // 2026-10 星骸之海：海上是生活區，跟營地一樣不能放技能、快捷列照原版用
        }
        if (plugin.zones() == null || !plugin.zones().inTown(player.getLocation())) {
            return false;
        }
        return plugin.training() == null || !plugin.training().inArena(player.getLocation());
    }

    /** 這個按鍵綁的是光環（營地裡也能開關）。 */
    boolean auraBound(final Player player, final int key) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return false;
        }
        final Resolved resolved = resolve(state, key);
        return !resolved.isDefault() && resolved.definition().mechanic() == Mechanic.AURA;
    }

    boolean isLeaping(final Player player) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        return runtime != null && runtime.leap != null;
    }

    // ---- 按鍵 ----

    /** 2026-10-26：這個鍵是不是正在連續出手的引導（STREAM）：是的話記下還按著（數字鍵再按一次 = 結束）。 */
    private boolean streamHeld(final SkillRuntime runtime, final int key, final long now) {
        final SkillRuntime.Charging held = runtime.charging;
        final ChargeSpec spec = held == null || held.key != key || held.build == null ? null : config.charge(held.skillId);
        if (spec == null || spec.mode() != ChargeSpec.Mode.STREAM) {
            return false;
        }
        if (!SkillLoadout.mouseKey(key) && now - held.last > 3) {
            held.releaseNow = true;
        }
        held.last = now;
        return true;
    }

    /** 按下一個按鍵（0 左鍵、1 右鍵、2 潛行＋左鍵、3 潛行＋右鍵）。 */
    void trigger(final Player player, final int key, final LivingEntity preferred) {
        final SkillRuntime runtime = runtime(player);
        if (runtime.leap != null) {
            return;
        }
        final long now = now();
        if (streamHeld(runtime, key, now)) {
            return; // 2026-10-26：連續出手的引導（熾息）按住中：只記下還按著，出手由 Channelling 照間隔放
        }
        if (now + 1e-9 >= runtime.readyAt) {
            // 2026-10：排著的數字鍵還沒放（剛好這一 tick 好了），按住左鍵的連發先讓它
            if (runtime.queuedKey >= 0 && !SkillLoadout.mouseKey(runtime.queuedKey) && SkillLoadout.mouseKey(key)
                    && now - runtime.queuedAt <= SkillRuntime.HOTBAR_QUEUE_TIMEOUT) {
                final int queued = runtime.queuedKey;
                final LivingEntity target = runtime.queuedTarget == null ? null : living(player, runtime.queuedTarget);
                runtime.clearQueue();
                perform(player, runtime, queued, target, now);
                return;
            }
            runtime.clearQueue();
            perform(player, runtime, key, preferred, now);
        } else {
            // 2026-10-23：動作還沒做完時按的數字鍵：光環、戰吼、守護、印記直接插隊（不打斷手上那一招、也不用等）；
            //   位移技能打斷手上那一招馬上放；其他的照舊排隊
            final Interrupt mode = SkillLoadout.mouseKey(key) ? Interrupt.QUEUE : interruptMode(resolve(plugin.players().get(player), key).definition());
            if (mode == Interrupt.INSTANT) {
                final double saved = runtime.readyAt;
                perform(player, runtime, key, preferred, now);
                runtime.readyAt = saved; // 手上那一招照原本的時間做完（插隊的這招不另外佔動作時間）
                return;
            }
            if (mode == Interrupt.MOVEMENT) {
                final double saved = runtime.readyAt;
                final SkillRuntime.Repeat pending = runtime.repeat;
                runtime.readyAt = now;
                runtime.repeat = null;
                perform(player, runtime, key, preferred, now);
                if (runtime.readyAt <= now + 1e-9 && runtime.leap == null) {
                    // 沒放出去（冷卻中、魔力不夠、前方沒空間）：手上那一招照舊
                    runtime.readyAt = saved;
                    runtime.repeat = pending;
                    return;
                }
                runtime.clearQueue();
                if (pending != null) {
                    // 複擊被打斷：剩下的不打了，照常開始冷卻（不能靠位移跳過冷卻）
                    final SkillDefinition interrupted = pending.build().definition();
                    runtime.startCooldown(interrupted.id(), now, pending.build().cooldownTicks());
                    showCooldown(player, interrupted.id(), pending.build().cooldownTicks());
                }
                return;
            }
            runtime.queue(key, preferred == null ? null : preferred.getUniqueId(), now);
        }
    }

    /** 灌注元素的一個字（火／冰／電）。 */
    static String infusionWord(final String element) {
        return switch (element) {
            case "fire" -> "火";
            case "cold" -> "冰";
            default -> "電";
        };
    }

    /** 殘骸是哪一種灌注（random = 三種隨機一種）。 */
    private int remnantElement(final ResourceSpec spec) {
        return "random".equals(spec.remnant()) ? plugin.random().nextInt(3) : SkillResources.infusionIndex(spec.remnant());
    }

    /** 2026-10-26 照 PoE2：過 remnant_delay 秒在 at 留下灌注殘骸（random = 三種隨機一種）。 */
    private void dropInfusionRemnantLater(final Player player, final org.bukkit.Location at, final ResourceSpec spec, final ChargeEffects.RemnantMods mods) {
        final int element = remnantElement(spec);
        final org.bukkit.Location spot = at.clone();
        final long delay = Math.max(1, Math.round(spec.remnantDelay() * 20));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> chargeEffects().dropInfusionRemnant(player, spot, element, mods), delay); // 2026-10-27：殘骸輔助
    }

    /** 2026-10-26 照 PoE2 Rhoa Mount：正騎在坐騎上（被打會累積重暈）。 */
    public boolean mounted(final Player player) {
        return mounts != null && mounts.mounted(player);
    }

    /** 2026-10-26 照 PoE2 Rhoa Mount：騎著被重暈就摔下坐騎。 */
    public void dismountOnStun(final Player player) {
        if (mounted(player)) {
            mounts.dismount(player);
            plugin.players().get(player).notice("<red>被重暈，摔下坐騎", now() + 40);
        }
    }

    /**
     * 2026-10-26：用掉灌注的加成套到這一次：多連鎖（skill.chains FLAT）、範圍（skill.area INCREASED）改技能本身，其他當這一下的修飾器。
     */
    static SkillBuild applyInfused(final SkillBuild build, final List<tw.exilecore.stats.Modifier> mods) {
        SkillBuild result = build;
        final List<tw.exilecore.stats.Modifier> local = new ArrayList<>();
        for (final tw.exilecore.stats.Modifier mod : mods) {
            if (StatKeys.SKILL_CHAINS.equals(mod.stat()) && mod.type() == tw.exilecore.stats.ModType.FLAT) {
                result = result.withChains(result.chains() + (int) Math.round(mod.value()));
            } else if (StatKeys.SKILL_AREA.equals(mod.stat()) && mod.type() == tw.exilecore.stats.ModType.INCREASED) {
                result = result.withAreaAndProjectiles(result.areaScale() * Math.sqrt(1 + mod.value()), result.projectiles());
            } else if (StatKeys.SKILL_PROJECTILES.equals(mod.stat()) && mod.type() == tw.exilecore.stats.ModType.FLAT) {
                result = result.withAreaAndProjectiles(result.areaScale(), result.projectiles() + (int) Math.round(mod.value())); // 2026-10-26 游雷冰灌注
            } else {
                local.add(mod);
            }
        }
        return local.isEmpty() ? result : result.withProfile(result.profile().withLocal(local));
    }

    /**
     * 2026-10-28 照 PoE2（RePoE SparkColdInfusedPlayer）：用掉灌注時整組換掉的數值——基礎傷害換成灌注版的逐級表、
     * 基礎投射物數換成灌注版的（+x 投射物照樣加上去，最多 {@link SkillBuild#MAX_PROJECTILES}）、改成往四周一圈射出。
     * 這次之前游雷冰灌注做成多 7 顆投射物、獲得 120% 傷害為額外冰冷（平衡等效）。
     */
    static SkillBuild applyInfusedSet(final SkillBuild build, final ResourceSpec.InfusedSet set) {
        if (set == null) {
            return build;
        }
        SkillBuild result = build;
        if (!set.damage().isEmpty() && build.profile().spellDamage() != null) {
            final tw.exilecore.stats.DamagePacket min = new tw.exilecore.stats.DamagePacket();
            final tw.exilecore.stats.DamagePacket max = new tw.exilecore.stats.DamagePacket();
            for (final java.util.Map.Entry<tw.exilecore.stats.DamageType, int[]> entry : set.damage().entrySet()) {
                final double[] range = SkillProfiles.tableAt(entry.getValue(), build.level());
                min.set(entry.getKey(), range[0]);
                max.set(entry.getKey(), range[1]);
            }
            final tw.exilecore.stats.SkillProfile profile = build.profile();
            result = result.withProfile(new tw.exilecore.stats.SkillProfile(profile.id(), profile.tags(), profile.effectiveness(), min, max,
                    profile.conversion(), profile.local()));
        }
        if (set.projectiles() != null || set.nova()) {
            final int base = build.definition().params().projectiles().intAt(build.level());
            final int count = set.projectiles() == null ? result.projectiles()
                    : result.projectiles() - base + set.projectiles().intAt(build.level());
            result = result.withProjectilesSpread(count, set.nova());
        }
        return result;
    }

    /** 2026-10-23：動作中按下去的技能怎麼處理。 */
    enum Interrupt { QUEUE, INSTANT, MOVEMENT }

    /** 光環、戰吼、守護、印記（含詛咒）：插隊；帶「位移」標籤的位移、跳躍：打斷；其他排隊。純函式（測試用）。 */
    static Interrupt interruptMode(final SkillDefinition definition) {
        if (definition == null) {
            return Interrupt.QUEUE;
        }
        return switch (definition.mechanic()) {
            case AURA, WARCRY, GUARD, MARK -> Interrupt.INSTANT;
            case DASH, LEAP -> definition.tags().contains(Tags.MOVEMENT) ? Interrupt.MOVEMENT : Interrupt.QUEUE;
            default -> Interrupt.QUEUE;
        };
    }

    /** 綁了什麼（狀態列、提示用）：「｜左 熾星彈｜右 霜星爆｜3 怒雷」，都沒綁是「（都是普攻）」。 */
    String bindingSummary(final PlayerState state) {
        final StringBuilder text = new StringBuilder();
        for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
            final Resolved resolved = resolve(state, slot);
            if (!resolved.isDefault()) {
                text.append("<dark_gray>｜<gray>").append(tw.exilecore.character.HotbarLayout.of(state).shortKeyName(slot)).append(" <white>")
                        .append(tw.exilecore.item.ItemRenderer.escape(resolved.definition().name()));
            }
        }
        return text.isEmpty() ? "<gray>（都是普攻）" : text.toString();
    }

    /** 每 tick：排隊的按鍵、跳躍落地、投射物。一個玩家出錯不影響其他人。 */
    void tick() {
        final long now = now();
        if (now % GroundDecals.FOLLOW_STEP == 0) {
            decals.follow(); // 常駐地面圈：跟著實體走的移過去
        }
        if (now % GroundDecals.SPIN_STEP == 0) {
            decals.spin(); // 光環的法陣與符文：送下一個角度（客戶端補成連續的轉動）
        }
        if (now % 20 == 0) {
            decals.tick(); // 常駐地面圈：主人不在的收掉、附近新來的人看得到
        }
        final boolean syncHold = now % 10 == 0;
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (syncHold) {
                try {
                    hold.sync(player); // 進出營地、換角色、切遊戲模式：最多半秒內跟上
                } catch (final RuntimeException e) {
                    tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "按住左鍵設定出錯（" + player.getName() + " 等人）", e); // 2026-10-27：同一個錯一分鐘寫一次
                }
            }
            movePenalty.tick(player, now); // 2026-10-26：放技能的移動懲罰時間到了就拿掉（2026-10-27 不再套用，只清舊的慢速）
            final SkillRuntime runtime = runtimes.get(player.getUniqueId());
            if (runtime == null) {
                continue;
            }
            try {
                tickPlayer(player, runtime, now);
            } catch (final RuntimeException e) {
                runtime.clearQueue();
                runtime.leap = null;
                tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "技能施放出錯（" + player.getName() + " 等人）", e);
            }
        }
        // 各部分分開計時（/ec perf 的 skill.*），出錯只影響自己那部分
        timed("skill.projectiles", projectiles::tick, "投射物更新出錯");
        timed("skill.zones", zones::tick, "地面效果更新出錯");
        timed("skill.deploy", deployables::tick, "圖騰、陷阱更新出錯");
        timed("skill.minions", minions::tick, "召喚物更新出錯");
        timed("skill.boss", bossSkills::tick, "Boss 招式更新出錯");
        if (now % 10 == 0 && plugin.combat() != null) {
            try {
                tickDebuffs(now);
            } catch (final RuntimeException e) {
                tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "印記更新出錯", e);
            }
        }
        if (now % 5 == 0) {
            timed("skill.supports", () -> supportMechanics().tick(now), "輔助寶石的機制更新出錯"); // 2026-10-26
        }
        if (now % 10 == 5) {
            try {
                buffs.tick();
                auras.tick(now);
                granted().tick(); // 2026-10-26：鏡子、烏鴉
                persistentBuffs().tick(); // 2026-10-26：護時、幻步、木甲、不熄怒
                warcries.taunts().tick();
                if (mounts != null && now % 20 == 5) {
                    mounts.tick(); // 2026-10：坐騎
                }
            } catch (final RuntimeException e) {
                tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "光環、增益更新出錯", e);
            }
            try {
                corpseWalkers(now);
            } catch (final RuntimeException e) {
                tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "踏骸之履出錯", e);
            }
            try {
                corpseMarkers(now);
            } catch (final RuntimeException e) {
                tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "屍體記號出錯", e);
            }
        }
    }

    private void timed(final String name, final Runnable task, final String error) {
        plugin.perf().time(name, () -> {
            try {
                task.run();
            } catch (final RuntimeException e) {
                tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), error, e); // 2026-10-27：每 tick 出錯不要每 tick 寫一份堆疊
            }
        });
    }

    /** 面板上有骸爆、喚屍的人：附近還在的屍體冒一點灰燼，才知道有沒有材料。 */
    private void corpseMarkers(final long now) {
        if (plugin.combat() == null || plugin.combat().corpses().size() == 0) {
            return;
        }
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().get(player);
            if (!state.hasCharacter() || !usesCorpses(state)) {
                continue;
            }
            final Location at = player.getLocation();
            for (final tw.exilecore.combat.Corpses.Corpse corpse : plugin.combat().corpses().near(at.getWorld().getUID(), at.getX(), at.getZ(), 18, now)) {
                // 2026-10-17：更明顯（一圈亮綠點＋靈魂），只畫給自己；快消失變紅
                visuals.corpseMarker(player, new Location(at.getWorld(), corpse.x(), corpse.y(), corpse.z()), corpse.until() - now <= 60);
            }
        }
    }

    // ---- 踏骸之履（2026-10-17 傳奇）：移動時在腳下留屍體；附近每具屍體回生命、加移動速度 ----

    /** 移動時每幾 tick 留一具。 */
    private static final long WALK_CORPSE_TICKS = 30;
    /** 數「附近的屍體」的半徑（格）。 */
    private static final double WALK_RADIUS = 6;
    /** 最多算幾具（不會無限疊）。 */
    private static final int WALK_MAX = 15;
    /** 留下的屍體大小：同等級一般怪最大生命的幾成。 */
    private static final double WALK_CORPSE_LIFE = 0.5;

    /** {上次算到幾具, 上次給增益的 tick, 上次留屍體的 tick, 上次留屍體的 x, z}。 */
    private final Map<UUID, double[]> walkers = new HashMap<>();

    private void corpseWalkers(final long now) {
        if (plugin.combat() == null) {
            return;
        }
        final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().get(player);
            if (!state.hasCharacter() || player.isDead()) {
                continue;
            }
            final tw.exilecore.stats.StatSheet sheet = plugin.players().sheet(player);
            final double regen = sheet.get(tw.exilecore.stats.StatKeys.CORPSE_LIFE_REGEN);
            final double move = sheet.get(tw.exilecore.stats.StatKeys.CORPSE_MOVEMENT);
            final boolean walk = sheet.get(tw.exilecore.stats.StatKeys.CORPSE_WALK) > 0;
            double[] memo = walkers.get(player.getUniqueId());
            if (regen <= 0 && move <= 0 && !walk) {
                if (memo != null) {
                    walkers.remove(player.getUniqueId());
                    buffs.remove(player, "corpsewalker");
                }
                continue;
            }
            final Location at = player.getLocation();
            if (memo == null) {
                memo = new double[] {-1, 0, 0, at.getX(), at.getZ()};
                walkers.put(player.getUniqueId(), memo);
            }
            // 移動時留屍體（走了 2 格以上、間隔夠了；那裡已經堆了 8 具就不再留）
            if (walk && plugin.monsters() != null && now - (long) memo[2] >= WALK_CORPSE_TICKS) {
                final double dx = at.getX() - memo[3];
                final double dz = at.getZ() - memo[4];
                if (dx * dx + dz * dz >= 4 && corpses.near(at.getWorld().getUID(), at.getX(), at.getZ(), 3, now).size() < DESECRATE_PILE) {
                    final Location ground = groundOf(at);
                    corpses.add(at.getWorld().getUID(), ground.getX(), ground.getY(), ground.getZ(),
                            plugin.monsters().config().lifeAt(Math.max(1, state.level())) * WALK_CORPSE_LIFE, now);
                    Fx.as(player.getUniqueId(), () -> Fx.spawn(ground.getWorld(), org.bukkit.Particle.SOUL, ground.clone().add(0, 0.2, 0),
                            4, 0.25, 0.05, 0.25, 0.01));
                    memo[2] = now;
                    memo[3] = at.getX();
                    memo[4] = at.getZ();
                }
            }
            final int count = Math.min(WALK_MAX, corpses.near(at.getWorld().getUID(), at.getX(), at.getZ(), WALK_RADIUS, now).size());
            // 數量變了、或快到期（每 2 秒續一次）才重給增益（不要每次都重算屬性表）
            if (count != (int) memo[0] || now - (long) memo[1] >= 40) {
                memo[0] = count;
                memo[1] = now;
                if (count <= 0) {
                    buffs.remove(player, "corpsewalker");
                } else {
                    final List<tw.exilecore.stats.Modifier> mods = new ArrayList<>();
                    if (regen > 0) {
                        mods.add(tw.exilecore.stats.Modifier.flat(tw.exilecore.stats.StatKeys.LIFE_REGEN, regen * count, "踏骸之履（屍體 ×" + count + "）"));
                    }
                    if (move > 0) {
                        mods.add(tw.exilecore.stats.Modifier.increased(tw.exilecore.stats.StatKeys.MOVEMENT_SPEED, move * count,
                                "踏骸之履（屍體 ×" + count + "）"));
                    }
                    buffs.apply(player, "corpsewalker", "踏骸之履：附近 " + count + " 具屍體", 60, mods);
                }
            }
        }
    }

    /** 一個地方最多堆幾具（褻瀆不會越堆越多）。 */
    private static final int DESECRATE_PILE = 8;

    /**
     * 褻瀆（2026-10，技能）：準心指的地方（指著怪就是怪的腳下）從地底拖出幾具屍體，給骸爆、喚屍用。
     * 屍體大小 = 同等級一般怪最大生命 × 技能等級的 life%；那裡已經堆了 {@value #DESECRATE_PILE} 具就不再生。回傳生了幾具。
     */
    private int desecrate(final Player player, final SkillBuild build, final long now) {
        if (plugin.combat() == null || plugin.monsters() == null) {
            return 0;
        }
        final SkillParams params = build.params();
        final LivingEntity aimed = targets.aimed(Origin.of(player), build.range(), 1.0);
        final Location center = aimed != null ? aimed.getLocation() : targets.aimPoint(Origin.of(player), build.range());
        final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
        final double spread = Math.max(1.0, params.search());
        final int have = corpses.near(player.getWorld().getUID(), center.getX(), center.getZ(), spread + 1, now).size();
        final int make = Math.max(0, Math.min(params.targets(), DESECRATE_PILE - have));
        final double life = plugin.monsters().config().lifeAt(Math.max(1, build.level()))
                * params.percent().at(build.level()) / 100.0;
        for (int i = 0; i < make; i++) {
            final double angle = Math.PI * 2 * i / make + Math.random() * 0.6;
            final double r = make == 1 ? 0 : spread * (0.5 + Math.random() * 0.5);
            final Location at = groundOf(center.clone().add(Math.cos(angle) * r, 0, Math.sin(angle) * r));
            corpses.add(player.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), life, now);
            Fx.as(player.getUniqueId(), () -> {
                final Location fx = at.clone().add(0, 0.2, 0);
                Fx.spawn(fx.getWorld(), org.bukkit.Particle.SOUL, fx, 10, 0.35, 0.1, 0.35, 0.02);
                Fx.spawn(fx.getWorld(), org.bukkit.Particle.BLOCK, fx, 16, 0.3, 0.05, 0.3, 0.0,
                        org.bukkit.Material.SOUL_SOIL.createBlockData());
                Fx.spawn(fx.getWorld(), org.bukkit.Particle.SMOKE, fx, 6, 0.3, 0.2, 0.3, 0.01);
            });
        }
        Fx.as(player.getUniqueId(), () -> Fx.sound(center, org.bukkit.Sound.BLOCK_SOUL_SOIL_BREAK, org.bukkit.SoundCategory.PLAYERS, 0.9f, 0.6f));
        return make;
    }

    /** 往下找地面（最多 6 格），找不到就原地。 */
    private static Location groundOf(final Location at) {
        final Location probe = at.clone().add(0, 1.5, 0);
        for (int i = 0; i < 8; i++) {
            if (!probe.getBlock().isPassable()) {
                return probe.add(0, 1, 0).toBlockLocation().add(0.5, 0, 0.5);
            }
            probe.add(0, -1, 0);
        }
        return at;
    }

    private boolean usesCorpses(final PlayerState state) {
        for (int position = 0; position < SkillLoadout.MAX_SKILLS; position++) {
            final SkillLoadout.Entry entry = state.skills().skill(position);
            final SkillDefinition definition = entry == null ? null : config.skill(entry.skillId());
            if (definition != null && (definition.mechanic() == Mechanic.CORPSE || (definition.summon() != null && definition.summon().corpse()))) {
                return true;
            }
        }
        return false;
    }

    /** 印記：過期的清掉，還在的在目標頭上畫一圈。 */
    private void tickDebuffs(final long now) {
        final tw.exilecore.combat.Debuffs debuffs = plugin.combat().debuffs();
        debuffs.expire(now);
        plugin.combat().corpses().expire(now);
        for (final UUID uuid : debuffs.targets()) {
            final Entity entity = plugin.getServer().getEntity(uuid);
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                visuals.markTick(living);
            } else {
                debuffs.forget(uuid);
            }
        }
    }

    private void tickPlayer(final Player player, final SkillRuntime runtime, final long now) {
        final java.util.UUID previousFx = Fx.source(player.getUniqueId()); // 2026-10：隱藏別人的技能特效
        try {
            tickPlayerInner(player, runtime, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void tickPlayerInner(final Player player, final SkillRuntime runtime, final long now) {
        signatures.tick(player, runtime, now); // 2026-10：蓄力放開、旋風、插地的矛、火星
        spears().tick(player, now); // 2026-10-27 照 PoE2：插著的炎爆矛時間到炸開
        executor.tickDecompose(player, now); // 2026-10-28 照 PoE2：腐屍爆的毒雲（中毒、碰到燃燒的東西炸開）
        metas.cadence(player, now); // 2026-10-26 照 PoE2 基石：祭祀終章（祈喚技能每 2 秒觸發）
        if (runtime.leap != null) {
            updateLeap(player, runtime, now);
            return;
        }
        if (runtime.echo != null && now >= runtime.echo.atTick()) {
            final SkillRuntime.Echo echo = runtime.echo;
            runtime.echo = null;
            if (eligible(player) && !inTown(player)) {
                final LivingEntity target = echo.target() == null ? null : living(player, echo.target());
                executor.executeFrom(Origin.of(player), echo.build(), target);
                visuals.echo(player.getLocation());
                cascadeFinalEcho(player, echo.build(), target); // 2026-10-27 照 PoE2 神幻雪崩
            }
        }
        if (runtime.repeat != null && now + 1e-9 >= runtime.repeat.atTick()) {
            repeatStrike(player, runtime, now);
        }
        if (runtime.queuedKey < 0) {
            return;
        }
        if (now > runtime.queuedUntil || !eligible(player)) {
            runtime.clearQueue();
            return;
        }
        if (now + 1e-9 >= runtime.readyAt) {
            final int key = runtime.queuedKey;
            final LivingEntity target = runtime.queuedTarget == null ? null : living(player, runtime.queuedTarget);
            runtime.clearQueue();
            runtime.fromQueue = true;
            try {
                perform(player, runtime, key, target, now);
            } finally {
                runtime.fromQueue = false;
            }
        }
    }

    /**
     * 複擊：再打一下（不花魔力，照原本的目標；目標死了就打準心或前方的）；瞬影擊是再閃一次。
     * 死掉、凍結、回到營地、換成用不了這招的武器、瞬影擊附近沒有敵人了：剩下的不打了，也不用等它們打完。
     * 有冷卻的（瞬影擊）全部打完才開始冷卻（2026-10：閃三次再進冷卻）。
     */
    private void repeatStrike(final Player player, final SkillRuntime runtime, final long now) {
        final java.util.UUID previousFx = Fx.source(player.getUniqueId()); // 2026-10：隱藏別人的技能特效
        try {
            repeatStrikeInner(player, runtime, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void repeatStrikeInner(final Player player, final SkillRuntime runtime, final long now) {
        final SkillRuntime.Repeat repeat = runtime.repeat;
        // 2026-10-28 照 PoE2（三連刺的第六刺）：連刺的最後一下用自己那一段的傷害表、距離（SkillStages 的 last）；這次之前每一下都一樣
        final SkillBuild build = repeat.left() == 1 ? SkillStages.apply(repeat.build(), SkillStages.LAST) : repeat.build();
        final SkillDefinition definition = build.definition();
        boolean able = eligible(player) && !inTown(player)
                && (plugin.ailments() == null || !plugin.ailments().isIncapacitated(player))
                && definition.usableWith(plugin.players().get(player).weaponTags());
        if (able && KillingPalm.is(build) && executor.palmChaining(player, now)) {
            // 2026-10-28 照 PoE2 斷魂掌：連衝還沒打完，複擊的那一下等連衝打完（每 tick 再看一次）
            runtime.repeat = new SkillRuntime.Repeat(now + 1, repeat.gapTicks(), repeat.left(), repeat.build(), repeat.target());
            runtime.readyAt = Math.max(runtime.readyAt, runtime.repeat.doneAt());
            return;
        }
        if (able) {
            runtime.repeat = repeat.next();
            final LivingEntity target = repeat.target() == null ? null : living(player, repeat.target());
            ServerSwing.mainHand(player);
            if (definition.mechanic() == Mechanic.FLICKER) {
                able = executor.flickerAgain(player, build, target);
            } else if (KillingPalm.is(build)) {
                executor.palmAgain(player, runtime, build, target); // 2026-10-28 照 PoE2：複擊的斷魂掌照樣衝過去、撲殺就連衝；這次之前原地打
            } else {
                if (repeat.left() == 1) {
                    stageEffects().armLast(player, repeat.build()); // 2026-10-28 照 PoE2：三連刺最後一刺插矛頭
                }
                executor.executeFrom(Origin.of(player), build, target);
            }
        }
        if (!able) {
            runtime.repeat = null;
            runtime.readyAt = Math.min(runtime.readyAt, now);
        }
        if (runtime.repeat == null) {
            runtime.startCooldown(definition.id(), now, build.cooldownTicks()); // 打完（或斷掉）才開始冷卻；沒有冷卻的不會記
            showCooldown(player, definition.id(), build.cooldownTicks());
        }
    }

    /** 排隊時記下的目標：還活著、跟玩家在同一個世界才算。 */
    private LivingEntity living(final Player player, final UUID uuid) {
        final Entity entity = plugin.getServer().getEntity(uuid);
        return entity instanceof LivingEntity living && living.isValid() && living.getWorld().equals(player.getWorld()) ? living : null;
    }

    private void updateLeap(final Player player, final SkillRuntime runtime, final long now) {
        final SkillRuntime.Leap leap = runtime.leap;
        final long elapsed = now - leap.startTick();
        final boolean landed = elapsed >= 3 && grounded(player);
        if (landed || elapsed >= leap.ticks() + 8 || !eligible(player)) {
            runtime.leap = null;
            runtime.landedAt = now;
            if (eligible(player)) {
                executor.land(player, leap);
                procs().onUse(player, leap.build().definition(), player.getLocation()); // 2026-10：躍電這類「放技能時」的輔助在落地點觸發
            }
        }
    }

    /**
     * 腳底四個角有沒有踩到會擋路的方塊（伺服器自己判斷，不信任客戶端的 onGround）。
     * 取樣點往內縮一點點，貼著牆站時才不會把牆當成地面。
     */
    static boolean grounded(final Player player) {
        final Location at = player.getLocation();
        final World world = player.getWorld();
        final int y = (int) Math.floor(at.getY() - 0.05);
        for (final double dx : new double[] {-0.299, 0.299}) {
            for (final double dz : new double[] {-0.299, 0.299}) {
                if (!world.getBlockAt((int) Math.floor(at.getX() + dx), y, (int) Math.floor(at.getZ() + dz)).isPassable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 剛跳完（落地後 1 秒內）也不算摔落傷害。 */
    boolean recentlyLeapt(final Player player) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        return runtime != null && (runtime.leap != null || now() - runtime.landedAt < 20);
    }

    /** 真正放一次。 */
    void perform(final Player player, final SkillRuntime runtime, final int key, final LivingEntity target, final long now) {
        final java.util.UUID previousFx = Fx.source(player.getUniqueId()); // 2026-10：隱藏別人的技能特效
        try {
            performInner(player, runtime, key, target, now);
        } finally {
            Fx.restore(previousFx);
        }
    }

    private void performInner(final Player player, final SkillRuntime runtime, final int key, final LivingEntity target, final long now) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player); // 剛換武器時先重算，普攻與標籤才會跟著新武器
        final LivingEntity preferred = target != null && target.getWorld().equals(player.getWorld()) ? target : null;
        final Resolved pressed = resolve(state, key);
        SkillBuild build = build(state, sheet, pressed).rollElement(sheet, plugin.items().random());
        final SkillDefinition definition = build.definition();
        if (runtime.charging != null && !runtime.charging.skillId.equals(definition.id())) {
            runtime.charging = null; // 蓄力中按了別的技能：這次蓄力取消
        }
        if (inTown(player) && definition.mechanic() != Mechanic.AURA) {
            fail(player, runtime, state, "營地裡不能戰鬥", now); // 光環在營地也能開關
            return;
        }
        if (plugin.ailments() != null && plugin.ailments().isIncapacitated(player)) {
            fail(player, runtime, state, "凍結中，不能施放", now);
            return;
        }
        if (!definition.usableWith(state.weaponTags())) {
            fail(player, runtime, state, weaponProblem(player, state, definition), now);
            return;
        }
        final String gemProblem = GemAttributes.problem(config, pressed.entry(), sheet); // 2026-10-26 照 PoE2：屬性需求不足的技能寶石不能用
        if (gemProblem != null) {
            fail(player, runtime, state, ItemRenderer.escape(definition.name()) + "：" + ItemRenderer.escape(gemProblem), now);
            return;
        }
        // 2026-10-28 照 PoE2：保留精魂的主動技能（戰旗、掠閃、喚駒）要保留得到精魂才能用（綁在按鍵格上照按鍵順序保留，跟光環一起算）；
        //   這次之前不保留精魂（掠閃每次花 [4, 8] 魔力）
        if (definition.reservesSpiritOnly() && !pressed.isDefault() && !auras.isActive(player.getUniqueId(), definition.id())) {
            fail(player, runtime, state, String.format(Locale.ROOT, "%s：精魂不夠，要保留 %.0f 精魂才能用（已經保留 %.0f／%.0f）",
                    ItemRenderer.escape(definition.name()), build.reserve(),
                    auras.totalReserve(player.getUniqueId()) + auras.otherReserve(player.getUniqueId()), // 2026-10-28：含先祖魂約的圖騰
                    AuraService.capacity(sheet)), now);
            return;
        }
        // 2026-10-28 照 PoE2：同一個技能可以放好幾顆：主要寶石孔裡有一顆（自動觸發），面板上這顆照樣能自己按（用這顆自己的輔助）。
        //   這次之前（2026-10-23）放在宿主孔裡的技能自己不能再放
        // 2026-10-27 照 PoE2 雙持（巨人之血兩手各拿一把雙手武器）：攻擊輪流用主手、副手的武器（傷害、攻速、暴擊照出手的那一把）
        final tw.exilecore.character.DualWield.OffHand offHand = state.offHandAttack();
        // 2026-10-27：這裡只看輪到哪一手，真的出手（付了消耗）才換手（下面 flipHand）；這次之前按下就換，失敗的也換
        final boolean alternating = tw.exilecore.character.DualWield.usable(offHand, definition.isAttack(),
                offHand != null && definition.usableWith(offHand.tags()));
        if (alternating && state.peekHandIsOff()) {
            build = build.withProfile(tw.exilecore.character.DualWield.offHand(build.profile(), offHand));
        }
        // 英靈圖騰（2026-10 PoE2）：面板上下一格要放錘子攻擊技能，英靈用它打
        SkillBuild linked = null;
        List<SkillBuild> moreLinked = List.of();
        if (SkillExecutor.hasFlag(definition, StatKeys.FLAG_TOTEM_LINKED)) {
            final Object found = linkedMace(state, sheet, definition);
            if (found instanceof String message) {
                fail(player, runtime, state, message, now);
                return;
            }
            linked = (SkillBuild) found;
            // 2026-10-27 照 PoE2：孔裡其他能用的錘子技能，英靈照孔的順序輪流用（這次之前只用第 1 孔）
            final List<SkillBuild> all = linkedMaces(state, sheet, definition);
            moreLinked = all.size() > 1 ? all.subList(1, all.size()) : List.of();
        }
        // 2026-10-26：連續出手的引導（熾息）每一下：冷卻在開始引導時已經算了，這裡不看也不重算
        final boolean streaming = runtime.charging != null && runtime.charging.skillId.equals(definition.id())
                && runtime.releasing != null && runtime.releasing.skillId().equals(definition.id());
        final long cooldownLeft = streaming ? 0 : runtime.cooldownLeft(definition.id(), now);
        final String bypass = definition.cooldownBypass();
        // 2026-10-26（怒嘯，PoE2 Enraged Warcry）：戰吼改花 x 點怒氣跳過冷卻，而不是一顆磐星
        final int rageBypass = definition.mechanic() == Mechanic.WARCRY && bypass != null
                ? (int) Math.round(build.profile().effective(sheet).flat(tw.exilecore.stats.SupportKeys.WARCRY_RAGE_BYPASS, build.profile().tags())) : 0;
        final boolean bypassing = rageBypass > 0 ? cooldownLeft > 0 && resources.rage(player) >= rageBypass
                : SkillMath.bypassCooldown(cooldownLeft, bypass, chargesHeld(player, bypass));
        if (cooldownLeft > 0 && !bypassing) {
            // 冷卻中：只提示、不響（按住按鍵時每 tick 都會來）；可以用戰星跳過的技能說一下沒有戰星
            state.notice(String.format(Locale.ROOT, "<gray>%s 冷卻中（%.1f 秒%s）", ItemRenderer.escape(definition.name()), cooldownLeft / 20.0,
                    rageBypass > 0 ? "，怒氣不到 " + rageBypass + " 點"
                    : bypass == null ? "" : "，沒有" + ItemRenderer.escape(chargeName(bypass)) + "可以消耗"), now + 10);
            plugin.players().display(player, state);
            return;
        }
        // 2026-10-26（PoE2）：要榮耀才能用的技能（熔魂嘯、澤嘯）、要先移動 10 格才能用的戰吼（震嘯）
        final String usage = supportMechanics().usageProblem(player, build);
        if (usage != null) {
            state.notice("<gray>" + usage, now + 10);
            plugin.players().display(player, state);
            return;
        }
        // 2026-10-27 照 PoE2 Ferocious Roar：第 1 孔放著人形戰吼 → 改成觸發那個戰吼（它自己的冷卻、榮耀、武器、盾的要求照樣看）
        final SkillBuild socketedCry = SocketedWarcry.hosts(definition) && pressed.entry() != null && pressed.entry().inner() != null
                ? socketedWarcry(state, sheet, pressed.entry(), build.level()) : null;
        if (SocketedWarcry.hosts(definition) && pressed.entry() != null && pressed.entry().inner() != null) {
            final String cryProblem = socketedCryProblem(player, runtime, state, pressed.entry().inner().skillId(), socketedCry, now);
            if (cryProblem != null) {
                state.notice("<gray>" + ItemRenderer.escape(definition.name()) + "：" + cryProblem, now + 10);
                plugin.players().display(player, state);
                return;
            }
        }
        // 2026-10-26 照 PoE2：要連擊才能用的技能（驚雷之鐘 4 點：用其他長棍技能打擊敵人累積）；這次之前用冷卻代替
        // 2026-10-27 照 PoE2 Delayed Gratification：連擊也是使用條件（更難 150%；達成後 10 秒內多一次不用連擊）
        if ("ancestral_spirits".equals(definition.id())) {
            // 2026-10-29 照 PoE2 Ancestral Spirits：召喚圖騰時自動觸發（面板上可以插輔助），不能自己按
            state.notice("<gray>先祖之魂：放下圖騰時自動觸發，不用按", now + 30);
            plugin.players().display(player, state);
            return;
        }
        final int comboNeeded = supportMechanics().comboRequired(player, build, config.resource(definition.id()).combo());
        if (comboNeeded > 0 && resources.combo(player) < comboNeeded) {
            state.notice(String.format(Locale.ROOT, "<gray>%s 要 %d 點連擊（現在 %d；用打擊打中敵人累積）", ItemRenderer.escape(definition.name()),
                    comboNeeded, resources.combo(player)), now + 10);
            plugin.players().display(player, state);
            return;
        }
        // 2026-10：弩的彈匣：裝填中不能放
        if (ammoSkill(definition)) {
            final Long done = runtime.reloadDone.get(definition.id());
            if (done != null && now < done) {
                state.notice(String.format(Locale.ROOT, "<gray>%s 裝填中（%.1f 秒）", ItemRenderer.escape(definition.name()), (done - now) / 20.0), now + 10);
                plugin.players().display(player, state);
                return;
            }
            if (done != null) {
                runtime.reloadDone.remove(definition.id());
                runtime.ammo.remove(definition.id());
                supportMechanics().extras().boltsReloaded(player, definition.id(), clipSize(config, build, sheet), done); // 2026-10-27 照 PoE2 Fresh Clip
                state.markRecent(tw.exilecore.item.unique.UniqueStats.RECENT_RELOAD, done); // 2026-10-26：「最近裝填過」（破城機弩）
            }
        }
        // 2026-10-28 照 PoE2 Hollow Form：空無型態按住引導，照孔裡近戰攻擊的攻擊時間 ÷ 0.7 一直顯現幻影（HollowImages）；
        //   這次之前是一按一次的戰吼型技能（AscPaths：幻影用你最近使用的近戰攻擊）
        if (HollowForm.hosts(definition)) {
            hollowForm(player, runtime, state, sheet, key, build, preferred, now);
            return;
        }
        final MetaSpec metaSpec = pressed.entry() == null ? null : config.meta(definition.id());
        if (metaSpec != null && metaSpec.totem()) {
            spellTotem(player, runtime, state, sheet, key, pressed.entry(), build, metaSpec, now); // 2026-10：法術圖騰（PoE2 主要寶石）
            return;
        }
        final String problem = build.deploy() != null ? deployables.precheck(player, build)
                : originals().handles(config.signature(definition.id())) ? originals().problem(player, build, preferred) // 2026-10-26 照 PoE2
                : definition.mechanic() == Mechanic.SUMMON ? minions.precheck(player, build)
                : definition.mechanic() == Mechanic.RALLY ? (minions.count(player.getUniqueId()) == 0 ? "你現在沒有召喚物" : null)
                : definition.mechanic() == Mechanic.GUARD ? (definition.weapons().isEmpty() && !ManaTempest.is(definition) ? guardProblem(state, definition) : null) // 2026-10：限定武器的增益技能（霜鏃、急裝）不用盾；2026-10-26：魔湧不用盾
                : Warcries.needsShield(definition) ? armouredShieldProblem(state, definition) // 2026-10-27 照 PoE2：堅壁戰吼要護甲盾
                : definition.warcry() != null && definition.warcry().spike() ? warcries.spikeProblem(player) // 2026-10：祭柱要屍體或召喚物；2026-10-30 照 PoE2：要骷髏
                : config.mount(definition.id()) != null ? null // 2026-10：坐騎技能（叫坐騎／下馬）
                : config.command(definition.id()) != null ? (minions.commandable(player, config.command(definition.id()), 1, false).isEmpty()
                        ? "附近沒有聽這個指令的召喚物" : null) // 2026-10：指令型召喚物技能
                : MinionAbilities.MANIFEST.equals(definition.id()) && !auras.isActive(player.getUniqueId(), definition.id())
                        && minions.manifestForm(player) == null
                        ? "幻化武器要主手拿近戰武鬥武器（劍、斧、錘、矛、長棍、爪、匕首、鏈錘、獸魂符）" // 2026-10-29 照 PoE2
                : executor.precheck(player, build, preferred);
        // 2026-10-26（血偶巨鎚）：不能使用戰吼
        final String noWarcry = definition.mechanic() == Mechanic.WARCRY ? tw.exilecore.item.unique.UniqueCosts.warcryProblem(state.sheet()) : null;
        if (problem != null || noWarcry != null) {
            fail(player, runtime, state, problem != null ? problem : noWarcry, now);
            return;
        }
        // 2026-10 蓄力放開（PoE2 的穿雲箭、蓄勁一擊、炎核爆）：按下去先蓄力，放開（Signatures 偵測到）才真的出手
        final ChargeSpec chargeSpec = ChargeSpec.withQuality(config.charge(definition.id()), build); // 2026-10-28 照 PoE2：品質的階段、不耗怒氣、衝刺距離
        final SkillRuntime.Release release = chargeSpec != null && runtime.releasing != null
                && runtime.releasing.skillId().equals(definition.id()) ? runtime.releasing : null;
        runtime.releasing = null;
        if (chargeSpec != null && release == null) {
            if (signatures.press(player, runtime, state, key, definition, build, chargeSpec, preferred, sheet, now)) {
                // 2026-10-26 照 PoE2 的引導：開始時先扣一次（逐秒扣的先扣一秒）、衝鋒與持續作用記下技能
                final String unpaid = channelling.start(player, runtime, state, build, chargeSpec, now);
                if (unpaid != null) {
                    runtime.charging = null;
                    fail(player, runtime, state, unpaid, now);
                }
            }
            return;
        }
        final ResourceSpec resourceSpec = config.resource(definition.id());
        // 2026-10-26（PoE2）：弩技能的魔力是裝填時扣的：彈匣滿的時候（剛裝好）打第一發才扣，彈匣裡剩下的不扣
        int cost = ammoSkill(definition) && runtime.ammo.containsKey(definition.id()) ? 0 : build.manaCost();
        if (socketedCry != null) {
            cost += socketedCryCost(build, socketedCry); // 2026-10-27 照 PoE2：插槽戰吼的魔力 ×消耗倍率（熊咆 2 級起 200%）
        }
        if (release != null && (chargeSpec.drain() || chargeSpec.mode() != ChargeSpec.Mode.RELEASE)) {
            cost = 0; // 2026-10-26 照 PoE2：引導的魔力按住時逐秒扣過了（或開始時扣過一次），放開／連續出手不另外扣
        }
        if (streaming && runtime.charging.empowered != null && !runtime.charging.empowered.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(runtime.charging.empowered)); // 2026-10-26：熾息用掉能星的強化
        }
        // 2026-10-26（嘯準，PoE2 Unerring Power）：這一下會被戰吼強化時，消耗更少 30%
        if (cost > 0 && definition.isAttack() && StatKeys.flag(build.profile().effective(sheet), tw.exilecore.stats.SupportKeys.FLAG_UNERRING)
                && willEmpower(runtime, definition, now)) {
            cost = (int) Math.round(cost * 0.7);
        }
        // 2026-10-26（昇華）：火祭協議（消耗改成獲得煉獄之炎）、晶化護符、鑲嵌的寶石（藍）、狂怒源泉（+5 怒氣消耗）
        final tw.exilecore.ascendancy.AscendancyEffects.Cost ascCost = tw.exilecore.ascendancy.AscendancyEffects.cost(player, state, definition, cost);
        if (ascCost.problem() != null) {
            fail(player, runtime, state, ascCost.problem(), now);
            return;
        }
        cost = ascCost.mana();
        // 血魔法（2026-10 PoE2 基石）：沒有魔力，技能的消耗改扣生命
        final boolean bloodMagic = StatKeys.flag(state.sheet(), StatKeys.FLAG_BLOOD_MAGIC)
                || StatKeys.flag(build.profile().effective(state.sheet()), StatKeys.FLAG_BLOOD_MAGIC); // 2026-10：血付（輔助）只對被輔助的技能
        // 2026-10-26 照 PoE2 天賦樹：「x% 的技能魔力消耗改成生命消耗」「增加技能的生命消耗」
        final int[] split = lifeCostSplit(cost, bloodMagic, build.profile().effective(state.sheet()), build.profile().tags());
        // 2026-10-26（恍惚之矛）：剩下的魔力消耗有一部分再改扣生命（血魔法已經全扣生命，不用再算）
        final int costToLife = bloodMagic ? 0 : (int) Math.round(Math.min(split[1], tw.exilecore.item.unique.UniqueCosts.lifeShare(state.sheet(), split[1])));
        final int lifeCost = split[0] + costToLife;
        final int manaCost = split[1] - costToLife;
        if (lifeCost > 0 && state.life() <= lifeCost) {
            fail(player, runtime, state, "生命不足（" + definition.name() + "要 " + lifeCost + " 生命）", now);
            return;
        }
        // 血祭術（血術士，PoE2）：技能另外扣同樣多的生命
        final boolean sanguimancy = !bloodMagic && StatKeys.flag(state.sheet(), StatKeys.FLAG_SANGUIMANCY);
        // 2026-10-27 照 PoE2：血祭術的生命消耗不吃魔力消耗效率、吃生命消耗效率；這次之前直接等於魔力消耗
        final int sanguineCost = sanguimancy && cost > 0 ? (int) Math.round(cost
                * tw.exilecore.stats.CostKeys.manaToLife(build.profile().effective(state.sheet()), build.profile().tags())) : 0;
        if (cost > 0 && sanguimancy && state.life() <= sanguineCost) {
            fail(player, runtime, state, "生命不足（血祭術：" + definition.name() + "要 " + sanguineCost + " 生命）", now);
            return;
        }
        if (manaCost > 0 && state.mana() + 1e-9 < manaCost && !supportMechanics().extras().freeMana(player, build)) { // 2026-10-27 照 PoE2 Defy／Desperation
            fail(player, runtime, state, "魔力不足（" + definition.name() + "要 " + manaCost + "）", now);
            return;
        }
        // 2026-10-26（PoE2 的英靈圖騰）：放的時候要消耗幾顆戰星，不夠就不能放
        // 2026-10-26 照 PoE2 先祖魂約：放圖騰不需要充能（有的話照樣消耗）；這次之前先祖魂約是圖騰技能不花魔力
        final boolean bondTotem = build.deploy() != null && build.deploy().kind() == DeploySpec.Kind.TOTEM
                && StatKeys.flag(build.profile().effective(sheet), StatKeys.FLAG_ANCESTRAL_BOND);
        final SkillConfig.ChargeCost chargeCost = config.chargeCost(definition.id());
        if (chargeCost != null && !bondTotem && (plugin.combat() == null || plugin.combat().charges() == null
                || plugin.combat().charges().count(player, chargeCost.charge()) < chargeCost.count())) {
            fail(player, runtime, state, definition.name() + "要 " + chargeCost.count() + " 顆" + chargeName(chargeCost.charge()), now);
            return;
        }
        if (chargeCost != null && plugin.combat() != null && plugin.combat().charges() != null) {
            final int spend = bondTotem ? Math.min(chargeCost.count(), plugin.combat().charges().count(player, chargeCost.charge())) : chargeCost.count();
            for (int i = 0; i < spend; i++) {
                plugin.combat().charges().spend(player, chargeCost.charge(), build.profile().effective(sheet), build.profile().tags());
            }
        }
        if (bypassing && rageBypass > 0) {
            resources.spendRage(player, rageBypass); // 2026-10-26：怒嘯
        } else if (bypassing && plugin.combat().charges().spend(player, bypass, build.profile().effective(sheet), build.profile().tags()) <= 0) { // 2026-10-24：戰星輔助
            fail(player, runtime, state, definition.name() + "冷卻中，沒有" + chargeName(bypass) + "可以消耗", now); // 剛好過期了
            return;
        }
        if (alternating) {
            state.flipHand(); // 2026-10-27：消耗付了、真的出手：換另一手（蓄力是放開這次才走到這裡，只換一次）
        }
        supportMechanics().used(player, build); // 2026-10-26：用掉榮耀、記下移動距離（震嘯）
        if (socketedCry != null) {
            supportMechanics().used(player, socketedCry); // 2026-10-27：插槽戰吼要的榮耀（熔魂嘯）也用掉
        }
        if (comboNeeded > 0) {
            resources.spendCombo(player, comboNeeded); // 2026-10-26 照 PoE2：用掉連擊（驚雷之鐘）
        }
        if (cost > 0) {
            state.setLife(state.life() - lifeCost);
            state.setMana(Math.max(0, state.mana() - manaCost)); // 2026-10-27：被包圍時魔力不夠也能放（扣到 0）
            if (lifeCost > 0) {
                supportMechanics().lifeSpent(player, definition.id(), lifeCost); // 2026-10-26：澤嘯的榮耀（消耗轉生命的部分）
                tw.exilecore.passive.ConquestEffects.lifeSpent(player.getUniqueId(), lifeCost, now); // 2026-10-26：鮮血獻祭
            }
            if (sanguimancy) {
                state.setLife(state.life() - sanguineCost);
                supportMechanics().lifeSpent(player, definition.id(), sanguineCost); // 2026-10-26：澤嘯的榮耀
            }
            tw.exilecore.item.unique.UniqueCosts.afterManaPaid(state, state.sheet(), manaCost); // 2026-10-26（寂心）
            manaTempest.spent(player, lifeCost + manaCost + (sanguimancy ? sanguineCost : 0)); // 2026-10-26 照 PoE2：魔湧裡花掉的魔力、生命加到每秒消耗
            tw.exilecore.ascendancy.AscendancyEffects.lifeSpent(player, lifeCost + (sanguimancy ? sanguineCost : 0)); // 2026-10-26（昇華・血紅之潮）
            plugin.players().display(player, state);
        }
        tw.exilecore.ascendancy.AscendancyEffects.paid(player, state, ascCost); // 2026-10-26（昇華）：煉獄之炎、怒氣消耗
        tw.exilecore.item.unique.UniqueCosts.onCast(state, state.sheet(), definition.tags()); // 2026-10-26（念珠魔杖）：施放法術時偷取生命
        // 2026-10-26 照 PoE2 天賦樹：「最近施放過法術」「最近攻擊過」的條件
        if (definition.isAttack()) {
            state.markRecent(tw.exilecore.stats.KeystoneKeys.RECENT_ATTACK, now);
            if (build.deploy() == null && definition.tags().contains(tw.exilecore.stats.Tags.PROJECTILE)) {
                deployables.firedAttackProjectile(player); // 2026-10-26 照 PoE2「砲彈準備好了！」：圖騰只在你發射攻擊投射物時用技能
            }
        } else if (definition.tags().contains(tw.exilecore.stats.Tags.SPELL)) {
            state.markRecent(tw.exilecore.stats.KeystoneKeys.RECENT_SPELL, now);
            leftovers().spellCast(player, definition); // 2026-10-28 照 PoE2 Orb of Storms：在雷雲旁邊施法多放一道雷
        }
        // 2026-10-26（捐命，PoE2 Sacrificial Offering）：放的時候獻祭 x% 最大生命
        supportMechanics().sacrifice(player, build, state);
        if (treeMechanics != null) {
            treeMechanics.onUse(player, build, cost > 0 ? manaCost : 0, comboNeeded); // 2026-10-26 照 PoE2 天賦：統治者、傀儡大師、秘術增強、圖騰…
        }
        if (definition.mechanic() != Mechanic.AURA) {
            plugin.players().grace().end(player, true); // 出手了：入場保護結束（光環只是開關，不算）
        }
        // 2026-10-24：放的時候消耗戰星的輔助（狂速、久咒、碎地；照 PoE2）
        final ChargeEffects.OnUse onUse = chargeEffects().onUse(player, build, sheet);
        build = originals().beforeCast(player, onUse.build(), preferred, now); // 2026-10-26 照 PoE2：熱度、崩滅心咒、霜鏃、帶電油彈、噬咬的跳躍
        build = stageAttacks().beforeCast(player, build, preferred, now); // 2026-10-28 照 PoE2：攻擊的第二段（狂怒、第三擊／終結、帶電、箭雨、毒花）
        // 2026-10-26（昇華）：先祖強化、力量的代價、異能賦予、順風、回響與重複、猛擊的餘震…（AscendancyEffects）
        build = tw.exilecore.ascendancy.AscendancyEffects.beforeCast(player, state, build, preferred);
        build = supportMechanics().lineage().prepare(player, build); // 2026-10-27 照 PoE2：血脈輔助（血滲、狂伐、傲寒、積勢、雪封）
        // 冰緩讓攻擊與施法變慢（動作速度 0.7 = 間隔 ÷ 0.7）
        final double speed = plugin.ailments() == null ? 1.0 : Math.max(0.05, plugin.ailments().actionSpeed(player));
        final double interval = build.intervalTicks(sheet) / speed / onUse.speedMore();
        // 2026-10-28 照 PoE2 Furious Slam、Rolling Slam 的品質：使用這個技能的動作期間（一次攻擊時間）暈眩門檻增加 x%
        final double stunWhileUsing = build.qualityFlat(tw.exilecore.stats.GemQualityKeys.STUN_THRESHOLD_WHILE_USING);
        if (stunWhileUsing > 0) {
            buff(player, ACTION_STUN_BUFF, definition.name(), (int) Math.max(1, Math.round(interval)),
                    List.of(tw.exilecore.stats.Modifier.increased(StatKeys.STUN_THRESHOLD, stunWhileUsing / 100.0, definition.name())));
        }
        runtime.readyAt = SkillMath.nextReady(runtime.readyAt, now, release != null && chargeSpec.stages() <= 0 ? interval * RELEASE_RECOVERY : interval);
        // 2026-10-27 照站長「放技能減速全部移除」：放技能不再變慢（減少懲罰的效果改成平衡等效的移動速度）；
        // 這次之前（2026-10-26 照 PoE2）放技能的那段時間移動速度更少 70%（SkillMovePenalty.apply）
        if ((key != SkillLoadout.KEY_LEFT && key != SkillLoadout.KEY_SNEAK_LEFT) || runtime.fromQueue) {
            ServerSwing.mainHand(player); // 左鍵客戶端自己會揮手；右鍵、數字鍵要伺服器揮；排隊放出來的左鍵也要（2026-10-17：揮手時機對上真的出手）
        }
        if (!streaming) {
            runtime.startCooldown(definition.id(), now, build.cooldownTicks());
            showCooldown(player, definition.id(), build.cooldownTicks());
        }
        // 2026-10-28 照 PoE2（melee_conditional_step_distance，MeleeStep）：近戰攻擊出手前往前踏一步（目標在打得到的距離外、或朝準心的地面），
        //   踏完才打、才捲旋風、才砸鐘；被定住不動。這次之前都在原地打
        final MeleeStep.Spec step = config.step(definition.id());
        if (step != null && !supportMechanics().immobilised(player)) {
            if (mounts().mounted(player) && config.mount(definition.id()) == null && !Mounts.usableWhileMounted(definition)) {
                mounts().dismount(player); // 近戰技能本來就會下馬（下面那段）；要先下馬才踏得出去
            }
            executor.meleeStep(player, build, step, preferred);
        }
        if (build.deploy() != null) {
            if (linked != null) {
                deployables.place(player, build, List.of(), linked, moreLinked, 0); // 英靈（2026-10-20）：照英靈圖騰的 interval ÷ 攻速叫（SkillDeployables.totemTicks）
            } else {
                deployables.place(player, build); // 圖騰、陷阱、鐘：放下去，由它們放技能
            }
            return;
        }
        if (definition.mechanic() == Mechanic.SUMMON) {
            minions.summon(player, build); // 召喚物：召出來，由它們自己打
            return;
        }
        if (definition.mechanic() == Mechanic.DESECRATE) {
            final int made = desecrate(player, build, now); // 褻瀆：準心的地方生出屍體
            visuals.cast(definition.visual(), player);
            state.notice("<#9fd36b>掘墳：拖出 " + made + " 具屍體", now + 40);
            plugin.players().display(player, state);
            return;
        }
        if (definition.mechanic() == Mechanic.RALLY) {
            final int count = minions.rally(player); // 號召：召喚物全部叫回腳邊
            visuals.cast(definition.visual(), player);
            state.notice("<#c7a6ff>號召：" + count + " 隻召喚物回到身邊", now + 40);
            plugin.players().display(player, state);
            return;
        }
        if (originals().handles(config.signature(definition.id()))) {
            originals().cast(player, build, preferred, now); // 2026-10-26 照 PoE2：整個交給 OriginalSkills（雷縛長棍、崩滅心咒、雷流場、燼種、墜星熊吼）
            plugin.players().display(player, state);
            return;
        }
        if (definition.mechanic() == Mechanic.AURA && auras.isActive(player.getUniqueId(), definition.id())) {
            final String released = storedReleases().press(player, build); // 2026-10-27 照 PoE2：鐵壁守、瘟囊爆放出存的傷害
            if (released != null) {
                state.notice(released, now + 40);
                plugin.players().display(player, state);
                return;
            }
            final String granted = originals().auraPress(player, build, now); // 2026-10-26 照 PoE2：回盾術、骸餘饗賦予的法術
            if (granted != null) {
                state.notice(granted, now + 40);
                plugin.players().display(player, state);
                return;
            }
            final String said = minionPress(player, build, preferred); // 2026-10-26：精魂召喚的鍵 = 下指令
            if (said != null) {
                state.notice(said, now + 40);
                plugin.players().display(player, state);
                return;
            }
        }
        if (definition.mechanic() == Mechanic.AURA && config.meta(definition.id()) instanceof MetaSpec invocation && invocation.invocation()
                && auras.isActive(player.getUniqueId(), definition.id())) {
            // 2026-10-26 照 PoE2 Invocation（祈喚）：按下去花掉存的能量觸發插槽法術
            final String said = metas().invoke(player, state.skills().find(definition.id()), preferred);
            if (said != null) {
                state.notice(said, now + 40);
                plugin.players().display(player, state);
            }
            return;
        }
        if (definition.mechanic() == Mechanic.AURA) {
            // 2026-10：光環放在技能面板上就一直開著，按鍵不用開關（魔力不扣回來：這一下也沒花魔力，光環的 mana 是 0）
            final String socketProblem = config.meta(definition.id()) == null
                    // 2026-10-29 照 PoE2 Blasphemy：咒環孔裡沒有能用的詛咒，按下去說清楚
                    ? (Blasphemy.hosts(definition) && blasphemyCurses(player).isEmpty()
                    ? definition.name() + "：孔裡要放" + Blasphemy.wants() + "的技能寶石（/skills 點它，拿技能寶石點一個孔），沒放就沒有效果" : null)
                    : metas().socketProblem(state, state.skills().find(definition.id()));
            state.notice(socketProblem != null ? "<red>" + ItemRenderer.escape(socketProblem) // 2026-10：主要寶石插槽放錯，按下去說清楚
                    : auras.isActive(player.getUniqueId(), definition.id())
                    ? "<#8fd3ff>✦ " + ItemRenderer.escape(definition.name()) + " 一直開著（綁在按鍵格上就常駐有效，不用按）"
                    : "<red>" + ItemRenderer.escape(definition.name()) + " 沒開：精魂不夠（按鍵排前面的先開）", now + 60); // 2026-10-28：這次之前誤寫成「保留的魔力」
            plugin.players().display(player, state);
            return;
        }
        if (definition.mechanic() == Mechanic.GUARD && ManaTempest.is(definition)) {
            manaTempest.cast(player, runtime, build, now); // 2026-10-26 照 PoE2：魔湧召出風暴（這次之前是常駐光環）
            visuals.cast(definition.visual(), player);
            return;
        }
        if (definition.mechanic() == Mechanic.GUARD && config.barrage(definition.id()) != null) {
            // 連珠（2026-10）：備好，下一次弓／矛的投射物攻擊重複
            readyBarrage(player, runtime, state, build, config.barrage(definition.id()), now);
            return;
        }
        if (definition.mechanic() == Mechanic.GUARD) {
            if (SkillExecutor.hasFlag(definition, StatKeys.FLAG_RELOAD_ALL)) {
                emergencyReload(player, runtime, state, build, now); // 急裝：弩全部裝滿、強化每個彈匣的弩矢（2026-10-28 照 PoE2）
            } else {
                guard(player, build); // 舉盾：一段時間的增益
            }
            originals().afterCast(player, build, preferred, now); // 2026-10-26 照 PoE2：霜鏃強化接下來 4 次
            visuals.cast(definition.visual(), player);
            state.notice("<#c9d6e3>🛡 " + ItemRenderer.escape(definition.name()), now + 30);
            plugin.players().display(player, state);
            return;
        }
        if (socketedCry != null) {
            // 2026-10-27 照 PoE2 Ferocious Roar：「Triggers Socketed Warcry instead of Ferocious Roar's effects」——不變身、沒有熊咆自己的效果
            final SkillDefinition cry = socketedCry.definition();
            runtime.startCooldown(cry.id(), now, socketedCry.cooldownTicks());
            procs().onUse(player, cry, player.getLocation());
            final String notice = warcries.shout(player, runtime, socketedCry);
            plugin.players().conditions().onWarcry(player, cry.id(), now);
            state.notice("<#f5b942>" + ItemRenderer.escape(definition.name()) + "<gray> 觸發 " + (notice == null ? ItemRenderer.escape(cry.name()) : notice), now + 60);
            plugin.players().display(player, state);
            return;
        }
        if (definition.mechanic() == Mechanic.WARCRY) {
            procs().onUse(player, definition, player.getLocation()); // 2026-10：戰吼觸發的專屬技能在腳下
            final String notice = warcries.shout(player, runtime, build);
            shapeshift(player, definition); // 銀月庇（2026-10-15）：帶狼人標籤的戰吼吼完變狼人
            plugin.players().conditions().onWarcry(player, definition.id(), now); // 2026-10-26：天賦樹「最近每戰吼一次」
            if (notice != null) {
                state.notice(notice, now + 60);
                plugin.players().display(player, state);
            }
            return;
        }
        if (runtime.barrage != null && !runtime.barrage.active(now)) {
            runtime.barrage = null; // 連珠過期了
        }
        // 戰吼的強化：接下來幾次攻擊更痛
        if (runtime.exert != null) {
            if (!runtime.exert.active(now)) {
                runtime.exert = null;
            } else if (definition.isAttack()) {
                // 2026-10-26 照 PoE2：被強化的攻擊帶 empowered 標籤（天賦「強化攻擊…」吃得到）
                build = build.withProfile(build.profile().scaled(1 + runtime.exert.more() / 100.0, "戰吼強化")
                        .derive(build.profile().id(), Set.of(tw.exilecore.stats.TreeMechanicKeys.EMPOWERED), Set.of()));
                runtime.exert = runtime.exert.consume();
                plugin.players().get(player).markRecent(tw.exilecore.stats.TreeLastKeys.EVENT_EMPOWERED, now); // 2026-10-27：「強化攻擊中」
                visuals.exert(player);
            }
        }
        if (treeMechanics != null) {
            build = treeMechanics.beforeAttack(player, build); // 2026-10-26 照 PoE2 天賦：先祖強化、招架之後的攻擊、用掉連擊的傷害
        }
        // 2026-10-26 照 PoE2：各戰吼自己的強化（焚獄：額外火焰；震地：猛擊多一次餘震；寒夜嗥：附加冰冷；熊咆：破甲…）、
        // 用的元素（元湧）、累積的電荷（引雷）
        final SupportMechanics.Empowering empowering = supportMechanics().empower(player, runtime, build, now);
        build = supportMechanics().beforeUse(player, empowering.build());
        // 2026-10-28 照 PoE2：連段的那一下自己的攻擊速度（終結打擊更少 45%、第三擊更少 35%…；SkillStages）——出手時間照比例拉長
        final double stageTime = SkillStages.timeScale(build.profile());
        if (stageTime != 1.0) {
            runtime.readyAt += interval * (stageTime - 1);
        }
        if (ammoSkill(definition) && runtime.takeEmpoweredBolt(definition.id(), now)) {
            // 2026-10-28 照 PoE2 Emergency Reload：被急裝強化的弩矢更多 x% 傷害（每個彈匣各自的發數，用完或到期就沒了）
            build = build.withProfile(build.profile().withLocal(List.of(tw.exilecore.stats.Modifier.more(StatKeys.DAMAGE, runtime.empoweredMore, "急裝"))));
            if (runtime.empoweredBolts.isEmpty()) {
                buffs.remove(player, EMERGENCY_BUFF);
            }
        }
        // 2026-10：連段、榮耀、元素灌注拿掉了，以前要付了才有的加成（連段乘需要的段數、強化）一律算上；灌注的另外加成不算
        final List<tw.exilecore.stats.Modifier> resourceBonus = resourceBonus(resourceSpec);
        if (!resourceBonus.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(resourceBonus));
        }
        // 持續引導（2026-10）：連續放同一個技能（按住）每放一次加一層，每層更多傷害；停下來就歸零
        final StatSheet channelSheet = build.profile().effective(sheet);
        final int maxStages = (int) channelSheet.flat(StatKeys.CHANNEL_STAGES, Set.of());
        if (maxStages > 0) {
            final boolean continuing = definition.id().equals(runtime.channelSkill) && now - runtime.channelTick <= Math.max(16, interval + 12);
            runtime.channelStage = continuing ? Math.min(maxStages, runtime.channelStage + 1) : 1;
            if (!continuing) {
                runtime.channelSince = now; // 2026-10-27 漸熾：從這一輪開始算引導了幾秒
            }
            runtime.channelSkill = definition.id();
            runtime.channelTick = now;
            state.markRecent(tw.exilecore.stats.TreeConditions.CHANNEL_EVENT, now); // 2026-10-26：天賦樹「引導中」
            tw.exilecore.combat.KeystoneEffects.channelled(plugin, player, state, now); // 2026-10-26 天賦樹：引導 2 秒後解除一個詛咒
            final double more = channelSheet.flat(StatKeys.CHANNEL_MORE, Set.of()) * (runtime.channelStage - 1) / 100.0;
            if (more > 0) {
                build = build.withProfile(build.profile().withLocal(List.of(tw.exilecore.stats.Modifier.more(StatKeys.DAMAGE, more, "蓄力"))));
            }
            build = Burgeon.apply(build, sheet, (now - runtime.channelSince) / 20.0); // 2026-10-27 照 PoE2 Burgeon：引導越久越痛
            state.notice(String.format(Locale.ROOT, "<#ffb070>%s 蓄力 %d／%d", ItemRenderer.escape(definition.name()), runtime.channelStage, maxStages), now + 20);
        }
        // 2026-10：封印：放的時候把蓄好的封印全部解開
        int sealRepeats = 0;
        double sealRepeatScale = 0.5;
        {
            final StatSheet sealSheet = build.profile().effective(sheet);
            final Set<String> sealTags = build.profile().tags();
            final int sealMax = (int) Math.round(sealSheet.flat(StatKeys.SEAL_MAX, sealTags));
            if (sealMax > 0) {
                final double sealSeconds = sealSheet.flat(StatKeys.SEAL_INTERVAL, sealTags) > 0 ? sealSheet.flat(StatKeys.SEAL_INTERVAL, sealTags)
                        : sealSheet.flat(tw.exilecore.stats.LineageKeys.SEAL_CAST_PERCENT, sealTags) > 0 // 2026-10-27 照 PoE2 萬封：每過施法時間的 50%
                        ? Math.max(0.25, definition.castTime() * sealSheet.flat(tw.exilecore.stats.LineageKeys.SEAL_CAST_PERCENT, sealTags) / 100.0)
                        : Math.max(0.5, 2 * definition.castTime());
                // 2026-10-26 照 PoE2：天賦的「封印獲得頻率增加」（間隔 ÷ (1 + 頻率)）
                final long gap = Math.max(5, Math.round(sealSeconds * 20
                        / Math.max(0.1, 1 + sealSheet.increased(tw.exilecore.stats.TreeMechanicKeys.SEAL_FREQUENCY, sealTags))));
                final long since = runtime.sealSince.getOrDefault(definition.id(), now - gap * sealMax);
                final int seals = (int) Math.min(sealMax, Math.max(0, (now - since) / gap));
                runtime.sealSince.put(definition.id(), now);
                if (seals > 0) {
                    final double areaPer = sealSheet.flat(StatKeys.SEAL_AREA, sealTags);
                    final int projPer = (int) Math.round(sealSheet.flat(StatKeys.SEAL_PROJECTILES, sealTags));
                    if (areaPer > 0 || projPer > 0) {
                        build = build.withAreaAndProjectiles(build.areaScale() * Math.sqrt(1 + areaPer * seals / 100.0),
                                Math.min(SkillBuild.MAX_PROJECTILES, build.projectiles() + projPer * seals));
                    }
                    sealRepeats = seals * (int) Math.round(sealSheet.flat(StatKeys.SEAL_REPEAT, sealTags));
                    sealRepeatScale = sealSheet.flat(tw.exilecore.stats.LineageKeys.REPEAT_LESS, sealTags) > 0 // 2026-10-27 照 PoE2 萬封：重複更少 20%（沒有的話照原本的少一半）
                            ? Math.max(0, 1 - sealSheet.flat(tw.exilecore.stats.LineageKeys.REPEAT_LESS, sealTags) / 100.0) : 0.5;
                    state.notice(String.format(Locale.ROOT, "<#c7a6ff>%s 解開 %d 個封印", ItemRenderer.escape(definition.name()), seals), now + 20);
                }
            }
        }
        resources.beforeCast(player, build.profile().effective(sheet), build.profile().tags()); // 2026-10：終式、怒鑄
        build = resources.culminate(player, build, sheet); // 2026-10-28 照 PoE2 頂點：用掉這個技能自己的連擊換這一下的傷害
        // 2026-10：元素灌注：有 infusion.consume 的用掉身上每種灌注各一層換這一下的加成
        final int[] infusionUsed = new int[3];
        final List<tw.exilecore.stats.Modifier> infused = resources.consumeInfusion(player, build.profile().effective(sheet), build.profile().tags(),
                infusionUsed);
        if (!infused.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(infused));
        }
        final String infusionText = resources.infusionUsed(player, ItemRenderer.escape(definition.name()), infusionUsed); // 2026-10-15：寫清楚用掉哪幾種
        if (infusionText != null) {
            state.notice(infusionText, now + 40);
        }
        // 2026-10-26 照 PoE2：技能自己用灌注（skills.yml 的 resource.infusion：鏈雷用電灌注多連鎖 6 次、更多 200%；隕星用火灌注範圍變大、附加火焰）
        // infusion_each（流焰天幕）：寫的每一種各用一層、各給自己的加成；掛了抑星不用灌注
        if (!resourceSpec.infusion().isEmpty()) {
            final int[] used = new int[3];
            final List<List<String>> rounds = resourceSpec.infusionEach()
                    ? resourceSpec.infusion().stream().map(List::of).toList() : List.of(resourceSpec.infusion());
            for (final List<String> round : rounds) {
                final int element = resources.consumeOne(player, round, build.profile().effective(sheet), build.profile().tags());
                if (element >= 0) {
                    final SkillBuild plain = build;
                    build = applyInfusedSet(applyInfused(build, resourceSpec.infusedFor(SkillResources.INFUSION_ELEMENTS[element])),
                            resourceSpec.infusedSet());
                    used[element]++;
                    // 2026-10-28 照 PoE2（游雷冰灌注「施法時間 +0.5 秒」）：灌注是放的時候才用掉，下一次能動作的時間補上多出來的施法時間
                    final double extra = build.intervalTicks(sheet) - plain.intervalTicks(sheet);
                    if (extra > 1e-9) {
                        runtime.readyAt += extra / speed / onUse.speedMore();
                    }
                }
            }
            final String text = resources.infusionUsed(player, ItemRenderer.escape(definition.name()), used);
            if (text != null) {
                state.notice(text, now + 40);
            }
        }
        // 2026-10-26 照 PoE2 亞馬遜「元素湧動」：非近戰的投射物攻擊每一發用掉一層湧動；2026-10-27 照 PoE2 投射物飛行結束時另外觸發一發湧動爆炸
        // （ProjectileEngine → SkillResources.surgeBlast；這次之前是投射物自己在終點爆開、獲得 100% 傷害為額外元素傷害）
        if (definition.isAttack() && definition.tags().contains(tw.exilecore.stats.Tags.PROJECTILE)
                && !definition.tags().contains(tw.exilecore.stats.Tags.MELEE)) {
            final int[] surged = resources.consumeSurges(player, build.projectiles());
            if (surged != null) {
                resources.queueSurgeBlasts(player, surged);
                build = build.withProfile(build.profile().withLocal(SkillResources.surgeMarker()));
            }
        }
        if (release != null) {
            build = signatures.applyRelease(player, state, build, chargeSpec, release, now); // 完美時機、疊層
        }
        build = signatures.beforeCast(player, definition, build, state, now); // 2026-10-15：霆落矛、擲雷槍消耗狂怒球
        build = spears().beforeCast(player, definition, build, state, now); // 2026-10-27 照 PoE2：擲矛、炎爆矛消耗怒星
        final java.util.List<String> commanded = config.command(definition.id());
        final Mounts.Spec mountSpec = config.mount(definition.id());
        if (mountSpec == null && !Mounts.usableWhileMounted(definition) && mounts().mounted(player)) { // 2026-10-26：鐵蹄踐踏（騎著才能放）拿掉了
            mounts().dismount(player); // 2026-10-26 照 PoE2 Rhoa Mount：騎著只能放弓、投擲長矛與印記技能，放別的會下馬；這次之前只有法術會下馬
        }
        final int airborne;
        if (mountSpec != null) {
            airborne = 0;
            state.notice(mounts().toggle(player, definition, mountSpec), now + 40);
        } else if (commanded != null) {
            // 2026-10：指令：從每一隻聽命的召喚物身上放這個技能（朝準心的敵人，沒有就朝牠自己的目標），最多 COMMAND_MAX 隻
            airborne = 0;
            for (final java.util.Map.Entry<org.bukkit.Location, org.bukkit.entity.LivingEntity> one
                    : minions.commandable(player, commanded, COMMAND_MAX)) {
                final org.bukkit.entity.LivingEntity aim = preferred != null ? preferred : one.getValue();
                final org.bukkit.Location from = one.getKey();
                final org.bukkit.util.Vector direction = aim != null
                        ? aim.getLocation().toVector().subtract(from.toVector()).setY(0)
                        : SkillTargets.facing(player);
                if (direction.lengthSquared() < 1.0e-4) {
                    direction.copy(SkillTargets.facing(player));
                }
                executor.executeFrom(Origin.at(player, from, direction.normalize()), build, aim);
            }
        } else if ("ember".equals(config.signature(definition.id()))) {
            airborne = 0;
            signatures.conjureEmber(player, build, preferred, now); // 星火連珠（PoE2）：火星先懸在頭上
        } else if ("hexblast".equals(config.signature(definition.id()))) {
            airborne = 0;
            final int blasts = executor.hexblast(player, build, preferred); // 2026-10-26 照 PoE2（蝕咒裂）：引爆詛咒
            if (blasts <= 0) {
                state.notice("<gray>" + ItemRenderer.escape(definition.name()) + "：範圍內沒有被你詛咒的敵人", now + 30);
                plugin.players().display(player, state);
            }
        } else if ("sunder".equals(config.signature(definition.id()))) {
            airborne = 0;
            final int blasts = executor.sunder(player, build); // 2026-10-26 照 PoE2（稜光裂）：用掉冰凍、感電、點燃引爆
            if (blasts <= 0) {
                state.notice("<gray>" + ItemRenderer.escape(definition.name()) + "：附近沒有冰凍、感電、點燃中的敵人", now + 30);
                plugin.players().display(player, state);
            }
        } else if (supportMechanics().extras().astral(player, build, preferred)) {
            airborne = 0; // 2026-10-27 照 PoE2 Astral Projection：新星改在準心指的地方放
        } else {
            airborne = executor.execute(player, runtime, build, preferred, now);
        }
        resources.gainInfusion(player, build.profile().effective(sheet), build.profile().tags()); // 2026-10：放完疊灌注
        if (resourceSpec.remnant() != null) { // 2026-10-26 照 PoE2：寒晶雷引爆、燼種爆開、雷雲消失時在落點留下灌注殘骸
            final ChargeEffects.RemnantMods remnantMods = ChargeEffects.RemnantMods.of(build.profile().effective(sheet), build.profile().tags());
            // 2026-10-29 照 PoE2 Orb of Storms（0.3.0）：雷雲放完雷、時間到、被新的擠掉時，在雷雲那裡留下殘骸；這次之前固定放下後 12 秒、在準心那裡
            if (!zones.onExpire(player.getUniqueId(), definition.id(), spot -> {
                if (player.isOnline()) {
                    chargeEffects().dropInfusionRemnant(player, spot, remnantElement(resourceSpec), remnantMods);
                }
            })) {
                dropInfusionRemnantLater(player, useLocation(player, definition, build, preferred), resourceSpec, remnantMods);
            }
        }
        supportMechanics().afterEmpowered(player, empowering, build, preferred); // 2026-10-26：餘震、盾波、冰緩地面
        if (definition.mechanic() != Mechanic.LEAP) {
            procs().onUse(player, definition, useLocation(player, definition, build, preferred)); // 2026-10：輔助觸發的專屬技能
        }
        originals().afterCast(player, build, preferred, now); // 2026-10-26 照 PoE2：鼓翼風後推、噬咬、冰裂、熱度、電震波
        // 跳躍：落地時才算「放了這個技能」（updateLeap），躍電的帶電地面在落地點（衝刺是瞬間到終點，本來就在落地點）
        if (release != null && release.perfect()) {
            procs().event(player, definition.id(), "perfect", player.getLocation(), preferred); // 完美時機放開（蓄勁一擊的火浪）
        }
        if (config.signature(definition.id()) != null) {
            signatures.afterCast(player, definition, build, useLocation(player, definition, build, preferred), now); // 旋風、插地的矛
        }
        final org.bukkit.Location detonateAt = useLocation(player, definition, build, preferred);
        final int detonated = signatures.detonateNear(player, definition, detonateAt) // 2026-10-15：其他技能引爆霆落矛
                + spears().detonateNear(player, definition, detonateAt) // 2026-10-27 照 PoE2：引爆類技能引爆插著的炎爆矛
                + stageEffects().afterUse(player, definition, detonateAt); // 2026-10-28 照 PoE2：引爆矛頭、震脈掌的投射物
        // 2026-10-28 照 PoE2 Thunderous Leap 的品質：引爆矛的時候 (0—30)% 機率留下感電地面
        final double shockedGround = detonated > 0 ? build.qualityFlat(tw.exilecore.stats.GemQualityKeys.SHOCKED_GROUND_CHANCE) : 0;
        if (shockedGround > 0 && detonateAt != null && plugin.random().nextDouble() * 100 < shockedGround) {
            procs().castAt(player, StageLeftovers.TEMPEST_GROUND, definition.id(), detonateAt);
        }
        // 2026-10：幻弓影：弓的攻擊過一下由身旁的幻影再射一次（傷害少很多）
        if (definition.isAttack() && definition.weapons().contains("bow") && !definition.hidden() && definition.cooldown() <= 0
                && StatKeys.flag(state.sheet(), StatKeys.FLAG_MIRAGE)) {
            final SkillBuild mirage = build.withProfile(build.profile().scaled(0.7, "幻影")); // 2026-10-26 照 PoE2 Mirage Archer：更少 30%（這次之前少 60%）
            final org.bukkit.util.Vector facing = SkillTargets.facing(player);
            final org.bukkit.Location from = player.getLocation().add(new org.bukkit.util.Vector(-facing.getZ(), 0, facing.getX()).multiply(1.5));
            // 2026-10：幻影要射向放的那一刻準心指的地方（以前用玩家的水平朝向從旁邊 1.5 格平行射出：
            // 不管上下的角度、也不管鎖定的目標，單發的箭打不到同一個地方，偏一格半而且往下瞄時從頭頂飛過去）
            // 2026-10-28 修正：準心的射線也算敵人（這次之前只看方塊：準心對著怪時，點落在怪後面的牆上，幻影從旁邊 1.5 格朝那點射，
            //   箭從怪旁邊擦過去）；準心指到的敵人當作幻影的目標
            final org.bukkit.Location eye = player.getEyeLocation();
            final org.bukkit.util.RayTraceResult ray = player.getWorld().rayTrace(eye, eye.getDirection(), MIRAGE_AIM_RANGE,
                    org.bukkit.FluidCollisionMode.NEVER, true, 0.3,
                    entity -> entity instanceof org.bukkit.entity.LivingEntity && !(entity instanceof Player) && !entity.equals(player)
                            && !(entity instanceof org.bukkit.entity.ArmorStand));
            final org.bukkit.entity.LivingEntity crosshair = ray != null && ray.getHitEntity() instanceof org.bukkit.entity.LivingEntity hit ? hit : null;
            final java.util.UUID aim = preferred != null ? preferred.getUniqueId() : crosshair != null ? crosshair.getUniqueId() : null;
            final org.bukkit.util.Vector point = ray != null && ray.getHitPosition() != null ? ray.getHitPosition()
                    : eye.toVector().add(eye.getDirection().multiply(MIRAGE_AIM_RANGE));
            // 2026-10-28 照 PoE2 Mirage Archer 的品質（幻影持續時間 +(0—1) 秒）：你停手之後幻影再照攻擊間隔多射幾秒（下一次弓攻擊就重來）
            final long linger = Math.round(Math.max(0, qualityFlat(player, MIRAGE_ARCHER, tw.exilecore.stats.GemQualityKeys.DURATION_FLAT)) * 20);
            final long gap = Math.max(4, Math.round(build.intervalTicks(sheet)));
            final long sequence = mirageSequence.merge(player.getUniqueId(), 1L, Long::sum);
            for (long delay = 10; delay <= 10 + linger; delay += gap) {
                final boolean extra = delay > 10;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (extra && mirageSequence.getOrDefault(player.getUniqueId(), 0L) != sequence) {
                        return; // 又射了一次：這一輪的延續作廢
                    }
                    if (player.isOnline() && player.getWorld().equals(from.getWorld())) {
                        from.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, from.clone().add(0, 1, 0), 12, 0.2, 0.5, 0.2, 0.01);
                        final org.bukkit.entity.LivingEntity mirageTarget = aim == null ? null : living(player, aim);
                        // 有鎖定的敵人就朝牠（牠可能動了），沒有就朝放的時候準心指的點
                        final Origin origin = mirageTarget != null && mirageTarget.isValid()
                                ? Origin.towards(player, from, mirageTarget)
                                : Origin.at(player, from, point.clone().subtract(from.toVector().add(new org.bukkit.util.Vector(0, 1.2, 0))));
                        executor.executeFrom(origin, mirage, mirageTarget);
                    }
                }, delay);
            }
        }
        if (ammoSkill(definition)) {
            useAmmo(player, runtime, state, definition, build, sheet, now);
        }
        // 2026-10：冰晶（投射物的在落點留，ProjectileEngine 處理）
        if (definition.mechanic() != Mechanic.PROJECTILE) {
            final int crystalCount = IceCrystals.count(build, sheet);
            if (crystalCount > 0) {
                final org.bukkit.Location at = definition.mechanic() == Mechanic.ZONE && build.params().zone() != null && !"line".equals(build.params().zone().shape())
                        ? useLocation(player, definition, build, preferred)
                        : player.getLocation().add(SkillTargets.facing(player).multiply(3));
                final org.bukkit.util.Vector facing = SkillTargets.facing(player);
                crystals().spawn(player, build, at, crystalCount, new org.bukkit.util.Vector(-facing.getZ(), 0, facing.getX()));
            }
        }
        recoil(player, definition); // 往後跳（躍退射這類，2026-10）
        shapeshift(player, definition); // 變身（德魯伊，2026-10）
        // 2026-10-26：天賦樹的條件（最近攻擊過、法術種類、印記、混沌技能的代價）
        plugin.players().conditions().onSkillUsed(player, definition.id(), definition.tags(), definition.isAttack(),
                definition.mechanic() == Mechanic.MARK || definition.tags().contains("instant"), now);
        if (!definition.isAttack() && definition.tags().contains(tw.exilecore.stats.Tags.SPELL) && definition.tags().contains(tw.exilecore.stats.Tags.FIRE)) {
            auras.fireSpellCast(player, definition.tags().contains(tw.exilecore.stats.Tags.AREA) ? 2 : 1); // 焚魂（2026-10 PoE2）
        }
        if (!definition.isAttack() && definition.tags().contains(tw.exilecore.stats.Tags.SPELL) && definition.castTime() > 0
                && config.meta(definition.id()) == null && definition.mechanic() != Mechanic.AURA) { // 2026-10-28：只保留精魂的主動技能（掠閃）照樣算；這次之前寫 aura() == null
            metas.gain(player, MetaSpec.Kind.SPELL_CAST, definition.castTime() * 10, preferred); // 2026-10-26：Spellslinger 每 0.1 秒施法時間給能量
        }
        if (airborne > 0) {
            runtime.readyAt = Math.max(runtime.readyAt, now + airborne);
        }
        // 2026-10-28 照 PoE2 Comet 的品質：(0—10)% 機率回響（法術、自己放的；跟回響輔助一樣再放一次）
        final double echoChance = build.echo() || definition.isAttack() ? 0 : build.qualityFlat(tw.exilecore.stats.GemQualityKeys.ECHO_CHANCE);
        if (build.echo() || echoChance > 0 && plugin.random().nextDouble() * 100 < echoChance) {
            // 2026-10-26 照 PoE2 天賦樹：「回響的法術增加 x% 範圍」（只放大回響那一次；範圍倍率開根號）
            final double echoArea = sheet.increased(tw.exilecore.stats.KeystoneKeys.ECHO_AREA, build.profile().tags());
            final SkillBuild echoBuild = echoArea > 0 && build.areaScale() != 1.0
                    ? build.withAreaAndProjectiles(build.areaScale() * Math.sqrt(1 + echoArea), build.projectiles()) : build;
            runtime.echo = new SkillRuntime.Echo(now + 5, echoBuild, preferred == null ? null : preferred.getUniqueId());
            plugin.players().conditions().note(player, tw.exilecore.stats.TreeConditions.ECHOES, definition.id() + "@" + now, now); // 2026-10-27：最近回響過幾次
        }
        if (build.repeats() > 0) {
            // 複擊：接下來每隔一次攻擊間隔自動再打一下（不花魔力），全部打完才能做別的
            // 2026-10-28 照 PoE2 Rapid Assault：有「最後一下」那一段的連刺（stages.last）整串在一次攻擊時間裡打完（六刺平分攻擊時間）；
            // 這次之前每一下都要等一整個攻擊間隔
            final boolean sequence = SkillStages.get(definition.id(), SkillStages.LAST) != null;
            final double gap = sequence ? interval / (build.repeats() + 1) : interval;
            runtime.repeat = new SkillRuntime.Repeat(sequence ? now + gap : runtime.readyAt, gap, build.repeats(), build,
                    preferred == null ? null : preferred.getUniqueId());
            runtime.readyAt = sequence ? Math.max(runtime.readyAt, runtime.repeat.doneAt()) : runtime.repeat.doneAt();
        } else if (sealRepeats > 0) {
            // 封印（解封）：接下來自動再放幾次，每次傷害少一半
            runtime.repeat = new SkillRuntime.Repeat(Math.max(runtime.readyAt, now + 6), 6, sealRepeats,
                    build.withProfile(build.profile().scaled(sealRepeatScale, "解封")), preferred == null ? null : preferred.getUniqueId());
            runtime.readyAt = runtime.repeat.doneAt();
        } else if (runtime.barrage != null && maxStages <= 0 && commanded == null && mountSpec == null
                && runtime.barrage.spec().empowersSkill(definition, state.weaponTags(), build.repeats())) {
            // 連珠（2026-10）：這一下是弓／矛的投射物攻擊——用掉備好的連珠，接下來很快再射幾次（每次少一截傷害、不花魔力，
            // 每次都是重新射：可以再打到同一個敵人），射完才能做別的。連珠技能從現在開始冷卻（基礎＋這一招攻擊時間的幾 %）。
            final SkillRuntime.Barrage barrage = runtime.barrage;
            runtime.barrage = null;
            final int gap = barrage.spec().gapTicks();
            runtime.repeat = new SkillRuntime.Repeat(now + gap, gap, barrage.repeats(),
                    build.withProfile(build.profile().scaled(barrage.spec().damageScale(), barrage.name())),
                    preferred == null ? null : preferred.getUniqueId());
            runtime.readyAt = Math.max(runtime.readyAt, runtime.repeat.doneAt());
            runtime.cooldowns.put(barrage.skillId(), now + barrage.spec().usedCooldownTicks(barrage.baseCooldown(), build.intervalTicks(sheet),
                    barrage.cooldownPercent(), barrage.cooldownScale()));
            showCooldown(player, barrage.skillId(), runtime.cooldownLeft(barrage.skillId(), now));
            state.notice(String.format(Locale.ROOT, "<#ffd27f>➶ %s：重複 %d 次", ItemRenderer.escape(barrage.name()), barrage.repeats()), now + 30);
            plugin.players().display(player, state);
        }
    }

    /**
     * 連珠（2026-10）：備好「下一次弓／矛的投射物攻擊重複」。施放時用掉身上全部的那種戰星，每顆多重複幾次；
     * 增益撐 guard.duration 秒（吃技能持續時間），沒用掉就過期（只算技能自己的基礎冷卻）。
     */
    private void readyBarrage(final Player player, final SkillRuntime runtime, final PlayerState state, final SkillBuild build,
                              final BarrageSpec raw, final long now) {
        final SkillDefinition definition = build.definition();
        final BarrageSpec spec = raw.withLess(build.qualityFlat(tw.exilecore.stats.GemQualityKeys.BARRAGE_LESS)); // 2026-10-28 照 PoE2 Barrage 的品質
        int consumed = 0;
        int benefit = 0;
        if (spec.charge() != null && spec.perCharge() > 0 && plugin.combat() != null && plugin.combat().charges() != null) {
            // 2026-10-24：戰星輔助（恆星不扣、倍星算兩顆）；恆星不扣的那顆不能再用一次，所以最多照身上原本的顆數算
            final int held = plugin.combat().charges().count(player, spec.charge());
            final StatSheet effective = build.profile().effective(plugin.players().sheet(player));
            while (consumed < Math.min(20, held)) {
                final int got = plugin.combat().charges().spend(player, spec.charge(), effective, build.profile().tags());
                if (got <= 0) {
                    break;
                }
                consumed++;
                benefit += got;
            }
        }
        final int repeats = spec.repeatsWith(benefit);
        final int ticks = (int) Math.max(1, Math.round(definition.params().mark().duration() * build.durationScale() * 20));
        final double cooldownScale = definition.cooldown() > 0 ? build.cooldownTicks() / (definition.cooldown() * 20.0) : 1.0;
        runtime.barrage = new SkillRuntime.Barrage(definition.id(), definition.name(), spec, repeats, now + ticks, definition.cooldown(),
                spec.cooldownAttackTime().at(build.level()), cooldownScale);
        visuals.cast(definition.visual(), player);
        state.notice(String.format(Locale.ROOT, "<#ffd27f>➶ %s：下一次" + (spec.spells() ? "法術" : "弓／矛的投射物攻擊") + "重複 <white>%d</white> 次%s",
                ItemRenderer.escape(definition.name()), repeats,
                consumed > 0 ? "<gray>（用掉 " + consumed + " 顆" + ItemRenderer.escape(chargeName(spec.charge())) + "）" : ""), now + 50);
        plugin.players().display(player, state);
    }

    /** 身上有幾顆這種戰星（沒有戰星系統、技能不能跳過冷卻：0）。 */
    private int chargesHeld(final Player player, final String chargeId) {
        if (chargeId == null || plugin.combat() == null || plugin.combat().charges() == null) {
            return 0;
        }
        return plugin.combat().charges().count(player, chargeId);
    }

    /** 2026-10-26：面板估強化用：強化的種類（照 {@link WarcrySpec.Empower#kind}）。 */
    private static final List<String> KINDS = List.of("attack", "melee", "slam", "strike", "shield");

    private static boolean exertFits(final int kind, final SkillDefinition definition) {
        final String name = kind < 0 || kind >= KINDS.size() ? "attack" : KINDS.get(kind);
        return new WarcrySpec.Empower(name, null, 1, 1, Scaled.of(0), Scaled.of(0), Scaled.of(0), Scaled.of(0), 0, 0, false, null, Scaled.of(0), false)
                .accepts(definition);
    }

    /** 2026-10-26：這個攻擊技能放下去會不會被戰吼強化（嘯準：消耗更少）。 */
    private static boolean willEmpower(final SkillRuntime runtime, final SkillDefinition definition, final long now) {
        if (runtime.exert != null && runtime.exert.active(now) && definition.isAttack()) {
            return true;
        }
        for (final SkillRuntime.Empowered each : runtime.empowered.values()) {
            if (each.active(now) && each.spec().accepts(definition)) {
                return true;
            }
        }
        return false;
    }

    /** 戰星的名字（charges.yml）。 */
    private String chargeName(final String chargeId) {
        return plugin.combat() == null || plugin.combat().charges() == null
                ? tw.exilecore.charge.ChargeText.name(chargeId) : plugin.combat().charges().nameOf(chargeId);
    }

    /** 戰星的顏色（MiniMessage）。 */
    private String chargeColor(final String chargeId) {
        return plugin.combat() == null || plugin.combat().charges() == null ? "white" : plugin.combat().charges().colorOf(chargeId);
    }

    /** 某個技能的冷卻還剩幾秒（給面板顯示）。 */
    public double cooldownLeftSeconds(final Player player, final String skillId) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        return runtime == null ? 0 : runtime.cooldownLeft(skillId, now()) / 20.0;
    }

    /**
     * 手上的武器用不了這個技能時說清楚為什麼（2026-10：武器需求不符沒生效時，原本只說「需要矛」，看起來像拿對武器也不能用）：
     * 角色面板的武器對、但需求（等級、屬性）不夠 → 說差多少；背包裡有對的武器、但沒裝進角色面板 → 說要裝；其他 → 說現在拿什麼。
     */
    private String weaponProblem(final Player player, final PlayerState state, final SkillDefinition definition) {
        final tw.exilecore.item.ItemBase equipped = baseOf(tw.exilecore.character.Gear.mainHand(state));
        if (equipped != null && definition.usableWith(equipped.tags())) {
            final String prefix = equipped.name() + "：";
            for (final String line : state.inactiveEquipment()) {
                if (line.startsWith(prefix)) {
                    return equipped.name() + "需求不符、沒有生效（" + line.substring(prefix.length()) + "），" + definition.name() + "用不了";
                }
            }
            return equipped.name() + "沒有生效，" + definition.name() + "用不了";
        }
        final org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            final org.bukkit.inventory.ItemStack stack = inventory.getItem(slot);
            if (tw.exilecore.character.Gear.isDisplay(stack)) {
                continue;
            }
            final tw.exilecore.item.ItemBase base = baseOf(stack);
            if (base != null && base.slot() == tw.exilecore.item.ItemSlot.MAIN_HAND && definition.usableWith(base.tags())) {
                return definition.name() + "需要" + definition.weaponText() + "：背包裡的" + base.name()
                        + "要裝進角色面板的武器欄（/panel，或拖到快捷列第 9 格）才算拿著";
            }
        }
        return definition.name() + "需要" + definition.weaponText() + "（現在：" + (equipped == null ? "徒手" : equipped.name()) + "）";
    }

    /** 英靈圖騰：技能面板上排在它後面一格的錘子攻擊技能（不能有冷卻、不能蓄力、要能交給圖騰放）；不行回傳錯誤訊息。 */
    /**
     * 法術圖騰（2026-10，照 PoE2 的主要寶石）：消耗 3 顆銳星或磐星（銳星先用），放一根用插槽法術的圖騰
     * （2026-10-27 照 PoE2：孔裡所有的法術，照孔的順序輪流放；這次之前只用第 1 孔）。
     * 魔力照法術圖騰自己的消耗。
     */
    private void spellTotem(final Player player, final SkillRuntime runtime, final PlayerState state, final StatSheet sheet, final int key,
                            final SkillLoadout.Entry entry, final SkillBuild metaBuild, final MetaSpec spec, final long now) {
        final SkillDefinition definition = metaBuild.definition();
        final int position = state.skills().find(definition.id());
        final MetaGems.Socket socket = position < 0 ? null : metas.socket(state, position);
        if (socket == null) {
            final String problem = position < 0 ? null : metas.socketProblem(state, position);
            fail(player, runtime, state, problem == null ? definition.name() + "：孔裡要放法術的寶石（/skills 點它，可以放好幾個）" : problem, now);
            return;
        }
        final tw.exilecore.charge.ChargeService charges = plugin.combat() == null ? null : plugin.combat().charges();
        final int power = charges == null ? 0 : charges.count(player, "power");
        final int endurance = charges == null ? 0 : charges.count(player, "endurance");
        // 2026-10-26 照 PoE2 先祖魂約：放圖騰不需要充能（有的話照樣消耗，照消耗的顆數加成）
        final boolean bondTotem = StatKeys.flag(metaBuild.profile().effective(sheet), StatKeys.FLAG_ANCESTRAL_BOND);
        if (!bondTotem && power + endurance < spec.charges()) {
            fail(player, runtime, state, String.format(Locale.ROOT, "%s要消耗 %d 顆銳星或磐星（現在 %d 顆）", definition.name(), spec.charges(),
                    power + endurance), now);
            return;
        }
        final int powerUsed = Math.min(power, spec.charges());
        final int enduranceUsed = Math.min(endurance, spec.charges() - powerUsed);
        // 2026-10-27 照 PoE2 Spell Totem「uses socketed Spells」：孔裡每個法術各算一個，圖騰照孔的順序輪流放（這次之前只用第 1 孔）
        List<SkillBuild> builds = metas.totemBuilds(state, sheet, socket, metaBuild.level(), powerUsed, enduranceUsed);
        SkillBuild build = builds.getFirst();
        if (build.deploy() == null) {
            fail(player, runtime, state, definition.name() + "：" + socket.spell().name() + "不能交給圖騰放", now);
            return;
        }
        final String problem = deployables.precheck(player, build);
        if (problem != null) {
            fail(player, runtime, state, problem, now);
            return;
        }
        final int cost = metaBuild.manaCost();
        final boolean bloodMagic = StatKeys.flag(state.sheet(), StatKeys.FLAG_BLOOD_MAGIC);
        if (cost > 0 && (bloodMagic ? state.life() <= cost : state.mana() + 1e-9 < cost)) {
            fail(player, runtime, state, (bloodMagic ? "生命不足（" : "魔力不足（") + definition.name() + "要 " + cost + "）", now);
            return;
        }
        // 2026-10-24：戰星輔助（恆星不扣、倍星那顆的效果算兩次）
        final StatSheet metaEffective = metaBuild.profile().effective(sheet);
        int powerBenefit = 0;
        int enduranceBenefit = 0;
        for (int i = 0; i < powerUsed; i++) {
            powerBenefit += Math.max(1, charges.spend(player, "power", metaEffective, metaBuild.profile().tags()));
        }
        for (int i = 0; i < enduranceUsed; i++) {
            enduranceBenefit += Math.max(1, charges.spend(player, "endurance", metaEffective, metaBuild.profile().tags()));
        }
        if (powerBenefit != powerUsed || enduranceBenefit != enduranceUsed) {
            builds = metas.totemBuilds(state, sheet, socket, metaBuild.level(), powerBenefit, enduranceBenefit);
            build = builds.getFirst();
        }
        if (cost > 0) {
            if (bloodMagic) {
                state.setLife(state.life() - cost);
            } else {
                state.setMana(state.mana() - cost);
            }
        }
        plugin.players().grace().end(player, true);
        final double speed = plugin.ailments() == null ? 1.0 : Math.max(0.05, plugin.ailments().actionSpeed(player));
        runtime.readyAt = SkillMath.nextReady(runtime.readyAt, now, metaBuild.intervalTicks(sheet) / speed);
        if (key != SkillLoadout.KEY_LEFT && key != SkillLoadout.KEY_SNEAK_LEFT) {
            ServerSwing.mainHand(player);
        }
        deployables.place(player, build, builds.subList(1, builds.size()), null, List.of(), 0);
        state.notice(String.format(Locale.ROOT, "<#c9a7ff>%s：%s（銳星 %d、磐星 %d）", ItemRenderer.escape(definition.name()),
                ItemRenderer.escape(socket.names()), powerUsed, enduranceUsed), now + 40);
        plugin.players().display(player, state);
    }

    private Object linkedMace(final PlayerState state, final StatSheet sheet, final SkillDefinition totem) {
        final SkillLoadout loadout = state.skills();
        int position = -1;
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = loadout.skill(i);
            if (entry != null && entry.skillId().equals(totem.id())) {
                position = i;
                break;
            }
        }
        // 2026-10-22：英靈用的技能放在英靈圖騰自己的孔裡（以前是面板上它後面那一格）
        // 2026-10-27 照 PoE2（Ancestral Warrior Totem：Place one or more Skill Gems；Each warrior uses a socketed Mace Skill）：
        //   孔裡可以放好幾個錘子技能，英靈照孔的順序輪流用（linkedMaces）；這裡回傳第一個能用的（說明、每秒傷害用）
        final SkillLoadout.Entry host = position < 0 ? null : loadout.skill(position);
        final List<SkillLoadout.Entry> inside = innerEntries(host);
        Object first = null;
        for (final SkillLoadout.Entry next : inside) {
            final Object found = linkedMace(state, sheet, totem, host, next);
            if (found instanceof SkillBuild) {
                return found;
            }
            if (first == null) {
                first = found;
            }
        }
        return first != null ? first : linkedMace(state, sheet, totem, host, null);
    }

    /** 2026-10-27：英靈圖騰孔裡所有能用的錘子技能（照孔的順序，英靈輪流用）；一個都不能用回傳空的。 */
    private List<SkillBuild> linkedMaces(final PlayerState state, final StatSheet sheet, final SkillDefinition totem) {
        final int position = state.skills().find(totem.id());
        final SkillLoadout.Entry host = position < 0 ? null : state.skills().skill(position);
        final List<SkillBuild> list = new ArrayList<>();
        for (final SkillLoadout.Entry next : innerEntries(host)) {
            if (linkedMace(state, sheet, totem, host, next) instanceof SkillBuild found) {
                list.add(found);
            }
        }
        return list;
    }

    /** 英靈圖騰孔裡的一個錘子技能（next）英靈用的數值；不行回傳錯誤訊息。 */
    private Object linkedMace(final PlayerState state, final StatSheet sheet, final SkillDefinition totem, final SkillLoadout.Entry host,
                              final SkillLoadout.Entry next) {
        final SkillDefinition definition = next == null ? null : config.skill(next.skillId());
        final String hint = totem.name() + "：孔裡要放錘子攻擊技能的寶石（/skills 點它，可以放好幾個）";
        if (definition == null) {
            return hint;
        }
        final String why = linkedProblem(definition);
        if (why != null) {
            return hint + "（" + definition.name() + "：" + why + "）";
        }
        final String attributes = GemAttributes.problem(config, next, sheet); // 2026-10-27 照 PoE2：屬性需求不足的錘子技能英靈不用（這次之前照用）
        if (attributes != null) {
            return hint + "（" + definition.name() + "：" + attributes + "）";
        }
        // 2026-10-21：輔助寶石加的冷卻也算（蓄鋒、蓄疾…）：英靈不等冷卻，以前白拿那顆輔助的加成（暴擊率更多 40%、異常機率加倍）
        // 2026-10-22：要圖騰的輔助（樁怒、疾樁…）也套到英靈的攻擊上（innerSupportsOf 併成一顆不挑技能的）
        // 2026-10-23 修正：放進去的錘子寶石的品質也要算（以前英靈這一下直接用 SkillBuild.of，品質沒套上）
        final SkillBuild built = SkillBuild.of(config, definition, next.level(), state.level(), false, innerSupportsOf(host, totem, definition),
                state.weaponTags(), sheet, resonantOf(next), qualityModifiers(definition, next.quality() + extraQuality(sheet)));
        if (built.cooldownTicks() > 0) {
            final List<String> names = new ArrayList<>();
            for (final SupportDefinition support : supportsOf(next)) {
                if (addsCooldown(support)) {
                    names.add(support.name());
                }
            }
            return hint + "（" + definition.name() + "不能有冷卻：" + (names.isEmpty() ? "輔助寶石加的冷卻也算" : String.join("、", names) + "會加冷卻，拿掉才能放圖騰") + "）";
        }
        return built;
    }

    /**
     * 2026-10-23：英靈圖騰的說明多寫英靈那一下（放在孔裡的錘子技能、吃其他孔的輔助與圖騰傷害）：
     * 平均每次、暴擊、輔助。上面那幾行是英靈圖騰本身的，看不到英靈吃到的輔助（例如萬界歸墟的暴擊）。
     */
    private List<String> ancestorLines(final PlayerState state, final StatSheet sheet, final SkillBuild rawAncestor) {
        final SkillBuild ancestor = SkillDeployables.ancestorHits(rawAncestor);
        final List<String> lines = new ArrayList<>();
        lines.add("<#e7c56b>英靈的" + ItemRenderer.escape(ancestor.definition().name()) + "<gray>（Lv." + ancestor.level() + "）：");
        for (final tw.exilecore.stats.Modifier mod : ancestor.profile().local()) {
            if (mod.source() != null && mod.source().startsWith("品質 ")) {
                lines.add("<gray>　<#8888ff>" + ItemRenderer.escape(mod.source()) + "：增加 " + Math.round(mod.value() * 100) + "% 傷害"); // 2026-10-23
            }
        }
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, ancestor.profile());
        final long kinds = java.util.Arrays.stream(tw.exilecore.stats.DamageType.values()).filter(type -> expected.get(type) > 0).count();
        lines.add(kinds > 1
                ? String.format(Locale.ROOT, "<gray>　平均每次：<white>%d <dark_gray>（%s）", Math.round(expected.total()), expected)
                : String.format(Locale.ROOT, "<gray>　平均每次：<white>%s", expected.isEmpty() ? "0" : expected));
        final double evasion = tw.exilecore.character.StatsReport.monsterEvasion(plugin, state);
        final double crit = DamageCalculator.critChance(sheet, ancestor.profile(), evasion);
        lines.add(String.format(Locale.ROOT, "<gray>　暴擊：<white>%.1f%% <dark_gray>× <white>%.0f%%", crit * 100,
                DamageCalculator.critMultiplier(ancestor.profile().effective(sheet), ancestor.profile().tags(), null) * 100));
        if (!ancestor.supports().isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final SupportDefinition support : ancestor.supports()) {
                // 要圖騰的輔助（樁怒、疾樁…）併成一顆，名字是宿主的：改寫成「圖騰類輔助」
                names.add(support.id().startsWith("host.") ? "圖騰類輔助" : ItemRenderer.escape(support.name()));
            }
            lines.add("<gray>　輔助：<#8fd3ff>" + String.join("、", names));
        }
        return lines;
    }

    /** 2026-10-22：這個技能能不能放進英靈圖騰的孔（2026-10-27：每一孔都能放）；可以回傳 null，不行回傳原因。 */
    static String linkedProblem(final SkillDefinition definition) {
        if (definition == null || definition.hidden() || !definition.isAttack() || !definition.weapons().contains("mace")) {
            return "要錘子的攻擊技能";
        }
        if (definition.cooldown() > 0 || SkillExecutor.hasFlag(definition, StatKeys.CHANNEL_STAGES) || !SkillBuild.deployable(definition)) {
            return "不能是有冷卻、蓄力、位移的技能";
        }
        return TotemRules.ancestralProblem(definition); // 2026-10-27 照 PoE2 Ancestral Warrior Totem：主要寶石、引導、有使用條件、原作不能給圖騰的也擋掉
    }

    /** 這顆輔助會讓技能多冷卻時間（蓄鋒、蓄疾這種）。 */
    static boolean addsCooldown(final SupportDefinition support) {
        for (final tw.exilecore.stats.Modifier modifier : support.modifiers()) {
            if (StatKeys.SKILL_ADDED_COOLDOWN.equals(modifier.stat()) && modifier.value() > 0) {
                return true;
            }
        }
        return false;
    }


    /** 舉盾：副手要是盾。 */
    private String guardProblem(final PlayerState state, final SkillDefinition definition) {
        final tw.exilecore.item.ItemBase off = baseOf(state.jewellery(tw.exilecore.character.Gear.OFF));
        if (off == null || !off.isShield() || off.hasTag("focus")) {
            return "要在副手裝盾才能舉盾（法器不行）";
        }
        // 2026-10-26 照 PoE2：架盾（Raise Shield）要有護甲的盾（RequiresShield／Armoured Shield；圓盾是招架用的）；這次之前任何盾都行
        final ChargeSpec charge = config.charge(definition.id());
        if (charge != null && charge.mode() == ChargeSpec.Mode.HOLD && off.armour() <= 0) {
            return "要在副手裝有護甲的盾才能架盾（圓盾不行）";
        }
        return null;
    }

    /** 2026-10-27 照 PoE2（Fortifying Cry 要 Armoured Shield）：副手要有護甲的盾（圓盾、法器不行）。 */
    private String armouredShieldProblem(final PlayerState state, final SkillDefinition definition) {
        final tw.exilecore.item.ItemBase off = baseOf(state.jewellery(tw.exilecore.character.Gear.OFF));
        return off == null || !off.isShield() || off.hasTag("focus") || off.armour() <= 0
                ? "要在副手裝有護甲的盾才能用" + ItemRenderer.escape(definition.name()) + "（圓盾、法器不行）" : null;
    }

    /** 舉盾（2026-10）：guard.effects 變成增益。2026-10-27 照站長「放技能減速全部移除」：資料裡的 guard.slow（招架 50%）不再讓移動變慢；這次之前算進去。 */
    /** 2026-10-28：急裝的增益代號（只顯示倒數；傷害在放弩技能時照被強化的發數給）。 */
    static final String EMERGENCY_BUFF = "guard.emergency_reload";
    /** 2026-10-28 照 PoE2 Emergency Reload 的品質：被強化的弩矢再更多 x%（FLAT，百分點；gem_quality.yml）。 */
    static final String EMERGENCY_MORE = "emergency_reload.more";

    /** 急裝的「更多 x%」（0.33 = 33%）：這個等級的＋品質給的。 */
    static double emergencyMore(final SkillBuild build, final StatSheet sheet) {
        double more = 0;
        for (final ScaledMod effect : build.definition().params().mark().effects()) {
            final tw.exilecore.stats.Modifier mod = effect.toModifier(build.level(), 1.0, build.definition().name());
            if (mod.stat().equals(StatKeys.DAMAGE) && mod.type() == tw.exilecore.stats.ModType.MORE) {
                more += mod.value();
            }
        }
        final StatSheet effective = sheet == null ? null : build.profile().effective(sheet);
        double quality = 0;
        if (effective != null) {
            quality = effective.flat(EMERGENCY_MORE, build.profile().tags());
        } else {
            for (final tw.exilecore.stats.Modifier mod : build.profile().local()) {
                if (mod.stat().equals(EMERGENCY_MORE)) {
                    quality += mod.value();
                }
            }
        }
        return more + quality / 100.0;
    }

    /**
     * 2026-10-28 照 PoE2 Emergency Reload：立刻裝滿所有弩技能的彈匣，並強化每個彈匣的弩矢（發數＝那個技能的彈匣大小）
     * 更多 x% 傷害，持續 6 秒或強化的弩矢用完為止。這次之前是 6 秒內所有弩攻擊更多 x%（不限發數）。
     */
    private void emergencyReload(final Player player, final SkillRuntime runtime, final PlayerState state, final SkillBuild build, final long now) {
        final StatSheet sheet = plugin.players().sheet(player);
        runtime.empoweredBolts.clear();
        for (int i = 0; i < tw.exilecore.character.SkillLoadout.MAX_SKILLS; i++) {
            final tw.exilecore.character.SkillLoadout.Entry entry = state.skills().skill(i);
            final SkillDefinition each = entry == null ? null : config.skill(entry.skillId());
            if (each != null && ammoSkill(each)) {
                runtime.empoweredBolts.put(each.id(), clipSize(config, build(state, sheet, each, entry), sheet));
            }
        }
        reloadNow(player, null);
        runtime.empoweredMore = emergencyMore(build, sheet);
        final int ticks = (int) Math.round(build.definition().params().mark().duration() * build.durationScale() * 20);
        runtime.empoweredUntil = now + tw.exilecore.stats.TreeEffectKeys.buffTicks(state.sheet(), ticks);
        buff(player, EMERGENCY_BUFF, build.definition().name(), ticks, List.of());
    }

    private void guard(final Player player, final SkillBuild build) {
        final SkillParams.Mark guard = build.definition().params().mark();
        final List<tw.exilecore.stats.Modifier> mods = new ArrayList<>();
        for (final ScaledMod effect : guard.effects()) {
            mods.add(effect.toModifier(build.level(), 1.0, build.definition().name()));
        }
        // 2026-10-28 照 PoE2：品質加在增益本身的數值上
        final double feast = feastScale(player, build);
        final List<tw.exilecore.stats.Modifier> granted = feast == 1.0 ? mods : scaledMods(mods, feast);
        buff(player, build.definition().weapons().isEmpty() ? "guard" : "guard." + build.definition().id(), build.definition().name(),
                (int) Math.round(guard.duration() * build.durationScale() * 20), build.withQualityEffects(granted, 1.0, build.definition().name()));
    }

    /** 噬屍宴：原作一次最多吃 5 具屍體；我們固定算 3 具的量（不用屍體）。 */
    static final int FEAST_CORPSES = 5;
    static final int FEAST_FIXED = 3;
    static final double FEAST_RADIUS = 4;

    /**
     * 2026-10-28 照 PoE2 Feast of Flesh 的品質（多吃 (0—3) 具屍體）：我們的噬屍宴固定算 3 具、不用屍體；品質多的那幾具照原作
     * 是「超過 5 具的部分」：身邊 4 格內的屍體超過 5 具時，多出來的（最多品質給的數量）吃掉，每具再多一份回復。回傳回復的倍率。
     */
    private double feastScale(final Player player, final SkillBuild build) {
        final int extra = OriginalSkills.qualityInt(build.qualityFlat(tw.exilecore.stats.GemQualityKeys.CORPSES_FLAT));
        if (extra <= 0 || plugin.combat() == null || plugin.combat().corpses() == null) {
            return 1.0;
        }
        final org.bukkit.Location at = player.getLocation();
        final List<tw.exilecore.combat.Corpses.Corpse> near = new ArrayList<>(plugin.combat().corpses().near(at.getWorld().getUID(), at.getX(), at.getZ(),
                FEAST_RADIUS, plugin.getServer().getCurrentTick()));
        final int eaten = Math.min(extra, Math.max(0, near.size() - FEAST_CORPSES));
        for (int i = 0; i < eaten; i++) {
            plugin.combat().corpses().remove(near.get(FEAST_CORPSES + i));
        }
        return (FEAST_FIXED + eaten) / (double) FEAST_FIXED;
    }

    private static List<tw.exilecore.stats.Modifier> scaledMods(final List<tw.exilecore.stats.Modifier> mods, final double scale) {
        final List<tw.exilecore.stats.Modifier> out = new ArrayList<>();
        for (final tw.exilecore.stats.Modifier mod : mods) {
            out.add(new tw.exilecore.stats.Modifier(mod.stat(), mod.type(), mod.value() * scale, mod.tags(), mod.source()));
        }
        return out;
    }

    /** 變身的形態：放帶 bear／werewolf／wyvern 標籤的技能就變成那種形態 {@value #FORM_TICKS} tick（換一種就換掉）。 */
    static final int FORM_TICKS = 160;
    /** 變身形態的增益代號（獸血沸的狂暴要看現在是不是獸形、把形態撐到狂暴結束）。 */
    static final String FORM_BUFF = "shapeshift";

    private void shapeshift(final Player player, final SkillDefinition definition) {
        final java.util.Set<String> tags = definition.tags();
        final String name;
        final List<tw.exilecore.stats.Modifier> mods;
        if (tags.contains(tw.exilecore.stats.Tags.BEAR)) {
            name = "熊形態";
            mods = List.of(tw.exilecore.stats.Modifier.more(StatKeys.LIFE_MAX, 0.15, name),
                    tw.exilecore.stats.Modifier.increased(StatKeys.ARMOUR, 0.5, name),
                    tw.exilecore.stats.Modifier.increased(StatKeys.STUN_THRESHOLD, 0.3, name),
                    tw.exilecore.stats.Modifier.increased(StatKeys.MOVEMENT_SPEED, -0.05, name));
        } else if (tags.contains(tw.exilecore.stats.Tags.WEREWOLF)) {
            name = "狼人形態";
            mods = List.of(tw.exilecore.stats.Modifier.increased(StatKeys.ATTACK_SPEED, 0.15, name),
                    tw.exilecore.stats.Modifier.increased(StatKeys.MOVEMENT_SPEED, 0.1, name),
                    tw.exilecore.stats.Modifier.increased(StatKeys.CRIT_CHANCE, 0.25, name));
        } else if (tags.contains(tw.exilecore.stats.Tags.WYVERN)) {
            name = "飛龍形態";
            mods = List.of(new tw.exilecore.stats.Modifier(StatKeys.DAMAGE, tw.exilecore.stats.ModType.INCREASED, 0.2,
                            java.util.Set.of(tw.exilecore.stats.Tags.ELEMENTAL), name),
                    tw.exilecore.stats.Modifier.increased(StatKeys.EVASION, 0.4, name));
        } else {
            return;
        }
        final boolean changed = !name.equals(formName(player)); // 2026-10-26（蟄甲）：這一下是不是真的換了形態
        buff(player, FORM_BUFF, name, FORM_TICKS, mods);
        plugin.players().conditions().onShapeshift(player, now()); // 2026-10-26：天賦樹「最近變成獸形」
        druidSupports().shapeshifted(player, definition, name, changed); // 2026-10-26 照 PoE2 Helbrym's Hide
    }

    /** 技能的 skill.recoil（FLAT，×0.01 = 每 tick 幾格）：放完往後跳。 */
    private static void recoil(final Player player, final SkillDefinition definition) {
        double power = 0;
        for (final tw.exilecore.stats.Modifier modifier : definition.modifiers()) {
            if (modifier.stat().equals(StatKeys.SKILL_RECOIL)) {
                power += modifier.value();
            }
        }
        if (power <= 0) {
            return;
        }
        final org.bukkit.util.Vector back = player.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1e-6) {
            return;
        }
        back.normalize().multiply(-power / 100.0).setY(0.42);
        player.setVelocity(back);
    }

    private tw.exilecore.item.ItemBase baseOf(final org.bukkit.inventory.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        // 2026-10-27 效能：只讀 PDC 的基底代號（施放失敗時要看背包 36 格；這次之前每格整份解析）
        return plugin.items().base(stack);
    }

    /** 2026-10-28 照 PoE2 Hollow Form：按下去開始引導（孔裡要有能用的近戰攻擊）；引導中的每一下交給 {@link HollowImages#beat}。 */
    private void hollowForm(final Player player, final SkillRuntime runtime, final PlayerState state, final StatSheet sheet, final int key,
                            final SkillBuild build, final LivingEntity preferred, final long now) {
        final SkillDefinition definition = build.definition();
        final boolean beat = runtime.releasing != null && runtime.releasing.skillId().equals(definition.id())
                && runtime.charging != null && runtime.charging.skillId.equals(definition.id());
        runtime.releasing = null;
        if (beat) {
            hollowImages.beat(player, runtime, state, sheet, build, now);
            return;
        }
        final ChargeSpec spec = config.charge(definition.id());
        final List<SkillBuild> socketed = hollowSocketed(player);
        if (spec == null || socketed.isEmpty()) {
            final String unusable = hollowUnusable(player); // 2026-10-29：放了但不能用的，說是哪一顆、為什麼
            fail(player, runtime, state, definition.name() + "：" + (unusable != null ? "孔裡的技能現在不能用（" + unusable + "）"
                    : "孔裡要放" + HollowForm.wants() + "的技能寶石（/skills 點它，拿技能寶石點一個孔）"), now);
            return;
        }
        if (signatures.press(player, runtime, state, key, definition, build, spec, preferred, sheet, now)) {
            final String unpaid = channelling.start(player, runtime, state, build, spec, now);
            if (unpaid != null) {
                runtime.charging = null;
                fail(player, runtime, state, unpaid, now);
                return;
            }
            hollowImages.started(player, runtime, socketed.getFirst(), sheet, now);
        }
    }

    private void fail(final Player player, final SkillRuntime runtime, final PlayerState state, final String message, final long now) {
        state.notice("<red>" + ItemRenderer.escape(message), now + 30);
        plugin.players().display(player, state);
        if (now - runtime.lastFailTick >= 10) {
            visuals.fail(player);
        }
        runtime.lastFailTick = now;
    }

    // ---- 技能面板 ----

    /** 按鍵格子目前會放什麼（{@link SkillLoadout#BIND_SLOTS} 個格子：左、右、潛行左、潛行右、數字鍵 3–8）。 */
    public Resolved resolve(final PlayerState state, final int bindSlot) {
        final SkillLoadout.Entry entry = state.skills().boundSkill(bindSlot);
        if (entry != null) {
            final SkillDefinition definition = config.skill(entry.skillId());
            if (definition != null && !definition.hidden()) {
                return new Resolved(definition, entry);
            }
        }
        // 2026-10-26：PoE2 的法杖、長杖沒有普攻，拿著時沒綁技能的鍵放武器賦予的法術（第一個會造成傷害的）
        final Set<String> weaponTags = state.weaponTags();
        if (weaponTags.contains(Tags.WAND) || weaponTags.contains(Tags.STAFF) && weaponTags.contains(Tags.RANGED)) {
            for (final SkillLoadout.Entry granted : state.skills().entries()) {
                if (granted.granted() && !state.skills().dormant(granted)) {
                    final SkillDefinition definition = config.skill(granted.skillId());
                    if (definition != null && !definition.hidden() && definition.dealsDamage() && definition.mechanic() != Mechanic.AURA
                            && definition.summon() == null && definition.cooldown() <= 0) {
                        return new Resolved(definition, granted);
                    }
                }
            }
        }
        return new Resolved(config.defaultAttack(weaponTags), null);
    }

    /**
     * /stats 用（2026-10）：技能欄上每個會造成傷害的技能，估計每秒傷害（平均一下 × 暴擊期望 × 每秒次數 × 複擊；
     * 沒算怪物的減免、持續傷害）。召喚技能算召喚物：一隻的每秒傷害 × 同時最多幾隻（名字後面寫「召喚物 ×N」）。
     * 回傳「按鍵、名字、每秒傷害」，同一個技能綁在好幾個鍵只算一次。
     */
    /** 幻弓影：找準心指的點最遠幾格。 */
    private static final double MIRAGE_AIM_RANGE = 48;

    /**
     * 一個技能每秒打幾下：一輪是「複擊的每一下各一次攻擊間隔」再加「最後一下的間隔與冷卻比較長的那個」。
     */
    /** 2026-10-28 照 PoE2 Rapid Assault：整串在一次攻擊時間裡打完的連刺（stages.last），每一下隔「攻擊間隔 ÷ 下數」（估算用）。 */
    static double repeatGap(final SkillBuild build, final double intervalTicks) {
        return SkillStages.get(build.definition().id(), SkillStages.LAST) != null && build.repeats() > 0
                ? intervalTicks / (build.repeats() + 1) : intervalTicks;
    }

    static double hitsPerSecond(final double intervalTicks, final double cooldownTicks, final int repeats) {
        final int extra = Math.max(0, repeats);
        final double cycle = extra * intervalTicks + Math.max(intervalTicks, cooldownTicks);
        return 20.0 * (extra + 1) / Math.max(1, cycle);
    }

    /**
     * 放一次對同一個敵人打幾下的量（2026-10，面板以前只算第一下）：連射的每一波各算一下；
     * 有餘震的再加一下「更多 more%」的。
     */
    static double castHits(final int burstCount, final boolean aftershock, final double aftershockMore) {
        final double first = Math.max(1, burstCount);
        return aftershock ? first + 1 + aftershockMore / 100.0 : first;
    }

    /**
     * 戰吼、戰旗、詛咒、印記「全開」時（2026-10，面板的第二個數字）：技能欄上這些技能給自己的增益、敵人受到的傷害增加、
     * 抗性降低，與強化攻擊（每個 [次數, 更多%, 冷卻秒數]）。
     */
    private record FullOn(List<tw.exilecore.stats.Modifier> self, double taken, java.util.Map<tw.exilecore.stats.DamageType, Double> resist,
                          List<double[]> exerts) {
        boolean isEmpty() {
            return self.isEmpty() && taken <= 0 && resist.isEmpty() && exerts.isEmpty();
        }
    }

    private FullOn fullOn(final PlayerState state, final StatSheet sheet) {
        final List<tw.exilecore.stats.Modifier> self = new ArrayList<>();
        double taken = 0;
        final java.util.Map<tw.exilecore.stats.DamageType, Double> resist = new java.util.EnumMap<>(tw.exilecore.stats.DamageType.class);
        final List<double[]> exerts = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        final java.util.Set<String> grantSeen = new java.util.HashSet<>(); // 2026-10：同一顆輔助掛在好幾個戰吼上只算一次
        double bestMarkScore = 0;
        double bestMarkTaken = 0;
        java.util.Map<tw.exilecore.stats.DamageType, Double> bestMarkResist = java.util.Map.of();
        double bestCurseScore = -1;
        double bestCurseTaken = 0;
        java.util.Map<tw.exilecore.stats.DamageType, Double> bestCurseResist = java.util.Map.of();
        for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
            final Resolved resolved = resolve(state, slot);
            if (resolved.isDefault() || !seen.add(resolved.definition().id())) {
                continue;
            }
            final SkillDefinition definition = resolved.definition();
            if (definition.mechanic() == Mechanic.WARCRY && definition.warcry() != null) {
                final SkillBuild build = build(state, sheet, resolved);
                self.addAll(build.warcryOwnModifiers());
                for (final java.util.Map.Entry<String, List<tw.exilecore.stats.Modifier>> grant : build.warcrySupportGrants().entrySet()) {
                    if (grantSeen.add(grant.getKey())) {
                        self.addAll(grant.getValue());
                    }
                }
                taken += definition.warcry().taken().at(build.level()) * build.auraEffect() / 100.0;
                if (definition.warcry().empower() != null) {
                    // 2026-10-26 照 PoE2：強化次數照附近敵人的強度（面板照「一群裡有一隻稀有怪」= 強度 10 估），
                    // 強化的效果換算成大約更多幾 %（額外火焰 x% ≈ 更多 x%、多一次餘震 ≈ 更多 100%）；只算這個戰吼會強化的那一類攻擊
                    final WarcrySpec.Empower empower = definition.warcry().empower();
                    final int count = Math.min(50, definition.warcry().empowersFor(Warcries.TYPICAL_POWER) + build.exerts());
                    final double more = empower.gainFire().at(build.level()) + empower.more().at(build.level()) + 100.0 * empower.aftershocks();
                    if (count > 0 && more > 0) {
                        exerts.add(new double[] {count, more, Math.max(20, build.cooldownTicks()) / 20.0, KINDS.indexOf(empower.kind())});
                    }
                } else if (build.exerts() > 0) {
                    exerts.add(new double[] {build.exerts(), definition.warcry().exertMore().at(build.level()), // 不吃戰吼效果
                            Math.max(20, build.cooldownTicks()) / 20.0, 0});
                }
            } else if (definition.mechanic() == Mechanic.MARK && definition.params().mark() != null) {
                final SkillBuild build = build(state, sheet, resolved);
                final SkillParams.Mark mark = definition.params().mark();
                final double markTaken = mark.taken() / 100.0 * build.auraEffect();
                final java.util.Map<tw.exilecore.stats.DamageType, Double> markResist = new java.util.EnumMap<>(tw.exilecore.stats.DamageType.class);
                for (final ScaledMod effect : mark.effects()) {
                    final tw.exilecore.stats.Modifier mod = effect.toModifier(build.level(), build.auraEffect(), definition.name());
                    if (mod.type() == tw.exilecore.stats.ModType.FLAT && mod.value() < 0 && mod.stat().startsWith("resist.")) {
                        final tw.exilecore.stats.DamageType type = tw.exilecore.stats.DamageType.fromTag(mod.stat().substring("resist.".length()));
                        if (type != null) {
                            markResist.merge(type, -mod.value(), Double::sum);
                        }
                    }
                }
                double score = markTaken;
                for (final double value : markResist.values()) {
                    score += value / 100.0;
                }
                if (mark.curse()) {
                    // 2026-10-22：詛咒一隻敵人同時只能有一個：面板只算效果最大的那個（以前四個詛咒全部加起來）
                    if (score > bestCurseScore) {
                        bestCurseScore = score;
                        bestCurseTaken = markTaken;
                        bestCurseResist = markResist;
                    }
                } else {
                    // 2026-10-22：印記同時只能有一個：面板只算效果最大的那個（以前三個印記全部加起來）
                    if (score > bestMarkScore) {
                        bestMarkScore = score;
                        bestMarkTaken = markTaken;
                        bestMarkResist = markResist;
                    }
                }
            }
        }
        taken += bestMarkTaken + bestCurseTaken;
        bestMarkResist.forEach((type, value) -> resist.merge(type, value, Double::sum));
        bestCurseResist.forEach((type, value) -> resist.merge(type, value, Double::sum));
        return new FullOn(self, taken, resist, exerts);
    }

    /** 敵人抗性降低後，這一下傷害多幾倍（對象原本 0 抗性；每降 1% 抗性多 1%）。 */
    private static tw.exilecore.stats.DamagePacket afterResist(final tw.exilecore.stats.DamagePacket packet,
                                                               final java.util.Map<tw.exilecore.stats.DamageType, Double> resist) {
        if (resist.isEmpty()) {
            return packet;
        }
        final tw.exilecore.stats.DamagePacket out = new tw.exilecore.stats.DamagePacket(packet);
        for (final java.util.Map.Entry<tw.exilecore.stats.DamageType, Double> entry : resist.entrySet()) {
            out.set(entry.getKey(), packet.get(entry.getKey()) * (1 + entry.getValue() / 100.0));
        }
        return out;
    }

    /**
     * 光環現在的樣子（2026-10）：在線的玩家看 {@link AuraService}，網頁計算器的虛擬角色看 {@link #virtualAuras} 算出來的。
     */
    public interface AuraView {
        /** 這個光環（精魂召喚）開著沒。 */
        boolean active(String skillId);

        /** 照隻數算精魂的召喚養得起幾隻（沒開 -1、不是照隻數算的 Integer.MAX_VALUE）。 */
        int minionCap(String skillId);

        /** 召喚物也吃到的光環效果。 */
        List<tw.exilecore.stats.Modifier> minionEffects();
    }

    /**
     * 虛擬角色開著的光環（2026-10，網頁計算器）：照 {@link AuraService#sync} 的規則——綁在按鍵上、能用的光環照按鍵順序開，
     * 精魂不夠的不開。
     *
     * @param self          給自己的效果（含保留的精魂），要加進屬性表
     * @param minionEffects 召喚物也吃到的
     * @param active        開著的（技能代號，照順序）
     * @param skipped       精魂不夠沒開的（名字）
     */
    public record VirtualAuras(List<tw.exilecore.stats.Modifier> self, List<tw.exilecore.stats.Modifier> minionEffects,
                               java.util.Map<String, Integer> minionCaps, List<String> active, List<String> skipped, double reserved)
            implements AuraView {
        @Override
        public boolean active(final String skillId) {
            return active.contains(skillId);
        }

        @Override
        public int minionCap(final String skillId) {
            return minionCaps.getOrDefault(skillId, -1);
        }
    }

    /** 照這張屬性表（不含光環）算虛擬角色會開哪些光環。 */
    public VirtualAuras virtualAuras(final PlayerState state, final StatSheet sheet) {
        final List<tw.exilecore.stats.Modifier> self = new ArrayList<>();
        final List<tw.exilecore.stats.Modifier> minion = new ArrayList<>();
        final java.util.Map<String, Integer> caps = new java.util.LinkedHashMap<>();
        final List<String> active = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        double total = 0;
        double lifeTotal = 0;
        for (final SkillLoadout.Entry entry : state.skills().entriesInKeyOrder()) {
            final SkillDefinition definition = config.skill(entry.skillId());
            if (definition == null || definition.aura() == null || definition.hidden() || active.contains(definition.id())
                    || !AuraService.wearable(state, definition)) {
                continue;
            }
            final SkillBuild build = build(state, sheet, definition, entry);
            final AuraService.Fit fit = AuraService.fit(build, AuraService.capacity(sheet) - total, AuraService.wantedCount(state, definition.id())); // 2026-10-29：玩家設定的數量
            // 2026-10-27 照 PoE2 血契：保留生命付不起的也不開（跟 AuraService 一樣）
            if (fit == null || !AuraService.lifeFits(build, PlayerState.maxLife(sheet), AuraService.lifeLocked(sheet), lifeTotal)) {
                skipped.add(definition.name());
                continue;
            }
            lifeTotal += LineageRules.communionLife(build);
            if (!build.generosity()) {
                self.addAll(build.auraModifiers());
            }
            self.addAll(build.grantModifiers());
            minion.addAll(build.minionAuraModifiers());
            caps.put(definition.id(), fit.minions());
            active.add(definition.id());
            total += fit.reserve();
        }
        if (total > 0) {
            self.add(tw.exilecore.stats.Modifier.flat(StatKeys.SPIRIT_RESERVED, total, "光環保留"));
        }
        if (lifeTotal > 0) {
            self.add(tw.exilecore.stats.Modifier.more(StatKeys.LIFE_MAX, -Math.min(100, lifeTotal) / 100.0, "血契保留"));
        }
        return new VirtualAuras(List.copyOf(self), List.copyOf(minion), java.util.Map.copyOf(caps), List.copyOf(active), List.copyOf(skipped), total);
    }

    private AuraView liveAuras(final Player player) {
        final java.util.UUID uuid = player.getUniqueId();
        return new AuraView() {
            @Override
            public boolean active(final String skillId) {
                return auras.isActive(uuid, skillId);
            }

            @Override
            public int minionCap(final String skillId) {
                return auras.minionCap(uuid, skillId);
            }

            @Override
            public List<tw.exilecore.stats.Modifier> minionEffects() {
                return auras.effectsFor(uuid);
            }
        };
    }

    /**
     * 一個技能的估計每秒傷害（2026-10，網頁計算器）。
     *
     * @param slot      綁在哪個按鍵格（{@link SkillLoadout} 的按鍵格）
     * @param position  技能面板上第幾格
     * @param dps       每秒傷害（同 /stats 的「✦ 技能 每秒」）
     * @param full      全開：戰吼、戰旗、詛咒、印記都開著
     * @param minions   召喚技能：算了幾隻（不是召喚技能是 0）
     * @param hit       平均一下（含暴擊期望；召喚物是一隻的）
     * @param rate      每秒幾下（召喚物是一隻的）
     * @param crit      暴擊率（0–1）
     */
    public record DpsRow(int slot, int position, String skillId, String name, double dps, double full, int minions,
                         double hit, double rate, double crit, double multi) {
    }

    /**
     * 每個技能的估計每秒傷害：{按鍵, 名稱, 每秒傷害, 全開的每秒傷害}（第四格：戰吼、戰旗、詛咒、印記都開著；沒有這些技能時跟第三格一樣）。
     */
    public List<String[]> skillDps(final Player player) {
        final List<String[]> rows = new ArrayList<>();
        for (final DpsRow row : skillDps(plugin.players().get(player), plugin.players().sheet(player), liveAuras(player))) {
            rows.add(new String[] {row.slot() < 0 ? "觸發" : tw.exilecore.character.HotbarLayout.of(plugin.players().get(player)).shortKeyName(row.slot()),
                    row.minions() > 0 ? row.name() + "（召喚物 ×" + row.minions() + "，暴擊 " + ModifierText.number(row.crit() * 100)
                            + "% ×" + ModifierText.number(row.multi()) + "%）" : row.name(),
                    String.valueOf(Math.round(row.dps())), String.valueOf(Math.round(row.full()))});
        }
        return rows;
    }

    /**
     * 2026-10-27：綁在按鍵上的每個技能疊上輔助的屬性表（面板列「打到符合條件的敵人才算」用：掛在輔助上的條件只在這裡看得到）。
     * 算不出來的那格跳過。
     */
    public List<StatSheet> boundSkillSheets(final PlayerState state, final StatSheet sheet) {
        final List<StatSheet> out = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
            final Resolved resolved = resolve(state, slot);
            if (resolved.isDefault() || !seen.add(resolved.definition().id())) {
                continue;
            }
            try {
                out.add(build(state, sheet, resolved).profile().effective(sheet));
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("算技能 {} 的屬性表失敗（按鍵格 {}）", resolved.definition().id(), slot, e);
            }
        }
        return out;
    }

    /**
     * 每個綁在按鍵上的技能的估計每秒傷害（同一個技能綁兩個鍵只算一次）。在線的玩家與虛擬角色共用（2026-10）。
     * 不碰玩家、不碰世界，可以在網頁伺服器的執行緒上算（資料都是唯讀的）。
     */
    public List<DpsRow> skillDps(final PlayerState state, final StatSheet rawSheet, final AuraView view) {
        final StatSheet sheet = withLifeCrit(rawSheet); // 2026-10-26：血鋒照滿血估
        final FullOn full = fullOn(state, sheet);
        final StatSheet fullSheet = full.self().isEmpty() ? sheet : sheet.overlay(full.self());
        final List<DpsRow> rows = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
            final Resolved resolved = resolve(state, slot);
            if (resolved.isDefault() || !seen.add(resolved.definition().id())) {
                continue;
            }
            // 2026-10-24：一個技能算不出來只跳過那一格（以前整組丟例外，面板與網頁那個角色就完全沒有估計）
            try {
                final DpsRow row = dpsOf(state, sheet, fullSheet, full, view, slot, state.skills().bound(slot), resolved);
                if (row != null) {
                    rows.add(row);
                }
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("估計技能 {} 的每秒傷害失敗（按鍵格 {}）", resolved.definition().id(), slot, e);
            }
        }
        try {
            rows.addAll(metaRows(state, sheet, full, view, rows)); // 2026-10-17：主要寶石觸發的法術也算
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().warn("估計主要寶石觸發的每秒傷害失敗", e);
        }
        return rows;
    }

    /** 主要寶石觸發每秒最多算幾次（估計用的上限；2026-10-26 自動觸發已經沒有間隔，照舊用 {@link MetaGems#MIN_GAP} 當估計的上限）。 */
    static final double META_MAX_PER_SECOND = 20.0 / MetaGems.MIN_GAP;

    /**
     * 主要寶石（暴擊時施放、元素異常時施放）觸發的法術的估計每秒傷害（2026-10-17，面板以前完全沒算）：
     * 能量照面板上每秒傷害最高的那個技能（不是召喚物）一直打同等級一般怪物來估——
     * 暴擊時：每秒暴擊次數 × 能量；元素異常時：每秒（點燃＋感電＋冰凍 ×3）次數 × 能量；
     * 每秒觸發 = 每秒能量 ÷ 插槽法術的能量上限，最多 {@link #META_MAX_PER_SECOND} 次，有冷卻的不超過冷卻。
     * 翻滾、被擊中、召喚物死掉、近戰擊殺這幾種看打法，不估；法術圖騰不算（圖騰自己放）。
     * 名字寫成「法術（主要寶石觸發，每秒約 N 次）」，按鍵格是 -1。
     */
    private List<DpsRow> metaRows(final PlayerState state, final StatSheet sheet, final FullOn full, final AuraView view,
                                  final List<DpsRow> rows) {
        DpsRow main = null;
        for (final DpsRow row : rows) {
            final SkillDefinition rowDefinition = config.skill(row.skillId());
            if (rowDefinition != null && (SkillExecutor.hasFlag(rowDefinition, StatKeys.FLAG_TOTEM_LINKED)
                    || config.meta(rowDefinition.id()) instanceof MetaSpec rowMeta && rowMeta.totem())) {
                continue; // 2026-10-18：英靈圖騰、法術圖騰那行是圖騰打的（含好幾根），不當成你自己打的能量來源
            }
            if (row.minions() == 0 && row.rate() > 0 && (main == null || row.dps() > main.dps())) {
                main = row;
            }
        }
        if (main == null) {
            return List.of();
        }
        double[] ailmentsPerHit = null;
        final List<DpsRow> out = new ArrayList<>();
        for (int position = 0; position < SkillLoadout.MAX_SKILLS; position++) {
            final SkillLoadout.Entry entry = state.skills().skill(position);
            final MetaSpec spec = entry == null ? null : config.meta(entry.skillId());
            if (spec == null || spec.totem() || !view.active(entry.skillId())) {
                continue;
            }
            final MetaGems.Socket socket = metas.socket(state, position);
            if (socket == null) {
                continue;
            }
            final double events;
            if (spec.kind() == MetaSpec.Kind.CRIT) {
                // 2026-10-26：暴擊引星照打掉異常門檻的 % 給能量；估計時當每下打掉 10%（＝這次之前固定的每點強度 10）
                events = main.rate() * main.crit() * MetaGems.CRIT_PERCENT_FALLBACK;
            } else if (spec.kind() == MetaSpec.Kind.AILMENT) {
                if (ailmentsPerHit == null) {
                    ailmentsPerHit = mainAilments(state, sheet, main);
                }
                // 2026-10-26 照 PoE2：冰凍 ×10（這次之前 ×3）；點燃照打掉異常門檻的 %，估計時當 10%（跟暴擊一樣）
                events = main.rate() * (ailmentsPerHit[0] * MetaGems.CRIT_PERCENT_FALLBACK + ailmentsPerHit[1] + 10 * ailmentsPerHit[2]);
            } else {
                continue;
            }
            final double energy = events * spec.energy() * (1 + spec.energyMore().at(metas.usableLevel(state, entry)) / 100.0);
            // 2026-10-26 照 PoE2：上限是所有插槽法術加起來，滿了全部一起觸發
            final double cycles = Math.min(META_MAX_PER_SECOND, energy / socket.maxEnergy());
            final SkillDefinition metaDefinition = config.skill(entry.skillId());
            for (int si = 0; si < socket.spells().size(); si++) {
                final SkillDefinition spell = socket.spells().get(si);
                if (!spell.dealsDamage()) {
                    continue;
                }
                SkillBuild build = metaBuild(state, sheet, entry, metaDefinition, spell, socket.spellEntries().get(si)); // 2026-10-26：跟真的觸發一樣算輔助
                double triggers = cycles;
                if (build.cooldownTicks() > 0) {
                    triggers = Math.min(triggers, 20.0 / build.cooldownTicks());
                }
                if (triggers <= 0) {
                    continue;
                }
                final String metaName = metaDefinition == null ? "主要寶石" : metaDefinition.name();
                build = build.withProfile(build.profile().scaled((1 - spec.lessDamage() / 100.0) * (1 + spec.moreDamage() / 100.0), metaName));
                final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, build.profile());
                final double crit = DamageCalculator.critChance(sheet, build.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
                final double multi = DamageCalculator.critMultiplier(build.profile().effective(sheet), build.profile().tags(), null) * 100;
                final double critFactor = 1 + crit * (multi / 100.0 - 1);
                final SkillParams params = build.params();
                // 被觸發的只放一次（複擊、雙詠、虛星分身的重複只有自己按的時候才有）
                final double hitRate = triggers * castHits(params.burst() == null ? 1 : params.burst().count(), params.aftershock() != null,
                        params.aftershock() == null ? 0 : params.aftershock().more());
                double dps = expected.total() * critFactor * hitRate;
                if (plugin.ailments() != null) {
                    dps = plugin.ailments().estimateDps(sheet, build.profile(), expected, critFactor, hitRate, state.level(), dps);
                }
                final double fullDps = afterResist(expected, full.resist()).total() / Math.max(1e-9, expected.total()) * dps * (1 + full.taken());
                out.add(new DpsRow(-1, socket.metaPosition() + 1, spell.id(),
                        spell.name() + "（" + metaName + "觸發，每秒約 " + ModifierText.number(triggers) + " 次）",
                        dps, Math.max(dps, fullDps), 0, expected.total() * critFactor, hitRate, crit, multi));
            }
        }
        return out;
    }

    /** 能量來源那個技能一下平均造成幾次 {點燃, 感電, 冰凍}。 */
    private double[] mainAilments(final PlayerState state, final StatSheet sheet, final DpsRow main) {
        final SkillLoadout.Entry entry = main.position() < 0 ? null : state.skills().skill(main.position());
        final SkillDefinition definition = entry == null ? null : config.skill(entry.skillId());
        if (definition == null || plugin.ailments() == null) {
            return new double[3];
        }
        final SkillBuild build = build(state, sheet, definition, entry);
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, build.profile());
        final double critFactor = 1 + main.crit() * (main.multi() / 100.0 - 1);
        return plugin.ailments().ailmentsPerHit(sheet, build.profile(), expected, critFactor, state.level());
    }

    /**
     * 技能面板上第 position 格的技能，照這張屬性表算的每秒傷害（網頁計算器比較輔助寶石用：技能可以沒綁鍵）。
     * 不造成傷害的技能回傳 null。
     */
    public DpsRow skillDpsAt(final PlayerState state, final StatSheet rawSheet, final AuraView view, final int position) {
        final StatSheet sheet = withLifeCrit(rawSheet); // 2026-10-26：血鋒照滿血估
        final SkillLoadout.Entry entry = state.skills().skill(position);
        final SkillDefinition definition = entry == null ? null : config.skill(entry.skillId());
        if (definition == null) {
            return null;
        }
        final FullOn full = fullOn(state, sheet);
        final StatSheet fullSheet = full.self().isEmpty() ? sheet : sheet.overlay(full.self());
        int slot = -1;
        for (int i = 0; i < SkillLoadout.BIND_SLOTS; i++) {
            if (state.skills().bound(i) == position) {
                slot = i;
                break;
            }
        }
        return dpsOf(state, sheet, fullSheet, full, view, slot, position, new Resolved(definition, entry));
    }

    /**
     * 2026-10-26：血鋒（每 50 目前生命增加 x% 暴擊傷害加成）在戰鬥裡是擊中時照當下生命算（CombatListener），屬性表裡沒有；
     * 面板、網頁的估計照滿血（最大生命）算一份疊上去，配了才看得到效果。
     */
    static StatSheet withLifeCrit(final StatSheet sheet) {
        // 2026-10-27：一直生效、擊中時才算的也照滿血估：季節循環、穿透、痛極生力（滿血＝暴擊傷害加成更少 30%）；這次之前沒算
        final List<Modifier> extra = new ArrayList<>(tw.exilecore.character.BuffImpact.estimateModifiers(sheet, 1.0));
        final double perLife = sheet.flat(StatKeys.CRIT_BONUS_PER_LIFE, Set.of());
        final double life = sheet.get(StatKeys.LIFE_MAX);
        if (perLife > 0 && life >= 50) {
            extra.add(Modifier.increased(StatKeys.CRIT_BONUS, Math.floor(life / 50) * perLife / 100.0, "血鋒（照滿血估）"));
        }
        return extra.isEmpty() ? sheet : sheet.overlay(extra);
    }

    private DpsRow dpsOf(final PlayerState state, final StatSheet sheet, final StatSheet fullSheet, final FullOn full, final AuraView view,
                         final int slot, final int position, final Resolved resolved) {
        final SkillDefinition definition = resolved.definition();
        if (definition.summon() != null) {
            // 2026-10：召喚技能算召喚物的（每隻每秒傷害 × 同時最多幾隻；精魂召喚要開著才算）
            final SkillBuild build = build(state, sheet, resolved);
            if (definition.mechanic() == Mechanic.AURA && !view.active(definition.id())) {
                return null;
            }
            final double[] each = minionDps(state.level(), view.minionEffects(), build, sheet);
            final int cap = view.minionCap(definition.id()); // 2026-10：照隻數算精魂的，只算養得起的隻數
            final int count = Math.max(1, cap > 0 ? Math.min(build.minions(), cap) : build.minions());
            if (each[0] <= 0) {
                return null;
            }
            // 全開：召喚物吃不到你的戰吼增益（只吃「召喚物…」的加成），只算敵人受到的傷害增加
            final double fullEach = each[0] * (1 + full.taken());
            return new DpsRow(slot, position, definition.id(), definition.name(), each[0] * count, Math.max(each[0], fullEach) * count, count,
                    each[1], each[2], each[3], each[4]);
        }
        if (!resolved.isDefault() && SkillExecutor.hasFlag(definition, StatKeys.FLAG_TOTEM_LINKED)) {
            // 2026-10-18：英靈圖騰自己不打，算英靈用下一格錘子技能打的（以前只算圖騰本身那一下，面板低了好幾百倍）
            final DpsRow ancestors = ancestralDps(state, sheet, fullSheet, full, slot, position, resolved);
            if (ancestors != null) {
                return ancestors;
            }
        }
        if (!resolved.isDefault() && config.meta(definition.id()) instanceof MetaSpec metaSpec && metaSpec.totem()) {
            // 2026-10-21：法術圖騰（星咒圖騰）自己不打，算圖騰替你放插槽法術的（以前整行沒有，面板少了好幾十倍）
            return spellTotemDps(state, sheet, full, slot, position, resolved, metaSpec);
        }
        if (!definition.dealsDamage() || definition.mechanic() == Mechanic.AURA) {
            return null;
        }
        final DpsRow mainHand = handDps(state, sheet, fullSheet, full, slot, position, resolved, null);
        // 2026-10-27 照 PoE2 雙持（巨人之血）：攻擊輪流用主手、副手出手（跟施放時一樣），兩手的一下合起來照時間平均；
        // 這次之前只算主手
        final tw.exilecore.character.DualWield.OffHand offHand = state.offHandAttack();
        if (tw.exilecore.character.DualWield.usable(offHand, definition.isAttack(), offHand != null && definition.usableWith(offHand.tags()))) {
            return alternate(mainHand, handDps(state, sheet, fullSheet, full, slot, position, resolved, offHand));
        }
        return mainHand;
    }

    /**
     * 2026-10-27：雙持輪流出手的平均：兩手各打一下花的時間合起來（1／每秒次數），傷害合起來除以時間。純函式（測試用）。
     * 有一手算不出來（每秒 0 次）就用另一手。
     */
    static DpsRow alternate(final DpsRow main, final DpsRow off) {
        if (off == null || off.rate() <= 0) {
            return main;
        }
        if (main == null || main.rate() <= 0) {
            return off;
        }
        final double tMain = 1 / main.rate();
        final double tOff = 1 / off.rate();
        final double time = tMain + tOff;
        final double dps = (main.dps() * tMain + off.dps() * tOff) / time;
        final double full = (main.full() * tMain + off.full() * tOff) / time;
        return new DpsRow(main.slot(), main.position(), main.skillId(), main.name() + "（主副手輪流）", dps, Math.max(dps, full), 0,
                (main.hit() + off.hit()) / 2, 2 / time, (main.crit() + off.crit()) / 2, (main.multi() + off.multi()) / 2);
    }

    /** 一般技能的每秒傷害；off 不是 null＝這一下改成副手出手（雙持）。 */
    private DpsRow handDps(final PlayerState state, final StatSheet sheet, final StatSheet fullSheet, final FullOn full,
                           final int slot, final int position, final Resolved resolved, final tw.exilecore.character.DualWield.OffHand off) {
        final SkillDefinition definition = resolved.definition();
        // 2026-10-27：血滲、傲寒（放的時候 LineageMechanics.prepare 才加）照滿魔力估；這次之前面板、網頁沒算
        final SkillBuild build = LineageMechanics.estimated(onHand(build(state, sheet, resolved), off),
                tw.exilecore.character.PlayerState.maxMana(sheet));
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, build.profile());
        final double crit = DamageCalculator.critChance(sheet, build.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
        final double multi = DamageCalculator.critMultiplier(build.profile().effective(sheet), build.profile().tags(), null) * 100; // 2026-10：跟戰鬥一樣算「增加／更多暴擊加成」
        // 有冷卻的：一輪要等「施放間隔」和「冷卻」比較長的那個。
        // 2026-10：複擊的每一下都要花一次攻擊間隔（打完才能做別的），以前當成同一個間隔裡打完，面板高了好幾倍
        final SkillParams params = build.params();
        final double critFactor = 1 + crit * (multi / 100.0 - 1);
        // 2026-10-24：圖騰（弩台等）算圖騰自己射的（每根每秒幾次 × 同時幾根，吃攻速）；以前算成放圖騰的速度（被冷卻卡住），攻速、圖騰數都不會動
        final double totemRate = totemFireRate(build, sheet);
        final double hitRate = (totemRate > 0 ? totemRate * build.totems() : hitsPerSecond(repeatGap(build, build.intervalTicks(sheet)), build.cooldownTicks(), build.repeats()))
                * castHits(params.burst() == null ? 1 : params.burst().count(), params.aftershock() != null,
                        params.aftershock() == null ? 0 : params.aftershock().more());
        // 2026-10-24：補齊以前漏算的（實測比估計高 6～20 倍的來源）：雙詠的第二發、蓄力層（平常就會有的）
        final double[] always = alwaysMultipliers(build, sheet);
        final DamagePacket expectedScaled = new DamagePacket(expected).scale(always[1]);
        final double rateScaled = hitRate * always[0];
        // 2026-10-27：「擊中傷害更少」（燎原 II、血崩、焚天之怒、環壁 II…）只乘擊中，異常照沒減的那一下算（跟 CombatListener 一樣）；這次之前面板沒乘
        final double hitOnly = SupportMechanics.hitOnlyFactor(sheet, build.profile());
        double dps = expectedScaled.total() * critFactor * rateScaled * hitOnly;
        if (plugin.ailments() != null) {
            // 2026-10：面板以前只算擊中；點燃、流血、中毒的持續傷害與感電的增傷也算進去（對同等級一般怪物、站著不動）
            dps = plugin.ailments().estimateDps(sheet, build.profile(), expectedScaled, critFactor, rateScaled, state.level(), dps);
        }
        double fullDps = dps;
        {
            // 全開：用疊上戰吼增益的屬性表重算一次（攻速、傷害都會變），再算敵人的抗性降低、受到的傷害增加、強化攻擊
            // 2026-10-24：沒有戰吼也要走這段（投射物全中、灌注、銳星連打這些「全開」才算的乘區）
            final SkillBuild fullBuild = LineageMechanics.estimated(onHand(build(state, fullSheet, resolved), off),
                    tw.exilecore.character.PlayerState.maxMana(fullSheet)); // 2026-10-27：同上（血滲、傲寒）
            final tw.exilecore.stats.DamagePacket fullExpected = afterResist(
                    DamageCalculator.expectedOutgoing(fullSheet, fullBuild.profile()), full.resist());
            final double fullCrit = DamageCalculator.critChance(fullSheet, fullBuild.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
            final double fullMulti = DamageCalculator.critMultiplier(fullBuild.profile().effective(fullSheet), fullBuild.profile().tags(), null) * 100;
            final double fullCritFactor = 1 + fullCrit * (fullMulti / 100.0 - 1);
            final SkillParams fullParams = fullBuild.params();
            final double fullTotemRate = totemFireRate(fullBuild, fullSheet); // 2026-10-24：同上
            final double casts = fullTotemRate > 0 ? fullTotemRate * fullBuild.totems()
                    : hitsPerSecond(repeatGap(fullBuild, fullBuild.intervalTicks(fullSheet)), fullBuild.cooldownTicks(), fullBuild.repeats());
            final double fullRate = casts * castHits(fullParams.burst() == null ? 1 : fullParams.burst().count(), fullParams.aftershock() != null,
                    fullParams.aftershock() == null ? 0 : fullParams.aftershock().more());
            double exert = 1.0;
            if (fullBuild.profile().isAttack()) {
                // 強化：每次戰吼冷卻好就吼一次，接下來幾次攻擊更痛；攻擊比強化的次數多時照比例平均
                for (final double[] each : full.exerts()) {
                    if (!exertFits((int) each[3], fullBuild.definition())) {
                        continue; // 2026-10-26：這個戰吼不強化這一類攻擊（焚獄只強化近戰、震地只強化猛擊…）
                    }
                    final double attacksPerCycle = Math.max(1e-6, casts * each[2]);
                    exert *= 1 + each[1] / 100.0 * Math.min(1, each[0] / attacksPerCycle);
                }
            }
            // 2026-10-24：全開再加「打起來才有的」：投射物貼臉全中、灌注滿層、瞬影擊滿銳星連打
            final double[] fullAlways = alwaysMultipliers(fullBuild, fullSheet);
            final double[] fullOnly = fullOnlyMultipliers(fullBuild, fullSheet);
            final double fullRateScaled = fullRate * fullAlways[0] * fullOnly[0];
            final DamagePacket fullScaled = new tw.exilecore.stats.DamagePacket(fullExpected).scale(exert * fullAlways[1] * fullOnly[1]);
            fullDps = fullScaled.total() * fullCritFactor * fullRateScaled * SupportMechanics.hitOnlyFactor(fullSheet, fullBuild.profile()); // 2026-10-27：同上（只減擊中）
            if (plugin.ailments() != null) {
                fullDps = plugin.ailments().estimateDps(fullSheet, fullBuild.profile(), fullScaled, fullCritFactor, fullRateScaled,
                        state.level(), fullDps);
            }
            fullDps *= 1 + full.taken();
        }
        // 2026-10-27：掠獸（只能傷害低血的敵人）：數字照能打的時候算，名字標清楚
        final String name = StatKeys.flag(build.profile().effective(sheet), tw.exilecore.stats.LineageKeys.LOW_LIFE_ONLY)
                ? definition.name() + "（掠獸：只傷害生命 35% 以下的敵人）" : definition.name();
        return new DpsRow(slot, position, definition.id(), name, dps, Math.max(dps, fullDps), 0,
                expectedScaled.total() * critFactor * hitOnly, rateScaled, crit, multi);
    }

    /** 2026-10-27：雙持時改成副手出手的技能（跟施放時 DualWield.offHand 一樣）；off 是 null 照原樣。 */
    private static SkillBuild onHand(final SkillBuild build, final tw.exilecore.character.DualWield.OffHand off) {
        return off == null ? build : build.withProfile(tw.exilecore.character.DualWield.offHand(build.profile(), off));
    }

    /**
     * 估計時平常就會有、以前沒算的乘區（2026-10-24）：回傳 {每秒次數的倍率, 每一下傷害的倍率}。
     * <ul>
     *   <li>雙詠：法術自動再放一次（傷害已經在輔助裡少 25%）→ 次數 ×2。</li>
     *   <li>持續引導的蓄力層（熾息）：按住放到滿層每層更多 x%，照滿層算。</li>
     * </ul>
     */
    private static double[] alwaysMultipliers(final SkillBuild build, final StatSheet sheet) {
        double rate = 1;
        double damage = 1;
        if (build.echo()) {
            rate *= 2;
        } else {
            rate *= 1 + Math.max(0, build.qualityFlat(tw.exilecore.stats.GemQualityKeys.ECHO_CHANCE)) / 100.0; // 2026-10-28 照 PoE2 Comet 的品質：機率回響
        }
        final StatSheet effective = build.profile().effective(sheet);
        final int stages = (int) effective.flat(StatKeys.CHANNEL_STAGES, Set.of());
        if (stages > 1) {
            damage *= 1 + Math.max(0, effective.flat(StatKeys.CHANNEL_MORE, Set.of()) * (stages - 1) / 100.0);
        }
        damage *= 1 + Burgeon.more(effective, 60) / 100.0; // 2026-10-27 漸熾：照引導滿（最多那一段）算
        return new double[] {rate, damage};
    }

    /**
     * 只在「全開」算的乘區（2026-10-24，打起來才有的）：回傳 {每秒次數的倍率, 每一下傷害的倍率}。
     * <ul>
     *   <li>投射物好幾發：貼著敵人放全部打中（分光、星辰萬象）→ 次數 × 發數。</li>
     *   <li>元素灌注：有「用掉灌注」的技能，照三種灌注都滿（每種各一層）算更多傷害與額外元素傷害。</li>
     *   <li>瞬影擊類（flicker）：滿銳星時每顆多連打 2 下、速度更多 285%（SkillExecutor），一輪 = 本體一下＋連打，照一輪的時間平均。</li>
     * </ul>
     */
    private double[] fullOnlyMultipliers(final SkillBuild build, final StatSheet sheet) {
        double rate = 1;
        double damage = 1;
        final SkillDefinition definition = build.definition();
        final StatSheet effective = build.profile().effective(sheet);
        final Set<String> tags = build.profile().tags();
        if (definition.mechanic() == Mechanic.PROJECTILE && build.projectiles() > 1 && !build.params().blink()) {
            rate *= build.projectiles();
        }
        if (effective.flat(StatKeys.INFUSION_CONSUME, tags) > 0 && effective.flat(tw.exilecore.charge.ChargeService.SUP_INHIBIT, tags) <= 0) {
            final int used = 3;
            damage *= 1 + Math.max(0, effective.flat(StatKeys.INFUSION_MORE, tags) * used / 100.0);
            damage *= 1 + Math.max(0, effective.flat(StatKeys.INFUSION_EXTRA, tags) * used / 100.0); // 額外元素傷害（沒算抗性）
        }
        if (definition.mechanic() == Mechanic.FLICKER && plugin.combat() != null && plugin.combat().charges() != null) {
            int powerMax = 0;
            for (final tw.exilecore.charge.ChargeType type : plugin.combat().charges().config().types()) {
                if ("power".equals(type.id())) {
                    powerMax = tw.exilecore.charge.ChargeService.maxOf(type, effective);
                }
            }
            if (powerMax > 0) {
                final double interval = build.intervalTicks(sheet);
                final double cycle = Math.max(interval, build.cooldownTicks());
                final int extra = Math.min(SkillExecutor.FLICKER_MAX_CHAIN, SkillExecutor.FLICKER_PER_CHARGE * powerMax);
                final double step = Math.max(2, Math.round(interval / (1 + SkillExecutor.FLICKER_MORE_SPEED)));
                rate *= (1 + extra) * cycle / (cycle + extra * step);
            }
        }
        return new double[] {rate, damage};
    }

    /**
     * 法術圖騰的估計每秒傷害（2026-10-21，照英靈圖騰的算法）：插槽法術（含它自己的輔助、帶到圖騰施法上的輔助、施法速度更少）平均一下
     * × 每根每秒放幾次 × 同時幾根。銳星照全用來算（每顆更多傷害）；對異常敵人的更多傷害、詛咒、共鳴這類看戰況的不算。
     * 插槽沒放法術、或插槽法術不能交給圖騰的回傳 null。
     */
    private DpsRow spellTotemDps(final PlayerState state, final StatSheet sheet, final FullOn full, final int slot, final int position,
                                 final Resolved resolved, final MetaSpec spec) {
        final int at = position >= 0 ? position : state.skills().find(resolved.definition().id());
        final MetaGems.Socket socket = at < 0 ? null : metas.socket(state, at);
        if (socket == null || !socket.spell().dealsDamage()) {
            return null;
        }
        final SkillBuild metaBuild = build(state, sheet, resolved);
        // 2026-10-27 照 PoE2：圖騰輪流放孔裡的每個法術——每個法術各算（好像只有它），再照各自放一次的時間加權平均
        final List<SkillBuild> totems = metas.totemBuilds(state, sheet, socket, metaBuild.level(), spec.charges(), 0);
        final SkillBuild first = totems.getFirst();
        if (first.deploy() == null || first.deploy().interval() <= 0) {
            return null;
        }
        final double up = totemsUp(first.totems(), first.durationTicks(first.deploy().duration()), metaBuild.intervalTicks(sheet),
                metaBuild.cooldownTicks());
        double weighted = 0;
        double weightedFull = 0;
        double perHit = 0;
        double totalTicks = 0;
        double hitRate = 0;
        double crit = 0;
        double multi = 0;
        for (final SkillBuild totem : totems) {
            if (totem.deploy() == null || totem.deploy().interval() <= 0) {
                continue;
            }
            final double ticks = SkillDeployables.totemTicks(totem, sheet, totem.totems()); // 2026-10-26：含品質的每根圖騰施法速度（照滿圖騰算）
            final double perTotem = 20.0 / ticks;
            final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, totem.profile());
            final double c = DamageCalculator.critChance(sheet, totem.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
            final double m = DamageCalculator.critMultiplier(totem.profile().effective(sheet), totem.profile().tags(), null) * 100;
            final double critFactor = 1 + c * (m / 100.0 - 1);
            final SkillParams params = totem.params();
            final double rate = perTotem * up * castHits(params.burst() == null ? 1 : params.burst().count(), params.aftershock() != null,
                    params.aftershock() == null ? 0 : params.aftershock().more());
            double dps = expected.total() * critFactor * rate;
            if (plugin.ailments() != null) {
                dps = plugin.ailments().estimateDps(sheet, totem.profile(), expected, critFactor, rate, state.level(), dps);
            }
            final double fullDps = expected.total() <= 0 ? dps
                    : afterResist(expected, full.resist()).total() / expected.total() * dps * (1 + full.taken());
            weighted += dps * ticks;
            weightedFull += Math.max(dps, fullDps) * ticks;
            perHit += expected.total() * critFactor * ticks;
            hitRate += rate * ticks;
            crit += c * ticks;
            multi += m * ticks;
            totalTicks += ticks;
        }
        if (totalTicks <= 0) {
            return null;
        }
        final double dps = weighted / totalTicks;
        final String name = resolved.definition().name() + "（圖騰" + (totems.size() > 1 ? "輪流" : "") + "放" + socket.names()
                + " ×" + ModifierText.number(up) + " 根）";
        return new DpsRow(slot, position, resolved.definition().id(), name, dps, Math.max(dps, weightedFull / totalTicks), 0,
                perHit / totalTicks, hitRate / totalTicks, crit / totalTicks, multi / totalTicks);
    }

    /** 一根英靈圖騰每秒叫幾次英靈（2026-10-21，跟 {@link SkillDeployables#ancestorTicks} 一樣：錘子技能攻擊時間 ×1.5 ÷ 攻速）。 */
    static double ancestorCallsPerSecond(final SkillBuild totem, final SkillBuild linked, final StatSheet sheet) {
        return 20.0 / SkillDeployables.ancestorTicks(totem, linked, sheet);
    }

    /** 2026-10-18：同時立著幾根圖騰：上限與「持續時間 ÷ 放一根要多久（施放間隔與冷卻較長的）」比較小的那個，至少 1。 */
    static double totemsUp(final int totems, final double durationTicks, final double placeIntervalTicks, final double cooldownTicks) {
        final double cycle = Math.max(1, Math.max(placeIntervalTicks, cooldownTicks));
        return Math.max(1, Math.min(Math.max(1, totems), durationTicks / cycle));
    }

    /**
     * 英靈圖騰的估計每秒傷害（2026-10-18）：下一格錘子技能（含它的輔助、餘震）平均一下 × 每根每秒叫幾次英靈 × 同時幾根；
     * 被叫出的英靈只打一次（沒有複擊、不吃戰吼強化）。下一格不是能連的錘子技能時回傳 null（照舊算圖騰本身）。
     * 不含你自己按那個錘子技能打的（那個技能有綁鍵的話自己另外一行）。
     */
    private DpsRow ancestralDps(final PlayerState state, final StatSheet sheet, final StatSheet fullSheet, final FullOn full,
                                final int slot, final int position, final Resolved resolved) {
        final SkillDefinition definition = resolved.definition();
        if (!(linkedMace(state, sheet, definition) instanceof SkillBuild rawLinked)) {
            return null;
        }
        final SkillBuild linked = SkillDeployables.ancestorHits(rawLinked); // 2026-10-21：英靈的那一下吃圖騰傷害
        final SkillBuild totem = build(state, sheet, resolved);
        if (totem.deploy() == null) {
            return null;
        }
        final double up = totemsUp(totem.totems(), totem.durationTicks(totem.deploy().duration()), totem.intervalTicks(sheet), totem.cooldownTicks());
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, linked.profile());
        final double crit = DamageCalculator.critChance(sheet, linked.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
        final double multi = DamageCalculator.critMultiplier(linked.profile().effective(sheet), linked.profile().tags(), null) * 100;
        final double critFactor = 1 + crit * (multi / 100.0 - 1);
        final SkillParams params = linked.params();
        final double hitRate = ancestorCallsPerSecond(totem, linked, sheet) * up
                * castHits(params.burst() == null ? 1 : params.burst().count(), params.aftershock() != null,
                        params.aftershock() == null ? 0 : params.aftershock().more());
        double dps = expected.total() * critFactor * hitRate;
        if (plugin.ailments() != null) {
            dps = plugin.ailments().estimateDps(sheet, linked.profile(), expected, critFactor, hitRate, state.level(), dps);
        }
        double fullDps = dps;
        if (!full.isEmpty() && linkedMace(state, fullSheet, definition) instanceof SkillBuild rawFullLinked) {
            final SkillBuild fullLinked = SkillDeployables.ancestorHits(rawFullLinked);
            // 全開：同 dpsOf，用疊上戰吼增益的屬性表重算（攻速變快英靈也叫得快），再算抗性降低、受到的傷害增加；英靈不吃強化
            final SkillBuild fullTotem = build(state, fullSheet, resolved);
            final double fullUp = totemsUp(fullTotem.totems(), fullTotem.durationTicks(totem.deploy().duration()),
                    fullTotem.intervalTicks(fullSheet), fullTotem.cooldownTicks());
            final DamagePacket fullExpected = afterResist(DamageCalculator.expectedOutgoing(fullSheet, fullLinked.profile()), full.resist());
            final double fullCrit = DamageCalculator.critChance(fullSheet, fullLinked.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
            final double fullMulti = DamageCalculator.critMultiplier(fullLinked.profile().effective(fullSheet), fullLinked.profile().tags(), null) * 100;
            final double fullCritFactor = 1 + fullCrit * (fullMulti / 100.0 - 1);
            final SkillParams fullParams = fullLinked.params();
            final double fullRate = ancestorCallsPerSecond(fullTotem, fullLinked, fullSheet) * fullUp
                    * castHits(fullParams.burst() == null ? 1 : fullParams.burst().count(), fullParams.aftershock() != null,
                            fullParams.aftershock() == null ? 0 : fullParams.aftershock().more());
            fullDps = fullExpected.total() * fullCritFactor * fullRate;
            if (plugin.ailments() != null) {
                fullDps = plugin.ailments().estimateDps(fullSheet, fullLinked.profile(), fullExpected, fullCritFactor, fullRate, state.level(), fullDps);
            }
            fullDps *= 1 + full.taken();
        }
        final String name = definition.name() + "（含英靈用" + linked.definition().name() + " ×" + ModifierText.number(up) + " 根）";
        return new DpsRow(slot, position, definition.id(), name, dps, Math.max(dps, fullDps), 0,
                expected.total() * critFactor, hitRate, crit, multi);
    }

    /**
     * 一隻召喚物的估計：{每秒傷害, 平均一下（含暴擊期望）, 每秒幾下, 暴擊率, 暴擊傷害 %}（同召喚物說明的算法）；沒有怪物模組全部是 0。
     *
     * @param ownerLevel  主人的等級
     * @param auraEffects 召喚物吃到的光環效果
     */
    double[] minionDps(final int ownerLevel, final List<tw.exilecore.stats.Modifier> auraEffects, final SkillBuild build, final StatSheet ownerSheet) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null || plugin.monsters() == null) {
            return new double[5];
        }
        final tw.exilecore.monster.MonsterConfig monsters = plugin.monsters().config();
        final MinionSheets.Base base = MinionSheets.base(config, spec, build.level(), ownerLevel, monsters::lifeAt, monsters::damageAt,
                monsters::accuracyAt, monsters::evasionAt, monsters::armourAt, monsters.critChance()); // 2026-10-26 照 PoE2 的召喚物表
        final List<Modifier> ownerModifiers = new ArrayList<>(MinionSheets.forMinion(MinionSheets.ownerModifiers(ownerSheet),
                MinionSheets.conditions(build.definition(), true))); // 2026-10-26：估計時當作在存在範圍內（夥伴照技能）
        ownerModifiers.addAll(auraEffects);
        // 2026-10-27：看看我怎麼辦事的、巨伴、夥伴獲得你的力量（照主人屬性表，當作在存在範圍內；跟戰鬥同一套）；這次之前 /stats、網頁沒算
        ownerModifiers.addAll(MinionEstimates.estimate(build, ownerSheet,
                MinionSheets.poe2Factors(config, build.level(), ownerLevel, monsters::lifeAt, monsters::damageAt)[1]));
        final StatSheet minionSheet = MinionSheets.build(spec, base, ownerModifiers);
        final tw.exilecore.stats.SkillProfile profile = MinionSheets.profile(build);
        final StatSheet effective = profile.effective(minionSheet);
        final DamagePacket expected = DamageCalculator.expectedOutgoing(minionSheet, profile);
        if (expected.isEmpty()) {
            return new double[5];
        }
        final double crit = DamageCalculator.critChance(minionSheet, profile);
        final double multi = DamageCalculator.critMultiplier(effective, profile.tags(), null) * 100; // 2026-10：含召喚物的「增加暴擊加成」
        final double hit = expected.total() * (1 + crit * (multi / 100.0 - 1));
        final double rate = MinionSheets.attacksPerSecond(effective, profile);
        return new double[] {hit * rate, hit, rate, crit, multi};
    }

    /** 這個按鍵這次會放出的數值（含輔助寶石、角色等級的限制）。 */
    SkillBuild build(final PlayerState state, final StatSheet sheet, final Resolved resolved) {
        if (resolved.isDefault()) {
            return SkillBuild.of(config, resolved.definition(), 1, state.level(), true, List.of(), state.weaponTags(), sheet);
        }
        return build(state, sheet, resolved.definition(), resolved.entry());
    }

    /** 2026-10-28 照 PoE2（melee_conditional_step_distance）：技能說明裡的近戰條件位移；沒有回傳 null。 */
    static String stepLine(final MeleeStep.Spec step) {
        if (step == null) {
            return null;
        }
        return String.format(Locale.ROOT, "<gray>出手前往前踏一步：最多 <white>%s</white> 格%s", trimNumber(step.distance()),
                step.conditional() ? "（準心的敵人已經打得到就不動，走到打得到就停；沒有目標就朝準心指的地面）" : "");
    }

    /**
     * 2026-10-28 照 PoE2：技能說明裡每一段（skills.yml 的 stages）自己的傷害效益、轉換、半徑、攻速（這一級）。
     */
    static List<String> stageLines(final SkillBuild build) {
        final List<String> lines = new ArrayList<>();
        for (final SkillStages.Stage stage : SkillStages.of(build.definition().id()).values()) {
            final StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "<gray>%s：傷害效益 <white>%d%%",
                    ItemRenderer.escape(stage.name()), Math.round(stage.effectiveness().at(build.level()) * 100)));
            if (stage.hits() > 1) {
                line.append(String.format(Locale.ROOT, "<gray> × <white>%d</white> 下", stage.hits()));
            }
            if (stage.radius(build.level()) > 0) {
                line.append(String.format(Locale.ROOT, "<gray>，半徑 <white>%.1f</white> 格", build.scaledRadius(stage.radius())));
            }
            if (stage.speed() != 0) {
                line.append(String.format(Locale.ROOT, "<gray>，攻擊速度%s <white>%d%%", stage.speed() > 0 ? "更多" : "更少",
                        Math.round(Math.abs(stage.speed()))));
            }
            if (stage.reach() != 0) {
                line.append(String.format(Locale.ROOT, "<gray>，打擊距離 +<white>%.1f</white> 格", stage.reach()));
            }
            lines.add(line.toString());
            stage.conversion().forEach((type, fraction) -> lines.add(String.format(Locale.ROOT,
                    "<dark_gray>　%s：%d%% 物理傷害轉換為%s傷害", ItemRenderer.escape(stage.name()), Math.round(fraction * 100), type.displayName())));
        }
        return lines;
    }

    /** 面板上（或寶石裡）的一個技能在這個角色身上的數值。 */
    SkillBuild build(final PlayerState state, final StatSheet sheet, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        // 2026-10-29 照 PoE2：巨靈的指令是巨靈放的——範圍、持續時間、冷卻回復、暴擊、傷害照巨靈（召喚物）的屬性表；
        //   魔力消耗是你付的，照你的（施法動作的時間由呼叫的地方用你的屬性表算 intervalTicks）。巨靈沒叫出來照舊用你的
        final StatSheet djinn = djinnSheet(state, definition);
        if (djinn != null && djinn != sheet) {
            final SkillBuild yours = buildCached(state, sheet, definition, entry);
            return buildCached(state, djinn, definition, entry).withManaCost(yours.manaCost());
        }
        return buildCached(state, sheet, definition, entry);
    }

    /** 2026-10-29：巨靈指令用的屬性表（那位巨靈的；不是巨靈的指令、或巨靈沒叫出來回傳 null）。 */
    StatSheet djinnSheet(final PlayerState state, final SkillDefinition definition) {
        final String djinn = definition == null ? null : DjinnCommands.djinnFor(definition.id());
        return djinn == null || state == null || minions == null ? null : minions.djinnSheet(state.uuid(), djinn);
    }

    private SkillBuild buildCached(final PlayerState state, final StatSheet sheet, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        // 2026-10-23：同一張屬性表（沒改過）、同一個技能、同樣的寶石：直接用上次算好的（按住攻擊一秒要施放好幾次）
        if (SkillBuild.PREVIEW_LEVEL.get() != null) { // 2026-10-28：圖鑑的預覽等級不進快取（不然會被一般施放拿去用）
            return minionSpirit(state, sheet, StageAttacks.withShield(plugin, state, buildFresh(state, sheet, definition, entry)));
        }
        final BuildKey key = new BuildKey(config, definition, entry, state.level(), Set.copyOf(state.weaponTags()), sheet, sheet == null ? 0 : sheet.version());
        final SkillBuild cached = buildCache.get(key);
        if (cached != null) {
            return minionSpirit(state, sheet, cached);
        }
        final SkillBuild fresh = StageAttacks.withShield(plugin, state, buildFresh(state, sheet, definition, entry)); // 2026-10-28 照 PoE2：盾技能照盾的護甲附加傷害
        if (buildCache.size() >= BUILD_CACHE_MAX) {
            buildCache.clear(); // 很簡單的上限：滿了就整個清掉（舊的屬性表也跟著放掉）
        }
        buildCache.put(key, fresh);
        return minionSpirit(state, sheet, fresh);
    }

    /**
     * 2026-10-26 照 PoE2：附魂術每隻保留 floor(50 × P^0.75) 精魂（P = 附身的那種怪的強度；skills.yml 寫的 50 是 P = 1），
     * 荒原狼伴保留最大精魂的 floor(100 × √P) × 0.3 %（skills.yml 寫的 per_minion 30 當作 30%）。都已經算進保留效率。
     */
    private SkillBuild minionSpirit(final PlayerState state, final StatSheet sheet, final SkillBuild build) {
        if (build.minionReserve() <= 0) {
            return build;
        }
        final String id = build.definition().id();
        if (MinionAbilities.SPECTRE.equals(id)) {
            final double power = MinionService.spectrePower(state, plugin.monsters() == null ? null : plugin.monsters().config());
            return build.withMinionReserve(build.minionReserve() * MinionAbilities.spectreSpirit(power) / 50.0);
        }
        if (MinionAbilities.BEAST.equals(id) && sheet != null && build.definition().summon() != null) {
            // 2026-10-30 照 PoE2 Tame Beast：收服了野獸就照那種怪的強度（monsters.yml 的生命倍率）；沒收服照狼
            final double percent = MinionAbilities.companionSpiritPercent(MinionService.tamedPower(state,
                    plugin.monsters() == null ? null : plugin.monsters().config(), build.definition().summon().life()));
            final double spirit = Math.max(0, sheet.get(StatKeys.SPIRIT_MAX));
            return build.withMinionReserve(build.minionReserve() / 30.0 * percent / 100.0 * spirit);
        }
        return build;
    }

    /**
     * 2026-10-26：按下開著的精魂召喚的鍵：下指令（PoE2 的 Command）、附魂術附身、鴉群密謀的亂鴉狂舞。
     * 這個技能沒有可以按的回傳 null（照舊提示「一直開著」）。
     */
    private String minionPress(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final String id = build.definition().id();
        if (MinionAbilities.SPECTRE.equals(id)) {
            return minions.bindSpectre(player, build);
        }
        if (MinionAbilities.RAVENS.equals(id)) {
            return granted().madFlight(player, build, preferred);
        }
        if (MinionAbilities.BEAST.equals(id)) {
            return minions.startTame(player, build, preferred); // 2026-10-30 照 PoE2 Tame Beast：按鍵用鬼火纏住稀有野獸，打倒就收服
        }
        return minions.command(player, build, preferred);
    }

    /** 技能數值快取的鍵（屬性表比的是同一個物件＋版本）。 */
    private record BuildKey(SkillConfig config, SkillDefinition definition, SkillLoadout.Entry entry, int level,
                            java.util.Set<String> weaponTags, StatSheet sheet, long version) {
    }

    private static final int BUILD_CACHE_MAX = 512;
    private final Map<BuildKey, SkillBuild> buildCache = new HashMap<>();

    private SkillBuild buildFresh(final PlayerState state, final StatSheet sheet, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        return buildFresh(state, sheet, definition, entry, List.of());
    }

    /** 2026-10-27：同上，另外在算範圍、冷卻倍率之前加上 more（裂甲熊咆插槽給插槽戰吼的範圍、傷害、冷卻回復）。 */
    private SkillBuild buildFresh(final PlayerState state, final StatSheet sheet, final SkillDefinition definition, final SkillLoadout.Entry entry,
                                  final List<tw.exilecore.stats.Modifier> more) {
        // 2026-10-22：宿主寶石（英靈圖騰、星咒圖騰、暴擊引星）孔裡的輔助是給放進去的技能的；宿主自己只吃其中要圖騰的、加速度與持續的
        final List<SupportDefinition> supports = SupportDefinition.hostsSkill(definition) ? hostSupportsOf(entry) : supportsOf(entry);
        // 2026-10-26（昇華・嵌晶）：技能品質 +x%、進階奇術（品質效果加倍）、鑲嵌物（技能等級）、整合效率（相連的輔助顏色）
        final tw.exilecore.ascendancy.AscBuild.Gem gem = tw.exilecore.ascendancy.AscBuild.gem(sheet, definition, supports);
        final int quality = entry.quality() + gem.quality() + bonusQuality(sheet); // 2026-10-26：＋天賦樹的技能品質
        List<tw.exilecore.stats.Modifier> extra = qualityModifiers(definition, quality);
        if (gem.twice()) {
            extra = tw.exilecore.ascendancy.AscBuild.advanced(definition.id(), quality, extra); // 2026-10-27 照 PoE2：進階奇術給額外的品質效果
        }
        if (!gem.extra().isEmpty()) {
            extra = new ArrayList<>(extra);
            extra.addAll(gem.extra());
        }
        if (!more.isEmpty()) {
            extra = new ArrayList<>(extra);
            extra.addAll(more); // 2026-10-27：裂甲熊咆插槽
        }
        final StatSheet gemSheet = gem.levels() > 0 && sheet != null
                ? sheet.overlay(List.of(tw.exilecore.stats.Modifier.flat(StatKeys.SKILL_LEVEL, gem.levels(), "鑲嵌物"))) : sheet;
        // 2026-10-23：品質放進去一起算（光環、戰吼、詛咒的品質是加效果倍率，算完才補上就沒用了）
        final SkillBuild built = SkillBuild.of(config, definition, entry.level(), state.level(), false, supports, state.weaponTags(), gemSheet,
                resonantOf(entry), extra); // 2026-10-18：共鳴的專屬輔助不佔名額
        if (Blasphemy.hosts(definition)) {
            return built.withReserveCount(Blasphemy.curses(config, entry)); // 2026-10-29 照 PoE2 Blasphemy：咒環每個插槽詛咒保留 60 精魂
        }
        return sceptreAura(definition, entry) ? built.withoutSupportSpirit() : built; // 2026-10-29 照站長：權杖自帶的光環上的輔助免精魂
    }

    /** 2026-10-29 照 PoE2 Blasphemy：咒環插槽詛咒的快取（同一張屬性表、同樣的寶石不重算；光環每 0.5 秒要用一次）。 */
    private record BlasphemyKey(SkillLoadout.Entry host, int level, java.util.Set<String> weaponTags, StatSheet sheet, long version) {
    }

    private final Map<java.util.UUID, Map.Entry<BlasphemyKey, List<SkillBuild>>> blasphemyCache = new HashMap<>();

    /**
     * 2026-10-29 照 PoE2 Blasphemy：咒環孔裡能用的詛咒（照孔的順序），已經套上咒環的「效果更少」與額外品質效果；
     * 輔助是咒環其他孔的（不合的略過）。屬性需求不足、放錯的略過；面板上沒有咒環或孔裡沒有回傳空的。
     */
    public List<SkillBuild> blasphemyCurses(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(Blasphemy.ID);
        final SkillLoadout.Entry host = position < 0 ? null : state.skills().skill(position);
        final SkillDefinition hostDefinition = config.skill(Blasphemy.ID);
        if (host == null || !Blasphemy.hosts(hostDefinition) || host.spells().isEmpty()) {
            return List.of();
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final BlasphemyKey key = new BlasphemyKey(host, state.level(), Set.copyOf(state.weaponTags()), sheet, sheet == null ? 0 : sheet.version());
        final Map.Entry<BlasphemyKey, List<SkillBuild>> cached = blasphemyCache.get(player.getUniqueId());
        if (cached != null && cached.getKey().equals(key)) {
            return cached.getValue();
        }
        final SkillBuild hostBuild = build(state, sheet, hostDefinition, host);
        final List<tw.exilecore.stats.Modifier> more = Blasphemy.curseModifiers(hostBuild.level(), hostBuild.qualityFlat(Blasphemy.CURSE_MAGNITUDE));
        final List<SkillBuild> list = new ArrayList<>();
        for (final SkillLoadout.Entry inner : GemAttributes.usable(config, innerEntries(host), sheet)) {
            final SkillDefinition definition = config.skill(inner.skillId());
            if (definition != null && Blasphemy.problem(definition) == null && definition.usableWith(state.weaponTags())) {
                list.add(buildFresh(state, sheet, definition, inner, more));
            }
        }
        final List<SkillBuild> result = List.copyOf(list);
        blasphemyCache.put(player.getUniqueId(), Map.entry(key, result));
        return result;
    }

    /**
     * 2026-10-29 照 PoE2 Hand of Chayula：夏烏拉之手孔裡能用的印記、詛咒（照孔的順序），已經套上印記效果增加／詛咒持續時間增加（含品質）；
     * 輔助是夏烏拉之手其他孔的（不合的略過）。屬性需求不足、放錯的略過；面板上沒有夏烏拉之手或孔裡沒有回傳空的。
     */
    public List<SkillBuild> chayulaSocketed(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(ChayulaHand.ID);
        final SkillLoadout.Entry host = position < 0 ? null : state.skills().skill(position);
        final SkillDefinition hostDefinition = config.skill(ChayulaHand.ID);
        if (host == null || !ChayulaHand.hosts(hostDefinition) || host.spells().isEmpty()) {
            return List.of();
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillBuild hostBuild = build(state, sheet, hostDefinition, host);
        final double markQuality = hostBuild.qualityFlat(ChayulaHand.MARK_EFFECT);
        final double curseQuality = hostBuild.qualityFlat(ChayulaHand.CURSE_DURATION);
        final List<SkillBuild> list = new ArrayList<>();
        for (final SkillLoadout.Entry inner : GemAttributes.usable(config, innerEntries(host), sheet)) {
            final SkillDefinition definition = config.skill(inner.skillId());
            if (definition != null && ChayulaHand.problem(definition) == null && definition.usableWith(state.weaponTags())) {
                list.add(buildFresh(state, sheet, definition, inner, ChayulaHand.modifiers(definition, hostBuild.level(), markQuality, curseQuality)));
            }
        }
        return list;
    }

    /** 2026-10-29 照 PoE2 Hand of Chayula：這一掌打了 target，孔裡的印記放在牠身上、詛咒套在牠的位置。 */
    void chayulaTrigger(final Player player, final LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead() || executor == null) {
            return;
        }
        for (final SkillBuild socketed : chayulaSocketed(player)) {
            final SkillParams.Mark spec = socketed.params().mark();
            if (spec == null) {
                continue;
            }
            if (spec.curse()) {
                executor.curseAt(player, socketed, target.getLocation(), spec);
            } else if (plugin.combat() != null) {
                executor.markOn(player, socketed, target, false);
            }
        }
    }

    /**
     * 2026-10-29 照 PoE2 Blasphemy：咒環開著時（AuraService 每 0.5 秒呼叫）孔裡每個詛咒對你身邊的敵人重套一次
     * （半徑 = 詛咒這一級的半徑 + 1 公尺，再乘詛咒的範圍倍率；延續 {@link Blasphemy#LINGER_TICKS} tick，離開範圍很快就消失）。
     */
    void blasphemyPulse(final Player player, final long now) {
        if (player.isDead() || executor == null) {
            return;
        }
        for (final SkillBuild curse : blasphemyCurses(player)) {
            final SkillParams.Mark spec = curse.params().mark();
            if (spec != null) {
                executor.applyCurseAt(player, curse, player.getLocation(), Blasphemy.widened(spec, curse.level()), now + Blasphemy.LINGER_TICKS);
            }
        }
    }

    /**
     * 2026-10-29 照站長：這一格是權杖賦予的光環（律盾、惡念、焰守、霜守、雷守、蝕守、寒心、雷霆共鳴）：上面掛的輔助不用精魂。
     * 只看光環（叫召喚物、照隻數保留的不算），而且要是武器賦予的那一格（自己插的同名寶石照舊）。
     */
    boolean sceptreAura(final SkillDefinition definition, final SkillLoadout.Entry entry) {
        if (entry == null || !entry.granted() || definition.mechanic() != Mechanic.AURA || definition.aura() == null
                || definition.aura().perMinion() != null || definition.summon() != null
                || plugin == null || plugin.items() == null || plugin.items().repository() == null) {
            return false;
        }
        return sceptreGrants(plugin.items().repository(), definition.id());
    }

    /** 有沒有權杖（基底帶 sceptre 標籤）賦予這個技能。 */
    static boolean sceptreGrants(final tw.exilecore.item.ItemRepository repository, final String skillId) {
        for (final Map.Entry<String, String> grant : repository.grantedSkills().entrySet()) {
            if (skillId.equals(grant.getValue())) {
                final tw.exilecore.item.ItemBase base = repository.base(grant.getKey());
                if (base != null && base.tags().contains("sceptre")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 2026-10-22：宿主寶石本身吃的輔助（孔裡要圖騰的、只加攻擊／施法速度或持續時間的）。 */
    List<SupportDefinition> hostSupportsOf(final SkillLoadout.Entry entry) {
        final List<SupportDefinition> list = new ArrayList<>();
        final SkillDefinition hostDefinition = config.skill(entry.skillId());
        final boolean deadeye = DeadeyeMetas.hosts(hostDefinition); // 2026-10-28 照 PoE2：幻影的冷卻、持續時間；常駐增益；預告射擊的範圍
        final boolean hollow = HollowForm.hosts(hostDefinition); // 2026-10-28 照 PoE2 Hollow Form：恆星、倍星（用掉銳星時）
        final boolean chayula = ChayulaHand.hosts(hostDefinition); // 2026-10-29 照 PoE2 Hand of Chayula：能輔助這一掌的都套在這一掌上
        for (final SupportDefinition support : supportsOf(entry)) {
            if (support.worksOnHost() || deadeye && DeadeyeMetas.appliesToHost(support, hostDefinition)
                    || hollow && HollowForm.appliesToHost(support) || chayula && support.supports(hostDefinition)) {
                list.add(support);
            }
        }
        return list;
    }

    /**
     * 2026-10-22：宿主寶石第 1 孔裡那顆技能的面板項目：代號、等級照放進去的寶石，輔助就是宿主第 2～5 孔的輔助
     * （放進去的寶石自己沒有輔助）。宿主沒放技能回傳 null。
     */
    static SkillLoadout.Entry innerEntry(final SkillLoadout.Entry host) {
        if (host == null || host.inner() == null) {
            return null;
        }
        return spellEntry(host, host.inner());
    }

    /**
     * 2026-10-26 照 PoE2 Meta Gem（「Triggers all Socketed Spells」）：宿主孔裡每一顆技能寶石的面板項目（照孔的順序），
     * 輔助都是宿主孔裡的輔助（放著技能寶石的孔不算）。
     */
    static List<SkillLoadout.Entry> innerEntries(final SkillLoadout.Entry host) {
        final List<SkillLoadout.Entry> list = new ArrayList<>();
        if (host != null) {
            for (final SkillLoadout.Entry spell : host.spells()) {
                list.add(spellEntry(host, spell));
            }
        }
        return list;
    }

    /**
     * 2026-10-27 照 PoE2 Ferocious Roar：裂甲熊咆第 1 孔放著人形戰吼時，用熊咆觸發的那個戰吼的數值——
     * 戰吼自己的等級與品質、熊咆其他孔的輔助（不合的略過）、插槽的範圍與傷害加成（{@link SocketedWarcry}）。
     * 沒放、放的不合格回傳 null。
     *
     * @param roarLevel 熊咆實際的等級（插槽傷害、消耗倍率照這個）
     */
    SkillBuild socketedWarcry(final PlayerState state, final StatSheet sheet, final SkillLoadout.Entry roarEntry, final int roarLevel) {
        final SkillDefinition roar = roarEntry == null ? null : config.skill(roarEntry.skillId());
        if (!SocketedWarcry.hosts(roar) || roarEntry.inner() == null) {
            return null;
        }
        final SkillDefinition cry = config.skill(roarEntry.inner().skillId());
        if (cry == null || SocketedWarcry.problem(cry) != null) {
            return null;
        }
        final List<tw.exilecore.stats.Modifier> more = SocketedWarcry.modifiers(roarLevel,
                qualityModifiers(roar, roarEntry.quality() + extraQuality(sheet)));
        return buildFresh(state, sheet, cry, spellEntry(roarEntry, roarEntry.inner()), more);
    }

    /** 2026-10-27 照 PoE2：插槽戰吼要花的魔力（它自己的消耗 × 熊咆的消耗倍率）。 */
    static int socketedCryCost(final SkillBuild roar, final SkillBuild cry) {
        return (int) Math.round(cry.manaCost() * SocketedWarcry.costMultiplier(roar.level()));
    }

    /** 2026-10-27：熊咆觸發插槽戰吼之前要過的關（插槽放錯、武器、盾、它自己的冷卻、榮耀）；沒問題回傳 null（給玩家看的文字，已經跳脫）。 */
    private String socketedCryProblem(final Player player, final SkillRuntime runtime, final PlayerState state, final String cryId,
                                      final SkillBuild cryBuild, final long now) {
        final SkillDefinition cry = config.skill(cryId);
        final String why = SocketedWarcry.problem(cry);
        if (why != null || cryBuild == null) {
            return "插槽裡的" + ItemRenderer.escape(cry == null ? cryId : cry.name()) + "不能用（" + ItemRenderer.escape(why == null ? "不認得" : why) + "）";
        }
        if (!cry.usableWith(state.weaponTags())) {
            return "插槽裡的" + ItemRenderer.escape(weaponProblem(player, state, cry));
        }
        if (Warcries.needsShield(cry)) {
            final String shield = armouredShieldProblem(state, cry);
            if (shield != null) {
                return shield;
            }
        }
        final long left = runtime.cooldownLeft(cry.id(), now);
        if (left > 0) {
            return String.format(Locale.ROOT, "插槽裡的%s冷卻中（%.1f 秒）", ItemRenderer.escape(cry.name()), left / 20.0);
        }
        return supportMechanics().usageProblem(player, cryBuild);
    }

    private static SkillLoadout.Entry spellEntry(final SkillLoadout.Entry host, final SkillLoadout.Entry spell) {
        final List<String> supports = new ArrayList<>(host.supports());
        for (int i = 0; i < supports.size(); i++) {
            if (supports.get(i).startsWith(SkillLoadout.Entry.INNER_PREFIX)) {
                supports.set(i, ""); // 2026-10-26：別的孔裡的技能寶石不是輔助
            }
        }
        return new SkillLoadout.Entry(spell.skillId(), spell.level(), SkillLoadout.MAX_SOCKETS, supports, host.resonant())
                .withQuality(spell.quality()); // 2026-10-23：放進去的寶石的品質
    }

    /** 2026-10-26 照 PoE2 天賦樹：「所有技能的品質 +x%」（加在每顆技能寶石自己的品質上）。 */
    static int bonusQuality(final StatSheet sheet) {
        return sheet == null ? 0 : (int) Math.max(0, Math.round(sheet.flat(tw.exilecore.stats.TreeEffectKeys.SKILL_QUALITY, java.util.Set.of())));
    }

    /** 2026-10-29：昇華「技能寶石品質」（嵌晶：所有技能品質 +2%，配兩個 +4%）。 */
    static int ascendancyQuality(final StatSheet sheet) {
        return sheet == null ? 0 : (int) Math.max(0, Math.round(sheet.flat(tw.exilecore.ascendancy.AscKeys.SKILL_QUALITY, java.util.Set.of())));
    }

    /** 2026-10-29：寶石以外的技能品質（昇華＋天賦樹）——技能面板顯示、插槽裡的技能都照這個加。 */
    static int extraQuality(final StatSheet sheet) {
        return ascendancyQuality(sheet) + bonusQuality(sheet);
    }

    /**
     * 2026-10-23：技能寶石的品質（琢星稜鏡、腐化）：每 1% 品質——光環、戰吼、詛咒增加 0.5% 效果，其他增加 1% 傷害
     * （召喚技能是召喚物的傷害）。照 PoE2 的「品質給這個技能額外的加成」，這裡統一成這幾種。
     */
    static List<tw.exilecore.stats.Modifier> qualityModifiers(final SkillDefinition definition, final int quality) {
        if (definition == null || quality <= 0) {
            return List.of();
        }
        final String source = QUALITY_SOURCE + quality + "%";
        // 2026-10-28 照 PoE2：剔殘、馭星訣也寫在 gem_quality.yml（第二條是額外的品質效果，進階奇術才有）；
        // 這次之前（2026-10-24～2026-10-27）這兩顆寫死在這裡，兩條都算一般品質
        // 2026-10-26 照 PoE2：寫在 data/gem_quality.yml 的技能照它自己的品質效果（琢星稜鏡）；沒寫的照下面的通用規則
        final List<tw.exilecore.stats.Modifier> own = tw.exilecore.currency.GemQuality.modifiers(definition.id(), quality, source);
        if (own != null) {
            return own;
        }
        if (definition.summon() != null) {
            // 2026-10-23 修正：精魂召喚（骸骨兵這類）機制是 aura，以前被當光環算成「光環效果」；召喚技能的品質一律是召喚物傷害
            return List.of(tw.exilecore.stats.Modifier.increased(StatKeys.DAMAGE, quality * 0.01, source));
        }
        if (definition.mechanic() == Mechanic.AURA) {
            return List.of(tw.exilecore.stats.Modifier.increased(StatKeys.AURA_EFFECT, quality * 0.005, source));
        }
        if (definition.mechanic() == Mechanic.WARCRY) {
            return List.of(tw.exilecore.stats.Modifier.increased(StatKeys.WARCRY_EFFECT, quality * 0.005, source));
        }
        if (definition.id().endsWith("_curse")) {
            return List.of(tw.exilecore.stats.Modifier.increased(StatKeys.CURSE_EFFECT, quality * 0.005, source));
        }
        return List.of(tw.exilecore.stats.Modifier.increased(StatKeys.DAMAGE, quality * 0.01, source));
    }

    /** 2026-10-28 照 PoE2 Mirage Archer 的品質：每個玩家第幾次弓攻擊（幻影延續看的是不是最新那一次）。 */
    private final Map<java.util.UUID, Long> mirageSequence = new HashMap<>();
    static final String MIRAGE_ARCHER = "mirage_archer";

    /** 2026-10-28：使用技能的動作期間的增益（震野踏、震野雙錘品質的暈眩門檻）。 */
    static final String ACTION_STUN_BUFF = "action.stun_threshold";

    /** 品質修飾器的來源名稱開頭（SkillBuild.grantModifiers 用來認品質給的 grant.*）。 */
    static final String QUALITY_SOURCE = "品質 ";

    /** 2026-10-30 照站長：圖鑑顯示 20% 品質的效果（不含「品質 +20%：」開頭）；寫不出來回傳 null。 */
    public static String codexQualityText(final SkillDefinition definition) {
        try {
            final String text = qualityText(definition, 20);
            return text == null ? null : text.replaceFirst("^品質 \\+20%：", "");
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 2026-10-30 照站長：圖鑑顯示 20% 品質的額外品質效果（嵌晶戰團・進階奇術）；寫不出來回傳 null。 */
    public static String codexAdvancedQualityText(final SkillDefinition definition) {
        try {
            return advancedQualityText(definition, 20);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** 2026-10-23：品質的說明（寶石、技能面板用）。 */
    /** 2026-10-28：額外的品質效果（gem_quality.yml 的 advanced；昇華「進階奇術」才有）的說明；沒有寫的技能是品質效果加倍（平衡等效）。 */
    static String advancedQualityText(final SkillDefinition definition, final int quality) {
        if (definition == null || quality <= 0) {
            return null;
        }
        final List<tw.exilecore.stats.Modifier> mods = tw.exilecore.currency.GemQuality.advancedModifiers(definition.id(), quality, QUALITY_SOURCE);
        if (mods == null) {
            final List<tw.exilecore.stats.Modifier> normal = tw.exilecore.currency.GemQuality.modifiers(definition.id(), quality, QUALITY_SOURCE);
            if (normal != null && normal.isEmpty()) {
                return "沒有效果（這個技能的品質沒有效果）"; // 2026-10-30：品質寫 [] 的技能，加倍還是沒有
            }
            return "一般的品質效果再算一次（效果加倍）";
        }
        final List<String> lines = new ArrayList<>();
        for (final tw.exilecore.stats.Modifier modifier : mods) {
            final String special = qualityLine(modifier);
            final String line = special != null ? special : ModifierText.describe(modifier);
            if (line != null && !line.startsWith("特殊效果")) {
                lines.add(definition.summon() != null && !line.startsWith("召喚物") && !line.startsWith("同時最多") ? "召喚物：" + line : line);
            }
        }
        return lines.isEmpty() ? null : String.join("、", lines);
    }

    static String qualityText(final SkillDefinition definition, final int quality) {
        if (definition == null || quality <= 0) {
            return null;
        }
        switch (definition.id()) { // 2026-10-24：照 PoE2 這個技能自己的品質效果
            case "cull_the_weak" -> { // 2026-10-28 照 PoE2：隨機種類那條是額外的品質效果（進階奇術）；這次之前兩條都寫在這裡
                return String.format(Locale.ROOT, "品質 +%d%%：獲得戰星時 %s%% 機率多一顆同種的", quality,
                        trimNumber(qualityFlat(definition, quality, tw.exilecore.charge.ChargeService.EXTRA)));
            }
            case "charge_regulation" -> { // 2026-10-28 照 PoE2：每顆戰星增加傷害那條是額外的品質效果（進階奇術）；這次之前兩條都寫在這裡
                return String.format(Locale.ROOT, "品質 +%d%%：消耗戰星的間隔多 %s 秒", quality,
                        trimNumber(qualityFlat(definition, quality, StatKeys.GRANT_PREFIX + tw.exilecore.charge.ChargeService.REGULATION_CONSUME)));
            }
            case "coiling_bolts" -> { // 2026-10-28 照 PoE2 Coiling Bolts：+(0—2) 分裂目標（每 10% 品質 +1）；這次之前顯示成增加傷害
                return String.format(Locale.ROOT, "品質 +%d%%：分裂目標 +%d", quality,
                        tw.exilecore.stats.GrantedSkillKeys.splitTargets(qualityFlat(definition, quality, ManaTempest.SPLIT), 0, false));
            }
            case "reap" -> { // 2026-10-28 照 PoE2 Reap：暴擊弱點持續時間 +(0—2) 秒；這次之前顯示成增加傷害
                return String.format(Locale.ROOT, "品質 +%d%%：暴擊弱點持續時間 +%s 秒", quality,
                        trimNumber(qualityFlat(definition, quality, tw.exilecore.stats.GrantedSkillKeys.CRIT_WEAKNESS_SECONDS)));
            }
            case "tornado_shot" -> { // 2026-10-28 照 PoE2 Tornado Shot：複製的投射物 (0—15)% 更多傷害（gem_quality.yml）；這次之前顯示成增加傷害
                return String.format(Locale.ROOT, "品質 +%d%%：複製的投射物更多 %s%% 傷害（跟等級的「更少」相加）", quality,
                        trimNumber(qualityFlat(definition, quality, TornadoSpec.COPY_MORE)));
            }
            case "whirling_slash" -> { // 2026-10-28 照 PoE2 Whirling Slash：崩散每多一階再更多 +(0—40)%（gem_quality.yml）；這次之前顯示成增加傷害
                return String.format(Locale.ROOT, "品質 +%d%%：旋風崩散每多一階再更多 %s%% 傷害（加在 150%% 上）", quality,
                        trimNumber(qualityFlat(definition, quality, Signatures.WHIRL_QUALITY_MORE)));
            }
            case "gale_spear" -> { // 2026-10-28 照 PoE2 Twister：(0—20)% 機率多一道旋風（gem_quality.yml）；這次之前顯示成增加傷害
                return String.format(Locale.ROOT, "品質 +%d%%：%s%% 機率多一道旋風", quality,
                        trimNumber(qualityFlat(definition, quality, Twisters.EXTRA_CHANCE)));
            }
            case "falling_thunder" -> { // 2026-10-28 照 PoE2 Falling Thunder：每用掉一顆銳星再更多 (0—20)%（gem_quality.yml）；這次之前顯示成增加傷害
                return String.format(Locale.ROOT, "品質 +%d%%：每用掉一顆銳星，猛擊與投射物各更多 %s%% 傷害（投射物的加在 50%% 上）", quality,
                        trimNumber(qualityFlat(definition, quality, SkillExecutor.THUNDER_QUALITY)));
            }
            case "snipe" -> { // 2026-10-28 照 PoE2 Snipe：爆炸半徑 +(0—0.4) 格（gem_quality.yml）；這次之前顯示成增加傷害
                return String.format(Locale.ROOT, "品質 +%d%%：爆炸與寒冰爆破的半徑 +%s 格", quality, trimNumber(tw.exilecore.core.Units.metres(quality * 0.02))); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），品質加的半徑（照 PoE2 公尺）也換算
            }
            default -> {
            }
        }
        // 2026-10-28 照 PoE2：這次照原作接上的 111 顆（GemQualityText；這次之前照通用規則顯示「增加 x% 傷害」）
        final String poe2 = GemQualityText.body(definition.id(),
                tw.exilecore.currency.GemQuality.modifiers(definition.id(), quality, QUALITY_SOURCE), quality);
        if (poe2 != null) {
            return String.format(Locale.ROOT, "品質 +%d%%：%s", quality, poe2);
        }
        final String warcry = warcryQualityText(definition, quality); // 2026-10-27 照 PoE2：戰吼各自的品質效果
        if (warcry != null) {
            return warcry;
        }
        // 2026-10-28：寫在 gem_quality.yml 的技能照表上的效果寫（ModifierText）；這次之前沒有專門寫的一律顯示「增加 x% 傷害」，跟實際效果不同
        final List<tw.exilecore.stats.Modifier> own = tw.exilecore.currency.GemQuality.modifiers(definition.id(), quality, QUALITY_SOURCE);
        if (own != null && own.isEmpty()) {
            return String.format(Locale.ROOT, "品質 +%d%%：這顆寶石的品質沒有效果", quality); // 2026-10-28 照 PoE2（架盾、招架、擲矛、凋蝕術）
        }
        if (own != null) {
            final List<String> lines = new ArrayList<>();
            for (final tw.exilecore.stats.Modifier modifier : own) {
                final String special = qualityLine(modifier); // 2026-10-28：ModifierText 寫不出來的品質屬性
                final String line = special != null ? special : ModifierText.describe(modifier);
                if (line != null && !line.startsWith("特殊效果")) {
                    // 召喚技能的品質是給召喚物的（傷害、生命、暈眩累積）
                    lines.add(definition.summon() != null && !line.startsWith("召喚物") && !line.startsWith("同時最多") ? "召喚物：" + line : line);
                }
            }
            return String.format(Locale.ROOT, "品質 +%d%%：%s", quality, lines.isEmpty() ? "強化這個技能自己的效果" : String.join("、", lines));
        }
        final String what = definition.summon() != null ? "召喚物傷害" // 2026-10-23：精魂召喚的機制是 aura，先看有沒有召喚
                : definition.mechanic() == Mechanic.AURA ? "光環效果" : definition.mechanic() == Mechanic.WARCRY
                ? (definition.tags().contains("warcry") ? "戰吼效果" : "技能效果") // 2026-10-30：借戰吼做的增益技能（冥想、時間凍結…）不是戰吼，這次之前寫「戰吼效果」"
                : definition.id().endsWith("_curse") ? "詛咒效果" : "傷害";
        final double amount = what.endsWith("效果") ? quality * 0.5 : quality;
        return String.format(Locale.ROOT, "品質 +%d%%：增加 %s%% %s", quality,
                amount == Math.rint(amount) ? String.valueOf((long) amount) : String.valueOf(amount), what);
    }

    /** 2026-10-28 照 PoE2：只有技能品質才用到、ModifierText 寫不出來（或寫得不像原作）的屬性的說明；其他回傳 null。 */
    static String qualityLine(final tw.exilecore.stats.Modifier modifier) {
        final double value = modifier.value();
        return switch (modifier.stat()) {
            case StageAttacks.LIMIT + "jagged" -> "鋸齒地面上限 +" + (int) Math.floor(value + 1e-9);
            case tw.exilecore.stats.TreeEffectKeys.PROJECTILE_CHANCE_TWO -> trimNumber(value) + "% 機率多射 2 發投射物";
            case tw.exilecore.item.unique.UniqueStats.PERFECT_WINDOW -> "完美時機的區間長 " + trimNumber(value * 100) + "%";
            case OriginalSkills.OIL_EXPOSURE -> "黑油的曝露多降低 " + trimNumber(value) + "% 元素抗性";
            case StatKeys.BLIND_CHANCE -> "擊中時 " + trimNumber(value) + "% 機率致盲";
            case tw.exilecore.stats.KeystoneKeys.MINION_LIFE_AS_ES -> "召喚物獲得最大生命的 " + trimNumber(value) + "% 為額外最大能量護盾";
            case Snap.EXTRA_REMNANT -> trimNumber(value) + "% 機率多一個灌注殘骸";
            case StatKeys.MELEE_RANGE -> modifier.type() == tw.exilecore.stats.ModType.FLAT ? "近戰打擊距離 +" + trimNumber(value / 10) + " 格" : null;
            case tw.exilecore.stats.WarcryKeys.RADIUS_FLAT -> "增益半徑 +" + trimNumber(tw.exilecore.core.Units.metres(value)) + " 格"; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），品質加的半徑（照 PoE2 公尺）也換算
            case StatKeys.DEBUFF_DURATION -> "減益持續時間 +" + trimNumber(value) + " 秒";
            case StatKeys.META_ENERGY_GAIN -> "獲得的能量增加 " + trimNumber(value * 100) + "%";
            case tw.exilecore.stats.GemQualityKeys.CHAIN_MORE_PER_REMAINING -> "每剩一次連鎖，擊中傷害更多 " + trimNumber(value) + "%"; // 2026-10-30 照 PoE2 Arc
            case tw.exilecore.stats.GemQualityKeys.META_TRIGGERED_DAMAGE -> "插槽法術被觸發時傷害增加 " + trimNumber(value) + "%"; // 2026-10-30 照 PoE2 Cast on Minion Death
            case ChayulaHand.CURSE_DURATION -> "插槽詛咒的持續時間增加 " + trimNumber(value) + "%"; // 2026-10-29 照 PoE2 Hand of Chayula
            case ChayulaHand.MARK_EFFECT -> "插槽印記的效果增加 " + trimNumber(value) + "%";
            case Blasphemy.CURSE_MAGNITUDE -> "插槽詛咒的效果增加 " + trimNumber(value) + "%"; // 2026-10-29 照 PoE2 Blasphemy（額外的品質效果）
            case StatKeys.DAMAGE -> modifier.tags().contains(tw.exilecore.stats.TreeConditions.VS_BROKEN_ARMOUR)
                    ? "對護甲完全破碎的敵人擊中更多 " + trimNumber(value * 100) + "% 傷害"
                    : modifier.tags().contains(tw.exilecore.stats.TreeConditions.VS_IMMOBILISED)
                    ? "對被禁錮的敵人更多 " + trimNumber(value * 100) + "% 傷害" : null;
            default -> null;
        };
    }

    /** 2026-10-28：gem_quality.yml 上這個技能 quality% 品質給的某個 FLAT 屬性總和（表上沒有回傳 0）。 */
    static double qualityFlat(final SkillDefinition definition, final int quality, final String stat) {
        final List<tw.exilecore.stats.Modifier> mods = tw.exilecore.currency.GemQuality.modifiers(definition.id(), quality, QUALITY_SOURCE);
        double sum = 0;
        for (final tw.exilecore.stats.Modifier modifier : mods == null ? List.<tw.exilecore.stats.Modifier>of() : mods) {
            if (modifier.stat().equals(stat) && modifier.type() == tw.exilecore.stats.ModType.FLAT) {
                sum += modifier.value();
            }
        }
        return sum;
    }

    /** 2026-10-27 照 PoE2：戰吼的品質說明（數值從 gem_quality.yml 算）；不是寫在表上的戰吼回傳 null。 */
    static String warcryQualityText(final SkillDefinition definition, final int quality) {
        if (definition.mechanic() != Mechanic.WARCRY) {
            return null;
        }
        final List<tw.exilecore.stats.Modifier> mods = tw.exilecore.currency.GemQuality.modifiers(definition.id(), quality, QUALITY_SOURCE);
        if (mods == null) {
            return null;
        }
        final java.util.function.Function<String, String> v = stat -> {
            double sum = 0;
            for (final tw.exilecore.stats.Modifier modifier : mods) {
                if (modifier.stat().equals(stat)) {
                    sum += modifier.type() == tw.exilecore.stats.ModType.FLAT ? modifier.value() : modifier.value() * 100;
                }
            }
            return trimNumber(sum);
        };
        final String body = switch (definition.id()) {
            // 2026-10-28：只寫一般品質效果（額外的品質效果在 advanced，進階奇術才有）；這次之前連額外的那幾條也寫，沒有進階奇術時顯示 0
            case "infernal_cry" -> "強化攻擊獲得 " + v.apply(tw.exilecore.stats.WarcryKeys.EMPOWER_GAIN_FIRE) + "% 傷害為額外火焰傷害";
            case "seismic_cry" -> "強化猛擊的餘震更多 " + v.apply(tw.exilecore.stats.WarcryKeys.AFTERSHOCK_MORE) + "% 傷害";
            case "fortifying_cry" -> "盾波半徑 +" + trimNumber(tw.exilecore.core.Units.metres(Double.parseDouble(v.apply(tw.exilecore.stats.WarcryKeys.TRIGGER_RADIUS_FLAT)))) + " 格"; // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），品質加的半徑（照 PoE2 公尺）也換算
            case "ancestral_cry" -> "每用掉一顆磐星，戰吼持續時間增加 " + v.apply(tw.exilecore.stats.WarcryKeys.DURATION_PER_ENDURANCE) + "%";
            case "arctic_howl" -> "給隊友的增益增加 " + v.apply(tw.exilecore.stats.WarcryKeys.ALLY_FREEZE_BUILDUP) + "% 凍結累積";
            case "ferocious_roar" -> "每 1 點強度多 " + v.apply(tw.exilecore.stats.WarcryKeys.RAGE_PER_POWER) + " 點怒氣；插槽戰吼：被它強化的攻擊增加 "
                    + v.apply(tw.exilecore.stats.WarcryKeys.SOCKETED_EMPOWERED_DAMAGE) + "% 傷害";
            case "lunar_blessing" -> "增益另外獲得 " + v.apply(StatKeys.GRANT_PREFIX + StatKeys.damageExtra(tw.exilecore.stats.DamageType.COLD))
                    + "% 傷害為冰冷傷害";
            default -> null;
        };
        return body == null ? null : String.format(Locale.ROOT, "品質 +%d%%：%s", quality, body);
    }

    private static String trimNumber(final double value) {
        final double rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    /**
     * 2026-10-22：給放在宿主裡的技能用的輔助：宿主其他孔的輔助，其中「要圖騰才能掛的」（疾樁、樁怒、堅樁…）放進去的技能本身
     * 沒有圖騰標籤、SkillBuild 會跳過，所以把它們的修飾器併成一顆不挑技能的輔助（名字用宿主的）；魔力倍率不算（放圖騰才花魔力）。
     */
    List<SupportDefinition> innerSupportsOf(final SkillLoadout.Entry host, final SkillDefinition hostDefinition, final SkillDefinition inner) {
        final List<SupportDefinition> list = new ArrayList<>();
        final List<tw.exilecore.stats.Modifier> carried = new ArrayList<>();
        for (final SupportDefinition support : supportsOf(host)) {
            if (hostDefinition != null && DeadeyeMetas.MIRAGE.equals(hostDefinition.id()) && Poe2SupportRules.proxyExcluded(support.id())) {
                continue; // 2026-10-30 照 PoE2：幻影代放的技能不吃迅鋒、衝勢這類（已經掛上去的也不生效）
            }
            if (hostDefinition != null && Poe2SupportRules.metaHostRefusal(support.id(), hostDefinition.id()) != null && !support.worksOnHost()) {
                continue; // 2026-10-30 照 PoE2：觸發的技能不吃蓄詠這類、圖騰代放的不吃迅鋒這類（已經掛上去的也不生效）
            }
            if (support.requires().contains(Tags.TOTEM) && inner != null && !support.supports(inner)) {
                carried.addAll(support.modifiers());
            } else if (support.requires().contains(SkillLimits.TAG) && hostDefinition != null && hostDefinition.tags().contains(SkillLimits.TAG)
                    && inner != null && !support.supports(inner)) {
                carried.addAll(support.modifiers()); // 2026-10-28 照 PoE2：過剩掛在星咒圖騰上（圖騰的上限），插槽法術本身沒有上限也吃得到
            } else if (support.requires().contains(Tags.META) && support.flags(StatKeys.DAMAGE_RANDOM) && inner != null && !support.supports(inner)) {
                carried.addAll(support.modifiers()); // 2026-10-26（骰運，PoE2 Fluke）：掛在主要寶石上，觸發出來的技能傷害忽高忽低
            } else {
                list.add(support);
            }
        }
        if (!carried.isEmpty()) {
            final String name = hostDefinition == null ? "圖騰" : hostDefinition.name();
            list.addFirst(new SupportDefinition("host." + host.skillId(), name, "", "PAPER", 1, java.util.Set.of(), java.util.Set.of(), 1.0,
                    carried, List.of()));
        }
        return list;
    }

    /** 2026-10-22：面板上這一格宿主寶石裡的技能（定義）；沒有或不認得回傳 null。 */
    SkillDefinition innerSkill(final SkillLoadout.Entry host) {
        // 2026-10-27：孔裡第一個技能寶石（不一定在第 1 孔：每一孔都能放）
        final List<SkillLoadout.Entry> spells = host == null ? List.of() : host.spells();
        return spells.isEmpty() ? null : config.skill(spells.getFirst().skillId());
    }

    /** 2026-10-18：技能掛的輔助裡已共鳴（用過共鳴石）的代號。 */
    static java.util.Set<String> resonantOf(final SkillLoadout.Entry entry) {
        final java.util.Set<String> set = new java.util.HashSet<>();
        for (int i = 0; i < entry.sockets(); i++) {
            if (entry.resonant(i) && !entry.support(i).isEmpty()) {
                set.add(entry.support(i));
            }
        }
        return set;
    }

    /** 技能掛的輔助寶石（資料檔裡已經沒有的略過）。 */
    List<SupportDefinition> supportsOf(final SkillLoadout.Entry entry) {
        final List<SupportDefinition> list = new ArrayList<>();
        for (final String id : entry.supportIds()) {
            final SupportDefinition support = config.support(id);
            if (support != null) {
                list.add(support);
            }
        }
        return list;
    }

    /**
     * 新角色（或第一次載入、技能面板是空的角色）送起始技能，等級照角色能用的最高等級，綁在右鍵。
     * 起始技能跟著建角色時選的新手武器（角色進度 starter.skill）；沒有就用職業的起始技能（skills.yml 的 starter）。
     * 每個角色只送一次（技能寶石可以拿出面板）。也清掉上一個角色的施放狀態。
     */
    public void onCharacterApplied(final Player player, final PlayerState state) {
        runtime(player).reset();
        projectiles.forget(player.getUniqueId());
        zones.forget(player.getUniqueId());
        deployables.forget(player.getUniqueId());
        minions.forget(player.getUniqueId());
        buffs.forget(player.getUniqueId());
        walkers.remove(player.getUniqueId());
        auras.forget(player.getUniqueId());
        metas.forget(player.getUniqueId());
        signatures.forget(player.getUniqueId()); // 2026-10-17：換角色也清掉插地的矛、蓄力這些（以前照舊角色的 build 繼續打）
        spears().forget(player.getUniqueId()); // 2026-10-27：插著的炎爆矛、血嗅印
        stageEffects().forget(player.getUniqueId()); // 2026-10-28：矛頭、震脈掌的增益
        executor.forgetDecompose(player.getUniqueId()); // 2026-10-28：腐屍爆的毒雲
        // 2026-10-27：換角色也清掉魔湧的風暴、怒氣／連擊／灌注、榮耀與輔助的標記、移動懲罰（這次之前只有離線才清，會帶到另一個角色）
        manaTempest.clear(player.getUniqueId());
        resources.forget(player.getUniqueId());
        supportMechanics().forget(player.getUniqueId());
        storedReleases().forget(player.getUniqueId()); // 2026-10-27：鐵壁守、瘟囊爆存的傷害
        movePenalty.forget(player);
        if (mounts != null) {
            mounts.dismount(player);
        }
        if (crystals != null) {
            crystals.clear(player.getUniqueId());
        }
        if (state.hasCharacter()) {
            migrateRemoved(player, state); // 2026-10 PoE2 改版拿掉的技能、輔助換成未切割寶石
            auras.sync(player); // 技能面板上的光環開起來
        }
        if (!state.hasCharacter() || "1".equals(state.progress().get(PROGRESS_STARTER))) {
            return;
        }
        if (!state.skills().isEmpty()) {
            plugin.characters().setProgress(player, PROGRESS_STARTER, "1"); // 批次 1 就拿過起始技能的角色
            return;
        }
        final SkillDefinition starter = starterFor(state);
        if (starter == null) {
            return;
        }
        final int level = config.maxLevelFor(state.level());
        final int position = state.skills().put(starter.id(), level);
        if (position < 0) {
            return;
        }
        state.skills().bind(SkillLoadout.KEY_RIGHT, position);
        plugin.characters().setProgress(player, PROGRESS_STARTER, "1");
        plugin.characters().save(player); // 進度記下「送過了」，技能也要馬上存，當機才不會兩頭落空
        player.sendMessage(plugin.text().prefixed("<gray>起始技能：<#e0c080>" + ItemRenderer.escape(starter.name())
                + " Lv." + level + "</#e0c080>（綁在右鍵，按住可以連續施放）。<white>/skills</white> 打開技能面板"));
    }

    /**
     * 2026-10 PoE2 改版：技能面板上拿掉的技能換成同等級的未切割技能寶石（掛著的輔助一起拆下來）、
     * 拿掉的輔助換成同階的未切割輔助寶石，放進背包（放不下的掉在腳邊）。
     */
    public void migrateRemoved(final Player player, final PlayerState state) {
        final SkillLoadout loadout = state.skills();
        final List<org.bukkit.inventory.ItemStack> give = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final List<String> merges = new ArrayList<>();
        boolean hostChanged = false;
        for (int position = 0; position < SkillLoadout.MAX_SKILLS; position++) {
            SkillLoadout.Entry original = loadout.skill(position);
            if (original == null) {
                continue;
            }
            // 2026-10-26：併入別的技能（skills.yml 的 merged）：原地換成那個技能，等級、品質、孔、輔助、按鍵都保留；
            //   面板上已經有那個技能、而且是一個角色一顆的常駐、宿主（GemCopies）就取下來成寶石放進背包
            //   （2026-10-28 照 PoE2：一般的技能可以放好幾顆，原地換；這次之前一律不能放兩個）
            final SkillLoadout.Entry merged = config.merge(original);
            if (merged != null && config.skill(merged.skillId()) != null) {
                if (!merged.skillId().equals(original.skillId())) {
                    merges.add(config.mergeNotice(original.skillId()));
                } else if (original.inner() != null) {
                    merges.add(config.mergeNotice(original.inner().skillId()));
                }
                final int existing = loadout.find(merged.skillId());
                if (existing >= 0 && existing != position && GemCopies.singleCopy(config, merged.skillId())) {
                    loadout.remove(position);
                    give.add(gems.create(GemData.skill(merged), 1));
                    merges.set(merges.size() - 1, merges.getLast() + "（面板上已經有" + config.skill(merged.skillId()).name() + "，這顆放進背包）");
                    continue;
                }
                loadout.set(position, merged);
                original = merged;
            }
            if (config.skill(original.skillId()) == null) {
                loadout.remove(position);
                give.add(gems.create(GemData.uncutSkill(original.level()), 1));
                for (final String support : original.supportIds()) {
                    give.add(gems.supportOrUncut(support));
                }
                give.addAll(gems.investedRefund(original)); // 2026-10-26：開過的孔、品質原數退還
                for (final SkillLoadout.Entry spell : original.spells()) { // 2026-10-26：第 2～5 孔放的技能寶石也還
                    give.add(gems.innerRefund(spell)); // 2026-10-26：拿掉的引星第 1 孔裡的技能寶石原樣還給玩家（這次之前會跟著不見）
                }
                names.add("技能 " + config.retiredSkillName(original.skillId()) + " Lv." + original.level() // 2026-10-26：有名稱就用名稱；這次之前是代號
                        + (original.sockets() > SkillLoadout.BASE_SOCKETS || original.quality() > 0 ? "（開孔、品質的通貨退還）" : ""));
                continue;
            }
            if (gems.grantedOnlyGem(original)) {
                // 2026-10-28 照 PoE2：武器、昇華賦予的技能沒有寶石：面板上不是賦予的那一格（管理指令、雕出來的烈陽珠）拿掉，
                //   換成同等級的未切割技能寶石，輔助、開孔、品質、孔裡的技能退還（拿著那把武器／點了天賦會再放回賦予的）
                loadout.remove(position);
                give.addAll(gems.grantedOnlyRefund(original));
                names.add("技能 " + config.skill(original.skillId()).name() + " Lv." + original.level() + "（武器／昇華賦予的技能沒有寶石，換成未切割技能寶石）");
                continue;
            }
            SkillLoadout.Entry entry = original;
            if (entry.legacySockets()) {
                // 2026-10-28 照 PoE2：賦予的技能的輔助孔照等級自動開（Lv.11／15／20 開第 3／4／5 孔），不能付費開。
                //   這次之前固定 2 孔、要用鑿星石開：開過的孔原數退還鑿星石，孔數改成照等級，多出來那幾孔的寶石放回背包
                final int sockets = SkillLoadout.grantedSockets(entry.level());
                final int paid = Math.max(0, entry.sockets() - SkillLoadout.BASE_SOCKETS);
                give.addAll(gems.investedRefund(entry));
                final List<SkillLoadout.Overflow> overflow = SkillLoadout.overflow(entry, sockets);
                for (final SkillLoadout.Overflow item : overflow) {
                    give.add(gems.overflowItem(item));
                }
                entry = entry.withLegacySockets(false);
                if (paid > 0 || !overflow.isEmpty()) {
                    names.add("技能 " + gems.skillName(original) + " Lv." + original.level() + "（賦予的技能改成照等級 " + sockets + " 孔"
                            + (paid > 0 ? "，開過的 " + paid + " 孔退還鑿星石" : "") + (overflow.isEmpty() ? "" : "，多出來的 " + overflow.size() + " 顆寶石放回背包")
                            + "）");
                }
            }
            final List<String> grantedSpells = new ArrayList<>();
            entry = gems.withoutGrantedOnlySpells(entry, give, grantedSpells); // 2026-10-28 照 PoE2：主要寶石孔裡放著賦予的技能的拿出來
            for (final String spell : grantedSpells) {
                names.add("技能 " + spell + "（武器／昇華賦予的技能不能插進" + gems.skillName(original) + "，換成未切割技能寶石）");
            }
            // 2026-10-26：掛著的低階 I／II 輔助原孔換成同系列最高階（supports.yml 的 merged）；同一個技能已經掛著那顆的取下來放進背包
            final List<GemData> detached = new ArrayList<>();
            final SkillLoadout.Entry supportsMerged = config.mergeSupports(entry, detached);
            if (supportsMerged != entry) {
                for (final String support : allSupportIds(entry)) {
                    final String notice = config.supportMergeNotice(support);
                    if (notice != null && !merges.contains(notice)) {
                        merges.add(notice);
                    }
                }
                for (final GemData gem : detached) {
                    give.add(gems.create(gem, 1));
                }
                entry = supportsMerged;
            }
            if (entry.inner() != null && config.skill(entry.inner().skillId()) == null) { // 2026-10-22：放在宿主裡的技能被拿掉
                give.add(gems.create(GemData.uncutSkill(entry.inner().level()), 1));
                give.addAll(gems.investedRefund(entry.inner())); // 2026-10-26：開過的孔、品質原數退還
                names.add("技能 " + config.retiredSkillName(entry.inner().skillId()) + " Lv." + entry.inner().level());
                entry = entry.withInner(null);
            }
            for (int i = SkillLoadout.Entry.INNER_SOCKET + 1; i < entry.sockets(); i++) {
                final SkillLoadout.Entry spell = entry.spellAt(i); // 2026-10-26：主要寶石第 2～5 孔放的技能被拿掉
                if (spell != null && config.skill(spell.skillId()) == null) {
                    give.add(gems.innerRefund(spell));
                    names.add("技能 " + config.retiredSkillName(spell.skillId()) + " Lv." + spell.level());
                    entry = entry.withSpell(i, null);
                }
            }
            for (int i = 0; i < entry.sockets(); i++) {
                final String support = entry.support(i);
                if (!support.isEmpty() && !entry.spellSocket(i) && config.support(support) == null) {
                    give.add(gems.supportOrUncut(support));
                    entry = entry.withSupport(i, null);
                    names.add("輔助 " + support);
                }
            }
            // 2026-10-27：一個技能只能掛一顆血脈輔助：舊存檔掛了好幾顆的，第二顆以後拆下來放回背包
            for (final int i : java.util.List.<Integer>of()) { // 2026-10-28 照 PoE2：不同的血脈輔助可以一起掛，不再拆（這次之前第二顆以後拆下來）
                final String support = entry.support(i);
                give.add(gems.create(GemData.support(support, entry.resonant(i)), 1));
                names.add("輔助 " + gems.supportName(support) + "（一個技能只能掛一顆血脈輔助）");
                entry = entry.withSupport(i, null);
            }
            // 2026-10-22：宿主寶石（英靈圖騰、星咒圖騰、暴擊引星）第 1 孔改放技能寶石：舊資料第 1 孔是輔助的往後挪一孔，
            // 擠出去的最後一顆退還（放進去的技能留在原本那一格，玩家自己拖進第 1 孔）
            if (SupportDefinition.hostsSkill(config.skill(entry.skillId())) && !entry.support(SkillLoadout.Entry.INNER_SOCKET).isEmpty()) {
                final List<String> shifted = new ArrayList<>();
                final List<Boolean> marks = new ArrayList<>();
                shifted.add("");
                marks.add(false);
                for (int i = 0; i < entry.sockets(); i++) {
                    shifted.add(entry.support(i));
                    marks.add(entry.resonant(i));
                }
                final String last = shifted.get(entry.sockets());
                if (!last.isEmpty()) {
                    give.add(gems.create(GemData.support(last, marks.get(entry.sockets())), 1));
                    names.add("輔助 " + gems.supportName(last) + "（" + gems.skillName(entry) + "第 1 孔改放技能寶石，擠出來）");
                }
                entry = new SkillLoadout.Entry(entry.skillId(), entry.level(), entry.sockets(), shifted.subList(0, entry.sockets()),
                        marks.subList(0, entry.sockets()), entry.inner(), entry.corrupted(), entry.quality());
                hostChanged = true;
            }
            if (entry != original) {
                loadout.set(position, entry);
            }
        }
        if (hostChanged) {
            plugin.players().markDirty(player);
            plugin.characters().save(player);
            player.sendMessage(plugin.text().prefixed("<yellow>技能改版：英靈圖騰、星咒圖騰、暴擊引星改成把技能寶石放進自己的第 1 孔"
                    + "（後面 4 孔的輔助套在那顆技能上）。請打開 <white>/skills</white>，把原本放在它後面那一格的技能拖進第 1 孔"));
        }
        if (!merges.isEmpty()) {
            plugin.players().markDirty(player);
            plugin.characters().save(player);
            player.sendMessage(plugin.text().prefixed("<yellow>技能改版：" + ItemRenderer.escape(String.join("、", merges))
                    + "（技能面板上的直接換過去，等級、品質、輔助寶石都保留；同一個技能重複的輔助放進背包）"));
        }
        if (give.isEmpty()) {
            return;
        }
        gems.giveAll(player, give);
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        if (names.isEmpty()) {
            return; // 2026-10-26：只有併入別的技能、面板上重複取下來的（上面通知過了）
        }
        // 2026-10-28：也包含賦予的技能改成照等級開孔的退還，說法改成「變動」（這次之前是「拿掉的 n 個技能／輔助」）
        player.sendMessage(plugin.text().prefixed("<yellow>技能改版：技能面板上變動的 " + names.size()
                + " 項，退還的東西已經放進背包（" + ItemRenderer.escape(String.join("、", names)) + "）"));
    }

    /** 2026-10-26：技能（含放在宿主孔裡的技能）掛著的所有輔助代號（通知用）。 */
    private static List<String> allSupportIds(final SkillLoadout.Entry entry) {
        final List<String> ids = new ArrayList<>(entry.supportIds());
        if (entry.inner() != null) {
            ids.addAll(entry.inner().supportIds());
        }
        return ids;
    }

    /** 這個角色的起始技能：選新手武器時決定的優先，找不到（改過設定）就用職業的。 */
    private SkillDefinition starterFor(final PlayerState state) {
        final String chosen = state.progress().get(CharacterService.PROGRESS_STARTER_SKILL);
        if (chosen != null) {
            final SkillDefinition definition = config.skill(chosen);
            if (definition != null && !definition.hidden()) {
                return definition;
            }
        }
        return config.starter(state.characterClass().id());
    }

    /** 管理指令：加技能（已經有就改等級）；等級 null = 角色能用的最高等級。回傳錯誤訊息或 null。 */
    public String give(final Player player, final String skillId, final Integer level) {
        final PlayerState state = plugin.players().get(player);
        if (!state.hasCharacter()) {
            return "沒有角色";
        }
        final SkillDefinition definition = config.skill(skillId);
        if (definition == null || definition.hidden()) {
            return "沒有這個技能：" + skillId;
        }
        if (definition.grantedOnly()) { // 2026-10-28 照 PoE2：賦予的技能沒有寶石（以前放進面板後能取出成寶石）
            return definition.name() + "是" + definition.sourceText() + "的技能，沒有寶石：裝備賦予它的武器或點那個昇華天賦就會出現在面板上";
        }
        final int actual = level == null ? config.maxLevelFor(state.level()) : level;
        if (state.skills().put(definition.id(), actual) < 0) {
            return "技能面板滿了（最多 " + SkillLoadout.MAX_SKILLS + " 個），先移除一個";
        }
        plugin.characters().save(player);
        return null;
    }

    /** 管理指令：移除技能。 */
    public String remove(final Player player, final String skillId) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        if (position < 0) {
            return "面板上沒有這個技能：" + skillId;
        }
        state.skills().remove(position);
        plugin.characters().save(player);
        return null;
    }

    /** 管理指令：清空技能面板（下次載入角色會再送起始技能）。 */
    public void reset(final Player player) {
        final PlayerState state = plugin.players().get(player);
        state.skills().clear();
        if (state.progress().containsKey(PROGRESS_STARTER)) {
            plugin.characters().setProgress(player, PROGRESS_STARTER, "0");
        }
        plugin.characters().save(player);
    }

    // ---- 快捷列的技能圖示 ----

    /** 快捷列冷卻（2026-10）：技能在原版的冷卻群組（exilecore:skill/代號）。 */
    public static net.kyori.adventure.key.Key cooldownGroup(final String skillId) {
        return net.kyori.adventure.key.Key.key("exilecore", "skill/" + skillId.toLowerCase(Locale.ROOT));
    }

    /**
     * 2026-10-23：時間泡泡（怪物詞綴「時滯的」）：還在冷卻的技能都晚 extra tick 好（每 0.5 秒推 5 tick ＝ 冷卻回復慢一半），
     * 快捷列的冷卻遮罩跟著更新。
     */
    public void delayCooldowns(final Player player, final long extra) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        if (runtime == null || extra <= 0) {
            return;
        }
        final long now = plugin.getServer().getCurrentTick();
        for (final Map.Entry<String, Long> entry : runtime.cooldowns.entrySet()) {
            if (entry.getValue() > now) {
                entry.setValue(entry.getValue() + extra);
                showCooldown(player, entry.getKey(), entry.getValue() - now);
            }
        }
    }

    /** 這個技能還要冷卻幾 tick（0 = 好了）。 */
    public long cooldownLeft(final Player player, final String skillId) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        return runtime == null ? 0 : runtime.cooldownLeft(skillId, plugin.getServer().getCurrentTick());
    }

    /** 冷卻開始：快捷列上那個技能的圖示馬上蓋上原版的冷卻遮罩（倒數的數字由 Gear 每 4 tick 更新）。 */
    void showCooldown(final Player player, final String skillId, final long ticks) {
        if (ticks > 0) {
            player.setCooldown(cooldownGroup(skillId), (int) Math.min(Integer.MAX_VALUE, ticks));
        }
    }


    /**
     * 快捷列上顯示用的技能圖示（戰鬥區域裡第 3–8 格，見 {@link tw.exilecore.character.Gear}）：名字前面是按鍵，說明是技能的數值。
     * 回傳的是一般的物品，標成顯示用由 Gear 做。
     */
    public org.bukkit.inventory.ItemStack hotbarIcon(final Player player, final int bindSlot) {
        final PlayerState state = plugin.players().get(player);
        final Resolved resolved = resolve(state, bindSlot);
        final SkillDefinition definition = resolved.definition();
        final String key = tw.exilecore.character.HotbarLayout.of(state).shortKeyName(bindSlot); // 2026-10：照玩家選的排法
        final List<String> lore = new ArrayList<>();
        final org.bukkit.Material material;
        final String title;
        if (definition == null) {
            material = org.bukkit.Material.GRAY_STAINED_GLASS_PANE;
            title = "<white>" + key + " <gray>沒有綁技能";
        } else {
            material = tw.exilecore.gui.Menu.material(definition.icon(), resolved.isDefault() ? org.bukkit.Material.WOODEN_SWORD
                    : org.bukkit.Material.BOOK);
            final boolean auraOn = !resolved.isDefault() && definition.mechanic() == Mechanic.AURA && auraActive(player, definition.id());
            title = "<white>" + key + " " + (resolved.isDefault() ? "<gray>" : "<#e0c080>") + ItemRenderer.escape(definition.name())
                    + (resolved.isDefault() ? "（沒有綁技能）" : " <gray>Lv." + resolved.level()) + (auraOn ? " <#8fd3ff>開啟中" : "");
            lore.addAll(resolved.isDefault() ? describeDefault(player, definition) : describe(player, definition, resolved.entry()));
            lore.add("");
        }
        lore.add("<dark_gray>戰鬥中按 " + key + " 施放・換技能：Shift＋F → 技能");
        final org.bukkit.inventory.ItemStack stack = org.bukkit.inventory.ItemStack.of(material);
        if (definition != null && !resolved.isDefault()) {
            SkillArt.decorate(stack, definition);
            // 2026-10：快捷列冷卻倒數——每個技能自己的原版冷卻群組（圖示上有轉圈的灰色遮罩，同材質的技能不會互相影響），
            // 數量 = 剩幾秒（Gear#cooldowns 每 4 tick 對一次），所以要能疊到 99
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.USE_COOLDOWN,
                    io.papermc.paper.datacomponent.item.UseCooldown.useCooldown(0.05f).cooldownGroup(cooldownGroup(definition.id())).build());
            if (material.getMaxDurability() == 0) {
                stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_STACK_SIZE, 99);
            }
        }
        stack.editMeta(meta -> {
            meta.displayName(ItemRenderer.line(title));
            meta.lore(tw.exilecore.core.text.TextWrap.lines(lore)); // 2026-10-26：長行自動換行
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        });
        return stack;
    }

    // ---- 說明文字 ----

    /** 普攻在這個玩家身上的說明（按鍵格用）。 */
    public List<String> describeDefault(final Player player, final SkillDefinition definition) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final List<String> lines = new ArrayList<>();
        // 2026-10-29：裝著但沒生效的裝備（需求不符…）寫在普攻說明最上面——主手武器沒生效時普攻會變成空手的，不寫看不出原因
        for (final String inactive : state.inactiveEquipment()) {
            lines.add("<red>沒生效：" + tw.exilecore.item.ItemRenderer.escape(inactive));
        }
        lines.addAll(describe(player, SkillBuild.of(config, definition, 1, state.level(), true, List.of(), state.weaponTags(), sheet)));
        return lines;
    }

    /** 面板上（或寶石裡）一個技能在這個玩家身上的說明。 */
    public List<String> describe(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        final PlayerState state = plugin.players().get(player);
        final List<String> lines = new ArrayList<>(describe(player, build(state, plugin.players().sheet(player), definition, entry)));
        // 2026-10-23：品質、腐化放在標籤下面第一行（以前面板上看不到）
        final List<String> top = new ArrayList<>();
        // 2026-10-29 照站長：品質照實際生效的算（寶石＋昇華「技能寶石品質」＋天賦樹），寫出各自加了多少；這次之前只寫寶石自己的
        final StatSheet qualitySheet = plugin.players().sheet(player);
        final int ascQuality = ascendancyQuality(qualitySheet);
        final int treeQuality = bonusQuality(qualitySheet);
        final int totalQuality = entry.quality() + ascQuality + treeQuality;
        final String quality = qualityText(definition, totalQuality);
        if (totalQuality > 0) {
            top.add("<#8888ff>◆ 品質 " + totalQuality + "%" + qualitySources(entry, ascQuality, treeQuality)); // 2026-10-27：武器賦予的寫「來自武器」
        }
        if (quality != null) {
            top.add("<#8888ff>◆ " + ItemRenderer.escape(quality));
            // 2026-10-28：額外的品質效果（進階奇術）也寫出來：配了就照常顏色，沒配就灰字提示；這次之前看不到
            final String advanced = advancedQualityText(definition, totalQuality);
            if (advanced != null) {
                final boolean on = tw.exilecore.ascendancy.AscBuild.gem(plugin.players().sheet(player), definition,
                        SupportDefinition.hostsSkill(definition) ? hostSupportsOf(entry) : supportsOf(entry)).twice();
                top.add(on ? "<#c9a7ff>◆ 額外的品質效果（進階奇術）：" + ItemRenderer.escape(advanced)
                        : "<dark_gray>◆ 額外的品質效果（要配昇華「進階奇術」）：" + ItemRenderer.escape(advanced));
            }
        }
        if (entry.corrupted()) {
            top.add("<#d20000>◆ 已腐化 <dark_gray>（不能再升級、開孔、加品質）");
        }
        if (!top.isEmpty() && !lines.isEmpty()) {
            lines.addAll(1, top);
        }
        lines.addAll(GemAttributes.lines(config, entry, plugin.players().sheet(player))); // 2026-10-26 照 PoE2：屬性需求
        if (!entry.granted()) { // 2026-10-28 照站長：未切割寶石不看角色等級就能升，角色等級不夠時自動降級，面板寫出來
            final int byLevel = gems() == null ? entry.level() : gems().usableByCharacter(state.level(), entry.level());
            if (byLevel < entry.level()) {
                lines.add("<yellow>⚠ 角色等級 " + state.level() + "（Lv." + entry.level() + " 要 " + config.requiredLevel(entry.level())
                        + "）：自動以 Lv." + byLevel + " 使用（裝備加的技能等級另外加上去）");
            }
        }
        if (SocketedWarcry.hosts(definition)) {
            lines.addAll(socketedCrySummary(definition, entry, build(state, plugin.players().sheet(player), definition, entry).level()));
        }
        if (DeadeyeMetas.hosts(definition)) {
            lines.addAll(deadeyeSummary(player, definition, entry)); // 2026-10-28 照 PoE2：預告射擊的間隔、幻影的持續時間與冷卻
        }
        if (HollowForm.hosts(definition)) {
            lines.addAll(hollowSummary(player)); // 2026-10-28 照 PoE2 Hollow Form：幻影的間隔、消耗與孔裡放了什麼
        }
        if (Blasphemy.hosts(definition)) {
            lines.addAll(blasphemySummary(player, definition, entry)); // 2026-10-29 照 PoE2 Blasphemy：咒環的詛咒、範圍與保留
        }
        if (ChayulaHand.hosts(definition)) {
            lines.addAll(chayulaSummary(player, definition, entry)); // 2026-10-29 照 PoE2 Hand of Chayula：孔裡的印記、詛咒
        }
        return lines;
    }

    /** 2026-10-29 照 PoE2 Hand of Chayula：說明上這一級的印記效果、詛咒持續時間，和孔裡放了什麼。 */
    private List<String> chayulaSummary(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        final List<String> lines = new ArrayList<>();
        final PlayerState state = plugin.players().get(player);
        final SkillBuild host = build(state, plugin.players().sheet(player), definition, entry);
        lines.add(String.format(Locale.ROOT, "<#c9a7ff>夏烏拉之手<gray>：插槽印記效果增加 <white>%s%%</white>、插槽詛咒持續時間增加 <white>%s%%</white>",
                ModifierText.number(ChayulaHand.markEffect(host.level()) + host.qualityFlat(ChayulaHand.MARK_EFFECT)),
                ModifierText.number(ChayulaHand.CURSE_DURATION_INCREASED + host.qualityFlat(ChayulaHand.CURSE_DURATION))));
        final List<SkillBuild> socketed = state.skills().find(ChayulaHand.ID) < 0 ? List.of() : chayulaSocketed(player);
        if (socketed.isEmpty()) {
            lines.add("<red>孔裡要放" + ChayulaHand.wants() + "的技能寶石（/skills 點它，拿技能寶石點一個孔），沒放就只是打一掌");
            return lines;
        }
        for (final SkillBuild inner : socketed) {
            final SkillParams.Mark spec = inner.params().mark();
            final boolean curse = spec != null && spec.curse();
            lines.add(String.format(Locale.ROOT, "<#c9a7ff>%s<gray>：%s（%s <white>%s</white> 秒）",
                    curse ? "套上詛咒" : "放上印記", ItemRenderer.escape(inner.definition().name()), curse ? "持續" : "持續",
                    ModifierText.number(Math.round(inner.durationTicks(spec == null ? 0 : spec.duration(inner.level())) / 2.0) / 10.0)));
        }
        return lines;
    }

    /** 2026-10-29 照 PoE2 Blasphemy：說明上咒環這一級的效果更少、每個插槽詛咒的半徑與效果，和實際保留的精魂。 */
    private List<String> blasphemySummary(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        final List<String> lines = new ArrayList<>();
        final PlayerState state = plugin.players().get(player);
        final SkillBuild host = build(state, plugin.players().sheet(player), definition, entry);
        lines.add(String.format(Locale.ROOT, "<#c9a7ff>咒環<gray>：插槽詛咒效果更少 <white>%s%%</white>、基礎半徑 +<white>%s</white> 格（%s 公尺），每個保留 %s 精魂",
                ModifierText.number(Blasphemy.lessMagnitude(host.level())), ModifierText.number(tw.exilecore.core.Units.metres(Blasphemy.EXTRA_RADIUS_METRES)),
                ModifierText.number(Blasphemy.EXTRA_RADIUS_METRES), ModifierText.number(Blasphemy.RESERVE_PER_CURSE)));
        final List<SkillBuild> curses = state.skills().find(Blasphemy.ID) < 0 ? List.of() : blasphemyCurses(player);
        if (curses.isEmpty()) {
            lines.add("<red>孔裡要放" + Blasphemy.wants() + "的技能寶石（/skills 點它，拿技能寶石點一個孔），沒放就沒有效果、也不保留精魂");
            return lines;
        }
        for (final SkillBuild curse : curses) {
            final SkillParams.Mark spec = curse.params().mark();
            lines.add(String.format(Locale.ROOT, "<#c9a7ff>光環詛咒<gray>：%s，你身邊 <white>%s</white> 格內的敵人一直被詛咒（詛咒效果 <white>%d%%</white>）",
                    ItemRenderer.escape(curse.definition().name()),
                    ModifierText.number(Math.round(Blasphemy.baseRadius(spec, curse.level()) * curse.areaScale() * 10) / 10.0),
                    Math.round(curse.auraEffect() * 100)));
        }
        lines.add(String.format(Locale.ROOT, "<gray>實際保留 <white>%s</white> 精魂（%d 個詛咒，已算保留效率）",
                ModifierText.number(Math.round(host.reserve() * 10) / 10.0), Blasphemy.curses(config, entry)));
        return lines;
    }

    /** 2026-10-28 照 PoE2 Hollow Form：說明上的幻影間隔、每個幻影的消耗（吃孔裡攻擊的輔助與攻擊速度）與孔裡放了什麼。 */
    private List<String> hollowSummary(final Player player) {
        final List<String> lines = new ArrayList<>();
        final List<SkillBuild> socketed = hollowSocketed(player);
        if (socketed.isEmpty()) {
            lines.add("<red>孔裡要放" + HollowForm.wants() + "的技能寶石（/skills 點它，拿技能寶石點一個孔），沒放就沒有效果");
            return lines;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        for (final SkillBuild attack : socketed) {
            final double step = HollowForm.stepTicks(attack.intervalTicks(sheet)) / 20.0;
            lines.add(String.format(Locale.ROOT, "<#c9a7ff>幻影<gray>：%s，每 <white>%s</white> 秒一個，每個花 <white>%d</white> 魔力%s",
                    ItemRenderer.escape(attack.definition().name()), ModifierText.number(Math.round(step * 100) / 100.0),
                    HollowForm.imageCost(attack.manaCost()),
                    attack.cooldownTicks() > 0 ? "（冷卻 " + ModifierText.number(Math.round(attack.cooldownTicks() / 20.0 * 100) / 100.0) + " 秒）" : ""));
        }
        return lines;
    }

    /** 2026-10-28 照 PoE2 Called Shots／Mirage Deadeye：說明上的實際間隔、持續時間、冷卻（吃其他孔的輔助）與孔裡放了什麼。 */
    private List<String> deadeyeSummary(final Player player, final SkillDefinition host, final SkillLoadout.Entry entry) {
        final List<String> lines = new ArrayList<>();
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final int level = grantedLevel(player);
        final List<SkillBuild> socketed = deadeyeSocketed(player, host.id());
        if (socketed.isEmpty()) {
            lines.add("<red>孔裡要放" + DeadeyeMetas.wants(host) + "的技能寶石（/skills 點它，拿技能寶石點一個孔），沒放就沒有效果");
            return lines;
        }
        if (DeadeyeMetas.CALLED_SHOTS.equals(host.id())) {
            // 2026-10-28 照 PoE2：預告射擊自己的 5 秒不吃冷卻恢復；孔裡印記自己的冷卻（吃冷卻恢復）也要好了才放
            final double seconds = Math.max(1, levelValue(host.id(), "asc.called_seconds", level));
            final SkillBuild hostBuild = build(state, sheet, host, entry);
            final List<String> cooldowns = new ArrayList<>();
            for (final SkillBuild mark : socketed) {
                cooldowns.add(mark.definition().name() + (mark.cooldownTicks() > 0 ? " 冷卻 " + ModifierText.number(Math.round(mark.cooldownTicks() / 20.0 * 100) / 100.0) + " 秒" : " 沒有冷卻"));
            }
            lines.add(String.format(Locale.ROOT, "<#c9a7ff>預告射擊<gray>：每 <white>%s</white> 秒（印記自己的冷卻也要好）把孔裡的印記放在 <white>%s</white> 格內的敵人身上；"
                            + "用掉時馬上補放（同一個冷卻），並標記被用掉那隻 %s 格內的另一個敵人",
                    ModifierText.number(seconds), ModifierText.number(Math.round(DeadeyeMetas.CALLED_RADIUS * hostBuild.areaScale() * 10) / 10.0),
                    ModifierText.number(DeadeyeMetas.SPREAD_RADIUS)));
            lines.add("<gray>　" + ItemRenderer.escape(String.join("、", cooldowns)));
        } else {
            final SkillBuild hostBuild = build(state, sheet, host, entry);
            final StatSheet effective = hostBuild.profile().effective(sheet);
            final double duration = Math.max(4, levelValue(host.id(), "asc.mirage_seconds", level) + hostBuild.qualityFlat("asc.mirage_seconds")) // 2026-10-30 照 PoE2：品質 +(0—1) 秒
                    * DeadeyeMetas.durationFactor(effective, hostBuild.profile().tags());
            final double cooldown = DeadeyeMetas.MIRAGE_COOLDOWN * DeadeyeMetas.cooldownFactor(effective, hostBuild.profile().tags());
            lines.add(String.format(Locale.ROOT, "<#c9a7ff>幻影<gray>：持續 <white>%s</white> 秒、冷卻 <white>%s</white> 秒，用孔裡的%s",
                    ModifierText.number(Math.round(duration * 100) / 100.0), ModifierText.number(Math.round(cooldown * 100) / 100.0),
                    ItemRenderer.escape(deadeyeNames(socketed))));
        }
        return lines;
    }

    private static String deadeyeNames(final List<SkillBuild> builds) {
        final List<String> names = new ArrayList<>();
        for (final SkillBuild build : builds) {
            names.add(build.definition().name());
        }
        return String.join("、", names);
    }

    /** 2026-10-27 照 PoE2 Ferocious Roar：裂甲熊咆說明上的插槽規則（與孔裡放了什麼）。 */
    List<String> socketedCrySummary(final SkillDefinition roar, final SkillLoadout.Entry entry, final int level) {
        final List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "<#c9a7ff>插槽<gray>：第 1 孔可以放一顆人形戰吼（變身的戰吼不行）：用%s時改成觸發它——不變身、沒有熊咆自己的效果；"
                        + "它範圍增加 <white>%s%%</white>、傷害增加 <white>%s%%</white>、被它強化的攻擊增加 <white>%s%%</white> 傷害，其他孔的輔助也套在它身上",
                ItemRenderer.escape(roar.name()), ModifierText.number(SocketedWarcry.AREA), ModifierText.number(SocketedWarcry.damage(level)),
                ModifierText.number(SocketedWarcry.damage(level))));
        lines.add(String.format(Locale.ROOT, "<gray>　另外花它的魔力 ×%s%%；它自己的冷卻、榮耀、武器與盾的要求照樣要",
                ModifierText.number(SocketedWarcry.costMultiplier(level) * 100)));
        final SkillDefinition cry = entry == null || entry.inner() == null ? null : config.skill(entry.inner().skillId());
        if (entry != null && entry.inner() != null) {
            final String why = SocketedWarcry.problem(cry);
            lines.add(why == null
                    ? "<gray>　現在插著：<#ffd27f>" + ItemRenderer.escape(cry.name()) + " <gray>Lv." + entry.inner().level() + "（用熊咆就觸發它）"
                    : "<red>　插槽裡的" + ItemRenderer.escape(cry == null ? entry.inner().skillId() : cry.name()) + "不能用（" + ItemRenderer.escape(why) + "）");
        }
        return lines;
    }

    /**
     * 2026-10-23：宿主寶石第 1 孔裡那顆技能的說明（技能面板放技能寶石那一孔的圖示用）：
     * 英靈圖騰：英靈那一下的傷害、暴擊、輔助（吃圖騰傷害）；主要寶石（暴擊引星、法術圖騰…）：插槽法術吃著其他孔輔助的完整說明。
     */
    public List<String> innerLines(final Player player, final SkillDefinition host, final SkillLoadout.Entry hostEntry) {
        return innerLines(player, host, hostEntry, SkillLoadout.Entry.INNER_SOCKET);
    }

    /** 2026-10-26：同上，第 index 孔裡的那顆（宿主寶石每一孔都能放技能，2026-10-27 起含星咒圖騰、英靈圖騰）。 */
    public List<String> innerLines(final Player player, final SkillDefinition host, final SkillLoadout.Entry hostEntry, final int index) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        if (SkillExecutor.hasFlag(host, StatKeys.FLAG_TOTEM_LINKED)) {
            // 2026-10-27：第 index 孔的那個錘子技能（孔裡可以放好幾個，英靈輪流用）
            final SkillLoadout.Entry socketedMace = hostEntry == null ? null : hostEntry.spellAt(index);
            return socketedMace != null && linkedMace(state, sheet, host, hostEntry, spellEntry(hostEntry, socketedMace)) instanceof SkillBuild found
                    ? ancestorLines(state, sheet, found) : List.of();
        }
        if (SocketedWarcry.hosts(host)) {
            return socketedCryLines(player, state, sheet, host, hostEntry); // 2026-10-27 照 PoE2 Ferocious Roar
        }
        final SkillLoadout.Entry socketed = hostEntry == null ? null : hostEntry.spellAt(index);
        final SkillLoadout.Entry inner = socketed == null ? null : spellEntry(hostEntry, socketed);
        final SkillDefinition spell = inner == null ? null : config.skill(inner.skillId());
        if (spell == null) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        lines.add("<#e7c56b>" + ItemRenderer.escape(spell.name()) + "<gray>（由" + ItemRenderer.escape(host.name())
                + (DeadeyeMetas.MIRAGE.equals(host.id()) ? "的幻影使用、傷害更少 " + ModifierText.number(DeadeyeMetas.MIRAGE_LESS) + "%" // 2026-10-28 照 PoE2
                        : HollowForm.hosts(host) ? "的幻影使用、傷害更少 " + ModifierText.number(HollowForm.LESS_DAMAGE) + "%、攻擊速度更少 "
                        + ModifierText.number(HollowForm.LESS_ATTACK_SPEED) + "%" // 2026-10-28 照 PoE2 Hollow Form
                        : Blasphemy.hosts(host) ? "變成光環一直詛咒你身邊的敵人、詛咒效果更少 " // 2026-10-29 照 PoE2 Blasphemy
                        + ModifierText.number(Blasphemy.lessMagnitude(hostEntry == null ? 1 : hostEntry.level())) + "%、半徑 +"
                        + ModifierText.number(tw.exilecore.core.Units.metres(Blasphemy.EXTRA_RADIUS_METRES)) + " 格"
                        : ChayulaHand.hosts(host) ? "每一掌打中的敵人觸發" // 2026-10-29 照 PoE2 Hand of Chayula
                        : metaDamageNote(host))
                + "、吃其他孔的輔助）：");
        for (final String line : describe(player, spell, inner)) {
            lines.add("<gray>　" + line);
        }
        return lines;
    }

    /** 2026-10-30：主要寶石觸發的插槽法術：說明上寫「施放」再加上主要寶石的更少／更多傷害（暴擊引星更少 20%；觸發時才乘，下面的數字沒算進去）。 */
    private String metaDamageNote(final SkillDefinition host) {
        final MetaSpec meta = config.meta(host.id());
        if (meta == null) {
            return "施放";
        }
        final StringBuilder out = new StringBuilder(meta.kind() == MetaSpec.Kind.TOTEM ? "施放" : "觸發");
        if (meta.lessDamage() > 0) {
            out.append("、傷害更少 ").append(ModifierText.number(meta.lessDamage())).append("%");
        }
        if (meta.moreDamage() > 0) {
            out.append("、傷害更多 ").append(ModifierText.number(meta.moreDamage())).append("%");
        }
        return out.toString();
    }

    /** 2026-10-27 照 PoE2 Ferocious Roar：裂甲熊咆孔裡那個戰吼被觸發時的說明（吃熊咆的插槽加成與其他孔的輔助）。 */
    private List<String> socketedCryLines(final Player player, final PlayerState state, final StatSheet sheet, final SkillDefinition roar,
                                          final SkillLoadout.Entry roarEntry) {
        if (roarEntry == null || roarEntry.inner() == null) {
            return List.of();
        }
        final SkillDefinition cry = config.skill(roarEntry.inner().skillId());
        final String why = SocketedWarcry.problem(cry);
        if (why != null) {
            return List.of("<red>" + ItemRenderer.escape(roar.name()) + "不能用它（" + ItemRenderer.escape(why) + "）");
        }
        final SkillBuild roarBuild = build(state, sheet, roar, roarEntry);
        final SkillBuild cryBuild = socketedWarcry(state, sheet, roarEntry, roarBuild.level());
        if (cryBuild == null) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        lines.add("<#e7c56b>" + ItemRenderer.escape(cry.name()) + "<gray>（由" + ItemRenderer.escape(roar.name()) + "觸發、吃其他孔的輔助）：");
        lines.add(String.format(Locale.ROOT, "<gray>　插槽加成：範圍增加 <white>%s%%</white>、傷害增加 <white>%s%%</white>、被它強化的攻擊增加 <white>%s%%</white> 傷害",
                ModifierText.number(SocketedWarcry.AREA), ModifierText.number(SocketedWarcry.damage(roarBuild.level())),
                ModifierText.number(cryBuild.profile().effective(sheet).flat(tw.exilecore.stats.WarcryKeys.EMPOWERED_DAMAGE, cryBuild.profile().tags()))));
        lines.add(String.format(Locale.ROOT, "<gray>　用熊咆時另外花 <#5599ff>%d</#5599ff> 魔力（%s 的消耗 ×%s%%）", socketedCryCost(roarBuild, cryBuild),
                ItemRenderer.escape(cry.name()), ModifierText.number(SocketedWarcry.costMultiplier(roarBuild.level()) * 100)));
        for (final String line : describe(player, cryBuild)) {
            lines.add("<gray>　" + line);
        }
        return lines;
    }

    /** 2026-10-23：名字後面的品質、腐化小標（技能面板的標題用）；都沒有回傳空字串。 */
    public static String qualityTag(final SkillLoadout.Entry entry) {
        return qualityTag(entry, 0);
    }

    /** 2026-10-29：同上，品質加上寶石以外的（昇華、天賦樹；{@link #extraQuality}）。 */
    public static String qualityTag(final SkillLoadout.Entry entry, final int extra) {
        final int total = entry.quality() + Math.max(0, extra);
        return (total > 0 ? " <#8888ff>品質 " + total + "%" : "") + (entry.corrupted() ? " <#d20000>腐化" : "");
    }

    /** 2026-10-29：品質的來源（寶石／武器、昇華、天賦樹），只有一種來源的寶石不寫；例如「（寶石 10% ＋昇華 4%）」。 */
    static String qualitySources(final SkillLoadout.Entry entry, final int ascQuality, final int treeQuality) {
        final List<String> parts = new ArrayList<>();
        if (entry.quality() > 0) {
            parts.add((entry.granted() ? "武器 " : "寶石 ") + entry.quality() + "%");
        }
        if (ascQuality > 0) {
            parts.add("昇華 " + ascQuality + "%");
        }
        if (treeQuality > 0) {
            parts.add("天賦樹 " + treeQuality + "%");
        }
        if (parts.isEmpty() || parts.size() == 1 && entry.quality() > 0 && !entry.granted()) {
            return "";
        }
        return "<gray>（" + String.join(" ＋", parts) + "）";
    }

    /** 圖鑑（2026-10）：看 Lv.30、Lv.40 這種超過寶石上限的數值——寶石當 20 級，多的當成「+x 技能等級」（再加上你裝備本來的）。 */
    /** 2026-10-28：圖鑑用：照指定的寶石等級（1–40）算，不受角色等級、屬性需求限制，也不加裝備的 +等級；沒有輔助。 */
    public List<String> describePreview(final Player player, final SkillDefinition definition, final int level) {
        final PlayerState state = plugin.players().get(player);
        SkillBuild.PREVIEW_LEVEL.set(level);
        try {
            return describe(player, buildFresh(state, plugin.players().sheet(player), definition,
                    new SkillLoadout.Entry(definition.id(), Math.min(SkillLoadout.MAX_LEVEL, level))));
        } finally {
            SkillBuild.PREVIEW_LEVEL.remove();
        }
    }

    public List<String> describeExtra(final Player player, final SkillDefinition definition, final int extraLevels) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player).overlay(List.of(
                tw.exilecore.stats.Modifier.flat(StatKeys.SKILL_LEVEL, extraLevels, "圖鑑")));
        return describe(player, build(state, sheet, definition, new SkillLoadout.Entry(definition.id(), SkillLoadout.MAX_LEVEL)));
    }

    /** 技能說明：數值都是用玩家目前的屬性與掛的輔助寶石算的。 */
    public List<String> describe(final Player player, final SkillBuild build) {
        final PlayerState state = plugin.players().get(player);
        // 2026-10-29：血術士的血棘（每 50 生命增加暴擊傷害加成）擊中時才算，技能說明也照滿血估一份（跟面板的每秒傷害一樣）；
        //   這次之前技能說明的暴擊傷害沒算到，比角色面板低
        final StatSheet actionSheet = withLifeCrit(plugin.players().sheet(player));
        final SkillDefinition definition = build.definition();
        // 2026-10-29 照 PoE2：巨靈的指令照巨靈的屬性表算傷害、暴擊（施法動作的時間照你的）
        final StatSheet djinnSheet = djinnSheet(state, definition);
        final StatSheet sheet = djinnSheet != null ? djinnSheet : actionSheet;
        final List<String> lines = new ArrayList<>();
        lines.add(headlineChips(definition)); // 2026-10：標籤改成小方塊
        if (!definition.description().isBlank()) {
            lines.add("<gray>" + ItemRenderer.escape(definition.description()));
        }
        lines.add("");
        if (config.meta(definition.id()) != null) {
            lines.addAll(metas.lines(player, definition)); // 2026-10：主要寶石的插槽、能量
            lines.add("");
        }
        final boolean deals = definition.dealsDamage();
        if (definition.isAttack()) {
            if (deals && definition.weaponless()) { // 2026-10-27 照 PoE2：不用武器的攻擊
                final double[] range = SkillProfiles.spellDamage(config, definition, build.level());
                lines.add(String.format(Locale.ROOT, "<gray>不用武器的攻擊，基礎傷害：<white>%d – %d", Math.round(range[0]), Math.round(range[1])));
            }
            if (deals) {
                lines.add(String.format(Locale.ROOT, "<gray>傷害效益：<white>%d%%", Math.round(build.profile().effectiveness() * 100)));
            }
            if (definition.attackSpeed() != 1.0) {
                lines.add(String.format(Locale.ROOT, "<gray>攻擊速度：<white>%d%%", Math.round(definition.attackSpeed() * 100)));
            }
        } else {
            if (deals && definition.damage() != null) {
                final double[] range = SkillProfiles.spellDamage(config, definition, build.level());
                lines.add(String.format(Locale.ROOT, "<gray>基礎傷害：<white>%d – %d", Math.round(range[0]), Math.round(range[1])));
            }
            if (definition.mechanic() != Mechanic.AURA) { // 2026-10：光環、精魂召喚綁著就開著，不用施放（沒有施法時間）
                // 2026-10-23：寫實際的施法時間（吃施法速度、捷咒這些輔助）；以前只寫基礎的，看起來像沒變
                final double actual = build.intervalTicks(actionSheet) / 20.0;
                lines.add(Math.abs(actual - definition.castTime()) < 0.005
                        ? String.format(Locale.ROOT, "<gray>施法時間：<white>%.2f 秒", definition.castTime())
                        : String.format(Locale.ROOT, "<gray>施法時間：<white>%.2f 秒 <dark_gray>（基礎 %.2f 秒）", actual, definition.castTime()));
            }
            if (deals && definition.damage() != null && definition.mechanic() == Mechanic.WARCRY) {
                // 2026-10（PoE2）：戰吼的傷害不算攻擊也不算法術
                lines.add("<gray>戰吼傷害：<white>不算攻擊也不算法術<gray>，吃「戰吼增加 x% 傷害」與不分種類的傷害加成");
            } else if (deals && definition.damage() != null) {
                lines.add(String.format(Locale.ROOT, "<gray>法術附加的效益：<white>%d%%", Math.round(build.profile().effectiveness() * 100)));
            }
        }
        if (!definition.conversion().isEmpty()) {
            definition.conversion().forEach((type, fraction) -> lines.add(String.format(Locale.ROOT,
                    "<gray>%d%% 物理傷害轉換為%s傷害", Math.round(fraction * 100), type.displayName())));
        }
        lines.addAll(stageLines(build)); // 2026-10-28 照 PoE2：每一段自己的傷害效益
        final String stepLine = stepLine(config.step(definition.id())); // 2026-10-28 照 PoE2：近戰條件位移
        if (stepLine != null) {
            lines.add(stepLine);
        }
        lines.addAll(resourceLines(player, definition));
        if (build.deploy() != null) {
            lines.addAll(deployLines(build, sheet, deployables.count(player.getUniqueId(), definition.id())));
        }
        if (definition.summon() != null) {
            lines.addAll(summonLines(player, build, sheet, minions.count(player.getUniqueId(), definition.id())));
        } else if (MinionTypes.line(definition) != null) {
            lines.add(MinionTypes.line(definition)); // 2026-10-29 照 PoE2：掘骸的骨構體（召喚物的技能標籤）
        }
        if (definition.aura() != null) {
            lines.addAll(auraLines(player, build));
        }
        if (definition.warcry() != null) {
            lines.addAll(warcryLines(build));
        }
        mechanicLines(build, lines);
        barrageLines(player, build, lines);
        if (ammoSkill(definition)) {
            lines.add(String.format(Locale.ROOT, "<gray>彈匣：<white>%d</white> 發，打完自動裝填 <white>%.1f</white> 秒",
                    clipSize(config, build, state.sheet()), reloadTicks(build, state.sheet()) / 20.0));
        }
        if (build.cooldownTicks() > 0 && definition.mechanic() != Mechanic.AURA) { // 光環不用按，冷卻沒有意義
            final double left = cooldownLeftSeconds(player, definition.id());
            lines.add(String.format(Locale.ROOT, "<gray>冷卻時間：<white>%.1f 秒", build.cooldownSeconds())
                    + (left > 0 ? String.format(Locale.ROOT, " <red>（還要 %.1f 秒）", left) : ""));
            final int rageBypass = definition.mechanic() == Mechanic.WARCRY && definition.cooldownBypass() != null
                    ? (int) Math.round(build.profile().effective(sheet).flat(tw.exilecore.stats.SupportKeys.WARCRY_RAGE_BYPASS, build.profile().tags())) : 0;
            if (rageBypass > 0) {
                lines.add("<gray>冷卻中按下去：花 <white>" + rageBypass + "</white> 點怒氣直接吼（怒嘯）"); // 2026-10-26
            } else if (definition.cooldownBypass() != null) {
                final String id = definition.cooldownBypass();
                final String color = chargeColor(id);
                final int held = chargesHeld(player, id);
                lines.add("<gray>冷卻中按下去：消耗一顆<" + color + ">" + ItemRenderer.escape(chargeName(id)) + "</" + color + ">直接施放"
                        + (held > 0 ? "（現在 <white>" + held + "</white> 顆）" : ""));
            }
        }
        if (build.manaCost() > 0) {
            final ChargeSpec drainSpec = config.charge(build.definition().id());
            lines.add("<gray>魔力消耗：<#5599ff>" + (drainSpec != null && drainSpec.drain() ? drainSpec.drainCost(build, sheet) : build.manaCost()) // 2026-10-28：穿雲箭的攻擊速度倍率
                    + (drainSpec != null && drainSpec.drain() ? "<gray>／秒（引導中逐秒扣）" : "")); // 2026-10-26 照 PoE2 的每分鐘魔力消耗
        }
        // 2026-10-27：血滲、傲寒（放的時候才加）照滿魔力估；「擊中傷害更少」只乘平均每次（異常照沒減的算）；這次之前說明都沒算
        final SkillBuild estimate = LineageMechanics.estimated(build, PlayerState.maxMana(sheet));
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, estimate.profile());
        if (deals) {
            final double hitOnly = SupportMechanics.hitOnlyFactor(sheet, estimate.profile());
            final DamagePacket hitExpected = hitOnly == 1.0 ? expected : new DamagePacket(expected).scale(hitOnly);
            // 2026-10：好幾種傷害（噬異爆的火、冰、雷）先寫合計，括號裡再分開寫（實際打中時本來就是加在一起）
            final long kinds = java.util.Arrays.stream(tw.exilecore.stats.DamageType.values()).filter(type -> hitExpected.get(type) > 0).count();
            lines.add(kinds > 1
                    ? String.format(Locale.ROOT, "<gray>平均每次：<white>%d <dark_gray>（%s）", Math.round(hitExpected.total()), hitExpected)
                    : String.format(Locale.ROOT, "<gray>平均每次：<white>%s", hitExpected.isEmpty() ? "0" : hitExpected));
            if (StatKeys.flag(estimate.profile().effective(sheet), tw.exilecore.stats.LineageKeys.LOW_LIFE_ONLY)) {
                lines.add("<#ff6a6a>掠獸：只能傷害生命 35% 以下的敵人（其他敵人打了沒有傷害）"); // 2026-10-27
            }
            // 這個技能自己的暴擊（照它的標籤：攻擊吃攻擊暴擊、法術吃法術暴擊；輔助寶石也算進去）
            final double amazonCrit = DamageCalculator.amazonCritBonus(build.profile().effective(sheet), build.profile(),
                    tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
            final double skillCrit = DamageCalculator.critChance(sheet, build.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
            lines.add(String.format(Locale.ROOT, "<gray>暴擊：<white>%.1f%% <dark_gray>× <white>%.0f%%", skillCrit * 100,
                    DamageCalculator.critMultiplier(build.profile().effective(sheet), build.profile().tags(), null) * 100)
                    + (amazonCrit > 0 && skillCrit > 0 ? String.format(Locale.ROOT, " <dark_gray>（含命中溢出 +%.1f%%）", amazonCrit * 100) : ""));
            final int extraTargets = SkillExecutor.extraStrikeTargets(sheet, build);
            if (extraTargets > 0) {
                lines.add("<gray>打擊：一次打 <white>" + (1 + extraTargets) + "</white> 個敵人（多 " + extraTargets + " 個附近的）");
            }
            final double splash = definition.mechanic() == Mechanic.STRIKE ? SkillExecutor.meleeSplash(sheet, build) : 0;
            if (splash > 0) {
                lines.add(String.format(Locale.ROOT, "<gray>近戰擴散：打中的目標周圍 <white>%.1f</white> 格的敵人受到 <white>%d%%</white> 的傷害",
                        SkillExecutor.MELEE_SPLASH_RADIUS * build.areaScale(), Math.round(splash)));
            }
        }
        // 每秒次數一律顯示（以前有冷卻的技能只在攻速比冷卻慢時才顯示，裝不裝迅擊會讓這行忽隱忽現）
        // 光環放在面板上就開著，每秒幾次沒有意義，不顯示
        if (definition.mechanic() != Mechanic.AURA) {
            final DeploySpec deploy = build.deploy();
            if (deploy != null && deploy.kind() == DeploySpec.Kind.TOTEM) {
                // 2026-10-20（照 PoE）：圖騰攻擊吃你的攻速（法術圖騰吃施法速度）；英靈圖騰叫出的英靈照固定間隔，不吃
                lines.add(String.format(Locale.ROOT, "<gray>放圖騰：每秒最多 <white>%.2f<gray> 次", 20.0 / build.intervalTicks(actionSheet)));
                if (deploy.interval() > 0) {
                    final boolean linked = SkillExecutor.hasFlag(definition, StatKeys.FLAG_TOTEM_LINKED);
                    final SkillBuild ancestor = linked && linkedMace(state, sheet, definition) instanceof SkillBuild found ? found : null;
                    final double rate = ancestor != null ? 20.0 / SkillDeployables.ancestorTicks(build, ancestor, sheet)
                            : 20.0 / SkillDeployables.totemTicks(build, sheet, build.totems()); // 2026-10-26：崩震圖騰照武器攻速的一半
                    lines.add(String.format(Locale.ROOT, "<gray>每個圖騰每秒 <white>%.2f<gray> 次（滿 %d 個合計 <white>%.2f<gray> 次）%s",
                            rate, build.totems(), build.totems() * rate,
                            linked ? "　<dark_gray>（" + (ancestor == null ? "孔裡沒有放能用的錘子技能" : ItemRenderer.escape(ancestor.definition().name())
                                    + (linkedMaces(state, sheet, definition).size() > 1 ? "等（英靈輪流用）" : "") + "的攻擊時間 ×1.5")
                                    + " ÷ 你的攻速）"
                                    : build.isAttack() ? "　<dark_gray>（吃你的攻擊速度）" : "　<dark_gray>（吃你的施法速度）"));
                    if (ancestor != null) {
                        lines.addAll(ancestorLines(state, sheet, ancestor)); // 2026-10-23：英靈那一下的傷害、暴擊、輔助
                    }
                }
            } else if (config.meta(definition.id()) != null && config.meta(definition.id()).totem()) {
                // 2026-10-21：法術圖騰寶石的施放速度是「放圖騰」的速度（圖騰放插槽法術的速度寫在上面的主要寶石那幾行）
                lines.add(String.format(Locale.ROOT, "<gray>放圖騰：每秒最多 <white>%.2f<gray> 次", 20.0 / build.intervalTicks(actionSheet)));
            } else {
                // 2026-10-22：有冷卻的照冷卻算（以前只看施放時間：掠閃寫每秒 10 次、印記 4.98 次、千鈞砸 1.12 次，實際被冷卻卡住）
                final double interval = build.intervalTicks(actionSheet);
                if (build.cooldownTicks() > interval && definition.mechanic() != Mechanic.AURA) {
                    lines.add(String.format(Locale.ROOT, "<gray>每秒 <white>%.2f<gray> 次 <dark_gray>（受冷卻限制；不算冷卻每秒 %.2f 次）",
                            hitsPerSecond(repeatGap(build, interval), build.cooldownTicks(), build.repeats()), 20.0 / interval));
                } else {
                    lines.add(String.format(Locale.ROOT, "<gray>每秒 <white>%.2f<gray> 次", 20.0 / interval));
                }
            }
        }
        if (build.repeats() > 0) {
            lines.add(definition.mechanic() == Mechanic.FLICKER
                    ? String.format(Locale.ROOT, "<gray>複擊：按一次連閃 <white>%d</white> 次（魔力只扣一次，閃完才開始冷卻）", build.repeats() + 1)
                    : String.format(Locale.ROOT, "<gray>複擊：按一次連打 <white>%d</white> 下（魔力只扣一次，打完才能做別的）", build.repeats() + 1));
        }
        if (deals) {
            lines.addAll(AilmentText.lines(build.profile().effective(sheet), build.profile(), expected)); // 點燃、冰緩、凍結、感電的加成
        }
        if (!build.supports().isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final SupportDefinition support : build.supports()) {
                names.add(GemAttributes.supportTag(support.id()) + ItemRenderer.escape(support.name()) // 2026-10-26：照顏色（這次之前一律淡藍）
                        + (MinionTypes.throughMinions(support, definition) ? "<dark_gray>（給召喚物）" : "")); // 2026-10-29 照 PoE2 的 Minion Types
            }
            lines.add("<gray>輔助：" + String.join("<gray>、", names));
        }
        if (build.bonusLevel() > 0) {
            lines.add("<#8fd3ff>裝備 +" + build.bonusLevel() + " 技能等級<gray>：實際以 Lv." + build.level() + " 施放");
        }
        if (!build.isDefault()) {
            final int required = config.requiredLevel(build.gemLevel());
            if (build.levelCapped()) {
                lines.add("<red>需要角色等級 " + required + "，目前照 Lv." + (build.level() - build.bonusLevel()) + " 算");
            } else {
                lines.add("<gray>需要角色等級 " + required);
            }
        }
        if (!definition.weapons().isEmpty()) {
            lines.add((definition.usableWith(state.weaponTags()) ? "<gray>" : "<red>") + "需要" + definition.weaponText());
        }
        return lines;
    }

    /** 「裝備加成」只列這些屬性（傷害、暴擊、速度、技能等級／範圍／投射物…、異常、穿透、光環／戰吼／詛咒效果）。 */
    private static final List<String> GEAR_STAT_PREFIXES = List.of("damage", "crit.", "attack.speed", "cast.speed", "accuracy", "skill.",
            "penetration", "ailment.duration", "bleed.", "ignite.", "poison.", "shock.", "chill.", "freeze.", "electrocute.", "stun.buildup",
            "stun.duration", "daze.chance", "pin.chance", "armour.break", "exposure.", "curse.effect", "mark.effect", "aura.effect", "herald.effect",
            "warcry.", "totem.", "infusion.", "minion.", "crossbow.", "grenade.", "seal.", "channel.", "combo.", "rage.effect");

    /** 沒寫條件標籤的速度、命中照技能種類分：法術不列攻速、命中，攻擊不列施法速度；不造成傷害的技能（光環、增益）不列傷害類。 */
    private static boolean relevantTo(final SkillDefinition definition, final java.util.Set<String> context, final String stat) {
        if (stat.startsWith("attack.speed") || stat.startsWith("accuracy")) {
            return definition.isAttack();
        }
        if (stat.startsWith("cast.speed")) {
            return !definition.isAttack();
        }
        // 2026-10：沒寫條件標籤的詞綴也只列真的用得到的技能（以前戰吼、召喚技能也列出「投射物速度」「弩的裝填」）
        if (stat.startsWith("crossbow.")) {
            return definition.weapons().contains(tw.exilecore.stats.Tags.CROSSBOW) || context.contains(tw.exilecore.stats.Tags.CROSSBOW);
        }
        if (stat.startsWith("skill.projectile") || stat.equals("skill.pierce") || stat.equals("skill.fork")) {
            return context.contains(tw.exilecore.stats.Tags.PROJECTILE);
        }
        if (stat.equals("skill.chains")) {
            return context.contains(tw.exilecore.stats.Tags.PROJECTILE) || definition.mechanic() == Mechanic.CHAIN;
        }
        if (stat.equals("skill.area")) {
            return context.contains(tw.exilecore.stats.Tags.AREA);
        }
        if (stat.equals("skill.duration")) {
            return context.contains(tw.exilecore.stats.Tags.DURATION);
        }
        if (stat.equals("skill.cooldown") || stat.equals("skill.added_cooldown")) {
            return definition.cooldown() > 0;
        }
        if (stat.equals(tw.exilecore.stats.KeystoneKeys.SKILL_LIMIT)) {
            return context.contains(SkillLimits.TAG) || definition.mechanic() == Mechanic.ZONE; // 2026-10-28：+x 上限只列有上限的技能
        }
        if (stat.equals("skill.strike_targets") || stat.equals("skill.melee_splash") || stat.startsWith("combo.")) {
            return context.contains(tw.exilecore.stats.Tags.MELEE);
        }
        if (stat.equals("skill.totems") || stat.startsWith("totem.")) {
            return context.contains(tw.exilecore.stats.Tags.TOTEM);
        }
        if (stat.equals("skill.minions") || stat.startsWith("minion.")) {
            return context.contains(tw.exilecore.stats.Tags.MINION);
        }
        if (stat.startsWith("warcry.")) {
            return context.contains(tw.exilecore.stats.Tags.WARCRY);
        }
        if (stat.startsWith("curse.")) {
            return context.contains("curse");
        }
        if (stat.startsWith("aura.")) {
            return context.contains(tw.exilecore.stats.Tags.AURA) || context.contains(tw.exilecore.stats.Tags.HERALD);
        }
        if (stat.startsWith("herald.")) {
            return context.contains(tw.exilecore.stats.Tags.HERALD);
        }
        if (stat.startsWith("grenade.")) {
            return context.contains(tw.exilecore.stats.Tags.GRENADE);
        }
        if (stat.startsWith("seal.")) {
            return context.contains("seal");
        }
        if (stat.startsWith("channel.")) {
            return context.contains("channelling") || context.contains("channel");
        }
        if (stat.startsWith("infusion.")) {
            return context.contains(tw.exilecore.stats.Tags.FIRE) || context.contains(tw.exilecore.stats.Tags.COLD)
                    || context.contains(tw.exilecore.stats.Tags.LIGHTNING);
        }
        if (!definition.dealsDamage()) {
            // 不造成傷害的技能（光環、增益、召喚）：傷害、暴擊、穿透、異常、暈眩這些都用不到
            return !(stat.startsWith("damage") || stat.startsWith("crit.") || stat.startsWith("penetration") || stat.startsWith("ailment.")
                    || stat.startsWith("bleed.") || stat.startsWith("ignite.") || stat.startsWith("poison.") || stat.startsWith("shock.")
                    || stat.startsWith("chill.") || stat.startsWith("freeze.") || stat.startsWith("electrocute.") || stat.startsWith("stun.")
                    || stat.startsWith("daze.") || stat.startsWith("pin.") || stat.startsWith("armour.break") || stat.startsWith("exposure."));
        }
        return true;
    }

    private static boolean gearStat(final String stat) {
        for (final String prefix : GEAR_STAT_PREFIXES) {
            if (stat.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 裝備對這個技能的加成（2026-10，技能面板 Shift＋右鍵）：身上每件生效的裝備裡，條件標籤吃得到這個技能
     * （技能自己的標籤＋它打出的傷害類型）的詞綴；上面先給合計（技能等級、增加／更多傷害、暴擊、速度）。
     */
    public List<String> gearBonusLines(final Player player, final SkillBuild build) {
        final java.util.Set<String> context = hintContext(plugin.players().sheet(player), build);
        double levels = 0;
        double increased = 0;
        double more = 1;
        double critInc = 0;
        double speedInc = 0;
        final String speedStat = build.definition().isAttack() ? StatKeys.ATTACK_SPEED : StatKeys.CAST_SPEED;
        final List<String> items = new ArrayList<>();
        int count = 0;
        for (final tw.exilecore.character.PlayerStateService.GearPiece piece : plugin.players().activeGear(player)) {
            final List<Modifier> useful = new ArrayList<>();
            final List<Modifier> conditional = new ArrayList<>();
            for (final Modifier mod : piece.modifiers()) {
                if (!gearAffects(build.definition(), context, mod)) {
                    continue;
                }
                (piece.conditional().contains(mod) ? conditional : useful).add(mod);
                if (piece.conditional().contains(mod)) {
                    continue; // 合計只算一直有的
                }
                if (mod.stat().equals(StatKeys.SKILL_LEVEL) && mod.type() == tw.exilecore.stats.ModType.FLAT) {
                    levels += mod.value();
                } else if (mod.stat().equals(StatKeys.DAMAGE) && mod.type() == tw.exilecore.stats.ModType.INCREASED) {
                    increased += mod.value();
                } else if (mod.stat().equals(StatKeys.DAMAGE) && mod.type() == tw.exilecore.stats.ModType.MORE) {
                    more *= 1 + mod.value();
                } else if (mod.stat().equals(StatKeys.CRIT_CHANCE) && mod.type() == tw.exilecore.stats.ModType.INCREASED) {
                    critInc += mod.value();
                } else if (mod.stat().equals(speedStat) && mod.type() == tw.exilecore.stats.ModType.INCREASED) {
                    speedInc += mod.value();
                }
            }
            if (useful.isEmpty() && conditional.isEmpty()) {
                continue;
            }
            count += useful.size() + conditional.size();
            items.add("<" + piece.color() + ">" + ItemRenderer.escape(piece.name()) + " <dark_gray>（" + ItemRenderer.escape(piece.slot()) + "）");
            for (final String text : ModifierText.describeAll(useful)) {
                items.add("<#8fd3ff>　" + ItemRenderer.escape(text));
            }
            for (final String text : ModifierText.describeAll(conditional)) {
                items.add("<#8fd3ff>　" + ItemRenderer.escape(text) + " <dark_gray>（有條件）");
            }
        }
        final List<String> lines = new ArrayList<>();
        if (items.isEmpty()) {
            lines.add("<gray>你身上的裝備沒有對這個技能有效的詞綴。");
            lines.add("<dark_gray>（只看生效中的裝備；需求不符的不算）");
            return lines;
        }
        final List<String> totals = new ArrayList<>();
        if (levels > 0) {
            totals.add("技能等級 <white>+" + ModifierText.number(levels));
        }
        if (increased != 0) {
            totals.add("增加傷害 <white>" + (increased > 0 ? "+" : "") + Math.round(increased * 100) + "%");
        }
        if (more != 1) {
            totals.add(String.format(Locale.ROOT, "更多傷害 <white>×%.2f", more));
        }
        if (critInc != 0) {
            totals.add("暴擊率 <white>+" + Math.round(critInc * 100) + "%");
        }
        if (speedInc != 0) {
            totals.add((build.definition().isAttack() ? "攻擊速度" : "施法速度") + " <white>+" + Math.round(speedInc * 100) + "%");
        }
        lines.add("<gray>你的裝備有 <white>" + count + "</white> 條詞綴吃得到「" + ItemRenderer.escape(build.definition().name()) + "」");
        if (!totals.isEmpty()) {
            lines.add("<gray>合計：" + String.join("<gray>、", totals));
        }
        lines.add("");
        lines.addAll(items);
        lines.add("");
        lines.add("<dark_gray>照這個技能的標籤與傷害類型篩選；天賦、光環、輔助寶石不在這裡");
        return lines;
    }

    /** 這條裝備詞綴吃不吃得到這個技能（context 見 {@link #hintContext}）。 */
    public static boolean gearAffects(final SkillDefinition definition, final java.util.Set<String> context, final Modifier mod) {
        return gearStat(mod.stat()) && mod.appliesTo(context) && relevantTo(definition, context, mod.stat());
    }

    /** 拿來判斷詞綴吃不吃得到的情境標籤：技能自己的標籤、等級標籤、它實際打出的傷害類型。 */
    private java.util.Set<String> hintContext(final StatSheet sheet, final SkillBuild build) {
        final java.util.Set<String> context = new java.util.HashSet<>(build.profile().tags());
        context.addAll(SkillBuild.levelTags(build.definition()));
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, build.profile());
        for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
            if (expected.get(type) > 0) {
                context.addAll(build.profile().contextFor(type));
            }
        }
        return java.util.Set.copyOf(context);
    }

    /** 技能面板上的一個技能（滑鼠指到裝備、輔助寶石時的提示用；主執行緒算好，封包那邊只讀）。 */
    public record SkillHint(String id, String name, SkillDefinition definition, java.util.Set<String> context) {
    }

    /** 這個玩家技能面板上的技能（重複的只算一次）。 */
    public List<SkillHint> skillHints(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final List<SkillHint> hints = new ArrayList<>();
        if (!state.isLoaded() || !state.hasCharacter()) {
            return hints;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillLoadout loadout = state.skills();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < SkillLoadout.MAX_SKILLS; i++) {
            final SkillLoadout.Entry entry = loadout.skill(i);
            if (entry == null || !seen.add(entry.skillId())) {
                continue;
            }
            final SkillDefinition definition = config.skill(entry.skillId());
            if (definition == null) {
                continue;
            }
            try {
                hints.add(new SkillHint(definition.id(), definition.name(), definition, hintContext(sheet, build(state, sheet, definition, entry))));
            } catch (final RuntimeException e) {
                // 算不出來的技能（設定有問題）就不提示
            }
        }
        return hints;
    }

    /** 面板上一個技能的裝備加成（Shift＋右鍵）。 */
    public List<String> gearBonusLines(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        return gearBonusLines(player, build(plugin.players().get(player), plugin.players().sheet(player), definition, entry));
    }

    /** 有幾條裝備詞綴吃得到這個技能（技能面板的提示用）。 */
    public int gearBonusCount(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        return gearBonusCount(player, build(plugin.players().get(player), plugin.players().sheet(player), definition, entry));
    }

    /** 同上（已經算好的 build）。 */
    public int gearBonusCount(final Player player, final SkillBuild build) {
        final List<String> lines = gearBonusLines(player, build);
        int count = 0;
        for (final String line : lines) {
            if (line.startsWith("<#8fd3ff>　")) {
                count++;
            }
        }
        return count;
    }

    /** 2026-10-28：灌注版整組換掉的數值的說明（20 級的值）。 */
    static List<String> infusedSetLines(final ResourceSpec.InfusedSet set) {
        if (set == null) {
            return List.of();
        }
        final int level = 20;
        final List<String> lines = new ArrayList<>();
        if (set.projectiles() != null) {
            lines.add(String.format(Locale.ROOT, "<gray>　%s射出 <white>%d</white> 顆（%d 級）", set.nova() ? "改成往四周一圈" : "改成",
                    set.projectiles().intAt(level), level));
        } else if (set.nova()) {
            lines.add("<gray>　改成往四周一圈射出");
        }
        if (!set.damage().isEmpty()) {
            final List<String> parts = new ArrayList<>();
            for (final tw.exilecore.stats.DamageType type : tw.exilecore.stats.DamageType.values()) {
                final int[] table = set.damage().get(type);
                if (table != null) {
                    final double[] range = SkillProfiles.tableAt(table, level);
                    parts.add(String.format(Locale.ROOT, "%d–%d %s", Math.round(range[0]), Math.round(range[1]), DAMAGE_WORD.get(type)));
                }
            }
            lines.add(String.format(Locale.ROOT, "<gray>　基礎傷害改成 %s（%d 級）", String.join("＋", parts), level));
        }
        return lines;
    }

    private static final java.util.Map<tw.exilecore.stats.DamageType, String> DAMAGE_WORD = java.util.Map.of(
            tw.exilecore.stats.DamageType.PHYSICAL, "物理", tw.exilecore.stats.DamageType.FIRE, "火焰",
            tw.exilecore.stats.DamageType.COLD, "冰冷", tw.exilecore.stats.DamageType.LIGHTNING, "閃電",
            tw.exilecore.stats.DamageType.CHAOS, "混沌");

    /** 以前付了連段、榮耀才有的加成（2026-10 拿掉資源之後一律算上）。 */
    static List<tw.exilecore.stats.Modifier> resourceBonus(final ResourceSpec spec) {
        if (spec == null || spec.isEmpty() || spec.combo() <= 0 && spec.glory() <= 0) {
            return List.of();
        }
        final List<tw.exilecore.stats.Modifier> bonus = new ArrayList<>();
        for (final tw.exilecore.stats.Modifier mod : spec.perCombo()) {
            bonus.add(new tw.exilecore.stats.Modifier(mod.stat(), mod.type(), mod.value() * Math.max(1, spec.combo()), mod.tags(), mod.source()));
        }
        bonus.addAll(spec.empowered());
        return bonus;
    }

    /** 技能本身帶的加成（以前要付連段、榮耀的，2026-10 起直接有）。 */
    private List<String> resourceLines(final Player player, final SkillDefinition definition) {
        final List<String> lines = new ArrayList<>();
        final ResourceSpec spec = config.resource(definition.id());
        // 2026-10-26 照 PoE2：要連擊、榮耀、灌注的技能寫清楚
        if (spec.combo() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>要 <white>%d</white> 點連擊才能用（打擊打中敵人累積），用了就用掉%s", spec.combo(),
                    player == null ? "" : "（現在 " + resources.combo(player) + " 點）"));
        }
        // 戰旗的榮耀寫在 warcry.glory（不是戰吼：戰吼的說明不會列出來）
        final int gloryNeed = Math.max(spec.glory(), definition.warcry() != null && !definition.warcry().cry() ? definition.warcry().extras().glory() : 0);
        if (gloryNeed > 0) {
            final String from = switch (spec.gloryFrom() == null ? "attack_hit" : spec.gloryFrom()) {
                case "ailment" -> "讓敵人冰凍、感電、點燃各 1 點";
                case "heavy_stun" -> "重暈敵人時照牠的強度";
                case "ignite" -> "點燃敵人時照牠的強度";
                case "elemental_hit" -> "用元素傷害擊中敵人時照牠的強度";
                case "chaos_hit" -> "用混沌傷害擊中敵人時照牠的強度";
                default -> "攻擊打中敵人時照牠的強度";
            };
            lines.add(String.format(Locale.ROOT, "<gray>要 <white>%d</white> 點榮耀才能用（%s獲得），用了就用掉；沒有冷卻%s", gloryNeed, from,
                    player == null ? "" : String.format(Locale.ROOT, "（現在 %d 點）", (int) Math.floor(supportMechanics().glory(player, definition.id())))));
        }
        if (!spec.infusion().isEmpty() && spec.infusionEach()) {
            // 2026-10-26 照 PoE2（流焰天幕）：每一種各用一層、各有加成
            for (final String element : spec.infusion()) {
                lines.add("<gray>身上有" + infusionWord(element) + "灌注時用掉一層，這一下：");
                for (final String effect : ModifierText.describeAll(spec.infusedFor(element))) {
                    lines.add("<gray>　" + effect);
                }
            }
        } else if (!spec.infusion().isEmpty()) {
            lines.add("<gray>身上有" + String.join("或", spec.infusion().stream().map(SkillService::infusionWord).toList()) + "灌注時用掉一層，這一下：");
            lines.addAll(infusedSetLines(spec.infusedSet())); // 2026-10-28 照 PoE2（游雷的冰灌注版）
            for (final String effect : ModifierText.describeAll(spec.infused())) {
                lines.add("<gray>　" + effect);
            }
        }
        if (spec.remnant() != null) { // 2026-10-26 照 PoE2：留下灌注殘骸
            lines.add("<gray>留下" + ("random".equals(spec.remnant()) ? "隨機一種" : infusionWord(spec.remnant())) + "灌注殘骸：走過去撿到獲得那種灌注");
        }
        final List<tw.exilecore.stats.Modifier> bonus = resourceBonus(spec);
        if (!bonus.isEmpty()) {
            lines.add("<gray>這個技能本身：");
            for (final String effect : ModifierText.describeAll(bonus)) {
                lines.add("<gray>　" + effect);
            }
        }
        return lines;
    }

    /** 連珠（2026-10）的說明：重複幾次、少多少傷害、戰星、冷卻。 */
    private void barrageLines(final Player player, final SkillBuild build, final List<String> lines) {
        final BarrageSpec barrage = config.barrage(build.definition().id());
        if (barrage == null || build.definition().params().mark() == null) {
            return;
        }
        final SkillParams.Mark guard = build.definition().params().mark();
        // 2026-10-30：封釋（PoE2 Unleash，spells: true）強化的是法術，說明照法術寫；這次之前跟連珠一樣寫「弓或矛的投射物攻擊」「攻擊時間」
        final boolean spells = barrage.spells();
        lines.add(String.format(Locale.ROOT, "<gray>放了之後 <white>%.1f</white> 秒內，你下一次<white>%s</white>自動重複 <white>%d</white> 次",
                guard.duration() * build.durationScale(), spells ? "可以重複的法術" : "弓或矛的投射物攻擊", barrage.repeats()));
        lines.add(String.format(Locale.ROOT, "<gray>　每次重複少 <white>%s%%</white> 傷害、隔 %s 秒、不花魔力；%s",
                ModifierText.number(barrage.less()), ModifierText.number(barrage.gap()),
                spells ? "每次都是重新放，可以再打到同一個敵人" : "每次都是重新射，可以再打到同一個敵人"));
        if (barrage.charge() != null && barrage.perCharge() > 0) {
            final String color = chargeColor(barrage.charge());
            final int held = player == null ? 0 : chargesHeld(player, barrage.charge());
            lines.add("<gray>　施放時用掉身上全部的<" + color + ">" + ItemRenderer.escape(chargeName(barrage.charge())) + "</" + color
                    + ">，每顆多重複 <white>" + barrage.perCharge() + "</white> 次" + (held > 0 ? "（現在 <white>" + held + "</white> 顆）" : ""));
        }
        lines.add(String.format(Locale.ROOT, "<gray>　用掉之後的冷卻：%s 秒＋那一招%s時間的 <white>%s%%</white>（最多 %s 秒）",
                ModifierText.number(build.definition().cooldown()), spells ? "施法" : "攻擊", ModifierText.number(barrage.cooldownAttackTime().at(build.level())),
                ModifierText.number(barrage.maxCooldown())));
        lines.add(spells ? "<dark_gray>　有冷卻的法術、引導的法術、不會造成傷害的法術不能被強化" : "<dark_gray>　有冷卻的攻擊、引導技能不能被強化");
    }

    /**
     * 2026-10-26：網頁圖鑑（/codex/values）用——技能面板上不看玩家的那幾段說明（圖騰／陷阱／鐘、戰吼、範圍、投射物、持續…），
     * 跟面板同一套文字；屬性表照傳進來的算（圖鑑傳空的＝基礎值）。只讀，不改任何東西。
     */
    public static List<String> codexLines(final SkillBuild build, final StatSheet sheet) {
        final List<String> lines = new ArrayList<>();
        if (build.deploy() != null) {
            lines.addAll(deployLines(build, sheet, 0));
        }
        if (build.definition().warcry() != null) {
            lines.addAll(warcryLines(build));
        }
        mechanicLines(build, lines);
        return lines;
    }

    private static void mechanicLines(final SkillBuild build, final List<String> lines) {
        final SkillParams params = build.params();
        switch (build.definition().mechanic()) {
            case STRIKE -> {
                if (build.targets() != 1) {
                    lines.add(String.format(Locale.ROOT, "<gray>打中前方 %.1f 格、%d° 內%s的敵人", build.reach(),
                            Math.round(build.angle()), build.targets() == 0 ? "所有" : "最多 " + build.targets() + " 個"));
                }
                if (build.knockback() > 0) {
                    lines.add("<gray>擊退敵人");
                }
                if (params.execute() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>處決：目標生命低於 <white>%d%%</white> 時更多 <white>%d%%</white> 傷害",
                            Math.round(params.execute().below() * 100), Math.round(params.execute().more())));
                }
            }
            case PROJECTILE -> {
                if (build.projectiles() > 1) {
                    lines.add("<gray>一次射出 <white>" + build.projectiles() + "</white> 發"
                            + (build.explode() > 0 ? "<dark_gray>（落在一起時，同一個敵人一次只算一發）" : ""));
                }
                if (params.burst() != null) {
                    lines.add("<gray>連續 <white>" + params.burst().count() + "</white> 波");
                }
                if (params.fuse() > 0) {
                    lines.add(String.format(Locale.ROOT, "<gray>榴彈：拋出去彈跳，<white>%.1f</white> 秒後炸開", params.fuse()));
                }
                if (build.explode() > 0) {
                    lines.add(String.format(Locale.ROOT, "<gray>爆炸半徑 <white>%.1f</white> 格", build.explode()));
                }
                if (build.pierce() > 0) {
                    lines.add("<gray>穿透 <white>" + build.pierce() + "</white> 個敵人");
                }
                if (build.chains() > 0) {
                    lines.add("<gray>連鎖 <white>" + build.chains() + "</white> 次");
                }
                if (build.forks() > 0) {
                    lines.add("<gray>第一次打中時分裂成兩發（<white>" + build.forks() + "</white> 次）");
                }
                if (params.splash() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>打中時電到附近 <white>%d</white> 個敵人（<white>%d%%</white> 傷害）",
                            params.splash().targets(build.level()), Math.round(params.splash().damage(build.level()))));
                }
                if (params.scatter() != null && params.scatter().onHit()) {
                    // 2026-10-26 照 PoE2（凝霜箭的冰片）
                    lines.add(String.format(Locale.ROOT, "<gray>打中敵人時往前方噴出 <white>%d</white> 片（扇形 %d°，各 <white>%d%%</white> 傷害效益）",
                            params.scatter().count(build.level()), Math.round(params.scatter().spread()),
                            Math.round((params.scatter().effectiveness() > 0 ? params.scatter().effectiveness() : params.scatter().damage() / 100.0) * 100)));
                } else if (params.scatter() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>停下時散射 <white>%d</white> 支（各 <white>%d%%</white> 傷害）",
                            params.scatter().count(build.level()), Math.round(params.scatter().damage())));
                }
                if (params.vortex() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>停下時留下漩渦 <white>%.1f</white> 秒：把敵人往中心吸（玩家不會被吸）、每 %.1f 秒 <white>%d%%</white> 傷害",
                            params.vortex().duration() * build.durationScale(), params.vortex().interval(), Math.round(params.vortex().damage())));
                    lines.add("<dark_gray>　同一招的漩渦疊在一起只算一個（不疊加）");
                }
                if (params.ground() != null) {
                    lines.add(zoneLine(build, params.ground(), "停下時留下"));
                    if (build.projectiles() > 1) {
                        lines.add("<dark_gray>　每發各留一灘，疊在一起只算一灘；同時最多 "
                                + (params.ground().max() > 0 ? params.ground().max() : build.projectiles()) + " 灘");
                    } else {
                        lines.add("<dark_gray>　同一招的地面效果疊在一起只算一灘（不疊加）");
                    }
                }
                if (params.blink()) {
                    lines.add("<gray>停下來的地方你會瞬移過去");
                }
                if (build.returning()) {
                    lines.add("<gray>停下來之後飛回你身邊，路上再打一次");
                }
            }
            case NOVA -> {
                lines.add(String.format(Locale.ROOT, "<gray>半徑 <white>%.1f</white> 格", build.radius()));
                if (params.ground() != null) {
                    lines.add(zoneLine(build, params.ground(), "炸完留下"));
                    lines.add("<dark_gray>　同一招的地面效果疊在一起只算一灘（不疊加）");
                }
                if (params.aftershock() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>餘震：<white>%.1f</white> 秒後再炸一次，半徑 ×%.1f、更多 <white>%d%%</white> 傷害",
                            params.aftershock().delay(), params.aftershock().radius(build.level()), Math.round(params.aftershock().more(build.level()))));
                }
            }
            case CONE -> {
                lines.add(String.format(Locale.ROOT, "<gray>前方 <white>%.1f</white> 格、%d° 的扇形",
                        build.length(), Math.round(build.angle())));
                if (params.aftershock() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>第二擊：<white>%.1f</white> 秒後在前方再砸一圈（半徑 <white>%.1f</white> 格），更多 <white>%d%%</white> 傷害",
                            params.aftershock().delay(), build.length() * params.aftershock().radius(build.level()), Math.round(params.aftershock().more(build.level()))));
                }
            }
            case LEAP -> lines.add(String.format(Locale.ROOT, "<gray>最遠跳 <white>%.0f</white> 格，落地半徑 <white>%.1f</white> 格",
                    build.range(), build.radius()));
            case CHAIN -> {
                // 2026-10-28 照 PoE2：鏈雷的閃電是投射物，多的投射物各放一道（SkillExecutor.chain）；這次之前不顯示（也只放一道）
                final int beams = SkillExecutor.chainBeams(build.definition(), build.projectiles());
                if (beams > 1) {
                    lines.add("<gray>一次放出 <white>" + beams + "</white> 道" + (build.spread() >= 360 - 1e-9 ? "，往四周一圈" : "，扇形排開")
                            + "<dark_gray>（同一個敵人一次只會被其中一道打到）");
                }
                lines.add("<gray>連鎖 <white>" + build.chains() + "</white> 次");
            }
            case DASH -> {
                if (params.dash().whirls()) {
                    // 2026-10-28 照 PoE2 Whirling Assault：整個動作時間一路往前走、沿路轉好幾圈；這次之前跟別的位移一樣寫「瞬移」
                    lines.add(String.format(Locale.ROOT, "<gray>整個動作時間裡往前方走最遠 <white>%.1f</white> 格（被牆擋住就停、被釘住就原地轉）", build.range()));
                    lines.add(String.format(Locale.ROOT, "<gray>沿路轉 <white>%d</white> 圈，每一圈打身邊半徑 <white>%.1f</white> 格的敵人<dark_gray>（各是一個打擊範圍）",
                            params.dash().spins(), params.dash().spinRadius() * build.areaScale()));
                    break;
                }
                lines.add(String.format(Locale.ROOT, "<gray>往前方瞬移最遠 <white>%.0f</white> 格（被牆擋住就停）", build.range()));
                if (params.dash().hitWidth() > 0) {
                    lines.add("<gray>順路打中沿途的敵人" + (build.knockback() > 0 ? "並擊退" : ""));
                }
                if (params.dash().startRadius().last() > 0) {
                    lines.add(String.format(Locale.ROOT, "<gray>起點炸開半徑 <white>%.1f</white> 格", build.scaledRadius(params.dash().startRadius())));
                }
                if (params.dash().endRadius().last() > 0) {
                    lines.add(String.format(Locale.ROOT, "<gray>終點炸開半徑 <white>%.1f</white> 格", build.scaledRadius(params.dash().endRadius())));
                }
                if (params.ground() != null) {
                    lines.add(zoneLine(build, params.ground(), "沿路留下"));
                    lines.add("<dark_gray>　同一招的地面效果疊在一起只算一灘（不疊加）");
                }
            }
            case IMPACT -> {
                lines.add(String.format(Locale.ROOT, "<gray>準心指的地方（最遠 %.0f 格）<white>%.1f</white> 秒後落下，半徑 <white>%.1f</white> 格",
                        build.range(), params.delay(), build.radius()));
                // 2026-10-27：多的投射物各落一道（SkillExecutor.impact）
                // 2026-10-28 照 PoE2：衝霄弩台的弩彈落地後再炸開（cascade）；落釘弩台一輪落下好幾擊（投射物數量的加成加在每輪的擊中次數上）
                final SkillConfig current = SkillConfig.current();
                final SkillConfig.CascadeSpec cascade = current == null ? null : current.cascade(build.definition().id());
                final boolean salvo = params.projectiles().last() > 1 && cascade == null;
                if (cascade != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>弩彈 <white>%d</white> 發；每發落地 <white>%.1f</white> 秒後炸開，半徑 <white>%.1f</white> 格，傷害效益 <white>%d%%</white>",
                            build.projectiles(), cascade.after(), cascade.radius() * build.areaScale(),
                            Math.round(cascade.effectiveness().at(build.level()) * 100)));
                    cascade.conversion().forEach((type, fraction) -> lines.add(String.format(Locale.ROOT,
                            "<dark_gray>　爆炸：%d%% 物理傷害轉換為%s傷害", Math.round(fraction * 100), type.displayName())));
                } else if (salvo) {
                    lines.add(String.format(Locale.ROOT, "<gray>每輪落下 <white>%d</white> 擊<dark_gray>（投射物數量的加成改成加在每輪的擊中次數上）",
                            build.projectiles()));
                } else if (build.definition().tags().contains(Tags.PROJECTILE) && build.projectiles() > 1) {
                    lines.add(String.format(Locale.ROOT, "<gray>投射物 <white>%d</white> 發", build.projectiles()));
                }
            }
            case ZONE -> {
                final SkillParams.Zone zone = params.zone();
                if (EntangleRoots.ID.equals(build.definition().id())) {
                    // 2026-10-30 照 PoE2 Entangle：裂隙＋藤蔓
                    final int limit = EntangleRoots.limit(build.level()) + SkillLimits.bonus(null, build); // 輔助給的上限（身上其他的沒算）
                    lines.add(String.format(Locale.ROOT, "<gray>裂隙：往前爬 <white>%.1f</white> 格，打中路上的敵人一次（100%% 傷害），留 <white>%.1f</white> 秒；同時最多 <white>%d</white> 道",
                            build.length(), zone.duration() * build.durationScale(), limit));
                    lines.add(String.format(Locale.ROOT, "<gray>藤蔓：每 0.1 秒纏住 <white>%.1f</white> 格內的一個敵人，每條 <white>%d%%</white> 傷害（擊中）、減速 15%%；"
                            + "一個敵人最多 3 條、每條最多 1 秒，拉開超過 <white>%.1f</white> 格就斷",
                            build.radius(), Math.round(EntangleRoots.vineRatio(build.level()) * 100),
                            tw.exilecore.core.Units.metres(EntangleRoots.VINE_LENGTH_METRES) * build.areaScale()));
                    break;
                }
                final String where = zone.line() ? String.format(Locale.ROOT, "面前一道 <white>%.0f</white> 格長的", build.length())
                        : build.range() <= 0 ? "腳下" : String.format(Locale.ROOT, "準心指的地方（最遠 %.0f 格）半徑 <white>%.1f</white> 格的", build.range(), build.radius());
                lines.add(String.format(Locale.ROOT, "<gray>在%s區域留 <white>%.1f</white> 秒，每 %.1f 秒對裡面的敵人造成 <white>%d%%</white> 傷害%s",
                        where, zone.duration() * build.durationScale(), zone.interval(), Math.round(zone.damage()),
                        zone.pull() > 0 ? "，並把敵人往中心吸（玩家不會被吸）" : ""));
                if (zone.bolts() != null) { // 2026-10-28 照 PoE2 Orb of Storms
                    lines.add(String.format(Locale.ROOT, "<gray>　每一波不是整圈都打：對 <white>%.1f</white> 格內隨機一個敵人放一道雷，連鎖 <white>%d</white> 次（每次跳 %.1f 格內的）",
                            zone.bolts().range() * build.areaScale(), zone.bolts().chains().intAt(build.level()), zone.bolts().chainRange()));
                }
                if (zone.shower() != null) { // 2026-10-28 照 PoE2 Volcano
                    lines.add(String.format(Locale.ROOT, "<gray>　每一波不是整圈都打：噴出 <white>%d</white> 顆火山彈，落在範圍內隨機的地方，炸到落點 <white>%.1f</white> 格內的敵人",
                            zone.shower().count().intAt(build.level()), zone.shower().radius() * build.areaScale()));
                }
                if (zone.max() != 1) {
                    lines.add("<dark_gray>　" + (zone.max() > 1 ? "同時最多 " + zone.max() + " 個；" : "") + "疊在一起只算一個（不疊加）");
                }
            }
            case MARK -> {
                final SkillParams.Mark mark = params.mark();
                final double fx = build.auraEffect(); // 2026-10：詛咒與印記效果（輔助）
                if (mark.curse()) {
                    final List<String> kinds = new ArrayList<>();
                    for (final String tag : mark.tags()) {
                        kinds.add(ModifierText.tagName(tag));
                    }
                    final String head = String.format(Locale.ROOT, "<gray>詛咒：準心指的地方（最遠 %.0f 格）半徑 <white>%.1f</white> 格內的敵人，<white>%.1f</white> 秒內",
                            build.range(), mark.radius(build.level()) * build.areaScale(), mark.duration(build.level()) * build.durationScale());
                    final List<String> parts = new ArrayList<>();
                    if (mark.taken() > 0) {
                        parts.add(String.format(Locale.ROOT, "受到的%s傷害增加 <white>%d%%</white>（誰打都算）",
                                kinds.isEmpty() ? "" : String.join("、", kinds), Math.round(mark.taken() * fx)));
                    }
                    final double slowPct = mark.slow().at(build.level()) * fx;
                    if (slowPct > 0) {
                        parts.add(String.format(Locale.ROOT, "移動速度慢 <white>%d%%</white>", Math.round(slowPct)));
                    }
                    for (final ScaledMod effect : mark.effects()) {
                        parts.add("<white>" + effect.describe(build.level(), fx) + "</white>");
                    }
                    lines.add(head + String.join("<gray>、", parts));
                    break;
                }
                if (!mark.effects().isEmpty()) {
                    final List<String> parts = new ArrayList<>();
                    for (final ScaledMod effect : mark.effects()) {
                        parts.add(effect.stat().equals(StatKeys.CRIT_BONUS_TAKEN)
                                ? String.format(Locale.ROOT, "暴擊傷害加成增加 <white>%d%%</white>", Math.round(effect.value().at(build.level()) * fx))
                                : "<white>" + effect.describe(build.level(), fx) + "</white>");
                    }
                    // 2026-10-28：誰打都算的印記（引雷印、霜縛印：牠受到的電擊／凍結累積…）寫「牠…（誰打都算）」；這次之前一律寫「對牠」
                    lines.add(String.format(Locale.ROOT, "<gray>標記準心指的敵人（最遠 %.0f 格）<white>%.1f</white> 秒：%s", build.range(),
                            mark.duration() * build.durationScale(), mark.selfOnly() ? "只有你對牠" : "牠") + String.join("<gray>、", parts)
                            + (mark.selfOnly() ? "" : "<gray>（誰打都算）"));
                    break;
                }
                lines.add(String.format(Locale.ROOT, "<gray>準心指的敵人（最遠 %.0f 格）<white>%.1f</white> 秒內%s受到的傷害增加 <white>%d%%</white>",
                        build.range(), mark.duration() * build.durationScale(), mark.selfOnly() ? "被你" : "", Math.round(mark.taken() * fx)));
            }
            case GUARD -> {
                final SkillParams.Mark guard = params.mark();
                if (guard.effects().isEmpty()) {
                    break; // 沒有增益的（連珠）：說明在 barrageLines
                }
                if (SkillExecutor.hasFlag(build.definition(), StatKeys.FLAG_RELOAD_ALL)) {
                    // 2026-10-28 照 PoE2 Emergency Reload
                    lines.add(String.format(Locale.ROOT, "<gray>強化每個弩技能彈匣的弩矢（發數＝那個技能的彈匣大小）：<white>更多 %s%% 傷害</white>，"
                                    + "<white>%.1f</white> 秒內或強化的弩矢用完為止",
                            trimNumber(emergencyMore(build, null) * 100), guard.duration() * build.durationScale()));
                    break;
                }
                final List<String> parts = new ArrayList<>();
                for (final ScaledMod effect : guard.effects()) {
                    parts.add("<white>" + effect.describe(build.level(), 1.0) + "</white>");
                }
                // 2026-10-27 照站長「放技能減速全部移除」：舉盾不再寫「移動速度慢」（不會變慢了）
                lines.add(String.format(Locale.ROOT, build.definition().weapons().isEmpty() ? "<gray>要拿盾：舉起來 <white>%.1f</white> 秒，" : "<gray><white>%.1f</white> 秒內：",
                        guard.duration() * build.durationScale())
                        + String.join("<gray>、", parts));
            }
            case DESECRATE -> lines.add(String.format(Locale.ROOT,
                    "<gray>準心 %.0f 格內的地方拖出 <white>%d</white> 具屍體，大小是同等級一般怪的 <white>%d%%</white>（同一處最多 8 具）",
                    build.range(), params.targets(), Math.round(params.percent().at(build.level()))));
            case CORPSE -> {
                if (DecomposeClouds.is(build)) {
                    // 2026-10-28 照 PoE2 Decompose：毒雲（這次之前跟骸爆一樣寫引爆屍體）
                    final double seconds = build.durationTicks(DecomposeClouds.seconds(
                            SkillZones.localFlat(build.profile(), tw.exilecore.stats.GemQualityKeys.DURATION_FLAT))) / 20.0;
                    lines.add(String.format(Locale.ROOT, "<gray>消耗準心附近 %.0f 格內最近的屍體（怪物死後 10 秒內），在那裡留下一團易燃的毒雲："
                                    + "半徑 <white>%.1f</white> 格，每秒擴大 20%%（最多 +80%%），持續 <white>%s</white> 秒，最多 <white>%d</white> 團",
                            params.search(), build.radius(), GemQualityText.number(seconds),
                            DecomposeClouds.limit(SkillLimits.bonus(null, build.profile()))));
                    lines.add(String.format(Locale.ROOT, "<gray>雲裡的敵人中毒，像受到屍體最大生命 <white>%.0f%%</white> 的混沌傷害一樣", DecomposeClouds.POISON_PERCENT));
                    lines.add("<gray>雲裡有點燃中的敵人、碰到點燃地面，或被你的引爆技能打到時炸開：對目前的範圍造成這個技能的火焰傷害，毒雲消失");
                    break;
                }
                // 2026-10-28：屍體生命的部分照 PoE2 是物理（這次之前寫火焰）；沒有這個部分的（腐屍爆）不寫「0%」
                final double percent = params.percent().at(build.level());
                lines.add(String.format(Locale.ROOT, "<gray>引爆準心附近 %.0f 格內最近的屍體（怪物死後 10 秒內）：半徑 <white>%.1f</white> 格", params.search(), build.radius())
                        + (percent > 0 ? String.format(Locale.ROOT, "，再加上屍體最大生命 <white>%s%%</white> 的物理傷害",
                        percent == Math.rint(percent) ? String.valueOf((long) percent) : String.format(Locale.ROOT, "%.1f", percent)) : ""));
            }
            case FLICKER -> {
                lines.add(String.format(Locale.ROOT, "<gray>瞬移到準心指的敵人身旁打一下；沒指到敵人就找 <white>%.0f</white> 格內最近的（看得到的）", build.range()));
                if (build.targets() != 1) {
                    lines.add(String.format(Locale.ROOT, "<gray>打中面前 %.1f 格、%d° 內%s的敵人", build.reach(),
                            Math.round(build.angle()), build.targets() == 0 ? "所有" : "最多 " + build.targets() + " 個"));
                }
                if (build.knockback() > 0) {
                    lines.add("<gray>擊退敵人");
                }
            }
            default -> {
            }
        }
    }

    /**
     * 2026-10-24：一般圖騰（弩台等，圖騰自己放這個技能）每根每秒射幾次，跟 {@link SkillDeployables#totemTicks} 一樣吃攻速／施法速度；
     * 不是這種圖騰（英靈、替身、焚鏈、沒有間隔）回傳 0。
     */
    static double totemFireRate(final SkillBuild build, final StatSheet sheet) {
        final DeploySpec deploy = build.deploy();
        if (deploy == null || deploy.kind() != DeploySpec.Kind.TOTEM || deploy.interval() <= 0
                || SkillExecutor.hasFlag(build.definition(), StatKeys.FLAG_TOTEM_LINKED)) {
            return 0;
        }
        final StatSheet local = new StatSheet().overlay(build.profile().local());
        if (StatKeys.flag(local, StatKeys.FLAG_TOTEM_TAUNT) || StatKeys.flag(local, StatKeys.FLAG_SEARING_BOND)) {
            return 0;
        }
        return 20.0 / SkillDeployables.totemTicks(build, sheet, build.totems()); // 2026-10-26：崩震圖騰照武器攻速的一半
    }

    /** 圖騰、陷阱、鐘的說明。 */
    private static List<String> deployLines(final SkillBuild build, final StatSheet sheet, final int active) {
        final DeploySpec spec = build.deploy();
        final List<String> lines = new ArrayList<>();
        final double duration = spec.duration() * build.durationScale();
        switch (spec.kind()) {
            case TOTEM -> {
                final tw.exilecore.stats.StatSheet local = new tw.exilecore.stats.StatSheet().overlay(build.profile().local());
                if (StatKeys.flag(local, StatKeys.FLAG_TOTEM_TAUNT)) {
                    lines.add(String.format(Locale.ROOT, "<#f5b942>圖騰<gray>：放在面前 <white>%.1f</white> 秒，嘲諷 %.0f 格內的怪物過來打它；生命打完就倒（Boss：招式照打玩家，每次只嘲諷 4 秒、之後 8 秒免疫，打圖騰的傷害 ×3）",
                            duration, spec.range()));
                } else if (StatKeys.flag(local, StatKeys.FLAG_SEARING_BOND)) {
                    lines.add(String.format(Locale.ROOT, "<#f5b942>圖騰<gray>：放在面前 <white>%.1f</white> 秒，不施法；你與圖騰、圖騰與圖騰之間的火鏈每 0.5 秒燒一次（下面的傷害）",
                            duration));
                    if (StatKeys.flag(local, StatKeys.FLAG_TOTEM_DETONATE)) {
                        lines.add("<#ff8a3a>放到上限時全部引爆：每根炸一圈（火鏈一跳的 6 倍，引爆幾根每一下更多 30%）");
                    }
                } else {
                    // 2026-10-24：間隔照實際吃攻速／施法速度後的（以前寫 yml 的原始間隔，看不出攻速的效果）
                    final double rate = totemFireRate(build, sheet);
                    final double every = rate > 0 ? 1.0 / rate : spec.interval();
                    lines.add(String.format(Locale.ROOT, "<#f5b942>圖騰<gray>：放在面前（時間到才消失），<white>%.1f</white> 秒內每 <white>%.2f</white> 秒對 %.0f 格內最近的敵人放一次（下面的效果；基礎 %.1f 秒）",
                            duration, every, spec.range(), spec.interval()));
                }
                lines.add("<gray>同時最多 <white>" + build.totems() + "</white> 個" + (active > 0 ? "，目前 " + active + " 個" : ""));
                // 2026-10-26 照 PoE2：圖騰生命照技能等級的召喚物表（這次之前寫你最大生命的 30%～60%）
                lines.add("<gray>圖騰生命、護甲照技能等級（跟召喚物同一張表，再乘圖騰生命的加成）；怪物會打圖騰，被圖騰打中的怪會先去打圖騰");
                if (!spec.buff().isEmpty()) {
                    final List<String> buffs = new ArrayList<>();
                    for (final tw.exilecore.stats.Modifier modifier : spec.buff()) {
                        buffs.add(ModifierText.describe(modifier));
                    }
                    lines.add("<gray>圖騰在的時候你：<#8fd3ff>" + ItemRenderer.escape(String.join("、", buffs)));
                }
            }
            case TRAP -> {
                lines.add(String.format(Locale.ROOT, "<#f5b942>陷阱<gray>：丟到準心指的地方，<white>%.1f</white> 秒後可以觸發；敵人靠到 %.1f 格內就放一次（下面的效果）然後消失，%.0f 秒沒人踩就沒了",
                        spec.arm(), spec.trigger(), duration));
                lines.add("<gray>同時最多 <white>" + spec.max() + "</white> 個" + (active > 0 ? "，目前 " + active + " 個" : ""));
                if (spec.root() > 0) {
                    lines.add(String.format(Locale.ROOT, "<gray>踩到的敵人定住 <white>%.1f</white> 秒", spec.root()));
                }
            }
            case BELL -> lines.add(String.format(Locale.ROOT, "<#f5b942>鐘<gray>：放在面前 <white>%.0f</white> 秒；你用攻擊打中離鐘 %.1f 格內的敵人，鐘就響一次（最多 %d 次、至少隔 %.2f 秒），每響一次打周圍一圈（下面的效果）",
                    duration, spec.range(), spec.rings(), spec.interval()));
        }
        return lines;
    }

    /** 召喚物的說明：幾隻、多久、材料，還有照主人目前屬性算出來的召喚物數值。 */
    private List<String> summonLines(final Player player, final SkillBuild build, final StatSheet ownerSheet, final int active) {
        final SummonSpec spec = build.definition().summon();
        final List<String> lines = new ArrayList<>();
        final int cap = build.minionReserve() > 0 ? auras.minionCap(player.getUniqueId(), build.definition().id()) : -1;
        lines.add(String.format(Locale.ROOT, "<#8fd3ff>召喚物<gray>：召出 %d 隻%s，同時最多 <white>%d</white> 隻%s%s",
                spec.count(), spec.kindName(), build.minions(),
                cap > 0 && cap < build.minions() ? "<gray>（精魂只養得起 <white>" + cap + "</white> 隻）" : "",
                active > 0 ? "，目前 " + active + " 隻" : ""));
        if (spec.corpse()) {
            lines.add(String.format(Locale.ROOT, "<gray>要用你 %.0f 格內的一具屍體（怪物死後十秒內）當材料，從屍體的位置爬出來", spec.search()));
        }
        // 2026-10-29 照 PoE2（MinionsAreUndamagable，幻化武器）：不會受傷的召喚物寫「不會受傷」；這次之前一律寫「直到被打死」
        lines.add(spec.immortal() ? "<gray>不會受傷、不會死：一直跟著你，直到你關掉技能、下線或換角色"
                : spec.permanent() ? "<gray>不會消失：直到被打死、你下線或換角色"
                : String.format(Locale.ROOT, "<gray>持續 <white>%.1f</white> 秒", spec.duration() * build.durationScale()));
        final String minionTypes = MinionTypes.line(build.definition()); // 2026-10-29 照 PoE2 的 Minion Types
        if (minionTypes != null) {
            lines.add(minionTypes);
        }
        if (plugin.monsters() != null) {
            final tw.exilecore.monster.MonsterConfig monsters = plugin.monsters().config();
            final int ownerLevelNow = plugin.players().get(player).level();
            final int level = MinionSheets.displayLevel(spec, build.level(), MinionSheets.monsterLevel(config, build.level(), ownerLevelNow));
            final MinionSheets.Base base = MinionSheets.base(config, spec, build.level(), ownerLevelNow, monsters::lifeAt, monsters::damageAt,
                    monsters::accuracyAt, monsters::evasionAt, monsters::armourAt, monsters.critChance()); // 2026-10-26 照 PoE2 的召喚物表
            final List<Modifier> ownerModifiers = new ArrayList<>(MinionSheets.forMinion(MinionSheets.ownerModifiers(ownerSheet),
                MinionSheets.conditions(build.definition(), true))); // 2026-10-26：估計時當作在存在範圍內（夥伴照技能）
            ownerModifiers.addAll(auras.effectsFor(player.getUniqueId()));
            // 2026-10-27 照 PoE2：看看我怎麼辦事的（估計時當作在存在範圍內）、巨伴的閃避與偏斜、夥伴獲得你的力量的生命（跟戰鬥、召喚物每秒傷害同一套）
            final List<Modifier> derived = MinionEstimates.estimate(build, ownerSheet,
                    MinionSheets.poe2Factors(config, build.level(), ownerLevelNow, monsters::lifeAt, monsters::damageAt)[1]);
            ownerModifiers.addAll(derived);
            final StatSheet minionSheet = MinionSheets.build(spec, base, ownerModifiers);
            final tw.exilecore.stats.SkillProfile profile = MinionSheets.profile(build);
            final StatSheet effective = profile.effective(minionSheet);
            final DamagePacket expected = DamageCalculator.expectedOutgoing(minionSheet, profile);
            if (spec.immortal()) { // 2026-10-29 照 PoE2：不會受傷的召喚物不寫生命（寫了也沒意義）
                lines.add(String.format(Locale.ROOT, "<gray>召喚物等級 <white>%d</white>：不會受傷、每下平均 <white>%s</white>、每秒 <white>%.2f</white> 次",
                        level, expected.isEmpty() ? "0" : expected, MinionSheets.attacksPerSecond(effective, profile)));
            } else {
                lines.add(String.format(Locale.ROOT, "<gray>召喚物等級 <white>%d</white>：生命 <white>%,d</white>、每下平均 <white>%s</white>、每秒 <white>%.2f</white> 次",
                        level, Math.round(MinionSheets.life(effective)), expected.isEmpty() ? "0" : expected,
                        MinionSheets.attacksPerSecond(effective, profile)));
            }
            // 2026-10-16：召喚物的暴擊率、暴擊傷害（跟 /stats 的召喚物每秒傷害同一套算法：含主人身上「召喚物…」的加成、光環、輔助）
            lines.add(String.format(Locale.ROOT, "<gray>召喚物暴擊率 <white>%s%%</white>、暴擊傷害 <white>×%s%%</white>",
                    ModifierText.number(DamageCalculator.critChance(minionSheet, profile) * 100),
                    ModifierText.number(DamageCalculator.critMultiplier(effective, profile.tags(), null) * 100)));
            if (derived.stream().anyMatch(mod -> "巨伴".equals(mod.source()))) {
                lines.add(String.format(Locale.ROOT, "<gray>召喚物閃避 <white>%,d</white>、偏斜 <white>%,d</white> <dark_gray>（含巨伴給的）",
                        Math.round(effective.get(StatKeys.EVASION)), Math.round(effective.get(StatKeys.DEFLECTION)))); // 2026-10-27
            }
            // 2026-10-29 照 PoE2 的 Minion Types：遠程召喚物的彈道吃從召喚物這邊掛的穿透、連鎖、分裂、多射
            // 2026-10-29 照 PoE2：投射物真的會飛（MinionProjectiles），射真的投射物的召喚物一定寫每秒飛幾格（含投射物速度）；這次之前沒寫速度
            final String projectileLine = spec.isRanged() ? MinionTypes.projectileLine(MinionTypes.projectiles(
                    minionSheet.overlay(MinionSheets.stripMinion(profile.local())), profile.tags()), // 跟 MinionService 的 effective 一樣
                    MinionAbilities.projectileSpeed(build.definition().id())) : null;
            if (projectileLine != null) {
                lines.add(projectileLine);
            }
            final double regen = MinionSheets.regenPercent(effective);
            if (regen > 0) {
                lines.add(String.format(Locale.ROOT, "<gray>召喚物每秒回復 <white>%s%%</white> 最大生命", ModifierText.number(regen)));
            }
            final double shield = MinionSheets.energyShield(effective);
            if (shield > 0) { // 2026-10-26 照 PoE2 的 Energy Shield from Life
                lines.add(String.format(Locale.ROOT, "<gray>能量護盾 <white>%,d</white>（生命的 %d%% 改成護盾；4 秒沒受傷後每秒回充 12.5%%）",
                        Math.round(shield), Math.round(spec.es() * 100)));
            }
        }
        // 2026-10-26：精魂召喚開著的時候按這個鍵 = 下指令（PoE2 的 Command）
        final MinionAbilities.Command command = MinionAbilities.command(build.definition().id());
        if (command != null && build.definition().mechanic() == Mechanic.AURA) {
            final int mana = command.manaAt(build.level());
            lines.add(String.format(Locale.ROOT, "<#8fd3ff>指令<gray>：開著的時候按這個鍵 = <white>%s</white>（每隻冷卻 %s 秒%s）",
                    ItemRenderer.escape(command.name()), ModifierText.number(command.cooldown()), mana > 0 ? "，花 " + mana + " 魔力" : ""));
        } else if (MinionAbilities.SPECTRE.equals(build.definition().id())) {
            final String bound = MinionService.boundSpectre(plugin.players().get(player));
            lines.add("<#8fd3ff>附身<gray>：開著的時候按這個鍵 = 附身附近一具屍體，之後一直叫那種怪（現在："
                    + ItemRenderer.escape(MinionService.spectreName(plugin.monsters() == null ? null : plugin.monsters().config(), bound)) + "）");
        } else if (MinionAbilities.BEAST.equals(build.definition().id())) {
            // 2026-10-30 照 PoE2 Tame Beast
            final String tamed = MinionService.tamedKind(plugin.players().get(player));
            final List<String> mods = minions.tamedModNames(player);
            lines.add("<#8fd3ff>馴獸<gray>：開著的時候按這個鍵 = 用鬼火纏住準心的稀有野獸 8 秒（阻礙 30%），這段時間內打倒牠就收服，之後叫的是那隻野獸"
                    + "（用牠自己的招式、保留牠最多 4 個怪物詞綴、精魂照牠的強度）");
            lines.add("<gray>　現在的夥伴：<white>" + ItemRenderer.escape(tamed == null ? "狼（還沒收服野獸）"
                    : MinionService.spectreName(plugin.monsters() == null ? null : plugin.monsters().config(), tamed))
                    + "</white>" + (mods.isEmpty() ? "" : "（" + ItemRenderer.escape(String.join("、", mods)) + "）"));
            final List<String> moves = minions.tamedSkillNames(player);
            if (!moves.isEmpty()) {
                lines.add("<gray>　招式：<white>" + ItemRenderer.escape(String.join("、", moves)) + "</white>（冷卻好了就放，傷害照牠普通一下的倍數）");
            }
        }
        if (spec.radius() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>打中時連目標周圍 <white>%.1f</white> 格內的敵人一起打", spec.radius()));
        }
        if (spec.taunt() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>每 %.0f 秒吼一聲，把 %.0f 格內的怪引過來打它", spec.taunt(), spec.tauntRadius()));
        }
        if (!spec.buff().isEmpty()) {
            final List<String> buffs = new ArrayList<>();
            for (final tw.exilecore.stats.Modifier modifier : spec.buff()) {
                buffs.add(ModifierText.describe(modifier));
            }
            lines.add("<gray>有一隻在的時候你：<#8fd3ff>" + ItemRenderer.escape(String.join("、", buffs)));
        }
        lines.add("<dark_gray>召喚物只吃你身上「召喚物…」的加成與掛在這個技能上的輔助");
        return lines;
    }

    /** 光環的說明：保留多少、效果、捷的擊殺效果、現在開著沒。 */
    private List<String> auraLines(final Player player, final SkillBuild build) {
        final AuraSpec spec = build.definition().aura();
        final List<String> lines = new ArrayList<>();
        final boolean on = auras.isActive(player.getUniqueId(), build.definition().id());
        final double others = auras.totalReserve(player.getUniqueId()) - (on ? auras.reserveOf(player.getUniqueId(), build.definition().id()) : 0);
        { // 2026-10-26：焚心業火拿掉了；這次之前這裡先分出「常駐技能：不保留精魂」（只有它）
            if (build.minionReserve() > 0) {
                // 2026-10（照 PoE2）：精魂召喚每一隻保留精魂
                final int cap = auras.minionCap(player.getUniqueId(), build.definition().id());
                final int free = Math.min(build.minions(), spec.freeMinions());
                lines.add(String.format(Locale.ROOT, "<#8fd3ff>精魂召喚<gray>：每隻保留 <white>%s</white> 精魂%s%s（你有 %.0f%s）",
                        ModifierText.number(Math.round(build.minionReserve() * 10) / 10.0),
                        free > 0 ? "，前 " + free + " 隻不用" : "",
                        build.reserve() > 0 ? String.format(Locale.ROOT, "，另外固定 %.0f", build.reserve()) : "",
                        Math.max(0, plugin.players().sheet(player).get(StatKeys.SPIRIT_MAX)),
                        others > 0 ? String.format(Locale.ROOT, "，其他光環已保留 %.0f", others) : ""));
                // 2026-10-30 照 PoE2：照技能面板設定的隻數帶（沒設定照原作預設：骸骨戰士 2 隻、其他 1 隻）；這次之前付得起幾隻叫幾隻
                final int wanted = AuraService.wantedCount(plugin.players().get(player), build.definition().id());
                final int target = wanted == AuraService.AUTO_COUNT ? build.minions() : Math.min(build.minions(), AuraService.resolveWanted(build, wanted));
                final String setting = wanted == AuraService.AUTO_COUNT ? "自動（付得起就帶滿）" : wanted == AuraService.DEFAULT_COUNT ? "預設 " + target + " 隻" : target + " 隻";
                if (on && cap > 0) {
                    lines.add(String.format(Locale.ROOT, "<gray>現在帶 <white>%d</white> 隻（設定：%s，上限 %d），共保留 <white>%.0f</white> 精魂%s", Math.min(cap, build.minions()),
                            setting, build.minions(), auras.reserveOf(player.getUniqueId(), build.definition().id()),
                            cap < target ? "<red>（精魂不夠，少帶了 " + (target - cap) + " 隻）" : ""));
                } else {
                    lines.add(String.format(Locale.ROOT, "<gray>設定帶 %s，要 <white>%.0f</white> 精魂；精魂不夠時付得起幾隻就叫幾隻",
                            setting, build.reserve() + Math.max(0, target - free) * build.minionReserve()));
                }
                lines.add("<dark_gray>數量在技能面板的「設定召喚物數量」調整");
            } else {
            lines.add(String.format(Locale.ROOT, "<#8fd3ff>%s<gray>：保留 <white>%.0f</white> 精魂（你有 %.0f%s）",
                    build.definition().reservesSpiritOnly() ? "常駐" // 2026-10-28 照 PoE2：戰旗、掠閃、喚駒綁在按鍵格上就保留，精魂不夠時不能用
                    : spec.herald() ? "捷光環" : "光環", build.reserve(),
                    Math.max(0, plugin.players().sheet(player).get(StatKeys.SPIRIT_MAX)),
                    others > 0 ? String.format(Locale.ROOT, "，其他光環已保留 %.0f", others) : ""));
            }
        }
        final List<Modifier> effects = build.auraModifiers();
        if (!effects.isEmpty()) {
            final String who = build.generosity() ? String.format(Locale.ROOT, "%.0f 格內的隊友（要組隊；效果 ×%.1f）與你的召喚物", spec.radius() * build.areaScale(), AuraService.GENEROSITY_ALLY_BONUS)
                    : spec.allies() ? String.format(Locale.ROOT, "你、%.0f 格內的隊友（要組隊）與你的召喚物", spec.radius() * build.areaScale()) : "你"; // 2026-10：只給自己的增益不再給召喚物
            lines.add("<gray>開著的時候" + who + "：");
            for (final String line : ModifierText.describeAll(effects)) {
                lines.add("<#8fd3ff>　" + ItemRenderer.escape(line));
            }
            if (build.auraEffect() != 1.0) {
                lines.add(String.format(Locale.ROOT, "<dark_gray>　（已含光環效果 ×%.2f）", build.auraEffect()));
            }
            // 2026-10（祕法之心）：照最大魔力算的效果，把現在的數字寫出來
            final double maxMana = tw.exilecore.character.PlayerState.maxMana(plugin.players().sheet(player)); // 2026-10：跟血條一樣（含靈能電池）
            for (final Modifier effect : effects) {
                if (effect.stat().startsWith(StatKeys.DAMAGE_EXTRA_PER_MANA)) {
                    lines.add(String.format(Locale.ROOT, "<gray>　你現在最大魔力 <white>%.0f</white>：獲得 <white>%s%%</white> 傷害為額外傷害",
                            maxMana, ModifierText.number(effect.value() * maxMana / 100.0)));
                } else if (effect.stat().equals(StatKeys.SKILL_COST_MAX_MANA)) {
                    lines.add(String.format(Locale.ROOT, "<red>　每次施放多花 <white>%.0f</white> 魔力<gray>（再乘上輔助寶石的魔力消耗倍率；引導技能、不用魔力的技能不算）",
                            effect.value() * maxMana / 100.0));
                }
            }
        }
        if (spec.enemy() != null) {
            // 對敵人的光環（威壓）：受到的傷害增加多少（已含光環效果），待滿幾秒後增加更多
            final AuraSpec.Enemy enemy = spec.enemy();
            final String kind = enemy.tag() == null ? "" : switch (enemy.tag()) {
                case "physical" -> "物理";
                case "fire" -> "火焰";
                case "cold" -> "冰冷";
                case "lightning" -> "閃電";
                case "chaos" -> "混沌";
                case "elemental" -> "元素";
                default -> "";
            };
            final double scale = build.auraEffect();
            lines.add(String.format(Locale.ROOT, "<gray>開著的時候 %.0f 格內的敵人：", spec.radius() * build.areaScale()));
            if (enemy.taken().at(build.level()) > 0) { // 2026-10-26：只有凋零、修飾器的（凋息、鎮懾）不寫「增加 0%」
                lines.add(String.format(Locale.ROOT, "<#8fd3ff>　受到的%s傷害增加 <white>%.0f%%</white>", kind, enemy.taken().at(build.level()) * scale));
            }
            for (final ScaledMod mod : enemy.effects()) { // 2026-10-26：鎮懾
                lines.add("<#8fd3ff>　" + mod.describe(build.level(), scale));
            }
            if (enemy.witheredEvery() != null) { // 2026-10-26：凋息
                lines.add(String.format(Locale.ROOT, "<#8fd3ff>　每 <white>%.2f</white> 秒受到 1 層凋零<gray>（每層受到的混沌傷害增加 5%%，最多 10 層，6 秒）",
                        enemy.witheredEvery().at(build.level()) / Math.max(0.1, scale)));
            }
            if (enemy.rampSeconds() > 0 && enemy.rampTaken() != null && enemy.rampTaken().at(build.level()) > 0) {
                lines.add(String.format(Locale.ROOT, "<#8fd3ff>　在光環裡待滿 %s 秒：改成增加 <white>%.0f%%</white>",
                        enemy.rampSeconds() == Math.rint(enemy.rampSeconds()) ? Long.toString((long) enemy.rampSeconds()) : Double.toString(enemy.rampSeconds()),
                        enemy.rampTaken().at(build.level()) * scale));
            }
            if (scale != 1.0) {
                lines.add(String.format(Locale.ROOT, "<dark_gray>　（已含光環效果 ×%.2f）", scale));
            }
        }
        if (spec.herald()) {
            final SkillDefinition sub = config.skill(spec.onKill());
            final String when = spec.condition().always() ? "擊殺" : "<white>" + spec.condition().describe() + "</white>";
            final String heraldId = build.definition().id();
            if (AuraService.HERALD_THUNDER.equals(heraldId)) {
                // 2026-10-24（照 PoE2）：接下來幾次攻擊擊中落雷
                lines.add(String.format(Locale.ROOT, "<gray>%s後，接下來 <white>%d</white> 次攻擊擊中的目標落下 <white>%s</white>（照這顆寶石的等級與輔助）",
                        when, AuraService.thunderCharges(build.level()), sub == null ? spec.onKill() : ItemRenderer.escape(sub.name())));
            } else if (AuraService.HERALD_PLAGUE.equals(heraldId)) {
                lines.add(String.format(Locale.ROOT, "<gray>%s時把牠身上最痛的毒傳給 <white>%.1f</white> 格內的敵人，被傳到的 <white>%.0f%%</white> 機率緩行 %.0f 秒",
                        when, AuraService.plagueRadius(build.level()), AuraService.plagueHinderChance(build.level()), AuraService.PLAGUE_HINDER_SECONDS));
            } else {
                // 2026-10（照 PoE2）：有觸發條件的寫出來（用攻擊擊中殺死感電中的敵人、過量擊殺…）
                lines.add(String.format(Locale.ROOT, "<gray>%s時%s從屍體的位置放出 <white>%s</white>（照這顆寶石的等級與輔助）", when,
                        spec.onKillChance() < 100 ? String.format(Locale.ROOT, " %.0f%% 機率", spec.onKillChance()) : "",
                        sub == null ? spec.onKill() : ItemRenderer.escape(sub.name())));
            }
            if (AuraService.HERALD_BLOOD.equals(heraldId)) {
                // 2026-10-26（照 PoE2）：受過失血的敵人都會炸，只有普通、魔法敵人的屍體炸完會沒掉
                lines.add(String.format(Locale.ROOT, "<gray>　受過失血的敵人死了就炸；再多打牠累積失血的 <white>%.0f%%</white>（不吃加成），普通、魔法敵人的屍體炸過就沒了",
                        AuraService.BLOOD_LOSS_SHARE * 100));
            }
            if (spec.condition().needsOwnAttack()) {
                lines.add("<dark_gray>　法術、持續傷害、召喚物與捷自己的效果打死的不算");
            }
            if (sub != null && sub.damage() != null && !AuraService.HERALD_PLAGUE.equals(heraldId)) {
                final double[] range = SkillProfiles.spellDamage(config, sub, build.level());
                lines.add(String.format(Locale.ROOT, "<gray>　基礎傷害 <white>%d – %d</white>%s", Math.round(range[0]), Math.round(range[1]),
                        sub.mechanic() == Mechanic.NOVA ? String.format(Locale.ROOT, "，半徑 %.1f 格", sub.params().radius().at(build.level()) * build.areaScale())
                                : sub.mechanic() == Mechanic.CHAIN ? "，連鎖 " + sub.params().chains().intAt(build.level()) + " 次" : ""));
            }
        }
        if (spec.fury() != null) {
            // 2026-10（照 PoE2 的 Savage Fury）：攻擊累積獸性，滿了爆發成狂暴
            final AuraSpec.Fury fury = spec.fury();
            lines.add(String.format(Locale.ROOT, "<gray>你的攻擊擊中敵人累積<white>獸性</white>（一次技能 1 點，最多 %d 點）；滿了%s就爆發成<white>狂暴 %s 秒</white>：",
                    fury.max(), fury.form() ? "而且在獸形（熊、狼人、飛龍）" : "", ModifierText.number(fury.ticks(fury.max()) / 20.0)));
            final List<Modifier> frenzy = new ArrayList<>();
            for (final ScaledMod mod : fury.effects()) {
                frenzy.add(mod.toModifier(build.level(), build.auraEffect(), build.definition().name()));
            }
            for (final String line : ModifierText.describeAll(frenzy)) {
                lines.add("<#8fd3ff>　" + ItemRenderer.escape(line));
            }
            if (fury.onslaught()) {
                lines.add(String.format(Locale.ROOT, "<#8fd3ff>　疾速（攻擊、施法、移動速度增加 %.0f%%）", tw.exilecore.combat.KillEffects.ONSLAUGHT_SPEED * 100));
            }
            if (fury.lifeLoss() > 0) {
                lines.add(String.format(Locale.ROOT, "<red>　狂暴中每秒失去最大生命 %s%%<gray>（直接扣生命；扣不死，剩 1 點時狂暴結束；營地不扣）",
                        ModifierText.number(fury.lifeLoss())));
            }
            lines.add(fury.form() ? "<gray>　狂暴中一直維持獸形；狂暴中不會再累積獸性" : "<gray>　狂暴中不會再累積獸性");
        }
        if (spec.lifeCost() != null) {
            // 2026-10（照 PoE2 的 Berserk）：開著的代價
            final AuraSpec.LifeCost cost = spec.lifeCost();
            if (cost.percent() > 0) {
                lines.add(String.format(Locale.ROOT, "<red>開著的時候每秒失去最大生命 %s%%", ModifierText.number(cost.percent())));
            }
            if (cost.perRage() > 0) {
                lines.add(String.format(Locale.ROOT, "<red>怒氣沒有在消退的時候，每 1 點怒氣每秒失去最大生命 %s%%<gray>（30 點怒氣 = 每秒 %s%%）",
                        ModifierText.number(cost.perRage()), ModifierText.number(cost.perRage() * 30)));
            }
            lines.add(String.format(Locale.ROOT, "<gray>　直接扣生命（不經過能量護盾）；扣不死，剩 1 點時效果暫停，回到 %.0f%% 以上繼續；營地不扣",
                    AuraSpec.LifeCost.RESUME_PERCENT));
        }
        lines.add(AuraService.stateText(on));
        return lines;
    }

    /** 戰吼的說明。2026-10-29：祭柱找屍體的距離改成讀 {@link Warcries#SPIKE_SEARCH}（這次之前寫死「12 格」）。 */
    private static List<String> warcryLines(final SkillBuild build) {
        final WarcrySpec spec = build.definition().warcry();
        final List<String> lines = new ArrayList<>();
        final double duration = spec.duration() * build.durationScale();
        // 2026-10-30 照 PoE2：串骷髏、骨柱是有生命的召喚物（這次之前用屍體，沒有就獻祭任何召喚物；骨柱打不掉）
        lines.add(String.format(Locale.ROOT, spec.spike() ? "<#c9a0ff>祭柱<gray>：串一隻 " + Math.round(Warcries.SPIKE_SEARCH) + " 格內你的骷髏立骨柱，柱子半徑 <white>%.0f</white> 格"
                : spec.cry() ? "<#f5b942>戰吼<gray>：半徑 <white>%.1f</white> 格" // 2026-10-26 照 PoE2：使用時間 0.8 秒（只吃戰吼速度），不是瞬間
                : spec.minionBuff().isEmpty() ? "<#f5b942>戰吼<gray>：半徑 <white>%.0f</white> 格，瞬間發動" : "<#c9a0ff>祭品<gray>：半徑 <white>%.0f</white> 格，瞬間發動", spec.radius() * build.areaScale()));
        if (spec.spike()) {
            lines.add(String.format(Locale.ROOT, "<gray>骨柱是召喚物：生命是同等級召喚物的 <white>%s</white> 倍（吃召喚物生命與「祭品的最大生命」的加成），會被敵人打；被打死效果就結束",
                    Warcries.ROLE_BONE.equals(build.definition().id()) ? "0.35" : "0.18"));
        }
        final double heal = spec.heal().at(build.level());
        if (heal > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>立刻回復 <white>%d%%</white> 最大生命", Math.round(heal)));
        }
        final List<Modifier> buff = build.warcryModifiers();
        if (!buff.isEmpty()) {
            lines.add(String.format(Locale.ROOT, "<gray>%.1f 秒內你%s：", duration, spec.allies() ? "和範圍內的隊友（要組隊）" : ""));
            for (final String line : ModifierText.describeAll(buff)) {
                lines.add("<#f5b942>　" + ItemRenderer.escape(line));
            }
        }
        if (!spec.minionBuff().isEmpty()) {
            lines.add(String.format(Locale.ROOT, spec.spike() ? "<gray>祭柱立著的 %.1f 秒內，柱子附近你的召喚物：" : "<gray>%.1f 秒內範圍內你的召喚物（祭品）：", duration));
            boolean shield = false;
            double shieldLess = 0;
            for (final ScaledMod mod : spec.minionBuff()) {
                // 2026-10-30 照 PoE2 Bone Offering：護盾（五種傷害的減傷＋記號）合成一句
                if (mod.stat().equals(tw.exilecore.stats.TreeMechanicKeys.OFFERING_BONE_SHIELD)) {
                    shield = true;
                    continue;
                }
                if (mod.stat().startsWith(StatKeys.DEBUFF_TAKEN)) {
                    shieldLess = -mod.value().at(build.level());
                    continue;
                }
                lines.add("<#c9a0ff>　" + ItemRenderer.escape(mod.describe(build.level(), 1.0)));
            }
            if (shield) {
                lines.add(String.format(Locale.ROOT, "<#c9a0ff>　有護盾：受到的擊中傷害減少 <white>%s%%</white>（骨柱自己也有）", trimNumber(shieldLess)));
                lines.add("<#c9a0ff>　受到超過最大生命 25% 的擊中時，護盾把那一下整個吸掉，然後爆掉（這根骨柱立著的期間不會再有）");
            }
        }
        if (spec.empower() == null && build.exerts() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>接下來 <white>%d</white> 次攻擊更多 <white>%d%%</white> 傷害（%.1f 秒內有效）",
                    build.exerts(), Math.round(spec.exertMore().at(build.level())), duration));
        }
        lines.addAll(Warcries.describe(build));
        if (!spec.charges().isEmpty()) {
            final List<String> charges = new ArrayList<>();
            for (final Map.Entry<String, Integer> entry : spec.charges().entrySet()) {
                charges.add("<white>" + entry.getValue() + "</white> 顆" + ItemRenderer.escape(tw.exilecore.charge.ChargeText.name(entry.getKey())));
            }
            lines.add("<gray>立刻獲得 " + String.join("<gray>、", charges) + "<gray>（只有你自己；滿了就把最舊的那顆換新）");
        }
        final double taken = spec.taken().at(build.level()) * build.auraEffect();
        if (taken > 0 || spec.taunt()) {
            lines.add("<gray>範圍內的敵人" + (taken > 0 ? String.format(Locale.ROOT, " %.1f 秒內受到的傷害增加 <white>%d%%</white>（誰打都算）", duration, Math.round(taken)) : "")
                    + (spec.taunt() ? (taken > 0 ? "，並" : "") + String.format(Locale.ROOT, "被嘲諷：<white>%.1f</white> 秒內只打你", duration) : ""));
        }
        return lines;
    }

    private static String zoneLine(final SkillBuild build, final SkillParams.Zone zone, final String prefix) {
        return String.format(Locale.ROOT, "<gray>%s半徑 <white>%.1f</white> 格的地面效果 <white>%.1f</white> 秒，每 %.1f 秒 <white>%d%%</white> 傷害",
                prefix, build.scaledRadius(zone.radius()), zone.duration() * build.durationScale(), zone.interval(), Math.round(zone.damage()));
    }

    /**
     * 技能標籤顯示的順序（2026-10 統一）：形態、類別（召喚物、光環…）、打法（近戰、投射物、範圍…）、位移、傷害類型、持續與冷卻、其他。
     * 沒列在這裡的排在最後。
     */
    static final List<String> TAG_ORDER = List.of(
            Tags.SHAPESHIFT, Tags.BEAR, Tags.WEREWOLF, Tags.WYVERN,
            Tags.MINION, "command", Tags.AURA, Tags.HERALD, Tags.PERSISTENT, "curse", Tags.MARK, Tags.WARCRY,
            Tags.TOTEM, Tags.BELL, Tags.TRAP, Tags.GRENADE,
            Tags.MELEE, Tags.STRIKE, Tags.SLAM, Tags.PROJECTILE, "chain", "nova", Tags.AREA,
            Tags.MOVEMENT, Tags.TRAVEL,
            Tags.PHYSICAL, Tags.FIRE, Tags.COLD, Tags.LIGHTNING, Tags.CHAOS,
            Tags.DURATION, Tags.COOLDOWN, "crystal", Tags.CORPSE, "wind"); // 2026-10-28：疾風（PoE2 Wind）

    /** 技能的標籤（不含攻擊、法術），照 {@link #TAG_ORDER} 排好。 */
    static List<String> orderedTags(final SkillDefinition definition) {
        final List<String> tags = new ArrayList<>();
        for (final String tag : definition.displayTags()) { // 2026-10-29：照原作寶石標籤顯示
            if (!Set.of("attack", "spell").contains(tag)) {
                tags.add(tag);
            }
        }
        tags.sort(java.util.Comparator.comparingInt(tag -> {
            final int index = TAG_ORDER.indexOf(tag);
            return index < 0 ? TAG_ORDER.size() : index;
        }));
        return tags;
    }

    static List<String> tagNames(final SkillDefinition definition) {
        final List<String> names = new ArrayList<>();
        for (final String tag : orderedTags(definition)) {
            final String name = ModifierText.tagName(tag);
            if (!name.equals(tag) && !name.equals(definition.typeName())) {
                names.add(name); // 戰吼的種類就寫「戰吼」，標籤不再重複一次
            }
        }
        return names;
    }

    /** 技能說明的第一行（純文字，搜尋用）：「法術・投射物、範圍」；戰吼不算法術，是「戰吼・範圍、持續、冷卻」。 */
    public static String headline(final SkillDefinition definition) {
        final List<String> tags = tagNames(definition);
        return definition.typeName() + (tags.isEmpty() ? "" : "・" + String.join("、", tags));
    }

    /**
     * 技能寶石說明的標籤列（2026-10）：跟裝備詞綴一樣的小方塊，種類（攻擊、法術、召喚、戰吼）在最前面。
     * 沒套資源包的人看到「[攻擊] [投射物]」。
     */
    public static String headlineChips(final SkillDefinition definition) {
        final List<tw.exilecore.item.AffixChips.Chip> chips = new ArrayList<>();
        chips.add(definition.summon() != null ? tw.exilecore.item.AffixChips.Chip.SUMMON
                : definition.mechanic() == Mechanic.WARCRY ? tw.exilecore.item.AffixChips.Chip.WARCRY
                // 2026-10：光環、捷光環、常駐不算法術，第一格寫它自己的種類（不再掛「法術」）
                : definition.mechanic() == Mechanic.AURA ? (definition.tags().contains(Tags.HERALD) ? tw.exilecore.item.AffixChips.Chip.HERALD
                        : definition.tags().contains(Tags.PERSISTENT) ? tw.exilecore.item.AffixChips.Chip.PERSISTENT : tw.exilecore.item.AffixChips.Chip.AURA)
                // 2026-10-29 照 PoE2 寶石標籤（displayTags）：攻擊、法術照原作；不算法術的（印記、增益：獵殺印記、急裝…）第一格不再掛「法術」，
                //   印記寫印記，增益直接接標籤
                : definition.displayTags().contains(Tags.ATTACK) || definition.isAttack() && !definition.displayTags().contains(Tags.SPELL)
                        ? tw.exilecore.item.AffixChips.Chip.ATTACK
                : definition.displayTags().contains(Tags.SPELL) ? tw.exilecore.item.AffixChips.Chip.CASTER
                : definition.displayTags().contains(Tags.MARK) ? tw.exilecore.item.AffixChips.Chip.MARK : null);
        chips.remove(null);
        for (final String tag : orderedTags(definition)) {
            final tw.exilecore.item.AffixChips.Chip chip = tw.exilecore.item.AffixChips.byId(tag);
            if (chip != null && !chips.contains(chip)) {
                chips.add(chip);
            }
        }
        return tw.exilecore.item.AffixChips.miniMessage(chips).replaceFirst("^(<#e6e6e6>) ", "$1");
    }

    /** 連段、榮耀、元素灌注（2026-10 PoE2）。 */
    public SkillResources resources() {
        return resources;
    }

    /**
     * 2026-10-27 照 PoE2 神幻雪崩：可連瀑的法術（落在目標區域的範圍法術：定點的衝擊、地面效果）最後一次回響，
     * 也沿著隨機一條軸往目標區域兩側各落一次（隔一個範圍直徑；沒有指定目標，照偏移後的準心）。
     * 這次之前我們沒有連瀑，這一條沒有效果。
     * 2026-10-28 照 PoE2：可不可以連瀑看 SkillCascade（原作的 Cascadable：詛咒、新星也算）；詛咒照原本的落點往兩側各落一圈、
     * 骸爆照原本的爆炸往兩側各炸一次（SkillExecutor.cascade）；這次之前只認衝擊、圓形地面，一律照偏移後的準心重放。
     */
    private void cascadeFinalEcho(final Player player, final SkillBuild build, final LivingEntity target) {
        if (!StatKeys.flag(plugin.players().sheet(player), tw.exilecore.stats.KeystoneOriginKeys.FINAL_ECHO_CASCADE)
                || !cascadable(build.definition())) {
            return;
        }
        final double angle = plugin.random().nextDouble() * Math.PI;
        final double offset = Math.max(1.5, SkillCascade.radius(build) * 2);
        final org.bukkit.util.Vector axis = new org.bukkit.util.Vector(Math.cos(angle), 0, Math.sin(angle)).multiply(offset);
        final SkillExecutor.CascadeAnchor anchor = executor.cascadeAnchor(player, build, target);
        for (final int side : new int[] {1, -1}) {
            executor.cascade(player, build, anchor, axis.clone().multiply(side));
        }
    }

    /** PoE2 Cascadable（2026-10-28 改成 SkillCascade 一套；這次之前這裡只認衝擊、圓形地面）。 */
    static boolean cascadable(final SkillDefinition definition) {
        return SkillCascade.cascadable(definition);
    }
}
