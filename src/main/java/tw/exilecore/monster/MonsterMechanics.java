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
 *   <li>2026-11-02 照 PoE2 0.5：狂躁的（每 10 秒狂暴 5 秒）、庇護光環的（每 12 秒讓附近的友軍 5 秒內不會受傷）、
 *       殉焰／殉霜／殉雷的（死掉時爆炸）、焦土／凍土／雷土的（腳下留地面、死掉時一大塊）、晶爆的（被打長晶體、死掉時炸開）、
 *       爆花的（追人的光球）、爆岩的（走近就炸的岩塊）、替身的（受到的傷害先由小怪扛）、復甦的（叫起來的小怪更強）、
 *       腐化詞綴：蝕朽的（打牠的擊中有撲殺，CombatListener）、蝕巨的（變大）、蝕生的（死掉後爬起來一次）。</li>
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
    // 2026-11-02 照 PoE2 0.5（poe2db 有寫的照寫，沒寫的推估，寫在 monsters.yml）
    /** Periodically Enrages：每 10 秒狂暴 5 秒。 */
    static final long ENRAGE_EVERY = 200;
    static final long ENRAGE_FOR = 100;
    /** Periodic Invulnerability Aura：每 12 秒、5 秒。 */
    static final long INVULN_EVERY = 240;
    static final long INVULN_FOR = 100;
    /**
     * 2026-11-02 照 PoE2 的怪物技能資料（repoe-fork/poe2 skills.json）：怪物技能寫的是 1 級的基礎傷害，跟著怪物等級長，
     * 所以「是這隻普通一下的幾倍」= 技能的平均傷害 ÷ 1 級怪物普通一下的平均（default_monster_stats.json 的 1 級 physical_damage 9.16）。
     */
    static final double POE2_LEVEL1_HIT = 9.16;

    /** 1 級的平均傷害 min～max 換成「普通一下的幾倍」。 */
    static double effectiveness(final double min, final double max) {
        return (min + max) / 2 / POE2_LEVEL1_HIT;
    }

    /**
     * 死掉時爆炸：2026-11-02 照 PoE2 GSMonsterModsOnDeathFire／Cold／LightningExplosion（火、冰 28–42、閃電 17–52 → 3.82／3.82／3.77 倍）；
     * 這次之前推估 2 倍。1 秒後、3 格還是推估（範圍、延遲在技能的幾何資料裡，RePoE、PoB2、poe2db 都查不到）。
     */
    static final long DEATH_BLAST_FUSE = 20;
    static final double DEATH_BLAST_RADIUS = 3;

    static double deathBlastDamage(final DamageType type) {
        return type == DamageType.LIGHTNING ? effectiveness(17, 52) : effectiveness(28, 42);
    }

    /**
     * 焦土／凍土／雷土的：2026-11-02 照 PoE2 GSMonsterModBurning／Chill／ShockedGroundNova(OnDeath)：放的時候先一圈新星
     * （火 13–20、冰 12–18、雷 8–25 → 1.80／1.64／1.80 倍），地面留 6 秒（base_skill_effect_duration 6000）、感電地面 30%
     * （shocked_ground_base_magnitude_override）；活著時跟死掉時是同一招。這次之前沒有新星、死掉 6 秒／活著 3 秒、感電 20%。
     * 範圍 2.5 格、活著時每 5 秒一次還是推估（查不到）。
     */
    static final double DEATH_GROUND_RADIUS = 2.5;
    static final long DEATH_GROUND_FOR = 120;
    static final long GROUND_EVERY = 100;
    static final double GROUND_RADIUS = DEATH_GROUND_RADIUS;
    static final long GROUND_FOR = DEATH_GROUND_FOR;
    /** 新星的前搖（看得到紅圈再炸；推估）。 */
    static final long GROUND_NOVA_FUSE = 12;

    static double groundNovaDamage(final AilmentType type) {
        return switch (type) {
            case IGNITE -> effectiveness(13, 20);
            case CHILL -> effectiveness(12, 18);
            default -> effectiveness(8, 25);
        };
    }

    static DamageType groundType(final AilmentType type) {
        return switch (type) {
            case IGNITE -> DamageType.FIRE;
            case CHILL -> DamageType.COLD;
            default -> DamageType.LIGHTNING;
        };
    }

    /**
     * 晶爆的：被打中每 1.5 秒最多長一顆、最多 6 顆（推估）；死掉時各炸 2.5 格（推估）。
     * 2026-11-02 照 PoE2 MonsterLivingCrystal：混沌 19–28（2.57 倍）、施放 1.2 秒（cast_time 1200）；這次之前推估 1 倍、1 秒。
     */
    static final long CRYSTAL_EVERY = 30;
    static final int CRYSTAL_MAX = 6;
    static final double CRYSTAL_RADIUS = 2.5;
    static final long CRYSTAL_FUSE = 24;
    static final double CRYSTAL_DAMAGE = effectiveness(19, 28);
    /**
     * 爆岩的：2026-11-02 照 PoE2 GSMonsterModVolatileRockExplode（強化的 ...Explode2 一樣）：火焰 19–28（2.57 倍）；
     * 這次之前推估 1.2 倍、強化的 1.8 倍。強化的一次兩塊、每 10 秒、引信與範圍還是推估。
     */
    static final double CRAG_DAMAGE = effectiveness(19, 28);
    /**
     * 爆花的：每 8 秒長一朵（強化的兩朵），1.5 秒後放出光球追人（每 tick 0.2 格，比跑步慢），6 秒或碰到就炸 2.5 格（推估）。
     * 傷害 2026-11-02 照 PoE2 MonsterModPlantVolatile／MonsterModPlantVolatile2：混沌 12–18／15–22（1.64／2.02 倍，monsters.yml 寫 164／202）；
     * 這次之前推估 1 倍／1.5 倍。
     */
    static final long PLANT_EVERY = 160;
    static final long PLANT_GROW = 30;
    static final double ORB_SPEED = 0.2;
    static final long ORB_LIFE = 120;
    /** 爆岩的：每 10 秒在目標腳邊落一塊（強化的兩塊），1 秒後有人走進 2 格就 0.6 秒後炸 2.5 格、1.2 倍火焰，8 秒沒人來也會炸（推估）。 */
    static final long CRAG_EVERY = 200;
    static final long CRAG_ARM = 20;
    static final long CRAG_FUSE = 12;
    static final long CRAG_LIFE = 160;
    static final double CRAG_TRIGGER = 2;
    /** 替身的：10 格內的小怪扛傷害。 */
    static final double GUARD_RADIUS = 10;
    /** 蝕生的：死掉 2 秒後爬起來。 */
    static final long RAISE_DELAY = 40;
    /** 蝕巨的：體型倍率。2026-11-02 照 PoE2 的 Gigantic（keywords.json：20% increased size；生命、傷害各更多 20% 寫在 monsters.yml）；這次之前推估 1.5。 */
    static final double GIGANTIC_SCALE = 1.2;

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
    /** 2026-11-02：狂躁的：狂暴到哪個 tick。 */
    private final Map<UUID, Long> enraged = new HashMap<>();
    /** 2026-11-02：庇護光環的：受庇護的怪物 → {光環主人, 到哪個 tick}。 */
    private final Map<UUID, Protect> protectedBy = new HashMap<>();
    /** 2026-11-02：晶爆的：長在身邊的晶體。 */
    private final Map<UUID, List<Location>> crystals = new HashMap<>();
    /** 2026-11-02：爆花（長出來、還沒放光球的）、光球、爆岩。 */
    private final List<Plant> plants = new ArrayList<>();
    private final List<Orb> orbs = new ArrayList<>();
    private final List<Crag> crags = new ArrayList<>();
    /** 2026-11-02：死後爆炸用的分身（盔甲架）→ 最晚什麼時候移除。 */
    private final Map<UUID, Long> ghosts = new HashMap<>();
    private long ticks;

    /**
     * 2026-10-23：一隻怪的特殊詞綴數值，每秒才重讀一次屬性表（以前每 2 tick 對每隻怪讀一次怪物資料與屬性表）。
     */
    record Traits(double regen, double hinder, double bubble, boolean trailFire, boolean trailCold, boolean trailLightning,
                  boolean siphon, double heal, boolean corpse, boolean revive, boolean flaskSiphon, double recoveryCap,
                  boolean soulEater, boolean pool, Extra extra) {

        static final Traits NONE = new Traits(0, 0, 0, false, false, false, false, 0, false, false, false, 0, false, false, Extra.NONE);

        static Traits of(final StatSheet sheet) {
            final Set<String> none = Set.of();
            return new Traits(sheet.flat(StatKeys.MONSTER_REGEN, none), sheet.flat(StatKeys.MONSTER_HINDER_AURA, none),
                    sheet.flat(StatKeys.MONSTER_TEMPORAL_BUBBLE, none), sheet.flat(StatKeys.MONSTER_TRAIL_FIRE, none) > 0,
                    sheet.flat(StatKeys.MONSTER_TRAIL_COLD, none) > 0, sheet.flat(StatKeys.MONSTER_TRAIL_LIGHTNING, none) > 0,
                    sheet.flat(StatKeys.MONSTER_MANA_SIPHON, none) > 0, sheet.flat(StatKeys.MONSTER_HEAL_ALLIES, none),
                    sheet.flat(StatKeys.MONSTER_CORPSE_EXPLODE, none) > 0, sheet.flat(StatKeys.MONSTER_REVIVE, none) > 0,
                    sheet.flat(StatKeys.MONSTER_FLASK_SIPHON, none) > 0, sheet.flat(StatKeys.MONSTER_RECOVERY_CAP, none),
                    sheet.flat(StatKeys.MONSTER_SOUL_EATER, none) > 0,
                    sheet.flat(StatKeys.MONSTER_SHIELD, none) > 0 || sheet.flat(StatKeys.MONSTER_MAGMA, none) > 0, Extra.of(sheet));
        }

        boolean trails() {
            return trailFire || trailCold || trailLightning;
        }
    }

    /**
     * 2026-11-02 照 PoE2 0.5 補的詞綴（分開一個紀錄，Traits 才不會太長）：狂躁、庇護光環的半徑、死掉時爆炸／地面的種類、
     * 晶爆、爆花與爆岩的強度（100 = 一般）、替身的比例、復甦、蝕生。
     */
    record Extra(boolean enrage, double invuln, List<DamageType> deathBlasts, List<AilmentType> grounds, boolean crystals, double plants,
                 double crag, double guard, boolean reviveEmpowered, boolean raise) {

        static final Extra NONE = new Extra(false, 0, List.of(), List.of(), false, 0, 0, 0, false, false);

        static Extra of(final StatSheet sheet) {
            final Set<String> none = Set.of();
            final List<DamageType> blasts = new ArrayList<>(3);
            if (sheet.flat(StatKeys.MONSTER_DEATH_BLAST_FIRE, none) > 0) {
                blasts.add(DamageType.FIRE);
            }
            if (sheet.flat(StatKeys.MONSTER_DEATH_BLAST_COLD, none) > 0) {
                blasts.add(DamageType.COLD);
            }
            if (sheet.flat(StatKeys.MONSTER_DEATH_BLAST_LIGHTNING, none) > 0) {
                blasts.add(DamageType.LIGHTNING);
            }
            final List<AilmentType> grounds = new ArrayList<>(3);
            if (sheet.flat(StatKeys.MONSTER_DEATH_GROUND_FIRE, none) > 0) {
                grounds.add(AilmentType.IGNITE);
            }
            if (sheet.flat(StatKeys.MONSTER_DEATH_GROUND_COLD, none) > 0) {
                grounds.add(AilmentType.CHILL);
            }
            if (sheet.flat(StatKeys.MONSTER_DEATH_GROUND_LIGHTNING, none) > 0) {
                grounds.add(AilmentType.SHOCK);
            }
            return new Extra(sheet.flat(StatKeys.MONSTER_PERIODIC_ENRAGE, none) > 0, sheet.flat(StatKeys.MONSTER_INVULNERABILITY_AURA, none),
                    List.copyOf(blasts), List.copyOf(grounds), sheet.flat(StatKeys.MONSTER_VOLATILE_CRYSTALS, none) > 0,
                    sheet.flat(StatKeys.MONSTER_VOLATILE_PLANTS, none), sheet.flat(StatKeys.MONSTER_VOLATILE_CRAG, none),
                    sheet.flat(StatKeys.MONSTER_MINION_SHIELD, none), sheet.flat(StatKeys.MONSTER_REVIVE_EMPOWERED, none) > 0,
                    sheet.flat(StatKeys.MONSTER_RAISE_AFTER_DEATH, none) > 0);
        }

        /** 死掉的時候有沒有事要做。 */
        boolean onDeath() {
            return !deathBlasts.isEmpty() || !grounds.isEmpty() || crystals || raise;
        }
    }

    /** 庇護光環：受庇護到哪個 tick、誰給的（主人死了就沒了）。 */
    private record Protect(UUID owner, long until) {
    }

    /** 爆花：長在地上，時間到放出光球。 */
    private record Plant(UUID source, Location at, long bloom, double power) {
    }

    /** 爆花放出的光球：追最近的玩家，碰到或時間到就炸。 */
    private static final class Orb {
        final UUID source;
        Location at;
        final long until;
        final double power;

        Orb(final UUID source, final Location at, final long until, final double power) {
            this.source = source;
            this.at = at;
            this.until = until;
            this.power = power;
        }
    }

    /** 爆岩：armed 之後有人走近就點燃引信，fuse 到了炸開；until 到了沒人來也炸。 */
    private static final class Crag {
        final UUID source;
        final Location at;
        final long armed;
        final long until;
        final double power;
        long fuse = -1;

        Crag(final UUID source, final Location at, final long armed, final long until, final double power) {
            this.source = source;
            this.at = at;
            this.armed = armed;
            this.until = until;
            this.power = power;
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

    /**
     * 2026-11-01 照站長：Boss 血條顯示的護盾（護盾的、護盾光環、換界石的額外能量護盾，加上熔岩護盾）：{剩下, 上限}；沒有回傳 null。
     */
    public double[] shieldLeft(final java.util.UUID entity) {
        final Pool pool = pools.get(entity);
        if (pool == null || pool.shieldMax + pool.magmaMax <= 0) {
            return null;
        }
        return new double[] {Math.max(0, pool.shield) + Math.max(0, pool.magma), pool.shieldMax + pool.magmaMax};
    }

    /** 2026-11-02 照站長（模組 1.4 頭上血條）：現在受庇護光環保護（5 秒內不會受傷；給的那隻還活著）。 */
    public boolean sanctuaryProtected(final java.util.UUID entity) {
        final Protect protect = protectedBy.get(entity);
        return protect != null && protect.until() > now() && plugin.getServer().getEntity(protect.owner()) instanceof LivingEntity owner
                && owner.isValid();
    }

    /** 2026-11-02（模組 1.4 頭上血條）：狂躁的現在在狂暴（「暴怒的」一直狂暴，看詞綴）。 */
    public boolean enragedNow(final java.util.UUID entity) {
        final Long until = enraged.get(entity);
        return until != null && until > now();
    }

    /** 純邏輯：血條上護盾那段的文字（剩下 ÷ 上限，四捨五入到 %；沒有護盾回傳空字串）。 */
    public static String shieldText(final double[] left) {
        if (left == null || left[1] <= 0) {
            return "";
        }
        final long percent = Math.round(Math.max(0, Math.min(1, left[0] / left[1])) * 100);
        return " <#7fc8ff>護盾 " + percent + "%";
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

    /**
     * 2026-10-28 照 PoE2（旋風矛、旋矛擊的元素旋風）：at 附近 reach 格內最新的一塊足跡／地圖地面是哪種（點燃、冰緩、感電）；沒有回傳 null。
     * 這次之前這些地面只有怪物自己用，外面查不到。
     */
    public AilmentType groundAt(final Location at, final double reach, final long now) {
        if (at == null || at.getWorld() == null) {
            return null;
        }
        final UUID world = at.getWorld().getUID();
        for (final List<Patch> list : List.of(mapPatches, patches)) {
            for (int i = list.size() - 1; i >= 0; i--) {
                final Patch patch = list.get(i);
                if (patch.until() <= now || !patch.world().equals(world) || Math.abs(at.getY() - patch.y()) > 1.5) {
                    continue;
                }
                final double dx = at.getX() - patch.x();
                final double dz = at.getZ() - patch.z();
                final double r = patch.radius() + Math.max(0, reach);
                if (dx * dx + dz * dz <= r * r) {
                    return patch.type();
                }
            }
        }
        return null;
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
    private record Blast(UUID source, Location at, long fuse, double radius, double damage, DamageType type) {
        Blast(final UUID source, final Location at, final long fuse, final double radius, final double damage) {
            this(source, at, fuse, radius, damage, DamageType.PHYSICAL);
        }
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
                if (StatKeys.MONSTER_GIGANTIC.equals(stat.stat()) && !data.boss()) {
                    // 2026-11-02 照 PoE2 腐化詞綴 Is Gigantic：體型變大（Boss 的體型由地圖設定，不改）
                    final AttributeInstance size = entity.getAttribute(Attribute.SCALE);
                    if (size != null && size.getBaseValue() < GIGANTIC_SCALE) {
                        size.setBaseValue(GIGANTIC_SCALE);
                    }
                }
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
        enraged.remove(uuid);
        protectedBy.remove(uuid);
        crystals.remove(uuid);
    }

    /** 噬魂的：吞了幾層，招式就快幾 %。 */
    private void augment(final LivingEntity entity, final StatSheet sheet) {
        final Integer stacks = souls.get(entity.getUniqueId());
        if (stacks != null && stacks > 0) {
            sheet.add(Modifier.increased(StatKeys.SKILL_COOLDOWN, -0.01 * stacks, "吞魂"));
        }
        final Long until = enraged.get(entity.getUniqueId());
        if (until != null && until > now()) {
            enrageModifiers(sheet, "狂暴");
        }
    }

    /**
     * 2026-11-02 照 PoE2 Periodically Enrages（MonsterPeriodicEnrage1）的狂暴：傷害增加 30%、技能與移動速度 +25%、受到的傷害少 33%
     * （技能速度：冷卻 ÷1.25、攻擊速度 +25%）。跟 monsters.yml 的「暴怒的」同一組數值。
     */
    static void enrageModifiers(final StatSheet sheet, final String source) {
        sheet.add(Modifier.increased(StatKeys.DAMAGE, 0.30, source));
        sheet.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, 0.25, source));
        sheet.add(Modifier.increased(StatKeys.SKILL_COOLDOWN, -0.20, source));
        sheet.add(Modifier.increased(StatKeys.ATTACK_SPEED, 0.25, source));
        sheet.add(Modifier.flat(StatKeys.DAMAGE_TAKEN, -33, source));
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
        // 2026-11-02 照 PoE2 Periodic Invulnerability Aura：受庇護的這段時間不會受傷（給的那隻死了就沒了）
        final Protect protect = protectedBy.get(defender.getUniqueId());
        if (protect != null) {
            if (protect.until() > now && plugin.getServer().getEntity(protect.owner()) instanceof LivingEntity owner && owner.isValid()) {
                if (credit != null && farWord.getOrDefault(credit.getUniqueId(), 0L) <= now && plugin.combat() != null
                        && plugin.combat().numbers() != null) {
                    plugin.combat().numbers().word(credit, defender, "庇護");
                    farWord.put(credit.getUniqueId(), now + 20);
                }
                return 0;
            }
            protectedBy.remove(defender.getUniqueId());
        }
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
        // 2026-11-02 照 PoE2 Damage Taken From Minions First（50%）：先由附近的小怪扛
        final double guardShare = sheet.flat(StatKeys.MONSTER_MINION_SHIELD, Set.of());
        if (guardShare > 0 && rest > 0) {
            rest -= guard(defender, credit, rest * Math.min(100, guardShare) / 100.0, after);
        }
        // 2026-11-02 照 PoE2 Volatile Crystals：被打中時長出晶體
        if (hit && sheet.flat(StatKeys.MONSTER_VOLATILE_CRYSTALS, Set.of()) > 0) {
            after.accept(() -> growCrystal(defender));
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
                    // 2026-11-01 照站長：被護盾吃掉的傷害也跳數字（藍色），不然 Boss 血條不動看起來像沒傷害
                    if (credit != null && plugin.combat() != null && plugin.combat().numbers() != null) {
                        plugin.combat().numbers().absorbed(credit, defender, magmaTaken + shieldTaken);
                    }
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
                            tw.exilecore.skill.Fx.spawn(defender.getWorld(), Particle.ENCHANTED_HIT, defender.getLocation().add(0, defender.getHeight() * 0.6, 0),
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

    /**
     * 替身的：找 10 格內最近的小怪（普通、魔法怪，不是 Boss），讓牠扛 want（我們的單位，最多扛到牠的生命）；回傳扛了多少。
     * 真的扣小怪的血等事件確定沒被取消（after）；打的人照扛的量算傷害（掉落分份）。
     */
    private double guard(final LivingEntity defender, final Player credit, final double want, final java.util.function.Consumer<Runnable> after) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        MonsterData bestData = null;
        for (final Entity near : defender.getNearbyEntities(GUARD_RADIUS, GUARD_RADIUS / 2, GUARD_RADIUS)) {
            if (!(near instanceof LivingEntity minion) || near instanceof Player || !minion.isValid() || minion.isDead() || !monsters.isMonster(minion)) {
                continue;
            }
            final MonsterData minionData = monsters.data(minion).orElse(null);
            if (minionData == null || minionData.boss() || minionData.rarity() == MonsterRarity.RARE || minionData.rarity() == MonsterRarity.UNIQUE) {
                continue;
            }
            final double distance = near.getLocation().distanceSquared(defender.getLocation());
            if (distance < bestDistance) {
                best = minion;
                bestDistance = distance;
                bestData = minionData;
            }
        }
        if (best == null) {
            return 0;
        }
        final double scale = Math.max(1e-9, monsters.healthScale(best, bestData));
        final double taken = Math.max(0, Math.min(want, best.getHealth() / scale));
        if (taken <= 0) {
            return 0;
        }
        final LivingEntity minion = best;
        after.accept(() -> {
            if (!minion.isValid() || minion.isDead()) {
                return;
            }
            if (credit != null) {
                monsters.ledger().record(minion.getUniqueId(), credit.getUniqueId(), taken);
            }
            final double left = minion.getHealth() - taken * scale;
            minion.setHealth(left <= 0.01 ? 0 : left);
            final Location from = defender.getLocation().add(0, defender.getHeight() * 0.6, 0);
            final Location to = minion.getLocation().add(0, minion.getHeight() * 0.6, 0);
            for (int i = 1; i <= 4; i++) {
                tw.exilecore.skill.Fx.spawn(from.getWorld(), Particle.DUST, from.clone().add(to.clone().subtract(from).toVector().multiply(i / 5.0)), 1,
                        0, 0, 0, 0, GUARD_DUST);
            }
        });
        return taken;
    }

    private static final Particle.DustOptions GUARD_DUST = new Particle.DustOptions(Color.fromRGB(0xb0b0ff), 0.9f);
    private static final Particle.DustOptions CRYSTAL_DUST = new Particle.DustOptions(Color.fromRGB(0x9b30ff), 1.3f);
    private static final Particle.DustOptions PLANT_DUST = new Particle.DustOptions(Color.fromRGB(0x5fbf3f), 1.3f);
    private static final Particle.DustOptions CRAG_DUST = new Particle.DustOptions(Color.fromRGB(0xff7a1a), 1.4f);

    /** 晶爆的：身邊長一顆晶體（每 1.5 秒最多一顆、最多 6 顆，落在牠身邊 1.5～3 格）。 */
    private void growCrystal(final LivingEntity defender) {
        if (!defender.isValid() || defender.isDead() || !ready(defender, "crystal", now(), CRYSTAL_EVERY)) {
            return;
        }
        final List<Location> list = crystals.computeIfAbsent(defender.getUniqueId(), key -> new ArrayList<>(CRYSTAL_MAX));
        if (list.size() >= CRYSTAL_MAX) {
            return;
        }
        final double angle = random.nextDouble() * Math.PI * 2;
        final double distance = 1.5 + random.nextDouble() * 1.5;
        final Location at = defender.getLocation().add(Math.cos(angle) * distance, 0.1, Math.sin(angle) * distance);
        list.add(at);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.DUST, at.clone().add(0, 0.5, 0), 8, 0.15, 0.4, 0.15, 0, CRYSTAL_DUST);
        at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.7f, 1.2f);
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
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.LAVA, at.clone().add(0, 0.5, 0), 30, MAGMA_RADIUS / 2, 0.5, MAGMA_RADIUS / 2, 0.1);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.FLAME, at.clone().add(0, 0.5, 0), 40, MAGMA_RADIUS / 2, 0.3, MAGMA_RADIUS / 2, 0.05);
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
        final boolean wasTracked = tracked.containsKey(id);
        final List<Location> grown = crystals.get(id);
        forget(id);
        if (dead instanceof Player || !monsters.isMonster(dead)) {
            return;
        }
        final long tick = now();
        if (wasTracked) {
            try {
                deathEffects(dead, grown, tick);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("怪物詞綴的死亡效果出錯（{}）", id, e);
            }
        }
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
            tw.exilecore.skill.Fx.spawn(eater.getWorld(), Particle.SOUL, dead.getLocation().add(0, 0.8, 0), 6, 0.2, 0.3, 0.2, 0.03);
        }
    }

    /**
     * 2026-11-02 照 PoE2：死掉時的詞綴效果——殉焰／殉霜／殉雷的（畫圈 1 秒後炸）、焦土／凍土／雷土的（一大塊地面）、
     * 晶爆的（長出來的晶體全部炸開）、蝕生的（2 秒後原地爬起來一次）。爆炸用死掉那隻的分身出手（MonsterService.spawnGhost）。
     * 這時怪物資料的快取已經被 MonsterListener 清掉，這裡讀完再清一次。
     */
    private void deathEffects(final LivingEntity dead, final List<Location> grown, final long now) {
        final MonsterData data = monsters.data(dead).orElse(null);
        if (data == null) {
            return;
        }
        final StatSheet sheet = monsters.sheet(dead, data);
        final Extra extra = Extra.of(sheet);
        final double hit = averageHit(sheet);
        monsters.forget(dead.getUniqueId());
        if (!extra.onDeath()) {
            return;
        }
        final Location at = dead.getLocation();
        final UUID world = at.getWorld().getUID();
        for (final AilmentType type : extra.grounds()) {
            patches.add(new Patch(world, at.getX(), at.getY(), at.getZ(), type, groundValue(type, hit), now + DEATH_GROUND_FOR,
                    DEATH_GROUND_RADIUS, null));
        }
        final boolean crystalsLeft = extra.crystals() && grown != null && !grown.isEmpty();
        if (!extra.deathBlasts().isEmpty() || crystalsLeft || !extra.grounds().isEmpty()) {
            final LivingEntity ghost = monsters.spawnGhost(dead);
            if (ghost != null) {
                ghosts.put(ghost.getUniqueId(), now + CRYSTAL_FUSE + 60);
                for (final DamageType type : extra.deathBlasts()) {
                    blasts.add(new Blast(ghost.getUniqueId(), at.clone(), now + DEATH_BLAST_FUSE, DEATH_BLAST_RADIUS, deathBlastDamage(type), type));
                }
                for (final AilmentType type : extra.grounds()) { // 2026-11-02 照 PoE2：地面先一圈新星
                    blasts.add(new Blast(ghost.getUniqueId(), at.clone(), now + GROUND_NOVA_FUSE, DEATH_GROUND_RADIUS, groundNovaDamage(type),
                            groundType(type)));
                }
                if (crystalsLeft) {
                    int i = 0;
                    for (final Location crystal : grown) {
                        blasts.add(new Blast(ghost.getUniqueId(), crystal, now + CRYSTAL_FUSE + 3L * i++, CRYSTAL_RADIUS, CRYSTAL_DAMAGE,
                                DamageType.CHAOS));
                    }
                }
            }
        }
        if (extra.raise() && !data.boss() && !monsters.isSummoned(dead)) {
            final String type = dead.getType().name();
            final List<String> mods = new ArrayList<>(data.mods());
            mods.removeIf(mod -> monsters.config().mod(mod) != null && monsters.config().mod(mod).mods().stream()
                    .anyMatch(stat -> StatKeys.MONSTER_RAISE_AFTER_DEATH.equals(stat.stat())));
            final MonsterData risen = new MonsterData(data.level(), data.rarity(), List.copyOf(mods), "復生的" + data.name(),
                    data.lifeMultiplier(), data.damageMultiplier(), false);
            final LivingEntity source = dead;
            tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SOUL, at.clone().add(0, 0.5, 0), 12, 0.4, 0.3, 0.4, 0.02);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> raise(source, type, at, risen), RAISE_DELAY);
        }
    }

    /** 蝕生的：原地爬起來（標記成召喚的：不掉東西、不給經驗；帶著原本那隻的地圖標記）。 */
    private void raise(final LivingEntity dead, final String typeName, final Location at, final MonsterData risen) {
        if (at.getWorld() == null || !at.isChunkLoaded()) {
            return;
        }
        final org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(typeName);
        } catch (final IllegalArgumentException e) {
            return;
        }
        if (type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            return;
        }
        final Entity spawned = at.getWorld().spawnEntity(at, type, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
            monsters.inheritTags(dead, created);
            if (created instanceof LivingEntity living) {
                monsters.markSummoned(living);
            }
        });
        if (!(spawned instanceof LivingEntity living) || !spawned.isValid()) {
            spawned.remove();
            return;
        }
        monsters.decorateData(living, risen);
        living.setPersistent(false);
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.SOUL, at.clone().add(0, 0.8, 0), 16, 0.4, 0.6, 0.4, 0.03);
        at.getWorld().playSound(at, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.7f, 0.7f);
    }

    /**
     * 地面的數值：點燃 = 這隻普通一下的火焰（跟焚跡的一樣），冰緩 30%（跟冰跡一樣，原作的冰緩幅度查不到）、
     * 感電 30%（2026-11-02 照 PoE2 shocked_ground_base_magnitude_override 30；這次之前 20%）。
     */
    static double groundValue(final AilmentType type, final double hit) {
        return switch (type) {
            case IGNITE -> hit;
            case CHILL -> 0.3;
            default -> 0.3;
        };
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
        tickVolatiles(now); // 2026-11-02：爆花、光球、爆岩
        if (ticks % 5 == 0) {
            tickStatus(now); // 2026-11-02：狂躁、庇護、晶體、分身
        }
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
            tw.exilecore.skill.Fx.spawn(world, Particle.DRIPPING_LAVA, at.clone().add(0, monster.getHeight() * 0.7, 0), 3, 0.35, 0.4, 0.35, 0);
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
                // 2026-11-02 照 PoE2 Temporal Bubble（action_speed_-% 25、debuff_time_passed_+% −40、base_cooldown_speed_+% −60）：
                //   動作慢 25%（冰緩 25%）、身上的減益消退慢 40%、冷卻回復慢 60%（每 0.5 秒往後推 4、6 tick）。
                //   這次之前另外被阻礙、冰緩 20%、減益與冷卻各慢一半
                plugin.ailments().groundAilment(player, AilmentType.CHILL, 0.25, 1.0);
                plugin.ailments().extendDebuffs(player, 4);
                if (plugin.skills() != null) {
                    plugin.skills().delayCooldowns(player, 6);
                }
            }
            // 2026-11-02：怪物的範圍光環走 Fx.zone（重要的：每 0.5 秒重畫、點數照周長）
            tw.exilecore.skill.Fx.zone(new tw.exilecore.skill.Fx.Part(monster.getUniqueId(), 0), at.clone().add(0, 0.15, 0), bubble,
                    tw.exilecore.skill.Fx.Look.edge(Particle.REVERSE_PORTAL, null), -1, null, true);
        }
        // 足跡：走了一段就留一塊
        if (traits.trails()) {
            trails(monster, traits, at, now);
        }
        final Extra extra = traits.extra();
        // 2026-11-02 照 PoE2 Burning／Chilled／Shocked Ground on Death（「Releases X at intervals」）：活著時每 5 秒一圈新星並留下地面
        //   （跟死掉時同一招，見 deathEffects）；這次之前只留 1.5 格、3 秒的地面，沒有新星
        if (!extra.grounds().isEmpty() && ready(monster, "ground", now, GROUND_EVERY)) {
            final StatSheet sheet = extra.grounds().contains(AilmentType.IGNITE) ? sheetOf(monster) : null;
            final double hit = sheet == null ? 0 : averageHit(sheet);
            for (final AilmentType type : extra.grounds()) {
                patches.add(new Patch(world.getUID(), at.getX(), at.getY(), at.getZ(), type, groundValue(type, hit), now + GROUND_FOR,
                        GROUND_RADIUS, null));
                blasts.add(new Blast(monster.getUniqueId(), at.clone(), now + GROUND_NOVA_FUSE, GROUND_RADIUS, groundNovaDamage(type), groundType(type)));
            }
        }
        // 晶爆的：身邊的晶體閃一下
        final List<Location> grown = extra.crystals() ? crystals.get(monster.getUniqueId()) : null;
        if (grown != null) {
            for (final Location crystal : grown) {
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, crystal.clone().add(0, 0.4, 0), 2, 0.1, 0.25, 0.1, 0, CRYSTAL_DUST);
                tw.exilecore.skill.Fx.spawn(world, Particle.END_ROD, crystal.clone().add(0, 0.7, 0), 1, 0.05, 0.05, 0.05, 0);
            }
        }
        // 狂躁的：狂暴的時候頭上冒火
        final Long rage = enraged.get(monster.getUniqueId());
        if (rage != null && rage > now) {
            tw.exilecore.skill.Fx.spawn(world, Particle.ANGRY_VILLAGER, at.clone().add(0, monster.getHeight() + 0.2, 0), 1, 0.3, 0.1, 0.3, 0);
        }
        // 噬魔的：內圈安全，外圈吸魔力、不能回魔，每秒一下閃電
        if (traits.siphon() && !held) {
            tw.exilecore.skill.Fx.zone(new tw.exilecore.skill.Fx.Part(monster.getUniqueId(), 1), at.clone().add(0, 0.15, 0), SIPHON_INNER,
                    tw.exilecore.skill.Fx.Look.edge(Particle.ELECTRIC_SPARK, null), -1, null, true);
            tw.exilecore.skill.Fx.zone(new tw.exilecore.skill.Fx.Part(monster.getUniqueId(), 2), at.clone().add(0, 0.15, 0), SIPHON_OUTER,
                    tw.exilecore.skill.Fx.Look.edge(Particle.ELECTRIC_SPARK, null), -1, null, true);
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
        // 2026-11-02 照 PoE2 Periodically Enrages：每 10 秒狂暴 5 秒（看到玩家才開始算）
        if (extra.enrage() && target != null && ready(monster, "enrage", now, ENRAGE_EVERY)) {
            enraged.put(monster.getUniqueId(), now + ENRAGE_FOR);
            monsters.refreshVanilla(monster); // 屬性表重算（傷害、速度、受到的傷害），原版的移動速度跟著變
            world.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.3f);
        }
        // 2026-11-02 照 PoE2 Periodic Invulnerability Aura：每 12 秒一圈，5 格內的友軍（不含自己）5 秒內不會受傷
        if (extra.invuln() > 0 && target != null && ready(monster, "invuln", now, INVULN_EVERY)) {
            final double radius = extra.invuln();
            int count = 0;
            for (final Entity near : monster.getNearbyEntities(radius, radius / 2, radius)) {
                if (near instanceof LivingEntity ally && !(near instanceof Player) && monsters.isMonster(ally)
                        && near.getLocation().distanceSquared(at) <= radius * radius) {
                    protectedBy.put(ally.getUniqueId(), new Protect(monster.getUniqueId(), now + INVULN_FOR));
                    count++;
                }
            }
            tw.exilecore.skill.Fx.ring(world, at.clone().add(0, 0.2, 0), radius, Particle.END_ROD, null);
            if (count > 0) {
                world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
            }
        }
        // 2026-11-02 照 PoE2 Volatile Plants：在目標附近長爆花（強化的兩朵）
        if (extra.plants() > 0 && target != null && ready(monster, "plant", now, PLANT_EVERY) && plants.size() < 64) {
            final int count = extra.plants() >= 200 ? 2 : 1; // 強化的（202）兩朵：推估
            for (int i = 0; i < count; i++) {
                final Location spot = near(target.getLocation(), 3, 5);
                plants.add(new Plant(monster.getUniqueId(), spot, now + PLANT_GROW, extra.plants() / 100.0));
                tw.exilecore.skill.Fx.spawn(world, Particle.SPORE_BLOSSOM_AIR, spot.clone().add(0, 0.3, 0), 6, 0.3, 0.2, 0.3, 0);
            }
            world.playSound(at, Sound.BLOCK_BIG_DRIPLEAF_TILT_DOWN, 0.7f, 0.8f);
        }
        // 2026-11-02 照 PoE2 Volatile Crag：在目標腳邊落爆岩（強化的兩塊）
        if (extra.crag() > 0 && target != null && ready(monster, "crag", now, CRAG_EVERY) && crags.size() < 64) {
            final int count = extra.crag() >= 150 ? 2 : 1;
            for (int i = 0; i < count; i++) {
                final Location spot = near(target.getLocation(), 0.5, 2.5);
                crags.add(new Crag(monster.getUniqueId(), spot, now + CRAG_ARM, now + CRAG_LIFE, CRAG_DAMAGE));
                tw.exilecore.skill.Fx.spawn(world, Particle.LAVA, spot.clone().add(0, 0.3, 0), 6, 0.3, 0.1, 0.3, 0);
            }
            world.playSound(target.getLocation(), Sound.BLOCK_BASALT_PLACE, 0.8f, 0.7f);
        }
        // 癒療的：每 6 秒治療附近怪物
        if (traits.heal() > 0 && target != null && ready(monster, "heal", now, 120)) {
            for (final Entity near : monster.getNearbyEntities(10, 5, 10)) {
                if (near instanceof LivingEntity ally && !(near instanceof Player) && monsters.isMonster(ally)) {
                    healPercent(ally, traits.heal());
                }
            }
            healPercent(monster, traits.heal());
            tw.exilecore.skill.Fx.spawn(world, Particle.HEART, at.clone().add(0, monster.getHeight() + 0.3, 0), 5, 0.6, 0.3, 0.6, 0);
            tw.exilecore.skill.Fx.ring(world, at.clone().add(0, 0.15, 0), 10, Particle.HAPPY_VILLAGER, null);
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
                final LivingEntity minion = effects.spawnMinion(monster, data, corpse.type(), spot, extra.reviveEmpowered()); // 2026-11-02 復甦的
                corpses.remove(corpse);
                if (minion != null) {
                    raised++;
                    if (minion instanceof Mob minionMob) {
                        minionMob.setTarget(target);
                    }
                    tw.exilecore.skill.Fx.spawn(world, Particle.SOUL, spot.clone().add(0, 0.5, 0), 12, 0.3, 0.5, 0.3, 0.03);
                }
            }
            if (raised > 0) {
                world.playSound(at, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.6f, 0.8f);
            }
        }
        // 竊瓶的：每 20 秒附近玩家的藥劑各少 1 點充能（2026-11-02 照 PoE2 每分鐘 −3 充能；這次之前每 5 秒）
        if (traits.flaskSiphon() && plugin.flasks() != null && ready(monster, "flask", now, 400)) {
            for (final Player player : at.getNearbyPlayers(12)) {
                plugin.flasks().drainCharges(player, 1);
                tw.exilecore.skill.Fx.spawn(player.getWorld(), Particle.WITCH, player.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
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
            // 2026-10-28 照 PoE2 Ignited Ground：視同這隻怪一下的火焰傷害（疊易燃到 50% 才點燃，點燃每秒 20%）；這次之前直接每秒 25%
            patches.add(new Patch(world, at.getX(), at.getY(), at.getZ(), AilmentType.IGNITE, hit, now + 60));
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
        // 2026-10-28 照 PoE2：同一種地面不疊加——一個玩家同時踩在好幾塊上，每種只算最強的一塊（這次之前每一塊各上一次，易燃／點燃疊得更快）
        final Map<Player, Map<AilmentType, Double>> standing = new HashMap<>();
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
                    standing.computeIfAbsent(player, key -> new java.util.EnumMap<>(AilmentType.class)).merge(patch.type(), patch.value(), Math::max);
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
                // 2026-11-02 照站長（粒子太多）：每 0.5 秒畫一次（以前每 5 tick），一次多畫一點；地圖的地面效果是危險區（重要的，每個人都看得到）
                tw.exilecore.skill.Fx.with(null, patch.owner() != null, () -> {
                    if (tw.exilecore.skill.Fx.redrawDue(patch)) {
                        tw.exilecore.skill.Fx.spawn(world, switch (patch.type()) {
                            case IGNITE -> Particle.FLAME;
                            case CHILL -> Particle.SNOWFLAKE;
                            default -> Particle.ELECTRIC_SPARK;
                        }, patch.x(), patch.y() + 0.1, patch.z(), patch.owner() == null ? 5 : 3 + (int) Math.round(patch.radius() * 3),
                                patch.radius() * 0.4, 0.05, patch.radius() * 0.4, 0.01);
                    }
                    if (patch.type() == AilmentType.IGNITE) {
                        // 2026-10-27 照站長「怪物放的燃燒地板太不明顯」：畫一圈外框（玩家自己的燃燒地面不畫）
                        tw.exilecore.skill.Fx.zone(patch, new Location(world, patch.x(), patch.y() + 0.08, patch.z()), patch.radius(),
                                tw.exilecore.skill.Fx.Look.edge(Particle.DUST, tw.exilecore.skill.Fx.BURNING_EDGE), (int) (patch.until() - now),
                                null, patch.owner() != null);
                    }
                });
            }
        }
        for (final Map.Entry<Player, Map<AilmentType, Double>> entry : standing.entrySet()) {
            for (final Map.Entry<AilmentType, Double> ground : entry.getValue().entrySet()) {
                plugin.ailments().groundAilment(entry.getKey(), ground.getKey(), ground.getValue(), 2.0);
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
            tw.exilecore.skill.Fx.spawn(mirage.at.getWorld(), Particle.ELECTRIC_SPARK, mirage.at, 6, 0.15, 0.3, 0.15, 0.02);
            tw.exilecore.skill.Fx.spawn(mirage.at.getWorld(), Particle.DUST, mirage.at, 3, 0.15, 0.4, 0.15, 0,
                    new Particle.DustOptions(Color.fromRGB(0x9fd3ff), 1.2f));
        }
    }

    private void explodeMirage(final LivingEntity source, final Location at) {
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.ELECTRIC_SPARK, at, 30, 1, 0.6, 1, 0.1);
        at.getWorld().playSound(at, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.6f, 1.6f);
        for (final Player player : at.getNearbyPlayers(2.5)) {
            hit(source, player, "lightning_mirage", DamageType.LIGHTNING, 0.8);
        }
    }

    private static final Particle.DustOptions BLAST_WARNING = new Particle.DustOptions(Color.fromRGB(0xc03030), 1.1f);

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
                // 2026-11-02：預警的圈走 Fx.zone（重要的：每個人都看得到；每 0.5 秒重畫，點大一點存在久一點）
                tw.exilecore.skill.Fx.zone(blast, blast.at().clone().add(0, 0.15, 0), blast.radius(), tw.exilecore.skill.Fx.Look.edge(Particle.DUST, BLAST_WARNING),
                        (int) (blast.fuse() - now), null, true);
                continue;
            }
            iterator.remove();
            tw.exilecore.skill.Fx.zoneStop(blast);
            tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION, blast.at().clone().add(0, 0.5, 0), 2, 0.4, 0.2, 0.4, 0);
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, blast.at().clone().add(0, 0.5, 0), 30, 1.2, 0.4, 1.2, 0,
                    new Particle.DustOptions(Color.fromRGB(0x8a0e0e), 1.4f));
            world.playSound(blast.at(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f);
            final Entity source = plugin.getServer().getEntity(blast.source());
            if (!(source instanceof LivingEntity living) || !living.isValid()) {
                continue;
            }
            if (blast.type() != DamageType.PHYSICAL) {
                // 2026-11-02：死後爆炸、爆晶的顏色照傷害類型
                tw.exilecore.skill.Fx.spawn(world, switch (blast.type()) {
                    case FIRE -> Particle.FLAME;
                    case COLD -> Particle.SNOWFLAKE;
                    case LIGHTNING -> Particle.ELECTRIC_SPARK;
                    default -> Particle.WITCH;
                }, blast.at().clone().add(0, 0.5, 0), 30, blast.radius() / 2, 0.4, blast.radius() / 2, 0.05);
            }
            for (final Player player : blast.at().getNearbyPlayers(blast.radius())) {
                hit(living, player, blast.type() == DamageType.PHYSICAL ? "corpse_blast" : "death_blast", blast.type(), blast.damage());
            }
        }
    }

    // ---- 2026-11-02：狂躁、庇護、晶體、分身（每 0.5 秒） ----

    private void tickStatus(final long now) {
        for (final Iterator<Map.Entry<UUID, Long>> iterator = enraged.entrySet().iterator(); iterator.hasNext(); ) {
            final Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                iterator.remove();
                if (plugin.getServer().getEntity(entry.getKey()) instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                    monsters.refreshVanilla(living); // 狂暴結束：屬性表、原版移動速度回來
                } else {
                    monsters.invalidate(entry.getKey());
                }
            }
        }
        for (final Iterator<Map.Entry<UUID, Protect>> iterator = protectedBy.entrySet().iterator(); iterator.hasNext(); ) {
            final Map.Entry<UUID, Protect> entry = iterator.next();
            final Entity ally = plugin.getServer().getEntity(entry.getKey());
            if (entry.getValue().until() <= now || !(plugin.getServer().getEntity(entry.getValue().owner()) instanceof LivingEntity owner)
                    || !owner.isValid() || !(ally instanceof LivingEntity living) || !living.isValid()) {
                iterator.remove();
                continue;
            }
            tw.exilecore.skill.Fx.spawn(ally.getWorld(), Particle.END_ROD, ally.getLocation().add(0, living.getHeight() * 0.6, 0), 2,
                    0.3, 0.4, 0.3, 0.01);
        }
        for (final Iterator<Map.Entry<UUID, Long>> iterator = ghosts.entrySet().iterator(); iterator.hasNext(); ) {
            final Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() > now && hasBlast(entry.getKey())) {
                continue;
            }
            iterator.remove();
            final Entity ghost = plugin.getServer().getEntity(entry.getKey());
            if (ghost != null) {
                ghost.remove();
            }
        }
    }

    private boolean hasBlast(final UUID source) {
        for (final Blast blast : blasts) {
            if (blast.source().equals(source)) {
                return true;
            }
        }
        return false;
    }

    // ---- 2026-11-02：爆花、光球、爆岩（每 2 tick） ----

    private void tickVolatiles(final long now) {
        if (plants.isEmpty() && orbs.isEmpty() && crags.isEmpty()) {
            return;
        }
        for (final Iterator<Plant> iterator = plants.iterator(); iterator.hasNext(); ) {
            final Plant plant = iterator.next();
            if (plant.at().getWorld() == null || !(plugin.getServer().getEntity(plant.source()) instanceof LivingEntity source) || !source.isValid()) {
                iterator.remove();
                continue;
            }
            if (now < plant.bloom()) {
                if (ticks % 5 == 0) {
                    tw.exilecore.skill.Fx.spawn(plant.at().getWorld(), Particle.DUST, plant.at().clone().add(0, 0.3, 0), 3, 0.15, 0.2, 0.15, 0, PLANT_DUST);
                }
                continue;
            }
            iterator.remove();
            orbs.add(new Orb(plant.source(), plant.at().clone().add(0, 1, 0), now + ORB_LIFE, plant.power()));
            plant.at().getWorld().playSound(plant.at(), Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.8f, 1.4f);
        }
        for (final Iterator<Orb> iterator = orbs.iterator(); iterator.hasNext(); ) {
            final Orb orb = iterator.next();
            final World world = orb.at.getWorld();
            if (world == null || !(plugin.getServer().getEntity(orb.source) instanceof LivingEntity source) || !source.isValid()) {
                iterator.remove();
                continue;
            }
            Player nearest = null;
            double best = 12 * 12;
            for (final Player player : orb.at.getNearbyPlayers(12)) {
                final double distance = player.getLocation().add(0, 1, 0).distanceSquared(orb.at);
                if (distance < best && !player.isDead()) {
                    nearest = player;
                    best = distance;
                }
            }
            if (now >= orb.until || nearest != null && best <= 1.2 * 1.2) {
                iterator.remove();
                volatileBurst(source, orb.at, DamageType.CHAOS, orb.power, PLANT_DUST, "volatile_plant");
                continue;
            }
            if (nearest != null) {
                final org.bukkit.util.Vector step = nearest.getLocation().add(0, 1, 0).toVector().subtract(orb.at.toVector());
                final double length = step.length();
                if (length > 1e-6) {
                    orb.at = orb.at.add(step.multiply(Math.min(ORB_SPEED * 2, length) / length)); // 每 2 tick 走兩步
                }
            }
            tw.exilecore.skill.Fx.spawn(world, Particle.DUST, orb.at, 3, 0.12, 0.12, 0.12, 0, PLANT_DUST);
            tw.exilecore.skill.Fx.spawn(world, Particle.SNEEZE, orb.at, 1, 0.05, 0.05, 0.05, 0.01);
        }
        for (final Iterator<Crag> iterator = crags.iterator(); iterator.hasNext(); ) {
            final Crag crag = iterator.next();
            final World world = crag.at.getWorld();
            if (world == null || !(plugin.getServer().getEntity(crag.source) instanceof LivingEntity source) || !source.isValid()) {
                if (crag.fuse >= 0) {
                    tw.exilecore.skill.Fx.zoneStop(crag);
                }
                iterator.remove();
                continue;
            }
            if (crag.fuse < 0 && (now >= crag.until || now >= crag.armed && !crag.at.getNearbyPlayers(CRAG_TRIGGER).isEmpty())) {
                crag.fuse = now + CRAG_FUSE; // 有人走近（或放太久）：點燃引信，地上畫出爆炸的範圍
                world.playSound(crag.at, Sound.ENTITY_TNT_PRIMED, 0.7f, 1.3f);
            }
            if (crag.fuse >= 0) {
                if (now >= crag.fuse) {
                    iterator.remove();
                    tw.exilecore.skill.Fx.zoneStop(crag);
                    volatileBurst(source, crag.at, DamageType.FIRE, crag.power, CRAG_DUST, "volatile_crag");
                    continue;
                }
                tw.exilecore.skill.Fx.zone(crag, crag.at.clone().add(0, 0.15, 0), CRYSTAL_RADIUS,
                        tw.exilecore.skill.Fx.Look.edge(Particle.DUST, BLAST_WARNING), (int) (crag.fuse - now), null, true);
            } else if (ticks % 5 == 0) {
                tw.exilecore.skill.Fx.spawn(world, Particle.DUST, crag.at.clone().add(0, 0.3, 0), 4, 0.25, 0.15, 0.25, 0, CRAG_DUST);
                if (now >= crag.armed) {
                    tw.exilecore.skill.Fx.spawn(world, Particle.SMALL_FLAME, crag.at.clone().add(0, 0.4, 0), 1, 0.1, 0.05, 0.1, 0);
                }
            }
        }
    }

    /** 爆花的光球、爆岩炸開：2.5 格內的玩家各挨一下（牠普通一下的 effectiveness 倍）。 */
    private void volatileBurst(final LivingEntity source, final Location at, final DamageType type, final double effectiveness,
                               final Particle.DustOptions dust, final String id) {
        final World world = at.getWorld();
        tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION, at, 1, 0.2, 0.2, 0.2, 0);
        tw.exilecore.skill.Fx.spawn(world, Particle.DUST, at, 24, CRYSTAL_RADIUS / 2, 0.4, CRYSTAL_RADIUS / 2, 0, dust);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
        for (final Player player : at.getNearbyPlayers(CRYSTAL_RADIUS)) {
            hit(source, player, id, type, effectiveness);
        }
    }

    /** center 周圍 min～max 格的一個隨機點（同一個高度）。 */
    private Location near(final Location center, final double min, final double max) {
        final double angle = random.nextDouble() * Math.PI * 2;
        final double distance = min + random.nextDouble() * Math.max(0, max - min);
        return center.clone().add(Math.cos(angle) * distance, 0.05, Math.sin(angle) * distance);
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
}
