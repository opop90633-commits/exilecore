package tw.exilecore.character;

import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

/**
 * 2026-11-07 照站長「守衛新增暮色瓣落、遺忘看守」：符文保護（PoE2 Runic Ward；poe2db Runic_Ward、PoB2 CalcDefence）。
 * <ul>
 *   <li>死前的最後一道保障：受到的傷害（擊中、持續傷害這種「承受傷害」）會讓生命歸零時，你剩 1 點生命，剩下的由符文保護吃；</li>
 *   <li>符文保護吃不下剩下的那些，照樣會死（符文保護不扣）；</li>
 *   <li>不是承受傷害的生命流失（技能消耗生命、犧牲生命、「每秒失去生命」）不會被擋；</li>
 *   <li>預設每秒回復最大符文保護的 {@value #REGEN_PERCENT}%（一直回，不管有沒有被打）。</li>
 * </ul>
 * 最大符文保護是屬性表的 {@link StatKeys#WARD_MAX}（FLAT、INCREASED、MORE）。這次之前沒有符文保護（磐石之拳那幾條換成能量護盾）。
 */
public final class RunicWard {

    /** 每秒回復最大符文保護的幾 %（PoE2 預設 5%）。 */
    public static final double REGEN_PERCENT = 5;

    private RunicWard() {
    }

    /** 某張屬性表的最大符文保護（沒有是 0）。 */
    public static double max(final StatSheet sheet) {
        return sheet == null ? 0 : Math.max(0, sheet.get(StatKeys.WARD_MAX));
    }

    /** 純函式：這麼多秒回復多少符文保護（最大值的 {@value #REGEN_PERCENT}% ／秒）。 */
    public static double regen(final double max, final double seconds) {
        return Math.max(0, max) * REGEN_PERCENT / 100.0 * Math.max(0, seconds);
    }

    /**
     * 純函式：生命還有 life，這一下要扣 lifeDamage 生命（護盾、守護、心靈昇華都先扣過了）、身上有 ward 符文保護時，
     * 回傳 {真的要扣的生命, 用掉的符文保護}。
     * 不會死（扣完還有生命）、或沒有符文保護：照扣、不用符文保護。會死而且符文保護吃得下多出來的那些：生命扣到剩 1（本來就不到 1 的不扣），
     * 多出來的由符文保護吃。吃不下：照扣（照樣死），符文保護不用。
     */
    public static double[] absorb(final double life, final double lifeDamage, final double ward) {
        final double damage = Math.max(0, lifeDamage);
        if (life - damage > 0 || ward <= 0 || life <= 0) {
            return new double[] {damage, 0};
        }
        final double kept = Math.max(0, Math.min(damage, life - 1));
        final double excess = damage - kept;
        if (excess > ward) {
            return new double[] {damage, 0};
        }
        return new double[] {kept, excess};
    }
}
