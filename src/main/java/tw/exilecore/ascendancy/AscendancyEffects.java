package tw.exilecore.ascendancy;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.skill.SkillBuild;
import tw.exilecore.skill.SkillDefinition;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-10-26 照 PoE2 0.5：昇華天賦裡原本寫著「（尚未生效）」的機制（屬性鍵在 {@link AscKeys}）。這次之前這些天賦只有說明、沒有效果。
 * <p>
 * 別的模組在固定的地方呼叫這裡的靜態方法（模組沒啟用時什麼都不做）：
 * 放技能（{@link #cost}、{@link #paid}、{@link #beforeCast}）、受到擊中（{@link #defenderSheet}、{@link #enemyVsPlayer}、{@link #absorb}、
 * {@link #reroute}）、打出去（{@link #attackerConditional}）、異常（{@link #ailment}）、戰星（{@link #chargeSpent}）、圖騰、增益…
 * 狀態（玉石、黑暗、易爆、煉獄之炎、順風、適應…）只在記憶體，下線就清掉。
 * 一格當一格。
 */
public final class AscendancyEffects implements Listener {

    private static AscendancyEffects instance;

    final ExileCore plugin;
    final Map<UUID, AscState> states = new HashMap<>();
    final Map<UUID, EnemyState> enemies = new HashMap<>();
    final AscGranted granted;
    /** 2026-10-27 照 PoE2 0.5：補上的三個昇華（靈狩者、鳴鐘武師、淵井骸主）。 */
    final AscPaths paths;

    /** 每幾 tick 跑一次（玉石、黑暗、易爆、煉獄之炎…）。 */
    static final long PERIOD = 10;

    /**
     * 2026-10-27 修正：每 n tick 一次。這裡每 PERIOD（10）tick 才跑一次，起點是插件啟用那一刻的 tick（不一定是 10 的倍數），
     * 以前寫 now % 20 == 0 可能永遠不成立（召喚獄炎獵犬從來沒點燃、血肉低語、每秒的效果都可能整個沒作用）。
     */
    static boolean every(final long now, final long n) {
        return Math.floorMod(now, n) < PERIOD;
    }

    // ---- 原作的數值（keywords：Jade、Tailwind、Volatility、Adaptation、Archon、Infernal Flame…）----
    static final int JADE_MAX = 10;
    static final int TAILWIND_MAX = 10;
    static final double HIGH_INFERNAL = 0.65;
    static final double LOW_RESOURCE = 0.35;
    static final int ADAPTATIONS_MAX = 3;
    static final long RECENT = 80;
    /** 存在範圍（PoE2 Presence）。 */
    // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算（這次之前 6 格；實際用的是 combat.Presence）
    static final double PRESENCE = tw.exilecore.core.Units.metres(6);

    /** 一個玩家的狀態。 */
    static final class AscState {
        int slams;
        int jade;
        long jadeNext;
        /** 黑暗的保留：{量, 到哪個 tick}。 */
        final ArrayDeque<double[]> darkness = new ArrayDeque<>();
        int tailwind;
        double infernal;
        double infernalMax;
        long infernalGained;
        final EnumMap<DamageType, Long> adaptations = new EnumMap<>(DamageType.class);
        double lockedLife = -1;
        int unravel;
        long unravelNext;
        long sandsStart = -1;
        long cannonsFired;
        double sanguineDamage;
        long sanguineUntil;
        boolean lastDirtyFlags;
        int lastEmbankment;
        boolean forTheKill;
        boolean esPowerRecent;
        boolean conduitActive;
        double lifeSpentCarry;
        long chillGround;
        long expressionReady;
        long tempestReady;
        /** 2026-10-27：上次給效果的隊友指紋（{@link #giverSignature}）。 */
        long giverSignature;
    }

    /** 一隻敵人的狀態（專注、弱點、易爆…）。 */
    static final class EnemyState {
        double concentration = -1;
        double concentrationMax;
        long concentrationLost;
        /** 開放弱點的區段（生命比例 [起, 迄]），空的 = 沒揭露。 */
        double[][] weakness;
        double negativeArmour;
        long negativeUntil;
        final List<double[]> chills = new ArrayList<>();
        final List<double[]> shocks = new ArrayList<>();
        long lastSeen;
    }

    AscendancyEffects(final ExileCore plugin) {
        this.plugin = plugin;
        this.granted = new AscGranted(this);
        this.paths = new AscPaths(this);
    }

    /** 昇華模組啟用時：註冊事件、排程、屬性來源。 */
    static void start(final ExileCore plugin) {
        final AscendancyEffects effects = new AscendancyEffects(plugin);
        instance = effects;
        plugin.getServer().getPluginManager().registerEvents(effects, plugin);
        plugin.getServer().getPluginManager().registerEvents(effects.granted, plugin);
        plugin.getServer().getPluginManager().registerEvents(effects.paths, plugin);
        plugin.repeat("ascendancy", effects::tick, PERIOD, PERIOD); // 2026-10-27：計時給 /ec perf 看
        plugin.players().addBuffSource(effects::buffModifiers);
        if (plugin.characters() != null) {
            // 2026-10-27：換角色（登入、建立、切換）時清掉上一個角色的順風、時間之沙、專注擊殺、堤防、時空裂隙…；這次之前只在下線時清
            plugin.characters().addCharacterResetHook(player -> effects.forget(player.getUniqueId()));
        }
    }

    /** 清掉一個玩家的昇華暫時狀態（下線、換角色）。 */
    void forget(final java.util.UUID player) {
        states.remove(player);
        granted.forget(player);
        paths.forget(player);
    }

    /** 模組沒啟用時是 null。 */
    public static AscendancyEffects get() {
        return instance;
    }

    long now() {
        return plugin.getServer().getCurrentTick();
    }

    AscState state(final Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new AscState());
    }

    EnemyState enemy(final LivingEntity entity) {
        final EnemyState e = enemies.computeIfAbsent(entity.getUniqueId(), id -> new EnemyState());
        e.lastSeen = now();
        return e;
    }

    StatSheet sheet(final Player player) {
        return plugin.players().sheet(player);
    }

    static double flat(final StatSheet sheet, final String stat) {
        return sheet == null ? 0 : sheet.flat(stat, Set.of());
    }

    static boolean has(final StatSheet sheet, final String stat) {
        return flat(sheet, stat) != 0;
    }

    /** 怪物強度（PoE2：普通 1、魔法 2、稀有 10、傳奇 20）。 */
    double power(final LivingEntity target) {
        final MonsterData data = target == null || plugin.monsters() == null ? null : plugin.monsters().data(target).orElse(null);
        if (data == null) {
            return 0;
        }
        if (data.boss()) {
            return 20;
        }
        return switch (data.rarity()) {
            case NORMAL -> 1;
            case MAGIC -> 2;
            case RARE -> 10;
            case UNIQUE -> 20;
        };
    }

    boolean enemyOf(final Player player, final LivingEntity target) {
        return !(target instanceof Player) && target.isValid() && !target.isDead() && plugin.monsters() != null
                && plugin.monsters().data(target).isPresent()
                && (plugin.skills() == null || plugin.skills().minions() == null || !plugin.skills().minions().isMinion(target))
                && (plugin.training() == null || plugin.training().canTarget(target, player));
    }

    List<LivingEntity> enemiesNear(final Player player, final Location at, final double radius) {
        final List<LivingEntity> out = new ArrayList<>();
        if (at.getWorld() == null) {
            return out;
        }
        for (final LivingEntity living : at.getWorld().getNearbyLivingEntities(at, radius)) {
            if (enemyOf(player, living) && living.getLocation().distanceSquared(at) <= radius * radius) {
                out.add(living);
            }
        }
        return out;
    }

    static double presence(final StatSheet sheet) {
        return tw.exilecore.combat.Presence.radius(sheet);
    }

    // =====================================================================
    // 放技能（SkillService 呼叫）
    // =====================================================================

    /**
     * 放技能的消耗（SkillService 在扣魔力之前呼叫）。
     *
     * @param mana    要扣的魔力
     * @param flame   改成獲得多少煉獄之炎（火祭協議）
     * @param rage    另外要花幾點怒氣（狂怒源泉）
     * @param problem 不能放的原因（null = 可以）
     */
    public record Cost(int mana, double flame, int rage, int flask, String problem) {
    }

    /** 御風的混合物：每次用掉魔力藥劑幾點充能（照 PoE2 5 點）。 */
    static final int CONCOCTION_CHARGES = 5;
    /** 巨靈的指令要先召喚哪一位。 */
    private static final Map<String, String> DJINN = Map.ofEntries(Map.entry("kelari_brutality", "kelari_djinn"), Map.entry("kelari_deception", "kelari_djinn"), Map.entry("kelari_judgment", "kelari_djinn"),
            Map.entry("kelari_malediction", "kelari_djinn"), Map.entry("navira_oasis", "navira_djinn"), Map.entry("navira_well", "navira_djinn"),
            Map.entry("navira_fracturing", "navira_djinn"), Map.entry("ruzhan_reckoning", "ruzhan_djinn"), Map.entry("ruzhan_fury", "ruzhan_djinn"),
            Map.entry("ruzhan_trap", "ruzhan_djinn"));

    public static Cost cost(final Player player, final PlayerState state, final SkillDefinition definition, final int cost) {
        final AscendancyEffects effects = instance;
        if (effects == null) {
            return new Cost(cost, 0, 0, 0, null);
        }
        final StatSheet sheet = state.sheet();
        double mana = cost;
        if (mana > 0 && has(sheet, AscKeys.NO_ES_COST) && state.energyShield() <= 0) {
            mana *= 1 + flat(sheet, AscKeys.NO_ES_COST) / 100.0; // 晶化護符：沒有能量護盾時更多 50% 魔力消耗
        }
        if (mana > 0) {
            mana *= AscBuild.costScale(effects, player); // 鑲嵌的寶石（藍）：技能消耗更少 30%
        }
        double flame = 0;
        if (has(sheet, AscKeys.INFERNAL) && mana > 0) {
            flame = mana; // 火祭協議：消耗改成獲得煉獄之炎
            mana = 0;
        }
        // 巨靈的指令：要先召喚那位巨靈；2026-10-27 照 PoE2 巨靈是有實體的召喚物，要在你身邊 20 格內（這次之前只看有沒有開著）
        final String djinn = DJINN.get(definition.id());
        if (djinn != null && plugin(effects).skills() != null) {
            final SkillDefinition summon = plugin(effects).skills().config().skill(djinn);
            if (!plugin(effects).skills().auraOn(player, djinn)) {
                return new Cost((int) Math.round(mana), 0, 0, 0, "要先召喚" + (summon == null ? "巨靈" : summon.name()));
            }
            if (plugin(effects).skills().minions() != null && plugin(effects).skills().minions().locate(player, djinn) == null) {
                return new Cost((int) Math.round(mana), 0, 0, 0, (summon == null ? "巨靈" : summon.name()) + "不在你身邊");
            }
        }
        // 御風的混合物：用掉魔力藥劑 5 點充能
        int flask = 0;
        if (definition.id().endsWith("_concoction") && "ascendancy".equals(definition.source())) {
            flask = CONCOCTION_CHARGES;
            if (plugin(effects).flasks() == null || plugin(effects).flasks().charges(player, tw.exilecore.flask.FlaskKind.MANA) < flask) {
                return new Cost((int) Math.round(mana), 0, 0, 0, definition.name() + "要魔力藥劑 " + flask + " 點充能");
            }
        }
        int rage = 0;
        final double rageCost = flat(sheet, AscKeys.RAGE_COST);
        if (rageCost > 0 && definition.mechanic() != tw.exilecore.skill.Mechanic.AURA && !definition.hidden()) {
            rage = (int) Math.round(rageCost / tw.exilecore.stats.CostKeys.divisor(tw.exilecore.stats.CostKeys.otherEfficiency(sheet, definition.tags()))); // 2026-10-27 照 PoE2：怒氣消耗也吃消耗效率
            if (plugin(effects).skills() != null && plugin(effects).skills().resources().rage(player) < rage) {
                return new Cost((int) Math.round(mana), flame, rage, flask, "怒氣不足（狂怒源泉：" + definition.name() + "要 " + rage + " 怒氣）");
            }
        }
        return new Cost((int) Math.round(mana), flame, rage, flask, null);
    }

    private static ExileCore plugin(final AscendancyEffects effects) {
        return effects.plugin;
    }

    /** 扣完消耗之後：煉獄之炎、怒氣。 */
    public static void paid(final Player player, final PlayerState state, final Cost cost) {
        final AscendancyEffects effects = instance;
        if (effects == null || cost == null) {
            return;
        }
        if (cost.rage() > 0 && effects.plugin.skills() != null) {
            effects.plugin.skills().resources().spendRage(player, cost.rage());
        }
        if (cost.flame() > 0) {
            effects.gainFlame(player, state, cost.flame());
        }
        if (cost.flask() > 0 && effects.plugin.flasks() != null) {
            effects.plugin.flasks().spendCharges(player, tw.exilecore.flask.FlaskKind.MANA, cost.flask());
        }
    }

    /** 翻滾時（SkillTriggers 呼叫）：虛空幻影。 */
    public static void dodge(final Player player) {
        final AscendancyEffects effects = instance;
        if (effects != null) {
            effects.granted.dodge(player);
            effects.paths.dodge(player); // 2026-10-27 照 PoE2：原始賞金
        }
    }

    /** 技能花了生命（血紅之潮：每花 2% 最大生命，生命藥劑 +1 充能）。 */
    public static void lifeSpent(final Player player, final double amount) {
        final AscendancyEffects effects = instance;
        if (effects == null || amount <= 0 || !has(effects.sheet(player), AscKeys.SANGUINE) || effects.plugin.flasks() == null) {
            return;
        }
        final PlayerState state = effects.plugin.players().get(player);
        final AscState s = effects.state(player);
        s.lifeSpentCarry += amount / Math.max(1, state.maxLife()) * 100 / 2;
        final int charges = (int) Math.floor(s.lifeSpentCarry);
        if (charges > 0) {
            s.lifeSpentCarry -= charges;
            effects.plugin.flasks().addCharges(player, charges, tw.exilecore.flask.FlaskKind.LIFE);
        }
    }

    /**
     * 真的要放了（扣完消耗、算完戰星的強化）：回傳這一下要用的 build（先祖強化、力量的代價、異能賦予），
     * 順便處理順風、猛擊的餘震、回響與重複、元素執政官、賦予的技能自己的效果。
     */
    public static SkillBuild beforeCast(final Player player, final PlayerState state, final SkillBuild build, final LivingEntity target) {
        final AscendancyEffects effects = instance;
        if (effects == null || build == null) {
            return build;
        }
        try {
            return effects.cast(player, state, build, target);
        } catch (final RuntimeException e) {
            effects.plugin.getSLF4JLogger().error("昇華效果（放技能）出錯", e);
            return build;
        }
    }

    private SkillBuild cast(final Player player, final PlayerState state, final SkillBuild original, final LivingEntity target) {
        SkillBuild build = original;
        final StatSheet sheet = state.sheet();
        final SkillDefinition definition = build.definition();
        final Set<String> tags = build.profile().tags();
        final AscState s = state(player);
        final List<Modifier> local = new ArrayList<>();
        // 集風：使用技能時獲得順風（最多 10 層）
        if (has(sheet, AscKeys.TAILWIND) && s.tailwind < TAILWIND_MAX && definition.mechanic() != tw.exilecore.skill.Mechanic.AURA) {
            s.tailwind++;
            plugin.players().markDirty(player);
        }
        // 砲彈準備好了！：發射攻擊投射物時，圖騰才使用技能
        if (tags.contains(Tags.ATTACK) && tags.contains(Tags.PROJECTILE)) {
            s.cannonsFired++;
        }
        final boolean self = build.deploy() == null && definition.mechanic() != tw.exilecore.skill.Mechanic.SUMMON
                && definition.mechanic() != tw.exilecore.skill.Mechanic.AURA;
        // 猛擊（親自使用的）：先祖威能、天崩地裂、劈山碎石
        if (self && tags.contains(Tags.SLAM)) {
            s.slams++;
            // 先祖威能（每兩次猛擊先祖強化一次）：跟天賦樹同一個（flag.ancestral_every_other_slam，TreeMechanics）
        }
        // 先祖強化的打擊：多打 2 個目標（先祖威能只有猛擊；這裡留給圖騰的英靈不處理）
        // 力量的代價：法術消耗 1 顆銳星，更多 40% 傷害
        final double price = flat(sheet, AscKeys.PRICE_OF_POWER);
        if (price > 0 && tags.contains(Tags.SPELL) && plugin.combat() != null && plugin.combat().charges() != null
                && plugin.combat().charges().spend(player, "power", null, tags) > 0) {
            local.add(Modifier.more(StatKeys.DAMAGE, price / 100.0, "力量的代價"));
        }
        // 異能賦予：施放法術時犧牲 5% 最大能量護盾；完全支付的法術更多 30% 傷害
        final double eldritch = flat(sheet, AscKeys.ELDRITCH);
        if (eldritch > 0 && tags.contains(Tags.SPELL)) {
            final double need = state.maxEnergyShield() * eldritch / 100.0;
            if (need > 0) {
                final boolean full = state.energyShield() >= need - 1e-6;
                state.setEnergyShield(Math.max(0, state.energyShield() - need)); // 犧牲不會中斷回充（不改最後受傷時間）
                if (full) {
                    local.add(Modifier.more(StatKeys.DAMAGE, ELDRITCH_MORE, "異能賦予"));
                }
                plugin.players().display(player, state);
            }
        }
        // 沸騰軀體：高煉獄之炎時施放法術獲得元素執政官
        if (has(sheet, AscKeys.SEETHING) && tags.contains(Tags.SPELL) && s.infernalMax > 0 && s.infernal >= s.infernalMax * HIGH_INFERNAL) {
            gainArchon(player, s);
        }
        // 元素執政官：使用攻擊時移除（TreeMechanics；高煉獄之炎的沸騰軀體例外，見 archonHeld）
        if (!local.isEmpty()) {
            build = build.withProfile(build.profile().withLocal(local));
        }
        if (self && tags.contains(Tags.SLAM)) {
            slamAftershocks(player, sheet, s, build, target);
        }
        // 戰爭之哮：戰吼引爆屍體，造成屍體最大生命 25% 的物理傷害
        final double corpses = flat(sheet, AscKeys.WARCRY_CORPSES);
        if (corpses > 0 && definition.warcry() != null && definition.warcry().cry() && plugin.combat() != null) {
            warcryCorpses(player, definition.warcry().radius(), corpses);
        }
        echoOrRepeat(player, sheet, build, target);
        final List<Modifier> extra = granted.used(player, state, build, target);
        // 2026-10-27 照 PoE2：靈現奔竄、原始賞金、山巒之教、空無之鐘、深淵幻影（AscPaths）
        return paths.used(player, state, extra.isEmpty() ? build : build.withProfile(build.profile().withLocal(extra)), target);
    }

    /** 天崩地裂、劈山碎石：猛擊的餘震（照原本那一下的傷害、同一個範圍）。 */
    private void slamAftershocks(final Player player, final StatSheet sheet, final AscState s, final SkillBuild build, final LivingEntity target) {
        if (plugin.skills() == null) {
            return;
        }
        final org.bukkit.util.Vector facing = player.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) {
            facing.setX(1);
        }
        facing.normalize();
        final Location center = target != null && target.getWorld() == player.getWorld()
                && target.getLocation().distanceSquared(player.getLocation()) < 64 ? target.getLocation()
                : player.getLocation().add(facing.clone().multiply(2.5));
        final double chance = flat(sheet, AscKeys.SLAM_AFTERSHOCK);
        if (chance > 0 && plugin.random().nextDouble() * 100 < chance) {
            later(10, () -> plugin.skills().castWithParent(player, "asc_aftershock", build, 1.0, center, facing));
        }
        if (has(sheet, AscKeys.MOUNTAIN_SPLITTER) && s.slams % 3 == 0 && !build.definition().tags().contains("fissure")) {
            final org.bukkit.util.Vector side = new org.bukkit.util.Vector(-facing.getZ(), 0, facing.getX());
            final double step = 3.0;
            for (int i = 1; i <= 3; i++) {
                final int n = i;
                later(6L * i, () -> {
                    plugin.skills().castWithParent(player, "asc_aftershock", build, 1.0, center.clone().add(facing.clone().multiply(step * n)), facing);
                    plugin.skills().castWithParent(player, "asc_aftershock", build, 1.0, center.clone().add(side.clone().multiply(step * n)), facing);
                    plugin.skills().castWithParent(player, "asc_aftershock", build, 1.0, center.clone().add(side.clone().multiply(-step * n)), facing);
                });
            }
        }
    }

    private void warcryCorpses(final Player player, final double radius, final double percent) {
        final long now = now();
        int count = 0;
        for (final tw.exilecore.combat.Corpses.Corpse corpse : plugin.combat().corpses().near(player.getWorld().getUID(),
                player.getLocation().getX(), player.getLocation().getZ(), radius, now)) {
            if (count++ >= 20) {
                break;
            }
            plugin.combat().corpses().remove(corpse);
            final Location at = new Location(player.getWorld(), corpse.x(), corpse.y(), corpse.z());
            final SkillProfile profile = new SkillProfile("asc_corpse_burst", Set.of(Tags.AREA, Tags.PHYSICAL, Tags.WARCRY), 0,
                    new DamagePacket().set(DamageType.PHYSICAL, corpse.maxLife() * percent / 100.0));
            player.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0, 0.5, 0), 20, 0.4, 0.4, 0.4, 0,
                    org.bukkit.Material.BONE_BLOCK.createBlockData());
            for (final LivingEntity enemy : enemiesNear(player, at, 2.5)) {
                plugin.combat().skillDamage().hit(player, enemy, profile, null);
            }
        }
    }

    /** 頻繁暴雨：元素技能（地面效果）的上限多幾個。 */
    public static int limitBonus(final Player owner, final Set<String> tags) {
        final AscendancyEffects effects = instance;
        if (effects == null || owner == null || tags == null
                || !(tags.contains(Tags.FIRE) || tags.contains(Tags.COLD) || tags.contains(Tags.LIGHTNING))) {
            return 0;
        }
        return (int) flat(effects.sheet(owner), AscKeys.ELEMENTAL_LIMIT);
    }

    /** 循環往復：可層疊法術 20% 機率回響（同一個地方再一次）、可重複法術 20% 機率重複。 */
    private void echoOrRepeat(final Player player, final StatSheet sheet, final SkillBuild build, final LivingEntity target) {
        if (plugin.skills() == null || !build.profile().tags().contains(Tags.SPELL) || build.deploy() != null
                || build.definition().mechanic() == tw.exilecore.skill.Mechanic.AURA
                || build.definition().mechanic() == tw.exilecore.skill.Mechanic.SUMMON
                || build.definition().mechanic() == tw.exilecore.skill.Mechanic.WARCRY
                || build.definition().tags().contains("channelling")) {
            return;
        }
        final tw.exilecore.skill.Mechanic mechanic = build.definition().mechanic();
        final boolean cascadable = mechanic == tw.exilecore.skill.Mechanic.NOVA || mechanic == tw.exilecore.skill.Mechanic.IMPACT
                || mechanic == tw.exilecore.skill.Mechanic.ZONE || mechanic == tw.exilecore.skill.Mechanic.CONE;
        final boolean repeatable = mechanic == tw.exilecore.skill.Mechanic.PROJECTILE || mechanic == tw.exilecore.skill.Mechanic.CHAIN;
        final double echo = flat(sheet, AscKeys.ECHO);
        final double repeat = flat(sheet, AscKeys.REPEAT);
        final Location at = player.getLocation().clone();
        final org.bukkit.util.Vector facing = player.getLocation().getDirection();
        if (repeatable && repeat > 0 && plugin.random().nextDouble() * 100 < repeat) {
            later(5, () -> plugin.skills().recast(player, build, player.getLocation(), player.getLocation().getDirection(), target));
        } else if (cascadable && echo > 0 && plugin.random().nextDouble() * 100 < echo) {
            later(12, () -> plugin.skills().recast(player, build, at, facing, target));
        }
    }

    void later(final long ticks, final Runnable task) {
        plugin.getServer().getScheduler().runTaskLater(plugin, task, Math.max(1, ticks));
    }

    // =====================================================================
    // 煉獄之炎（火祭協議）、元素執政官（沸騰軀體）
    // =====================================================================

    void gainFlame(final Player player, final PlayerState state, final double amount) {
        final AscState s = state(player);
        if (s.infernalMax <= 0) {
            return;
        }
        s.infernal += amount;
        s.infernalGained = now();
        if (s.infernal >= s.infernalMax - 1e-6) {
            // 達到上限：承受等同最大生命與能量護盾的火焰傷害，失去全部煉獄之炎與元素執政官
            s.infernal = 0;
            if (has(sheet(player), AscKeys.SEETHING) && plugin.skills() != null && plugin.skills().treeMechanics() != null) {
                plugin.skills().treeMechanics().endArchon(player);
            }
            selfHit(player, state, new DamagePacket().set(DamageType.FIRE, state.maxLife() + state.maxEnergyShield()), "煉獄之炎");
            player.getWorld().spawnParticle(Particle.FLAME, player.getLocation().add(0, 1, 0), 60, 0.5, 0.8, 0.5, 0.1);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BLAZE_DEATH, 0.8f, 0.7f);
            plugin.players().markDirty(player);
        }
        state.notice(String.format(java.util.Locale.ROOT, "<#ff7a3a>煉獄之炎 %d／%d", Math.round(s.infernal), Math.round(s.infernalMax)), now() + 30);
        plugin.players().display(player, state);
    }

    void gainArchon(final Player player, final AscState s) {
        final long now = now();
        // 2026-10-26：跟天賦樹同一個元素統治者（TreeMechanics：持續、恢復期、效果都照那邊）
        if (plugin.skills() != null && plugin.skills().treeMechanics() != null
                && plugin.skills().treeMechanics().gainArchon(player, tw.exilecore.skill.TreeMechanics.Archon.ELEMENTAL)) {
            player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.05);
        }
    }

    /** 2026-10-26：沸騰軀體——高煉獄之炎時元素統治者不會因為使用攻擊而結束（TreeMechanics 問這裡）。 */
    public static boolean archonHeld(final Player player) {
        final AscendancyEffects effects = instance;
        if (effects == null) {
            return false;
        }
        final AscState s = effects.states.get(player.getUniqueId());
        return s != null && has(effects.sheet(player), AscKeys.SEETHING) && s.infernalMax > 0 && s.infernal >= s.infernalMax * HIGH_INFERNAL;
    }

    /** 玩家自己承受一下（煉獄之炎、易爆）：照抗性、護甲減免，先扣護盾再扣生命；扣到 0 就死。 */
    void selfHit(final Player player, final PlayerState state, final DamagePacket raw, final String cause) {
        final DamagePacket dealt = tw.exilecore.stats.DamageCalculator.mitigate(raw, state.sheet());
        double amount = dealt.total();
        if (amount <= 0) {
            return;
        }
        final double fromEs = Math.min(state.energyShield(), amount);
        state.setEnergyShield(state.energyShield() - fromEs);
        amount -= fromEs;
        state.setLife(state.life() - amount);
        state.setLastDamageTick(now());
        if (state.life() <= 0) {
            player.setHealth(0);
        } else {
            plugin.players().display(player, state);
        }
    }

    // =====================================================================
    // 戰星、灌注、增益（ChargeService、SkillResources、SkillBuffs 呼叫）
    // =====================================================================

    /** 消耗一顆戰星：最近消耗過銳星（元素湧動在 SkillResources 的 surge.*）。 */
    public static void chargeSpent(final Player player, final String chargeId) {
        final AscendancyEffects effects = instance;
        if (effects == null) {
            return;
        }
        final StatSheet sheet = effects.sheet(player);
        if ("power".equals(chargeId) && has(sheet, AscKeys.ES_POWER)) {
            effects.plugin.players().markDirty(player);
        }
    }

    /** 驚險追逐：消耗怒星的效益 50% 機率加倍。 */
    public static boolean doubleFrenzy(final Player player) {
        final AscendancyEffects effects = instance;
        if (effects == null) {
            return false;
        }
        final double chance = flat(effects.sheet(player), AscKeys.FRENZY_DOUBLE);
        return chance > 0 && effects.plugin.random().nextDouble() * 100 < chance;
    }

    /** 增益效果失效速度：身上的增益持續久一點（減慢 x% = 持續時間 ÷ (1 - x%)）。 */
    public static int buffTicks(final Player player, final int ticks) {
        final AscendancyEffects effects = instance;
        if (effects == null) {
            return ticks;
        }
        final double slower = Math.min(90, flat(effects.sheet(player), AscKeys.BUFF_EXPIRY));
        return slower <= 0 ? ticks : (int) Math.round(ticks / (1 - slower / 100.0));
    }

    // =====================================================================
    // 圖騰（SkillDeployables 呼叫）
    // =====================================================================

    /** 無名心木：圖騰生命降到 0 之後幾 tick 才死（沒有 = 0）。 */
    public static long totemLingerTicks(final Player owner) {
        final AscendancyEffects effects = instance;
        return effects == null || owner == null ? 0 : Math.round(flat(effects.sheet(owner), AscKeys.TOTEM_LINGER) * 20);
    }

    /** 砲彈準備好了！：這個玩家發射過幾次攻擊投射物（沒有這個天賦 = -1）。 */
    public static long cannonsFired(final Player owner) {
        final AscendancyEffects effects = instance;
        if (effects == null || owner == null || !has(effects.sheet(owner), AscKeys.CANNONS_READY)) {
            return -1;
        }
        return effects.state(owner).cannonsFired;
    }

    /** 放下圖騰時：先祖之引觸發先祖之魂。 */
    public static void totemPlaced(final Player owner, final Location at) {
        final AscendancyEffects effects = instance;
        if (effects == null || !has(effects.sheet(owner), AscKeys.ANCESTRAL_SPIRITS) || effects.plugin.skills() == null) {
            return;
        }
        effects.plugin.skills().triggerSkill(owner, "ancestral_spirits", at);
    }

    // =====================================================================
    // 受到擊中（CombatListener 呼叫）
    // =====================================================================

    /** 防守方（玩家）這一下的屬性表：神聖儀式（當前能量護盾的 60% 加到護甲）、元素傷害更少（反應性生長、適應）。 */
    public static StatSheet defenderSheet(final Player defender, final PlayerState state, final StatSheet sheet) {
        final AscendancyEffects effects = instance;
        if (effects == null || state == null) {
            return sheet;
        }
        final List<Modifier> extra = new ArrayList<>();
        final double esArmour = flat(sheet, AscKeys.ES_ARMOUR);
        if (esArmour > 0 && state.energyShield() > 0) {
            extra.add(Modifier.flat(StatKeys.ARMOUR, state.energyShield() * esArmour / 100.0, "神聖儀式"));
        }
        final double less = flat(sheet, AscKeys.ELEMENTAL_TAKEN_LESS);
        final double adapt = flat(sheet, AscKeys.ADAPT);
        if (less > 0 || adapt > 0) {
            final AscState s = effects.state(defender);
            final double each = adapt * (has(sheet, AscKeys.ADAPT_DOUBLE) ? 2 : 1);
            final long now = effects.now();
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                double more = 1 - less / 100.0;
                final Long until = s.adaptations.get(type);
                if (each > 0 && until != null && until > now) {
                    more *= 1 - each / 100.0;
                }
                if (more < 1) {
                    extra.add(Modifier.flat(StatKeys.DEBUFF_TAKEN + type.tag(), (more - 1) * 100, "適應"));
                }
            }
        }
        return extra.isEmpty() ? sheet : sheet.overlay(extra);
    }

    /** 敵人打玩家的這一下（攻擊方的表）：保持警覺，善用掩護——敵人依距離對你有命中懲罰。 */
    public static StatSheet enemyVsPlayer(final Player defender, final LivingEntity attacker, final StatSheet attackerSheet) {
        final AscendancyEffects effects = instance;
        if (effects == null || attacker == null || !has(effects.sheet(defender), AscKeys.ENEMY_ACCURACY_FALLOFF)
                || attacker.getWorld() != defender.getWorld()) {
            return attackerSheet;
        }
        final double falloff = tw.exilecore.stats.Formulas.accuracyFalloff(
                Math.max(0, attacker.getLocation().distance(defender.getLocation()) - defender.getWidth() / 2));
        return falloff > 0 ? attackerSheet.overlay(List.of(Modifier.more(StatKeys.ACCURACY, -falloff, "保持警覺"))) : attackerSheet;
    }

    /** 承受的傷害視為另一種（DamageCalculator.mitigate 呼叫；asc.taken.來源.變成 = x%）。 */
    public static DamagePacket reroute(final DamagePacket incoming, final StatSheet defender) {
        if (instance == null || defender == null) {
            return incoming;
        }
        DamagePacket out = incoming;
        for (final DamageType from : DamageType.values()) {
            final double amount = incoming.get(from);
            if (amount <= 0) {
                continue;
            }
            double moved = 0;
            for (final DamageType to : DamageType.values()) {
                if (to == from) {
                    continue;
                }
                final double percent = defender.flat(AscKeys.TAKEN_AS + from.tag() + "." + to.tag(), Set.of());
                if (percent > 0) {
                    if (out == incoming) {
                        out = new DamagePacket(incoming);
                    }
                    final double part = amount * Math.min(100 - moved, percent) / 100.0;
                    moved += Math.min(100 - moved, percent);
                    out.add(to, part);
                    out.add(from, -part);
                }
            }
        }
        return out;
    }

    /**
     * 擊中扣生命、護盾之前（CombatListener.applyToPlayer）：回傳 {護盾, 生命, 另外扣的魔力}。
     * 黑暗（先保留黑暗）、堅木之壘（圖騰先扣）、無魂型態（10% 無視護盾）、瓦拉什塔的直覺（元素傷害先扣魔力）、心宇和諧（魔力比生命多時先扣魔力）。
     */
    public static double[] absorb(final Player player, final PlayerState state, final StatSheet sheet, final double es, final double life,
                                  final DamagePacket dealt) {
        final AscendancyEffects effects = instance;
        if (effects == null) {
            return new double[] {es, life, 0};
        }
        try {
            return effects.absorbInner(player, state, sheet, es, life, dealt);
        } catch (final RuntimeException e) {
            effects.plugin.getSLF4JLogger().error("昇華效果（受到擊中）出錯", e);
            return new double[] {es, life, 0};
        }
    }

    private double[] absorbInner(final Player player, final PlayerState state, final StatSheet sheet, final double esIn, final double lifeIn,
                                 final DamagePacket dealt) {
        double es = esIn;
        double life = lifeIn;
        double mana = 0;
        final AscState s = state(player);
        final long now = now();
        // 適應：照這一下最高的元素傷害（最多 3 個，演化化身：持續 5 秒）
        if (has(sheet, AscKeys.ADAPT) && dealt != null) {
            DamageType best = null;
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                if (dealt.get(type) > 0 && (best == null || dealt.get(type) > dealt.get(best))) {
                    best = type;
                }
            }
            if (best != null) {
                final double seconds = flat(sheet, AscKeys.ADAPT_DURATION);
                s.adaptations.put(best, seconds > 0 ? now + Math.round(seconds * 20) : Long.MAX_VALUE);
                s.adaptations.values().removeIf(until -> until <= now);
                while (s.adaptations.size() > ADAPTATIONS_MAX) {
                    DamageType oldest = null;
                    for (final Map.Entry<DamageType, Long> entry : s.adaptations.entrySet()) {
                        if (entry.getKey() != best && (oldest == null || entry.getValue() < s.adaptations.get(oldest))) {
                            oldest = entry.getKey();
                        }
                    }
                    s.adaptations.remove(oldest);
                }
            }
        }
        // 集風：被擊中時失去全部順風
        if (s.tailwind > 0 && has(sheet, AscKeys.TAILWIND)) {
            s.tailwind = 0;
            plugin.players().markDirty(player);
        }
        double total = es + life;
        if (total <= 0) {
            return new double[] {es, life, 0};
        }
        // 巫術護盾：屏障先吃擊中的元素傷害（儀式淨身：全部傷害）；高尚禦盾：被擊中時失去一顆寶珠
        final double warded = granted.ward(player, state, sheet, total, dealt);
        if (warded > 0) {
            final double scale = (total - warded) / total;
            es *= scale;
            life *= scale;
            total -= warded;
        }
        // 黑暗：承受的傷害先從黑暗保留（保留 5 秒後回來）
        final double darkMax = darknessMax(player, state, sheet);
        if (darkMax > 0) {
            double reserved = 0;
            for (final double[] r : s.darkness) {
                reserved += r[0];
            }
            final double free = Math.max(0, darkMax - reserved);
            final double take = Math.min(free, total);
            if (take > 0) {
                final double seconds = Math.max(0.1, flat(sheet, AscKeys.DARKNESS_SECONDS) > 0 ? flat(sheet, AscKeys.DARKNESS_SECONDS) : 5);
                s.darkness.add(new double[] {take, now + Math.round(seconds * 20)});
                final double scale = (total - take) / total;
                es *= scale;
                life *= scale;
                total -= take;
            }
        }
        // 堅木之壘：擊中的傷害有 20% 先從最近的圖騰扣
        final double wall = flat(sheet, AscKeys.WOODEN_WALL);
        if (wall > 0 && total > 0 && plugin.skills() != null) {
            final double share = total * wall / 100.0;
            final double taken = plugin.skills().drainTotem(player.getUniqueId(), player.getLocation(), share);
            if (taken > 0) {
                final double scale = (total - taken) / total;
                es *= scale;
                life *= scale;
                total -= taken;
            }
        }
        // 2026-10-27 照 PoE2：荒野擁抱（熊靈先承受 8%）、山巒之教（小擊中更少 40%、被擊中失去 1 層）
        final double[] path = paths.absorb(player, state, es, life);
        es = path[0];
        life = path[1];
        // 無魂型態：承受的傷害有 10% 無視能量護盾（永生：生命不會變，這一份改由護盾承受）
        final double bypass = flat(sheet, AscKeys.ES_BYPASS);
        if (bypass > 0 && es > 0 && !(has(sheet, AscKeys.ETERNAL_LIFE) && state.energyShield() > 0)) {
            final double moved = Math.min(es, (es + life) * bypass / 100.0);
            es -= moved;
            life += moved;
        }
        // 瓦拉什塔的直覺：元素傷害先扣魔力
        final double elementalFromMana = flat(sheet, AscKeys.ELEMENTAL_FROM_MANA);
        if (elementalFromMana > 0 && life > 0 && dealt != null && dealt.total() > 0) {
            final double elemental = (dealt.get(DamageType.FIRE) + dealt.get(DamageType.COLD) + dealt.get(DamageType.LIGHTNING)) / dealt.total();
            final double want = life * elemental * Math.min(100, elementalFromMana) / 100.0;
            final double paid = Math.min(Math.max(0, state.mana() - mana), want);
            mana += paid;
            life -= paid;
        }
        // 心宇和諧：當前魔力高於當前生命時，擊中傷害先從魔力扣
        if (has(sheet, AscKeys.HARMONY) && life > 0 && state.mana() - mana > state.life()) {
            final double paid = Math.min(state.mana() - mana, life);
            mana += paid;
            life -= paid;
        }
        // 2026-10-26 照 PoE2 天賦樹（觀星專屬）：有 x% 機率避免被擊中致死（剩 1 點生命）；這次之前沒有
        final double avoid = flat(sheet, AscKeys.AVOID_DEATH);
        if (avoid > 0 && life >= state.life() && state.life() > 1 && plugin.random().nextDouble() * 100 < avoid) {
            life = state.life() - 1;
        }
        return new double[] {es, life, mana};
    }

    /** 最大黑暗（靈魂昇華：基礎 100 ＋ 每等級 10，吃增加；加深陰影：每 1% 混沌抗性增加 1%）。 */
    double darknessMax(final Player player, final PlayerState state, final StatSheet sheet) {
        final double base = flat(sheet, AscKeys.DARKNESS) + flat(sheet, AscKeys.DARKNESS_PER_LEVEL) * state.level();
        if (base <= 0) {
            return 0;
        }
        double increased = sheet.increased(AscKeys.DARKNESS, Set.of());
        final double perChaos = flat(sheet, AscKeys.DARKNESS_PER_CHAOS);
        if (perChaos > 0) {
            increased += Math.max(0, sheet.get(StatKeys.RESIST_CHAOS)) * perChaos / 100.0;
        }
        return base * Math.max(0, 1 + increased);
    }

    // =====================================================================
    // 打出去（CombatListener 呼叫）
    // =====================================================================

    /**
     * 2026-10-27：一直生效、但戰鬥裡擊中時才疊上的昇華加成，換成面板／技能每秒傷害／網頁估計用的等效值（不看目標）：
     * 季節循環（10% 傷害為隨機一種元素的額外傷害）＝火、冰、電各 1/3 的期望值；穿透（使用武器的攻擊附加物理傷害＝武器命中值的 25%）。
     * 戰鬥照舊在 {@link #attackerConditional} 算（每下隨機一種元素）。這次之前面板、技能每秒傷害、網頁都沒算這兩個。純函式（測試用）。
     */
    public static List<Modifier> alwaysOnEstimate(final StatSheet sheet) {
        final List<Modifier> out = new ArrayList<>();
        final double random = flat(sheet, AscKeys.RANDOM_ELEMENT);
        if (random > 0) {
            for (final String element : new String[] {Tags.FIRE, Tags.COLD, Tags.LIGHTNING}) {
                out.add(Modifier.flat(StatKeys.DAMAGE_EXTRA + element, random / 3.0, "季節循環（期望值）"));
            }
        }
        final double accuracy = flat(sheet, AscKeys.ACCURACY_DAMAGE);
        final double weaponAccuracy = sheet == null ? 0 : sheet.base(AscBuild.WEAPON_ACCURACY);
        if (accuracy > 0 && weaponAccuracy > 0) {
            final double added = weaponAccuracy * accuracy / 100.0;
            out.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, added, Set.of(Tags.PHYSICAL, Tags.ATTACK), "穿透"));
            out.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, added, Set.of(Tags.PHYSICAL, Tags.ATTACK), "穿透"));
        }
        // 2026-10-27：異能賦予（放法術時犧牲 x% 最大能量護盾，付得起的法術更多 30%，{@link #cast}）照付得起估；這次之前面板、網頁沒算
        if (flat(sheet, AscKeys.ELDRITCH) > 0 && tw.exilecore.character.PlayerState.maxEnergyShield(sheet) > 0) {
            out.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, ELDRITCH_MORE, Set.of(Tags.SPELL), "異能賦予"));
        }
        return out;
    }

    /** 異能賦予：完全支付的法術更多 30% 傷害。 */
    static final double ELDRITCH_MORE = 0.30;

    /** 玩家打的這一下的條件加成。 */
    public static List<Modifier> attackerConditional(final Player attacker, final LivingEntity defender, final MonsterData monster,
                                                     final StatSheet skillSheet, final Set<String> hitTags, final SkillProfile skill) {
        final AscendancyEffects effects = instance;
        if (effects == null) {
            return List.of();
        }
        try {
            return effects.conditional(attacker, defender, monster, skillSheet, hitTags, skill);
        } catch (final RuntimeException e) {
            effects.plugin.getSLF4JLogger().error("昇華效果（擊中加成）出錯", e);
            return List.of();
        }
    }

    private List<Modifier> conditional(final Player attacker, final LivingEntity defender, final MonsterData monster, final StatSheet sheet,
                                       final Set<String> hitTags, final SkillProfile skill) {
        final List<Modifier> out = new ArrayList<>();
        final long now = now();
        final AscState s = state(attacker);
        // 獵食直覺：對有開放弱點的敵人更多 50% 傷害
        final double weakness = flat(sheet, AscKeys.WEAKNESS);
        if (weakness > 0 && monster != null && openWeakness(defender)) {
            out.add(Modifier.more(StatKeys.DAMAGE, weakness / 100.0, "開放弱點"));
        }
        // 毫不留情：依敵人損失的專注最多更多 40%
        final double noMercy = flat(sheet, AscKeys.NO_MERCY);
        if (noMercy > 0 && monster != null) {
            final EnemyState e = enemies.get(defender.getUniqueId());
            if (e != null && e.concentrationMax > 0 && e.concentration >= 0) {
                final double lost = 1 - Math.max(0, e.concentration) / e.concentrationMax;
                if (lost > 0) {
                    out.add(Modifier.more(StatKeys.DAMAGE, noMercy / 100.0 * lost, "毫不留情"));
                }
            }
        }
        // 季節循環：獲得 10% 傷害為隨機一種元素的額外傷害
        final double random = flat(sheet, AscKeys.RANDOM_ELEMENT);
        if (random > 0) {
            final String element = new String[] {Tags.FIRE, Tags.COLD, Tags.LIGHTNING}[plugin.random().nextInt(3)];
            out.add(Modifier.flat(StatKeys.DAMAGE_EXTRA + element, random, "季節循環"));
        }
        // 腐化生命力：對受血液沸騰影響的敵人更多 15%
        final double boil = flat(sheet, AscKeys.BLOOD_BOIL_MORE);
        if (boil > 0 && granted.boiling(defender)) {
            out.add(Modifier.more(StatKeys.DAMAGE, boil / 100.0, "血液沸騰"));
        }
        // 穿透：使用武器的攻擊附加物理傷害 = 武器命中值的 25%
        final double accuracy = flat(sheet, AscKeys.ACCURACY_DAMAGE);
        if (accuracy > 0 && skill.isAttack() && !skill.tags().contains(Tags.MINION)) {
            final double weaponAccuracy = AscBuild.weaponAccuracy(plugin, attacker);
            if (weaponAccuracy > 0) {
                out.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, weaponAccuracy * accuracy / 100.0, Set.of(Tags.PHYSICAL, Tags.ATTACK), "穿透"));
                out.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, weaponAccuracy * accuracy / 100.0, Set.of(Tags.PHYSICAL, Tags.ATTACK), "穿透"));
            }
        }
        // 碎猛之擊：護甲破壞到 0 以下（物理傷害更多，照負的護甲）
        if (has(sheet, AscKeys.NEGATIVE_ARMOUR) && monster != null) {
            final EnemyState e = enemies.get(defender.getUniqueId());
            if (e != null && e.negativeArmour > 0 && e.negativeUntil > now) {
                final double physical = tw.exilecore.stats.DamageCalculator.expectedOutgoing(sheet, skill).get(DamageType.PHYSICAL);
                if (physical > 0) {
                    final double more = Math.min(0.9, e.negativeArmour / (e.negativeArmour + 10 * physical));
                    out.add(new Modifier(StatKeys.DAMAGE, ModType.MORE, more, Set.of(Tags.PHYSICAL), "護甲低於 0"));
                }
            }
        }
        // 血紅之潮：每消耗 1 充能，獲得 1% 傷害為物理傷害 5 秒
        if (s.sanguineDamage > 0 && s.sanguineUntil > now) {
            out.add(Modifier.flat(StatKeys.DAMAGE_EXTRA + Tags.PHYSICAL, s.sanguineDamage, "血紅之潮"));
        }
        // 堤防光環（攻城弩車）：對被禁錮的敵人更多 25% 傷害
        if ((s.lastEmbankment & 2) != 0 && plugin.ailments() != null
                && (plugin.ailments().isIncapacitated(defender) || plugin.ailments().pinned(defender))) {
            out.add(Modifier.more(StatKeys.DAMAGE, 0.25, "堤防光環"));
        }
        out.addAll(granted.conditional(attacker, defender, sheet, skill));
        out.addAll(paths.conditional(attacker, sheet, skill)); // 2026-10-27：莫里根的指引
        return out;
    }

    /** 這隻敵人現在有沒有開放弱點（生命在揭露的區段內）。 */
    boolean openWeakness(final LivingEntity defender) {
        final EnemyState e = enemies.get(defender.getUniqueId());
        if (e == null || e.weakness == null) {
            return false;
        }
        final org.bukkit.attribute.AttributeInstance max = defender.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (max == null || max.getValue() <= 0) {
            return false;
        }
        final double fraction = defender.getHealth() / max.getValue();
        for (final double[] segment : e.weakness) {
            if (fraction >= segment[0] && fraction <= segment[1]) {
                return true;
            }
        }
        return false;
    }

    /** 揭露弱點：45% 的生命，分成三段隨機的區段。 */
    void reveal(final LivingEntity defender) {
        final EnemyState e = enemy(defender);
        if (e.weakness != null) {
            return;
        }
        final double[][] segments = new double[3][];
        final double each = 0.15;
        final double[] starts = new double[3];
        for (int i = 0; i < 3; i++) {
            // 每段落在 1/3 的範圍裡（不重疊）
            starts[i] = i / 3.0 + plugin.random().nextDouble() * (1 / 3.0 - each);
            segments[i] = new double[] {starts[i], starts[i] + each};
        }
        e.weakness = segments;
        defender.getWorld().spawnParticle(Particle.CRIT, defender.getLocation().add(0, defender.getHeight(), 0), 12, 0.3, 0.2, 0.3, 0.05);
    }

    // =====================================================================
    // 異常（SkillTriggers.ailment 呼叫：造成異常時）
    // =====================================================================

    public static void ailment(final Player player, final LivingEntity target, final AilmentType type) {
        final AscendancyEffects effects = instance;
        if (effects == null || player == null || target == null) {
            return;
        }
        final StatSheet sheet = effects.sheet(player);
        final boolean elemental = type == AilmentType.IGNITE || type == AilmentType.CHILL || type == AilmentType.FREEZE
                || type == AilmentType.SHOCK || type == AilmentType.ELECTROCUTE;
        if (type == AilmentType.SHOCK) {
            final double chance = flat(sheet, AscKeys.SHOCKED_GROUND);
            if (chance > 0 && effects.plugin.random().nextDouble() * 100 < chance && effects.plugin.skills() != null) {
                effects.plugin.skills().triggerSkill(player, "asc_shocked_ground", target.getLocation());
            }
        }
        if (type == AilmentType.FREEZE && has(sheet, AscKeys.CHILLED_GROUND) && effects.plugin.skills() != null) {
            effects.plugin.skills().triggerSkill(player, "asc_chilled_ground", target.getLocation());
        }
        effects.paths.ailment(player, target, type); // 2026-10-27：山巒之道（凍結、電擊算禁錮）
    }

    // ---- 2026-10-27 照 PoE2 0.5 補上的三個昇華：別的模組呼叫的入口（模組沒啟用時什麼都不做）----

    /** 武術宗師：所有攻擊擊中都會累積連擊（SkillResources 呼叫）。 */
    public static boolean comboFromAnyAttack(final Player player) {
        final AscendancyEffects effects = instance;
        return effects != null && has(effects.sheet(player), AscKeys.COMBO_ANY_ATTACK);
    }

    /** 武術精通：用掉 spent 層連擊，目前的能量護盾回充延遲每層 -0.2 秒（SkillResources 呼叫）。 */
    public static void comboSpent(final Player player, final int spent) {
        final AscendancyEffects effects = instance;
        if (effects == null || spent <= 0) {
            return;
        }
        final double seconds = flat(effects.sheet(player), AscKeys.COMBO_ES_DELAY);
        if (seconds > 0) {
            final PlayerState state = effects.plugin.players().get(player);
            state.setLastDamageTick(state.lastDamageTick() - Math.round(seconds * 20 * spent));
        }
    }

    /** 幽影之井：本來要召喚骷髏，改成幽影之魂；回傳 true = 不要叫出來（MinionService 呼叫）。 */
    public static boolean umbral(final Player player, final SkillBuild build) {
        final AscendancyEffects effects = instance;
        return effects != null && build != null && effects.paths.umbral(player, build.definition().id());
    }

    /** 不情願的獻祭：祭品改成影響你（Warcries 呼叫）。 */
    public static boolean unwillingOffering(final Player player) {
        final AscendancyEffects effects = instance;
        return effects != null && has(effects.sheet(player), AscKeys.UNWILLING_OFFERING);
    }

    /** 不情願的獻祭：radius 格內在撲殺範圍的敵人（沒有、或沒有這個天賦回傳 null）。 */
    public static LivingEntity offeringVictim(final Player player, final double radius) {
        final AscendancyEffects effects = instance;
        return effects == null || !has(effects.sheet(player), AscKeys.UNWILLING_OFFERING) ? null : effects.paths.offeringVictim(player, radius);
    }

    /** 不情願的獻祭：撲殺祭品的目標，回傳牠的強度（祭品每點強度效果增加 1%）。 */
    public static double cullForOffering(final Player player, final LivingEntity victim) {
        final AscendancyEffects effects = instance;
        return effects == null || victim == null ? 0 : effects.paths.cull(player, victim);
    }

    /** 狂風暴雪、二重打擊：目標身上你的冰緩／感電可以同時兩個（回傳兩個加起來的幅度；不是你的、沒有這個天賦就照原本）。 */
    public static double stackAilment(final LivingEntity attacker, final LivingEntity target, final AilmentType type, final double magnitude,
                                      final long until) {
        final AscendancyEffects effects = instance;
        if (effects == null || !(attacker instanceof Player player) || magnitude <= 0) {
            return magnitude;
        }
        final StatSheet sheet = effects.sheet(player);
        double value = magnitude;
        if (type == AilmentType.SHOCK) {
            value *= 1 - Math.min(100, flat(sheet, AscKeys.SHOCK_LESS)) / 100.0;
        }
        if (type == AilmentType.CHILL) {
            value *= 1 - Math.min(100, flat(sheet, AscKeys.CHILL_LESS)) / 100.0;
            final double cap = flat(sheet, AscKeys.CHILL_CAP);
            if (cap > 0) {
                value = Math.min(cap / 100.0, value);
            }
        }
        final boolean twice = type == AilmentType.CHILL ? has(sheet, AscKeys.DOUBLE_CHILL) : type == AilmentType.SHOCK && has(sheet, AscKeys.DOUBLE_SHOCK);
        if (!twice) {
            return value;
        }
        final EnemyState e = effects.enemy(target);
        final List<double[]> list = type == AilmentType.CHILL ? e.chills : e.shocks;
        final long now = effects.now();
        list.removeIf(entry -> entry[1] <= now);
        list.add(new double[] {value, until});
        list.sort((a, b) -> Double.compare(b[0], a[0]));
        while (list.size() > 2) {
            list.remove(list.size() - 1);
        }
        double sum = 0;
        for (final double[] entry : list) {
            sum += entry[0];
        }
        return sum;
    }

    /** 紛亂：現在混沌傷害也算進哪一種（0 凍結、1 點燃、2 感電；沒有 -1）。 */
    public static int unravel(final LivingEntity attacker) {
        final AscendancyEffects effects = instance;
        if (effects == null || !(attacker instanceof Player player) || !has(effects.sheet(player), AscKeys.UNRAVELLING)) {
            return -1;
        }
        final AscState s = effects.states.get(player.getUniqueId());
        return s == null ? -1 : s.unravel;
    }

    /** 不斷爭吵：這個玩家的詛咒 {可以多幾個, 持續時間無限（1）}。 */
    public static int[] curseRules(final UUID source) {
        final AscendancyEffects effects = instance;
        final Player player = effects == null || source == null ? null : effects.plugin.getServer().getPlayer(source);
        if (player == null) {
            return new int[] {0, 0};
        }
        final StatSheet sheet = effects.sheet(player);
        return new int[] {(int) flat(sheet, AscKeys.CURSE_EXTRA), has(sheet, AscKeys.CURSE_INFINITE) ? 1 : 0};
    }

    /** 血紅之潮：藥劑不會回復生命。 */
    public static boolean flaskNoLife(final Player player) {
        final AscendancyEffects effects = instance;
        return effects != null && has(effects.sheet(player), AscKeys.SANGUINE);
    }

    /** 阿茲莫里之酒：生命藥劑也回復魔力、魔力藥劑也回復生命。 */
    public static boolean flaskBoth(final Player player) {
        final AscendancyEffects effects = instance;
        return effects != null && has(effects.sheet(player), AscKeys.AZMERI);
    }

    /** 耐久靈藥：生命全滿時不移除生命藥劑效果。 */
    public static boolean enduringElixirs(final Player player) {
        final AscendancyEffects effects = instance;
        return effects != null && has(effects.sheet(player), AscKeys.ENDURING_ELIXIRS);
    }

    /** 心靈護符：護符充能不足時改消耗幾 % 最大魔力（沒有 0）。 */
    public static double charmManaShare(final Player player) {
        final AscendancyEffects effects = instance;
        return effects == null ? 0 : flat(effects.sheet(player), AscKeys.CHARM_MANA);
    }

    /** 鮮血荊棘：元素傷害也計入流血幅度。 */
    public static boolean elementalBleed(final LivingEntity attacker) {
        final AscendancyEffects effects = instance;
        return effects != null && attacker instanceof Player player && has(effects.sheet(player), AscKeys.ELEMENTAL_BLEED);
    }

    /** 血肉低語：被（有這個天賦的玩家）詛咒的敵人沒有生命再生。 */
    public static boolean cursedNoRegen(final LivingEntity monster) {
        final AscendancyEffects effects = instance;
        if (effects == null || effects.plugin.combat() == null || !effects.plugin.combat().debuffs().cursed(monster.getUniqueId(), effects.now())) {
            return false;
        }
        for (final tw.exilecore.combat.Debuffs.Debuff debuff : effects.plugin.combat().debuffs().active(monster.getUniqueId(), effects.now())) {
            final Player source = debuff.source() == null ? null : effects.plugin.getServer().getPlayer(debuff.source());
            if (source != null && has(effects.sheet(source), AscKeys.CURSE_NO_REGEN)) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // 事件：擊中、擊殺、下線
    // =====================================================================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeHit(final HitEvent event) {
        if (event.attacker() instanceof Player player && event.defender() != null && plugin.combat() != null) {
            final StatSheet sheet = sheet(player);
            // 靶心：先記下牠身上有沒有印記（下一 tick 印記沒了 = 被消耗了）
            if (has(sheet, AscKeys.BULLSEYE) && plugin.combat().debuffs().marked(event.defender().getUniqueId(), now())) {
                final LivingEntity target = event.defender();
                later(1, () -> {
                    if (target.isValid() && !plugin.combat().debuffs().marked(target.getUniqueId(), now())) {
                        tw.exilecore.combat.HitEffects.addCritWeakness(plugin.combat().debuffs(), target.getUniqueId(), player.getUniqueId(), now(),
                                (int) flat(sheet, AscKeys.BULLSEYE), tw.exilecore.combat.HitEffects.CRIT_WEAKNESS_TICKS);
                    }
                });
            }
            // 毀滅打擊：擊中滿血的敵人先扣掉 5～30% 生命
            if (has(sheet, AscKeys.DECIMATING) && event.result().hit() && !event.result().blocked()) {
                final LivingEntity target = event.defender();
                final org.bukkit.attribute.AttributeInstance max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (max != null && target.getHealth() >= max.getValue() - 1e-6 && plugin.monsters() != null
                        && plugin.monsters().data(target).filter(data -> !data.boss()).isPresent()) {
                    final double cut = max.getValue() * (0.05 + plugin.random().nextDouble() * 0.25);
                    target.setHealth(Math.max(1, target.getHealth() - cut));
                    target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!event.result().hit() || event.result().blocked()) {
            return;
        }
        try {
            if (event.attacker() instanceof Player player) {
                hitByPlayer(player, event);
            }
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("昇華效果（擊中後）出錯", e);
        }
    }

    private void hitByPlayer(final Player player, final HitEvent event) {
        final LivingEntity target = event.defender();
        if (target instanceof Player) {
            return;
        }
        final StatSheet sheet = sheet(player);
        final MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(target).orElse(null);
        if (data == null) {
            return;
        }
        final SkillProfile skill = event.skill();
        final Set<String> tags = skill == null ? Set.of() : skill.tags();
        final boolean totem = tags.contains(Tags.TOTEM_HIT);
        final AscState s = state(player);
        final long now = now();
        // 女巫殺手：敵人的最大專注 = 最大生命的 30%；擊中破壞專注（= 造成的傷害）
        final double witchbane = flat(sheet, AscKeys.WITCHBANE);
        if (witchbane > 0) {
            final EnemyState e = enemy(target);
            final org.bukkit.attribute.AttributeInstance max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max != null) {
                e.concentrationMax = max.getValue() * witchbane / 100.0;
                if (e.concentration < 0) {
                    e.concentration = e.concentrationMax;
                }
                e.concentration = Math.max(0, e.concentration - event.result().total());
                e.concentrationLost = now;
            }
        }
        // 獵食直覺：對稀有、傳奇敵人揭露弱點
        if (has(sheet, AscKeys.WEAKNESS) && (data.boss() || data.rarity() == MonsterRarity.RARE || data.rarity() == MonsterRarity.UNIQUE)) {
            reveal(target);
        }
        // 碎猛之擊：完全破甲之後繼續破，記成負的護甲
        if (has(sheet, AscKeys.NEGATIVE_ARMOUR) && plugin.ailments() != null && Double.isInfinite(plugin.ailments().armourBroken(target))) {
            final double percent = sheet.apply(StatKeys.ARMOUR_BREAK, sheet.flat(StatKeys.ARMOUR_BREAK, skill.contextFor(DamageType.PHYSICAL)),
                    skill.contextFor(DamageType.PHYSICAL));
            final double broken = event.result().outgoing().get(DamageType.PHYSICAL) * Math.max(0, percent) / 100.0;
            if (broken > 0) {
                final EnemyState e = enemy(target);
                final double armour = plugin.monsters().sheet(target, data).get(StatKeys.ARMOUR);
                e.negativeArmour = Math.min(Math.max(armour, 0), e.negativeArmour + broken);
                e.negativeUntil = now + 160;
            }
        }
        // 位置正好：投射物傷害會累積釘身；被釘身的敵人無法進行動作
        if (has(sheet, AscKeys.PROJECTILE_PIN) && tags.contains(Tags.PROJECTILE) && plugin.ailments() != null) {
            final double multiplier = sheet.apply("pin.buildup", 1.0, tags);
            if (plugin.ailments().pinBuildup(target, data, plugin.monsters().sheet(target, data), event.result().dealt().total(), multiplier)
                    && has(sheet, AscKeys.PIN_STOPS)) {
                plugin.ailments().holdPinned(target);
            }
        }
        // 血紅之潮：生命藥劑充能全滿時擊中敵人，消耗 40% 充能；每 1 充能獲得 1% 傷害為物理 5 秒
        if (has(sheet, AscKeys.SANGUINE) && !totem && plugin.flasks() != null) {
            final int used = AscBuild.consumeFullLifeFlask(plugin, player, 0.4);
            if (used > 0) {
                s.sanguineDamage = used;
                s.sanguineUntil = now + 100;
            }
        }
        // ……並驅散於長風之中：近戰暴擊時觸發元素展現（冷卻 0.25 秒）
        if (event.result().crit() && !totem && tags.contains(Tags.MELEE) && has(sheet, AscKeys.EXPRESSION) && plugin.skills() != null
                && now >= s.expressionReady) {
            s.expressionReady = now + 5;
            later(1, () -> plugin.skills().triggerSkill(player, AscGranted.expressionFor(this, player), target.getLocation()));
        }
        // 暴風召喚者：法術暴擊時觸發元素風暴（照這一下最高的元素，冷卻 3 秒）
        if (event.result().crit() && !totem && tags.contains(Tags.SPELL) && has(sheet, AscKeys.TEMPEST) && plugin.skills() != null
                && now >= s.tempestReady) {
            final DamagePacket dealt = event.result().dealt();
            DamageType best = null;
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                if (dealt.get(type) > 0 && (best == null || dealt.get(type) > dealt.get(best))) {
                    best = type;
                }
            }
            if (best != null) {
                s.tempestReady = now + 60;
                final String id = "elemental_storm_" + best.tag();
                later(1, () -> plugin.skills().triggerSkill(player, id, target.getLocation()));
            }
        }
        granted.hit(player, event, data);
        paths.hit(player, event, data); // 2026-10-27：空無之鐘、空無共鳴、山巒之道
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        final EnemyState enemy = enemies.remove(dead.getUniqueId());
        if (dead instanceof Player) {
            return;
        }
        final Player killer = dead.getKiller();
        try {
            if (killer != null) {
            }
            rupture(dead, killer);
            granted.death(dead, killer);
            paths.death(dead, killer); // 2026-10-27：割裂血肉
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error("昇華效果（擊殺）出錯", e);
        }
        if (enemy != null) {
            enemy.weakness = null;
        }
    }

    /** 割裂靈魂：被你（或存在範圍內友軍）擊殺的被詛咒敵人 33% 機率爆炸，造成牠最大生命四分之一的混沌傷害。 */
    private void rupture(final LivingEntity dead, final Player killer) {
        if (plugin.combat() == null || !plugin.combat().debuffs().cursed(dead.getUniqueId(), now())) {
            return;
        }
        Player lich = null;
        for (final Player player : dead.getWorld().getPlayers()) {
            final StatSheet sheet = sheet(player);
            if (!has(sheet, AscKeys.RUPTURE)) {
                continue;
            }
            if (player.equals(killer) || killer != null && killer.getWorld() == player.getWorld()
                    && killer.getLocation().distanceSquared(player.getLocation()) <= Math.pow(presence(sheet), 2)) {
                lich = player;
                break;
            }
        }
        if (lich == null || plugin.random().nextDouble() * 100 >= flat(sheet(lich), AscKeys.RUPTURE)) {
            return;
        }
        final org.bukkit.attribute.AttributeInstance max = dead.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        final double amount = max == null ? 0 : max.getValue() / 4;
        if (amount <= 0 || plugin.combat().skillDamage() == null) {
            return;
        }
        final SkillProfile profile = new SkillProfile("asc_rupture_soul", Set.of(Tags.AREA, Tags.CHAOS), 0, new DamagePacket().set(DamageType.CHAOS, amount));
        final Location at = dead.getLocation();
        dead.getWorld().spawnParticle(Particle.SCULK_SOUL, at.clone().add(0, 1, 0), 30, 0.6, 0.6, 0.6, 0.05);
        dead.getWorld().playSound(at, Sound.ENTITY_WITHER_BREAK_BLOCK, 0.6f, 1.4f);
        for (final LivingEntity target : enemiesNear(lich, at, 2.5)) {
            plugin.combat().skillDamage().hit(lich, target, profile, null);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    // =====================================================================
    // 易爆（虛步）
    // =====================================================================


    // =====================================================================
    // 每 10 tick
    // =====================================================================

    void tick() {
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
            if (state == null || !state.hasCharacter() || state.isDead() || player.isDead()) {
                continue;
            }
            try {
                state.bag().setBonusPages(has(state.sheet(), AscKeys.CHEST_SLOTS) ? 1 : 0); // 巨量承載：攜帶一個箱子（背包多一頁）
                tickPlayer(player, state, now);
                if (plugin.skills() != null) {
                    plugin.skills().tetherSpirits(player, has(state.sheet(), AscKeys.ANCESTRAL_SPIRITS)); // 2026-10-29 照 PoE2：先祖之魂跟著圖騰
                }
                // 2026-10-26：看看我怎麼辦事的（隊友的武器傷害）、導管給附近的效果：靠近、離開別人時重算
                // 2026-10-27（效能）：改成「給效果的隊友、距離內與否、他們的數值」有變才重算；這次之前只要同世界有任何人
                //   帶著這兩種天賦，整個世界每個人每 2 秒（而且是同一個 tick）都整張屬性表重算——營地人多時固定卡一下
                final long signature = giverSignature(player);
                final AscState own = state(player);
                if (signature != own.giverSignature) {
                    own.giverSignature = signature;
                    state.markDirty();
                }
            } catch (final RuntimeException e) {
                tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "昇華效果（每秒）出錯", e); // 2026-10-27：同一個錯一分鐘寫一次
            }
        }
        // 專注：過去 5 秒沒失去專注的敵人每秒恢復 10%
        final Iterator<Map.Entry<UUID, EnemyState>> iterator = enemies.entrySet().iterator();
        while (iterator.hasNext()) {
            final EnemyState e = iterator.next().getValue();
            if (now - e.lastSeen > 1200) {
                iterator.remove();
                continue;
            }
            if (e.concentration >= 0 && e.concentration < e.concentrationMax && now - e.concentrationLost >= 100) {
                e.concentration = Math.min(e.concentrationMax, e.concentration + e.concentrationMax * 0.10 * PERIOD / 20.0);
            }
        }
        granted.tick(now);
        paths.tick(now); // 2026-10-27：靈現之靈、梟羽、熊靈、空無之鐘、山巒之教、幽影之魂
    }

    /**
     * 2026-10-27（效能）：會給這個人效果的隊友（看看我怎麼辦事的、導管）現在的指紋：誰、在不在存在範圍內、他的武器傷害、魔力、導管開著沒。
     * 指紋變了才重算屬性表（{@link #allyWeapon}、{@link #unholyMight} 用的就是這些）。沒有隊伍、沒有隊友帶這些天賦是 0。
     */
    private long giverSignature(final Player player) {
        if (plugin.party() == null) {
            return 0;
        }
        long hash = 0;
        for (final UUID id : plugin.party().groupMembers(player.getUniqueId())) {
            final Player other = plugin.getServer().getPlayer(id);
            if (other == null || other.equals(player) || other.getWorld() != player.getWorld()) {
                continue;
            }
            final PlayerState otherState = plugin.players().find(id).orElse(null);
            if (otherState == null) {
                continue;
            }
            final StatSheet sheet = sheet(other);
            final boolean weapon = has(sheet, AscKeys.ALLY_WEAPON_DAMAGE);
            final boolean conduit = has(sheet, AscKeys.CONDUIT);
            if (!weapon && !conduit) {
                continue;
            }
            final boolean inside = other.getLocation().distanceSquared(player.getLocation()) <= Math.pow(presence(sheet), 2);
            long one = id.getLeastSignificantBits() * 31 + (inside ? 1 : 2);
            if (inside && weapon) {
                one = one * 31 + Double.hashCode(flat(sheet, AscKeys.ALLY_WEAPON_DAMAGE));
                one = one * 31 + Double.hashCode(sheet.flat(StatKeys.WEAPON_MAX, Set.of()));
                one = one * 31 + Double.hashCode(sheet.flat(StatKeys.WEAPON_MIN, Set.of()));
            }
            if (inside && conduit) {
                final AscState s = states.get(id);
                one = one * 31 + (s != null && s.conduitActive ? 7 : 3);
                one = one * 31 + (long) Math.floor(otherState.maxMana() / 100);
            }
            hash += one * 0x9E3779B97F4A7C15L; // 順序不影響
        }
        return hash;
    }

    private void tickPlayer(final Player player, final PlayerState state, final long now) {
        final StatSheet sheet = state.sheet();
        final AscState s = states.get(player.getUniqueId());
        final boolean any = s != null || has(sheet, AscKeys.JADE) || has(sheet, AscKeys.INFERNAL) || has(sheet, AscKeys.CONDUIT)
                || has(sheet, AscKeys.RAGE_REGEN) || has(sheet, AscKeys.LOW_ES_RECHARGE) || has(sheet, AscKeys.ETERNAL_LIFE)
                || has(sheet, AscKeys.BANNER_GLORY) || has(sheet, AscKeys.SANDS) || has(sheet, AscKeys.UNRAVELLING)
                || has(sheet, AscKeys.FOR_THE_KILL) || has(sheet, AscKeys.EMBANKMENT) || has(sheet, AscKeys.ES_POWER);
        if (!any) {
            return;
        }
        final AscState st = state(player);
        boolean dirty = false;
        boolean changed = false;
        // 玉石：每秒一層（有守護時不能獲得）
        if (has(sheet, AscKeys.JADE)) {
            if (st.jade < JADE_MAX && now >= st.jadeNext && state.guard(now) <= 0) {
                st.jade++;
                st.jadeNext = now + 20;
                dirty = true;
            }
        } else if (st.jade > 0) {
            st.jade = 0;
            dirty = true;
        }
        // 黑暗：保留到期的回來
        while (!st.darkness.isEmpty() && st.darkness.peekFirst()[1] <= now) {
            st.darkness.pollFirst();
        }
        // 煉獄之炎：最大值是兩倍的最大魔力（建表時記下）；過去 2 秒沒獲得就每秒失去 25%
        if (has(sheet, AscKeys.INFERNAL)) {
            st.infernalMax = AscBuild.infernalMax(sheet);
            if (st.infernal > 0 && now - st.infernalGained >= 40) {
                st.infernal = Math.max(0, st.infernal - st.infernal * 0.25 * PERIOD / 20.0 - 0.01);
            }
            // 沸騰軀體：高煉獄之炎時元素執政官不會結束
            if (has(sheet, AscKeys.SEETHING) && st.infernal >= st.infernalMax * HIGH_INFERNAL && plugin.skills() != null
                    && plugin.skills().treeMechanics() != null) {
                plugin.skills().treeMechanics().holdArchon(player, now + PERIOD * 2);
            }
        }
        // 靈能導體：每秒失去 5% 最大魔力
        final double conduit = flat(sheet, AscKeys.CONDUIT);
        if (conduit > 0) {
            state.setMana(Math.max(0, state.mana() - state.maxMana() * conduit / 100.0 * PERIOD / 20.0));
            changed = true;
        }
        // 狂怒源泉：每秒再生 6% 最大怒氣（魔力再生速度的增減也套用）、怒氣不會自然流失
        final double rageRegen = flat(sheet, AscKeys.RAGE_REGEN);
        if (rageRegen > 0 && plugin.skills() != null) {
            final int max = plugin.skills().resources().rageMax(sheet);
            final double scale = Math.max(0, 1 + sheet.increased(StatKeys.MANA_REGEN, Set.of())) * sheet.more(StatKeys.MANA_REGEN, Set.of());
            plugin.skills().resources().gainRage(player, max * rageRegen / 100.0 * scale * PERIOD / 20.0);
        }
        // 第四教誨：低能量護盾時更多 40% 能量護盾回充速度
        final double lowEs = flat(sheet, AscKeys.LOW_ES_RECHARGE);
        if (lowEs > 0 && state.maxEnergyShield() > 0 && state.energyShield() < state.maxEnergyShield() * LOW_RESOURCE
                && now - state.lastDamageTick() >= tw.exilecore.character.PlayerStateService.esRechargeDelay(sheet)) {
            state.setEnergyShield(Math.min(state.maxEnergyShield(), state.energyShield()
                    + tw.exilecore.stats.Formulas.esRechargePerSecond(state.maxEnergyShield(),
                    tw.exilecore.character.PlayerStateService.esRechargeSpeed(sheet)) * lowEs / 100.0 * PERIOD / 20.0));
            changed = true;
        }
        // 永生：擁有能量護盾時，生命無法改變
        if (has(sheet, AscKeys.ETERNAL_LIFE)) {
            if (state.energyShield() > 0 && st.lockedLife >= 0) {
                if (Math.abs(state.life() - st.lockedLife) > 1e-6) {
                    state.setLife(Math.min(state.maxLife(), st.lockedLife));
                    changed = true;
                }
            } else {
                st.lockedLife = state.life();
            }
            if (state.energyShield() > 0 && st.lockedLife < 0) {
                st.lockedLife = state.life();
            }
        }
        // 待價而沽：鬥旗每秒獲得 5 榮耀
        final double bannerGlory = flat(sheet, AscKeys.BANNER_GLORY);
        if (bannerGlory > 0 && plugin.skills() != null) {
            for (int i = 0; i < tw.exilecore.character.SkillLoadout.MAX_SKILLS; i++) {
                final tw.exilecore.character.SkillLoadout.Entry entry = state.skills().skill(i);
                final SkillDefinition definition = entry == null ? null : plugin.skills().config().skill(entry.skillId());
                if (definition != null && definition.tags().contains("banner")) {
                    plugin.skills().addGlory(player, definition.id(), bannerGlory * PERIOD / 20.0);
                }
            }
        }
        // 紛亂：每 2 秒換一種
        if (has(sheet, AscKeys.UNRAVELLING) && now >= st.unravelNext) {
            st.unravel = plugin.random().nextInt(3);
            st.unravelNext = now + 40;
        }
        // 時間之沙、專注擊殺、堤防光環、最近消耗過銳星、不潔之力：狀態一變就重算屬性表
        if (has(sheet, AscKeys.SANDS)) {
            if (st.sandsStart < 0) {
                st.sandsStart = now;
            }
            if (every(now, 20)) {
                dirty = true;
            }
        } else if (st.sandsStart >= 0) {
            // 2026-10-27：退了天賦（或換了角色）就沒有；這次之前增益一直留著
            st.sandsStart = -1;
            dirty = true;
        }
        if (!has(sheet, AscKeys.TAILWIND) && st.tailwind > 0) {
            st.tailwind = 0; // 2026-10-27：同上
            dirty = true;
        }
        if (has(sheet, AscKeys.FOR_THE_KILL)) {
            boolean open = false;
            for (final LivingEntity enemy : enemiesNear(player, player.getLocation(), presence(sheet))) {
                if (openWeakness(enemy)) {
                    open = true;
                    break;
                }
            }
            if (open != st.forTheKill) {
                st.forTheKill = open;
                dirty = true;
            }
        } else if (st.forTheKill) {
            st.forTheKill = false; // 2026-10-27：退了天賦就沒有
            dirty = true;
        }
        if (has(sheet, AscKeys.EMBANKMENT) && plugin.skills() != null) {
            final int mask = embankmentMask(player);
            if (mask != st.lastEmbankment) {
                st.lastEmbankment = mask;
                dirty = true;
            }
        } else if (st.lastEmbankment != 0) {
            st.lastEmbankment = 0; // 2026-10-27：退了天賦就沒有
            dirty = true;
        }
        if (has(sheet, AscKeys.ES_POWER)) {
            final boolean recent = state.recently(tw.exilecore.stats.Recently.POWER, now);
            if (recent != st.esPowerRecent) {
                st.esPowerRecent = recent;
                dirty = true;
            }
        } else if (st.esPowerRecent) {
            st.esPowerRecent = false; // 2026-10-27：退了天賦就沒有
            dirty = true;
        }
        if (has(sheet, AscKeys.CONDUIT)) {
            final boolean active = state.mana() > state.maxMana() * LOW_RESOURCE;
            if (active != st.conduitActive) {
                st.conduitActive = active;
                dirty = true;
            }
        }
        if (has(sheet, AscKeys.ARCANE_PER_MANA) && every(now, 20)) {
            dirty = true;
        }
        if (dirty) {
            plugin.players().markDirty(player);
        }
        if (changed) {
            plugin.players().display(player, state);
        }
    }

    // =====================================================================
    // 增益（屬性表的來源）
    // =====================================================================

    static final String[] EMBANKMENT_SKILLS = {"siege_ballista", "artillery_ballista", "mortar_cannon", "shockwave_totem", "ancestral_totem",
            "dark_idol", "spell_totem"};

    /** 站在哪幾種圖騰的堤防光環（4.5 格）裡（位元）。 */
    private int embankmentMask(final Player player) {
        int mask = 0;
        for (final tw.exilecore.skill.SkillService.TotemView totem : plugin.skills().totems(player.getUniqueId())) {
            if (totem.feet().getWorld() != player.getWorld() || totem.feet().distanceSquared(player.getLocation()) > 4.5 * 4.5) {
                continue;
            }
            int index = -1;
            for (int i = 0; i < EMBANKMENT_SKILLS.length; i++) {
                if (EMBANKMENT_SKILLS[i].equals(totem.skillId())) {
                    index = i;
                }
            }
            if (index < 0 && totem.skillId().contains("totem")) {
                index = 6; // 其他施法圖騰當作法術圖騰
            }
            if (index >= 0) {
                mask |= 1 << index;
            }
        }
        return mask;
    }

    /** 這些機制給的暫時修飾器（重算屬性表時）。 */
    List<Modifier> buffModifiers(final Player player) {
        final AscState s = states.get(player.getUniqueId());
        final List<Modifier> out = new ArrayList<>();
        final PlayerState state = plugin.players().find(player.getUniqueId()).orElse(null);
        if (state == null || !state.hasCharacter()) {
            return out;
        }
        final long now = now();
        if (s != null) {
            if (s.jade > 0) {
                out.add(Modifier.flat(StatKeys.PHYS_REDUCTION, s.jade, "玉石"));
            }
            if (s.tailwind > 0) {
                out.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, 0.01 * s.tailwind, "順風"));
                out.add(Modifier.increased(StatKeys.ATTACK_SPEED, 0.02 * s.tailwind, "順風"));
                out.add(Modifier.increased(StatKeys.CAST_SPEED, 0.02 * s.tailwind, "順風"));
                out.add(Modifier.increased(StatKeys.EVASION, 0.10 * s.tailwind, "順風"));
            }
            if (s.forTheKill) {
                out.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, 0.20, "專注擊殺"));
                out.add(Modifier.increased(StatKeys.ATTACK_SPEED, 0.40, "專注擊殺"));
                out.add(Modifier.increased(StatKeys.CAST_SPEED, 0.40, "專注擊殺"));
            }
            if (s.esPowerRecent) {
                final double es = flat(state.sheet(), AscKeys.ES_POWER);
                if (es > 0) {
                    out.add(Modifier.increased(StatKeys.ES_MAX, es / 100.0, "最近消耗過銳星"));
                }
            }
            if (s.lastEmbankment != 0) {
                out.addAll(embankment(s.lastEmbankment));
            }
            if (s.sandsStart >= 0) {
                // 時間之沙：技能速度 60% 在 10 秒內降到 1%，再升回 60%；範圍反過來
                final double phase = ((now - s.sandsStart) % 400) / 200.0;
                final double t = phase <= 1 ? phase : 2 - phase;
                final double speed = 0.60 - 0.59 * t;
                final double area = 0.01 + 0.59 * t;
                out.add(Modifier.increased(StatKeys.ATTACK_SPEED, speed, "時間之沙"));
                out.add(Modifier.increased(StatKeys.CAST_SPEED, speed, "時間之沙"));
                out.add(Modifier.increased(StatKeys.SKILL_AREA, area, "時間之沙"));
            }
        }
        // 不變勁風：你擁有秘術增強（天賦樹的 flag.arcane_surge_always，TreeMechanics 給增益）；
        // 意志之力：每損失 10% 魔力，秘術增強效果增加 20%（加在 arcane_surge.effect，TreeMechanics 重給增益時照這個算）
        final StatSheet base = state.sheet();
        final double perMana = flat(base, AscKeys.ARCANE_PER_MANA);
        if (perMana > 0 && state.maxMana() > 0) {
            out.add(Modifier.increased(tw.exilecore.stats.TreeNewMechanicKeys.ARCANE_SURGE_EFFECT,
                    Math.floor((1 - state.mana() / state.maxMana()) * 10) * perMana / 100.0, "意志之力"));
        }
        // 靈能導體：非低魔力時你與存在範圍內的友軍擁有不潔之力；黑化之心：每 100 最大魔力增加 4% 幅度
        out.addAll(unholyMight(player, state));
        // 看看我怎麼辦事的：存在範圍內的友軍獲得附加攻擊傷害 = 你主手武器傷害的 25%
        out.addAll(allyWeapon(player));
        // 衝刺突擊：衝刺時無法被重度暈眩；女王進軍：衝刺時其他來源的傷害不會造成重度暈眩
        if (player.isSprinting() && (has(base, AscKeys.SPRINT_STUN) || plugin.skills() != null && plugin.skills().auraOn(player, "queens_procession"))) {
            out.add(Modifier.flat("flag.immune.stun", 1, "衝刺突擊"));
        }
        out.addAll(granted.buffModifiers(player, state));
        out.addAll(paths.buffModifiers(player, state)); // 2026-10-27：凱莎的平衡、自然秩序、翱翔之地、荒野擁抱、山巒之教、幽影之魂
        return out;
    }

    /**
     * 2026-10-27：網頁規劃器的虛擬角色用（PlayerStateService 不呼叫線上玩家才有的 {@link #buffModifiers}）：昇華增益裡只看屬性表的——
     * 凱莎的平衡、自然秩序的移動速度（{@link AscPaths#sheetOnly}）、靈能導體的不潔之力（當作不是低魔力：30% 額外混沌，黑化之心照最大魔力加幅度）。
     * 看狀態的（順風、時間之沙、林靈附身、意志之力…）不算。這次之前網頁完全沒有這些。純函式（測試用）。
     */
    public static List<Modifier> virtualBuffs(final StatSheet sheet) {
        final List<Modifier> out = new ArrayList<>(AscPaths.sheetOnly(sheet));
        if (has(sheet, AscKeys.CONDUIT) && !StatKeys.flag(sheet, tw.exilecore.item.unique.UniqueStats.UNHOLY_MIGHT)) {
            final double magnitude = 1 + Math.floor(tw.exilecore.character.PlayerState.maxMana(sheet) / 100) * flat(sheet, AscKeys.UNHOLY_PER_MANA) / 100.0;
            out.add(Modifier.flat(StatKeys.DAMAGE_EXTRA + Tags.CHAOS, tw.exilecore.item.unique.UniqueStats.UNHOLY_MIGHT_PERCENT * magnitude, "不潔之力"));
        }
        return out;
    }

    private static List<Modifier> embankment(final int mask) {
        final List<Modifier> out = new ArrayList<>();
        if ((mask & 1) != 0) {
            out.add(Modifier.increased(StatKeys.ATTACK_SPEED, 0.20, "堤防光環"));
            out.add(Modifier.increased(StatKeys.CAST_SPEED, 0.20, "堤防光環"));
        }
        // 2（攻城弩車：對被禁錮的敵人更多 25% 傷害）在擊中時看目標（attackerConditional）
        if ((mask & 4) != 0) {
            out.add(Modifier.flat(StatKeys.DAMAGE_EXTRA + Tags.FIRE, 25, "堤防光環"));
        }
        if ((mask & 8) != 0) {
            out.add(Modifier.increased(StatKeys.SKILL_AREA, 0.30, "堤防光環"));
        }
        if ((mask & 16) != 0) {
            out.add(Modifier.increased(AscKeys.GLORY_GENERATION, 0.40, "堤防光環"));
        }
        if ((mask & 32) != 0) {
            // 增加 40% 持續傷害：異常（點燃、流血、中毒）的幅度與技能的持續傷害
            out.add(Modifier.increased(StatKeys.IGNITE_MAGNITUDE, 0.40, "堤防光環"));
            out.add(Modifier.increased(StatKeys.BLEED_MAGNITUDE, 0.40, "堤防光環"));
            out.add(Modifier.increased(StatKeys.POISON_MAGNITUDE, 0.40, "堤防光環"));
            out.add(Modifier.more(tw.exilecore.stats.SupportKeys.DAMAGE_SKILL_DOT, 0.40, "堤防光環"));
        }
        if ((mask & 64) != 0) {
            out.add(Modifier.increased(StatKeys.CRIT_CHANCE, 0.50, "堤防光環"));
        }
        return out;
    }

    private List<Modifier> allyWeapon(final Player player) {
        if (plugin.party() == null) {
            return List.of();
        }
        final List<Modifier> out = new ArrayList<>();
        for (final UUID id : plugin.party().groupMembers(player.getUniqueId())) {
            final Player other = plugin.getServer().getPlayer(id);
            if (other == null || other.equals(player) || other.getWorld() != player.getWorld()) {
                continue;
            }
            final StatSheet sheet = sheet(other);
            final double percent = flat(sheet, AscKeys.ALLY_WEAPON_DAMAGE);
            if (percent <= 0 || other.getLocation().distanceSquared(player.getLocation()) > Math.pow(presence(sheet), 2)) {
                continue;
            }
            for (final DamageType type : DamageType.values()) {
                final double min = sheet.flat(StatKeys.WEAPON_MIN, type.tags()) * percent / 100.0;
                final double max = sheet.flat(StatKeys.WEAPON_MAX, type.tags()) * percent / 100.0;
                if (max > 0) {
                    final Set<String> tags = new java.util.HashSet<>(type.tags());
                    tags.add(Tags.ATTACK);
                    out.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, min, Set.of(type.tag(), Tags.ATTACK), "看看我怎麼辦事的"));
                    out.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, max, Set.of(type.tag(), Tags.ATTACK), "看看我怎麼辦事的"));
                }
            }
            break;
        }
        return out;
    }

    /**
     * 2026-10-27 照 PoE2（Watch How I Do It 的「友軍」含召喚物）：召喚物在主人或主人隊友的存在範圍內時，
     * 獲得附加攻擊傷害 = 那個人主手武器傷害的 25%（取第一個符合的人，跟隊友那條一樣）。這次之前只給隊友。
     */
    public List<Modifier> minionWeapon(final UUID owner, final org.bukkit.Location at) {
        final List<UUID> people = new ArrayList<>();
        people.add(owner);
        if (plugin.party() != null) {
            for (final UUID id : plugin.party().groupMembers(owner)) {
                if (!id.equals(owner)) {
                    people.add(id);
                }
            }
        }
        for (final UUID id : people) {
            final Player other = plugin.getServer().getPlayer(id);
            if (other == null || at == null || other.getWorld() != at.getWorld()) {
                continue;
            }
            final StatSheet sheet = sheet(other);
            final double percent = flat(sheet, AscKeys.ALLY_WEAPON_DAMAGE);
            if (percent <= 0 || other.getLocation().distanceSquared(at) > Math.pow(presence(sheet), 2)) {
                continue;
            }
            return minionWeaponFrom(sheet);
        }
        return List.of();
    }

    /**
     * 2026-10-27：看看我怎麼辦事的給召喚物的附加攻擊傷害，只照那個人的屬性表算（當作在存在範圍內）：
     * {@link #minionWeapon} 與 /stats、網頁的召喚物每秒傷害、召喚物說明共用。沒有這個昇華是空的。純函式（測試用）。
     */
    public static List<Modifier> minionWeaponFrom(final StatSheet sheet) {
        final double percent = flat(sheet, AscKeys.ALLY_WEAPON_DAMAGE);
        if (percent <= 0) {
            return List.of();
        }
        final List<Modifier> out = new ArrayList<>();
        for (final DamageType type : DamageType.values()) {
            final double min = sheet.flat(StatKeys.WEAPON_MIN, type.tags()) * percent / 100.0;
            final double max = sheet.flat(StatKeys.WEAPON_MAX, type.tags()) * percent / 100.0;
            if (max > 0) {
                out.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, min, Set.of(type.tag(), Tags.ATTACK), "看看我怎麼辦事的"));
                out.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, max, Set.of(type.tag(), Tags.ATTACK), "看看我怎麼辦事的"));
            }
        }
        return out;
    }

    private List<Modifier> unholyMight(final Player player, final PlayerState state) {
        double best = 0;
        for (final Player other : player.getWorld().getPlayers()) {
            final StatSheet sheet = other.equals(player) ? state.sheet() : sheet(other);
            if (!has(sheet, AscKeys.CONDUIT)) {
                continue;
            }
            final AscState s = states.get(other.getUniqueId());
            if (s == null || !s.conduitActive) {
                continue;
            }
            if (!other.equals(player) && (plugin.party() == null || !plugin.party().groupMembers(other.getUniqueId()).contains(player.getUniqueId())
                    || other.getLocation().distanceSquared(player.getLocation()) > Math.pow(presence(sheet), 2))) {
                continue;
            }
            final PlayerState otherState = other.equals(player) ? state : plugin.players().get(other);
            final double magnitude = 1 + Math.floor(otherState.maxMana() / 100) * flat(sheet, AscKeys.UNHOLY_PER_MANA) / 100.0;
            best = Math.max(best, magnitude);
        }
        if (best <= 0 || StatKeys.flag(state.sheet(), tw.exilecore.item.unique.UniqueStats.UNHOLY_MIGHT)) {
            return List.of();
        }
        return List.of(Modifier.flat(StatKeys.DAMAGE_EXTRA + Tags.CHAOS, tw.exilecore.item.unique.UniqueStats.UNHOLY_MIGHT_PERCENT * best, "不潔之力"));
    }
}
