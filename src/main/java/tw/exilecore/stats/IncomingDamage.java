package tw.exilecore.stats;

/**
 * 玩家受到的一下最後怎麼扣（護盾的部分已經算好）：
 * <ul>
 *   <li>扣生命的部分有 x% 改扣魔力（傳奇「受到的傷害先從魔力扣」，PoE 的心靈勝過物質；魔力不夠的照扣生命）</li>
 *   <li>受到的傷害（護盾＋生命＋魔力）有 y% 之後慢慢回成生命（傳奇的「回復」，PoE 的 recoup；存進偷取一起回）</li>
 * </ul>
 * 純函式，不依賴 Bukkit。
 *
 * @param es     扣護盾
 * @param life   扣生命
 * @param mana   扣魔力
 * @param recoup 之後慢慢回成生命的量
 */
public record IncomingDamage(double es, double life, double mana, double recoup) {

    /**
     * @param esDamage        扣護盾的量
     * @param lifeDamage      本來要扣生命的量
     * @param currentMana     目前的魔力
     * @param fromManaPercent 扣生命的部分有幾 % 改扣魔力（百分比點數，最多 100）
     * @param recoupPercent   受到的傷害有幾 % 之後回成生命（百分比點數，最多 100）
     */
    public static IncomingDamage split(final double esDamage, final double lifeDamage, final double currentMana,
                                       final double fromManaPercent, final double recoupPercent) {
        final double es = Math.max(0, esDamage);
        double life = Math.max(0, lifeDamage);
        double mana = 0;
        final double fromMana = clampPercent(fromManaPercent);
        if (fromMana > 0 && life > 0) {
            mana = Math.min(Math.max(0, currentMana), life * fromMana);
            life -= mana;
        }
        final double recoup = (es + life + mana) * clampPercent(recoupPercent);
        return new IncomingDamage(es, life, mana, recoup);
    }

    /**
     * 守護（護符詞綴，效果期間先替你吃掉傷害）吃掉多少：先吃扣護盾的那份、再吃扣生命的那份，最多 available。回傳 {從護盾那份, 從生命那份}。
     * 2026-11-02 照站長（PoB2 CalcDefence 的 EHP 共用）：從 CombatListener.applyToPlayer 抽出來；算法沒變。
     */
    public static double[] guardAbsorb(final double esDamage, final double lifeDamage, final double available) {
        final double fromEs = esDamage > 0 ? Math.min(available, esDamage) : 0;
        final double fromLife = lifeDamage > 0 ? Math.min(available - fromEs, lifeDamage) : 0;
        return new double[] {fromEs, fromLife};
    }

    private static double clampPercent(final double percent) {
        return Math.max(0, Math.min(100, percent)) / 100.0;
    }
}
