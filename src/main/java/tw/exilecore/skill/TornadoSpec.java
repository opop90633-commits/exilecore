package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;
import tw.exilecore.stats.StatKeys;
import tw.exilecore.stats.StatSheet;
import tw.exilecore.stats.Tags;

import java.util.Set;

/**
 * 龍捲風（2026-10，照 PoE2 的龍捲射擊）：skills.yml 投射物技能的 {@code tornado:} 區塊。
 * 箭停下來的地方捲起一陣龍捲風（同時最多 {@link #max} 個，留 {@link #duration} 秒）；你自己射的其他攻擊投射物（箭、弩箭、長矛）
 * 飛進龍捲風會被吸進去，從龍捲風往外一圈射出 {@link #copies} 支複製的（傷害更少 {@link #copyLessByLevel}%、最遠 {@link #copyRange} 格；
 * 複製的不會再被複製）。2026-10-26：copy_less 可以寫逐級表（PoE2 龍捲射擊：60%→41%→21%）。
 * <p>
 * 2026-10-28 照 PoE2（poe2db／RePoE Tornado Shot）：龍捲風本身是物理持續傷害，每秒基礎傷害照逐級表 {@link #dpsByLevel}
 * （base_physical_damage_to_deal_per_minute ÷ 60：4.3 → 208.7 → 4450.3），吃物理、投射物、一般傷害的加成（不暴擊、不算擊中），
 * 每 {@link #interval} 秒結算一次並阻礙裡面的敵人；被吸了 {@link #hitsByLevel} 支投射物（tornado_shot_number_of_hits_allowed 5 → 8 → 12）就消失。
 * 這次之前每 interval 秒「擊中」裡面的敵人一次（箭的傷害的 damage%＝20%），龍捲風不會被吸到消失。
 */
public record TornadoSpec(double duration, double interval, Scaled radius, Scaled dpsByLevel, Scaled hitsByLevel, int max, int copies,
                          Scaled copyLessByLevel, double copyRange) {

    /** FLAT（百分點）：複製的投射物傷害「更多」（品質；跟等級的「更少」是同一條原作屬性，相加）。 */
    public static final String COPY_MORE = "tornado.copy_more";
    /** FLAT：龍捲風上限 +x（進階奇術的品質；小數捨去）。 */
    public static final String LIMIT = "tornado.limit";
    /** 龍捲風的持續傷害吃哪些標籤的加成：物理、投射物（原作「投射物傷害的加成套用在持續傷害上」）、持續；不是攻擊、不是範圍傷害。 */
    public static final Set<String> DOT_CONTEXT = Set.of(Tags.PHYSICAL, Tags.PROJECTILE, Tags.DURATION, "wind");

    /** 複製的傷害更少幾 %（這一級）。 */
    public double copyLess(final int level) {
        return Math.max(0, Math.min(99, copyLessByLevel.at(level)));
    }

    /** 2026-10-28：複製的投射物的傷害倍率（等級的更少 − 品質的更多，同一條屬性相加）。 */
    public double copyFactor(final int level, final double qualityMore) {
        return Math.max(0, 1 + (Math.max(0, qualityMore) - copyLess(level)) / 100.0);
    }

    /** 2026-10-28：龍捲風每秒的基礎物理傷害（這一級，加成之前）。 */
    public double dps(final int level) {
        return Math.max(0, dpsByLevel.at(level));
    }

    /** 2026-10-28：龍捲風被吸幾支投射物就消失（這一級）。 */
    public int hits(final int level) {
        return Math.max(1, hitsByLevel.intAt(level));
    }

    /** 2026-10-28：同時最多幾個龍捲風（＋品質的上限，小數捨去）。 */
    public int limit(final double bonus) {
        return max + (int) Math.floor(Math.max(0, bonus) + 1e-9);
    }

    /**
     * 2026-10-28：龍捲風每秒的物理持續傷害（加成之後）：基礎 × 這個技能（含輔助）的物理、投射物、一般傷害的增加與更多 × 非異常持續傷害更多。
     *
     * @param effective 施放者的屬性表疊上這個技能的輔助與品質
     * @param dotMore   非異常持續傷害的更多（急蝕；1 = 沒有）
     */
    public static double scaledDps(final StatSheet effective, final double base, final double dotMore) {
        if (base <= 0) {
            return 0;
        }
        final double scaled = effective == null ? base : effective.apply(StatKeys.DAMAGE, base, DOT_CONTEXT);
        return Math.max(0, scaled * Math.max(0, dotMore));
    }

    static TornadoSpec parse(final String where, final YamlNode node) {
        final double duration = node.decimal("duration", 15);
        final double interval = node.decimal("interval", 0.5);
        final Scaled dps = Scaled.parse(where + ".dps", node.raw("dps"), 0);
        final Scaled hits = Scaled.parse(where + ".hits", node.raw("hits"), 5);
        final int max = node.integer("max", 1);
        final int copies = node.integer("copies", 3);
        final Scaled copyLess = Scaled.parse(where + ".copy_less", node.raw("copy_less"), 50);
        final double copyRange = node.decimal("copy_range", 6);
        if (duration <= 0 || duration > 60 || interval < 0.1 || interval > 5 || dps.min() < 0 || dps.last() <= 0 || hits.min() < 1
                || max < 1 || max > 5 || copies < 0 || copies > 12 || copyLess.min() < 0 || copyLess.first() >= 100 || copyLess.last() >= 100
                || copyRange < 1 || copyRange > 30) {
            throw new IllegalArgumentException(where + "：duration 0–60、interval 0.1–5、dps 要寫（每秒物理傷害，> 0）、hits ≥ 1、max 1–5、"
                    + "copies 0–12、copy_less 0–99、copy_range 1–30");
        }
        return new TornadoSpec(duration, interval, Scaled.parse(where + ".radius", node.raw("radius"), 2.5), dps, hits, max, copies,
                copyLess, copyRange);
    }
}
