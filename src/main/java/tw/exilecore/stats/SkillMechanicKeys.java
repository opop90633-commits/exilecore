package tw.exilecore.stats;

/**
 * 2026-10-26 照 PoE2 把技能的「平衡等效」換回原作機制時新加的屬性代號（不放進 StatKeys，方便合併）。
 */
public final class SkillMechanicKeys {

    private SkillMechanicKeys() {
    }

    /**
     * FLAT：擊中時讓敵人受到的混沌持續傷害，每秒的基礎傷害（PoE2 汲魂彈的 base_chaos_damage_to_deal_per_minute ÷ 60）。
     * 吃法術、混沌、一般傷害的增加與更多（「法術傷害的加成也套用在持續傷害上」）；同一個技能的不疊加，再打中就刷新。
     * 這次之前汲魂彈用 debuff.chaos 換算成「每秒是這一下傷害的 x%」。
     */
    public static final String DEBUFF_CHAOS_BASE = "debuff.chaos_base";

    /**
     * FLAT：掛著會傳染的減益（flag.debuff_spread，瘟疫）的敵人死掉時傳染的半徑（格），乘這個技能的範圍加成（√ 增加／更多範圍）。
     * 2026-10-28 照 PoE2 Contagion（Spread radius 1.7 公尺）；這次之前固定 4 格。
     */
    public static final String DEBUFF_SPREAD_RADIUS = "debuff.spread_radius";

    /** FLAG：這個技能留下的地面效果不打人，每一跳像打中一樣讓裡面的敵人中毒（蝕毒箭、瘴氣榴彈的毒霧）。 */
    public static final String FLAG_GROUND_POISON_ONLY = "flag.ground_poison_only";

    /** FLAG：這個技能的投射物爆炸不打人，像打中一樣讓範圍內的敵人中毒（爆毒矢的毒爆）。 */
    public static final String FLAG_BURST_POISON_ONLY = "flag.burst_poison_only";

    /** FLAG：這個技能留下的地面（火油）要被你的火焰點燃才會燒；點燃之前只讓敵人緩速（火油榴彈）。 */
    public static final String FLAG_GROUND_NEEDS_IGNITE = "flag.ground_needs_ignite";

    /** FLAG：法術投射物照傷害種類分開射（每一種一發、各自打中；纏蝕箭的物理、混沌兩發）。 */
    public static final String FLAG_SPLIT_TYPES = "flag.split_damage_types";

    /** FLAT：有引信的投射物每次落地都炸一圈再往前彈，總共彈幾次（熔球 3／5／6 次；最後一次落地炸完就沒了）。 */
    public static final String PROJECTILE_HOPS = "projectile.hops";
}
