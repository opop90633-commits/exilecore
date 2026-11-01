package tw.exilecore.skill;

/**
 * 2026-11-01 照 PoE2 Spiral Volley（螺旋箭雨，PoB2 SpiralVolleyPlayer）：
 * <ul>
 *   <li>用的時候用掉身上所有的怒星：有用掉就更多 100% 傷害（spiral_volley_damage_+%_final_when_frenzy_charges_consumed 100），
 *       每顆再更多 30%（…_per_frenzy_charge_consumed 30，同一條相加：1 + 0.3 × 顆數），每顆多連鎖 1 次（spiral_volley_X_chains_per_frenzy_charge_consumed 1）；</li>
 *   <li>6 公尺內有敵人就直接朝牠們射（active_skill_base_area_of_effect_radius 60，firing directly at targets if possible），一隻一支，剩下的照一圈散開；</li>
 *   <li>每個目標只會被打中一次（同一次施放共用「打過了」，ProjectileEngine 本來就這樣）。</li>
 * </ul>
 * 數值寫在 skills.yml 的 modifiers（FLAT）。純函式，不依賴 Bukkit。這次之前不用怒星、平均一圈散射。
 */
public final class SpiralVolley {

    /** FLAT：有用掉怒星時更多 x% 傷害（0 = 這個技能不用怒星）。 */
    public static final String CONSUMED_MORE = "frenzy.consumed_more";
    /** FLAT：每用掉一顆怒星再更多 x%。 */
    public static final String CONSUMED_MORE_PER = "frenzy.consumed_more_per";
    /** FLAT：每用掉一顆怒星多連鎖幾次。 */
    public static final String CONSUMED_CHAINS = "frenzy.consumed_chains";
    /** FLAT：幾格內有敵人就直接朝牠射（0 = 不瞄準）。 */
    public static final String TARGET_RADIUS = "volley.target_radius";
    /** 一次最多用掉幾顆（保險；戰星上限遠低於這個）。 */
    static final int MAX_CONSUMED = 20;

    private SpiralVolley() {
    }

    /** 用掉 consumed 顆怒星的傷害倍率：沒用掉 1；有的話 (1 + more%) × (1 + per% × 顆數)。 */
    public static double damageMultiplier(final int consumed, final double morePercent, final double perPercent) {
        if (consumed <= 0) {
            return 1.0;
        }
        return Math.max(0, 1 + morePercent / 100.0) * Math.max(0, 1 + perPercent / 100.0 * consumed);
    }

    /** 用掉 consumed 顆怒星多連鎖幾次（每顆 perCharge 次，捨去）。 */
    public static int extraChains(final int consumed, final double perCharge) {
        return consumed <= 0 || perCharge <= 0 ? 0 : (int) Math.floor(consumed * perCharge + 1e-9);
    }

    /** 這一輪有幾支直接朝敵人射：一隻一支，最多全部的箭。 */
    public static int aimed(final int arrows, final int targetsInRange) {
        return Math.max(0, Math.min(arrows, targetsInRange));
    }
}
