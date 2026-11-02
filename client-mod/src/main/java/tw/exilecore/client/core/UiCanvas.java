package tw.exilecore.client.core;

import java.util.List;

/**
 * 2026-11-02 照站長（D 組）：原生畫面畫圖用的最小介面。遊戲裡接 GuiGraphicsExtractor（{@code GuiCanvas}），
 * 預覽圖接 Java2D——兩邊用同一份畫法（{@link TreePainter}、{@link AtlasPainter}），預覽圖看到的就是遊戲裡的樣子。
 * 顏色都是 0xAARRGGBB；座標是 GUI 像素（可以有小數）。
 */
public interface UiCanvas {

    /** 一個方塊（x0,y0 到 x1,y1）。 */
    void rect(double x0, double y0, double x1, double y1, int argb);

    /** 一個轉了 angle（弧度）的方塊，中心 (cx, cy)、寬 w、高 h。 */
    void quad(double cx, double cy, double w, double h, double angle, int argb);

    /** 一行字（scale 倍，左上角在 (x, y)）。 */
    void text(String text, double x, double y, int argb, double scale, boolean shadow);

    /** 一個字串在 scale 1 時有多寬。 */
    int width(String text);

    /** 字的高度（scale 1）。 */
    int fontHeight();

    /** 只畫在這個方塊裡（到 {@link #unclip}）。 */
    void clip(int x0, int y0, int x1, int y1);

    void unclip();

    // ---- 用上面那些組出來的 ----

    default void line(final double x0, final double y0, final double x1, final double y1, final double width, final int argb) {
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double length = Math.hypot(dx, dy);
        if (length < 0.01) {
            return;
        }
        quad((x0 + x1) / 2, (y0 + y1) / 2, length, width, Math.atan2(dy, dx), argb);
    }

    /** 接近圓形的八角形（半徑 r）。 */
    default void dot(final double cx, final double cy, final double r, final int argb) {
        final double side = 2 * r * 0.9239; // 內切的正八角形：兩個方塊疊起來
        if (r < 1.6) {
            rect(cx - r, cy - r, cx + r, cy + r, argb);
            return;
        }
        quad(cx, cy, side, side, 0, argb);
        quad(cx, cy, side, side, Math.PI / 4, argb);
    }

    /** 尖頂朝上的六角形（外接圓半徑 r）：三個轉 0°、60°、120° 的方塊疊起來。 */
    default void hex(final double cx, final double cy, final double r, final int argb) {
        final double w = Math.sqrt(3) * r;
        quad(cx, cy, w, r, 0, argb);
        quad(cx, cy, w, r, Math.PI / 3, argb);
        quad(cx, cy, w, r, -Math.PI / 3, argb);
    }

    /** 六角形的外框。 */
    default void hexOutline(final double cx, final double cy, final double r, final double width, final int argb) {
        for (int i = 0; i < 6; i++) {
            final double[] edge = HexGrid.edge(i, r);
            line(cx + edge[0], cy + edge[1], cx + edge[2], cy + edge[3], width, argb);
        }
    }

    /** 方框（線寬 1）。 */
    default void frame(final double x0, final double y0, final double x1, final double y1, final int argb) {
        rect(x0, y0, x1, y0 + 1, argb);
        rect(x0, y1 - 1, x1, y1, argb);
        rect(x0, y0, x0 + 1, y1, argb);
        rect(x1 - 1, y0, x1, y1, argb);
    }

    /** 有顏色的一行字（每段自己的顏色）；回傳畫了多寬。 */
    default double runs(final List<Rich.Run> runs, final double x, final double y, final double scale, final boolean shadow) {
        double at = x;
        for (final Rich.Run run : runs) {
            text(run.text(), at, y, 0xFF000000 | run.color(), scale, shadow);
            at += width(run.text()) * scale;
        }
        return at - x;
    }

    default int width(final List<Rich.Run> runs) {
        int total = 0;
        for (final Rich.Run run : runs) {
            total += width(run.text());
        }
        return total;
    }
}
