package tw.exilecore.stats;

/**
 * 戰鬥公式。全部是純函式，數字都可以在這裡調。
 */
public final class Formulas {

    private Formulas() {
    }

    /** 命中率下限。 */
    public static final double MIN_HIT_CHANCE = 0.05;
    /** 護甲減免上限。 */
    public static final double MAX_ARMOUR_REDUCTION = 0.90;
    /** 抗性下限（負抗）。 */
    public static final double MIN_RESIST = -60;
    /** PoE2 命中公式：命中 × 1.25 ÷ (命中 + 閃避 × 0.3)。 */
    public static final double ACCURACY_FACTOR = 1.25;
    public static final double EVASION_FACTOR = 0.3;
    /** PoE2 護甲公式：護甲 ÷ (護甲 + 10 × 傷害)。 */
    public static final double ARMOUR_DAMAGE_FACTOR = 10.0;

    /**
     * 攻擊命中率（2026-10 改成 PoE2）：命中 × 1.25 ÷ (命中 + 閃避 × 0.3)，下限 5%，上限 100%。
     * 60 級怪物命中 490：閃避 1000 閃 23%、2000 閃 44%、4000 閃 64%。法術不用擲命中。
     */
    public static double hitChance(final double accuracy, final double evasion) {
        if (evasion <= 0) {
            return 1.0;
        }
        if (accuracy <= 0) {
            return MIN_HIT_CHANCE;
        }
        final double chance = accuracy * ACCURACY_FACTOR / (accuracy + evasion * EVASION_FACTOR);
        return clamp(chance, MIN_HIT_CHANCE, 1.0);
    }

    /**
     * 護甲對單次物理傷害的減免比例（2026-10 改成 PoE2）：護甲 ÷ (護甲 + 10 × 傷害)，上限 90%。
     * 護甲對小傷害很有效、對大傷害幾乎沒用，這是 PoE 的特性。
     */
    public static double armourReduction(final double armour, final double physicalDamage) {
        if (armour <= 0 || physicalDamage <= 0) {
            return 0;
        }
        return Math.min(MAX_ARMOUR_REDUCTION, armour / (armour + ARMOUR_DAMAGE_FACTOR * physicalDamage));
    }

    /**
     * 2026-10-23（PoE2）：攻擊的命中照距離變差（距離量到目標身體的邊）：3 格以內（近戰觸及範圍）不影響，
     * 之後線性變少，10 格以上少 90%。
     */
    public static final double ACCURACY_FALLOFF_START = 3;
    public static final double ACCURACY_FALLOFF_END = 10;
    public static final double ACCURACY_FALLOFF_MAX = 0.9;

    /** 打在 distance 格外的攻擊，命中更少幾成（0～0.9）。 */
    public static double accuracyFalloff(final double distance) {
        if (distance <= ACCURACY_FALLOFF_START) {
            return 0;
        }
        final double t = (distance - ACCURACY_FALLOFF_START) / (ACCURACY_FALLOFF_END - ACCURACY_FALLOFF_START);
        return Math.min(ACCURACY_FALLOFF_MAX, ACCURACY_FALLOFF_MAX * t);
    }

    /** 抗性減免比例：抗性（百分比點數）夾在 [下限, 上限] 之後除以 100。 */
    public static double resistReduction(final double resistPoints, final double maxResistPoints) {
        return clamp(resistPoints, MIN_RESIST, maxResistPoints) / 100.0;
    }

    /** 攻擊間隔（tick）：20 ÷ 每秒攻擊次數，最少 1 tick。 */
    public static int attackIntervalTicks(final double attacksPerSecond) {
        if (attacksPerSecond <= 0) {
            return 20;
        }
        return Math.max(1, (int) Math.round(20.0 / attacksPerSecond));
    }

    /** 屬性帶來的基礎加成（2026-10 改成 PoE2）：每點力量 +2 生命，每點智慧 +2 魔力，每點敏捷 +6 命中。 */
    public static double lifeFromStrength(final double strength) {
        return strength * 2.0;
    }

    public static double manaFromIntelligence(final double intelligence) {
        return intelligence * 2.0;
    }

    public static double accuracyFromDexterity(final double dexterity) {
        return dexterity * 6.0;
    }

    /** 每 10 敏捷增加 2% 閃避；回傳小數。 */
    public static double evasionIncreaseFromDexterity(final double dexterity) {
        return 0; // 2026-10 PoE2：敏捷不再給閃避（原本每 10 點 2%）
    }

    /** 每 10 智慧增加 2% 能量護盾；回傳小數。 */
    public static double esIncreaseFromIntelligence(final double intelligence) {
        return 0; // 2026-10 PoE2：智慧不再給能量護盾
    }

    /** 每 10 力量增加 2% 近戰物理傷害；回傳小數。 */
    public static double meleePhysicalIncreaseFromStrength(final double strength) {
        return 0; // 2026-10 PoE2：力量不再給近戰物理傷害
    }

    /** 角色基礎生命：38 + 12 × 等級。 */
    public static double baseLife(final int level) {
        return 38 + 12.0 * level;
    }

    /** 角色基礎魔力：34 + 6 × 等級。 */
    public static double baseMana(final int level) {
        return 34 + 6.0 * level;
    }

    /** 角色基礎閃避：53 + 3 × 等級。 */
    public static double baseEvasion(final int level) {
        return 53 + 3.0 * level;
    }

    /** 角色基礎命中：2 × 等級（敏捷另加）。 */
    public static double baseAccuracy(final int level) {
        return 2.0 * level;
    }

    /** 怪物招式冷卻倍率的下限：地圖詞綴再怎麼縮短，也不會比基礎快過四倍。 */
    public static final double MIN_MONSTER_COOLDOWN = 0.25;

    /** 怪物屬性表算出來的冷卻倍率（1 = 沒有加成；0.6 = 縮短四成）夾在下限以上。 */
    public static double monsterCooldownFactor(final double multiplier) {
        return Math.max(MIN_MONSTER_COOLDOWN, multiplier);
    }

    // ---- 能量護盾回充 ----

    /** 被打之後多久才開始回充（tick；2026-10 改成 PoE2 的 4 秒）。 */
    public static final long ES_RECHARGE_DELAY_TICKS = 80;
    /** 基礎回充：延遲過後每秒回最大值的這個比例（PoE2：12.5%，從 0 回滿 8 秒）。 */
    public static final double ES_RECHARGE_FRACTION = 0.125;
    /** 延遲最短剩基礎的幾成（再縮短也沒用）。 */
    public static final double ES_DELAY_FLOOR = 0.2;

    /** 能量護盾每秒回充量：最大值的 12.5% × 回充速度倍率（增加、更多）。 */
    public static double esRechargePerSecond(final double maxEnergyShield, final double speedMultiplier) {
        return Math.max(0, maxEnergyShield) * ES_RECHARGE_FRACTION * Math.max(0, speedMultiplier);
    }

    /** 回充前的等待（tick）：基礎 80 tick × 延遲倍率（縮短最多剩兩成）。 */
    public static long esRechargeDelayTicks(final double delayMultiplier) {
        return Math.round(ES_RECHARGE_DELAY_TICKS * Math.max(ES_DELAY_FLOOR, delayMultiplier));
    }

    public static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }
}
