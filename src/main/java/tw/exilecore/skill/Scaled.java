package tw.exilecore.skill;

import tw.exilecore.character.SkillLoadout;

import java.util.Arrays;
import java.util.List;

/**
 * 隨技能等級變化的數值。三種寫法：
 * <ul>
 *   <li>{@code 1.5}：固定值；</li>
 *   <li>{@code [等級 1, 等級 20]}：線性內插，裝備的「+x 技能等級」讓等級超過 20 時照同樣的趨勢往外推
 *       （最高 {@link SkillBuild#MAX_EFFECTIVE_LEVEL}），本來是正的數值推出去不會變成負的；</li>
 *   <li>{@code [等級 1, 等級 2, 等級 3, …]}（三個以上）：逐級表（2026-10-26：照 PoE2 的寶石表，1～40 級每一級都寫，不再內插）。
 *       比表長的等級用最後兩級的比例往外推。</li>
 * </ul>
 *
 * @param first 等級 1 的值
 * @param last  等級 20 的值（逐級表比 20 短的話是最後一級）
 * @param table 逐級表（index 0 = 等級 1）；null = 兩端內插
 */
public record Scaled(double first, double last, double[] table) {

    public Scaled(final double first, final double last) {
        this(first, last, null);
    }

    public static Scaled of(final double value) {
        return new Scaled(value, value);
    }

    /** 逐級表。 */
    public static Scaled table(final double[] values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("逐級表不能是空的");
        }
        final double[] copy = values.clone();
        return new Scaled(copy[0], copy[Math.min(SkillLoadout.MAX_LEVEL, copy.length) - 1], copy);
    }

    public boolean isTable() {
        return table != null;
    }

    public double at(final int level) {
        final int clamped = Math.max(1, Math.min(SkillBuild.MAX_EFFECTIVE_LEVEL, level));
        if (table != null) {
            if (clamped <= table.length) {
                return table[clamped - 1];
            }
            final double end = table[table.length - 1];
            if (table.length < 2 || table[table.length - 2] <= 0 || end <= 0) {
                return end;
            }
            return end * Math.pow(end / table[table.length - 2], clamped - table.length);
        }
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
        if (table != null) {
            return Arrays.stream(table).allMatch(v -> v == 0);
        }
        return first == 0 && last == 0;
    }

    /** 最小值（驗證用：逐級表看每一級）。 */
    public double min() {
        return table != null ? Arrays.stream(table).min().orElse(0) : Math.min(first, last);
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Scaled s && s.first == first && s.last == last && Arrays.equals(s.table, table);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Double.hashCode(first) + Double.hashCode(last)) + Arrays.hashCode(table);
    }

    @Override
    public String toString() {
        return table != null ? "Scaled" + Arrays.toString(table) : "Scaled[" + first + ", " + last + "]";
    }

    /** 從 YAML 的原始值讀：數字、兩個數字的清單，或三個以上數字的逐級表。 */
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
        if (raw instanceof List<?> list && list.size() >= 3 && list.size() <= SkillBuild.MAX_EFFECTIVE_LEVEL
                && list.stream().allMatch(v -> v instanceof Number)) {
            return table(list.stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray());
        }
        throw new IllegalArgumentException(where + "：要寫一個數字、[等級 1 的值, 等級 20 的值]，或從等級 1 開始的逐級表（3～"
                + SkillBuild.MAX_EFFECTIVE_LEVEL + " 個數字），卻是「" + raw + "」");
    }
}
