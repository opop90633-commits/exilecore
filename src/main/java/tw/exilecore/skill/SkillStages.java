package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.Modifier;
import tw.exilecore.stats.SkillProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-28 照 PoE2：技能的分段傷害（skills.yml 的 stages）。PoE2 很多攻擊技能有好幾個 stat set（終結打擊、爆炸、砸鐘、旋風…），
 * 每一段有自己的逐級傷害效益（damage_multiplier）與轉換、範圍；這次之前這些副段直接用主段的傷害，或用一個固定的比例估。
 *
 * <pre>
 * stages:
 *   final: { name: 終結打擊, effectiveness: [1.35, …, 11.17], convert: { lightning: 1.0 }, speed: -45, reach: 0.6 }
 *   explosion: { name: 爆炸, effectiveness: [3.0, …, 54.29], radius: 3.2 }
 * </pre>
 *
 * 每一段在造成傷害的地方（{@link #profile}）把這一下的傷害換成「這一段的效益 ÷ 技能主段的效益」倍（同一個等級；輔助、品質、加成照吃），
 * 有寫 convert 就換掉物理轉換；modifiers 是只對這一段有效的逐級修飾器（凍結累積更多…）。
 * speed（這一下的攻擊速度更多／更少 %）、reach（打擊距離 +幾格）、hits（這一下打幾次）是給連段的那一下用的（{@link #apply}）。
 * radius、count、interval、duration、after 是這一段自己的範圍、次數、間隔、持續、延遲（各個技能自己讀）。純資料，不依賴 Bukkit。
 */
public final class SkillStages {

    /**
     * 一段。
     *
     * @param key           代號（stages 底下的鍵）
     * @param name          玩家看到的名稱
     * @param effectiveness 這一段的傷害效益（逐級，跟技能的 effectiveness 同單位）
     * @param conversion    這一段的物理轉換（空的 = 跟主段一樣）
     * @param speed         這一下的攻擊速度更多 x%（負的是更少）
     * @param reach         打擊距離 +幾格
     * @param hits          這一下打幾次
     * @param radius        這一段的半徑（格；0 = 各技能的預設）
     * @param count         這一段的次數（逐級；0 = 各技能的預設）
     * @param interval      這一段的間隔（秒）
     * @param duration      這一段的持續（秒）
     * @param after         這一段在主段之後幾秒
     * @param modifiers     只對這一段有效的修飾器（逐級）
     */
    public record Stage(String key, String name, Scaled effectiveness, Map<DamageType, Double> conversion, double speed, double reach,
                        int hits, Scaled radius, Scaled count, double interval, double duration, double after, List<ScaledMod> modifiers) {
        public Stage {
            conversion = conversion == null || conversion.isEmpty() ? Map.of() : Map.copyOf(conversion);
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }

        /** 這個等級的半徑（格，還沒乘範圍加成）。 */
        public double radius(final int level) {
            return radius.at(level);
        }
    }

    /** 加在這一下的記號（FLAT，只放在 profile 的 local 裡，給出手的地方讀）。 */
    public static final String SPEED = "stage.speed";
    public static final String REACH = "stage.reach";
    public static final String HITS = "stage.hits";
    /** 這一下是哪一段（FLAT 1，「stage.is.代號」；打中之後的效果用來認）。 */
    public static final String IS = "stage.is.";
    /** 連刺的最後一下（三連刺的第六刺）。 */
    public static final String LAST = "last";

    private static volatile Map<String, Map<String, Stage>> stages = Map.of();

    private SkillStages() {
    }

    /** 讀一個技能的 stages 區塊（沒有就回傳空的）。 */
    static Map<String, Stage> parse(final YamlNode skill) {
        if (!skill.has("stages")) {
            return Map.of();
        }
        final YamlNode section = skill.node("stages");
        final Map<String, Stage> out = new LinkedHashMap<>();
        for (final String key : section.keys()) {
            final YamlNode node = section.node(key);
            final String where = node.path();
            final Scaled effectiveness = Scaled.parse(where + ".effectiveness", node.raw("effectiveness"), 0);
            if (effectiveness.min() <= 0) {
                throw new IllegalArgumentException(where + ".effectiveness：每一級都要大於 0");
            }
            final double speed = node.decimal("speed", 0);
            if (speed <= -95 || speed > 500) {
                throw new IllegalArgumentException(where + ".speed：要在 -95 到 500 之間（%）");
            }
            final int hits = node.integer("hits", 1);
            if (hits < 1 || hits > 10) {
                throw new IllegalArgumentException(where + ".hits：要在 1 到 10 之間");
            }
            out.put(key, new Stage(key, node.string("name", key).trim(), effectiveness,
                    node.has("convert") ? SkillDefinition.weights(where + ".convert", node.node("convert"), false) : Map.of(),
                    speed, node.decimal("reach", 0), hits,
                    Scaled.parse(where + ".radius", node.raw("radius"), 0), Scaled.parse(where + ".count", node.raw("count"), 0),
                    Math.max(0, node.decimal("interval", 0)), Math.max(0, node.decimal("duration", 0)), Math.max(0, node.decimal("after", 0)),
                    ScaledMod.parseList(node, "modifiers")));
        }
        return Map.copyOf(out);
    }

    /** 換上新讀的設定（SkillConfig.load；技能代號 → 那個技能的各段）。 */
    static void install(final Map<String, Map<String, Stage>> parsed) {
        stages = Map.copyOf(parsed);
    }

    /** 這個技能的某一段（沒有回傳 null）。 */
    public static Stage get(final String skillId, final String key) {
        if (skillId == null || key == null) {
            return null;
        }
        final Map<String, Stage> mine = stages.get(skillId);
        return mine == null ? null : mine.get(key);
    }

    /** 這個技能的所有段（說明用；照 skills.yml 的順序）。 */
    public static Map<String, Stage> of(final String skillId) {
        final Map<String, Stage> mine = skillId == null ? null : stages.get(skillId);
        return mine == null ? Map.of() : mine;
    }

    /** 這一段這個等級是主段一下的幾倍（同一個等級的效益相除）。 */
    public static double share(final SkillDefinition definition, final Stage stage, final int level) {
        final double base = definition.effectiveness().at(level);
        return base <= 0 ? 1 : stage.effectiveness().at(level) / base;
    }

    /**
     * 這一段的傷害：base（這一下原本的 profile，含輔助、強化）乘上這一段的倍數，換上這一段的轉換、修飾器。
     * 這個技能沒有這一段就原樣回傳。
     */
    public static SkillProfile profile(final SkillBuild build, final SkillProfile base, final String key) {
        final Stage stage = get(build.definition().id(), key);
        return stage == null ? base : profile(build.definition(), build.level(), base, stage);
    }

    static SkillProfile profile(final SkillDefinition definition, final int level, final SkillProfile base, final Stage stage) {
        SkillProfile result = base.scaled(share(definition, stage, level), stage.name());
        if (!stage.modifiers().isEmpty()) {
            final List<Modifier> extra = new ArrayList<>();
            for (final ScaledMod mod : stage.modifiers()) {
                extra.add(mod.toModifier(level, 1.0, stage.name()));
            }
            result = result.withLocal(extra);
        }
        if (!stage.conversion().isEmpty()) {
            result = new SkillProfile(result.id(), result.tags(), result.effectiveness(), result.spellDamage(), result.spellDamageMax(),
                    stage.conversion(), result.local());
        }
        return result;
    }

    /** 這一下整個換成這一段（連段的那一下：終結打擊、第三擊）：傷害、轉換、修飾器，再加上攻速、距離、次數的記號。 */
    public static SkillBuild apply(final SkillBuild build, final String key) {
        final Stage stage = get(build.definition().id(), key);
        if (stage == null) {
            return build;
        }
        SkillProfile profile = profile(build.definition(), build.level(), build.profile(), stage);
        final List<Modifier> marks = new ArrayList<>();
        marks.add(Modifier.flat(IS + key, 1, stage.name()));
        if (stage.speed() != 0) {
            marks.add(Modifier.flat(SPEED, stage.speed(), stage.name()));
        }
        if (stage.reach() != 0) {
            marks.add(Modifier.flat(REACH, stage.reach(), stage.name()));
        }
        if (stage.hits() > 1) {
            marks.add(Modifier.flat(HITS, stage.hits(), stage.name()));
        }
        return build.withProfile(profile.withLocal(marks));
    }

    /** 這一下身上的記號加總（沒有是 0）。 */
    public static double mark(final SkillProfile profile, final String stat) {
        double total = 0;
        for (final Modifier modifier : profile.local()) {
            if (modifier.stat().equals(stat)) {
                total += modifier.value();
            }
        }
        return total;
    }

    /** 這一下打幾次（連段的第三擊這種一下打好幾次的；至少 1）。 */
    public static int hits(final SkillProfile profile) {
        return Math.max(1, (int) Math.round(mark(profile, HITS)));
    }

    /** 這一下的出手時間是原本的幾倍（攻擊速度更少 45% → 1 ÷ 0.55）。 */
    public static double timeScale(final SkillProfile profile) {
        final double speed = mark(profile, SPEED);
        return speed == 0 ? 1.0 : 1.0 / Math.max(0.05, 1 + speed / 100.0);
    }

    /** 連段的這一下用哪一段：最後一擊（final）、三連的第三下（third），沒有回傳 null。 */
    public static String comboKey(final String skillId, final int step, final int finalAt) {
        if (finalAt <= 0 || step <= 0) {
            return null;
        }
        if (step >= finalAt && get(skillId, "final") != null) {
            return "final";
        }
        if (step == 3 && step < finalAt && get(skillId, "third") != null) {
            return "third";
        }
        return null;
    }

    /** 這一段的半徑乘上範圍加成（沒寫半徑就用 fallback，已經乘好的）。 */
    public static double radius(final SkillBuild build, final String key, final double fallback) {
        final Stage stage = get(build.definition().id(), key);
        if (stage == null || stage.radius(build.level()) <= 0) {
            return fallback;
        }
        return build.scaledRadius(stage.radius());
    }
}
