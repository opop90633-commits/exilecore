package tw.exilecore.client.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 2026-11-02 照站長：左下角的兩塊怎麼排（純 Java，測試用；資源包版 HotbarSides.placeBuffs 是同一套規則）。
 * <ul>
 *   <li>有倒數的增益：右緣貼著生命球左邊，從 bottom 往上一格一格排（一行「名稱 秒數」），超過 top（畫面中間的準心區下面）就往左再開一欄，
 *       最左到 left；全部塞不下先縮小（0.75），還是塞不下最後一格寫「+N」。</li>
 *   <li>固定的光環：畫面最底下一行，從 left 往右排到 right；塞不下先縮小，再塞不下最後寫「+N」。</li>
 * </ul>
 */
public final class SideLayout {

    /** 一格：第幾個、x（增益是這一格的右緣、光環是左緣）、y（字的上緣）。 */
    public record Cell(int index, int x, int y) {
    }

    /** 排的結果：scale = 字的縮放、cells = 放得下的、more = 「+N」的 N（0 = 全部放得下）。 */
    public record Result(float scale, List<Cell> cells, int more) {
    }

    public static final int ROW = 12;
    public static final int GAP = 4;
    public static final float SMALL = 0.75f;

    private SideLayout() {
    }

    /**
     * 增益一欄一欄往上排。
     *
     * @param widths 每一格的寬度（縮放 1）
     * @param right  第一欄的右緣
     * @param bottom 最下面那格的下緣
     * @param top    最上面那格的上緣不能比這裡高
     * @param left   最左到這裡
     */
    public static Result column(final List<Integer> widths, final int right, final int bottom, final int top, final int left) {
        final Result big = columns(widths, 1f, right, bottom, top, left);
        if (big.cells().size() == widths.size()) {
            return big;
        }
        final Result small = columns(widths, SMALL, right, bottom, top, left);
        if (small.cells().size() == widths.size() || small.cells().isEmpty()) {
            return new Result(SMALL, small.cells(), widths.size() - small.cells().size());
        }
        final List<Cell> cells = new ArrayList<>(small.cells());
        cells.removeLast(); // 最後一格寫「+N」
        return new Result(SMALL, cells, widths.size() - cells.size());
    }

    private static Result columns(final List<Integer> widths, final float scale, final int right, final int bottom, final int top,
                                  final int left) {
        final int row = Math.round(ROW * scale);
        final int rows = Math.max(1, (bottom - top) / row);
        final List<Cell> cells = new ArrayList<>();
        int x = right;
        for (int i = 0; i < widths.size(); i += rows) {
            int column = 0;
            for (int k = i; k < Math.min(widths.size(), i + rows); k++) {
                column = Math.max(column, (int) Math.ceil(widths.get(k) * scale));
            }
            if (x - column < left) {
                break;
            }
            for (int k = i; k < Math.min(widths.size(), i + rows); k++) {
                cells.add(new Cell(k, x, bottom - (k - i + 1) * row));
            }
            x -= column + Math.round(GAP * scale);
        }
        return new Result(scale, cells, 0);
    }

    /**
     * 光環一行往右排。
     *
     * @param more 「+N」那格要多寬（縮放 1）
     */
    public static Result strip(final List<Integer> widths, final int moreWidth, final int left, final int right, final int y) {
        for (final float scale : new float[] {1f, SMALL}) {
            final List<Cell> cells = stripCells(widths, scale, left, right, y);
            if (cells.size() == widths.size()) {
                return new Result(scale, cells, 0);
            }
        }
        // 最後寫「+N」：留它的位置
        final List<Cell> cells = stripCells(widths, SMALL, left, right - (int) Math.ceil(moreWidth * SMALL) - GAP, y);
        return new Result(SMALL, cells, widths.size() - cells.size());
    }

    private static List<Cell> stripCells(final List<Integer> widths, final float scale, final int left, final int right, final int y) {
        final List<Cell> cells = new ArrayList<>();
        int x = left;
        for (int i = 0; i < widths.size(); i++) {
            final int w = (int) Math.ceil(widths.get(i) * scale);
            if (x + w > right) {
                break;
            }
            cells.add(new Cell(i, x, y));
            x += w + Math.round(GAP * scale);
        }
        return cells;
    }

    /**
     * 2026-11-02 照站長：增益從左邊往右一欄一欄排（聊天框上面那塊，{@link HudLayout#buffArea}）：每欄由 bottom 往上排到 top，
     * 放不下往右再開一欄（中間空 {@value #GAP}），最右到 right；全部塞不下先縮小（{@value #SMALL}），還是塞不下最後一格寫「+N」。
     * Cell 的 x 是這一格的<b>左緣</b>（同一欄靠左對齊）。
     */
    public static Result columnFromLeft(final List<Integer> widths, final int left, final int bottom, final int top, final int right) {
        final Result big = columnsFromLeft(widths, 1f, left, bottom, top, right);
        if (big.cells().size() == widths.size()) {
            return big;
        }
        final Result small = columnsFromLeft(widths, SMALL, left, bottom, top, right);
        if (small.cells().size() == widths.size() || small.cells().isEmpty()) {
            return new Result(SMALL, small.cells(), widths.size() - small.cells().size());
        }
        final List<Cell> cells = new ArrayList<>(small.cells());
        cells.removeLast(); // 最後一格寫「+N」
        return new Result(SMALL, cells, widths.size() - cells.size());
    }

    private static Result columnsFromLeft(final List<Integer> widths, final float scale, final int left, final int bottom, final int top,
                                          final int right) {
        final int row = Math.round(ROW * scale);
        final int rows = Math.max(1, (bottom - top) / row);
        final List<Cell> cells = new ArrayList<>();
        int x = left;
        for (int i = 0; i < widths.size(); i += rows) {
            int column = 0;
            for (int k = i; k < Math.min(widths.size(), i + rows); k++) {
                column = Math.max(column, (int) Math.ceil(widths.get(k) * scale));
            }
            if (x + column > right) {
                break;
            }
            for (int k = i; k < Math.min(widths.size(), i + rows); k++) {
                cells.add(new Cell(k, x, bottom - (k - i + 1) * row));
            }
            x += column + Math.round(GAP * scale);
        }
        return new Result(scale, cells, 0);
    }

    /** 排好的範圍（GUI 像素；cells 的 x 是左緣、widths 是縮放 1 的寬）：給拖動的編輯畫面畫框用。沒有格子回傳 null。 */
    public static HudLayout.Box bounds(final Result result, final List<Integer> widths) {
        if (result.cells().isEmpty()) {
            return null;
        }
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y1 = Integer.MIN_VALUE;
        final int row = Math.round(ROW * result.scale());
        for (final Cell cell : result.cells()) {
            final int w = (int) Math.ceil(widths.get(cell.index()) * result.scale());
            x0 = Math.min(x0, cell.x());
            y0 = Math.min(y0, cell.y());
            x1 = Math.max(x1, cell.x() + w);
            y1 = Math.max(y1, cell.y() + row);
        }
        return new HudLayout.Box(x0, y0, x1 - x0, y1 - y0);
    }
}
