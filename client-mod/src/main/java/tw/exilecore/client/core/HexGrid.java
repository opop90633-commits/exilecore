package tw.exilecore.client.core;

/**
 * 2026-11-02 照站長（D 組）：星圖原生畫面的六角格數學（尖頂朝上的六角形，軸座標 (q, r)：跟插件的 AtlasGrid 一樣，
 * 相鄰是 (+1,0) (+1,−1) (0,−1) (−1,0) (−1,+1) (0,+1)，r 往下變大）。純 Java（測試用）。
 * 這次之前的箱子選單用「奇數列往右半格」模擬，看起來是方格；這裡照真正的六角形排。
 */
public final class HexGrid {

    /** 跟 AtlasGrid.DIRECTIONS 同順序。 */
    public static final int[][] DIRECTIONS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
    static final double SQRT3 = Math.sqrt(3);

    private HexGrid() {
    }

    /** 六角形中心的 x（外接圓半徑 1）。 */
    public static double x(final int q, final int r) {
        return SQRT3 * (q + r / 2.0);
    }

    public static double y(final int q, final int r) {
        return 1.5 * r;
    }

    /** 世界座標落在哪一格：{q, r}（四捨五入到最近的六角形）。 */
    public static int[] at(final double x, final double y) {
        final double fq = (SQRT3 / 3 * x - y / 3);
        final double fr = 2.0 / 3 * y;
        return round(fq, fr);
    }

    /** 立方座標四捨五入。 */
    static int[] round(final double fq, final double fr) {
        final double fs = -fq - fr;
        long q = Math.round(fq);
        long r = Math.round(fr);
        final long s = Math.round(fs);
        final double dq = Math.abs(q - fq);
        final double dr = Math.abs(r - fr);
        final double ds = Math.abs(s - fs);
        if (dq > dr && dq > ds) {
            q = -r - s;
        } else if (dr > ds) {
            r = -q - s;
        }
        return new int[] {(int) q, (int) r};
    }

    public static int distance(final int q, final int r) {
        return (Math.abs(q) + Math.abs(r) + Math.abs(q + r)) / 2;
    }

    /** 給玩家看的座標（跟箱子選單一樣：右＝+x、上＝+y，營地 (0, 0)）。 */
    public static String where(final int q, final int r) {
        return "(" + (q + (r - (r & 1)) / 2) + ", " + (-r) + ")";
    }

    /**
     * 往第 direction 個鄰居的那一條邊的兩個角（外接圓半徑 radius，以格子中心為原點）：{x1, y1, x2, y2}。
     * 尖頂朝上的六角形的角在 30° + 60°k，鄰居在邊的正對面。
     */
    public static double[] edge(final int direction, final double radius) {
        final int[] d = DIRECTIONS[direction];
        final double angle = Math.atan2(y(d[0], d[1]), x(d[0], d[1]));
        final double a1 = angle - Math.PI / 6;
        final double a2 = angle + Math.PI / 6;
        return new double[] {Math.cos(a1) * radius, Math.sin(a1) * radius, Math.cos(a2) * radius, Math.sin(a2) * radius};
    }
}
