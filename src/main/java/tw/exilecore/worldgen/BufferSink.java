package tw.exilecore.worldgen;

import java.util.Arrays;

/**
 * 先把要放的方塊記下來（地圖實例：主執行緒再分幾個 tick 放進世界；單元測試：直接檢查內容）。
 * 同一格放兩次時以最後一次為準（{@link #compact()} 之後）。可以限定水平範圍，範圍外的直接略過。
 */
public final class BufferSink implements BlockSink {

    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private int[] data = new int[4 * 1024];
    private int size;

    /** 不限範圍。 */
    public BufferSink() {
        this(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public BufferSink(final int minX, final int minZ, final int maxX, final int maxZ) {
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
    }

    @Override
    public void set(final int x, final int y, final int z, final int block) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return;
        }
        if (size * 4 + 4 > data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        final int index = size * 4;
        data[index] = x;
        data[index + 1] = y;
        data[index + 2] = z;
        data[index + 3] = block;
        size++;
    }

    @Override
    public boolean intersects(final int areaMinX, final int areaMinZ, final int areaMaxX, final int areaMaxZ) {
        return areaMaxX >= minX && areaMinX <= maxX && areaMaxZ >= minZ && areaMinZ <= maxZ;
    }

    /** 記了幾筆（含重複的格子）。 */
    public int size() {
        return size;
    }

    public int x(final int index) {
        return data[index * 4];
    }

    public int y(final int index) {
        return data[index * 4 + 1];
    }

    public int z(final int index) {
        return data[index * 4 + 2];
    }

    public int block(final int index) {
        return data[index * 4 + 3];
    }

    /**
     * 去掉重複的格子（保留最後一次），並依 x、z、y 排序，讓放置時同一個區塊的方塊擠在一起。
     */
    public void compact() {
        final Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            final int ax = x(a) >> 4;
            final int bx = x(b) >> 4;
            if (ax != bx) {
                return Integer.compare(ax, bx);
            }
            final int az = z(a) >> 4;
            final int bz = z(b) >> 4;
            if (az != bz) {
                return Integer.compare(az, bz);
            }
            if (x(a) != x(b)) {
                return Integer.compare(x(a), x(b));
            }
            if (z(a) != z(b)) {
                return Integer.compare(z(a), z(b));
            }
            if (y(a) != y(b)) {
                return Integer.compare(y(a), y(b));
            }
            return Integer.compare(a, b);
        });
        final int[] result = new int[Math.max(4, size * 4)];
        int count = 0;
        for (int i = 0; i < order.length; i++) {
            final int current = order[i];
            final boolean lastOfCell = i + 1 >= order.length
                    || x(order[i + 1]) != x(current) || y(order[i + 1]) != y(current) || z(order[i + 1]) != z(current);
            if (!lastOfCell) {
                continue;
            }
            result[count * 4] = x(current);
            result[count * 4 + 1] = y(current);
            result[count * 4 + 2] = z(current);
            result[count * 4 + 3] = block(current);
            count++;
        }
        data = result;
        size = count;
    }

    /** 某一格最後放的方塊；沒放過回傳 -1。（測試用，線性搜尋） */
    public int blockAt(final int x, final int y, final int z) {
        for (int i = size - 1; i >= 0; i--) {
            if (x(i) == x && y(i) == y && z(i) == z) {
                return block(i);
            }
        }
        return -1;
    }
}
