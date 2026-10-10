package tw.exilecore.quest;

import tw.exilecore.stats.ModType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.StatKeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 任務的永久獎勵（純邏輯）：從角色完成的任務（與選的那一個）算出天賦點與屬性修飾器。
 * 不另外存：完成了就有，任務的設定改了（例如重新平衡）也跟著變；不會重複拿，也不會因為當機少拿。
 */
public final class QuestRewards {

    /** 獎勵可以用的屬性 → 名稱（照這個順序列在任務日誌的總覽）。 */
    public static final Map<String, String> STATS;

    static {
        final Map<String, String> stats = new LinkedHashMap<>();
        stats.put(StatKeys.LIFE_MAX, "最大生命");
        stats.put(StatKeys.MANA_MAX, "最大魔力");
        stats.put(StatKeys.ES_MAX, "能量護盾");
        stats.put(StatKeys.RESIST_FIRE, "火焰抗性");
        stats.put(StatKeys.RESIST_COLD, "冰冷抗性");
        stats.put(StatKeys.RESIST_LIGHTNING, "閃電抗性");
        stats.put(StatKeys.RESIST_CHAOS, "混沌抗性");
        stats.put(StatKeys.ARMOUR, "護甲");
        stats.put(StatKeys.EVASION, "閃避");
        stats.put(StatKeys.AILMENT_THRESHOLD, "元素異常門檻");
        stats.put(StatKeys.LIFE_REGEN, "每秒生命回復");
        stats.put(StatKeys.MANA_REGEN, "魔力回復");
        stats.put(StatKeys.FLASK_LIFE_RECOVERY, "生命藥劑回復");
        stats.put(StatKeys.FLASK_MANA_RECOVERY, "魔力藥劑回復");
        stats.put(StatKeys.MOVEMENT_SPEED, "移動速度");
        STATS = java.util.Collections.unmodifiableMap(stats);
    }

    /** 只能寫百分比（increased）的屬性：遊戲只讀這一種。 */
    private static final Set<String> INCREASED_ONLY = Set.of(StatKeys.AILMENT_THRESHOLD,
            StatKeys.FLASK_LIFE_RECOVERY, StatKeys.FLASK_MANA_RECOVERY, StatKeys.MOVEMENT_SPEED);
    /** 只能寫固定值（flat）的屬性。 */
    private static final Set<String> FLAT_ONLY = Set.of(StatKeys.RESIST_FIRE, StatKeys.RESIST_COLD, StatKeys.RESIST_LIGHTNING,
            StatKeys.RESIST_CHAOS, StatKeys.LIFE_REGEN);

    /** 這個屬性可以用這種寫法嗎（寫錯的那一種遊戲不會讀）。 */
    public static boolean allowed(final String stat, final ModType type) {
        if (type == ModType.FLAT) {
            return !INCREASED_ONLY.contains(stat);
        }
        return type == ModType.INCREASED && !FLAT_ONLY.contains(stat);
    }

    /** 固定值寫成百分比的屬性（抗性 +10 = +10%）。 */
    private static final Set<String> PERCENT_POINTS = Set.of(StatKeys.RESIST_FIRE, StatKeys.RESIST_COLD,
            StatKeys.RESIST_LIGHTNING, StatKeys.RESIST_CHAOS);

    private QuestRewards() {
    }

    /** 「冰冷抗性 +10%」「最大生命 +20」「最大生命 +5%」。 */
    public static String describe(final QuestConfig.Bonus bonus) {
        final String name = STATS.getOrDefault(bonus.stat(), bonus.stat());
        if (bonus.type() == ModType.FLAT) {
            return name + " +" + number(bonus.value()) + (PERCENT_POINTS.contains(bonus.stat()) ? "%" : "");
        }
        return name + " +" + number(bonus.value() * 100) + "%";
    }

    /** 一個任務的獎勵（一行一個；有幾選一時寫「選一個：…」，選過了寫選的那個）。 */
    public static List<String> describe(final QuestConfig.Quest quest, final String chosen) {
        final List<String> lines = new ArrayList<>();
        if (quest.passivePoints() > 0) {
            lines.add("天賦點 +" + quest.passivePoints());
        }
        for (final QuestConfig.Bonus bonus : quest.bonuses()) {
            lines.add(describe(bonus));
        }
        if (!quest.choices().isEmpty()) {
            final QuestConfig.Choice choice = chosen == null ? null : quest.choice(chosen);
            if (choice != null) {
                lines.add(choice.label());
            } else {
                final List<String> labels = new ArrayList<>();
                for (final QuestConfig.Choice option : quest.choices()) {
                    labels.add(option.label());
                }
                lines.add((quest.choices().size() == 2 ? "二選一：" : quest.choices().size() == 3 ? "三選一：" : "選一個：")
                        + String.join("／", labels));
            }
        }
        return lines;
    }

    /** 完成的任務給的天賦點加起來。 */
    public static int passivePoints(final QuestConfig config, final Map<String, String> progress) {
        int total = 0;
        for (final QuestConfig.Quest quest : config.quests()) {
            if (QuestLog.parse(progress.get(QuestLog.key(quest.id()))).phase() == QuestLog.Phase.DONE) {
                total += quest.passivePoints();
            }
        }
        return total;
    }

    /** 完成的任務給的屬性修飾器（含選的那一個）。 */
    public static List<Modifier> modifiers(final QuestConfig config, final Map<String, String> progress) {
        final List<Modifier> list = new ArrayList<>();
        for (final QuestConfig.Quest quest : config.quests()) {
            final QuestLog.State state = QuestLog.parse(progress.get(QuestLog.key(quest.id())));
            if (state.phase() != QuestLog.Phase.DONE) {
                continue;
            }
            final String source = "任務「" + quest.name() + "」";
            for (final QuestConfig.Bonus bonus : quest.bonuses()) {
                list.add(modifier(bonus, source));
            }
            final QuestConfig.Choice choice = state.choice() == null ? null : quest.choice(state.choice());
            if (choice != null) {
                for (final QuestConfig.Bonus bonus : choice.bonuses()) {
                    list.add(modifier(bonus, source));
                }
            }
        }
        return list;
    }

    /** 加起來的總覽：屬性 → 「冰冷抗性 +20%」這種（同一種屬性、同一種型態加在一起）。 */
    public static List<String> summary(final QuestConfig config, final Map<String, String> progress) {
        final Map<String, Double> flat = new LinkedHashMap<>();
        final Map<String, Double> increased = new LinkedHashMap<>();
        for (final Modifier modifier : modifiers(config, progress)) {
            (modifier.type() == ModType.FLAT ? flat : increased).merge(modifier.stat(), modifier.value(), Double::sum);
        }
        final List<String> lines = new ArrayList<>();
        final int points = passivePoints(config, progress);
        if (points > 0) {
            lines.add("天賦點 +" + points);
        }
        for (final String stat : STATS.keySet()) {
            if (flat.containsKey(stat)) {
                lines.add(describe(new QuestConfig.Bonus(stat, ModType.FLAT, flat.get(stat))));
            }
            if (increased.containsKey(stat)) {
                lines.add(describe(new QuestConfig.Bonus(stat, ModType.INCREASED, increased.get(stat))));
            }
        }
        return lines;
    }

    private static Modifier modifier(final QuestConfig.Bonus bonus, final String source) {
        return new Modifier(bonus.stat(), bonus.type(), bonus.value(), Set.of(), source);
    }

    private static String number(final double value) {
        final double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? Long.toString(Math.round(rounded)) : String.format(Locale.ROOT, "%.1f", rounded);
    }
}
