package tw.exilecore.skill;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import tw.exilecore.ExileCore;
import tw.exilecore.ailment.AilmentType;
import tw.exilecore.character.PlayerState;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.HitEvent;
import tw.exilecore.stats.ModType;
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

/**
 * 2026-10-28 照 PoE2 0.5（RePoE skills.json 的 stat_sets）：攻擊技能的「第二段」——同一顆寶石裡另一組自己的逐級傷害表與數值。
 * 這次之前這些段不是沒做，就是用 20 級的比例（或平衡等效）頂著。
 * <p>
 * 資料寫在 skills.yml 那個技能的 level_modifiers（逐級，跟著技能等級、含 +技能等級）：
 * <ul>
 *   <li>{@code stage.代號}：那一段的傷害效益（%，PoE2 的 Attack Damage，例如震野踏狂怒 110 → 398 → 1447）。
 *       同一下換段（狂怒、第三擊／終結、強化）時把 {@link SkillProfile#effectiveness()} 換成它，輔助、加成照舊。</li>
 *   <li>{@code limit.代號}：逐級的數量上限（地動的鋸齒地面、驚雷之鐘、荊棘矢的藤苞、萬箭齊落的箭數…）。</li>
 * </ul>
 * 另外打出去的那一段（印記震波、鹿靈、冰牆、毒苞…）是隱藏技能（parent_damage: own，自己的表），用 {@link #ownSub} 照原技能那一下（含輔助）換算。
 * 盾技能（PoE2 NonWeaponAttack＋RequiresShield）用副手盾的逐級基礎傷害（skills.yml 的 damage），另外照盾的護甲／閃避附加傷害（{@link #shieldAdded}）。
 */
public final class StageAttacks implements Listener {

    static final String STAGE = "stage.";
    static final String LIMIT = "limit.";
    /** 這一下是哪一段（給擊中之後的效果看：驚雷刺的終結一擊用掉感電召喚鹿群）。 */
    static final String MARK = "stage.this";

    // ---- 技能代號 ----
    static final String BEAR_SLAM = "bear_slam";
    static final String SHRED = "wolf_shred";
    static final String CROSS = "cross_slash";
    static final String REND = "wyvern_rend";
    static final String LANCE = "glacial_lance";
    static final String PRIMAL = "primal_strikes";
    static final String RAIN = "rain_of_arrows";
    static final String DOMAIN = "toxic_domain";
    static final String SHIELD_RUSH = "shield_rush";
    static final String MAGNETIC = "magnetic_salvo";

    // ---- 隱藏技能 ----
    static final String CROSS_SHOCKWAVE = "proc_cross_shockwave";
    static final String LANCE_BURST = "proc_glacial_lance_burst";
    static final String PRIMAL_SPIRIT = "proc_primal_spirit";
    static final String PUSTULE = "proc_domain_pustule";
    /** 2026-10-28 照 PoE2 Glacial Bolt 的 Wall：凍壁矢留下的冰晶碎掉時用冰牆自己的表炸（其他冰晶照舊 proc_crystal_burst）。 */
    static final Map<String, String> CRYSTAL_BURST = Map.of("glacial_bolt", "proc_glacial_bolt_wall");

    // ---- PoE2 的固定數值 ----
    /** 震野踏：狂怒猛擊要 10 點怒氣（bear_slam_spend_X_rage_for_stronger_slam）、震波 2 → 3 公尺。 */
    static final int ENRAGED_RAGE = 10;
    static final double ENRAGED_AREA = 3.0 / 2.0;
    /** 疾爪：第三擊更多 35% 攻擊速度、終結一擊更少 55%、+0.6 公尺。 */
    static final double SHRED_FAST = 0.35;
    static final double SHRED_FINAL = -0.55;
    static final double SHRED_FINAL_REACH = 0.6;
    /** 2026-10-28 照 PoE2 Shred：終結一擊打兩下。 */
    static final int SHRED_FINAL_HITS = 2;
    /** 驚雷刺終結一擊：更少 35% 攻擊速度、60% 轉閃電（一般 40%）、近戰範圍 +1.5～2.1 公尺（level_modifiers limit.final_reach，0.1 公尺）。 */
    static final double PRIMAL_FINAL_SPEED = -0.35;
    static final double PRIMAL_FINAL_CONVERT = 20;
    /** 驚雷刺的鹿靈：衝 8.6 公尺、寬 1.8 公尺；終結一擊 7 隻、間隔 1.6 公尺、更多 50% 傷害、間隔 150 毫秒。 */
    static final double SPIRIT_LENGTH = 8.6;
    static final double SPIRIT_WIDTH = 1.8;
    static final int STAMPEDE = 7;
    static final double STAMPEDE_SPACING = 1.6;
    static final double STAMPEDE_MORE = 0.5;
    static final long SPIRIT_DELAY = 3;
    /** 翼刃斬：用掉一顆銳星給 10 秒增益（50% 傷害額外獲得閃電）；增益期間更多 150% 範圍、更少 20% 攻擊速度。 */
    static final long REND_TICKS = 200;
    static final double REND_GAIN = 50;
    static final double REND_AREA = 2.5;
    static final double REND_SPEED = -0.2;
    static final String REND_BUFF = "wyvern_rend.charged";
    /** 交錯爪：用掉印記之後 150 毫秒震波。 */
    static final long CROSS_DELAY = 3;
    /** 寒錐投：冰碎片 6 秒（用掉怒星時 1 秒後炸開）、碎片爆炸 2.6 公尺。 */
    static final double LANCE_FRAGMENT_SECONDS = 6;
    static final double LANCE_EMPOWERED_SECONDS = 1;
    static final double LANCE_BURST_SPACING = 2.0;
    /** 萬箭齊落：箭雨 3.6 公尺、每 0.08 秒落一支、用掉怒星更多 150% 箭。 */
    static final double RAIN_RADIUS = 3.6;
    static final double RAIN_TICKS_PER_ARROW = 1.6;
    static final double RAIN_FRENZY_MORE = 1.5;
    /** 磁霆齊發：往前方（散開 70 度）的雷樁矢的箭射，落在箭旁邊的那一發炸成 2.4 公尺、全部轉閃電，箭被毀掉。 */
    static final double MAGNETIC_HALF_ANGLE = 35;
    static final double MAGNETIC_RADIUS = 2.4;
    /** 瘴域：每個敵人最多 1 顆毒苞、15 秒後引爆、存的毒最多更多 300% 傷害、半徑最多 +1.5 公尺（每一點 0.1 公尺）、技能消耗增加 25%。 */
    static final long PUSTULE_TICKS = 300;
    static final double PUSTULE_MORE_CAP = 300;
    static final double PUSTULE_RADIUS_CAP = 1.5;
    static final double DOMAIN_COST = 25;
    static final long BLOOM_LINGER = 15;
    static final String BLOOM_BUFF = "toxic_domain.bloom";
    /** 瘴域逐級數值（level_modifiers）：每分鐘回復生命、毒苞每存幾點毒更多 1% 傷害、每存幾點毒半徑 +0.1 公尺。 */
    static final String DOMAIN_REGEN = "domain.regen_per_minute";
    static final String PUSTULE_MORE_PER = "pustule.more_per";
    static final String PUSTULE_RADIUS_PER = "pustule.radius_per";

    /** 盾技能：副手盾每 15 點護甲／閃避附加幾點傷害（PoE2 off_hand_min/max_added_*_damage_per_15_shield_armour／evasion）。 */
    record ShieldAdd(DamageType type, double armourMin, double armourMax, double evasionMin, double evasionMax) {
    }

    static final Map<String, ShieldAdd> SHIELD_SKILLS = Map.of(
            SHIELD_RUSH, new ShieldAdd(DamageType.PHYSICAL, 3, 5, 0, 0),
            "resonating_shield", new ShieldAdd(DamageType.PHYSICAL, 5, 7, 0, 0),
            "shield_wall", new ShieldAdd(DamageType.PHYSICAL, 5, 7, 0, 0),
            "proc_magma_spray", new ShieldAdd(DamageType.FIRE, 5, 7, 5, 7));
    /** 鐵壁衝撞撞上的那一下（Collision）：每 15 點護甲 6～8（範圍那一下 3～5）。 */
    static final double COLLISION_MIN = 6;
    static final double COLLISION_MAX = 8;
    static final String SHIELD_SOURCE = "副手盾";

    // ================================================================ 純邏輯（單元測試）

    /** 技能這一下的 local 修飾器裡某個 FLAT 屬性的值（level_modifiers 已經照等級算好）；沒寫回傳 NaN。 */
    static double local(final SkillProfile profile, final String stat) {
        if (profile == null) {
            return Double.NaN;
        }
        double sum = 0;
        boolean found = false;
        for (final Modifier modifier : profile.local()) {
            if (modifier.stat().equals(stat) && modifier.type() == ModType.FLAT) {
                sum += modifier.value();
                found = true;
            }
        }
        return found ? sum : Double.NaN;
    }

    /** 這個技能有沒有這一段的表。 */
    static boolean has(final SkillProfile profile, final String stage) {
        return !Double.isNaN(local(profile, STAGE + stage));
    }

    /** 換成另一段：傷害效益換成那一段的表（沒有這一段就原封不動）。 */
    public static SkillProfile stage(final SkillProfile profile, final String stage) {
        final double value = local(profile, STAGE + stage);
        if (Double.isNaN(value) || value <= 0) {
            return profile;
        }
        return withEffectiveness(profile, value / 100.0);
    }

    static SkillProfile withEffectiveness(final SkillProfile profile, final double effectiveness) {
        return new SkillProfile(profile.id(), profile.tags(), effectiveness, profile.spellDamage(), profile.spellDamageMax(),
                profile.conversion(), profile.local());
    }

    /** 同上，另外加幾條只對這一段有效的修飾器（攻擊速度、轉換…）與「這一下是哪一段」的記號。 */
    static SkillProfile stage(final SkillProfile profile, final String stage, final List<Modifier> extra) {
        final SkillProfile swapped = stage(profile, stage);
        final List<Modifier> mods = new ArrayList<>(extra);
        mods.add(Modifier.flat(MARK + "." + stage, 1, "第二段"));
        return swapped.withLocal(mods);
    }

    public static SkillBuild stage(final SkillBuild build, final String stage) {
        return build.withProfile(stage(build.profile(), stage));
    }

    /** 這一下是不是那一段（{@link #stage(SkillProfile, String, List)} 換過的）。 */
    static boolean is(final SkillProfile profile, final String stage) {
        return !Double.isNaN(local(profile, MARK + "." + stage));
    }

    /**
     * 完美時機（Signatures）：有 stage.perfect 就換成那一段的表（PoE2 引霆突 Perfect Dash 280% → 1014% → 3684%），
     * 不然照舊更多 factor 倍。
     */
    public static SkillProfile perfect(final SkillProfile profile, final double factor, final String source) {
        return has(profile, "perfect") ? stage(profile, "perfect") : profile.scaled(factor, source);
    }

    /** 逐級的數量上限（limit.代號）；沒寫回傳 fallback。 */
    public static int limit(final SkillProfile profile, final String name, final int fallback) {
        final double value = local(profile, LIMIT + name);
        return Double.isNaN(value) ? fallback : (int) Math.floor(value + 1e-9);
    }

    /**
     * 連段的第幾下對應哪一段（PoE2 的 statset_index_for_*_animation_combo_index）：最後一擊（finalAt）是 final；
     * 疾爪第三擊起（到最後一擊之前）是 third（Fast Strikes）；其他 null（一般的那一段）。
     */
    static String comboStage(final String skill, final int step, final int finalAt) {
        if (finalAt <= 0 || step <= 0) {
            return null;
        }
        if (step >= finalAt) {
            return "final";
        }
        return SHRED.equals(skill) && step >= 3 ? "third" : null;
    }

    /** 萬箭齊落這次落幾支：逐級的箭數 ＋ 多的投射物；用掉怒星更多 150%（無條件捨去）。 */
    static int rainArrows(final int base, final int extraProjectiles, final boolean frenzy) {
        final int arrows = Math.max(1, base + Math.max(0, extraProjectiles));
        return frenzy ? (int) Math.floor(arrows * (1 + RAIN_FRENZY_MORE) + 1e-9) : arrows;
    }

    /** 第 i 支箭（0 起）比第一支晚幾 tick（每 0.08 秒一支）。 */
    static long rainDelay(final int index) {
        return Math.round(index * RAIN_TICKS_PER_ARROW);
    }

    /**
     * 地動的鋸齒地面（PoE2：已經有上限數量的鋸齒地面、或疊在現有的上面，就不產生——也就沒有餘震）。
     * active：還沒餘震的 {x, z, 半徑}。
     */
    static boolean jaggedAllowed(final List<double[]> active, final double x, final double z, final int limit) {
        if (limit <= 0) {
            return true;
        }
        if (active.size() >= limit) {
            return false;
        }
        for (final double[] patch : active) {
            final double dx = patch[0] - x;
            final double dz = patch[1] - z;
            if (dx * dx + dz * dz < patch[2] * patch[2]) {
                return false;
            }
        }
        return true;
    }

    /** 毒苞存了 stored 點毒：更多幾 %（每 per 點 1%，最多 300%）。 */
    static double pustuleMore(final double stored, final double per) {
        return per <= 0 || stored <= 0 ? 0 : Math.min(PUSTULE_MORE_CAP, Math.floor(stored / per));
    }

    /** 毒苞存了 stored 點毒：基礎半徑多幾公尺（每 per 點 0.1 公尺，最多 1.5 公尺）。 */
    static double pustuleRadius(final double stored, final double per) {
        return per <= 0 || stored <= 0 ? 0 : Math.min(PUSTULE_RADIUS_CAP, Math.floor(stored / per) * 0.1);
    }

    /** 盾技能照副手盾的護甲、閃避附加的傷害（每 15 點）；沒有就空的。 */
    static List<Modifier> shieldAdded(final ShieldAdd spec, final double armour, final double evasion) {
        final double min = Math.max(0, armour) / 15 * spec.armourMin() + Math.max(0, evasion) / 15 * spec.evasionMin();
        final double max = Math.max(0, armour) / 15 * spec.armourMax() + Math.max(0, evasion) / 15 * spec.evasionMax();
        if (max <= 0) {
            return List.of();
        }
        final Set<String> tags = Set.copyOf(spec.type().tags());
        return List.of(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, min, tags, SHIELD_SOURCE),
                new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, max, tags, SHIELD_SOURCE));
    }

    /**
     * 鐵壁衝撞撞上的那一下（Channelling.crash）：換成 Collision 那一段的表（70% → 254% → 921%；這次之前固定是範圍那一下的 2.7 倍），
     * 盾護甲的附加傷害從每 15 點 3～5 換成 6～8。沒有 stage.collision 就照舊乘 fallback。
     */
    public static SkillProfile collision(final SkillProfile area, final double fallback) {
        if (!has(area, "collision")) {
            return area.scaled(fallback, "撞擊");
        }
        final SkillProfile swapped = stage(area, "collision");
        final ShieldAdd spec = SHIELD_SKILLS.get(SHIELD_RUSH);
        final List<Modifier> local = new ArrayList<>();
        for (final Modifier modifier : swapped.local()) {
            if (SHIELD_SOURCE.equals(modifier.source()) && modifier.stat().equals(StatKeys.DAMAGE_ADDED_MIN)) {
                local.add(new Modifier(modifier.stat(), modifier.type(), modifier.value() * COLLISION_MIN / spec.armourMin(), modifier.tags(), SHIELD_SOURCE));
            } else if (SHIELD_SOURCE.equals(modifier.source()) && modifier.stat().equals(StatKeys.DAMAGE_ADDED_MAX)) {
                local.add(new Modifier(modifier.stat(), modifier.type(), modifier.value() * COLLISION_MAX / spec.armourMax(), modifier.tags(), SHIELD_SOURCE));
            } else {
                local.add(modifier);
            }
        }
        return new SkillProfile(swapped.id(), swapped.tags(), swapped.effectiveness(), swapped.spellDamage(), swapped.spellDamageMax(),
                swapped.conversion(), local);
    }

    /** 毒霧引爆（SkillZones.detonateGas）：有 stage.explosion 就換成爆炸自己的表、全部轉火焰、拿掉毒霧的倍率；不然照舊乘 fallback。 */
    public static SkillProfile gasBlast(final SkillProfile zone, final double fallback, final String source) {
        if (!has(zone, "explosion")) {
            return zone.scaled(fallback, source);
        }
        final List<Modifier> local = new ArrayList<>();
        for (final Modifier modifier : zone.local()) {
            final boolean groundScale = modifier.stat().equals(StatKeys.DAMAGE) && modifier.type() == ModType.MORE
                    && ("地面效果".equals(modifier.source()) || "持續傷害".equals(modifier.source()));
            if (!groundScale && !modifier.stat().equals(tw.exilecore.stats.SkillMechanicKeys.FLAG_GROUND_POISON_ONLY)) {
                local.add(modifier);
            }
        }
        final double value = local(zone, STAGE + "explosion");
        return new SkillProfile(zone.id(), zone.tags(), value / 100.0, zone.spellDamage(), zone.spellDamageMax(),
                Map.of(DamageType.FIRE, 1.0), local);
    }

    /** SkillBuild 換掉打擊距離、扇形長度（record 的其他欄位照舊）。 */
    static SkillBuild reshape(final SkillBuild b, final double reachExtra, final double lengthScale) {
        if (reachExtra == 0 && lengthScale == 1) {
            return b;
        }
        return new SkillBuild(b.definition(), b.level(), b.gemLevel(), b.isDefault(), b.supports(), b.profile(),
                b.reach() + reachExtra, b.angle(), b.targets(), b.knockback(), b.projectiles(), b.spread(), b.speed(), b.range(), b.size(),
                b.pierce(), b.chains(), b.explode(), b.wobble(), b.arrow(), b.radius() * lengthScale, b.length() * lengthScale, b.chainRange(),
                b.manaCost(), b.areaScale() * lengthScale, b.bonusLevel(), b.cooldownTicks(), b.durationScale(), b.forks(), b.returning(), b.echo(),
                b.deploy(), b.totems(), b.minions(), b.reserve(), b.minionReserve(), b.auraEffect(), b.generosity(), b.exerts(), b.repeats());
    }

    // ================================================================ 執行期

    private final ExileCore plugin;
    private final SkillService service;

    private record Combo(String skill, long tick, int count) {
    }

    private final Map<UUID, Combo> combos = new HashMap<>();
    /** 地動：還沒餘震的鋸齒地面（玩家 → {x, z, 半徑, 餘震的 tick}）。 */
    private final Map<UUID, List<double[]>> jagged = new HashMap<>();

    private static final class Bloom {
        final UUID owner;
        final Location center;
        final double radius;
        final long until;
        final int level;

        Bloom(final UUID owner, final Location center, final double radius, final long until, final int level) {
            this.owner = owner;
            this.center = center;
            this.radius = radius;
            this.until = until;
            this.level = level;
        }
    }

    private static final class Pustule {
        final UUID owner;
        final LivingEntity carrier;
        final long until;
        final int level;
        double stored;
        /** 2026-10-28 照 PoE2：已經倒數了幾 tick（存的毒讓倒數變快）、帶著牠的敵人死掉的地方（之後留在原地倒數）。 */
        double elapsed;
        Location dropped;

        Pustule(final UUID owner, final LivingEntity carrier, final long until, final int level) {
            this.owner = owner;
            this.carrier = carrier;
            this.until = until;
            this.level = level;
        }
    }

    private final List<Bloom> blooms = new ArrayList<>();
    /** 敵人 → 身上的毒苞（照 PoE2：每個敵人最多 1 顆）。 */
    private final Map<UUID, Pustule> pustules = new HashMap<>();
    private int timer = -1;

    StageAttacks(final ExileCore plugin, final SkillService service) {
        this.plugin = plugin;
        this.service = service;
    }

    private long now() {
        return plugin.getServer().getCurrentTick();
    }

    /**
     * 放技能之前（SkillService，OriginalSkills 之後、算攻擊間隔之前）：換段、消耗怒氣／戰星、萬箭齊落的箭雨、寒錐投的爆炸、瘴域的毒花。
     */
    SkillBuild beforeCast(final Player player, final SkillBuild build, final LivingEntity preferred, final long now) {
        final SkillDefinition definition = build.definition();
        if (definition.hidden()) {
            return build;
        }
        final String id = definition.id();
        final int finalAt = SHRED.equals(id) || PRIMAL.equals(id) ? tw.exilecore.stats.SupportExtraKeys.FINAL_STRIKE_SKILLS.getOrDefault(id, 0) : 0;
        final Combo previous = combos.get(player.getUniqueId());
        final int step = SupportExtras.comboStep(previous == null ? null : previous.skill(), previous == null ? 0 : previous.tick(),
                previous == null ? 0 : previous.count(), id, now, finalAt);
        combos.put(player.getUniqueId(), new Combo(id, now, finalAt > 0 && step >= finalAt ? 0 : step));
        final String combo = comboStage(id, step, finalAt);
        return switch (id) {
            case BEAR_SLAM -> enraged(player, build);
            case SHRED -> "third".equals(combo)
                    ? build.withProfile(stage(build.profile(), "third", List.of(Modifier.more(StatKeys.ATTACK_SPEED, SHRED_FAST, "第三擊"))))
                    : "final".equals(combo)
                    // 2026-10-28 照 PoE2 Shred：終結一擊打兩下（Strikes twice）；這次之前打一下
                    ? reshape(build.withProfile(stage(build.profile(), "final", List.of(Modifier.more(StatKeys.ATTACK_SPEED, SHRED_FINAL, "終結一擊"),
                    Modifier.flat(SkillStages.HITS, SHRED_FINAL_HITS, "終結一擊")))), SHRED_FINAL_REACH, 1)
                    : build;
            case PRIMAL -> "final".equals(combo)
                    ? reshape(build.withProfile(stage(build.profile(), "final", List.of(Modifier.more(StatKeys.ATTACK_SPEED, PRIMAL_FINAL_SPEED, "終結一擊"),
                    Modifier.flat(StatKeys.DAMAGE_CONVERT + "lightning", PRIMAL_FINAL_CONVERT, "終結一擊")))),
                    Math.max(0, limit(build.profile(), "final_reach", 15)) / 10.0, 1)
                    : build;
            case REND -> rend(player, build, now);
            case LANCE -> lance(player, build, preferred);
            case RAIN -> rain(player, build);
            case StageLeftovers.WHIRLWIND_LANCE -> frenzyMark(player, build, StageLeftovers.WHIRLWIND_FRENZY, "旋風第 4 階");
            case MAGNETIC -> magnetic(player, build);
            case DOMAIN -> {
                bloom(player, build, now);
                yield build;
            }
            default -> build;
        };
    }

    /** 震野踏：有 10 點怒氣就用掉，改成狂怒猛擊（自己的表、震波 3 公尺；鋸齒地面沒做）。 */
    private SkillBuild enraged(final Player player, final SkillBuild build) {
        if (service.resources().rage(player) < ENRAGED_RAGE || service.resources().spendRage(player, ENRAGED_RAGE) < ENRAGED_RAGE) {
            return build;
        }
        player.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, player.getLocation().add(0, 1.5, 0), 4, 0.4, 0.3, 0.4, 0);
        return reshape(build.withProfile(stage(build.profile(), "enraged", List.of())), 0, ENRAGED_AREA);
    }

    /** 翼刃斬：沒有增益時用掉一顆銳星給 10 秒增益；增益期間是帶電的那一段（範圍更大、更慢）。 */
    private SkillBuild rend(final Player player, final SkillBuild build, final long now) {
        if (!service.buffs().has(player.getUniqueId(), REND_BUFF) && plugin.combat() != null && plugin.combat().charges() != null
                && plugin.combat().charges().spend(player, "power", build.profile().effective(plugin.players().sheet(player)), build.profile().tags()) > 0) {
            service.buff(player, REND_BUFF, "翼刃帶電", (int) Math.round(REND_TICKS * build.durationScale()),
                    List.of(Modifier.flat(StatKeys.DAMAGE_GAIN + "lightning", REND_GAIN, "翼刃帶電")));
        }
        if (!service.buffs().has(player.getUniqueId(), REND_BUFF)) {
            return build;
        }
        return reshape(build.withProfile(stage(build.profile(), "empowered", List.of(Modifier.more(StatKeys.ATTACK_SPEED, REND_SPEED, "翼刃帶電")))),
                0, Math.sqrt(REND_AREA));
    }

    /**
     * 寒錐投：冰碎片 6 秒；身上有怒星時用掉一顆，碎片 1 秒後沿著冰槍飛過的路炸開（冰牆那一段自己的表，2.6 公尺）。
     */
    private SkillBuild lance(final Player player, final SkillBuild build, final LivingEntity preferred) {
        if (plugin.combat() == null || plugin.combat().charges() == null
                || plugin.combat().charges().spend(player, "frenzy", build.profile().effective(plugin.players().sheet(player)), build.profile().tags()) <= 0) {
            return build;
        }
        final Origin origin = Origin.of(player);
        final Location from = player.getLocation();
        final Location to = preferred != null && preferred.getWorld() == player.getWorld()
                ? preferred.getLocation() : service.targets().aimPoint(origin, build.range());
        final double distance = Math.max(1, from.distance(to));
        final long travel = Math.round(distance / Math.max(0.2, build.speed()));
        final long delay = travel + Math.round(LANCE_EMPOWERED_SECONDS * 20);
        final SkillBuild parent = build;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.getWorld() != from.getWorld()) {
                return;
            }
            final SkillBuild burst = ownSub(player, parent, LANCE_BURST);
            if (burst == null) {
                return;
            }
            final Vector step = to.toVector().subtract(from.toVector()).setY(0);
            final double length = step.length();
            if (length < 1e-6) {
                return;
            }
            step.normalize();
            final java.util.Set<UUID> hit = new java.util.HashSet<>();
            final double radius = burst.radius();
            for (double d = 0; d <= length + 1e-6; d += LANCE_BURST_SPACING) {
                final Location at = from.clone().add(step.clone().multiply(d));
                for (final LivingEntity target : service.targets().around(at, radius, player)) {
                    if (hit.add(target.getUniqueId())) {
                        service.executor().hitOne(player, burst.profile(), target, at, 0);
                    }
                }
                at.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, at.clone().add(0, 0.4, 0), 10, radius * 0.4, 0.2, radius * 0.4, 0.05);
            }
            from.getWorld().playSound(to, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
        }, delay);
        // 照 PoE2：用掉怒星時碎片只留 1 秒（這次之前沒有怒星的那一段）
        return build.withDurationScale(build.durationScale() * LANCE_EMPOWERED_SECONDS / LANCE_FRAGMENT_SECONDS);
    }

    /**
     * 萬箭齊落：有怒星就用掉一顆，更多 150% 箭（PoE2 Rain of Arrows）。
     * 2026-10-28：箭雨本身改在 StageLeftovers 排（SkillExecutor.impact；圖騰、觸發放的一樣落全部的箭、同一隻 0.12 秒一次、同時一場）；
     * 這裡只用掉怒星、做記號。這次之前在這裡排箭（只有自己放的才有），一般的落下當第一支。
     */
    private SkillBuild rain(final Player player, final SkillBuild build) {
        if (limit(build.profile(), "arrows", 0) <= 0) {
            return build;
        }
        return frenzyMark(player, build, StageLeftovers.RAIN_FRENZY, "怒星");
    }

    /** 有怒星就用掉一顆，在這一下做記號（萬箭齊落多射箭、颶風槍的旋風第 4 階）。 */
    private SkillBuild frenzyMark(final Player player, final SkillBuild build, final String mark, final String source) {
        final boolean frenzy = plugin.combat() != null && plugin.combat().charges() != null
                && plugin.combat().charges().spend(player, "frenzy", build.profile().effective(plugin.players().sheet(player)), build.profile().tags()) > 0;
        return frenzy ? build.withProfile(build.profile().withLocal(List.of(Modifier.flat(mark, 1, source)))) : build;
    }

    /**
     * 磁霆齊發：前方射程內、散開角度裡你的雷樁矢插在地上的箭（其他閃電攻擊留下的），每一支吸走一發能量彈：
     * 落在箭旁邊炸開（爆炸那一段自己的表 70% → 286% → 1267%、2.4 公尺、全部轉閃電），箭被毀掉；剩下的照常往前射。
     * （PoE2 也會往其他閃電攻擊留下的弩矢射；我們地上留著的閃電弩矢不是同一套，還沒接）
     */
    private SkillBuild magnetic(final Player player, final SkillBuild build) {
        if (!has(build.profile(), "explosion")) {
            return build;
        }
        final Location eye = player.getEyeLocation();
        final Vector facing = SkillTargets.facing(player).setY(0);
        if (facing.lengthSquared() < 1e-6) {
            return build;
        }
        facing.normalize();
        final double cos = Math.cos(Math.toRadians(MAGNETIC_HALF_ANGLE));
        final SkillProfile swapped = stage(build.profile(), "explosion");
        final SkillProfile blast = new SkillProfile(swapped.id() + ".explosion", swapped.tags(), swapped.effectiveness(), swapped.spellDamage(),
                swapped.spellDamageMax(), Map.of(DamageType.LIGHTNING, 1.0), swapped.local());
        final double radius = MAGNETIC_RADIUS * build.areaScale();
        int used = 0;
        for (final LightningRods.Rod rod : service.zones().rodsAround(player, eye, build.range())) {
            if (used >= build.projectiles()) {
                break;
            }
            final Location at = rod.at.toLocation(player.getWorld());
            final Vector to = at.toVector().subtract(eye.toVector()).setY(0);
            final double distance = to.length();
            if (distance > 1e-6 && to.multiply(1 / distance).dot(facing) < cos) {
                continue;
            }
            rod.spent = true; // 箭被毀掉（排好的爆發照炸，之後消失）
            used++;
            final long travel = Math.max(2, Math.round(distance / Math.max(0.2, build.speed())));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getWorld() == at.getWorld()) {
                    service.executor().burst(player, blast, at, radius, 0, build.definition().visual());
                }
            }, travel);
        }
        return used == 0 ? build : build.withAreaAndProjectiles(build.areaScale(), Math.max(0, build.projectiles() - used));
    }

    /** 地動（SkillExecutor 放餘震之前）：鋸齒地面的上限（limit.jagged），疊在現有的上面或已經滿了就不產生。 */
    boolean allowAftershock(final Player owner, final SkillBuild build, final Location center, final long delayTicks) {
        final int limit = limit(build.profile(), "jagged", 0);
        if (owner == null || limit <= 0) {
            return true;
        }
        final long now = now();
        final List<double[]> mine = jagged.computeIfAbsent(owner.getUniqueId(), key -> new ArrayList<>());
        mine.removeIf(patch -> patch[3] <= now);
        if (!jaggedAllowed(mine, center.getX(), center.getZ(), limit)) {
            return false;
        }
        mine.add(new double[] {center.getX(), center.getZ(), build.radius(), now + delayTicks});
        return true;
    }

    /** 隱藏技能（parent_damage: own）照原技能那一下（含輔助）換算；原技能換過段的先換回本來的表。 */
    SkillBuild ownSub(final Player player, final SkillBuild parent, final String subId) {
        final SkillDefinition sub = service.config().skill(subId);
        if (sub == null) {
            return null;
        }
        final PlayerState state = plugin.players().get(player);
        final StatSheet sheet = plugin.players().sheet(player);
        final SkillBuild raw = SkillBuild.of(service.config(), sub, parent.level(), state.level(), false, List.of(), state.weaponTags(), sheet);
        final SkillBuild base = parent.withProfile(withEffectiveness(parent.profile(), parent.definition().effectiveness().at(parent.level())));
        final double share = service.config().parentShare(subId, parent.definition(), parent.level());
        return share > 0 ? SupportProcs.ownSubBuild(raw, base, share, sheet) : raw;
    }

    /** 面板上這個技能這一下的數值（含輔助）；不在面板上回傳 null。 */
    private SkillBuild current(final Player player, final String skillId) {
        final PlayerState state = plugin.players().get(player);
        final int position = state.skills().find(skillId);
        final SkillDefinition definition = service.config().skill(skillId);
        if (position < 0 || definition == null) {
            return null;
        }
        return service.build(state, plugin.players().sheet(player), definition, state.skills().skill(position));
    }

    // ---- 擊中之後 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(final HitEvent event) {
        if (!(event.attacker() instanceof Player player) || event.skill() == null || !event.result().hit()) {
            return;
        }
        final String id = SupportProcs.baseId(event.skill().id());
        final LivingEntity target = event.defender();
        if (CROSS.equals(id)) {
            crossMark(player, target);
        } else if (PRIMAL.equals(id)) {
            spirits(player, event.skill(), target);
        }
        if (event.skill().isAttack() && event.skill().tags().contains(Tags.PROJECTILE) && !PUSTULE.equals(id)) {
            attachPustule(player, target);
        }
    }

    /** 交錯爪打中被你標記的敵人：用掉印記，150 毫秒後在牠身上震波（proc.mark_consumed → 交錯震波，自己的表）。 */
    private void crossMark(final Player player, final LivingEntity target) {
        if (plugin.combat() == null) {
            return;
        }
        final long now = now();
        final tw.exilecore.combat.Debuffs debuffs = plugin.combat().debuffs();
        String mark = null;
        for (final tw.exilecore.combat.Debuffs.Debuff debuff : debuffs.active(target.getUniqueId(), now)) {
            if (debuffs.isMark(debuff.id()) && player.getUniqueId().equals(debuff.source())) {
                mark = debuff.id();
                break;
            }
        }
        if (mark == null || !debuffs.remove(target.getUniqueId(), mark, player.getUniqueId(), now)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && target.getWorld() == player.getWorld()) {
                service.procEvent(player, CROSS, "mark_consumed", target.getLocation(), target);
            }
        }, CROSS_DELAY);
    }

    /**
     * 驚雷刺打中感電的敵人：前兩擊各召喚一隻鹿靈（從你這邊往牠衝 8.6 公尺、寬 1.8 公尺）；
     * 終結一擊用掉感電，召喚 7 隻並排（間隔 1.6 公尺）的鹿群，每隻更多 50% 傷害。
     */
    private void spirits(final Player player, final SkillProfile skill, final LivingEntity target) {
        if (plugin.ailments() == null || !plugin.ailments().has(target, AilmentType.SHOCK)) {
            return;
        }
        final boolean stampede = is(skill, "final");
        if (stampede) {
            plugin.ailments().consume(target, AilmentType.SHOCK, player);
        }
        final SkillBuild parent = current(player, PRIMAL);
        if (parent == null) {
            return;
        }
        final SkillBuild spirit = ownSub(player, parent, PRIMAL_SPIRIT);
        if (spirit == null) {
            return;
        }
        final SkillProfile profile = stampede ? spirit.profile().scaled(1 + STAMPEDE_MORE, "鹿群") : spirit.profile();
        final Location start = player.getLocation();
        final Vector forward = target.getLocation().toVector().subtract(start.toVector()).setY(0);
        if (forward.lengthSquared() < 1e-6) {
            forward.copy(SkillTargets.facing(player).setY(0));
        }
        forward.normalize();
        final Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        final int count = stampede ? STAMPEDE : 1;
        final double length = SPIRIT_LENGTH * Math.sqrt(Math.max(0.1, spirit.areaScale()));
        final double width = SPIRIT_WIDTH * Math.sqrt(Math.max(0.1, spirit.areaScale())) / 2;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.getWorld() != start.getWorld()) {
                return;
            }
            for (int i = 0; i < count; i++) {
                final double offset = (i - (count - 1) / 2.0) * STAMPEDE_SPACING;
                final Location from = start.clone().add(side.clone().multiply(offset));
                final Location to = from.clone().add(forward.clone().multiply(length));
                for (final LivingEntity enemy : service.targets().alongLine(from, to, width, player)) {
                    service.executor().hitOne(player, profile, enemy, from, 0.3);
                }
                for (double d = 0; d <= length; d += 1.0) {
                    final Location at = from.clone().add(forward.clone().multiply(d)).add(0, 0.8, 0);
                    at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 2, 0.2, 0.2, 0.2, 0);
                }
            }
            start.getWorld().playSound(start, Sound.ENTITY_HORSE_GALLOP, SoundCategory.PLAYERS, 1f, 1.3f);
        }, SPIRIT_DELAY);
    }

    // ---- 瘴域 ----

    /** 放瘴域：在腳下開一片毒花（不打傷害；花裡的你回血、技能消耗增加 25%，你的投射物攻擊在敵人身上掛毒苞）。 */
    private void bloom(final Player player, final SkillBuild build, final long now) {
        final SkillParams.Zone zone = build.params().zone();
        final double radius = zone == null ? 3 : build.radius();
        final long ticks = build.durationTicks(zone == null ? 8 : zone.duration());
        blooms.removeIf(each -> each.owner.equals(player.getUniqueId()));
        blooms.add(new Bloom(player.getUniqueId(), player.getLocation(), radius, now + ticks, build.level()));
        startTimer();
    }

    private Bloom bloomOf(final Player player) {
        final long now = now();
        for (final Bloom bloom : blooms) {
            if (bloom.owner.equals(player.getUniqueId()) && bloom.until > now && bloom.center.getWorld() == player.getWorld()
                    && bloom.center.distanceSquared(player.getLocation()) <= bloom.radius * bloom.radius) {
                return bloom;
            }
        }
        return null;
    }

    private void attachPustule(final Player player, final LivingEntity target) {
        if (blooms.isEmpty() || pustules.containsKey(target.getUniqueId()) || !service.buffs().has(player.getUniqueId(), BLOOM_BUFF)) {
            return;
        }
        final SkillBuild domain = current(player, DOMAIN);
        if (domain == null) {
            return;
        }
        pustules.put(target.getUniqueId(), new Pustule(player.getUniqueId(), target, now() + PUSTULE_TICKS, domain.level()));
        target.getWorld().spawnParticle(Particle.SNEEZE, target.getLocation().add(0, 1, 0), 6, 0.3, 0.3, 0.3, 0.01);
        startTimer();
    }

    /** AilmentService 讓敵人中毒時呼叫：身上有這個玩家的毒苞就存起來（這次中毒的預期總傷害）。 */
    public void poisoned(final LivingEntity attacker, final LivingEntity target, final double expected) {
        if (!(attacker instanceof Player player) || target == null || expected <= 0) {
            return;
        }
        final Pustule pustule = pustules.get(target.getUniqueId());
        if (pustule != null && pustule.owner.equals(player.getUniqueId())) {
            pustule.stored += expected;
        }
    }

    private void burst(final Pustule pustule, final Location at) {
        final Player player = plugin.getServer().getPlayer(pustule.owner);
        if (player == null || !player.isOnline() || at.getWorld() != player.getWorld()) {
            return;
        }
        final SkillBuild domain = current(player, DOMAIN);
        if (domain == null) {
            return;
        }
        SkillBuild burst = ownSub(player, domain, PUSTULE);
        if (burst == null) {
            return;
        }
        final double more = pustuleMore(pustule.stored, service.levelValue(DOMAIN, PUSTULE_MORE_PER, pustule.level));
        if (more > 0) {
            burst = burst.withProfile(burst.profile().scaled(1 + more / 100.0, "存的毒"));
        }
        final double base = burst.definition().params().radius().at(burst.level());
        final double extra = pustuleRadius(pustule.stored, service.levelValue(DOMAIN, PUSTULE_RADIUS_PER, pustule.level));
        if (extra > 0 && base > 0) {
            burst = burst.withAreaAndProjectiles(burst.areaScale() * (base + extra) / base, burst.projectiles());
        }
        service.procCast(Origin.at(player, at.clone().add(0, 0.2, 0), SkillTargets.facing(player)), burst, null);
    }

    private void startTimer() {
        if (timer >= 0) {
            return;
        }
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 10, 10).getTaskId();
    }

    private void tick() {
        final long now = now();
        blooms.removeIf(bloom -> bloom.until <= now);
        for (final Bloom bloom : blooms) {
            final Player player = plugin.getServer().getPlayer(bloom.owner);
            if (player == null || bloomOf(player) != bloom) {
                continue;
            }
            // 照 PoE2 Toxic Bloom：花裡每秒回復生命（逐級，每分鐘 340 → 2500 → 4660）、技能消耗增加 25%
            final double regen = service.levelValue(DOMAIN, DOMAIN_REGEN, bloom.level) / 60.0;
            service.buff(player, BLOOM_BUFF, "毒花", (int) BLOOM_LINGER, List.of(Modifier.flat(StatKeys.LIFE_REGEN, regen, "毒花"),
                    Modifier.increased(StatKeys.SKILL_COST, DOMAIN_COST / 100.0, "毒花")));
        }
        for (final Iterator<Map.Entry<UUID, Pustule>> it = pustules.entrySet().iterator(); it.hasNext(); ) {
            final Pustule pustule = it.next().getValue();
            if (pustule.dropped == null && (!pustule.carrier.isValid() || pustule.carrier.isDead())) {
                pustule.dropped = pustule.carrier.getLocation(); // 被拿掉（不是打死）的照樣留在原地
            }
            // 2026-10-28 照 PoE2（toxic_pustule_duration_expires_1%_faster_per_x_stored_poison_damage_multiplied_by_sixty）：
            // 存的毒 × 60 每 X 點，倒數快 1%；這次之前固定 15 秒
            pustule.elapsed += 10 * StageLeftovers.pustuleRate(pustule.stored, service.levelValue(DOMAIN, StageLeftovers.PUSTULE_FASTER_PER, pustule.level));
            if (pustule.elapsed >= PUSTULE_TICKS || pustule.until + PUSTULE_TICKS <= now) {
                it.remove();
                burst(pustule, pustule.dropped != null ? pustule.dropped : pustule.carrier.getLocation());
            }
        }
        if (blooms.isEmpty() && pustules.isEmpty() && timer >= 0) {
            plugin.getServer().getScheduler().cancelTask(timer);
            timer = -1;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final EntityDeathEvent event) {
        final Pustule pustule = pustules.get(event.getEntity().getUniqueId());
        if (pustule != null && pustule.dropped == null) {
            // 2026-10-28 照 PoE2 Toxic Domain：毒苞只在倒數完（或存夠毒）時引爆，敵人死掉時留在原地繼續倒數；這次之前當場引爆
            pustule.dropped = event.getEntity().getLocation();
        }
    }

    // ---- 盾技能 ----

    /** 盾技能（SkillService.build、SupportProcs.castNow）：照副手盾的護甲、閃避加上附加傷害（每 15 點）。 */
    static SkillBuild withShield(final ExileCore plugin, final PlayerState state, final SkillBuild build) {
        final ShieldAdd spec = build == null ? null : SHIELD_SKILLS.get(build.definition().id());
        if (spec == null || state == null || plugin.items() == null) {
            return build;
        }
        final org.bukkit.inventory.ItemStack off = state.jewellery(tw.exilecore.character.Gear.OFF);
        if (off == null || off.isEmpty()) {
            return build;
        }
        final tw.exilecore.item.ItemData data = plugin.items().read(off).orElse(null);
        final tw.exilecore.item.ItemBase base = data == null ? null : plugin.items().repository().base(data.base());
        if (base == null || !base.isShield()) {
            return build;
        }
        final tw.exilecore.item.ItemStats.Snapshot stats = tw.exilecore.item.ItemStats.snapshot(plugin.items().repository(), base, data);
        final List<Modifier> added = shieldAdded(spec, stats.armour(), stats.evasion());
        return added.isEmpty() ? build : build.withProfile(build.profile().withLocal(added));
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        final UUID id = event.getPlayer().getUniqueId();
        combos.remove(id);
        jagged.remove(id);
        blooms.removeIf(bloom -> bloom.owner.equals(id));
        pustules.values().removeIf(pustule -> pustule.owner.equals(id));
    }
}
