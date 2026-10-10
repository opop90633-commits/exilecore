package tw.exilecore.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 劇情世界的路標（純邏輯）：出營地東門之後，沿著路每 {@link #SPACING} 格在路面鋪一個發光的箭頭，指向路往東（往下一個區域、往 Boss）。
 * 路彎得比較多的地方用斜的箭頭。檢查點的祭壇、Boss 場地（地形壓平的地方）不放；木棧道上也照樣放（換掉木板）。
 * 用跟地形生成同一套函式算位置與高度，所以新世界、舊世界都對得上（區塊載入時才蓋，見 world 模組）。
 */
public final class RoadGuides {

    /** 一格路標方塊。 */
    public record Cell(int x, int y, int z) {
    }

    /** 每隔幾格一個（照 x）。 */
    public static final int SPACING = 32;
    /** 第一個路標（營地東門外）。 */
    static final int FIRST_X = CampaignLayout.ZONE_START_X + 8;
    /** 斜率超過這個用斜的箭頭（tan 22.5°）。 */
    private static final double DIAGONAL = 0.4142;

    private static volatile Map<Long, List<Cell>> byChunk;

    private RoadGuides() {
    }

    /** 全部的路標方塊（照 x 排好）。 */
    public static List<Cell> cells() {
        final List<Cell> cells = new ArrayList<>();
        final int last = CampaignLayout.STAR_X - 40;
        for (int x = FIRST_X; x <= last; x += SPACING) {
            // 放不下（箭頭有一格超出路面）就前後挪幾格試試
            for (int shift = 0; shift <= 6; shift++) {
                List<Cell> arrow = arrowAt(x + shift);
                if (arrow.isEmpty() && shift > 0) {
                    arrow = arrowAt(x - shift);
                }
                if (!arrow.isEmpty()) {
                    cells.addAll(arrow);
                    break;
                }
            }
        }
        return cells;
    }

    /** x 這裡的路標（放不下回傳空的）。 */
    static List<Cell> arrowAt(final int x) {
        final int z = (int) Math.round(CampaignLayout.roadZ(x + 0.5) - 0.5);
        if (CampaignLayout.flatness(x + 0.5, z + 0.5) > 0) {
            return List.of(); // 檢查點的祭壇、Boss 場地
        }
        final double slope = (CampaignLayout.roadZ(x + 2.5) - CampaignLayout.roadZ(x - 1.5)) / 4.0;
        final int dz = slope > DIAGONAL ? 1 : slope < -DIAGONAL ? -1 : 0;
        final List<Cell> cells = new ArrayList<>(9);
        for (final int[] cell : GuideArrow.cells(x, z, 1, dz)) {
            final int cx = cell[0];
            final int cz = cell[1];
            if (!CampaignTerrain.isRoad(cx, cz) || CampaignLayout.flatness(cx + 0.5, cz + 0.5) > 0) {
                return List.of(); // 箭頭有一格不在路面上：這一個不放（不要畫一半）
            }
            final int y = CampaignTerrain.isBoardwalk(cx, cz) ? CampaignTerrain.roadTop(cx) : CampaignTerrain.top(cx, cz);
            cells.add(new Cell(cx, y, cz));
        }
        return cells;
    }

    /** 區塊（x、z 各除以 16）→ 這個區塊裡的路標方塊。第一次用到時算好（之後不會變）。 */
    public static Map<Long, List<Cell>> byChunk() {
        Map<Long, List<Cell>> map = byChunk;
        if (map == null) {
            final Map<Long, List<Cell>> built = new HashMap<>();
            for (final Cell cell : cells()) {
                built.computeIfAbsent(chunkKey(cell.x() >> 4, cell.z() >> 4), key -> new ArrayList<>()).add(cell);
            }
            map = Collections.unmodifiableMap(built);
            byChunk = map;
        }
        return map;
    }

    public static long chunkKey(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
