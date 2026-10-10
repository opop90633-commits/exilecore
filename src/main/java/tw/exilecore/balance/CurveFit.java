package tw.exilecore.balance;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 找一組錨點，讓等比內插出來的曲線最貼近目標（對數空間的最小平方法）。
 * 參考角色的輸出在拿到新輔助孔、新階級的那幾級會跳一下；用固定的錨點去貼，怪物的曲線還是平滑的，
 * 玩家變強的那一刻就感覺得到。純函式，不依賴 Bukkit。
 */
public final class CurveFit {

    private CurveFit() {
    }

    /**
     * @param anchors 錨點等級（遞增，至少一個）
     * @param targets 等級 → 目標值（正數；0 與負數略過）
     * @return 錨點等級 → 數值；目標是空的回傳空表
     */
    public static NavigableMap<Integer, Double> fit(final int[] anchors, final Map<Integer, Double> targets) {
        final int n = anchors.length;
        final double[][] a = new double[n][n];
        final double[] b = new double[n];
        int samples = 0;
        for (final Map.Entry<Integer, Double> entry : targets.entrySet()) {
            final double value = entry.getValue();
            if (!(value > 0)) {
                continue;
            }
            samples++;
            final double y = Math.log(value);
            final int level = entry.getKey();
            // 這個等級的曲線值 = w0 × 錨點 i 的對數 + w1 × 錨點 i+1 的對數
            int i = 0;
            while (i + 1 < n && anchors[i + 1] <= level) {
                i++;
            }
            if (level <= anchors[0] || i + 1 >= n) {
                final int only = level <= anchors[0] ? 0 : n - 1;
                a[only][only] += 1;
                b[only] += y;
                continue;
            }
            final double t = (level - anchors[i]) / (double) (anchors[i + 1] - anchors[i]);
            final double w0 = 1 - t;
            final double w1 = t;
            a[i][i] += w0 * w0;
            a[i][i + 1] += w0 * w1;
            a[i + 1][i] += w0 * w1;
            a[i + 1][i + 1] += w1 * w1;
            b[i] += w0 * y;
            b[i + 1] += w1 * y;
        }
        final NavigableMap<Integer, Double> result = new TreeMap<>();
        if (samples == 0) {
            return result;
        }
        // 沒有資料撐住的錨點：輕輕拉向相鄰錨點（很小的平滑項），矩陣才解得出來
        final double smooth = 1e-6;
        for (int i = 0; i + 1 < n; i++) {
            a[i][i] += smooth;
            a[i + 1][i + 1] += smooth;
            a[i][i + 1] -= smooth;
            a[i + 1][i] -= smooth;
        }
        for (int i = 0; i < n; i++) {
            a[i][i] += 1e-12;
        }
        final double[] x = solve(a, b);
        for (int i = 0; i < n; i++) {
            result.put(anchors[i], Math.exp(x[i]));
        }
        return result;
    }

    /** 高斯消去法（部分選主元）。 */
    private static double[] solve(final double[][] matrix, final double[] vector) {
        final int n = vector.length;
        final double[][] a = new double[n][];
        for (int i = 0; i < n; i++) {
            a[i] = matrix[i].clone();
        }
        final double[] b = vector.clone();
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            final double[] swapRow = a[col];
            a[col] = a[pivot];
            a[pivot] = swapRow;
            final double swap = b[col];
            b[col] = b[pivot];
            b[pivot] = swap;
            final double diagonal = a[col][col];
            if (Math.abs(diagonal) < 1e-300) {
                continue;
            }
            for (int row = col + 1; row < n; row++) {
                final double factor = a[row][col] / diagonal;
                if (factor == 0) {
                    continue;
                }
                for (int k = col; k < n; k++) {
                    a[row][k] -= factor * a[col][k];
                }
                b[row] -= factor * b[col];
            }
        }
        final double[] x = new double[n];
        for (int row = n - 1; row >= 0; row--) {
            double sum = b[row];
            for (int k = row + 1; k < n; k++) {
                sum -= a[row][k] * x[k];
            }
            x[row] = Math.abs(a[row][row]) < 1e-300 ? 0 : sum / a[row][row];
        }
        return x;
    }
}
