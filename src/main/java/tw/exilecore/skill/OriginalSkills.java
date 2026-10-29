package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.combat.Corpses;
import tw.exilecore.combat.Debuffs;
import tw.exilecore.combat.SkillDamage;
import tw.exilecore.monster.MonsterData;
import tw.exilecore.monster.MonsterRarity;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 2026-10-26：照 PoE2 0.5 的原作機制重做的技能（這次之前用平衡等效的數值頂著）。資料（數值逐級表、隱藏技能）在 skills.yml。
 * <ul>
 *   <li><b>凜冬之殼</b>（Arctic Armour）：常駐，隨時間疊冰甲層數；被近戰攻擊打中時用掉一層，對攻擊者炸一圈冰（每層一份冰冷法術傷害）。</li>
 *   <li><b>雷縛長棍</b>（Charged Staff）：用掉全部銳星，每顆 6 秒、每顆附加閃電到長棍攻擊；期間長棍攻擊放出電震波。</li>
 *   <li><b>崩滅心咒</b>（Mantra of Destruction）：要 8 點連擊，強化下一次長棍攻擊（獲得傷害為額外混沌）；被它殺死的敵人給你紫焰 20 秒。</li>
 *   <li><b>磨蝕之志</b>（Attrition）：稀有、傳奇敵人在你身邊越久受到你的擊中傷害越多，夠久之後撲殺。</li>
 *   <li><b>雷流場</b>（Galvanic Field）：增益讓感電更容易；感電一個敵人時用掉增益，在牠身上掛一顆電球，每 0.6 秒射一道連鎖閃電。</li>
 *   <li><b>燼種</b>（Living Bomb）：種在敵人身上，牠受到異常門檻 200% 的傷害或死掉時爆開，留下火焰灌注殘影。</li>
 *   <li><b>回盾術</b>（Convalescence）、<b>骸餘饗</b>（Grim Feast）：常駐技能按按鍵放出賦予的法術（立刻回充護盾／把存的生命拿去復活召喚物）。</li>
 *   <li><b>僕骸替</b>（Sacrifice）：不死的常駐召喚物可以當屍體用（被用掉就倒下，復活更慢）。</li>
 *   <li><b>拾晶裝</b>（Shard Scavenger）、<b>蒺藜步</b>（Trail of Caltrops）、<b>墜星熊吼</b>（Walking Calamity）、
 *       <b>鼓翼風</b>、<b>噬咬</b>、<b>黑油雨</b>、<b>掠影撲</b>的獵手印記與狼、<b>冰裂</b>（Ice Fragments）、弩的<b>熱度</b>（Heat）。</li>
 * </ul>
 */
public final class OriginalSkills implements Listener {

    // ---- 技能代號 ----
    static final String ARCTIC = "frost_aegis";
    static final String STAFF = "storm_staff";
    static final String MANTRA = "ruin_mantra";
    static final String ATTRITION = "attrition";
    static final String GALVANIC = "galvanic_field";
    static final String SEED = "ember_seed";
    static final String CONVALESCENCE = "convalescence";
    static final String GRIM = "grim_feast";
    static final String SACRIFICE = "sacrifice";
    static final String SHARD = "shard_scavenger";
    static final String CALTROPS = "trail_of_caltrops";
    static final String CALAMITY = "walking_calamity";
    static final String WING = "wing_blast";
    static final String DEVOUR = "devour";
    static final String OIL = "oil_barrage";
    static final String POUNCE = "pounce";
    static final String SHRED = "wolf_shred";
    static final String CROSS = "cross_slash";
    static final String ICE_TIPPED = "ice_tipped_arrows";
    static final String ESCAPE = "escape_shot";
    static final String RAPID = "rapid_fire";

    // ---- 隱藏技能 ----
    static final String ARCTIC_BURST = "proc_arctic_burst";
    static final String STAFF_WAVE = "proc_charged_shockwave";
    static final String GALVANIC_BOLT = "proc_galvanic_bolt";
    static final String CALTROP = "proc_caltrop";
    static final String METEOR = "proc_calamity_meteor";
    static final String WING_WAVE = "proc_wing_shockwave";
    static final String WOLF = "proc_predator_wolf";
    static final String FRAGMENT_SHRED = "proc_ice_fragment_shred";
    static final String FRAGMENT_ARROW = "proc_ice_fragment_arrow";
    static final String FRAGMENT_ESCAPE = "proc_ice_fragment_escape";

    /** skills.yml 的 signature：自己整個接手（不走一般的執行）。 */
    public static final Set<String> SIGNATURES = Set.of("charged_staff", "mantra", "galvanic", "living_bomb", "calamity",
            Spearfield.SIGNATURE); // 2026-10-27 照 PoE2 Spearfield（矛陣：插在地上、敵人碰到才炸的矛）
    /** 熱度：用掉 15 點換加成的技能（PoE2 的爆裂榴彈、子母榴彈：獲得 50% 傷害為額外火焰；燃焰散射：點燃幅度更多 100%）。 */
    static final Map<String, List<Modifier>> HEAT_CONSUMERS = Map.of(
            "explosive_grenade", List.of(Modifier.flat(StatKeys.damageExtra(DamageType.FIRE), 50, "熱度")),
            "cluster_grenade", List.of(Modifier.flat(StatKeys.damageExtra(DamageType.FIRE), 50, "熱度")),
            "incendiary_shot", List.of(Modifier.more(StatKeys.IGNITE_MAGNITUDE, 1.0, "熱度")));
    static final int HEAT_COST = 15;

    // ---- 逐級數值（level_modifiers 的屬性代號）----
    static final String ARCTIC_INTERVAL = "arctic.interval";
    static final String ARCTIC_STAGES = "arctic.stages";
    static final String STAFF_MIN = "charged_staff.min";
    static final String STAFF_MAX = "charged_staff.max";
    static final String MANTRA_CHAOS = "mantra.chaos";
    static final String ATTRITION_CAP = "attrition.cap";
    static final String ATTRITION_CULL = "attrition.cull";
    static final String CONVALESCENCE_TIME = "convalescence.duration";
    static final String GRIM_CAP = "grim.cap";
    static final String SACRIFICE_SLOWER = "sacrifice.slower";
    static final String SHARD_INTERVAL = "shard.interval";
    static final String DEVOUR_REGEN = "devour.regen";
    static final String WOLF_LIMIT = "predator.wolves";
    static final String ESCAPE_FRAGMENTS = "escape.fragments";

    /** 標記：這一下是被崩滅心咒強化的、帶著霜鏃的冰裂。 */
    static final String MANTRA_MARK = "mantra.empowered";
    static final String ICE_TIPPED_MARK = "ice_tipped.empowered";
    /** 黑油雨的油地（SkillZones 的未點燃火油）：緩速幾 %、曝露幾 %。 */
    public static final String OIL_SLOW = "oil.slow";
    public static final String OIL_EXPOSURE = "oil.exposure";
    /** 不留下地面效果（黑油雨用掉銳星的帶電油彈）。 */
    public static final String NO_GROUND = "flag.no_ground";

    static final long ARCTIC_BURST_COOLDOWN = 5;
    static final long STAFF_TICKS_PER_CHARGE = 120;
    static final int MANTRA_COMBO = 8;
    static final long MANTRA_TICKS = 160;
    static final long PURPLE_FLAMES_TICKS = 400;
    static final double PURPLE_FLAMES = 7;
    static final long GALVANIC_TICKS = 120;
    static final long GALVANIC_INTERVAL = 12;
    static final double GALVANIC_RADIUS = tw.exilecore.core.Units.metres(1.9); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final long SEED_TICKS = 300;
    static final double SEED_THRESHOLD = 2.0;
    static final double SEED_RADIUS = tw.exilecore.core.Units.metres(2.5); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final long REMNANT_TICKS = 160;
    static final double REMNANT_PICKUP = tw.exilecore.core.Units.metres(1.6); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final long CONVALESCENCE_COOLDOWN = 600;
    static final double CONVALESCENCE_MANA = 20;
    static final double GRIM_SHARE = 0.35;
    static final long GRIM_COOLDOWN = 20;
    static final long SHARD_BUFF = 60;
    static final int CALTROP_COUNT = 3;
    static final int CALTROP_MAX = 20;
    static final long CALTROP_TICKS = 160;
    static final double CALTROP_TRIGGER = 0.5;
    static final double CALTROP_BLAST = tw.exilecore.core.Units.metres(0.8); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final long CALAMITY_TICKS = 200;
    static final double CALAMITY_RANGE = tw.exilecore.core.Units.metres(8); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final double CALAMITY_INTERVAL = 3;
    static final int CALAMITY_LARGE_EVERY = 15;
    static final double SMALL_METEOR = tw.exilecore.core.Units.metres(0.8); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final double LARGE_METEOR = tw.exilecore.core.Units.metres(1.4); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final double DEVOUR_LEAP = tw.exilecore.core.Units.metres(4); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final int DEVOUR_EXTRA = 2;
    static final long OIL_WINDOW = 30;
    static final long MARK_TICKS = 160;
    static final double MARK_PER_ENEMY = 3;
    static final double MARK_CAP = 45;
    static final double MARK_RADIUS = tw.exilecore.core.Units.metres(6); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final long FRAGMENT_TICKS = 50;
    static final double FRAGMENT_RADIUS = tw.exilecore.core.Units.metres(1.8); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
    static final int SHRED_FRAGMENTS = 3;
    static final int ICE_TIPPED_FRAGMENTS = 5;
    static final int ICE_TIPPED_USES = 4;
    static final long ICE_TIPPED_TICKS = 400;
    static final double CROSS_PULL = 7;
    static final long HEAT_DECAY_DELAY = 80;
    /** 地刺崩裂的地刺：6 秒、最多 6 根（PoE2 Earthshatter）。 */
    static final long SPIKE_TICKS = 120;
    static final int SPIKE_LIMIT = 6;
    static final double HEAT_DECAY_PER_SECOND = 10;

    private record Fragment(UUID owner, Location at, long explodeAt, SkillProfile profile, double radius, ItemDisplay display) {
    }

    private record Caltrop(UUID owner, Location at, long until, SkillProfile profile, ItemDisplay display, long armedAt, boolean delayed, double rearm) {
        /** 2026-10-27：delayed＝Delayed Reaction（只在放下的那一刻或時間到時炸）；rearm＝Rearm 的機率 %；armedAt＝重新布置後幾時能踩。 */
        Caltrop(final UUID owner, final Location at, final long until, final SkillProfile profile, final ItemDisplay display) {
            this(owner, at, until, profile, display, 0, false, 0);
        }
    }

    private static final class Orb {
        UUID target;
        Location last;
        long until;
        long next;
        SkillBuild bolt;
    }

    private static final class Seed {
        UUID target;
        SkillBuild build;
        double threshold;
        double taken;
        long until;
    }

    private static final class State {
        int arcticStages;
        long nextArcticStage = -1;
        long arcticReady;
        long staffUntil;
        int staffCharges;
        long mantraUntil;
        int mantraLevel;
        /** 雷流場的電球、燼種（2026-10-28 照 PoE2「上限 1 顆」吃 +x 上限（過剩），改成清單、最舊的在前面；這次之前各一顆的欄位）。 */
        final List<Orb> orbs = new java.util.ArrayList<>();
        final List<Seed> seeds = new java.util.ArrayList<>();
        long convalescenceReady;
        long convalescenceUntil;
        double grimStored;
        long grimReady;
        final Map<String, Double> grimCost = new HashMap<>();
        long shardReady;
        long lastRapid = Long.MIN_VALUE / 2;
        long calamityUntil;
        long nextMeteor;
        int meteors;
        SkillBuild calamity;
        int iceTippedLeft;
        long fragmentReady;
        long shredUse = -1;
        long escapeUse = -1;
        long oilUntil;
        final List<Caltrop> caltrops = new ArrayList<>();
        final Map<UUID, Double> attrition = new HashMap<>();
        UUID marked;
        long markUntil;
        int markLevel;
        long pounceTick = -1;
        UUID pounceBest;
        int pounceRank = -1;
        final Map<UUID, Corpses.Corpse> virtualCorpses = new HashMap<>();
        /** 2026-10-28 照 PoE2 Earthshatter 的品質：最近幾次用地刺崩裂的 tick（每次留一根地刺，6 秒、最多 6 根）。 */
        final java.util.ArrayDeque<Long> spikes = new java.util.ArrayDeque<>();
    }

    private final ExileCore plugin;
    private final SkillService service;
    private final Map<UUID, State> states = new HashMap<>();
    private final List<Fragment> fragments = new ArrayList<>();
    /** 被崩滅心咒強化的那一下打到的敵人 → {玩家, tick}。 */
    private final Map<UUID, Object[]> mantraHits = new HashMap<>();
    /** 誰最後打這隻怪（拾晶裝看是誰用掉了異常）：怪物 → {玩家, tick}。 */
    private final Map<UUID, Object[]> lastHitter = new HashMap<>();
    /** 磨蝕之志：玩家 → 可以撲殺的稀有、傳奇敵人。 */
    private static final Map<UUID, Set<UUID>> CULLABLE = new ConcurrentHashMap<>();
    /** 僕骸替：玩家 → 召喚物復活時間的倍率。 */
    private static final Map<UUID, Double> REVIVE_SCALE = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask task;

    OriginalSkills(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
        this.spearfield = new Spearfield(plugin, service);
    }

    /** 2026-10-27 照 PoE2 Spearfield（矛陣）。 */
    private final Spearfield spearfield;

    /** 給測試：矛陣插著幾支。 */
    Spearfield spearfield() {
        return spearfield;
    }

    /** 開始每 tick 跑（SkillModule 啟用時呼叫一次）。 */
    void start() {
        if (task == null) {
            task = plugin.repeat("skill.original", this::tick, 1, 1); // 2026-10-27：計時給 /ec perf 看
        }
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private State state(final Player player) {
        return states.computeIfAbsent(player.getUniqueId(), k -> new State());
    }

    private SkillDamage damage() {
        return plugin.combat() == null ? null : plugin.combat().skillDamage();
    }

    /** 這個技能在技能面板上的寶石等級（沒有回傳 0）。 */
    private int gemLevel(final Player player, final String skillId) {
        final tw.exilecore.character.SkillLoadout loadout = plugin.players().get(player).skills();
        final int position = loadout.find(skillId);
        return position < 0 || loadout.skill(position) == null ? 0 : Math.max(1, loadout.skill(position).level());
    }

    /** 常駐技能開著的話它的等級（沒開回傳 0）。 */
    private int auraLevel(final Player player, final String skillId) {
        return service.auras() != null && service.auras().isActive(player.getUniqueId(), skillId) ? gemLevel(player, skillId) : 0;
    }

    private double levelValue(final String skillId, final String stat, final int level, final double fallback) {
        for (final ScaledMod mod : service.config().levelModifiers(skillId)) {
            if (mod.stat().equals(stat)) {
                return mod.value().at(level);
            }
        }
        return fallback;
    }

    /** 隱藏技能在某個等級的組合（用玩家現在的屬性表與武器）。 */
    private SkillBuild proc(final Player player, final String skillId, final int level) {
        final SkillDefinition definition = service.config().skill(skillId);
        if (definition == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        return SkillBuild.of(service.config(), definition, Math.max(1, level), state.level(), false, List.of(), state.weaponTags(),
                plugin.players().sheet(player));
    }

    /** 2026-10-29 照 PoE2：電震波吃雷縛長棍寶石上掛的輔助（能輔助攻擊的那些）；這次之前沒有輔助。 */
    private SkillBuild staffWave(final Player player) {
        final SkillDefinition definition = service.config().skill(STAFF_WAVE);
        final SkillBuild host = service.equippedBuild(player, STAFF);
        if (definition == null || host == null) {
            return proc(player, STAFF_WAVE, gemLevel(player, STAFF));
        }
        final PlayerState state = plugin.players().get(player);
        return SkillBuild.of(service.config(), definition, Math.max(1, gemLevel(player, STAFF)), state.level(), false, host.supports(),
                state.weaponTags(), plugin.players().sheet(player));
    }

    private void blast(final Player player, final Location at, final double radius, final SkillProfile profile) {
        final SkillDamage damage = damage();
        if (damage == null || at.getWorld() == null) {
            return;
        }
        for (final LivingEntity victim : service.targets().around(at, radius, player)) {
            damage.hit(player, victim, profile, null);
        }
    }

    private static ItemDisplay marker(final Location at, final Material material, final float size) {
        if (at.getWorld() == null) {
            return null;
        }
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(material));
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
        });
    }

    private static void removeDisplay(final ItemDisplay display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private boolean monster(final LivingEntity entity) {
        return !(entity instanceof Player) && plugin.monsters() != null && plugin.monsters().data(entity).isPresent()
                && (service.minions() == null || !service.minions().isMinion(entity));
    }

    private static int rank(final MonsterRarity rarity) {
        return rarity == null ? 0 : rarity.ordinal();
    }

    // =====================================================================
    // SkillService 呼叫的入口
    // =====================================================================

    /** 2026-10-28 照 PoE2 Mantra of Destruction 的品質：需要的連擊少 (0—1)（往 0 捨去）。 */
    static int mantraCombo(final SkillBuild build) {
        return Math.max(1, MANTRA_COMBO + qualityInt(build.qualityFlat(tw.exilecore.stats.GemQualityKeys.MANTRA_COMBO)));
    }

    /** 這個 signature 是不是這裡接手的。 */
    boolean handles(final String signature) {
        return signature != null && SIGNATURES.contains(signature);
    }

    /** 放之前的檢查（花魔力之前）：不能放回傳原因。 */
    String problem(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final String signature = service.config().signature(build.definition().id());
        if ("charged_staff".equals(signature)) {
            return plugin.combat() == null || plugin.combat().charges() == null || plugin.combat().charges().count(player, "power") <= 0
                    ? "雷縛長棍要用掉銳星（身上沒有銳星）" : null;
        }
        if ("mantra".equals(signature)) {
            final int combo = service.resources().combo(player);
            final int need = mantraCombo(build);
            return combo < need ? "崩滅心咒要 " + need + " 點連擊（現在 " + combo + "）" : null;
        }
        if ("living_bomb".equals(signature)) {
            return seedTarget(player, build, preferred) == null ? "準心沒有指著敵人" : null;
        }
        return null;
    }

    private LivingEntity seedTarget(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (preferred != null && preferred.isValid() && service.targets().valid(preferred, player)) {
            return preferred;
        }
        return service.targets().aimed(Origin.of(player), Math.max(4, build.range()), 1.2);
    }

    /** 接手放出（已經付過消耗、開始冷卻）。 */
    void cast(final Player player, final SkillBuild build, final LivingEntity preferred, final long now) {
        final String signature = service.config().signature(build.definition().id());
        final State s = state(player);
        switch (signature) {
            case "charged_staff" -> chargedStaff(player, s, build, now);
            case "mantra" -> {
                service.resources().spendCombo(player, mantraCombo(build));
                s.mantraUntil = now + Math.round(MANTRA_TICKS * build.durationScale());
                s.mantraLevel = build.level();
                service.buffs().apply(player, MANTRA, build.definition().name(), (int) (s.mantraUntil - now),
                        List.of(Modifier.flat(MANTRA_MARK, 1, build.definition().name())));
                Fx.spawn(player.getWorld(), Particle.WITCH, player.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.05);
            }
            case "galvanic" -> {
                service.buffs().apply(player, GALVANIC, build.definition().name(), 20 * 60,
                        List.of(Modifier.more(StatKeys.SHOCK_CHANCE, 0.3, build.definition().name())));
                s.orbs.removeIf(orb -> orb.until <= now);
                final SkillBuild bolt = proc(player, GALVANIC_BOLT, build.level());
                if (bolt != null) {
                    pendingBolt.put(player.getUniqueId(), bolt);
                    // 2026-10-28 照 PoE2：電球的上限（1 + 過剩）與持續時間照雷流場自己的組合（含輔助）；這次之前照電球（沒有輔助）的持續時間
                    pendingGalvanic.put(player.getUniqueId(), new double[] {1 + SkillLimits.bonus(plugin.players().sheet(player), build),
                            build.durationScale()});
                }
                Fx.spawn(player.getWorld(), Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 25, 0.5, 0.8, 0.5, 0.1);
            }
            case "living_bomb" -> plantSeed(player, s, build, seedTarget(player, build, preferred), now);
            case Spearfield.SIGNATURE -> spearfield.place(player, build, now); // 2026-10-27 照 PoE2 Spearfield；這次之前是持續打的範圍
            case "calamity" -> {
                s.calamityUntil = now + Math.round(CALAMITY_TICKS * build.durationScale());
                s.calamity = build;
                s.meteors = 0;
                s.nextMeteor = now;
                // 2026-10-28 照 PoE2 Walking Calamity 的品質：增益另外獲得 (0—10)% 傷害為火焰（加在 30% 上）
                service.buffs().apply(player, CALAMITY, build.definition().name(), (int) (s.calamityUntil - now),
                        build.withQualityEffects(List.of(Modifier.flat(StatKeys.damageExtra(DamageType.FIRE), 30, build.definition().name()),
                                Modifier.flat(SkillResources.RAGE_REGEN, 5, build.definition().name())), 1.0, build.definition().name()));
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 1f, 0.7f);
            }
            default -> {
            }
        }
    }

    private final Map<UUID, SkillBuild> pendingBolt = new HashMap<>();
    /** 2026-10-28：雷流場放的時候記下 {電球上限, 持續時間倍率}。 */
    private final Map<UUID, double[]> pendingGalvanic = new HashMap<>();

    /**
     * 放之前改這一下（熱度、崩滅心咒、霜鏃、黑油雨的帶電油彈、噬咬的跳躍）：回傳改過的組合（沒改就是原本的）。
     */
    SkillBuild beforeCast(final Player player, final SkillBuild build, final LivingEntity preferred, final long now) {
        final SkillDefinition definition = build.definition();
        if (definition.hidden()) {
            return build;
        }
        final State s = state(player);
        SkillBuild out = build;
        final List<Modifier> heat = HEAT_CONSUMERS.get(definition.id());
        if (heat != null && consumeHeat(player, HEAT_COST)) {
            out = out.withProfile(out.profile().withLocal(heat)); // 照 PoE2：有 15 點熱度就用掉換加成
        }
        final PlayerState state = plugin.players().get(player);
        if (definition.isAttack() && s.mantraUntil > now && !MANTRA.equals(definition.id()) && state.weaponTags().contains("quarterstaff")) {
            final double chaos = levelValue(MANTRA, MANTRA_CHAOS, s.mantraLevel, 50);
            out = out.withProfile(out.profile().withLocal(List.of(Modifier.flat(StatKeys.damageExtra(DamageType.CHAOS), chaos, "崩滅心咒"),
                    Modifier.flat(MANTRA_MARK, 1, "崩滅心咒"))));
            s.mantraUntil = 0;
            service.buffs().remove(player, MANTRA);
        }
        if (definition.isAttack() && definition.tags().contains(Tags.PROJECTILE) && s.iceTippedLeft > 0
                && service.buffs().has(player.getUniqueId(), "guard." + ICE_TIPPED)
                && (state.weaponTags().contains("bow") || state.weaponTags().contains("spear"))) {
            out = out.withProfile(out.profile().withLocal(List.of(Modifier.flat(StatKeys.DAMAGE_CONVERT + "cold", 100, "霜鏃"),
                    Modifier.flat(ICE_TIPPED_MARK, 1, "霜鏃"))));
            if (--s.iceTippedLeft <= 0) {
                service.buffs().remove(player, "guard." + ICE_TIPPED);
            }
        }
        if (OIL.equals(definition.id())) {
            if (s.oilUntil <= now && plugin.combat() != null && plugin.combat().charges() != null
                    && plugin.combat().charges().spend(player, "power", out.profile().effective(plugin.players().sheet(player)), out.profile().tags()) > 0) {
                s.oilUntil = now + OIL_WINDOW; // 照 PoE2：每 1.5 秒用掉一顆銳星，換成帶電的油彈（40% 閃電、不留油地）
            }
            if (s.oilUntil > now) {
                // 2026-10-28 照 PoE2：帶電油彈自己的表（stage.empowered 100% → 312% → 828%）；這次之前照一般油彈的表
                out = out.withProfile(StageAttacks.stage(out.profile(), "empowered").withLocal(List.of(Modifier.flat(StatKeys.DAMAGE_CONVERT + "lightning", 40, "帶電油彈"),
                        Modifier.flat(NO_GROUND, 1, "帶電油彈"))));
            }
        }
        if (DEVOUR.equals(definition.id())) {
            devourLeap(player, out, preferred);
        }
        // 2026-10-28 照 PoE2 Earthshatter 的品質：每根還在的地刺更多 x% 擊中傷害（原作每用一次在裂縫盡頭留一根，6 秒、最多 6 根；
        //   我們的地刺崩裂沒有實體的地刺，照最近 6 秒內用了幾次算）
        final double perSpike = out.qualityFlat(tw.exilecore.stats.GemQualityKeys.MORE_PER_SPIKE);
        if (perSpike > 0) {
            s.spikes.removeIf(tick -> now - tick >= SPIKE_TICKS);
            final int alive = Math.min(SPIKE_LIMIT, s.spikes.size());
            s.spikes.addLast(now);
            while (s.spikes.size() > SPIKE_LIMIT) {
                s.spikes.pollFirst();
            }
            if (alive > 0) {
                out = out.withProfile(out.profile().scaled(1 + perSpike / 100.0 * alive, "地刺"));
            }
        }
        return out;
    }

    /** 放完之後（一般執行的技能）：鼓翼風往後推、噬咬吃屍體、交錯爪拉冰裂、熱度、雷縛長棍的震波。 */
    void afterCast(final Player player, final SkillBuild build, final LivingEntity preferred, final long now) {
        final SkillDefinition definition = build.definition();
        if (definition.hidden()) {
            return;
        }
        final State s = state(player);
        final PlayerState state = plugin.players().get(player);
        switch (definition.id()) {
            case WING -> {
                // 照 PoE2：把自己往後推
                final Vector back = SkillTargets.facing(player).multiply(-1.1).setY(0.35);
                player.setVelocity(player.getVelocity().multiply(0.2).add(back));
            }
            case DEVOUR -> devour(player, build);
            case CROSS -> pullFragments(player);
            case RAPID -> s.lastRapid = now;
            case ICE_TIPPED -> {
                s.iceTippedLeft = ICE_TIPPED_USES; // 照 PoE2：強化接下來 4 次、增益最多 20 秒
                service.buffs().apply(player, "guard." + ICE_TIPPED, definition.name(), (int) Math.round(ICE_TIPPED_TICKS * build.durationScale()),
                        List.of(Modifier.flat("ice_tipped.ready", 1, definition.name())));
            }
            case SHRED -> s.shredUse = now;
            case ESCAPE -> s.escapeUse = now;
            default -> {
            }
        }
        if (definition.isAttack() && s.staffUntil > now && state.weaponTags().contains("quarterstaff") && !STAFF.equals(definition.id())) {
            final SkillBuild wave = staffWave(player);
            if (wave != null) {
                service.executor().executeFrom(Origin.of(player), wave, preferred); // 照 PoE2：長棍攻擊放出電震波（5 格、45 度）
            }
        }
    }

    /** 常駐技能的按鍵（開著的時候按）：回盾術、骸餘饗賦予的法術；不是的回傳 null。 */
    String auraPress(final Player player, final SkillBuild build, final long now) {
        final String id = build.definition().id();
        final State s = state(player);
        final PlayerState state = plugin.players().get(player);
        if (CONVALESCENCE.equals(id)) {
            if (s.convalescenceUntil > now) {
                return "<gray>回盾術已經在作用";
            }
            if (now < s.convalescenceReady) {
                return String.format(java.util.Locale.ROOT, "<gray>回盾術冷卻中（%.1f 秒）", (s.convalescenceReady - now) / 20.0);
            }
            if (state.maxEnergyShield() <= 0 || state.energyShield() >= state.maxEnergyShield() - 1e-6) {
                return "<gray>能量護盾是滿的，不能用回盾術";
            }
            if (state.mana() + 1e-9 < CONVALESCENCE_MANA) {
                return "<red>魔力不足（回盾術要 " + (int) CONVALESCENCE_MANA + "）";
            }
            state.setMana(state.mana() - CONVALESCENCE_MANA);
            // 2026-10-28 照 PoE2 Convalescence 的品質：增益持續時間 +(0—0.5) 秒
            s.convalescenceUntil = now + Math.round((levelValue(CONVALESCENCE, CONVALESCENCE_TIME, build.level(), 3)
                    + build.qualityFlat(CONVALESCENCE_TIME)) * 20 * build.durationScale());
            state.setLastDamageTick(now - 1_000_000L); // 立刻開始回充
            Fx.spawn(player.getWorld(), Particle.END_ROD, player.getLocation().add(0, 1, 0), 20, 0.4, 0.7, 0.4, 0.03);
            plugin.players().display(player, state);
            return "<#8fd3ff>回盾術：開始回充能量護盾";
        }
        if (GRIM.equals(id)) {
            if (now < s.grimReady) {
                return "<gray>骸骨復甦冷卻中";
            }
            if (s.grimStored <= 0) {
                return "<gray>骸餘饗沒有存到生命（召喚物死在你身邊才會留下殘骸）";
            }
            s.grimReady = now + GRIM_COOLDOWN;
            final double[] budget = {s.grimStored};
            final int revived = service.auras().reviveWaiting(player.getUniqueId(), skillId -> s.grimCost.getOrDefault(skillId, 0.0), budget, now);
            s.grimStored = budget[0];
            return revived <= 0 ? "<gray>沒有正在等復活的召喚物" : "<#9fd36b>骸骨復甦：復活 " + revived + " 隻召喚物（剩 " + Math.round(s.grimStored) + " 生命）";
        }
        return null;
    }

    // =====================================================================
    // 每 tick
    // =====================================================================

    private void tick() {
        final long now = now();
        try {
            for (final Player player : plugin.getServer().getOnlinePlayers()) {
                // 2026-10-27：一個人出錯不影響別人；同一個錯一分鐘只寫一次（這次之前整輪中斷、每 tick 寫一份堆疊）
                try {
                    tickPlayer(player, now);
                } catch (final RuntimeException e) {
                    tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "照原作的技能機制（OriginalSkills）更新出錯（" + player.getName() + "）", e);
                }
            }
            fragments(now);
            if (now % 100 == 0) {
                lastHitter.values().removeIf(v -> now - (long) v[1] > 100);
                mantraHits.values().removeIf(v -> now - (long) v[1] > 100);
            }
        } catch (final RuntimeException e) {
            tw.exilecore.core.LogThrottle.error(plugin.getSLF4JLogger(), "照原作的技能機制（OriginalSkills）更新出錯", e);
        }
    }

    private void tickPlayer(final Player player, final long now) {
        final State s = states.get(player.getUniqueId());
        if (now % 10 == 3) {
            slowTick(player, now);
        }
        spearfield.tick(player, now); // 2026-10-27 照 PoE2 Spearfield：插在地上的矛
        if (now % KillingPalm.HIGHLIGHT_TICKS == 1) {
            palmHighlight(player); // 2026-10-28 照 PoE2 斷魂掌：標出身邊可以撲殺的敵人；這次之前沒有
        }
        if (s == null) {
            return;
        }
        arctic(player, s, now);
        staff(player, s, now);
        orb(player, s, now);
        seed(player, s, now);
        convalescence(player, s, now);
        calamity(player, s, now);
        caltrops(player, s, now);
        if (s.pounceTick >= 0 && now > s.pounceTick) {
            applyMark(player, s, now);
        }
    }

    /** 每 10 tick：磨蝕之志、僕骸替、熱度消退、獵手印記、凜冬之殼開關。 */
    private void slowTick(final Player player, final long now) {
        final boolean any = auraLevel(player, ARCTIC) > 0 || auraLevel(player, ATTRITION) > 0 || auraLevel(player, SACRIFICE) > 0
                || states.containsKey(player.getUniqueId());
        if (!any) {
            return;
        }
        final State s = state(player);
        attrition(player, s, now);
        sacrifice(player, s, now);
        heatDecay(player, s, now);
        markTick(player, s, now);
    }

    // ---- 斷魂掌 ----

    /** 斷魂掌標記的顏色（暗紅）。 */
    private static final Particle.DustOptions PALM_MARK = new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 16, 16), 1.4f);

    /**
     * 2026-10-28 照 PoE2 Killing Palm「你周圍的可撲殺敵人會被重點標示」：技能面板上有斷魂掌時，衝刺距離內生命已經在撲殺門檻以下的怪物
     * 發光（外框，只送給這個玩家；要裝 PacketEvents，見 {@link tw.exilecore.hook.CullGlow}）。
     * 沒裝 PacketEvents 照舊在頭上冒紅點（spawnParticle 只送給這個玩家，別人看不到）。這次之前一律是紅點。
     */
    private void palmHighlight(final Player player) {
        final boolean glow = tw.exilecore.hook.CullGlow.active();
        if (player.isDead() || plugin.monsters() == null || gemLevel(player, KillingPalm.ID) <= 0) {
            if (glow) {
                tw.exilecore.hook.CullGlow.update(player, List.of()); // 拿掉斷魂掌、死掉：外框拿掉
            }
            return;
        }
        final List<LivingEntity> cullable = new ArrayList<>();
        for (final LivingEntity near : player.getWorld().getNearbyLivingEntities(player.getLocation(), KillingPalm.DASH_RANGE + 1)) {
            if (near.isDead() || !monster(near)) {
                continue;
            }
            final org.bukkit.attribute.AttributeInstance max = near.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            final double threshold = plugin.monsters().data(near).map(tw.exilecore.combat.CombatListener::cullThreshold).orElse(0.0);
            if (max == null || !KillingPalm.cullable(near.getHealth(), max.getValue(), threshold)) {
                continue;
            }
            cullable.add(near);
        }
        if (glow) {
            tw.exilecore.hook.CullGlow.update(player, cullable);
            if (tw.exilecore.hook.CullGlow.active()) {
                return;
            }
        }
        for (final LivingEntity near : cullable) {
            final Location top = near.getLocation().add(0, near.getHeight() + 0.45, 0);
            player.spawnParticle(Particle.DUST, top, 5, 0.12, 0.08, 0.12, 0, PALM_MARK);
        }
    }

    // ---- 凜冬之殼 ----

    private void arctic(final Player player, final State s, final long now) {
        if (now % 5 != 0) {
            return;
        }
        final int level = auraLevel(player, ARCTIC);
        if (level <= 0) {
            s.arcticStages = 0;
            s.nextArcticStage = -1;
            return;
        }
        // 2026-10-28 照 PoE2 Arctic Armour 的品質：最大層數 +(0—1)（無條件捨去）
        final int max = (int) Math.round(levelValue(ARCTIC, ARCTIC_STAGES, level, 2)) + qualityInt(service.qualityFlat(player, ARCTIC, ARCTIC_STAGES));
        final long every = Math.max(2, Math.round(levelValue(ARCTIC, ARCTIC_INTERVAL, level, 1.25) * 20));
        if (s.nextArcticStage < 0 || s.arcticStages >= max) {
            s.arcticStages = Math.min(s.arcticStages, max);
            s.nextArcticStage = now + every;
        } else if (now >= s.nextArcticStage) {
            s.arcticStages++;
            s.nextArcticStage = now + every;
        }
        if (s.arcticStages > 0 && now % 20 == 0) {
            Fx.spawn(player.getWorld(), Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 2 + s.arcticStages, 0.4, 0.6, 0.4, 0.01);
        }
    }

    // ---- 雷縛長棍 ----

    private void chargedStaff(final Player player, final State s, final SkillBuild build, final long now) {
        final tw.exilecore.charge.ChargeService charges = plugin.combat().charges();
        int consumed = 0;
        final int held = charges.count(player, "power");
        final StatSheet effective = build.profile().effective(plugin.players().sheet(player));
        for (int i = 0; i < held; i++) {
            if (charges.spend(player, "power", effective, build.profile().tags()) > 0) {
                consumed++;
            }
        }
        if (consumed <= 0) {
            return;
        }
        final long add = Math.round(STAFF_TICKS_PER_CHARGE * consumed * build.durationScale());
        if (s.staffUntil > now) {
            s.staffUntil += add; // 照 PoE2：再放一次加時間與傷害
            s.staffCharges += consumed;
        } else {
            s.staffUntil = now + add;
            s.staffCharges = consumed;
        }
        final double min = levelValue(STAFF, STAFF_MIN, build.level(), 1) * s.staffCharges;
        final double max = levelValue(STAFF, STAFF_MAX, build.level(), 2) * s.staffCharges;
        final Set<String> tags = Set.of(Tags.LIGHTNING, Tags.ATTACK, "quarterstaff");
        service.buffs().apply(player, STAFF, build.definition().name(), (int) (s.staffUntil - now),
                List.of(new Modifier(StatKeys.DAMAGE_ADDED_MIN, tw.exilecore.stats.ModType.FLAT, min, tags, build.definition().name()),
                        new Modifier(StatKeys.DAMAGE_ADDED_MAX, tw.exilecore.stats.ModType.FLAT, max, tags, build.definition().name())));
        Fx.spawn(player.getWorld(), Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1, 0), 20 + 5 * consumed, 0.4, 0.8, 0.4, 0.1);
    }

    private void staff(final Player player, final State s, final long now) {
        if (s.staffUntil > 0 && now >= s.staffUntil) {
            s.staffUntil = 0;
            s.staffCharges = 0;
        }
    }

    // ---- 磨蝕之志 ----

    private void attrition(final Player player, final State s, final long now) {
        final int level = auraLevel(player, ATTRITION);
        if (level <= 0) {
            if (!s.attrition.isEmpty()) {
                s.attrition.clear();
                CULLABLE.remove(player.getUniqueId());
            }
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final double radius = tw.exilecore.combat.Presence.radius(sheet);
        final double cap = levelValue(ATTRITION, ATTRITION_CAP, level, 40);
        // 2026-10-28 照 PoE2 Attrition 的品質：撲殺需要的層數少 (0—5)
        final double cull = levelValue(ATTRITION, ATTRITION_CULL, level, 40) + service.qualityFlat(player, ATTRITION, ATTRITION_CULL);
        final Debuffs debuffs = plugin.combat() == null ? null : plugin.combat().debuffs();
        final Set<UUID> cullable = CULLABLE.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        for (final LivingEntity near : player.getWorld().getNearbyLivingEntities(player.getLocation(), radius)) {
            if (!monster(near) || near.isDead()) {
                continue;
            }
            final MonsterData data = plugin.monsters().data(near).orElse(null);
            if (data == null || data.rarity() != MonsterRarity.RARE && data.rarity() != MonsterRarity.UNIQUE && !data.boss()) {
                continue;
            }
            final double seconds = s.attrition.merge(near.getUniqueId(), 0.5, Double::sum); // 照 PoE2：在你身邊的總時間（離開不歸零）
            final double more = Math.min(cap, Math.floor(seconds / 2));
            if (debuffs != null && more > 0) {
                debuffs.apply(near.getUniqueId(), new Debuffs.Debuff("attrition." + player.getUniqueId(), player.getUniqueId(), now + 40,
                        more / 100.0, true, null));
            }
            if (seconds >= cull) {
                cullable.add(near.getUniqueId());
            }
        }
        if (s.attrition.size() > 200) {
            s.attrition.keySet().removeIf(id -> plugin.getServer().getEntity(id) == null);
            cullable.removeIf(id -> plugin.getServer().getEntity(id) == null);
        }
    }

    /** 磨蝕之志：這個玩家打這隻敵人有沒有撲殺（CombatListener 的撲殺判斷呼叫）。 */
    public static boolean culls(final LivingEntity attacker, final LivingEntity defender) {
        if (attacker == null || defender == null) {
            return false;
        }
        final Set<UUID> set = CULLABLE.get(attacker.getUniqueId());
        return set != null && set.contains(defender.getUniqueId());
    }

    // ---- 雷流場 ----

    private void orb(final Player player, final State s, final long now) {
        if (s.orbs.isEmpty()) {
            return;
        }
        for (final Orb orb : new java.util.ArrayList<>(s.orbs)) {
            if (!orbTick(player, orb, now)) {
                s.orbs.remove(orb);
            }
        }
    }

    /** 一顆電球的這一刻；回傳 false = 這顆沒了。 */
    private boolean orbTick(final Player player, final Orb orb, final long now) {
        if (now >= orb.until) {
            return false;
        }
        final Entity entity = plugin.getServer().getEntity(orb.target);
        if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
            orb.last = living.getLocation().add(0, living.getHeight() + 0.4, 0);
        }
        if (orb.last == null || orb.last.getWorld() == null || !orb.last.getWorld().equals(player.getWorld())) {
            return false;
        }
        if (now % 4 == 0) {
            Fx.spawn(orb.last.getWorld(), Particle.ELECTRIC_SPARK, orb.last, 4, 0.15, 0.15, 0.15, 0.02);
        }
        if (now < orb.next) {
            return true;
        }
        orb.next = now + GALVANIC_INTERVAL;
        final double radius = GALVANIC_RADIUS * orb.bolt.areaScale();
        LivingEntity aim = null;
        double best = Double.MAX_VALUE;
        for (final LivingEntity near : service.targets().around(orb.last, radius + 1, player)) {
            final double d = near.getLocation().distanceSquared(orb.last);
            if (d < best) {
                best = d;
                aim = near;
            }
        }
        if (aim != null) {
            final Vector direction = aim.getLocation().add(0, aim.getHeight() / 2, 0).toVector().subtract(orb.last.toVector());
            service.executor().executeFrom(Origin.at(player, orb.last.clone().subtract(0, 1.5, 0),
                    direction.lengthSquared() < 1e-6 ? new Vector(0, -1, 0) : direction.normalize()), orb.bolt, aim);
        }
        return true;
    }

    // ---- 燼種 ----

    private void plantSeed(final Player player, final State s, final SkillBuild build, final LivingEntity target, final long now) {
        if (target == null) {
            return;
        }
        final Seed seed = new Seed();
        seed.target = target.getUniqueId();
        seed.build = build;
        final MonsterData data = plugin.monsters() == null ? null : plugin.monsters().data(target).orElse(null);
        final double threshold = target instanceof Player victim ? plugin.players().get(victim).maxLife() * 0.5
                : data == null ? target.getHealth() : plugin.monsters().config().ailmentThresholdAt(Math.max(1, data.level()));
        seed.threshold = Math.max(1, threshold * SEED_THRESHOLD);
        seed.until = now + Math.round(SEED_TICKS * build.durationScale());
        // 照 PoE2：上限 1 顆（2026-10-28：+x 上限（過剩）多幾顆，滿了最舊的消失；同一隻身上重種換掉舊的）
        s.seeds.removeIf(old -> old.target.equals(seed.target));
        while (s.seeds.size() >= 1 + SkillLimits.bonus(plugin.players().sheet(player), build)) {
            s.seeds.removeFirst();
        }
        s.seeds.add(seed);
        Fx.spawn(target.getWorld(), Particle.FLAME, target.getLocation().add(0, target.getHeight() / 2, 0), 15, 0.3, 0.4, 0.3, 0.02);
    }

    private void seed(final Player player, final State s, final long now) {
        if (s.seeds.isEmpty()) {
            return;
        }
        for (final Seed seed : new java.util.ArrayList<>(s.seeds)) {
            final Entity entity = plugin.getServer().getEntity(seed.target);
            if (now >= seed.until || !(entity instanceof LivingEntity living) || !living.isValid()) {
                s.seeds.remove(seed);
                continue;
            }
            if (now % 10 == 0) {
                Fx.spawn(living.getWorld(), Particle.SMALL_FLAME, living.getLocation().add(0, living.getHeight() + 0.2, 0), 3, 0.1, 0.1, 0.1, 0.01);
            }
        }
    }

    private void explodeSeed(final Player player, final State s, final Seed seed, final Location at) {
        if (seed == null || !s.seeds.remove(seed) || at.getWorld() == null) {
            return;
        }
        blast(player, at, SEED_RADIUS * seed.build.areaScale(), seed.build.profile());
        Fx.spawn(at.getWorld(), Particle.EXPLOSION, at.clone().add(0, 0.8, 0), 2, 0.3, 0.3, 0.3, 0);
        Fx.spawn(at.getWorld(), Particle.FLAME, at.clone().add(0, 0.8, 0), 40, 1.2, 0.6, 1.2, 0.05);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.8f, 1.3f);
        // 照 PoE2：留下火焰灌注殘影
        service.chargeEffects().dropInfusionRemnant(player, at, 0, ChargeEffects.RemnantMods.of(seed.build.profile().effective(plugin.players().sheet(player)),
                seed.build.profile().tags())); // 2026-10-26：跟素痕湧同一套灌注殘骸（ChargeEffects）；2026-10-27：殘骸輔助
    }

    // ---- 回盾術 ----

    private void convalescence(final Player player, final State s, final long now) {
        if (s.convalescenceUntil <= 0) {
            return;
        }
        final PlayerState state = plugin.players().get(player);
        if (now >= s.convalescenceUntil || state.energyShield() >= state.maxEnergyShield() - 1e-6 || auraLevel(player, CONVALESCENCE) <= 0) {
            s.convalescenceUntil = 0;
            // 照 PoE2：冷卻從效果結束才開始算（效果期間不回復）
            s.convalescenceReady = now + Math.round(CONVALESCENCE_COOLDOWN / Math.max(0.1,
                    plugin.players().sheet(player).apply(StatKeys.COOLDOWN_RECOVERY, 1.0, Set.of())));
            return;
        }
        state.setLastDamageTick(now - 1_000_000L); // 照 PoE2：效果期間被打也不會打斷回充
    }

    // ---- 骸餘饗、僕骸替 ----

    private void sacrifice(final Player player, final State s, final long now) {
        final int level = auraLevel(player, SACRIFICE);
        final Corpses corpses = plugin.combat() == null ? null : plugin.combat().corpses();
        if (level <= 0 || corpses == null || service.minions() == null) {
            REVIVE_SCALE.remove(player.getUniqueId());
            for (final Corpses.Corpse corpse : s.virtualCorpses.values()) {
                if (corpses != null) {
                    corpses.remove(corpse);
                }
            }
            s.virtualCorpses.clear();
            return;
        }
        // 2026-10-28 照 PoE2 Sacrifice 的品質：召喚物復活更快 (0—5)%（加在「更慢 x%」上）
        final double slower = Math.abs(Math.min(0, levelValue(SACRIFICE, SACRIFICE_SLOWER, level, -59)
                + service.qualityFlat(player, SACRIFICE, SACRIFICE_SLOWER))) / 100.0;
        REVIVE_SCALE.put(player.getUniqueId(), 1 / Math.max(0.05, 1 - slower)); // 照 PoE2：復活慢 x%
        final Set<UUID> seen = new java.util.HashSet<>();
        for (final MinionService.Minion minion : service.minions().all()) {
            if (!minion.owner.equals(player.getUniqueId()) || !minion.spec.permanent() || !minion.mob.isValid()
                    || !tw.exilecore.passive.ConquestEffects.undead(minion.mob.getType().name())) {
                continue;
            }
            final UUID id = minion.mob.getUniqueId();
            seen.add(id);
            final Corpses.Corpse old = s.virtualCorpses.remove(id);
            final Location at = minion.mob.getLocation();
            if (old != null) {
                final boolean used = corpses.near(old.world(), old.x(), old.z(), 0.01, now).stream().noneMatch(old::equals);
                corpses.remove(old);
                if (used) {
                    // 被當成屍體用掉：這隻倒下（之後照常復活，只是更慢）
                    Fx.spawn(at.getWorld(), Particle.SOUL, at.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.03);
                    minion.mob.setHealth(0);
                    continue;
                }
            }
            final double life = minion.mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null ? 20
                    : MinionSheets.life(minion.effective == null ? minion.sheet : minion.effective);
            corpses.add(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), Math.max(1, life), now, "minion");
            for (final Corpses.Corpse corpse : corpses.near(at.getWorld().getUID(), at.getX(), at.getZ(), 0.01, now)) {
                if ("minion".equals(corpse.type())) {
                    s.virtualCorpses.put(id, corpse);
                    break;
                }
            }
        }
        s.virtualCorpses.keySet().removeIf(id -> !seen.contains(id));
    }

    /** 僕骸替：召喚物死掉之後等多久的倍率（AuraService 叫回精魂召喚物時乘上去）。 */
    public static double reviveScale(final UUID player) {
        return REVIVE_SCALE.getOrDefault(player, 1.0);
    }

    // ---- 熱度（弩：連射每發 +1，4 秒沒拿到每秒退 10；彈匣用掉的就是熱度）----

    /** 現在的熱度（連射的彈匣用掉幾發；過熱中 = 最大值）。 */
    int heat(final Player player) {
        final SkillRuntime runtime = service.runtime(player);
        final int level = gemLevel(player, RAPID);
        if (runtime == null || level <= 0) {
            return 0;
        }
        final int max = heatMax(player, level);
        if (runtime.reloadDone.containsKey(RAPID)) {
            return max;
        }
        final Integer left = runtime.ammo.get(RAPID);
        return left == null ? 0 : Math.max(0, max - left);
    }

    /**
     * 2026-10-28：資源條上的熱度（有連射寶石、熱度大於 0 或過熱中才顯示；其他回傳空字串）。
     * 這次之前熱度沒有地方看，只有射的時候動作列閃一下彈匣剩幾發。
     */
    public String heatHud(final Player player) {
        final SkillRuntime runtime = service.runtime(player);
        final int level = gemLevel(player, RAPID);
        if (runtime == null || level <= 0) {
            return "";
        }
        final Long done = runtime.reloadDone.get(RAPID);
        if (done != null) {
            return heatText(0, 0, Math.max(0, done - now()));
        }
        final int max = heatMax(player, level);
        return heatText(heat(player), max, -1);
    }

    /**
     * 2026-10-28 照 PoE2 Rapid Shot 的品質：最大熱度 +(0—15)（無條件捨去）。熱度就是連射的彈匣用掉幾發（彈匣 = 最大熱度），
     * 品質寫成 crossbow.clip，跟 SkillService.clipSize 一樣加。
     */
    private int heatMax(final Player player, final int level) {
        return service.config().clip(RAPID, level) + qualityInt(service.qualityFlat(player, RAPID, StatKeys.CROSSBOW_CLIP));
    }

    /** 熱度那一段的文字：過熱（overheatTicks ≥ 0）寫剩幾秒，不然寫 熱度 x/最大；0 點不顯示。 */
    static String heatText(final int heat, final int max, final long overheatTicks) {
        if (overheatTicks >= 0) {
            return String.format(java.util.Locale.ROOT, "<#ff3b1f>過熱 <white>%.1f<gray> 秒", overheatTicks / 20.0);
        }
        if (heat <= 0 || max <= 0) {
            return "";
        }
        final String colour = heat * 4 >= max * 3 ? "<#ff3b1f>" : heat * 2 >= max ? "<#ff8a3d>" : "<#ffc46b>";
        return colour + "熱度 <white>" + heat + "<gray>/" + max;
    }

    /** 用掉 amount 點熱度（不夠、過熱中都不用）；用掉了回傳 true。 */
    boolean consumeHeat(final Player player, final int amount) {
        final SkillRuntime runtime = service.runtime(player);
        final int level = gemLevel(player, RAPID);
        if (runtime == null || level <= 0 || runtime.reloadDone.containsKey(RAPID)) {
            return false;
        }
        final Integer left = runtime.ammo.get(RAPID);
        final int max = heatMax(player, level);
        if (left == null || max - left < amount) {
            return false;
        }
        runtime.ammo.put(RAPID, Math.min(max, left + amount));
        final PlayerState state = plugin.players().get(player);
        state.notice("<#ff8a3d>用掉 " + amount + " 點熱度（剩 " + Math.max(0, max - left - amount) + "）", now() + 30);
        return true;
    }

    private void heatDecay(final Player player, final State s, final long now) {
        if (now - s.lastRapid < HEAT_DECAY_DELAY) {
            return;
        }
        final SkillRuntime runtime = service.runtime(player);
        if (runtime == null || runtime.reloadDone.containsKey(RAPID)) {
            return;
        }
        final Integer left = runtime.ammo.get(RAPID);
        final int level = gemLevel(player, RAPID);
        if (left == null || level <= 0) {
            return;
        }
        final int max = heatMax(player, level);
        final int next = left + (int) Math.round(HEAT_DECAY_PER_SECOND / 2);
        if (next >= max) {
            runtime.ammo.remove(RAPID);
        } else {
            runtime.ammo.put(RAPID, next);
        }
        plugin.players().display(player, plugin.players().get(player)); // 2026-10-28：資源條上的熱度跟著退
    }

    // ---- 墜星熊吼 ----

    private void calamity(final Player player, final State s, final long now) {
        if (s.calamityUntil <= 0) {
            return;
        }
        if (now >= s.calamityUntil || s.calamity == null) {
            s.calamityUntil = 0;
            s.calamity = null;
            return;
        }
        if (now < s.nextMeteor) {
            return;
        }
        final List<LivingEntity> near = service.targets().around(player.getLocation(), CALAMITY_RANGE, player);
        // 照 PoE2：每 0.15 秒一顆，附近每多一個敵人頻率增加 50%（最多 200%）
        final double frequency = 1 + Math.min(2.0, 0.5 * Math.max(0, near.size() - 1));
        s.nextMeteor = now + Math.max(1, Math.round(CALAMITY_INTERVAL / frequency));
        final Location at;
        if (!near.isEmpty()) {
            at = near.get(plugin.random().nextInt(near.size())).getLocation();
        } else {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double r = plugin.random().nextDouble() * CALAMITY_RANGE;
            at = player.getLocation().add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
        }
        s.meteors++;
        final boolean large = s.meteors % CALAMITY_LARGE_EVERY == 0;
        final SkillBuild build = large ? s.calamity : proc(player, METEOR, s.calamity.level());
        if (build == null) {
            return;
        }
        final double radius = (large ? LARGE_METEOR : SMALL_METEOR) * build.areaScale();
        blast(player, at, radius, build.profile());
        Fx.spawn(at.getWorld(), Particle.FLAME, at.clone().add(0, 0.3, 0), large ? 40 : 8, radius / 2, 0.2, radius / 2, 0.03);
        Fx.spawn(at.getWorld(), Particle.LAVA, at.clone().add(0, 0.3, 0), large ? 8 : 2, radius / 3, 0.1, radius / 3, 0);
        if (large) {
            at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.8f, 0.8f);
        }
    }

    // ---- 蒺藜步 ----

    /** 翻滾時（SkillTriggers 呼叫）：照 PoE2 往兩旁撒 3 顆鐵蒺（翻滾的 5%、25%、45%）。 */
    void dodge(final Player player) {
        final int level = auraLevel(player, CALTROPS);
        if (level <= 0) {
            return;
        }
        final SkillBuild build = proc(player, CALTROP, level);
        if (build == null) {
            return;
        }
        final State s = state(player);
        final long now = now();
        // 2026-10-27 照 PoE2 Delayed Reaction／Rearm（掛在蒺藜步上）：鐵蒺是危險區域；這次之前蒺藜步的輔助對鐵蒺沒有作用
        final double delayed = service.supportFlat(player, CALTROPS, tw.exilecore.stats.SupportExtraKeys.DELAYED_REACTION);
        final double rearm = service.supportFlat(player, CALTROPS, tw.exilecore.stats.SupportExtraKeys.REARM);
        final SkillProfile profile = delayed > 0 ? build.profile().scaled(1 + delayed / 100.0, "遲爆") : build.profile();
        final long ticks = Math.round(CALTROP_TICKS * build.durationScale() * (delayed > 0 ? tw.exilecore.stats.SupportExtraKeys.DELAYED_REACTION_DURATION : 1));
        for (int i = 0; i < CALTROP_COUNT; i++) {
            final int index = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                final Vector facing = SkillTargets.facing(player);
                final Vector side = new Vector(-facing.getZ(), 0, facing.getX()).multiply((index % 2 == 0 ? 1 : -1) * (0.8 + plugin.random().nextDouble() * 0.7));
                final Location at = player.getLocation().add(side).add(0, 0.1, 0);
                while (s.caltrops.size() >= caltropMax(player)) {
                    removeDisplay(s.caltrops.removeFirst().display());
                }
                final Caltrop caltrop = new Caltrop(player.getUniqueId(), at, now() + ticks, profile, marker(at, Material.IRON_NUGGET, 0.35f),
                        now(), delayed > 0, rearm);
                if (delayed > 0 && !service.targets().around(at, CALTROP_TRIGGER + 0.3, player).isEmpty()) {
                    caltropBlast(player, s, caltrop); // 放下的那一刻就有敵人：立刻炸
                    return;
                }
                s.caltrops.add(caltrop);
            }, 1 + 2L * index);
        }
    }

    private void caltrops(final Player player, final State s, final long now) {
        if (s.caltrops.isEmpty()) {
            return;
        }
        final Iterator<Caltrop> iterator = s.caltrops.iterator();
        while (iterator.hasNext()) {
            final Caltrop caltrop = iterator.next();
            if (now >= caltrop.until() || caltrop.at().getWorld() == null || !caltrop.at().getWorld().equals(player.getWorld())) {
                removeDisplay(caltrop.display());
                iterator.remove();
                if (caltrop.delayed() && now >= caltrop.until() && caltrop.at().getWorld() != null && caltrop.at().getWorld().equals(player.getWorld())) {
                    rearmed.add(caltrop); // 2026-10-27 照 PoE2 Delayed Reaction：時間到才炸
                }
                continue;
            }
            if (caltrop.delayed() || now < caltrop.armedAt() || service.targets().around(caltrop.at(), CALTROP_TRIGGER + 0.3, player).isEmpty()) {
                continue;
            }
            // 照 PoE2：敵人碰到就炸（0.8 格）然後消失
            iterator.remove();
            rearmed.add(caltrop);
        }
        if (!rearmed.isEmpty()) {
            final List<Caltrop> boom = new ArrayList<>(rearmed);
            rearmed.clear();
            for (final Caltrop caltrop : boom) {
                caltropBlast(player, s, caltrop);
            }
        }
    }

    /** 2026-10-28 照 PoE2 Trail of Caltrops 的品質：鐵蒺上限 +(0—5)（無條件捨去）。 */
    private int caltropMax(final Player player) {
        return CALTROP_MAX + qualityInt(service.qualityFlat(player, CALTROPS, tw.exilecore.stats.GemQualityKeys.CALTROPS_MAX));
    }

    /** 2026-10-28：品質給的個數（往 0 捨去：PoE2 的整數屬性 10% 品質的 +0.5 是 0、−0.5 也是 0）。 */
    static int qualityInt(final double value) {
        return (int) (value + (value >= 0 ? 1e-9 : -1e-9));
    }

    /** 這一輪要炸的鐵蒺（炸的時候會再放回去，不能邊跑清單邊加）。 */
    private final List<Caltrop> rearmed = new ArrayList<>();

    /** 鐵蒺炸開（0.8 格）然後消失；2026-10-27 照 PoE2 Rearm：x% 機率過 1 秒在原地重新布置。 */
    private void caltropBlast(final Player player, final State s, final Caltrop caltrop) {
        blast(player, caltrop.at(), CALTROP_BLAST + 0.3, caltrop.profile());
        Fx.spawn(caltrop.at().getWorld(), Particle.CRIT, caltrop.at().clone().add(0, 0.3, 0), 8, 0.3, 0.2, 0.3, 0.1);
        removeDisplay(caltrop.display());
        if (caltrop.rearm() > 0 && plugin.random().nextDouble() * 100 < caltrop.rearm() && s.caltrops.size() < caltropMax(player)) {
            final long now = now();
            s.caltrops.add(new Caltrop(caltrop.owner(), caltrop.at(), now + Math.max(caltrop.until() - caltrop.armedAt(), 40), caltrop.profile(),
                    marker(caltrop.at(), Material.IRON_NUGGET, 0.35f), now + tw.exilecore.stats.SupportExtraKeys.REARM_TICKS, caltrop.delayed(), caltrop.rearm()));
        }
    }

    // ---- 噬咬 ----

    private void devourLeap(final Player player, final SkillBuild build, final LivingEntity preferred) {
        final double reach = Math.max(2, build.length());
        LivingEntity target = preferred != null && service.targets().valid(preferred, player) ? preferred
                : service.targets().aimed(Origin.of(player), reach + DEVOUR_LEAP, 1.2);
        Location point = target == null ? null : target.getLocation();
        if (point == null && plugin.combat() != null) {
            final Location aim = service.targets().aimPoint(Origin.of(player), reach + DEVOUR_LEAP);
            final Corpses.Corpse corpse = plugin.combat().corpses().nearest(aim.getWorld().getUID(), aim.getX(), aim.getZ(), 2.5, now());
            point = corpse == null ? null : new Location(aim.getWorld(), corpse.x(), corpse.y(), corpse.z());
        }
        if (point == null || !point.getWorld().equals(player.getWorld())) {
            return;
        }
        final Vector to = point.toVector().subtract(player.getLocation().toVector()).setY(0);
        final double distance = to.length();
        if (distance <= reach * 0.8 || distance > reach + DEVOUR_LEAP) {
            return;
        }
        // 照 PoE2：目標太遠就跳過去（4 格）再咬
        final Location land = player.getLocation().add(to.normalize().multiply(Math.min(DEVOUR_LEAP, distance - 1.5)));
        if (land.getBlock().isPassable() && land.clone().add(0, 1, 0).getBlock().isPassable()) {
            tw.exilecore.core.Teleports.glide(player, land); // 2026-10-29：保留視角（這次之前轉向目標、視角被拉走）
            Fx.spawn(player.getWorld(), Particle.CLOUD, land, 10, 0.4, 0.1, 0.4, 0.02);
        }
    }

    private void devour(final Player player, final SkillBuild build) {
        if (plugin.combat() == null) {
            return;
        }
        final Corpses corpses = plugin.combat().corpses();
        final Location front = player.getLocation().add(SkillTargets.facing(player).multiply(1.5));
        final List<Corpses.Corpse> near = new ArrayList<>(corpses.near(front.getWorld().getUID(), front.getX(), front.getZ(), 3.0, now()));
        final int eaten = Math.min(near.size(), 1 + DEVOUR_EXTRA);
        if (eaten <= 0) {
            return;
        }
        final double regen = levelValue(DEVOUR, DEVOUR_REGEN, build.level(), 10);
        // 2026-10-28 照 PoE2 Devour 的品質：每吃一具 (0—50)% 機率當作擊殺獲得藥劑與護符充能（屍體不知道稀有度，照普通怪）
        final double flaskChance = build.qualityFlat(tw.exilecore.stats.GemQualityKeys.CORPSE_FLASK_CHANCE);
        for (int i = 0; i < eaten; i++) {
            corpses.remove(near.get(i));
            if (plugin.combat().charges() != null) {
                plugin.combat().charges().grant(player, "power", 1);
            }
            if (flaskChance > 0 && plugin.flasks() != null && plugin.random().nextDouble() * 100 < flaskChance) {
                plugin.flasks().gainAsKill(player, MonsterRarity.NORMAL);
            }
        }
        // 照 PoE2：每吃一個在 1 秒內回復生命
        final double total = regen * eaten;
        for (int step = 1; step <= 4; step++) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    final PlayerState state = plugin.players().get(player);
                    state.setLife(state.life() + total / 4);
                    plugin.players().display(player, state);
                }
            }, 5L * step);
        }
        Fx.spawn(front.getWorld(), Particle.DAMAGE_INDICATOR, front.clone().add(0, 0.5, 0), 6 * eaten, 0.5, 0.3, 0.5, 0.05);
    }

    // ---- 掠影撲：獵手印記與狼 ----

    private void applyMark(final Player player, final State s, final long now) {
        s.pounceTick = -1;
        final UUID best = s.pounceBest;
        s.pounceBest = null;
        s.pounceRank = -1;
        if (best == null) {
            return;
        }
        s.marked = best;
        s.markUntil = now + MARK_TICKS;
        s.markLevel = Math.max(1, gemLevel(player, POUNCE));
        markTick(player, s, now);
    }

    private void markTick(final Player player, final State s, final long now) {
        if (s.marked == null) {
            return;
        }
        final Entity entity = plugin.getServer().getEntity(s.marked);
        if (now >= s.markUntil || !(entity instanceof LivingEntity target) || target.isDead()) {
            if (now >= s.markUntil) {
                s.marked = null;
            }
            return;
        }
        // 照 PoE2：周圍 6 格內每有一個牠的敵人（你、隊友、召喚物），受到的傷害增加 3%（最多 45%）
        int enemies = 0;
        for (final LivingEntity near : target.getWorld().getNearbyLivingEntities(target.getLocation(), MARK_RADIUS)) {
            if (near instanceof Player || service.minions() != null && service.minions().isMinion(near)) {
                enemies++;
            }
        }
        // 2026-10-28 照 PoE2 Pounce 的品質：每個敵人另外 +(0—1)%、上限另外 +(0—15)%
        final double taken = markTaken(enemies, service.qualityFlat(player, POUNCE, tw.exilecore.stats.GemQualityKeys.MARK_PER_ENEMY),
                service.qualityFlat(player, POUNCE, tw.exilecore.stats.GemQualityKeys.MARK_CAP)) / 100.0;
        if (plugin.combat() != null && plugin.combat().debuffs() != null) {
            plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("predator_mark", player.getUniqueId(), s.markUntil, taken, false, null));
        }
        Fx.spawn(target.getWorld(), Particle.ANGRY_VILLAGER, target.getLocation().add(0, target.getHeight() + 0.5, 0), 1, 0.1, 0.1, 0.1, 0);
    }

    /** 獵手印記受到的傷害增加幾 %：附近 enemies 個敵人、每個 3%（＋品質）、上限 45%（＋品質）。純函式（測試用）。 */
    static double markTaken(final int enemies, final double extraPer, final double extraCap) {
        return Math.min(MARK_CAP + extraCap, (MARK_PER_ENEMY + extraPer) * enemies);
    }

    private void summonWolves(final Player player, final State s, final boolean unique) {
        final SkillBuild wolf = proc(player, WOLF, s.markLevel);
        if (wolf == null || service.minions() == null) {
            return;
        }
        final int limit = (int) Math.round(levelValue(POUNCE, WOLF_LIMIT, s.markLevel, 4));
        final int count = unique ? 2 : 1;
        for (int i = 0; i < count && service.minions().count(player.getUniqueId(), WOLF) < limit; i++) {
            service.minions().summon(player, wolf);
        }
    }

    // ---- 冰裂 ----

    private void spawnFragments(final Player player, final Location around, final int count, final SkillBuild build, final double radius, final long now) {
        if (build == null || around.getWorld() == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double r = 0.6 + plugin.random().nextDouble() * 1.8;
            final Location at = around.clone().add(Math.cos(angle) * r, 0.2, Math.sin(angle) * r);
            fragments.add(new Fragment(player.getUniqueId(), at, now + FRAGMENT_TICKS, build.profile(), radius * build.areaScale(),
                    marker(at, Material.PRISMARINE_SHARD, 0.4f)));
        }
    }

    private void fragments(final long now) {
        if (fragments.isEmpty()) {
            return;
        }
        final Iterator<Fragment> iterator = fragments.iterator();
        while (iterator.hasNext()) {
            final Fragment fragment = iterator.next();
            if (now < fragment.explodeAt()) {
                continue;
            }
            iterator.remove();
            explodeFragment(fragment, fragment.at());
        }
    }

    private void explodeFragment(final Fragment fragment, final Location at) {
        removeDisplay(fragment.display());
        final Player owner = plugin.getServer().getPlayer(fragment.owner());
        if (owner == null || at.getWorld() == null || !owner.getWorld().equals(at.getWorld())) {
            return;
        }
        blast(owner, at, fragment.radius(), fragment.profile());
        Fx.spawn(at.getWorld(), Particle.SNOWFLAKE, at, 15, fragment.radius() / 2, 0.3, fragment.radius() / 2, 0.02);
        Fx.spawn(at.getWorld(), Particle.ITEM_SNOWBALL, at, 6, 0.3, 0.2, 0.3, 0.05);
    }

    /** 交錯爪：把 7 格內自己的冰裂拉到交叉點立刻炸開。 */
    private void pullFragments(final Player player) {
        final Location point = player.getLocation().add(SkillTargets.facing(player).multiply(2.5)).add(0, 0.3, 0);
        final Iterator<Fragment> iterator = fragments.iterator();
        final List<Fragment> pulled = new ArrayList<>();
        while (iterator.hasNext()) {
            final Fragment fragment = iterator.next();
            if (fragment.owner().equals(player.getUniqueId()) && fragment.at().getWorld() != null && fragment.at().getWorld().equals(point.getWorld())
                    && fragment.at().distanceSquared(point) <= CROSS_PULL * CROSS_PULL) {
                iterator.remove();
                pulled.add(fragment);
            }
        }
        for (final Fragment fragment : pulled) {
            explodeFragment(fragment, point);
        }
    }

    // =====================================================================
    // 事件
    // =====================================================================

    /** 讓敵人感電時（SkillTriggers.ailment 呼叫）：雷流場掛電球（素痕湧的灌注殘骸在 SkillTriggers.siphonRemnant，不在這裡）。 */
    void onAilment(final Player player, final LivingEntity target, final AilmentType type) {
        final long now = now();
        if (type == AilmentType.SHOCK && service.buffs().has(player.getUniqueId(), GALVANIC)) {
            final SkillBuild bolt = pendingBolt.remove(player.getUniqueId());
            final double[] galvanic = pendingGalvanic.remove(player.getUniqueId());
            service.buffs().remove(player, GALVANIC);
            if (bolt != null) {
                final Orb orb = new Orb();
                orb.target = target.getUniqueId();
                orb.until = now + Math.round(GALVANIC_TICKS * (galvanic != null ? galvanic[1] : bolt.durationScale()));
                orb.next = now + 2;
                orb.bolt = bolt;
                orb.last = target.getLocation().add(0, target.getHeight() + 0.4, 0);
                // 照 PoE2：上限 1 顆（2026-10-28：+x 上限（過剩）多幾顆，滿了最舊的消失）
                final List<Orb> orbs = state(player).orbs;
                while (orbs.size() >= Math.max(1, galvanic == null ? 1 : (int) galvanic[0])) {
                    orbs.removeFirst();
                }
                orbs.add(orb);
            }
        }
    }

    /** 異常被用掉時（AilmentService.cure 呼叫，怪物身上的冰凍、感電、點燃）：拾晶裝。 */
    public void ailmentConsumed(final LivingEntity entity, final AilmentType type) {
        if (entity instanceof Player || type != AilmentType.FREEZE && type != AilmentType.SHOCK && type != AilmentType.IGNITE) {
            return;
        }
        final Object[] hit = lastHitter.get(entity.getUniqueId());
        final long now = now();
        if (hit == null || now - (long) hit[1] > 2) {
            return;
        }
        final Player player = plugin.getServer().getPlayer((UUID) hit[0]);
        if (player == null) {
            return;
        }
        final int level = auraLevel(player, SHARD);
        final State s = state(player);
        if (level <= 0 || now < s.shardReady) {
            return;
        }
        s.shardReady = now + Math.round(levelValue(SHARD, SHARD_INTERVAL, level, 7) * 20);
        service.reloadNow(player, null); // 照 PoE2：立刻裝滿弩
        service.buffs().apply(player, SHARD, "拾晶裝", (int) SHARD_BUFF,
                List.of(new Modifier(StatKeys.CROSSBOW_AMMO_SAVE, tw.exilecore.stats.ModType.FLAT, 100, Set.of("crossbow"), "拾晶裝")));
        Fx.spawn(player.getWorld(), Particle.END_ROD, player.getLocation().add(0, 1.2, 0), 10, 0.3, 0.4, 0.3, 0.02);
    }

    /** 輔助的事件（SkillService.procEvent 呼叫）：鼓翼風重暈時的震波。 */
    void event(final Player player, final String skillId, final String event, final LivingEntity target) {
        if (WING.equals(skillId) && "stun_applied".equals(event) && target != null) {
            final SkillBuild wave = proc(player, WING_WAVE, gemLevel(player, WING));
            if (wave == null) {
                return;
            }
            final boolean unique = plugin.monsters() != null && plugin.monsters().data(target).map(d -> d.rarity() == MonsterRarity.UNIQUE).orElse(false);
            // 照 PoE2：重暈蓄勢待暈的敵人時炸出 2 格震波（傳奇更多 50%）
            blast(player, target.getLocation(), tw.exilecore.core.Units.metres(2.0) * wave.areaScale(), /* 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算 */ unique ? wave.profile().scaled(1.5, "傳奇") : wave.profile());
            Fx.spawn(target.getWorld(), Particle.SWEEP_ATTACK, target.getLocation().add(0, 0.6, 0), 6, 1, 0.2, 1, 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        final long now = now();
        if (event.defender() instanceof Player player && !(event.attacker() instanceof Player) && event.result().hit()) {
            arcticBurst(player, event.attacker(), event.skill(), now);
        }
        if (!(event.attacker() instanceof Player player) || !event.result().hit() || event.skill() == null) {
            return;
        }
        final LivingEntity defender = event.defender();
        lastHitter.put(defender.getUniqueId(), new Object[] {player.getUniqueId(), now});
        final State s = states.get(player.getUniqueId());
        final SkillProfile skill = event.skill();
        if (hasMark(skill, MANTRA_MARK)) {
            mantraHits.put(defender.getUniqueId(), new Object[] {player.getUniqueId(), now});
        }
        // 燼種：被種的敵人受到的傷害
        for (final State other : states.values()) {
            for (final Seed seed : other.seeds) {
                if (!seed.target.equals(defender.getUniqueId())) {
                    continue;
                }
                seed.taken += event.result().total() > 0 ? event.result().total() : event.result().dealt().total();
                if (seed.taken >= seed.threshold) {
                    final Player owner = ownerOf(other);
                    if (owner != null) {
                        final Location at = defender.getLocation();
                        plugin.getServer().getScheduler().runTask(plugin, () -> explodeSeed(owner, other, seed, at));
                    }
                }
            }
        }
        if (s == null) {
            state(player);
        }
        final State mine = state(player);
        final String id = baseId(skill.id());
        if (POUNCE.equals(id) && monster(defender)) {
            final int rank = plugin.monsters().data(defender).map(d -> rank(d.rarity())).orElse(0);
            if (mine.pounceTick != now || rank > mine.pounceRank) {
                mine.pounceTick = now;
                mine.pounceRank = rank;
                mine.pounceBest = defender.getUniqueId();
            }
        }
        if (SHRED.equals(id) && mine.shredUse >= 0 && plugin.ailments() != null && plugin.ailments().isFrozen(defender)) {
            mine.shredUse = -1; // 照 PoE2：每次使用一次
            spawnFragments(player, defender.getLocation(), SHRED_FRAGMENTS, proc(player, FRAGMENT_SHRED, gemLevel(player, SHRED)), FRAGMENT_RADIUS, now);
        }
        if (ESCAPE.equals(id) && mine.escapeUse >= 0) {
            final int level = gemLevel(player, ESCAPE);
            mine.escapeUse = -1;
            spawnFragments(player, defender.getLocation(), (int) Math.round(levelValue(ESCAPE, ESCAPE_FRAGMENTS, level, 8)),
                    proc(player, FRAGMENT_ESCAPE, level), tw.exilecore.core.Units.metres(2.0), now); // 2026-10-29 照站長：1 公尺 = 1.5 格（Units），照 PoE2 公尺的常數也換算
        }
        if (hasMark(skill, ICE_TIPPED_MARK) && now >= mine.fragmentReady) {
            mine.fragmentReady = now + 3;
            spawnFragments(player, defender.getLocation(), ICE_TIPPED_FRAGMENTS, proc(player, FRAGMENT_ARROW, gemLevel(player, ICE_TIPPED)),
                    FRAGMENT_RADIUS, now);
        }
    }

    private Player ownerOf(final State target) {
        for (final Map.Entry<UUID, State> entry : states.entrySet()) {
            if (entry.getValue() == target) {
                return plugin.getServer().getPlayer(entry.getKey());
            }
        }
        return null;
    }

    private static boolean hasMark(final SkillProfile skill, final String mark) {
        for (final Modifier modifier : skill.local()) {
            if (mark.equals(modifier.stat())) {
                return true;
            }
        }
        return false;
    }

    /** 技能描述的代號（召喚物、地面效果會在後面加 .minion、.zone）。 */
    private static String baseId(final String id) {
        final int dot = id == null ? -1 : id.indexOf('.');
        return dot < 0 ? id : id.substring(0, dot);
    }

    private void arcticBurst(final Player player, final LivingEntity attacker, final SkillProfile skill, final long now) {
        final State s = states.get(player.getUniqueId());
        if (s == null || s.arcticStages <= 0 || now < s.arcticReady || skill == null || !skill.tags().contains(Tags.MELEE)) {
            return;
        }
        final int level = auraLevel(player, ARCTIC);
        final SkillBuild burst = level <= 0 ? null : proc(player, ARCTIC_BURST, level);
        if (burst == null) {
            return;
        }
        final int stages = s.arcticStages;
        s.arcticStages--;
        s.arcticReady = now + ARCTIC_BURST_COOLDOWN;
        final SkillDamage damage = damage();
        if (damage != null && attacker.isValid()) {
            // 照 PoE2：每層一份冰冷法術傷害（用掉一層）
            damage.hit(player, attacker, burst.profile().scaled(stages, "冰甲層數"), null);
        }
        Fx.spawn(attacker.getWorld(), Particle.SNOWFLAKE, attacker.getLocation().add(0, 1, 0), 20, 0.5, 0.6, 0.5, 0.05);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        final long now = now();
        // 燼種：被種的敵人死掉時爆開
        for (final State s : states.values()) {
            for (final Seed seed : new java.util.ArrayList<>(s.seeds)) {
                if (seed.target.equals(dead.getUniqueId())) {
                    final Player owner = ownerOf(s);
                    if (owner != null) {
                        explodeSeed(owner, s, seed, dead.getLocation());
                    }
                }
            }
        }
        // 崩滅心咒：被強化的那一下打死 → 紫焰
        final Object[] mantra = mantraHits.remove(dead.getUniqueId());
        if (mantra != null && now - (long) mantra[1] <= 20) {
            final Player player = plugin.getServer().getPlayer((UUID) mantra[0]);
            if (player != null) {
                service.buffs().apply(player, "purple_flames", "紫焰", (int) PURPLE_FLAMES_TICKS,
                        List.of(Modifier.flat(StatKeys.damageExtra(DamageType.CHAOS), PURPLE_FLAMES, "紫焰")));
            }
        }
        // 獵手印記：被標記的敵人死掉 → 叫狼
        for (final Map.Entry<UUID, State> entry : states.entrySet()) {
            final State s = entry.getValue();
            if (dead.getUniqueId().equals(s.marked) && now < s.markUntil) {
                s.marked = null;
                final Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    final boolean unique = plugin.monsters() != null && plugin.monsters().data(dead).map(d -> d.rarity() == MonsterRarity.UNIQUE).orElse(false);
                    summonWolves(player, s, unique);
                }
            }
        }
    }

    /** 骸餘饗：身邊的召喚物死掉留下殘骸（存 35% 最大生命）。召喚物模組收掉牠之前先看（LOWEST）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMinionDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        if (service.minions() != null && service.minions().isMinion(dead)) {
            final Player owner = service.minions().owner(dead);
            final int level = owner == null ? 0 : auraLevel(owner, GRIM);
            if (owner != null && level > 0 && owner.getWorld().equals(dead.getWorld())) {
                final StatSheet minionSheet = service.minions().sheet(dead);
                final double life = minionSheet == null ? 0 : MinionSheets.life(minionSheet);
                final State s = state(owner);
                final String skill = service.minions().skillOf(dead);
                if (skill != null && life > 0) {
                    s.grimCost.put(skill, life);
                }
                final double radius = tw.exilecore.combat.Presence.radius(plugin.players().sheet(owner));
                if (life > 0 && owner.getLocation().distanceSquared(dead.getLocation()) <= radius * radius) {
                    // 2026-10-28 照 PoE2 Grim Feast 的品質：殘骸另外存召喚物最大生命的 (0—5)%
                    final double stored = life * (GRIM_SHARE + service.qualityFlat(owner, GRIM, tw.exilecore.stats.GemQualityKeys.GRIM_SHARE) / 100.0);
                    final double cap = levelValue(GRIM, GRIM_CAP, level, 47);
                    service.chargeEffects().dropCustomRemnant(owner, dead.getLocation().add(0, 0.4, 0), Material.BONE_MEAL,
                            times -> state(owner).grimStored = Math.min(cap, state(owner).grimStored + stored * times)); // 2026-10-26：共用殘影（ChargeEffects）
                }
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        spearfield.forget(event.getPlayer().getUniqueId());
        tw.exilecore.hook.CullGlow.forget(event.getPlayer().getUniqueId()); // 2026-10-28 斷魂掌的外框
        final State s = states.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            for (final Caltrop caltrop : s.caltrops) {
                removeDisplay(caltrop.display());
            }
            if (plugin.combat() != null) {
                for (final Corpses.Corpse corpse : s.virtualCorpses.values()) {
                    plugin.combat().corpses().remove(corpse);
                }
            }
        }
        CULLABLE.remove(event.getPlayer().getUniqueId());
        REVIVE_SCALE.remove(event.getPlayer().getUniqueId());
        pendingBolt.remove(event.getPlayer().getUniqueId());
        pendingGalvanic.remove(event.getPlayer().getUniqueId());
    }

    /** 給技能面板、測試：凜冬之殼現在幾層。 */
    int arcticStages(final Player player) {
        final State s = states.get(player.getUniqueId());
        return s == null ? 0 : s.arcticStages;
    }

    /** 給測試：世界還沒準備好時也能呼叫的純計算。 */
    static double calamityInterval(final int enemies) {
        return CALAMITY_INTERVAL / (1 + Math.min(2.0, 0.5 * Math.max(0, enemies - 1)));
    }

    /** 給測試：磨蝕之志在身邊幾秒、上限 cap 時的更多傷害 %。 */
    static double attritionMore(final double seconds, final double cap) {
        return Math.min(cap, Math.floor(seconds / 2));
    }

    /** 火油（SkillZones 的未點燃火油）：技能描述上某個數值（oil.slow、oil.exposure；沒寫是 0）。 */
    static double oilValue(final SkillProfile profile, final String stat) {
        double total = 0;
        if (profile != null) {
            for (final Modifier modifier : profile.local()) {
                if (stat.equals(modifier.stat())) {
                    total += modifier.value();
                }
            }
        }
        return total;
    }

    /** 2026-10-26 照 PoE2：火油讓裡面的敵人曝露（元素抗性 −x%，吃曝露效果；oil.exposure 沒寫就不給）。 */
    static void oilExposure(final ExileCore plugin, final Player owner, final LivingEntity target, final SkillProfile profile, final long ticks) {
        final double amount = oilValue(profile, OIL_EXPOSURE);
        if (amount <= 0 || plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(owner);
        final double scaled = amount * Math.max(0, 1 + sheet.increased(StatKeys.EXPOSURE_EFFECT, Set.of()));
        final List<Modifier> mods = new ArrayList<>();
        for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
            mods.add(Modifier.flat(type.mitigationStat(), -scaled * tw.exilecore.passive.ConquestEffects.exposureScale(sheet, type), "火油曝露"));
        }
        plugin.combat().debuffs().apply(target.getUniqueId(), new Debuffs.Debuff("oil_exposure", owner.getUniqueId(),
                plugin.getServer().getCurrentTick() + ticks, 0, false, null, mods, 0));
    }
}
