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
        this.auras = new AuraService(plugin, config, this, targets, visuals, executor, damage);
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

    SkillRuntime runtime(final Player player) {
        return runtimes.computeIfAbsent(player.getUniqueId(), uuid -> new SkillRuntime());
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
        projectiles.forget(player.getUniqueId());
        zones.forget(player.getUniqueId());
        deployables.forget(player.getUniqueId());
        minions.forget(player.getUniqueId());
        buffs.forget(player.getUniqueId());
        walkers.remove(player.getUniqueId());
        auras.forget(player.getUniqueId());
        signatures.forget(player.getUniqueId());
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

    /** 玩家的召喚物（殭屍、骷髏、焚魂、魔像）。 */
    private SkillTriggers triggers;

    /** 主要寶石（2026-10）。 */
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
    }

    /** 最後一下打這隻怪的技能（是這個玩家打的、1 秒內）；沒有就 null。 */
    public String lastSkill(final LivingEntity entity, final Player player) {
        return procs().lastSkill(entity, player);
    }

    /** 技能面板上這個技能掛的輔助裡，某個屬性的 FLAT 加總（2026-10：召喚物死掉回魔力、骸恤這類只看輔助的效果）。 */
    public double supportFlat(final Player player, final String skillId, final String stat) {
        return player == null || skillId == null ? 0 : procs().supportFlat(player, skillId, stat);
    }

    /** 觸發（2026-10）。 */
    public SkillTriggers triggers() {
        if (triggers == null) {
            triggers = new SkillTriggers(plugin, this);
        }
        return triggers;
    }

    // ---- 弩的彈匣（2026-10，照 PoE2）：每發用掉一支，打完自動裝填 ----

    static final int BASE_CLIP = 5;
    static final double BASE_RELOAD_SECONDS = 0.35; // 2026-10 平衡：0.5 → 0.35（裝填縮短 3 成）
    /** 指令型召喚物技能一次最多幾隻聽命（2026-10）。 */
    static final int COMMAND_MAX = 8;

    /** 要用彈匣的技能：弩的攻擊（沒有冷卻的）。 */
    static boolean ammoSkill(final SkillDefinition definition) {
        return definition.isAttack() && !definition.hidden() && definition.weapons().contains("crossbow") && definition.cooldown() <= 0;
    }

    static int clipSize(final SkillBuild build, final tw.exilecore.stats.StatSheet sheet) {
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(sheet);
        return Math.max(1, Math.min(30, BASE_CLIP + (int) Math.round(effective.flat(StatKeys.CROSSBOW_CLIP, build.profile().tags()))));
    }

    static int reloadTicks(final SkillBuild build, final tw.exilecore.stats.StatSheet sheet) {
        final tw.exilecore.stats.StatSheet effective = build.profile().effective(sheet);
        final double speed = Math.max(0.1, effective.apply(StatKeys.CROSSBOW_RELOAD, 1.0, build.profile().tags()));
        return (int) Math.max(2, Math.round(BASE_RELOAD_SECONDS * 20 / speed));
    }

    private void useAmmo(final Player player, final SkillRuntime runtime, final PlayerState state, final SkillDefinition definition,
                         final SkillBuild build, final tw.exilecore.stats.StatSheet sheet, final long now) {
        final int clip = clipSize(build, sheet);
        int left = Math.min(clip, runtime.ammo.getOrDefault(definition.id(), clip));
        final double save = build.profile().effective(sheet).flat(StatKeys.CROSSBOW_AMMO_SAVE, build.profile().tags());
        if (plugin.random().nextDouble() * 100 >= save) {
            left--;
        }
        if (left <= 0) {
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

    /** 立刻裝滿（震裝、急裝）：skillId = null 是全部的弩技能。 */
    void reloadNow(final Player player, final String skillId) {
        final SkillRuntime runtime = runtimes.get(player.getUniqueId());
        if (runtime == null) {
            return;
        }
        if (skillId == null) {
            runtime.ammo.clear();
            runtime.reloadDone.clear();
        } else {
            runtime.ammo.remove(skillId);
            runtime.reloadDone.remove(skillId);
        }
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
        if (MetaGems.problem(new MetaSpec(MetaSpec.Kind.CRIT, 1, Scaled.of(0), 0, 0, 0, 0, 0, Scaled.of(1), 0), definition) != null) {
            return; // 插槽的檢查在 MetaGems 已經做過，這裡只是保險
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        SkillBuild build = build(state, sheet, definition, entry).rollElement(sheet, plugin.items().random());
        build = build.withProfile(build.profile().scaled(scale, source)
                .derive(build.profile().id() + MetaGems.TRIGGERED_SUFFIX, java.util.Set.of(), java.util.Set.of()));
        final SkillRuntime runtime = runtime(player);
        final long now = now();
        // 2026-10-17：有冷卻的法術被觸發也照冷卻（以前能量一滿就放，冷卻等於沒有）
        if (runtime.cooldownLeft(definition.id(), now) > 0) {
            return;
        }
        if (build.cooldownTicks() > 0) {
            runtime.startCooldown(definition.id(), now, build.cooldownTicks());
        }
        // 2026-10-26：被觸發的法術也用掉灌注（照 PoE2：觸發的法術是玩家放的，吃得到身上的灌注；圖騰不走這裡、不吃）
        //   以前只有手動施放（performInner）才用，引星插槽法術掛灌元等於沒掛
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
            procs().onUse(player, definition, useLocation(player, definition, build, target));
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("主要寶石觸發 {} 失敗", definition.id(), e);
        } finally {
            Fx.restore(previousFx);
        }
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
        buffs.apply(player, id, name, ticks, modifiers);
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

    /** 這個技能是不是正開著的光環（技能面板用：發光、標「開啟中」）。 */
    boolean auraActive(final Player player, final String skillId) {
        return auras.isActive(player.getUniqueId(), skillId);
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
        if (runtime != null && runtime.barrage != null && runtime.barrage.active(now)) {
            lines.add(String.format(Locale.ROOT, "<#ffd27f>%s<gray>：下一次弓／矛的投射物攻擊重複 <white>%d</white> 次（剩 %.1f 秒）",
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

    /** 按下一個按鍵（0 左鍵、1 右鍵、2 潛行＋左鍵、3 潛行＋右鍵）。 */
    void trigger(final Player player, final int key, final LivingEntity preferred) {
        final SkillRuntime runtime = runtime(player);
        if (runtime.leap != null) {
            return;
        }
        final long now = now();
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
                    plugin.getSLF4JLogger().error("{} 的按住左鍵設定出錯", player.getName(), e);
                }
            }
            final SkillRuntime runtime = runtimes.get(player.getUniqueId());
            if (runtime == null) {
                continue;
            }
            try {
                tickPlayer(player, runtime, now);
            } catch (final RuntimeException e) {
                runtime.clearQueue();
                runtime.leap = null;
                plugin.getSLF4JLogger().error("{} 的技能施放出錯", player.getName(), e);
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
                plugin.getSLF4JLogger().error("印記更新出錯", e);
            }
        }
        if (now % 10 == 5) {
            try {
                buffs.tick();
                auras.tick(now);
                warcries.taunts().tick();
                if (mounts != null && now % 20 == 5) {
                    mounts.tick(); // 2026-10：坐騎
                }
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("光環、增益更新出錯", e);
            }
            try {
                corpseWalkers(now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("踏骸之履出錯", e);
            }
            try {
                corpseMarkers(now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error("屍體記號出錯", e);
            }
        }
    }

    private void timed(final String name, final Runnable task, final String error) {
        plugin.perf().time(name, () -> {
            try {
                task.run();
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().error(error, e);
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
        final SkillBuild build = repeat.build();
        final SkillDefinition definition = build.definition();
        boolean able = eligible(player) && !inTown(player)
                && (plugin.ailments() == null || !plugin.ailments().isIncapacitated(player))
                && definition.usableWith(plugin.players().get(player).weaponTags());
        if (able) {
            runtime.repeat = repeat.next();
            final LivingEntity target = repeat.target() == null ? null : living(player, repeat.target());
            player.swingMainHand();
            if (definition.mechanic() == Mechanic.FLICKER) {
                able = executor.flickerAgain(player, build, target);
            } else {
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
        // 2026-10-23（照 PoE2：同一個技能只能有一顆）：放在宿主第 1 孔裡的技能自己不能再放（舊的面板可能兩邊都有）
        final int hostOf = state.skills().findInner(definition.id());
        if (hostOf >= 0 && state.skills().skill(hostOf) != null) {
            fail(player, runtime, state, ItemRenderer.escape(definition.name()) + "放在" + ItemRenderer.escape(hostNameOf(state.skills().skill(hostOf)))
                    + "的第 1 孔裡，不能自己再放（先取出一邊）", now);
            return;
        }
        // 英靈圖騰（2026-10 PoE2）：面板上下一格要放錘子攻擊技能，英靈用它打
        SkillBuild linked = null;
        if (SkillExecutor.hasFlag(definition, StatKeys.FLAG_TOTEM_LINKED)) {
            final Object found = linkedMace(state, sheet, definition);
            if (found instanceof String message) {
                fail(player, runtime, state, message, now);
                return;
            }
            linked = (SkillBuild) found;
        }
        final long cooldownLeft = runtime.cooldownLeft(definition.id(), now);
        final String bypass = definition.cooldownBypass();
        final boolean bypassing = SkillMath.bypassCooldown(cooldownLeft, bypass, chargesHeld(player, bypass));
        if (cooldownLeft > 0 && !bypassing) {
            // 冷卻中：只提示、不響（按住按鍵時每 tick 都會來）；可以用戰星跳過的技能說一下沒有戰星
            state.notice(String.format(Locale.ROOT, "<gray>%s 冷卻中（%.1f 秒%s）", ItemRenderer.escape(definition.name()), cooldownLeft / 20.0,
                    bypass == null ? "" : "，沒有" + ItemRenderer.escape(chargeName(bypass)) + "可以消耗"), now + 10);
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
            }
        }
        final MetaSpec metaSpec = pressed.entry() == null ? null : config.meta(definition.id());
        if (metaSpec != null && metaSpec.totem()) {
            spellTotem(player, runtime, state, sheet, key, pressed.entry(), build, metaSpec, now); // 2026-10：法術圖騰（PoE2 主要寶石）
            return;
        }
        final String problem = build.deploy() != null ? deployables.precheck(player, build)
                : definition.mechanic() == Mechanic.SUMMON ? minions.precheck(player, build)
                : definition.mechanic() == Mechanic.RALLY ? (minions.count(player.getUniqueId()) == 0 ? "你現在沒有召喚物" : null)
                : definition.mechanic() == Mechanic.GUARD ? (definition.weapons().isEmpty() ? guardProblem(state) : null) // 2026-10：限定武器的增益技能（霜鏃、急裝）不用盾
                : definition.warcry() != null && definition.warcry().spike() ? warcries.spikeProblem(player) // 2026-10：祭柱要屍體或召喚物
                : config.mount(definition.id()) != null ? null // 2026-10：坐騎技能（叫坐騎／下馬）
                : config.requiresMount(definition.id()) && !mounts().mounted(player) ? "要騎在坐騎上才能放" // 2026-10
                : config.command(definition.id()) != null ? (minions.commandable(player, config.command(definition.id()), 1).isEmpty()
                        ? "附近沒有聽這個指令的召喚物" : null) // 2026-10：指令型召喚物技能
                : executor.precheck(player, build, preferred);
        if (problem != null) {
            fail(player, runtime, state, problem, now);
            return;
        }
        // 2026-10 蓄力放開（PoE2 的穿雲箭、蓄勁一擊、炎核爆）：按下去先蓄力，放開（Signatures 偵測到）才真的出手
        final ChargeSpec chargeSpec = config.charge(definition.id());
        final SkillRuntime.Release release = chargeSpec != null && runtime.releasing != null
                && runtime.releasing.skillId().equals(definition.id()) ? runtime.releasing : null;
        runtime.releasing = null;
        if (chargeSpec != null && release == null) {
            signatures.press(player, runtime, state, key, definition, build, chargeSpec, preferred, sheet, now);
            return;
        }
        final ResourceSpec resourceSpec = config.resource(definition.id());
        final int cost = build.manaCost();
        // 血魔法（2026-10 PoE2 基石）：沒有魔力，技能的消耗改扣生命
        final boolean bloodMagic = StatKeys.flag(state.sheet(), StatKeys.FLAG_BLOOD_MAGIC)
                || StatKeys.flag(build.profile().effective(state.sheet()), StatKeys.FLAG_BLOOD_MAGIC); // 2026-10：血付（輔助）只對被輔助的技能
        if (cost > 0 && bloodMagic && state.life() <= cost) {
            fail(player, runtime, state, "生命不足（" + definition.name() + "要 " + cost + " 生命）", now);
            return;
        }
        // 血祭術（血術士，PoE2）：技能另外扣同樣多的生命
        final boolean sanguimancy = !bloodMagic && StatKeys.flag(state.sheet(), StatKeys.FLAG_SANGUIMANCY);
        if (cost > 0 && sanguimancy && state.life() <= cost) {
            fail(player, runtime, state, "生命不足（血祭術：" + definition.name() + "要 " + cost + " 生命）", now);
            return;
        }
        if (cost > 0 && !bloodMagic && state.mana() + 1e-9 < cost) {
            fail(player, runtime, state, "魔力不足（" + definition.name() + "要 " + cost + "）", now);
            return;
        }
        if (bypassing && plugin.combat().charges().spend(player, bypass, build.profile().effective(sheet), build.profile().tags()) <= 0) { // 2026-10-24：戰星輔助
            fail(player, runtime, state, definition.name() + "冷卻中，沒有" + chargeName(bypass) + "可以消耗", now); // 剛好過期了
            return;
        }
        if (cost > 0) {
            if (bloodMagic) {
                state.setLife(state.life() - cost);
            } else {
                state.setMana(state.mana() - cost);
                if (sanguimancy) {
                    state.setLife(state.life() - cost);
                }
            }
            plugin.players().display(player, state);
        }
        if (definition.mechanic() != Mechanic.AURA) {
            plugin.players().grace().end(player, true); // 出手了：入場保護結束（光環只是開關，不算）
        }
        // 2026-10-24：放的時候消耗戰星的輔助（狂速、久咒、碎地；照 PoE2）
        final ChargeEffects.OnUse onUse = chargeEffects().onUse(player, build, sheet);
        build = onUse.build();
        // 冰緩讓攻擊與施法變慢（動作速度 0.7 = 間隔 ÷ 0.7）
        final double speed = plugin.ailments() == null ? 1.0 : Math.max(0.05, plugin.ailments().actionSpeed(player));
        final double interval = build.intervalTicks(sheet) / speed / onUse.speedMore();
        runtime.readyAt = SkillMath.nextReady(runtime.readyAt, now, release != null && chargeSpec.stages() <= 0 ? interval * RELEASE_RECOVERY : interval);
        if ((key != SkillLoadout.KEY_LEFT && key != SkillLoadout.KEY_SNEAK_LEFT) || runtime.fromQueue) {
            player.swingMainHand(); // 左鍵客戶端自己會揮手；右鍵、數字鍵要伺服器揮；排隊放出來的左鍵也要（2026-10-17：揮手時機對上真的出手）
        }
        runtime.startCooldown(definition.id(), now, build.cooldownTicks());
        showCooldown(player, definition.id(), build.cooldownTicks());
        if (build.deploy() != null) {
            if (linked != null) {
                deployables.place(player, build, linked, 0); // 英靈（2026-10-20）：照英靈圖騰的 interval ÷ 攻速叫（SkillDeployables.totemTicks）
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
        if (definition.mechanic() == Mechanic.AURA) {
            // 2026-10：光環放在技能面板上就一直開著，按鍵不用開關（魔力不扣回來：這一下也沒花魔力，光環的 mana 是 0）
            final String socketProblem = config.meta(definition.id()) == null ? null
                    : metas().socketProblem(state, state.skills().find(definition.id()));
            state.notice(socketProblem != null ? "<red>" + ItemRenderer.escape(socketProblem) // 2026-10：主要寶石插槽放錯，按下去說清楚
                    : auras.isActive(player.getUniqueId(), definition.id())
                    ? "<#8fd3ff>✦ " + ItemRenderer.escape(definition.name()) + " 一直開著（綁在按鍵格上就常駐有效，不用按）"
                    : "<red>" + ItemRenderer.escape(definition.name()) + " 沒開：保留的魔力不夠（面板上排前面的光環先開）", now + 60);
            plugin.players().display(player, state);
            return;
        }
        if (definition.mechanic() == Mechanic.GUARD && config.barrage(definition.id()) != null) {
            // 連珠（2026-10）：備好，下一次弓／矛的投射物攻擊重複
            readyBarrage(player, runtime, state, build, config.barrage(definition.id()), now);
            return;
        }
        if (definition.mechanic() == Mechanic.GUARD) {
            if (SkillExecutor.hasFlag(definition, StatKeys.FLAG_RELOAD_ALL)) {
                reloadNow(player, null); // 急裝：弩全部裝滿（2026-10）
            }
            guard(player, build); // 舉盾：一段時間的增益
            visuals.cast(definition.visual(), player);
            state.notice("<#c9d6e3>🛡 " + ItemRenderer.escape(definition.name()), now + 30);
            plugin.players().display(player, state);
            return;
        }
        if (definition.mechanic() == Mechanic.WARCRY) {
            procs().onUse(player, definition, player.getLocation()); // 2026-10：戰吼觸發的專屬技能在腳下
            final String notice = warcries.shout(player, runtime, build);
            shapeshift(player, definition); // 銀月庇（2026-10-15）：帶狼人標籤的戰吼吼完變狼人
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
                build = build.withProfile(build.profile().scaled(1 + runtime.exert.more() / 100.0, "戰吼強化"));
                runtime.exert = runtime.exert.consume();
                visuals.exert(player);
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
            runtime.channelSkill = definition.id();
            runtime.channelTick = now;
            final double more = channelSheet.flat(StatKeys.CHANNEL_MORE, Set.of()) * (runtime.channelStage - 1) / 100.0;
            if (more > 0) {
                build = build.withProfile(build.profile().withLocal(List.of(tw.exilecore.stats.Modifier.more(StatKeys.DAMAGE, more, "蓄力"))));
            }
            state.notice(String.format(Locale.ROOT, "<#ffb070>%s 蓄力 %d／%d", ItemRenderer.escape(definition.name()), runtime.channelStage, maxStages), now + 20);
        }
        // 2026-10：封印：放的時候把蓄好的封印全部解開
        int sealRepeats = 0;
        {
            final StatSheet sealSheet = build.profile().effective(sheet);
            final Set<String> sealTags = build.profile().tags();
            final int sealMax = (int) Math.round(sealSheet.flat(StatKeys.SEAL_MAX, sealTags));
            if (sealMax > 0) {
                final double sealSeconds = sealSheet.flat(StatKeys.SEAL_INTERVAL, sealTags) > 0 ? sealSheet.flat(StatKeys.SEAL_INTERVAL, sealTags)
                        : Math.max(0.5, 2 * definition.castTime());
                final long gap = Math.max(5, Math.round(sealSeconds * 20));
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
                    state.notice(String.format(Locale.ROOT, "<#c7a6ff>%s 解開 %d 個封印", ItemRenderer.escape(definition.name()), seals), now + 20);
                }
            }
        }
        resources.beforeCast(player, build.profile().effective(sheet), build.profile().tags()); // 2026-10：終式、怒鑄
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
        if (release != null) {
            build = signatures.applyRelease(player, state, build, chargeSpec, release, now); // 完美時機、疊層
        }
        build = signatures.beforeCast(player, definition, build, state, now); // 2026-10-15：霆落矛、擲雷槍消耗狂怒球
        final java.util.List<String> commanded = config.command(definition.id());
        final Mounts.Spec mountSpec = config.mount(definition.id());
        if (mountSpec == null && !definition.isAttack() && !config.requiresMount(definition.id()) && mounts().mounted(player)) {
            mounts().dismount(player); // 2026-10：騎著放法術會下馬
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
        } else {
            airborne = executor.execute(player, runtime, build, preferred, now);
        }
        resources.gainInfusion(player, build.profile().effective(sheet), build.profile().tags()); // 2026-10：放完疊灌注
        if (config.requiresMount(definition.id())) {
            mounts().charge(player); // 坐騎技能：坐騎往前衝一下
        }
        if (definition.mechanic() != Mechanic.LEAP) {
            procs().onUse(player, definition, useLocation(player, definition, build, preferred)); // 2026-10：輔助觸發的專屬技能
        } // 跳躍：落地時才算「放了這個技能」（updateLeap），躍電的帶電地面在落地點（衝刺是瞬間到終點，本來就在落地點）
        if (release != null && release.perfect()) {
            procs().event(player, definition.id(), "perfect", player.getLocation(), preferred); // 完美時機放開（蓄勁一擊的火浪）
        }
        if (config.signature(definition.id()) != null) {
            signatures.afterCast(player, definition, build, useLocation(player, definition, build, preferred), now); // 旋風、插地的矛
        }
        signatures.detonateNear(player, definition, useLocation(player, definition, build, preferred)); // 2026-10-15：其他技能引爆霆落矛
        // 2026-10：幻弓影：弓的攻擊過一下由身旁的幻影再射一次（傷害少很多）
        if (definition.isAttack() && definition.weapons().contains("bow") && !definition.hidden() && definition.cooldown() <= 0
                && StatKeys.flag(state.sheet(), StatKeys.FLAG_MIRAGE)) {
            final SkillBuild mirage = build.withProfile(build.profile().scaled(0.4, "幻影"));
            final org.bukkit.util.Vector facing = SkillTargets.facing(player);
            final org.bukkit.Location from = player.getLocation().add(new org.bukkit.util.Vector(-facing.getZ(), 0, facing.getX()).multiply(1.5));
            final java.util.UUID aim = preferred == null ? null : preferred.getUniqueId();
            // 2026-10：幻影要射向放的那一刻準心指的地方（以前用玩家的水平朝向從旁邊 1.5 格平行射出：
            // 不管上下的角度、也不管鎖定的目標，單發的箭打不到同一個地方，偏一格半而且往下瞄時從頭頂飛過去）
            final org.bukkit.Location eye = player.getEyeLocation();
            final org.bukkit.util.RayTraceResult ray = player.getWorld().rayTraceBlocks(eye, eye.getDirection(), MIRAGE_AIM_RANGE,
                    org.bukkit.FluidCollisionMode.NEVER, true);
            final org.bukkit.util.Vector point = ray != null && ray.getHitPosition() != null ? ray.getHitPosition()
                    : eye.toVector().add(eye.getDirection().multiply(MIRAGE_AIM_RANGE));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getWorld().equals(from.getWorld())) {
                    from.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, from.clone().add(0, 1, 0), 12, 0.2, 0.5, 0.2, 0.01);
                    final org.bukkit.entity.LivingEntity mirageTarget = aim == null ? null : living(player, aim);
                    // 有鎖定的敵人就朝牠（牠可能動了），沒有就朝放的時候準心指的點
                    final Origin origin = mirageTarget != null && mirageTarget.isValid()
                            ? Origin.towards(player, from, mirageTarget)
                            : Origin.at(player, from, point.clone().subtract(from.toVector().add(new org.bukkit.util.Vector(0, 1.2, 0))));
                    executor.executeFrom(origin, mirage, mirageTarget);
                }
            }, 10);
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
        if (!definition.isAttack() && definition.tags().contains(tw.exilecore.stats.Tags.SPELL) && definition.tags().contains(tw.exilecore.stats.Tags.FIRE)) {
            auras.fireSpellCast(player, definition.tags().contains(tw.exilecore.stats.Tags.AREA) ? 2 : 1); // 焚魂（2026-10 PoE2）
        }
        if (airborne > 0) {
            runtime.readyAt = Math.max(runtime.readyAt, now + airborne);
        }
        if (build.echo()) {
            runtime.echo = new SkillRuntime.Echo(now + 5, build, preferred == null ? null : preferred.getUniqueId());
        }
        if (build.repeats() > 0) {
            // 複擊：接下來每隔一次攻擊間隔自動再打一下（不花魔力），全部打完才能做別的
            runtime.repeat = new SkillRuntime.Repeat(runtime.readyAt, interval, build.repeats(), build,
                    preferred == null ? null : preferred.getUniqueId());
            runtime.readyAt = runtime.repeat.doneAt();
        } else if (sealRepeats > 0) {
            // 封印（解封）：接下來自動再放幾次，每次傷害少一半
            runtime.repeat = new SkillRuntime.Repeat(Math.max(runtime.readyAt, now + 6), 6, sealRepeats,
                    build.withProfile(build.profile().scaled(0.5, "解封")), preferred == null ? null : preferred.getUniqueId());
            runtime.readyAt = runtime.repeat.doneAt();
        } else if (runtime.barrage != null && maxStages <= 0 && commanded == null && mountSpec == null
                && BarrageSpec.empowers(definition, state.weaponTags(), build.repeats())) {
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
                              final BarrageSpec spec, final long now) {
        final SkillDefinition definition = build.definition();
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
        state.notice(String.format(Locale.ROOT, "<#ffd27f>➶ %s：下一次弓／矛的投射物攻擊重複 <white>%d</white> 次%s",
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
     * 法術圖騰（2026-10，照 PoE2 的主要寶石）：消耗 3 顆銳星或磐星（銳星先用），放一根用插槽法術（面板上它後面那一格）的圖騰。
     * 魔力照法術圖騰自己的消耗。
     */
    private void spellTotem(final Player player, final SkillRuntime runtime, final PlayerState state, final StatSheet sheet, final int key,
                            final SkillLoadout.Entry entry, final SkillBuild metaBuild, final MetaSpec spec, final long now) {
        final SkillDefinition definition = metaBuild.definition();
        final int position = state.skills().find(definition.id());
        final MetaGems.Socket socket = position < 0 ? null : metas.socket(state, position);
        if (socket == null) {
            final String problem = position < 0 ? null : metas.socketProblem(state, position);
            fail(player, runtime, state, problem == null ? definition.name() + "：第 1 孔要放一個法術的寶石（/skills 點它）" : problem, now);
            return;
        }
        final tw.exilecore.charge.ChargeService charges = plugin.combat() == null ? null : plugin.combat().charges();
        final int power = charges == null ? 0 : charges.count(player, "power");
        final int endurance = charges == null ? 0 : charges.count(player, "endurance");
        if (power + endurance < spec.charges()) {
            fail(player, runtime, state, String.format(Locale.ROOT, "%s要消耗 %d 顆銳星或磐星（現在 %d 顆）", definition.name(), spec.charges(),
                    power + endurance), now);
            return;
        }
        final int powerUsed = Math.min(power, spec.charges());
        final int enduranceUsed = spec.charges() - powerUsed;
        SkillBuild build = metas.totemBuild(state, sheet, socket, metaBuild.level(), powerUsed, enduranceUsed);
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
            build = metas.totemBuild(state, sheet, socket, metaBuild.level(), powerBenefit, enduranceBenefit);
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
            player.swingMainHand();
        }
        deployables.place(player, build);
        state.notice(String.format(Locale.ROOT, "<#c9a7ff>%s：%s（銳星 %d、磐星 %d）", ItemRenderer.escape(definition.name()),
                ItemRenderer.escape(socket.spell().name()), powerUsed, enduranceUsed), now + 40);
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
        // 2026-10-22：英靈用的技能放在英靈圖騰自己的第 1 孔（以前是面板上它後面那一格）
        final SkillLoadout.Entry host = position < 0 ? null : loadout.skill(position);
        final SkillLoadout.Entry next = innerEntry(host);
        final SkillDefinition definition = next == null ? null : config.skill(next.skillId());
        final String hint = totem.name() + "：第 1 孔要放錘子攻擊技能的寶石（/skills 點它）";
        if (definition == null) {
            return hint;
        }
        final String why = linkedProblem(definition);
        if (why != null) {
            return hint + "（" + why + "）";
        }
        // 2026-10-21：輔助寶石加的冷卻也算（蓄鋒、蓄疾…）：英靈不等冷卻，以前白拿那顆輔助的加成（暴擊率更多 40%、異常機率加倍）
        // 2026-10-22：要圖騰的輔助（樁怒、疾樁…）也套到英靈的攻擊上（innerSupportsOf 併成一顆不挑技能的）
        // 2026-10-23 修正：放進去的錘子寶石的品質也要算（以前英靈這一下直接用 SkillBuild.of，品質沒套上）
        final SkillBuild built = SkillBuild.of(config, definition, next.level(), state.level(), false, innerSupportsOf(host, totem, definition),
                state.weaponTags(), sheet, resonantOf(next), qualityModifiers(definition, next.quality()));
        if (built.cooldownTicks() > 0) {
            final List<String> names = new ArrayList<>();
            for (final SupportDefinition support : supportsOf(next)) {
                if (addsCooldown(support)) {
                    names.add(support.name());
                }
            }
            return hint + "（不能有冷卻：" + (names.isEmpty() ? "輔助寶石加的冷卻也算" : String.join("、", names) + "會加冷卻，拿掉才能放圖騰") + "）";
        }
        return built;
    }

    /**
     * 2026-10-23：英靈圖騰的說明多寫英靈那一下（放在第 1 孔的錘子技能、吃第 2～5 孔的輔助與圖騰傷害）：
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

    /** 2026-10-22：這個技能能不能放進英靈圖騰的第 1 孔；可以回傳 null，不行回傳原因。 */
    static String linkedProblem(final SkillDefinition definition) {
        if (definition == null || definition.hidden() || !definition.isAttack() || !definition.weapons().contains("mace")) {
            return "要錘子的攻擊技能";
        }
        if (definition.cooldown() > 0 || SkillExecutor.hasFlag(definition, StatKeys.CHANNEL_STAGES) || !SkillBuild.deployable(definition)) {
            return "不能是有冷卻、蓄力、位移的技能";
        }
        return null;
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
    private String guardProblem(final PlayerState state) {
        final tw.exilecore.item.ItemBase off = baseOf(state.jewellery(tw.exilecore.character.Gear.OFF));
        return off != null && off.isShield() && !off.hasTag("focus") ? null : "要在副手裝盾才能舉盾（法器不行）";
    }

    /** 舉盾（2026-10）：guard.effects 變成增益，移動變慢也算進去。 */
    private void guard(final Player player, final SkillBuild build) {
        final SkillParams.Mark guard = build.definition().params().mark();
        final List<tw.exilecore.stats.Modifier> mods = new ArrayList<>();
        for (final ScaledMod effect : guard.effects()) {
            mods.add(effect.toModifier(build.level(), 1.0, build.definition().name()));
        }
        final double slowPct = guard.slow().at(build.level());
        if (slowPct > 0) {
            mods.add(tw.exilecore.stats.Modifier.increased(StatKeys.MOVEMENT_SPEED, -slowPct / 100.0, build.definition().name()));
        }
        buff(player, build.definition().weapons().isEmpty() ? "guard" : "guard." + build.definition().id(), build.definition().name(), (int) Math.round(guard.duration() * build.durationScale() * 20), mods);
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
        buff(player, FORM_BUFF, name, FORM_TICKS, mods);
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
        return plugin.items().read(stack).map(data -> plugin.items().repository().base(data.base())).orElse(null);
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
        return new Resolved(config.defaultAttack(state.weaponTags()), null);
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
                if (build.exerts() > 0) {
                    exerts.add(new double[] {build.exerts(), definition.warcry().exertMore().at(build.level()), // 不吃戰吼效果
                            Math.max(20, build.cooldownTicks()) / 20.0});
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
        for (final SkillLoadout.Entry entry : state.skills().entriesInKeyOrder()) {
            final SkillDefinition definition = config.skill(entry.skillId());
            if (definition == null || definition.aura() == null || definition.hidden() || active.contains(definition.id())
                    || !AuraService.wearable(state, definition)) {
                continue;
            }
            final SkillBuild build = build(state, sheet, definition, entry);
            final AuraService.Fit fit = AuraService.fit(build, AuraService.capacity(sheet) - total);
            if (fit == null) {
                skipped.add(definition.name());
                continue;
            }
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
     * 每個綁在按鍵上的技能的估計每秒傷害（同一個技能綁兩個鍵只算一次）。在線的玩家與虛擬角色共用（2026-10）。
     * 不碰玩家、不碰世界，可以在網頁伺服器的執行緒上算（資料都是唯讀的）。
     */
    public List<DpsRow> skillDps(final PlayerState state, final StatSheet sheet, final AuraView view) {
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

    /** 主要寶石觸發：兩次觸發最快隔 {@link MetaGems#MIN_GAP} tick，每秒最多幾次。 */
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
            if (socket == null || !socket.spell().dealsDamage()) {
                continue;
            }
            final double events;
            if (spec.kind() == MetaSpec.Kind.CRIT) {
                events = main.rate() * main.crit();
            } else if (spec.kind() == MetaSpec.Kind.AILMENT) {
                if (ailmentsPerHit == null) {
                    ailmentsPerHit = mainAilments(state, sheet, main);
                }
                events = main.rate() * (ailmentsPerHit[0] + ailmentsPerHit[1] + 3 * ailmentsPerHit[2]);
            } else {
                continue;
            }
            final double energy = events * spec.energy() * (1 + spec.energyMore().at(metas.usableLevel(state, entry)) / 100.0);
            SkillBuild build = build(state, sheet, socket.spell(), socket.spellEntry());
            double triggers = Math.min(META_MAX_PER_SECOND, energy / MetaSpec.maxEnergy(socket.spell()));
            if (build.cooldownTicks() > 0) {
                triggers = Math.min(triggers, 20.0 / build.cooldownTicks());
            }
            if (triggers <= 0) {
                continue;
            }
            final SkillDefinition metaDefinition = config.skill(entry.skillId());
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
            out.add(new DpsRow(-1, socket.metaPosition() + 1, socket.spell().id(),
                    socket.spell().name() + "（" + metaName + "觸發，每秒約 " + ModifierText.number(triggers) + " 次）",
                    dps, Math.max(dps, fullDps), 0, expected.total() * critFactor, hitRate, crit, multi));
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
    public DpsRow skillDpsAt(final PlayerState state, final StatSheet sheet, final AuraView view, final int position) {
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
        final SkillBuild build = build(state, sheet, resolved);
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, build.profile());
        final double crit = DamageCalculator.critChance(sheet, build.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
        final double multi = DamageCalculator.critMultiplier(build.profile().effective(sheet), build.profile().tags(), null) * 100; // 2026-10：跟戰鬥一樣算「增加／更多暴擊加成」
        // 有冷卻的：一輪要等「施放間隔」和「冷卻」比較長的那個。
        // 2026-10：複擊的每一下都要花一次攻擊間隔（打完才能做別的），以前當成同一個間隔裡打完，面板高了好幾倍
        final SkillParams params = build.params();
        final double critFactor = 1 + crit * (multi / 100.0 - 1);
        // 2026-10-24：圖騰（弩台等）算圖騰自己射的（每根每秒幾次 × 同時幾根，吃攻速）；以前算成放圖騰的速度（被冷卻卡住），攻速、圖騰數都不會動
        final double totemRate = totemFireRate(build, sheet);
        final double hitRate = (totemRate > 0 ? totemRate * build.totems() : hitsPerSecond(build.intervalTicks(sheet), build.cooldownTicks(), build.repeats()))
                * castHits(params.burst() == null ? 1 : params.burst().count(), params.aftershock() != null,
                        params.aftershock() == null ? 0 : params.aftershock().more());
        // 2026-10-24：補齊以前漏算的（實測比估計高 6～20 倍的來源）：雙詠的第二發、蓄力層（平常就會有的）
        final double[] always = alwaysMultipliers(build, sheet);
        final DamagePacket expectedScaled = new DamagePacket(expected).scale(always[1]);
        final double rateScaled = hitRate * always[0];
        double dps = expectedScaled.total() * critFactor * rateScaled;
        if (plugin.ailments() != null) {
            // 2026-10：面板以前只算擊中；點燃、流血、中毒的持續傷害與感電的增傷也算進去（對同等級一般怪物、站著不動）
            dps = plugin.ailments().estimateDps(sheet, build.profile(), expectedScaled, critFactor, rateScaled, state.level(), dps);
        }
        double fullDps = dps;
        {
            // 全開：用疊上戰吼增益的屬性表重算一次（攻速、傷害都會變），再算敵人的抗性降低、受到的傷害增加、強化攻擊
            // 2026-10-24：沒有戰吼也要走這段（投射物全中、灌注、銳星連打這些「全開」才算的乘區）
            final SkillBuild fullBuild = build(state, fullSheet, resolved);
            final tw.exilecore.stats.DamagePacket fullExpected = afterResist(
                    DamageCalculator.expectedOutgoing(fullSheet, fullBuild.profile()), full.resist());
            final double fullCrit = DamageCalculator.critChance(fullSheet, fullBuild.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
            final double fullMulti = DamageCalculator.critMultiplier(fullBuild.profile().effective(fullSheet), fullBuild.profile().tags(), null) * 100;
            final double fullCritFactor = 1 + fullCrit * (fullMulti / 100.0 - 1);
            final SkillParams fullParams = fullBuild.params();
            final double fullTotemRate = totemFireRate(fullBuild, fullSheet); // 2026-10-24：同上
            final double casts = fullTotemRate > 0 ? fullTotemRate * fullBuild.totems()
                    : hitsPerSecond(fullBuild.intervalTicks(fullSheet), fullBuild.cooldownTicks(), fullBuild.repeats());
            final double fullRate = casts * castHits(fullParams.burst() == null ? 1 : fullParams.burst().count(), fullParams.aftershock() != null,
                    fullParams.aftershock() == null ? 0 : fullParams.aftershock().more());
            double exert = 1.0;
            if (fullBuild.profile().isAttack()) {
                // 強化：每次戰吼冷卻好就吼一次，接下來幾次攻擊更痛；攻擊比強化的次數多時照比例平均
                for (final double[] each : full.exerts()) {
                    final double attacksPerCycle = Math.max(1e-6, casts * each[2]);
                    exert *= 1 + each[1] / 100.0 * Math.min(1, each[0] / attacksPerCycle);
                }
            }
            // 2026-10-24：全開再加「打起來才有的」：投射物貼臉全中、灌注滿層、瞬影擊滿銳星連打
            final double[] fullAlways = alwaysMultipliers(fullBuild, fullSheet);
            final double[] fullOnly = fullOnlyMultipliers(fullBuild, fullSheet);
            final double fullRateScaled = fullRate * fullAlways[0] * fullOnly[0];
            final DamagePacket fullScaled = new tw.exilecore.stats.DamagePacket(fullExpected).scale(exert * fullAlways[1] * fullOnly[1]);
            fullDps = fullScaled.total() * fullCritFactor * fullRateScaled;
            if (plugin.ailments() != null) {
                fullDps = plugin.ailments().estimateDps(fullSheet, fullBuild.profile(), fullScaled, fullCritFactor, fullRateScaled,
                        state.level(), fullDps);
            }
            fullDps *= 1 + full.taken();
        }
        return new DpsRow(slot, position, definition.id(), definition.name(), dps, Math.max(dps, fullDps), 0,
                expectedScaled.total() * critFactor, rateScaled, crit, multi);
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
        }
        final StatSheet effective = build.profile().effective(sheet);
        final int stages = (int) effective.flat(StatKeys.CHANNEL_STAGES, Set.of());
        if (stages > 1) {
            damage *= 1 + Math.max(0, effective.flat(StatKeys.CHANNEL_MORE, Set.of()) * (stages - 1) / 100.0);
        }
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
        final SkillBuild totem = metas.totemBuild(state, sheet, socket, metaBuild.level(), spec.charges(), 0);
        if (totem.deploy() == null || totem.deploy().interval() <= 0) {
            return null;
        }
        final double perTotem = 20.0 / SkillDeployables.totemTicks(totem.deploy().interval(), SkillDeployables.totemSpeed(totem, sheet, false));
        final double up = totemsUp(totem.totems(), totem.durationTicks(totem.deploy().duration()), metaBuild.intervalTicks(sheet),
                metaBuild.cooldownTicks());
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, totem.profile());
        final double crit = DamageCalculator.critChance(sheet, totem.profile(), tw.exilecore.character.StatsReport.monsterEvasion(plugin, state));
        final double multi = DamageCalculator.critMultiplier(totem.profile().effective(sheet), totem.profile().tags(), null) * 100;
        final double critFactor = 1 + crit * (multi / 100.0 - 1);
        final SkillParams params = totem.params();
        final double hitRate = perTotem * up * castHits(params.burst() == null ? 1 : params.burst().count(), params.aftershock() != null,
                params.aftershock() == null ? 0 : params.aftershock().more());
        double dps = expected.total() * critFactor * hitRate;
        if (plugin.ailments() != null) {
            dps = plugin.ailments().estimateDps(sheet, totem.profile(), expected, critFactor, hitRate, state.level(), dps);
        }
        final double fullDps = expected.total() <= 0 ? dps
                : afterResist(expected, full.resist()).total() / expected.total() * dps * (1 + full.taken());
        final String name = resolved.definition().name() + "（圖騰放" + socket.spell().name() + " ×" + ModifierText.number(up) + " 根）";
        return new DpsRow(slot, position, resolved.definition().id(), name, dps, Math.max(dps, fullDps), 0,
                expected.total() * critFactor, hitRate, crit, multi);
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
        final int level = MinionSheets.monsterLevel(config, build.level(), ownerLevel);
        final double[] damage = monsters.damageAt(level);
        final double scale = MinionSheets.extraScale(config, build.level());
        final MinionSheets.Base base = new MinionSheets.Base(monsters.lifeAt(level) * scale, monsters.accuracyAt(level), monsters.evasionAt(level),
                monsters.armourAt(level), damage[0] * scale, damage[1] * scale, monsters.critChance());
        final List<Modifier> ownerModifiers = new ArrayList<>(MinionSheets.ownerModifiers(ownerSheet));
        ownerModifiers.addAll(auraEffects);
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

    /** 面板上（或寶石裡）的一個技能在這個角色身上的數值。 */
    SkillBuild build(final PlayerState state, final StatSheet sheet, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        // 2026-10-23：同一張屬性表（沒改過）、同一個技能、同樣的寶石：直接用上次算好的（按住攻擊一秒要施放好幾次）
        final BuildKey key = new BuildKey(config, definition, entry, state.level(), Set.copyOf(state.weaponTags()), sheet, sheet == null ? 0 : sheet.version());
        final SkillBuild cached = buildCache.get(key);
        if (cached != null) {
            return cached;
        }
        final SkillBuild fresh = buildFresh(state, sheet, definition, entry);
        if (buildCache.size() >= BUILD_CACHE_MAX) {
            buildCache.clear(); // 很簡單的上限：滿了就整個清掉（舊的屬性表也跟著放掉）
        }
        buildCache.put(key, fresh);
        return fresh;
    }

    /** 技能數值快取的鍵（屬性表比的是同一個物件＋版本）。 */
    private record BuildKey(SkillConfig config, SkillDefinition definition, SkillLoadout.Entry entry, int level,
                            java.util.Set<String> weaponTags, StatSheet sheet, long version) {
    }

    private static final int BUILD_CACHE_MAX = 512;
    private final Map<BuildKey, SkillBuild> buildCache = new HashMap<>();

    private SkillBuild buildFresh(final PlayerState state, final StatSheet sheet, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        // 2026-10-22：宿主寶石（英靈圖騰、星咒圖騰、暴擊引星）孔裡的輔助是給放進去的技能的；宿主自己只吃其中要圖騰的、加速度與持續的
        final List<SupportDefinition> supports = SupportDefinition.hostsSkill(definition) ? hostSupportsOf(entry) : supportsOf(entry);
        // 2026-10-23：品質放進去一起算（光環、戰吼、詛咒的品質是加效果倍率，算完才補上就沒用了）
        return SkillBuild.of(config, definition, entry.level(), state.level(), false, supports, state.weaponTags(), sheet,
                resonantOf(entry), qualityModifiers(definition, entry.quality())); // 2026-10-18：共鳴的專屬輔助不佔名額
    }

    /** 2026-10-22：宿主寶石本身吃的輔助（孔裡要圖騰的、只加攻擊／施法速度或持續時間的）。 */
    List<SupportDefinition> hostSupportsOf(final SkillLoadout.Entry entry) {
        final List<SupportDefinition> list = new ArrayList<>();
        for (final SupportDefinition support : supportsOf(entry)) {
            if (support.worksOnHost()) {
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
        return new SkillLoadout.Entry(host.inner().skillId(), host.inner().level(), SkillLoadout.MAX_SOCKETS, host.supports(), host.resonant())
                .withQuality(host.inner().quality()); // 2026-10-23：放進去的寶石的品質
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
        // 2026-10-24：照 PoE2 這個技能自己的品質效果
        switch (definition.id()) {
            case "cull_the_weak" -> {
                return List.of(tw.exilecore.stats.Modifier.flat(tw.exilecore.charge.ChargeService.EXTRA, quality, source),
                        tw.exilecore.stats.Modifier.flat(tw.exilecore.charge.ChargeService.EXTRA_RANDOM, quality, source));
            }
            case "charge_regulation" -> {
                return List.of(tw.exilecore.stats.Modifier.flat(StatKeys.GRANT_PREFIX + tw.exilecore.charge.ChargeService.REGULATION_CONSUME,
                                quality * REGULATION_QUALITY_SECONDS, source),
                        tw.exilecore.stats.Modifier.flat(StatKeys.GRANT_PREFIX + StatKeys.DAMAGE_PER_CHARGE, quality * REGULATION_QUALITY_DAMAGE, source));
            }
            default -> {
            }
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

    /** 品質修飾器的來源名稱開頭（SkillBuild.grantModifiers 用來認品質給的 grant.*）。 */
    static final String QUALITY_SOURCE = "品質 ";
    /** 馭星訣的品質（PoE2）：每 1% 品質消耗間隔多 0.12 秒（20% = +2.4 秒）、每顆戰星多增加 0.1% 傷害（20% = 2%）。 */
    static final double REGULATION_QUALITY_SECONDS = 0.12;
    static final double REGULATION_QUALITY_DAMAGE = 0.1;

    /** 2026-10-23：品質的說明（寶石、技能面板用）。 */
    static String qualityText(final SkillDefinition definition, final int quality) {
        if (definition == null || quality <= 0) {
            return null;
        }
        switch (definition.id()) { // 2026-10-24：照 PoE2 這個技能自己的品質效果
            case "cull_the_weak" -> {
                return String.format(Locale.ROOT, "品質 +%d%%：獲得戰星時 %d%% 機率多一顆同種的、%d%% 機率多一顆隨機種類的", quality, quality, quality);
            }
            case "charge_regulation" -> {
                return String.format(Locale.ROOT, "品質 +%d%%：消耗戰星的間隔多 %s 秒、身上每顆戰星增加 %s%% 傷害", quality,
                        trimNumber(quality * REGULATION_QUALITY_SECONDS), trimNumber(quality * REGULATION_QUALITY_DAMAGE));
            }
            default -> {
            }
        }
        final String what = definition.summon() != null ? "召喚物傷害" // 2026-10-23：精魂召喚的機制是 aura，先看有沒有召喚
                : definition.mechanic() == Mechanic.AURA ? "光環效果" : definition.mechanic() == Mechanic.WARCRY ? "戰吼效果"
                : definition.id().endsWith("_curse") ? "詛咒效果" : "傷害";
        final double amount = what.endsWith("效果") ? quality * 0.5 : quality;
        return String.format(Locale.ROOT, "品質 +%d%%：增加 %s%% %s", quality,
                amount == Math.rint(amount) ? String.valueOf((long) amount) : String.valueOf(amount), what);
    }

    private static String trimNumber(final double value) {
        final double rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    /**
     * 2026-10-22：給放在宿主裡的技能用的輔助：宿主第 2～5 孔的輔助，其中「要圖騰才能掛的」（疾樁、樁怒、堅樁…）放進去的技能本身
     * 沒有圖騰標籤、SkillBuild 會跳過，所以把它們的修飾器併成一顆不挑技能的輔助（名字用宿主的）；魔力倍率不算（放圖騰才花魔力）。
     */
    List<SupportDefinition> innerSupportsOf(final SkillLoadout.Entry host, final SkillDefinition hostDefinition, final SkillDefinition inner) {
        final List<SupportDefinition> list = new ArrayList<>();
        final List<tw.exilecore.stats.Modifier> carried = new ArrayList<>();
        for (final SupportDefinition support : supportsOf(host)) {
            if (support.requires().contains(Tags.TOTEM) && inner != null && !support.supports(inner)) {
                carried.addAll(support.modifiers());
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
        return host == null || host.inner() == null ? null : config.skill(host.inner().skillId());
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
        boolean hostChanged = false;
        for (int position = 0; position < SkillLoadout.MAX_SKILLS; position++) {
            final SkillLoadout.Entry original = loadout.skill(position);
            if (original == null) {
                continue;
            }
            if (config.skill(original.skillId()) == null) {
                loadout.remove(position);
                give.add(gems.create(GemData.uncutSkill(original.level()), 1));
                for (final String support : original.supportIds()) {
                    give.add(gems.supportOrUncut(support));
                }
                names.add("技能 " + original.skillId() + " Lv." + original.level());
                continue;
            }
            SkillLoadout.Entry entry = original;
            if (entry.inner() != null && config.skill(entry.inner().skillId()) == null) { // 2026-10-22：放在宿主裡的技能被拿掉
                give.add(gems.create(GemData.uncutSkill(entry.inner().level()), 1));
                names.add("技能 " + entry.inner().skillId() + " Lv." + entry.inner().level());
                entry = entry.withInner(null);
            }
            for (int i = 0; i < entry.sockets(); i++) {
                final String support = entry.support(i);
                if (!support.isEmpty() && config.support(support) == null) {
                    give.add(gems.supportOrUncut(support));
                    entry = entry.withSupport(i, null);
                    names.add("輔助 " + support);
                }
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
        if (give.isEmpty()) {
            return;
        }
        gems.giveAll(player, give);
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        player.sendMessage(plugin.text().prefixed("<yellow>技能改版：技能面板上拿掉的 " + names.size()
                + " 個技能／輔助已經退還放進背包（" + ItemRenderer.escape(String.join("、", names)) + "）"));
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
    private void showCooldown(final Player player, final String skillId, final long ticks) {
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
            final List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (final String line : lore) {
                lines.add(ItemRenderer.line(line));
            }
            meta.lore(lines);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        });
        return stack;
    }

    // ---- 說明文字 ----

    /** 普攻在這個玩家身上的說明（按鍵格用）。 */
    public List<String> describeDefault(final Player player, final SkillDefinition definition) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        return describe(player, SkillBuild.of(config, definition, 1, state.level(), true, List.of(), state.weaponTags(), sheet));
    }

    /** 面板上（或寶石裡）一個技能在這個玩家身上的說明。 */
    public List<String> describe(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry) {
        final PlayerState state = plugin.players().get(player);
        final List<String> lines = new ArrayList<>(describe(player, build(state, plugin.players().sheet(player), definition, entry)));
        // 2026-10-23：品質、腐化放在標籤下面第一行（以前面板上看不到）
        final List<String> top = new ArrayList<>();
        final String quality = qualityText(definition, entry.quality());
        if (quality != null) {
            top.add("<#8888ff>◆ " + ItemRenderer.escape(quality));
        }
        if (entry.corrupted()) {
            top.add("<#d20000>◆ 已腐化 <dark_gray>（不能再升級、開孔、加品質）");
        }
        if (!top.isEmpty() && !lines.isEmpty()) {
            lines.addAll(1, top);
        }
        return lines;
    }

    private String hostNameOf(final SkillLoadout.Entry entry) {
        final SkillDefinition definition = config.skill(entry.skillId());
        return definition == null ? entry.skillId() : definition.name();
    }

    /**
     * 2026-10-23：宿主寶石第 1 孔裡那顆技能的說明（技能面板的第 1 孔圖示用）：
     * 英靈圖騰：英靈那一下的傷害、暴擊、輔助（吃圖騰傷害）；主要寶石（暴擊引星、法術圖騰…）：插槽法術吃著第 2～5 孔輔助的完整說明。
     */
    public List<String> innerLines(final Player player, final SkillDefinition host, final SkillLoadout.Entry hostEntry) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        if (SkillExecutor.hasFlag(host, StatKeys.FLAG_TOTEM_LINKED)) {
            return linkedMace(state, sheet, host) instanceof SkillBuild found ? ancestorLines(state, sheet, found) : List.of();
        }
        final SkillLoadout.Entry inner = innerEntry(hostEntry);
        final SkillDefinition spell = inner == null ? null : config.skill(inner.skillId());
        if (spell == null) {
            return List.of();
        }
        final List<String> lines = new ArrayList<>();
        lines.add("<#e7c56b>" + ItemRenderer.escape(spell.name()) + "<gray>（由" + ItemRenderer.escape(host.name()) + "施放、吃第 2～5 孔的輔助）：");
        for (final String line : describe(player, spell, inner)) {
            lines.add("<gray>　" + line);
        }
        return lines;
    }

    /** 2026-10-23：名字後面的品質、腐化小標（技能面板的標題用）；都沒有回傳空字串。 */
    public static String qualityTag(final SkillLoadout.Entry entry) {
        return (entry.quality() > 0 ? " <#8888ff>品質 " + entry.quality() + "%" : "") + (entry.corrupted() ? " <#d20000>腐化" : "");
    }

    /** 圖鑑（2026-10）：看 Lv.30、Lv.40 這種超過寶石上限的數值——寶石當 20 級，多的當成「+x 技能等級」（再加上你裝備本來的）。 */
    public List<String> describeExtra(final Player player, final SkillDefinition definition, final int extraLevels) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player).overlay(List.of(
                tw.exilecore.stats.Modifier.flat(StatKeys.SKILL_LEVEL, extraLevels, "圖鑑")));
        return describe(player, build(state, sheet, definition, new SkillLoadout.Entry(definition.id(), SkillLoadout.MAX_LEVEL)));
    }

    /** 技能說明：數值都是用玩家目前的屬性與掛的輔助寶石算的。 */
    public List<String> describe(final Player player, final SkillBuild build) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillDefinition definition = build.definition();
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
                final double actual = build.intervalTicks(sheet) / 20.0;
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
        lines.addAll(resourceLines(player, definition));
        if (build.deploy() != null) {
            lines.addAll(deployLines(build, sheet, deployables.count(player.getUniqueId(), definition.id())));
        }
        if (definition.summon() != null) {
            lines.addAll(summonLines(player, build, sheet, minions.count(player.getUniqueId(), definition.id())));
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
                    clipSize(build, state.sheet()), reloadTicks(build, state.sheet()) / 20.0));
        }
        if (build.cooldownTicks() > 0 && definition.mechanic() != Mechanic.AURA) { // 光環不用按，冷卻沒有意義
            final double left = cooldownLeftSeconds(player, definition.id());
            lines.add(String.format(Locale.ROOT, "<gray>冷卻時間：<white>%.1f 秒", build.cooldownSeconds())
                    + (left > 0 ? String.format(Locale.ROOT, " <red>（還要 %.1f 秒）", left) : ""));
            if (definition.cooldownBypass() != null) {
                final String id = definition.cooldownBypass();
                final String color = chargeColor(id);
                final int held = chargesHeld(player, id);
                lines.add("<gray>冷卻中按下去：消耗一顆<" + color + ">" + ItemRenderer.escape(chargeName(id)) + "</" + color + ">直接施放"
                        + (held > 0 ? "（現在 <white>" + held + "</white> 顆）" : ""));
            }
        }
        if (build.manaCost() > 0) {
            lines.add("<gray>魔力消耗：<#5599ff>" + build.manaCost());
        }
        final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, build.profile());
        if (deals) {
            // 2026-10：好幾種傷害（噬異爆的火、冰、雷）先寫合計，括號裡再分開寫（實際打中時本來就是加在一起）
            final long kinds = java.util.Arrays.stream(tw.exilecore.stats.DamageType.values()).filter(type -> expected.get(type) > 0).count();
            lines.add(kinds > 1
                    ? String.format(Locale.ROOT, "<gray>平均每次：<white>%d <dark_gray>（%s）", Math.round(expected.total()), expected)
                    : String.format(Locale.ROOT, "<gray>平均每次：<white>%s", expected.isEmpty() ? "0" : expected));
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
                lines.add(String.format(Locale.ROOT, "<gray>放圖騰：每秒最多 <white>%.2f<gray> 次", 20.0 / build.intervalTicks(sheet)));
                if (deploy.interval() > 0) {
                    final boolean linked = SkillExecutor.hasFlag(definition, StatKeys.FLAG_TOTEM_LINKED);
                    final SkillBuild ancestor = linked && linkedMace(state, sheet, definition) instanceof SkillBuild found ? found : null;
                    final double speed = SkillDeployables.totemSpeed(build, sheet, linked);
                    final double rate = ancestor != null ? 20.0 / SkillDeployables.ancestorTicks(build, ancestor, sheet)
                            : 20.0 / SkillDeployables.totemTicks(deploy.interval(), speed);
                    lines.add(String.format(Locale.ROOT, "<gray>每個圖騰每秒 <white>%.2f<gray> 次（滿 %d 個合計 <white>%.2f<gray> 次）%s",
                            rate, build.totems(), build.totems() * rate,
                            linked ? "　<dark_gray>（" + (ancestor == null ? "第 1 孔沒有放能用的錘子技能" : ItemRenderer.escape(ancestor.definition().name()) + "的攻擊時間 ×1.5")
                                    + " ÷ 你的攻速）"
                                    : build.isAttack() ? "　<dark_gray>（吃你的攻擊速度）" : "　<dark_gray>（吃你的施法速度）"));
                    if (ancestor != null) {
                        lines.addAll(ancestorLines(state, sheet, ancestor)); // 2026-10-23：英靈那一下的傷害、暴擊、輔助
                    }
                }
            } else if (config.meta(definition.id()) != null && config.meta(definition.id()).totem()) {
                // 2026-10-21：法術圖騰寶石的施放速度是「放圖騰」的速度（圖騰放插槽法術的速度寫在上面的主要寶石那幾行）
                lines.add(String.format(Locale.ROOT, "<gray>放圖騰：每秒最多 <white>%.2f<gray> 次", 20.0 / build.intervalTicks(sheet)));
            } else {
                // 2026-10-22：有冷卻的照冷卻算（以前只看施放時間：掠閃寫每秒 10 次、印記 4.98 次、千鈞砸 1.12 次，實際被冷卻卡住）
                final double interval = build.intervalTicks(sheet);
                if (build.cooldownTicks() > interval && definition.mechanic() != Mechanic.AURA) {
                    lines.add(String.format(Locale.ROOT, "<gray>每秒 <white>%.2f<gray> 次 <dark_gray>（受冷卻限制；不算冷卻每秒 %.2f 次）",
                            hitsPerSecond(interval, build.cooldownTicks(), build.repeats()), 20.0 / interval));
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
                names.add(ItemRenderer.escape(support.name()));
            }
            lines.add("<gray>輔助：<#8fd3ff>" + String.join("、", names));
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
        final List<tw.exilecore.stats.Modifier> bonus = resourceBonus(config.resource(definition.id()));
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
        lines.add(String.format(Locale.ROOT, "<gray>放了之後 <white>%.1f</white> 秒內，你下一次<white>弓或矛的投射物攻擊</white>自動重複 <white>%d</white> 次",
                guard.duration() * build.durationScale(), barrage.repeats()));
        lines.add(String.format(Locale.ROOT, "<gray>　每次重複少 <white>%s%%</white> 傷害、隔 %s 秒、不花魔力；每次都是重新射，可以再打到同一個敵人",
                ModifierText.number(barrage.less()), ModifierText.number(barrage.gap())));
        if (barrage.charge() != null && barrage.perCharge() > 0) {
            final String color = chargeColor(barrage.charge());
            final int held = player == null ? 0 : chargesHeld(player, barrage.charge());
            lines.add("<gray>　施放時用掉身上全部的<" + color + ">" + ItemRenderer.escape(chargeName(barrage.charge())) + "</" + color
                    + ">，每顆多重複 <white>" + barrage.perCharge() + "</white> 次" + (held > 0 ? "（現在 <white>" + held + "</white> 顆）" : ""));
        }
        lines.add(String.format(Locale.ROOT, "<gray>　用掉之後的冷卻：%s 秒＋那一招攻擊時間的 <white>%s%%</white>（最多 %s 秒）",
                ModifierText.number(build.definition().cooldown()), ModifierText.number(barrage.cooldownAttackTime().at(build.level())),
                ModifierText.number(barrage.maxCooldown())));
        lines.add("<dark_gray>　有冷卻的攻擊、引導技能不能被強化");
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
                            params.splash().targets(), Math.round(params.splash().damage())));
                }
                if (params.scatter() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>停下時散射 <white>%d</white> 支（各 <white>%d%%</white> 傷害）",
                            params.scatter().count(), Math.round(params.scatter().damage())));
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
                            params.aftershock().delay(), params.aftershock().radius(), Math.round(params.aftershock().more())));
                }
            }
            case CONE -> {
                lines.add(String.format(Locale.ROOT, "<gray>前方 <white>%.1f</white> 格、%d° 的扇形",
                        build.length(), Math.round(build.angle())));
                if (params.aftershock() != null) {
                    lines.add(String.format(Locale.ROOT, "<gray>第二擊：<white>%.1f</white> 秒後在前方再砸一圈（半徑 <white>%.1f</white> 格），更多 <white>%d%%</white> 傷害",
                            params.aftershock().delay(), build.length() * params.aftershock().radius(), Math.round(params.aftershock().more())));
                }
            }
            case LEAP -> lines.add(String.format(Locale.ROOT, "<gray>最遠跳 <white>%.0f</white> 格，落地半徑 <white>%.1f</white> 格",
                    build.range(), build.radius()));
            case CHAIN -> lines.add("<gray>連鎖 <white>" + build.chains() + "</white> 次");
            case DASH -> {
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
            case IMPACT -> lines.add(String.format(Locale.ROOT, "<gray>準心指的地方（最遠 %.0f 格）<white>%.1f</white> 秒後落下，半徑 <white>%.1f</white> 格",
                    build.range(), params.delay(), build.radius()));
            case ZONE -> {
                final SkillParams.Zone zone = params.zone();
                final String where = zone.line() ? String.format(Locale.ROOT, "面前一道 <white>%.0f</white> 格長的", build.length())
                        : build.range() <= 0 ? "腳下" : String.format(Locale.ROOT, "準心指的地方（最遠 %.0f 格）半徑 <white>%.1f</white> 格的", build.range(), build.radius());
                lines.add(String.format(Locale.ROOT, "<gray>在%s區域留 <white>%.1f</white> 秒，每 %.1f 秒對裡面的敵人造成 <white>%d%%</white> 傷害%s",
                        where, zone.duration() * build.durationScale(), zone.interval(), Math.round(zone.damage()),
                        zone.pull() > 0 ? "，並把敵人往中心吸（玩家不會被吸）" : ""));
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
                            build.range(), mark.radius() * build.areaScale(), mark.duration() * build.durationScale());
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
                    lines.add(String.format(Locale.ROOT, "<gray>標記準心指的敵人（最遠 %.0f 格）<white>%.1f</white> 秒：%s對牠", build.range(),
                            mark.duration() * build.durationScale(), mark.selfOnly() ? "只有你" : "") + String.join("<gray>、", parts));
                    break;
                }
                lines.add(String.format(Locale.ROOT, "<gray>準心指的敵人（最遠 %.0f 格）<white>%.1f</white> 秒內%s受到的傷害增加 <white>%d%%</white>",
                        build.range(), mark.duration() * build.durationScale(), mark.selfOnly() ? "被你" : "", Math.round(mark.taken() * fx)));
            }
            case GUARD -> {
                final SkillParams.Mark guard = params.mark();
                if (guard.effects().isEmpty() && guard.slow().at(build.level()) <= 0) {
                    break; // 沒有增益的（連珠）：說明在 barrageLines
                }
                final List<String> parts = new ArrayList<>();
                for (final ScaledMod effect : guard.effects()) {
                    parts.add("<white>" + effect.describe(build.level(), 1.0) + "</white>");
                }
                final double slowPct = guard.slow().at(build.level());
                if (slowPct > 0) {
                    parts.add(String.format(Locale.ROOT, "移動速度慢 <white>%d%%</white>", Math.round(slowPct)));
                }
                lines.add(String.format(Locale.ROOT, build.definition().weapons().isEmpty() ? "<gray>要拿盾：舉起來 <white>%.1f</white> 秒，" : "<gray><white>%.1f</white> 秒內：",
                        guard.duration() * build.durationScale())
                        + String.join("<gray>、", parts));
            }
            case DESECRATE -> lines.add(String.format(Locale.ROOT,
                    "<gray>準心 %.0f 格內的地方拖出 <white>%d</white> 具屍體，大小是同等級一般怪的 <white>%d%%</white>（同一處最多 8 具）",
                    build.range(), params.targets(), Math.round(params.percent().at(build.level()))));
            case CORPSE -> lines.add(String.format(Locale.ROOT,
                    "<gray>引爆準心附近 %.0f 格內最近的屍體（怪物死後 10 秒內）：半徑 <white>%.1f</white> 格，再加上屍體最大生命 <white>%d%%</white> 的火焰傷害",
                    params.search(), build.radius(), Math.round(params.percent().at(build.level()))));
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
        return 20.0 / SkillDeployables.totemTicks(deploy.interval(), SkillDeployables.totemSpeed(build, sheet, false));
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
                // 2026-10-23：圖騰生命照技能等級
                lines.add(String.format(Locale.ROOT, "<gray>圖騰生命：你最大生命的 <white>%.0f%%</white>（照技能等級；再乘圖騰生命的加成）",
                        SkillDeployables.totemLifeFactor(build.level()) * 100));
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
        lines.add(spec.permanent() ? "<gray>不會消失：直到被打死、你下線或換角色"
                : String.format(Locale.ROOT, "<gray>持續 <white>%.1f</white> 秒", spec.duration() * build.durationScale()));
        if (plugin.monsters() != null) {
            final tw.exilecore.monster.MonsterConfig monsters = plugin.monsters().config();
            final int level = MinionSheets.monsterLevel(config, build.level(), plugin.players().get(player).level());
            final double[] damage = monsters.damageAt(level);
            final double scale = MinionSheets.extraScale(config, build.level()); // 2026-10：超過 20 級的技能等級
            final MinionSheets.Base base = new MinionSheets.Base(monsters.lifeAt(level) * scale, monsters.accuracyAt(level), monsters.evasionAt(level),
                    monsters.armourAt(level), damage[0] * scale, damage[1] * scale, monsters.critChance());
            final List<Modifier> ownerModifiers = new ArrayList<>(MinionSheets.ownerModifiers(ownerSheet));
            ownerModifiers.addAll(auras.effectsFor(player.getUniqueId()));
            final StatSheet minionSheet = MinionSheets.build(spec, base, ownerModifiers);
            final tw.exilecore.stats.SkillProfile profile = MinionSheets.profile(build);
            final StatSheet effective = profile.effective(minionSheet);
            final DamagePacket expected = DamageCalculator.expectedOutgoing(minionSheet, profile);
            lines.add(String.format(Locale.ROOT, "<gray>召喚物等級 <white>%d</white>：生命 <white>%,d</white>、每下平均 <white>%s</white>、每秒 <white>%.2f</white> 次",
                    level, Math.round(MinionSheets.life(effective)), expected.isEmpty() ? "0" : expected,
                    MinionSheets.attacksPerSecond(effective, profile)));
            // 2026-10-16：召喚物的暴擊率、暴擊傷害（跟 /stats 的召喚物每秒傷害同一套算法：含主人身上「召喚物…」的加成、光環、輔助）
            lines.add(String.format(Locale.ROOT, "<gray>召喚物暴擊率 <white>%s%%</white>、暴擊傷害 <white>×%s%%</white>",
                    ModifierText.number(DamageCalculator.critChance(minionSheet, profile) * 100),
                    ModifierText.number(DamageCalculator.critMultiplier(effective, profile.tags(), null) * 100)));
            final double regen = MinionSheets.regenPercent(effective);
            if (regen > 0) {
                lines.add(String.format(Locale.ROOT, "<gray>召喚物每秒回復 <white>%s%%</white> 最大生命", ModifierText.number(regen)));
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
        if (spec.persistent()) {
            lines.add("<#ffb070>常駐技能<gray>：不保留魔力，不算光環（光環的輔助與加成都不吃）");
        } else {
            if (build.minionReserve() > 0) {
                // 2026-10（照 PoE2）：精魂召喚每一隻保留精魂，付得起幾隻就叫幾隻
                final int cap = auras.minionCap(player.getUniqueId(), build.definition().id());
                final int free = Math.min(build.minions(), spec.freeMinions());
                lines.add(String.format(Locale.ROOT, "<#8fd3ff>精魂召喚<gray>：每隻保留 <white>%s</white> 精魂%s%s（你有 %.0f%s）",
                        ModifierText.number(Math.round(build.minionReserve() * 10) / 10.0),
                        free > 0 ? "，前 " + free + " 隻不用" : "",
                        build.reserve() > 0 ? String.format(Locale.ROOT, "，另外固定 %.0f", build.reserve()) : "",
                        Math.max(0, plugin.players().sheet(player).get(StatKeys.SPIRIT_MAX)),
                        others > 0 ? String.format(Locale.ROOT, "，其他光環已保留 %.0f", others) : ""));
                if (on && cap > 0) {
                    lines.add(String.format(Locale.ROOT, "<gray>現在養 <white>%d</white>／%d 隻，共保留 <white>%.0f</white> 精魂%s", Math.min(cap, build.minions()),
                            build.minions(), auras.reserveOf(player.getUniqueId(), build.definition().id()),
                            cap < build.minions() ? "<dark_gray>（精魂多一點就能多養）" : ""));
                } else {
                    lines.add(String.format(Locale.ROOT, "<gray>養滿 %d 隻要 <white>%.0f</white> 精魂；精魂不夠時付得起幾隻就叫幾隻",
                            build.minions(), build.reserve() + Math.max(0, build.minions() - free) * build.minionReserve()));
                }
            } else {
            lines.add(String.format(Locale.ROOT, "<#8fd3ff>%s<gray>：保留 <white>%.0f</white> 精魂（你有 %.0f%s）", spec.herald() ? "捷光環" : "光環", build.reserve(),
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
            lines.add(String.format(Locale.ROOT, "<#8fd3ff>　受到的%s傷害增加 <white>%.0f%%</white>", kind, enemy.taken().at(build.level()) * scale));
            if (enemy.rampSeconds() > 0 && enemy.rampTaken() != null) {
                lines.add(String.format(Locale.ROOT, "<#8fd3ff>　在光環裡待滿 %s 秒：改成增加 <white>%.0f%%</white>",
                        enemy.rampSeconds() == Math.rint(enemy.rampSeconds()) ? Long.toString((long) enemy.rampSeconds()) : Double.toString(enemy.rampSeconds()),
                        enemy.rampTaken().at(build.level()) * scale));
            }
            if (scale != 1.0) {
                lines.add(String.format(Locale.ROOT, "<dark_gray>　（已含光環效果 ×%.2f）", scale));
            }
        }
        if (spec.burns() && spec.selfBurn() > 0) {
            lines.add(String.format(Locale.ROOT, "<red>開著的時候你每秒受到最大生命＋能量護盾 %.0f%% 的火焰傷害<gray>（目前每秒 <white>%.0f</white>，已算火焰抗性；燒不死你，最低留 1 點生命；營地不燒，練習場照燒）",
                    spec.selfBurn(), auras.selfBurnPerSecond(player, spec)));
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
                lines.add(String.format(Locale.ROOT, "<gray>　只有普通、魔法敵人的屍體會炸；再多打牠累積失血的 <white>%.0f%%</white>（不吃加成），炸過的屍體就沒了",
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
        lines.add(AuraService.stateText(on, spec.persistent()));
        return lines;
    }

    /** 戰吼的說明。 */
    private static List<String> warcryLines(final SkillBuild build) {
        final WarcrySpec spec = build.definition().warcry();
        final List<String> lines = new ArrayList<>();
        final double duration = spec.duration() * build.durationScale();
        lines.add(String.format(Locale.ROOT, spec.spike() ? "<#c9a0ff>祭柱<gray>：用掉 12 格內一具屍體（沒有就獻祭一隻你的召喚物）立柱，柱子半徑 <white>%.0f</white> 格"
                : spec.minionBuff().isEmpty() ? "<#f5b942>戰吼<gray>：半徑 <white>%.0f</white> 格，瞬間發動" : "<#c9a0ff>祭品<gray>：半徑 <white>%.0f</white> 格，瞬間發動", spec.radius() * build.areaScale()));
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
            for (final ScaledMod mod : spec.minionBuff()) {
                lines.add("<#c9a0ff>　" + ItemRenderer.escape(mod.describe(build.level(), 1.0)));
            }
        }
        if (build.exerts() > 0) {
            lines.add(String.format(Locale.ROOT, "<gray>接下來 <white>%d</white> 次攻擊更多 <white>%d%%</white> 傷害（%.1f 秒內有效）",
                    build.exerts(), Math.round(spec.exertMore().at(build.level())), duration));
        }
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
            Tags.DURATION, Tags.COOLDOWN, "crystal", Tags.CORPSE);

    /** 技能的標籤（不含攻擊、法術），照 {@link #TAG_ORDER} 排好。 */
    static List<String> orderedTags(final SkillDefinition definition) {
        final List<String> tags = new ArrayList<>();
        for (final String tag : definition.tags()) {
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
                : definition.isAttack() ? tw.exilecore.item.AffixChips.Chip.ATTACK : tw.exilecore.item.AffixChips.Chip.CASTER);
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
}
