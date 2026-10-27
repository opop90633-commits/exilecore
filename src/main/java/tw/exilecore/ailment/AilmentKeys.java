package tw.exilecore.ailment;

/**
 * 2026-10-26 照 PoE2 0.5 的異常狀態新增的屬性鍵（不放進 StatKeys，方便合併）。
 */
public final class AilmentKeys {

    /** FLAT：敵人身上你的中毒可以多幾層同時生效（PoE2 預設 1 層；輔助、天賦另外加）。 */
    public static final String POISON_STACKS = "poison.stacks";
    /** MORE／INCREASED（防禦方）：身上受到的暈眩累積（眩暈：更多 50%）。 */
    public static final String STUN_BUILDUP_TAKEN = "stun.buildup_taken";
    /** MORE（防禦方）：身上的曝露效果（PoE2 稀有度：魔法 −15%、稀有 −30%、傳奇 −50%）。 */
    public static final String EXPOSURE_EFFECT_ON_SELF = "exposure.effect_on_self";

    private AilmentKeys() {
    }
}
