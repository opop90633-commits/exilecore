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

    /**
     * 2026-11-01 照 PoE2 Bone Chains（骸骨連鎖，physical_spell_damage_can_pin_on_critical_hit；PoB2 ModParser：
     * {@code flag("CanPin", nil, ModFlag.Spell, KeywordFlag.Physical, { type = "Condition", var = "CriticalStrike" })}）：
     * 帶物理標籤的法術（技能類型 Physical，不是看這一下剩多少物理）的暴擊會累積定身。CanPin 沒有限定傷害類型，
     * 所以這一下的所有傷害（含物理轉成的元素）都算進累積（跟擊中必定定身一樣）。
     */
    public static boolean physicalSpell(final java.util.Set<String> tags) {
        return tags != null && tags.contains(Tags.SPELL) && tags.contains(Tags.PHYSICAL);
    }

    /** 這一下用什麼規則累積定身（{@code HitEffects} 的同一個入口；面板 {@code AilmentText} 也照這個列）。 */
    public enum Source {
        /** 不會累積定身。 */
        NONE,
        /** 投射物攻擊（有 pin.chance 來源）：只算物理。 */
        PROJECTILE_ATTACK,
        /** 骸骨連鎖：物理法術的暴擊才累積，所有傷害都算。 */
        SPELL_CRIT,
        /** 擊中必定定身（{@link #FLAG_HITS_PIN}）：每一下都累積，所有傷害都算。 */
        EVERY_HIT;

        /** 所有傷害類型都算（不是只算物理）。 */
        public boolean allDamage() {
            return this == SPELL_CRIT || this == EVERY_HIT;
        }

        /** 這一下（是不是暴擊）會不會累積。 */
        public boolean builds(final boolean crit) {
            return this == PROJECTILE_ATTACK || this == EVERY_HIT || this == SPELL_CRIT && crit;
        }
    }

    /**
     * 這個技能的定身來源（多個來源不疊加：PoB2 都是同一個 CanPin 旗標，只決定「能不能」與「哪些傷害算」）。
     *
     * @param hitsPin        {@link #FLAG_HITS_PIN}
     * @param spellCritPin   骸骨連鎖（{@code TreeNewMechanicKeys.FLAG_SPELL_CRIT_PIN}，只算你自己打的）
     * @param pinChance      pin.chance（投射物攻擊的定身來源）
     * @param cannotPin      不能定身（骨裂；PoB2 CannotPin 讓累積歸零，蓋過所有來源）
     */
    public static Source source(final boolean hitsPin, final boolean spellCritPin, final double pinChance, final java.util.Set<String> tags,
                                final boolean cannotPin) {
        if (cannotPin || tags == null) {
            return Source.NONE;
        }
        if (hitsPin) {
            return Source.EVERY_HIT;
        }
        if (spellCritPin && physicalSpell(tags)) {
            return Source.SPELL_CRIT;
        }
        if (pinChance > 0 && tags.contains(Tags.ATTACK) && tags.contains(Tags.PROJECTILE)) {
            return Source.PROJECTILE_ATTACK;
        }
        return Source.NONE;
    }
}
