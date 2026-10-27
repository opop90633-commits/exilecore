package tw.exilecore.skill;

import java.util.Map;

/**
 * 2026-10-26 照 PoE2 0.5：召喚物自己的技能與指令（Command）的數值（RePoE 0.5.5 匯出、Path of Building PoE2 的資料）。純資料，不依賴 Bukkit。
 * <p>
 * 表裡的傷害、生命是 PoE2 的單位（寶石 1～20 級），用的時候乘 {@link MinionSheets#poe2Factors} 換成我們的
 * （超過 20 級照 20 級的值，再乘 {@link MinionSheets#extraScale}）。距離 1 格 = 1 格。
 * <p>
 * 指令的操作方式（2026-10-26）：精魂召喚綁在上方按鍵格就一直開著，以前按那個鍵只會提示「一直開著，不用按」；
 * 現在按下去就是對那種召喚物下指令（PoE2 的 Command: 技能），花指令的魔力、用指令的施放時間（技能的 cast_time），
 * 範圍內每一隻冷卻好了的照自己的冷卻放一次。附魂術按下去是「附身」附近的屍體；鴉群密謀按下去是亂鴉狂舞（Mad Flight）。
 */
final class MinionAbilities {

    static final String WARRIOR = "summon_skeletons";
    static final String BRUTE = "skeletal_brute";
    static final String SNIPER = "skeletal_archers";
    static final String ARSONIST = "skeletal_pyromancer";
    static final String STORM = "skeletal_stormcaller";
    static final String FROST = "skeletal_frostcaller";
    static final String CLERIC = "skeletal_cleric";
    static final String REAVER = "skeletal_reaver";
    static final String WOLVES = "wolf_pack";
    static final String BEAST = "beast_companion";
    static final String SWARM = "ravenous_swarm";
    static final String SPIRITS = "raging_spirits";
    static final String SPECTRE = "bound_spectre";
    static final String RAVENS = "spiraling_conspiracy";
    /** 2026-10-27 照 PoE2 昇華：弩衛的支援火力（Supporting Fire）的砲手。 */
    static final String ARTILLERY = "supporting_fire";
    /** 2026-10-27 照 PoE2 昇華：沙靈使的三位巨靈（Kelari／Navira／Ruzhan）。 */
    static final String KELARI = "kelari_djinn";
    static final String NAVIRA = "navira_djinn";
    static final String RUZHAN = "ruzhan_djinn";
    /**
     * 2026-10-27 照 PoE2（MinionsAreUndamagable、只在你下指令時行動）：不會受傷、敵人不理、自己不會主動打的召喚物
     * （支援火力的砲手、三位巨靈）。不能被獻祭、引爆，也不聽「任何召喚物」的指令。
     */
    static final java.util.Set<String> COMMAND_ONLY = java.util.Set.of(ARTILLERY, KELARI, NAVIRA, RUZHAN);

    static boolean commandOnly(final String skillId) {
        return skillId != null && COMMAND_ONLY.contains(skillId);
    }

    private MinionAbilities() {
    }

    // ---- 支援火力（Supporting Fire，2026-10-27 照 PoE2 0.5：RePoE DeathFromAboveDaemonMinion／CommandDeathFromAbovePlayer）----

    /** 從天而降（Death From Above，指令）：每一輪 763% 傷害、共 6 輪、每 0.75 秒一輪，落在 14 × 14 格（換成同面積的圓：半徑 7.9 格）。 */
    static final double VOLLEY_DAMAGE = 7.63;
    static final int VOLLEYS = 6;
    static final long VOLLEY_TICKS = 15;
    static final double VOLLEY_RADIUS = 7.9;
    /** 每一輪箭落地前的延遲（explosion_delay_ms 250）。 */
    static final long VOLLEY_DELAY = 5;
    /** 砲手每 1 點力量增加 1% 傷害、每 3 點敏捷增加 1% 範圍（minion_1%_damage_+%_per_X_player_strength 1、…_area_…_dexterity 3）。 */
    static final double STR_PER_DAMAGE = 1;
    static final double DEX_PER_AREA = 3;
    /** 砲手站在你身後幾格。 */
    static final double ARTILLERY_BEHIND = 2.5;

    // ---- 通用 ----

    /** 能量護盾：最後一次受傷後 4 秒開始回充（PoE2 BaseShieldRegenCooldownTimeMs 4000）。 */
    static final long ES_DELAY_TICKS = 80;
    /** 每秒回充最大能量護盾的 12.5%（怪物 energy_shield_recharge_rate_per_minute_% 750）。 */
    static final double ES_RECHARGE_PER_SECOND = 0.125;
    /** 會自己復活的召喚物（骷髏、附身、夥伴）死後 12 秒回來（minion_base_resummon_time_ms 12000）。 */
    static final long RESUMMON_TICKS = 240;
    /** 沒有覆寫的（灰狼隨）照全域 7.5 秒（global_resummon_time_ms 7500）。 */
    static final long GLOBAL_RESUMMON_TICKS = 150;
    /** 恫嚇（PoE2 Intimidate）：受到的傷害增加 10%。 */
    static final double INTIMIDATE_TAKEN = 0.10;

    /** 指令：名稱、每一隻的冷卻（秒）、魔力（寶石 1～40 級）。 */
    record Command(String name, double cooldown, int[] mana) {
        int manaAt(final int level) {
            return mana.length == 0 ? 0 : mana[Math.max(1, Math.min(mana.length, level)) - 1];
        }
    }

    // 2026-10-28 照 PoE2（poe2db／RePoE Command 技能 1–40 級的魔力）；這次之前只寫到 20 級，21 級以上停在 20 級的值
    private static final int[] BRUTE_MANA = {8, 9, 11, 13, 15, 17, 19, 22, 25, 28, 32, 36, 40, 45, 50, 55, 61, 68, 75, 83,
            92, 101, 112, 123, 135, 148, 162, 178, 195, 213, 233, 254, 278, 303, 330, 360, 392, 427, 464, 505};
    private static final Map<String, Command> COMMANDS = Map.of(
            BRUTE, new Command("震地咆哮", 8, BRUTE_MANA),
            SNIPER, new Command("毒氣箭", 6, new int[] {8, 9, 11, 13, 15, 17, 20, 22, 25, 29, 32, 36, 41, 46, 51, 56, 63, 69, 77, 85,
                    94, 103, 114, 125, 137, 151, 165, 181, 198, 217, 237, 259, 283, 308, 336, 366, 399, 435, 473, 514}),
            ARSONIST, new Command("爆裂殉身", 3, new int[] {9, 10, 12, 14, 16, 19, 22, 25, 28, 31, 35, 40, 45, 50, 55, 62, 68, 76, 84, 93,
                    102, 113, 124, 137, 150, 165, 180, 198, 216, 237, 259, 283, 309, 337, 367, 400, 436, 475, 517, 562}),
            STORM, new Command("死亡風暴", 5, new int[] {15, 17, 21, 24, 28, 32, 36, 41, 47, 53, 59, 67, 75, 83, 93, 103, 114, 127, 140, 155,
                    171, 188, 207, 228, 250, 274, 301, 330, 361, 395, 432, 471, 515, 561, 612, 667, 727, 791, 861, 936}),
            FROST, new Command("冰甲", 12, new int[] {16, 20, 23, 27, 31, 36, 41, 46, 52, 59, 66, 75, 83, 93, 104, 115, 128, 142, 157, 173,
                    191, 210, 232, 255, 280, 307, 336, 369, 404, 441, 482, 527, 575, 628, 684, 746, 812, 884, 962, 1047}),
            REAVER, new Command("激怒", 4, BRUTE_MANA),
            RAVENS, new Command("亂鴉狂舞", 10, new int[0]),
            // 2026-10-27 照 PoE2（Command: Death From Above：冷卻 10 秒、魔力 8～83；這次之前支援火力是 4 秒冷卻的箭雨，不花魔力）
            ARTILLERY, new Command("從天而降", 10, BRUTE_MANA));

    /** 這個精魂召喚有沒有指令（骸骨戰士、祭司、狼、蟲、焚魂沒有）。 */
    static Command command(final String skillId) {
        return COMMANDS.get(skillId);
    }

    /** 寶石等級 → 表的位置（超過 20 級照 20 級）。 */
    static int row(final int level) {
        return Math.max(1, Math.min(20, level)) - 1;
    }

    /** 寶石等級 → 1～40 級表的位置（2026-10-28：照 PoE2 寫到 40 級的表用這個）。 */
    static int row40(final int level) {
        return Math.max(1, Math.min(40, level)) - 1;
    }

    /** {最小, 最大} 表在這一級的值。 */
    static double[] range(final int[][] table, final int level) {
        return new double[] {table[0][row(level)], table[1][row(level)]};
    }

    // ---- 骸骨重卒（Skeletal Brute）----

    /** 碎骨（Bonebreaker，自動）：冷卻 4 秒、攻擊速度 120%、180% 傷害、碾壓；重暈蓄勢待暈的敵人時接一記震波（480%、2 格）。 */
    static final double BONEBREAKER_COOLDOWN = 4;
    static final double BONEBREAKER_SPEED = 1.2;
    static final double BONEBREAKER_DAMAGE = 1.8;
    static final double SHOCKWAVE_DAMAGE = 4.8;
    static final double SHOCKWAVE_RADIUS = 2.0;
    /** 震地咆哮（Shattering Roar，指令）：4 格內的敵人恫嚇 4 秒；消耗範圍內敵人與友軍的凍結，各炸 2.4 格的冰冷傷害，暴擊率 12%。 */
    static final double ROAR_RADIUS = 4.0;
    static final double ROAR_SECONDS = 4.0;
    static final double ROAR_EXPLOSION = 2.4;
    static final double ROAR_CRIT = 12;
    static final int[][] ROAR_COLD = {
            {5, 8, 14, 21, 31, 44, 61, 83, 112, 152, 204, 274, 368, 495, 667, 902, 1223, 1664, 2273, 3119},
            {7, 12, 21, 32, 47, 66, 91, 124, 169, 228, 306, 411, 552, 743, 1001, 1353, 1834, 2495, 3409, 4678}};

    // ---- 骸骨射手（Skeletal Sniper）----

    /** 毒氣箭（Gas Arrow，指令）：冷卻 6 秒、攻擊速度 120%；落點 1.4 格 80% 傷害，留下 8 秒的毒雲。 */
    static final double GAS_SPEED = 1.2;
    static final double GAS_IMPACT = 0.8;
    static final double GAS_RADIUS = 1.4;
    static final double GAS_SECONDS = 8;
    /** 毒雲每秒半徑更多 20%，最多更多 80%。 */
    static final double GAS_GROWTH = 0.2;
    static final double GAS_GROWTH_MAX = 0.8;
    /** 毒雲不擊中，但像 220% 傷害的擊中一樣使敵人中毒。 */
    static final double GAS_POISON = 2.2;
    /** 被引爆（焰術士的火彈、爆裂殉身，或碰到點燃中的敵人）：630% 傷害，物理全部轉火焰。 */
    static final double GAS_EXPLOSION = 6.3;

    // ---- 骸骨焰術士（Skeletal Arsonist）----

    /** 火彈（Fire Bomb，自動）：1.6 格、物理 70% 轉火焰，會引爆毒雲。 */
    static final double FIRE_BOMB_RADIUS = 1.6;
    /** 爆裂殉身（Explosive Demise，指令）：冷卻 3 秒；引爆目標 3 格內生命低於門檻的自己的召喚物，2.4 格、300% 傷害全轉火焰，加上牠最大生命 8% 的火焰傷害。 */
    static final double DEMISE_DAMAGE = 3.0;
    static final double DEMISE_RADIUS = 2.4;
    static final double DEMISE_SEARCH = 3.0;
    static final double DEMISE_LIFE_PERCENT = 8;
    /** 指令：目標召喚物目前生命低於這個（PoE2 的生命單位，寶石 1～40 級；用 {@link #row40}）。
     *  2026-10-28 照 PoE2（RePoE CommandSkeletalArsonistPlayer 1–40 級）；這次之前只寫到 20 級，21 級以上停在 1379 */
    static final int[] DEMISE_THRESHOLD = {29, 54, 83, 114, 148, 187, 229, 277, 329, 386, 449, 518, 594, 677, 770, 870, 981, 1101, 1235, 1379,
            1538, 1711, 1899, 2105, 2330, 2575, 2842, 3134, 3451, 3795, 4173, 4583, 5028, 5513, 6040, 6612, 7236, 7913, 8647, 9447};

    // ---- 骸骨雷術士（Skeletal Storm Mage）----

    /** 電弧（Arc，自動法術）：施放 1.25 秒、暴擊率 9%、連鎖 2 次（3.5 格）、感電機率更多 100%。 */
    static final double ARC_CAST = 1.25;
    static final double ARC_CRIT = 9;
    static final int ARC_CHAINS = 2;
    static final double ARC_CHAIN_RANGE = 3.5;
    static final int[][] ARC_LIGHTNING = {
            {1, 1, 1, 2, 2, 3, 3, 4, 5, 5, 6, 7, 9, 10, 11, 13, 15, 17, 19, 21},
            {11, 17, 24, 32, 40, 50, 61, 74, 88, 103, 121, 141, 163, 188, 216, 246, 281, 319, 361, 408}};
    /** 死亡風暴（Death Storm，指令）：施放 2 秒、冷卻 5 秒、暴擊率 10%；目標區域裡每一具倒下的骷髏、屍體落雷（2 格）並留下 6 秒的感電地面。 */
    static final double STORM_CAST = 2.0;
    static final double STORM_CRIT = 10;
    static final double STORM_RADIUS = 2.0;
    /** 目標區域多大（原作沒寫區域大小，這裡取 4 格）。 */
    static final double STORM_AREA = 4.0;
    static final int[][] STORM_LIGHTNING = {
            {14, 21, 29, 38, 49, 62, 76, 92, 110, 131, 154, 180, 210, 244, 281, 324, 371, 424, 484, 551},
            {77, 118, 164, 217, 279, 349, 429, 520, 623, 740, 873, 1022, 1191, 1381, 1595, 1835, 2104, 2405, 2743, 3121}};

    // ---- 骸骨霜術士（Skeletal Frost Mage）----

    /** 冰箭（Ice Shard，自動法術）：施放 1.25 秒；投射物暴擊率 11%，到了炸開 0.8 格（暴擊率 12%）；必定凍結累積、凍結累積更多 100%。 */
    static final double SHARD_CAST = 1.25;
    static final double SHARD_CRIT = 11;
    static final double SHARD_EXPLOSION_CRIT = 12;
    static final double SHARD_RADIUS = 0.8;
    static final int[][] SHARD_COLD = {
            {4, 6, 9, 11, 14, 18, 22, 26, 30, 36, 42, 48, 55, 63, 72, 82, 92, 104, 117, 132},
            {6, 9, 13, 17, 22, 27, 32, 39, 46, 54, 62, 72, 83, 95, 108, 123, 139, 156, 176, 198}};
    static final int[][] SHARD_EXPLOSION_COLD = {
            {3, 5, 7, 9, 12, 14, 17, 21, 25, 29, 34, 39, 45, 51, 58, 66, 75, 84, 95, 107},
            {5, 8, 11, 14, 17, 22, 26, 31, 37, 43, 50, 58, 67, 77, 87, 99, 112, 127, 142, 160}};
    /** 冰甲（Ice Armour，指令）：冷卻 12 秒；目標 4 格內自己的召喚物 6 秒內受到的擊中傷害更少 40%，打牠們的敵人受到冰冷傷害，算凍結中。 */
    static final double ARMOUR_RADIUS = 4.0;
    static final double ARMOUR_SECONDS = 6.0;
    static final double ARMOUR_LESS = 40;
    static final int[][] ARMOUR_COLD = {
            {3, 5, 8, 12, 18, 25, 34, 47, 64, 86, 116, 155, 209, 281, 378, 511, 693, 943, 1288, 1767},
            {4, 7, 12, 18, 26, 37, 52, 71, 96, 129, 173, 233, 313, 421, 567, 767, 1039, 1414, 1932, 2651}};

    // ---- 骸骨祭司（Skeletal Cleric）----

    /** 治療（Heal，自動）：施放 1 秒，替一隻受傷的自己的召喚物加 4 秒的生命回復（固定值，不是百分比）。 */
    static final double HEAL_CAST = 1.0;
    static final double HEAL_SECONDS = 4.0;
    /** 每分鐘回復多少生命（PoE2 的生命單位，寶石 1～40 級；用 {@link #row40}）。
     *  2026-10-28 照 PoE2（RePoE HealSkeletonClericMinion 1–40 級）；這次之前只寫到 20 級，21 級以上停在 27243 */
    static final int[] HEAL_PER_MINUTE = {776, 1410, 2109, 2884, 3695, 4599, 5547, 6591, 7717, 8925, 10184, 11587, 13099, 14673, 16435,
            18306, 20314, 22439, 24779, 27243, 29927, 32745, 35801, 39046, 42572, 46315, 50317, 54638, 59219, 64126, 69402, 75060,
            81057, 87502, 94367, 101706, 109584, 118001, 126925, 136534};
    /** 治療找多遠內的召喚物（原作是祭司的施法距離 8 格）。 */
    static final double HEAL_RANGE = 8;
    /** 復活骷髏（Revive Skeleton，自動）：施放 1.2 秒、冷卻 5 秒，倒下的骷髏馬上回來（不用等 12 秒）。 */
    static final double REVIVE_COOLDOWN = 5;

    // ---- 骸骨狂卒（Skeletal Reaver）----

    /** 激怒（Enrage，指令）：冷卻 4 秒，全部的狂卒一直激怒：擊中獲得 2 點怒氣，每秒失去最大生命 1%，每過一秒失去的速度更多 20%。 */
    static final int RAGE_ON_HIT = 2;
    static final int RAGE_MAX = 30;
    /** 每點怒氣：攻擊傷害更多 1%（召喚物內建）、攻擊速度增加 3%（狂卒寶石）。 */
    static final double RAGE_DAMAGE = 0.01;
    static final double RAGE_SPEED = 0.03;
    /** 4 秒沒有獲得怒氣，每秒失去 5 點。 */
    static final long RAGE_DECAY_DELAY = 80;
    static final double RAGE_DECAY_PER_SECOND = 5;

    /** 激怒後第 seconds 秒，每秒失去最大生命的比例（1% × 1.2^經過的整秒數）。 */
    static double enrageDegen(final double seconds) {
        return 0.01 * Math.pow(1.2, Math.floor(Math.max(0, seconds)));
    }

    // ---- 狼（Wolf Companion：灰狼隨、荒原狼伴）----

    /** 躍擊（Leap Slam）：冷卻 7 秒、攻擊速度 125%、落地 1 格範圍、擊退；依跳的距離最多多 0.25 秒。 */
    static final double LEAP_COOLDOWN = 7;
    static final double LEAP_SPEED = 1.25;
    static final double LEAP_RADIUS = 1.0;
    static final double LEAP_MAX_EXTRA = 0.25;
    /** 太近就不跳（照躍擊的最短距離，取 2 格）。 */
    static final double LEAP_MIN = 2.0;
    static final double LEAP_MAX = 9.0;

    // ---- 蟲噬群 ----

    /** 蟲群只打你身邊 4 格內的敵人。 */
    static final double SWARM_RADIUS = 4.0;

    // ---- 鴉群密謀 ----

    /** 振翅熵流（Winged Entropy）：你身邊 2 格的持續傷害，每一隻烏鴉持續傷害更多 5%、範圍更多 5%。 */
    static final double ENTROPY_RADIUS = 2.0;
    static final double ENTROPY_PER_RAVEN = 0.05;
    /** 每秒的混沌、物理傷害（0 隻烏鴉，PoE2 單位；寶石 1～20 級）。 */
    static final double[] ENTROPY_CHAOS = {16.2, 24.6, 34.3, 45.4, 58.3, 72.9, 89.6, 108.7, 130.3, 154.8, 182.5, 213.8, 249.1, 288.8, 333.5,
            383.7, 440.0, 503.1, 573.7, 652.7};
    static final double[] ENTROPY_PHYSICAL = {4.0, 6.1, 8.6, 11.4, 14.6, 18.2, 22.4, 27.2, 32.6, 38.7, 45.6, 53.5, 62.3, 72.2, 83.4, 95.9,
            110.0, 125.8, 143.4, 163.2};
    /** 亂鴉狂舞（Mad Flight，指令）：用掉 10 隻烏鴉，兩面鏡子之間（寬 2.2、長 7 格）5 秒，每 0.12 秒打一下 550%（70% 轉混沌）。 */
    static final int FLIGHT_RAVENS = 10;
    static final double FLIGHT_DAMAGE = 5.5;
    static final double FLIGHT_CHAOS = 0.7;
    static final double FLIGHT_WIDTH = 2.2;
    static final double FLIGHT_LENGTH = 7.0;
    static final double FLIGHT_SECONDS = 5.0;
    static final double FLIGHT_TICK = 0.12;
    /** 烏鴉（Raven Conspiracy）的傷害倍率與浮動。 */
    static final double RAVEN_DAMAGE = 0.67;
    static final double RAVEN_SPREAD = 0.2;

    // ---- 附魂術、夥伴的精魂 ----

    /** 附身的召喚物每一隻保留 floor(50 × P^0.75) 精魂（P = 那種怪的強度；我們用 monsters.yml 的生命倍率）。 */
    static int spectreSpirit(final double power) {
        return (int) Math.floor(50 * Math.pow(Math.max(0.05, power), 0.75));
    }

    /** 夥伴保留最大精魂的 floor(100 × √P) × 0.3 %。 */
    static double companionSpiritPercent(final double power) {
        return Math.floor(100 * Math.sqrt(Math.max(0.05, power))) * 0.3;
    }

    /** 附身、夥伴的傷害：PoE2 用怪物傷害表再乘「隱藏等級補正」，約等於召喚物表 × 1.25（0.3 版加的伺服器端 25% 更多傷害）。 */
    static final double SPECTRE_BEAST_FIXUP = 1.25;
}
