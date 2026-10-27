package tw.exilecore.monster;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.item.AffixMod;
import tw.exilecore.stats.DamageType;
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
import java.util.random.RandomGenerator;

/**
 * 2026-10-23：PoE2 稀有怪詞綴的特殊行為（monsters.yml 的詞綴寫 {@code monster.*} 的屬性）。
 * <ul>
 *   <li>再生的：每秒回復 2% 最大生命。護盾的：多一層護盾（先扣，4 秒沒被打開始回充）。</li>
 *   <li>熔盾的：熔岩護盾吸收傷害，被打破時爆炸（火焰），8 秒後回來。</li>
 *   <li>阻礙的：附近的玩家一直被阻礙。時滯的：時間泡泡裡的玩家移動、攻擊、施法都變慢，冷卻回復與身上的減益消退慢一半。</li>
 *   <li>焚跡的、冰跡的、雷跡的：走過的地上留下點燃／冰緩／感電的地面。</li>
 *   <li>癒療的：定期治療附近的怪物。爆屍的：引爆目標附近的屍體。喚靈的：讓附近的屍體爬起來。</li>
 *   <li>竊瓶的：附近玩家的藥劑一直被吸走充能。抑生的：附近玩家生命回復不能超過 50%。</li>
 *   <li>幻雷的：被打中時有機率放出追人的閃電幻影。噬魔的：周圍一圈吸魔力、閃電傷害（中間安全）。</li>
 *   <li>近身的：只有近身的攻擊打得到牠。噬魂的：附近的怪物死掉時吞魂（招式更快、受到的傷害更少）。</li>
 * </ul>
 * 每 2 tick 跑一次（幻影、屍爆、抑生要準），其他每 0.5／1 秒。
 */
public final class MonsterMechanics implements Listener {

    /** 統御的：小怪帶這條隱藏的詞綴（monsters.yml，min_level 很高，不會自然抽到）。 */
    public static final String EMPOWERED_MINION = "empowered_minion";

    static final double SOUL_RADIUS = 15;
    static final int SOUL_MAX = 50;
    static final long SHIELD_RECHARGE_DELAY = 80;
    static final long MAGMA_RETURN = 160;
    static final double MAGMA_RADIUS = 4;
    static final double MIRAGE_CHANCE = 0.25;
    static final long MIRAGE_COOLDOWN = 40;
    static final double MIRAGE_SPEED = 0.3;
    static final double SIPHON_INNER = 3;
    static final double SIPHON_OUTER = 7;

    private final ExileCore plugin;
    private final MonsterService monsters;
    private final MonsterEffects effects;
    private final RandomGenerator random;

    private final Map<UUID, Tracked> tracked = new HashMap<>();
    /** 2026-10-23：吞魂後要重算屬性表的（一個 tick 裡死很多隻只重算一次）。 */
    private final java.util.Set<UUID> soulDirty = new java.util.HashSet<>();
    private final Map<UUID, Pool> pools = new HashMap<>();
    private final Map<UUID, Integer> souls = new HashMap<>();
    private final Map<UUID, Location> lastTrail = new HashMap<>();
    private final List<Patch> patches = new ArrayList<>();
    private final List<Mirage> mirages = new ArrayList<>();
    private final List<Blast> blasts = new ArrayList<>();
    private final Map<UUID, Map<String, Long>> nextAction = new HashMap<>(); // 2026-10-23：用怪物本身的 UUID 當鍵，forget() 才拿得掉
    /** 2026-10-23：時間泡泡這一輪已經套過的玩家（好幾個泡泡疊在一起只算一次，不然減益、冷卻永遠不會結束）。 */
    private final Map<UUID, Long> bubbled = new HashMap<>();
    private final Map<UUID, Long> mirageReady = new HashMap<>();
    private final Map<UUID, Double> lastLife = new HashMap<>();
    private final Map<UUID, Double> lastMana = new HashMap<>();
    private final Map<UUID, Long> farWord = new HashMap<>();
    private final Map<String, SkillProfile> profiles = new HashMap<>();
    private long ticks;

    /**
     * 2026-10-23：一隻怪的特殊詞綴數值，每秒才重讀一次屬性表（以前每 2 tick 對每隻怪讀一次怪物資料與屬性表）。
     */
    record Traits(double regen, double hinder, double bubble, boolean trailFire, boolean trailCold, boolean trailLightning,
                  boolean siphon, double heal, boolean corpse, boolean revive, boolean flaskSiphon, double recoveryCap,
                  boolean soulEater, boolean pool) {

        static final Traits NONE = new Traits(0, 0, 0, false, false, false, false, 0, false, false, false, 0, false, false);

        static Traits of(final StatSheet sheet) {
            final Set<String> none = Set.of();
            return new Traits(sheet.flat(StatKeys.MONSTER_REGEN, none), sheet.flat(StatKeys.MONSTER_HINDER_AURA, none),
                    sheet.flat(StatKeys.MONSTER_TEMPORAL_BUBBLE, none), sheet.flat(StatKeys.MONSTER_TRAIL_FIRE, none) > 0,
                    sheet.flat(StatKeys.MONSTER_TRAIL_COLD, none) > 0, sheet.flat(StatKeys.MONSTER_TRAIL_LIGHTNING, none) > 0,
                    sheet.flat(StatKeys.MONSTER_MANA_SIPHON, none) > 0, sheet.flat(StatKeys.MONSTER_HEAL_ALLIES, none),
                    sheet.flat(StatKeys.MONSTER_CORPSE_EXPLODE, none) > 0, sheet.flat(StatKeys.MONSTER_REVIVE, none) > 0,
                    sheet.flat(StatKeys.MONSTER_FLASK_SIPHON, none) > 0, sheet.flat(StatKeys.MONSTER_RECOVERY_CAP, none),
                    sheet.flat(StatKeys.MONSTER_SOUL_EATER, none) > 0,
                    sheet.flat(StatKeys.MONSTER_SHIELD, none) > 0 || sheet.flat(StatKeys.MONSTER_MAGMA, none) > 0);
        }

        boolean trails() {
            return trailFire || trailCold || trailLightning;
        }
    }

    /** 追蹤中的一隻怪。 */
    private static final class Tracked {
        final LivingEntity entity;
        Traits traits = Traits.NONE;
        long readAt = Long.MIN_VALUE;

        Tracked(final LivingEntity entity) {
            this.entity = entity;
        }
    }

    /** 這隻的特殊詞綴（每 20 tick 重讀一次；讀不到怪物資料回傳 null）。 */
    private Traits traits(final Tracked entry, final long now) {
        if (now - entry.readAt >= 20) {
            final StatSheet sheet = sheetOf(entry.entity);
            if (sheet == null) {
                return null;
            }
            entry.traits = Traits.of(sheet);
            entry.readAt = now;
        }
        return entry.traits;
    }

    /** 護盾（護盾的、護盾光環）與熔岩護盾的剩餘量（我們的傷害單位）。 */
    private static final class Pool {
        double shieldMax;
        double shield;
        double magmaMax;
        double magma;
        long lastHit;
        long magmaBack;
    }

    /** 足跡：一小塊點燃／冰緩／感電的地面。 */
    private record Patch(UUID world, double x, double y, double z, AilmentType type, double value, long until, double radius, String owner) {
        Patch(final UUID world, final double x, final double y, final double z, final AilmentType type, final double value, final long until) {
            this(world, x, y, z, type, value, until, 1.3, null);
        }
    }

    /** 地圖的地面效果（2026-10-26 PoE2 換界石）：不會被足跡擠掉；owner 是實例代號，關掉時一起收。 */
    private final List<Patch> mapPatches = new ArrayList<>();

    /** 放一塊地圖的地面效果（點燃 value = 每秒火焰傷害、冰緩 = 冰緩幅度、感電 = 感電幅度），半徑 radius 格。 */
    public void addMapPatch(final String owner, final Location at, final AilmentType type, final double value, final double radius,
                            final long until) {
        mapPatches.add(new Patch(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(), type, value, until, radius, owner));
    }

    /** 收掉某個實例的地面效果。 */
    public void clearMapPatches(final String owner) {
        mapPatches.removeIf(patch -> owner.equals(patch.owner()));
    }

    /** 閃電幻影：追著玩家跑，碰到就炸。 */
    private static final class Mirage {
        final UUID source;
        final UUID target;
        Location at;
        final long until;

        Mirage(final UUID source, final UUID target, final Location at, final long until) {
            this.source = source;
            this.target = target;
            this.at = at;
            this.until = until;
        }
    }

    /** 屍爆：標好位置，時間到炸開。 */
    private record Blast(UUID source, Location at, long fuse, double radius, double damage) {
    }

    MonsterMechanics(final ExileCore plugin, final MonsterService monsters, final MonsterEffects effects) {
        this.plugin = plugin;
        this.monsters = monsters;
        this.effects = effects;
        this.random = plugin.items().random();
        monsters.addAugmenter(this::augment);
    }

    /** 詞綴裡有 monster.* 的屬性就追蹤（光環給的護盾不用追蹤，被打時才建）。 */
    void track(final LivingEntity entity, final MonsterData data) {
        for (final String modId : data.mods()) {
            final MonsterConfig.MonsterMod mod = monsters.config().mod(modId);
            if (mod == null) {
                continue;
            }
            for (final AffixMod stat : mod.mods()) {
                if (stat.stat().startsWith(StatKeys.MONSTER_PREFIX)) {
                    tracked.put(entity.getUniqueId(), new Tracked(entity));
                    return;
                }
            }
        }
    }

    void forget(final UUID uuid) {
        tracked.remove(uuid);
        soulDirty.remove(uuid);
        pools.remove(uuid);
        souls.remove(uuid);
        lastTrail.remove(uuid);
        nextAction.remove(uuid);
        mirageReady.remove(uuid);
    }

    /** 噬魂的：吞了幾層，招式就快幾 %。 */
    private void augment(final LivingEntity entity, final StatSheet sheet) {
        final Integer stacks = souls.get(entity.getUniqueId());
        if (stacks != null && stacks > 0) {
            sheet.add(Modifier.increased(StatKeys.SKILL_COOLDOWN, -0.01 * stacks, "吞魂"));
        }
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private StatSheet sheetOf(final LivingEntity entity) {
        final MonsterData data = monsters.data(entity).orElse(null);
        return data == null ? null : monsters.sheet(entity, data);
    }

    // ---- 被打 ----

    /**
     * 玩家（或他的召喚物）打中怪物、還沒扣血之前：近身的（太遠打不到）、噬魂的（受到的傷害少）、熔岩護盾、護盾，
     * 以及幻雷的（被打中放幻影）。回傳實際要扣的傷害（我們的單位）。
     */
    public double incoming(final LivingEntity defender, final MonsterData data, final LivingEntity attacker, final Player credit,
                           final double amount) {
        return incoming(defender, data, attacker, credit, amount, Runnable::run, true);
    }

    /**
     * 2026-10-23：{@code after} 收「事件確定沒被取消才做」的事（扣護盾、熔岩爆炸、幻雷）；持續傷害直接跑（{@code Runnable::run}）。
     * {@code hit} = false 是持續傷害：不會觸發幻雷。
     */
    public double incoming(final LivingEntity defender, final MonsterData data, final LivingEntity attacker, final Player credit,
                           final double amount, final java.util.function.Consumer<Runnable> after, final boolean hit) {
        if (amount <= 0 || data == null) {
            return amount;
        }
        final StatSheet sheet = monsters.sheet(defender, data);
        final long now = now();
        final double proximal = sheet.flat(StatKeys.MONSTER_PROXIMAL, Set.of());
        if (proximal > 0 && attacker != null && attacker.getWorld().equals(defender.getWorld())
                && attacker.getLocation().distanceSquared(defender.getLocation()) > proximal * proximal) {
            if (credit != null && farWord.getOrDefault(credit.getUniqueId(), 0L) <= now && plugin.combat() != null
                    && plugin.combat().numbers() != null) {
                plugin.combat().numbers().word(credit, defender, "太遠");
                farWord.put(credit.getUniqueId(), now + 20);
            }
            return 0;
        }
        double rest = amount / MonsterSheets.effectiveness(sheet); // 2026-10-26（PoE2）：怪物效能 = 韌性，受到的傷害照倍率變少
        final Integer stacks = souls.get(defender.getUniqueId());
        if (stacks != null && stacks > 0) {
            rest *= Math.max(0, 1 - 0.01 * stacks);
        }
        final Pool pool = pool(defender, sheet);
        if (pool != null) {
            // 2026-10-23：先算擋掉多少，真的扣護盾等事件確定沒被取消（after）
            final double magmaTaken = Math.min(pool.magma, rest);
            rest -= magmaTaken;
            final double shieldTaken = rest > 0 ? Math.min(pool.shield, rest) : 0;
            rest -= shieldTaken;
            if (magmaTaken > 0 || shieldTaken > 0) {
                after.accept(() -> {
                    pool.lastHit = now();
                    if (magmaTaken > 0 && pool.magma > 0) {
                        pool.magma = Math.max(0, pool.magma - magmaTaken);
                        if (pool.magma <= 1e-6) {
                            pool.magma = 0;
                            pool.magmaBack = now() + MAGMA_RETURN;
                            magmaBurst(defender);
                        }
                    }
                    if (shieldTaken > 0) {
                        pool.shield = Math.max(0, pool.shield - shieldTaken);
                        if (defender.isValid()) {
                            defender.getWorld().spawnParticle(Particle.ENCHANTED_HIT, defender.getLocation().add(0, defender.getHeight() * 0.6, 0),
                                    4, 0.3, 0.3, 0.3, 0.05);
                        }
                    }
                });
            } else {
                after.accept(() -> pool.lastHit = now());
            }
        }
        if (hit && credit != null && sheet.flat(StatKeys.MONSTER_MIRAGE, Set.of()) > 0 && mirageReady.getOrDefault(defender.getUniqueId(), 0L) <= now
                && random.nextDouble() < MIRAGE_CHANCE) {
            mirageReady.put(defender.getUniqueId(), now + MIRAGE_COOLDOWN);
            after.accept(() -> {
                if (defender.isValid()) {
                    mirages.add(new Mirage(defender.getUniqueId(), credit.getUniqueId(), defender.getLocation().add(0, 1, 0), now() + 100));
                    defender.getWorld().playSound(defender.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.7f, 1.4f);
                }
            });
        }
        return rest;
    }

    /** 這隻的護盾、熔岩護盾（沒有回傳 null）；第一次用時照最大生命建好。 */
    private Pool pool(final LivingEntity entity, final StatSheet sheet) {
        Pool pool = pools.get(entity.getUniqueId());
        final double life = Math.max(1, sheet.get(StatKeys.LIFE_MAX));
        final double shieldMax = life * Math.max(0, sheet.flat(StatKeys.MONSTER_SHIELD, Set.of())) / 100.0;
        final double magmaMax = life * Math.max(0, sheet.flat(StatKeys.MONSTER_MAGMA, Set.of())) / 100.0;
        if (shieldMax <= 0 && magmaMax <= 0) {
            return pool;
        }
        if (pool == null) {
            pool = new Pool();
            pool.shield = shieldMax;
            pool.magma = magmaMax;
            pools.put(entity.getUniqueId(), pool);
        }
        pool.shieldMax = shieldMax;
        pool.magmaMax = magmaMax;
        pool.shield = Math.min(pool.shield, shieldMax);
        return pool;
    }

    private void magmaBurst(final LivingEntity source) {
        final Location at = source.getLocation();
        at.getWorld().spawnParticle(Particle.LAVA, at.clone().add(0, 0.5, 0), 30, MAGMA_RADIUS / 2, 0.5, MAGMA_RADIUS / 2, 0.1);
        at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 0.5, 0), 40, MAGMA_RADIUS / 2, 0.3, MAGMA_RADIUS / 2, 0.05);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
        for (final Player player : at.getNearbyPlayers(MAGMA_RADIUS)) {
            hit(source, player, "magma_burst", DamageType.FIRE, 2.0);
        }
    }

    // ---- 死亡 ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity dead = event.getEntity();
        final UUID id = dead.getUniqueId();
        forget(id);
        if (dead instanceof Player || !monsters.isMonster(dead)) {
            return;
        }
        final long tick = now();
        for (final Tracked entry : tracked.values()) {
            final LivingEntity eater = entry.entity;
            if (!entry.traits.soulEater() && entry.readAt != Long.MIN_VALUE) {
                continue; // 2026-10-23：先看快取的詞綴（不是噬魂的就不算距離）
            }
            if (!eater.isValid() || eater.isDead() || !eater.getWorld().equals(dead.getWorld())
                    || eater.getLocation().distanceSquared(dead.getLocation()) > SOUL_RADIUS * SOUL_RADIUS) {
                continue;
            }
            final Traits traits = traits(entry, tick);
            if (traits == null || !traits.soulEater()) {
                continue;
            }
            final int now = souls.merge(eater.getUniqueId(), 1, Integer::sum);
            if (now > SOUL_MAX) {
                souls.put(eater.getUniqueId(), SOUL_MAX);
            } else {
                soulDirty.add(eater.getUniqueId()); // 招式速度跟著變（下一次 tick 一起重算）
            }
            eater.getWorld().spawnParticle(Particle.SOUL, dead.getLocation().add(0, 0.8, 0), 6, 0.2, 0.3, 0.2, 0.03);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        lastLife.remove(event.getPlayer().getUniqueId());
        lastMana.remove(event.getPlayer().getUniqueId());
        farWord.remove(event.getPlayer().getUniqueId());
        bubbled.remove(event.getPlayer().getUniqueId());
    }

    // ---- 每 2 tick ----

    public void tick() {
        ticks++;
        final long now = now();
        if (!soulDirty.isEmpty()) {
            for (final UUID uuid : soulDirty) {
                monsters.invalidate(uuid);
            }
            soulDirty.clear();
        }
        tickMirages(now);
        tickBlasts(now);
        tickCaps(now);
        if (ticks % 5 == 0) {
            tickPatches(now);
            for (final Iterator<Tracked> iterator = tracked.values().iterator(); iterator.hasNext(); ) {
                final Tracked entry = iterator.next();
                final LivingEntity monster = entry.entity;
                if (!monster.isValid() || monster.isDead()) {
                    iterator.remove();
                    continue;
                }
                final Traits traits = traits(entry, now);
                if (traits == null) {
                    iterator.remove();
                    continue;
                }
                try {
                    halfSecond(monster, traits, now);
                } catch (final RuntimeException e) {
                    plugin.getSLF4JLogger().warn("怪物詞綴的特殊行為出錯（{}）", monster.getUniqueId(), e);
                    iterator.remove();
                }
            }
            tickPools(now);
        }
        if (ticks % 100 == 0) {
            pools.keySet().removeIf(uuid -> !(plugin.getServer().getEntity(uuid) instanceof LivingEntity living) || !living.isValid());
            souls.keySet().removeIf(uuid -> !(plugin.getServer().getEntity(uuid) instanceof LivingEntity living) || !living.isValid());
        }
    }

    /** 每 0.5 秒：一隻帶特殊詞綴的怪物。 */
    private void halfSecond(final LivingEntity monster, final Traits traits, final long now) {
        final Location at = monster.getLocation();
        final World world = at.getWorld();
        // 再生的：每秒（每兩次）回復 x% 最大生命
        if (traits.regen() > 0 && ticks % 10 == 0 && !tw.exilecore.ascendancy.AscendancyEffects.cursedNoRegen(monster)) { // 2026-10-26（昇華・血肉低語）
            final AttributeInstance max = monster.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && monster.getHealth() < max.getValue()) {
                // 2026-10-26（淵握戒）：深淵消耗中生命回復速度減少 50%
                double wasting = plugin.combat() != null && plugin.combat().debuffs()
                        .has(monster.getUniqueId(), tw.exilecore.combat.KeystoneEffects.NO_REGEN_ID, now) ? 0 // 2026-10-26 天賦樹：流血／完全破甲的敵人不能再生
                        : plugin.combat() != null && plugin.combat().debuffs()
                        .has(monster.getUniqueId(), tw.exilecore.item.unique.UniqueMechanics.WASTING_ID, now) ? 0.5 : 1.0;
                if (plugin.combat() != null) {
                    // 2026-10-26 照 PoE2 天賦樹：詛咒帶的「生命再生速度減少 x%」
                    double rate = 0;
                    for (final tw.exilecore.stats.Modifier mod : plugin.combat().debuffs().modifiers(monster.getUniqueId(), null, now)) {
                        if (tw.exilecore.stats.TreeEffectKeys.REGEN_RATE.equals(mod.stat()) && mod.type() == tw.exilecore.stats.ModType.INCREASED) {
                            rate += mod.value();
                        }
                    }
                    wasting *= Math.max(0, 1 + rate);
                }
                monster.setHealth(Math.min(max.getValue(), monster.getHealth() + max.getValue() * traits.regen() * wasting / 100.0));
            }
        }
        if (world == null || at.getNearbyPlayers(VIEW_RADIUS).isEmpty()) {
            return; // 2026-10-23：附近沒有玩家：其他的（光環、足跡、粒子）都不用做
        }
        final boolean held = plugin.ailments() != null && plugin.ailments().isIncapacitated(monster);
        // 熔岩護盾：在的時候身上滴岩漿
        final Pool pool = traits.pool() ? pools.get(monster.getUniqueId()) : null;
        if (pool != null && pool.magma > 0) {
            world.spawnParticle(Particle.DRIPPING_LAVA, at.clone().add(0, monster.getHeight() * 0.7, 0), 3, 0.35, 0.4, 0.35, 0);
        }
        // 阻礙光環
        if (traits.hinder() > 0 && plugin.ailments() != null) {
            for (final Player player : at.getNearbyPlayers(traits.hinder())) {
                plugin.ailments().hinder(player, 25);
            }
        }
        // 時間泡泡：阻礙＋小冰緩（動作也變慢）
        final double bubble = traits.bubble();
        if (bubble > 0 && plugin.ailments() != null) {
            for (final Player player : at.getNearbyPlayers(bubble)) {
                final Long last = bubbled.put(player.getUniqueId(), now);
                if (last != null && now - last < 10) {
                    continue; // 這半秒已經被別的泡泡套過了
                }
                plugin.ailments().hinder(player, 25);
                plugin.ailments().groundAilment(player, AilmentType.CHILL, 0.2, 1.0);
                // 2026-10-23（PoE2）：冷卻回復慢一半、身上的減益消退慢一半（每 0.5 秒都往後推 5 tick）
                plugin.ailments().extendDebuffs(player, 5);
                if (plugin.skills() != null) {
                    plugin.skills().delayCooldowns(player, 5);
                }
            }
            ring(world, at, bubble, Particle.REVERSE_PORTAL, 16);
        }
        // 足跡：走了一段就留一塊
        if (traits.trails()) {
            trails(monster, traits, at, now);
        }
        // 噬魔的：內圈安全，外圈吸魔力、不能回魔，每秒一下閃電
        if (traits.siphon() && !held) {
            ring(world, at, SIPHON_INNER, Particle.ELECTRIC_SPARK, 14);
            ring(world, at, SIPHON_OUTER, Particle.ELECTRIC_SPARK, 28);
            for (final Player player : at.getNearbyPlayers(SIPHON_OUTER)) {
                if (player.getLocation().distanceSquared(at) < SIPHON_INNER * SIPHON_INNER) {
                    continue;
                }
                final PlayerState state = plugin.players().get(player);
                final double drain = state.maxMana() * 0.02;
                state.setMana(Math.max(0, Math.min(state.mana(), lastMana.getOrDefault(player.getUniqueId(), state.mana())) - drain));
                lastMana.put(player.getUniqueId(), state.mana());
                if (ticks % 10 == 0) {
                    hit(monster, player, "mana_siphon", DamageType.LIGHTNING, 0.15);
                }
            }
        }
        if (held) {
            return; // 凍住、暈住不放主動的東西
        }
        final Player target = monster instanceof Mob mob && mob.getTarget() instanceof Player p && p.getWorld().equals(world)
                && p.getLocation().distanceSquared(at) <= 16 * 16 ? p : null;
        // 癒療的：每 6 秒治療附近怪物
        if (traits.heal() > 0 && target != null && ready(monster, "heal", now, 120)) {
            for (final Entity near : monster.getNearbyEntities(10, 5, 10)) {
                if (near instanceof LivingEntity ally && !(near instanceof Player) && monsters.isMonster(ally)) {
                    healPercent(ally, traits.heal());
                }
            }
            healPercent(monster, traits.heal());
            world.spawnParticle(Particle.HEART, at.clone().add(0, monster.getHeight() + 0.3, 0), 5, 0.6, 0.3, 0.6, 0);
            ring(world, at, 10, Particle.HAPPY_VILLAGER, 30);
        }
        // 爆屍的：目標附近的屍體 1 秒後炸開
        if (traits.corpse() && target != null && plugin.combat() != null && ready(monster, "corpse", now, 120)) {
            final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
            final Location tl = target.getLocation();
            final tw.exilecore.combat.Corpses.Corpse corpse = corpses.nearest(tl.getWorld().getUID(), tl.getX(), tl.getZ(), 8, now);
            if (corpse != null) {
                corpses.remove(corpse);
                blasts.add(new Blast(monster.getUniqueId(), new Location(tl.getWorld(), corpse.x(), corpse.y(), corpse.z()), now + 20, 3, 1.5));
            }
        }
        // 喚靈的：讓附近的屍體爬起來（最多兩隻）
        if (traits.revive() && target != null && plugin.combat() != null && ready(monster, "revive", now, 160)) {
            final MonsterData data = monsters.data(monster).orElse(null);
            final tw.exilecore.combat.Corpses corpses = plugin.combat().corpses();
            int raised = 0;
            for (final tw.exilecore.combat.Corpses.Corpse corpse : data == null ? List.<tw.exilecore.combat.Corpses.Corpse>of()
                    : corpses.near(world.getUID(), at.getX(), at.getZ(), 10, now)) {
                if (raised >= 2 || corpse.type() == null) {
                    continue;
                }
                final Location spot = new Location(world, corpse.x(), corpse.y() + 0.1, corpse.z());
                final LivingEntity minion = effects.spawnMinion(monster, data, corpse.type(), spot);
                corpses.remove(corpse);
                if (minion != null) {
                    raised++;
                    if (minion instanceof Mob minionMob) {
                        minionMob.setTarget(target);
                    }
                    world.spawnParticle(Particle.SOUL, spot.clone().add(0, 0.5, 0), 12, 0.3, 0.5, 0.3, 0.03);
                }
            }
            if (raised > 0) {
                world.playSound(at, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.6f, 0.8f);
            }
        }
        // 竊瓶的：每 5 秒附近玩家的藥劑各少 1 點充能
        if (traits.flaskSiphon() && plugin.flasks() != null && ready(monster, "flask", now, 100)) {
            for (final Player player : at.getNearbyPlayers(12)) {
                plugin.flasks().drainCharges(player, 1);
                player.getWorld().spawnParticle(Particle.WITCH, player.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
            }
        }
    }

    /** 2026-10-23：附近這麼多格內沒有玩家，怪物詞綴的光環、足跡、粒子都不做。 */
    static final double VIEW_RADIUS = 32;

    private boolean ready(final LivingEntity monster, final String what, final long now, final long every) {
        final Map<String, Long> times = nextAction.computeIfAbsent(monster.getUniqueId(), k -> new HashMap<>(4));
        if (times.getOrDefault(what, 0L) > now) {
            return false;
        }
        times.put(what, now + every);
        return true;
    }

    private static void healPercent(final LivingEntity entity, final double percent) {
        final AttributeInstance max = entity.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && entity.isValid() && !entity.isDead()) {
            entity.setHealth(Math.min(max.getValue(), entity.getHealth() + max.getValue() * percent / 100.0));
        }
    }

    // ---- 足跡 ----

    private void trails(final LivingEntity monster, final Traits traits, final Location at, final long now) {
        final boolean fire = traits.trailFire();
        final boolean cold = traits.trailCold();
        final boolean lightning = traits.trailLightning();
        final Location last = lastTrail.get(monster.getUniqueId());
        if (last != null && last.getWorld().equals(at.getWorld()) && last.distanceSquared(at) < 1.0) {
            return;
        }
        lastTrail.put(monster.getUniqueId(), at.clone());
        final StatSheet sheet = fire ? sheetOf(monster) : null;
        final double hit = sheet == null ? 0 : averageHit(sheet);
        final UUID world = at.getWorld().getUID();
        if (fire) {
            patches.add(new Patch(world, at.getX(), at.getY(), at.getZ(), AilmentType.IGNITE, hit * 0.25, now + 60));
        }
        if (cold) {
            patches.add(new Patch(world, at.getX(), at.getY(), at.getZ(), AilmentType.CHILL, 0.3, now + 80));
        }
        if (lightning) {
            patches.add(new Patch(world, at.getX(), at.getY(), at.getZ(), AilmentType.SHOCK, 0.2, now + 80));
        }
        while (patches.size() > 400) {
            patches.removeFirst();
        }
    }

    /**
     * 2026-10-23：每個世界只拿一次玩家清單；沒有玩家的世界整個跳過，附近 32 格沒人看的地面不放粒子
     * （以前每一塊都各自查世界、查附近玩家、放粒子）。
     */
    private void tickPatches(final long now) {
        patches.removeIf(patch -> patch.until() <= now);
        mapPatches.removeIf(patch -> patch.until() <= now);
        if (patches.isEmpty() && mapPatches.isEmpty()) {
            return;
        }
        final Map<UUID, List<Player>> players = new HashMap<>();
        final List<Patch> all = new ArrayList<>(patches.size() + mapPatches.size());
        all.addAll(patches);
        all.addAll(mapPatches);
        for (final Patch patch : all) {
            final List<Player> here = players.computeIfAbsent(patch.world(), uuid -> {
                final World world = plugin.getServer().getWorld(uuid);
                return world == null ? List.of() : world.getPlayers();
            });
            if (here.isEmpty()) {
                continue;
            }
            boolean seen = false;
            for (final Player player : here) {
                final Location pl = player.getLocation();
                final double dx = pl.getX() - patch.x();
                final double dy = pl.getY() - patch.y();
                final double dz = pl.getZ() - patch.z();
                final double flat = dx * dx + dz * dz;
                if (flat <= VIEW_RADIUS * VIEW_RADIUS) {
                    seen = true;
                }
                if (plugin.ailments() != null && flat <= patch.radius() * patch.radius() && Math.abs(dy) <= 1.5 && !player.isDead()) {
                    plugin.ailments().groundAilment(player, patch.type(), patch.value(), 2.0);
                    // 2026-10-27 照 PoE2 天賦樹（「站在感電／冰緩／點燃地面上時」）
                    plugin.players().get(player).markRecent(switch (patch.type()) {
                        case IGNITE -> tw.exilecore.stats.TreeLastKeys.EVENT_GROUND_FIRE;
                        case CHILL -> tw.exilecore.stats.TreeLastKeys.EVENT_GROUND_CHILL;
                        default -> tw.exilecore.stats.TreeLastKeys.EVENT_GROUND_SHOCK;
                    }, now);
                }
            }
            if (seen) {
                final World world = here.getFirst().getWorld();
                world.spawnParticle(switch (patch.type()) {
                    case IGNITE -> Particle.FLAME;
                    case CHILL -> Particle.SNOWFLAKE;
                    default -> Particle.ELECTRIC_SPARK;
                }, patch.x(), patch.y() + 0.1, patch.z(), patch.owner() == null ? 3 : 2 + (int) Math.round(patch.radius() * 2),
                        patch.radius() * 0.4, 0.05, patch.radius() * 0.4, 0.01);
                if (patch.type() == AilmentType.IGNITE) {
                    // 2026-10-27 照站長「怪物放的燃燒地板太不明顯」：畫一圈外框（玩家自己的燃燒地面不畫）
                    tw.exilecore.skill.Fx.groundOutline(new Location(world, patch.x(), patch.y(), patch.z()), patch.radius(),
                            tw.exilecore.skill.Fx.BURNING_EDGE);
                }
            }
        }
    }

    // ---- 護盾回充、熔岩護盾回來 ----

    private void tickPools(final long now) {
        for (final Map.Entry<UUID, Pool> entry : pools.entrySet()) {
            final Pool pool = entry.getValue();
            if (pool.shieldMax > 0 && pool.shield < pool.shieldMax && now - pool.lastHit >= SHIELD_RECHARGE_DELAY
                    && (plugin.combat() == null || !plugin.combat().debuffs().has(entry.getKey(),
                            tw.exilecore.stats.KeystoneOriginKeys.NO_ES_RECHARGE_ID, now))) { // 2026-10-27 照 PoE2 命運終結：被詛咒的不能回充
                pool.shield = Math.min(pool.shieldMax, pool.shield + pool.shieldMax * 0.1); // 每秒 20%
            }
            if (pool.magmaMax > 0 && pool.magma <= 0 && pool.magmaBack > 0 && now >= pool.magmaBack) {
                pool.magma = pool.magmaMax;
                pool.magmaBack = 0;
                if (plugin.getServer().getEntity(entry.getKey()) instanceof LivingEntity living && living.isValid()) {
                    living.getWorld().playSound(living.getLocation(), Sound.BLOCK_LAVA_POP, 0.8f, 0.8f);
                }
            }
        }
    }

    // ---- 抑生的：附近玩家的生命回復不能超過門檻 ----

    private void tickCaps(final long now) {
        Map<UUID, Double> caps = null;
        for (final Tracked entry : tracked.values()) {
            final double percent = entry.traits.recoveryCap(); // 2026-10-23：用快取的詞綴（每 0.5 秒更新）
            if (percent <= 0) {
                continue;
            }
            final LivingEntity monster = entry.entity;
            if (!monster.isValid() || monster.isDead()) {
                continue;
            }
            for (final Player player : monster.getLocation().getNearbyPlayers(12)) {
                if (caps == null) {
                    caps = new HashMap<>();
                }
                caps.merge(player.getUniqueId(), percent, Math::min);
            }
        }
        if (caps == null) {
            lastLife.clear();
            if (ticks % 5 == 0 && !lastMana.isEmpty()) {
                lastMana.keySet().removeIf(uuid -> {
                    final Player player = plugin.getServer().getPlayer(uuid);
                    return player == null || !nearSiphon(player);
                });
            }
            return;
        }
        lastLife.keySet().retainAll(caps.keySet());
        for (final Map.Entry<UUID, Double> entry : caps.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            final PlayerState state = plugin.players().get(player);
            final double cap = state.maxLife() * entry.getValue() / 100.0;
            final Double last = lastLife.get(entry.getKey());
            if (last != null && state.life() > last && state.life() > cap) {
                state.setLife(Math.max(last, cap)); // 回復被擋在門檻（本來就比門檻高的不會被扣）
            }
            lastLife.put(entry.getKey(), state.life());
        }
        // 噬魔的：離開範圍就不管
        if (ticks % 5 == 0) {
            lastMana.keySet().removeIf(uuid -> {
                final Player player = plugin.getServer().getPlayer(uuid);
                return player == null || !nearSiphon(player);
            });
        }
    }

    private boolean nearSiphon(final Player player) {
        for (final Tracked entry : tracked.values()) {
            final LivingEntity monster = entry.entity;
            if (entry.traits.siphon() && monster.isValid() && monster.getWorld().equals(player.getWorld())
                    && monster.getLocation().distanceSquared(player.getLocation()) <= SIPHON_OUTER * SIPHON_OUTER) {
                return true;
            }
        }
        return false;
    }

    // ---- 閃電幻影、屍爆 ----

    private void tickMirages(final long now) {
        final Iterator<Mirage> iterator = mirages.iterator();
        while (iterator.hasNext()) {
            final Mirage mirage = iterator.next();
            final Entity source = plugin.getServer().getEntity(mirage.source);
            final Player target = plugin.getServer().getPlayer(mirage.target);
            if (!(source instanceof LivingEntity living) || !living.isValid() || target == null || !target.getWorld().equals(mirage.at.getWorld())) {
                iterator.remove();
                continue;
            }
            final Location goal = target.getLocation().add(0, 1, 0);
            final org.bukkit.util.Vector step = goal.toVector().subtract(mirage.at.toVector());
            final double distance = step.length();
            if (distance <= 1.3 || now >= mirage.until) {
                explodeMirage(living, mirage.at);
                iterator.remove();
                continue;
            }
            mirage.at = mirage.at.add(step.multiply(Math.min(MIRAGE_SPEED * 2, distance) / distance)); // 每 2 tick 走兩步
            mirage.at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, mirage.at, 6, 0.15, 0.3, 0.15, 0.02);
            mirage.at.getWorld().spawnParticle(Particle.DUST, mirage.at, 3, 0.15, 0.4, 0.15, 0,
                    new Particle.DustOptions(Color.fromRGB(0x9fd3ff), 1.2f));
        }
    }

    private void explodeMirage(final LivingEntity source, final Location at) {
        at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 30, 1, 0.6, 1, 0.1);
        at.getWorld().playSound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.6f, 1.6f);
        for (final Player player : at.getNearbyPlayers(2.5)) {
            hit(source, player, "lightning_mirage", DamageType.LIGHTNING, 0.8);
        }
    }

    private void tickBlasts(final long now) {
        final Iterator<Blast> iterator = blasts.iterator();
        while (iterator.hasNext()) {
            final Blast blast = iterator.next();
            final World world = blast.at().getWorld();
            if (world == null) {
                iterator.remove();
                continue;
            }
            if (now < blast.fuse()) {
                ring(world, blast.at(), blast.radius(), Particle.DUST, 18,
                        new Particle.DustOptions(Color.fromRGB(0xc03030), 1.1f));
                continue;
            }
            iterator.remove();
            world.spawnParticle(Particle.EXPLOSION, blast.at().clone().add(0, 0.5, 0), 2, 0.4, 0.2, 0.4, 0);
            world.spawnParticle(Particle.DUST, blast.at().clone().add(0, 0.5, 0), 30, 1.2, 0.4, 1.2, 0,
                    new Particle.DustOptions(Color.fromRGB(0x8a0e0e), 1.4f));
            world.playSound(blast.at(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f);
            final Entity source = plugin.getServer().getEntity(blast.source());
            if (!(source instanceof LivingEntity living) || !living.isValid()) {
                continue;
            }
            for (final Player player : blast.at().getNearbyPlayers(blast.radius())) {
                hit(living, player, "corpse_blast", DamageType.PHYSICAL, blast.damage());
            }
        }
    }

    // ---- 工具 ----

    /** 怪物用自己的普攻打一下玩家（effectiveness 倍、整下轉成 type）：走戰鬥模組的管道（抗性、護甲、閃避、格擋照算）。 */
    private void hit(final LivingEntity source, final Player player, final String id, final DamageType type, final double effectiveness) {
        if (plugin.combat() == null || plugin.combat().skillDamage() == null || !player.isValid() || player.isDead()) {
            return;
        }
        final SkillProfile profile = profiles.computeIfAbsent(id + ":" + type + ":" + effectiveness, key -> new SkillProfile("monster." + id,
                Set.of(Tags.ATTACK, Tags.AREA, type.tag()), effectiveness, null, null,
                type == DamageType.PHYSICAL ? Map.of() : Map.of(type, 1.0), List.of()));
        plugin.combat().skillDamage().hitAs(source, player, profile, null);
    }

    /** 屬性表上這隻普通一下的平均（物理；足跡的點燃傷害用）。 */
    static double averageHit(final StatSheet sheet) {
        final Set<String> physical = Set.of(Tags.PHYSICAL);
        return (sheet.flat(StatKeys.WEAPON_MIN, physical) + sheet.flat(StatKeys.WEAPON_MAX, physical)) / 2;
    }

    private static void ring(final World world, final Location center, final double radius, final Particle particle, final int points) {
        ring(world, center, radius, particle, points, null);
    }

    private static <T> void ring(final World world, final Location center, final double radius, final Particle particle, final int points,
                                 final T data) {
        for (int i = 0; i < points; i++) {
            final double angle = Math.PI * 2 * i / points;
            final Location at = new Location(world, center.getX() + Math.cos(angle) * radius, center.getY() + 0.15,
                    center.getZ() + Math.sin(angle) * radius);
            if (data == null) {
                world.spawnParticle(particle, at, 1, 0, 0, 0, 0);
            } else {
                world.spawnParticle(particle, at, 1, 0, 0, 0, 0, data);
            }
        }
    }
}
