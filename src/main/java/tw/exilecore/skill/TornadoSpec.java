package tw.exilecore.skill;

import tw.exilecore.core.config.YamlNode;

/**
 * 龍捲風（2026-10，照 PoE2 的龍捲射擊）：skills.yml 投射物技能的 {@code tornado:} 區塊。
 * 箭停下來的地方捲起一陣龍捲風（同時最多 {@link #max} 個，留 {@link #duration} 秒），每 {@link #interval} 秒打一次範圍內的敵人
 * （這一下的 {@link #damage}%）；你自己射的其他攻擊投射物（箭、弩箭、長矛）飛進龍捲風會被吸進去，
 * 從龍捲風往外一圈射出 {@link #copies} 支複製的（傷害更少 {@link #copyLessByLevel}%、最遠 {@link #copyRange} 格；複製的不會再被複製）。
 * 2026-10-26：copy_less 可以寫逐級表（PoE2 龍捲射擊：60%→41%→21%）。
 */
public record TornadoSpec(double duration, double interval, Scaled radius, double damage, int max, int copies, Scaled copyLessByLevel,
                          double copyRange) {

    /** 複製的傷害更少幾 %（這一級）。 */
    public double copyLess(final int level) {
        return Math.max(0, Math.min(99, copyLessByLevel.at(level)));
    }

    static TornadoSpec parse(final String where, final YamlNode node) {
        final double duration = node.decimal("duration", 15);
        final double interval = node.decimal("interval", 0.5);
        final double damage = node.decimal("damage", 30);
        final int max = node.integer("max", 1);
        final int copies = node.integer("copies", 3);
        final Scaled copyLess = Scaled.parse(where + ".copy_less", node.raw("copy_less"), 50);
        final double copyRange = node.decimal("copy_range", 6);
        if (duration <= 0 || duration > 60 || interval < 0.1 || interval > 5 || damage <= 0 || damage > 300 || max < 1 || max > 5
                || copies < 0 || copies > 12 || copyLess.min() < 0 || copyLess.first() >= 100 || copyLess.last() >= 100 || copyRange < 1 || copyRange > 30) {
            throw new IllegalArgumentException(where + "：duration 0–60、interval 0.1–5、damage 0–300、max 1–5、copies 0–12、"
                    + "copy_less 0–99、copy_range 1–30");
        }
        return new TornadoSpec(duration, interval, Scaled.parse(where + ".radius", node.raw("radius"), 2.5), damage, max, copies,
                copyLess, copyRange);
    }
}
