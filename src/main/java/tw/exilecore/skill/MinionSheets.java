package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 召喚物的屬性表（PoE 的規矩：召喚物有自己的數值，主人只有「召喚物…」的修飾器會給牠們）：
 * <ul>
 *   <li>2026-10-26 照 PoE2 的召喚物（summon.ally）：等級 = 寶石等級 × 2，用 PoE2 的召喚物表換算（{@link #allyBase}）；</li>
 *   <li>舊的（幻身這類）底是「同等級普通怪物」：等級 = 技能等級對應的需求角色等級（20 級以上每級再加 4），
 *       生命、命中、閃避、護甲、傷害照怪物的曲線，乘 {@link SummonSpec} 的倍率；</li>
 *   <li>主人屬性表裡帶 minion 標籤的修飾器（天賦「增加 x% 召喚物傷害」、裝備、昇華）全部複製過來；</li>
 *   <li>掛在技能上的輔助寶石（兇猛召喚、蠻力…）是技能描述的 local，結算時疊上去。</li>
 * </ul>
 * 查詢召喚物的數值一律帶 {@link #CONTEXT}（含 minion），主人自己的「增加 x% 傷害」沒有 minion 標籤就不會算進來。
 * 純函式，不依賴 Bukkit。
 */
public final class MinionSheets {

    /** 怪物曲線在某個等級的底（遊戲裡由 monsters.yml 給；測試可以直接造）。 */
    public record Base(double life, double accuracy, double evasion, double armour, double damageMin, double damageMax,
                       double critChance) {
    }

    /** 召喚物查屬性時的情境。 */
    public static final Set<String> CONTEXT = Set.of(Tags.MINION);
    /** 召喚物的命中比同等級怪物高一些（怪物的命中是對玩家調的；召喚物打不中會很煩）。 */
    public static final double ACCURACY_BONUS = 1.25;
    /** 20 級以上（裝備的 +技能等級）每多一級，召喚物多幾個怪物等級。 */
    public static final int EXTRA_LEVELS_PER_GEM_LEVEL = 4;

    private MinionSheets() {
    }

    // ---- 2026-10-26 照 PoE2 的召喚物（summon.ally: true） ----
    // PoE2 的召喚物有自己的等級（寶石等級 × 2）與一張「召喚物生命／傷害」表（ally table，Path of Building 的 Misc.lua）。
    // 我們的怪物曲線跟 PoE2 不一樣，所以換算時保留「比例」：
    //   召喚物生命 ÷ 同等級怪物每一下的傷害（挨幾下會死）、召喚物每一下的傷害 ÷ 同等級怪物的生命（幾下打死一隻），
    // 跟 PoE2 在同一個區域等級（寶石需求等級；2026-10-27 起表逐級到 100 級，這次之前到 83 級）時一樣。
    // 2026-10-27 照 PoE2（Path of Building Misc.lua，1–100 級逐級）：這次之前只有 11 個錨點（1、10、…、80、83 級，中間等比內插），
    // 超過 83 級夾在 83 級。20 級寶石需求 90 級，區域等級算 90，我們的怪物（monsters.yml 照 PoE2 寫到 100 級）拿去跟 PoE2 的 83 級比，
    // 召喚物、圖騰的生命多了 22%（444 ÷ 364）、傷害多了 23%（41748 ÷ 33945）；70–80 級之間的錨點內插也跟原作差到 ±20%。
    // 現在逐級對照，我們的怪物表跟 PoE2 一樣時換算倍率就是 1，召喚物生命、傷害跟原作的絕對值一樣。
    private static final double[] POE2_ALLY_LIFE = {
            51, 83, 116, 150, 186, 223, 261, 300, 341, 382, 426, 471, 517, 565, 614, 665, 718, 772, 828, 886,
            945, 1007, 1070, 1135, 1203, 1272, 1344, 1417, 1493, 1571, 1652, 1734, 1820, 1907, 1998, 2091, 2186, 2285, 2386, 2490,
            2598, 2708, 2821, 2938, 3058, 3181, 3307, 3438, 3571, 3709, 3850, 3995, 4144, 4298, 4455, 4617, 4783, 4953, 5128, 5308,
            5493, 5682, 5877, 6077, 6282, 6492, 6708, 6930, 7157, 7391, 7630, 7876, 8128, 8387, 8652, 8924, 9203, 9489, 9783, 10084,
            10393, 10710, 11034, 11367, 11708, 12058, 12417, 12785, 13161, 13548, 13944, 14350, 14766, 15192, 15629, 16076, 16535, 17005, 17486, 17980
    };
    private static final double[] POE2_ALLY_DAMAGE = {
            3.11, 4.42, 5.82, 7.31, 8.92, 10.63, 12.46, 14.42, 16.51, 18.73, 21.1, 23.62, 26.31, 29.16, 32.19, 35.42, 38.83, 42.46, 46.31, 50.39,
            54.71, 59.29, 64.14, 69.27, 74.69, 80.43, 86.5, 92.91, 99.69, 106.84, 114.4, 122.37, 130.79, 139.67, 149.04, 158.91, 169.32, 180.29, 191.86, 204.04,
            216.86, 230.37, 244.6, 259.57, 275.32, 291.9, 309.34, 327.69, 346.98, 367.27, 388.59, 411.01, 434.57, 459.32, 485.33, 512.66, 541.35, 571.49, 603.14, 636.37,
            671.26, 707.87, 746.3, 786.63, 828.94, 873.34, 919.91, 968.76, 1019.99, 1073.72, 1130.06, 1189.13, 1251.06, 1315.98, 1384.03, 1455.34, 1530.08, 1608.4, 1690.46, 1776.43,
            1866.5, 1960.84, 2059.66, 2163.16, 2271.56, 2385.06, 2503.91, 2628.36, 2758.64, 2895.03, 3037.8, 3187.24, 3343.66, 3507.35, 3678.66, 3857.93, 4045.51, 4241.77, 4447.11, 4661.93
    };
    private static final double[] POE2_MONSTER_LIFE = {
            15, 20, 24, 28, 33, 38, 45, 50, 58, 67, 78, 89, 103, 118, 134, 158, 178, 200, 224, 249,
            276, 305, 335, 366, 400, 434, 472, 510, 551, 593, 637, 683, 731, 790, 853, 921, 995, 1074, 1160, 1253,
            1353, 1462, 1578, 1705, 1841, 1967, 2101, 2244, 2395, 2556, 2726, 2909, 3102, 3307, 3525, 3756, 4002, 4264, 4540, 4834,
            5147, 5478, 5829, 6203, 6555, 7079, 7646, 8257, 8918, 11148, 11984, 12882, 13849, 14887, 18609, 20005, 21505, 23118, 24852, 31065,
            31997, 32956, 33945, 34963, 36012, 37093, 38206, 39352, 40532, 41748, 43001, 44291, 45619, 46988, 48398, 49850, 51345, 52885, 54472, 56106
    };
    private static final double[] POE2_MONSTER_DAMAGE = {
            9.16, 10.26, 11.39, 12.57, 13.78, 15.03, 16.32, 17.65, 19.02, 20.44, 21.9, 23.41, 24.97, 26.57, 28.23, 29.93, 31.69, 33.5, 35.37, 37.29,
            39.27, 41.31, 43.41, 45.57, 47.8, 50.09, 52.45, 54.88, 57.37, 59.94, 62.59, 65.31, 68.1, 70.98, 73.94, 76.98, 80.11, 83.32, 86.63, 90.02,
            93.51, 97.1, 100.79, 104.57, 108.46, 112.46, 116.57, 120.78, 125.12, 129.56, 134.13, 138.82, 143.64, 148.58, 153.66, 158.87, 164.21, 169.7, 175.34, 181.12,
            187.05, 193.14, 199.38, 205.79, 212.36, 219.11, 226.03, 233.12, 240.4, 247.86, 255.52, 263.37, 271.42, 279.68, 288.14, 296.82, 305.72, 314.84, 324.19, 333.78,
            343.6, 353.67, 364, 374.58, 385.42, 396.53, 407.92, 419.58, 431.54, 443.79, 456.34, 469.2, 482.38, 495.87, 509.7, 523.86, 538.37, 553.23, 568.46, 584.05
    };
    private static final double[] POE2_ARMOUR = {
            3, 6, 8, 10, 13, 16, 19, 22, 26, 30, 34, 39, 43, 49, 54, 60, 67, 73, 81, 89,
            97, 106, 116, 126, 137, 149, 161, 174, 189, 204, 220, 237, 255, 274, 295, 317, 340, 364, 391, 418,
            448, 479, 512, 547, 585, 624, 666, 711, 758, 808, 861, 917, 976, 1039, 1105, 1176, 1250, 1329, 1412, 1500,
            1594, 1692, 1796, 1906, 2023, 2146, 2276, 2413, 2558, 2712, 2874, 3044, 3225, 3416, 3617, 3829, 4053, 4290, 4540, 4803,
            5081, 5375, 5684, 6011, 6355, 6718, 7101, 7505, 7930, 8379, 8852, 9351, 9877, 10431, 11015, 11630, 12279, 12962, 13682, 14441
    };
    private static final double[] POE2_EVASION = {
            24, 30, 36, 43, 49, 56, 63, 70, 77, 84, 91, 98, 105, 113, 120, 128, 136, 144, 152, 160,
            168, 176, 185, 193, 202, 211, 220, 229, 238, 247, 257, 266, 276, 286, 296, 306, 316, 326, 337, 347,
            358, 369, 380, 391, 403, 414, 426, 438, 449, 462, 474, 486, 499, 511, 524, 537, 551, 564, 578, 591,
            605, 619, 634, 648, 663, 677, 692, 708, 723, 738, 754, 770, 786, 803, 819, 836, 853, 870, 887, 905,
            923, 941, 959, 977, 996, 1015, 1034, 1053, 1073, 1093, 1113, 1133, 1154, 1174, 1195, 1217, 1238, 1260, 1282, 1304
    };
    private static final double[] POE2_ACCURACY = {
            32, 35, 39, 43, 48, 52, 57, 62, 67, 72, 78, 84, 90, 96, 103, 110, 117, 124, 132, 140,
            149, 158, 167, 176, 186, 196, 207, 218, 230, 242, 254, 267, 281, 295, 309, 325, 340, 356, 373, 391,
            409, 428, 447, 468, 489, 511, 533, 557, 581, 606, 632, 659, 688, 717, 747, 778, 810, 844, 878, 914,
            951, 990, 1030, 1071, 1114, 1158, 1204, 1251, 1300, 1351, 1403, 1457, 1514, 1572, 1632, 1694, 1758, 1824, 1893, 1964,
            2038, 2114, 2192, 2273, 2357, 2444, 2534, 2626, 2722, 2821, 2923, 3029, 3138, 3251, 3368, 3488, 3613, 3741, 3874, 4011
    };
    /** PoE2 召喚物的基礎暴擊率 5%（所有召喚物都一樣）。 */
    public static final double ALLY_CRIT = 5;

    /** 照 PoE2 寶石逐級表才有的最高技能等級（40 級；召喚物等級 80）。 */
    public static final int POE2_MAX_GEM_LEVEL = 40;

    /**
     * PoE2：召喚物等級 = 寶石等級 × 2（20 級寶石 = 40 級、40 級寶石 = 80 級召喚物；poe2db 的 Minion Level 欄）。
     * 2026-10-28 照 PoE2：超過 20 級也照 × 2 往上（這次之前停在 40 級，超過 20 級改乘 {@link #extraScale}）；40 級以上才照 extraScale。
     */
    public static int allyLevel(final int skillLevel) {
        return 2 * Math.max(1, Math.min(POE2_MAX_GEM_LEVEL, skillLevel));
    }

    /** PoE2 的表（1–100 級逐級；超出兩端夾住，非整數等級照相鄰兩級等比內插）。 */
    static double poe2(final double[] table, final double level) {
        final double x = Math.max(1, Math.min(table.length, level));
        final int low = (int) Math.floor(x);
        if (low >= table.length) {
            return table[table.length - 1];
        }
        final double t = x - low;
        return t <= 0 ? table[low - 1] : table[low - 1] * Math.pow(table[low] / table[low - 1], t);
    }

    /**
     * PoE2 召喚物的底（還沒乘 summon 的倍率）：
     * 生命 = PoE2 召喚物生命(召喚物等級) × 我們的怪物每下傷害(區域等級) ÷ PoE2 怪物每下傷害(區域等級)，
     * 傷害 = PoE2 召喚物傷害(召喚物等級) × 我們的怪物生命(區域等級) ÷ PoE2 怪物生命(區域等級)，
     * 護甲照生命的比例、閃避照命中的比例；區域等級 = {@link #monsterLevel}（寶石需求等級，角色等級往上補）。
     *
     * @param ourLife     我們的普通怪生命（區域等級）
     * @param ourDamage   我們的普通怪每一下 {最小, 最大}（區域等級）
     * @param ourAccuracy 我們的普通怪命中（區域等級）
     */
    public static Base allyBase(final SummonSpec spec, final int skillLevel, final int areaLevel, final double ourLife,
                                final double[] ourDamage, final double ourAccuracy, final double scale) {
        final double[] factors = poe2Factors(areaLevel, ourLife, ourDamage, scale);
        final int minion = allyLevel(skillLevel);
        final double area = Math.min(POE2_MONSTER_LIFE.length, Math.max(1, areaLevel));
        final double life = poe2(POE2_ALLY_LIFE, minion) * factors[1];
        final double hit = poe2(POE2_ALLY_DAMAGE, minion) * factors[0];
        final double spread = spec.spread();
        return new Base(life, ourAccuracy * 4, poe2(POE2_EVASION, minion) * ourAccuracy / poe2(POE2_ACCURACY, area),
                poe2(POE2_ARMOUR, minion) * factors[1] / Math.max(1e-9, scale), hit * (1 - spread), hit * (1 + spread), ALLY_CRIT);
    }

    /**
     * 2026-10-26：PoE2 的傷害、生命換成我們的單位要乘多少（{傷害, 生命}，已經含超過 20 級的 {@link #extraScale}）：
     * 傷害照「幾下打死同等級怪物」、生命照「挨同等級怪物幾下」換（跟 {@link #allyBase} 同一套）。
     * 召喚物法術的傷害表（電弧、死亡風暴、冰箭…）、祭司的回復、爆裂殉身的生命門檻都用這個換。
     */
    public static double[] poe2Factors(final int areaLevel, final double ourLife, final double[] ourDamage, final double scale) {
        final double area = Math.min(POE2_MONSTER_LIFE.length, Math.max(1, areaLevel));
        final double toOurHits = (ourDamage[0] + ourDamage[1]) / 2 / poe2(POE2_MONSTER_DAMAGE, area);
        final double toOurLife = ourLife / poe2(POE2_MONSTER_LIFE, area);
        return new double[] {toOurLife * scale, toOurHits * scale};
    }

    /** 同上，直接給技能等級與主人等級（區域等級照 {@link #monsterLevel}）。 */
    public static double[] poe2Factors(final SkillConfig config, final int skillLevel, final int ownerLevel,
                                       final java.util.function.IntToDoubleFunction ourLife,
                                       final java.util.function.IntFunction<double[]> ourDamage) {
        final int level = monsterLevel(config, skillLevel, ownerLevel);
        return poe2Factors(level, ourLife.applyAsDouble(level), ourDamage.apply(level), extraScale(config, skillLevel));
    }

    /** PoE2 召喚物表在這個召喚物等級的每一下傷害（還沒換算）：鴉群這類沒有召喚物實體的用。 */
    public static double poe2AllyDamage(final int minionLevel) {
        return poe2(POE2_ALLY_DAMAGE, minionLevel);
    }

    /** 2026-10-26：PoE2 召喚物表的生命、護甲（還沒換算；圖騰用）。 */
    public static double poe2AllyLife(final int minionLevel) {
        return poe2(POE2_ALLY_LIFE, minionLevel);
    }

    public static double poe2AllyArmour(final int minionLevel) {
        return poe2(POE2_ARMOUR, minionLevel);
    }

    /** 給顯示用：召喚物的等級（照 PoE2 的是寶石等級 × 2，舊的是當幾級的怪物）。 */
    public static int displayLevel(final SummonSpec spec, final int skillLevel, final int monsterLevel) {
        return spec != null && spec.ally() ? allyLevel(skillLevel) : monsterLevel;
    }

    /**
     * 一隻召喚物的底（三個地方共用：召喚、技能說明、/stats 的召喚物每秒傷害）。
     *
     * @param ourLife   等級 → 我們的普通怪生命
     * @param ourDamage 等級 → 我們的普通怪每一下 {最小, 最大}
     */
    public static Base base(final SkillConfig config, final SummonSpec spec, final int skillLevel, final int ownerLevel,
                            final java.util.function.IntToDoubleFunction ourLife,
                            final java.util.function.IntFunction<double[]> ourDamage,
                            final java.util.function.IntToDoubleFunction ourAccuracy,
                            final java.util.function.IntToDoubleFunction ourEvasion,
                            final java.util.function.IntToDoubleFunction ourArmour, final double ourCrit) {
        final int level = monsterLevel(config, skillLevel, ownerLevel);
        final double scale = extraScale(config, skillLevel);
        final double[] damage = ourDamage.apply(level);
        if (spec != null && spec.ally()) {
            return allyBase(spec, skillLevel, level, ourLife.applyAsDouble(level), damage, ourAccuracy.applyAsDouble(level), scale);
        }
        return new Base(ourLife.applyAsDouble(level) * scale, ourAccuracy.applyAsDouble(level), ourEvasion.applyAsDouble(level),
                ourArmour.applyAsDouble(level), damage[0] * scale, damage[1] * scale, ourCrit);
    }

    /** 技能等級 → 召喚物當幾級的怪物算：寶石的需求角色等級；超過 20 級每級再加 4。 */
    public static int monsterLevel(final SkillConfig config, final int skillLevel) {
        return monsterLevel(config, skillLevel, 0);
    }

    /**
     * 同上，再照角色等級往上補（2026-10）：寶石的需求等級是一階一階的（18 級要 50、19 級要 64），
     * 以前角色從 50 練到 63 級，召喚物一直停在 50 級怪的強度。現在召喚物至少跟角色同級，
     * 但不超過「下一級寶石的需求等級 − 1」（20 級的寶石沒有上限）——換寶石、升寶石還是有意義。
     * characterLevel ≤ 0 = 不補（舊的算法）。
     */
    public static int monsterLevel(final SkillConfig config, final int skillLevel, final int characterLevel) {
        final int capped = Math.max(1, Math.min(SkillLoadout.MAX_LEVEL, skillLevel));
        final int base = config.requiredLevel(capped);
        if (characterLevel <= 0) {
            return base + Math.max(0, skillLevel - SkillLoadout.MAX_LEVEL) * EXTRA_LEVELS_PER_GEM_LEVEL;
        }
        // 2026-10：超過 20 級的部分不再加怪物等級（以前加在 80 上再跟角色等級取大的，角色 94 級時 +1～+3 完全沒效果；
        // 怪物表到 100 級就封頂），改成 {@link #extraScale} 直接乘生命與傷害
        final int ceiling = capped < SkillLoadout.MAX_LEVEL ? config.requiredLevel(capped + 1) - 1 : Integer.MAX_VALUE;
        return Math.max(base, Math.min(characterLevel, ceiling));
    }

    /**
     * 超過 40 級（2026-10-28 之前是 20 級）每多一級，召喚物的生命與傷害再乘 1 + minion_extra_level_damage
     * （2026-10：預設 5%，比法術的 10% 低——召喚流的「+召喚物技能等級」很好疊，+8 級時 ×1.48，以前 ×2.14）。
     */
    public static double extraScale(final SkillConfig config, final int skillLevel) {
        // 2026-10-28：21～40 級改成召喚物等級照 PoE2 表往上（allyLevel），這個倍率只給超過 40 級的；這次之前超過 20 級就乘
        return skillLevel > POE2_MAX_GEM_LEVEL ? Math.pow(config.minionExtraLevelGrowth(), skillLevel - POE2_MAX_GEM_LEVEL) : 1.0;
    }

    /** 主人屬性表裡會給召喚物的修飾器：帶 minion 標籤的。 */
    public static List<Modifier> ownerModifiers(final StatSheet owner) {
        final List<Modifier> list = new ArrayList<>();
        for (final Modifier modifier : owner.allModifiers()) {
            if (modifier.tags().contains(Tags.MINION)) {
                list.add(modifier);
            }
        }
        return list;
    }

    /**
     * 2026-10-26 照 PoE2：只給某些召喚物的修飾器——「夥伴…」（[minion, companion]，技能帶 companion 標籤的才給）、
     * 「存在範圍內的友軍…」（[minion, in_presence]，召喚物在你的存在範圍內才給）。
     */
    public static final Set<String> CONDITIONAL = Set.of(tw.exilecore.stats.TreeMechanicKeys.COMPANION,
            tw.exilecore.stats.TreeMechanicKeys.IN_PRESENCE, tw.exilecore.passive.ConquestEffects.UNDEAD); // 2026-10-26：不死召喚物（忠誠獻祭）

    /** 這隻召喚物成立的條件標籤：技能是夥伴就有 companion；inPresence = 牠在主人的存在範圍內。 */
    public static Set<String> conditions(final SkillDefinition definition, final boolean inPresence) {
        final Set<String> active = new HashSet<>();
        if (definition != null && definition.tags().contains(tw.exilecore.stats.TreeMechanicKeys.COMPANION)) {
            active.add(tw.exilecore.stats.TreeMechanicKeys.COMPANION);
        }
        if (inPresence) {
            active.add(tw.exilecore.stats.TreeMechanicKeys.IN_PRESENCE);
        }
        if (definition != null && definition.summon() != null && tw.exilecore.passive.ConquestEffects.undead(definition.summon().kind())) {
            active.add(tw.exilecore.passive.ConquestEffects.UNDEAD);
        }
        return active;
    }

    /**
     * 照條件挑主人給召喚物的修飾器：帶條件標籤的，條件都成立才給（拿掉條件標籤，召喚物查屬性時不用帶），有一個不成立就整條不給。
     * 沒有條件標籤的照原樣。
     */
    public static List<Modifier> forMinion(final List<Modifier> modifiers, final Set<String> active) {
        final List<Modifier> list = new ArrayList<>(modifiers.size());
        for (final Modifier modifier : modifiers) {
            if (java.util.Collections.disjoint(modifier.tags(), CONDITIONAL)) {
                list.add(modifier);
                continue;
            }
            final Set<String> rest = new HashSet<>(modifier.tags());
            boolean ok = true;
            for (final String tag : CONDITIONAL) {
                if (rest.remove(tag) && !active.contains(tag)) {
                    ok = false;
                }
            }
            if (ok) {
                list.add(modifier.withTags(rest));
            }
        }
        return list;
    }

    /** 一隻召喚物的屬性表（還沒疊輔助寶石；疊法見 {@link SkillProfile#effective}）。 */
    public static StatSheet build(final SummonSpec spec, final Base base, final List<Modifier> ownerModifiers) {
        final StatSheet sheet = new StatSheet();
        // 2026-10-26 照 PoE2（Energy Shield from Life）：生命的 es 比例移到能量護盾（生命變少、多一條護盾；護盾吃召喚物的能量護盾加成）
        final double total = Math.max(1, base.life() * spec.life());
        sheet.setBase(StatKeys.LIFE_MAX, Math.max(1, total * (1 - spec.es())));
        if (spec.es() > 0) {
            sheet.setBase(StatKeys.ES_MAX, total * spec.es());
        }
        sheet.setBase(StatKeys.ACCURACY, base.accuracy() * ACCURACY_BONUS);
        // 2026-10-26 照 PoE2 的召喚物：護甲、閃避是那隻怪自己的倍率（沒寫就是 0），而且攻擊一定命中
        sheet.setBase(StatKeys.EVASION, spec.ally() ? base.evasion() * spec.evasion() : base.evasion());
        sheet.setBase(StatKeys.ARMOUR, spec.ally() ? base.armour() * spec.armour() : base.armour());
        if (spec.ally()) {
            sheet.setBase(StatKeys.FLAG_ALWAYS_HIT, 1);
        }
        sheet.setBase(StatKeys.RESIST_MAX, 75);
        // 2026-10-27 照 PoE2（PlayerMinionIntrinsicStats base_critical_hit_damage_bonus 70）：召喚物暴擊傷害加成 +70%；這次之前 +100%（200）
        sheet.setBase(StatKeys.CRIT_MULTI, 170);
        sheet.setBase(tw.exilecore.stats.DamageCalculator.FLAG_PLAYER_SIDE, 1); // 2026-10-27 照 PoE2：召喚物被怪物打用怪物的命中公式
        sheet.setBase(StatKeys.WEAPON_APS, spec.attackSpeed());
        sheet.setBase(StatKeys.WEAPON_CRIT, base.critChance());
        for (final Map.Entry<DamageType, Double> entry : spec.resists().entrySet()) {
            sheet.setBase(entry.getKey().mitigationStat(), entry.getValue());
        }
        sheet.add(new Modifier(StatKeys.WEAPON_MIN, ModType.FLAT, base.damageMin() * spec.damage(), Set.of(Tags.PHYSICAL), "召喚物基礎"));
        sheet.add(new Modifier(StatKeys.WEAPON_MAX, ModType.FLAT, base.damageMax() * spec.damage(), Set.of(Tags.PHYSICAL), "召喚物基礎"));
        // 2026-10：給召喚物的修飾器拿掉 minion 標籤再放進召喚物的屬性表：
        // 召喚物被打時抗性、護甲、閃避是用空的標籤讀的，留著 minion 標籤的話「召喚物抗性」「召喚物護甲」這類不會生效
        // 2026-10-26：還帶著條件標籤（夥伴、存在範圍內）的是呼叫的人沒挑過的：當作條件不成立（挑過的已經拿掉標籤）
        sheet.addAll(stripMinion(forMinion(ownerModifiers, Set.of())));
        sheet.addAll(spec.stats()); // 2026-10-26：召喚物自己的（骸骨戰士的格擋…）
        return sheet;
    }

    /** 拿掉 minion 標籤（放進召喚物自己的屬性表時用：在那裡 minion 標籤已經沒有意義，留著反而讓防禦類讀不到）。 */
    public static List<Modifier> stripMinion(final List<Modifier> modifiers) {
        final List<Modifier> list = new ArrayList<>(modifiers.size());
        for (final Modifier modifier : modifiers) {
            if (modifier.tags().contains(Tags.MINION)) {
                final Set<String> rest = new HashSet<>(modifier.tags());
                rest.remove(Tags.MINION);
                list.add(modifier.withTags(rest));
            } else {
                list.add(modifier);
            }
        }
        return list;
    }

    /**
     * 召喚物打人用的技能描述：代號加 .minion，是近戰攻擊（擲命中、用上面的武器傷害），帶 minion 與技能自己的傷害類型標籤，
     * 物理轉換照技能寫的（焚魂全轉火焰），輔助寶石照技能的。
     */
    public static SkillProfile profile(final SkillBuild build) {
        final SkillProfile base = build.profile();
        final Set<String> tags = new HashSet<>(base.tags());
        tags.remove(Tags.SPELL);
        tags.add(Tags.ATTACK);
        final SummonSpec spec = build.definition().summon();
        tags.add(spec != null && spec.isRanged() ? Tags.PROJECTILE : Tags.MELEE); // 2026-10：遠程召喚物的攻擊算投射物
        if (spec != null && spec.radius() > 0) {
            tags.add(Tags.AREA); // 2026-10-29 照 PoE2（Skeletal Arsonist 的火彈是投射物、範圍）：打中時連周圍一起打的攻擊算範圍（凝聚、震域吃得到）
        }
        tags.add(Tags.MINION);
        return new SkillProfile(base.id() + ".minion", tags, 1.0, null, null, base.conversion(), base.local());
    }

    /** 這隻每秒攻擊幾次（屬性表已經疊好輔助寶石）。 */
    public static double attacksPerSecond(final StatSheet effective, final SkillProfile profile) {
        return Math.max(0.2, Math.min(5, DamageCalculator.attacksPerSecond(effective, profile.tags())));
    }

    /** 兩次攻擊之間幾 tick。 */
    public static int attackTicks(final double attacksPerSecond) {
        return (int) Math.max(4, Math.round(20 / Math.max(0.2, attacksPerSecond)));
    }

    /** 召喚物的最大生命（疊好輔助寶石之後）。 */
    public static double life(final StatSheet effective) {
        return Math.max(1, effective.get(StatKeys.LIFE_MAX, CONTEXT));
    }

    /** 召喚物的最大能量護盾（2026-10-26；沒有的是 0）。 */
    public static double energyShield(final StatSheet effective) {
        // 2026-10-26 照 PoE2 天賦樹：「召喚物獲得最大生命的 x% 為額外最大能量護盾」
        final double fromLife = effective.flat(tw.exilecore.stats.KeystoneKeys.MINION_LIFE_AS_ES, CONTEXT);
        return Math.max(0, effective.get(StatKeys.ES_MAX, CONTEXT) + (fromLife > 0 ? life(effective) * fromLife / 100.0 : 0));
    }

    /** 移動速度倍率（1 = 原版該生物的速度 × summon.speed）。 */
    public static double movementScale(final StatSheet effective) {
        return Math.max(0.3, 1 + effective.increased(StatKeys.MOVEMENT_SPEED, CONTEXT)) * effective.more(StatKeys.MOVEMENT_SPEED, CONTEXT);
    }

    /** 每秒回復最大生命的幾 %（堅韌召喚輔助）。 */
    public static double regenPercent(final StatSheet effective) {
        // 2026-10-26 照 PoE2 天賦樹：「召喚物的生命回復速率」
        return Math.max(0, effective.flat(StatKeys.LIFE_REGEN_PERCENT, CONTEXT))
                * Math.max(0, 1 + effective.increased(tw.exilecore.stats.KeystoneKeys.MINION_LIFE_RECOVERY, CONTEXT));
    }

    /** 給 /stats：主人身上給召喚物的加成，沒有就是空的。 */
    public static List<String> summary(final StatSheet owner) {
        final List<Modifier> mods = ownerModifiers(owner);
        if (mods.isEmpty()) {
            return List.of();
        }
        final StatSheet filtered = new StatSheet().addAll(mods);
        final Set<String> context = Set.of(Tags.MINION, Tags.ATTACK, Tags.MELEE);
        final List<String> lines = new ArrayList<>();
        final String damage = scaledText(filtered.increased(StatKeys.DAMAGE, context), filtered.more(StatKeys.DAMAGE, context));
        if (damage != null) {
            lines.add("召喚物傷害：" + damage);
        }
        final String life = scaledText(filtered.increased(StatKeys.LIFE_MAX, context), filtered.more(StatKeys.LIFE_MAX, context));
        if (life != null) {
            lines.add("召喚物最大生命：" + life);
        }
        final String speed = scaledText(filtered.increased(StatKeys.ATTACK_SPEED, context), filtered.more(StatKeys.ATTACK_SPEED, context));
        if (speed != null) {
            lines.add("召喚物攻擊速度：" + speed);
        }
        final String move = scaledText(filtered.increased(StatKeys.MOVEMENT_SPEED, context), filtered.more(StatKeys.MOVEMENT_SPEED, context));
        if (move != null) {
            lines.add("召喚物移動速度：" + move);
        }
        final double extra = filtered.flat(StatKeys.SKILL_MINIONS, context);
        if (Math.abs(extra) > 1e-9) {
            lines.add("召喚物數量上限 " + (extra > 0 ? "+" : "") + ModifierText.number(extra));
        }
        final double regen = filtered.flat(StatKeys.LIFE_REGEN_PERCENT, context);
        if (regen > 1e-9) {
            lines.add("召喚物每秒回復 " + ModifierText.number(regen) + "% 最大生命");
        }
        return lines;
    }

    /** 「增加 30%、更多 10%」；都沒有回傳 null。 */
    private static String scaledText(final double increased, final double more) {
        final List<String> parts = new ArrayList<>();
        if (Math.abs(increased) > 1e-9) {
            parts.add((increased > 0 ? "增加 " : "減少 ") + ModifierText.number(Math.abs(increased) * 100) + "%");
        }
        if (Math.abs(more - 1) > 1e-9) {
            parts.add(String.format(Locale.ROOT, "%s %s%%", more > 1 ? "更多" : "更少", ModifierText.number(Math.abs(more - 1) * 100)));
        }
        return parts.isEmpty() ? null : String.join("、", parts);
    }
}
