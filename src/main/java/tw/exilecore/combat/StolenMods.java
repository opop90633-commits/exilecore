package tw.exilecore.combat;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import tw.exilecore.ExileCore;
import tw.exilecore.character.PlayerState;
import tw.exilecore.monster.MonsterConfig;
import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 2026-11-11 照站長「稀有怪物詞綴有哪些玩家不能用斬獲腰環之類的拿到效果…照原作補（PoE2 的）」：偷到（斬獲腰環、斬首輔助、
 * 儀典獻祭、皮囊偶符）的怪物詞綴，照 PoE2 Headhunter 偷到時玩家拿到的效果。這次之前只有詞綴上的數值會套，
 * 怪物專用的屬性（monster.*）套在玩家身上沒有作用、招式詞綴什麼都沒有。
 * <p>
 * 換算（{@link #modifiers}）：
 * <ul>
 *   <li>再生的 → 每秒回復 2% 最大生命；護盾的／護盾光環的 → 等同最大生命 25%／20% 的額外能量護盾（PoE2 Extra Energy Shield／ES Aura）；
 *       統御的 → 你的召喚物增加 50% 最大生命、25% 傷害（PoE2 Powerful Minions）。</li>
 *   <li>其他行為詞綴在屬性表上掛一個旗標 {@code stolen.<詞綴代號>}（數值照那條詞綴自己的數值，沒有的寫 1），
 *       這個類別每 {@value #PERIOD} tick 照旗標做玩家版的效果；增益到期旗標就沒了。</li>
 * </ul>
 * 傷害照 PoE2 偷到的怪物招式：照「你這個等級的怪物普通一下」×招式的倍率（{@code monsters.yml} 的 damage 表；倍率照 boss_skills.yml、
 * MonsterMechanics 同一招的數值），算是你打的（吃你的傷害加成、算你的擊殺）。只打怪物。
 * 只有怪物有、PoE2 也查不到玩家版的沒做：蝕朽的、蝕生的、蝕巨的的變大，以及我們自己加的招式詞綴（猛擊、衝鋒、齊射、撲擊、閃現、震地、
 * 詛咒、隕石、吐火、吐霜、召喚的）。
 */
public final class StolenMods implements Listener {

    /** 屬性表上的旗標：{@code stolen.<詞綴代號>}。 */
    public static final String FLAG_PREFIX = "stolen.";
    /** 多久跑一次（tick）。 */
    public static final long PERIOD = 10;

    /** 掛旗標、由這個類別做效果的詞綴（monsters.yml 的代號）→ 給玩家看的說明。 */
    static final Map<String, String> EFFECTS = new LinkedHashMap<>();

    static {
        EFFECTS.put("hindering", "附近的敵人被阻礙（移動速度減少 30%）");
        EFFECTS.put("temporal_bubble", "時間泡泡：裡面的敵人被冰緩 25%");
        EFFECTS.put("fire_trail", "走過的地上留下 3 秒的燃燒地面");
        EFFECTS.put("cold_trail", "走過的地上留下 4 秒的冰緩地面");
        EFFECTS.put("lightning_trail", "走過的地上留下 4 秒的感電地面");
        EFFECTS.put("healer", "每 6 秒治療你與附近的隊友 10% 最大生命");
        EFFECTS.put("corpse_exploder", "每 6 秒引爆附近的一具屍體（物理，照屍體的最大生命）");
        EFFECTS.put("flask_siphon", "你的藥劑每 20 秒少 1 點充能");
        EFFECTS.put("recovery_cap", "附近的敵人生命回復不能超過 50%");
        EFFECTS.put("reviver", "每 8 秒復活兩隻倒下等復活的常駐召喚物");
        EFFECTS.put("empowered_reviver", "每 8 秒復活兩隻倒下等復活的常駐召喚物");
        EFFECTS.put("magma_barrier", "熔岩護盾（最大生命 20%）先吃擊中，打破時爆炸（火焰），8 秒後回來");
        EFFECTS.put("mirage", "被擊中時 25% 機率放出閃電幻影，衝向敵人自爆（閃電）");
        EFFECTS.put("mana_siphon", "身邊 3～7 格一圈每秒閃電傷害");
        EFFECTS.put("proximal", "只有 5 格內的敵人打得到你");
        EFFECTS.put("soul_eater", "附近的敵人死掉時吞魂（最多 50）：每個魂 +1% 技能速度、受到的傷害減少 1%");
        EFFECTS.put("periodic_enrage", "每 10 秒狂暴 5 秒（同暴怒的）");
        EFFECTS.put("invulnerability_aura", "每 12 秒，5 格內的隊友 5 秒內不會受傷（不含你）");
        EFFECTS.put("empowered_invulnerability_aura", "每 12 秒，5 格內的隊友 5 秒內不會受傷（不含你）");
        EFFECTS.put("fire_death_blast", "你死掉時爆炸（火焰）");
        EFFECTS.put("cold_death_blast", "你死掉時爆炸（冰冷）");
        EFFECTS.put("lightning_death_blast", "你死掉時爆炸（閃電）");
        EFFECTS.put("burning_ground", "每 5 秒放一圈火焰新星，留下 6 秒的燃燒地面");
        EFFECTS.put("chilled_ground", "每 5 秒放一圈冰霜新星，留下 6 秒的冰緩地面");
        EFFECTS.put("shocked_ground", "每 5 秒放一圈閃電新星，留下 6 秒的感電地面");
        EFFECTS.put("volatile_crystals", "被擊中時身邊長出晶體（最多 6 顆），3 秒後炸開（混沌）");
        EFFECTS.put("volatile_plants", "每 8 秒在敵人附近長一朵爆花，放出光球追敵人（混沌）");
        EFFECTS.put("empowering_volatile_plants", "每 8 秒在敵人附近長兩朵爆花，放出光球追敵人（混沌）");
        EFFECTS.put("volatile_crag", "每 10 秒在敵人腳邊落一塊爆岩，敵人走近就炸（火焰）");
        EFFECTS.put("empowering_volatile_crag", "每 10 秒在敵人腳邊落兩塊爆岩，敵人走近就炸（火焰）");
        EFFECTS.put("minion_shield", "擊中傷害的 50% 先由附近你的召喚物承受");
        EFFECTS.put("fire_unleasher", "每 9 秒放一圈火焰新星");
        EFFECTS.put("ice_unleasher", "每 9 秒放一圈冰霜新星");
        EFFECTS.put("lightning_unleasher", "每 9 秒放一圈閃電新星");
        EFFECTS.put("fire_bomber", "每 10 秒在敵人身上連爆三次（火焰）");
        EFFECTS.put("cold_bomber", "每 10 秒在敵人身上連爆三次（冰冷）");
        EFFECTS.put("lightning_bomber", "每 10 秒在敵人身上連爆三次（閃電）");
        EFFECTS.put("flamewall", "每 14 秒在敵人附近燒起焰牆");
        EFFECTS.put("ice_prison", "每 18 秒在敵人周圍立起一圈冰牢（冰冷、冰緩）");
        EFFECTS.put("stormcaller", "每 16 秒在敵人附近落五道雷");
        EFFECTS.put("bombardier", "每 9 秒朝敵人發射三發砲彈（火焰）");
        EFFECTS.put("elemental_hazards", "輪流在敵人附近放火、冰、雷的危險地面");
        EFFECTS.put("shroud_walker", "每 8 秒瞬移到遠處的敵人身邊，起點、終點各一團煙霧（致盲）");
    }

    // ---- 數值（照 MonsterMechanics、boss_skills.yml 同一招；PoE2 有寫的照寫，其他推估）----
    static final double HINDER_SLOW = 0.3;
    static final double BUBBLE_CHILL = 0.25;
    static final long FIRE_TRAIL_TICKS = 60;
    static final long TRAIL_TICKS = 80;
    static final double TRAIL_RADIUS = 1.3;
    static final long HEAL_EVERY = 120;
    static final double HEAL_RADIUS = 10;
    static final long CORPSE_EVERY = 120;
    static final double CORPSE_PERCENT = 0.05;
    static final long FLASK_EVERY = 400;
    static final double CAP_RADIUS = 12;
    static final long REVIVE_EVERY = 160;
    static final double MAGMA_PERCENT = 20;
    static final long MAGMA_RETURN = 160;
    static final double MAGMA_RADIUS = 4;
    static final double MAGMA_DAMAGE = 2.0;
    static final double MIRAGE_CHANCE = 0.25;
    static final long MIRAGE_COOLDOWN = 40;
    static final double MIRAGE_SPEED = 0.3;
    static final double MIRAGE_RADIUS = 2.5;
    static final double MIRAGE_DAMAGE = 0.8;
    static final double SIPHON_INNER = 3;
    static final double SIPHON_OUTER = 7;
    static final double SIPHON_DAMAGE = 0.15;
    static final double PROXIMAL_RADIUS = 5;
    static final double SOUL_RADIUS = 15;
    static final int SOUL_MAX = 50;
    static final long ENRAGE_EVERY = 200;
    static final long ENRAGE_FOR = 100;
    static final long INVULN_EVERY = 240;
    static final long INVULN_FOR = 100;
    static final double INVULN_RADIUS = 5;
    static final double DEATH_BLAST_RADIUS = 3;
    static final long GROUND_EVERY = 100;
    static final double GROUND_RADIUS = 2.5;
    static final long GROUND_FOR = 120;
    static final long CRYSTAL_EVERY = 30;
    static final int CRYSTAL_MAX = 6;
    static final long CRYSTAL_FUSE = 60;
    static final double CRYSTAL_RADIUS = 2.5;
    static final long PLANT_EVERY = 160;
    static final long PLANT_GROW = 30;
    static final double ORB_SPEED = 0.2;
    static final long ORB_LIFE = 120;
    static final long CRAG_EVERY = 200;
    static final long CRAG_ARM = 20;
    static final long CRAG_LIFE = 160;
    static final double CRAG_TRIGGER = 2;
    static final double MINION_SHIELD_RADIUS = 10;
    static final long SHROUD_EVERY = 160;
    static final double SHROUD_MIN = 10;
    static final double SHROUD_MAX = 30;
    static final long SHROUD_BLIND = 80;
    /** 看得到敵人才放主動的招式（城鎮、沒怪的地方不放）。 */
    static final double ENGAGE_RANGE = 16;

    /** 1 級的平均傷害 min～max 換成「普通一下的幾倍」（同 MonsterMechanics.effectiveness）。 */
    static double effectiveness(final double min, final double max) {
        return (min + max) / 2 / 9.16;
    }

    static final double DEATH_BLAST = effectiveness(28, 42);
    static final double DEATH_BLAST_LIGHTNING = effectiveness(17, 52);
    static final double CRYSTAL_DAMAGE = effectiveness(19, 28);
    static final double CRAG_DAMAGE = effectiveness(19, 28);

    private final ExileCore plugin;
    private final Map<UUID, Own> owns = new HashMap<>();
    private final List<Patch> patches = new ArrayList<>();
    private final List<Orb> orbs = new ArrayList<>();
    private final List<Crag> crags = new ArrayList<>();
    private final Map<String, SkillProfile> profiles = new HashMap<>();
    private long ticks;

    /** 每個玩家的狀態。 */
    static final class Own {
        final Map<String, Long> next = new HashMap<>();
        double magma;
        long magmaBack;
        boolean magmaUp;
        int souls;
        Location lastTrail;
        final List<long[]> crystalTimes = new ArrayList<>();
        final List<Location> crystals = new ArrayList<>();
        long nextCrystal;
        long nextMirage;
        final Map<UUID, Double> lastHealth = new HashMap<>();
        int hazard;
    }

    /** 一塊地面：owner 放的、照 type 給效果；dps 是每秒傷害（0 = 不打傷害）。 */
    record Patch(UUID owner, Location at, double radius, DamageType type, double dps, double chill, double shock, double ignite, long until) {
    }

    static final class Orb {
        final UUID owner;
        Location at;
        final long until;
        final double power;

        Orb(final UUID owner, final Location at, final long until, final double power) {
            this.owner = owner;
            this.at = at;
            this.until = until;
            this.power = power;
        }
    }

    record Crag(UUID owner, Location at, long armed, long until) {
    }

    public StolenMods(final ExileCore plugin) {
        this.plugin = plugin;
    }

    // =====================================================================
    // 換算
    // =====================================================================

    /**
     * 怪物詞綴換成玩家身上的修飾器：詞綴的數值直接套、「附加 X% 為某元素」變成獲得額外傷害、極速的變成增加移動速度、
     * 光環的詞綴給光環裡的數值；怪物專用的屬性照 PoE2 換成玩家版（見類別說明），行為詞綴掛旗標。對玩家沒有任何效果的回傳空的。
     */
    public static List<Modifier> modifiers(final MonsterConfig.MonsterMod mod, final String source) {
        final List<Modifier> modifiers = new ArrayList<>();
        double flag = 0;
        for (final tw.exilecore.item.AffixMod affixMod : mod.mods()) {
            final Modifier modifier = affixMod.toModifier(Map.of(), source);
            if (modifier.stat().startsWith(MONSTER_PREFIX)) {
                flag = Math.max(flag, convertMonsterStat(modifier, source, modifiers));
            } else {
                modifiers.add(modifier);
            }
        }
        for (final Map.Entry<DamageType, Double> entry : mod.addedFromPhysical().entrySet()) {
            modifiers.add(Modifier.flat(StatKeys.damageGain(entry.getKey()), entry.getValue() * 100, source));
        }
        if (mod.vanillaSpeedBonus() > 0) {
            modifiers.add(Modifier.increased(StatKeys.MOVEMENT_SPEED, mod.vanillaSpeedBonus(), source));
        }
        if (mod.aura() != null) {
            for (final tw.exilecore.item.AffixMod affixMod : mod.aura().stats()) {
                final Modifier modifier = affixMod.toModifier(Map.of(), source);
                if (modifier.stat().startsWith(MONSTER_PREFIX)) {
                    convertMonsterStat(modifier, source, modifiers);
                } else {
                    modifiers.add(modifier);
                }
            }
        }
        if (EFFECTS.containsKey(mod.id())) {
            modifiers.add(Modifier.flat(FLAG_PREFIX + mod.id(), flag > 0 ? flag : 1, source));
        }
        return modifiers;
    }

    private static final String MONSTER_PREFIX = "monster.";

    /** 怪物專用的屬性換成玩家版（加進 out）；回傳這條要當旗標的數值（換成屬性的、沒有玩家版的回傳 0）。 */
    private static double convertMonsterStat(final Modifier modifier, final String source, final List<Modifier> out) {
        final double value = modifier.value();
        switch (modifier.stat()) {
            case StatKeys.MONSTER_REGEN -> out.add(Modifier.flat(StatKeys.LIFE_REGEN_PERCENT, value, source));
            case StatKeys.MONSTER_SHIELD -> out.add(Modifier.flat(tw.exilecore.item.unique.UniqueStats.ES_FROM_LIFE, value, source));
            case StatKeys.MONSTER_POWERFUL_MINIONS -> {
                out.add(new Modifier(StatKeys.LIFE_MAX, ModType.INCREASED, 0.5, Set.of(Tags.MINION), source));
                out.add(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, 0.25, Set.of(Tags.MINION), source));
            }
            case StatKeys.MONSTER_GIGANTIC, StatKeys.MONSTER_CULLABLE, StatKeys.MONSTER_RAISE_AFTER_DEATH -> {
                return 0; // 只有怪物有（PoE2 查不到玩家版）
            }
            default -> {
                return value;
            }
        }
        return 0;
    }

    /** 旗標的說明（{@code stolen.<代號>}）；不是旗標回傳 null。 */
    public static String describe(final String stat) {
        return stat != null && stat.startsWith(FLAG_PREFIX) ? EFFECTS.get(stat.substring(FLAG_PREFIX.length())) : null;
    }

    // =====================================================================
    // 每 PERIOD tick
    // =====================================================================

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    private static double flag(final StatSheet sheet, final String id) {
        return sheet.flat(FLAG_PREFIX + id, Set.of());
    }

    /** 這個人身上有沒有任何偷到的行為詞綴。 */
    private static boolean any(final StatSheet sheet) {
        for (final String id : EFFECTS.keySet()) {
            if (flag(sheet, id) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean ready(final Own own, final String what, final long now, final long every) {
        if (own.next.getOrDefault(what, 0L) > now) {
            return false;
        }
        own.next.put(what, now + every);
        return true;
    }

    public void tick() {
        ticks++;
        final long now = now();
        for (final Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isDead() || !plugin.players().get(player).hasCharacter()) {
                continue;
            }
            final StatSheet sheet = plugin.players().sheet(player);
            if (!any(sheet)) {
                final Own gone = owns.remove(player.getUniqueId());
                if (gone != null && gone.souls > 0) {
                    plugin.players().markDirty(player); // 噬魂的到期：魂一起沒了
                }
                continue;
            }
            final Own own = owns.computeIfAbsent(player.getUniqueId(), id -> new Own());
            try {
                tickPlayer(player, sheet, own, now);
            } catch (final RuntimeException e) {
                plugin.getSLF4JLogger().warn("偷到的詞綴（{}）出錯", player.getName(), e);
            }
        }
        tickPatches(now);
        tickOrbs(now);
        tickCrags(now);
    }

    private void tickPlayer(final Player player, final StatSheet sheet, final Own own, final long now) {
        final Location at = player.getLocation();
        final World world = at.getWorld();
        final PlayerState state = plugin.players().get(player);
        final double hit = baseHit(state);
        final LivingEntity target = nearestEnemy(player, at, ENGAGE_RANGE);
        // 阻礙的、時滯的：身邊的敵人
        final double hinder = flag(sheet, "hindering");
        if (hinder > 0 && plugin.ailments() != null) {
            for (final LivingEntity enemy : enemies(player, at, hinder)) {
                plugin.ailments().groundOnMonster(enemy, HINDER_SLOW, 0, PERIOD + 5);
            }
            if (ticks % 2 == 0) {
                tw.exilecore.skill.Fx.ring(world, at.clone().add(0, 0.15, 0), hinder, Particle.WITCH, null);
            }
        }
        final double bubble = flag(sheet, "temporal_bubble");
        if (bubble > 0 && plugin.ailments() != null) {
            for (final LivingEntity enemy : enemies(player, at, bubble)) {
                plugin.ailments().groundStatusOnMonster(enemy, BUBBLE_CHILL, 0, PERIOD + 5);
            }
            if (ticks % 2 == 0) {
                tw.exilecore.skill.Fx.ring(world, at.clone().add(0, 0.15, 0), bubble, Particle.REVERSE_PORTAL, null);
            }
        }
        // 足跡
        final boolean fireTrail = flag(sheet, "fire_trail") > 0;
        final boolean coldTrail = flag(sheet, "cold_trail") > 0;
        final boolean shockTrail = flag(sheet, "lightning_trail") > 0;
        if ((fireTrail || coldTrail || shockTrail)
                && (own.lastTrail == null || !own.lastTrail.getWorld().equals(world) || own.lastTrail.distanceSquared(at) >= 1.0)) {
            own.lastTrail = at.clone();
            if (fireTrail) {
                patches.add(new Patch(player.getUniqueId(), at.clone(), TRAIL_RADIUS, DamageType.FIRE, 0, 0, 0, hit, now + FIRE_TRAIL_TICKS));
            }
            if (coldTrail) {
                patches.add(new Patch(player.getUniqueId(), at.clone(), TRAIL_RADIUS, DamageType.COLD, 0, 0.3, 0, 0, now + TRAIL_TICKS));
            }
            if (shockTrail) {
                patches.add(new Patch(player.getUniqueId(), at.clone(), TRAIL_RADIUS, DamageType.LIGHTNING, 0, 0, 0.2, 0, now + TRAIL_TICKS));
            }
        }
        // 癒療的
        final double heal = flag(sheet, "healer");
        if (heal > 0 && target != null && ready(own, "heal", now, HEAL_EVERY)) {
            for (final Player ally : at.getNearbyPlayers(HEAL_RADIUS)) {
                if (ally.isDead() || !plugin.players().get(ally).hasCharacter()) {
                    continue;
                }
                final PlayerState allyState = plugin.players().get(ally);
                allyState.setLife(Math.min(allyState.maxLife(), allyState.life() + allyState.maxLife() * heal / 100.0));
                plugin.players().display(ally, allyState);
            }
            tw.exilecore.skill.Fx.spawn(world, Particle.HEART, at.clone().add(0, 2.2, 0), 4, 0.5, 0.2, 0.5, 0);
            tw.exilecore.skill.Fx.ring(world, at.clone().add(0, 0.15, 0), HEAL_RADIUS, Particle.HAPPY_VILLAGER, null);
        }
        // 爆屍的
        if (flag(sheet, "corpse_exploder") > 0 && target != null && ready(own, "corpse", now, CORPSE_EVERY)) {
            final Corpses corpses = plugin.combat().corpses();
            final Corpses.Corpse corpse = corpses.nearest(world.getUID(), at.getX(), at.getZ(), 8, now);
            if (corpse != null) {
                corpses.remove(corpse);
                final Location spot = new Location(world, corpse.x(), corpse.y() + 0.5, corpse.z());
                delayedBlast(player, spot, 3, corpse.maxLife() * CORPSE_PERCENT, DamageType.PHYSICAL, "corpse_explode", 20);
            }
        }
        // 竊瓶的（缺點）
        if (flag(sheet, "flask_siphon") > 0 && plugin.flasks() != null && ready(own, "flask", now, FLASK_EVERY)) {
            plugin.flasks().drainCharges(player, 1);
            tw.exilecore.skill.Fx.spawn(world, Particle.WITCH, at.clone().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
        }
        // 抑生的：附近的敵人生命回復不能超過 50%（比上一次高、又超過一半的拉回去）
        if (flag(sheet, "recovery_cap") > 0) {
            capRecovery(player, own, at);
        } else {
            own.lastHealth.clear();
        }
        // 喚靈的、復甦的
        if ((flag(sheet, "reviver") > 0 || flag(sheet, "empowered_reviver") > 0) && plugin.skills() != null
                && ready(own, "revive", now, REVIVE_EVERY)) {
            if (plugin.skills().reviveWaitingMinions(player, 2) > 0) {
                tw.exilecore.skill.Fx.spawn(world, Particle.SOUL, at.clone().add(0, 1, 0), 12, 0.6, 0.6, 0.6, 0.02);
            }
        }
        // 熔盾的
        if (flag(sheet, "magma_barrier") > 0) {
            final double max = state.maxLife() * MAGMA_PERCENT / 100.0;
            if (!own.magmaUp && now >= own.magmaBack) {
                own.magmaUp = true;
                own.magma = max;
            }
            own.magma = Math.min(own.magma, max);
            if (own.magmaUp && own.magma > 0) {
                tw.exilecore.skill.Fx.spawn(world, Particle.DRIPPING_LAVA, at.clone().add(0, 1.4, 0), 2, 0.35, 0.4, 0.35, 0);
            }
        } else {
            own.magmaUp = false;
            own.magma = 0;
        }
        // 噬魔的
        if (flag(sheet, "mana_siphon") > 0) {
            if (ticks % 2 == 0) {
                tw.exilecore.skill.Fx.ring(world, at.clone().add(0, 0.15, 0), SIPHON_INNER, Particle.ELECTRIC_SPARK, null);
                tw.exilecore.skill.Fx.ring(world, at.clone().add(0, 0.15, 0), SIPHON_OUTER, Particle.ELECTRIC_SPARK, null);
                for (final LivingEntity enemy : enemies(player, at, SIPHON_OUTER)) {
                    if (enemy.getLocation().distanceSquared(at) >= SIPHON_INNER * SIPHON_INNER) {
                        damage(player, enemy, hit * SIPHON_DAMAGE, DamageType.LIGHTNING, "mana_siphon");
                    }
                }
            }
        }
        // 噬魂的：沒有了就清掉
        if (flag(sheet, "soul_eater") <= 0 && own.souls > 0) {
            own.souls = 0;
            plugin.players().markDirty(player);
        }
        // 狂躁的
        if (flag(sheet, "periodic_enrage") > 0 && target != null && plugin.skills() != null && ready(own, "enrage", now, ENRAGE_EVERY)) {
            final MonsterConfig.MonsterMod enraged = plugin.monsters() == null ? null : plugin.monsters().config().mod("enraged");
            if (enraged != null) {
                plugin.skills().buff(player, "stolen.enrage", "狂躁的（狂暴）", (int) ENRAGE_FOR, modifiers(enraged, "狂躁的：狂暴"));
                world.playSound(at, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 0.5f, 1.4f);
            }
        }
        // 庇護光環的
        if ((flag(sheet, "invulnerability_aura") > 0 || flag(sheet, "empowered_invulnerability_aura") > 0) && target != null
                && plugin.skills() != null && ready(own, "invuln", now, INVULN_EVERY)) {
            for (final Player ally : at.getNearbyPlayers(INVULN_RADIUS)) {
                if (ally != player && !ally.isDead() && plugin.players().get(ally).hasCharacter()) {
                    plugin.skills().buff(ally, "stolen.invuln", "庇護光環", (int) INVULN_FOR,
                            List.of(Modifier.flat(StatKeys.DAMAGE_TAKEN, -100, "庇護光環")));
                }
            }
            tw.exilecore.skill.Fx.ring(world, at.clone().add(0, 0.2, 0), INVULN_RADIUS, Particle.END_ROD, null);
        }
        // 焦土、凍土、雷土的：每 5 秒一圈新星＋地面
        groundNova(player, sheet, own, at, hit, target, now, "burning_ground", DamageType.FIRE, effectiveness(13, 20));
        groundNova(player, sheet, own, at, hit, target, now, "chilled_ground", DamageType.COLD, effectiveness(12, 18));
        groundNova(player, sheet, own, at, hit, target, now, "shocked_ground", DamageType.LIGHTNING, effectiveness(8, 25));
        // 晶爆的：到時間的晶體炸開
        tickCrystals(player, own, hit, now);
        // 爆花的、爆岩的
        final double plants = Math.max(flag(sheet, "volatile_plants"), flag(sheet, "empowering_volatile_plants"));
        if (plants > 0 && target != null && ready(own, "plant", now, PLANT_EVERY) && orbs.size() < 64) {
            final int count = flag(sheet, "empowering_volatile_plants") > 0 ? 2 : 1;
            for (int i = 0; i < count; i++) {
                final Location spot = near(target.getLocation(), 1.5, 3);
                final double power = (plants >= 200 ? effectiveness(15, 22) : effectiveness(12, 18)) * hit;
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> orbs.add(new Orb(player.getUniqueId(), spot.clone().add(0, 1, 0),
                        now() + ORB_LIFE, power)), PLANT_GROW);
                tw.exilecore.skill.Fx.spawn(world, Particle.SPORE_BLOSSOM_AIR, spot.clone().add(0, 0.3, 0), 6, 0.3, 0.2, 0.3, 0);
            }
        }
        if ((flag(sheet, "volatile_crag") > 0 || flag(sheet, "empowering_volatile_crag") > 0) && target != null
                && ready(own, "crag", now, CRAG_EVERY) && crags.size() < 64) {
            final int count = flag(sheet, "empowering_volatile_crag") > 0 ? 2 : 1;
            for (int i = 0; i < count; i++) {
                final Location spot = near(target.getLocation(), 0.5, 2.5);
                crags.add(new Crag(player.getUniqueId(), spot, now + CRAG_ARM, now + CRAG_LIFE));
                tw.exilecore.skill.Fx.spawn(world, Particle.LAVA, spot.clone().add(0, 0.3, 0), 6, 0.3, 0.1, 0.3, 0);
            }
        }
        // 招式（照 boss_skills.yml 的 mod_* 同一招）
        if (target != null) {
            nova(player, sheet, own, at, hit, now, "fire_unleasher", DamageType.FIRE);
            nova(player, sheet, own, at, hit, now, "ice_unleasher", DamageType.COLD);
            nova(player, sheet, own, at, hit, now, "lightning_unleasher", DamageType.LIGHTNING);
            bombs(player, sheet, own, at, hit, now, "fire_bomber", DamageType.FIRE, 200, 3, 1.3, 2.2, 24, 16);
            bombs(player, sheet, own, at, hit, now, "cold_bomber", DamageType.COLD, 200, 3, 1.3, 2.2, 24, 16);
            bombs(player, sheet, own, at, hit, now, "lightning_bomber", DamageType.LIGHTNING, 200, 3, 1.3, 2.2, 24, 16);
            bombs(player, sheet, own, at, hit, now, "stormcaller", DamageType.LIGHTNING, 320, 5, 1.3, 2.5, 20, 20);
            bombs(player, sheet, own, at, hit, now, "bombardier", DamageType.FIRE, 180, 3, 1.2, 2.2, 28, 22);
            if (flag(sheet, "flamewall") > 0 && ready(own, "flamewall", now, 280)) {
                for (int i = 0; i < 4; i++) {
                    patches.add(new Patch(player.getUniqueId(), near(target.getLocation(), 0, 3), 1.4, DamageType.FIRE, hit * 1.0, 0, 0, 0, now + 100));
                }
                world.playSound(target.getLocation(), Sound.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 0.7f, 0.8f);
            }
            if (flag(sheet, "ice_prison") > 0 && target.getLocation().distanceSquared(at) <= 6 * 6 && ready(own, "ice_prison", now, 360)) {
                final Location center = target.getLocation();
                for (int i = 0; i < 10; i++) {
                    final double angle = Math.PI * 2 * i / 10;
                    final Location spot = center.clone().add(Math.cos(angle) * 3, 0, Math.sin(angle) * 3);
                    patches.add(new Patch(player.getUniqueId(), spot, 0.8, DamageType.COLD, hit * 0.4 / 0.6, 0.5, 0, 0, now + 100));
                }
                world.playSound(center, Sound.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 0.8f, 0.6f);
            }
            if (flag(sheet, "elemental_hazards") > 0) {
                hazard(player, own, target, hit, now, "hazard_fire", DamageType.FIRE, 300);
                hazard(player, own, target, hit, now, "hazard_cold", DamageType.COLD, 340);
                hazard(player, own, target, hit, now, "hazard_lightning", DamageType.LIGHTNING, 380);
            }
            if (flag(sheet, "shroud_walker") > 0 && ready(own, "shroud", now, SHROUD_EVERY)) {
                shroud(player, at);
            }
        }
    }

    // ---- 擊中、死亡（CombatListener、事件）----

    /** CombatListener 扣血前：近身的、替身的、熔盾的；被擊中時的幻雷的、晶爆的。after：事件確定沒被取消才做的（真的扣護盾、放幻影）。 */
    public double[] absorb(final Player player, final StatSheet sheet, final LivingEntity attacker, final double es, final double life,
                           final Consumer<Runnable> after) {
        double total = es + life;
        if (total <= 0) {
            return new double[] {es, life};
        }
        final Own own = owns.get(player.getUniqueId());
        if (own == null) {
            return new double[] {es, life};
        }
        // 近身的：只有 5 格內的敵人打得到你
        final double proximal = flag(sheet, "proximal");
        if (proximal > 0 && attacker != null && attacker.getWorld().equals(player.getWorld())
                && attacker.getLocation().distanceSquared(player.getLocation()) > proximal * proximal) {
            return new double[] {0, 0};
        }
        double scale = 1;
        // 替身的：擊中傷害的 50% 先由附近你的召喚物承受
        final double guard = flag(sheet, "minion_shield");
        if (guard > 0 && plugin.skills() != null && plugin.skills().minions() != null) {
            final double absorbed = plugin.skills().minions().absorbForOwnerNear(player.getUniqueId(), total * Math.min(100, guard) / 100.0,
                    player.getLocation(), MINION_SHIELD_RADIUS);
            if (absorbed > 0) {
                scale *= 1 - absorbed / total;
                total -= absorbed;
            }
        }
        // 熔盾的：熔岩護盾先吃
        if (own.magmaUp && own.magma > 0 && total > 0) {
            final double taken = Math.min(own.magma, total);
            scale *= 1 - taken / total;
            total -= taken;
            after.accept(() -> {
                own.magma = Math.max(0, own.magma - taken);
                if (own.magma <= 1e-6) {
                    own.magma = 0;
                    own.magmaUp = false;
                    own.magmaBack = now() + MAGMA_RETURN;
                    magmaBurst(player);
                }
            });
        }
        final long now = now();
        // 幻雷的：被擊中時 25% 機率放閃電幻影
        if (flag(sheet, "mirage") > 0 && now >= own.nextMirage && plugin.random().nextDouble() < MIRAGE_CHANCE) {
            own.nextMirage = now + MIRAGE_COOLDOWN;
            after.accept(() -> mirage(player));
        }
        // 晶爆的：被擊中時身邊長晶體
        if (flag(sheet, "volatile_crystals") > 0 && now >= own.nextCrystal && own.crystals.size() < CRYSTAL_MAX) {
            own.nextCrystal = now + CRYSTAL_EVERY;
            after.accept(() -> {
                own.crystals.add(near(player.getLocation(), 1, 2));
                own.crystalTimes.add(new long[] {now() + CRYSTAL_FUSE});
            });
        }
        return new double[] {es * scale, life * scale};
    }

    /** 噬魂的：附近的敵人死掉時吞魂。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final LivingEntity victim = event.getEntity();
        if (victim instanceof Player || owns.isEmpty() || plugin.monsters() == null || plugin.monsters().data(victim).isEmpty()) {
            return;
        }
        for (final Map.Entry<UUID, Own> entry : owns.entrySet()) {
            final Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.getWorld().equals(victim.getWorld()) || entry.getValue().souls >= SOUL_MAX
                    || player.getLocation().distanceSquared(victim.getLocation()) > SOUL_RADIUS * SOUL_RADIUS
                    || flag(plugin.players().sheet(player), "soul_eater") <= 0) {
                continue;
            }
            entry.getValue().souls++;
            plugin.players().markDirty(player);
            tw.exilecore.skill.Fx.spawn(victim.getWorld(), Particle.SOUL, victim.getLocation().add(0, 1, 0), 4, 0.2, 0.3, 0.2, 0.02);
        }
    }

    /** 屬性表的來源（增益）：噬魂的每個魂 +1% 技能速度、受到的傷害減少 1%。 */
    public List<Modifier> modifiers(final Player player) {
        final Own own = owns.get(player.getUniqueId());
        if (own == null || own.souls <= 0) {
            return List.of();
        }
        final String source = "噬魂的 ×" + own.souls;
        return List.of(Modifier.increased(StatKeys.ATTACK_SPEED, own.souls / 100.0, source),
                Modifier.increased(StatKeys.CAST_SPEED, own.souls / 100.0, source),
                Modifier.flat(StatKeys.DAMAGE_TAKEN, -own.souls, source));
    }

    /** 噬魂的：現在幾個魂（測試、側欄用）。 */
    public int souls(final Player player) {
        final Own own = owns.get(player.getUniqueId());
        return own == null ? 0 : own.souls;
    }

    /** 殉焰、殉霜、殉雷的：你死掉時爆炸。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(final PlayerDeathEvent event) {
        final Player player = event.getEntity();
        final Own own = owns.remove(player.getUniqueId());
        if (own == null) {
            return;
        }
        final StatSheet sheet = plugin.players().sheet(player);
        final double hit = baseHit(plugin.players().get(player));
        final Location at = player.getLocation().add(0, 0.5, 0);
        if (flag(sheet, "fire_death_blast") > 0) {
            blast(player, at, DEATH_BLAST_RADIUS, hit * DEATH_BLAST, DamageType.FIRE, "death_blast_fire");
        }
        if (flag(sheet, "cold_death_blast") > 0) {
            blast(player, at, DEATH_BLAST_RADIUS, hit * DEATH_BLAST, DamageType.COLD, "death_blast_cold");
        }
        if (flag(sheet, "lightning_death_blast") > 0) {
            blast(player, at, DEATH_BLAST_RADIUS, hit * DEATH_BLAST_LIGHTNING, DamageType.LIGHTNING, "death_blast_lightning");
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        owns.remove(event.getPlayer().getUniqueId());
    }

    // ---- 效果 ----

    /** 你這個等級的怪物普通一下的平均（招式的基礎傷害）。 */
    double baseHit(final PlayerState state) {
        if (plugin.monsters() == null) {
            return 0;
        }
        final int level = Math.max(1, state.level());
        final double[] range = plugin.monsters().config().damageAt(level);
        return (range[0] + range[1]) / 2;
    }

    private boolean enemy(final Player player, final LivingEntity entity) {
        return !(entity instanceof Player) && entity.isValid() && !entity.isDead() && plugin.monsters() != null
                && plugin.monsters().data(entity).isPresent()
                && (plugin.training() == null || plugin.training().canTarget(entity, player));
    }

    private List<LivingEntity> enemies(final Player player, final Location at, final double radius) {
        final List<LivingEntity> list = new ArrayList<>();
        if (at.getWorld() == null) {
            return list;
        }
        for (final LivingEntity entity : at.getWorld().getNearbyLivingEntities(at, radius)) {
            if (enemy(player, entity) && entity.getLocation().distanceSquared(at) <= radius * radius) {
                list.add(entity);
            }
        }
        return list;
    }

    private LivingEntity nearestEnemy(final Player player, final Location at, final double radius) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final LivingEntity entity : enemies(player, at, radius)) {
            final double d = entity.getLocation().distanceSquared(at);
            if (d < bestDistance) {
                bestDistance = d;
                best = entity;
            }
        }
        return best;
    }

    private Location near(final Location center, final double min, final double max) {
        final double angle = plugin.random().nextDouble() * Math.PI * 2;
        final double distance = min + plugin.random().nextDouble() * Math.max(0, max - min);
        return center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
    }

    private SkillProfile profile(final String id, final DamageType type, final double amount) {
        return new SkillProfile("stolen." + id, Set.of(Tags.AREA, type.tag()), 0, new DamagePacket().set(type, amount));
    }

    private void damage(final Player player, final LivingEntity target, final double amount, final DamageType type, final String id) {
        if (amount <= 0 || plugin.combat() == null || plugin.combat().skillDamage() == null) {
            return;
        }
        plugin.combat().skillDamage().hit(player, target, profile(id, type, amount), null);
    }

    /** 一圈爆炸：radius 格內的敵人各受 amount 點 type 傷害。 */
    private void blast(final Player player, final Location at, final double radius, final double amount, final DamageType type, final String id) {
        final World world = at.getWorld();
        if (world == null || !player.isOnline() || !player.getWorld().equals(world)) {
            return;
        }
        for (final LivingEntity enemy : enemies(player, at, radius)) {
            damage(player, enemy, amount, type, id);
        }
        tw.exilecore.skill.Fx.spawn(world, Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
        tw.exilecore.skill.Fx.spawn(world, particle(type), at, 20, radius / 2, 0.4, radius / 2, 0.04);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.5f, 1.3f);
    }

    /** 先畫一圈，delay tick 後炸。 */
    private void delayedBlast(final Player player, final Location at, final double radius, final double amount, final DamageType type,
                              final String id, final long delay) {
        tw.exilecore.skill.Fx.ring(at.getWorld(), at.clone().add(0, 0.15, 0), radius, particle(type), null);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> blast(player, at, radius, amount, type, id), Math.max(1, delay));
    }

    private static Particle particle(final DamageType type) {
        return switch (type) {
            case FIRE -> Particle.FLAME;
            case COLD -> Particle.SNOWFLAKE;
            case LIGHTNING -> Particle.ELECTRIC_SPARK;
            case CHAOS -> Particle.WITCH;
            default -> Particle.CRIT;
        };
    }

    private void groundNova(final Player player, final StatSheet sheet, final Own own, final Location at, final double hit,
                            final LivingEntity target, final long now, final String id, final DamageType type, final double effectiveness) {
        if (flag(sheet, id) <= 0 || target == null || !ready(own, id, now, GROUND_EVERY)) {
            return;
        }
        delayedBlast(player, at.clone().add(0, 0.5, 0), GROUND_RADIUS, hit * effectiveness, type, id, 12);
        patches.add(new Patch(player.getUniqueId(), at.clone(), GROUND_RADIUS, type, 0,
                type == DamageType.COLD ? 0.3 : 0, type == DamageType.LIGHTNING ? 0.3 : 0, type == DamageType.FIRE ? hit : 0, now + GROUND_FOR));
    }

    /** 爆焰、爆寒、爆雷的：1.1 秒後 5 格新星（mod_*_nova：1.2 倍、每 9 秒）。 */
    private void nova(final Player player, final StatSheet sheet, final Own own, final Location at, final double hit, final long now,
                      final String id, final DamageType type) {
        if (flag(sheet, id) <= 0 || nearestEnemy(player, at, 6) == null || !ready(own, id, now, 180)) {
            return;
        }
        final Location center = at.clone().add(0, 0.5, 0);
        delayedBlast(player, center, 5, hit * 1.2, type, id, 22);
        if (type == DamageType.COLD && plugin.ailments() != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (final LivingEntity enemy : enemies(player, center, 5)) {
                    plugin.ailments().groundStatusOnMonster(enemy, 0.3, 0, 40);
                }
            }, 22);
        }
    }

    /** 焰爆、冰爆、雷爆的、雷暴的、砲擊的：在 range 格內的敵人身上（附近散開）先畫圈、delay 後炸。 */
    private void bombs(final Player player, final StatSheet sheet, final Own own, final Location at, final double hit, final long now,
                       final String id, final DamageType type, final long every, final int count, final double effectiveness,
                       final double radius, final long delay, final double range) {
        if (flag(sheet, id) <= 0 || !ready(own, id, now, every)) {
            return;
        }
        final List<LivingEntity> targets = enemies(player, at, range);
        if (targets.isEmpty()) {
            own.next.put(id, now);
            return;
        }
        for (int i = 0; i < count; i++) {
            final LivingEntity target = targets.get(i % targets.size());
            final Location spot = near(target.getLocation(), 0, i < targets.size() ? 0.5 : 2.5).add(0, 0.5, 0);
            final long wait = delay + i * 6L;
            delayedBlast(player, spot, radius, hit * effectiveness, type, id, wait);
            if (type == DamageType.COLD && plugin.ailments() != null) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    for (final LivingEntity enemy : enemies(player, spot, radius)) {
                        plugin.ailments().groundStatusOnMonster(enemy, 0.3, 0, 20);
                    }
                }, wait);
            }
        }
    }

    /** 危地的：三種輪流（火 15 秒、冰 17 秒、雷 19 秒），每次三塊 1.6 格、6 秒、每 0.5 秒 0.4 倍。 */
    private void hazard(final Player player, final Own own, final LivingEntity target, final double hit, final long now, final String id,
                        final DamageType type, final long every) {
        if (!ready(own, id, now, every)) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            patches.add(new Patch(player.getUniqueId(), near(target.getLocation(), 0, 4), 1.6, type, hit * 0.8,
                    type == DamageType.COLD ? 0.3 : 0, type == DamageType.LIGHTNING ? 0.2 : 0, 0, now + 120));
        }
    }

    /** 遁影的：瞬移到 10～30 格外看得到的敵人身邊，起點、終點各一團煙霧（致盲附近的敵人）。 */
    private void shroud(final Player player, final Location from) {
        LivingEntity pick = null;
        for (final LivingEntity enemy : enemies(player, from, SHROUD_MAX)) {
            final double d = enemy.getLocation().distanceSquared(from);
            if (d >= SHROUD_MIN * SHROUD_MIN && player.hasLineOfSight(enemy)) {
                pick = enemy;
                break;
            }
        }
        if (pick == null) {
            return;
        }
        smoke(player, from);
        final Location to = pick.getLocation().clone();
        to.setYaw(from.getYaw());
        to.setPitch(from.getPitch());
        player.teleport(to);
        smoke(player, to);
        player.getWorld().playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.2f);
    }

    private void smoke(final Player player, final Location at) {
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 30, 1.2, 0.8, 1.2, 0.02);
        if (plugin.combat() == null || plugin.combat().debuffs() == null) {
            return;
        }
        final long now = now();
        for (final LivingEntity enemy : enemies(player, at, 3)) {
            plugin.combat().debuffs().apply(enemy.getUniqueId(), new Debuffs.Debuff("blind", player.getUniqueId(), now + SHROUD_BLIND, 0,
                    false, null, HitEffects.blindModifiers(0), 0));
        }
    }

    private void magmaBurst(final Player player) {
        final Location at = player.getLocation().add(0, 0.5, 0);
        final double hit = baseHit(plugin.players().get(player));
        tw.exilecore.skill.Fx.spawn(at.getWorld(), Particle.LAVA, at, 24, MAGMA_RADIUS / 2, 0.5, MAGMA_RADIUS / 2, 0.1);
        blast(player, at, MAGMA_RADIUS, hit * MAGMA_DAMAGE, DamageType.FIRE, "magma_burst");
    }

    /** 幻雷的：閃電幻影衝向最近的敵人（每 tick 0.3 格），到了炸 2.5 格。 */
    private void mirage(final Player player) {
        final Location from = player.getLocation().add(0, 1, 0);
        final LivingEntity target = nearestEnemy(player, from, 12);
        if (target == null) {
            return;
        }
        final Location to = target.getLocation().add(0, 0.5, 0);
        final double distance = from.distance(to);
        final long travel = Math.max(4, Math.min(40, Math.round(distance / MIRAGE_SPEED)));
        final double hit = baseHit(plugin.players().get(player));
        for (long t = 0; t < travel; t += 2) {
            final double f = (double) t / travel;
            final Location step = from.clone().add(to.clone().subtract(from).toVector().multiply(f));
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> tw.exilecore.skill.Fx.spawn(step.getWorld(), Particle.ELECTRIC_SPARK, step, 6, 0.2, 0.4, 0.2, 0.02), t);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> blast(player, to, MIRAGE_RADIUS, hit * MIRAGE_DAMAGE, DamageType.LIGHTNING,
                "lightning_mirage"), travel);
    }

    private void tickCrystals(final Player player, final Own own, final double hit, final long now) {
        for (int i = own.crystals.size() - 1; i >= 0; i--) {
            final Location crystal = own.crystals.get(i);
            if (own.crystalTimes.get(i)[0] <= now) {
                own.crystals.remove(i);
                own.crystalTimes.remove(i);
                blast(player, crystal.clone().add(0, 0.5, 0), CRYSTAL_RADIUS, hit * CRYSTAL_DAMAGE, DamageType.CHAOS, "volatile_crystal");
            } else {
                tw.exilecore.skill.Fx.spawn(crystal.getWorld(), Particle.END_ROD, crystal.clone().add(0, 0.7, 0), 1, 0.05, 0.05, 0.05, 0);
            }
        }
    }

    private void capRecovery(final Player player, final Own own, final Location at) {
        final Map<UUID, Double> seen = new HashMap<>();
        for (final LivingEntity enemy : enemies(player, at, CAP_RADIUS)) {
            final org.bukkit.attribute.AttributeInstance max = enemy.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max == null) {
                continue;
            }
            final double cap = max.getValue() * 0.5;
            final Double last = own.lastHealth.get(enemy.getUniqueId());
            double health = enemy.getHealth();
            if (last != null && health > last && health > cap) {
                health = Math.max(last, cap);
                enemy.setHealth(Math.min(max.getValue(), health));
            }
            seen.put(enemy.getUniqueId(), health);
        }
        own.lastHealth.clear();
        own.lastHealth.putAll(seen);
    }

    // ---- 地面、光球、爆岩 ----

    private void tickPatches(final long now) {
        patches.removeIf(patch -> patch.until() <= now);
        for (final Patch patch : patches) {
            final Player owner = plugin.getServer().getPlayer(patch.owner());
            final World world = patch.at().getWorld();
            if (owner == null || world == null || !owner.getWorld().equals(world)) {
                continue;
            }
            final StatSheet sheet = patch.ignite() > 0 ? plugin.players().sheet(owner) : null;
            for (final LivingEntity enemy : enemies(owner, patch.at(), patch.radius())) {
                if (patch.dps() > 0) {
                    damage(owner, enemy, patch.dps() * PERIOD / 20.0, patch.type(), "ground");
                }
                if (plugin.ailments() != null) {
                    if (patch.chill() > 0 || patch.shock() > 0) {
                        plugin.ailments().groundStatusOnMonster(enemy, patch.chill(), patch.shock(), PERIOD + 5);
                    }
                    if (sheet != null) {
                        plugin.ailments().groundIgnite(owner, enemy, patch.ignite(), sheet);
                    }
                }
            }
            if (ticks % 2 == 0) {
                tw.exilecore.skill.Fx.spawn(world, particle(patch.type()), patch.at().clone().add(0, 0.2, 0), 3,
                        patch.radius() / 2, 0.05, patch.radius() / 2, 0.01);
            }
        }
        while (patches.size() > 400) {
            patches.removeFirst();
        }
    }

    private void tickOrbs(final long now) {
        for (final Iterator<Orb> iterator = orbs.iterator(); iterator.hasNext(); ) {
            final Orb orb = iterator.next();
            final Player owner = plugin.getServer().getPlayer(orb.owner);
            if (owner == null || orb.at.getWorld() == null || !owner.getWorld().equals(orb.at.getWorld())) {
                iterator.remove();
                continue;
            }
            final LivingEntity target = nearestEnemy(owner, orb.at, 12);
            if (now >= orb.until || target != null && target.getLocation().add(0, 1, 0).distanceSquared(orb.at) <= 1.0) {
                iterator.remove();
                blast(owner, orb.at, CRYSTAL_RADIUS, orb.power, DamageType.CHAOS, "volatile_plant");
                continue;
            }
            if (target != null) {
                final org.bukkit.util.Vector step = target.getLocation().add(0, 1, 0).toVector().subtract(orb.at.toVector());
                final double length = step.length();
                if (length > 1e-6) {
                    orb.at = orb.at.add(step.multiply(Math.min(ORB_SPEED * PERIOD, length) / length));
                }
            }
            tw.exilecore.skill.Fx.spawn(orb.at.getWorld(), Particle.SPORE_BLOSSOM_AIR, orb.at, 3, 0.1, 0.1, 0.1, 0);
        }
    }

    private void tickCrags(final long now) {
        for (final Iterator<Crag> iterator = crags.iterator(); iterator.hasNext(); ) {
            final Crag crag = iterator.next();
            final Player owner = plugin.getServer().getPlayer(crag.owner());
            if (owner == null || crag.at().getWorld() == null || !owner.getWorld().equals(crag.at().getWorld())) {
                iterator.remove();
                continue;
            }
            final boolean triggered = now >= crag.armed() && !enemies(owner, crag.at(), CRAG_TRIGGER).isEmpty();
            if (triggered || now >= crag.until()) {
                iterator.remove();
                delayedBlast(owner, crag.at().clone().add(0, 0.5, 0), CRYSTAL_RADIUS, baseHit(plugin.players().get(owner)) * CRAG_DAMAGE,
                        DamageType.FIRE, "volatile_crag", 12);
            } else if (ticks % 2 == 0) {
                tw.exilecore.skill.Fx.spawn(crag.at().getWorld(), Particle.LAVA, crag.at().clone().add(0, 0.3, 0), 1, 0.2, 0.05, 0.2, 0);
            }
        }
    }
}
