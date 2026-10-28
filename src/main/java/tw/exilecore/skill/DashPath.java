package tw.exilecore.skill;

/**
 * 位移的落點（純邏輯，不依賴 Bukkit）：沿著水平的面向一小步一小步走，每一步找站得住的高度
 * （可以爬上 1 格、走下最多 3 格），被牆擋住或前面沒有地（虛空、深坑）就停在最後一個能站的地方。
 */
public final class DashPath {

    /** 每一步走多遠（格）。 */
    public static final double STEP = 0.5;
    /** 一步最多爬幾格、走下幾格。 */
    public static final int CLIMB = 1;
    public static final int DROP = 3;

    /** 方塊查詢：腳底跟頭的位置能不能站（passable），腳下是不是實心（solid）。 */
    public interface Blocks {
        boolean passable(int x, int y, int z);

        boolean solid(int x, int y, int z);
    }

    /** 落點：[x, y, z]（y 是腳底的整數高度；x、z 是實際座標）；走不出去（第一步就被擋）回傳 null。 */
    public static double[] destination(final double startX, final double startY, final double startZ,
                                       final double dirX, final double dirZ, final double range, final Blocks blocks) {
        return destination(startX, startY, startZ, dirX, dirZ, range, blocks, STEP);
    }

    /**
     * 2026-10-28：同上，每一步走 {@code step} 格（近戰條件位移 {@link MeleeStep}：踏 1.7 格要剛好走到 1.7，
     * 用 {@link #evenStep(double)} 把距離切成不超過 0.5 格的等分；這次之前只有固定 0.5 格一步）。
     */
    public static double[] destination(final double startX, final double startY, final double startZ,
                                       final double dirX, final double dirZ, final double range, final Blocks blocks, final double step) {
        final double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length < 1e-9 || range <= 0) {
            return null;
        }
        final double ux = dirX / length;
        final double uz = dirZ / length;
        double x = startX;
        double z = startZ;
        int y = (int) Math.floor(startY + 1e-6);
        double[] best = null;
        final double stride = step > 1e-3 ? Math.min(STEP, step) : STEP;
        for (double travelled = stride; travelled <= range + 1e-9; travelled += stride) {
            final double nx = startX + ux * travelled;
            final double nz = startZ + uz * travelled;
            final int bx = (int) Math.floor(nx);
            final int bz = (int) Math.floor(nz);
            final int ny = standableNear(bx, y, bz, blocks);
            if (ny == Integer.MIN_VALUE || !roomy(nx, ny, nz, blocks)) {
                break; // 牆、深坑、虛空、身體會卡進牆（2026-10）：停在上一步
            }
            x = nx;
            z = nz;
            y = ny;
            best = new double[] {x, y, z};
        }
        return best;
    }

    /** 把 range 切成不超過 {@link #STEP} 的等分，最後一步剛好走到 range。 */
    public static double evenStep(final double range) {
        return range <= 0 ? STEP : range / Math.max(1, Math.ceil(range / STEP - 1e-9));
    }

    /** 從 y 往上 CLIMB、往下 DROP 找站得住的高度（先看同高度，再往上，再往下）；沒有回傳 MIN_VALUE。 */
    static int standableNear(final int x, final int y, final int z, final Blocks blocks) {
        if (standable(x, y, z, blocks)) {
            return y;
        }
        for (int up = 1; up <= CLIMB; up++) {
            if (standable(x, y + up, z, blocks)) {
                return y + up;
            }
        }
        for (int down = 1; down <= DROP; down++) {
            if (standable(x, y - down, z, blocks)) {
                return y - down;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 玩家的半個身寬（碰撞箱 0.6 格）。 */
    public static final double HALF_WIDTH = 0.3;

    /**
     * 整個身體放得下（2026-10）：以前只看身體中心那一格，貼著牆停下來時碰撞箱的邊會插進旁邊的方塊，
     * 自製地牢（星圖）的窄走廊常常因此卡進牆裡。碰撞箱四個角（往內縮一點點）所在的格子，腳跟頭都要是空的。
     */
    static boolean roomy(final double x, final int y, final double z, final Blocks blocks) {
        final double r = HALF_WIDTH - 0.01;
        for (final double dx : new double[] {-r, r}) {
            for (final double dz : new double[] {-r, r}) {
                final int cx = (int) Math.floor(x + dx);
                final int cz = (int) Math.floor(z + dz);
                if (!blocks.passable(cx, y, cz) || !blocks.passable(cx, y + 1, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 腳與頭都是空的、腳下是實心的。 */
    static boolean standable(final int x, final int y, final int z, final Blocks blocks) {
        return blocks.passable(x, y, z) && blocks.passable(x, y + 1, z) && blocks.solid(x, y - 1, z);
    }

    private DashPath() {
    }
}
