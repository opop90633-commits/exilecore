package tw.exilecore.stats;

/**
 * 2026-10-27 照 PoE2 0.5：塗油限定的核心（天賦樹上沒有、只能注入在項鍊上；tree.json 的 anoint，配方在 data/passives/anoint_only.yml）
 * 用到的新屬性鍵。機制寫在 {@code tw.exilecore.skill.TreeMechanics}、{@code TreeLeftovers} 與幾個共用的地方（每個鍵寫消費的地方）；
 * 不放進 {@link StatKeys}，方便合併。純邏輯的換算也放這裡（不依賴 Bukkit，可以直接測）。
 */
public final class AnointKeys {

    private AnointKeys() {
    }

    /** 旗標：使用戰吼後，親自施放的下一個火焰法術先祖強化（岡姆之祝）。TreeMechanics 的 onUse 記下、beforeAttack 用掉。 */
    public static final String FLAG_WARCRY_FIRE_SPELL_ANCESTRAL = "flag.warcry_fire_spell_ancestral";
    /** FLAT %：每層易爆增加的持續混沌傷害（危境增長；持續混沌傷害照中毒的強度算）。TreeMechanics 每 5 tick 照層數更新增益。 */
    public static final String VOLATILITY_CHAOS_DOT = "volatility.chaos_dot_per";
    /** FLAT %：使用指令技能時，等著復活的常駐召喚物馬上復活的機率（群集復甦）。TreeMechanics 的 onUse 擲、AuraService.reviveRandom 復活。 */
    public static final String COMMAND_REVIVE_CHANCE = "command.revive_minion_chance";
    /** FLAT（秒）：召喚物復活時給牠元素統治者幾秒（狂颮之主）。TreeLeftovers.minionRevived。 */
    public static final String MINION_REVIVE_ARCHON = "minion.revive_elemental_archon";
    /** FLAT（公尺）：舉盾時正前方幾公尺內的敵人致盲（光明壁壘）。TreeLeftovers 每 10 tick。 */
    public static final String SHIELD_BLIND = "shield.blind_range";
    /** FLAT（秒）：舉盾擋下擊中時，讓攻擊者被招架幾秒（光明壁壘）。TreeMechanics.onBlock。 */
    public static final String RAISE_SHIELD_PARRIED = "shield.raise_parried";
    /** FLAT %：站在你的冰緩地面上的敵人受到的火焰傷害增加（沙漠之蔑）。SkillZones 每一波呼叫 TreeLeftovers.scornGround。 */
    public static final String CHILLED_GROUND_FIRE_TAKEN = "ground.chilled_fire_taken";
    /** FLAT %：站在你的點燃地面上的敵人受到的冰冷傷害增加（沙漠之蔑）。同上。 */
    public static final String IGNITED_GROUND_COLD_TAKEN = "ground.ignited_cold_taken";
    /** FLAT %：每個開著但空著的護符欄，你身上的護符效果增加 x%（巨著）。CharmService 觸發護符時算。 */
    public static final String CHARM_EFFECT_PER_EMPTY = "charm.effect_per_empty_slot";

    /** 危境增長：易爆 stacks 層時中毒的強度增加多少（小數）。 */
    public static double perilPoison(final double perStack, final int stacks) {
        return perStack <= 0 || stacks <= 0 ? 0 : perStack * stacks / 100.0;
    }

    /** 巨著：開著 open 格、裝了 filled 格時護符效果增加多少（小數；空格 × 每格 %）。 */
    public static double emptyCharmEffect(final double perEmpty, final int open, final int filled) {
        final int empty = Math.max(0, open - Math.max(0, filled));
        return perEmpty <= 0 ? 0 : perEmpty * empty / 100.0;
    }
}
