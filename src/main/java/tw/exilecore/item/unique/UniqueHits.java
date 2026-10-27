package tw.exilecore.item.unique;

import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2026-10-27 照 PoE2 0.5 第二批傳奇：擊中計算、技能冷卻用到的效果（{@link tw.exilecore.stats.DamageCalculator}、
 * {@link tw.exilecore.skill.SkillBuild} 呼叫）。純函式，不依賴 Bukkit。
 * <ul>
 *   <li>{@link UniqueStats#CONVERT_FROM}：元素傷害轉換為另一種（濁源指環：元素全部轉混沌；藍焰護腕：火轉冰；雷鉗鐵手：火轉電；兄弟戒）。</li>
 *   <li>{@link UniqueStats#CRIT_IGNORE_RESIST}：暴擊無視正的抗性（雷頌墜：閃電）。</li>
 *   <li>{@link UniqueStats#EVADE_MAX}：閃避率上限（警望指環）。</li>
 *   <li>{@link UniqueStats#CRIT_WEAPON_BASE}：武器攻擊的基礎暴擊率改成固定值（北爪護腕）。</li>
 *   <li>{@link UniqueStats#COOLDOWN_FLAT}：技能的基礎冷卻時間 ± 秒（逆漏法袍）。</li>
 * </ul>
 */
public final class UniqueHits {

    private static final DamageType[] ELEMENTAL_AND_CHAOS = {DamageType.FIRE, DamageType.COLD, DamageType.LIGHTNING, DamageType.CHAOS};

    private UniqueHits() {
    }

    /**
     * 物理以外的轉換（在基礎上轉，跟物理的轉換一樣：轉過去的只吃新類型的修飾器）。每一種來源照轉換前的量算，
     * 轉過去的不會再被轉一次；同一種來源的轉換加起來超過 100% 時照比例縮。base 照 {@link DamageType#ordinal()} 排。
     */
    public static void convertElements(final double[] base, final StatSheet attacker, final SkillProfile skill) {
        double[] snapshot = null;
        for (final DamageType from : DamageType.values()) {
            if (from == DamageType.PHYSICAL || base[from.ordinal()] <= 0) {
                continue;
            }
            final Set<String> context = skill.contextFor(from);
            double total = 0;
            for (final DamageType to : DamageType.values()) {
                if (to != from) {
                    total += Math.max(0, attacker.flat(UniqueStats.convertFrom(from, to), context));
                }
            }
            if (total <= 0) {
                continue;
            }
            if (snapshot == null) {
                snapshot = base.clone();
            }
            final double scale = total > 100 ? 100 / total : 1;
            for (final DamageType to : DamageType.values()) {
                final double percent = to == from ? 0 : Math.max(0, attacker.flat(UniqueStats.convertFrom(from, to), context));
                if (percent > 0) {
                    final double moved = snapshot[from.ordinal()] * percent * scale / 100.0;
                    base[from.ordinal()] -= moved;
                    base[to.ordinal()] += moved;
                }
            }
            if (base[from.ordinal()] < 1e-9) {
                base[from.ordinal()] = 0;
            }
        }
    }

    /** 暴擊時無視防禦方正的抗性（只看攻擊方寫了哪幾種元素）；沒有的話回傳同一張表。 */
    public static StatSheet critIgnore(final StatSheet mitigator, final StatSheet attacker) {
        List<Modifier> ignore = null;
        for (final DamageType type : ELEMENTAL_AND_CHAOS) {
            if (attacker.flat(UniqueStats.CRIT_IGNORE_RESIST, type.tags()) <= 0) {
                continue;
            }
            final double resist = mitigator.get(type.mitigationStat());
            if (resist > 0) {
                ignore = ignore == null ? new ArrayList<>(2) : ignore;
                ignore.add(Modifier.flat(type.mitigationStat(), -resist, "暴擊無視抗性"));
            }
        }
        return ignore == null ? mitigator : mitigator.overlay(ignore);
    }

    /** 擊中你的機率（命中）：閃避率最多 x% 時，命中至少 1 − x%。 */
    public static double evadeCap(final StatSheet defender, final double hitChance) {
        final double max = defender.flat(UniqueStats.EVADE_MAX, Set.of());
        return max > 0 ? Math.max(hitChance, 1 - Math.min(100, max) / 100.0) : hitChance;
    }

    /** 武器攻擊的基礎暴擊率（北爪護腕：固定 7%）；沒有這個效果回傳原本的。 */
    public static double weaponCrit(final StatSheet attacker, final double baseCrit) {
        final double fixed = attacker.flat(UniqueStats.CRIT_WEAPON_BASE, Set.of());
        return fixed > 0 && baseCrit > 0 ? fixed : baseCrit;
    }

    /** 技能的基礎冷卻時間（秒）：有冷卻的技能 ± 固定秒數，最少 {@link UniqueStats#COOLDOWN_FLAT_MIN}；沒有冷卻的不變。 */
    public static double cooldown(final double baseSeconds, final StatSheet effective, final Set<String> context) {
        if (baseSeconds <= 0) {
            return baseSeconds;
        }
        final double flat = effective.flat(UniqueStats.COOLDOWN_FLAT, context);
        return flat == 0 ? baseSeconds : Math.max(UniqueStats.COOLDOWN_FLAT_MIN, baseSeconds + flat);
    }
}
