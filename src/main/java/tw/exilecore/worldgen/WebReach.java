package tw.exilecore.worldgen;

/**
 * 蜘蛛網碰不碰得到（2026-10，「跑步會拉腳」）：純邏輯，劇情世界區塊載入時的清理（world.WalkableCleanup）與單元測試共用。
 * <p>
 * 站在一格方塊上（腳在上面那格）：走路時腳、頭碰得到上面 1～2 格，跳到最高頭頂大約在上面 4 格多（碰到蜘蛛網會整個停住）；
 * 身體寬 0.6 格，站在格子邊上會伸進隔壁的格子（含斜對角）。所以蜘蛛網這一格與周圍八格裡，
 * 只要有一格在蜘蛛網底下 1～{@link #REACH} 格有站得上去的方塊，就碰得到。
 * 站得上去：擋路的方塊、不是樹葉（爬不上樹頂）、上面兩格不擋路（站得下）。
 */
public final class WebReach {

    /** 跳起來頭頂碰得到腳下那格往上幾格（地形那邊垂下來的蜘蛛網至少離地 {@link CampaignTerrain#WEB_CLEARANCE} 格）。 */
    public static final int REACH = CampaignTerrain.WEB_CLEARANCE - 1;

    /** 查方塊（世界座標）。 */
    public interface Blocks {
        /** 擋路的方塊（樹葉也算；蜘蛛網、草、地毯、一層雪不算）。 */
        boolean solid(int x, int y, int z);

        /** 樹葉。 */
        boolean leaves(int x, int y, int z);
    }

    private WebReach() {
    }

    /** 站得上去：擋路、不是樹葉、上面兩格不擋路。 */
    public static boolean standable(final Blocks blocks, final int x, final int y, final int z) {
        return blocks.solid(x, y, z) && !blocks.leaves(x, y, z) && !blocks.solid(x, y + 1, z) && !blocks.solid(x, y + 2, z);
    }

    /** 這格蜘蛛網碰得到：自己與周圍八格，底下 1～{@link #REACH} 格有站得上去的方塊。 */
    public static boolean reachable(final Blocks blocks, final int x, final int y, final int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int below = 1; below <= REACH; below++) {
                    if (standable(blocks, x + dx, y - below, z + dz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
