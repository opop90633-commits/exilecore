package tw.exilecore.stats;

/**
 * 2026-10-26 照 PoE2：圖騰新加的屬性鍵（放在這裡，不動 {@link StatKeys}）。
 */
public final class TotemKeys {

    private TotemKeys() {
    }

    /** 先祖魂約（PoE2 Ancestral Bond）：每個圖騰保留的精魂。 */
    public static final double ANCESTRAL_BOND_SPIRIT = 75;

    /** FLAT 1：圖騰只在你發射攻擊投射物時使用技能（PoE2 昇華「砲彈準備好了！」的代價）。 */
    public static final String FLAG_ON_ATTACK_PROJECTILE = "flag.totem_on_attack_projectile";

    /** FLAT：擊中的傷害有 x% 先由你最近的圖騰的生命承受（PoE2 昇華「堅木之壘」）。 */
    public static final String TAKES_DAMAGE = "totem.takes_damage";

    /** FLAT：圖騰每秒回復 x% 最大生命（PoE2 天賦 Totems Regenerate x% of maximum Life per second）。 */
    public static final String REGEN = "totem.regen";

    /** 2026-10-26 照 PoE2：FLAT：圖騰持續時間 +x 秒（加在基礎持續時間上、再乘持續時間倍率；星咒圖騰、英靈圖騰、崩震圖騰的品質）。 */
    public static final String DURATION_FLAT = "totem.duration_flat";

    /** 2026-10-26 照 PoE2 Spell Totem 的品質：FLAT：你每有一根召喚中的圖騰，圖騰施放的法術增加 x% 施法速度。 */
    public static final String CAST_SPEED_PER_TOTEM = "totem.cast_speed_per_totem";

    /** 2026-10-26 照 PoE2 Shockwave Totem：FLAT：圖騰的猛擊打到你的碎地時引爆，引爆的傷害效益 x%（0 = 不引爆）。 */
    public static final String JAGGED_ERUPTION = "totem.jagged_eruption";

    /** 碎地引爆的半徑（格；PoE2 3 格，乘技能的範圍倍率）。 */
    public static final double JAGGED_ERUPTION_RADIUS = 3.0;
}
