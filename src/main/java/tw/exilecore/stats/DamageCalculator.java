package tw.exilecore.stats;

import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 傷害計算流程（設計文件「數值引擎」一節）：
 * <ol>
 *   <li>攻擊擲命中（法術必中）、防禦方擲格擋</li>
 *   <li>基礎傷害：攻擊用武器各類型傷害 + 附加傷害，乘技能效益；法術用技能自己的基礎傷害 + 附加</li>
 *   <li>每個類型各自套用攻擊方的增加／更多（只取標籤符合的）</li>
 *   <li>擲暴擊，全部乘暴擊倍率</li>
 *   <li>防禦方減免：物理看護甲、元素與混沌看抗性</li>
 *   <li>先扣能量護盾再扣生命，混沌直接扣生命</li>
 * </ol>
 * 純函式，不依賴 Bukkit。
 */
public final class DamageCalculator {

    /** 法術沒有武器時的基礎暴擊率（百分比點數）。 */
    public static final double BASE_SPELL_CRIT = 5.0;

    private DamageCalculator() {
    }

    public static HitResult calculate(final StatSheet attacker, final SkillProfile skill,
                                      final StatSheet defender, final double defenderCurrentEs,
                                      final RandomGenerator random) {
        // 1. 命中與格擋
        if (skill.isAttack()) {
            final double chance = Formulas.hitChance(attacker.get(StatKeys.ACCURACY), defender.get(StatKeys.EVASION));
            if (random.nextDouble() >= chance) {
                return HitResult.miss();
            }
            final double block = defender.get(StatKeys.BLOCK_CHANCE) / 100.0;
            if (block > 0 && random.nextDouble() < block) {
                return HitResult.block();
            }
        }

        // 2 + 3. 各類型基礎傷害與增加／更多
        final DamagePacket outgoing = new DamagePacket();
        for (final DamageType type : DamageType.values()) {
            final Set<String> context = skill.contextFor(type);
            final double min;
            final double max;
            if (skill.isAttack()) {
                min = (attacker.flat(StatKeys.WEAPON_MIN, context) + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context)) * skill.effectiveness();
                max = (attacker.flat(StatKeys.WEAPON_MAX, context) + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context)) * skill.effectiveness();
            } else {
                final double base = skill.spellDamage() == null ? 0 : skill.spellDamage().get(type);
                min = base + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context) * skill.effectiveness();
                max = base + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context) * skill.effectiveness();
            }
            if (max <= 0) {
                continue;
            }
            final double rolled = min >= max ? max : min + random.nextDouble() * (max - min);
            outgoing.set(type, attacker.apply(StatKeys.DAMAGE, rolled, context));
        }

        // 4. 暴擊
        final Set<String> critContext = skill.tags();
        final double baseCrit = skill.isAttack() ? attacker.flat(StatKeys.WEAPON_CRIT, critContext) : BASE_SPELL_CRIT;
        final double critChance = baseCrit * (1 + attacker.increased(StatKeys.CRIT_CHANCE, critContext)) / 100.0;
        final boolean crit = critChance > 0 && random.nextDouble() < Math.min(1.0, critChance);
        if (crit) {
            outgoing.scale(attacker.get(StatKeys.CRIT_MULTI, critContext) / 100.0);
        }

        // 5. 減免
        final DamagePacket dealt = mitigate(outgoing, defender);

        // 6. 護盾與生命
        double es = Math.max(0, defenderCurrentEs);
        double esDamage = 0;
        double lifeDamage = 0;
        for (final DamageType type : DamageType.values()) {
            double amount = dealt.get(type);
            if (amount <= 0) {
                continue;
            }
            if (type != DamageType.CHAOS && es > 0) {
                final double absorbed = Math.min(es, amount);
                es -= absorbed;
                esDamage += absorbed;
                amount -= absorbed;
            }
            lifeDamage += amount;
        }
        return new HitResult(true, false, crit, outgoing, dealt, esDamage, lifeDamage);
    }

    /** 防禦方減免（不含護盾）。 */
    public static DamagePacket mitigate(final DamagePacket outgoing, final StatSheet defender) {
        final DamagePacket dealt = new DamagePacket();
        final double maxResist = defender.get(StatKeys.RESIST_MAX);
        for (final DamageType type : DamageType.values()) {
            final double amount = outgoing.get(type);
            if (amount <= 0) {
                continue;
            }
            final double reduction = type == DamageType.PHYSICAL
                    ? Formulas.armourReduction(defender.get(StatKeys.ARMOUR), amount)
                    : Formulas.resistReduction(defender.get(type.mitigationStat()), maxResist);
            dealt.set(type, amount * (1 - reduction));
        }
        return dealt;
    }

    /** 不擲骰的平均輸出（減免前、不含暴擊），給 /ec stats 顯示。 */
    public static DamagePacket expectedOutgoing(final StatSheet attacker, final SkillProfile skill) {
        final DamagePacket packet = new DamagePacket();
        for (final DamageType type : DamageType.values()) {
            final Set<String> context = skill.contextFor(type);
            final double min;
            final double max;
            if (skill.isAttack()) {
                min = (attacker.flat(StatKeys.WEAPON_MIN, context) + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context)) * skill.effectiveness();
                max = (attacker.flat(StatKeys.WEAPON_MAX, context) + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context)) * skill.effectiveness();
            } else {
                final double base = skill.spellDamage() == null ? 0 : skill.spellDamage().get(type);
                min = base + attacker.flat(StatKeys.DAMAGE_ADDED_MIN, context) * skill.effectiveness();
                max = base + attacker.flat(StatKeys.DAMAGE_ADDED_MAX, context) * skill.effectiveness();
            }
            if (max <= 0) {
                continue;
            }
            packet.set(type, attacker.apply(StatKeys.DAMAGE, (min + max) / 2.0, context));
        }
        return packet;
    }

    /** 攻擊方的每秒攻擊次數：武器基礎攻速 ×（1 + 增加）× 更多。 */
    public static double attacksPerSecond(final StatSheet attacker, final Set<String> context) {
        final double base = attacker.flat(StatKeys.WEAPON_APS, context);
        return attacker.apply(StatKeys.ATTACK_SPEED, base <= 0 ? 1.0 : base, context);
    }

    /** 攻擊方的暴擊率（小數）。 */
    public static double critChance(final StatSheet attacker, final SkillProfile skill) {
        final double baseCrit = skill.isAttack() ? attacker.flat(StatKeys.WEAPON_CRIT, skill.tags()) : BASE_SPELL_CRIT;
        return Math.min(1.0, baseCrit * (1 + attacker.increased(StatKeys.CRIT_CHANCE, skill.tags())) / 100.0);
    }
}
