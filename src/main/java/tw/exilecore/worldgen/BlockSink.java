package tw.exilecore.worldgen;

/**
 * 放方塊的目的地（世界座標）。區塊生成時只接受落在那個區塊裡的方塊（其他的直接略過），
 * 地圖實例則是先收集起來、再分幾個 tick 放進世界。
 * <p>
 * 結構與地形都用世界座標寫，不用管自己被切在哪幾個區塊裡；{@link #intersects} 讓整個不相交的結構可以直接跳過。
 */
public interface BlockSink {

    /** 放一個方塊（{@link Palette} 代號；0 = 空氣）。範圍外的直接略過。 */
    void set(int x, int y, int z, int block);

    /** 範圍填滿（兩端都含）。 */
    default void fill(final int x1, final int y1, final int z1, final int x2, final int y2, final int z2, final int block) {
        final int minX = Math.min(x1, x2);
        final int maxX = Math.max(x1, x2);
        final int minY = Math.min(y1, y2);
        final int maxY = Math.max(y1, y2);
        final int minZ = Math.min(z1, z2);
        final int maxZ = Math.max(z1, z2);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    set(x, y, z, block);
                }
            }
        }
    }

    /** 這個水平範圍（兩端都含）有沒有任何一格會被接受。 */
    boolean intersects(int minX, int minZ, int maxX, int maxZ);
}
