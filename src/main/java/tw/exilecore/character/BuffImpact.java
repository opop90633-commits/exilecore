package tw.exilecore.character;

import org.bukkit.entity.Player;
import tw.exilecore.ExileCore;
import tw.exilecore.stats.DamageCalculator;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 增益對面板數值的影響（2026-10）：拿玩家身上的屬性表跟「沒有暫時增益」的表（{@link PlayerStateService#baselineSheet}）比，
 * 列出變了的數值：「生命上限 1,000 → 1,200（+200）」。
 * <p>
 * 暫時增益＝光環、戰吼、護符、球、疾速、圖騰、魔像，加上條件成立才有的詞綴（最近被擊中、藥劑效果中……）與怒氣。
 * 只在擊中當下才算的（戰吼強化、連擊、對低血的敵人）沒辦法事先算，不列。
 */
public final class BuffImpact {

    /** 防禦、攻擊兩邊各自變了的行（MiniMessage）；都空的＝增益沒改到面板上的數值。 */
    public record Lines(List<String> defence, List<String> offence) {
        public boolean isEmpty() {
            return defence.isEmpty() && offence.isEmpty();
        }

        public List<String> all() {
            final List<String> all = new ArrayList<>(defence);
            all.addAll(offence);
            return all;
        }
    }

    private static final double EPSILON = 1e-6;

    private BuffImpact() {
    }

    /**
     * 面板要用的「現在」的屬性表：玩家身上的表，再疊上只在擊中時算、但現在就看得出來的加成（怒氣）。
     * 跟 CombatListener 的算法一樣：每點怒氣增加 1% 攻擊傷害（吃「怒氣效果」）。
     */
    public static StatSheet liveSheet(final ExileCore plugin, final Player player) {
        final StatSheet sheet = plugin.players().sheet(player);
        final List<Modifier> hitTime = hitTimeModifiers(plugin, player, sheet);
        return hitTime.isEmpty() ? sheet : sheet.overlay(hitTime);
    }

    private static List<Modifier> hitTimeModifiers(final ExileCore plugin, final Player player, final StatSheet sheet) {
        if (plugin.skills() == null) {
            return List.of();
        }
        final int rage = plugin.skills().resources().rage(player);
        if (rage <= 0) {
            return List.of();
        }
        final double per = 0.01 * Math.max(0, 1 + sheet.increased(StatKeys.RAGE_EFFECT, Set.of()));
        return List.of(new Modifier(StatKeys.DAMAGE, ModType.INCREASED, rage * per, Set.of(Tags.ATTACK), "怒氣"));
    }

    /** 怒氣讓攻擊傷害增加多少（小數；沒有怒氣是 0）。 */
    public static double rageBonus(final ExileCore plugin, final Player player) {
        double total = 0;
        for (final Modifier modifier : hitTimeModifiers(plugin, player, plugin.players().sheet(player))) {
            total += modifier.value();
        }
        return total;
    }

    public static Lines of(final ExileCore plugin, final Player player) {
        final PlayerState state = plugin.players().get(player);
        final StatSheet now = liveSheet(plugin, player);
        final StatSheet base = plugin.players().baselineSheet(player);
        return new Lines(defence(now, base), offence(now, base, state.weaponTags()));
    }

    // ---- 防禦 ----

    private static List<String> defence(final StatSheet now, final StatSheet base) {
        final List<String> lines = new ArrayList<>();
        number(lines, StatsReport.LIFE + "生命上限", PlayerState.maxLife(base), PlayerState.maxLife(now));
        number(lines, StatsReport.MANA + "魔力上限", PlayerState.maxMana(base), PlayerState.maxMana(now));
        number(lines, StatsReport.ES + "能量護盾", PlayerState.maxEnergyShield(base), PlayerState.maxEnergyShield(now));
        number(lines, "<#c9a7ff>精魂上限", Math.max(0, base.get(StatKeys.SPIRIT_MAX)), Math.max(0, now.get(StatKeys.SPIRIT_MAX)));
        decimal(lines, StatsReport.LIFE + "生命回復/秒", lifeRegen(base), lifeRegen(now));
        decimal(lines, StatsReport.MANA + "魔力回復/秒", base.get(StatKeys.MANA_REGEN), now.get(StatKeys.MANA_REGEN));
        number(lines, "<gray>護甲", base.get(StatKeys.ARMOUR), now.get(StatKeys.ARMOUR));
        number(lines, "<gray>閃避", base.get(StatKeys.EVASION), now.get(StatKeys.EVASION));
        number(lines, "<gray>偏斜", deflection(base), deflection(now));
        percentPoints(lines, StatsReport.BLOCK + "格擋", Math.min(StatKeys.BLOCK_CAP, base.get(StatKeys.BLOCK_CHANCE)),
                Math.min(StatKeys.BLOCK_CAP, now.get(StatKeys.BLOCK_CHANCE)));
        for (final DamageType type : DamageType.values()) {
            if (type == DamageType.PHYSICAL) {
                continue;
            }
            percentPoints(lines, "<" + tw.exilecore.combat.DamageNumbers.color(type) + ">" + type.displayName() + "抗性",
                    resist(base, type), resist(now, type));
        }
        percentPoints(lines, "<gray>抗性上限", base.get(StatKeys.RESIST_MAX), now.get(StatKeys.RESIST_MAX));
        return lines;
    }

    private static double lifeRegen(final StatSheet sheet) {
        return sheet.get(StatKeys.LIFE_REGEN) + PlayerState.maxLife(sheet) * sheet.get(StatKeys.LIFE_REGEN_PERCENT) / 100.0;
    }

    private static double deflection(final StatSheet sheet) {
        return sheet.get(StatKeys.DEFLECTION)
                + sheet.get(StatKeys.EVASION) * Math.max(0, sheet.flat(StatKeys.DEFLECTION_FROM_EVASION, Set.of())) / 100.0;
    }

    private static double resist(final StatSheet sheet, final DamageType type) {
        return Math.min(sheet.get(type.mitigationStat()), sheet.get(StatKeys.RESIST_MAX));
    }

    // ---- 攻擊 ----

    private static List<String> offence(final StatSheet now, final StatSheet base, final Set<String> weaponTags) {
        final List<String> lines = new ArrayList<>();
        final SkillProfile attack = SkillProfile.defaultAttack(weaponTags);
        final double baseHit = DamageCalculator.expectedOutgoing(base, attack).total();
        final double nowHit = DamageCalculator.expectedOutgoing(now, attack).total();
        number(lines, "<gray>普攻平均傷害", baseHit, nowHit);
        decimal2(lines, "<gray>普攻每秒次數", DamageCalculator.attacksPerSecond(base, attack.tags()), DamageCalculator.attacksPerSecond(now, attack.tags()));
        percentPoints1(lines, "<gray>普攻暴擊率", DamageCalculator.critChance(base, attack) * 100, DamageCalculator.critChance(now, attack) * 100);
        percentPoints(lines, "<gray>暴擊傷害", DamageCalculator.critMultiplier(base, attack.tags(), null) * 100,
                DamageCalculator.critMultiplier(now, attack.tags(), null) * 100);
        number(lines, "<gray>普攻估計每秒傷害", attackDps(base, attack), attackDps(now, attack));
        final SkillProfile spell = new SkillProfile("stats_spell", Set.of(Tags.SPELL), 1.0, null);
        percentPoints1(lines, "<gray>法術暴擊率", DamageCalculator.critChance(base, spell) * 100, DamageCalculator.critChance(now, spell) * 100);
        number(lines, "<gray>命中", base.get(StatKeys.ACCURACY), now.get(StatKeys.ACCURACY));
        // 傷害倍率：先看不分種類的，再看各種類多出來的（跟不分種類一樣就不重複列）
        final double general = ratio(base, now, StatKeys.DAMAGE, Set.of());
        relative(lines, "<white>傷害（全部）", general);
        final String[][] contexts = {{Tags.ATTACK, "攻擊傷害"}, {Tags.SPELL, "法術傷害"}};
        for (final String[] context : contexts) {
            final double r = ratio(base, now, StatKeys.DAMAGE, Set.of(context[0]));
            if (Math.abs(r - general) > EPSILON) {
                relative(lines, "<white>" + context[1], r);
            }
        }
        for (final DamageType type : DamageType.values()) {
            final double r = ratio(base, now, StatKeys.DAMAGE, type.tags());
            if (Math.abs(r - general) > EPSILON) {
                relative(lines, "<" + tw.exilecore.combat.DamageNumbers.color(type) + ">" + type.displayName() + "傷害", r);
            }
        }
        for (final DamageType type : DamageType.values()) {
            if (type == DamageType.PHYSICAL) {
                continue;
            }
            percentPoints(lines, "<" + tw.exilecore.combat.DamageNumbers.color(type) + ">" + type.displayName() + "穿透",
                    base.get(StatKeys.PENETRATION, type.tags()), now.get(StatKeys.PENETRATION, type.tags()));
        }
        relative(lines, "<gray>施法速度", ratio(base, now, StatKeys.CAST_SPEED, Set.of(Tags.SPELL)));
        increasedPoints(lines, "<gray>移動速度", base.increased(StatKeys.MOVEMENT_SPEED, Set.of()), now.increased(StatKeys.MOVEMENT_SPEED, Set.of()));
        increasedPoints(lines, "<gray>技能範圍", base.increased(StatKeys.SKILL_AREA, Set.of()), now.increased(StatKeys.SKILL_AREA, Set.of()));
        increasedPoints(lines, "<gray>持續時間", base.increased(StatKeys.SKILL_DURATION, Set.of()), now.increased(StatKeys.SKILL_DURATION, Set.of()));
        increasedPoints(lines, "<gray>冷卻時間", base.increased(StatKeys.SKILL_COOLDOWN, Set.of()), now.increased(StatKeys.SKILL_COOLDOWN, Set.of()));
        percentPoints1(lines, "<gray>物理偷取生命", base.get(StatKeys.LEECH_LIFE), now.get(StatKeys.LEECH_LIFE));
        number(lines, "<gray>擊中回復生命", base.get(StatKeys.LIFE_ON_HIT), now.get(StatKeys.LIFE_ON_HIT));
        return lines;
    }

    /** 跟 StatsReport 的「估計每秒傷害」同一個算法。 */
    private static double attackDps(final StatSheet sheet, final SkillProfile attack) {
        final double crit = DamageCalculator.critChance(sheet, attack);
        final double multi = DamageCalculator.critMultiplier(sheet, attack.tags(), null);
        return DamageCalculator.expectedOutgoing(sheet, attack).total() * DamageCalculator.attacksPerSecond(sheet, attack.tags())
                * (1 + crit * (multi - 1));
    }

    /** 這個屬性的（增加 × 更多）倍率，現在比沒有增益時大幾倍。 */
    private static double ratio(final StatSheet base, final StatSheet now, final String stat, final Set<String> tags) {
        final double before = base.apply(stat, 1.0, tags);
        final double after = now.apply(stat, 1.0, tags);
        if (before <= EPSILON) {
            return after <= EPSILON ? 1.0 : Double.POSITIVE_INFINITY;
        }
        return after / before;
    }

    // ---- 格式 ----

    private static String arrow(final double delta) {
        return delta > 0 ? "<green>" : "<red>";
    }

    /** 整數：「生命上限 1,000 → 1,200（+200）」。 */
    private static void number(final List<String> into, final String label, final double before, final double after) {
        final long b = Math.round(before);
        final long a = Math.round(after);
        if (a == b) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%,d <dark_gray>→ %s%,d <dark_gray>（%s%,d）", label, b, arrow(a - b), a, a > b ? "+" : "−", Math.abs(a - b)));
    }

    /** 一位小數：回復速度。 */
    private static void decimal(final List<String> into, final String label, final double before, final double after) {
        if (Math.abs(after - before) < 0.05) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%.1f <dark_gray>→ %s%.1f", label, before, arrow(after - before), after));
    }

    /** 兩位小數：每秒次數。 */
    private static void decimal2(final List<String> into, final String label, final double before, final double after) {
        if (Math.abs(after - before) < 0.005) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%.2f <dark_gray>→ %s%.2f", label, before, arrow(after - before), after));
    }

    /** 百分比點數（整數）：抗性、格擋、暴擊傷害。 */
    private static void percentPoints(final List<String> into, final String label, final double before, final double after) {
        final long b = Math.round(before);
        final long a = Math.round(after);
        if (a == b) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%d%% <dark_gray>→ %s%d%%", label, b, arrow(a - b), a));
    }

    /** 百分比點數（一位小數）：暴擊率、偷取。 */
    private static void percentPoints1(final List<String> into, final String label, final double before, final double after) {
        if (Math.abs(after - before) < 0.05) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <white>%.1f%% <dark_gray>→ %s%.1f%%", label, before, arrow(after - before), after));
    }

    /** 「增加 x%」的總和：移動速度 +10% → +30%。 */
    private static void increasedPoints(final List<String> into, final String label, final double before, final double after) {
        final long b = Math.round(before * 100);
        final long a = Math.round(after * 100);
        if (a == b) {
            return;
        }
        // 冷卻時間：增加是壞事（顏色反過來）
        final boolean good = label.contains("冷卻") ? a < b : a > b;
        into.add(String.format(Locale.ROOT, "%s <white>%s <dark_gray>→ %s%s", label, signed(b), good ? "<green>" : "<red>", signed(a)));
    }

    /** 倍率：「火焰傷害 更多 +18%」（相對沒有增益的時候）。 */
    private static void relative(final List<String> into, final String label, final double ratio) {
        if (Double.isInfinite(ratio)) {
            into.add(label + " <green>（原本是 0）");
            return;
        }
        final long percent = Math.round((ratio - 1) * 100);
        if (percent == 0) {
            return;
        }
        into.add(String.format(Locale.ROOT, "%s <dark_gray>比沒有增益時 %s%s%d%%", label, percent > 0 ? "<green>多 " : "<red>少 ", "", Math.abs(percent)));
    }

    private static String signed(final long percent) {
        return (percent >= 0 ? "+" : "−") + Math.abs(percent) + "%";
    }
}
