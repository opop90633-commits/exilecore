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

    /** 法術沒有武器時的基礎暴擊率（百分比點數）。 */
    public static final double BASE_SPELL_CRIT = 5.0;
    /** 混沌傷害打在能量護盾上的倍率（PoE2：2 倍）。 */
    public static final double CHAOS_ES_MULTIPLIER = 2.0;

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
        if (evadable && !StatKeys.flag(attacker, StatKeys.FLAG_ALWAYS_HIT)) {
            double chance = Formulas.hitChance(attacker.get(StatKeys.ACCURACY), defender.get(StatKeys.EVASION));
            if (StatKeys.flag(defender, StatKeys.FLAG_STAY_LIGHT)) {
                chance = Math.max(chance, 0.5); // 戰術家：最多閃 50%
            }
            final boolean hit = chance >= 1.0 || (roll != null ? roll.hits(chance) : random.nextDouble() < chance);
            if (!hit) {
                return HitResult.miss();
            }
        }
        double blockedScale = 1.0;
        if (skill.isAttack()) {
            final double cap = StatKeys.BLOCK_CAP + Math.max(0, defender.flat(StatKeys.BLOCK_MAX, Set.of()));
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
            final double deflection = defender.get(StatKeys.DEFLECTION)
                    + defender.get(StatKeys.EVASION) * Math.max(0, defender.flat(StatKeys.DEFLECTION_FROM_EVASION, Set.of())) / 100.0;
            if (deflection > 0) {
                final double deflectChance = deflectChance(attacker.get(StatKeys.ACCURACY), deflection);
                deflected = random.nextDouble() < deflectChance;
            }
        }

        // 2 + 3. 各類型基礎傷害（含轉換）與增加／更多
        final DamagePacket outgoing = outgoing(attacker, skill, (min, max) -> min >= max ? max : min + random.nextDouble() * (max - min));

        // 4. 暴擊
        final Set<String> critContext = skill.tags();
        final double critChance = withAmazonCrit(attacker, skill, critChanceOn(attacker, skill), defender.get(StatKeys.EVASION));
        final boolean crit = critChance > 0 && random.nextDouble() < critChance;
        if (crit) {
            final double multiplier = critMultiplier(attacker, critContext, defender);
            outgoing.scale(multiplier);
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

        // 5. 減免（感電之類的「受到的傷害增加」在減免之後乘）
        // 粉碎我的敵人（祈願者）：暴擊無視敵人非負的元素抗性
        StatSheet mitigator = defender;
        if (crit && StatKeys.flag(attacker, StatKeys.FLAG_SUNDER)) {
            final java.util.List<Modifier> ignore = new java.util.ArrayList<>();
            for (final DamageType type : new DamageType[] {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING}) {
                final double resist = defender.get(type.mitigationStat());
                if (resist > 0) {
                    ignore.add(Modifier.flat(type.mitigationStat(), -resist, "破法拳"));
                }
            }
            mitigator = defender.overlay(ignore);
        }
        final DamagePacket dealt = mitigate(outgoing, mitigator, attacker, skill.tags());
        if (blockedScale < 1.0) {
            dealt.scale(blockedScale);
        }
        if (deflected) {
            dealt.scale(1 - StatKeys.DEFLECT_LESS);
        }
        if (damageTakenMultiplier != 1.0) {
            dealt.scale(Math.max(0, damageTakenMultiplier));
        }

        // 6. 護盾與生命
        double es = Math.max(0, defenderCurrentEs);
        double esDamage = 0;
        double lifeDamage = 0;
        for (final DamageType type : DamageType.values()) {
            double amount = dealt.get(type);
            if (amount <= 0) {
                continue;
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

    /** 偏斜率（2026-10）：跟閃避一樣照命中對偏斜值算，最多 75%。 */
    public static double deflectChance(final double accuracy, final double deflection) {
        if (deflection <= 0) {
            return 0;
        }
        return Math.min(0.75, 1 - Formulas.hitChance(accuracy, deflection));
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

    /** 同上，攻擊方的穿透（penetration，tags 限定類型）在抗性上限之後扣。 */
    public static DamagePacket mitigate(final DamagePacket outgoing, final StatSheet defender, final StatSheet attacker, final Set<String> skillTags) {
        final DamagePacket dealt = new DamagePacket();
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
            final double typeMax = type == DamageType.PHYSICAL ? maxResist : maxResist + defender.flat(StatKeys.resistMax(type), Set.of());
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
                // 護甲＋額外物理減免（PoE2，上限 90%）
                // 守護我免受傷害（祈願者）：護甲減傷照護甲＋閃避算；戰術家：物理減傷最多 50%
                final double armour = defender.get(StatKeys.ARMOUR)
                        + (StatKeys.flag(defender, StatKeys.FLAG_PROTECT_HARM) ? defender.get(StatKeys.EVASION) : 0);
                final double cap = StatKeys.flag(defender, StatKeys.FLAG_STAY_LIGHT) ? 0.5 : Formulas.MAX_ARMOUR_REDUCTION;
                final double reduction = Math.min(cap,
                        Formulas.armourReduction(armour, amount) + Math.max(0, defender.flat(StatKeys.PHYS_REDUCTION, Set.of())) / 100.0);
                taken = amount * (1 - reduction);
            } else {
                // 護甲的一部分套用在元素傷害（PoE2，在抗性之前）
                final double share = type == DamageType.CHAOS ? 0 : defender.flat(StatKeys.ARMOUR_ELEMENTAL, Set.of()) / 100.0;
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
     * 攻擊方打出的各類型傷害（暴擊與減免前）：
     * 基礎（攻擊 = 武器 + 附加，乘效益；法術 = 技能基礎 + 附加 × 效益）→ 物理的「額外獲得」與轉換 → 各自套增加／更多。
     * 轉換與額外獲得出來的部分同時吃原本類型與新類型的修飾器（PoE 規則），例如焚羽箭的火焰部分也吃「增加物理傷害」。
     * 額外獲得（引火：物理傷害的 25% 額外附加為火焰）用轉換前的物理傷害算，物理本身不會變少。
     */
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

    private static DamagePacket outgoing(final StatSheet attacker, final SkillProfile skill, final Roller roller) {
        final DamagePacket packet = new DamagePacket();
        for (final DamageType type : DamageType.values()) {
            final Set<String> context = skill.contextFor(type);
            final double min;
            final double max;
            if (skill.isAttack()) {
                min = (attacker.flat(StatKeys.WEAPON_MIN, context) + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context)) * skill.effectiveness();
                max = (attacker.flat(StatKeys.WEAPON_MAX, context) + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context)) * skill.effectiveness();
            } else {
                min = (skill.spellDamage() == null ? 0 : skill.spellDamage().get(type))
                        + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context) * skill.effectiveness();
                max = (skill.spellMax() == null ? 0 : skill.spellMax().get(type))
                        + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context) * skill.effectiveness();
            }
            if (max <= 0) {
                continue;
            }
            double amount = roller.roll(min, max);
            // 獲得 x% 傷害為額外某類型（PoE2，看全部傷害；額外的部分同時吃原本類型與新類型的修飾器）
            for (final DamageType extra : DamageType.values()) {
                if (extra == type) {
                    continue;
                }
                final double percent = attacker.flat(StatKeys.damageExtra(extra), context) + extraPerMana(attacker, extra, context);
                if (percent > 0) {
                    final Set<String> both = new HashSet<>(context);
                    both.addAll(extra.tags());
                    packet.add(extra, attacker.apply(StatKeys.DAMAGE, amount * percent / 100.0, both));
                }
            }
            if (type == DamageType.PHYSICAL) {
                for (final DamageType extra : DamageType.values()) {
                    if (extra == DamageType.PHYSICAL) {
                        continue;
                    }
                    final double percent = attacker.flat(StatKeys.damageGain(extra), context);
                    if (percent > 0) {
                        final Set<String> both = new HashSet<>(context);
                        both.addAll(extra.tags());
                        packet.add(extra, attacker.apply(StatKeys.DAMAGE, amount * percent / 100.0, both));
                    }
                }
            }
            final Map<DamageType, Double> conversion = type == DamageType.PHYSICAL ? conversion(attacker, skill, context) : Map.of();
            if (!conversion.isEmpty()) {
                final double total = conversion.values().stream().mapToDouble(value -> Math.max(0, value)).sum();
                final double scale = total > 1 ? 1 / total : 1;
                final double physical = amount;
                for (final Map.Entry<DamageType, Double> entry : conversion.entrySet()) {
                    if (entry.getKey() == DamageType.PHYSICAL || entry.getValue() <= 0) {
                        continue;
                    }
                    final double converted = physical * entry.getValue() * scale;
                    amount -= converted;
                    final Set<String> both = new HashSet<>(context);
                    both.addAll(entry.getKey().tags());
                    packet.add(entry.getKey(), attacker.apply(StatKeys.DAMAGE, converted, both));
                }
            }
            if (amount > 1e-9) {
                packet.add(type, attacker.apply(StatKeys.DAMAGE, amount, context));
            }
        }
        // 黑焰之約（PoE2 基石）：火焰法術的火焰傷害全部轉成混沌
        if (StatKeys.flag(attacker, StatKeys.FLAG_BLACKFLAME) && skill.tags().contains(Tags.SPELL) && skill.tags().contains(Tags.FIRE)) {
            packet.add(DamageType.CHAOS, packet.get(DamageType.FIRE));
            packet.set(DamageType.FIRE, 0);
        }
        // 火焰化身（PoE2 基石）：75% 傷害轉成火焰，不造成非火焰傷害
        if (StatKeys.flag(attacker, StatKeys.FLAG_AVATAR_FIRE)) {
            double fire = packet.get(DamageType.FIRE);
            for (final DamageType type : DamageType.values()) {
                if (type != DamageType.FIRE) {
                    fire += packet.get(type) * 0.75;
                    packet.set(type, 0);
                }
            }
            packet.set(DamageType.FIRE, fire);
        }
        // 只造成某一種傷害（幻元箭）：其他類型拿掉
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
        double baseCrit = skill.isAttack() ? attacker.flat(StatKeys.WEAPON_CRIT, skill.tags()) : BASE_SPELL_CRIT;
        if (!skill.isAttack() && skill.tags().contains(Tags.SPELL) && StatKeys.flag(attacker, StatKeys.FLAG_SPELL_BASE_CRIT)) {
            baseCrit = Math.max(baseCrit, 15); // 撕裂血肉（血術士）：法術的基礎暴擊率 15%
        }
        return Math.max(0, Math.min(1.0, baseCrit * (1 + attacker.increased(StatKeys.CRIT_CHANCE, skill.tags())) * attacker.more(StatKeys.CRIT_CHANCE, skill.tags()) / 100.0)); // 2026-10：「更多／更少暴擊率」也算
    }
}
