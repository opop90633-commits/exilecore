package tw.exilecore.skill;

import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01 照 PoE2 0.5（Path of Building PoE2 act_int.lua）：火焰法術補上原作的機制。純資料與純計算（不依賴 Bukkit），
 * 由 ProjectileEngine、SkillZones、SkillExecutor、Signatures 呼叫。
 * <ul>
 *   <li>熾星彈（Fireball，Firebolts statset）：用掉火灌注的那一發爆炸時，往四周一圈射出 12 顆小火彈（同樣的傷害、射程 12 − 6 = 6 公尺、
 *       點燃機率更多 100%、不爆炸、數量不能改）。這次之前做成範圍增加 100%、更多 50% 傷害、點燃機率更多 100%。</li>
 *   <li>焰幕（Flame Wall，Projectile Damage statset）：你與隊友的投射物穿過火牆時附加火焰傷害（逐級 2–3 → 27–41 → 102–153）；
 *       用掉閃電灌注的火牆再附加閃電傷害（1–4 → 3–65 → 13–242）。這次之前沒有。</li>
 *   <li>流焰天幕（Firestorm）：每 0.2 秒一顆火彈落在 3 公尺內隨機一點（落下 0.5 秒），炸 1 公尺；同一個敵人 0.15 秒最多被打一次。
 *       火灌注：火彈頻率增加 100%、傷害增加 100%、風暴半徑 +1.5 公尺；冰灌注：另外落下冰彈（自己的冰冷傷害、1.5 公尺、暴擊 12%）；
 *       電灌注：另外落下閃電（自己的閃電傷害、0.7 公尺、暴擊 9%）。這次之前整片每 0.4 秒打 60%、灌注做成額外傷害。</li>
 *   <li>炎核爆（Flameblast）：以自己為中心爆炸（準心指著自己的烈陽珠時以珠為中心），半徑每層 0.6 公尺。
 *       這次之前在準心指的地方、3.6 → 4.6 格、每層範圍 +12%。</li>
 * </ul>
 */
final class FireSpells {

    static final String FIREBALL = "blazing_star_shell";
    static final String FLAME_WALL = "flame_curtain";
    static final String FIRESTORM = "flowing_flame_canopy";
    static final String FLAMEBLAST = "flame_core_detonation";
    static final String SOLAR_ORB = "scorching_sun_pearl";

    private FireSpells() {
    }

    /** 這些記號的說明（技能面板；不是這裡的回傳 null）。 */
    static String describe(final String stat, final double value) {
        return switch (stat) {
            case FIREBALL_RING -> "爆炸時用掉一層火灌注（有的話），往四周射出一圈 " + RING_BOLTS + " 顆小火彈（同樣傷害、飛 " + (int) RING_RANGE_METRES + " 格、點燃機率更多 100%）";
            case BURN_PER_MINUTE -> "不擊中：範圍裡的敵人每秒受到 " + String.format(java.util.Locale.ROOT, "%.1f", value / 60.0) + " 點基礎火焰持續傷害，並照基礎傷害當作造成來點燃";
            case WALL_INFUSED -> "穿過火牆的投射物另外附加閃電傷害";
            case STORM_FIRE -> "火彈落得快一倍、風暴半徑 +" + STORM_FIRE_RADIUS + " 格";
            case STORM_ICE -> "另外落下冰彈（自己的冰冷傷害、" + ICE_IMPACT + " 格、暴擊率 " + (int) ICE_CRIT + "%）";
            case STORM_LIGHTNING -> "另外落下閃電（自己的閃電傷害、" + LIGHTNING_IMPACT + " 格、暴擊率 " + (int) LIGHTNING_CRIT + "%）";
            case STORM_IMPACT_RADIUS -> "火彈落點半徑 +" + String.format(java.util.Locale.ROOT, "%.2f", value) + " 格";
            case STAGE_RADIUS -> "爆炸半徑每層 0.6 格";
            case SELF_CENTRED -> "以自己為中心（準心指著自己的烈陽珠時在珠上）";
            case FLAG_COMPOUNDING -> "同一次引導的點燃疊加在一起";
            default -> null;
        };
    }

    // ---- 熾星彈 ----

    /** FLAT 1（skills.yml 的 modifiers）：每一發爆炸時用掉一層火灌注（有的話），射出一圈小火彈。 */
    static final String FIREBALL_RING = "fireball.infused_ring";
    /** 原作 base_number_of_projectiles 12（number_of_projectiles_cannot_be_modified）。 */
    static final int RING_BOLTS = 12;
    /** 原作 projectile_maximum_range_override 120 − 60 ＝ 60（6 公尺）。 */
    static final double RING_RANGE_METRES = 6.0;
    /** 原作 active_skill_ignite_chance_+%_final 100。 */
    static final double RING_IGNITE_MORE = 100;

    /** 一圈 count 個水平方向（單位向量 {x, z}），從 phase 弧度開始平均分。純函式。 */
    static List<double[]> ring(final int count, final double phase) {
        final List<double[]> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            final double a = phase + Math.PI * 2 * i / count;
            out.add(new double[] {Math.cos(a), Math.sin(a)});
        }
        return out;
    }

    /** 小火彈的傷害：同一發的傷害（含輔助），拿掉火彈環的記號，點燃機率更多 100%。 */
    static SkillProfile ringProfile(final SkillProfile base) {
        final List<Modifier> local = new ArrayList<>();
        for (final Modifier modifier : base.local()) {
            if (!FIREBALL_RING.equals(modifier.stat())) {
                local.add(modifier);
            }
        }
        local.add(Modifier.more(StatKeys.IGNITE_CHANCE, RING_IGNITE_MORE / 100.0, "小火彈"));
        return new SkillProfile(base.id(), base.tags(), base.effectiveness(), base.spellDamage(), base.spellDamageMax(), base.conversion(), local);
    }

    // ---- 焰幕 ----

    /** FLAT 1（resource.infused）：這道火牆用掉了閃電灌注，穿過的投射物再附加閃電傷害。 */
    static final String WALL_INFUSED = "flame_wall.infused";

    /** 原作 flame_wall_minimum／maximum_added_fire_damage（1–40 級）。 */
    static final int[] WALL_FIRE = {2, 3, 2, 4, 3, 5, 4, 6, 5, 7, 6, 9, 7, 10, 8, 12, 9, 14, 10, 15, 12, 17, 13, 19, 14, 22, 16, 24, 18, 26, 19, 29,
        21, 32, 23, 35, 25, 38, 27, 41, 29, 44, 32, 48, 34, 51, 37, 55, 40, 59, 42, 64, 45, 68, 49, 73, 52, 78, 55, 83, 59, 89, 63, 95, 67, 101,
        71, 107, 76, 114, 80, 121, 85, 128, 91, 136, 96, 144, 102, 153};
    /** 原作 flame_wall_minimum／maximum_added_lightning_damage_to_add_to_projectile（1–40 級，閃電灌注）。 */
    static final int[] WALL_LIGHTNING = {1, 4, 1, 6, 1, 8, 1, 10, 1, 12, 1, 14, 1, 16, 1, 19, 1, 22, 1, 24, 1, 28, 2, 31, 2, 34, 2, 38, 2, 42,
        2, 46, 3, 50, 3, 55, 3, 59, 3, 65, 4, 70, 4, 75, 4, 81, 5, 87, 5, 94, 5, 101, 6, 108, 6, 115, 6, 123, 7, 132, 7, 140, 8, 150, 8, 159,
        9, 169, 9, 180, 10, 191, 11, 203, 11, 215, 12, 228, 13, 242};

    /** 這個等級的火牆給穿過的投射物附加多少 {火最小, 火最大, 電最小, 電最大}（沒用掉閃電灌注的電是 0）。純函式。 */
    static double[] wallAdded(final int level, final boolean infused) {
        final double[] fire = SkillProfiles.tableAt(WALL_FIRE, level);
        final double[] lightning = infused ? SkillProfiles.tableAt(WALL_LIGHTNING, level) : new double[] {0, 0};
        return new double[] {fire[0], fire[1], lightning[0], lightning[1]};
    }

    /** 附加傷害的修飾器（只放在穿過火牆的那一發投射物上）。 */
    static List<Modifier> wallMods(final double[] added) {
        final List<Modifier> out = new ArrayList<>(4);
        if (added[1] > 0) {
            out.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, added[0], Set.of(Tags.FIRE), "焰幕"));
            out.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, added[1], Set.of(Tags.FIRE), "焰幕"));
        }
        if (added[3] > 0) {
            out.add(new Modifier(StatKeys.DAMAGE_ADDED_MIN, ModType.FLAT, added[2], Set.of(Tags.LIGHTNING), "焰幕"));
            out.add(new Modifier(StatKeys.DAMAGE_ADDED_MAX, ModType.FLAT, added[3], Set.of(Tags.LIGHTNING), "焰幕"));
        }
        return out;
    }

    /** 火牆的高度：投射物在牆底下這麼高以內穿過才算（格）。 */
    static final double WALL_HEIGHT = 3.0;

    /**
     * 線段 from → to（這一 tick 投射物飛過的路）有沒有穿過火牆：水平方向離牆上任何一個圓心 radius 以內，
     * 而且高度在牆底下 −0.5 到 {@link #WALL_HEIGHT} 格之間。points 是 {x, y, z}。純函式。
     */
    static boolean crosses(final double[] from, final double[] to, final List<double[]> points, final double radius) {
        final double dx = to[0] - from[0];
        final double dz = to[2] - from[2];
        final double len2 = dx * dx + dz * dz;
        for (final double[] p : points) {
            double t = len2 < 1e-9 ? 0 : ((p[0] - from[0]) * dx + (p[2] - from[2]) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            final double x = from[0] + dx * t - p[0];
            final double z = from[2] + dz * t - p[2];
            final double y = from[1] + (to[1] - from[1]) * t - p[1];
            if (x * x + z * z <= radius * radius && y >= -0.5 && y <= WALL_HEIGHT) {
                return true;
            }
        }
        return false;
    }

    // ---- 流焰天幕 ----

    /** FLAT 1（resource.infused_fire／cold／lightning）：這一陣用掉了哪幾種灌注。 */
    static final String STORM_FIRE = "firestorm.fire_infused";
    static final String STORM_ICE = "firestorm.ice_bolts";
    static final String STORM_LIGHTNING = "firestorm.lightning_bolts";
    /** 品質：火彈落點半徑 +x 公尺（原作 active_skill_base_area_of_effect_radius 每 1% 品質 +0.01 公尺）。 */
    static final String STORM_IMPACT_RADIUS = "firestorm.impact_radius";

    /** 原作 fire_storm_fireball_delay_ms 200：每 0.2 秒一顆（火灌注 firestorm_bolt_frequency_+% 100 → 0.1 秒）。 */
    static final int BOLT_TICKS = 4;
    /** 原作 firestorm_fall_time_ms 500。 */
    static final int FALL_TICKS = 10;
    /** 原作 rain_hit_delay_ms 150：同一個敵人 0.15 秒最多被同一種落彈打一次（3 tick）。 */
    static final int HIT_GAP_TICKS = 3;
    /** 原作 active_skill_base_secondary_area_of_effect_radius 30、firestorm_secondary_radius_+_if_consumed_fire_infusion 15（公尺）。 */
    static final double STORM_RADIUS = 3.0;
    static final double STORM_FIRE_RADIUS = 1.5;
    /** 原作各 statset 的 active_skill_base_area_of_effect_radius：火 10、冰 15、電 7（公尺 × 10）。 */
    static final double FIRE_IMPACT = 1.0;
    static final double ICE_IMPACT = 1.5;
    static final double LIGHTNING_IMPACT = 0.7;
    /** 原作 Ice Bolts、Lightning Bolts 的 critChance。 */
    static final double ICE_CRIT = 12;
    static final double LIGHTNING_CRIT = 9;

    /** 原作 Ice Bolts（1–40 級冰冷傷害）。 */
    static final int[] ICE_BOLTS = {3, 4, 4, 6, 5, 8, 7, 11, 9, 14, 11, 17, 14, 20, 16, 24, 19, 29, 23, 34, 27, 40, 31, 47, 36, 54, 42, 63, 48, 72,
        55, 83, 64, 95, 73, 109, 83, 125, 95, 143, 109, 164, 125, 187, 142, 213, 163, 244, 186, 278, 212, 318, 242, 363, 277, 415, 317, 475,
        362, 544, 415, 622, 475, 713, 545, 818, 626, 938, 719, 1078, 826, 1239, 951, 1426, 1096, 1643, 1264, 1895, 1459, 2189};
    /** 原作 Lightning Bolts（1–40 級閃電傷害）。 */
    static final int[] LIGHTNING_BOLTS = {1, 9, 1, 13, 1, 17, 1, 23, 2, 29, 2, 35, 2, 43, 3, 52, 3, 61, 4, 72, 4, 85, 5, 99, 6, 114, 7, 132, 8, 152,
        9, 175, 11, 201, 12, 231, 14, 264, 16, 302, 18, 345, 21, 395, 24, 451, 27, 515, 31, 588, 35, 671, 40, 767, 46, 877, 53, 1003, 60, 1147,
        69, 1314, 79, 1505, 91, 1726, 104, 1981, 120, 2275, 138, 2616, 158, 3011, 183, 3469, 211, 4001, 243, 4621};

    /** 一種落彈：傷害、落點半徑（格，已含範圍加成）、特效、0 火／1 冰／2 電。 */
    record Bolt(SkillProfile profile, double radius, String visual, int kind) {
    }

    /** 一陣風暴的落彈設定：有哪幾種落彈、幾 tick 一顆、每種各自的「誰什麼時候被打過」。 */
    static final class Storm {
        final List<Bolt> bolts;
        final int periodTicks;
        long nextBolt;
        final List<Map<UUID, Long>> lastHit = new ArrayList<>();

        Storm(final List<Bolt> bolts, final int periodTicks, final long start) {
            this.bolts = List.copyOf(bolts);
            this.periodTicks = Math.max(1, periodTicks);
            this.nextBolt = start;
            for (int i = 0; i < this.bolts.size(); i++) {
                lastHit.add(new java.util.HashMap<>());
            }
        }
    }

    /** 幾 tick 落一顆：0.2 秒，火灌注頻率增加 100%（0.1 秒）。純函式。 */
    static int boltTicks(final boolean fireInfused) {
        return fireInfused ? BOLT_TICKS / 2 : BOLT_TICKS;
    }

    /** 風暴半徑（公尺，還沒乘範圍加成）。純函式。 */
    static double stormRadius(final boolean fireInfused) {
        return STORM_RADIUS + (fireInfused ? STORM_FIRE_RADIUS : 0);
    }

    /** 這個敵人這一 tick 能不能再被這種落彈打（0.15 秒一次）；能的話記下來。純函式（會改 last）。 */
    static boolean gate(final Map<UUID, Long> last, final UUID target, final long now) {
        final Long previous = last.get(target);
        if (previous != null && now - previous < HIT_GAP_TICKS) {
            return false;
        }
        last.put(target, now);
        return true;
    }

    /**
     * 冰彈、閃電的傷害：base（流焰天幕這一下，含輔助、灌注的加成）換成只帶 type 一種、照自己逐級表的基礎傷害，
     * 基礎暴擊率換成 crit（標籤不變：仍是流焰天幕這個火焰技能）。純函式。
     */
    static SkillProfile boltProfile(final SkillProfile base, final DamageType type, final int[] table, final int level, final double crit) {
        final double[] range = SkillProfiles.tableAt(table, level);
        final DamagePacket low = new DamagePacket().set(type, range[0]);
        final DamagePacket high = new DamagePacket().set(type, Math.max(range[0], range[1]));
        final List<Modifier> local = new ArrayList<>();
        for (final Modifier modifier : base.local()) {
            if (!StatKeys.SPELL_BASE_CRIT.equals(modifier.stat())) {
                local.add(modifier);
            }
        }
        local.add(Modifier.flat(StatKeys.SPELL_BASE_CRIT, crit, type == DamageType.COLD ? "冰彈" : "閃電"));
        return new SkillProfile(base.id(), base.tags(), base.effectiveness(), low, high, Map.of(), local);
    }

    // ---- 燃燒（焰幕、焚流）----

    /**
     * level_modifiers（逐級，FLAT）：技能自己的燃燒持續傷害，每分鐘基礎火焰傷害（原作 base_fire_damage_to_deal_per_minute）。
     * 寫了這個的技能不擊中：範圍內的敵人受到燃燒持續傷害，並且照技能的基礎傷害（damage 表 = 原作的 spell_minimum／maximum_base_fire_damage）
     * 「當作造成」來點燃（{@link tw.exilecore.ailment.AilmentService#igniteAsThough}）。
     */
    static final String BURN_PER_MINUTE = "burn.per_minute";
    /** 原作 Incinerate：「Ignites as though dealing X to Y Fire damage, every 0.075 seconds」。 */
    static final double INCINERATE_IGNITE_SECONDS = 0.075;
    static final String INCINERATE = "burning_current";

    /**
     * 燃燒的每秒傷害（純函式）：每分鐘的基礎 ÷ 60，吃這個技能在火焰情境下的傷害增加、更多（跟 PoB2 一樣：法術、範圍、火焰、持續傷害的加成；
     * 不吃擊中才有的），再乘非異常持續傷害的更多（{@link tw.exilecore.stats.SupportKeys#DAMAGE_SKILL_DOT}）與其他倍率 dotMore。
     */
    static double burnDps(final tw.exilecore.stats.StatSheet effective, final SkillProfile profile, final double perMinute, final double dotMore) {
        if (!(perMinute > 0)) {
            return 0;
        }
        final double base = perMinute / 60.0;
        final double scaled = effective == null ? base : effective.apply(StatKeys.DAMAGE, base, profile.contextFor(DamageType.FIRE));
        final double more = effective == null ? 1.0 : effective.more(tw.exilecore.stats.SupportKeys.DAMAGE_SKILL_DOT, profile.tags());
        return Math.max(0, scaled * more * Math.max(0, dotMore));
    }

    /** 這一段時間（秒）裡焚流點燃了幾次（每 0.075 秒一次；純函式）。 */
    static double incinerateIgnites(final double seconds) {
        return Math.max(0, seconds) / INCINERATE_IGNITE_SECONDS;
    }

    // ---- 焚流 ----

    /** FLAG（skills.yml 的 modifiers）：同一次引導造成的點燃疊加（{@link tw.exilecore.ailment.AilmentState#COMPOUND_USE}）。 */
    static final String FLAG_COMPOUNDING = "flag.compounding_ignites";

    // ---- 炎核爆 ----

    /** FLAT x（skills.yml 的 modifiers）：放開時半徑 ×（1 ＋ x ×（層數 − 1））——炎核爆 x = 1，半徑 = 每層 0.6 公尺 × 層數。 */
    static final String STAGE_RADIUS = "charge.radius_per_stage";
    /** FLAT 1：落點在自己身上（準心指著自己的烈陽珠時在珠上）。 */
    static final String SELF_CENTRED = "impact.self_centred";
    /** 準心離烈陽珠的中心這麼近（格）算指著它。 */
    static final double ORB_AIM = 1.6;
    /** 最遠指得到多遠的烈陽珠（格）。 */
    static final double ORB_RANGE = 40;

    /** 半徑的倍率（純函式）：1 ＋ x ×（層數 − 1），至少 1。 */
    static double stageRadiusScale(final double perStage, final int stages) {
        if (!(perStage > 0) || stages <= 1) {
            return 1.0;
        }
        return 1 + perStage * (stages - 1);
    }

    /**
     * 射線（起點 eye、單位方向 dir）離 point 多近：回傳 {沿著射線的距離, 離射線的距離}；在背後的回傳 null。純函式。
     */
    static double[] rayDistance(final double[] eye, final double[] dir, final double[] point) {
        final double px = point[0] - eye[0];
        final double py = point[1] - eye[1];
        final double pz = point[2] - eye[2];
        final double along = px * dir[0] + py * dir[1] + pz * dir[2];
        if (along < 0) {
            return null;
        }
        final double ox = px - dir[0] * along;
        final double oy = py - dir[1] * along;
        final double oz = pz - dir[2] * along;
        return new double[] {along, Math.sqrt(ox * ox + oy * oy + oz * oz)};
    }
}
