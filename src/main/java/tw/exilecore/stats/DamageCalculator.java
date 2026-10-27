package tw.exilecore.stats;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 傷害計算流程（設計文件「數值引擎」一節）：
 * <ol>
 *   <li>攻擊擲命中（法術必中）、防禦方擲格擋</li>
 *   <li>基礎傷害：攻擊用武器各類型傷害 + 附加傷害，乘技能效益；法術用技能自己的基礎傷害 + 附加；物理再依技能轉換成其他類型</li>
 *   <li>每個類型各自套用攻擊方的增加／更多（只取標籤符合的）</li>
 *   <li>擲暴擊，全部乘暴擊倍率</li>
 *   <li>防禦方減免：物理看護甲、元素與混沌看抗性</li>
 *   <li>先扣能量護盾再扣生命，混沌對護盾兩倍（PoE2）</li>
 * </ol>
 * 純函式，不依賴 Bukkit。
 */
public final class DamageCalculator {

    /** 帶這個標籤的擊中不能被格擋或招架（= DodgeRoll.UNDODGEABLE_TAG，Boss 全場大招）。 */
    static final String UNBLOCKABLE_TAG = "undodgeable";

    /** 法術沒有武器時的基礎暴擊率（百分比點數）。 */
    public static final double BASE_SPELL_CRIT = 5.0;
    /** 2026-10-27 照 PoE2：不用武器的攻擊（血爆、嗅血爆：crit_chance 500）的基礎暴擊率（%）。 */
    public static final double WEAPONLESS_BASE_CRIT = 5.0;
    /**
     * 2026-10-27 照 PoE2（NonWeaponAttack）：不用武器的攻擊的標籤（SkillProfiles 給 skills.yml 裡寫了 damage 的攻擊加上）：
     * 基礎傷害用技能自己的表（SkillProfile.spellDamage）取代武器傷害、暴擊率用 {@link #WEAPONLESS_BASE_CRIT}。
     */
    public static final String WEAPONLESS_TAG = "weaponless";

    /** 這一下是不是不用武器的攻擊（有 {@link #WEAPONLESS_TAG}、是攻擊、有自己的基礎傷害）。 */
    public static boolean weaponless(final SkillProfile skill) {
        return skill.isAttack() && skill.spellDamage() != null && skill.tags().contains(WEAPONLESS_TAG);
    }
    /** 混沌傷害打在能量護盾上的倍率（PoE2：2 倍）。 */
    public static final double CHAOS_ES_MULTIPLIER = 2.0;

    /**
     * 2026-10-27 照 PoE2：玩家這一邊（玩家、玩家的召喚物）的屬性表帶這個旗標（基礎值 1）。
     * 被怪物打時用怪物的命中公式（{@link Formulas#monsterHitChance}）、物理減免上限 90%；沒有的（怪物）用玩家打怪的命中公式、物理減免上限 75%。
     */
    public static final String FLAG_PLAYER_SIDE = "flag.player_side";

    /** 這張表是玩家這一邊的（玩家、召喚物）。 */
    public static boolean playerSide(final StatSheet sheet) {
        return StatKeys.flag(sheet, FLAG_PLAYER_SIDE);
    }

    /** 打這個防禦方的命中率：防禦方是玩家這一邊用怪物的公式，不然用玩家打怪的公式（2026-10-27 照 PoE2；這次之前都用後者）。 */
    public static double hitChanceAgainst(final StatSheet defender, final double accuracy, final double evasion) {
        return playerSide(defender) ? Formulas.monsterHitChance(accuracy, evasion) : Formulas.hitChance(accuracy, evasion);
    }

    /** 格擋上限（百分比點數）：基礎 50 ＋ 格擋上限加成，最多 90（2026-10-27 照 PoE2；這次之前沒有 90 的硬上限）。 */
    public static double blockCap(final StatSheet sheet) {
        return Formulas.blockCap(sheet.flat(StatKeys.BLOCK_MAX, Set.of()));
    }

    private DamageCalculator() {
    }

    public static HitResult calculate(final StatSheet attackerSheet, final SkillProfile skill,
                                      final StatSheet defender, final double defenderCurrentEs,
                                      final RandomGenerator random) {
        return calculate(attackerSheet, skill, defender, defenderCurrentEs, 1.0, random);
    }

    /**
     * 同上，再乘防禦方「受到的傷害」倍率（感電：1.2 = 受到的傷害增加 20%），在減免之後、扣護盾與生命之前。
     * 結果的 {@link HitResult#dealt()} 已經乘過這個倍率。
     */
    public static HitResult calculate(final StatSheet attackerSheet, final SkillProfile skill,
                                      final StatSheet defender, final double defenderCurrentEs,
                                      final double damageTakenMultiplier, final RandomGenerator random) {
        return calculate(attackerSheet, skill, defender, defenderCurrentEs, damageTakenMultiplier, random, null, skill.isAttack());
    }

    /** 命中判定：給命中率（0～1），回傳這一下有沒有打中。PoE2 的「熵」見 {@link Entropy}。 */
    @FunctionalInterface
    public interface HitRoll {
        boolean hits(double chance);
    }

    /**
     * 同上，指定命中判定（null = 擲骰）與這一下能不能被閃避。2026-10 PoE2：攻擊與法術的擊中都能被閃避
     * （Boss 的大招等「無法閃避」的除外，由呼叫端決定 evadable）。格擋只擋攻擊。
     */
    public static HitResult calculate(final StatSheet attackerSheet, final SkillProfile skill,
                                      final StatSheet defender, final double defenderCurrentEs,
                                      final double damageTakenMultiplier, final RandomGenerator random,
                                      final HitRoll roll, final boolean evadable) {
        // 技能的輔助寶石疊在攻擊方的屬性表上（只對這一下有效）
        final StatSheet attacker = chargeScaling(skill.effective(attackerSheet), skill.tags());
        // 1. 命中與格擋（基石「必中」跳過命中擲骰）
        // 2026-10-26 照 PoE2 天賦樹：「必中」可以帶標籤（「你的擊中不會被釘住的敵人閃避」＝ vs_immobilised）；這次之前只看無標籤的
        if (evadable && !(attacker.flat(StatKeys.FLAG_ALWAYS_HIT, skill.tags()) > 0)) {
            // 2026-10-26 照 PoE2 天賦樹：命中帶這一下的情境（技能標籤＋目標狀態：對稀有、傳奇，對流血的，近距離）；這次之前只看無標籤的
            // 2026-10-27 照 PoE2：怪物打玩家用怪物的命中公式（hitChanceAgainst）
            double chance = hitChanceAgainst(defender, attacker.get(StatKeys.ACCURACY, skill.tags()), defender.get(StatKeys.EVASION));
            if (StatKeys.flag(defender, StatKeys.FLAG_STAY_LIGHT)) {
                chance = Math.max(chance, 0.5); // 戰術家：最多閃 50%
            }
            chance = tw.exilecore.item.unique.UniqueHits.evadeCap(defender, chance); // 2026-10-27（警望指環）：閃避率最多 x%
            if (StatKeys.flag(defender, KeystoneKeys.FLAG_GLANCING)) {
                chance = KeystoneKeys.unluckyEvadeHitChance(chance); // 2026-10-26 側身之擊（PoE2 Glancing Blows）：閃避機率為不幸
            }
            final boolean hit = chance >= 1.0 || (roll != null ? roll.hits(chance) : random.nextDouble() < chance);
            if (!hit) {
                return HitResult.miss();
            }
        }
        double blockedScale = 1.0;
        // 2026-10-24：Boss 全場大招（翻滾躲不掉、要躲安全區）也不能招架或格擋
        final boolean blockable = skill.isAttack() && !skill.tags().contains(UNBLOCKABLE_TAG);
        if (blockable && StatKeys.flag(defender, StatKeys.FLAG_PARRY)) {
            return HitResult.block(); // 2026-10-24（招架，PoE2 Parry）：舉盾中的那一擊一定擋下（不吃格擋上限、龜殼護符）
        }
        if (blockable) {
            final double cap = blockCap(defender); // 2026-10-27：最多 90%（PoE2 硬上限）
            final double block = Math.min(cap, defender.get(StatKeys.BLOCK_CHANCE)) / 100.0; // 上限 50%（PoE2），昇華可以提高
            if (block > 0 && random.nextDouble() < block) {
                final double taken = defender.flat(StatKeys.BLOCK_TAKEN, Set.of());
                if (taken <= 0) {
                    return HitResult.block();
                }
                blockedScale = Math.min(1.0, taken / 100.0); // 龜殼護符：被格擋的擊中仍受到一部分傷害
            }
        }

        // 偏斜（2026-10 PoE2）：沒閃掉的擊中再照命中對偏斜值擲一次，偏斜的傷害更少 40%
        boolean deflected = false;
        if (evadable) {
            final double deflection = deflectionRating(defender);
            if (deflection > 0) {
                double deflectChance = deflectChance(attacker.get(StatKeys.ACCURACY, skill.tags()), deflection); // 2026-10-26：同上，帶情境
                if (StatKeys.flag(defender, KeystoneKeys.FLAG_GLANCING)) {
                    deflectChance = KeystoneKeys.luckyChance(deflectChance); // 2026-10-26 側身之擊：偏斜機率為幸運
                }
                deflected = random.nextDouble() < deflectChance;
            }
        }

        // 2 + 3. 各類型基礎傷害（含轉換）與增加／更多
        // 2026-10-26（貫天矛）：每一種傷害只擲出最小值或最大值
        final boolean extreme = StatKeys.flag(attacker, tw.exilecore.item.unique.UniqueStats.EXTREME_ROLLS);
        // 2026-10-26 照 PoE2 天賦樹：擊中你的傷害為不幸（擲兩次取低）的機率（防禦方）；幸運（攻擊方，照類型）在 outgoing 裡擲
        final double unluckyTaken = StatKeys.flag(defender, tw.exilecore.ascendancy.AscKeys.LESSER_HARM) ? 1 // 2026-10-26（昇華・微小損害）：一定不幸
                : Math.max(0, defender.flat(KeystoneKeys.UNLUCKY_TAKEN, Set.of())) / 100.0;
        final boolean unlucky = unluckyTaken > 0 && random.nextDouble() < unluckyTaken;
        final Roller once = (min, max) -> min >= max ? max
                : extreme ? (random.nextBoolean() ? min : max) : min + random.nextDouble() * (max - min);
        // 2026-10-27 照 PoE2 極雷（Esh's Prowess）：只有閃電傷害擲最小值或最大值
        final Roller extremes = (min, max) -> min >= max ? max : random.nextBoolean() ? min : max;
        final Roller roller = unlucky ? (min, max) -> Math.min(once.roll(min, max), once.roll(min, max)) : once;
        final DamagePacket outgoing = StatKeys.flag(attacker, LineageKeys.LIGHTNING_EXTREMES)
                ? outgoing(attacker, skill, roller, extremes) : outgoing(attacker, skill, roller);

        // 4. 暴擊
        final Set<String> critContext = skill.tags();
        double critChance = withAmazonCrit(attacker, skill, critChanceOn(attacker, skill), defender.get(StatKeys.EVASION));
        if (critChance > 0) { // 2026-10-26：暴擊弱點（PoE2 Critical Weakness）：擊中牠的暴擊率 +x 個百分點
            critChance = Math.min(1.0, critChance + Math.max(0, defender.flat(StatKeys.CRIT_CHANCE_TAKEN, Set.of())) / 100.0);
            // 2026-10-26 照 PoE2：「擊中你的暴擊率減少 x%」（INCREASED，負的；這次之前只讀暴擊弱點的加法）
            critChance *= Math.max(0, 1 + defender.increased(StatKeys.CRIT_CHANCE_TAKEN, Set.of()));
            if (StatKeys.flag(defender, TreeLastKeys.FLAG_NO_CRIT_TAKEN)) {
                critChance = 0; // 2026-10-27 照 PoE2 天賦樹：招架中不會被暴擊
            }
        }
        // 2026-10-27 照 PoE2 雙鋒（Garukhan's Resolve）：暴擊率有上限，暴擊擲兩次（任一成功就暴擊，兩次都成功暴擊加成算兩次）
        final double critCap = attacker.flat(LineageKeys.CRIT_CAP, skill.tags());
        if (critCap > 0) {
            critChance = Math.min(critChance, critCap / 100.0);
        }
        final boolean bifurcate = critChance > 0 && StatKeys.flag(attacker, LineageKeys.BIFURCATE);
        final boolean firstRoll = critChance > 0 && random.nextDouble() < critChance && (!unlucky || random.nextDouble() < critChance);
        final boolean secondRoll = bifurcate && random.nextDouble() < critChance;
        boolean crit = firstRoll || secondRoll;
        // 2026-10-26（昇華・強制結果，PoE2 必然暴擊）：沒暴擊就重擲到暴擊為止，每重擲一次暴擊傷害加成更少 30%
        int rerolls = 0;
        if (!crit && critChance > 0 && StatKeys.flag(attacker, tw.exilecore.ascendancy.AscKeys.INEVITABLE_CRIT)) {
            do {
                rerolls++;
            } while (random.nextDouble() >= critChance && rerolls < 200);
            crit = true;
        }
        if (crit) {
            final double multiplier = 1 + (critMultiplier(attacker, critContext, defender) - 1) * Math.pow(0.7, rerolls);
            outgoing.scale(multiplier);
            if (firstRoll && secondRoll && multiplier > 1) {
                outgoing.scale((1 + 2 * (multiplier - 1)) / multiplier); // 2026-10-27 雙鋒：兩次都暴擊，暴擊加成算兩次
            }
            // 超暴擊（2026-10 輔助）：再擲一次，成功暴擊加成再算一次
            final double superChance = attacker.flat(StatKeys.CRIT_SUPER, critContext) / 100.0;
            if (superChance > 0 && multiplier > 1 && random.nextDouble() < superChance) {
                outgoing.scale((1 + 2 * (multiplier - 1)) / multiplier); // 暴擊加成變兩倍
            }
        }
        // 骰運（2026-10 輔助）：每一下傷害在（100 − x）%～（100 + 1.5x）% 之間隨機
        final double swing = Math.min(80, attacker.flat(StatKeys.DAMAGE_RANDOM, critContext));
        if (swing > 0) {
            outgoing.scale(1 + (-swing + random.nextDouble() * swing * 2.5) / 100.0);
        }
        // 傳奇：機率造成雙倍傷害（沒有這個屬性就不擲骰，其他結果不受影響）
        final double doubleChance = attacker.flat(StatKeys.DAMAGE_DOUBLE, critContext) / 100.0;
        if (doubleChance > 0 && random.nextDouble() < doubleChance) {
            outgoing.scale(2);
        }
        // 2026-10-27 照 PoE2 穿刺：這一下抽出的穿刺加在減免前的物理傷害（不再吃增加、更多、暴擊；CombatListener 疊在這一下的表上）
        final double impale = attacker.flat(KeystoneOriginKeys.IMPALE_EXTRACT, Set.of());
        if (impale > 0) {
            outgoing.add(DamageType.PHYSICAL, impale);
        }

        // 5. 減免（感電之類的「受到的傷害增加」在減免之後乘）
        // 粉碎我的敵人（祈願者）：暴擊無視敵人非負的元素抗性
        StatSheet mitigator = defender;
        // 2026-10-27 照 PoE2 天賦樹：「擊中無視凍結敵人非負的元素抗性」（旗標帶目標的條件標籤）
        if (crit && StatKeys.flag(attacker, StatKeys.FLAG_SUNDER) || attacker.flat(TreeLastKeys.FLAG_IGNORE_ELEMENTAL_RESIST, skill.tags()) > 0) {
            final java.util.List<Modifier> ignore = new java.util.ArrayList<>();
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                final double resist = defender.get(type.mitigationStat());
                if (resist > 0) {
                    ignore.add(Modifier.flat(type.mitigationStat(), -resist, "破法拳"));
                }
            }
            mitigator = defender.overlay(ignore);
        }
        if (crit) {
            mitigator = tw.exilecore.item.unique.UniqueHits.critIgnore(mitigator, attacker); // 2026-10-27（雷頌墜）：暴擊無視正的閃電抗性
        }
        // 2026-10-26 照 PoE2 天賦樹：「擊中有 x% 機率把敵人的元素抗性當成相反數」（只翻正的）、
        // 防禦方「x% 機率用 200% 護甲防禦」「受到的擊中物理傷害有 x% 改成隨機一種元素」
        final java.util.List<Modifier> keystone = new java.util.ArrayList<>();
        final double invert = attacker.flat(KeystoneKeys.INVERT_RESIST, skill.tags()) / 100.0;
        if (invert > 0 && random.nextDouble() < invert) {
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                final double resist = Math.min(mitigator.get(type.mitigationStat()), resistCap(mitigator, type));
                if (resist > 0) {
                    keystone.add(Modifier.flat(type.mitigationStat(), -(mitigator.get(type.mitigationStat()) + resist), "抗性反轉")); // 結果 = −（封頂後的抗性）
                }
            }
        }
        final double doubleArmour = defender.flat(KeystoneKeys.DOUBLE_ARMOUR, Set.of()) / 100.0;
        if (doubleArmour > 0 && random.nextDouble() < doubleArmour) {
            keystone.add(Modifier.more(StatKeys.ARMOUR, 1.0, "雙倍護甲"));
        }
        // 2026-10-27 照 PoE2 天賦樹：「用 x% 護甲防禦」（對暴擊、對投射物攻擊、不在低能量護盾時、6 格外的）
        final Set<String> defendContext = crit ? withTag(skill.tags(), TreeLastKeys.CRIT_TAKEN) : skill.tags();
        final double defend = TreeLastKeys.armourDefend(defender, defendContext);
        if (defend > 1) {
            keystone.add(Modifier.more(StatKeys.ARMOUR, defend - 1, "護甲防禦"));
        }
        if (!keystone.isEmpty()) {
            mitigator = mitigator.overlay(keystone);
        }
        final double physAsRandom = Math.min(100, defender.flat(KeystoneKeys.PHYS_TAKEN_AS_RANDOM, Set.of())) / 100.0;
        if (physAsRandom > 0 && outgoing.get(DamageType.PHYSICAL) > 0) {
            final DamageType[] elements = {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING};
            final double moved = outgoing.get(DamageType.PHYSICAL) * physAsRandom;
            outgoing.set(DamageType.PHYSICAL, outgoing.get(DamageType.PHYSICAL) - moved);
            outgoing.add(elements[random.nextInt(elements.length)], moved);
        }
        final DamagePacket dealt = mitigate(outgoing, mitigator, attacker, skill.tags());
        if (blockedScale < 1.0) {
            dealt.scale(blockedScale);
        }
        if (deflected) {
            // 2026-10-26 照 PoE2 天賦樹：「被偏斜的擊中多擋 x%」（加在基礎 40% 上）
            // 2026-10-27 照 PoE2 天賦樹：「被偏斜的暴擊多擋 x%」（標籤 crit_taken）
            dealt.scale(Math.max(0, 1 - Math.min(1, StatKeys.DEFLECT_LESS + defender.flat(TreeEffectKeys.DEFLECT_PREVENT,
                    crit ? Set.of(TreeLastKeys.CRIT_TAKEN) : Set.of()) / 100.0)));
        }
        if (damageTakenMultiplier != 1.0) {
            dealt.scale(Math.max(0, damageTakenMultiplier));
        }

        // 6. 護盾與生命
        double es = Math.max(0, defenderCurrentEs);
        double esDamage = 0;
        double lifeDamage = 0;
        // 2026-10-26 照 PoE2 天賦樹：受到的傷害有 x% 跳過能量護盾
        final double bypass = Math.max(0, Math.min(100, defender.flat(TreeEffectKeys.ES_BYPASS, Set.of()))) / 100.0;
        for (final DamageType type : DamageType.values()) {
            double amount = dealt.get(type);
            if (amount <= 0) {
                continue;
            }
            if (bypass > 0 && es > 0) {
                lifeDamage += amount * bypass;
                amount -= amount * bypass;
            }
            if (es > 0) {
                // PoE2：混沌不再穿過護盾，改成對護盾造成兩倍傷害
                final double rate = type == DamageType.CHAOS ? CHAOS_ES_MULTIPLIER : 1.0;
                final double absorbed = Math.min(es, amount * rate);
                es -= absorbed;
                esDamage += absorbed;
                amount -= absorbed / rate;
            }
            lifeDamage += amount;
        }
        return new HitResult(true, false, crit, outgoing, dealt, esDamage, lifeDamage, deflected);
    }

    /** 2026-10-27：多一個情境標籤的集合。 */
    private static Set<String> withTag(final Set<String> tags, final String tag) {
        final Set<String> out = new HashSet<>(tags);
        out.add(tag);
        return out;
    }

    /**
     * 偏斜值（2026-10-27 照 PoE2）：(固定偏斜 + 閃避值 × 閃避轉偏斜 %) ×（1 + 增加 x% 偏斜值）× 更多——「增加偏斜值」也乘在閃避轉來的那部分
     *（原作閃避轉偏斜是在閃避算完之後加進偏斜的基礎值，偏斜的增加再乘在上面）。這次之前增加／更多只乘到固定偏斜，大宗的閃避轉偏斜沒吃到。
     * 傷害計算、角色面板、增益比較三處都用這個。
     */
    public static double deflectionRating(final StatSheet sheet) {
        final double base = sheet.flat(StatKeys.DEFLECTION, Set.of())
                + sheet.get(StatKeys.EVASION) * Math.max(0, sheet.flat(StatKeys.DEFLECTION_FROM_EVASION, Set.of())) / 100.0;
        return base <= 0 ? 0 : base * Math.max(0, 1 + sheet.increased(StatKeys.DEFLECTION, Set.of())) * sheet.more(StatKeys.DEFLECTION, Set.of());
    }

    /** 偏斜率：見 {@link Formulas#deflectChance}（2026-10-27 照 PoE2，最多 95%；這次之前 1 − 命中公式，最多 75%）。 */
    public static double deflectChance(final double accuracy, final double deflection) {
        return Formulas.deflectChance(accuracy, deflection);
    }

    /**
     * 暴擊倍率（2026-10 PoE2）：1 + 加成 ×（1 + 增加暴擊傷害加成）；加成 = crit.multi − 100（基礎 200 → 加成 100%）。
     * defender 不是 null 時再乘防禦方的「擊中你的暴擊傷害加成減少」。
     */
    public static double critMultiplier(final StatSheet attacker, final Set<String> tags, final StatSheet defender) {
        double bonus = Math.max(0, attacker.get(StatKeys.CRIT_MULTI, tags) - 100) / 100.0 * Math.max(0, 1 + attacker.increased(StatKeys.CRIT_BONUS, tags))
                * Math.max(0, attacker.more(StatKeys.CRIT_BONUS, tags));
        if (defender != null) {
            bonus *= Math.max(0, 1 + defender.increased(StatKeys.CRIT_BONUS_TAKEN, Set.of()));
        }
        return 1 + bonus;
    }

    /** 防禦方減免（不含護盾）。基石「混沌免疫」讓混沌傷害歸零。 */
    public static DamagePacket mitigate(final DamagePacket outgoing, final StatSheet defender) {
        return mitigate(outgoing, defender, null, Set.of());
    }

    /**
     * 這一種傷害的抗性上限（2026-10-20）：全部抗性的上限（resist.max，預設 75）加上這一種自己的上限（resist.max.cold 這種，
     * 「+3% 冰冷抗性上限」）。物理沒有抗性，回傳 resist.max。傷害計算、角色面板、網頁都用這個（以前面板只看 resist.max）。
     */
    public static double resistCap(final StatSheet sheet, final DamageType type) {
        final double max = sheet.get(StatKeys.RESIST_MAX);
        return type == DamageType.PHYSICAL ? max : max + sheet.flat(StatKeys.resistMax(type), Set.of());
    }

    /** 同上，攻擊方的穿透（penetration，tags 限定類型）在抗性上限之後扣。 */
    public static DamagePacket mitigate(final DamagePacket raw, final StatSheet defender, final StatSheet attacker, final Set<String> skillTags) {
        final DamagePacket dealt = new DamagePacket();
        // 2026-10-26（焚衣）：物理視為火焰；2026-10-26（昇華・變形肉體、演化化身）：承受的傷害視為另一種
        final DamagePacket outgoing = tw.exilecore.ascendancy.AscendancyEffects.reroute(tw.exilecore.item.unique.UniqueDefence.reroute(raw, defender), defender);
        final double maxResist = defender.get(StatKeys.RESIST_MAX);
        final boolean chaosImmune = StatKeys.flag(defender, StatKeys.FLAG_CHAOS_IMMUNE);
        for (final DamageType type : DamageType.values()) {
            final double amount = outgoing.get(type);
            if (amount <= 0) {
                continue;
            }
            if (type == DamageType.CHAOS && chaosImmune) {
                continue;
            }
            final double typeMax = type == DamageType.PHYSICAL ? maxResist : resistCap(defender, type);
            double rawResist = type == DamageType.PHYSICAL ? 0 : defender.get(type.mitigationStat());
            if (type == DamageType.CHAOS && StatKeys.flag(defender, StatKeys.FLAG_CHAOS_RES_DOUBLE)) {
                rawResist *= 2; // 夏烏拉的禮物：混沌抗性加倍
            }
            double resist = type == DamageType.PHYSICAL ? 0 : Math.min(rawResist, typeMax);
            if (attacker != null && type != DamageType.PHYSICAL) {
                final Set<String> context = new HashSet<>(skillTags);
                context.addAll(type.tags());
                resist -= Math.max(0, attacker.flat(StatKeys.PENETRATION, context));
            }
            double taken = amount;
            if (type == DamageType.PHYSICAL) {
                // 護甲＋額外物理減免（PoE2，上限玩家 90%、怪物 75%）
                // 守護我免受傷害（祈願者）：護甲減傷照護甲＋閃避算；戰術家：物理減傷最多 50%
                // 2026-10-26（傳奇）：防禦時護甲多 x%、物理減免上限、被壓碎、攻擊的物理傷害扣固定值（tw.exilecore.item.unique.UniqueDefence）
                final double armour = (defender.get(StatKeys.ARMOUR)
                        + (StatKeys.flag(defender, StatKeys.FLAG_PROTECT_HARM) ? defender.get(StatKeys.EVASION) : 0))
                        * tw.exilecore.item.unique.UniqueDefence.armourScale(defender);
                final double cap = tw.exilecore.item.unique.UniqueDefence.reductionCap(defender,
                        StatKeys.flag(defender, StatKeys.FLAG_STAY_LIGHT) ? 0.5
                                : playerSide(defender) ? Formulas.MAX_ARMOUR_REDUCTION : Formulas.MONSTER_MAX_ARMOUR_REDUCTION); // 2026-10-27 照 PoE2：怪物最多 75%（這次之前 90%）
                final double reduction = Math.min(cap,
                        Formulas.armourReduction(armour, amount) + Math.max(0, defender.flat(StatKeys.PHYS_REDUCTION, Set.of())) / 100.0)
                        - tw.exilecore.item.unique.UniqueDefence.crushed(defender);
                taken = tw.exilecore.item.unique.UniqueDefence.flatTaken(defender, skillTags, amount * (1 - reduction));
            } else {
                // 護甲的一部分套用在元素傷害（PoE2，在抗性之前）
                // 2026-10-26 照 PoE2 天賦樹：「x% 護甲也套用在火焰傷害」這種分元素的（標籤寫元素）；這次之前只看沒標籤的
                // 2026-10-26（昇華・奇塔弗奉獻）：混沌也可以
                final double share = type == DamageType.CHAOS ? defender.flat(tw.exilecore.ascendancy.AscKeys.CHAOS_ARMOUR, Set.of()) / 100.0 : defender.flat(StatKeys.ARMOUR_ELEMENTAL, type.tags()) / 100.0;
                if (share > 0) {
                    taken *= 1 - Formulas.armourReduction(defender.get(StatKeys.ARMOUR) * share, taken);
                }
                // 永續之道（開拓者）：閃避的一部分也像護甲一樣減元素傷害
                final double evShare = type == DamageType.CHAOS ? 0 : defender.flat(StatKeys.EVASION_ELEMENTAL, Set.of()) / 100.0;
                if (evShare > 0) {
                    taken *= 1 - Formulas.armourReduction(defender.get(StatKeys.EVASION) * evShare, taken);
                }
                taken *= 1 - Formulas.resistReduction(resist, typeMax);
            }
            // 2026-10-21：減益的「受到的這種傷害增加」（凋息只加混沌、縛根只加物理）
            final double typed = defender.flat(StatKeys.DEBUFF_TAKEN + type.tag(), Set.of());
            if (typed != 0) {
                taken *= Math.max(0, 1 + typed / 100.0);
            }
            dealt.set(type, taken);
        }
        return dealt;
    }

    /** 不擲骰的平均輸出（減免前、不含暴擊），給 /stats 與技能面板顯示。 */
    public static DamagePacket expectedOutgoing(final StatSheet attacker, final SkillProfile skill) {
        return outgoing(chargeScaling(skill.effective(attacker), skill.tags()), skill, (min, max) -> (min + max) / 2.0);
    }

    /**
     * 2026-10：怒星箭這種「每顆怒星更多 x% 攻擊傷害與攻擊速度」的技能：照身上的怒星顆數疊上去（沒有就是原本那張表）。
     */
    public static StatSheet chargeScaling(final StatSheet effective, final Set<String> tags) {
        final double per = effective.flat(StatKeys.PER_FRENZY, tags);
        if (per <= 0) {
            return effective;
        }
        final double held = effective.flat(StatKeys.CHARGE_HELD + "frenzy", Set.of());
        if (held <= 0) {
            return effective;
        }
        final double more = per * held / 100.0;
        return effective.overlay(java.util.List.of(
                Modifier.more(StatKeys.DAMAGE, more, "怒星").withTags(Set.of(Tags.ATTACK)),
                Modifier.more(StatKeys.ATTACK_SPEED, more, "怒星")));
    }

    /** 從一個範圍取值：擲骰或取平均。 */
    @FunctionalInterface
    private interface Roller {
        double roll(double min, double max);
    }

    /**
     * 2026-10（照 PoE2 的 Archmage）：每 100 點最大魔力，獲得 x% 傷害為額外的某類型（{@link StatKeys#damageExtraPerMana}）。
     * 回傳現在的最大魔力換出來的百分比；引導技能（有 channel.stages）不算。
     */
    public static double extraPerMana(final StatSheet attacker, final DamageType extra, final Set<String> context) {
        final double per = attacker.flat(StatKeys.damageExtraPerMana(extra), context);
        if (per <= 0 || attacker.flat(StatKeys.CHANNEL_STAGES, context) > 0) {
            return 0;
        }
        return per * maxMana(attacker) / 100.0;
    }

    /**
     * 最大魔力（跟血條上顯示的一樣，同 PlayerState.maxMana）：血魔法 0；靈能電池把能量護盾也算進最大魔力。
     * 2026-10：以前直接讀 mana.max，靈能電池的人祕法之心少算了能量護盾那一段。
     */
    public static double maxMana(final StatSheet sheet) {
        if (StatKeys.flag(sheet, StatKeys.FLAG_BLOOD_MAGIC)) {
            return 0;
        }
        final double mana = Math.max(0, sheet.get(StatKeys.MANA_MAX));
        return StatKeys.flag(sheet, StatKeys.FLAG_ELDRITCH_BATTERY) ? mana + Math.max(0, sheet.get(StatKeys.ES_MAX)) : mana;
    }

    /**
     * 攻擊方打出的各類型傷害（暴擊與減免前），2026-10-27 照 PoE2（PoB CalcOffence calcDamage，typeFlags = 0）：
     * 基礎（攻擊 = 武器 + 附加，乘效益；法術 = 技能基礎 + 附加 × 效益）→ 物理的轉換（在基礎上算）→
     * 額外獲得（用轉換後各類型的基礎算）→ 每一種「最後的」類型只吃適用那一種類型的增加／更多。
     * 例：100 物理、50% 轉火焰：50 物理只吃物理的修飾器，50 火焰只吃火焰的修飾器（「增加物理傷害」不再加到火焰那一半）。
     * 這次之前（PoE1 規則）：轉換與額外獲得出來的部分同時吃原本類型與新類型的修飾器，額外獲得用轉換前的傷害算。
     */
    private static DamagePacket outgoing(final StatSheet attacker, final SkillProfile skill, final Roller roller) {
        return outgoing(attacker, skill, roller, null);
    }

    /** 同上；lightningRoller 不是 null 時閃電傷害用它擲（2026-10-27 極雷：只擲最小值或最大值）。 */
    private static DamagePacket outgoing(final StatSheet attacker, final SkillProfile skill, final Roller defaultRoller, final Roller lightningRoller) {
        // 墨焰誓（PoE2 黑焰之約）：火焰法術的火焰傷害全部轉成混沌。2026-10-27 照 PoE2 轉換規則：轉過去的傷害只吃混沌的修飾器
        // （這次之前照 PoE1 規則火焰與混沌的都吃；更早只吃火焰的）
        final boolean blackflame = StatKeys.flag(attacker, StatKeys.FLAG_BLACKFLAME) && skill.tags().contains(Tags.SPELL)
                && skill.tags().contains(Tags.FIRE);
        // 2026-10-26（凝霜刃，PoE2 Embitter）：會獲得任何非冰冷類型的額外傷害時，改成獲得額外冰冷傷害
        final boolean asCold = StatKeys.flag(attacker, SupportKeys.FLAG_GAIN_AS_COLD);
        // 2026-10-26（昇華・御風的混合物、女王進軍）：不看武器傷害的攻擊（基礎傷害寫在技能的附加傷害）
        final boolean noWeapon = StatKeys.flag(attacker, "flag.no_weapon_damage");
        final double[] base = new double[DamageType.values().length];
        for (final DamageType type : DamageType.values()) {
            final Set<String> context = skill.contextFor(type);
            final double min;
            double max;
            if (weaponless(skill)) {
                // 2026-10-27 照 PoE2（NonWeaponAttack：血爆、嗅血爆）：不用武器的攻擊——技能自己的基礎傷害取代武器，加上附加傷害再乘效益；
                //   不吃武器的傷害（含武器上的本地附加傷害）。這次之前這類技能只能做成法術
                min = (skill.spellDamage().get(type) + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context)) * skill.effectiveness();
                max = (skill.spellMax().get(type) + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context)) * skill.effectiveness();
            } else if (skill.isAttack()) {
                min = ((noWeapon ? 0 : attacker.flat(StatKeys.WEAPON_MIN, context)) + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context)) * skill.effectiveness();
                max = ((noWeapon ? 0 : attacker.flat(StatKeys.WEAPON_MAX, context)) + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context)) * skill.effectiveness();
            } else {
                min = (skill.spellDamage() == null ? 0 : skill.spellDamage().get(type))
                        + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context) * skill.effectiveness();
                max = (skill.spellMax() == null ? 0 : skill.spellMax().get(type))
                        + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context) * skill.effectiveness();
            }
            // 2026-10-26（沉重，PoE2 Heft）：最大傷害更多 x%（最小傷害不變）
            final double maxMore = attacker.more(SupportKeys.DAMAGE_MAX, context);
            if (maxMore != 1 && max > 0) {
                max = Math.max(min, max * maxMore);
            }
            if (max <= 0) {
                continue;
            }
            final Roller roller = type == DamageType.LIGHTNING && lightningRoller != null ? lightningRoller : defaultRoller;
            double amount = roller.roll(min, max);
            // 2026-10-26 照 PoE2 天賦樹：傷害為幸運（擲兩次取高）的機率（可帶類型：閃電傷害為幸運）。
            // 機率用同一個擲骰器擲 0～1（平均值的擲骰器給 0.5，面板上不會跳來跳去）
            final double lucky = attacker.flat(KeystoneKeys.LUCKY, context) / 100.0;
            if (lucky > 0 && min < max && defaultRoller.roll(0, 1) < lucky) {
                amount = Math.max(amount, roller.roll(min, max));
            }
            base[type.ordinal()] += amount;
        }
        // 物理的轉換（在基礎上算，轉過去的傷害之後只吃新類型的修飾器）
        final double physicalBase = base[DamageType.PHYSICAL.ordinal()];
        if (physicalBase > 0) {
            final Map<DamageType, Double> conversion = conversion(attacker, skill, skill.contextFor(DamageType.PHYSICAL));
            if (!conversion.isEmpty()) {
                final double total = conversion.values().stream().mapToDouble(value -> Math.max(0, value)).sum();
                final double scale = total > 1 ? 1 / total : 1;
                for (final Map.Entry<DamageType, Double> entry : conversion.entrySet()) {
                    if (entry.getKey() == DamageType.PHYSICAL || entry.getValue() <= 0) {
                        continue;
                    }
                    final double converted = physicalBase * entry.getValue() * scale;
                    base[DamageType.PHYSICAL.ordinal()] -= converted;
                    base[entry.getKey().ordinal()] += converted;
                }
                if (base[DamageType.PHYSICAL.ordinal()] < 1e-9) {
                    base[DamageType.PHYSICAL.ordinal()] = 0;
                }
            }
        }
        tw.exilecore.item.unique.UniqueHits.convertElements(base, attacker, skill); // 2026-10-27（傳奇：濁源指環、藍焰護腕…）：元素傷害的轉換
        // 額外獲得（PoE2，PoB calcGainedDamage）：用轉換後每一種類型的基礎算（來源類型的情境決定吃不吃得到），加進新類型的基礎
        // 2026-10-23 修正：同類型也算（混沌傷害也給額外混沌；PoE2 的「傷害」是全部的傷害）
        // 「物理傷害的 x% 額外獲得為…」（引火這種）只看轉換後還是物理的部分（這次之前用轉換前的物理算）
        final double[] summed = base.clone();
        for (final DamageType source : DamageType.values()) {
            final double amount = base[source.ordinal()];
            if (amount <= 0) {
                continue;
            }
            final Set<String> context = skill.contextFor(source);
            for (final DamageType extra : DamageType.values()) {
                double percent = attacker.flat(StatKeys.damageExtra(extra), context) + extraPerMana(attacker, extra, context);
                if (source == DamageType.PHYSICAL && extra != DamageType.PHYSICAL) {
                    percent += attacker.flat(StatKeys.damageGain(extra), context);
                }
                if (percent > 0) {
                    final DamageType gained = asCold ? DamageType.COLD : extra;
                    summed[gained.ordinal()] += amount * percent / 100.0;
                }
            }
        }
        if (blackflame) {
            summed[DamageType.CHAOS.ordinal()] += summed[DamageType.FIRE.ordinal()];
            summed[DamageType.FIRE.ordinal()] = 0;
        }
        // 火焰化身（PoE2 基石）：75% 傷害轉成火焰，不造成非火焰傷害。2026-10-27 照 PoE2：在基礎上轉，轉過去的只吃火焰的修飾器
        // （這次之前在套完增加／更多之後才轉）
        if (StatKeys.flag(attacker, StatKeys.FLAG_AVATAR_FIRE)) {
            for (final DamageType type : DamageType.values()) {
                if (type != DamageType.FIRE) {
                    summed[DamageType.FIRE.ordinal()] += summed[type.ordinal()] * 0.75;
                    summed[type.ordinal()] = 0;
                }
            }
        }
        // 每一種最後的類型只吃適用它的增加／更多（PoE2）
        final DamagePacket packet = new DamagePacket();
        for (final DamageType type : DamageType.values()) {
            final double amount = summed[type.ordinal()];
            if (amount > 1e-9) {
                packet.add(type, attacker.apply(StatKeys.DAMAGE, amount, skill.contextFor(type)));
            }
        }
        // 只造成某一種傷害（隨機元素的稜光裂）：其他類型拿掉
        DamageType only = null;
        for (final DamageType type : DamageType.values()) {
            if (attacker.flat(StatKeys.DAMAGE_ONLY + type.tag(), skill.tags()) > 0) {
                only = type;
            }
        }
        if (only != null) {
            for (final DamageType type : DamageType.values()) {
                if (type != only) {
                    packet.set(type, 0);
                }
            }
        }
        return packet;
    }

    /** 物理的轉換：技能自己的＋輔助的（damage.convert.類型，百分比點數）。 */
    private static Map<DamageType, Double> conversion(final StatSheet attacker, final SkillProfile skill, final Set<String> context) {
        Map<DamageType, Double> result = skill.conversion();
        for (final DamageType type : DamageType.values()) {
            if (type == DamageType.PHYSICAL) {
                continue;
            }
            final double percent = attacker.flat(StatKeys.damageConvert(type), context);
            if (percent > 0) {
                if (!(result instanceof java.util.EnumMap)) {
                    result = result.isEmpty() ? new java.util.EnumMap<>(DamageType.class) : new java.util.EnumMap<>(result);
                }
                result.merge(type, percent / 100.0, Double::sum);
            }
        }
        return result;
    }

    /** 施法速度倍率：（1 + 增加施法速度）× 更多；施法時間 = 技能的基礎施法時間 ÷ 這個倍率。 */
    public static double castSpeedMultiplier(final StatSheet caster, final Set<String> context) {
        return Math.max(0.1, caster.apply(StatKeys.CAST_SPEED, 1.0, context));
    }

    /** 攻擊方的每秒攻擊次數：武器基礎攻速 ×（1 + 增加）× 更多。 */
    public static double attacksPerSecond(final StatSheet attacker, final Set<String> context) {
        final double base = attacker.flat(StatKeys.WEAPON_APS, context);
        return attacker.apply(StatKeys.ATTACK_SPEED, base <= 0 ? 1.0 : base, context);
    }

    /** 攻擊方的暴擊率（小數）；基石「無法暴擊」為 0。會把技能的輔助寶石疊上去。 */
    public static double critChance(final StatSheet attacker, final SkillProfile skill) {
        return critChanceOn(skill.effective(attacker), skill);
    }

    /**
     * 暴擊率，含亞馬遜「命中率溢出的 25% 變成暴擊率」（2026-10：以前只有打中時才加，面板、技能每秒傷害都看不到）。
     *
     * @param defenderEvasion 對方的閃避（面板上用同等級普通怪物的）
     */
    public static double critChance(final StatSheet attacker, final SkillProfile skill, final double defenderEvasion) {
        final StatSheet effective = skill.effective(attacker);
        return withAmazonCrit(effective, skill, critChanceOn(effective, skill), defenderEvasion);
    }

    /** 亞馬遜（PoE2 昇華）：攻擊的命中率可以超過 100%，超過部分的 25% 變成暴擊率（對這個閃避的對手多幾個百分點）。 */
    public static double amazonCritBonus(final StatSheet attacker, final SkillProfile skill, final double defenderEvasion) {
        if (!skill.isAttack() || !StatKeys.flag(attacker, StatKeys.FLAG_AMAZON_CRIT)) {
            return 0;
        }
        final double accuracy = attacker.get(StatKeys.ACCURACY);
        final double raw = accuracy * Formulas.ACCURACY_FACTOR / Math.max(1, accuracy + Math.max(0, defenderEvasion) * Formulas.EVASION_FACTOR);
        return Math.max(0, raw - 1) * 0.25;
    }

    private static double withAmazonCrit(final StatSheet attacker, final SkillProfile skill, final double critChance, final double defenderEvasion) {
        if (critChance <= 0) {
            return critChance; // 不能暴擊的（旗標）就不加
        }
        return Math.min(1.0, critChance + amazonCritBonus(attacker, skill, defenderEvasion));
    }

    /** 同上，但屬性表已經疊好輔助寶石。 */
    private static double critChanceOn(final StatSheet attacker, final SkillProfile skill) {
        if (StatKeys.flag(attacker, StatKeys.FLAG_NO_CRIT)) {
            return 0;
        }
        double baseCrit = weaponless(skill) ? WEAPONLESS_BASE_CRIT // 2026-10-27 照 PoE2：不用武器的攻擊不看武器的暴擊率
                : skill.isAttack() ? tw.exilecore.item.unique.UniqueHits.weaponCrit(attacker, attacker.flat(StatKeys.WEAPON_CRIT, skill.tags())) // 2026-10-27（北爪護腕）
                : BASE_SPELL_CRIT;
        if (!skill.isAttack()) {
            final double own = attacker.flat(StatKeys.SPELL_BASE_CRIT, skill.tags()); // 2026-10-15：照 PoE2，每個法術自己的基礎暴擊率
            if (own > 0) {
                baseCrit = own;
            }
        }
        if (!skill.isAttack() && skill.tags().contains(Tags.SPELL) && StatKeys.flag(attacker, StatKeys.FLAG_SPELL_BASE_CRIT)) {
            baseCrit = Math.max(baseCrit, 15); // 撕裂血肉（血術士）：法術的基礎暴擊率 15%
        }
        baseCrit += attacker.flat(TreeNewMechanicKeys.CRIT_BASE_EXTRA, skill.tags()); // 2026-10-26 照 PoE2 天賦：「捷光環技能的暴擊率 +x%」
        return Math.max(0, Math.min(1.0, baseCrit * (1 + attacker.increased(StatKeys.CRIT_CHANCE, skill.tags())) * attacker.more(StatKeys.CRIT_CHANCE, skill.tags()) / 100.0)); // 2026-10：「更多／更少暴擊率」也算
    }
}
