package tw.exilecore.stats;

/**
 * 護甲、閃避、命中換成百分比（角色面板、/stats）：對同等級的普通怪物，跟 PoE 角色面板的「估計值」一樣。純計算，不依賴 Bukkit。
 *
 * @param monsterHit            同等級普通怪物一下的平均物理傷害
 * @param armourReduction       護甲對這一下的減免
 * @param armourReductionBigHit 護甲對三倍大的一下的減免（護甲擋小傷害比擋大傷害有效）
 * @param evadeChance           躲開這種怪物攻擊的機率
 * @param hitChance             攻擊打中這種怪物的機率
 */
public record DefenceEstimates(double monsterHit, double armourReduction, double armourReductionBigHit, double evadeChance,
                               double hitChance) {

    /** 怪物的傷害範圍、命中、閃避，與角色的護甲、閃避、命中。 */
    public static DefenceEstimates of(final double monsterMin, final double monsterMax, final double monsterAccuracy,
                                      final double monsterEvasion, final double armour, final double evasion, final double accuracy) {
        final double hit = (monsterMin + monsterMax) / 2.0;
        return new DefenceEstimates(hit,
                Formulas.armourReduction(armour, hit),
                Formulas.armourReduction(armour, hit * 3),
                1 - Formulas.monsterHitChance(monsterAccuracy, evasion), // 2026-10-27 照 PoE2：怪物打玩家的公式（這次之前用玩家打怪的）
                Formulas.hitChance(accuracy, monsterEvasion));
    }
}
