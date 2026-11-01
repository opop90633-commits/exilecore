package tw.exilecore.ailment;

import tw.exilecore.stats.DamagePacket;
import tw.exilecore.stats.DamageType;
import tw.exilecore.stats.SkillProfile;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 技能說明裡的元素異常加成（只列有加成的）。純函式。 */
public final class AilmentText {

    private AilmentText() {
    }

    /**
     * @param effective 疊好輔助寶石與技能修飾器的屬性表
     * @param expected  技能平均打出的各類型傷害（決定這個技能會不會造成哪種異常）
     */
    public static List<String> lines(final StatSheet effective, final SkillProfile profile, final DamagePacket expected) {
        final List<String> lines = new ArrayList<>();
        final boolean noElemental = StatKeys.flag(effective, StatKeys.FLAG_NO_AILMENTS);
        if (noElemental && (expected.get(DamageType.FIRE) > 0 || expected.get(DamageType.COLD) > 0 || expected.get(DamageType.LIGHTNING) > 0)) {
            lines.add("<gray>不能造成元素異常");
        }
        if (!noElemental && expected.get(DamageType.FIRE) > 0) {
            final Set<String> context = profile.contextFor(DamageType.FIRE);
            final List<String> parts = new ArrayList<>();
            flat(parts, "機率", effective.flat(StatKeys.IGNITE_CHANCE, context));
            increased(parts, "機率", effective.increased(StatKeys.IGNITE_CHANCE, context));
            more(parts, "機率", effective.more(StatKeys.IGNITE_CHANCE, context));
            multiplier(parts, "傷害", effective.apply(StatKeys.IGNITE_MAGNITUDE, 1.0, context));
            increased(parts, "持續", effective.increased(StatKeys.IGNITE_DURATION, context) + effective.increased(StatKeys.AILMENT_DURATION, context));
            if (AilmentService.chaosIgnite(effective)) {
                parts.add("造成混沌傷害而非火焰傷害"); // 2026-10-28 照 PoE2 Blackflame（黯炎）
            }
            add(lines, AilmentType.IGNITE, parts);
        }
        if (!noElemental && expected.get(DamageType.COLD) > 0) {
            final Set<String> context = profile.contextFor(DamageType.COLD);
            final List<String> chill = new ArrayList<>();
            increased(chill, "效果", effective.increased(StatKeys.CHILL_EFFECT, context));
            more(chill, "效果", effective.more(StatKeys.CHILL_EFFECT, context));
            increased(chill, "持續", effective.increased(StatKeys.CHILL_DURATION, context) + effective.increased(StatKeys.AILMENT_DURATION, context));
            add(lines, AilmentType.CHILL, chill);
            final List<String> freeze = new ArrayList<>();
            multiplier(freeze, "累積", effective.apply(StatKeys.FREEZE_BUILDUP, 1.0, context));
            increased(freeze, "持續", effective.increased(StatKeys.FREEZE_DURATION, context) + effective.increased(StatKeys.AILMENT_DURATION, context));
            add(lines, AilmentType.FREEZE, freeze);
        }
        if (!noElemental && expected.get(DamageType.LIGHTNING) > 0) {
            final Set<String> context = profile.contextFor(DamageType.LIGHTNING);
            final List<String> parts = new ArrayList<>();
            flat(parts, "機率", effective.flat(StatKeys.SHOCK_CHANCE, context));
            increased(parts, "機率", effective.increased(StatKeys.SHOCK_CHANCE, context));
            more(parts, "機率", effective.more(StatKeys.SHOCK_CHANCE, context));
            increased(parts, "效果", effective.increased(StatKeys.SHOCK_EFFECT, context));
            more(parts, "效果", effective.more(StatKeys.SHOCK_EFFECT, context));
            increased(parts, "持續", effective.increased(StatKeys.SHOCK_DURATION, context) + effective.increased(StatKeys.AILMENT_DURATION, context));
            add(lines, AilmentType.SHOCK, parts);
        }
        // 流血（擊中的物理，攻擊、法術都可以：2026-10-27 照 PoE2）與中毒（物理＋混沌）：沒有機率來源就不會發生，所以只在有機率時列出
        if (expected.get(DamageType.PHYSICAL) > 0) {
            final Set<String> context = profile.contextFor(DamageType.PHYSICAL);
            final List<String> parts = new ArrayList<>();
            flat(parts, "機率", effective.flat(StatKeys.BLEED_CHANCE, context));
            if (!parts.isEmpty()) {
                increased(parts, "機率", effective.increased(StatKeys.BLEED_CHANCE, context));
                more(parts, "機率", effective.more(StatKeys.BLEED_CHANCE, context));
                multiplier(parts, "傷害", effective.apply(StatKeys.BLEED_MAGNITUDE, 1.0, context));
                increased(parts, "持續", effective.increased(StatKeys.BLEED_DURATION, context) + effective.increased(StatKeys.AILMENT_DURATION, context));
                add(lines, AilmentType.BLEED, parts);
            }
        }
        if (expected.get(DamageType.PHYSICAL) > 0 || expected.get(DamageType.CHAOS) > 0) {
            final Set<String> context = profile.contextFor(DamageType.CHAOS);
            final List<String> parts = new ArrayList<>();
            flat(parts, "機率", effective.flat(StatKeys.POISON_CHANCE, context));
            if (!parts.isEmpty()) {
                increased(parts, "機率", effective.increased(StatKeys.POISON_CHANCE, context));
                more(parts, "機率", effective.more(StatKeys.POISON_CHANCE, context));
                multiplier(parts, "傷害", effective.apply(StatKeys.POISON_MAGNITUDE, 1.0, context));
                increased(parts, "持續", effective.increased(StatKeys.POISON_DURATION, context) + effective.increased(StatKeys.AILMENT_DURATION, context));
                add(lines, AilmentType.POISON, parts);
            }
        }
        // 暈眩：任何傷害都會累積，只在有加成時列；電擊：要有電擊機率才會累積
        final boolean anyDamage = expected.get(DamageType.PHYSICAL) > 0 || expected.get(DamageType.FIRE) > 0 || expected.get(DamageType.COLD) > 0
                || expected.get(DamageType.LIGHTNING) > 0 || expected.get(DamageType.CHAOS) > 0;
        if (anyDamage) {
            final Set<String> context = profile.contextFor(DamageType.PHYSICAL);
            final List<String> parts = new ArrayList<>();
            multiplier(parts, "累積", effective.apply(StatKeys.STUN_BUILDUP, 1.0, context));
            increased(parts, "持續", effective.increased(StatKeys.STUN_DURATION, context));
            add(lines, AilmentType.STUN, parts);
        }
        // 2026-11-01 照 PoE2：定身（骸骨連鎖的物理法術暴擊、擊中必定定身、投射物攻擊）——跟 HitEffects 同一個判斷（PinKeys.source）；
        //   這次之前面板不列，看不出骸骨連鎖有沒有作用
        if (anyDamage) {
            lines.addAll(pinLines(effective, profile.tags()));
        }
        if (expected.get(DamageType.LIGHTNING) > 0) {
            final Set<String> context = profile.contextFor(DamageType.LIGHTNING);
            final List<String> parts = new ArrayList<>();
            flat(parts, "機率", effective.flat(StatKeys.ELECTROCUTE_CHANCE, context));
            if (!parts.isEmpty()) {
                increased(parts, "機率", effective.increased(StatKeys.ELECTROCUTE_CHANCE, context));
                more(parts, "機率", effective.more(StatKeys.ELECTROCUTE_CHANCE, context));
                multiplier(parts, "累積", effective.apply(StatKeys.ELECTROCUTE_BUILDUP, 1.0, context));
                increased(parts, "持續", effective.increased(StatKeys.ELECTROCUTE_DURATION, context) + effective.increased(StatKeys.AILMENT_DURATION, context));
                add(lines, AilmentType.ELECTROCUTE, parts);
            }
        }
        return lines;
    }

    /** 2026-11-01：定身的顏色（不是 AilmentType：定身是禁錮，不是異常）。 */
    static final String PIN_COLOR = "#c9a27e";

    /**
     * 2026-11-01：會造成傷害的技能的定身說明（沒有定身來源回傳空的）。effective 是你的屬性表疊上技能（骸骨連鎖這種天賦只給你自己打的）。
     * 累積倍率跟 HitEffects 一樣：定身累積（增加、更多）× 當作傷害更多；遠釘照距離另算，不列。
     */
    public static List<String> pinLines(final StatSheet effective, final Set<String> tags) {
        final tw.exilecore.stats.PinKeys.Source source = tw.exilecore.stats.PinKeys.source(
                StatKeys.flag(effective, tw.exilecore.stats.PinKeys.FLAG_HITS_PIN),
                StatKeys.flag(effective, tw.exilecore.stats.TreeNewMechanicKeys.FLAG_SPELL_CRIT_PIN),
                effective.flat(StatKeys.PIN_CHANCE, tags), tags, StatKeys.flag(effective, tw.exilecore.stats.SupportExtraKeys.FLAG_CANNOT_PIN));
        if (source == tw.exilecore.stats.PinKeys.Source.NONE) {
            return List.of();
        }
        final List<String> parts = new ArrayList<>();
        parts.add(switch (source) {
            case SPELL_CRIT -> "暴擊時累積（物理法術；所有傷害類型都算）";
            case EVERY_HIT -> "擊中都會累積（所有傷害類型都算）";
            default -> "擊中時累積（只算物理）";
        });
        multiplier(parts, "累積", Math.max(0, effective.apply(tw.exilecore.stats.TreeKeys.PIN_BUILDUP, 1.0, tags))
                * tw.exilecore.stats.PinKeys.asThoughMultiplier(effective.flat(tw.exilecore.stats.PinKeys.PIN_AS_THOUGH, tags)));
        increased(parts, "持續", effective.increased(tw.exilecore.stats.SupportKeys.PIN_DURATION, tags));
        return List.of("<" + PIN_COLOR + ">定身<gray> " + String.join("、", parts));
    }

    private static void add(final List<String> lines, final AilmentType type, final List<String> parts) {
        if (!parts.isEmpty()) {
            lines.add("<" + type.color() + ">" + type.displayName() + "<gray> " + String.join("、", parts));
        }
    }

    private static void flat(final List<String> parts, final String what, final double percentPoints) {
        if (Math.abs(percentPoints) >= 0.05) {
            parts.add(what + String.format(Locale.ROOT, " %+.0f%%", percentPoints));
        }
    }

    private static void increased(final List<String> parts, final String what, final double fraction) {
        if (Math.abs(fraction) >= 0.005) {
            parts.add(what + String.format(Locale.ROOT, " %s%d%%", fraction >= 0 ? "增加 " : "減少 ", Math.round(Math.abs(fraction) * 100)));
        }
    }

    /** 2026-10-27：「更多／更少」（輔助寶石的導電「更多 100% 感電機率」這種）；這次之前不列，面板看不出輔助有作用。 */
    private static void more(final List<String> parts, final String what, final double multiplier) {
        if (Math.abs(multiplier - 1.0) >= 0.005) {
            parts.add(what + String.format(Locale.ROOT, " %s%d%%", multiplier >= 1 ? "更多 " : "更少 ", Math.round(Math.abs(multiplier - 1) * 100)));
        }
    }

    private static void multiplier(final List<String> parts, final String what, final double multiplier) {
        if (Math.abs(multiplier - 1.0) >= 0.005) {
            parts.add(what + String.format(Locale.ROOT, " ×%.2f", multiplier));
        }
    }
}
