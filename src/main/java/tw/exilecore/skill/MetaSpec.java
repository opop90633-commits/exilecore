package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

import java.util.Locale;

/**
 * 主要寶石（2026-10，照 PoE2 的 Meta Gem）：skills.yml 裡寫了 {@code meta:} 區塊的技能。
 * 它本身不打人，而是「用插在它裡面的所有法術」（2026-10-22；以前是面板上它後面那一格；PoE2 是把技能寶石插進主要寶石；
 * 2026-10-26／27 照 PoE2：每一孔都能放，引星全部一起觸發、星咒圖騰輪流放）。
 * <ul>
 *   <li>{@code totem}：法術圖騰——要放的時候消耗 3 顆銳星或磐星，放一根用插槽法術的圖騰；每消耗一顆銳星插槽法術更多 {@link #perPower}% 傷害，
 *       每消耗一顆磐星圖騰更多 {@link #perEndurance}% 生命與持續時間；插槽法術施法速度更少 {@link #lessCastSpeed}%。不能放有冷卻的法術。</li>
 *   <li>其他（crit、ailment、dodge、barrier、minion_death、melee_kill、spell_cast）：常駐（mechanic: aura，保留精魂，綁在按鍵格就開著），
 *       條件成立時累積能量；能量到上限（插槽法術的基礎施法時間每 0.1 秒 10 點，全部加起來）就觸發所有插槽法術、失去全部能量
 *       （2026-10-26 照 PoE2；這次之前只觸發第 1 孔、扣掉上限、多的留著）。</li>
 *   <li>2026-10-26 照 PoE2 的 Invocation（祈喚）：寫了 {@code max_energy}（原作 500）的不會自動觸發，能量存到這個上限為止；
 *       按它的按鍵（冷卻 0.2 秒）才把能量花掉，每觸發一次插槽法術花它的上限那麼多（基礎施法時間每 0.1 秒 10 點），能量夠就連續觸發好幾次。</li>
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
 * @param maxEnergy     2026-10-26：Invocation 類的能量上限（PoE2 的 generic_ongoing_trigger_maximum_energy 500）；0 = 不是 Invocation（滿了自動觸發）
 */
public record MetaSpec(Kind kind, double energy, Scaled energyMore, double lessDamage, double moreDamage,
                       double perPower, double perEndurance, double lessCastSpeed, Scaled totems, int charges, double maxEnergy) {

    public MetaSpec(final Kind kind, final double energy, final Scaled energyMore, final double lessDamage, final double moreDamage,
                    final double perPower, final double perEndurance, final double lessCastSpeed, final Scaled totems, final int charges) {
        this(kind, energy, energyMore, lessDamage, moreDamage, perPower, perEndurance, lessCastSpeed, totems, charges, 0);
    }

    /** 2026-10-26 照 PoE2：Invocation（祈喚）——能量存起來，按下去才花掉觸發插槽法術。 */
    public boolean invocation() {
        return maxEnergy > 0;
    }

    public enum Kind {
        /** 法術圖騰。 */
        TOTEM,
        /**
         * 暴擊時：energy × 怪物強度 × 這一下打掉牠元素異常門檻的百分比（2026-10-26 照 PoE2 Cast on Critical：
         * 「每點強度 1 能量，再照暴擊造成牠異常門檻的百分比調整」；百分比最多算 100）。這次之前固定 10 × 怪物強度。
         */
        CRIT,
        /**
         * 元素異常（冰凍、感電、點燃）：冰凍給 energy × 10 × 怪物強度，感電給 energy × 怪物強度，
         * 點燃給 energy × 怪物強度 × 這個點燃會造成牠異常門檻的 %（2026-10-26 照 PoE2，最多 100；這次之前點燃跟感電一樣）。
         */
        AILMENT,
        /** 翻滾時：翻滾每 1 公尺給 energy（2026-10-26 照 PoE2：每公尺 2；一次翻滾約 3.5 公尺）。這次之前每次固定 12。 */
        DODGE,
        /**
         * 2026-10-26 照 PoE2 Barrier Invocation：你的能量護盾被敵人擊中扣掉時，每扣 1 點給 energy（原作每 10 點 1 能量，寫 0.1）。
         * 這次之前是受創引星的「被擊中時每次 30」（原作 0.5 已經沒有 Cast when Damage Taken）。
         */
        BARRIER,
        /** 持續型召喚物死掉時：每隻 energy × 召喚物強度（2026-10-26 照 PoE2：有持續時間的不算；強度＝相對生命，等級太低打折，見 SkillTriggers.minionPower）。 */
        MINION_DEATH,
        /** 近戰擊殺時：每隻 energy × 怪物強度（2026-10-26 照 PoE2 Reaper's Invocation：每點強度 30）。 */
        MELEE_KILL,
        /** 2026-10-26（PoE2 的 Spellslinger）：你自己施放法術時：energy × 那個法術的基礎施法時間（每 0.1 秒）。 */
        SPELL_CAST,
        /** 2026-10-26（昇華・熔爐熱力，PoE2 Fire Spell on Melee Hit）：近戰攻擊擊中時：energy × 怪物強度；只能插火焰法術。 */
        MELEE_HIT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind parse(final String where, final String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".kind：要寫 totem、crit、ailment、dodge、barrier、minion_death、melee_kill、spell_cast、melee_hit，卻是「"
                        + text + "」");
            }
        }

        /** 這一種的條件（說明用）。 */
        public String condition() {
            return switch (this) {
                case TOTEM -> "放下時";
                case CRIT -> "你暴擊敵人時（照這一下打掉牠異常門檻的比例）";
                case AILMENT -> "你讓敵人冰凍、感電或點燃時";
                case DODGE -> "你翻滾時（每公尺）";
                case BARRIER -> "你的能量護盾被敵人擊中扣掉時";
                case MINION_DEATH -> "你的持續型召喚物死掉時（乘牠的強度）";
                case MELEE_KILL -> "你以近戰擊殺敵人時";
                case SPELL_CAST -> "你施放法術時（每 0.1 秒基礎施法時間）";
                case MELEE_HIT -> "你用近戰攻擊擊中敵人時";
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
        final double maxEnergy = node.decimal("max_energy", 0); // 2026-10-26：Invocation 類
        if (maxEnergy < 0 || maxEnergy > 10000 || kind == Kind.TOTEM && maxEnergy > 0) {
            throw new IllegalArgumentException(where + ".max_energy：要在 0 到 10000 之間（法術圖騰不能寫）");
        }
        return new MetaSpec(kind, energy, Scaled.parse(where + ".energy_more", node.raw("energy_more"), 0), less, more,
                node.decimal("per_power", 10), node.decimal("per_endurance", 20), node.decimal("less_cast_speed", 25),
                Scaled.parse(where + ".totems", node.raw("totems"), 1), charges, maxEnergy);
    }
}
