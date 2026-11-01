package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 Vine Arrow（荊棘矢）：植物可以被下毒——你的擊中打到地上的植物時，植物存下那一下的中毒每秒傷害；
 * 存著的時候藤蔓另外每秒造成「存下的中毒每秒傷害 × {@link #STORED}%」的混沌持續傷害（poison_vine_arrow_vine_stored_poison_damage_+%_final −50，
 * 品質每 1% 再更多 0.5%）。存下的毒照中毒的持續時間消失；同時只算最強的一道（PoE2 預設一個目標只吃最強的那道中毒）。
 * 純函式，不依賴 Bukkit。這次之前荊棘矢的植物不能被下毒、品質沒有效果。
 */
public final class VinePlants {

    /** FLAT（skills.yml 的 modifiers）：存下的中毒每秒傷害有幾 % 變成藤蔓的混沌持續傷害（0 = 這一灘不是會存毒的植物）。 */
    public static final String STORED = "vine.stored_poison";

    private VinePlants() {
    }

    /** 藤蔓每秒另外的混沌傷害：存下的中毒每秒傷害 × percent% × more（品質的更多）。 */
    public static double extraDps(final double storedPoisonDps, final double percent, final double more) {
        if (storedPoisonDps <= 0 || percent <= 0) {
            return 0;
        }
        return storedPoisonDps * percent / 100.0 * Math.max(0, more);
    }

    /**
     * 植物又被下了一道毒：還沒過期的照最強的那道（新的一樣強或更強就換成新的、重新計時），過期了直接換成新的。
     *
     * @return {每秒傷害, 到哪個 tick}
     */
    public static double[] store(final double currentDps, final long currentUntil, final double newDps, final long now, final long durationTicks) {
        if (newDps <= 0) {
            return new double[] {now < currentUntil ? currentDps : 0, now < currentUntil ? currentUntil : 0};
        }
        if (now < currentUntil && currentDps > newDps) {
            return new double[] {currentDps, currentUntil};
        }
        return new double[] {newDps, now + Math.max(1, durationTicks)};
    }
}
