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

    /**
     * 攻擊命中率：命中 ÷ (命中 + (閃避 ÷ 4)^0.8)，下限 5%，上限 100%。
     * 法術不用擲命中。
     */
    public static double hitChance(final double accuracy, final double evasion) {
        if (evasion <= 0) {
            return 1.0;
        }
        if (accuracy <= 0) {
            return MIN_HIT_CHANCE;
        }
        final double chance = accuracy / (accuracy + Math.pow(evasion / 4.0, 0.8));
        return clamp(chance, MIN_HIT_CHANCE, 1.0);
    }

    /**
     * 護甲對單次物理傷害的減免比例：護甲 ÷ (護甲 + 5 × 傷害)，上限 90%。
     * 護甲對小傷害很有效、對大傷害幾乎沒用，這是 PoE 的特性。
     */
    public static double armourReduction(final double armour, final double physicalDamage) {
        if (armour <= 0 || physicalDamage <= 0) {
            return 0;
        }
        return Math.min(MAX_ARMOUR_REDUCTION, armour / (armour + 5.0 * physicalDamage));
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

    /** 屬性帶來的基礎加成，與 PoE 相同：每點力量 +0.5 生命，每點智慧 +0.5 魔力，每點敏捷 +2 命中。 */
    public static double lifeFromStrength(final double strength) {
        return strength * 0.5;
    }

    public static double manaFromIntelligence(final double intelligence) {
        return intelligence * 0.5;
    }

    public static double accuracyFromDexterity(final double dexterity) {
        return dexterity * 2.0;
    }

    /** 每 10 敏捷增加 2% 閃避；回傳小數。 */
    public static double evasionIncreaseFromDexterity(final double dexterity) {
        return dexterity * 0.002;
    }

    /** 每 10 智慧增加 2% 能量護盾；回傳小數。 */
    public static double esIncreaseFromIntelligence(final double intelligence) {
        return intelligence * 0.002;
    }

    /** 每 10 力量增加 2% 近戰物理傷害；回傳小數。 */
    public static double meleePhysicalIncreaseFromStrength(final double strength) {
        return strength * 0.002;
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

    public static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }
}
