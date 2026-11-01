package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 0.5 Ember Fusillade（星火連珠，PoB2 EmberFusilladePlayer 的 Beam statSet）：
 * 「Consumes a Lightning Infusion if possible to cause the entire Fusillade to create beams that Chain to enemies」——
 * 這一輪（一次連射）裡只要有一次施放用掉了電灌注，整輪的每一顆火星炸開時都再放一道閃電光束，連鎖 4 次（number_of_chains 4），
 * 傷害是自己的逐級表（1–10 → 16–309 → 218–4140，{@value #BEAM}）。這次之前做成獲得 65% 傷害為額外閃電。
 * 純資料與純函式；記號在 {@link Signatures}（整輪）、光束在 {@link ProjectileEngine}。
 */
public final class EmberBeams {

    /** FLAT 1：這一顆火星炸開時放閃電光束（用掉電灌注的灌注加成；整輪的火星都會帶上）。 */
    public static final String MARK = "ember.lightning_beam";
    /** 存光束逐級傷害的隱藏技能。 */
    public static final String BEAM = "proc_ember_beam";
    /** 光束連鎖幾次（不含第一下）。 */
    public static final int CHAINS = 4;
    /** 火星沒有直接打中敵人（打在地上）時，光束找落點幾公尺內最近的敵人（blazing_cluster_subsequent_targeting_radius 30）。 */
    public static final double FIRST_RADIUS_M = 3.0;

    private EmberBeams() {
    }

    /** 光束連鎖幾次：4 ＋ 連鎖的加成（不會少於 0）。 */
    static int chains(final double bonus) {
        return Math.max(0, CHAINS + (int) Math.round(bonus));
    }

    /** 這一輪帶不帶光束：原本帶了就一直帶（整輪），這次施放用掉了電灌注也帶。新的一輪（佇列空了）重算。 */
    static boolean fusillade(final boolean before, final boolean queueEmpty, final boolean infusedNow) {
        return (!queueEmpty && before) || infusedNow;
    }
}
