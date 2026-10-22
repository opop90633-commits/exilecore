package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.Locale;

/**
 * 主要寶石（2026-10，照 PoE2 的 Meta Gem）：skills.yml 裡寫了 {@code meta:} 區塊的技能。
 * 它本身不打人，而是「用放在它第 1 孔裡的法術」（2026-10-22；以前是面板上它後面那一格；PoE2 是把技能寶石插進主要寶石）。
 * <ul>
 *   <li>{@code totem}：法術圖騰——要放的時候消耗 3 顆銳星或磐星，放一根用插槽法術的圖騰；每消耗一顆銳星插槽法術更多 {@link #perPower}% 傷害，
 *       每消耗一顆磐星圖騰更多 {@link #perEndurance}% 生命與持續時間；插槽法術施法速度更少 {@link #lessCastSpeed}%。不能放有冷卻的法術。</li>
 *   <li>其他（crit、ailment、dodge、damage_taken、minion_death、melee_kill）：常駐（mechanic: aura，保留精魂，綁在按鍵格就開著），
 *       條件成立時累積能量；能量到插槽法術的上限（基礎施法時間每 0.1 秒 10 點）就觸發它一次，扣掉上限。</li>
 * </ul>
 * 插槽裡的法術不能自己按（只會被觸發／由圖騰放）；被觸發的法術不花魔力。
 *
 * @param kind          哪一種
 * @param energy        條件成立一次給的基礎能量（照 {@link Kind} 的說明；乘怪物強度、乘 {@link #energyMore}）
 * @param energyMore    能量獲得量增加幾 %（隨寶石等級）
 * @param lessDamage    插槽法術更少幾 % 傷害（暴擊時施放 20）
 * @param moreDamage    插槽法術更多幾 % 傷害（近戰擊殺時施放 50）
 * @param perPower      法術圖騰：每消耗一顆銳星更多幾 % 傷害
 * @param perEndurance  法術圖騰：每消耗一顆磐星圖騰更多幾 % 生命與持續時間
 * @param lessCastSpeed 法術圖騰：插槽法術施法速度更少幾 %
 * @param totems        法術圖騰：最多幾根（隨寶石等級；整數無條件捨去）
 * @param charges       法術圖騰：放一次要消耗幾顆銳星或磐星
 */
public record MetaSpec(Kind kind, double energy, Scaled energyMore, double lessDamage, double moreDamage,
                       double perPower, double perEndurance, double lessCastSpeed, Scaled totems, int charges) {

    public enum Kind {
        /** 法術圖騰。 */
        TOTEM,
        /** 暴擊時：每次暴擊 energy × 怪物強度。 */
        CRIT,
        /** 元素異常（冰凍、感電、點燃）：冰凍給 energy × 3 × 怪物強度，感電、點燃給 energy × 怪物強度。 */
        AILMENT,
        /** 翻滾時：每次 energy。 */
        DODGE,
        /** 被敵人擊中時：每次 energy。 */
        DAMAGE_TAKEN,
        /** 召喚物死掉時：每隻 energy。 */
        MINION_DEATH,
        /** 近戰擊殺時：每隻 energy × 怪物強度。 */
        MELEE_KILL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind parse(final String where, final String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".kind：要寫 totem、crit、ailment、dodge、damage_taken、minion_death、melee_kill，卻是「"
                        + text + "」");
            }
        }

        /** 這一種的條件（說明用）。 */
        public String condition() {
            return switch (this) {
                case TOTEM -> "放下時";
                case CRIT -> "你暴擊敵人時";
                case AILMENT -> "你讓敵人冰凍、感電或點燃時";
                case DODGE -> "你翻滾時";
                case DAMAGE_TAKEN -> "你被敵人擊中時";
                case MINION_DEATH -> "你的召喚物死掉時";
                case MELEE_KILL -> "你以近戰擊殺敵人時";
            };
        }
    }

    /** 插槽法術的能量上限：基礎施法時間每 0.1 秒 10 點（照 PoE2），最少 10。 */
    public static double maxEnergy(final SkillDefinition spell) {
        return Math.max(10, Math.round(spell.castTime() * 100));
    }

    public boolean totem() {
        return kind == Kind.TOTEM;
    }

    static MetaSpec parse(final String where, final YamlNode node) {
        final Kind kind = Kind.parse(where, node.requireString("kind"));
        final double energy = node.decimal("energy", kind == Kind.TOTEM ? 0 : 10);
        if (kind != Kind.TOTEM && (energy <= 0 || energy > 1000)) {
            throw new IllegalArgumentException(where + ".energy：要在 0 到 1000 之間");
        }
        final double less = node.decimal("less_damage", 0);
        final double more = node.decimal("more_damage", 0);
        if (less < 0 || less >= 100 || more < 0 || more > 300) {
            throw new IllegalArgumentException(where + "：less_damage 要在 0 到 99、more_damage 要在 0 到 300 之間");
        }
        final int charges = node.integer("charges", kind == Kind.TOTEM ? 3 : 0);
        if (charges < 0 || charges > 10) {
            throw new IllegalArgumentException(where + ".charges：要在 0 到 10 之間");
        }
        return new MetaSpec(kind, energy, Scaled.parse(where + ".energy_more", node.raw("energy_more"), 0), less, more,
                node.decimal("per_power", 10), node.decimal("per_endurance", 20), node.decimal("less_cast_speed", 25),
                Scaled.parse(where + ".totems", node.raw("totems"), 1), charges);
    }
}
