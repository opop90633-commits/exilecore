package tw.exilecore.core;

/**
 * 2026-10-29 照站長：PoE2 的公尺換成 Minecraft 的格，1 公尺 = 1.5 格（2026-10-29 先改成 2 格、同一天改成 1.5 格；這次之前 1 公尺 = 1 格，資料檔直接填公尺數當格數）。
 * <p>
 * 資料檔（skills.yml 等）照原作寫公尺，讀進來的時候乘上 {@link #BLOCKS_PER_METRE}；程式裡照 PoE2 公尺寫死的常數用 {@link #metres}。
 * 只有玩家這邊照 PoE2 公尺來的距離換算（範圍半徑、扇形與直線長度、連鎖距離、「x 公尺內」的條件、位移距離…）；
 * 投射物的射程與速度、近戰打擊距離、準心最遠指多遠這些本來就照 Minecraft 調的不動，怪物與 Boss 也不動。
 * 要整體調整只改這一個數字。純邏輯，不依賴 Bukkit。
 */
public final class Units {

    /** 1 公尺幾格。 */
    public static final double BLOCKS_PER_METRE = 1.5;

    private Units() {
    }

    /** PoE2 的 x 公尺是幾格。 */
    public static double metres(final double metres) {
        return metres * BLOCKS_PER_METRE;
    }

    /** 幾格是 PoE2 的幾公尺（說明文字用）。 */
    public static double toMetres(final double blocks) {
        return blocks / BLOCKS_PER_METRE;
    }
}
