package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;

import java.util.List;

/**
 * 隨技能等級變化的數值：等級 1 的值到等級 20 的值線性內插。
 * skills.yml 寫成 {@code [等級 1, 等級 20]}，只寫一個數字就是固定值。
 * 裝備的「+x 技能等級」讓等級超過 20 時照同樣的趨勢往外推（最高 {@link SkillBuild#MAX_EFFECTIVE_LEVEL}），
 * 本來是正的數值推出去不會變成負的。
 */
public record Scaled(double first, double last) {

    public static Scaled of(final double value) {
        return new Scaled(value, value);
    }

    public double at(final int level) {
        final int clamped = Math.max(1, Math.min(SkillBuild.MAX_EFFECTIVE_LEVEL, level));
        final double t = (clamped - 1) / (double) (SkillLoadout.MAX_LEVEL - 1);
        final double value = first + (last - first) * t;
        if (clamped > SkillLoadout.MAX_LEVEL && first >= 0 && last >= 0) {
            return Math.max(0, value);
        }
        return value;
    }

    /** 整數值（無條件捨去），例如投射物數量。 */
    public int intAt(final int level) {
        return (int) Math.floor(at(level) + 1e-9);
    }

    public boolean isZero() {
        return first == 0 && last == 0;
    }

    /** 從 YAML 的原始值讀：數字或兩個數字的清單。 */
    public static Scaled parse(final String where, final Object raw, final double def) {
        if (raw == null) {
            return of(def);
        }
        if (raw instanceof Number number) {
            return of(number.doubleValue());
        }
        if (raw instanceof List<?> list && list.size() == 2
                && list.get(0) instanceof Number a && list.get(1) instanceof Number b) {
            return new Scaled(a.doubleValue(), b.doubleValue());
        }
        throw new IllegalArgumentException(where + "：要寫一個數字，或 [等級 1 的值, 等級 20 的值]，卻是「" + raw + "」");
    }
}
