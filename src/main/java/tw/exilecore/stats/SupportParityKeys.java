package tw.exilecore.stats;

/**
 * 2026-11-01 照 PoE2 把幾顆「機制近似」的輔助改成跟原作一樣時新增的屬性鍵與純計算（不放進 {@link StatKeys}，方便合併）：
 * 遠釘（Lockdown）、脆晶（Icicle）、穩擊（Dazzle）、蓄嘯（Premeditation）。
 */
public final class SupportParityKeys {

    private SupportParityKeys() {
    }

    // ---- 遠釘（Lockdown：support_lockdown_distance_based_pin_damage_+%_final 120）----

    /** FLAT（%）：敵人在你 {@value #LOCKDOWN_NEAR} 格內時定身累積更多 x%，越遠越少，{@value #LOCKDOWN_FAR} 格以外不變。{@code HitEffects}、{@code SupportMechanics}。 */
    public static final String PIN_LOCKDOWN = "pin.lockdown";
    public static final double LOCKDOWN_NEAR = 2;
    public static final double LOCKDOWN_FAR = 12;

    /** 遠釘的定身累積倍率：2 格內 1 + x ÷ 100，線性降到 12 格時 1（x ≤ 0 或距離不明時 1）。純函式（測試用）。 */
    public static double lockdownMultiplier(final double percent, final double distance) {
        if (percent <= 0 || Double.isNaN(distance) || Double.isInfinite(distance)) {
            return 1.0;
        }
        final double closeness = Math.max(0, Math.min(1, (LOCKDOWN_FAR - distance) / (LOCKDOWN_FAR - LOCKDOWN_NEAR)));
        return 1 + percent / 100.0 * closeness;
    }

    // ---- 脆晶（Icicle：support_icicle_ice_crystal_maximum_life_+%_final −50）----

    /** MORE：冰晶的最大生命（有生命的冰晶直接乘；照「打幾下」算的冰晶，要打的下數照比例、無條件進位）。{@code IceCrystals}。 */
    public static final String CRYSTAL_LIFE = "crystal.life";

    /** 照「打幾下」算的冰晶，生命乘 lifeMore 之後要打幾下（無條件進位，至少 1）。純函式（測試用）。 */
    public static int crystalHits(final int hits, final double lifeMore) {
        return Math.max(1, (int) Math.ceil(hits * Math.max(0, lifeMore) - 1e-9));
    }

    // ---- 穩擊（Dazzle）----

    /** FLAG：擊中時用掉敵人身上的眩暈（不管是誰給的）。{@code SupportProcs}。 */
    public static final String FLAG_CONSUME_DAZE = "flag.consume_daze";
    /** FLAG：不會造成眩暈（擊中的眩暈機率不算）。{@code HitEffects}。 */
    public static final String FLAG_CANNOT_DAZE = "flag.cannot_daze";

    // ---- 蓄嘯（Premeditation：被輔助的戰吼強化的技能更多 15% 傷害）----

    /** FLAT（%）：被這個戰吼強化的攻擊更多 x% 傷害。{@code Warcries.empoweredExtra}。 */
    public static final String WARCRY_EMPOWERED_MORE = "warcry.empowered_more";
}
