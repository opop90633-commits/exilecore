package tw.exilecore.skill;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 0.5（站長「機制沒做的部分就做出來補齊、基本要跟原作一樣」）：冰系法術補上的原作機制。
 * <ul>
 *   <li>霜星爆（Ice Nova）：依距離擊退（generic_knockback_+%_final_at_min_distance 50、at_max_distance −90、distance_limit 2 公尺），
 *       {@link #KNOCKBACK_FALLOFF}；冰灌注版的傷害表寫在 skills.yml 的 resource.infused_set。</li>
 *   <li>霜鑽（Frostbolt）：撞到地形時爆炸（Explosion：2.4 公尺、自己的傷害表 {@link #FROSTBOLT_BURST}）。</li>
 *   <li>冰梭（Frost Darts）：打中冰緩或凍結的敵人時每支落下 2 塊冰塊（Ice Chunk：0.8 公尺、0.5–1.7 公尺內隨機、自己的傷害表）；
 *       用掉冰灌注時每支插在敵人身上再爆炸（Explosion：1.8 公尺、自己的傷害表），爆炸再落下 3 塊冰塊。</li>
 *   <li>寒冬之眼（Eye of Winter）：冰眼本身不打人，飛行中每秒往四周螺旋放出 30 片冰片（+x 投射物 = 每秒多 x 片），
 *       同一次施放每個敵人第一次被冰片打中更多 900%；冰眼經過元素地面時之後的冰片獲得 100% 傷害為那種元素的額外傷害（每種各一次）。</li>
 *   <li>遲滯詛咒（Temporal Chains）：敵人身上其他效果流逝慢 25%（{@link #TIME_PASSED}，吃詛咒效果）、
 *       低等級寶石不能詛咒高等級敵人（{@link #CURSE_MAX_LEVEL}）。攻擊、施法速度更少寫在 mark.effects。</li>
 * </ul>
 * 霜壁（Frost Wall）的冰晶牆在 {@link IceCrystals}（擋路、被推碎、自己的爆炸與閃電灌注的閃電爆炸）。
 */
final class ColdSpells implements Listener {

    static final String ICE_NOVA = "frost_star_blast";
    static final String FROST_WALL = "frost_rampart";
    static final String EYE = "cold_winter_gaze";
    static final String FROSTBOLT = "frost_drill";
    static final String DARTS = "ice_shuttle";
    static final String TEMPORAL = "sluggish_curse";

    /** 隱藏技能（只拿來放各段自己的傷害表與半徑，傷害照放它的技能的輔助、加成）。 */
    static final String FROSTBOLT_BURST = "proc_frostbolt_burst";
    static final String DARTS_BURST = "proc_frost_darts_burst";
    static final String DARTS_CHUNK = "proc_frost_darts_chunk";
    static final String WALL_BURST = "proc_crystal_frost_wall";
    static final String WALL_VOLT = "proc_crystal_frost_wall_volt";

    /** FLAT 1：新星的擊退照距離縮放（近的更多 {@value #KNOCKBACK_NEAR}%、最遠的更少 {@value #KNOCKBACK_FAR}%、最多 {@value #KNOCKBACK_LIMIT} 公尺）。 */
    static final String KNOCKBACK_FALLOFF = "knockback.distance_falloff";
    static final double KNOCKBACK_NEAR = 50;
    static final double KNOCKBACK_FAR = -90;
    static final double KNOCKBACK_LIMIT = 2.0;
    /**
     * 原版的擊退速度換成實際被推開的距離：水平速度 v、往上 0.3 時，空中每 tick 留 0.91、落地後每 tick 留 0.546，
     * 總共大約走 6.5v 格（實測估的）。擊退「幾公尺」除以這個才是要給的速度。
     */
    static final double BLOCKS_PER_KNOCKBACK_VELOCITY = 6.5;

    /** FLAT 1（冰梭用掉冰灌注）：每支插在敵人身上再爆炸。 */
    static final String DARTS_LODGE = "frost_darts.lodge";
    /** FLAT 1（霜壁用掉電灌注）：冰晶碎掉時再多一次閃電爆炸。 */
    static final String WALL_VOLT_FLAG = "crystal.wall_volt";
    /** FLAT %（詛咒的效果，吃詛咒效果）：身上其他效果流逝慢 x%。 */
    static final String TIME_PASSED = "debuff.time_passed_slower";
    /** FLAT（逐級，0 = 不限）：詛咒不能套在等級高於這個的敵人身上。 */
    static final String CURSE_MAX_LEVEL = "curse.max_target_level";

    /** 冰梭插進去到爆炸幾 tick（原作沒寫，照動畫估 0.5 秒）。 */
    static final int LODGE_TICKS = 10;
    /** 冰塊從空中落下要幾 tick（原作沒寫，0.4 秒）。 */
    static final int CHUNK_FALL_TICKS = 8;
    static final int CHUNKS_PER_DART = 2;
    static final int CHUNKS_PER_EXPLOSION = 3;
    /** 冰塊落在多遠（skill_mortar_minimum／maximum_projectile_distance 5、17：0.5–1.7 公尺）。 */
    static final double CHUNK_MIN = 0.5;
    static final double CHUNK_MAX = 1.7;

    /** 寒冬之眼：每秒放幾片（base_number_of_projectiles 30）、每片轉幾度、冰片多快多遠（原作沒寫，照我們的一般冰片）、第一下更多幾 %。 */
    static final double EYE_SHARDS_PER_SECOND = 30;
    /**
     * 冰眼每 tick 飛幾格（poe2db Eye of Winter：2.25 公尺／秒）。skills.yml 的 projectile.speed 驗證最少 0.2，
     * 所以那邊寫 0.2 只當「投射物速度加成」的基準（實際速度 = 這個 × 加成後 ÷ 0.2）。
     */
    static final double EYE_SPEED = 2.25 / 20;
    static final double SPIRAL_STEP_DEGREES = 37;
    /** 2026-11-01 照站長（poe2db Eye of Winter 投射物速度 2.25 公尺／秒，冰片也是投射物）：冰片跟冰眼一樣 2.25 公尺／秒；這次之前 0.75 格／tick（15 公尺／秒，原作沒寫時的暫定）。 */
    static final double SHARD_SPEED = 2.25 / 20;
    static final double SHARD_RANGE = 6;
    static final double FIRST_HIT_MORE = 900;
    /** 經過元素地面時獲得那種元素的額外傷害（eye_of_winter_damage_%_to_gain_as_element_per_ground_effect_type 100）。 */
    static final double GROUND_GAIN = 100;

    /**
     * 2026-11-01 照 PoE2 Eye of Winter（0.2.0e：passing through other elemental orbs like Solar Orb, Orb of Storms or Frost Bomb）：
     * 法球（PoB2 skillTypes 有 Orb 的：Frost Bomb 寒晶雷、Orb of Storms 雷雲、Solar Orb 熾陽珠、Galvanic Field 雷流場的電球、
     * Elemental Storm 元素風暴）在這裡登記，冰眼飛過去跟飛過元素地面一樣拿到那種元素。at 是現在的位置（電球跟著敵人走；null = 不見了），
     * alive 是還在不在（被拿掉、被上限擠掉就不算）。
     */
    record Orb(UUID world, java.util.function.Supplier<Location> at, double radius, DamageType element, long until,
               java.util.function.BooleanSupplier alive) {
    }

    private static final List<Orb> ORBS = new ArrayList<>();
    static final int MAX_ORBS = 200;

    /** 登記一顆法球（只登記火焰、冰冷、閃電）。 */
    static void markOrb(final Location center, final double radius, final DamageType element, final long until,
                        final java.util.function.Supplier<Location> at, final java.util.function.BooleanSupplier alive) {
        if (center == null || center.getWorld() == null || element == null || !element.isElemental()) {
            return;
        }
        final Location fixed = center.clone();
        ORBS.add(new Orb(center.getWorld().getUID(), at != null ? at : () -> fixed, Math.max(0.5, radius), element, until,
                alive != null ? alive : () -> true));
        while (ORBS.size() > MAX_ORBS) {
            ORBS.removeFirst();
        }
    }

    /** here 附近 reach 格內碰到的法球的元素（順便清掉過期的）。 */
    static Set<DamageType> orbsAt(final Location here, final double reach, final long now) {
        final Set<DamageType> out = EnumSet.noneOf(DamageType.class);
        if (here == null || here.getWorld() == null) {
            return out;
        }
        final UUID world = here.getWorld().getUID();
        ORBS.removeIf(orb -> now >= orb.until() || !orb.alive().getAsBoolean());
        for (final Orb orb : ORBS) {
            if (!orb.world().equals(world)) {
                continue;
            }
            final Location at = orb.at().get();
            if (at != null && touches(at.getX(), at.getY(), at.getZ(), orb.radius(), here.getX(), here.getY(), here.getZ(), reach)) {
                out.add(orb.element());
            }
        }
        return out;
    }

    /** 純函式：(px, py, pz) reach 格內有沒有碰到中心 (x, y, z)、半徑 radius 的法球（水平距離，高度差 3 格內）。 */
    static boolean touches(final double x, final double y, final double z, final double radius, final double px, final double py,
                           final double pz, final double reach) {
        if (Math.abs(py - y) > 3) {
            return false;
        }
        final double r = radius + Math.max(0, reach);
        return (px - x) * (px - x) + (pz - z) * (pz - z) <= r * r;
    }

    /** 純函式：技能標籤的元素（冰冷、閃電、火焰照這個順序找第一個）；沒有回傳 null。 */
    static DamageType elementOf(final Set<String> tags) {
        if (tags.contains(tw.exilecore.stats.Tags.COLD)) {
            return DamageType.COLD;
        }
        if (tags.contains(tw.exilecore.stats.Tags.LIGHTNING)) {
            return DamageType.LIGHTNING;
        }
        return tags.contains(tw.exilecore.stats.Tags.FIRE) ? DamageType.FIRE : null;
    }

    /** 這個地面效果／落下的技能是不是法球（標籤 orb；元素風暴的三種隱藏風暴也是）。 */
    static boolean isOrb(final String id, final Set<String> tags) {
        return tags.contains(ORB_TAG) || id != null && id.startsWith("elemental_storm_");
    }

    static final String ORB_TAG = "orb";

    /** 遲滯：每幾 tick 推一次其他效果的結束時間。 */
    static final int TEMPORAL_PERIOD = 4;

    private final ExileCore plugin;
    private final SkillService service;
    private final List<Eye> eyes = new ArrayList<>();
    private final Map<UUID, Double> temporalCarry = new HashMap<>();
    private BukkitTask task;
    private long ticks;

    ColdSpells(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    // =====================================================================
    // 純函式（測試用）
    // =====================================================================

    /**
     * 依距離縮放的擊退（公尺）：base 公尺，距離 0 時更多 {@value #KNOCKBACK_NEAR}%、到 radius 時更少 {@value #KNOCKBACK_FAR}%，中間線性，
     * 最多 {@value #KNOCKBACK_LIMIT} 公尺。
     */
    static double falloffMetres(final double base, final double distance, final double radius) {
        if (!(base > 0)) {
            return 0;
        }
        final double t = radius <= 0 ? 0 : Math.max(0, Math.min(1, distance / radius));
        final double more = KNOCKBACK_NEAR + (KNOCKBACK_FAR - KNOCKBACK_NEAR) * t;
        return Math.min(tw.exilecore.core.Units.metres(KNOCKBACK_LIMIT), base * Math.max(0, 1 + more / 100.0));
    }

    /** 推開幾格要給多少原版擊退速度。 */
    static double knockbackVelocity(final double blocks) {
        return Math.max(0, blocks) / BLOCKS_PER_KNOCKBACK_VELOCITY;
    }

    /** 寒冬之眼第 index 片冰片的水平方向角度（度）：從 base 開始每片轉 {@value #SPIRAL_STEP_DEGREES} 度。 */
    static double spiralAngle(final double base, final int index) {
        return (base + SPIRAL_STEP_DEGREES * index) % 360;
    }

    /** 寒冬之眼每秒幾片：30 片 + 多的投射物（技能本身是 1 顆冰眼）。 */
    static double shardsPerSecond(final int projectiles) {
        return EYE_SHARDS_PER_SECOND + Math.max(0, projectiles - 1);
    }

    /** 這一輪（period tick）要推幾 tick：carry 是上次剩的小數，slower 是流逝慢幾 %；回傳 {推幾 tick, 剩下的小數}。 */
    static double[] temporalStep(final double carry, final double slower, final int period) {
        final double total = Math.max(0, carry) + period * Math.max(0, Math.min(90, slower)) / 100.0;
        final double whole = Math.floor(total);
        return new double[] {whole, total - whole};
    }

    /** 詛咒的等級上限：cap ≤ 0 不限；敵人等級高於 cap 就套不上。 */
    static boolean aboveCurseLevel(final double cap, final int targetLevel) {
        return cap > 0 && targetLevel > cap;
    }

    /** 冰塊落點離中心多遠（roll 是 0～1 的亂數）。 */
    static double chunkDistance(final double roll) {
        return CHUNK_MIN + (CHUNK_MAX - CHUNK_MIN) * Math.max(0, Math.min(1, roll));
    }

    // =====================================================================
    // 共用：各段自己的傷害表
    // =====================================================================

    /**
     * base（放它的技能這一下的 profile，含輔助、加成）換上隱藏技能 tableId 的傷害表（同一個等級），代號換成 tableId
     * （冰晶的爆炸不會再打冰晶：IceCrystals 不理 proc_crystal 開頭的）。沒有那個隱藏技能就原樣回傳。
     */
    static SkillProfile ownTable(final SkillConfig config, final SkillProfile base, final String tableId, final int level) {
        final SkillDefinition table = config == null ? null : config.skill(tableId);
        if (table == null) {
            return base;
        }
        final SkillProfile own = SkillProfiles.build(config, table, level, Set.of());
        return new SkillProfile(tableId, base.tags(), base.effectiveness(), own.spellDamage(), own.spellDamageMax(), base.conversion(), base.local());
    }

    /** 隱藏技能 tableId 寫的半徑（格，還沒乘範圍）；沒有就 fallback。 */
    static double tableRadius(final SkillConfig config, final String tableId, final int level, final double fallback) {
        final SkillDefinition table = config == null ? null : config.skill(tableId);
        if (table == null || table.params().radius() == null) {
            return fallback;
        }
        final double radius = table.params().radius().at(level);
        return radius > 0 ? radius : fallback;
    }

    static boolean falloff(final SkillProfile profile) {
        return SkillZones.localFlat(profile, KNOCKBACK_FALLOFF) > 0;
    }

    /** 玩家看到的說明（GroundSkills.describe 不認得的交給這裡）；不是這裡的回傳 null。 */
    static String describe(final String stat, final double value) {
        final String number = value == Math.rint(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.2f", value);
        return switch (stat) {
            case IceCrystals.WALL -> "整道牆都是一顆顆冰晶：擋住敵人、自己不打傷害；冰晶被打碎（或被推不動的敵人推碎）時炸出 2 格的冰";
            case KNOCKBACK_FALLOFF -> "擊退敵人：離中心越近推得越遠（最近更多 50%、最遠更少 90%，最多 2 格）";
            case DARTS_LODGE -> "每支冰梭插在敵人身上再爆開（1.8 格，照等級 2–4 → 66–98 冰冷傷害），爆開時再落下 3 塊冰塊";
            case WALL_VOLT_FLAG -> "冰晶碎掉時再多一次閃電爆炸（2.8 格，照等級 1–17 → 23–444 閃電傷害）";
            case TIME_PASSED -> "敵人身上的其他效果流逝慢 " + number + "%";
            case CURSE_MAX_LEVEL -> value > 0 ? "不能詛咒等級高於 " + number + " 的敵人" : "能詛咒任何等級的敵人";
            default -> null;
        };
    }

    // =====================================================================
    // 每 tick
    // =====================================================================

    private void ensureTask() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        }
    }

    /** 遲滯詛咒：放了就開始每 tick 跑（拖慢其他效果）。 */
    void curseCast() {
        ensureTask();
    }

    private void tick() {
        ticks++;
        if (!eyes.isEmpty()) {
            for (final Iterator<Eye> it = eyes.iterator(); it.hasNext(); ) {
                if (!tickEye(it.next())) {
                    it.remove();
                }
            }
        }
        if (ticks % TEMPORAL_PERIOD == 0) {
            tickTemporal();
        }
    }

    // =====================================================================
    // 遲滯詛咒：其他效果流逝慢 25%
    // =====================================================================

    private void tickTemporal() {
        if (plugin.combat() == null) {
            return;
        }
        final tw.exilecore.combat.Debuffs debuffs = plugin.combat().debuffs();
        final long now = plugin.getServer().getCurrentTick();
        final Set<UUID> seen = new HashSet<>();
        for (final UUID target : debuffs.targets()) {
            double slower = 0;
            for (final tw.exilecore.combat.Debuffs.Debuff debuff : debuffs.active(target, now)) {
                if (debuff.id().startsWith(TEMPORAL)) {
                    for (final Modifier modifier : debuff.mods()) {
                        if (TIME_PASSED.equals(modifier.stat())) {
                            slower = Math.max(slower, modifier.value());
                        }
                    }
                }
            }
            if (slower <= 0) {
                continue;
            }
            seen.add(target);
            final double[] step = temporalStep(temporalCarry.getOrDefault(target, 0.0), slower, TEMPORAL_PERIOD);
            temporalCarry.put(target, step[1]);
            final long extra = (long) step[0];
            if (extra <= 0) {
                continue;
            }
            debuffs.extendExcept(target, extra, now, TEMPORAL);
            if (plugin.ailments() != null && plugin.getServer().getEntity(target) instanceof LivingEntity living) {
                plugin.ailments().extendEffects(living, extra);
            }
        }
        temporalCarry.keySet().retainAll(seen);
    }

    /** 詛咒的等級上限（profile 有 {@link #CURSE_MAX_LEVEL}）：這隻敵人太高等就跳過。 */
    boolean tooHighForCurse(final SkillProfile profile, final LivingEntity target) {
        final double cap = SkillZones.localFlat(profile, CURSE_MAX_LEVEL);
        if (cap <= 0 || plugin.monsters() == null) {
            return false;
        }
        final int level = plugin.monsters().data(target).map(tw.exilecore.monster.MonsterData::level).orElse(0);
        return aboveCurseLevel(cap, level);
    }

    // =====================================================================
    // 霜鑽：撞到地形爆炸
    // =====================================================================

    /** 投射物撞到方塊（還沒做停下的效果之前）：霜鑽在那裡爆炸。 */
    void terrainHit(final Player owner, final SkillBuild build, final SkillProfile profile, final Location at) {
        if (!FROSTBOLT.equals(build.definition().id()) || owner == null) {
            return;
        }
        final SkillConfig config = service.config();
        final double radius = tableRadius(config, FROSTBOLT_BURST, build.level(), 2.4) * build.areaScale();
        final SkillProfile blast = ownTable(config, profile, FROSTBOLT_BURST, build.level());
        service.executor().burst(owner, blast, at, radius, 0, ICE_NOVA);
    }

    // =====================================================================
    // 冰梭：冰塊、插進去爆炸
    // =====================================================================

    /** 冰梭打中一個敵人之後（chilled：打中前牠是不是冰緩或凍結）。 */
    void dartHit(final Player owner, final SkillBuild build, final SkillProfile profile, final LivingEntity target, final boolean chilled,
                 final Location at) {
        if (!DARTS.equals(build.definition().id()) || owner == null) {
            return;
        }
        if (chilled) {
            chunks(owner, build, profile, at, CHUNKS_PER_DART);
        }
        if (SkillZones.localFlat(profile, DARTS_LODGE) <= 0) {
            return;
        }
        final Location fallback = at.clone();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!owner.isOnline()) {
                return;
            }
            final Location where = target.isValid() && !target.isDead() ? SkillTargets.center(target) : fallback;
            if (where.getWorld() == null || !where.getWorld().equals(owner.getWorld())) {
                return;
            }
            final SkillConfig config = service.config();
            final double radius = tableRadius(config, DARTS_BURST, build.level(), 1.8) * build.areaScale();
            service.executor().burst(owner, ownTable(config, profile, DARTS_BURST, build.level()), where, radius, 0, ICE_NOVA);
            chunks(owner, build, profile, where, CHUNKS_PER_EXPLOSION);
        }, LODGE_TICKS);
    }

    /** 在 at 附近 0.5–1.7 格隨機落下 count 塊冰塊（各 0.8 格，落地時打）。 */
    private void chunks(final Player owner, final SkillBuild build, final SkillProfile profile, final Location at, final int count) {
        final SkillConfig config = service.config();
        final SkillProfile chunk = ownTable(config, profile, DARTS_CHUNK, build.level());
        final double radius = tableRadius(config, DARTS_CHUNK, build.level(), 0.8) * build.areaScale();
        for (int i = 0; i < count; i++) {
            final double angle = plugin.random().nextDouble() * Math.PI * 2;
            final double distance = chunkDistance(plugin.random().nextDouble());
            final Location land = ground(at.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance));
            service.zones().addImpact(owner, land, radius, chunk, ICE_NOVA, CHUNK_FALL_TICKS);
        }
    }

    private static Location ground(final Location at) {
        final World world = at.getWorld();
        if (world == null) {
            return at;
        }
        final RayTraceResult down = world.rayTraceBlocks(at.clone().add(0, 1, 0), new Vector(0, -1, 0), 4, FluidCollisionMode.NEVER, true);
        return down == null ? at : down.getHitPosition().toLocation(world).add(0, 0.05, 0);
    }

    // =====================================================================
    // 寒冬之眼
    // =====================================================================

    private static final class Eye {
        final Player owner;
        final SkillBuild build;
        final World world;
        final Vector direction;
        final double speed;
        final double rate;
        final double shardSpeed;
        final double baseAngle;
        final Set<UUID> firstHit = new HashSet<>();
        final Set<DamageType> absorbed = EnumSet.noneOf(DamageType.class);
        Vector position;
        int ticksLeft;
        double emit;
        int index;

        Eye(final Player owner, final SkillBuild build, final World world, final Vector position, final Vector direction, final double speed,
            final int ticks, final double rate, final double shardSpeed, final double baseAngle) {
            this.owner = owner;
            this.build = build;
            this.world = world;
            this.position = position;
            this.direction = direction;
            this.speed = speed;
            this.ticksLeft = ticks;
            this.rate = rate;
            this.shardSpeed = shardSpeed;
            this.baseAngle = baseAngle;
        }
    }

    /** 放出一顆冰眼（取代一般的投射物發射）。 */
    void eye(final Origin origin, final SkillBuild build) {
        final Player owner = origin.owner();
        if (owner == null) {
            return;
        }
        final Vector direction = origin.facing().clone();
        final Vector start = origin.eye().toVector().add(new Vector(0, -0.4, 0)).add(direction.clone().multiply(0.6));
        final SkillParams params = build.definition().params();
        final double speedRatio = params.speed() > 0 ? build.speed() / params.speed() : 1.0;
        final double speed = Math.max(0.02, EYE_SPEED * speedRatio);
        // 飛的時間照基礎射程 ÷ 基礎速度（投射物速度變快就飛得遠，跟原作一樣）
        final int life = (int) Math.max(1, Math.ceil(params.range() / EYE_SPEED));
        final double base = Math.toDegrees(Math.atan2(direction.getZ(), direction.getX()));
        eyes.add(new Eye(owner, build, origin.world(), start, direction, speed, life, shardsPerSecond(build.projectiles()),
                SHARD_SPEED * speedRatio, base));
        ensureTask();
    }

    /** 冰眼的一 tick；回傳還要不要繼續。 */
    private boolean tickEye(final Eye eye) {
        if (!eye.owner.isOnline() || !eye.owner.getWorld().equals(eye.world) || eye.ticksLeft-- <= 0) {
            return false;
        }
        final Location here = eye.position.toLocation(eye.world);
        if (!eye.world.isChunkLoaded(here.getBlockX() >> 4, here.getBlockZ() >> 4)) {
            return false;
        }
        final RayTraceResult block = eye.world.rayTraceBlocks(here, eye.direction, eye.speed, FluidCollisionMode.NEVER, true);
        final DamageType ground = ElementalGround.at(plugin, here, 0.5);
        if (ground != null) {
            eye.absorbed.add(ground);
        }
        eye.absorbed.addAll(orbsAt(here, 0.5, plugin.getServer().getCurrentTick())); // 2026-11-01 照 PoE2：飛過法球也算
        eye.emit += eye.rate / 20.0;
        final SkillProfile shard = shardProfile(eye);
        while (eye.emit >= 1) {
            eye.emit -= 1;
            final double angle = Math.toRadians(spiralAngle(eye.baseAngle, eye.index++));
            final Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle));
            service.projectiles().shard(eye.owner, eye.build, shard, eye.world, eye.position.clone().add(dir.clone().multiply(0.3)), dir,
                    eye.shardSpeed, SHARD_RANGE, eye.firstHit, FIRST_HIT_MORE);
        }
        tw.exilecore.skill.Fx.spawn(eye.world, Particle.SNOWFLAKE, here, 3, 0.15, 0.15, 0.15, 0.01);
        tw.exilecore.skill.Fx.spawn(eye.world, Particle.END_ROD, here, 1, 0.05, 0.05, 0.05, 0);
        if (block != null) {
            tw.exilecore.skill.Fx.spawn(eye.world, Particle.ITEM_SNOWBALL, here, 10, 0.3, 0.3, 0.3, 0.05);
            return false; // 撞到牆：冰眼消失
        }
        eye.position = eye.position.clone().add(eye.direction.clone().multiply(eye.speed));
        return true;
    }

    /** 冰片的 profile：冰眼經過過哪幾種元素地面，各獲得 100% 為那種元素的額外傷害。 */
    private static SkillProfile shardProfile(final Eye eye) {
        if (eye.absorbed.isEmpty()) {
            return eye.build.profile();
        }
        final List<Modifier> gains = new ArrayList<>();
        for (final DamageType element : eye.absorbed) {
            gains.add(Modifier.flat(StatKeys.DAMAGE_GAIN + element.name().toLowerCase(java.util.Locale.ROOT), GROUND_GAIN, "元素地面"));
        }
        return eye.build.profile().withLocal(gains);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        eyes.removeIf(eye -> eye.owner.getUniqueId().equals(event.getPlayer().getUniqueId()));
    }
}
