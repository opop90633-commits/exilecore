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
    private final MinionService minions;
    private final SkillBuffs buffs;
    private final AuraService auras;
    /** 常駐的地面圈（套了資源包的人看平面圈，取代一直畫的粒子）。 */
    private final GroundDecals decals;

    public GroundDecals decals() {
        return decals;
    }
    private final Warcries warcries;
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
        this.gems = new GemService(plugin, this);
        this.hold = new HoldToCast(plugin, this);
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
        auras.forget(player.getUniqueId());
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

    private SupportProcs procs;
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
    static final double BASE_RELOAD_SECONDS = 0.5;
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

    /** 觸發（2026-10）：自動施放面板上的一個法術（不花魔力、不看冷卻與施法時間；位移、召喚、光環、戰吼不會被觸發）。 */
    void triggerCast(final Player player, final SkillDefinition definition, final SkillLoadout.Entry entry, final LivingEntity target) {
        final Mechanic mechanic = definition.mechanic();
        if (mechanic == Mechanic.AURA || mechanic == Mechanic.SUMMON || mechanic == Mechanic.WARCRY || mechanic == Mechanic.DASH
                || mechanic == Mechanic.LEAP || mechanic == Mechanic.FLICKER || mechanic == Mechanic.RALLY
                || mechanic == Mechanic.GUARD) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillBuild build = build(state, sheet, definition, entry).rollElement(sheet, plugin.items().random());
        final SkillRuntime runtime = runtime(player);
        final long now = now();
        final java.util.UUID previousFx = Fx.source(player.getUniqueId());
        try {
            executor.execute(player, runtime, build, target, now);
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("觸發 {} 失敗", definition.id(), e);
        } finally {
            Fx.restore(previousFx);
        }
    }

    /** 夥伴能不能出來：有角色、不在營地（營地裡收起來）。 */
    boolean eligibleForCompanion(final Player player) {
        return plugin.players().get(player).hasCharacter() && !inTown(player);
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
            runtime.clearQueue();
            perform(player, runtime, key, preferred, now);
        } else {
            runtime.queue(key, preferred == null ? null : preferred.getUniqueId(), now);
        }
    }

    /** 綁了什麼（狀態列、提示用）：「｜左 熾星彈｜右 霜星爆｜3 怒雷」，都沒綁是「（都是普攻）」。 */
    String bindingSummary(final PlayerState state) {
        final StringBuilder text = new StringBuilder();
        for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
            final Resolved resolved = resolve(state, slot);
            if (!resolved.isDefault()) {
                text.append("<dark_gray>｜<gray>").append(SkillLoadout.shortKeyName(slot)).append(" <white>")
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
            for (final tw.exilecore.combat.Corpses.Corpse corpse : plugin.combat().corpses().near(at.getWorld().getUID(), at.getX(), at.getZ(), 14, now)) {
                visuals.corpseMarker(new Location(at.getWorld(), corpse.x(), corpse.y(), corpse.z()));
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
        if (now - runtime.queuedAt > SkillRuntime.QUEUE_TIMEOUT || !eligible(player)) {
            runtime.clearQueue();
            return;
        }
        if (now + 1e-9 >= runtime.readyAt) {
            final int key = runtime.queuedKey;
            final LivingEntity target = runtime.queuedTarget == null ? null : living(player, runtime.queuedTarget);
            runtime.clearQueue();
            perform(player, runtime, key, target, now);
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
    private void perform(final Player player, final SkillRuntime runtime, final int key, final LivingEntity target, final long now) {
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
        if (inTown(player) && definition.mechanic() != Mechanic.AURA) {
            fail(player, runtime, state, "營地裡不能戰鬥", now); // 光環在營地也能開關
            return;
        }
        if (pressed.entry() != null && definition.type() == SkillType.SPELL && triggers().triggeredOnly(pressed.entry())) {
            // 2026-10（照 PoE）：掛了觸發輔助的法術只會自動觸發，不能自己按
            fail(player, runtime, state, definition.name() + "掛了觸發輔助：只會自動觸發，不能手動施放", now);
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
        if (bypassing && !plugin.combat().charges().consume(player, bypass)) {
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
        // 冰緩讓攻擊與施法變慢（動作速度 0.7 = 間隔 ÷ 0.7）
        final double speed = plugin.ailments() == null ? 1.0 : Math.max(0.05, plugin.ailments().actionSpeed(player));
        final double interval = build.intervalTicks(sheet) / speed;
        runtime.readyAt = SkillMath.nextReady(runtime.readyAt, now, interval);
        if (key != SkillLoadout.KEY_LEFT && key != SkillLoadout.KEY_SNEAK_LEFT) {
            player.swingMainHand(); // 左鍵客戶端自己會揮手；右鍵、數字鍵要伺服器揮
        }
        runtime.startCooldown(definition.id(), now, build.cooldownTicks());
        if (build.deploy() != null) {
            if (linked != null) {
                deployables.place(player, build, linked, Math.round(linked.intervalTicks(sheet) * 1.5)); // 英靈：錘子技能的攻擊時間 ×1.5 叫一次
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
            state.notice(auras.isActive(player.getUniqueId(), definition.id())
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
        final List<tw.exilecore.stats.Modifier> infused = resources.consumeInfusion(player, build.profile().effective(sheet), build.profile().tags());
        if (!infused.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(infused));
            state.notice("<#ffd27f>" + ItemRenderer.escape(definition.name()) + " 用掉灌注", now + 20);
        }
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
        } else {
            airborne = executor.execute(player, runtime, build, preferred, now);
        }
        resources.gainInfusion(player, build.profile().effective(sheet), build.profile().tags()); // 2026-10：放完疊灌注
        if (config.requiresMount(definition.id())) {
            mounts().charge(player); // 坐騎技能：坐騎往前衝一下
        }
        procs().onUse(player, definition, useLocation(player, definition, build, preferred)); // 2026-10：輔助觸發的專屬技能
        // 2026-10：幻弓影：弓的攻擊過一下由身旁的幻影再射一次（傷害少很多）
        if (definition.isAttack() && definition.weapons().contains("bow") && !definition.hidden() && definition.cooldown() <= 0
                && StatKeys.flag(state.sheet(), StatKeys.FLAG_MIRAGE)) {
            final SkillBuild mirage = build.withProfile(build.profile().scaled(0.4, "幻影"));
            final org.bukkit.util.Vector facing = SkillTargets.facing(player);
            final org.bukkit.Location from = player.getLocation().add(new org.bukkit.util.Vector(-facing.getZ(), 0, facing.getX()).multiply(1.5));
            final java.util.UUID aim = preferred == null ? null : preferred.getUniqueId();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getWorld().equals(from.getWorld())) {
                    from.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, from.clone().add(0, 1, 0), 12, 0.2, 0.5, 0.2, 0.01);
                    executor.executeFrom(Origin.at(player, from, SkillTargets.facing(player)), mirage, aim == null ? null : living(player, aim));
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
        if (spec.charge() != null && spec.perCharge() > 0 && plugin.combat() != null && plugin.combat().charges() != null) {
            while (consumed < 20 && plugin.combat().charges().consume(player, spec.charge())) {
                consumed++;
            }
        }
        final int repeats = spec.repeatsWith(consumed);
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
        final SkillLoadout.Entry next = position < 0 ? null : loadout.skill(position + 1);
        final SkillDefinition definition = next == null ? null : config.skill(next.skillId());
        final String hint = totem.name() + "：技能面板上它後面那一格要放錘子攻擊技能";
        if (definition == null || !definition.isAttack() || !definition.weapons().contains("mace")) {
            return hint;
        }
        if (definition.cooldown() > 0 || SkillExecutor.hasFlag(definition, StatKeys.CHANNEL_STAGES) || !SkillBuild.deployable(definition)) {
            return hint + "（不能是有冷卻、蓄力、位移的技能）";
        }
        return build(state, sheet, definition, next);
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

    public List<String[]> skillDps(final Player player) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final List<String[]> rows = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int slot = 0; slot < SkillLoadout.BIND_SLOTS; slot++) {
            final Resolved resolved = resolve(state, slot);
            if (resolved.isDefault() || !seen.add(resolved.definition().id())) {
                continue;
            }
            final SkillDefinition definition = resolved.definition();
            if (definition.summon() != null) {
                // 2026-10：召喚技能算召喚物的（每隻每秒傷害 × 同時最多幾隻；精魂召喚要開著才算）
                final SkillBuild build = build(state, sheet, resolved);
                if (definition.mechanic() == Mechanic.AURA && !auras.isActive(player.getUniqueId(), definition.id())) {
                    continue;
                }
                final double each = minionDps(player, build, sheet);
                final int cap = auras.minionCap(player.getUniqueId(), definition.id()); // 2026-10：照隻數算精魂的，只算養得起的隻數
                final int count = Math.max(1, cap > 0 ? Math.min(build.minions(), cap) : build.minions());
                if (each > 0) {
                    rows.add(new String[] {SkillLoadout.shortKeyName(slot), definition.name() + "（召喚物 ×" + count + "）",
                            String.valueOf(Math.round(each * count))});
                }
                continue;
            }
            if (!definition.dealsDamage() || definition.mechanic() == Mechanic.AURA) {
                continue;
            }
            final SkillBuild build = build(state, sheet, resolved);
            final DamagePacket expected = DamageCalculator.expectedOutgoing(sheet, build.profile());
            final double crit = DamageCalculator.critChance(sheet, build.profile());
            final double multi = build.profile().effective(sheet).get(StatKeys.CRIT_MULTI, build.profile().tags());
            // 有冷卻的：一輪要等「施放間隔」和「冷卻」比較長的那個。
            // 2026-10：複擊的每一下都要花一次攻擊間隔（打完才能做別的），以前當成同一個間隔裡打完，面板高了好幾倍
            final SkillParams params = build.params();
            final double dps = expected.total() * (1 + crit * (multi / 100.0 - 1))
                    * hitsPerSecond(build.intervalTicks(sheet), build.cooldownTicks(), build.repeats())
                    * castHits(params.burst() == null ? 1 : params.burst().count(), params.aftershock() != null,
                            params.aftershock() == null ? 0 : params.aftershock().more());
            rows.add(new String[] {SkillLoadout.shortKeyName(slot), definition.name(), String.valueOf(Math.round(dps))});
        }
        return rows;
    }

    /** 一隻召喚物的估計每秒傷害（同召喚物說明的算法：平均一下 × 暴擊期望 × 每秒次數）；沒有怪物模組回傳 0。 */
    double minionDps(final Player player, final SkillBuild build, final StatSheet ownerSheet) {
        final SummonSpec spec = build.definition().summon();
        if (spec == null || plugin.monsters() == null) {
            return 0;
        }
        final tw.exilecore.monster.MonsterConfig monsters = plugin.monsters().config();
        final int level = MinionSheets.monsterLevel(config, build.level(), plugin.players().get(player).level());
        final double[] damage = monsters.damageAt(level);
        final double scale = MinionSheets.extraScale(config, build.level());
        final MinionSheets.Base base = new MinionSheets.Base(monsters.lifeAt(level) * scale, monsters.accuracyAt(level), monsters.evasionAt(level),
                monsters.armourAt(level), damage[0] * scale, damage[1] * scale, monsters.critChance());
        final List<Modifier> ownerModifiers = new ArrayList<>(MinionSheets.ownerModifiers(ownerSheet));
        ownerModifiers.addAll(auras.effectsFor(player.getUniqueId()));
        final StatSheet minionSheet = MinionSheets.build(spec, base, ownerModifiers);
        final tw.exilecore.stats.SkillProfile profile = MinionSheets.profile(build);
        final StatSheet effective = profile.effective(minionSheet);
        final DamagePacket expected = DamageCalculator.expectedOutgoing(minionSheet, profile);
        if (expected.isEmpty()) {
            return 0;
        }
        final double crit = DamageCalculator.critChance(minionSheet, profile);
        final double multi = effective.get(StatKeys.CRIT_MULTI, profile.tags());
        return expected.total() * (1 + crit * (multi / 100.0 - 1)) * MinionSheets.attacksPerSecond(effective, profile);
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
        return SkillBuild.of(config, definition, entry.level(), state.level(), false, supportsOf(entry), state.weaponTags(), sheet);
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
        auras.forget(player.getUniqueId());
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
            for (int i = 0; i < entry.sockets(); i++) {
                final String support = entry.support(i);
                if (!support.isEmpty() && config.support(support) == null) {
                    give.add(gems.supportOrUncut(support));
                    entry = entry.withSupport(i, null);
                    names.add("輔助 " + support);
                }
            }
            if (entry != original) {
                loadout.set(position, entry);
            }
        }
        if (give.isEmpty()) {
            return;
        }
        gems.giveAll(player, give);
        plugin.players().markDirty(player);
        plugin.characters().save(player);
        player.sendMessage(plugin.text().prefixed("<yellow>技能改版：技能面板上拿掉的 " + names.size()
                + " 個技能／輔助已經換成同等級的未切割寶石放進背包（" + ItemRenderer.escape(String.join("、", names)) + "）"));
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

    /**
     * 快捷列上顯示用的技能圖示（戰鬥區域裡第 3–8 格，見 {@link tw.exilecore.character.Gear}）：名字前面是按鍵，說明是技能的數值。
     * 回傳的是一般的物品，標成顯示用由 Gear 做。
     */
    public org.bukkit.inventory.ItemStack hotbarIcon(final Player player, final int bindSlot) {
        final PlayerState state = plugin.players().get(player);
        final Resolved resolved = resolve(state, bindSlot);
        final SkillDefinition definition = resolved.definition();
        final String key = SkillLoadout.shortKeyName(bindSlot);
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
        return describe(player, build(state, plugin.players().sheet(player), definition, entry));
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
                lines.add(String.format(Locale.ROOT, "<gray>施法時間：<white>%.2f 秒", definition.castTime()));
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
            lines.addAll(deployLines(build, deployables.count(player.getUniqueId(), definition.id())));
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
            lines.add(String.format(Locale.ROOT, "<gray>平均每次：<white>%s", expected.isEmpty() ? "0" : expected));
            // 這個技能自己的暴擊（照它的標籤：攻擊吃攻擊暴擊、法術吃法術暴擊；輔助寶石也算進去）
            lines.add(String.format(Locale.ROOT, "<gray>暴擊：<white>%.1f%% <dark_gray>× <white>%.0f%%",
                    DamageCalculator.critChance(sheet, build.profile()) * 100,
                    build.profile().effective(sheet).get(StatKeys.CRIT_MULTI, build.profile().tags())));
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
                // 2026-10：圖騰：你的攻速／施法速度只影響「放圖騰」多快；圖騰自己照固定間隔放（上面那行），不吃你的速度
                lines.add(String.format(Locale.ROOT, "<gray>放圖騰：每秒最多 <white>%.2f<gray> 次　<dark_gray>（攻速、施法速度只影響放圖騰）",
                        20.0 / build.intervalTicks(sheet)));
                if (deploy.interval() > 0) {
                    lines.add(String.format(Locale.ROOT, "<gray>每個圖騰每秒 <white>%.2f<gray> 次（滿 %d 個合計 <white>%.2f<gray> 次）",
                            1.0 / deploy.interval(), build.totems(), build.totems() / deploy.interval()));
                }
            } else {
                lines.add(String.format(Locale.ROOT, "<gray>每秒 <white>%.2f<gray> 次", 20.0 / build.intervalTicks(sheet)));
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

    /** 圖騰、陷阱、鐘的說明。 */
    private static List<String> deployLines(final SkillBuild build, final int active) {
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
                    lines.add(String.format(Locale.ROOT, "<#f5b942>圖騰<gray>：放在面前（時間到才消失），<white>%.1f</white> 秒內每 %.1f 秒對 %.0f 格內最近的敵人放一次（下面的效果）",
                            duration, spec.interval(), spec.range()));
                }
                lines.add("<gray>同時最多 <white>" + build.totems() + "</white> 個" + (active > 0 ? "，目前 " + active + " 個" : ""));
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
            final String who = build.generosity() ? String.format(Locale.ROOT, "%.0f 格內的隊友（效果 ×%.1f）與你的召喚物", spec.radius() * build.areaScale(), AuraService.GENEROSITY_ALLY_BONUS)
                    : spec.allies() ? String.format(Locale.ROOT, "你、%.0f 格內的隊友與你的召喚物", spec.radius() * build.areaScale()) : "你"; // 2026-10：只給自己的增益不再給召喚物
            lines.add("<gray>開著的時候" + who + "：");
            for (final String line : ModifierText.describeAll(effects)) {
                lines.add("<#8fd3ff>　" + ItemRenderer.escape(line));
            }
            if (build.auraEffect() != 1.0) {
                lines.add(String.format(Locale.ROOT, "<dark_gray>　（已含光環效果 ×%.2f）", build.auraEffect()));
            }
            // 2026-10（祕法之心）：照最大魔力算的效果，把現在的數字寫出來
            final double maxMana = Math.max(0, plugin.players().sheet(player).get(StatKeys.MANA_MAX));
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
            // 2026-10（照 PoE2）：有觸發條件的寫出來（用攻擊擊中殺死感電中的敵人、過量擊殺…）
            lines.add(String.format(Locale.ROOT, "<gray>%s時%s從屍體的位置放出 <white>%s</white>（照這顆寶石的等級與輔助）",
                    spec.condition().always() ? "擊殺" : "<white>" + spec.condition().describe() + "</white>",
                    spec.onKillChance() < 100 ? String.format(Locale.ROOT, " %.0f%% 機率", spec.onKillChance()) : "",
                    sub == null ? spec.onKill() : ItemRenderer.escape(sub.name())));
            if (spec.condition().needsOwnAttack()) {
                lines.add("<dark_gray>　法術、持續傷害、召喚物與捷自己的效果打死的不算");
            }
            if (sub != null && sub.damage() != null) {
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
            lines.add(String.format(Locale.ROOT, "<gray>%.1f 秒內你%s：", duration, spec.allies() ? "和範圍內的隊友" : ""));
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
                    build.exerts(), Math.round(spec.exertMore().at(build.level()) * build.auraEffect()), duration));
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
