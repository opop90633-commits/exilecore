package tw.exilecore.stats;

/**
 * 2026-10-28 照 PoE2 Artillery Ballista（落釘弩台）：定身（Pin）累積的技能屬性。
 * 這次之前落釘弩台用 pin.chance 30 頂著（只是「有定身來源」），累積照一般物理傷害算。
 */
public final class PinKeys {

    private PinKeys() {
    }

    /** FLAG：擊中必定累積定身（Hits are Pinning：所有傷害類型都算進定身累積，不只物理）。{@code HitEffects}。 */
    public static final String FLAG_HITS_PIN = "flag.hits_pin";

    /**
     * FLAT（%）：定身累積當作傷害更多 x%（PoE2 active_skill_pins_as_though_dealt_damage_+%_final：落釘弩台 600，品質再 +0～200，
     * 同一條加起來再乘：600 → ×7）。{@code HitEffects}。
     */
    public static final String PIN_AS_THOUGH = "pin.as_though_damage";

    /** 定身累積要乘的倍率：1 + x ÷ 100（x 是 {@link #PIN_AS_THOUGH} 的總和；負的當 0）。 */
    public static double asThoughMultiplier(final double percent) {
        return 1 + Math.max(0, percent) / 100.0;
    }

    /** 拿來累積定身的傷害：擊中必定定身時全部傷害都算，否則只算物理。 */
    public static double pinDamage(final boolean allDamage, final DamagePacket outgoing) {
        return allDamage ? outgoing.total() : outgoing.get(DamageType.PHYSICAL);
    }
}
