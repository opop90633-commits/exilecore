package tw.exilecore.stats;

/**
 * 2026-10-27 照 PoE2：戰吼品質效果（gem_quality.yml）與裂甲熊咆插槽用的屬性鍵（放在這裡，不動 {@link StatKeys}）。
 * 都是 FLAT，寫在戰吼技能自己的修飾器（品質）裡，吼的時候（Warcries）讀。
 */
public final class WarcryKeys {

    private WarcryKeys() {
    }

    /** FLAT：戰吼半徑 +x 格（加在基礎半徑上、再乘範圍倍率；焚獄戰吼的品質）。 */
    public static final String RADIUS_FLAT = "warcry.radius_flat";

    /** FLAT：被這個戰吼強化的攻擊另外獲得 x% 傷害為額外火焰傷害（焚獄戰吼的品質）。 */
    public static final String EMPOWER_GAIN_FIRE = "warcry.empower_gain_fire";

    /** FLAT：被這個戰吼強化的猛擊，餘震更多 x% 傷害（震地戰吼的品質）。 */
    public static final String AFTERSHOCK_MORE = "warcry.aftershock_more";

    /** FLAT：獲得的守護更多 x%（堅壁戰吼的品質）。 */
    public static final String GUARD_MORE = "warcry.guard_more";

    /** FLAT：被強化的攻擊觸發的隱藏技能（盾波）半徑 +x 格（堅壁戰吼的品質）。 */
    public static final String TRIGGER_RADIUS_FLAT = "warcry.trigger_radius_flat";

    /** FLAT：每用掉一顆磐星，持續時間另外增加 x%（熔魂嘯的品質）。 */
    public static final String DURATION_PER_ENDURANCE = "warcry.duration_per_endurance";

    /** FLAT：給隊友的增益另外增加 x% 凍結累積（寒夜嗥的品質）。 */
    public static final String ALLY_FREEZE_BUILDUP = "warcry.ally_freeze_buildup";

    /** FLAT：每 1 點強度多獲得 x 點怒氣（裂甲熊咆的品質）。 */
    public static final String RAGE_PER_POWER = "warcry.rage_per_power";

    /** FLAT：被這個戰吼強化的攻擊增加 x% 傷害（裂甲熊咆插槽的戰吼：Attacks Empowered by Socketed Skills deal x% increased Damage）。 */
    public static final String EMPOWERED_DAMAGE = "warcry.empowered_damage";

    /**
     * 裂甲熊咆自己的品質裡「只給插槽戰吼」的兩條（熊咆本身不用；SocketedWarcry 觸發時換成上面的 EMPOWERED_DAMAGE 與冷卻回復速度）。
     * FLAT：被插槽戰吼強化的攻擊增加 x% 傷害。
     */
    public static final String SOCKETED_EMPOWERED_DAMAGE = "socketed_warcry.empowered_damage";

    /** INCREASED：插槽戰吼的冷卻回復速度。 */
    public static final String SOCKETED_COOLDOWN_RECOVERY = "socketed_warcry.cooldown_recovery";

    /** FLAT 1：這個戰吼的孔可以放人形戰吼的技能寶石（裂甲熊咆）。 */
    public static final String FLAG_SOCKETS_WARCRY = "flag.socketed_warcry";
}
